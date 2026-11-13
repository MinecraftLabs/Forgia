/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the package-private region task queue. The queue lives in a mod module, so the test
 * module cannot declare classes in the same package; reflection keeps the test independent of
 * the public scheduling API.
 */
@Timeout(10)
class RegionTaskQueueTest {
    private static Constructor<?> queueHookConstructor;
    private static Constructor<?> taskConstructor;
    private static Method enqueue;
    private static Method schedule;
    private static Method scheduleAtFixedRate;
    private static Method drain;
    private static Method cappedDrainCount;
    private static Method currentTick;
    private static Method isEmpty;
    private static Method clearAndCancel;
    private static Method markRunning;
    private static Method isDue;
    private static Method runAndReschedule;

    @BeforeAll
    static void locateInternals() throws ReflectiveOperationException {
        final Class<?> queueClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionTaskQueue");
        final Class<?> taskClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionScheduledTask");
        queueHookConstructor = accessible(queueClass.getDeclaredConstructor(Runnable.class, Runnable.class));
        taskConstructor = accessible(taskClass.getDeclaredConstructor(Runnable.class, long.class, long.class));
        enqueue = accessible(queueClass.getDeclaredMethod("enqueue", Runnable.class));
        schedule = accessible(queueClass.getDeclaredMethod("schedule", Runnable.class, long.class));
        scheduleAtFixedRate = accessible(queueClass.getDeclaredMethod("scheduleAtFixedRate", Runnable.class, long.class, long.class));
        drain = accessible(queueClass.getDeclaredMethod("drain", int.class, int.class));
        cappedDrainCount = accessible(queueClass.getDeclaredMethod("cappedDrainCount"));
        currentTick = accessible(queueClass.getDeclaredMethod("currentTick"));
        isEmpty = accessible(queueClass.getDeclaredMethod("isEmpty"));
        clearAndCancel = accessible(queueClass.getDeclaredMethod("clearAndCancel"));
        markRunning = accessible(taskClass.getDeclaredMethod("markRunning"));
        isDue = accessible(taskClass.getDeclaredMethod("isDue", long.class));
        runAndReschedule = accessible(taskClass.getDeclaredMethod("runAndReschedule", long.class));
    }

    @Test
    void delayedTaskRunsOnlyAtItsDeadline() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = schedule(queue, runs::incrementAndGet, 2L);

        assertEquals(0, drain(queue));
        assertEquals(0, drain(queue));
        assertEquals(0, runs.get());
        assertFalse(task.isDone());

        assertEquals(0, drain(queue));
        assertEquals(1, runs.get());
        assertTrue(task.isDone());

