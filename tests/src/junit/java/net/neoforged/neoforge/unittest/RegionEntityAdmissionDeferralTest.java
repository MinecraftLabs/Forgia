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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
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
import net.minecraft.world.level.ServerLevelAccessor;
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
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the deferred admission of a new entity by a leased region worker against the real engine,
 * regionizer, worker pool and barrier, and against the real {@link PersistentEntitySectionManager}.
 * <p>
 * A worker that spawns an entity inside its leased region must not admit it: the admission writes the
 * level's UUID set, the visible lookup, the entity section, the ticking list and the region index. The
 * worker constructs and configures the entity - the position writes of a pending entity are field writes and
 * are granted - records the whole riding graph, and the barrier runs the unmodified
 * {@code ServerLevel.addEntity} on the server thread. Every spawn runs the real patched guard of the real
 * vanilla entry point on an actual native worker thread of the engine's pool, and the level is a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock that owns the real section manager of the test, so the
 * test observes exactly which thread wrote which storage and compares a deferred admission with a
 * server-thread admission.
 */
@Timeout(30)
class RegionEntityAdmissionDeferralTest {
    private static final String REGION_PACKAGE = "net.neoforged.neoforge.server.threading.region.";
    private static final String ENTITY_BATCH_TYPE = REGION_PACKAGE + "RegionEntityTickBatch";
    /**
     * Entries the batch reserves for the deferred block updates. The seams under test defer no block write, so
     * the production bound is used.
     */
    private static final int DEFERRED_BLOCK_UPDATE_ENTRIES = 1 << 16;
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    /** The position of the worker-ticked entity, in region {@code 0/0} of the fixed 3x3 grid. */
    private static final BlockPos SOURCE_POSITION = new BlockPos(1, 64, 1);
    /** A position inside the leased region but in another section than {@link #SOURCE_POSITION}. */
    private static final BlockPos SECTION_POSITION = new BlockPos(20, 64, 4);
    /** A position in the neighbouring region {@code 1/0}, which the lease does not cover. */
    private static final BlockPos FOREIGN_POSITION = new BlockPos(64, 64, 4);

