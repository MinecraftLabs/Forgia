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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentity;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the identity publication contract on the real persistent entity manager and on the ledger
 * itself.
 * <p>
 * Every manager test uses a real {@link PersistentEntitySectionManager} with a controllable callback, so
 * the published state is compared against the actual vanilla maps (UUID set, visible lookup, section
 * membership, ticking list) instead of against a test double. The ledger tests use the same class the
 * server thread uses; nothing here re-implements the contract.
 */
@Timeout(30)
class EntityIdentityLedgerTest {
    private RegionThreadingEngine engine;

    @AfterEach
    void clearThreading() {
        try {
            RegionThreadingManager.clear();
        } finally {
            if (this.engine != null) {
                this.engine.shutdown();
                this.engine = null;
            }
        }
    }

    @Test
    void managerPublishesAdmittedThenTrackedAndBackForChunkVisibility() {
        final TestCallbacks callbacks = new TestCallbacks();
        final PersistentEntitySectionManager<Entity> manager = newManager(callbacks);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final UUID uuid = entity.getUUID();
        callbacks.watch(ledger);

        assertTrue(manager.addNewEntityWithoutEvent(entity), "the server thread admits the entity");
        final EntityIdentity admitted = ledger.current(uuid);
        assertNotNull(admitted, "admission must publish an identity");
        assertEquals(EntityIdentity.State.ADMITTED, admitted.state());
        assertFalse(admitted.isVisible(), "a hidden chunk is admitted but not visible");
        assertEquals(entity.getId(), admitted.entityId());
        assertEquals(admitted, ledger.current(entity.getId()), "the int id binding is published with the identity");
        assertTrue(manager.isLoaded(uuid));
        assertEquals(0, manager.count(), "a hidden section is not in the visible lookup");
        assertTrue(callbacks.calls.contains("created"));

        manager.updateChunkStatus(new ChunkPos(0, 0), FullChunkStatus.FULL);
        final EntityIdentity tracked = ledger.current(uuid);
        assertNotNull(tracked);
        assertEquals(EntityIdentity.State.TRACKED, tracked.state());
        assertTrue(tracked.isVisible());
        assertEquals(1, manager.count());
        assertSame(entity, manager.getEntityGetter().get(uuid));
        assertTrue(tracked.epoch() > admitted.epoch(), "each publication advances the lifecycle epoch");
        assertEquals("tracking-start", callbacks.calls.get(callbacks.calls.size() - 1));

        manager.updateChunkStatus(new ChunkPos(0, 0), FullChunkStatus.ENTITY_TICKING);
        assertTrue(callbacks.ticking.contains(entity), "the ticking callback ran for a ticking chunk");
        assertEquals(EntityIdentity.State.TRACKED, ledger.current(uuid).state(), "visibility is published, ticking stays server-owned");
        assertTrue(ledger.current(uuid).epoch() >= tracked.epoch());

        manager.updateChunkStatus(new ChunkPos(0, 0), Visibility.HIDDEN);
        final EntityIdentity hiddenAgain = ledger.current(uuid);
        assertNotNull(hiddenAgain);
        assertEquals(EntityIdentity.State.ADMITTED, hiddenAgain.state());
        assertFalse(hiddenAgain.isVisible());
        assertFalse(callbacks.ticking.contains(entity));
        assertEquals(0, manager.count());
        assertTrue(manager.isLoaded(uuid), "hiding a chunk must not remove the UUID admission");
        assertTrue(hiddenAgain.epoch() > tracked.epoch());
        assertEquals(0L, ledger.rejectedTransitions(), "the visibility round trip is a legal publication sequence");
    }