        drain(queue);
        assertEquals(1, runs.get());
    }

    @Test
    void fixedRateTaskUsesItsOriginalScheduleAfterLateDrains() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        scheduleAtFixedRate(queue, runs::incrementAndGet, 1L, 2L);

        drain(queue);
        assertEquals(0, runs.get());
        drain(queue);
        assertEquals(1, runs.get());
        drain(queue);
        assertEquals(1, runs.get());
        drain(queue);
        assertEquals(2, runs.get());
    }

    @Test
    void cancellationPreventsExecutionAndIsIdempotent() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = schedule(queue, runs::incrementAndGet, 0L);

        assertTrue(task.cancel());
        assertFalse(task.cancel());
        assertTrue(task.isCancelled());
        assertTrue(task.isDone());
        drain(queue);
        assertEquals(0, runs.get());
        assertTrue(isEmpty(queue));
    }

    @Test
    void cancellationDuringRunPreventsRescheduling() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<ScheduledRegionTask> task = new AtomicReference<>();
        task.set(scheduleAtFixedRate(queue, () -> {
            runs.incrementAndGet();
            assertTrue(task.get().cancel());
        }, 0L, 1L));

        drain(queue);
        drain(queue);
        assertEquals(1, runs.get());
        assertTrue(task.get().isCancelled());
        assertTrue(isEmpty(queue));
    }

    @Test
    void cancellationAfterPollingPreventsExecution() {
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = newTask(runs::incrementAndGet, 0L, 0L);

        assertTrue((boolean) invoke(markRunning, task));
        assertTrue(task.cancel());
        assertFalse((boolean) invoke(runAndReschedule, task, 0L));
        assertEquals(0, runs.get());
        assertTrue(task.isDone());
    }

    @Test
    void failureCompletesOneShotTaskWithoutStoppingOtherWork() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask failing = schedule(queue, () -> {
            throw new IllegalStateException("expected");
        }, 0L);
        enqueue(queue, runs::incrementAndGet);

        assertEquals(1, drain(queue));
        assertTrue(failing.isDone());
        assertFalse(failing.isCancelled());
        assertFalse(failing.cancel());
        assertEquals(1, runs.get());

        assertEquals(0, drain(queue));
        assertTrue(isEmpty(queue));
    }

    @Test
    void failureRetiresFixedRateTask() {
        final Object queue = newQueue();
        final AtomicInteger attempts = new AtomicInteger();
        final ScheduledRegionTask failing = scheduleAtFixedRate(queue, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("expected");
        }, 0L, 1L);

        assertEquals(1, drain(queue));
        assertEquals(0, drain(queue));
        assertEquals(1, attempts.get());
        assertTrue(failing.isDone());
        assertTrue(isEmpty(queue));
    }

    @Test
    void immediateTaskFailureIsCountedAndDoesNotStopTheDrain() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        enqueue(queue, () -> {
            throw new IllegalStateException("expected");
        });
        enqueue(queue, runs::incrementAndGet);

        assertEquals(1, drain(queue));
        assertEquals(1, runs.get());
    }

    @Test
    void invalidSchedulesAreRejected() {
        final Object queue = newQueue();
        assertThrows(IllegalArgumentException.class, () -> schedule(queue, () -> {}, -1L));
        assertThrows(IllegalArgumentException.class, () -> scheduleAtFixedRate(queue, () -> {}, -1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> scheduleAtFixedRate(queue, () -> {}, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> scheduleAtFixedRate(queue, () -> {}, 0L, -1L));
    }

    @Test
    void immediateDrainLimitCapsWorkAndCountsIt() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 3; ++i) {
            enqueue(queue, runs::incrementAndGet);
        }

        assertEquals(0, (int) invoke(drain, queue, 1, 0));
        assertEquals(1, runs.get());
        assertEquals(1L, (long) invoke(cappedDrainCount, queue));

        assertEquals(0, (int) invoke(drain, queue, 1, 0));
        assertEquals(2, runs.get());
        assertEquals(2L, (long) invoke(cappedDrainCount, queue));

        assertEquals(0, (int) invoke(drain, queue, 0, 0));
        assertEquals(3, runs.get());
        assertTrue(isEmpty(queue));
    }

    @Test
    void immediateWorkInsertedDuringADrainRunsOnTheNextDrain() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        enqueue(queue, () -> {
            runs.incrementAndGet();
            enqueue(queue, runs::incrementAndGet);
        });

        assertEquals(0, drain(queue));
        assertEquals(1, runs.get(), "work inserted during the drain belongs to the next one");
        assertEquals(0, drain(queue));
        assertEquals(2, runs.get());
        assertTrue(isEmpty(queue));
    }

    @Test
    void scheduledDrainLimitCapsWorkAndCountsIt() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        schedule(queue, runs::incrementAndGet, 0L);
        schedule(queue, runs::incrementAndGet, 0L);

        assertEquals(0, (int) invoke(drain, queue, 0, 1));
        assertEquals(1, runs.get());
        assertEquals(1L, (long) invoke(cappedDrainCount, queue));

        assertEquals(0, (int) invoke(drain, queue, 0, 1));
        assertEquals(2, runs.get());
        assertTrue(isEmpty(queue));
    }

    @Test
    void futureScheduledTaskKeepsQueueNonEmpty() {
        final Object queue = newQueue();
        schedule(queue, () -> {}, 5L);

        assertFalse(isEmpty(queue));
        drain(queue);
        assertFalse(isEmpty(queue));
    }

    @Test
    void clearAndCancelDuringRunSkipsTheRemainingSnapshot() throws InterruptedException {
        final Object queue = newQueue();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        enqueue(queue, () -> {
            runs.incrementAndGet();
            running.countDown();
            await(release);
        });
        enqueue(queue, runs::incrementAndGet);
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = drainer(queue, drainFailure);
        drainer.start();

        assertTrue(running.await(5, TimeUnit.SECONDS));
        invoke(clearAndCancel, queue);
        release.countDown();
        drainer.join(5_000L);

        assertFalse(drainer.isAlive());
        assertEquals(null, drainFailure.get());
        assertEquals(1, runs.get(), "work snapshotted before the close belongs to a retired region");
        assertTrue(isEmpty(queue));
    }

    @Test
    void clearAndCancelDuringRunPreventsRescheduling() throws InterruptedException {
        final Object queue = newQueue();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        scheduleAtFixedRate(queue, () -> {
            runs.incrementAndGet();
            running.countDown();
            await(release);
        }, 0L, 1L);
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = drainer(queue, drainFailure);
        drainer.start();

        assertTrue(running.await(5, TimeUnit.SECONDS));
        invoke(clearAndCancel, queue);
        release.countDown();
        drainer.join(5_000L);

        assertFalse(drainer.isAlive());
        assertEquals(null, drainFailure.get());
        assertEquals(1, runs.get());
        assertTrue(isEmpty(queue));
        assertEquals(0, drain(queue));
        assertEquals(1, runs.get());
    }

    @Test
    void closeAndScheduleAreMutuallyExclusive() throws InterruptedException {
        final CountDownLatch inserting = new CountDownLatch(1);
        final CountDownLatch clearStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Object queue = newQueue(null, () -> {
            inserting.countDown();
            await(release);
        });
        final AtomicReference<ScheduledRegionTask> scheduled = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread scheduler = new Thread(() -> {
            try {
                scheduled.set(schedule(queue, () -> {}, 0L));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        final Thread clearer = new Thread(() -> {
            clearStarted.countDown();
            try {
                invoke(clearAndCancel, queue);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        scheduler.start();

        assertTrue(inserting.await(5, TimeUnit.SECONDS));
        clearer.start();
        assertTrue(clearStarted.await(5, TimeUnit.SECONDS));
        awaitClearerBlockedOrDone(clearer);
        release.countDown();
        scheduler.join(5_000L);
        clearer.join(5_000L);

        assertFalse(scheduler.isAlive());
        assertFalse(clearer.isAlive());
        assertEquals(null, failure.get());
        assertNotNull(scheduled.get());
        assertTrue(scheduled.get().isCancelled());
        assertTrue(scheduled.get().isDone());
        assertTrue(isEmpty(queue));
    }

    @Test
    void closeAndEnqueueAreMutuallyExclusive() throws InterruptedException {
        final CountDownLatch inserting = new CountDownLatch(1);
        final CountDownLatch clearStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Object queue = newQueue(() -> {
            inserting.countDown();
            await(release);
        }, null);
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread enqueuer = new Thread(() -> {
            try {
                enqueue(queue, runs::incrementAndGet);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        final Thread clearer = new Thread(() -> {
            clearStarted.countDown();
            try {
                invoke(clearAndCancel, queue);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        enqueuer.start();

        assertTrue(inserting.await(5, TimeUnit.SECONDS));
        clearer.start();
        assertTrue(clearStarted.await(5, TimeUnit.SECONDS));
        awaitClearerBlockedOrDone(clearer);
        release.countDown();
        enqueuer.join(5_000L);
        clearer.join(5_000L);

        assertFalse(enqueuer.isAlive());
        assertFalse(clearer.isAlive());
        assertEquals(null, failure.get());
        assertTrue(isEmpty(queue));
        assertEquals(0, drain(queue));
        assertEquals(0, runs.get());
    }

    @Test
    void scheduleSaturatesDeadlineOverflow() {
        final Object queue = newQueue();
        drain(queue);

        final ScheduledRegionTask task = schedule(queue, () -> {}, Long.MAX_VALUE);
        assertFalse((boolean) invoke(isDue, task, Long.MAX_VALUE - 1L));
        assertTrue((boolean) invoke(isDue, task, Long.MAX_VALUE));
        assertFalse(task.isDone());
    }

    @Test
    void fixedRateDeadlineOverflowSaturates() {
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = newTask(runs::incrementAndGet, 0L, Long.MAX_VALUE);

        assertTrue((boolean) invoke(runAndReschedule, task, 1L));
        assertEquals(1, runs.get());
        assertFalse((boolean) invoke(isDue, task, Long.MAX_VALUE - 1L));
        assertTrue((boolean) invoke(isDue, task, Long.MAX_VALUE));
    }

    @Test
    void cancellationWinsAgainstBlockedDueTask() throws InterruptedException {
        final Object queue = newQueue();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = schedule(queue, () -> {
            runs.incrementAndGet();
            running.countDown();
            await(release);
        }, 0L);
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = drainer(queue, drainFailure);
        drainer.start();

        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(task.cancel());
        release.countDown();
        drainer.join(5_000L);

        assertFalse(drainer.isAlive());
        assertEquals(null, drainFailure.get());
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
        assertTrue(isEmpty(queue));
    }

    @Test
    void taskScheduledFromAnotherThreadObservesCurrentTick() {
        final Object queue = newQueue();
        final AtomicInteger runs = new AtomicInteger();
        drain(queue);
        drain(queue);
        schedule(queue, runs::incrementAndGet, 2L);

        drain(queue);
        drain(queue);
        assertEquals(0, runs.get());
        drain(queue);
        assertEquals(1, runs.get());
    }

    @Test
    void reentrantDrainDoesNotAdvanceTheLogicalTickOrRunNewWork() {
        final Object queue = newQueue();
        final AtomicInteger outerRuns = new AtomicInteger();
        final AtomicInteger innerRuns = new AtomicInteger();
        final AtomicInteger nestedResult = new AtomicInteger(-1);
        enqueue(queue, () -> {
            outerRuns.incrementAndGet();
            enqueue(queue, innerRuns::incrementAndGet);
            nestedResult.set(drain(queue));
            assertEquals(0, innerRuns.get(), "a nested drain must leave new work for the next tick");
        });

        assertEquals(0, drain(queue));
        assertEquals(1, outerRuns.get());
        assertEquals(0, innerRuns.get());
        assertEquals(0, nestedResult.get());
        assertEquals(1L, currentTick(queue), "a nested drain must not advance the queue tick twice");

        drain(queue);
        assertEquals(1, innerRuns.get());
        assertEquals(2L, currentTick(queue));
    }

    @Test
    void drainOnClosedQueueDoesNotAdvanceTheLogicalTick() {
        final Object queue = newQueue();
        drain(queue);
        assertEquals(1L, currentTick(queue));

        invoke(clearAndCancel, queue);
        assertEquals(1L, currentTick(queue), "closing the queue must not change the logical tick");

        assertEquals(0, drain(queue));
        assertEquals(1L, currentTick(queue), "a drain on a closed queue must not advance the logical tick");
    }

    @Test
    void clearDuringDrainDoesNotAdvanceTheLogicalTick() throws InterruptedException {
        final Object queue = newQueue();
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        enqueue(queue, () -> {
            running.countDown();
            await(release);
        });
        final AtomicReference<Throwable> drainFailure = new AtomicReference<>();
        final Thread drainer = drainer(queue, drainFailure);
        drainer.start();

        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertEquals(0L, currentTick(queue));
        invoke(clearAndCancel, queue);
        release.countDown();
        drainer.join(5_000L);

        assertFalse(drainer.isAlive());
        assertEquals(null, drainFailure.get());
        assertEquals(0L, currentTick(queue), "closure during the drain must suppress the logical tick advance");
    }

    @Test
    void concurrentDrainersRunEachImmediateTaskOnce() throws InterruptedException {
        final Object queue = newQueue();
        final Set<Integer> taskIds = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 1_000; ++i) {
            final int taskId = i;
            enqueue(queue, () -> assertTrue(taskIds.add(taskId)));
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread first = drainer(queue, failure);
        final Thread second = drainer(queue, failure);
        first.start();
        second.start();
        first.join(5_000L);
        second.join(5_000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertEquals(null, failure.get());
        assertEquals(1_000, taskIds.size());
    }

    private static Object newQueue() {
        return newQueue(null, null);
    }

    private static Object newQueue(Runnable beforeImmediateInsert, Runnable beforeScheduledInsert) {
        try {
            return queueHookConstructor.newInstance(beforeImmediateInsert, beforeScheduledInsert);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ScheduledRegionTask newTask(Runnable task, long deadlineTick, long periodTicks) {
        try {
            return (ScheduledRegionTask) taskConstructor.newInstance(task, deadlineTick, periodTicks);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void enqueue(Object queue, Runnable task) {
        invoke(enqueue, queue, task);
    }

    private static ScheduledRegionTask schedule(Object queue, Runnable task, long delayTicks) {
        return (ScheduledRegionTask) invoke(schedule, queue, task, delayTicks);
    }

    private static ScheduledRegionTask scheduleAtFixedRate(Object queue, Runnable task, long initialDelayTicks, long periodTicks) {
        return (ScheduledRegionTask) invoke(scheduleAtFixedRate, queue, task, initialDelayTicks, periodTicks);
    }

    private static int drain(Object queue) {
        return (int) invoke(drain, queue, 0, 0);
    }

    private static long currentTick(Object queue) {
        return (long) invoke(currentTick, queue);
    }

    private static boolean isEmpty(Object queue) {
        return (boolean) invoke(isEmpty, queue);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T object) {
        object.setAccessible(true);
        return object;
    }

    private static Thread drainer(Object queue, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                drain(queue);
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitClearerBlockedOrDone(Thread clearer) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (clearer.isAlive() && clearer.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("clearer did not complete or block on the insertion lock");
            }
            Thread.onSpinWait();
        }
    }
}
