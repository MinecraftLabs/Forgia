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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the region-scoped entity query of a native worker that owns a region entity tick lease.
 * <p>
 * The out-of-region contract is clamp and count: a worker sees only the entities of its leased region, a
 * query box that crosses the boundary is clamped to the lease, the truncated counter increments, and the
 * neighbouring region's physical bucket is never consulted. The level is a real section manager behind a
 * {@link org.mockito.Mockito#CALLS_REAL_METHODS} {@link ServerLevel} mock, so the query runs through the real
 * {@code Level.getEntities} entry points, the real region-scoped section accessor and the real per-region
 * partitions.
 */
@Timeout(30)
class RegionScopedEntityQueryTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final AABB INSIDE_BOX = new AABB(0.0, 60.0, 0.0, 20.0, 70.0, 20.0);
    private static final AABB CROSSING_BOX = new AABB(0.0, 60.0, 0.0, 70.0, 70.0, 20.0);

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void leasedWorkerQueryReturnsExactlyTheEntitiesOfItsRegion() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = new TestEntity(level);
        inside.setPos(5.0, 64.0, 5.0);
        final TestEntity neighbour = new TestEntity(level);
        neighbour.setPos(60.0, 64.0, 5.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 3, 0, neighbour);

        final List<Entity> result = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity self = new TestEntity(level, entity -> {
            try {
                result.addAll(level.getEntities(entity, INSIDE_BOX, candidate -> true));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        self.setPos(1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, self);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(self));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the leased query was rejected: " + failures);
        assertEquals(List.of(inside), result, "the worker must see exactly the entities stored in its region");
        assertEquals(1L, this.engine.diagnostics().entityQueriesAllowedByLease());
        assertEquals(0L, this.engine.diagnostics().entityQueriesTruncatedByLease());
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
        assertEquals(1L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void crossingQueryIsClampedCountedAndNeverReadsTheNeighbourBucket() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity inside = new TestEntity(level);
        inside.setPos(5.0, 64.0, 5.0);
        final TestEntity neighbour = new TestEntity(level);
        neighbour.setPos(60.0, 64.0, 5.0);
        addEntity(manager, 0, 0, inside);
        addEntity(manager, 3, 0, neighbour);

        final List<Entity> result = new CopyOnWriteArrayList<>();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity self = new TestEntity(level, entity -> {
            try {
                result.addAll(level.getEntities(entity, CROSSING_BOX, candidate -> true));
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        self.setPos(1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, self);
        final FixedEntitySectionPartitions<Entity> partitions = partitions(manager);
        final List<String> bucketAccesses = new CopyOnWriteArrayList<>();
        partitions.setBucketAccessObserverForTesting((regionX, regionZ) -> bucketAccesses.add(regionX + "," + regionZ));

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(self));
        plan.executeAndAwait();

        assertEquals(List.of(), failures, () -> "the clamped query was rejected: " + failures);
        assertEquals(List.of(inside), result, "the crossing query must not return the neighbouring entity");
        assertFalse(bucketAccesses.contains("1,0"), () -> "the neighbouring bucket was consulted: " + bucketAccesses);
        assertEquals(List.of("0,0"), bucketAccesses, "the query must read only the leased region's bucket");
        assertEquals(1L, this.engine.diagnostics().entityQueriesAllowedByLease());
        assertEquals(1L, this.engine.diagnostics().entityQueriesTruncatedByLease(),
                "a crossing query must be counted as truncated");
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void workerWithoutLeaseIsStillRejectedWithTheOperationName() {
        this.installEngine(1, false, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        final AtomicReference<Throwable> rejection = new AtomicReference<>();

        runOnNativeWorker(() -> rejection.set(assertThrows(IllegalStateException.class,
                () -> level.getEntities((Entity) null, INSIDE_BOX, candidate -> true))));

        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Level.getEntities(Entity, AABB, Predicate)"),
                () -> "the rejection must name the operation, got: " + rejection.get().getMessage());
        assertEquals(0L, this.engine.diagnostics().entityQueriesAllowedByLease());
        assertEquals(0L, this.engine.diagnostics().entityQueriesTruncatedByLease());
        assertEquals(1L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void leaseOnDynamicStorageKeepsTheLegacyRejection() {
        this.installEngine(1, true, RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.DYNAMIC);
        final ServerLevel level = mockLevel(manager);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final TestEntity self = new TestEntity(level, entity -> {
            try {
                level.getEntities(entity, INSIDE_BOX, candidate -> true);
            } catch (Throwable throwable) {
                failures.add(throwable);
            }
        });
        self.setPos(1.0, 64.0, 1.0);
        addEntity(manager, 0, 0, self);

        final RegionEntityTickPlan plan = this.plan(level);
        assertTrue(plan.accept(self));
        plan.executeAndAwait();

        assertEquals(1, failures.size(), () -> "a DYNAMIC lease must keep the legacy rejection: " + failures);
        assertTrue(failures.get(0).getMessage().contains("Level.getEntities(Entity, AABB, Predicate)"),
                () -> "the rejection must name the operation, got: " + failures.get(0).getMessage());
        assertEquals(0L, this.engine.diagnostics().entityQueriesAllowedByLease());
        assertEquals(0L, this.engine.diagnostics().entityQueriesTruncatedByLease());
        assertEquals(1L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void mainThreadOnlyStorageKeepsTheWorkerRejection() {
        this.installEngine(1, true, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        final ServerLevel level = mockLevel(newManager(RegionLayout.FIXED_3X3));
        assertNull(this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER),
                "MAIN_THREAD_ONLY storage must not start an entity worker batch");
        final AtomicReference<Throwable> rejection = new AtomicReference<>();

        runOnNativeWorker(() -> rejection.set(assertThrows(IllegalStateException.class,
                () -> level.getEntities((Entity) null, INSIDE_BOX, candidate -> true))));

        assertNotNull(rejection.get());
        assertTrue(rejection.get().getMessage().contains("Level.getEntities(Entity, AABB, Predicate)"));
        assertEquals(0L, this.engine.diagnostics().entityQueriesAllowedByLease());
        assertEquals(1L, this.engine.diagnostics().entityQueriesRejected());
    }

    @Test
    void serverThreadQueriesKeepTheVanillaResultsOrderingAndAbortSemantics() {
        this.installEngine(1, false, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        final PersistentEntitySectionManager<Entity> manager = newManager(RegionLayout.FIXED_3X3);
        final ServerLevel level = mockLevel(manager);
        final TestEntity first = new TestEntity(level);
        first.setPos(1.0, 64.0, 1.0);
        final TestEntity second = new TestEntity(level);
        second.setPos(20.0, 64.0, 20.0);
        final TestEntity third = new TestEntity(level);
        third.setPos(40.0, 64.0, 40.0);
        addEntity(manager, 0, 0, first);
        addEntity(manager, 1, 1, second);
        addEntity(manager, 2, 2, third);

        final List<Entity> vanilla = new ArrayList<>();
        manager.getEntityGetter().get(INSIDE_BOX, vanilla::add);
        final List<Entity> throughLevel = level.getEntities((Entity) null, INSIDE_BOX, candidate -> true);

        assertEquals(vanilla, throughLevel, "the server-thread result and its ordering must stay vanilla");
        final List<Entity> limited = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(TestEntity.class), INSIDE_BOX, candidate -> true, limited, 1);
        assertEquals(1, limited.size(), "the typed overload must keep the max abort");
        assertEquals(vanilla.get(0), limited.get(0));
        assertEquals(0L, this.engine.diagnostics().entityQueriesAllowedByLease(),
                "the server thread must never use the leased query path");
        assertEquals(0L, this.engine.diagnostics().entityQueriesTruncatedByLease());
        assertEquals(0L, this.engine.diagnostics().entityQueriesRejected());
    }

    private void installEngine(int workerThreads, boolean entityTickWorkers, RegionPolicy policy, RegionEntityStorageMode storageMode) {
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, false, false, entityTickWorkers,
                false, false, false, RegionLayout.of(policy), storageMode);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    private static ServerLevel mockLevel(PersistentEntitySectionManager<Entity> manager) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // The seed of an entity group random source is derived from the world seed, the game time and the
        // dimension of its level.
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        doReturn(List.of()).when(level).getPartEntities();
        setField(ServerLevel.class, level, "entityManager", manager);
        setField(ServerLevel.class, level, "regionAwareEntityGetter", new RegionAwareEntityGetter(level, manager.getEntityGetter()));
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        return level;
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

    @SuppressWarnings("unchecked")
    private static FixedEntitySectionPartitions<Entity> partitions(PersistentEntitySectionManager<Entity> manager) {
        final Object storage = getField(PersistentEntitySectionManager.class, manager, "sectionStorage");
        return (FixedEntitySectionPartitions<Entity>) getField(EntitySectionStorage.class, storage, "partitions");
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

    private void runOnNativeWorker(Runnable task) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(this.workerPool().execute("region-scoped query test", () -> {
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

    private static final class TestEntity extends Entity {
        private final Consumer<TestEntity> tickAction;

        private TestEntity(Level level) {
            this(level, entity -> {});
        }

        private TestEntity(Level level, Consumer<TestEntity> tickAction) {
            super(EntityType.ITEM, level);
            this.tickAction = tickAction;
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
}
