/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
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
 * Exercises the deferred section seam of the entity ownership lease against the real engine, regionizer,
 * worker pool and barrier, and against the real {@link PersistentEntitySectionManager} callback.
 * <p>
 * A worker that moves an entity added to its level must not publish the entity section or acquire the
 * target chunk: both are server-owned storage. The worker records the level callback the inline vanilla
 * move would have invoked plus the section of the position the move reached, and the entity batch barrier
 * applies both effects on the server thread. Every mutation runs the real patched guard of the real vanilla
 * entry point on an actual native worker thread of the engine's pool, and the level is a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock whose chunk acquisition is recorded, so the test
 * observes exactly which thread ran which effect.
 */
@Timeout(30)
class RegionEntitySectionDeferralTest {
    private static final String REGION_PACKAGE = "net.neoforged.neoforge.server.threading.region.";
    private static final String ENTITY_BATCH_TYPE = REGION_PACKAGE + "RegionEntityTickBatch";
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final BlockPos SOURCE_POSITION = new BlockPos(1, 64, 1);
    private static final BlockPos TARGET_POSITION = new BlockPos(4096, 64, 0);
    private static final BlockPos FAR_POSITION = new BlockPos(8192, 64, 0);

    private final Thread ownerThread = Thread.currentThread();
    private final List<ChunkPos> chunkRequests = new CopyOnWriteArrayList<>();
    private final List<String> chunkRequestThreads = new CopyOnWriteArrayList<>();
    /** Runs on the server thread inside the recorded chunk acquisition of the level mock. */
    private Consumer<ChunkPos> chunkRequestObserver = ignored -> {};

    private RegionThreadingEngine engine;
    private EntityIdentityLedger levelLedger;

    @AfterEach
    void shutdownEngine() {
        // Also shuts the engine down; a test that already shut it down is unaffected.
        RegionThreadingManager.clear();
        this.engine = null;
        this.levelLedger = null;
        this.chunkRequestObserver = ignored -> {};
        this.chunkRequests.clear();
        this.chunkRequestThreads.clear();
    }

