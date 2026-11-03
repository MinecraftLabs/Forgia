/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;

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
 * <p>
 * The two section counters report the level effects of a worker entity move that the batch barrier applied
 * or dropped. An applied count is one entity section publication plus its target chunk acquisition, both
 * run on the server thread. A dropped count is one update that did not complete: the engine shut down
 * (including the updates a shut-down batch releases without applying), the level identity closed, the
 * entity was removed or left the level, or the publication or the chunk acquisition threw. A dropped
 * update never leaves a section publication behind, and a failing update never costs the updates queued
 * behind it.
 * <p>
 * The three removal counters report the level callback of a worker entity removal that the batch barrier
 * applied, dropped or turned into a no-op. An applied count is one normal removal path on the server thread:
 * the identity departure, the section removal, the tracking and ticking stop, the destruction callback and
 * the region retirement. A dropped count is one removal that did not complete (the engine shut down, the
 * level identity closed, the entity left the level, it was revived, or its callback threw). A repeated count
 * is one removal request that was a no-op because the entity was already recorded or its removal had already
 * been applied; a repeat is never a second removal.
 * <p>
 * {@link #entityAddRejectedOnWorker()} counts the entity spawns a native region worker attempted and the
 * storage guard refused: death loot, experience orbs, projectiles and every other entity a worker-side tick
 * tries to create. It counts both the position write of the new entity outside the leased region and the
 * admission into the level that could not be deferred, so one refused spawn attempt is counted once. A spawn
 * the lease records is not counted here: it is deferred and shows up in the admission counters instead.
 * <p>
 * The three admission counters report the entity additions a worker recorded and the batch barrier applied
 * or dropped. A recorded admission is one riding graph the worker constructed and configured inside its
 * leased region; an applied admission is that graph admitted into the level by the unmodified vanilla path
 * on the server thread. A dropped admission is one that never reached the level - the engine shut down, the
 * level identity closed, the entity was removed before the barrier, a member of the graph was already in the
 * level, or the vanilla path refused a member - and it <b>loses the entity</b>, because the entity was never
 * in the level and nothing retries it. That is why a dropped admission is always counted and never silently
 * skipped.
 * <p>
 * The two ownership counters report the entity tick lease of a region worker group. An allowance counts
 * one grant of a vanilla entity mutation the lease may own: it is the lease decision, not a completed
 * mutation, so one vanilla call can record several allowances when it enters several granted guard sites.
 * An ownership rejection counts one worker entity mutation the active lease refused, including the removal
 * operations and the position changes of an entity that is already added to a level. Every such refusal
 * also increments {@link #ownershipViolationCount()}, because the guard still reports the operation and
 * throws before it mutates anything. A worker entity mutation without an active lease is not an ownership
 * decision: it only increments {@link #ownershipViolationCount()}.
 * <p>
 * The three query counters report the region-scoped entity query of a leased native worker. An allowed
 * query is one {@code Level.getEntities} call that ran against the lease instead of the legacy guard, a
 * truncated query is one allowed query whose box extended past the leased region and was clamped to it, and
 * a rejected query is one legacy query the storage guard refused on a native worker. A rejected query is
 * always counted with {@link #ownershipViolationCount()} as well, because the guard reports the operation and
 * throws. The counters make the clamp-and-count contract observable: a truncated query never silently loses
 * the entities outside the lease.
 * <p>
 * {@link #regionChunkSize()} is the edge length in chunks of one region coordinate of the installed
 * layout. For a fixed policy it is the configured fixed region size, so the status output and every
 * diagnostic consumer read the same value the engine and the physical section partitions use.
 * <p>
 * The four block-entity chunk counters report the chunks the chunk-indexed plan did not hand to a worker, by
 * reason. A non-ticking chunk is out of block ticking range or the tick rate is frozen, a chunk whose entities
 * are still loading keeps the vanilla skip, and a chunk that is no longer loaded has no snapshot at all; none
 * of them ticks this tick, exactly like the vanilla ticker loop skips them. A stale snapshot counts a chunk
 * whose ticker set or chunk instance changed between the plan and the worker tick, so its tickers were
 * revalidated and ticked on the server thread instead of from the stale snapshot. The first three counters
 * therefore measure work the plan avoided, and the last one measures the safety net.
 * <p>
 * The three chunk counters report the entity and block-entity ticks that did not run on a worker because a
 * chunk they need was not available. A guard refusal counts one chunk request that a native region worker
 * refused instead of blocking on the server chunk source
 * ({@link RegionWorkerChunkUnavailableException}); the same request is counted once, so a refusal is never
 * double counted per entity or ticker. A chunk fallback entity tick counts one entity that a worker handed
 * to the server-thread fallback after such a refusal; it is ticked there exactly once. A pre-load refusal
 * counts one entity that was never submitted to a worker because its own chunk neighborhood was not
 * loaded inside the pre-load margin. The counters therefore separate the ticks that never left the server
 * thread (pre-load refusal) from the ticks that were attempted on a worker and completed on the server
 * thread (chunk fallback), and the guard refusals count the chunk requests behind the latter.
 * <p>
 * {@link #regionizerWriteLockAcquisitions()} counts the write-lock acquisitions of the regionizer, so the
 * publication scope of one entity batch barrier is observable: a barrier that publishes its deferred
 * ownership updates in one batch takes one write lock for all of them, and a barrier that publishes them one
 * by one takes one per update. {@link #batchedDeferredEntityMoves()} counts the deferred updates a barrier
 * resolved through that batch, either by publishing an untracked entity or by reporting a cross-region move
 * to the recorded transfer transaction; {@link #unchangedDeferredEntityMoves()} counts the updates that named
 * the region their entity was already mapped to and therefore published nothing;
 * {@link #refusedDeferredEntityMoveBatches()} counts the batches the regionizer refused as a whole, whose
 * updates the barrier then published per entity. {@link #scheduledTaskMigrations()} counts the scheduled
 * entity tasks a move notification had to migrate to the region that now contains the task's entity.
 * <p>
 * {@link #regionTicks()} holds the per-region tick metrics of every live region and {@link #regionMetrics()}
 * their aggregate; both are immutable snapshots taken at the same moment as this record.
 * {@link RegionTickSnapshot} documents what region MSPT and region TPS mean, the window they are measured
 * over and how a tick is classified as keeping up or behind. A diagnostics call without any live region -
 * including a call on a server that never installed the engine - reports an empty region list and a summary
 * of zero regions, and such a server records no per-region metric at all.
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
        long entityMutationsAllowedByOwnership,
        long entityMutationsRejectedByOwnership,
        long entityQueriesAllowedByLease,
        long entityQueriesTruncatedByLease,
        long entityQueriesRejected,
        long blockEntityTicksOnWorkers,
        long skippedBlockEntityTicks,
        long fallbackBlockEntityTicks,
        long nonTickingBlockEntityChunks,
        long entitiesLoadingBlockEntityChunks,
        long missingBlockEntityChunks,
        long staleBlockEntityChunkSnapshots,
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
        int regionChunkSize,
        RegionEntityStorageMode entityStorageMode,
        boolean scheduledTickWorkerDispatchEffective,
        boolean randomTickWorkerDispatchEffective,
        int pendingEntityTransferCount,
        int rememberedEntityTransferResultCount,
        long rejectedEntityTransferRecordCount,
        long legacyDeferredEntityMoveCount,
        long retriedDeferredEntityMoveCount,
        long deferredEntitySectionUpdatesApplied,
        long droppedDeferredEntitySectionUpdates,
        long deferredEntityRemovalsApplied,
        long droppedDeferredEntityRemovals,
        long repeatedDeferredEntityRemovals,
        long deferredEntityAdditionsRecorded,
        long deferredEntityAdditionsApplied,
        long droppedDeferredEntityAdditions,
        long entityAddRejectedOnWorker,
        long chunkGuardRefusals,
        long chunkFallbackEntityTicks,
        long chunkPreloadRefusedEntityTicks,
        long regionizerWriteLockAcquisitions,
        long batchedDeferredEntityMoves,
        long unchangedDeferredEntityMoves,
        long refusedDeferredEntityMoveBatches,
        long scheduledTaskMigrations,
        RegionMetricsSummary regionMetrics,
        List<RegionTickSnapshot> regionTicks) {}
