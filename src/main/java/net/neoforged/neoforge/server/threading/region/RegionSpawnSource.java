/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server-owned reads the natural spawning plan needs from one level's chunk source.
 * <p>
 * Every one of them is a read of a structure that only the server thread may touch - the chunk map, the
 * distance manager and the entity manager's visibility map - so the plan takes them from the level owner on
 * the server thread and never hands them to a worker. They are functions instead of a {@code ChunkMap} handle
 * because the chunk cache does not expose the structures themselves: the level owner creates them, and the
 * plan calls them on the thread that owns them.
 *
 * @param chunkGetter            the vanilla full-chunk getter, which decides whether a mob at a position is
 *                               counted for the mob cap exactly like the vanilla spawn state counts it
 * @param playersNearForSpawning the players close enough for spawning to a chunk key, i.e. the vanilla
 *                               {@code ChunkMap.getPlayersCloseForSpawning}
 * @param spawnableChunk         whether a chunk key counts into the vanilla natural spawn chunk count, i.e.
 *                               whether a player is close enough for spawning to it
 * @param naturalSpawnChunkCount the vanilla level-wide natural spawn chunk count, used by the server-thread
 *                               fallback state
 */
@ApiStatus.Internal
public record RegionSpawnSource(
        NaturalSpawner.ChunkGetter chunkGetter,
        LongFunction<List<ServerPlayer>> playersNearForSpawning,
        LongPredicate spawnableChunk,
        IntSupplier naturalSpawnChunkCount) {}
