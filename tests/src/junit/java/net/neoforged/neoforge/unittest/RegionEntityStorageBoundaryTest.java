/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapFrame;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the server-owned entity storage boundary on real patched entry points.
 * <p>
 * Every rejection runs on an actual native worker thread of the engine's worker pool. The tests use
 * latches as hang detectors only; no test relies on a sleep or on a stress repetition.
 * <p>
 * The teleport, removal and hanging-position tests drive the real patched vanilla methods on mock
 * instances that run the real method bodies ({@code CALLS_REAL_METHODS}). Only the state those methods
 * read or write before the guard is installed reflectively, so the assertions are about the real
 * production code path rather than about a test-only entity.
 */
@Timeout(20)
class RegionEntityStorageBoundaryTest {
    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
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
    void nativeWorkerScopeIsClearedOnTheSameWorkerThread() {
        this.installSingleWorkerEngine();
        final AtomicBoolean markedInside = new AtomicBoolean();

        this.runOnNativeWorker(() -> markedInside.set(RegionThreadingHooks.isNativeRegionWorkerThread()));
        assertTrue(markedInside.get(), "pool work must run inside the native worker origin scope");

        // The probe bypasses RegionWorkerPool.execute, so it does not re-enter the scope. It runs on the
        // pool's single worker thread after the marked task (including its finally) finished, which is the
        // only place where a missing RegionWorkerScope.exit() would still be observable.
        assertFalse(this.markerOnUnwrappedWorkerThread(), "the origin scope must be cleared when a pool task returns normally");
    }

