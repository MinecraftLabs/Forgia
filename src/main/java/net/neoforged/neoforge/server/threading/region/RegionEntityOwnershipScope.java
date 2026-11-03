/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Entity ownership lease of exactly one region entity tick group.
 * <p>
 * The scope carries the leased level and region as one {@link RegionCoordinate}, the
 * {@link RegionEntityTickBatch.Group} that answers {@link RegionEntityTickBatch.Group#owns(Entity)}, and a
 * monotonically increasing lease id. It is installed around the worker tick loop of that group only and
 * removed in a {@code finally}, so a worker task outside the tick loop never inherits it.
 * <p>
 * The lease id makes a lease unusable as soon as a newer lease was opened on the same thread: a nested
 * scope replaces the enclosing one instead of inheriting it, and a scope that was closed can never grant a
 * mutation again. Closing a scope restores the enclosing scope of the same thread, so the lease is
 * re-entrant in the strict stack order of its opening.
 * <p>
 * This is field-level ownership of the entities a group ticks, not entity storage ownership. Only the
 * vanilla mutations listed in {@link #GRANTABLE_OPERATIONS} and the removals listed in
 * {@link #GRANTABLE_REMOVAL_OPERATIONS} are granted; level admission, subclass mutations that run additional
 * side effects and every guard without an entity argument stay rejected. The lease makes no atomicity claim
 * about entity storage, tracking, cross-region reads, chunk state or the vanilla section index: those keep
 * their server-thread handoff and their storage guard. The only storage read the lease adds is the
 * region-scoped range query of its own physical bucket; see {@link RegionScopedEntityQuery}.
 * <p>
 * A position change of an entity that is <em>added to a level</em> also reaches two server-owned level
 * effects after it wrote the position and bounds: {@code EntityInLevelCallback#onMove} - for a real entity
 * the {@code PersistentEntitySectionManager.Callback}, which rejects a native worker through the
 * storage-level guard - publishes the entity section, and the target chunk is acquired through the
 * server-owned chunk source. The lease therefore grants such a move only when the batch can defer both
 * effects: the worker records the section publication and the target chunk acquisition with
 * {@link #deferEntitySectionUpdate(Entity, EntityInLevelCallback, double, double, double)} and the barrier
 * applies them on the server thread. When the batch cannot defer them - the lease outlived its batch, or
 * the caller is not a native worker - the move keeps today's refusal. {@code Entity#isAddedToLevel()} is
 * the Neo marker that the server sets when it admits an entity and clears when the entity leaves the
 * level, so it is exactly the population whose position change needs the deferral.
 * <p>
 * A removal of an entity added to the level is the second mutation with a server-owned effect: the vanilla
 * removal sets the entity's own removal state and then runs its level callback, which removes the entity
 * from the level's storage. The lease therefore grants a removal only when the batch can defer that
 * callback: the worker records it with
 * {@link #deferEntityRemoval(Entity, EntityInLevelCallback, Entity.RemovalReason)} and the barrier applies it
 * on the server thread. The entity is removed for the rest of the worker tick, because the worker itself set
 * the removal state. A removal of an entity that is not added to a level has no server-owned effect and
 * needs no deferral.
 * <p>
 * An admission of a new entity is the third mutation with a server-owned effect, and the only one that is
 * split: <b>field writes are unrestricted for pending entities, admission is lease-contained</b>. The
 * position writes of an entity that is not added to a level are granted without a position condition
 * ({@link #GRANTABLE_PENDING_ADMISSION_OPERATIONS}), because such an entity has the {@code NULL} level
 * callback and no server-owned effect - the vanilla constructor of every entity starts at
 * {@code (0, 0, 0)} before the subclass writes the real position, so a containment test on the write would
 * refuse every spawn outside the region {@code (0, 0)}. The containment is enforced where it decides
 * storage: {@link #deferEntityAddition(ServerLevel, Entity)} refuses to record an admission whose position
 * is outside the leased region, and the level keeps the storage refusal for it. A recorded admission is
 * applied by the batch barrier through the unmodified {@code ServerLevel.addEntity}.
 */
@ApiStatus.Internal
public final class RegionEntityOwnershipScope implements AutoCloseable {
    /**
     * The vanilla entity mutations a lease may grant.
     * <p>
     * This is an allowlist, not a denylist. {@code Entity.teleportTo} (the cross-level teleport, which
     * changes level, identity and storage), the subclass mutations that run additional side effects
     * ({@code BlockAttachedEntity.move/setPos} kill and re-anchor the entity,
     * {@code ServerPlayer.teleportTo/dismountTo/teleportRelative} touch the connection and the chunk source)
     * and every guard without an entity argument stay rejected for an owned entity. A guarded operation
     * therefore stays rejected until it is listed here on purpose.
     */
    private static final Set<String> GRANTABLE_OPERATIONS = Set.of(
            "Entity.absMoveTo",
            "Entity.move",
            "Entity.moveTo",
            "Entity.setPosRaw");

    /**
     * The vanilla entity removals a lease may grant.
     * <p>
     * Every removal entry point funnels into {@code Entity.setRemoved}, so these four names are the whole
     * removal path of an ordinary entity: {@code discard}, the virtual {@code remove} of {@code Entity} and of
     * {@code LivingEntity} (which runs the mob effect callbacks and clears the brain before the removal), and
     * the {@code setRemoved} funnel itself. A grant never runs the level callback on the worker: the removal
     * is recorded for the batch barrier instead, which applies the callback on the server thread.
     * <p>
     * The subclass overrides stay rejected on purpose: {@code Slime.remove} splits into new entities,
     * {@code Boat.remove}, {@code ChestBoat.remove} and {@code AbstractMinecartContainer.remove} spawn their
     * contents and {@code FishingHook.remove} reels the hooked entity in. Each of them runs an entity addition
     * or a foreign mutation before it reaches the funnel, and entity addition is not part of this lease.
     */
    private static final Set<String> GRANTABLE_REMOVAL_OPERATIONS = Set.of(
            "Entity.discard",
            "Entity.remove",
            "Entity.setRemoved",
            "LivingEntity.remove");

    /**
     * The vanilla entity position writes a lease may grant for an entity that is <em>not added to a level
     * yet</em>, i.e. for the construction and configuration of an entity the worker is about to admit.
     * <p>
     * These writes touch only the entity's own {@code position}, {@code blockPosition} and
     * {@code chunkPosition} fields: a not-added entity has the {@code NULL} level callback, so no section is
     * published, and the target chunk acquisition of {@code setPosRaw} is gated on
     * {@code isAddedToLevel()}. They are granted without a position condition on purpose: the vanilla
     * {@code Entity} constructor writes {@code (0, 0, 0)} before the subclass writes the real position, so a
     * containment test on the write would refuse every spawn outside the region {@code (0, 0)}. The lease
     * containment of an admission is decided by {@link #deferEntityAddition(ServerLevel, Entity)}, which is
     * the step that gives the position a section bucket.
     * <p>
     * {@code Entity.move} is deliberately absent: physics and collision reads belong to the chunk guard, and
     * no spawn path needs them. {@code Entity.teleportTo} stays absent as well, because it changes level,
     * identity and storage.
     */
    private static final Set<String> GRANTABLE_PENDING_ADMISSION_OPERATIONS = Set.of(
            "Entity.absMoveTo",
            "Entity.moveTo",
            "Entity.setPosRaw");

    private static final ThreadLocal<RegionEntityOwnershipScope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_LEASE_ID = new ThreadLocal<>();
    private static final AtomicLong NEXT_LEASE_ID = new AtomicLong();
    private static final AtomicLong ALLOWED_MUTATION_COUNT = new AtomicLong();
    private static final AtomicLong REJECTED_MUTATION_COUNT = new AtomicLong();
    private static final AtomicLong ALLOWED_QUERY_COUNT = new AtomicLong();
    private static final AtomicLong TRUNCATED_QUERY_COUNT = new AtomicLong();
    private static final AtomicLong REJECTED_QUERY_COUNT = new AtomicLong();

    private final RegionCoordinate coordinate;
    private final RegionEntityTickBatch.Group group;
    private final long leaseId;
    @Nullable
    private final RegionEntityOwnershipScope previous;
    @Nullable
    private final Long previousLeaseId;
    private boolean closed;

    private RegionEntityOwnershipScope(RegionCoordinate coordinate, RegionEntityTickBatch.Group group, long leaseId) {
        this.coordinate = coordinate;
        this.group = group;
        this.leaseId = leaseId;
        this.previous = CURRENT.get();
        this.previousLeaseId = CURRENT_LEASE_ID.get();
    }

    /**
     * Installs a new lease for the worker tick loop of {@code group} on the current thread.
     */
    static RegionEntityOwnershipScope open(RegionCoordinate coordinate, RegionEntityTickBatch.Group group) {
        final RegionEntityOwnershipScope scope = new RegionEntityOwnershipScope(coordinate, group, NEXT_LEASE_ID.incrementAndGet());
        CURRENT.set(scope);
        CURRENT_LEASE_ID.set(scope.leaseId);
        return scope;
    }

    /**
     * {@return whether the lease of the current thread grants the entity mutation {@code operation}}
     * <p>
     * The mutation is granted only while a lease is active, its lease id is the newest one of the current
     * thread, its level is the entity's level, the operation is one of the
     * {@linkplain #GRANTABLE_OPERATIONS grantable vanilla mutations} or one of the
     * {@linkplain #GRANTABLE_REMOVAL_OPERATIONS grantable removals}, and the group owns the entity. A field
     * mutation additionally needs the entity not to be removed; a removal of an entity that is already
     * removed stays granted, because the vanilla entry point re-enters the removal of an entity a previous
     * removal marked and the batch turns the repeat into a counted no-op. An entity that is added to a level
     * is granted only when the lease can defer the server-owned effect of the mutation to the batch barrier -
     * the section publication and the target chunk acquisition of a position change, the level callback of a
     * removal - because the vanilla body reaches them after it wrote the entity's own state. Anything else
     * keeps the caller on the storage guard, which names the operation and throws before the mutation runs.
     * <p>
     * The call records the decision for the entity worker diagnostics: a grant counts an allowance, a
     * refusal under the active lease counts an ownership rejection. Nothing is counted without an active
     * lease, because such a caller never had an ownership decision - the storage guard rejected it and
     * counted the violation. A refusal of a mutation of an entity that is neither added to the level nor
     * owned by the lease is also counted as a rejected entity spawn, because the vanilla entity constructor
     * writes the position of a new entity before the entity is added to the level.
     * <p>
     * The position writes of a not-yet-added entity are granted by
     * {@link #grantsPendingAdmissionMutation(RegionEntityOwnershipScope, ServerLevel, Entity, String)} and
     * are not counted as a rejected spawn: the entity is being constructed for an admission the lease may
     * record.
     */
    public static boolean grantsEntityMutation(ServerLevel level, Entity entity, String operation) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null || !scope.isActive()) {
            return false;
        }
        if (scope.coordinate.level() == level
                && scope.group.owns(entity)
                && grantsOperation(scope, entity, operation)) {
            ALLOWED_MUTATION_COUNT.incrementAndGet();
            return true;
        }
        if (grantsPendingAdmissionMutation(scope, level, entity, operation)) {
            ALLOWED_MUTATION_COUNT.incrementAndGet();
            return true;
        }
        if (scope.coordinate.level() == level && !entity.isAddedToLevel() && !scope.group.owns(entity)) {
            // The vanilla entity constructor writes the position of a new entity before the entity is added to
            // the level, so a rejected mutation of an entity that is neither in the level nor owned by the lease
            // is a worker-side entity spawn - death loot, an experience orb, a projectile. It is counted
            // separately, so the remaining spawn rate stays observable next to the removal counters.
            RegionOwnershipViolationHandler.recordEntityAdditionRejection();
        }
        REJECTED_MUTATION_COUNT.incrementAndGet();
        return false;
    }

    /**
     * {@return true when {@code operation} on {@code entity} is granted under the active lease {@code scope}}
     * <p>
     * A removal is granted when the lease can defer its level callback - an entity that is not added to a
     * level has no server-owned effect to defer. A field mutation is granted when the entity is not removed
     * and the lease can defer its section publication and target chunk acquisition. Every other operation
     * stays rejected.
     */
    private static boolean grantsOperation(RegionEntityOwnershipScope scope, Entity entity, String operation) {
        if (GRANTABLE_REMOVAL_OPERATIONS.contains(operation)) {
            // Only a native worker defers, so only a native worker is granted the removal of an added entity.
            return !entity.isAddedToLevel()
                    || (RegionWorkerScope.isNativeWorkerThread() && scope.group.batch().acceptsDeferredRemovals());
        }
        return GRANTABLE_OPERATIONS.contains(operation)
                && !entity.isRemoved()
                && (!entity.isAddedToLevel() || defersSectionUpdate(entity));
    }

    /**
     * {@return true when {@code operation} positions an entity that is not added to {@code level} yet}
     * <p>
     * The grant is the construction half of an admission: the lease level must be the entity's level (the
     * storage guard derives {@code level} from the entity), the entity must not be added to a level and must
     * not be removed, and the operation must be one of the
     * {@linkplain #GRANTABLE_PENDING_ADMISSION_OPERATIONS grantable pending-admission writes}. No position is
     * checked here; see the class documentation for why the containment belongs to
     * {@link #deferEntityAddition(ServerLevel, Entity)}.
     */
    private static boolean grantsPendingAdmissionMutation(RegionEntityOwnershipScope scope, ServerLevel level, Entity entity, String operation) {
        return scope.coordinate.level() == level
                && GRANTABLE_PENDING_ADMISSION_OPERATIONS.contains(operation)
                && !entity.isAddedToLevel()
                && !entity.isRemoved();
    }

    /**
     * Records the admission of the entity graph rooted at {@code entity} for the batch barrier.
     * <p>
     * This is the admission half of the lease, and the only place where an entity addition is
     * lease-contained: the entity must be a not-yet-added, not-removed entity of {@code level} whose position
     * is inside the leased region, and every entity of its self-and-passenger graph must be a not-yet-added,
     * not-removed entity of the same level. A riding graph is recorded whole or refused whole, because half a
     * graph in the level is never a valid state.
     * <p>
     * The barrier then runs the unmodified {@code ServerLevel.addEntity} for every member of the graph on the
     * server thread, so the section membership, the identity ledger, the tracking and ticking callbacks and
     * the region index are written exactly like a server-thread spawn. Nothing is admitted on the worker: the
     * entity stays {@code !isAddedToLevel()} for the rest of the worker tick, and an admission the barrier
     * drops (the engine shut down, the level closed, the entity was removed meanwhile) loses the entity and
     * is counted.
     *
     * @param level  the level the caller is adding to
     * @param entity the entity the caller is adding
     * @return {@code true} when the caller must skip the vanilla admission because the barrier runs it,
     *         {@code false} when the caller keeps the storage refusal
     */
    public static boolean deferEntityAddition(ServerLevel level, Entity entity) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null
                || !scope.isActive()
                || !RegionWorkerScope.isNativeWorkerThread()
                || scope.coordinate.level() != level
                || entity.level() != level
                || entity.isAddedToLevel()
                || entity.isRemoved()
                || !containsPosition(scope, level, entity.blockPosition())) {
            return false;
        }
        final List<Entity> graph = entity.getSelfAndPassengers().toList();
        for (final Entity member : graph) {
            if (member.level() != level || member.isAddedToLevel() || member.isRemoved()) {
                // Half a graph is never a valid state: refuse the whole admission instead of recording the
                // members that would be admissible.
                return false;
            }
        }
        return scope.group.batch().deferAddition(entity, graph);
    }

    /**
     * {@return true when {@code pos} of {@code level} lies inside the region of the active lease {@code scope}}
     * <p>
     * The bounds are the ones of the physical section bucket of the leased region, the same block bounds the
     * region-scoped entity query clamps a query box to. A level without physical region buckets answers
     * {@code false}, so a lease never records an admission for storage that is not partitioned.
     */
    private static boolean containsPosition(RegionEntityOwnershipScope scope, ServerLevel level, BlockPos pos) {
        final int regionChunkSize = level.entitySectionRegionChunkSize();
        if (regionChunkSize <= 0) {
            return false;
        }
        return Math.floorDiv(SectionPos.blockToSectionCoord(pos.getX()), regionChunkSize) == scope.coordinate.regionX()
                && Math.floorDiv(SectionPos.blockToSectionCoord(pos.getZ()), regionChunkSize) == scope.coordinate.regionZ();
    }

    /**
     * {@return the coordinate of the region whose entity storage the current thread may query, or
     * {@code null} when the current thread holds no active lease for {@code level}}
     * <p>
     * The check is the query half of the lease: a native region worker, the newest lease of the thread, and
     * the leased level. Whether the level actually has physical region buckets is decided by
     * {@link RegionScopedEntityQuery}, which also applies the out-of-region clamp and records the counters.
     */
    @Nullable
    static RegionCoordinate queryCoordinate(ServerLevel level) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null
                || !scope.isActive()
                || !RegionWorkerScope.isNativeWorkerThread()
                || scope.coordinate.level() != level) {
            return null;
        }
        return scope.coordinate;
    }

    /**
     * {@return true when the active lease of the current thread owns exactly the region
     * {@code regionX}/{@code regionZ} of {@code level}}
     * <p>
     * The level calls this before it reads the physical bucket of the requested region, so a worker cannot
     * use the internal region-scoped section accessor to read a neighbouring region's storage.
     */
    public static boolean ownsQueryRegion(ServerLevel level, int regionX, int regionZ) {
        final RegionCoordinate coordinate = queryCoordinate(level);
        return coordinate != null && coordinate.regionX() == regionX && coordinate.regionZ() == regionZ;
    }

    static void recordAllowedQuery() {
        ALLOWED_QUERY_COUNT.incrementAndGet();
    }

    static void recordTruncatedQuery() {
        TRUNCATED_QUERY_COUNT.incrementAndGet();
    }

    /**
     * Counts a legacy entity query that the storage guard rejected on a native worker. Called by
     * {@code RegionThreadingHooks.rejectLegacyEntityQuery} before it throws, so the rejection is observable
     * next to the ownership counters.
     */
    public static void recordRejectedQuery() {
        REJECTED_QUERY_COUNT.incrementAndGet();
    }

    /**
     * Defers the server-owned level effects of a position change of {@code entity} to the batch barrier.
     * <p>
     * The worker records the section publication it must not run - the callback the inline vanilla move
     * would have invoked - and the section of the position this move reached, which is also the target
     * chunk of the acquisition the worker must not run. The barrier applies both effects on the server
     * thread. The record is only queued when the current thread is a native region worker whose active
     * lease owns {@code entity}, the entity is added to a level and its batch still accepts deferred
     * section updates.
     *
     * @param entity   the moved entity
     * @param callback the entity's level callback, the exact object the inline vanilla move would invoke
     * @param x        the x coordinate this move reached
     * @param y        the y coordinate this move reached
     * @param z        the z coordinate this move reached
     * @return {@code true} when the move was deferred, so the caller must skip the section publication and
     *         the target chunk acquisition
     * @throws IllegalStateException when the batch closed its section seam between the ownership grant and
     *                               this record; the caller is a native region worker, so the move is
     *                               refused before it mutates the entity
     */
    public static boolean deferEntitySectionUpdate(Entity entity, EntityInLevelCallback callback, double x, double y, double z) {
        final RegionEntityOwnershipScope scope = sectionUpdateOwner(entity);
        if (scope == null) {
            return false;
        }
        final long targetSection = SectionPos.asLong(
                SectionPos.posToSectionCoord(x),
                SectionPos.posToSectionCoord(y),
                SectionPos.posToSectionCoord(z));
        if (!scope.group.batch().deferSectionUpdate(entity, callback, targetSection)) {
            // The batch stopped accepting section updates between the ownership grant and this record - the
            // engine shut down in between - so this move can never be published. Refusing it here, before
            // Entity.setPosRaw writes the position, keeps the reject-before-mutation invariant of the guard;
            // letting the inline publication run instead would mutate first and throw afterwards.
            RegionOwnershipViolationHandler.rejectNativeWorkerStorageAccess("Entity.setPosRaw");
        }
        return true;
    }

    /**
     * {@return true when the current thread defers the section publication of {@code entity} to a barrier}
     * <p>
     * The deferral needs a native region worker thread, an active lease that owns the entity, an entity
     * that is added to a level and a batch whose barrier still runs and whose section seam is still open. A
     * non-worker caller - the server thread, the client and every ordinary thread - never defers, so it
     * keeps the inline vanilla effect.
     */
    private static boolean defersSectionUpdate(Entity entity) {
        final RegionEntityOwnershipScope scope = sectionUpdateOwner(entity);
        return scope != null && scope.group.batch().acceptsDeferredSectionUpdates();
    }

    /**
     * Defers the level callback of the removal of {@code entity} to the batch barrier.
     * <p>
     * The worker records the callback the inline vanilla removal would have invoked and the removal reason;
     * the barrier runs the callback on the server thread, so the section membership, the tracking and ticking
     * callbacks, the identity ledger and the region index are written exactly as they are for a server-thread
     * removal. The entity's own removal state is written by the caller, so the rest of the worker tick sees a
     * removed entity.
     * <p>
     * Nothing is recorded outside a lease that owns the entity: the server thread, the client, every ordinary
     * thread, a foreign entity and a lease that outlived its batch answer {@code false} and keep the inline
     * vanilla callback. An entity that is not added to a level answers {@code false} as well, because its
     * level callback is the {@code NULL} callback and the removal has no server-owned effect.
     *
     * @param entity   the entity that is being removed
     * @param callback the entity's level callback, the exact object the inline vanilla removal would invoke
     * @param reason   the removal reason the entity is being marked with
     * @return {@code true} when the caller must skip the level callback, because the barrier runs it instead
     */
    public static boolean deferEntityRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null
                || !scope.isActive()
                || !RegionWorkerScope.isNativeWorkerThread()
                || !scope.group.owns(entity)
                || !entity.isAddedToLevel()
                || callback == EntityInLevelCallback.NULL) {
            return false;
        }
        return scope.group.batch().deferRemoval(entity, callback, reason);
    }

    /**
     * {@return the lease of the current thread when it owns the section update of {@code entity}}
     * <p>
     * The ownership part of the deferral decision: a native region worker, an active lease, an entity that
     * is added to a level and not removed, and a group that owns the entity. Whether the lease can still
     * defer is decided by {@link RegionEntityTickBatch#acceptsDeferredSectionUpdates()}.
     */
    @Nullable
    private static RegionEntityOwnershipScope sectionUpdateOwner(Entity entity) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null
                || !scope.isActive()
                || !RegionWorkerScope.isNativeWorkerThread()
                || !entity.isAddedToLevel()
                || entity.isRemoved()
                || !scope.group.owns(entity)) {
            return null;
        }
        return scope;
    }

    /**
     * {@return true while this scope is the open, newest lease of the current thread}
     */
    private boolean isActive() {
        final Long currentLeaseId = CURRENT_LEASE_ID.get();
        return !this.closed
                && CURRENT.get() == this
                && currentLeaseId != null
                && currentLeaseId.longValue() == this.leaseId;
    }

    /**
     * Restores the lease that was active before this scope was opened.
     * <p>
     * A scope that is no longer the current one is only marked closed: restoring its predecessor on top of
     * the lease that superseded it would resurrect an older lease. A closed scope never grants again, even
     * when a later close of the superseding scope restores it as the installed value; closing that value
     * once more restores its own predecessor, so the thread local never keeps a dead lease.
     */
    @Override
    public void close() {
        this.closed = true;
        if (CURRENT.get() != this) {
            return;
        }
        if (this.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(this.previous);
        }
        if (this.previousLeaseId == null) {
            CURRENT_LEASE_ID.remove();
        } else {
            CURRENT_LEASE_ID.set(this.previousLeaseId);
        }
    }

    static long allowedMutationCount() {
        return ALLOWED_MUTATION_COUNT.get();
    }

    static long rejectedMutationCount() {
        return REJECTED_MUTATION_COUNT.get();
    }

    static long allowedQueryCount() {
        return ALLOWED_QUERY_COUNT.get();
    }

    static long truncatedQueryCount() {
        return TRUNCATED_QUERY_COUNT.get();
    }

    static long rejectedQueryCount() {
        return REJECTED_QUERY_COUNT.get();
    }

    static void resetCounters() {
        ALLOWED_MUTATION_COUNT.set(0L);
        REJECTED_MUTATION_COUNT.set(0L);
        ALLOWED_QUERY_COUNT.set(0L);
        TRUNCATED_QUERY_COUNT.set(0L);
        REJECTED_QUERY_COUNT.set(0L);
    }
}
