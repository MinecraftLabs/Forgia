/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behaviour of the region worker pool: the persistent dispatch path and the executor path it falls back to.
 * <p>
 * The region test module cannot declare classes in the package of the pool, so the pool is driven through
 * reflection. The tests assert the contracts the tick batches rely on - a task is run exactly once, a batch
 * submitted while the pool is stopping still runs every group on the submitting thread, and a task that throws
 * neither loses the work behind it nor retires its worker - plus the shutdown rule that a worker is never left
 * parked.
 */
@Timeout(60)
class RegionWorkerPoolTest {
    private static Constructor<?> poolConstructor;
    private static Method execute;
    private static Method close;
    private static Method isPersistent;
    private static Method isWorkerThread;
    private static Method dispatchSnapshot;
    private static Class<?> dispatchSnapshotClass;

    private final List<Object> pools = new ArrayList<>();

    @BeforeAll
    static void locateInternals() throws ReflectiveOperationException {
        final Class<?> poolClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        poolConstructor = accessible(poolClass.getDeclaredConstructor(int.class, boolean.class));
        execute = accessible(poolClass.getDeclaredMethod("execute", String.class, Runnable.class));
        close = accessible(poolClass.getDeclaredMethod("close"));
        isPersistent = accessible(poolClass.getDeclaredMethod("isPersistent"));
        isWorkerThread = accessible(poolClass.getDeclaredMethod("isWorkerThread", Thread.class));
        dispatchSnapshot = accessible(poolClass.getDeclaredMethod("dispatchSnapshot"));
        dispatchSnapshotClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool$DispatchSnapshot");
    }

    @AfterEach
    void closePools() {
        for (final Object pool : this.pools) {
            unchecked(() -> close.invoke(pool));
        }
        this.pools.clear();
    }

    @Test
    void persistentPoolRunsEveryTaskExactlyOnceAndTheBarrierWaitsForAll() throws Exception {
        final Object pool = this.persistentPool(4);
        final int groupCount = 64;
        final int[] runs = new int[groupCount];
        final RegionTaskBatch batch = new RegionTaskBatch();
        for (int index = 0; index < groupCount; ++index) {
            final int group = index;
            batch.add("group " + group, () -> {
                sleep(ThreadLocalRandom.current().nextInt(3));
                ++runs[group];
            });
        }

        final RegionTaskBatch.Barrier barrier = batch.submit(executor(pool));
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertNull(barrier.failure());
        assertEquals(0, barrier.rejectedCount());
        for (int index = 0; index < groupCount; ++index) {
            assertEquals(1, runs[index], "group " + index + " ran " + runs[index] + " times");
        }
        assertEquals(groupCount, snapshotLong(pool, "submissions"));
        assertTrue(snapshotLong(pool, "submitNanos") > 0L);
        assertTrue(snapshotLong(pool, "wakeNanos") >= 0L);
        // Only the tasks an idle worker took measure a wake-up; the ones that went to the shared overflow queue
        // waited behind other work, and every one of them still ran exactly once.
        final long idleWakeUps = snapshotLong(pool, "idleWakeUps");
        assertTrue(idleWakeUps > 0L, "the first groups of the batch must be handed to idle workers");
        assertTrue(idleWakeUps <= groupCount);
        assertEquals(idleWakeUps, snapshotLong(pool, "spinWakeUps") + snapshotLong(pool, "parkWakeUps"));
    }

    @Test
    void persistentPoolNeverLosesOrRepeatsATask() throws Exception {
        final Object pool = this.persistentPool(4);
        final int taskCount = 2000;
        final int[] runs = new int[taskCount];
        final CountDownLatch done = new CountDownLatch(taskCount);
        for (int index = 0; index < taskCount; ++index) {
            final int task = index;
            assertTrue(submit(pool, "task " + task, () -> {
                ++runs[task];
                done.countDown();
            }));
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "the pool lost a task");
        for (int index = 0; index < taskCount; ++index) {
            assertEquals(1, runs[index], "task " + index + " ran " + runs[index] + " times");
        }
    }

