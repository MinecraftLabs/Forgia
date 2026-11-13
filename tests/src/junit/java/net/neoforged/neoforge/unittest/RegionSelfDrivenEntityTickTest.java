/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
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
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the self-driven entity tick against the real engine: the server thread activates and leases the
 * level's regions, one worker task per region enumerates the region's entity tick index, and the server thread
 * only runs the handoff.
 * <p>
 * The level mock reports the test's tick list through the seed seam, so the production path is the one under
 * test: no entity is collected on the server thread. The level is a {@code CALLS_REAL_METHODS} mock, so the
 * worker runs the real {@code guardEntityTick} and {@code tickNonPassenger} code with mock entities.
 */
@Timeout(60)
class RegionSelfDrivenEntityTickTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;

    private RegionThreadingEngine engine;
    /** The level's entity tick list as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    /** Chunks the distance manager does not report as entity-ticking. */
    private final List<ChunkPos> nonTickingChunks = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.nonTickingChunks.clear();
        this.engine = null;
    }

    @Test
    void selfDrivenTickRunsExactlyTheEntitiesOfEachRegionOnItsWorker() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // One region per entity: chunk 0 is region 0 and chunk 3 is region 1 of the fixed 3x3 layout.
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final Entity west = this.entity(level, new BlockPos(1, 64, 1));
        final Entity eastFirst = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        final Entity eastSecond = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 2, 64, 1));
        this.tickList.addAll(List.of(west, eastFirst, eastSecond));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        for (final Entity entity : List.of(west, eastFirst, eastSecond)) {
            doAnswer(invocation -> {
                tickThreads.add(Thread.currentThread().getName());
                return null;
            }).when(entity).tick();
        }

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.ticksEntitiesOnRegionWorkers(), "the entity tick must be driven by the regions");
        plan.executeAndAwait();

        verify(west, times(1)).tick();
        verify(eastFirst, times(1)).tick();
        verify(eastSecond, times(1)).tick();
        assertEquals(3, tickThreads.size());
        for (final String thread : tickThreads) {
            assertTrue(thread.startsWith("NeoForge Region Worker"), "an entity of a region must tick on its region worker: " + thread);
        }
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(3, diagnostics.entityTicksOnWorkers());
        assertEquals(2, diagnostics.entityTickRegionsOnWorkers(), "one worker task per active region");
        assertEquals(0, diagnostics.entityTicksOnWorkers() - 3);
        assertEquals(0, diagnostics.fallbackEntityTicks(), "a covered entity must not fall back");
        assertEquals(0, diagnostics.fallbackEntityTickRegions());
        assertEquals(0, diagnostics.entityTicksOutsideRegion());
        assertEquals(0, diagnostics.serverThreadOnlyEntityTicks());
    }

    @Test
    void crossRegionMoveIsPublishedAtTheBarrierAndTickedByItsNewRegionNextTick() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(1, 64, 1));
        final Entity entity = this.entity(level, position);
        this.tickList.add(entity);
        doAnswer(invocation -> {
            position.set(new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
            // The deferred ownership update the entity tick posts: the worker never writes the mapping.
            RegionThreadingHooks.moveRegionEntity(entity);
            return null;
        }).when(entity).tick();

        // Opening the plan seeds the region index, which publishes the mapping at the start position.
        final RegionEntityTickPlan plan = this.plan(level);
        assertEquals(0, regionX(entity), "the entity starts in region 0");
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, regionX(entity), "the barrier must publish the region the tick moved the entity into");

        // The next tick dispatches the new region with the migrated index entry and ticks the entity once.
        this.plan(level).executeAndAwait();

        verify(entity, times(2)).tick();
        assertEquals(2, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().entityTicksOutsideRegion(), "an entity that moved must not leave the tick");
    }

    @Test
    void readOnlyGroupingDoesNotWriteTheRegionizerAndGroupsLikeTheWritePath() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final Entity tracked = this.entity(level, new BlockPos(1, 64, 1));
        this.engine.addRegionEntity(tracked);
        final long writeLocks = this.engine.diagnostics().regionizerWriteLockAcquisitions();

        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertTrue(plan.accept(tracked));
        assertEquals(writeLocks, this.engine.diagnostics().regionizerWriteLockAcquisitions(),
                "grouping an already tracked entity must not take the regionizer write lock");

        // The first observation of an untracked entity is the only one that publishes the mapping.
        final Entity untracked = this.entity(level, new BlockPos(2, 64, 1));
        assertTrue(plan.accept(untracked));
        assertEquals(writeLocks + 1, this.engine.diagnostics().regionizerWriteLockAcquisitions(),
                "exactly one write lock acquisition publishes the mapping of an untracked entity");

        // The read-only grouping and the write grouping name the same region for the same position.
        final Entity readOnly = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        final Entity writeGrouped = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        this.engine.addRegionEntity(readOnly);
        final RegionEntityTickPlan readOnlyPlan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        final RegionEntityTickPlan writePlan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertTrue(readOnlyPlan.accept(readOnly));
        assertTrue(writePlan.accept(writeGrouped));
        assertEquals(regionX(readOnly), regionX(writeGrouped), "the read-only grouping must group like the write path");
    }

    @Test
    void alwaysTickingEntityIsTickedByTheServerThread() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity player = this.entity(level, new BlockPos(1, 64, 1));
        when(player.isAlwaysTicking()).thenReturn(true);
        this.tickList.add(player);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(player).tick();

        this.plan(level).executeAndAwait();

        verify(player, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "an always-ticking entity must not tick on a worker");
        assertEquals(1, this.engine.diagnostics().serverThreadOnlyEntityTicks());
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(1, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void entityThatLeftTheRegionRectangleIsTickedByTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(1, 64, 1));
        final Entity entity = this.entity(level, position);
        this.tickList.add(entity);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).tick();

        // Opening the plan publishes the mapping at the start position; the position then outruns it without
        // a tick, so the region's activation snapshot cannot decide the entity's chunk.
        final RegionEntityTickPlan plan = this.plan(level);
        position.set(new BlockPos(30 * 16 + 1, 64, 1));
        plan.executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, this.engine.diagnostics().entityTicksOutsideRegion());
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"), "an undecidable entity must tick on the server thread");
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void regionPublishedByItsEntityIsActivatedWithoutAnyChunkLoadEvent() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // No chunk load is reported to this engine: the region gets its chunk rectangle from the mapping that
        // publishes the entity, which is what an engine installed after the level's chunks were loaded sees.
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);

        this.plan(level).executeAndAwait();

        verify(entity, times(1)).tick();
        assertEquals(1, this.engine.diagnostics().entityTickRegionsOnWorkers());
        assertEquals(1, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().fallbackEntityTickRegions());
    }

    @Test
    void entityOutsideTheEntityTickingRangeIsDespawnedButNotTicked() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        // Every chunk of the region's rectangle is outside the entity ticking range, so the region is
        // dispatched for the despawn check only.
        for (int chunkX = 0; chunkX < REGION_CHUNK_SIZE; chunkX++) {
            for (int chunkZ = 0; chunkZ < REGION_CHUNK_SIZE; chunkZ++) {
                this.nonTickingChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        this.plan(level).executeAndAwait();

        verify(entity, times(1)).checkDespawn();
        verify(entity, never()).tick();
        assertEquals(1, this.engine.diagnostics().entityTickRegionsWithoutEligibleChunk());
        assertEquals(0, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(0, this.engine.diagnostics().fallbackEntityTicks());
    }

    @Test
    void levelWithoutATickListFallsBackToThePerEntityPlan() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        // A level that cannot report its tick list keeps the per-entity collection of the slice.
        doReturn(false).when(level).forEachEntityInTickListForRegionIndex(any());

        final RegionEntityTickPlan plan = this.plan(level);

        assertFalse(plan.ticksEntitiesOnRegionWorkers());
        assertEquals(1, this.engine.diagnostics().entityTickIndexFallbacks());
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
        doAnswer(invocation -> {
            final long chunkKey = invocation.getArgument(0);
            return !this.nonTickingChunks.contains(new ChunkPos(chunkKey));
        }).when(level).isInEntityTickingRange(anyLong());
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
        return this.entity(level, new AtomicReference<>(pos));
    }

    private Entity entity(ServerLevel level, AtomicReference<BlockPos> position) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenAnswer(invocation -> position.get());
        when(entity.chunkPosition()).thenAnswer(invocation -> {
            final BlockPos pos = position.get();
            return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        });
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }

    private int regionX(Entity entity) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(this.engine);
        final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
        assertNotNull(region, "the entity is not tracked by the regionizer");
        final Method coordinateMethod = region.getClass().getDeclaredMethod("coordinate");
        coordinateMethod.setAccessible(true);
        return ((RegionCoordinate) coordinateMethod.invoke(region)).regionX();
    }
}
