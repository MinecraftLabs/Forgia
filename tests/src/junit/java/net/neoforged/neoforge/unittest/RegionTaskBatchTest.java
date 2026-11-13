/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RegionTaskBatchTest {
    private final ExecutorService workers = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            final Thread thread = new Thread(runnable, "test-region-worker-" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    @AfterEach
    void shutdownWorkers() {
        this.workers.shutdownNow();
    }

    @Test
    void barrierCompletesOnlyAfterEveryGroupFinished() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("group", () -> {
            started.countDown();
            awaitUnchecked(release);
        });

        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(barrier.isComplete());
        release.countDown();
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertNull(barrier.failure());
        assertEquals(0, barrier.rejectedCount());
    }

    @Test
    void groupsRunOnWorkerThreads() {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("first", () -> threads.add(Thread.currentThread().getName()));
        batch.add("second", () -> threads.add(Thread.currentThread().getName()));

        batch.submit(this.workerExecutor()).awaitCompletion();

        assertEquals(2, threads.size());
        assertTrue(threads.stream().allMatch(name -> name.startsWith("test-region-worker-")), threads.toString());
    }

    @Test
    void differentGroupsRunConcurrently() {
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("first", () -> awaitUnchecked(rendezvous));
        batch.add("second", () -> awaitUnchecked(rendezvous));

        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());
        barrier.awaitCompletion();

        // The rendezvous only opens when both groups are inside their work at the same time.
        assertNull(barrier.failure());
        assertTrue(barrier.isComplete());
    }

    @Test
    void failureOfOneGroupReleasesTheBarrierAndIsRethrown() {
        final IllegalStateException failure = new IllegalStateException("boom");
        final AtomicInteger completed = new AtomicInteger();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("failing", () -> {
            throw failure;
        });
        batch.add("surviving", completed::incrementAndGet);

        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertEquals(1, completed.get());
        assertSame(failure, barrier.failure());
        assertSame(failure, assertThrows(IllegalStateException.class, barrier::throwIfFailed));
    }

    @Test
    void rejectedGroupsRunOnTheSubmittingThread() {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("rejected", () -> threads.add(Thread.currentThread().getName()));

        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> false);

        assertEquals(1, barrier.rejectedCount());
        assertTrue(barrier.isComplete());
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void executorThatThrowsRunsTheGroupOnTheSubmittingThread() {
        // A pool that shuts down between the closed check and the submission throws instead of
        // returning false; the group still has to run so the barrier can complete.
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("throws", () -> threads.add(Thread.currentThread().getName()));

        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> {
            throw new RejectedExecutionException("pool closed");
        });

        assertEquals(1, barrier.rejectedCount());
        assertTrue(barrier.isComplete());
        assertNull(barrier.failure());
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void submissionFailureDoesNotStrandTheRemainingGroups() {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger submissions = new AtomicInteger();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("accepted", runs::incrementAndGet);
        batch.add("throws", runs::incrementAndGet);
        batch.add("after", runs::incrementAndGet);

        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> {
            if (submissions.incrementAndGet() == 1) {
                this.workers.execute(task);
                return true;
            }
            throw new RejectedExecutionException("pool closed");
        });
        barrier.awaitCompletion();

        assertEquals(3, runs.get());
        assertTrue(barrier.isComplete());
        assertEquals(2, barrier.rejectedCount());
    }

    @Test
    void interruptedWaitStillWaitsForTheBatch() {
        final CountDownLatch release = new CountDownLatch(1);
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.add("group", () -> awaitUnchecked(release));
        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());

        Thread.currentThread().interrupt();
        release.countDown();
        barrier.awaitCompletion();

        assertTrue(Thread.interrupted(), "the interrupt status must be preserved");
        assertTrue(barrier.isComplete());
    }

    @Test
    void packedGroupsRunExactlyOnceAndTheBarrierWaitsForEveryGroup() {
        final int groupCount = 6;
        final int[] runs = new int[groupCount];
        final CountDownLatch release = new CountDownLatch(1);
        final RegionTaskBatch batch = new RegionTaskBatch();
        final List<Runnable> works = new java.util.ArrayList<>(groupCount);
        for (int index = 0; index < groupCount; ++index) {
            final int group = index;
            works.add(() -> {
                awaitUnchecked(release);
                ++runs[group];
            });
        }
        batch.addPacked("pack", works);
        assertEquals(groupCount, batch.groupCount());
        assertEquals(1, batch.taskCount());

        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());
        release.countDown();
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertNull(barrier.failure());
        for (int index = 0; index < groupCount; ++index) {
            assertEquals(1, runs[index], "group " + index + " ran " + runs[index] + " times");
        }
    }

    @Test
    void aFailingGroupOfAPackDoesNotStopTheGroupsPackedWithIt() {
        final IllegalStateException failure = new IllegalStateException("boom");
        final int[] runs = new int[3];
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.addPacked("pack", List.of(
                () -> ++runs[0],
                () -> {
                    ++runs[1];
                    throw failure;
                },
                () -> ++runs[2]));

        final RegionTaskBatch.Barrier barrier = batch.submit(this.workerExecutor());
        barrier.awaitCompletion();

        assertTrue(barrier.isComplete());
        assertArrayEquals(new int[] { 1, 1, 1 }, runs, "every group of the pack must run exactly once");
        assertSame(failure, barrier.failure());
        assertSame(failure, assertThrows(IllegalStateException.class, barrier::throwIfFailed));
    }

    @Test
    void packedGroupsRunOnOneWorker() {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.addPacked("pack", List.of(
                () -> threads.add(Thread.currentThread().getName()),
                () -> threads.add(Thread.currentThread().getName()),
                () -> threads.add(Thread.currentThread().getName())));

        batch.submit(this.workerExecutor()).awaitCompletion();

        assertEquals(1, threads.size(), "a packed task must run all of its groups on one worker");
        assertTrue(threads.iterator().next().startsWith("test-region-worker-"), threads.toString());
    }

    @Test
    void aRejectedPackedTaskRunsAllOfItsGroupsOnTheSubmittingThread() {
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        final RegionTaskBatch batch = new RegionTaskBatch();
        batch.addPacked("pack", List.of(
                () -> threads.add(Thread.currentThread().getName()),
                () -> threads.add(Thread.currentThread().getName())));

        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> false);

        assertEquals(1, barrier.rejectedCount());
        assertEquals(2, barrier.groupCount());
        assertTrue(barrier.isComplete());
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    void emptyBatchIsImmediatelyComplete() {
        final RegionTaskBatch batch = new RegionTaskBatch();

        assertTrue(batch.isEmpty());
        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> {
            throw new AssertionError("empty batch must not submit work");
        });

        assertTrue(barrier.isComplete());
        assertNull(barrier.failure());
    }

    private RegionWorkerExecutor workerExecutor() {
        return (description, task) -> {
            this.workers.execute(task);
            return true;
        };
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the batch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitUnchecked(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("groups did not run concurrently", exception);
        }
    }
}
