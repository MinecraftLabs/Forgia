/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the block event phase as a quiescent server-thread scope.
 * <p>
 * The phase drains the level's ordered block event set after every region batch of the tick returned, so no worker
 * runs and no region is leased by another thread. The scope therefore takes the lease of each region its events run
 * in once and keeps it until the phase ends, and it captures no border update: a write of an event body to another
 * region is the vanilla inline call, which is also why a piston that pushes across a border queues the same single
 * block event the inline run queues.
 * <p>
 * The level is a mock that runs the real vanilla methods, so the write, the neighbour propagation and the piston
 * body are the production ones; only the level, its chunk source, its light engine and the entity storage are mocks.
 */
@Timeout(120)
class RegionBlockEventPhaseTest {
    private static final RegionPolicy FIXED = RegionPolicy.FIXED_3X3;
    private static final int Y = 64;
    /** A position in the middle of region (0,0), i.e. of the chunks x 0..2 and z 0..2. */
    private static final BlockPos REGION_ZERO_POS = new BlockPos(8, Y, 8);
    /** A position in the middle of region (1,0), i.e. of the chunks x 3..5 and z 0..2. */
    private static final BlockPos REGION_ONE_POS = new BlockPos(56, Y, 8);
    /** A position in the middle of region (2,0), i.e. of the chunks x 6..8 and z 0..2. */
    private static final BlockPos REGION_TWO_POS = new BlockPos(104, Y, 8);
    /**
     * The first block of region (1,0). A sticky piston stands here and pushes west into region (0,0), so the head
     * and the whole column of the move are deferred writes if the phase captures border updates.
     */
    private static final BlockPos PISTON_POS = new BlockPos(48, Y, 8);
    /** The redstone block that powers the piston from the side it does not push into. */
    private static final BlockPos CLOCK_POS = new BlockPos(49, Y, 8);
    private static final int COLUMN_SIZE = 12;
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

    /**
     * A phase that runs events of three regions takes each region's lease once, releases all of them when the phase
     * ends, and runs no border fixpoint.
     */
    @Test
    void leasesEachRegionOncePerPhaseAndReleasesItAtTheEnd() {
        final ServerLevel level = this.mockLevel();
        this.serveChunks(level, new HashMap<>());
        this.installEngine(level);
        final RegionThreadingDiagnostics.BlockEventPhaseCounters before = this.phaseCounters();

        final List<String> bodies = new ArrayList<>();
        assertTrue(RegionThreadingHooks.beginBlockEventPhase(level), "the phase has to open as a quiescent scope");
        try {
            this.runEvent(level, REGION_ZERO_POS, () -> bodies.add("zero-a"));
            this.runEvent(level, REGION_ONE_POS, () -> bodies.add("one"));
            this.runEvent(level, REGION_ZERO_POS, () -> bodies.add("zero-b"));
            this.runEvent(level, REGION_TWO_POS, () -> bodies.add("two"));
        } finally {
            RegionThreadingHooks.endBlockEventPhase(level);
        }

        assertEquals(List.of("zero-a", "one", "zero-b", "two"), bodies, "every event of the phase ran once");
        final RegionThreadingDiagnostics.BlockEventPhaseCounters after = this.phaseCounters();
        assertEquals(before.phases() + 1, after.phases(), "one phase ran as a scope");
        assertEquals(before.events() + 4, after.events(), "the scope ran every event of the phase");
        assertEquals(before.leases() + 3, after.leases(), "three regions, so three leases and not one per event");
        assertEquals(before.releasedLeases() + 3, after.releasedLeases(), "the phase releases every lease it took");
        assertEquals(before.fixpoints(), after.fixpoints(), "a quiescent phase needs no border fixpoint");
        assertEquals(before.leaseFallbacks(), after.leaseFallbacks(), "every region of the phase was leasable");
        assertEquals(0, this.engine.diagnostics().runningRegionCount(), "no region stays leased after the phase");
        assertEquals(0L, this.engine.diagnostics().borderUpdates().recorded(),
                "the scope captures no border update, so the fixpoint has nothing to flush");
    }

