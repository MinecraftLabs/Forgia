/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Worker pool reserved for native region execution.
 * <p>
 * The pool has two dispatch paths. The default path hands a task to a fixed thread pool; the persistent path
 * hands it to one worker's own slot instead: the submitting thread appends the task to the slot of the worker it
 * selected, publishes the sequence number of the slot and unparks that worker, which runs the task and then
 * spins a bounded number of iterations before it parks again. The persistent path removes the shared queue
 * lock, the executor's worker publication and most of the wake-up latency of a per-tick batch, which is what a
 * light workload pays for in full. The executor path is kept and is used whenever the persistent path is not
 * installed, so a shutdown or a configuration that cannot use it falls back to the unchanged behaviour.
 * <p>
 * {@link #close()} only returns after every worker thread terminated. Work that was accepted before the close
 * is always executed (it holds region leases and batch barriers that other threads wait for), and an interrupt
 * of the closing thread only escalates to interrupting the workers without abandoning the wait. A task that
 * neither returns nor reacts to interrupts therefore blocks shutdown instead of letting the server continue
 * while a worker can still mutate level state. A submission that arrives after the close is refused and runs on
 * the submitting thread instead.
 */
final class RegionWorkerPool implements RegionWorkerExecutor, AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Spin iterations a persistent worker runs before it parks. A batch is submitted and awaited by the server
     * thread, so a worker that just finished a group of the previous batch finds the next one while spinning;
     * an idle worker parks after at most this many iterations and costs no CPU.
     */
    private static final int SPIN_ATTEMPTS = 128;
    /** Longest a parked worker sleeps before it re-checks its slot. */
    private static final long PARK_NANOS = 1_000_000L;
    /**
     * Bounded parks an idle worker runs before it parks without a timeout. An idle worker of a pool that is no
     * longer used - a replaced engine, a shut-down level - costs no CPU at all after this many checks, and a
     * submission always unparks it, so no wake-up is ever lost.
     */
    private static final int BOUNDED_PARKS = 1024;

    private final ExecutorService executor;
    @Nullable
    private final Worker[] workers;
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final int threadCount;
    private final AtomicInteger nextWorker = new AtomicInteger();
    /**
     * The tasks no idle worker could take, drained by whichever worker finishes next.
     * <p>
     * A per-worker slot is only handed a task when its worker has nothing pending, so a saturated batch would be
     * split between the workers before it runs and could not be balanced any more: a region of hoppers that
     * transfers this tick costs several times one that does not, and the barrier waits for the slowest worker.
     * The overflow queue keeps the dynamic balancing of a shared queue for exactly the case - a batch that needs
     * more tasks than there are workers - while an idle worker still starts its task without any queue at all.
     */
    private final ConcurrentLinkedQueue<SubmittedTask> overflow = new ConcurrentLinkedQueue<>();
    /**
     * Number of submissions that observed the open pool and have not finished enqueuing yet. {@link #close()}
     * sets the closed flag and then waits for this to reach zero, so a task can never be enqueued after the
     * workers were told to stop draining.
     */
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicLong submissions = new AtomicLong();
    private final AtomicLong submitNanos = new AtomicLong();
    private final AtomicLong wakeNanos = new AtomicLong();
    private final AtomicLong idleWakeUps = new AtomicLong();
    private final AtomicLong spinWakeUps = new AtomicLong();
    private final AtomicLong parkWakeUps = new AtomicLong();
    private volatile boolean closed;

    /**
     * Creates a pool that hands its work to a fixed thread pool.
     */
    RegionWorkerPool(int threadCount) {
        this(threadCount, false);
    }

    /**
     * Creates a pool with the selected dispatch path.
     *
     * @param persistentWorkers {@code true} to dispatch through the per-worker slots of a persistent worker
     *                          instead of a shared executor queue
     */
    RegionWorkerPool(int threadCount, boolean persistentWorkers) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be > 0");
        }
        this.threadCount = threadCount;
        if (persistentWorkers) {
            this.executor = null;
            this.workers = new Worker[threadCount];
            for (int index = 0; index < threadCount; ++index) {
                final Worker worker = new Worker(index + 1);
                this.workers[index] = worker;
                worker.thread.start();
            }
        } else {
            this.executor = Executors.newFixedThreadPool(threadCount, new RegionThreadFactory(this.workerThreads));
            this.workers = null;
        }
    }

    int threadCount() {
        return this.threadCount;
    }

    /** {@return true when this pool dispatches through its persistent workers} */
    boolean isPersistent() {
        return this.workers != null;
    }

    /**
     * {@return true when {@code thread} is one of this pool's worker threads}
     * <p>
     * Lifecycle transitions and blocking region drains use this to fail fast instead of waiting for work
     * that only the calling worker could complete.
     */
    boolean isWorkerThread(Thread thread) {
        return this.workerThreads.contains(thread);
    }

    @Override
    public boolean execute(String description, Runnable task) {
        if (this.closed) {
            LOGGER.warn("Rejected region worker task after shutdown: {}", description);
            return false;
        }
        if (this.workers != null) {
            return this.executePersistent(description, task);
        }
        final long start = System.nanoTime();
        this.admissions.incrementAndGet();
        try {
            if (this.closed) {
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
            try {
                this.executor.execute(() -> this.runTask(description, task));
                this.submissions.incrementAndGet();
                return true;
            } catch (RejectedExecutionException exception) {
                // close() may race with an admission that already observed the open flag; the caller runs the
                // rejected work on its own thread instead of losing it.
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
        } finally {
            this.admissions.decrementAndGet();
            this.submitNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * Hands {@code task} to an idle worker's slot, or to the shared overflow queue when every worker is busy.
     * <p>
     * The task is enqueued before the sequence number of the slot is published, so a worker that observes the new
     * sequence always finds the task; exactly one worker consumes a slot and every worker may consume the overflow
     * queue, whose poll is atomic, so a task is never run twice and never dropped. A submission that races
     * {@link #close()} is refused: the admission counter keeps the enqueue and the stop of the drain loop ordered,
     * so no task is enqueued after the workers were told to stop.
     */
    private boolean executePersistent(String description, Runnable task) {
        final long start = System.nanoTime();
        this.admissions.incrementAndGet();
        try {
            if (this.closed) {
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
            // An idle worker starts the task immediately and is the only worker that needs a wake-up, so it is
            // preferred. A task no idle worker can take goes to the overflow queue, which the workers drain when
            // they finish: that keeps the dynamic balancing of a shared queue instead of pinning the task to one
            // worker before it runs.
            final Worker idle = this.selectIdleWorker();
            // A task that is handed to an idle worker measures the wake-up: the submitter observed no pending
            // task in the slot. A task that goes to the overflow queue waits behind other work instead, which
            // the barrier wait already reports, so it is not counted as a wake-up.
            // The submission time travels with the task, so the wake-up latency of one task is measurable even
            // when several tasks wait in the same slot.
            final SubmittedTask submitted = new SubmittedTask(description, task, start, idle != null);
            if (idle != null) {
                idle.tasks.add(submitted);
                idle.published.incrementAndGet();
                LockSupport.unpark(idle.thread);
            } else {
                this.overflow.add(submitted);
                // Every worker is busy, so one of them drains the queue when it finishes. Only a worker that is
                // already parked has to be woken - and only one of them, because every worker drains the queue -
                // so a saturated batch costs no wake-up syscall on the submitting thread at all.
                for (final Worker worker : this.workers) {
                    if (worker.parked) {
                        LockSupport.unpark(worker.thread);
                        break;
                    }
                }
            }
            this.submissions.incrementAndGet();
            return true;
        } finally {
            this.admissions.decrementAndGet();
            this.submitNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * {@return a worker with nothing pending, or {@code null} when every worker is busy}
     * <p>
     * A worker that has nothing pending answers in the time of one task, so it is preferred: the task starts
     * immediately and only that worker needs a wake-up. The scan starts at a round-robin index, so idle workers
     * are used evenly when the pool is not saturated.
     */
    @Nullable
    private Worker selectIdleWorker() {
        final int start = Math.floorMod(this.nextWorker.getAndIncrement(), this.workers.length);
        for (int offset = 0; offset < this.workers.length; ++offset) {
            final Worker worker = this.workers[(start + offset) % this.workers.length];
            if (worker.isIdle()) {
                return worker;
            }
        }
        return null;
    }

    /**
     * Runs one accepted task on the calling worker thread with the native worker scope installed.
     */
    private void runTask(String description, Runnable task) {
        // The origin scope belongs to the pool, so it stays valid when the manager or engine is
        // removed while this task is still unwinding. It is cleared in finally for every exit.
        RegionWorkerScope.enter();
        try {
            task.run();
        } catch (Throwable throwable) {
            LOGGER.error("Region worker task failed: {}", description, throwable);
            throw throwable;
        } finally {
            RegionWorkerScope.exit();
        }
    }

    /**
     * Runs one accepted task on a persistent worker and keeps the worker alive when the task fails.
     * <p>
     * A failing task must not retire the worker: the worker owns the slot the pool dispatches to, so its
     * failure is logged here and the loop continues with the next task of the slot.
     */
    private void runPersistentTask(String description, Runnable task) {
        RegionWorkerScope.enter();
        try {
            task.run();
        } catch (Throwable throwable) {
            LOGGER.error("Region worker task failed: {}", description, throwable);
        } finally {
            RegionWorkerScope.exit();
        }
    }

    /**
     * {@return a snapshot of the dispatch counters of this pool}
     * <p>
     * {@code submitNanos} is the wall time the submitting threads spent handing tasks to the pool.
     * {@code wakeNanos} is the wall time between that hand-off and the start of the task body, counted only for
     * the tasks a submitter handed to a worker that had nothing pending - that is the wake-up the pool controls -
     * and {@code idleWakeUps} counts them. A task that waited behind a running task of the same worker measures
     * that task's execution instead, which the batch barrier wait reports. {@code spinWakeUps} and
     * {@code parkWakeUps} split the wake-ups into the ones a spinning worker took and the ones a parked worker
     * took.
     */
    DispatchSnapshot dispatchSnapshot() {
        return new DispatchSnapshot(this.submissions.get(), this.submitNanos.get(), this.wakeNanos.get(),
                this.idleWakeUps.get(), this.spinWakeUps.get(), this.parkWakeUps.get());
    }

    /** {@return true when this pool dispatches through per-worker slots} */
    boolean persistentWorkers() {
        return this.workers != null;
    }

    @Override
    public void close() {
        if (this.workerThreads.contains(Thread.currentThread())) {
            throw new IllegalStateException("Cannot close the region worker pool from a region worker thread");
        }
        if (this.closed) {
            return;
        }
        this.closed = true;
        // A submission that observed the open pool has to finish enqueuing before the workers are told to stop,
        // otherwise its task could be enqueued into a slot whose worker already left the drain loop.
        while (this.admissions.get() != 0) {
            Thread.onSpinWait();
        }
        if (this.workers != null) {
            this.closePersistent();
        } else {
            this.closeExecutor();
        }
    }

    private void closePersistent() {
        for (final Worker worker : this.workers) {
            worker.stopping = true;
            LockSupport.unpark(worker.thread);
        }
        boolean interrupted = false;
        for (final Worker worker : this.workers) {
            while (true) {
                try {
                    worker.thread.join();
                    break;
                } catch (InterruptedException exception) {
                    // The pool cannot be abandoned: a worker may still be mutating region state. Ask the
                    // running tasks to stop and keep waiting for the workers to finish.
                    interrupted = true;
                    worker.thread.interrupt();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeExecutor() {
        this.executor.shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (this.executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException exception) {
                // The pool cannot be abandoned: a worker may still be mutating region state. Ask the
                // running tasks to stop and keep waiting for termination.
                interrupted = true;
                this.executor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One task handed to a worker slot, with the time and the description of the hand-off.
     */
    private record SubmittedTask(String description, Runnable work, long submittedNanos, boolean idleSubmission) {}

    /**
     * Dispatch counters of one pool.
     */
    record DispatchSnapshot(long submissions, long submitNanos, long wakeNanos, long idleWakeUps, long spinWakeUps, long parkWakeUps) {}

    /**
     * One persistent worker: its thread, the slot it consumes and the sequence number that publishes a task.
     * <p>
     * The queue is single-consumer and is written by the submitting threads. {@link #published} counts the
     * tasks enqueued into the slot, so the worker can spin on a plain counter instead of polling the queue: it
     * polls only when the count it has not consumed yet is non-zero, and the count is published after the
     * enqueue, so a non-zero count always means a task is waiting.
     */
    private final class Worker {
        private final ConcurrentLinkedQueue<SubmittedTask> tasks = new ConcurrentLinkedQueue<>();
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final Thread thread;
        private volatile boolean stopping;
        /** Whether this worker is running a task, of its slot or of the overflow queue. */
        private volatile boolean busy;
        /** Whether this worker is parked; a submission that cannot find an idle worker wakes exactly one. */
        private volatile boolean parked;

        private Worker(int index) {
            this.thread = new Thread(this::run, "NeoForge Region Worker #" + index);
            this.thread.setDaemon(true);
            RegionWorkerPool.this.workerThreads.add(this.thread);
        }

        /** {@return true when this worker runs nothing and has no task pending in its slot} */
        private boolean isIdle() {
            return !this.busy && this.published.get() == this.completed.get();
        }

        private void run() {
            long consumed = 0L;
            int spins = 0;
            int idleParks = 0;
            while (true) {
                final long published = this.published.get();
                if (published != consumed) {
                    final SubmittedTask task = this.tasks.poll();
                    if (task == null) {
                        // The sequence number is published after the enqueue, so this only happens when the
                        // consumer raced itself; re-read instead of dropping the wake-up.
                        continue;
                    }
                    ++consumed;
                    if (spins < SPIN_ATTEMPTS) {
                        RegionWorkerPool.this.spinWakeUps.incrementAndGet();
                    } else {
                        RegionWorkerPool.this.parkWakeUps.incrementAndGet();
                    }
                    if (task.idleSubmission()) {
                        RegionWorkerPool.this.idleWakeUps.incrementAndGet();
                        RegionWorkerPool.this.wakeNanos.addAndGet(System.nanoTime() - task.submittedNanos());
                    }
                    spins = 0;
                    idleParks = 0;
                    this.runTask(task);
                    this.completed.set(consumed);
                    continue;
                }
                if (spins >= SPIN_ATTEMPTS) {
                    // Every worker drains the overflow queue when it finds nothing of its own, so a task that
                    // arrived while all workers were busy runs on whichever worker finishes next. Its wait is
                    // reported by the batch barrier, not as a wake-up of this pool.
                    final SubmittedTask shared = RegionWorkerPool.this.overflow.poll();
                    if (shared != null) {
                        spins = 0;
                        idleParks = 0;
                        this.runTask(shared);
                        continue;
                    }
                }
                if (this.stopping) {
                    // The close waits for this thread; a task published before the stop is still drained,
                    // because the count is only final after every admission finished enqueuing.
                    if (this.published.get() == consumed && RegionWorkerPool.this.overflow.isEmpty()) {
                        return;
                    }
                    // Drain what is left without spinning; another worker may take it instead.
                    spins = SPIN_ATTEMPTS;
                    continue;
                }
                ++spins;
                if (spins < SPIN_ATTEMPTS) {
                    Thread.onSpinWait();
                    continue;
                }
                // Publish the parked state and re-check both work sources before parking: a submitter that hands
                // a task to the overflow queue wakes a worker only when it sees this flag, and a re-check that
                // runs after the publication makes a lost wake-up impossible.
                this.parked = true;
                try {
                    if (this.published.get() != consumed || !RegionWorkerPool.this.overflow.isEmpty()) {
                        continue;
                    }
                    if (idleParks < BOUNDED_PARKS) {
                        ++idleParks;
                        LockSupport.parkNanos(this, PARK_NANOS);
                    } else {
                        LockSupport.park(this);
                    }
                    Thread.interrupted();
                } finally {
                    this.parked = false;
                }
            }
        }

        /**
         * Runs one task of either source and keeps the worker alive when the task fails.
         */
        private void runTask(SubmittedTask task) {
            this.busy = true;
            try {
                RegionWorkerPool.this.runPersistentTask(task.description(), task.work());
            } finally {
                this.busy = false;
            }
        }
    }

    private static final class RegionThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final Set<Thread> workerThreads;

        private RegionThreadFactory(Set<Thread> workerThreads) {
            this.workerThreads = workerThreads;
        }

        @Override
        public Thread newThread(Runnable task) {
            final Thread thread = new Thread(task, "NeoForge Region Worker #" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            this.workerThreads.add(thread);
            return thread;
        }
    }
}
