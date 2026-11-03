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
import net.minecraft.world.level.entity.EntityInLevelCallback;
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
 * current tick and no new region work can start for the entity before the handoff. Each deferred update
 * carries the destination region that the deferring thread observed, so the handoff is published to that
 * destination instead of one re-derived from the entity's live position at the barrier.
 * <p>
 * A worker that moves an entity added to its level cannot run the level effects of that move: the
 * {@link EntityInLevelCallback#onMove()} publication of the entity section and the target chunk
 * acquisition are server-owned storage. The worker records one deferred section update per move instead,
 * and the barrier applies both effects on the server thread from the entity's final position.
 * <p>
 * A worker that removes an entity it owns cannot run the level callback of the removal either: it writes the
 * entity's own removal state and records one deferred removal, and the barrier runs the callback on the
 * server thread, so the section membership, the tracking and ticking callbacks, the identity ledger and the
 * region index are written exactly like a server-thread removal. A removal of an entity that is already
 * recorded is a counted no-op, so an entity is never removed twice.
 */
@ApiStatus.Internal
final class RegionEntityTickBatch extends RegionTickBatch {
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Entity> fallbackEntities = new ArrayList<>();
    /**
     * Entities a worker handed back after the chunk source refused a chunk it needed. They are ticked on the
     * server thread after the batch, like the entities whose region could not be leased. The list is filled
     * from the worker threads, so it is synchronized.
     */
    private final List<Entity> chunkFallbackEntities = Collections.synchronizedList(new ArrayList<>());
    /**
     * Ownership changes that a worker deferred to the barrier, keyed by entity identity.
     * <p>
     * The entity is its own key, so recording a deferral is one hash insert instead of a scan of every
     * deferral already recorded in the tick: a later observation of the same entity replaces the entry it
     * finds (the newest observation wins) and a first observation adds one. The map is guarded by its own
     * monitor, is only filled from workers, and is drained and cleared once per tick by the barrier, so it
     * stays bounded by the entities deferred in the current tick.
     * <p>
     * The drain order is the iteration order of an identity hash map, which is unspecified, and no longer
     * the first-defer order of the previous list. Every entry is an independent per-entity decision, so the
     * barrier publishes the same set of updates either way; only the order among distinct entities can
     * differ, and that order is not relied upon. Within one entity, newest wins is preserved.
     */
    private final IdentityHashMap<Entity, DeferredEntityUpdate> deferredUpdates = new IdentityHashMap<>();
    private final List<DeferredSectionUpdate> deferredSectionUpdates = Collections.synchronizedList(new ArrayList<>());
    /** Set when the engine shuts down; guarded by the monitor of {@link #deferredSectionUpdates}. */
    private volatile boolean sectionUpdateSeamClosed;
    /**
     * Removals that a worker recorded for the barrier, keyed by entity identity.
     * <p>
     * The entity is its own key, so a repeat of the same removal is one hash lookup instead of a scan, and
     * the first record is the removal that runs. The map is guarded by its own monitor, is only filled from
     * workers, and is drained and cleared once per tick by the barrier, so it stays bounded by the entities
     * removed in the current tick. Each entry keeps the level callback the inline vanilla removal would have
     * invoked and the removal reason, so the barrier runs the normal removal path on the server thread.
     */
    private final IdentityHashMap<Entity, DeferredEntityRemoval> deferredRemovals = new IdentityHashMap<>();
    /** Removal requests the batch turned into a no-op; guarded by the monitor of {@link #deferredRemovals}. */
    private long repeatedRemovalCount;
    /** Set when the engine shuts down; guarded by the monitor of {@link #deferredRemovals}. */
    private volatile boolean removalSeamClosed;
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

    /**
     * Records an entity that a worker could not tick because the chunk source refused a chunk the tick
     * needed; the server thread ticks it after the batch instead.
     */
    void addChunkFallback(Entity entity) {
        this.chunkFallbackEntities.add(entity);
    }

    /**
     * Takes the entities a worker handed back because of a refused chunk request.
     */
    List<Entity> chunkFallbackEntities() {
        synchronized (this.chunkFallbackEntities) {
            return List.copyOf(this.chunkFallbackEntities);
        }
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
     *
     * @param destination the destination region the deferring thread observed, or {@code null} when the
     *                    entity had already left the server side; the barrier never re-derives it
     */
    void deferEntityUpdate(Entity entity, @Nullable RegionCoordinate destination) {
        final DeferredEntityUpdate update = new DeferredEntityUpdate(entity, destination);
        synchronized (this.deferredUpdates) {
            // The entity is published once; the later observation of it is the newer decision.
            this.deferredUpdates.put(entity, update);
        }
    }

    /**
     * Drops the deferred ownership changes of {@code entities} that are still waiting for the end of the
     * batch.
     * <p>
     * A recorded transfer commits the authoritative region mapping of the entity while the batch is still
     * running its server-thread post-processing. Publishing the older, deferred update afterwards would
     * overwrite that committed destination with the region of the live position, so the committed decision
     * wins and the pending entry is dropped. A later {@link #deferEntityUpdate(Entity, RegionCoordinate)}
     * remains unaffected.
     */
    void discardDeferredUpdates(Collection<Entity> entities) {
        synchronized (this.deferredUpdates) {
            // An identity map matches the recorded key by reference, so no lookup set has to be built and the
            // discard costs one hash removal per named entity instead of a scan of every deferred update.
            for (final Entity entity : entities) {
                this.deferredUpdates.remove(entity);
            }
        }
    }

    /**
     * {@return true when the barrier of this batch will still apply deferred section updates}
     * <p>
     * The section publication and the target chunk acquisition of a worker entity move are server-owned
     * storage, so they may only be deferred to a barrier that still runs. A batch that already completed its
     * barrier, or whose seam was closed because the engine shut down, cannot defer them and keeps the
     * storage refusal instead.
     */
    boolean acceptsDeferredSectionUpdates() {
        return !this.isSectionUpdateSeamClosed() && !this.isComplete();
    }

    /**
     * {@return true when this batch no longer accepts or applies deferred section updates}
     */
    boolean isSectionUpdateSeamClosed() {
        return this.sectionUpdateSeamClosed;
    }

    /**
     * Stops accepting deferred section updates and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier of this batch will never publish a section again, so a
     * worker that still ticks must be refused instead of granted, and the released updates must not keep an
     * entity reference alive.
     *
     * @return the number of released updates, which the caller counts as dropped
     */
    int closeDeferredSectionUpdates() {
        synchronized (this.deferredSectionUpdates) {
            this.sectionUpdateSeamClosed = true;
            final int released = this.deferredSectionUpdates.size();
            this.deferredSectionUpdates.clear();
            return released;
        }
    }

    /**
     * Records the level effects of one entity move that a worker deferred to the barrier.
     * <p>
     * Every move is recorded: the section publication of the barrier is idempotent because it reconciles
     * the membership with the entity's final position, while the target chunk acquisition of each move
     * still runs, exactly like the inline vanilla call the worker skipped.
     *
     * @param entity        the moved entity
     * @param callback      the level callback the inline move would have invoked
     * @param targetSection the section of the position this move reached, captured on the worker
     * @return {@code false} when the seam was closed or the barrier already completed, so the move can never
     *         be published and the caller has to refuse it before it mutates the entity
     */
    boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
        synchronized (this.deferredSectionUpdates) {
            if (this.sectionUpdateSeamClosed || this.isComplete()) {
                return false;
            }
            this.deferredSectionUpdates.add(new DeferredSectionUpdate(entity, callback, targetSection));
            return true;
        }
    }

    /**
     * {@return true when the barrier of this batch will still apply deferred removals}
     * <p>
     * The level callback of a removal is server-owned storage, so it may only be deferred to a barrier that
     * still runs. A batch that already completed its barrier, or whose seam was closed because the engine shut
     * down, cannot defer it and keeps the storage refusal instead of letting a worker remove an entity whose
     * storage removal nobody applies.
     */
    boolean acceptsDeferredRemovals() {
        return !this.isRemovalSeamClosed() && !this.isComplete();
    }

    /**
     * {@return true when this batch no longer accepts or applies deferred removals}
     */
    boolean isRemovalSeamClosed() {
        return this.removalSeamClosed;
    }

    /**
     * Records the level callback of one removal that a worker deferred to the barrier.
     * <p>
     * The first removal of an entity is the one that runs. A repeat - the vanilla entry point removing an
     * entity that a previous removal already marked, which reaches the same funnel again - is a counted no-op,
     * so the entity is never removed twice.
     *
     * @param entity   the entity that is being removed
     * @param callback the level callback the inline vanilla removal would have invoked
     * @param reason   the removal reason the entity is being marked with
     * @return {@code false} when the seam was closed or the barrier already completed, so the removal can
     *         never be applied and the caller has to keep the storage refusal
     */
    boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        synchronized (this.deferredRemovals) {
            if (this.removalSeamClosed || this.isComplete()) {
                return false;
            }
            if (this.deferredRemovals.containsKey(entity)) {
                this.repeatedRemovalCount++;
                return true;
            }
            this.deferredRemovals.put(entity, new DeferredEntityRemoval(entity, callback, reason));
            return true;
        }
    }

    /**
     * Stops accepting deferred removals and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier of this batch will never apply a removal again, so a
     * worker that still ticks must be refused instead of granted, and the released removals must not keep an
     * entity reference alive.
     *
     * @return the released removals and the repeats that were counted but not applied
     */
    RemovalDrain closeDeferredRemovals() {
        synchronized (this.deferredRemovals) {
            this.removalSeamClosed = true;
            return this.drainRemovals();
        }
    }

    /**
     * Takes the deferred removals that wait for the end of the batch.
     */
    RemovalDrain drainDeferredRemovals() {
        synchronized (this.deferredRemovals) {
            return this.drainRemovals();
        }
    }

    private RemovalDrain drainRemovals() {
        final RemovalDrain drain = new RemovalDrain(List.copyOf(this.deferredRemovals.values()), this.repeatedRemovalCount);
        this.deferredRemovals.clear();
        this.repeatedRemovalCount = 0L;
        return drain;
    }

    /**
     * Takes the deferred section updates that wait for the end of the batch.
     */
    List<DeferredSectionUpdate> drainDeferredSectionUpdates() {
        synchronized (this.deferredSectionUpdates) {
            final List<DeferredSectionUpdate> updates = List.copyOf(this.deferredSectionUpdates);
            this.deferredSectionUpdates.clear();
            return updates;
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
     * Takes the deferred ownership handoffs that wait for the end of the batch.
     */
    List<DeferredEntityUpdate> drainDeferredUpdates() {
        synchronized (this.deferredUpdates) {
            final List<DeferredEntityUpdate> updates = List.copyOf(this.deferredUpdates.values());
            this.deferredUpdates.clear();
            return updates;
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
     * One deferred ownership change: the entity and the destination region that the deferring thread
     * observed. The destination is captured once and never re-derived at the batch barrier.
     */
    record DeferredEntityUpdate(Entity entity, @Nullable RegionCoordinate destination) {}

    /**
     * One deferred section update: the moved entity, the level callback the inline move would have invoked
     * and the section of the position the move reached. The section carries the target chunk acquisition as
     * well, because a section coordinate is a chunk coordinate.
     */
    record DeferredSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {}

    /**
     * One deferred removal: the removed entity, the level callback the inline vanilla removal would have
     * invoked and the removal reason the entity was marked with. The barrier runs that callback on the server
     * thread, which is the whole server-owned part of the removal.
     */
    record DeferredEntityRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {}

    /**
     * One drain of the deferred removals of a batch: the removals to apply and the removal requests that were
     * already recorded or already applied and were therefore counted as no-ops.
     */
    record RemovalDrain(List<DeferredEntityRemoval> removals, long repeats) {}

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
