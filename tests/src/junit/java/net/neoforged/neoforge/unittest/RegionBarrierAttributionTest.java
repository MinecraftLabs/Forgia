/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.neoforged.neoforge.server.threading.region.RegionBarrierAttribution;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the enable contract and the arithmetic of the opt-in tick barrier accounting.
 * <p>
 * The accounting is a diagnostics-only singleton, and its whole value rests on one identity: the three segments
 * of a wait add up to the wait, so the part no worker work explains is exactly {@code dispatch + tail}. A
 * disabled accounting must stay at zero no matter what the measured path calls.
 */
class RegionBarrierAttributionTest {
    @AfterEach
    void disable() {
        RegionBarrierAttribution.setEnabled(false);
    }

    @Test
    void disabledAccountingRecordsNothing() {
        RegionBarrierAttribution.setEnabled(false);

        RegionBarrierAttribution.recordBatch(RegionTickSlice.ENTITY_TICK, 0L, 5_000L, 1_000L, 1_500L, 4_000L, 3);
        RegionBarrierAttribution.recordHandoff(RegionTickSlice.ENTITY_TICK, 5_000L, 9_000L);

        final RegionBarrierAttribution.Snapshot snapshot = RegionBarrierAttribution.snapshot();
        assertFalse(RegionBarrierAttribution.isEnabled());
        for (final RegionBarrierAttribution.SliceSnapshot slice : snapshot.slices()) {
            assertEquals(0L, slice.batches(), "a disabled accounting must not count a batch of " + slice.slice());
            assertEquals(0L, slice.awaitNanos());
            assertEquals(0L, slice.handoffNanos());
        }
    }

    @Test
    void theThreeSegmentsAddUpToTheWait() {
        RegionBarrierAttribution.setEnabled(true);

        // Await 5 000 ns: 1 500 ns until the first group started, 2 500 ns of makespan, 1 000 ns of tail.
        RegionBarrierAttribution.recordBatch(RegionTickSlice.ENTITY_TICK, 0L, 5_000L, 400L, 1_500L, 4_000L, 3);
        RegionBarrierAttribution.recordHandoff(RegionTickSlice.ENTITY_TICK, 5_000L, 9_000L);

        final RegionBarrierAttribution.SliceSnapshot slice = RegionBarrierAttribution.snapshot().of(RegionTickSlice.ENTITY_TICK);
        assertEquals(1L, slice.batches());
        assertEquals(3L, slice.groups());
        assertEquals(5_000L, slice.awaitNanos());
        assertEquals(1_500L, slice.dispatchNanos());
        assertEquals(2_500L, slice.spanNanos());
        assertEquals(1_000L, slice.tailNanos());
        assertEquals(slice.awaitNanos(), slice.dispatchNanos() + slice.spanNanos() + slice.tailNanos());
        assertEquals(slice.dispatchNanos() + slice.tailNanos(), slice.overheadNanos());
        assertEquals(400L, slice.submitNanos());
        assertEquals(4_000L, slice.handoffNanos());
    }

    @Test
    void aBatchNoGroupStartedIsCountedAsDispatch() {
        RegionBarrierAttribution.setEnabled(true);

        RegionBarrierAttribution.recordBatch(RegionTickSlice.BLOCK_ENTITY_TICK, 1_000L, 4_000L, 100L, 0L, 0L, 0);

        final RegionBarrierAttribution.SliceSnapshot slice = RegionBarrierAttribution.snapshot().of(RegionTickSlice.BLOCK_ENTITY_TICK);
        assertEquals(3_000L, slice.awaitNanos());
        assertEquals(3_000L, slice.dispatchNanos(), "a wait no group explains is all dispatch");
        assertEquals(0L, slice.spanNanos());
        assertEquals(0L, slice.tailNanos());
    }

    @Test
    void theSegmentsAreAccountedPerSlice() {
        RegionBarrierAttribution.setEnabled(true);

        RegionBarrierAttribution.recordBatch(RegionTickSlice.ENTITY_TICK, 0L, 1_000L, 0L, 100L, 900L, 1);
        RegionBarrierAttribution.recordBatch(RegionTickSlice.CHUNK_TICK, 0L, 3_000L, 0L, 100L, 2_900L, 1);

        final RegionBarrierAttribution.Snapshot snapshot = RegionBarrierAttribution.snapshot();
        assertEquals(1_000L, snapshot.of(RegionTickSlice.ENTITY_TICK).awaitNanos());
        assertEquals(3_000L, snapshot.of(RegionTickSlice.CHUNK_TICK).awaitNanos());
        assertEquals(0L, snapshot.of(RegionTickSlice.SPAWN).awaitNanos());
    }

    @Test
    void theMedianOfTheBatchesIsWithinOneBucketOfTheSample() {
        RegionBarrierAttribution.setEnabled(true);

        // Five batches of 1, 2, 3, 4 and 5 microseconds: the median is the third one.
        for (int microseconds = 1; microseconds <= 5; ++microseconds) {
            final long awaitNanos = microseconds * 1_000L;
            RegionBarrierAttribution.recordBatch(RegionTickSlice.SPAWN, 0L, awaitNanos, 0L, 0L, awaitNanos, 1);
        }

        final RegionBarrierAttribution.SliceSnapshot slice = RegionBarrierAttribution.snapshot().of(RegionTickSlice.SPAWN);
        assertTrue(slice.awaitP50Nanos() >= 3_000L && slice.awaitP50Nanos() <= 3_000L + 8_000L,
                "the p50 of 1..5 us batches must be the bucket of 3 us, not " + slice.awaitP50Nanos());
    }

    @Test
    void theReportHasOneRowPerSliceAndTheDocumentedColumns() {
        RegionBarrierAttribution.setEnabled(true);

        RegionBarrierAttribution.recordBatch(RegionTickSlice.ENTITY_TICK, 0L, 1_000L, 0L, 100L, 900L, 1);
        final List<String> rows = RegionBarrierAttribution.formatRows(RegionBarrierAttribution.snapshot(), "TEST", 2);

        assertEquals(RegionTickSlice.values().length + 1, rows.size(), "one header row and one row per slice");
        final int columns = rows.getFirst().substring("TEST_HEADER ".length()).split(",", -1).length;
        for (final String row : rows.subList(1, rows.size())) {
            assertEquals(columns, row.substring("TEST ".length()).split(",", -1).length, "column count of " + row);
        }
        assertTrue(rows.stream().anyMatch(row -> row.startsWith("TEST ENTITY_TICK,1,0.500,")), "the entity row reports its batch per tick");
    }
}
