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
 * The state is the vanilla {@link NaturalSpawner.SpawnState} built from what the region owns instead of from the
 * whole level:
 * <ul>
 * <li>the entities the region owns, by category, skipping persistent mobs and mobs of the {@code MISC} category,
 * and only counting an entity whose chunk the level's full-chunk getter answers for - the same filter vanilla
 * applies to {@code level.getAllEntities()},</li>
 * <li>the number of the region's loaded chunks that a player is close enough to for spawning, as the area term of
 * the cap instead of the level-wide natural spawn chunk count, and</li>
 * <li>the per-chunk spawning answers of the region (see {@link RegionSpawnNeighborhood}), so a spawn body of the
 * region reads neither the entity manager's visibility map nor the chunk map.</li>
 * </ul>
 * The mob cap it enforces is the vanilla formula {@code maxInstancesPerChunk * spawnableChunks / 289}. Because the
 * regions of a level partition its chunks, the sum of the region area terms is the level's own area term, so the
 * level-wide population bound is preserved. Two differences against a level-wide state remain, and both are
 * reported rather than hidden: each region truncates its cap to an integer ({@code 70 * 9 / 289} is 2 for a
 * 3x3-chunk region, so nine such regions allow 18 mobs where the level-wide cap allows 19), and the per-player cap
 * counts the mobs of the region a player is close to instead of the mobs of the level.
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
     * @return the built state, the answers its bodies read and the area term of its cap
     */
    public static Built build(ServerLevel level, RegionSpawnSource source, Collection<Long> regionChunks,
            Collection<Entity> regionEntities, Collection<Long> handedChunkKeys) {
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
                source.chunkGetter(), new RegionLocalMobCapCalculator(neighborhood::playersNear));
        return new Built(neighborhood, state, spawnableChunks);
    }

    /**
     * One built region state.
     *
     * @param neighborhood    the per-chunk answers the region's spawn bodies read
     * @param state           the vanilla spawn state of the region
     * @param spawnableChunks the area term of the region's mob cap, i.e. its spawnable chunk count
     */
    public record Built(RegionSpawnNeighborhood neighborhood, NaturalSpawner.SpawnState state, int spawnableChunks) {}
}
