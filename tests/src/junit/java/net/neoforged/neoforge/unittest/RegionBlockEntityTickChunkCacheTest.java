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

import java.lang.reflect.Method;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
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
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickChunkCache;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the per-chunk admission cache of the block-entity worker slice.
 * <p>
 * The cache decides, once per chunk and per change, what the plan re-evaluated for every ticker on every
 * tick: the allowlist of the compatibility declaration, the interior-chunk rule, the region of the chunk and
 * the exact binding capture of each ticker. The tests drive the real engine, the real plan and the real
 * worker batch against a {@link org.mockito.Mockito#CALLS_REAL_METHODS} level and real {@link LevelChunk}
 * instances, so the chunk that holds a snapshot, the ticker revision the patch bumps and the unload that
 * releases the snapshot are the production ones.
 * <p>
 * The central test compares the cached plan with the uncached evaluation over randomized chunk and ticker
 * states, including rebinds, ticker set changes, chunk reloads and entity-load flips. The uncached
 * evaluation is the behavior of the plan before the cache existed, so an equal outcome means the cache never
 * changes which tickers are admitted, in which order they are ticked and which of them fall back.
 */
@Timeout(60)
class RegionBlockEntityTickChunkCacheTest {
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
    private static final int EQUIVALENCE_ROUNDS = 120;

    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private final List<TickingBlockEntity> registeredTickers = new ArrayList<>();
    private final List<Long> loadedChunks = new ArrayList<>();
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
    void theCachedPlanMatchesTheUncachedEvaluationOverRandomizedStates() {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final RandomSource random = RandomSource.create(20240117L);
        final List<ChunkPos> chunkPool = List.of(INTERIOR_CHUNK, SECOND_INTERIOR_CHUNK, THIRD_INTERIOR_CHUNK, BOUNDARY_CHUNK);
        final Map<ChunkPos, LevelChunk> chunkByPos = new LinkedHashMap<>();
        final Map<ChunkPos, List<WorkloadTicker>> tickersByChunk = new LinkedHashMap<>();
        for (final ChunkPos pos : chunkPool) {
            chunkByPos.put(pos, this.loadChunk(level, pos));
            this.loadedChunks.add(pos.toLong());
            tickersByChunk.put(pos, new ArrayList<>());
        }
        for (final ChunkPos pos : chunkPool) {
            for (int index = 0; index < random.nextInt(4); index++) {
                tickersByChunk.get(pos).add(this.addTicker(tickersByChunk.get(pos), pos, random));
            }
        }

        for (int round = 0; round < EQUIVALENCE_ROUNDS; round++) {
            this.mutate(random, level, chunkByPos, tickersByChunk);
            final List<TickingBlockEntity> visited = new ArrayList<>();
            for (final List<WorkloadTicker> tickers : tickersByChunk.values()) {
                for (final WorkloadTicker ticker : tickers) {
                    visited.add(ticker.handle());
                }
            }
            final List<String> uncached = this.runAndObserve(level, visited, false);
            final List<String> cached = this.runAndObserve(level, visited, true);
            assertEquals(uncached, cached, "the cached plan diverged from the uncached evaluation in round " + round);
        }
    }

    @Test
    void theCachedPlanReusesTheAdmissionOfAnUnchangedTicker() {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, SECOND_INTERIOR_CHUNK);
        this.loadedChunks.add(SECOND_INTERIOR_CHUNK.toLong());
        final List<TickingBlockEntity> tickers = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            tickers.add(mockTicker(new BlockPos(SECOND_INTERIOR_CHUNK.getMinBlockX() + index, 64, SECOND_INTERIOR_CHUNK.getMinBlockZ()),
                    DECLARED_TYPE, tick -> {}));
        }
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);

        final List<RegionBlockEntityTickAdmission> first = this.collect(level, tickers);
        final List<RegionBlockEntityTickAdmission> second = this.collect(level, tickers);

        assertEquals(3, first.size());
        for (int index = 0; index < first.size(); index++) {
            assertSame(first.get(index), second.get(index),
                    "an unchanged ticker must reuse the admission its chunk cached instead of capturing it again");
        }
        assertNotNull(chunk.blockEntityTickAdmissionCache());
    }

    @Test
    void aRebindDuringTheTickDoesNotReuseTheCachedAdmission() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        final BlockPos position = new BlockPos(INTERIOR_CHUNK.getMinBlockX(), 64, INTERIOR_CHUNK.getMinBlockZ());
        final TickingBlockEntity wrapper = this.registerTicker(chunk, position);
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);

        final RegionBlockEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(wrapper), "the interior furnace ticker must be admitted");
        // An inline tick of the same tick rebounds the ticker: LevelChunk.updateBlockEntityTicker replaces
        // the delegate of the position's wrapper and bumps the chunk's ticker revision, which the snapshot
        // only observes on the next tick. The admission of the old delegate must not be reused.
        assertSame(wrapper, this.registerTicker(chunk, position), "a rebind reuses the wrapper of the position");
        assertTrue(plan.accept(wrapper), "the rebound ticker must still be admitted");

        final List<RegionBlockEntityTickAdmission> collected = plan.collectedTickers();
        assertEquals(2, collected.size());
        assertNotSame(collected.get(0), collected.get(1),
                "a ticker rebound during the tick must be captured again instead of reusing the admission of its old delegate");
    }

    @Test
    void theEntityLoadEligibilityIsReadOncePerChunkAndTick() {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        final List<TickingBlockEntity> tickers = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            tickers.add(mockTicker(new BlockPos(INTERIOR_CHUNK.getMinBlockX() + index, 64, INTERIOR_CHUNK.getMinBlockZ()), "minecraft:hopper", tick -> {}));
        }

        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);
        final RegionBlockEntityTickPlan plan = this.plan(level);
        for (final TickingBlockEntity ticker : tickers) {
            assertTrue(plan.accept(ticker), "an interior hopper must be admitted to the worker batch");
        }

        verify(level, times(1)).areEntitiesLoaded(INTERIOR_CHUNK.toLong());
        assertNotNull(chunk.blockEntityTickAdmissionCache(), "the plan must cache the admission of the chunk");
    }

    @Test
    void theSnapshotIsInvalidatedByTheTickerSetAndByTheLayout() throws ReflectiveOperationException {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
        this.loadedChunks.add(INTERIOR_CHUNK.toLong());
        final BlockPos position = new BlockPos(INTERIOR_CHUNK.getMinBlockX(), 64, INTERIOR_CHUNK.getMinBlockZ());
        final TickingBlockEntity ticker = mockTicker(position, "minecraft:furnace", tick -> {});
        final int liveBefore = RegionBlockEntityTickChunkCache.liveSnapshotCount();

        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);
        this.acceptAndExecute(level, ticker);
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
        this.addBlockEntityTicker(chunk, position);
        assertNotEquals(revision, chunk.blockEntityTickerRevision(), "adding a ticker must bump the revision");
        final long afterAdd = chunk.blockEntityTickerRevision();
        chunk.removeBlockEntity(position);
        assertNotEquals(afterAdd, chunk.blockEntityTickerRevision(), "removing a ticker must bump the revision");

        this.acceptAndExecute(level, ticker);
        assertNotSame(snapshot, chunk.blockEntityTickAdmissionCache(), "a changed ticker set must rebuild the snapshot");
        assertEquals(liveBefore + 1, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "a rebuilt snapshot must release the snapshot it replaces");

        chunk.clearAllBlockEntities();
        assertNull(chunk.blockEntityTickAdmissionCache(), "an unloaded chunk must release its snapshot");
        assertEquals(liveBefore, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "an unloaded chunk must not keep its snapshot live");
    }

    @Test
    void aRegionMergeKeepsTheCachedPlanOfTheRelocatedChunk() {
        // The dynamic layout merges neighbouring 16x16-chunk sections. The snapshot caches no region state, so
        // a merge must neither invalidate nor break the plan of a chunk whose region was absorbed.
        this.installEngine(RegionPolicy.DYNAMIC);
        final ServerLevel level = mockLevel();
        final ChunkPos chunkPos = INTERIOR_CHUNK;
        final LevelChunk chunk = this.loadChunk(level, chunkPos);
        this.loadedChunks.add(chunkPos.toLong());
        RegionThreadingHooks.addRegionSection(level, chunkPos.x, chunkPos.z);
        final TickingBlockEntity ticker = mockTicker(new BlockPos(chunkPos.getMinBlockX() + 1, 64, chunkPos.getMinBlockZ() + 1),
                "minecraft:hopper", tick -> {});
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);

        this.acceptAndExecute(level, ticker);
        final RegionBlockEntityTickChunkCache snapshot = chunk.blockEntityTickAdmissionCache();
        assertNotNull(snapshot);
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers(), "the interior hopper must tick on a worker");

        // A neighbouring section arrives: its region absorbs the region of the chunk.
        RegionThreadingHooks.addRegionSection(level, chunkPos.x + 15, chunkPos.z);

        this.acceptAndExecute(level, ticker);
        assertSame(snapshot, chunk.blockEntityTickAdmissionCache(),
                "a merge must not invalidate a snapshot that holds no region state");
        assertEquals(2L, this.engine.diagnostics().blockEntityTicksOnWorkers(),
                "the relocated chunk must still tick on a worker of its merged region");
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void chunkChurnKeepsOneSnapshotPerLoadedChunk() {
        this.installEngine(RegionPolicy.FIXED_3X3);
        final ServerLevel level = mockLevel();
        final int liveBefore = RegionBlockEntityTickChunkCache.liveSnapshotCount();

        for (int load = 0; load < 32; load++) {
            final LevelChunk chunk = this.loadChunk(level, INTERIOR_CHUNK);
            this.loadedChunks.add(INTERIOR_CHUNK.toLong());
            final TickingBlockEntity ticker = mockTicker(new BlockPos(INTERIOR_CHUNK.getMinBlockX(), 64, INTERIOR_CHUNK.getMinBlockZ()),
                    "minecraft:hopper", tick -> {});
            this.engine.setBlockEntityAdmissionCacheEnabledForTesting(true);
            this.acceptAndExecute(level, ticker);
            assertNotNull(chunk.blockEntityTickAdmissionCache(), "a loaded chunk with tickers must hold one snapshot");

            // This is the unload path of ServerLevel.unload(LevelChunk).
            chunk.clearAllBlockEntities();
            assertNull(chunk.blockEntityTickAdmissionCache(), "an unloaded chunk must hold no snapshot");
            this.loadedChunks.remove(INTERIOR_CHUNK.toLong());
        }

        assertEquals(liveBefore, RegionBlockEntityTickChunkCache.liveSnapshotCount(),
                "chunk churn must not grow the set of live snapshots");
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

    private void acceptAndExecute(ServerLevel level, TickingBlockEntity... tickers) {
        final RegionBlockEntityTickPlan plan = this.plan(level);
        for (final TickingBlockEntity ticker : tickers) {
            plan.accept(ticker);
        }
        plan.executeAndAwait();
    }

    /**
     * Runs one plan over {@code visited} and returns everything the run decided: one {@code accept} result per
     * visited ticker in visit order, followed by one line per tick the batch really executed, in execution
     * order, naming the ticker and whether it ran on a region worker or on the server thread.
     */
    private List<String> runAndObserve(ServerLevel level, List<TickingBlockEntity> visited, boolean cacheEnabled) {
        this.engine.setBlockEntityAdmissionCacheEnabledForTesting(cacheEnabled);
        this.ticks.clear();
        final RegionBlockEntityTickPlan plan = this.plan(level);
        final List<String> observed = new ArrayList<>();
        for (final TickingBlockEntity ticker : visited) {
            observed.add("accept " + name(ticker) + "=" + plan.accept(ticker));
        }
        for (final RegionBlockEntityTickAdmission admission : plan.collectedTickers()) {
            observed.add("plan " + name(admission.ticker()) + ":" + admission.type() + ":" + admission.decision() + "@" + admission.position().toShortString());
        }
        plan.executeAndAwait();
        observed.addAll(this.ticks);
        return observed;
    }

    private List<RegionBlockEntityTickAdmission> collect(ServerLevel level, List<TickingBlockEntity> tickers) {
        final RegionBlockEntityTickPlan plan = this.plan(level);
        for (final TickingBlockEntity ticker : tickers) {
            plan.accept(ticker);
        }
        final List<RegionBlockEntityTickAdmission> collected = List.copyOf(plan.collectedTickers());
        plan.executeAndAwait();
        return collected;
    }

    private void mutate(RandomSource random, ServerLevel level, Map<ChunkPos, LevelChunk> chunkByPos, Map<ChunkPos, List<WorkloadTicker>> tickersByChunk) {
        final List<ChunkPos> positions = new ArrayList<>(chunkByPos.keySet());
        final ChunkPos pos = positions.get(random.nextInt(positions.size()));
        final List<WorkloadTicker> tickers = tickersByChunk.get(pos);
        switch (random.nextInt(6)) {
            case 0 -> {
                if (tickers.size() < 4) {
                    tickers.add(this.addTicker(tickers, pos, random));
                }
            }
            case 1 -> {
                if (!tickers.isEmpty()) {
                    tickers.remove(random.nextInt(tickers.size()));
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
                    this.bumpTickerRevision(chunkByPos.get(pos), pos);
                }
            }
            case 3 -> {
                final Long key = pos.toLong();
                if (!this.loadedChunks.remove(key)) {
                    this.loadedChunks.add(key);
                }
            }
            case 4 -> this.bumpTickerRevision(chunkByPos.get(pos), pos);
            case 5 -> {
                // A chunk unloads and a new chunk instance of the same position loads: the snapshot of the
                // old instance must not leak into the new one.
                chunkByPos.get(pos).clearAllBlockEntities();
                final LevelChunk reloaded = this.loadChunk(level, pos);
                chunkByPos.put(pos, reloaded);
                this.loadedChunks.add(pos.toLong());
            }
            default -> throw new IllegalStateException();
        }
    }

    private WorkloadTicker addTicker(List<WorkloadTicker> tickers, ChunkPos pos, RandomSource random) {
        final BlockPos position = new BlockPos(pos.getMinBlockX() + tickers.size(), 64, pos.getMinBlockZ() + random.nextInt(16));
        final WorkloadTicker ticker = new WorkloadTicker("ticker-" + pos.x + "," + pos.z + "#" + tickers.size(), position,
                TYPES.get(random.nextInt(TYPES.size())), this.ticks::add);
        return ticker;
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
        this.chunks.put(pos.toLong(), chunk);
        return chunk;
    }

    /**
     * Bumps the ticker revision of {@code chunk} through the production hook, by updating the ticker of a
     * block entity at {@code position} like a block state change of the block entity does.
     */
    private void bumpTickerRevision(LevelChunk chunk, ChunkPos pos) {
        try {
            this.addBlockEntityTicker(chunk, new BlockPos(pos.getMinBlockX(), 64, pos.getMinBlockZ()));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("the chunk must expose its ticker update hook", exception);
        }
    }

    /**
     * Registers the furnace block entity at {@code position} in {@code chunk} and returns the ticker wrapper
     * the chunk registered for it. A second call for the same position rebinds that wrapper to a new
     * delegate, exactly like a block state change of the block entity does.
     */
    private TickingBlockEntity registerTicker(LevelChunk chunk, BlockPos position) throws ReflectiveOperationException {
        this.addBlockEntityTicker(chunk, position);
        return this.registeredTickers.get(this.registeredTickers.size() - 1);
    }

    private void addBlockEntityTicker(LevelChunk chunk, BlockPos position) throws ReflectiveOperationException {
        final BlockEntity blockEntity = new FurnaceBlockEntity(position, Blocks.FURNACE.defaultBlockState());
        blockEntity.setLevel(chunk.getLevel());
        final Method update = LevelChunk.class.getDeclaredMethod("updateBlockEntityTicker", BlockEntity.class);
        update.setAccessible(true);
        update.invoke(chunk, blockEntity);
    }

    private String name(TickingBlockEntity ticker) {
        return ticker.toString();
    }

    private static TickingBlockEntity mockTicker(BlockPos position, String type, Consumer<TickingBlockEntity> tick) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(position);
        when(ticker.getType()).thenReturn(type);
        when(ticker.toString()).thenReturn("ticker-" + position.toShortString() + ":" + type);
        doAnswer(invocation -> {
            tick.accept(ticker);
            return null;
        }).when(ticker).tick();
        return ticker;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("admission-cache-level").when(level).toString();
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
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
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
     * One mock ticker of the randomized workload. The type is mutable because a rebind replaces the delegate
     * of the wrapper, which changes the type the plan sees for the same ticker.
     */
    private static final class WorkloadTicker {
        private final BlockPos position;
        private final Consumer<String> recorder;
        private final TickingBlockEntity handle;
        private String type;

        WorkloadTicker(String name, BlockPos position, String type, Consumer<String> recorder) {
            this.position = position;
            this.type = type;
            this.recorder = recorder;
            this.handle = mock(TickingBlockEntity.class);
            when(this.handle.getPos()).thenReturn(position);
            when(this.handle.getType()).thenAnswer(invocation -> this.type);
            when(this.handle.toString()).thenReturn(name);
            doAnswer(invocation -> {
                this.recorder.accept(name + "@" + (RegionThreadingHooks.isNativeRegionWorkerThread() ? "worker" : "server"));
                return null;
            }).when(this.handle).tick();
        }

        TickingBlockEntity handle() {
            return this.handle;
        }

        String type() {
            return this.type;
        }

        void rebind(String newType) {
            this.type = newType;
        }
    }
}
