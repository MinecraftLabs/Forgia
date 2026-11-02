/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the enable contract and the accumulation of the opt-in block-entity tick attribution.
 * <p>
 * The attribution is a diagnostics-only singleton: a disabled attribution must stay at zero no matter what
 * the measured path calls, and an enabled one must accumulate one slice per block-entity ticker pass with
 * segments that never exceed the slice.
 */
class RegionBlockEntityTickAttributionTest {
    @AfterEach
    void disable() {
        RegionBlockEntityTickAttribution.setEnabled(false);
    }

    @Test
    void disabledAttributionRecordsNothing() {
        RegionBlockEntityTickAttribution.setEnabled(false);

        RegionBlockEntityTickAttribution.beginSlice();
        RegionBlockEntityTickAttribution.endPlanSegment();
        RegionBlockEntityTickAttribution.recordLease(1_000L);
        RegionBlockEntityTickAttribution.recordAwait(1_000L);
        RegionBlockEntityTickAttribution.recordHandoff(1_000L);
        RegionBlockEntityTickAttribution.endSlice();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertFalse(RegionBlockEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.planNanos());
        assertEquals(0L, snapshot.leaseNanos());
        assertEquals(0L, snapshot.awaitNanos());
        assertEquals(0L, snapshot.handoffNanos());
        assertEquals(0L, snapshot.sliceNanos());
    }

    @Test
    void enabledAttributionRecordsOneSlicePerTickerPass() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.beginSlice();
        RegionBlockEntityTickAttribution.endPlanSegment();
        RegionBlockEntityTickAttribution.recordLease(1_000L);
        RegionBlockEntityTickAttribution.recordAwait(2_000L);
        RegionBlockEntityTickAttribution.recordHandoff(3_000L);
        RegionBlockEntityTickAttribution.endSlice();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertTrue(RegionBlockEntityTickAttribution.isEnabled());
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
    void resetClearsTheAccumulatorsOfAnEnabledAttribution() {
        RegionBlockEntityTickAttribution.setEnabled(true);
        RegionBlockEntityTickAttribution.beginSlice();
        RegionBlockEntityTickAttribution.recordAwait(5_000L);
        RegionBlockEntityTickAttribution.endSlice();

        RegionBlockEntityTickAttribution.reset();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertTrue(RegionBlockEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.sliceNanos());
        assertEquals(0L, snapshot.awaitNanos());
    }

    @Test
    void endSliceWithoutBeginSliceRecordsNoSlice() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.endSlice();

        assertEquals(0L, RegionBlockEntityTickAttribution.snapshot().sliceCount());
    }
}
