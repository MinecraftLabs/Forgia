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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the per-region entity tick index: the index is the region's share of the level's entity tick list,
 * so it must always name exactly the entities the regionizer's authoritative mapping names, in the order the
 * level's own entity tick list has.
 * <p>
 * The regionizer and its region state are package private, so the test drives them through the same
 * reflection seam the other region threading tests use. That keeps the index seam out of the public API while
 * the test still compares the index against the authoritative mapping after every operation.
 */
@Timeout(60)
class RegionEntityTickIndexTest {
    private final ServerLevel level = mock(ServerLevel.class);
    private int nextEntityId = 1;

    @Test
    void indexMatchesTheRegionizerEntityMapUnderRandomEntityOperations() throws ReflectiveOperationException {
        final RegionLayout layout = RegionLayout.FIXED_3X3;
        final Object regionizer = newRegionizer(layout);
        // A 9x9 chunk island spans three fixed regions per axis, so the random walk crosses region boundaries
        // in both axes.
        for (int chunkX = 0; chunkX < 9; chunkX++) {
            for (int chunkZ = 0; chunkZ < 9; chunkZ++) {
                addChunk(regionizer, this.level, chunkX, chunkZ);
            }
        }
        final Random random = new Random(20261103L);
        final List<Entity> live = new ArrayList<>();
        final List<Boolean> ticking = new ArrayList<>();
        for (int operation = 0; operation < 600; operation++) {
            final int choice = random.nextInt(10);
            if (choice < 3 || live.isEmpty()) {
                final Entity entity = this.entity(this.position(random));
                addEntity(regionizer, layout, entity);
                live.add(entity);
                ticking.add(Boolean.FALSE);
            } else if (choice < 5) {
                final int index = random.nextInt(live.size());
                final Entity entity = live.get(index);
                this.moveTo(entity, this.position(random));
                addEntity(regionizer, layout, entity);
            } else if (choice < 6) {
                final int index = random.nextInt(live.size());
                final Entity entity = live.remove(index);
                ticking.remove(index);
                removeEntity(regionizer, entity);
            } else if (choice < 8) {
                final int index = random.nextInt(live.size());
                if (!ticking.get(index)) {
                    assertTrue(tickStarted(regionizer, this.level, live.get(index)), "a live entity must be indexable");
                    ticking.set(index, Boolean.TRUE);
                }
            } else {
                final int index = random.nextInt(live.size());
                if (ticking.get(index)) {
                    tickEnded(regionizer, this.level, live.get(index));
                    ticking.set(index, Boolean.FALSE);
                }
            }
            this.assertIndexMatchesMapping(regionizer, live, ticking);
        }
        assertTrue(entityCount(regionizer) > 0, "the random walk must leave entities behind");
    }

    @Test
    void tickListOrderIsPreservedAndReinsertionAppends() throws ReflectiveOperationException {
        final Object regionizer = newRegionizer(RegionLayout.FIXED_3X3);
        addChunk(regionizer, this.level, 0, 0);
        final Entity first = this.tickingEntity(regionizer, new BlockPos(1, 64, 1));
        final Entity second = this.tickingEntity(regionizer, new BlockPos(2, 64, 1));
        final Entity third = this.tickingEntity(regionizer, new BlockPos(3, 64, 1));

        assertEquals(List.of(first, second, third), tickingEntities(regionFor(regionizer, first)));

        // The vanilla tick list keeps the position of an entry it already contains ...
        assertTrue(tickStarted(regionizer, this.level, first));
        assertEquals(List.of(first, second, third), tickingEntities(regionFor(regionizer, first)));

        // ... and appends an entity that stopped ticking and starts again.
        tickEnded(regionizer, this.level, second);
        assertEquals(List.of(first, third), tickingEntities(regionFor(regionizer, first)));
        assertTrue(tickStarted(regionizer, this.level, second));
        assertEquals(List.of(first, third, second), tickingEntities(regionFor(regionizer, first)));
    }

