/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentity;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the deferred removal of the entity ownership lease against the real engine, regionizer, worker
 * pool and barrier, and against the real {@link PersistentEntitySectionManager} callback.
 * <p>
 * A worker that removes an entity it owns must not run the level callback of the removal: it writes the
 * entity's own removal state, the rest of the tick sees a removed entity, and the entity batch barrier runs
 * the callback on the server thread. Every removal runs the real patched guard of the real vanilla entry point
 * on an actual native worker thread of the engine's pool, and the level is a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock whose section manager publishes into a recording
 * {@link LevelCallback}, so the test observes exactly which thread ran which effect and compares the tracking
 * callbacks of a deferred removal with the ones of a server-thread removal.
 */
@Timeout(30)
class RegionEntityRemovalDeferralTest {
    private static final String REGION_PACKAGE = "net.neoforged.neoforge.server.threading.region.";
    private static final String ENTITY_BATCH_TYPE = REGION_PACKAGE + "RegionEntityTickBatch";
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final BlockPos SOURCE_POSITION = new BlockPos(1, 64, 1);
    private static final BlockPos REFERENCE_POSITION = new BlockPos(4, 64, 1);
    /** A position in a region of its own, so the entity there is never a member of the group under test. */
    private static final BlockPos FOREIGN_POSITION = new BlockPos(4096, 64, 0);

