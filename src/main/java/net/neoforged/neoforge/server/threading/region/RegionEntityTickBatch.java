/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One level entity tick that is executed on region workers.
 * <p>
 * Each group is the unit of region exclusivity and carries the entities that the group is allowed to tick
 * inline. {@link Group#owns(Entity)} only accepts the snapshot members of that group (and their vehicle
 * chain), so work for an entity that belongs to another region is never executed under this group's
 * lease. Such foreign work is queued and executed on the server thread after the batch completed.
 * <p>
 * Ownership changes that happen while a batch owns an entity are recorded as deferred updates and
 * published after the batch finished, so the snapshot region stays authoritative for the duration of the
 * current tick and no new region work can start for the entity before the handoff.
 */
@ApiStatus.Internal
final class RegionEntityTickBatch extends RegionTickBatch {
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Entity> fallbackEntities = new ArrayList<>();
    private final List<Entity> deferredUpdates = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> deferredServerTasks = Collections.synchronizedList(new ArrayList<>());

    RegionEntityTickBatch(ServerLevel level) {
        super(level);
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param entities    the entities the group ticks
     * @param workFactory builds the worker task; it receives the group so it can install the group
     *                    membership in the execution context
     */
    Group addGroup(RegionState region, List<Entity> entities, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Set<Entity> members = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Entity entity : entities) {
            addMembers(members, entity);
        }
        final Group group = new Group(this, region, entities, members);
        this.groups.add(group);
        this.addTask("entity tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records an accepted entity that could not be leased safely; it is ticked on the server thread after
     * the batch completed instead of being dropped.
     */
    void addFallback(Entity entity) {
        this.fallbackEntities.add(entity);
    }

    List<Entity> fallbackEntities() {
        return this.fallbackEntities;
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    boolean owns(Group group, Entity entity) {
        if (group.members().contains(entity)) {
            return true;
        }
        Entity passenger = entity;
        Entity vehicle = entity.getVehicle();
        while (vehicle != null && vehicle.hasPassenger(passenger)) {
            if (group.members().contains(vehicle)) {
                return true;
            }
            passenger = vehicle;
            vehicle = vehicle.getVehicle();
        }
        return false;
    }

    /**
     * Records an ownership change of {@code entity} that must be published after the batch finished.
     */
    void deferEntityUpdate(Entity entity) {
        synchronized (this.deferredUpdates) {
            if (!this.deferredUpdates.contains(entity)) {
                this.deferredUpdates.add(entity);
            }
        }
    }

    /**
     * Drops the deferred ownership changes of {@code entities} that are still waiting for the end of the
     * batch.
     * <p>
     * A recorded transfer commits the authoritative region mapping of the entity while the batch is still
     * running its server-thread post-processing. Publishing the older, deferred update afterwards would
     * overwrite that committed destination with the region of the live position, so the committed decision
     * wins and the pending entry is dropped. A later {@link #deferEntityUpdate(Entity)} remains unaffected.
     */
    void discardDeferredUpdates(Collection<Entity> entities) {
        final Set<Entity> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        discarded.addAll(entities);
        synchronized (this.deferredUpdates) {
            this.deferredUpdates.removeIf(discarded::contains);
        }
    }

    /**
     * Queues work that a worker is not allowed to run inline; it executes on the server thread after the
     * batch completed.
     */
    void queueServerTask(Runnable task) {
        this.deferredServerTasks.add(task);
    }

    /**
     * Runs the work that workers queued because they do not own the entity, on the server thread.
     */
    void runDeferredServerTasks() {
        final List<Runnable> pending;
        synchronized (this.deferredServerTasks) {
            pending = new ArrayList<>(this.deferredServerTasks);
            this.deferredServerTasks.clear();
        }
        for (final Runnable task : pending) {
            task.run();
        }
    }

    /**
     * Publishes the deferred ownership handoffs after the batch finished.
     */
    void applyDeferredEntityUpdates(RegionThreadingEngine engine) {
        final List<Entity> updates;
        synchronized (this.deferredUpdates) {
            updates = new ArrayList<>(this.deferredUpdates);
            this.deferredUpdates.clear();
        }
        for (final Entity entity : updates) {
            if (entity.isRemoved() || !(entity.level() instanceof ServerLevel)) {
                engine.publishEntityRemoval(entity);
            } else {
                engine.publishEntityMove(entity);
            }
        }
    }

    private static void addMembers(Set<Entity> members, Entity entity) {
        if (!members.add(entity)) {
            return;
        }
        for (final Entity passenger : entity.getPassengers()) {
            addMembers(members, passenger);
        }
    }

    /**
     * One region's share of the batch: the region lease, the entities it ticks, and the snapshot members
     * it is allowed to execute inline.
     */
    static final class Group {
        private final RegionEntityTickBatch batch;
        private final RegionState region;
        private final List<Entity> entities = new ArrayList<>();
        private final Set<Entity> members;

        Group(RegionEntityTickBatch batch, RegionState region, List<Entity> entities, Set<Entity> members) {
            this.batch = batch;
            this.region = region;
            this.members = members;
            this.addEntities(entities);
        }

        RegionEntityTickBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        List<Entity> entities() {
            return this.entities;
        }

        Set<Entity> members() {
            return this.members;
        }

        void addEntities(List<Entity> entities) {
            for (final Entity entity : entities) {
                this.entities.add(entity);
                addMembers(this.members, entity);
            }
        }

        boolean owns(Entity entity) {
            return this.batch.owns(this, entity);
        }

        @Nullable
        ServerLevel level() {
            return this.batch.level();
        }
    }
}
