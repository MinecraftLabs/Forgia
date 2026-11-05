/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionBlockEventAttribution;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionMetricsReport;
import net.neoforged.neoforge.server.threading.region.RegionSpawnAttribution;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Measures dedicated server tick time under two controlled loads and reports each as one CSV line.
 * <p>
 * Both workloads force-load a 16x16 chunk grid around the test structure, run {@value #WARMUP_TICKS}
 * warmup ticks and then sample {@value #MEASURED_TICKS} ticks of the server tick body from
 * {@link ServerTickEvent.Pre} to {@link ServerTickEvent.Post}. The reported values are the average and the
 * nearest-rank p50, p95 and p99 of those samples; the counters are the deltas of
 * {@link RegionThreadingHooks#diagnostics()} over the same window.
 * <p>
 * The furnace test also enables {@link RegionBlockEntityTickAttribution} for its window and reports the
 * per-tick wall time of the server-thread block-entity slice segments: the plan collection, the region lease
 * acquisition, the wait for the worker batch, the deferred work after the barrier and the whole slice. The
 * entity worker test enables the equivalent {@link RegionEntityTickAttribution} for its window and reports
 * the same segments of the server-thread entity slice. Both attributions are disabled again when their
 * window ends, so the other batches stay uninstrumented.
 * <p>
 * The furnace workload places {@value #FURNACE_COUNT} lit furnaces by default, or as many as the
 * {@value #FURNACE_COUNT_PROPERTY} property asks for; they smelt and burn for the whole window, so every
 * furnace ticks every tick. The furnaces sit in region-interior chunks so the
 * block-entity worker slice can lease their regions: a chunk whose coordinates are {@code 1} modulo 3 is
 * the center chunk of a 3x3 region and an interior chunk of a 9x9 region, so the placement rule does not
 * depend on the installed layout and every measured profile ticks the same furnaces at the same absolute
 * positions.
 * <p>
 * The entity workload spawns {@value #ZOMBIE_COUNT} zombies with their vanilla AI and
 * {@value #ITEM_COUNT} item entities across the same grid, pushes the items every
 * {@value #ITEM_PUSH_INTERVAL_TICKS} ticks so they keep crossing chunk boundaries, and replaces dead
 * zombies so the population stays constant. Entity ticks are not parallel in this build (production keeps
 * entity storage on the server thread), so this workload measures the region threading overhead on a
 * server-thread-bound entity load and checks the entity section invariants, not a speedup.
 * <p>
 * The tests only measure: they pass as long as the workload ran and never assert a duration. The one
 * exception is the soak test, which asserts the end-state invariants of its window and therefore fails the
 * batch when the entity worker path left a batch, a mapping or a ledger entry behind. A run without
 * the {@value #ENABLED_PROPERTY} system property does no work, so the normal GameTest batches stay as fast
 * as before; the measurement script sets it through {@code -PserverBenchmark=true} and greps the
 * {@code SERVER_BENCH_} and {@code SERVER_SOAK_} lines from the log.
 */
@ForEachTest(groups = "level.serverbench")
public class ServerLoadBenchmarkGameTests {
    /**
     * System property of the measurement run. Without it the tests return immediately.
     */
    static final String ENABLED_PROPERTY = "neoforge.serverBenchmark";
    /** Edge length of the force-loaded chunk grid. */
    private static final int GRID_CHUNKS = 16;
    /** Ticks discarded before sampling so the load reached its steady state. */
    private static final int WARMUP_TICKS = 200;
    /** Ticks sampled and reported. */
    private static final int MEASURED_TICKS = 600;
    /** Regions the measured window reports per-region MSPT and TPS for, worst p99 first. */
    private static final int REGION_METRIC_SAMPLE_LIMIT = 5;
    /**
     * Furnaces of the block entity workload when {@value #FURNACE_COUNT_PROPERTY} is unset.
     */
    private static final int FURNACE_COUNT = 4096;
    /**
     * System property that overrides {@link #FURNACE_COUNT}, so the size of the furnace workload can be
     * varied without editing the test. {@code 0} places no furnace at all: the block-entity slice then has
     * no ticker to admit and reports its server-thread segments without a worker batch.
     */
    static final String FURNACE_COUNT_PROPERTY = "neoforge.serverBenchmarkFurnaces";
    /**
     * Chunk residue of a furnace column. Residue 1 is the center chunk of a 3x3 region and an interior
     * chunk of a 9x9 region, so a furnace in such a column is admissible to the block-entity worker slice
     * for every measured fixed region size.
     */
    private static final int INTERIOR_CHUNK_RESIDUE = 1;
    /**
     * Hoppers of the hopper workload when {@value #HOPPER_COUNT_PROPERTY} is unset. Every hopper of the
     * workload has a source chest above it and a destination chest below it, so one column is one
     * container-to-container transfer that the vanilla hopper rate spreads over eight ticks.
     */
    private static final int HOPPER_COUNT = 2048;
    /**
     * System property that overrides {@link #HOPPER_COUNT}, so the size of the hopper workload can be varied
     * without editing the test. {@code 0} places no hopper at all.
     */
    static final String HOPPER_COUNT_PROPERTY = "neoforge.serverBenchmarkHoppers";
    /**
     * System property that turns the per-chunk block-entity admission cache of the installed engine off for a
     * hopper measurement run, so the same workload can be measured with and without the cache. A run without
     * the property keeps the cache enabled, which is the production path.
     */
    static final String BLOCK_ENTITY_ADMISSION_CACHE_PROPERTY = "neoforge.serverBenchmarkBlockEntityAdmissionCache";
    /**
     * Chunks of the natural spawning workload when {@value #SPAWN_CHUNKS_PROPERTY} is unset.
     * <p>
     * The default is the full 17x17-chunk square of the vanilla spawn distance: vanilla computes its mob cap from
     * every chunk a player is close enough to for spawning, loaded or not, so a smaller platform would make the
     * level-wide cap larger than the sum of the region caps and the off/on comparison would compare two different
     * areas instead of two cap scopes.
     */
    private static final int SPAWN_CHUNKS = 17 * 17;
    /**
     * System property that overrides {@link #SPAWN_CHUNKS}, so the size of the spawning workload can be varied
     * without editing the test. The grid is the smallest square that holds the requested count.
     */
    static final String SPAWN_CHUNKS_PROPERTY = "neoforge.serverBenchmarkSpawnChunks";
    /** Height of the spawning platform floor, far above the generated terrain of the test level. */
    private static final int SPAWN_PLATFORM_HEIGHT = 200;
    /** Air blocks between the spawning floor and its roof, so a mob fits under the roof. */
    private static final int SPAWN_PLATFORM_HEADROOM = 3;
    /** Ticks the spawning platform is left alone before the measured window, so the light of it settles. */
    private static final int SPAWN_PLATFORM_SETTLE_TICKS = 40;
    /**
     * Chunks of the random tick workload when {@value #RANDOM_TICK_CHUNKS_PROPERTY} is unset.
     */
    private static final int RANDOM_TICK_CHUNKS = 256;
    /**
     * System property that overrides {@link #RANDOM_TICK_CHUNKS}, so the size of the random tick workload can be
     * varied without editing the test. The grid is the smallest square that holds the requested count.
     */
    static final String RANDOM_TICK_CHUNKS_PROPERTY = "neoforge.serverBenchmarkRandomTickChunks";
    /**
     * Random tick speed of the random tick workload. The GameTest server runs with {@code randomTickSpeed=0}, so
     * the workload sets the rule for its own window and restores it afterwards.
     */
    private static final int RANDOM_TICK_SPEED = 3;
    /** Randomly ticking block layers per chunk of the random tick workload. */
    private static final int RANDOM_TICK_LAYERS = 4;
    /**
     * Height of the first randomly ticking layer. Every layer sits in open sky far above the generated terrain of
     * the test level, so the light checks of the random ticks see full sky light and the layer is a plain
     * random-tick workload instead of a light-limited one.
     */
    private static final int RANDOM_TICK_BASE_HEIGHT = 200;
    /** Vertical distance between two randomly ticking layers, so each layer is its own chunk section. */
    private static final int RANDOM_TICK_LAYER_SPACING = 8;
    /**
     * One in this many blocks of the workload is read for the block state fingerprint. The fingerprint only has
     * to prove that two runs of the same profile ended in the same block states, so a regular sample keeps the
     * reporting cost small on the 1024-chunk grid.
     */
    private static final int RANDOM_TICK_FINGERPRINT_STRIDE = 7;
    /** Block distance between two hopper columns of one chunk, so their chests never merge into one chest. */
    private static final int HOPPER_COLUMN_SPACING = 2;
    /** Slots of one chunk layer: {@code 16 / spacing} per horizontal axis. */
    private static final int HOPPER_SLOTS_PER_LAYER = (16 / HOPPER_COLUMN_SPACING) * (16 / HOPPER_COLUMN_SPACING);
    /** Blocks between two vertical layers: the hopper and the two chests of its column. */
    private static final int HOPPER_COLUMN_HEIGHT = 3;
    /** Slots of the source chest of one column that start filled, so the transfer never runs dry. */
    private static final int HOPPER_SOURCE_SLOTS = 4;
    /**
     * One in this many columns has no source chest and is fed with dropped item entities instead, so the
     * workload exercises the item query and the removal of an absorbed item entity, not only the container
     * to container transfer.
     */
    private static final int HOPPER_ITEM_COLUMN_EVERY = 3;
    /** Ticks between two drops above an item column; the vanilla hopper cooldown is the same eight ticks. */
    private static final int HOPPER_DROP_INTERVAL_TICKS = 8;
    /** Items of one drop above an item column. Every drop is the only inflow the conservation check adds. */
    private static final int HOPPER_DROP_STACK_SIZE = 1;
    /** Height above the hopper block an item entity is dropped at, inside the suck box and above the block. */
    private static final double HOPPER_DROP_HEIGHT = 1.05;
    /**
     * Sticky pistons of the piston workload when {@value #PISTON_COUNT_PROPERTY} is unset.
     * <p>
     * A piston clock unit carries two pistons, so the count is rounded up to an even number. The default is
     * the size at which a farm clock that fires every piston at once still moves a whole block column per
     * tick over a window of ten ticks: it is the workload at which the serialized block event phase would
     * first be expected to matter.
     */
    private static final int PISTON_COUNT = 128;
    /**
     * System property that overrides {@link #PISTON_COUNT}, so the size of the piston workload can be varied
     * without editing the test. An odd count is rounded up to the next even one, because a clock unit carries
     * two pistons.
     */
    static final String PISTON_COUNT_PROPERTY = "neoforge.serverBenchmarkPistons";
    /**
     * Ticks of one piston clock period when {@value #PISTON_CLOCK_PROPERTY} is unset: the redstone block of a
     * clock is placed for the first half of the period and removed for the second half, so every piston of the
     * workload extends once and retracts once per period.
     */
    private static final int PISTON_CLOCK_TICKS = 20;
    /**
     * System property that overrides {@link #PISTON_CLOCK_TICKS}. A farm clock of a piston door or a flying
     * machine runs between four and twenty ticks; the workload asserts the range because a faster clock would
     * fire into a move that has not finished its two-tick animation.
     */
    static final String PISTON_CLOCK_PROPERTY = "neoforge.serverBenchmarkPistonClockTicks";
    /**
     * Largest column a piston of the workload moves: vanilla resolves at most twelve pushed blocks, so one
     * clock unit per size class covers the whole range of a piston move.
     */
    private static final int PISTON_MAX_SIZE = 12;
    /**
     * Column size of every piston of the workload when {@value #PISTON_SIZE_PROPERTY} is unset: the size cycles
     * through 1..{@value #PISTON_MAX_SIZE} over the clock units, so one window covers the whole range.
     */
    private static final int PISTON_SIZE_MIXED = 0;
    /**
     * System property that pins the column size of every piston of the workload to one value between 1 and
     * {@value #PISTON_MAX_SIZE}, so the cost of one move can be reported per size class. Unset mixes the sizes.
     */
    static final String PISTON_SIZE_PROPERTY = "neoforge.serverBenchmarkPistonSize";
    /**
     * Y of the piston workload. The units stand in open sky far above the generated terrain of the test level,
     * so every pushed column moves into air and the workload does not depend on the terrain under it.
     */
    private static final int PISTON_HEIGHT = 250;
    /**
     * Blocks between two piston clock columns. A unit spans the clock, the two pistons and a column of at most
     * {@value #PISTON_MAX_SIZE} blocks on each side, which is 27 blocks; 48 is the width of a 3x3-chunk region,
     * so every column of the workload starts on the same block of its region.
     */
    private static final int PISTON_COLUMN_SPACING = 48;
    /** Clock units per row of the piston workload. */
    private static final int PISTON_UNITS_PER_ROW = 4;
    /** Blocks between two rows of the piston workload; a unit occupies a single z line. */
    private static final int PISTON_ROW_SPACING = 2;
    /**
     * Clock units of a row that push across a region border, as a period of the row index. Every second row is
     * placed on the first block of its region and pushes west into the previous one, so half of the moves of the
     * workload cross a border.
     */
    private static final int PISTON_BORDER_ROW_PERIOD = 2;
    /**
     * System property that overrides {@link #PISTON_BORDER_ROW_PERIOD}, so a measurement can place the whole
     * workload inside its own region ({@code 0}) and compare it with the half-crossing default. Every positive
     * value keeps the row pattern of the default workload.
     */
    static final String PISTON_BORDER_PERIOD_PROPERTY = "neoforge.serverBenchmarkPistonBorderRowPeriod";
    /** Distance from the chunk corner to the first workload slot of that chunk. */
    private static final int ENTITY_WORKER_SLOT_ORIGIN = 3;
    /** Distance between two workload slots of one chunk, so every mob of a chunk has room for its own POI. */
    private static final int ENTITY_WORKER_SLOT_SPACING = 6;
    /** Slots per chunk edge of the workload grid: two by two mobs per chunk at most. */
    private static final int ENTITY_WORKER_SLOTS_PER_EDGE = 2;

    /** Zombies of the entity workload. */
    private static final int ZOMBIE_COUNT = 250;
    /** Item entities of the entity workload. */
    private static final int ITEM_COUNT = 800;
    /** Ticks between two pushes of the item entities, so they keep crossing chunk boundaries. */
    private static final int ITEM_PUSH_INTERVAL_TICKS = 25;
    /** Radius of the block-ticking chunk ticket of the forced grid. */
    private static final int BLOCK_TICKING_TICKET_RADIUS = 1;
    /** Radius of the entity-ticking chunk ticket of the forced grid. */
    private static final int ENTITY_TICKING_TICKET_RADIUS = 2;
    /**
     * Zombies of the entity worker diagnostics workload when {@value #ZOMBIE_COUNT_PROPERTY} is unset. The
     * population only has to keep the region workers busy; the reported numbers are per operation, not per
     * entity.
     */
    private static final int ENTITY_WORKER_ZOMBIE_COUNT = 64;
    /**
     * System property that overrides {@link #ENTITY_WORKER_ZOMBIE_COUNT}, so the population of the entity
     * worker workload can be varied without editing the test. An unparsable or negative value fails instead
     * of silently measuring the default population.
     */
    static final String ZOMBIE_COUNT_PROPERTY = "neoforge.serverBenchmarkZombies";
    /**
     * System property that selects the mob of the entity worker workload, so the same window measures a work
     * load the per-type entity policy keeps on a region worker next to the zombie load it hands to the server
     * thread. The value is an entity type id; unset keeps {@code minecraft:zombie}.
     */
    static final String MOB_PROPERTY = "neoforge.serverBenchmarkMob";
    /**
     * System property that runs the entity worker workload with villagers instead of zombies, and with a bed
     * and a workstation next to every villager so its tick really takes both POIs. It overrides
     * {@value #MOB_PROPERTY} and the population of {@value #ZOMBIE_COUNT_PROPERTY}.
     */
    static final String VILLAGER_COUNT_PROPERTY = "neoforge.serverBenchmarkVillagers";
    /**
     * System property that drives zombie deaths in the entity worker workload: the number of zombies killed
     * every {@value #DEATH_INTERVAL_TICKS} ticks. Each death starts inside the tick of its own zombie, so the
     * loot spawn and the removal run on the thread that ticks the entity. Unset or {@code 0} keeps the
     * population alive for the whole window, which is the workload every earlier measurement used.
     */
    static final String ZOMBIE_DEATHS_PROPERTY = "neoforge.serverBenchmarkZombieDeaths";
    /** Ticks between two batches of driven zombie deaths. */
    private static final int DEATH_INTERVAL_TICKS = 10;
    /** Zombies killed every {@value #DEATH_INTERVAL_TICKS} ticks when {@value #ZOMBIE_DEATHS_PROPERTY} is unset. */
    private static final int ENTITY_WORKER_ZOMBIE_DEATHS = 0;
    /**
     * System property that turns the mob loot rule off for the entity worker window. A death then fails on the
     * experience orb it still spawns instead of on its loot table, which isolates the cost of the deferred
     * removal from the crash report the shared random source builds when two workers roll loot at once.
     * Unset keeps the vanilla rule.
     */
    static final String ZOMBIE_LOOT_PROPERTY = "neoforge.serverBenchmarkZombieLoot";
    /**
     * Message prefix of {@code RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess}. The suffix
     * is the operation the guard rejected, so the blocker table is keyed by that operation.
     */
    private static final String STORAGE_ACCESS_REJECTION_PREFIX = "Native region worker attempted server-owned entity storage access: ";
    /**
     * System property that turns the diagnostic engine's entity tick worker execution off, so the same
     * workload measures the server-thread reference on the same grid, population and window. Defaults to the
     * worker measurement; the reference run sets it to {@code false}.
     */
    static final String ENTITY_WORKER_TICKS_PROPERTY = "neoforge.serverBenchmarkEntityWorkerTicks";
    /**
     * System property that sets the measured window of the entity worker soak, in ticks. The soak is the only
     * workload with a window longer than the {@value #MEASURED_TICKS} ticks of the profiling workloads, so it
     * reads its own length instead of the shared constant.
     */
    static final String TICKS_PROPERTY = "neoforge.serverBenchmarkTicks";
    /** Measured ticks of the soak window unless {@value #TICKS_PROPERTY} overrides it. */
    private static final int SOAK_MEASURED_TICKS = 3000;
    /**
     * Trailing ticks of the soak window that the drift comparison reports next to the first half, i.e. the
     * window the profiling workloads measure, so a soak row is comparable with their rows.
     */
    private static final int SOAK_DRIFT_TICKS = 600;
    /**
     * Annotated tick timeout of the soak batch. The window has to fit into it with one tick of slack, which
     * the test asserts, so a misconfigured {@value #TICKS_PROPERTY} fails instead of timing the batch out.
     */
    private static final int SOAK_TIMEOUT_TICKS = 6000;
    /**
     * Zombies of the soak population when {@value #ZOMBIE_COUNT_PROPERTY} is unset. The soak is the only
     * workload that runs at a population large enough to keep four workers busy for minutes.
     */
    private static final int SOAK_ZOMBIE_COUNT = 1024;
    /**
     * Zombies the soak kills every {@value #DEATH_INTERVAL_TICKS} ticks when {@value #ZOMBIE_DEATHS_PROPERTY}
     * is unset. Vanilla never removes the loot of a death inside the window, so every death adds entities to
     * the level: the rate stays low enough for the population to stay roughly constant over the window.
     */
    private static final int SOAK_ZOMBIE_DEATHS = 1;

    /**
     * The worker dispatch columns of a window measured without an installed engine: every counter of the
     * dispatch path is zero, so the columns keep their shape for a row without region workers.
     */
    private static final RegionThreadingDiagnostics.WorkerDispatchCounters NO_WORKER_DISPATCH = new RegionThreadingDiagnostics.WorkerDispatchCounters(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false);
    static final String FURNACE_CSV_HEADER = "profile,workers,regionSize,furnaces,chunks,msptAvg,msptP50,msptP95,msptP99,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,leasedRegions,ownershipViolations,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceSamples,tasksPerTick,regionsPerPackedTask,taskSubmissions,packedTasks,packedRegions,submissionNanosPerTask,wakeUpNanosPerWakeUp,partialPackFallbacks,stealsPerTick";
    /**
     * Columns of the random tick workload row: the profile and its load, the server tick distribution, the chunk
     * tick counters, the segments of the server-thread chunk tick slice and the block state fingerprint of the
     * window's end state.
     */
    static final String SPAWN_CSV_HEADER = "profile,workers,regionSize,chunks,spawnableChunks,msptAvg,msptP50,msptP95,msptP99,spawnChunksOnWorkers,gateRefusedSpawnChunks,unloadedNeighborhoodSpawnChunks,deferredUpdateBudgetSpawnChunks,lateSpawnChunks,unleasedSpawnChunks,truncatedSpawnChunks,skippedSpawnChunks,capRefusedSpawns,refusedSpawnAdditions,spawnedMonsters,spawnedCreatures,spawnedWaterCreatures,spawnedWaterAmbient,spawnedAmbient,spawnedAxolotls,spawnedUndergroundWaterCreatures,spawnedMisc,mobsInLevel,additionsRecorded,additionsApplied,additionsDropped,entityAddRejectedOnWorker,ownershipViolations,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceP50Ms,sliceSamples";
    static final String RANDOM_TICK_CSV_HEADER = "profile,workers,regionSize,chunks,blocks,msptAvg,msptP50,msptP95,msptP99,chunkTicksOnWorkers,fallbackChunkTicks,boundaryChunkTicks,unloadedNeighborhoodChunkTicks,truncatedChunkTicks,unleasedChunkTicks,chunkTickLightningStrikes,ownershipViolations,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceP50Ms,sliceSamples,blockStateFingerprint";
    static final String HOPPER_CSV_HEADER = "profile,workers,regionSize,hoppers,chunks,msptAvg,msptP50,msptP95,msptP99,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,nonTickingBlockEntityChunks,entitiesLoadingBlockEntityChunks,missingBlockEntityChunks,staleBlockEntityChunkSnapshots,leasedRegions,entityQueriesAllowed,entityQueriesTruncated,entityQueriesRejected,ownershipViolations,removalsApplied,removalsDropped,removalsRepeated,itemCountStart,itemCountEnd,itemsDropped,itemsDelivered,itemsConserved,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceSamples,blockEntityAdmissionCache,tasksPerTick,regionsPerPackedTask,taskSubmissions,packedTasks,packedRegions,submissionNanosPerTask,wakeUpNanosPerWakeUp,partialPackFallbacks,stealsPerTick";
    static final String ENTITY_CSV_HEADER = "profile,workers,regionSize,zombies,items,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,deferredEntityTicks,sectionUpdatesApplied,sectionUpdatesDropped,ownershipViolations,zombieReplacements,regionMismatches";
    static final String ENTITY_WORKER_CSV_HEADER = "profile,workers,regionSize,mobs,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,skippedEntityTicks,deferredEntityTicks,entityMutationsAllowed,entityMutationsRejected,entityQueriesAllowed,entityQueriesTruncated,entityQueriesRejected,ownershipViolations,sectionUpdatesApplied,sectionUpdatesDropped,pendingTransfers,rememberedTransfers,rejectedTransferRecords,legacyDeferredMoves,retriedDeferredMoves,unchangedDeferredMoves,batchedDeferredMoves,refusedDeferredMoveBatches,regionMismatches,mobSurvivors,entityTickFailures,distinctBlockers,chunkGuardRefusals,chunkFallbackEntityTicks,chunkPreloadRefusedEntityTicks,chunkPreloadMargin,entityTickMode,planMs,leaseMs,awaitMs,handoffMs,deferredPublicationMs,sliceMs,sliceSamples,removalsApplied,removalsDropped,removalsRepeated,additionsRecorded,additionsApplied,additionsDropped,entityAddRejectedOnWorker,mobDeaths,mobReplacements,tasksPerTick,regionsPerPackedTask,taskSubmissions,packedTasks,packedRegions,submissionNanosPerTask,wakeUpNanosPerWakeUp,partialPackFallbacks,stealsPerTick";
    /**
     * Columns of the piston workload row: the profile and its load, the server tick distribution, the block
     * event phase and the distribution of one move.
     */
    static final String PISTON_CSV_HEADER = "profile,workers,regionSize,pistons,units,borderUnits,clockTicks,msptAvg,msptP50,msptP95,msptP99,blockEventPhases,blockEvents,movesPerTick,blockEventMsPerTick,blockEventMicrosPerMove,eventP50Micros,eventP95Micros,eventP99Micros,blockEventPhaseMaxMs,ownershipViolations";
    static final String ENTITY_WORKER_BLOCKER_HEADER = "operation,count,firstFrame,entityTypes";
    static final String SOAK_MSPT_HEADER = "profile,zombies,measuredTicks,window,samples,msptAvg,msptP50,msptP95,msptP99";
    static final String SOAK_DRIFT_HEADER = "profile,firstHalfAvgMs,lastAvgMs,driftRatio,driftPercent,firstHalfP99Ms,lastP99Ms";
    static final String SOAK_COUNTER_HEADER = "profile,counter,before,after,delta,perTick";
    static final String SOAK_STRUCT_HEADER = "point,measuredTick,heapUsedMiB,heapCommittedMiB,heapMaxMiB,gcCollections,gcMillis,regionCount,runningRegions,trackedEntities,trackedEntityTasks,sections,loadedChunks,pendingDeferredMoves,pendingTransfers,rememberedTransfers,activeBatches";
    static final String SOAK_TICK_HEADER = "ticksChecked,ticksWithActiveBatches,maxActiveBatches";
    static final String SOAK_INVARIANT_HEADER = "check,observed,expected,ok";

    @GameTest(batch = "benchmarkFurnace", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures server tick time under a lit-furnace load; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderFurnaceLoad(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped furnace workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<BlockPos> furnaces = furnacePositions(origin);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, null);
            // The attribution is reported per measured tick, so it is reset when the warmup ends and the
            // warmup itself stays unmeasured.
            sampler.onMeasuredWindowStart(RegionBlockEntityTickAttribution::reset);
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<RegionBlockEntityTickAttribution.Snapshot> attributionBefore = new AtomicReference<>(RegionBlockEntityTickAttribution.snapshot());
            final AtomicBoolean cleaned = new AtomicBoolean();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    RegionBlockEntityTickAttribution.setEnabled(false);
                    removeFurnaces(level, furnaces);
                    releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS))
                    // The worker slice only admits a ticker whose chunk finished its entity load, so the
                    // grid is materialised and waited for before any furnace is placed.
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the benchmark chunk grid never finished its entity load"))
                    .thenExecute(() -> placeFurnaces(level, furnaces, helper))
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        RegionBlockEntityTickAttribution.setEnabled(true);
                        attributionBefore.set(RegionBlockEntityTickAttribution.snapshot());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    // One extra tick: the sequence runs inside the tick body, so the last sampled tick only
                    // completes its Post event after this sequence step would otherwise have read it.
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    // Cleanup runs before the test succeeds: the GameTestRunner forces the chunks of the
                    // next batch's structures while the test-passed listeners run, so releasing the grid
                    // afterwards would unforce those chunks again and stall the next batch forever.
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final RegionBlockEntityTickAttribution.Snapshot attributionAfter = RegionBlockEntityTickAttribution.snapshot();
                        RegionBlockEntityTickAttribution.setEnabled(false);
                        reportFurnaces(helper, sampler, before.get(), attributionBefore.get(), attributionAfter, furnaces.size(), grid.size());
                        if (cleaned.compareAndSet(false, true)) {
                            removeFurnaces(level, furnaces);
                            releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "benchmarkHopper", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures server tick time under a hopper transfer load; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderHopperLoad(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped hopper workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final boolean admissionCache = applyBlockEntityAdmissionCacheSetting();
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<BlockPos> hoppers = hopperPositions(origin);
            final List<BlockPos> itemColumns = new ArrayList<>();
            for (int index = 0; index < hoppers.size(); index++) {
                if (isHopperItemColumn(index)) {
                    itemColumns.add(hoppers.get(index));
                }
            }
            final HopperItemDrops drops = new HopperItemDrops(level, itemColumns);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, drops);
            // The attribution is reported per measured tick, so it is reset when the warmup ends and the
            // warmup itself stays unmeasured.
            sampler.onMeasuredWindowStart(RegionBlockEntityTickAttribution::reset);
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<RegionBlockEntityTickAttribution.Snapshot> attributionBefore = new AtomicReference<>(RegionBlockEntityTickAttribution.snapshot());
            final AtomicReference<Long> itemCountStart = new AtomicReference<>(0L);
            final AtomicBoolean cleaned = new AtomicBoolean();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    RegionBlockEntityTickAttribution.setEnabled(false);
                    removeHoppers(level, hoppers);
                    releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS))
                    // The worker slice only admits a ticker whose chunk finished its entity load, so the
                    // grid is materialised and waited for before any hopper is placed.
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the benchmark chunk grid never finished its entity load"))
                    .thenExecute(() -> placeHoppers(level, hoppers, helper))
                    .thenExecute(() -> {
                        itemCountStart.set(countItems(level, hoppers));
                        before.set(RegionThreadingHooks.diagnostics());
                        RegionBlockEntityTickAttribution.setEnabled(true);
                        attributionBefore.set(RegionBlockEntityTickAttribution.snapshot());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    // One extra tick: the sequence runs inside the tick body, so the last sampled tick only
                    // completes its Post event after this sequence step would otherwise have read it.
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    // Cleanup runs before the test succeeds: the GameTestRunner forces the chunks of the
                    // next batch's structures while the test-passed listeners run, so releasing the grid
                    // afterwards would unforce those chunks again and stall the next batch forever.
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final RegionBlockEntityTickAttribution.Snapshot attributionAfter = RegionBlockEntityTickAttribution.snapshot();
                        RegionBlockEntityTickAttribution.setEnabled(false);
                        reportHoppers(helper, sampler, before.get(), attributionBefore.get(), attributionAfter, hoppers, grid.size(), itemCountStart.get(), drops.injectedItems(), admissionCache);
                        if (cleaned.compareAndSet(false, true)) {
                            removeHoppers(level, hoppers);
                            releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "benchmarkPiston", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures the server-thread block event phase under a piston clock load; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderPistonLoad(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped piston workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final int clockTicks = pistonClockTicks();
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<PistonUnit> units = pistonUnits(origin);
            final PistonClock clock = new PistonClock(level, units, clockTicks);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, clock);
            // The attribution is reported per measured tick, so it starts from zero when the warmup ends and
            // the warmup itself stays unmeasured.
            sampler.onMeasuredWindowStart(() -> {
                RegionBlockEventAttribution.setMeasuredLevel(level);
                RegionBlockEventAttribution.setEnabled(true);
            });
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicBoolean cleaned = new AtomicBoolean();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    RegionBlockEventAttribution.setEnabled(false);
                    RegionBlockEventAttribution.setMeasuredLevel(null);
                    removePistonUnits(level, units);
                    releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS))
                    // The clock units stand in chunks that have to exist before a piston of them can move.
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the benchmark chunk grid never finished its entity load"))
                    .thenExecute(() -> placePistonUnits(level, units, helper))
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    // Two extra ticks: the sequence runs inside the tick body, so the last sampled tick only
                    // completes its Post event after this sequence step would otherwise have read it, and the
                    // sampler closes its window on the tick after that.
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 2)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final RegionBlockEventAttribution.Snapshot attribution = RegionBlockEventAttribution.snapshot();
                        RegionBlockEventAttribution.setEnabled(false);
                        RegionBlockEventAttribution.setMeasuredLevel(null);
                        reportPistons(helper, level, sampler, before.get(), attribution, units, clockTicks, clock.writes());
                        if (cleaned.compareAndSet(false, true)) {
                            removePistonUnits(level, units);
                            releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "benchmarkRandomTick", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures the server tick and the chunk tick slice under a random tick load; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderRandomTickLoad(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped random tick workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = randomTickGrid(origin, intProperty(RANDOM_TICK_CHUNKS_PROPERTY, RANDOM_TICK_CHUNKS));
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, null);
            // The chunk tick attribution is reported per measured tick, so it is reset when the warmup ends. The
            // slices of the test level are measured; the other dimensions of the server carry no workload.
            sampler.onMeasuredWindowStart(() -> {
                RegionChunkTickAttribution.reset();
                RegionChunkTickAttribution.setMeasuredLevel(level);
            });
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<RegionChunkTickAttribution.Snapshot> attributionBefore = new AtomicReference<>(RegionChunkTickAttribution.snapshot());
            final AtomicReference<Long> placedFingerprint = new AtomicReference<>(0L);
            final AtomicReference<Integer> blocksPlaced = new AtomicReference<>(0);
            final GameRules.IntegerValue randomTicking = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTicking = randomTicking.get();
            final AtomicBoolean cleaned = new AtomicBoolean();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    RegionChunkTickAttribution.setEnabled(false);
                    RegionChunkTickAttribution.setMeasuredLevel(null);
                    randomTicking.set(previousRandomTicking, level.getServer());
                    releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the random tick chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        blocksPlaced.set(placeRandomTickBlocks(level, grid));
                        placedFingerprint.set(randomTickFingerprint(level, grid));
                        randomTicking.set(RANDOM_TICK_SPEED, level.getServer());
                    })
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        RegionChunkTickAttribution.setEnabled(true);
                        attributionBefore.set(RegionChunkTickAttribution.snapshot());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    // One extra tick: the sequence runs inside the tick body, so the last sampled tick only
                    // completes its Post event after this sequence step would otherwise have read it.
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final RegionChunkTickAttribution.Snapshot attributionAfter = RegionChunkTickAttribution.snapshot();
                        RegionChunkTickAttribution.setEnabled(false);
                        RegionChunkTickAttribution.setMeasuredLevel(null);
                        reportRandomTicks(helper, sampler, before.get(), attributionBefore.get(), attributionAfter,
                                grid.size(), blocksPlaced.get(), placedFingerprint.get(), randomTickFingerprint(level, grid), level.getGameTime());
                        if (cleaned.compareAndSet(false, true)) {
                            randomTicking.set(previousRandomTicking, level.getServer());
                            releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "benchmarkSpawn", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures the natural spawning slice and the spawned population under a dark spawn platform; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderSpawnLoad(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped spawning workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = randomTickGrid(origin, intProperty(SPAWN_CHUNKS_PROPERTY, SPAWN_CHUNKS));
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, null);
            sampler.onMeasuredWindowStart(() -> {
                RegionSpawnAttribution.reset();
                RegionSpawnAttribution.setMeasuredLevel(level);
            });
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<RegionSpawnAttribution.Snapshot> attributionBefore = new AtomicReference<>(RegionSpawnAttribution.snapshot());
            final AtomicReference<Player> spawnPlayer = new AtomicReference<>();
            final AtomicBoolean cleaned = new AtomicBoolean();
            // The GameTest server runs with mob spawning off and on a peaceful difficulty, so neither vanilla nor
            // the region slice would spawn for any chunk. The workload turns both on for its own window and
            // restores them afterwards.
            final GameRules.BooleanValue doMobSpawning = level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
            final boolean previousDoMobSpawning = doMobSpawning.get();
            final Difficulty previousDifficulty = level.getDifficulty();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    RegionSpawnAttribution.setEnabled(false);
                    RegionSpawnAttribution.setMeasuredLevel(null);
                    removeSpawnPlatform(level, grid);
                    releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                    doMobSpawning.set(previousDoMobSpawning, level.getServer());
                    level.getServer().setDifficulty(previousDifficulty, true);
                    final Player player = spawnPlayer.get();
                    if (player != null && !player.isRemoved()) {
                        player.discard();
                    }
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the spawning chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        doMobSpawning.set(true, level.getServer());
                        level.getServer().setDifficulty(Difficulty.NORMAL, true);
                        placeSpawnPlatform(level, grid);
                    })
                    .thenIdle(SPAWN_PLATFORM_SETTLE_TICKS)
                    // The spawn gate of the level is player-driven: vanilla spawns nothing for a chunk no player
                    // is close enough to for spawning, and a spawn body needs a player to measure its distance
                    // from. The ticking mock player is placed at the centre of the platform, so every chunk of
                    // it that is within the vanilla spawn distance of a player spawns.
                    .thenExecute(() -> {
                        // A fake player instead of a game test player: it has no connection that the server
                        // could drop during the window, and the spawn gate of a chunk is player-driven.
                        final FakePlayer player = FakePlayerFactory.getMinecraft(level);
                        final ChunkPos centre = originChunk(origin);
                        player.moveTo(centre.getMinBlockX() + 8.5, SPAWN_PLATFORM_HEIGHT + 1.0, centre.getMinBlockZ() + 8.5);
                        level.addNewPlayer(player);
                        level.getChunkSource().move(player);
                        spawnPlayer.set(player);
                        helper.assertTrue(level.players().contains(player), "the spawn workload player was not added to the level");
                    })
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().filter(chunk -> level.isNaturalSpawningAllowed(chunk))
                                    .count() > grid.size() / 2,
                            "the game test level never made the spawn platform entity ticking"))
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        RegionSpawnAttribution.setEnabled(true);
                        attributionBefore.set(RegionSpawnAttribution.snapshot());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final RegionSpawnAttribution.Snapshot attributionAfter = RegionSpawnAttribution.snapshot();
                        RegionSpawnAttribution.setEnabled(false);
                        RegionSpawnAttribution.setMeasuredLevel(null);
                        reportSpawns(helper, sampler, before.get(), attributionBefore.get(), attributionAfter, level, grid, origin);
                        if (cleaned.compareAndSet(false, true)) {
                            removeSpawnPlatform(level, grid);
                            releaseChunks(level, grid, BLOCK_TICKING_TICKET_RADIUS);
                            doMobSpawning.set(previousDoMobSpawning, level.getServer());
                            level.getServer().setDifficulty(previousDifficulty, true);
                            final Player player = spawnPlayer.get();
                            if (player != null && !player.isRemoved()) {
                                player.discard();
                            }
                        }
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "benchmarkEntity", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Measures server tick time under a moving entity load; only runs when neoforge.serverBenchmark is set")
    static void measuresServerTickTimeUnderEntityChurn(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped entity workload: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<Zombie> zombies = new ArrayList<>(ZOMBIE_COUNT);
            final List<ItemEntity> items = new ArrayList<>(ITEM_COUNT);
            final EntityChurn churn = new EntityChurn(level, zombies, items, origin);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, churn);
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicBoolean cleaned = new AtomicBoolean();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    churn.discardAll();
                    daylight.set(previousDaylight, level.getServer());
                    releaseChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS);
                }
            });

            helper.startSequence()
                    // Entities only tick in entity-ticking chunks, so this grid uses the deeper ticket.
                    .thenExecute(() -> forceChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the benchmark chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        daylight.set(false, level.getServer());
                        level.setDayTime(18000L);
                        churn.spawnAll(helper);
                    })
                    .thenExecute(() -> {
                        before.set(RegionThreadingHooks.diagnostics());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        reportEntities(helper, sampler, before.get(), churn, grid.size());
                        if (cleaned.compareAndSet(false, true)) {
                            churn.discardAll();
                            daylight.set(previousDaylight, level.getServer());
                            releaseChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS);
                        }
                    })
                    .thenSucceed();
        });
    }

    /**
     * Diagnoses what blocks entity ticks on region workers: it replaces the configured engine with one that
     * has region-owned entity storage and entity tick worker execution enabled, ticks a real zombie
     * population on the workers for {@value #WARMUP_TICKS} warmup and {@value #MEASURED_TICKS} measured
     * ticks, and reports every rejected operation with its count, its entity type and the first stack frame
     * of its first failure.
     * <p>
     * The diagnostic engine keeps the layout, the worker count and the other slices of the configured
     * engine, and it is rebuilt from those flags after the window, so neither the production defaults nor
     * the server config are changed by the run. The guards stay untouched: a rejected operation is still
     * rejected and counted, only the batch is kept alive so one run can collect the whole blocker list
     * instead of the first blocker.
     * <p>
     * A run without an installed engine (region threading off) has nothing to replace: the workload then
     * ticks its entities inline on the server thread and reports the server-thread reference row for the
     * same grid, population and window.
     */
    @GameTest(batch = "benchmarkEntityWorkers", timeoutTicks = 2000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Diagnoses the operations that reject entity ticks on region workers; only runs when neoforge.serverBenchmark is set")
    static void diagnosesEntityTicksOnRegionWorkers(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_BENCH_NOTE skipped entity worker diagnostics: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<Mob> mobs = new ArrayList<>(entityWorkerMobCount());
            final ZombieDeathDriver deathDriver = new ZombieDeathDriver(level, mobs, entityWorkerZombieDeaths());
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, deathDriver);
            final EntityWorkerBlockers blockers = new EntityWorkerBlockers();
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<RegionEntityTickAttribution.Snapshot> attributionBefore = new AtomicReference<>(RegionEntityTickAttribution.snapshot());
            final AtomicReference<InstalledEngine> replaced = new AtomicReference<>();
            final AtomicBoolean cleaned = new AtomicBoolean();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            final GameRules.BooleanValue mobLoot = level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
            final boolean previousMobLoot = mobLoot.get();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    NeoForge.EVENT_BUS.unregister(deathDriver);
                    cleanupEntityWorkerWindow(level, grid, mobs, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
                }
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the benchmark chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        // Zombies burn in daylight, so the window runs at night like the entity workload.
                        daylight.set(false, level.getServer());
                        level.setDayTime(18000L);
                        mobLoot.set(zombieLootEnabled(), level.getServer());
                        spawnEntityWorkerMobs(level, grid, origin, mobs, helper, entityWorkerMobCount());
                    })
                    .thenExecute(() -> {
                        replaced.set(installEntityWorkerEngine(blockers, entityTickWorkers()).orElse(null));
                        // The attribution is reported per measured tick, so it is reset when the warmup
                        // ends and the warmup itself stays unmeasured.
                        sampler.onMeasuredWindowStart(() -> {
                            RegionEntityTickAttribution.reset();
                            deathDriver.markMeasuredWindowStart();
                        });
                        RegionEntityTickAttribution.setEnabled(true);
                        before.set(RegionThreadingHooks.diagnostics());
                        attributionBefore.set(RegionEntityTickAttribution.snapshot());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                        // The death driver hurts a zombie from inside its own tick, so it has to be a bus
                        // listener: the entity tick of a worker profile runs on the region worker.
                        NeoForge.EVENT_BUS.register(deathDriver);
                    })
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        NeoForge.EVENT_BUS.unregister(deathDriver);
                        // The counters of the diagnostic engine are read before it is replaced again.
                        reportEntityWorkers(helper, sampler, before.get(), attributionBefore.get(), RegionEntityTickAttribution.snapshot(), mobs, blockers, deathDriver, grid.size());
                        if (cleaned.compareAndSet(false, true)) {
                            cleanupEntityWorkerWindow(level, grid, mobs, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
                        }
                    })
                    .thenSucceed();
        });
    }

    /**
     * Soaks the entity worker path: a zombie population large enough to keep four workers busy, churned by
     * deaths, respawns and the loot of the deaths, for a window of {@value #SOAK_MEASURED_TICKS} measured
     * ticks after {@value #WARMUP_TICKS} warmup ticks.
     * <p>
     * The diagnostic engine of the worker profile is installed before the grid of this test is loaded, so the
     * chunk load, the spawn of the population and every measured tick of the window run on the engine under
     * test. The window also runs with the level random already wrapped for region workers, because the server
     * config requests the entity tick slice at server start and {@code RegionThreadingHooks.wrapLevelRandom}
     * decides the wrapper when the level is created; a mid-window swap cannot recreate it. A run without an
     * installed engine (region threading off) ticks the same workload inline on the server thread and reports
     * the server-thread reference row of the mspt context.
     * <p>
     * The workload is a {@link ZombieDeathDriver} that kills {@value #SOAK_ZOMBIE_DEATHS} zombie every
     * {@value #DEATH_INTERVAL_TICKS} ticks from inside its own tick and replaces a dead or dying zombie at
     * the same position, so the population stays roughly constant while the death loot, the deferred removal
     * of the dying entity and the deferred admission of the loot keep running through the batch barrier. The
     * loot rule stays on; the death rate is deliberately low because vanilla never removes the loot of a
     * death inside the window, so a high rate would grow the level by more entities than the window measures.
     * <p>
     * The report covers the mspt of the whole window, of its first half and of its last
     * {@value #SOAK_DRIFT_TICKS} ticks, every {@link RegionThreadingDiagnostics} counter of the window, and
     * an after-GC heap and engine structure sample at the start, the middle and the end. The end-state
     * invariants are asserted after the report was printed: no tick leaves an active batch behind, no
     * deferred ownership update or transfer stays queued, the regionizer mapping of every tracked entity
     * names the region of its block position, no removed entity stays mapped, every live entity sits in the
     * section of its position, the identity ledger live count matches the live entity count of the level, and
     * the window produced no entity tick failure and no ownership violation.
     */
    @GameTest(batch = "benchmarkEntitySoak", timeoutTicks = SOAK_TIMEOUT_TICKS)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Soaks the entity worker path with a large zombie population, deaths, respawn and loot; only runs when neoforge.serverBenchmark is set")
    static void soaksEntityTicksOnRegionWorkers(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
                System.out.println("SERVER_SOAK_NOTE skipped entity worker soak: -D" + ENABLED_PROPERTY + "=true is not set");
                helper.succeed();
                return;
            }
            final int measuredTicks = soakMeasuredTicks();
            final int zombieCount = soakZombieCount();
            helper.assertTrue(measuredTicks > 0 && WARMUP_TICKS + measuredTicks + 1 <= SOAK_TIMEOUT_TICKS,
                    "the soak window of " + measuredTicks + " measured ticks does not fit into the batch timeout of " + SOAK_TIMEOUT_TICKS + " ticks");
            final ServerLevel level = helper.getLevel();
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final List<ChunkPos> grid = gridChunks(origin);
            final List<Mob> zombies = new ArrayList<>(zombieCount);
            final ZombieDeathDriver deathDriver = new ZombieDeathDriver(level, zombies, soakZombieDeaths());
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, measuredTicks, deathDriver);
            final SoakWatch watch = new SoakWatch();
            final EntityWorkerBlockers blockers = new EntityWorkerBlockers();
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<InstalledEngine> replaced = new AtomicReference<>();
            final AtomicBoolean cleaned = new AtomicBoolean();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            final GameRules.BooleanValue mobLoot = level.getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
            final boolean previousMobLoot = mobLoot.get();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    NeoForge.EVENT_BUS.unregister(deathDriver);
                    NeoForge.EVENT_BUS.unregister(watch);
                    cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
                }
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        // The engine of the soak is installed before any of the workload exists, so the chunk
                        // load, every spawn and every measured tick of the window run on the engine under test.
                        replaced.set(installEntityWorkerEngine(blockers, entityTickWorkers()).orElse(null));
                        watch.install();
                        forceChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the soak chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        // Zombies burn in daylight, so the window runs at night like the entity workload.
                        daylight.set(false, level.getServer());
                        level.setDayTime(18000L);
                        mobLoot.set(zombieLootEnabled(), level.getServer());
                        spawnZombies(level, grid, origin, zombies, helper, zombieCount);
                    })
                    .thenExecute(() -> {
                        watch.startWindow(measuredTicks);
                        // The counters of the window start when the warmup ends, so every reported delta
                        // belongs to the measured ticks and to the load those ticks carry.
                        sampler.onMeasuredWindowStart(() -> {
                            deathDriver.markMeasuredWindowStart();
                            before.set(RegionThreadingHooks.diagnostics());
                        });
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(watch);
                        NeoForge.EVENT_BUS.register(sampler);
                        // The death driver hurts a zombie from inside its own tick, so it has to be a bus
                        // listener: the entity tick of a worker profile runs on the region worker.
                        NeoForge.EVENT_BUS.register(deathDriver);
                    })
                    // The middle sample splits the window in half, so the heap and the engine structures are
                    // observed at the start, the middle and the end of the measured ticks.
                    .thenIdle(WARMUP_TICKS + measuredTicks / 2 + 1)
                    .thenExecute(watch::sampleMiddle)
                    .thenIdle(measuredTicks - measuredTicks / 2)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        NeoForge.EVENT_BUS.unregister(deathDriver);
                        NeoForge.EVENT_BUS.unregister(watch);
                        final List<Invariant> invariants = reportSoak(helper, new SoakWindow(level, sampler, watch, before.get(), blockers, deathDriver, zombies, grid, zombieCount, measuredTicks));
                        if (cleaned.compareAndSet(false, true)) {
                            cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
                        }
                        // The report is printed before the invariants fail the batch, so a broken soak always
                        // leaves its evidence in the log.
                        for (Invariant invariant : invariants) {
                            helper.assertTrue(invariant.ok(), "soak invariant " + invariant.name() + " expected " + invariant.expected() + " but observed " + invariant.observed());
                        }
                    })
                    .thenSucceed();
        });
    }

    /**
     * {@return the smallest square chunk grid of at least {@code chunks} chunks around {@code origin}}
     * <p>
     * The random tick workload is sized by a chunk count, but a chunk grid is square, so a request of 64, 256 or
     * 1024 chunks is exact and any other count is rounded up to the next square.
     */
    private static List<ChunkPos> randomTickGrid(BlockPos origin, int chunks) {
        final int side = Math.max(1, (int) Math.ceil(Math.sqrt(chunks)));
        final int baseChunkX = Math.floorDiv(origin.getX(), 16);
        final int baseChunkZ = Math.floorDiv(origin.getZ(), 16);
        final List<ChunkPos> grid = new ArrayList<>(side * side);
        for (int dx = -side / 2; dx < side - side / 2; dx++) {
            for (int dz = -side / 2; dz < side - side / 2; dz++) {
                grid.add(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
            }
        }
        return grid;
    }

    /**
     * Places the random tick workload: {@value #RANDOM_TICK_LAYERS} randomly ticking layers per chunk.
     * <p>
     * Three layers are grass blocks on dirt, so the grass spread attempts - the constant part of a random tick
     * workload - run on every tick and the dirt below gives the spread a target during the warmup. The last layer
     * is farmland with wheat above it, so the crop growth path is part of the load as well; the wheat reaches its
     * last age inside the warmup and keeps its area and light checks afterwards.
     * <p>
     * The blocks are written into the chunk sections directly: the workload is a block state pattern, and a full
     * {@code setBlock} per placed block would queue a light update per block and leave the light thread working
     * during the measured window. Every layer sits far above the generated terrain, so the sky light of the
     * untouched sections is the full sky light the random ticks of the workload check.
     *
     * @return the number of placed blocks
     */
    private static int placeRandomTickBlocks(ServerLevel level, List<ChunkPos> grid) {
        final BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState farmland = Blocks.FARMLAND.defaultBlockState();
        final BlockState wheat = Blocks.WHEAT.defaultBlockState();
        int placed = 0;
        for (final ChunkPos chunk : grid) {
            final LevelChunk levelChunk = level.getChunk(chunk.x, chunk.z);
            for (int layer = 0; layer < RANDOM_TICK_LAYERS; layer++) {
                final int y = RANDOM_TICK_BASE_HEIGHT + layer * RANDOM_TICK_LAYER_SPACING;
                final boolean cropLayer = layer == RANDOM_TICK_LAYERS - 1;
                for (int offsetX = 0; offsetX < 16; offsetX++) {
                    for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                        final int x = chunk.getMinBlockX() + offsetX;
                        final int z = chunk.getMinBlockZ() + offsetZ;
                        if (cropLayer) {
                            placeBlock(levelChunk, x, y, z, farmland);
                            placeBlock(levelChunk, x, y + 1, z, wheat);
                            placed += 2;
                        } else {
                            placeBlock(levelChunk, x, y - 1, z, dirt);
                            placeBlock(levelChunk, x, y, z, grass);
                            placed += 2;
                        }
                    }
                }
            }
        }
        return placed;
    }

    private static void placeBlock(LevelChunk chunk, int x, int y, int z, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x & 15, y & 15, z & 15, state);
    }

    /**
     * {@return a fingerprint of the block states of the random tick workload}
     * <p>
     * The fingerprint is the FNV-1a hash of the block state ids of every
     * {@value #RANDOM_TICK_FINGERPRINT_STRIDE}th workload block. Two runs of the same profile have to end in the
     * same block states, so a difference between two repeats of one profile is a determinism failure.
     */
    private static long randomTickFingerprint(ServerLevel level, List<ChunkPos> grid) {
        long hash = 0xcbf29ce484222325L;
        int visited = 0;
        for (final ChunkPos chunk : grid) {
            final LevelChunk levelChunk = level.getChunk(chunk.x, chunk.z);
            for (int layer = 0; layer < RANDOM_TICK_LAYERS; layer++) {
                final int y = RANDOM_TICK_BASE_HEIGHT + layer * RANDOM_TICK_LAYER_SPACING;
                final boolean cropLayer = layer == RANDOM_TICK_LAYERS - 1;
                for (int offsetX = 0; offsetX < 16; offsetX++) {
                    for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                        if (visited++ % RANDOM_TICK_FINGERPRINT_STRIDE != 0) {
                            continue;
                        }
                        final int x = chunk.getMinBlockX() + offsetX;
                        final int z = chunk.getMinBlockZ() + offsetZ;
                        final BlockState state = levelChunk.getBlockState(new BlockPos(x, cropLayer ? y + 1 : y, z));
                        hash ^= Block.getId(state);
                        hash *= 0x100000001b3L;
                    }
                }
            }
        }
        return hash;
    }

    /**
     * Prints the measurement of the random tick workload as one CSV row.
     * <p>
     * The counters are the deltas of {@link RegionThreadingDiagnostics} over the measured window, so they belong
     * to the measured ticks only. The five attribution columns are the per-tick wall time of the segments of the
     * server-thread chunk tick slice, and {@code sliceP50Ms} is the median of the per-tick slice wall times: a
     * full server tick carries a 12-20 ms tail even inline, so the mean of the tick cannot resolve a slice effect
     * of a few milliseconds and the median of the slice itself is reported next to it.
     * <p>
     * {@code blockStateFingerprint} is printed for both repeats of one profile: the same seed and the same region
     * layout have to end in the same block states.
     */
    /**
     * Places the natural spawning platform: a grass floor and an opaque roof
     * {@value #SPAWN_PLATFORM_HEADROOM} blocks above it, over every chunk of the grid.
     * <p>
     * The floor is a spawnable solid block and the roof leaves only the three air blocks between them for a mob, so
     * the light of the room is zero and every spawn rule of a monster passes on the floor. Both layers are placed
     * with {@code Block.UPDATE_CLIENTS}, so the placement does not cascade through the level; the light of the room
     * settles during the settle ticks before the measured window.
     */
    private static void placeSpawnPlatform(ServerLevel level, List<ChunkPos> grid) {
        final BlockState floor = Blocks.GRASS_BLOCK.defaultBlockState();
        final BlockState roof = Blocks.STONE.defaultBlockState();
        for (final ChunkPos chunk : grid) {
            for (int offsetX = 0; offsetX < 16; offsetX++) {
                for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                    final int x = chunk.getMinBlockX() + offsetX;
                    final int z = chunk.getMinBlockZ() + offsetZ;
                    level.setBlock(new BlockPos(x, SPAWN_PLATFORM_HEIGHT, z), floor, Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(x, SPAWN_PLATFORM_HEIGHT + 1 + SPAWN_PLATFORM_HEADROOM, z), roof,
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Removes the natural spawning platform and every mob that spawned on it, so a later measurement of the same
     * server starts from the state the platform left.
     */
    private static void removeSpawnPlatform(ServerLevel level, List<ChunkPos> grid) {
        final BlockState air = Blocks.AIR.defaultBlockState();
        for (final ChunkPos chunk : grid) {
            for (int offsetX = 0; offsetX < 16; offsetX++) {
                for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                    final int x = chunk.getMinBlockX() + offsetX;
                    final int z = chunk.getMinBlockZ() + offsetZ;
                    level.setBlock(new BlockPos(x, SPAWN_PLATFORM_HEIGHT, z), air, Block.UPDATE_CLIENTS);
                    level.setBlock(new BlockPos(x, SPAWN_PLATFORM_HEIGHT + 1 + SPAWN_PLATFORM_HEADROOM, z), air,
                            Block.UPDATE_CLIENTS);
                }
            }
        }
        final int minY = SPAWN_PLATFORM_HEIGHT;
        final int maxY = SPAWN_PLATFORM_HEIGHT + SPAWN_PLATFORM_HEADROOM + 2;
        for (final ChunkPos chunk : grid) {
            final AABB box = new AABB(chunk.getMinBlockX(), minY, chunk.getMinBlockZ(),
                    chunk.getMinBlockX() + 16, maxY, chunk.getMinBlockZ() + 16);
            for (final Entity entity : level.getEntitiesOfClass(Entity.class, box, entity -> true)) {
                if (!(entity instanceof Player)) {
                    entity.discard();
                }
            }
        }
    }

    /**
     * Prints the natural spawning measurement of {@code level}: the server tick samples of the window, the
     * per-category population the window spawned and the spawning slice with its counters and its attribution.
     * <p>
     * The spawned population of a category is the delta of the level's own entity count, so it counts what actually
     * reached the level and not what a spawn body attempted. The population at the end of the window is reported as
     * {@code mobsInLevel}, which is the number the vanillla mob cap of the same chunk count bounds.
     */
    private static void reportSpawns(ExtendedGameTestHelper helper, TickSampler sampler,
            Optional<RegionThreadingDiagnostics> before, RegionSpawnAttribution.Snapshot attributionBefore,
            RegionSpawnAttribution.Snapshot attributionAfter, ServerLevel level, List<ChunkPos> grid, BlockPos origin) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        final long sliceP50Nanos = attributionAfter.latency().percentileNanos(0.50);
        final int spawnableChunks = countSpawnableChunks(level, grid);
        final long[] byCategory = mobsByCategory(level, grid);
        final long mobsInLevel = Arrays.stream(byCategory).sum();
        final NaturalSpawner.SpawnState lastState = level.getChunkSource().getLastSpawnState();
        System.out.println(sampler.note(profile, after)
                + " spawnWorkerExecution=" + after.map(RegionThreadingDiagnostics::spawnWorkerExecutionEnabled).orElse(false)
                + " spawnWorkerDispatchEffective=" + after.map(RegionThreadingDiagnostics::spawnWorkerDispatchEffective).orElse(false)
                + " spawnChunks=" + grid.size()
                + " spawnableChunks=" + spawnableChunks
                + " playerGatedChunks=" + countPlayerGatedChunks(level, grid)
                + " lastSpawnStateChunks=" + (lastState == null ? -1 : lastState.getSpawnableChunkCount())
                + " lastSpawnStateMonsters=" + (lastState == null ? -1 : lastState.getMobCategoryCounts().getInt(MobCategory.MONSTER))
                + " roomLight=" + roomBrightness(level, origin)
                + " probe=" + spawnProbe(level, origin)
                + " mobsInLevel=" + mobsInLevel
                + " maxMobsPerRegion=" + maxMobsPerRegion(level, grid, after)
                + " regionsWithMobs=" + regionsWithMobs(level, grid, after)
                + " spawnAttributionSlices=" + slices);
        System.out.println("SERVER_BENCH_SPAWN_HEADER " + SPAWN_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_SPAWN_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                grid.size(),
                spawnableChunks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::spawnChunksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::gateRefusedSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::unloadedNeighborhoodSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::deferredUpdateBudgetSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::lateSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::unleasedSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::truncatedSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::skippedSpawnChunks),
                delta(before, after, RegionThreadingDiagnostics::capRefusedSpawns),
                delta(before, after, RegionThreadingDiagnostics::refusedSpawnAdditions),
                byCategory[MobCategory.MONSTER.ordinal()],
                byCategory[MobCategory.CREATURE.ordinal()],
                byCategory[MobCategory.WATER_CREATURE.ordinal()],
                byCategory[MobCategory.WATER_AMBIENT.ordinal()],
                byCategory[MobCategory.AMBIENT.ordinal()],
                byCategory[MobCategory.AXOLOTLS.ordinal()],
                byCategory[MobCategory.UNDERGROUND_WATER_CREATURE.ordinal()],
                byCategory[MobCategory.MISC.ordinal()],
                mobsInLevel,
                delta(before, after, RegionThreadingDiagnostics::deferredEntityAdditionsRecorded),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityAdditionsApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntityAdditions),
                delta(before, after, RegionThreadingDiagnostics::entityAddRejectedOnWorker),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                millisPerSlice(attributionAfter.planNanos() - attributionBefore.planNanos(), slices),
                millisPerSlice(attributionAfter.leaseNanos() - attributionBefore.leaseNanos(), slices),
                millisPerSlice(attributionAfter.awaitNanos() - attributionBefore.awaitNanos(), slices),
                millisPerSlice(attributionAfter.handoffNanos() - attributionBefore.handoffNanos(), slices),
                millisPerSlice(attributionAfter.sliceNanos() - attributionBefore.sliceNanos(), slices),
                sliceP50Nanos / 1_000_000.0,
                slices);
        printRegionMetrics(after);
    }

    /** {@return the chunk of the test structure origin, the centre of the spawning platform} */
    private static ChunkPos originChunk(BlockPos origin) {
        return new ChunkPos(Math.floorDiv(origin.getX(), 16), Math.floorDiv(origin.getZ(), 16));
    }

    /**
     * {@return a compact probe of the spawn facts of one platform position}
     * <p>
     * A dark platform spawns nothing if one link of the vanilla chain is missing - the heightmap does not see the
     * roof, the roof does not reach the light engine, the biome has no monster list, the spawn placement or the
     * spawn rules refuse the position, or the position collides. The probe names the link that failed, so a run
     * without mobs is diagnosable from its own log.
     */
    private static String spawnProbe(ServerLevel level, BlockPos origin) {
        final ChunkPos chunk = new ChunkPos(originChunk(origin).x + 2, originChunk(origin).z);
        final BlockPos pos = new BlockPos(chunk.getMinBlockX() + 8, SPAWN_PLATFORM_HEIGHT + 1, chunk.getMinBlockZ() + 8);
        final LevelChunk levelChunk = level.getChunk(chunk.x, chunk.z);
        final var biome = level.getBiome(pos);
        return "height=" + levelChunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ())
                + ",below=" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock())
                + ",at=" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                + ",sky=" + level.getBrightness(LightLayer.SKY, pos)
                + ",block=" + level.getBrightness(LightLayer.BLOCK, pos)
                + ",biome=" + biome.unwrapKey().map(key -> key.location().toString()).orElse("?")
                + ",mobs=" + level.getChunkSource().getGenerator()
                        .getMobsAt(biome, level.structureManager(), MobCategory.MONSTER, pos).unwrap().size()
                + ",placementOk=" + SpawnPlacements.isSpawnPositionOk(EntityType.ZOMBIE, level, pos)
                + ",rules=" + SpawnPlacements.checkSpawnRules(EntityType.ZOMBIE, level, MobSpawnType.NATURAL, pos, RandomSource.create(1L))
                + ",noCollision=" + level.noCollision(EntityType.ZOMBIE.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
    }

    /**
     * {@return the largest number of platform mobs that stand in one region of the fixed grid}
     * <p>
     * The region slice bounds a region by {@code maxInstancesPerChunk * spawnableChunks / 289}; a region that holds
     * visibly more mobs than that bound is a region whose spawn state did not count the mobs that stand in it, and
     * the measured rate cannot be compared with vanilla before that is fixed.
     */
    private static int maxMobsPerRegion(ServerLevel level, List<ChunkPos> grid, Optional<RegionThreadingDiagnostics> diagnostics) {
        final int[] counts = mobsPerRegion(level, grid, diagnostics);
        int max = 0;
        for (final int count : counts) {
            max = Math.max(max, count);
        }
        return max;
    }

    /** {@return the number of regions of the fixed grid that hold at least one platform mob} */
    private static int regionsWithMobs(ServerLevel level, List<ChunkPos> grid, Optional<RegionThreadingDiagnostics> diagnostics) {
        int regions = 0;
        for (final int count : mobsPerRegion(level, grid, diagnostics)) {
            if (count > 0) {
                ++regions;
            }
        }
        return regions;
    }

    /** {@return the platform mobs per fixed region, indexed by {@code regionX * 64 + regionZ} of the grid} */
    private static int[] mobsPerRegion(ServerLevel level, List<ChunkPos> grid, Optional<RegionThreadingDiagnostics> diagnostics) {
        final int regionSize = Math.max(1, diagnostics.map(RegionThreadingDiagnostics::regionChunkSize).orElse(3));
        final Map<Integer, Integer> counts = new java.util.HashMap<>();
        final int minY = SPAWN_PLATFORM_HEIGHT;
        final int maxY = SPAWN_PLATFORM_HEIGHT + SPAWN_PLATFORM_HEADROOM + 1;
        for (final ChunkPos chunk : grid) {
            final AABB box = new AABB(chunk.getMinBlockX(), minY, chunk.getMinBlockZ(),
                    chunk.getMinBlockX() + 16, maxY, chunk.getMinBlockZ() + 16);
            for (final Mob mob : level.getEntitiesOfClass(Mob.class, box, mob -> true)) {
                final ChunkPos mobChunk = mob.chunkPosition();
                final int key = (Math.floorDiv(mobChunk.x, regionSize) & 63) * 64 + (Math.floorDiv(mobChunk.z, regionSize) & 63);
                counts.merge(key, 1, Integer::sum);
            }
        }
        final int[] values = new int[counts.size()];
        int index = 0;
        for (final int count : counts.values()) {
            values[index++] = count;
        }
        return values;
    }

    /**
     * {@return the number of chunks of {@code grid} a player is close enough to for spawning}
     * <p>
     * This is the second half of the level's spawn gate - the natural spawning ticket is the first - and it is
     * what makes a chunk of the platform spawn at all.
     */
    private static int countPlayerGatedChunks(ServerLevel level, List<ChunkPos> grid) {
        int gated = 0;
        for (final ChunkPos chunk : grid) {
            if (level.getNearestPlayer(chunk.getMinBlockX() + 8.5, SPAWN_PLATFORM_HEIGHT, chunk.getMinBlockZ() + 8.5, -1.0, false) != null) {
                ++gated;
            }
        }
        return gated;
    }

    /**
     * {@return the light of one position of the spawning room}
     * <p>
     * A monster only spawns where the light is zero, so a room whose roof did not reach the light engine reports a
     * positive value here and spawns nothing.
     */
    private static int roomBrightness(ServerLevel level, BlockPos origin) {
        final ChunkPos chunk = originChunk(origin);
        return level.getMaxLocalRawBrightness(
                new BlockPos(chunk.getMinBlockX() + 8, SPAWN_PLATFORM_HEIGHT + 1, chunk.getMinBlockZ() + 8));
    }

    /**
     * {@return the number of chunks of {@code grid} the level allows natural spawning in}
     * <p>
     * This is the area term both cap scopes use: vanilla counts every chunk of the level a player is close enough
     * to for spawning, and the region slice counts the region's share of the same predicate. Dividing the vanilla
     * mob cap by it is how the cap per chunk is compared between the profiles.
     */
    private static int countSpawnableChunks(ServerLevel level, List<ChunkPos> grid) {
        int spawnable = 0;
        for (final ChunkPos chunk : grid) {
            if (level.isNaturalSpawningAllowed(chunk)) {
                ++spawnable;
            }
        }
        return spawnable;
    }

    /**
     * {@return the mobs of every category that stand on the spawning platform, indexed by
     * {@link MobCategory#ordinal()}}
     */
    private static long[] mobsByCategory(ServerLevel level, List<ChunkPos> grid) {
        final long[] counts = new long[MobCategory.values().length];
        final int minY = SPAWN_PLATFORM_HEIGHT;
        final int maxY = SPAWN_PLATFORM_HEIGHT + SPAWN_PLATFORM_HEADROOM + 1;
        for (final ChunkPos chunk : grid) {
            final AABB box = new AABB(chunk.getMinBlockX(), minY, chunk.getMinBlockZ(),
                    chunk.getMinBlockX() + 16, maxY, chunk.getMinBlockZ() + 16);
            for (final Mob mob : level.getEntitiesOfClass(Mob.class, box, mob -> true)) {
                ++counts[mob.getType().getCategory().ordinal()];
            }
        }
        return counts;
    }

    private static void reportRandomTicks(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before,
            RegionChunkTickAttribution.Snapshot attributionBefore, RegionChunkTickAttribution.Snapshot attributionAfter,
            int chunks, int blocks, long placedFingerprint, long endFingerprint, long gameTime) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        final long sliceP50Nanos = attributionAfter.latency().percentileNanos(0.50);
        System.out.println(sampler.note(profile, after)
                + " chunkTickWorkerExecution=" + after.map(RegionThreadingDiagnostics::chunkTickWorkerExecutionEnabled).orElse(false)
                + " randomTickChunks=" + chunks
                + " randomTickBlocks=" + blocks
                + " randomTickSpeed=" + RANDOM_TICK_SPEED
                + " randomTickWindowGameTime=" + gameTime
                + " randomTickBlocksChanged=" + (placedFingerprint != endFingerprint)
                + " blockStateFingerprint=" + endFingerprint
                + " chunkTickAttributionSlices=" + slices);
        System.out.println("SERVER_BENCH_RANDOMTICK_HEADER " + RANDOM_TICK_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_RANDOMTICK_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                chunks,
                blocks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::chunkTicksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::fallbackChunkTicks),
                delta(before, after, RegionThreadingDiagnostics::boundaryChunkTicks),
                delta(before, after, RegionThreadingDiagnostics::unloadedNeighborhoodChunkTicks),
                delta(before, after, RegionThreadingDiagnostics::truncatedChunkTicks),
                delta(before, after, RegionThreadingDiagnostics::unleasedChunkTicks),
                delta(before, after, RegionThreadingDiagnostics::chunkTickLightningStrikes),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                millisPerSlice(attributionAfter.planNanos() - attributionBefore.planNanos(), slices),
                millisPerSlice(attributionAfter.leaseNanos() - attributionBefore.leaseNanos(), slices),
                millisPerSlice(attributionAfter.awaitNanos() - attributionBefore.awaitNanos(), slices),
                millisPerSlice(attributionAfter.handoffNanos() - attributionBefore.handoffNanos(), slices),
                millisPerSlice(attributionAfter.sliceNanos() - attributionBefore.sliceNanos(), slices),
                sliceP50Nanos / 1_000_000.0,
                slices,
                endFingerprint);
        printRegionMetrics(after);
        helper.assertTrue(placedFingerprint != endFingerprint,
                "the random tick workload changed no block state, so no random tick ran");
    }

    /**
     * {@return the force-loaded chunk grid, {@value #GRID_CHUNKS} chunks per axis around {@code origin}}
     */
    private static List<ChunkPos> gridChunks(BlockPos origin) {
        final int baseChunkX = Math.floorDiv(origin.getX(), 16);
        final int baseChunkZ = Math.floorDiv(origin.getZ(), 16);
        final List<ChunkPos> chunks = new ArrayList<>(GRID_CHUNKS * GRID_CHUNKS);
        for (int dx = -GRID_CHUNKS / 2; dx < GRID_CHUNKS / 2; dx++) {
            for (int dz = -GRID_CHUNKS / 2; dz < GRID_CHUNKS / 2; dz++) {
                chunks.add(new ChunkPos(baseChunkX + dx, baseChunkZ + dz));
            }
        }
        return chunks;
    }

    /**
     * {@return the interior chunk columns of the grid}
     * <p>
     * A column is the center chunk of a 3x3 region and an interior chunk of a 9x9 region, so the interior-only
     * block-entity types may tick there for every measured fixed region size.
     */
    private static List<ChunkPos> interiorColumns(BlockPos origin) {
        final List<ChunkPos> columns = new ArrayList<>();
        for (ChunkPos chunk : gridChunks(origin)) {
            if (Math.floorMod(chunk.x, 3) == INTERIOR_CHUNK_RESIDUE && Math.floorMod(chunk.z, 3) == INTERIOR_CHUNK_RESIDUE) {
                columns.add(chunk);
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("the benchmark grid has no interior chunk for the workload columns");
        }
        return columns;
    }

    /**
     * {@return the absolute furnace positions of the workload}
     * <p>
     * The furnaces are distributed over the interior columns of the grid, round-robin, with distinct
     * positions per column and additional vertical layers when a column holds more than one chunk worth.
     * The positions only depend on {@code origin} and {@link #furnaceCount()}, never on the installed region
     * layout, so all measured profiles tick the same furnaces at the same places.
     */
    private static List<BlockPos> furnacePositions(BlockPos origin) {
        final List<ChunkPos> columns = interiorColumns(origin);
        final int count = furnaceCount();
        final List<BlockPos> positions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final ChunkPos column = columns.get(index % columns.size());
            final int slot = index / columns.size();
            final int offsetX = slot & 15;
            final int offsetZ = (slot >> 4) & 15;
            final int layer = slot >> 8;
            positions.add(new BlockPos(column.getMinBlockX() + offsetX, origin.getY() + 2 + 2 * layer, column.getMinBlockZ() + offsetZ));
        }
        return positions;
    }

    /**
     * {@return the number of furnaces of this run, {@value #FURNACE_COUNT} unless
     * {@value #FURNACE_COUNT_PROPERTY} overrides it}
     */
    private static int furnaceCount() {
        return intProperty(FURNACE_COUNT_PROPERTY, FURNACE_COUNT);
    }

    /**
     * {@return the absolute hopper positions of the workload}
     * <p>
     * Every hopper is the middle of a three-block column: a source chest above it and a destination chest
     * below it, so one hopper moves one stack from one container into the other and the vanilla cooldown
     * spreads its transfers over eight ticks. The columns are distributed over the interior chunks of the
     * grid like the furnaces, with two blocks between two columns of one chunk so that no two chests of the
     * workload merge into a double chest. The positions only depend on {@code origin} and
     * {@link #hopperCount()}, never on the installed region layout.
     */
    private static List<BlockPos> hopperPositions(BlockPos origin) {
        final List<ChunkPos> columns = interiorColumns(origin);
        final int count = hopperCount();
        final List<BlockPos> positions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final ChunkPos column = columns.get(index % columns.size());
            final int slot = index / columns.size();
            final int offsetX = (slot % (16 / HOPPER_COLUMN_SPACING)) * HOPPER_COLUMN_SPACING;
            final int offsetZ = (slot / (16 / HOPPER_COLUMN_SPACING) % (16 / HOPPER_COLUMN_SPACING)) * HOPPER_COLUMN_SPACING;
            final int layer = slot / HOPPER_SLOTS_PER_LAYER;
            positions.add(new BlockPos(column.getMinBlockX() + offsetX, origin.getY() + 2 + HOPPER_COLUMN_HEIGHT * layer, column.getMinBlockZ() + offsetZ));
        }
        return positions;
    }

    /**
     * {@return the number of hoppers of this run, {@value #HOPPER_COUNT} unless
     * {@value #HOPPER_COUNT_PROPERTY} overrides it}
     */
    private static int hopperCount() {
        return intProperty(HOPPER_COUNT_PROPERTY, HOPPER_COUNT);
    }

    /**
     * {@return the population of the entity worker diagnostics workload,
     * {@value #ENTITY_WORKER_ZOMBIE_COUNT} unless {@value #ZOMBIE_COUNT_PROPERTY} overrides it, or the
     * villager count when {@value #VILLAGER_COUNT_PROPERTY} runs the villager workload}
     */
    private static int entityWorkerMobCount() {
        if (villagerWorkload()) {
            return intProperty(VILLAGER_COUNT_PROPERTY, ENTITY_WORKER_ZOMBIE_COUNT);
        }
        return intProperty(ZOMBIE_COUNT_PROPERTY, ENTITY_WORKER_ZOMBIE_COUNT);
    }

    /** {@return true when {@value #VILLAGER_COUNT_PROPERTY} selected the villager workload} */
    private static boolean villagerWorkload() {
        final String configured = System.getProperty(VILLAGER_COUNT_PROPERTY);
        return configured != null && !configured.isBlank();
    }

    /**
     * {@return the entity type of the entity worker workload}
     * <p>
     * The villager workload overrides the mob selection, because a villager without its POI blocks would not
     * exercise the audited path. Every other run reads {@value #MOB_PROPERTY} and defaults to the zombie.
     */
    private static EntityType<?> entityWorkerMobType() {
        if (villagerWorkload()) {
            return EntityType.VILLAGER;
        }
        final String configured = System.getProperty(MOB_PROPERTY, "");
        if (configured.isBlank()) {
            return EntityType.ZOMBIE;
        }
        final ResourceLocation id = ResourceLocation.tryParse(configured.trim());
        final EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null) {
            // Fail instead of silently measuring a different population than the row claims.
            throw new IllegalArgumentException("unknown entity type in " + MOB_PROPERTY + ": " + configured);
        }
        return type;
    }

    /**
     * {@return the number of zombies the entity worker workload kills every
     * {@value #DEATH_INTERVAL_TICKS} ticks, {@value #ENTITY_WORKER_ZOMBIE_DEATHS} unless
     * {@value #ZOMBIE_DEATHS_PROPERTY} overrides it}
     */
    private static int entityWorkerZombieDeaths() {
        return intProperty(ZOMBIE_DEATHS_PROPERTY, ENTITY_WORKER_ZOMBIE_DEATHS);
    }

    /**
     * {@return the measured ticks of the soak window, {@value #SOAK_MEASURED_TICKS} unless
     * {@value #TICKS_PROPERTY} overrides it}
     */
    private static int soakMeasuredTicks() {
        return intProperty(TICKS_PROPERTY, SOAK_MEASURED_TICKS);
    }

    /**
     * {@return the zombie population of the soak workload, {@value #SOAK_ZOMBIE_COUNT} unless
     * {@value #ZOMBIE_COUNT_PROPERTY} overrides it}
     */
    private static int soakZombieCount() {
        return intProperty(ZOMBIE_COUNT_PROPERTY, SOAK_ZOMBIE_COUNT);
    }

    /**
     * {@return the number of zombies the soak kills every {@value #DEATH_INTERVAL_TICKS} ticks,
     * {@value #SOAK_ZOMBIE_DEATHS} unless {@value #ZOMBIE_DEATHS_PROPERTY} overrides it}
     */
    private static int soakZombieDeaths() {
        return intProperty(ZOMBIE_DEATHS_PROPERTY, SOAK_ZOMBIE_DEATHS);
    }

    /**
     * {@return false when {@value #ZOMBIE_LOOT_PROPERTY} turns the mob loot rule off for a window}
     */
    private static boolean zombieLootEnabled() {
        final String configured = System.getProperty(ZOMBIE_LOOT_PROPERTY);
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured.trim());
    }

    /**
     * Applies {@value #BLOCK_ENTITY_ADMISSION_CACHE_PROPERTY} to the installed engine and returns the
     * effective setting, so one hopper run measures the cached admission and another the uncached evaluation
     * of the same workload.
     * <p>
     * A run without an installed engine has no block-entity worker slice and nothing to configure; the
     * property then only labels the row.
     */
    private static boolean applyBlockEntityAdmissionCacheSetting() {
        final String configured = System.getProperty(BLOCK_ENTITY_ADMISSION_CACHE_PROPERTY);
        final boolean enabled = configured == null || configured.isBlank() || Boolean.parseBoolean(configured.trim());
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.setBlockEntityAdmissionCacheEnabledForTesting(enabled);
            }
        });
        return enabled;
    }

    /**
     * {@return the integer value of the system property {@code key}, or {@code defaultValue} when it is
     * unset or blank}
     * <p>
     * An unparsable or negative override fails instead of silently measuring the default workload, so a
     * mistyped measurement knob cannot produce a row that looks like a different profile.
     */
    private static int intProperty(String key, int defaultValue) {
        final String configured = System.getProperty(key);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        final int value;
        try {
            value = Integer.parseInt(configured.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " is not an integer: " + configured, exception);
        }
        if (value < 0) {
            throw new IllegalArgumentException(key + " must not be negative: " + value);
        }
        return value;
    }

    /**
     * Force-loads every chunk of the grid and makes it block or entity ticking without a nearby player.
     */
    private static void forceChunks(ServerLevel level, List<ChunkPos> grid, int ticketRadius) {
        for (ChunkPos chunk : grid) {
            level.setChunkForced(chunk.x, chunk.z, true);
            // A plain forced chunk keeps its block entities in the ticker list, but vanilla skips the
            // chunk tick without a nearby player. The force-ticking ticket makes the whole grid tick.
            level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, ticketRadius, chunk, true);
        }
    }

    private static void releaseChunks(ServerLevel level, List<ChunkPos> grid, int ticketRadius) {
        for (ChunkPos chunk : grid) {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, ticketRadius, chunk, true);
            level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    /**
     * Places the furnaces with enough fuel and input to burn and smelt for the whole measurement.
     */
    private static void placeFurnaces(ServerLevel level, List<BlockPos> positions, ExtendedGameTestHelper helper) {
        for (BlockPos pos : positions) {
            level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
        }
        int placed = 0;
        for (BlockPos pos : positions) {
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                // 64 cobblestone need 8 coal; 64 coal keep the furnace lit far beyond the measured ticks.
                furnace.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                furnace.setItem(1, new ItemStack(Items.COAL, 64));
                placed++;
            }
        }
        helper.assertTrue(placed == positions.size(),
                "only " + placed + " of " + positions.size() + " benchmark furnaces were created");
    }

    private static void removeFurnaces(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).is(Blocks.FURNACE)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Places the hopper columns: a chest of source items above every hopper and an empty chest below it.
     * <p>
     * The source chests start with {@value #HOPPER_SOURCE_SLOTS} full stacks, which is more than the window can
     * transfer at the vanilla hopper rate, so the flow never runs dry and the measured ticks all carry the
     * same work.
     */
    private static void placeHoppers(ServerLevel level, List<BlockPos> positions, ExtendedGameTestHelper helper) {
        for (int index = 0; index < positions.size(); index++) {
            final BlockPos pos = positions.get(index);
            if (isHopperItemColumn(index)) {
                // No source chest: the block above stays air, so the hopper reads the item entities the drop
                // driver spawns there instead of a container.
                level.setBlock(pos, Blocks.HOPPER.defaultBlockState(), 3);
            } else {
                level.setBlock(pos.above(), Blocks.CHEST.defaultBlockState(), 3);
                level.setBlock(pos, Blocks.HOPPER.defaultBlockState(), 3);
            }
            level.setBlock(pos.below(), Blocks.CHEST.defaultBlockState(), 3);
        }
        int placed = 0;
        for (int index = 0; index < positions.size(); index++) {
            final BlockPos pos = positions.get(index);
            if (!(level.getBlockEntity(pos) instanceof HopperBlockEntity)) {
                continue;
            }
            if (isHopperItemColumn(index)) {
                placed++;
            } else if (level.getBlockEntity(pos.above()) instanceof ChestBlockEntity source) {
                for (int slot = 0; slot < HOPPER_SOURCE_SLOTS; slot++) {
                    source.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                }
                placed++;
            }
        }
        helper.assertTrue(placed == positions.size(),
                "only " + placed + " of " + positions.size() + " benchmark hopper columns were created");
    }

    /**
     * {@return true when the column at {@code index} is fed with dropped item entities instead of a chest}
     */
    private static boolean isHopperItemColumn(int index) {
        return index % HOPPER_ITEM_COLUMN_EVERY == 0;
    }

    private static void removeHoppers(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).is(Blocks.HOPPER)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * One piston clock unit of the piston workload: a redstone block clock, the sticky piston on each side of
     * it and the stone column each piston moves.
     * <p>
     * The redstone block powers both pistons from the side they do not push into, so one clock write drives two
     * pistons that push in opposite directions. Both are sticky, so the retract pulls the column back and the
     * unit returns to the state it was placed in: a window can run for any number of periods without walking
     * the column out of its space.
     *
     * @param clock         position of the redstone block that clocks the unit
     * @param westPiston    the piston that pushes its column west
     * @param eastPiston    the piston that pushes its column east
     * @param size          blocks in each of the two columns, 1..{@value #PISTON_MAX_SIZE}
     * @param crossesBorder whether the west column of the unit reaches into the region before its own
     */
    private record PistonUnit(BlockPos clock, BlockPos westPiston, BlockPos eastPiston, int size, boolean crossesBorder) {}

    /**
     * {@return the piston clock units of the workload}
     * <p>
     * The layout depends on {@code origin} and the piston count, never on the installed region layout: every
     * column starts on the same block of the 48-block region grid, so all measured profiles move the same
     * pistons at the same absolute positions. The rows alternate between a unit on the first block of its
     * region - whose west column crosses the border into the region before it - and a unit in the middle of its
     * region, so half of the moves of the workload cross a border. The column size cycles through 1..12 over the
     * units, so one window covers every move size a piston can have.
     */
    private static List<PistonUnit> pistonUnits(BlockPos origin) {
        final int unitCount = (pistonCount() + 1) / 2;
        final int baseX = pistonBaseX(origin);
        final int baseZ = (Math.floorDiv(origin.getZ(), 16) - GRID_CHUNKS / 2) * 16 + 8;
        final List<PistonUnit> units = new ArrayList<>(unitCount);
        for (int index = 0; index < unitCount; index++) {
            final int row = index / PISTON_UNITS_PER_ROW;
            final int column = index % PISTON_UNITS_PER_ROW;
            final boolean crossesBorder = pistonBorderRowPeriod() > 0 && row % pistonBorderRowPeriod() == 0;
            final int clockX = baseX + column * PISTON_COLUMN_SPACING + (crossesBorder ? 1 : PISTON_COLUMN_SPACING / 2);
            final int z = baseZ + row * PISTON_ROW_SPACING;
            units.add(new PistonUnit(
                    new BlockPos(clockX, PISTON_HEIGHT, z),
                    new BlockPos(clockX - 1, PISTON_HEIGHT, z),
                    new BlockPos(clockX + 1, PISTON_HEIGHT, z),
                    pistonSize(index),
                    crossesBorder));
        }
        return units;
    }

    /**
     * {@return the x of the first piston column of the workload, on the region grid}
     * <p>
     * A 3x3-chunk region is {@value #PISTON_COLUMN_SPACING} blocks wide, so a multiple of that spacing is the
     * first block of a region for every fixed region size the benchmark measures. The value is the first such
     * block that leaves a whole unit - a column of at most {@value #PISTON_MAX_SIZE} blocks on each side of its
     * clock - inside the force-loaded grid.
     */
    private static int pistonBaseX(BlockPos origin) {
        final int gridFirstX = (Math.floorDiv(origin.getX(), 16) - GRID_CHUNKS / 2) * 16;
        return Math.floorDiv(gridFirstX + 16 + PISTON_COLUMN_SPACING - 1, PISTON_COLUMN_SPACING) * PISTON_COLUMN_SPACING;
    }

    /**
     * Places the clock units: the floor under the workload, the two sticky pistons, their stone columns and the
     * air the redstone block clock is written into by {@link PistonClock}.
     * <p>
     * The floor is what makes the measurement a measurement of the move: the level's heightmaps are recomputed
     * for every block a piston writes, and a column that stands in open sky makes a removal scan down to the
     * terrain under it. With a floor one block under the workload the scan stops immediately, which is the cost
     * a piston of a real build pays as well.
     */
    private static void placePistonUnits(ServerLevel level, List<PistonUnit> units, ExtendedGameTestHelper helper) {
        final BlockState westPiston = Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.WEST)
                .setValue(BlockStateProperties.EXTENDED, false);
        final BlockState eastPiston = Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST)
                .setValue(BlockStateProperties.EXTENDED, false);
        for (PistonUnit unit : units) {
            for (int x = unit.westPiston().getX() - unit.size() - 1; x <= unit.eastPiston().getX() + unit.size() + 1; x++) {
                level.setBlock(new BlockPos(x, PISTON_HEIGHT - 1, unit.clock().getZ()), Blocks.STONE.defaultBlockState(), 3);
            }
            level.setBlock(unit.clock(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(unit.westPiston(), westPiston, 3);
            level.setBlock(unit.eastPiston(), eastPiston, 3);
            for (int index = 1; index <= unit.size(); index++) {
                level.setBlock(unit.westPiston().west(index), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(unit.eastPiston().east(index), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        int placed = 0;
        for (PistonUnit unit : units) {
            final boolean pistons = level.getBlockState(unit.westPiston()).is(Blocks.STICKY_PISTON)
                    && level.getBlockState(unit.eastPiston()).is(Blocks.STICKY_PISTON);
            final boolean westColumn = level.getBlockState(unit.westPiston().west(unit.size())).is(Blocks.STONE);
            final boolean eastColumn = level.getBlockState(unit.eastPiston().east(unit.size())).is(Blocks.STONE);
            if (pistons && westColumn && eastColumn) {
                placed++;
            }
        }
        helper.assertTrue(placed == units.size(),
                "only " + placed + " of " + units.size() + " benchmark piston clock units were created");
    }

    private static void removePistonUnits(ServerLevel level, List<PistonUnit> units) {
        for (PistonUnit unit : units) {
            for (int x = unit.westPiston().getX() - unit.size() - 1; x <= unit.eastPiston().getX() + unit.size() + 1; x++) {
                level.setBlock(new BlockPos(x, PISTON_HEIGHT - 1, unit.clock().getZ()), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(unit.clock(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(unit.westPiston(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(unit.eastPiston(), Blocks.AIR.defaultBlockState(), 3);
            for (int index = 1; index <= PISTON_MAX_SIZE; index++) {
                level.setBlock(unit.westPiston().west(index), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(unit.eastPiston().east(index), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Drives the redstone block of every piston clock unit.
     * <p>
     * The block is placed for the first half of the clock period and removed for the second half, so every
     * piston of the workload extends on the rising edge and retracts on the falling edge. The write runs at
     * {@link ServerTickEvent.Pre}, before the level of the tick ticks, so it never races a region worker batch;
     * it is the only server-thread write the workload itself performs and it is reported as its own count.
     */
    private static final class PistonClock implements Runnable {
        private final ServerLevel level;
        private final List<PistonUnit> units;
        private final int period;
        private boolean powered;
        private long writes;

        private PistonClock(ServerLevel level, List<PistonUnit> units, int period) {
            this.level = level;
            this.units = units;
            this.period = period;
        }

        @Override
        public void run() {
            final boolean next = this.level.getGameTime() % this.period < this.period / 2;
            if (next == this.powered) {
                return;
            }
            this.powered = next;
            final BlockState state = next ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            for (PistonUnit unit : this.units) {
                this.level.setBlock(unit.clock(), state, 3);
                this.writes++;
            }
        }

        /** {@return the clock block writes the driver performed} */
        private long writes() {
            return this.writes;
        }
    }

    /**
     * Prints the measurement of the piston workload: the server tick distribution, the wall time of the block
     * event phase and of one event, and the load that produced them.
     * <p>
     * A piston move runs inside the block event phase, so {@code blockEventMsPerTick} is the server-thread time
     * the moves of the workload serialized, and {@code blockEventMicrosPerMove} is the cost of one move of the
     * size class the workload mixes. {@code movesPerTick} is the number of moves the window produced per tick,
     * so a reader can scale the phase to a different piston count without re-running the benchmark.
     */
    private static void reportPistons(ExtendedGameTestHelper helper, ServerLevel level, TickSampler sampler,
            Optional<RegionThreadingDiagnostics> before, RegionBlockEventAttribution.Snapshot attribution,
            List<PistonUnit> units, int clockTicks, long clockWrites) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final long phases = attribution.phaseCount();
        final long events = attribution.eventCount();
        final long borderUnits = units.stream().filter(PistonUnit::crossesBorder).count();
        final int pistons = units.size() * 2;
        final double movesPerTick = events / (double) phases;
        final double phaseMillisPerTick = attribution.phaseNanos() / 1_000_000.0 / phases;
        final double microsPerMove = attribution.eventNanos() / 1_000.0 / events;
        System.out.println(sampler.note(profile, after)
                + " pistonCount=" + pistons
                + " pistonUnits=" + units.size()
                + " pistonBorderUnits=" + borderUnits
                + " pistonSizes=" + pistonSizeLabel()
                + " pistonClockTicks=" + clockTicks
                + " pistonClockWrites=" + clockWrites
                + " pistonExtended=" + countExtendedPistons(level, units)
                + " pistonColumnStones=" + countColumnStones(level, units)
                + " pistonExpectedStones=" + expectedColumnStones(units)
                + " pistonHeads=" + countPistonHeads(level, units)
                + " writeMicros=" + String.format(Locale.ROOT, "%.3f", measureWriteMicros(level, units.get(0)))
                + " readMicros=" + String.format(Locale.ROOT, "%.3f", measureReadMicros(level, units.get(0)))
                + " pendingBlockEvents=" + pendingBlockEvents(level)
                + " blockEventPhaseMaxMs=" + String.format(Locale.ROOT, "%.3f", attribution.phaseMaxNanos() / 1_000_000.0)
                + " blockEventPhases=" + phases
                + " blockEvents=" + events);
        helper.assertTrue(events > 0,
                "the piston workload produced no block event, so no piston moved: clockWrites=" + clockWrites
                        + " phases=" + phases);
        System.out.println("SERVER_BENCH_PISTON_HEADER " + PISTON_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_PISTON_ROW %s,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%.3f,%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                pistons,
                units.size(),
                borderUnits,
                clockTicks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                phases,
                events,
                movesPerTick,
                phaseMillisPerTick,
                microsPerMove,
                attribution.latency().percentileNanos(0.50) / 1_000.0,
                attribution.latency().percentileNanos(0.95) / 1_000.0,
                attribution.latency().percentileNanos(0.99) / 1_000.0,
                attribution.phaseMaxNanos() / 1_000_000.0,
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount));
    }

    /**
     * {@return the row period of the clock units that push across a region border, {@code 0} for a workload
     * that stays inside its region}
     */
    private static int pistonBorderRowPeriod() {
        return intProperty(PISTON_BORDER_PERIOD_PROPERTY, PISTON_BORDER_ROW_PERIOD);
    }

    /** {@return the size classes the piston workload moves, for the note line of a row} */
    private static String pistonSizeLabel() {
        final int configured = intProperty(PISTON_SIZE_PROPERTY, PISTON_SIZE_MIXED);
        return configured == PISTON_SIZE_MIXED ? "1.." + PISTON_MAX_SIZE : Integer.toString(configured);
    }

    /**
     * {@return the wall time of one block write of the workload, in microseconds}
     * <p>
     * The calibration writes and clears the same position above the floor of the workload's own chunk, so it
     * pays the same heightmap, light and neighbour work a piston write pays. It is the denominator of the move
     * cost: a move of a column of {@code n} blocks writes {@code 2n + 2} positions.
     */
    private static double measureWriteMicros(ServerLevel level, PistonUnit unit) {
        final BlockPos pos = new BlockPos(unit.westPiston().getX() - unit.size() - 1, PISTON_HEIGHT, unit.clock().getZ());
        return timeMicros(32, 512, index -> level.setBlock(pos,
                index % 2 == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3));
    }

    /** {@return the wall time of one block state read of the workload, in microseconds} */
    private static double measureReadMicros(ServerLevel level, PistonUnit unit) {
        final BlockPos pos = new BlockPos(unit.westPiston().getX() - unit.size() - 1, PISTON_HEIGHT, unit.clock().getZ());
        return timeMicros(32, 512, index -> level.getBlockState(pos));
    }

    private static double timeMicros(int warmup, int repetitions, java.util.function.IntConsumer action) {
        for (int index = 0; index < warmup; index++) {
            action.accept(index);
        }
        final long start = System.nanoTime();
        for (int index = 0; index < repetitions; index++) {
            action.accept(index);
        }
        return (System.nanoTime() - start) / 1000.0 / repetitions;
    }

    /** {@return the column blocks of the workload that are still where the unit was placed} */
    private static long countColumnStones(ServerLevel level, List<PistonUnit> units) {
        long stones = 0L;
        for (PistonUnit unit : units) {
            for (int index = 1; index <= unit.size(); index++) {
                if (level.getBlockState(unit.westPiston().west(index)).is(Blocks.STONE)) {
                    stones++;
                }
                if (level.getBlockState(unit.eastPiston().east(index)).is(Blocks.STONE)) {
                    stones++;
                }
            }
        }
        return stones;
    }

    /** {@return the column blocks the workload placed} */
    private static long expectedColumnStones(List<PistonUnit> units) {
        long stones = 0L;
        for (PistonUnit unit : units) {
            stones += 2L * unit.size();
        }
        return stones;
    }

    /** {@return the piston heads or moving pistons in front of the pistons of the workload} */
    private static long countPistonHeads(ServerLevel level, List<PistonUnit> units) {
        long heads = 0L;
        for (PistonUnit unit : units) {
            if (level.getBlockState(unit.westPiston().west(1)).is(Blocks.PISTON_HEAD)) {
                heads++;
            }
            if (level.getBlockState(unit.eastPiston().east(1)).is(Blocks.PISTON_HEAD)) {
                heads++;
            }
        }
        return heads;
    }

    /** {@return the sticky pistons of the workload that are in their extended state} */
    private static long countExtendedPistons(ServerLevel level, List<PistonUnit> units) {
        long extended = 0L;
        for (PistonUnit unit : units) {
            final BlockState state = level.getBlockState(unit.westPiston());
            if (state.is(Blocks.STICKY_PISTON) && state.getValue(BlockStateProperties.EXTENDED)) {
                extended++;
            }
        }
        return extended;
    }

    /** {@return the block events the level still has queued or rescheduled} */
    private static int pendingBlockEvents(ServerLevel level) {
        return ((Collection<?>) storageField(level, "blockEvents")).size()
                + ((Collection<?>) storageField(level, "blockEventsToReschedule")).size();
    }

    /** {@return the configured piston count, rounded up to a whole clock unit} */
    private static int pistonCount() {
        final int configured = intProperty(PISTON_COUNT_PROPERTY, PISTON_COUNT);
        if (configured <= 0) {
            throw new IllegalArgumentException(PISTON_COUNT_PROPERTY + " must be positive: " + configured);
        }
        return configured + (configured & 1);
    }

    /**
     * {@return the column size of the clock unit at {@code index}: the configured size, or the size class of
     * the unit when the workload mixes all of them}
     */
    private static int pistonSize(int index) {
        final int configured = intProperty(PISTON_SIZE_PROPERTY, PISTON_SIZE_MIXED);
        if (configured == PISTON_SIZE_MIXED) {
            return 1 + index % PISTON_MAX_SIZE;
        }
        if (configured > PISTON_MAX_SIZE) {
            throw new IllegalArgumentException(PISTON_SIZE_PROPERTY + " must not exceed " + PISTON_MAX_SIZE + ": " + configured);
        }
        return configured;
    }

    /** {@return the configured clock period, which has to leave room for the two-tick move animation} */
    private static int pistonClockTicks() {
        final int configured = intProperty(PISTON_CLOCK_PROPERTY, PISTON_CLOCK_TICKS);
        if (configured < 4) {
            throw new IllegalArgumentException(PISTON_CLOCK_PROPERTY + " must be at least 4 ticks: " + configured);
        }
        return configured;
    }

    /**
     * {@return the item count of the whole workload: every container slot of every column and every item
     * entity of the level}
     * <p>
     * The count has no cell that decides whether an item is visible. The containers are the fixed part of
     * the workload - the source chest, the hopper and the destination chest of every column - and every slot
     * of them is counted. Every {@link ItemEntity} of the level is counted wherever it is and however it
     * moves, so an item that is still on its way, that waits in a suck box or that drifted out of the box
     * above its hopper is part of the system and can never be read as a lost item.
     * <p>
     * Counting only the exact 1x1x1 box above each hopper did read such an item as lost: an item entity keeps
     * the vanilla spawn impulse, and with gravity off that impulse never decays, so a dropped item rises out
     * of the box within a few ticks whenever the hopper does not absorb it immediately. The workload then
     * reported a loss of up to several hundred items while every item was still in the level.
     */
    private static long countItems(ServerLevel level, List<BlockPos> positions) {
        long total = 0L;
        for (BlockPos pos : positions) {
            total += containerItems(level, pos.above());
            total += containerItems(level, pos);
            total += containerItems(level, pos.below());
        }
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof ItemEntity item) {
                total += item.getItem().getCount();
            }
        }
        return total;
    }

    /**
     * {@return the item count of the containers the hoppers of the workload delivered into}
     */
    private static long countDeliveredItems(ServerLevel level, List<BlockPos> positions) {
        long total = 0L;
        for (BlockPos pos : positions) {
            total += containerItems(level, pos.below());
        }
        return total;
    }

    private static long containerItems(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof Container container)) {
            return 0L;
        }
        long total = 0L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    /**
     * Feeds the item columns of the hopper workload.
     * <p>
     * Every {@value #HOPPER_DROP_INTERVAL_TICKS} ticks one cobblestone item entity is spawned above each item
     * column, inside the suck box of the hopper and above its block, so the hopper absorbs it from its own
     * block-entity tick - the region-scoped item query and the deferred removal of the absorbed entity - and
     * not from the {@code entityInside} callback of the entity tick. The items carry no gravity, so they stay
     * in the suck box until a hopper absorbs them; a hopper on cooldown leaves its item waiting, and the
     * report counts it in the item conservation of the workload.
     */
    private static final class HopperItemDrops implements Runnable {
        private final ServerLevel level;
        private final List<BlockPos> columns;
        private long spawned;
        private int countdown = HOPPER_DROP_INTERVAL_TICKS;

        HopperItemDrops(ServerLevel level, List<BlockPos> columns) {
            this.level = level;
            this.columns = columns;
        }

        /**
         * Drops one item above every item column, in the same interval as the hopper cooldown so the flow of
         * a column stays one item per eight ticks.
         */
        @Override
        public void run() {
            if (this.columns.isEmpty() || --this.countdown > 0) {
                return;
            }
            this.countdown = HOPPER_DROP_INTERVAL_TICKS;
            for (BlockPos pos : this.columns) {
                final ItemEntity item = new ItemEntity(this.level, pos.getX() + 0.5, pos.getY() + HOPPER_DROP_HEIGHT, pos.getZ() + 0.5,
                        new ItemStack(Items.COBBLESTONE, HOPPER_DROP_STACK_SIZE));
                // No gravity: the item has to stay in the suck box of its hopper instead of falling into the
                // hopper block, where the entity tick of the server thread would absorb it.
                item.setNoGravity(true);
                this.level.addFreshEntity(item);
                this.spawned++;
            }
        }

        /**
         * {@return the number of items dropped since the workload was set up}
         * <p>
         * The item count of the workload is taken before the first drop, so this is the exact number of items
         * the system gained and the conservation check has to see them all in the containers or in the item
         * entities that still wait above a hopper.
         */
        long spawned() {
            return this.spawned;
        }

        /** {@return the number of items every drop injected into the system} */
        long injectedItems() {
            return this.spawned * HOPPER_DROP_STACK_SIZE;
        }
    }

    /**
     * The moving part of the entity workload: keeps the zombie population at its size and keeps the item
     * entities moving across chunk boundaries.
     */
    private static final class EntityChurn implements Runnable {
        private final ServerLevel level;
        private final List<Zombie> zombies;
        private final List<ItemEntity> items;
        private final BlockPos origin;
        private final RandomSource random = RandomSource.create(0x5EED5EEDL);
        private int replacementCount;
        private int pushCountdown = ITEM_PUSH_INTERVAL_TICKS;

        EntityChurn(ServerLevel level, List<Zombie> zombies, List<ItemEntity> items, BlockPos origin) {
            this.level = level;
            this.zombies = zombies;
            this.items = items;
            this.origin = origin;
        }

        /**
         * Spawns the initial population spread over the grid.
         */
        void spawnAll(ExtendedGameTestHelper helper) {
            final List<ChunkPos> grid = gridChunks(this.origin);
            for (int index = 0; index < ZOMBIE_COUNT; index++) {
                final ChunkPos chunk = grid.get(index % grid.size());
                this.zombies.add(this.spawnZombie(this.slotPos(chunk, 8, 8)));
            }
            for (int index = 0; index < ITEM_COUNT; index++) {
                final ChunkPos chunk = grid.get(index % grid.size());
                final int slot = index / grid.size();
                final BlockPos pos = this.slotPos(chunk, 2 + 4 * (slot & 3), 2 + 4 * ((slot >> 2) & 3));
                final ItemEntity item = new ItemEntity(this.level, pos.getX() + 0.5, this.origin.getY() + 3.0, pos.getZ() + 0.5, new ItemStack(Items.STONE, 1));
                item.setUnlimitedLifetime();
                item.setDeltaMovement(this.nextHorizontalSpeed(), 0.2, this.nextHorizontalSpeed());
                if (this.level.addFreshEntity(item)) {
                    this.items.add(item);
                }
            }
            helper.assertTrue(this.zombies.size() == ZOMBIE_COUNT, "only " + this.zombies.size() + " of " + ZOMBIE_COUNT + " benchmark zombies were spawned");
            helper.assertTrue(this.items.size() == ITEM_COUNT, "only " + this.items.size() + " of " + ITEM_COUNT + " benchmark item entities were spawned");
        }

        @Override
        public void run() {
            for (int index = 0; index < this.zombies.size(); index++) {
                final Zombie zombie = this.zombies.get(index);
                if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                    continue;
                }
                final BlockPos pos = zombie.blockPosition();
                zombie.discard();
                this.zombies.set(index, this.spawnZombie(pos));
                this.replacementCount++;
            }
            if (--this.pushCountdown <= 0) {
                this.pushCountdown = ITEM_PUSH_INTERVAL_TICKS;
                for (ItemEntity item : this.items) {
                    if (!item.isRemoved()) {
                        item.setDeltaMovement(this.nextHorizontalSpeed(), 0.2, this.nextHorizontalSpeed());
                    }
                }
            }
        }

        void discardAll() {
            for (Zombie zombie : this.zombies) {
                zombie.discard();
            }
            for (ItemEntity item : this.items) {
                item.discard();
            }
            this.items.clear();
        }

        int survivors() {
            int alive = 0;
            for (Zombie zombie : this.zombies) {
                if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                    alive++;
                }
            }
            return alive;
        }

        int itemSurvivors() {
            int alive = 0;
            for (ItemEntity item : this.items) {
                if (!item.isRemoved()) {
                    alive++;
                }
            }
            return alive;
        }

        int replacementCount() {
            return this.replacementCount;
        }

        List<Zombie> zombies() {
            return this.zombies;
        }

        List<ItemEntity> items() {
            return this.items;
        }

        private Zombie spawnZombie(BlockPos pos) {
            final Zombie zombie = new Zombie(EntityType.ZOMBIE, this.level);
            zombie.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, this.random.nextFloat() * 360.0F, 0.0F);
            // Without a nearby player a mob despawns; the measurement needs the population to stay put.
            zombie.setPersistenceRequired();
            this.level.addFreshEntity(zombie);
            return zombie;
        }

        private BlockPos slotPos(ChunkPos chunk, int offsetX, int offsetZ) {
            return new BlockPos(chunk.getMinBlockX() + offsetX, this.origin.getY() + 2, chunk.getMinBlockZ() + offsetZ);
        }

        private double nextHorizontalSpeed() {
            return (this.random.nextDouble() - 0.5) * 1.2;
        }
    }

    /**
     * Prints the measurement of the furnace workload as one CSV row.
     * <p>
     * {@code leasedRegions} is the delta of {@link RegionThreadingDiagnostics#totalRegionTickCount()},
     * which records one region lease per leased region and tick.
     * <p>
     * The five attribution columns are the deltas of {@link RegionBlockEntityTickAttribution} over the same
     * window, divided by the number of measured slices (the {@code sliceSamples} column), so each one is the
     * per-tick wall time of that segment of the server-thread block-entity slice. A segment that never ran in
     * the window reports {@code 0}, and a window without slices reports {@code -1}.
     */
    private static void reportFurnaces(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before, RegionBlockEntityTickAttribution.Snapshot attributionBefore, RegionBlockEntityTickAttribution.Snapshot attributionAfter, int furnaces, int chunks) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        System.out.println(sampler.note(profile, after) + " blockEntityAttributionSlices=" + slices);
        printBlockEntityTickerAttribution("FURNACE", profile, attributionAfter);
        System.out.println("SERVER_BENCH_FURNACE_HEADER " + FURNACE_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_FURNACE_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%s%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                furnaces,
                chunks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::blockEntityTicksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::fallbackBlockEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::totalRegionTickCount),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                millisPerSlice(attributionAfter.planNanos() - attributionBefore.planNanos(), slices),
                millisPerSlice(attributionAfter.leaseNanos() - attributionBefore.leaseNanos(), slices),
                millisPerSlice(attributionAfter.awaitNanos() - attributionBefore.awaitNanos(), slices),
                millisPerSlice(attributionAfter.handoffNanos() - attributionBefore.handoffNanos(), slices),
                millisPerSlice(attributionAfter.sliceNanos() - attributionBefore.sliceNanos(), slices),
                slices,
                workerDispatchColumns(before, after));
    }

    /**
     * Prints the measurement of the hopper workload as one CSV row and asserts its item conservation.
     * <p>
     * {@code itemsConserved} is the invariant that proves the hopper tick neither duplicated nor lost an
     * item: the workload owns every item of its columns, so the count of the start and the end of the
     * measured window has to be equal. {@code itemsDelivered} is the count of the destination chests, which
     * proves the columns really transferred: a workload that never moved an item would leave it at zero.
     * <p>
     * The five attribution columns are the per-tick wall time of the segments of the server-thread
     * block-entity slice, exactly like {@link #reportFurnaces}: the plan collection, the region lease
     * acquisition, the wait for the batch, the deferred work after the barrier and the whole slice.
     */
    private static void reportHoppers(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before,
            RegionBlockEntityTickAttribution.Snapshot attributionBefore, RegionBlockEntityTickAttribution.Snapshot attributionAfter,
            List<BlockPos> hoppers, int chunks, long itemCountStart, long itemsDropped, boolean admissionCache) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final long itemCountEnd = countItems(helper.getLevel(), hoppers);
        final long delivered = countDeliveredItems(helper.getLevel(), hoppers);
        // The workload owns every item it started with plus every item the drop driver spawned, and the drop
        // driver is the only inflow, so the end count of the whole level has to be exactly their sum.
        final long expectedEnd = itemCountStart + itemsDropped;
        final boolean conserved = expectedEnd == itemCountEnd;
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        System.out.println(sampler.note(profile, after)
                + " hopperItemCountStart=" + itemCountStart
                + " hopperItemCountEnd=" + itemCountEnd
                + " hopperItemsDropped=" + itemsDropped
                + " hopperItemsDelivered=" + delivered
                + " hopperItemsConserved=" + conserved
                + " blockEntityAttributionSlices=" + slices);
        System.out.println("SERVER_BENCH_HOPPER_HEADER " + HOPPER_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%b,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%b,%s%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                hoppers.size(),
                chunks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::blockEntityTicksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::fallbackBlockEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::nonTickingBlockEntityChunks),
                delta(before, after, RegionThreadingDiagnostics::entitiesLoadingBlockEntityChunks),
                delta(before, after, RegionThreadingDiagnostics::missingBlockEntityChunks),
                delta(before, after, RegionThreadingDiagnostics::staleBlockEntityChunkSnapshots),
                delta(before, after, RegionThreadingDiagnostics::totalRegionTickCount),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesAllowedByLease),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesTruncatedByLease),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesRejected),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityRemovalsApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntityRemovals),
                delta(before, after, RegionThreadingDiagnostics::repeatedDeferredEntityRemovals),
                itemCountStart,
                itemCountEnd,
                itemsDropped,
                delivered,
                conserved,
                millisPerSlice(attributionAfter.planNanos() - attributionBefore.planNanos(), slices),
                millisPerSlice(attributionAfter.leaseNanos() - attributionBefore.leaseNanos(), slices),
                millisPerSlice(attributionAfter.awaitNanos() - attributionBefore.awaitNanos(), slices),
                millisPerSlice(attributionAfter.handoffNanos() - attributionBefore.handoffNanos(), slices),
                millisPerSlice(attributionAfter.sliceNanos() - attributionBefore.sliceNanos(), slices),
                slices,
                admissionCache,
                workerDispatchColumns(before, after));
        printRegionMetrics(after);
        printHopperDiagnostics(profile, attributionAfter, after);
        helper.assertTrue(conserved,
                "the hopper workload lost or duplicated items: start " + itemCountStart + ", dropped " + itemsDropped + ", end " + itemCountEnd);
        helper.assertTrue(delivered > 0L, "the hopper workload never moved an item");
    }

    /**
     * {@return the worker dispatch columns of one measured window}
     * <p>
     * {@code tasksPerTick} is the number of tasks the worker pool accepted per measured tick - every slice,
     * including the packed ones - and {@code regionsPerPackedTask} the number of regions a packed task held.
     * {@code submissionNanosPerTask} is the wall time the submitting threads spent handing a task to the pool,
     * averaged over the accepted submissions of the window, and {@code wakeUpNanosPerWakeUp} the wall time between
     * a hand-off and the start of the task body, averaged over the hand-offs to a worker that had nothing pending
     * - the wake-up the pool controls, not the wait behind a task the same worker is still running. {@code partialPackFallbacks} counts the packs that
     * could not lease every region they needed and released what they took. A window without an installed engine
     * reports zeros.
     */
    private static String workerDispatchColumns(Optional<RegionThreadingDiagnostics> before, Optional<RegionThreadingDiagnostics> after) {
        final RegionThreadingDiagnostics.WorkerDispatchCounters first = before.map(RegionThreadingDiagnostics::workerDispatch).orElse(NO_WORKER_DISPATCH);
        final RegionThreadingDiagnostics.WorkerDispatchCounters last = after.map(RegionThreadingDiagnostics::workerDispatch).orElse(NO_WORKER_DISPATCH);
        final long submissions = last.submissions() - first.submissions();
        final long packedTasks = last.packedTasks() - first.packedTasks();
        final long packedRegions = last.packedRegions() - first.packedRegions();
        final long submitNanos = last.submitNanos() - first.submitNanos();
        final long wakeNanos = last.wakeNanos() - first.wakeNanos();
        final long idleWakeUps = last.idleWakeUps() - first.idleWakeUps();
        final long fallbacks = last.partialPackFallbacks() - first.partialPackFallbacks();
        final long steals = last.steals() - first.steals();
        return String.format(Locale.ROOT, "%.2f,%.2f,%d,%d,%d,%.1f,%.1f,%d,%.2f",
                submissions / (double) MEASURED_TICKS,
                packedTasks == 0L ? 0.0 : packedRegions / (double) packedTasks,
                submissions,
                packedTasks,
                packedRegions,
                submissions == 0L ? 0.0 : submitNanos / (double) submissions,
                idleWakeUps == 0L ? 0.0 : wakeNanos / (double) idleWakeUps,
                fallbacks,
                steals / (double) MEASURED_TICKS);
    }

    /**
     * {@return the per-tick average of {@code nanos} over {@code slices} in milliseconds, or {@code -1} when
     * the window recorded no slice}
     */
    private static double millisPerSlice(long nanos, long slices) {
        return slices <= 0L ? -1.0 : nanos / (double) slices / 1_000_000.0;
    }

    /**
     * Prints the decomposition of the measured window that the hopper CSV row cannot carry: what the worker
     * groups of the block-entity slice did, how the handoff splits into its steps, and why tickers ticked on the
     * server thread instead of a worker.
     * <p>
     * The accumulators are reset when the measured window starts and the snapshot is taken when it ends, so the
     * snapshot is the total of the window and every per-tick value divides by the window's slice count. A
     * profile without a worker batch (region threading off) records no slice and reports {@code -1} for every
     * per-tick value, which is how a missing measurement is told apart from a zero one.
     * <p>
     * The group distribution separates the two ways a barrier can be the limit: a batch whose groups are uneven
     * reports a group coefficient of variation above zero and a slowest group well above the mean, while a batch
     * whose groups are balanced but whose workers are saturated reports a worker utilization near one and a
     * slowest group near the ideal share of one worker. {@code packingRatio} is the slowest group divided by the
     * equal share of one worker, so a perfectly packed batch reports about one. {@code tickerMsPerTick} is the
     * part of the group wall time the tickers spent inside their own tick and {@code wrapperMsPerTick} the rest,
     * so a worker that runs slower than the same work inline is not confused with a wrapper that became
     * expensive. {@code awaitPollsPerTick} and {@code awaitPollNs} are the barrier condition evaluations per
     * tick and the await time each of them accounts for, which tells a wait that parks from one that spins.
     */
    private static void printHopperDiagnostics(String profile, RegionBlockEntityTickAttribution.Snapshot attribution, Optional<RegionThreadingDiagnostics> diagnostics) {
        final long slices = attribution.sliceCount();
        final int workers = diagnostics.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0);
        final RegionBlockEntityTickAttribution.GroupDistribution groups = attribution.groups();
        final RegionBlockEntityTickAttribution.HandoffSteps handoff = attribution.handoffSteps();
        final double meanGroupNanos = mean(groups.nanos(), groups.groups());
        final double meanTickers = mean(groups.tickers(), groups.groups());
        final double workerBusyMean = mean(groups.nanos(), (long) Math.max(workers, 1) * Math.max(slices, 1L));
        final double workerBusyMax = groups.workers().stream().mapToLong(RegionBlockEntityTickAttribution.WorkerLoad::nanos).max().orElse(0L) / (double) Math.max(slices, 1L);
        final double workerBusyMin = groups.workers().stream().mapToLong(RegionBlockEntityTickAttribution.WorkerLoad::nanos).min().orElse(0L) / (double) Math.max(slices, 1L);
        System.out.println("SERVER_BENCH_HOPPER_GROUPS_HEADER profile,slices,workers,groupsPerTick,emptyGroupsPerTick,idleGroupsPerTick,meanTickersPerGroup,maxTickersPerGroup,tickerCv,meanGroupMs,groupP50Ms,groupP95Ms,groupP99Ms,maxGroupMs,groupCv,sumGroupMsPerTick,sumGroupMsPerTickClean,tickerMsPerTick,wrapperMsPerTick,tickerUsPerTicker,workerBusyMinMs,workerBusyMeanMs,workerBusyMaxMs,workerBusyCv,packingRatio,workerUtilization,awaitPollsPerTick,awaitPollNs,awaitCpuRatio,batchMaxGroupMs,awaitMinusBatchMaxMs,makespanOverIdeal,submitMs,dispatchMs,spanMs,tailMs,workerCpuMsPerTick,cpuOverWall,retireMsPerTick,stalledGroupsPerTick,perWorkerBusyMs");
        System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_GROUPS %s,%d,%d,%.2f,%.2f,%.2f,%.1f,%d,%.3f,%.4f,%.4f,%.4f,%.4f,%.4f,%.3f,%.4f,%.4f,%.4f,%.4f,%.3f,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.1f,%.1f,%.3f,%.4f,%.4f,%.4f,%.3f,%.4f,%.4f,%.4f,%.4f,%.3f,%.4f,%.2f,%s%n",
                profile,
                slices,
                workers,
                perTick(groups.groups(), slices),
                perTick(groups.emptyGroups(), slices),
                perTick(groups.idleGroups(), slices),
                meanTickers,
                groups.maxTickers(),
                coefficientOfVariation(groups.tickers(), groups.tickersSquared(), groups.groups()),
                meanGroupNanos / 1_000_000.0,
                groups.latency().percentileNanos(0.50) / 1_000_000.0,
                groups.latency().percentileNanos(0.95) / 1_000_000.0,
                groups.latency().percentileNanos(0.99) / 1_000_000.0,
                groups.maxNanos() / 1_000_000.0,
                coefficientOfVariation(groups.nanos(), groups.nanosSquared(), groups.groups()),
                millisPerSlice(groups.nanos(), slices),
                millisPerSlice(groups.nanos() - groups.stalledNanos(), slices),
                millisPerSlice(groups.tickerNanos(), slices),
                millisPerSlice(groups.nanos() - groups.tickerNanos(), slices),
                groups.tickerCount() <= 0L ? -1.0 : groups.tickerNanos() / (double) groups.tickerCount() / 1_000.0,
                workerBusyMin / 1_000_000.0,
                workerBusyMean / 1_000_000.0,
                workerBusyMax / 1_000_000.0,
                spread(groups.workers(), slices),
                ratio(groups.maxNanos(), meanGroupNanos * workers),
                ratio(groups.nanos(), (double) Math.max(workers, 1) * attribution.awaitNanos()),
                perTick(attribution.awaitPolls(), slices),
                attribution.awaitPolls() <= 0L ? -1.0 : attribution.awaitNanos() / (double) attribution.awaitPolls(),
                ratio(attribution.awaitCpuNanos(), (double) attribution.awaitNanos()),
                millisPerSlice(attribution.batchMakespanNanos(), slices),
                millisPerSlice(attribution.awaitNanos() - attribution.batchMakespanNanos(), slices),
                ratio(attribution.batchMakespanNanos(), (double) slices * meanGroupNanos * workers),
                millisPerSlice(attribution.batchSubmitNanos(), slices),
                millisPerSlice(attribution.batchDispatchNanos(), slices),
                millisPerSlice(attribution.batchSpanNanos(), slices),
                millisPerSlice(attribution.batchTailNanos(), slices),
                millisPerSlice(groups.cpuNanos(), slices),
                ratio(groups.cpuNanos(), (double) groups.nanos()),
                millisPerSlice(groups.retireNanos(), slices),
                perTick(groups.stalledGroups(), slices),
                perWorkerBusy(groups.workers(), slices));
        System.out.println("SERVER_BENCH_HOPPER_HANDOFF_HEADER profile,slices,handoffMs,removalMs,removalsPerTick,fallbackMs,fallbackTickersPerTick,mutationMs,mutationsPerTick,finishMs,otherMs");
        System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_HANDOFF %s,%d,%.4f,%.4f,%.2f,%.4f,%.2f,%.4f,%.2f,%.4f,%.4f%n",
                profile,
                slices,
                millisPerSlice(attribution.handoffNanos(), slices),
                millisPerSlice(handoff.removalNanos(), slices),
                perTick(handoff.removalCount(), slices),
                millisPerSlice(handoff.fallbackNanos(), slices),
                perTick(handoff.fallbackTickers(), slices),
                millisPerSlice(handoff.mutationNanos(), slices),
                perTick(handoff.mutationCount(), slices),
                millisPerSlice(handoff.finishNanos(), slices),
                millisPerSlice(attribution.handoffNanos() - handoff.removalNanos() - handoff.fallbackNanos() - handoff.mutationNanos() - handoff.finishNanos(), slices));
        System.out.println("SERVER_BENCH_HOPPER_FALLBACK_HEADER profile,slices,unsafeRegionTickersPerTick,unresolvableRegionTickersPerTick,staleSnapshotTickersPerTick,staleAdmissionTickersPerTick,chunkUnavailableTickersPerTick,totalTickersPerTick");
        System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_FALLBACK %s,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                profile,
                slices,
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION), slices),
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNRESOLVABLE_REGION), slices),
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT), slices),
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION), slices),
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.CHUNK_UNAVAILABLE), slices),
                perTick(attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION)
                        + attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.UNRESOLVABLE_REGION)
                        + attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT)
                        + attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION)
                        + attribution.fallbackTickers(RegionBlockEntityTickAttribution.FallbackReason.CHUNK_UNAVAILABLE), slices));
        printBlockEntityTickerAttribution("HOPPER", profile, attribution);
        printHopperRegionSpread(diagnostics);
    }

    /**
     * Prints the split of the worker-side block-entity time into the per-ticker parts and the group wrapper.
     * <p>
     * The ticker columns are microseconds per ticker: the admission checks, the reservation of the block-write
     * effects, the entry and exit of the admission scope and the body itself, which together with the reservation
     * are the whole ticker loop. {@code queryUsPerTicker} is the part of the body the region-scoped entity query
     * took - the worker's replacement for the level-wide entity section index - and
     * {@code deferredRegistrationUsPerTicker} the part the block writes deferred. The group columns are the work
     * the slice adds around the ticker loop, per group. {@code wrapperMsPerTick} is what is left of the group wall
     * time after the tickers and those group parts: the dispatch, the batch bookkeeping and the completion of the
     * groups, i.e. the part of the worker time that is neither a ticker nor a region.
     */
    private static void printBlockEntityTickerAttribution(String marker, String profile, RegionBlockEntityTickAttribution.Snapshot attribution) {
        final long slices = attribution.sliceCount();
        final RegionBlockEntityTickAttribution.TickerAttribution tickers = attribution.tickers();
        final RegionBlockEntityTickAttribution.GroupDistribution groups = attribution.groups();
        final long wrapperNanos = groups.nanos() - groups.tickerNanos() - tickers.admissionNanos()
                - tickers.reservationNanos() - tickers.ownershipScopeNanos() - tickers.groupPrepareNanos()
                - tickers.groupEndNanos() - groups.retireNanos();
        System.out.println("SERVER_BENCH_" + marker + "_TICKER_HEADER profile,slices,tickersPerTick,groupsPerTick,admissionUsPerTicker,reserveUsPerTicker,scopeUsPerTicker,queryUsPerTicker,queriesPerTicker,deferredRegistrationUsPerTicker,registrationsPerTicker,bodyUsPerTicker,tickerTotalUsPerTicker,tickerMsPerTick,ownershipScopeUsPerGroup,prepareUsPerGroup,endUsPerGroup,retireMsPerTick,wrapperMsPerTick,wrapperUsPerTicker");
        System.out.printf(Locale.ROOT, "SERVER_BENCH_" + marker + "_TICKER_ROW %s,%d,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f,%.3f,%.3f,%.3f,%.4f,%.4f,%.3f%n",
                profile,
                slices,
                perUnit(tickers.tickers(), slices),
                perUnit(groups.groups(), slices),
                microsPerUnit(tickers.admissionNanos(), tickers.tickers()),
                microsPerUnit(tickers.reservationNanos(), tickers.tickers()),
                microsPerUnit(tickers.scopeNanos(), tickers.tickers()),
                microsPerUnit(tickers.queryNanos(), tickers.tickers()),
                perUnit(tickers.queries(), tickers.tickers()),
                microsPerUnit(tickers.deferredRegistrationNanos(), tickers.tickers()),
                perUnit(tickers.deferredRegistrations(), tickers.tickers()),
                microsPerUnit(tickers.bodyNanos(), tickers.tickers()),
                microsPerUnit(groups.tickerNanos(), tickers.tickers()),
                millisPerSlice(groups.tickerNanos(), slices),
                microsPerUnit(tickers.ownershipScopeNanos(), groups.groups()),
                microsPerUnit(tickers.groupPrepareNanos(), groups.groups()),
                microsPerUnit(tickers.groupEndNanos(), groups.groups()),
                millisPerSlice(groups.retireNanos(), slices),
                millisPerSlice(wrapperNanos, slices),
                microsPerUnit(wrapperNanos, tickers.tickers()));
    }

    /** {@return {@code nanos} per {@code units} in microseconds, or {@code -1} when nothing was measured} */
    private static double microsPerUnit(long nanos, long units) {
        return units <= 0L ? -1.0 : nanos / (double) units / 1_000.0;
    }

    /** {@return {@code count} per {@code units}, or {@code -1} when nothing was measured} */
    private static double perUnit(long count, long units) {
        return units <= 0L ? -1.0 : count / (double) units;
    }

    /**
     * Prints the mean region MSPT of the window and its spread, so a workload whose regions carry uneven load is
     * visible in one line.
     */
    private static void printHopperRegionSpread(Optional<RegionThreadingDiagnostics> diagnostics) {
        diagnostics.ifPresent(current -> {
            final List<RegionTickSnapshot> regions = current.regionTicks().stream().filter(RegionTickSnapshot::hasSamples).toList();
            final double mean = regions.stream().mapToDouble(RegionTickSnapshot::msptAvg).average().orElse(0.0);
            final double max = regions.stream().mapToDouble(RegionTickSnapshot::msptAvg).max().orElse(0.0);
            final double variance = regions.stream().mapToDouble(region -> Math.pow(region.msptAvg() - mean, 2)).average().orElse(0.0);
            System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_REGIONS %s,%d,%.4f,%.4f,%.3f,%d%n",
                    profileName(diagnostics),
                    regions.size(),
                    mean,
                    max,
                    mean <= 0.0 ? -1.0 : Math.sqrt(variance) / mean,
                    current.regionMetrics().regionsBehind());
        });
    }

    /** {@return {@code count} per measured tick, or {@code -1} when the window recorded no slice} */
    private static double perTick(long count, long slices) {
        return slices <= 0L ? -1.0 : count / (double) slices;
    }

    /** {@return {@code total / count}, or zero when nothing was counted} */
    private static double mean(long total, long count) {
        return count <= 0L ? 0.0 : total / (double) count;
    }

    /** {@return {@code numerator / denominator}, or {@code -1} when the denominator is not positive} */
    private static double ratio(long numerator, double denominator) {
        return denominator <= 0.0 ? -1.0 : numerator / denominator;
    }

    /**
     * {@return the coefficient of variation of a distribution whose count, sum and squared sum are known, or
     * {@code -1} when the mean is not positive}
     */
    private static double coefficientOfVariation(long sum, double squaredSum, long count) {
        final double mean = mean(sum, count);
        if (mean <= 0.0) {
            return -1.0;
        }
        return Math.sqrt(Math.max(squaredSum / count - mean * mean, 0.0)) / mean;
    }

    /**
     * {@return the coefficient of variation of the per-tick busy time of the worker threads, or {@code -1} when
     * no worker ran a group}
     */
    private static double spread(List<RegionBlockEntityTickAttribution.WorkerLoad> workers, long slices) {
        if (workers.isEmpty() || slices <= 0L) {
            return -1.0;
        }
        final double mean = workers.stream().mapToDouble(worker -> worker.nanos() / (double) slices).average().orElse(0.0);
        if (mean <= 0.0) {
            return -1.0;
        }
        final double variance = workers.stream().mapToDouble(worker -> Math.pow(worker.nanos() / (double) slices - mean, 2)).average().orElse(0.0);
        return Math.sqrt(variance) / mean;
    }

    /** {@return the per-tick group wall time of every worker thread that ran a group} */
    private static String perWorkerBusy(List<RegionBlockEntityTickAttribution.WorkerLoad> workers, long slices) {
        final StringBuilder busy = new StringBuilder();
        for (final RegionBlockEntityTickAttribution.WorkerLoad worker : workers) {
            if (busy.length() > 0) {
                busy.append(';');
            }
            busy.append(worker.thread()).append('=').append(String.format(Locale.ROOT, "%.4f", perTick(worker.nanos(), slices) / 1_000_000.0));
        }
        return busy.length() == 0 ? "none" : busy.toString();
    }

    private static void reportEntities(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before, EntityChurn churn, int chunks) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final List<Entity> tracked = new ArrayList<>(churn.zombies());
        tracked.addAll(churn.items());
        final int mismatches = regionMismatches(helper.getLevel(), tracked);
        System.out.println(sampler.note(profile, after)
                + " zombieSurvivors=" + churn.survivors() + "/" + ZOMBIE_COUNT
                + " itemSurvivors=" + churn.itemSurvivors() + "/" + ITEM_COUNT
                + " zombieReplacements=" + churn.replacementCount()
                + " regionMismatches=" + mismatches);
        System.out.println("SERVER_BENCH_ENTITY_HEADER " + ENTITY_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_ENTITY_ROW %s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                ZOMBIE_COUNT,
                ITEM_COUNT,
                chunks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::entityTicksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::fallbackEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::deferredEntitySectionUpdatesApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntitySectionUpdates),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                churn.replacementCount(),
                mismatches);
        printRegionMetrics(after);
    }

    /**
     * Prints the per-region MSPT and TPS of the measured window in the form the region threading command
     * reports them, so a benchmark run also shows which region was slow and whether it kept up.
     */
    private static void printRegionMetrics(Optional<RegionThreadingDiagnostics> diagnostics) {
        diagnostics.ifPresent(current -> {
            System.out.println(RegionMetricsReport.summaryLine(current.regionMetrics()));
            RegionMetricsReport.listingLines(current.regionTicks(), REGION_METRIC_SAMPLE_LIMIT).forEach(System.out::println);
        });
    }

    /**
     * Spawns {@code count} zombies of the entity workload, spread over the grid.
     * <p>
     * The zombies keep their vanilla AI and carry nothing, so the measured operations are the ones a plain
     * {@code Zombie.tick} reaches: despawn, collisions, pathfinding, target selection and movement.
     */
    private static void spawnEntityWorkerMobs(ServerLevel level, List<ChunkPos> grid, BlockPos origin, List<Mob> mobs, ExtendedGameTestHelper helper, int count) {
        final RandomSource random = RandomSource.create(0x5EED5EEDL);
        for (int index = 0; index < count; index++) {
            final ChunkPos chunk = grid.get(index % grid.size());
            final int slot = index / grid.size();
            final BlockPos pos = new BlockPos(
                    chunk.getMinBlockX() + ENTITY_WORKER_SLOT_ORIGIN + ENTITY_WORKER_SLOT_SPACING * (slot % ENTITY_WORKER_SLOTS_PER_EDGE),
                    origin.getY() + 2,
                    chunk.getMinBlockZ() + ENTITY_WORKER_SLOT_ORIGIN + ENTITY_WORKER_SLOT_SPACING * (slot / ENTITY_WORKER_SLOTS_PER_EDGE));
            if (villagerWorkload()) {
                // Each villager of the workload gets its own platform, bed and workstation, because a bed and
                // a workstation are one-ticket POIs: four villagers on one spot would leave three of them
                // without a POI to claim and the workload would measure idle brains.
                placeVillagerPoiBlocks(level, pos);
            }
            final Mob mob = newEntityWorkerMob(level, pos, random);
            helper.assertTrue(level.addFreshEntity(mob), "the entity workload could not spawn a benchmark mob");
            mobs.add(mob);
        }
    }

    /**
     * Spawns the zombie population of the soak and the entity workload.
     * <p>
     * Those two workloads always measure a zombie population; the entity worker workload selects its mob with
     * {@value #MOB_PROPERTY} and spawns it through {@link #spawnEntityWorkerMobs}.
     */
    private static void spawnZombies(ServerLevel level, List<ChunkPos> grid, BlockPos origin, List<? super Zombie> zombies, ExtendedGameTestHelper helper, int count) {
        final RandomSource random = RandomSource.create(0x5EED5EEDL);
        for (int index = 0; index < count; index++) {
            final ChunkPos chunk = grid.get(index % grid.size());
            final Zombie zombie = newZombie(level, new BlockPos(chunk.getMinBlockX() + 8, origin.getY() + 2, chunk.getMinBlockZ() + 8), random);
            helper.assertTrue(level.addFreshEntity(zombie), "the entity workload could not spawn a benchmark zombie");
            zombies.add(zombie);
        }
    }

    /**
     * {@return one benchmark zombie at {@code pos}, not yet added to the level}
     */
    private static Zombie newZombie(ServerLevel level, BlockPos pos, RandomSource random) {
        final Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
        zombie.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        zombie.setPersistenceRequired();
        return zombie;
    }

    /**
     * {@return one mob of the workload at {@code pos}, not yet added to the level}
     * <p>
     * Without a nearby player a mob despawns; the diagnostic needs the population to stay put.
     */
    private static Mob newEntityWorkerMob(ServerLevel level, BlockPos pos, RandomSource random) {
        final Entity spawned = entityWorkerMobType().create(level);
        if (!(spawned instanceof Mob mob)) {
            throw new IllegalStateException("the entity worker workload needs a mob, got " + entityWorkerMobType());
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        mob.setPersistenceRequired();
        return mob;
    }

    /**
     * Places a bed and a workstation next to a villager of the workload, so its tick takes both POIs and the
     * POI path the per-type policy pins really runs during the window.
     */
    private static void placeVillagerPoiBlocks(ServerLevel level, BlockPos villagerPos) {
        // The workload spawns far above the world terrain, so every villager gets its own small platform to
        // stand and path on; without it the population falls into the terrain and never reaches its POI.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(villagerPos.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        final BlockPos foot = villagerPos.offset(-1, 0, 0);
        level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(foot.south(), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD));
        level.setBlockAndUpdate(villagerPos.offset(1, 0, 0), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
    }

    /**
     * Drives the zombie deaths of the entity worker workload.
     * <p>
     * A scheduled zombie is hurt from inside its own tick, so the death, the loot spawn of the death and the
     * removal of the dying entity all run on the thread that ticks the entity: the region worker in a worker
     * profile and the server thread in the reference profile. The loot spawn is the entity addition the
     * ownership lease defers: the worker constructs the item entity inside its leased region, records the
     * admission and the batch barrier admits it on the server thread, so a driven death reports one deferred
     * admission next to the deferred removal twenty ticks later, and no rejected entity tick. A death whose
     * loot lands outside the leased region keeps the storage refusal and counts one
     * {@code entityAddRejectedOnWorker}. The population is kept at its size by replacing a dead or dying
     * zombie on the server thread with a fresh one at the same position, so every profile ticks the same
     * number of entities and the row stays comparable with the runs that drive no deaths at all.
     */
    private static final class ZombieDeathDriver implements Runnable {
        private final ServerLevel level;
        private final List<Mob> mobs;
        /** Mobs whose death is due at their next tick; the identity set is written by workers and the server thread. */
        private final Set<Mob> scheduled = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        private final int deathsPerInterval;
        private final RandomSource random = RandomSource.create(0xDEADBEEFL);
        private int intervalCountdown = DEATH_INTERVAL_TICKS;
        private int nextSlot;
        private int deaths;
        private int replacements;
        private int removedReplacements;
        private int failedReplacements;
        private int deathsAtWindowStart;
        private int replacementsAtWindowStart;
        private int removedReplacementsAtWindowStart;
        private int failedReplacementsAtWindowStart;

        ZombieDeathDriver(ServerLevel level, List<Mob> mobs, int deathsPerInterval) {
            this.level = level;
            this.mobs = mobs;
            this.deathsPerInterval = deathsPerInterval;
        }

        /**
         * Schedules the deaths of this interval and replaces the zombies that died. Runs on the server thread
         * at the head of the server tick, so a zombie scheduled here is ticked later in the same tick.
         */
        @Override
        public void run() {
            this.replaceDeadMobs();
            if (this.deathsPerInterval <= 0 || this.mobs.isEmpty()) {
                return;
            }
            if (--this.intervalCountdown > 0) {
                return;
            }
            this.intervalCountdown = DEATH_INTERVAL_TICKS;
            for (int index = 0; index < this.deathsPerInterval; index++) {
                final Mob mob = this.mobs.get(this.nextSlot++ % this.mobs.size());
                if (!mob.isRemoved() && !mob.isDeadOrDying()) {
                    this.scheduled.add(mob);
                }
            }
        }

        /**
         * Hurts the scheduled zombie inside its own tick, on the thread that ticks it. The vanilla death runs
         * the loot spawn and marks the entity dead; the entity then removes itself twenty ticks later, also on
         * the ticking thread, which is exactly the gameplay death the removal deferral has to carry.
         */
        @SubscribeEvent
        public void onEntityTickPre(EntityTickEvent.Pre event) {
            if (!(event.getEntity() instanceof Mob mob) || !this.scheduled.remove(mob)) {
                return;
            }
            if (mob.isRemoved() || mob.isDeadOrDying()) {
                return;
            }
            this.deaths++;
            mob.hurt(mob.damageSources().generic(), Float.MAX_VALUE);
        }

        private void replaceDeadMobs() {
            for (int index = 0; index < this.mobs.size(); index++) {
                final Mob mob = this.mobs.get(index);
                if (!mob.isRemoved() && !mob.isDeadOrDying()) {
                    continue;
                }
                final boolean removed = mob.isRemoved();
                final Mob replacement = newEntityWorkerMob(this.level, mob.blockPosition(), this.random);
                if (!this.level.addFreshEntity(replacement)) {
                    this.failedReplacements++;
                    continue;
                }
                this.mobs.set(index, replacement);
                this.replacements++;
                if (removed) {
                    this.removedReplacements++;
                }
            }
        }

        /**
         * Marks the start of the measured window, so the reported deaths and replacements are the ones the
         * measured ticks produced and stay comparable with the counters of the same window.
         */
        void markMeasuredWindowStart() {
            this.deathsAtWindowStart = this.deaths;
            this.replacementsAtWindowStart = this.replacements;
            this.removedReplacementsAtWindowStart = this.removedReplacements;
            this.failedReplacementsAtWindowStart = this.failedReplacements;
        }

        int deathsInWindow() {
            return this.deaths - this.deathsAtWindowStart;
        }

        int replacementsInWindow() {
            return this.replacements - this.replacementsAtWindowStart;
        }

        /** Replacements of a zombie that was already removed, i.e. a removal that completed without a driven death. */
        int removedReplacementsInWindow() {
            return this.removedReplacements - this.removedReplacementsAtWindowStart;
        }

        int failedReplacementsInWindow() {
            return this.failedReplacements - this.failedReplacementsAtWindowStart;
        }
    }

    /**
     * Removes the workload, rebuilds the configured engine and releases the forced grid.
     * <p>
     * Runs from the measured window and from the end listener, so a failing or timing-out test never leaves
     * the diagnostic engine installed or the grid forced.
     */
    private static void cleanupEntityWorkerWindow(ServerLevel level, List<ChunkPos> grid, List<Mob> mobs, GameRules.BooleanValue daylight, boolean previousDaylight, GameRules.BooleanValue mobLoot, boolean previousMobLoot, Optional<InstalledEngine> replaced) {
        RegionEntityTickAttribution.setEnabled(false);
        for (Mob mob : mobs) {
            mob.discard();
        }
        mobs.clear();
        replaced.ifPresent(ServerLoadBenchmarkGameTests::restoreConfiguredEngine);
        daylight.set(previousDaylight, level.getServer());
        mobLoot.set(previousMobLoot, level.getServer());
        releaseChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS);
    }

    /**
     * Replaces the installed engine with the diagnostics engine of this run.
     * <p>
     * The diagnostic engine has the same layout, worker count and slices as the configured engine, but
     * region-owned entity storage and, unless {@value #ENTITY_WORKER_TICKS_PROPERTY} turned it off, entity
     * tick worker execution enabled. The swap runs on the server thread outside any batch:
     * {@link RegionThreadingManager#clear()} shuts the configured engine down before the diagnostic one is
     * installed.
     *
     * @param entityTickWorkers whether entity ticks execute on the region workers, or stay on the server
     *                          thread for the reference measurement
     * @return the flags and layout of the replaced engine, so the configured engine can be rebuilt
     *         afterwards, or empty when no engine is installed: the workload then ticks its entities
     *         inline on the server thread, which is the server-thread reference row
     */
    private static Optional<InstalledEngine> installEntityWorkerEngine(EntityWorkerBlockers blockers, boolean entityTickWorkers) {
        final RegionThreading installed = RegionThreadingManager.currentOrNull();
        if (installed == null) {
            // Region threading is off for this run: there is no engine to replace, and the workload ticks
            // its entities inline on the server thread exactly like the vanilla level tick.
            return Optional.empty();
        }
        if (!(installed instanceof RegionThreadingEngine configured)) {
            throw new IllegalStateException("the entity worker diagnostics need the native region threading engine, got " + installed.getClass().getName());
        }
        final InstalledEngine replaced = new InstalledEngine(configured.diagnostics(), configured.regionLayout());
        RegionThreadingManager.clear();
        final RegionThreadingEngine diagnostic = replaced.rebuild(entityTickWorkers, RegionEntityStorageMode.REGION_OWNED);
        // The production install declares no per-type entity policy: the POI storage is partitioned per
        // region, so every measured type runs on its region worker.
        diagnostic.setEntityTickFailureObserverForTesting(blockers::record);
        RegionThreadingManager.install(diagnostic);
        return Optional.of(replaced);
    }

    /**
     * {@return true when the diagnostic engine ticks entities on the region workers}
     */
    private static boolean entityTickWorkers() {
        return !"false".equalsIgnoreCase(System.getProperty(ENTITY_WORKER_TICKS_PROPERTY));
    }

    /**
     * Rebuilds the configured engine from the flags captured before the diagnostic window, so the rest of
     * the server run keeps the configured entity storage mode and entity tick policy.
     */
    private static void restoreConfiguredEngine(InstalledEngine replaced) {
        RegionThreadingManager.clear();
        final RegionThreadingDiagnostics flags = replaced.flags();
        final RegionThreadingEngine restored = replaced.rebuild(flags.entityTickWorkerExecutionEnabled(), flags.entityStorageMode());
        RegionThreadingManager.install(restored);
    }

    /**
     * The engine flags and layout of one run: what the diagnostic engine copies and what the configured
     * engine is rebuilt from.
     */
    private record InstalledEngine(RegionThreadingDiagnostics flags, RegionLayout layout) {
        RegionThreadingEngine rebuild(boolean entityTickWorkers, RegionEntityStorageMode storageMode) {
            // The light-load dispatch of the configured engine is part of what the diagnostic window measures, so
            // the rebuilt engine keeps it instead of silently falling back to the shared executor and one task per
            // region.
            return new RegionThreadingEngine(
                    this.flags.workerThreadCount(),
                    this.flags.workerExecutionEnabled(),
                    this.flags.globalWorkerExecutionEnabled(),
                    entityTickWorkers,
                    this.flags.blockEntityTickWorkerExecutionEnabled(),
                    this.flags.scheduledTickWorkerExecutionEnabled(),
                    this.flags.chunkTickWorkerExecutionEnabled(),
                    this.flags.spawnWorkerExecutionEnabled(),
                    this.flags.workerDispatch().persistentWorkers(),
                    this.flags.workerDispatch().packedRegionLeases(),
                    this.layout,
                    storageMode);
        }
    }

    /**
     * Prints the entity worker diagnostics: the counters of the measured window, the per-tick attribution of
     * the entity tick slice, the region mismatches and one line per rejected operation, ordered by count.
     * <p>
     * The attribution columns are the deltas over the measured window divided by the number of measured
     * slices (the {@code sliceSamples} column), so each one is the per-tick wall time of that segment. A
     * segment that never ran in the window reports {@code 0}, and a window without slices reports {@code -1}.
     */
    private static void reportEntityWorkers(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before, RegionEntityTickAttribution.Snapshot attributionBefore, RegionEntityTickAttribution.Snapshot attributionAfter, List<Mob> mobs, EntityWorkerBlockers blockers, ZombieDeathDriver deathDriver, int chunks) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final int mismatches = regionMismatches(helper.getLevel(), mobs);
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        int survivors = 0;
        for (Mob mob : mobs) {
            if (!mob.isRemoved() && !mob.isDeadOrDying()) {
                survivors++;
            }
        }
        final int villagersWithPoi = villagersWithBedAndWorkstation(mobs);
        if (villagerWorkload()) {
            // The villager workload exists to show that the POI path works while the villager ticks on its
            // region worker, so a window without a single claimed POI pair proves nothing and fails instead of
            // reporting an idle population.
            helper.assertTrue(villagersWithPoi > 0, "no villager of the workload claimed its bed and workstation");
        }
        System.out.println(sampler.note(profile, after)
                + " entityStorageMode=" + after.map(RegionThreadingDiagnostics::entityStorageMode).map(Object::toString).orElse("NONE")
                + " entityQueriesAllowed=" + delta(before, after, RegionThreadingDiagnostics::entityQueriesAllowedByLease)
                + " entityQueriesTruncated=" + delta(before, after, RegionThreadingDiagnostics::entityQueriesTruncatedByLease)
                + " entityQueriesRejected=" + delta(before, after, RegionThreadingDiagnostics::entityQueriesRejected)
                + " chunkGuardRefusals=" + delta(before, after, RegionThreadingDiagnostics::chunkGuardRefusals)
                + " chunkFallbackEntityTicks=" + delta(before, after, RegionThreadingDiagnostics::chunkFallbackEntityTicks)
                + " chunkPreloadRefusedEntityTicks=" + delta(before, after, RegionThreadingDiagnostics::chunkPreloadRefusedEntityTicks)
                + " chunkPreloadMargin=" + RegionThreadingEngine.entityTickChunkMargin()
                + " entityTickMode=" + entityTickMode(after)
                + " workloadMob=" + entityWorkerMobType()
                + " mobSurvivors=" + survivors + "/" + mobs.size()
                + " villagersWithBedAndWorkstation=" + villagersWithPoi + "/" + mobs.size()
                + " serverThreadOnlyEntityTicksByReason=" + after.map(RegionThreadingDiagnostics::serverThreadOnlyEntityTicksByReason).map(Object::toString).orElse("NONE")
                + " serverThreadOnlyEntityTicks=" + delta(before, after, RegionThreadingDiagnostics::serverThreadOnlyEntityTicks)
                + " deferredPortalTransitions=" + delta(before, after, RegionThreadingDiagnostics::deferredPortalTransitions)
                + " appliedPortalTransitions=" + delta(before, after, RegionThreadingDiagnostics::appliedPortalTransitions)
                + " refusedPortalTransitions=" + delta(before, after, RegionThreadingDiagnostics::refusedPortalTransitions)
                + " mobDeaths=" + deathDriver.deathsInWindow()
                + " mobReplacements=" + deathDriver.replacementsInWindow()
                + " removedReplacements=" + deathDriver.removedReplacementsInWindow()
                + " failedReplacements=" + deathDriver.failedReplacementsInWindow()
                + " regionMismatches=" + mismatches
                + " entityTickFailures=" + blockers.failureCount()
                + " distinctBlockers=" + blockers.blockerCount()
                + " poiLocalQueries=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::localQueries)
                + " poiPublishedQueries=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::publishedQueries)
                + " poiFallbackQueries=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::fallbackQueries)
                + " poiStaleTakes=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::staleTakes)
                + " poiPublishes=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::publishes)
                + " poiVillageDistanceQueries=" + poiDelta(before, after, RegionThreadingDiagnostics.PoiCounters::villageDistanceQueries)
                + " entityAttributionSlices=" + slices);
        System.out.println("SERVER_BENCH_ENTITY_WORKER_HEADER " + ENTITY_WORKER_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_ENTITY_WORKER_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                entityWorkerMobCount(),
                chunks,
                averageMillis(sorted),
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99),
                delta(before, after, RegionThreadingDiagnostics::entityTicksOnWorkers),
                delta(before, after, RegionThreadingDiagnostics::fallbackEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::skippedEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::entityMutationsAllowedByOwnership),
                delta(before, after, RegionThreadingDiagnostics::entityMutationsRejectedByOwnership),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesAllowedByLease),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesTruncatedByLease),
                delta(before, after, RegionThreadingDiagnostics::entityQueriesRejected),
                delta(before, after, RegionThreadingDiagnostics::ownershipViolationCount),
                delta(before, after, RegionThreadingDiagnostics::deferredEntitySectionUpdatesApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntitySectionUpdates),
                after.map(RegionThreadingDiagnostics::pendingEntityTransferCount).orElse(0),
                after.map(RegionThreadingDiagnostics::rememberedEntityTransferResultCount).orElse(0),
                delta(before, after, RegionThreadingDiagnostics::rejectedEntityTransferRecordCount),
                delta(before, after, RegionThreadingDiagnostics::legacyDeferredEntityMoveCount),
                delta(before, after, RegionThreadingDiagnostics::retriedDeferredEntityMoveCount),
                delta(before, after, RegionThreadingDiagnostics::unchangedDeferredEntityMoves),
                delta(before, after, RegionThreadingDiagnostics::batchedDeferredEntityMoves),
                delta(before, after, RegionThreadingDiagnostics::refusedDeferredEntityMoveBatches),
                mismatches,
                survivors,
                blockers.failureCount(),
                blockers.blockerCount(),
                delta(before, after, RegionThreadingDiagnostics::chunkGuardRefusals),
                delta(before, after, RegionThreadingDiagnostics::chunkFallbackEntityTicks),
                delta(before, after, RegionThreadingDiagnostics::chunkPreloadRefusedEntityTicks),
                RegionThreadingEngine.entityTickChunkMargin(),
                entityTickMode(after),
                millisPerSlice(attributionAfter.planNanos() - attributionBefore.planNanos(), slices),
                millisPerSlice(attributionAfter.leaseNanos() - attributionBefore.leaseNanos(), slices),
                millisPerSlice(attributionAfter.awaitNanos() - attributionBefore.awaitNanos(), slices),
                millisPerSlice(attributionAfter.handoffNanos() - attributionBefore.handoffNanos(), slices),
                millisPerSlice(attributionAfter.deferredPublicationNanos() - attributionBefore.deferredPublicationNanos(), slices),
                millisPerSlice(attributionAfter.sliceNanos() - attributionBefore.sliceNanos(), slices),
                slices,
                delta(before, after, RegionThreadingDiagnostics::deferredEntityRemovalsApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntityRemovals),
                delta(before, after, RegionThreadingDiagnostics::repeatedDeferredEntityRemovals),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityAdditionsRecorded),
                delta(before, after, RegionThreadingDiagnostics::deferredEntityAdditionsApplied),
                delta(before, after, RegionThreadingDiagnostics::droppedDeferredEntityAdditions),
                delta(before, after, RegionThreadingDiagnostics::entityAddRejectedOnWorker),
                deathDriver.deathsInWindow(),
                deathDriver.replacementsInWindow(),
                workerDispatchColumns(before, after));
        System.out.println("SERVER_BENCH_ENTITY_WORKER_BLOCKER_HEADER " + ENTITY_WORKER_BLOCKER_HEADER);
        blockers.printBlockers();
    }

    /**
     * Prints the soak report of {@code window} and {@return the end-state invariants it observed}
     * <p>
     * The report is printed before the caller asserts the invariants, so a broken soak always leaves its
     * evidence in the log. The mspt rows are the whole measured window, its first half and its last
     * {@value #SOAK_DRIFT_TICKS} ticks: the last window is the one the profiling workloads measure, and the
     * first half against it is the drift comparison. The struct rows are the after-GC heap and the engine
     * structures at the start, the middle and the end of the window, so a growth that the live population
     * cannot explain is visible next to the population of the same sample.
     */
    private static List<Invariant> reportSoak(ExtendedGameTestHelper helper, SoakWindow window) {
        final long[] samples = window.sampler().samplesInOrder();
        helper.assertTrue(samples.length > 0, "the soak tick sampler recorded no server tick");
        final ServerLevel level = window.level();
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final int measuredTicks = window.measuredTicks();
        final int lastCount = Math.min(SOAK_DRIFT_TICKS, samples.length);
        final TickStats whole = TickStats.of("whole", samples);
        final TickStats firstHalf = TickStats.of("firstHalf", Arrays.copyOfRange(samples, 0, samples.length / 2));
        final TickStats last = TickStats.of("last" + lastCount, Arrays.copyOfRange(samples, samples.length - lastCount, samples.length));
        window.watch().sampleEnd();

        System.out.println(window.sampler().note(profile, after)
                + " soakZombies=" + window.zombieCount()
                + " zombieSurvivors=" + aliveZombies(window.zombies())
                + " zombieDeaths=" + window.deathDriver().deathsInWindow()
                + " zombieReplacements=" + window.deathDriver().replacementsInWindow()
                + " entityTickFailures=" + window.blockers().failureCount()
                + " zombiesOutsideGrid=" + zombiesOutsideGrid(window.grid(), window.zombies())
                + " liveEntities=" + liveLevelEntities(level).size());
        System.out.println("SERVER_SOAK_MSPT_HEADER " + SOAK_MSPT_HEADER);
        for (TickStats stats : List.of(whole, firstHalf, last)) {
            System.out.println("SERVER_SOAK_MSPT " + csv(profile, window.zombieCount(), measuredTicks, stats.window(), stats.samples(),
                    fixed(stats.avgMs()), fixed(stats.p50Ms()), fixed(stats.p95Ms()), fixed(stats.p99Ms())));
        }
        final double driftRatio = firstHalf.avgMs() <= 0.0 ? -1.0 : last.avgMs() / firstHalf.avgMs();
        System.out.println("SERVER_SOAK_DRIFT_HEADER " + SOAK_DRIFT_HEADER);
        System.out.println("SERVER_SOAK_DRIFT " + csv(profile, fixed(firstHalf.avgMs()), fixed(last.avgMs()), fixed(driftRatio),
                fixed((driftRatio - 1.0) * 100.0), fixed(firstHalf.p99Ms()), fixed(last.p99Ms())));
        if (after.isPresent()) {
            printSoakCounters(profile, window.before(), after, measuredTicks);
        } else {
            System.out.println("SERVER_SOAK_COUNTER_NOTE no region threading engine is installed in this profile: the reference row reports server tick time only");
        }
        System.out.println("SERVER_SOAK_STRUCT_HEADER " + SOAK_STRUCT_HEADER);
        for (SoakSample sample : window.watch().samples()) {
            System.out.println("SERVER_SOAK_STRUCT " + csv(sample.point(), sample.measuredTick(),
                    fixed(sample.heapUsedBytes() / 1048576.0), fixed(sample.heapCommittedBytes() / 1048576.0), fixed(sample.heapMaxBytes() / 1048576.0),
                    sample.gcCollections(), sample.gcMillis(), sample.regionCount(), sample.runningRegions(), sample.trackedEntities(),
                    sample.trackedEntityTasks(), sample.sections(), sample.loadedChunks(), sample.pendingDeferredMoves(),
                    sample.pendingTransfers(), sample.rememberedTransfers(), sample.activeBatches()));
        }
        System.out.println("SERVER_SOAK_TICK_HEADER " + SOAK_TICK_HEADER);
        System.out.println("SERVER_SOAK_TICK " + csv(window.watch().ticksChecked(), window.watch().ticksWithActiveBatches(), window.watch().maxActiveBatches()));

        final List<Invariant> invariants = new ArrayList<>();
        if (window.watch().engineState() == null || after.isEmpty()) {
            System.out.println("SERVER_SOAK_INVARIANT_NOTE no region threading engine is installed in this profile: the end-state invariants need the engine of the worker profile");
            return invariants;
        }
        final RegionThreadingDiagnostics engine = after.get();
        final RegionMappingCheck mapping = window.watch().engineState().checkRegionMapping(level, RegionLayout.of(engine.regionPolicy(), engine.regionChunkSize()));
        final List<Entity> liveEntities = liveLevelEntities(level);
        final int sectionMismatches = regionMismatches(level, liveEntities);
        final int ledgerLive = level.entityIdentityLedger().liveCount();
        final SoakSample end = window.watch().samples().get(window.watch().samples().size() - 1);
        invariants.add(Invariant.of("activeBatchesAfterEveryTick", window.watch().ticksWithActiveBatches(), 0));
        invariants.add(Invariant.of("pendingDeferredEntityMoves", end.pendingDeferredMoves(), 0));
        invariants.add(Invariant.of("pendingEntityTransfers", end.pendingTransfers(), 0));
        invariants.add(Invariant.of("regionMappingMatchesPosition", mapping.misplacedMappings(), 0));
        invariants.add(Invariant.of("staleRegionMappings", mapping.staleMappings(), 0));
        invariants.add(Invariant.of("sectionMembershipMatchesPosition", sectionMismatches, 0));
        invariants.add(Invariant.of("identityLedgerLiveCountMatchesLevel", ledgerLive, liveEntities.size()));
        invariants.add(Invariant.of("entityTickFailures", window.blockers().failureCount(), 0));
        invariants.add(Invariant.of("ownershipViolations", delta(window.before(), after, RegionThreadingDiagnostics::ownershipViolationCount), 0));
        System.out.println("SERVER_SOAK_MAPPING_HEADER mappedEntities,staleMappings,misplacedMappings,unmappedLiveEntities");
        System.out.println("SERVER_SOAK_MAPPING " + csv(mapping.mappedEntities(), mapping.staleMappings(), mapping.misplacedMappings(), mapping.unmappedLiveEntities()));
        System.out.println("SERVER_SOAK_INVARIANT_HEADER " + SOAK_INVARIANT_HEADER);
        for (Invariant invariant : invariants) {
            System.out.println("SERVER_SOAK_INVARIANT " + csv(invariant.name(), invariant.observed(), invariant.expected(), invariant.ok()));
        }
        if (!"-".equals(mapping.firstProblem())) {
            System.out.println("SERVER_SOAK_INVARIANT_DETAIL regionMapping " + mapping.firstProblem());
        }
        window.blockers().printBlockers();
        return invariants;
    }

    /**
     * Prints every {@link RegionThreadingDiagnostics} field of the measured window as one row: its value
     * before the window, its value after it, the delta and the delta per measured tick. A field that is not
     * a number reports no delta, because it is state and not work.
     */
    private static void printSoakCounters(String profile, Optional<RegionThreadingDiagnostics> before, Optional<RegionThreadingDiagnostics> after, int measuredTicks) {
        System.out.println("SERVER_SOAK_COUNTER_HEADER " + SOAK_COUNTER_HEADER);
        for (RecordComponent component : RegionThreadingDiagnostics.class.getRecordComponents()) {
            final Object beforeValue = before.map(value -> counterValue(component, value)).orElse(null);
            final Object afterValue = after.map(value -> counterValue(component, value)).orElse(null);
            final boolean numeric = beforeValue instanceof Number && afterValue instanceof Number;
            final long delta = numeric ? ((Number) afterValue).longValue() - ((Number) beforeValue).longValue() : 0L;
            System.out.println("SERVER_SOAK_COUNTER " + csv(profile, component.getName(), beforeValue, afterValue,
                    numeric ? Long.toString(delta) : "-", numeric && measuredTicks > 0 ? fixed(delta / (double) measuredTicks) : "-"));
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
     * Watches the two things a soak has to observe continuously: the tick batches a finished tick must never
     * leave behind, and the after-GC heap and engine structures at the start, the middle and the end of the
     * window.
     * <p>
     * The per-tick check runs from {@link ServerTickEvent.Post}, i.e. after the tick body and therefore after
     * every batch of the tick completed. It only reads the size of the engine's active batch set, so the
     * watched ticks stay within the noise of the measurement.
     */
    private static final class SoakWatch {
        private final List<SoakSample> samples = new ArrayList<>(3);
        private SoakEngineState engineState;
        private int measuredTicks;
        private int ticksChecked;
        private int ticksWithActiveBatches;
        private int maxActiveBatches;

        /**
         * Resolves the engine of the window, or nothing when this profile runs no engine at all.
         */
        void install() {
            this.engineState = RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine installed ? SoakEngineState.of(installed) : null;
        }

        void startWindow(int measuredTicks) {
            this.measuredTicks = measuredTicks;
            this.samples.add(this.sample("start", 0));
        }

        void sampleMiddle() {
            this.samples.add(this.sample("middle", this.measuredTicks / 2));
        }

        SoakSample sampleEnd() {
            final SoakSample end = this.sample("end", this.measuredTicks);
            this.samples.add(end);
            return end;
        }

        @SubscribeEvent
        public void onTickPost(ServerTickEvent.Post event) {
            if (this.engineState == null) {
                return;
            }
            final int active = this.engineState.activeBatchCount();
            this.ticksChecked++;
            if (active > 0) {
                this.ticksWithActiveBatches++;
                this.maxActiveBatches = Math.max(this.maxActiveBatches, active);
            }
        }

        List<SoakSample> samples() {
            return this.samples;
        }

        SoakEngineState engineState() {
            return this.engineState;
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

        private SoakSample sample(String point, int measuredTick) {
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
            final Optional<RegionThreadingDiagnostics> diagnostics = RegionThreadingHooks.diagnostics();
            final SoakEngineState state = this.engineState;
            if (diagnostics.isEmpty() || state == null) {
                return new SoakSample(point, measuredTick, used, committed, max, gcCollections, gcMillis, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
            }
            final RegionThreadingDiagnostics value = diagnostics.get();
            return new SoakSample(point, measuredTick, used, committed, max, gcCollections, gcMillis,
                    value.regionCount(), value.runningRegionCount(), value.trackedEntityCount(), value.trackedEntityTaskCount(),
                    value.sectionCount(), value.loadedChunkCount(), state.pendingDeferredMoveCount(),
                    value.pendingEntityTransferCount(), value.rememberedEntityTransferResultCount(), state.activeBatchCount());
        }
    }

    /**
     * The engine state a soak watches that no diagnostics field exposes: the tick batches that are still
     * active, the deferred ownership updates that are still queued and the regionizer mapping of every
     * tracked entity.
     * <p>
     * Every read is a test-only reflective read of private engine state, like the reflective reads of the
     * other harnesses; the production engine is untouched.
     */
    private static final class SoakEngineState {
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
            return ((Collection<?>) fieldValue(this.activeTickBatches, this.engine)).size();
        }

        int pendingDeferredMoveCount() {
            return ((Collection<?>) fieldValue(this.pendingDeferredEntityMoves, this.engine)).size();
        }

        /**
         * {@return the state of the regionizer entity mapping of {@code level}}
         * <p>
         * A mapping is misplaced when the region it names is not the region of the block position of its
         * entity, which is what a lost or stale ownership handoff leaves behind. A stale mapping names an
         * entity that is already removed, i.e. an ownership entry the removal did not retire. An unmapped
         * live entity is a level entity the regionizer never learned about.
         */
        RegionMappingCheck checkRegionMapping(ServerLevel level, RegionLayout layout) {
            final Map<?, ?> mapping = (Map<?, ?>) fieldValue(this.regionizerEntities, fieldValue(this.regionizer, this.engine));
            int stale = 0;
            int misplaced = 0;
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
                final RegionCoordinate mapped = (RegionCoordinate) fieldValue(this.regionStateCoordinate, entry.getValue());
                final RegionCoordinate expected = layout.regionAt(level, entity.blockPosition());
                if (mapped.regionX() != expected.regionX() || mapped.regionZ() != expected.regionZ()) {
                    misplaced++;
                    if ("-".equals(firstProblem)) {
                        firstProblem = entity.blockPosition() + " mapped to " + mapped.regionX() + "/" + mapped.regionZ()
                                + " but belongs to " + expected.regionX() + "/" + expected.regionZ();
                    }
                }
            }
            int unmapped = 0;
            for (Entity entity : liveLevelEntities(level)) {
                if (!mapping.containsKey(entity)) {
                    unmapped++;
                }
            }
            return new RegionMappingCheck(mapping.size(), stale, misplaced, unmapped, firstProblem);
        }

        private static Class<?> regionStateClass() {
            try {
                return Class.forName("net.neoforged.neoforge.server.threading.region.RegionState");
            } catch (ClassNotFoundException exception) {
                throw new AssertionError("the regionizer state class of the entity mapping is gone", exception);
            }
        }
    }

    /**
     * {@return the state of the regionizer entity mapping at the end of a soak window}
     *
     * @param mappedEntities       ownership entries of the regionizer
     * @param staleMappings        entries that name an entity which is already removed
     * @param misplacedMappings    entries whose region is not the region of the entity's block position
     * @param unmappedLiveEntities live level entities the mapping does not name
     * @param firstProblem         the first problem in words, or {@code -} when there is none
     */
    private record RegionMappingCheck(int mappedEntities, int staleMappings, int misplacedMappings, int unmappedLiveEntities, String firstProblem) {}

    /**
     * The measured ticks of one soak window and the workload that produced them.
     */
    private record SoakWindow(ServerLevel level, TickSampler sampler, SoakWatch watch, Optional<RegionThreadingDiagnostics> before, EntityWorkerBlockers blockers, ZombieDeathDriver deathDriver, List<Mob> zombies, List<ChunkPos> grid, int zombieCount, int measuredTicks) {}

    /**
     * The mspt statistics of one subset of the measured ticks of a soak window.
     */
    private record TickStats(String window, int samples, double avgMs, double p50Ms, double p95Ms, double p99Ms) {
        static TickStats of(String window, long[] nanos) {
            final long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            return new TickStats(window, sorted.length, averageMillis(sorted), percentileMillis(sorted, 0.50), percentileMillis(sorted, 0.95), percentileMillis(sorted, 0.99));
        }
    }

    /**
     * One after-GC heap and engine structure sample of a soak window. The engine fields are {@code -1} when
     * the profile runs no engine.
     */
    private record SoakSample(String point, int measuredTick, long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, long gcCollections, long gcMillis, int regionCount, int runningRegions, int trackedEntities, int trackedEntityTasks, int sections, int loadedChunks, int pendingDeferredMoves, int pendingTransfers, int rememberedTransfers, int activeBatches) {}

    /**
     * One end-state check of a soak window: what it observed, what it expected and whether it held.
     */
    private record Invariant(String name, String observed, String expected, boolean ok) {
        static Invariant of(String name, long observed, long expected) {
            return new Invariant(name, Long.toString(observed), Long.toString(expected), observed == expected);
        }
    }

    /** {@return the comma separated report line of {@code values}, with {@code -} for an absent value} */
    private static String csv(Object... values) {
        final StringJoiner joiner = new StringJoiner(",");
        for (Object value : values) {
            joiner.add(value == null ? "-" : String.valueOf(value));
        }
        return joiner.toString();
    }

    /** {@return {@code value} with a fixed number of decimals, so a report line stays locale independent} */
    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** {@return the live zombie population of {@code zombies}} */
    private static int aliveZombies(List<Mob> zombies) {
        int alive = 0;
        for (Mob zombie : zombies) {
            if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                alive++;
            }
        }
        return alive;
    }

    /** {@return the tracked zombies whose chunk is not one of the force-ticked chunks of {@code grid}} */
    private static int zombiesOutsideGrid(List<ChunkPos> grid, List<Mob> zombies) {
        final Set<Long> chunks = new HashSet<>(grid.size());
        for (ChunkPos chunk : grid) {
            chunks.add(chunk.toLong());
        }
        int outside = 0;
        for (Mob zombie : zombies) {
            if (zombie.isRemoved() || zombie.isDeadOrDying()) {
                continue;
            }
            if (!chunks.contains(ChunkPos.asLong(zombie.blockPosition()))) {
                outside++;
            }
        }
        return outside;
    }

    /**
     * {@return the villagers of the workload that claimed both a bed and a workstation}
     * <p>
     * A villager claims its bed as {@code HOME} and its workstation as {@code JOB_SITE}, both through the
     * {@code PoiManager} of the level. The count is the evidence that the POI path of an audited type still
     * runs while its tick is handed to the server thread.
     */
    private static int villagersWithBedAndWorkstation(List<Mob> mobs) {
        int claimed = 0;
        for (Mob mob : mobs) {
            if (mob instanceof Villager villager
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

    /**
     * {@return the entity tick mode of the measured window, so a worker row and its server-thread reference
     * row are distinguishable in the CSV}
     * <p>
     * A run without an installed engine ticks its entities on the server thread, so it reports the server
     * mode as well.
     */
    private static String entityTickMode(Optional<RegionThreadingDiagnostics> diagnostics) {
        return diagnostics.map(value -> value.entityTickWorkerExecutionEnabled() ? "worker" : "server").orElse("server");
    }

    /**
     * Collects the entity tick failures of the diagnostic window, keyed by the operation the failure names.
     * <p>
     * The failures are recorded on the region workers and printed on the server thread after the window, so
     * the counts are accumulated in concurrent structures. A storage guard rejection names its operation in
     * the message; any other failure is keyed by its throwable class.
     */
    private static final class EntityWorkerBlockers {
        private final Map<String, Blocker> blockers = new ConcurrentHashMap<>();
        private final AtomicLong failures = new AtomicLong();

        void record(Entity entity, Throwable throwable) {
            this.failures.incrementAndGet();
            final String operation = operationName(throwable);
            final String entityType = entityTypeName(entity);
            this.blockers.compute(operation, (name, existing) -> {
                final Blocker blocker = existing != null ? existing : new Blocker(name, throwable);
                blocker.count++;
                blocker.entityTypes.add(entityType);
                return blocker;
            });
        }

        long failureCount() {
            return this.failures.get();
        }

        int blockerCount() {
            return this.blockers.size();
        }

        void printBlockers() {
            final List<Blocker> sorted = new ArrayList<>(this.blockers.values());
            sorted.sort(Comparator.comparingLong((Blocker blocker) -> blocker.count).reversed());
            for (Blocker blocker : sorted) {
                System.out.printf(Locale.ROOT, "SERVER_BENCH_ENTITY_WORKER_BLOCKER %s,%d,%s,%s%n",
                        blocker.operation,
                        blocker.count,
                        firstFrame(blocker.firstFailure),
                        String.join("|", blocker.entityTypes));
                System.out.println("SERVER_BENCH_ENTITY_WORKER_STACK " + blocker.operation);
                blocker.firstFailure.printStackTrace(System.out);
            }
        }
    }

    /**
     * One rejected operation: its failure count, the entities it rejected and the first failure, which
     * carries the stack trace the report prints once.
     */
    private static final class Blocker {
        private final String operation;
        private final Throwable firstFailure;
        private final Set<String> entityTypes = ConcurrentHashMap.newKeySet();
        private long count;

        Blocker(String operation, Throwable firstFailure) {
            this.operation = operation;
            this.firstFailure = firstFailure;
        }
    }

    /**
     * {@return the operation a failure names, or the throwable class when it names none}
     * <p>
     * A storage guard rejection carries the rejected operation in its message; {@code guardEntityTick} wraps
     * it in a crash report exception, so the cause chain is searched.
     */
    private static String operationName(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            final String message = current.getMessage();
            if (message != null && message.startsWith(STORAGE_ACCESS_REJECTION_PREFIX)) {
                return message.substring(STORAGE_ACCESS_REJECTION_PREFIX.length());
            }
        }
        return throwable.getClass().getName();
    }

    /**
     * {@return the innermost cause of {@code throwable}, which is where the rejected operation threw}
     */
    private static Throwable deepestCause(Throwable throwable) {
        Throwable deepest = throwable;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        return deepest;
    }

    /**
     * {@return the first stack frame of the innermost cause of {@code throwable}, or {@code -} when the
     * failure carries no stack trace}
     */
    private static String firstFrame(Throwable throwable) {
        final StackTraceElement[] trace = deepestCause(throwable).getStackTrace();
        return trace.length == 0 ? "-" : trace[0].toString();
    }

    private static String entityTypeName(Entity entity) {
        return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    /**
     * {@return the number of tracked entities that are not indexed in the entity section of their position}
     * <p>
     * A section belongs to exactly one region of the installed layout, so an entity that is indexed in the
     * section of its own block position is in the region of its block position. A non-zero count means the
     * entity storage kept a stale section for a moved entity.
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

    private static Object storageField(Object target, String name) {
        try {
            final Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String profileName(Optional<RegionThreadingDiagnostics> diagnostics) {
        return diagnostics.map(value -> "fixed" + value.regionChunkSize() + "-" + value.workerThreadCount() + "w").orElse("off");
    }

    /**
     * {@return the nearest-rank percentile {@code fraction} of the ascending {@code sorted} samples}
     */
    private static double percentileMillis(long[] sorted, double fraction) {
        final int rank = Math.max(1, (int) Math.ceil(fraction * sorted.length));
        return sorted[Math.min(sorted.length, rank) - 1] / 1_000_000.0;
    }

    private static double averageMillis(long[] sorted) {
        long total = 0L;
        for (long sample : sorted) {
            total += sample;
        }
        return total / (double) sorted.length / 1_000_000.0;
    }

    /**
     * {@return the growth of one regionized POI counter over the measured window}
     */
    private static long poiDelta(Optional<RegionThreadingDiagnostics> before, Optional<RegionThreadingDiagnostics> after,
            ToLongFunction<RegionThreadingDiagnostics.PoiCounters> counter) {
        if (after.isEmpty()) {
            return 0L;
        }
        final long afterValue = counter.applyAsLong(after.get().poiCounters());
        final long beforeValue = before.map(diagnostics -> counter.applyAsLong(diagnostics.poiCounters())).orElse(0L);
        return afterValue - beforeValue;
    }

    private static long delta(Optional<RegionThreadingDiagnostics> before, Optional<RegionThreadingDiagnostics> after, ToLongFunction<RegionThreadingDiagnostics> counter) {
        if (after.isEmpty()) {
            return 0L;
        }
        final long afterValue = counter.applyAsLong(after.get());
        final long beforeValue = before.map(counter::applyAsLong).orElse(0L);
        return afterValue - beforeValue;
    }

    /**
     * Samples the server tick body duration from {@link ServerTickEvent.Pre} to
     * {@link ServerTickEvent.Post} on the server thread.
     * <p>
     * The first {@code warmupTicks} ticks are discarded and the next {@code measuredTicks} are kept. An
     * optional per-tick action runs at the start of the tick, inside the measured window. The sampler only
     * records; the owning test registers it before the measured ticks and unregisters it afterwards, so no
     * listener outlives the run.
     */
    public static final class TickSampler {
        private final int warmupTicks;
        private final long[] samples;
        private final Runnable perTickWork;
        private int tickIndex;
        private int sampleCount;
        private long tickStartNanos = -1L;
        private long windowStartNanos = -1L;
        private long windowEndNanos = -1L;
        private long firstGameTime = -1L;
        private long lastGameTime = -1L;
        private Runnable measuredWindowStart;

        TickSampler(int warmupTicks, int measuredTicks, Runnable perTickWork) {
            this.warmupTicks = warmupTicks;
            this.samples = new long[measuredTicks];
            this.perTickWork = perTickWork;
        }

        void startWindow(long gameTime) {
            this.windowStartNanos = System.nanoTime();
            this.firstGameTime = gameTime;
        }

        /**
         * Runs {@code action} once, at the start of the first measured tick, so a measurement that must
         * cover the measured window only starts from zero when the warmup ended.
         */
        void onMeasuredWindowStart(Runnable action) {
            this.measuredWindowStart = action;
        }

        @SubscribeEvent
        public void onTickPre(ServerTickEvent.Pre event) {
            if (this.measuredWindowStart != null && this.tickIndex == this.warmupTicks) {
                this.measuredWindowStart.run();
            }
            if (this.perTickWork != null) {
                this.perTickWork.run();
            }
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

        /**
         * {@return the recorded samples in ascending order}
         */
        long[] sortedSamples() {
            final long[] copy = Arrays.copyOf(this.samples, this.sampleCount);
            Arrays.sort(copy);
            return copy;
        }

        /**
         * {@return the recorded samples in the order the ticks completed}
         * <p>
         * The report of a soak window splits this order into a first and a trailing window, so the drift of
         * the window is comparable over time; the sorted samples cannot answer that.
         */
        long[] samplesInOrder() {
            return Arrays.copyOf(this.samples, this.sampleCount);
        }

        int sampleCount() {
            return this.sampleCount;
        }

        /**
         * {@return the one-line measurement metadata of this run}
         * <p>
         * {@code tickMs} is the wall clock time per sampled tick, so {@code 1000 / tickMs} is the tick rate
         * the server actually reached; {@code gameTimeDelta} is {@code 0} for a level that does not advance
         * its game time.
         */
        String note(String profile, Optional<RegionThreadingDiagnostics> diagnostics) {
            final long windowNanos = this.windowStartNanos < 0L || this.windowEndNanos < 0L ? -1L : this.windowEndNanos - this.windowStartNanos;
            final double tickMillis = windowNanos <= 0L || this.sampleCount == 0 ? -1.0 : windowNanos / 1_000_000.0 / this.sampleCount;
            return "SERVER_BENCH_NOTE profile=" + profile
                    + " warmupTicks=" + this.warmupTicks
                    + " measuredTicks=" + this.samples.length
                    + " samples=" + this.sampleCount
                    + " windowMs=" + (windowNanos < 0L ? -1L : windowNanos / 1_000_000L)
                    + " tickMs=" + String.format(Locale.ROOT, "%.2f", tickMillis)
                    + " gameTimeDelta=" + (this.firstGameTime < 0L || this.lastGameTime < 0L ? -1L : this.lastGameTime - this.firstGameTime)
                    + " workerThreads=" + diagnostics.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0)
                    + " blockEntityWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::blockEntityTickWorkerExecutionEnabled).orElse(false)
                    + " entityWorkerExecution=" + diagnostics.map(RegionThreadingDiagnostics::entityTickWorkerExecutionEnabled).orElse(false)
                    + " regionPolicy=" + diagnostics.map(RegionThreadingDiagnostics::regionPolicy).map(Object::toString).orElse("NONE");
        }
    }
}
