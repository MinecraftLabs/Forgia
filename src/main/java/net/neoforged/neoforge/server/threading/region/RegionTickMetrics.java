/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.ApiStatus;

/**
 * Tick metrics of one region: the lifetime counters plus the rolling window the per-region MSPT and TPS
 * diagnostics are computed from.
 * <p>
 * <b>Window.</b> The window holds the last {@value #WINDOW_TICKS} server ticks of the region - five
 * seconds at the {@value #TARGET_TICKS_PER_SECOND} ticks per second target. That is short enough to react
 * to a stall within a couple of seconds and long enough that the 95th and 99th percentiles of the window
 * are the fifth-worst and second-worst tick rather than a single outlier.
 * <p>
 * <b>Slot.</b> One slot is one server tick of the region. The engine opens the slot of the current server
 * tick for every live region once per level tick ({@link #beginTick(long, long)}), which closes the slot of
 * the previous server tick. Every duration the region records in between
 * ({@link #recordTick(long)}: the entity, block entity, scheduled tick, random tick and region drain group
 * durations) is added to the open slot. A server tick in which the region is not leased therefore closes
 * as a slot of {@code 0} ns: it contributes {@code 0} to the MSPT statistics and it is <b>not</b> a missed
 * tick, because the region owed no work in it. The slot itself counts, so an idle region still reports one
 * sample per server tick and stays classified as keeping up.
 * <p>
 * <b>Missed tick.</b> A tick the region was live for but was never offered: the slot index (the level game
 * time) jumps by more than one between two consecutive slots of the window. Missed ticks are counted from
 * those index gaps at snapshot time and reported next to the observed rate.
 * <p>
 * <b>Cost.</b> Recording is O(1) and allocation-free: {@link #recordTick(long)} increments two atomic
 * counters and adds to the open slot, and {@link #beginTick(long, long)} writes three longs of a
 * preallocated ring. Percentiles are only computed in {@link #snapshot(String, int, int)}, which copies and
 * sorts the window; nothing sorts per tick.
 * <p>
 * <b>Lifetime.</b> The buffers belong to the region state, so a retired or merged region releases them
 * with {@link #release()} and the memory stays bounded by the live regions of the installed engine.
 */
@ApiStatus.Internal
public final class RegionTickMetrics {
    /** Server ticks kept per region window: five seconds at the tick target. */
    public static final int WINDOW_TICKS = 100;
    /** Server tick target in ticks per second the region TPS is compared to. */
    public static final double TARGET_TICKS_PER_SECOND = 20.0D;
    /**
     * Fraction of the tick target a region has to reach to count as keeping up: one missed tick in twenty.
     * The tolerance absorbs the ms of jitter of a healthy server tick loop without hiding a region that
     * misses ticks.
     */
    public static final double KEEP_UP_FRACTION = 0.95D;

    private static final AtomicInteger LIVE_WINDOWS = new AtomicInteger();

    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong lastTickDurationNanos = new AtomicLong();
    private final AtomicLong currentTickDurationNanos = new AtomicLong();
    private volatile boolean currentTickOpen;

    private long[] windowBeginNanos = new long[WINDOW_TICKS];
    private long[] windowTickIndex = new long[WINDOW_TICKS];
    private long[] windowDurationNanos = new long[WINDOW_TICKS];
    private int windowCursor;
    private int windowSize;
    private long currentTickIndex;
    private long currentTickBeginNanos;
    private boolean released;

    public RegionTickMetrics() {
        LIVE_WINDOWS.incrementAndGet();
    }

    /**
     * {@return the number of region windows that currently hold their buffers}
     * <p>
     * Only used to verify that retired regions release their buffers, so a leak of the per-region window
     * stays observable instead of only showing up as retained heap.
     */
    public static int liveWindowCount() {
        return LIVE_WINDOWS.get();
    }

    /**
     * Opens the window slot of server tick {@code tickIndex} and closes the slot of the previous tick.
     * <p>
     * Called once per live region per level tick on the server thread. A repeated call for the same tick
     * index is a no-op, so a level that drains its regions twice in one tick cannot open a second slot.
     * Durations recorded before the first call are dropped: they belong to no measured server tick.
     *
     * @param tickIndex  the level game time of the server tick
     * @param beginNanos the wall clock time the server tick started at
     */
    public synchronized void beginTick(long tickIndex, long beginNanos) {
        if (this.released) {
            return;
        }
        if (this.currentTickOpen && tickIndex == this.currentTickIndex) {
            return;
        }
        if (this.currentTickOpen) {
            this.push(this.currentTickIndex, this.currentTickBeginNanos, this.currentTickDurationNanos.getAndSet(0L));
        } else {
            this.currentTickDurationNanos.set(0L);
        }
        this.currentTickIndex = tickIndex;
        this.currentTickBeginNanos = beginNanos;
        this.currentTickOpen = true;
    }

