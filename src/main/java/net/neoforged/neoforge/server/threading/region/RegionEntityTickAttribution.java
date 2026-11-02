/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Opt-in wall-clock attribution of the server-thread entity tick slice.
 * <p>
 * The slice starts where the level begins collecting the entities of its entity tick pass and ends where the
 * pass returns, so it covers the whole server-thread entity slice of one level tick. Inside the slice the
 * instrumentation accumulates three segments: the region lease acquisition, the wait for the batch barrier
 * and the deferred work that runs after the barrier. The rest of the slice is the collection and admission
 * of the entities before the batch, which is closed by {@link #endPlanSegment()} exactly like the plan
 * segment of the block-entity attribution.
 * <p>
 * Everything is measured with {@link System#nanoTime()} into plain {@code long} accumulators on the server
 * thread; nothing is allocated or logged in the measured path. Only the server thread writes the fields, so
 * they need no synchronization and {@link #snapshot()} is meant for a reader on the same thread.
 * <p>
 * The instrumentation is disabled by default. When it is disabled every entry point returns after one
 * volatile read of {@link #isEnabled()}, which is read once per batch, never per entity. A disabled
 * attribution therefore cannot change tick behavior or cost measurable time.
 * <p>
 * A tick that submits no batch (no accepted entity, a level that closed or an engine that shut down between
 * the snapshot and the batch) still counts one slice, with no segment recorded: the whole slice is then the
 * collection work of the server thread.
 */
@ApiStatus.Internal
public final class RegionEntityTickAttribution {
    private static volatile boolean enabled;
    private static long sliceStartNanos;
    private static long planNanos;
    private static long leaseNanos;
    private static long awaitNanos;
    private static long handoffNanos;
    private static long sliceNanos;
    private static long sliceCount;

    private RegionEntityTickAttribution() {}

    /**
     * {@return true when the attribution accumulates}
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the attribution and clears the accumulators, so a measurement starts from zero.
     */
    public static void setEnabled(boolean enabled) {
        RegionEntityTickAttribution.enabled = enabled;
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
    }

    /**
     * Opens the slice of the entity tick pass that is about to start on the server thread.
     */
    public static void beginSlice() {
        if (!enabled) {
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
        sliceNanos += System.nanoTime() - start;
        ++sliceCount;
    }

    /**
     * Closes the plan segment, which is the part of the slice the server thread spent collecting and admitting
     * the entities before it submitted the worker batch.
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
     * Adds the time the server thread spent grouping the accepted entities by region and acquiring the region
     * leases.
     */
    public static void recordLease(long durationNanos) {
        if (enabled) {
            leaseNanos += durationNanos;
        }
    }

    /**
     * Adds the time the server thread spent submitting the worker batch and waiting for the barrier,
     * including the main-thread handoffs it serviced while it waited.
     */
    public static void recordAwait(long durationNanos) {
        if (enabled) {
            awaitNanos += durationNanos;
        }
    }

    /**
     * Adds the time the server thread spent on the deferred work after the barrier: the deferred entity
     * section updates, the fallback entity ticks, the deferred server tasks and the batch completion.
     */
    public static void recordHandoff(long durationNanos) {
        if (enabled) {
            handoffNanos += durationNanos;
        }
    }

    /**
     * {@return the accumulated attribution of the server thread so far}
     */
    public static Snapshot snapshot() {
        return new Snapshot(planNanos, leaseNanos, awaitNanos, handoffNanos, sliceNanos, sliceCount);
    }

    /**
     * The accumulated attribution of the entity tick slice, in nanoseconds.
     *
     * @param sliceCount the number of slices the accumulators cover, so the segments can be reported per
     *                   tick
     */
    public record Snapshot(
            long planNanos,
            long leaseNanos,
            long awaitNanos,
            long handoffNanos,
            long sliceNanos,
            long sliceCount) {}
}
