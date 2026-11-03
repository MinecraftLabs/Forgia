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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * One level tick's chunk ticks that are executed on region workers.
 * <p>
 * The batch is the set of chunks the server thread decided to tick and could hand over, each with its own
 * random source. Each group owns the lease of exactly one region and ticks the chunks of that region in the
 * order the level's chunk list collected them, so different regions run in parallel while a region never ticks
 * against itself. Chunks whose region cannot be leased safely are ticked on the server thread after the batch
 * instead of being dropped.
 * <p>
 * A group also carries the entity ownership lease of its region, so a random tick that queries the entities of
 * its region, spawns one or moves one reaches them through the same grants as an entity tick: the region-scoped
 * query answers from the physical bucket of the lease, an admission is recorded for the barrier and a move
 * records its section publication for the barrier. A grant is only given when the batch can still apply the
 * deferred effect, so a batch that already finished refuses it and the worker keeps the storage refusal.
 * <p>
 * The batch also carries the server-owned structures a chunk tick can reach: the deferred mutations of
 * {@code LevelTicks} (a random tick that schedules a block or fluid tick), the lightning strikes a worker
 * may not resolve (the strike target comes from the POI manager and from entity storage, and it can be in
 * another region) and the light rechecks and block update publications of the block writes a worker performed
 * (the light engine and the block update publication are one structure per level, not one per region). All of
 * them are applied by the publishing server thread after the barrier, and the light rechecks and block updates
 * of a write go through the deferred queue every region batch shares, in recording order.
 */
@ApiStatus.Internal
final class RegionChunkTickBatch extends RegionTickBatch {
    private final int randomTickSpeed;
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Collection<RegionChunkTickEntry> fallbackChunks = new ConcurrentLinkedQueue<>();
    /**
     * Scheduled block and fluid ticks a random tick started: {@code LevelTicks} is server-owned storage, so a
     * worker records the mutation and the publishing server thread applies it after the barrier.
     */
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();
    /**
     * Removals a worker deferred to the barrier, shared with the entity and block-entity tick batches: every
     * slice that grants the removal of an entity it owns lets the barrier run its level callback.
     */
    private final RegionDeferredEntityRemovals deferredRemovals = new RegionDeferredEntityRemovals(this);
    /** Admissions a worker deferred to the barrier; a random tick can hatch or otherwise spawn an entity. */
    private final RegionDeferredEntityAdditions deferredAdditions = new RegionDeferredEntityAdditions(this);
    /** Section publications of the moves a worker deferred to the barrier; a snow layer pushes entities up. */
    private final RegionDeferredEntitySectionUpdates deferredSectionUpdates = new RegionDeferredEntitySectionUpdates(this);
    /**
     * The positions of the lightning strikes the workers rolled. Resolving a strike reads the POI manager and
     * entity storage and can place the bolt in another region, so the server thread resolves it after the
     * barrier.
     */
    private final Queue<BlockPos> lightningStrikes = new ConcurrentLinkedQueue<>();

    RegionChunkTickBatch(ServerLevel level, int randomTickSpeed, int blockUpdateEntryBudget) {
        super(level, RegionTickSlice.CHUNK_TICK, blockUpdateEntryBudget);
        this.randomTickSpeed = randomTickSpeed;
    }

