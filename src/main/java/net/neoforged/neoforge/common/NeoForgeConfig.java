/*
 * Copyright (c) neoforge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.Logging;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;
import net.neoforged.neoforge.server.threading.RegionThreadingSupport;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;

/**
 * NeoForge's own configuration.
 */
public class NeoForgeConfig {
    public static class Server {
        public final BooleanValue removeErroringBlockEntities;

        public final BooleanValue removeErroringEntities;

        public final BooleanValue fullBoundingBoxLadders;

        public final ConfigValue<String> permissionHandler;

        public final BooleanValue advertiseDedicatedServerToLan;

        public final BooleanValue enableRegionThreadingBridge;

        public final BooleanValue enableRegionThreadingWorkers;

        public final BooleanValue enableGlobalRegionThreadingWorker;

        public final BooleanValue enableRegionThreadingEntityTicks;

        public final BooleanValue enableRegionThreadingBlockEntityTicks;

        public final BooleanValue enableRegionThreadingScheduledTicks;

        public final BooleanValue enableRegionThreadingRandomTicks;

        public final BooleanValue enableRegionThreadingSpawning;

        public final BooleanValue enableRegionThreadingPoi;

        public final IntValue regionThreadingWorkerThreads;

        public final ModConfigSpec.EnumValue<RegionPolicy> regionThreadingMode;

        public final IntValue regionThreadingFixedRegionChunks;

        public final ModConfigSpec.EnumValue<RegionThreadingOwnershipViolationMode> regionThreadingOwnershipViolationMode;

        public final ModConfigSpec.EnumValue<RegionThreadingSupport> defaultRegionThreadingSupport;

        public final LongValue regionThreadingLongTickWarningNanos;

        public final IntValue regionThreadingMaxImmediateTasksPerDrain;

        public final IntValue regionThreadingMaxScheduledTasksPerDrain;