    @Test
    void managerRejectsDuplicateUuidBeforeAnyMapWrite() {
        final TestCallbacks callbacks = new TestCallbacks();
        final PersistentEntitySectionManager<Entity> manager = newManager(callbacks);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity first = new TestEntity(level);
        first.setPos(1.0, 64.0, 1.0);
        assertTrue(manager.addNewEntityWithoutEvent(first));
        final EntityIdentity firstIdentity = ledger.current(first.getUUID());

        final TestEntity duplicate = new TestEntity(level);
        duplicate.setPos(9.0, 64.0, 9.0);
        duplicate.setUUID(first.getUUID());
        assertFalse(manager.addNewEntityWithoutEvent(duplicate), "a duplicate UUID is rejected synchronously");
        assertEquals(1L, ledger.duplicateUuidAdmissions());
        assertSame(firstIdentity, ledger.current(first.getUUID()));
        assertEquals(1, ledger.liveCount());
        assertNull(ledger.current(duplicate.getId()), "the rejected identity must not publish its int id");
        assertEquals(0, manager.count(), "the duplicate never reached the visible lookup");
        assertTrue(callbacks.calls.stream().noneMatch("tracking-start"::equals), "the duplicate was never tracked");
    }

    @Test
    void managerRejectsDuplicateIntIdBeforeAnyMapWrite() {
        final PersistentEntitySectionManager<Entity> manager = newManager(new TestCallbacks());
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity first = new TestEntity(level);
        first.setPos(1.0, 64.0, 1.0);
        assertTrue(manager.addNewEntityWithoutEvent(first));
        final EntityIdentity firstIdentity = ledger.current(first.getId());

        final TestEntity duplicate = new TestEntity(level);
        duplicate.setPos(5.0, 64.0, 5.0);
        duplicate.setId(first.getId());
        assertFalse(manager.addNewEntityWithoutEvent(duplicate), "a duplicate int id is rejected synchronously");
        assertEquals(1L, ledger.duplicateIdAdmissions());
        assertSame(firstIdentity, ledger.current(first.getId()), "the first identity keeps its id binding");
        assertNull(ledger.current(duplicate.getUUID()), "the rejected identity is not published");
        assertFalse(manager.isLoaded(duplicate.getUUID()));
        assertTrue(manager.isLoaded(first.getUUID()));
    }

    @Test
    void managerCanceledJoinPublishesNoIdentity() {
        final TestCallbacks callbacks = new TestCallbacks();
        final PersistentEntitySectionManager<Entity> manager = newManager(callbacks);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final UUID uuid = entity.getUUID();
        final Consumer<EntityJoinLevelEvent> cancel = event -> event.setCanceled(true);

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
        try {
            assertFalse(manager.addNewEntity(entity), "a canceled join reports false synchronously");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
        }

        assertNull(ledger.current(uuid), "a canceled join must not publish any identity");
        assertEquals(0, ledger.liveCount());
        assertFalse(manager.isLoaded(uuid));
        assertEquals(0, manager.count());
        assertTrue(callbacks.calls.isEmpty(), "a canceled join runs no entity callback");
    }

    @Test
    void managerPublishesDepartingBeforeGone() {
        final TestCallbacks callbacks = new TestCallbacks();
        final PersistentEntitySectionManager<Entity> manager = newManager(callbacks);
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final UUID uuid = entity.getUUID();
        final int id = entity.getId();
        assertTrue(manager.addNewEntityWithoutEvent(entity));
        manager.updateChunkStatus(new ChunkPos(0, 0), FullChunkStatus.FULL);
        final EntityIdentity admitted = ledger.current(uuid);
        assertEquals(EntityIdentity.State.TRACKED, admitted.state());
        callbacks.watch(ledger);

        entity.setRemoved(Entity.RemovalReason.DISCARDED);

        assertEquals(EntityIdentity.State.DEPARTING, callbacks.stateDuringTrackingEnd, "the departure is published before the maps are released");
        assertEquals(EntityIdentity.State.DEPARTING, callbacks.stateDuringDestroyed);
        final EntityIdentity gone = ledger.current(uuid);
        assertNotNull(gone);
        assertEquals(EntityIdentity.State.GONE, gone.state());
        assertFalse(gone.isLive());
        assertFalse(ledger.isLive(uuid), "the departure releases the live identity");
        assertNull(ledger.current(id), "a departed identity releases its int id binding");
        assertEquals(0, ledger.liveCount());
        assertFalse(manager.isLoaded(uuid));
        assertTrue(gone.epoch() > admitted.epoch());
        assertEquals(1, ledger.rememberedDepartureCount());
        assertEquals(0L, ledger.rejectedTransitions(), "a removal is a legal publication sequence");
    }

