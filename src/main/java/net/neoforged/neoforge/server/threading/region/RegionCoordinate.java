/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stable key for a regionized chunk group in a single server level.
 */
public record RegionCoordinate(ServerLevel level, int regionX, int regionZ) {

    public static final int DEFAULT_REGION_CHUNK_SHIFT = RegionLayout.DYNAMIC_REGION_CHUNK_SHIFT;
    /**
     * {@return the legacy {@link RegionPolicy#DYNAMIC} region containing {@code pos}
     *
     * @deprecated use the {@link RegionLayout} of the active engine instead
     */
    @Deprecated
    public static RegionCoordinate fromBlock(ServerLevel level, BlockPos pos) {
        return RegionLayout.DYNAMIC.regionAt(level, pos);
    }

    /**
     * {@return the legacy {@link RegionPolicy#DYNAMIC} region containing the chunk
     *
     * @deprecated use the {@link RegionLayout} of the active engine instead
     */
    @Deprecated
    public static RegionCoordinate fromChunk(ServerLevel level, int chunkX, int chunkZ) {
        return RegionLayout.DYNAMIC.regionAt(level, chunkX, chunkZ);
    }
}