    /** {@return the random tick speed every chunk of this batch ticks with} */
    int randomTickSpeed() {
        return this.randomTickSpeed;
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param chunks      the chunks the group ticks
     * @param workFactory builds the worker task; it receives the group so later retries can extend it
     */
    Group addGroup(RegionState region, List<RegionChunkTickEntry> chunks, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Group group = new Group(this, region, chunks);
        this.groups.add(group);
        this.addTask("chunk tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records a chunk whose region could not be leased safely; it is ticked on the server thread after the
     * batch completed instead of being dropped.
     */
    void addFallback(RegionChunkTickEntry chunk) {
        this.fallbackChunks.add(chunk);
    }

    Collection<RegionChunkTickEntry> fallbackChunks() {
        return this.fallbackChunks;
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    /**
     * {@return the server-owned scheduled tick mutations the worker groups of this batch deferred}
     */
    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
    }

    /** {@return the lightning strikes the workers rolled, in the order they were rolled} */
    Queue<BlockPos> lightningStrikes() {
        return this.lightningStrikes;
    }

    /**
     * Records the position a worker rolled for a lightning strike; the server thread resolves and applies it
     * after the batch.
     */
    void addLightningStrike(BlockPos rolledPos) {
        this.lightningStrikes.add(rolledPos);
    }

    /** {@return true when the barrier of this batch will still apply deferred removals} */
    boolean acceptsDeferredRemovals() {
        return this.deferredRemovals.accepts();
    }

    /** {@return true when this batch no longer accepts or applies deferred removals} */
    boolean isRemovalSeamClosed() {
        return this.deferredRemovals.isSeamClosed();
    }

    /**
     * Records the level callback of one removal that a worker deferred to the barrier.
     *
     * @return {@code false} when the seam was closed or the barrier already completed, so the caller has to
     *         keep the storage refusal
     */
    boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        return this.deferredRemovals.defer(entity, callback, reason);
    }

    /** Stops accepting deferred removals and releases the ones that already wait. */
    RegionDeferredEntityRemovals.Drain closeDeferredRemovals() {
        return this.deferredRemovals.close();
    }

    /** Takes the deferred removals that wait for the end of the batch. */
    RegionDeferredEntityRemovals.Drain drainDeferredRemovals() {
        return this.deferredRemovals.drain();
    }

    /** {@return true when this batch no longer accepts or applies deferred admissions} */
    boolean isAdditionSeamClosed() {
        return this.deferredAdditions.isSeamClosed();
    }

    /**
     * Records the admission of one riding graph that a worker deferred to the barrier.
     *
     * @return {@code false} when the seam was closed or the barrier already completed, so the caller has to
     *         keep the storage refusal
     */
    boolean deferAddition(Entity entity, List<Entity> graph) {
        return this.deferredAdditions.defer(entity, graph);
    }

    /** Stops accepting deferred admissions and releases the ones that already wait. */
    RegionDeferredEntityAdditions.Drain closeDeferredAdditions() {
        return this.deferredAdditions.close();
    }

    /** Takes the deferred admissions that wait for the end of the batch. */
    RegionDeferredEntityAdditions.Drain drainDeferredAdditions() {
        return this.deferredAdditions.drain();
    }

    /** {@return true when the barrier of this batch will still apply deferred section updates} */
    boolean acceptsDeferredSectionUpdates() {
        return this.deferredSectionUpdates.accepts();
    }

    /** {@return true when this batch no longer accepts or applies deferred section updates} */
    boolean isSectionUpdateSeamClosed() {
        return this.deferredSectionUpdates.isSeamClosed();
    }

    /**
     * Records the level effects of one entity move that a worker deferred to the barrier.
     *
     * @return {@code false} when the seam was closed or the barrier already completed, so the caller has to
     *         refuse the move before it mutates the entity
     */
    boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
        return this.deferredSectionUpdates.defer(entity, callback, targetSection);
    }

    /** Stops accepting deferred section updates and releases the ones that already wait. */
    int closeDeferredSectionUpdates() {
        return this.deferredSectionUpdates.close();
    }

    /** Takes the deferred section updates that wait for the end of the batch. */
    List<RegionDeferredEntitySectionUpdates.DeferredSectionUpdate> drainDeferredSectionUpdates() {
        return this.deferredSectionUpdates.drain();
    }

    /**
     * One region's share of the batch: the region lease, the chunks it ticks and the server-owned effects it
     * defers to the barrier.
     * <p>
     * The group is the ownership lease of the entities stored in its region: a random tick of one of its chunks
     * reaches an entity only through the region-scoped query, which reads the physical bucket of the leased
     * region. It defers every server-owned storage effect a chunk tick can reach, because a random tick can
     * spawn an entity, move one and remove one.
     */
    static final class Group implements RegionOwnershipLeaseGroup {
        private final RegionChunkTickBatch batch;
        private final RegionState region;
        private final List<RegionChunkTickEntry> chunks = new ArrayList<>();

        Group(RegionChunkTickBatch batch, RegionState region, List<RegionChunkTickEntry> chunks) {
            this.batch = batch;
            this.region = region;
            this.chunks.addAll(chunks);
        }

        RegionChunkTickBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        List<RegionChunkTickEntry> chunks() {
            return this.chunks;
        }

        void addChunks(List<RegionChunkTickEntry> chunks) {
            this.chunks.addAll(chunks);
        }

        /**
         * {@return whether this group owns {@code entity}}
         * <p>
         * The chunk ticks of a region reach an entity through the region-scoped query, which only reads the
         * physical section bucket of the leased region. The group therefore owns the entities stored in that
         * bucket, and the query result and the ownership decision agree by construction.
         */
        @Override
        public boolean owns(Entity entity) {
            return entity.level() == this.batch.level()
                    && RegionEntityOwnershipScope.isInsideRegion(this.region.coordinate(), this.batch.level(), entity.blockPosition());
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
    }
}
