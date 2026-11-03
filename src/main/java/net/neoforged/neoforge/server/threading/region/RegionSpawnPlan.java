/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The natural spawning of one level tick that the server thread hands to the region workers.
 * <p>
 * The plan does not decide which chunks may spawn: {@code ServerChunkCache.tickChunks} already walks the level's
 * chunk list and evaluates the spawn gate per chunk - the natural spawning ticket and the player distance, the
 * domino spawning rule, the friends/enemies switches, the world border and the forced-tick ticket, all of them
 * reads of the distance manager, the chunk map and the level data that only the server thread may make - and it
 * hands every chunk that may spawn to {@link #accept(LevelChunk, boolean)}. The decision therefore stays where
 * vanilla makes it, and the plan only decides whether the spawn body may run on a worker.
 * <p>
 * A chunk is handed over when
 * <ul>
 * <li>its eight neighboring chunks are loaded, so no chunk read of the spawn body - the candidate positions, the
 * spawn rules, the heightmap, the biome - has to force-load a chunk from a worker: the worker chunk source
 * refuses a load instead of waiting for the server thread that is waiting for the worker, and</li>
 * <li>the batch could reserve the block-write effects of the chunk, so a block a modded spawn hook writes has
 * somewhere to be recorded (the vanilla body writes no block).</li>
 * </ul>
 * Every other chunk spawns on the server thread at the point vanilla spawns it and is counted by reason: the gate
 * refused, a neighbor is not loaded, or the batch had no room left.
 * <p>
 * The mob cap of a region is built from the region's own chunks and entities (see
 * {@link #regionState(RegionState)}), and a chunk's spawn body draws from a random source derived from the world
 * seed, the dimension, the game time, the region and the chunk (see
 * {@link RegionSpawnChunk#spawnSeed}), so a region's spawn sequence depends neither on the other regions nor on
 * the worker that runs it. The chunks the plan keeps on the server thread spawn from the vanilla level-wide state,
 * exactly like vanilla, and that state is created on demand: a tick that hands every spawnable chunk to a worker
 * never builds it.
 */
@ApiStatus.Internal
public final class RegionSpawnPlan {
    /** Capacity hint of the collected chunk list, so a large level does not re-grow it every tick. */
    private static final int CHUNK_CAPACITY_HINT = 256;
    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final RegionSpawnSource source;
    private final boolean spawnFriendlies;
    private final boolean spawnEnemies;
    private final boolean spawnPersistent;
    private final RegionTickBarrier barrier;
    /** The chunks this plan hands to the batch, in the order {@code tickChunks} collected them. */
    private final List<RegionSpawnChunk> chunks = new ArrayList<>(CHUNK_CAPACITY_HINT);
    /**
     * The vanilla level-wide spawn state of the chunks this plan keeps on the server thread.
     * <p>
     * It is built on the first chunk that has to spawn inline, from the same entity set and the same full-chunk
     * getter vanilla uses, so a refused chunk spawns against exactly the state vanilla would have given it. A tick
     * whose chunks all leave the server thread never builds it, which is what makes the regionized slice save the
     * level-wide entity scan.
     */
    @Nullable
    private NaturalSpawner.SpawnState fallbackState;
    private boolean executed;

    RegionSpawnPlan(RegionThreadingEngine engine, ServerLevel level, RegionSpawnSource source, boolean spawnFriendlies,
            boolean spawnEnemies, boolean spawnPersistent, RegionTickBarrier barrier) {
        this.engine = engine;
        this.level = level;
        this.source = source;
        this.spawnFriendlies = spawnFriendlies;
        this.spawnEnemies = spawnEnemies;
        this.spawnPersistent = spawnPersistent;
        this.barrier = barrier;
    }

    /**
     * Decides one chunk of the level's chunk tick: hand it to the region batch, or spawn for it on the server
     * thread like vanilla and count the refusal.
     * <p>
     * Runs on the server thread, once per chunk the level would spawn for.
     *
     * @param chunk        the chunk the level decided to spawn for
     * @param spawnAllowed whether vanilla's inner spawn gate lets this chunk spawn; {@code false} means the level
     *                     would not have called {@code spawnForChunk} either, and the plan only counts it
     */
    public void accept(LevelChunk chunk, boolean spawnAllowed) {
        if (!spawnAllowed) {
            // The vanilla gate - the doMobSpawning rule, the friends/enemies switches or the world border -
            // refused this chunk. Vanilla spawns nothing for it, so no body runs; the counter makes the gate
            // visible in a measurement of the slice.
            this.engine.countGateRefusedSpawnChunk();
            return;
        }
        if (this.executed) {
            // A chunk collected after the batch ran belongs to a chunk the server thread deferred to a later
            // region drain. Spawn for it inline like a fallback chunk instead of dropping it.
            this.engine.countLateSpawnChunk();
            this.spawnInline(chunk);
            return;
        }
        final ChunkPos chunkPos = chunk.getPos();
        if (!this.engine.isLoadedChunkNeighborhood(this.level, chunkPos)) {
            // The spawn body reads the chunks around the spawning chunk - the candidate positions, the block
            // below a candidate, the biome, the spawn rules - and a worker may not load a chunk, so the chunk
            // keeps the server-thread body.
            this.engine.countUnloadedNeighborhoodSpawnChunk();
            this.spawnInline(chunk);
            return;
        }
        if (this.chunks.size() >= this.engine.spawnDeferredUpdateBudget()) {
            // The batch reserves the block-write effects of every chunk it admits. A chunk that does not fit
            // keeps the server-thread body, where its writes need no deferred record at all.
            this.engine.countDeferredUpdateBudgetSpawnChunk();
            this.spawnInline(chunk);
            return;
        }
        this.chunks.add(RegionSpawnChunk.of(chunk));
    }

    /**
     * {@return the chunks this plan hands to the batch, in collection order}
     * <p>
     * The enumeration test compares the chunks of the plan with the set the server thread would spawn for in the
     * same layout, so the handed-over chunks are observable without spawning.
     */
    @VisibleForTesting
    public List<RegionSpawnChunk> collectedChunks() {
        return Collections.unmodifiableList(this.chunks);
    }

    /**
     * Spawns every collected chunk on the worker of its owning region and waits for the batch.
     */
    public void executeAndAwait() {
        this.executeAndAwait(this.barrier);
    }

    @VisibleForTesting
    public void executeAndAwait(RegionTickBarrier barrier) {
        try {
            this.engine.spawnChunks(this.level, this.chunks, this, barrier);
        } finally {
            this.executed = true;
            this.chunks.clear();
        }
    }

    /**
     * Spawns for {@code chunk} on the server thread from the vanilla level-wide spawn state.
     * <p>
     * Used for every chunk the plan keeps on the server thread: a gate refusal, a chunk with an unloaded
     * neighbor, a chunk the batch could not admit, a chunk whose region could not be leased and a chunk whose
     * body a worker truncated. The vanilla state is built once per level tick on the first such chunk.
     */
    void spawnInline(LevelChunk chunk) {
        NaturalSpawner.spawnForChunk(this.level, chunk, this.fallbackState(), this.spawnFriendlies, this.spawnEnemies, this.spawnPersistent);
    }

    /**
     * {@return the vanilla level-wide spawn state of this tick, built on first use}
     * <p>
     * It is exactly the state {@code ServerChunkCache.tickChunks} builds for the vanilla path: the level's
     * natural spawn chunk count, every entity of the level and the level's own full-chunk getter. The per-player
     * cap reads the chunk map directly, because this state only ever runs on the thread that owns it.
     */
    private NaturalSpawner.SpawnState fallbackState() {
        if (this.fallbackState == null) {
            this.fallbackState = NaturalSpawner.createState(
                    this.source.naturalSpawnChunkCount().getAsInt(),
                    this.level.getAllEntities(),
                    this.source.chunkGetter(),
                    new RegionLocalMobCapCalculator(this.source.playersNearForSpawning()));
        }
        return this.fallbackState;
    }

    /**
     * {@return the spawn state of one region and the answers its bodies read, built on the server thread}
     * <p>
     * The state counts the entities the region owns and uses the region's own spawnable chunk count as the area
     * term of its mob cap; see {@link RegionSpawnState} for the exact semantics and for the two differences
     * against the level-wide state of vanilla.
     *
     * @param regionChunks the chunks this tick hands to the region's worker
     */
    RegionSpawnState.Built buildRegionState(RegionState region, List<RegionSpawnChunk> regionChunks) {
        final List<Entity> entities = new ArrayList<>(region.entityTickEntryCount());
        for (final RegionEntityTickEntry entry : region.entityTickEntries()) {
            entities.add(entry.entity());
        }
        final List<Long> handed = new ArrayList<>(regionChunks.size());
        for (final RegionSpawnChunk chunk : regionChunks) {
            handed.add(chunk.chunkKey());
        }
        return RegionSpawnState.build(this.level, this.source, region.loadedChunkKeys(), entities, handed);
    }

    /**
     * {@return the vanilla level-wide spawn state this tick's fallback chunks spawned from, or {@code null} when
     * no chunk stayed on the server thread}
     * <p>
     * The level keeps the last state for its debug output. A tick that handed every chunk to a worker built no
     * level-wide state at all, and that is exactly what this answer reports.
     */
    @Nullable
    public NaturalSpawner.SpawnState inlineState() {
        return this.fallbackState;
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
}
