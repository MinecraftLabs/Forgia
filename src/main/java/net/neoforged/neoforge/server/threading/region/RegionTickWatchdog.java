/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class RegionTickWatchdog {
    private static final Logger LOGGER = LogManager.getLogger();

    private RegionTickWatchdog() {}

    static void warnIfLongTick(String name, long durationNanos) {
        final long threshold = RegionThreadingConfig.longTickWarningNanos();
        if (threshold > 0L && durationNanos > threshold) {
            LOGGER.warn("Region threading {} drain took {} ns", name, durationNanos);
        }
    }
}
