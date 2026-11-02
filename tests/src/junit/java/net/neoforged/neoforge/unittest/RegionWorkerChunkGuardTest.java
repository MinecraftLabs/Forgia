/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the fail-fast chunk source guard of a native region worker: the refusal itself, the pre-load
 * margin that keeps the common case off the chunk source, and the server-thread fallback that ticks an
 * entity whose tick still needed a chunk that was not loaded.
 * <p>
 * The level is a {@link org.mockito.Mockito#CALLS_REAL_METHODS} mock, so the worker runs the real
 * {@code guardEntityTick} and {@code tickNonPassenger} code; the chunk source is a mock, so a test decides
 * which chunks are loaded without loading one.
 */
@Timeout(30)
class RegionWorkerChunkGuardTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final String WORKER_THREAD_PREFIX = "NeoForge Region Worker";

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void refusalIsRecognizedThroughWrappingCauses() {
        final RegionWorkerChunkUnavailableException refusal = RegionWorkerChunkUnavailableException.forChunk(3, -7, ChunkStatus.FULL);

        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(refusal));
        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(new RuntimeException("wrapper", refusal)));
        assertTrue(RegionWorkerChunkUnavailableException.isChunkUnavailable(new RuntimeException(new IllegalStateException(refusal))));
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(new IllegalStateException("unrelated")));
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(null));
        // A cause chain that points at itself must not hang the worker that is already recovering.
        final Throwable cyclic = new Throwable("cyclic") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertFalse(RegionWorkerChunkUnavailableException.isChunkUnavailable(cyclic));
    }

    @Test
    void everyRefusedChunkRequestIsCountedOnce() {
        final long before = RegionWorkerChunkUnavailableException.refusalCount();

        final RegionWorkerChunkUnavailableException chunkRefusal = RegionWorkerChunkUnavailableException.forChunk(1, 2, ChunkStatus.FULL);
        final RegionWorkerChunkUnavailableException futureRefusal = RegionWorkerChunkUnavailableException.forChunkFuture(1, 2, ChunkStatus.FULL);

        assertEquals(before + 2L, RegionWorkerChunkUnavailableException.refusalCount());
        assertTrue(chunkRefusal.getMessage().contains("1, 2"), chunkRefusal.getMessage());
        assertTrue(futureRefusal.getMessage().contains("1, 2"), futureRefusal.getMessage());
        assertThrows(RegionWorkerChunkUnavailableException.class, () -> {
            throw chunkRefusal;
        });
    }

    @Test
    void entityWhoseTickRefusesAChunkTicksOnTheServerThreadInstead() {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final Entity refusing = mockEntity(level, new BlockPos(1, 64, 1));
        final Entity healthy = mockEntity(level, new BlockPos(2, 64, 2));
        final List<String> refusingTickThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            refusingTickThreads.add(Thread.currentThread().getName());
            if (attempts.incrementAndGet() == 1) {
                throw RegionWorkerChunkUnavailableException.forChunk(1, 1, ChunkStatus.FULL);
            }
            return null;
        }).when(refusing).tick();
        final List<String> healthyTickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            healthyTickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(healthy).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(refusing));
        assertTrue(plan.accept(healthy));
        plan.executeAndAwait();

        // The refused entity is ticked exactly once: the abandoned worker attempt throws, the fallback tick
        // runs on the server thread. The group keeps its lease and ticks the remaining entity on the worker.
        assertEquals(2, refusingTickThreads.size(), refusingTickThreads.toString());
        assertTrue(refusingTickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), refusingTickThreads.toString());
        assertFalse(refusingTickThreads.get(1).startsWith(WORKER_THREAD_PREFIX), refusingTickThreads.toString());
        verify(refusing, times(2)).tick();
        assertEquals(1, healthyTickThreads.size(), healthyTickThreads.toString());
        assertTrue(healthyTickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), healthyTickThreads.toString());

        assertEquals(1L, this.engine.diagnostics().chunkFallbackEntityTicks());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void regionWhoseNeighborhoodIsNotLoadedTicksOnTheServerThread() {
        this.installEngine();
        final ServerLevel level = mockLevel(false);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertFalse(tickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
        // The region was never leased, so it is not a lease fallback either.
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void regionWhoseNeighborhoodIsLoadedTicksOnTheWorkerWithoutRefusals() {
        this.installEngine();
        final ServerLevel level = mockLevel(true);
        final Entity entity = mockEntity(level, new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, tickThreads.size(), tickThreads.toString());
        assertTrue(tickThreads.get(0).startsWith(WORKER_THREAD_PREFIX), tickThreads.toString());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
    }

    /**
     * {@return the pre-load margin of the installed engine, which the neighborhood stubs are built from}
     */
    private static int margin() {
        return RegionThreadingEngine.entityTickChunkMargin();
    }

    private void installEngine() {
        // The worker slice mechanics are exercised directly; production keeps entity storage on the server
        // thread and therefore never submits an entity group.
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false, RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    /**
     * Creates a level whose chunk source reports the neighborhood of the region at chunk {@code 0, 0} as
     * loaded or as missing.
     * <p>
     * A {@link RegionPolicy#DYNAMIC} region is 16 chunks wide and the pre-load margin is
     * {@link RegionThreadingEngine#entityTickChunkMargin()} chunks around it, so the stub answers exactly
     * the chunks the pre-load check asks for and reports every other chunk as missing.
     */
    private static ServerLevel mockLevel(boolean neighborhoodLoaded) {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        final int regionChunkSize = RegionLayout.DYNAMIC.regionChunkSize();
        final int minChunk = -margin();
        final int maxChunk = regionChunkSize - 1 + margin();
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            final boolean inNeighborhood = chunkX >= minChunk && chunkX <= maxChunk && chunkZ >= minChunk && chunkZ <= maxChunk;
            return neighborhoodLoaded && inNeighborhood ? loadedChunk : null;
        });
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
    }

    private static Entity mockEntity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.getPassengers()).thenReturn(List.of());
        return entity;
    }
}
