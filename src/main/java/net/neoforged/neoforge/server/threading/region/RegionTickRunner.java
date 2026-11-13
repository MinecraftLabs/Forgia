/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Executes due work for a region state.
 */
final class RegionTickRunner {
    boolean runIfDue(RegionState region, long nowNanos, Runnable drainTask) {
        synchronized (region) {
            if (!region.tickTimer().isDue(nowNanos)) {
                return false;
            }

            region.tickTimer().markTickStarted(nowNanos);
        }
        final long start = System.nanoTime();
        drainTask.run();
        final long duration = System.nanoTime() - start;
        region.tickMetrics().recordTick(duration);
        RegionTickWatchdog.warnIfLongTick("region " + region.coordinate(), duration);
        return true;
    }
}
