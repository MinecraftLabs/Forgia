/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import net.neoforged.neoforge.server.threading.region.LevelTickPhase;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionMailbox;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the explicit global/region phase of the level tick and the cross-region mailbox against the real
 * engine.
 * <p>
 * The level is the same {@code CALLS_REAL_METHODS} mock the self-driven entity tick tests use, so the production
 * entity slice is the producer of region work: a region workers' group is the region phase, and the test thread is
 * the server thread that owns the global phase.
 */
@Timeout(60)
class RegionGlobalPhaseMailboxTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;
    private static final String WORKER_THREAD_PREFIX = "NeoForge Region Worker";

    private RegionThreadingEngine engine;
    /** The level's entity tick list as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final List<ChunkPos> nonTickingChunks = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        this.tickList.clear();
        this.nonTickingChunks.clear();
    }

    @Test
    void levelTickPhaseIsGlobalAndTheWorkerRunsInTheRegionPhase() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final List<LevelTickPhase> phasesInTheBody = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            phasesInTheBody.add(RegionThreadingHooks.levelTickPhase(level));
            return null;
        }).when(entity).tick();

        RegionThreadingHooks.beginLevelTick(level);
        assertEquals(LevelTickPhase.GLOBAL, RegionThreadingHooks.levelTickPhase(level), "the level tick opens in its global phase");
        this.plan(level).executeAndAwait();
        assertEquals(List.of(LevelTickPhase.REGION), phasesInTheBody, "the region body runs in the region phase");
        assertEquals(LevelTickPhase.GLOBAL, RegionThreadingHooks.levelTickPhase(level), "the batch hands the level back to its global phase");
        RegionThreadingHooks.endLevelTick(level);

        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1L, diagnostics.globalPhases());
        assertEquals(1L, diagnostics.regionPhases());
        assertEquals(0L, diagnostics.regionPhasesWithoutGlobalPhase(), "a region phase of the level tick is inside the global phase");
        assertEquals(0L, diagnostics.abandonedGlobalSegments());
        assertEquals(0L, diagnostics.ownershipViolationCount(), "the phase model was not violated");
    }

    @Test
    void globalMessagesAreDeliveredAtTheHeadOfTheNextLevelTickInPostingOrder() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final List<String> order = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<LevelTickPhase> phases = new CopyOnWriteArrayList<>();

        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> {
            order.add("first");
            threads.add(Thread.currentThread().getName());
            phases.add(RegionThreadingHooks.levelTickPhase(level));
        }));
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> order.add("second")));
        assertEquals(List.of(), order, "no global message runs outside a global phase");

        RegionThreadingHooks.beginLevelTick(level);
        assertEquals(List.of("first", "second"), order, "the global phase drains in posting order");
        assertEquals(List.of(Thread.currentThread().getName()), threads, "a global message runs on the server thread");
        assertEquals(List.of(LevelTickPhase.GLOBAL), phases, "the global message runs in the global phase");
        RegionThreadingHooks.endLevelTick(level);

        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(2L, diagnostics.mailboxAcceptedMessages());
        assertEquals(2L, diagnostics.mailboxDeliveredMessages());
        assertEquals(0L, diagnostics.mailboxDeliveryFailures());
    }

    @Test
    void globalMessagePostedByARegionWorkerIsDeliveredOnTheServerThreadBeforeTheSliceReturns() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final List<String> deliveredOn = new CopyOnWriteArrayList<>();
        final AtomicReference<LevelTickPhase> deliveredIn = new AtomicReference<>();
        doAnswer(invocation -> {
            assertTrue(Thread.currentThread().getName().startsWith(WORKER_THREAD_PREFIX), "the entity must tick on a region worker");
            assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> {
                deliveredOn.add(Thread.currentThread().getName());
                deliveredIn.set(RegionThreadingHooks.levelTickPhase(level));
            }));
            assertTrue(deliveredOn.isEmpty(), "the worker may not run a global message itself");
            return null;
        }).when(entity).tick();

        RegionThreadingHooks.beginLevelTick(level);
        this.plan(level).executeAndAwait();

        assertEquals(List.of(Thread.currentThread().getName()), deliveredOn, "the global message is delivered on the server thread");
        assertEquals(LevelTickPhase.GLOBAL, deliveredIn.get(), "the delivery happens in the global phase, before the slice returns");
        assertEquals(LevelTickPhase.GLOBAL, RegionThreadingHooks.levelTickPhase(level));
        RegionThreadingHooks.endLevelTick(level);
        assertEquals(1L, this.engine.diagnostics().mailboxDeliveredMessages());
    }

    @Test
    void regionMessageIsDeliveredExactlyOnceAtTheStartOfTheTargetRegionsGroup() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final Entity west = this.entity(level, new BlockPos(1, 64, 1));
        final Entity east = this.entity(level, new BlockPos(REGION_CHUNK_SIZE * 16 + 1, 64, 1));
        this.tickList.addAll(List.of(west, east));
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final RegionCoordinate eastRegion = new RegionCoordinate(level, 1, 0);
        final List<String> order = new CopyOnWriteArrayList<>();
        final AtomicReference<String> deliveryThread = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean posted = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            if (posted.compareAndSet(false, true)) {
                order.add("post-west");
                assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.post(eastRegion, () -> {
                    order.add("deliver");
                    deliveryThread.set(Thread.currentThread().getName());
                }));
            }
            return null;
        }).when(west).tick();
        doAnswer(invocation -> {
            order.add("body-east");
            return null;
        }).when(east).tick();

        this.plan(level).executeAndAwait();
        this.plan(level).executeAndAwait();

        assertEquals(1, order.stream().filter("deliver"::equals).count(), "a message is delivered exactly once");
        assertTrue(order.indexOf("deliver") > order.indexOf("post-west"), "the message is delivered after it was posted");
        assertTrue(order.indexOf("deliver") < order.lastIndexOf("body-east"), "the message is delivered before the target region's body");
        assertNotNull(deliveryThread.get());
        assertTrue(deliveryThread.get().startsWith(WORKER_THREAD_PREFIX), "the message runs on the target region's worker: " + deliveryThread.get());
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1L, diagnostics.mailboxAcceptedMessages());
        assertEquals(1L, diagnostics.mailboxDeliveredMessages());
        assertEquals(0L, diagnostics.mailboxDroppedRetiredMessages());
        assertEquals(0L, diagnostics.mailboxRefusedFullMessages());
    }

    @Test
    void fullQueueRefusesAndCountsAndTheSenderObservesIt() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final RegionCoordinate target = new RegionCoordinate(level, 0, 0);

        for (int i = 0; i < RegionMailbox.DEFAULT_QUEUE_CAPACITY; i++) {
            assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.post(target, () -> {}), "message " + i);
        }
        assertEquals(RegionMailbox.PostResult.QUEUE_FULL, mailbox.post(target, () -> {}), "the sender observes the full queue");
        for (int i = 0; i < RegionMailbox.DEFAULT_QUEUE_CAPACITY; i++) {
            assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> {}), "global message " + i);
        }
        assertEquals(RegionMailbox.PostResult.QUEUE_FULL, mailbox.postGlobal(() -> {}));

        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(2L * RegionMailbox.DEFAULT_QUEUE_CAPACITY, diagnostics.mailboxAcceptedMessages());
        assertEquals(2L, diagnostics.mailboxRefusedFullMessages(), "one refusal per full queue");
        assertEquals(0L, diagnostics.mailboxDeliveredMessages(), "a refused message is never delivered");
    }

    @Test
    void messageToARetiredRegionIsCountedAndNeverDeliveredToTheRecreatedRegion() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final RegionCoordinate target = new RegionCoordinate(level, 0, 0);
        this.engine.addRegionSection(level, 0, 0);
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final AtomicInteger deliveries = new AtomicInteger();
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.post(target, deliveries::incrementAndGet));

        // The region is retired and a new one is created under the same coordinate. The queued message is
        // addressed to the retired instance, so it must not be applied to the new one.
        this.engine.removeRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        this.plan(level).executeAndAwait();

        assertEquals(0, deliveries.get(), "a message of a retired region is never applied");
        assertEquals(1L, this.engine.diagnostics().mailboxDroppedRetiredMessages());
        assertEquals(0L, this.engine.diagnostics().mailboxDeliveredMessages());

        // A coordinate without a live region cannot be posted to at all.
        assertEquals(RegionMailbox.PostResult.TARGET_RETIRED, mailbox.post(new RegionCoordinate(level, 30, 30), deliveries::incrementAndGet));
        assertEquals(2L, this.engine.diagnostics().mailboxDroppedRetiredMessages());
    }

    @Test
    void closingTheMailboxDropsQueuedMessagesRefusesLaterPostsAndStrandsNothing() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final AtomicInteger deliveries = new AtomicInteger();
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.post(new RegionCoordinate(level, 0, 0), deliveries::incrementAndGet));
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.post(new RegionCoordinate(level, 0, 0), deliveries::incrementAndGet));
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(deliveries::incrementAndGet));
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(deliveries::incrementAndGet));

        this.engine.shutdown();

        assertEquals(0, deliveries.get(), "a close drops the queued messages instead of delivering them");
        assertEquals(RegionMailbox.PostResult.MAILBOX_CLOSED, mailbox.postGlobal(deliveries::incrementAndGet));
        assertTrue(mailbox.isClosed());
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(4L, diagnostics.mailboxDroppedOnCloseMessages());
        assertEquals(1L, diagnostics.mailboxRefusedClosedMessages());
        assertEquals(0L, diagnostics.mailboxDeliveredMessages());
    }

    @Test
    void aFailingMessageIsCountedAndDoesNotStopTheDrain() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        final RegionMailbox mailbox = this.engine.mailbox(level);
        final List<String> order = new CopyOnWriteArrayList<>();
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> {
            throw new IllegalStateException("broken message");
        }));
        assertEquals(RegionMailbox.PostResult.ACCEPTED, mailbox.postGlobal(() -> order.add("after")));

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);

        assertEquals(List.of("after"), order, "the drain continues after a failing message");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(1L, diagnostics.mailboxDeliveryFailures());
        assertEquals(1L, diagnostics.mailboxDeliveredMessages());
    }

    @Test
    void globalStateWriteIsRejectedInARegionPhaseAndAllowedInTheGlobalPhase() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        RegionThreadingHooks.beginLevelTick(level);

        // The server thread owns the global statements, so the write is allowed here.
        RegionThreadingHooks.assertGlobalStateWrite(level, "test");
        assertEquals(0L, this.engine.diagnostics().ownershipViolationCount());

        this.engine.beginRegionPhase(level);
        try {
            RegionThreadingHooks.assertGlobalStateWrite(level, "ServerLevel.setDayTime");
            assertEquals(1L, this.engine.diagnostics().ownershipViolationCount(), "a write of global state in the region phase is reported");
        } finally {
            this.engine.endRegionPhase(level);
        }
        RegionThreadingHooks.endLevelTick(level);

        // The worker of a region group is always in a region phase, so the same write is reported there.
        this.engine.addRegionSection(level, 0, 0);
        final Entity entity = this.entity(level, new BlockPos(1, 64, 1));
        this.tickList.add(entity);
        doAnswer(invocation -> {
            RegionThreadingHooks.assertGlobalStateWrite(level, "ServerLevel.setDayTime");
            return null;
        }).when(entity).tick();
        this.plan(level).executeAndAwait();
        assertEquals(2L, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void aSecondRegionBatchOfTheSameLevelIsReportedAsAnOverlap() throws InterruptedException {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        this.engine.beginLevelTick(level);
        this.engine.beginRegionPhase(level);
        try {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread other = new Thread(() -> {
                try {
                    this.engine.beginRegionPhase(level);
                    this.engine.endRegionPhase(level);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "overlapping region phase");
            other.start();
            other.join();
            assertNull(failure.get(), "the ownership violation mode reports the overlap without interrupting the caller");
            assertEquals(1L, this.engine.diagnostics().ownershipViolationCount(), "two region batches of one level never overlap silently");
        } finally {
            this.engine.endRegionPhase(level);
            RegionThreadingHooks.endLevelTick(level);
        }
    }

    @Test
    void aLevelTickThatThrowsIsCountedAsAbandonedAndRepairedAtTheNextEntry() {
        this.installEngine();
        final ServerLevel level = this.mockLevel();
        RegionThreadingHooks.beginLevelTick(level);
        // The level tick threw before its tail: the global phase was never closed.
        RegionThreadingHooks.beginLevelTick(level);
        assertEquals(1L, this.engine.diagnostics().abandonedGlobalSegments());
        RegionThreadingHooks.endLevelTick(level);
        assertEquals(LevelTickPhase.GLOBAL, RegionThreadingHooks.levelTickPhase(level));
        assertEquals(2L, this.engine.diagnostics().globalPhases());
    }

    @Test
    void switchOffKeepsTheVanillaPath() {
        RegionThreadingManager.clear();
        final ServerLevel level = this.mockLevel();

        assertNull(RegionThreadingHooks.levelTickPhase(level), "no engine, no phase boundary");
        assertNull(RegionThreadingHooks.mailbox(level), "no engine, no mailbox");

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.assertGlobalStateWrite(level, "test");
        RegionThreadingHooks.endLevelTick(level);

        assertNull(RegionThreadingHooks.levelTickPhase(level), "the switch-off path records no phase at all");
        assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread());
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
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }
}
