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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * One level block-entity tick that is executed on region workers.
 * <p>
 * The batch is the set of chunks the plan handed over, each with the tickers it admitted. Each group owns the
 * lease of exactly one region and ticks the chunks of that region, whose tickers it merges back into the
 * order of the level's own ticker list, so different regions run in parallel while a region never ticks
 * against itself. Chunks whose region cannot be leased safely, and chunks whose snapshot went stale before
 * the worker ticked them, are ticked on the server thread after the batch instead of being dropped.
 * <p>
 * A group also carries the entity ownership lease of its region, so a ticker whose compatibility declaration
 * admits entity work reaches the entities of its own region through the region-scoped query and its mutations
 * of them go through the same grants as an entity tick group. The lease is only opened for the tickers of
 * that group and closed before the region lease, and it defers the server-owned storage effect of a granted
 * removal to the barrier of this batch: a hopper that absorbs an item entity of its region removes the
 * entity's own state on the worker and the barrier runs the level callback on the server thread.
 * <p>
 * The batch also carries the two server-owned structures a block-entity tick can reach through a neighbor
 * update: the deferred mutations of {@code LevelTicks} and the removal records of the ownership lease. A ticker
 * that writes a block - its own state, the container it reads - records the light recheck and the block update
 * publication of the write in the deferred queue every region batch shares, so the worker never drives the
 * level-wide light engine or the level-wide block update publication. A ticker whose reservation does not fit
 * into that queue is refused before its body and ticked on the server thread after the batch.
 */
@ApiStatus.Internal
final class RegionBlockEntityTickBatch extends RegionTickBatch {
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Collection<RegionBlockEntityTickChunk> fallbackChunks = new ConcurrentLinkedQueue<>();
    private final Collection<RegionBlockEntityTickAdmission> fallbackTickers = new ConcurrentLinkedQueue<>();
    /**
     * Scheduled block and fluid ticks a worker could not schedule inline, because {@code LevelTicks} is
     * server-owned storage. A block-entity tick reaches them through the neighbor update of its own
     * {@code setChanged()}: a comparator or a diode next to the ticker re-evaluates its output and schedules
     * the delay of its next tick.
     */
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();
    /**
     * Removals a worker deferred to the barrier, shared with the entity tick batch: both slices grant the
     * removal of an entity they own and let the barrier run its level callback on the server thread.
     */
    private final RegionDeferredEntityRemovals deferredRemovals = new RegionDeferredEntityRemovals(this);
    /**
     * Slowest group of this batch. The server thread waits for the whole batch, so the slowest group is the
     * part of the wait the workers caused; the difference to the wait is dispatch and completion. It is only
     * written while the opt-in attribution of the slice is enabled.
     */
    private final java.util.concurrent.atomic.AtomicLong slowestGroupNanos = new java.util.concurrent.atomic.AtomicLong();
    /**
     * First group start and last group end of this batch, so the server-thread wait splits into the time until
     * the first worker picked up work, the span the workers ran in and the time until the wait noticed that the
     * last group finished. Only written while the opt-in attribution of the slice is enabled.
     */
    private final java.util.concurrent.atomic.AtomicLong firstGroupStartNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastGroupEndNanos = new java.util.concurrent.atomic.AtomicLong();

