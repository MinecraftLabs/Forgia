/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One chunk's share of a block-entity worker tick.
 * <p>
 * The plan hands the worker the chunk and the tickers it collected for it, so the worker iterates the
 * chunk's admitted tickers itself instead of the server thread walking the level's ticker list. The
 * admissions are in the order the level's own ticker list would tick them, so a worker group reproduces the
 * tick order of the region it owns.
 * <p>
 * A unit of the chunk-indexed plan carries the chunk and its admission snapshot, and the worker revalidates
 * the snapshot against the live chunk before it ticks anything: a chunk that unloaded or changed its ticker
 * set since the plan handed it over is ticked on the server thread instead of from the stale snapshot. A unit
 * of the explicit per-ticker admission carries no snapshot, because each of its admissions revalidates its
 * own binding and position.
 */
@ApiStatus.Internal
public record RegionBlockEntityTickChunk(
        long chunkKey,
        @Nullable LevelChunk chunk,
        @Nullable RegionBlockEntityTickChunkCache cache,
        List<RegionBlockEntityTickAdmission> admissions,
        boolean allAdmitted) {
    public RegionBlockEntityTickChunk {
        admissions = List.copyOf(admissions);
    }

    /**
     * {@return the unit of {@code chunk}, whose tickers come from its admission snapshot}
     */
    static RegionBlockEntityTickChunk ofSnapshot(LevelChunk chunk, RegionBlockEntityTickChunkCache cache) {
        return new RegionBlockEntityTickChunk(chunk.getPos().toLong(), chunk, cache, cache.admissions(), cache.allAdmitted());
    }

    /**
     * {@return true when the chunk still holds exactly the ticker set this unit was collected from}
     * <p>
     * This is the safety check of the chunk-indexed plan: the server thread is parked in the batch barrier
     * while the workers run, so a chunk cannot unload or change its ticker set underneath a worker. The check
     * proves the assumption instead of trusting it, and a unit that fails it is handed back to the server
     * thread instead of being ticked from a stale snapshot.
     */
    public boolean isCurrent(RegionLayout layout) {
        if (this.chunk == null) {
            return true;
        }
        return this.chunk.isLoaded() && this.cache != null && this.cache.isCurrent(layout, this.chunk.blockEntityTickerRevision());
    }

    /** {@return the region coordinate of this chunk under {@code layout}} */
    RegionCoordinate region(RegionLayout layout, ServerLevel level) {
        return layout.regionAt(level, ChunkPos.getX(this.chunkKey), ChunkPos.getZ(this.chunkKey));
    }
}
