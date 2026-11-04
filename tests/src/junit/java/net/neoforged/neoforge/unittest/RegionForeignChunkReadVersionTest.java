/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the version check of a cross-region chunk read and the fixpoint re-run it triggers.
 * <p>
 * A region worker reads any loaded chunk through the chunk source, including a chunk another region is mutating.
 * Every read of a chunk of another region records the version it observed, and the border fixpoint re-runs the
 * propagation of the reader when that version changed, so a torn or stale read is repaired in the same tick.
 * <p>
 * The tests drive the real slices against a real engine, real {@link LevelChunk}s with real palettes and the real
 * vanilla write path; only the level, its chunk source, its light engine and the scheduled tick callback are
 * mocks. The chunk source records the version of the chunk it hands out exactly like the worker lookup of
 * {@code ServerChunkCache} does, so the recording seam, the read set, the version comparison and the re-run are
 * the production ones.
 */
@Timeout(120)
class RegionForeignChunkReadVersionTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final RegionPolicy FIXED = RegionPolicy.FIXED_3X3;
    /** Last chunk of region (0,0): the region that reads across the border. */
    private static final ChunkPos READER_CHUNK = new ChunkPos(2, 0);
    /** First chunk of region (1,0): the chunk another region mutates under the reader. */
    private static final ChunkPos WRITER_CHUNK = new ChunkPos(3, 0);
    private static final int Y = 64;
    private static final int Z = 0;
    /** Last block of the reader region, next to the border. */
    private static final BlockPos READER_POS = new BlockPos(READER_CHUNK.getMaxBlockX(), Y, Z);
    /** First block of the writer region, one block across the border and adjacent to {@link #READER_POS}. */
    private static final BlockPos WRITER_POS = new BlockPos(WRITER_CHUNK.getMinBlockX(), Y, Z);
    private static final int MIN_BUILD_HEIGHT = 0;
    private static final int LEVEL_HEIGHT = 384;
    private static final String LIT = "lit";
    private static final String UNLIT = "unlit";

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
     * The acceptance test of the feature: the worker of region (0,0) reads a block of region (1,0) while the
     * worker of that region writes it, and the border fixpoint re-runs the reader so the final state is the state
     * the same read produces when it runs after the write.
     */
    @Test
    void aStaleForeignReadIsRerunInTheSameTick() {
        final String sequential = this.sequentialReaderState();
        final Race race = this.runRace(true);
        assertEquals(LIT, race.finalReaderState,
                "the reader has to be re-run after the foreign chunk changed under its read: " + race.diagnostics());
        assertEquals(sequential, race.finalReaderState,
                "the re-run has to reach the single-threaded result of the same read order");
        assertTrue(race.foreignReads() > 0, "the foreign read has to be recorded: " + race.diagnostics());
        assertTrue(race.invalidatedPasses() > 0, "the changed version has to invalidate the pass: " + race.diagnostics());
        assertTrue(race.rerunsByReads() > 0, "the reader region has to be re-run: " + race.diagnostics());
        assertEquals(0L, race.rerunSeedsDropped(), "no seed bound was reached: " + race.diagnostics());
    }

    /**
     * The same interleaving with the version check turned off. It proves the test above fails without the re-run:
     * the reader keeps the state its stale read decided, because the write of the other region notifies no
     * neighbour and the border capture therefore has no update to apply.
     */
    @Test
    void withoutTheVersionCheckTheStaleReadSurvives() {
        final String sequential = this.sequentialReaderState();
        final Race race = this.runRace(false);
        assertEquals(UNLIT, race.finalReaderState,
                "without the version check the reader keeps the state of its stale read: " + race.diagnostics());
        assertNotEquals(sequential, race.finalReaderState,
                "the stale state has to differ from the state the read order of the single-threaded run produces");
        assertTrue(race.foreignReads() > 0, "the read is still recorded when the check is off: " + race.diagnostics());
        assertEquals(0L, race.rerunsByReads(), "the disabled check must not re-run anything: " + race.diagnostics());
    }

    /** A region that only reads its own chunks records nothing and re-runs nothing. */
    @Test
    void readsInsideOneRegionRecordNothingAndRerunNothing() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        chunks.put(READER_CHUNK.toLong(), realChunk(level, READER_CHUNK));
        serveChunks(level, chunks);
        this.installEngine(level, true);
        final AtomicInteger ticked = new AtomicInteger();
        // A position in the middle of the chunk: every neighbour of it and every shape update it sends stays in
        // the same chunk, so the write cannot reach a border at all.
        final BlockPos ownPos = new BlockPos(READER_CHUNK.getMinBlockX() + 8, Y, 8);
        final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        blockTicks.addContainer(READER_CHUNK, new LevelChunkTicks<>());
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, ownPos, 1L, TickPriority.NORMAL, 0L));

        blockTicks.tick(1L, 65536, (pos, block) -> {
            ticked.incrementAndGet();
            // A read and a write of the region's own chunk: no version is recorded, so nothing can invalidate.
            level.getBlockState(ownPos);
            level.setBlock(ownPos, Blocks.DIRT.defaultBlockState(), 2);
        }, this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER));

        assertEquals(1, ticked.get(), "the tick has to run");
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertEquals(0L, counters.foreignReads(), "a read inside the region is not a foreign read: " + counters);
        assertEquals(0L, counters.foreignReadsWithoutVersion(), "no read had to be left unverified: " + counters);
        assertEquals(0L, counters.invalidatedPasses(), "a quiet region invalidates nothing: " + counters);
        assertEquals(0L, counters.regionsRerunByReads(), "a quiet region is not re-run: " + counters);
        assertEquals(0L, counters.rerunSeedsDropped(), "no seed bound was reached: " + counters);
        assertEquals(0L, counters.passes(), "the fixpoint has nothing to do: " + counters);
        assertTrue(level.getBlockState(ownPos).is(Blocks.DIRT), "the write of the tick has to be visible");
    }

    /**
     * A region whose re-run keeps invalidating itself stops at the fixpoint bound and hands the rest of its
     * propagation to the mailbox, so the server does not spin and no update is lost.
     */
    @Test
    void repeatedInvalidationStopsAtTheBoundAndDeliversTheRestThroughTheMailbox() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        chunks.put(READER_CHUNK.toLong(), realChunk(level, READER_CHUNK));
        chunks.put(WRITER_CHUNK.toLong(), realChunk(level, WRITER_CHUNK));
        serveChunks(level, chunks);
        this.installEngine(level, true);
        this.engine.setBorderUpdateBudgetForTesting(3);
        final AtomicInteger reevaluations = new AtomicInteger();
        final AtomicReference<String> reevaluationThread = new AtomicReference<>();
        final AtomicInteger written = new AtomicInteger();
        // The block of the reader region re-decides from the foreign chunk on every neighbour update and writes
        // that chunk again, so every re-run changes the version the re-run just recorded and the region can never
        // settle. The fixpoint has to stop at its bound and hand the rest to the mailbox.
        final BlockState readerState = mock(BlockState.class);
        doAnswer(invocation -> {
            reevaluations.incrementAndGet();
            reevaluationThread.compareAndSet(null, Thread.currentThread().getName());
            level.getBlockState(WRITER_POS);
            // Flags 18: the client update only, so the write notifies no neighbour and sends no shape update; the
            // re-run changes the version of the foreign chunk and nothing else.
            level.setBlock(WRITER_POS, (written.getAndIncrement() & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 18);
            return null;
        }).when(readerState).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        doReturn(readerState).when(level).getBlockState(READER_POS);

        final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        blockTicks.addContainer(WRITER_CHUNK, new LevelChunkTicks<>());
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, WRITER_POS, 1L, TickPriority.NORMAL, 0L));
        // Flags 19: the neighbour update of the write, without a shape update, so the neighbour update of
        // READER_POS is the one update the fixpoint captures for the reader region.
        blockTicks.tick(1L, 65536, (pos, block) -> level.setBlock(WRITER_POS, Blocks.ANDESITE.defaultBlockState(), 19),
                this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER));

        assertTrue(reevaluations.get() >= 1, "the reader region has to be re-run at least once");
        assertTrue(reevaluationThread.get().startsWith("NeoForge Region Worker"),
                () -> "the re-run has to run on the region worker: " + reevaluationThread.get());
        final RegionThreadingDiagnostics.BorderUpdateCounters counters = this.engine.diagnostics().borderUpdates();
        assertTrue(counters.passes() >= 2L, "the region has to be re-run at least once: " + counters);
        assertTrue(counters.passes() <= 3L, "the fixpoint has to stop at its bound: " + counters);
        assertEquals(counters.passes(), counters.worstPasses(), "the worst pass count is this fixpoint: " + counters);
        assertEquals(counters.passes(), counters.invalidatedPasses(),
                "every pass observes a changed version, so the region never settles: " + counters);
        assertEquals(counters.invalidatedPasses(), counters.regionsRerunByReads(),
                "every invalidated pass has to re-run the region: " + counters);
        assertTrue(counters.delayed() > 0,
                "the propagation the bound refused has to be handed to the mailbox: " + counters);
        assertTrue(this.engine.diagnostics().mailboxAcceptedMessages() > 0,
                "the refused propagation has to be a mailbox message: " + this.engine.diagnostics());
    }

    /**
     * The version a read observed proves every stale read of a concurrently written chunk.
     * <p>
     * A writer thread mutates one position of a real chunk through the vanilla palette write while a reader reads
     * the position and the chunk version in the order the chunk source hands them to a worker. Every value the
     * reader sees is one of the two states the writer alternates between - the palette never yields a mixture -
     * and every value that is not the final state comes with a version that is not the final version, so the
     * check re-runs exactly the reads that have to be re-run.
     */
    @Test
    void theVersionProvesEveryStaleReadOfAConcurrentlyWrittenChunk() {
        final ServerLevel level = this.mockLevel();
        final LevelChunk chunk = realChunk(level, READER_CHUNK);
        final BlockPos pos = new BlockPos(READER_CHUNK.getMinBlockX() + 3, Y, Z);
        // The reader reads the palette of the section directly: a level read would reach the level mock from a
        // second thread, and the test is about the palette and the version, not about the level.
        final LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(Y));
        final int localX = pos.getX() & 15;
        final int localY = Y & 15;
        final int localZ = pos.getZ() & 15;
        // Both states are opaque: the heightmap of the write then never reads the column back, so the writer
        // thread only touches the palette and the version of the chunk.
        final BlockState dirt = Blocks.DIRT.defaultBlockState();
        final BlockState stone = Blocks.STONE.defaultBlockState();
        // Settle the palette on the two states first, so the race cannot observe a palette growth.
        chunk.setBlockState(pos, stone, false);
        chunk.setBlockState(pos, dirt, false);
        final int iterations = 5_000;
        final int maxSamples = 100_000;
        final long[] versions = new long[maxSamples];
        final BlockState[] states = new BlockState[maxSamples];
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong writerDone = new AtomicLong();

        final Thread writer = new Thread(() -> {
            try {
                for (int index = 0; index < iterations; index++) {
                    chunk.setBlockState(pos, (index & 1) == 0 ? stone : dirt, false);
                    writerDone.set(index + 1L);
                }
                chunk.setBlockState(pos, stone, false);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                writerDone.set(Long.MAX_VALUE);
            }
        }, "chunk-writer");
        writer.start();
        int samples = 0;
        while (samples < maxSamples && writerDone.get() != Long.MAX_VALUE) {
            versions[samples] = chunk.chunkVersion();
            states[samples] = section.getBlockState(localX, localY, localZ);
            samples++;
        }
        try {
            writer.join(TimeUnit.SECONDS.toMillis(60));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        assertFalse(writer.isAlive(), "the writer thread has to finish");
        if (failure.get() != null) {
            throw new AssertionError("the concurrent write failed", failure.get());
        }
        assertTrue(samples > 1, "the reader has to observe the chunk during the writes");
        final long finalVersion = chunk.chunkVersion();
        final BlockState finalState = section.getBlockState(localX, localY, localZ);
        assertEquals(stone, finalState, "the last write of the writer is the state of the chunk");
        int staleReads = 0;
        for (int index = 0; index < samples; index++) {
            final BlockState observed = states[index];
            assertTrue(observed.is(Blocks.DIRT) || observed.is(Blocks.STONE),
                    () -> "a read of a concurrently written palette saw a mixture: " + observed);
            if (observed != finalState) {
                staleReads++;
                assertNotEquals(finalVersion, versions[index],
                        "a read that saw an older state has to carry an older version, so the re-run fires");
            }
        }
        assertTrue(staleReads > 0, "the reader has to hit the write window at least once, samples=" + samples);
    }

    /** The version of a chunk is bumped by a block state write, a heightmap write and a block entity change. */
    @Test
    void everyChunkMutationBumpsTheVersion() {
        final ServerLevel level = this.mockLevel();
        final LevelChunk chunk = realChunk(level, READER_CHUNK);
        final BlockPos pos = new BlockPos(READER_CHUNK.getMinBlockX() + 1, Y, Z);
        assertEquals(0L, chunk.chunkVersion(), "a fresh chunk starts at version zero");

        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
        final long afterBlock = chunk.chunkVersion();
        assertTrue(afterBlock > 0L, "a block state write has to bump the version");

        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
        assertEquals(afterBlock, chunk.chunkVersion(), "a write that changes nothing must not bump the version");

        chunk.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        final long afterBlockEntity = chunk.chunkVersion();
        assertTrue(afterBlockEntity > afterBlock, "a write that creates a block entity has to bump the version");

        chunk.removeBlockEntity(pos);
        assertTrue(chunk.chunkVersion() > afterBlockEntity, "a block entity removal has to bump the version");
    }

    // ---------------------------------------------------------------------------------------------------------
    // the deterministic race
    // ---------------------------------------------------------------------------------------------------------

    /** One reader that reads across the border and one writer that mutates the chunk it read, in the same tick. */
    private Race runRace(boolean versionCheck) {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        chunks.put(READER_CHUNK.toLong(), realChunk(level, READER_CHUNK));
        chunks.put(WRITER_CHUNK.toLong(), realChunk(level, WRITER_CHUNK));
        serveChunks(level, chunks);
        this.installEngine(level, versionCheck);
        final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        blockTicks.addContainer(READER_CHUNK, new LevelChunkTicks<>());
        blockTicks.addContainer(WRITER_CHUNK, new LevelChunkTicks<>());
        final Race race = new Race(level);
        // The reader runs first and reads the foreign block before the writer wrote it; the writer then writes the
        // foreign chunk with flags 2, i.e. without a neighbour update, so the only repair of the reader's stale
        // state is the version check. Both ticks are scheduled for the same tick, and the latches make the
        // interleaving deterministic instead of hoping for the window.
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, READER_POS, 1L, TickPriority.NORMAL, 0L));
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, WRITER_POS, 1L, TickPriority.NORMAL, 1L));
        blockTicks.tick(1L, 65536, race::tick, this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER));
        race.readFinalState();
        return race;
    }

    /**
     * The single-threaded reference: the writer's tick runs before the reader's, so the reader reads the state the
     * writer wrote and decides its own state from it. That is the order the fixpoint guarantees by re-running the
     * reader after the pass that applied the write.
     */
    private String sequentialReaderState() {
        final ServerLevel level = this.mockLevel();
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        chunks.put(READER_CHUNK.toLong(), realChunk(level, READER_CHUNK));
        chunks.put(WRITER_CHUNK.toLong(), realChunk(level, WRITER_CHUNK));
        serveChunks(level, chunks);
        this.installEngine(level, true);
        final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        blockTicks.addContainer(READER_CHUNK, new LevelChunkTicks<>());
        blockTicks.addContainer(WRITER_CHUNK, new LevelChunkTicks<>());
        final Race race = new Race(level);
        race.sequential = true;
        // The writer has the lower sequence, so the vanilla inline loop ticks it first.
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, WRITER_POS, 1L, TickPriority.NORMAL, 0L));
        blockTicks.schedule(new ScheduledTick<>(Blocks.STONE, READER_POS, 1L, TickPriority.NORMAL, 1L));
        blockTicks.tick(1L, 65536, race::tick);
        race.readFinalState();
        this.engine.shutdown();
        this.engine = null;
        RegionThreadingManager.clear();
        return race.finalReaderState;
    }

    /** One race of the reader and the writer of {@link RegionForeignChunkReadVersionTest}. */
    private final class Race {
        private final ServerLevel level;
        private final CountDownLatch read = new CountDownLatch(1);
        private final CountDownLatch written = new CountDownLatch(1);
        private final AtomicReference<BlockState> foreignRead = new AtomicReference<>();
        private final AtomicReference<String> readerThread = new AtomicReference<>();
        private final AtomicReference<String> writerThread = new AtomicReference<>();
        private String finalReaderState;
        private boolean sequential;

        private Race(ServerLevel level) {
            this.level = level;
        }

        /**
         * The scheduled tick body of both regions. The reader decides the lit state of the lamp it places from the
         * foreign block it read; the writer writes that foreign block without notifying a neighbour.
         */
        private void tick(BlockPos pos, Block block) {
            if (pos.equals(READER_POS)) {
                this.readerThread.compareAndSet(null, Thread.currentThread().getName());
                final BlockState foreign = this.level.getBlockState(WRITER_POS);
                this.foreignRead.set(foreign);
                if (!this.sequential) {
                    this.read.countDown();
                    awaitOrFail(this.written, "the writer tick has to write the foreign block");
                }
                // The decision the read justifies: the lamp is placed with the lit state of the state the read
                // observed, which is the stale one when the writer wrote the chunk in between.
                this.level.setBlock(READER_POS, lamp(foreign.is(Blocks.REDSTONE_BLOCK)), 2);
            } else if (pos.equals(WRITER_POS)) {
                this.writerThread.compareAndSet(null, Thread.currentThread().getName());
                if (!this.sequential) {
                    awaitOrFail(this.read, "the reader tick has to read the foreign block first");
                }
                // Flags 2: the client update only, so no neighbour update reaches the reader and the border
                // capture has nothing to apply. Only the version check can repair the reader's state.
                this.level.setBlock(WRITER_POS, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
                this.written.countDown();
            }
        }

        private void readFinalState() {
            final BlockState state = this.level.getBlockState(READER_POS);
            this.finalReaderState = state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT) ? LIT : UNLIT;
        }

        private long foreignReads() {
            return this.counters().foreignReads();
        }

        private long invalidatedPasses() {
            return this.counters().invalidatedPasses();
        }

        private long rerunsByReads() {
            return this.counters().regionsRerunByReads();
        }

        private long rerunSeedsDropped() {
            return this.counters().rerunSeedsDropped();
        }

        private RegionThreadingDiagnostics.BorderUpdateCounters counters() {
            return RegionForeignChunkReadVersionTest.this.engine.diagnostics().borderUpdates();
        }

        private String diagnostics() {
            return this.counters() + ", reader=" + this.readerThread.get() + ", writer=" + this.writerThread.get()
                    + ", foreignRead=" + this.foreignRead.get() + ", state=" + this.finalReaderState;
        }
    }

    private static BlockState lamp(boolean lit) {
        return Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit);
    }

    // ---------------------------------------------------------------------------------------------------------
    // the level, its chunk source and the engine
    // ---------------------------------------------------------------------------------------------------------

    private void installEngine(ServerLevel level, boolean versionCheck) {
        final RegionThreadingEngine created = new RegionThreadingEngine(4, false, false, false, false, true, FIXED);
        created.setBorderUpdatesEnabledForTesting(true);
        created.enableUnclassifiedWorkerCallbacksForTesting();
        created.setForeignReadRerunEnabledForTesting(versionCheck);
        RegionThreadingManager.install(created);
        this.engine = created;
        for (int chunkX = 0; chunkX <= 5; chunkX++) {
            created.addRegionSection(level, chunkX, 0);
        }
    }

    /**
     * {@return a level that runs the real vanilla write path and serves its chunks from the test}
     * <p>
     * The mock runs the real methods, so the write, the block update publication and the neighbour updates are the
     * production ones. The fields the vanilla constructor would have filled are installed explicitly, because a
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
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickLightning(any());
        doReturn(1000000).when(level).maxChainedNeighborUpdates();
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(3);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        @SuppressWarnings("unchecked")
        final Registry<Biome> biomes = mock(Registry.class);
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        when(registryAccess.registryOrThrow(Registries.BIOME)).thenReturn(biomes);
        doReturn(registryAccess).when(level).registryAccess();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
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
        setLevelField(level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "freshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "pendingFreshBlockEntities", new ArrayList<BlockEntity>());
        setLevelField(level, "blockEntityTickerLock", new Object());
        setLevelField(level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setLevelField(level, "blockEntityTickerChunks",
                new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<LevelChunk>());
        setLevelField(level, "blockEntityTickerSequence", new AtomicLong());
        return level;
    }

    /**
     * Makes the chunk source answer the block reads and writes of the tick bodies from {@code chunks}.
     * <p>
     * The {@code getChunk} answer records the version of the chunk it hands out, exactly like the worker chunk
     * lookup of the production chunk source does, so the test exercises the recording seam of a foreign read.
     * {@code getChunkNow} is the server-thread lookup the version check uses and records nothing.
     */
    private static void serveChunks(ServerLevel level, Map<Long, LevelChunk> chunks) {
        final ServerChunkCache source = level.getChunkSource();
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            final LevelChunk chunk = chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ),
                    key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
            // The production call of ServerChunkCache.getChunkForRegionWorker.
            RegionThreadingHooks.recordForeignChunkRead(level, chunkX, chunkZ, chunk);
            return chunk;
        }).when(source).getChunk(anyInt(), anyInt(), any(ChunkStatus.class), anyBoolean());
        doAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return chunks.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> realChunk(level, new ChunkPos(chunkX, chunkZ)));
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
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
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

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }
}
