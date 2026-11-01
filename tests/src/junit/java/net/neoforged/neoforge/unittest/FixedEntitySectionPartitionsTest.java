/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
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
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class FixedEntitySectionPartitionsTest {
    private static EntitySectionStorage<TestEntity> storage(RegionPolicy policy) {
        return new EntitySectionStorage<>(TestEntity.class, chunk -> Visibility.TICKING, policy);
    }

    @Test
    void physicalBucketsOwnIndependentMapsAndTreesAndRetire() throws Exception {
        var fixed = storage(RegionPolicy.FIXED_3X3);
        assertNull(field(fixed, "sections"));
        assertNull(field(fixed, "sectionIds"));
        long[] ids = { SectionPos.asLong(-4, 0, -4), SectionPos.asLong(-3, 0, -3), SectionPos.asLong(-1, 17, -1), SectionPos.asLong(0, -7, 0), SectionPos.asLong(2, 8, 2), SectionPos.asLong(3, 0, 3) };
        for (long id : ids) {
            assertSame(fixed.getOrCreateSection(id), fixed.getOrCreateSection(id));
        }
        Map<Long, Object> buckets = buckets(fixed);
        assertEquals(4, buckets.size());
        assertEquals(2, ((Map<?, ?>) field(buckets.get(ChunkPos.asLong(-1, -1)), "sections")).size());
        assertEquals(2, ((LongSortedSet) field(buckets.get(ChunkPos.asLong(0, 0)), "sectionIds")).size());
        var maps = new java.util.IdentityHashMap<>();
        var trees = new java.util.IdentityHashMap<>();
        for (Object bucket : buckets.values()) {
            maps.put(field(bucket, "sections"), true);
            trees.put(field(bucket, "sectionIds"), true);
        }
        assertEquals(4, maps.size());
        assertEquals(4, trees.size());
        for (long id : ids) {
            fixed.remove(id);
            assertNull(fixed.getSection(id));
        }
        assertEquals(0, fixed.count());
        assertTrue(buckets(fixed).isEmpty());
        assertTrue(partitionRows(field(fixed, "partitions")).isEmpty());
        assertTrue(fixed.getAllChunksWithExistingSections().isEmpty());
        fixed.getOrCreateSection(ids[0]);
        assertEquals(1, buckets(fixed).size());
    }

    @Test
    void operationsMatchLegacyAtNegativeBoundariesAndPackedCoordinateEdges() {
        var legacy = storage(RegionPolicy.DYNAMIC);
        var fixed = storage(RegionPolicy.FIXED_3X3);
        int[] horizontal = { -2097152, -2097151, -4, -3, -1, 0, 2, 3, 2097150, 2097151 };
        int[] vertical = { -524288, -17, -1, 0, 1, 524287 };
        List<Long> keys = new ArrayList<>();
        for (int x : horizontal) {
            for (int z : horizontal) {
                for (int y : vertical) {
                    long key = SectionPos.asLong(x, y, z);
                    keys.add(key);
                    legacy.getOrCreateSection(key);
                    fixed.getOrCreateSection(key);
                    assertSame(fixed.getSection(key), fixed.getOrCreateSection(key));
                }
                long chunk = ChunkPos.asLong(x, z);
                // Vanilla's final signed-long range overflows for x=max,z=-1; preserve its exception.
                if (x == 2097151 && z == -1) {
                    assertThrows(IllegalArgumentException.class, () -> legacy.getExistingSectionPositionsInChunk(chunk));
                    assertThrows(IllegalArgumentException.class, () -> fixed.getExistingSectionPositionsInChunk(chunk));
                } else {
                    assertArrayEquals(legacy.getExistingSectionPositionsInChunk(chunk).toArray(), fixed.getExistingSectionPositionsInChunk(chunk).toArray());
                    assertEquals(legacy.getExistingSectionsInChunk(chunk).count(), fixed.getExistingSectionsInChunk(chunk).count());
                }
            }
        }
        assertEquals(legacy.count(), fixed.count());
        assertEquals(legacy.getAllChunksWithExistingSections(), fixed.getAllChunksWithExistingSections());
        for (long key : keys) {
            legacy.remove(key);
            fixed.remove(key);
            assertEquals(legacy.count(), fixed.count());
            fixed.remove(key);
        }
        assertTrue(fixed.getAllChunksWithExistingSections().isEmpty());
        for (long key : keys) {
            assertNotNull(fixed.getOrCreateSection(key));
        }
        assertEquals(keys.size(), fixed.count());
    }

    @Test
    void queriesPreserveVanillaOrderVisibilityTypedCacheAndEveryAbortPrefix() {
        var legacy = storage(RegionPolicy.DYNAMIC);
        var fixed = storage(RegionPolicy.FIXED_3X3);
        int id = 0;
        for (int x : new int[] { 3, -4, 0, -1, 2, -3 }) {
            for (int z : new int[] { -1, 3, -3, 0 }) {
                for (int y : new int[] { -1, 4, 0 }) {
                    long key = SectionPos.asLong(x, y, z);
                    for (int n = 0; n < 2; n++) {
                        TestEntity entity = n == 0 ? new TypedEntity(++id, new BlockPos(x * 16 + 1, y * 16 + 1, z * 16 + 1)) : new TestEntity(++id, new BlockPos(x * 16 + 2, y * 16 + 2, z * 16 + 2));
                        legacy.getOrCreateSection(key).add(entity);
                        fixed.getOrCreateSection(key).add(entity);
                    }
                    Visibility visibility = z == 3 ? Visibility.HIDDEN : x == -3 ? Visibility.TRACKED : Visibility.TICKING;
                    legacy.getSection(key).updateChunkStatus(visibility);
                    fixed.getSection(key).updateChunkStatus(visibility);
                }
            }
        }
        AABB bounds = new AABB(-100, -100, -100, 100, 100, 100);
        List<Integer> expected = query(legacy, bounds, Integer.MAX_VALUE, false);
        assertFalse(expected.isEmpty());
        assertEquals(expected.size(), expected.stream().distinct().count());
        assertEquals(expected, query(fixed, bounds, Integer.MAX_VALUE, false));
        for (boolean typed : new boolean[] { false, true }) {
            List<Integer> all = query(legacy, bounds, Integer.MAX_VALUE, typed);
            for (int stop = 1; stop <= all.size(); stop++) {
                assertEquals(all.subList(0, stop), query(fixed, bounds, stop, typed));
            }
        }
        // The first typed query above creates the real ClassInstanceMultiMap lazy cache. Subsequent
        // additions/removals must continue to update it on the calling server thread.
        long key = SectionPos.asLong(0, 0, 0);
        TypedEntity added = new TypedEntity(++id, BlockPos.ZERO);
        legacy.getSection(key).add(added);
        fixed.getSection(key).add(added);
        assertEquals(query(legacy, bounds, 999, true), query(fixed, bounds, 999, true));
        assertTrue(query(fixed, bounds, 999, true).contains(id));
        legacy.getSection(key).remove(added);
        fixed.getSection(key).remove(added);
        assertEquals(query(legacy, bounds, 999, true), query(fixed, bounds, 999, true));
        for (AABB narrow : List.of(new AABB(-1, -1, -1, 1, 1, 1), new AABB(46, 63, -2, 50, 67, 2), new AABB(-50, -20, -50, -46, 2, -46))) {
            assertEquals(query(legacy, narrow, 999, false), query(fixed, narrow, 999, false));
        }
    }

    @Test
    void expandedQueryBoundsAndSignedLongOverflowMatchLegacy() {
        for (int x : new int[] { -2097152, -4, -1, 0, 3, 2097150, 2097151 }) {
            var legacy = storage(RegionPolicy.DYNAMIC);
            var fixed = storage(RegionPolicy.FIXED_3X3);
            TestEntity oversized = new TestEntity(1, new BlockPos(x * 16 + 15, -16, -16));
            oversized.bounds = new AABB(x * 16 + 15, -16, -16, x * 16 + 19, 4, 4);
            long key = SectionPos.asLong(oversized.pos);
            legacy.getOrCreateSection(key).add(oversized);
            fixed.getOrCreateSection(key).add(oversized);
            AABB bounds = new AABB(x * 16 + 16, 1, 1, x * 16 + 17, 2, 2);
            if (x >= 2097150) {
                assertThrows(IllegalArgumentException.class, () -> query(legacy, bounds, 99, false));
                assertThrows(IllegalArgumentException.class, () -> query(fixed, bounds, 99, false));
            } else {
                assertEquals(query(legacy, bounds, 99, false), query(fixed, bounds, 99, false));
            }
        }
    }

    @Test
    void deferredChunkStreamDoesNotRetainRetiredBucketAndResolvesRecreatedSection() throws Exception {
        var fixed = storage(RegionPolicy.FIXED_3X3);
        long key = SectionPos.asLong(-3, -1, -3);
        EntitySection<TestEntity> old = fixed.getOrCreateSection(key);
        Stream<EntitySection<TestEntity>> stream = fixed.getExistingSectionsInChunk(ChunkPos.asLong(-3, -3));
        Object bucket = buckets(fixed).values().iterator().next();
        fixed.remove(key);
        assertTrue(buckets(fixed).isEmpty());
        assertTrue(((Map<?, ?>) field(bucket, "sections")).isEmpty());
        assertTrue(((LongSortedSet) field(bucket, "sectionIds")).isEmpty());
        EntitySection<TestEntity> replacement = fixed.getOrCreateSection(key);
        assertNotSame(old, replacement);
        assertEquals(List.of(replacement), stream.toList());
    }

    @Test
    void sectionFactoriesCannotPublishNullOrDuplicateSections() throws Exception {
        var partitions = new FixedEntitySectionPartitions<TestEntity>();
        long key = SectionPos.asLong(-3, 0, -3);
        assertThrows(NullPointerException.class, () -> partitions.getOrCreate(key, ignored -> null));
        assertNull(partitions.get(key));
        assertEquals(0, partitions.count());
        assertTrue(((Long2ObjectMap<?>) field(partitions, "partitionRows")).isEmpty());

        EntitySection<TestEntity> published = new EntitySection<>(TestEntity.class, Visibility.TICKING);
        assertSame(published, partitions.getOrCreate(key, ignored -> {
            // Re-entering for the same id publishes the first section; the outer call must reuse it
            // instead of replacing the mapping or adding a second tree entry.
            assertSame(published, partitions.getOrCreate(key, inner -> published));
            return new EntitySection<>(TestEntity.class, Visibility.TICKING);
        }));
        assertSame(published, partitions.get(key));
        assertSame(published, partitions.getOrCreate(key, ignored -> new EntitySection<>(TestEntity.class, Visibility.TICKING)));
        assertEquals(1, partitions.count());
        assertEquals(java.util.Set.of(key), partitions.sectionIdsInRange(key, key + 1L));
        assertEquals(1, partitionRows(partitions).size());
        partitions.remove(key);
        assertTrue(partitionRows(partitions).isEmpty());
    }

    @Test
    void rangeLookupUsesRegionXRowsAndRetiresEmptyRows() throws Exception {
        var partitions = new FixedEntitySectionPartitions<TestEntity>();
        int[] xs = { -9, -8, -7, -1, 0, 1, 7, 8 };
        int[] zs = { -4, 0, 4 };
        for (int x : xs) {
            for (int z : zs) {
                long key = SectionPos.asLong(x, 0, z);
                partitions.getOrCreate(key, id -> new EntitySection<>(TestEntity.class, Visibility.TICKING));
            }
        }
        Long2ObjectMap<?> rows = partitionRows(partitions);
        // Chunk x -9/-8/-7 map to region X -3, x -1 to -1, x 0/1 to 0 and x 7/8 to 2.
        assertEquals(4, rows.size());
        for (Object row : rows.values()) {
            assertEquals(3, ((Long2ObjectMap<?>) row).size());
        }

        // A chunk column stays inside one bucket. The AABB path instead asks for one whole x slab,
        // which must cover every region-Z bucket of that single region-X row in packed ID order.
        long key = SectionPos.asLong(-7, 0, -4);
        assertEquals(java.util.Set.of(key), partitions.sectionIdsInRange(key, key + 1L));
        LongSortedSet slab = expectedSections(-7);
        assertEquals(slab, partitions.sectionIdsInRange(SectionPos.asLong(-7, 0, 0), SectionPos.asLong(-7, -1, -1) + 1L));

        // A range that spans region rows includes those rows only, never the distant ones.
        LongSortedSet spanning = expectedSections(-1);
        spanning.addAll(expectedSections(0));
        assertEquals(spanning, partitions.sectionIdsInRange(SectionPos.asLong(-1, 0, 0), SectionPos.asLong(0, -1, -1) + 1L));

        // Removing the last section of a region-X row retires the row; removing one column from a
        // row that still has sections keeps the row and the other columns.
        for (int x : new int[] { -9, -8, -7 }) {
            for (int z : zs) {
                partitions.remove(SectionPos.asLong(x, 0, z));
            }
        }
        assertEquals(3, rows.size());
        assertNull(rows.get(-3L));
        partitions.remove(SectionPos.asLong(0, 0, -4));
        assertEquals(3, ((Long2ObjectMap<?>) rows.get(0L)).size());
        for (int z : new int[] { 0, 4 }) {
            partitions.remove(SectionPos.asLong(0, 0, z));
        }
        assertEquals(3, ((Long2ObjectMap<?>) rows.get(0L)).size());
        for (int z : zs) {
            partitions.remove(SectionPos.asLong(1, 0, z));
        }
        assertNull(rows.get(0L));
        assertEquals(2, rows.size());
        for (int x : xs) {
            for (int z : zs) {
                partitions.remove(SectionPos.asLong(x, 0, z));
            }
        }
        assertTrue(rows.isEmpty());
        assertEquals(0, partitions.count());
        assertTrue(partitions.chunks().isEmpty());
        assertTrue(partitions.sectionIdsInRange(SectionPos.asLong(-7, 0, 0), SectionPos.asLong(-7, -1, -1) + 1L).isEmpty());
        partitions.getOrCreate(SectionPos.asLong(-7, 0, -4), id -> new EntitySection<>(TestEntity.class, Visibility.TICKING));
        assertEquals(1, rows.size());
        assertEquals(1, ((Long2ObjectMap<?>) rows.get(-3L)).size());
    }

    @Test
    void persistentManagerMovementLoadSaveUnloadAndCallbacksMatchLegacy() throws Exception {
        Scenario legacy = scenario(RegionPolicy.DYNAMIC);
        Scenario fixed = scenario(RegionPolicy.FIXED_3X3);
        assertEquals(legacy, fixed);
        assertTrue(fixed.events.contains("section:1"));
        assertTrue(fixed.events.contains("trackingEnd:1"));
        assertTrue(fixed.events.contains("destroyed:2"));
    }

    private static Scenario scenario(RegionPolicy policy) throws Exception {
        List<String> events = new ArrayList<>();
        Fixture fixture = new Fixture();
        var manager = new PersistentEntitySectionManager<>(TestEntity.class, callbacks(events), fixture, RegionThreadingHooks::assertServerEntityStorageAccess, policy);
        EntitySectionStorage<TestEntity> sections = managerStorage(manager);
        ChunkPos source = new ChunkPos(-1, -1);
        ChunkPos target = new ChunkPos(0, 0);
        manager.updateChunkStatus(source, Visibility.TICKING);
        manager.updateChunkStatus(target, Visibility.TRACKED);
        assertFalse(manager.areEntitiesLoaded(source.toLong()));
        TestEntity loaded = new TestEntity(2, new BlockPos(-1, 80, -1));
        fixture.complete(source, List.of(loaded));
        fixture.complete(target, List.of());
        manager.tick();
        assertTrue(manager.areEntitiesLoaded(source.toLong()));
        TestEntity moving = new TestEntity(1, new BlockPos(-1, 64, -1));
        assertTrue(manager.addNewEntity(moving));
        assertFalse(manager.addNewEntity(moving), "duplicate UUID remains rejected");
        assertEquals(2, sections.count());
        moving.move(new BlockPos(1, 64, 1));
        assertNull(sections.getSection(SectionPos.asLong(-1, 4, -1)));
        assertEquals(List.of(moving), sections.getSection(SectionPos.asLong(0, 4, 0)).getEntities().toList());
        assertSame(moving, manager.getEntityGetter().get(moving.uuid));
        manager.updateChunkStatus(target, Visibility.TICKING);
        moving.move(new BlockPos(1, 81, 1));
        moving.move(new BlockPos(49, 81, 1)); // enter a hidden partition
        assertNull(manager.getEntityGetter().get(moving.uuid));
        moving.move(new BlockPos(1, 81, 1));
        assertSame(moving, manager.getEntityGetter().get(moving.uuid));
        List<Integer> query = new ArrayList<>();
        manager.getEntityGetter().get(new AABB(-100, 0, -100, 100, 200, 100), entity -> query.add(entity.id));
        assertEquals(List.of(2, 1), query);
        manager.autoSave();
        List<String> saved = new ArrayList<>(fixture.stored);
        manager.updateChunkStatus(source, Visibility.HIDDEN);
        manager.updateChunkStatus(target, Visibility.HIDDEN);
        manager.tick();
        assertEquals(0, sections.count());
        assertEquals(0, manager.count());
        assertFalse(manager.isLoaded(moving.uuid));
        assertFalse(manager.isLoaded(loaded.uuid));
        if (policy == RegionPolicy.FIXED_3X3) assertTrue(buckets(sections).isEmpty());
        manager.updateChunkStatus(target, Visibility.TICKING);
        fixture.complete(target, List.of(moving));
        manager.tick();
        assertSame(moving, manager.getEntityGetter().get(moving.uuid));
        // Real removal callback clears the last section and UUID, allowing the same UUID to reload.
        moving.setRemoved(Entity.RemovalReason.DISCARDED);
        manager.updateChunkStatus(source, Visibility.TICKING);
        fixture.complete(source, List.of(loaded));
        manager.tick();
        loaded.setRemoved(Entity.RemovalReason.DISCARDED);
        manager.saveAll();
        manager.close();
        assertEquals(0, sections.count());
        if (policy == RegionPolicy.FIXED_3X3) assertTrue(buckets(sections).isEmpty());
        return new Scenario(events, saved, fixture.stored, query);
    }

    @Test
    void installedPolicyIsCapturedWithoutChangingGenericOrTransientStorage() throws Exception {
        RegionThreadingManager.clear();
        var early = new PersistentEntitySectionManager<>(TestEntity.class, callbacks(new ArrayList<>()), new Fixture(), RegionThreadingHooks::assertServerEntityStorageAccess, RegionThreadingHooks.entitySectionStoragePolicy());
        assertNull(field(managerStorage(early), "partitions"));
        for (RegionPolicy policy : RegionPolicy.values()) {
            var engine = new RegionThreadingEngine(1, false, false, false, false, policy);
            try {
                RegionThreadingManager.install(engine);
                assertEquals(policy, RegionThreadingHooks.entitySectionStoragePolicy());
                var server = new PersistentEntitySectionManager<>(TestEntity.class, callbacks(new ArrayList<>()), new Fixture(), RegionThreadingHooks::assertServerEntityStorageAccess, RegionThreadingHooks.entitySectionStoragePolicy());
                assertEquals(policy == RegionPolicy.FIXED_3X3, field(managerStorage(server), "partitions") != null);
                var generic = new PersistentEntitySectionManager<>(TestEntity.class, callbacks(new ArrayList<>()), new Fixture());
                assertNull(field(managerStorage(generic), "partitions"));
                var transientManager = new TransientEntitySectionManager<>(TestEntity.class, callbacks(new ArrayList<>()));
                assertNull(field(field(transientManager, "sectionStorage"), "partitions"));
                assertNull(field(managerStorage(early), "partitions"));
            } finally {
                RegionThreadingManager.clear();
            }
        }
    }

    @Test
    void nativeWorkerCannotAccessPartitionsOrManagerEvenAfterBridgeRemoval() throws Exception {
        var engine = new RegionThreadingEngine(1, true, false, false, false, RegionPolicy.FIXED_3X3);
        var manager = new PersistentEntitySectionManager<>(TestEntity.class, callbacks(new ArrayList<>()), new Fixture(), RegionThreadingHooks::assertServerEntityStorageAccess, RegionPolicy.FIXED_3X3);
        var sections = managerStorage(manager);
        sections.getOrCreateSection(0);
        var getter = manager.getEntityGetter();
        RegionThreadingManager.install(engine);
        try {
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            RegionWorkerExecutor workers = (RegionWorkerExecutor) field(engine, "workerPool");
            assertTrue(workers.execute("partition guard", () -> {
                try {
                    assertThrows(IllegalStateException.class, RegionThreadingManager::clear);
                    assertNull(RegionThreadingManager.currentOrNull());
                    assertThrows(IllegalStateException.class, () -> sections.getOrCreateSection(1));
                    assertThrows(IllegalStateException.class, () -> sections.getSection(0));
                    assertThrows(IllegalStateException.class, () -> sections.remove(0));
                    assertThrows(IllegalStateException.class, sections::count);
                    assertThrows(IllegalStateException.class, sections::getAllChunksWithExistingSections);
                    assertThrows(IllegalStateException.class, () -> sections.getExistingSectionsInChunk(0));
                    assertThrows(IllegalStateException.class, () -> query(sections, new AABB(-16, -16, -16, 16, 16, 16), 1, true));
                    assertThrows(IllegalStateException.class, manager::count);
                    assertThrows(IllegalStateException.class, getter::getAll);
                    assertThrows(IllegalStateException.class, () -> manager.addNewEntity(new TestEntity(3, BlockPos.ZERO)));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    finished.countDown();
                }
            }));
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(1, sections.count());
        } finally {
            RegionThreadingManager.clear();
            engine.shutdown();
        }
    }

    private static List<Integer> query(EntitySectionStorage<TestEntity> storage, AABB bounds, int stop, boolean typed) {
        List<Integer> ids = new ArrayList<>();
        if (typed) {
            storage.getEntities(EntityTypeTest.forClass(TypedEntity.class), bounds, entity -> {
                ids.add(entity.id);
                return ids.size() >= stop ? Continuation.ABORT : Continuation.CONTINUE;
            });
        } else {
            storage.getEntities(bounds, entity -> {
                ids.add(entity.id);
                return ids.size() >= stop ? Continuation.ABORT : Continuation.CONTINUE;
            });
        }
        return ids;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static LongSortedSet expectedSections(int chunkX) {
        LongSortedSet expected = new LongAVLTreeSet();
        for (int chunkZ : new int[] { -4, 0, 4 }) {
            expected.add(SectionPos.asLong(chunkX, 0, chunkZ));
        }
        return expected;
    }

    private static Map<Long, Object> buckets(EntitySectionStorage<?> storage) throws Exception {
        Long2ObjectMap<?> rows = partitionRows(field(storage, "partitions"));
        Map<Long, Object> buckets = new HashMap<>();
        for (var rowEntry : rows.long2ObjectEntrySet()) {
            Long2ObjectMap<?> row = (Long2ObjectMap<?>) rowEntry.getValue();
            for (var bucketEntry : row.long2ObjectEntrySet()) {
                buckets.put(ChunkPos.asLong((int) rowEntry.getLongKey(), (int) bucketEntry.getLongKey()), bucketEntry.getValue());
            }
        }
        return buckets;
    }

    private static Long2ObjectMap<?> partitionRows(Object partitions) throws Exception {
        return (Long2ObjectMap<?>) field(partitions, "partitionRows");
    }

    @SuppressWarnings("unchecked")
    private static EntitySectionStorage<TestEntity> managerStorage(PersistentEntitySectionManager<TestEntity> manager) throws Exception {
        return (EntitySectionStorage<TestEntity>) field(manager, "sectionStorage");
    }

    private static LevelCallback<TestEntity> callbacks(List<String> events) {
        return new LevelCallback<>() {
            public void onCreated(TestEntity entity) {
                events.add("created:" + entity.id);
            }

            public void onDestroyed(TestEntity entity) {
                events.add("destroyed:" + entity.id);
            }

            public void onTickingStart(TestEntity entity) {
                events.add("tickingStart:" + entity.id);
            }

            public void onTickingEnd(TestEntity entity) {
                events.add("tickingEnd:" + entity.id);
            }

            public void onTrackingStart(TestEntity entity) {
                events.add("trackingStart:" + entity.id);
            }

            public void onTrackingEnd(TestEntity entity) {
                events.add("trackingEnd:" + entity.id);
            }

            public void onSectionChange(TestEntity entity) {
                events.add("section:" + entity.id);
            }
        };
    }

    private record Scenario(List<String> events, List<String> firstSave, List<String> allStores, List<Integer> query) {}

    private static final class Fixture implements EntityPersistentStorage<TestEntity> {
        final Map<ChunkPos, CompletableFuture<ChunkEntities<TestEntity>>> pending = new HashMap<>();
        final List<String> stored = new ArrayList<>();

        public CompletableFuture<ChunkEntities<TestEntity>> loadEntities(ChunkPos pos) {
            CompletableFuture<ChunkEntities<TestEntity>> future = new CompletableFuture<>();
            assertNull(pending.put(pos, future));
            return future;
        }

        void complete(ChunkPos pos, List<TestEntity> entities) {
            assertTrue(pending.remove(pos).complete(new ChunkEntities<>(pos, entities)));
        }

        public void storeEntities(ChunkEntities<TestEntity> entities) {
            stored.add(entities.getPos() + ":" + entities.getEntities().map(entity -> entity.id).toList());
        }

        public void flush(boolean sync) {
            assertTrue(pending.isEmpty());
        }
    }

    private static class TestEntity implements EntityAccess {
        final int id;
        final UUID uuid;
        BlockPos pos;
        AABB bounds;
        EntityInLevelCallback callback = EntityInLevelCallback.NULL;

        TestEntity(int id, BlockPos pos) {
            this.id = id;
            this.uuid = new UUID(0, id);
            this.pos = pos;
            this.bounds = new AABB(pos);
        }

        void move(BlockPos pos) {
            this.pos = pos;
            this.bounds = new AABB(pos);
            this.callback.onMove();
        }

        public int getId() {
            return id;
        }

        public UUID getUUID() {
            return uuid;
        }

        public BlockPos blockPosition() {
            return pos;
        }

        public AABB getBoundingBox() {
            return bounds;
        }

        public void setLevelCallback(EntityInLevelCallback callback) {
            this.callback = callback;
        }

        public Stream<TestEntity> getSelfAndPassengers() {
            return Stream.of(this);
        }

        public Stream<TestEntity> getPassengersAndSelf() {
            return Stream.of(this);
        }

        public void setRemoved(Entity.RemovalReason reason) {
            callback.onRemove(reason);
        }

        public boolean shouldBeSaved() {
            return true;
        }

        public boolean isAlwaysTicking() {
            return false;
        }
    }

    private static final class TypedEntity extends TestEntity {
        TypedEntity(int id, BlockPos pos) {
            super(id, pos);
        }
    }
}
