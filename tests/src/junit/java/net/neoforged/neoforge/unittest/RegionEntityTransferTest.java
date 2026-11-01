/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferResult;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferToken;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the bounded, recorded cross-region entity transfer transaction.
 * <p>
 * The engine, its journal and the identity ledger are the real implementations. The level and the entity
 * are mechanics objects because the vanilla section, tracking and network pipeline is explicitly outside
 * this transaction and stays a server-thread concern of the parent lanes.
 */
@Timeout(30)
class RegionEntityTransferTest {
    private static final int JOURNAL_BOUND = 1024;

    private RegionThreadingEngine engine;
    private RegionThreadingEngine secondaryEngine;
    private ServerLevel level;
    private EntityIdentityLedger ledger;
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void clearThreading() {
        try {
            RegionThreadingManager.clear();
        } finally {
            if (this.engine != null) {
                this.engine.shutdown();
                this.engine = null;
            }
            if (this.secondaryEngine != null) {
                this.secondaryEngine.shutdown();
                this.secondaryEngine = null;
            }
            this.level = null;
            this.ledger = null;
        }
    }

    @Test
    void recordedMoveCommitsTheRecordedDestinationAndASecondCommitIsANoOp() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos recordedPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = regionAt(recordedPosition);
        this.engine.addRegionSection(this.level, chunkX(recordedPosition), chunkZ(recordedPosition));

        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token, "the decision must be recorded");
        assertEquals(RegionEntityTransferToken.Operation.REGION_MOVE, token.operation());
        assertEquals(source, token.sourceRegion());
        assertEquals(destination, token.destinationRegion());
        assertEquals(1, this.engine.pendingEntityTransferCount());

        // The entity moves again after the decision. The commit must use the recorded destination.
        when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));
        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(1, this.engine.rememberedEntityTransferResultCount());
        final int regionsAfterCommit = this.engine.diagnostics().regionCount();

        assertEquals(RegionEntityTransferResult.ALREADY_COMMITTED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(regionsAfterCommit, this.engine.diagnostics().regionCount(), "a replay must not write again");
    }

    @Test
    void staleIdentityEpochAbortsWithoutMutation() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        // A re-publication of the same identity advances its lifecycle epoch.
        this.ledger.publishUntracked(entity);
        this.ledger.publishTracked(entity);

        assertEquals(RegionEntityTransferResult.STALE_IDENTITY_EPOCH, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(1, this.engine.rememberedEntityTransferResultCount());
    }

    @Test
    void unknownTokenIsRejectedWithoutMutation() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        this.secondaryEngine = new RegionThreadingEngine(1, false, false);
        assertEquals(RegionEntityTransferResult.UNKNOWN_TOKEN, this.secondaryEngine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity));
    }

    @Test
    void commitAfterLevelCloseIsRejectedWithoutMutation() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        this.engine.clearLevelRegionTasks(this.level);

        assertEquals(RegionEntityTransferResult.LEVEL_CLOSED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(0, this.engine.diagnostics().trackedEntityCount());
    }

    @Test
    void commitAfterShutdownIsRejectedAndTheJournalIsCleared() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        this.engine.shutdown();

        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(0, this.engine.rememberedEntityTransferResultCount());
        assertEquals(RegionEntityTransferResult.ENGINE_SHUT_DOWN, this.engine.commitEntityRegionTransfer(token));
    }

    @Test
    void workerCommitIsRejectedBeforeTheJournalIsClaimed() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue(workerPool(this.engine).execute("entity transfer commit probe", () -> {
            try {
                this.engine.commitEntityRegionTransfer(token);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the probe");
        assertTrue(await(done), "the worker probe did not finish");

        final Throwable throwable = failure.get();
        assertTrue(throwable instanceof IllegalStateException, "the worker commit must be rejected");
        assertTrue(throwable.getMessage().contains("RegionEntityTransferJournal.commit"),
                () -> "the rejection must name the operation, got: " + throwable.getMessage());
        assertEquals(source, trackedCoordinate(this.engine, entity), "the worker commit must not mutate");
        assertEquals(1, this.engine.pendingEntityTransferCount(), "the journal must not be claimed on a worker");
    }

    @Test
    void ridingGraphThatWouldSplitAcrossRegionsIsRejectedWhole() throws ReflectiveOperationException {
        this.installEngine();
        final Entity root = registeredEntity(new BlockPos(0, 64, 0));
        final Entity passenger = registeredEntity(new BlockPos(4096, 64, 0));
        when(root.getPassengers()).thenReturn(List.of(passenger));
        final RegionCoordinate rootSource = trackedCoordinate(this.engine, root);
        final RegionCoordinate passengerSource = trackedCoordinate(this.engine, passenger);
        final RegionCoordinate destination = activeDestination(new BlockPos(8192, 64, 0));

        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(root, destination);
        assertNotNull(token);
        assertEquals(2, token.ridingGraph().size());

        assertEquals(RegionEntityTransferResult.RIDING_GRAPH_SPLIT, this.engine.commitEntityRegionTransfer(token));
        assertEquals(rootSource, trackedCoordinate(this.engine, root), "the root must not be transferred alone");
        assertEquals(passengerSource, trackedCoordinate(this.engine, passenger), "the passenger keeps its region");
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void ridingGraphThatWouldSplitAcrossLevelsIsRejectedWhole() throws ReflectiveOperationException {
        this.installEngine();
        final Entity root = registeredEntity(new BlockPos(0, 64, 0));
        final ServerLevel otherLevel = mock(ServerLevel.class);
        final EntityIdentityLedger otherLedger = new EntityIdentityLedger();
        doReturn(otherLedger).when(otherLevel).entityIdentityLedger();
        final Entity passenger = registeredEntity(otherLevel, otherLedger, new BlockPos(4096, 64, 0));
        when(root.getPassengers()).thenReturn(List.of(passenger));
        final RegionCoordinate rootSource = trackedCoordinate(this.engine, root);
        final RegionCoordinate passengerSource = trackedCoordinate(this.engine, passenger);
        final RegionCoordinate destination = activeDestination(new BlockPos(8192, 64, 0));

        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(root, destination);
        assertNotNull(token);

        assertEquals(RegionEntityTransferResult.RIDING_GRAPH_SPLIT, this.engine.commitEntityRegionTransfer(token));
        assertEquals(rootSource, trackedCoordinate(this.engine, root));
        assertEquals(passengerSource, trackedCoordinate(this.engine, passenger));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void removedEntityIsRejectedWithoutMutation() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        when(entity.isRemoved()).thenReturn(true);

        assertEquals(RegionEntityTransferResult.ENTITY_REMOVED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void inactiveDestinationRegionIsRejectedWithoutMutation() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos destinationPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = regionAt(destinationPosition);
        this.engine.addRegionSection(this.level, chunkX(destinationPosition), chunkZ(destinationPosition));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        this.engine.removeRegionSection(this.level, chunkX(destinationPosition), chunkZ(destinationPosition));

        assertEquals(RegionEntityTransferResult.DESTINATION_INACTIVE, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void journalStaysBoundedAndIsClearedOnShutdown() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));

        RegionEntityTransferToken lastAccepted = null;
        int accepted = 0;
        for (int i = 0; i < JOURNAL_BOUND + 100; i++) {
            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            if (token != null) {
                lastAccepted = token;
                ++accepted;
            }
        }

        assertEquals(JOURNAL_BOUND, accepted, "recording must stop at the journal bound");
        assertEquals(accepted, this.engine.pendingEntityTransferCount());
        assertNotNull(lastAccepted);

        this.engine.shutdown();

        assertEquals(0, this.engine.pendingEntityTransferCount(), "shutdown clears pending decisions");
        assertEquals(0, this.engine.rememberedEntityTransferResultCount(), "shutdown clears remembered results");
        assertEquals(RegionEntityTransferResult.ENGINE_SHUT_DOWN, this.engine.commitEntityRegionTransfer(lastAccepted));
    }

    @Test
    void disabledRegionThreadingKeepsTheVanillaPath() {
        RegionThreadingManager.clear();
        final ServerLevel mockLevel = mock(ServerLevel.class);
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(mockLevel);

        assertFalse(RegionThreadingManager.isInstalled());
        assertNull(RegionThreadingHooks.recordEntityRegionTransfer(entity, new RegionCoordinate(mockLevel, 0, 0)));
        assertEquals(RegionEntityTransferResult.DISABLED, RegionThreadingHooks.commitEntityRegionTransfer(null));
    }

    @Test
    void crossLevelDestinationIsRejectedExplicitly() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final ServerLevel otherLevel = mock(ServerLevel.class);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> this.engine.recordEntityRegionTransfer(entity, new RegionCoordinate(otherLevel, 1, 1)));
        assertTrue(thrown.getMessage().contains("Cross-level"), thrown.getMessage());
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    private void installEngine() {
        this.engine = new RegionThreadingEngine(1, false, false);
        this.level = mock(ServerLevel.class);
        this.ledger = new EntityIdentityLedger();
        doReturn(this.ledger).when(this.level).entityIdentityLedger();
    }

    private Entity registeredEntity(BlockPos position) {
        return registeredEntity(this.level, this.ledger, position);
    }

    private Entity registeredEntity(ServerLevel entityLevel, EntityIdentityLedger entityLedger, BlockPos position) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(entityLevel);
        when(entity.blockPosition()).thenReturn(position);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.getPassengers()).thenReturn(List.of());
        when(entity.isRemoved()).thenReturn(false);
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, entityLedger.admit(entity));
        entityLedger.publishAdmitted(entity);
        entityLedger.publishTracked(entity);
        this.engine.addRegionEntity(entity);
        return entity;
    }

    private RegionCoordinate activeDestination(BlockPos position) {
        final RegionCoordinate coordinate = regionAt(position);
        this.engine.addRegionSection(this.level, chunkX(position), chunkZ(position));
        return coordinate;
    }

    private RegionCoordinate regionAt(BlockPos position) {
        return RegionLayout.DYNAMIC.regionAt(this.level, position);
    }

    private static int chunkX(BlockPos position) {
        return Math.floorDiv(position.getX(), 16);
    }

    private static int chunkZ(BlockPos position) {
        return Math.floorDiv(position.getZ(), 16);
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static RegionWorkerExecutor workerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        return (RegionWorkerExecutor) field.get(engine);
    }

    private static RegionCoordinate trackedCoordinate(RegionThreadingEngine engine, Entity entity) throws ReflectiveOperationException {
        final Object region = trackedEntities(engine).get(entity);
        assertNotNull(region, "the entity is not tracked by the regionizer");
        final Method coordinate = region.getClass().getDeclaredMethod("coordinate");
        coordinate.setAccessible(true);
        return (RegionCoordinate) coordinate.invoke(region);
    }

    private static Map<?, ?> trackedEntities(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(engine);
        final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        return (Map<?, ?>) entitiesField.get(regionizer);
    }
}
