/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntitySnapshots;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionSnapshotBlockEntity;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the per-tick value publication of border block entities: the answer a refused foreign block-entity
 * read receives instead of {@code null}.
 * <p>
 * The comparator's input is a chain of vanilla calls - {@code ComparatorBlock.getInputSignal},
 * {@code BlockState.getAnalogOutputSignal}, {@code HopperBlock.getAnalogOutputSignal},
 * {@code Level.getBlockEntity}, {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity} - and the last
 * two are the seams the publication answers. The tests drive that chain from a native region worker of the
 * engine, so the read is refused exactly like a real cross-region read, and compare the answer with the live
 * value the inline read computes.
 * <p>
 * The level is the {@link RegionRedstoneWorld} mock, which runs the production block-entity gate, the production
 * write capture and the real vanilla block classes. The per-tick publication is the production one, taken at the
 * head of the level tick while every region is quiescent.
 */
@Timeout(120)
class RegionValueSnapshotTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int Y = 64;
    /**
     * A hopper in chunk {@code (0,0)} and a comparator in chunk {@code (-1,0)}: the fixed 3x3 layout makes both
     * chunks border chunks of different regions, so the comparator reads a container of another region.
     */
    private static final BlockPos HOPPER_POS = new BlockPos(0, Y, 4);
    /** A hopper deep inside the region of chunk {@code (1,1)}: no foreign read reaches it. */
    private static final BlockPos INTERIOR_HOPPER_POS = new BlockPos(20, Y, 20);
    /** A chest on the border and the comparator that reads it from the neighbouring region. */
    private static final int BORDER_X = RegionLayout.FIXED_3X3.regionChunkSize() * 16;
    private static final BlockPos CHEST_POS = new BlockPos(BORDER_X, Y, 4);
    private static final BlockPos CHEST_COMPARATOR_POS = new BlockPos(BORDER_X - 1, Y, 4);

    private final AtomicLong gameTime = new AtomicLong();
    private RegionThreadingEngine engine;
    private RegionRedstoneWorld world;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        RegionBlockEntitySnapshots.reset();
    }

    @Test
    void foreignComparatorReadIsAnsweredWithThePublishedSignalForEveryTickOfARun() {
        final ServerLevel level = this.newWorld();
        level.setBlock(HOPPER_POS, Blocks.HOPPER.defaultBlockState(), 3);
        final HopperBlockEntity hopper = (HopperBlockEntity) this.world.blockEntities.get(HOPPER_POS);
        this.installEngine(level);

        for (int tick = 1; tick <= 320; tick++) {
            this.gameTime.set(tick);
            // The container changes every tick, like the hopper line of a comparator circuit, so the recorded
            // signal has to follow it.
            hopper.setItem(0, new ItemStack(Items.STONE, 1 + (tick % 16) * 4));
            this.world.publishBlockEntitySnapshot();

            // The inline reference: the same vanilla chain on the server thread, which reaches the live container.
            final int inline = level.getBlockState(HOPPER_POS).getAnalogOutputSignal(level, HOPPER_POS);
            final AtomicInteger answered = new AtomicInteger(-1);
            final AtomicReference<BlockEntity> handedOut = new AtomicReference<>();
            final AtomicReference<BlockEntity> typed = new AtomicReference<>();
            this.runOnNativeWorker(() -> {
                handedOut.set(level.getBlockEntity(HOPPER_POS));
                typed.set(BlockEntityType.HOPPER.getBlockEntity(level, HOPPER_POS));
                answered.set(level.getBlockState(HOPPER_POS).getAnalogOutputSignal(level, HOPPER_POS));
            });
            assertNull(typed.get(), "tick " + tick + ": a typed block-entity lookup refuses the stand-in instead of "
                    + "handing it out as a hopper");

            assertEquals(inline, answered.get(), "tick " + tick
                    + ": the comparator input of the region run has to equal the inline input");
            assertNotSame(hopper, handedOut.get(), "tick " + tick
                    + ": the live block entity of the owning region must never be handed to a worker");
        }

        assertTrue(RegionBlockEntitySnapshots.publishedSignalAnswers() >= 320,
                "every tick of the run has to be answered from the publication, got "
                        + RegionBlockEntitySnapshots.publishedSignalAnswers());
        assertTrue(RegionBlockEntitySnapshots.publishedAnswers() >= 320,
                "every read of the run has to be answered from the publication, got "
                        + RegionBlockEntitySnapshots.publishedAnswers());
        assertEquals(320L, RegionBlockEntitySnapshots.publicationCount());
        assertEquals(1L, RegionBlockEntitySnapshots.publishedEntries(), "the border hopper is the only border block entity");
        assertEquals(0L, this.world.refusedBlockEntityReads(),
                "no read of the run may stay unanswered, got " + this.world.refusedBlockEntityReads());
        assertEquals(0L, this.world.foreignWriteAttempts(),
                "the answered reads must not turn into writes of the published values");
    }

    @Test
    void standInCarriesTheRecordedValuesAndAReadOnlyAnswer() {
        final ServerLevel level = this.newWorld();
        level.setBlock(HOPPER_POS, Blocks.HOPPER.defaultBlockState(), 3);
        final HopperBlockEntity hopper = (HopperBlockEntity) this.world.blockEntities.get(HOPPER_POS);
        hopper.setItem(0, new ItemStack(Items.STONE, 16));
        hopper.setItem(1, new ItemStack(Items.DIRT, 5));
        this.installEngine(level);
        this.world.publishBlockEntitySnapshot();

        final RegionSnapshotBlockEntity standIn = RegionBlockEntitySnapshots.standIn(level, HOPPER_POS);
        assertTrue(standIn != null, "the border hopper has to be published");
        assertEquals(hopper.getBlockPos(), standIn.getBlockPos());
        assertEquals(hopper.getBlockState(), standIn.getBlockState());
        assertSame(hopper.getType(), standIn.getType(), "the stand-in carries the recorded block-entity type");
        assertSame(level, standIn.getLevel(), "the stand-in reports the level the value was published for");
        assertFalse(standIn.isRemoved());
        assertFalse(standIn.triggerEvent(0, 0), "a block event on a stand-in is a no-op");

        final List<ItemStack> contents = standIn.contents();
        assertEquals(2, contents.size(), "the published list holds the non-empty slots");
        assertEquals(16, contents.get(0).getCount());
        assertNotSame(hopper.getItem(0), contents.get(0), "the publication copies the stack, it does not share it");
        hopper.setItem(0, new ItemStack(Items.STONE, 1));
        assertEquals(16, contents.get(0).getCount(), "the published copy does not follow the live container");

        // Every mutation is refused, so a caller that received a stand-in cannot write through it.
        assertThrows(UnsupportedOperationException.class, standIn::setChanged);
        assertThrows(UnsupportedOperationException.class, standIn::setRemoved);
        assertThrows(UnsupportedOperationException.class, standIn::clearRemoved);
        assertThrows(UnsupportedOperationException.class, () -> standIn.setLevel(level));
        assertThrows(UnsupportedOperationException.class, () -> standIn.setBlockState(Blocks.AIR.defaultBlockState()));
        assertThrows(UnsupportedOperationException.class, () -> standIn.saveWithoutMetadata(RegistryAccess.EMPTY));

        // And it is neither a container nor the real block entity, so a mutation or a cast has nothing to reach.
        assertFalse(Container.class.isInstance(standIn), "a container cast of the stand-in has to fail closed");
        assertFalse(hopper.getClass().isInstance(standIn), "a cast to the real block-entity class has to fail closed");
    }

    @Test
    void standInIsOnlyServedForAPublishedBorderBlockEntity() {
        final ServerLevel level = this.newWorld();
        level.setBlock(HOPPER_POS, Blocks.HOPPER.defaultBlockState(), 3);
        level.setBlock(INTERIOR_HOPPER_POS, Blocks.HOPPER.defaultBlockState(), 3);
        this.installEngine(level);
        this.world.publishBlockEntitySnapshot();

        assertEquals(1, RegionBlockEntitySnapshots.of(level).size(),
                "a block entity inside a region is never read across a border and must not be published");
        assertNull(RegionBlockEntitySnapshots.standIn(level, INTERIOR_HOPPER_POS));

        final AtomicReference<BlockEntity> interiorRead = new AtomicReference<>();
        this.runOnNativeWorker(() -> interiorRead.set(level.getBlockEntity(INTERIOR_HOPPER_POS)));
        assertNull(interiorRead.get(), "an unpublished position keeps the null answer of the gate");
        assertEquals(1L, this.world.refusedBlockEntityReads(), "the unanswered read is counted as before");
        assertSame(this.world.blockEntities.get(INTERIOR_HOPPER_POS), level.getBlockEntity(INTERIOR_HOPPER_POS),
                "the server thread keeps reading the live block entity");
    }

    @Test
    void quiescentForeignRegionIsStillReadLive() {
        final ServerLevel level = this.newWorld();
        // Both components need a solid support, or a neighbor update drops them instead of answering its read.
        level.setBlock(CHEST_POS.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(CHEST_COMPARATOR_POS.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(CHEST_POS, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 3);
        level.setBlock(CHEST_COMPARATOR_POS,
                Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 3);
        final Container chest = (Container) this.world.blockEntities.get(CHEST_POS);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.STONE, 64));
        }
        this.installEngine(level);
        this.world.addTickContainer(2, 0);
        this.world.addTickContainer(BORDER_X / 16, 0);

        this.gameTime.set(1L);
        this.world.publishBlockEntitySnapshot();
        assertTrue(RegionBlockEntitySnapshots.of(level).size() >= 1, "the border chest and comparator are published");
        level.scheduleTick(CHEST_COMPARATOR_POS, Blocks.COMPARATOR, 0);

        // The comparator's scheduled tick runs on the worker of its region and reads the chest of the other
        // region, which holds no lease in this slice: the live read is the preferred answer.
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level,
                ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        this.world.blockTicks.tick(1L, 65536,
                (pos, block) -> level.getBlockState(pos).tick(level, pos, this.world.scheduledRandom), dispatcher);

        assertTrue(this.engine.foreignBlockEntityReads() > 0,
                "a quiescent foreign region has to answer a live read, got " + this.engine.foreignBlockEntityReads());
        assertEquals(0L, RegionBlockEntitySnapshots.publishedAnswers(),
                "the publication is the answer of last resort, not the preferred one");
    }

    @Test
    void levelWithoutAPublicationKeepsTheVanillaNullAnswer() {
        final ServerLevel level = this.newWorld();
        level.setBlock(HOPPER_POS, Blocks.HOPPER.defaultBlockState(), 3);

        final AtomicReference<BlockEntity> foreignRead = new AtomicReference<>();
        final Thread reader = new Thread(() -> foreignRead.set(level.getBlockEntity(HOPPER_POS)), "vanilla read");
        reader.start();
        try {
            reader.join(10_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        assertFalse(reader.isAlive(), "the read of the level mock has to finish");
        assertNull(foreignRead.get(), "a level without a publication keeps the vanilla null answer");
        assertNull(RegionThreadingHooks.snapshotBlockEntity(level, HOPPER_POS));
        assertSame(this.world.blockEntities.get(HOPPER_POS), level.getBlockEntity(HOPPER_POS),
                "the server thread keeps reading the live block entity");
    }

    /**
     * Publishes through the production entry point - the loaded chunks the regionizer knows - and reports what
     * the publication costs, so the number in the task report is a measurement and not an estimate.
     * <p>
     * The level mock owns no chunks of its own, so the chunk source answers with one mock chunk per chunk
     * coordinate; the block entities in them are real hoppers. Chunk 1 is inside the fixed 3x3 region, so only
     * the three border chunks contribute an entry.
     */
    @Test
    void productionPublicationCopiesOnlyTheBorderChunksAndReportsItsCost() {
        final ServerLevel level = this.newWorld();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        final BlockState hopperState = Blocks.HOPPER.defaultBlockState();
        // Chunk row 1 is inside the region on the z axis, so only the x axis decides whether a chunk is a border.
        final int chunkZ = 1;
        for (int chunkX = 0; chunkX <= 3; chunkX++) {
            final BlockPos pos = new BlockPos(chunkX * 16 + 8, Y, chunkZ * 16 + 8);
            final LevelChunk chunk = mock(LevelChunk.class);
            when(chunk.getBlockEntities()).thenReturn(Map.of(pos, new HopperBlockEntity(pos, hopperState)));
            chunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
        }
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(invocation -> chunks.get(
                ChunkPos.asLong(((Number) invocation.getArgument(0)).intValue(), ((Number) invocation.getArgument(1)).intValue())));
        doReturn(chunkSource).when(level).getChunkSource();
        this.installEngine(level);
        for (int chunkX = 0; chunkX <= 3; chunkX++) {
            this.engine.addRegionSection(level, chunkX, chunkZ);
        }

        // The first ticks pay for the JIT and for the chunk-source mock warming up, so the reported cost is the
        // mean of the last measured window. No bound is asserted: a slow machine must not fail the suite.
        final int warmupTicks = 1500;
        final int measuredTicks = 500;
        final int ticks = warmupTicks + measuredTicks;
        long measuredNanos = 0L;
        long maxNanos = 0L;
        for (int tick = 1; tick <= ticks; tick++) {
            this.gameTime.set(tick);
            this.engine.beginLevelTick(level);
            this.engine.endLevelTick(level);
            if (tick > warmupTicks) {
                final long nanos = RegionBlockEntitySnapshots.lastPublishNanos();
                measuredNanos += nanos;
                maxNanos = Math.max(maxNanos, nanos);
            }
        }

        assertEquals((long) ticks, RegionBlockEntitySnapshots.publicationCount());
        assertEquals(3L, RegionBlockEntitySnapshots.publishedEntries(),
                "the three border chunks contribute one entry each, the interior chunk is not copied");
        assertTrue(RegionBlockEntitySnapshots.standIn(level, new BlockPos(8, Y, 24)) != null);
        assertNull(RegionBlockEntitySnapshots.standIn(level, new BlockPos(24, Y, 24)),
                "a block entity inside the region is not published");
        System.out.println("block-entity-snapshot-cost,chunks=4,entries=" + RegionBlockEntitySnapshots.publishedEntries()
                + ",measuredTicks=" + measuredTicks
                + ",meanNanos=" + (measuredNanos / measuredTicks)
                + ",maxNanos=" + maxNanos
                + ",engineMeanNanos=" + RegionBlockEntitySnapshots.meanPublishNanos());
    }

    /**
     * Measures the copying half of the publication on container block entities, which is the expensive entry: a
     * signal and a copied item list per block entity. The reported number is the mean of a warm window, and no
     * bound is asserted, so a slow machine cannot fail the suite.
     */
    @Test
    void publicationCostPerBorderContainerIsMeasured() {
        final ServerLevel level = this.newWorld();
        final BlockState hopperState = Blocks.HOPPER.defaultBlockState();
        final List<BlockEntity> hoppers = new java.util.ArrayList<>();
        for (int index = 0; index < 500; index++) {
            final BlockPos pos = new BlockPos(8, Y + index, 8);
            final HopperBlockEntity hopper = new HopperBlockEntity(pos, hopperState);
            hopper.setItem(0, new ItemStack(Items.STONE, 16));
            hopper.setItem(1, new ItemStack(Items.DIRT, 3));
            hoppers.add(hopper);
        }
        RegionBlockEntitySnapshots.reset();
        this.installEngine(level);

        final int warmupPublications = 200;
        final int measuredPublications = 200;
        for (int publication = 0; publication < warmupPublications + measuredPublications; publication++) {
            RegionBlockEntitySnapshots.publish(level, RegionLayout.FIXED_3X3, hoppers);
        }

        assertEquals(500L, RegionBlockEntitySnapshots.publishedEntries());
        assertEquals(1_000L, RegionBlockEntitySnapshots.copiedStacks());
        System.out.println("block-entity-snapshot-entry-cost,entries=500,copiedStacks=1000,measuredPublications="
                + measuredPublications + ",meanNanos=" + RegionBlockEntitySnapshots.meanPublishNanos()
                + ",meanNanosPerEntry=" + (RegionBlockEntitySnapshots.meanPublishNanos() / 500L));
    }

    private ServerLevel newWorld() {
        RegionThreadingManager.clear();
        this.gameTime.set(0L);
        this.world = new RegionRedstoneWorld(this.gameTime);
        return this.world.level;
    }

    private void installEngine(ServerLevel level) {
        final RegionThreadingEngine installed = new RegionThreadingEngine(4, false, false, false, false, true,
                RegionPolicy.FIXED_3X3);
        installed.setBorderUpdatesEnabledForTesting(true);
        installed.setBorderUpdateBudgetForTesting(20000);
        installed.enableUnclassifiedWorkerCallbacksForTesting();
        RegionThreadingManager.install(installed);
        this.engine = installed;
        installed.addRegionSection(level, 2, 0);
        installed.addRegionSection(level, 3, 0);
        installed.addRegionSection(level, -1, 0);
        installed.addRegionSection(level, 0, 0);
        installed.addRegionSection(level, 1, 1);
    }

    /** Runs {@code task} on a native region worker of the installed engine and rethrows its failure. */
    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("region value snapshot test", () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the test task");
        try {
            assertTrue(done.await(20, TimeUnit.SECONDS), "the worker task did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        final Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
    }

    private RegionWorkerExecutor workerPool() {
        try {
            final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
            field.setAccessible(true);
            return (RegionWorkerExecutor) field.get(this.engine);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
