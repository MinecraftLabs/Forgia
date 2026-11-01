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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the region entity tick plan against the real engine: predicates, region grouping, worker
 * execution, the barrier, and the region lease lifecycle.
 * <p>
 * The level is a {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock, so the worker runs the real
 * {@code guardEntityTick}, {@code tickNonPassenger} and {@code tickPassenger} code and only the entity
 * itself is a mock. The engine is installed into {@link RegionThreadingManager} so the entity tick hooks
 * resolve it exactly like the server does. The fully bootstrapped entity path is covered by the server
 * smoke test.
 */
@Timeout(30)
class RegionEntityTickPlanTest {
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
    void entityHooksKeepVanillaInlineBehaviorWithoutAnInstalledEngine() {
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();

        assertNull(RegionThreadingHooks.beginEntityTickPlan(level, candidate -> true, PARKING_BARRIER));
        RegionThreadingHooks.runEntityTickPlan(null);
        RegionThreadingHooks.runEntityTickTask(entity, runs::incrementAndGet);
        RegionThreadingHooks.addRegionEntity(entity);
        RegionThreadingHooks.moveRegionEntity(entity);
        RegionThreadingHooks.removeRegionEntity(entity);

        assertEquals(1, runs.get());
        verify(entity, times(0)).tick();
        assertFalse(RegionThreadingManager.isInstalled());
    }

    @Test
    void planIsDisabledWhenEntityTickWorkersAreOff() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER));
        assertFalse(this.engine.diagnostics().entityTickWorkerExecutionEnabled());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void planFallsBackToTheServerThreadWhileTheProfilerIsActive() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        doReturn(mock(ProfilerFiller.class)).when(level).getProfiler();

        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER));
    }

    @Test
    void planIsDisabledWhenTheLevelIsAlreadyClosed() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        this.engine.clearLevelRegionTasks(level);

        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER));
    }

    @Test
    void acceptedEntityTicksOnceOnARegionWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().skippedEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        // The tracked entity keeps its region alive after the batch released the lease.
        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().trackedEntityCount());
    }

    @Test
    void entitiesInDifferentRegionsTickInParallel() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity first = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity second = mockEntity(level, new BlockPos(4096, 64, 0));
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        doAnswer(invocation -> {
            rendezvous.await(10, TimeUnit.SECONDS);
            return null;
        }).when(level).tickNonPassenger(any(Entity.class));

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));
        plan.executeAndAwait();

        // The rendezvous only opens when both regions are inside their worker tick at the same time.
        verify(first, times(1)).checkDespawn();
        verify(second, times(1)).checkDespawn();
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void entitiesInTheSameRegionTickSerially() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity first = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity second = mockEntity(level, new BlockPos(2, 64, 2));
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
            } finally {
                active.decrementAndGet();
            }
            return null;
        }).when(level).tickNonPassenger(any(Entity.class));

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));
        plan.executeAndAwait();

        assertEquals(1, maxActive.get(), "one region must never tick against itself");
        verify(first, times(1)).checkDespawn();
        verify(second, times(1)).checkDespawn();
    }

    @Test
    void failedTickReleasesTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity failing = mockEntity(level, new BlockPos(1, 64, 1));
        doThrow(new IllegalStateException("boom")).when(failing).checkDespawn();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(failing));
        assertThrows(IllegalStateException.class, plan::executeAndAwait);

        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());

        // The lease and the barrier are reusable after a failure.
        final Entity healthy = mockEntity(level, new BlockPos(1, 64, 1));
        final RegionEntityTickPlan retry = plan(level);
        assertTrue(retry.accept(healthy));
        retry.executeAndAwait();
        verify(healthy, times(1)).checkDespawn();
    }

    @Test
    void migratedEntityIsTickedOnlyOnceByTheSnapshotRegion() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(0, 64, 0));

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        // The entity moves to another region while the tick list is being collected.
        when(entity.blockPosition()).thenReturn(new BlockPos(4096, 64, 0));
        plan.executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void entityThatMigratesIntoAnotherBatchRegionKeepsItsSnapshotLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final BlockPos migratedPosition = new BlockPos(4096, 64, 0);
        final AtomicReference<BlockPos> migratingPosition = new AtomicReference<>(new BlockPos(0, 64, 0));
        final Entity migrating = mockEntity(level, migratingPosition.get());
        when(migrating.blockPosition()).thenAnswer(invocation -> migratingPosition.get());
        final Entity resident = mockEntity(level, migratedPosition);

        final CountDownLatch residentTicking = new CountDownLatch(1);
        final CountDownLatch migratingTicked = new CountDownLatch(1);
        final List<String> migratingTickThreads = new CopyOnWriteArrayList<>();

        // The resident entity holds the lease of the region the migrating entity moves into.
        doAnswer(invocation -> {
            residentTicking.countDown();
            awaitOrFail(migratingTicked, "the migrating entity was not ticked under its snapshot region lease");
            return null;
        }).when(resident).tick();
        // checkDespawn is part of the tick: the entity has already moved when its own tick starts.
        doAnswer(invocation -> {
            awaitOrFail(residentTicking, "the resident region never started ticking");
            migratingPosition.set(migratedPosition);
            return null;
        }).when(migrating).checkDespawn();
        doAnswer(invocation -> {
            migratingTickThreads.add(Thread.currentThread().getName());
            migratingTicked.countDown();
            return null;
        }).when(migrating).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(migrating));
        assertTrue(plan.accept(resident));
        plan.executeAndAwait();

        verify(migrating, times(1)).tick();
        verify(resident, times(1)).tick();
        assertEquals(1, migratingTickThreads.size());
        assertTrue(migratingTickThreads.get(0).startsWith("NeoForge Region Worker"), migratingTickThreads.toString());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void foreignEntityTickFromBatchIsDeferredToTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(0, 64, 0));
        // The passenger is in another region: ServerLevel#tickPassenger ticks it through the same seam,
        // but the vehicle's batch lease does not own it, so it must not run inline on the worker.
        final Entity passenger = mockEntity(level, new BlockPos(4096, 64, 0));
        final List<String> passengerThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            this.engine.runInEntityRegion(passenger, () -> passengerThreads.add(Thread.currentThread().getName()));
            return null;
        }).when(vehicle).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(vehicle));
        plan.executeAndAwait();

        assertEquals(1, passengerThreads.size());
        assertFalse(passengerThreads.get(0).startsWith("NeoForge Region Worker"), passengerThreads.toString());
        assertEquals(1L, this.engine.diagnostics().deferredEntityTicks());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(2, this.engine.diagnostics().regionCount());
    }

    @Test
    void passengersAreTickedInsideTheVehicleRegion() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity passenger = mockEntity(level, new BlockPos(1, 64, 1));
        when(vehicle.getPassengers()).thenReturn(List.of(passenger));
        when(passenger.getVehicle()).thenReturn(vehicle);
        when(passenger.getId()).thenReturn(42);
        final EntityTickList tickingEntities = new EntityTickList();
        tickingEntities.add(passenger);
        setEntityTickList(level, tickingEntities);

        final List<String> passengerThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            passengerThreads.add(Thread.currentThread().getName());
            return null;
        }).when(passenger).rideTick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(vehicle));
        plan.executeAndAwait();

        verify(vehicle, times(1)).tick();
        verify(passenger, times(1)).rideTick();
        assertEquals(1, passengerThreads.size());
        assertTrue(passengerThreads.get(0).startsWith("NeoForge Region Worker"), passengerThreads.toString());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void shutdownWaitsForTheRunningBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the entity tick was never released");
            tickFinished.set(true);
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));

        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread serverThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "entity-batch-test");
        serverThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the batch never started");

        // Shutdown must not return while a region worker is still ticking entities of the batch.
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdownThread = new Thread(() -> {
            try {
                this.engine.shutdown();
                if (!tickFinished.get()) {
                    shutdownFailure.set(new AssertionError("shutdown returned before the entity tick finished"));
                }
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "engine-shutdown-test");
        shutdownThread.start();

        releaseTick.countDown();
        serverThread.join(10_000L);
        shutdownThread.join(10_000L);

        assertFalse(serverThread.isAlive(), "the entity batch barrier was never released");
        assertFalse(shutdownThread.isAlive(), "shutdown never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(shutdownFailure.get(), () -> "shutdown failed: " + shutdownFailure.get());
        verify(entity, times(1)).tick();
    }

    @Test
    void migrationHandoffIsPublishedAfterTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(0, 64, 0));
        final Entity entity = mockEntity(level, position.get());
        when(entity.blockPosition()).thenAnswer(invocation -> position.get());
        final List<Boolean> ownedAfterMove = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            position.set(new BlockPos(4096, 64, 0));
            this.engine.moveRegionEntity(entity);
            this.engine.runInEntityRegion(entity, () -> ownedAfterMove.add(this.engine.isOwnedByCurrentRegion(entity)));
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(List.of(true), ownedAfterMove);
        assertEquals(1, this.engine.diagnostics().trackedEntityCount());
        assertEquals(1, this.engine.diagnostics().regionCount());
    }

    @Test
    void removalHandoffRetiresEntityTasksAndRegionAfterTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(0, 64, 0));
        final AtomicInteger scheduledRuns = new AtomicInteger();
        final var scheduled = this.engine.entityScheduler().runAtFixedRate(entity, scheduledRuns::incrementAndGet, 0L, 1L);
        doAnswer(invocation -> {
            when(entity.isRemoved()).thenReturn(true);
            this.engine.removeRegionEntity(entity);
            assertFalse(scheduled.isCancelled(), "removal must not publish from inside the worker tick");
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertTrue(scheduled.isCancelled());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
        this.engine.drainRegionTasks(level, BlockPos.ZERO);
        assertEquals(0, scheduledRuns.get());
    }

    @Test
    void splitIsDeferredUntilEntityBatchReleasesItsLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);
        this.engine.addRegionSection(level, 32, 0);
        final Entity entity = mockEntity(level, new BlockPos(0, 64, 0));
        doAnswer(invocation -> {
            this.engine.removeRegionSection(level, 16, 0);
            assertEquals(1, this.engine.diagnostics().regionCount());
            assertEquals(1, this.engine.diagnostics().pendingRecalculationCount());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(2, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void shutdownWaitsForEveryPublishedBatch() throws Exception {
        this.installEngine(true);
        final ServerLevel firstLevel = mockLevel();
        final ServerLevel secondLevel = mockLevel();
        setEntityTickList(firstLevel, new EntityTickList());
        setEntityTickList(secondLevel, new EntityTickList());
        stubGuardEntityTick(firstLevel);
        stubGuardEntityTick(secondLevel);
        stubTickNonPassenger(firstLevel);
        stubTickNonPassenger(secondLevel);
        final Entity first = mockEntity(firstLevel, new BlockPos(0, 64, 0));
        final Entity second = mockEntity(secondLevel, new BlockPos(4096, 64, 0));
        final CountDownLatch ticking = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Runnable firstTick = blockingTick(ticking, release, firstFailure);
        final Runnable secondTick = blockingTick(ticking, release, secondFailure);
        doAnswer(invocation -> {
            firstTick.run();
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            secondTick.run();
            return null;
        }).when(second).tick();
        final RegionEntityTickPlan firstPlan = plan(firstLevel);
        final RegionEntityTickPlan secondPlan = plan(secondLevel);
        assertTrue(firstPlan.accept(first));
        assertTrue(secondPlan.accept(second));
        final Thread firstBatch = batchThread(firstPlan, firstFailure, "first-entity-batch-test");
        final Thread secondBatch = batchThread(secondPlan, secondFailure, "second-entity-batch-test");
        firstBatch.start();
        secondBatch.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "both entity batches must start before shutdown");

        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdown = new Thread(() -> {
            try {
                this.engine.shutdown();
                assertEquals(0, this.engine.diagnostics().runningRegionCount());
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "all-entity-batches-shutdown-test");
        shutdown.start();
        release.countDown();
        firstBatch.join(10_000L);
        secondBatch.join(10_000L);
        shutdown.join(10_000L);

        assertFalse(firstBatch.isAlive());
        assertFalse(secondBatch.isAlive());
        assertFalse(shutdown.isAlive());
        assertNull(firstFailure.get(), () -> "first batch failed: " + describeFailure(firstFailure.get()));
        assertNull(secondFailure.get(), () -> "second batch failed: " + describeFailure(secondFailure.get()));
        assertNull(shutdownFailure.get(), () -> "shutdown failed: " + shutdownFailure.get());
    }

    @Test
    void shutdownFromEntityWorkerIsRejectedWithoutBreakingTheNextBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity first = mockEntity(level, BlockPos.ZERO);
        doAnswer(invocation -> {
            assertThrows(IllegalStateException.class, this.engine::shutdown);
            return null;
        }).when(first).tick();
        final RegionEntityTickPlan firstPlan = plan(level);
        assertTrue(firstPlan.accept(first));
        firstPlan.executeAndAwait();

        final Entity second = mockEntity(level, BlockPos.ZERO);
        final RegionEntityTickPlan secondPlan = plan(level);
        assertNotNull(secondPlan, "a rejected in-batch shutdown must not close the engine");
        assertTrue(secondPlan.accept(second));
        secondPlan.executeAndAwait();
        verify(second).tick();
    }

    @Test
    void shutdownFromDeferredServerWorkIsRejectedWithoutBreakingTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, BlockPos.ZERO);
        final Entity foreign = mockEntity(level, new BlockPos(4096, 64, 0));
        final AtomicInteger foreignRuns = new AtomicInteger();
        doAnswer(invocation -> {
            // The batch does not own the foreign entity, so its tick is queued for the server thread after
            // the batch. That thread still owns the batch, so a shutdown there must be rejected instead of
            // waiting for a batch that only it can finish.
            this.engine.runInEntityRegion(foreign, () -> {
                assertThrows(IllegalStateException.class, this.engine::shutdown);
                foreignRuns.incrementAndGet();
            });
            return null;
        }).when(vehicle).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(vehicle));
        plan.executeAndAwait();

        assertEquals(1, foreignRuns.get());
        assertEquals(1L, this.engine.diagnostics().deferredEntityTicks());
        final Entity next = mockEntity(level, BlockPos.ZERO);
        final RegionEntityTickPlan nextPlan = plan(level);
        assertNotNull(nextPlan, "a rejected in-batch shutdown must not close the engine");
        assertTrue(nextPlan.accept(next));
        nextPlan.executeAndAwait();
        verify(next).tick();
    }

    @Test
    void unloadWaitsForBatchAndCancelsItsEntityTasks() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, BlockPos.ZERO);
        final AtomicInteger scheduledRuns = new AtomicInteger();
        final var scheduled = this.engine.entityScheduler().runAtFixedRate(entity, scheduledRuns::incrementAndGet, 0L, 1L);
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(release, "entity batch was not released");
            return null;
        }).when(entity).tick();
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batch = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "unload-entity-batch-test");
        batch.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS));
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unload = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "entity-level-unload-test");
        unload.start();
        release.countDown();
        batch.join(10_000L);
        unload.join(10_000L);

        assertFalse(batch.isAlive());
        assertFalse(unload.isAlive());
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
        assertTrue(scheduled.isCancelled());
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.engine.drainRegionTasks(level, BlockPos.ZERO);
        assertEquals(0, scheduledRuns.get());
    }

    @Test
    void staleVehicleLinkDoesNotExpandBatchMembership() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, BlockPos.ZERO);
        final Entity stalePassenger = mockEntity(level, new BlockPos(4096, 64, 0));
        when(stalePassenger.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(stalePassenger)).thenReturn(false);
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(vehicle));
        plan.executeAndAwait();

        assertFalse(this.engine.isOwnedByCurrentRegion(stalePassenger));
        final AtomicBoolean ran = new AtomicBoolean();
        this.engine.runInEntityRegion(stalePassenger, () -> ran.set(true));
        assertTrue(ran.get());
        // The stale passenger keeps its own tracked region after the deferred server-thread handoff;
        // it was never folded into the vehicle's group.
        assertEquals(2, this.engine.diagnostics().regionCount());
    }

    @Test
    void sameRegionResolvedDuringRetryIsAddedToItsExistingGroup() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity first = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity second = mockEntity(level, new BlockPos(4096, 64, 0));
        final Entity late = mockEntity(level, new BlockPos(8192, 64, 0));
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));
        assertTrue(plan.accept(late));
        final AtomicInteger lookups = new AtomicInteger();
        final RegionTickBarrier retryBarrier = condition -> {
            if (lookups.incrementAndGet() == 2) {
                when(late.blockPosition()).thenReturn(new BlockPos(0, 64, 0));
                this.engine.moveRegionEntity(late);
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };

        plan.executeAndAwait(retryBarrier);

        verify(first).tick();
        verify(second).tick();
        verify(late).tick();
        assertEquals(3L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void entityOutsideTheTickingRangeStaysOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));

        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, candidate -> false, PARKING_BARRIER);
        assertNotNull(plan);
        assertFalse(plan.accept(entity));
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void entityTickedByItsVehicleStaysOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(1, 64, 1));
        final Entity passenger = mockEntity(level, new BlockPos(1, 64, 1));
        when(passenger.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(passenger)).thenReturn(true);

        final RegionEntityTickPlan plan = plan(level);
        assertFalse(plan.accept(passenger));
    }

    @Test
    void staleVehicleIsDroppedBeforeAccepting() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(1, 64, 1));
        final Entity rider = mockEntity(level, new BlockPos(1, 64, 1));
        when(rider.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(rider)).thenReturn(false);

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(rider));
        verify(rider).stopRiding();
    }

    @Test
    void removedEntityStaysOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        when(entity.isRemoved()).thenReturn(true);

        final RegionEntityTickPlan plan = plan(level);
        assertFalse(plan.accept(entity));
    }

    @Test
    void crossRegionPassengerStackStaysOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity passenger = mockEntity(level, new BlockPos(8192, 64, 0));
        when(vehicle.getPassengers()).thenReturn(List.of(passenger));
        when(passenger.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(passenger)).thenReturn(true);

        final RegionEntityTickPlan plan = plan(level);
        assertFalse(plan.accept(passenger));
        assertFalse(plan.accept(vehicle));
    }

    @Test
    void sameRegionPassengerStackIsAccepted() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity vehicle = mockEntity(level, new BlockPos(0, 64, 0));
        final Entity passenger = mockEntity(level, new BlockPos(1, 64, 1));
        when(vehicle.getPassengers()).thenReturn(List.of(passenger));
        when(passenger.getVehicle()).thenReturn(vehicle);
        when(vehicle.hasPassenger(passenger)).thenReturn(true);

        final RegionEntityTickPlan plan = plan(level);
        assertFalse(plan.accept(passenger));
        assertTrue(plan.accept(vehicle));
    }

    @Test
    void mergedSnapshotRegionIsReResolvedBeforeTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(0, 64, 0));
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));

        // Merge the snapshot region with the adjacent section's region while the plan is collected.
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());

        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        plan.executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        assertEquals(1, tickThreads.size());
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().skippedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void acceptedSnapshotIsSkippedWhenTheLevelClearedBeforeTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        assertEquals(1, this.engine.diagnostics().regionCount());

        this.engine.clearLevelRegionTasks(level);
        assertEquals(0, this.engine.diagnostics().regionCount());

        plan.executeAndAwait();

        verify(entity, times(0)).checkDespawn();
        verify(entity, times(0)).tick();
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().skippedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0, this.engine.diagnostics().trackedEntityCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void snapshotRegionRemovedBeforeTheBatchFallsBackToTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));

        // The region dies between collection and lease acquisition.
        this.engine.removeRegionEntity(entity);
        assertEquals(0, this.engine.diagnostics().regionCount());

        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        plan.executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void migratingEntityKeepsSnapshotOwnershipUntilTheBatchFinishes() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final BlockPos original = new BlockPos(0, 64, 0);
        final BlockPos migrated = new BlockPos(4096, 64, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(original);
        final Entity entity = mockEntity(level, original);
        when(entity.blockPosition()).thenAnswer(invocation -> position.get());
        final AtomicInteger taskRuns = new AtomicInteger();
        final ScheduledRegionTask scheduled = this.engine.entityScheduler().runDelayed(entity, taskRuns::incrementAndGet, 0L);

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        final AtomicInteger regionXDuringTick = new AtomicInteger(Integer.MIN_VALUE);
        doAnswer(invocation -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(entity), "the batch group must own the entity during its tick");
            position.set(migrated);
            this.engine.moveRegionEntity(entity);
            regionXDuringTick.set(regionX(this.engine, entity));
            return null;
        }).when(entity).tick();

        plan.executeAndAwait();

        assertEquals(0, regionXDuringTick.get(), "the snapshot region must stay authoritative during the tick");
        assertEquals(16, regionX(this.engine, entity), "the handoff must be published after the batch");
        this.engine.drainRegionTasks(level, original);
        assertEquals(0, taskRuns.get(), "the old region must not run work for the migrated entity");
        this.engine.drainRegionTasks(level, migrated);
        assertEquals(1, taskRuns.get(), "scheduled work must follow the entity after the handoff");
        assertTrue(scheduled.isDone());
    }

    @Test
    void shutdownWaitsForTheBatchWhileItAcquiresTheRegionLease() throws InterruptedException {
        this.installEngine(true, true);
        final ServerLevel level = mockLevel();
        final BlockPos pos = new BlockPos(1, 64, 1);
        final Entity entity = mockEntity(level, pos);
        final CountDownLatch drainStarted = new CountDownLatch(1);
        final CountDownLatch releaseDrain = new CountDownLatch(1);
        this.engine.regionScheduler().execute(level, pos, () -> {
            drainStarted.countDown();
            awaitOrFail(releaseDrain, "the region drain was never released");
        });
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainThread = new Thread(() -> {
            try {
                this.engine.drainLevelRegionTasks(level);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "level-drain-test");
        drainThread.start();
        assertTrue(drainStarted.await(10, TimeUnit.SECONDS), "the region drain did not start on a worker");

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        final AtomicInteger ticked = new AtomicInteger();
        doAnswer(invocation -> {
            ticked.incrementAndGet();
            return null;
        }).when(entity).tick();

        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "entity-batch-test");
        batchThread.start();
        Thread.sleep(200L);
        assertTrue(batchThread.isAlive(), "the batch must wait for the region lease");

        final AtomicBoolean shutdownReturned = new AtomicBoolean();
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdownThread = new Thread(() -> {
            try {
                this.engine.shutdown();
                shutdownReturned.set(true);
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "engine-shutdown-test");
        shutdownThread.start();
        Thread.sleep(200L);
        assertFalse(shutdownReturned.get(), "shutdown must wait for the batch that is acquiring a lease");
        assertEquals(0, ticked.get());

        releaseDrain.countDown();
        batchThread.join(10_000L);
        shutdownThread.join(10_000L);
        drainThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the entity batch never completed");
        assertFalse(shutdownThread.isAlive(), "shutdown never returned");
        assertFalse(drainThread.isAlive(), "the level drain never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(shutdownFailure.get(), () -> "shutdown failed: " + shutdownFailure.get());
        assertNull(drainFailure.get(), () -> "level drain failed: " + drainFailure.get());
        assertEquals(1, ticked.get());
    }

    @Test
    void levelUnloadWaitsForTheRunningBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the entity tick was never released");
            tickFinished.set(true);
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "entity-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the entity tick never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                if (!tickFinished.get()) {
                    unloadFailure.set(new AssertionError("level unload returned before the entity tick finished"));
                }
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "level-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "level unload must wait for the running batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the entity batch never completed");
        assertFalse(unloadThread.isAlive(), "level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void levelUnloadWaitsForTheActiveBatchAndRetiresTheDeferredRegion() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        doAnswer(invocation -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the entity tick was never released");
            // Defer the removal to the batch handoff, which retires the now empty region on the server
            // thread before the batch reports completion.
            this.engine.removeRegionEntity(entity);
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        assertEquals(1, this.engine.diagnostics().regionCount());
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = batchThread(plan, batchFailure, "entity-clear-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the entity tick never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "entity-clear-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level clear must wait for the active entity batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the entity batch never completed");
        assertFalse(unloadThread.isAlive(), "the level clear never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "clear failed: " + unloadFailure.get());
        verify(entity, times(1)).tick();
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().regionCount(), "the deferred removal must retire the region");
        assertEquals(0, this.engine.diagnostics().trackedEntityCount());
        assertNull(this.engine.beginEntityTickPlan(level, ignored -> true, PARKING_BARRIER), "the cleared level identity must reject new plans");
    }

    @Test
    void rejectedWorkerGroupsRunOnTheServerThreadBeforeTheBatchCompletes() throws ReflectiveOperationException {
        this.installEngine(true);
        closeWorkerPool(this.engine);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), tickThreads.toString());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    /**
     * Creates the engine and installs it as the active region threading implementation, so the entity
     * tick hooks of the level reach it exactly like they do on a server.
     */
    private void installEngine(boolean entityTickWorkers) {
        this.installEngine(entityTickWorkers, false);
    }

    private void installEngine(boolean entityTickWorkers, boolean workerExecution) {
        // These tests exercise the worker slice mechanics directly; production uses MAIN_THREAD_ONLY and
        // therefore runs the entity slice on the server thread.
        final RegionThreadingEngine created = new RegionThreadingEngine(2, workerExecution, false, entityTickWorkers, false, false, false, RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
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
        return level;
    }

    private static Entity mockEntity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        return entity;
    }

    private static void setEntityTickList(ServerLevel level, EntityTickList tickingEntities) throws ReflectiveOperationException {
        final Field field = ServerLevel.class.getDeclaredField("entityTickList");
        field.setAccessible(true);
        field.set(level, tickingEntities);
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

    private static void closeWorkerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        final Object pool = field.get(engine);
        final Method close = pool.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(pool);
    }

    private static String describeFailure(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        final java.io.StringWriter writer = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    private static Runnable blockingTick(CountDownLatch ticking, CountDownLatch release, AtomicReference<Throwable> failure) {
        return () -> {
            try {
                ticking.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("entity batch was not released");
                }
            } catch (Exception exception) {
                failure.set(exception);
                throw new IllegalStateException(exception);
            }
        };
    }

    private static void stubGuardEntityTick(ServerLevel level) {
        doAnswer(invocation -> {
            final java.util.function.Consumer<Entity> task = invocation.getArgument(0);
            task.accept(invocation.getArgument(1));
            return null;
        }).when(level).guardEntityTick(any(), any());
    }

    private static void stubTickNonPassenger(ServerLevel level) {
        doAnswer(invocation -> {
            invocation.<Entity>getArgument(0).tick();
            return null;
        }).when(level).tickNonPassenger(any());
    }

    private static Thread batchThread(RegionEntityTickPlan plan, AtomicReference<Throwable> failure, String name) {
        return new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, name);
    }

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