        Server(ModConfigSpec.Builder builder) {
            removeErroringBlockEntities = builder
                    .comment("Set this to true to remove any BlockEntity that throws an error in its update method instead of closing the server and reporting a crash log. BE WARNED THIS COULD SCREW UP EVERYTHING USE SPARINGLY WE ARE NOT RESPONSIBLE FOR DAMAGES.")
                    .translation("neoforge.configgui.removeErroringBlockEntities")
                    .worldRestart()
                    .define("removeErroringBlockEntities", false);

            removeErroringEntities = builder
                    .comment("Set this to true to remove any Entity (Note: Does not include BlockEntities) that throws an error in its tick method instead of closing the server and reporting a crash log. BE WARNED THIS COULD SCREW UP EVERYTHING USE SPARINGLY WE ARE NOT RESPONSIBLE FOR DAMAGES.")
                    .translation("neoforge.configgui.removeErroringEntities")
                    .worldRestart()
                    .define("removeErroringEntities", false);

            fullBoundingBoxLadders = builder
                    .comment("Set this to true to check the entire entity's collision bounding box for ladders instead of just the block they are in. Causes noticeable differences in mechanics so default is vanilla behavior. Default: false.")
                    .translation("neoforge.configgui.fullBoundingBoxLadders")
                    .worldRestart()
                    .define("fullBoundingBoxLadders", false);

            permissionHandler = builder
                    .comment("The permission handler used by the server. Defaults to neoforge:default_handler if no such handler with that name is registered.")
                    .translation("neoforge.configgui.permissionHandler")
                    .define("permissionHandler", "neoforge:default_handler");

            advertiseDedicatedServerToLan = builder
                    .comment("Set this to true to enable advertising the dedicated server to local LAN clients so that it shows up in the Multiplayer screen automatically.")
                    .translation("neoforge.configgui.advertiseDedicatedServerToLan")
                    .define("advertiseDedicatedServerToLan", true);

            enableRegionThreadingBridge = builder
                    .comment("EXPERIMENTAL: Enables the temporary single-thread region threading bridge. This exposes the region scheduler API before the full Folia-style tick scheduler is ported.")
                    .translation("neoforge.configgui.enableRegionThreadingBridge")
                    .worldRestart()
                    .define("enableRegionThreadingBridge", false);

            enableRegionThreadingWorkers = builder
                    .comment("EXPERIMENTAL: Executes due region queue drains on native region worker threads instead of the server thread bridge. Requires enableRegionThreadingBridge. Unsafe until ownership guards are complete.")
                    .translation("neoforge.configgui.enableRegionThreadingWorkers")
                    .worldRestart()
                    .define("enableRegionThreadingWorkers", false);

            enableGlobalRegionThreadingWorker = builder
                    .comment("EXPERIMENTAL: Executes global-region queue drains on the native worker pool instead of the server thread bridge. Requires enableRegionThreadingBridge.")
                    .translation("neoforge.configgui.enableGlobalRegionThreadingWorker")
                    .worldRestart()
                    .define("enableGlobalRegionThreadingWorker", false);

            enableRegionThreadingEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge. Entity storage is single-threaded and owned by the server thread, so entity ticks currently fall back to the server thread. This is a bounded entity tick slice, not regionized world ticking.")
                    .translation("neoforge.configgui.enableRegionThreadingEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingEntityTicks", false);

            enableRegionThreadingBlockEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel block entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge. Only block entity types whose compatibility declaration proves that their server tick never queries or mutates entity storage, never spawns/removes an entity and writes only inside its region run on workers; every other type stays on the server thread. This is a bounded block-entity tick slice, not regionized world ticking.")
                    .translation("neoforge.configgui.enableRegionThreadingBlockEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingBlockEntityTicks", false);

            enableRegionThreadingScheduledTicks = builder
                    .comment("EXPERIMENTAL: Requests the bounded scheduled block and fluid tick slice. Requires enableRegionThreadingBridge. In production the effective dispatch stays gated because no scheduled callback class is admitted as entity-storage safe, so the vanilla inline loop runs; diagnostics report the request separately from the effective dispatch.")
                    .translation("neoforge.configgui.enableRegionThreadingScheduledTicks")
                    .worldRestart()
                    .define("enableRegionThreadingScheduledTicks", false);

            enableRegionThreadingRandomTicks = builder
                    .comment("EXPERIMENTAL: Ticks the chunks of a level on their owning region worker thread while the server thread waits for the batch. This is the chunk tick body - random block and fluid ticks, ice and snow, and thunder - not only the random ticks the key is named after. Requires enableRegionThreadingBridge. Only chunks whose eight neighboring chunks belong to the same region and are loaded run on a worker; a chunk that a random tick could write out of its region, or that would have to load a chunk, stays on the server thread. Natural spawning, the inhabited time update, the custom spawners and every other global decision stay on the server thread. Every refusal is counted and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingRandomTicks")
                    .worldRestart()
                    .define("enableRegionThreadingRandomTicks", false);

            enableRegionThreadingSpawning = builder
                    .comment(
                            "EXPERIMENTAL: Runs the natural spawning of a level on the worker of the region that owns the chunk, while the server thread waits for the batch. The mob cap is the vanilla level-wide cap: the server thread counts the level's mobs per category once per tick and every region spawns against that count, so a mob that walks into the neighbouring region still holds its slot. A region spawns from a random source derived from the world seed, the dimension, the game time, the region and the chunk, and every mob a region spawns is admitted by the server thread at the barrier. Natural spawning requires enableRegionThreadingBridge and a fixed region policy (the physical entity sections of a level are partitioned per region only for a fixed layout). Only chunks whose eight neighboring chunks are loaded run on a worker; a chunk with an unloaded neighbor, a chunk whose region cannot be leased and a chunk the batch cannot admit stays on the server thread, where it spawns from the vanilla level-wide state exactly like vanilla. The spawn gate, the custom spawners and the per-chunk broadcast stay on the server thread. Every refusal is counted by reason and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingSpawning")
                    .worldRestart()
                    .define("enableRegionThreadingSpawning", false);

            enableRegionThreadingPoi = builder
                    .comment(
                            "EXPERIMENTAL: Partitions the POI storage of a level per region so that the POI users (villagers, iron golems, raiders, bees, zombies and foxes) tick on their region worker again. A POI section belongs to the region that owns its chunk; each region publishes an immutable snapshot of the sections it wrote at the end of every region phase, and a query from a worker reads its own region's live sections plus the published snapshots of the regions its box overlaps, in vanilla's result order. A foreign answer is at most one publish old, free-ticket counts are live, and the village distance is answered from the published village-center index instead of draining the level-wide distance tracker. Requires enableRegionThreadingBridge and a fixed region policy (regionThreadingMode FIXED_3X3), because the storage maps a chunk to exactly one region coordinate. Enabling enableRegionThreadingEntityTicks implies this storage, because a worker tick of a POI user reaches the level's POI manager. With the switch off, and without entity worker ticks, the vanilla PoiManager storage is used unchanged.")
                    .translation("neoforge.configgui.enableRegionThreadingPoi")
                    .worldRestart()
                    .define("enableRegionThreadingPoi", false);

            regionThreadingWorkerThreads = builder
                    .comment("EXPERIMENTAL: Number of native region worker threads reserved for the future region scheduler. A value of 0 uses max(1, available processors - 1).")
                    .translation("neoforge.configgui.regionThreadingWorkerThreads")
                    .worldRestart()
                    .defineInRange("regionThreadingWorkerThreads", 0, 0, 512);

            regionThreadingMode = builder
                    .comment("EXPERIMENTAL: Region coordinate policy. FIXED_3X3 uses a non-overlapping global fixed-chunk grid whose edge length is regionThreadingFixedRegionChunks (3 chunks unless configured otherwise); regions never merge, split, or change coordinates. DYNAMIC keeps the legacy 16x16-chunk grid with merge and split recalculation for compatibility.")
                    .translation("neoforge.configgui.regionThreadingMode")
                    .worldRestart()
                    .defineEnum("regionThreadingMode", RegionPolicy.FIXED_3X3);

            regionThreadingFixedRegionChunks = builder
                    .comment("EXPERIMENTAL: Edge length in chunks of one fixed region, used when regionThreadingMode is FIXED_3X3. 3 reproduces the original 3x3-chunk grid, where only the center chunk of a region has all eight neighbors in the same region. Larger regions keep more neighboring chunks inside one region at the cost of coarser parallelism and longer region leases. A value outside 3..32 is clamped into that range when the config loads, and the region layout rejects it with an IllegalArgumentException.")
                    .translation("neoforge.configgui.regionThreadingFixedRegionChunks")
                    .worldRestart()
                    .defineInRange("regionThreadingFixedRegionChunks", RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE, RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE, RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE);

            regionThreadingOwnershipViolationMode = builder
                    .comment("EXPERIMENTAL: Controls how region threading ownership assertion failures are handled.")
                    .translation("neoforge.configgui.regionThreadingOwnershipViolationMode")
                    .defineEnum("regionThreadingOwnershipViolationMode", RegionThreadingOwnershipViolationMode.WARN);

            defaultRegionThreadingSupport = builder
                    .comment("EXPERIMENTAL: Default region threading support status for mods that do not declare explicit metadata yet.")
                    .translation("neoforge.configgui.defaultRegionThreadingSupport")
                    .worldRestart()
                    .defineEnum("defaultRegionThreadingSupport", RegionThreadingSupport.UNKNOWN);

            regionThreadingLongTickWarningNanos = builder
                    .comment("EXPERIMENTAL: Logs a warning when a global or region queue drain exceeds this duration in nanoseconds. 0 disables warnings.")
                    .translation("neoforge.configgui.regionThreadingLongTickWarningNanos")
                    .defineInRange("regionThreadingLongTickWarningNanos", 0L, 0L, Long.MAX_VALUE);

            regionThreadingMaxImmediateTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum immediate region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxImmediateTasksPerDrain")
                    .defineInRange("regionThreadingMaxImmediateTasksPerDrain", 10000, 0, Integer.MAX_VALUE);

            regionThreadingMaxScheduledTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum due scheduled region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxScheduledTasksPerDrain")
                    .defineInRange("regionThreadingMaxScheduledTasksPerDrain", 10000, 0, Integer.MAX_VALUE);
        }
    }

