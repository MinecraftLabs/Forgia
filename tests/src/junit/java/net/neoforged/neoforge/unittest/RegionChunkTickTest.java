/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickEntry;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the region-scoped chunk tick against the real engine, the real {@link RegionChunkTickPlan} and the
 * real {@link ServerLevel#tickChunk(LevelChunk, int)} body.
 * <p>
 * Only the level and its chunks are mocks: the plan decision, the region grouping, the leases, the batch
 * barrier, the per-chunk random sources, the deferred effects and the server-thread fallback all run for real.
 * Chunks are chosen in the {@link RegionPolicy#FIXED_3X3} grid, where chunk {@code (1,1)} is interior to region
 * {@code (0,0)} and chunk {@code (4,4)} is interior to region {@code (1,1)}, while chunk {@code (0,0)} sits on
 * the region boundary and has to stay on the server thread.
 */
@Timeout(60)
class RegionChunkTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int RANDOM_TICK_SPEED = 3;
    private static final ChunkPos INTERIOR_CHUNK = new ChunkPos(1, 1);
    private static final ChunkPos OTHER_INTERIOR_CHUNK = new ChunkPos(4, 4);
    private static final ChunkPos BOUNDARY_CHUNK = new ChunkPos(0, 0);

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        RegionChunkTickAttribution.setEnabled(false);
        this.engine = null;
    }

    @Test
    void chunkTickOfAnInteriorChunkRunsOnARegionWorker() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        // One randomly ticking section, three random ticks, one block and no fluid.
        assertEquals(RANDOM_TICK_SPEED, recorder.ticks.size(), "every selected random tick has to run exactly once");
        assertTrue(recorder.ticks.stream().allMatch(tick -> tick.thread().startsWith("NeoForge Region Worker")),
                () -> "an interior chunk has to tick on a region worker: " + recorder.ticks);
        assertEquals(1L, this.engine.diagnostics().chunkTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackChunkTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void boundaryChunkStaysOnTheServerThreadAndIsCounted() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(BOUNDARY_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(RANDOM_TICK_SPEED, recorder.ticks.size(), "the boundary chunk still ticks exactly once");
        assertTrue(recorder.ticks.stream().allMatch(tick -> tick.thread().equals(Thread.currentThread().getName())),
                () -> "a boundary chunk must keep the server-thread body: " + recorder.ticks);
        assertEquals(1L, this.engine.diagnostics().boundaryChunkTicks());
        assertEquals(1L, this.engine.diagnostics().fallbackChunkTicks());
        assertEquals(0L, this.engine.diagnostics().chunkTicksOnWorkers());
        assertTrue(plan.collectedChunks().isEmpty(), "a boundary chunk must not be handed to the batch");
    }

    @Test
    void unloadedNeighborhoodStaysOnTheServerThreadAndIsCounted() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> !(invocation.getArgument(0, Integer.class) == INTERIOR_CHUNK.x + 1
                && invocation.getArgument(1, Integer.class) == INTERIOR_CHUNK.z)).when(chunkSource).hasChunk(anyInt(), anyInt());
        doReturn(chunkSource).when(level).getChunkSource();
        doReturn(chunkSource).when(level).getChunkSource();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(RANDOM_TICK_SPEED, recorder.ticks.size());
        assertTrue(recorder.ticks.stream().allMatch(tick -> tick.thread().equals(Thread.currentThread().getName())),
                () -> "a chunk with an unloaded neighbor must keep the server-thread body: " + recorder.ticks);
        assertEquals(1L, this.engine.diagnostics().unloadedNeighborhoodChunkTicks());
        assertEquals(1L, this.engine.diagnostics().fallbackChunkTicks());
        assertEquals(0L, this.engine.diagnostics().chunkTicksOnWorkers());
    }

    @Test
    void planIsDisabledWhenTheChunkTickWorkersAreOff() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER));
        assertFalse(this.engine.diagnostics().chunkTickWorkerExecutionEnabled());
        assertFalse(this.engine.diagnostics().chunkTickWorkerDispatchEffective());
    }

    @Test
    void planStaysOnTheServerThreadWithoutRegionOwnedEntityStorage() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // A level that keeps the legacy shared entity section storage cannot answer the region-scoped query a
        // random tick reaches entity storage with, so its chunk tick keeps the server-thread body.
        doReturn(0).when(level).entitySectionRegionChunkSize();

        assertNull(this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER));
        assertEquals(1L, this.engine.diagnostics().nonPartitionedChunkTicks());
    }

    @Test
    void planIsDisabledWhileTheProfilerIsActive() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        doReturn(mock(ProfilerFiller.class)).when(level).getProfiler();

        assertNull(this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER));
    }

    /**
     * Property test of the enumeration: for randomized region layouts and randomized chunk sets, the chunks the
     * plan hands to the batch are exactly the chunks the server thread would tick minus the ones it keeps for
     * itself, and the executed tick of every handed-over chunk happens exactly once.
     */
    @Test
    void collectedChunksEqualTheServerThreadSetForRandomizedLayouts() {
        final Random random = new Random(20260101L);
        for (int layoutSize : new int[] { 3, 4, 5, 8 }) {
            this.installEngine(2, RegionLayout.fixed(layoutSize));
            final ServerLevel level = mockLevel();
            final TickRecorder recorder = new TickRecorder();
            final Set<Long> unloaded = new HashSet<>();
            final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
            doAnswer(invocation -> !unloaded.contains(ChunkPos.asLong(invocation.getArgument(0), invocation.getArgument(1))))
                    .when(chunkSource).hasChunk(anyInt(), anyInt());
            doReturn(chunkSource).when(level).getChunkSource();

            final List<ChunkPos> candidates = new ArrayList<>();
            final Set<Long> seen = new HashSet<>();
            while (candidates.size() < 24) {
                final ChunkPos candidate = new ChunkPos(random.nextInt(layoutSize * 3) - layoutSize, random.nextInt(layoutSize * 3) - layoutSize);
                if (seen.add(candidate.toLong())) {
                    candidates.add(candidate);
                }
            }
            for (int index = 0; index < 4; index++) {
                final ChunkPos candidate = candidates.get(random.nextInt(candidates.size()));
                unloaded.add(ChunkPos.asLong(candidate.x + 1, candidate.z));
            }
            final List<ChunkPos> expected = new ArrayList<>();
            final List<LevelChunk> chunks = new ArrayList<>();
            for (final ChunkPos candidate : candidates) {
                chunks.add(chunk(candidate, recorder));
                if (isInteriorChunk(candidate, layoutSize) && !hasUnloadedNeighbor(candidate, unloaded)) {
                    expected.add(candidate);
                }
            }

            final RegionChunkTickPlan plan = this.beginPlan(level);
            chunks.forEach(plan::accept);
            assertEquals(expected, plan.collectedChunks().stream().map(RegionChunkTickEntry::chunkPos).toList(),
                    () -> "the plan has to hand over exactly the chunks of layout " + layoutSize + " that may leave the server thread");
            plan.executeAndAwait(PARKING_BARRIER);

            final Set<Long> workerTickedChunks = new HashSet<>();
            final Set<Long> serverTickedChunks = new HashSet<>();
            for (final Tick tick : recorder.ticks) {
                final long chunkKey = ChunkPos.asLong(tick.pos().getX() >> 4, tick.pos().getZ() >> 4);
                if (tick.thread().startsWith("NeoForge Region Worker")) {
                    workerTickedChunks.add(chunkKey);
                } else {
                    serverTickedChunks.add(chunkKey);
                }
            }
            assertEquals(new HashSet<>(expected.stream().map(ChunkPos::toLong).toList()), workerTickedChunks,
                    "every handed-over chunk has to be ticked on a worker");
            for (final long serverChunk : serverTickedChunks) {
                assertFalse(workerTickedChunks.contains(serverChunk), "a chunk must be ticked exactly once");
            }
            assertEquals(chunks.size(), workerTickedChunks.size() + serverTickedChunks.size(),
                    "every chunk of the level's tick has to be ticked exactly once");
            RegionThreadingManager.clear();
        }
    }

    @Test
    void randomTickWritesInsideTheRegionExactlyOnceOnTheWorker() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        // The recorded position is the write the random tick performed: every position is inside the region of
        // the chunk, and each of them was reached exactly once.
        assertEquals(RANDOM_TICK_SPEED, recorder.positions().size(), "a write must be applied exactly once");
        assertEquals(RANDOM_TICK_SPEED, new HashSet<>(recorder.positions()).size(), "no position may be ticked twice");
        for (final BlockPos pos : recorder.positions()) {
            assertTrue(isInteriorChunk(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE),
                    () -> "a worker chunk may only write inside its own region: " + pos);
        }
    }

    @Test
    void randomTickOfABoundaryChunkAppliesItsWriteExactlyOnceOnTheServerThread() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(BOUNDARY_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(RANDOM_TICK_SPEED, recorder.positions().size(), "the effect has to happen exactly once");
        assertEquals(RANDOM_TICK_SPEED, new HashSet<>(recorder.positions()).size());
        assertTrue(recorder.ticks.stream().allMatch(tick -> tick.thread().equals(Thread.currentThread().getName())),
                () -> "the boundary chunk keeps the server-thread body: " + recorder.ticks);
        assertEquals(1L, this.engine.diagnostics().boundaryChunkTicks());
    }

    @Test
    void precipitationRunsExactlyOncePerChunkWithTheChunkRandom() {
        this.installEngine(true);
        final List<BlockPos> precipitation = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        doAnswer(invocation -> {
            precipitation.add(invocation.getArgument(0));
            threads.add(Thread.currentThread().getName());
            return null;
        }).when(level).tickPrecipitation(any());
        final TickRecorder recorder = new TickRecorder();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);

        // Replay the chunk random of the body: the thunder roll only happens while it rains and thunders, so the
        // body draws the precipitation roll three times and the position selection once per selected tick.
        final long seed = RegionChunkTickEntry.randomSeed(level, INTERIOR_CHUNK.toLong());
        final RandomSource replay = RandomSource.create(seed);
        int expectedPrecipitation = 0;
        for (int draw = 0; draw < RANDOM_TICK_SPEED; draw++) {
            if (replay.nextInt(48) == 0) {
                expectedPrecipitation++;
                replay.nextInt();
            }
        }

        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(expectedPrecipitation, precipitation.size(), "precipitation has to run exactly once per selected draw");
        assertTrue(threads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")),
                () -> "precipitation of an interior chunk runs on the worker: " + threads);
    }

    @Test
    void lightningIsDeferredToTheServerThreadAndAppliedExactlyOnce() {
        this.installEngine(true);
        final AtomicLong gameTime = new AtomicLong();
        final ServerLevel level = mockLevel();
        doAnswer(invocation -> gameTime.get()).when(level).getGameTime();
        doReturn(true).when(level).isRaining();
        doReturn(true).when(level).isThundering();
        doNothing().when(level).tickLightning(any());
        final long strikeGameTime = gameTimeWithLightningStrike(level, INTERIOR_CHUNK);
        assertTrue(strikeGameTime > 0L, "no game time in the scan produced a lightning roll");
        gameTime.set(strikeGameTime);

        final long seed = RegionChunkTickEntry.randomSeed(level, INTERIOR_CHUNK.toLong());
        final RandomSource replay = RandomSource.create(seed);
        assertEquals(0, replay.nextInt(100000), "the replayed roll has to be the strike of the chosen tick");
        final int value = replay.nextInt() >> 2;
        final BlockPos expected = new BlockPos(
                INTERIOR_CHUNK.getMinBlockX() + (value & 15),
                value >> 16 & 15,
                INTERIOR_CHUNK.getMinBlockZ() + (value >> 8 & 15));

        final TickRecorder recorder = new TickRecorder();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        verify(level, times(1)).tickLightning(expected);
        assertEquals(1L, this.engine.diagnostics().chunkTickLightningStrikes());
        assertEquals(1L, this.engine.diagnostics().chunkTicksOnWorkers());
    }

    @Test
    void lightningIsResolvedInlineOutsideAChunkTickGroup() {
        final ServerLevel level = mockLevel();
        assertFalse(RegionThreadingHooks.deferChunkTickLightning(new BlockPos(1, 64, 1)),
                "the server thread resolves a strike inline");
    }

    @Test
    void chunkThatNeedsAnUnavailableChunkIsCountedAsTruncated() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder, () -> {
            throw RegionWorkerChunkUnavailableException.forChunk(INTERIOR_CHUNK.x, INTERIOR_CHUNK.z, ChunkStatus.FULL);
        });
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(0, recorder.ticks.size(), "the truncated tick must not continue after the refusal");
        assertEquals(1L, this.engine.diagnostics().truncatedChunkTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void failedChunkTickReleasesTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, new TickRecorder(), () -> {
            throw new IllegalStateException("boom");
        });
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);

        assertThrows(IllegalStateException.class, () -> plan.executeAndAwait(PARKING_BARRIER));

        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void chunkTicksOfDifferentRegionsRunInParallel() {
        this.installEngine(true);
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final ServerLevel level = mockLevel();
        final TickRecorder recorder = new TickRecorder(rendezvous);
        final LevelChunk first = chunk(INTERIOR_CHUNK, recorder);
        final LevelChunk second = chunk(OTHER_INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(first);
        plan.accept(second);
        plan.executeAndAwait(PARKING_BARRIER);

        // The rendezvous only opens when both regions are inside their worker tick at the same time.
        assertEquals(2L, this.engine.diagnostics().chunkTicksOnWorkers());
    }

    @Test
    void chunkTicksOfTheSameRegionRunSeriallyInListOrder() {
        // A 5x5 fixed region has three interior chunk columns, so two interior chunks can share one region.
        this.installEngine(2, RegionLayout.fixed(5));
        final ServerLevel level = mockLevel();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<Long> order = new CopyOnWriteArrayList<>();
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                order.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        final ChunkPos firstPos = new ChunkPos(1, 1);
        final ChunkPos secondPos = new ChunkPos(2, 2);
        final LevelChunk first = chunk(firstPos, recorder);
        final LevelChunk second = chunk(secondPos, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(first);
        plan.accept(second);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, maxActive.get(), "one region must never tick against itself");
        assertEquals(2L, this.engine.diagnostics().chunkTicksOnWorkers());
        final List<Long> chunksInOrder = order.stream().distinct().toList();
        assertEquals(List.of(firstPos.toLong(), secondPos.toLong()), chunksInOrder,
                "the chunks of one region tick in collection order");
    }

    @Test
    void levelTicksMutationFromAChunkTickWorkerIsAppliedOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = new LevelTicks<>(chunk -> true, () -> InactiveProfiler.INSTANCE);
        ticks.addContainer(INTERIOR_CHUNK, new LevelChunkTicks<>());
        final Block type = mock(Block.class);
        final AtomicBoolean deferredInsideWorker = new AtomicBoolean();
        final AtomicReference<String> thread = new AtomicReference<>();
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                thread.set(Thread.currentThread().getName());
                assertNotNull(RegionThreadingHooks.regionTickMutationQueue(), "a chunk tick worker must have a mutation queue");
                ticks.schedule(new ScheduledTick<>(type, pos, 100L, 0L));
                deferredInsideWorker.set(!ticks.hasScheduledTick(pos, type));
            }
        };
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(thread.get().startsWith("NeoForge Region Worker"), thread.get());
        assertTrue(deferredInsideWorker.get(), "a worker must not mutate LevelTicks directly");
        assertTrue(recorder.positions().stream().allMatch(pos -> ticks.hasScheduledTick(pos, type)),
                "the queued mutation has to be applied on the server thread after the batch");
    }

    @Test
    void chunkTickWorkerOwnsTheBlockEntitiesOfItsRegion() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final AtomicBoolean ownedInsideRegion = new AtomicBoolean();
        final AtomicBoolean ownedOutsideRegion = new AtomicBoolean();
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                ownedInsideRegion.set(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, pos));
                ownedOutsideRegion.set(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, new BlockPos(4096, 64, 4096)));
            }
        };
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(ownedInsideRegion.get(), "a random tick has to reach the block entities of its own region");
        assertFalse(ownedOutsideRegion.get(), "a random tick must not reach another region's block entities");
    }

    @Test
    void entityAdditionFromAChunkTickWorkerIsAdmittedAtTheBarrier() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.isAddedToLevel()).thenReturn(false);
        when(entity.isRemoved()).thenReturn(false);
        when(entity.blockPosition()).thenReturn(new BlockPos(20, 64, 20));
        when(entity.getSelfAndPassengers()).thenAnswer(invocation -> java.util.stream.Stream.of(entity));
        doReturn(true).when(level).addFreshEntity(any());
        final AtomicBoolean deferred = new AtomicBoolean();
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                deferred.set(RegionThreadingHooks.deferEntityAddition(level, entity));
            }
        };
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(deferred.get(), "a random tick spawns through the deferred admission of its lease");
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityAdditions());
        verify(level, times(1)).addFreshEntity(entity);
    }

    @Test
    void levelUnloadWaitsForTheRunningChunkTick() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                ticking.countDown();
                awaitOrFail(release, "the chunk tick was never released");
                tickFinished.set(true);
            }
        };
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait(PARKING_BARRIER);
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "chunk-tick-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the chunk tick never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final Thread unloadThread = new Thread(() -> {
            this.engine.clearLevelRegionTasks(level);
            unloadReturned.set(true);
        }, "chunk-tick-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level unload must wait for the running chunk tick batch");
        assertFalse(tickFinished.get());

        release.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the chunk tick batch never completed");
        assertFalse(unloadThread.isAlive(), "the level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void shutdownMidBatchClosesCleanly() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TickRecorder recorder = new TickRecorder() {
            @Override
            void record(BlockPos pos, RandomSource random) {
                ticking.countDown();
                awaitOrFail(release, "the chunk tick was never released");
            }
        };
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait(PARKING_BARRIER);
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "chunk-tick-shutdown-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the chunk tick never started");

        final AtomicBoolean shutdownReturned = new AtomicBoolean();
        final Thread shutdownThread = new Thread(() -> {
            this.engine.shutdown();
            shutdownReturned.set(true);
        }, "chunk-tick-engine-shutdown");
        shutdownThread.start();
        Thread.sleep(200L);
        assertFalse(shutdownReturned.get(), "the engine shutdown must wait for the running chunk tick batch");

        release.countDown();
        batchThread.join(10_000L);
        shutdownThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the chunk tick batch never completed");
        assertFalse(shutdownThread.isAlive(), "the engine shutdown never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void aLevelWithoutTheEngineKeepsTheVanillaChunkTick() {
        RegionThreadingManager.clear();
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);

        assertNull(RegionThreadingHooks.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER),
                "a server without an engine has no chunk tick plan");
        assertNull(RegionThreadingHooks.chunkTickPositionRandom());
        level.tickChunk(chunk, RANDOM_TICK_SPEED);

        assertEquals(RANDOM_TICK_SPEED, recorder.ticks.size());
        assertTrue(recorder.ticks.stream().allMatch(tick -> tick.thread().equals(Thread.currentThread().getName())));
    }

    @Test
    void twoRunsWithTheSameSeedProduceTheSamePositionsPerChunk() {
        final List<BlockPos> firstRun = this.runInteriorChunkTick();
        final List<BlockPos> secondRun = this.runInteriorChunkTick();

        assertEquals(firstRun, secondRun, "the same seed and layout have to produce the same positions per chunk");
        assertFalse(firstRun.isEmpty(), "the workload has to select at least one random tick");
    }

    @Test
    void positionSelectionOfAChunkDoesNotDependOnTheOtherChunksOfItsRegion() {
        // A 5x5 fixed region keeps both chunks in one region, so the second chunk ticks after the first one and
        // would inherit its random state if the selection were region-scoped instead of per chunk.
        final List<BlockPos> alone = this.runTwoInteriorChunksOfOneRegion(true);
        final List<BlockPos> afterTheFirstChunk = this.runTwoInteriorChunksOfOneRegion(false);

        assertEquals(alone, afterTheFirstChunk, "a chunk's selection must not depend on the chunks before it");
    }

    @Test
    void fallbackChunkDrawsTheSamePositionsAsAWorkerChunk() throws InterruptedException {
        final List<BlockPos> onTheWorker = this.runInteriorChunkTick();

        // Force the refusal deterministically with the deferred-update budget: the first chunk takes the only
        // reservation, so the chunk under test is refused before its body and ticks inline with the same
        // per-chunk random source. Holding a synthetic lease on the region instead raced with the plan's own
        // lease attempt and made this test flaky.
        this.installEngine(true);
        this.engine.setChunkTickDeferredUpdateBudget(1);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk first = chunk(OTHER_INTERIOR_CHUNK, new TickRecorder());
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(first);
        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1L, this.engine.diagnostics().deferredUpdateBudgetChunkTicks(),
                "the chunk under test has to be refused by the reservation, not dispatched");
        assertEquals(onTheWorker, recorder.positions(), "a fallback chunk has to draw the same positions as a worker chunk");
        assertTrue(recorder.threads().stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the refused chunk has to tick on the server thread: " + recorder.threads());
    }

    @Test
    void attributionCountsOneSlicePerChunkTickPass() {
        this.installEngine(true);
        RegionChunkTickAttribution.setEnabled(true);
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, new TickRecorder());
        final RegionChunkTickPlan plan = RegionThreadingHooks.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");

        plan.accept(chunk);
        RegionThreadingHooks.runChunkTickPlan(plan);

        final RegionChunkTickAttribution.Snapshot snapshot = RegionChunkTickAttribution.snapshot();
        assertEquals(1L, snapshot.sliceCount(), "one chunk tick pass is one slice");
        assertTrue(snapshot.sliceNanos() > 0L, "the slice has to be measured");
        assertEquals(1L, snapshot.latency().count());
        assertTrue(snapshot.latency().percentileNanos(0.50) > 0L, "the slice latency distribution has to report a p50");
        assertTrue(snapshot.planNanos() <= snapshot.sliceNanos(), "the plan segment is part of the slice");
    }

    private List<BlockPos> runInteriorChunkTick() {
        this.installEngine(true);
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk chunk = chunk(INTERIOR_CHUNK, recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);
        RegionThreadingManager.clear();
        return recorder.positions();
    }

    private List<BlockPos> runTwoInteriorChunksOfOneRegion(boolean firstChunkOnly) {
        this.installEngine(2, RegionLayout.fixed(5));
        final TickRecorder recorder = new TickRecorder();
        final ServerLevel level = mockLevel();
        final LevelChunk second = chunk(new ChunkPos(2, 2), recorder);
        final RegionChunkTickPlan plan = this.beginPlan(level);
        if (!firstChunkOnly) {
            plan.accept(chunk(new ChunkPos(1, 1), new TickRecorder()));
        }
        plan.accept(second);
        plan.executeAndAwait(PARKING_BARRIER);
        RegionThreadingManager.clear();
        return recorder.positions();
    }

    private void installEngine(boolean chunkTickWorkers) {
        this.installEngine(2, RegionPolicy.FIXED_3X3, chunkTickWorkers);
    }

    private void installEngine(int workerThreads, RegionLayout layout) {
        this.installEngine(workerThreads, layout, true);
    }

    private void installEngine(int workerThreads, RegionPolicy policy, boolean chunkTickWorkers) {
        this.installEngine(workerThreads, RegionLayout.of(policy), chunkTickWorkers);
    }

    private void installEngine(int workerThreads, RegionLayout layout, boolean chunkTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, false, false, false,
                chunkTickWorkers, layout);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionChunkTickPlan beginPlan(ServerLevel level) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        return plan;
    }

    /** {@return true when all eight neighbors of {@code chunk} belong to the same fixed region} */
    private static boolean isInteriorChunk(ChunkPos chunk, int regionSize) {
        final int regionX = Math.floorDiv(chunk.x, regionSize);
        final int regionZ = Math.floorDiv(chunk.z, regionSize);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (Math.floorDiv(chunk.x + offsetX, regionSize) != regionX || Math.floorDiv(chunk.z + offsetZ, regionSize) != regionZ) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@return true when one of the eight neighbors of {@code chunk} is in {@code unloaded}} */
    private static boolean hasUnloadedNeighbor(ChunkPos chunk, Set<Long> unloaded) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                if (unloaded.contains(ChunkPos.asLong(chunk.x + offsetX, chunk.z + offsetZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return a game time whose lightning roll of {@code chunk} hits, or {@code -1} when the scan found none}
     * <p>
     * The roll is {@code random.nextInt(100000) == 0} on the chunk's own random source, whose seed is derived from
     * the level seed, the game time and the chunk. Scanning the game time therefore finds a tick of this chunk
     * that strikes without stubbing the random source, which keeps the test on the production derivation.
     */
    private static long gameTimeWithLightningStrike(ServerLevel level, ChunkPos chunk) {
        final AtomicLong probe = new AtomicLong();
        final ServerLevel scanner = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(level.getSeed()).when(scanner).getSeed();
        doAnswer(invocation -> probe.get()).when(scanner).getGameTime();
        doReturn(level.dimension()).when(scanner).dimension();
        for (long gameTime = 1L; gameTime < 4_000_000L; gameTime++) {
            probe.set(gameTime);
            if (RandomSource.create(RegionChunkTickEntry.randomSeed(scanner, chunk.toLong())).nextInt(100000) == 0) {
                return gameTime;
            }
        }
        return -1L;
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(20L).when(level).getGameTime();
        when(level.dimension()).thenReturn(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse("minecraft:overworld")));
        // The physical section bucket of a region is what the ownership lease tests the entity position against.
        doReturn(3).when(level).entitySectionRegionChunkSize();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickLightning(any());
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(RANDOM_TICK_SPEED);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        return level;
    }

    private static LevelChunk chunk(ChunkPos pos, TickRecorder recorder) {
        return chunk(pos, recorder, null);
    }

    private static LevelChunk chunk(ChunkPos pos, TickRecorder recorder, Runnable onRandomTick) {
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(false);
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            if (onRandomTick != null) {
                onRandomTick.run();
            }
            recorder.record(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(blockState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));
        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        return chunk;
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final Field field = net.minecraft.world.level.Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
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

    /** Records the random ticks that ran, so a test can assert the thread, the order and the effect. */
    private static class TickRecorder {
        private final List<Tick> ticks = new CopyOnWriteArrayList<>();
        private final CyclicBarrier rendezvous;

        TickRecorder() {
            this(null);
        }

        TickRecorder(CyclicBarrier rendezvous) {
            this.rendezvous = rendezvous;
        }

        void record(BlockPos pos, RandomSource random) {
            this.ticks.add(new Tick(Thread.currentThread().getName(), pos.immutable()));
            if (this.rendezvous != null) {
                try {
                    this.rendezvous.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }

        List<BlockPos> positions() {
            return this.ticks.stream().map(Tick::pos).sorted(Comparator.comparing(BlockPos::asLong)).toList();
        }

        List<String> threads() {
            return this.ticks.stream().map(Tick::thread).distinct().toList();
        }
    }

    private record Tick(String thread, BlockPos pos) {}
}
