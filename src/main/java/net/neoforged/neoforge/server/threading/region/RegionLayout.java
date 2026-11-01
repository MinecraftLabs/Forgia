/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Immutable chunk-to-region mapping for one {@link RegionPolicy}.
 * <p>
 * {@link RegionPolicy#DYNAMIC} keeps the legacy 16x16-chunk grid with merge and split recalculation.
 * {@link RegionPolicy#FIXED_3X3} maps every chunk to the non-overlapping global 3x3-chunk grid using
 * {@link Math#floorDiv(int, int)}, so negative chunks map to the region that actually contains them
 * (chunk {@code -1} and {@code -3} belong to region {@code -1}, chunk {@code -4} to region {@code -2}).
 * Fixed regions never merge, split, or change their coordinates.
 */
public final class RegionLayout {
    public static final int DYNAMIC_REGION_CHUNK_SHIFT = 4;
    public static final int FIXED_3X3_REGION_CHUNK_SIZE = 3;
    public static final RegionLayout DYNAMIC = new RegionLayout(RegionPolicy.DYNAMIC);
    public static final RegionLayout FIXED_3X3 = new RegionLayout(RegionPolicy.FIXED_3X3);

    private final RegionPolicy policy;

    private RegionLayout(RegionPolicy policy) {
        this.policy = policy;
    }

    public static RegionLayout of(RegionPolicy policy) {
        return switch (policy) {
            case DYNAMIC -> DYNAMIC;
            case FIXED_3X3 -> FIXED_3X3;
        };
    }

    public RegionPolicy policy() {
        return this.policy;
    }

    public boolean isFixed() {
        return this.policy == RegionPolicy.FIXED_3X3;
    }

    public int regionX(int chunkX) {
        return this.isFixed() ? Math.floorDiv(chunkX, FIXED_3X3_REGION_CHUNK_SIZE) : chunkX >> DYNAMIC_REGION_CHUNK_SHIFT;
    }

    public int regionZ(int chunkZ) {
        return this.isFixed() ? Math.floorDiv(chunkZ, FIXED_3X3_REGION_CHUNK_SIZE) : chunkZ >> DYNAMIC_REGION_CHUNK_SHIFT;
    }

    public RegionCoordinate regionAt(ServerLevel level, int chunkX, int chunkZ) {
        return new RegionCoordinate(level, this.regionX(chunkX), this.regionZ(chunkZ));
    }

    public RegionCoordinate regionAt(ServerLevel level, BlockPos pos) {
        return this.regionAt(level, Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }
}