    private final Thread ownerThread = Thread.currentThread();
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        // Also shuts the engine down; a test that already shut it down is unaffected.
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void workerRemovesAnOwnedAddedEntityAndTheBarrierRunsTheNormalRemovalPath() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final List<Boolean> removedOnWorker = new CopyOnWriteArrayList<>();
        final List<Boolean> ledgerLiveOnWorker = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                self.discard();
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
            // The rest of the worker tick must see a removed entity while the level storage is untouched.
            removedOnWorker.add(self.isRemoved());
            ledgerLiveOnWorker.add(ledger.isLive(self.getUUID()));
        });
        admit(manager, entity, SOURCE_POSITION);
        // A second entity is removed on the server thread; its storage effects are the reference.
        final TestEntity reference = new TestEntity(level);
        admit(manager, reference, REFERENCE_POSITION);
        // ServerLevel.addEntity registers the entity in the region index right after the admission.
        this.engine.addRegionEntity(entity);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();
        reference.setRemoved(Entity.RemovalReason.DISCARDED);

        assertEquals(List.of(), failures, () -> "the owned removal was rejected: " + failures);
        assertEquals(List.of(true), removedOnWorker, "isRemoved must answer true immediately on the worker");
        assertEquals(List.of(true), ledgerLiveOnWorker,
                "the identity ledger must stay untouched until the barrier");
        assertEquals(Entity.RemovalReason.DISCARDED, entity.getRemovalReason());
        assertEquals(recorder.eventsOf(reference), recorder.eventsOf(entity),
                "the barrier must run exactly the tracking callbacks of a server-thread removal");
        assertFalse(manager.isLoaded(entity.getUUID()), "the removed entity must leave the UUID set");
        assertNull(manager.getEntityGetter().get(entity.getUUID()),
                "the removed entity must leave the visible lookup");
        assertFalse(sectionContains(manager, SOURCE_POSITION, entity),
                "the removed entity must leave its entity section");
        assertEquals(EntityIdentity.State.GONE, ledger.current(entity.getUUID()).state(),
                "the barrier must publish the identity as gone");
        assertFalse(ledger.isLive(entity.getUUID()));
        assertSame(EntityInLevelCallback.NULL, levelCallback(entity),
                "the barrier must clear the level callback of the removed entity");
        assertNull(regionOf(this.engine, entity), "the region index must be retired");
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityRemovals());
        assertEquals(0L, this.engine.diagnostics().repeatedDeferredEntityRemovals());
        assertTrue(this.engine.diagnostics().entityMutationsAllowedByOwnership() >= 3L,
                () -> "discard, remove and setRemoved are three granted removal guard sites, got: "
                        + this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void aSecondRemovalOfTheSameEntityIsANoOpAndCounted() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                self.discard();
                // The vanilla entry point re-enters the removal of an entity that a previous removal
                // marked; both repeats must be no-ops and must not reach the storage a second time.
                self.discard();
                self.remove(Entity.RemovalReason.KILLED);
                self.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        admit(manager, entity, SOURCE_POSITION);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "a repeated removal was rejected: " + failures);
        assertEquals(Entity.RemovalReason.DISCARDED, entity.getRemovalReason(),
                "the first removal reason wins, exactly like the vanilla removal");
        assertEquals(List.of("created", "trackingStart", "tickingStart", "tickingEnd", "trackingEnd", "destroyed"),
                recorder.eventsOf(entity),
                "the storage removal must run exactly once");
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(3L, this.engine.diagnostics().repeatedDeferredEntityRemovals(),
                "every removal of an already removed entity is a counted no-op");
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityRemovals());
    }

    @Test
    void removalOfAForeignEntityIsRejectedBeforeAnyMutation() {
        this.installEngine(2, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final TestEntity foreign = new TestEntity(level);
        admit(manager, foreign, FOREIGN_POSITION);
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final TestEntity owned = new TestEntity(level, self -> recordAttempt(attempts, foreign::discard));
        admit(manager, owned, SOURCE_POSITION);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(owned));
        assertTrue(plan.accept(foreign));
        plan.executeAndAwait();

        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).contains("Entity.discard"),
                () -> "the rejection must name the operation, got: " + attempts.get(0));
        assertFalse(foreign.isRemoved(), "a rejected removal must not remove the entity");
        assertNull(foreign.getRemovalReason(), "a rejected removal must not change the removal reason");
        assertTrue(manager.isLoaded(foreign.getUUID()), "a rejected removal must not touch the storage");
        assertTrue(sectionContains(manager, FOREIGN_POSITION, foreign),
                "a rejected removal must not change the section membership");
        assertEquals(0L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertTrue(this.engine.diagnostics().entityMutationsRejectedByOwnership() >= 1L);
    }

    @Test
    void removalOfAnOwnedEntityOutsideALeaseIsRejected() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity, SOURCE_POSITION);

        final AtomicReference<Throwable> rejection = new AtomicReference<>();
        final AtomicBoolean isWorker = new AtomicBoolean();
        runOnNativeWorker(() -> {
            isWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            rejection.set(assertThrows(IllegalStateException.class,
                    () -> entity.setRemoved(Entity.RemovalReason.DISCARDED)));
        });

        assertTrue(isWorker.get(), "the probe must run on a native region worker");
        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Entity.setRemoved"),
                () -> "the rejection must name the operation, got: " + rejection.get().getMessage());
        assertFalse(entity.isRemoved(), "a rejected removal must not remove the entity");
        assertNull(entity.getRemovalReason(), "a rejected removal must not change the removal reason");
        assertTrue(manager.isLoaded(entity.getUUID()), "a rejected removal must not touch the storage");
        assertTrue(sectionContains(manager, SOURCE_POSITION, entity));
        assertEquals(List.of("created", "trackingStart", "tickingStart"), recorder.eventsOf(entity));
        assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership(),
                "a worker removal without a lease is a storage rejection, not an ownership rejection");
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void aLeaseWhoseBatchAlreadyFinishedKeepsTheRemovalRefusal() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity, SOURCE_POSITION);

        // A batch that already released its barrier can never apply a deferred removal, so the lease must
        // keep the storage refusal instead of removing an entity nobody publishes.
        final AtomicReference<Throwable> rejection = new AtomicReference<>();
        try {
            final Object batch = newEntityBatch(level);
            final Object group = addGroup(batch, level, entity);
            submitAndAwait(batch);
            finish(batch);
            final AtomicBoolean isWorker = new AtomicBoolean();
            runOnNativeWorker(() -> {
                isWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                try (RegionEntityOwnershipScope lease = openLease(level, group)) {
                    rejection.set(assertThrows(IllegalStateException.class,
                            () -> entity.setRemoved(Entity.RemovalReason.DISCARDED)));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            });
            assertTrue(isWorker.get(), "the probe must run on a native region worker");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }

        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Entity.setRemoved"),
                () -> "a finished batch must not grant the removal, got: " + rejection.get().getMessage());
        assertFalse(entity.isRemoved(), "a rejected removal must not remove the entity");
        assertNull(entity.getRemovalReason(), "a rejected removal must not change the removal reason");
        assertTrue(manager.isLoaded(entity.getUUID()), "a rejected removal must not touch the storage");
        assertEquals(List.of("created", "trackingStart", "tickingStart"), recorder.eventsOf(entity));
        assertEquals(0L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void aWorkerSideLootSpawnIsDeferredAndAppliedWhileTheRemovalCompletes() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        // The admission of the loot runs on the server thread through the level's section manager, exactly
        // like the removal of the dying entity runs through its callback.
        setField(ServerLevel.class, level, "entityManager", manager);
        final List<Boolean> acceptedOnWorker = new CopyOnWriteArrayList<>();
        final List<Entity> spawned = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                // The vanilla death spawns its loot on the tick that kills it. The new item entity is
                // constructed on the worker inside the leased region and admitted at the barrier.
                final TestEntity loot = new TestEntity(self.level());
                loot.setPos(SOURCE_POSITION.getX() + 0.5, SOURCE_POSITION.getY(), SOURCE_POSITION.getZ() + 0.5);
                spawned.add(loot);
                acceptedOnWorker.add(self.level().addFreshEntity(loot));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
            self.discard();
        });
        admit(manager, entity, SOURCE_POSITION);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the worker-side loot spawn was rejected: " + failures);
        assertEquals(List.of(true), acceptedOnWorker, "the loot spawn must be accepted for this tick");
        assertTrue(manager.isLoaded(spawned.get(0).getUUID()),
                "the barrier must admit the loot of the dying entity");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsRecorded());
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityAdditions());
        assertEquals(0L, this.engine.diagnostics().entityAddRejectedOnWorker(),
                "a deferred loot spawn is no longer a rejected entity add");
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied(),
                "the removal must still complete next to the deferred admission");
        assertFalse(manager.isLoaded(entity.getUUID()));
        assertEquals(EntityIdentity.State.GONE, manager.identityLedger().current(entity.getUUID()).state());
    }

    @Test
    void theBarrierAppliesTheRemovalBeforeTheOwnershipPublication() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final List<Integer> regionAtRemoval = new CopyOnWriteArrayList<>();
        final List<Long> sectionAtRemoval = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> self.discard());
        admit(manager, entity, SOURCE_POSITION);
        // The region index tracks the entity before the tick, and the recording callback observes it from
        // inside the removal: the ownership publication of the same barrier has not run yet.
        this.engine.addRegionEntity(entity);
        final EntityInLevelCallback managerCallback = levelCallback(entity);
        setField(Entity.class, entity, "levelCallback", new RecordingLevelCallback(managerCallback, () -> {
            regionAtRemoval.add(regionOf(this.engine, entity));
            sectionAtRemoval.add(sectionKeyOf(managerCallback));
        }));

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(0), regionAtRemoval,
                "the removal must run before the ownership publication retires the region index");
        assertEquals(List.of(SectionPos.of(SOURCE_POSITION).asLong()), sectionAtRemoval,
                "the removal must run against the section the entity is still published in");
        assertNull(regionOf(this.engine, entity),
                "the ownership publication of the same barrier must retire the region index afterwards");
        assertFalse(manager.isLoaded(entity.getUUID()));
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied());
    }

    @Test
    void aShutDownEngineDropsTheDeferredRemovalWithoutTouchingStorage() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity, SOURCE_POSITION);
        final Object batch;
        try {
            batch = deferredRemovalBatch(level, entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        this.engine.shutdown();

        this.applyDeferredRemovals(batch);

        assertEquals(List.of("created", "trackingStart", "tickingStart"), recorder.eventsOf(entity),
                "a dropped removal must not run any storage callback");
        assertTrue(manager.isLoaded(entity.getUUID()), "a dropped removal must not touch the storage");
        assertTrue(sectionContains(manager, SOURCE_POSITION, entity),
                "a dropped removal must not change the section membership");
        assertEquals(EntityIdentity.State.TRACKED, manager.identityLedger().current(entity.getUUID()).state(),
                "a dropped removal must not publish the identity as gone");
        assertEquals(0L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntityRemovals());
    }

    @Test
    void aRemovalTheServerThreadAlreadyAppliedIsANoOp() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity, SOURCE_POSITION);
        final Object batch;
        try {
            batch = deferredRemovalBatch(level, entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        // The server thread removed the entity between the deferred removal and the barrier.
        entity.setRemoved(Entity.RemovalReason.DISCARDED);

        this.applyDeferredRemovals(batch);

        assertEquals(List.of("created", "trackingStart", "tickingStart", "tickingEnd", "trackingEnd", "destroyed"),
                recorder.eventsOf(entity),
                "the storage removal of the server thread must not run a second time");
        assertFalse(manager.isLoaded(entity.getUUID()));
        assertEquals(0L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(1L, this.engine.diagnostics().repeatedDeferredEntityRemovals(),
                "a removal the server thread already applied is a counted no-op");
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityRemovals());
    }

    private void installEngine(int workerThreads, boolean entityTickWorkers) {
        // Only the entity tick mechanics tests construct an engine with region-owned entity storage.
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, entityTickWorkers,
                false, false, false, RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // The seed of an entity group random source is derived from the world seed, the game time and the
        // dimension of its level.
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        // The recorded transfer of the ownership handoff needs an identity ledger and the server thread.
        doReturn(new EntityIdentityLedger()).when(level).entityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == this.ownerThread);
        doReturn(server).when(level).getServer();
        // The target chunk acquisition of a deferred section update is server-owned storage.
        doAnswer(invocation -> null).when(level).getChunk(anyInt(), anyInt());
        // The lease containment of a deferred entity addition asks the level for its physical region size.
        // The removal path itself does not depend on the partitions, so the level reports the fixed edge
        // length the lease containment uses.
        doReturn(3).when(level).entitySectionRegionChunkSize();
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    /**
     * Places {@code entity} at {@code position}, admits it to the real section manager, marks it as added to
     * the level like {@code ServerLevel.addEntity} does and makes its chunk accessible and ticking, so the
     * admission runs the same tracking callbacks a loaded entity runs.
     */
    private static void admit(PersistentEntitySectionManager<Entity> manager, Entity entity, BlockPos position) {
        entity.setPos(position.getX(), position.getY(), position.getZ());
        assertTrue(manager.addNewEntityWithoutEvent(entity), "the entity must be admitted to the section manager");
        entity.onAddedToLevel();
        assertTrue(entity.isAddedToLevel());
        assertEquals("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback",
                levelCallback(entity).getClass().getName());
        manager.updateChunkStatus(new ChunkPos(SectionPos.posToSectionCoord(position.getX()), SectionPos.posToSectionCoord(position.getZ())), Visibility.TICKING);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager(LevelCallback<Entity> callbacks) {
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        // A chunk that becomes accessible is queued for its entity load; the test never loads one.
        when(storage.loadEntities(any())).thenReturn(CompletableFuture.completedFuture(null));
        return new PersistentEntitySectionManager<>(
                Entity.class,
                callbacks,
                storage,
                RegionThreadingHooks::assertServerEntityStorageAccess);
    }

    /**
     * Builds a real entity batch for {@code level} that carries one deferred removal of {@code entity}, the
     * record a worker writes when it removes an entity it owns.
     */
    private static Object deferredRemovalBatch(ServerLevel level, Entity entity) throws ReflectiveOperationException {
        final Object batch = newEntityBatch(level);
        final Method defer = Class.forName(ENTITY_BATCH_TYPE)
                .getDeclaredMethod("deferRemoval", Entity.class, EntityInLevelCallback.class, Entity.RemovalReason.class);
        defer.setAccessible(true);
        assertTrue((Boolean) defer.invoke(batch, entity, levelCallback(entity), Entity.RemovalReason.DISCARDED),
                "the batch must accept the deferred removal");
        return batch;
    }

    private static Object newEntityBatch(ServerLevel level) throws ReflectiveOperationException {
        final Constructor<?> constructor = Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class);
        constructor.setAccessible(true);
        return constructor.newInstance(level);
    }

    /**
     * Runs the groups of {@code batch} and waits for them, so only the release of its barrier is missing.
     */
    private void submitAndAwait(Object batch) throws ReflectiveOperationException {
        final Method submitAndAwait = Class.forName(REGION_PACKAGE + "RegionTickBatch")
                .getDeclaredMethod("submitAndAwait", RegionWorkerExecutor.class, RegionTickBarrier.class);
        submitAndAwait.setAccessible(true);
        submitAndAwait.invoke(batch, this.workerPool(), PARKING_BARRIER);
    }

    /**
     * Releases the barrier of {@code batch}, so it can never apply a deferred removal again.
     */
    private static void finish(Object batch) throws ReflectiveOperationException {
        final Method finish = Class.forName(REGION_PACKAGE + "RegionTickBatch").getDeclaredMethod("finish");
        finish.setAccessible(true);
        finish.invoke(batch);
    }

    private void applyDeferredRemovals(Object batch) {
        try {
            final Method apply = RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntityRemovals", Class.forName(ENTITY_BATCH_TYPE));
            apply.setAccessible(true);
            apply.invoke(this.engine, batch);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static EntityInLevelCallback levelCallback(Entity entity) {
        try {
            return (EntityInLevelCallback) getField(Entity.class, entity, "levelCallback");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return the section key {@code callback} currently holds}
     */
    private static long sectionKeyOf(EntityInLevelCallback callback) {
        try {
            return (long) getField(callback.getClass(), callback, "currentSectionKey");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return true when the entity section of {@code position} still holds {@code entity}}
     */
    @SuppressWarnings("unchecked")
    private static boolean sectionContains(PersistentEntitySectionManager<Entity> manager, BlockPos position, Entity entity) {
        try {
            final EntitySectionStorage<Entity> storage = (EntitySectionStorage<Entity>) getField(
                    PersistentEntitySectionManager.class, manager, "sectionStorage");
            final EntitySection<Entity> section = storage.getSection(SectionPos.asLong(position));
            return section != null && section.getEntities().anyMatch(candidate -> candidate == entity);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return the x coordinate of the region that tracks {@code entity}, or {@code null} when the region
     * index no longer tracks it}
     */
    @SuppressWarnings("unchecked")
    private static Integer regionOf(RegionThreadingEngine engine, Entity entity) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
            entitiesField.setAccessible(true);
            final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
            if (region == null) {
                return null;
            }
            final Method coordinateMethod = region.getClass().getDeclaredMethod("coordinate");
            coordinateMethod.setAccessible(true);
            return ((RegionCoordinate) coordinateMethod.invoke(region)).regionX();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void recordAttempt(List<String> attempts, Runnable action) {
        try {
            action.run();
            attempts.add("granted");
        } catch (IllegalStateException rejected) {
            attempts.add(String.valueOf(rejected.getMessage()));
        }
    }

    /**
     * Runs {@code task} on a real worker thread of the engine's pool and rethrows its failure.
     */
    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("entity removal deferral test", () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the test task");
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the worker task did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        final Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
    }

    /**
     * Adds a real group of {@code members} to {@code batch} and returns it.
     */
    @SuppressWarnings("unchecked")
    private static Object addGroup(Object batch, ServerLevel level, Entity... members) throws ReflectiveOperationException {
        final Class<?> regionClass = Class.forName(REGION_PACKAGE + "RegionState");
        final Constructor<?> regionConstructor = regionClass.getDeclaredConstructor(RegionCoordinate.class);
        regionConstructor.setAccessible(true);
        final Object region = regionConstructor.newInstance(new RegionCoordinate(level, 0, 0));
        final Method addGroup = batch.getClass().getDeclaredMethod("addGroup", regionClass, List.class, Function.class);
        addGroup.setAccessible(true);
        return addGroup.invoke(batch, region, List.of(members), (Function<Object, Runnable>) ignored -> () -> {});
    }

    /**
     * Opens the real lease the engine opens around one worker tick loop, for {@code group}.
     */
    private static RegionEntityOwnershipScope openLease(ServerLevel level, Object group) throws ReflectiveOperationException {
        final Method open = RegionEntityOwnershipScope.class.getDeclaredMethod("open", RegionCoordinate.class,
                Class.forName(REGION_PACKAGE + "RegionOwnershipLeaseGroup"));
        open.setAccessible(true);
        return (RegionEntityOwnershipScope) open.invoke(null, new RegionCoordinate(level, 0, 0), group);
    }

    private RegionWorkerExecutor workerPool() {
        try {
            final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
            field.setAccessible(true);
            return (RegionWorkerExecutor) field.get(this.engine);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object getField(Class<?> declaringClass, Object target, String name) throws ReflectiveOperationException {
        final Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> declaringClass, Object target, String name, Object value) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }

    /**
     * Level callback of the real section manager that records every tracking callback per entity, so a
     * deferred removal can be compared with a server-thread removal.
     */
    private static final class TrackingRecorder implements LevelCallback<Entity> {
        private final Map<Entity, List<String>> events = Collections.synchronizedMap(new IdentityHashMap<>());

        @Override
        public void onCreated(Entity entity) {
            this.record(entity, "created");
        }

        @Override
        public void onDestroyed(Entity entity) {
            this.record(entity, "destroyed");
        }

        @Override
        public void onTickingStart(Entity entity) {
            this.record(entity, "tickingStart");
        }

        @Override
        public void onTickingEnd(Entity entity) {
            this.record(entity, "tickingEnd");
        }

        @Override
        public void onTrackingStart(Entity entity) {
            this.record(entity, "trackingStart");
        }

        @Override
        public void onTrackingEnd(Entity entity) {
            this.record(entity, "trackingEnd");
        }

        @Override
        public void onSectionChange(Entity entity) {
            this.record(entity, "sectionChange");
        }

        List<String> eventsOf(Entity entity) {
            return List.copyOf(this.events.getOrDefault(entity, List.of()));
        }

        private void record(Entity entity, String event) {
            this.events.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(event);
        }
    }

    /**
     * Level callback that runs a recording action and then delegates to the real section-manager callback,
     * so a test observes the state the barrier sees at the moment the removal runs.
     */
    private record RecordingLevelCallback(EntityInLevelCallback delegate, Runnable beforeRemoval) implements EntityInLevelCallback {
        @Override
        public void onMove() {
            this.delegate.onMove();
        }

        @Override
        public void onRemove(Entity.RemovalReason reason) {
            this.beforeRemoval.run();
            this.delegate.onRemove(reason);
        }
    }

    /**
     * Real server entity used by the removal deferral tests. It is constructed on the test thread and its
     * {@code tick()} performs the guarded vanilla calls on the region worker.
     */
    private static final class TestEntity extends Entity {
        private final Consumer<TestEntity> tickAction;

        private TestEntity(Level level) {
            this(level, entity -> {});
        }

        private TestEntity(Level level, Consumer<TestEntity> tickAction) {
            super(EntityType.ITEM, level);
            this.tickAction = tickAction;
        }

        @Override
        public void tick() {
            this.tickAction.accept(this);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
