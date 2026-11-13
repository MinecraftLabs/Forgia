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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * One level tick's natural spawning that is executed on region workers.
 * <p>
 * The batch is the set of chunks the server thread decided may spawn and could hand over, grouped by the region
 * that owns the chunk. A group leases its region and holds the {@code SpawnState} of that region, which is built
 * from the region's own entities but capped by the level-wide mob count of the tick. A region's chunks spawn in the
 * order the level's chunk list collected them, and different regions run in parallel while a region never spawns
 * against itself.
 * <p>
 * A group also carries the entity ownership lease of its region, so a spawn body reaches entity storage through
 * the same grants as an entity tick: the entity it creates is a pending entity whose position writes are field
 * writes, the admission is recorded for the barrier and the barrier runs the unmodified vanilla admission on the
 * server thread. A spawn that lands in the region next to the one that decided it is recorded as well: vanilla
 * picks the position without regard to the region border, and the barrier admits into whichever region stores the
 * entity.
 * <p>
 * The batch also carries the two structures a spawn body writes: the deferred block-write effects of any block a
 * modded spawn hook or a mob's equipment writes (through {@link RegionTickBatch}), and the removals a spawn body
 * could cause. It defers no section updates: a spawn body only positions entities that are not in the level yet.
 * <p>
 * Chunks whose region cannot be leased, whose batch cannot reserve their block-write effects and whose body was
 * truncated by an unavailable chunk are spawned on the server thread after the batch instead of being dropped.
 */
@ApiStatus.Internal
final class RegionSpawnBatch extends RegionTickBatch {
    private final boolean spawnFriendlies;
    private final boolean spawnEnemies;
    /**
     * Whether this tick spawns the persistent categories, i.e. the vanilla
     * {@code gameTime % 400 == 0} flag of {@code ServerChunkCache.tickChunks}.
     */
    private final boolean spawnPersistent;
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Collection<RegionSpawnChunk> fallbackChunks = new ConcurrentLinkedQueue<>();
    /**
     * Scheduled block and fluid ticks a spawn body started: {@code LevelTicks} is server-owned storage, so a
     * worker records the mutation and the publishing server thread applies it after the barrier. Vanilla spawns
     * no scheduled tick, but a modded spawn hook or a {@code finalizeSpawn} can.
     */
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();
    /** Removals a spawn body deferred to the barrier; a {@code finalizeSpawn} may remove an entity. */
    private final RegionDeferredEntityRemovals deferredRemovals = new RegionDeferredEntityRemovals(this);
    /** Admissions a spawn body deferred to the barrier; every mob a chunk spawns is one record. */
    private final RegionDeferredEntityAdditions deferredAdditions = new RegionDeferredEntityAdditions(this);

    RegionSpawnBatch(ServerLevel level, boolean spawnFriendlies, boolean spawnEnemies, boolean spawnPersistent, int blockUpdateEntryBudget) {
        super(level, RegionTickSlice.SPAWN, blockUpdateEntryBudget);
        this.spawnFriendlies = spawnFriendlies;
        this.spawnEnemies = spawnEnemies;
        this.spawnPersistent = spawnPersistent;
    }

    /** {@return whether the friends categories may spawn in this tick} */
    boolean spawnFriendlies() {
        return this.spawnFriendlies;
    }

    /** {@return whether the enemy categories may spawn in this tick} */
    boolean spawnEnemies() {
        return this.spawnEnemies;
    }

    /** {@return whether the persistent categories may spawn in this tick} */
    boolean spawnPersistent() {
        return this.spawnPersistent;
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region       the region whose lease the group holds
     * @param chunks       the chunks the group spawns for
     * @param state        the spawn state of that region, built on the server thread
     * @param neighborhood the per-chunk answers of that region, read on the server thread
     * @param workFactory  builds the worker task; it receives the group so later retries can extend it
     */
    Group addGroup(RegionState region, List<RegionSpawnChunk> chunks, NaturalSpawner.SpawnState state,
            RegionSpawnNeighborhood neighborhood, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Group group = new Group(this, region, chunks, state, neighborhood);
        this.groups.add(group);
        this.addTask("spawn " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records a chunk whose region could not be leased or whose body has to run on the server thread; it spawns
     * there after the batch completed instead of being dropped.
     */
    void addFallback(RegionSpawnChunk chunk) {
        this.fallbackChunks.add(chunk);
    }

    Collection<RegionSpawnChunk> fallbackChunks() {
        return this.fallbackChunks;
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    /** {@return the server-owned scheduled tick mutations the worker groups of this batch deferred} */
    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
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

    /**
     * One region's share of the batch: the region lease, the spawn state of that region, the answers its spawn
     * bodies read and the chunks it spawns for.
     * <p>
     * The group is the ownership lease of the entities stored in its region, exactly like a chunk tick group: a
     * spawn body may query the entities of its region (a zombie looks for a chicken to ride) and reach them
     * through the region-scoped query, and a spawn it admits is admitted by the barrier.
     */
    static final class Group implements RegionOwnershipLeaseGroup {
        private final RegionSpawnBatch batch;
        private final RegionState region;
        private final List<RegionSpawnChunk> chunks = new ArrayList<>();
        private final NaturalSpawner.SpawnState state;
        private final RegionSpawnNeighborhood neighborhood;

        Group(RegionSpawnBatch batch, RegionState region, List<RegionSpawnChunk> chunks, NaturalSpawner.SpawnState state,
                RegionSpawnNeighborhood neighborhood) {
            this.batch = batch;
            this.region = region;
            this.chunks.addAll(chunks);
            this.state = state;
            this.neighborhood = neighborhood;
        }

        RegionSpawnBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        List<RegionSpawnChunk> chunks() {
            return this.chunks;
        }

        void addChunks(List<RegionSpawnChunk> chunks) {
            this.chunks.addAll(chunks);
        }

        /** {@return the spawn state of this region, built on the server thread} */
        NaturalSpawner.SpawnState state() {
            return this.state;
        }

        /** {@return the per-chunk answers this region's spawn bodies read} */
        RegionSpawnNeighborhood neighborhood() {
            return this.neighborhood;
        }

        /**
         * {@return whether this group owns {@code entity}}
         * <p>
         * The spawn bodies of a region reach an entity through the region-scoped query, which only reads the
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
            return !this.batch.isRemovalSeamClosed();
        }

        @Override
        public boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
            return this.batch.deferRemoval(entity, callback, reason);
        }

        @Override
        public boolean acceptsDeferredSectionUpdates() {
            // A spawn body only positions entities that are not in the level yet, so their position writes have
            // no section publication. A move of an entity that is already in the level keeps the storage refusal
            // instead of being recorded for a seam this batch does not carry.
            return false;
        }

        @Override
        public boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
            return false;
        }

        @Override
        public boolean deferAddition(Entity entity, List<Entity> graph) {
            return this.batch.deferAddition(entity, graph);
        }

        @Override
        public boolean deferPortalTransition(Entity entity) {
            // A spawn body admits entities, it never transitions one, so this group has no transition to defer.
            return false;
        }
    }
}
