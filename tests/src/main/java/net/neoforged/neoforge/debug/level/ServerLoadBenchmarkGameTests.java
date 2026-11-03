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
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionMetricsReport;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
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

    static final String FURNACE_CSV_HEADER = "profile,workers,regionSize,furnaces,chunks,msptAvg,msptP50,msptP95,msptP99,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,leasedRegions,ownershipViolations,planMs,leaseMs,awaitMs,handoffMs,deferredPublicationMs,sliceMs,sliceSamples";
    static final String HOPPER_CSV_HEADER = "profile,workers,regionSize,hoppers,chunks,msptAvg,msptP50,msptP95,msptP99,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,leasedRegions,entityQueriesAllowed,entityQueriesTruncated,entityQueriesRejected,ownershipViolations,removalsApplied,removalsDropped,removalsRepeated,itemCountStart,itemCountEnd,itemsDropped,itemsDelivered,itemsConserved,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceSamples,blockEntityAdmissionCache";
    static final String ENTITY_CSV_HEADER = "profile,workers,regionSize,zombies,items,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,deferredEntityTicks,sectionUpdatesApplied,sectionUpdatesDropped,ownershipViolations,zombieReplacements,regionMismatches";
    static final String ENTITY_WORKER_CSV_HEADER = "profile,workers,regionSize,zombies,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,skippedEntityTicks,deferredEntityTicks,entityMutationsAllowed,entityMutationsRejected,entityQueriesAllowed,entityQueriesTruncated,entityQueriesRejected,ownershipViolations,sectionUpdatesApplied,sectionUpdatesDropped,pendingTransfers,rememberedTransfers,rejectedTransferRecords,legacyDeferredMoves,retriedDeferredMoves,unchangedDeferredMoves,batchedDeferredMoves,refusedDeferredMoveBatches,regionMismatches,zombieSurvivors,entityTickFailures,distinctBlockers,chunkGuardRefusals,chunkFallbackEntityTicks,chunkPreloadRefusedEntityTicks,chunkPreloadMargin,entityTickMode,planMs,leaseMs,awaitMs,handoffMs,deferredPublicationMs,sliceMs,sliceSamples,removalsApplied,removalsDropped,removalsRepeated,additionsRecorded,additionsApplied,additionsDropped,entityAddRejectedOnWorker,zombieDeaths,zombieReplacements";
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
            final List<Zombie> zombies = new ArrayList<>(entityWorkerZombieCount());
            final ZombieDeathDriver deathDriver = new ZombieDeathDriver(level, zombies, entityWorkerZombieDeaths());
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
                    cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
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
                        spawnZombies(level, grid, origin, zombies, helper, entityWorkerZombieCount());
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
                        reportEntityWorkers(helper, sampler, before.get(), attributionBefore.get(), RegionEntityTickAttribution.snapshot(), zombies, blockers, deathDriver, grid.size());
                        if (cleaned.compareAndSet(false, true)) {
                            cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, mobLoot, previousMobLoot, Optional.ofNullable(replaced.get()));
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
            final List<Zombie> zombies = new ArrayList<>(zombieCount);
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
     * {@value #ENTITY_WORKER_ZOMBIE_COUNT} unless {@value #ZOMBIE_COUNT_PROPERTY} overrides it}
     */
    private static int entityWorkerZombieCount() {
        return intProperty(ZOMBIE_COUNT_PROPERTY, ENTITY_WORKER_ZOMBIE_COUNT);
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
        System.out.println("SERVER_BENCH_FURNACE_HEADER " + FURNACE_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_FURNACE_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%d%n",
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
                slices);
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
        System.out.printf(Locale.ROOT, "SERVER_BENCH_HOPPER_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%b,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%b%n",
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
                admissionCache);
        printRegionMetrics(after);
        helper.assertTrue(conserved,
                "the hopper workload lost or duplicated items: start " + itemCountStart + ", dropped " + itemsDropped + ", end " + itemCountEnd);
        helper.assertTrue(delivered > 0L, "the hopper workload never moved an item");
    }

    /**
     * {@return the per-tick average of {@code nanos} over {@code slices} in milliseconds, or {@code -1} when
     * the window recorded no slice}
     */
    private static double millisPerSlice(long nanos, long slices) {
        return slices <= 0L ? -1.0 : nanos / (double) slices / 1_000_000.0;
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
    private static void spawnZombies(ServerLevel level, List<ChunkPos> grid, BlockPos origin, List<Zombie> zombies, ExtendedGameTestHelper helper, int count) {
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
     * <p>
     * Without a nearby player a mob despawns; the diagnostic needs the population to stay put.
     */
    private static Zombie newZombie(ServerLevel level, BlockPos pos, RandomSource random) {
        final Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
        zombie.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360.0F, 0.0F);
        zombie.setPersistenceRequired();
        return zombie;
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
        private final List<Zombie> zombies;
        /** Zombies whose death is due at their next tick; the identity set is written by workers and the server thread. */
        private final Set<Zombie> scheduled = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
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

        ZombieDeathDriver(ServerLevel level, List<Zombie> zombies, int deathsPerInterval) {
            this.level = level;
            this.zombies = zombies;
            this.deathsPerInterval = deathsPerInterval;
        }

        /**
         * Schedules the deaths of this interval and replaces the zombies that died. Runs on the server thread
         * at the head of the server tick, so a zombie scheduled here is ticked later in the same tick.
         */
        @Override
        public void run() {
            this.replaceDeadZombies();
            if (this.deathsPerInterval <= 0 || this.zombies.isEmpty()) {
                return;
            }
            if (--this.intervalCountdown > 0) {
                return;
            }
            this.intervalCountdown = DEATH_INTERVAL_TICKS;
            for (int index = 0; index < this.deathsPerInterval; index++) {
                final Zombie zombie = this.zombies.get(this.nextSlot++ % this.zombies.size());
                if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                    this.scheduled.add(zombie);
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
            if (!(event.getEntity() instanceof Zombie zombie) || !this.scheduled.remove(zombie)) {
                return;
            }
            if (zombie.isRemoved() || zombie.isDeadOrDying()) {
                return;
            }
            this.deaths++;
            zombie.hurt(zombie.damageSources().generic(), Float.MAX_VALUE);
        }

        private void replaceDeadZombies() {
            for (int index = 0; index < this.zombies.size(); index++) {
                final Zombie zombie = this.zombies.get(index);
                if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                    continue;
                }
                final boolean removed = zombie.isRemoved();
                final Zombie replacement = newZombie(this.level, zombie.blockPosition(), this.random);
                if (!this.level.addFreshEntity(replacement)) {
                    this.failedReplacements++;
                    continue;
                }
                this.zombies.set(index, replacement);
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
    private static void cleanupEntityWorkerWindow(ServerLevel level, List<ChunkPos> grid, List<Zombie> zombies, GameRules.BooleanValue daylight, boolean previousDaylight, GameRules.BooleanValue mobLoot, boolean previousMobLoot, Optional<InstalledEngine> replaced) {
        RegionEntityTickAttribution.setEnabled(false);
        for (Zombie zombie : zombies) {
            zombie.discard();
        }
        zombies.clear();
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
        RegionThreadingManager.install(replaced.rebuild(flags.entityTickWorkerExecutionEnabled(), flags.entityStorageMode()));
    }

    /**
     * The engine flags and layout of one run: what the diagnostic engine copies and what the configured
     * engine is rebuilt from.
     */
    private record InstalledEngine(RegionThreadingDiagnostics flags, RegionLayout layout) {
        RegionThreadingEngine rebuild(boolean entityTickWorkers, RegionEntityStorageMode storageMode) {
            return new RegionThreadingEngine(
                    this.flags.workerThreadCount(),
                    this.flags.workerExecutionEnabled(),
                    this.flags.globalWorkerExecutionEnabled(),
                    entityTickWorkers,
                    this.flags.blockEntityTickWorkerExecutionEnabled(),
                    this.flags.scheduledTickWorkerExecutionEnabled(),
                    this.flags.randomTickWorkerExecutionEnabled(),
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
    private static void reportEntityWorkers(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before, RegionEntityTickAttribution.Snapshot attributionBefore, RegionEntityTickAttribution.Snapshot attributionAfter, List<Zombie> zombies, EntityWorkerBlockers blockers, ZombieDeathDriver deathDriver, int chunks) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final int mismatches = regionMismatches(helper.getLevel(), zombies);
        final long slices = attributionAfter.sliceCount() - attributionBefore.sliceCount();
        int survivors = 0;
        for (Zombie zombie : zombies) {
            if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                survivors++;
            }
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
                + " zombieSurvivors=" + survivors + "/" + zombies.size()
                + " zombieDeaths=" + deathDriver.deathsInWindow()
                + " zombieReplacements=" + deathDriver.replacementsInWindow()
                + " removedReplacements=" + deathDriver.removedReplacementsInWindow()
                + " failedReplacements=" + deathDriver.failedReplacementsInWindow()
                + " regionMismatches=" + mismatches
                + " entityTickFailures=" + blockers.failureCount()
                + " distinctBlockers=" + blockers.blockerCount()
                + " entityAttributionSlices=" + slices);
        System.out.println("SERVER_BENCH_ENTITY_WORKER_HEADER " + ENTITY_WORKER_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_ENTITY_WORKER_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                entityWorkerZombieCount(),
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
                deathDriver.replacementsInWindow());
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
    private record SoakWindow(ServerLevel level, TickSampler sampler, SoakWatch watch, Optional<RegionThreadingDiagnostics> before, EntityWorkerBlockers blockers, ZombieDeathDriver deathDriver, List<Zombie> zombies, List<ChunkPos> grid, int zombieCount, int measuredTicks) {}

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
    private static int aliveZombies(List<Zombie> zombies) {
        int alive = 0;
        for (Zombie zombie : zombies) {
            if (!zombie.isRemoved() && !zombie.isDeadOrDying()) {
                alive++;
            }
        }
        return alive;
    }

    /** {@return the tracked zombies whose chunk is not one of the force-ticked chunks of {@code grid}} */
    private static int zombiesOutsideGrid(List<ChunkPos> grid, List<Zombie> zombies) {
        final Set<Long> chunks = new HashSet<>(grid.size());
        for (ChunkPos chunk : grid) {
            chunks.add(chunk.toLong());
        }
        int outside = 0;
        for (Zombie zombie : zombies) {
            if (zombie.isRemoved() || zombie.isDeadOrDying()) {
                continue;
            }
            if (!chunks.contains(ChunkPos.asLong(zombie.blockPosition()))) {
                outside++;
            }
        }
        return outside;
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
