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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the scoped main-thread fallback transport for entity range queries issued by a region tick
 * worker.
 * <p>
 * This is mechanics coverage only: production rejects every legacy entity query issued by a native
 * worker before a predicate, consumer or live result escapes. These tests enable the engine's internal
 * handoff seam explicitly, so a passing handoff test is not evidence that production workers may read
 * entity storage.
 * <p>
 * The tests drive a real scheduled tick batch so the query runs inside a real {@code RegionTickBatch}
 * group. The "server thread" is the test thread. The barrier stands in for
 * {@code ServerChunkCache.managedBlockOnMainThread}: it pumps only the chunk source task queue, exactly
 * like {@code BlockableEventLoop.managedBlock}. The {@code MinecraftServer} task queue is kept separate
 * and is never pumped by the barrier, so a handoff only completes when the batch-aware barrier drains it
 * on the waiting server thread.
 */
@Timeout(30)
class RegionEntityQueryHandoffTest {
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void entityTickPlanFallsBackWhenStorageIsMainThreadOnly() {
        this.installEngine(true, false);
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), Thread.currentThread());

        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, parkingBarrier()));
        assertEquals(RegionEntityStorageMode.MAIN_THREAD_ONLY, this.engine.diagnostics().entityStorageMode());
    }

    @Test
    void blockEntityTickPlanIsEnabledIndependentlyOfEntityStorageMode() {
        this.installEngine(false, true);
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), Thread.currentThread());

        // The block-entity slice runs only the compatibility-declared subset; it does not need region-owned
        // entity storage, so the plan is created and the per-region lease decides what runs on a worker.
        assertNotNull(this.engine.beginBlockEntityTickPlan(level, parkingBarrier()));
        assertEquals(RegionEntityStorageMode.MAIN_THREAD_ONLY, this.engine.diagnostics().entityStorageMode());
    }

    @Test
    void regionOwnedEntityStorageEnablesTheEntityWorkerPlan() {
        this.installEngine(true, true, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), Thread.currentThread());

        assertNotNull(this.engine.beginEntityTickPlan(level, entity -> true, parkingBarrier()));
        assertNotNull(this.engine.beginBlockEntityTickPlan(level, parkingBarrier()));
        assertEquals(RegionEntityStorageMode.REGION_OWNED, this.engine.diagnostics().entityStorageMode());
    }

    @Test
    void workerHandoffRunsTheCompleteComputationOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final Queue<Runnable> chunkSourceTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> computationThreads = new CopyOnWriteArrayList<>();
        final List<String> callbackThreads = new CopyOnWriteArrayList<>();
        final AtomicReference<String> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(chunkSourceTasks), 0, 0, (pos, type) -> {
            callbackThreads.add(Thread.currentThread().getName());
            result.set(RegionThreadingHooks.runOnServerThread(level, () -> {
                computationThreads.add(Thread.currentThread().getName());
                return "query-result";
            }));
        });

        assertEquals("query-result", result.get());
        assertEquals(1, callbackThreads.size());
        assertTrue(callbackThreads.get(0).startsWith("NeoForge Region Worker"), callbackThreads.toString());
        assertEquals(List.of(serverThread.getName()), computationThreads, "the computation must run on the server thread");
        assertTrue(serverTasks.isEmpty(), "the handoff must not depend on the MinecraftServer task queue");
        assertTrue(chunkSourceTasks.isEmpty());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
    }

    @Test
    void handoffIsServicedInAdditionToChunkSourceTasksWhileTheServerWaits() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final Queue<Runnable> chunkSourceTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final AtomicBoolean chunkSourceTaskRan = new AtomicBoolean();
        chunkSourceTasks.add(() -> chunkSourceTaskRan.set(true));
        final AtomicReference<String> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(chunkSourceTasks), 0, 0, (pos, type) -> result.set(RegionThreadingHooks.runOnServerThread(level, () -> "handoff")));

        assertEquals("handoff", result.get());
        assertTrue(chunkSourceTaskRan.get(), "the barrier must keep servicing chunk source tasks while it waits");
        assertTrue(serverTasks.isEmpty(), "the handoff must not depend on the MinecraftServer task queue");
    }

    @Test
    void workerHandoffPropagatesComputationFailure() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final IllegalStateException failure = new IllegalStateException("query boom");
        final AtomicReference<Throwable> thrown = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> {
            try {
                RegionThreadingHooks.runOnServerThread(level, () -> {
                    throw failure;
                });
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });

        assertSame(failure, thrown.get());
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void mainThreadCallRunsTheComputationInline() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> computationThreads = new CopyOnWriteArrayList<>();

        final String value = RegionThreadingHooks.runOnServerThread(level, () -> {
            computationThreads.add(Thread.currentThread().getName());
            return "inline";
        });

        assertEquals("inline", value);
        assertEquals(List.of(serverThread.getName()), computationThreads);
        assertTrue(serverTasks.isEmpty(), "the server thread must not queue work for itself");
    }

    @Test
    void entityRangeQueryFromAWorkerRunsOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> getterThreads = new CopyOnWriteArrayList<>();
        final Entity entity = mock(Entity.class);
        final AABB queryBox = new AABB(0, 0, 0, 1, 1, 1);
        when(entity.getBoundingBox()).thenReturn(queryBox);

        final LevelEntityGetter<Entity> getter = mock(LevelEntityGetter.class);
        doAnswer(invocation -> {
            getterThreads.add(Thread.currentThread().getName());
            final Consumer<Entity> consumer = invocation.getArgument(1);
            consumer.accept(entity);
            return null;
        }).when(getter).get(any(AABB.class), any(Consumer.class));
        doReturn(getter).when(level).getEntities();

        final AtomicReference<List<Entity>> result = new AtomicReference<>();
        final List<String> callbackThreads = new CopyOnWriteArrayList<>();
        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> {
            callbackThreads.add(Thread.currentThread().getName());
            result.set(level.getEntities((Entity) null, queryBox, candidate -> true));
        });

        assertEquals(List.of(entity), result.get());
        assertEquals(1, callbackThreads.size());
        assertTrue(callbackThreads.get(0).startsWith("NeoForge Region Worker"), callbackThreads.toString());
        assertEquals(List.of(serverThread.getName()), getterThreads, "the live section storage must only be iterated on the server thread");
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void mainThreadEntityQueryRunsInline() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> getterThreads = new CopyOnWriteArrayList<>();
        final Entity entity = mock(Entity.class);
        final AABB queryBox = new AABB(0, 0, 0, 1, 1, 1);
        when(entity.getBoundingBox()).thenReturn(queryBox);

        final LevelEntityGetter<Entity> getter = mock(LevelEntityGetter.class);
        doAnswer(invocation -> {
            getterThreads.add(Thread.currentThread().getName());
            final Consumer<Entity> consumer = invocation.getArgument(1);
            consumer.accept(entity);
            return null;
        }).when(getter).get(any(AABB.class), any(Consumer.class));
        doReturn(getter).when(level).getEntities();

        final List<Entity> result = level.getEntities((Entity) null, queryBox, candidate -> true);

        assertEquals(List.of(entity), result);
        assertEquals(List.of(serverThread.getName()), getterThreads);
        assertTrue(serverTasks.isEmpty(), "the main-thread path must not queue work");
    }

    @Test
    void twoRegionQueriesAreSerializedOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> computationThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<BlockPos> completed = new CopyOnWriteArrayList<>();
        final Block type = mock(Block.class);
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        addContainer(ticks, 16, 16);
        final BlockPos first = new BlockPos(1, 64, 1);
        final BlockPos second = new BlockPos(257, 64, 257);
        ticks.schedule(new ScheduledTick<>(type, first, 10L, 0L));
        ticks.schedule(new ScheduledTick<>(type, second, 10L, 1L));

        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, chunkSourceBarrier(new ConcurrentLinkedQueue<>()));
        assertNotNull(dispatcher);
        ticks.tick(10L, 65536, (pos, tickType) -> RegionThreadingHooks.runOnServerThread(level, () -> {
            computationThreads.add(Thread.currentThread().getName());
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            completed.add(pos);
            return null;
        }), dispatcher);

        assertEquals(Set.of(first, second), Set.copyOf(completed));
        assertEquals(1, maxActive.get(), "main-thread handoffs must be serialized");
        assertEquals(2, computationThreads.size());
        assertTrue(computationThreads.stream().allMatch(serverThread.getName()::equals), computationThreads.toString());
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void failedBarrierReleasesAPendingHandoffWithoutRunningIt() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final AtomicBoolean computationRan = new AtomicBoolean();
        final Block type = mock(Block.class);
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, 0, 0);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));

        final RegionTickBarrier failingBarrier = condition -> {
            throw new IllegalStateException("barrier boom");
        };
        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, failingBarrier);
        assertNotNull(dispatcher);

        assertThrows(IllegalStateException.class, () -> ticks.tick(10L, 65536, (pos, tickType) -> RegionThreadingHooks.runOnServerThread(level, () -> {
            computationRan.set(true);
            return null;
        }), dispatcher));
        assertFalse(computationRan.get(), "a handoff released by a failed batch must not run");
    }

    @Test
    void levelClosedBeforeTheHandoffRunsFailsTheQuery() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final AtomicBoolean computationRan = new AtomicBoolean();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final RegionTickBarrier closingBarrier = condition -> {
            if (closed.compareAndSet(false, true)) {
                closeLevelInRegionizer(this.engine, level);
            }
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };

        runScheduledTick(level, closingBarrier, 0, 0, (pos, type) -> {
            try {
                RegionThreadingHooks.runOnServerThread(level, () -> {
                    computationRan.set(true);
                    return null;
                });
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });

        assertFalse(computationRan.get(), "a handoff of a closed level must not run its computation");
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertEquals("Level closed before region main-thread work ran", thrown.get().getMessage());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void nestedHandoffFromAComputationRunsInlineOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> computationThreads = new CopyOnWriteArrayList<>();
        final AtomicReference<String> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(RegionThreadingHooks.runOnServerThread(level, () -> RegionThreadingHooks.runOnServerThread(level, () -> {
            computationThreads.add(Thread.currentThread().getName());
            return "nested";
        }))));

        assertEquals("nested", result.get());
        assertEquals(List.of(serverThread.getName()), computationThreads, "a nested handoff must run inline on the server thread");
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void rejectedWorkerGroupRunsTheQueryOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine(false, false);
        closeWorkerPool(this.engine);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final List<String> computationThreads = new CopyOnWriteArrayList<>();
        final AtomicReference<String> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(RegionThreadingHooks.runOnServerThread(level, () -> {
            computationThreads.add(Thread.currentThread().getName());
            return "inline";
        })));

        assertEquals("inline", result.get());
        assertEquals(List.of(serverThread.getName()), computationThreads, "a rejected group runs its query inline on the publishing thread");
        assertTrue(serverTasks.isEmpty());
    }

    @Test
    void handoffBatchLeavesNoRegionMarkerBehindAtShutdown() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final AtomicReference<String> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(RegionThreadingHooks.runOnServerThread(level, () -> "handoff")));

        assertEquals("handoff", result.get());
        assertFalse(RegionThreadingHooks.shouldHandOffEntityQuery(), "the batch marker must be cleared after the batch");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().submittedRegionCount());

        RegionThreadingManager.clear();
        assertFalse(RegionThreadingManager.isInstalled(), "shutdown must complete without a stranded batch or region marker");
    }

    @Test
    void shutdownRequestedFromTheHandoffIsRejectedInsteadOfWaiting() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final Queue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();
        final ServerLevel level = mockLevel(serverTasks, serverThread);
        final AtomicReference<Throwable> thrown = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> {
            try {
                RegionThreadingHooks.runOnServerThread(level, () -> {
                    this.engine.shutdown();
                    return null;
                });
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });

        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertEquals("Cannot shut down region threading from its region tick batch", thrown.get().getMessage());
        assertTrue(RegionThreadingManager.isInstalled(), "the rejected shutdown must not stop the engine");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
    }

    @Test
    void entityGetterIdLookupFromAWorkerRunsOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final Entity entity = mock(Entity.class);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final List<String> lookupThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            lookupThreads.add(Thread.currentThread().getName());
            return entity;
        }).when(delegate).get(42);
        final RegionAwareEntityGetter getter = new RegionAwareEntityGetter(level, delegate);
        final AtomicReference<Entity> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(getter.get(42)));

        assertSame(entity, result.get());
        assertEquals(List.of(serverThread.getName()), lookupThreads, "the by-id lookup must run on the server thread");
    }

    @Test
    void entityGetterUuidLookupFromAWorkerRunsOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final Entity entity = mock(Entity.class);
        final UUID uuid = UUID.randomUUID();
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final List<String> lookupThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            lookupThreads.add(Thread.currentThread().getName());
            return entity;
        }).when(delegate).get(uuid);
        final RegionAwareEntityGetter getter = new RegionAwareEntityGetter(level, delegate);
        final AtomicReference<Entity> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(getter.get(uuid)));

        assertSame(entity, result.get());
        assertEquals(List.of(serverThread.getName()), lookupThreads, "the by-uuid lookup must run on the server thread");
    }

    @Test
    void entityGetterGetAllIteratesOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final Entity entity = mock(Entity.class);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final List<String> iterationThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            iterationThreads.add(Thread.currentThread().getName());
            return List.of(entity);
        }).when(delegate).getAll();
        final RegionAwareEntityGetter getter = new RegionAwareEntityGetter(level, delegate);
        final AtomicReference<Iterable<Entity>> result = new AtomicReference<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> result.set(getter.getAll()));

        final List<Entity> snapshot = new CopyOnWriteArrayList<>();
        result.get().forEach(snapshot::add);
        assertEquals(List.of(entity), snapshot);
        assertEquals(List.of(serverThread.getName()), iterationThreads, "getAll must be consumed on the server thread");
    }

    @Test
    void entityGetterAabbQueryConsumerRunsOnTheServerThread() {
        this.installEngine(false, false);
        final Thread serverThread = Thread.currentThread();
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), serverThread);
        final Entity entity = mock(Entity.class);
        final AABB queryBox = new AABB(0, 0, 0, 1, 1, 1);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final List<String> queryThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            queryThreads.add(Thread.currentThread().getName());
            final Consumer<Entity> consumer = invocation.getArgument(1);
            consumer.accept(entity);
            return null;
        }).when(delegate).get(any(AABB.class), any(Consumer.class));
        final RegionAwareEntityGetter getter = new RegionAwareEntityGetter(level, delegate);
        final List<Entity> result = new CopyOnWriteArrayList<>();

        runScheduledTick(level, chunkSourceBarrier(new ConcurrentLinkedQueue<>()), 0, 0, (pos, type) -> getter.get(queryBox, result::add));

        assertEquals(List.of(entity), result);
        assertEquals(List.of(serverThread.getName()), queryThreads, "the query and its consumer must run on the server thread");
    }

    @Test
    void entityGetterRunsInlineWhenNoRegionThreadingIsInstalled() {
        final ServerLevel level = mockLevel(new ConcurrentLinkedQueue<>(), Thread.currentThread());
        final Entity entity = mock(Entity.class);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final List<String> lookupThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            lookupThreads.add(Thread.currentThread().getName());
            return entity;
        }).when(delegate).get(42);

        assertSame(entity, new RegionAwareEntityGetter(level, delegate).get(42));
        assertEquals(List.of(Thread.currentThread().getName()), lookupThreads);
    }

    private void installEngine(boolean entityTickWorkers, boolean blockEntityTickWorkers) {
        this.installEngine(entityTickWorkers, blockEntityTickWorkers, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    private void installEngine(boolean entityTickWorkers, boolean blockEntityTickWorkers, RegionEntityStorageMode entityStorageMode) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, entityTickWorkers, blockEntityTickWorkers, true, false, RegionPolicy.FIXED_3X3, entityStorageMode);
        // These tests exercise the legacy handoff transport and the scheduled tick mechanics directly.
        // Production rejects legacy worker queries and gates unclassified scheduled callbacks.
        created.enableLegacyEntityQueryHandoffForTesting();
        created.enableUnclassifiedWorkerCallbacksForTesting();
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private void runScheduledTick(ServerLevel level, RegionTickBarrier barrier, int chunkX, int chunkZ, BiConsumer<BlockPos, Block> callback) {
        final Block type = mock(Block.class);
        final LevelTicks<Block> ticks = newTicks();
        addContainer(ticks, chunkX, chunkZ);
        final BlockPos pos = new BlockPos(chunkX * 16 + 1, 64, chunkZ * 16 + 1);
        ticks.schedule(new ScheduledTick<>(type, pos, 10L, 0L));

        final LevelTicks.TickDispatcher<Block> dispatcher = this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, barrier);
        assertNotNull(dispatcher);
        ticks.tick(10L, 65536, callback, dispatcher);
    }

    /**
     * Stands in for {@code ServerChunkCache.managedBlockOnMainThread}: it pumps the chunk source task queue
     * only, and never the {@code MinecraftServer} task queue.
     */
    private static RegionTickBarrier chunkSourceBarrier(Queue<Runnable> chunkSourceTasks) {
        return condition -> {
            while (!condition.getAsBoolean()) {
                final Runnable task = chunkSourceTasks.poll();
                if (task != null) {
                    task.run();
                } else {
                    LockSupport.parkNanos(100_000L);
                }
            }
        };
    }

    private static RegionTickBarrier parkingBarrier() {
        return condition -> {
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };
    }

    private static ServerLevel mockLevel(Queue<Runnable> serverTasks, Thread serverThread) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(List.of()).when(level).getPartEntities();
        // The scheduled tick batch draws one seed from the level random before it creates a group.
        setLevelField(level, "random", RandomSource.create(1234L));
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == serverThread);
        doAnswer(invocation -> {
            serverTasks.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        doReturn(server).when(level).getServer();
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private static void closeLevelInRegionizer(RegionThreadingEngine engine, ServerLevel level) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Method clearLevel = regionizer.getClass().getDeclaredMethod("clearLevel", Object.class);
            clearLevel.setAccessible(true);
            clearLevel.invoke(regionizer, level);
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

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final Field field = net.minecraft.world.level.Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static <T> LevelTicks<T> newTicks() {
        return new LevelTicks<>(chunk -> true, () -> InactiveProfiler.INSTANCE);
    }

    private static <T> void addContainer(LevelTicks<T> ticks, int chunkX, int chunkZ) {
        ticks.addContainer(new ChunkPos(chunkX, chunkZ), new LevelChunkTicks<>());
    }
}
