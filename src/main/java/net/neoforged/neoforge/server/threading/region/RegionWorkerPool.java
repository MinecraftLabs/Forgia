/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Worker pool reserved for native region execution.
 * <p>
 * {@link #close()} only returns after every worker thread terminated. Queued work is always executed
 * (it holds region leases and batch barriers that other threads wait for), and an interrupt of the
 * closing thread only escalates to {@link ExecutorService#shutdownNow()} without abandoning the wait.
 * A task that neither returns nor reacts to interrupts therefore blocks shutdown instead of letting the
 * server continue while a worker can still mutate level state.
 */
final class RegionWorkerPool implements RegionWorkerExecutor, AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();

    private final ExecutorService executor;
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final int threadCount;
    private volatile boolean closed;

    RegionWorkerPool(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be > 0");
        }
        this.threadCount = threadCount;
        this.executor = Executors.newFixedThreadPool(threadCount, new RegionThreadFactory(this.workerThreads));
    }

    int threadCount() {
        return this.threadCount;
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
        try {
            this.executor.execute(() -> {
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
            });
            return true;
        } catch (RejectedExecutionException exception) {
            // close() may race with an admission that already observed the open flag; the caller runs the
            // rejected work on its own thread instead of losing it.
            LOGGER.warn("Rejected region worker task after shutdown: {}", description);
            return false;
        }
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
