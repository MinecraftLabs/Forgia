/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Opt-in wall-clock accounting of the tick barrier of every region slice.
 * <p>
 * One batch of one slice is one barrier: the server thread submits the groups, waits for the last one and then
 * applies the deferred work. This class splits that wait of every batch into three segments, so the part of a
 * tick the server thread spends waiting is separable from the part the workers spend working:
 * <ul>
 * <li><b>dispatch</b> - submit start to the first group start. It is the server thread handing the groups to
 * the pool plus the pool waking a worker; no worker work happens in it.</li>
 * <li><b>span</b> - first group start to last group end. It is the worker makespan of the batch: the interval in
 * which at least one worker ran. No barrier can finish before it, so it bounds what any scheduling change could
 * remove.</li>
 * <li><b>tail</b> - last group end to the end of the wait. It is how long the barrier needed to notice that the
 * last group finished.</li>
 * </ul>
 * {@code dispatch + span + tail} is the await of the batch, so the two segments that are not worker work are
 * exactly {@code dispatch + tail}. That sum is the recoverable part of a tick barrier: the work the server
 * thread does only because it waits for a batch that is already finished.
 * <p>
 * Every slice of {@link RegionTickSlice} is accounted separately, because a tick has one barrier per slice and a
 * single average over them cannot say which slice the server thread is parked in.
 * <p>
 * The per-batch await and span are kept as nearest-rank histograms, so the report carries the p50 of the measured
 * batches instead of a mean the slowest batch of a window pulls up.
 * <p>
 * The instrumentation is disabled by default. When it is disabled every entry point returns after one volatile
 * read: the group wrapper reads it once per group and the submit path once per batch, never per unit of work.
 * Only a diagnostics run enables it, and only the server thread resets the accumulators, between windows.
 */
@ApiStatus.Internal
public final class RegionBarrierAttribution {
    /**
     * Width of one bucket of the await and span distributions: 8 microseconds, so {@value #LATENCY_BUCKETS}
     * buckets cover the first 64 milliseconds of a batch and everything slower is counted as overflow.
     */
    private static final long LATENCY_BUCKET_NANOS = 8_000L;
    /** Buckets of the per-batch distributions. */
    private static final int LATENCY_BUCKETS = 8_192;
    private static volatile boolean enabled;
    private static final SliceCounters[] SLICES = newSliceCounters();
    /**
     * End of the last barrier the current thread waited at, or {@code 0} outside a measured window.
     * <p>
     * Only the server thread submits a batch, so this stamp measures the interval between two barriers of the
     * level tick: the server-thread work of the segment between them - the handoff of the batch that just
     * completed, the vanilla statements vanilla places between the two slices and the plan and lease of the next
     * one. The gap is reported because that interval is where a schedule that let a region run ahead would have
     * to find something to overlap with, and because it is the answer to "what does the server thread do between
     * two barriers". It is an upper bound on overlap, not a claim that any of it can overlap: the plan of the
     * next slice reads the state the handoff of the previous one writes.
     */
    private static final ThreadLocal<long[]> LAST_BARRIER_END = ThreadLocal.withInitial(() -> new long[1]);

    private RegionBarrierAttribution() {}

    /** {@return true when the accounting accumulates} */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the accounting and clears the accumulators, so a measurement starts from zero.
     */
    public static void setEnabled(boolean enabled) {
        RegionBarrierAttribution.enabled = enabled;
        reset();
    }

    /**
     * Clears the accumulators, so a measurement window starts from zero.
     */
    public static void reset() {
        for (final SliceCounters counters : SLICES) {
            counters.reset();
        }
        LAST_BARRIER_END.remove();
    }

    /**
     * Records that one group of a batch started, against the batch's first-start stamp.
     * <p>
     * The stamp is the earliest start of the batch, so a group that starts while another one is already running
     * does not move it. Called from the worker (or from the publishing thread for a rejected group).
     *
     * @param firstStartNanos the batch's first-start stamp; {@code 0} means no group started yet
     */
    static void groupStarted(AtomicLong firstStartNanos) {
        if (!enabled) {
            return;
        }
        final long now = System.nanoTime();
        long current = firstStartNanos.get();
        while (current == 0L || now < current) {
            if (firstStartNanos.compareAndSet(current, now)) {
                return;
            }
            current = firstStartNanos.get();
        }
    }

