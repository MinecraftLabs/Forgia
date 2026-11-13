/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Fixed-interval timing helper for future region tick scheduling.
 */
final class RegionTickTimer {
    static final long TICK_INTERVAL_NANOS = 50_000_000L;

    private final long intervalNanos;
    private final RegionSchedule schedule;

    RegionTickTimer(long firstTickNanos) {
        this(firstTickNanos, TICK_INTERVAL_NANOS);
    }

    RegionTickTimer(long firstTickNanos, long intervalNanos) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("intervalNanos must be > 0");
        }
        this.intervalNanos = intervalNanos;
        // The first tick is due immediately; subsequent ticks are spaced by the interval.
        this.schedule = new RegionSchedule(firstTickNanos - intervalNanos);
    }

    long nextDeadline() {
        return this.schedule.deadline(this.intervalNanos);
    }

    boolean isDue(long nowNanos) {
        return nowNanos >= this.nextDeadline();
    }

    int ticksBehind(long nowNanos) {
        return this.schedule.periodsAhead(this.intervalNanos, nowNanos);
    }

    void markTickStarted(long startNanos) {
        this.schedule.setLastPeriod(startNanos);
    }

    void advanceBy(int ticks) {
        this.schedule.advanceBy(ticks, this.intervalNanos);
    }
}
