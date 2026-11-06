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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.server.threading.region.LevelTickPhase;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickScope;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import net.neoforged.neoforge.server.threading.region.RegionEntityQueryCounters;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferResult;
import net.neoforged.neoforge.server.threading.region.RegionEntityTransferToken;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionMailbox;
import net.neoforged.neoforge.server.threading.region.RegionOwnershipViolationHandler;
import net.neoforged.neoforge.server.threading.region.RegionPlayerSnapshots;
import net.neoforged.neoforge.server.threading.region.RegionScopedEntityQuery;
import net.neoforged.neoforge.server.threading.region.RegionSpawnAttribution;
import net.neoforged.neoforge.server.threading.region.RegionSpawnPlan;
import net.neoforged.neoforge.server.threading.region.RegionSpawnSource;
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
    private static final java.util.concurrent.atomic.AtomicLong RAW_CHUNK_INTERNALS_ACCESSES = new java.util.concurrent.atomic.AtomicLong();

    /** Diagnostic access count only: obtaining raw internals is not proof that a caller mutated them. */
    public static void recordRawChunkInternalsAccess() {
        if (isNativeRegionWorkerThread()) {
            RAW_CHUNK_INTERNALS_ACCESSES.incrementAndGet();
        }
    }

    public static long rawChunkInternalsAccessCount() {
        return RAW_CHUNK_INTERNALS_ACCESSES.get();
    }

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
     * {@return the owner check a section manager built with {@code callbacks} installs}
     * <p>
     * A level supplies its own callbacks object ({@link ServerEntityStorageOwner}), so the manager it builds
     * for itself is guarded from construction on - without an installed engine, and while a worker is still
     * unwinding after the manager was removed. Every other caller (the client, a test, a mod that builds its
     * own manager) owns no level and keeps vanilla's single-threaded storage, so it gets the no-op check.
     * <p>
     * This is the level's construction seam, not its constructor: the manager's constructor keeps the
     * three-argument shape vanilla has and mods mix into.
     */
    public static PersistentEntitySectionManager.EntityStorageAccessCheck serverEntityStorageAccessCheck(
            LevelCallback<?> callbacks) {
        return callbacks instanceof ServerEntityStorageOwner
                ? RegionThreadingHooks::assertServerEntityStorageAccess
                : operation -> {};
    }

    /**
     * {@return the physical section storage layout a section manager built with {@code callbacks} partitions with}
     * <p>
     * Only a level assigns the section storage a region partition, and it is the same layout
     * {@link #entitySectionStorageLayout()} captures at level construction; a manager built by any other caller
     * stays on the legacy section map.
     */
    public static RegionLayout serverEntityStorageLayout(LevelCallback<?> callbacks) {
        return callbacks instanceof ServerEntityStorageOwner
                ? entitySectionStorageLayout()
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
     * {@link #deferEntitySectionUpdate(Entity, EntityInLevelCallback, double, double, double)}. The position
     * writes of an entity that is <em>not</em> added to a level are granted as field writes, so a worker can
     * construct and configure an entity it is about to admit; the admission itself is not granted, it is
     * deferred to the same barrier by {@link #deferEntityAddition(ServerLevel, Entity)}. Every non-worker
     * caller is unaffected and keeps the vanilla behavior.
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
     * Rejects the admission of a new entity into a level issued from a native region worker.
     * <p>
     * Adding an entity writes the level's UUID admission set, the visible lookup, the entity section and the
     * ticking list, so it is server-owned storage and stays rejected for a worker unless the entity tick
     * lease records the admission for the barrier of its batch; see
     * {@link #deferEntityAddition(ServerLevel, Entity)}. The rejection is counted in
     * {@link RegionThreadingDiagnostics#entityAddRejectedOnWorker()} before it throws, so the rate of entity
     * additions a worker attempted and could not defer - a spawn outside its leased region, an entity that is
     * already added or removed, a closed seam - is observable next to the removal counters. A spawn whose new
     * entity is constructed on the worker outside the lease is rejected one step earlier, at the position
     * write of the constructor, and is counted there.
     *
     * @throws IllegalStateException when the caller is a native region worker
     */
    public static void assertServerEntityAdditionAccess(String operation) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return;
        }
        RegionOwnershipViolationHandler.recordEntityAdditionRejection();
        RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess(operation);
    }

    /**
     * Defers the admission of a new entity to the entity batch barrier of the current native region worker.
     * <p>
     * Adding an entity writes the level's UUID admission set, the visible lookup, the entity section, the
     * ticking list, the tracking and the region index, so a native region worker must not run it. This hook
     * records the whole riding graph of {@code entity} for the barrier of the worker's entity batch instead;
     * the barrier runs the unmodified {@code ServerLevel.addEntity} on the server thread, so every storage
     * effect is written exactly like a server-thread spawn.
     * <p>
     * <b>Contract change:</b> on a native region worker whose lease covers the entity's position,
     * {@code ServerLevel.addFreshEntity} answers "accepted for this tick", not "already in the world". The
     * entity is not in the level for the rest of the worker tick - {@code isAddedToLevel()} stays
     * {@code false}, the level's lookups do not find it and it does not tick - and the barrier admits it
     * afterwards. An admission the barrier cannot apply is <b>dropped</b>: the entity is then lost,
     * because it was never in the level and nothing retries it. Every recorded admission is therefore counted
     * as applied or dropped ({@link RegionThreadingDiagnostics#deferredEntityAdditionsApplied()},
     * {@link RegionThreadingDiagnostics#deferredEntityAdditionsDropped()}), never silently skipped. The
     * construction events of the entity ({@code EntityEvent.EntityConstructing}, {@code EntityEvent.Size})
     * fire on the worker thread as a consequence; that exposure is documented and not moved.
     * <p>
     * Only a native region worker whose active lease covers the entity's position, whose entity and every
     * entity of its riding graph are not added to a level and not removed, and whose entity is in the lease
     * level records anything. The server thread, the client, every ordinary thread, a worker without a lease,
     * an entity outside the leased region, a member of the graph that is already in the level and a lease that
     * outlived its batch answer {@code false}, and the caller keeps the storage refusal.
     *
     * @param level  the level the entity is being added to
     * @param entity the entity that is being added
     * @return {@code true} when the caller must skip the vanilla admission, because the barrier admits the
     *         entity instead
     */
    public static boolean deferEntityAddition(ServerLevel level, Entity entity) {
        return RegionEntityOwnershipScope.deferEntityAddition(level, entity);
    }

    /**
     * Defers a portal transition of a worker-ticked entity to the entity batch barrier.
     * <p>
     * {@code Entity#handlePortal} resolves the destination of a completed portal time through
     * {@code PortalForcer}, whose search reads the {@code PoiManager} of the destination level (and loads the
     * chunks its search covers). Every entity type reaches that call from its own tick, so a per-type
     * declaration cannot keep it off a worker. This hook records the transition for the barrier of the
     * worker's entity batch instead, and the barrier resolves and applies it on the server thread in the same
     * tick, before it applies the other deferred effects of that tick.
     * <p>
     * Only a native region worker whose active lease owns {@code entity} in the lease level and whose batch
     * still accepts deferred transitions records anything, and an entity is recorded at most once per tick.
     * The server thread, the client, every ordinary thread, a foreign entity and a lease that outlived its
     * batch answer {@code false} and keep the inline vanilla transition.
     *
     * @param level  the level the transition happens in
     * @param entity the entity that completed its portal time
     * @return {@code true} when the caller must skip the inline transition, because the barrier runs it
     *         instead
     */
    public static boolean deferPortalTransition(ServerLevel level, Entity entity) {
        return RegionEntityOwnershipScope.deferPortalTransition(level, entity);
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
     * {@return true when a position write of {@code entity} has to skip the inline section publication and
     * target chunk acquisition because the entity was already removed}
     * <p>
     * A removed entity still finishes its own tick in vanilla - a mob killed in the same tick runs the rest of
     * its AI and reaches {@code Entity.move} - but it has no section left to publish and is leaving the level.
     * A native region worker that leases the entity skips both server-owned effects instead of running them
     * inline; the barrier records nothing for it, so no deferred update is lost. Every other caller keeps the
     * vanilla inline behaviour.
     */
    public static boolean skipsSectionUpdateOfRemovedEntity(Entity entity) {
        return RegionEntityOwnershipScope.skipsSectionUpdateOfRemovedEntity(entity);
    }

    /**
     * Defers the server-owned storage effect of a worker entity removal to the entity batch barrier.
     * <p>
     * {@code Entity.setRemoved} sets the entity's own removal state and then runs its level callback, which
     * removes the entity from the level's section storage, stops its tracking and ticking, retires its
     * identity in the ledger and publishes the departure that retires its region mapping. A native region
     * worker must run none of that, so this hook records the removal for the barrier of the worker's entity
     * batch, which applies the callback on the server thread. The entity is already removed for the rest of
     * the worker tick, because {@link Entity#isRemoved()} reads the state the caller set.
     * <p>
     * Only a native region worker whose active lease owns {@code entity} and whose batch still accepts
     * deferred removals records anything. The server thread, the client, every ordinary thread, a foreign
     * entity, a lease that outlived its batch and an entity that is not added to a level answer
     * {@code false} and keep the inline vanilla callback.
     *
     * @param entity   the entity that is being removed
     * @param callback the entity's level callback, the exact object the inline vanilla removal would invoke
     * @param reason   the removal reason the entity was marked with
     * @return {@code true} when the caller must skip the level callback, because the barrier runs it instead
     */
    public static boolean deferEntityRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        return RegionEntityOwnershipScope.deferEntityRemoval(entity, callback, reason);
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
     * Rejects a legacy entity query of one operation before it runs a predicate, a consumer or returns a live
     * reference.
     * <p>
     * The rejection is counted per query operation next to the aggregate rejection counter, so a crash report
     * shows which entry point a worker reached and how often it happened before the guard refused it.
     *
     * @param operation the entry point a crash report has to name
     * @param path      the operation the per-operation counters record the refusal under
     */
    public static void rejectLegacyEntityQuery(String operation, RegionEntityQueryCounters.Operation path) {
        RegionEntityOwnershipScope.recordRejectedQuery();
        RegionEntityQueryCounters.record(path, RegionEntityQueryCounters.Path.REFUSED);
        RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess(operation);
    }

    /**
     * {@return the players of {@code level} a native region worker may iterate, or {@code null} when the caller
     * has to keep the level's live player list}
     * <p>
     * A player is an always-ticking entity that only the server thread ticks, so the level's player list is
     * server-owned storage and a player lookup is a level-wide question a region cannot answer. The server
     * thread therefore publishes an immutable snapshot of the list once per tick, at the head of the level
     * tick, and a worker answers every player lookup - {@code getNearestPlayer}, {@code getPlayerByUUID},
     * {@code getNearbyPlayers}, {@code hasNearbyAlivePlayer} - from it. The snapshot is at most one tick old:
     * a player who joined, left or changed dimension after the publication is not in it.
     * <p>
     * The server thread, the client and every ordinary thread get {@code null} and keep the live list, which is
     * the vanilla behaviour. A native worker never gets {@code null}: it gets the published list, or - when the
     * level has no snapshot - the same {@link IllegalStateException} every other storage guard throws, counted
     * as a refused player answer. Reading the live {@code ArrayList} would be exactly the server-owned storage
     * access this bridge exists to keep away from a worker, so there is no fallback for it.
     */
    @Nullable
    public static List<ServerPlayer> playersOfLevel(ServerLevel level) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return null;
        }
        final RegionPlayerSnapshots.Snapshot snapshot = RegionPlayerSnapshots.of(level);
        if (snapshot == null) {
            // The guard counts the refusal and throws; a worker never reaches the live list.
            rejectLegacyEntityQuery("ServerLevel.players()", RegionEntityQueryCounters.Operation.PLAYERS);
            return List.of();
        }
        RegionEntityQueryCounters.record(RegionEntityQueryCounters.Operation.PLAYERS, RegionEntityQueryCounters.Path.PUBLISHED);
        return snapshot.players();
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
     * Starts the region-scoped chunk tick of the current level tick.
     * <p>
     * The call opens the slice of the opt-in chunk tick attribution, which is closed by
     * {@link #runChunkTickPlan(RegionChunkTickPlan)} and stays disabled unless a diagnostics run enables it.
     *
     * @param randomTickSpeed the level's random tick speed, read once by the level for the whole tick
     * @param barrier         waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when the chunk tick has to stay on the server thread
     */
    @Nullable
    public static RegionChunkTickPlan beginChunkTickPlan(ServerLevel level, int randomTickSpeed, RegionTickBarrier barrier) {
        RegionChunkTickAttribution.beginSlice(level);
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginChunkTickPlan(level, randomTickSpeed, barrier);
        }
        return null;
    }

    /**
     * Ticks the chunks collected by {@code plan} on region workers and waits for the batch.
     */
    public static void runChunkTickPlan(@Nullable RegionChunkTickPlan plan) {
        try {
            if (plan != null) {
                plan.executeAndAwait();
            }
        } finally {
            // The slice covers the whole chunk tick pass, including a failing batch: the attribution only
            // measures, so it must not lose the slice of a tick that threw.
            RegionChunkTickAttribution.endSlice();
        }
    }

    /**
     * {@return the random source of the chunk tick executing on the current thread, or {@code null} outside a
     * chunk tick worker group}
     * <p>
     * A chunk tick installs the random source of its own chunk, so the block code it runs and the position
     * selection of the level draw from the chunk's source instead of the shared level sources.
     */
    @Nullable
    public static RandomSource chunkTickRandom() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.chunkTickRandom();
        }
        return null;
    }

    /**
     * {@return the position selection of the chunk tick executing on the current thread, or {@code null}
     * outside a chunk tick worker group}
     * <p>
     * {@code Level.getBlockRandomPos} selects a random tick position from the level-wide {@code randValue}
     * sequence, which one worker cannot share with another. Inside a chunk tick group the level selects the
     * position from this source instead, so the selection of a chunk is per-chunk deterministic.
     */
    @Nullable
    public static RandomSource chunkTickPositionRandom() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.chunkTickPositionRandom();
        }
        return null;
    }

    /**
     * Defers the lightning strike of one chunk tick to the server thread.
     * <p>
     * A chunk tick worker may not resolve a strike: the target comes from the POI manager and from entity
     * storage and can be in another region. Inside a chunk tick worker group the rolled position is recorded
     * and the server thread resolves it at the batch barrier.
     *
     * @return {@code true} when the strike was deferred, {@code false} when the caller has to resolve it inline
     */
    public static boolean deferChunkTickLightning(net.minecraft.core.BlockPos rolledPos) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferChunkTickLightning(rolledPos);
    }

    /**
     * {@return true when the installed engine captures cross-region border updates}
     * <p>
     * The switch is the engine's copy of {@code enableRegionThreadingBorderUpdates}. It decides whether a region
     * context collects neighbor updates through the vanilla collector or through the border-capturing one.
     */
    public static boolean borderUpdatesEnabled() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.borderUpdatesEnabled();
    }

    /**
     * Defers one neighbor update whose target belongs to another region.
     * <p>
     * Called from the region collector before the vanilla update reads or applies the target state. Outside a
     * region lease, with the switch off, or for a target of the current lease this returns {@code false} and the
     * vanilla path runs unchanged.
     *
     * @return {@code true} when the update was recorded for the target region's fixpoint pass
     */
    public static boolean deferBorderNeighborUpdate(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.level.block.Block block,
            BlockPos neighborPos, boolean movedByPiston) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.deferBorderNeighborUpdate(serverLevel, pos, block, neighborPos, movedByPiston);
    }

    /**
     * Defers one shape update whose target belongs to another region.
     *
     * @return {@code true} when the update was recorded for the target region's fixpoint pass
     */
    public static boolean deferBorderShapeUpdate(net.minecraft.world.level.Level level, net.minecraft.core.Direction direction,
            net.minecraft.world.level.block.state.BlockState state, BlockPos pos, BlockPos neighborPos, int updateFlags, int updateLimit) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.deferBorderShapeUpdate(serverLevel, direction, state, pos, neighborPos, updateFlags, updateLimit);
    }

    /**
     * Defers one direction of {@code Level.updateNeighbourForOutputSignal} whose target belongs to another
     * region.
     *
     * @return {@code true} when the comparator update was recorded for the target region's fixpoint pass
     */
    public static boolean deferBorderComparatorUpdate(net.minecraft.world.level.Level level, BlockPos pos, BlockPos sourcePos,
            net.minecraft.world.level.block.Block sourceBlock, net.minecraft.core.Direction direction) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.deferBorderComparatorUpdate(serverLevel, pos, sourcePos, sourceBlock, direction);
    }

    /**
     * Defers the unsaved-chunk mark of one block-entity change whose block entity belongs to another region.
     *
     * @return {@code true} when the change was recorded for the target region's fixpoint pass
     */
    public static boolean deferBorderBlockEntityChange(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferBorderBlockEntityChange(serverLevel, pos);
    }

    /**
     * Defers one block write whose position belongs to another region.
     * <p>
     * Called by {@code Level.setBlock} after the chunk lookup and before the mutation guard. A region worker that
     * writes outside its lease records the call here, and the target region runs the vanilla
     * {@code Level.setBlock} on its own worker at the barrier of the same tick, so the block-entity lifecycle,
     * the heightmaps, the light recheck and the neighbour propagation are the target's own. The recording
     * worker does not mutate the target chunk.
     * <p>
     * A caller that receives {@code true} reports success: the write is accepted, not applied yet. A callback
     * that reads the same position again in the same lease still observes the state before the write; the read
     * is counted through {@link #observeReadAfterDeferredBlockWrite(BlockPos)}.
     *
     * @return {@code true} when the write was recorded and the caller has to skip the inline mutation
     */
    public static boolean deferBorderBlockWrite(net.minecraft.world.level.Level level, BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state, int flags, int recursionLeft) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.deferBorderBlockWrite(serverLevel, pos, state, flags, recursionLeft);
    }

    /**
     * Counts one block state read of a position the current region wrote outside its lease during the current
     * lease.
     * <p>
     * Called from the block state read of a chunk that is not owned by the current region, i.e. exactly where the
     * deferred write would have been visible. The read itself is unaffected: this only makes the
     * read-after-write approximation observable.
     */
    public static void observeReadAfterDeferredBlockWrite(BlockPos pos) {
        observeReadAfterDeferredBlockWrite(pos.asLong());
    }

    /**
     * Counts one fluid state read of a position the current region wrote outside its lease during the current
     * lease, the packed-position form of {@link #observeReadAfterDeferredBlockWrite(BlockPos)}.
     */
    public static void observeReadAfterDeferredBlockWrite(int x, int y, int z) {
        observeReadAfterDeferredBlockWrite(BlockPos.asLong(x, y, z));
    }

    private static void observeReadAfterDeferredBlockWrite(long packedPos) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.observeReadAfterDeferredBlockWrite(packedPos);
        }
    }

    /**
     * Defers the light section status change of one block write of a region worker group to the server thread.
     * <p>
     * The light engine of a level is one engine with one set of queues and one light thread, so a region worker
     * of any slice records the change and the server thread applies it at the batch barrier, in the order the
     * body produced it.
     *
     * @param pos   the written position, already immutable
     * @param empty whether the light section of the position holds only air now
     * @return {@code true} when the change was deferred, {@code false} when the caller has to call the light
     *         engine itself
     */
    public static boolean deferLightSectionStatus(net.minecraft.core.BlockPos pos, boolean empty) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferLightSectionStatus(pos, empty);
    }

    /**
     * Defers the light recheck of one block write of a region worker group to the server thread.
     *
     * @param pos the written position, already immutable
     * @return {@code true} when the recheck was deferred, {@code false} when the caller has to call the light
     *         engine itself
     */
    public static boolean deferLightCheck(net.minecraft.core.BlockPos pos) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferLightCheck(pos);
    }

    /**
     * Defers the block update publication of one block write of a region worker group to the server thread.
     * <p>
     * The publication is level-wide: it marks the changed sections of the chunk holder, invalidates the
     * level-wide path type cache and recomputes the paths of every navigating mob of the level, which can live
     * in any region. Inside a worker group of any slice the write is recorded and the server thread publishes it
     * at the batch barrier.
     *
     * @return {@code true} when the publication was deferred, {@code false} when the caller has to publish it
     *         itself
     */
    public static boolean deferBlockUpdate(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
            net.minecraft.world.level.block.state.BlockState newState, int flags) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferBlockUpdate(pos, oldState, newState, flags);
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
     * Starts the region-scoped natural spawning of the current level tick.
     * <p>
     * The call opens the slice of the opt-in spawn attribution, which is closed by
     * {@link #runSpawnPlan(RegionSpawnPlan)} and stays disabled unless a diagnostics run enables it.
     *
     * @param source          the server-owned reads the plan needs from the level's chunk source
     * @param spawnFriendlies whether the friends categories may spawn in this tick
     * @param spawnEnemies    whether the enemy categories may spawn in this tick
     * @param spawnPersistent whether the persistent categories may spawn in this tick
     * @param barrier         waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when natural spawning has to stay on the server thread
     */
    @Nullable
    public static RegionSpawnPlan beginSpawnPlan(ServerLevel level, RegionSpawnSource source, boolean spawnFriendlies,
            boolean spawnEnemies, boolean spawnPersistent, RegionTickBarrier barrier) {
        RegionSpawnAttribution.beginSlice(level);
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.beginSpawnPlan(level, source, spawnFriendlies, spawnEnemies, spawnPersistent, barrier);
        }
        return null;
    }

    /**
     * Spawns for the chunks collected by {@code plan} on region workers and waits for the batch.
     */
    public static void runSpawnPlan(@Nullable RegionSpawnPlan plan) {
        try {
            if (plan != null) {
                plan.executeAndAwait();
            }
        } finally {
            // The slice covers the whole spawn pass, including a failing batch: the attribution only measures,
            // so it must not lose the slice of a tick that threw.
            RegionSpawnAttribution.endSlice();
        }
    }

    /**
     * {@return the vanilla spawn-gate answer for {@code pos} of {@code level} inside a spawn worker group, or
     * {@code null} when the caller has to ask the level itself}
     * <p>
     * {@code ServerLevel.isNaturalSpawningAllowed} reads the entity manager's chunk visibility map, which only the
     * server thread may touch. A spawn worker therefore answers from the snapshot the plan recorded for its region.
     */
    @Nullable
    public static Boolean spawnPositionAllowed(ServerLevel level, BlockPos pos) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            return engine.spawnPositionAllowed(level, pos);
        }
        return null;
    }

    /**
     * Counts one mob category a natural spawn attempt was refused for because the mob cap was reached.
     * <p>
     * Inside a region spawn worker group this is the level-wide cap, read from the shared counter of the tick; on
     * the server thread it is the vanilla body, which the vanilla spawn diagnostics already report, so nothing is
     * counted there.
     */
    public static void countSpawnCapRefusal() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.countSpawnCapRefusal();
        }
    }

    /**
     * Defers the admission of one naturally spawned entity to the barrier of the spawn batch.
     * <p>
     * The hook is the spawn half of the deferred entity admission: a spawn worker constructs and configures the
     * mob - the position writes of a pending entity are field writes - and the barrier runs the unmodified
     * {@code ServerLevel.addEntity} for the riding graph on the server thread.
     *
     * @return {@code true} when the admission was recorded and the caller must skip the vanilla admission,
     *         {@code false} when the caller has to admit the entity itself
     */
    public static boolean deferSpawnAddition(ServerLevel level, net.minecraft.world.entity.Entity entity) {
        if (RegionEntityOwnershipScope.deferSpawnAddition(level, entity)) {
            return true;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            // Only counts inside a spawn worker group: on the server thread the caller admits the entity
            // itself, which is the vanilla path and not a refusal.
            engine.countRefusedSpawnAddition();
        }
        return false;
    }

    /**
     * Wraps the level random so region workers draw from the random source of their group instead of the
     * shared level random. When no worker slice that can read {@code level.random} is enabled, the vanilla
     * random is returned unchanged.
     */
    public static RandomSource wrapLevelRandom(RandomSource vanilla) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine
                && (engine.isEntityTickWorkerExecutionEnabled()
                        || engine.isScheduledTickWorkerExecutionEnabled()
                        || engine.isChunkTickWorkerExecutionEnabled()
                        || engine.isSpawnWorkerDispatchEffective())) {
            return new RegionAwareRandomSource(vanilla);
        }
        return vanilla;
    }

    /** Foreign scalar reads use a bounded seqlock; a refusal never replays a partially executed callback. */
    public static boolean requiresStableForeignRead(net.minecraft.world.level.chunk.LevelChunk chunk) {
        return isNativeRegionWorkerThread() && !isRegionTickOwnedByCurrentThread(chunk.getLevel(), chunk.getPos().getWorldPosition());
    }

    public static <T> T readStableForeignChunk(net.minecraft.world.level.chunk.LevelChunk chunk, java.util.function.Supplier<T> read) {
        for (int attempt = 0; attempt < 4096; attempt++) {
            final long before = chunk.chunkVersion();
            if ((before & 1L) == 0L) {
                T value = null;
                RuntimeException failure = null;
                try {
                    value = read.get();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
                java.lang.invoke.VarHandle.loadLoadFence();
                if (chunk.chunkVersion() == before) {
                    if (failure != null) {
                        throw failure;
                    }
                    final RegionThreading threading = RegionThreadingManager.currentOrNull();
                    if (threading instanceof RegionThreadingEngine engine && chunk.getLevel() instanceof ServerLevel level) {
                        engine.recordForeignChunkRead(level, chunk.getPos().x, chunk.getPos().z, before);
                    }
                    return value;
                }
            }
            Thread.onSpinWait();
        }
        throw net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.forUnstableRead(chunk.getPos().x, chunk.getPos().z);
    }

    public static boolean refuseForeignChunkMutation(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!isNativeRegionWorkerThread()) {
            return false;
        }
        if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk
                && isRegionTickOwnedByCurrentThread(levelChunk.getLevel(), chunk.getPos().getWorldPosition())) {
            return false;
        }
        // Interior admission covers ordinary vanilla work. This safety net refuses callbacks that reach
        // beyond their lease before any mutation, without replaying their already-applied effects.
        net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.countForeignWriteRefusal();
        return true;
    }

    /**
     * {@return true when a region execution context is running on the current thread and that context
     * holds the lease of the region containing {@code pos}}
     * <p>
     * Used by the block-entity lookup so every region group - a scheduled tick, a random tick, a block
     * entity tick, an entity tick and a border propagation pass - can read a block entity of its own
     * region. The check is the lease, not the random source of one slice, because a border propagation
     * pass runs comparator and piston reads without a scheduled tick random. Cross-region and other
     * off-thread access still returns {@code null} unless
     * {@link #isForeignRegionBlockEntityReadable} proves the owning region is quiescent.
     */
    public static boolean isRegionTickOwnedByCurrentThread(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.isOwnedByCurrentRegion(serverLevel, pos);
    }

    /**
     * {@return true when the current region context may read a block entity of another region because the
     * owning region holds no execution lease}
     * <p>
     * A comparator reads the analog output of the block behind it and a piston reads the moving block
     * entity in front of it; that block can be in the next region. A region of one phase is leased on the
     * server thread before the phase's groups are submitted and released when its group ends, so a region
     * without a lease cannot start another group before the current phase finishes. The read therefore
     * observes a state that its owner is not mutating. The block entity lookup returns {@code null} when
     * the region is running, because only its own worker may read it then.
     */
    public static boolean isForeignRegionBlockEntityReadable(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.isForeignRegionBlockEntityReadable(serverLevel, pos);
    }

    /**
     * {@return the published stand-in of a block entity the caller may not read live, or {@code null} when the
     * position is not a published border block entity}
     * <p>
     * This is the answer a refused foreign block-entity read receives instead of {@code null}: the immutable
     * values the server thread published for that position at the head of the tick. The stand-in is a value,
     * never the live block entity of the owning region, so a caller cannot read or mutate region state through
     * it; {@link net.neoforged.neoforge.server.threading.region.RegionSnapshotBlockEntity} lists exactly what it
     * answers and what it refuses. A level without an engine has no publication and keeps the vanilla
     * {@code null}.
     *
     * @param level the level the read was issued on
     * @param pos   the position of the block entity that was asked for
     */
    @Nullable
    public static net.minecraft.world.level.block.entity.BlockEntity snapshotBlockEntity(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return net.neoforged.neoforge.server.threading.region.RegionBlockEntitySnapshots.standIn(serverLevel, pos);
    }

    /**
     * Records the version of one chunk a region worker is about to read.
     * <p>
     * The chunk source of a level serves any loaded chunk to a region worker, including a chunk another region
     * is mutating. Block, fluid and heightmap scalar reads validate the chunk seqlock before returning data;
     * versionless native-worker reads are refused without replay. The observed version is also recorded per
     * region and pass, so propagation origins are reevaluated if their dependencies change later in the tick.
     * Non-replayable callbacks receive a consistent scalar value, not a whole-world transaction snapshot.
     * <p>
     * The call site is the worker chunk lookup, so the hook is on the read path of every block, fluid and block
     * entity read of a worker. It costs one static read on a server without region threading and one thread-local
     * read plus a chunk-coordinate comparison for a read of the caller's own region.
     *
     * @param level  the level of the chunk
     * @param chunkX the chunk x of the chunk that is handed to the worker
     * @param chunkZ the chunk z of the chunk that is handed to the worker
     * @param chunk  the chunk the worker is about to read
     */
    public static void recordForeignChunkRead(net.minecraft.world.level.Level level, int chunkX, int chunkZ,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (isNativeRegionWorkerThread() && !(chunk instanceof net.minecraft.world.level.chunk.LevelChunk)) {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.recordVersionlessChunkRead();
            }
            throw net.neoforged.neoforge.server.threading.region.RegionWorkerChunkUnavailableException.forVersionlessRead(chunkX, chunkZ);
        }
        if (!(threading instanceof RegionThreadingEngine engine) || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        engine.recordForeignChunkRead(serverLevel, chunkX, chunkZ,
                chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk ? levelChunk.chunkVersion() : -1L);
    }

    /**
     * Defers the block event of a region worker to the server thread's block event queue.
     * <p>
     * {@code ServerLevel.blockEvent} adds the event to one level-wide ordered set, and the event body of a
     * piston move or a note block plays through the level and can write the next region. A worker must not
     * mutate that set or run the body, so the event is recorded in the worker batch's server-owned mutation
     * queue and re-added on the server thread at the batch barrier, before vanilla's block event phase. The
     * event therefore still runs in the same tick and keeps its recording order.
     *
     * @return {@code true} when the caller must skip the inline add, because the barrier re-adds the event
     */
    public static boolean deferBlockEvent(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, int eventId,
            int eventParam) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.deferBlockEvent(level, pos, block, eventId, eventParam);
    }

    /**
     * {@return the chunk a game event dispatch visits, or {@code null} when it is not available}
     * <p>
     * The vanilla dispatch asks {@code ServerChunkCache.getChunkNow}, which answers {@code null} on every thread
     * but the server thread: a region worker that asked it would visit no listener at all and silently drop the
     * event. A worker therefore asks the chunk source for a chunk that is already available, which is the answer
     * the server thread gives too and never loads a chunk - the server thread is waiting at the batch barrier
     * while the worker runs, so a load could not complete.
     *
     * @param level  the level the event was posted on
     * @param chunkX the chunk column's X coordinate
     * @param chunkZ the chunk column's Z coordinate
     */
    public static net.minecraft.world.level.chunk.ChunkAccess gameEventChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (isNativeRegionWorkerThread()) {
            return level.getChunkSource().getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        }
        return level.getChunkSource().getChunkNow(chunkX, chunkZ);
    }

    /**
     * Counts one game event the level dispatches while a region batch of it is in flight.
     * <p>
     * {@code GameEventDispatcher.post} reaches every listener of the event's notification radius and every
     * listener writes its own block entity, so the dispatch is the one place where a region worker can reach
     * the state of another region without a block write. The count is the denominator of the capture counters
     * of {@link #captureCrossRegionGameEvent}; with the cross-region game event switch off it stays zero and
     * the dispatch is the vanilla one.
     */
    public static void recordGameEventPost(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.recordGameEventPost(level);
        }
    }

    /**
     * Captures one section column of a game event dispatch whose listeners belong to another region.
     * <p>
     * The dispatcher asks once per chunk column of the notification radius, before it reads the column's chunk
     * or its listener registries. A {@code true} answer means the column is not this worker's to visit: the
     * dispatcher skips it and the target region's worker replays it in the same tick, in recording order. The
     * caller must not read the column at all, because the listener registry of a foreign section is mutable
     * state of the region that owns it.
     *
     * @param level       the level the event was posted on
     * @param event       the posted game event
     * @param source      the position the event was posted from
     * @param context     the context of the post
     * @param chunkX      the chunk column's X coordinate
     * @param chunkZ      the chunk column's Z coordinate
     * @param minSectionY the lowest section of the dispatch's Y range
     * @param maxSectionY the highest section of the dispatch's Y range
     * @return {@code true} when the caller must skip the column, because the owning region delivers it
     */
    public static boolean captureCrossRegionGameEvent(ServerLevel level, net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
            net.minecraft.world.phys.Vec3 source, net.minecraft.world.level.gameevent.GameEvent.Context context, int chunkX, int chunkZ,
            int minSectionY, int maxSectionY) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine
                && engine.captureCrossRegionGameEvent(level, event, source, context, chunkX, chunkZ, minSectionY, maxSectionY);
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
     * Opens the global phase of the tick of {@code level} and drains the level's global mailbox.
     * <p>
     * Called on the server thread at the head of the level tick, before the queued region work of the tick and
     * before every vanilla statement. Without an installed engine this is a no-op: there is no region phase, so
     * the level is global for its whole tick and the vanilla order is unchanged.
     */
    public static void beginLevelTick(ServerLevel level) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.beginLevelTick(level);
            }
        });
    }

    /**
     * Closes the global phase of the tick of {@code level}. A no-op without an installed engine.
     */
    public static void endLevelTick(ServerLevel level) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.endLevelTick(level);
            }
        });
    }

    /**
     * {@return the phase of the tick of {@code level}, or {@code null} when no engine is installed}
     * <p>
     * Without an engine no slice ever leaves the server thread, so there is no phase boundary to report.
     */
    @Nullable
    public static LevelTickPhase levelTickPhase(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine ? engine.levelTickPhase(level) : null;
    }

    /**
     * {@return the mailbox of {@code level}, or {@code null} when no engine is installed}
     * <p>
     * The mailbox is the only supported way for region work to affect another region or to ask the server thread
     * to change global state at a defined point; see {@link RegionMailbox}.
     */
    @Nullable
    public static RegionMailbox mailbox(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine ? engine.mailbox(level) : null;
    }

    /**
     * Rejects a write of the state the global phase owns from a region phase; see the ownership table of
     * {@link LevelTickPhase}.
     * <p>
     * Called by the single accessors of the owned state ({@code ServerLevel.setDayTime},
     * {@code setDayTimeFraction}, {@code setDayTimePerTick} and {@code setWeatherParameters}). A write from a
     * native region worker, or from the server thread while a region phase of the level is in flight, is an
     * ownership violation: it is counted and reported according to the configured ownership violation mode.
     * Without an installed engine the call is a no-op.
     */
    public static void assertGlobalStateWrite(ServerLevel level, String operation) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.assertGlobalStateWrite(level, operation);
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
     * Publishes that the level's entity tick list now contains {@code entity}.
     * <p>
     * This is the membership seam of the self-driven entity tick: the engine indexes the entity in the region
     * that owns its mapping, so the region's worker enumerates exactly the entities the level would tick. The
     * hook is a no-op without an installed engine, so a server that does not run the bridge keeps the vanilla
     * tick list untouched.
     */
    public static void entityTickStarted(ServerLevel level, Entity entity) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.entityTickStarted(level, entity);
            }
        });
    }

    /**
     * Publishes that the level's entity tick list no longer contains {@code entity}.
     * <p>
     * The entity stays indexed and mapped - its scheduled tasks and its region queries keep working - and only
     * its tick membership is cleared, so no region worker ticks an entity the level stopped ticking.
     */
    public static void entityTickEnded(ServerLevel level, Entity entity) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.entityTickEnded(level, entity);
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
     * Opens the block event phase of {@code level} as a quiescent server-thread scope when the native engine is
     * installed.
     * <p>
     * The vanilla block event phase runs on the server thread after every region batch of the level tick returned,
     * so no worker runs and no region is leased by another thread. The scope takes the lease of each region its
     * events run in once, keeps it until the phase ends and captures no border update, so a write of an event body
     * to another region is the vanilla inline call and the phase needs no border fixpoint. Without an engine this
     * is a no-op: the phase is the vanilla one.
     *
     * @return {@code true} when the phase runs as a scope; the caller closes it in a finally either way
     */
    public static boolean beginBlockEventPhase(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.beginBlockEventPhase(level);
    }

    /**
     * Closes the block event phase opened by {@link #beginBlockEventPhase(ServerLevel)} and releases every region
     * lease it took, whether the phase completed or threw. Without an engine this is a no-op.
     */
    public static void endBlockEventPhase(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.endBlockEventPhase(level);
        }
    }

    /**
     * Records that {@code level} is about to save, so the engine can check that the save observes a level whose
     * last tick is fully applied.
     * <p>
     * A save may not observe a region batch in flight or a deferred effect its barrier has not applied: the
     * world on disk would be a half-applied tick. The engine counts the observation and what it saw; see
     * {@code RegionThreadingEngine#beginLevelSave}. Without an engine this is a no-op, because the vanilla
     * save path already runs between two level ticks.
     */
    public static void beginLevelSave(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.beginLevelSave(level);
        }
    }

    /**
     * Records that {@code level} finished a save that flushes, so the regionized state the save owns can reach
     * the disk.
     * <p>
     * The chunk and entity data of a flushing save is waited for by the vanilla path. The regionized POI
     * storage writes its own columns through its own IO worker, which the vanilla wait does not cover, so the
     * engine writes its remaining columns and waits for them here. Without an engine or without a regionized
     * storage of the level this is a no-op.
     */
    public static void endLevelSave(ServerLevel level) {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.endLevelSave(level);
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

    /**
     * Records that a player is about to change level, before it leaves the old one.
     * <p>
     * The region of a player is published at the start of the entity slice of the level the player is in, so this
     * hook does not write a region mapping: it records the cause of the transition the publication will see and
     * hands the player's identity to the ledger of the destination level, so the entry follows the player
     * instead of being released by the old level and reserved again by the new one. Called from the
     * dimension-travel event, which fires before the player's level changes. Without an installed engine this is
     * a no-op.
     *
     * @param player      the player that is about to travel
     * @param destination the dimension the player travels to
     */
    public static void recordPlayerDimensionTransition(net.minecraft.server.level.ServerPlayer player, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> destination) {
        RegionThreadingManager.ifInstalled(threading -> {
            if (threading instanceof RegionThreadingEngine engine) {
                engine.recordPlayerDimensionTransition(player, destination);
            }
        });
    }
}
