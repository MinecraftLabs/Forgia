/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the bounded scheduled block/fluid tick slice against the real engine, the real
 * {@link LevelTicks} collection and the real block tick hook.
 * <p>
 * The tests drive {@link LevelTicks#tick(long, int, BiConsumer, LevelTicks.TickDispatcher)} with the
 * dispatcher the engine creates, so collection, budget, de-duplication and the per-tick bookkeeping run
 * for real while only the level is a mock.
 */
@Timeout(30)
class RegionScheduledTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void dispatcherIsDisabledWhenScheduledTickWorkersAreOff() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER));
        assertFalse(this.engine.diagnostics().scheduledTickWorkerExecutionEnabled());
    }

    @Test
    void configuredScheduledTickWorkersFallBackToTheVanillaInlinePath() {
        // The production constructor never enables the mechanics seam, so a configured worker slice must
        // report the request but keep the phase on the server thread.
        this.engine = new RegionThreadingEngine(2, false, false, false, false, true, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Block> dispatcher = RegionThreadingHooks.scheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNull(dispatcher, "unclassified production scheduled callbacks must stay on the vanilla inline path");
        ticks.tick(10L, 65536, (tickPos, tickType) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(tickPos);
        }, dispatcher);

        assertEquals(List.of(pos), ran);
        assertEquals(List.of(Thread.currentThread().getName()), threads);
        assertTrue(this.engine.diagnostics().scheduledTickWorkerExecutionEnabled(), "the requested configuration must stay visible");
        assertFalse(this.engine.diagnostics().scheduledTickWorkerDispatchEffective(), "the effective dispatch must stay gated");
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackScheduledTicks());
    }

    @Test
    void rejectedWorkerCallbackIsNotReplayedAndTheBatchCompletes() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger sideEffects = new AtomicInteger();
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> ticks.tick(10L, 65536, (tickPos, tickType) -> {
            executions.incrementAndGet();
            sideEffects.incrementAndGet();
            // Admission of a server entity from a native worker is rejected before any registration.
            level.addFreshEntity(mock(Entity.class));
        }, dispatcher));

        assertTrue(thrown.getMessage().contains("ServerLevel.addEntity"), thrown.getMessage());
        assertEquals(1, executions.get(), "the rejected callback must run exactly once");
        assertEquals(1, sideEffects.get(), "the side effect before the rejection must not be repeated");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
        this.engine.shutdown();
        this.engine = null;
    }

    @Test
    void disabledSliceRunsCollectedTicksInlineLikeVanilla() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Block> dispatcher = RegionThreadingHooks.scheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNull(dispatcher);
        ticks.tick(10L, 65536, (tickPos, tickType) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(tickPos);
        }, dispatcher);

        assertEquals(List.of(pos), ran);
        assertEquals(1, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void collectedBlockTickRunsOnceOnARegionWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(tickPos);
        });

        assertEquals(List.of(pos), ran);
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(1L, this.engine.diagnostics().scheduledTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().skippedScheduledTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void collectedFluidTickRunsOnARegionWorker() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Fluid> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Fluid type = mock(Fluid.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final List<String> threads = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Fluid> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.FLUID, PARKING_BARRIER);
        assertNotNull(dispatcher);
        ticks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> threads.add(Thread.currentThread().getName())), dispatcher);

        assertEquals(1, threads.size());
        assertTrue(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(1L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void ticksOfDifferentRegionsRunInParallel() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        addContainer(ticks, 16, 16);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(257, 64, 257), 10L, 1L));
        final CyclicBarrier rendezvous = new CyclicBarrier(2);

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            try {
                rendezvous.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        // The rendezvous only opens when both regions are inside their worker tick at the same time.
        assertEquals(2L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void ticksOfTheSameRegionRunSeriallyInSnapshotOrder() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<BlockPos> order = new CopyOnWriteArrayList<>();

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            order.add(tickPos);
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        });

        assertEquals(1, maxActive.get(), "one region must never tick against itself");
        assertEquals(List.of(first, second), order);
    }

    @Test
    void blockWorkersScheduleFluidTicksAcrossRegionsWithoutEarlyExecution() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> blockTicks = newTicks();
        final LevelTicks<Fluid> fluidTicks = newTicks();
        addContainer(blockTicks, 0, 0);
        addContainer(blockTicks, 16, 16);
        final ThreadRecordingChunkTicks<Fluid> firstFluidContainer = new ThreadRecordingChunkTicks<>();
        final ThreadRecordingChunkTicks<Fluid> secondFluidContainer = new ThreadRecordingChunkTicks<>();
        fluidTicks.addContainer(new ChunkPos(0, 0), firstFluidContainer);
        fluidTicks.addContainer(new ChunkPos(16, 16), secondFluidContainer);
        final Block blockType = mock(Block.class);
        final Fluid fluidType = mock(Fluid.class);
        final BlockPos firstBlockPos = new BlockPos(1, 64, 1);
        final BlockPos secondBlockPos = new BlockPos(257, 64, 257);
        final BlockPos firstFluidPos = new BlockPos(2, 64, 2);
        final BlockPos secondFluidPos = new BlockPos(258, 64, 258);
        blockTicks.schedule(new ScheduledTick<>(blockType, firstBlockPos, 10L, 0L));
        blockTicks.schedule(new ScheduledTick<>(blockType, secondBlockPos, 10L, 1L));
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final List<String> blockThreads = new CopyOnWriteArrayList<>();
        final List<BlockPos> fluidRan = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Block> blockDispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(blockDispatcher);
        blockTicks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            blockThreads.add(Thread.currentThread().getName());
            awaitRendezvous(rendezvous);
            if (tickPos.equals(firstBlockPos)) {
                // Both region workers enqueue into the other LevelTicks instance concurrently.
                fluidTicks.schedule(new ScheduledTick<>(fluidType, firstFluidPos, 10L, 0L));
            } else {
                fluidTicks.schedule(new ScheduledTick<>(fluidType, secondFluidPos, 11L, 1L));
            }
        }), blockDispatcher);

        assertEquals(2, blockThreads.size());
        assertTrue(blockThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), blockThreads.toString());
        assertEquals(0, fluidRan.size(), "scheduling a fluid tick from a block worker must not execute it in the block phase");
        assertTrue(fluidTicks.hasScheduledTick(firstFluidPos, fluidType));
        assertTrue(fluidTicks.hasScheduledTick(secondFluidPos, fluidType));
        assertEquals(10L, firstFluidContainer.peek().triggerTick(), "the original trigger has to survive the cross-phase queue");
        assertEquals(11L, secondFluidContainer.peek().triggerTick());
        assertEquals(List.of(Thread.currentThread().getName()), firstFluidContainer.scheduleThreads(),
                "a block worker must enqueue the fluid mutation for the server thread");
        assertEquals(List.of(Thread.currentThread().getName()), secondFluidContainer.scheduleThreads());

        final LevelTicks.TickDispatcher<Fluid> fluidDispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.FLUID, PARKING_BARRIER);
        assertNotNull(fluidDispatcher);
        final List<String> fluidThreads = new CopyOnWriteArrayList<>();
        final BiConsumer<BlockPos, Fluid> fluidCallback = (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            fluidThreads.add(Thread.currentThread().getName());
            fluidRan.add(tickPos);
        });
        fluidTicks.tick(10L, 65536, fluidCallback, fluidDispatcher);

        assertEquals(List.of(firstFluidPos), fluidRan, "a fluid tick scheduled for the current tick runs in the following fluid phase");
        assertTrue(fluidThreads.get(0).startsWith("NeoForge Region Worker"), fluidThreads.toString());
        assertTrue(fluidTicks.hasScheduledTick(secondFluidPos, fluidType));
        assertEquals(11L, secondFluidContainer.peek().triggerTick());

        fluidTicks.tick(11L, 65536, fluidCallback, fluidDispatcher);

        assertEquals(List.of(firstFluidPos, secondFluidPos), fluidRan);
    }

    @Test
    void fluidWorkersScheduleBlockTicksAcrossRegionsWithoutEarlyExecution() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Fluid> fluidTicks = newTicks();
        final LevelTicks<Block> blockTicks = newTicks();
        addContainer(fluidTicks, 0, 0);
        addContainer(fluidTicks, 16, 16);
        final ThreadRecordingChunkTicks<Block> firstBlockContainer = new ThreadRecordingChunkTicks<>();
        final ThreadRecordingChunkTicks<Block> secondBlockContainer = new ThreadRecordingChunkTicks<>();
        blockTicks.addContainer(new ChunkPos(0, 0), firstBlockContainer);
        blockTicks.addContainer(new ChunkPos(16, 16), secondBlockContainer);
        final Fluid fluidType = mock(Fluid.class);
        final Block blockType = mock(Block.class);
        final BlockPos firstFluidPos = new BlockPos(1, 64, 1);
        final BlockPos secondFluidPos = new BlockPos(257, 64, 257);
        final BlockPos firstBlockPos = new BlockPos(2, 64, 2);
        final BlockPos secondBlockPos = new BlockPos(258, 64, 258);
        fluidTicks.schedule(new ScheduledTick<>(fluidType, firstFluidPos, 10L, 0L));
        fluidTicks.schedule(new ScheduledTick<>(fluidType, secondFluidPos, 10L, 1L));
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final List<String> fluidThreads = new CopyOnWriteArrayList<>();
        final List<BlockPos> blockRan = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Fluid> fluidDispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.FLUID, PARKING_BARRIER);
        assertNotNull(fluidDispatcher);
        fluidTicks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            fluidThreads.add(Thread.currentThread().getName());
            awaitRendezvous(rendezvous);
            if (tickPos.equals(firstFluidPos)) {
                // Both region workers enqueue into the LevelTicks instance whose phase already passed.
                blockTicks.schedule(new ScheduledTick<>(blockType, firstBlockPos, 10L, 0L));
            } else {
                blockTicks.schedule(new ScheduledTick<>(blockType, secondBlockPos, 11L, 1L));
            }
        }), fluidDispatcher);

        assertEquals(2, fluidThreads.size());
        assertTrue(fluidThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), fluidThreads.toString());
        assertEquals(0, blockRan.size(), "scheduling a block tick from a fluid worker must not execute it in the fluid phase");
        assertTrue(blockTicks.hasScheduledTick(firstBlockPos, blockType));
        assertTrue(blockTicks.hasScheduledTick(secondBlockPos, blockType));
        assertEquals(10L, firstBlockContainer.peek().triggerTick(), "the original trigger has to survive the cross-phase queue");
        assertEquals(11L, secondBlockContainer.peek().triggerTick());
        assertEquals(List.of(Thread.currentThread().getName()), firstBlockContainer.scheduleThreads(),
                "a fluid worker must enqueue the block mutation for the server thread");
        assertEquals(List.of(Thread.currentThread().getName()), secondBlockContainer.scheduleThreads());

        final LevelTicks.TickDispatcher<Block> blockDispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(blockDispatcher);
        final List<String> blockThreads = new CopyOnWriteArrayList<>();
        final BiConsumer<BlockPos, Block> blockCallback = (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            blockThreads.add(Thread.currentThread().getName());
            blockRan.add(tickPos);
        });
        blockTicks.tick(11L, 65536, blockCallback, blockDispatcher);

        // Different regions tick in parallel, so only the set of executed ticks is deterministic.
        assertEquals(2, blockRan.size());
        assertTrue(blockRan.containsAll(List.of(firstBlockPos, secondBlockPos)), blockRan.toString());
        assertEquals(2, blockThreads.size());
        assertTrue(blockThreads.stream().allMatch(name -> name.startsWith("NeoForge Region Worker")), blockThreads.toString());
    }

    @Test
    void collectionBudgetAndDeduplicationArePreserved() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        // Scheduling the same type at the same position twice must be de-duplicated by the container.
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 1L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 2L));
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);
        ticks.tick(10L, 1, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> ran.add(tickPos)), dispatcher);

        assertEquals(List.of(first), ran, "the budget must only select one tick and the duplicate must be dropped");
        assertFalse(ticks.hasScheduledTick(first, type));
        assertTrue(ticks.hasScheduledTick(second, type), "the tick over budget has to stay scheduled");

        ticks.tick(11L, 1, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> ran.add(tickPos)), dispatcher);

        assertEquals(List.of(first, second), ran);
    }

    @Test
    void tickScheduledByACallbackIsNotExecutedByTheSameBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos original = new BlockPos(1, 64, 1);
        final BlockPos now = new BlockPos(2, 64, 2);
        final BlockPos later = new BlockPos(3, 64, 3);
        ticks.schedule(new ScheduledTick<>(type, original, 10L, 0L));
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();
        final AtomicBoolean scheduled = new AtomicBoolean();

        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);
        final BiConsumer<BlockPos, Block> callback = (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            ran.add(tickPos);
            if (scheduled.compareAndSet(false, true)) {
                ticks.schedule(new ScheduledTick<>(tickType, now, 10L, 1L));
                ticks.schedule(new ScheduledTick<>(tickType, later, 15L, 2L));
            }
        });
        ticks.tick(10L, 65536, callback, dispatcher);

        assertEquals(List.of(original), ran, "a tick scheduled by a callback must not run in the same batch");
        assertTrue(ticks.hasScheduledTick(now, type), "the callback-scheduled tick has to stay in the scheduling system");
        assertTrue(ticks.hasScheduledTick(later, type));

        ticks.tick(11L, 65536, callback, dispatcher);

        assertEquals(List.of(original, now), ran, "the tick scheduled for the current game time runs on the next tick");
        assertTrue(ticks.hasScheduledTick(later, type));

        ticks.tick(15L, 65536, callback, dispatcher);

        assertEquals(List.of(original, now, later), ran);
    }

    /**
     * A tick's callback has to see the collected ticks of this tick that have not run yet, exactly like the
     * inline loop does.
     * <p>
     * Vanilla removes a collected tick from the will-tick set immediately before that tick's own callback runs,
     * so a running tick answers {@code false} for itself, {@code true} for another tick of this tick and
     * {@code false} for a tick of a later tick. Redstone blocks consult the predicate to decide whether to
     * schedule a follow-up tick, so a dispatcher that cleared the whole set before any worker ran made every
     * second notification in a tick schedule a tick that vanilla suppresses.
     */
    @Test
    void runningDispatchedTickSeesTheTicksOfThisTickThatHaveNotRunYet() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);

        assertEquals("self=false,queued=true,later=false", willTickAnswersFromTheFirstTickOf(dispatcher));
    }

    /**
     * {@return the answers the callback of the first of two ticks of this tick observes for itself, for the
     * other tick of this tick and for a tick of the next tick}
     * <p>
     * The ticks are scheduled in the opposite order of their sub-tick order, so the inspected tick is not the
     * first one the container returns. {@code dispatcher} is {@code null} for the vanilla inline loop.
     */
    private static String willTickAnswersFromTheFirstTickOf(@Nullable LevelTicks.TickDispatcher<Block> dispatcher) {
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block running = mock(Block.class);
        final Block queued = mock(Block.class);
        final Block later = mock(Block.class);
        final BlockPos runningPos = new BlockPos(1, 64, 1);
        final BlockPos queuedPos = new BlockPos(2, 64, 2);
        final BlockPos laterPos = new BlockPos(3, 64, 3);
        ticks.schedule(new ScheduledTick<>(queued, queuedPos, 10L, 2L));
        ticks.schedule(new ScheduledTick<>(running, runningPos, 10L, 1L));
        ticks.schedule(new ScheduledTick<>(later, laterPos, 11L, 0L));
        final List<String> answers = new CopyOnWriteArrayList<>();
        final BiConsumer<BlockPos, Block> callback = (tickPos, tickType) -> {
            if (tickPos.equals(runningPos)) {
                answers.add("self=" + ticks.willTickThisTick(runningPos, running)
                        + ",queued=" + ticks.willTickThisTick(queuedPos, queued)
                        + ",later=" + ticks.willTickThisTick(laterPos, later));
            }
        };

        ticks.tick(10L, 65536, callback, dispatcher);

        assertEquals(1, answers.size(), "the inspected tick has to run exactly once, on the server thread or on a worker");
        return answers.get(0);
    }

    @Test
    void runningInlineTickSeesTheSameTicksAsADispatchedOne() {
        assertNull(RegionThreadingHooks.scheduledTickDispatcher(mockLevel(), ScheduledTickPhase.BLOCK, PARKING_BARRIER),
                "the inline path must not have a dispatcher");

        assertEquals("self=false,queued=true,later=false", willTickAnswersFromTheFirstTickOf(null));
    }

    @Test
    void scheduledBlockTickUsesTheRegionRandomInsteadOfTheSharedLevelRandom() throws ReflectiveOperationException {
        this.installEngine(true);
        final RandomSource levelRandom = RandomSource.create(1234L);
        final RandomSource reference = RandomSource.create(1234L);
        final ServerLevel level = mockLevel(new RegionAwareRandomSource(levelRandom));
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        addContainer(ticks, 16, 16);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(257, 64, 257);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));
        final BlockState firstState = mock(BlockState.class);
        final BlockState secondState = mock(BlockState.class);
        when(firstState.is(type)).thenReturn(true);
        when(secondState.is(type)).thenReturn(true);
        final List<RandomSource> randoms = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            randoms.add(invocation.getArgument(2));
            return null;
        }).when(firstState).tick(any(), any(), any());
        doAnswer(invocation -> {
            randoms.add(invocation.getArgument(2));
            return null;
        }).when(secondState).tick(any(), any(), any());
        doReturn(firstState).when(level).getBlockState(first);
        doReturn(secondState).when(level).getBlockState(second);
        final long before = levelRandom.nextLong();
        assertEquals(reference.nextLong(), before);

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            try {
                invokeTickBlock(level, tickPos, tickType);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
            // Reading the level random directly, as block code may do, must resolve to the region random.
            level.random.nextLong();
        });

        assertEquals(2, randoms.size());
        assertFalse(randoms.stream().anyMatch(random -> random == levelRandom), "a worker must not draw from the shared level random");
        assertEquals(2, randoms.stream().distinct().count(), "every region has to own its random source");
        assertNull(RegionThreadingHooks.scheduledTickRandom(), "the random source must only be visible inside the worker group");
        // The batch derives one seed from the level random on the server thread; the two worker draws
        // above must not advance the shared random any further.
        reference.nextLong();
        assertEquals(reference.nextLong(), levelRandom.nextLong(), "worker random draws must not advance the shared level random");
    }

    @Test
    void sameRegionSharesOneRandomSourceWithinTheBatch() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(2, 64, 2), 10L, 1L));
        final List<RandomSource> randoms = new CopyOnWriteArrayList<>();

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            final RandomSource random = RegionThreadingHooks.scheduledTickRandom();
            assertNotNull(random);
            randoms.add(random);
        });

        assertEquals(2, randoms.size());
        assertSame(randoms.get(0), randoms.get(1));
    }

    @Test
    void failedScheduledTickReleasesTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);

        assertThrows(IllegalStateException.class, () -> ticks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            throw new IllegalStateException("boom");
        }), dispatcher));

        // The failure was recorded on the worker and reported by the server thread, counted once for the slice.
        assertEquals(1L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.SCHEDULED_TICK));
        assertEquals(0L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.ENTITY_TICK));
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void barrierFailureStillWaitsForTheWorkerAndReleasesTheLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final AtomicBoolean tickFinished = new AtomicBoolean();
        final RegionTickBarrier throwingBarrier = condition -> {
            throw new IllegalStateException("barrier boom");
        };
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, throwingBarrier);
        assertNotNull(dispatcher);

        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ticks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> tickFinished.set(true)), dispatcher));

        assertEquals("barrier boom", failure.getMessage());
        assertTrue(tickFinished.get(), "the batch must wait for the worker before it reports the barrier failure");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void rejectedWorkerGroupRunsOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(true);
        closeWorkerPool(this.engine);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        tickOnWorkers(level, ticks, 10L, (tickPos, tickType) -> {
            threads.add(Thread.currentThread().getName());
            ran.add(tickPos);
        });

        assertEquals(List.of(first, second), ran, "a rejected group still has to execute every selected tick in order");
        assertEquals(2, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertFalse(threads.get(1).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void closedLevelSkipsTheCollectedTicks() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);

        this.engine.clearLevelRegionTasks(level);
        assertEquals(0, this.engine.diagnostics().regionCount());

        ticks.tick(10L, 65536, (tickPos, tickType) -> fail("a tick of a closed level must not run"), dispatcher);

        assertEquals(1L, this.engine.diagnostics().skippedScheduledTicks());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void fallbackTickRunsDirectlyOnTheServerThreadBeforeDispatcherReturns() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));
        final Object region = region(this.engine, level, first);
        invokeRegion(region, "tryMarkRunning");
        final AtomicBoolean closed = new AtomicBoolean();
        final RegionTickBarrier fallbackBarrier = condition -> {
            if (closed.compareAndSet(false, true)) {
                invokeRegion(region, "markDead");
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, fallbackBarrier);
        assertNotNull(dispatcher);
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<BlockPos> ran = new CopyOnWriteArrayList<>();

        ticks.tick(10L, 65536,
                (tickPos, tickType) -> fail("a fallback tick must not go through the region handoff again"),
                (tickPos, tickType) -> {
                    threads.add(Thread.currentThread().getName());
                    ran.add(tickPos);
                },
                dispatcher);

        assertEquals(List.of(first, second), ran, "every fallback tick has to run before the dispatcher returns");
        assertEquals(2, threads.size());
        assertTrue(threads.stream().allMatch(name -> !name.startsWith("NeoForge Region Worker")), threads.toString());
        assertEquals(2L, this.engine.diagnostics().fallbackScheduledTicks());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void fallbackTickOfClosedLevelIsSkippedBeforeDispatcherReturns() throws ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(2, 64, 2);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));
        final Object region = region(this.engine, level, first);
        invokeRegion(region, "tryMarkRunning");
        final AtomicBoolean closed = new AtomicBoolean();
        final RegionTickBarrier closingBarrier = condition -> {
            if (closed.compareAndSet(false, true)) {
                closeLevelInRegionizer(this.engine, level);
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, closingBarrier);
        assertNotNull(dispatcher);

        ticks.tick(10L, 65536,
                (tickPos, tickType) -> fail("a tick of a closed level must not run"),
                (tickPos, tickType) -> fail("a fallback tick of a closed level must be skipped, not handed to another queue"),
                dispatcher);

        assertEquals(2L, this.engine.diagnostics().skippedScheduledTicks());
        assertEquals(0L, this.engine.diagnostics().fallbackScheduledTicks());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void shutdownAfterCollectionRunsTheSnapshotOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);
        final List<String> threads = new CopyOnWriteArrayList<>();

        this.engine.shutdown();
        ticks.tick(10L, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> threads.add(Thread.currentThread().getName())), dispatcher);

        assertEquals(1, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void levelUnloadWaitsForTheRunningScheduledTickBatch() throws InterruptedException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();

        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher);
        final BiConsumer<BlockPos, Block> callback = (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> {
            ticking.countDown();
            awaitOrFail(releaseTick, "the scheduled tick was never released");
            tickFinished.set(true);
        });
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                ticks.tick(10L, 65536, callback, dispatcher);
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "scheduled-tick-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the batch never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                if (!tickFinished.get()) {
                    unloadFailure.set(new AssertionError("the level unload returned before the scheduled tick finished"));
                }
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "scheduled-tick-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level unload must wait for the running scheduled tick batch");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the scheduled tick batch never completed");
        assertFalse(unloadThread.isAlive(), "the level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
    }

    @Test
    void levelUnloadWaitsForTheRunningFallbackTick() throws InterruptedException, ReflectiveOperationException {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        final Block type = mock(Block.class);
        final BlockPos pos = new BlockPos(1, 64, 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));
        final Object region = region(this.engine, level, pos);
        invokeRegion(region, "tryMarkRunning");
        final AtomicBoolean closed = new AtomicBoolean();
        final RegionTickBarrier fallbackBarrier = condition -> {
            if (closed.compareAndSet(false, true)) {
                invokeRegion(region, "markDead");
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };
        final CountDownLatch ticking = new CountDownLatch(1);
        final CountDownLatch releaseTick = new CountDownLatch(1);
        final AtomicBoolean tickFinished = new AtomicBoolean();
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, fallbackBarrier);
        assertNotNull(dispatcher);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread batchThread = new Thread(() -> {
            try {
                ticks.tick(10L, 65536,
                        (tickPos, tickType) -> fail("a fallback tick must not go through the region handoff again"),
                        (tickPos, tickType) -> {
                            ticking.countDown();
                            awaitOrFail(releaseTick, "the fallback tick was never released");
                            tickFinished.set(true);
                        },
                        dispatcher);
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "scheduled-tick-fallback-batch-test");
        batchThread.start();
        assertTrue(ticking.await(10, TimeUnit.SECONDS), "the fallback tick never started");

        final AtomicBoolean unloadReturned = new AtomicBoolean();
        final AtomicReference<Throwable> unloadFailure = new AtomicReference<>();
        final Thread unloadThread = new Thread(() -> {
            try {
                this.engine.clearLevelRegionTasks(level);
                if (!tickFinished.get()) {
                    unloadFailure.set(new AssertionError("the level unload returned before the fallback tick finished"));
                }
                unloadReturned.set(true);
            } catch (Throwable throwable) {
                unloadFailure.set(throwable);
            }
        }, "scheduled-tick-fallback-unload-test");
        unloadThread.start();
        Thread.sleep(200L);
        assertFalse(unloadReturned.get(), "the level unload must wait for the running fallback tick");

        releaseTick.countDown();
        batchThread.join(10_000L);
        unloadThread.join(10_000L);

        assertFalse(batchThread.isAlive(), "the fallback batch never completed");
        assertFalse(unloadThread.isAlive(), "the level unload never returned");
        assertNull(batchFailure.get(), () -> "batch failed: " + batchFailure.get());
        assertNull(unloadFailure.get(), () -> "unload failed: " + unloadFailure.get());
        assertEquals(1L, this.engine.diagnostics().fallbackScheduledTicks());
        assertEquals(0L, this.engine.diagnostics().skippedScheduledTicks());
    }

    private void installEngine(boolean scheduledTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, scheduledTickWorkers, RegionPolicy.FIXED_3X3);
        if (scheduledTickWorkers) {
            // Production falls back to the vanilla inline path for unclassified scheduled callbacks; these
            // tests explicitly opt into the internal mechanics seam.
            created.enableUnclassifiedWorkerCallbacksForTesting();
        }
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private static <T> LevelTicks<T> newTicks() {
        return new LevelTicks<>(chunk -> true, () -> InactiveProfiler.INSTANCE);
    }

    private static <T> LevelChunkTicks<T> addContainer(LevelTicks<T> ticks, int chunkX, int chunkZ) {
        final LevelChunkTicks<T> container = new LevelChunkTicks<>();
        ticks.addContainer(new ChunkPos(chunkX, chunkZ), container);
        return container;
    }

    private static void awaitRendezvous(CyclicBarrier rendezvous) {
        try {
            rendezvous.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object region(RegionThreadingEngine engine, ServerLevel level, BlockPos pos) {
        try {
            final Object regionizer = regionizer(engine);
            final Method getOrCreate = regionizer.getClass().getDeclaredMethod("getOrCreate", RegionCoordinate.class);
            getOrCreate.setAccessible(true);
            return getOrCreate.invoke(regionizer, RegionLayout.FIXED_3X3.regionAt(level, pos));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void invokeRegion(Object region, String method) {
        try {
            final Method regionMethod = region.getClass().getDeclaredMethod(method);
            regionMethod.setAccessible(true);
            regionMethod.invoke(region);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void closeLevelInRegionizer(RegionThreadingEngine engine, ServerLevel level) {
        try {
            final Object regionizer = regionizer(engine);
            final Method clearLevel = regionizer.getClass().getDeclaredMethod("clearLevel", Object.class);
            clearLevel.setAccessible(true);
            clearLevel.invoke(regionizer, level);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object regionizer(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("regionizer");
        field.setAccessible(true);
        return field.get(engine);
    }

    private void tickOnWorkers(ServerLevel level, LevelTicks<Block> ticks, long gameTime, BiConsumer<BlockPos, Block> action) {
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
        assertNotNull(dispatcher, "scheduled tick worker execution must be enabled");
        ticks.tick(gameTime, 65536, (tickPos, tickType) -> RegionThreadingHooks.runBlockTickTask(level, tickPos, () -> action.accept(tickPos, tickType)), dispatcher);
    }

    private static ServerLevel mockLevel() {
        return mockLevel(RandomSource.create(1234L));
    }

    private static ServerLevel mockLevel(RandomSource random) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        // The game time is part of the context a worker records with a failing tick.
        doReturn(0L).when(level).getGameTime();
        setLevelFieldUnchecked(level, "random", random);
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private static void invokeTickBlock(ServerLevel level, BlockPos pos, Block type) throws ReflectiveOperationException {
        final Method method = ServerLevel.class.getDeclaredMethod("tickBlock", BlockPos.class, Block.class);
        method.setAccessible(true);
        try {
            method.invoke(level, pos, type);
        } catch (InvocationTargetException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static void setLevelFieldUnchecked(ServerLevel level, String name, Object value) {
        try {
            final Field field = net.minecraft.world.level.Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
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

    private static void fail(String message) {
        throw new AssertionError(message);
    }

    /**
     * Records the threads that mutate the container, so a cross-phase test can prove the mutation ran on
     * the server thread instead of the worker that requested it.
     */
    private static final class ThreadRecordingChunkTicks<T> extends LevelChunkTicks<T> {
        private final List<String> scheduleThreads = new CopyOnWriteArrayList<>();

        @Override
        public void schedule(ScheduledTick<T> tick) {
            this.scheduleThreads.add(Thread.currentThread().getName());
            super.schedule(tick);
        }

        List<String> scheduleThreads() {
            return this.scheduleThreads;
        }
    }
}
