/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the cross-region border update capture and its same-tick fixpoint against the real engine.
 * <p>
 * The worker body is a real chunk tick group: it calls {@code Level.neighborChanged} for a position in the
 * neighbouring region. The target state is a mock whose {@code handleNeighborChanged} records the thread it runs
 * on, so the test can prove that the update is not applied by the source worker, is applied exactly once by a
 * propagation-only pass of the target region, and is never applied a second time by the target's next tick.
 */
@Timeout(60)
class RegionBorderUpdateTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    /** Source chunk: the middle of region (0,0) of the size-3 fixed layout. */
    private static final ChunkPos SOURCE_CHUNK = new ChunkPos(1, 1);
    /** Target chunk: the first chunk of region (1,0), one block across the border. */
    private static final ChunkPos TARGET_CHUNK = new ChunkPos(3, 1);
    /** An interior chunk of the target region, so the next-tick plan can lease the region and deliver its mailbox. */
    private static final ChunkPos TARGET_TICK_CHUNK = new ChunkPos(4, 1);
    private static final BlockPos SOURCE_POS = new BlockPos(SOURCE_CHUNK.getMinBlockX() + 1, 64, SOURCE_CHUNK.getMinBlockZ() + 1);
    private static final BlockPos TARGET_POS = new BlockPos(TARGET_CHUNK.getMinBlockX() + 1, 64, TARGET_CHUNK.getMinBlockZ() + 1);
    /** The last block of the source region and the first block of the target region, for a comparator update. */
    private static final BlockPos SOURCE_EDGE_POS = new BlockPos(TARGET_CHUNK.getMinBlockX() - 1, 64, TARGET_CHUNK.getMinBlockZ() + 1);
    private static final BlockPos TARGET_EDGE_POS = new BlockPos(TARGET_CHUNK.getMinBlockX(), 64, TARGET_CHUNK.getMinBlockZ() + 1);

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
    void borderUpdateIsCapturedAndAppliedOnceByTheTargetRegionInTheSameTick() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        final AtomicInteger targetUpdates = new AtomicInteger();
        final List<String> targetThreads = new CopyOnWriteArrayList<>();
        final AtomicReference<String> sourceThread = new AtomicReference<>();
        final AtomicInteger chunkTickCalls = new AtomicInteger();
        final AtomicInteger batch = new AtomicInteger();
        final AtomicInteger sourceBatch = new AtomicInteger(-1);
        final List<Integer> targetBatches = new CopyOnWriteArrayList<>();
        final BlockState targetState = mockTargetState(targetUpdates, targetThreads, targetBatches, batch);
        doReturn(targetState).when(level).getBlockState(TARGET_POS);
        final Block block = mock(Block.class);
        doAnswer(invocation -> {
            final LevelChunk chunk = invocation.getArgument(0);
            if (!SOURCE_CHUNK.equals(chunk.getPos())) {
                return null;
            }
            chunkTickCalls.incrementAndGet();
            sourceThread.set(Thread.currentThread().getName());
            sourceBatch.set(batch.get());
            // The production call a neighbor cascade makes for a block in another region.
            level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(this.sourceChunk());
        batch.set(1);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, chunkTickCalls.get(), "the source chunk ticks exactly once");
        assertTrue(sourceThread.get().startsWith("NeoForge Region Worker"), "the source body runs on a worker: " + sourceThread.get());
        assertEquals(1, targetUpdates.get(), "the captured update has to be applied exactly once");
        assertTrue(targetThreads.get(0).startsWith("NeoForge Region Worker"), "the target update runs on the target worker: " + targetThreads.get(0));
        assertFalse(targetThreads.get(0).equals(sourceThread.get()), "the target update must not run on the source worker");
        assertEquals(List.of(sourceBatch.get()), targetBatches, "the target fires in the same tick/batch as the input change");
        final var counters = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, counters.recorded());
        assertEquals(1L, counters.applied());
        assertEquals(0L, counters.delayed());
        assertEquals(0L, counters.dropped());
        assertEquals(1L, counters.passes());
        assertEquals(1L, counters.regionsRerun());
        assertEquals(1L, counters.worstPasses());
    }

    @Test
    void repeatedBorderUpdatesMatchInlineDeliveryAndOrder() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        final List<Block> delivered = new CopyOnWriteArrayList<>();
        final BlockState targetState = mock(BlockState.class);
        doAnswer(invocation -> {
            delivered.add(invocation.getArgument(2));
            return null;
        }).when(targetState).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        doReturn(targetState).when(level).getBlockState(TARGET_POS);
        final Block a = mock(Block.class);
        final Block b = mock(Block.class);
        final List<Block> calls = List.of(a, a, b, a);
        for (final Block block : calls) {
            level.neighborChanged(TARGET_POS, block, SOURCE_POS);
        }
        assertEquals(calls, delivered, "inline delivers identical calls and A-B-A in order");
        final List<Block> inline = List.copyOf(delivered);
        delivered.clear();
        doAnswer(invocation -> {
            for (final Block block : calls) {
                level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            }
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(this.sourceChunk());
        plan.executeAndAwait(PARKING_BARRIER);
        assertEquals(inline, delivered, "border delivery must preserve every inline invocation");
        assertEquals(4L, this.engine.diagnostics().borderUpdates().recorded());
        assertEquals(4L, this.engine.diagnostics().borderUpdates().applied());
    }

    @Test
    void boundDefersTheRemainingUpdateToTheTargetsNextTickAndCountsIt() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        this.engine.setBorderUpdateBudgetForTesting(0);
        final AtomicInteger targetUpdates = new AtomicInteger();
        final List<String> targetThreads = new CopyOnWriteArrayList<>();
        doReturn(mockTargetState(targetUpdates, targetThreads)).when(level).getBlockState(TARGET_POS);
        final Block block = mock(Block.class);
        doAnswer(invocation -> {
            final LevelChunk chunk = invocation.getArgument(0);
            if (SOURCE_CHUNK.equals(chunk.getPos())) {
                level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            }
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan sourcePlan = this.beginPlan(level);
        sourcePlan.accept(this.sourceChunk());
        sourcePlan.executeAndAwait(PARKING_BARRIER);

        assertEquals(0, targetUpdates.get(), "the bound reached before the target region ran");
        final var first = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, first.recorded());
        assertEquals(0L, first.applied());
        assertEquals(1L, first.delayed(), "the remaining update is handed to the target's next tick");
        assertEquals(0L, first.passes());
        assertEquals(1L, this.engine.diagnostics().mailboxAcceptedMessages());

        // The target region's own next group delivers the mailbox message and the fixpoint applies it.
        this.engine.setBorderUpdateBudgetForTesting(-1);
        final RegionChunkTickPlan targetPlan = this.beginPlan(level);
        targetPlan.accept(this.targetChunk());
        targetPlan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, targetUpdates.get(), "the delayed update converges on the target's next tick exactly once: " + this.engine.diagnostics().borderUpdates() + ", delivered=" + this.engine.diagnostics().mailboxDeliveredMessages() + ", accepted=" + this.engine.diagnostics().mailboxAcceptedMessages());
        final var second = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, second.applied());
        assertEquals(1L, second.delayed());
        assertEquals(1L, this.engine.diagnostics().mailboxDeliveredMessages());
    }

    @Test
    void oscillatingPairHitsTheBoundAndDoesNotSpin() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        this.engine.setBorderUpdateBudgetForTesting(1);
        final AtomicInteger targetUpdates = new AtomicInteger();
        final AtomicInteger sourceUpdates = new AtomicInteger();
        final Block block = mock(Block.class);
        final BlockState targetState = mock(BlockState.class);
        final BlockState sourceState = mock(BlockState.class);
        doReturn(targetState).when(level).getBlockState(TARGET_POS);
        doReturn(sourceState).when(level).getBlockState(SOURCE_POS);
        doAnswer(invocation -> {
            // The mock target blocks and writes back to the source region on every update, i.e. the two
            // regions keep each other changing. The fixpoint must stop at its bound instead of spinning.
            if (targetUpdates.incrementAndGet() == 1) {
                level.neighborChanged(SOURCE_POS, block, TARGET_POS);
            }
            return null;
        }).when(targetState).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        doAnswer(invocation -> {
            sourceUpdates.incrementAndGet();
            level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            return null;
        }).when(sourceState).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        doAnswer(invocation -> {
            final LevelChunk chunk = invocation.getArgument(0);
            if (SOURCE_CHUNK.equals(chunk.getPos())) {
                level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            }
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(this.sourceChunk());
        plan.executeAndAwait(PARKING_BARRIER);

        final var counters = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, counters.passes(), "one pass is the budget, so the second update cannot run this tick");
        assertEquals(1L, counters.applied());
        assertTrue(counters.delayed() >= 1L, "the update the bound refused is delayed, not lost: " + counters);
        assertEquals(1, targetUpdates.get(), "the target applies its update once and stops");
        assertEquals(0, sourceUpdates.get(), "the source never re-runs in the same tick after the bound");
    }

    @Test
    void comparatorUpdateTargetingAnotherRegionIsAppliedOnTheTargetWorker() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        final AtomicInteger neighborChanges = new AtomicInteger();
        final BlockState targetState = mock(BlockState.class);
        doAnswer(invocation -> {
            neighborChanges.incrementAndGet();
            return null;
        }).when(targetState).onNeighborChange(any(), any(), any());
        doReturn(false).when(targetState).isRedstoneConductor(any(), any());
        final BlockState air = mock(BlockState.class);
        doReturn(false).when(air).isRedstoneConductor(any(), any());
        doAnswer(invocation -> TARGET_EDGE_POS.equals(invocation.getArgument(0)) ? targetState : air)
                .when(level).getBlockState(any(BlockPos.class));
        final Block block = mock(Block.class);
        doAnswer(invocation -> {
            level.updateNeighbourForOutputSignal(SOURCE_EDGE_POS, block);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(this.sourceChunk());
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, neighborChanges.get(), "the comparator update has to reach the target region once");
        final var counters = this.engine.diagnostics().borderUpdates();
        assertEquals(1L, counters.recorded());
        assertEquals(1L, counters.applied());
        assertEquals(0L, counters.delayed());
    }

    @Test
    void propagationPassDoesNotRunAnotherChunkTick() {
        final ServerLevel level = this.mockLevel();
        this.installEngine(level);
        final AtomicInteger chunkTickCalls = new AtomicInteger();
        final AtomicInteger targetUpdates = new AtomicInteger();
        doReturn(mockTargetState(targetUpdates, new CopyOnWriteArrayList<>())).when(level).getBlockState(TARGET_POS);
        final Block block = mock(Block.class);
        doAnswer(invocation -> {
            final LevelChunk chunk = invocation.getArgument(0);
            if (!SOURCE_CHUNK.equals(chunk.getPos())) {
                return null;
            }
            chunkTickCalls.incrementAndGet();
            level.neighborChanged(TARGET_POS, block, SOURCE_POS);
            return null;
        }).when(level).tickChunk(any(LevelChunk.class), anyInt());

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(this.sourceChunk());
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, chunkTickCalls.get(), "the propagation pass must not re-run the chunk tick slice");
        assertEquals(1, targetUpdates.get());
        assertEquals(1L, this.engine.diagnostics().chunkTicksOnWorkers(), "only the original chunk tick ran on a worker");
    }

    private void installEngine(ServerLevel level) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, true,
                RegionLayout.of(RegionPolicy.FIXED_3X3));
        created.setBorderUpdatesEnabledForTesting(true);
        RegionThreadingManager.install(created);
        this.engine = created;
        this.engine.addRegionSection(level, SOURCE_CHUNK.x, SOURCE_CHUNK.z);
        this.engine.addRegionSection(level, TARGET_CHUNK.x, TARGET_CHUNK.z);
        this.engine.addRegionSection(level, TARGET_TICK_CHUNK.x, TARGET_TICK_CHUNK.z);
    }

    private RegionChunkTickPlan beginPlan(ServerLevel level) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, 1, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        return plan;
    }

    private LevelChunk sourceChunk() {
        final LevelChunk chunk = mock(LevelChunk.class);
        doReturn(SOURCE_CHUNK).when(chunk).getPos();
        return chunk;
    }

    private LevelChunk targetChunk() {
        final LevelChunk chunk = mock(LevelChunk.class);
        doReturn(TARGET_TICK_CHUNK).when(chunk).getPos();
        return chunk;
    }

    private static BlockState mockTargetState(AtomicInteger updates, List<String> threads) {
        return mockTargetState(updates, threads, new CopyOnWriteArrayList<>(), new AtomicInteger());
    }

    private static BlockState mockTargetState(AtomicInteger updates, List<String> threads, List<Integer> batches,
            AtomicInteger batch) {
        final BlockState state = mock(BlockState.class);
        doAnswer(invocation -> {
            updates.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            batches.add(batch.get());
            return null;
        }).when(state).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        return state;
    }

    /**
     * {@return a level that serves the two chunks and runs the real neighbor updater}
     * <p>
     * The mock runs the real methods so {@code neighborChanged} reaches the region collector. The fields the
     * vanilla constructor would fill are installed explicitly. The chunk source's {@code managedBlockOnMainThread}
     * is a real barrier, so the fixpoint actually waits for the target worker.
     */
    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(20L).when(level).getGameTime();
        doReturn(1234L).when(level).getSeed();
        doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(level).dimension();
        doReturn(3).when(level).entitySectionRegionChunkSize();
        doReturn(512).when(level).maxChainedNeighborUpdates();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doAnswer(invocation -> {
            final LevelChunk chunk = mock(LevelChunk.class);
            doReturn(new ChunkPos(invocation.getArgument(0), invocation.getArgument(1))).when(chunk).getPos();
            return chunk;
        }).when(chunkSource).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> {
            final LevelChunk chunk = mock(LevelChunk.class);
            doReturn(new ChunkPos(invocation.getArgument(0), invocation.getArgument(1))).when(chunk).getPos();
            return chunk;
        }).when(chunkSource).getChunkNow(anyInt(), anyInt());
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelField(level, "neighborUpdater", new RegionAwareNeighborUpdater(level, 512));
        return level;
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final Field field = Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
