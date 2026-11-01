/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Random ticks of one level tick that are selected on the server thread and executed on region workers.
 * <p>
 * The level collects the callbacks while it traverses the randomly ticking sections of the ticking
 * chunks on the server thread. Chunk selection, random position selection, section traversal, thunder,
 * precipitation, natural spawning and inhabited time stay in the unchanged server-thread body; the plan
 * only receives the block and fluid random tick callbacks that were actually selected.
 * <p>
 * A random tick callback can write into the chunks around its own chunk. The plan therefore only hands a
 * chunk's ticks to a worker when all eight neighboring chunks belong to the same region, so a callback
 * cannot write into a region the worker does not own; the ticks of every other chunk are executed on the
 * server thread after the batch instead of being dropped. The plan never claims general thread safety
 * for arbitrary cross-region writes.
 */
@ApiStatus.Internal
public final class RegionRandomTickPlan {
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionTickBarrier barrier;
    private final List<RegionRandomTick> ticks = new ArrayList<>();
    private final List<RegionRandomTick> fallbackTicks = new ArrayList<>();
    private final Map<Long, Boolean> dispatchByChunk = new HashMap<>();
    private boolean executed;

    RegionRandomTickPlan(RegionThreadingEngine engine, ServerLevel level, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.barrier = barrier;
    }

    /**
     * Records one random tick the server thread selected.
     *
     * @param chunkPos the chunk the position belongs to
     * @param pos      the selected position
     * @param callback the captured block or fluid random tick callback
     */
    public void accept(ChunkPos chunkPos, BlockPos pos, RegionRandomTickCallback callback) {
        if (this.executed) {
            // A tick collected after the batch ran belongs to a chunk the server thread deferred to a
            // later region drain. Run it inline like the server-thread fallback instead of dropping it.
            callback.randomTick(this.level, pos, this.level.random);
            return;
        }
        final RegionRandomTick tick = new RegionRandomTick(pos, callback);
        if (this.dispatchByChunk.computeIfAbsent(chunkPos.toLong(), key -> this.engine.isInteriorRegionChunk(chunkPos))) {
            this.ticks.add(tick);
        } else {
            this.fallbackTicks.add(tick);
        }
    }

    /**
     * Executes every collected random tick on its owning region worker and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        try {
            this.engine.tickRandomTicks(this.level, this.ticks, this.fallbackTicks, barrier);
        } finally {
            this.executed = true;
            this.ticks.clear();
            this.fallbackTicks.clear();
            this.dispatchByChunk.clear();
        }
    }
}