    @Test
    void nativeWorkerScopeIsClearedWhenPoolWorkFails() {
        this.installSingleWorkerEngine();
        final AtomicReference<Boolean> markedWhileDying = new AtomicReference<>();
        final CountDownLatch died = new CountDownLatch(1);

        assertTrue(this.workerPool().execute("failing worker scope task", () -> {
            // A task that throws makes the pool replace its worker thread, so the scope state of that
            // thread can only be observed from the thread itself while it terminates. The handler runs
            // after the pool wrapper's finally and after the executor's worker exit path.
            Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
                markedWhileDying.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                died.countDown();
            });
            throw new IllegalStateException("worker scope probe failure");
        }), "the worker pool must accept the failing probe task");

        await(died);
        assertEquals(Boolean.FALSE, markedWhileDying.get(), "the origin scope must be cleared when a pool task throws");
        assertFalse(RegionThreadingHooks.isNativeRegionWorkerThread(), "the origin scope must never mark the test thread");
    }

    @Test
    void workerMarkerSurvivesManagerRemovalAndClearsOnExit() {
        this.installSingleWorkerEngine();
        final AtomicBoolean markedAfterRemoval = new AtomicBoolean();

        this.runOnNativeWorker(() -> {
            assertThrows(IllegalStateException.class, RegionThreadingManager::clear, "a worker cannot clear the manager normally");
            assertNull(RegionThreadingManager.currentOrNull(), "the manager must be uninstalled");
            markedAfterRemoval.set(RegionThreadingHooks.isNativeRegionWorkerThread());
        });

        assertTrue(markedAfterRemoval.get(), "the origin scope must not depend on the installed manager");
        assertFalse(this.markerOnUnwrappedWorkerThread(), "the origin scope must still be cleared after the manager was removed");
    }

    @Test
    void nativeWorkerPositionMutationRejectsBeforeChangingState() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);
        final Vec3 before = entity.position();
        final AABB beforeBounds = entity.getBoundingBox();
        final BlockPos beforeBlock = entity.blockPosition();
        final ChunkPos beforeChunk = entity.chunkPosition();

        assertRejected("Entity.setPosRaw", () -> entity.setPosRaw(2.0, 65.0, 2.0));
        assertPositionUnchanged(entity, before, beforeBounds, beforeBlock, beforeChunk);

        assertRejected("Entity.move", () -> entity.move(MoverType.SELF, new Vec3(1.0, 0.0, 0.0)));
        assertPositionUnchanged(entity, before, beforeBounds, beforeBlock, beforeChunk);

        assertRejected("Entity.absMoveTo", () -> entity.absMoveTo(8.0, 70.0, 8.0));
        assertPositionUnchanged(entity, before, beforeBounds, beforeBlock, beforeChunk);
        assertEquals(0.0, entity.xo, "a rejected absMoveTo must not write the previous position");
        assertEquals(0.0, entity.yo);
        assertEquals(0.0, entity.zo);

        assertRejected("Entity.moveTo", () -> entity.moveTo(9.0, 71.0, 9.0, 0.0F, 0.0F));
        assertPositionUnchanged(entity, before, beforeBounds, beforeBlock, beforeChunk);
        assertEquals(0.0, entity.xo, "a rejected moveTo must not write the previous position");
        assertFalse(entity.isPassenger(), "a rejected move must not change the riding state");

        assertRejected("Entity.teleportTo", () -> entity.teleportTo(level, 10.0, 72.0, 10.0, Set.of(), 0.0F, 0.0F));
        assertPositionUnchanged(entity, before, beforeBounds, beforeBlock, beforeChunk);
    }

    @Test
    void nativeWorkerPlayerTeleportRejectsBeforePendingTeleportAndRidingState() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final ServerPlayer player = mock(ServerPlayer.class, CALLS_REAL_METHODS);
        initializeMockEntity(player, level);
        final TestEntity vehicle = new TestEntity(level);
        setField(Entity.class, player, "vehicle", vehicle);
        setField(Entity.class, vehicle, "passengers", ImmutableList.of(player));

        final ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
        connection.player = player;
        setField(ServerGamePacketListenerImpl.class, connection, "awaitingTeleport", 7);
        setField(ServerGamePacketListenerImpl.class, connection, "awaitingTeleportTime", 99);
        final Vec3 pending = new Vec3(1.0, 2.0, 3.0);
        setField(ServerGamePacketListenerImpl.class, connection, "awaitingPositionFromClient", pending);
        player.connection = connection;

        assertRejected("ServerGamePacketListenerImpl.teleport",
                () -> connection.teleport(5.0, 70.0, 5.0, 0.0F, 0.0F, Set.of()));
        assertEquals(7, (int) getField(ServerGamePacketListenerImpl.class, connection, "awaitingTeleport"),
                "a rejected connection teleport must not advance the pending teleport id");
        assertEquals(99, (int) getField(ServerGamePacketListenerImpl.class, connection, "awaitingTeleportTime"),
                "a rejected connection teleport must not record a pending teleport time");
        assertSame(pending, getField(ServerGamePacketListenerImpl.class, connection, "awaitingPositionFromClient"),
                "a rejected connection teleport must not record a pending position");
        verify(player, never()).absMoveTo(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());

        assertRejected("ServerPlayer.teleportTo", () -> player.teleportTo(5.0, 70.0, 5.0));
        assertRejected("ServerPlayer.teleportRelative", () -> player.teleportRelative(1.0, 2.0, 3.0));
        verify(connection, times(1)).teleport(anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat(), anySet());

        assertRejected("ServerPlayer.teleportTo", () -> player.teleportTo(level, 5.0, 70.0, 5.0, Set.of(), 0.0F, 0.0F));
        verify(level, never()).getChunkSource();
        verify(player, never()).stopSleepInBed(anyBoolean(), anyBoolean());
        verify(player, never()).setYHeadRot(anyFloat());

        assertRejected("ServerPlayer.teleportTo", () -> player.teleportTo(level, 5.0, 70.0, 5.0, 0.0F, 0.0F));
        verify(player, never()).setCamera(any());

        assertRejected("ServerPlayer.dismountTo", () -> player.dismountTo(5.0, 70.0, 5.0));
        verify(player, never()).removeVehicle();

        assertSame(vehicle, player.getVehicle(), "a rejected player teleport must not stop the player riding");
        assertTrue(player.isPassenger());
        assertTrue(vehicle.getPassengers().contains(player), "the vehicle relationship must stay unchanged");
    }

    @Test
    void nativeWorkerRemovalRejectsBeforeChangingState() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final TestEntity entity = new TestEntity(level);
        entity.setPos(1.0, 64.0, 1.0);

        assertRejected("Entity.setRemoved", () -> entity.setRemoved(Entity.RemovalReason.DISCARDED));
        assertNull(entity.getRemovalReason(), "a rejected removal must not change the removal reason");
        assertFalse(entity.isRemoved());

        assertRejected("Entity.discard", entity::discard);
        assertNull(entity.getRemovalReason(), "a rejected discard must not change the removal reason");

        assertRejected("Entity.remove", () -> entity.remove(Entity.RemovalReason.KILLED));
        assertNull(entity.getRemovalReason(), "a rejected remove must not change the removal reason");
    }

    @Test
    void nativeWorkerLivingRemovalRejectsBeforeRemovalEffectsAndEffectClear() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final LivingEntity living = mock(LivingEntity.class, CALLS_REAL_METHODS);
        initializeMockEntity(living, level);
        setField(LivingEntity.class, living, "brain", mock(Brain.class));
        final TestEntity passenger = new TestEntity(level);
        setField(Entity.class, living, "passengers", ImmutableList.of(passenger));

        final AtomicInteger removalCallbacks = new AtomicInteger();
        final MobEffect countingEffect = new MobEffect(MobEffectCategory.NEUTRAL, 0) {
            @Override
            public void onMobRemoved(LivingEntity entity, int amplifier, Entity.RemovalReason reason) {
                removalCallbacks.incrementAndGet();
            }
        };
        final Holder<MobEffect> holder = Holder.direct(countingEffect);
        final Map<Holder<MobEffect>, MobEffectInstance> effects = new HashMap<>();
        effects.put(holder, new MobEffectInstance(holder));
        setField(LivingEntity.class, living, "activeEffects", effects);

        assertTrue(manager.addNewEntityWithoutEvent(living), "the server thread must be able to register the entity");

        assertRejected("Entity.discard", living::discard);
        assertEquals(1, living.getActiveEffects().size(), "a rejected discard must keep the active effects");
        assertEquals(0, removalCallbacks.get(), "a rejected discard must not run a removal effect callback");
        assertNull(living.getRemovalReason());
        assertEquals(1, living.getPassengers().size(), "a rejected discard must keep the passengers");
        assertTrue(manager.isLoaded(living.getUUID()), "a rejected discard must keep the manager membership");

        assertRejected("LivingEntity.remove", () -> living.remove(Entity.RemovalReason.DISCARDED));
        assertEquals(1, living.getActiveEffects().size(), "a rejected remove must keep the active effects");
        assertEquals(0, removalCallbacks.get(), "a rejected remove must not run a removal effect callback");
        assertNull(living.getRemovalReason());
        assertTrue(manager.isLoaded(living.getUUID()));

        // The same implementation still removes normally on the server thread.
        living.discard();
        assertEquals(Entity.RemovalReason.DISCARDED, living.getRemovalReason());
        assertEquals(1, removalCallbacks.get(), "a server-thread removal must run the effect callback exactly once");
        assertTrue(living.getActiveEffects().isEmpty(), "a server-thread removal must clear the active effects");
    }

    @Test
    void nativeWorkerSlimeRemovalRejectsBeforeSplitPreparation() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final Slime slime = mock(Slime.class, CALLS_REAL_METHODS);
        initializeMockEntity(slime, level);

        assertRejected("Slime.remove", () -> slime.remove(Entity.RemovalReason.KILLED));
        assertNull(slime.getRemovalReason(), "a rejected slime removal must not change the removal reason");
        assertFalse(slime.isRemoved());
        verifyNoInteractions(level);
    }

    @Test
    void nativeWorkerHangingPositionRejectsBeforeAnchorWrite() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final ItemFrame frame = mock(ItemFrame.class, CALLS_REAL_METHODS);
        initializeMockEntity(frame, level);
        final BlockPos anchor = new BlockPos(4, 65, 4);
        setField(net.minecraft.world.entity.decoration.BlockAttachedEntity.class, frame, "pos", anchor);
        final Vec3 position = new Vec3(4.5, 65.5, 4.5);
        final AABB bounds = new AABB(4.0, 65.0, 4.0, 5.0, 66.0, 5.0);
        final ChunkPos chunkPos = new ChunkPos(0, 4);
        setField(Entity.class, frame, "position", position);
        setField(Entity.class, frame, "bb", bounds);
        setField(Entity.class, frame, "blockPosition", anchor);
        setField(Entity.class, frame, "chunkPosition", chunkPos);

        assertRejected("BlockAttachedEntity.setPos", () -> frame.setPos(20.0, 70.0, 20.0));

        assertEquals(anchor, frame.getPos(), "a rejected hanging move must not change the attachment anchor");
        assertEquals(position, frame.position(), "a rejected hanging move must not change the position");
        assertEquals(bounds, frame.getBoundingBox(), "a rejected hanging move must not change the bounds");
        assertEquals(anchor, frame.blockPosition());
        assertEquals(chunkPos, frame.chunkPosition(), "a rejected hanging move must not change the section membership");
    }

    @Test
    void nativeWorkerHangingMoveRejectsBeforeKillAndFrameCleanup() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final BlockPos anchor = new BlockPos(4, 65, 4);
        final ItemStack displayed = new ItemStack(Items.PAPER);
        final ItemFrame frame = hangingFrame(level, anchor, displayed);
        displayed.setEntityRepresentation(frame);
        final UUID uuid = frame.getUUID();
        assertTrue(manager.addNewEntityWithoutEvent(frame), "the server thread must be able to register the frame");

        assertRejected("BlockAttachedEntity.move", () -> frame.move(MoverType.SELF, new Vec3(1.0, 0.0, 0.0)));

        assertSame(frame, displayed.getEntityRepresentation(), "a rejected hanging move must keep the item-frame association");
        assertNull(frame.getRemovalReason(), "a rejected hanging move must not kill the frame");
        assertFalse(frame.isRemoved());
        assertTrue(manager.isLoaded(uuid), "a rejected hanging move must keep the manager membership");
        assertEquals(anchor, frame.getPos(), "a rejected hanging move must not move the attachment anchor");
    }

    @Test
    void nativeWorkerHangingMoveKeepsRealFramedMapMarkersAndDecorations() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final BlockPos anchor = new BlockPos(4, 65, 4);
        final int frameId = 21;
        final ItemStack filledMap = new ItemStack(Items.FILLED_MAP);
        filledMap.set(DataComponents.MAP_ID, new MapId(7));
        final MapItemSavedData mapData = MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, true, false, Level.OVERWORLD);
        // A real frame marker and its matching frame decoration, exactly what removedFromFrame clears.
        final MapFrame marker = new MapFrame(anchor, 0, frameId);
        mapFrameMarkers(mapData).put(marker.getId(), marker);
        mapData.addDecoration(MapDecorationTypes.FRAME, null, "frame-" + frameId, anchor.getX(), anchor.getZ(), 0.0, null);
        doReturn(mapData).when(level).getMapData(any());
        final ItemFrame frame = hangingFrame(level, anchor, filledMap);
        doReturn(frameId).when(frame).getId();
        filledMap.setEntityRepresentation(frame);
        final UUID uuid = frame.getUUID();
        assertTrue(manager.addNewEntityWithoutEvent(frame));
        assertTrue(mapFrameMarkers(mapData).containsKey(marker.getId()), "the fixture must contain the frame marker");
        assertTrue(mapDecorations(mapData).containsKey("frame-" + frameId), "the fixture must contain the frame decoration");

        assertRejected("BlockAttachedEntity.move", () -> frame.move(MoverType.SELF, new Vec3(1.0, 0.0, 0.0)));

        assertSame(frame, filledMap.getEntityRepresentation(), "a rejected hanging move must keep the framed map representation");
        assertTrue(mapFrameMarkers(mapData).containsKey(marker.getId()), "a rejected hanging move must keep the map frame marker");
        assertTrue(mapDecorations(mapData).containsKey("frame-" + frameId), "a rejected hanging move must keep the map frame decoration");
        assertNull(frame.getRemovalReason());
        assertTrue(manager.isLoaded(uuid));

        // The same fixture state is what the vanilla frame cleanup removes, so the assertions above are
        // about the rejected path and not about a marker that no code path would ever clear.
        mapData.removedFromFrame(anchor, frameId);
        assertFalse(mapFrameMarkers(mapData).containsKey(marker.getId()));
        assertFalse(mapDecorations(mapData).containsKey("frame-" + frameId));
    }

    @Test
    void nativeWorkerServerLevelAdmissionRejectsBeforeRegistration() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final TestEntity entity = new TestEntity(level);

        assertRejected("ServerLevel.addEntity", () -> level.addFreshEntity(entity));
        assertFalse(entity.isAddedToLevel(), "a rejected admission must not mark the entity as added");

        assertRejected("ServerLevel.tryAddFreshEntityWithPassengers", () -> level.tryAddFreshEntityWithPassengers(entity));
        assertFalse(entity.isAddedToLevel());

        final ServerPlayer player = mock(ServerPlayer.class);
        assertRejected("ServerLevel.addPlayer", () -> level.addNewPlayer(player));
    }

    @Test
    void persistentManagerRejectsWorkerAdmissionBeforeUuidRegistration() {
        this.installEngine();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(mockServerLevel());
        entity.setPos(1.0, 64.0, 1.0);
        final UUID uuid = entity.getUUID();

        assertRejected("PersistentEntitySectionManager.addNewEntityWithoutEvent", () -> manager.addNewEntityWithoutEvent(entity));
        assertFalse(manager.isLoaded(uuid), "a rejected admission must not register the UUID");
        assertEquals(0, manager.count());
    }

    @Test
    void persistentManagerRejectsWorkerTickAndSaveBeforeMutatingSharedState() {
        this.installEngine();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();

        assertRejected("PersistentEntitySectionManager.processPendingLoads", manager::tick);
        assertRejected("PersistentEntitySectionManager.saveAll", manager::saveAll);
    }

    @Test
    void persistentManagerRejectsWorkerReadsOfItsOwnState() {
        this.installEngine();
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(mockServerLevel());
        entity.setPos(1.0, 64.0, 1.0);
        assertTrue(manager.addNewEntityWithoutEvent(entity));
        final UUID uuid = entity.getUUID();
        // Captured on the server thread, before the worker starts: the guard has to apply to the
        // operations of the getter, not only to the accessor that returned it.
        final LevelEntityGetter<Entity> getter = manager.getEntityGetter();
        final List<Entity> callerList = new ArrayList<>();
        final AtomicBoolean consumerRan = new AtomicBoolean();

        assertRejected("PersistentEntitySectionManager.isLoaded", () -> manager.isLoaded(uuid));
        assertRejected("PersistentEntitySectionManager.areEntitiesLoaded", () -> manager.areEntitiesLoaded(0L));
        assertRejected("PersistentEntitySectionManager.canPositionTick", () -> manager.canPositionTick(BlockPos.ZERO));
        assertRejected("PersistentEntitySectionManager.canPositionTick", () -> manager.canPositionTick(new ChunkPos(0, 0)));
        assertRejected("PersistentEntitySectionManager.gatherStats", () -> manager.gatherStats());
        assertRejected("PersistentEntitySectionManager.count", () -> manager.count());
        assertRejected("PersistentEntitySectionManager.dumpSections", () -> {
            try {
                manager.dumpSections(new StringWriter());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        assertRejected("PersistentEntitySectionManager.getEntityGetter", () -> manager.getEntityGetter());

        assertRejected("PersistentEntitySectionManager.getEntityGetter().get(int)", () -> getter.get(42));
        assertRejected("PersistentEntitySectionManager.getEntityGetter().get(UUID)", () -> getter.get(uuid));
        assertRejected("PersistentEntitySectionManager.getEntityGetter().getAll", getter::getAll);
        assertRejected("PersistentEntitySectionManager.getEntityGetter().get(AABB, Consumer)",
                () -> getter.get(new AABB(0, 0, 0, 1, 1, 1), candidate -> consumerRan.set(true)));
        assertRejected("PersistentEntitySectionManager.getEntityGetter().get(EntityTypeTest, AbortableIterationConsumer)",
                () -> getter.get(EntityTypeTest.forClass(Entity.class),
                        ignored -> net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE));
        assertRejected("PersistentEntitySectionManager.getEntityGetter().get(EntityTypeTest, AABB, AbortableIterationConsumer)",
                () -> getter.get(EntityTypeTest.forClass(Entity.class), new AABB(0, 0, 0, 1, 1, 1),
                        ignored -> net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE));

        assertFalse(consumerRan.get(), "a rejected read must not run its consumer");
        assertTrue(callerList.isEmpty());
        // The server thread keeps the vanilla behavior for the same instance and the same getter.
        assertTrue(manager.isLoaded(uuid));
        assertFalse(getter.getAll().iterator().hasNext(), "the server thread must run the same getter inline");
        assertEquals(0, manager.count());
    }

    @Test
    void serverObtainedGetAllIterableRejectsNativeWorkerIterationBeforeEntityRead() {
        this.installEngine();
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any(ChunkPos.class))).thenAnswer(invocation -> CompletableFuture.completedFuture(
                new ChunkEntities<>((ChunkPos) invocation.getArgument(0), List.of())));
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager(storage);
        final TestEntity entity = new TestEntity(mockServerLevel());
        entity.setPos(1.0, 64.0, 1.0);
        manager.updateChunkStatus(new ChunkPos(0, 0), Visibility.TICKING);
        assertTrue(manager.addNewEntityWithoutEvent(entity), "the server thread must be able to register the entity");

        // A live view captured on the server thread is exactly what a mod can hand to a worker.
        final LevelEntityGetter<Entity> getter = manager.getEntityGetter();
        final Iterable<Entity> view = getter.getAll();
        final List<Entity> serverView = new ArrayList<>();
        view.forEach(serverView::add);
        assertEquals(List.of(entity), serverView, "the server thread must iterate the live view inline");

        // Only iterator() runs on the worker: the rejection has to happen there, before the live storage
        // iterator is obtained and before any entity is read.
        final IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> this.runOnNativeWorker(view::iterator));
        assertTrue(rejected.getMessage().contains("PersistentEntitySectionManager.getEntityGetter().getAll"),
                "the rejection must name the getter operation, got: " + rejected.getMessage());

        // The rejected worker iteration must not disturb the server-owned storage or the live view.
        // A mod can also capture the iterator itself on the server thread and advance it on a worker later,
        // so the rejection must be repeated for every element operation, not only when the view is obtained.
        final Iterator<Entity> serverIterator = view.iterator();
        assertTrue(serverIterator.hasNext(), "the server thread must run the wrapper iterator inline");
        assertThrows(IllegalStateException.class, () -> this.runOnNativeWorker(serverIterator::hasNext),
                "a worker must not advance a server-obtained iterator");
        assertThrows(IllegalStateException.class, () -> this.runOnNativeWorker(serverIterator::next),
                "a worker must not read an entity through a server-obtained iterator");
        assertEquals(entity, serverIterator.next(), "a rejected advance must not consume the delegate iterator");
        assertFalse(serverIterator.hasNext(), "the delegate iterator must be exhausted exactly like vanilla");

        // The wrapper must stay a live view, not a snapshot: an entity admitted after the view was captured
        // has to be visible through the same iterable.
        final TestEntity later = new TestEntity(mockServerLevel());
        later.setPos(2.0, 64.0, 2.0);
        assertTrue(manager.addNewEntityWithoutEvent(later), "the server thread must admit a later entity");
        serverView.clear();
        view.forEach(serverView::add);
        assertEquals(2, serverView.size(), "the server thread must keep iterating the same live view");
        assertTrue(serverView.containsAll(List.of(entity, later)), "the live view must expose the later entity");
        assertTrue(manager.isLoaded(entity.getUUID()));
        assertTrue(manager.isLoaded(later.getUUID()));
        assertEquals(2, manager.count());
    }

    @Test
    void genericManagerKeepsTheVanillaLiveIterableWithoutAnyGuard() {
        this.installEngine();
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any(ChunkPos.class))).thenAnswer(invocation -> CompletableFuture.completedFuture(
                new ChunkEntities<>((ChunkPos) invocation.getArgument(0), List.of())));
        // The 3-argument constructor keeps the no-op check, so a generic manager must not gain a guard.
        final PersistentEntitySectionManager<Entity> manager = new PersistentEntitySectionManager<>(Entity.class,
                (LevelCallback) mock(LevelCallback.class), storage);
        final TestEntity entity = new TestEntity(mockServerLevel());
        entity.setPos(1.0, 64.0, 1.0);
        manager.updateChunkStatus(new ChunkPos(0, 0), Visibility.TICKING);
        assertTrue(manager.addNewEntityWithoutEvent(entity));

        final Iterable<Entity> view = manager.getEntityGetter().getAll();
        final AtomicReference<List<Entity>> workerView = new AtomicReference<>();
        this.runOnNativeWorker(() -> {
            final List<Entity> seen = new ArrayList<>();
            view.forEach(seen::add);
            workerView.set(seen);
        });
        assertEquals(List.of(entity), workerView.get(),
                "a no-op manager must keep the vanilla live iterable for any thread");
    }

    @Test
    void publicServerLevelStatusAccessRejectsNativeWorker() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        attachEntityManager(level);
        setField(ServerLevel.class, level, "players", new ArrayList<ServerPlayer>());
        setField(ServerLevel.class, level, "chunkSource", mock(net.minecraft.server.level.ServerChunkCache.class));

        assertRejected("PersistentEntitySectionManager.areEntitiesLoaded", () -> level.areEntitiesLoaded(0L));
        assertRejected("PersistentEntitySectionManager.canPositionTick", () -> level.isNaturalSpawningAllowed(BlockPos.ZERO));
        assertRejected("PersistentEntitySectionManager.canPositionTick", () -> level.isNaturalSpawningAllowed(new ChunkPos(0, 0)));
        assertRejected("PersistentEntitySectionManager.canPositionTick", () -> level.isPositionEntityTicking(BlockPos.ZERO));
        assertRejected("PersistentEntitySectionManager.gatherStats", () -> level.getWatchdogStats());
        assertRejected("PersistentEntitySectionManager.gatherStats", () -> level.gatherChunkSourceStats());
    }

    @Test
    void persistentManagerAdmitsOnTheServerThread() {
        final PersistentEntitySectionManager<Entity> manager = newGuardedManager();
        final TestEntity entity = new TestEntity(mockServerLevel());
        entity.setPos(1.0, 64.0, 1.0);

        assertTrue(manager.addNewEntityWithoutEvent(entity));
        assertTrue(manager.isLoaded(entity.getUUID()));
        // The entity is loaded but not visible/tracked in a hidden section, so count() stays 0 exactly like
        // vanilla; isLoaded() is the UUID admission set this test cares about.
        assertEquals(0, manager.count());
        manager.tick();
        assertTrue(manager.isLoaded(entity.getUUID()), "a server-thread tick must not remove the entity");
    }

    @Test
    void configuredScheduledSpawnRunsInlineAndReturnsActualResults() {
        // The requested scheduled worker slice stays gated, so the callback runs synchronously on the
        // server thread and addFreshEntity returns the real result instead of a provisional one.
        this.engine = new RegionThreadingEngine(2, false, false, false, false, true, false, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mockServerLevel();
        attachEntityManager(level);
        final LevelTicks<Block> ticks = new LevelTicks<>(chunk -> true, () -> net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        ticks.addContainer(new ChunkPos(0, 0), new LevelChunkTicks<>());
        final Block type = mock(Block.class);
        ticks.schedule(new ScheduledTick<>(type, new BlockPos(1, 64, 1), 10L, 0L));
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<Boolean> results = new CopyOnWriteArrayList<>();
        final AtomicInteger joinEvents = new AtomicInteger();
        final Consumer<EntityJoinLevelEvent> recorder = event -> {
            joinEvents.incrementAndGet();
            event.getEntity().addTag("region-join-event");
        };

        final LevelTicks.TickDispatcher<Block> dispatcher = RegionThreadingHooks.scheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, condition -> {
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        });
        assertNull(dispatcher, "the production scheduled slice must stay on the vanilla inline path");

        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, recorder);
        try {
            ticks.tick(10L, 65536, (pos, tickType) -> {
                threads.add(Thread.currentThread().getName());
                final TestEntity accepted = new TestEntity(level);
                results.add(level.addFreshEntity(accepted));
                // A dependent action of the join event must already be visible when the call returns.
                assertTrue(accepted.getTags().contains("region-join-event"), "the join event must run inline before addFreshEntity returns");
                final TestEntity duplicate = new TestEntity(level);
                results.add(level.addFreshEntity(duplicate));
                results.add(level.addFreshEntity(duplicate));
                final TestEntity removed = new TestEntity(level);
                removed.setRemoved(Entity.RemovalReason.DISCARDED);
                results.add(level.addFreshEntity(removed));
                assertFalse(removed.getTags().contains("region-join-event"), "an already removed entity must not reach the join event");
                final Consumer<EntityJoinLevelEvent> cancel = event -> event.setCanceled(true);
                NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, cancel);
                try {
                    results.add(level.addFreshEntity(new TestEntity(level)));
                } finally {
                    NeoForge.EVENT_BUS.unregister(cancel);
                }
            }, dispatcher);
        } finally {
            NeoForge.EVENT_BUS.unregister(recorder);
        }

        assertEquals(List.of(Thread.currentThread().getName()), threads);
        assertEquals(List.of(true, true, false, false, false), results);
        // One event for the accepted entity, one for the first duplicate attempt, one for the repeated
        // duplicate and one for the cancelled join. The already-removed entity is rejected before the event.
        assertEquals(4, joinEvents.get(), "the join event must fire inline exactly once per admitted attempt");
        assertTrue(this.engine.diagnostics().scheduledTickWorkerExecutionEnabled());
        assertFalse(this.engine.diagnostics().scheduledTickWorkerDispatchEffective());
        assertEquals(0L, this.engine.diagnostics().scheduledTicksOnWorkers());
    }

    @Test
    void legacyEntityGetterRejectsNativeWorkerBeforePredicateOrResult() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final Entity entity = mock(Entity.class);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        final RegionAwareEntityGetter getter = new RegionAwareEntityGetter(level, delegate);
        final AtomicBoolean predicateRan = new AtomicBoolean();
        final Consumer<Entity> consumer = candidate -> predicateRan.set(true);

        assertRejected("RegionAwareEntityGetter.get(int)", () -> getter.get(42));
        assertRejected("RegionAwareEntityGetter.get(UUID)", () -> getter.get(UUID.randomUUID()));
        assertRejected("RegionAwareEntityGetter.getAll", getter::getAll);
        assertRejected("RegionAwareEntityGetter.get(AABB, Consumer)", () -> getter.get(new AABB(0, 0, 0, 1, 1, 1), consumer));
        assertRejected("RegionAwareEntityGetter.get(EntityTypeTest, AbortableIterationConsumer)", () -> getter.get(EntityTypeTest.forClass(Entity.class), ignored -> net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE));
        assertRejected("RegionAwareEntityGetter.get(EntityTypeTest, AABB, AbortableIterationConsumer)",
                () -> getter.get(EntityTypeTest.forClass(Entity.class), new AABB(0, 0, 0, 1, 1, 1),
                        ignored -> net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE));

        assertFalse(predicateRan.get(), "a rejected query must not run its consumer");
        verifyNoInteractions(delegate);
    }

    @Test
    void legacyLevelEntityQueryRejectsNativeWorkerBeforePredicate() {
        this.installEngine();
        final ServerLevel level = mockServerLevel();
        final AtomicBoolean predicateRan = new AtomicBoolean();
        final Predicate<Entity> predicate = candidate -> {
            predicateRan.set(true);
            return true;
        };
        final List<Entity> callerList = new ArrayList<>();

        assertRejected("Level.getEntities(Entity, AABB, Predicate)", () -> level.getEntities((Entity) null, new AABB(0, 0, 0, 1, 1, 1), predicate));
        assertRejected("Level.getEntities(EntityTypeTest, AABB, Predicate, List, int)",
                () -> level.getEntities(EntityTypeTest.forClass(Entity.class), new AABB(0, 0, 0, 1, 1, 1), predicate, callerList, 10));
        assertFalse(predicateRan.get(), "a rejected query must not run its predicate");
        assertTrue(callerList.isEmpty(), "a rejected query must not fill the caller list");
    }

    @Test
    void legacyEntityGetterRunsInlineOnTheServerThread() {
        final ServerLevel level = mockServerLevel();
        final Entity entity = mock(Entity.class);
        final LevelEntityGetter<Entity> delegate = mock(LevelEntityGetter.class);
        when(delegate.get(42)).thenReturn(entity);

        assertSame(entity, new RegionAwareEntityGetter(level, delegate).get(42));
        verify(delegate).get(42);
    }

    private void installEngine() {
        this.engine = new RegionThreadingEngine(2, false, false, false, false, false, false, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
    }

    private void installSingleWorkerEngine() {
        this.engine = new RegionThreadingEngine(1, false, false, false, false, false, false, RegionPolicy.FIXED_3X3);
        RegionThreadingManager.install(this.engine);
    }

    private void assertRejected(String operation, Runnable action) {
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> this.runOnNativeWorker(action));
        assertTrue(
                thrown.getMessage().contains(operation),
                "expected the operation '" + operation + "' in the rejection, got: " + thrown.getMessage());
    }

    private static void assertPositionUnchanged(Entity entity, Vec3 position, AABB bounds, BlockPos blockPos, ChunkPos chunkPos) {
        assertEquals(position, entity.position(), "a rejected position change must not move the entity");
        assertEquals(bounds, entity.getBoundingBox(), "a rejected position change must not resize the bounds");
        assertEquals(blockPos, entity.blockPosition());
        assertEquals(chunkPos, entity.chunkPosition(), "a rejected position change must not change the section membership");
    }

    /**
     * Runs {@code task} on a real worker thread of the engine's pool and rethrows its failure.
     */
    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("storage boundary test", () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        }), "the worker pool must accept the test task");
        await(done);
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

    /**
     * {@return true when the pool's own worker thread still reports the native worker origin}
     * <p>
     * The probe is submitted to the underlying executor directly, so it does not re-enter
     * {@link RegionWorkerScope} through {@code RegionWorkerPool.execute}. With a single worker thread it
     * runs on the same thread after the previously submitted pool task, including that task's
     * {@code finally}, which is exactly where a missing scope exit would leak.
     */
    private boolean markerOnUnwrappedWorkerThread() {
        final ExecutorService raw = this.rawWorkerExecutor();
        final AtomicBoolean marked = new AtomicBoolean(true);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        raw.execute(() -> {
            try {
                marked.set(RegionThreadingHooks.isNativeRegionWorkerThread());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        await(done);
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return marked.get();
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

    private ExecutorService rawWorkerExecutor() {
        final RegionWorkerExecutor pool = this.workerPool();
        try {
            final Field field = pool.getClass().getDeclaredField("executor");
            field.setAccessible(true);
            return (ExecutorService) field.get(pool);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the worker task did not complete");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void attachEntityManager(ServerLevel level) {
        setField(ServerLevel.class, level, "entityManager", newGuardedManager());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager() {
        return newGuardedManager(mock(EntityPersistentStorage.class));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newGuardedManager(EntityPersistentStorage<Entity> storage) {
        return new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback) mock(LevelCallback.class),
                storage,
                RegionThreadingHooks::assertServerEntityStorageAccess);
    }

    private static ServerLevel mockServerLevel() {
        return mock(ServerLevel.class, CALLS_REAL_METHODS);
    }

    /**
     * {@return a live, non-fixed item frame mock running the real {@link ItemFrame} bodies} The anchor,
     * position, bounds and displayed item are the only state the real methods read before the guard.
     */
    private static ItemFrame hangingFrame(ServerLevel level, BlockPos anchor, ItemStack displayed) {
        final ItemFrame frame = mock(ItemFrame.class, CALLS_REAL_METHODS);
        initializeMockEntity(frame, level);
        setField(net.minecraft.world.entity.decoration.BlockAttachedEntity.class, frame, "pos", anchor);
        setField(Entity.class, frame, "position", new Vec3(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5));
        setField(Entity.class, frame, "bb", new AABB(anchor.getX(), anchor.getY(), anchor.getZ(), anchor.getX() + 1, anchor.getY() + 1, anchor.getZ() + 1));
        setField(Entity.class, frame, "blockPosition", anchor);
        setField(Entity.class, frame, "chunkPosition", new ChunkPos(anchor.getX() >> 4, anchor.getZ() >> 4));
        setField(Entity.class, frame, "id", 21);
        doReturn(displayed).when(frame).getItem();
        return frame;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MapFrame> mapFrameMarkers(MapItemSavedData mapData) {
        return (Map<String, MapFrame>) getField(MapItemSavedData.class, mapData, "frameMarkers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MapDecoration> mapDecorations(MapItemSavedData mapData) {
        return (Map<String, MapDecoration>) getField(MapItemSavedData.class, mapData, "decorations");
    }

    /**
     * Installs the entity state the real patched methods read before their guard. Mockito instances are
     * created without running field initializers, so the defaults have to be supplied explicitly.
     */
    private static void initializeMockEntity(Entity entity, ServerLevel level) {
        setField(Entity.class, entity, "level", level);
        setField(Entity.class, entity, "uuid", UUID.randomUUID());
        setField(Entity.class, entity, "levelCallback", mock(EntityInLevelCallback.class));
        setField(Entity.class, entity, "passengers", ImmutableList.of());
        setField(Entity.class, entity, "position", Vec3.ZERO);
        setField(Entity.class, entity, "bb", new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        setField(Entity.class, entity, "blockPosition", BlockPos.ZERO);
        setField(Entity.class, entity, "chunkPosition", new ChunkPos(0, 0));
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

    private static Object getField(Class<?> declaringClass, Object target, String name) {
        try {
            final Field field = declaringClass.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read " + declaringClass.getSimpleName() + "." + name, exception);
        }
    }

    /**
     * Minimal server entity used by the mutation tests. It is constructed on the test thread; the tests
     * only invoke the guarded entry points from the worker.
     */
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
