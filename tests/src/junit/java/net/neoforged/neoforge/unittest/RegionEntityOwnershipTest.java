/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
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
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
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
 * Exercises the entity ownership lease of a region entity tick group against the real engine, regionizer
 * and worker pool.
 * <p>
 * Every mutation runs the real patched guard of the real vanilla entry point on an actual native worker
 * thread of the engine's pool: the entities are real {@link TestEntity} instances whose {@code tick()}
 * performs the guarded call, and the level is a {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock, so
 * {@code guardEntityTick} and {@code tickNonPassenger} are the production bodies. The lease is only
 * installed for the worker tick loop, so the same mutation is rejected again once the loop finished.
 * <p>
 * A position change of an entity that is not added to a level is granted as a plain field mutation. The
 * position change of an entity that is added to a level is granted only because the lease defers its two
 * server-owned level effects - the section-manager {@code onMove} publication and the target chunk
 * acquisition - to the entity batch barrier; the {@code addedEntity...} test admits an entity to a real
 * {@link PersistentEntitySectionManager} and proves that the worker publishes nothing before the barrier
 * and that the barrier publishes the section of the final position. A removal of an owned entity is granted
 * as well: it only writes the entity's own removal state and defers its level callback, which
 * {@code RegionEntityRemovalDeferralTest} covers against a real section manager. The cross-level teleport
 * stays rejected before any mutation.
 */
@Timeout(30)
class RegionEntityOwnershipTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        // Also shuts the engine down; a test that already shut it down is unaffected.
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void ownedEntityMovesOnItsWorkerAndTheDeferredHandoffRunsAtTheBarrier() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final List<Throwable> mutationFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> storageGuardAttempts = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            tickThreads.add(Thread.currentThread().getName());
            try {
                self.setPosRaw(4096.0, 64.0, 0.0);
                this.engine.moveRegionEntity(self);
            } catch (Throwable throwable) {
                mutationFailures.add(throwable);
            }
            // The storage-level guard has no entity argument, so the lease must never grant it.
            try {
                RegionThreadingHooks.assertServerEntityStorageAccess("ServerLevel.addEntity");
                storageGuardAttempts.add(new AssertionError("the lease granted a storage-level access"));
            } catch (IllegalStateException rejected) {
                storageGuardAttempts.add(rejected);
            }
        });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(List.of(), mutationFailures, () -> "an owned mutation was rejected: " + mutationFailures);
        assertEquals(1, storageGuardAttempts.size());
        assertTrue(storageGuardAttempts.get(0) instanceof IllegalStateException,
                () -> "the storage-level guard must stay strict, got: " + storageGuardAttempts.get(0));
        assertTrue(storageGuardAttempts.get(0).getMessage().contains("ServerLevel.addEntity"));
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(16, regionX(this.engine, entity), "the deferred handoff must still run at the barrier");
        assertEquals(1L, this.engine.diagnostics().legacyDeferredEntityMoveCount());
    }

    @Test
    void ownedFieldMutationsAreGrantedWhileTheCrossLevelTeleportIsNot() {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final List<Throwable> grantedFailures = new CopyOnWriteArrayList<>();
        final List<String> teleportAttempts = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                self.setPosRaw(2.0, 64.0, 2.0);
                self.absMoveTo(3.0, 65.0, 3.0);
                self.moveTo(4.0, 66.0, 4.0, 45.0F, 10.0F);
                self.move(MoverType.SELF, new Vec3(1.0, 0.0, 0.0));
            } catch (Throwable throwable) {
                grantedFailures.add(throwable);
            }
            // Entity.teleportTo is the cross-level teleport: it changes level, identity and storage.
            recordAttempt(teleportAttempts, () -> self.teleportTo(level, 400.0, 74.0, 400.0, Set.of(), 0.0F, 0.0F));
        });
        // The lease covers the entity fields, not the collision query of Entity.move.
        entity.noPhysics = true;

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(), grantedFailures, () -> "an owned field mutation was rejected: " + grantedFailures);
        assertEquals(new Vec3(5.0, 66.0, 4.0), entity.position());
        assertEquals(45.0F, entity.getYRot());
        assertEquals(1, teleportAttempts.size());
        assertTrue(teleportAttempts.get(0).contains("Entity.teleportTo"), teleportAttempts.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
        // Every granted guard site of the four field mutations is one allowance; a vanilla call can enter
        // several of them (for example moveTo reaches setPosRaw twice).
        assertTrue(this.engine.diagnostics().entityMutationsAllowedByOwnership() >= 4L,
                () -> "all four field mutations must be granted, got: " + this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void mutationOfAnEntityOutsideTheGroupIsRejectedEvenThoughAnotherGroupOwnsIt() {
        this.installEngine(2, true);
        final ServerLevel level = mockLevel();
        final TestEntity foreign = new TestEntity(level);
        foreign.setPos(4096.0, 64.0, 0.0);
        final List<Throwable> ownFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> foreignAttempts = new CopyOnWriteArrayList<>();
        final TestEntity owned = new TestEntity(level, self -> {
            try {
                self.setPosRaw(2.0, 64.0, 2.0);
            } catch (Throwable throwable) {
                ownFailures.add(throwable);
            }
            try {
                foreign.setPosRaw(4098.0, 64.0, 0.0);
                foreignAttempts.add(new AssertionError("a worker mutated an entity of another group"));
            } catch (IllegalStateException rejected) {
                foreignAttempts.add(rejected);
            }
        });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(owned));
        assertTrue(plan.accept(foreign));
        plan.executeAndAwait();

        assertEquals(List.of(), ownFailures, () -> "the owned mutation was rejected: " + ownFailures);
        assertEquals(1, foreignAttempts.size());
        assertTrue(foreignAttempts.get(0) instanceof IllegalStateException,
                () -> "a foreign entity mutation must be rejected, got: " + foreignAttempts.get(0));
        assertTrue(foreignAttempts.get(0).getMessage().contains("Entity.setPosRaw"),
                () -> "the rejection must name the operation, got: " + foreignAttempts.get(0).getMessage());
        assertEquals(4096.0, foreign.position().x, "a rejected foreign mutation must not move the entity");
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(1L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
    }

    @Test
    void addedEntityMovesThroughTheDeferredSectionSeamWhileTheTeleportStaysRejected() throws ReflectiveOperationException {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final List<Throwable> grantedFailures = new CopyOnWriteArrayList<>();
        final List<Long> sectionsOnWorker = new CopyOnWriteArrayList<>();
        final List<String> teleportAttempts = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            try {
                self.setPosRaw(40.0, 64.0, 40.0);
                self.absMoveTo(50.0, 65.0, 50.0);
                self.moveTo(60.0, 66.0, 60.0, 45.0F, 10.0F);
                self.move(MoverType.SELF, new Vec3(1.0, 0.0, 0.0));
            } catch (Throwable throwable) {
                grantedFailures.add(throwable);
            }
            sectionsOnWorker.add(sectionKey(self));
            // Entity.teleportTo is the cross-level teleport: it changes level, identity and storage.
            recordAttempt(teleportAttempts, () -> self.teleportTo(level, 400.0, 74.0, 400.0, Set.of(), 0.0F, 0.0F));
        });
        // The lease covers the entity fields, not the collision query of Entity.move.
        entity.noPhysics = true;
        entity.setPos(1.0, 64.0, 1.0);
        assertTrue(manager.addNewEntityWithoutEvent(entity), "the entity must be admitted to the section manager");
        // ServerLevel#addEntity marks the admitted entity as added to the level right after the admission.
        entity.onAddedToLevel();
        assertTrue(entity.isAddedToLevel());
        assertEquals("net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback",
                getField(Entity.class, entity, "levelCallback").getClass().getName(),
                "the deferred section publication must reach the real section-manager callback");
        final long sourceSection = sectionKey(entity);

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(), grantedFailures, () -> "an owned field mutation was rejected: " + grantedFailures);
        assertEquals(new Vec3(61.0, 66.0, 60.0), entity.position());
        assertEquals(45.0F, entity.getYRot());
        assertEquals(List.of(sourceSection), sectionsOnWorker,
                "the section membership must stay the source section until the barrier");
        assertEquals(SectionPos.of(entity.blockPosition()).asLong(), sectionKey(entity),
                "the barrier must publish the section of the final position");
        assertNotEquals(sourceSection, sectionKey(entity), "the final position must be in another section");
        assertEquals(1, teleportAttempts.size());
        assertTrue(teleportAttempts.get(0).contains("Entity.teleportTo"), teleportAttempts.toString());
        assertTrue(manager.isLoaded(entity.getUUID()), "the entity must stay admitted to the section manager");
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
        assertTrue(this.engine.diagnostics().deferredEntitySectionUpdatesApplied() >= 1L,
                () -> "the deferred section publication must run at the barrier, got: "
                        + this.engine.diagnostics().deferredEntitySectionUpdatesApplied());
        // Every granted guard site of the four field mutations is one allowance; a vanilla call can enter
        // several of them (for example moveTo reaches setPosRaw twice).
        assertTrue(this.engine.diagnostics().entityMutationsAllowedByOwnership() >= 4L,
                () -> "all four field mutations must be granted, got: " + this.engine.diagnostics().entityMutationsAllowedByOwnership());
    }

    @Test
    void removalOfAnOwnedEntityIsGrantedAndOnlyChangesItsOwnState() {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final List<String> removalAttempts = new CopyOnWriteArrayList<>();
        final List<Boolean> removedOnWorker = new CopyOnWriteArrayList<>();
        // This entity is not added to a level, so its removal has no server-owned effect and needs no
        // deferral; the granted removal only writes the entity's own removal state.
        final TestEntity entity = new TestEntity(level, self -> {
            recordAttempt(removalAttempts, self::discard);
            removedOnWorker.add(self.isRemoved());
            recordAttempt(removalAttempts, () -> self.remove(Entity.RemovalReason.KILLED));
            recordAttempt(removalAttempts, () -> self.setRemoved(Entity.RemovalReason.DISCARDED));
        });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of("granted", "granted", "granted"), removalAttempts);
        assertEquals(List.of(true), removedOnWorker, "the removal must be visible to the rest of the tick");
        assertEquals(Entity.RemovalReason.DISCARDED, entity.getRemovalReason(),
                "the first removal reason wins, exactly like the vanilla removal");
        assertTrue(entity.isRemoved());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(6L, this.engine.diagnostics().entityMutationsAllowedByOwnership(),
                "discard, remove and setRemoved are granted again for every removal that re-enters them");
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void workerTaskAfterTheTickLoopSeesNoLease() {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final TestEntity entity = new TestEntity(level, self -> {
            tickThreads.add(Thread.currentThread().getName());
            self.setPosRaw(2.0, 64.0, 2.0);
        });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());

        final AtomicReference<String> probeThread = new AtomicReference<>();
        final AtomicBoolean probeIsWorker = new AtomicBoolean();
        final AtomicReference<Throwable> probeRejection = new AtomicReference<>();
        runOnNativeWorker(() -> {
            probeThread.set(Thread.currentThread().getName());
            probeIsWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            probeRejection.set(assertThrows(IllegalStateException.class, () -> entity.setPosRaw(3.0, 64.0, 3.0)));
        });

        assertEquals(1, tickThreads.size());
        assertEquals(tickThreads.get(0), probeThread.get(), "the probe must run on the tick worker thread");
        assertTrue(probeIsWorker.get(), "the probe must be a native region worker");
        assertNotNull(probeRejection.get());
        assertTrue(probeRejection.get().getMessage().contains("Entity.setPosRaw"),
                () -> "the leaked lease must not grant a mutation, got: " + probeRejection.get().getMessage());
        assertEquals(2.0, entity.position().x, "a rejected mutation must not move the entity");
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership(),
                "a worker mutation without a lease is a storage rejection, not an ownership rejection");
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void passengerOfAnOwnedVehicleIsAllowedWhileAForeignPassengerIsRejected() {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        // The vehicle tick is the only worker tick of this batch, so the passenger loop of
        // ServerLevel#tickNonPassenger must not run for the membership assertions.
        stubTickNonPassenger(level);
        final TestEntity passenger = new TestEntity(level);
        passenger.setPos(1.0, 64.0, 1.0);
        final TestEntity foreignPassenger = new TestEntity(level);
        foreignPassenger.setPos(1.0, 64.0, 1.0);
        final Entity foreignVehicle = mock(Entity.class);
        when(foreignVehicle.hasPassenger(foreignPassenger)).thenReturn(true);
        setField(Entity.class, foreignPassenger, "vehicle", foreignVehicle);

        final List<Throwable> memberFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> foreignAttempts = new CopyOnWriteArrayList<>();
        final TestEntity vehicle = new TestEntity(level, self -> {
            try {
                passenger.setPosRaw(2.0, 64.0, 2.0);
            } catch (Throwable throwable) {
                memberFailures.add(throwable);
            }
            try {
                foreignPassenger.setPosRaw(2.0, 64.0, 2.0);
                foreignAttempts.add(new AssertionError("a worker mutated a passenger of a foreign vehicle"));
            } catch (IllegalStateException rejected) {
                foreignAttempts.add(rejected);
            }
        });
        vehicle.setPos(1.0, 64.0, 1.0);
        setField(Entity.class, vehicle, "passengers", ImmutableList.of(passenger));
        setField(Entity.class, passenger, "vehicle", vehicle);

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(vehicle));
        plan.executeAndAwait();

        assertEquals(List.of(), memberFailures, () -> "the member passenger was rejected: " + memberFailures);
        assertEquals(1, foreignAttempts.size());
        assertTrue(foreignAttempts.get(0) instanceof IllegalStateException,
                () -> "a foreign passenger must be rejected, got: " + foreignAttempts.get(0));
        assertTrue(foreignAttempts.get(0).getMessage().contains("Entity.setPosRaw"));
        assertEquals(2.0, passenger.position().x, "the owned passenger must move");
        assertEquals(1.0, foreignPassenger.position().x, "the foreign passenger must not move");
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(1L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
    }

    @Test
    void secondBatchOnTheSameWorkerDoesNotInheritThePreviousLease() {
        this.installEngine(1, true);
        final ServerLevel level = mockLevel();
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final TestEntity first = new TestEntity(level, self -> {
            tickThreads.add(Thread.currentThread().getName());
            self.setPosRaw(1.0, 64.0, 1.0);
        });
        final List<Throwable> ownFailures = new CopyOnWriteArrayList<>();
        final List<Throwable> inheritedAttempts = new CopyOnWriteArrayList<>();
        final TestEntity second = new TestEntity(level, self -> {
            tickThreads.add(Thread.currentThread().getName());
            try {
                self.setPosRaw(2.0, 64.0, 2.0);
            } catch (Throwable throwable) {
                ownFailures.add(throwable);
            }
            try {
                first.setPosRaw(3.0, 64.0, 3.0);
                inheritedAttempts.add(new AssertionError("the second batch inherited the lease of the first"));
            } catch (IllegalStateException rejected) {
                inheritedAttempts.add(rejected);
            }
        });

        final RegionEntityTickPlan firstPlan = plan(level);
        assertTrue(firstPlan.accept(first));
        firstPlan.executeAndAwait();

        final RegionEntityTickPlan secondPlan = plan(level);
        assertTrue(secondPlan.accept(second));
        secondPlan.executeAndAwait();

        assertEquals(2, tickThreads.size());
        assertEquals(tickThreads.get(0), tickThreads.get(1), "both batches must run on the same worker thread");
        assertEquals(List.of(), ownFailures, () -> "the owned mutation was rejected: " + ownFailures);
        assertEquals(1, inheritedAttempts.size());
        assertTrue(inheritedAttempts.get(0) instanceof IllegalStateException,
                () -> "the entity of the previous batch must be rejected, got: " + inheritedAttempts.get(0));
        assertTrue(inheritedAttempts.get(0).getMessage().contains("Entity.setPosRaw"));
        assertEquals(1.0, first.position().x, "the previous batch's entity must not move");
        assertEquals(2.0, second.position().x);
        assertEquals(2L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(1L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
    }

    @Test
    void entityTickWorkersOffInstallsNoLeaseAndKeepsTheStorageGuard() {
        this.installEngine(1, false);
        final ServerLevel level = mockLevel();
        final TestEntity entity = new TestEntity(level);

        assertNull(this.engine.beginEntityTickPlan(level, ignored -> true, PARKING_BARRIER),
                "entity tick workers are off, so no batch and no lease may start");
        assertFalse(this.engine.diagnostics().entityTickWorkerExecutionEnabled());

        final AtomicBoolean probeIsWorker = new AtomicBoolean();
        final AtomicReference<Throwable> probeRejection = new AtomicReference<>();
        runOnNativeWorker(() -> {
            probeIsWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            probeRejection.set(assertThrows(IllegalStateException.class, () -> entity.setPosRaw(1.0, 64.0, 1.0)));
        });

        assertTrue(probeIsWorker.get());
        assertNotNull(probeRejection.get());
        assertTrue(probeRejection.get().getMessage().contains("Entity.setPosRaw"),
                () -> "the default-off guard must reject like before, got: " + probeRejection.get().getMessage());
        assertEquals(0.0, entity.position().x);
        assertEquals(0L, this.engine.diagnostics().entityMutationsAllowedByOwnership());
        assertEquals(0L, this.engine.diagnostics().entityMutationsRejectedByOwnership());
        assertEquals(1L, this.engine.diagnostics().ownershipViolationCount());
    }

    /**
     * Creates the engine and installs it as the active region threading implementation, so the entity
     * tick hooks of the level reach it exactly like they do on a server.
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

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        // The target chunk acquisition of a deferred section update is server-owned storage.
        doAnswer(invocation -> null).when(level).getChunk(anyInt(), anyInt());
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private static void stubTickNonPassenger(ServerLevel level) {
        doAnswer(invocation -> {
            invocation.<Entity>getArgument(0).tick();
            return null;
        }).when(level).tickNonPassenger(any());
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
     * {@return the section key the real section-manager callback currently holds for {@code entity}}
     *
     * @throws IllegalStateException when the entity has no section-manager callback
     */
    private static long sectionKey(Entity entity) {
        try {
            final Object callback = getField(Entity.class, entity, "levelCallback");
            if (callback == null) {
                throw new IllegalStateException("the entity has no level callback");
            }
            return (long) getField(callback.getClass(), callback, "currentSectionKey");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager() {
        return new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback) mock(LevelCallback.class),
                mock(EntityPersistentStorage.class),
                RegionThreadingHooks::assertServerEntityStorageAccess);
    }

    private static Object getField(Class<?> declaringClass, Object target, String name) throws ReflectiveOperationException {
        final Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Runs {@code task} on a real worker thread of the engine's pool and rethrows its failure.
     */
    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("entity ownership test", () -> {
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

    private static int regionX(RegionThreadingEngine engine, Entity entity) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(engine);
        final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
        assertNotNull(region, "the entity is not tracked by the regionizer");
        final Method coordinateMethod = region.getClass().getDeclaredMethod("coordinate");
        coordinateMethod.setAccessible(true);
        return ((RegionCoordinate) coordinateMethod.invoke(region)).regionX();
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
     * Real server entity used by the ownership tests. It is constructed on the test thread and its
     * {@code tick()} performs the guarded vanilla call on the region worker.
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
