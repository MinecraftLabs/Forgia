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
     * Non-overlapping global 3x3-chunk grid. Regions never merge, split, or change coordinates.
     */
    FIXED_3X3
}