    @Test
    void workerMovesAnAddedEntityAndTheBarrierPublishesItsSection() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<Long> sectionsOnWorker = new CopyOnWriteArrayList<>();
        final List<Vec3> positionsOnWorker = new CopyOnWriteArrayList<>();
        final List<Throwable> storageReads = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                self.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ());
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
            positionsOnWorker.add(self.position());
            sectionsOnWorker.add(sectionKey(self));
            // The section storage itself stays server-owned: the worker may not even read it.
            try {
                manager.isLoaded(self.getUUID());
                storageReads.add(new AssertionError("a worker read the section manager"));
            } catch (IllegalStateException rejected) {
                storageReads.add(rejected);
            }
        });
        admit(manager, entity);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the owned move was rejected: " + failures);
        assertEquals(List.of(new Vec3(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ())), positionsOnWorker,
                "the worker must update the position itself");
        assertEquals(List.of(sectionKeyOf(SOURCE_POSITION)), sectionsOnWorker,
                "the section membership must stay the source section until the barrier");
        assertEquals(1, storageReads.size());
        assertTrue(storageReads.get(0) instanceof IllegalStateException,
                () -> "the section manager must stay server-owned, got: " + storageReads.get(0));

        assertEquals(sectionKeyOf(TARGET_POSITION), sectionKey(entity),
                "the barrier must publish the section of the final position");
        assertEquals(List.of(chunkOf(TARGET_POSITION)), this.chunkRequests,
                "the barrier must acquire the target chunk of the move");
        assertEquals(List.of(this.ownerThread.getName()), this.chunkRequestThreads,
                "the chunk acquisition must run on the server thread");
        assertTrue(manager.isLoaded(entity.getUUID()), "the published entity must stay admitted");
        assertEquals(1L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void deferredSectionUpdateIsAppliedBeforeTheOwnershipHandoff() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level, self -> {
            self.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ());
            this.engine.moveRegionEntity(self);
            // The live position keeps moving after the ownership destination was captured, so a handoff
            // published from the live position would land in the far region instead of the captured one.
            self.setPosRaw(FAR_POSITION.getX(), FAR_POSITION.getY(), FAR_POSITION.getZ());
        });
        admit(manager, entity);
        this.registerIdentity(entity);
        final List<Integer> regionAtChunkAcquisition = new CopyOnWriteArrayList<>();
        this.chunkRequestObserver = ignored -> regionAtChunkAcquisition.add(regionX(this.engine, entity));
        // The recorded transfer commits into a live destination region, so the target region must exist.
        this.engine.addRegionSection(level, SectionPos.posToSectionCoord(TARGET_POSITION.getX()), SectionPos.posToSectionCoord(TARGET_POSITION.getZ()));

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(0, 0), regionAtChunkAcquisition,
                "the section publication must run before the ownership handoff commits a new region");
        assertEquals(16, regionX(this.engine, entity),
                "the committed destination captured at defer time must win over the live position");
        assertEquals(sectionKeyOf(FAR_POSITION), sectionKey(entity),
                "the section publication must reconcile the membership with the final position");
        assertEquals(List.of(chunkOf(TARGET_POSITION), chunkOf(FAR_POSITION)), this.chunkRequests,
                "every deferred move must acquire its target chunk");
        assertEquals(2L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
        assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                "the handoff must be committed through the recorded transfer");
        assertEquals(1, this.engine.rememberedEntityTransferResultCount());
    }

    @Test
    void movingAForeignAddedEntityIsRejectedBeforeAnyMutation() {
        this.installEngine(2, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity foreign = new TestEntity(level);
        admit(manager, foreign, TARGET_POSITION);
        final List<Throwable> foreignAttempts = new CopyOnWriteArrayList<>();
        final TestEntity owned = new TestEntity(level, self -> {
            try {
                foreign.setPosRaw(FAR_POSITION.getX(), FAR_POSITION.getY(), FAR_POSITION.getZ());
                foreignAttempts.add(new AssertionError("a worker moved an entity of another group"));
            } catch (IllegalStateException rejected) {
                foreignAttempts.add(rejected);
            }
        });
        admit(manager, owned);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(owned));
        assertTrue(plan.accept(foreign));
        plan.executeAndAwait();

        assertEquals(1, foreignAttempts.size());
        assertTrue(foreignAttempts.get(0) instanceof IllegalStateException,
                () -> "a foreign entity move must be rejected, got: " + foreignAttempts.get(0));
        assertTrue(foreignAttempts.get(0).getMessage().contains("Entity.setPosRaw"),
                () -> "the rejection must name the operation, got: " + foreignAttempts.get(0).getMessage());
        assertEquals(TARGET_POSITION, foreign.blockPosition(), "a rejected move must not move the entity");
        assertEquals(sectionKeyOf(TARGET_POSITION), sectionKey(foreign),
                "a rejected move must not change the section membership");
        assertEquals(List.of(), this.chunkRequests, "a rejected move must not acquire a chunk");
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void removalAndCrossLevelTeleportOfAnOwnedAddedEntityStayRejected() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            recordAttempt(attempts, self::discard);
            recordAttempt(attempts, () -> self.remove(Entity.RemovalReason.KILLED));
            recordAttempt(attempts, () -> self.setRemoved(Entity.RemovalReason.DISCARDED));
            recordAttempt(attempts, () -> self.teleportTo(level, FAR_POSITION.getX(), FAR_POSITION.getY(), FAR_POSITION.getZ(), Set.of(), 0.0F, 0.0F));
        });
        admit(manager, entity);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(4, attempts.size());
        assertTrue(attempts.get(0).contains("Entity.discard"), attempts.toString());
        assertTrue(attempts.get(1).contains("Entity.remove"), attempts.toString());
        assertTrue(attempts.get(2).contains("Entity.setRemoved"), attempts.toString());
        assertTrue(attempts.get(3).contains("Entity.teleportTo"), attempts.toString());
        assertFalse(entity.isRemoved(), "a rejected removal must not remove the entity");
        assertNull(entity.getRemovalReason(), "a rejected removal must not change the removal reason");
        assertEquals(SOURCE_POSITION, entity.blockPosition(), "a rejected teleport must not move the entity");
        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity));
        assertEquals(List.of(), this.chunkRequests, "a rejected mutation must not defer a chunk acquisition");
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(4L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
    }

    @Test
    void aClosedLevelDropsTheDeferredSectionUpdateWithoutTouchingStorage() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);
        final Object batch = deferredSectionBatch(level, entity, TARGET_POSITION);
        // A level clear cannot legally interleave with a published batch - it waits for the batch to
        // finish - so the test retires the level identity and then runs the barrier step.
        this.engine.clearLevelRegionTasks(level);

        this.applyDeferredSectionUpdates(batch);

        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity),
                "a dropped section update must not publish the section");
        assertEquals(List.of(), this.chunkRequests, "a dropped section update must not acquire a chunk");
        assertTrue(manager.isLoaded(entity.getUUID()), "a dropped section update must not touch the storage");
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
    }

    @Test
    void aShutDownEngineDropsTheDeferredSectionUpdateWithoutTouchingStorage() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);
        final Object batch = deferredSectionBatch(level, entity, TARGET_POSITION);
        this.engine.shutdown();

        this.applyDeferredSectionUpdates(batch);

        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity),
                "a dropped section update must not publish the section");
        assertEquals(List.of(), this.chunkRequests, "a dropped section update must not acquire a chunk");
        assertTrue(manager.isLoaded(entity.getUUID()), "a dropped section update must not touch the storage");
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
    }

    @Test
    void aRemovedEntityDropsTheDeferredSectionUpdateWithoutTouchingStorage() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);
        final Object batch = deferredSectionBatch(level, entity, TARGET_POSITION);
        // The server thread removed the entity between the deferred move and the barrier.
        entity.setRemoved(Entity.RemovalReason.DISCARDED);

        this.applyDeferredSectionUpdates(batch);

        assertEquals(List.of(), this.chunkRequests, "a dropped section update must not acquire a chunk");
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
    }

    @Test
    void aLeaseWhoseBatchAlreadyFinishedKeepsTheRefusal() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);

        // A batch that already released its barrier can never apply a deferred section update, so the lease
        // must keep the storage refusal instead of letting the worker mutate a position nobody publishes.
        final Object batch = newEntityBatch(level);
        final Object group = addGroup(batch, level, entity);
        submitAndAwait(batch);
        finish(batch);

        final AtomicReference<Throwable> rejection = new AtomicReference<>();
        final AtomicBoolean isWorker = new AtomicBoolean();
        runOnNativeWorker(() -> {
            isWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            try (RegionEntityOwnershipScope lease = openLease(level, group)) {
                rejection.set(assertThrows(IllegalStateException.class,
                        () -> entity.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ())));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        });

        assertTrue(isWorker.get(), "the probe must run on a native region worker");
        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Entity.setPosRaw"),
                () -> "a finished batch must not grant the move, got: " + rejection.get().getMessage());
        assertEquals(SOURCE_POSITION, entity.blockPosition(), "a rejected move must not move the entity");
        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity));
        assertEquals(List.of(), this.chunkRequests);
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void aFailingSectionUpdateDoesNotDropTheUpdatesQueuedBehindIt() {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity failing = new TestEntity(level, self -> self.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ()));
        admit(manager, failing);
        final EntityInLevelCallback failingManagerCallback = levelCallback(failing);
        // A level callback whose publication fails, like a modded section listener that throws while the
        // barrier publishes the section of the moved entity.
        setField(Entity.class, failing, "levelCallback", new FailingLevelCallback());
        final TestEntity first = new TestEntity(level, self -> self.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ()));
        admit(manager, first);
        final TestEntity second = new TestEntity(level, self -> self.setPosRaw(FAR_POSITION.getX(), FAR_POSITION.getY(), FAR_POSITION.getZ()));
        admit(manager, second);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(failing));
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));

        // The failing publication is reported to the batch, but it must not cost the updates behind it.
        final IllegalStateException failure = assertThrows(IllegalStateException.class, plan::executeAndAwait,
                "the publication failure must be reported to the batch");
        assertEquals("modded section listener failed", failure.getMessage(),
                "the batch must report the publication failure itself");

        assertEquals(2L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied(),
                "the updates queued behind the failing one must still be applied");
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKeyOf(failingManagerCallback),
                "the failing update must not publish its section");
        assertEquals(sectionKeyOf(TARGET_POSITION), sectionKey(first));
        assertEquals(sectionKeyOf(FAR_POSITION), sectionKey(second));
        assertEquals(List.of(chunkOf(TARGET_POSITION), chunkOf(FAR_POSITION)), this.chunkRequests,
                "the failing update must not acquire a chunk, the ones behind it must");
    }

    @Test
    void shutdownClosesTheSectionSeamOfARunningBatchAndRefusesTheNextMove() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);

        // A batch that is still running: its group is not complete and its seam is open, so a worker move
        // would be granted. One update already waits, so the release is observable.
        final Object batch = newEntityBatch(level);
        final Object group = addGroup(batch, level, entity);
        deferSectionUpdate(batch, entity, levelCallback(entity), TARGET_POSITION);
        activeTickBatches(this.engine).add(batch);

        final CountDownLatch shutdownStarted = new CountDownLatch(1);
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdownThread = new Thread(() -> {
            shutdownStarted.countDown();
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "section seam shutdown test");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
        try {
            assertTrue(await(shutdownStarted), "the shutdown thread must start");
            assertTrue(awaitSectionSeamClosed(batch),
                    "a shutdown must close the deferred section seam of a batch that is still running");
            assertEquals(1L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates(),
                    "the released update must be counted as dropped");
            assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());

            // The worker still holds the lease of that batch, but the seam is closed: the move is refused
            // before any mutation instead of being granted and dropped.
            final AtomicReference<Throwable> rejection = new AtomicReference<>();
            final AtomicBoolean isWorker = new AtomicBoolean();
            runOnNativeWorker(() -> {
                isWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                try (RegionEntityOwnershipScope lease = openLease(level, group)) {
                    rejection.set(assertThrows(IllegalStateException.class,
                            () -> entity.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ())));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            });

            assertTrue(isWorker.get(), "the probe must run on a native region worker");
            assertNotNull(rejection.get());
            assertTrue(rejection.get().getMessage().contains("Entity.setPosRaw"),
                    () -> "a shut-down batch must not grant the move, got: " + rejection.get().getMessage());
            assertEquals(SOURCE_POSITION, entity.blockPosition(), "a rejected move must not move the entity");
            assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity));
            assertEquals(List.of(), this.chunkRequests, "a closed seam must not acquire a chunk");
            assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        } finally {
            // Release the barrier so the shutdown thread can finish, whatever the assertions above did.
            try {
                submitAndAwait(batch);
            } finally {
                finish(batch);
                activeTickBatches(this.engine).remove(batch);
            }
            join(shutdownThread);
        }
        assertFalse(shutdownThread.isAlive(), "the shutdown must complete");
        assertNull(shutdownFailure.get(), () -> "the shutdown failed: " + shutdownFailure.get());
    }

    @Test
    void entityTickWorkersOffDefersNothingAndKeepsTheStorageGuard() {
        this.installEngine(1, false);
        final ServerLevel level = this.mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(level);
        admit(manager, entity);

        assertNull(this.engine.beginEntityTickPlan(level, ignored -> true, PARKING_BARRIER),
                "entity tick workers are off, so no batch and no lease may start");

        final AtomicBoolean isWorker = new AtomicBoolean();
        final AtomicReference<Throwable> rejection = new AtomicReference<>();
        runOnNativeWorker(() -> {
            isWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            rejection.set(assertThrows(IllegalStateException.class,
                    () -> entity.setPosRaw(TARGET_POSITION.getX(), TARGET_POSITION.getY(), TARGET_POSITION.getZ())));
        });

        assertTrue(isWorker.get());
        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Entity.setPosRaw"),
                () -> "the default-off guard must reject like before, got: " + rejection.get().getMessage());
        assertEquals(SOURCE_POSITION, entity.blockPosition(), "a rejected move must not move the entity");
        assertEquals(sectionKeyOf(SOURCE_POSITION), sectionKey(entity));
        assertEquals(List.of(), this.chunkRequests);
        assertEquals(0L, this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntitySectionUpdates());
    }

    /**
     * Creates the engine and installs it as the active region threading implementation, so the entity tick
     * hooks of the level reach it exactly like they do on a server.
     */
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
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        // The recorded transfer of the ownership handoff needs an identity ledger and the server thread.
        this.levelLedger = new EntityIdentityLedger();
        doReturn(this.levelLedger).when(level).entityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == this.ownerThread);
        doReturn(server).when(level).getServer();
        // The chunk acquisition of a deferred move is server-owned storage: the test records the thread and
        // the chunk of every acquisition instead of loading one.
        doAnswer(invocation -> {
            final ChunkPos position = new ChunkPos(invocation.<Integer>getArgument(0), invocation.<Integer>getArgument(1));
            this.chunkRequests.add(position);
            this.chunkRequestThreads.add(Thread.currentThread().getName());
            this.chunkRequestObserver.accept(position);
            return null;
        }).when(level).getChunk(anyInt(), anyInt());
        return level;
    }

    private void admit(PersistentEntitySectionManager<Entity> manager, Entity entity) {
        this.admit(manager, entity, SOURCE_POSITION);
    }

    /**
     * Places {@code entity} at {@code position}, admits it to the real section manager and marks it as
     * added to the level, exactly like {@code ServerLevel.addEntity} does after the admission.
     */
    private void admit(PersistentEntitySectionManager<Entity> manager, Entity entity, BlockPos position) {
        entity.setPos(position.getX(), position.getY(), position.getZ());
        assertTrue(manager.addNewEntityWithoutEvent(entity), "the entity must be admitted to the section manager");
        entity.onAddedToLevel();
        assertTrue(entity.isAddedToLevel());
        assertEquals("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback",
                levelCallback(entity).getClass().getName());
    }

    /**
     * Registers {@code entity} in the level identity ledger, so the ownership handoff of the barrier can be
     * recorded as a transfer instead of falling back to the live position.
     */
    private void registerIdentity(Entity entity) {
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, this.levelLedger.admit(entity));
        this.levelLedger.publishAdmitted(entity);
        this.levelLedger.publishTracked(entity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager() {
        return new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback) mock(LevelCallback.class),
                mock(EntityPersistentStorage.class),
                RegionThreadingHooks::assertServerEntityStorageAccess);
    }

    /**
     * Builds a real entity batch for {@code level} that carries one deferred section update of {@code entity}.
     * <p>
     * The update is the one a worker records: the entity, the level callback the inline move would have
     * invoked and the section of the target position.
     */
    private static Object deferredSectionBatch(ServerLevel level, Entity entity, BlockPos target) throws ReflectiveOperationException {
        final Object batch = newEntityBatch(level);
        deferSectionUpdate(batch, entity, levelCallback(entity), target);
        return batch;
    }

    /**
     * Records one deferred section update on {@code batch}, exactly like the worker seam does.
     */
    private static void deferSectionUpdate(Object batch, Entity entity, EntityInLevelCallback callback, BlockPos target) throws ReflectiveOperationException {
        final Method defer = Class.forName(ENTITY_BATCH_TYPE)
                .getDeclaredMethod("deferSectionUpdate", Entity.class, EntityInLevelCallback.class, long.class);
        defer.setAccessible(true);
        defer.invoke(batch, entity, callback, sectionKeyOf(target));
    }

    /**
     * {@return true when the deferred section seam of {@code batch} no longer accepts updates}
     */
    private static boolean awaitSectionSeamClosed(Object batch) throws ReflectiveOperationException {
        final Method accepts = Class.forName(ENTITY_BATCH_TYPE).getDeclaredMethod("acceptsDeferredSectionUpdates");
        accepts.setAccessible(true);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!(Boolean) accepts.invoke(batch)) {
                return true;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> activeTickBatches(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("activeTickBatches");
        field.setAccessible(true);
        return (Set<Object>) field.get(engine);
    }

    private static Object newEntityBatch(ServerLevel level) throws ReflectiveOperationException {
        final Constructor<?> constructor = Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class);
        constructor.setAccessible(true);
        return constructor.newInstance(level);
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
     * Runs the groups of {@code batch} and waits for them, so only the release of its barrier is missing.
     */
    private void submitAndAwait(Object batch) throws ReflectiveOperationException {
        final Method submitAndAwait = Class.forName(REGION_PACKAGE + "RegionTickBatch")
                .getDeclaredMethod("submitAndAwait", RegionWorkerExecutor.class, RegionTickBarrier.class);
        submitAndAwait.setAccessible(true);
        submitAndAwait.invoke(batch, this.workerPool(), PARKING_BARRIER);
    }

    /**
     * Releases the barrier of {@code batch}, so it can never apply a deferred section update again.
     */
    private static void finish(Object batch) throws ReflectiveOperationException {
        final Method finish = Class.forName(REGION_PACKAGE + "RegionTickBatch").getDeclaredMethod("finish");
        finish.setAccessible(true);
        finish.invoke(batch);
    }

    /**
     * Opens the real lease the engine opens around one worker tick loop, for {@code group}.
     */
    private static RegionEntityOwnershipScope openLease(ServerLevel level, Object group) throws ReflectiveOperationException {
        final Method open = RegionEntityOwnershipScope.class.getDeclaredMethod("open", RegionCoordinate.class, group.getClass());
        open.setAccessible(true);
        return (RegionEntityOwnershipScope) open.invoke(null, new RegionCoordinate(level, 0, 0), group);
    }

    private void applyDeferredSectionUpdates(Object batch) throws ReflectiveOperationException {
        final Method apply = RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntitySectionUpdates", Class.forName(ENTITY_BATCH_TYPE));
        apply.setAccessible(true);
        apply.invoke(this.engine, batch);
    }

    private static EntityInLevelCallback levelCallback(Entity entity) {
        try {
            return (EntityInLevelCallback) getField(Entity.class, entity, "levelCallback");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return the section key the real section-manager callback currently holds for {@code entity}}
     */
    private static long sectionKey(Entity entity) {
        return sectionKeyOf(levelCallback(entity));
    }

    /**
     * {@return the section key {@code callback} currently holds, or -1 when it holds none}
     */
    private static long sectionKeyOf(EntityInLevelCallback callback) {
        if (callback == null) {
            throw new IllegalStateException("the entity has no level callback");
        }
        try {
            return (long) getField(callback.getClass(), callback, "currentSectionKey");
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("the level callback holds no section key", exception);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
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
     * Waits for {@code thread} with a bound, so a batch that never releases cannot hang the test.
     */
    private static void join(Thread thread) {
        try {
            thread.join(10_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static long sectionKeyOf(BlockPos position) {
        return SectionPos.of(position).asLong();
    }

    private static ChunkPos chunkOf(BlockPos position) {
        return new ChunkPos(SectionPos.posToSectionCoord(position.getX()), SectionPos.posToSectionCoord(position.getZ()));
    }

    private static Object getField(Class<?> declaringClass, Object target, String name) throws ReflectiveOperationException {
        final Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int regionX(RegionThreadingEngine engine, Entity entity) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
            entitiesField.setAccessible(true);
            final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
            if (region == null) {
                throw new IllegalStateException("the entity is not tracked by the regionizer");
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
        assertTrue(this.workerPool().execute("entity section deferral test", () -> {
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

    private RegionWorkerExecutor workerPool() {
        try {
            final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
            field.setAccessible(true);
            return (RegionWorkerExecutor) field.get(this.engine);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Level callback whose section publication fails, standing in for a modded listener that throws while
     * the barrier publishes a section.
     */
    private static final class FailingLevelCallback implements EntityInLevelCallback {
        @Override
        public void onMove() {
            throw new IllegalStateException("modded section listener failed");
        }

        @Override
        public void onRemove(Entity.RemovalReason reason) {}
    }

    /**
     * Real server entity used by the section deferral tests. It is constructed on the test thread and its
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
