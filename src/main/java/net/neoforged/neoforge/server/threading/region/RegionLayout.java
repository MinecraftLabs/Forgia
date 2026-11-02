/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Immutable chunk-to-region mapping for one {@link RegionPolicy} and one region edge length in chunks.
 * <p>
 * {@link RegionPolicy#DYNAMIC} keeps the legacy 16x16-chunk grid with merge and split recalculation.
 * {@link RegionPolicy#FIXED_3X3} maps every chunk to the non-overlapping global grid whose edge length is
 * {@link #regionChunkSize()} using {@link Math#floorDiv(int, int)}, so negative chunks map to the region
 * that actually contains them (for an edge length of 3, chunk {@code -1} and {@code -3} belong to region
 * {@code -1}, chunk {@code -4} to region {@code -2}). Fixed regions never merge, split, or change their
 * coordinates.
 * <p>
 * The fixed edge length is the single source of truth of the fixed region size: the engine, the
 * regionizer, the physical section partitions and the diagnostics all take it from one instance. The
 * instance is created from {@code regionThreadingFixedRegionChunks} at world start, so
 * {@link #FIXED_3X3} is only the size-3 default instance. An edge length outside
 * {@value #MIN_FIXED_REGION_CHUNK_SIZE}..{@value #MAX_FIXED_REGION_CHUNK_SIZE} is rejected with an
 * {@link IllegalArgumentException} instead of being clamped, so a misconfigured caller never ticks and
 * partitions with a size it did not ask for. The config itself clamps an out-of-range value into that
 * range when it loads, so the layout only sees in-range values from the server lifecycle.
 */
public final class RegionLayout {
    public static final int DYNAMIC_REGION_CHUNK_SHIFT = 4;
    /** Edge length in chunks of the legacy dynamic grid. */
    public static final int DYNAMIC_REGION_CHUNK_SIZE = 1 << DYNAMIC_REGION_CHUNK_SHIFT;
    /** Smallest accepted fixed region edge length in chunks. */
    public static final int MIN_FIXED_REGION_CHUNK_SIZE = 3;
    /** Largest accepted fixed region edge length in chunks. */
    public static final int MAX_FIXED_REGION_CHUNK_SIZE = 32;
    /** Edge length in chunks of the default fixed layout; the default of {@code regionThreadingFixedRegionChunks}. */
    public static final int FIXED_3X3_REGION_CHUNK_SIZE = 3;
    public static final RegionLayout DYNAMIC = new RegionLayout(RegionPolicy.DYNAMIC, DYNAMIC_REGION_CHUNK_SIZE);
    public static final RegionLayout FIXED_3X3 = new RegionLayout(RegionPolicy.FIXED_3X3, FIXED_3X3_REGION_CHUNK_SIZE);

    private final RegionPolicy policy;
    private final int regionChunkSize;

    private RegionLayout(RegionPolicy policy, int regionChunkSize) {
        this.policy = policy;
        this.regionChunkSize = regionChunkSize;
    }

    /**
     * {@return the layout of {@code policy} with the default fixed edge length}
     */
    public static RegionLayout of(RegionPolicy policy) {
        return switch (policy) {
            case DYNAMIC -> DYNAMIC;
            case FIXED_3X3 -> FIXED_3X3;
        };
    }

    /**
     * {@return the layout of {@code policy} with the configured fixed edge length}
     * <p>
     * {@code fixedRegionChunkSize} is ignored by {@link RegionPolicy#DYNAMIC}, whose grid edge is fixed by
     * the legacy regionizer.
     */
    public static RegionLayout of(RegionPolicy policy, int fixedRegionChunkSize) {
        return switch (policy) {
            case DYNAMIC -> DYNAMIC;
            case FIXED_3X3 -> fixed(fixedRegionChunkSize);
        };
    }

    /**
     * {@return a fixed layout with an edge length of {@code regionChunkSize} chunks}
     *
     * @throws IllegalArgumentException when {@code regionChunkSize} is outside
     *                                  {@value #MIN_FIXED_REGION_CHUNK_SIZE}..{@value #MAX_FIXED_REGION_CHUNK_SIZE}
     */
    public static RegionLayout fixed(int regionChunkSize) {
        if (regionChunkSize < MIN_FIXED_REGION_CHUNK_SIZE || regionChunkSize > MAX_FIXED_REGION_CHUNK_SIZE) {
            throw new IllegalArgumentException("Fixed region chunk size must be in "
                    + MIN_FIXED_REGION_CHUNK_SIZE + ".." + MAX_FIXED_REGION_CHUNK_SIZE + ": " + regionChunkSize);
        }
        return regionChunkSize == FIXED_3X3_REGION_CHUNK_SIZE ? FIXED_3X3 : new RegionLayout(RegionPolicy.FIXED_3X3, regionChunkSize);
    }

    public RegionPolicy policy() {
        return this.policy;
    }

    public boolean isFixed() {
        return this.policy == RegionPolicy.FIXED_3X3;
    }

    /**
     * {@return the edge length in chunks of one region coordinate}
     * <p>
     * For a fixed layout this is the configured fixed region size. {@link RegionPolicy#DYNAMIC} answers the
     * edge of the legacy 16x16-chunk grid, whose regions may still merge into larger shapes.
     */
    public int regionChunkSize() {
        return this.regionChunkSize;
    }

    public int regionX(int chunkX) {
        return this.isFixed() ? Math.floorDiv(chunkX, this.regionChunkSize) : chunkX >> DYNAMIC_REGION_CHUNK_SHIFT;
    }

    public int regionZ(int chunkZ) {
        return this.isFixed() ? Math.floorDiv(chunkZ, this.regionChunkSize) : chunkZ >> DYNAMIC_REGION_CHUNK_SHIFT;
    }

    public RegionCoordinate regionAt(ServerLevel level, int chunkX, int chunkZ) {
        return new RegionCoordinate(level, this.regionX(chunkX), this.regionZ(chunkZ));
    }

    public RegionCoordinate regionAt(ServerLevel level, BlockPos pos) {
        return this.regionAt(level, Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }
}
