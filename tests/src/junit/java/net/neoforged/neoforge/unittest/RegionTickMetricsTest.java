/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickMetrics;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import net.neoforged.neoforge.server.threading.region.RegionTickStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests of the per-region metric window behind the region MSPT and region TPS diagnostics.
 */
@Timeout(20)
class RegionTickMetricsTest {
    private static final long TICK_NANOS = 50_000_000L;
    private static final long MILLIS_IN_NANOS = 1_000_000L;
    private static final long FIRST_TICK_INDEX = 100L;
    private static final long FIRST_TICK_NANOS = 1_000_000_000L;
    private static final String OVERWORLD = "minecraft:overworld";

    private final List<RegionTickMetrics> windows = new ArrayList<>();
    private RegionThreadingEngine engine;

    @AfterEach
    void releaseWindows() {
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
        for (final RegionTickMetrics metrics : this.windows) {
            metrics.release();
        }
        this.windows.clear();
    }

    @Test
    void windowReportsPercentilesOfTheKnownTickDurations() {
        final RegionTickMetrics metrics = newMetrics();
        recordTicks(metrics, TICK_NANOS, 1, 1.0D, 2.0D, 3.0D, 4.0D, 5.0D, 6.0D, 7.0D, 8.0D, 9.0D, 10.0D,
                11.0D, 12.0D, 13.0D, 14.0D, 15.0D, 16.0D, 17.0D, 18.0D, 19.0D, 20.0D);

        final RegionTickSnapshot snapshot = metrics.snapshot(OVERWORLD, 4, -2);

        assertEquals(20, snapshot.samples(), "every closed server tick of the window is one sample");
        assertEquals(10.5D, snapshot.msptAvg(), 1.0e-9D, "the mean of the 1..20 ms ticks");
        assertEquals(19.0D, snapshot.msptP95(), 1.0e-9D, "the nearest-rank 95th percentile of 20 samples");
        assertEquals(20.0D, snapshot.msptP99(), 1.0e-9D, "the nearest-rank 99th percentile of 20 samples");
        assertEquals(20.0D, snapshot.msptMax(), 1.0e-9D);
        assertEquals(20.0D, snapshot.msptLast(), 1.0e-9D, "the newest closed tick is the last one recorded");
        assertEquals(20.0D, snapshot.ticksPerSecond(), 1.0e-6D, "one tick per 50 ms is the 20 TPS target");
        assertEquals(0, snapshot.missedTicks());
        assertEquals(RegionTickStatus.OK, snapshot.status());
        assertEquals(OVERWORLD + "@4,-2", snapshot.describeRegion());
    }

    @Test
    void windowKeepsOnlyTheNewestTicks() {
        final RegionTickMetrics metrics = newMetrics();
        final double[] durations = new double[150];
        for (int index = 0; index < durations.length; ++index) {
            durations[index] = index + 1.0D;
        }
        recordTicks(metrics, TICK_NANOS, 1, durations);

        final RegionTickSnapshot snapshot = metrics.snapshot(OVERWORLD, 0, 0);

        assertEquals(RegionTickMetrics.WINDOW_TICKS, snapshot.samples(), "the window is bounded, never unbounded history");
        assertEquals(100.5D, snapshot.msptAvg(), 1.0e-9D, "the mean of the surviving 51..150 ms ticks");
        assertEquals(145.0D, snapshot.msptP95(), 1.0e-9D, "the 95th percentile of the surviving ticks");
        assertEquals(149.0D, snapshot.msptP99(), 1.0e-9D, "the 99th percentile of the surviving ticks");
        assertEquals(150.0D, snapshot.msptMax(), 1.0e-9D);
        assertEquals(150.0D, snapshot.msptLast(), 1.0e-9D);
        assertEquals(20.0D, snapshot.ticksPerSecond(), 1.0e-6D);
    }

    @Test
    void ticksWithoutLeasedWorkContributeZeroAndAreNotMissed() {
        final RegionTickMetrics metrics = newMetrics();
        recordTicks(metrics, TICK_NANOS, 1, 2.0D, 2.0D, 2.0D, 2.0D,
                -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D,
                -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D);

        final RegionTickSnapshot snapshot = metrics.snapshot(OVERWORLD, 1, 1);

        assertEquals(20, snapshot.samples(), "an unleased server tick still closes its slot");
        assertEquals(0.4D, snapshot.msptAvg(), 1.0e-9D, "16 unleased ticks contribute 0 ms to the 20-tick window");
        assertEquals(2.0D, snapshot.msptMax(), 1.0e-9D);
        assertEquals(0.0D, snapshot.msptLast(), 1.0e-9D);
        assertEquals(0, snapshot.missedTicks(), "a tick without leased work is not a missed tick");
        assertEquals(20.0D, snapshot.ticksPerSecond(), 1.0e-6D);
        assertEquals(RegionTickStatus.OK, snapshot.status(), "an idle region is not behind");
    }

