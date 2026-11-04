/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the capture and the same-tick delivery of a game event whose listeners belong to another region.
 * <p>
 * The worker body is a real chunk tick group whose body posts a game event through the real
 * {@link GameEventDispatcher}. The listeners are real registrations in the section registries of real chunks, so
 * the dispatch visits them through the vanilla registry, the vanilla distance filter and the vanilla delivery
 * mode. A recording listener names the thread it ran on, so the test proves that a listener of another region is
 * not called by the posting worker, that the owning region's worker calls it inside the posting batch, and that
 * the recording order is kept. The refusal paths - the switch off, a full capture queue and a target without a
 * live region - are counted and never delivered to a different region.
 */
@Timeout(60)
class RegionGameEventCaptureTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int Y = 64;
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;
    /** Source chunk: the last chunk column of region (0,0) of the size-3 fixed layout. */
    private static final ChunkPos SOURCE_CHUNK = new ChunkPos(2, 1);
    /**
     * The source position: the last block of region (0,0), so the 16-block notification radius of the common
     * events reaches the first chunk column of region (1,0).
     */
    private static final BlockPos SOURCE_POS = new BlockPos(47, Y, 17);
    /** First chunk column of region (1,0). */
    private static final ChunkPos TARGET_CHUNK = new ChunkPos(3, 1);
    private static final BlockPos TARGET_POS = new BlockPos(49, Y, 17);
    /** A second position of the same target chunk column, inside the 8-block listener radius of the source. */
    private static final BlockPos SECOND_POS = new BlockPos(52, Y, 17);
    /** Chunk column (3,0) of the target region, its closest block still inside the listener radius. */
    private static final ChunkPos NORTH_CHUNK = new ChunkPos(3, 0);
    private static final BlockPos NORTH_POS = new BlockPos(49, Y, 15);
    /** Chunk column (3,2) of the target region: its closest block is 15 blocks away, outside every radius. */
    private static final ChunkPos SOUTH_CHUNK = new ChunkPos(3, 2);

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
     * A game event posted by a worker reaches the listener of the neighbouring region on that region's own worker
     * inside the posting batch, and the posting worker only visits the listeners it owns.
     */
    @Test
    void deliversAForeignListenerOnItsOwnWorkerInsideThePostingBatch() {
        final World world = new World(true);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        final AtomicReference<String> sourceThread = new AtomicReference<>();
        world.register(SOURCE_CHUNK, new RecordingListener("local", SOURCE_POS, deliveries));
        world.register(TARGET_CHUNK, new RecordingListener("foreign", TARGET_POS, deliveries));
        doAnswer(invocation -> {
            sourceThread.set(Thread.currentThread().getName());
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertTrue(sourceThread.get().startsWith("NeoForge Region Worker"), "the posting body runs on a worker: " + sourceThread.get());
        assertEquals(2, deliveries.size(), "both listeners are called once: " + deliveries);
        assertEquals("local@" + sourceThread.get(), deliveries.get(0), "the local listener is called by the posting worker");
        assertTrue(deliveries.get(1).startsWith("foreign@NeoForge Region Worker"),
                "the foreign listener is called by a region worker: " + deliveries.get(1));
        final String foreignThread = deliveries.get(1).substring("foreign@".length());
        assertNotEquals(sourceThread.get(), foreignThread,
                "the foreign listener must not run on the posting worker: " + deliveries);
        final RegionThreadingDiagnostics.GameEventCounters counters = this.engine.diagnostics().gameEvents();
        assertEquals(1L, counters.posted(), "the post happened while a region batch was in flight: " + counters);
        assertEquals(6L, counters.localSections(), "the six source columns of chunk x 1..2 are the worker's own: " + counters);
        assertEquals(3L, counters.deferredSections(), "the three columns of chunk x 3 belong to the other region: " + counters);
        assertEquals(3L, counters.deliveredSections(), "the owning region replays every captured column: " + counters);
        assertEquals(0L, counters.refusedSections(), "the capture queue admitted every column: " + counters);
        assertEquals(0L, counters.droppedSections(), "every captured column was replayed: " + counters);
        assertEquals(1L, counters.passes(), "one delivery round is enough: " + counters);
    }

    /** Two posts of one worker phase are delivered to the foreign region in recording order. */
    @Test
    void keepsTheRecordingOrderOfTwoPostsToOneForeignRegion() {
        final World world = new World(true);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(TARGET_CHUNK, new RecordingListener("first", TARGET_POS, deliveries));
        world.register(TARGET_CHUNK, new RecordingListener("second", SECOND_POS, deliveries));
        doAnswer(invocation -> {
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            world.dispatcher.post(GameEvent.BLOCK_DESTROY, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertEquals(List.of("first", "second", "first", "second"), names(deliveries),
                "every post reaches both listeners of the foreign region, in recording order: " + deliveries);
        assertEquals(6L, this.engine.diagnostics().gameEvents().deferredSections(), "three columns per post");
        assertEquals(6L, this.engine.diagnostics().gameEvents().deliveredSections(), "both posts are replayed");
    }

    /**
     * With the switch off the dispatch is the vanilla one: the posting worker visits the foreign listener itself
     * and no counter moves, which is the documented racy baseline of the feature.
     */
    @Test
    void switchOffKeepsTheVanillaDispatchAndCountsNothing() {
        final World world = new World(true);
        this.engine.setCrossRegionGameEventsEnabledForTesting(false);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(SOURCE_CHUNK, new RecordingListener("local", SOURCE_POS, deliveries));
        world.register(TARGET_CHUNK, new RecordingListener("foreign", TARGET_POS, deliveries));
        final AtomicReference<String> sourceThread = new AtomicReference<>();
        doAnswer(invocation -> {
            sourceThread.set(Thread.currentThread().getName());
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertEquals(2, deliveries.size(), "the vanilla dispatch visits both listeners: " + deliveries);
        assertEquals("local@" + sourceThread.get(), deliveries.get(0), "the local listener is called inline");
        assertEquals("foreign@" + sourceThread.get(), deliveries.get(1),
                "the posting worker calls the foreign listener inline, which is the documented race");
        final RegionThreadingDiagnostics.GameEventCounters counters = this.engine.diagnostics().gameEvents();
        assertEquals(0L, counters.posted(), "the switch off counts nothing: " + counters);
        assertEquals(0L, counters.deferredSections(), "the switch off captures nothing: " + counters);
        assertEquals(0L, counters.deliveredSections(), "the switch off delivers nothing: " + counters);
    }

    /** A capture queue that is full refuses the column, counts it and never delivers the event late. */
    @Test
    void refusesTheColumnWhenTheCaptureQueueIsFull() {
        final World world = new World(true);
        this.engine.setGameEventCapacityForTesting(1);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(NORTH_CHUNK, new RecordingListener("north", NORTH_POS, deliveries));
        world.register(TARGET_CHUNK, new RecordingListener("middle", TARGET_POS, deliveries));
        world.register(SOUTH_CHUNK, new RecordingListener("south", new BlockPos(49, Y, 33), deliveries));
        doAnswer(invocation -> {
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertEquals(List.of("north"), names(deliveries), "only the admitted column's listener is called: " + deliveries);
        final RegionThreadingDiagnostics.GameEventCounters counters = this.engine.diagnostics().gameEvents();
        assertEquals(1L, counters.deferredSections(), "the queue admits one column: " + counters);
        assertEquals(2L, counters.refusedSections(), "the other two columns are refused at the call site: " + counters);
        assertEquals(1L, counters.deliveredSections(), "only the admitted column is replayed: " + counters);
        assertEquals(0L, counters.droppedSections(), "a refused column is counted as refused, not as dropped: " + counters);
    }

    /**
     * A captured column whose target region has no live region is dropped and counted: it is never replayed into a
     * different region, and its listener is never called.
     */
    @Test
    void dropsTheColumnWhoseTargetRegionDoesNotExist() {
        final World world = new World(false);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(TARGET_CHUNK, new RecordingListener("foreign", TARGET_POS, deliveries));
        doAnswer(invocation -> {
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertEquals(List.of(), deliveries, "the listener of a region that has no owner is never called");
        final RegionThreadingDiagnostics.GameEventCounters counters = this.engine.diagnostics().gameEvents();
        assertEquals(3L, counters.deferredSections(), "the columns were captured: " + counters);
        assertEquals(3L, counters.droppedSections(), "and dropped because no region owns them: " + counters);
        assertEquals(0L, counters.deliveredSections(), "nothing was replayed: " + counters);
    }

    /** A game event posted on the server thread outside a region batch is the vanilla inline dispatch. */
    @Test
    void aServerThreadPostStaysInline() {
        final World world = new World(true);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(TARGET_CHUNK, new RecordingListener("foreign", TARGET_POS, deliveries));

        world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));

        assertEquals(List.of("foreign@" + Thread.currentThread().getName()), deliveries,
                "the server thread delivers inline: " + deliveries);
        assertEquals(0L, this.engine.diagnostics().gameEvents().deferredSections(), "no region batch was in flight");
    }

    /** One dispatch, one listener: the same event reaches the same listener exactly once. */
    @Test
    void deliversEachListenerOnce() {
        final World world = new World(true);
        final List<String> deliveries = new CopyOnWriteArrayList<>();
        world.register(TARGET_CHUNK, new RecordingListener("foreign", TARGET_POS, deliveries));
        doAnswer(invocation -> {
            world.dispatcher.post(GameEvent.BLOCK_PLACE, Vec3.atCenterOf(SOURCE_POS), GameEvent.Context.of(Blocks.STONE.defaultBlockState()));
            return null;
        }).when(world.level).tickChunk(any(LevelChunk.class), anyInt());

        world.tickSourceChunk();

        assertEquals(1, deliveries.size(), "the listener is called once, not once per dispatch and replay: " + deliveries);
    }

    /**
     * The chunks, the level and the installed engine of one test run.
     * <p>
     * The level is a mock that runs the real methods, so the dispatch, the listener registry and the distance
     * filter are the production ones; only the level, its chunk source and its light engine are mocks.
     */
    private final class World {
        private final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        private final Map<Long, LevelChunk> chunks = new HashMap<>();
        private final GameEventDispatcher dispatcher = new GameEventDispatcher(this.level);

        /**
         * @param withTargetRegion whether the region that owns the captured chunk columns exists, so the delivery
         *                         has an owner to lease
         */
        private World(boolean withTargetRegion) {
            doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
            doReturn("test-level").when(this.level).toString();
            doReturn(1234L).when(this.level).getSeed();
            doReturn(20L).when(this.level).getGameTime();
            doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(this.level).dimension();
            doReturn(3).when(this.level).entitySectionRegionChunkSize();
            doReturn(512).when(this.level).maxChainedNeighborUpdates();
            doReturn(MIN_BUILD_HEIGHT).when(this.level).getMinBuildHeight();
            doReturn(LEVEL_HEIGHT).when(this.level).getHeight();
            doReturn(Blocks.AIR.defaultBlockState()).when(this.level).getBlockState(any(BlockPos.class));
            @SuppressWarnings("unchecked")
            final Registry<Biome> biomes = mock(Registry.class);
            final RegistryAccess registryAccess = mock(RegistryAccess.class);
            when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
            doReturn(registryAccess).when(this.level).registryAccess();
            doAnswer(invocation -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup()).when(this.level).holderLookup(any());
            final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
            doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
            doAnswer(invocation -> this.chunk(invocation.getArgument(0), invocation.getArgument(1)))
                    .when(chunkSource).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
            doAnswer(invocation -> this.chunk(invocation.getArgument(0), invocation.getArgument(1)))
                    .when(chunkSource).getChunkNow(anyInt(), anyInt());
            doAnswer(invocation -> {
                final java.util.function.BooleanSupplier condition = invocation.getArgument(0);
                while (!condition.getAsBoolean()) {
                    LockSupport.parkNanos(100_000L);
                }
                return null;
            }).when(chunkSource).managedBlockOnMainThread(any());
            doReturn(chunkSource).when(this.level).getChunkSource();
            setLevelField(this.level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
            setLevelField(this.level, "neighborUpdater", new RegionAwareNeighborUpdater(this.level, 512));

            final RegionThreadingEngine installed = new RegionThreadingEngine(2, false, false, false, false, false, true,
                    RegionLayout.of(RegionPolicy.FIXED_3X3));
            installed.setCrossRegionGameEventsEnabledForTesting(true);
            RegionThreadingManager.install(installed);
            engine = installed;
            installed.addRegionSection(this.level, SOURCE_CHUNK.x, SOURCE_CHUNK.z);
            if (withTargetRegion) {
                installed.addRegionSection(this.level, TARGET_CHUNK.x, TARGET_CHUNK.z);
                installed.addRegionSection(this.level, NORTH_CHUNK.x, NORTH_CHUNK.z);
                installed.addRegionSection(this.level, SOUTH_CHUNK.x, SOUTH_CHUNK.z);
            }
        }

        /** Registers {@code listener} in the section registry of {@code chunkPos}. */
        private GameEventListener register(ChunkPos chunkPos, GameEventListener listener) {
            this.chunk(chunkPos.x, chunkPos.z).getListenerRegistry(SectionPos.blockToSectionCoord(Y)).register(listener);
            return listener;
        }

        /** Ticks the source chunk through the region chunk tick plan, i.e. on the worker of its region. */
        private void tickSourceChunk() {
            final RegionChunkTickPlan plan = engine.beginChunkTickPlan(this.level, 1, PARKING_BARRIER);
            assertNotNull(plan, "chunk tick worker execution has to be enabled");
            plan.accept(this.chunk(SOURCE_CHUNK.x, SOURCE_CHUNK.z));
            plan.executeAndAwait(PARKING_BARRIER);
        }

        /** {@return a real chunk with real listener registries and real block palettes} */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private LevelChunk chunk(int chunkX, int chunkZ) {
            return this.chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> {
                final int sectionCount = this.level.getSectionsCount();
                final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
                for (int index = 0; index < sectionCount; index++) {
                    sections[index] = new LevelChunkSection(
                            new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                                    PalettedContainer.Strategy.SECTION_STATES),
                            (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class));
                }
                final LevelChunk chunk = new LevelChunk(this.level, new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY,
                        new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
                chunk.setFullStatus(() -> net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING);
                chunk.setLoaded(true);
                return chunk;
            });
        }
    }

    /** {@return the listener names of {@code deliveries}, without the thread each one ran on} */
    private static List<String> names(List<String> deliveries) {
        final List<String> names = new ArrayList<>(deliveries.size());
        for (final String delivery : deliveries) {
            names.add(delivery.substring(0, delivery.indexOf('@')));
        }
        return names;
    }

    /** A listener that records the order and the thread of every delivery. */
    private static final class RecordingListener implements GameEventListener {
        private final String name;
        private final BlockPos pos;
        private final List<String> deliveries;

        private RecordingListener(String name, BlockPos pos, List<String> deliveries) {
            this.name = name;
            this.pos = pos;
            this.deliveries = deliveries;
        }

        @Override
        public PositionSource getListenerSource() {
            return new BlockPositionSource(this.pos);
        }

        @Override
        public int getListenerRadius() {
            return 8;
        }

        @Override
        public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 source) {
            this.deliveries.add(this.name + "@" + Thread.currentThread().getName());
            return true;
        }
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
