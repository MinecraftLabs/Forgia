/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.mojang.brigadier.builder.ArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatibility;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;

final class RegionThreadingCommand {
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
                }));
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
                + ", randomTickWorkerExecutionRequested=" + diagnostics.randomTickWorkerExecutionEnabled()
                + ", randomTickWorkerDispatchEffective=" + diagnostics.randomTickWorkerDispatchEffective()
                + ", entityTicksOnWorkers=" + diagnostics.entityTicksOnWorkers()
                + ", skippedEntityTicks=" + diagnostics.skippedEntityTicks()
                + ", fallbackEntityTicks=" + diagnostics.fallbackEntityTicks()
                + ", deferredEntityTicks=" + diagnostics.deferredEntityTicks()
                + ", entityMutationsAllowedByOwnership=" + diagnostics.entityMutationsAllowedByOwnership()
                + ", entityMutationsRejectedByOwnership=" + diagnostics.entityMutationsRejectedByOwnership()
                + ", deferredEntitySectionUpdatesApplied=" + diagnostics.deferredEntitySectionUpdatesApplied()
                + ", droppedDeferredEntitySectionUpdates=" + diagnostics.droppedDeferredEntitySectionUpdates()
                + ", blockEntityTicksOnWorkers=" + diagnostics.blockEntityTicksOnWorkers()
                + ", skippedBlockEntityTicks=" + diagnostics.skippedBlockEntityTicks()
                + ", fallbackBlockEntityTicks=" + diagnostics.fallbackBlockEntityTicks()
                + ", scheduledTicksOnWorkers=" + diagnostics.scheduledTicksOnWorkers()
                + ", skippedScheduledTicks=" + diagnostics.skippedScheduledTicks()
                + ", fallbackScheduledTicks=" + diagnostics.fallbackScheduledTicks()
                + ", randomTicksOnWorkers=" + diagnostics.randomTicksOnWorkers()
                + ", skippedRandomTicks=" + diagnostics.skippedRandomTicks()
                + ", fallbackRandomTicks=" + diagnostics.fallbackRandomTicks()
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
                + ", regionPolicy=" + diagnostics.regionPolicy()
                + ", regionChunkSize=" + diagnostics.regionChunkSize()
                + ", entityStorageMode=" + diagnostics.entityStorageMode()), false);
    }

    private static void sendNotInstalled(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Region threading bridge is not installed: enabled="
                + NeoForgeConfig.SERVER.enableRegionThreadingBridge.get()
                + ", workerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingWorkers.get()
                + ", globalWorkerExecution=" + NeoForgeConfig.SERVER.enableGlobalRegionThreadingWorker.get()
                + ", entityTickWorkerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingEntityTicks.get()
                + ", blockEntityTickWorkerExecution=" + NeoForgeConfig.SERVER.enableRegionThreadingBlockEntityTicks.get()
                + ", scheduledTickWorkerExecutionRequested=" + NeoForgeConfig.SERVER.enableRegionThreadingScheduledTicks.get()
                + ", randomTickWorkerExecutionRequested=" + NeoForgeConfig.SERVER.enableRegionThreadingRandomTicks.get()
                + ", ownershipViolations=" + NeoForgeConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                + ", defaultModSupport=" + RegionThreadingCompatibility.defaultSupport()
                + ", unsupportedMods=" + RegionThreadingCompatibility.unsupportedMods().size()
                + ", requiredMods=" + RegionThreadingCompatibility.requiredMods().size()
                + ", configuredWorkerThreads=" + NeoForgeConfig.SERVER.regionThreadingWorkerThreads.get()
                + ", configuredRegionPolicy=" + NeoForgeConfig.SERVER.regionThreadingMode.get()
                + ", configuredRegionChunkSize=" + NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.get()), false);
    }
}
