/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import org.jetbrains.annotations.Nullable;

final class RegionScheduledTask implements ScheduledRegionTask {
    @Nullable
    private final RegionTaskQueue queue;
    private final Runnable task;
    private final long periodTicks;
    private boolean cancelled;
    private boolean completed;
    private boolean running;
    private long deadlineTick;

    RegionScheduledTask(Runnable task, long deadlineTick, long periodTicks) {
        this(null, task, deadlineTick, periodTicks);
    }

    RegionScheduledTask(@Nullable RegionTaskQueue queue, Runnable task, long deadlineTick, long periodTicks) {
        this.queue = queue;
        this.task = task;
        this.deadlineTick = deadlineTick;
        this.periodTicks = periodTicks;
    }

    static RegionScheduledTask cancelled(Runnable task) {
        final RegionScheduledTask scheduledTask = new RegionScheduledTask(null, task, 0L, 0L);
        scheduledTask.cancel();
        return scheduledTask;
    }

    @Override
    public synchronized boolean cancel() {
        if (this.cancelled || this.completed) {
            return false;
        }
        this.cancelled = true;
        return true;
    }

    @Override
    public synchronized boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public synchronized boolean isDone() {
        return this.cancelled || this.completed;
    }

    synchronized boolean isDue(long currentTick) {
        return !this.cancelled && !this.completed && !this.running && this.deadlineTick <= currentTick;
    }

    synchronized long remainingDelay() {
        if (this.queue == null) {
            return 0L;
        }
        return Math.max(0L, this.deadlineTick - this.queue.currentTick());
    }

    synchronized boolean markRunning() {
        if (this.cancelled || this.completed || this.running) {
            return false;
        }
        this.running = true;
        return true;
    }

    boolean runAndReschedule(long currentTick) {
        synchronized (this) {
            if (this.cancelled || this.completed) {
                this.running = false;
                return false;
            }
        }

        try {
            this.task.run();
        } catch (RuntimeException | Error exception) {
            synchronized (this) {
                this.completed = true;
                this.running = false;
            }
            throw exception;
        }

        synchronized (this) {
            this.running = false;
            if (this.cancelled || this.periodTicks <= 0L) {
                this.completed = true;
                return false;
            }

            try {
                this.deadlineTick = Math.addExact(currentTick, this.periodTicks);
            } catch (ArithmeticException exception) {
                this.deadlineTick = Long.MAX_VALUE;
            }
            return true;
        }
    }
}
