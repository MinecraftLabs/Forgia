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
import net.minecraft.world.ticks.ScheduledTick;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One scheduled block or fluid tick phase that is executed on region workers.
 * <p>
 * The ticks are the snapshot {@code LevelTicks} selected for this tick. Each group owns the lease of
 * exactly one region and executes the snapshot ticks of that region in their original order, so different
 * regions run in parallel while a region never ticks against itself. Every tick is reported to the
 * {@code LevelTicks} per-tick hook immediately before its callback runs, so the will-tick predicate answers
 * for the ticks of this tick that have not run yet exactly as it does in the inline loop. Ticks whose region
 * cannot be leased safely are executed on the server thread after the batch instead of being dropped.
 * <p>
 * Every group owns its own {@link RandomSource}, derived from one server-thread seed source per batch.
 * A worker therefore never draws from the shared level random, and the sequence a region observes only
 * depends on the region order of this batch.
 * <p>
 * A scheduled tick that writes a block records the light recheck and the block update publication of the write
 * in the deferred queue every region batch shares, and the ticks beyond the deferred block-update bound are
 * executed on the server thread after the batch instead of on a worker.
 */
@ApiStatus.Internal
final class RegionScheduledTickBatch<T> extends RegionTickBatch {
    private final ScheduledTickPhase phase;
    private final List<Group<T>> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ScheduledTick<T>> fallbackTicks = new ArrayList<>();
    private final Queue<Runnable> scheduledTickMutations = new ConcurrentLinkedQueue<>();
    @Nullable
    private RandomSource seedSource;

    RegionScheduledTickBatch(ServerLevel level, ScheduledTickPhase phase, int blockUpdateEntryBudget) {
        super(level, RegionTickSlice.SCHEDULED_TICK, blockUpdateEntryBudget);
        this.phase = phase;
    }

    ScheduledTickPhase phase() {
        return this.phase;
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param ticks       the scheduled ticks the group executes
     * @param randomSeed  the seed of the group's own random source
     * @param workFactory builds the worker task; it receives the group so later retries can extend it
     */
    Group<T> addGroup(RegionState region, List<ScheduledTick<T>> ticks, long randomSeed, Function<Group<T>, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Group<T> group = new Group<>(this, region, ticks, randomSeed);
        this.groups.add(group);
        this.addTask(this.phase.description() + " tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
    }

    /**
     * Records ticks that could not be leased safely; they are executed on the server thread after the
     * batch completed instead of being dropped.
     */
    void addFallback(List<ScheduledTick<T>> ticks) {
        this.fallbackTicks.addAll(ticks);
    }

    List<ScheduledTick<T>> fallbackTicks() {
        return this.fallbackTicks;
    }

    /**
     * {@return the server-owned queue workers enqueue {@code LevelTicks} mutations in}
     * <p>
     * The server thread drains it after every worker group of this phase finished, before the next phase
     * collects its ticks. The queue is shared by both {@code LevelTicks} instances of the level, so a
     * block worker that schedules a fluid tick and a fluid worker that schedules a block tick never
     * mutate the other scheduler concurrently.
     */
    Queue<Runnable> scheduledTickMutations() {
        return this.scheduledTickMutations;
    }

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group<T>> group(RegionState region) {
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
    static final class Group<T> {
        private final RegionScheduledTickBatch<T> batch;
        private final RegionState region;
        private final List<ScheduledTick<T>> ticks = new ArrayList<>();
        private final RandomSource random;

        Group(RegionScheduledTickBatch<T> batch, RegionState region, List<ScheduledTick<T>> ticks, long randomSeed) {
            this.batch = batch;
            this.region = region;
            this.ticks.addAll(ticks);
            this.random = RandomSource.create(randomSeed);
        }

        RegionScheduledTickBatch<T> batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        ServerLevel level() {
            return this.batch.level();
        }

        List<ScheduledTick<T>> ticks() {
            return this.ticks;
        }

        RandomSource random() {
            return this.random;
        }

        void addTicks(List<ScheduledTick<T>> ticks) {
            this.ticks.addAll(ticks);
        }
    }
}
