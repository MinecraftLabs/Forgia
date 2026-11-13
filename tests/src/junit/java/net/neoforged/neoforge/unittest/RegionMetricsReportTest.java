/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionMetricsReport;
import net.neoforged.neoforge.server.threading.region.RegionMetricsSummary;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickMetrics;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import net.neoforged.neoforge.server.threading.region.RegionTickStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests of the per-region metrics the region threading command reports: the aggregate line, the ranked
 * listing and the behaviour without installed region threading.
 */
@Timeout(20)
class RegionMetricsReportTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
    }

    @Test
    void summaryLineReportsTheAggregateOfTheLiveRegions() {
        final List<RegionTickSnapshot> regions = List.of(
                snapshot(OVERWORLD, 0, 0, 10, 2.0D, 2.5D, 4.0D, 5.0D, 1.5D, 20.0D, 0, RegionTickStatus.OK),
                snapshot(OVERWORLD, 1, 0, 10, 6.0D, 7.0D, 9.0D, 12.0D, 5.0D, 10.0D, 9, RegionTickStatus.BEHIND),
                snapshot(OVERWORLD, 2, 0, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, RegionTickStatus.OK));

        final RegionMetricsSummary summary = RegionMetricsSummary.summarize(regions);

        assertEquals(3, summary.regionCount());
        assertEquals(1, summary.regionsBehind());
        assertEquals(8.0D / 3.0D, summary.msptAvg(), 1.0e-9D);
        assertEquals(8.0D, summary.msptTotalPerTick(), 1.0e-9D, "the total region work of one server tick");
        assertEquals(6.0D, summary.worstRegion().msptAvg(), 1.0e-9D);
        assertEquals(RegionTickMetrics.WINDOW_TICKS, summary.windowTicks());
        assertEquals("Region metrics: windowTicks=100, regions=3, behind=1, avgMspt=2.667, totalMsptPerTick=8.000,"
                + " worst=minecraft:overworld@1,0 avgMspt=6.000", RegionMetricsReport.summaryLine(summary));
    }

    @Test
    void listingRanksTheSlowestRegionsByP99First() {
        final List<RegionTickSnapshot> regions = List.of(
                snapshot(OVERWORLD, -3, 7, 100, 1.0D, 2.0D, 3.0D, 4.0D, 1.0D, 20.0D, 0, RegionTickStatus.OK),
                snapshot("minecraft:the_nether", 2, 2, 42, 4.0D, 6.0D, 9.0D, 11.0D, 3.0D, 10.0D, 21, RegionTickStatus.BEHIND),
                snapshot(OVERWORLD, 5, -5, 7, 2.0D, 3.0D, 6.0D, 8.0D, 2.5D, 19.0D, 0, RegionTickStatus.BEHIND));

        final List<String> lines = RegionMetricsReport.listingLines(regions, 2);

        assertEquals("Region metrics by p99: showing 2 of 3 live regions", lines.get(0));
        assertEquals("  #1 p99=9.000ms avg=4.000ms max=11.000ms samples=42 tps=10.0 missed=21"
                + " minecraft:the_nether@2,2 behind", lines.get(1));
        assertEquals("  #2 p99=6.000ms avg=2.000ms max=8.000ms samples=7 tps=19.0 missed=0"
                + " minecraft:overworld@5,-5 behind", lines.get(2));
        assertEquals(3, lines.size());
    }

    @Test
    void listingSkipsRegionsThatDidNotCloseATickYet() {
        final List<String> lines = RegionMetricsReport.listingLines(
                List.of(snapshot(OVERWORLD, 0, 0, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, RegionTickStatus.OK)), 10);

        assertEquals("Region metrics by p99: showing 0 of 1 live regions", lines.get(0));
        assertEquals("  no live region closed a server tick in its window yet", lines.get(1));
    }

    @Test
    void withoutAnInstalledEngineTheReportSaysNothingIsCollected() {
        final String line = RegionMetricsReport.notInstalledLine();

        assertTrue(line.contains("no active regions"), line);
        assertTrue(line.contains("no per-region tick metrics are collected"), line);
    }

    @Test
    void engineWithoutRegionsReportsNoRegionMetrics() {
        this.engine = new RegionThreadingEngine(1, false, false);

        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();

        assertEquals(0, diagnostics.regionMetrics().regionCount(), "no live region means no region metrics");
        assertEquals(0.0D, diagnostics.regionMetrics().msptTotalPerTick());
        assertNull(diagnostics.regionMetrics().worstRegion());
        assertTrue(diagnostics.regionTicks().isEmpty());
        assertEquals("Region metrics: windowTicks=100, regions=0, behind=0, avgMspt=0.000, totalMsptPerTick=0.000,"
                + " worst=none", RegionMetricsReport.summaryLine(diagnostics.regionMetrics()));
        assertEquals("  no live region closed a server tick in its window yet",
                RegionMetricsReport.listingLines(diagnostics.regionTicks(), 10).getLast());
    }

    @Test
    void diagnosticsListsEveryLiveRegionWithItsCoordinateDimensionAndSamples() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameTime()).thenReturn(1L);
        this.engine.regionScheduler().runDelayed(level, BlockPos.ZERO, () -> {}, 100L);
        this.engine.regionScheduler().runDelayed(level, new BlockPos(300, 64, 0), () -> {}, 100L);
        // One level pass opens the metric slot of server tick 1, the next pass closes it again.
        this.engine.drainLevelRegionTasks(level);
        when(level.getGameTime()).thenReturn(2L);
        this.engine.drainLevelRegionTasks(level);

        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();

        assertEquals(2, diagnostics.regionTicks().size(), "every live region reports its own metrics");
        assertEquals(2, diagnostics.regionMetrics().regionCount());
        assertEquals(0, diagnostics.regionMetrics().regionsBehind());
        for (final RegionTickSnapshot region : diagnostics.regionTicks()) {
            assertEquals(OVERWORLD, region.dimension());
            assertEquals(1, region.samples(), "the closed server tick is one sample");
            assertEquals(0, region.missedTicks());
            assertEquals(RegionTickStatus.OK, region.status(), "a single sample has no observable rate yet");
        }
        assertTrue(RegionMetricsReport.summaryLine(diagnostics.regionMetrics()).contains("regions=2"));
        assertEquals(2, RegionMetricsReport.listingLines(diagnostics.regionTicks(), 10).size() - 1,
                "both regions are listed below the header line");
        assertThrows(UnsupportedOperationException.class,
                () -> diagnostics.regionTicks().add(diagnostics.regionTicks().getFirst()),
                "the region metrics of a snapshot have to be immutable");
    }

    private static RegionTickSnapshot snapshot(String dimension, int regionX, int regionZ, int samples,
            double msptAvg, double msptP95, double msptP99, double msptMax, double msptLast,
            double ticksPerSecond, int missedTicks, RegionTickStatus status) {
        return new RegionTickSnapshot(regionX, regionZ, dimension, samples, msptAvg, msptP95, msptP99, msptMax,
                msptLast, ticksPerSecond, missedTicks, status);
    }
}