    public enum RegionThreadingOwnershipViolationMode {
        DISABLED,
        WARN,
        THROW
    }

    /**
     * General configuration that doesn't need to be synchronized but needs to be available before server startup
     */
    public static class Common {
        public final ModConfigSpec.EnumValue<TagConventionLogWarning.LogWarningMode> logUntranslatedItemTagWarnings;

        public final ModConfigSpec.EnumValue<TagConventionLogWarning.LogWarningMode> logLegacyTagWarnings;

        public final BooleanValue attributeAdvancedTooltipDebugInfo;

        Common(ModConfigSpec.Builder builder) {
            logUntranslatedItemTagWarnings = builder
                    .comment("A config option mainly for developers. Logs out modded item tags that do not have translations when running on integrated server. Format desired is tag.item.<namespace>.<path> for the translation key. Defaults to SILENCED.")
                    .translation("neoforge.configgui.logUntranslatedItemTagWarnings")
                    .defineEnum("logUntranslatedItemTagWarnings", TagConventionLogWarning.LogWarningMode.SILENCED);

            logLegacyTagWarnings = builder
                    .comment("A config option mainly for developers. Logs out modded tags that are using the 'forge' namespace when running on integrated server. Defaults to DEV_SHORT.")
                    .translation("neoforge.configgui.logLegacyTagWarnings")
                    .defineEnum("logLegacyTagWarnings", TagConventionLogWarning.LogWarningMode.DEV_SHORT);

            attributeAdvancedTooltipDebugInfo = builder
                    .comment("Set this to true to enable showing debug information about attributes on an item when advanced tooltips is on.")
                    .translation("neoforge.configgui.attributeAdvancedTooltipDebugInfo")
                    .define("attributeAdvancedTooltipDebugInfo", true);
        }
    }

