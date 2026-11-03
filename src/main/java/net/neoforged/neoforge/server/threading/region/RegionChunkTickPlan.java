/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The chunk ticks of one level tick that the server thread hands to the region workers.
 * <p>
 * The plan does not collect chunks itself. {@code ServerChunkCache.tickChunks} already walks the level's chunk
 * index and decides per chunk whether it is ticked at all - the natural spawning gate, the forced-tick gate and
 * {@code shouldTickBlocksAt} - and it hands every chunk it would tick to {@link #accept(LevelChunk)}. Those
 * reads are server-owned (the distance manager's ticking tracker and the chunk map are mutated by the server
 * thread while it waits at the batch barrier), so the decision stays where vanilla makes it and the plan only
 * decides whether the body may run on a worker.
 * <p>
 * A chunk is handed over when
 * <ul>
 * <li>it is an interior chunk of its region, i.e. all eight neighboring chunks belong to the same region, so a
 * random tick that writes into a neighboring chunk writes into the lease the worker holds, and</li>
 * <li>those eight neighbors are loaded, so a random tick cannot force-load a chunk from a worker - the worker
 * chunk source refuses a load instead of waiting for the server thread that is waiting for the worker.</li>
 * </ul>
 * Every other chunk is ticked on the server thread at the point vanilla ticks it and counted by reason: a
 * boundary chunk, a chunk with an unloaded neighbor, or a chunk collected after the batch already ran.
 * <p>
 * Natural spawning, the inhabited time update, the custom spawners and the per-chunk broadcast stay in
 * {@code ServerChunkCache.tickChunks} on the server thread. The plan never touches {@code SpawnState}, the mob
 * cap or the level random.
 */
@ApiStatus.Internal
public final class RegionChunkTickPlan {
    /** Capacity hint of the collected chunk list, so a large level does not re-grow it every tick. */
    private static final int CHUNK_CAPACITY_HINT = 256;
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionTickBarrier barrier;
    private final int randomTickSpeed;
    /** The chunks this plan hands to the batch, in the order {@code tickChunks} collected them. */
    private final List<RegionChunkTickEntry> chunks = new ArrayList<>(CHUNK_CAPACITY_HINT);
    private boolean executed;

    RegionChunkTickPlan(RegionThreadingEngine engine, ServerLevel level, int randomTickSpeed, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.randomTickSpeed = randomTickSpeed;
        this.barrier = barrier;
    }

    /**
     * Decides one chunk of the level's chunk tick: hand it to the region batch, or tick it on the server
     * thread like vanilla and count the refusal.
     * <p>
     * Runs on the server thread, once per chunk the level would tick.
     *
     * @param chunk the chunk the level decided to tick
     */
    public void accept(LevelChunk chunk) {
        if (this.executed) {
            // A chunk collected after the batch ran belongs to a chunk the server thread deferred to a later
            // region drain. Tick it inline like a fallback chunk instead of dropping it.
            this.engine.countLateChunkTick();
            this.engine.tickChunkInline(this.level, chunk, this.randomTickSpeed);
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        if (!this.engine.isInteriorRegionChunk(chunkPos)) {
            // A random tick of a boundary chunk can write into the neighboring region, so the chunk keeps the
            // server-thread body: the one-chunk bound of the interior rule is what makes a worker write safe.
            this.engine.countBoundaryChunkTick();
            this.engine.tickChunkInline(this.level, chunk, this.randomTickSpeed);
            return;
        }
        if (!this.engine.isChunkTickNeighborhoodLoaded(this.level, chunkPos)) {
            // A random tick that reaches a neighboring chunk would have to load it, and a worker may not load
            // a chunk. The chunk therefore keeps the server-thread body.
            this.engine.countUnloadedNeighborhoodChunkTick();
            this.engine.tickChunkInline(this.level, chunk, this.randomTickSpeed);
            return;
        }
        if (this.chunks.size() >= this.engine.chunkTickDeferredUpdateBudget()) {
            // Every block write of a worker chunk is recorded for the server thread, because the light engine
            // and the block update publication of a level are not partitioned per region. The batch reserves
            // that space per chunk before the body starts: a chunk that does not fit keeps the server-thread
            // body, where its writes need no deferred record at all.
            this.engine.countDeferredUpdateBudgetChunkTick();
            this.engine.tickChunkInline(this.level, chunk, this.randomTickSpeed);
            return;
        }
        this.chunks.add(RegionChunkTickEntry.of(chunk));
    }

    /**
     * {@return the chunks this plan hands to the batch, in collection order}
     * <p>
     * The enumeration test compares the chunks of the plan with the set the server thread would tick for the
     * same layout, so the handed-over chunks are observable without ticking them.
     */
    @VisibleForTesting
    public List<RegionChunkTickEntry> collectedChunks() {
        return Collections.unmodifiableList(this.chunks);
    }

    /**
     * Ticks every collected chunk on the worker of its owning region and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        try {
            this.engine.tickChunks(this.level, this.chunks, this.randomTickSpeed, barrier);
        } finally {
            this.executed = true;
            this.chunks.clear();
        }
    }
}
