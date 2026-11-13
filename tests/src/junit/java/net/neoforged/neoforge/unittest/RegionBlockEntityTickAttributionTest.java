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
        RegionBlockEntityTickAttribution.recordAwait(1_000L, 500L);
        RegionBlockEntityTickAttribution.recordHandoff(1_000L);
        RegionBlockEntityTickAttribution.recordHandoffRemovals(1_000L, 2L);
        RegionBlockEntityTickAttribution.recordHandoffFallback(1_000L, 3L);
        RegionBlockEntityTickAttribution.recordHandoffMutations(1_000L, 4L);
        RegionBlockEntityTickAttribution.recordHandoffFinish(1_000L);
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION, 5);
        RegionBlockEntityTickAttribution.recordGroup(1_000L, 1_000L, 6, 6);
        RegionBlockEntityTickAttribution.recordTicker(100L);
        RegionBlockEntityTickAttribution.recordTickerAdmission(100L);
        RegionBlockEntityTickAttribution.recordTickerReservation(100L);
        RegionBlockEntityTickAttribution.recordTickerScope(100L);
        RegionBlockEntityTickAttribution.recordTickerBody(100L);
        RegionBlockEntityTickAttribution.recordDeferredRegistration(100L);
        RegionBlockEntityTickAttribution.recordOwnershipScope(100L);
        RegionBlockEntityTickAttribution.recordGroupPrepare(100L);
        RegionBlockEntityTickAttribution.recordGroupEnd(100L);
        RegionBlockEntityTickAttribution.recordAwaitPoll();
        RegionBlockEntityTickAttribution.endSlice();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertFalse(RegionBlockEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.planNanos());
        assertEquals(0L, snapshot.leaseNanos());
        assertEquals(0L, snapshot.awaitNanos());
        assertEquals(0L, snapshot.handoffNanos());
        assertEquals(0L, snapshot.sliceNanos());
        assertEquals(0L, snapshot.groups().groups());
        assertEquals(0L, snapshot.groups().tickerCount());
        assertEquals(0L, snapshot.awaitPolls());
        assertEquals(0L, snapshot.batchMakespanNanos());
        assertEquals(0L, snapshot.batchSpanNanos());
        assertEquals(0L, snapshot.tickers().tickers());
        assertEquals(0L, snapshot.tickers().admissionNanos());
        assertEquals(0L, snapshot.tickers().bodyNanos());
        assertEquals(0L, snapshot.tickers().deferredRegistrationNanos());
        assertEquals(0L, snapshot.tickers().ownershipScopeNanos());
        assertEquals(0L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION));
        assertEquals(0L, snapshot.handoffSteps().removalCount());
    }

    @Test
    void enabledAttributionRecordsOneSlicePerTickerPass() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.beginSlice();
        RegionBlockEntityTickAttribution.endPlanSegment();
        RegionBlockEntityTickAttribution.recordLease(1_000L);
        RegionBlockEntityTickAttribution.recordAwait(2_000L, 500L);
        RegionBlockEntityTickAttribution.recordHandoff(3_000L);
        RegionBlockEntityTickAttribution.endSlice();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertTrue(RegionBlockEntityTickAttribution.isEnabled());
        assertEquals(1L, snapshot.sliceCount());
        assertEquals(1_000L, snapshot.leaseNanos());
        assertEquals(2_000L, snapshot.awaitNanos());
        assertEquals(500L, snapshot.awaitCpuNanos());
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
        RegionBlockEntityTickAttribution.recordAwait(5_000L, 500L);
        RegionBlockEntityTickAttribution.recordGroup(7_000L, 7_000L, 8, 8);
        RegionBlockEntityTickAttribution.recordHandoffFallback(9_000L, 10L);
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT, 11);
        RegionBlockEntityTickAttribution.endSlice();

        RegionBlockEntityTickAttribution.reset();

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertTrue(RegionBlockEntityTickAttribution.isEnabled());
        assertEquals(0L, snapshot.sliceCount());
        assertEquals(0L, snapshot.sliceNanos());
        assertEquals(0L, snapshot.awaitNanos());
        assertEquals(0L, snapshot.awaitCpuNanos());
        assertEquals(0L, snapshot.groups().groups());
        assertEquals(0L, snapshot.groups().workers().size());
        assertEquals(0L, snapshot.handoffSteps().fallbackTickers());
        assertEquals(0L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT));
    }

    @Test
    void groupDistributionSeparatesABalancedBatchFromAnUnevenOne() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordGroup(100L, 100L, 10, 10);
        RegionBlockEntityTickAttribution.recordGroup(100L, 100L, 10, 10);
        RegionBlockEntityTickAttribution.recordGroup(0L, 0L, 0, 0);

        final RegionBlockEntityTickAttribution.GroupDistribution balanced = RegionBlockEntityTickAttribution.snapshot().groups();
        assertEquals(3L, balanced.groups());
        assertEquals(200L, balanced.nanos());
        assertEquals(100L, balanced.maxNanos());
        assertEquals(20L, balanced.tickers());
        assertEquals(10L, balanced.maxTickers());
        assertEquals(1L, balanced.emptyGroups());
        assertEquals(1L, balanced.idleGroups());

        RegionBlockEntityTickAttribution.recordGroup(5_000L, 5_000L, 10, 10);

        final RegionBlockEntityTickAttribution.GroupDistribution uneven = RegionBlockEntityTickAttribution.snapshot().groups();
        assertEquals(4L, uneven.groups());
        assertEquals(5_000L, uneven.maxNanos());
        // One slow group of four moves the slowest group above the mean, which is what the report reads as an
        // uneven batch.
        assertTrue(uneven.maxNanos() > uneven.nanos() / uneven.groups());
        // The latency distribution keeps the same four samples: three in the first 4 us bucket and one in the
        // second, so the median reports the cheap bucket and the maximum the expensive one.
        assertEquals(4L, uneven.latency().count());
        assertEquals(4_000L, uneven.latency().percentileNanos(0.50));
        assertEquals(8_000L, uneven.latency().percentileNanos(1.0));
    }

    @Test
    void latencyDistributionReportsTheBucketOfTheNearestRank() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        // Ten groups of 1 us and ten of 3 us, with a bucket width of 4 us: every sample lands in the first
        // bucket, so the nearest-rank percentile is the upper bound of that bucket.
        for (int index = 0; index < 20; ++index) {
            RegionBlockEntityTickAttribution.recordGroup(index < 10 ? 1_000L : 3_000L, 1_000L, 1, 1);
        }

        final RegionBlockEntityTickAttribution.LatencyDistribution latency = RegionBlockEntityTickAttribution.snapshot().groups().latency();
        assertEquals(20L, latency.count());
        assertEquals(4_000L, latency.percentileNanos(0.50));
        assertEquals(4_000L, latency.percentileNanos(0.99));
        assertEquals(4_000L, latency.percentileNanos(0.0));
    }

    @Test
    void groupDistributionCountsThePerWorkerLoad() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordGroup(500L, 400L, 4, 4);

        final RegionBlockEntityTickAttribution.GroupDistribution groups = RegionBlockEntityTickAttribution.snapshot().groups();
        assertEquals(1L, groups.workers().size());
        assertEquals(Thread.currentThread().getName(), groups.workers().get(0).thread());
        assertEquals(1L, groups.workers().get(0).groups());
        assertEquals(4L, groups.workers().get(0).tickers());
        assertEquals(500L, groups.workers().get(0).nanos());
        assertEquals(400L, groups.cpuNanos());
        assertEquals(0L, groups.retireNanos());
    }

    @Test
    void tickerTimeAndAwaitPollsAccumulateSeparately() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordGroup(10_000L, 9_000L, 2, 2);
        RegionBlockEntityTickAttribution.recordTicker(3_000L);
        RegionBlockEntityTickAttribution.recordTicker(4_000L);
        RegionBlockEntityTickAttribution.recordAwaitPoll();
        RegionBlockEntityTickAttribution.recordAwaitPoll();
        RegionBlockEntityTickAttribution.recordGroupRetire(600L);

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertEquals(7_000L, snapshot.groups().tickerNanos());
        assertEquals(2L, snapshot.groups().tickerCount());
        assertEquals(2L, snapshot.awaitPolls());
        // The wrapper of the group is what the group wall time has beyond the tickers' own tick.
        assertEquals(3_000L, snapshot.groups().nanos() - snapshot.groups().tickerNanos());
    }

    @Test
    void tickerPartsAndGroupWrapperAccumulateSeparately() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        // One group of two tickers: the group wall time is the ticker loop plus the setup, the ownership lease
        // and the release of the group, and every part of the ticker loop is reported on its own.
        RegionBlockEntityTickAttribution.recordGroupPrepare(500L);
        RegionBlockEntityTickAttribution.recordOwnershipScope(300L);
        RegionBlockEntityTickAttribution.recordTickerScope(40L);
        RegionBlockEntityTickAttribution.recordTickerAdmission(10L);
        RegionBlockEntityTickAttribution.recordTickerReservation(20L);
        RegionBlockEntityTickAttribution.recordTickerBody(2_000L);
        RegionBlockEntityTickAttribution.recordTicker(2_070L);
        RegionBlockEntityTickAttribution.recordTickerScope(40L);
        RegionBlockEntityTickAttribution.recordTickerAdmission(10L);
        RegionBlockEntityTickAttribution.recordTickerReservation(20L);
        RegionBlockEntityTickAttribution.recordTickerBody(3_000L);
        RegionBlockEntityTickAttribution.recordTicker(3_070L);
        RegionBlockEntityTickAttribution.recordDeferredRegistration(120L);
        RegionBlockEntityTickAttribution.recordDeferredRegistration(80L);
        RegionBlockEntityTickAttribution.recordOwnershipScope(300L);
        RegionBlockEntityTickAttribution.recordGroupEnd(100L);
        RegionBlockEntityTickAttribution.recordGroup(5_500L, 5_000L, 2, 2);

        final RegionBlockEntityTickAttribution.TickerAttribution tickers = RegionBlockEntityTickAttribution.snapshot().tickers();
        assertEquals(2L, tickers.tickers());
        assertEquals(20L, tickers.admissionNanos());
        assertEquals(40L, tickers.reservationNanos());
        assertEquals(80L, tickers.scopeNanos());
        assertEquals(5_000L, tickers.bodyNanos());
        assertEquals(200L, tickers.deferredRegistrationNanos());
        assertEquals(2L, tickers.deferredRegistrations());
        assertEquals(600L, tickers.ownershipScopeNanos());
        assertEquals(500L, tickers.groupPrepareNanos());
        assertEquals(100L, tickers.groupEndNanos());
    }

    @Test
    void batchMakespanSeparatesTheSlowestGroupFromTheWait() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordBatchMakespan(4_000L);
        RegionBlockEntityTickAttribution.recordBatchMakespan(9_000L);

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertEquals(13_000L, snapshot.batchMakespanNanos());
        assertEquals(9_000L, snapshot.batchMakespanMaxNanos());
    }

    @Test
    void batchPhasesSplitTheWaitIntoDispatchSpanAndTail() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        // The wait opens at 1_000, the first group starts at 1_100, the last ends at 5_100 and the wait ends at
        // 5_400: 100 of dispatch, 4_000 of span and 300 of tail.
        RegionBlockEntityTickAttribution.recordBatchPhases(1_000L, 5_400L, 1_100L, 5_100L);

        RegionBlockEntityTickAttribution.recordBatchSubmit(40L);

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertEquals(40L, snapshot.batchSubmitNanos());
        assertEquals(100L, snapshot.batchDispatchNanos());
        assertEquals(4_000L, snapshot.batchSpanNanos());
        assertEquals(300L, snapshot.batchTailNanos());
    }

    @Test
    void stalledGroupsAreCountedApartFromTheSlicesCost() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordGroup(200L, 200L, 2, 2);
        RegionBlockEntityTickAttribution.recordGroup(2_000_000L, 2_000_000L, 2, 2);

        final RegionBlockEntityTickAttribution.GroupDistribution groups = RegionBlockEntityTickAttribution.snapshot().groups();
        assertEquals(2L, groups.groups());
        assertEquals(1L, groups.stalledGroups());
        assertEquals(2_000_000L, groups.stalledNanos());
        assertEquals(200L, groups.nanos() - groups.stalledNanos());
    }

    @Test
    void handoffStepsAndFallbackReasonsAccumulateSeparately() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.recordHandoffRemovals(1_000L, 2L);
        RegionBlockEntityTickAttribution.recordHandoffFallback(3_000L, 4L);
        RegionBlockEntityTickAttribution.recordHandoffMutations(5_000L, 6L);
        RegionBlockEntityTickAttribution.recordHandoffFinish(7_000L);
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION, 8);
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT, 9);

        final RegionBlockEntityTickAttribution.Snapshot snapshot = RegionBlockEntityTickAttribution.snapshot();
        assertEquals(1_000L, snapshot.handoffSteps().removalNanos());
        assertEquals(2L, snapshot.handoffSteps().removalCount());
        assertEquals(3_000L, snapshot.handoffSteps().fallbackNanos());
        assertEquals(4L, snapshot.handoffSteps().fallbackTickers());
        assertEquals(5_000L, snapshot.handoffSteps().mutationNanos());
        assertEquals(6L, snapshot.handoffSteps().mutationCount());
        assertEquals(7_000L, snapshot.handoffSteps().finishNanos());
        assertEquals(8L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION));
        assertEquals(9L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT));
        assertEquals(0L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION));
        assertEquals(0L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.CHUNK_UNAVAILABLE));
        assertEquals(0L, snapshot.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNRESOLVABLE_REGION));
    }

    @Test
    void endSliceWithoutBeginSliceRecordsNoSlice() {
        RegionBlockEntityTickAttribution.setEnabled(true);

        RegionBlockEntityTickAttribution.endSlice();

        assertEquals(0L, RegionBlockEntityTickAttribution.snapshot().sliceCount());
    }
}
