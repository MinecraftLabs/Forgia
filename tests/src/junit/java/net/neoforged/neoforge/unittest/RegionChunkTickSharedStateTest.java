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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the two level-wide structures a chunk tick worker reaches through a block write: the light engine of the
 * level and the block update publication of the level.
 * <p>
 * A worker drives neither of them. It records the light section status change, the light recheck and the block
 * update of every write, and the server thread applies the records at the batch barrier, in recording order. The
 * tests prove that the worker really defers (nothing reaches the engine or the level from the worker thread), that
 * the deferred path produces exactly the light state the server-thread path produces for the same seed, and that
 * the deferred queue is bounded by the plan and drained when a batch is interrupted.
 * <p>
 * The chunk a write reaches is a real {@link LevelChunk} with real block palettes and the light engine is a real
 * {@link LevelLightEngine}, so the light arrays compared here are the ones the production light engine computes.
 * Only the level, its chunk source and the chunk the tick iterates are mocks.
 */
@Timeout(60)
class RegionChunkTickSharedStateTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int RANDOM_TICK_SPEED = 3;
    private static final ChunkPos INTERIOR_CHUNK = new ChunkPos(1, 1);
    private static final ChunkPos SECOND_INTERIOR_CHUNK = new ChunkPos(4, 4);
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;
    /** Section the ticked chunk reports, so every position the tick selects lands in it. */
    private static final int SECTION_Y = 4;

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void blockUpdateOfAWorkerWriteIsPublishedOnTheServerThreadAtTheBarrier() {
        this.installEngine();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            return null;
        }).when(level).sendBlockUpdated(any(), any(), any(), anyInt());
        final List<BlockPos> written = new CopyOnWriteArrayList<>();
        final LevelChunk chunk = tickedChunk(INTERIOR_CHUNK, pos -> {
            written.add(pos);
            // The production seam: a chunk tick worker records the publication instead of publishing it.
            assertTrue(RegionThreadingHooks.deferBlockUpdate(pos, Blocks.AIR.defaultBlockState(),
                    Blocks.GLOWSTONE.defaultBlockState(), 3), "a chunk tick worker has to defer the publication");
        });
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(RANDOM_TICK_SPEED, written.size(), "every selected random tick has to run exactly once");
        assertEquals(written, published, "every deferred write has to be published once, in recording order");
        assertTrue(publishThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the publication has to run on the server thread: " + publishThreads);
        assertEquals(RANDOM_TICK_SPEED, this.engine.diagnostics().deferredBlockUpdates().applied(RegionTickSlice.CHUNK_TICK));
        assertEquals(0L, this.engine.diagnostics().deferredBlockUpdates().dropped(RegionTickSlice.CHUNK_TICK));
    }

    @Test
    void blockUpdateAndLightRecheckAreInlineOutsideAChunkTickGroup() {
        mockLevel();
        assertFalse(RegionThreadingHooks.deferBlockUpdate(BlockPos.ZERO, Blocks.AIR.defaultBlockState(),
                Blocks.GLOWSTONE.defaultBlockState(), 3), "the server thread publishes a block update inline");
        assertFalse(RegionThreadingHooks.deferLightCheck(BlockPos.ZERO), "the server thread rechecks light inline");
        assertFalse(RegionThreadingHooks.deferLightSectionStatus(BlockPos.ZERO, false),
                "the server thread updates the light section status inline");
    }

    @Test
    void lightRecheckOfAWorkerWriteIsAppliedByTheBarrierInRecordingOrder() {
        this.installEngine();
        final List<String> engineThreads = new CopyOnWriteArrayList<>();
        final List<String> engineCalls = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        final LevelLightEngine real = realLightEngine(level, realChunks);
        final ThreadedLevelLightEngine delegated = delegatingLightEngine(level, real, engineThreads, engineCalls);
        final List<BlockPos> written = new CopyOnWriteArrayList<>();
        final LevelChunk chunk = tickedChunk(INTERIOR_CHUNK, pos -> {
            written.add(pos);
            assertTrue(RegionThreadingHooks.deferLightSectionStatus(pos, false),
                    "a chunk tick worker has to defer the light section status change");
            assertTrue(RegionThreadingHooks.deferLightCheck(pos), "a chunk tick worker has to defer the light recheck");
        });
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(chunk);
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(2 * RANDOM_TICK_SPEED, engineCalls.size(), "every write has to hand over its status change and its recheck");
        assertEquals(2 * RANDOM_TICK_SPEED, this.engine.diagnostics().deferredBlockUpdates().applied(RegionTickSlice.CHUNK_TICK));
        assertTrue(engineThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the light engine may only be driven from the server thread: " + engineThreads);
        final List<String> expected = new ArrayList<>();
        for (final BlockPos pos : written) {
            // The order of one write is the vanilla one: the section status change first, then the recheck.
            expected.add("status " + pos);
            expected.add("check " + pos);
        }
        assertEquals(expected, engineCalls, "the deferred effects have to be applied in recording order");
        assertNotNull(delegated);
    }

    @Test
    void deferredUpdateBudgetRefusesAChunkBeforeItsBody() {
        this.installEngine();
        this.engine.setChunkTickDeferredUpdateBudget(1);
        final ServerLevel level = mockLevel();
        doNothing().when(level).sendBlockUpdated(any(), any(), any(), anyInt());
        final List<String> firstThreads = new CopyOnWriteArrayList<>();
        final List<String> secondThreads = new CopyOnWriteArrayList<>();
        final LevelChunk first = tickedChunk(INTERIOR_CHUNK, pos -> firstThreads.add(Thread.currentThread().getName()));
        final LevelChunk second = tickedChunk(SECOND_INTERIOR_CHUNK, pos -> secondThreads.add(Thread.currentThread().getName()));
        final RegionChunkTickPlan plan = this.beginPlan(level);

        plan.accept(first);
        plan.accept(second);
        assertEquals(1, plan.collectedChunks().size(), "the plan has to refuse the chunk that does not fit the budget");
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1L, this.engine.diagnostics().deferredUpdateBudgetChunkTicks(),
                "the refused chunk has to be counted by its own reason");
        assertEquals(1L, this.engine.diagnostics().fallbackChunkTicks());
        assertEquals(RANDOM_TICK_SPEED, firstThreads.size());
        assertTrue(firstThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")),
                () -> "the admitted chunk has to run on a worker: " + firstThreads);
        assertEquals(RANDOM_TICK_SPEED, secondThreads.size(), "the refused chunk still has to tick exactly once");
        assertTrue(secondThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "a chunk refused before its body has to run on the server thread: " + secondThreads);
    }

    @Test
    void shutdownMidBatchDrainsTheDeferredUpdatesBeforeTheLevelCloses() throws InterruptedException {
        this.installEngine();
        final List<String> engineThreads = new CopyOnWriteArrayList<>();
        final List<String> engineCalls = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final LevelLightEngine real = realLightEngine(level, new HashMap<>());
        final ThreadedLevelLightEngine delegated = delegatingLightEngine(level, real, engineThreads, engineCalls);
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final LevelChunk chunk = tickedChunk(INTERIOR_CHUNK, pos -> {
            assertTrue(RegionThreadingHooks.deferLightCheck(pos), "the recheck has to be recorded");
            ticking.countDown();
            awaitOrFail(release, "the chunk tick was never released");
        });
        final RegionChunkTickPlan plan = this.beginPlan(level);
        plan.accept(chunk);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait(PARKING_BARRIER);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        }, "chunk-tick-shutdown-batch");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the chunk tick never started");

        final Thread shutdownThread = new Thread(this.engine::shutdown, "chunk-tick-shutdown");
        shutdownThread.start();
        Thread.sleep(200L);
        release.countDown();
        batchThread.join(10_000L);
        shutdownThread.join(10_000L);

        assertTrue(failures.isEmpty(), () -> "batch failed: " + failures);
        assertFalse(batchThread.isAlive(), "the chunk tick batch never completed");
        assertFalse(shutdownThread.isAlive(), "the engine shutdown never returned");
        assertEquals(RANDOM_TICK_SPEED, this.engine.diagnostics().deferredBlockUpdates().applied(RegionTickSlice.CHUNK_TICK),
                "the barrier has to drain the deferred queue before the level closes");
        assertEquals(0L, this.engine.diagnostics().deferredBlockUpdates().dropped(RegionTickSlice.CHUNK_TICK));
        verify(delegated, times(RANDOM_TICK_SPEED)).checkBlock(any());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void workerChunkTickProducesTheSameLightStateAsTheServerThreadTick() throws InterruptedException {
        final LightRun onWorker = this.runLightWrite(null);
        // The reference writes the positions the worker run selected, directly on the server thread, so the
        // comparison is deterministic: light depends on the resulting block states, not on the order or the
        // thread that produced them.
        final LightRun onServerThread = this.runLightWrite(onWorker.written());

        assertFalse(onWorker.written().isEmpty(), "the workload has to write at least one block");
        assertEquals(onServerThread.written(), onWorker.written(), "the reference has to write the same positions");
        assertEquals(onServerThread.lightArrays(), onWorker.lightArrays(),
                "a worker chunk tick has to produce exactly the light state of the server-thread tick");
        assertTrue(onWorker.lightChanged(), "the write has to change the light state, or the comparison proves nothing");
        assertEquals(onServerThread.before(), onWorker.before(), "both runs have to start from the same light state");
        assertFalse(onWorker.engineThreads().isEmpty(), "the worker run has to reach the light engine through its barrier");
        assertTrue(onWorker.applied() >= onWorker.written().size(), "every write of the worker run has to be applied");
        assertTrue(onWorker.writeThreads().stream().anyMatch(name -> name.startsWith("NeoForge Region Worker")),
                () -> "the worker run has to tick its chunk body on a region worker: " + onWorker.writeThreads());
        assertTrue(onServerThread.writeThreads().stream().allMatch(name -> name.equals(onServerThread.serverThread())),
                () -> "the reference has to write on the server thread: " + onServerThread.writeThreads());
        assertTrue(onWorker.engineThreads().stream().allMatch(name -> name.equals(onWorker.serverThread())),
                () -> "the worker run may only drive the light engine from its batch barrier: " + onWorker.engineThreads());
        assertTrue(onServerThread.engineThreads().stream().allMatch(name -> name.equals(onServerThread.serverThread())),
                () -> "the server-thread run drives the light engine inline: " + onServerThread.engineThreads());
        assertEquals(0L, onWorker.dropped());
        assertTrue(onWorker.applied() > 0L, "the worker run has to apply its deferred light work");
        assertEquals(0L, onServerThread.dropped(), "the server-thread run must not drop deferred work");
    }

    /**
     * Runs one chunk tick that writes a light-emitting block, on a worker or on the server thread, and returns the
     * light state of the region before and after it.
     *
     * @param referencePositions {@code null} to tick the chunk on a region worker, or the positions to write
     *                           directly on the server thread as the light reference
     */
    private LightRun runLightWrite(@Nullable List<BlockPos> referencePositions) throws InterruptedException {
        final boolean onWorker = referencePositions == null;
        final String serverThread = Thread.currentThread().getName();
        this.installEngine();
        final List<String> engineThreads = new CopyOnWriteArrayList<>();
        final List<String> engineCalls = new CopyOnWriteArrayList<>();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        final List<ChunkPos> regionChunks = new ArrayList<>();
        final ServerLevel level = mockLevel();
        final ServerChunkCache chunkSource = level.getChunkSource();
        final LevelLightEngine real = realLightEngine(level, realChunks);
        delegatingLightEngine(level, real, engineThreads, engineCalls);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                final ChunkPos pos = new ChunkPos(INTERIOR_CHUNK.x + offsetX, INTERIOR_CHUNK.z + offsetZ);
                regionChunks.add(pos);
                realChunks.put(pos.toLong(), realChunk(level, pos));
            }
        }
        serveChunks(chunkSource, realChunks);
        // The light storage has to hold a data layer for the written section, exactly like a light-initialized
        // chunk; the chunk itself is still all air, so the write below also changes the section emptiness.
        for (final ChunkPos pos : regionChunks) {
            real.updateSectionStatus(SectionPos.of(pos, SECTION_Y), false);
        }
        final Map<Long, String> before = lightArrays(real, regionChunks);
        final List<BlockPos> written = new CopyOnWriteArrayList<>();
        final List<String> writeThreads = new CopyOnWriteArrayList<>();
        if (onWorker) {
            final LevelChunk ticked = tickedChunk(INTERIOR_CHUNK, pos -> {
                written.add(pos);
                writeThreads.add(Thread.currentThread().getName());
                level.setBlockAndUpdate(pos, Blocks.GLOWSTONE.defaultBlockState());
            });
            final RegionChunkTickPlan plan = this.beginPlan(level);
            plan.accept(ticked);
            plan.executeAndAwait(PARKING_BARRIER);
        } else {
            for (final BlockPos pos : referencePositions) {
                written.add(pos);
                writeThreads.add(Thread.currentThread().getName());
                level.setBlockAndUpdate(pos, Blocks.GLOWSTONE.defaultBlockState());
            }
        }
        real.runLightUpdates();
        final Map<Long, String> after = lightArrays(real, regionChunks);
        final long applied = this.engine.diagnostics().deferredBlockUpdates().applied(RegionTickSlice.CHUNK_TICK);
        final long dropped = this.engine.diagnostics().deferredBlockUpdates().dropped(RegionTickSlice.CHUNK_TICK);
        RegionThreadingManager.clear();
        return new LightRun(List.copyOf(written), List.copyOf(writeThreads), before, after, List.copyOf(engineThreads),
                List.copyOf(engineCalls), serverThread, applied, dropped, !before.equals(after));
    }

    /**
     * {@return the block light arrays of {@code chunks}, one entry per light section}
     * <p>
     * The values are the {@link DataLayer} data the level light engine holds, so two runs that end in the same light
     * state compare equal, and a run whose rechecks never ran differs from one that lit the written positions.
     */
    private static Map<Long, String> lightArrays(LevelLightEngine engine, Collection<ChunkPos> chunks) {
        final Map<Long, String> arrays = new LinkedHashMap<>();
        for (final ChunkPos chunk : chunks) {
            for (int sectionY = engine.getMinLightSection(); sectionY < engine.getMaxLightSection(); sectionY++) {
                final SectionPos section = SectionPos.of(chunk, sectionY);
                final DataLayer layer = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section);
                arrays.put(section.asLong(), layer == null ? "none" : HexFormat.of().formatHex(layer.getData()));
            }
        }
        return arrays;
    }

    private record LightRun(List<BlockPos> written, List<String> writeThreads, Map<Long, String> before,
            Map<Long, String> lightArrays,
            List<String> engineThreads, List<String> engineCalls, String serverThread, long applied, long dropped,
            boolean lightChanged) {}

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, true,
                RegionLayout.of(RegionPolicy.FIXED_3X3));
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionChunkTickPlan beginPlan(ServerLevel level) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        return plan;
    }

    /**
     * {@return a level that runs the real vanilla write path and serves its chunks from the test}
     * <p>
     * The mock runs the real methods, so the write, the block update publication and the neighbour updates are the
     * production ones. The fields the vanilla constructor would have filled are installed explicitly, because a
     * mock never runs it.
     */
    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(20L).when(level).getGameTime();
        when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld")));
        doReturn(3).when(level).entitySectionRegionChunkSize();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
        doReturn(MIN_BUILD_HEIGHT).when(level).getMinBuildHeight();
        doReturn(LEVEL_HEIGHT).when(level).getHeight();
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickLightning(any());
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(RANDOM_TICK_SPEED);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        @SuppressWarnings("unchecked")
        final Registry<Biome> biomes = mock(Registry.class);
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
        doReturn(registryAccess).when(level).registryAccess();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        // The vanilla constructor fills these fields; a mock does not run it, and the write path and the block
        // update publication of the server thread read them.
        setLevelField(level, "neighborUpdater", new RegionAwareNeighborUpdater(level, 512));
        setLevelField(level, "navigatingMobs", new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>());
        setLevelField(level, "pathTypesByPosCache", new PathTypeCache());
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    /** Makes {@code chunkSource} answer the block reads and writes of the chunk body from {@code chunks}. */
    private static void serveChunks(ServerChunkCache chunkSource, Map<Long, LevelChunk> chunks) {
        doAnswer(invocation -> chunks.get(ChunkPos.asLong(invocation.getArgument(0), invocation.getArgument(1))))
                .when(chunkSource).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> chunks.get(ChunkPos.asLong(invocation.getArgument(0), invocation.getArgument(1))))
                .when(chunkSource).getChunkNow(anyInt(), anyInt());
    }

    /**
     * Installs a delegating {@link ThreadedLevelLightEngine} on the chunk source of {@code level}.
     * <p>
     * The chunk source answers with a {@code ThreadedLevelLightEngine}, so the test cannot hand out the plain
     * engine directly. The mock records the thread and the order of every call and forwards to the real engine,
     * which is what computes the light arrays the assertions compare.
     */
    private static ThreadedLevelLightEngine delegatingLightEngine(ServerLevel level, LevelLightEngine real, List<String> threads,
            List<String> calls) {
        final ThreadedLevelLightEngine engine = mock(ThreadedLevelLightEngine.class);
        doAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            calls.add("check " + invocation.getArgument(0));
            real.checkBlock(invocation.getArgument(0));
            return null;
        }).when(engine).checkBlock(any());
        doAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            calls.add("status " + invocation.getArgument(0));
            real.updateSectionStatus(invocation.getArgument(0, BlockPos.class), invocation.getArgument(1, Boolean.class));
            return null;
        }).when(engine).updateSectionStatus(any(BlockPos.class), anyBoolean());
        final ServerChunkCache chunkSource = level.getChunkSource();
        doReturn(engine).when(chunkSource).getLightEngine();
        return engine;
    }

    /** {@return a light engine over the real chunks of {@code chunks} that computes block light only} */
    private static LevelLightEngine realLightEngine(ServerLevel level, Map<Long, LevelChunk> chunks) {
        return new LevelLightEngine(new LightChunkGetter() {
            @Override
            public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
                return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
            }

            @Override
            public net.minecraft.world.level.BlockGetter getLevel() {
                return level;
            }
        }, true, false);
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
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
        return chunk;
    }

    /**
     * {@return the chunk the chunk tick iterates}
     * <p>
     * It reports one randomly ticking section and hands every selected position to {@code onRandomTick}, which
     * performs the real write on the real chunk of that position.
     */
    private static LevelChunk tickedChunk(ChunkPos pos, java.util.function.Consumer<BlockPos> onRandomTick) {
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(false);
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            onRandomTick.accept(invocation.getArgument(1));
            return null;
        }).when(blockState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));
        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(SECTION_Y);
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
