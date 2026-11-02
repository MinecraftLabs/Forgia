/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Snapshot of the internal region threading runtime state.
 * <p>
 * The transfer counters report the recorded cross-region entity transfer transaction: the decisions that
 * still wait for a commit, the terminal results kept for idempotent replays, and the recordings the journal
 * bound refused. A refused recording leaves the caller on the vanilla path, so a growing rejection count is
 * the only observable sign that the pending bound of the journal is exhausted.
 * <p>
 * The deferred counters report the ownership handoffs of a finished entity batch. A deferred move whose
 * destination was captured at defer time is committed through the recorded transaction; the legacy count
 * reports the handoffs the transaction refused and that were therefore published from the live position
 * like before, and the retried count reports the handoffs a busy region delayed to a later barrier.
 */
public record RegionThreadingDiagnostics(
        int regionCount,
        int runningRegionCount,
        int submittedRegionCount,
        int sectionCount,
        int loadedChunkCount,
        int pendingRecalculationCount,
        long structuralChangeCount,
        long splitCheckRequestCount,
        long mergeCheckRequestCount,
        int trackedEntityCount,
        int trackedEntityTaskCount,
        int workerThreadCount,
        boolean workerExecutionEnabled,
        boolean globalWorkerExecutionEnabled,
        boolean entityTickWorkerExecutionEnabled,
        boolean blockEntityTickWorkerExecutionEnabled,
        boolean scheduledTickWorkerExecutionEnabled,
        boolean randomTickWorkerExecutionEnabled,
        long entityTicksOnWorkers,
        long skippedEntityTicks,
        long fallbackEntityTicks,
        long deferredEntityTicks,
        long blockEntityTicksOnWorkers,
        long skippedBlockEntityTicks,
        long fallbackBlockEntityTicks,
        long scheduledTicksOnWorkers,
        long skippedScheduledTicks,
        long fallbackScheduledTicks,
        long randomTicksOnWorkers,
        long skippedRandomTicks,
        long fallbackRandomTicks,
        boolean globalRegionRunning,
        boolean globalRegionSubmitted,
        long rejectedWorkerTaskCount,
        long failedQueuedTaskCount,
        long ownershipViolationCount,
        long globalTickCount,
        long globalLastTickDurationNanos,
        long totalRegionTickCount,
        long maxRegionLastTickDurationNanos,
        long cappedDrainCount,
        RegionPolicy regionPolicy,
        RegionEntityStorageMode entityStorageMode,
        boolean scheduledTickWorkerDispatchEffective,
        boolean randomTickWorkerDispatchEffective,
        int pendingEntityTransferCount,
        int rememberedEntityTransferResultCount,
        long rejectedEntityTransferRecordCount,
        long legacyDeferredEntityMoveCount,
        long retriedDeferredEntityMoveCount) {}
