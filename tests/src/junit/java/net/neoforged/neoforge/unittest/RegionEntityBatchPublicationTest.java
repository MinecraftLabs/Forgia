/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.ScheduledRegionTask;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the batch publication of the deferred ownership updates of one entity batch barrier.
 * <p>
 * The barrier publishes every deferred update of a tick, so a per-update publication makes the server
 * thread take one regionizer write lock and visit the registered scheduled tasks once per entity. The batch
 * entry resolves the whole list under one write lock: the updates that name the region their entity is
 * already published in are no-ops, an untracked entity is added, and a move to another region keeps the
 * recorded transfer transaction. These tests reach the barrier, the batch and the regionizer through their
 * package-private seams; the engine, the batch and the regionizer are the real implementations and only the
 * levels are mechanics objects.
 */
@Timeout(60)
class RegionEntityBatchPublicationTest {
    private static final String ENTITY_BATCH_TYPE = "net.neoforged.neoforge.server.threading.region.RegionEntityTickBatch";
    private static final String REGIONIZER_TYPE = "net.neoforged.neoforge.server.threading.region.ThreadedRegionizer";
    private static final String OWNER_MOVE_TYPE = REGIONIZER_TYPE + "$OwnerMove";

    private static final Constructor<?> OWNER_MOVE;
    private static final Constructor<?> ENTITY_BATCH;
    private static final Method PUBLISH_ENTITY_MOVES;
    private static final Method REGIONIZER_REGION_FOR;
    private static final Method REGION_COORDINATE;
    private static final Method APPLY_DEFERRED_UPDATES;
    private static final Method APPLY_DEFERRED_UPDATE;
    private static final Method DEFER_ENTITY_UPDATE;
    private static final Field REGIONIZER_FIELD;
    private static final Field TRACKED_ENTITIES;
    private static final Field ACTIVE_TICK_BATCHES;

