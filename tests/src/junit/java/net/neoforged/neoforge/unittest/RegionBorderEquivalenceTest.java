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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Randomized equivalence harness for a redstone circuit laid across region borders.
 * <p>
 * The circuit is a line of real {@link Blocks#REPEATER} components with randomized delays. A repeater has the
 * exact three effects every redstone component family shares: it reads the input of the block behind it, it
 * schedules its own delay with the real {@link LevelTicks} scheduler when that input changes, and on its tick it
 * writes its powered state and starts a neighbor cascade. The harness runs the line with region threading off
 * and with the border capture and fixpoint on, and compares the powered state of every position after every
 * tick. The mechanism under test is therefore exercised with the real vanilla delay, input and propagation
 * code; the harness does not claim to exercise the individual shapes of dust, torches, comparators, observers
 * and pistons.
 * <p>
 * One test starts the line at every offset 1..15 from a region border. The second starts it one block before
 * the border and makes it longer than one fixed region (3 chunks = 48 blocks), so it crosses two borders. The
 * level is a mock that runs the same write, signal and neighbor hooks the production level runs; the scheduled
 * ticks of the on run really execute on region workers.
 */
@Timeout(180)
class RegionBorderEquivalenceTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;
    /** First block of region (1,0); every offset is measured from this border. */
    private static final int BORDER_X = REGION_CHUNK_SIZE * 16;
    private static final int Y = 64;
    private static final int Z = 1;
    private static final int OFFSET_CHAIN_LENGTH = 8;
    private static final int OFFSET_TICKS = 40;
    private static final int LONG_CHAIN_LENGTH = 50;
    private static final int LONG_TICKS = 120;

    private RegionThreadingEngine engine;
    private final AtomicLong gameTime = new AtomicLong();
    private String lastOnDiagnostics = "";

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void everyBorderOffsetProducesTheSameTickByTickStateOnAndOff() {
        for (int offset = 1; offset <= 15; offset++) {
            final List<String> off = this.run(false, offset, 0x5EEDL + offset, OFFSET_CHAIN_LENGTH, OFFSET_TICKS);
            final List<String> on = this.run(true, offset, 0x5EEDL + offset, OFFSET_CHAIN_LENGTH, OFFSET_TICKS);
            assertEquals(off, on, "a chain starting " + offset + " block(s) before the border has to tick like vanilla: " + this.lastOnDiagnostics);
            assertEquals(OFFSET_TICKS, on.size());
        }
    }

    @Test
    void chainLongerThanOneRegionProducesTheSameTickByTickStateOnAndOff() {
        final List<String> off = this.run(false, 1, 0x60DEL, LONG_CHAIN_LENGTH, LONG_TICKS);
        final List<String> on = this.run(true, 1, 0x60DEL, LONG_CHAIN_LENGTH, LONG_TICKS);
        assertEquals(off, on, "a chain longer than one region has to tick like vanilla: " + this.lastOnDiagnostics);
    }

    private List<String> run(boolean regionThreading, int offset, long seed, int chainLength, int ticks) {
        RegionThreadingManager.clear();
        this.engine = null;
        this.gameTime.set(0L);
        final MockWorld world = new MockWorld();
        final ServerLevel level = world.level;
        final int startX = BORDER_X - offset;
        final int firstChunk = Math.floorDiv(startX, 16) - 1;
        final int lastChunk = Math.floorDiv(startX + chainLength, 16) + 1;
        final RandomSource random = RandomSource.create(seed);
        final List<BlockPos> chain = new ArrayList<>(chainLength);
        for (int index = 0; index < chainLength; index++) {
            final BlockPos pos = new BlockPos(startX + index, Y, Z);
            chain.add(pos);
            // Every repeater needs a sturdy support; the chain runs east and the repeater's FACING is its input
            // side, so the whole line faces west.
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                    .setValue(BlockStateProperties.DELAY, 1 + random.nextInt(2)), 3);
        }
        if (regionThreading) {
            final RegionThreadingEngine installed = new RegionThreadingEngine(4, false, false, false, false, true,
                    net.neoforged.neoforge.server.threading.region.RegionPolicy.FIXED_3X3);
            installed.setBorderUpdatesEnabledForTesting(true);
            installed.enableUnclassifiedWorkerCallbacksForTesting();
            RegionThreadingManager.install(installed);
            this.engine = installed;
            for (int chunkX = firstChunk; chunkX <= lastChunk; chunkX++) {
                installed.addRegionSection(level, chunkX, 0);
            }
        }
        final List<String> trace = new ArrayList<>(ticks);
        // The source is placed last, so every component is in the world when the first cascade starts.
        level.setBlock(new BlockPos(startX - 1, Y, Z), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        assertTrue(world.blockTicks.count() > 0, "the source placement has to schedule the first repeater");
        for (int tick = 1; tick <= ticks; tick++) {
            this.gameTime.set(tick);
            final LevelTicks.TickDispatcher<Block> dispatcher = regionThreading
                    ? this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER)
                    : null;
            if (regionThreading) {
                assertNotNull(dispatcher, "the on run has to dispatch scheduled ticks on workers: " + this.lastOnDiagnostics);
            }
            world.blockTicks.tick(tick, 65536, (pos, block) -> level.getBlockState(pos).tick(level, pos, random), dispatcher);
            trace.add(world.states(chain));
        }
        if (this.engine != null) {
            this.lastOnDiagnostics = this.engine.diagnostics().borderUpdates() + ", scheduledOnWorkers="
                    + this.engine.diagnostics().scheduledTicksOnWorkers() + ", fallbackScheduled="
                    + this.engine.diagnostics().fallbackScheduledTicks() + ", skippedScheduled="
                    + this.engine.diagnostics().skippedScheduledTicks() + ", violations="
                    + this.engine.diagnostics().ownershipViolationCount();
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
        return trace;
    }

    /**
     * A level mock with the production write, signal and neighbor seams; the block states live in a concurrent
     * map so the propagation passes of two regions can read each other's writes.
     */
    private final class MockWorld {
        private final ServerLevel level = mock(ServerLevel.class);
        private final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
        private final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        private final RegionAwareNeighborUpdater neighborUpdater;

        private MockWorld() {
            for (int chunkX = -2; chunkX <= 12; chunkX++) {
                this.blockTicks.addContainer(new net.minecraft.world.level.ChunkPos(chunkX, 0), new LevelChunkTicks<>());
            }
            this.neighborUpdater = new RegionAwareNeighborUpdater(this.level, 512);
            setLevelField(this.level, "random", RandomSource.create(4321L));
            doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
            doReturn(1234L).when(this.level).getSeed();
            doAnswer(invocation -> gameTime.get()).when(this.level).getGameTime();
            doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(this.level).dimension();
            doReturn(512).when(this.level).maxChainedNeighborUpdates();
            doReturn(this.blockTicks).when(this.level).getBlockTicks();
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()))
                    .when(this.level).getBlockState(any(BlockPos.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                final BlockState state = invocation.getArgument(1);
                final BlockState previous = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                this.states.put(pos.immutable(), state);
                if (previous != state) {
                    // The production write publishes the new state and starts the neighbor cascade of the write.
                    this.level.updateNeighborsAt(pos, previous.getBlock());
                }
                return true;
            }).when(this.level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, invocation.getArgument(1), null);
                return null;
            }).when(this.level).updateNeighborsAt(any(BlockPos.class), any(Block.class));
            doAnswer(invocation -> {
                this.neighborUpdater.updateNeighborsAtExceptFromFacing(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2));
                return null;
            }).when(this.level).updateNeighborsAtExceptFromFacing(any(BlockPos.class), any(Block.class), any());
            doAnswer(invocation -> {
                this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(this.level).neighborChanged(any(BlockPos.class), any(Block.class), any(BlockPos.class));
            doAnswer(invocation -> {
                this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4));
                return null;
            }).when(this.level).neighborChanged(any(BlockState.class), any(BlockPos.class), any(Block.class), any(BlockPos.class),
                    anyBoolean());
            doAnswer(invocation -> {
                this.blockTicks.schedule(new ScheduledTick<Block>(invocation.getArgument(1, Block.class),
                        invocation.getArgument(0, BlockPos.class), gameTime.get() + invocation.getArgument(2, Integer.class), 0));
                return null;
            }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt());
            doAnswer(invocation -> {
                this.blockTicks.schedule(new ScheduledTick<Block>(invocation.getArgument(1, Block.class),
                        invocation.getArgument(0, BlockPos.class), gameTime.get() + invocation.getArgument(2, Integer.class),
                        invocation.getArgument(3, TickPriority.class), 0L));
                return null;
            }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt(), any(TickPriority.class));
            doAnswer(invocation -> {
                final BlockState state = this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState());
                final Direction direction = invocation.getArgument(1);
                if (state.is(Blocks.REDSTONE_BLOCK)) {
                    return 15;
                }
                if (state.is(Blocks.REPEATER)) {
                    return state.getValue(BlockStateProperties.POWERED)
                            && state.getValue(BlockStateProperties.HORIZONTAL_FACING) == direction ? 15 : 0;
                }
                return 0;
            }).when(this.level).getSignal(any(BlockPos.class), any(Direction.class));
            final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
            doAnswer(invocation -> {
                final BooleanSupplier condition = invocation.getArgument(0);
                while (!condition.getAsBoolean()) {
                    LockSupport.parkNanos(100_000L);
                }
                return null;
            }).when(chunkSource).managedBlockOnMainThread(any());
            doReturn(chunkSource).when(this.level).getChunkSource();
            setLevelField(this.level, "neighborUpdater", this.neighborUpdater);
        }

        private String states(List<BlockPos> chain) {
            final StringBuilder states = new StringBuilder(chain.size());
            for (final BlockPos pos : chain) {
                final BlockState state = this.states.get(pos);
                states.append(state != null && state.is(Blocks.REPEATER) && state.getValue(BlockStateProperties.POWERED) ? '1' : '0');
            }
            return states.toString();
        }
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
