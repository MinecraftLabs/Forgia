/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickScope;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferResult;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferToken;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionOwnershipViolationHandler;
import net.neoforged.neoforge.server.threading.region.RegionRandomTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionScopedEntityQuery;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Internal bridge points for the region threading runtime.
 */
@ApiStatus.Internal
public final class RegionThreadingHooks {
    private RegionThreadingHooks() {}

    /**
     * Captures the installed bridge's layout without reading a partially constructed level. Levels
     * created before bridge installation retain legacy storage for their lifetime; storage is never
     * migrated by installing or removing a bridge. The normal server lifecycle installs the bridge
     * before creating levels. This choice grants no worker access to either backend.
     * <p>
     * The layout carries the fixed region edge length, so the physical section partitions of a level are
     * created with the same size the installed engine ticks with.
     */
    public static RegionLayout entitySectionStorageLayout() {
        return RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine
                ? engine.regionLayout()
                : RegionLayout.DYNAMIC;
    }

    /**
     * Drains global-region work on the current server tick loop.
     */
    public static void drainGlobalTasks() {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.drainGlobalTasks();
            }
        });
    }

    /**
     * Runs {@code task} in global-region context when the native engine is installed.
     */
    public static void runGlobalTask(Runnable task) {
        final Optional<RegionThreading> threading = RegionThreadingManager.get();
        if (threading.isPresent() && threading.get() instanceof RegionThreadingEngine engine) {
            engine.runInGlobalRegion(task);
        } else {
            task.run();
        }
    }

    /**
     * Drains region work for {@code level} on the current level tick loop.
     */
    public static void drainLevelRegionTasks(ServerLevel level) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.drainLevelRegionTasks(level);
            }
        });
    }

    /**
     * {@return true when the current thread executes work of a native region worker pool}
     * <p>
     * The marker belongs to the pool and survives removal of the installed manager or engine, so it can
     * be used by storage guards that must never depend on a successful engine lookup.
     */
    public static boolean isNativeRegionWorkerThread() {
        return RegionWorkerScope.isNativeWorkerThread();
    }

    /**
     * {@return true when the current thread may skip the server-owned entity-load read of {@code pos}}
     * <p>
     * The entity section status is server-owned storage: a native region worker read of it is rejected.
     * The server thread therefore evaluates the vanilla entity-load eligibility of every block-entity
     * ticker before it admits the ticker to a region worker batch, and this check is the replacement for
     * that one read. It answers {@code true} only inside the invocation of the admitted
     * ticker or its captured delegate, for the exact level and position while its group owns the region
     * lease. Every other caller - the server thread, the watchdog thread, an unrelated native pool task,
     * another level, another position, another region - keeps the guarded read.
     */
    public static boolean isAdmittedBlockEntityTick(ServerLevel level, @Nullable TickingBlockEntity ticker, BlockPos pos) {
        return RegionBlockEntityTickScope.isAdmitted(level, ticker, pos);
    }

    /**
     * Rejects server-owned entity storage access issued from a native region worker.
     * <p>
     * This is production behavior, not a debug switch. The rejection is unconditional and happens before
     * the guarded operation reads or changes state. It is deliberately independent of
     * {@code ownershipViolationMode}: a worker that reaches the shared vanilla section/index storage would
     * race the server thread even when violations are only logged or disabled. It is also independent of
     * the installed manager, so a worker that is still unwinding after the manager or engine was removed
     * is still rejected.
     * <p>
     * Every non-worker caller - the server thread, the watchdog thread, the client level and any ordinary
     * thread - passes through and keeps the vanilla behavior. The mechanics-only query handoff on
     * {@link RegionThreadingEngine} is not a way past this check: it runs the query on the server thread,
     * so the check is evaluated there.
     * <p>
     * This overload carries no entity, so it never consults an entity tick lease: it is the storage-level
     * guard, and a worker that reaches level storage, entity admission or the legacy entity query surface is
     * rejected by it even while a lease owns the entities it ticks. The region-scoped range query of a leased
     * worker does not reach this guard; it reads only the leased region's physical bucket through
     * {@link #queryEntitiesInLeasedRegion(ServerLevel, Entity, AABB, Predicate)}.
     */
    public static void assertServerEntityStorageAccess(String operation) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess(operation);
        }
    }

    /**
     * Rejects server-owned entity storage access of a server-level entity issued from a native region
     * worker, unless the entity tick lease of the current thread grants this mutation of this entity.
     * <p>
     * The entity's level, not {@code isAddedToLevel()}, decides whether its storage is server owned. An
     * entity that has not been registered yet is still backed by the server level's storage.
     * <p>
     * The lease exception is narrow: it is decided by
     * {@link RegionEntityOwnershipScope#grantsEntityMutation(ServerLevel, Entity, String)} and covers only
     * the vanilla entity mutations a group may own. Removal, level admission, the cross-level teleport,
     * subclass mutations with extra side effects and every operation without an entity argument still reach
     * {@link #assertServerEntityStorageAccess(String)}, which never consults a lease. The position change of
     * an entity that is added to a level is granted only when the lease can defer its section publication
     * and its target chunk acquisition to the batch barrier, which is the job of
     * {@link #deferEntitySectionUpdate(Entity, EntityInLevelCallback, double, double, double)}. Every
     * non-worker caller is unaffected and keeps the vanilla behavior.
     */
    public static void assertServerEntityStorageAccess(Entity entity, String operation) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return;
        }
        if (RegionEntityOwnershipScope.grantsEntityMutation(level, entity, operation)) {
            return;
        }
        RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess(operation);
    }

    /**
     * Defers the server-owned level effects of a worker entity move to the entity batch barrier.
     * <p>
     * {@code Entity.setPosRaw} of an entity that is added to a level reaches two server-owned level effects
     * after it wrote the position and bounds: the {@link EntityInLevelCallback#onMove()} publication of the
     * entity section and the acquisition of the target chunk. A native region worker must run neither, so
     * this hook records both for the barrier of the worker's entity batch, which applies them on the server
     * thread from the entity's final position. The caller then skips the two inline effects.
     * <p>
     * Only a native region worker whose active lease owns {@code entity}, whose entity is added to a level
     * and whose batch still accepts deferred section updates defers anything. The server thread, the client,
     * every ordinary thread, a foreign entity and a lease that outlived its batch answer {@code false} and
     * keep the inline vanilla effect.
     *
     * @param entity   the entity whose position changed
     * @param callback the level callback the inline move would have invoked
     * @param x        the x coordinate the move reached
     * @param y        the y coordinate the move reached
     * @param z        the z coordinate the move reached
     * @return {@code true} when the caller must skip the section publication and the target chunk
     *         acquisition, because the barrier applies them instead
     */
    public static boolean deferEntitySectionUpdate(Entity entity, EntityInLevelCallback callback, double x, double y, double z) {
        return RegionEntityOwnershipScope.deferEntitySectionUpdate(entity, callback, x, y, z);
    }

    /**
     * Rejects a recorded transfer operation that runs on any thread except the server thread owning
     * {@code level}.
     * <p>
     * A recorded transfer reads the level's identity ledger, validates the recorded riding graph against
     * the region index and writes the recorded mapping, so it belongs to the same logical server thread
     * as every other entity storage mutation. The owner is the {@link MinecraftServer} of the level itself
     * ({@link MinecraftServer#isSameThread()}), not the first caller, the installed engine, or a thread
     * name: an ordinary background thread that never touched the worker pool is rejected by the same
     * check. The check fails closed when the level has no owning server.
     * <p>
     * It runs after {@link #assertServerEntityStorageAccess(String)}, so a native region worker always
     * reports that violation first with the exact operation name.
     *
     * @throws IllegalStateException when the current thread is not the level's server thread, or when the
     *                               level has no owning server
     */
    public static void assertServerThreadEntityTransfer(ServerLevel level, String operation) {
        final MinecraftServer server = level.getServer();
        if (server == null) {
            throw new IllegalStateException("Recorded entity transfer operation has no owning server: " + operation);
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Recorded entity transfer operation must run on the server thread: "
                    + operation + " was called from thread '" + Thread.currentThread().getName() + "'");
        }
    }

    /**
     * {@return true when a legacy entity query issued on the current thread has to be rejected}
     * <p>
     * Production region workers never receive live entity storage: the legacy getters return live entity
     * references or run caller predicates/consumers, so a completed handoff is not a safety boundary for
     * them. The check only depends on the worker scope, so it keeps rejecting while a worker unwinds after
     * the manager was removed. The mechanics seam on {@link RegionThreadingEngine} re-enables the handoff
     * for transport tests only; it is never enabled in production.
     */
    public static boolean shouldRejectLegacyEntityQuery() {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return !(threading instanceof RegionThreadingEngine engine && engine.isLegacyEntityQueryHandoffEnabled());
    }

    /**
     * Rejects a legacy entity query before it runs a predicate, a consumer or returns a live reference.
     */
    public static void rejectLegacyEntityQuery(String operation) {
        RegionEntityOwnershipScope.recordRejectedQuery();
        RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess(operation);
    }

    /**
     * Runs an entity range query against the entity tick lease of the current native region worker.
     * <p>
     * The contract is clamp and count: the query sees only the entities stored in the leased region, a box
     * that extends past the region edge is clamped to the lease, and the truncated counter increments. Only
     * the physical bucket of the lease is read; another region's storage is never consulted.
     *
     * @return the region-scoped result, or {@code null} when the current thread may not run a region-scoped
     *         query and the caller has to keep the legacy guard
     */
    @Nullable
    public static List<Entity> queryEntitiesInLeasedRegion(ServerLevel level, @Nullable Entity excluded, AABB box, Predicate<? super Entity> predicate) {
        return RegionScopedEntityQuery.tryQuery(level, excluded, box, predicate);
    }

    /**
     * Runs a typed entity range query against the entity tick lease of the current native region worker and
     * appends to {@code result}. See
     * {@link #queryEntitiesInLeasedRegion(ServerLevel, Entity, AABB, Predicate)} for the contract.
     *
     * @return {@code true} when the query ran region-scoped, {@code false} when the caller has to keep the
     *         legacy guard
     */
    public static <T extends Entity> boolean queryEntitiesInLeasedRegion(
            ServerLevel level, EntityTypeTest<Entity, T> typeTest, AABB box, Predicate<? super T> predicate, List<? super T> result, int max) {
        return RegionScopedEntityQuery.tryQuery(level, typeTest, box, predicate, result, max);
    }

    /**
     * Rejects a region-scoped section access that does not match the active lease of the current thread.
     * <p>
     * The level calls this before it reads the physical bucket of the requested region, so the internal
     * region-scoped section accessor cannot be used by a worker to read a neighbouring region.
     */
    public static void assertLeasedEntityQuery(ServerLevel level, int regionX, int regionZ) {
        if (!RegionEntityOwnershipScope.ownsQueryRegion(level, regionX, regionZ)) {
            RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess("ServerLevel.forEachAccessibleNonEmptySectionInRegion");
        }
    }

    /**
     * {@return true when an entity range query on the current thread has to be handed to the server
     * thread because the thread is a region tick worker}
     * <p>
     * The check is a thread-local lookup on the server thread and does not allocate, so the vanilla
     * single-threaded path pays no lock or queue cost. This transport path is only reachable through the
     * internal mechanics seam; production workers are rejected by
     * {@link #shouldRejectLegacyEntityQuery()} first.
     */
    public static boolean shouldHandOffEntityQuery() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.isRegionTickBatchWorkerThread();
    }

    /**
     * Runs a complete computation on the server thread while the current region tick worker waits for it.
     * <p>
     * The computation has to contain everything that touches unregionized shared state, including the
     * consumer of any entity query. The worker receives the exact result or exception and never continues
     * against the shared state itself. Outside a region tick batch the computation runs inline.
     */
    public static <T> T runOnServerThread(ServerLevel level, java.util.function.Supplier<T> computation) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.runOnServerThread(level, computation);
        }
        return computation.get();
    }

    /**
     * Runs one complete entity range query on the server thread when the caller is a region tick worker.
     * <p>
     * The whole query, including its predicate and the consumer that fills the result list, executes on
     * the server thread against the authoritative section storage. The worker only receives the finished
     * snapshot list; it never iterates the live storage.
     */
    public static <T> T runEntityQueryOnServerThread(ServerLevel level, java.util.function.Supplier<T> query) {
        return runOnServerThread(level, query);
    }

    /**
     * Wraps the entity getter of {@code level} with the server-owned entity storage boundary.
     * <p>
     * The vanilla section/index storage stays single-threaded and is owned by the server thread, so the
     * by-id, by-uuid, type, AABB and {@code getAll} lookups must not run on a worker. In production the
     * wrapper rejects every legacy query issued from a native region worker before its predicate, consumer
     * or result escapes; it never hands a live entity reference or a caller callback across threads.
     * <p>
     * Every non-worker caller runs inline with the vanilla semantics, including the whole server thread and
     * the case where no region threading is installed at all. The only handoff left is the internal
     * mechanics seam ({@code RegionThreadingEngine.enableLegacyEntityQueryHandoffForTesting()}), which runs
     * the complete query on the server thread for transport tests; production never enables it.
     */
    public static LevelEntityGetter<Entity> wrapEntityGetter(ServerLevel level, LevelEntityGetter<Entity> delegate) {
        return new RegionAwareEntityGetter(level, delegate);
    }

    /**
     * Starts collecting the entities of the current level tick that should tick on region workers.
     * <p>
     * The call opens the slice of the opt-in entity tick attribution, which is closed by
     * {@link #runEntityTickPlan(RegionEntityTickPlan)} and stays disabled unless a diagnostics run enables
     * it.
     *
     * @param inEntityTickingRange the vanilla entity ticking range predicate, evaluated on the server thread
     * @param barrier              waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when entity ticks have to stay on the server thread
     */
    @Nullable
    public static RegionEntityTickPlan beginEntityTickPlan(ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        RegionEntityTickAttribution.beginSlice();
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginEntityTickPlan(level, inEntityTickingRange, barrier);
        }
        return null;
    }

    /**
     * Ticks the entities collected by {@code plan} on region workers and waits for the batch.
     */
    public static void runEntityTickPlan(@Nullable RegionEntityTickPlan plan) {
        try {
            if (plan != null) {
                plan.executeAndAwait();
            }
        } finally {
            // The slice covers the whole entity pass, including a failing batch: the attribution only
            // measures, so it must not lose the slice of a tick that threw.
            RegionEntityTickAttribution.endSlice();
        }
    }

    /**
     * Starts collecting the block-entity tickers of the current level tick that should tick on region
     * workers.
     * <p>
     * The call opens the slice of the opt-in block-entity tick attribution, which is closed by
     * {@link #runBlockEntityTickPlan(RegionBlockEntityTickPlan)} and stays disabled unless a diagnostics
     * run enables it.
     *
     * @param barrier waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when block-entity ticks have to stay on the server thread
     */
    @Nullable
    public static RegionBlockEntityTickPlan beginBlockEntityTickPlan(ServerLevel level, RegionTickBarrier barrier) {
        RegionBlockEntityTickAttribution.beginSlice();
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginBlockEntityTickPlan(level, barrier);
        }
        return null;
    }

    /**
     * Ticks the block-entity tickers collected by {@code plan} on region workers and waits for the batch.
     */
    public static void runBlockEntityTickPlan(@Nullable RegionBlockEntityTickPlan plan) {
        try {
            if (plan != null) {
                plan.executeAndAwait();
            }
        } finally {
            // The slice covers the whole ticker pass, including a failing batch: the attribution only
            // measures, so it must not lose the slice of a tick that threw.
            RegionBlockEntityTickAttribution.endSlice();
        }
    }

    /**
     * Creates the dispatcher that executes the scheduled ticks of one phase on region workers.
     *
     * @return the dispatcher, or {@code null} when the scheduled ticks have to stay on the server thread
     */
    @Nullable
    public static <T> LevelTicks.TickDispatcher<T> scheduledTickDispatcher(ServerLevel level, ScheduledTickPhase phase, RegionTickBarrier barrier) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginScheduledTickDispatcher(level, phase, barrier);
        }
        return null;
    }

    /**
     * {@return the random source of the scheduled tick group executing on the current thread, or
     * {@code null} outside a worker group}
     */
    @Nullable
    public static RandomSource scheduledTickRandom() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.scheduledTickRandom();
        }
        return null;
    }

    /**
     * Starts collecting the block and fluid random ticks of the current level tick that should run on
     * region workers.
     *
     * @param barrier waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when random ticks have to stay on the server thread
     */
    @Nullable
    public static RegionRandomTickPlan beginRandomTickPlan(ServerLevel level, RegionTickBarrier barrier) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginRandomTickPlan(level, barrier);
        }
        return null;
    }

    /**
     * Ticks the random ticks collected by {@code plan} on region workers and waits for the batch.
     */
    public static void runRandomTickPlan(@Nullable RegionRandomTickPlan plan) {
        if (plan != null) {
            plan.executeAndAwait();
        }
    }

    /**
     * {@return the random source of the random tick group executing on the current thread, or
     * {@code null} outside a random tick worker group}
     */
    @Nullable
    public static RandomSource randomTickRandom() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.randomTickRandom();
        }
        return null;
    }

    /**
     * {@return the server-owned queue a region tick worker has to use for mutations, or {@code null}
     * when the current thread is not executing a region tick worker group}
     * <p>
     * Scheduled block/fluid ticks and random ticks share this queue. The engine drains it on the server
     * thread after every batch, so a worker never mutates either {@code LevelTicks} instance concurrently.
     */
    @Nullable
    public static java.util.Queue<Runnable> regionTickMutationQueue() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.regionTickMutationQueue();
        }
        return null;
    }

    /**
     * Wraps the level random so region tick workers draw from their region random instead of the shared
     * level random. When no region worker slice is enabled, the vanilla random is returned unchanged.
     */
    public static RandomSource wrapLevelRandom(RandomSource vanilla) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine
                && (engine.isScheduledTickWorkerExecutionEnabled() || engine.isRandomTickWorkerExecutionEnabled())) {
            return new RegionAwareRandomSource(vanilla, engine);
        }
        return vanilla;
    }

    /**
     * {@return true when a region tick worker is executing and the current thread owns the region
     * containing {@code pos}}
     * <p>
     * Used by the block-entity lookup so scheduled tick and random tick callbacks can read a block entity
     * of their own region. Cross-region and other off-thread access still returns {@code null}.
     */
    public static boolean isRegionTickOwnedByCurrentThread(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.currentRegionTickRandom() != null
                && engine.isOwnedByCurrentRegion(serverLevel, pos);
    }

    /**
     * Runs {@code task} in the current level tick bridge context.
     */
    public static void runLevelTickTask(ServerLevel level, Runnable task) {
        final Optional<RegionThreading> threading = RegionThreadingManager.get();
        if (threading.isPresent() && threading.get() instanceof RegionThreadingEngine engine) {
            engine.runInLevelTick(level, task);
        } else {
            task.run();
        }
    }

    /**
     * Drops queued region work for {@code level} during level unload.
     */
    public static void clearLevelRegionTasks(ServerLevel level) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.clearLevelRegionTasks(level);
            }
        });
    }

    /**
     * Tracks a loaded chunk section in the native regionizer facade.
     */
    public static void addRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.addRegionSection(level, chunkX, chunkZ);
            }
        });
    }

    /**
     * Removes a loaded chunk section from the native regionizer facade.
     */
    public static void removeRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.removeRegionSection(level, chunkX, chunkZ);
            }
        });
    }

    /**
     * Tracks an entity in the native regionizer facade.
     */
    public static void addRegionEntity(Entity entity) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.addRegionEntity(entity);
            }
        });
    }

    /**
     * Removes an entity from the native regionizer facade.
     */
    public static void removeRegionEntity(Entity entity) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.removeRegionEntity(entity);
            }
        });
    }

    /**
     * Updates an entity's current region in the native regionizer facade.
     */
    public static void moveRegionEntity(Entity entity) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.moveRegionEntity(entity);
            }
        });
    }

    /**
     * Records a cross-region entity transfer decision in the installed engine without mutating any index.
     * <p>
     * This is the bridge for a caller that owns the vanilla entity, world and network side effects of a
     * transfer: it records the destination region first, performs the vanilla sequence, and commits the
     * token at a barrier with {@link #commitEntityRegionTransfer(RegionEntityTransferToken)}. The token
     * records the identity epoch and both regions at decision time; the commit never re-reads the live
     * position.
     * <p>
     * The bookmark does not interrupt the vanilla path for a normal server-thread caller: a destination in
     * another level, a riding graph that spans levels, an untracked entity or a full journal answer
     * {@code null} instead of throwing, and the caller keeps the vanilla behavior. Two callers are rejected
     * with an {@link IllegalStateException} instead: a native region worker, which names the operation, and
     * any other thread that is not the server thread owning the level; see
     * {@link #assertServerThreadEntityTransfer(ServerLevel, String)}.
     *
     * @return the recorded token, or {@code null} when region threading is not installed or no transfer
     *         can be recorded; in that case the caller keeps the vanilla path
     */
    @Nullable
    public static RegionEntityTransferToken recordEntityRegionTransfer(Entity entity, RegionCoordinate destinationRegion) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.recordEntityRegionTransfer(entity, destinationRegion);
        }
        return null;
    }

    /**
     * Commits a recorded cross-region entity transfer on the server thread.
     * <p>
     * A caller on another thread is rejected before the journal entry is claimed; see
     * {@link #assertServerThreadEntityTransfer(ServerLevel, String)}. With region threading not installed
     * this is a no-op and answers
     * {@link RegionEntityTransferResult#DISABLED}, which leaves the caller's vanilla behavior and return
     * values unchanged.
     */
    public static RegionEntityTransferResult commitEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.commitEntityRegionTransfer(token);
        }
        return RegionEntityTransferResult.DISABLED;
    }

    /**
     * Aborts a recorded cross-region entity transfer that will never be committed.
     * <p>
     * A caller that gave up on the vanilla side of a transfer releases its pending decision here instead of
     * leaving it in the journal until the pending bound is exhausted. The abort never mutates a region
     * mapping and is idempotent: a terminal token answers its recorded result. A caller on another thread
     * is rejected before the pending entry is released. With region threading not installed this answers
     * {@link RegionEntityTransferResult#DISABLED}.
     */
    public static RegionEntityTransferResult abortEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.abortEntityRegionTransfer(token);
        }
        return RegionEntityTransferResult.DISABLED;
    }

    /**
     * Runs {@code task} in the entity's current region context when the native engine is installed.
     */
    public static void runEntityTickTask(Entity entity, Runnable task) {
        final Optional<RegionThreading> threading = RegionThreadingManager.get();
        if (threading.isPresent() && threading.get() instanceof RegionThreadingEngine engine) {
            engine.runInEntityRegion(entity, task);
        } else {
            task.run();
        }
    }

    /**
     * Runs {@code task} in the owning block region context when the native engine is installed.
     * <p>
     * Called once per block-entity ticker, so it reads the installed engine without allocating the
     * {@link Optional} of {@link RegionThreadingManager#get()}.
     */
    public static void runBlockTickTask(ServerLevel level, BlockPos pos, Runnable task) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.runInBlockRegion(level, pos, task);
        } else {
            task.run();
        }
    }

    /**
     * {@return a snapshot of internal diagnostics, if the native engine is installed}
     */
    public static Optional<RegionThreadingDiagnostics> diagnostics() {
        return RegionThreadingManager.get().flatMap(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                return Optional.of(engine.diagnostics());
            }
            return Optional.empty();
        });
    }
}