    static {
        try {
            final Class<?> regionizerType = Class.forName(REGIONIZER_TYPE);
            OWNER_MOVE = accessible(Class.forName(OWNER_MOVE_TYPE).getDeclaredConstructor(Entity.class, RegionCoordinate.class));
            ENTITY_BATCH = accessible(Class.forName(ENTITY_BATCH_TYPE).getDeclaredConstructor(ServerLevel.class));
            PUBLISH_ENTITY_MOVES = accessible(regionizerType.getDeclaredMethod("publishEntityMoves", List.class));
            REGIONIZER_REGION_FOR = accessible(regionizerType.getDeclaredMethod("regionFor", Entity.class));
            REGION_COORDINATE = accessible(Class.forName("net.neoforged.neoforge.server.threading.region.RegionState").getDeclaredMethod("coordinate"));
            APPLY_DEFERRED_UPDATES = accessible(RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntityUpdates", Class.forName(ENTITY_BATCH_TYPE)));
            APPLY_DEFERRED_UPDATE = accessible(RegionThreadingEngine.class.getDeclaredMethod("applyDeferredEntityUpdate", Entity.class, RegionCoordinate.class, int.class));
            DEFER_ENTITY_UPDATE = accessible(Class.forName(ENTITY_BATCH_TYPE).getDeclaredMethod("deferEntityUpdate", Entity.class, RegionCoordinate.class));
            REGIONIZER_FIELD = accessible(RegionThreadingEngine.class.getDeclaredField("regionizer"));
            TRACKED_ENTITIES = accessible(regionizerType.getDeclaredField("entities"));
            ACTIVE_TICK_BATCHES = accessible(RegionThreadingEngine.class.getDeclaredField("activeTickBatches"));
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private RegionThreadingEngine engine;
    private RegionThreadingEngine referenceEngine;
    private EntityIdentityLedger ledger;
    private ServerLevel level;
    private Thread serverThread;
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void clearThreading() {
        try {
            RegionThreadingManager.clear();
        } finally {
            if (this.engine != null) {
                this.engine.shutdown();
            }
            if (this.referenceEngine != null) {
                this.referenceEngine.shutdown();
            }
            this.engine = null;
            this.referenceEngine = null;
            this.level = null;
            this.ledger = null;
            this.serverThread = null;
        }
    }

    /**
     * A batch publishes the same mapping as the per-entity publication of the same updates.
     * <p>
     * The three cases of a deferred update are covered in one barrier: an entity that stayed in its region,
     * an entity that reached another region (which keeps the recorded transfer) and an entity that was not
     * tracked yet. The batch publishes all of them for one barrier; the reference engine publishes the same
     * list one update at a time, exactly like the barrier did before the batch existed.
     */
    @Test
    void oneBatchProducesTheSameMappingAsThePerEntityPublication() throws ReflectiveOperationException {
        final int unchangedCount = 340;
        final int transferredCount = 340;
        final int addedCount = 344;
        final BlockPos origin = new BlockPos(0, 64, 0);
        final BlockPos transferredTarget = new BlockPos(4096, 64, 0);
        final BlockPos addedTarget = new BlockPos(8192, 64, 0);

        this.installEngines();
        final List<Entity> unchanged = new ArrayList<>();
        final List<Entity> transferred = new ArrayList<>();
        final List<Entity> added = new ArrayList<>();
        for (int index = 0; index < unchangedCount; index++) {
            unchanged.add(this.registeredEntity(origin));
        }
        this.activate(transferredTarget);
        for (int index = 0; index < transferredCount; index++) {
            transferred.add(this.registeredEntity(origin));
        }
        for (int index = 0; index < addedCount; index++) {
            added.add(this.entity(addedTarget));
        }
        final List<Entity> all = new ArrayList<>(unchanged);
        all.addAll(transferred);
        all.addAll(added);
        final Map<Entity, RegionCoordinate> destinations = new IdentityHashMap<>();
        for (final Entity entity : unchanged) {
            destinations.put(entity, this.regionAt(origin));
        }
        for (final Entity entity : transferred) {
            // The entity already reached the destination region; the deferring thread captured it.
            when(entity.blockPosition()).thenReturn(transferredTarget);
            destinations.put(entity, this.regionAt(transferredTarget));
        }
        for (final Entity entity : added) {
            destinations.put(entity, this.regionAt(addedTarget));
        }

        final Object batch = newBatch();
        Collections.shuffle(all, new Random(0x5EED5EEDL));
        for (final Entity entity : all) {
            DEFER_ENTITY_UPDATE.invoke(batch, entity, destinations.get(entity));
        }
        this.addActiveBatch(batch);
        try {
            APPLY_DEFERRED_UPDATES.invoke(this.engine, batch);
        } finally {
            this.removeActiveBatch(batch);
        }

        for (final Entity entity : all) {
            APPLY_DEFERRED_UPDATE.invoke(this.referenceEngine, entity, destinations.get(entity), 0);
        }

        assertEquals(all.size(), this.trackedCount(this.engine),
                "every update must leave its entity published exactly once");
        assertEquals(this.trackedCount(this.engine), this.trackedCount(this.referenceEngine),
                "the batch must publish the same population as the per-entity publication");
        int unchangedMappings = 0;
        for (final Entity entity : unchanged) {
            if (this.regionAt(origin).equals(trackedCoordinate(this.engine, entity))) {
                unchangedMappings++;
            }
        }
        assertEquals(unchangedCount, unchangedMappings, "an unchanged update must keep its mapping");
        for (final Entity entity : all) {
            assertEquals(trackedCoordinate(this.referenceEngine, entity), trackedCoordinate(this.engine, entity),
                    "the batch must publish the destination the per-entity publication reaches");
        }
        assertEquals(unchangedCount, this.engine.diagnostics().unchangedDeferredEntityMoves(),
                "the barrier must resolve the unchanged updates through the batch");
        assertEquals(transferredCount + addedCount, this.engine.diagnostics().batchedDeferredEntityMoves(),
                "the barrier must publish the added entities and report the transfers of the batch");
        assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount(),
                "a recorded transfer of the batch must not fall back to the live position");
        assertEquals(0L, this.engine.diagnostics().refusedDeferredEntityMoveBatches());
    }

    /**
     * A refused batch writes nothing at all, and the barrier then publishes its updates per entity.
     * <p>
     * An invalid entry is never dropped, skipped or applied alone: the whole batch is refused, so the
     * barrier cannot end up with half of its ownership updates published. The engine falls back to the
     * per-entity publication of every update of that barrier, which is exactly what it did before the batch
     * existed.
     */
    @Test
    void aRefusedBatchPublishesNoneOfItsMovesAndTheBarrierFallsBack() throws ReflectiveOperationException {
        this.installEngine();
        final ServerLevel closedLevel = mock(ServerLevel.class);
        this.engine.clearLevelRegionTasks(closedLevel);
        final Entity open = this.entity(new BlockPos(0, 64, 0));
        final Entity closed = this.entity(new BlockPos(0, 64, 0));

        final Object result = publishEntityMoves(this.engine, List.of(
                ownerMove(open, this.regionAt(new BlockPos(0, 64, 0))),
                ownerMove(closed, RegionLayout.DYNAMIC.regionAt(closedLevel, BlockPos.ZERO))));

        assertEquals("LEVEL_CLOSED", outcomeOf(result), "a destination in a closed level identity must refuse the batch");
        assertEquals(0, this.trackedCount(this.engine), "a refused batch must not publish any of its moves");
        assertTrue(publishedOf(result).isEmpty(), "a refused batch must report no published entity");
        assertTrue(unchangedOf(result).isEmpty(), "a refused batch must report no unchanged entity");
        assertTrue(transfersOf(result).isEmpty(), "a refused batch must report no transfer");

        // The same refusal through the barrier: the regionizer refuses the whole batch of a level identity
        // that closed, so the barrier publishes the updates per entity, which cannot create a mapping there.
        final Entity barrierEntity = this.entity(new BlockPos(0, 64, 0));
        final RegionCoordinate destination = this.regionAt(new BlockPos(0, 64, 0));
        this.engine.clearLevelRegionTasks(this.level);
        final Object batch = newBatch();
        DEFER_ENTITY_UPDATE.invoke(batch, barrierEntity, destination);
        this.addActiveBatch(batch);
        try {
            APPLY_DEFERRED_UPDATES.invoke(this.engine, batch);
        } finally {
            this.removeActiveBatch(batch);
        }
        assertEquals(1L, this.engine.diagnostics().refusedDeferredEntityMoveBatches(),
                "the barrier must count the refused batch instead of dropping it silently");
        assertEquals(0, this.trackedCount(this.engine), "a closed level identity must not receive a mapping");
    }

    /**
     * A move notification still migrates a scheduled task that waits in another region, and an unchanged
     * update does not migrate a task that already waits in the entity's region.
     * <p>
     * The batch skips the notification of an unchanged update for an entity that has no wrapper, because
     * such a notification could never migrate anything. An entity that has a wrapper is notified exactly like
     * before, so a task that was scheduled in a region the entity has already left follows the entity.
     */
    @Test
    void aBatchNotifiesTheEntitiesThatHaveAScheduledTask() throws ReflectiveOperationException {
        this.installEngine();
        final BlockPos origin = new BlockPos(0, 64, 0);
        final BlockPos elsewhere = new BlockPos(4096, 64, 0);
        final Entity entity = this.entity(origin);
        this.engine.addRegionEntity(entity);
        final AtomicInteger runs = new AtomicInteger();

        // The task is scheduled while the entity stands in another region, so it waits where the entity no
        // longer is.
        when(entity.blockPosition()).thenReturn(elsewhere);
        final ScheduledRegionTask task = this.engine.entityScheduler().runDelayed(entity, runs::incrementAndGet, 5L);
        assertNotNull(task);
        when(entity.blockPosition()).thenReturn(origin);
        assertEquals(0L, this.engine.diagnostics().scheduledTaskMigrations());

        // The deferred update names the region the entity is already published in: nothing is published, but
        // the task still has to follow the entity back to its region.
        final Object migratingBatch = newBatch();
        DEFER_ENTITY_UPDATE.invoke(migratingBatch, entity, this.regionAt(origin));
        this.addActiveBatch(migratingBatch);
        try {
            APPLY_DEFERRED_UPDATES.invoke(this.engine, migratingBatch);
        } finally {
            this.removeActiveBatch(migratingBatch);
        }
        assertEquals(1L, this.engine.diagnostics().scheduledTaskMigrations(),
                "a task that waits in another region must still be migrated by a notification");
        assertEquals(1L, this.engine.diagnostics().unchangedDeferredEntityMoves(),
                "the update must be resolved as unchanged instead of being published again");

        // The task now waits in the entity's region, so the next unchanged update must not migrate it again.
        final Object stableBatch = newBatch();
        DEFER_ENTITY_UPDATE.invoke(stableBatch, entity, this.regionAt(origin));
        this.addActiveBatch(stableBatch);
        try {
            APPLY_DEFERRED_UPDATES.invoke(this.engine, stableBatch);
        } finally {
            this.removeActiveBatch(stableBatch);
        }
        assertEquals(1L, this.engine.diagnostics().scheduledTaskMigrations(),
                "an intra-region move must not migrate a task that already waits in the region");
        assertEquals(2L, this.engine.diagnostics().unchangedDeferredEntityMoves());
        assertEquals(0, runs.get(), "a delayed task must not run before its delay elapsed");
    }

    /**
     * One barrier resolves every update of its batch under one regionizer write lock.
     * <p>
     * The deferred update of every entity tick names the region the entity is already published in, so the
     * barrier of a whole tick is a batch of no-ops. The same updates published one at a time take one write
     * lock each, which is the cost the batch removes from the server thread.
     */
    @Test
    void oneBarrierPublishesAllItsUpdatesUnderOneWriteLock() throws ReflectiveOperationException {
        this.installEngine();
        final BlockPos origin = new BlockPos(0, 64, 0);
        final int population = 64;
        final List<Entity> entities = new ArrayList<>();
        for (int index = 0; index < population; index++) {
            final Entity entity = this.entity(origin);
            this.engine.addRegionEntity(entity);
            entities.add(entity);
        }
        final RegionCoordinate coordinate = this.regionAt(origin);
        final Object batch = newBatch();
        for (final Entity entity : entities) {
            DEFER_ENTITY_UPDATE.invoke(batch, entity, coordinate);
        }

        final long beforeBatch = this.engine.diagnostics().regionizerWriteLockAcquisitions();
        this.addActiveBatch(batch);
        try {
            APPLY_DEFERRED_UPDATES.invoke(this.engine, batch);
        } finally {
            this.removeActiveBatch(batch);
        }
        final long afterBatch = this.engine.diagnostics().regionizerWriteLockAcquisitions();
        assertEquals(1L, afterBatch - beforeBatch, "one barrier must publish its whole batch in one write lock scope");
        assertEquals(population, this.engine.diagnostics().unchangedDeferredEntityMoves(),
                "every update of this barrier is a no-op and must be resolved as unchanged");
        assertEquals(0L, this.engine.diagnostics().batchedDeferredEntityMoves(),
                "an unchanged update publishes no mapping");
        assertEquals(population, this.trackedCount(this.engine), "no entity may lose its mapping");

        final long beforePerEntity = this.engine.diagnostics().regionizerWriteLockAcquisitions();
        for (final Entity entity : entities) {
            APPLY_DEFERRED_UPDATE.invoke(this.engine, entity, coordinate, 0);
        }
        final long afterPerEntity = this.engine.diagnostics().regionizerWriteLockAcquisitions();
        assertEquals(population, afterPerEntity - beforePerEntity,
                "the per-entity publication takes one write lock per update, which is the cost the batch removes");
        assertEquals(population, this.trackedCount(this.engine));
        assertEquals(0L, this.engine.diagnostics().legacyDeferredEntityMoveCount());
    }

    private void installEngine() {
        this.engine = new RegionThreadingEngine(1, false, false);
        this.serverThread = Thread.currentThread();
        this.ledger = new EntityIdentityLedger();
        this.level = mechanicsLevel(this.ledger);
    }

    /**
     * Installs the measured engine and the reference engine on the same mechanics level and ledger, so both
     * publish into their own regionizer but read the same level identity.
     */
    private void installEngines() {
        this.installEngine();
        this.referenceEngine = new RegionThreadingEngine(1, false, false);
    }

    /**
     * Creates a mechanics level whose server answers {@link MinecraftServer#isSameThread()} for the thread
     * that created it, so a barrier that runs on that thread may publish its batch.
     */
    private ServerLevel mechanicsLevel(EntityIdentityLedger levelLedger) {
        final ServerLevel mockLevel = mock(ServerLevel.class);
        doReturn(levelLedger).when(mockLevel).entityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenAnswer(invocation -> Thread.currentThread() == this.serverThread);
        doReturn(server).when(mockLevel).getServer();
        return mockLevel;
    }

    private Entity entity(BlockPos position) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(this.level);
        when(entity.blockPosition()).thenReturn(position);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.getPassengers()).thenReturn(List.of());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }

