/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the deferred ownership updates of one entity tick batch.
 * <p>
 * Every entity tick defers one update per entity and every worker defers onto the same batch, so the batch
 * has to record a deferral in constant time under one monitor: an implementation that scans the deferrals
 * already recorded makes the tick quadratic in the population and serializes the workers (measured as the
 * dominant cost of the 1024-zombie cells). The structure is reached through its package-private seams; the
 * batch itself is the real implementation and only its level is a mechanics object.
 */
@Timeout(120)
class RegionEntityDeferredUpdateTest {
    private static final String ENTITY_BATCH_TYPE = "net.neoforged.neoforge.server.threading.region.RegionEntityTickBatch";
    /**
     * Entries the batch reserves for the deferred block updates. The seams under test defer no block write, so
     * the production bound is used.
     */
    private static final int DEFERRED_BLOCK_UPDATE_ENTRIES = 1 << 16;

    private static final Method DEFER_ENTITY_UPDATE;
    private static final Method DRAIN_DEFERRED_UPDATES;
    private static final Method DISCARD_DEFERRED_UPDATES;
    private static final Method UPDATE_ENTITY;
    private static final Method UPDATE_DESTINATION;

    /** One level for every captured destination, so the coordinates of one test compare by value. */
    private final ServerLevel destinationLevel = mock(ServerLevel.class);

    static {
        try {
            final Class<?> batchType = Class.forName(ENTITY_BATCH_TYPE);
            DEFER_ENTITY_UPDATE = accessible(batchType.getDeclaredMethod("deferEntityUpdate", Entity.class, RegionCoordinate.class));
            DRAIN_DEFERRED_UPDATES = accessible(batchType.getDeclaredMethod("drainDeferredUpdates"));
            DISCARD_DEFERRED_UPDATES = accessible(batchType.getDeclaredMethod("discardDeferredUpdates", Collection.class));
            final Class<?> updateType = Class.forName(ENTITY_BATCH_TYPE + "$DeferredEntityUpdate");
            UPDATE_ENTITY = accessible(updateType.getDeclaredMethod("entity"));
            UPDATE_DESTINATION = accessible(updateType.getDeclaredMethod("destination"));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Test
    void concurrentDefersOfDistinctEntitiesDrainOnceWithOneEntryPerEntity() throws ReflectiveOperationException {
        final Object batch = newBatch();
        final List<Entity> entities = entities(1_024);
        final RegionCoordinate destination = destination(0);

        deferConcurrently(batch, entities, 4, destination);

        final List<?> updates = drain(batch);
        assertEquals(entities.size(), updates.size(), "every deferred entity must reach the barrier once");
        final Set<Entity> drained = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object update : updates) {
            assertTrue(drained.add(entityOf(update)), "an entity must not be deferred twice");
            assertEquals(destination, destinationOf(update), "the captured destination must survive the deferral");
        }
        assertEquals(entities.size(), drained.size(), "the drain must return exactly the deferred entities");
        for (final Entity entity : entities) {
            assertTrue(drained.contains(entity), "a concurrently deferred entity was lost");
        }
    }

    @Test
    void theDrainEmptiesTheStructureAndALaterDeferIsRecordedAgain() throws ReflectiveOperationException {
        final Object batch = newBatch();
        final Entity entity = mock(Entity.class);

        defer(batch, entity, destination(0));
        assertEquals(1, drain(batch).size());
        assertEquals(0, drain(batch).size(), "the barrier must not see a drained update twice");

        defer(batch, entity, destination(1));
        final List<?> updates = drain(batch);
        assertEquals(1, updates.size(), "a defer after the drain belongs to the structure again");
        assertEquals(destination(1), destinationOf(updates.get(0)));
    }

    @Test
    void aLaterObservationOfTheSameEntityReplacesTheEarlierDestination() throws ReflectiveOperationException {
        final Object batch = newBatch();
        final Entity entity = mock(Entity.class);

        defer(batch, entity, destination(0));
        defer(batch, entity, destination(1));
        defer(batch, entity, destination(2));

        final List<?> updates = drain(batch);
        assertEquals(1, updates.size(), "an entity is published once per batch");
        assertEquals(entity, entityOf(updates.get(0)));
        assertEquals(destination(2), destinationOf(updates.get(0)),
                "the newest observation of the entity must win");
    }

    @Test
    void discardRemovesExactlyTheNamedEntitiesAndKeepsTheOthers() throws ReflectiveOperationException {
        final Object batch = newBatch();
        final List<Entity> entities = entities(4);
        for (int index = 0; index < entities.size(); index++) {
            defer(batch, entities.get(index), destination(index));
        }
        final Entity neverDeferred = mock(Entity.class);

        discard(batch, List.of(entities.get(1), entities.get(3), neverDeferred));

        final List<?> updates = drain(batch);
        assertEquals(2, updates.size(), "only the named entries may be dropped");
        final Set<Entity> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object update : updates) {
            remaining.add(entityOf(update));
        }
        assertTrue(remaining.contains(entities.get(0)));
        assertTrue(remaining.contains(entities.get(2)));
    }

