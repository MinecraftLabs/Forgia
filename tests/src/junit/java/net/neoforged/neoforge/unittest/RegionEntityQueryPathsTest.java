/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionEntityIdentitySnapshots;
import net.neoforged.neoforge.server.threading.region.RegionEntityQueryCounters;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPlayerSnapshots;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises every entity query entry point a native region worker can reach, and the per-tick player snapshot
 * that replaces the level's live player list for it.
 * <p>
 * Each operation is covered from both sides: a worker lease answers it from its own region or from the
 * published snapshot, and the same call on the server thread keeps the vanilla semantics. The refusal side is
 * covered too - a lookup of a foreign entity, a worker without a lease, a level whose region entity index is
 * not published - because a refusal has to name the operation and be counted, never be a silent empty answer.
 * <p>
 * The level is a real section manager behind a {@link org.mockito.Mockito#CALLS_REAL_METHODS} {@link ServerLevel}
 * mock, so every query runs through the real {@code Level}/{@code ServerLevel} entry points, the real
 * region-aware getter and the real per-region partitions.
 */
@Timeout(60)
class RegionEntityQueryPathsTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final AABB INSIDE_BOX = new AABB(0.0, 60.0, 0.0, 20.0, 70.0, 20.0);
    private static final AABB CROSSING_BOX = new AABB(0.0, 60.0, 0.0, 70.0, 70.0, 20.0);

    private RegionThreadingEngine engine;
    /** The level's entity tick list as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @BeforeEach
    void resetQueryCounters() {
        // The counters are process-wide, so every test starts from zero instead of from whatever the test that
        // happened to run before it left behind. Without this, a test that asserts an absolute count passes or
        // fails depending on the order the runner picked.
        RegionEntityQueryCounters.reset();
    }

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
        this.tickList.clear();
    }

    @Test
    void getAllOnAWorkerAnswersFromItsRegionAndCountsTruncation() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity neighbour = this.entity(level, 60.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 3, 0, neighbour);
        addEntity(manager, 0, 0, self);
        this.tickList.add(inside);
        this.tickList.add(neighbour);
        this.tickList.add(self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                for (final Entity candidate : level.getAllEntities()) {
                    seen.add(candidate);
                }
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the region-scoped getAll was rejected: " + failures);
        assertEquals(List.of(inside, self), seen, "the worker must see exactly the entities indexed in its region");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.TRUNCATED),
                "a region that holds fewer entities than the level must be counted as truncated");
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void getAllOnAWorkerOfACompleteRegionIsNotTruncated() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 0, 0, self);
        this.tickList.add(inside);
        this.tickList.add(self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            for (final Entity candidate : level.getAllEntities()) {
                seen.add(candidate);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(inside, self), seen);
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.TRUNCATED),
                "a region that holds every entity of the level is the whole answer");
    }

    @Test
    void getAllOnTheServerThreadKeepsVanillaSemantics() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity first = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity second = this.entity(level, 60.0, 64.0, 5.0);
        addEntity(manager, 0, 0, first);
        addEntity(manager, 3, 0, second);

        final List<Entity> vanilla = new ArrayList<>();
        manager.getEntityGetter().getAll().forEach(vanilla::add);
        final List<Entity> throughLevel = new ArrayList<>();
        level.getAllEntities().forEach(throughLevel::add);

        assertEquals(vanilla, throughLevel, "the server thread keeps the vanilla view of the whole level");
        assertEquals(2, throughLevel.size());
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.LOCAL),
                "the server thread never uses the region-scoped getAll");
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void serverThreadLookupsOfEveryShapeKeepVanillaSemantics() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity first = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity second = this.entity(level, 60.0, 64.0, 5.0);
        addEntity(manager, 0, 0, first);
        addEntity(manager, 3, 0, second);

        assertSame(first, level.getEntity(first.getId()), "the server thread resolves by id from the whole level");
        assertSame(second, level.getEntity(second.getUUID()), "the server thread resolves by uuid from the whole level");
        final List<Entity> typed = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(TestEntity.class), candidate -> true, typed);
        assertEquals(2, typed.size(), "the type-filtered lookup sees the whole level on the server thread");
        final List<Entity> all = new ArrayList<>();
        level.getAllEntities().forEach(all::add);
        assertEquals(2, all.size(), "getAll sees the whole level on the server thread");

        for (final RegionEntityQueryCounters.Operation operation : RegionEntityQueryCounters.Operation.values()) {
            assertEquals(0L, RegionEntityQueryCounters.count(operation, RegionEntityQueryCounters.Path.LOCAL),
                    () -> "the server thread must never use the region-scoped path: " + operation);
            assertEquals(0L, RegionEntityQueryCounters.count(operation, RegionEntityQueryCounters.Path.REFUSED),
                    () -> "the server thread is never refused: " + operation);
        }
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void getAllOnAWorkerWithoutALeaseAnswersEmptyAndCountsIt() {
        // A worker that holds no lease has no region to answer from, so the lookup answers the empty view vanilla
        // answers for a level without entities and counts the truncation. Throwing here used to turn an ordinary
        // "no entity is here" into a server crash.
        this.installEngine(1, false, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();

        this.runOnNativeWorker(() -> {
            try {
                level.getAllEntities().forEach(seen::add);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });

        assertTrue(failures.isEmpty(), () -> "a lookup must not throw on a worker, got: " + failures);
        assertTrue(seen.isEmpty(), "a worker without a lease has no region to answer from");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.TRUNCATED));
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected(),
                "an unanswered lookup is a truncation, not a rejected query");
    }

    @Test
    void getAllOnAWorkerOfALevelWithoutAPublishedIndexAnswersEmptyAndCountsIt() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                level.getAllEntities().forEach(seen::add);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        // No seed pass, so the level's region entity index is not a complete view: the answer is the empty view
        // and the truncation is counted, instead of a crash out of an ordinary query.
        this.runEntityTickWithoutSeeding(level, self);

        assertTrue(failures.isEmpty(), () -> "a lookup must not throw on a worker, got: " + failures);
        assertTrue(seen.isEmpty(), "an unpublished index cannot answer with entities");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_ALL, RegionEntityQueryCounters.Path.TRUNCATED));
    }

    @Test
    void byUuidLookupOnAWorkerAnswersFromItsRegion() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity owner = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, owner);
        addEntity(manager, 0, 0, self);
        this.tickList.add(owner);
        this.tickList.add(self);

        final AtomicReference<Entity> found = new AtomicReference<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                found.set(level.getEntity(owner.getUUID()));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the by-uuid lookup was rejected: " + failures);
        assertSame(owner, found.get(), "the worker must find the entity of its own region by UUID");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_UUID, RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void byUuidLookupOfAForeignEntityIsRefusedAndCounted() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity neighbour = this.entity(level, 60.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 3, 0, neighbour);
        addEntity(manager, 0, 0, self);
        this.tickList.add(neighbour);
        this.tickList.add(self);

        final AtomicReference<Entity> byUuid = new AtomicReference<>();
        final AtomicReference<Entity> byId = new AtomicReference<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                byUuid.set(level.getEntity(neighbour.getUUID()));
                byId.set(level.getEntity(neighbour.getId()));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertTrue(failures.isEmpty(), () -> "a cross-region lookup must not throw, got: " + failures);
        assertNull(byUuid.get(), "a foreign entity is not visible from this lease");
        assertNull(byId.get(), "a foreign entity is not visible from this lease");
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_UUID, RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_UUID, RegionEntityQueryCounters.Path.TRUNCATED),
                "a cross-region owner is a counted truncation, answered like an absent entity");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_ID, RegionEntityQueryCounters.Path.TRUNCATED));
    }

    /**
     * A refusal of a by-uuid or by-id lookup of another region's entity names the region that owns it, and the
     * lookup is counted separately from a genuine miss of an identity no region of the level holds.
     */
    @Test
    void byUuidLookupOfAForeignEntityNamesTheOwningRegion() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionEntityIdentitySnapshots.reset();
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        // Chunk 3 is region 1 of the fixed 3x3 layout; chunk 0 is region 0.
        final TestEntity neighbour = this.entity(level, 60.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 3, 0, neighbour);
        addEntity(manager, 0, 0, self);
        this.tickList.add(neighbour);
        this.tickList.add(self);

        final AtomicReference<Entity> byUuid = new AtomicReference<>();
        final AtomicReference<Entity> byId = new AtomicReference<>();
        final AtomicReference<Entity> miss = new AtomicReference<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                byUuid.set(level.getEntity(neighbour.getUUID()));
                byId.set(level.getEntity(neighbour.getId()));
                miss.set(level.getEntity(UUID.randomUUID()));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        // The head of the level tick publishes the ownership of every identity the regionizer knows.
        this.publishLevelTick(level);
        this.runEntityTickWithoutSeeding(level, self);

        assertEquals(2L, RegionEntityIdentitySnapshots.publishedEntries(),
                "the publication has to name every identity of the level");
        assertTrue(failures.isEmpty(), () -> "a lookup must not throw on a worker, got: " + failures);
        assertNull(byUuid.get(), "a foreign entity is not handed to another worker");
        assertNull(byId.get(), "a foreign entity is not handed to another worker");
        assertNull(miss.get(), "an identity no region holds is absent");
        assertEquals(2L, RegionEntityIdentitySnapshots.namedCrossRegionLookups(),
                "the cross-region lookups are named by the publication, the genuine miss is not");
        final RegionEntityIdentitySnapshots.Owner owner = RegionEntityIdentitySnapshots.ownerOf(level, neighbour.getUUID());
        assertNotNull(owner, "the publication must name the region that owns the entity, so the one-shot log can say it");
        assertEquals(1, owner.regionX());
        assertEquals(0, owner.regionZ());
    }

    @Test
    void typeFilteredLookupOnAWorkerAnswersFromItsRegionIndex() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity neighbour = this.entity(level, 60.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 3, 0, neighbour);
        addEntity(manager, 0, 0, self);
        this.tickList.add(inside);
        this.tickList.add(neighbour);
        this.tickList.add(self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                level.getEntities(EntityTypeTest.forClass(TestEntity.class), candidate -> true, seen);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the type-filtered lookup was rejected: " + failures);
        assertEquals(List.of(inside, self), seen, "the type-filtered lookup must see exactly the region's entities");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_TYPE, RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_TYPE, RegionEntityQueryCounters.Path.TRUNCATED));
    }

    @Test
    void workerReadsThePlayerSnapshotInsteadOfTheLiveList() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<ServerPlayer> live = livePlayers(level);
        final ServerPlayer first = player(level, 1.0, 64.0, 1.0);
        live.add(first);

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        final ServerPlayer second = player(level, 2.0, 64.0, 2.0);
        live.add(second);

        final AtomicReference<List<ServerPlayer>> seenByWorker = new AtomicReference<>();
        final AtomicReference<List<ServerPlayer>> seenByGetPlayers = new AtomicReference<>();
        final AtomicReference<Player> nearest = new AtomicReference<>();
        this.runOnNativeWorker(() -> {
            seenByWorker.set(level.players());
            seenByGetPlayers.set(level.getPlayers(candidate -> true));
            nearest.set(level.getNearestPlayer(0.0, 64.0, 0.0, -1.0, false));
        });

        assertEquals(List.of(first), seenByWorker.get(),
                "the worker must see the snapshot of this tick, not the player who joined after it was published");
        assertEquals(List.of(first), seenByGetPlayers.get(), "the level's player filter answers from the same snapshot");
        assertEquals(first, nearest.get(), "targeting reads the same snapshot, so it cannot target the new player yet");
        assertEquals(List.of(first, second), level.players(), "the server thread keeps the live list");
        assertEquals(3L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.PUBLISHED),
                "players(), getPlayers and getNearestPlayer are three published answers");
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.REFUSED));

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        final AtomicReference<List<ServerPlayer>> afterRepublish = new AtomicReference<>();
        this.runOnNativeWorker(() -> afterRepublish.set(level.players()));

        assertEquals(List.of(first, second), afterRepublish.get(), "the next tick publishes the new player as well");
        assertEquals(2L, this.engine.diagnostics().playerSnapshotsPublished(), "one publication per level tick");
    }

    @Test
    void playerSnapshotIsReplacedOncePerLevelTick() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<ServerPlayer> live = livePlayers(level);
        live.add(player(level, 1.0, 64.0, 1.0));

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        final RegionPlayerSnapshots.Snapshot firstTick = RegionPlayerSnapshots.of(level);
        assertTrue(firstTick != null, "the level tick publishes a player snapshot");

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        final RegionPlayerSnapshots.Snapshot secondTick = RegionPlayerSnapshots.of(level);

        assertNotSame(firstTick, secondTick, "every level tick replaces the snapshot instead of mutating it");
        assertEquals(1, firstTick.players().size(), "the previous snapshot keeps its own complete view");
        assertEquals(firstTick.entries(), secondTick.entries(), "the recorded keys and positions are republished");
        assertEquals(2L, this.engine.diagnostics().playerSnapshotsPublished());
    }

    @Test
    void playerSnapshotIsDetachedWithTheLevel() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<ServerPlayer> live = livePlayers(level);
        live.add(player(level, 1.0, 64.0, 1.0));

        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        assertTrue(RegionPlayerSnapshots.of(level) != null, "the snapshot is published before the level is cleared");

        this.engine.clearLevelRegionTasks(level);
        assertNull(RegionPlayerSnapshots.of(level), "a cleared level keeps no player snapshot alive");

        final AtomicReference<Throwable> refusal = new AtomicReference<>();
        this.runOnNativeWorker(() -> refusal.set(assertThrows(IllegalStateException.class, () -> level.players())));

        assertTrue(refusal.get().getMessage().contains("ServerLevel.players()"),
                () -> "the rejection must name the operation, got: " + refusal.get().getMessage());
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.REFUSED),
                "a worker without a snapshot is refused and counted instead of reading the live list");
        assertEquals(1, live.size(), "the live list of the server thread is untouched");
        assertEquals(live, level.players(), "the server thread keeps the live list");
    }

    @Test
    void levelWithoutTheEngineKeepsTheLivePlayerList() {
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<ServerPlayer> live = livePlayers(level);
        final ServerPlayer player = player(level, 1.0, 64.0, 1.0);
        live.add(player);

        assertNull(RegionThreadingHooks.playersOfLevel(level), "a level without an engine publishes no snapshot");
        assertEquals(live, level.players(), "the vanilla player list is returned unchanged");
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.PUBLISHED));
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.REFUSED),
                "an ordinary thread is not a refused published answer");
    }

    @Test
    void playerTypedBoxQueryOnAWorkerAddsThePlayerOfAnotherRegion() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final List<ServerPlayer> live = livePlayers(level);
        // The player stands beyond the leased region's 48-block edge, inside the crossing query box.
        live.add(player(level, 60.0, 64.0, 5.0));
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, self);
        this.tickList.add(self);
        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);

        final List<Player> players = new CopyOnWriteArrayList<>();
        final List<Entity> entities = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                players.addAll(level.getEntitiesOfClass(Player.class, CROSSING_BOX));
                entities.addAll(level.getEntities((Entity) null, CROSSING_BOX, candidate -> true));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the player box query was rejected: " + failures);
        assertEquals(List.of(live.get(0)), players, "the player of the neighbouring region is answered from the published snapshot");
        assertTrue(entities.contains(self), "the region bucket still answers the entities of the lease");
        assertFalse(entities.contains(live.get(0)), "a non-player query stays clamped to the leased region");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX,
                RegionEntityQueryCounters.Path.PUBLISHED), "the snapshot answered the player part of the query");
    }

    @Test
    void typedBoxConsumerOnAWorkerAnswersAPlayerOfTheLeaseOnce() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final List<ServerPlayer> live = livePlayers(level);
        // The first player stands inside the leased region, so the physical section bucket answers it. The
        // second one stands beyond the region edge and is only reachable through the published snapshot.
        final ServerPlayer local = player(level, 5.0, 64.0, 5.0);
        final ServerPlayer remote = player(level, 60.0, 64.0, 5.0);
        live.add(local);
        live.add(remote);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, local);
        addEntity(manager, 0, 0, self);
        this.tickList.add(self);
        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);

        final List<Player> answered = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                level.getEntities().get(EntityTypeTest.forClass(Player.class), CROSSING_BOX, candidate -> {
                    answered.add(candidate);
                    return AbortableIterationConsumer.Continuation.CONTINUE;
                });
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the typed box query was rejected: " + failures);
        assertEquals(1, answered.stream().filter(candidate -> candidate == local).count(),
                () -> "the player the region bucket already answered must not be added again by the union: " + answered);
        assertEquals(1, answered.stream().filter(candidate -> candidate == remote).count(),
                () -> "the player of the neighbouring region is answered once by the published snapshot: " + answered);
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX,
                RegionEntityQueryCounters.Path.TRUNCATED), "the box crossed the region edge and was clamped");
    }

    @Test
    void boxConsumerOnAWorkerAnswersTheClampedLease() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity outsideTheBox = this.entity(level, 5.0, 64.0, 30.0);
        final TestEntity neighbour = this.entity(level, 60.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 0, 1, outsideTheBox);
        addEntity(manager, 3, 0, neighbour);
        addEntity(manager, 0, 0, self);
        this.tickList.add(inside);
        this.tickList.add(outsideTheBox);
        this.tickList.add(neighbour);
        this.tickList.add(self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                level.getEntities().get(CROSSING_BOX, seen::add);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the box consumer query was rejected: " + failures);
        assertTrue(seen.contains(inside), "an entity of the lease inside the box is answered");
        assertTrue(seen.contains(self), "every entity of the lease inside the box is answered");
        assertFalse(seen.contains(outsideTheBox), "an entity outside the query box is not answered");
        assertFalse(seen.contains(neighbour), "the box is clamped to the lease, so a foreign region is not read");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_IN_BOX,
                RegionEntityQueryCounters.Path.LOCAL));
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_IN_BOX,
                RegionEntityQueryCounters.Path.TRUNCATED), "the box crossed the region edge and was clamped");
    }

    @Test
    void typedBoxQueryOnAWorkerStopsAtMaxWhileItAppendsThePlayerUnion() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final List<ServerPlayer> live = livePlayers(level);
        final ServerPlayer local = player(level, 5.0, 64.0, 5.0);
        live.add(local);
        live.add(player(level, 60.0, 64.0, 5.0));
        live.add(player(level, 62.0, 64.0, 5.0));
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, local);
        addEntity(manager, 0, 0, self);
        this.tickList.add(self);
        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);

        final List<Player> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                level.getEntities(EntityTypeTest.forClass(Player.class), CROSSING_BOX, candidate -> true, seen, 2);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the typed box query was rejected: " + failures);
        assertEquals(2, seen.size(), () -> "the max limit also bounds the players the snapshot appends: " + seen);
        assertSame(local, seen.get(0), "the region bucket answers first");
        assertSame(live.get(1), seen.get(1), "the union appends the published players until the limit is reached");
    }

    @Test
    void byIdLookupOfAnEntityWhoseRemovalIsDeferredIsRefused() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity owner = this.entity(level, 5.0, 64.0, 5.0);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, owner);
        addEntity(manager, 0, 0, self);
        this.tickList.add(owner);
        this.tickList.add(self);
        // The removal is deferred to the batch barrier, so the region index still holds the entity while the
        // vanilla index would already answer null for it.
        owner.setRemoved(Entity.RemovalReason.DISCARDED);

        final AtomicReference<Entity> byId = new AtomicReference<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                byId.set(level.getEntity(owner.getId()));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertTrue(failures.isEmpty(), () -> "a lookup must not throw on a worker, got: " + failures);
        assertNull(byId.get(), "a removed entity is answered like vanilla answers for an absent entity");
        assertEquals(0L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_ID, RegionEntityQueryCounters.Path.LOCAL),
                "a removed entity is not a local answer");
        assertEquals(1L, RegionEntityQueryCounters.count(RegionEntityQueryCounters.Operation.GET_BY_ID, RegionEntityQueryCounters.Path.TRUNCATED));
    }

    @Test
    void playerTypedBoxQueryUsesTheBoundingBoxOfTheSnapshot() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final List<ServerPlayer> live = livePlayers(level);
        final ServerPlayer player = player(level, 60.0, 64.0, 5.0);
        live.add(player);
        final TestEntity self = this.entity(level, 1.0, 64.0, 1.0);
        this.tickList.add(self);
        RegionThreadingHooks.beginLevelTick(level);
        RegionThreadingHooks.endLevelTick(level);
        // The box clips the body of the player but not its centre: vanilla intersects bounding boxes, so the
        // player belongs to the answer.
        final AABB clipping = new AABB(0.0, 64.9, 0.0, 70.0, 70.0, 20.0);

        final List<Player> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                seen.addAll(level.getEntitiesOfClass(Player.class, clipping));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the player box query was rejected: " + failures);
        assertEquals(List.of(player), seen, "the recorded bounding box of the player is intersected with the query box");
    }

    @Test
    void boxQueryOnAWorkerAppendsThePartsOfTheOwnersItAnswers() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final MultipartTestEntity multipart = new MultipartTestEntity(level);
        multipart.setPos(5.0, 64.0, 5.0);
        final PartEntity<?> part = multipart.parts[0];
        // The self entity stands outside the query box, so the answer is exactly the multipart entity and its part.
        final TestEntity self = this.entity(level, 5.0, 64.0, 30.0);
        addEntity(manager, 0, 0, multipart);
        addEntity(manager, 0, 1, self);
        this.tickList.add(multipart);
        this.tickList.add(self);

        final List<Entity> seen = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        self.tickAction = entity -> {
            try {
                seen.addAll(level.getEntities((Entity) null, INSIDE_BOX, candidate -> true));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        };

        this.runEntityTick(level, self);

        assertEquals(List.of(), failures, () -> "the box query was rejected: " + failures);
        assertEquals(List.of(multipart, part), seen, "vanilla appends the parts of the entities the box answered");
    }

    private void installEngine(int workerThreads, boolean entityTickWorkers, RegionPolicy policy, RegionEntityStorageMode storageMode) {
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, entityTickWorkers,
                false, false, false, RegionLayout.of(policy), storageMode);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    /** Publishes the entity ownership of {@code level}, exactly like the head of its level tick. */
    private void publishLevelTick(ServerLevel level) {
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);
    }

    /**
     * Seeds the level's region entity index and ticks {@code self} on a region worker.
     * <p>
     * The seed pass is the production entry point and marks the level's index as published; the per-entity plan
     * is the fallback mode of the entity slice and is the mechanics seam the lease tests use.
     */
    private void runEntityTick(ServerLevel level, Entity self) {
        this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        this.runEntityTickWithoutSeeding(level, self);
    }

    private void runEntityTickWithoutSeeding(ServerLevel level, Entity self) {
        final RegionEntityTickPlan plan = this.engine.beginPerEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertTrue(plan.accept(self), "the plan must own the tick of the test entity");
        plan.executeAndAwait();
    }

    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("entity query paths test", () -> {
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

    private ServerLevel mockLevel(PersistentEntitySectionManager<Entity> manager) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        doReturn(List.of()).when(level).getPartEntities();
        setField(ServerLevel.class, level, "entityManager", manager);
        setField(ServerLevel.class, level, "regionAwareEntityGetter", new RegionAwareEntityGetter(level, manager.getEntityGetter()));
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        // The seed pass of the level's region entity index reads the level's authoritative tick list.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        setField(ServerLevel.class, level, "players", new ArrayList<ServerPlayer>());
        return level;
    }

    /** {@return the live player list of the mock level, as the level's own field holds it} */
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> livePlayers(ServerLevel level) {
        return (List<ServerPlayer>) getField(ServerLevel.class, level, "players");
    }

    private ServerPlayer player(ServerLevel level, double x, double y, double z) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(player.getX()).thenReturn(x);
        when(player.getY()).thenReturn(y);
        when(player.getZ()).thenReturn(z);
        when(player.blockPosition()).thenReturn(BlockPos.containing(x, y, z));
        // A player is 0.6 x 1.8 blocks; the snapshot records the bounding box so a box query intersects it like
        // the vanilla section scan intersects the bounding box of an entity.
        when(player.getBoundingBox()).thenReturn(new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
        when(player.isRemoved()).thenReturn(false);
        return player;
    }

    private TestEntity entity(ServerLevel level, double x, double y, double z) {
        final TestEntity entity = new TestEntity(level);
        entity.setPos(x, y, z);
        return entity;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static PersistentEntitySectionManager<Entity> newManager(RegionLayout layout) {
        final EntityPersistentStorage<Entity> storage = mock(EntityPersistentStorage.class);
        when(storage.loadEntities(any(ChunkPos.class)))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(new ChunkEntities<>(invocation.getArgument(0), List.of())));
        return new PersistentEntitySectionManager<>(
                Entity.class,
                (LevelCallback) mock(LevelCallback.class),
                storage,
                operation -> {},
                layout);
    }

    private static void addEntity(PersistentEntitySectionManager<Entity> manager, int chunkX, int chunkZ, Entity entity) {
        manager.updateChunkStatus(new ChunkPos(chunkX, chunkZ), Visibility.TICKING);
        assertTrue(manager.addNewEntityWithoutEvent(entity), "the test entity must be admitted to the section manager");
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

    private static class TestEntity extends Entity {
        private volatile Consumer<TestEntity> tickAction = entity -> {};

        private TestEntity(Level level) {
            super(EntityType.ITEM, level);
        }

        @Override
        public void tick() {
            this.tickAction.accept(this);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }

    /**
     * A test entity that carries parts, so a box query has a multipart owner to append the parts of.
     */
    private static final class MultipartTestEntity extends TestEntity {
        private final PartEntity<?>[] parts;

        private MultipartTestEntity(Level level) {
            super(level);
            final TestPart part = new TestPart(this);
            part.setPos(5.0, 64.0, 5.0);
            this.parts = new PartEntity<?>[] { part };
        }

        @Override
        public boolean isMultipartEntity() {
            return true;
        }

        @Override
        public PartEntity<?>[] getParts() {
            return this.parts;
        }
    }

    private static final class TestPart extends PartEntity<MultipartTestEntity> {
        private TestPart(MultipartTestEntity parent) {
            super(parent);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
