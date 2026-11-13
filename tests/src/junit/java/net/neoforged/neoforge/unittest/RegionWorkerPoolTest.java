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
        // Only the tasks an idle worker took measure a wake-up; the ones that waited behind other work - or were
        // stolen from another worker's deque - waited for that work instead, and every one of them still ran
        // exactly once.
        final long idleWakeUps = snapshotLong(pool, "idleWakeUps");
        assertTrue(idleWakeUps > 0L, "the first groups of the batch must be handed to idle workers");
        assertTrue(idleWakeUps <= groupCount);
        // Every task start is one spin or park wake-up, whether the worker took the task from its own deque or
        // stole it, so the two counters add up to the tasks the barrier saw.
        assertEquals(groupCount, snapshotLong(pool, "spinWakeUps") + snapshotLong(pool, "parkWakeUps"));
    }

    @Test
    void aStealingWorkerTakesWorkFromABusyOne() throws Exception {
        final Object pool = this.persistentPool(2);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);
        final CountDownLatch quickRan = new CountDownLatch(1);
        // Both workers are held inside a task, so the next submission finds no idle worker and is queued behind
        // the one the rotating scan starts at. Releasing the other worker then leaves it with no own work and a
        // task waiting in a neighbour's deque: it has to steal it, and the steal counter says so without timing.
        assertTrue(submit(pool, "first", () -> {
            firstStarted.countDown();
            awaitUnchecked(releaseFirst);
        }));
        awaitUnchecked(firstStarted);
        assertTrue(submit(pool, "second", () -> {
            secondStarted.countDown();
            awaitUnchecked(releaseSecond);
        }));
        awaitUnchecked(secondStarted);
        assertTrue(submit(pool, "quick", quickRan::countDown));

        releaseSecond.countDown();
        assertTrue(quickRan.await(10, TimeUnit.SECONDS), "the queued task never ran while both workers were held");
        assertTrue(snapshotLong(pool, "steals") > 0L, "the released worker never stole the task queued on the other one");
        releaseFirst.countDown();
    }

    @Test
    void packedGroupsStolenAcrossWorkersRunEveryGroupExactlyOnce() throws Exception {
        final Object pool = this.persistentPool(4);
        // Eight packs of four groups per batch, over twenty batches with random group durations, so the packs of
        // one batch are stolen across the workers while the next batch is submitted. A group is the unit of
        // region exclusivity: a worker may only be inside a group while no other worker is inside the same one.
        final int batches = 20;
        final int packCount = 8;
        final int groupsPerPack = 4;
        final int groupCount = packCount * groupsPerPack;
        final int[] runs = new int[groupCount];
        final AtomicBoolean overlapped = new AtomicBoolean();
        for (int batchIndex = 0; batchIndex < batches; ++batchIndex) {
            final RegionTaskBatch batch = new RegionTaskBatch();
            final AtomicInteger[] insideGroups = new AtomicInteger[groupCount];
            for (int pack = 0; pack < packCount; ++pack) {
                final List<Runnable> works = new ArrayList<>();
                for (int index = 0; index < groupsPerPack; ++index) {
                    final int group = pack * groupsPerPack + index;
                    insideGroups[group] = new AtomicInteger();
                    works.add(() -> {
                        if (insideGroups[group].incrementAndGet() != 1) {
                            overlapped.set(true);
                        }
                        sleep(ThreadLocalRandom.current().nextInt(3));
                        ++runs[group];
                        insideGroups[group].decrementAndGet();
                    });
                }
                batch.addPacked("pack " + pack, works);
            }

            final RegionTaskBatch.Barrier barrier = batch.submit(executor(pool));
            barrier.awaitCompletion();
            assertTrue(barrier.isComplete());
            assertNull(barrier.failure());
        }

        assertFalse(overlapped.get(), "two workers were inside one group of a packed batch at the same time");
        for (int index = 0; index < runs.length; ++index) {
            assertEquals(batches, runs[index], "group " + index + " ran " + runs[index] + " times of " + batches);
        }
    }

    @Test
    void aThrowingGroupFailsTheBatchAndTheOtherGroupsStillFinish() throws Exception {
        final Object pool = this.persistentPool(2);
        final RegionTaskBatch batch = new RegionTaskBatch();
        final CountDownLatch survivors = new CountDownLatch(15);
        for (int index = 0; index < 15; ++index) {
            batch.add("survivor " + index, survivors::countDown);
        }
        batch.add("failing", () -> {
            throw new IllegalStateException("boom");
        });

        final RegionTaskBatch.Barrier barrier = batch.submit(executor(pool));
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete(), "the barrier did not complete after a failing group");
        assertTrue(survivors.await(1, TimeUnit.SECONDS), "the groups behind the failing one never ran");
        assertTrue(barrier.failure() instanceof IllegalStateException, String.valueOf(barrier.failure()));
        assertEquals(0, barrier.rejectedCount());
    }

    @Test
    void closeDrainsTheQueuedWorkOfEveryWorker() throws Exception {
        final Object pool = this.persistentPool(3);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(submit(pool, "blocker", () -> {
            started.countDown();
            awaitUnchecked(release);
        }));
        awaitUnchecked(started);
        final int queued = 300;
        final int[] runs = new int[queued];
        final CountDownLatch done = new CountDownLatch(queued);
        for (int index = 0; index < queued; ++index) {
            final int task = index;
            assertTrue(submit(pool, "queued " + task, () -> {
                ++runs[task];
                done.countDown();
            }));
        }

        final AtomicReference<Throwable> closerFailure = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final Thread closer = new Thread(() -> {
            try {
                close.invoke(pool);
            } catch (Throwable throwable) {
                closerFailure.set(throwable);
            } finally {
                closed.countDown();
            }
        }, "pool-close-test");
        closer.start();
        // The queued tasks cannot run before the blocker releases the worker they are queued behind.
        sleep(50);
        release.countDown();

        assertTrue(closed.await(30, TimeUnit.SECONDS), "close never returned");
        assertNull(closerFailure.get(), () -> "close failed: " + closerFailure.get());
        assertTrue(done.await(1, TimeUnit.SECONDS), "close left queued work unexecuted");
        for (int index = 0; index < queued; ++index) {
            assertEquals(1, runs[index], "queued task " + index + " ran " + runs[index] + " times");
        }
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
        final Set<Thread> workers = ConcurrentHashMap.newKeySet();
        // One blocking task per worker: a blocked worker cannot steal the task handed to its neighbour, so the
        // three threads are all occupied before they are released and the close has to retire every one of them.
        final CountDownLatch started = new CountDownLatch(3);
        final CountDownLatch release = new CountDownLatch(1);
        for (int index = 0; index < 3; ++index) {
            assertTrue(submit(pool, "report " + index, () -> {
                workers.add(Thread.currentThread());
                started.countDown();
                awaitUnchecked(release);
            }));
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the three workers did not all start a task");
        release.countDown();

        unchecked(() -> close.invoke(pool));

        assertEquals(3, workers.size(), "the three tasks did not run on three distinct workers");
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
