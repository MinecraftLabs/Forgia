/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the bounded, recorded cross-region entity transfer transaction.
 * <p>
 * The engine, its journal and the identity ledger are the real implementations. The level and the entity
 * are mechanics objects because the vanilla section, tracking and network pipeline is explicitly outside
 * this transaction and stays a server-thread concern of the parent lanes. The few package-private seams of
 * the regionizer and the journal are reached through reflection so the test can force the states that a
 * single-threaded run cannot reach on its own: an active region lease, an in-flight claim and a deferred
 * entity update of a running batch.
 */
@Timeout(30)
class RegionEntityTransferTest {
    private static final String ENTITY_BATCH_TYPE = "net.neoforged.neoforge.server.threading.region.RegionEntityTickBatch";
    /**
     * Entries the batch reserves for the deferred block updates. The seams under test defer no block write, so
     * the production bound is used.
     */
    private static final int DEFERRED_BLOCK_UPDATE_ENTRIES = 1 << 16;

    private RegionThreadingEngine engine;
    private RegionThreadingEngine secondaryEngine;
    private ServerLevel level;
    private EntityIdentityLedger ledger;
    /** Thread that runs the test body; the mock server answers {@code isSameThread()} for it only. */
    private Thread ownerThread;
    private final AtomicInteger nextEntityId = new AtomicInteger(1);
    private final AtomicLong nextForgedTokenId = new AtomicLong(-1);

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
            this.ownerThread = null;
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
    void workerCommitIsRejectedBeforeTheJournalIsClaimedAndTheTokenStaysCommittable() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos destinationPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = activeDestination(destinationPosition);
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
        assertTrue(throwable.getMessage().contains("RegionThreadingEngine.commitEntityRegionTransfer"),
                () -> "the rejection must name the engine operation, got: " + throwable.getMessage());
        assertEquals(source, trackedCoordinate(this.engine, entity), "the worker commit must not mutate");
        assertEquals(1, this.engine.pendingEntityTransferCount(), "the journal must not be claimed on a worker");

        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                "the rejected worker commit must not have consumed the claim");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void workerRecordIsRejectedBeforeTheJournalGrows() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue(workerPool(this.engine).execute("entity transfer record probe", () -> {
            try {
                this.engine.recordEntityRegionTransfer(entity, destination);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the probe");
        assertTrue(await(done), "the worker probe did not finish");

        final Throwable throwable = failure.get();
        assertTrue(throwable instanceof IllegalStateException, "the worker record must be rejected");
        assertTrue(throwable.getMessage().contains("RegionThreadingEngine.recordEntityRegionTransfer"),
                () -> "the rejection must name the engine operation, got: " + throwable.getMessage());
        assertEquals(0, this.engine.pendingEntityTransferCount(), "a rejected recording must not reach the journal");
    }

    @Test
    void workerAbortIsRejectedBeforeThePendingEntryIsReleased() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue(workerPool(this.engine).execute("entity transfer abort probe", () -> {
            try {
                this.engine.abortEntityRegionTransfer(token);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the probe");
        assertTrue(await(done), "the worker probe did not finish");

        assertOperationRejected(failure.get(), "RegionThreadingEngine.abortEntityRegionTransfer");
        assertEquals(1, this.engine.pendingEntityTransferCount(), "a worker abort must not release the decision");
        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                "the rejected worker abort must not have cancelled the decision");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
    }

    @Test
    void foreignThreadRecordCommitAndAbortAreRejectedBeforeAnyJournalOrMappingChange() throws Exception {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        // Counts prove the foreign thread is rejected before the identity ledger read and the riding-graph
        // traversal, not merely before the journal insertion.
        final AtomicInteger ledgerReads = new AtomicInteger();
        doAnswer(invocation -> {
            ledgerReads.incrementAndGet();
            return this.ledger;
        }).when(this.level).entityIdentityLedger();
        final AtomicInteger passengerReads = new AtomicInteger();
        when(entity.getPassengers()).thenAnswer(invocation -> {
            passengerReads.incrementAndGet();
            return List.of();
        });

        final AtomicReference<Throwable> recordFailure = new AtomicReference<>();
        final AtomicReference<Throwable> commitFailure = new AtomicReference<>();
        final AtomicReference<Throwable> abortFailure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final Thread foreignThread = new Thread(() -> {
            try {
                try {
                    this.engine.recordEntityRegionTransfer(entity, destination);
                } catch (Throwable throwable) {
                    recordFailure.set(throwable);
                }
                try {
                    this.engine.commitEntityRegionTransfer(token);
                } catch (Throwable throwable) {
                    commitFailure.set(throwable);
                }
                try {
                    this.engine.abortEntityRegionTransfer(token);
                } catch (Throwable throwable) {
                    abortFailure.set(throwable);
                }
            } finally {
                done.countDown();
            }
        }, "entity transfer foreign thread");
        foreignThread.setDaemon(true);
        foreignThread.start();
        assertTrue(await(done), "the foreign-thread probe did not finish");
        foreignThread.join(TimeUnit.SECONDS.toMillis(10));

        // The foreign thread is not a native pool worker, so its rejection has to come from the level's
        // own MinecraftServer owner and must name the exact operation it attempted.
        assertOperationRejected(recordFailure.get(), "RegionThreadingEngine.recordEntityRegionTransfer");
        assertOperationRejected(commitFailure.get(), "RegionThreadingEngine.commitEntityRegionTransfer");
        assertOperationRejected(abortFailure.get(), "RegionThreadingEngine.abortEntityRegionTransfer");
        assertEquals(0, ledgerReads.get(), "a foreign thread must not read the identity ledger");
        assertEquals(0, passengerReads.get(), "a foreign thread must not traverse the riding graph");
        assertEquals(1, this.engine.pendingEntityTransferCount(),
                "a foreign thread must neither add nor claim nor release the decision");
        assertEquals(source, trackedCoordinate(this.engine, entity), "a foreign thread must not write the mapping");

        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                "the server thread still owns the same token after the foreign-thread rejections");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void aLevelWithoutAnOwningServerIsRejectedInsteadOfRecording() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));

        doReturn(null).when(this.level).getServer();

        final IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> this.engine.recordEntityRegionTransfer(entity, destination),
                "a level without an owning server must fail closed");
        assertTrue(failure.getMessage().contains("RegionThreadingEngine.recordEntityRegionTransfer"),
                () -> "the rejection must name the engine operation, got: " + failure.getMessage());
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void ridingGraphSpanningTwoRegionsIsRejectedWholeAsSourceMappingChanged() throws ReflectiveOperationException {
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

        // The membership is intact, so this is not a graph split: the passenger simply does not map to the
        // recorded source region, and the whole token is rejected as a different transfer.
        assertEquals(RegionEntityTransferResult.SOURCE_MAPPING_CHANGED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(rootSource, trackedCoordinate(this.engine, root), "the root must not be transferred alone");
        assertEquals(passengerSource, trackedCoordinate(this.engine, passenger), "the passenger keeps its region");
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void crossLevelRidingGraphIsRefusedAndAMemberLevelClearReleasesNothing() throws ReflectiveOperationException {
        this.installEngine();
        final Entity root = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate rootSource = trackedCoordinate(this.engine, root);
        final EntityIdentityLedger otherLedger = new EntityIdentityLedger();
        final ServerLevel otherLevel = transferLevel(otherLedger);
        final Entity passenger = registeredEntity(otherLevel, otherLedger, new BlockPos(4096, 64, 0));
        when(root.getPassengers()).thenReturn(List.of(passenger));
        final RegionCoordinate destination = activeDestination(new BlockPos(8192, 64, 0));

        // A same-level decision that the member-level clear must preserve.
        final Entity unrelated = registeredEntity(new BlockPos(64, 64, 0));
        final RegionEntityTransferToken unrelatedToken = this.engine.recordEntityRegionTransfer(unrelated, destination);
        assertNotNull(unrelatedToken);
        assertNotNull(trackedRegion(this.engine, passenger), "the passenger starts tracked in its own level");

        // A graph that spans levels is outside the strictly same-level REGION_MOVE scope, so it is never
        // recorded: no level clear could release such a decision later.
        assertNull(this.engine.recordEntityRegionTransfer(root, destination),
                "a graph that spans levels must not be recorded");
        assertEquals(1, this.engine.pendingEntityTransferCount(), "a refused cross-level graph retains nothing");

        this.engine.clearLevelRegionTasks(otherLevel);

        assertNull(trackedRegion(this.engine, passenger), "the member-level clear retired its entity");
        assertEquals(1, this.engine.pendingEntityTransferCount(),
                "a member-level clear must not release a decision of another level");
        assertEquals(rootSource, trackedCoordinate(this.engine, root), "the root belongs to another level");

        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(unrelatedToken));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void rootLevelUnloadReleasesItsRecordedGraphAndKeepsOtherLevelDecisions() throws ReflectiveOperationException {
        this.installEngine();
        final Entity root = registeredEntity(new BlockPos(0, 64, 0));
        final Entity passenger = registeredEntity(new BlockPos(64, 64, 0));
        when(root.getPassengers()).thenReturn(List.of(passenger));
        final RegionCoordinate destination = activeDestination(new BlockPos(8192, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(root, destination);
        assertNotNull(token);
        assertEquals(2, token.ridingGraph().size(), "both graph members belong to the recorded level");

        final EntityIdentityLedger otherLedger = new EntityIdentityLedger();
        final ServerLevel otherLevel = transferLevel(otherLedger);
        final Entity other = registeredEntity(otherLevel, otherLedger, new BlockPos(0, 64, 0));
        final BlockPos otherDestinationPosition = new BlockPos(8192, 64, 0);
        final RegionCoordinate otherDestination = RegionLayout.DYNAMIC.regionAt(otherLevel, otherDestinationPosition);
        this.engine.addRegionSection(otherLevel, chunkX(otherDestinationPosition), chunkZ(otherDestinationPosition));
        final RegionEntityTransferToken otherToken = this.engine.recordEntityRegionTransfer(other, otherDestination);
        assertNotNull(otherToken);
        assertEquals(2, this.engine.pendingEntityTransferCount());

        this.engine.clearLevelRegionTasks(this.level);

        assertEquals(1, this.engine.pendingEntityTransferCount(),
                "the clear must keep the decision of the level that stays loaded");
        assertEquals(RegionEntityTransferResult.LEVEL_CLOSED, this.engine.commitEntityRegionTransfer(token),
                "the recorded graph belonged to the unloaded level");
        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(otherToken));
        assertEquals(otherDestination, trackedCoordinate(this.engine, other));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void ridingGraphThatDivergesAfterRecordingIsRejectedWhole() throws ReflectiveOperationException {
        this.installEngine();
        final Entity root = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, root);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(root, destination);
        assertNotNull(token);
        assertEquals(1, token.ridingGraph().size());

        // A passenger boards the recorded root after the decision; the recorded graph is no longer the
        // live one, so the whole token is rejected instead of moving the root alone.
        final Entity passenger = registeredEntity(new BlockPos(64, 64, 0));
        assertEquals(source, trackedCoordinate(this.engine, passenger), "the passenger must start in the source region");
        when(root.getPassengers()).thenReturn(List.of(passenger));

        assertEquals(RegionEntityTransferResult.RIDING_GRAPH_SPLIT, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, root));
        assertEquals(source, trackedCoordinate(this.engine, passenger));
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
    void mergedSourceRegionIsReportedAsSourceMappingChanged() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        this.engine.addRegionSection(this.level, 0, 0);
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(8192, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        // A neighbouring region merges with the recorded source; the merged region keeps the neighbour
        // coordinate, so the entity no longer maps to the recorded source even though the graph is intact.
        this.engine.addRegionSection(this.level, 16, 0);
        final RegionCoordinate mergedSource = trackedCoordinate(this.engine, entity);
        assertNotEquals(source, mergedSource, "the neighbouring region must have merged with the source");

        assertEquals(RegionEntityTransferResult.SOURCE_MAPPING_CHANGED, this.engine.commitEntityRegionTransfer(token));
        assertNotEquals(destination, trackedCoordinate(this.engine, entity), "a merge must not move the entity to the destination");
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void secondTokenForAnAlreadyMovedEntityIsReportedAsSourceMappingChanged() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken first = this.engine.recordEntityRegionTransfer(entity, destination);
        final RegionEntityTransferToken second = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(source, second.sourceRegion());

        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(first));
        assertEquals(destination, trackedCoordinate(this.engine, entity));

        assertEquals(RegionEntityTransferResult.SOURCE_MAPPING_CHANGED, this.engine.commitEntityRegionTransfer(second),
                "the first commit already moved the entity away from the recorded source");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
    }

    @Test
    void aRegionUnderAnActiveLeaseIsNeverWritten() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos destinationPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = activeDestination(destinationPosition);
        final Entity occupant = registeredEntity(destinationPosition);
        final Object sourceRegion = trackedRegion(this.engine, entity);
        final Object destinationRegion = trackedRegion(this.engine, occupant);

        beginRegionLease(sourceRegion);
        try {
            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            assertNotNull(token);
            assertEquals(RegionEntityTransferResult.REGION_BUSY, this.engine.commitEntityRegionTransfer(token),
                    "a commit must not write while the source region is leased");
            assertEquals(source, trackedCoordinate(this.engine, entity));
        } finally {
            endRegionLease(sourceRegion);
        }

        beginRegionLease(destinationRegion);
        try {
            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            assertNotNull(token);
            assertEquals(RegionEntityTransferResult.REGION_BUSY, this.engine.commitEntityRegionTransfer(token),
                    "a commit must not write while the destination region is leased");
            assertEquals(source, trackedCoordinate(this.engine, entity));
        } finally {
            endRegionLease(destinationRegion);
        }

        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);
        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                "a released region must not keep the recorded transfer busy");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
    }

    @Test
    void abortReleasesThePendingEntryAndALaterCommitAnswersCancelled() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);
        assertEquals(1, this.engine.pendingEntityTransferCount());

        assertEquals(RegionEntityTransferResult.CANCELLED, this.engine.abortEntityRegionTransfer(token));
        assertEquals(0, this.engine.pendingEntityTransferCount(), "the abort must release the pending entry");
        assertEquals(source, trackedCoordinate(this.engine, entity), "an abort must not move the entity");

        assertEquals(RegionEntityTransferResult.CANCELLED, this.engine.abortEntityRegionTransfer(token),
                "an abort must be idempotent");
        assertEquals(RegionEntityTransferResult.CANCELLED, this.engine.commitEntityRegionTransfer(token),
                "an aborted token can never be committed");
        assertEquals(source, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void claimedTokenAnswersInProgressUntilItsCommitCompletes() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        // A commit that already claimed the token but has not applied it yet.
        claimThroughJournal(this.engine, token);

        assertEquals(RegionEntityTransferResult.IN_PROGRESS, this.engine.commitEntityRegionTransfer(token),
                "a claimed but unapplied entry must not report ALREADY_COMMITTED");
        assertEquals(RegionEntityTransferResult.IN_PROGRESS, this.engine.abortEntityRegionTransfer(token));
        assertEquals(1, this.engine.pendingEntityTransferCount(), "an in-flight token keeps its pending entry");
        assertEquals(source, trackedCoordinate(this.engine, entity), "an in-flight token must not be applied twice");
        assertEquals(0, this.engine.rememberedEntityTransferResultCount());
    }

    @Test
    void committedTransferDropsAPendingDeferredEntityUpdate() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos destinationPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = activeDestination(destinationPosition);
        // The batch deferred its handoff after the entity already reached the third region, so the captured
        // destination is the one that must lose against the committed transfer.
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, regionAt(new BlockPos(8192, 64, 0)));
        try {
            // The live position keeps moving after the batch deferred its handoff. Publishing that
            // deferred update would move the entity to the third region instead of the committed one.
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            assertNotNull(token);
            assertEquals(source, token.sourceRegion());
            assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token));
            assertEquals(destination, trackedCoordinate(this.engine, entity));

            applyDeferredEntityUpdates(batch, this.engine);
            assertEquals(destination, trackedCoordinate(this.engine, entity),
                    "the committed mapping must win over the older deferred update");
        } finally {
            discardPublishedBatch(this.engine, batch);
        }
    }

    @Test
    void deferredMoveCommitsTheDestinationCapturedAtDeferTime() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, destination);
        try {
            // The worker observed the destination while it ticked the entity; the live position keeps moving
            // until the barrier runs.
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            applyDeferredEntityUpdates(batch, this.engine);

            assertEquals(destination, trackedCoordinate(this.engine, entity),
                    "the barrier must commit the destination captured at defer time");
            assertEquals(0, this.engine.pendingEntityTransferCount());
            assertEquals(1, this.engine.rememberedEntityTransferResultCount());
            assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                    "a recorded handoff must not fall back to the live position");
        } finally {
            discardPublishedBatch(this.engine, batch);
        }
    }

    @Test
    void aRefusedRecordingKeepsTheLegacyDeferredPublish() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final ServerLevel otherLevel = mock(ServerLevel.class);
        // A cross-level destination is outside the recorded transaction, so the update has no decision.
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, new RegionCoordinate(otherLevel, 1, 1));
        try {
            when(entity.blockPosition()).thenReturn(new BlockPos(4096, 64, 0));

            applyDeferredEntityUpdates(batch, this.engine);

            assertEquals(regionAt(new BlockPos(4096, 64, 0)), trackedCoordinate(this.engine, entity),
                    "a refused recording must publish the deferred move from the live position like before");
            assertNotEquals(source, trackedCoordinate(this.engine, entity));
            assertEquals(0, this.engine.pendingEntityTransferCount(), "a refused recording leaves no decision behind");
            assertEquals(0, this.engine.rememberedEntityTransferResultCount());
            assertEquals(1L, this.engine.diagnostics().legacyDeferredEntityMoveCount());
        } finally {
            discardPublishedBatch(this.engine, batch);
        }
    }

    @Test
    void aBusyDestinationDelaysTheCapturedMoveToTheNextBarrier() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final Object destinationRegion = region(this.engine, destination);
        final Object firstBatch = publishedEntityBatch(this.engine, this.level, entity, destination);
        final Object nextBatch = publishedEntityBatch(this.engine, this.level, entity, destination);
        try {
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            beginRegionLease(destinationRegion);
            try {
                applyDeferredEntityUpdates(firstBatch, this.engine);
                assertEquals(source, trackedCoordinate(this.engine, entity),
                        "a busy destination must neither drop nor redirect the deferred move");
                assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                        "a busy destination must not fall back to the live position");
                assertEquals(1L, this.engine.diagnostics().retriedDeferredEntityMoveCount());
            } finally {
                endRegionLease(destinationRegion);
            }

            // The next entity batch barrier applies the same captured destination.
            applyDeferredEntityUpdates(nextBatch, this.engine);

            assertEquals(destination, trackedCoordinate(this.engine, entity),
                    "the retried move must keep the destination captured at defer time");
            assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount());
            assertEquals(0, this.engine.pendingEntityTransferCount());
        } finally {
            discardPublishedBatch(this.engine, firstBatch);
            discardPublishedBatch(this.engine, nextBatch);
        }
    }

    @Test
    void aNewerCaptureAtTheNextBarrierSupersedesAPendingBusyRetry() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate olderDestination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionCoordinate newerDestination = activeDestination(new BlockPos(8192, 64, 0));
        final Object olderDestinationRegion = region(this.engine, olderDestination);
        final Object firstBatch = publishedEntityBatch(this.engine, this.level, entity, olderDestination);
        try {
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            // Barrier N: the older capture is queued because its destination region is leased.
            beginRegionLease(olderDestinationRegion);
            try {
                applyDeferredEntityUpdates(firstBatch, this.engine);
            } finally {
                endRegionLease(olderDestinationRegion);
            }
            assertEquals(source, trackedCoordinate(this.engine, entity));
            assertEquals(1, pendingDeferredEntityMoves(this.engine).size(),
                    "the busy destination must wait for the next barrier");

            // Barrier N+1: the same entity is deferred again, now with a newer destination.
            final Object nextBatch = publishedEntityBatch(this.engine, this.level, entity, newerDestination);
            try {
                final long legacyBefore = this.engine.diagnostics().legacyDeferredEntityMoveCount();

                applyDeferredEntityUpdates(nextBatch, this.engine);

                assertEquals(newerDestination, trackedCoordinate(this.engine, entity),
                        "the newer capture of the batch must win over the queued older destination");
                assertEquals(0, pendingDeferredEntityMoves(this.engine).size(),
                        "committing the newer capture must prune the stale queued retry");
                assertEquals(legacyBefore, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                        "the newer capture must not fall back to the live position");
            } finally {
                discardPublishedBatch(this.engine, nextBatch);
            }
        } finally {
            discardPublishedBatch(this.engine, firstBatch);
        }
    }

    @Test
    void aRegionThatStaysBusyEndsOnTheLegacyPublishAfterTheRetryBound() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final Object destinationRegion = region(this.engine, destination);
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, destination);
        try {
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            beginRegionLease(destinationRegion);
            try {
                applyDeferredEntityUpdates(batch, this.engine);
                applyDeferredEntityUpdates(batch, this.engine);
                assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                        "the move waits for the busy region instead of being published from the live position");

                // The retry bound is finite, so a region that never becomes idle cannot keep the entry alive.
                for (int barrier = 0; barrier < 16 && this.engine.diagnostics().legacyDeferredEntityMoveCount() == 0L; barrier++) {
                    applyDeferredEntityUpdates(batch, this.engine);
                }
                assertEquals(1L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                        "a region that stays busy must end on the pre-transaction publish");
                assertEquals(regionAt(new BlockPos(8192, 64, 0)), trackedCoordinate(this.engine, entity));
            } finally {
                endRegionLease(destinationRegion);
            }

            applyDeferredEntityUpdates(batch, this.engine);
            assertEquals(1L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                    "an exhausted retry must not be queued again");
        } finally {
            discardPublishedBatch(this.engine, batch);
        }
    }

    @Test
    void aCommittedTransferDropsAPendingBusyRetry() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate busyDestination = activeDestination(new BlockPos(4096, 64, 0));
        final Object destinationRegion = region(this.engine, busyDestination);
        final RegionCoordinate committedDestination = activeDestination(new BlockPos(8192, 64, 0));
        final Object firstBatch = publishedEntityBatch(this.engine, this.level, entity, busyDestination);
        final Object nextBatch = publishedEntityBatch(this.engine, this.level, entity, busyDestination);
        try {
            when(entity.blockPosition()).thenReturn(new BlockPos(8192, 64, 0));

            beginRegionLease(destinationRegion);
            try {
                applyDeferredEntityUpdates(firstBatch, this.engine);
                assertEquals(1L, this.engine.diagnostics().retriedDeferredEntityMoveCount());
            } finally {
                endRegionLease(destinationRegion);
            }

            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, committedDestination);
            assertNotNull(token);
            assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token));
            assertEquals(committedDestination, trackedCoordinate(this.engine, entity));

            // The waiting retry carries the older decision, so the commit has to drop it.
            applyDeferredEntityUpdates(nextBatch, this.engine);
            assertEquals(committedDestination, trackedCoordinate(this.engine, entity),
                    "a committed transfer must win over a pending retry of an older destination");
        } finally {
            discardPublishedBatch(this.engine, firstBatch);
            discardPublishedBatch(this.engine, nextBatch);
        }
    }

    @Test
    void aShutDownEngineKeepsTheLegacyDeferredPublish() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, destination);
        // A mechanics batch is never submitted, so it must not stay registered as an active batch: shutdown
        // waits for every published batch to complete its barrier, and this one never will.
        discardPublishedBatch(this.engine, batch);

        this.engine.shutdown();
        applyDeferredEntityUpdates(batch, this.engine);

        assertEquals(0, this.engine.pendingEntityTransferCount(), "a shut-down engine records nothing");
        assertEquals(0, this.engine.rememberedEntityTransferResultCount());
        assertEquals(1L, this.engine.diagnostics().legacyDeferredEntityMoveCount());
    }

    @Test
    void aPreWriteFailureAnswersCommitFailedAndLeavesATerminalJournalEntry() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        // The recorded-epoch read is the first dependency the engine touches after the claim and before the
        // regionizer write, so making it fail is a real pre-write exception and not a reported outcome.
        doThrow(new IllegalStateException("injected identity ledger failure before the region write"))
                .when(this.level).entityIdentityLedger();

        assertEquals(RegionEntityTransferResult.COMMIT_FAILED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity), "the source mapping must stay intact");
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(1, this.engine.rememberedEntityTransferResultCount(),
                "the failed commit stays terminal in the journal");

        assertEquals(RegionEntityTransferResult.COMMIT_FAILED, this.engine.commitEntityRegionTransfer(token),
                "a pre-write failure is terminal and is never retried");
        assertEquals(source, trackedCoordinate(this.engine, entity));
    }

    @Test
    void aMaintenanceFailureAfterTheWriteKeepsTheTransferCommitted() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final Object batch = publishedEntityBatch(this.engine, this.level, entity, destination);
        final AtomicInteger maintenanceAttempts = new AtomicInteger();
        try {
            // The engine discards the deferred ownership handoff of the committed entity after the region
            // mapping was written. Replacing that batch's deferred-update map with one that refuses the discard
            // makes the real maintenance step throw at its real call point.
            setDeferredUpdates(batch, new IdentityHashMap<Object, Object>() {
                @Override
                public Object remove(Object key) {
                    maintenanceAttempts.incrementAndGet();
                    throw new IllegalStateException("injected deferred-update discard failure after the region write");
                }
            });

            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            assertNotNull(token);

            assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                    "a post-write maintenance failure must not downgrade the committed mapping");
            assertTrue(maintenanceAttempts.get() > 0, "the maintenance path must have been reached");
            assertEquals(destination, trackedCoordinate(this.engine, entity));
            assertEquals(0, this.engine.pendingEntityTransferCount());
            assertEquals(1, this.engine.rememberedEntityTransferResultCount());

            assertEquals(RegionEntityTransferResult.ALREADY_COMMITTED, this.engine.commitEntityRegionTransfer(token));
            assertEquals(destination, trackedCoordinate(this.engine, entity));
            assertEquals(0, this.engine.pendingEntityTransferCount());
        } finally {
            discardPublishedBatch(this.engine, batch);
        }
    }

    @Test
    void aNotificationFailureAfterTheWriteKeepsTheTransferCommitted() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        this.engine.entityScheduler().runDelayed(entity, () -> {}, 1L);
        final AtomicInteger notificationAttempts = new AtomicInteger();

        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);
        // The committed transfer notifies the registered scheduled task of the moved entity. The task's
        // real onEntityMoved() reads the entity position first, which throws here.
        when(entity.blockPosition()).thenAnswer(invocation -> {
            notificationAttempts.incrementAndGet();
            throw new IllegalStateException("injected scheduled-task notification failure after the region write");
        });

        assertEquals(RegionEntityTransferResult.COMMITTED, this.engine.commitEntityRegionTransfer(token),
                "a post-write notification failure must not downgrade the committed mapping");
        assertTrue(notificationAttempts.get() > 0, "the notification path must have been reached");
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(1, this.engine.rememberedEntityTransferResultCount());

        assertEquals(RegionEntityTransferResult.ALREADY_COMMITTED, this.engine.commitEntityRegionTransfer(token));
        assertEquals(destination, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void aRecordingThatRacedALevelClearDoesNotInsertAPendingDecision() throws Exception {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final CountDownLatch recordingAtLedger = new CountDownLatch(1);
        final CountDownLatch clearFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> clearFailure = new AtomicReference<>();
        final Thread clearThread = new Thread(() -> {
            try {
                if (!recordingAtLedger.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the recording did not reach the recorded-epoch read");
                }
                this.engine.clearLevelRegionTasks(this.level);
            } catch (Throwable throwable) {
                clearFailure.set(throwable);
            } finally {
                clearFinished.countDown();
            }
        }, "entity transfer level clear racer");
        clearThread.setDaemon(true);
        clearThread.start();
        doAnswer(invocation -> {
            // The recorded-epoch read is the last read of the recording path before its admission. The
            // clear completes while the recording is paused here, so the admission re-check is forced.
            recordingAtLedger.countDown();
            if (!clearFinished.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the level clear did not complete");
            }
            return this.ledger;
        }).when(this.level).entityIdentityLedger();

        // The recording runs on the level's server thread (this test thread), the lifecycle clear on the
        // racing thread; the recording is paused inside the recorded-epoch read until that clear finished.
        final RegionEntityTransferToken recorded = this.engine.recordEntityRegionTransfer(entity, destination);
        clearThread.join(TimeUnit.SECONDS.toMillis(10));

        assertNull(clearFailure.get(), () -> "the level clear failed: " + clearFailure.get());
        assertFalse(clearThread.isAlive(), "the level clear must finish");
        assertNull(recorded, "a recording that observed the retired level must not return a token");
        assertEquals(0, this.engine.pendingEntityTransferCount(),
                "no pending decision may survive a level clear that the recording raced");
        assertEquals(0, this.engine.rememberedEntityTransferResultCount());
    }

    @Test
    void aLateCommitCompletionAfterShutdownDoesNotResurrectAJournalEntry() throws Exception {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
        assertNotNull(token);

        final CountDownLatch commitAtLedger = new CountDownLatch(1);
        final CountDownLatch shutdownFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            // The commit claimed the token and is paused at the recorded-epoch read, before any regionizer
            // write. The shutdown then clears the journal and the index under the commit.
            commitAtLedger.countDown();
            if (!shutdownFinished.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the engine shutdown did not complete");
            }
            return this.ledger;
        }).when(this.level).entityIdentityLedger();

        final Thread shutdownThread = new Thread(() -> {
            try {
                if (!commitAtLedger.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the commit did not reach the recorded-epoch read");
                }
                this.engine.shutdown();
            } catch (Throwable throwable) {
                shutdownFailure.set(throwable);
            } finally {
                shutdownFinished.countDown();
            }
        }, "region threading shutdown racer");
        shutdownThread.setDaemon(true);
        shutdownThread.start();

        final RegionEntityTransferResult result = this.engine.commitEntityRegionTransfer(token);
        shutdownThread.join(TimeUnit.SECONDS.toMillis(10));

        assertNull(shutdownFailure.get(), () -> "the engine shutdown failed: " + shutdownFailure.get());
        assertFalse(shutdownThread.isAlive(), "the shutdown thread must finish");
        assertNotEquals(RegionEntityTransferResult.COMMITTED, result, "the shutdown must win over the late commit");
        assertEquals(0, this.engine.pendingEntityTransferCount());
        assertEquals(0, this.engine.rememberedEntityTransferResultCount(),
                "a late completion must not resurrect a terminal journal entry after shutdown");
    }

    @Test
    void journalStaysBoundedAndTheRefusalIsObservable() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = activeDestination(new BlockPos(4096, 64, 0));
        final int bound = RegionThreadingEngine.maxPendingEntityTransfers();

        RegionEntityTransferToken lastAccepted = null;
        int accepted = 0;
        for (int i = 0; i < bound + 100; i++) {
            final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
            if (token != null) {
                lastAccepted = token;
                ++accepted;
            }
        }

        assertEquals(bound, accepted, "recording must stop at the journal bound");
        assertEquals(accepted, this.engine.pendingEntityTransferCount());
        assertEquals(accepted, this.engine.diagnostics().pendingEntityTransferCount());
        assertEquals(100L, this.engine.diagnostics().rejectedEntityTransferRecordCount(),
                "a full journal must be observable instead of silently disabling recording");
        assertNotNull(lastAccepted);

        this.engine.shutdown();

        assertEquals(0, this.engine.pendingEntityTransferCount(), "shutdown clears pending decisions");
        assertEquals(0, this.engine.rememberedEntityTransferResultCount(), "shutdown clears remembered results");
        assertEquals(RegionEntityTransferResult.ENGINE_SHUT_DOWN, this.engine.commitEntityRegionTransfer(lastAccepted));
    }

    @Test
    void crossLevelDestinationIsNotRecorded() {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final ServerLevel otherLevel = mock(ServerLevel.class);

        assertNull(this.engine.recordEntityRegionTransfer(entity, new RegionCoordinate(otherLevel, 1, 1)),
                "a cross-level destination must leave the caller on the vanilla path");
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void crossLevelCommitIsRejectedWithoutMutation() throws ReflectiveOperationException {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final ServerLevel otherLevel = mock(ServerLevel.class);
        final RegionCoordinate crossLevelDestination = new RegionCoordinate(otherLevel, 1, 1);
        final RegionEntityTransferToken token = forgedToken(entity, source, crossLevelDestination, this.ledger);
        assertTrue(recordInJournal(this.engine, token), "the journal must know the forged token");

        assertEquals(RegionEntityTransferResult.DESTINATION_INACTIVE, this.engine.commitEntityRegionTransfer(token));
        assertEquals(source, trackedCoordinate(this.engine, entity));
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    @Test
    void disabledRegionThreadingKeepsTheVanillaPath() {
        RegionThreadingManager.clear();
        final ServerLevel mockLevel = mock(ServerLevel.class);
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(mockLevel);

        assertFalse(RegionThreadingManager.isInstalled());
        RegionThreadingHooks.addRegionEntity(entity);
        RegionThreadingHooks.moveRegionEntity(entity);
        RegionThreadingHooks.removeRegionEntity(entity);
        assertNull(RegionThreadingHooks.recordEntityRegionTransfer(entity, new RegionCoordinate(mockLevel, 0, 0)));
        assertEquals(RegionEntityTransferResult.DISABLED, RegionThreadingHooks.commitEntityRegionTransfer(null));
        assertEquals(RegionEntityTransferResult.DISABLED, RegionThreadingHooks.abortEntityRegionTransfer(null));
    }

    @Test
    void aDestinationRetiredWhileCommittingNeverLeavesAHalfAppliedMapping() throws Exception {
        this.installEngine();
        final Entity entity = registeredEntity(new BlockPos(0, 64, 0));
        final RegionCoordinate source = trackedCoordinate(this.engine, entity);
        final BlockPos destinationPosition = new BlockPos(4096, 64, 0);
        final RegionCoordinate destination = regionAt(destinationPosition);
        this.engine.addRegionSection(this.level, chunkX(destinationPosition), chunkZ(destinationPosition));

        final AtomicBoolean stop = new AtomicBoolean();
        final Thread racer = new Thread(() -> {
            while (!stop.get()) {
                this.engine.addRegionSection(this.level, chunkX(destinationPosition), chunkZ(destinationPosition));
                this.engine.removeRegionSection(this.level, chunkX(destinationPosition), chunkZ(destinationPosition));
            }
        }, "entity transfer destination racer");
        racer.setDaemon(true);
        racer.start();
        try {
            for (int i = 0; i < 100; i++) {
                final RegionEntityTransferToken token = this.engine.recordEntityRegionTransfer(entity, destination);
                assertNotNull(token, "the entity keeps its source region for the whole race");
                final RegionEntityTransferResult result = this.engine.commitEntityRegionTransfer(token);
                assertTrue(result == RegionEntityTransferResult.COMMITTED || result == RegionEntityTransferResult.DESTINATION_INACTIVE
                        || result == RegionEntityTransferResult.REGION_BUSY || result == RegionEntityTransferResult.SOURCE_MAPPING_CHANGED,
                        () -> "a racing retirement must not produce an unrelated result: " + result);
                final RegionCoordinate current = trackedCoordinate(this.engine, entity);
                assertTrue(current.equals(source) || current.equals(destination),
                        () -> "a racing commit must leave the mapping at the source or the destination, got: " + current);
                if (result == RegionEntityTransferResult.COMMITTED) {
                    // Put the entity back at the source so the next iteration starts from the recorded state.
                    this.engine.addRegionEntity(entity);
                }
            }
        } finally {
            stop.set(true);
            racer.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertEquals(0, this.engine.pendingEntityTransferCount());
    }

    private void installEngine() {
        this.engine = new RegionThreadingEngine(1, false, false);
        this.ownerThread = Thread.currentThread();
        this.ledger = new EntityIdentityLedger();
        this.level = transferLevel(this.ledger);
    }

    /**
     * Creates a mechanics level whose server answers {@link MinecraftServer#isSameThread()} for the test's
     * own thread only, so a test that runs on an ordinary background thread is rejected while the test body
     * itself keeps the server-thread affinity of a recorded transfer.
     */
    private ServerLevel transferLevel(EntityIdentityLedger levelLedger) {
        final ServerLevel mockLevel = mock(ServerLevel.class);
        doReturn(levelLedger).when(mockLevel).entityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == this.ownerThread);
        doReturn(server).when(mockLevel).getServer();
        return mockLevel;
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

    private static void assertOperationRejected(@Nullable Throwable failure, String operation) {
        assertNotNull(failure, () -> "the " + operation + " attempt was not rejected");
        assertTrue(failure instanceof IllegalStateException,
                () -> "the rejection must be an IllegalStateException, got: " + failure);
        assertTrue(failure.getMessage().contains(operation),
                () -> "the rejection must name " + operation + ", got: " + failure.getMessage());
    }

    private static RegionWorkerExecutor workerPool(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        return (RegionWorkerExecutor) field.get(engine);
    }

    private static RegionCoordinate trackedCoordinate(RegionThreadingEngine engine, Entity entity) throws ReflectiveOperationException {
        final Object region = trackedRegion(engine, entity);
        assertNotNull(region, "the entity is not tracked by the regionizer");
        final Method coordinate = region.getClass().getDeclaredMethod("coordinate");
        coordinate.setAccessible(true);
        return (RegionCoordinate) coordinate.invoke(region);
    }

    private static Object trackedRegion(RegionThreadingEngine engine, Entity entity) throws ReflectiveOperationException {
        return trackedEntities(engine).get(entity);
    }

    /** {@return the live region state at {@code coordinate}, or {@code null} when none exists there} */
    private static Object region(RegionThreadingEngine engine, RegionCoordinate coordinate) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(engine);
        final Method get = regionizer.getClass().getDeclaredMethod("get", RegionCoordinate.class);
        get.setAccessible(true);
        return get.invoke(regionizer, coordinate);
    }

    private static Map<?, ?> trackedEntities(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
        regionizerField.setAccessible(true);
        final Object regionizer = regionizerField.get(engine);
        final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
        entitiesField.setAccessible(true);
        return (Map<?, ?>) entitiesField.get(regionizer);
    }

    /** Marks the region as ticked, which is the lease {@code mergeRegions} and a commit must respect. */
    private static void beginRegionLease(Object region) throws ReflectiveOperationException {
        final Method tryMarkRunning = region.getClass().getDeclaredMethod("tryMarkRunning");
        tryMarkRunning.setAccessible(true);
        assertTrue((Boolean) tryMarkRunning.invoke(region), "the region must accept the lease");
    }

    private static void endRegionLease(Object region) throws ReflectiveOperationException {
        final Method clearRunning = region.getClass().getDeclaredMethod("clearRunning");
        clearRunning.setAccessible(true);
        clearRunning.invoke(region);
    }

    private static Object entityTransferJournal(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("entityTransferJournal");
        field.setAccessible(true);
        return field.get(engine);
    }

    private static void claimThroughJournal(RegionThreadingEngine engine, RegionEntityTransferToken token) throws ReflectiveOperationException {
        final Object journal = entityTransferJournal(engine);
        final Method claim = journal.getClass().getDeclaredMethod("claim", RegionEntityTransferToken.class);
        claim.setAccessible(true);
        claim.invoke(journal, token);
    }

    private static boolean recordInJournal(RegionThreadingEngine engine, RegionEntityTransferToken token) throws ReflectiveOperationException {
        final Object journal = entityTransferJournal(engine);
        final Method record = journal.getClass().getDeclaredMethod("record", RegionEntityTransferToken.class);
        record.setAccessible(true);
        return (Boolean) record.invoke(journal, token);
    }

    /**
     * Builds a token the public API refuses to record, so a defensive commit validation stays covered.
     * <p>
     * The token is registered in the journal like a recorded decision; only the destination level is one
     * the recording path never accepts.
     */
    private RegionEntityTransferToken forgedToken(Entity entity, RegionCoordinate source, RegionCoordinate destination, EntityIdentityLedger entityLedger) throws ReflectiveOperationException {
        final UUID uuid = entity.getUUID();
        final long identityEpoch = entityLedger.current(uuid).epoch();
        final Constructor<RegionEntityTransferToken> constructor = RegionEntityTransferToken.class.getDeclaredConstructor(
                long.class,
                RegionEntityTransferToken.Operation.class,
                Entity.class,
                UUID.class,
                long.class,
                RegionCoordinate.class,
                RegionCoordinate.class,
                List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                this.nextForgedTokenId.getAndDecrement(),
                RegionEntityTransferToken.Operation.REGION_MOVE,
                entity,
                uuid,
                identityEpoch,
                source,
                destination,
                List.of(new RegionEntityTransferToken.Member(entity, uuid, identityEpoch)));
    }

    private static Object publishedEntityBatch(RegionThreadingEngine engine, ServerLevel batchLevel, Entity deferred, RegionCoordinate destination) throws ReflectiveOperationException {
        final Constructor<?> constructor = Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class, int.class);
        constructor.setAccessible(true);
        final Object batch = constructor.newInstance(batchLevel, DEFERRED_BLOCK_UPDATE_ENTRIES);
        activeTickBatches(engine).add(batch);
        final Method deferEntityUpdate = batch.getClass().getDeclaredMethod("deferEntityUpdate", Entity.class, RegionCoordinate.class);
        deferEntityUpdate.setAccessible(true);
        deferEntityUpdate.invoke(batch, deferred, destination);
        return batch;
    }

    /** Replaces the deferred-update map of a published batch with an injected failing dependency. */
    private static void setDeferredUpdates(Object batch, Map<?, ?> updates) throws ReflectiveOperationException {
        final Field field = batch.getClass().getDeclaredField("deferredUpdates");
        field.setAccessible(true);
        field.set(batch, updates);
    }

    private static void applyDeferredEntityUpdates(Object batch, RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Method apply = RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntityUpdates", Class.forName(ENTITY_BATCH_TYPE));
        apply.setAccessible(true);
        apply.invoke(engine, batch);
    }

    private static void discardPublishedBatch(RegionThreadingEngine engine, Object batch) throws ReflectiveOperationException {
        activeTickBatches(engine).remove(batch);
    }

    private static Set<Object> activeTickBatches(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("activeTickBatches");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<Object> batches = (Set<Object>) field.get(engine);
        return batches;
    }

    /** {@return the deferred entity moves that wait for a busy region at the next barrier} */
    private static List<?> pendingDeferredEntityMoves(RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Field field = RegionThreadingEngine.class.getDeclaredField("pendingDeferredEntityMoves");
        field.setAccessible(true);
        return (List<?>) field.get(engine);
    }
}
