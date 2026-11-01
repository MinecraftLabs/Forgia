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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RegionThreadingEngineTest {
    private RegionThreadingEngine engine;
    private final AtomicReference<Throwable> drainFailure = new AtomicReference<>();

    @AfterEach
    void shutdownEngine() {
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
    }

    @Test
    void delayedRegionTaskRunsInOwningContext() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = new BlockPos(17, 64, -17);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.regionScheduler().runDelayed(level, pos, () -> {
            runs.incrementAndGet();
            assertEquals(RegionThreadingContext.Kind.REGION, this.engine.currentContext().kind());
            assertSame(level, this.engine.currentContext().level().orElseThrow());
            assertTrue(this.engine.isOwnedByCurrentRegion(level, pos));
        }, 1L);

        this.drainRegion(level, pos);
        assertEquals(0, runs.get());
        assertFalse(task.isDone());
        this.drainRegion(level, pos);
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
        assertFalse(task.cancel());
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
    }

    @Test
    void globalTickDrainRunsQueuedTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.globalScheduler().execute(() -> {
            runs.incrementAndGet();
            assertEquals(RegionThreadingContext.Kind.GLOBAL_REGION, this.engine.currentContext().kind());
        });
        this.engine.drainGlobalTasks();

        assertEquals(1, runs.get());
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertFalse(this.engine.diagnostics().globalRegionRunning());
        assertEquals(1L, this.engine.diagnostics().globalTickCount());
    }

    @Test
    void globalWorkerDrainRunsTasksOnWorkerThread() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, true);
        final CountDownLatch ran = new CountDownLatch(1);
        final AtomicReference<RegionThreadingContext.Kind> kind = new AtomicReference<>();

        this.engine.globalScheduler().execute(() -> {
            kind.set(this.engine.currentContext().kind());
            ran.countDown();
        });
        this.engine.drainGlobalTasks();

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals(RegionThreadingContext.Kind.GLOBAL_REGION, kind.get());
    }

    @Test
    void levelTickDrainRunsQueuedRegionTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = new BlockPos(0, 64, 0);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.regionScheduler().execute(level, pos, () -> {
            runs.incrementAndGet();
            assertEquals(RegionThreadingContext.Kind.REGION, this.engine.currentContext().kind());
            assertTrue(this.engine.isOwnedByCurrentRegion(level, pos));
        });
        this.engine.drainLevelRegionTasks(level);

        assertEquals(1, runs.get());
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void levelTickDrainIgnoresOtherLevels() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerLevel otherLevel = mock(ServerLevel.class);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.regionScheduler().execute(level, BlockPos.ZERO, runs::incrementAndGet);
        this.engine.drainLevelRegionTasks(otherLevel);
        assertEquals(0, runs.get());

        this.engine.drainLevelRegionTasks(level);
        assertEquals(1, runs.get());
    }

    @Test
    void crossRegionBlockWorkIsDeferredAndRestoresPreviousContext() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos outer = new BlockPos(0, 0, 0);
        final BlockPos inner = new BlockPos(256, 0, 256);
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger innerRuns = new AtomicInteger();

        this.engine.runInBlockRegion(level, outer, () -> {
            this.engine.executeOrSchedule(level, outer, runs::incrementAndGet);
            assertTrue(this.engine.isOwnedByCurrentRegion(level, outer));
            this.engine.runInBlockRegion(level, inner, () -> {
                assertTrue(this.engine.isOwnedByCurrentRegion(level, inner));
                innerRuns.incrementAndGet();
            });
            assertTrue(this.engine.isOwnedByCurrentRegion(level, outer));
            assertFalse(this.engine.isOwnedByCurrentRegion(level, inner));
            assertEquals(1, runs.get(), "same-region work must run inline");
            assertEquals(0, innerRuns.get(), "cross-region work must not run under the outer lease");
        });

        this.drainRegion(level, inner);
        assertEquals(1, runs.get());
        assertEquals(1, innerRuns.get());
        this.drainRegion(level, outer);
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void globalAndLevelContextsAreRestored() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);

        this.engine.runInGlobalRegion(() -> {
            assertEquals(RegionThreadingContext.Kind.GLOBAL_REGION, this.engine.currentContext().kind());
            assertTrue(this.engine.currentContext().isTickThread());
            assertTrue(this.engine.currentContext().level().isEmpty());
        });
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertFalse(this.engine.diagnostics().globalRegionRunning());

        this.engine.runInLevelTick(level, () -> {
            assertEquals(RegionThreadingContext.Kind.LEVEL, this.engine.currentContext().kind());
            assertSame(level, this.engine.currentContext().level().orElseThrow());
        });
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
    }

    @Test
    void nestedGlobalRegionTaskRunsInCurrentContext() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.runInGlobalRegion(() -> {
            assertEquals(RegionThreadingContext.Kind.GLOBAL_REGION, this.engine.currentContext().kind());
            this.engine.runInGlobalRegion(runs::incrementAndGet);
        });

        assertEquals(1, runs.get());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertFalse(this.engine.diagnostics().globalRegionRunning());
    }

    @Test
    void globalContextIsRestoredWhenTaskThrows() {
        this.engine = new RegionThreadingEngine(1, false, false);

        assertThrows(IllegalStateException.class, () -> this.engine.runInGlobalRegion(() -> {
            throw new IllegalStateException("expected");
        }));

        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertFalse(this.engine.diagnostics().globalRegionRunning());
    }

    @Test
    void regionContextIsRestoredWhenQueuedTaskThrows() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = new BlockPos(0, 0, 0);

        this.engine.regionScheduler().execute(level, pos, () -> {
            throw new IllegalStateException("expected");
        });
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        this.drainRegion(level, pos);

        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertFalse(this.engine.isOwnedByCurrentRegion(level, pos));
        assertEquals(1, this.engine.diagnostics().failedQueuedTaskCount());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void nestedEntityRegionTaskRunsInCurrentContext() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.runInEntityRegion(entity, () -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(entity));
            this.engine.runInEntityRegion(entity, runs::incrementAndGet);
        });

        assertEquals(1, runs.get());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void entityTaskFollowsMigrationAndDoesNotUseGlobalTicks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, new BlockPos(0, 0, 0));
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 1L, 2L);

        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
        this.drainEntityRegion(entity);
        assertEquals(1, runs.get());

        final BlockPos migrated = new BlockPos(256, 0, 256);
        when(entity.blockPosition()).thenReturn(migrated);
        this.engine.moveRegionEntity(entity);
        this.drainRegion(level, new BlockPos(0, 0, 0));
        assertEquals(1, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(1, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(2, runs.get());
        assertFalse(task.isDone());
        assertTrue(task.cancel());
        assertTrue(task.isDone());
    }

    @Test
    void entityTaskMigrationPreservesInitialDelay() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, new BlockPos(0, 0, 0));
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 3L);

        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());

        final BlockPos migrated = new BlockPos(256, 0, 256);
        when(entity.blockPosition()).thenReturn(migrated);
        this.engine.moveRegionEntity(entity);
        this.drainRegion(level, new BlockPos(0, 0, 0));
        this.drainRegion(level, migrated);
        assertEquals(0, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(0, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
    }

    @Test
    void entityTaskMigrationPreservesFixedRateRemainder() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, new BlockPos(0, 0, 0));
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 1L, 3L);

        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
        this.drainEntityRegion(entity);
        assertEquals(1, runs.get());

        final BlockPos migrated = new BlockPos(256, 0, 256);
        when(entity.blockPosition()).thenReturn(migrated);
        this.engine.moveRegionEntity(entity);
        this.drainRegion(level, new BlockPos(0, 0, 0));
        this.drainRegion(level, migrated);
        assertEquals(1, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(1, runs.get());
        this.drainRegion(level, migrated);
        assertEquals(2, runs.get());
        assertFalse(task.isDone());
        assertTrue(task.cancel());
        assertTrue(task.isDone());
    }

    @Test
    void removedEntityTaskRetiresBeforeExecution() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 0L, 1L);
        when(entity.isRemoved()).thenReturn(true);

        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
        assertFalse(task.cancel());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
    }

    @Test
    void entityRemovedBeforeStartDoesNotLeakTask() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        when(entity.isRemoved()).thenReturn(false, true);
        final AtomicInteger runs = new AtomicInteger();

        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 0L);

        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
    }

    @Test
    void entityTaskScheduledIntoClosedQueueRetiresWrapper() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        this.engine.addRegionSection(level, 0, 0);
        closeRegionQueue(this.engine, level, 0, 0);
        final AtomicInteger runs = new AtomicInteger();

        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 0L);
        final ScheduledRegionTask child = childTask(task);

        assertTrue(task.isDone());
        assertTrue(task.isCancelled(), "a child cancelled by queue retirement must end the public handle cancelled");
        assertNotNull(child);
        assertTrue(child.isCancelled());
        assertTrue(child.isDone());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.drainRegion(level, BlockPos.ZERO);
        assertEquals(0, runs.get());
    }

    @Test
    void entityTaskAdmittedAfterLevelClearEndsCancelledAndUnregistered() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        this.engine.clearLevelRegionTasks(level);

        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 0L);
        final ScheduledRegionTask child = childTask(task);

        assertTrue(task.isCancelled(), "a periodic task rejected by the closed level must end cancelled");
        assertTrue(task.isDone());
        assertNotNull(child);
        assertTrue(child.isCancelled(), "no live child may be published for a closed level");
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.engine.drainLevelRegionTasks(level);
        assertEquals(0, runs.get());
    }

    @Test
    void workerRescheduleRacingLevelClearEndsCancelledAndUnregistered() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, () -> {
            runs.incrementAndGet();
            running.countDown();
            await(release);
        }, 0L, 1L);

        final Thread drainer = this.drainInThread(level);
        assertTrue(running.await(5, TimeUnit.SECONDS), "the periodic task never ran on its worker");
        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread clearer = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
        }, "level-clear-reschedule-race-test");
        clearer.start();
        awaitThreadBlockedOrWaiting(clearer);
        assertTrue(clearer.isAlive(), "the level clear must wait for the rescheduling worker");

        release.countDown();
        this.joinDrain(drainer);
        clearer.join(5_000L);

        assertFalse(clearer.isAlive(), "the level clear never returned");
        assertNull(clearFailure.get(), () -> "the level clear failed: " + clearFailure.get());
        assertEquals(1, runs.get(), "the running period must not be repeated after the clear");
        assertTrue(task.isCancelled(), "the public handle must end cancelled when the clear retires the child");
        assertTrue(task.isDone());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
        final ScheduledRegionTask child = childTask(task);
        if (child != null) {
            assertTrue(child.isCancelled(), "no live child may remain queued after the clear");
        }
        this.engine.drainLevelRegionTasks(level);
        assertEquals(1, runs.get());
    }

    @Test
    void entityMoveIntoClearedLevelRetiresScheduledTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerLevel clearedLevel = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 0L, 1L);
        final ScheduledRegionTask child = childTask(task);
        assertNotNull(child);

        this.engine.clearLevelRegionTasks(clearedLevel);
        when(entity.level()).thenReturn(clearedLevel);
        this.engine.moveRegionEntity(entity);

        assertTrue(task.isCancelled(), "a move into a cleared level must retire the public handle");
        assertTrue(task.isDone());
        assertTrue(child.isCancelled(), "the child queued in the old level must be cancelled with its wrapper");
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.engine.drainLevelRegionTasks(level);
        assertEquals(0, runs.get());
    }

    @Test
    void entityMoveToNonServerLevelRetiresScheduledTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 0L, 1L);
        final ScheduledRegionTask child = childTask(task);
        assertNotNull(child);

        when(entity.level()).thenReturn(mock(Level.class));
        this.engine.moveRegionEntity(entity);

        assertTrue(task.isCancelled(), "a move out of the server side must retire the public handle");
        assertTrue(task.isDone());
        assertTrue(child.isCancelled());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.engine.drainLevelRegionTasks(level);
        assertEquals(0, runs.get());
    }

    @Test
    void removingEntityRetiresItsScheduledTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 0L, 1L);

        this.engine.removeRegionEntity(entity);

        assertTrue(task.isCancelled());
        assertTrue(task.isDone());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
    }

    @Test
    void cancelledEntityTaskCancelsOutstandingRegionWork() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 0L);

        assertTrue(task.cancel());
        assertTrue(task.isCancelled());
        this.drainEntityRegion(entity);
        assertEquals(0, runs.get());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void cancellingEntityTaskDuringExecutionStopsRepeat() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, () -> {
            runs.incrementAndGet();
            running.countDown();
            await(release);
        }, 0L, 1L);

        final Thread drainer = new Thread(() -> this.drainEntityRegion(entity));
        drainer.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(task.cancel());
        release.countDown();
        drainer.join(5_000L);

        assertFalse(drainer.isAlive());
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        this.drainEntityRegion(entity);
        assertEquals(1, runs.get());
    }

    @Test
    void failedEntityTaskRetiresAndDoesNotRepeat() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("expected");
        }, 0L, 1L);

        this.drainEntityRegion(entity);
        this.drainEntityRegion(entity);
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
        assertFalse(task.isCancelled());
        assertEquals(1, this.engine.diagnostics().failedQueuedTaskCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
    }

    @Test
    void unloadedLevelCancelsEntityAndRegionTasks() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask entityTask = this.engine.entityScheduler().runAtFixedRate(entity, runs::incrementAndGet, 0L, 1L);
        final ScheduledRegionTask regionTask = this.engine.regionScheduler().runDelayed(level, BlockPos.ZERO, runs::incrementAndGet, 0L);

        this.engine.clearLevelRegionTasks(level);
        assertTrue(entityTask.isCancelled());
        assertTrue(regionTask.isCancelled());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void levelClearSerializesWithAnAdmissionThatAlreadyPassedTheShutdownCheck() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final CountDownLatch atAdmission = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        this.engine.setBeforeRegionAdmissionForTesting(() -> {
            atAdmission.countDown();
            await(releaseAdmission);
        });
        final AtomicReference<ScheduledRegionTask> accepted = new AtomicReference<>();
        final AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
        final Thread admitter = new Thread(() -> {
            try {
                accepted.set(this.engine.regionScheduler().runDelayed(level, pos, () -> {}, 0L));
            } catch (Throwable throwable) {
                admissionFailure.set(throwable);
            }
        }, "region-admission-clear-race-test");
        admitter.start();
        assertTrue(atAdmission.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread clearer = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
        }, "level-clear-admission-race-test");
        clearer.start();
        awaitThreadBlockedOrWaiting(clearer);
        assertTrue(clearer.isAlive(), "the level clear must wait for the admission that is already in flight");

        this.engine.setBeforeRegionAdmissionForTesting(null);
        releaseAdmission.countDown();
        admitter.join(5_000L);
        clearer.join(5_000L);

        assertFalse(admitter.isAlive(), "the admission never returned");
        assertFalse(clearer.isAlive(), "the level clear never returned");
        assertNull(admissionFailure.get(), () -> "the admission failed: " + admissionFailure.get());
        assertNull(clearFailure.get(), () -> "the level clear failed: " + clearFailure.get());
        assertNotNull(accepted.get());
        assertTrue(accepted.get().isCancelled(), "the clear must cancel the admission that won the lock");
        assertEquals(0, this.engine.diagnostics().regionCount(), "no region may survive the clear");
        assertEquals(0, this.engine.diagnostics().sectionCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
    }

    @Test
    void concurrentAdmissionsAfterLevelClearLeaveNoRegionStateOrTasks() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        final List<ScheduledRegionTask> accepted = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int threadIndex = 0; threadIndex < 4; ++threadIndex) {
            final int kind = threadIndex;
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (stop.getCount() > 0L) {
                    try {
                        if (kind == 0) {
                            accepted.add(this.engine.regionScheduler().runDelayed(level, BlockPos.ZERO, runs::incrementAndGet, 0L));
                        } else if (kind == 1) {
                            accepted.add(this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 0L));
                        } else if (kind == 2) {
                            this.engine.addRegionSection(level, 0, 0);
                        } else {
                            this.engine.regionScheduler().execute(level, BlockPos.ZERO, runs::incrementAndGet);
                        }
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                        return;
                    }
                }
            }, "region-level-admission-" + threadIndex);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        Thread.sleep(200L);
        this.engine.clearLevelRegionTasks(level);
        stop.countDown();
        for (final Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "an admission thread never stopped");
        }

        assertTrue(unexpected.isEmpty(), () -> "unexpected admission failure: " + unexpected);
        assertEquals(0, this.engine.diagnostics().regionCount(), "no region may be recreated after the clear");
        assertEquals(0, this.engine.diagnostics().sectionCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        for (final ScheduledRegionTask task : accepted) {
            assertTrue(task.isDone(), "every accepted task must be cancelled by the clear");
        }
        this.engine.drainLevelRegionTasks(level);
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, runs.get(), "no admitted task may run after the clear");

        final ServerLevel reloaded = mock(ServerLevel.class);
        final AtomicInteger reloadedRuns = new AtomicInteger();
        this.engine.regionScheduler().execute(reloaded, BlockPos.ZERO, reloadedRuns::incrementAndGet);
        this.engine.drainLevelRegionTasks(reloaded);
        assertEquals(1, reloadedRuns.get(), "a reloaded level identity must still accept work");
    }

    @Test
    void workerDrainRunsRegionTasksConcurrently() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos firstPos = new BlockPos(0, 64, 0);
        final BlockPos secondPos = new BlockPos(256, 64, 0);
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(2);
        final Set<String> workerThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger ownedTasks = new AtomicInteger();

        final Runnable first = workerTask(level, firstPos, started, release, finished, workerThreads, ownedTasks);
        final Runnable second = workerTask(level, secondPos, started, release, finished, workerThreads, ownedTasks);
        this.engine.regionScheduler().execute(level, firstPos, first);
        this.engine.regionScheduler().execute(level, secondPos, second);

        final AtomicBoolean drainReturned = new AtomicBoolean();
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = new Thread(() -> {
            try {
                this.engine.drainLevelRegionTasks(level);
                drainReturned.set(true);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "level-drain-test");
        drainer.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "both region tasks should start concurrently");
            assertFalse(drainReturned.get(), "the level drain must not return while its workers still run");
        } finally {
            release.countDown();
        }
        drainer.join(5_000L);
        assertFalse(drainer.isAlive(), "the level drain never returned");
        assertNull(drainFailure.get(), () -> "the level drain failed: " + drainFailure.get());
        assertTrue(drainReturned.get());
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(2, ownedTasks.get());
        assertEquals(2, workerThreads.size());
        assertTrue(workerThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")));
    }

    @Test
    void blockTickHookWithoutEngineRunsInline() {
        final ServerLevel level = mock(ServerLevel.class);
        final AtomicInteger runs = new AtomicInteger();

        RegionThreadingHooks.runBlockTickTask(level, BlockPos.ZERO, runs::incrementAndGet);

        assertEquals(1, runs.get());
        assertFalse(RegionThreadingManager.isInstalled());
    }

    @Test
    void busyRegionDefersBlockTickWithoutViolationOrNestedLease() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, pos, () -> {
            ticking.countDown();
            await(release);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(ticking.await(5, TimeUnit.SECONDS));

        RegionThreadingHooks.runBlockTickTask(level, pos, () -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, pos));
            runs.incrementAndGet();
        });

        assertEquals(0, runs.get());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        release.countDown();
        this.joinDrain(drainer);
        this.drainRegion(level, pos);
        assertEquals(1, runs.get(), "the deferred tick must run exactly once under the owner");
    }

    @Test
    void blockTickInLevelTickContextRunsUnderItsOwner() {
        this.engine = new RegionThreadingEngine(1, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final AtomicInteger runs = new AtomicInteger();

        this.engine.runInLevelTick(level, () -> RegionThreadingHooks.runBlockTickTask(level, pos, () -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, pos));
            runs.incrementAndGet();
        }));

        assertEquals(1, runs.get(), "a level tick block tick must run in its region, not behind a queue");
        assertEquals(RegionThreadingContext.Kind.NONE, this.engine.currentContext().kind());
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void blockTickDeferredToABusyRegionRunsOnItsNextTick() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, pos, () -> {
            ticking.countDown();
            await(release);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(ticking.await(5, TimeUnit.SECONDS));

        RegionThreadingHooks.runBlockTickTask(level, pos, runs::incrementAndGet);
        release.countDown();
        this.joinDrain(drainer);
        awaitRegionIdle();

        assertEquals(0, runs.get(), "the deferred tick must wait for the next tick of the busy region");
        this.drainRegion(level, pos);
        assertEquals(1, runs.get(), "the deferred tick must run exactly once under the owner");
    }

    @Test
    void crossRegionHandoffsPreserveSubmissionOrderAndRunOnOwner() {
        this.engine = new RegionThreadingEngine(2, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos source = BlockPos.ZERO;
        final BlockPos target = new BlockPos(4096, 64, 0);
        final List<Integer> order = new CopyOnWriteArrayList<>();
        final AtomicInteger sourceRuns = new AtomicInteger();
        this.engine.regionScheduler().execute(level, source, () -> {
            sourceRuns.incrementAndGet();
            RegionThreadingHooks.runBlockTickTask(level, target, () -> {
                assertTrue(this.engine.isOwnedByCurrentRegion(level, target));
                order.add(1);
            });
            RegionThreadingHooks.runBlockTickTask(level, target, () -> {
                assertTrue(this.engine.isOwnedByCurrentRegion(level, target));
                order.add(2);
            });
        });
        this.drainRegion(level, source);

        assertEquals(1, sourceRuns.get());
        // The default server-thread bridge may drain the handoff in the same pass; it must still run
        // under the target context and exactly once, never inline in the source task.
        this.drainRegion(level, target);
        assertEquals(List.of(1, 2), order);
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void queuedBlockTickRehandsOffAfterOwnerChanges() {
        this.engine = new RegionThreadingEngine(2, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);
        final BlockPos pos = BlockPos.ZERO;
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, pos, () -> {
            this.engine.removeRegionSection(level, 16, 0);
            this.engine.runInBlockRegion(level, pos, () -> {
                assertTrue(this.engine.isOwnedByCurrentRegion(level, pos));
                runs.incrementAndGet();
            });
        });

        this.drainRegion(level, pos);
        this.drainRegion(level, pos);
        assertEquals(1, runs.get(), "the handoff must run exactly once under the current owner");
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void nestedSameRegionDrainLeavesWorkForTheNextTick() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final RegionCoordinate coordinate = RegionCoordinate.fromBlock(level, pos);
        final AtomicInteger outerRuns = new AtomicInteger();
        final AtomicInteger innerRuns = new AtomicInteger();
        this.engine.regionScheduler().execute(level, pos, () -> {
            outerRuns.incrementAndGet();
            this.engine.regionScheduler().execute(level, pos, innerRuns::incrementAndGet);
            this.engine.drainRegionTasks(level, pos);
            this.engine.drainLevelRegionTasks(level);
            assertEquals(0, innerRuns.get(), "a nested drain must not run work that belongs to the next tick");
        });

        this.engine.drainRegionTasks(level, pos);
        assertEquals(1, outerRuns.get());
        assertEquals(0, innerRuns.get());
        assertEquals(1L, regionQueueTick(this.engine, coordinate), "a nested drain must not advance the logical queue tick");

        this.engine.drainRegionTasks(level, pos);
        assertEquals(1, innerRuns.get(), "the work must run exactly once on the next tick");
        assertEquals(0, this.engine.diagnostics().regionCount(), "the drained region retires once it is empty");
    }

    @Test
    void nestedLevelDrainInsideRegionTickDoesNotConsumeTheRegionTimer() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final AtomicInteger outerRuns = new AtomicInteger();
        final AtomicInteger innerRuns = new AtomicInteger();
        this.engine.regionScheduler().execute(level, pos, () -> {
            outerRuns.incrementAndGet();
            this.engine.regionScheduler().execute(level, pos, innerRuns::incrementAndGet);
            this.engine.drainLevelRegionTasks(level);
            assertEquals(0, innerRuns.get());
        });
        final Object region = regionState(this.engine, RegionCoordinate.fromBlock(level, pos));

        this.engine.drainRegionTasks(level, pos);
        assertEquals(1, outerRuns.get());
        assertEquals(0L, regionTickCount(region), "the outer drain must not be a level tick");

        this.engine.drainLevelRegionTasks(level);
        assertEquals(1, innerRuns.get(), "the nested level drain must not consume the next region tick");
        assertEquals(1L, regionTickCount(region));
    }

    @Test
    void rejectedRegionWorkerDrainsQueuedBlockTickOnServerThread() {
        this.engine = new RegionThreadingEngine(1, true, false);
        closeWorkerPool(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final List<String> threads = new CopyOnWriteArrayList<>();

        this.engine.regionScheduler().execute(level, pos, () -> threads.add(Thread.currentThread().getName()));
        this.engine.drainLevelRegionTasks(level);

        assertEquals(List.of(Thread.currentThread().getName()), threads);
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void unloadWaitsForRegionWorkerBeforeCancellingQueues() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicInteger laterRuns = new AtomicInteger();
        final ScheduledRegionTask later = this.engine.regionScheduler().runDelayed(level, pos, laterRuns::incrementAndGet, 100L);
        this.engine.regionScheduler().execute(level, pos, () -> {
            ticking.countDown();
            await(release);
            finished.set(true);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(ticking.await(5, TimeUnit.SECONDS));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread unload = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "region-unload-test");
        unload.start();
        release.countDown();
        this.joinDrain(drainer);
        unload.join(5_000L);

        assertFalse(unload.isAlive());
        assertNull(failure.get());
        assertTrue(finished.get());
        assertTrue(later.isCancelled());
        assertEquals(0, laterRuns.get());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void shutdownWaitsForRegionWorkerBeforeClosingPool() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        this.engine.regionScheduler().execute(level, pos, () -> {
            ticking.countDown();
            await(release);
            finished.set(true);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(ticking.await(5, TimeUnit.SECONDS));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread shutdown = new Thread(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "region-shutdown-test");
        shutdown.start();
        release.countDown();
        this.joinDrain(drainer);
        shutdown.join(5_000L);

        assertFalse(shutdown.isAlive());
        assertNull(failure.get());
        assertTrue(finished.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void invalidEntitySchedulesAndShutdownAreRejected() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        assertThrows(IllegalArgumentException.class, () -> this.engine.entityScheduler().runDelayed(entity, () -> {}, -1L));
        assertThrows(IllegalArgumentException.class, () -> this.engine.entityScheduler().runAtFixedRate(entity, () -> {}, 0L, 0L));

        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, () -> {}, 100L);
        this.engine.shutdown();
        assertTrue(task.isCancelled());
        assertThrows(IllegalStateException.class, () -> this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {}));
    }

    @Test
    void levelDrainReturnsOnlyAfterItsWorkerFinished() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            started.countDown();
            await(release);
            finished.set(true);
        });

        final Thread drainer = this.drainInThread(level);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(finished.get(), "the worker is still running");
        assertTrue(drainer.isAlive(), "the level drain must wait for its worker");
        release.countDown();
        this.joinDrain(drainer);

        assertTrue(finished.get(), "the level drain returned before its worker finished");
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void acceptedButNotStartedRegionWorkerIsAwaitedInsteadOfDrainedTwice() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos firstPos = new BlockPos(0, 64, 0);
        final BlockPos secondPos = new BlockPos(25600, 64, 0);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 1600, 0);
        final RegionCoordinate firstRegion = RegionCoordinate.fromBlock(level, firstPos);
        final RegionCoordinate secondRegion = RegionCoordinate.fromBlock(level, secondPos);
        final CountDownLatch anyStarted = new CountDownLatch(1);
        final CountDownLatch bothStarted = new CountDownLatch(2);
        final CountDownLatch bothFinished = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final Runnable task = () -> {
            if (started.incrementAndGet() == 1) {
                anyStarted.countDown();
            }
            bothStarted.countDown();
            await(release);
            runs.incrementAndGet();
            bothFinished.countDown();
        };
        this.engine.regionScheduler().execute(level, firstPos, task);
        this.engine.regionScheduler().execute(level, secondPos, task);

        final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        final Thread firstDrain = this.drainInThread(level, firstFailure);
        assertTrue(anyStarted.await(5, TimeUnit.SECONDS));
        assertEquals(1, started.get(), "only one worker can run on the single worker thread");

        final AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        final Thread secondDrain = this.drainInThread(level, secondFailure);
        Thread.sleep(200L);
        assertEquals(1, started.get(), "an accepted but not started worker must not be drained inline");
        assertTrue(secondDrain.isAlive(), "the second drain must wait for the accepted worker");

        release.countDown();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        assertTrue(bothFinished.await(5, TimeUnit.SECONDS));
        this.joinDrain(firstDrain, firstFailure);
        this.joinDrain(secondDrain, secondFailure);

        assertEquals(2, runs.get(), "each queued task must run exactly once");
        assertEquals(1L, regionQueueTick(this.engine, firstRegion), "the first queue must advance exactly once");
        assertEquals(1L, regionQueueTick(this.engine, secondRegion), "the accepted queue must not be drained twice");
    }

    @Test
    void delayedRegionTaskSurvivesForcedMergeAndRunsOnLiveSuccessor() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        this.engine.addRegionSection(level, 0, 0);
        final AtomicInteger runs = new AtomicInteger();
        final AtomicBoolean owned = new AtomicBoolean();
        final ScheduledRegionTask task = this.engine.regionScheduler().runDelayed(level, pos, () -> {
            owned.set(this.engine.isOwnedByCurrentRegion(level, pos));
            runs.incrementAndGet();
        }, 1L);

        // Request a merge with the adjacent section while the delayed task is still queued.
        this.engine.addRegionSection(level, 16, 0);
        assertEquals(2, this.engine.diagnostics().regionCount());
        assertFalse(task.isCancelled());

        this.drainRegion(level, pos);
        assertEquals(0, runs.get());
        this.drainRegion(level, pos);

        assertEquals(1, runs.get(), "the delayed task must survive the merge request");
        assertTrue(owned.get(), "the task must run on the live region that owns the position");
        assertTrue(task.isDone());
        assertEquals(1, this.engine.diagnostics().regionCount(), "the pending merge must complete on the live successor");
    }

    @Test
    void fixedRateRegionTaskSurvivesForcedMergeAndReschedules() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        this.engine.addRegionSection(level, 0, 0);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.regionScheduler().runAtFixedRate(level, pos, runs::incrementAndGet, 1L, 2L);

        this.engine.addRegionSection(level, 16, 0);
        assertFalse(task.isCancelled());

        this.drainRegion(level, pos);
        this.drainRegion(level, pos);

        assertEquals(1, runs.get(), "the fixed-rate task must survive the merge request");
        assertFalse(task.isDone(), "the fixed-rate task must reschedule on the live region");
        assertTrue(task.cancel());
        assertTrue(task.isDone());
    }

    @Test
    void regionWorkerLifecycleWaitsFailFastWithoutChangingState() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final CountDownLatch ran = new CountDownLatch(1);
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
            ran.countDown();
        });

        this.engine.drainLevelRegionTasks(level);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(shutdownFailure.get() instanceof IllegalStateException, () -> "shutdown failed late: " + shutdownFailure.get());
        assertTrue(clearFailure.get() instanceof IllegalStateException, () -> "level clear failed late: " + clearFailure.get());
        assertEquals(1, this.engine.diagnostics().regionCount(), "rejected lifecycle waits must not clear state");

        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, new BlockPos(256, 64, 0), runs::incrementAndGet);
        this.engine.drainLevelRegionTasks(level);
        assertEquals(1, runs.get(), "the engine must keep accepting work after rejected worker lifecycle calls");
    }

    @Test
    void globalWorkerLifecycleWaitsFailFastWithoutChangingState() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, true);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final CountDownLatch ran = new CountDownLatch(1);
        this.engine.globalScheduler().execute(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
            ran.countDown();
        });
        this.engine.drainGlobalTasks();

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(shutdownFailure.get() instanceof IllegalStateException, () -> "shutdown failed late: " + shutdownFailure.get());
        assertTrue(clearFailure.get() instanceof IllegalStateException, () -> "level clear failed late: " + clearFailure.get());
        assertEquals(1, this.engine.diagnostics().regionCount());

        awaitGlobalIdle();
        final CountDownLatch secondRun = new CountDownLatch(1);
        this.engine.globalScheduler().execute(secondRun::countDown);
        Thread.sleep(60L);
        this.engine.drainGlobalTasks();
        assertTrue(secondRun.await(5, TimeUnit.SECONDS), "the engine must keep accepting global work");
    }

    @Test
    void managerClearUninstallsEngineWhenShutdownIsRejectedFromWorker() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch ran = new CountDownLatch(1);
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            try {
                RegionThreadingManager.clear();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
            ran.countDown();
        });

        this.engine.drainLevelRegionTasks(level);

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof IllegalStateException, () -> "clear failed late: " + failure.get());
        assertFalse(RegionThreadingManager.isInstalled(), "clear must uninstall the engine even when shutdown throws");

        final RegionThreadingEngine replacement = new RegionThreadingEngine(1, false, false);
        RegionThreadingManager.install(replacement);
        assertTrue(RegionThreadingManager.isInstalled());
        RegionThreadingManager.clear();
        assertFalse(RegionThreadingManager.isInstalled());
    }

    @Test
    void managerClearFromWorkerDoesNotDeadlockWithServerShutdown() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            started.countDown();
            await(release);
            try {
                RegionThreadingManager.clear();
            } catch (Throwable throwable) {
                workerFailure.set(throwable);
            }
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread serverClear = new Thread(() -> {
            try {
                RegionThreadingManager.clear();
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
        }, "manager-clear-test");
        serverClear.start();
        Thread.sleep(200L);
        assertTrue(serverClear.isAlive(), "server clear must wait for the running region worker");

        release.countDown();
        this.joinDrain(drainer);
        serverClear.join(5_000L);

        assertFalse(serverClear.isAlive(), "server clear never returned");
        assertNull(clearFailure.get(), () -> "server clear failed: " + clearFailure.get());
        assertTrue(workerFailure.get() instanceof IllegalStateException, () -> "worker clear failed late: " + workerFailure.get());
        assertFalse(RegionThreadingManager.isInstalled());
    }

    @Test
    void managerClearDoesNotUninstallReplacementInstalledWhileShutdownWaits() throws InterruptedException {
        final RegionThreadingEngine old = new RegionThreadingEngine(1, true, false);
        this.engine = old;
        RegionThreadingManager.install(old);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch enteredClear = new CountDownLatch(1);
        final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        final Thread serverClear = new Thread(() -> {
            enteredClear.countDown();
            try {
                RegionThreadingManager.clear();
            } catch (Throwable throwable) {
                serverFailure.set(throwable);
            }
        }, "manager-clear-replacement-test");

        synchronized (RegionThreadingManager.class) {
            serverClear.start();
            assertTrue(enteredClear.await(5, TimeUnit.SECONDS));
            // The captured instance and the worker check already happened; the thread now waits for the
            // manager monitor, which this test holds for the rest of the shutdown window.
            awaitThreadBlockedOrWaiting(serverClear);

            // The worker branch uninstalls the captured engine without taking the manager monitor, so it
            // can finish while the server clear is still blocked before its shutdown.
            final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            old.regionScheduler().execute(level, BlockPos.ZERO, () -> {
                try {
                    RegionThreadingManager.clear();
                } catch (Throwable throwable) {
                    workerFailure.set(throwable);
                }
            });
            old.drainLevelRegionTasks(level);
            assertTrue(workerFailure.get() instanceof IllegalStateException, () -> "worker clear failed late: " + workerFailure.get());
            assertFalse(RegionThreadingManager.isInstalled());

            final RegionThreadingEngine replacement = new RegionThreadingEngine(1, false, false);
            RegionThreadingManager.install(replacement);
            assertTrue(RegionThreadingManager.isInstalled());
        }

        serverClear.join(5_000L);
        assertFalse(serverClear.isAlive(), "the server clear never returned");
        assertNull(serverFailure.get(), () -> "server clear failed: " + serverFailure.get());
        assertTrue(RegionThreadingManager.isInstalled(), "the server clear must not uninstall the replacement engine");
        final RegionThreading[] installed = new RegionThreading[1];
        RegionThreadingManager.ifInstalled(threading -> installed[0] = threading);
        assertNotNull(installed[0]);

        final AtomicInteger replacementRuns = new AtomicInteger();
        installed[0].regionScheduler().execute(level, BlockPos.ZERO, replacementRuns::incrementAndGet);
        ((RegionThreadingEngine) installed[0]).drainLevelRegionTasks(level);
        assertEquals(1, replacementRuns.get(), "the replacement engine must still be usable");
    }

    @Test
    void periodicRescheduleDoesNotDeadlockWithMigration() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos origin = BlockPos.ZERO;
        final BlockPos migrated = new BlockPos(256, 0, 256);
        final AtomicReference<BlockPos> position = new AtomicReference<>(origin);
        final AtomicReference<Thread> moverThread = new AtomicReference<>();
        final CountDownLatch moverInPosition = new CountDownLatch(1);
        final CountDownLatch releaseMover = new CountDownLatch(1);
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenAnswer(invocation -> {
            final BlockPos current = position.get();
            if (Thread.currentThread() == moverThread.get()) {
                moverInPosition.countDown();
                await(releaseMover);
            }
            return current;
        });
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch secondRunStarted = new CountDownLatch(1);
        final CountDownLatch releaseSecondRun = new CountDownLatch(1);
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, () -> {
            if (runs.incrementAndGet() == 2) {
                secondRunStarted.countDown();
                await(releaseSecondRun);
            }
        }, 0L, 1L);

        this.drainEntityRegion(entity);
        assertEquals(1, runs.get());

        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = new Thread(() -> {
            try {
                this.engine.drainRegionTasks(level, origin);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "entity-reschedule-test");
        drainer.start();
        assertTrue(secondRunStarted.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> moveFailure = new AtomicReference<>();
        final Thread mover = new Thread(() -> {
            position.set(migrated);
            try {
                this.engine.moveRegionEntity(entity);
            } catch (Throwable throwable) {
                moveFailure.set(throwable);
            }
        }, "entity-move-test");
        moverThread.set(mover);
        mover.start();
        assertTrue(moverInPosition.await(5, TimeUnit.SECONDS), "the migration must hold the admission lock");

        // Let the periodic run reschedule. It must perform the engine admission outside the task monitor,
        // otherwise it blocks on the admission lock while the migration blocks on the task monitor.
        releaseSecondRun.countDown();
        awaitThreadBlockedOrWaiting(drainer);
        releaseMover.countDown();

        drainer.join(5_000L);
        mover.join(5_000L);
        assertFalse(drainer.isAlive(), "the periodic reschedule deadlocked against the migration");
        assertFalse(mover.isAlive(), "the migration deadlocked against the periodic reschedule");
        assertNull(drainFailure.get(), () -> "the region drain failed: " + drainFailure.get());
        assertNull(moveFailure.get(), () -> "the entity move failed: " + moveFailure.get());
        assertEquals(2, runs.get());
        assertFalse(task.isDone());

        this.drainRegion(level, origin);
        assertEquals(2, runs.get(), "the rescheduled child must not stay on the old region");
        this.drainRegion(level, migrated);
        this.drainRegion(level, migrated);
        assertEquals(3, runs.get(), "the periodic task must continue on the new region");

        final ScheduledRegionTask child = childTask(task);
        assertNotNull(child);
        assertFalse(child.isCancelled());
        assertTrue(task.cancel());
        assertTrue(task.isDone());
        assertTrue(child.isCancelled(), "cancelling the wrapper must cancel the published child");
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
    }

    @Test
    void levelClearRejectsDrainActivationPausedBeforeWorkerSubmit() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch atActivation = new CountDownLatch(1);
        final CountDownLatch releaseActivation = new CountDownLatch(1);
        this.engine.setBeforeRegionDrainActivationForTesting(() -> {
            atActivation.countDown();
            await(releaseActivation);
        });
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, runs::incrementAndGet);
        final Object region = regionState(this.engine, RegionCoordinate.fromBlock(level, BlockPos.ZERO));

        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = new Thread(() -> {
            try {
                this.engine.drainLevelRegionTasks(level);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "level-drain-clear-race-test");
        drainer.start();
        assertTrue(atActivation.await(5, TimeUnit.SECONDS));

        this.engine.clearLevelRegionTasks(level);
        this.engine.setBeforeRegionDrainActivationForTesting(null);
        assertEquals(0, this.engine.diagnostics().regionCount());
        releaseActivation.countDown();

        drainer.join(5_000L);
        assertFalse(drainer.isAlive(), "the drain never returned after the level clear");
        assertNull(drainFailure.get(), () -> "the drain failed after the level clear: " + drainFailure.get());
        assertEquals(0, runs.get(), "a drain must not activate a region that lifecycle cleanup already cleared");
        assertEquals(0L, regionTickCount(region), "a rejected activation must not submit a region tick");
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void shutdownRejectsDrainActivationPausedBeforeWorkerSubmit() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch atActivation = new CountDownLatch(1);
        final CountDownLatch releaseActivation = new CountDownLatch(1);
        this.engine.setBeforeRegionDrainActivationForTesting(() -> {
            atActivation.countDown();
            await(releaseActivation);
        });
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<String> runThread = new AtomicReference<>();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            runs.incrementAndGet();
            runThread.set(Thread.currentThread().getName());
        });
        final Object region = regionState(this.engine, RegionCoordinate.fromBlock(level, BlockPos.ZERO));

        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = new Thread(() -> {
            try {
                this.engine.drainLevelRegionTasks(level);
            } catch (Throwable throwable) {
                drainFailure.set(throwable);
            }
        }, "level-drain-shutdown-race-test");
        drainer.start();
        assertTrue(atActivation.await(5, TimeUnit.SECONDS));

        this.engine.shutdown();
        this.engine.setBeforeRegionDrainActivationForTesting(null);
        releaseActivation.countDown();

        drainer.join(5_000L);
        assertFalse(drainer.isAlive(), "the drain never returned after shutdown");
        assertNull(drainFailure.get(), () -> "the drain failed after shutdown: " + drainFailure.get());
        assertEquals(1, runs.get(), "shutdown must drain accepted work exactly once on its own thread");
        assertEquals(Thread.currentThread().getName(), runThread.get(), "the late drain activation must not run work after shutdown");
        assertEquals(0L, regionTickCount(region), "a rejected activation must not submit a region tick");
    }

    @Test
    void levelClearWaitsForDrainActivationThatWonTheLifecycleGate() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            runs.incrementAndGet();
            started.countDown();
            await(release);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread clearer = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            }
        }, "level-clear-wait-test");
        clearer.start();
        awaitThreadBlockedOrWaiting(clearer);
        assertTrue(clearer.isAlive(), "the level clear must wait for the drain activation that already won");
        assertEquals(1, runs.get());

        release.countDown();
        this.joinDrain(drainer);
        clearer.join(5_000L);
        assertFalse(clearer.isAlive(), "the level clear never returned");
        assertNull(clearFailure.get(), () -> "the level clear failed: " + clearFailure.get());
        assertEquals(1, runs.get());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void shutdownWaitsForDrainActivationThatWonTheLifecycleGate() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, true, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {
            runs.incrementAndGet();
            started.countDown();
            await(release);
        });
        final Thread drainer = this.drainInThread(level);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdowner = new Thread(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "engine-shutdown-wait-test");
        shutdowner.start();
        awaitThreadBlockedOrWaiting(shutdowner);
        assertTrue(shutdowner.isAlive(), "shutdown must wait for the drain activation that already won");

        release.countDown();
        this.joinDrain(drainer);
        shutdowner.join(5_000L);
        assertFalse(shutdowner.isAlive(), "shutdown never returned");
        assertNull(shutdownFailure.get(), () -> "shutdown failed: " + shutdownFailure.get());
        assertEquals(1, runs.get());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void concurrentRegionAdmissionDoesNotCreateRegionsAfterShutdown() throws InterruptedException {
        this.engine = new RegionThreadingEngine(2, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int threadIndex = 0; threadIndex < 4; ++threadIndex) {
            final int base = threadIndex * 1_000_000;
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int offset = 0;
                while (stop.getCount() > 0L) {
                    try {
                        this.engine.regionScheduler().execute(level, new BlockPos(base + offset, 64, 0), () -> {});
                        offset += 16;
                    } catch (IllegalStateException expected) {
                        return;
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                        return;
                    }
                }
            }, "region-admission-" + threadIndex);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        Thread.sleep(200L);
        this.engine.shutdown();
        stop.countDown();
        for (final Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "an admission thread never stopped");
        }

        assertTrue(unexpected.isEmpty(), () -> "unexpected admission failure: " + unexpected);
        assertEquals(0, this.engine.diagnostics().regionCount(), "no region may be created after shutdown cleared the regionizer");
    }

    @Test
    void concurrentGlobalAdmissionCancelsAcceptedTasksAndRejectsAfterShutdown() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, false);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        final List<ScheduledRegionTask> accepted = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int threadIndex = 0; threadIndex < 4; ++threadIndex) {
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (stop.getCount() > 0L) {
                    try {
                        accepted.add(this.engine.globalScheduler().runDelayed(() -> {}, 100L));
                    } catch (IllegalStateException expected) {
                        return;
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                        return;
                    }
                }
            }, "global-admission-" + threadIndex);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        Thread.sleep(200L);
        this.engine.shutdown();
        stop.countDown();
        for (final Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "a global admission thread never stopped");
        }

        assertTrue(unexpected.isEmpty(), () -> "unexpected global admission failure: " + unexpected);
        for (final ScheduledRegionTask task : accepted) {
            assertTrue(task.isDone(), "an accepted global task must be cancelled by shutdown");
        }
        assertThrows(IllegalStateException.class, () -> this.engine.globalScheduler().execute(() -> {}));
    }

    @Test
    void concurrentEntityAdmissionCancelsAcceptedTasksAndRejectsAfterShutdown() throws InterruptedException {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        final List<ScheduledRegionTask> accepted = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int threadIndex = 0; threadIndex < 4; ++threadIndex) {
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (stop.getCount() > 0L) {
                    try {
                        accepted.add(this.engine.entityScheduler().runDelayed(entity, () -> {}, 100L));
                    } catch (IllegalStateException expected) {
                        return;
                    } catch (Throwable throwable) {
                        unexpected.add(throwable);
                        return;
                    }
                }
            }, "entity-admission-" + threadIndex);
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        Thread.sleep(200L);
        this.engine.shutdown();
        stop.countDown();
        for (final Thread thread : threads) {
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "an entity admission thread never stopped");
        }

        assertTrue(unexpected.isEmpty(), () -> "unexpected entity admission failure: " + unexpected);
        assertEquals(0, this.engine.diagnostics().trackedEntityTaskCount());
        for (final ScheduledRegionTask task : accepted) {
            assertTrue(task.isDone(), "an accepted entity task must be cancelled by shutdown");
        }
        assertThrows(IllegalStateException.class, () -> this.engine.entityScheduler().runDelayed(entity, () -> {}, 0L));
    }

    private Runnable workerTask(ServerLevel level, BlockPos pos, CountDownLatch started, CountDownLatch release, CountDownLatch finished, Set<String> workerThreads, AtomicInteger ownedTasks) {
        return () -> {
            assertEquals(RegionThreadingContext.Kind.REGION, this.engine.currentContext().kind());
            if (this.engine.isOwnedByCurrentRegion(level, pos)) {
                ownedTasks.incrementAndGet();
            }
            workerThreads.add(Thread.currentThread().getName());
            started.countDown();
            await(release);
            finished.countDown();
        };
    }

    private Thread drainInThread(ServerLevel level) {
        this.drainFailure.set(null);
        return this.drainInThread(level, this.drainFailure);
    }

    private Thread drainInThread(ServerLevel level, AtomicReference<Throwable> failure) {
        final Thread drainer = new Thread(() -> {
            try {
                this.engine.drainLevelRegionTasks(level);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "level-drain-test");
        drainer.start();
        return drainer;
    }

    private void joinDrain(Thread drainer) throws InterruptedException {
        this.joinDrain(drainer, this.drainFailure);
    }

    private void joinDrain(Thread drainer, AtomicReference<Throwable> failure) throws InterruptedException {
        drainer.join(5_000L);
        assertFalse(drainer.isAlive(), "the level drain never returned");
        assertNull(failure.get(), () -> "the level drain failed: " + failure.get());
    }

    private void awaitGlobalIdle() {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.engine.diagnostics().globalRegionSubmitted()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("the global region worker did not finish");
            }
            Thread.onSpinWait();
        }
    }

    private static long regionQueueTick(RegionThreadingEngine engine, RegionCoordinate coordinate) {
        try {
            final Object region = regionState(engine, coordinate);
            final Method taskQueue = region.getClass().getDeclaredMethod("taskQueue");
            taskQueue.setAccessible(true);
            final Object queue = taskQueue.invoke(region);
            final Method currentTick = queue.getClass().getDeclaredMethod("currentTick");
            currentTick.setAccessible(true);
            return (long) currentTick.invoke(queue);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object regionState(RegionThreadingEngine engine, RegionCoordinate coordinate) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Method get = regionizer.getClass().getDeclaredMethod("get", RegionCoordinate.class);
            get.setAccessible(true);
            final Object region = get.invoke(regionizer, coordinate);
            assertNotNull(region, "the region was retired before it could be inspected");
            return region;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static long regionTickCount(Object region) {
        try {
            final Method tickMetrics = region.getClass().getDeclaredMethod("tickMetrics");
            tickMetrics.setAccessible(true);
            final Object metrics = tickMetrics.invoke(region);
            final Method tickCount = metrics.getClass().getDeclaredMethod("tickCount");
            tickCount.setAccessible(true);
            return (long) tickCount.invoke(metrics);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private void drainEntityRegion(Entity entity) {
        this.engine.drainRegionTasks((ServerLevel) entity.level(), entity.blockPosition());
    }

    private void drainRegion(ServerLevel level, BlockPos pos) {
        this.engine.makeRegionDueForTesting(level, pos);
        this.engine.drainRegionTasks(level, pos);
    }

    private void awaitRegionIdle() {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (this.engine.diagnostics().runningRegionCount() > 0 || this.engine.diagnostics().submittedRegionCount() > 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("the region worker tick did not finish");
            }
            Thread.onSpinWait();
        }
    }

    private static void closeRegionQueue(RegionThreadingEngine engine, ServerLevel level, int regionX, int regionZ) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Method get = regionizer.getClass().getDeclaredMethod("get", RegionCoordinate.class);
            get.setAccessible(true);
            final Object region = get.invoke(regionizer, new RegionCoordinate(level, regionX, regionZ));
            assertNotNull(region);
            final Method taskQueue = region.getClass().getDeclaredMethod("taskQueue");
            taskQueue.setAccessible(true);
            final Object queue = taskQueue.invoke(region);
            assertNotNull(queue);
            final Method clearAndCancel = queue.getClass().getDeclaredMethod("clearAndCancel");
            clearAndCancel.setAccessible(true);
            clearAndCancel.invoke(queue);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void closeWorkerPool(RegionThreadingEngine engine) {
        try {
            final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
            field.setAccessible(true);
            final Object pool = field.get(engine);
            final Method close = pool.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(pool);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ScheduledRegionTask childTask(ScheduledRegionTask task) {
        try {
            final Field field = task.getClass().getDeclaredField("scheduledTask");
            field.setAccessible(true);
            return (ScheduledRegionTask) field.get(task);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        return entity;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    /**
     * Waits until {@code thread} blocks on a monitor or waits for a lifecycle latch.
     * <p>
     * Used to prove that the thread reached the contended point deterministically instead of sleeping.
     */
    private static void awaitThreadBlockedOrWaiting(Thread thread) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING) {
            if (!thread.isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("thread " + thread.getName() + " did not block or wait; state: " + thread.getState());
            }
            Thread.onSpinWait();
        }
    }
}
