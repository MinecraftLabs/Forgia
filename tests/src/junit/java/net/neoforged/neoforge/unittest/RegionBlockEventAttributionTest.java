/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.region.RegionBlockEventAttribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the enable contract, the level filter and the accumulation of the opt-in block event attribution.
 * <p>
 * The attribution is a diagnostics-only singleton: a disabled attribution must stay at zero no matter what the
 * measured path calls, a phase of a level that is not the measured one must not contribute, and the events of a
 * measured phase must be reported with a distribution that covers all of them.
 */
class RegionBlockEventAttributionTest {
    @AfterEach
    void disable() {
        RegionBlockEventAttribution.setEnabled(false);
        RegionBlockEventAttribution.setMeasuredLevel(null);
    }

    @Test
    void disabledAttributionRecordsNothing() {
        RegionBlockEventAttribution.setEnabled(false);

        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        RegionBlockEventAttribution.endEvent(RegionBlockEventAttribution.beginEvent());
        RegionBlockEventAttribution.endPhase();

        final RegionBlockEventAttribution.Snapshot snapshot = RegionBlockEventAttribution.snapshot();
        assertFalse(RegionBlockEventAttribution.isEnabled());
        assertEquals(0L, snapshot.phaseCount());
        assertEquals(0L, snapshot.phaseNanos());
        assertEquals(0L, snapshot.phaseMaxNanos());
        assertEquals(0L, snapshot.eventCount());
        assertEquals(0L, snapshot.eventNanos());
        assertEquals(0L, snapshot.latency().count());
    }

    @Test
    void onePhaseWithTwoEventsIsReportedPerPhaseAndPerEvent() {
        RegionBlockEventAttribution.setEnabled(true);

        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        for (int index = 0; index < 2; index++) {
            RegionBlockEventAttribution.endEvent(RegionBlockEventAttribution.beginEvent());
        }
        RegionBlockEventAttribution.endPhase();

        final RegionBlockEventAttribution.Snapshot snapshot = RegionBlockEventAttribution.snapshot();
        assertTrue(RegionBlockEventAttribution.isEnabled());
        assertEquals(1L, snapshot.phaseCount());
        assertEquals(2L, snapshot.eventCount());
        // The events run inside the phase, so their sum is bounded by it, and the phase is its own maximum.
        assertTrue(snapshot.eventNanos() >= 0L);
        assertTrue(snapshot.eventNanos() <= snapshot.phaseNanos());
        assertEquals(snapshot.phaseNanos(), snapshot.phaseMaxNanos());
        assertEquals(2L, snapshot.latency().count());
        assertTrue(snapshot.latency().percentileNanos(0.50) > 0L);
    }

    @Test
    void theEventsOfAnUnmeasuredPhaseAreNotCounted() {
        final ServerLevel measured = mock(ServerLevel.class);
        RegionBlockEventAttribution.setMeasuredLevel(measured);
        RegionBlockEventAttribution.setEnabled(true);

        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        RegionBlockEventAttribution.endEvent(RegionBlockEventAttribution.beginEvent());
        RegionBlockEventAttribution.endPhase();

        final RegionBlockEventAttribution.Snapshot unmeasured = RegionBlockEventAttribution.snapshot();
        assertEquals(0L, unmeasured.phaseCount());
        assertEquals(0L, unmeasured.eventCount());

        RegionBlockEventAttribution.beginPhase(measured);
        RegionBlockEventAttribution.endEvent(RegionBlockEventAttribution.beginEvent());
        RegionBlockEventAttribution.endPhase();

        final RegionBlockEventAttribution.Snapshot snapshot = RegionBlockEventAttribution.snapshot();
        assertEquals(1L, snapshot.phaseCount());
        assertEquals(1L, snapshot.eventCount());
    }

    @Test
    void theSlowestPhaseOfTheWindowIsKept() {
        RegionBlockEventAttribution.setEnabled(true);

        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        RegionBlockEventAttribution.endPhase();
        final long shortPhase = RegionBlockEventAttribution.snapshot().phaseMaxNanos();

        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        final long until = System.nanoTime() + 2_000_000L;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        RegionBlockEventAttribution.endPhase();

        final RegionBlockEventAttribution.Snapshot snapshot = RegionBlockEventAttribution.snapshot();
        assertEquals(2L, snapshot.phaseCount());
        assertTrue(snapshot.phaseMaxNanos() >= 2_000_000L, "the slow phase has to be the maximum: " + snapshot);
        assertTrue(snapshot.phaseMaxNanos() > shortPhase);
    }

    @Test
    void enablingClearsThePreviousWindow() {
        RegionBlockEventAttribution.setEnabled(true);
        RegionBlockEventAttribution.beginPhase(mock(ServerLevel.class));
        RegionBlockEventAttribution.endEvent(RegionBlockEventAttribution.beginEvent());
        RegionBlockEventAttribution.endPhase();
        assertEquals(1L, RegionBlockEventAttribution.snapshot().eventCount());

        RegionBlockEventAttribution.setEnabled(true);

        final RegionBlockEventAttribution.Snapshot snapshot = RegionBlockEventAttribution.snapshot();
        assertEquals(0L, snapshot.phaseCount());
        assertEquals(0L, snapshot.eventCount());
    }
}
