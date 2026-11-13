/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class RegionTaskQueue {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<RegionScheduledTask> scheduledTasks = new ArrayList<>();
    private final Set<Thread> drainingThreads = new HashSet<>();
    @Nullable
    private final Runnable beforeImmediateInsertHook;
    @Nullable
    private final Runnable beforeScheduledInsertHook;
    private volatile long cappedDrainCount;
    private volatile long currentTick;
    private volatile boolean closed;

    RegionTaskQueue() {
        this(null, null);
    }

    /**
     * Creates a queue with optional insertion hooks. The hooks are only used by tests to force
     * interleavings between insertion and {@link #clearAndCancel()}; they run while the insertion
     * lock is held, immediately before the new work is inserted.
     */
    RegionTaskQueue(@Nullable Runnable beforeImmediateInsertHook, @Nullable Runnable beforeScheduledInsertHook) {
        this.beforeImmediateInsertHook = beforeImmediateInsertHook;
        this.beforeScheduledInsertHook = beforeScheduledInsertHook;
    }

    void enqueue(Runnable task) {
        synchronized (this.scheduledTasks) {
            if (this.closed) {
                return;
            }
            if (this.beforeImmediateInsertHook != null) {
                this.beforeImmediateInsertHook.run();
            }
            this.tasks.add(task);
        }
    }

    RegionScheduledTask schedule(Runnable task, long delayTicks) {
        if (delayTicks < 0L) {
            throw new IllegalArgumentException("delayTicks must be >= 0");
        }
        return this.schedule(task, delayTicks, 0L);
    }

    RegionScheduledTask scheduleAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        if (initialDelayTicks < 0L) {
            throw new IllegalArgumentException("initialDelayTicks must be >= 0");
        }
        if (periodTicks <= 0L) {
            throw new IllegalArgumentException("periodTicks must be > 0");
        }
        return this.schedule(task, initialDelayTicks, periodTicks);
    }

    int drain() {
        return this.drain(RegionThreadingConfig.maxImmediateTasksPerDrain(), RegionThreadingConfig.maxScheduledTasksPerDrain());
    }

    int drain(int maxImmediateTasks, int maxScheduledTasks) {
        synchronized (this.scheduledTasks) {
            if (this.closed) {
                // The queue was retired before this drain started; it has no logical tick left to run.
                return 0;
            }
            if (!this.drainingThreads.add(Thread.currentThread())) {
                // A task drained by this queue must not drain the same queue again: a second snapshot
                // would run work that belongs to the next tick and advance the logical tick (and every
                // scheduled deadline) inside the outer tick. The engine guards the same-region case; this
                // is the last line of defense for direct queue users.
                return 0;
            }
        }
        try {
            int failedTasks = 0;
            for (final Runnable task : this.pollImmediateTasks(maxImmediateTasks)) {
                if (this.closed) {
                    // The owner retired the queue (level unload, engine shutdown) after the snapshot was taken;
                    // work that was queued before belongs to a region that no longer exists.
                    break;
                }
                try {
                    task.run();
                } catch (RuntimeException | Error exception) {
                    ++failedTasks;
                    LOGGER.error("Region task failed", exception);
                }
            }

            for (final RegionScheduledTask scheduledTask : this.pollDueScheduledTasks(maxScheduledTasks)) {
                if (this.closed) {
                    scheduledTask.cancel();
                    continue;
                }
                try {
                    if (scheduledTask.runAndReschedule(this.currentTick)) {
                        synchronized (this.scheduledTasks) {
                            if (this.closed) {
                                scheduledTask.cancel();
                            } else {
                                this.scheduledTasks.add(scheduledTask);
                            }
                        }
                    }
                } catch (RuntimeException | Error exception) {
                    ++failedTasks;
                    LOGGER.error("Scheduled region task failed", exception);
                }
            }

            synchronized (this.scheduledTasks) {
                if (!this.closed) {
                    // A queue retired during this drain has no next tick: advancing the logical tick
                    // after the close would shift deadlines on state the owner already dropped.
                    ++this.currentTick;
                }
            }
            return failedTasks;
        } finally {
            synchronized (this.scheduledTasks) {
                this.drainingThreads.remove(Thread.currentThread());
            }
        }
    }

    long cappedDrainCount() {
        return this.cappedDrainCount;
    }

    long currentTick() {
        return this.currentTick;
    }

    boolean isEmpty() {
        synchronized (this.scheduledTasks) {
            this.scheduledTasks.removeIf(RegionScheduledTask::isCancelled);
            return this.tasks.isEmpty() && this.scheduledTasks.isEmpty();
        }
    }

    void clearAndCancel() {
        synchronized (this.scheduledTasks) {
            this.closed = true;
            this.tasks.clear();
            this.scheduledTasks.forEach(RegionScheduledTask::cancel);
            this.scheduledTasks.clear();
        }
    }

    private RegionScheduledTask schedule(Runnable task, long delayTicks, long periodTicks) {
        synchronized (this.scheduledTasks) {
            if (this.closed) {
                return RegionScheduledTask.cancelled(task);
            }
            if (this.beforeScheduledInsertHook != null) {
                this.beforeScheduledInsertHook.run();
            }
            final RegionScheduledTask scheduledTask = new RegionScheduledTask(this, task, deadlineTick(this.currentTick, delayTicks), periodTicks);
            this.scheduledTasks.add(scheduledTask);
            return scheduledTask;
        }
    }

    private static long deadlineTick(long currentTick, long delayTicks) {
        try {
            return Math.addExact(currentTick, delayTicks);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Removes the immediate tasks this drain is allowed to run.
     * <p>
     * Work inserted while the drain is running — cross-region handoffs, own re-handoffs and follow-up
     * scheduling from the tasks that run now — is left in the queue for the next tick of the region.
     * Without this snapshot a region tick could run an unbounded amount of work, and a tick deferred to a
     * busy region would silently run inside the tick that made it busy instead of on the next one.
     */
    private List<Runnable> pollImmediateTasks(int maxImmediateTasks) {
        final List<Runnable> batch = new ArrayList<>();
        synchronized (this.scheduledTasks) {
            if (this.closed) {
                return batch;
            }
            Runnable task;
            while ((task = this.tasks.poll()) != null) {
                batch.add(task);
                if (maxImmediateTasks > 0 && batch.size() >= maxImmediateTasks && !this.tasks.isEmpty()) {
                    ++this.cappedDrainCount;
                    LOGGER.warn("Region task drain hit immediate task limit of {}", maxImmediateTasks);
                    break;
                }
            }
        }
        return batch;
    }

    private List<RegionScheduledTask> pollDueScheduledTasks(int maxScheduledTasks) {
        final List<RegionScheduledTask> dueTasks = new ArrayList<>();
        synchronized (this.scheduledTasks) {
            final Iterator<RegionScheduledTask> iterator = this.scheduledTasks.iterator();
            while (iterator.hasNext()) {
                final RegionScheduledTask scheduledTask = iterator.next();
                if (scheduledTask.isCancelled()) {
                    iterator.remove();
                } else if (scheduledTask.isDue(this.currentTick)) {
                    if (maxScheduledTasks > 0 && dueTasks.size() >= maxScheduledTasks) {
                        ++this.cappedDrainCount;
                        LOGGER.warn("Region task drain hit scheduled task limit of {}", maxScheduledTasks);
                        break;
                    }
                    if (scheduledTask.markRunning()) {
                        iterator.remove();
                        dueTasks.add(scheduledTask);
                    }
                }
            }
        }
        return dueTasks;
    }
}
