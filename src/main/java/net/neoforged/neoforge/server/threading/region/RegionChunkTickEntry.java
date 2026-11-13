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
 * One chunk's share of a level tick's chunk tick, as the region worker of its region executes it.
 * <p>
 * The entry carries the chunk the server thread decided to tick and the seed of the random source its body
 * draws from. The seed is a pure function of the world seed, the game time and the chunk position
 * ({@link #randomSeed(ServerLevel, long)}), so the chunk's selection is per-chunk deterministic: it does not
 * depend on which other chunks tick, on the region layout, on the number of workers or on the thread that
 * runs the chunk. That is the property the region slice needs, because the same chunk may be ticked by a
 * worker in one tick and by the server thread in the next when its region cannot be leased.
 * <p>
 * Not provided: this is not the vanilla sequence. Vanilla selects the position of a random tick from one
 * level-wide {@code randValue} and draws the thunder and precipitation rolls from one level-wide
 * {@link RandomSource}, so a chunk's selection there depends on every chunk ticked before it in the same
 * tick.
 */
@ApiStatus.Internal
public record RegionChunkTickEntry(long chunkKey, LevelChunk chunk) {
    /**
     * {@return the entry of {@code chunk}}
     */
    static RegionChunkTickEntry of(LevelChunk chunk) {
        return new RegionChunkTickEntry(chunk.getPos().toLong(), chunk);
    }

    /**
     * {@return the seed of the random source of {@code chunkKey}'s chunk tick in the current level tick}
     * <p>
     * Every input passes through the splitmix64 finalizer, so a step of one tick or of one chunk changes every
     * bit of the {@code LegacyRandomSource} seed instead of only its low bits. The dimension is part of the
     * derivation for the same reason it is part of the group random seed: two dimensions of one server share
     * the world seed and the game time.
     */
    public static long randomSeed(ServerLevel level, long chunkKey) {
        long seed = RandomSupport.mixStafford13(level.getSeed());
        seed = RandomSupport.mixStafford13(seed ^ level.getGameTime());
        seed = RandomSupport.mixStafford13(seed ^ level.dimension().location().hashCode());
        return RandomSupport.mixStafford13(seed ^ chunkKey);
    }

    /** {@return the position of the chunk this entry ticks} */
    public ChunkPos chunkPos() {
        return new ChunkPos(this.chunkKey);
    }

    /** {@return the region coordinate of this chunk under {@code layout}} */
    RegionCoordinate region(RegionLayout layout, ServerLevel level) {
        return layout.regionAt(level, ChunkPos.getX(this.chunkKey), ChunkPos.getZ(this.chunkKey));
    }

    /** {@return the random source this chunk's tick draws from} */
    RandomSource random(ServerLevel level) {
        return RandomSource.create(randomSeed(level, this.chunkKey));
    }
}