    /**
     * Records that one group of a batch ended, against the batch's last-end stamp.
     *
     * @param lastEndNanos the batch's last-end stamp; it only ever moves forward
     */
    static void groupEnded(AtomicLong lastEndNanos) {
        if (!enabled) {
            return;
        }
        final long now = System.nanoTime();
        long current = lastEndNanos.get();
        while (now > current) {
            if (lastEndNanos.compareAndSet(current, now)) {
                return;
            }
            current = lastEndNanos.get();
        }
    }

    /**
     * Records one awaited batch of {@code slice}.
     * <p>
     * Called on the server thread after the barrier of the batch completed, so the three segments describe one
     * wait exactly. A batch no worker group started (an empty batch, or one whose groups the pool rejected and
     * that therefore ran inline) has no span; it contributes its await to {@code dispatch} only.
     *
     * @param slice           the slice the batch executed
     * @param awaitStartNanos the time the server thread started submitting
     * @param awaitEndNanos   the time the barrier completed
     * @param submitNanos     the part of the submit the batch measured itself
     * @param firstStartNanos the batch's first group start, or {@code 0}
     * @param lastEndNanos    the batch's last group end, or {@code 0}
     * @param groupCount      the number of groups the batch waited for
     */
    public static void recordBatch(RegionTickSlice slice, long awaitStartNanos, long awaitEndNanos, long submitNanos,
            long firstStartNanos, long lastEndNanos, int groupCount) {
        if (!enabled) {
            return;
        }
        final SliceCounters counters = SLICES[slice.ordinal()];
        final long awaitNanos = Math.max(awaitEndNanos - awaitStartNanos, 0L);
        final long[] lastEnd = LAST_BARRIER_END.get();
        if (lastEnd[0] != 0L) {
            final long gapNanos = Math.max(awaitStartNanos - lastEnd[0], 0L);
            counters.gapNanos.add(gapNanos);
            counters.gapHistogram(bucket(gapNanos)).increment();
        }
        lastEnd[0] = awaitEndNanos;
        counters.batches.increment();
        counters.groups.add(groupCount);
        counters.awaitNanos.add(awaitNanos);
        counters.submitNanos.add(submitNanos);
        counters.awaitHistogram(bucket(awaitNanos)).increment();
        if (firstStartNanos <= 0L || lastEndNanos <= 0L || lastEndNanos < firstStartNanos) {
            counters.dispatchNanos.add(awaitNanos);
            return;
        }
        final long dispatchNanos = Math.max(firstStartNanos - awaitStartNanos, 0L);
        final long spanNanos = lastEndNanos - firstStartNanos;
        final long tailNanos = Math.max(awaitEndNanos - lastEndNanos, 0L);
        counters.dispatchNanos.add(dispatchNanos);
        counters.spanNanos.add(spanNanos);
        counters.tailNanos.add(tailNanos);
        counters.spanHistogram(bucket(spanNanos)).increment();
    }

    /**
     * Records the handoff that followed the barrier of one batch: the deferred work the server thread applies
     * after the wait and before the next statement of the level tick.
     * <p>
     * The handoff is not part of the wait, so it is not part of what a window could overlap with region work.
     * It is accounted because the interval between two barriers of a tick is the handoff of the batch that just
     * completed plus the vanilla statements between the two slices plus the plan of the next one, and only the
     * middle term is work a window could run next to a region.
     *
     * @param slice         the slice the batch executed
     * @param awaitEndNanos the end of the barrier wait, or {@code 0} for a batch that never waited
     * @param finishNanos   the time the batch released its last permit
     */
    public static void recordHandoff(RegionTickSlice slice, long awaitEndNanos, long finishNanos) {
        if (!enabled || awaitEndNanos <= 0L) {
            return;
        }
        SLICES[slice.ordinal()].handoffNanos.add(Math.max(finishNanos - awaitEndNanos, 0L));
    }

    /**
     * {@return the accumulated accounting per slice}
     */
    public static Snapshot snapshot() {
        final SliceSnapshot[] slices = new SliceSnapshot[SLICES.length];
        for (final RegionTickSlice slice : RegionTickSlice.values()) {
            slices[slice.ordinal()] = SLICES[slice.ordinal()].snapshot(slice);
        }
        return new Snapshot(slices);
    }

