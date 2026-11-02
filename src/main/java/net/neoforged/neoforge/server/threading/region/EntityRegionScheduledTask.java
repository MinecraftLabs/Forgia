/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;

final class EntityRegionScheduledTask implements ScheduledRegionTask {
    private final RegionThreadingEngine engine;
    private final Entity entity;
    private final Runnable task;
    private final long initialDelayTicks;
    private final long periodTicks;
    private RegionScheduledTask scheduledTask;
    private RegionCoordinate scheduledCoordinate;
    private long generation;
    private boolean cancelled;
    private boolean completed;
    private boolean running;

    EntityRegionScheduledTask(RegionThreadingEngine engine, Entity entity, Runnable task, long delayTicks, long periodTicks) {
        this.engine = engine;
        this.entity = entity;
        this.task = task;
        this.initialDelayTicks = delayTicks;
        this.periodTicks = periodTicks;
    }

    /**
     * Queues the first run. Must be called after the engine has registered this task.
     */
    void start() {
        this.scheduleNext(this.initialDelayTicks);
    }

    @Override
    public synchronized boolean cancel() {
        if (this.cancelled || this.completed) {
            return false;
        }
        this.cancelled = true;
        if (this.scheduledTask != null) {
            this.scheduledTask.cancel();
        }
        this.engine.unregisterEntityTask(this);
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

    Entity entity() {
        return this.entity;
    }

    boolean isOwnedBy(ServerLevel level) {
        return this.entity.level() == level;
    }

    /**
     * Moves outstanding work to the region that now contains the entity.
     * <p>
     * Runs under the engine admission lock; the next schedule reservation is taken here, but the engine
     * admission happens after the monitor is released. See {@link #scheduleNext(long)}.
     */
    void onEntityMoved() {
        final long remainingTicks;
        synchronized (this) {
            if (this.cancelled || this.completed || this.running) {
                return;
            }
            if (this.entity.isRemoved() || !(this.entity.level() instanceof ServerLevel level)) {
                this.complete();
                return;
            }
            final BlockPos position = this.entity.blockPosition();
            final RegionCoordinate coordinate = this.engine.regionAt(level, position);
            if (coordinate.equals(this.scheduledCoordinate)) {
                return;
            }
            remainingTicks = this.scheduledTask == null ? 0L : this.scheduledTask.remainingDelay();
            if (this.scheduledTask != null) {
                this.scheduledTask.cancel();
            }
        }
        this.engine.recordEntityTaskMigration();
        this.scheduleNext(remainingTicks);
    }

    private void run(long scheduledGeneration) {
        synchronized (this) {
            if (this.cancelled || this.completed || this.running || scheduledGeneration != this.generation) {
                return;
            }
            this.running = true;
        }

        try {
            if (this.entity.isRemoved() || !(this.entity.level() instanceof ServerLevel)) {
                this.complete();
                return;
            }
            if (!this.engine.isOwnedByCurrentRegion(this.entity)) {
                this.scheduleNext(0L);
                return;
            }

            this.task.run();

            synchronized (this) {
                if (this.cancelled || this.completed) {
                    return;
                }
                if (this.periodTicks <= 0L) {
                    this.complete();
                    return;
                }
            }
            this.scheduleNext(this.periodTicks);
        } catch (RuntimeException | Error exception) {
            this.complete();
            throw exception;
        } finally {
            synchronized (this) {
                this.running = false;
            }
        }
    }

    /**
     * Queues the next run on the entity's current region.
     * <p>
     * The engine admission (which takes the admission lock and the regionizer write lock) must never run
     * while this task's monitor is held. {@link RegionThreadingEngine#moveRegionEntity(Entity)} takes the
     * admission lock and then calls {@link #onEntityMoved()}, so holding this monitor across admission
     * would invert the lock order and deadlock a reschedule against a migration.
     * <p>
     * The monitor is therefore only used to reserve a generation and to publish the resulting child. A
     * concurrent cancel, completion or newer schedule invalidates the reservation and the child is
     * cancelled after the admission instead of being published.
     */
    private void scheduleNext(long delayTicks) {
        final long scheduledGeneration;
        final ServerLevel level;
        final BlockPos position;
        synchronized (this) {
            if (this.cancelled || this.completed) {
                return;
            }
            if (this.entity.isRemoved() || !(this.entity.level() instanceof ServerLevel currentLevel)) {
                this.complete();
                return;
            }
            level = currentLevel;
            position = this.entity.blockPosition();
            scheduledGeneration = ++this.generation;
        }

        final RegionScheduledTask child;
        try {
            child = this.engine.scheduleRegion(level, position, () -> this.run(scheduledGeneration), delayTicks);
        } catch (IllegalStateException exception) {
            // The engine only rejects a valid admission once shutdown started. The wrapper must not stay
            // registered as a live periodic task after its admission was refused.
            this.retireAfterClosedChild();
            return;
        }

        synchronized (this) {
            if (this.cancelled || this.completed || this.generation != scheduledGeneration) {
                // A cancel, migration or newer reschedule won while the admission was in progress. The
                // losing child must not stay queued in a region that is no longer the entity's owner.
                child.cancel();
                return;
            }
            this.scheduledCoordinate = this.engine.regionAt(level, position);
            this.scheduledTask = child;
            if (child.isCancelled()) {
                // The child was cancelled because its queue, level or the engine was retired while it was
                // being admitted. This is retirement, not ordinary completion: keep the public handle
                // deterministically cancelled and unregistered instead of reporting a silent completion.
                this.retireAfterClosedChild();
            }
        }
    }

    /**
     * Retires the wrapper after its child was rejected or cancelled by lifecycle retirement.
     * <p>
     * The public handle ends {@link #isCancelled() cancelled} and {@link #isDone() done}, exactly like an
     * explicit cancel, because the periodic task was dropped by the engine rather than finishing normally.
     */
    private synchronized void retireAfterClosedChild() {
        if (this.cancelled || this.completed) {
            return;
        }
        this.cancelled = true;
        if (this.scheduledTask != null) {
            this.scheduledTask.cancel();
        }
        this.engine.unregisterEntityTask(this);
    }

    private synchronized void complete() {
        if (this.completed) {
            return;
        }
        this.completed = true;
        if (this.scheduledTask != null) {
            this.scheduledTask.cancel();
        }
        this.engine.unregisterEntityTask(this);
    }
}