    @Test
    void aFailingTaskDoesNotRetireItsWorker() throws Exception {
        final Object pool = this.persistentPool(1);
        final CountDownLatch survived = new CountDownLatch(1);
        final AtomicReference<Thread> failingThread = new AtomicReference<>();

        assertTrue(submit(pool, "failing", () -> {
            failingThread.set(Thread.currentThread());
            throw new IllegalStateException("boom");
        }));
        assertTrue(submit(pool, "after the failure", survived::countDown));

        assertTrue(survived.await(10, TimeUnit.SECONDS), "the worker did not survive a failing task");
        assertTrue((Boolean) unchecked(() -> isWorkerThread.invoke(pool, failingThread.get())));
        assertEquals(2L, snapshotLong(pool, "submissions"));
    }

    @Test
    void tasksRunOnRecognizedWorkerThreads() throws Exception {
        final Object pool = this.persistentPool(2);
        final Set<String> names = ConcurrentHashMap.newKeySet();
        final AtomicBoolean allRecognized = new AtomicBoolean(true);
        final CountDownLatch done = new CountDownLatch(4);
        for (int index = 0; index < 4; ++index) {
            assertTrue(submit(pool, "task " + index, () -> {
                names.add(Thread.currentThread().getName());
                allRecognized.compareAndSet(true, (Boolean) unchecked(() -> isWorkerThread.invoke(pool, Thread.currentThread()))
                        && RegionWorkerScope.isNativeWorkerThread());
                done.countDown();
            }));
        }

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(allRecognized.get(), "a task did not run on a recognized native worker thread");
        assertTrue(names.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), names.toString());
    }

    @Test
    void closeWaitsForAcceptedWorkAndRefusesNewWork() throws Exception {
        final Object pool = this.persistentPool(2);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        assertTrue(submit(pool, "blocking", () -> {
            started.countDown();
            awaitUnchecked(release);
            runs.incrementAndGet();
        }));
        assertTrue(started.await(10, TimeUnit.SECONDS));

        final AtomicBoolean closeReturned = new AtomicBoolean();
        final Thread closer = new Thread(() -> {
            unchecked(() -> close.invoke(pool));
            closeReturned.set(true);
        }, "test-pool-closer");
        closer.start();
        sleep(50);
        assertFalse(closeReturned.get(), "close returned while an accepted task was still running");

        release.countDown();
        closer.join(10_000L);
        assertFalse(closer.isAlive(), "close did not return after the accepted task finished");
        assertEquals(1, runs.get());
        assertFalse(submit(pool, "refused", runs::incrementAndGet));
        assertEquals(1, runs.get());
    }

    @Test
    void aBatchSubmittedWhileStoppingRunsItsGroupsOnTheSubmittingThread() throws Exception {
        final Object pool = this.persistentPool(2);
        unchecked(() -> close.invoke(pool));
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("first", () -> threads.add(Thread.currentThread().getName()));
        batch.add("second", () -> threads.add(Thread.currentThread().getName()));

        final RegionTaskBatch.Barrier barrier = batch.submit(executor(pool));
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertEquals(2, barrier.rejectedCount());
        assertNull(barrier.failure());
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void closeLeavesNoWorkerParked() throws Exception {
        final Object pool = this.persistentPool(3);
        final Set<Thread> workers = new HashSet<>();
        for (int index = 0; index < 3; ++index) {
            final CountDownLatch reported = new CountDownLatch(1);
            final AtomicReference<Thread> thread = new AtomicReference<>();
            assertTrue(submit(pool, "report", () -> {
                thread.set(Thread.currentThread());
                reported.countDown();
            }));
            awaitUnchecked(reported);
            workers.add(thread.get());
        }

        unchecked(() -> close.invoke(pool));

        assertEquals(3, workers.size());
        for (final Thread worker : workers) {
            assertFalse(worker.isAlive(), "worker " + worker.getName() + " is still alive after close");
        }
    }

    @Test
    void closeFromAWorkerThreadIsRefused() throws Exception {
        final Object pool = this.persistentPool(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue(submit(pool, "close from the worker", () -> {
            try {
                close.invoke(pool);
            } catch (Throwable throwable) {
                failure.set(throwable instanceof java.lang.reflect.InvocationTargetException target ? target.getCause() : throwable);
            }
            done.countDown();
        }));

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof IllegalStateException, String.valueOf(failure.get()));
    }

    @Test
    void closeWaitsForARunningWorkerTaskOnTheExecutorPath() throws Exception {
        final Object pool = newPool(1, false);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        assertTrue(submit(pool, "blocking", () -> {
            started.countDown();
            awaitUnchecked(release);
            finished.set(true);
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                close.invoke(pool);
                closed.set(true);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "pool-close-test");
        closer.start();

        Thread.sleep(200L);
        assertTrue(closer.isAlive(), "close must not return while a worker task is still running");
        assertFalse(closed.get());

        release.countDown();
        closer.join(10_000L);

        assertFalse(closer.isAlive(), "close never returned after the worker finished");
        assertNull(failure.get(), () -> "close failed: " + failure.get());
        assertTrue(finished.get(), "the worker task did not finish before close returned");
        assertTrue(closed.get());
    }

    @Test
    void interruptingTheCloserStillWaitsForANonCooperativeWorker() throws Exception {
        final Object pool = newPool(1, false);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean released = new AtomicBoolean();
        assertTrue(submit(pool, "non-cooperative", () -> {
            started.countDown();
            while (!released.get()) {
                sleep(10L);
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                close.invoke(pool);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "pool-close-test");
        closer.start();

        Thread.sleep(200L);
        closer.interrupt();
        Thread.sleep(200L);
        assertTrue(closer.isAlive(), "an interrupted close must keep waiting for the worker");

        released.set(true);
        closer.join(10_000L);

        assertFalse(closer.isAlive(), "close never returned after the worker finished");
        assertNull(failure.get(), () -> "close failed: " + failure.get());
    }

    @Test
    void executorFallbackKeepsTheUnchangedBehaviour() throws Exception {
        final Object pool = newPool(2, false);
        assertFalse((Boolean) unchecked(() -> isPersistent.invoke(pool)));
        final CountDownLatch done = new CountDownLatch(8);
        final Set<String> names = ConcurrentHashMap.newKeySet();
        for (int index = 0; index < 8; ++index) {
            assertTrue(submit(pool, "task " + index, () -> {
                names.add(Thread.currentThread().getName());
                done.countDown();
            }));
        }

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(names.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), names.toString());
        assertEquals(8L, snapshotLong(pool, "submissions"));
    }

    private Object persistentPool(int threads) throws Exception {
        final Object pool = newPool(threads, true);
        assertTrue((Boolean) unchecked(() -> isPersistent.invoke(pool)));
        return pool;
    }

    private Object newPool(int threads, boolean persistent) throws Exception {
        final Object pool = poolConstructor.newInstance(threads, persistent);
        this.pools.add(pool);
        return pool;
    }

    private static RegionWorkerExecutor executor(Object pool) {
        return (description, task) -> {
            try {
                return (Boolean) execute.invoke(pool, description, task);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private static boolean submit(Object pool, String description, Runnable task) {
        return (Boolean) unchecked(() -> execute.invoke(pool, description, task));
    }

    private static long snapshotLong(Object pool, String accessor) throws Exception {
        final Object snapshot = dispatchSnapshot.invoke(pool);
        return (Long) accessible(dispatchSnapshotClass.getDeclaredMethod(accessor)).invoke(snapshot);
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T object) {
        object.setAccessible(true);
        return object;
    }

    private static Object unchecked(ReflectiveCall call) {
        try {
            return call.call();
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object call() throws ReflectiveOperationException;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            // The workload of the non-cooperative close test deliberately ignores an interrupt.
            Thread.interrupted();
        }
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the region worker pool");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