    @Test
    void managerCloseSealsLedgerAndRejectsLateAdmission() throws Exception {
        final PersistentEntitySectionManager<Entity> manager = newManager(new TestCallbacks());
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final UUID uuid = entity.getUUID();
        assertTrue(manager.addNewEntityWithoutEvent(entity));
        assertTrue(ledger.isLive(uuid));

        manager.close();

        assertTrue(ledger.isSealed());
        assertEquals(EntityIdentity.State.GONE, ledger.current(uuid).state(), "close publishes the remaining identities as gone");
        assertEquals(0, ledger.liveCount());

        final TestEntity late = new TestEntity(level);
        late.setPos(3.0, 64.0, 3.0);
        assertFalse(manager.addNewEntityWithoutEvent(late), "a closed manager rejects admission");
        assertNull(ledger.current(late.getUUID()));
        assertFalse(manager.isLoaded(late.getUUID()));
        assertEquals(0, manager.count());
    }

    @Test
    void managerRebindsThePublishedIdentityOnPlayerRespawnReId() {
        final PersistentEntitySectionManager<Entity> manager = newManager(new TestCallbacks());
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final int previousId = entity.getId();
        final UUID uuid = entity.getUUID();
        assertTrue(manager.addNewEntityWithoutEvent(entity));
        final EntityIdentity admitted = ledger.current(uuid);

        // Exactly what PlayerList.respawn does after the old player is gone: the new player adopts its id.
        entity.setId(previousId + 7);

        assertNull(ledger.current(previousId), "the previous id binding is released");
        final EntityIdentity rebound = ledger.current(previousId + 7);
        assertNotNull(rebound);
        assertEquals(uuid, rebound.uuid());
        assertEquals(EntityIdentity.State.ADMITTED, rebound.state());
        assertEquals(previousId + 7, rebound.entityId());
        assertSame(rebound, ledger.current(uuid));
        assertTrue(rebound.epoch() > admitted.epoch());
        assertEquals(0L, ledger.rejectedRebinds());
    }

