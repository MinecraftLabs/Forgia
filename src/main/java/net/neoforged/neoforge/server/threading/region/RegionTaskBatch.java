/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One batch of region-scoped work that is submitted to the worker pool and awaited by the submitting
 * thread before it continues.
 * <p>
 * A group is the unit of region exclusivity: the caller adds one group per region, so the pool can run
 * different regions in parallel while a region can never run against itself. {@link #submit} returns a
 * {@link Barrier} that completes only after every submitted group finished or failed, which gives the
 * server thread a single point to wait on before it touches state the workers may be mutating.
 * <p>
 * A group the executor rejects or fails to accept runs on the submitting thread instead of being dropped,
 * so a closing pool can never strand the barrier. The first failure of any group is recorded and rethrown
 * by the submitting thread through {@link Barrier#throwIfFailed()} once the whole batch is complete; the
 * remaining failures are reported by the worker pool itself.
 */
@ApiStatus.Internal
public final class RegionTaskBatch {
    private static final Logger LOGGER = LogManager.getLogger();

    private final List<Group> groups = new ArrayList<>();

    /**
     * Adds one group of region work.
     *
     * @param description human readable description used by worker diagnostics
     * @param work        the work of a single region; it must release any state it acquired even when it fails
     */
    public void add(String description, Runnable work) {
        this.groups.add(new Group(description, work));
    }

    public boolean isEmpty() {
        return this.groups.isEmpty();
    }

    public int groupCount() {
        return this.groups.size();
    }

    /**
     * Submits every group to {@code executor} and returns the barrier of this batch.
     * <p>
     * Groups the executor rejects are run on the calling thread before this method returns.
     */
    public Barrier submit(RegionWorkerExecutor executor) {
        final Barrier barrier = this.newBarrier();
        this.submit(executor, barrier);
        return barrier;
    }

    /**
     * {@return a barrier that completes when every group added so far finished}
     * <p>
     * Lets the caller publish the batch before it is submitted, so a concurrent shutdown cannot miss a
     * batch that already holds region leases. Groups must not be added after this call.
     */
    public Barrier newBarrier() {
        return new Barrier(this.groups.size());
    }

    /**
     * Submits every group to {@code executor} against {@code barrier}, which must come from
     * {@link #newBarrier()} of this batch.
     */
    public void submit(RegionWorkerExecutor executor, Barrier barrier) {
        if (barrier.groupCount() != this.groups.size()) {
            throw new IllegalStateException("Batch barrier was created for " + barrier.groupCount() + " groups but " + this.groups.size() + " were added");
        }
        this.submitGroups(executor, barrier);
    }

    /**
     * Submits every group against an open barrier from {@link Barrier#open()}.
     * <p>
     * The barrier keeps an owner permit until {@link Barrier#setupComplete()}, so a batch can publish
     * itself before it knows how many groups it will hold. Groups are registered one by one right before
     * they are submitted, so a group that finishes immediately can never complete the barrier before the
     * setup finished.
     */
    public void submitOpen(RegionWorkerExecutor executor, Barrier barrier) {
        for (final Group group : this.groups) {
            barrier.registerGroup();
            this.submitGroup(executor, barrier, group);
        }
    }

    private void submitGroups(RegionWorkerExecutor executor, Barrier barrier) {
        for (final Group group : this.groups) {
            this.submitGroup(executor, barrier, group);
        }
    }

    private void submitGroup(RegionWorkerExecutor executor, Barrier barrier, Group group) {
        final Runnable task = () -> {
            try {
                group.work().run();
            } catch (Throwable throwable) {
                barrier.recordFailure(throwable);
            } finally {
                barrier.groupFinished();
            }
        };
        if (!trySubmit(executor, group.description(), task)) {
            barrier.recordRejected();
            task.run();
        }
    }

    /**
     * {@return true when {@code executor} accepted {@code task}}
     * <p>
     * An executor that throws instead of returning {@code false} still has to run the task on the
     * submitting thread: the work holds region leases and the barrier only completes when it ran.
     */
    private static boolean trySubmit(RegionWorkerExecutor executor, String description, Runnable task) {
        try {
            return executor.execute(description, task);
        } catch (Throwable throwable) {
            LOGGER.warn("Region worker executor failed to accept {}; running it on the submitting thread", description, throwable);
            return false;
        }
    }

    private record Group(String description, Runnable work) {}

    /**
     * Completion barrier of one submitted batch.
     */
    public static final class Barrier {
        private final AtomicInteger pendingGroups;
        private final CountDownLatch completed;
        private final int groupCount;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger rejectedGroups = new AtomicInteger();
        private boolean open;

        private Barrier(int groupCount) {
            this(groupCount, false);
        }

        private Barrier(int groupCount, boolean open) {
            if (groupCount < 0) {
                throw new IllegalArgumentException("groupCount must be >= 0");
            }
            this.pendingGroups = new AtomicInteger(groupCount);
            this.completed = new CountDownLatch(groupCount == 0 ? 0 : 1);
            this.groupCount = groupCount;
            this.open = open;
        }

        /**
         * {@return a barrier that starts with one owner permit and accepts groups through
         * {@link #registerGroup()} until {@link #setupComplete()} is called}
         * <p>
         * This lets a batch publish its completion latch before it acquires any region lease: the owner
         * permit keeps the barrier incomplete while the batch is still being set up, and each registered
         * group adds a permit of its own.
         */
        public static Barrier open() {
            return new Barrier(1, true);
        }

        /**
         * {@return the number of groups this barrier waits for}
         */
        public int groupCount() {
            return this.groupCount;
        }

        /**
         * {@return true when every group of the batch finished}
         */
        public boolean isComplete() {
            return this.pendingGroups.get() == 0;
        }

        /**
         * Waits until every group of the batch finished, preserving the interrupt status of the caller.
         */
        public void awaitCompletion() {
            boolean interrupted = false;
            while (true) {
                try {
                    this.completed.await();
                    break;
                } catch (InterruptedException exception) {
                    // The batch cannot be abandoned: a worker may still be mutating region state.
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Registers one more group against an open barrier.
         */
        void registerGroup() {
            synchronized (this) {
                if (!this.open) {
                    throw new IllegalStateException("Barrier setup is already complete");
                }
                if (this.pendingGroups.get() <= 0) {
                    throw new IllegalStateException("Barrier is already complete");
                }
                this.pendingGroups.incrementAndGet();
            }
        }

        /**
         * Releases the owner permit of an open barrier; no group may be registered afterwards.
         */
        void setupComplete() {
            synchronized (this) {
                if (!this.open) {
                    throw new IllegalStateException("Barrier setup is already complete");
                }
                this.open = false;
            }
            this.groupFinished();
        }

        /**
         * {@return the first failure of the batch, or {@code null} when no group failed}
         */
        @Nullable
        public Throwable failure() {
            return this.failure.get();
        }

        /**
         * {@return the number of groups the executor rejected and that therefore ran on the submitting thread}
         */
        public int rejectedCount() {
            return this.rejectedGroups.get();
        }

        /**
         * Rethrows the failure of the batch so the submitting thread reports it like any other tick failure.
         */
        public void throwIfFailed() {
            final Throwable throwable = this.failure.get();
            if (throwable == null) {
                return;
            }
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Region worker task failed", throwable);
        }

        private void recordFailure(Throwable throwable) {
            this.failure.compareAndSet(null, throwable);
        }

        private void recordRejected() {
            this.rejectedGroups.incrementAndGet();
        }

        private void groupFinished() {
            if (this.pendingGroups.decrementAndGet() == 0) {
                this.completed.countDown();
            }
        }
    }
}
