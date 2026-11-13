/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.common.util.LogicalSidedProvider;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.StructureModifier;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.mixins.MappedRegistryAccessor;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.NeoForgeRegistries.Keys;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatReport;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatibility;
import net.neoforged.neoforge.server.threading.RegionThreadingConflicts;
import net.neoforged.neoforge.server.threading.RegionThreadingDeclarations;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.Nullable;

public class ServerLifecycleHooks {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Marker SERVERHOOKS = MarkerManager.getMarker("SERVERHOOKS");
    private static final LevelResource SERVERCONFIG = new LevelResource("serverconfig");
    @Nullable
    private static volatile CountDownLatch exitLatch = null;
    @Nullable
    private static MinecraftServer currentServer;

    private static Path getServerConfigPath(final MinecraftServer server) {
        final Path serverConfig = server.getWorldPath(SERVERCONFIG);
        if (!Files.isDirectory(serverConfig)) {
            try {
                Files.createDirectories(serverConfig);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        final Path explanation = serverConfig.resolve("readme.txt");
        if (!Files.exists(explanation)) {
            try {
                Files.writeString(explanation, """
                        Any server configs put in this folder will override the corresponding server config from <instance path>/config/<config path>.
                        If the config being transferred is in a subfolder of the base config folder make sure to include that folder here in the path to the file you are overwriting.
                        For example if you are overwriting a config with the path <instance path>/config/ExampleMod/config-server.toml, you would need to put it in serverconfig/ExampleMod/config-server.toml
                        """, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return serverConfig;
    }

    public static void handleServerAboutToStart(final MinecraftServer server) {
        currentServer = server;
        // on the dedi server we need to force the stuff to setup properly
        LogicalSidedProvider.setServer(() -> server);
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, FMLPaths.CONFIGDIR.get(), getServerConfigPath(server));
        installRegionThreading();
        runModifiers(server);
        NeoForge.EVENT_BUS.post(new ServerAboutToStartEvent(server));
    }

    /**
     * Installs the region threading engine the configuration asks for, as far as the compatibility layer allows
     * it, applies the declaration files and records the verdict for the diagnostics and for
     * {@code /neoforge regionthreading compat}.
     * <p>
     * The order is the contract: the known-conflict table decides which slices exist, the engine is installed
     * with the remaining ones, and only then are the declaration files read and applied, before any level is
     * created and therefore before the first tick. Both halves are fail-closed: a conflict removes a slice and
     * a declaration only ever moves a type back to the server thread.
     */
    private static void installRegionThreading() {
        final boolean requested = NeoForgeConfig.SERVER.enableRegionThreadingBridge.get();
        final boolean forced = NeoForgeConfig.SERVER.regionThreadingForceWithKnownConflicts.get();
        final RegionThreadingConflicts.Plan compatibility = RegionThreadingConflicts.plan(
                requested,
                NeoForgeConfig.SERVER.enableRegionThreadingEntityTicks.get(),
                NeoForgeConfig.SERVER.enableRegionThreadingSpawning.get(),
                modId -> net.neoforged.fml.ModList.get().isLoaded(modId),
                forced);
        if (forced && requested) {
            // Measurement only: the table's actions are suppressed, so say so loudly and never let a forced run
            // look like a supported configuration in a log or a bug report.
            LOGGER.warn(SERVERHOOKS, "Region threading runs FORCED with known conflicts present ({}); this configuration is for measurement and is not supported",
                    compatibility.conflicts().stream().map(RegionThreadingConflicts.Conflict::modId).toList());
        }
        final List<String> blockedBy = new ArrayList<>();
        RegionThreadingEngine installed = null;
        if (!compatibility.installEngine()) {
            if (requested) {
                // A known conflict makes every worker slice unsafe, so refusing to install the engine is the
                // only fail-closed answer; the server starts on the vanilla server-thread tick.
                logCompatibility(compatibility, false);
                blockedBy.add("known conflicts: " + compatibility.conflicts().stream()
                        .map(RegionThreadingConflicts.Conflict::modId).toList());
            }
        } else if (RegionThreadingCompatibility.canInstallBridge()) {
            // The layout carries the fixed region edge length, so the engine, its regionizer and the
            // physical section partitions of every level created afterwards share one region size.
            final RegionLayout layout = RegionLayout.of(NeoForgeConfig.SERVER.regionThreadingMode.get(), NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.get());
            // Entity worker ticks need region-owned entity storage: the ticks of the leased entities mutate
            // the level's entity storage, and only the region-owned guards let a worker run them while the
            // server thread keeps the shared vanilla maps. A dynamic layout cannot partition the storage, so
            // the entity slice stays on the server thread there.
            final RegionEntityStorageMode entityStorageMode = RegionEntityStorageMode.forConfiguration(
                    compatibility.entityTickWorkers(), layout);
            final RegionThreadingEngine engine = new RegionThreadingEngine(NeoForgeConfig.SERVER.regionThreadingWorkerThreads.get(), NeoForgeConfig.SERVER.enableRegionThreadingWorkers.get(), NeoForgeConfig.SERVER.enableGlobalRegionThreadingWorker.get(), compatibility.entityTickWorkers(), NeoForgeConfig.SERVER.enableRegionThreadingBlockEntityTicks.get(), NeoForgeConfig.SERVER.enableRegionThreadingScheduledTicks.get(), NeoForgeConfig.SERVER.enableRegionThreadingRandomTicks.get(), compatibility.spawnWorkers(), NeoForgeConfig.SERVER.enableRegionThreadingPersistentWorkers.get(), NeoForgeConfig.SERVER.enableRegionThreadingPackedRegionLeases.get(), layout, entityStorageMode);
            // The engine ships the vanilla declarations of the types whose own tick reaches a mutation the
            // lease does not own (a splitting slime, a discarding fishing hook); every other type ticks on
            // its region worker. The vanilla POI declarations are gone because the level's POI storage is
            // partitioned per region (enableRegionThreadingPoi), so a villager, golem, raider, bee, zombie
            // or fox tick reaches region-owned sections instead of one shared manager.
            RegionThreadingManager.install(engine);
            installed = engine;
            logCompatibility(compatibility, true);
        } else {
            blockedBy.add("mod support policy: default=" + RegionThreadingCompatibility.defaultSupport()
                    + ", unsupported mods=" + RegionThreadingCompatibility.unsupportedMods());
            LOGGER.warn(SERVERHOOKS, "Region threading bridge is enabled but blocked by compatibility policy {}; unsupported mods: {}", RegionThreadingCompatibility.defaultSupport(), RegionThreadingCompatibility.unsupportedMods());
        }
        // The declarations are read whether or not an engine was installed: a block-entity declaration narrows
        // the block-entity allowlist of the engine, and the report has to name what the files asked for even
        // when the engine is off.
        final RegionThreadingDeclarations.Report declarations = RegionThreadingDeclarations.load();
        declarations.applyTo(installed);
        if (!declarations.isEmpty()) {
            LOGGER.info(SERVERHOOKS, "Region threading declarations in effect: {}{}", declarations.declarationCount(),
                    declarations.ignored().isEmpty() ? "" : " (" + declarations.ignored().size() + " ignored)");
        }
        for (final String ignored : declarations.ignored()) {
            // A file a mod declared but did not ship, or an entry a pack author mistyped, is a configuration
            // error of that declaration and is reported once per server start instead of being swallowed.
            LOGGER.warn(SERVERHOOKS, "Region threading declaration ignored: {}", ignored);
        }
        RegionThreadingCompatReport.record(requested, installed != null,
                blockedBy.isEmpty() ? null : String.join("; ", blockedBy), compatibility, declarations);
        if (!requested && !RegionThreadingCompatibility.requiredMods().isEmpty()) {
            LOGGER.warn(SERVERHOOKS, "Region threading bridge is disabled but required by mods: {}", RegionThreadingCompatibility.requiredMods());
        }
    }

    /**
     * Logs the verdict of the known-conflict table once per server start, at error level when the engine is
     * not installed and warning level when a slice is left off.
     */
    private static void logCompatibility(final RegionThreadingConflicts.Plan compatibility, boolean engineInstalled) {
        for (final String line : compatibility.lines()) {
            if (engineInstalled) {
                LOGGER.warn(SERVERHOOKS, line);
            } else {
                LOGGER.error(SERVERHOOKS, line);
            }
        }
    }

    public static void handleServerStarting(final MinecraftServer server) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            LanguageHook.loadModLanguages(server);
            // GameTestServer requires the gametests to be registered earlier, so it is done in main and should not be done twice.
            if (!(server instanceof GameTestServer))
                GameTestHooks.registerGametests();
        }
        PermissionAPI.initializePermissionAPI();
        NeoForge.EVENT_BUS.post(new ServerStartingEvent(server));
    }

    public static void handleServerStarted(final MinecraftServer server) {
        NeoForge.EVENT_BUS.post(new ServerStartedEvent(server));
    }

    public static void handleServerStopping(final MinecraftServer server) {
        NeoForge.EVENT_BUS.post(new ServerStoppingEvent(server));
    }

    public static void expectServerStopped() {
        exitLatch = new CountDownLatch(1);
    }

    public static void handleServerStopped(final MinecraftServer server) {
        NeoForge.EVENT_BUS.post(new ServerStoppedEvent(server));
        RegionThreadingManager.clear();
        // The declarations of a stopped server must not be reported, and a static block-entity declaration must
        // not outlive the server it belongs to; the next start applies its own set instead.
        RegionThreadingCompatReport.reset();
        RegionTickCompatibility.declareServerThreadOnlyTypes(Map.of());
        currentServer = null;
        LogicalSidedProvider.setServer(null);
        CountDownLatch latch = exitLatch;

        if (latch != null) {
            latch.countDown();
            exitLatch = null;
        }
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Nullable
    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void handleExit(int retVal) {
        System.exit(retVal);
    }

    private static <T> void ensureProperSync(boolean modified, Holder.Reference<T> holder, Registry<T> registry) {
        if (modified) {
            // If the object's networked data has been modified, we force it to sync by removing its original KnownPack info.
            Optional<RegistrationInfo> originalInfo = registry.registrationInfo(holder.key());
            originalInfo.ifPresent(info -> {
                RegistrationInfo newInfo = new RegistrationInfo(Optional.empty(), info.lifecycle());
                //noinspection unchecked
                ((MappedRegistryAccessor<T>) registry).neoforge$getRegistrationInfos().put(holder.key(), newInfo);
            });
        }
    }

    private static void runModifiers(final MinecraftServer server) {
        final RegistryAccess registries = server.registryAccess();

        // The order of holders() is the order modifiers were loaded in.
        final List<BiomeModifier> biomeModifiers = registries.registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS)
                .holders()
                .map(Holder::value)
                .toList();
        final List<StructureModifier> structureModifiers = registries.registryOrThrow(Keys.STRUCTURE_MODIFIERS)
                .holders()
                .map(Holder::value)
                .toList();

        final Set<EntityType<?>> entitiesWithoutPlacements = new HashSet<>();

        // Apply sorted biome modifiers to each biome.
        final var biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        biomeRegistry.holders().forEach(biomeHolder -> {
            final Biome biome = biomeHolder.value();
            ensureProperSync(
                    biome.modifiableBiomeInfo()
                            .applyBiomeModifiers(biomeHolder, biomeModifiers, registries),
                    biomeHolder,
                    biomeRegistry);

            final MobSpawnSettings mobSettings = biome.getMobSettings();
            mobSettings.getSpawnerTypes().forEach(category -> {
                mobSettings.getMobs(category).unwrap().forEach(data -> {
                    if (SpawnPlacements.hasPlacement(data.type)) return;
                    entitiesWithoutPlacements.add(data.type);
                });
            });

            for (MobCategory mobCategory : mobSettings.getSpawnerTypes()) {
                for (MobSpawnSettings.SpawnerData spawnerData : mobSettings.getMobs(mobCategory).unwrap()) {
                    if (spawnerData.type.getCategory() != mobCategory) {
                        // Ignore vanilla bugged entries to reduce unneeded logging. See https://bugs.mojang.com/browse/MC-1788 for the Ocelot/Jungle vanilla bug.
                        boolean isVanillaBug = spawnerData.type == EntityType.OCELOT && (biomeHolder.is(Biomes.JUNGLE) || biomeHolder.is(Biomes.BAMBOO_JUNGLE));
                        if (!isVanillaBug) {
                            LOGGER.warn("Detected {} that was registered with {} mob category but was added under {} mob category for {} biome! " +
                                    "Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.",
                                    BuiltInRegistries.ENTITY_TYPE.getKey(spawnerData.type),
                                    spawnerData.type.getCategory(),
                                    mobCategory,
                                    biomeHolder.getKey().location());
                        }
                    }
                }
            }
        });
        // Rebuild the indexed feature list
        registries.registryOrThrow(Registries.LEVEL_STEM).forEach(levelStem -> {
            levelStem.generator().refreshFeaturesPerStep();
        });

        // Apply sorted structure modifiers to each structure.
        registries.registryOrThrow(Registries.STRUCTURE).holders().forEach(structureHolder -> {
            structureHolder.value().modifiableStructureInfo().applyStructureModifiers(structureHolder, structureModifiers);
        });

        if (!entitiesWithoutPlacements.isEmpty() && !FMLLoader.isProduction()) {
            LOGGER.error("The following entities have not registered to the RegisterSpawnPlacementsEvent, but a spawn entry was found. This will mean that the entity doesn't have restrictions on its spawn location, please register a spawn placement for the entity, you can register with NO_RESTRICTIONS if you don't want any restrictions."
                    + entitiesWithoutPlacements.stream().map(EntityType::getKey).map(ResourceLocation::toString).collect(Collectors.joining("\n\t - ", "\n\t - ", "")));
        }
    }
}
