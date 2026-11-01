/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.PartEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Entities of one level tick that are ticked on their owning region worker instead of the server thread.
 * <p>
 * {@link #accept(Entity)} runs the vanilla inclusion, range, and vehicle predicates on the server thread
 * and records the entity when its whole passenger stack belongs to a single region. The server thread
 * keeps iterating the level entity tick list while it collects, and only submits the collected entities
 * once the iteration is over, so the list is never iterated and mutated by workers at the same time.
 * <p>
 * Anything the plan does not accept is ticked inline by the vanilla loop, which keeps unsafe cases (cross
 * region passenger stacks, out of range entities, non tickable entities) on the server thread.
 */
@ApiStatus.Internal
public final class RegionEntityTickPlan {
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final Predicate<Entity> inEntityTickingRange;
    private final RegionTickBarrier barrier;
    private final List<Entity> accepted = new ArrayList<>();

    RegionEntityTickPlan(RegionThreadingEngine engine, ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.inEntityTickingRange = inEntityTickingRange;
        this.barrier = barrier;
    }

    /**
     * Applies the vanilla per entity predicates to {@code entity} and records it for a region worker tick.
     *
     * @return {@code true} when the plan owns the tick of {@code entity}, {@code false} when the caller has
     *         to tick it on the server thread with the vanilla inline path
     */
    public boolean accept(Entity entity) {
        if (!this.inEntityTickingRange.test(entity)) {
            return false;
        }
        final Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                // Ticked as part of the vehicle stack; the vehicle decides on the region.
                return false;
            }
            entity.stopRiding();
        }
        if (entity.isRemoved() || entity instanceof PartEntity<?>) {
            // The vanilla path still has to run checkDespawn for this entity.
            return false;
        }
        final RegionState region = this.engine.regionOf(entity);
        if (region == null || region.isDead() || !this.passengersShareRegion(entity, region)) {
            return false;
        }
        this.accepted.add(entity);
        return true;
    }

    /**
     * Ticks every accepted entity on its owning region worker and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        try {
            this.engine.tickEntities(this.level, this.accepted, barrier);
        } finally {
            this.accepted.clear();
        }
    }

    private boolean passengersShareRegion(Entity vehicle, RegionState region) {
        for (final Entity passenger : vehicle.getPassengers()) {
            final RegionState passengerRegion = this.engine.regionOf(passenger);
            if (passengerRegion == null || passengerRegion != region || !this.passengersShareRegion(passenger, region)) {
                return false;
            }
        }
        return true;
    }
}
