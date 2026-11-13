/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.GameEventTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.VibrationFrequency;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Compares a sculk sensor and a calibrated sculk sensor whose vibration is posted from another region against the
 * inline run, tick by tick.
 * <p>
 * The sensor is a real block entity with its real {@link VibrationSystem}: the candidate selector, the travel
 * time, the redstone strength and the comparator output are the production code. The vibration source is a real
 * chunk tick body that posts a real game event through the real {@link GameEventDispatcher}, so the capture, the
 * replay and the distance filter are the production ones. The observable of one tick is the block state of the
 * sensor - its phase and its redstone power - plus its analog (comparator) output and the frequency of the
 * vibration it received.
 * <p>
 * The block entity tick of the sensor runs on the test thread in both runs. The property under test is the game
 * time at which the candidate is recorded and the tick at which the selector consumes it, not which thread ticks
 * the block entity. The tick order of the harness is vanilla's - the scheduled block ticks first, then the chunk
 * tick that posts the vibration, then the block entity ticks - so a delivery that missed the posting tick would
 * shift every activation by one tick and fail the comparison.
 */
@Timeout(120)
class RegionSculkSensorEquivalenceTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int Y = 64;
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;
    /**
     * The sensor sits on the last block of region (0,0) of the size-3 fixed layout: chunk (2,2) ends at block 47.
     * Every vibration source of the test is in a neighbouring region, so the dispatch of the source worker always
     * crosses a border.
     */
    private static final BlockPos SENSOR_POS = new BlockPos(47, Y, 47);
    /** A source two blocks away, in region (1,0). */
    private static final BlockPos EAST_POS = new BlockPos(49, Y, 47);
    /** A source two blocks away, in region (0,1), i.e. the same distance as {@link #EAST_POS}. */
    private static final BlockPos SOUTH_POS = new BlockPos(47, Y, 49);
    /** A source six blocks away, in region (0,1). */
    private static final BlockPos FAR_POS = new BlockPos(47, Y, 53);
    /** A source at the diagonal of region (1,1), which the 16-block radius of the event reaches as well. */
    private static final BlockPos CORNER_POS = new BlockPos(49, Y, 49);
    private static final ChunkPos SENSOR_CHUNK = new ChunkPos(2, 2);
    private static final ChunkPos EAST_CHUNK = new ChunkPos(3, 2);
    private static final ChunkPos SOUTH_CHUNK = new ChunkPos(2, 3);

    private RegionThreadingEngine engine;

    /**
     * Binds the vibration tag and the vibration frequencies the scenarios use.
     * <p>
     * A running server binds both from its datapack. A unit test has no datapack, so the tag would be empty and
     * every frequency would read as zero - the comparator output of the sensor is the frequency of the vibration
     * it received, and the frequency is the second half of the selector's tie-break, so without them neither could
     * be compared. The values are the vanilla ones: {@code block_place} 13, {@code block_destroy} 12 and
     * {@code entity_die} 15.
     */
    @BeforeEach
    void bindGameEventData() {
        final List<Holder<GameEvent>> vibrations = List.of(GameEvent.BLOCK_PLACE, GameEvent.BLOCK_DESTROY, GameEvent.ENTITY_DIE);
        gameEventRegistry().bindTags(Map.of(
                GameEventTags.VIBRATIONS, vibrations,
                GameEventTags.SHRIEKER_CAN_LISTEN, vibrations));
        dataMaps().put(NeoForgeDataMaps.VIBRATION_FREQUENCIES, Map.of(
                GameEvent.BLOCK_PLACE.key(), new VibrationFrequency(13),
                GameEvent.BLOCK_DESTROY.key(), new VibrationFrequency(12),
                GameEvent.ENTITY_DIE.key(), new VibrationFrequency(15)));
    }

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        gameEventRegistry().resetTags();
        dataMaps().clear();
    }

    /**
     * A vibration posted from the neighbouring region activates the sensor on the same tick as inline, with the
     * same redstone power and the same comparator output.
     */
    @Test
    void aCrossRegionVibrationActivatesTheSensorLikeTheInlineRun() {
        final Run inline = this.run(false, Blocks.SCULK_SENSOR, false, List.of(new Source(EAST_POS, GameEvent.BLOCK_PLACE)), 12);
        final Run regionized = this.run(true, Blocks.SCULK_SENSOR, false, List.of(new Source(EAST_POS, GameEvent.BLOCK_PLACE)), 12);

        assertEquals(inline.trace(), regionized.trace(), "the regionized run has to tick like the inline run");
        // The candidate is recorded at game time 1 at a distance of 2 blocks, so the selector picks it at tick 2
        // and the travel time of floor(2) = 2 ticks completes at tick 3. The power of a distance of 2 blocks is
        // 15 - floor(15 / 8 * 2) = 12 and the comparator output is the frequency of block_place, 13.
        assertEquals("inactive:0:0:0", inline.trace().get(0), "the sensor is idle at the tick of the vibration: " + inline.trace());
        assertEquals("inactive:0:0:0", inline.trace().get(1), "the selector picks the candidate one tick later: " + inline.trace());
        assertEquals("active:12:13:13", inline.trace().get(2), "the vibration arrives at tick 3: " + inline.trace());
        assertTrue(regionized.gameEvents().deliveredSections() > 0L,
                "the sensor's chunk column was delivered to its own region: " + regionized.gameEvents());
        assertEquals(regionized.gameEvents().deferredSections(), regionized.gameEvents().deliveredSections(),
                "every captured column is replayed: " + regionized.gameEvents());
        assertEquals(0L, regionized.gameEvents().refusedSections(), "no column was refused: " + regionized.gameEvents());
        assertEquals(0L, regionized.gameEvents().droppedSections(), "no column was dropped: " + regionized.gameEvents());
    }

    /** A calibrated sculk sensor filters by frequency and is delivered across the border identically. */
    @Test
    void aCalibratedSensorFiltersAcrossTheBorderLikeTheInlineRun() {
        // The redstone block behind the sensor calibrates it to the signal 15, i.e. to the frequency of
        // entity_die. A block_place of frequency 13 is refused by the calibration, entity_die is not.
        final Run inlineMatching = this.run(false, Blocks.CALIBRATED_SCULK_SENSOR, true,
                List.of(new Source(EAST_POS, GameEvent.ENTITY_DIE)), 12);
        final Run regionizedMatching = this.run(true, Blocks.CALIBRATED_SCULK_SENSOR, true,
                List.of(new Source(EAST_POS, GameEvent.ENTITY_DIE)), 12);
        assertEquals(inlineMatching.trace(), regionizedMatching.trace(), "the matching frequency activates like inline");
        // A calibrated sensor listens at 16 blocks, so the power of a distance of 2 blocks is
        // 15 - floor(15 / 16 * 2) = 14, and the comparator output is the frequency of entity_die, 15.
        assertEquals("active:14:15:15", inlineMatching.trace().get(2),
                "the matching frequency activates at tick 3: " + inlineMatching.trace());

        final Run inlineFiltered = this.run(false, Blocks.CALIBRATED_SCULK_SENSOR, true,
                List.of(new Source(EAST_POS, GameEvent.BLOCK_PLACE)), 12);
        final Run regionizedFiltered = this.run(true, Blocks.CALIBRATED_SCULK_SENSOR, true,
                List.of(new Source(EAST_POS, GameEvent.BLOCK_PLACE)), 12);
        assertEquals(inlineFiltered.trace(), regionizedFiltered.trace(), "the filtered frequency is refused like inline");
        assertEquals("inactive:0:0:0", inlineFiltered.trace().get(11),
                "the calibrated sensor never activates: " + inlineFiltered.trace());
    }

    /**
     * Two sources of two regions vibrate one sensor in one tick: the nearest wins, exactly like inline, and the
     * power and the comparator output are the ones of that source.
     */
    @Test
    void theNearestOfTwoRegionsWinsLikeInline() {
        final List<Source> sources = List.of(new Source(EAST_POS, GameEvent.BLOCK_DESTROY), new Source(FAR_POS, GameEvent.ENTITY_DIE));
        final Run inline = this.run(false, Blocks.SCULK_SENSOR, false, sources, 12);
        final Run regionized = this.run(true, Blocks.SCULK_SENSOR, false, sources, 12);

        assertEquals(inline.trace(), regionized.trace(), "the two sources have to resolve to the same winner");
        // The east source is two blocks away with frequency 12, the far one six blocks away with frequency 15.
        // The distance decides first: power 15 - floor(15 / 8 * 2) = 12, comparator output 12.
        assertEquals("active:12:12:12", inline.trace().get(2),
                "the nearer source wins even with the lower frequency: " + inline.trace());
    }

    /**
     * Two sources of two regions at the same distance vibrate one sensor in one tick: the higher frequency wins,
     * exactly like inline.
     */
    @Test
    void theHigherFrequencyOfTwoRegionsAtEqualDistanceWinsLikeInline() {
        final List<Source> sources = List.of(new Source(EAST_POS, GameEvent.BLOCK_DESTROY), new Source(SOUTH_POS, GameEvent.ENTITY_DIE));
        final Run inline = this.run(false, Blocks.SCULK_SENSOR, false, sources, 12);
        final Run regionized = this.run(true, Blocks.SCULK_SENSOR, false, sources, 12);

        assertEquals(inline.trace(), regionized.trace(), "the two sources have to resolve to the same winner");
        assertEquals("active:12:15:15", inline.trace().get(2), "the higher frequency wins the tie: " + inline.trace());
    }

    /**
     * The 16-block notification radius of an event crosses two region borders: the source is in region (1,1) and
     * the sensor in region (0,0), so the dispatch of the source worker reaches a region it does not own.
     */
    @Test
    void aVibrationCrossingTwoBordersReachesTheSensor() {
        final Run inline = this.run(false, Blocks.SCULK_SENSOR, false, List.of(new Source(CORNER_POS, GameEvent.BLOCK_PLACE)), 12);
        final Run regionized = this.run(true, Blocks.SCULK_SENSOR, false, List.of(new Source(CORNER_POS, GameEvent.BLOCK_PLACE)), 12);

        assertEquals(inline.trace(), regionized.trace(), "a vibration across two borders has to tick like the inline run");
        // The diagonal distance is sqrt(8) = 2.83 blocks, so the travel time is 2 ticks and the power is
        // 15 - floor(15 / 8 * 2.83) = 10.
        assertEquals("active:10:13:13", inline.trace().get(2), "the diagonal vibration arrives at tick 3: " + inline.trace());
        assertTrue(regionized.gameEvents().deferredSections() > 0L,
                "the sensor's chunk column is captured for its own region: " + regionized.gameEvents());
    }

    /**
     * A shrieker whose region is not the region of the vibration still shrieks on the same tick as inline, and it
     * schedules its warden summon for the same game time.
     * <p>
     * The shriek is what summons the warden: {@code SculkShriekerBlockEntity.shriek} sets the shrieking state,
     * schedules the block tick of the shrieker 90 ticks later and posts {@code GameEvent.SHRIEK}, and that block
     * tick is what responds. The delivery therefore fixes the summon tick, and the test compares the tick of the
     * shriek and the scheduled summon with the inline run instead of spawning a warden.
     */
    @Test
    void aShriekerInAnotherRegionShrieksAndSchedulesTheSummonLikeInline() {
        final List<Source> sources = List.of(new Source(EAST_POS, GameEvent.BLOCK_PLACE, mock(net.minecraft.server.level.ServerPlayer.class)));
        final Run inline = this.runShrieker(false, sources, 12);
        final Run regionized = this.runShrieker(true, sources, 12);

        assertEquals(inline.trace(), regionized.trace(), "the shrieker has to shriek like the inline run");
        assertEquals("shrieking:true:scheduled:true", inline.trace().get(2),
                "the shrieker shrieks at tick 3 and schedules its summon: " + inline.trace());
        assertTrue(regionized.gameEvents().deliveredSections() > 0L,
                "the shrieker's chunk column was delivered to its own region: " + regionized.gameEvents());
    }

    /**
     * Runs one scenario with a shrieker instead of a sensor and returns its observable at every tick.
     */
    private Run runShrieker(boolean regionized, List<Source> sources, int ticks) {
        RegionThreadingManager.clear();
        this.engine = null;
        final World world = new World(regionized);
        world.placeShrieker();
        for (final Source source : sources) {
            world.addSource(source);
        }
        final List<String> trace = new ArrayList<>(ticks);
        for (int tick = 1; tick <= ticks; tick++) {
            world.gameTime.set(tick);
            world.tickScheduledBlocks();
            world.tickSourceChunks();
            world.tickShrieker();
            trace.add(world.describeShrieker());
        }
        final RegionThreadingDiagnostics.GameEventCounters gameEvents = this.engine == null ? null : this.engine.diagnostics().gameEvents();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
        return new Run(trace, gameEvents);
    }

    /**
     * Runs one scenario and returns the observable of the sensor at every tick.
     *
     * @param regionized whether the vibration source is ticked on a region worker through the real plan
     * @param calibrated whether a redstone block sits behind the sensor, which calibrates a calibrated sensor to
     *                   the signal 15
     */
    private Run run(boolean regionized, Block sensorBlock, boolean calibrated, List<Source> sources, int ticks) {
        RegionThreadingManager.clear();
        this.engine = null;
        final World world = new World(regionized);
        world.placeSensor(sensorBlock);
        if (calibrated) {
            world.placeCalibrationBlock();
        }
        for (final Source source : sources) {
            world.addSource(source);
        }
        final List<String> trace = new ArrayList<>(ticks);
        for (int tick = 1; tick <= ticks; tick++) {
            world.gameTime.set(tick);
            // Vanilla's order inside one level tick: the scheduled block ticks, then the chunk tick that posts
            // the vibration, then the block entity ticks.
            world.tickScheduledBlocks();
            world.tickSourceChunks();
            world.tickSensor();
            trace.add(world.describeSensor());
        }
        final RegionThreadingDiagnostics.GameEventCounters gameEvents = this.engine == null ? null : this.engine.diagnostics().gameEvents();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
        return new Run(trace, gameEvents);
    }

    /**
     * The result of one run: the observable of the sensor at every tick and, for a regionized run, the counters
     * of the game event delivery.
     */
    private record Run(List<String> trace, RegionThreadingDiagnostics.GameEventCounters gameEvents) {}

    /** One vibration source: the position it posts from, the event it posts and the entity it posts it for. */
    private record Source(BlockPos pos, Holder<GameEvent> event, Entity sourceEntity) {
        private Source(BlockPos pos, Holder<GameEvent> event) {
            this(pos, event, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static MappedRegistry<GameEvent> gameEventRegistry() {
        return (MappedRegistry<GameEvent>) BuiltInRegistries.GAME_EVENT;
    }

    /** {@return the mutable data map table of the game event registry} */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<Object, Map<ResourceKey<GameEvent>, Object>> dataMaps() {
        try {
            final Field field = BaseMappedRegistry.class.getDeclaredField("dataMaps");
            field.setAccessible(true);
            return (Map) field.get(BuiltInRegistries.GAME_EVENT);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * The level, the chunks and the sensor of one run.
     * <p>
     * The level is a mock that runs the real methods, so the vibration listener, the selector, the travel time,
     * the block activation and the game event dispatch are the production ones. Only the level, its chunk source
     * and its light engine are test doubles; the block states, the block entities and the scheduled ticks are
     * real.
     */
    private final class World {
        private final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        private final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new ConcurrentHashMap<>();
        private final Map<Long, LevelChunk> chunks = new ConcurrentHashMap<>();
        private final Map<Long, List<Source>> sourcesByChunk = new HashMap<>();
        private final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        private final GameEventDispatcher dispatcher = new GameEventDispatcher(this.level);
        private final AtomicLong gameTime = new AtomicLong();
        private final RandomSource random = RandomSource.create(4321L);
        private final boolean regionized;
        private SculkSensorBlockEntity sensor;
        private net.minecraft.world.level.block.entity.SculkShriekerBlockEntity shrieker;

        private World(boolean regionized) {
            this.regionized = regionized;
            doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
            doReturn("test-level").when(this.level).toString();
            doReturn(1234L).when(this.level).getSeed();
            doAnswer(invocation -> this.gameTime.get()).when(this.level).getGameTime();
            final net.minecraft.world.level.storage.LevelData levelData = mock(net.minecraft.world.level.storage.LevelData.class);
            doAnswer(invocation -> this.gameTime.get()).when(levelData).getGameTime();
            doReturn(levelData).when(this.level).getLevelData();
            doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(this.level).dimension();
            doReturn(3).when(this.level).entitySectionRegionChunkSize();
            doReturn(512).when(this.level).maxChainedNeighborUpdates();
            doReturn(MIN_BUILD_HEIGHT).when(this.level).getMinBuildHeight();
            doReturn(LEVEL_HEIGHT).when(this.level).getHeight();
            doReturn(this.blockTicks).when(this.level).getBlockTicks();
            doReturn(true).when(this.level).shouldTickBlocksAt(anyLong());
            doReturn(BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO)).when(this.level).isBlockInLine(any());
            doReturn(net.minecraft.world.Difficulty.PEACEFUL).when(this.level).getDifficulty();
            doNothing().when(this.level).levelEvent(anyInt(), any(BlockPos.class), anyInt());
            doReturn(0).when(this.level).sendParticles(any(net.minecraft.core.particles.ParticleOptions.class), anyDouble(), anyDouble(),
                    anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
            doNothing().when(this.level).playSound(any(), anyDouble(), anyDouble(), anyDouble(),
                    any(net.minecraft.sounds.SoundEvent.class), any(net.minecraft.sounds.SoundSource.class), anyFloat(), anyFloat());
            @SuppressWarnings("unchecked")
            final Registry<Biome> biomes = mock(Registry.class);
            final RegistryAccess registryAccess = mock(RegistryAccess.class);
            when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
            doReturn(registryAccess).when(this.level).registryAccess();
            doAnswer(invocation -> BuiltInRegistries.BLOCK.asLookup()).when(this.level).holderLookup(any());
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()))
                    .when(this.level).getBlockState(any(BlockPos.class));
            doAnswer(invocation -> this.blockEntities.get(invocation.getArgument(0)))
                    .when(this.level).getBlockEntity(any(BlockPos.class));
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState())
                    .getSignal(this.level, invocation.getArgument(0), invocation.getArgument(1)))
                            .when(this.level).getSignal(any(BlockPos.class), any(Direction.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                final BlockState state = invocation.getArgument(1);
                final BlockState previous = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                this.states.put(pos.immutable(), state);
                final BlockEntity blockEntity = this.blockEntities.get(pos);
                if (blockEntity != null) {
                    blockEntity.setBlockState(state);
                }
                if (previous != state) {
                    // The production write publishes the new state and starts the neighbor cascade of the write.
                    this.level.updateNeighborsAt(pos, previous.getBlock());
                }
                return true;
            }).when(this.level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
            doAnswer(invocation -> {
                this.level.gameEvent((Holder<GameEvent>) invocation.getArgument(0), Vec3.atCenterOf(invocation.getArgument(1)),
                        (GameEvent.Context) invocation.getArgument(2));
                return null;
            }).when(this.level).gameEvent(any(Holder.class), any(BlockPos.class), any(GameEvent.Context.class));
            doAnswer(invocation -> {
                this.level.gameEvent((Entity) invocation.getArgument(0), (Holder<GameEvent>) invocation.getArgument(1),
                        (BlockPos) invocation.getArgument(2));
                return null;
            }).when(this.level).gameEvent(any(Entity.class), any(Holder.class), any(BlockPos.class));
            doAnswer(invocation -> {
                this.dispatcher.post((Holder<GameEvent>) invocation.getArgument(0), (Vec3) invocation.getArgument(1),
                        (GameEvent.Context) invocation.getArgument(2));
                return null;
            }).when(this.level).gameEvent(any(Holder.class), any(Vec3.class), any(GameEvent.Context.class));
            doAnswer(invocation -> {
                final LevelChunk chunk = invocation.getArgument(0);
                for (final Source source : this.sourcesByChunk.getOrDefault(chunk.getPos().toLong(), List.of())) {
                    this.dispatcher.post(source.event(), Vec3.atCenterOf(source.pos()),
                            new GameEvent.Context(source.sourceEntity(), Blocks.STONE.defaultBlockState()));
                }
                return null;
            }).when(this.level).tickChunk(any(LevelChunk.class), anyInt());

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
            setLevelField(this.level, "random", new RegionAwareRandomSource(this.random));
            setLevelField(this.level, "subTickCount", new java.util.concurrent.atomic.AtomicLong());
            setLevelField(this.level, "neighborUpdater", new RegionAwareNeighborUpdater(this.level, 512));

            for (final ChunkPos pos : List.of(SENSOR_CHUNK, EAST_CHUNK, SOUTH_CHUNK, new ChunkPos(3, 3))) {
                this.blockTicks.addContainer(pos, new LevelChunkTicks<>());
            }
            if (regionized) {
                final RegionThreadingEngine installed = new RegionThreadingEngine(2, false, false, false, false, false, true,
                        RegionLayout.of(RegionPolicy.FIXED_3X3));
                installed.setCrossRegionGameEventsEnabledForTesting(true);
                RegionThreadingManager.install(installed);
                engine = installed;
                for (final ChunkPos pos : List.of(SENSOR_CHUNK, EAST_CHUNK, SOUTH_CHUNK, new ChunkPos(3, 3))) {
                    installed.addRegionSection(this.level, pos.x, pos.z);
                }
            }
        }

        /** Places the sensor and registers its listener in the section registry of its chunk. */
        private void placeSensor(Block sensorBlock) {
            final BlockState state = sensorBlock.defaultBlockState();
            this.states.put(SENSOR_POS, state);
            this.sensor = (SculkSensorBlockEntity) ((EntityBlock) sensorBlock).newBlockEntity(SENSOR_POS, state);
            this.blockEntities.put(SENSOR_POS, this.sensor);
            this.chunk(SENSOR_CHUNK.x, SENSOR_CHUNK.z)
                    .getListenerRegistry(SectionPos.blockToSectionCoord(Y))
                    .register(this.sensor.getListener());
        }

        /** Places the shrieker and registers its listener in the section registry of its chunk. */
        private void placeShrieker() {
            final BlockState state = Blocks.SCULK_SHRIEKER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SculkShriekerBlock.CAN_SUMMON, Boolean.TRUE)
                    .setValue(net.minecraft.world.level.block.SculkShriekerBlock.SHRIEKING, Boolean.FALSE);
            this.states.put(SENSOR_POS, state);
            this.shrieker = (net.minecraft.world.level.block.entity.SculkShriekerBlockEntity) ((EntityBlock) state.getBlock())
                    .newBlockEntity(SENSOR_POS, state);
            this.blockEntities.put(SENSOR_POS, this.shrieker);
            this.chunk(SENSOR_CHUNK.x, SENSOR_CHUNK.z)
                    .getListenerRegistry(SectionPos.blockToSectionCoord(Y))
                    .register(this.shrieker.getListener());
        }

        /** Ticks the shrieker's block entity, i.e. the body {@code SculkShriekerBlock.getTicker} installs. */
        private void tickShrieker() {
            VibrationSystem.Ticker.tick(this.level, this.shrieker.getVibrationData(), this.shrieker.getVibrationUser());
        }

        /** {@return the shrieking state and whether the summon tick is still scheduled} */
        private String describeShrieker() {
            final BlockState state = this.states.get(SENSOR_POS);
            return "shrieking:" + state.getValue(net.minecraft.world.level.block.SculkShriekerBlock.SHRIEKING)
                    + ":scheduled:" + this.blockTicks.hasScheduledTick(SENSOR_POS, state.getBlock());
        }

        /** Places the redstone block that calibrates a calibrated sensor to the signal 15. */
        private void placeCalibrationBlock() {
            this.states.put(SENSOR_POS.south(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }

        /** Adds one vibration source: the chunk it is posted from and the event it posts. */
        private void addSource(Source source) {
            this.states.put(source.pos(), Blocks.STONE.defaultBlockState());
            final ChunkPos chunkPos = new ChunkPos(source.pos());
            this.sourcesByChunk.computeIfAbsent(chunkPos.toLong(), key -> new ArrayList<>()).add(source);
            this.chunk(chunkPos.x, chunkPos.z);
        }

        /** Runs the scheduled block ticks of the tick, exactly like the vanilla level tick does. */
        private void tickScheduledBlocks() {
            this.blockTicks.tick(this.gameTime.get(), 65536,
                    (pos, block) -> this.level.getBlockState(pos).tick(this.level, pos, this.random));
        }

        /** Ticks the chunks that hold a vibration source, on a region worker when the run is regionized. */
        private void tickSourceChunks() {
            if (!this.regionized) {
                for (final long key : List.copyOf(this.sourcesByChunk.keySet())) {
                    final ChunkPos pos = new ChunkPos(key);
                    this.level.tickChunk(this.chunk(pos.x, pos.z), 1);
                }
                return;
            }
            final RegionChunkTickPlan plan = engine.beginChunkTickPlan(this.level, 1, PARKING_BARRIER);
            assertNotNull(plan, "chunk tick worker execution has to be enabled");
            for (final long key : List.copyOf(this.sourcesByChunk.keySet())) {
                final ChunkPos pos = new ChunkPos(key);
                plan.accept(this.chunk(pos.x, pos.z));
            }
            plan.executeAndAwait(PARKING_BARRIER);
        }

        /** Ticks the sensor's block entity, i.e. the body {@code SculkSensorBlock.getTicker} installs. */
        private void tickSensor() {
            VibrationSystem.Ticker.tick(this.level, this.sensor.getVibrationData(), this.sensor.getVibrationUser());
        }

        /** {@return the phase, the redstone power, the comparator output and the received frequency} */
        private String describeSensor() {
            final BlockState state = this.states.get(SENSOR_POS);
            return SculkSensorBlock.getPhase(state).name().toLowerCase(java.util.Locale.ROOT)
                    + ":" + state.getValue(BlockStateProperties.POWER)
                    + ":" + state.getAnalogOutputSignal(this.level, SENSOR_POS)
                    + ":" + this.sensor.getLastVibrationFrequency();
        }

        /** {@return a real chunk with real listener registries and real block palettes} */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private LevelChunk chunk(int chunkX, int chunkZ) {
            final long key = ChunkPos.asLong(chunkX, chunkZ);
            final LevelChunk known = this.chunks.get(key);
            if (known != null) {
                return known;
            }
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
            this.chunks.put(key, chunk);
            return chunk;
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