    /**
     * Creates an entity that is admitted to the level identity ledger and tracked by both engines, so a
     * recorded cross-region transfer of it can commit.
     */
    private Entity registeredEntity(BlockPos position) {
        final Entity entity = this.entity(position);
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, this.ledger.admit(entity));
        this.ledger.publishAdmitted(entity);
        this.ledger.publishTracked(entity);
        this.engine.addRegionEntity(entity);
        this.referenceEngine.addRegionEntity(entity);
        return entity;
    }

    private void activate(BlockPos position) {
        final int chunkX = Math.floorDiv(position.getX(), 16);
        final int chunkZ = Math.floorDiv(position.getZ(), 16);
        this.engine.addRegionSection(this.level, chunkX, chunkZ);
        this.referenceEngine.addRegionSection(this.level, chunkX, chunkZ);
    }

    private RegionCoordinate regionAt(BlockPos position) {
        return RegionLayout.DYNAMIC.regionAt(this.level, position);
    }

    private int trackedCount(RegionThreadingEngine installed) throws ReflectiveOperationException {
        return trackedEntities(installed).size();
    }

    private static RegionCoordinate trackedCoordinate(RegionThreadingEngine installed, Entity entity) throws ReflectiveOperationException {
        final Object region = REGIONIZER_REGION_FOR.invoke(regionizerOf(installed), entity);
        assertNotNull(region, () -> "the entity is not tracked by the regionizer: " + entity);
        return (RegionCoordinate) REGION_COORDINATE.invoke(region);
    }

    private static Map<?, ?> trackedEntities(RegionThreadingEngine installed) throws ReflectiveOperationException {
        return (Map<?, ?>) TRACKED_ENTITIES.get(regionizerOf(installed));
    }

    private static Object regionizerOf(RegionThreadingEngine installed) throws ReflectiveOperationException {
        return REGIONIZER_FIELD.get(installed);
    }

    private static Object ownerMove(Entity entity, RegionCoordinate destination) throws ReflectiveOperationException {
        return OWNER_MOVE.newInstance(entity, destination);
    }

    private static Object publishEntityMoves(RegionThreadingEngine installed, List<?> moves) throws ReflectiveOperationException {
        return PUBLISH_ENTITY_MOVES.invoke(regionizerOf(installed), moves);
    }

    private static String outcomeOf(Object result) throws ReflectiveOperationException {
        return String.valueOf(listMember(result, "outcome"));
    }

    private static List<?> publishedOf(Object result) throws ReflectiveOperationException {
        return (List<?>) listMember(result, "published");
    }

    private static List<?> unchangedOf(Object result) throws ReflectiveOperationException {
        return (List<?>) listMember(result, "unchanged");
    }

    private static List<?> transfersOf(Object result) throws ReflectiveOperationException {
        return (List<?>) listMember(result, "transfers");
    }

    private static Object listMember(Object result, String accessor) throws ReflectiveOperationException {
        final Method method = result.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(result);
    }

    private Object newBatch() throws ReflectiveOperationException {
        return ENTITY_BATCH.newInstance(this.level);
    }

    @SuppressWarnings("unchecked")
    private void addActiveBatch(Object batch) {
        try {
            ((Set<Object>) ACTIVE_TICK_BATCHES.get(this.engine)).add(batch);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeActiveBatch(Object batch) {
        try {
            ((Set<Object>) ACTIVE_TICK_BATCHES.get(this.engine)).remove(batch);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static Constructor<?> accessible(Constructor<?> constructor) {
        constructor.setAccessible(true);
        return constructor;
    }
}
