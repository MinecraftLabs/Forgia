/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Container;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the deferred cross-region block write and the read-after-write contract it carries.
 * <p>
 * A region worker may not mutate a chunk of another region. Instead of dropping the write, {@code Level.setBlock}
 * records it and the region that owns the position runs the vanilla call on its own worker at the barrier of the
 * same tick, so the block-entity lifecycle, the heightmaps, the light recheck and the neighbour propagation are
 * the target region's own. These tests drive that path through the real chunk tick slice, real {@link LevelChunk}s
 * with real palettes and the real vanilla write path; only the level, its chunk source, its light engine and the
 * chunk tick body are mocks.
 * <p>
 * The tests also pin what the deferral cannot do: a block-entity mutation that carries data keeps the no-replay
 * refusal, a write past the capture bound is refused at the call site instead of being applied a tick later, and a
 * callback that reads the position back still observes the state before the write, which is counted.
 */
@Timeout(120)
class RegionBorderBlockWriteTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final RegionPolicy FIXED = RegionPolicy.FIXED_3X3;
    /** Middle chunk of region (0,0): the region whose worker writes across the border. */
    private static final ChunkPos SOURCE_CHUNK = new ChunkPos(1, 1);
    /** Middle chunk of region (1,0): the region that owns the written position and applies the write. */
    private static final ChunkPos TARGET_CHUNK = new ChunkPos(4, 1);
    private static final int Y = 64;
    /** A position in the middle of the target chunk, so no neighbour or shape update leaves the target region. */
    private static final BlockPos TARGET_POS = new BlockPos(TARGET_CHUNK.getMinBlockX() + 8, Y, TARGET_CHUNK.getMinBlockZ() + 8);
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;

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
    void foreignBlockWriteIsAppliedOnceByTheTargetRegionInTheSameTick() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final List<String> applyingThreads = new CopyOnWriteArrayList<>();
        final List<Boolean> ownedTheTarget = new CopyOnWriteArrayList<>();
        final List<Boolean> ownedTheSource = new CopyOnWriteArrayList<>();
        final AtomicBoolean sourceBodyDone = new AtomicBoolean();
        final AtomicBoolean accepted = new AtomicBoolean();
        this.recordWrites(level, applyingThreads, ownedTheTarget, ownedTheSource, sourceBodyDone);
        doAnswer(invocation -> {
            assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread(), "the write has to come from a region worker");
            accepted.set(level.setBlock(TARGET_POS, Blocks.STONE.defaultBlockState(), 3));
            sourceBodyDone.set(true);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(accepted.get(), "a deferred write reports success to its caller");
        assertEquals(1, applyingThreads.size(), "the target region applies the write exactly once: " + applyingThreads);
        assertTrue(applyingThreads.get(0).startsWith("NeoForge Region Worker"),
                "the write runs on a region worker: " + applyingThreads.get(0));
        assertEquals(List.of(Boolean.TRUE), ownedTheTarget,
                "the applying thread holds the lease of the region that owns the written position");
        assertEquals(List.of(Boolean.FALSE), ownedTheSource,
                "the applying thread does not hold the lease of the writing region, so the write is the target's own");
        assertTrue(chunks.get(TARGET_CHUNK.toLong()).getBlockState(TARGET_POS).is(Blocks.STONE),
                "the final state is the state the inline write reaches");
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, counters.blockWritesRecorded(), "the write is recorded once: " + counters);
        assertEquals(1L, counters.blockWritesApplied(), "the write is applied once: " + counters);
        assertEquals(0L, counters.blockWritesOverflowed(), "the capture bound was not reached: " + counters);
        assertEquals(0L, counters.blockWritesDropped(), "the target chunk stayed loaded: " + counters);
        assertEquals(0L, counters.readAfterBlockWrite(), "the body did not read the position back: " + counters);
        assertEquals(1L, this.engine.diagnostics().chunkTicksOnWorkers(), "the source chunk ticked on its worker");
    }

    /**
     * The heightmap of the write is the target region's own: the deferred write reaches the real chunk of the
     * target region, so the chunk's heightmap is updated by the vanilla call and ends up at the value the same
     * write produces inline.
     */
    @Test
    void theTargetRegionRunsTheWholeVanillaWrite() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        doAnswer(invocation -> {
            level.setBlock(TARGET_POS, Blocks.STONE.defaultBlockState(), 3);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        final LevelChunk target = chunks.get(TARGET_CHUNK.toLong());
        assertTrue(target.getBlockState(TARGET_POS).is(Blocks.STONE));
        final LevelChunk reference = chunks.get(SOURCE_CHUNK.toLong());
        final BlockPos referencePos = new BlockPos(SOURCE_CHUNK.getMinBlockX() + 8, Y, SOURCE_CHUNK.getMinBlockZ() + 8);
        assertTrue(level.setBlock(referencePos, Blocks.STONE.defaultBlockState(), 3),
                "the reference write runs inline on the server thread");
        // The four heightmaps a block write updates in LevelChunk.setBlockState.
        for (final Heightmap.Types type : List.of(Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
            assertEquals(reference.getHeight(type, referencePos.getX() & 15, referencePos.getZ() & 15),
                    target.getHeight(type, TARGET_POS.getX() & 15, TARGET_POS.getZ() & 15),
                    "the " + type + " heightmap of the target chunk has to equal the inline run");
        }
    }

    @Test
    void twoWritesToTheSamePositionKeepTheRecordedOrderAndTheLastOneWins() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final List<String> transitions = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            final BlockState oldState = invocation.getArgument(1);
            final BlockState newState = invocation.getArgument(2);
            transitions.add(oldState.getBlock().getName().getString() + "->" + newState.getBlock().getName().getString());
            return null;
        }).when(level).onBlockStateChange(any(BlockPos.class), any(BlockState.class), any(BlockState.class));
        doAnswer(invocation -> {
            level.setBlock(TARGET_POS, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(TARGET_POS, Blocks.STONE.defaultBlockState(), 3);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(List.of("Air->Dirt", "Dirt->Stone"), transitions,
                "the target region applies the recorded writes in recording order");
        assertTrue(chunks.get(TARGET_CHUNK.toLong()).getBlockState(TARGET_POS).is(Blocks.STONE),
                "the last recorded write wins, exactly like the inline run");
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertEquals(2L, counters.blockWritesRecorded(), counters.toString());
        assertEquals(2L, counters.blockWritesApplied(), counters.toString());
    }

    @Test
    void blockEntityMutationOutsideTheLeaseKeepsTheNoReplayRefusal() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final long refusals = RegionWorkerChunkUnavailableException.foreignWriteRefusalCount();
        final AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            // A block entity with data cannot be expressed as (position, state, flags): it is refused, never
            // replayed, because the guard fires before any mutation of the foreign chunk.
            level.setBlockEntity(new ChestBlockEntity(TARGET_POS, Blocks.CHEST.defaultBlockState()));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, attempts.get(), "the body runs exactly once, so nothing is replayed");
        assertEquals(refusals + 1, RegionWorkerChunkUnavailableException.foreignWriteRefusalCount(),
                "the refused block-entity mutation keeps its own count");
        assertNull(chunks.get(TARGET_CHUNK.toLong()).getBlockEntity(TARGET_POS),
                "the refused mutation did not reach the foreign chunk");
        assertEquals(0L, this.engine.diagnostics().borderUpdates().blockWritesRecorded(),
                "a block-entity mutation is not a deferred block write");
    }

    @Test
    void writePastTheCaptureBoundIsRefusedAtTheCallSiteAndCounted() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        this.engine.setBorderUpdateCapacityForTesting(1);
        final long refusals = RegionWorkerChunkUnavailableException.foreignWriteRefusalCount();
        final AtomicBoolean firstAccepted = new AtomicBoolean();
        final AtomicBoolean secondAccepted = new AtomicBoolean(true);
        this.recordWrites(level, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new AtomicBoolean());
        doAnswer(invocation -> {
            firstAccepted.set(level.setBlock(TARGET_POS, Blocks.STONE.defaultBlockState(), 3));
            secondAccepted.set(level.setBlock(TARGET_POS.above(), Blocks.DIRT.defaultBlockState(), 3));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(firstAccepted.get(), "the write inside the bound is recorded and accepted");
        assertFalse(secondAccepted.get(), "a write past the bound is refused at the call site, not delayed");
        assertEquals(refusals + 1, RegionWorkerChunkUnavailableException.foreignWriteRefusalCount(),
                "the refused write is counted by the existing no-replay refusal");
        assertTrue(chunks.get(TARGET_CHUNK.toLong()).getBlockState(TARGET_POS).is(Blocks.STONE),
                "the write inside the bound still converges");
        assertTrue(chunks.get(TARGET_CHUNK.toLong()).getBlockState(TARGET_POS.above()).isAir(),
                "the refused write did not reach the foreign chunk");
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, counters.blockWritesRecorded(), counters.toString());
        assertEquals(1L, counters.blockWritesApplied(), counters.toString());
        assertEquals(1L, counters.blockWritesOverflowed(), counters.toString());
        assertEquals(1L, this.engine.diagnostics().chunkTicksOnWorkers(), "the level keeps running: the chunk ticked");
    }

    /**
     * The read-after-write contract: the write is applied by another region at the barrier, so a callback that
     * reads the position back in the same lease observes the state before the write. The test asserts that
     * documented behaviour and its count instead of racing the target region.
     */
    @Test
    void readAfterDeferredWriteObservesTheStateBeforeTheWriteAndIsCounted() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final AtomicReference<BlockState> readBack = new AtomicReference<>();
        this.recordWrites(level, new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(),
                new AtomicBoolean());
        doAnswer(invocation -> {
            assertTrue(level.setBlock(TARGET_POS, Blocks.STONE.defaultBlockState(), 3));
            readBack.set(level.getBlockState(TARGET_POS));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertNotNull(readBack.get(), "the body read the written position back");
        assertTrue(readBack.get().isAir(), "the read observes the state before the deferred write: " + readBack.get());
        assertTrue(chunks.get(TARGET_CHUNK.toLong()).getBlockState(TARGET_POS).is(Blocks.STONE),
                "the write itself converges in the same tick");
        assertEquals(1L, this.engine.diagnostics().borderUpdates().readAfterBlockWrite(),
                "the approximation is counted: " + this.engine.diagnostics().borderUpdates());
    }

    /**
     * The block entity of a deferred write is created by the target region's own vanilla call: the chest exists
     * with its container after the barrier, and the writing worker never saw it.
     */
    @Test
    void deferredWriteCreatesTheBlockEntityInTheTargetRegion() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final LevelChunk target = chunks.get(TARGET_CHUNK.toLong());
        final AtomicReference<BlockEntity> visibleInsideTheBody = new AtomicReference<>();
        doAnswer(invocation -> {
            assertTrue(level.setBlock(TARGET_POS, Blocks.CHEST.defaultBlockState(), 3));
            visibleInsideTheBody.set(target.getBlockEntity(TARGET_POS));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertNull(visibleInsideTheBody.get(), "the writing worker does not create the block entity of another region");
        final BlockEntity created = target.getBlockEntity(TARGET_POS);
        assertNotNull(created, "the target region created the block entity with its own vanilla write");
        assertTrue(created instanceof ChestBlockEntity, "the block entity is the one of the placed block: " + created);
        assertTrue(created instanceof Container container && container.isEmpty(),
                "the created block entity carries the data of a fresh chest");
        assertEquals(1L, this.engine.diagnostics().borderUpdates().blockWritesApplied());
    }

    /**
     * Removing a block entity outside the lease goes through the same deferred write: after the barrier the
     * block entity is gone and the ticker it registered is marked removed, exactly like the inline run.
     */
    @Test
    void deferredWriteRemovesTheBlockEntityAndItsTickerInTheTargetRegion() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final LevelChunk target = chunks.get(TARGET_CHUNK.toLong());
        doAnswer(invocation -> {
            // A furnace registers a ticker, so the removal has to unregister it.
            assertTrue(level.setBlock(TARGET_POS, Blocks.FURNACE.defaultBlockState(), 3));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan createPlan = this.beginPlan(level);
        createPlan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        createPlan.executeAndAwait(PARKING_BARRIER);

        final BlockEntity furnace = target.getBlockEntity(TARGET_POS);
        assertNotNull(furnace, "the target region created the furnace block entity");
        assertTrue(this.tickersAt(level, TARGET_POS).stream().anyMatch(ticker -> !ticker.isRemoved()),
                "the furnace registered a block-entity ticker: " + this.tickersAt(level, TARGET_POS));

        doAnswer(invocation -> {
            assertTrue(level.setBlock(TARGET_POS, Blocks.AIR.defaultBlockState(), 3));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());
        final RegionChunkTickPlan removePlan = this.beginPlan(level);
        removePlan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        removePlan.executeAndAwait(PARKING_BARRIER);

        assertTrue(target.getBlockState(TARGET_POS).isAir(), "the target region removed the block");
        assertNull(target.getBlockEntity(TARGET_POS), "the block entity of the removed block is gone");
        assertTrue(this.tickersAt(level, TARGET_POS).stream().allMatch(TickingBlockEntity::isRemoved),
                "the ticker of the removed block entity is unregistered: " + this.tickersAt(level, TARGET_POS));
    }

    /** A write inside the region lease keeps the inline vanilla path and records nothing. */
    @Test
    void writeInsideTheLeaseIsInlineAndRecordsNothing() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = this.chunks(level);
        this.installEngine(level);
        final BlockPos ownPos = new BlockPos(SOURCE_CHUNK.getMinBlockX() + 8, Y, SOURCE_CHUNK.getMinBlockZ() + 8);
        final AtomicBoolean accepted = new AtomicBoolean();
        doAnswer(invocation -> {
            accepted.set(level.setBlock(ownPos, Blocks.STONE.defaultBlockState(), 3));
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunks.get(SOURCE_CHUNK.toLong()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(accepted.get(), "the inline write succeeds");
        assertTrue(chunks.get(SOURCE_CHUNK.toLong()).getBlockState(ownPos).is(Blocks.STONE), "the inline write is visible");
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertEquals(0L, counters.blockWritesRecorded(), "a write inside the lease needs no record: " + counters);
        assertEquals(0L, counters.passes(), "the fixpoint has nothing to do: " + counters);
    }

    // ---------------------------------------------------------------------------------------------------------
    // the level, its chunk source and the engine
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Records the thread and the region lease of every block state change of the level.
     * <p>
     * The vanilla write calls {@code Level.onBlockStateChange} at the end of {@code markAndNotifyBlock}, so the
     * recorded thread is the thread that ran the write and the two lease reads name the region it held.
     */
    private void recordWrites(ServerLevel level, List<String> threads, List<Boolean> ownedTheTarget, List<Boolean> ownedTheSource,
            AtomicBoolean sourceBodyDone) {
        doAnswer(invocation -> {
            assertTrue(sourceBodyDone.get() || threads.isEmpty(),
                    "the write is applied after the recording body returned, never inline by it");
            threads.add(Thread.currentThread().getName());
            ownedTheTarget.add(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, TARGET_POS));
            ownedTheSource.add(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, SOURCE_CHUNK.getWorldPosition()));
            return null;
        }).when(level).onBlockStateChange(any(BlockPos.class), any(BlockState.class), any(BlockState.class));
    }

    /** {@return the level's block-entity tickers whose position is {@code pos}} */
    @SuppressWarnings("unchecked")
    private List<TickingBlockEntity> tickersAt(ServerLevel level, BlockPos pos) {
        try {
            final Field field = Level.class.getDeclaredField("blockEntityTickers");
            field.setAccessible(true);
            final List<TickingBlockEntity> tickers = (List<TickingBlockEntity>) field.get(level);
            return tickers.stream().filter(ticker -> ticker.getPos().equals(pos)).toList();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RegionChunkTickPlan beginPlan(ServerLevel level) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, 1, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        return plan;
    }

    private Map<Long, LevelChunk> chunks(ServerLevel level) {
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        for (int chunkX = 0; chunkX <= 5; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 2; chunkZ++) {
                final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                chunks.put(pos.toLong(), realChunk(level, pos));
            }
        }
        serveChunks(level, chunks);
        return chunks;
    }

    private void installEngine(ServerLevel level) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, true, FIXED);
        created.setBorderUpdatesEnabledForTesting(true);
        RegionThreadingManager.install(created);
        this.engine = created;
        for (int chunkX = 0; chunkX <= 5; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 2; chunkZ++) {
                created.addRegionSection(level, chunkX, chunkZ);
            }
        }
    }

    /**
     * {@return a level that runs the real vanilla write path and serves its chunks from the test}
     * <p>
     * The mock runs the real methods, so the write, the block update publication and the neighbour updates are the
     * production ones. The fields the vanilla constructor would have filled are installed explicitly, because a
     * mock never runs it.
     */
    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(20L).when(level).getGameTime();
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        doReturn(3).when(level).entitySectionRegionChunkSize();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
        doReturn(MIN_BUILD_HEIGHT).when(level).getMinBuildHeight();
        doReturn(LEVEL_HEIGHT).when(level).getHeight();
        doReturn(1000000).when(level).maxChainedNeighborUpdates();
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(3);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        @SuppressWarnings("unchecked")
        final Registry<Biome> biomes = mock(Registry.class);
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
        doReturn(registryAccess).when(level).registryAccess();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        setLevelField(level, "neighborUpdater", new RegionAwareNeighborUpdater(level, 512));
        setLevelField(level, "navigatingMobs", new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<PathNavigation>());
        setLevelField(level, "pathTypesByPosCache", new PathTypeCache());
        setLevelField(level, "capListenerHolder", new CapabilityListenerHolder());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        doReturn(true).when(level).areEntitiesLoaded(anyLong());
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(false);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        final ServerChunkCache source = mock(ServerChunkCache.class);
        doReturn(true).when(source).hasChunk(anyInt(), anyInt());
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(source).managedBlockOnMainThread(any());
        final ThreadedLevelLightEngine lightEngine = mock(ThreadedLevelLightEngine.class);
        doNothing().when(lightEngine).updateSectionStatus(any(BlockPos.class), anyBoolean());
        doNothing().when(lightEngine).checkBlock(any(BlockPos.class));
        doReturn(lightEngine).when(source).getLightEngine();
        doReturn(source).when(level).getChunkSource();
        setLevelField(level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "freshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "pendingFreshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "blockEntityTickerLock", new Object());
        setLevelField(level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "blockEntityTickerChunks",
                new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<LevelChunk>());
        setLevelField(level, "blockEntityTickerSequence", new java.util.concurrent.atomic.AtomicLong());
        return level;
    }

    /**
     * Makes the chunk source answer the block reads and writes of the tick bodies from {@code chunks}.
     * <p>
     * The {@code getChunk} answer records the version of the chunk it hands out, exactly like the worker chunk
     * lookup of the production chunk source does, so the test exercises the recording seam of a foreign read.
     */
    private static void serveChunks(ServerLevel level, Map<Long, LevelChunk> chunks) {
        final ServerChunkCache source = level.getChunkSource();
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            final LevelChunk chunk = chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                    key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
            RegionThreadingHooks.recordForeignChunkRead(level, chunkX, chunkZ, chunk);
            return chunk;
        }).when(source).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
        }).when(source).getChunkNow(anyInt(), anyInt());
    }

    /** {@return a real chunk with real block palettes for every section of the level} */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static LevelChunk realChunk(ServerLevel level, ChunkPos pos) {
        final int sectionCount = level.getSectionsCount();
        final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (int index = 0; index < sectionCount; index++) {
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                            PalettedContainer.Strategy.SECTION_STATES),
                    (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class));
        }
        final LevelChunk chunk = new LevelChunk(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L,
                sections, null, null);
        chunk.setFullStatus(() -> net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
        chunk.setLoaded(true);
        return chunk;
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        Class<?> type = ServerLevel.class;
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(level, value);
                return;
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
        throw new IllegalStateException("No field " + name + " on the level class hierarchy");
    }
}
