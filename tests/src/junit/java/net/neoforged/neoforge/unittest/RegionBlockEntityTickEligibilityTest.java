/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the server-thread entity-load admission of the region block-entity tick slice.
 * <p>
 * The level is a {@link org.mockito.Mockito#CALLS_REAL_METHODS} {@link ServerLevel} mock, but the state
 * that decides eligibility is real: the level reads a real {@link PersistentEntitySectionManager} through
 * the patched {@code areEntitiesLoaded} entry point, the chunk is a real {@link LevelChunk} with a real
 * {@code BLOCK_TICKING} full status, and the ticker is the real {@code LevelChunk.BoundTickingBlockEntity}
 * of that chunk bound to a real {@link FurnaceBlockEntity} running the vanilla
 * {@link AbstractFurnaceBlockEntity#serverTick}. The pending entity load is the manager's own
 * {@code PENDING} state: {@code updateChunkStatus} requests the load and the storage future is only
 * completed when the test finishes the load.
 * <p>
 * No test fakes a manager read or a loaded status. The admission decision, the worker-side replacement
 * for the entity-load read and the scope cleanup are observed through the production entry points.
 */
@Timeout(30)
class RegionBlockEntityTickEligibilityTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final BlockPos FURNACE_POS = new BlockPos(17, 64, 17);
    private static final ChunkPos FURNACE_CHUNK = new ChunkPos(1, 1);

    private RegionThreadingEngine engine;
    private CompletableFuture<ChunkEntities<Entity>> pendingLoad;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
    }

    @Test
    void pendingEntityLoadKeepsFurnaceProgressOffOnTheInlineAndWorkerPaths() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        final PersistentEntitySectionManager<Entity> manager = attachPendingEntityLoad(level);
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity furnace = furnace(level);
        final TickingBlockEntity ticker = boundTicker(chunk, furnace);

        // The chunk is in block ticking range and block-ticking status, but its entity load is pending.
        assertFalse(level.areEntitiesLoaded(FURNACE_CHUNK.toLong()), "the entity load must still be pending");

        // Vanilla inline path: the real bound ticker re-evaluates isTicking and skips the furnace.
        ticker.tick();
        assertEquals(5, litTime(furnace), "an inline tick must not progress the furnace while the entity load is pending");

        // Worker-requested path: the server thread must not admit the ticker in the first place.
        final RegionBlockEntityTickPlan pendingPlan = plan(level);
        assertFalse(pendingPlan.accept(ticker), "a ticker whose chunk is not entity-load eligible must not be admitted");
        pendingPlan.executeAndAwait();
        assertEquals(5, litTime(furnace), "the worker path must not progress the furnace while the entity load is pending");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());

        // Completing the real manager load makes the same ticker admissible.
        completeEntityLoad(manager);
        assertTrue(level.areEntitiesLoaded(FURNACE_CHUNK.toLong()), "the completed entity load must be visible");

        final RegionBlockEntityTickPlan loadedPlan = plan(level);
        assertTrue(loadedPlan.accept(ticker), "an entity-load eligible ticker must be admitted");
        loadedPlan.executeAndAwait();
        assertEquals(4, litTime(furnace), "the admitted worker must run the real furnace tick");
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void unrelatedNativeWorkerTaskCannotUseTheAdmission() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity furnace = furnace(level);
        final TickingBlockEntity ticker = boundTicker(chunk, furnace);
        final AtomicReference<Boolean> admitted = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        runOnWorkerPool(() -> {
            admitted.set(RegionThreadingHooks.isAdmittedBlockEntityTick(level, ticker, FURNACE_POS));
            try {
                ticker.tick();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        assertFalse(admitted.get(), "an unrelated native pool task must not receive a block-entity tick admission");
        assertEntityStorageReadRejected(failure.get());
        assertEquals(5, litTime(furnace), "an unrelated native pool task must not tick the block entity");
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void theAdmissionOnlyCoversTheAdmittedTickerLevelAndOwnedRegion() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final ServerLevel otherLevel = mockEligibilityLevel();
        final BlockPos sameChunkPos = FURNACE_POS.offset(1, 0, 0);
        final BlockPos otherRegionPos = new BlockPos(97, 64, 97);
        final List<String> observed = new CopyOnWriteArrayList<>();
        final TickingBlockEntity ticker = mockTicker(FURNACE_POS);
        doAnswer(invocation -> {
            observed.add("admitted=" + RegionThreadingHooks.isAdmittedBlockEntityTick(level, ticker, FURNACE_POS));
            observed.add("sameChunk=" + RegionThreadingHooks.isAdmittedBlockEntityTick(level, ticker, sameChunkPos));
            observed.add("otherRegion=" + RegionThreadingHooks.isAdmittedBlockEntityTick(level, ticker, otherRegionPos));
            observed.add("otherLevel=" + RegionThreadingHooks.isAdmittedBlockEntityTick(otherLevel, ticker, FURNACE_POS));
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(ticker));
        plan.executeAndAwait();

        assertEquals(List.of("admitted=true", "sameChunk=false", "otherRegion=false", "otherLevel=false"), observed,
                "the admission must cover exactly the admitted ticker of its own level in its owned region");
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void exactTickerAdmissionDoesNotAuthorizeSiblingOrSamePositionReplacement() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final TickingBlockEntity admitted = mockTicker(FURNACE_POS);
        final BlockPos siblingPosition = FURNACE_POS.offset(1, 0, 0);
        final TickingBlockEntity sibling = mockTicker(siblingPosition);
        final TickingBlockEntity replacement = mockTicker(siblingPosition);
        final List<Boolean> observations = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            observations.add(RegionThreadingHooks.isAdmittedBlockEntityTick(level, admitted, FURNACE_POS));
            observations.add(RegionThreadingHooks.isAdmittedBlockEntityTick(level, sibling, siblingPosition));
            observations.add(RegionThreadingHooks.isAdmittedBlockEntityTick(level, replacement, siblingPosition));
            return null;
        }).when(admitted).tick();
        doAnswer(invocation -> {
            observations.add(RegionThreadingHooks.isAdmittedBlockEntityTick(level, sibling, siblingPosition));
            observations.add(RegionThreadingHooks.isAdmittedBlockEntityTick(level, replacement, siblingPosition));
            return null;
        }).when(sibling).tick();

        final RegionBlockEntityTickPlan firstPlan = plan(level);
        assertTrue(firstPlan.accept(admitted));
        assertTrue(firstPlan.accept(sibling));
        firstPlan.executeAndAwait();

        assertEquals(List.of(true, false, false, true, false), observations);
        verify(replacement, never()).tick();
    }

    @Test
    void changedPositionAfterAdmissionUsesOneServerThreadFallback() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final AtomicReference<BlockPos> position = new AtomicReference<>(FURNACE_POS);
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenAnswer(invocation -> position.get());
        when(ticker.getType()).thenReturn("minecraft:furnace");
        final List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(ticker));
        position.set(FURNACE_POS.offset(1, 0, 0));
        plan.executeAndAwait();

        verify(ticker, times(1)).tick();
        assertEquals(1, threads.size());
        assertFalse(threads.get(0).startsWith("NeoForge Region Worker"), threads.toString());
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
    }

    @Test
    void loadedPositionChangedToPendingChunkBeforeExecutionDoesNotRun() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        final PersistentEntitySectionManager<Entity> manager = attachPendingEntityLoad(level);
        completeEntityLoad(manager);
        final BlockPos pendingPosition = new BlockPos(33, 64, 17);
        this.pendingLoad = new CompletableFuture<>();
        manager.updateChunkStatus(new ChunkPos(pendingPosition), FullChunkStatus.BLOCK_TICKING);
        assertFalse(level.areEntitiesLoaded(ChunkPos.asLong(pendingPosition)));
        final BlockPos.MutableBlockPos position = FURNACE_POS.mutable();
        final TickingBlockEntity ticker = mockTicker(position);
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(ticker));
        position.set(pendingPosition);

        plan.executeAndAwait();

        verify(ticker, never()).tick();
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void positionChangedByEarlierGroupTickerIsRevalidatedBeforeInvocation() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final TickingBlockEntity first = mockTicker(FURNACE_POS);
        final BlockPos.MutableBlockPos position = FURNACE_POS.offset(1, 0, 0).mutable();
        final TickingBlockEntity second = mockTicker(position);
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            position.move(1, 0, 0);
            return null;
        }).when(first).tick();
        doAnswer(invocation -> {
            threads.add(Thread.currentThread());
            assertFalse(RegionThreadingHooks.isAdmittedBlockEntityTick(level, second, position));
            return null;
        }).when(second).tick();
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));

        plan.executeAndAwait();

        verify(second, times(1)).tick();
        assertEquals(List.of(Thread.currentThread()), threads);
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void unstableReadRefusalIsNeitherWrappedNorReplayedByRealTicker() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity furnace = furnace(level);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final var refusal = net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.forUnstableRead(3, 1);
        final Method createTicker = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        createTicker.setAccessible(true);
        final TickingBlockEntity bound = (TickingBlockEntity) createTicker.invoke(chunk, furnace,
                (BlockEntityTicker<FurnaceBlockEntity>) (world, pos, state, entity) -> {
                    calls.incrementAndGet();
                    throw refusal;
                });
        assertSame(refusal, assertThrows(net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.class, bound::tick));
        calls.set(0);
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(wrappedTicker(chunk, bound)));
        plan.executeAndAwait();
        assertEquals(1, calls.get(), "the interrupted ticker must not run again on the server thread");
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
        assertFalse(furnace.isRemoved());
    }

    @Test
    void realWrapperAuthorizesOnlyItsCapturedBoundDelegate() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity firstFurnace = furnace(level);
        final FurnaceBlockEntity secondFurnace = furnace(level);
        final FurnaceBlockEntity unrelatedFurnace = furnace(level);
        final TickingBlockEntity firstBound = boundTicker(chunk, firstFurnace);
        final TickingBlockEntity secondBound = boundTicker(chunk, secondFurnace);
        final TickingBlockEntity unrelatedBound = boundTicker(chunk, unrelatedFurnace);
        final TickingBlockEntity secondWrapper = wrappedTicker(chunk, secondBound);
        final Method createTicker = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        createTicker.setAccessible(true);
        final TickingBlockEntity probingBound = (TickingBlockEntity) createTicker.invoke(chunk, firstFurnace,
                (BlockEntityTicker<FurnaceBlockEntity>) (world, pos, state, furnace) -> {
                    assertFalse(RegionThreadingHooks.isAdmittedBlockEntityTick(level, firstBound, FURNACE_POS));
                    assertFalse(RegionThreadingHooks.isAdmittedBlockEntityTick(level, secondWrapper, FURNACE_POS));
                    assertFalse(RegionThreadingHooks.isAdmittedBlockEntityTick(level, secondBound, FURNACE_POS));
                    assertFalse(RegionThreadingHooks.isAdmittedBlockEntityTick(level, unrelatedBound, FURNACE_POS));
                    assertEntityStorageReadRejected(assertThrows(IllegalStateException.class, secondWrapper::tick));
                    assertEntityStorageReadRejected(assertThrows(IllegalStateException.class, unrelatedBound::tick));
                    AbstractFurnaceBlockEntity.serverTick(world, pos, state, furnace);
                });
        final TickingBlockEntity firstWrapper = wrappedTicker(chunk, probingBound);
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(firstWrapper));
        assertTrue(plan.accept(secondWrapper));

        plan.executeAndAwait();

        assertEquals(4, litTime(firstFurnace));
        assertEquals(4, litTime(secondFurnace), "the sibling must be eligible on its own turn only");
        assertEquals(5, litTime(unrelatedFurnace));
    }

    @Test
    void realWrapperRebindToSamePositionInvalidatesAdmissionEvenWhenRestored() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity original = furnace(level);
        final FurnaceBlockEntity replacement = furnace(level);
        final TickingBlockEntity originalBound = boundTicker(chunk, original);
        final TickingBlockEntity replacementBound = boundTicker(chunk, replacement);
        final TickingBlockEntity wrapper = wrappedTicker(chunk, originalBound);
        final Method rebind = wrapper.getClass().getDeclaredMethod("rebind", TickingBlockEntity.class);
        rebind.setAccessible(true);
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(wrapper));
        rebind.invoke(wrapper, replacementBound);
        rebind.invoke(wrapper, originalBound);

        plan.executeAndAwait();

        assertEquals(4, litTime(original));
        assertEquals(5, litTime(replacement));
        assertEquals(0L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void realWrapperReboundByEarlierTickerFallsBackWithoutRunningOldDelegate() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final LevelChunk chunk = furnaceChunk(level);
        final FurnaceBlockEntity original = furnace(level);
        final FurnaceBlockEntity replacement = furnace(level);
        final TickingBlockEntity wrapper = wrappedTicker(chunk, boundTicker(chunk, original));
        final TickingBlockEntity replacementBound = boundTicker(chunk, replacement);
        final Method rebind = wrapper.getClass().getDeclaredMethod("rebind", TickingBlockEntity.class);
        rebind.setAccessible(true);
        final TickingBlockEntity first = mockTicker(FURNACE_POS);
        doAnswer(invocation -> {
            rebind.invoke(wrapper, replacementBound);
            return null;
        }).when(first).tick();
        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(wrapper));

        plan.executeAndAwait();

        assertEquals(5, litTime(original));
        assertEquals(4, litTime(replacement));
        assertEquals(1L, this.engine.diagnostics().blockEntityTicksOnWorkers());
        assertEquals(1L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    @Test
    void theAdmissionScopeIsClosedAfterAFailingTicker() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final AtomicReference<Boolean> admittedInside = new AtomicReference<>();
        final AtomicReference<Boolean> admittedAfterwards = new AtomicReference<>();
        final AtomicReference<Thread> failedGroupThread = new AtomicReference<>();
        final AtomicReference<Thread> probeThread = new AtomicReference<>();
        final TickingBlockEntity failing = mockTicker(FURNACE_POS);
        doAnswer(invocation -> {
            failedGroupThread.set(Thread.currentThread());
            admittedInside.set(RegionThreadingHooks.isAdmittedBlockEntityTick(level, failing, FURNACE_POS));
            throw new IllegalStateException("furnace boom");
        }).when(failing).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(failing));
        assertThrows(IllegalStateException.class, plan::executeAndAwait);

        // The group failure is captured by the batch, so the single pool worker survives and this probe
        // observes the thread local of the very thread that ran the failed group.
        runOnWorkerPool(() -> {
            probeThread.set(Thread.currentThread());
            admittedAfterwards.set(RegionThreadingHooks.isAdmittedBlockEntityTick(level, failing, FURNACE_POS));
        });

        assertSame(failedGroupThread.get(), probeThread.get(), "the probe must observe the thread that ran the failed group");
        assertEquals(Boolean.TRUE, admittedInside.get(), "the admitted ticker must see its own admission");
        assertEquals(Boolean.FALSE, admittedAfterwards.get(), "a failed group must not leak its admission scope");
    }

    @Test
    void theAdmissionScopeIsNotGrantedToARejectedGroupRunningOnTheServerThread() throws ReflectiveOperationException {
        this.installEngine();
        closeWorkerPool(this.engine);
        final ServerLevel level = mockEligibilityLevel();
        completeEntityLoad(attachPendingEntityLoad(level));
        final AtomicReference<Boolean> admittedInside = new AtomicReference<>();
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        final TickingBlockEntity ticker = mockTicker(FURNACE_POS);
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            admittedInside.set(RegionThreadingHooks.isAdmittedBlockEntityTick(level, ticker, FURNACE_POS));
            return null;
        }).when(ticker).tick();

        final RegionBlockEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(ticker));
        plan.executeAndAwait();

        assertEquals(List.of(Thread.currentThread().getName()), tickThreads, "a rejected group must tick on the server thread");
        assertEquals(Boolean.FALSE, admittedInside.get(),
                "the server-thread fallback keeps the real entity-load read instead of an admission");
        assertEquals(0L, this.engine.diagnostics().fallbackBlockEntityTicks());
    }

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(1, false, false, false, true);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionBlockEntityTickPlan plan(ServerLevel level) {
        final RegionBlockEntityTickPlan created = this.engine.beginBlockEntityTickPlan(level, PARKING_BARRIER);
        assertNotNull(created, "block entity tick worker execution must be enabled");
        return created;
    }

    /**
     * Builds the level state the real eligibility path reads. The entity manager is attached by
     * {@link #attachPendingEntityLoad(ServerLevel)}; this method never stubs an entity-load read.
     */
    private static ServerLevel mockEligibilityLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("eligibility-level").when(level).toString();
        doReturn(-64).when(level).getMinBuildHeight();
        doReturn(384).when(level).getHeight();
        // The chunk only needs the biome registry handle for its biome containers; the containers are
        // supplied by the test, so the handle is never dereferenced.
        final RegistryAccess registryAccess = mock(RegistryAccess.class);
        doReturn(biomeRegistry()).when(registryAccess).registryOrThrow(Registries.BIOME);
        doReturn(registryAccess).when(level).registryAccess();
        doReturn(new WorldBorder()).when(level).getWorldBorder();
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));
        // The group random of a block-entity group is derived from the level's dimension, seed and game
        // time, so the level has to answer them like a real server level.
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        // A Mockito mock does not run the level constructor, so the block-entity ticker bookkeeping of the
        // patched level has to be installed explicitly.
        setField(Level.class, level, "blockEntityTickerLock", new Object());
        setField(Level.class, level, "blockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "pendingBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "foreignBlockEntityTickers", new ArrayList<TickingBlockEntity>());
        setField(Level.class, level, "blockEntityTickerChunks", new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<LevelChunk>());
        setField(Level.class, level, "blockEntityTickerSequence", new java.util.concurrent.atomic.AtomicLong());
        return level;
    }

    /**
     * Attaches a real guarded entity manager whose chunk load is requested but not completed: the manager
     * stores its own {@code PENDING} status and {@code areEntitiesLoaded} reports it.
     */
    @SuppressWarnings("unchecked")
    private PersistentEntitySectionManager<Entity> attachPendingEntityLoad(ServerLevel level) {
        this.pendingLoad = new CompletableFuture<>();
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any())).thenAnswer(invocation -> this.pendingLoad);
        final PersistentEntitySectionManager<Entity> manager = new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback<Entity>) mock(LevelCallback.class),
                storage,
                RegionThreadingHooks::assertServerEntityStorageAccess);
        setField(ServerLevel.class, level, "entityManager", manager);
        manager.updateChunkStatus(FURNACE_CHUNK, FullChunkStatus.BLOCK_TICKING);
        return manager;
    }

    private void completeEntityLoad(PersistentEntitySectionManager<Entity> manager) {
        this.pendingLoad.complete(new ChunkEntities<>(FURNACE_CHUNK, List.of()));
        manager.tick();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Registry<Biome> biomeRegistry() {
        return (Registry<Biome>) mock(Registry.class);
    }

    /**
     * Builds a real {@link LevelChunk} whose furnace position holds a real furnace block state. The
     * sections carry real block-state palettes; only the biome palette is a placeholder, because no test
     * here reads a biome.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static LevelChunk furnaceChunk(ServerLevel level) {
        final LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int index = 0; index < sections.length; index++) {
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES),
                    (PalettedContainerRO<Holder<Biome>>) mock(PalettedContainerRO.class));
        }
        sections[level.getSectionIndex(FURNACE_POS.getY())]
                .setBlockState(FURNACE_POS.getX() & 15, FURNACE_POS.getY() & 15, FURNACE_POS.getZ() & 15, Blocks.FURNACE.defaultBlockState());
        final LevelChunk chunk = new LevelChunk(level, FURNACE_CHUNK, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L,
                sections, null, null);
        chunk.setFullStatus(() -> FullChunkStatus.BLOCK_TICKING);
        return chunk;
    }

    private static FurnaceBlockEntity furnace(ServerLevel level) throws ReflectiveOperationException {
        final FurnaceBlockEntity furnace = new FurnaceBlockEntity(FURNACE_POS, Blocks.FURNACE.defaultBlockState());
        furnace.setLevel(level);
        setField(AbstractFurnaceBlockEntity.class, furnace, "litTime", 5);
        return furnace;
    }

    /**
     * {@return the real bound ticker the chunk registers for {@code furnace}} The bound ticker is the
     * production {@code LevelChunk.BoundTickingBlockEntity}; the chunk factory is private, so the test
     * reaches it the same way the vanilla chunk does.
     */
    @SuppressWarnings("unchecked")
    private static TickingBlockEntity boundTicker(LevelChunk chunk, FurnaceBlockEntity furnace) throws ReflectiveOperationException {
        final Method createTicker = LevelChunk.class.getDeclaredMethod("createTicker", BlockEntity.class, BlockEntityTicker.class);
        createTicker.setAccessible(true);
        return (TickingBlockEntity) createTicker.invoke(chunk, furnace, (BlockEntityTicker<FurnaceBlockEntity>) AbstractFurnaceBlockEntity::serverTick);
    }

    private static TickingBlockEntity wrappedTicker(LevelChunk chunk, TickingBlockEntity delegate) throws ReflectiveOperationException {
        final Class<?> wrapperClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper");
        final var constructor = wrapperClass.getDeclaredConstructor(LevelChunk.class, TickingBlockEntity.class);
        constructor.setAccessible(true);
        return (TickingBlockEntity) constructor.newInstance(chunk, delegate);
    }

    private static int litTime(FurnaceBlockEntity furnace) throws ReflectiveOperationException {
        return (int) getField(AbstractFurnaceBlockEntity.class, furnace, "litTime");
    }

    private static TickingBlockEntity mockTicker(BlockPos pos) {
        final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
        when(ticker.getPos()).thenReturn(pos);
        when(ticker.getType()).thenReturn("minecraft:furnace");
        return ticker;
    }

    /**
     * Asserts the worker reached the guarded entity-load read instead of an admission. The bound ticker
     * wraps the failure in a crash report, so the rejection is looked up in the cause chain.
     */
    private static void assertEntityStorageReadRejected(Throwable failure) {
        assertNotNull(failure, "the worker tick must fail instead of running the block entity");
        Throwable candidate = failure;
        while (candidate != null) {
            if (candidate.getMessage() != null && candidate.getMessage().contains("PersistentEntitySectionManager.areEntitiesLoaded")) {
                return;
            }
            candidate = candidate.getCause();
        }
        throw new AssertionError("expected the guarded entity-load read to reject the worker, got: " + failure, failure);
    }

    private void runOnWorkerPool(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue(this.workerPool().execute("eligibility probe", () -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the probe task");
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the worker probe never finished");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the worker probe", exception);
        }
    }

    private RegionWorkerExecutor workerPool() {
        return (RegionWorkerExecutor) getField(RegionThreadingEngine.class, this.engine, "workerPool");
    }

    private static void closeWorkerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Object pool = getField(RegionThreadingEngine.class, engine, "workerPool");
        final Method close = pool.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        close.invoke(pool);
    }

    private static Object getField(Class<?> declaringClass, Object target, String name) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }

    private static void setField(Class<?> declaringClass, Object target, String name, Object value) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot set " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }
}
