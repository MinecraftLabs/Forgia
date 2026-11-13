/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The phase state of one level's tick.
 * <p>
 * The levels are ticked one after another by the server thread, so the state has a single writer and only the
 * phase itself (and the thread that owns the level tick) is volatile: a worker of a batch reads the phase to
 * observe the region phase of its own group, and a diagnostics reader observes the phase of any level.
 * <p>
 * A level tick that throws skips the close of its global phase. The next {@link #beginGlobalPhase()} therefore
 * replaces the leftover segment instead of nesting a second one, and counts it in
 * {@link #abandonedGlobalSegments()}: the phase model never depends on a tick reaching its tail.
 */
final class LevelTickPhaseState {
    /** Upper bound of the ownership violations one level reports, so a broken slice cannot flood the log. */
    private static final int MAX_REPORTED_VIOLATIONS = 8;

    private final AtomicLong globalPhaseCount = new AtomicLong();
    private final AtomicLong regionPhaseCount = new AtomicLong();
    private final AtomicLong regionPhasesWithoutGlobalPhase = new AtomicLong();
    private final AtomicLong abandonedGlobalSegments = new AtomicLong();
    private final AtomicLong reportedViolations = new AtomicLong();
    private final AtomicReference<LevelTickPhase> phase = new AtomicReference<>(LevelTickPhase.GLOBAL);
    private final AtomicReference<Thread> levelTickOwner = new AtomicReference<>();
    private boolean globalPhaseOpen;
    private int regionDepth;
    private Thread regionPhaseOwner;

    /** {@return the phase the level is in; {@link LevelTickPhase#GLOBAL} outside a level tick} */
    LevelTickPhase phase() {
        return this.phase.get();
    }

    /** {@return the number of global segments opened so far} */
    long globalPhaseCount() {
        return this.globalPhaseCount.get();
    }

    /** {@return the number of region phases entered so far} */
    long regionPhaseCount() {
        return this.regionPhaseCount.get();
    }

    /** {@return the region phases entered while no global segment of the level was open} */
    long regionPhasesWithoutGlobalPhase() {
        return this.regionPhasesWithoutGlobalPhase.get();
    }

    /** {@return the global segments a previous level tick left open, i.e. level ticks that threw} */
    long abandonedGlobalSegments() {
        return this.abandonedGlobalSegments.get();
    }

    /**
     * Opens the global phase of the level tick: the server thread owns the level's global statements from now
     * until {@link #endGlobalPhase()}.
     */
    void beginGlobalPhase() {
        if (this.regionDepth > 0) {
            this.reportViolation("global phase started while a region batch of the level was in flight");
        }
        if (this.globalPhaseOpen) {
            // The previous level tick threw and skipped its close: the leftover segment is replaced, never
            // nested, so one entry point always matches one close.
            this.abandonedGlobalSegments.incrementAndGet();
            this.globalPhaseOpen = false;
            this.levelTickOwner.set(null);
        }
        this.globalPhaseOpen = true;
        this.levelTickOwner.set(Thread.currentThread());
        this.globalPhaseCount.incrementAndGet();
        if (this.regionDepth == 0) {
            this.phase.set(LevelTickPhase.GLOBAL);
        }
    }

    /**
     * Closes the global phase of the level tick. The level is then outside its tick, which reads as
     * {@link LevelTickPhase#GLOBAL}.
     */
    void endGlobalPhase() {
        if (!this.globalPhaseOpen) {
            this.reportViolation("global phase closed without an open segment");
            return;
        }
        this.globalPhaseOpen = false;
        this.levelTickOwner.set(null);
        if (this.regionDepth == 0) {
            this.phase.set(LevelTickPhase.GLOBAL);
        }
    }

    /**
     * Enters the region phase of one slice batch. A batch may only start from the global phase of the thread that
     * owns the level tick, and only when no other region batch of the level is in flight.
     */
    void beginRegionPhase() {
        final Thread current = Thread.currentThread();
        if (this.regionDepth == 0) {
            if (!this.globalPhaseOpen) {
                // Direct engine use outside a level tick, e.g. a plan executed by a mechanics test or a
                // benchmark. The region work is still exactly one batch of the level, so it is counted but not
                // reported as a violation.
                this.regionPhasesWithoutGlobalPhase.incrementAndGet();
            } else if (this.levelTickOwner.get() != current) {
                this.reportViolation("region phase started from a thread that does not own the level tick");
            }
            this.regionPhaseOwner = current;
            this.phase.set(LevelTickPhase.REGION);
        } else if (this.regionPhaseOwner != current) {
            this.reportViolation("region phase started while another region batch of the level was in flight");
        }
        this.regionDepth++;
        this.regionPhaseCount.incrementAndGet();
    }

    /**
     * Leaves the region phase. The level is back in its global phase when the last batch of the segment ended.
     */
    void endRegionPhase() {
        if (this.regionDepth == 0) {
            this.reportViolation("region phase closed without an open batch");
            return;
        }
        this.regionDepth--;
        if (this.regionDepth == 0) {
            this.regionPhaseOwner = null;
            this.phase.set(LevelTickPhase.GLOBAL);
        }
    }

    /** {@return true when a region batch of the level is in flight} */
    boolean isRegionPhase() {
        return this.regionDepth > 0;
    }

    /**
     * Reports one ownership violation of the phase model.
     * <p>
     * The violation is always counted in the ownership diagnostics. The message is reported - logged, or thrown
     * by the {@code THROW} mode - for the first {@link #MAX_REPORTED_VIOLATIONS} violations of this level, so a
     * broken slice that violates the model every region of every tick cannot flood the log while the count stays
     * exact.
     */
    private void reportViolation(String message) {
        if (this.reportedViolations.incrementAndGet() <= MAX_REPORTED_VIOLATIONS) {
            RegionOwnershipViolationHandler.handle("Level tick phase violation: " + message);
        } else {
            RegionOwnershipViolationHandler.recordViolation();
        }
    }
}
