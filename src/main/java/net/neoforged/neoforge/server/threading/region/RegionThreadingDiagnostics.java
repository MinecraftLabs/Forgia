/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.Map;

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
 * behind it. {@link #droppedDeferredEntitySectionUpdatesForRemovedEntity()} is the subset of the dropped
 * updates whose entity was removed, left the level or was never added to it between the move and the
 * barrier: the removal already retired the section membership, so dropping the publication loses nothing.
 * The rest of the dropped count is the update that really lost a move.
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
 * The four phase counters report the explicit global/region phase of the level tick ({@link LevelTickPhase}).
 * {@link #globalPhases()} counts the global segments the engine bridge opened, {@link #regionPhases()} the
 * region batches it entered, {@link #regionPhasesWithoutGlobalPhase()} the region batches that ran outside a
 * level tick (direct engine use from a mechanics test or a benchmark, never production) and
 * {@link #abandonedGlobalSegments()} the global segments a level tick left open because it threw. A phase
 * violation - a global segment opened while a batch was in flight, or two batches of one level at once - is
 * counted with {@link #ownershipViolationCount()}.
 * <p>
 * The seven mailbox counters report the cross-region mailbox of every level that has one
 * ({@link RegionMailbox}). {@link #mailboxAcceptedMessages()} counts the posts that were queued,
 * {@link #mailboxDeliveredMessages()} the messages a delivery actually ran, and the remaining counters the
 * messages that never ran: {@link #mailboxRefusedFullMessages()} the posts refused by a full queue,
 * {@link #mailboxRefusedClosedMessages()} the posts refused by a closed mailbox,
 * {@link #mailboxDroppedRetiredMessages()} the messages whose target region did not exist or retired before the
 * delivery, {@link #mailboxDroppedOnCloseMessages()} the messages dropped by a close or by a post that raced it,
 * and {@link #mailboxDeliveryFailures()} the delivered messages whose body threw. Every message reaches exactly
 * one of accepted, refused or dropped, so no message is ever lost silently.
 * <p>
 * The per-slice block-update counters report the light rechecks and block update publications every region batch
 * defers: one batch of the block-entity, entity, chunk or scheduled tick slice records the writes of its workers
 * and the server thread applies them at its barrier, because the light engine and the block update publication
 * of a level are level-wide. {@link BlockUpdateCounters} reports the records each slice applied, the records it
 * dropped because its level closed and the units it refused before their body because the batch could not
 * reserve them.
 * <p>
 * {@link TickFailureCounters} reports the tick failures the worker groups of each slice recorded, counted per
 * slice. A worker that catches a failing tick records the throwable and the context and stops its group; the
 * server thread reports it after the barrier and after the handoff of the batch, so the crash report of a
 * failing tick is never built on a worker that the server thread is waiting for. A counted failure is one
 * failure, counted on the worker and reported once by the server thread.
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
 * The spawn counters report the natural spawning slice. A spawn chunk on a worker is one chunk whose spawn body
 * ran on the worker of its region, against that region's own spawn state. Every other chunk is counted by the
 * reason it stayed on the server thread: the vanilla spawn gate refused it (no body runs, exactly like vanilla),
 * a neighboring chunk is not loaded, the batch had no room for the block writes of its body, it was collected
 * after its batch already ran, its region could not be leased or the engine had already shut down. None of them
 * is dropped: every chunk that keeps the server thread spawns there, from the vanilla level-wide state.
 * {@link #truncatedSpawnChunks()} counts chunks whose worker body a chunk source refusal truncated after it had
 * already spawned: the spawns it recorded are admitted, the rest of the attempt is lost. {@link #capRefusedSpawns()}
 * counts the mob categories a region's spawn state refused because the level-wide cap was reached, and
 * {@link #refusedSpawnAdditions()} counts spawn attempts that could not be recorded for the barrier at all, so no
 * entity was created.
 * <p>
 * {@link #regionTicks()} holds the per-region tick metrics of every live region and {@link #regionMetrics()}
 * their aggregate; both are immutable snapshots taken at the same moment as this record.
 * {@link RegionTickSnapshot} documents what region MSPT and region TPS mean, the window they are measured
 * over and how a tick is classified as keeping up or behind. A diagnostics call without any live region -
 * including a call on a server that never installed the engine - reports an empty region list and a summary
 * of zero regions, and such a server records no per-region metric at all.
 * <p>
 * {@link #serverThreadOnlyEntityTicks()} counts the ticks a region handed to the server thread because the
 * entity is always-ticking (a player).
 * {@link #serverThreadOnlyEntityTicksByReason()} counts the ticks a region handed to the server thread because
 * of a per-type declaration ({@link RegionThreadingEngine#serverThreadOnlyEntity(Class, String)}), one entry
 * per reason token, ordered by token. It answers why an entity tick stayed on the server thread and not only
 * how many did; a type without a declaration keeps the worker and never appears here.
 * <p>
 * {@link #deferredPortalTransitions()}, {@link #appliedPortalTransitions()} and
 * {@link #refusedPortalTransitions()} report the portal transitions of worker-ticked entities. The
 * destination search of a transition reads the {@code PoiManager} of the destination level, which is one
 * storage per level, and every entity type reaches it from its own tick, so a worker records the transition
 * instead of resolving it. A deferred transition is one record a batch barrier drained; applied is one the
 * server thread resolved and applied in that same tick; refused is one it could not apply (the engine shut
 * down, the level identity closed, the entity was removed or left the level). A refused transition is never
 * lost: the entity keeps its portal state, so a later tick records it again.
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
        boolean chunkTickWorkerExecutionEnabled,
        boolean spawnWorkerExecutionEnabled,
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
        long chunkTicksOnWorkers,
        long skippedChunkTicks,
        long fallbackChunkTicks,
        long boundaryChunkTicks,
        long unloadedNeighborhoodChunkTicks,
        long truncatedChunkTicks,
        long lateChunkTicks,
        long unleasedChunkTicks,
        long chunkTickLightningStrikes,
        BlockUpdateCounters deferredBlockUpdates,
        TickFailureCounters tickFailures,
        long deferredUpdateBudgetChunkTicks,
        long nonPartitionedChunkTicks,
        long spawnChunksOnWorkers,
        long gateRefusedSpawnChunks,
        long unloadedNeighborhoodSpawnChunks,
        long deferredUpdateBudgetSpawnChunks,
        long lateSpawnChunks,
        long unleasedSpawnChunks,
        long truncatedSpawnChunks,
        long skippedSpawnChunks,
        long capRefusedSpawns,
        long refusedSpawnAdditions,
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
        boolean chunkTickWorkerDispatchEffective,
        boolean spawnWorkerDispatchEffective,
        int pendingEntityTransferCount,
        int rememberedEntityTransferResultCount,
        long rejectedEntityTransferRecordCount,
        long legacyDeferredEntityMoveCount,
        long retriedDeferredEntityMoveCount,
        long deferredEntitySectionUpdatesApplied,
        long droppedDeferredEntitySectionUpdates,
        long droppedDeferredEntitySectionUpdatesForRemovedEntity,
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
        long entityTickRegionsOnWorkers,
        long fallbackEntityTickRegions,
        long entityTickRegionsWithoutEligibleChunk,
        long entityTicksOutsideRegion,
        long serverThreadOnlyEntityTicks,
        Map<String, Long> serverThreadOnlyEntityTicksByReason,
        long unindexedEntityTickStarts,
        long entityTickIndexFallbacks,
        long deferredPortalTransitions,
        long appliedPortalTransitions,
        long refusedPortalTransitions,
        long globalPhases,
        long regionPhases,
        long regionPhasesWithoutGlobalPhase,
        long abandonedGlobalSegments,
        long mailboxAcceptedMessages,
        long mailboxDeliveredMessages,
        long mailboxRefusedFullMessages,
        long mailboxRefusedClosedMessages,
        long mailboxDroppedRetiredMessages,
        long mailboxDroppedOnCloseMessages,
        long mailboxDeliveryFailures,
        BorderUpdateCounters borderUpdates,
        BlockEventPhaseCounters blockEventPhase,
        PoiCounters poiCounters,
        WorkerDispatchCounters workerDispatch,
        RegionMetricsSummary regionMetrics,
        List<RegionTickSnapshot> regionTicks) {
    /**
     * The counters of the worker dispatch path and of the packed region leases.
     * <p>
     * {@code submissions} counts the tasks the pool accepted, {@code submitNanos} the wall time the submitting
     * threads spent handing them to the pool, and {@code wakeNanos} the wall time between a hand-off and the start
     * of the task body for the tasks a submitter handed to an idle worker - the wake-up the pool controls.
     * {@code idleWakeUps} counts those tasks; {@code wakeNanos / idleWakeUps} is the wake-up latency and a window
     * without an idle hand-off reports zero for it. {@code spinWakeUps} and {@code parkWakeUps} split the
     * wake-ups into the ones a spinning worker took and the ones a parked worker took, so their ratio is the
     * spin-versus-park behaviour of the pool. {@code packedTasks} counts the worker tasks that hold several regions and
     * {@code packedRegions} the regions they hold, so {@code packedRegions / packedTasks} is the regions per
     * packed task. {@code partialPackFallbacks} counts the packs that could not lease every region they needed,
     * released the ones they took and leased the affected regions one by one instead: a fallback is never a
     * dropped region. {@code persistentWorkers} and {@code packedRegionLeases} report which of the two
     * light-load changes is installed.
     */
    public record WorkerDispatchCounters(
            long submissions,
            long submitNanos,
            long wakeNanos,
            long idleWakeUps,
            long spinWakeUps,
            long parkWakeUps,
            long packedTasks,
            long packedRegions,
            long partialPackFallbacks,
            boolean persistentWorkers,
            boolean packedRegionLeases) {}

    /**
     * The counters of the region-partitioned POI storage.
     * <p>
     * {@code localQueries} counts the chunk reads a worker answered from the live sections of its own region,
     * {@code publishedQueries} the chunk and position reads it answered from another region's published
     * snapshot - those are the answers that can be one publish old - and {@code fallbackQueries} the reads it
     * could not answer at all because the column was not loaded, which ran on the server thread instead.
     * {@code staleTakes} counts a take whose candidate the owning region no longer holds, refused instead of
     * booking a ticket on a removed POI; {@code deferredWrites} counts a worker write of a foreign region that
     * the server thread applied instead. {@code publishes} counts the published snapshots, {@code loadedColumns}
     * and {@code savedColumns} the columns the regionized storage read from and wrote to disk, and
     * {@code villageDistanceQueries} the village distance answers that were not served from the per-section
     * memo. The counters are process-wide, so a server with several regionized levels reports their sum.
     */
    public record PoiCounters(
            long localQueries,
            long publishedQueries,
            long fallbackQueries,
            long staleTakes,
            long deferredWrites,
            long publishes,
            long loadedColumns,
            long savedColumns,
            long villageDistanceQueries) {}

    /**
     * The counters of the cross-region border update fixpoint.
     * <p>
     * {@code recorded} counts the neighbor, shape, comparator and block-entity updates a region worker captured
     * because they targeted another region. {@code applied} counts the records a propagation pass applied on the
     * target region's worker, {@code delayed} the records the bound pushed to the target region's next tick
     * through the mailbox, and {@code dropped} the records no live target region (or no open mailbox) could take.
     * {@code passes} counts the full fixpoint passes the server thread ran, {@code regionsRerun} the
     * propagation-only groups it submitted, and {@code worstPasses} the largest number of passes one worker
     * phase needed. {@code overflowed} counts the records the capture queue's own bound refused; they are handed
     * to the same next-tick fallback. Every record reaches exactly one of applied, delayed or dropped, so a
     * growing {@code delayed} count is the only observable sign that a circuit needed more than one tick.
     * <p>
     * The foreign read counters cover the version check of a cross-region read. {@code foreignReads} counts the
     * chunks a region read from another region, one per chunk and region and pass. {@code readOfUnownedChunk}
     * counts reads of versioned chunks with no owning region; {@code readOfVersionlessChunk} counts refused
     * native-worker reads of chunks without a seqlock, such as proto chunks. {@code invalidatedPasses} counts the fixpoint checks in which a region
     * observed a changed version of a chunk it read, {@code regionsRerunByReads} the region propagation groups
     * re-run because of one, and {@code rerunSeedsDropped} the written positions the seed bound refused, which a
     * re-run of that region does not re-evaluate. {@code worstPasses} bounds the re-runs too, because a re-run
     * needs a pass of its own.
     * <p>
     * The block write counters report the deferred cross-region writes of a region worker. {@code
     * blockWritesRecorded} counts the {@code Level.setBlock} calls a worker left to the region that owns the
     * written position, {@code blockWritesApplied} the calls a propagation pass ran on that region's own worker
     * with the vanilla body, and {@code blockWritesDropped} the writes whose target chunk was no longer loaded
     * when the fixpoint grouped them. {@code blockWritesOverflowed} counts the writes the capture bound refused
     * at the call site: the caller's write was not applied and was counted by the no-replay refusal instead,
     * unlike every other kind of border update, which falls back to the next tick. {@code readAfterBlockWrite}
     * counts the reads of a position the same region wrote outside its lease during the same lease: the write is
     * applied by the target region at the barrier, so such a read observes the state before the write.
     */
    public record BorderUpdateCounters(long recorded, long applied, long delayed, long dropped, long passes,
            long regionsRerun, long worstPasses, long overflowed, long foreignReads, long readOfUnownedChunk, long readOfVersionlessChunk,
            long invalidatedPasses, long regionsRerunByReads, long rerunSeedsDropped, long blockWritesRecorded,
            long blockWritesApplied, long blockWritesOverflowed, long blockWritesDropped, long readAfterBlockWrite) {
        @Override
        public String toString() {
            return "recorded=" + this.recorded
                    + "/applied=" + this.applied
                    + "/delayed=" + this.delayed
                    + "/dropped=" + this.dropped
                    + "/passes=" + this.passes
                    + "/regionsRerun=" + this.regionsRerun
                    + "/worstPasses=" + this.worstPasses
                    + "/overflowed=" + this.overflowed
                    + "/foreignReads=" + this.foreignReads
                    + "/readOfUnownedChunk=" + this.readOfUnownedChunk
                    + "/readOfVersionlessChunk=" + this.readOfVersionlessChunk
                    + "/invalidatedPasses=" + this.invalidatedPasses
                    + "/regionsRerunByReads=" + this.regionsRerunByReads
                    + "/rerunSeedsDropped=" + this.rerunSeedsDropped
                    + "/blockWritesRecorded=" + this.blockWritesRecorded
                    + "/blockWritesApplied=" + this.blockWritesApplied
                    + "/blockWritesOverflowed=" + this.blockWritesOverflowed
                    + "/blockWritesDropped=" + this.blockWritesDropped
                    + "/readAfterBlockWrite=" + this.readAfterBlockWrite;
        }
    }

    /**
     * The counters of the block event phase of a level.
     * <p>
     * The phase drains the level's ordered block event set on the server thread after every region batch of the
     * tick has returned. It therefore runs as a quiescent scope: the lease of each region its events run in is
     * taken once and released when the phase ends, and the scope captures no border update, so a phase needs no
     * border fixpoint at all.
     * <p>
     * {@code phases} counts the phases that ran as a scope, {@code leases} the region leases they took and
     * {@code releasedLeases} the leases they gave back: a phase that completed, threw or was cut short by a
     * shutdown releases every lease it took, so the two counters differ only while a phase is open.
     * {@code events} counts the events the scopes ran and {@code leaseFallbacks} the events that ran without a
     * region context because its region could not be leased. {@code fixpoints} counts the border fixpoints a
     * phase ran: a quiescent phase captures nothing, so it has nothing to flush and the counter stays zero.
     * {@code nanos} is the wall time of the phases the scopes covered and {@code maxNanos} the slowest of them.
     *
     * @param phases         the block event phases that ran as a quiescent scope
     * @param leases         the region leases those phases took, one per region and phase
     * @param releasedLeases the region leases those phases released
     * @param events         the events those phases ran
     * @param fixpoints      the border fixpoints a phase ran, zero for every quiescent phase
     * @param leaseFallbacks the events of a phase that ran without a region lease
     * @param nanos          the wall time of the phases that ran as a scope
     * @param maxNanos       the wall time of the slowest phase that ran as a scope
     */
    public record BlockEventPhaseCounters(long phases, long leases, long releasedLeases, long events, long fixpoints,
            long leaseFallbacks, long nanos, long maxNanos) {
        @Override
        public String toString() {
            return "phases=" + this.phases
                    + "/leases=" + this.leases
                    + "/releasedLeases=" + this.releasedLeases
                    + "/events=" + this.events
                    + "/fixpoints=" + this.fixpoints
                    + "/leaseFallbacks=" + this.leaseFallbacks
                    + "/nanos=" + this.nanos
                    + "/maxNanos=" + this.maxNanos;
        }
    }

    /**
     * The light rechecks and block update publications the batches of the level deferred, counted per slice.
     * <p>
     * An applied record is one vanilla effect the barrier ran on the server thread: the light section status
     * change, the light recheck or the block update publication of one block write. A dropped record never
     * completed - the level identity closed before the barrier applied it - and no record is ever silently
     * skipped. A refused unit is one chunk, ticker, entity or scheduled tick that the batch could not reserve
     * and that therefore ran on the server thread before its body started, where its writes need no record. The
     * three counts are indexed by {@link RegionTickSlice#ordinal()}.
     *
     * @param applied the records each slice applied
     * @param dropped the records each slice dropped
     * @param refused the units each slice refused before their body
     */
    public record BlockUpdateCounters(long[] applied, long[] dropped, long[] refused) {
        /** {@return the records the barrier of {@code slice} applied} */
        public long applied(RegionTickSlice slice) {
            return this.applied[slice.ordinal()];
        }

        /** {@return the records of {@code slice} that were never applied because their level closed} */
        public long dropped(RegionTickSlice slice) {
            return this.dropped[slice.ordinal()];
        }

        /** {@return the units of {@code slice} that were refused before their body} */
        public long refused(RegionTickSlice slice) {
            return this.refused[slice.ordinal()];
        }

        @Override
        public String toString() {
            final StringBuilder summary = new StringBuilder();
            for (final RegionTickSlice slice : RegionTickSlice.values()) {
                if (!summary.isEmpty()) {
                    summary.append(", ");
                }
                summary.append(slice.name().toLowerCase(java.util.Locale.ROOT))
                        .append('=').append(this.applied(slice))
                        .append('/').append(this.dropped(slice))
                        .append('/').append(this.refused(slice));
            }
            return summary.toString();
        }
    }

    /**
     * The tick failures the worker groups of each slice recorded, counted per slice.
     * <p>
     * A counted failure is one tick a region worker caught and stopped its group at. It is counted on the worker
     * and reported by the server thread after the barrier, so the count is observable even though the report
     * itself is built by another thread. A failure is counted once, and it is counted whether or not the batch
     * kept it in its bounded record, so a tick that fails in many regions at once is counted in full. The counts
     * are indexed by {@link RegionTickSlice#ordinal()}.
     *
     * @param recorded the tick failures each slice recorded
     */
    public record TickFailureCounters(long[] recorded) {
        /** {@return the tick failures the workers of {@code slice} recorded} */
        public long recorded(RegionTickSlice slice) {
            return this.recorded[slice.ordinal()];
        }

        @Override
        public String toString() {
            final StringBuilder summary = new StringBuilder();
            for (final RegionTickSlice slice : RegionTickSlice.values()) {
                if (!summary.isEmpty()) {
                    summary.append(", ");
                }
                summary.append(slice.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(this.recorded(slice));
            }
            return summary.toString();
        }
    }
}
