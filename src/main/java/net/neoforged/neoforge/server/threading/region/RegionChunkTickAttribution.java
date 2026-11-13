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
 * Opt-in wall-clock attribution of the server-thread chunk tick slice.
 * <p>
 * The slice starts where the level opens its chunk tick plan and ends where the plan returned, so it covers the
 * whole server-thread share of the chunk tick of one level tick: the per-chunk dispatch decision, the region
 * lease acquisition, the wait for the batch barrier and the deferred work that runs after it. Inside the slice
 * the instrumentation accumulates four segments, so a slice that is slow because it waits and one that is slow
 * because the server thread still works can be told apart.
 * <p>
 * The distribution of the slice wall times is a nearest-rank histogram, so the reported p50 is the median of
 * the measured ticks instead of a mean that the 12-20 ms tail of a full server tick pulls up. The mean of a
 * 2 ms effect is not resolvable in a per-tick mean that also carries that tail, which is why the benchmark
 * reports both.
 * <p>
 * The instrumentation is disabled by default. When it is disabled every entry point returns after one
 * volatile read, which is read once per level tick and never per chunk. Only the server thread resets the
 * accumulators, between windows.
 */
@ApiStatus.Internal
public final class RegionChunkTickAttribution {
    /**
     * Width of one bucket of the slice latency distribution: 4 microseconds, so {@value #LATENCY_BUCKETS}
     * buckets cover the first 32 milliseconds of a slice and everything slower is counted as overflow.
     */
    private static final long LATENCY_BUCKET_NANOS = 4_000L;
    /** Buckets of the slice latency distribution. */
    private static final int LATENCY_BUCKETS = 8_192;
    private static volatile boolean enabled;
    /**
     * The level whose slices are measured, or {@code null} to measure every level.
     * <p>
     * A server ticks one chunk tick pass per level and tick, and the levels that carry no workload - the nether
     * and the end of a measurement server, for example - contribute a slice of almost no time. A measurement that
     * compares the slice of one workload has to report the slices of the level that carries it, so the harness
     * names that level and every other level is not timed at all.
     */
    @Nullable
    private static volatile ServerLevel measuredLevel;
    private static long sliceStartNanos;
    private static long planNanos;
    private static long leaseNanos;
    private static long awaitNanos;
    private static long handoffNanos;
    private static long sliceNanos;
    private static long sliceCount;
    private static final LongAdder[] sliceNanosHistogram = new LongAdder[LATENCY_BUCKETS];
    private static final LongAdder sliceNanosHistogramOverflow = new LongAdder();

    static {
        for (int index = 0; index < sliceNanosHistogram.length; ++index) {
            sliceNanosHistogram[index] = new LongAdder();
        }
    }

    private RegionChunkTickAttribution() {}

    /** {@return true when the attribution accumulates} */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the attribution and clears the accumulators, so a measurement starts from zero.
     */
    public static void setEnabled(boolean enabled) {
        RegionChunkTickAttribution.enabled = enabled;
        reset();
    }

    /**
     * Clears the accumulators and the open slice, so a measurement window starts from zero.
     */
    public static void reset() {
        sliceStartNanos = 0L;
        planNanos = 0L;
        leaseNanos = 0L;
        awaitNanos = 0L;
        handoffNanos = 0L;
        sliceNanos = 0L;
        sliceCount = 0L;
        for (final LongAdder bucket : sliceNanosHistogram) {
            bucket.reset();
        }
        sliceNanosHistogramOverflow.reset();
    }

    /**
     * Names the level whose chunk tick slices are measured, or clears the filter with {@code null}.
     */
    public static void setMeasuredLevel(@Nullable ServerLevel level) {
        measuredLevel = level;
    }

    /**
     * Opens the slice of the chunk tick pass of {@code level} that is about to start on the server thread.
     */
    public static void beginSlice(ServerLevel level) {
        if (!enabled) {
            return;
        }
        final ServerLevel measured = measuredLevel;
        if (measured != null && measured != level) {
            return;
        }
        sliceStartNanos = System.nanoTime();
    }

