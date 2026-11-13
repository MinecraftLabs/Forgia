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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the deferred block-write queue every region batch shares.
 * <p>
 * A block write reaches two level-wide structures: the light engine of the level and the block update
 * publication of the level, which invalidates the level-wide path type cache and recomputes the paths of every
 * navigating mob. No worker of any slice may drive either of them, so the batch base installs the queue for
 * every group, the worker records the effects of its writes and the server thread applies them at the barrier
 * of the batch that recorded them.
 * <p>
 * The tests drive the production slices - a block-entity ticker that performs the write a furnace {@code setLit}
 * performs, a chunk tick and a self-driven entity tick - against a real engine, a real {@link LevelChunk} and a
 * real {@link LevelLightEngine}, so the deferred path is the one that runs. Only the level, its chunk source, the
 * ticked chunk and the entities are mocks.
 */
@Timeout(60)
class RegionDeferredBlockUpdatesTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int RANDOM_TICK_SPEED = 3;
    private static final ChunkPos FURNACE_CHUNK = new ChunkPos(1, 1);
    /** Interior chunk of the fixed 3x3 layout: all eight neighbors belong to the same region. */
    private static final ChunkPos RANDOM_TICK_CHUNK = new ChunkPos(1, 4);
    private static final ChunkPos ENTITY_CHUNK = new ChunkPos(1, 3);
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;
    /** Section the ticked chunk reports, so every position the tick selects lands in it. */
    private static final int SECTION_Y = 4;
    /** The state a furnace {@code setLit} writes: {@code setBlock(pos, state, 3)} of the lit furnace. */
    private static final BlockState LIT_FURNACE = Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.LIT, true);

    /** The entity tick list of the level, as the self-driven seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    private RegionThreadingEngine engine;
    /** The chunk source of the level {@link #mockLevel()} built, so a test can stub it while it is not stubbing. */
    private ServerChunkCache chunkSource;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void furnaceTickerDefersItsBlockUpdateToTheBarrier() {
        this.installBlockEntityEngine();
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        final LevelChunk chunk = realChunk(level, FURNACE_CHUNK);
        realChunks.put(FURNACE_CHUNK.toLong(), chunk);
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), events);
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            events.add("block " + invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos pos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 4);
        final List<String> writeThreads = new CopyOnWriteArrayList<>();
        final TickingBlockEntity furnace = ticker(pos, writeThreads, level);

        final RegionBlockEntityTickPlan plan = this.beginBlockEntityPlan(level);
        assertTrue(plan.accept(furnace), "the furnace ticker has to be admitted to the batch");
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(writeThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")),
                () -> "the furnace has to tick on a region worker: " + writeThreads);
        assertEquals(List.of(pos), published, "the write has to be published exactly once");
        assertTrue(publishThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the publication may only run on the server thread: " + publishThreads);
        // The vanilla order of one write: the section status change, the light recheck and the publication.
        assertEquals(List.of("status " + pos, "check " + pos, "block " + pos), events,
                "the barrier has to apply the effects of the write in recording order");
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        assertEquals(3L, counters.applied(RegionTickSlice.BLOCK_ENTITY_TICK),
                "the status change, the recheck and the publication of the write have to be applied");
        assertEquals(0L, counters.dropped(RegionTickSlice.BLOCK_ENTITY_TICK));
        assertEquals(0L, counters.refused(RegionTickSlice.BLOCK_ENTITY_TICK));
    }

    @Test
    void blockEntityAndChunkTickBatchesBothDeferAndTheBarrierAppliesThemInTickOrder() {
        this.installBlockEntityAndChunkTickEngine();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        realChunks.put(FURNACE_CHUNK.toLong(), realChunk(level, FURNACE_CHUNK));
        realChunks.put(RANDOM_TICK_CHUNK.toLong(), realChunk(level, RANDOM_TICK_CHUNK));
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos furnacePos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 4);
        final TickingBlockEntity furnace = ticker(furnacePos, new CopyOnWriteArrayList<>(), level);
        final List<BlockPos> randomTickPositions = new CopyOnWriteArrayList<>();
        final LevelChunk tickedChunk = tickedChunk(RANDOM_TICK_CHUNK, pos -> {
            randomTickPositions.add(pos);
            level.setBlockAndUpdate(pos, Blocks.GLOWSTONE.defaultBlockState());
        });

        // The level ticks the block entities before its chunks, so the two batches of this tick run in that
        // order and each barrier applies the effects of its own workers.
        final RegionBlockEntityTickPlan blockEntityPlan = this.beginBlockEntityPlan(level);
        assertTrue(blockEntityPlan.accept(furnace), "the furnace ticker has to be admitted to the batch");
        blockEntityPlan.executeAndAwait(PARKING_BARRIER);
        final RegionChunkTickPlan chunkPlan = this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER);
        assertNotNull(chunkPlan, "chunk tick worker execution must be enabled");
        chunkPlan.accept(tickedChunk);
        chunkPlan.executeAndAwait(PARKING_BARRIER);

        assertEquals(RANDOM_TICK_SPEED, randomTickPositions.size(), "every selected random tick has to run once");
        final List<BlockPos> expected = new ArrayList<>();
        expected.add(furnacePos);
        expected.addAll(randomTickPositions);
        assertEquals(expected, published, "both batches have to publish their writes, in tick order");
        assertTrue(publishThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the publication may only run on the server thread: " + publishThreads);
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        assertEquals(3L, counters.applied(RegionTickSlice.BLOCK_ENTITY_TICK));
        // Every write publishes its block update; a write that changes the light properties of the position adds
        // the recheck, and a write that changes the emptiness of its section adds the status change. The selected
        // positions are random, so the exact count of records is not fixed, but every record is applied.
        final long chunkTickRecords = counters.applied(RegionTickSlice.CHUNK_TICK);
        assertTrue(chunkTickRecords >= RANDOM_TICK_SPEED && chunkTickRecords <= 3L * RANDOM_TICK_SPEED,
                () -> "every random tick write has to defer its effects: " + chunkTickRecords);
        assertEquals(0L, counters.refused(RegionTickSlice.CHUNK_TICK));
        assertEquals(0L, counters.dropped(RegionTickSlice.BLOCK_ENTITY_TICK) + counters.dropped(RegionTickSlice.CHUNK_TICK));
    }

    @Test
    void tickerBeyondTheReservationIsRefusedBeforeItsBody() {
        this.installBlockEntityEngine();
        // One ticker fits into the bound of the batch, so the second one cannot reserve its writes.
        this.engine.setDeferredBlockUpdateBudget(RegionTickSlice.BLOCK_ENTITY_TICK.reservationPerUnit());
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        realChunks.put(FURNACE_CHUNK.toLong(), realChunk(level, FURNACE_CHUNK));
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos firstPos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 4);
        final BlockPos secondPos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 8, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 8);
        final List<String> firstThreads = new CopyOnWriteArrayList<>();
        final List<String> secondThreads = new CopyOnWriteArrayList<>();
        final TickingBlockEntity first = ticker(firstPos, firstThreads, level);
        final TickingBlockEntity second = ticker(secondPos, secondThreads, level);

        final RegionBlockEntityTickPlan plan = this.beginBlockEntityPlan(level);
        assertTrue(plan.accept(first), "the first ticker has to be admitted");
        assertTrue(plan.accept(second), "the second ticker has to be admitted");
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, firstThreads.size(), "the admitted ticker has to tick exactly once");
        assertTrue(firstThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")),
                () -> "the admitted ticker has to tick on a region worker: " + firstThreads);
        assertEquals(1, secondThreads.size(), "the refused ticker still has to tick exactly once");
        assertTrue(secondThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "a ticker refused before its body has to tick on the server thread: " + secondThreads);
        // The admitted ticker defers its publication to the barrier, which applies it before the server-thread
        // fallback ticks; the refused ticker then publishes its own write inline, exactly like any server-thread
        // write.
        assertEquals(List.of(firstPos, secondPos), published,
                "the barrier has to apply the deferred write before the refused ticker publishes inline");
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        assertEquals(1L, counters.refused(RegionTickSlice.BLOCK_ENTITY_TICK), "the refused ticker has to be counted");
        assertEquals(3L, counters.applied(RegionTickSlice.BLOCK_ENTITY_TICK), "the admitted ticker keeps its records");
        assertEquals(0L, counters.dropped(RegionTickSlice.BLOCK_ENTITY_TICK));
    }

    @Test
    void shutdownMidBatchDrainsTheQueueBeforeTheLevelCloses() throws InterruptedException {
        this.installBlockEntityEngine();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        realChunks.put(FURNACE_CHUNK.toLong(), realChunk(level, FURNACE_CHUNK));
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos pos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 4);
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TickingBlockEntity furnace = ticker(pos, new CopyOnWriteArrayList<>(), level);
        doAnswer(invocation -> {
            level.setBlockAndUpdate(pos, LIT_FURNACE);
            ticking.countDown();
            awaitOrFail(release, "the furnace tick was never released");
            return null;
        }).when(furnace).tick();
        final RegionBlockEntityTickPlan plan = this.beginBlockEntityPlan(level);
        assertTrue(plan.accept(furnace), "the furnace ticker has to be admitted to the batch");
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final Thread batchThread = new Thread(() -> {
            try {
                plan.executeAndAwait(PARKING_BARRIER);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        }, "deferred-block-update-shutdown-batch");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the furnace tick never started");

        final Thread shutdownThread = new Thread(this.engine::shutdown, "deferred-block-update-shutdown");
        shutdownThread.start();
        Thread.sleep(200L);
        release.countDown();
        batchThread.join(10_000L);
        shutdownThread.join(10_000L);

        assertTrue(failures.isEmpty(), () -> "batch failed: " + failures);
        assertFalse(batchThread.isAlive(), "the block-entity batch never completed");
        assertFalse(shutdownThread.isAlive(), "the engine shutdown never returned");
        assertEquals(List.of(pos), published, "the barrier has to apply the queued write before the level closes");
        assertTrue(publishThreads.stream().allMatch(name -> name.equals(batchThread.getName())),
                () -> "the publication may only run on the thread that owns the batch barrier: " + publishThreads);
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        assertEquals(3L, counters.applied(RegionTickSlice.BLOCK_ENTITY_TICK),
                "the barrier has to drain the queue before the level closes");
        assertEquals(0L, counters.dropped(RegionTickSlice.BLOCK_ENTITY_TICK));
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void serverThreadFurnaceTickPublishesInline() {
        this.installBlockEntityEngine();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        realChunks.put(FURNACE_CHUNK.toLong(), realChunk(level, FURNACE_CHUNK));
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos pos = new BlockPos(FURNACE_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, FURNACE_CHUNK.getMinBlockZ() + 4);

        // Outside a region group the seam is inert, so the vanilla write publishes its own block update.
        assertFalse(RegionThreadingHooks.deferBlockUpdate(pos, Blocks.AIR.defaultBlockState(), LIT_FURNACE, 3),
                "the server thread publishes a block update inline");
        assertFalse(RegionThreadingHooks.deferLightCheck(pos), "the server thread rechecks light inline");
        assertFalse(RegionThreadingHooks.deferLightSectionStatus(pos, false),
                "the server thread updates the light section status inline");
        level.setBlockAndUpdate(pos, LIT_FURNACE);

        assertEquals(List.of(pos), published, "the server-thread write has to publish inline");
        assertEquals(List.of(Thread.currentThread().getName()), publishThreads);
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        for (final RegionTickSlice slice : RegionTickSlice.values()) {
            assertEquals(0L, counters.applied(slice), "no slice may record the inline write of the server thread: " + slice);
            assertEquals(0L, counters.dropped(slice));
            assertEquals(0L, counters.refused(slice));
        }
    }

    @Test
    void entityTickDefersItsBlockUpdateToTheBarrier() {
        this.installEntityTickEngine();
        final List<BlockPos> published = new CopyOnWriteArrayList<>();
        final List<String> publishThreads = new CopyOnWriteArrayList<>();
        final ServerLevel level = mockLevel();
        final Map<Long, LevelChunk> realChunks = new HashMap<>();
        realChunks.put(ENTITY_CHUNK.toLong(), realChunk(level, ENTITY_CHUNK));
        serveChunks(level, realChunks);
        delegatingLightEngine(level, realLightEngine(level, realChunks), new CopyOnWriteArrayList<>());
        doAnswer(invocation -> {
            publishThreads.add(Thread.currentThread().getName());
            published.add(invocation.getArgument(0));
            return null;
        }).when(this.chunkSource).blockChanged(any());
        final BlockPos pos = new BlockPos(ENTITY_CHUNK.getMinBlockX() + 4, MIN_BUILD_HEIGHT + 65, ENTITY_CHUNK.getMinBlockZ() + 4);
        this.engine.addRegionSection(level, ENTITY_CHUNK.x, ENTITY_CHUNK.z);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final Entity entity = entity(level, pos);
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            level.setBlockAndUpdate(pos, LIT_FURNACE);
            return null;
        }).when(entity).tick();
        this.tickList.add(entity);

        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, candidate -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        assertTrue(plan.ticksEntitiesOnRegionWorkers(), "the entity tick must be driven by the regions");
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1, tickThreads.size(), "the entity has to tick exactly once");
        assertTrue(tickThreads.get(0).startsWith("NeoForge Region Worker"),
                () -> "the entity has to tick on its region worker: " + tickThreads);
        assertEquals(List.of(pos), published, "the write of the entity tick has to be published once");
        assertTrue(publishThreads.stream().allMatch(name -> name.equals(Thread.currentThread().getName())),
                () -> "the publication may only run on the server thread: " + publishThreads);
        final RegionThreadingDiagnostics.BlockUpdateCounters counters = this.engine.diagnostics().deferredBlockUpdates();
        assertEquals(3L, counters.applied(RegionTickSlice.ENTITY_TICK),
                "the status change, the recheck and the publication of the write have to be applied");
        assertEquals(0L, counters.dropped(RegionTickSlice.ENTITY_TICK));
        assertEquals(0L, counters.refused(RegionTickSlice.ENTITY_TICK));
    }

    private void installBlockEntityEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, true, false, false,
                RegionLayout.of(RegionPolicy.FIXED_3X3));
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private void installBlockEntityAndChunkTickEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, true, false, true,
                RegionLayout.of(RegionPolicy.FIXED_3X3));
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private void installEntityTickEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionBlockEntityTickPlan beginBlockEntityPlan(ServerLevel level) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(plan, "block entity tick worker execution must be enabled");
        return plan;
    }

    /**
     * {@return a level that runs the real vanilla write path and serves its chunks from the test}
     * <p>
     * The mock runs the real methods, so the write, the block update publication and the neighbour updates are
     * the production ones. The fields the vanilla constructor would have filled are installed explicitly,
     * because a mock never runs it.
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
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doReturn(true).when(level).isInEntityTickingRange(anyLong());
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        this.chunkSource = chunkSource;
        // The block-entity plan keeps the level's ticker lists; the batch never walks them here, but the
        // vanilla finalization of the level tick reads them.
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
     * Makes {@code chunkSource} answer the block reads and writes of the chunk bodies from {@code chunks}.
     * <p>
     * A position outside the map is reported as a loaded but empty chunk: the entity activation asks the chunk
     * source whether the neighborhood of a chunk is loaded, and a {@code null} answer would hand the entity to
     * the server-thread fallback instead of the worker under test.
     */
    private static void serveChunks(ServerLevel level, Map<Long, LevelChunk> chunks) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        // A neighbor update of a write reads the chunks around the written position, so every requested chunk is
        // materialized: a chunk the source does not know would make the level fail its own chunk lookup.
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
        }).when(chunkSource).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
        }).when(chunkSource).getChunkNow(anyInt(), anyInt());
    }

    /**
     * Installs a delegating {@link ThreadedLevelLightEngine} on the chunk source of {@code level}.
     * <p>
     * The chunk source answers with a {@code ThreadedLevelLightEngine}, so the test cannot hand out the plain
     * engine directly. The mock records the order of every call in {@code events} and forwards to the real
     * engine, which is what computes the light the deferred path has to produce.
     */
    private static ThreadedLevelLightEngine delegatingLightEngine(ServerLevel level, LevelLightEngine real, List<String> events) {
        final ThreadedLevelLightEngine engine = mock(ThreadedLevelLightEngine.class);
        doAnswer(invocation -> {
            events.add("check " + invocation.getArgument(0));
            real.checkBlock(invocation.getArgument(0));
            return null;
        }).when(engine).checkBlock(any());
        doAnswer(invocation -> {
            events.add("status " + invocation.getArgument(0));
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
        // A chunk the plan hands to a worker has to be loaded: the snapshot of its tickers is revalidated on the
        // worker, and an unloaded chunk is handed back to the server thread.
        chunk.setLoaded(true);
        return chunk;
    }

    /**
     * {@return the chunk a chunk tick iterates}
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

    /** {@return a block-entity ticker that performs the write a furnace {@code setLit} performs} */
    private static TickingBlockEntity ticker(BlockPos pos, List<String> tickThreads, ServerLevel level) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn("minecraft:furnace");
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            level.setBlockAndUpdate(pos, LIT_FURNACE);
            return null;
        }).when(ticker).tick();
        return ticker;
    }

    private Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
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
