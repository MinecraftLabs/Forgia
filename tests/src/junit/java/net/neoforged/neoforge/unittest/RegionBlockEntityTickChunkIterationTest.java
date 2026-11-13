/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickChunk;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickChunkCache;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the chunk-indexed block-entity tick iteration of the region worker slice.
 * <p>
 * The plan decides per chunk - is the chunk block-ticking, did its entities finish loading, is its admission
 * snapshot current - and hands the chunk and its snapshot to the workers, which iterate the admitted tickers
 * themselves. The tests drive the real engine, the real plan, the real snapshot and the real worker batch
 * against a {@link org.mockito.Mockito#CALLS_REAL_METHODS} level and real {@link LevelChunk} instances, so
 * the chunk that holds a snapshot, the ticker revision the patch bumps, the level's chunk index and the
 * unload that releases the snapshot are the production ones.
 * <p>
 * The central test compares the execution trace of the chunk-indexed plan with the trace the per-ticker
 * implementation produced: it computes that reference trace from the same randomized chunk and ticker states
 * (block-ticking range, full status, entity-load eligibility, removed tickers, rebinds, non-ticking chunks,
 * undeclared and boundary types) and compares it region by region, because a region is the unit a worker
 * group ticks and the only order the slice ever preserved.
 */
@Timeout(120)
class RegionBlockEntityTickChunkIterationTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    /** Chunk 1,1 is the interior chunk of fixed region 0,0, so an admitted type there may run on a worker. */
    private static final ChunkPos INTERIOR_CHUNK = new ChunkPos(1, 1);
    /** Chunk 4,1 is the interior chunk of fixed region 1,0, a region no boundary chunk of this test shares. */
    private static final ChunkPos SECOND_INTERIOR_CHUNK = new ChunkPos(4, 1);
    /** Chunk 7,1 is the interior chunk of fixed region 2,0, the second region without a boundary chunk. */
    private static final ChunkPos THIRD_INTERIOR_CHUNK = new ChunkPos(7, 1);
    /** Chunk 0,1 borders fixed region -1,0, so an admitted type there keeps its whole region on the server thread. */
    private static final ChunkPos BOUNDARY_CHUNK = new ChunkPos(0, 1);
    /** Types the randomized workload draws from: three declared types and one that is not declared. */
    private static final List<String> TYPES = List.of("minecraft:furnace", "minecraft:hopper", "minecraft:smoker", "minecraft:chest");
    /** The declared and the undeclared type a rebind toggles between, so a rebind always flips the decision. */
    private static final String DECLARED_TYPE = "minecraft:hopper";
    private static final String UNDECLARED_TYPE = "minecraft:chest";
    private static final int EQUIVALENCE_ROUNDS = 60;

    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final Map<Long, java.util.concurrent.atomic.AtomicBoolean> blockTickingStatus = new HashMap<>();
    private final List<Long> loadedChunks = new ArrayList<>();
    private final List<Long> tickableChunks = new ArrayList<>();
    private final List<String> ticks = new CopyOnWriteArrayList<>();
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
    }

    @Test
    void theChunkIndexedPlanTicksTheSameTraceAsThePerTickerImplementationOverRandomizedStates() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final RandomSource random = RandomSource.create(20240117L);
        final List<ChunkPos> chunkPool = List.of(INTERIOR_CHUNK, SECOND_INTERIOR_CHUNK, THIRD_INTERIOR_CHUNK, BOUNDARY_CHUNK);
        final Map<ChunkPos, LevelChunk> chunkByPos = new LinkedHashMap<>();
        final Map<ChunkPos, List<WorkloadTicker>> tickersByChunk = new LinkedHashMap<>();
        for (final ChunkPos pos : chunkPool) {
            chunkByPos.put(pos, this.loadChunk(level, pos));
            this.loadedChunks.add(pos.toLong());
            this.tickableChunks.add(pos.toLong());
            tickersByChunk.put(pos, new ArrayList<>());
        }
        for (final ChunkPos pos : chunkPool) {
            for (int index = 0; index < random.nextInt(4); index++) {
                tickersByChunk.get(pos).add(this.addTicker(chunkByPos.get(pos), tickersByChunk.get(pos).size(), pos, random, null));
            }
        }

        for (int round = 0; round < EQUIVALENCE_ROUNDS; round++) {
            this.mutate(random, level, chunkByPos, tickersByChunk);
            final List<WorkloadTicker> all = new ArrayList<>();
            for (final List<WorkloadTicker> tickers : tickersByChunk.values()) {
                all.addAll(tickers);
            }
            final List<String> reference = this.referenceTrace(level, all);
            final List<String> planned = this.collectAndObserve(level);
            assertEquals(tracesByRegion(reference), tracesByRegion(planned),
                    "the chunk-indexed plan diverged from the per-ticker implementation in round " + round
                            + "\nreference: " + reference + "\nplanned:   " + planned);
        }
    }

    @Test
    void theSnapshotOfAnUnchangedChunkIsReusedAcrossTicks() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, SECOND_INTERIOR_CHUNK);
        this.loadedChunks.add(SECOND_INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(SECOND_INTERIOR_CHUNK.toLong());
        for (int index = 0; index < 3; index++) {
            this.registerTicker(chunk, this.position(SECOND_INTERIOR_CHUNK, index), DECLARED_TYPE);
        }

        final List<RegionBlockEntityTickAdmission> first = this.collectedAdmissions(level);
        final List<RegionBlockEntityTickAdmission> second = this.collectedAdmissions(level);

        assertEquals(3, first.size());
        for (int index = 0; index < first.size(); index++) {
            assertSame(first.get(index), second.get(index),
                    "an unchanged chunk must reuse the admissions of its snapshot instead of capturing them again");
        }
        assertNotNull(chunk.blockEntityTickAdmissionCache());
    }

    @Test
    void theSnapshotIsInvalidatedByTheTickerSetAndByTheLayout() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        final int liveBefore = RegionBlockEntityTickChunkCache.liveSnapshotCount();

        this.registerTicker(chunk, this.position(INTERIOR_CHUNK, 0), DECLARED_TYPE);
        this.collectAndExecute(level);
        final RegionBlockEntityTickChunkCache snapshot = chunk.blockEntityTickAdmissionCache();
        assertNotNull(snapshot, "the first plan must build the snapshot of the chunk");
        assertEquals(liveBefore + 1, RegionBlockEntityTickChunkCache.liveSnapshotCount());
        final long revision = chunk.blockEntityTickerRevision();
        assertTrue(snapshot.isCurrent(RegionLayout.FIXED_3X3, revision), "a fresh snapshot must be current");
        assertFalse(snapshot.isCurrent(RegionLayout.fixed(5), revision),
                "a changed region layout must invalidate the interior-chunk flag and the region of the snapshot");
        assertFalse(snapshot.isCurrent(RegionLayout.FIXED_3X3, revision + 1L),
                "a changed ticker revision must invalidate the snapshot");

        // A ticker added to the chunk and a ticker removed from it both change the ticker set, so both have
        // to invalidate the snapshot through the chunk's revision.
        final WorkloadTicker added = this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(7L), DECLARED_TYPE);
        assertNotEquals(revision, chunk.blockEntityTickerRevision(), "adding a ticker must bump the revision");
        final long afterAdd = chunk.blockEntityTickerRevision();
        this.removeTicker(chunk, added);
        assertNotEquals(afterAdd, chunk.blockEntityTickerRevision(), "removing a ticker must bump the revision");

        this.collectAndExecute(level);
        assertNotSame(snapshot, chunk.blockEntityTickAdmissionCache(), "a changed ticker set must rebuild the snapshot");
        assertEquals(liveBefore + 1, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "a rebuilt snapshot must release the snapshot it replaces");

        chunk.clearAllBlockEntities();
        assertNull(chunk.blockEntityTickAdmissionCache(), "an unloaded chunk must release its snapshot");
        assertEquals(liveBefore, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "an unloaded chunk must not keep its snapshot live");
    }

    @Test
    void aRebindBetweenTicksRebuildsTheSnapshotInsteadOfReusingTheOldDecision() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        final WorkloadTicker ticker = this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(11L), DECLARED_TYPE);

        final List<RegionBlockEntityTickAdmission> first = this.collectedAdmissions(level);
        assertEquals(1, first.size());
        assertTrue(first.get(0).isAdmitted(), "the declared hopper of an interior chunk must be admitted");

        // A rebind replaces the delegate of the position's wrapper, which is exactly what the chunk's ticker
        // revision records, so the snapshot and the cached allowlist decision must both be re-evaluated.
        ticker.rebind(UNDECLARED_TYPE);
        this.bumpRevision(chunk);

        final List<RegionBlockEntityTickAdmission> second = this.collectedAdmissions(level);
        assertEquals(1, second.size());
        assertFalse(second.get(0).isAdmitted(),
                "the rebound type is not declared, so its whole region must keep its tickers on the server thread");
        assertNotSame(first.get(0), second.get(0), "a rebound ticker must be captured again");
    }

    @Test
    void aSnapshotThatWentStaleBetweenPlanAndExecutionIsRevalidatedOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        final WorkloadTicker ticker = this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(13L), DECLARED_TYPE);

        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.collect();
        assertEquals(1, plan.collectedChunks().size(), "the interior hopper chunk must be handed to the workers");
        // The chunk changes its ticker set after the plan handed it over and before the worker ticks it, so
        // the snapshot the plan captured no longer describes the chunk. The worker must not tick it.
        this.bumpRevision(chunk);

        this.ticks.clear();
        plan.executeAndAwait();

        assertEquals(List.of(ticker.name() + "@server"), this.ticks,
                "a stale snapshot must be ticked on the server thread instead of from the stale chunk list");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertEquals(1L, this.engine.diagnostics().staleBlockEntityChunkSnapshots());
    }

    @Test
    void aChunkThatIsNotBlockTickingProducesNoWorkerTick() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(17L), DECLARED_TYPE);

        // Out of the block-ticking range: the vanilla loop does not tick the ticker either.
        this.tickableChunks.remove(INTERIOR_CHUNK.toLong());
        this.ticks.clear();
        this.collectAndExecute(level);
        assertEquals(List.of(), this.ticks, "a chunk outside the block ticking range must not tick");
        assertEquals(1L, this.engine.diagnostics().nonTickingBlockEntityChunks());

        // In range, but the chunk did not reach the block-ticking full status yet.
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        chunk.setFullStatus(() -> FullChunkStatus.FULL);
        this.ticks.clear();
        this.collectAndExecute(level);
        assertEquals(List.of(), this.ticks, "a chunk below the block ticking status must not tick");
        assertEquals(2L, this.engine.diagnostics().nonTickingBlockEntityChunks());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());

        // A frozen tick rate skips the whole pass, like the vanilla loop's runsNormally() check.
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
        final TickRateManager tickRateManager = level.tickRateManager();
        when(tickRateManager.runsNormally()).thenReturn(false);
        this.ticks.clear();
        this.collectAndExecute(level);
        assertEquals(List.of(), this.ticks, "a frozen tick rate must not tick any block entity");
        assertEquals(3L, this.engine.diagnostics().nonTickingBlockEntityChunks());
    }

    @Test
    void aChunkWhoseEntitiesAreStillLoadingProducesNoWorkerTick() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.tickableChunks.add(INTERIOR_CHUNK.toLong());
        this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(19L), DECLARED_TYPE);

        // The entity load of the chunk is pending: the vanilla bound ticker skips its body, so the chunk is
        // not handed over and nothing of it ticks.
        this.loadedChunks.remove(INTERIOR_CHUNK.toLong());
        this.ticks.clear();
        this.collectAndExecute(level);
        assertEquals(List.of(), this.ticks, "a chunk whose entities are loading must not tick");
        assertEquals(1L, this.engine.diagnostics().entitiesLoadingBlockEntityChunks());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());

        // The completed load makes the same chunk admissible again.
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        this.ticks.clear();
        this.collectAndExecute(level);
        assertEquals(1, this.ticks.size(), "the completed entity load must hand the chunk to the workers again");
        assertTrue(this.ticks.get(0).endsWith("@worker"), this.ticks.toString());
    }

    @Test
    void aTickerOfABoundaryChunkStillFallsBackToTheServerThread() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, BOUNDARY_CHUNK);
        this.loadedChunks.add(BOUNDARY_CHUNK.toLong());
        this.tickableChunks.add(BOUNDARY_CHUNK.toLong());
        final WorkloadTicker ticker = this.addTicker(chunk, 0, BOUNDARY_CHUNK, RandomSource.create(23L), DECLARED_TYPE);

        this.ticks.clear();
        this.collectAndExecute(level);

        assertEquals(List.of(ticker.name() + "@server"), this.ticks,
                "a boundary chunk of a declared type must keep its whole region on the server thread");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void theBlockTickingRangeOfAChunkIsEvaluatedOncePerChunkPerTick() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final List<ChunkPos> chunkPool = List.of(INTERIOR_CHUNK, SECOND_INTERIOR_CHUNK, THIRD_INTERIOR_CHUNK);
        for (final ChunkPos pos : chunkPool) {
            final LevelChunk chunk = this.loadChunk(level, pos);
            this.loadedChunks.add(pos.toLong());
            this.tickableChunks.add(pos.toLong());
            for (int index = 0; index < 4; index++) {
                this.addTicker(chunk, index, pos, RandomSource.create(index), DECLARED_TYPE);
            }
        }

        this.collectAndExecute(level);

        // Twelve tickers in three chunks: the server thread must evaluate the range of a chunk once, not once
        // per ticker, so the per-tick server-thread cost is proportional to chunks.
        verify(level, times(chunkPool.size())).shouldTickBlocksAt(anyLong());
        verify(level, times(chunkPool.size())).areEntitiesLoaded(anyLong());
        assertEquals(12L, this.engine.diagnostics().blockEntityTicksOnWorkers(),
                "every ticker of the three interior chunks must tick on a worker");
    }

    @Test
    void chunkChurnKeepsOneSnapshotPerLoadedChunk() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final int liveBefore = RegionBlockEntityTickChunkCache.liveSnapshotCount();

        for (int load = 0; load < 32; load++) {
            final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
            this.loadedChunks.add(INTERIOR_CHUNK.toLong());
            this.tickableChunks.add(INTERIOR_CHUNK.toLong());
            this.addTicker(chunk, 0, INTERIOR_CHUNK, RandomSource.create(load), DECLARED_TYPE);
            this.collectAndExecute(level);
            assertNotNull(chunk.blockEntityTickAdmissionCache(), "a loaded chunk with tickers must hold one snapshot");

            // This is the unload path of ServerLevel.unload(LevelChunk).
            chunk.clearAllBlockEntities();
            assertNull(chunk.blockEntityTickAdmissionCache(), "an unloaded chunk must hold no snapshot");
            this.loadedChunks.remove(INTERIOR_CHUNK.toLong());
            this.tickableChunks.remove(INTERIOR_CHUNK.toLong());
        }

        assertEquals(liveBefore, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "chunk churn must not grow the set of live snapshots");
    }

    @Test
    void aRegionMergeKeepsTheSnapshotOfTheRelocatedChunk() throws ReflectiveOperationException {
        // The dynamic layout merges neighbouring 16x16-chunk sections. The snapshot caches no region state, so
        // a merge must neither invalidate nor break the plan of a chunk whose region was absorbed.
        this.installEngine(RegionPolicy.DYNAMIC);
        final ServerLevel level = mockLevel();
        final ChunkPos chunkPos = INTERIOR_CHUNK;
        final LevelChunk chunk = this.loadChunk(level, chunkPos);
        this.loadedChunks.add(chunkPos.toLong());
        this.tickableChunks.add(chunkPos.toLong());
        RegionThreadingHooks.addRegionSection(level, chunkPos.x, chunkPos.z);
        this.addTicker(chunk, 0, chunkPos, RandomSource.create(29L), DECLARED_TYPE);

        this.collectAndExecute(level);
        final RegionBlockEntityTickChunkCache snapshot = chunk.blockEntityTickAdmissionCache();
        assertNotNull(snapshot);
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers(), "the interior hopper must tick on a worker");

        // A neighbouring section arrives: its region absorbs the region of the chunk.
        RegionThreadingHooks.addRegionSection(level, chunkPos.x + 15, chunkPos.z);

        this.collectAndExecute(level);
        assertSame(snapshot, chunk.blockEntityTickAdmissionCache(),
                "a merge must not invalidate a snapshot that holds no region state");
        assertEquals(2L, this.engine.diagnostics().blockEntityTicksOnWorkers(),
                "the relocated chunk must still tick on a worker of its merged region");
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    /**
     * Runs the chunk-indexed plan over the current state and returns everything it did: one line per tick,
     * in execution order, naming the ticker and whether it ran on a region worker or on the server thread.
     */
    private List<String> collectAndObserve(ServerLevel level) {
        this.ticks.clear();
        this.collectAndExecute(level);
        return List.copyOf(this.ticks);
    }

    private void collectAndExecute(ServerLevel level) {
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.collect();
        plan.executeAndAwait();
    }

    private List<RegionBlockEntityTickAdmission> collectedAdmissions(ServerLevel level) {
        final RegionBlockEntityTickPlan plan = this.plan(level);
        plan.collect();
        final List<RegionBlockEntityTickAdmission> admissions = new ArrayList<>();
        for (final RegionBlockEntityTickChunk chunk : plan.collectedChunks()) {
            admissions.addAll(chunk.admissions());
        }
        plan.executeAndAwait();
        return admissions;
    }

    /**
     * Computes the trace the per-ticker implementation produced for the current state: every ticker of the
     * level in ticker order, skipped when it is removed, when the level's tick rate is frozen, when its chunk
     * is out of the block ticking range or below the block ticking status, or when its chunk's entities are
     * still loading. A region whose tickers are all admitted ticks on a worker; a region with at least one
     * undeclared type or boundary chunk keeps all of its tickers on the server thread, which is the
     * conservative whole-region fallback of the slice.
     */
    private List<String> referenceTrace(ServerLevel level, List<WorkloadTicker> tickers) {
        final List<WorkloadTicker> ordered = new ArrayList<>(tickers);
        ordered.sort((first, second) -> Long.compare(first.sequence(), second.sequence()));
        final boolean runsNormally = level.tickRateManager().runsNormally();
        final RegionLayout layout = this.engine.regionLayout();
        final List<WorkloadTicker> eligible = new ArrayList<>();
        final Map<String, Boolean> admittedByRegion = new LinkedHashMap<>();
        for (final WorkloadTicker ticker : ordered) {
            if (ticker.isRemoved()) {
                continue;
            }
            final ChunkPos pos = new ChunkPos(Math.floorDiv(ticker.position().getX(), 16), Math.floorDiv(ticker.position().getZ(), 16));
            final long chunkKey = pos.toLong();
            if (!runsNormally || !level.shouldTickBlocksAt(chunkKey)) {
                continue;
            }
            final LevelChunk chunk = this.chunks.get(chunkKey);
            if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
                continue;
            }
            if (!level.areEntitiesLoaded(chunkKey)) {
                continue;
            }
            eligible.add(ticker);
            final String region = layout.regionX(pos.x) + "," + layout.regionZ(pos.z);
            final boolean admitted = RegionTickCompatibility.requiresInteriorRegion(ticker.type())
                    && isInteriorChunk(layout, chunk.getPos());
            admittedByRegion.merge(region, admitted, Boolean::logicalAnd);
        }
        final List<String> trace = new ArrayList<>(eligible.size());
        for (final WorkloadTicker ticker : eligible) {
            final ChunkPos pos = new ChunkPos(Math.floorDiv(ticker.position().getX(), 16), Math.floorDiv(ticker.position().getZ(), 16));
            final String region = layout.regionX(pos.x) + "," + layout.regionZ(pos.z);
            trace.add(ticker.name() + (admittedByRegion.get(region) ? "@worker" : "@server"));
        }
        return trace;
    }

    /**
     * Groups a trace by the region its ticker belongs to, because a region is what one worker group ticks and
     * the slice never promised an order across the regions that run in parallel.
     */
    private Map<String, List<String>> tracesByRegion(List<String> trace) {
        final Map<String, List<String>> byRegion = new LinkedHashMap<>();
        for (final String line : trace) {
            final String name = line.substring(0, line.indexOf('@'));
            final WorkloadTicker ticker = this.tickersByName.get(name);
            assertNotNull(ticker, "the trace names a ticker the workload does not know: " + name);
            final ChunkPos pos = new ChunkPos(Math.floorDiv(ticker.position().getX(), 16), Math.floorDiv(ticker.position().getZ(), 16));
            final String region = this.engine.regionLayout().regionX(pos.x) + "," + this.engine.regionLayout().regionZ(pos.z);
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(line);
        }
        return byRegion;
    }

    /** {@return true when every chunk around {@code pos} belongs to the same region of {@code layout}} */
    private static boolean isInteriorChunk(RegionLayout layout, ChunkPos pos) {
        final int regionX = layout.regionX(pos.x);
        final int regionZ = layout.regionZ(pos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (layout.regionX(pos.x + dx) != regionX || layout.regionZ(pos.z + dz) != regionZ) {
                    return false;
                }
            }
        }
        return true;
    }

    private void installEngine(RegionPolicy policy) {
        final RegionThreadingEngine created = new RegionThreadingEngine(1, false, false, false, true,
                false, false, RegionLayout.of(policy), RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionBlockEntityTickPlan plan(ServerLevel level) {
        final RegionBlockEntityTickPlan created = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(created, "block entity tick worker execution must be enabled");
        return created;
    }

    private final Map<String, WorkloadTicker> tickersByName = new HashMap<>();

    /**
     * Registers one workload ticker in {@code chunk}. {@code slot} keeps the position and the name of the
     * tickers of a chunk distinct, and {@code type} overrides the randomized type when a test needs one.
     */
    private WorkloadTicker addTicker(LevelChunk chunk, int slot, ChunkPos pos, RandomSource random, String type) throws ReflectiveOperationException {
        final BlockPos position = new BlockPos(pos.getMinBlockX() + slot, 64, pos.getMinBlockZ() + random.nextInt(16));
        final String name = "ticker-" + pos.x + "," + pos.z + "#" + slot + "-" + this.tickersByName.size();
        final WorkloadTicker ticker = new WorkloadTicker(name, position, type != null ? type : TYPES.get(random.nextInt(TYPES.size())), this.ticks::add);
        ticker.register(this.registerTicker(chunk, position, ticker));
        this.tickersByName.put(name, ticker);
        return ticker;
    }

    private void removeTicker(LevelChunk chunk, WorkloadTicker ticker) throws ReflectiveOperationException {
        ticker.remove();
        this.tickersByName.remove(ticker.name());
        this.unregisterTicker(chunk, ticker);
    }

    private void mutate(RandomSource random, ServerLevel level, Map<ChunkPos, LevelChunk> chunkByPos, Map<ChunkPos, List<WorkloadTicker>> tickersByChunk) {
        final List<ChunkPos> positions = new ArrayList<>(chunkByPos.keySet());
        final ChunkPos pos = positions.get(random.nextInt(positions.size()));
        final LevelChunk chunk = chunkByPos.get(pos);
        final List<WorkloadTicker> tickers = tickersByChunk.get(pos);
        switch (random.nextInt(7)) {
            case 0 -> {
                if (tickers.size() < 4) {
                    try {
                        tickers.add(this.addTicker(chunk, tickers.size(), pos, random, null));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError("the chunk must expose its ticker hooks", exception);
                    }
                }
            }
            case 1 -> {
                if (!tickers.isEmpty()) {
                    try {
                        this.removeTicker(chunk, tickers.remove(random.nextInt(tickers.size())));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError("the chunk must expose its ticker hooks", exception);
                    }
                }
            }
            case 2 -> {
                if (!tickers.isEmpty()) {
                    // A rebind replaces the delegate of the wrapper, which is exactly what the chunk's ticker
                    // revision records, so the snapshot and the cached allowlist decision of the ticker must
                    // both be re-evaluated. The toggle always flips the decision, so a stale decision can
                    // never hide behind an unchanged one.
                    final WorkloadTicker ticker = tickers.get(random.nextInt(tickers.size()));
                    ticker.rebind(ticker.type().equals(DECLARED_TYPE) ? UNDECLARED_TYPE : DECLARED_TYPE);
                    this.bumpRevision(chunk);
                }
            }
            case 3 -> {
                final Long key = pos.toLong();
                if (!this.loadedChunks.remove(key)) {
                    this.loadedChunks.add(key);
                }
            }
            case 4 -> {
                final Long key = pos.toLong();
                if (!this.tickableChunks.remove(key)) {
                    this.tickableChunks.add(key);
                }
            }
            case 5 -> this.blockTickingStatus.get(pos.toLong()).set(!this.blockTickingStatus.get(pos.toLong()).get());
            case 6 -> {
                // A chunk unloads and a new chunk instance of the same position loads: the snapshot of the
                // old instance must not leak into the new one.
                chunk.clearAllBlockEntities();
                final LevelChunk reloaded = this.loadChunk(level, pos);
                chunkByPos.put(pos, reloaded);
                for (final WorkloadTicker ticker : tickers) {
                    try {
                        ticker.register(this.registerTicker(reloaded, ticker.position(), ticker));
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError("the chunk must expose its ticker hooks", exception);
                    }
                }
                this.loadedChunks.add(pos.toLong());
            }
            default -> throw new IllegalStateException();
        }
    }

    /**
     * Loads a real chunk of {@code pos} and makes it the chunk the level's chunk source returns, so the plan
     * resolves the same instance a server would see.
     */
    private LevelChunk loadChunk(ServerLevel level, ChunkPos pos) {
        final LevelChunk chunk = chunk(level, pos);
        // A loaded chunk registers the ticker of every block entity it holds, so a block entity registered
        // here gets the wrapper ticker a real level iterates.
        chunk.setLoaded(true);
        final java.util.concurrent.atomic.AtomicBoolean blockTicking = new java.util.concurrent.atomic.AtomicBoolean(true);
        chunk.setFullStatus(() -> blockTicking.get() ? FullChunkStatus.BLOCK_TICKING : FullChunkStatus.FULL);
        this.blockTickingStatus.put(pos.toLong(), blockTicking);
        this.chunks.put(pos.toLong(), chunk);
        return chunk;
    }

    /**
     * Registers {@code ticker}'s recording delegate as the chunk's ticker at its position, through the real
     * wrapper the chunk uses, and joins the level's chunk index like a ticker the chunk registered itself.
     */
    private TickingBlockEntity registerTicker(LevelChunk chunk, BlockPos position, WorkloadTicker ticker) throws ReflectiveOperationException {
        return this.registerWrapper(chunk, position, ticker.delegate());
    }

    private TickingBlockEntity registerTicker(LevelChunk chunk, BlockPos position, String type) throws ReflectiveOperationException {
        return this.registerWrapper(chunk, position, mockTicker(position, type));
    }

    /**
     * Puts {@code delegate} behind the real rebindable wrapper of {@code chunk} at {@code position}, joins the
     * level's chunk index and bumps the chunk's ticker revision, exactly like the chunk does for a block
     * entity it registers.
     */
    private TickingBlockEntity registerWrapper(LevelChunk chunk, BlockPos position, TickingBlockEntity delegate) throws ReflectiveOperationException {
        final TickingBlockEntity wrapper = wrappedTicker(chunk, delegate);
        this.tickersInLevel(chunk).put(position, wrapper);
        chunk.getLevel().registerBlockEntityTickerChunk(chunk);
        chunk.bumpBlockEntityTickerRevision();
        return wrapper;
    }

    private void unregisterTicker(LevelChunk chunk, WorkloadTicker ticker) throws ReflectiveOperationException {
        this.tickersInLevel(chunk).remove(ticker.position());
        chunk.getLevel().markBlockEntityTickerRemoved();
        chunk.bumpBlockEntityTickerRevision();
        if (this.tickersInLevel(chunk).isEmpty()) {
            chunk.getLevel().unregisterBlockEntityTickerChunk(chunk);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<BlockPos, TickingBlockEntity> tickersInLevel(LevelChunk chunk) throws ReflectiveOperationException {
        final Field field = LevelChunk.class.getDeclaredField("tickersInLevel");
        field.setAccessible(true);
        return (Map<BlockPos, TickingBlockEntity>) (Map) field.get(chunk);
    }

    /** Wraps {@code delegate} in the real rebindable wrapper the chunk registers for a block entity. */
    private static TickingBlockEntity wrappedTicker(LevelChunk chunk, TickingBlockEntity delegate) throws ReflectiveOperationException {
        final Class<?> wrapperClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper");
        final var constructor = wrapperClass.getDeclaredConstructor(LevelChunk.class, TickingBlockEntity.class);
        constructor.setAccessible(true);
        return (TickingBlockEntity) constructor.newInstance(chunk, delegate);
    }

    private void bumpRevision(LevelChunk chunk) {
        chunk.bumpBlockEntityTickerRevision();
    }

    private BlockPos position(ChunkPos pos, int index) {
        return new BlockPos(pos.getMinBlockX() + index, 64, pos.getMinBlockZ());
    }

    private static TickingBlockEntity mockTicker(BlockPos position, String type) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(position);
        when(ticker.getType()).thenReturn(type);
        return ticker;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        // A Mockito mock does not run the level constructor, so the ticker bookkeeping the chunk index uses
        // has to be installed explicitly. Everything else the plan reads is stubbed below.
        setField(Level.class, level, "blockEntityTickerLock", new Object());
        setField(Level.class, level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "blockEntityTickerChunks", new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<LevelChunk>());
        setField(Level.class, level, "blockEntityTickerSequence", new java.util.concurrent.atomic.AtomicLong());
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("chunk-iteration-level").when(level).toString();
        doReturn(-64).when(level).getMinBuildHeight();
        doReturn(384).when(level).getHeight();
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        doReturn(biomeRegistry()).when(registryAccess).registryOrThrow(Registries.BIOME);
        doReturn(registryAccess).when(level).registryAccess();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(3).when(level).entitySectionRegionChunkSize();
        doReturn(new net.minecraft.world.level.border.WorldBorder()).when(level).getWorldBorder();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doAnswer(invocation -> this.tickableChunks.contains(invocation.getArgument(0))).when(level).shouldTickBlocksAt(anyLong());
        doAnswer(invocation -> this.loadedChunks.contains(invocation.getArgument(0))).when(level).areEntitiesLoaded(anyLong());
        doAnswer(invocation -> {
            this.registeredTickers.add(invocation.getArgument(0));
            return null;
        }).when(level).addBlockEntityTicker(any());
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doAnswer(invocation -> this.chunks.get(ChunkPos.asLong(invocation.getArgument(0), invocation.getArgument(1))))
                .when(chunkSource).getChunkNow(anyInt(), anyInt());
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private final List<TickingBlockEntity> registeredTickers = new ArrayList<>();

    private static void setField(Class<?> declaringClass, Object target, String name, Object value) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Registry<Biome> biomeRegistry() {
        return (Registry<Biome>) mock(Registry.class);
    }

    /**
     * Builds a real chunk whose sections carry real block-state palettes; only the biome palette is a
     * placeholder, because nothing here reads a biome.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static LevelChunk chunk(ServerLevel level, ChunkPos pos) {
        final LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class));
        }
        return new LevelChunk(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
    }

    /**
     * One ticker of the randomized workload: a recording delegate wrapped in the chunk's real rebindable
     * wrapper. The type is mutable because a rebind replaces the delegate of the wrapper, which changes the
     * type the plan sees for the same position.
     */
    private static final class WorkloadTicker {
        private final String name;
        private final BlockPos position;
        private final Consumer<String> recorder;
        private final TickingBlockEntity delegate;
        private TickingBlockEntity wrapper;
        private String type;
        private boolean removed;
        private long sequence;

        WorkloadTicker(String name, BlockPos position, String type, Consumer<String> recorder) {
            this.name = name;
            this.position = position;
            this.type = type;
            this.recorder = recorder;
            this.delegate = mock(TickingBlockEntity.class);
            when(this.delegate.getPos()).thenReturn(position);
            when(this.delegate.getType()).thenAnswer(invocation -> this.type);
            when(this.delegate.isRemoved()).thenAnswer(invocation -> this.removed);
            doAnswer(invocation -> {
                this.recorder.accept(this.name + "@" + (RegionThreadingHooks.isNativeRegionWorkerThread() ? "worker" : "server"));
                return null;
            }).when(this.delegate).tick();
        }

        String name() {
            return this.name;
        }

        BlockPos position() {
            return this.position;
        }

        String type() {
            return this.type;
        }

        long sequence() {
            return this.sequence;
        }

        boolean isRemoved() {
            return this.removed;
        }

        TickingBlockEntity delegate() {
            return this.delegate;
        }

        void register(TickingBlockEntity wrapper) {
            this.wrapper = wrapper;
            this.sequence = LevelChunk.blockEntityTickerSequence(wrapper);
        }

        void rebind(String newType) {
            this.type = newType;
        }

        void remove() {
            this.removed = true;
        }
    }
}
