/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Classification of the tick rate a region observed in its metric window.
 * <p>
 * The classification only depends on the observed rate of {@link RegionTickSnapshot#ticksPerSecond()}
 * against the server target of 20 ticks per second, so it stays meaningful before regions tick
 * independently: a region that cannot deliver a tick per server tick classifies as
 * {@link #BEHIND} exactly like a region that ticks on its own schedule and misses one.
 * A region whose window has fewer than two closed ticks has no observable rate yet and is
 * {@link #OK}; a freshly installed or idle region therefore never reports itself as behind.
 */
public enum RegionTickStatus {
    /** The region kept up with the 20 ticks per second target in its metric window. */
    OK("ok"),
    /** The region fell below the target rate in its metric window. */
    BEHIND("behind");

    private final String label;

    RegionTickStatus(String label) {
        this.label = label;
    }

    /**
     * {@return the lower-case label used by the region threading command}
     */
    public String label() {
        return this.label;
    }
}
