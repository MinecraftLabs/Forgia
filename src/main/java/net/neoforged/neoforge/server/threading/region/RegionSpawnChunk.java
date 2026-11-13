/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.jetbrains.annotations.ApiStatus;

/**
 * One chunk's share of a level tick's natural spawning, as the region worker of its region executes it.
 * <p>
 * The entry carries the chunk the server thread decided may spawn and the seed of the random source its spawn
 * body draws from. The seed is a pure function of the world seed, the dimension, the game time, the region
 * coordinate and the chunk position ({@link #spawnSeed(ServerLevel, RegionCoordinate, long)}), so a chunk's
 * spawn sequence is per-chunk deterministic: it does not depend on which other chunks spawn in the same tick, on
 * the region layout of the other regions, on the number of workers or on the thread that runs the chunk. That is
 * the property the slice needs, because the same chunk may spawn on a worker in one tick and on the server thread
 * in the next when its region cannot be leased.
 * <p>
 * Not provided: this is not the vanilla sequence. Vanilla spawns every chunk of a level from one level-wide
 * {@link RandomSource}, so a chunk's spawns there depend on every chunk spawned before it in the same tick - on
 * the shuffle, on the number of level chunks and on every spawn attempt of the chunks before it. The regionized
 * slice keeps the distribution of every draw (each source is a {@code LegacyRandomSource} seeded per chunk) but
 * not the order of the vanilla sequence, and a comparison of two runs can only compare populations and rates.
 */
@ApiStatus.Internal
public record RegionSpawnChunk(long chunkKey, LevelChunk chunk) {
    /**
     * {@return the entry of {@code chunk}}
     */
    public static RegionSpawnChunk of(LevelChunk chunk) {
        return new RegionSpawnChunk(chunk.getPos().toLong(), chunk);
    }

    /**
     * {@return the seed of the random source of {@code chunkKey}'s spawn body in the current level tick}
     * <p>
     * The region part is the region random of the tick ({@link RegionTickBatch#regionRandomSeed}), so two
     * chunks of one region of one tick draw from the same region seed, and the chunk position finalizes it, so
     * their sequences differ. Every input passes through the splitmix64 finalizer, so a step of one tick, of one
     * region or of one chunk changes every bit of the {@code LegacyRandomSource} seed instead of only its low
     * bits. The dimension is part of the derivation because two dimensions of one server share the world seed
     * and the game time, and the region coordinate is part of it because two regions of one dimension share the
     * world seed, the game time and the dimension.
     */
    public static long spawnSeed(ServerLevel level, RegionCoordinate coordinate, long chunkKey) {
        return RandomSupport.mixStafford13(RegionTickBatch.regionRandomSeed(level, coordinate) ^ chunkKey);
    }

    /** {@return the region coordinate of this chunk under {@code layout}} */
    public RegionCoordinate region(RegionLayout layout, ServerLevel level) {
        return layout.regionAt(level, ChunkPos.getX(this.chunkKey), ChunkPos.getZ(this.chunkKey));
    }

    /**
     * {@return the random source this chunk's spawn body draws from}
     * <p>
     * The spawn body of {@code NaturalSpawner} reads {@code level.random} for every draw - the start position, the
     * candidate walk, the cluster size, the spawn roll and the checks of the spawn rules - so the group installs
     * this source for the chunk and the level random wrapper answers with it.
     */
    public RandomSource random(ServerLevel level, RegionCoordinate coordinate) {
        return RandomSource.create(spawnSeed(level, coordinate, this.chunkKey));
    }
}
