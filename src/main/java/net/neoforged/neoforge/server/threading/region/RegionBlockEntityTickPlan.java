/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

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
 * <p>
 * The admission of a ticker is cached per chunk by {@link RegionBlockEntityTickChunkCache}: the allowlist
 * decision, the interior-chunk rule and the exact binding capture are taken from the chunk's snapshot and
 * reused while it is current, so collecting the plan costs one chunk lookup and one ticker lookup per ticker
 * instead of a full evaluation. The entity-load eligibility of the chunk stays a server-thread read per tick,
 * evaluated once per chunk instead of once per ticker.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickPlan {
    /**
     * Initial capacity of the collected ticker list and the chunk visit map. Both grow when a level collects
     * more, so the hint only avoids re-growing the collections of a large level every tick.
     */
    private static final int TICKER_CAPACITY_HINT = 1024;
    private static final int CHUNK_CAPACITY_HINT = 256;
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionTickBarrier barrier;
    private final List<RegionBlockEntityTickAdmission> tickers = new ArrayList<>(TICKER_CAPACITY_HINT);
    /**
     * Per-tick state of the chunks whose tickers this plan collected, so the chunk lookup and the entity-load
     * read of a chunk happen once per tick instead of once per ticker. The plan lives for exactly one level
     * tick, so this map never outlives the tick it belongs to.
     */
    private final Long2ObjectMap<ChunkVisit> chunkVisits = new Long2ObjectOpenHashMap<>(CHUNK_CAPACITY_HINT);

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
        final BlockPos position = ticker.getPos();
        final ChunkVisit visit = this.engine.isBlockEntityAdmissionCacheEnabled() ? this.chunkVisit(position) : ChunkVisit.UNRESOLVED;
        if (visit.kind() == ChunkVisit.Kind.UNRESOLVED) {
            // The chunk is not loaded, or the cache is off for this run: the ticker keeps the uncached
            // evaluation, which is the reference behavior of the cached one.
            return this.acceptUncached(ticker, position);
        }
        if (visit.kind() == ChunkVisit.Kind.LOADING) {
            return false;
        }
        final RegionBlockEntityTickChunkCache cache = visit.cache();
        final RegionBlockEntityTickAdmission cached = cache.admission(ticker);
        if (cached != null && cached.isCurrentForCollection()) {
            this.tickers.add(cached);
            return true;
        }
        return this.capture(cache, ticker, position);
    }

    /**
     * {@return the tickers this plan collected so far, in collection order}
     * <p>
     * The equivalence test of the chunk cache compares this list with the one of the uncached evaluation, so
     * the admitted set, its order and the reason of every rejected ticker are observable without ticking.
     */
    @org.jetbrains.annotations.VisibleForTesting
    public List<RegionBlockEntityTickAdmission> collectedTickers() {
        return Collections.unmodifiableList(this.tickers);
    }

    /**
     * Ticks every collected ticker on its owning region worker and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        // The server thread is done collecting and admitting the tickers; the attribution of the slice
        // closes its plan segment here, before the batch is published.
        RegionBlockEntityTickAttribution.endPlanSegment();
        try {
            this.engine.tickBlockEntities(this.level, this.tickers, barrier);
        } finally {
            this.tickers.clear();
            this.chunkVisits.clear();
        }
    }

    /**
     * {@return the state of the chunk of {@code position} for this tick, looked up once per chunk}
     */
    private ChunkVisit chunkVisit(BlockPos position) {
        final int chunkX = SectionPos.blockToSectionCoord(position.getX());
        final int chunkZ = SectionPos.blockToSectionCoord(position.getZ());
        final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        final ChunkVisit known = this.chunkVisits.get(chunkKey);
        if (known != null) {
            return known;
        }
        final ChunkVisit visit;
        final ServerChunkCache chunkSource = this.level.getChunkSource();
        final LevelChunk chunk = chunkSource == null ? null : chunkSource.getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            visit = ChunkVisit.UNRESOLVED;
        } else if (!this.level.areEntitiesLoaded(chunkKey)) {
            visit = ChunkVisit.LOADING;
        } else {
            visit = new ChunkVisit(ChunkVisit.Kind.READY, this.chunkCache(chunk));
        }
        this.chunkVisits.put(chunkKey, visit);
        return visit;
    }

    /**
     * {@return the current admission snapshot of {@code chunk}, rebuilt when the chunk's ticker set or the
     * region layout changed since it was built}
     */
    private RegionBlockEntityTickChunkCache chunkCache(LevelChunk chunk) {
        final RegionLayout layout = this.engine.regionLayout();
        final long tickerRevision = chunk.blockEntityTickerRevision();
        final RegionBlockEntityTickChunkCache cached = chunk.blockEntityTickAdmissionCache();
        if (cached != null && cached.isCurrent(layout, tickerRevision)) {
            return cached;
        }
        final RegionBlockEntityTickChunkCache rebuilt = new RegionBlockEntityTickChunkCache(
                layout, tickerRevision, this.engine.isInteriorRegionChunk(chunk.getPos()));
        // The setter releases the snapshot it replaces, so a chunk never holds more than one.
        chunk.setBlockEntityTickAdmissionCache(rebuilt);
        return rebuilt;
    }

    /**
     * Captures the admission of {@code ticker} into the snapshot of its chunk and records it in the plan.
     */
    private boolean capture(RegionBlockEntityTickChunkCache cache, TickingBlockEntity ticker, BlockPos position) {
        final String type = ticker.getType();
        final RegionBlockEntityTickAdmission admission = new RegionBlockEntityTickAdmission(
                this.level,
                ticker,
                position,
                type,
                LevelChunk.captureBlockEntityTickBinding(ticker, this.level),
                RegionTickCompatibility.decideBlockEntityTick(type, cache.interiorChunk()));
        if (!admission.isCurrent()) {
            return false;
        }
        cache.putAdmission(ticker, admission);
        this.tickers.add(admission);
        return true;
    }

    /**
     * The uncached admission of one ticker: the entity-load eligibility of its chunk and a fresh binding
     * capture are evaluated here, exactly like the plan evaluated every ticker before the chunk cache.
     */
    private boolean acceptUncached(TickingBlockEntity ticker, BlockPos position) {
        final String type = ticker.getType();
        final RegionBlockEntityTickAdmission admission = new RegionBlockEntityTickAdmission(
                this.level,
                ticker,
                position,
                type,
                LevelChunk.captureBlockEntityTickBinding(ticker, this.level),
                RegionTickCompatibility.decideBlockEntityTick(type, this.engine.isInteriorRegionChunk(new ChunkPos(position))));
        if (!this.level.areEntitiesLoaded(ChunkPos.asLong(position)) || !admission.isCurrent()) {
            return false;
        }
        this.tickers.add(admission);
        return true;
    }

    /**
     * What the plan decided for one visited chunk of this tick.
     */
    private record ChunkVisit(Kind kind, @Nullable RegionBlockEntityTickChunkCache cache) {

        /** The chunk is not loaded (or the cache is off): its tickers keep the uncached evaluation. */
        static final ChunkVisit UNRESOLVED = new ChunkVisit(Kind.UNRESOLVED, null);
        /** The chunk's entities are still loading: every ticker of it keeps the unchanged inline path. */
        static final ChunkVisit LOADING = new ChunkVisit(Kind.LOADING, null);
        enum Kind {
            UNRESOLVED,
            LOADING,
            READY
        }
    }
}