    /**
     * A phase that already leased a region takes it again from the pool, and a region released by one phase is
     * leasable by the next one.
     */
    @Test
    void reusesAPooledLeaseAndReleasesItForTheNextPhase() {
        final ServerLevel level = this.mockLevel();
        this.serveChunks(level, new HashMap<>());
        this.installEngine(level);

        assertTrue(RegionThreadingHooks.beginBlockEventPhase(level), "the first phase opens as a scope");
        try {
            this.runEvent(level, REGION_ZERO_POS, () -> {});
        } finally {
            RegionThreadingHooks.endBlockEventPhase(level);
        }
        final RegionThreadingDiagnostics.BlockEventPhaseCounters first = this.phaseCounters();
        assertEquals(1L, first.leases() - 0L, "the first phase leases its one region: " + first);
        assertEquals(first.leases(), first.releasedLeases(), "the first phase released what it leased: " + first);

        assertTrue(RegionThreadingHooks.beginBlockEventPhase(level), "the second phase opens as a scope again");
        try {
            this.runEvent(level, REGION_ZERO_POS, () -> {});
        } finally {
            RegionThreadingHooks.endBlockEventPhase(level);
        }
        final RegionThreadingDiagnostics.BlockEventPhaseCounters second = this.phaseCounters();
        assertEquals(first.leases() + 1, second.leases(), "the region is leasable again after the release: " + second);
        assertEquals(second.leases(), second.releasedLeases(), "both phases released every lease: " + second);
    }

