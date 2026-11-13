/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Aggregate of the per-region metrics of one {@link RegionThreadingDiagnostics} snapshot.
 * <p>
 * {@code msptTotalPerTick} is the total region work of one server tick: the sum of the mean MSPT of every
 * live region, i.e. the part of the tick budget the region threads spend on their own work. A region that
 * was not leased in a tick contributes {@code 0}, so an idle region never inflates the total.
 *
 * @param regionCount      number of live regions the summary was computed from
 * @param regionsBehind    number of those regions classified {@link RegionTickStatus#BEHIND}
 * @param msptAvg          mean region MSPT over all live regions, {@code 0} when there is none
 * @param msptTotalPerTick sum of the mean region MSPT of all live regions
 * @param worstRegion      live region with the highest mean MSPT, {@code null} when no live region closed
 *                         a tick in its window yet
 * @param windowTicks      number of server ticks one region window holds
 */
@ApiStatus.Internal
public record RegionMetricsSummary(
        int regionCount,
        int regionsBehind,
        double msptAvg,
        double msptTotalPerTick,
        @Nullable RegionTickSnapshot worstRegion,
        int windowTicks) {
    /**
     * {@return the summary of {@code regions}}
     * <p>
     * Each region contributes its own mean MSPT, so the total is the region work of one server tick and the
     * mean is that total spread over the live regions. Regions without a closed tick contribute {@code 0}
     * and are never the worst region.
     */
    public static RegionMetricsSummary summarize(List<RegionTickSnapshot> regions) {
        int behind = 0;
        double total = 0.0D;
        RegionTickSnapshot worst = null;
        for (final RegionTickSnapshot region : regions) {
            total += region.msptAvg();
            if (region.status() == RegionTickStatus.BEHIND) {
                ++behind;
            }
            if (region.hasSamples() && (worst == null || region.msptAvg() > worst.msptAvg())) {
                worst = region;
            }
        }
        final double average = regions.isEmpty() ? 0.0D : total / regions.size();
        return new RegionMetricsSummary(regions.size(), behind, average, total, worst, RegionTickMetrics.WINDOW_TICKS);
    }

    /**
     * {@return an empty summary, used when no region threading engine is installed}
     */
    public static RegionMetricsSummary empty() {
        return new RegionMetricsSummary(0, 0, 0.0D, 0.0D, null, RegionTickMetrics.WINDOW_TICKS);
    }
}