    @Test
    void mergeKeepsTheTickOrderOfBothRegions() throws ReflectiveOperationException {
        final Object regionizer = newRegionizer(RegionLayout.DYNAMIC);
        addChunk(regionizer, this.level, 0, 0);
        final Entity first = this.entity(new BlockPos(1, 64, 1));
        final Entity second = this.entity(new BlockPos(2, 64, 1));
        assertTrue(tickStarted(regionizer, this.level, first));
        assertTrue(tickStarted(regionizer, this.level, second));
        // The second section is adjacent, so publishing it merges the two regions.
        addChunk(regionizer, this.level, 16, 0);
        final Entity third = this.entity(new BlockPos(16 * 16 + 1, 64, 1));
        assertTrue(tickStarted(regionizer, this.level, third));

        assertEquals(1, size(regionizer), "adjacent dynamic sections must merge into one region");
        final Object merged = regionFor(regionizer, first);
        assertNotNull(merged);
        assertSame(merged, regionFor(regionizer, third));
        assertEquals(List.of(first, second, third), tickingEntities(merged));
        assertEquals(3, entryCount(merged));
    }

    @Test
    void splitMigratesEveryEntryToItsNewRegion() throws ReflectiveOperationException {
        final Object regionizer = newRegionizer(RegionLayout.DYNAMIC);
        addChunk(regionizer, this.level, 0, 0);
        addChunk(regionizer, this.level, 16, 0);
        addChunk(regionizer, this.level, 32, 0);
        assertEquals(1, size(regionizer), "the three adjacent sections must start as one region");
        final Entity west = this.entity(new BlockPos(1, 64, 1));
        final Entity middle = this.entity(new BlockPos(16 * 16 + 1, 64, 1));
        final Entity east = this.entity(new BlockPos(32 * 16 + 1, 64, 1));
        for (final Entity entity : List.of(west, middle, east)) {
            assertTrue(tickStarted(regionizer, this.level, entity));
        }

        // Unload the middle section: the connected components are no longer adjacent, so the region splits.
        removeChunk(regionizer, this.level, 16, 0);

        final Object westRegion = regionFor(regionizer, west);
        final Object middleRegion = regionFor(regionizer, middle);
        final Object eastRegion = regionFor(regionizer, east);
        assertNotNull(westRegion);
        assertNotNull(middleRegion);
        assertNotNull(eastRegion);
        assertTrue(westRegion != middleRegion && middleRegion != eastRegion && westRegion != eastRegion,
                "the split must give every entity the region its own position names");
        for (final Entity entity : List.of(west, middle, east)) {
            final Object region = regionFor(regionizer, entity);
            final Object entry = entryOf(region, entity);
            assertNotNull(entry, "the split must keep the entity indexed");
            assertTrue(ticking(entry), "the split must keep the tick membership");
            assertEquals(1, entryCount(region), "the split must not leave a foreign entity behind");
            assertEquals(1, tickingCount(region));
        }
    }

    @Test
    void mappedButNotTickingEntityIsIndexedAndNotPartOfTheTick() throws ReflectiveOperationException {
        final RegionLayout layout = RegionLayout.FIXED_3X3;
        final Object regionizer = newRegionizer(layout);
        addChunk(regionizer, this.level, 0, 0);
        final Entity entity = this.entity(new BlockPos(1, 64, 1));
        addEntity(regionizer, layout, entity);

        final Object region = regionFor(regionizer, entity);
        assertNotNull(region);
        assertEquals(1, entryCount(region));
        assertEquals(0, tickingCount(region));
        for (final Object entry : entries(region)) {
            assertFalse(ticking(entry), "an entity that the level does not tick is not part of the tick");
        }
        assertTrue(activeEntityTickRegions(regionizer, this.level).isEmpty(), "a mapped entity that is not ticking activates no region");

        // The mapping keeps the region alive even without a loaded section, exactly like the mapping scan did.
        removeChunk(regionizer, this.level, 0, 0);
        assertNotNull(regionFor(regionizer, entity));
    }

    @Test
    void alwaysTickingEntityIsMarkedForTheServerThread() throws ReflectiveOperationException {
        final Object regionizer = newRegionizer(RegionLayout.FIXED_3X3);
        addChunk(regionizer, this.level, 0, 0);
        final Entity player = this.entity(new BlockPos(1, 64, 1));
        when(player.isAlwaysTicking()).thenReturn(true);
        assertTrue(tickStarted(regionizer, this.level, player));

        final Object region = regionFor(regionizer, player);
        assertNotNull(region);
        assertEquals(1, tickingCount(region));
        assertEquals(1, serverThreadOnlyCount(region));
        assertTrue(serverThreadOnly(entryOf(region, player)), "an always-ticking entity must be marked for the server thread");
    }

