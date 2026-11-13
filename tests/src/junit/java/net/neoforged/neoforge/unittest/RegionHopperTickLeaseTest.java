/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the ownership lease a block-entity tick group opens for the hopper.
 * <p>
 * The hopper is the first admitted type whose tick reaches entity storage, so the block-entity slice has to
 * give its group the same machinery an entity tick group has: an ownership lease that makes the region-scoped
 * query and the entity grants of the lease available, a group random for a direct {@code level.random} read,
 * the block-entity access gate for the containers one block away, and the deferred removal of the item entity
 * it absorbs. The tests drive the real engine, the real plan and the real worker batch against a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} level, and assert the decisions from inside the worker tick.
 */
@Timeout(30)
class RegionHopperTickLeaseTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    /** Chunk 1,1 is the interior chunk of fixed region 0,0, so a hopper there may run on a worker. */
    private static final BlockPos INTERIOR_POSITION = new BlockPos(17, 64, 17);
    /** Chunk 4,1 is the interior chunk of fixed region 1,0, a region of its own. */
    private static final BlockPos SECOND_REGION_POSITION = new BlockPos(65, 64, 17);
    /** A position of the same region as {@link #INTERIOR_POSITION}, two blocks above the hopper. */
    private static final BlockPos OWNED_ENTITY_POSITION = new BlockPos(17, 66, 17);
    /** A position of a different region, so an entity there is never owned by the hopper's group. */
    private static final BlockPos FOREIGN_ENTITY_POSITION = new BlockPos(4096, 66, 0);

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void interiorHopperTicksOnAWorkerUnderTheRegionLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(INTERIOR_POSITION);
        final List<Boolean> workerThreads = new CopyOnWriteArrayList<>();
        final List<RandomSource> groupRandoms = new CopyOnWriteArrayList<>();
        final List<Boolean> gateInsideRegion = new CopyOnWriteArrayList<>();
        final List<Boolean> gateOutsideRegion = new CopyOnWriteArrayList<>();
        final List<Boolean> mutationQueues = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            workerThreads.add(RegionThreadingHooks.isNativeRegionWorkerThread());
            groupRandoms.add(this.engine.currentGroupRandom());
            gateInsideRegion.add(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, INTERIOR_POSITION));
            gateOutsideRegion.add(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, FOREIGN_ENTITY_POSITION));
            mutationQueues.add(RegionThreadingHooks.regionTickMutationQueue() != null);
            return null;
        }).when(ticker).tick();

        plan(level, ticker);

        assertEquals(List.of(true), workerThreads, "an interior hopper must tick on a native region worker");
        assertNotNull(groupRandoms.get(0), "a block-entity tick must resolve the group random, not the shared vanilla one");
        assertEquals(List.of(true), gateInsideRegion,
                "the block-entity access gate must open for the block entities of the leased region");
        assertEquals(List.of(false), gateOutsideRegion,
                "the block-entity access gate must stay closed outside the leased region");
        assertEquals(List.of(true), mutationQueues,
                "a scheduled tick a neighbor update starts must be deferred to the server thread");
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertNull(this.engine.currentGroupRandom(), "the server thread must not keep the group random");
        assertNull(RegionThreadingHooks.regionTickMutationQueue(), "the server thread must not keep a mutation queue");
    }

    @Test
    void everyGroupDrawsItsOwnRandomSource() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity first = mockTicker(INTERIOR_POSITION);
        final TickingBlockEntity second = mockTicker(SECOND_REGION_POSITION);
        final List<RandomSource> randoms = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            randoms.add(this.engine.currentGroupRandom());
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            randoms.add(this.engine.currentGroupRandom());
            return null;
        }).when(second).tick();

        plan(level, first, second);

        assertEquals(2, randoms.size());
        assertNotNull(randoms.get(0), "the first group must install its random");
        assertNotNull(randoms.get(1), "the second group must install its random");
        assertNotSame(randoms.get(0), randoms.get(1),
                "two region groups must never share one random source, because no two workers may draw from it");
        assertEquals(2L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void hopperOwnsTheItemEntitiesOfItsOwnRegionOnly() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(INTERIOR_POSITION);
        final Entity owned = mockEntity(level, OWNED_ENTITY_POSITION);
        final Entity foreign = mockEntity(level, FOREIGN_ENTITY_POSITION);
        final List<Boolean> ownedRemoval = new CopyOnWriteArrayList<>();
        final List<Boolean> foreignRemoval = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ownedRemoval.add(RegionEntityOwnershipScope.grantsEntityMutation(level, owned, "Entity.discard"));
            foreignRemoval.add(RegionEntityOwnershipScope.grantsEntityMutation(level, foreign, "Entity.discard"));
            return null;
        }).when(ticker).tick();

        plan(level, ticker);

        assertEquals(List.of(true), ownedRemoval,
                "the group owns every entity stored in its leased region, so the item entity it absorbs is grantable");
        assertEquals(List.of(false), foreignRemoval,
                "an entity of another region keeps the storage guard");
    }

    @Test
    void absorbedItemEntityIsRemovedAtTheBarrierOnTheServerThread() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final TickingBlockEntity ticker = mockTicker(INTERIOR_POSITION);
        final Entity absorbed = mockEntity(level, OWNED_ENTITY_POSITION);
        when(absorbed.isRemoved()).thenReturn(true);
        final EntityInLevelCallback callback = mock(EntityInLevelCallback.class);
        when(absorbed.getLevelCallback()).thenReturn(callback);
        final List<String> callbackThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            callbackThreads.add(Thread.currentThread().getName());
            return null;
        }).when(callback).onRemove(Entity.RemovalReason.DISCARDED);
        final List<Boolean> accepted = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            accepted.add(RegionEntityOwnershipScope.deferEntityRemoval(absorbed, callback, Entity.RemovalReason.DISCARDED));
            return null;
        }).when(ticker).tick();

        plan(level, ticker);

        assertEquals(List.of(true), accepted, "the removal of an owned item entity must be deferrable from a worker");
        assertEquals(List.of(Thread.currentThread().getName()), callbackThreads,
                "the level callback of the removal must run on the server thread, not on the worker");
        assertEquals(1L, this.engine.diagnostics().deferredEntityRemovalsApplied());
        assertEquals(0L, this.engine.diagnostics().droppedDeferredEntityRemovals());
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void boundaryHopperTicksOnTheServerThreadAndOpensNoLease() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        // Chunk 0,0 borders the regions at -1,0 and 0,-1, so a neighbor-writing hopper cannot be leased.
        final TickingBlockEntity ticker = mockTicker(new BlockPos(1, 64, 1));
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final List<Boolean> mutationQueues = new CopyOnWriteArrayList<>();
        final List<Boolean> gate = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            mutationQueues.add(RegionThreadingHooks.regionTickMutationQueue() != null);
            gate.add(RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, new BlockPos(1, 64, 1)));
            return null;
        }).when(ticker).tick();

        plan(level, ticker);

        assertEquals(List.of(Thread.currentThread().getName()), tickThreads,
                "a boundary hopper must stay on the server thread");
        assertEquals(List.of(false), mutationQueues, "a server-thread tick must not defer a scheduled tick");
        assertEquals(List.of(false), gate, "a server-thread tick must not be granted by the worker gate");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void serverThreadOutsideTheLeaseGrantsNothing() {
        this.installEngine(true);
        final ServerLevel level = mockLevel();
        final Entity entity = mockEntity(level, OWNED_ENTITY_POSITION);

        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(level, entity, "Entity.discard"),
                "without a lease on this thread the storage guard decides, and it refuses");
        assertNull(RegionThreadingHooks.regionTickMutationQueue());
        assertNull(this.engine.currentGroupRandom());
        assertNull(this.engine.currentRegionTickRandom());
    }

    @Test
    void disabledBlockEntityWorkersCollectNoPlan() {
        this.installEngine(false);
        final ServerLevel level = mockLevel();

        assertNull(this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER),
                "the block-entity slice must not collect a plan when its worker execution is off");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    /**
     * Installs an engine with the fixed 3x3 layout the lease containment and the interior-chunk rule use, so
     * the two positions of a test really are two regions.
     */
    private void installEngine(boolean blockEntityTickWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, blockEntityTickWorkers,
                false, false, RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private void plan(ServerLevel level, TickingBlockEntity... tickers) {
        final RegionBlockEntityTickPlan plan = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(plan, "block entity tick worker execution must be enabled");
        for (final TickingBlockEntity ticker : tickers) {
            assertTrue(plan.accept(ticker), "the ticker must be admitted to the worker batch");
        }
        plan.executeAndAwait();
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("hopper-lease-level").when(level).toString();
        // The group random of a block-entity group is derived from the level's dimension, seed and game time.
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        // The lease containment of an entity and the block-entity access gate both use the physical region
        // size of the level, which is the fixed edge length of the installed layout.
        doReturn(3).when(level).entitySectionRegionChunkSize();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        doReturn(true).when(level).areEntitiesLoaded(anyLong());
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

    private static TickingBlockEntity mockTicker(BlockPos pos) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn("minecraft:hopper");
        return ticker;
    }

    private static Entity mockEntity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.isAddedToLevel()).thenReturn(true);
        return entity;
    }
}
