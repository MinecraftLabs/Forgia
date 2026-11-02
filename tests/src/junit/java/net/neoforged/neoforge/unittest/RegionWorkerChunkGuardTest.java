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
import java.util.function.BiPredicate;
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
 * Exercises the fail-fast chunk source guard of a native region worker: the refusal itself, the per entity
 * pre-load margin that keeps the common case off the chunk source, and the server-thread fallback that ticks
 * an entity whose tick still needed a chunk that was not loaded.
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
    void entityWhoseNeighborhoodIsNotLoadedTicksOnTheServerThread() {
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
    void entityWhoseNeighborhoodIsLoadedTicksOnTheWorkerWithoutRefusals() {
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

    @Test
    void entityWhoseOwnNeighborhoodIsLoadedTicksOnAWorkerEvenWhenItsRegionIsNot() {
        this.installEngine();
        // The first three chunks of the region are never loaded, so the region rectangle plus its margin is
        // incomplete. The entity stands in an interior chunk whose own neighborhood is loaded, which is all
        // the per entity check asks for.
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX > 2 || chunkZ > 2);
        final Entity entity = mockEntity(level, chunkCenter(8, 8));
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
    }

    @Test
    void edgeEntityFallsBackWhileItsRegionTicksTheRestOfItsEntitiesOnWorkers() {
        this.installEngine();
        // Exactly the chunks of the region at 0, 0 are loaded. The three interior entities have a loaded
        // neighborhood; the entity in the last chunk of the region needs the margin chunk beyond it, which
        // is not loaded.
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX >= 0 && chunkX < 16 && chunkZ >= 0 && chunkZ < 16);
        final List<Entity> interior = List.of(mockEntity(level, chunkCenter(7, 7)), mockEntity(level, chunkCenter(8, 8)), mockEntity(level, chunkCenter(9, 9)));
        final Entity edge = mockEntity(level, chunkCenter(15, 15));
        final List<String> interiorThreads = new CopyOnWriteArrayList<>();
        for (final Entity entity : interior) {
            doAnswer(invocation -> {
                interiorThreads.add(Thread.currentThread().getName());
                return null;
            }).when(entity).tick();
        }
        final List<String> edgeThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            edgeThreads.add(Thread.currentThread().getName());
            return null;
        }).when(edge).tick();

        final RegionEntityTickPlan plan = plan(level);
        for (final Entity entity : interior) {
            assertTrue(plan.accept(entity));
        }
        assertTrue(plan.accept(edge));
        plan.executeAndAwait();

        // One edge entity is refused, not the four entities of its region.
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(3L, this.engine.diagnostics().entityTicksOnWorkers());
        // The region was leased, so the edge entity is a margin refusal and not a lease fallback.
        assertEquals(0L, this.engine.diagnostics().fallbackEntityTicks());
        assertEquals(0L, this.engine.diagnostics().chunkFallbackEntityTicks());
        assertEquals(3, interiorThreads.size(), interiorThreads.toString());
        for (final String thread : interiorThreads) {
            assertTrue(thread.startsWith(WORKER_THREAD_PREFIX), interiorThreads.toString());
        }
        assertEquals(1, edgeThreads.size(), edgeThreads.toString());
        assertFalse(edgeThreads.get(0).startsWith(WORKER_THREAD_PREFIX), edgeThreads.toString());
    }

    @Test
    void edgeFallbackEntityIsTickedExactlyOnceAndItsRegionLeaseIsReleased() {
        this.installEngine();
        final ServerLevel level = mockLevel((chunkX, chunkZ) -> chunkX >= 0 && chunkX < 16 && chunkZ >= 0 && chunkZ < 16);
        final Entity interior = mockEntity(level, chunkCenter(8, 8));
        final Entity edge = mockEntity(level, chunkCenter(0, 15));
        doAnswer(invocation -> null).when(interior).tick();
        doAnswer(invocation -> null).when(edge).tick();

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(interior));
        assertTrue(plan.accept(edge));
        plan.executeAndAwait();

        // The refused entity is ticked exactly once, by the server-thread fallback, even though the rest of
        // its region ran on a worker in the same tick.
        verify(edge, times(1)).tick();
        verify(interior, times(1)).tick();
        assertEquals(1L, this.engine.diagnostics().chunkPreloadRefusedEntityTicks());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().totalRegionTickCount());
        // The batch completed its handoff and released the lease of the region.
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
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
     * {@return the block position at the center of chunk {@code chunkX, chunkZ}}
     */
    private static BlockPos chunkCenter(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 8, 64, chunkZ * 16 + 8);
    }

    /**
     * Creates a level whose chunk source reports the chunks of the loaded rectangle of the dynamic region
     * at chunk {@code 0, 0} as loaded or as missing.
     * <p>
     * A {@link RegionPolicy#DYNAMIC} region is 16 chunks wide and the pre-load margin is
     * {@link RegionThreadingEngine#entityTickChunkMargin()} chunks around it, so the stub answers exactly
     * the chunks the pre-load check of an entity in that region asks for and reports every other chunk as
     * missing.
     */
    private static ServerLevel mockLevel(boolean neighborhoodLoaded) {
        final int regionChunkSize = RegionLayout.DYNAMIC.regionChunkSize();
        final int minChunk = -margin();
        final int maxChunk = regionChunkSize - 1 + margin();
        return mockLevel((chunkX, chunkZ) -> neighborhoodLoaded
                && chunkX >= minChunk && chunkX <= maxChunk && chunkZ >= minChunk && chunkZ <= maxChunk);
    }

    /**
     * Creates a level whose chunk source reports exactly the chunks that {@code loadedChunk} accepts as
     * loaded and every other chunk as missing.
     */
    private static ServerLevel mockLevel(BiPredicate<Integer, Integer> loadedChunk) {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        final LevelChunk loadedChunkInstance = mock(LevelChunk.class);
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenAnswer(invocation -> {
            final int chunkX = invocation.getArgument(0);
            final int chunkZ = invocation.getArgument(1);
            return loadedChunk.test(chunkX, chunkZ) ? loadedChunkInstance : null;
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