    /**
     * {@return the accounting of every slice as report lines, per measured tick}
     * <p>
     * One row per slice, so a reader sees which slice the server thread parks in instead of one average over
     * the tick. {@code await} is the whole wait, {@code dispatch} and {@code tail} are its two parts no worker
     * work explains, {@code span} is the worker makespan, and {@code awaitP50} / {@code spanP50} are the
     * per-batch medians.
     *
     * @param snapshot   the accounting to report, captured with {@link #snapshot()} at the end of the window
     * @param linePrefix the report tag of the caller, written as the first column
     * @param ticks      the measured ticks the accumulators cover
     */
    public static java.util.List<String> formatRows(Snapshot snapshot, String linePrefix, int ticks) {
        final java.util.List<String> rows = new java.util.ArrayList<>();
        rows.add(linePrefix + "_HEADER slice,batches,batchesPerTick,groups,awaitMs,dispatchMs,spanMs,tailMs,overheadMs,awaitShareOfTick,gapMs,handoffMs,awaitP50Ms,spanP50Ms,gapP50Ms,submitMs");
        final double ticksDenominator = Math.max(ticks, 1);
        for (final SliceSnapshot slice : snapshot.slices()) {
            rows.add(linePrefix + " " + slice.slice()
                    + "," + slice.batches()
                    + "," + fixed(slice.batches() / ticksDenominator)
                    + "," + slice.groups()
                    + "," + millis(slice.awaitNanos(), ticksDenominator)
                    + "," + millis(slice.dispatchNanos(), ticksDenominator)
                    + "," + millis(slice.spanNanos(), ticksDenominator)
                    + "," + millis(slice.tailNanos(), ticksDenominator)
                    + "," + millis(slice.overheadNanos(), ticksDenominator)
                    + "," + fixed(slice.awaitNanos() / ticksDenominator / 1.0e6D)
                    + "," + millis(slice.gapNanos(), ticksDenominator)
                    + "," + millis(slice.handoffNanos(), ticksDenominator)
                    + "," + fixed(slice.awaitP50Nanos() / 1.0e6D)
                    + "," + fixed(slice.spanP50Nanos() / 1.0e6D)
                    + "," + fixed(slice.gapP50Nanos() / 1.0e6D)
                    + "," + millis(slice.submitNanos(), ticksDenominator));
        }
        return rows;
    }

    private static String millis(long nanos, double ticks) {
        return fixed(nanos / ticks / 1.0e6D);
    }

