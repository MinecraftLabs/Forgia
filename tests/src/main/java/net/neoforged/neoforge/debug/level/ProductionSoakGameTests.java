/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Soaks a real generated world with the production region threading combination enabled.
 * <p>
 * The run is the milestone's end-to-end proof: it enables every region threading switch of
 * {@code NeoForgeConfig} (region-owned entity storage, entity ticks, block-entity ticks, scheduled ticks,
 * random/chunk ticks, natural spawning, regionized POI, cross-region border updates and stale-read
 * detection), generates a real overworld region - terrain, trees, water, caves - and populates it with mobs
 * of several types, villagers that own a bed and a workstation, animals, a working redstone contraption
 * spanning a region border, a hopper/furnace line, a farm whose crops grow from random ticks and a
 * player-like entity that crosses region borders. It then runs for a long window and asserts the
 * invariants continuously, not only at the end.
 * <p>
 * The test is registered in every GameTest run and returns immediately unless
 * {@value #ENABLED_PROPERTY} is set, so the default batches stay as fast as before. A soak run sets the
 * property, filters the batch and selects the world the level is generated from; see the
 * {@code soakGameTestServer} and {@code soakGameTestServerReference} runs of {@code tests/build.gradle}. The
 * threading-on run enables the whole combination from the server config, the reference run disables the
 * bridge and compares its redstone state series with the series the threading-on run wrote. The measured
 * window is three quarters noon - the villagers claim their POIs and the crops grow - and one quarter
 * midnight, when the natural spawning slice spawns on the surface.
 * <h2>What proves a slice ran on workers</h2>
 * The heartbeat of the window prints every counter that distinguishes "requested" from "effective":
 * {@code entityTicksOnWorkers}, {@code blockEntityTicksOnWorkers}, {@code chunkTicksOnWorkers},
 * {@code spawnChunksOnWorkers}, the regionized POI query counters, the border update fixpoint counters
 * (recorded/applied/delayed/invalidated), the tick failures and the ownership violations. The end-state
 * invariants require the entity, block-entity, chunk and spawn slices to have moved on workers, the border
 * fixpoint to have applied a cross-region update and the stale-read check to have observed a foreign read,
 * so a switch that silently does nothing fails the batch instead of passing.
 * <h2>Assertions</h2>
 * Continuously (every tick): no active tick batch survives a tick, the hopper line conserves its items, the
 * redstone contraption records its state series, and a rotating slice of the population is checked against
 * its region mapping. Every {@value #COUNTER_CHECK_TICKS} ticks: no ownership violation and no tick failure
 * appeared. At the end: no region mismatch, no stale mapping, no unmapped live entity, the identity ledger
 * agrees with the level, the spawn counts stay inside the vanilla mob cap, no queue grew without bound, the
 * heap stayed inside its stated bound, and - for the reference run - the redstone state series equals the
 * one the threading-on run recorded at the same seed and the same window.
 */
@ForEachTest(groups = "level.region")
public class ProductionSoakGameTests {
    /** System property of the soak run. Without it the test returns immediately. */
    static final String ENABLED_PROPERTY = "neoforge.serverSoak";
    /**
     * System property that sets the measured window of the soak in ticks. The run configuration passes the
     * window, so the same test can do a short iteration and the full {@value #DEFAULT_MEASURED_TICKS}-tick
     * soak.
     */
    static final String TICKS_PROPERTY = "neoforge.soakTicks";
    /**
     * System property that selects whether the run is the threading-on soak or its server-thread reference.
     * The reference run has no engine at all, which is what "region threading off" means, and fails the batch
     * if a bridge is installed anyway.
     */
    static final String REGION_THREADING_PROPERTY = "neoforge.soakRegionThreading";
    /**
     * System property of the reference run: the redstone state series the threading-on run wrote. The
     * reference run asserts that its own series equals that file tick for tick, which is the "redstone state
     * equal to a reference run with region threading off at the same seeds" invariant of the milestone.
     */
    static final String REFERENCE_PROPERTY = "neoforge.soakReference";
    /**
     * System property of the report directory, relative to the game directory of the run. The redstone series
     * of the window is written there.
     */
    static final String REPORT_PROPERTY = "neoforge.soakReport";

    /** Warmup ticks the sampler measures before the measured window: chunk load, content, POI claims. */
    private static final int WARMUP_TICKS = 200;
    /** Measured ticks of the soak: five minutes at twenty ticks per second. */
    private static final int DEFAULT_MEASURED_TICKS = 6000;
    /** Annotated tick timeout of the batch; the window has to fit into it with slack, which the test asserts. */
    private static final int TIMEOUT_TICKS = 9000;
    /** Ticks between two heartbeat lines. */
    private static final int HEARTBEAT_TICKS = 200;
    /** Ticks between two counter checks of the continuous assertions. */
    private static final int COUNTER_CHECK_TICKS = 100;
    /** Ticks the content settles before the sampler starts its warmup. */
    private static final int SETTLE_TICKS = 40;
    /** Ticks between two restores of the border trample fixture. */
    private static final int TRAMPLE_INTERVAL_TICKS = 40;
    /** Ticks between two moves of the player-like entity. */
    private static final int PLAYER_MOVE_INTERVAL_TICKS = 40;
    /** Random tick speed of the window; the vanilla default is 3. */
    private static final int RANDOM_TICK_SPEED = 3;
    /** Region edge length in chunks of the production layout. */
    private static final int REGION_CHUNK_SIZE = 3;
    /** First chunk of the generated area, in chunks. Chunk {@code 60} is the first chunk of a 3-chunk region. */
    private static final int AREA_ORIGIN_CHUNK_X = 60;
    private static final int AREA_ORIGIN_CHUNK_Z = 60;
    /** Edge length of the generated area in chunks; 15 chunks are five 3-chunk regions per axis. */
    private static final int AREA_CHUNK_SIDE = 15;
    /** Chunk ticket distance that makes a chunk entity-ticking, i.e. the level ticks its entities. */
    private static final int ENTITY_TICKING_TICKET_RADIUS = 2;
    /** Iron ore of the hopper line, in stacks of 64; the line smelts them for the whole window. */
    private static final int HOPPER_ORE_STACKS = 9;
    /** Villagers of the window; each owns a bed and a workstation. */
    private static final int VILLAGER_COUNT = 4;
    /** Animals per type of the window. */
    private static final int ANIMALS_PER_TYPE = 12;
    /** Hostile mobs of the window, spread over the generated surface at night. */
    private static final int ZOMBIE_COUNT = 40;
    private static final int SKELETON_COUNT = 12;
    private static final int CREEPER_COUNT = 12;
    /** The bound of the after-GC heap growth of the window, in bytes. */
    private static final long HEAP_GROWTH_BOUND_BYTES = 256L * 1024L * 1024L;
    /** The bound of the region count growth of the window. */
    private static final int REGION_COUNT_GROWTH_BOUND = 64;
    /** The bound of the tracked entity task growth of the window. */
    private static final int ENTITY_TASK_GROWTH_BOUND = 64;
    /** Distinct redstone states the window has to observe, so a settled contraption cannot pass the soak. */
    private static final int MIN_REDSTONE_STATES = 2;
    /**
     * One contraption transition per this many measured ticks is the floor of a running clock. The observer
     * clock of the soak toggles every few ticks, so a real clock is far above the floor and a stuck one is at
     * zero.
     */
    private static final int MIN_REDSTONE_TRANSITIONS_PER_TICK = 8;
    /** Fuel slot of the vanilla furnace family. */
    private static final int FURNACE_FUEL_SLOT = 1;

    static final String MSPT_HEADER = "profile,seed,measuredTicks,window,samples,msptAvg,msptP50,msptP95,msptP99";
    static final String DRIFT_HEADER = "profile,firstHalfAvgMs,lastAvgMs,driftRatio,driftPercent,firstHalfP99Ms,lastP99Ms";
    static final String COUNTER_HEADER = "profile,counter,before,after,delta,perTick";
    static final String STRUCT_HEADER = "point,measuredTick,heapUsedMiB,heapCommittedMiB,heapMaxMiB,gcCollections,gcMillis,regionCount,runningRegions,trackedEntities,trackedEntityTasks,sections,loadedChunks,pendingDeferredMoves,pendingTransfers,rememberedTransfers,activeBatches";
    static final String TICK_HEADER = "ticksChecked,ticksWithActiveBatches,maxActiveBatches,itemChecks,itemMismatches,redstoneTicks,redstoneStates,redstoneTransitions,mappingChecks,mappingMismatches,mappingMismatchesThatSurvivedATick";
    static final String INVARIANT_HEADER = "check,observed,expected,ok";
    static final String ENTITY_HEADER = "category,count";
    static final String HEARTBEAT_HEADER = "tick,windowTick,mspt,regionCount,regionsBehind,regionP99MeanMs,regionP99MaxMs,entityTicksOnWorkers,blockEntityTicksOnWorkers,chunkTicksOnWorkers,spawnChunksOnWorkers,poiLocalQueries,poiPublishedQueries,borderRecorded,borderApplied,borderDelayed,foreignReads,invalidatedPasses,tickFailures,ownershipViolations,itemCount,entities,contentMobs,heapUsedMiB,foreignWriteRefusals,unstableReadRefusals,rawChunkInternalsAccesses,boundaryChunkTicks,borderBlockWrites,borderBlockWritesApplied,readAfterBlockWrite";

    @GameTest(batch = "productionSoak", timeoutTicks = TIMEOUT_TICKS)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Soaks a real generated world with the production region threading combination; only runs when neoforge.serverSoak is set")
    static void soaksProductionRegionThreading(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SOAK_NOTE skipped production soak: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final int measuredTicks = intProperty(TICKS_PROPERTY, DEFAULT_MEASURED_TICKS);
            helper.assertTrue(measuredTicks > 0 && SETTLE_TICKS + WARMUP_TICKS + measuredTicks + 2 <= TIMEOUT_TICKS,
                    "the soak window of " + measuredTicks + " measured ticks does not fit into the batch timeout of " + TIMEOUT_TICKS + " ticks");
            final ServerLevel level = helper.getLevel();
            final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
            final String profile = engine.isPresent() ? "on" : "off";
            assertConfiguration(helper, engine);

            final List<ChunkPos> grid = soakGrid();
            final SoakWorld world = new SoakWorld(level, grid);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, measuredTicks);
            final SoakWatch watch = new SoakWatch(level, world, measuredTicks);
            final Heartbeat heartbeat = new Heartbeat(level, world, engine, profile);
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<Optional<RegionThreadingDiagnostics>> after = new AtomicReference<>(Optional.empty());
            final AtomicReference<SoakSample> startSample = new AtomicReference<>();
            final AtomicReference<SoakSample> endSample = new AtomicReference<>();
            final AtomicBoolean cleaned = new AtomicBoolean();
            final GameRules.BooleanValue mobSpawning = level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
            final boolean previousMobSpawning = mobSpawning.get();
            final GameRules.IntegerValue randomTickSpeed = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTickSpeed = randomTickSpeed.get();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            final GameRules.BooleanValue mobGriefing = level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
            final boolean previousMobGriefing = mobGriefing.get();
            final Difficulty previousDifficulty = level.getDifficulty();
            final long previousDayTime = level.getDayTime();
            final Runnable restoreServerState = () -> {
                world.discard();
                releaseChunks(level, grid);
                mobSpawning.set(previousMobSpawning, level.getServer());
                randomTickSpeed.set(previousRandomTickSpeed, level.getServer());
                daylight.set(previousDaylight, level.getServer());
                mobGriefing.set(previousMobGriefing, level.getServer());
                level.getServer().setDifficulty(previousDifficulty, true);
                level.setDayTime(previousDayTime);
            };
            helper.addEndListener(passed -> {
                NeoForge.EVENT_BUS.unregister(sampler);
                NeoForge.EVENT_BUS.unregister(watch);
                NeoForge.EVENT_BUS.unregister(heartbeat);
                if (cleaned.compareAndSet(false, true)) {
                    restoreServerState.run();
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the soak chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        // The GameTest server runs with mob spawning off, random ticks off and on peaceful, so
                        // neither the spawning nor the chunk tick slice would run for any chunk. The window
                        // turns them on and restores them afterwards; night makes the monster population and
                        // the villager sleep cycle part of the workload.
                        mobSpawning.set(true, level.getServer());
                        randomTickSpeed.set(RANDOM_TICK_SPEED, level.getServer());
                        daylight.set(false, level.getServer());
                        mobGriefing.set(true, level.getServer());
                        level.getServer().setDifficulty(Difficulty.NORMAL, true);
                        world.build();
                        watch.install();
                    })
                    .thenWaitUntil(() -> helper.assertTrue(
                            world.player() != null && world.player().isAddedToLevel()
                                    && grid.stream().filter(level::isNaturalSpawningAllowed).count() > grid.size() / 2,
                            "the soak area never became spawnable and entity-ticking"))
                    .thenIdle(SETTLE_TICKS)
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        startSample.set(SoakSample.of("start", 0, engine));
                        watch.startWindow();
                        heartbeat.startWindow();
                        NeoForge.EVENT_BUS.register(sampler);
                        NeoForge.EVENT_BUS.register(watch);
                        NeoForge.EVENT_BUS.register(heartbeat);
                        sampler.startWindow(level.getGameTime());
                    })
                    .thenIdle(WARMUP_TICKS + measuredTicks / 2)
                    .thenExecute(() -> {
                        watch.sampleMiddle();
                        heartbeat.sampleMiddle();
                    })
                    .thenIdle(measuredTicks / 4)
                    .thenExecute(watch::startNight)
                    .thenIdle(measuredTicks - measuredTicks / 2 - measuredTicks / 4)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        NeoForge.EVENT_BUS.unregister(watch);
                        NeoForge.EVENT_BUS.unregister(heartbeat);
                        // The counters of the window end here, before the extra tick below.
                        after.set(RegionThreadingHooks.diagnostics());
                        endSample.set(watch.sampleEnd());
                    })
                    // One tick after the window every entity that still ticks published its region mapping
                    // again (the mapping names the region of the tick's start position), so the end-state
                    // mapping check reads a mapping that no longer lags a move of the last measured tick.
                    .thenIdle(1)
                    .thenExecute(() -> {
                        final List<Invariant> invariants = report(new SoakWindow(profile, level, world, sampler, watch,
                                before.get(), after.get(), startSample.get(), endSample.get(), measuredTicks));
                        if (cleaned.compareAndSet(false, true)) {
                            restoreServerState.run();
                        }
                        // The report is printed before the invariants fail the batch, so a broken soak always
                        // leaves its evidence in the log.
                        for (Invariant invariant : invariants) {
                            helper.assertTrue(invariant.ok(), "soak invariant " + invariant.name()
                                    + " expected " + invariant.expected() + " but observed " + invariant.observed()
                                    + " (profile " + profile + ", window " + measuredTicks + " ticks)");
                        }
                    })
                    .thenSucceed();
        });
    }

    /**
     * Asserts that the run is configured with the intended production combination.
     * <p>
     * The threading-on run must have the engine installed with region-owned entity storage and every slice
     * effective; the reference run must have no engine at all, because that is what "region threading off"
     * means. A run that sets half of the switches fails here instead of reporting a soak of a combination
     * nobody asked for.
     */
    private static void assertConfiguration(ExtendedGameTestHelper helper, Optional<RegionThreadingDiagnostics> engine) {
        final boolean expectedOn = !"false".equalsIgnoreCase(System.getProperty(REGION_THREADING_PROPERTY));
        if (!expectedOn) {
            helper.assertTrue(engine.isEmpty(), "the reference soak must run without the region threading bridge, but the engine is installed");
            return;
        }
        helper.assertTrue(engine.isPresent(), "the production soak must install the region threading bridge");
        final RegionThreadingDiagnostics diagnostics = engine.get();
        helper.assertTrue(diagnostics.workerExecutionEnabled(), "the production soak needs the region worker drains enabled");
        helper.assertTrue(diagnostics.globalWorkerExecutionEnabled(), "the production soak needs the global region worker enabled");
        helper.assertTrue(diagnostics.entityTickWorkerExecutionEnabled(), "the production soak needs the entity tick slice enabled");
        helper.assertTrue(diagnostics.blockEntityTickWorkerExecutionEnabled(), "the production soak needs the block-entity tick slice enabled");
        helper.assertTrue(diagnostics.chunkTickWorkerExecutionEnabled(), "the production soak needs the chunk tick slice enabled");
        helper.assertTrue(diagnostics.spawnWorkerExecutionEnabled(), "the production soak needs the natural spawning slice enabled");
        helper.assertTrue(diagnostics.entityStorageMode() == RegionEntityStorageMode.REGION_OWNED,
                "the entity tick slice of the production soak needs region-owned entity storage, got " + diagnostics.entityStorageMode());
        helper.assertTrue(diagnostics.regionPolicy() == RegionPolicy.FIXED_3X3 && diagnostics.regionChunkSize() == REGION_CHUNK_SIZE,
                "the production soak needs the fixed " + REGION_CHUNK_SIZE + "-chunk layout, got " + diagnostics.regionPolicy() + "/" + diagnostics.regionChunkSize());
        helper.assertTrue(diagnostics.workerThreadCount() >= 2, "the production soak needs at least two region workers, got " + diagnostics.workerThreadCount());
        helper.assertTrue(diagnostics.chunkTickWorkerDispatchEffective(), "the chunk tick slice of the production soak is requested but not effective");
        helper.assertTrue(diagnostics.spawnWorkerDispatchEffective(), "the spawning slice of the production soak is requested but not effective");
        helper.assertTrue(NeoForgeConfig.SERVER.enableRegionThreadingPoi.get(), "the production soak needs the regionized POI storage enabled");
        helper.assertTrue(NeoForgeConfig.SERVER.enableRegionThreadingBorderUpdates.get(), "the production soak needs the border update fixpoint enabled");
    }

    /**
     * {@return the force-loaded chunk grid of the soak}
     * <p>
     * The grid starts on a region boundary of the 3-chunk layout and is five regions wide, so it contains
     * region borders in both axes and 25 interior chunks, the chunks whose eight neighbors belong to the
     * same region and that therefore carry the worker-ticked block entities, crops and spawn bodies.
     */
    private static List<ChunkPos> soakGrid() {
        final List<ChunkPos> grid = new ArrayList<>(AREA_CHUNK_SIDE * AREA_CHUNK_SIDE);
        for (int dx = 0; dx < AREA_CHUNK_SIDE; dx++) {
            for (int dz = 0; dz < AREA_CHUNK_SIDE; dz++) {
                grid.add(new ChunkPos(AREA_ORIGIN_CHUNK_X + dx, AREA_ORIGIN_CHUNK_Z + dz));
            }
        }
        return grid;
    }

    /**
     * The generated world region and the content of the soak.
     * <p>
     * Every content site is flattened onto the generated terrain, so the soak runs on the terrain the world
     * generator produced - trees, water and caves included - instead of on a flat platform, and the content
     * itself stays at fixed absolute positions so the threading-on and threading-off runs build exactly the
     * same world. Worker-eligible content (the furnace line, the farm, the crops and the spawn bodies) sits in
     * interior chunks; the redstone contraption and the trample fixture deliberately cross a region border.
     */
    static final class SoakWorld {
        private final ServerLevel level;
        private final List<ChunkPos> grid;
        private final List<Mob> mobs = new ArrayList<>();
        private final List<Villager> villagers = new ArrayList<>();
        private final List<Container> containers = new ArrayList<>();
        private final List<BlockPos> contraption = new ArrayList<>();
        private final List<Long> redstoneStates = Collections.synchronizedList(new ArrayList<>());
        /** The monsters the content spawned; the natural spawning slice must not be blamed for them. */
        private final Set<Entity> contentMonsters = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private BlockPos tramplePos;
        private Cow trampleCow;
        private FakePlayer player;
        private int initialItemCount = -1;
        private int terrainWater;
        private int terrainLeaves;
        private int terrainCaveAir;
        private int crops;
        private int farms;
        private int trees;
        private int fences;
        private int farmBaseY = Integer.MIN_VALUE;

        SoakWorld(ServerLevel level, List<ChunkPos> grid) {
            this.level = level;
            this.grid = grid;
        }

        void build() {
            // The generated terrain is sampled before the content is placed: the soak proves it ran on real
            // terrain, so the run reports and asserts what the generator actually produced.
            sampleTerrain();
            buildFurnaceLine();
            buildFarm();
            buildVillagers();
            buildPasture();
            buildContraption();
            buildTrampleFixture();
            spawnHostileMobs();
            addPlayer();
            if (this.initialItemCount <= 0) {
                throw new IllegalStateException("the hopper line was not filled");
            }
        }

        /**
         * Counts the generated terrain features of the grid: water, leaves of a generated tree, and cave air
         * below the surface. The counts prove the level is a generated world and not the flat gametest
         * platform.
         */
        private void sampleTerrain() {
            for (ChunkPos chunk : this.grid) {
                for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x += 4) {
                    for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z += 4) {
                        final int surface = this.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                        for (int y = this.level.getMinBuildHeight(); y < surface; y += 2) {
                            final BlockState state = this.level.getBlockState(new BlockPos(x, y, z));
                            if (state.is(Blocks.WATER)) {
                                this.terrainWater++;
                            } else if (state.isAir() && y < surface - 8) {
                                this.terrainCaveAir++;
                            } else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES)
                                    || state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.JUNGLE_LEAVES)
                                    || state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES)) {
                                        this.terrainLeaves++;
                                    }
                        }
                    }
                }
                for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x += 3) {
                    for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z += 3) {
                        final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        final BlockState state = this.level.getBlockState(new BlockPos(x, surface, z));
                        if (state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) || state.is(Blocks.SPRUCE_LOG)) {
                            this.trees++;
                        }
                    }
                }
            }
        }

        /**
         * Builds the hopper/furnace line in the interior chunk {@code (64, 61)}.
         * <p>
         * A chest of iron ore feeds a hopper, the hopper feeds a furnace whose fuel slot holds a lava bucket,
         * the furnace output hopper feeds a second chest. A lava bucket returns an empty bucket to the fuel
         * slot, so the item count of the five containers is conserved by every transfer and every smelt - the
         * conservation assertion of the soak. The line sits in one interior chunk, so every one of its
         * tickers is admitted to the region worker of its region.
         */
        private void buildFurnaceLine() {
            final int x = interiorBlockX(1);
            final int z = interiorBlockZ(1);
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            flatten(x - 2, z - 2, x + 2, z + 2, surface + 1);
            final int base = surface + 1;
            this.level.setBlockAndUpdate(new BlockPos(x, base + 4, z), Blocks.CHEST.defaultBlockState());
            this.level.setBlockAndUpdate(new BlockPos(x, base + 3, z), Blocks.HOPPER.defaultBlockState());
            this.level.setBlockAndUpdate(new BlockPos(x, base + 2, z), Blocks.FURNACE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
            this.level.setBlockAndUpdate(new BlockPos(x, base + 1, z), Blocks.HOPPER.defaultBlockState());
            this.level.setBlockAndUpdate(new BlockPos(x, base, z), Blocks.CHEST.defaultBlockState());
            // The vanilla hopper faces down, so the upper hopper pulls from the chest above it and pushes into
            // the furnace, and the lower hopper pulls from the furnace and pushes into the chest below it.
            final Container source = containerAt(new BlockPos(x, base + 4, z));
            for (int slot = 0; slot < HOPPER_ORE_STACKS; slot++) {
                source.setItem(slot, new ItemStack(Items.IRON_ORE, 64));
            }
            final Container furnace = containerAt(new BlockPos(x, base + 2, z));
            furnace.setItem(FURNACE_FUEL_SLOT, new ItemStack(Items.LAVA_BUCKET));
            this.containers.add(source);
            this.containers.add(containerAt(new BlockPos(x, base + 3, z)));
            this.containers.add(furnace);
            this.containers.add(containerAt(new BlockPos(x, base + 1, z)));
            this.containers.add(containerAt(new BlockPos(x, base, z)));
            this.initialItemCount = itemCount();
        }

        /**
         * Builds the farm in the interior chunk {@code (61, 61)}: farmland with a water channel and wheat on
         * top. The crops grow from the random ticks the chunk tick slice runs on the region worker.
         */
        private void buildFarm() {
            final int x = chunkMinX(AREA_ORIGIN_CHUNK_X) + 1;
            final int z = chunkMinZ(AREA_ORIGIN_CHUNK_Z) + 1;
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 7, z + 7);
            flatten(x - 1, z - 1, x + 14, z + 14, surface + 1);
            final int base = surface + 1;
            // The heightmap of the farm column changes as soon as the water channel and the crops exist, so
            // the farm remembers its own base and never re-reads the generated height.
            this.farmBaseY = base;
            for (int dx = 0; dx < 14; dx++) {
                for (int dz = 0; dz < 14; dz++) {
                    final BlockPos pos = new BlockPos(x + dx, base, z + dz);
                    if (dz == 7) {
                        this.level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
                        continue;
                    }
                    this.level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                    // Every crop starts at age 0, so the growth assertion of the window measures growth and
                    // not the initial pattern.
                    this.level.setBlockAndUpdate(pos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
                    this.crops++;
                }
            }
            fence(x - 1, z - 1, x + 14, z + 14, base);
            this.farms++;
        }

        /**
         * Builds the villager home in the interior chunk {@code (67, 61)}: one bed and one workstation per
         * villager, so every villager tick takes both POIs of the regionized storage.
         */
        private void buildVillagers() {
            final int x = interiorBlockX(2);
            final int z = interiorBlockZ(0);
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            flatten(x - 2, z - 2, x + 12, z + 12, surface + 1);
            final int base = surface + 1;
            for (int i = 0; i < VILLAGER_COUNT; i++) {
                final int vx = x + 2 + (i % 2) * 6;
                final int vz = z + 2 + (i / 2) * 6;
                final BlockPos foot = new BlockPos(vx, base, vz);
                this.level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                        .setValue(BedBlock.FACING, Direction.SOUTH)
                        .setValue(BedBlock.PART, BedPart.FOOT));
                this.level.setBlockAndUpdate(foot.south(), Blocks.RED_BED.defaultBlockState()
                        .setValue(BedBlock.FACING, Direction.SOUTH)
                        .setValue(BedBlock.PART, BedPart.HEAD));
                // The workstation stands right next to the villager. A villager only converts its potential job
                // site into a claimed job site when it stands within two blocks of it, and the walk to a site
                // it cannot reach would otherwise keep the claim pending for the whole window.
                this.level.setBlockAndUpdate(foot.offset(2, 0, 0), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
                final Villager villager = new Villager(EntityType.VILLAGER, this.level);
                villager.moveTo(vx + 1.5, base, vz + 0.5, 0.0F, 0.0F);
                villager.setPersistenceRequired();
                this.level.addFreshEntity(villager);
                this.villagers.add(villager);
                this.mobs.add(villager);
            }
            fence(x - 2, z - 2, x + 12, z + 12, base);
            // A villager home is lit: an unlit pen at night would let monsters spawn among the beds and kill
            // the POI users the window measures.
            for (int dx = 1; dx <= 11; dx += 5) {
                for (int dz = 1; dz <= 11; dz += 5) {
                    this.level.setBlockAndUpdate(new BlockPos(x + dx, base + 2, z + dz), Blocks.GLOWSTONE.defaultBlockState());
                }
            }
            this.fences++;
        }

        /** Builds the fenced pasture with the animals of the window. */
        private void buildPasture() {
            final int x = interiorBlockX(3);
            final int z = interiorBlockZ(3);
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + 7, z + 7);
            flatten(x - 1, z - 1, x + 14, z + 14, surface + 1);
            final int base = surface + 1;
            for (int dx = 0; dx < 14; dx++) {
                for (int dz = 0; dz < 14; dz++) {
                    this.level.setBlockAndUpdate(new BlockPos(x + dx, base - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            final EntityType<?>[] types = { EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN };
            for (EntityType<?> type : types) {
                for (int i = 0; i < ANIMALS_PER_TYPE; i++) {
                    final Entity spawned = type.create(this.level);
                    if (!(spawned instanceof Mob mob)) {
                        throw new IllegalStateException("the soak pasture needs mobs, got " + type);
                    }
                    mob.moveTo(x + 1.5 + (i % 4) * 3, base, z + 1.5 + (i / 4) * 3, 0.0F, 0.0F);
                    mob.setPersistenceRequired();
                    this.level.addFreshEntity(mob);
                    this.mobs.add(mob);
                }
            }
            fence(x - 1, z - 1, x + 14, z + 14, base);
            this.fences++;
        }

        /**
         * Builds the redstone contraption across the region border at {@code x = 1008}.
         * <p>
         * Two observers facing each other form a one-tick clock. Its output runs east through redstone dust
         * that crosses the border between the region of chunks {@code 60..62} and the region of chunks
         * {@code 63..65}, into a repeater and a lamp. The whole contraption is enclosed and lit, so no mob can
         * change its state, and its block states are hashed every measured tick into the series the reference
         * run compares against.
         */
        private void buildContraption() {
            final int z = interiorBlockZ(4);
            final int border = chunkMinX(AREA_ORIGIN_CHUNK_X + REGION_CHUNK_SIZE);
            final int x = border - 8;
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            flatten(x - 2, z - 3, x + 18, z + 3, surface + 1);
            final int base = surface + 1;
            for (int dx = -2; dx <= 18; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    this.level.setBlockAndUpdate(new BlockPos(x + dx, base - 1, z + dz), Blocks.SMOOTH_STONE.defaultBlockState());
                }
            }
            // Observer clock: O1 looks east at O2, O2 looks west at O1, so each sees the other's pulse. The
            // output leaves the back of O2, i.e. its east face, and the dust line crosses the region border.
            final BlockPos o1 = new BlockPos(x, base, z);
            final BlockPos o2 = new BlockPos(x + 1, base, z);
            this.level.setBlockAndUpdate(o1, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
            this.level.setBlockAndUpdate(o2, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
            for (int dx = 2; dx <= 12; dx++) {
                this.level.setBlockAndUpdate(new BlockPos(x + dx, base, z), Blocks.REDSTONE_WIRE.defaultBlockState());
            }
            this.level.setBlockAndUpdate(new BlockPos(x + 13, base, z), Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, Direction.EAST)
                    .setValue(RepeaterBlock.DELAY, 1));
            this.level.setBlockAndUpdate(new BlockPos(x + 14, base, z), Blocks.REDSTONE_LAMP.defaultBlockState());
            for (int dx = 2; dx <= 14; dx++) {
                this.contraption.add(new BlockPos(x + dx, base, z));
            }
            this.contraption.add(o1);
            this.contraption.add(o2);
            // The enclosure keeps mobs out and the light keeps them from spawning on the platform.
            fence(x - 2, z - 3, x + 18, z + 3, base);
            for (int dx = 0; dx <= 16; dx += 4) {
                this.level.setBlockAndUpdate(new BlockPos(x + dx, base + 2, z - 2), Blocks.GLOWSTONE.defaultBlockState());
                this.level.setBlockAndUpdate(new BlockPos(x + dx, base + 2, z + 2), Blocks.GLOWSTONE.defaultBlockState());
            }
            this.fences++;
        }

        /**
         * Builds the cross-region trample fixture: farmland at the last block of the region of chunks
         * {@code 60..62}, with a cow that the watch drops onto it every
         * {@value #TRAMPLE_INTERVAL_TICKS} ticks.
         * <p>
         * The trample is a block write of an entity tick, so it runs on the worker of the cow's region; the
         * block it writes is the last block of that region, so its neighbor updates target the next region and
         * the border update fixpoint has to record, apply or delay them. It is the one workload that proves
         * the border update slice really runs from a worker in production.
         */
        private void buildTrampleFixture() {
            final int x = chunkMinX(AREA_ORIGIN_CHUNK_X + REGION_CHUNK_SIZE) - 1;
            final int z = interiorBlockZ(2);
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            flatten(x - 2, z - 2, x + 2, z + 2, surface + 1);
            final int base = surface + 1;
            this.tramplePos = new BlockPos(x, base - 1, z);
            this.level.setBlockAndUpdate(this.tramplePos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            final Cow cow = new Cow(EntityType.COW, this.level);
            cow.moveTo(x + 0.5, base + 3.0, z + 0.5, 0.0F, 0.0F);
            cow.setPersistenceRequired();
            this.level.addFreshEntity(cow);
            this.trampleCow = cow;
            this.mobs.add(cow);
            fence(x - 2, z - 2, x + 2, z + 2, base);
            this.fences++;
        }

        /** Spawns the hostile population on the generated surface of the grid. */
        private void spawnHostileMobs() {
            final RandomSource random = RandomSource.create(0x50A4L);
            spawnHostiles(EntityType.ZOMBIE, ZOMBIE_COUNT, random);
            spawnHostiles(EntityType.SKELETON, SKELETON_COUNT, random);
            spawnHostiles(EntityType.CREEPER, CREEPER_COUNT, random);
        }

        private void spawnHostiles(EntityType<? extends Mob> type, int count, RandomSource random) {
            final int minX = chunkMinX(AREA_ORIGIN_CHUNK_X);
            final int minZ = chunkMinZ(AREA_ORIGIN_CHUNK_Z);
            final int maxX = chunkMinX(AREA_ORIGIN_CHUNK_X + AREA_CHUNK_SIDE);
            final int maxZ = chunkMinZ(AREA_ORIGIN_CHUNK_Z + AREA_CHUNK_SIDE);
            int spawned = 0;
            for (int attempt = 0; attempt < count * 20 && spawned < count; attempt++) {
                final int x = minX + 2 + random.nextInt(maxX - minX - 4);
                final int z = minZ + 2 + random.nextInt(maxZ - minZ - 4);
                final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                final BlockPos pos = new BlockPos(x, surface, z);
                if (!this.level.getBlockState(pos).isAir() || !this.level.getBlockState(pos.below()).isSolid()) {
                    continue;
                }
                final Mob mob = type.create(this.level);
                if (mob == null) {
                    continue;
                }
                mob.moveTo(x + 0.5, surface, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
                mob.setPersistenceRequired();
                if (!this.level.addFreshEntity(mob)) {
                    continue;
                }
                this.mobs.add(mob);
                if (mob.getType().getCategory() == MobCategory.MONSTER) {
                    this.contentMonsters.add(mob);
                }
                spawned++;
            }
            if (spawned < count) {
                System.out.println("SOAK_NOTE spawned " + spawned + "/" + count + " " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
            }
        }

        /**
         * Adds the player-like entity of the window: a fake player that is added to the level and moved across
         * a region border every {@value #PLAYER_MOVE_INTERVAL_TICKS} ticks.
         * <p>
         * The player drives the vanilla spawn gate - without a player no chunk is spawnable - and its moves
         * cross the border between the region of chunks {@code 66..68} and the region of chunks
         * {@code 69..71}, so the entity tracking and the region index see a player crossing regions.
         */
        private void addPlayer() {
            final FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(this.level);
            moveTo(fakePlayer, 0);
            this.level.addNewPlayer(fakePlayer);
            this.level.getChunkSource().move(fakePlayer);
            this.player = fakePlayer;
        }

        /** Moves the player-like entity across the region border of its row. */
        void movePlayer() {
            if (this.player == null || this.player.isRemoved()) {
                return;
            }
            final boolean second = this.level.getGameTime() % (2L * PLAYER_MOVE_INTERVAL_TICKS) >= PLAYER_MOVE_INTERVAL_TICKS;
            moveTo(this.player, second ? 32 : 0);
            this.level.getChunkSource().move(this.player);
        }

        private void moveTo(FakePlayer fakePlayer, int offsetX) {
            final int x = interiorBlockX(2) + 8 + offsetX;
            final int z = interiorBlockZ(2) + 8;
            final int surface = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            fakePlayer.moveTo(x + 0.5, surface + 1.0, z + 0.5, 0.0F, 0.0F);
        }

        /**
         * Restores the trample fixture and lifts the cow, so the next landing tramples the farmland again.
         * <p>
         * A fall of three blocks always tramples (the vanilla chance is {@code fallDistance - 0.5}), so every
         * restore produces one cross-region block write from the cow's worker tick.
         */
        void restoreTrampleFixture() {
            if (this.trampleCow == null || this.tramplePos == null || this.trampleCow.isRemoved()) {
                return;
            }
            this.level.setBlockAndUpdate(this.tramplePos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
            this.trampleCow.setPos(this.tramplePos.getX() + 0.5, this.tramplePos.getY() + 3.5, this.tramplePos.getZ() + 0.5);
            this.trampleCow.setDeltaMovement(0.0, 0.0, 0.0);
        }

        /** {@return the sum of the item counts of the hopper line containers} */
        int itemCount() {
            int items = 0;
            for (Container container : this.containers) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    final ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        items += stack.getCount();
                    }
                }
            }
            return items;
        }

        /** {@return the hash of the redstone contraption's block states} */
        long redstoneFingerprint() {
            long hash = 0xcbf29ce484222325L;
            for (BlockPos pos : this.contraption) {
                hash ^= this.level.getBlockState(pos).toString().hashCode();
                hash *= 0x100000001b3L;
            }
            return hash;
        }

        void recordRedstoneState() {
            this.redstoneStates.add(redstoneFingerprint());
        }

        List<Long> redstoneSeries() {
            return new ArrayList<>(this.redstoneStates);
        }

        /** {@return the number of distinct contraption states the window observed} */
        int redstoneStateCount() {
            return new HashSet<>(this.redstoneStates).size();
        }

        /**
         * {@return the number of measured ticks at which the contraption changed its state}
         * <p>
         * A redstone clock is periodic, so the number of distinct states is small no matter how long the
         * window runs; the number of transitions is what proves the contraption kept running instead of
         * settling into a stable state.
         */
        int redstoneTransitions() {
            final List<Long> series = redstoneSeries();
            int transitions = 0;
            for (int tick = 1; tick < series.size(); tick++) {
                if (!series.get(tick).equals(series.get(tick - 1))) {
                    transitions++;
                }
            }
            return transitions;
        }

        /** {@return the natural monsters of the level, i.e. the monsters the content did not spawn} */
        int naturalMonsterCount() {
            int count = 0;
            for (Entity entity : this.level.getEntities().getAll()) {
                if (!entity.isRemoved() && entity.getType().getCategory() == MobCategory.MONSTER
                        && !this.contentMonsters.contains(entity)) {
                    count++;
                }
            }
            return count;
        }

        /** {@return the number of live mobs of {@code category} in the level} */
        int mobCount(MobCategory category) {
            int count = 0;
            for (Entity entity : this.level.getEntities().getAll()) {
                if (!entity.isRemoved() && entity.getType().getCategory() == category) {
                    count++;
                }
            }
            return count;
        }

        int contentMobs() {
            return this.mobs.size();
        }

        int contentMonsterCount() {
            return this.contentMonsters.size();
        }

        List<Villager> villagers() {
            return this.villagers;
        }

        FakePlayer player() {
            return this.player;
        }

        int initialItemCount() {
            return this.initialItemCount;
        }

        int terrainWater() {
            return this.terrainWater;
        }

        int terrainLeaves() {
            return this.terrainLeaves;
        }

        int terrainCaveAir() {
            return this.terrainCaveAir;
        }

        int crops() {
            return this.crops;
        }

        int farms() {
            return this.farms;
        }

        int trees() {
            return this.trees;
        }

        int fences() {
            return this.fences;
        }

        /** {@return the wheat blocks of the farm that grew past age zero} */
        int grownCrops() {
            if (this.farmBaseY == Integer.MIN_VALUE) {
                return 0;
            }
            int grown = 0;
            final int x = chunkMinX(AREA_ORIGIN_CHUNK_X) + 1;
            final int z = chunkMinZ(AREA_ORIGIN_CHUNK_Z) + 1;
            for (int dx = 0; dx < 14; dx++) {
                for (int dz = 0; dz < 14; dz++) {
                    if (dz == 7) {
                        continue;
                    }
                    final BlockState state = this.level.getBlockState(new BlockPos(x + dx, this.farmBaseY + 1, z + dz));
                    if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) > 0) {
                        grown++;
                    }
                }
            }
            return grown;
        }

        void discard() {
            for (Mob mob : this.mobs) {
                if (!mob.isRemoved()) {
                    mob.discard();
                }
            }
            this.mobs.clear();
            if (this.player != null && !this.player.isRemoved()) {
                this.player.discard();
            }
            this.player = null;
        }

        private Container containerAt(BlockPos pos) {
            if (this.level.getBlockEntity(pos) instanceof Container container) {
                return container;
            }
            throw new IllegalStateException("the soak content at " + pos + " has no container");
        }

        private void fence(int minX, int minZ, int maxX, int maxZ, int y) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == minX || x == maxX || z == minZ || z == maxZ) {
                        this.level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
                        this.level.setBlockAndUpdate(new BlockPos(x, y + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState());
                    }
                }
            }
        }

        private void flatten(int minX, int minZ, int maxX, int maxZ, int y) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int dy = -2; dy <= 6; dy++) {
                        final BlockPos pos = new BlockPos(x, y + dy, z);
                        this.level.setBlockAndUpdate(pos, dy <= -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        /** {@return the block x of the interior (center) chunk {@code index} of the grid} */
        private static int interiorBlockX(int index) {
            return chunkMinX(AREA_ORIGIN_CHUNK_X + index * REGION_CHUNK_SIZE + 1) + 4;
        }

        /** {@return the block z of the interior (center) chunk {@code index} of the grid} */
        private static int interiorBlockZ(int index) {
            return chunkMinZ(AREA_ORIGIN_CHUNK_Z + index * REGION_CHUNK_SIZE + 1) + 4;
        }

        private static int chunkMinX(int chunkX) {
            return chunkX * 16;
        }

        private static int chunkMinZ(int chunkZ) {
            return chunkZ * 16;
        }
    }

    /** The window a report is written for. */
    private record SoakWindow(String profile, ServerLevel level, SoakWorld world, TickSampler sampler, SoakWatch watch,
            Optional<RegionThreadingDiagnostics> before, Optional<RegionThreadingDiagnostics> after,
            SoakSample start, SoakSample end, int measuredTicks) {}

    /**
     * Prints the report of the window: the tick distribution, the drift, every counter of the window, the
     * heap and engine structure samples, the entity counts, the redstone series and the invariants.
     *
     * @return the invariants the batch has to assert, printed before the caller fails on them
     */
    private static List<Invariant> report(SoakWindow window) {
        final ServerLevel level = window.level();
        final SoakWorld world = window.world();
        final long[] samples = window.sampler().samplesInOrder();
        final int lastCount = Math.min(600, samples.length);
        final TickStats whole = TickStats.of("whole", samples);
        final TickStats firstHalf = TickStats.of("firstHalf", Arrays.copyOfRange(samples, 0, samples.length / 2));
        final TickStats last = TickStats.of("last" + lastCount, Arrays.copyOfRange(samples, samples.length - lastCount, samples.length));
        final Optional<RegionThreadingDiagnostics> after = window.after();
        final long seed = level.getSeed();
        System.out.println(window.sampler().note(window.profile(), after)
                + " soakSeed=" + seed
                + " terrainWater=" + world.terrainWater()
                + " terrainLeaves=" + world.terrainLeaves()
                + " terrainCaveAir=" + world.terrainCaveAir()
                + " trees=" + world.trees()
                + " crops=" + world.crops()
                + " grownCrops=" + world.grownCrops()
                + " farms=" + world.farms()
                + " fences=" + world.fences()
                + " villagersWithBedAndWorkstation=" + villagersWithBedAndWorkstation(world.villagers()) + "/" + world.villagers().size()
                + " contentMobs=" + world.contentMobs()
                + " itemCount=" + world.itemCount() + "/" + world.initialItemCount()
                + " redstoneStates=" + world.redstoneStateCount()
                + " redstoneTransitions=" + world.redstoneTransitions()
                + " naturalMonsters=" + world.naturalMonsterCount()
                + " liveEntities=" + liveLevelEntities(level).size());
        System.out.println("SOAK_MSPT_HEADER " + MSPT_HEADER);
        for (TickStats stats : List.of(whole, firstHalf, last)) {
            System.out.println("SOAK_MSPT " + csv(window.profile(), Long.toString(seed), Integer.toString(window.measuredTicks()),
                    stats.window(), Integer.toString(stats.samples()), fixed(stats.avgMs()), fixed(stats.p50Ms()),
                    fixed(stats.p95Ms()), fixed(stats.p99Ms())));
        }
        final double driftRatio = firstHalf.avgMs() <= 0.0 ? -1.0 : last.avgMs() / firstHalf.avgMs();
        System.out.println("SOAK_DRIFT_HEADER " + DRIFT_HEADER);
        System.out.println("SOAK_DRIFT " + csv(window.profile(), fixed(firstHalf.avgMs()), fixed(last.avgMs()), fixed(driftRatio),
                fixed((driftRatio - 1.0) * 100.0), fixed(firstHalf.p99Ms()), fixed(last.p99Ms())));
        printCounters(window.profile(), window.before(), after, window.measuredTicks());
        System.out.println("SOAK_STRUCT_HEADER " + STRUCT_HEADER);
        for (SoakSample sample : window.watch().samples()) {
            System.out.println("SOAK_STRUCT " + csv(sample.point(), Integer.toString(sample.measuredTick()),
                    fixed(sample.heapUsedBytes() / 1048576.0), fixed(sample.heapCommittedBytes() / 1048576.0),
                    fixed(sample.heapMaxBytes() / 1048576.0), Long.toString(sample.gcCollections()),
                    Long.toString(sample.gcMillis()), Integer.toString(sample.regionCount()),
                    Integer.toString(sample.runningRegions()), Integer.toString(sample.trackedEntities()),
                    Integer.toString(sample.trackedEntityTasks()), Integer.toString(sample.sections()),
                    Integer.toString(sample.loadedChunks()), Integer.toString(sample.pendingDeferredMoves()),
                    Integer.toString(sample.pendingTransfers()), Integer.toString(sample.rememberedTransfers()),
                    Integer.toString(sample.activeBatches())));
        }
        printVillagers(world);
        System.out.println("SOAK_ENTITY_HEADER " + ENTITY_HEADER);
        for (MobCategory category : MobCategory.values()) {
            System.out.println("SOAK_ENTITY " + csv(category.getName(), Integer.toString(world.mobCount(category))));
        }
        System.out.println("SOAK_TICK_HEADER " + TICK_HEADER);
        System.out.println("SOAK_TICK " + csv(Integer.toString(window.watch().ticksChecked()),
                Integer.toString(window.watch().ticksWithActiveBatches()), Integer.toString(window.watch().maxActiveBatches()),
                Integer.toString(window.watch().itemChecks()), Integer.toString(window.watch().itemMismatches()),
                Integer.toString(window.watch().redstoneTicks()), Integer.toString(world.redstoneStateCount()),
                Integer.toString(world.redstoneTransitions()),
                Integer.toString(window.watch().mappingChecks()), Integer.toString(window.watch().mappingMismatches()),
                Integer.toString(window.watch().persistentMappingMismatches())));

        final List<Invariant> invariants = new ArrayList<>();
        invariants.add(Invariant.of("activeBatchesAfterEveryTick", window.watch().ticksWithActiveBatches(), 0));
        invariants.add(Invariant.of("itemConservationMismatches", window.watch().itemMismatches(), 0));
        invariants.add(Invariant.of("itemCountAtEnd", world.itemCount(), world.initialItemCount()));
        invariants.add(Invariant.of("regionMappingMismatchesThatSurvivedATick", window.watch().persistentMappingMismatches(), 0));
        invariants.add(Invariant.of("redstoneStatesObserved", world.redstoneStateCount() >= MIN_REDSTONE_STATES ? 1 : 0, 1));
        invariants.add(Invariant.of("redstoneTransitions", world.redstoneTransitions() >= window.measuredTicks() / MIN_REDSTONE_TRANSITIONS_PER_TICK ? 1 : 0, 1));
        invariants.add(Invariant.of("generatedTerrainIsNotFlat",
                world.terrainCaveAir() > 0 || world.terrainWater() > 0 || world.terrainLeaves() > 0 ? 1 : 0, 1));
        invariants.add(Invariant.of("grownCrops", world.grownCrops() > 0 ? 1 : 0, 1));
        invariants.add(Invariant.of("villagersWithBedAndWorkstation", villagersWithBedAndWorkstation(world.villagers()) > 0 ? 1 : 0, 1));
        invariants.add(Invariant.of("ownershipViolations", window.watch().ownershipViolations(), 0L));
        invariants.add(Invariant.of("tickFailures", window.watch().tickFailures(), 0L));
        invariants.add(Invariant.of("unboundedRegionGrowth",
                Math.max(0, window.end().regionCount() - window.start().regionCount() - REGION_COUNT_GROWTH_BOUND), 0));
        invariants.add(Invariant.of("unboundedEntityTaskGrowth",
                Math.max(0, window.end().trackedEntityTasks() - window.start().trackedEntityTasks() - ENTITY_TASK_GROWTH_BOUND), 0));
        invariants.add(Invariant.of("heapGrowthBeyondBoundMiB",
                Math.max(0L, window.end().heapUsedBytes() - window.start().heapUsedBytes() - HEAP_GROWTH_BOUND_BYTES) / 1048576L, 0L));
        invariants.add(Invariant.of("naturalMonsterCountWithinCap", naturalMonsterCountWithinCap(world) ? 1 : 0, 1));
        if (after.isEmpty()) {
            System.out.println("SOAK_INVARIANT_NOTE no region threading engine is installed in this profile: the worker and engine invariants need the threading-on run");
        } else {
            final RegionThreadingDiagnostics engine = after.get();
            final RegionThreadingDiagnostics start = window.before().orElseThrow();
            final RegionMappingCheck mapping = window.watch().engineState().checkRegionMapping(level,
                    RegionLayout.of(engine.regionPolicy(), engine.regionChunkSize()));
            final List<Entity> liveEntities = liveLevelEntities(level);
            final int sectionMismatches = regionMismatches(level, liveEntities);
            final int ledgerLive = level.entityIdentityLedger().liveCount();
            System.out.println("SOAK_MAPPING_HEADER mappedEntities,staleMappings,misplacedMappings,unmappedLiveEntities,dormantMappings");
            System.out.println("SOAK_MAPPING " + csv(Integer.toString(mapping.mappedEntities()), Integer.toString(mapping.staleMappings()),
                    Integer.toString(mapping.misplacedMappings()), Integer.toString(mapping.unmappedLiveEntities()),
                    Integer.toString(mapping.dormantMappings())));
            if (!"-".equals(mapping.firstProblem())) {
                System.out.println("SOAK_INVARIANT_DETAIL regionMapping " + mapping.firstProblem());
            }
            invariants.add(Invariant.of("regionMappingMatchesPosition", mapping.misplacedMappings(), 0));
            invariants.add(Invariant.of("staleRegionMappings", mapping.staleMappings(), 0));
            invariants.add(Invariant.of("unmappedLiveEntities", mapping.unmappedLiveEntities(), 0));
            invariants.add(Invariant.of("sectionMembershipMatchesPosition", sectionMismatches, 0));
            invariants.add(Invariant.of("identityLedgerLiveCountMatchesLevel", ledgerLive, liveEntities.size()));
            invariants.add(Invariant.of("entityTicksOnWorkers", engine.entityTicksOnWorkers() - start.entityTicksOnWorkers() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("blockEntityTicksOnWorkers", engine.blockEntityTicksOnWorkers() - start.blockEntityTicksOnWorkers() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("chunkTicksOnWorkers", engine.chunkTicksOnWorkers() - start.chunkTicksOnWorkers() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("spawnChunksOnWorkers", engine.spawnChunksOnWorkers() - start.spawnChunksOnWorkers() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("poiLocalQueries", engine.poiCounters().localQueries() - start.poiCounters().localQueries() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("borderUpdatesRecorded", engine.borderUpdates().recorded() - start.borderUpdates().recorded() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("borderUpdatesApplied", engine.borderUpdates().applied() - start.borderUpdates().applied() > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("borderUpdatesConserved",
                    (engine.borderUpdates().recorded() - start.borderUpdates().recorded())
                            - (engine.borderUpdates().applied() - start.borderUpdates().applied())
                            - (engine.borderUpdates().delayed() - start.borderUpdates().delayed())
                            - (engine.borderUpdates().dropped() - start.borderUpdates().dropped()),
                    0L));
            invariants.add(Invariant.of("foreignChunkReads", engine.borderUpdates().foreignReads() - start.borderUpdates().foreignReads() > 0 ? 1 : 0, 1));
            // The cross-region game event capture: a section column of a game event dispatch that belongs to
            // another region is replayed by that region or dropped and counted, so a captured column is never
            // lost. The soak world has no vibration listener, so a replayed column has nothing to deliver to;
            // the invariant is about the accounting, and the counters themselves are printed with the others.
            final long gameEventColumnsDeferred = engine.gameEvents().deferredSections() - start.gameEvents().deferredSections();
            final long gameEventColumnsDelivered = engine.gameEvents().deliveredSections() - start.gameEvents().deliveredSections();
            final long gameEventColumnsRefused = engine.gameEvents().refusedSections() - start.gameEvents().refusedSections();
            final long gameEventColumnsDropped = engine.gameEvents().droppedSections() - start.gameEvents().droppedSections();
            final long gameEventColumnsDroppedNoRegion = engine.gameEvents().droppedNoRegionSections()
                    - start.gameEvents().droppedNoRegionSections();
            final long gameEventColumnsDroppedNoChunk = engine.gameEvents().droppedNoChunkSections()
                    - start.gameEvents().droppedNoChunkSections();
            invariants.add(Invariant.of("gameEventColumnsLost",
                    gameEventColumnsDeferred - gameEventColumnsDelivered - gameEventColumnsRefused - gameEventColumnsDropped, 0L));
            invariants.add(Invariant.of("gameEventColumnsDropped", gameEventColumnsDropped, 0L));
            invariants.add(Invariant.of("gameEventColumnsDroppedNoRegion", gameEventColumnsDroppedNoRegion, 0L));
            invariants.add(Invariant.of("gameEventColumnsDroppedNoChunk", gameEventColumnsDroppedNoChunk, 0L));
            // The deferred cross-region block write: the relaxed admission rule hands boundary chunks to workers, and
            // every write of their bodies that lands in the neighbouring region is recorded here and applied by the
            // region that owns the position. A recorded write that was neither applied nor counted as dropped would
            // be a lost block change, so the difference is asserted.
            final long blockWritesRecorded = engine.borderUpdates().blockWritesRecorded() - start.borderUpdates().blockWritesRecorded();
            final long blockWritesApplied = engine.borderUpdates().blockWritesApplied() - start.borderUpdates().blockWritesApplied();
            final long blockWritesDropped = engine.borderUpdates().blockWritesDropped() - start.borderUpdates().blockWritesDropped();
            final long blockWritesOverflowed = engine.borderUpdates().blockWritesOverflowed() - start.borderUpdates().blockWritesOverflowed();
            invariants.add(Invariant.of("borderBlockWritesRecorded", blockWritesRecorded > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("borderBlockWritesApplied", blockWritesApplied > 0 ? 1 : 0, 1));
            invariants.add(Invariant.of("borderBlockWritesLost", blockWritesRecorded - blockWritesApplied - blockWritesDropped, 0L));
            invariants.add(Invariant.of("borderBlockWritesOverflowed", blockWritesOverflowed, 0L));
            printDispatchRatios(engine, start);
            invariants.add(Invariant.of("droppedDeferredEntityAdditions", engine.droppedDeferredEntityAdditions() - start.droppedDeferredEntityAdditions(), 0L));
            invariants.add(Invariant.of("droppedDeferredEntityRemovals", engine.droppedDeferredEntityRemovals() - start.droppedDeferredEntityRemovals(), 0L));
            // A section update of an entity that was removed between the move and the barrier has nothing left
            // to publish, so that subset is reported next to the asserted count instead of failing the soak.
            final long droppedSectionUpdates = engine.droppedDeferredEntitySectionUpdates() - start.droppedDeferredEntitySectionUpdates();
            final long droppedSectionUpdatesOfRemovedEntities = engine.droppedDeferredEntitySectionUpdatesForRemovedEntity()
                    - start.droppedDeferredEntitySectionUpdatesForRemovedEntity();
            invariants.add(Invariant.of("droppedDeferredEntitySectionUpdatesOfLiveEntities",
                    droppedSectionUpdates - droppedSectionUpdatesOfRemovedEntities, 0L));
            invariants.add(Invariant.of("mailboxDeliveryFailures", engine.mailboxDeliveryFailures() - start.mailboxDeliveryFailures(), 0L));
            invariants.add(Invariant.of("mailboxDroppedMessages",
                    (engine.mailboxDroppedRetiredMessages() - start.mailboxDroppedRetiredMessages())
                            + (engine.mailboxDroppedOnCloseMessages() - start.mailboxDroppedOnCloseMessages()),
                    0L));
        }
        // The first tick at which a continuous assertion fired is the most actionable part of a failed soak,
        // so it is printed with the invariants instead of only the end-state count.
        System.out.println("SOAK_INVARIANT_DETAIL firstItemMismatchTick=" + window.watch().firstItemMismatchTick()
                + " firstOwnershipViolationTick=" + window.watch().firstOwnershipViolationTick()
                + " firstTickFailureTick=" + window.watch().firstTickFailureTick());
        writeRedstoneSeries(window);
        System.out.println("SOAK_INVARIANT_HEADER " + INVARIANT_HEADER);
        for (Invariant invariant : invariants) {
            System.out.println("SOAK_INVARIANT " + csv(invariant.name(), invariant.observed().toString(),
                    invariant.expected().toString(), Boolean.toString(invariant.ok())));
        }
        return invariants;
    }

    /**
     * Writes the redstone state series of the window, and - in the reference run - compares it against the
     * series the threading-on run recorded at the same seed.
     * <p>
     * The comparison is the milestone's "redstone state equal to a reference run with region threading off at
     * the same seeds" assertion. The reference run passes the path of the threading-on series through
     * {@value #REFERENCE_PROPERTY}; the threading-on run writes its series under the report directory. A
     * reference run without a baseline fails, because then the comparison did not happen.
     */
    private static void writeRedstoneSeries(SoakWindow window) {
        final List<Long> series = window.world().redstoneSeries();
        final String reportDirectory = System.getProperty(REPORT_PROPERTY, "soak-report");
        final Path directory = Path.of(reportDirectory);
        final Path file = directory.resolve("redstone-" + window.profile() + ".csv");
        try {
            Files.createDirectories(directory);
            final StringBuilder builder = new StringBuilder("tick,fingerprint\n");
            for (int tick = 0; tick < series.size(); tick++) {
                builder.append(tick).append(',').append(series.get(tick)).append('\n');
            }
            Files.writeString(file, builder.toString());
        } catch (IOException exception) {
            throw new AssertionError("cannot write the soak redstone series to " + file, exception);
        }
        System.out.println("SOAK_REDSTONE_FILE profile=" + window.profile() + " path=" + file.toAbsolutePath() + " ticks=" + series.size());
        final String reference = System.getProperty(REFERENCE_PROPERTY, "");
        if (reference.isBlank()) {
            return;
        }
        final List<Long> expected = readRedstoneSeries(Path.of(reference));
        final int common = Math.min(expected.size(), series.size());
        int mismatches = 0;
        long firstMismatchTick = -1L;
        for (int tick = 0; tick < common; tick++) {
            if (!expected.get(tick).equals(series.get(tick))) {
                if (mismatches == 0) {
                    firstMismatchTick = tick;
                }
                mismatches++;
            }
        }
        System.out.println("SOAK_REDSTONE_COMPARE reference=" + reference
                + " referenceTicks=" + expected.size()
                + " measuredTicks=" + series.size()
                + " commonTicks=" + common
                + " mismatches=" + mismatches
                + " firstMismatchTick=" + firstMismatchTick);
        if (expected.isEmpty()) {
            throw new AssertionError("the reference redstone series " + reference + " is empty; run the threading-on soak first");
        }
        if (expected.size() != series.size()) {
            throw new AssertionError("the reference redstone series has " + expected.size()
                    + " ticks but this window has " + series.size() + "; both runs need the same window length");
        }
        if (mismatches != 0) {
            throw new AssertionError("the redstone state series differs from the reference run at " + mismatches
                    + " of " + common + " ticks, first at window tick " + firstMismatchTick);
        }
    }

    private static List<Long> readRedstoneSeries(Path file) {
        try {
            final List<String> lines = Files.readAllLines(file);
            final List<Long> series = new ArrayList<>(lines.size());
            for (int line = 1; line < lines.size(); line++) {
                final String value = lines.get(line).trim();
                if (!value.isEmpty()) {
                    series.add(Long.parseLong(value.substring(value.indexOf(',') + 1)));
                }
            }
            return series;
        } catch (IOException exception) {
            throw new AssertionError("cannot read the reference redstone series " + file, exception);
        }
    }

    /**
     * Prints every {@link RegionThreadingDiagnostics} counter of the window as one row: its value before the
     * window, after it, the delta and the delta per measured tick.
     */
    private static void printCounters(String profile, Optional<RegionThreadingDiagnostics> before,
            Optional<RegionThreadingDiagnostics> after, int measuredTicks) {
        System.out.println("SOAK_COUNTER_HEADER " + COUNTER_HEADER);
        for (RecordComponent component : RegionThreadingDiagnostics.class.getRecordComponents()) {
            final Object beforeValue = before.map(value -> counterValue(component, value)).orElse(null);
            final Object afterValue = after.map(value -> counterValue(component, value)).orElse(null);
            final boolean numeric = beforeValue instanceof Number && afterValue instanceof Number;
            final long delta = numeric ? ((Number) afterValue).longValue() - ((Number) beforeValue).longValue() : 0L;
            System.out.println("SOAK_COUNTER " + csv(profile, component.getName(), String.valueOf(beforeValue),
                    String.valueOf(afterValue), numeric ? Long.toString(delta) : "-",
                    numeric && measuredTicks > 0 ? fixed(delta / (double) measuredTicks) : "-"));
        }
    }

    private static Object counterValue(RecordComponent component, RegionThreadingDiagnostics diagnostics) {
        try {
            return component.getAccessor().invoke(diagnostics);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot read the diagnostics counter " + component.getName(), exception);
        }
    }

    /**
     * Prints the per-slice dispatch ratio of the window: how much of each slice ran on a region worker and how
     * much of it kept the server thread.
     * <p>
     * The chunk tick row is the measurement of the relaxed admission rule: {@code boundaryChunks} counts the
     * chunks whose neighbourhood crosses a region border and that the plan dispatched anyway, so the ratio can be
     * compared with a run of the interior-chunk rule without changing the counters.
     */
    private static void printDispatchRatios(RegionThreadingDiagnostics after, RegionThreadingDiagnostics before) {
        System.out.println("SOAK_DISPATCH_HEADER slice,onWorkers,fallbacks,dispatchedRatio,detail");
        printDispatchRatio("entityTick", after.entityTicksOnWorkers() - before.entityTicksOnWorkers(),
                after.fallbackEntityTicks() - before.fallbackEntityTicks(), "");
        printDispatchRatio("blockEntityTick", after.blockEntityTicksOnWorkers() - before.blockEntityTicksOnWorkers(),
                after.fallbackBlockEntityTicks() - before.fallbackBlockEntityTicks(),
                "nonInteriorChunks=" + (after.nonTickingBlockEntityChunks() - before.nonTickingBlockEntityChunks()));
        printDispatchRatio("chunkTick", after.chunkTicksOnWorkers() - before.chunkTicksOnWorkers(),
                after.fallbackChunkTicks() - before.fallbackChunkTicks(),
                "boundaryChunks=" + (after.boundaryChunkTicks() - before.boundaryChunkTicks())
                        + " unloadedNeighborhood=" + (after.unloadedNeighborhoodChunkTicks() - before.unloadedNeighborhoodChunkTicks())
                        + " truncated=" + (after.truncatedChunkTicks() - before.truncatedChunkTicks()));
        printDispatchRatio("spawnChunk", after.spawnChunksOnWorkers() - before.spawnChunksOnWorkers(),
                after.unleasedSpawnChunks() - before.unleasedSpawnChunks()
                        + after.lateSpawnChunks() - before.lateSpawnChunks()
                        + after.deferredUpdateBudgetSpawnChunks() - before.deferredUpdateBudgetSpawnChunks(),
                "");
        printDispatchRatio("scheduledTick", after.scheduledTicksOnWorkers() - before.scheduledTicksOnWorkers(),
                after.fallbackScheduledTicks() - before.fallbackScheduledTicks(), "");
        final long blockWritesRecorded = after.borderUpdates().blockWritesRecorded() - before.borderUpdates().blockWritesRecorded();
        final long blockWritesApplied = after.borderUpdates().blockWritesApplied() - before.borderUpdates().blockWritesApplied();
        System.out.println("SOAK_DISPATCH " + csv("borderBlockWrite",
                Long.toString(blockWritesApplied), Long.toString(after.borderUpdates().blockWritesDropped() - before.borderUpdates().blockWritesDropped()),
                fixed(blockWritesRecorded == 0L ? 1.0 : blockWritesApplied / (double) blockWritesRecorded),
                "recorded=" + blockWritesRecorded
                        + " overflowed=" + (after.borderUpdates().blockWritesOverflowed() - before.borderUpdates().blockWritesOverflowed())
                        + " readAfterWrite=" + (after.borderUpdates().readAfterBlockWrite() - before.borderUpdates().readAfterBlockWrite())));
    }

    private static void printDispatchRatio(String slice, long onWorkers, long fallbacks, String detail) {
        final long total = onWorkers + fallbacks;
        System.out.println("SOAK_DISPATCH " + csv(slice, Long.toString(onWorkers), Long.toString(fallbacks),
                fixed(total == 0L ? 1.0 : onWorkers / (double) total), detail));
    }

    /**
     * {@return true when the naturally spawned monster population is inside the vanilla mob cap}
     * <p>
     * Vanilla caps a category at {@code maxPerChunk * spawnableChunks / 289}. The soak does not know the
     * level's spawnable chunk count from the test, so it uses the grid size, which is an upper bound for it,
     * and allows the vanilla headroom of one spawn batch. The monsters the content spawned on purpose are not
     * part of the cap: vanilla never counts a mob that a command or a spawner placed, so the assertion counts
     * the monsters the spawning slice added.
     */
    private static boolean naturalMonsterCountWithinCap(SoakWorld world) {
        // The vanilla area term of the cap is the 17x17 chunks a player keeps spawnable, i.e. 289 chunks, and
        // the cap is maxInstancesPerChunk mobs per 289 chunks. That is the largest cap one player can have, so a
        // population above it cannot be explained by the area term. A spawn body reserves one slot and then
        // spawns its cluster, so the population can pass the cap by the size of one cluster; the bound allows
        // the largest vanilla monster cluster.
        final int spawnableChunks = 289;
        final int cap = MobCategory.MONSTER.getMaxInstancesPerChunk() * spawnableChunks / 289;
        final int bound = cap + 4;
        final int monsters = world.naturalMonsterCount();
        System.out.println("SOAK_CAP category=monster observed=" + monsters + " cap=" + cap + " bound=" + bound
                + " spawnableChunks=" + spawnableChunks + " contentMonsters=" + world.contentMonsterCount());
        return monsters <= bound;
    }

    /** Prints the POI state of every villager, so a window that never claims a job site says why. */
    private static void printVillagers(SoakWorld world) {
        System.out.println("SOAK_VILLAGER_HEADER index,removed,alive,position,home,jobSite,potentialJobSite,homePos,jobSitePos,potentialJobSitePos,profession,activity,sleeping,health");
        for (int index = 0; index < world.villagers().size(); index++) {
            final Villager villager = world.villagers().get(index);
            System.out.println("SOAK_VILLAGER " + csv(Integer.toString(index), Boolean.toString(villager.isRemoved()),
                    Boolean.toString(villager.isAlive()), villager.blockPosition().toString(),
                    Boolean.toString(villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent()),
                    Boolean.toString(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent()),
                    Boolean.toString(villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).isPresent()),
                    villager.getBrain().getMemory(MemoryModuleType.HOME).map(Object::toString).orElse("-"),
                    villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(Object::toString).orElse("-"),
                    villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).map(Object::toString).orElse("-"),
                    villager.getVillagerData().getProfession().toString(),
                    villager.getBrain().getActiveNonCoreActivity().map(Object::toString).orElse("NONE"),
                    Boolean.toString(villager.isSleeping()), fixed(villager.getHealth())));
        }
    }

    /** {@return the villagers that claimed both their bed and their workstation} */
    private static int villagersWithBedAndWorkstation(List<Villager> villagers) {
        int claimed = 0;
        for (Villager villager : villagers) {
            if (!villager.isRemoved()
                    && villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent()
                    && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent()) {
                claimed++;
            }
        }
        return claimed;
    }

    /** {@return the live entities the level exposes to its entity getter} */
    private static List<Entity> liveLevelEntities(ServerLevel level) {
        final List<Entity> entities = new ArrayList<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (!entity.isRemoved()) {
                entities.add(entity);
            }
        }
        return entities;
    }

    /**
     * {@return the entities whose section does not contain them}
     * <p>
     * A mismatch means the deferred section publication of a worker move was lost: the entity is live, but the
     * storage still maps its old position.
     */
    private static int regionMismatches(ServerLevel level, List<? extends Entity> tracked) {
        final EntitySectionStorage<Entity> storage = persistentSections(level);
        int mismatches = 0;
        for (Entity entity : tracked) {
            if (entity.isRemoved()) {
                continue;
            }
            final var section = storage.getSection(SectionPos.asLong(entity.blockPosition()));
            if (section == null || section.getEntities().noneMatch(candidate -> candidate == entity)) {
                mismatches++;
            }
        }
        return mismatches;
    }

    @SuppressWarnings("unchecked")
    private static EntitySectionStorage<Entity> persistentSections(ServerLevel level) {
        return (EntitySectionStorage<Entity>) storageField(storageField(level, "entityManager"), "sectionStorage");
    }

    private static void forceChunks(ServerLevel level, List<ChunkPos> grid) {
        for (ChunkPos chunk : grid) {
            level.setChunkForced(chunk.x, chunk.z, true);
            // A plain forced chunk keeps its block entities in the ticker list, but vanilla skips the chunk
            // tick without a nearby player. The force-ticking ticket makes the whole grid tick.
            level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, ENTITY_TICKING_TICKET_RADIUS, chunk, true);
        }
    }

    private static void releaseChunks(ServerLevel level, List<ChunkPos> grid) {
        for (ChunkPos chunk : grid) {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, ENTITY_TICKING_TICKET_RADIUS, chunk, true);
            level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    private static int intProperty(String property, int fallback) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(property + " must be an integer, got " + value, exception);
        }
    }

    private static Object storageField(Object target, String name) {
        try {
            final Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String csv(Object... values) {
        final StringBuilder builder = new StringBuilder();
        for (Object value : values) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /**
     * One assertion of the window: its name, the value the soak observed, the value it required, and the
     * verdict the batch fails on.
     */
    record Invariant(String name, Object observed, Object expected) {
        static Invariant of(String name, Object observed, Object expected) {
            return new Invariant(name, observed, expected);
        }

        boolean ok() {
            return this.observed.equals(this.expected);
        }
    }

    /** The tick time distribution of one window of the soak. */
    record TickStats(String window, int samples, double avgMs, double p50Ms, double p95Ms, double p99Ms) {
        static TickStats of(String window, long[] samples) {
            if (samples.length == 0) {
                return new TickStats(window, 0, -1.0, -1.0, -1.0, -1.0);
            }
            final long[] sorted = samples.clone();
            Arrays.sort(sorted);
            double sum = 0.0;
            for (long sample : samples) {
                sum += sample;
            }
            return new TickStats(window, samples.length, sum / samples.length / 1_000_000.0,
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99));
        }

        private static double percentile(long[] sorted, double quantile) {
            final int rank = Math.max(1, (int) Math.ceil(quantile * sorted.length));
            return sorted[Math.min(sorted.length, rank) - 1] / 1_000_000.0;
        }
    }

    /** One after-GC heap and engine structure sample of the window. */
    record SoakSample(String point, int measuredTick, long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
            long gcCollections, long gcMillis, int regionCount, int runningRegions, int trackedEntities,
            int trackedEntityTasks, int sections, int loadedChunks, int pendingDeferredMoves, int pendingTransfers,
            int rememberedTransfers, int activeBatches) {
        static SoakSample of(String point, int measuredTick, Optional<RegionThreadingDiagnostics> diagnostics) {
            final Runtime runtime = Runtime.getRuntime();
            // An after-GC sample is the only heap number the shared test JVM reports without a profiler.
            System.gc();
            final long used = runtime.totalMemory() - runtime.freeMemory();
            final long committed = runtime.totalMemory();
            final long max = runtime.maxMemory();
            long gcCollections = 0L;
            long gcMillis = 0L;
            for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCollections += Math.max(0L, collector.getCollectionCount());
                gcMillis += Math.max(0L, collector.getCollectionTime());
            }
            final SoakEngineState state = RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine
                    ? SoakEngineState.of(engine)
                    : null;
            final int activeBatches = state == null ? -1 : state.activeBatchCount();
            final int pendingDeferredMoves = state == null ? -1 : state.pendingDeferredMoveCount();
            if (diagnostics.isEmpty()) {
                return new SoakSample(point, measuredTick, used, committed, max, gcCollections, gcMillis,
                        -1, -1, -1, -1, -1, -1, -1, -1, -1, activeBatches);
            }
            final RegionThreadingDiagnostics value = diagnostics.get();
            return new SoakSample(point, measuredTick, used, committed, max, gcCollections, gcMillis,
                    value.regionCount(), value.runningRegionCount(), value.trackedEntityCount(),
                    value.trackedEntityTaskCount(), value.sectionCount(), value.loadedChunkCount(),
                    pendingDeferredMoves, value.pendingEntityTransferCount(),
                    value.rememberedEntityTransferResultCount(), activeBatches);
        }
    }

    /**
     * The tick sampler of the soak: it times the server tick body from {@link ServerTickEvent.Pre} to
     * {@link ServerTickEvent.Post} and keeps the measured window.
     */
    static final class TickSampler {
        private final int warmupTicks;
        private final long[] samples;
        private int tickIndex;
        private int sampleCount;
        private long tickStartNanos = -1L;
        private long windowStartNanos = -1L;
        private long windowEndNanos = -1L;
        private long firstGameTime = -1L;
        private long lastGameTime = -1L;

        TickSampler(int warmupTicks, int measuredTicks) {
            this.warmupTicks = warmupTicks;
            this.samples = new long[measuredTicks];
        }

        void startWindow(long gameTime) {
            this.windowStartNanos = System.nanoTime();
            this.firstGameTime = gameTime;
        }

        @SubscribeEvent
        public void onTickPre(ServerTickEvent.Pre event) {
            this.tickStartNanos = System.nanoTime();
        }

        @SubscribeEvent
        public void onTickPost(ServerTickEvent.Post event) {
            final long start = this.tickStartNanos;
            this.tickStartNanos = -1L;
            if (start < 0L) {
                return;
            }
            final long duration = System.nanoTime() - start;
            final int index = this.tickIndex++;
            if (index >= this.warmupTicks && this.sampleCount < this.samples.length) {
                this.samples[this.sampleCount++] = duration;
            }
            if (index >= this.warmupTicks + this.samples.length) {
                this.windowEndNanos = System.nanoTime();
                this.lastGameTime = event.getServer().overworld().getGameTime();
            }
        }

        long[] samplesInOrder() {
            return Arrays.copyOf(this.samples, this.sampleCount);
        }

        /** {@return the one-line measurement metadata of the run} */
        String note(String profile, Optional<RegionThreadingDiagnostics> diagnostics) {
            final long windowNanos = this.windowStartNanos < 0L || this.windowEndNanos < 0L ? -1L : this.windowEndNanos - this.windowStartNanos;
            final double tickMillis = windowNanos <= 0L || this.sampleCount == 0 ? -1.0 : windowNanos / 1_000_000.0 / this.sampleCount;
            return "SOAK_NOTE profile=" + profile
                    + " warmupTicks=" + this.warmupTicks
                    + " measuredTicks=" + this.samples.length
                    + " samples=" + this.sampleCount
                    + " windowMs=" + (windowNanos < 0L ? -1L : windowNanos / 1_000_000L)
                    + " tickMs=" + fixed(tickMillis)
                    + " gameTimeDelta=" + (this.firstGameTime < 0L || this.lastGameTime < 0L ? -1L : this.lastGameTime - this.firstGameTime)
                    + " workerThreads=" + diagnostics.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0)
                    + " entityStorageMode=" + diagnostics.map(RegionThreadingDiagnostics::entityStorageMode).map(Object::toString).orElse("NONE")
                    + " entityWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::entityTickWorkerExecutionEnabled).orElse(false)
                    + " blockEntityWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::blockEntityTickWorkerExecutionEnabled).orElse(false)
                    + " chunkTickWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::chunkTickWorkerExecutionEnabled).orElse(false)
                    + " spawnWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::spawnWorkerExecutionEnabled).orElse(false)
                    + " scheduledTickWorkerExecutionRequested=" + diagnostics.map(RegionThreadingDiagnostics::scheduledTickWorkerExecutionEnabled).orElse(false)
                    + " scheduledTickWorkerDispatchEffective=" + diagnostics.map(RegionThreadingDiagnostics::scheduledTickWorkerDispatchEffective).orElse(false)
                    + " regionPolicy=" + diagnostics.map(RegionThreadingDiagnostics::regionPolicy).map(Object::toString).orElse("NONE");
        }
    }

    /**
     * The continuous assertions of the soak.
     * <p>
     * Every tick it checks the things a broken tick leaves behind: an unfinished batch, a lost item of the
     * hopper line, and the region mapping of a rotating slice of the population. Every
     * {@value #COUNTER_CHECK_TICKS} ticks it reads the engine counters and records the first tick at which an
     * ownership violation or a tick failure appeared, so the failure is reported with its window tick instead
     * of only at the end.
     */
    static final class SoakWatch {
        private final ServerLevel level;
        private final SoakWorld world;
        private final int measuredTicks;
        private final List<SoakSample> samples = new ArrayList<>(3);
        private final List<Entity> mappingCandidates = new ArrayList<>();
        private SoakEngineState engineState;
        private RegionLayout layout;
        private int tick;
        private int ticksChecked;
        private int ticksWithActiveBatches;
        private int maxActiveBatches;
        private int itemChecks;
        private int itemMismatches;
        private int mappingChecks;
        private int mappingMismatches;
        private int mappingCursor;
        /**
         * Entities a check found outside their mapped region. A mapping is published by the entity's own tick
         * with the region the entity had when that tick started, so a move that crosses a region border leaves
         * the mapping one tick behind by design. A mismatch is therefore re-checked on the next tick and only
         * counted as a failure when it survived that tick; that is the property the region index needs, because
         * an entity whose mapping never catches up would be grouped and leased by the wrong region forever.
         */
        private final List<Entity> pendingMappingChecks = new ArrayList<>();
        private int persistentMappingMismatches;
        private int mappingMismatchDetails;
        private int redstoneTicks;
        private long firstItemMismatchTick = -1L;
        private long firstOwnershipViolationTick = -1L;
        private long firstTickFailureTick = -1L;
        private long ownershipViolations;
        private long tickFailures;
        private long lastOwnershipViolations;
        private long lastTickFailures;

        SoakWatch(ServerLevel level, SoakWorld world, int measuredTicks) {
            this.level = level;
            this.world = world;
            this.measuredTicks = measuredTicks;
        }

        void install() {
            if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
                this.engineState = SoakEngineState.of(engine);
                this.layout = engine.regionLayout();
            }
        }

        void startWindow() {
            // The first half of the window is noon: the villagers claim their bed and their workstation and
            // the crops of the farm grow on the random ticks of the chunk slice. The second half is midnight:
            // the monsters of the natural spawning slice spawn on the surface and the villagers sleep. Both
            // halves are needed, because a sleeping villager never walks to a potential job site and a crop
            // does not grow in the dark.
            this.level.setDayTime(1000L);
            this.samples.add(SoakSample.of("start", 0, RegionThreadingHooks.diagnostics()));
            this.mappingCandidates.clear();
            this.mappingCandidates.addAll(liveLevelEntities(this.level));
        }

        void sampleMiddle() {
            printVillagers(this.world);
            this.samples.add(SoakSample.of("middle", this.measuredTicks / 2, RegionThreadingHooks.diagnostics()));
        }

        /**
         * Turns the window from day to night.
         * <p>
         * The day covers three quarters of the measured window and the night the last quarter. A villager needs
         * up to the vanilla {@code GoToPotentialJobSite} timeout of 1200 ticks to walk to its workstation, and a
         * crop only grows in the light, so the day has to be long; the night is what makes the natural spawning
         * slice spawn on the surface.
         */
        void startNight() {
            this.level.setDayTime(18000L);
        }

        SoakSample sampleEnd() {
            final SoakSample end = SoakSample.of("end", this.measuredTicks, RegionThreadingHooks.diagnostics());
            this.samples.add(end);
            return end;
        }

        @SubscribeEvent
        public void onTickPost(ServerTickEvent.Post event) {
            this.tick++;
            if (this.engineState != null) {
                final int active = this.engineState.activeBatchCount();
                this.ticksChecked++;
                if (active > 0) {
                    this.ticksWithActiveBatches++;
                    this.maxActiveBatches = Math.max(this.maxActiveBatches, active);
                }
            }
            // Item conservation: the hopper line moves items between five containers, and the lava bucket
            // fuel returns an empty bucket, so the total count is constant at every tick.
            this.itemChecks++;
            final int items = this.world.itemCount();
            if (items != this.world.initialItemCount()) {
                this.itemMismatches++;
                if (this.firstItemMismatchTick < 0L) {
                    this.firstItemMismatchTick = this.tick;
                    System.out.println("SOAK_ITEM_MISMATCH tick=" + this.tick + " observed=" + items + " expected=" + this.world.initialItemCount());
                }
            }
            this.world.recordRedstoneState();
            this.redstoneTicks++;
            // The region mapping check. A mismatch of the previous tick is re-checked first: it has to be
            // repaired by the tick that just ran. Then a handful of entities of the rotating sample are
            // checked, so the whole population is covered every few ticks without scanning it on every tick.
            if (this.engineState != null && this.layout != null) {
                for (int index = this.pendingMappingChecks.size() - 1; index >= 0; index--) {
                    final Entity entity = this.pendingMappingChecks.get(index);
                    this.mappingChecks++;
                    if (entity.isRemoved() || !this.level.isPositionEntityTicking(entity.blockPosition())) {
                        this.pendingMappingChecks.remove(index);
                        continue;
                    }
                    if (this.engineState.mapsToItsRegion(this.level, this.layout, entity)) {
                        this.pendingMappingChecks.remove(index);
                        continue;
                    }
                    this.persistentMappingMismatches++;
                    this.pendingMappingChecks.remove(index);
                    if (this.mappingMismatchDetails < 3) {
                        this.mappingMismatchDetails++;
                        System.out.println("SOAK_MAPPING_MISMATCH tick=" + this.tick + " " + this.engineState.describeMapping(this.level, this.layout, entity));
                    }
                }
                final int checks = Math.min(8, this.mappingCandidates.size());
                for (int i = 0; i < checks; i++) {
                    final Entity entity = this.mappingCandidates.get(this.mappingCursor++ % this.mappingCandidates.size());
                    this.mappingChecks++;
                    if (entity.isRemoved() || !this.level.isPositionEntityTicking(entity.blockPosition())) {
                        continue;
                    }
                    if (!this.engineState.mapsToItsRegion(this.level, this.layout, entity)) {
                        this.mappingMismatches++;
                        if (!this.pendingMappingChecks.contains(entity)) {
                            this.pendingMappingChecks.add(entity);
                        }
                    }
                }
            }
            if (this.tick % TRAMPLE_INTERVAL_TICKS == 0) {
                this.world.restoreTrampleFixture();
            }
            if (this.tick % PLAYER_MOVE_INTERVAL_TICKS == 0) {
                this.world.movePlayer();
            }
            if (this.tick % COUNTER_CHECK_TICKS == 0) {
                final Optional<RegionThreadingDiagnostics> diagnostics = RegionThreadingHooks.diagnostics();
                if (diagnostics.isPresent()) {
                    final RegionThreadingDiagnostics value = diagnostics.get();
                    this.ownershipViolations = value.ownershipViolationCount();
                    this.tickFailures = tickFailureCount(value);
                    if (this.ownershipViolations > this.lastOwnershipViolations && this.firstOwnershipViolationTick < 0L) {
                        this.firstOwnershipViolationTick = this.tick;
                    }
                    if (this.tickFailures > this.lastTickFailures && this.firstTickFailureTick < 0L) {
                        this.firstTickFailureTick = this.tick;
                    }
                    this.lastOwnershipViolations = this.ownershipViolations;
                    this.lastTickFailures = this.tickFailures;
                }
            }
        }

        int ticksChecked() {
            return this.ticksChecked;
        }

        int ticksWithActiveBatches() {
            return this.ticksWithActiveBatches;
        }

        int maxActiveBatches() {
            return this.maxActiveBatches;
        }

        int itemChecks() {
            return this.itemChecks;
        }

        int itemMismatches() {
            return this.itemMismatches;
        }

        int mappingChecks() {
            return this.mappingChecks;
        }

        int mappingMismatches() {
            return this.mappingMismatches;
        }

        int persistentMappingMismatches() {
            return this.persistentMappingMismatches;
        }

        int redstoneTicks() {
            return this.redstoneTicks;
        }

        long ownershipViolations() {
            return this.ownershipViolations;
        }

        long tickFailures() {
            return this.tickFailures;
        }

        List<SoakSample> samples() {
            return this.samples;
        }

        SoakEngineState engineState() {
            return this.engineState;
        }

        long firstItemMismatchTick() {
            return this.firstItemMismatchTick;
        }

        long firstOwnershipViolationTick() {
            return this.firstOwnershipViolationTick;
        }

        long firstTickFailureTick() {
            return this.firstTickFailureTick;
        }

        static long tickFailureCount(RegionThreadingDiagnostics diagnostics) {
            long failures = 0L;
            for (RegionTickSlice slice : RegionTickSlice.values()) {
                failures += diagnostics.tickFailures().recorded(slice);
            }
            return failures;
        }
    }

    /**
     * The heartbeat of the window: every {@value #HEARTBEAT_TICKS} ticks it prints the tick time, the per-region
     * p99, every slice counter, the border and stale-read counters, the item count and the heap, so a long run
     * is observable while it happens and a drifting counter is visible before the end.
     */
    static final class Heartbeat {
        private final ServerLevel level;
        private final SoakWorld world;
        private final Optional<RegionThreadingDiagnostics> start;
        private final String profile;
        private long tickStartNanos = -1L;
        private long windowMsptSum;
        private int windowMsptCount;
        private int windowStartTick;
        private int ticks;

        Heartbeat(ServerLevel level, SoakWorld world, Optional<RegionThreadingDiagnostics> start, String profile) {
            this.level = level;
            this.world = world;
            this.start = start;
            this.profile = profile;
        }

        void startWindow() {
            this.windowStartTick = this.ticks;
            System.out.println("SOAK_HEARTBEAT_HEADER " + HEARTBEAT_HEADER);
        }

        void sampleMiddle() {
            // The middle sample of the engine structures is taken by the watch; the heartbeat only prints.
        }

        @SubscribeEvent
        public void onTickPre(ServerTickEvent.Pre event) {
            this.tickStartNanos = System.nanoTime();
        }

        @SubscribeEvent
        public void onTickPost(ServerTickEvent.Post event) {
            final long startNanos = this.tickStartNanos;
            this.tickStartNanos = -1L;
            if (startNanos >= 0L) {
                this.windowMsptSum += System.nanoTime() - startNanos;
                this.windowMsptCount++;
            }
            this.ticks++;
            final long windowTick = this.ticks - this.windowStartTick;
            if (windowTick % HEARTBEAT_TICKS != 0) {
                return;
            }
            final double mspt = this.windowMsptCount == 0 ? -1.0 : this.windowMsptSum / 1_000_000.0 / this.windowMsptCount;
            this.windowMsptSum = 0L;
            this.windowMsptCount = 0;
            final Optional<RegionThreadingDiagnostics> current = RegionThreadingHooks.diagnostics();
            final Runtime runtime = Runtime.getRuntime();
            final long heapMiB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
            if (current.isEmpty()) {
                System.out.println("SOAK_HEARTBEAT " + csv(Long.toString(this.level.getGameTime()), Long.toString(windowTick),
                        fixed(mspt), "-1", "-1", "-1", "-1", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0",
                        "0", "0", Long.toString(this.world.itemCount()),
                        Integer.toString(liveLevelEntities(this.level).size()),
                        Integer.toString(this.world.contentMobs()), Long.toString(heapMiB), "0", "0", "0", "0", "0", "0", "0"));
                return;
            }
            final RegionThreadingDiagnostics value = current.get();
            final RegionThreadingDiagnostics before = this.start.orElse(value);
            double p99Sum = 0.0;
            double p99Max = 0.0;
            int regions = 0;
            for (RegionTickSnapshot region : value.regionTicks()) {
                if (region.hasSamples()) {
                    p99Sum += region.msptP99();
                    p99Max = Math.max(p99Max, region.msptP99());
                    regions++;
                }
            }
            System.out.println("SOAK_HEARTBEAT " + csv(Long.toString(this.level.getGameTime()), Long.toString(windowTick),
                    fixed(mspt),
                    Integer.toString(value.regionCount()),
                    Integer.toString(value.regionMetrics().regionsBehind()),
                    fixed(regions == 0 ? -1.0 : p99Sum / regions),
                    fixed(p99Max),
                    Long.toString(value.entityTicksOnWorkers() - before.entityTicksOnWorkers()),
                    Long.toString(value.blockEntityTicksOnWorkers() - before.blockEntityTicksOnWorkers()),
                    Long.toString(value.chunkTicksOnWorkers() - before.chunkTicksOnWorkers()),
                    Long.toString(value.spawnChunksOnWorkers() - before.spawnChunksOnWorkers()),
                    Long.toString(value.poiCounters().localQueries() - before.poiCounters().localQueries()),
                    Long.toString(value.poiCounters().publishedQueries() - before.poiCounters().publishedQueries()),
                    Long.toString(value.borderUpdates().recorded() - before.borderUpdates().recorded()),
                    Long.toString(value.borderUpdates().applied() - before.borderUpdates().applied()),
                    Long.toString(value.borderUpdates().delayed() - before.borderUpdates().delayed()),
                    Long.toString(value.borderUpdates().foreignReads() - before.borderUpdates().foreignReads()),
                    Long.toString(value.borderUpdates().invalidatedPasses() - before.borderUpdates().invalidatedPasses()),
                    Long.toString(SoakWatch.tickFailureCount(value)),
                    Long.toString(value.ownershipViolationCount()),
                    Long.toString(this.world.itemCount()),
                    Integer.toString(liveLevelEntities(this.level).size()),
                    Integer.toString(this.world.contentMobs()),
                    Long.toString(heapMiB),
                    Long.toString(net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.foreignWriteRefusalCount()),
                    Long.toString(net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.unstableReadRefusalCount()),
                    Long.toString(RegionThreadingHooks.rawChunkInternalsAccessCount()),
                    Long.toString(value.boundaryChunkTicks() - before.boundaryChunkTicks()),
                    Long.toString(value.borderUpdates().blockWritesRecorded() - before.borderUpdates().blockWritesRecorded()),
                    Long.toString(value.borderUpdates().blockWritesApplied() - before.borderUpdates().blockWritesApplied()),
                    Long.toString(value.borderUpdates().readAfterBlockWrite() - before.borderUpdates().readAfterBlockWrite())));
        }
    }

    /**
     * Reads the engine's private structure state a soak cannot get from the diagnostics: the active tick
     * batches a finished tick must never leave behind, the pending deferred ownership updates, and the
     * regionizer's entity mapping.
     */
    static final class SoakEngineState {
        private final RegionThreadingEngine engine;
        private final Field regionizer;
        private final Field regionizerEntities;
        private final Field regionStateCoordinate;
        private final Field activeTickBatches;
        private final Field pendingDeferredEntityMoves;

        static SoakEngineState of(RegionThreadingEngine engine) {
            return new SoakEngineState(engine);
        }

        private SoakEngineState(RegionThreadingEngine engine) {
            this.engine = engine;
            this.regionizer = declaredField(RegionThreadingEngine.class, "regionizer");
            this.regionizerEntities = declaredField(this.regionizer.getType(), "entities");
            this.regionStateCoordinate = declaredField(regionStateClass(), "coordinate");
            this.activeTickBatches = declaredField(RegionThreadingEngine.class, "activeTickBatches");
            this.pendingDeferredEntityMoves = declaredField(RegionThreadingEngine.class, "pendingDeferredEntityMoves");
        }

        int activeBatchCount() {
            return ((java.util.Collection<?>) fieldValue(this.activeTickBatches, this.engine)).size();
        }

        int pendingDeferredMoveCount() {
            return ((java.util.Collection<?>) fieldValue(this.pendingDeferredEntityMoves, this.engine)).size();
        }

        /**
         * {@return true when the regionizer maps {@code entity} to the region of its block position}
         * <p>
         * Only an entity the level still ticks is checked. The mapping of an entity is published when the
         * level starts ticking it and is maintained by the entity tick slice while it ticks; an entity that
         * stopped ticking keeps the region it last ticked in, because its region index entry was retired by
         * the same stop and nothing can observe the difference. A mapping that is misplaced while the entity
         * is ticking is the real failure: the entity would be grouped into, and leased by, a region that does
         * not contain it.
         */
        boolean mapsToItsRegion(ServerLevel level, RegionLayout layout, Entity entity) {
            if (!level.isPositionEntityTicking(entity.blockPosition())) {
                return true;
            }
            final Object region = entityMapping().get(entity);
            if (region == null) {
                return false;
            }
            final RegionCoordinate mapped = (RegionCoordinate) fieldValue(this.regionStateCoordinate, region);
            final RegionCoordinate expected = layout.regionAt(level, entity.blockPosition());
            return mapped.regionX() == expected.regionX() && mapped.regionZ() == expected.regionZ();
        }

        /**
         * {@return the state of the regionizer entity mapping of {@code level}}
         * <p>
         * A mapping is misplaced when the region it names is not the region of the block position of its
         * entity, which is what a lost or stale ownership handoff leaves behind. A stale mapping names an
         * entity that is already removed. An unmapped live entity is a level entity the regionizer never
         * learned about.
         */
        RegionMappingCheck checkRegionMapping(ServerLevel level, RegionLayout layout) {
            final Map<?, ?> mapping = entityMapping();
            int stale = 0;
            int misplaced = 0;
            int dormant = 0;
            int mapped = 0;
            String firstProblem = "-";
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                final Entity entity = (Entity) entry.getKey();
                if (entity.isRemoved()) {
                    stale++;
                    if ("-".equals(firstProblem)) {
                        firstProblem = "removed entity still mapped at " + entity.blockPosition();
                    }
                    continue;
                }
                mapped++;
                final RegionCoordinate mappedCoordinate = (RegionCoordinate) fieldValue(this.regionStateCoordinate, entry.getValue());
                final RegionCoordinate expected = layout.regionAt(level, entity.blockPosition());
                if (mappedCoordinate.regionX() == expected.regionX() && mappedCoordinate.regionZ() == expected.regionZ()) {
                    continue;
                }
                final boolean ticking = level.isPositionEntityTicking(entity.blockPosition());
                if (!ticking) {
                    // The entity stopped ticking in a chunk that is not entity-ticking any more. Its region
                    // index entry was retired by the same stop, so the stale mapping cannot route a tick; it is
                    // reported, not asserted.
                    dormant++;
                    continue;
                }
                misplaced++;
                if ("-".equals(firstProblem)) {
                    firstProblem = entity.getType() + " at " + entity.blockPosition()
                            + " (addedToLevel=" + entity.isAddedToLevel() + ", ticking=" + ticking + ")"
                            + " mapped to " + mappedCoordinate.regionX() + "/" + mappedCoordinate.regionZ()
                            + " but belongs to " + expected.regionX() + "/" + expected.regionZ();
                }
            }
            int unmapped = 0;
            for (Entity entity : liveLevelEntities(level)) {
                if (!mapping.containsKey(entity)) {
                    unmapped++;
                    if ("-".equals(firstProblem)) {
                        firstProblem = "live entity without a mapping at " + entity.blockPosition();
                    }
                }
            }
            return new RegionMappingCheck(mapped, stale, misplaced, unmapped, dormant, firstProblem);
        }

        /** {@return a one-line description of a misplaced mapping, for the failure message} */
        String describeMapping(ServerLevel level, RegionLayout layout, Entity entity) {
            final Object region = entityMapping().get(entity);
            if (region == null) {
                return entity.getType() + " at " + entity.blockPosition() + " has no mapping";
            }
            final RegionCoordinate mapped = (RegionCoordinate) fieldValue(this.regionStateCoordinate, region);
            final RegionCoordinate expected = layout.regionAt(level, entity.blockPosition());
            return entity.getType() + " at " + entity.blockPosition()
                    + " mapped to " + mapped.regionX() + "/" + mapped.regionZ()
                    + " but belongs to " + expected.regionX() + "/" + expected.regionZ();
        }

        private Map<?, ?> entityMapping() {
            return (Map<?, ?>) fieldValue(this.regionizerEntities, fieldValue(this.regionizer, this.engine));
        }

        private static Class<?> regionStateClass() {
            try {
                return Class.forName("net.neoforged.neoforge.server.threading.region.RegionState");
            } catch (ClassNotFoundException exception) {
                throw new AssertionError(exception);
            }
        }

        private static Field declaredField(Class<?> type, String name) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("cannot read " + type.getName() + "." + name, exception);
            }
        }

        private static Object fieldValue(Field field, Object target) {
            try {
                return field.get(target);
            } catch (IllegalAccessException exception) {
                throw new AssertionError("cannot read " + field, exception);
            }
        }
    }

    /**
     * The region mapping state of one level at one point of the window.
     *
     * @param dormantMappings misplaced mappings of entities the level no longer ticks; they are reported next
     *                        to the asserted {@code misplacedMappings} so the difference stays visible
     */
    record RegionMappingCheck(int mappedEntities, int staleMappings, int misplacedMappings, int unmappedLiveEntities,
            int dormantMappings, String firstProblem) {}
}
