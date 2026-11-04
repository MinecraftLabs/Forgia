/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.neoforged.neoforge.common.NeoForgeConfig;

/**
 * Reads region threading settings, using the declared defaults until the server config is loaded.
 */
final class RegionThreadingConfig {
    private RegionThreadingConfig() {}

    static int maxImmediateTasksPerDrain() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.get()
                : NeoForgeConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.getDefault();
    }

    static int maxScheduledTasksPerDrain() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.get()
                : NeoForgeConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.getDefault();
    }

    static long longTickWarningNanos() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.regionThreadingLongTickWarningNanos.get()
                : NeoForgeConfig.SERVER.regionThreadingLongTickWarningNanos.getDefault();
    }

    /**
     * {@return true when a level created now partitions its POI storage per region}
     * <p>
     * The dedicated switch is off by default, and enabling the entity worker slice implies the storage: a
     * worker tick of a villager, iron golem, raider, bee, zombie or fox reaches the level's POI manager, and
     * the regionized storage is what makes that tick safe next to the server thread. A configuration that
     * enables entity worker ticks therefore gets the storage even with the dedicated switch off, instead of
     * silently running those ticks against the one shared vanilla manager.
     */
    static boolean poiStorageEnabled() {
        if (!NeoForgeConfig.isServerConfigLoaded()) {
            return false;
        }
        return NeoForgeConfig.SERVER.enableRegionThreadingPoi.get() || NeoForgeConfig.SERVER.enableRegionThreadingEntityTicks.get();
    }

    /**
     * {@return true when a region context captures the neighbor updates that target another region}
     * <p>
     * The switch is off by default. When it is off the region context uses the vanilla collecting updater and
     * the level write paths keep their vanilla behavior, so the feature costs nothing on a server that does not
     * enable it.
     */
    static boolean borderUpdatesEnabled() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                : NeoForgeConfig.SERVER.enableRegionThreadingBorderUpdates.getDefault();
    }

    static NeoForgeConfig.RegionThreadingOwnershipViolationMode ownershipViolationMode() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                : NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.getDefault();
    }
}