    @Test
    void regionThatMissesTicksReportsLowerRateAndBehind() {
        final RegionTickMetrics behind = newMetrics();
        recordTicks(behind, 2L * TICK_NANOS, 2, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D,
                1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D);
        final RegionTickMetrics keepingUp = newMetrics();
        recordTicks(keepingUp, TICK_NANOS, 1, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D,
                1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D, 1.0D);

        final RegionTickSnapshot behindSnapshot = behind.snapshot(OVERWORLD, 0, 0);
        final RegionTickSnapshot keepingUpSnapshot = keepingUp.snapshot(OVERWORLD, 0, 0);

        assertEquals(10.0D, behindSnapshot.ticksPerSecond(), 1.0e-6D, "one delivery per 100 ms is half the target");
        assertEquals(RegionTickStatus.BEHIND, behindSnapshot.status());
        assertEquals(19, behindSnapshot.missedTicks(), "every skipped server tick of the window is counted");
        assertEquals(20.0D, keepingUpSnapshot.ticksPerSecond(), 1.0e-6D);
        assertEquals(RegionTickStatus.OK, keepingUpSnapshot.status());
        assertEquals(0, keepingUpSnapshot.missedTicks());
    }

    @Test
    void snapshotStaysConsistentWhileAWorkerRecords() throws InterruptedException {
        final RegionTickMetrics metrics = newMetrics();
        final AtomicBoolean recording = new AtomicBoolean(true);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread recorder = new Thread(() -> {
            long tickIndex = FIRST_TICK_INDEX;
            long now = FIRST_TICK_NANOS;
            try {
                while (recording.get()) {
                    metrics.beginTick(tickIndex++, now);
                    metrics.recordTick(MILLIS_IN_NANOS);
                    now += TICK_NANOS;
                }
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "region-tick-metrics-test");
        recorder.start();
        try {
            for (int attempt = 0; attempt < 2_000; ++attempt) {
                final RegionTickSnapshot snapshot = metrics.snapshot(OVERWORLD, 0, 0);
                assertConsistent(snapshot);
            }
        } finally {
            recording.set(false);
            recorder.join(5_000L);
        }
        assertFalse(recorder.isAlive(), "the recording thread did not stop");
        if (failure.get() != null) {
            throw new AssertionError("the recording thread failed", failure.get());
        }
    }

    @Test
    void recordingOneRegionTickAllocatesNothing() {
        final ThreadMXBean bean = allocationBean();
        final RegionTickMetrics metrics = newMetrics();
        // Warm up: the first iterations interpret the recording path and would allocate compilation state.
        for (int index = 0; index < 200_000; ++index) {
            metrics.beginTick(FIRST_TICK_INDEX + index, FIRST_TICK_NANOS + index * TICK_NANOS);
            metrics.recordTick(MILLIS_IN_NANOS);
        }
        final int iterations = 100_000;
        final long before = bean.getCurrentThreadAllocatedBytes();
        for (int index = 0; index < iterations; ++index) {
            metrics.beginTick(FIRST_TICK_INDEX + index, FIRST_TICK_NANOS + index * TICK_NANOS);
            metrics.recordTick(MILLIS_IN_NANOS);
        }
        final long allocated = bean.getCurrentThreadAllocatedBytes() - before;

        // One region tick is one slot advance plus one atomic add; neither touches the heap. The tolerance
        // only covers JVM bookkeeping of the measuring thread, not a per-tick object.
        assertTrue(allocated < 4_096L,
                () -> "recording " + iterations + " region ticks allocated " + allocated + " bytes, expected none per tick");
    }

    @Test
    void releasedWindowDropsItsSamplesAndStaysReleased() {
        final RegionTickMetrics metrics = newMetrics();
        recordTicks(metrics, TICK_NANOS, 1, 1.0D, 2.0D, 3.0D);
        assertEquals(3, metrics.snapshot(OVERWORLD, 0, 0).samples());
        final long recordedTicks = metrics.tickCount();

        metrics.release();
        metrics.release();

        final RegionTickSnapshot released = metrics.snapshot(OVERWORLD, 0, 0);
        assertEquals(0, released.samples(), "a released window reports no samples");
        assertEquals(RegionTickStatus.OK, released.status());
        metrics.beginTick(FIRST_TICK_INDEX, FIRST_TICK_NANOS);
        metrics.recordTick(MILLIS_IN_NANOS);
        assertEquals(0, metrics.snapshot(OVERWORLD, 0, 0).samples(), "a released window never opens another slot");
        assertEquals(recordedTicks + 1L, metrics.tickCount(), "the lifetime counters keep working after the release");
    }

    @Test
    void retiredRegionReleasesItsWindow() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockPos pos = BlockPos.ZERO;
        final int baseline = RegionTickMetrics.liveWindowCount();
        this.engine.regionScheduler().execute(level, pos, () -> {});

        assertEquals(baseline + 1, RegionTickMetrics.liveWindowCount(), "a live region owns one metric window");
        assertEquals(1, this.engine.diagnostics().regionTicks().size());

        this.engine.drainRegionTasks(level, pos);

        assertEquals(0, this.engine.diagnostics().regionCount(), "the drained region retires once it is empty");
        assertEquals(baseline, RegionTickMetrics.liveWindowCount(), "a retired region releases its metric window");
        assertTrue(this.engine.diagnostics().regionTicks().isEmpty(), "a retired region is not part of the diagnostics");
    }

    private RegionTickMetrics newMetrics() {
        final RegionTickMetrics metrics = new RegionTickMetrics();
        this.windows.add(metrics);
        return metrics;
    }

    /**
     * Records {@code durationsMillis} as one region tick each, {@code intervalNanos} apart in wall clock time
     * and {@code tickStep} apart in server tick index. A negative duration records a server tick without
     * leased work, i.e. a tick that opens and closes its slot without a recorded group duration.
     */
    private static void recordTicks(RegionTickMetrics metrics, long intervalNanos, int tickStep, double... durationsMillis) {
        long tickIndex = FIRST_TICK_INDEX;
        long now = FIRST_TICK_NANOS;
        for (final double durationMillis : durationsMillis) {
            metrics.beginTick(tickIndex, now);
            if (durationMillis >= 0.0D) {
                metrics.recordTick((long) (durationMillis * MILLIS_IN_NANOS));
            }
            tickIndex += tickStep;
            now += intervalNanos;
        }
        // Only a closed slot belongs to the window: open the slot after the last measured tick to close it.
        metrics.beginTick(tickIndex, now);
    }

    private static void assertConsistent(RegionTickSnapshot snapshot) {
        assertNotNull(snapshot.status());
        assertTrue(snapshot.samples() >= 0 && snapshot.samples() <= RegionTickMetrics.WINDOW_TICKS,
                () -> "the window reported " + snapshot.samples() + " samples");
        assertTrue(snapshot.missedTicks() >= 0, "a window cannot report negative missed ticks");
        assertTrue(snapshot.msptAvg() >= 0.0D, "a mean MSPT cannot be negative");
        assertTrue(snapshot.msptP95() <= snapshot.msptP99(), "the percentiles have to be ordered");
        assertTrue(snapshot.msptP99() <= snapshot.msptMax(), "no percentile can exceed the maximum");
        assertTrue(snapshot.msptAvg() <= snapshot.msptMax(), "the mean cannot exceed the maximum");
        assertTrue(snapshot.msptLast() <= snapshot.msptMax(), "the last tick cannot exceed the maximum");
        assertTrue(snapshot.ticksPerSecond() >= 0.0D && !Double.isNaN(snapshot.ticksPerSecond()),
                "the observed rate has to be a positive number");
        if (snapshot.samples() == 0) {
            assertEquals(0.0D, snapshot.msptAvg(), "an empty window has no MSPT");
            assertEquals(0.0D, snapshot.ticksPerSecond(), "an empty window has no rate");
            assertEquals(RegionTickStatus.OK, snapshot.status(), "an empty window is never behind");
        }
    }

    private static ThreadMXBean allocationBean() {
        if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean allocationBean) {
            assertTrue(allocationBean.isThreadAllocatedMemoryEnabled(), "the JVM thread allocation counters are disabled");
            return allocationBean;
        }
        throw new AssertionError("the measuring JVM does not expose thread allocation counters");
    }
}
