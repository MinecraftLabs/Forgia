/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatibility;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionMetricsReport;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

final class RegionThreadingCommand {
    /** Regions the per-region listing shows when no count is given. */
    static final int DEFAULT_LISTED_REGIONS = 10;
    /** Upper bound of the listing, so one command cannot dump thousands of regions into the chat. */
    static final int MAX_LISTED_REGIONS = 100;

    private RegionThreadingCommand() {}

    static ArgumentBuilder<CommandSourceStack, ?> register() {
        return Commands.literal("regionthreading")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    final CommandSourceStack source = context.getSource();
                    RegionThreadingHooks.diagnostics().ifPresentOrElse(
                            diagnostics -> sendDiagnostics(source, diagnostics),
                            () -> sendNotInstalled(source));
                    return 1;
                }))
                .then(Commands.literal("regions")
                        .executes(context -> sendRegionListing(context.getSource(), DEFAULT_LISTED_REGIONS))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_LISTED_REGIONS))
                                .executes(context -> sendRegionListing(context.getSource(), IntegerArgumentType.getInteger(context, "count")))));
    }

    /**
     * Sends the per-region listing of the {@code count} slowest regions by p99.
     */
    private static int sendRegionListing(CommandSourceStack source, int count) {
        RegionThreadingHooks.diagnostics().ifPresentOrElse(
                diagnostics -> RegionMetricsReport.listingLines(diagnostics.regionTicks(), count)
                        .forEach(line -> source.sendSuccess(() -> Component.literal(line), false)),
                () -> source.sendSuccess(() -> Component.literal(RegionMetricsReport.notInstalledLine()), false));
        return 1;
    }

    private static void sendDiagnostics(CommandSourceStack source, RegionThreadingDiagnostics diagnostics) {
        source.sendSuccess(() -> Component.literal("Region threading diagnostics: enabled=" + NeoForgeConfig.SERVER.enableRegionThreadingBridge.get()
                + ", regions=" + diagnostics.regionCount()
                + ", runningRegions=" + diagnostics.runningRegionCount()
                + ", submittedRegions=" + diagnostics.submittedRegionCount()
                + ", sections=" + diagnostics.sectionCount()
                + ", loadedChunks=" + diagnostics.loadedChunkCount()
                + ", pendingRecalculations=" + diagnostics.pendingRecalculationCount()
                + ", structuralChanges=" + diagnostics.structuralChangeCount()
                + ", splitChecks=" + diagnostics.splitCheckRequestCount()
                + ", mergeChecks=" + diagnostics.mergeCheckRequestCount()
                + ", trackedEntities=" + diagnostics.trackedEntityCount()
                + ", entityTasks=" + diagnostics.trackedEntityTaskCount()
                + ", workerThreads=" + diagnostics.workerThreadCount()
                + ", workerExecution=" + diagnostics.workerExecutionEnabled()
                + ", globalWorkerExecution=" + diagnostics.globalWorkerExecutionEnabled()
                + ", entityTickWorkerExecution=" + diagnostics.entityTickWorkerExecutionEnabled()
                + ", blockEntityTickWorkerExecution=" + diagnostics.blockEntityTickWorkerExecutionEnabled()
                + ", scheduledTickWorkerExecutionRequested=" + diagnostics.scheduledTickWorkerExecutionEnabled()
                + ", scheduledTickWorkerDispatchEffective=" + diagnostics.scheduledTickWorkerDispatchEffective()
                + ", chunkTickWorkerExecutionRequested=" + diagnostics.chunkTickWorkerExecutionEnabled()
                + ", chunkTickWorkerDispatchEffective=" + diagnostics.chunkTickWorkerDispatchEffective()
                + ", spawnWorkerExecutionRequested=" + diagnostics.spawnWorkerExecutionEnabled()
                + ", spawnWorkerDispatchEffective=" + diagnostics.spawnWorkerDispatchEffective()
                + ", borderUpdateEnabled=" + NeoForgeConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                + ", borderUpdates=" + diagnostics.borderUpdates()
                + ", gameEvents=" + diagnostics.gameEvents()
                + ", entityTicksOnWorkers=" + diagnostics.entityTicksOnWorkers()
                + ", skippedEntityTicks=" + diagnostics.skippedEntityTicks()
                + ", fallbackEntityTicks=" + diagnostics.fallbackEntityTicks()
                + ", deferredEntityTicks=" + diagnostics.deferredEntityTicks()
                + ", entityMutationsAllowedByOwnership=" + diagnostics.entityMutationsAllowedByOwnership()
                + ", entityMutationsRejectedByOwnership=" + diagnostics.entityMutationsRejectedByOwnership()
                + ", entityQueriesAllowedByLease=" + diagnostics.entityQueriesAllowedByLease()
                + ", entityQueriesTruncatedByLease=" + diagnostics.entityQueriesTruncatedByLease()
                + ", entityQueriesRejected=" + diagnostics.entityQueriesRejected()
                + ", deferredEntitySectionUpdatesApplied=" + diagnostics.deferredEntitySectionUpdatesApplied()
                + ", droppedDeferredEntitySectionUpdates=" + diagnostics.droppedDeferredEntitySectionUpdates()
                + ", droppedDeferredEntitySectionUpdatesForRemovedEntity=" + diagnostics.droppedDeferredEntitySectionUpdatesForRemovedEntity()
                + ", deferredEntityRemovalsApplied=" + diagnostics.deferredEntityRemovalsApplied()
                + ", droppedDeferredEntityRemovals=" + diagnostics.droppedDeferredEntityRemovals()
                + ", repeatedDeferredEntityRemovals=" + diagnostics.repeatedDeferredEntityRemovals()
                + ", deferredEntityAdditionsRecorded=" + diagnostics.deferredEntityAdditionsRecorded()
                + ", deferredEntityAdditionsApplied=" + diagnostics.deferredEntityAdditionsApplied()
                + ", droppedDeferredEntityAdditions=" + diagnostics.droppedDeferredEntityAdditions()
                + ", entityAddRejectedOnWorker=" + diagnostics.entityAddRejectedOnWorker()
                + ", chunkGuardRefusals=" + diagnostics.chunkGuardRefusals()
                + ", chunkFallbackEntityTicks=" + diagnostics.chunkFallbackEntityTicks()
                + ", chunkPreloadRefusedEntityTicks=" + diagnostics.chunkPreloadRefusedEntityTicks()
                + ", entityTickChunkMargin=" + RegionThreadingEngine.entityTickChunkMargin()
                + ", blockEntityTicksOnWorkers=" + diagnostics.blockEntityTicksOnWorkers()
                + ", skippedBlockEntityTicks=" + diagnostics.skippedBlockEntityTicks()
                + ", fallbackBlockEntityTicks=" + diagnostics.fallbackBlockEntityTicks()
                + ", nonTickingBlockEntityChunks=" + diagnostics.nonTickingBlockEntityChunks()
                + ", entitiesLoadingBlockEntityChunks=" + diagnostics.entitiesLoadingBlockEntityChunks()
                + ", missingBlockEntityChunks=" + diagnostics.missingBlockEntityChunks()
                + ", staleBlockEntityChunkSnapshots=" + diagnostics.staleBlockEntityChunkSnapshots()
                + ", scheduledTicksOnWorkers=" + diagnostics.scheduledTicksOnWorkers()
                + ", skippedScheduledTicks=" + diagnostics.skippedScheduledTicks()
                + ", fallbackScheduledTicks=" + diagnostics.fallbackScheduledTicks()
                + ", chunkTicksOnWorkers=" + diagnostics.chunkTicksOnWorkers()
                + ", skippedChunkTicks=" + diagnostics.skippedChunkTicks()
                + ", fallbackChunkTicks=" + diagnostics.fallbackChunkTicks()
                + ", boundaryChunkTicks=" + diagnostics.boundaryChunkTicks()
                + ", unloadedNeighborhoodChunkTicks=" + diagnostics.unloadedNeighborhoodChunkTicks()
                + ", truncatedChunkTicks=" + diagnostics.truncatedChunkTicks()
                + ", lateChunkTicks=" + diagnostics.lateChunkTicks()
                + ", unleasedChunkTicks=" + diagnostics.unleasedChunkTicks()
                + ", chunkTickLightningStrikes=" + diagnostics.chunkTickLightningStrikes()
                + ", deferredBlockUpdates=" + diagnostics.deferredBlockUpdates()
                + ", tickFailures=" + diagnostics.tickFailures()
                + ", deferredUpdateBudgetChunkTicks=" + diagnostics.deferredUpdateBudgetChunkTicks()
                + ", nonPartitionedChunkTicks=" + diagnostics.nonPartitionedChunkTicks()
                + ", spawnChunksOnWorkers=" + diagnostics.spawnChunksOnWorkers()
                + ", gateRefusedSpawnChunks=" + diagnostics.gateRefusedSpawnChunks()
                + ", unloadedNeighborhoodSpawnChunks=" + diagnostics.unloadedNeighborhoodSpawnChunks()
                + ", deferredUpdateBudgetSpawnChunks=" + diagnostics.deferredUpdateBudgetSpawnChunks()
                + ", lateSpawnChunks=" + diagnostics.lateSpawnChunks()
                + ", unleasedSpawnChunks=" + diagnostics.unleasedSpawnChunks()
                + ", truncatedSpawnChunks=" + diagnostics.truncatedSpawnChunks()
                + ", skippedSpawnChunks=" + diagnostics.skippedSpawnChunks()
                + ", capRefusedSpawns=" + diagnostics.capRefusedSpawns()
                + ", refusedSpawnAdditions=" + diagnostics.refusedSpawnAdditions()
                + ", globalRunning=" + diagnostics.globalRegionRunning()
                + ", globalSubmitted=" + diagnostics.globalRegionSubmitted()
                + ", rejectedWorkerTasks=" + diagnostics.rejectedWorkerTaskCount()
                + ", failedQueuedTasks=" + diagnostics.failedQueuedTaskCount()
                + ", ownershipViolations=" + NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                + ", ownershipViolationCount=" + diagnostics.ownershipViolationCount()
                + ", defaultModSupport=" + RegionThreadingCompatibility.defaultSupport()
                + ", unsupportedMods=" + RegionThreadingCompatibility.unsupportedMods().size()
                + ", requiredMods=" + RegionThreadingCompatibility.requiredMods().size()
                + ", globalTicks=" + diagnostics.globalTickCount()
                + ", globalLastTickNs=" + diagnostics.globalLastTickDurationNanos()
                + ", regionTicks=" + diagnostics.totalRegionTickCount()
                + ", maxRegionLastTickNs=" + diagnostics.maxRegionLastTickDurationNanos()
                + ", cappedDrains=" + diagnostics.cappedDrainCount()
                + ", pendingEntityTransfers=" + diagnostics.pendingEntityTransferCount()
                + ", rememberedEntityTransferResults=" + diagnostics.rememberedEntityTransferResultCount()
                + ", rejectedEntityTransferRecords=" + diagnostics.rejectedEntityTransferRecordCount()
                + ", entityTickRegionsOnWorkers=" + diagnostics.entityTickRegionsOnWorkers()
                + ", fallbackEntityTickRegions=" + diagnostics.fallbackEntityTickRegions()
                + ", entityTickRegionsWithoutEligibleChunk=" + diagnostics.entityTickRegionsWithoutEligibleChunk()
                + ", entityTicksOutsideRegion=" + diagnostics.entityTicksOutsideRegion()
                + ", serverThreadOnlyEntityTicks=" + diagnostics.serverThreadOnlyEntityTicks()
                + ", serverThreadOnlyEntityTicksByReason=" + diagnostics.serverThreadOnlyEntityTicksByReason()
                + ", deferredPortalTransitions=" + diagnostics.deferredPortalTransitions()
                + ", appliedPortalTransitions=" + diagnostics.appliedPortalTransitions()
                + ", refusedPortalTransitions=" + diagnostics.refusedPortalTransitions()
                + ", unindexedEntityTickStarts=" + diagnostics.unindexedEntityTickStarts()
                + ", entityTickIndexFallbacks=" + diagnostics.entityTickIndexFallbacks()
                + ", regionPolicy=" + diagnostics.regionPolicy()
                + ", regionChunkSize=" + diagnostics.regionChunkSize()
                + ", entityStorageMode=" + diagnostics.entityStorageMode()), false);
        // Added, never merged into the line above: the region metrics are a second, independent line so the
        // existing diagnostics line stays stable for scripts that match it.
        source.sendSuccess(() -> Component.literal(RegionMetricsReport.summaryLine(diagnostics.regionMetrics())), false);
        // A third independent line for the regionized POI storage, so the stable diagnostics line and the
        // region metrics line keep their shape for scripts that match them.
        final RegionThreadingDiagnostics.PoiCounters poi = diagnostics.poiCounters();
        source.sendSuccess(() -> Component.literal("Region POI: localQueries=" + poi.localQueries()
                + ", publishedQueries=" + poi.publishedQueries()
                + ", fallbackQueries=" + poi.fallbackQueries()
                + ", staleTakes=" + poi.staleTakes()
                + ", deferredWrites=" + poi.deferredWrites()
                + ", publishes=" + poi.publishes()
                + ", loadedColumns=" + poi.loadedColumns()
                + ", savedColumns=" + poi.savedColumns()
                + ", villageDistanceQueries=" + poi.villageDistanceQueries()), false);
        // A fourth independent line for the worker dispatch path and the packed region leases, so the stable
        // diagnostics line and the two lines above keep their shape for scripts that match them.
        final RegionThreadingDiagnostics.WorkerDispatchCounters dispatch = diagnostics.workerDispatch();
        source.sendSuccess(() -> Component.literal("Region worker dispatch: persistentWorkers=" + dispatch.persistentWorkers()
                + ", packedRegionLeases=" + dispatch.packedRegionLeases()
                + ", taskSubmissions=" + dispatch.submissions()
                + ", submissionNanos=" + dispatch.submitNanos()
                + ", wakeUpNanos=" + dispatch.wakeNanos()
                + ", spinWakeUps=" + dispatch.spinWakeUps()
                + ", parkWakeUps=" + dispatch.parkWakeUps()
                + ", packedTasks=" + dispatch.packedTasks()
                + ", packedRegions=" + dispatch.packedRegions()
                + ", regionsPerPackedTask=" + regionsPerPackedTask(dispatch)
                + ", partialPackFallbacks=" + dispatch.partialPackFallbacks()), false);
        // A fifth independent line for the player region transitions, so the four lines above keep their shape
        // for scripts that match them.
        source.sendSuccess(() -> Component.literal("Region players: " + diagnostics.playerRegions()), false);
    }

    /** {@return the average number of regions a packed task held, formatted for the diagnostics line} */
    private static String regionsPerPackedTask(RegionThreadingDiagnostics.WorkerDispatchCounters dispatch) {
        if (dispatch.packedTasks() == 0L) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", dispatch.packedRegions() / (double) dispatch.packedTasks());
    }

    private static void sendNotInstalled(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Region threading bridge is not installed: enabled="
                + NeoForgeConfig.SERVER.enableRegionThreadingBridge.get()
                + ", workerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingWorkers.get()
                + ", globalWorkerExecution=" + NeoForgeConfig.SERVER.enableGlobalRegionThreadingWorker.get()
                + ", entityTickWorkerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingEntityTicks.get()
                + ", blockEntityTickWorkerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingBlockEntityTicks.get()
                + ", scheduledTickWorkerExecutionRequested=" + NeoForgeConfig.SERVER.enableRegionThreadingScheduledTicks.get()
                + ", chunkTickWorkerExecutionRequested=" + NeoForgeConfig.SERVER.enableRegionThreadingRandomTicks.get()
                + ", spawnWorkerExecutionRequested=" + NeoForgeConfig.SERVER.enableRegionThreadingSpawning.get()
                + ", borderUpdateEnabled=" + NeoForgeConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                + ", ownershipViolations=" + NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                + ", defaultModSupport=" + RegionThreadingCompatibility.defaultSupport()
                + ", unsupportedMods=" + RegionThreadingCompatibility.unsupportedMods().size()
                + ", requiredMods=" + RegionThreadingCompatibility.requiredMods().size()
                + ", configuredWorkerThreads=" + NeoForgeConfig.SERVER.regionThreadingWorkerThreads.get()
                + ", configuredRegionPolicy=" + NeoForgeConfig.SERVER.regionThreadingMode.get()
                + ", configuredRegionChunkSize=" + NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.get()), false);
        source.sendSuccess(() -> Component.literal(RegionMetricsReport.notInstalledLine()), false);
    }
}
