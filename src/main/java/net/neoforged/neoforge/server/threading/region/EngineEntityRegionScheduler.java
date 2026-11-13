/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;

final class EngineEntityRegionScheduler implements EntityRegionScheduler {
    private final RegionThreadingEngine engine;

    EngineEntityRegionScheduler(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    @Override
    public void execute(Entity entity, Runnable task) {
        this.runDelayed(entity, task, 0L);
    }

    @Override
    public ScheduledRegionTask runDelayed(Entity entity, Runnable task, long delayTicks) {
        if (delayTicks < 0L) {
            throw new IllegalArgumentException("delayTicks must be >= 0");
        }
        if (entity.isRemoved()) {
            return RegionScheduledTask.cancelled(task);
        }
        return this.engine.scheduleEntity(entity, task, delayTicks);
    }

    @Override
    public ScheduledRegionTask runAtFixedRate(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        if (initialDelayTicks < 0L) {
            throw new IllegalArgumentException("initialDelayTicks must be >= 0");
        }
        if (periodTicks <= 0L) {
            throw new IllegalArgumentException("periodTicks must be > 0");
        }
        if (entity.isRemoved()) {
            return RegionScheduledTask.cancelled(task);
        }
        return this.engine.scheduleEntityAtFixedRate(entity, task, initialDelayTicks, periodTicks);
    }
}
