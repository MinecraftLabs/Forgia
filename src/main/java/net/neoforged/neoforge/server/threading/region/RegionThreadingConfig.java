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

    static NeoForgeConfig.RegionThreadingOwnershipViolationMode ownershipViolationMode() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                : NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.getDefault();
    }
}