    @Test
    void workerCannotMutateTheLedgerButReadsThePublication() {
        this.engine = new RegionThreadingEngine(2, false, false, false, false, false, false, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
        final PersistentEntitySectionManager<Entity> manager = newManager(new TestCallbacks());
        final EntityIdentityLedger ledger = manager.identityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        assertTrue(manager.addNewEntityWithoutEvent(entity));
        final EntityIdentity published = ledger.current(entity.getUUID());
        final TestEntity other = new TestEntity(level);

        assertRejectedOnWorker("PersistentEntitySectionManager.identityLedger", () -> manager.identityLedger());
        assertRejectedOnWorker("EntityIdentityLedger.admit", () -> ledger.admit(other));
        assertRejectedOnWorker("EntityIdentityLedger.seal", ledger::seal);
        assertRejectedOnWorker("EntityIdentityLedger.publishGone", () -> ledger.publishGone(entity));

        final AtomicReference<EntityIdentity> readOnWorker = new AtomicReference<>();
        runOnNativeWorker(() -> readOnWorker.set(ledger.current(entity.getUUID())));
        assertSame(published, readOnWorker.get(), "the published record is readable from a native worker");
        assertFalse(ledger.isSealed(), "a rejected seal must not seal the ledger");
        assertTrue(ledger.isLive(entity.getUUID()));
    }

    @Test
    void ledgerReservesAndRejectsIllegalPublications() {
        final EntityIdentityLedger ledger = new EntityIdentityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        final UUID uuid = entity.getUUID();

        assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(entity));
        final EntityIdentity reserved = ledger.current(uuid);
        assertNotNull(reserved);
        assertEquals(EntityIdentity.State.RESERVED, reserved.state());
        assertFalse(reserved.isVisible(), "a reservation is not visible");

        // TRACKED is unreachable from RESERVED: visibility without the visible lookup would be a lie.
        ledger.publishTracked(entity);
        assertEquals(EntityIdentity.State.RESERVED, ledger.current(uuid).state());
        assertEquals(1L, ledger.rejectedTransitions());

        assertEquals(EntityIdentityLedger.Admission.DUPLICATE_UUID, ledger.admit(entity));
        assertEquals(1L, ledger.duplicateUuidAdmissions());

        ledger.publishAdmitted(entity);
        assertEquals(EntityIdentity.State.ADMITTED, ledger.current(uuid).state());
        ledger.publishTracked(entity);
        ledger.publishGone(entity);
        assertEquals(EntityIdentity.State.GONE, ledger.current(uuid).state());
        assertFalse(ledger.isLive(uuid));

        // A gone identity is not a valid admission target for a publication.
        ledger.publishDeparting(entity);
        assertEquals(1L, ledger.unknownTransitions(), "a publication for a retired identity is counted, not applied");
    }

    @Test
    void ledgerRebindsIdWithoutMergingIdentities() {
        final EntityIdentityLedger ledger = new EntityIdentityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        final UUID uuid = entity.getUUID();
        final int id = entity.getId();
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(entity));
        ledger.publishAdmitted(entity);

        final int reboundId = id + 11;
        ledger.rebindId(entity, id, reboundId);
        assertNull(ledger.current(id), "the previous int id binding is released");
        assertEquals(reboundId, ledger.current(reboundId).entityId());
        assertEquals(uuid, ledger.current(reboundId).uuid());
        assertEquals(EntityIdentity.State.ADMITTED, ledger.current(uuid).state());

