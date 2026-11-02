/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
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
 * attribution is disabled again when the window ends, so the entity test and every other batch stay
 * uninstrumented.
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
 * The tests only measure: they pass as long as the workload ran and never assert a duration. A run without
 * the {@value #ENABLED_PROPERTY} system property does no work, so the normal GameTest batches stay as fast
 * as before; the measurement script sets it through {@code -PserverBenchmark=true} and greps the
 * {@code SERVER_BENCH_} lines from the log.
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
     * Zombies of the entity worker diagnostics workload. The population only has to keep the region workers
     * busy; the reported numbers are per operation, not per entity.
     */
    private static final int ENTITY_WORKER_ZOMBIE_COUNT = 64;
    /**
     * Message prefix of {@code RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess}. The suffix
     * is the operation the guard rejected, so the blocker table is keyed by that operation.
     */
    private static final String STORAGE_ACCESS_REJECTION_PREFIX = "Native region worker attempted server-owned entity storage access: ";

    static final String FURNACE_CSV_HEADER = "profile,workers,regionSize,furnaces,chunks,msptAvg,msptP50,msptP95,msptP99,blockEntityTicksOnWorkers,fallbackBlockEntityTicks,leasedRegions,ownershipViolations,planMs,leaseMs,awaitMs,handoffMs,sliceMs,sliceSamples";
    static final String ENTITY_CSV_HEADER = "profile,workers,regionSize,zombies,items,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,deferredEntityTicks,sectionUpdatesApplied,sectionUpdatesDropped,ownershipViolations,zombieReplacements,regionMismatches";
    static final String ENTITY_WORKER_CSV_HEADER = "profile,workers,regionSize,zombies,chunks,msptAvg,msptP50,msptP95,msptP99,entityTicksOnWorkers,fallbackEntityTicks,skippedEntityTicks,deferredEntityTicks,entityMutationsAllowed,entityMutationsRejected,entityQueriesAllowed,entityQueriesTruncated,entityQueriesRejected,ownershipViolations,sectionUpdatesApplied,sectionUpdatesDropped,pendingTransfers,rememberedTransfers,rejectedTransferRecords,legacyDeferredMoves,retriedDeferredMoves,regionMismatches,zombieSurvivors,entityTickFailures,distinctBlockers";
    static final String ENTITY_WORKER_BLOCKER_HEADER = "operation,count,firstFrame,entityTypes";

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
            final List<Zombie> zombies = new ArrayList<>(ENTITY_WORKER_ZOMBIE_COUNT);
            final TickSampler sampler = new TickSampler(WARMUP_TICKS, MEASURED_TICKS, null);
            final EntityWorkerBlockers blockers = new EntityWorkerBlockers();
            final AtomicReference<Optional<RegionThreadingDiagnostics>> before = new AtomicReference<>(Optional.empty());
            final AtomicReference<InstalledEngine> replaced = new AtomicReference<>();
            final AtomicBoolean cleaned = new AtomicBoolean();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            helper.addEndListener(passed -> {
                if (cleaned.compareAndSet(false, true)) {
                    NeoForge.EVENT_BUS.unregister(sampler);
                    cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, Optional.ofNullable(replaced.get()));
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
                        spawnZombies(level, grid, origin, zombies, helper);
                    })
                    .thenExecute(() -> {
                        replaced.set(installEntityWorkerEngine(blockers));
                        before.set(RegionThreadingHooks.diagnostics());
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                    })
                    .thenIdle(WARMUP_TICKS + MEASURED_TICKS + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        // The counters of the diagnostic engine are read before it is replaced again.
                        reportEntityWorkers(helper, sampler, before.get(), zombies, blockers, grid.size());
                        if (cleaned.compareAndSet(false, true)) {
                            cleanupEntityWorkerWindow(level, grid, zombies, daylight, previousDaylight, Optional.ofNullable(replaced.get()));
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
     * {@return the absolute furnace positions of the workload}
     * <p>
     * The furnaces are distributed over the interior columns of the grid, round-robin, with distinct
     * positions per column and additional vertical layers when a column holds more than one chunk worth.
     * The positions only depend on {@code origin} and {@link #furnaceCount()}, never on the installed region
     * layout, so all measured profiles tick the same furnaces at the same places.
     */
    private static List<BlockPos> furnacePositions(BlockPos origin) {
        final List<ChunkPos> columns = new ArrayList<>();
        for (ChunkPos chunk : gridChunks(origin)) {
            if (Math.floorMod(chunk.x, 3) == INTERIOR_CHUNK_RESIDUE && Math.floorMod(chunk.z, 3) == INTERIOR_CHUNK_RESIDUE) {
                columns.add(chunk);
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalStateException("the benchmark grid has no interior chunk for the furnace columns");
        }
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
     * <p>
     * An unparsable override fails instead of silently measuring the default workload, so a mistyped
     * measurement knob cannot produce a row that looks like a different profile.
     */
    private static int furnaceCount() {
        final String configured = System.getProperty(FURNACE_COUNT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return FURNACE_COUNT;
        }
        final int count;
        try {
            count = Integer.parseInt(configured.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(FURNACE_COUNT_PROPERTY + " is not an integer: " + configured, exception);
        }
        if (count < 0) {
            throw new IllegalArgumentException(FURNACE_COUNT_PROPERTY + " must not be negative: " + count);
        }
        return count;
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
    }

    /**
     * Spawns the zombie population of the entity worker diagnostics workload, spread over the grid.
     * <p>
     * The zombies keep their vanilla AI and carry nothing, so the measured operations are the ones a plain
     * {@code Zombie.tick} reaches: despawn, collisions, pathfinding, target selection and movement.
     */
    private static void spawnZombies(ServerLevel level, List<ChunkPos> grid, BlockPos origin, List<Zombie> zombies, ExtendedGameTestHelper helper) {
        final RandomSource random = RandomSource.create(0x5EED5EEDL);
        for (int index = 0; index < ENTITY_WORKER_ZOMBIE_COUNT; index++) {
            final ChunkPos chunk = grid.get(index % grid.size());
            final Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
            zombie.moveTo(chunk.getMinBlockX() + 8.5, origin.getY() + 2, chunk.getMinBlockZ() + 8.5, random.nextFloat() * 360.0F, 0.0F);
            // Without a nearby player a mob despawns; the diagnostic needs the population to stay put.
            zombie.setPersistenceRequired();
            helper.assertTrue(level.addFreshEntity(zombie), "the entity worker diagnostics could not spawn a benchmark zombie");
            zombies.add(zombie);
        }
    }

    /**
     * Removes the workload, rebuilds the configured engine and releases the forced grid.
     * <p>
     * Runs from the measured window and from the end listener, so a failing or timing-out test never leaves
     * the diagnostic engine installed or the grid forced.
     */
    private static void cleanupEntityWorkerWindow(ServerLevel level, List<ChunkPos> grid, List<Zombie> zombies, GameRules.BooleanValue daylight, boolean previousDaylight, Optional<InstalledEngine> replaced) {
        for (Zombie zombie : zombies) {
            zombie.discard();
        }
        zombies.clear();
        replaced.ifPresent(ServerLoadBenchmarkGameTests::restoreConfiguredEngine);
        daylight.set(previousDaylight, level.getServer());
        releaseChunks(level, grid, ENTITY_TICKING_TICKET_RADIUS);
    }

    /**
     * Replaces the installed engine with the diagnostics engine of this run.
     * <p>
     * The diagnostic engine has the same layout, worker count and slices as the configured engine, but
     * region-owned entity storage and entity tick worker execution enabled. The swap runs on the server
     * thread outside any batch: {@link RegionThreadingManager#clear()} shuts the configured engine down
     * before the diagnostic one is installed.
     *
     * @return the flags and layout of the replaced engine, so the configured engine can be rebuilt afterwards
     */
    private static InstalledEngine installEntityWorkerEngine(EntityWorkerBlockers blockers) {
        final RegionThreading installed = RegionThreadingManager.getOrThrow();
        if (!(installed instanceof RegionThreadingEngine configured)) {
            throw new IllegalStateException("the entity worker diagnostics need the native region threading engine, got " + installed.getClass().getName());
        }
        final InstalledEngine replaced = new InstalledEngine(configured.diagnostics(), configured.regionLayout());
        RegionThreadingManager.clear();
        final RegionThreadingEngine diagnostic = replaced.rebuild(true, RegionEntityStorageMode.REGION_OWNED);
        diagnostic.setEntityTickFailureObserverForTesting(blockers::record);
        RegionThreadingManager.install(diagnostic);
        return replaced;
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
     * Prints the entity worker diagnostics: the counters of the measured window, the region mismatches and
     * one line per rejected operation, ordered by count.
     */
    private static void reportEntityWorkers(ExtendedGameTestHelper helper, TickSampler sampler, Optional<RegionThreadingDiagnostics> before, List<Zombie> zombies, EntityWorkerBlockers blockers, int chunks) {
        final long[] sorted = sampler.sortedSamples();
        helper.assertTrue(sorted.length > 0, "the tick sampler recorded no server tick");
        final Optional<RegionThreadingDiagnostics> after = RegionThreadingHooks.diagnostics();
        final String profile = profileName(after);
        final int mismatches = regionMismatches(helper.getLevel(), zombies);
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
                + " zombieSurvivors=" + survivors + "/" + zombies.size()
                + " regionMismatches=" + mismatches
                + " entityTickFailures=" + blockers.failureCount()
                + " distinctBlockers=" + blockers.blockerCount());
        System.out.println("SERVER_BENCH_ENTITY_WORKER_HEADER " + ENTITY_WORKER_CSV_HEADER);
        System.out.printf(Locale.ROOT, "SERVER_BENCH_ENTITY_WORKER_ROW %s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                profile,
                after.map(RegionThreadingDiagnostics::workerThreadCount).orElse(0),
                after.map(RegionThreadingDiagnostics::regionChunkSize).orElse(0),
                ENTITY_WORKER_ZOMBIE_COUNT,
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
                mismatches,
                survivors,
                blockers.failureCount(),
                blockers.blockerCount());
        System.out.println("SERVER_BENCH_ENTITY_WORKER_BLOCKER_HEADER " + ENTITY_WORKER_BLOCKER_HEADER);
        blockers.printBlockers();
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
                    + " measuredTicks=" + MEASURED_TICKS
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
