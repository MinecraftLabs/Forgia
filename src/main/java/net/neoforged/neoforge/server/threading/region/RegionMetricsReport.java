/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

/**
 * Text form of the per-region metrics for the region threading command.
 * <p>
 * The formatting lives next to the snapshot records so tests can assert the exact command output without a
 * command source, and so the command itself only sends the lines. Every line is plain text: a script can
 * match the {@code key=value} fields of the summary line and the ranked listing independently.
 * <p>
 * The listing ranks by {@link RegionTickSnapshot#msptP99()}, the worst one percent of the region's ticks,
 * which is the number that shows a region that is slow only every now and then. Ties are broken by the mean
 * MSPT and then by coordinate, so the order is stable for equal regions.
 */
@ApiStatus.Internal
public final class RegionMetricsReport {
    private RegionMetricsReport() {}

    /**
     * {@return the aggregate line of the per-region metrics}
     */
    public static String summaryLine(RegionMetricsSummary summary) {
        final String worst = summary.worstRegion() == null
                ? "none"
                : summary.worstRegion().describeRegion() + " avgMspt=" + millis(summary.worstRegion().msptAvg());
        return "Region metrics: windowTicks=" + summary.windowTicks()
                + ", regions=" + summary.regionCount()
                + ", behind=" + summary.regionsBehind()
                + ", avgMspt=" + millis(summary.msptAvg())
                + ", totalMsptPerTick=" + millis(summary.msptTotalPerTick())
                + ", worst=" + worst;
    }

    /**
     * {@return the report of a server that runs without region threading}
     * <p>
     * There is no region state and no engine recording group durations, so no region metrics exist and no
     * per-tick metric work happens at all.
     */
    public static String notInstalledLine() {
        return "Region metrics: no active regions, no per-region tick metrics are collected (region threading is not installed)";
    }

    /**
     * {@return the ranked listing of the {@code limit} slowest regions of {@code regions}}
     * <p>
     * Regions that did not close a tick in their window yet are left out: they have no measured MSPT and
     * their classification carries no information.
     */
    public static List<String> listingLines(List<RegionTickSnapshot> regions, int limit) {
        final List<RegionTickSnapshot> ranked = new ArrayList<>();
        for (final RegionTickSnapshot region : regions) {
            if (region.hasSamples()) {
                ranked.add(region);
            }
        }
        ranked.sort(RegionMetricsReport::compareByP99);
        final int shown = Math.min(Math.max(limit, 0), ranked.size());
        final List<String> lines = new ArrayList<>(shown + 2);
        lines.add("Region metrics by p99: showing " + shown + " of " + regions.size() + " live regions");
        for (int index = 0; index < shown; ++index) {
            final RegionTickSnapshot region = ranked.get(index);
            lines.add("  #" + (index + 1)
                    + " p99=" + millis(region.msptP99())
                    + "ms avg=" + millis(region.msptAvg())
                    + "ms max=" + millis(region.msptMax()) + "ms"
                    + " samples=" + region.samples()
                    + " tps=" + ticksPerSecond(region.ticksPerSecond())
                    + " missed=" + region.missedTicks()
                    + " " + region.describeRegion()
                    + " " + region.status().label());
        }
        if (shown == 0) {
            lines.add("  no live region closed a server tick in its window yet");
        }
        return List.copyOf(lines);
    }

    private static int compareByP99(RegionTickSnapshot left, RegionTickSnapshot right) {
        int result = Double.compare(right.msptP99(), left.msptP99());
        if (result == 0) {
            result = Double.compare(right.msptAvg(), left.msptAvg());
        }
        if (result == 0) {
            result = left.dimension().compareTo(right.dimension());
        }
        if (result == 0) {
            result = Integer.compare(left.regionX(), right.regionX());
        }
        if (result == 0) {
            result = Integer.compare(left.regionZ(), right.regionZ());
        }
        return result;
    }

    private static String millis(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String ticksPerSecond(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