    /** A phase whose event throws releases every lease it took, so no region stays leased by a failed phase. */
    @Test
    void releasesEveryLeaseWhenAnEventThrows() {
        final ServerLevel level = this.mockLevel();
        this.serveChunks(level, new HashMap<>());
        this.installEngine(level);
        final RegionThreadingDiagnostics.BlockEventPhaseCounters before = this.phaseCounters();
        final AtomicReference<RegionThreadingDiagnostics.BlockEventPhaseCounters> failed = new AtomicReference<>();

        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            RegionThreadingHooks.beginBlockEventPhase(level);
            try {
                this.runEvent(level, REGION_ZERO_POS, () -> {});
                this.runEvent(level, REGION_ONE_POS, () -> {
                    throw new IllegalStateException("event body failed");
                });
            } finally {
                RegionThreadingHooks.endBlockEventPhase(level);
                failed.set(this.phaseCounters());
            }
        });

        assertEquals("event body failed", failure.getMessage());
        final RegionThreadingDiagnostics.BlockEventPhaseCounters after = failed.get();
        assertEquals(before.leases() + 2, after.leases(), "the failed phase leased both regions before it threw");
        assertEquals(after.leases(), after.releasedLeases(), "the failed phase released every lease: " + after);
        assertEquals(0, this.engine.diagnostics().runningRegionCount(), "no region stays leased after the failure");
    }

    /**
     * A write of an event body to a position of another region is applied inline by the server thread: the body
     * reads back what it wrote, no border update is captured and no fixpoint is needed.
     */
    @Test
    void appliesACrossRegionWriteInlineInsteadOfCapturingIt() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        this.serveChunks(level, chunks);
        this.installEngine(level);
        final AtomicReference<BlockState> readBack = new AtomicReference<>();

        RegionThreadingHooks.beginBlockEventPhase(level);
        try {
            this.runEvent(level, REGION_ZERO_POS, () -> {
                assertTrue(level.setBlock(REGION_ONE_POS, Blocks.STONE.defaultBlockState(), 3),
                        "the write of the event body is accepted");
                readBack.set(level.getBlockState(REGION_ONE_POS));
            });
        } finally {
            RegionThreadingHooks.endBlockEventPhase(level);
        }

        assertEquals(Blocks.STONE.defaultBlockState(), readBack.get(),
                "the body reads back what it wrote instead of the state before the write");
        assertTrue(chunks.get(ChunkPos.asLong(3, 0)).getBlockState(REGION_ONE_POS).is(Blocks.STONE),
                "the cross-region write reached the target chunk inside the phase");
        final RegionThreadingDiagnostics.BorderUpdateCounters border = this.engine.diagnostics().borderUpdates();
        assertEquals(0L, border.recorded(), "the quiescent phase captures no border update: " + border);
        assertEquals(0L, border.blockWritesRecorded(), "the write was not deferred: " + border);
        assertEquals(0L, border.passes(), "no fixpoint pass was needed: " + border);
        assertEquals(0L, border.readAfterBlockWrite(), "there is no deferred write to read back: " + border);
        assertEquals(0L, this.phaseCounters().fixpoints(), "the phase ran no fixpoint");
    }

    /**
     * A sticky piston that pushes a column across a region border queues exactly one block event per edge, the same
     * one the inline run queues, and reaches the same block and block-entity states.
     * <p>
     * Inline, the second neighbour notification of the move reaches the piston after the head position already holds
     * the moving piston, so the structure resolver fails and no second event is queued. A phase that captured the
     * head write would resolve the structure against the block that was there before the move, succeed and queue a
     * second extend event for the same piston in the same tick.
     */
    @Test
    void pistonExtendingAcrossTheBorderQueuesOneBlockEventLikeTheInlineRun() {
        final PistonRun inline = this.runPistonClock(false);
        final PistonRun regionized = this.runPistonClock(true);

        assertEquals(inline.queuedDuringPhase(), regionized.queuedDuringPhase(),
                "the regionized phase queues the same block events as the inline run: inline=" + inline
                        + " regionized=" + regionized);
        assertEquals(0, regionized.queuedDuringPhase(), "the extend event queues no second event: " + regionized);
        assertEquals(inline.states(), regionized.states(),
                "the moved states are identical: inline=" + inline.states() + " regionized=" + regionized.states());
        assertEquals(inline.blockEntities(), regionized.blockEntities(),
                "the moving block entities are identical: inline=" + inline.blockEntities()
                        + " regionized=" + regionized.blockEntities());
        assertEquals(0L, regionized.borderUpdatesRecorded(),
                "the phase captured no border update for the move: " + regionized);
    }

    /** The result of one piston run: the events the phase queued and the states the move left behind. */
    private record PistonRun(List<String> events, int queuedDuringPhase, String states, String blockEntities,
            long borderUpdatesRecorded) {}

    /**
     * Builds the piston layout, extends the piston once through the block event phase and reports what the phase
     * queued and what the move left behind.
     *
     * @param regionThreading whether the engine is installed and the phase runs as a scope
     */
    private PistonRun runPistonClock(boolean regionThreading) {
        RegionThreadingManager.clear();
        this.engine = null;
        final ServerLevel level = this.mockLevel();
        this.serveChunks(level, new HashMap<>());
        if (regionThreading) {
            this.installEngine(level);
        }
        final List<String> queued = new ArrayList<>();
        doAnswer(invocation -> {
            queued.add(invocation.getArgument(0) + ":" + invocation.getArgument(2) + ":" + invocation.getArgument(3));
            return invocation.callRealMethod();
        }).when(level).blockEvent(any(BlockPos.class), any(Block.class), anyInt(), anyInt());

        level.setBlock(PISTON_POS, Blocks.STICKY_PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.WEST)
                .setValue(BlockStateProperties.EXTENDED, false), 3);
        for (int index = 1; index <= COLUMN_SIZE; index++) {
            level.setBlock(PISTON_POS.west(index), Blocks.STONE.defaultBlockState(), 3);
        }
        queued.clear();
        // The clock write powers the piston from the east, so the piston's own neighbour callback queues the extend
        // event on the server thread, before the phase runs.
        level.setBlock(CLOCK_POS, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        final List<String> events = List.copyOf(queued);
        queued.clear();

        this.runPhase(level, regionThreading);
        final int queuedDuringPhase = queued.size();
        final long borderUpdates = regionThreading ? this.engine.diagnostics().borderUpdates().recorded() : 0L;
        return new PistonRun(events, queuedDuringPhase, this.describeStates(level), this.describeBlockEntities(level),
                borderUpdates);
    }

    /** Drains the level's block event set the way the vanilla phase does, inside the quiescent scope when asked. */
    private void runPhase(ServerLevel level, boolean regionThreading) {
        if (regionThreading) {
            RegionThreadingHooks.beginBlockEventPhase(level);
        }
        try {
            final it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData> queue = this.blockEvents(level);
            while (!queue.isEmpty()) {
                final net.minecraft.world.level.BlockEventData event = queue.removeFirst();
                final BlockState state = level.getBlockState(event.pos());
                if (!state.is(event.block())) {
                    continue;
                }
                final Runnable body = () -> state.triggerEvent(level, event.pos(), event.paramA(), event.paramB());
                if (regionThreading) {
                    RegionThreadingHooks.runBlockTickTask(level, event.pos(), body);
                } else {
                    body.run();
                }
            }
        } finally {
            if (regionThreading) {
                RegionThreadingHooks.endBlockEventPhase(level);
            }
        }
    }

    /** {@return the block state of every position the piston move can touch, ordered by x} */
    private String describeStates(ServerLevel level) {
        final StringBuilder states = new StringBuilder();
        for (int x = PISTON_POS.getX() - COLUMN_SIZE - 2; x <= PISTON_POS.getX() + 2; x++) {
            final BlockState state = level.getBlockState(new BlockPos(x, Y, PISTON_POS.getZ()));
            if (state.isAir()) {
                continue;
            }
            states.append(x).append('=').append(state).append(' ');
        }
        return states.toString();
    }

    /** {@return the type of every block entity the piston move created, ordered by position} */
    private String describeBlockEntities(ServerLevel level) {
        final StringBuilder entities = new StringBuilder();
        for (int x = PISTON_POS.getX() - COLUMN_SIZE - 2; x <= PISTON_POS.getX() + 2; x++) {
            final BlockEntity blockEntity = level.getBlockEntity(new BlockPos(x, Y, PISTON_POS.getZ()));
            if (blockEntity != null) {
                entities.append(x).append('=').append(blockEntity.getType()).append(' ');
            }
        }
        return entities.toString();
    }

    /** Runs one event of the phase the way the vanilla phase does: under the region context of its position. */
    private void runEvent(ServerLevel level, BlockPos pos, Runnable body) {
        RegionThreadingHooks.runBlockTickTask(level, pos, () -> {
            assertTrue(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, pos),
                    "an event of a quiescent phase runs under its region's lease");
            body.run();
        });
    }

    private RegionThreadingDiagnostics.BlockEventPhaseCounters phaseCounters() {
        return this.engine.diagnostics().blockEventPhase();
    }

    @SuppressWarnings("unchecked")
    private it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData> blockEvents(
            ServerLevel level) {
        try {
            final Field field = ServerLevel.class.getDeclaredField("blockEvents");
            field.setAccessible(true);
            return (it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData>) field.get(level);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void installEngine(ServerLevel level) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, true, FIXED);
        created.setBorderUpdatesEnabledForTesting(true);
        RegionThreadingManager.install(created);
        this.engine = created;
        for (int chunkX = 0; chunkX <= 8; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 2; chunkZ++) {
                created.addRegionSection(level, chunkX, chunkZ);
            }
        }
    }

    /**
     * {@return a level that runs the real vanilla methods and serves its chunks from the test}
     * <p>
     * The mock runs the real methods, so the write, the block update publication and the neighbour propagation are
     * the production ones. The fields the vanilla constructor would have filled are installed explicitly, because a
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
        doReturn(new net.minecraft.world.level.border.WorldBorder()).when(level).getWorldBorder();
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(3);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        @SuppressWarnings("unchecked")
        final Registry<Biome> biomes = mock(Registry.class);
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
        doReturn(registryAccess).when(level).registryAccess();
        doAnswer(invocation -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup()).when(level).holderLookup(any());
        setLevelField(level, "thread", Thread.currentThread());
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        setLevelField(level, "neighborUpdater", new RegionAwareNeighborUpdater(level, 1000000));
        setLevelField(level, "navigatingMobs", new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<PathNavigation>());
        setLevelField(level, "pathTypesByPosCache", new net.minecraft.world.level.pathfinder.PathTypeCache());
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
            final java.util.function.BooleanSupplier condition = invocation.getArgument(0);
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
        // The piston body plays a sound and posts a game event through the level; neither is part of the phase.
        doNothing().when(level).playSound(any(), any(BlockPos.class), any(), any(), anyFloat(), anyFloat());
        doNothing().when(level).gameEvent(any(Holder.class), any(BlockPos.class), any(GameEvent.Context.class));
        doNothing().when(level).gameEvent(any(Holder.class), any(Vec3.class), any(GameEvent.Context.class));
        doNothing().when(level).gameEvent(any(net.minecraft.world.entity.Entity.class), any(Holder.class), any(BlockPos.class));
        doNothing().when(level).addDestroyBlockEffect(any(BlockPos.class), any(BlockState.class));
        setLevelField(level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "freshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "pendingFreshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "blockEntityTickerLock", new Object());
        setLevelField(level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "blockEntityTickerChunks",
                new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<LevelChunk>());
        setLevelField(level, "blockEntityTickerSequence", new java.util.concurrent.atomic.AtomicLong());
        setLevelField(level, "blockEvents", new it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet<net.minecraft.world.level.BlockEventData>());
        setLevelField(level, "blockEventsToReschedule", new ArrayList<net.minecraft.world.level.BlockEventData>());
        return level;
    }

    /** Makes the chunk source answer the block reads and writes of the test from {@code chunks}. */
    private void serveChunks(ServerLevel level, Map<Long, LevelChunk> chunks) {
        final ServerChunkCache source = level.getChunkSource();
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                    key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
        }).when(source).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                    key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
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
