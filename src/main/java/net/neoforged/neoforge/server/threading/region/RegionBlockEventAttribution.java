/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Opt-in wall-clock attribution of the server-thread block event phase.
 * <p>
 * The phase is the vanilla loop that drains the level's ordered block event set and calls
 * {@code BlockState.triggerEvent} for every event that is still valid. It runs once per level tick on the
 * server thread, and a piston move runs inside it: {@code PistonBaseBlock.triggerEvent} resolves the
 * structure and moves the blocks, so the wall time of one event is the wall time of one multi-block move. The
 * attribution records the phase, every event in it and the slowest phase, so a workload can be told apart by
 * the number of events it produces, by the cost of a single event and by the tick it fires on.
 * <p>
 * Only the server thread touches the accumulators: it opens and closes every phase and every event, and it
 * resets the window between measurements. When the attribution is disabled every entry point returns after one
 * volatile read, which is read once per phase and once per event, never per block.
 */
@ApiStatus.Internal
public final class RegionBlockEventAttribution {
    /**
     * Width of one bucket of the event latency distribution: 2 microseconds, so {@value #LATENCY_BUCKETS}
     * buckets cover the first 16 milliseconds of an event and everything slower is counted as overflow.
     */
    private static final long LATENCY_BUCKET_NANOS = 2_000L;
    /** Buckets of the event latency distribution. */
    private static final int LATENCY_BUCKETS = 8_192;
    private static volatile boolean enabled;
    /**
     * The level whose block event phases are measured, or {@code null} to measure every level.
     * <p>
     * A server runs one phase per level and tick, and a level that carries no piston workload contributes a
     * phase of almost no time. A measurement of one workload names its level, so the phases of the other
     * levels are not timed at all.
     */
    @Nullable
    private static volatile ServerLevel measuredLevel;
    /**
     * Whether the open phase is measured. An event is only counted inside a measured phase, so naming a level
     * also excludes the events of every other level.
     */
    private static boolean phaseMeasured;
    private static long phaseStartNanos;
    private static long phaseNanos;
    private static long phaseMaxNanos;
    private static long phaseCount;
    private static long eventNanos;
    private static long eventCount;
    private static final LongAdder[] eventNanosHistogram = new LongAdder[LATENCY_BUCKETS];
    private static final LongAdder eventNanosHistogramOverflow = new LongAdder();

    static {
        for (int index = 0; index < eventNanosHistogram.length; ++index) {
            eventNanosHistogram[index] = new LongAdder();
        }
    }

    private RegionBlockEventAttribution() {}

    /** {@return true when the attribution accumulates} */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the attribution and clears the accumulators, so a measurement starts from zero.
     */
    public static void setEnabled(boolean enabled) {
        RegionBlockEventAttribution.enabled = enabled;
        reset();
    }

    /**
     * Clears the accumulators and the open phase, so a measurement window starts from zero.
     */
    public static void reset() {
        phaseMeasured = false;
        phaseStartNanos = 0L;
        phaseNanos = 0L;
        phaseMaxNanos = 0L;
        phaseCount = 0L;
        eventNanos = 0L;
        eventCount = 0L;
        for (final LongAdder bucket : eventNanosHistogram) {
            bucket.reset();
        }
        eventNanosHistogramOverflow.reset();
    }

    /**
     * Names the level whose block event phases are measured, or clears the filter with {@code null}.
     */
    public static void setMeasuredLevel(@Nullable ServerLevel level) {
        measuredLevel = level;
    }

    /**
     * Opens the block event phase of {@code level} that is about to start on the server thread.
     */
    public static void beginPhase(ServerLevel level) {
        phaseMeasured = false;
        if (!enabled) {
            return;
        }
        final ServerLevel measured = measuredLevel;
        if (measured != null && measured != level) {
            return;
        }
        phaseMeasured = true;
        phaseStartNanos = System.nanoTime();
    }

    /**
     * Closes the phase opened by {@link #beginPhase(ServerLevel)}.
     */
    public static void endPhase() {
        if (!phaseMeasured) {
            return;
        }
        phaseMeasured = false;
        final long start = phaseStartNanos;
        phaseStartNanos = 0L;
        if (start == 0L) {
            return;
        }
        final long duration = System.nanoTime() - start;
        phaseNanos += duration;
        if (duration > phaseMaxNanos) {
            phaseMaxNanos = duration;
        }
        ++phaseCount;
    }

    /**
     * Opens the attribution of the event that is about to run on the server thread.
     *
     * @return the start timestamp to hand to {@link #endEvent(long)}, or {@code 0} when the event is not
     *         measured because the attribution is disabled or the open phase is not
     */
    public static long beginEvent() {
        return phaseMeasured ? System.nanoTime() : 0L;
    }

    /**
     * Closes the attribution of the event opened by {@link #beginEvent()}.
     *
     * @param startNanos the value {@link #beginEvent()} returned, or {@code 0} when the event is not
     *                   measured
     */
    public static void endEvent(long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        final long duration = System.nanoTime() - startNanos;
        eventNanos += duration;
        ++eventCount;
        final long bucket = duration / LATENCY_BUCKET_NANOS;
        if (bucket < eventNanosHistogram.length) {
            eventNanosHistogram[(int) bucket].increment();
        } else {
            eventNanosHistogramOverflow.increment();
        }
    }

    /** {@return the accumulated attribution of the server thread so far} */
    public static Snapshot snapshot() {
        final long[] buckets = new long[eventNanosHistogram.length];
        for (int index = 0; index < buckets.length; ++index) {
            buckets[index] = eventNanosHistogram[index].sum();
        }
        return new Snapshot(phaseNanos, phaseMaxNanos, phaseCount, eventNanos, eventCount,
                new RegionChunkTickAttribution.LatencyDistribution(LATENCY_BUCKET_NANOS, buckets, eventNanosHistogramOverflow.sum()));
    }

    /**
     * The accumulated attribution of the block event phase, in nanoseconds.
     *
     * @param phaseNanos    wall time of all measured phases
     * @param phaseMaxNanos wall time of the slowest measured phase, which is the tick the workload fired on
     * @param phaseCount    the number of phases the accumulators cover, so the phase can be reported per tick
     * @param eventNanos    wall time of all measured events, which the phase contains
     * @param eventCount    the number of events, which is the number of block events the workload produced
     * @param latency       the distribution of the event wall times
     */
    public record Snapshot(
            long phaseNanos,
            long phaseMaxNanos,
            long phaseCount,
            long eventNanos,
            long eventCount,
            RegionChunkTickAttribution.LatencyDistribution latency) {}
}