    /**
     * Closes the slice opened by {@link #beginSlice()}.
     */
    public static void endSlice() {
        if (!enabled) {
            return;
        }
        final long start = sliceStartNanos;
        sliceStartNanos = 0L;
        if (start == 0L) {
            return;
        }
        final long duration = System.nanoTime() - start;
        sliceNanos += duration;
        ++sliceCount;
        final long bucket = duration / LATENCY_BUCKET_NANOS;
        if (bucket < sliceNanosHistogram.length) {
            sliceNanosHistogram[(int) bucket].increment();
        } else {
            sliceNanosHistogramOverflow.increment();
        }
    }

    /**
     * Closes the plan segment, which is the part of the slice the server thread spent deciding per chunk
     * whether it may be handed to a worker.
     */
    public static void endPlanSegment() {
        if (!enabled) {
            return;
        }
        final long start = sliceStartNanos;
        if (start == 0L) {
            return;
        }
        planNanos += System.nanoTime() - start;
    }

    /**
     * Adds the time the server thread spent grouping the collected chunks by region and acquiring the region
     * leases.
     */
    public static void recordLease(long durationNanos) {
        if (enabled) {
            leaseNanos += durationNanos;
        }
    }

    /**
     * Adds the time the server thread spent submitting the worker batch and waiting for the barrier.
     */
    public static void recordAwait(long durationNanos) {
        if (enabled) {
            awaitNanos += durationNanos;
        }
    }

    /**
     * Adds the time the server thread spent on the deferred work after the barrier: the deferred entity
     * effects, the lightning strikes, the fallback chunks and the batch completion.
     */
    public static void recordHandoff(long durationNanos) {
        if (enabled) {
            handoffNanos += durationNanos;
        }
    }

    /** {@return the accumulated attribution of the server thread so far} */
    public static Snapshot snapshot() {
        final long[] buckets = new long[sliceNanosHistogram.length];
        for (int index = 0; index < buckets.length; ++index) {
            buckets[index] = sliceNanosHistogram[index].sum();
        }
        return new Snapshot(planNanos, leaseNanos, awaitNanos, handoffNanos, sliceNanos, sliceCount,
                new LatencyDistribution(LATENCY_BUCKET_NANOS, buckets, sliceNanosHistogramOverflow.sum()));
    }

    /**
     * The accumulated attribution of the chunk tick slice, in nanoseconds.
     *
     * @param planNanos    time the server thread spent deciding which chunks to hand over
     * @param leaseNanos   time the server thread spent grouping and leasing the regions
     * @param awaitNanos   time the server thread spent waiting for the batch
     * @param handoffNanos time the server thread spent on the deferred work after the barrier
     * @param sliceNanos   wall time of all slices
     * @param sliceCount   the number of slices the accumulators cover, so the segments can be reported per tick
     * @param latency      the distribution of the slice wall times
     */
    public record Snapshot(
            long planNanos,
            long leaseNanos,
            long awaitNanos,
            long handoffNanos,
            long sliceNanos,
            long sliceCount,
            LatencyDistribution latency) {}

    /**
     * Nearest-rank latency distribution of the slice wall times.
     *
     * @param bucketNanos the width of one bucket; bucket {@code i} holds the slices that ran between
     *                    {@code i * bucketNanos} and {@code (i + 1) * bucketNanos} nanoseconds
     * @param buckets     the slice count per bucket
     * @param overflow    the slices slower than the last bucket
     */
    public record LatencyDistribution(long bucketNanos, long[] buckets, long overflow) {
        /** {@return the number of slices the distribution covers} */
        public long count() {
            long total = this.overflow;
            for (final long bucket : this.buckets) {
                total += bucket;
            }
            return total;
        }

        /**
         * {@return the upper bound of the bucket that holds the nearest-rank {@code percentile} slice}
         * <p>
         * A slice in the overflow tail reports the last bucket, because the distribution only knows that it was
         * slower than that.
         */
        public long percentileNanos(double percentile) {
            final long total = this.count();
            if (total <= 0L) {
                return 0L;
            }
            final long rank = Math.max(1L, (long) Math.ceil(percentile * total));
            long seen = 0L;
            for (int index = 0; index < this.buckets.length; ++index) {
                seen += this.buckets[index];
                if (seen >= rank) {
                    return (index + 1L) * this.bucketNanos;
                }
            }
            return this.buckets.length * this.bucketNanos;
        }
    }
}