    /**
     * A batch records one deferral per ticked entity, so a whole batch of them must cost the same per
     * entity no matter how many are already recorded. An implementation that scans the recorded deferrals
     * per defer performs {@code entityCount * entityCount / 2} identity comparisons under the batch
     * monitor, which is orders of magnitude above this bound and was measured in the seconds range for this
     * population; the identity-keyed recording needs a few milliseconds including the thread handshakes.
     */
    @Test
    void deferringManyEntitiesDoesNotScanTheDeferredSet() throws ReflectiveOperationException {
        final int entityCount = 32_768;
        final Object batch = newBatch();
        final List<Entity> entities = entities(entityCount);
        final RegionCoordinate destination = destination(0);

        final long started = System.nanoTime();
        deferConcurrently(batch, entities, 4, destination);
        final long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(entityCount, drain(batch).size(), "every deferred entity must reach the barrier once");
        assertTrue(elapsedMillis < 5_000L,
                () -> "deferring " + entityCount + " distinct entities took " + elapsedMillis
                        + " ms; a scanning implementation needs " + ((long) entityCount * entityCount / 2)
                        + " identity comparisons under the batch monitor and cannot stay inside this bound");
    }

    private static Object newBatch() throws ReflectiveOperationException {
        final Constructor<?> constructor = Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(mock(ServerLevel.class), DEFERRED_BLOCK_UPDATE_ENTRIES);
    }

    private static List<Entity> entities(int count) {
        final List<Entity> entities = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entities.add(mock(Entity.class));
        }
        return entities;
    }

    private RegionCoordinate destination(int index) {
        return new RegionCoordinate(this.destinationLevel, index, 0);
    }

    private static void defer(Object batch, Entity entity, RegionCoordinate destination) throws ReflectiveOperationException {
        DEFER_ENTITY_UPDATE.invoke(batch, entity, destination);
    }

    private static List<?> drain(Object batch) throws ReflectiveOperationException {
        return (List<?>) DRAIN_DEFERRED_UPDATES.invoke(batch);
    }

    private static void discard(Object batch, Collection<Entity> entities) throws ReflectiveOperationException {
        DISCARD_DEFERRED_UPDATES.invoke(batch, entities);
    }

    private static Entity entityOf(Object update) throws ReflectiveOperationException {
        return (Entity) UPDATE_ENTITY.invoke(update);
    }

    private static RegionCoordinate destinationOf(Object update) throws ReflectiveOperationException {
        return (RegionCoordinate) UPDATE_DESTINATION.invoke(update);
    }

    /**
     * Defers {@code entities} from {@code threads} real threads at once, so the recording runs under the
     * contention of a batch whose entity work is spread over several workers.
     */
    private static void deferConcurrently(Object batch, List<Entity> entities, int threads, RegionCoordinate destination)
            throws ReflectiveOperationException {
        final int perThread = (entities.size() + threads - 1) / threads;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final List<Thread> workers = new ArrayList<>(threads);
        for (int index = 0; index < threads; index++) {
            final int from = index * perThread;
            final int to = Math.min(entities.size(), from + perThread);
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int entityIndex = from; entityIndex < to; entityIndex++) {
                        defer(batch, entities.get(entityIndex), destination);
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "deferred-update-" + index);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (final Thread worker : workers) {
            await(worker);
        }
        assertTrue(failures.isEmpty(), () -> "a concurrent deferral failed: " + failures);
        for (final Thread worker : workers) {
            assertFalse(worker.isAlive(), "a deferring thread did not finish inside the bound");
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static void await(Thread worker) {
        try {
            worker.join(TimeUnit.SECONDS.toMillis(60));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
