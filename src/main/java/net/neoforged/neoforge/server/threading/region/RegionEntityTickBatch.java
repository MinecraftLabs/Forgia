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
import net.minecraft.util.RandomSource;
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
 * <p>
 * A worker that admits an entity into its level records one deferred admission per riding graph instead of
 * running the vanilla admission, and the barrier runs the unmodified {@code ServerLevel.addEntity} for every
 * member of the graph on the server thread. The record therefore carries the whole self-and-passenger graph,
 * which is admitted whole or counted as dropped; the members the caller adds one by one (as
 * {@code addFreshEntityWithPassengers} does) are no-ops of the graph that already carries them.
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
     * The record is shared with the block-entity tick batch, which defers the removal of an item entity a
     * hopper absorbed: both slices apply the level callback of a removal on the server thread and count the
     * same repeats.
     */
    private final RegionDeferredEntityRemovals deferredRemovals = new RegionDeferredEntityRemovals(this);
    /**
     * Admissions that a worker recorded for the barrier, in record order.
     * <p>
     * One entry is one riding graph, because an entity and its passengers are one admission: the barrier
     * admits the members in vanilla self-then-passengers order, so a vehicle is in the level before its
     * passengers. The list is guarded by its own monitor, is only filled from workers, and is drained and
     * cleared once per tick by the barrier, so it stays bounded by the graphs admitted in the current tick.
     */
    private final List<DeferredEntityAddition> deferredAdditions = new ArrayList<>();
    /**
     * Every entity of an already recorded admission, by identity. It makes the per-entity calls of
     * {@code addFreshEntityWithPassengers} no-ops of the graph that already carries them instead of a second
     * record; guarded by the monitor of {@link #deferredAdditions}.
     */
    private final Set<Entity> recordedAdditionMembers = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Admissions this batch recorded; guarded by the monitor of {@link #deferredAdditions}. */
    private long recordedAdditionCount;
    /** Set when the engine shuts down; guarded by the monitor of {@link #deferredAdditions}. */
    private volatile boolean additionSeamClosed;
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
        // The random source is created here, inside the lease release path of the caller, so a failure
        // while building it cannot leave the region TICKING without a worker that releases it.
        final Group group = new Group(this, region, entities, members, this.groupRandomSeed(region.coordinate()));
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
        return this.deferredRemovals.accepts();
    }

    /**
     * {@return true when this batch no longer accepts or applies deferred removals}
     */
    boolean isRemovalSeamClosed() {
        return this.deferredRemovals.isSeamClosed();
    }

    /**
     * Records the level callback of one removal that a worker deferred to the barrier.
     *
     * @param entity   the entity that is being removed
     * @param callback the level callback the inline vanilla removal would have invoked
     * @param reason   the removal reason the entity is being marked with
     * @return {@code false} when the seam was closed or the barrier already completed, so the removal can
     *         never be applied and the caller has to keep the storage refusal
     */
    boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        return this.deferredRemovals.defer(entity, callback, reason);
    }

    /**
     * Stops accepting deferred removals and releases the ones that already wait.
     *
     * @return the released removals and the repeats that were counted but not applied
     */
    RegionDeferredEntityRemovals.Drain closeDeferredRemovals() {
        return this.deferredRemovals.close();
    }

    /**
     * Takes the deferred removals that wait for the end of the batch.
     */
    RegionDeferredEntityRemovals.Drain drainDeferredRemovals() {
        return this.deferredRemovals.drain();
    }

    /**
     * {@return true when this batch no longer accepts or applies deferred admissions}
     * <p>
     * A batch that already completed its barrier, or whose seam was closed because the engine shut down,
     * cannot admit an entity, so {@link #deferAddition(Entity, List)} refuses and the worker keeps the
     * storage refusal. The position writes of the pending entity stay granted: they are field writes only, and
     * refusing them would abort the constructor at an arbitrary point instead of at the admission.
     */
    boolean isAdditionSeamClosed() {
        return this.additionSeamClosed;
    }

    /**
     * Records the admission of one riding graph that a worker deferred to the barrier.
     * <p>
     * The graph is admitted whole or counted as dropped, so it is recorded with every member and in vanilla
     * self-then-passengers order. A member that an earlier record of the same batch already carries is a
     * no-op: {@code addFreshEntityWithPassengers} adds the members one by one, and the first record already
     * carries the whole graph.
     *
     * @param entity the entity the caller is adding, the root of the recorded graph
     * @param graph  the root and its indirect passengers, in the order they have to be admitted
     * @return {@code false} when the seam was closed or the barrier already completed, so the admission can
     *         never be applied and the caller has to keep the storage refusal
     */
    boolean deferAddition(Entity entity, List<Entity> graph) {
        synchronized (this.deferredAdditions) {
            if (this.additionSeamClosed || this.isComplete()) {
                return false;
            }
            if (this.recordedAdditionMembers.contains(entity)) {
                return true;
            }
            this.deferredAdditions.add(new DeferredEntityAddition(entity, List.copyOf(graph)));
            this.recordedAdditionMembers.addAll(graph);
            this.recordedAdditionCount++;
            return true;
        }
    }

    /**
     * Stops accepting deferred admissions and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier of this batch will never admit an entity again, so a
     * worker that still ticks must be refused instead of granted, and the released records must not keep an
     * entity reference alive.
     *
     * @return the released admissions and the records this batch accepted
     */
    AdditionDrain closeDeferredAdditions() {
        synchronized (this.deferredAdditions) {
            this.additionSeamClosed = true;
            return this.drainAdditions();
        }
    }

    /**
     * Takes the deferred admissions that wait for the end of the batch.
     */
    AdditionDrain drainDeferredAdditions() {
        synchronized (this.deferredAdditions) {
            return this.drainAdditions();
        }
    }

    private AdditionDrain drainAdditions() {
        final AdditionDrain drain = new AdditionDrain(List.copyOf(this.deferredAdditions), this.recordedAdditionCount);
        this.deferredAdditions.clear();
        this.recordedAdditionMembers.clear();
        this.recordedAdditionCount = 0L;
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
     * One deferred admission: the entity the worker added and the whole riding graph it belongs to, in the
     * order the barrier admits it. The root is kept for diagnostics; the graph is what the barrier admits.
     */
    record DeferredEntityAddition(Entity root, List<Entity> graph) {}

    /**
     * One drain of the deferred admissions of a batch: the admissions to apply and the number of records the
     * batch accepted, which the caller counts as recorded.
     */
    record AdditionDrain(List<DeferredEntityAddition> additions, long recorded) {}

    /**
     * One region's share of the batch: the region lease, the entities it ticks, the snapshot members it is
     * allowed to execute inline, and the random source its entity ticks draw from.
     */
    static final class Group implements RegionOwnershipLeaseGroup {
        private final RegionEntityTickBatch batch;
        private final RegionState region;
        private final List<Entity> entities = new ArrayList<>();
        private final Set<Entity> members;
        private final RandomSource random;

        Group(RegionEntityTickBatch batch, RegionState region, List<Entity> entities, Set<Entity> members, long randomSeed) {
            this.batch = batch;
            this.region = region;
            this.members = members;
            this.random = RandomSource.create(randomSeed);
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

        /**
         * {@return the random source every entity of this group draws from while the group runs}
         * <p>
         * The engine installs this one instance for the whole worker tick loop, so a direct
         * {@code level.random} read of any entity of the group resolves it through the level random
         * wrapper. It is derived in {@link RegionTickBatch#groupRandomSeed(RegionCoordinate)} and never
         * shared with another group.
         */
        RandomSource random() {
            return this.random;
        }

        void addEntities(List<Entity> entities) {
            for (final Entity entity : entities) {
                this.entities.add(entity);
                addMembers(this.members, entity);
            }
        }

        @Override
        public boolean owns(Entity entity) {
            return this.batch.owns(this, entity);
        }

        @Override
        public boolean acceptsDeferredRemovals() {
            return this.batch.acceptsDeferredRemovals();
        }

        @Override
        public boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
            return this.batch.deferRemoval(entity, callback, reason);
        }

        @Override
        public boolean acceptsDeferredSectionUpdates() {
            return this.batch.acceptsDeferredSectionUpdates();
        }

        @Override
        public boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
            return this.batch.deferSectionUpdate(entity, callback, targetSection);
        }

        @Override
        public boolean deferAddition(Entity entity, List<Entity> graph) {
            return this.batch.deferAddition(entity, graph);
        }

        @Nullable
        ServerLevel level() {
            return this.batch.level();
        }
    }
}