    @Test
    void clearLevelAndClearDropEveryIndexEntry() throws ReflectiveOperationException {
        final Object regionizer = newRegionizer(RegionLayout.FIXED_3X3);
        addChunk(regionizer, this.level, 0, 0);
        final Entity entity = this.tickingEntity(regionizer, new BlockPos(1, 64, 1));
        assertEquals(1, entityCount(regionizer));

        clearLevel(regionizer, this.level);
        assertEquals(0, entityCount(regionizer));
        assertNull(regionFor(regionizer, entity));
        for (final Object region : regions(regionizer)) {
            assertEquals(0, entryCount(region), "a level clear must drop the index of every region of the level");
        }

        final ServerLevel otherLevel = mock(ServerLevel.class);
        addChunk(regionizer, otherLevel, 0, 0);
        final Entity other = this.entity(otherLevel, new BlockPos(1, 64, 1));
        assertTrue(tickStarted(regionizer, otherLevel, other));
        assertEquals(1, entryCount(regionFor(regionizer, other)));

        clear(regionizer);
        assertTrue(regions(regionizer).isEmpty());
    }

    /**
     * Asserts that the index and the regionizer's entity mapping name the same entity set, that every entry
     * sits in the region its own mapping names, and that the order of every region is the tick order.
     */
    private void assertIndexMatchesMapping(Object regionizer, List<Entity> live, List<Boolean> ticking) throws ReflectiveOperationException {
        final Map<Entity, Object> view = new IdentityHashMap<>();
        for (final Object region : regions(regionizer)) {
            long previousSequence = Long.MIN_VALUE;
            int ticked = 0;
            for (final Object entry : entries(region)) {
                final long sequence = sequence(entry);
                assertTrue(sequence > previousSequence, "the entries of a region must be in tick order");
                previousSequence = sequence;
                final Entity entryEntity = entryEntity(entry);
                assertNull(view.put(entryEntity, region), "an entity must be indexed by exactly one region");
                assertSame(region, regionFor(regionizer, entryEntity), "an index entry must name the region of the entity's mapping");
                if (ticking(entry)) {
                    ++ticked;
                }
            }
            assertEquals(entryCount(region), entries(region).size());
            assertEquals(ticked, tickingCount(region));
        }
        assertEquals(entityCount(regionizer), view.size(), "every mapped entity must be indexed");
        for (int index = 0; index < live.size(); index++) {
            final Entity entity = live.get(index);
            final Object mapped = regionFor(regionizer, entity);
            assertNotNull(mapped, "a live entity must be mapped");
            assertSame(mapped, view.get(entity), "a live entity must be indexed by the region it is mapped to");
            assertEquals(ticking.get(index), ticking(entryOf(mapped, entity)), "the tick bit must follow the tick list membership");
        }
    }

    private List<Entity> tickingEntities(Object region) throws ReflectiveOperationException {
        final List<Entity> entities = new ArrayList<>();
        for (final Object entry : entries(region)) {
            if (ticking(entry)) {
                entities.add(entryEntity(entry));
            }
        }
        return entities;
    }

    private Entity tickingEntity(Object regionizer, BlockPos pos) throws ReflectiveOperationException {
        final Entity entity = this.entity(pos);
        assertTrue(tickStarted(regionizer, this.level, entity));
        return entity;
    }

    private Entity entity(BlockPos pos) {
        return this.entity(this.level, pos);
    }

