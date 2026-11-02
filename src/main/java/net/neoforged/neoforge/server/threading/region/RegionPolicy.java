/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Selects how chunks are mapped to region coordinates.
 */
public enum RegionPolicy {
    /**
     * Legacy 16x16-chunk grid with dynamic merge and split recalculation.
     */
    DYNAMIC,
    /**
     * Non-overlapping global fixed-chunk grid. Regions never merge, split, or change coordinates.
     * <p>
     * The name is the historical size-3 default: the edge length of one region is the
     * {@code regionThreadingFixedRegionChunks} server config value, which defaults to 3. Keeping this
     * value means an existing config file keeps the fixed policy and only the size changes.
     */
    FIXED_3X3
}
