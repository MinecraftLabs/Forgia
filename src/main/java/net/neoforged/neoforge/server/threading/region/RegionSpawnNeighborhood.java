/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * The per-chunk answers a spawn body of one region needs, read once on the server thread.
 * <p>
 * A vanilla spawn body asks two questions that are answered from server-owned state:
 * {@code ServerLevel.isNaturalSpawningAllowed(BlockPos)} reads the entity manager's chunk visibility map (the
 * spawn position's own chunk must be ticked unless the position is in the chunk that spawned), and
 * {@code LocalMobCapCalculator} asks the chunk map which players are close enough for spawning. A region worker
 * may read neither. The plan therefore records both answers for every chunk a spawn body can reach and the
 * worker only reads this snapshot.
 * <p>
 * <strong>Which chunks a spawn body reaches.</strong> The vanilla body picks its start position inside the
 * chunk that spawns and then walks candidate positions in steps of at most five blocks, up to four steps per
 * spawn attempt and three attempts. A candidate is therefore at most twenty blocks from the start position,
 * i.e. at most two chunks away from the spawning chunk. The plan records the square of
 * {@code (2 * REACH_CHUNKS + 1)^2} chunks around every chunk it hands to a worker, which covers every
 * candidate a spawn body of that chunk can produce. A chunk outside that square answers "not spawnable" and
 * "no player nearby": the answers are unreachable for a spawn body of a handed chunk, and a body that reaches
 * one anyway gets the vanilla answer for a chunk that may not spawn.
 * <p>
 * Both answers are read on the server thread before any worker runs, from the same structures the vanilla path
 * reads, so a chunk that is handed over decides its spawns from the same facts it would have used inline.
 */
@ApiStatus.Internal
public final class RegionSpawnNeighborhood {
    /**
     * Chunk radius around a spawnable chunk whose answers a spawn body can reach.
     * <p>
     * The walk of {@code NaturalSpawner.spawnCategoryForPosition} moves a candidate by at most five blocks per
     * step, at most four steps per attempt, from a position inside the spawning chunk, so twenty blocks is the
     * bound and two chunks is the chunk radius that contains it.
     */
    public static final int REACH_CHUNKS = 2;

    /** The vanilla answer of {@code ServerLevel.isNaturalSpawningAllowed(ChunkPos)} per chunk. */
    private final Long2BooleanMap spawnAllowed = new Long2BooleanOpenHashMap();
    /** The vanilla answer of {@code ChunkMap.getPlayersCloseForSpawning} per chunk. */
    private final Long2ObjectMap<List<ServerPlayer>> playersNear = new Long2ObjectOpenHashMap<>();

    /**
     * Records the answers of the chunks a spawn body of {@code chunkKey} can reach.
     *
     * @param level    the level the answers are read from; must be the server thread
     * @param source   the server-owned reads of that level
     * @param chunkKey the chunk the plan hands to a worker
     */
    public void recordReach(ServerLevel level, RegionSpawnSource source, long chunkKey) {
        final int chunkX = ChunkPos.getX(chunkKey);
        final int chunkZ = ChunkPos.getZ(chunkKey);
        for (int offsetX = -REACH_CHUNKS; offsetX <= REACH_CHUNKS; offsetX++) {
            for (int offsetZ = -REACH_CHUNKS; offsetZ <= REACH_CHUNKS; offsetZ++) {
                this.recordChunk(level, source, ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ));
            }
        }
    }

    /**
     * Records the answers of one chunk unless they were read already.
     */
    public void recordChunk(ServerLevel level, RegionSpawnSource source, long chunkKey) {
        if (this.spawnAllowed.containsKey(chunkKey)) {
            return;
        }
        this.spawnAllowed.put(chunkKey, level.isNaturalSpawningAllowed(new ChunkPos(chunkKey)));
        this.playersNear.put(chunkKey, source.playersNearForSpawning().apply(chunkKey));
    }

    /** {@return the vanilla spawn-gate answer of {@code chunkKey}; an unrecorded chunk answers {@code false}} */
    public boolean isSpawnAllowed(long chunkKey) {
        return this.spawnAllowed.get(chunkKey);
    }

    /**
     * {@return the players close enough for spawning to {@code chunkKey}}
     * <p>
     * An unrecorded chunk answers an empty list, which is the vanilla answer for a chunk no player is close to
     * and therefore never suppresses a spawn a recorded chunk would have made.
     */
    public List<ServerPlayer> playersNear(long chunkKey) {
        final List<ServerPlayer> players = this.playersNear.get(chunkKey);
        return players != null ? players : List.of();
    }
}