    /**
     * Adds the wall time of one group tick of the region to the open server tick slot.
     * <p>
     * O(1) and allocation-free; safe to call from a region worker while the server thread opens the next
     * slot, because the sum is atomic and only {@link #beginTick(long, long)} writes the ring.
     */
    public void recordTick(long durationNanos) {
        this.tickCount.incrementAndGet();
        this.lastTickDurationNanos.set(durationNanos);
        if (this.currentTickOpen) {
            this.currentTickDurationNanos.addAndGet(durationNanos);
        }
    }

    /**
     * {@return the number of group ticks recorded since the region was created}
     */
    public long tickCount() {
        return this.tickCount.get();
    }

    /**
     * {@return the duration of the newest recorded group tick}
     */
    public long lastTickDurationNanos() {
        return this.lastTickDurationNanos.get();
    }

    /**
     * {@return the metrics of this region at this moment, labelled with {@code dimension} and the
     * coordinates}
     * <p>
     * The only allocating path of this class: it copies the window, sorts the copy for the percentiles and
     * computes the observed tick rate and the classification. Nothing is mutated, so concurrent recorders
     * see no torn window and the snapshot stays consistent.
     */
    public synchronized RegionTickSnapshot snapshot(String dimension, int regionX, int regionZ) {
        final int samples = this.released ? 0 : this.windowSize;
        if (samples == 0) {
            return new RegionTickSnapshot(regionX, regionZ, dimension, 0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, RegionTickStatus.OK);
        }
        final long[] durations = new long[samples];
        long sum = 0L;
        long max = 0L;
        int missedTicks = 0;
        long previousTickIndex = 0L;
        final int oldest = (this.windowCursor - samples + WINDOW_TICKS) % WINDOW_TICKS;
        for (int index = 0; index < samples; ++index) {
            final int slot = (oldest + index) % WINDOW_TICKS;
            final long duration = this.windowDurationNanos[slot];
            durations[index] = duration;
            sum += duration;
            max = Math.max(max, duration);
            final long tickIndex = this.windowTickIndex[slot];
            if (index > 0) {
                missedTicks += (int) Math.max(0L, tickIndex - previousTickIndex - 1L);
            }
            previousTickIndex = tickIndex;
        }
        final int newest = (this.windowCursor - 1 + WINDOW_TICKS) % WINDOW_TICKS;
        final long spanNanos = this.windowBeginNanos[newest] - this.windowBeginNanos[oldest];
        final double ticksPerSecond = samples > 1 && spanNanos > 0L
                ? (samples - 1) * 1.0e9D / spanNanos
                : 0.0D;
        Arrays.sort(durations);
        final double nanosPerMillisecond = 1.0e6D;
        return new RegionTickSnapshot(
                regionX,
                regionZ,
                dimension,
                samples,
                sum / (double) samples / nanosPerMillisecond,
                percentile(durations, 0.95D) / nanosPerMillisecond,
                percentile(durations, 0.99D) / nanosPerMillisecond,
                (double) max / nanosPerMillisecond,
                (double) this.windowDurationNanos[newest] / nanosPerMillisecond,
                ticksPerSecond,
                missedTicks,
                // A window with a single closed tick has no observable rate yet; only a rate measured over at
                // least two ticks can fall below the target.
                samples > 1 && ticksPerSecond < TARGET_TICKS_PER_SECOND * KEEP_UP_FRACTION
                        ? RegionTickStatus.BEHIND
                        : RegionTickStatus.OK);
    }

    /**
     * Releases the window buffers of a retired region.
     * <p>
     * Idempotent, so a region that is retired by an empty-region removal and again by a level clear releases
     * once. Recording after the release still counts the lifetime counters but no longer opens a slot, and a
     * snapshot reports an empty window.
     */
    public synchronized void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        this.windowBeginNanos = null;
        this.windowTickIndex = null;
        this.windowDurationNanos = null;
        this.windowCursor = 0;
        this.windowSize = 0;
        this.currentTickOpen = false;
        this.currentTickDurationNanos.set(0L);
        LIVE_WINDOWS.decrementAndGet();
    }

    private void push(long tickIndex, long beginNanos, long durationNanos) {
        this.windowBeginNanos[this.windowCursor] = beginNanos;
        this.windowTickIndex[this.windowCursor] = tickIndex;
        this.windowDurationNanos[this.windowCursor] = durationNanos;
        this.windowCursor = (this.windowCursor + 1) % WINDOW_TICKS;
        if (this.windowSize < WINDOW_TICKS) {
            ++this.windowSize;
        }
    }

    /**
     * {@return the nearest-rank {@code fraction} percentile of the ascending {@code sorted} values}
     */
    private static long percentile(long[] sorted, double fraction) {
        final int rank = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.clamp(rank, 0, sorted.length - 1)];
    }
}