    private Entity entity(ServerLevel entityLevel, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(entityLevel);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.getId()).thenReturn(this.nextEntityId++);
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }

    private void moveTo(Entity entity, BlockPos pos) {
        when(entity.blockPosition()).thenReturn(pos);
    }

    private BlockPos position(Random random) {
        return new BlockPos(random.nextInt(9) * 16 + 1, 64, random.nextInt(9) * 16 + 1);
    }

    // The regionizer, its region state and its entry type are package private; these helpers are the
    // reflection seam of this test only.

    private static Object newRegionizer(RegionLayout layout) throws ReflectiveOperationException {
        final Class<?> type = Class.forName("net.neoforged.neoforge.server.threading.region.ThreadedRegionizer");
        final Constructor<?> constructor = type.getDeclaredConstructor(RegionLayout.class);
        constructor.setAccessible(true);
        return constructor.newInstance(layout);
    }

    private static void addChunk(Object regionizer, ServerLevel level, int chunkX, int chunkZ) throws ReflectiveOperationException {
        invoke(regionizer, "addChunk", level, chunkX, chunkZ);
    }

    private static void removeChunk(Object regionizer, ServerLevel level, int chunkX, int chunkZ) throws ReflectiveOperationException {
        invoke(regionizer, "removeChunk", level, chunkX, chunkZ);
    }

    private static void addEntity(Object regionizer, RegionLayout layout, Entity entity) throws ReflectiveOperationException {
        invoke(regionizer, "addEntity", entity, layout.regionAt((ServerLevel) entity.level(), entity.blockPosition()));
    }

    private static void removeEntity(Object regionizer, Entity entity) throws ReflectiveOperationException {
        invoke(regionizer, "removeEntity", entity);
    }

    private static boolean tickStarted(Object regionizer, ServerLevel level, Entity entity) throws ReflectiveOperationException {
        return (Boolean) invoke(regionizer, "onEntityTickStarted", level, entity);
    }

    private static void tickEnded(Object regionizer, ServerLevel level, Entity entity) throws ReflectiveOperationException {
        invoke(regionizer, "onEntityTickEnded", level, entity);
    }

    private static Object regionFor(Object regionizer, Entity entity) throws ReflectiveOperationException {
        return invoke(regionizer, "regionFor", entity);
    }

    private static List<?> regions(Object regionizer) throws ReflectiveOperationException {
        return new ArrayList<>((java.util.Collection<?>) invoke(regionizer, "regions"));
    }

    private static List<?> activeEntityTickRegions(Object regionizer, ServerLevel level) throws ReflectiveOperationException {
        final List<Object> active = new java.util.ArrayList<>();
        invoke(regionizer, "collectActiveEntityTickRegions", level, active);
        return active;
    }

    private static int entityCount(Object regionizer) throws ReflectiveOperationException {
        return (Integer) invoke(regionizer, "entityCount");
    }

    private static int size(Object regionizer) throws ReflectiveOperationException {
        return (Integer) invoke(regionizer, "size");
    }

    private static void clearLevel(Object regionizer, Object level) throws ReflectiveOperationException {
        invoke(regionizer, "clearLevel", level);
    }

    private static void clear(Object regionizer) throws ReflectiveOperationException {
        invoke(regionizer, "clear");
    }

    private static List<?> entries(Object region) throws ReflectiveOperationException {
        return (List<?>) invoke(region, "entityTickEntries");
    }

    private static Object entryOf(Object region, Entity entity) throws ReflectiveOperationException {
        return invoke(region, "entityTickEntry", entity);
    }

    private static int entryCount(Object region) throws ReflectiveOperationException {
        return (Integer) invoke(region, "entityTickEntryCount");
    }

    private static int tickingCount(Object region) throws ReflectiveOperationException {
        return (Integer) invoke(region, "tickingEntityCount");
    }

    private static int serverThreadOnlyCount(Object region) throws ReflectiveOperationException {
        return (Integer) invoke(region, "serverThreadOnlyEntityCount");
    }

    private static Entity entryEntity(Object entry) throws ReflectiveOperationException {
        return (Entity) invoke(entry, "entity");
    }

    private static long sequence(Object entry) throws ReflectiveOperationException {
        return (Long) invoke(entry, "sequence");
    }

    private static boolean ticking(Object entry) throws ReflectiveOperationException {
        return (Boolean) invoke(entry, "ticking");
    }

    private static boolean serverThreadOnly(Object entry) throws ReflectiveOperationException {
        return (Boolean) invoke(entry, "serverThreadOnly");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        final Method method = method(target.getClass(), name, arguments.length);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Method method(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (final Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + " with " + parameterCount + " parameters");
    }
}
