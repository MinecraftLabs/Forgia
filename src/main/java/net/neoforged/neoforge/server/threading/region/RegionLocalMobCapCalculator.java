/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.jetbrains.annotations.ApiStatus;

/**
 * The per-player mob cap of one region, computed from the answers of a {@link RegionSpawnNeighborhood}.
 * <p>
 * Vanilla counts, per player, the mobs that are close enough for spawning to the chunk the mob was added in,
 * capped at {@link MobCategory#getMaxInstancesPerChunk()}, and {@code NaturalSpawner.SpawnState} asks this
 * calculator both questions: {@link #addMob(ChunkPos, MobCategory)} for every mob the state counts and every mob
 * it spawns, and {@link #canSpawn(MobCategory, ChunkPos)} for every chunk it considers. Both are the whole
 * surface the spawn state uses, and both are overridden here.
 * <p>
 * The difference from the vanilla calculator is the scope of the player list, not the arithmetic: a region's
 * state sees the players close to the chunks the plan recorded for that region, so the per-player cap counts the
 * mobs of that region instead of the mobs of the level. A player near two regions can therefore hold a cap of
 * each of them; the per-region category cap of the spawn state is the bound that keeps the total population of
 * a region at the vanilla density.
 * <p>
 * The player lists come from the caller: the region slice reads them from a {@link RegionSpawnNeighborhood} that
 * was recorded on the server thread, and the server-thread fallback state reads them straight from the chunk map,
 * where the caller is the thread that owns it. The answers are cached per chunk, exactly like the vanilla
 * calculator caches them, and the counters are only touched by the thread that builds or runs the spawn state -
 * the server thread while the state is created, the group's worker afterwards. No vanilla structure is read from
 * a worker.
 */
@ApiStatus.Internal
final class RegionLocalMobCapCalculator extends LocalMobCapCalculator {
    private final LongFunction<List<ServerPlayer>> playersNearForSpawning;
    /** Players close enough for spawning, cached per chunk key like the vanilla calculator caches them. */
    private final Long2ObjectMap<List<ServerPlayer>> playersNearCaches = new Long2ObjectOpenHashMap<>();
    /** Mobs counted per player, indexed by the mob category they were added with. */
    private final Map<ServerPlayer, Object2IntOpenHashMap<MobCategory>> mobCounts = new IdentityHashMap<>();

    RegionLocalMobCapCalculator(LongFunction<List<ServerPlayer>> playersNearForSpawning) {
        // Both public methods of the parent are overridden below, so the chunk map it was constructed with is
        // never read: a region calculator never touches the server-owned chunk map.
        super(null);
        this.playersNearForSpawning = playersNearForSpawning;
    }

    @Override
    public void addMob(ChunkPos chunkPos, MobCategory category) {
        for (final ServerPlayer player : this.playersNear(chunkPos.toLong())) {
            this.mobCounts.computeIfAbsent(player, key -> new Object2IntOpenHashMap<>()).addTo(category, 1);
        }
    }

    @Override
    public boolean canSpawn(MobCategory category, ChunkPos chunkPos) {
        for (final ServerPlayer player : this.playersNear(chunkPos.toLong())) {
            final Object2IntMap<MobCategory> counts = this.mobCounts.get(player);
            if (counts == null || counts.getInt(category) < category.getMaxInstancesPerChunk()) {
                return true;
            }
        }
        return false;
    }

    private List<ServerPlayer> playersNear(long chunkKey) {
        final List<ServerPlayer> cached = this.playersNearCaches.get(chunkKey);
        if (cached != null) {
            return cached;
        }
        final List<ServerPlayer> players = this.playersNearForSpawning.apply(chunkKey);
        this.playersNearCaches.put(chunkKey, players);
        return players;
    }
}