    private final Thread ownerThread = Thread.currentThread();
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        // Also shuts the engine down; a test that already shut it down is unaffected.
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void workerAddsAnEntityIntoItsLeasedRegionAndTheBarrierRunsTheNormalAdmissionPath() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final List<Boolean> addedToLevelOnWorker = new CopyOnWriteArrayList<>();
        final List<Boolean> acceptedOnWorker = new CopyOnWriteArrayList<>();
        final List<Entity> spawned = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            try {
                final TestEntity loot = new TestEntity(self.level());
                loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
                spawned.add(loot);
                acceptedOnWorker.add(self.level().addFreshEntity(loot));
                // The rest of the worker tick must see the entity as not in the level: the admission is
                // "accepted for this tick", not "already in the world".
                addedToLevelOnWorker.add(loot.isAddedToLevel());
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the worker admission was rejected: " + failures);
        assertEquals(List.of(true), acceptedOnWorker, "addFreshEntity must answer \"accepted for this tick\"");
        assertEquals(List.of(false), addedToLevelOnWorker,
                "the entity must not be in the level for the rest of the worker tick");
        final Entity loot = spawned.get(0);
        final ChunkPos chunk = new ChunkPos(SectionPos.posToSectionCoord(loot.getX()), SectionPos.posToSectionCoord(loot.getZ()));
        // The reference admission runs on the server thread through the same vanilla entry point.
        final TestEntity reference = new TestEntity(level);
        reference.setPos(loot.getX(), loot.getY(), loot.getZ());
        assertTrue(level.addFreshEntity(reference), "the server thread must admit the reference entity");
        manager.updateChunkStatus(chunk, Visibility.TICKING);

        assertTrue(loot.isAddedToLevel(), "the barrier must mark the admitted entity as added to the level");
        assertTrue(manager.isLoaded(loot.getUUID()), "the barrier must register the UUID of the admitted entity");
        assertSame(loot, manager.getEntityGetter().get(loot.getUUID()),
                "the barrier must publish the admitted entity in the visible lookup");
        assertSame(loot, manager.getEntityGetter().get(loot.getId()));
        assertTrue(sectionContains(manager, SECTION_POSITION, loot),
                "the barrier must publish the admitted entity in the section of its final position");
        assertEquals(EntityIdentity.State.TRACKED, ledger.current(loot.getUUID()).state(),
                "the barrier must track the admitted identity like a server-thread admission");
        assertEquals(recorder.eventsOf(reference), recorder.eventsOf(loot),
                "the barrier must run exactly the tracking callbacks of a server-thread admission");
        assertEquals(List.of("created", "trackingStart", "tickingStart"), recorder.eventsOf(loot));
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsRecorded());
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityAdditions());
        assertEquals(0L, this.engine.diagnostics().entityAddRejectedOnWorker(),
                "a deferred admission is not a rejected spawn");
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
        assertTrue(this.engine.diagnostics().entityMutationsAllowedByOwnership() >= 2L,
                "the constructor origin and the real position are two granted pending-entity writes, got: "
                        + this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
    }

    @Test
    void theAdmittedEntityKeepsTheWorkerSetPositionAndLandsInItsSection() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<Entity> spawned = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            try {
                // A new entity starts at the constructor origin and is then positioned by the spawn path.
                final TestEntity loot = new TestEntity(self.level());
                loot.setPos(SECTION_POSITION.getX() + 0.25, SECTION_POSITION.getY() + 1.0, SECTION_POSITION.getZ() + 0.75);
                spawned.add(loot);
                self.level().addFreshEntity(loot);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the worker admission was rejected: " + failures);
        final Entity loot = spawned.get(0);
        assertEquals(SECTION_POSITION.getX() + 0.25, loot.getX());
        assertEquals(SECTION_POSITION.getY() + 1.0, loot.getY());
        assertEquals(SECTION_POSITION.getZ() + 0.75, loot.getZ());
        assertTrue(sectionContains(manager, SECTION_POSITION, loot),
                "the admitted entity must be in the section of the position the worker set");
        assertFalse(sectionContains(manager, BlockPos.ZERO, loot),
                "the admitted entity must not stay in the section of its constructor origin");
        assertEquals(SectionPos.of(new BlockPos(SECTION_POSITION.getX(), SECTION_POSITION.getY() + 1, SECTION_POSITION.getZ())).asLong(),
                sectionKeyOf(levelCallback(loot)),
                "the level callback of the admitted entity must name the section of its final position");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
    }

    @Test
    void anAdditionOutsideTheLeasedRegionKeepsTheStorageRefusalAndIsCounted() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final List<Entity> refused = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            final TestEntity loot = new TestEntity(self.level());
            loot.setPos(FOREIGN_POSITION.getX() + 0.5, FOREIGN_POSITION.getY(), FOREIGN_POSITION.getZ() + 0.5);
            refused.add(loot);
            recordAttempt(attempts, () -> self.level().addFreshEntity(loot));
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).contains("ServerLevel.addEntity"),
                () -> "an admission outside the lease must name the operation, got: " + attempts.get(0));
        final Entity loot = refused.get(0);
        assertFalse(loot.isAddedToLevel(), "a refused admission must not mark the entity as added");
        assertFalse(manager.isLoaded(loot.getUUID()), "a refused admission must not touch the storage");
        assertEquals(List.of(), recorder.eventsOf(loot));
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsRecorded());
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(1L, this.engine.diagnostics().entityAddRejectedOnWorker(),
                "a spawn the lease cannot contain is a rejected spawn");
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void anAlreadyAddedOrCrossLevelEntityKeepsTheAdmissionRefusal() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final ServerLevel otherLevel = mock(ServerLevel.class);
        doReturn(Level.OVERWORLD).when(otherLevel).dimension();
        final TestEntity alreadyAdded = new TestEntity(level);
        admit(manager, alreadyAdded, SECTION_POSITION);
        // The entity of another level is constructed on the server thread: the worker only tries to admit it.
        final TestEntity foreignLevel = new TestEntity(otherLevel);
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            // An entity that is already in the level is never re-admitted.
            recordAttempt(attempts, () -> self.level().addFreshEntity(alreadyAdded));
            // An entity of another level is never admitted into this one.
            recordAttempt(attempts, () -> self.level().addFreshEntity(foreignLevel));
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(2, attempts.size());
        assertTrue(attempts.get(0).contains("ServerLevel.addEntity"), attempts.toString());
        assertTrue(attempts.get(1).contains("ServerLevel.addEntity"), attempts.toString());
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsRecorded());
        assertEquals(2L, this.engine.diagnostics().entityAddRejectedOnWorker());
        assertTrue(manager.isLoaded(alreadyAdded.getUUID()), "the already added entity must stay admitted");
    }

    @Test
    void aRidingGraphIsAdmittedWholeAndThePerEntityAddsAreNoOps() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final List<Boolean> accepted = new CopyOnWriteArrayList<>();
        final List<Entity> spawned = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            try {
                final TestEntity vehicle = new TestEntity(self.level());
                vehicle.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
                final TestEntity passenger = new TestEntity(self.level());
                passenger.setPos(vehicle.getX(), vehicle.getY(), vehicle.getZ());
                setField(Entity.class, vehicle, "passengers", ImmutableList.of(passenger));
                setField(Entity.class, passenger, "vehicle", vehicle);
                spawned.add(vehicle);
                spawned.add(passenger);
                // The vanilla passenger path adds the graph member by member; the first record carries it
                // whole, so the members behind it are no-ops of that record.
                ((ServerLevelAccessor) self.level()).addFreshEntityWithPassengers(vehicle);
                accepted.add(vehicle.isAddedToLevel());
                accepted.add(passenger.isAddedToLevel());
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the riding graph was rejected: " + failures);
        assertEquals(List.of(false, false), accepted,
                "neither member of the graph may be in the level for the rest of the worker tick");
        final Entity vehicle = spawned.get(0);
        final Entity passenger = spawned.get(1);
        assertTrue(manager.isLoaded(vehicle.getUUID()), "the vehicle must be admitted");
        assertTrue(manager.isLoaded(passenger.getUUID()), "the passenger must be admitted with its vehicle");
        assertSame(vehicle, passenger.getVehicle(), "the admitted passenger must keep its vehicle");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsRecorded(),
                "one riding graph is one record, however many members it has");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityAdditions());
        assertEquals(0L, this.engine.diagnostics().entityAddRejectedOnWorker());
    }

    @Test
    void aGraphWithAnAlreadyAddedMemberIsRefusedWhole() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final TestEntity seatedPassenger = new TestEntity(level);
        admit(manager, seatedPassenger, SECTION_POSITION);
        final List<String> attempts = new CopyOnWriteArrayList<>();
        final TestEntity zombie = new TestEntity(level, self -> {
            final TestEntity vehicle = new TestEntity(self.level());
            vehicle.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
            setField(Entity.class, vehicle, "passengers", ImmutableList.of(seatedPassenger));
            setField(Entity.class, seatedPassenger, "vehicle", vehicle);
            recordAttempt(attempts, () -> self.level().addFreshEntity(vehicle));
        });
        admit(manager, zombie, SOURCE_POSITION);
        this.engine.addRegionEntity(zombie);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(zombie));
        plan.executeAndAwait();

        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).contains("ServerLevel.addEntity"),
                () -> "a graph with an admitted member must be refused whole, got: " + attempts.get(0));
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsRecorded(),
                "half a graph must never be recorded");
        assertEquals(1L, this.engine.diagnostics().entityAddRejectedOnWorker());
        assertTrue(manager.isLoaded(seatedPassenger.getUUID()));
    }

    @Test
    void anAdditionRecordedThenDroppedByShutdownIsCountedWithoutTouchingStorage() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final TestEntity loot = new TestEntity(level);
        loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
        final Object batch;
        try {
            batch = deferredAdditionBatch(level, loot);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        this.engine.shutdown();

        this.applyDeferredAdditions(batch);

        assertFalse(loot.isAddedToLevel(), "a dropped admission must not mark the entity as added");
        assertFalse(manager.isLoaded(loot.getUUID()), "a dropped admission must not touch the storage");
        assertFalse(sectionContains(manager, SECTION_POSITION, loot),
                "a dropped admission must not create a section membership");
        assertEquals(List.of(), recorder.eventsOf(loot), "a dropped admission must not run a storage callback");
        assertNull(manager.identityLedger().current(loot.getUUID()),
                "a dropped admission must not reserve an identity");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsRecorded());
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntityAdditions());
    }

    @Test
    void anEntityTheWorkerDiscardedBeforeTheBarrierIsNotAdded() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final TestEntity loot = new TestEntity(level);
        loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
        final Object batch;
        try {
            batch = deferredAdditionBatch(level, loot);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        // Spawn and discard in the same tick: the removal of a not-added entity is a field write, so the
        // barrier must observe the removal state and skip the admission.
        loot.discard();

        this.applyDeferredAdditions(batch);

        assertTrue(loot.isRemoved());
        assertFalse(loot.isAddedToLevel(), "a discarded entity must not be admitted");
        assertFalse(manager.isLoaded(loot.getUUID()), "a discarded entity must not touch the storage");
        assertEquals(List.of(), recorder.eventsOf(loot));
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntityAdditions());
    }

    @Test
    void aDuplicateUuidAtTheBarrierIsDroppedAndCountedWithoutFailingTheBatch() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final TestEntity loot = new TestEntity(level);
        loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
        final Object batch;
        try {
            batch = deferredAdditionBatch(level, loot);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        // The server thread admits another entity with the same UUID between the record and the barrier.
        final TestEntity original = new TestEntity(level);
        original.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
        setField(Entity.class, original, "uuid", loot.getUUID());
        assertTrue(level.addFreshEntity(original), "the server thread must admit the original entity");

        this.applyDeferredAdditions(batch);

        assertTrue(manager.isLoaded(original.getUUID()), "the original entity must stay live");
        assertTrue(original.isAddedToLevel(), "the original entity must stay added to the level");
        assertFalse(loot.isAddedToLevel(), "the duplicate must not be admitted");
        assertEquals(0L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(1L, this.engine.diagnostics().droppedDeferredEntityAdditions(),
                "a duplicate UUID at the barrier is a counted drop, never a silent loss");
    }

    @Test
    void aClosedAdditionSeamRefusesTheAdmissionAndReleasesTheRecord() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final TestEntity loot = new TestEntity(level);
        loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
        final Object batch;
        final Object drain;
        try {
            batch = deferredAdditionBatch(level, loot);
            drain = closeDeferredAdditions(batch);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }

        assertEquals(1L, recordedOf(drain), "the released record must stay counted as recorded");
        assertEquals(1, releasedAdditionsOf(drain), "the released record must be countable as dropped");
        assertFalse(manager.isLoaded(loot.getUUID()), "closing the seam must not touch the storage");
        assertFalse((Boolean) deferAddition(batch, loot, List.of(loot)),
                "a closed seam must refuse a further admission");
    }

    @Test
    void theBarrierAppliesTheAdditionAfterTheRemovals() {
        this.installEngine(1, true);
        final TrackingRecorder recorder = new TrackingRecorder();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(recorder);
        final ServerLevel level = this.mockLevel(manager);
        final List<Boolean> spawnedLoadedAtRemoval = new CopyOnWriteArrayList<>();
        final List<Entity> spawned = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity dying = new TestEntity(level, self -> self.discard());
        final TestEntity spawner = new TestEntity(level, self -> {
            try {
                final TestEntity loot = new TestEntity(self.level());
                loot.setPos(SECTION_POSITION.getX() + 0.5, SECTION_POSITION.getY(), SECTION_POSITION.getZ() + 0.5);
                spawned.add(loot);
                self.level().addFreshEntity(loot);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        admit(manager, dying, SECTION_POSITION);
        admit(manager, spawner, SOURCE_POSITION);
        this.engine.addRegionEntity(dying);
        this.engine.addRegionEntity(spawner);
        final EntityInLevelCallback managerCallback = levelCallback(dying);
        setField(Entity.class, dying, "levelCallback", new RecordingLevelCallback(managerCallback, () -> {
            // The removals of a barrier run before its admissions, so the entity spawned in the same tick
            // must still be unknown to the storage when the removal callback runs.
            final Entity loot = spawned.isEmpty() ? null : spawned.get(0);
            spawnedLoadedAtRemoval.add(loot != null && manager.isLoaded(loot.getUUID()));
        }));

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(dying));
        assertTrue(plan.accept(spawner));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the worker admission was rejected: " + failures);
        assertEquals(List.of(false), spawnedLoadedAtRemoval,
                "the removals of the barrier must run before its admissions");
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertFalse(manager.isLoaded(dying.getUUID()));
        assertTrue(manager.isLoaded(spawned.get(0).getUUID()),
                "the admission must run after the removals and before the publication of the same barrier");
    }

    private void installEngine(int workerThreads, boolean entityTickWorkers) {
        // Only the entity tick mechanics tests construct an engine with region-owned entity storage.
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, entityTickWorkers,
                false, false, false, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    /**
     * {@return the mock level the admission runs against, owning {@code manager}}
     * <p>
     * The manager is the real section storage of the test, so the level's physical region size - the size
     * the lease containment of an admission uses - is the fixed 3x3 edge of the layout the manager was built
     * with.
     */
    private ServerLevel mockLevel(PersistentEntitySectionManager<Entity> manager) {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // The seed of an entity group random source is derived from the world seed, the game time and the
        // dimension of its level.
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        doReturn(new EntityIdentityLedger()).when(level).entityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == this.ownerThread);
        doReturn(server).when(level).getServer();
        // The target chunk acquisition of a deferred section update is server-owned storage.
        doAnswer(invocation -> null).when(level).getChunk(anyInt(), anyInt());
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
        doReturn(chunkSource).when(level).getChunkSource();
        setField(ServerLevel.class, level, "entityManager", manager);
        setField(ServerLevel.class, level, "regionAwareEntityGetter", new RegionAwareEntityGetter(level, manager.getEntityGetter()));
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
        manager.updateChunkStatus(new ChunkPos(SectionPos.posToSectionCoord(position.getX()), SectionPos.posToSectionCoord(position.getZ())), Visibility.TICKING);
    }

    /**
     * {@return the guarded section manager of the test, partitioned by the fixed 3x3 layout}
     * <p>
     * The layout is the one the engine ticks with, so the level reports the same physical region size the
     * lease containment uses.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager(LevelCallback<Entity> callbacks) {
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        // A chunk that becomes accessible is queued for its entity load; the test never loads one.
        when(storage.loadEntities(any())).thenReturn(CompletableFuture.completedFuture(null));
        return new PersistentEntitySectionManager<>(
                Entity.class,
                callbacks,
                storage,
                RegionThreadingHooks::assertServerEntityStorageAccess,
                RegionLayout.FIXED_3X3);
    }

    /**
     * Builds a real entity batch for {@code level} that carries one deferred admission of {@code entity},
     * the record a worker writes when it admits an entity it owns.
     */
    private static Object deferredAdditionBatch(ServerLevel level, Entity entity) throws ReflectiveOperationException {
        final Object batch = newEntityBatch(level);
        assertTrue((Boolean) deferAddition(batch, entity, List.of(entity)), "the batch must accept the deferred admission");
        return batch;
    }

    private static Object deferAddition(Object batch, Entity entity, List<Entity> graph) throws ReflectiveOperationException {
        final Method defer = Class.forName(ENTITY_BATCH_TYPE).getDeclaredMethod("deferAddition", Entity.class, List.class);
        defer.setAccessible(true);
        return defer.invoke(batch, entity, graph);
    }

    private static Object closeDeferredAdditions(Object batch) throws ReflectiveOperationException {
        final Method close = Class.forName(ENTITY_BATCH_TYPE).getDeclaredMethod("closeDeferredAdditions");
        close.setAccessible(true);
        return close.invoke(batch);
    }

    private static long recordedOf(Object drain) throws ReflectiveOperationException {
        final Method recorded = drain.getClass().getDeclaredMethod("recorded");
        recorded.setAccessible(true);
        return (long) recorded.invoke(drain);
    }

    private static int releasedAdditionsOf(Object drain) throws ReflectiveOperationException {
        final Method additions = drain.getClass().getDeclaredMethod("additions");
        additions.setAccessible(true);
        return ((List<?>) additions.invoke(drain)).size();
    }

    private static Object newEntityBatch(ServerLevel level) throws ReflectiveOperationException {
        final Constructor<?> constructor = Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(level, DEFERRED_BLOCK_UPDATE_ENTRIES);
    }

    private void applyDeferredAdditions(Object batch) {
        try {
            final Method apply = RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntityAdditions", Class.forName(ENTITY_BATCH_TYPE));
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
        assertTrue(this.workerPool().execute("entity admission deferral test", () -> {
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
     * deferred admission can be compared with a server-thread admission.
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
     * Real server entity used by the admission deferral tests. It is constructed on the thread that runs its
     * {@code tick()}, which is the region worker for a spawned entity.
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