    RegionBlockEntityTickBatch(ServerLevel level, int blockUpdateEntryBudget) {
        super(level, RegionTickSlice.BLOCK_ENTITY_TICK, blockUpdateEntryBudget);
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param chunks      the block-entity chunks the group ticks
     * @param workFactory builds the worker task; it receives the group so later retries can extend it
     */
    Group addGroup(RegionState region, List<RegionBlockEntityTickChunk> chunks, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        // The random source is created here, inside the lease release path of the caller, so a failure
        // while building it cannot leave the region TICKING without a worker that releases it.
        final Group group = new Group(this, region, chunks, this.groupRandomSeed(region.coordinate()));
        this.groups.add(group);
        this.addTask("block entity tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records a chunk whose tickers could not be leased safely or whose snapshot went stale before the
     * worker ticked it; its tickers are ticked on the server thread after the batch instead of being dropped.
     */
    void addFallbackChunk(RegionBlockEntityTickChunk chunk) {
        this.fallbackChunks.add(chunk);
    }

    Collection<RegionBlockEntityTickChunk> fallbackChunks() {
        return this.fallbackChunks;
    }

    /**
     * Records a ticker that could not be leased safely; it is ticked on the server thread after the batch
     * completed instead of being dropped.
     */
    void addFallback(RegionBlockEntityTickAdmission ticker) {
        this.fallbackTickers.add(ticker);
    }

    Collection<RegionBlockEntityTickAdmission> fallbackTickers() {
        return this.fallbackTickers;
    }

    /**
     * Records the wall time of one finished group, keeping the slowest one.
     */
    void recordGroupDuration(long durationNanos) {
        this.slowestGroupNanos.accumulateAndGet(durationNanos, Math::max);
    }

    /** {@return the wall time of the slowest group of this batch, or zero when no group reported one} */
    long slowestGroupNanos() {
        return this.slowestGroupNanos.get();
    }

    /**
     * Records the start of one group of this batch, keeping the first one.
     */
    void recordGroupStart(long startNanos) {
        this.firstGroupStartNanos.compareAndSet(0L, startNanos);
    }

    /**
     * Records the end of one group of this batch, keeping the last one.
     */
    void recordGroupEnd(long endNanos) {
        this.lastGroupEndNanos.accumulateAndGet(endNanos, Math::max);
    }

    /** {@return the start of the first group of this batch, or zero when no group reported one} */
    long firstGroupStartNanos() {
        return this.firstGroupStartNanos.get();
    }

    /** {@return the end of the last group of this batch, or zero when no group reported one} */
    long lastGroupEndNanos() {
        return this.lastGroupEndNanos.get();
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    /**
     * {@return the server-owned scheduled tick mutations the worker groups of this batch deferred}
     * <p>
     * The publishing server thread drains the queue after the batch, exactly like it drains the queue of a
     * scheduled or random tick batch, so no worker mutates {@code LevelTicks}.
     */
    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
    }

    /**
     * {@return true when the barrier of this batch will still apply deferred removals}
     *
     * @see RegionDeferredEntityRemovals#accepts()
     */
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

    /**
     * Stops accepting deferred removals and releases the ones that already wait.
     *
     * @return the released removals and the repeats that were counted but not applied
     */
    RegionDeferredEntityRemovals.Drain closeDeferredRemovals() {
        return this.deferredRemovals.close();
    }

    /** Takes the deferred removals that wait for the end of the batch. */
    RegionDeferredEntityRemovals.Drain drainDeferredRemovals() {
        return this.deferredRemovals.drain();
    }

    /**
     * One region's share of the batch: the region lease, the chunks it ticks and the random source their
     * tickers draw from.
     * <p>
     * The group is also the ownership lease of the block entities of that region: a ticker of this group owns
     * every entity stored in the leased region, because the region-scoped query only returns the entities of
     * that storage. It defers removals only - a vanilla block-entity tick neither moves nor admits an entity -
     * and refuses the other two server-owned effects, so a ticker that tries them keeps the storage refusal.
     */
    static final class Group implements RegionOwnershipLeaseGroup {
        private final RegionBlockEntityTickBatch batch;
        private final RegionState region;
        private final List<RegionBlockEntityTickChunk> chunks = new ArrayList<>();
        private final RandomSource random;

        Group(RegionBlockEntityTickBatch batch, RegionState region, List<RegionBlockEntityTickChunk> chunks, long randomSeed) {
            this.batch = batch;
            this.region = region;
            this.random = RandomSource.create(randomSeed);
            this.chunks.addAll(chunks);
        }

        RegionBlockEntityTickBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        List<RegionBlockEntityTickChunk> chunks() {
            return this.chunks;
        }

        void addChunks(List<RegionBlockEntityTickChunk> chunks) {
            this.chunks.addAll(chunks);
        }

        /**
         * {@return the random source every ticker of this group draws from while the group runs}
         * <p>
         * The engine installs this one instance for the whole worker tick loop, so a direct
         * {@code level.random} read of a ticker resolves it through the level random wrapper instead of the
         * shared vanilla source. It is derived in {@link RegionTickBatch#groupRandomSeed(RegionCoordinate)}
         * and never shared with another group.
         */
        RandomSource random() {
            return this.random;
        }

        /**
         * {@return whether this group owns {@code entity}}
         * <p>
         * The block entities of a region reach an entity through the region-scoped query, which only reads the
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
            return false;
        }

        @Override
        public boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
            return false;
        }

        @Override
        public boolean deferAddition(Entity entity, List<Entity> graph) {
            return false;
        }

        @Override
        public boolean deferPortalTransition(Entity entity) {
            // A block-entity tick never transitions an entity, so this group has no transition to defer.
            return false;
        }
    }
}
