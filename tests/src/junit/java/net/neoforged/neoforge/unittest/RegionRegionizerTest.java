/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RegionRegionizerTest {
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        if (this.engine != null) {
            this.engine.shutdown();
        }
    }

    @Test
    void lastSectionRemovalRetiresRegion() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        final long structuralChanges = this.engine.diagnostics().structuralChangeCount();

        this.engine.removeRegionSection(level, 0, 0);

        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().sectionCount());
        assertEquals(0, this.engine.diagnostics().loadedChunkCount());
        assertTrue(this.engine.diagnostics().structuralChangeCount() > structuralChanges);

        this.engine.addRegionSection(level, 0, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());
    }

    @Test
    void adjacentSectionsMergeIntoOneRegion() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);

        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);

        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(2, this.engine.diagnostics().sectionCount());
        assertEquals(2, this.engine.diagnostics().loadedChunkCount());
        assertTrue(this.engine.diagnostics().mergeCheckRequestCount() > 0L);
    }

    @Test
    void pendingMergeRunsAfterRegionFinishesTicking() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);

        this.engine.runInBlockRegion(level, new BlockPos(0, 0, 0), () -> {
            this.engine.addRegionSection(level, 16, 0);
            assertEquals(2, this.engine.diagnostics().regionCount());
            assertEquals(2, this.engine.diagnostics().pendingRecalculationCount());
        });
        this.drainRegion(level, BlockPos.ZERO);

        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());
        assertEquals(2, this.engine.diagnostics().loadedChunkCount());
    }

    @Test
    void pendingSplitRunsAfterRegionFinishesTicking() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 16, 0);
        this.engine.addRegionSection(level, 32, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());

        this.engine.runInBlockRegion(level, new BlockPos(0, 0, 0), () -> {
            this.engine.removeRegionSection(level, 16, 0);
            assertEquals(1, this.engine.diagnostics().regionCount());
            assertEquals(1, this.engine.diagnostics().pendingRecalculationCount());
        });
        this.drainRegion(level, BlockPos.ZERO);

        assertEquals(2, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().pendingRecalculationCount());
        assertEquals(2, this.engine.diagnostics().loadedChunkCount());
    }

    @Test
    void entityOwnedRegionSurvivesUntilEntityIsRemoved() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel level = mock(ServerLevel.class);
        final Entity entity = entity(level, BlockPos.ZERO);

        this.engine.addRegionEntity(entity);
        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().trackedEntityCount());

        this.engine.removeRegionEntity(entity);
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityCount());
    }

    @Test
    void clearLevelClosesOnlyThatLevelIdentity() {
        this.engine = new RegionThreadingEngine(1, false, false);
        final ServerLevel first = mock(ServerLevel.class);
        final ServerLevel second = mock(ServerLevel.class);
        this.engine.addRegionSection(first, 0, 0);
        this.engine.addRegionSection(second, 0, 0);
        assertEquals(2, this.engine.diagnostics().regionCount());

        this.engine.clearLevelRegionTasks(first);

        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().sectionCount());

        // The unloaded identity stays closed: a later admission must not recreate region state for it.
        this.engine.addRegionSection(first, 0, 0);
        assertEquals(1, this.engine.diagnostics().regionCount());
        assertEquals(1, this.engine.diagnostics().sectionCount());
        final AtomicInteger closedRuns = new AtomicInteger();
        final ScheduledRegionTask closedTask = this.engine.regionScheduler().runDelayed(first, BlockPos.ZERO, closedRuns::incrementAndGet, 0L);
        assertTrue(closedTask.isCancelled());
        this.engine.drainLevelRegionTasks(first);
        assertEquals(0, closedRuns.get());
        final Entity closedEntity = entity(first, BlockPos.ZERO);
        this.engine.addRegionEntity(closedEntity);
        this.engine.moveRegionEntity(closedEntity);
        assertEquals(0, this.engine.diagnostics().trackedEntityCount(), "entity admissions must be dropped for a closed level");
        assertEquals(1, this.engine.diagnostics().regionCount());

        // A reloaded level is a new identity and receives a fresh lifecycle.
        final ServerLevel reloaded = mock(ServerLevel.class);
        this.engine.addRegionSection(reloaded, 0, 0);
        assertEquals(2, this.engine.diagnostics().regionCount());

        this.engine.clearLevelRegionTasks(second);
        assertEquals(1, this.engine.diagnostics().regionCount());
    }

    private void drainRegion(ServerLevel level, BlockPos pos) {
        this.engine.drainRegionTasks(level, pos);
    }

    private static Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        return entity;
    }
}
