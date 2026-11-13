/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Headless scaling benchmark of the region engine on the machine that runs it.
 * <p>
 * The class reports numbers instead of asserting behavior. The only assertions are that every
 * configuration completed and that the engine counters are non-negative: no elapsed time, no speedup and
 * no counter value is asserted, so a slow or oversubscribed machine can never fail the build.
 * <p>
 * Two workloads are measured for every combination of worker count {@code {1, 4, 7}}, region count
 * {@code {8, 32}} and work per region {@code {8, 16}}:
 * <ul>
 * <li>{@code block-entity}: the production furnace-family block-entity tick slice. The tickers are the
 * real {@code LevelChunk.BoundTickingBlockEntity} wrappers of real {@link FurnaceBlockEntity} instances
 * running the vanilla {@link AbstractFurnaceBlockEntity#serverTick}, collected and executed through
 * {@link RegionThreadingEngine#beginBlockEntityTickPlan}. The level is the same
 * {@code CALLS_REAL_METHODS} mock the block-entity unit tests use, with a real
 * {@link PersistentEntitySectionManager} deciding entity-load eligibility.</li>
 * <li>{@code scheduled-tick}: the scheduled block tick dispatcher with a fixed CPU cost per tick. The
 * callbacks stand in for vanilla block ticks and touch no level state, so the workload isolates the
 * batch barrier and the region lease from Minecraft work. {@code LevelTicks} collection is not part of
 * the measured region: only the dispatcher the engine hands to it runs.</li>
 * </ul>
 * Every measured tick times the whole production slice of the submitting server thread: collection
 * ({@link RegionBlockEntityTickPlan#accept} for the block entities, the collected snapshot for the
 * scheduled ticks) plus the batch execution. Each configuration is warmed up and then measured in
 * {@value #MEASURED_RUNS} runs; the reported row is the median run of that configuration.
 * <p>
 * This is a scaling harness, not JMH: it runs in the shared test JVM, so the absolute numbers carry the
 * usual JIT and GC noise. It is tagged {@value #TAG} and excluded from {@code junitTest}; run it with
 * {@code :tests:junitBenchmark}.
 */
@Tag(RegionEngineBenchmark.TAG)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class RegionEngineBenchmark {
    static final String TAG = "benchmark";

    private static final int[] WORKER_COUNTS = { 1, 4, 7 };
    private static final int[] REGION_COUNTS = { 8, 32 };
    private static final int[] WORK_PER_REGION = { 8, 16 };
    private static final int WARMUP_TICKS = 1;
    private static final int MEASURED_RUNS = 3;
    private static final int BLOCK_ENTITY_TICKS_PER_RUN = 100;
    private static final int SCHEDULED_TICKS_PER_RUN = 5;
    /**
     * Fixed loop count of one scheduled tick callback, so every task has the same CPU cost. The cost of one
     * task on this machine is the {@code msPerTick} of a one-worker row divided by the number of tasks of
     * that row, because a single worker executes every task of a tick one after the other.
     */
    private static final int CPU_STEPS_PER_TASK = 100_000;
    /** A benchmark tick must never wait forever; the whole harness is bounded by the class timeout too. */
    private static final long TICK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** Edge length in chunks of the fixed layout every engine of this harness uses. */
    private static final int REGION_CHUNK_SIZE = RegionLayout.FIXED_3X3.regionChunkSize();
    /** Regions are placed 8 per row of the fixed grid; the row index selects the region Z coordinate. */
    private static final int REGIONS_PER_ROW = 8;
    private static final int FURNACE_Y = 64;
    /** Keeps a furnace lit for every measured tick; a lit furnace with an empty inventory only burns. */
    private static final int LIT_TIME_TICKS = 10_000_000;
    /** Player counts of the region transition measurement. */
    private static final int[] PLAYER_COUNTS = { 1, 8, 32 };
    /** Publications per row of the region transition measurement. */
    private static final int PLAYER_TRANSITION_TICKS = 2000;
    private static final String PLAYER_TRANSITION_HEADER = "players,ticks,mode,totalMs,nanosPerPlayerTick,transitions,entries,departures";
    private static final java.util.concurrent.atomic.AtomicInteger ENTITY_IDS = new java.util.concurrent.atomic.AtomicInteger(1);
    private static final String CSV_HEADER = "workload,workerThreads,regions,workPerRegion,ticksPerRun,totalMs,msPerTick,speedupVs1Worker,leasedRegions,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,scheduledTicksOnWorkers,fallbackScheduledTicks,skippedScheduledTicks,rejectedWorkerTasks";

    /**
     * The barrier of every batch. It parks until the batch finished and fails after a bounded wait
     * instead of waiting forever. The workloads never hand work back to the server thread, so the barrier
     * does not have to pump chunk source tasks like the production barrier does.
     */
    private static final RegionTickBarrier BOUNDED_BARRIER = RegionEngineBenchmark::awaitBounded;

    private static volatile long cpuSink;

    @BeforeAll
    static void printJvmFacts() {
        final Runtime runtime = Runtime.getRuntime();
        System.out.println("JVM availableProcessors=" + runtime.availableProcessors()
                + " maxHeapBytes=" + runtime.maxMemory()
                + " osArch=" + System.getProperty("os.arch"));
    }

    @Test
    void reportsBlockEntityTickScaling() throws ReflectiveOperationException {
        final List<Result> results = new ArrayList<>();
        for (final int regions : REGION_COUNTS) {
            for (final int furnacesPerRegion : WORK_PER_REGION) {
                for (final int workerThreads : WORKER_COUNTS) {
                    results.add(measureBlockEntityConfiguration(workerThreads, regions, furnacesPerRegion));
                }
            }
        }

        printCsv("block-entity", results);
        assertEquals(WORKER_COUNTS.length * REGION_COUNTS.length * WORK_PER_REGION.length, results.size(),
                "every block-entity configuration must produce one row");
    }

    @Test
    void reportsScheduledTickScaling() {
        final List<Result> results = new ArrayList<>();
        for (final int regions : REGION_COUNTS) {
            for (final int tasksPerRegion : WORK_PER_REGION) {
                for (final int workerThreads : WORKER_COUNTS) {
                    results.add(measureScheduledTickConfiguration(workerThreads, regions, tasksPerRegion));
                }
            }
        }

        printCsv("scheduled-tick", results);
        assertEquals(WORKER_COUNTS.length * REGION_COUNTS.length * WORK_PER_REGION.length, results.size(),
                "every scheduled-tick configuration must produce one row");
    }

    /**
     * Measures what the player region transition bookkeeping costs at the defined publication point.
     * <p>
     * The region of every player of a level is published at the start of the level's entity slice, so the cost
     * of one player per tick is the cost of that publication. Two modes are measured against the same entry
     * point: a player that stays in its region, whose publication only reads the mapping back, and a player that
     * teleports to the other region before every publication, whose publication takes the regionizer write lock
     * and migrates the region tick index. The difference of the two rows is the cost of one transition; the
     * counters are asserted so the two rows measure what they claim.
     */
    @Test
    void reportsPlayerRegionTransitionCost() {
        final List<PlayerTransitionRow> rows = new ArrayList<>();
        for (final int players : PLAYER_COUNTS) {
            rows.add(measurePlayerPublication(players, false));
            rows.add(measurePlayerPublication(players, true));
        }
        System.out.println();
        System.out.println("CSV player-region-transitions (" + PLAYER_TRANSITION_TICKS + " publications per row)");
        System.out.println(PLAYER_TRANSITION_HEADER);
        for (final PlayerTransitionRow row : rows) {
            System.out.printf(Locale.ROOT, "%d,%d,%s,%.3f,%.1f,%d,%d,%d%n",
                    row.players(), row.ticks(), row.mode(), row.totalMs(), row.nanosPerPlayerTick(),
                    row.transitions(), row.entries(), row.departures());
        }
    }

    /**
     * Measures {@value #PLAYER_TRANSITION_TICKS} publications of {@code players} players on a fresh engine.
     *
     * @param moving {@code true} when every player teleports to the other region before every publication
     */
    private static PlayerTransitionRow measurePlayerPublication(int players, boolean moving) {
        final RegionThreadingEngine engine = new RegionThreadingEngine(2, false, false, true, false, false, false, RegionLayout.FIXED_3X3);
        RegionThreadingManager.install(engine);
        try {
            final ServerLevel level = mockLevel();
            engine.addRegionSection(level, 0, 0);
            engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
            final List<AtomicReference<BlockPos>> positions = new ArrayList<>(players);
            for (int index = 0; index < players; index++) {
                final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(1 + index, 64, 1));
                positions.add(position);
                // The tick list membership hook of the level tracks the player; the region mapping itself is
                // published at the defined point measured below.
                engine.entityTickStarted(level, benchmarkPlayer(level, position));
            }
            for (int tick = 0; tick < WARMUP_TICKS; tick++) {
                publishPlayers(engine, level, positions, moving);
            }
            final RegionThreadingDiagnostics.PlayerRegionCounters before = engine.diagnostics().playerRegions();
            final long start = System.nanoTime();
            for (int tick = 0; tick < PLAYER_TRANSITION_TICKS; tick++) {
                publishPlayers(engine, level, positions, moving);
            }
            final long elapsed = System.nanoTime() - start;
            final RegionThreadingDiagnostics.PlayerRegionCounters after = engine.diagnostics().playerRegions();
            final long transitions = after.walkTransitions() - before.walkTransitions()
                    + after.teleportTransitions() - before.teleportTransitions()
                    + after.portalTransitions() - before.portalTransitions()
                    + after.dimensionTransitions() - before.dimensionTransitions();
            final long expected = moving ? (long) PLAYER_TRANSITION_TICKS * players : 0L;
            assertEquals(expected, transitions, () -> "the " + (moving ? "moving" : "steady") + " row of " + players
                    + " players must publish one transition per player and publication");
            assertEquals(0L, after.invariantFailures() - before.invariantFailures(), "the player region invariant must never fail");
            final double totalMs = elapsed / 1_000_000.0;
            return new PlayerTransitionRow(players, PLAYER_TRANSITION_TICKS, moving ? "moving" : "steady", totalMs,
                    elapsed / (double) (PLAYER_TRANSITION_TICKS * players),
                    transitions, after.entries() - before.entries(), after.departures() - before.departures());
        } finally {
            RegionThreadingManager.clear();
        }
    }

    /** Runs one entity slice entry of the level, which is where the region of every player is published. */
    private static void publishPlayers(RegionThreadingEngine engine, ServerLevel level, List<AtomicReference<BlockPos>> positions, boolean moving) {
        if (moving) {
            for (int index = 0; index < positions.size(); index++) {
                // Alternate between the two regions: region 0 covers chunks 0..2, region 1 chunks 3..5.
                final boolean second = index % 2 == 0;
                positions.get(index).set(new BlockPos(second ? REGION_CHUNK_SIZE * 16 + 1 : 1, 64, 1));
            }
        }
        engine.beginEntityTickPlan(level, entity -> true, BOUNDED_BARRIER);
    }

    /** {@return a player of {@code level} at the mutable position of {@code position}} */
    private static ServerPlayer benchmarkPlayer(ServerLevel level, AtomicReference<BlockPos> position) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenAnswer(invocation -> position.get());
        when(player.chunkPosition()).thenAnswer(invocation -> {
            final BlockPos pos = position.get();
            return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        });
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getId()).thenReturn(nextEntityId());
        when(player.isRemoved()).thenReturn(false);
        when(player.isAlwaysTicking()).thenReturn(true);
        when(player.getPortalCooldown()).thenReturn(0);
        when(player.toString()).thenReturn("benchmark-player");
        return player;
    }

    private static int nextEntityId() {
        return ENTITY_IDS.getAndIncrement();
    }

    /**
     * Measures one block-entity configuration on a fresh engine and a fresh mock level, so no region
     * lease, regionizer entry or furnace state of another configuration is observed.
     */
    private static Result measureBlockEntityConfiguration(int workerThreads, int regions, int furnacesPerRegion) throws ReflectiveOperationException {
        final RegionThreadingEngine engine = installEngine(workerThreads, true, false);
        try {
            final ServerLevel level = mockLevel();
            final List<TickingBlockEntity> tickers = buildFurnaceTickers(level, attachEntityManager(level), regions, furnacesPerRegion);
            for (int region = 0; region < regions; region++) {
                // Loaded sections keep the fixed regions alive across the batches of this configuration.
                engine.addRegionSection(level, interiorChunkX(region), interiorChunkZ(region));
            }
            for (int tick = 0; tick < WARMUP_TICKS; tick++) {
                tickBlockEntities(engine, level, tickers);
            }

            final List<Run> runs = new ArrayList<>(MEASURED_RUNS);
            for (int run = 0; run < MEASURED_RUNS; run++) {
                final RegionThreadingDiagnostics before = engine.diagnostics();
                final long start = System.nanoTime();
                for (int tick = 0; tick < BLOCK_ENTITY_TICKS_PER_RUN; tick++) {
                    tickBlockEntities(engine, level, tickers);
                }
                final long elapsed = System.nanoTime() - start;
                runs.add(new Run(elapsed, counters(engine.diagnostics(), before)));
            }
            return summarize("block-entity", workerThreads, regions, furnacesPerRegion, BLOCK_ENTITY_TICKS_PER_RUN, runs);
        } finally {
            RegionThreadingManager.clear();
        }
    }

    /**
     * Measures one scheduled-tick configuration on a fresh engine and a fresh mock level.
     */
    private static Result measureScheduledTickConfiguration(int workerThreads, int regions, int tasksPerRegion) {
        final RegionThreadingEngine engine = installEngine(workerThreads, false, true);
        try {
            final ServerLevel level = mockLevel();
            final List<ScheduledTick<Block>> ticks = scheduledTicks(regions, tasksPerRegion);
            for (int region = 0; region < regions; region++) {
                engine.addRegionSection(level, interiorChunkX(region), interiorChunkZ(region));
            }
            for (int tick = 0; tick < WARMUP_TICKS; tick++) {
                dispatchScheduledTicks(engine, level, ticks);
            }

            final List<Run> runs = new ArrayList<>(MEASURED_RUNS);
            for (int run = 0; run < MEASURED_RUNS; run++) {
                final RegionThreadingDiagnostics before = engine.diagnostics();
                final long start = System.nanoTime();
                for (int tick = 0; tick < SCHEDULED_TICKS_PER_RUN; tick++) {
                    dispatchScheduledTicks(engine, level, ticks);
                }
                final long elapsed = System.nanoTime() - start;
                runs.add(new Run(elapsed, counters(engine.diagnostics(), before)));
            }
            return summarize("scheduled-tick", workerThreads, regions, tasksPerRegion, SCHEDULED_TICKS_PER_RUN, runs);
        } finally {
            RegionThreadingManager.clear();
        }
    }

    /**
     * Runs one production block-entity tick: collects the snapshot and awaits the region batch.
     */
    private static void tickBlockEntities(RegionThreadingEngine engine, ServerLevel level, List<TickingBlockEntity> tickers) {
        final RegionBlockEntityTickPlan plan = engine.beginBlockEntityTickPlan(level, BOUNDED_BARRIER);
        if (plan == null) {
            throw new IllegalStateException("the block-entity tick slice must be enabled for the benchmark");
        }
        for (final TickingBlockEntity ticker : tickers) {
            if (!plan.accept(ticker)) {
                throw new IllegalStateException("the benchmark ticker at " + ticker.getPos() + " was not admitted to the worker batch");
            }
        }
        plan.executeAndAwait();
    }

    /**
     * Runs one scheduled block tick phase through the dispatcher the engine hands to {@code LevelTicks}.
     * The callbacks only burn the fixed CPU cost; they do not touch the level, so the measured time is
     * the batch barrier and the region lease. The will-tick hook of {@code LevelTicks} is a no-op here: this
     * harness hands the ticks to the dispatcher directly, so there is no collected-tick set to maintain.
     */
    private static void dispatchScheduledTicks(RegionThreadingEngine engine, ServerLevel level, List<ScheduledTick<Block>> ticks) {
        final LevelTicks.TickDispatcher<Block> dispatcher = engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, BOUNDED_BARRIER);
        if (dispatcher == null) {
            throw new IllegalStateException("the scheduled tick slice must be enabled for the benchmark");
        }
        dispatcher.runCollected(ticks, (pos, type) -> burnCpu(), (pos, type) -> burnCpu(), tick -> {});
    }

    private static RegionThreadingEngine installEngine(int workerThreads, boolean blockEntityTickWorkers, boolean scheduledTickWorkers) {
        final RegionThreadingEngine engine = new RegionThreadingEngine(workerThreads, false, false, false, blockEntityTickWorkers, scheduledTickWorkers, false, RegionLayout.FIXED_3X3);
        if (scheduledTickWorkers) {
            // Production keeps unclassified scheduled callbacks on the server thread; the harness opts into
            // the internal mechanics seam to measure the dispatcher itself.
            engine.enableUnclassifiedWorkerCallbacksForTesting();
        }
        RegionThreadingManager.install(engine);
        return engine;
    }

    /**
     * {@return the engine counters this harness reports, relative to {@code before}}
     */
    private static Counters counters(RegionThreadingDiagnostics after, RegionThreadingDiagnostics before) {
        return new Counters(
                after.totalRegionTickCount() - before.totalRegionTickCount(),
                after.blockEntityTicksOnWorkers() - before.blockEntityTicksOnWorkers(),
                after.fallbackBlockEntityTicks() - before.fallbackBlockEntityTicks(),
                after.scheduledTicksOnWorkers() - before.scheduledTicksOnWorkers(),
                after.fallbackScheduledTicks() - before.fallbackScheduledTicks(),
                after.skippedScheduledTicks() - before.skippedScheduledTicks(),
                after.rejectedWorkerTaskCount() - before.rejectedWorkerTaskCount());
    }

    /**
     * One row of the player region transition measurement.
     *
     * @param players            the players whose region was published per tick
     * @param ticks              the publications the row measured
     * @param mode               {@code steady} for players that stay in their region, {@code moving} for one
     *                           teleport per player and publication
     * @param totalMs            the wall time of the row
     * @param nanosPerPlayerTick the wall time per player and publication
     * @param transitions        the transitions the engine counted for the row
     * @param entries            the region entries the engine counted for the row
     * @param departures         the region departures the engine counted for the row
     */
    private record PlayerTransitionRow(int players, int ticks, String mode, double totalMs, double nanosPerPlayerTick,
            long transitions, long entries, long departures) {}

    /**
     * {@return the median run of one configuration}
     * <p>
     * Every region tick of a batch is recorded once in the per-region tick metrics, so the
     * {@code totalRegionTickCount} delta of a run is the number of regions the batches of that run
     * actually leased.
     */
    private static Result summarize(String workload, int workerThreads, int regions, int workPerRegion, int ticksPerRun, List<Run> runs) {
        final List<Run> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingLong(Run::totalNanos));
        final Run median = sorted.get(sorted.size() / 2);
        for (final Run run : runs) {
            assertTrue(run.counters().isNonNegative(), () -> "the engine counters of " + workload + " with " + workerThreads + " workers went negative: " + run.counters());
        }
        final double totalMs = median.totalNanos() / 1_000_000.0;
        return new Result(workload, workerThreads, regions, workPerRegion, ticksPerRun, totalMs, totalMs / ticksPerRun, median.counters());
    }

    private static void printCsv(String workload, List<Result> results) {
        System.out.println();
        System.out.println("CSV " + workload + " (median of " + MEASURED_RUNS + " measured runs; counters are the totals of the median run)");
        System.out.println(CSV_HEADER);
        for (final Result result : results) {
            final Counters counters = result.counters();
            System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%.3f,%.6f,%.2f,%d,%d,%d,%d,%d,%d,%d%n",
                    result.workload(),
                    result.workerThreads(),
                    result.regions(),
                    result.workPerRegion(),
                    result.ticksPerRun(),
                    result.totalMs(),
                    result.msPerTick(),
                    speedupVsOneWorker(result, results),
                    counters.leasedRegions(),
                    counters.blockEntityTicksOnWorkers(),
                    counters.fallbackBlockEntityTicks(),
                    counters.scheduledTicksOnWorkers(),
                    counters.fallbackScheduledTicks(),
                    counters.skippedScheduledTicks(),
                    counters.rejectedWorkerTasks());
        }
    }

    /**
     * {@return the speedup of {@code result} against the one-worker row of the same work shape}
     */
    private static double speedupVsOneWorker(Result result, List<Result> results) {
        if (result.workerThreads() == 1) {
            return 1.0;
        }
        for (final Result baseline : results) {
            if (baseline.workerThreads() == 1 && baseline.regions() == result.regions() && baseline.workPerRegion() == result.workPerRegion()) {
                return baseline.msPerTick() / result.msPerTick();
            }
        }
        throw new IllegalStateException("missing the one-worker baseline of " + result.workload() + " with " + result.regions() + " regions and " + result.workPerRegion() + " work per region");
    }

    /**
     * Builds the tickers of one block-entity configuration: one interior chunk per fixed region, holding
     * real furnaces and their real bound tickers.
     */
    private static List<TickingBlockEntity> buildFurnaceTickers(ServerLevel level, PersistentEntitySectionManager<Entity> entityManager, int regions, int furnacesPerRegion) throws ReflectiveOperationException {
        final List<TickingBlockEntity> tickers = new ArrayList<>(regions * furnacesPerRegion);
        final List<ChunkPos> chunks = new ArrayList<>(regions);
        for (int region = 0; region < regions; region++) {
            final int chunkX = interiorChunkX(region);
            final int chunkZ = interiorChunkZ(region);
            final ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            chunks.add(chunkPos);
            final LevelChunk chunk = furnaceChunk(level, chunkPos, furnacesPerRegion);
            for (int index = 0; index < furnacesPerRegion; index++) {
                tickers.add(boundTicker(chunk, furnace(level, furnacePos(chunkX, chunkZ, index))));
            }
        }
        markChunksEntityLoaded(entityManager, chunks);
        return tickers;
    }

    private static List<ScheduledTick<Block>> scheduledTicks(int regions, int tasksPerRegion) {
        final List<ScheduledTick<Block>> ticks = new ArrayList<>(regions * tasksPerRegion);
        for (int region = 0; region < regions; region++) {
            for (int task = 0; task < tasksPerRegion; task++) {
                ticks.add(ScheduledTick.probe(Blocks.FURNACE, furnacePos(interiorChunkX(region), interiorChunkZ(region), task)));
            }
        }
        return ticks;
    }

    /**
     * {@return the interior chunk X of the fixed region with {@code region} as its index}
     * <p>
     * A furnace at a boundary chunk of a fixed region could not be leased, because its tick may write
     * into a neighboring region, so every workload position is inside the region's center chunk.
     */
    private static int interiorChunkX(int region) {
        return (region % REGIONS_PER_ROW) * REGION_CHUNK_SIZE + 1;
    }

    private static int interiorChunkZ(int region) {
        return (region / REGIONS_PER_ROW) * REGION_CHUNK_SIZE + 1;
    }

    /**
     * {@return the {@code index}th furnace position of one interior chunk} At most 64 positions fit, in an
     * 8 by 8 grid at the harness height.
     */
    private static BlockPos furnacePos(int chunkX, int chunkZ, int index) {
        return new BlockPos(chunkX * 16 + (index & 7), FURNACE_Y, chunkZ * 16 + (index >> 3));
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, withSettings().defaultAnswer(CALLS_REAL_METHODS).stubOnly());
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("benchmark-level").when(level).toString();
        doReturn(-64).when(level).getMinBuildHeight();
        doReturn(384).when(level).getHeight();
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        doReturn(biomeRegistry()).when(registryAccess).registryOrThrow(Registries.BIOME);
        doReturn(registryAccess).when(level).registryAccess();
        doReturn(new WorldBorder()).when(level).getWorldBorder();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        // The group random of a block-entity group is derived from the level's dimension, seed and game time.
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            awaitBounded(invocation.getArgument(0));
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        setField(Level.class, level, "random", RandomSource.create(1234L));
        return level;
    }

    /**
     * Attaches a real guarded entity manager whose chunk loads complete immediately. The manager decides
     * the entity-load eligibility of every furnace, and its storage access check rejects a native worker
     * that reads the server-owned storage.
     */
    @SuppressWarnings("unchecked")
    private static PersistentEntitySectionManager<Entity> attachEntityManager(ServerLevel level) {
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any())).thenAnswer(invocation -> CompletableFuture.completedFuture(new ChunkEntities<Entity>(invocation.getArgument(0), List.of())));
        final PersistentEntitySectionManager<Entity> manager = new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback<Entity>) mock(LevelCallback.class),
                storage,
                RegionThreadingHooks::assertServerEntityStorageAccess,
                RegionLayout.FIXED_3X3);
        setField(ServerLevel.class, level, "entityManager", manager);
        return manager;
    }

    private static void markChunksEntityLoaded(PersistentEntitySectionManager<Entity> entityManager, List<ChunkPos> chunks) {
        for (final ChunkPos chunk : chunks) {
            entityManager.updateChunkStatus(chunk, FullChunkStatus.BLOCK_TICKING);
        }
        entityManager.tick();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Registry<Biome> biomeRegistry() {
        return (Registry<Biome>) mock(Registry.class);
    }

    /**
     * Builds a real {@link LevelChunk} whose furnace positions hold a real furnace block state. The
     * sections carry real block-state palettes; only the biome palette is a placeholder, because nothing
     * in the benchmark reads a biome.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static LevelChunk furnaceChunk(ServerLevel level, ChunkPos chunkPos, int furnacesPerRegion) {
        final LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class, withSettings().stubOnly()));
        }
        final LevelChunkSection furnaceSection = sections[level.getSectionIndex(FURNACE_Y)];
        for (int index = 0; index < furnacesPerRegion; index++) {
            final BlockPos pos = furnacePos(chunkPos.x, chunkPos.z, index);
            furnaceSection.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, Blocks.FURNACE.defaultBlockState());
        }
        final LevelChunk chunk = new LevelChunk(level, chunkPos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
        return chunk;
    }

    private static FurnaceBlockEntity furnace(ServerLevel level, BlockPos pos) {
        final FurnaceBlockEntity furnace = new FurnaceBlockEntity(pos, Blocks.FURNACE.defaultBlockState());
        furnace.setLevel(level);
        // A lit furnace with an empty inventory runs the vanilla tick without touching the level: it only
        // decrements the burn time. A large burn time keeps every measured tick identical.
        setField(AbstractFurnaceBlockEntity.class, furnace, "litTime", LIT_TIME_TICKS);
        return furnace;
    }

    /**
     * {@return the real bound ticker the chunk registers for {@code furnace}} The bound ticker is the
     * production {@code LevelChunk.BoundTickingBlockEntity}; the chunk factory is private, so the
     * benchmark reaches it the same way the vanilla chunk does.
     */
    @SuppressWarnings("unchecked")
    private static TickingBlockEntity boundTicker(LevelChunk chunk, FurnaceBlockEntity furnace) throws ReflectiveOperationException {
        final Method createTicker = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        createTicker.setAccessible(true);
        return (TickingBlockEntity) createTicker.invoke(chunk, furnace, (BlockEntityTicker<FurnaceBlockEntity>) AbstractFurnaceBlockEntity::serverTick);
    }

    /**
     * Burns the fixed CPU cost of one scheduled tick callback. The volatile write keeps the loop
     * observable; it is a write only, so parallel tasks never contend on the sink.
     */
    private static void burnCpu() {
        long value = 1L;
        for (int step = 0; step < CPU_STEPS_PER_TASK; step++) {
            value = value * 31L + step;
        }
        cpuSink = value;
    }

    private static void awaitBounded(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TICK_TIMEOUT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0L) {
                throw new IllegalStateException("timed out after " + TimeUnit.NANOSECONDS.toSeconds(TICK_TIMEOUT_NANOS) + " s waiting for a region batch");
            }
            LockSupport.parkNanos(100_000L);
        }
    }

    private static void setField(Class<?> declaringClass, Object target, String name, Object value) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }

    /**
     * One measured run of one configuration.
     */
    private record Run(long totalNanos, Counters counters) {}

    /**
     * Engine counters of one measured run, all relative to the start of that run.
     */
    private record Counters(long leasedRegions, long blockEntityTicksOnWorkers, long fallbackBlockEntityTicks, long scheduledTicksOnWorkers,
            long fallbackScheduledTicks, long skippedScheduledTicks, long rejectedWorkerTasks) {
        boolean isNonNegative() {
            return this.leasedRegions >= 0L
                    && this.blockEntityTicksOnWorkers >= 0L
                    && this.fallbackBlockEntityTicks >= 0L
                    && this.scheduledTicksOnWorkers >= 0L
                    && this.fallbackScheduledTicks >= 0L
                    && this.skippedScheduledTicks >= 0L
                    && this.rejectedWorkerTasks >= 0L;
        }
    }

    /**
     * One CSV row of the benchmark matrix.
     */
    private record Result(String workload, int workerThreads, int regions, int workPerRegion, int ticksPerRun, double totalMs, double msPerTick, Counters counters) {}
}