    private static String fixed(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static int bucket(long durationNanos) {
        final long bucket = Math.max(durationNanos, 0L) / LATENCY_BUCKET_NANOS;
        return bucket < LATENCY_BUCKETS ? (int) bucket : LATENCY_BUCKETS;
    }

    private static SliceCounters[] newSliceCounters() {
        final RegionTickSlice[] values = RegionTickSlice.values();
        final SliceCounters[] counters = new SliceCounters[values.length];
        for (int index = 0; index < values.length; ++index) {
            counters[index] = new SliceCounters();
        }
        return counters;
    }

    /**
     * The accounting of every slice at one moment.
     *
     * @param slices the accounting of each slice, indexed like {@link RegionTickSlice#values()}
     */
    public record Snapshot(SliceSnapshot[] slices) {
        /** {@return the accounting of {@code slice}} */
        public SliceSnapshot of(RegionTickSlice slice) {
            return this.slices[slice.ordinal()];
        }
    }

    /**
     * The accounting of one slice, in nanoseconds.
     *
     * @param batches       the batches awaited
     * @param groups        the worker groups the batches waited for
     * @param awaitNanos    the wall time the server thread spent in the barrier
     * @param dispatchNanos the part of it before the first group started
     * @param spanNanos     the part in which at least one group ran
     * @param tailNanos     the part after the last group ended
     * @param submitNanos   the part of the dispatch the submission itself took
     * @param gapNanos      the server-thread time between the previous barrier and this slice's submit, so the
     *                      work of the level tick that sits between two barriers
     * @param handoffNanos  the deferred work the server thread applied after the barrier of this slice
     * @param awaitP50Nanos the nearest-rank p50 of the per-batch await
     * @param spanP50Nanos  the nearest-rank p50 of the per-batch span
     * @param gapP50Nanos   the nearest-rank p50 of the per-batch gap
     */
    public record SliceSnapshot(
            RegionTickSlice slice,
            long batches,
            long groups,
            long awaitNanos,
            long dispatchNanos,
            long spanNanos,
            long tailNanos,
            long submitNanos,
            long gapNanos,
            long handoffNanos,
            long awaitP50Nanos,
            long spanP50Nanos,
            long gapP50Nanos) {
        /**
         * {@return the part of the await no worker work explains, so the part a barrier that never waited for a
         * finished batch could remove}
         */
        public long overheadNanos() {
            return this.dispatchNanos + this.tailNanos;
        }
    }

    private static final class SliceCounters {
        private final LongAdder batches = new LongAdder();
        private final LongAdder groups = new LongAdder();
        private final LongAdder awaitNanos = new LongAdder();
        private final LongAdder dispatchNanos = new LongAdder();
        private final LongAdder spanNanos = new LongAdder();
        private final LongAdder tailNanos = new LongAdder();
        private final LongAdder submitNanos = new LongAdder();
        private final LongAdder gapNanos = new LongAdder();
        private final LongAdder handoffNanos = new LongAdder();
        private final LongAdder[] awaitHistogram = newHistogram();
        private final LongAdder[] spanHistogram = newHistogram();
        private final LongAdder[] gapHistogram = newHistogram();
        private final LongAdder awaitOverflow = new LongAdder();
        private final LongAdder spanOverflow = new LongAdder();
        private final LongAdder gapOverflow = new LongAdder();

        private static LongAdder[] newHistogram() {
            final LongAdder[] buckets = new LongAdder[LATENCY_BUCKETS];
            for (int index = 0; index < buckets.length; ++index) {
                buckets[index] = new LongAdder();
            }
            return buckets;
        }

        private LongAdder awaitHistogram(int bucket) {
            return bucket < LATENCY_BUCKETS ? this.awaitHistogram[bucket] : this.awaitOverflow;
        }

        private LongAdder spanHistogram(int bucket) {
            return bucket < LATENCY_BUCKETS ? this.spanHistogram[bucket] : this.spanOverflow;
        }

        private LongAdder gapHistogram(int bucket) {
            return bucket < LATENCY_BUCKETS ? this.gapHistogram[bucket] : this.gapOverflow;
        }

        private void reset() {
            this.batches.reset();
            this.groups.reset();
            this.awaitNanos.reset();
            this.dispatchNanos.reset();
            this.spanNanos.reset();
            this.tailNanos.reset();
            this.submitNanos.reset();
            this.gapNanos.reset();
            this.handoffNanos.reset();
            for (int index = 0; index < LATENCY_BUCKETS; ++index) {
                this.awaitHistogram[index].reset();
                this.spanHistogram[index].reset();
                this.gapHistogram[index].reset();
            }
            this.awaitOverflow.reset();
            this.spanOverflow.reset();
            this.gapOverflow.reset();
        }

        private SliceSnapshot snapshot(RegionTickSlice slice) {
            return new SliceSnapshot(slice, this.batches.sum(), this.groups.sum(), this.awaitNanos.sum(),
                    this.dispatchNanos.sum(), this.spanNanos.sum(), this.tailNanos.sum(), this.submitNanos.sum(),
                    this.gapNanos.sum(), this.handoffNanos.sum(),
                    percentileNanos(this.awaitHistogram, this.awaitOverflow.sum()),
                    percentileNanos(this.spanHistogram, this.spanOverflow.sum()),
                    percentileNanos(this.gapHistogram, this.gapOverflow.sum()));
        }

        /**
         * {@return the upper bound of the bucket that holds the nearest-rank {@code 0.5} sample}
         * <p>
         * A sample in the overflow tail reports the last bucket, because the distribution only knows that it was
         * slower than that.
         */
        private static long percentileNanos(LongAdder[] histogram, long overflow) {
            long total = overflow;
            for (final LongAdder bucket : histogram) {
                total += bucket.sum();
            }
            if (total <= 0L) {
                return 0L;
            }
            final long rank = Math.max(1L, (long) Math.ceil(0.5D * total));
            long seen = 0L;
            for (int index = 0; index < histogram.length; ++index) {
                seen += histogram[index].sum();
                if (seen >= rank) {
                    return (index + 1L) * LATENCY_BUCKET_NANOS;
                }
            }
            return histogram.length * LATENCY_BUCKET_NANOS;
        }
    }
}
