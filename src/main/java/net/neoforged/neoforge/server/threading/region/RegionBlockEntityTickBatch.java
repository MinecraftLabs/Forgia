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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * One level block-entity tick that is executed on region workers.
 * <p>
 * The batch is a snapshot of the tickers the vanilla server thread would tick this tick. Each group owns
 * the lease of exactly one region and ticks the snapshot tickers of that region in their original order,
 * so different regions run in parallel while a region never ticks against itself. Tickers whose region
 * cannot be leased safely are ticked on the server thread after the batch instead of being dropped.
 */
@ApiStatus.Internal
final class RegionBlockEntityTickBatch extends RegionTickBatch {
    private final List<Group> groups = new ArrayList<>();
    private final Set<RegionState> leasedRegions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Collection<RegionBlockEntityTickAdmission> fallbackTickers = new ConcurrentLinkedQueue<>();

    RegionBlockEntityTickBatch(ServerLevel level) {
        super(level);
    }

    /**
     * Adds one region group and registers its worker task.
     *
     * @param region      the region whose lease the group holds
     * @param tickers     the block-entity tickers the group ticks
     * @param workFactory builds the worker task; it receives the group so later retries can extend it
     */
    Group addGroup(RegionState region, List<RegionBlockEntityTickAdmission> tickers, Function<Group, Runnable> workFactory) {
        this.leasedRegions.add(region);
        final Group group = new Group(this, region, tickers);
        this.groups.add(group);
        this.addTask("block entity tick " + region.coordinate(), this.groupWork(workFactory.apply(group)));
        return group;
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

    boolean leases(RegionState region) {
        return this.leasedRegions.contains(region);
    }

    Optional<Group> group(RegionState region) {
        return this.groups.stream().filter(group -> group.region() == region).findFirst();
    }

    /**
     * One region's share of the batch: the region lease and the tickers it ticks in snapshot order.
     */
    static final class Group {
        private final RegionBlockEntityTickBatch batch;
        private final RegionState region;
        private final List<RegionBlockEntityTickAdmission> tickers = new ArrayList<>();

        Group(RegionBlockEntityTickBatch batch, RegionState region, List<RegionBlockEntityTickAdmission> tickers) {
            this.batch = batch;
            this.region = region;
            this.tickers.addAll(tickers);
        }

        RegionBlockEntityTickBatch batch() {
            return this.batch;
        }

        RegionState region() {
            return this.region;
        }

        List<RegionBlockEntityTickAdmission> tickers() {
            return this.tickers;
        }

        void addTickers(List<RegionBlockEntityTickAdmission> tickers) {
            this.tickers.addAll(tickers);
        }
    }
}
