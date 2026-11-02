/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.neoforged.neoforge.server.threading.region.RegionEntityTickAttribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the enable contract and the accumulation of the opt-in entity tick attribution.
 * <p>
 * The attribution is a diagnostics-only singleton: a disabled attribution must stay at zero no matter what
 * the measured path calls, and an enabled one must accumulate one slice per entity tick pass with segments
 * that never exceed the slice.
 */
class RegionEntityTickAttributionTest {
    @AfterEach
    void disable() {
        RegionEntityTickAttribution.setEnabled(false);
    }

    @Test
    void disabledAttributionRecordsNothing() {
        RegionEntityTickAttribution.setEnabled(false);

        RegionEntityTickAttribution.beginSlice();
        RegionEntityTickAttribution.endPlanSegment();
        RegionEntityTickAttribution.recordLease(1_000L);
        RegionEntityTickAttribution.recordAwait(1_000L);
        RegionEntityTickAttribution.recordHandoff(1_000L);
        RegionEntityTickAttribution.endSlice();

        final RegionEntityTickAttribution.Snapshot snapshot = RegionEntityTickAttribution.snapshot();
        assertFalse(RegionEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.planNanos());
        assertEquals(0L, snapshot.leaseNanos());
        assertEquals(0L, snapshot.awaitNanos());
        assertEquals(0L, snapshot.handoffNanos());
        assertEquals(0L, snapshot.sliceNanos());
    }

    @Test
    void enabledAttributionRecordsOneSlicePerEntityTickPass() {
        RegionEntityTickAttribution.setEnabled(true);

        RegionEntityTickAttribution.beginSlice();
        RegionEntityTickAttribution.endPlanSegment();
        RegionEntityTickAttribution.recordLease(1_000L);
        RegionEntityTickAttribution.recordAwait(2_000L);
        RegionEntityTickAttribution.recordHandoff(3_000L);
        RegionEntityTickAttribution.endSlice();

        final RegionEntityTickAttribution.Snapshot snapshot = RegionEntityTickAttribution.snapshot();
        assertTrue(RegionEntityTickAttribution.isEnabled());
        assertEquals(1L, snapshot.sliceCount());
        assertEquals(1_000L, snapshot.leaseNanos());
        assertEquals(2_000L, snapshot.awaitNanos());
        assertEquals(3_000L, snapshot.handoffNanos());
        // The plan segment is the wall time from the open slice to the batch submission, so it is bounded by
        // the slice.
        assertTrue(snapshot.planNanos() >= 0L);
        assertTrue(snapshot.planNanos() <= snapshot.sliceNanos());
    }

    @Test
    void aSliceWithoutSegmentsStillCounts() {
        RegionEntityTickAttribution.setEnabled(true);

        RegionEntityTickAttribution.beginSlice();
        RegionEntityTickAttribution.endSlice();

        final RegionEntityTickAttribution.Snapshot snapshot = RegionEntityTickAttribution.snapshot();
        assertEquals(1L, snapshot.sliceCount());
        assertEquals(0L, snapshot.leaseNanos());
        assertEquals(0L, snapshot.awaitNanos());
        assertEquals(0L, snapshot.handoffNanos());
        assertTrue(snapshot.sliceNanos() >= snapshot.planNanos());
    }

    @Test
    void resetClearsTheAccumulatorsOfAnEnabledAttribution() {
        RegionEntityTickAttribution.setEnabled(true);
        RegionEntityTickAttribution.beginSlice();
        RegionEntityTickAttribution.recordAwait(5_000L);
        RegionEntityTickAttribution.endSlice();

        RegionEntityTickAttribution.reset();

        final RegionEntityTickAttribution.Snapshot snapshot = RegionEntityTickAttribution.snapshot();
        assertTrue(RegionEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.sliceNanos());
        assertEquals(0L, snapshot.awaitNanos());
    }

    @Test
    void endSliceWithoutBeginSliceRecordsNoSlice() {
        RegionEntityTickAttribution.setEnabled(true);

        RegionEntityTickAttribution.endSlice();

        assertEquals(0L, RegionEntityTickAttribution.snapshot().sliceCount());
    }
}