    /**
     * Client specific configuration - only loaded clientside from neoforge-client.toml
     */
    public static class Client {
        public final BooleanValue experimentalForgeLightPipelineEnabled;
        boolean experimentalPipelineActive;

        public final BooleanValue showLoadWarnings;

        public final BooleanValue useCombinedDepthStencilAttachment;

        public final BooleanValue logUntranslatedConfigurationWarnings;

        Client(ModConfigSpec.Builder builder) {
            experimentalForgeLightPipelineEnabled = builder
                    .comment("EXPERIMENTAL: Enable the NeoForge block rendering pipeline - fixes the lighting of custom models.")
                    .translation("neoforge.configgui.forgeLightPipelineEnabled")
                    .define("experimentalForgeLightPipelineEnabled", false);

            showLoadWarnings = builder
                    .comment("When enabled, NeoForge will show any warnings that occurred during loading.")
                    .translation("neoforge.configgui.showLoadWarnings")
                    .define("showLoadWarnings", true);

            useCombinedDepthStencilAttachment = builder
                    .comment("Set to true to use a combined DEPTH_STENCIL attachment instead of two separate ones.")
                    .translation("neoforge.configgui.useCombinedDepthStencilAttachment")
                    .define("useCombinedDepthStencilAttachment", false);

            logUntranslatedConfigurationWarnings = builder
                    .comment("A config option mainly for developers. Logs out configuration values that do not have translations when running a client in a development environment.")
                    .translation("neoforge.configgui.logUntranslatedConfigurationWarnings")
                    .define("logUntranslatedConfigurationWarnings", true);
        }
    }

    static final ModConfigSpec clientSpec;
    public static final Client CLIENT;
    static {
        final Pair<Client, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Client::new);
        clientSpec = specPair.getRight();
        CLIENT = specPair.getLeft();
    }

    static final ModConfigSpec commonSpec;
    public static final Common COMMON;
    static {
        final Pair<Common, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Common::new);
        commonSpec = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    static final ModConfigSpec serverSpec;
    public static final Server SERVER;
    static {
        final Pair<Server, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Server::new);
        serverSpec = specPair.getRight();
        SERVER = specPair.getLeft();
    }

    /**
     * {@return true if the server configuration has been loaded}
     */
    public static boolean isServerConfigLoaded() {
        return serverSpec.isLoaded();
    }

    @SubscribeEvent
    public static void onLoad(final ModConfigEvent.Loading configEvent) {
        LogManager.getLogger().debug(Logging.FORGEMOD, "Loaded NeoForge config file {}", configEvent.getConfig().getFileName());

        if (configEvent.getConfig().getSpec() == clientSpec) {
            CLIENT.experimentalPipelineActive = CLIENT.experimentalForgeLightPipelineEnabled.getAsBoolean();
        }
    }

    @SubscribeEvent
    public static void onFileChange(final ModConfigEvent.Reloading configEvent) {
        LogManager.getLogger().debug(Logging.FORGEMOD, "NeoForge config just got changed on the file system!");

        if (configEvent.getConfig().getSpec() == clientSpec) {
            boolean experimentalPipelineActive = CLIENT.experimentalForgeLightPipelineEnabled.getAsBoolean();
            if (experimentalPipelineActive != CLIENT.experimentalPipelineActive) {
                CLIENT.experimentalPipelineActive = experimentalPipelineActive;
                ClientHooks.reloadRenderer();
            }
        }
    }

    //General
    //public static boolean disableVersionCheck = false;
    //public static boolean logCascadingWorldGeneration = true; // see Chunk#logCascadingWorldGeneration()
    //public static boolean fixVanillaCascading = false;
}
