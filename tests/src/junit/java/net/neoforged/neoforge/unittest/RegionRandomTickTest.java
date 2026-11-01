/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionRandomTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Answers;

/**
 * Exercises the bounded random block/fluid tick slice against the real engine and the real
 * {@link RegionRandomTickPlan} dispatcher.
 * <p>
 * The plan receives the real callbacks the level selects, so collection order, per-region grouping,
 * region leases, the batch barrier, the random sources and the server-thread fallback all run for real;
 * only the level is a mock. Chunks are chosen in the {@link RegionPolicy#FIXED_3X3} grid: chunk
 * {@code (1,1)} is interior to region {@code (0,0)} and chunk {@code (4,4)} is interior to region
 * {@code (1,1)}, while chunk {@code (0,0)} sits on the region boundary and must stay on the server
 * thread.
 */
@Timeout(30)
class RegionRandomTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final ChunkPos INTERIOR_CHUNK = new ChunkPos(1, 1);
    private static final ChunkPos OTHER_INTERIOR_CHUNK = new ChunkPos(4, 4);
    private static final ChunkPos BOUNDARY_CHUNK = new ChunkPos(0, 0);

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void tickChunkSelectsOnlyRandomlyTickingStatesForThePlan() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        doNothing().when(level).tickPrecipitation(any());
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(INTERIOR_CHUNK);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        final RegionRandomTickPlan plan = this.beginPlan(level);

        level.tickChunk(chunk, 3, plan);
        plan.executeAndAwait(PARKING_BARRIER);

        verify(blockState, times(3)).randomTick(eq(level), any(BlockPos.class), any(RandomSource.class));
        verify(fluidState, times(3)).randomTick(eq(level), any(BlockPos.class), any(RandomSource.class));
        assertEquals(6L, this.engine.diagnostics().randomTicksOnWorkers());
    }

    /**
     * Leases the chunk's region on another thread and runs the real {@code ServerChunkCache.tickChunks}.
     * The chunk body has to run on the calling server thread before the lease is released: chunk
     * selection, inhabited time, natural spawning, {@code tickChunk} and position selection all stay
     * inline, and only the selected random tick callbacks are handed to the plan. The chunk is a region
     * boundary chunk so its collected callbacks execute on the server thread without trying to lease
     * the region the holder already owns.
     */
    @Test
    void leasedRegionDoesNotDeferTheServerChunkTickBody() throws InterruptedException {
        this.installEngine(true);
        final String serverThread = Thread.currentThread().getName();
        final List<String> chunkSelectionThreads = new CopyOnWriteArrayList<>();
        final List<String> inhabitedThreads = new CopyOnWriteArrayList<>();
        final List<String> spawnThreads = new CopyOnWriteArrayList<>();
        final List<String> tickChunkThreads = new CopyOnWriteArrayList<>();
        final List<String> positionThreads = new CopyOnWriteArrayList<>();
        final List<String> randomTickThreads = new CopyOnWriteArrayList<>();

        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            randomTickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(blockState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));
        doAnswer(invocation -> {
            randomTickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(fluidState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));

        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(BOUNDARY_CHUNK);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        doAnswer(invocation -> {
            inhabitedThreads.add(Thread.currentThread().getName());
            return null;
        }).when(chunk).incrementInhabitedTime(anyLong());
        doAnswer(invocation -> {
            spawnThreads.add(Thread.currentThread().getName());
            return 99;
        }).when(chunk).getHeight(any(Heightmap.Types.class), anyInt(), anyInt());

        final ChunkHolder holder = mock(ChunkHolder.class);
        when(holder.getTickingChunk()).thenReturn(chunk);
        final ChunkMap chunkMap = mock(ChunkMap.class, invocation -> switch (invocation.getMethod().getName()) {
            case "size" -> 1;
            case "getChunks" -> List.of(holder);
            case "anyPlayerCloseEnoughForSpawning" -> {
                chunkSelectionThreads.add(Thread.currentThread().getName());
                yield true;
            }
            case "getPlayersCloseForSpawning" -> {
                spawnThreads.add(Thread.currentThread().getName());
                yield List.of(mock(ServerPlayer.class));
            }
            default -> Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        final DistanceManager distanceManager = mock(DistanceManager.class);
        when(distanceManager.getNaturalSpawnChunkCount()).thenReturn(10_000);
        when(distanceManager.shouldForceTicks(anyLong())).thenReturn(false);

        final ServerLevel level = mockLevel();
        doReturn(false).when(level).isRaining();
        doReturn(20L).when(level).getGameTime();
        doReturn(100).when(level).getMinBuildHeight();
        doReturn(List.of()).when(level).getAllEntities();
        doReturn(true).when(level).isNaturalSpawningAllowed(any(ChunkPos.class));
        doReturn(true).when(level).shouldTickBlocksAt(anyLong());
        final WorldBorder worldBorder = mock(WorldBorder.class);
        when(worldBorder.isWithinBounds(any(ChunkPos.class))).thenReturn(true);
        doReturn(worldBorder).when(level).getWorldBorder();
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(true);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(3);
        doReturn(gameRules).when(level).getGameRules();
        final LevelData levelData = mock(LevelData.class);
        when(levelData.getGameTime()).thenReturn(20L);
        doReturn(levelData).when(level).getLevelData();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickCustomSpawners(anyBoolean(), anyBoolean());
        doAnswer(invocation -> {
            positionThreads.add(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(level).getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt());
        doAnswer(invocation -> {
            tickChunkThreads.add(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(level).tickChunk(any(LevelChunk.class), anyInt(), any(RegionRandomTickPlan.class));

        final ServerChunkCache chunkSource = mock(ServerChunkCache.class, CALLS_REAL_METHODS);
        chunkSource.setSpawnSettings(true, true);
        setFieldUnchecked(ServerChunkCache.class, chunkSource, "level", level);
        setFieldUnchecked(ServerChunkCache.class, chunkSource, "chunkMap", chunkMap);
        setFieldUnchecked(ServerChunkCache.class, chunkSource, "distanceManager", distanceManager);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());

        final CountDownLatch leased = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> leaseFailure = new AtomicReference<>();
        final Thread leaseThread = new Thread(() -> {
            try {
                RegionThreadingHooks.runBlockTickTask(level, new BlockPos(1, 64, 1), () -> {
                    leased.countDown();
                    awaitOrFail(release, "the target region lease was never released");
                });
            } catch (Throwable throwable) {
                leaseFailure.set(throwable);
            }
        }, "random-tick-lease-holder");
        leaseThread.start();
        assertTrue(leased.await(10, TimeUnit.SECONDS), "the target region lease was never acquired");

        try {
            invokeTickChunks(chunkSource);

            assertAllOn(serverThread, "chunk selection", chunkSelectionThreads);
            assertAllOn(serverThread, "inhabited time", inhabitedThreads);
            assertAllOn(serverThread, "natural spawning", spawnThreads);
            assertAllOn(serverThread, "chunk tick", tickChunkThreads);
            assertAllOn(serverThread, "position selection", positionThreads);
            assertAllOn(serverThread, "random tick callback", randomTickThreads);
            assertEquals(1, inhabitedThreads.size(), "the chunk body must run exactly once");
            assertEquals(1, tickChunkThreads.size(), "the chunk body must run exactly once");
            assertEquals(6, randomTickThreads.size(), "every selected block and fluid tick must run");
            assertEquals(0L, this.engine.diagnostics().randomTicksOnWorkers());
            assertEquals(6L, this.engine.diagnostics().fallbackRandomTicks());
        } finally {
            release.countDown();
            leaseThread.join(TimeUnit.SECONDS.toMillis(10L));
        }
        assertFalse(leaseThread.isAlive(), "the lease holder did not finish");
        if (leaseFailure.get() != null) {
            throw new AssertionError("the lease holder failed", leaseFailure.get());
        }
    }

    @Test
    void planIsDisabledWhenRandomTickWorkersAreOff() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginRandomTickPlan(level, PARKING_BARRIER));
        assertFalse(this.engine.diagnostics().randomTickWorkerExecutionEnabled());
    }

    @Test
    void selectedCallbacksRunOnARegionWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicReference<RandomSource> passedRandom = new AtomicReference<>();

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> {
            threads.add(Thread.currentThread().getName());
            passedRandom.set(random);
            assertSame(random, RegionThreadingHooks.randomTickRandom(), "the callback must receive the group random source");
        });
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, threads.size());
        assertTrue(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertNotNull(passedRandom.get());
        assertNull(RegionThreadingHooks.randomTickRandom(), "the random source must only be visible inside the worker group");
        assertEquals(1L, this.engine.diagnostics().randomTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackRandomTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void randomTicksOfDifferentRegionsRunInParallel() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final CyclicBarrier rendezvous = new CyclicBarrier(2);

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> awaitRendezvous(rendezvous));
        plan.accept(OTHER_INTERIOR_CHUNK, new BlockPos(65, 64, 65), (tickLevel, pos, random) -> awaitRendezvous(rendezvous));
        plan.executeAndAwait(PARKING_BARRIER);

        // The rendezvous only opens when both regions are inside their worker tick at the same time.
        assertEquals(2L, this.engine.diagnostics().randomTicksOnWorkers());
    }

    @Test
    void randomTicksOfTheSameRegionRunSeriallyInSnapshotOrder() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final BlockPos first = new BlockPos(17, 64, 17);
        final BlockPos second = new BlockPos(18, 64, 18);
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<BlockPos> order = new CopyOnWriteArrayList<>();

        plan.accept(INTERIOR_CHUNK, first, (tickLevel, pos, random) -> {
            order.add(pos);
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        });
        plan.accept(INTERIOR_CHUNK, second, (tickLevel, pos, random) -> order.add(pos));
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, maxActive.get(), "one region must never tick against itself");
        assertEquals(List.of(first, second), order);
    }

    @Test
    void regionGroupsUseIsolatedRandomSources() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final List<RandomSource> firstRegion = new CopyOnWriteArrayList<>();
        final List<RandomSource> secondRegion = new CopyOnWriteArrayList<>();

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> firstRegion.add(random));
        plan.accept(INTERIOR_CHUNK, new BlockPos(18, 64, 18), (tickLevel, pos, random) -> firstRegion.add(random));
        plan.accept(OTHER_INTERIOR_CHUNK, new BlockPos(65, 64, 65), (tickLevel, pos, random) -> secondRegion.add(random));
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(2, firstRegion.size());
        assertSame(firstRegion.get(0), firstRegion.get(1), "one region group reuses its random source");
        assertEquals(1, secondRegion.size());
        assertNotSame(firstRegion.get(0), secondRegion.get(0), "different regions must not share a random source");
        assertNotSame(level.random, firstRegion.get(0), "workers must not draw from the level random");
        assertNotSame(level.random, secondRegion.get(0), "workers must not draw from the level random");
    }

    @Test
    void configuredRandomTickWorkersFallBackToTheVanillaInlinePath() {
        // The production constructor never enables the mechanics seam, so a configured worker slice must
        // report the request but keep the selected random ticks on the server thread.
        this.engine = new RegionThreadingEngine(2, false, false, false, false, false, true, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginRandomTickPlan(level, PARKING_BARRIER), "unclassified production random callbacks must stay on the vanilla inline path");
        assertTrue(this.engine.diagnostics().randomTickWorkerExecutionEnabled(), "the requested configuration must stay visible");
        assertFalse(this.engine.diagnostics().randomTickWorkerDispatchEffective(), "the effective dispatch must stay gated");
        assertEquals(0L, this.engine.diagnostics().randomTicksOnWorkers());
    }

    @Test
    void boundaryChunkRandomTicksStayOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final List<String> threads = new CopyOnWriteArrayList<>();

        plan.accept(BOUNDARY_CHUNK, new BlockPos(1, 64, 1), (tickLevel, pos, random) -> threads.add(Thread.currentThread().getName()));
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0L, this.engine.diagnostics().randomTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackRandomTicks());
    }

    @Test
    void activeProfilerFallsBackToTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        doReturn(mock(ProfilerFiller.class)).when(level).getProfiler();

        assertNull(this.engine.beginRandomTickPlan(level, PARKING_BARRIER));
    }

    @Test
    void entityQueryFromRandomTickWorkerRunsOnTheServerThread() {
        this.installEngine(true);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final AtomicReference<String> queryThread = new AtomicReference<>();
        final AtomicReference<String> result = new AtomicReference<>();

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> {
            assertTrue(RegionThreadingHooks.shouldHandOffEntityQuery(), "a random tick worker must hand entity queries off");
            result.set(RegionThreadingHooks.runOnServerThread(level, () -> {
                queryThread.set(Thread.currentThread().getName());
                return "entities";
            }));
        });
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals("entities", result.get());
        assertEquals(serverThread.getName(), queryThread.get(), "the query must run on the waiting server thread");
    }

    @Test
    void levelTicksMutationFromRandomTickWorkerIsAppliedOnTheServerThread() {
        this.installEngine(true);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final LevelTicks<Block> ticks = newTicks();
        ticks.addContainer(INTERIOR_CHUNK, new LevelChunkTicks<>());
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(17, 64, 17);
        final AtomicBoolean deferredInsideWorker = new AtomicBoolean();

        plan.accept(INTERIOR_CHUNK, pos, (tickLevel, tickPos, random) -> {
            assertNotNull(RegionThreadingHooks.regionTickMutationQueue(), "a random tick worker must have a server-owned mutation queue");
            ticks.schedule(new ScheduledTick<>(type, tickPos, 100L, 0L));
            deferredInsideWorker.set(!ticks.hasScheduledTick(tickPos, type));
        });
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(deferredInsideWorker.get(), "a worker must not mutate LevelTicks directly");
        assertTrue(ticks.hasScheduledTick(pos, type), "the queued mutation must be applied on the server thread after the batch");
    }

    @Test
    void failedRandomTickReleasesTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> plan.executeAndAwait(PARKING_BARRIER));

        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void rejectedWorkerGroupRunsOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(true);
        closeWorkerPool(this.engine);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final BlockPos first = new BlockPos(17, 64, 17);
        final BlockPos second = new BlockPos(18, 64, 18);
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        plan.accept(INTERIOR_CHUNK, first, (tickLevel, pos, random) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(pos);
        });
        plan.accept(INTERIOR_CHUNK, second, (tickLevel, pos, random) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(pos);
        });
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(List.of(first, second), ran, "a rejected group still has to execute every selected tick in order");
        assertTrue(threads.stream().allMatch(name -> !name.startsWith("NeoForge Region Worker")), threads.toString());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void closedLevelSkipsTheCollectedRandomTicks() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> {
            throw new AssertionError("a random tick of a closed level must not run");
        });

        this.engine.clearLevelRegionTasks(level);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1L, this.engine.diagnostics().skippedRandomTicks());
        assertEquals(0L, this.engine.diagnostics().randomTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackRandomTicks());
    }

    @Test
    void shutdownAfterCollectionRunsTheSnapshotOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final List<String> threads = new CopyOnWriteArrayList<>();

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> threads.add(Thread.currentThread().getName()));
        this.engine.shutdown();
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0L, this.engine.diagnostics().randomTicksOnWorkers());
    }

    @Test
    void levelUnloadWaitsForTheRunningRandomTickBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final RegionRandomTickPlan plan = this.beginPlan(level);
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();

        plan.accept(INTERIOR_CHUNK, new BlockPos(17, 64, 17), (tickLevel, pos, random) -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the random tick was never released");
            tickFinished.set(true);
        });
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait(PARKING_BARRIER);
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "random-tick-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the random tick never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                if (!tickFinished.get()) {
                    unloadFailure.set(new AssertionError("the level unload returned before the random tick finished"));
                }
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "random-tick-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level unload must wait for the running random tick batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the random tick batch never completed");
        assertFalse(unloadThread.isAlive(), "the level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    private void installEngine(boolean randomTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, randomTickWorkers, RegionPolicy.FIXED_3X3);
        if (randomTickWorkers) {
            // Production falls back to the vanilla inline path for unclassified random callbacks; these
            // tests explicitly opt into the internal mechanics seam.
            created.enableUnclassifiedWorkerCallbacksForTesting();
        }
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionRandomTickPlan beginPlan(ServerLevel level) {
        final RegionRandomTickPlan plan = this.engine.beginRandomTickPlan(level, PARKING_BARRIER);
        assertNotNull(plan, "random tick worker execution must be enabled");
        return plan;
    }

    private static <T> LevelTicks<T> newTicks() {
        return new LevelTicks<>(chunk -> true, () -> InactiveProfiler.INSTANCE);
    }

    private static void awaitRendezvous(CyclicBarrier rendezvous) {
        try {
            rendezvous.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ServerLevel mockLevel() {
        return mockLevel(null, null);
    }

    private static ServerLevel mockLevel(Queue<Runnable> serverTasks, Thread serverThread) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        doReturn(List.of()).when(level).getPartEntities();
        setLevelFieldUnchecked(level, "random", RandomSource.create(1234L));
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final java.util.function.BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        if (serverTasks != null && serverThread != null) {
            final MinecraftServer server = mock(MinecraftServer.class);
            when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == serverThread);
            doAnswer(invocation -> {
                serverTasks.add(invocation.getArgument(0));
                return null;
            }).when(server).execute(any(Runnable.class));
            doReturn(server).when(level).getServer();
        }
        return level;
    }

    private static void setLevelFieldUnchecked(ServerLevel level, String name, Object value) {
        setFieldUnchecked(net.minecraft.world.level.Level.class, level, name, value);
    }

    private static void setFieldUnchecked(Class<?> owner, Object instance, String name, Object value) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void invokeTickChunks(ServerChunkCache chunkSource) {
        try {
            final Method method = ServerChunkCache.class.getDeclaredMethod("tickChunks");
            method.setAccessible(true);
            method.invoke(chunkSource);
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertAllOn(String expectedThread, String label, List<String> threads) {
        assertFalse(threads.isEmpty(), label + " was never observed");
        for (final String thread : threads) {
            assertEquals(expectedThread, thread, label + " ran on " + thread);
        }
    }

    private static void closeWorkerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        final Object pool = field.get(engine);
        final Method close = pool.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(pool);
    }

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
