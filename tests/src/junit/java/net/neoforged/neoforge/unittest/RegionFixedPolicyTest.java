/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the non-overlapping fixed region policy, including the configured region edge length.
 */
@Timeout(10)
class RegionFixedPolicyTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
    }

    @Test
    void fixedLayoutMapsChunksToNonOverlappingThreeByThreeGrid() {
        final ServerLevel level = mock(ServerLevel.class);
        final RegionLayout layout = RegionLayout.FIXED_3X3;

        for (int chunkX = 0; chunkX < 3; chunkX++) {
            for (int chunkZ = 0; chunkZ < 3; chunkZ++) {
                assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, chunkX, chunkZ));
            }
        }
        assertEquals(new RegionCoordinate(level, 1, 0), layout.regionAt(level, 3, 0));
        assertEquals(new RegionCoordinate(level, 0, 1), layout.regionAt(level, 0, 3));
        assertEquals(new RegionCoordinate(level, -1, -1), layout.regionAt(level, -1, -1));
        assertEquals(new RegionCoordinate(level, -1, 0), layout.regionAt(level, -2, 0));
        assertEquals(new RegionCoordinate(level, -1, 0), layout.regionAt(level, -3, 0));
        assertEquals(new RegionCoordinate(level, -2, 0), layout.regionAt(level, -4, 0));
        assertEquals(new RegionCoordinate(level, -2, -3), layout.regionAt(level, -6, -7));
    }

    @Test
    void fixedLayoutMapsBlockPositionsThroughChunks() {
        final ServerLevel level = mock(ServerLevel.class);
        final RegionLayout layout = RegionLayout.FIXED_3X3;

        assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, new BlockPos(0, 64, 0)));
        assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, new BlockPos(47, 64, 47)));
        assertEquals(new RegionCoordinate(level, 1, 1), layout.regionAt(level, new BlockPos(48, 64, 48)));
        assertEquals(new RegionCoordinate(level, -1, -1), layout.regionAt(level, new BlockPos(-1, 64, -1)));
        assertEquals(new RegionCoordinate(level, -1, -1), layout.regionAt(level, new BlockPos(-48, 64, -48)));
        assertEquals(new RegionCoordinate(level, -2, -2), layout.regionAt(level, new BlockPos(-49, 64, -49)));
    }

    @Test
    void dynamicLayoutKeepsLegacySixteenChunkGrid() {
        final ServerLevel level = mock(ServerLevel.class);
        final RegionLayout layout = RegionLayout.DYNAMIC;

        assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, 0, 0));
        assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, 15, 15));
        assertEquals(new RegionCoordinate(level, 1, 1), layout.regionAt(level, 16, 16));
        assertEquals(new RegionCoordinate(level, -1, -1), layout.regionAt(level, -1, -1));
        assertEquals(new RegionCoordinate(level, -1, -1), layout.regionAt(level, -16, -16));
        assertEquals(new RegionCoordinate(level, -2, -2), layout.regionAt(level, -17, -17));
        assertEquals(new RegionCoordinate(level, 0, 0), layout.regionAt(level, new BlockPos(255, 64, 255)));
        assertEquals(new RegionCoordinate(level, 1, 1), layout.regionAt(level, new BlockPos(256, 64, 256)));
        assertFalse(layout.isFixed());
    }

    @Test
    void productionConfigDefaultsToFixedPolicy() {
        assertEquals(RegionPolicy.FIXED_3X3, NeoForgeConfig.SERVER.regionThreadingMode.getDefault());
        assertEquals(RegionPolicy.FIXED_3X3, RegionLayout.of(RegionPolicy.FIXED_3X3).policy());
        assertEquals(RegionPolicy.DYNAMIC, RegionLayout.of(RegionPolicy.DYNAMIC).policy());
    }

    @Test
    void productionConfigDefaultsToTheSizeThreeFixedRegion() {
        assertEquals(RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE, NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.getDefault().intValue());
        assertEquals(RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE, NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.getDefault().intValue());
        // The production wiring builds one layout from the two config values and hands it to the engine.
        final RegionLayout configured = RegionLayout.of(
                NeoForgeConfig.SERVER.regionThreadingMode.getDefault(),
                NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.getDefault());
        assertSame(RegionLayout.FIXED_3X3, configured);
        assertEquals(RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE, configured.regionChunkSize());
    }

    @Test
    void fixedLayoutUsesTheConfiguredEdgeLengthAtBoundariesAndNegativeChunks() {
        final ServerLevel level = mock(ServerLevel.class);
        for (int size : new int[] { 3, 9, 16 }) {
            final RegionLayout layout = RegionLayout.fixed(size);
            assertTrue(layout.isFixed());
            assertEquals(RegionPolicy.FIXED_3X3, layout.policy());
            assertEquals(size, layout.regionChunkSize());
            for (int region = -3; region <= 3; region++) {
                final int firstChunk = region * size;
                final int lastChunk = firstChunk + size - 1;
                assertEquals(new RegionCoordinate(level, region, region), layout.regionAt(level, firstChunk, firstChunk),
                        "first chunk of region " + region + " for size " + size);
                assertEquals(new RegionCoordinate(level, region, region), layout.regionAt(level, lastChunk, lastChunk),
                        "last chunk of region " + region + " for size " + size);
                assertEquals(region, layout.regionX(firstChunk));
                assertEquals(region, layout.regionZ(lastChunk));
                assertEquals(region - 1, layout.regionX(firstChunk - 1), "the chunk before the first chunk belongs to the previous region");
                assertEquals(region + 1, layout.regionZ(lastChunk + 1), "the chunk after the last chunk belongs to the next region");
                // A block inside the first chunk maps to the same region as that chunk.
                assertEquals(new RegionCoordinate(level, region, region), layout.regionAt(level, new BlockPos(firstChunk * 16, 64, firstChunk * 16)));
            }
        }
    }

    @Test
    void sizeThreeReproducesTheOriginalFixedRegionCoordinates() {
        final ServerLevel level = mock(ServerLevel.class);
        final int[] chunks = { 0, 1, 2, 3, 4, 5, 6, 47, 48, -1, -2, -3, -4, -5, -6, -7, -48, -49 };
        final int[] expectedRegions = { 0, 0, 0, 1, 1, 1, 2, 15, 16, -1, -1, -1, -2, -2, -2, -3, -16, -17 };
        final RegionLayout configured = RegionLayout.of(RegionPolicy.FIXED_3X3, RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE);
        for (int index = 0; index < chunks.length; index++) {
            final int chunk = chunks[index];
            final int expected = expectedRegions[index];
            assertEquals(expected, RegionLayout.FIXED_3X3.regionX(chunk), "default fixed layout at chunk " + chunk);
            assertEquals(expected, configured.regionX(chunk), "configured size-3 layout at chunk " + chunk);
            assertEquals(expected, RegionLayout.of(RegionPolicy.FIXED_3X3).regionX(chunk), "policy-only layout at chunk " + chunk);
            assertEquals(new RegionCoordinate(level, expected, expected), configured.regionAt(level, chunk, chunk));
            assertEquals(Math.floorDiv(chunk, 3), expected, "the fixed grid stays the global floorDiv grid");
        }
    }

    @Test
    void fixedRegionChunkSizeIsClampedByTheConfigAndRejectedByTheLayout() {
        // An existing config file with an out-of-range value is clamped into 3..32 when the config loads.
        assertEquals(RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE,
                ((Integer) NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.getSpec().correct(RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE - 1)).intValue());
        assertEquals(RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE,
                ((Integer) NeoForgeConfig.SERVER.regionThreadingFixedRegionChunks.getSpec().correct(RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE + 1)).intValue());
        // The layout constructor path rejects instead of clamping, so nothing can build an engine or
        // partition set with a size it did not ask for.
        assertThrows(IllegalArgumentException.class, () -> RegionLayout.fixed(RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE - 1));
        assertThrows(IllegalArgumentException.class, () -> RegionLayout.fixed(RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> RegionLayout.of(RegionPolicy.FIXED_3X3, 0));
        assertSame(RegionLayout.FIXED_3X3, RegionLayout.fixed(RegionLayout.MIN_FIXED_REGION_CHUNK_SIZE));
        assertEquals(RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE, RegionLayout.fixed(RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE).regionChunkSize());
        // The dynamic grid has no configurable fixed size, so the value is ignored instead of validated.
        assertSame(RegionLayout.DYNAMIC, RegionLayout.of(RegionPolicy.DYNAMIC, RegionLayout.MAX_FIXED_REGION_CHUNK_SIZE + 1));
    }

    @Test
    void engineDiagnosticsReportSelectedPolicy() {
        this.fixedEngine();
        assertEquals(RegionPolicy.FIXED_3X3, this.engine.diagnostics().regionPolicy());

        this.engine.shutdown();
        this.engine = new RegionThreadingEngine(1, false, false);
        assertEquals(RegionPolicy.DYNAMIC, this.engine.diagnostics().regionPolicy());
    }

    @Test
    void engineDiagnosticsReportTheConfiguredRegionChunkSize() {
        this.engine = new RegionThreadingEngine(1, false, false, false, false, false, false, RegionLayout.fixed(9));
        assertEquals(RegionPolicy.FIXED_3X3, this.engine.diagnostics().regionPolicy());
        assertEquals(9, this.engine.diagnostics().regionChunkSize());
        assertEquals(9, this.engine.regionLayout().regionChunkSize());

        this.engine.shutdown();
        this.engine = new RegionThreadingEngine(1, false, false, false, false, RegionPolicy.FIXED_3X3);
        assertEquals(RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE, this.engine.diagnostics().regionChunkSize());

        this.engine.shutdown();
        this.engine = new RegionThreadingEngine(1, false, false, false, false, RegionPolicy.DYNAMIC);
        assertEquals(RegionLayout.DYNAMIC_REGION_CHUNK_SIZE, this.engine.diagnostics().regionChunkSize());
    }

    @Test
    void engineMapsChunksAndSectionsWithTheConfiguredSize() {
        this.engine = new RegionThreadingEngine(1, false, false, false, false, false, false, RegionLayout.fixed(9));
        final ServerLevel level = mock(ServerLevel.class);
        // Nine chunks of one edge share one region; the ninth neighbor starts the next one.
        for (int chunkX = 0; chunkX < 9; chunkX++) {
            this.engine.addRegionSection(level, chunkX, 0);
        }
        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(9, this.engine.diagnostics().loadedChunkCount());

        this.engine.addRegionSection(level, 9, 0);
        assertEquals(2, this.engine.diagnostics().regionCount());
        assertEquals(0L, this.engine.diagnostics().mergeCheckRequestCount());
    }

    @Test
    void allNineChunksShareOneFixedRegionAndTheTenthIsDistinct() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        for (int chunkX = 0; chunkX < 3; chunkX++) {
            for (int chunkZ = 0; chunkZ < 3; chunkZ++) {
                this.engine.addRegionSection(level, chunkX, chunkZ);
            }
        }

        RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1, diagnostics.regionCount());
        assertEquals(1, diagnostics.sectionCount());
        assertEquals(9, diagnostics.loadedChunkCount());
        assertEquals(0L, diagnostics.mergeCheckRequestCount());
        assertEquals(0L, diagnostics.splitCheckRequestCount());

        this.engine.addRegionSection(level, 3, 0);

        diagnostics = this.engine.diagnostics();
        assertEquals(2, diagnostics.regionCount());
        assertEquals(2, diagnostics.sectionCount());
        assertEquals(10, diagnostics.loadedChunkCount());
        assertEquals(0L, diagnostics.mergeCheckRequestCount());

        for (int chunkX = 0; chunkX < 3; chunkX++) {
            for (int chunkZ = 0; chunkZ < 3; chunkZ++) {
                this.engine.removeRegionSection(level, chunkX, chunkZ);
            }
        }
        diagnostics = this.engine.diagnostics();
        assertEquals(1, diagnostics.regionCount());
        assertEquals(1, diagnostics.loadedChunkCount());
        assertEquals(0L, diagnostics.splitCheckRequestCount());

        this.engine.removeRegionSection(level, 3, 0);
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void adjacentFixedRegionsNeverMergeOrSplit() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 3, 0);
        this.engine.addRegionSection(level, 0, 3);

        assertEquals(3, this.engine.diagnostics().regionCount());
        assertEquals(0L, this.engine.diagnostics().mergeCheckRequestCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());

        final Entity sameRegion = entity(level, new BlockPos(47, 64, 47));
        final Entity adjacentRegion = entity(level, new BlockPos(48, 64, 0));
        this.engine.addRegionEntity(sameRegion);
        this.engine.addRegionEntity(adjacentRegion);

        assertEquals(3, this.engine.diagnostics().regionCount());
        assertEquals(0L, this.engine.diagnostics().mergeCheckRequestCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());

        this.engine.removeRegionEntity(sameRegion);
        this.engine.removeRegionEntity(adjacentRegion);
        this.engine.removeRegionSection(level, 0, 0);

        assertEquals(2, this.engine.diagnostics().regionCount());
        assertEquals(0L, this.engine.diagnostics().splitCheckRequestCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());
    }

    @Test
    void fixedRegionRetiresAndReactivatesAtTheSameCoordinateWithoutForcedLoads() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 1, 1);

        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().sectionCount());
        assertEquals(1, this.engine.diagnostics().loadedChunkCount());
        final Object firstState = regionState(this.engine, new RegionCoordinate(level, 0, 0));

        this.engine.removeRegionSection(level, 1, 1);
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().loadedChunkCount());

        this.engine.addRegionSection(level, 0, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().sectionCount());
        assertEquals(1, this.engine.diagnostics().loadedChunkCount());

        final Object secondState = regionState(this.engine, new RegionCoordinate(level, 0, 0));
        assertNotSame(firstState, secondState, "a reactivated fixed region must be a fresh live state");
        assertEquals(new RegionCoordinate(level, 0, 0), regionCoordinate(secondState));
    }

    @Test
    void fixedRegionStaysAliveForEntitiesAndQueuedTasks() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, new BlockPos(1, 64, 1));
        this.engine.addRegionEntity(entity);
        assertEquals(1, this.engine.diagnostics().regionCount());

        this.engine.removeRegionEntity(entity);
        assertEquals(0, this.engine.diagnostics().regionCount());

        final AtomicInteger runs = new AtomicInteger();
        final ScheduledRegionTask task = this.engine.regionScheduler().runDelayed(level, new BlockPos(1, 64, 1), runs::incrementAndGet, 1L);
        assertEquals(1, this.engine.diagnostics().regionCount());

        this.engine.drainRegionTasks(level, new BlockPos(1, 64, 1));
        assertEquals(0, runs.get());
        assertEquals(1, this.engine.diagnostics().regionCount());

        this.engine.drainRegionTasks(level, new BlockPos(1, 64, 1));
        assertEquals(1, runs.get());
        assertTrue(task.isDone());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void fixedChunksEntitiesAndTasksShareTheSameMapping() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = entity(level, new BlockPos(47, 64, 47));
        this.engine.addRegionEntity(entity);
        final AtomicInteger runs = new AtomicInteger();

        this.engine.regionScheduler().execute(level, new BlockPos(1, 64, 1), () -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, new BlockPos(47, 64, 47)));
            assertTrue(this.engine.isOwnedByCurrentRegion(entity));
            assertFalse(this.engine.isOwnedByCurrentRegion(level, new BlockPos(48, 64, 0)));
            runs.incrementAndGet();
        });
        this.engine.drainRegionTasks(level, new BlockPos(1, 64, 1));

        assertEquals(1, runs.get());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void fixedBlockEntityTickersShareTheChunkMapping() {
        this.engine = new RegionThreadingEngine(2, false, false, false, true, RegionPolicy.FIXED_3X3);
        final ServerLevel level = mock(ServerLevel.class);
        when(level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(level.toString()).thenReturn("fixed-policy-test-level");
        // The group random of a block-entity group is derived from the level's dimension, seed and game time.
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        // This test is about the region mapping of admitted tickers, not about the entity-load admission
        // that is proven against a real entity manager in RegionBlockEntityTickEligibilityTest.
        when(level.areEntitiesLoaded(anyLong())).thenReturn(true);
        // Interior chunks of fixed regions 0,0 and 1,0; a furnace may only leave the server thread there.
        final TickingBlockEntity sameRegion = ticker(new BlockPos(17, 64, 17));
        final TickingBlockEntity adjacentRegion = ticker(new BlockPos(65, 64, 17));
        doAnswer(invocation -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, new BlockPos(17, 64, 17)));
            assertFalse(this.engine.isOwnedByCurrentRegion(level, new BlockPos(65, 64, 17)));
            return null;
        }).when(sameRegion).tick();
        doAnswer(invocation -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, new BlockPos(65, 64, 17)));
            assertFalse(this.engine.isOwnedByCurrentRegion(level, new BlockPos(17, 64, 17)));
            return null;
        }).when(adjacentRegion).tick();

        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(plan);
        plan.accept(sameRegion);
        plan.accept(adjacentRegion);
        plan.executeAndAwait();

        verify(sameRegion, times(1)).tick();
        verify(adjacentRegion, times(1)).tick();
        assertEquals(2L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().mergeCheckRequestCount());
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void fixedEntityTaskHandsOffAcrossTheRegionBoundary() {
        this.fixedEngine();
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, new BlockPos(47, 64, 47));
        final AtomicInteger runs = new AtomicInteger();
        final AtomicReference<RegionCoordinate> lastRegion = new AtomicReference<>();
        final ScheduledRegionTask task = this.engine.entityScheduler().runAtFixedRate(entity, () -> {
            assertTrue(this.engine.isOwnedByCurrentRegion(entity));
            lastRegion.set(RegionLayout.FIXED_3X3.regionAt(level, entity.blockPosition()));
            runs.incrementAndGet();
        }, 1L, 2L);

        this.engine.drainRegionTasks(level, new BlockPos(47, 64, 47));
        assertEquals(0, runs.get());
        this.engine.drainRegionTasks(level, new BlockPos(47, 64, 47));
        assertEquals(1, runs.get());
        assertEquals(new RegionCoordinate(level, 0, 0), lastRegion.get());

        when(entity.blockPosition()).thenReturn(new BlockPos(48, 64, 48));
        this.engine.moveRegionEntity(entity);

        this.engine.drainRegionTasks(level, new BlockPos(47, 64, 47));
        assertEquals(1, runs.get(), "the old fixed region must not tick the migrated entity task");
        this.engine.drainRegionTasks(level, new BlockPos(48, 64, 48));
        assertEquals(1, runs.get());
        this.engine.drainRegionTasks(level, new BlockPos(48, 64, 48));
        assertEquals(2, runs.get());
        assertEquals(new RegionCoordinate(level, 1, 1), lastRegion.get());
        assertFalse(task.isDone());
        assertTrue(task.cancel());
    }

    private RegionThreadingEngine fixedEngine() {
        this.engine = new RegionThreadingEngine(1, false, false, false, false, RegionPolicy.FIXED_3X3);
        return this.engine;
    }

    private static Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        return entity;
    }

    private static TickingBlockEntity ticker(BlockPos pos) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn("minecraft:furnace");
        return ticker;
    }

    private static Object regionState(RegionThreadingEngine engine, RegionCoordinate coordinate) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(engine);
            final Method get = regionizer.getClass().getDeclaredMethod("get", RegionCoordinate.class);
            get.setAccessible(true);
            final Object region = get.invoke(regionizer, coordinate);
            assertNotNull(region, "the fixed region was retired before it could be inspected");
            return region;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static RegionCoordinate regionCoordinate(Object region) {
        try {
            final Method coordinate = region.getClass().getDeclaredMethod("coordinate");
            coordinate.setAccessible(true);
            return (RegionCoordinate) coordinate.invoke(region);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
