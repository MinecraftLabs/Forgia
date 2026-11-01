/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
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
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One level tick's selected random ticks that are executed on region workers.
 * <p>
 * The ticks are the callbacks the server thread selected while it traversed the randomly ticking
 * sections of the ticking chunks. Each group owns the lease of exactly one region and executes the
 * snapshot ticks of that region in their original order, so different regions run in parallel while a
 * region never ticks against itself. Ticks whose region cannot be leased safely, and the ticks of chunks
 * that are not surrounded by chunks of the same region, are executed on the server thread after the
 * batch instead of being dropped.
 * <p>
 * Every group owns its own {@link RandomSource}, derived from one server-thread seed source per batch. A
 * worker therefore never draws from the shared level random, and the sequence a region observes only
 * depends on the region order of this batch.
 * <p>
 * Random tick callbacks can schedule block or fluid ticks and mutate blocks in neighbouring chunks. The
 * server-owned mutation queue collects every {@code LevelTicks} mutation so it is applied on the server
 * thread after the batch, and entity queries are handed to the server thread by the shared
 * {@link RegionTickBatch} lifecycle.
 */
@ApiStatus.Internal
final class RegionRandomTickBatch extends RegionTickBatch {
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<RegionRandomTick> fallbackTicks = new ArrayList<>();
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();
    @Nullable
    private RandomSource seedSource;

    RegionRandomTickBatch(ServerLevel level, List<RegionRandomTick> chunkFallbackTicks) {
        super(level);
        this.fallbackTicks.addAll(chunkFallbackTicks);
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param ticks       the random ticks the group executes
     * @param randomSeed  the seed of the group's own random source
     * @param workFactory builds the worker task; it receives the group so later retries can extend it
     */
    Group addGroup(RegionState region, List<RegionRandomTick> ticks, long randomSeed, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Group group = new Group(this, region, ticks, randomSeed);
        this.groups.add(group);
        this.addTask("random tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records ticks that could not be leased safely; they are executed on the server thread after the
     * batch completed instead of being dropped.
     */
    void addFallback(List<RegionRandomTick> ticks) {
        this.fallbackTicks.addAll(ticks);
    }

    List<RegionRandomTick> fallbackTicks() {
        return this.fallbackTicks;
    }

    /**
     * {@return the server-owned queue workers enqueue {@code LevelTicks} mutations in}
     * <p>
     * A random tick callback can schedule block or fluid ticks, so both {@code LevelTicks} instances of
     * the level share this queue. The server thread drains it after the batch, before the level tick
     * continues.
     */
    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    /**
     * {@return the next per-group random seed, derived on the server thread from one batch seed}
     */
    long nextRandomSeed() {
        if (this.seedSource == null) {
            // One draw from the level random per batch keeps the impact on the vanilla random sequence
            // minimal while every group still gets a distinct, deterministic seed.
            this.seedSource = RandomSource.create(this.level().random.nextLong());
        }
        return this.seedSource.nextLong();
    }

    /**
     * One region's share of the batch: the region lease, the ticks it executes in snapshot order, and the
     * random source its callbacks draw from.
     */
    static final class Group {
        private final RegionRandomTickBatch batch;
        private final RegionState region;
        private final List<RegionRandomTick> ticks = new ArrayList<>();
        private final RandomSource random;

        Group(RegionRandomTickBatch batch, RegionState region, List<RegionRandomTick> ticks, long randomSeed) {
            this.batch = batch;
            this.region = region;
            this.ticks.addAll(ticks);
            this.random = RandomSource.create(randomSeed);
        }

        RegionRandomTickBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        ServerLevel level() {
            return this.batch.level();
        }

        List<RegionRandomTick> ticks() {
            return this.ticks;
        }

        RandomSource random() {
            return this.random;
        }

        void addTicks(List<RegionRandomTick> ticks) {
            this.ticks.addAll(ticks);
        }
    }
}
