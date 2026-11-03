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
 * The entity tick of one level tick.
 * <p>
 * The plan has two modes. In the self-driven mode, which is the production mode, the server thread does not
 * collect entities at all: it activates the level's regions, the region batch leases them and submits one
 * worker task per region, and each worker enumerates the entity tick index of the region it leased. The
 * {@code ServerLevel} entity pass therefore calls neither this plan nor the level's entity tick list per
 * entity; see {@link RegionThreadingEngine#tickSelfDrivenEntities}.
 * <p>
 * The per-entity mode is the fallback for a level whose region entity tick index cannot be seeded from the
 * level's authoritative tick list (a level without a tick list, or a retiring identity). It is the original
 * plan: {@link #accept(Entity)} runs the vanilla inclusion, range and vehicle predicates on the server thread
 * and records the entity, the server thread keeps iterating the level entity tick list while it collects, and
 * only the collected entities are submitted once the iteration is over. Anything the plan does not accept is
 * ticked inline by the vanilla loop.
 */
@ApiStatus.Internal
public final class RegionEntityTickPlan {
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final Predicate<Entity> inEntityTickingRange;
    private final RegionTickBarrier barrier;
    private final List<Entity> accepted = new ArrayList<>();
    /** {@code true} when the region workers enumerate their own region's entities. */
    private final boolean selfDriven;

    RegionEntityTickPlan(RegionThreadingEngine engine, ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier, boolean selfDriven) {
        this.engine = engine;
        this.level = level;
        this.inEntityTickingRange = inEntityTickingRange;
        this.barrier = barrier;
        this.selfDriven = selfDriven;
    }

    /**
     * {@return the plan of a level whose regions drive their own entity tick}
     * <p>
     * The range predicate is not used by this mode: the activation of each region evaluates the vanilla
     * entity ticking range per chunk of the region on the server thread, before the region is leased.
     */
    static RegionEntityTickPlan selfDriven(RegionThreadingEngine engine, ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        return new RegionEntityTickPlan(engine, level, inEntityTickingRange, barrier, true);
    }

    /**
     * {@return true when the region workers enumerate and tick the entities of their own region}
     * <p>
     * When this is {@code true} the level entity pass must not iterate its entity tick list: the entities are
     * already indexed per region, and walking the list would tick them a second time.
     */
    public boolean ticksEntitiesOnRegionWorkers() {
        return this.selfDriven;
    }

    /**
     * Applies the vanilla per entity predicates to {@code entity} and records it for a region worker tick.
     *
     * @return {@code true} when the plan owns the tick of {@code entity}, {@code false} when the caller has
     *         to tick it on the server thread with the vanilla inline path
     */
    public boolean accept(Entity entity) {
        if (this.selfDriven) {
            // The self-driven pass does not walk the level's entity tick list, so nothing reaches this
            // method. An entity that does stays with the vanilla inline body instead of being dropped.
            return false;
        }
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
        final RegionState region = this.engine.regionFor(entity);
        if (region == null) {
            // The entity is not tracked yet. Only this first observation has to publish the mapping; every
            // later tick groups with the read-only lookup, because the entity's own tick (or the recorded
            // transfer) keeps the authoritative mapping current.
            final RegionState created = this.engine.regionOf(entity);
            if (created == null || created.isDead() || !this.passengersShareRegion(entity, created)) {
                return false;
            }
            this.accepted.add(entity);
            return true;
        }
        if (region.isDead() || !this.passengersShareRegion(entity, region)) {
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
        // The attribution of the slice closes its plan segment where the server-thread collection of this mode
        // ends: after the per-entity collection of the fallback mode, and inside the activation of the
        // self-driven mode.
        if (!this.selfDriven) {
            RegionEntityTickAttribution.endPlanSegment();
        }
        try {
            if (this.selfDriven) {
                this.engine.tickSelfDrivenEntities(this.level, barrier);
            } else {
                this.engine.tickEntities(this.level, this.accepted, barrier);
            }
        } finally {
            this.accepted.clear();
        }
    }

    private boolean passengersShareRegion(Entity vehicle, RegionState region) {
        for (final Entity passenger : vehicle.getPassengers()) {
            RegionState passengerRegion = this.engine.regionFor(passenger);
            if (passengerRegion == null) {
                // A passenger that never ticked on its own is not tracked yet. Like the vehicle, it is the one
                // observation that has to publish the mapping; every later tick reads it.
                passengerRegion = this.engine.regionOf(passenger);
            }
            if (passengerRegion == null || passengerRegion != region || !this.passengersShareRegion(passenger, region)) {
                return false;
            }
        }
        return true;
    }
}
