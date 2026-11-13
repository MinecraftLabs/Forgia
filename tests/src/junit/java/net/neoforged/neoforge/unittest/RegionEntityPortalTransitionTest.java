/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
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
 * Exercises the deferred portal transition of a worker-ticked entity: a transition is a path to the
 * {@code PoiManager} of the destination level, it is reachable from every entity type, and it is therefore
 * recorded by the worker and applied by the batch barrier instead of being resolved on the worker.
 * <p>
 * The level mock reports the test's tick list through the seed seam, so the entity really ticks on a region
 * worker. The portal state itself is stubbed: the test asserts the deferral contract, not the vanilla
 * destination search, which the server thread runs through the unmodified vanilla path.
 */
@Timeout(120)
class RegionEntityPortalTransitionTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.engine = null;
    }

    @Test
    void workerTransitionIsDeferredAndAppliedOnceOnTheServerThread() {
        final ServerLevel level = this.installEngineAndLevel();
        final Cow entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final List<Boolean> recorded = new CopyOnWriteArrayList<>();
        final List<String> transitionThreads = new CopyOnWriteArrayList<>();
        // Two transitions of the same entity in one tick: a primitive entity ticks baseTick and its own body,
        // both of which can enter a portal. At most one transition may be applied.
        doAnswer(invocation -> {
            recorded.add(RegionThreadingHooks.deferPortalTransition(level, entity));
            recorded.add(RegionThreadingHooks.deferPortalTransition(level, entity));
            return null;
        }).when(entity).tick();
        doAnswer(invocation -> {
            transitionThreads.add(Thread.currentThread().getName());
            return null;
        }).when(entity).completePortalTransition(level);

        this.plan(level).executeAndAwait();

        assertEquals(List.of(true, true), recorded, "the worker records the transition instead of resolving it");
        assertEquals(1, transitionThreads.size(), "an entity is transitioned at most once per tick");
        assertFalse(transitionThreads.get(0).startsWith("NeoForge Region Worker"),
                "the destination search must run on the server thread");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1, diagnostics.deferredPortalTransitions());
        assertEquals(1, diagnostics.appliedPortalTransitions());
        assertEquals(0, diagnostics.refusedPortalTransitions());
    }

    @Test
    void transitionOfAnEntityRemovedBeforeTheBarrierIsRefused() {
        final ServerLevel level = this.installEngineAndLevel();
        final Cow entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final AtomicBoolean removed = new AtomicBoolean();
        doAnswer(invocation -> removed.get()).when(entity).isRemoved();
        doAnswer(invocation -> {
            final boolean deferred = RegionThreadingHooks.deferPortalTransition(level, entity);
            // The rest of the tick removes the entity, so the barrier must not transition a removed entity.
            removed.set(true);
            return deferred;
        }).when(entity).tick();

        this.plan(level).executeAndAwait();

        verify(entity, never()).completePortalTransition(level);
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1, diagnostics.deferredPortalTransitions());
        assertEquals(0, diagnostics.appliedPortalTransitions());
        assertEquals(1, diagnostics.refusedPortalTransitions(),
                "a refused transition is counted; the entity keeps its portal state and retries on a later tick");
    }

    @Test
    void transitionOutsideABatchKeepsTheVanillaPath() {
        final ServerLevel level = this.installEngineAndLevel();
        final Cow entity = this.entity(level, new BlockPos(1, 64, 1));

        // No lease on this thread: the caller keeps the inline vanilla transition, exactly like the server
        // thread, the client and every ordinary thread do.
        assertFalse(RegionThreadingHooks.deferPortalTransition(level, entity));
        assertEquals(0, this.engine.diagnostics().deferredPortalTransitions());
    }

    private ServerLevel installEngineAndLevel() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
        final ServerLevel level = this.mockLevel();
        created.addRegionSection(level, 0, 0);
        return level;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        return plan;
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
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> true).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(true);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        return level;
    }

    private Cow entity(ServerLevel level, BlockPos pos) {
        final Cow entity = mock(Cow.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        when(entity.getPassengers()).thenReturn(List.of());
        return entity;
    }
}
