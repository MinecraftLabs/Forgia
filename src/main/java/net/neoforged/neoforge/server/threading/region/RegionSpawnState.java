/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.ApiStatus;

/**
 * The natural spawn state of one region, built on the server thread.
 * <p>
 * The state is a vanilla {@link NaturalSpawner.SpawnState} built from what the region owns:
 * <ul>
 * <li>the entities the region owns, which are its spawn potential and the mobs its per-player cap counts, skipping
 * persistent mobs and mobs of the {@code MISC} category, and only counting an entity whose chunk the level's
 * full-chunk getter answers for - the same filter vanilla applies to {@code level.getAllEntities()},</li>
 * <li>the number of the region's loaded chunks that a player is close enough to for spawning, which is what the
 * region owns of the level's spawnable chunks and is reported by {@link Built#spawnableChunks()}, and</li>
 * <li>the per-chunk spawning answers of the region (see {@link RegionSpawnNeighborhood}), so a spawn body of the
 * region reads neither the entity manager's visibility map nor the chunk map.</li>
 * </ul>
 * The mob cap the state enforces is the level-wide one, not a share of it: the state asks the
 * {@link LevelSpawnCapCounter} of the tick for the count of a category and reserves a slot there, so the vanilla
 * formula {@code maxInstancesPerChunk * levelSpawnableChunks / 289} decides whether the region may spawn. The area
 * term of the region is therefore not the area term of the cap; it is built and reported because it is the region's
 * own share of the level's spawnable chunks, and the regions of a level partition them.
 * <p>
 * Two differences against a level-wide state remain, and both are reported rather than hidden: the spawn potential
 * (the mob spawn cost) is summed over the region's entities instead of the level's, and the per-player cap counts the
 * mobs of the region a player is close to instead of the mobs of the level.
 * <p>
 * Everything here runs on the server thread: the entity collection, the chunk map reads and the full-chunk getter.
 * The built state and its answers are then only read (and its counters written) by the single worker that runs the
 * region, or by the server thread when the region could not be leased.
 */
@ApiStatus.Internal
public final class RegionSpawnState {
    private RegionSpawnState() {}

    /**
     * Builds the spawn state of one region.
     *
     * @param level           the level the state belongs to; must be the server thread
     * @param source          the server-owned reads of that level
     * @param regionChunks    the loaded chunks the region owns
     * @param regionEntities  the entities the region owns
     * @param handedChunkKeys the chunks this tick hands to the region's worker, whose reach the answers cover
     * @param capCounter      the level-wide mob count of this tick, which decides the cap of every region
     * @return the built state, the answers its bodies read and the region's own spawnable chunk count
     */
    public static Built build(ServerLevel level, RegionSpawnSource source, Collection<Long> regionChunks,
            Collection<Entity> regionEntities, Collection<Long> handedChunkKeys, LevelSpawnCapCounter capCounter) {
        final RegionSpawnNeighborhood neighborhood = new RegionSpawnNeighborhood();
        for (final long chunkKey : regionChunks) {
            neighborhood.recordChunk(level, source, chunkKey);
        }
        for (final long chunkKey : handedChunkKeys) {
            neighborhood.recordReach(level, source, chunkKey);
        }
        int spawnableChunks = 0;
        for (final long chunkKey : regionChunks) {
            if (source.spawnableChunk().test(chunkKey)) {
                ++spawnableChunks;
            }
        }
        final NaturalSpawner.SpawnState state = NaturalSpawner.createState(spawnableChunks, regionEntities,
                source.chunkGetter(), new RegionLocalMobCapCalculator(neighborhood::playersNear), capCounter);
        return new Built(neighborhood, state, spawnableChunks);
    }

    /**
     * One built region state.
     *
     * @param neighborhood    the per-chunk answers the region's spawn bodies read
     * @param state           the vanilla spawn state of the region, capped by the level-wide counter
     * @param spawnableChunks the number of the region's own chunks a player is close enough to for spawning, i.e.
     *                        the region's share of the level's spawnable chunks
     */
    public record Built(RegionSpawnNeighborhood neighborhood, NaturalSpawner.SpawnState state, int spawnableChunks) {}
}
