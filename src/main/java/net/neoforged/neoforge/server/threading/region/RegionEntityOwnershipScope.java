/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
 * An entity that is <em>added to a level</em> is never granted either, even when the group owns it. A
 * position change of such an entity calls {@code EntityInLevelCallback#onMove} - for a real entity the
 * {@code PersistentEntitySectionManager.Callback}, which rejects a native worker through the storage-level
 * guard - and loads the target chunk through the server-owned chunk source. Granting it would mutate the
 * position and bounds before that rejection, so the lease would break the guard's reject-before-mutation
 * invariant. {@code Entity#isAddedToLevel()} is the Neo marker that the server sets when it admits an
 * entity and clears when the entity leaves the level, so it is exactly the population whose position
 * change needs the storage seam. Real worker entity ticking therefore needs the region-owned
 * section-storage seam (a deferred {@code onMove} and region-owned chunk acquisition); that seam is the
 * next milestone, not this lease.
 */
@ApiStatus.Internal
public final class RegionEntityOwnershipScope implements AutoCloseable {
    /**
     * The vanilla entity mutations a lease may grant.
     * <p>
     * This is an allowlist, not a denylist, and it is only ever applied to an entity that is not added to a
     * level. Removal ({@code Entity.discard}, {@code Entity.remove}, {@code Entity.setRemoved} and every
     * subclass override of them), {@code Entity.teleportTo} (the cross-level teleport, which changes level,
     * identity and storage), the subclass mutations that run additional side effects
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
     * {@linkplain #GRANTABLE_OPERATIONS grantable vanilla mutations}, the entity is not removed, the entity
     * is not added to a level and the group owns it. Anything else keeps the caller on the storage guard,
     * which names the operation and throws before the mutation runs.
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
                && !entity.isAddedToLevel()
                && !entity.isRemoved()
                && scope.group.owns(entity)) {
            ALLOWED_MUTATION_COUNT.incrementAndGet();
            return true;
        }
        REJECTED_MUTATION_COUNT.incrementAndGet();
        return false;
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
