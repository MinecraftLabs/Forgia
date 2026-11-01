/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * Block-entity tickers of one level tick that are ticked on their owning region worker instead of the
 * server thread.
 * <p>
 * The level collects the tickers while it iterates its ticker list on the server thread. Only the
 * tickers the vanilla loop would tick (not removed, tick rate normal, in block ticking range, entity load
 * eligible) are collected; everything else is handled by the unchanged vanilla loop. The server thread
 * submits the collected tickers once the iteration is over, so its list is never iterated while workers
 * mutate level state.
 * <p>
 * Tickers that cannot be resolved to a live, leasable region are ticked on the server thread after the
 * batch rather than being dropped, and the batch preserves the order of the tickers within each region.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickPlan {
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionTickBarrier barrier;
    private final List<RegionBlockEntityTickAdmission> tickers = new ArrayList<>();

    RegionBlockEntityTickPlan(RegionThreadingEngine engine, ServerLevel level, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.barrier = barrier;
    }

    /**
     * Records a ticker the vanilla loop would tick for a region worker tick.
     * <p>
     * Runs on the server thread and evaluates the vanilla entity-load eligibility of the ticker's chunk
     * here, before the ticker is admitted: a worker cannot read the server-owned entity section status,
     * and the block ticking range alone does not prove that the chunk's entities finished loading. A
     * rejected ticker stays with the unchanged inline loop, which evaluates the whole
     * {@code LevelChunk.isTicking} condition itself and skips the tick while the load is pending.
     *
     * @return {@code true} when the ticker was admitted to the worker batch
     */
    public boolean accept(TickingBlockEntity ticker) {
        final BlockPos position = ticker.getPos().immutable();
        final RegionBlockEntityTickAdmission admission = new RegionBlockEntityTickAdmission(
                this.level, ticker, position, ticker.getType(), LevelChunk.captureBlockEntityTickBinding(ticker, this.level));
        if (!this.level.areEntitiesLoaded(ChunkPos.asLong(position)) || !admission.isCurrent()) {
            return false;
        }
        this.tickers.add(admission);
        return true;
    }

    /**
     * Ticks every collected ticker on its owning region worker and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        try {
            this.engine.tickBlockEntities(this.level, this.tickers, barrier);
        } finally {
            this.tickers.clear();
        }
    }
}
