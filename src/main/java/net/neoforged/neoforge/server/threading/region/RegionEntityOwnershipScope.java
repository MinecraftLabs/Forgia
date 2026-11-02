/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
 * vanilla mutations listed in {@link #GRANTABLE_OPERATIONS} are granted; removal, level admission,
 * subclass mutations that run additional side effects and every guard without an entity argument stay
 * rejected. The lease makes no atomicity claim about entity storage, tracking, cross-region reads, chunk
 * state or the vanilla section index: those keep their server-thread handoff and their storage guard.
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
 */
@ApiStatus.Internal
public final class RegionEntityOwnershipScope implements AutoCloseable {
    /**
     * The vanilla entity mutations a lease may grant.
     * <p>
     * This is an allowlist, not a denylist. Removal ({@code Entity.discard}, {@code Entity.remove},
     * {@code Entity.setRemoved} and every subclass override of them), {@code Entity.teleportTo} (the
     * cross-level teleport, which changes level, identity and storage), the subclass mutations that run
     * additional side effects ({@code BlockAttachedEntity.move/setPos} kill and re-anchor the entity,
     * {@code ServerPlayer.teleportTo/dismountTo/teleportRelative} touch the connection and the chunk source)
     * and every guard without an entity argument stay rejected for an owned entity. A guarded operation
     * therefore stays rejected until it is listed here on purpose.
     */
    private static final Set<String> GRANTABLE_OPERATIONS = Set.of(
            "Entity.absMoveTo",
            "Entity.move",
            "Entity.moveTo",
            "Entity.setPosRaw");

    private static final ThreadLocal<RegionEntityOwnershipScope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_LEASE_ID = new ThreadLocal<>();
    private static final AtomicLong NEXT_LEASE_ID = new AtomicLong();
    private static final AtomicLong ALLOWED_MUTATION_COUNT = new AtomicLong();
    private static final AtomicLong REJECTED_MUTATION_COUNT = new AtomicLong();

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
     * {@linkplain #GRANTABLE_OPERATIONS grantable vanilla mutations}, the entity is not removed and the
     * group owns it. An entity that is added to a level is granted only when the lease can defer its
     * section publication and its target chunk acquisition to the batch barrier, because the vanilla body
     * reaches both after it wrote the position. Anything else keeps the caller on the storage guard, which
     * names the operation and throws before the mutation runs.
     * <p>
     * The call records the decision for the entity worker diagnostics: a grant counts an allowance, a
     * refusal under the active lease counts an ownership rejection. Nothing is counted without an active
     * lease, because such a caller never had an ownership decision - the storage guard rejected it and
     * counted the violation.
     */
    public static boolean grantsEntityMutation(ServerLevel level, Entity entity, String operation) {
        final RegionEntityOwnershipScope scope = CURRENT.get();
        if (scope == null || !scope.isActive()) {
            return false;
        }
        if (scope.coordinate.level() == level
                && GRANTABLE_OPERATIONS.contains(operation)
                && !entity.isRemoved()
                && scope.group.owns(entity)
                && (!entity.isAddedToLevel() || defersSectionUpdate(entity))) {
            ALLOWED_MUTATION_COUNT.incrementAndGet();
            return true;
        }
        REJECTED_MUTATION_COUNT.incrementAndGet();
        return false;
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

    static void resetCounters() {
        ALLOWED_MUTATION_COUNT.set(0L);
        REJECTED_MUTATION_COUNT.set(0L);
    }
}