        // A rebind onto an id owned by another live identity is refused instead of merging the two.
        final TestEntity other = new TestEntity(level);
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(other));
        ledger.publishAdmitted(other);
        ledger.rebindId(other, other.getId(), reboundId);
        assertEquals(1L, ledger.rejectedRebinds());
        assertEquals(other.getUUID(), ledger.current(other.getId()).uuid());
        assertEquals(uuid, ledger.current(reboundId).uuid());
    }

    @Test
    void ledgerRebindsUuidWithoutMergingIdentities() {
        final EntityIdentityLedger ledger = new EntityIdentityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        final UUID uuid = entity.getUUID();
        final int id = entity.getId();
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(entity));
        ledger.publishAdmitted(entity);

        final UUID reboundUuid = UUID.randomUUID();
        ledger.rebindUuid(entity, uuid, reboundUuid);
        assertNull(ledger.current(uuid), "the previous UUID binding is released");
        assertEquals(reboundUuid, ledger.current(reboundUuid).uuid());
        assertEquals(id, ledger.current(id).entityId(), "the int id binding follows the rebind");

        // A rebind onto a UUID owned by another live identity is refused instead of merging the two.
        final TestEntity other = new TestEntity(level);
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(other));
        ledger.publishAdmitted(other);
        ledger.rebindUuid(other, other.getUUID(), reboundUuid);
        assertEquals(1L, ledger.rejectedRebinds());
        assertEquals(other.getUUID(), ledger.current(other.getId()).uuid());
        assertEquals(reboundUuid, ledger.current(reboundUuid).uuid());
    }

    @Test
    void ledgerKeepsDepartureRecordsBounded() {
        final EntityIdentityLedger ledger = new EntityIdentityLedger();
        final ServerLevel level = mockLevel(ledger);
        final TestEntity entity = new TestEntity(level);
        for (int i = 0; i < 1200; i++) {
            entity.setUUID(UUID.randomUUID());
            assertEquals(EntityIdentityLedger.Admission.ADMITTED, ledger.admit(entity));
            ledger.publishAdmitted(entity);
            ledger.publishGone(entity);
        }
        assertTrue(ledger.rememberedDepartureCount() <= 1024, "departure records stay bounded");
        assertEquals(0, ledger.liveCount());
    }

    private void assertRejectedOnWorker(String operation, Runnable action) {
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.runOnNativeWorker(action));
        assertTrue(
                thrown.getMessage().contains(operation),
                "expected the operation '" + operation + "' in the rejection, got: " + thrown.getMessage());
    }

    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("identity ledger test", () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the test task");
        try {
            assertTrue(done.await(10, TimeUnit.SECONDS), "the worker task did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        final Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
    }

    private RegionWorkerExecutor workerPool() {
        try {
            final Field field = RegionThreadingEngine.class.getDeclaredField("workerPool");
            field.setAccessible(true);
            return (RegionWorkerExecutor) field.get(this.engine);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newManager(LevelCallback<Entity> callbacks) {
        return new PersistentEntitySectionManager<>(
                Entity.class,
                callbacks,
                newStorage(),
                RegionThreadingHooks::assertServerEntityStorageAccess);
    }

    @SuppressWarnings("unchecked")
    private static EntityPersistentStorage<Entity> newStorage() {
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any())).thenAnswer(invocation -> {
            final ChunkPos pos = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new ChunkEntities<>(pos, ImmutableList.of()));
        });
        return storage;
    }

    private static ServerLevel mockLevel(EntityIdentityLedger ledger) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(ledger).when(level).entityIdentityLedger();
        return level;
    }

    /**
     * Records the vanilla callbacks and keeps the ticking list the patched callback would fill, so a test
     * can compare a published state with the real map that backs it.
     */
    private static final class TestCallbacks implements LevelCallback<Entity> {
        final EntityTickList ticking = new EntityTickList();
        final List<String> calls = new ArrayList<>();
        final Map<UUID, Entity> visible = new HashMap<>();
        @Nullable
        EntityIdentityLedger ledger;
        @Nullable
        EntityIdentity.State stateDuringTrackingEnd;
        @Nullable
        EntityIdentity.State stateDuringDestroyed;

        void watch(EntityIdentityLedger ledger) {
            this.ledger = ledger;
        }

        @Override
        public void onCreated(Entity entity) {
            this.calls.add("created");
        }

        @Override
        public void onDestroyed(Entity entity) {
            this.calls.add("destroyed");
            this.stateDuringDestroyed = this.stateOf(entity);
        }

        @Override
        public void onTickingStart(Entity entity) {
            this.ticking.add(entity);
            this.calls.add("ticking-start");
        }

        @Override
        public void onTickingEnd(Entity entity) {
            this.ticking.remove(entity);
            this.calls.add("ticking-end");
        }

        @Override
        public void onTrackingStart(Entity entity) {
            this.visible.put(entity.getUUID(), entity);
            this.calls.add("tracking-start");
        }

        @Override
        public void onTrackingEnd(Entity entity) {
            this.stateDuringTrackingEnd = this.stateOf(entity);
            this.visible.remove(entity.getUUID());
            this.calls.add("tracking-end");
        }

        @Override
        public void onSectionChange(Entity entity) {
            this.calls.add("section-change");
        }

        @Nullable
        private EntityIdentity.State stateOf(Entity entity) {
            if (this.ledger == null) {
                return null;
            }
            final EntityIdentity identity = this.ledger.current(entity.getUUID());
            return identity == null ? null : identity.state();
        }
    }

    private static final class TestEntity extends Entity {
        private TestEntity(Level level) {
            super(EntityType.ITEM, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
