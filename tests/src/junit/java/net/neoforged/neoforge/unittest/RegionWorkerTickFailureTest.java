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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the failure path of a region worker tick: a worker that catches a failing tick records it and the
 * server thread builds and reports the crash report after the barrier and after the handoff.
 * <p>
 * The level is a {@code CALLS_REAL_METHODS} mock, so the worker runs the real {@code tickNonPassenger} body of
 * vanilla. Filling the report category of the failing entity is the step of the crash report that names the
 * entity, so the entity is the recording stub: every entry is recorded with the thread that entered it, which
 * is the thread that paid for the report.
 */
@Timeout(60)
class RegionWorkerTickFailureTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;

    private RegionThreadingEngine engine;
    /** The level's entity tick list as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    /** The threads that filled the report category of a failing entity, i.e. the threads that built a report. */
    private final List<String> reportThreads = new CopyOnWriteArrayList<>();
    /** The threads the entities actually ticked on. */
    private final List<String> tickThreads = new CopyOnWriteArrayList<>();

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.reportThreads.clear();
        this.tickThreads.clear();
        this.engine = null;
    }

    @Test
    void workerTickFailureIsReportedByTheServerThreadAfterTheBarrier() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final IllegalStateException failure = new IllegalStateException("worker tick boom");
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        this.failingTick(entity, failure, null);

        final ReportedException reported = assertThrows(ReportedException.class, () -> this.plan(level).executeAndAwait());

        // The tick ran on a worker, the report was built on the thread that waited for the batch.
        assertEquals(1, this.tickThreads.size());
        assertTrue(this.tickThreads.get(0).startsWith("NeoForge Region Worker"), this.tickThreads.toString());
        assertEquals(List.of(Thread.currentThread().getName()), this.reportThreads,
                "the crash report of a failing worker tick must be built by the server thread");
        assertSame(failure, reported.getReport().getException(), "the report must carry the original throwable");
        assertEquals(1L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.ENTITY_TICK));
        assertEquals(0L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.BLOCK_ENTITY_TICK));
        verify(entity, never()).discard();
    }

    @Test
    void reportedFailureCarriesTheVanillaReportAndTheEntityContext() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final IllegalStateException failure = new IllegalStateException("worker tick boom");
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        this.failingTick(entity, failure, null);

        final ReportedException reported = assertThrows(ReportedException.class, () -> this.plan(level).executeAndAwait());

        final CrashReport report = reported.getReport();
        assertEquals("Ticking entity", report.getTitle(), "the report is vanilla's entity tick report");
        assertSame(failure, report.getException());
        final String details = report.getDetails();
        assertTrue(details.contains("Entity being ticked"), details);
        assertTrue(details.contains("test-entity-" + entity.getId()), "the report must name the failing entity: " + details);
    }

    @Test
    void perEntityPlanReportsAWorkerTickFailureOnTheServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final IllegalStateException failure = new IllegalStateException("worker tick boom");
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, candidate -> true, PARKING_BARRIER);
        assertTrue(plan.accept(entity));
        this.failingTick(entity, failure, null);

        final ReportedException reported = assertThrows(ReportedException.class, plan::executeAndAwait);

        assertSame(failure, reported.getReport().getException());
        assertEquals(List.of(Thread.currentThread().getName()), this.reportThreads,
                "the report of the per-entity plan must be built on the server thread");
        assertEquals(1L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.ENTITY_TICK));
    }

    @Test
    void twoFailingEntitiesInOneTickProduceOneReport() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // One region per entity, so both workers fail in the same tick.
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final IllegalStateException west = new IllegalStateException("west boom");
        final IllegalStateException east = new IllegalStateException("east boom");
        final Entity westEntity = this.entity(level, new BlockPos(1, 64, 1));
        final Entity eastEntity = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        this.tickList.addAll(List.of(westEntity, eastEntity));
        this.failingTick(westEntity, west, null);
        this.failingTick(eastEntity, east, null);

        final ReportedException reported = assertThrows(ReportedException.class, () -> this.plan(level).executeAndAwait());

        // Vanilla builds one report and crashes on it, so the second failure is counted and never reported.
        assertEquals(1, this.reportThreads.size(), "two failing workers must not build two reports");
        assertEquals(Thread.currentThread().getName(), this.reportThreads.get(0));
        assertTrue(reported.getReport().getException() == west || reported.getReport().getException() == east,
                "the report must carry one of the recorded failures");
        assertEquals(2L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.ENTITY_TICK),
                "both failures are counted, only one is reported");
    }

    @Test
    void failureRecordedWhileTheEngineShutsDownIsStillReported() throws InterruptedException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final IllegalStateException failure = new IllegalStateException("worker tick boom during shutdown");
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final CountDownLatch tickStarted = new CountDownLatch(1);
        final CountDownLatch failureReleased = new CountDownLatch(1);
        this.failingTick(entity, failure, tickStarted, failureReleased);

        final RegionEntityTickPlan plan = this.plan(level);
        final AtomicReference<Throwable> batchFailure = new AtomicReference<>();
        final Thread serverThread = new Thread(() -> {
            try {
                plan.executeAndAwait();
            } catch (Throwable throwable) {
                batchFailure.set(throwable);
            }
        }, "region-tick-failure-test-server");
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        final Thread shutdownThread = new Thread(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            }
        }, "region-tick-failure-test-shutdown");
        serverThread.start();
        assertTrue(tickStarted.await(10, TimeUnit.SECONDS), "the failing worker tick never started");
        shutdownThread.start();
        // The worker waits until the shutdown reached its wait for this batch, so the failure is recorded after
        // the engine closed its deferred seams and while the level is on its way out.
        awaitThreadBlocked(shutdownThread);
        failureReleased.countDown();
        serverThread.join(TimeUnit.SECONDS.toMillis(10));
        shutdownThread.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(serverThread.isAlive(), "the batch barrier must not deadlock against the shutdown");
        assertFalse(shutdownThread.isAlive(), "the shutdown must not deadlock against the failing batch");
        assertNull(shutdownFailure.get(), () -> "the shutdown failed: " + shutdownFailure.get());
        assertTrue(batchFailure.get() instanceof ReportedException, () -> "the failure must be reported: " + batchFailure.get());
        assertSame(failure, ((ReportedException) batchFailure.get()).getReport().getException());
        assertEquals(List.of(serverThread.getName()), this.reportThreads,
                "the report of a failure recorded during shutdown is built by the server thread");
        assertEquals(1L, this.engine.diagnostics().tickFailures().recorded(RegionTickSlice.ENTITY_TICK));
    }

    /**
     * Fails the tick of {@code entity} on the thread that ticks it.
     *
     * @param started counts down when the tick started, or {@code null}
     * @param release the tick throws once this latch opened, or immediately when {@code null}
     */
    private void failingTick(Entity entity, IllegalStateException failure, CountDownLatch started, CountDownLatch release) {
        doAnswer(invocation -> {
            this.tickThreads.add(Thread.currentThread().getName());
            if (started != null) {
                started.countDown();
            }
            if (release != null) {
                release.await(10, TimeUnit.SECONDS);
            }
            throw failure;
        }).when(entity).tick();
    }

    private void failingTick(Entity entity, IllegalStateException failure, CountDownLatch release) {
        this.failingTick(entity, failure, null, release);
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        return plan;
    }

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        // The seed seam of the self-driven entity tick: the level reports the test's tick list.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doReturn(true).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(false);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        return level;
    }

    private Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        // The recording stub of the report: vanilla fills the report category of the entity it names, so every
        // entry is recorded with the thread that builds the report, and the detail makes it observable in the
        // report text.
        doAnswer(invocation -> {
            this.reportThreads.add(Thread.currentThread().getName());
            final CrashReportCategory category = invocation.getArgument(0);
            category.setDetail("Entity ID", "test-entity-" + entity.getId());
            return null;
        }).when(entity).fillCrashReportCategory(any());
        return entity;
    }

    /**
     * Waits until {@code thread} stopped running, which is how the shutdown thread is observed waiting for the
     * batch of the failing tick.
     */
    private static void awaitThreadBlocked(Thread thread) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() == Thread.State.NEW || thread.getState() == Thread.State.RUNNABLE) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("thread " + thread.getName() + " never blocked on the batch, it is " + thread.getState());
            }
            LockSupport.parkNanos(100_000L);
        }
    }
}
