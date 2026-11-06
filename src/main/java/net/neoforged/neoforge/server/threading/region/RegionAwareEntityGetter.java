/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Entity getter of a {@link ServerLevel} that keeps the vanilla section/index storage owned by the
 * server thread.
 * <p>
 * The vanilla storage is a set of single-threaded maps. A native region worker that read them directly would
 * race the server thread and, for the consumer shapes, run a caller callback against live storage. Every
 * operation of this getter is therefore answered from what the worker may read - the region it leases and the
 * per-tick player snapshot the server thread publishes - before this class reaches the delegate:
 * <ul>
 * <li>the by-id and by-uuid lookups from the region's entity index, because a modded entity resolves its
 * owner every tick and the owner almost always stands in the same region;</li>
 * <li>{@code getAll()} and the type-filtered lookup from the region's entity index, counted as truncated
 * when the level's index holds entities outside the lease;</li>
 * <li>the box queries from the physical section bucket of the lease, clamped to it and counted, plus the
 * players of the published snapshot;</li>
 * <li>the player lookups from the published snapshot; see {@link RegionThreadingHooks#playersOfLevel}.</li>
 * </ul>
 * What is left on the legacy guard is a query with no local and no published answer: a by-uuid or by-id
 * lookup of an entity of another region, a worker without a lease, a level with shared (DYNAMIC) section
 * storage, and a level whose region entity index is not published. Each of those is refused with the
 * operation name, counted, and reported by {@code /neoforge regionthreading status}. A by-uuid or by-id
 * lookup whose entity this tick's {@link RegionEntityIdentitySnapshots} publication names is refused with the
 * region that owns it, counted as a cross-region lookup, and never answered with the foreign entity.
 * <p>
 * The internal mechanics seam ({@code enableLegacyEntityQueryHandoffForTesting()}) keeps the handoff
 * transport covered by tests: it runs the complete query (including its predicate/consumer and, for
 * {@link #getAll()}, the iteration) on the server thread and returns the finished result. Production never
 * enables that seam.
 * <p>
 * On the server thread and on every non-worker caller every call runs inline and keeps the vanilla semantics.
 */
@ApiStatus.Internal
public final class RegionAwareEntityGetter implements LevelEntityGetter<Entity> {
    private final ServerLevel level;
    private final LevelEntityGetter<Entity> delegate;

    public RegionAwareEntityGetter(ServerLevel level, LevelEntityGetter<Entity> delegate) {
        this.level = level;
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public Entity get(int id) {
        final Entity scoped = regionScopedLookup(id, null);
        if (scoped != null) {
            return scoped;
        }
        rejectOnNativeWorker(lookupOperation("RegionAwareEntityGetter.get(int)", null, id),
                RegionEntityQueryCounters.Operation.GET_BY_ID);
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> this.delegate.get(id));
        }
        return this.delegate.get(id);
    }

    @Nullable
    @Override
    public Entity get(UUID uuid) {
        final Entity scoped = regionScopedLookup(0, uuid);
        if (scoped != null) {
            return scoped;
        }
        rejectOnNativeWorker(lookupOperation("RegionAwareEntityGetter.get(UUID)", uuid, 0),
                RegionEntityQueryCounters.Operation.GET_BY_UUID);
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> this.delegate.get(uuid));
        }
        return this.delegate.get(uuid);
    }

    @Override
    public Iterable<Entity> getAll() {
        final List<Entity> scoped = RegionScopedEntityQuery.tryGetAll(this.level);
        if (scoped != null) {
            return scoped;
        }
        rejectOnNativeWorker("RegionAwareEntityGetter.getAll", RegionEntityQueryCounters.Operation.GET_ALL);
        if (!RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return this.delegate.getAll();
        }
        final List<Entity> snapshot = new ArrayList<>();
        RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
            for (final Entity entity : this.delegate.getAll()) {
                snapshot.add(entity);
            }
            return null;
        });
        return snapshot;
    }

    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AbortableIterationConsumer<U> consumer) {
        if (RegionScopedEntityQuery.tryGetByType(this.level, typeTest, consumer)) {
            return;
        }
        rejectOnNativeWorker("RegionAwareEntityGetter.get(EntityTypeTest, AbortableIterationConsumer)",
                RegionEntityQueryCounters.Operation.GET_BY_TYPE);
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(typeTest, consumer);
                return null;
            });
        } else {
            this.delegate.get(typeTest, consumer);
        }
    }

    @Override
    public void get(AABB boundingBox, Consumer<Entity> consumer) {
        if (RegionScopedEntityQuery.tryGetInBox(this.level, boundingBox, consumer)) {
            return;
        }
        rejectOnNativeWorker("RegionAwareEntityGetter.get(AABB, Consumer)", RegionEntityQueryCounters.Operation.GET_IN_BOX);
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(boundingBox, consumer);
                return null;
            });
        } else {
            this.delegate.get(boundingBox, consumer);
        }
    }

    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AABB boundingBox, AbortableIterationConsumer<U> consumer) {
        if (RegionScopedEntityQuery.tryGetByTypeInBox(this.level, typeTest, boundingBox, consumer)) {
            return;
        }
        rejectOnNativeWorker("RegionAwareEntityGetter.get(EntityTypeTest, AABB, AbortableIterationConsumer)",
                RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX);
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(typeTest, boundingBox, consumer);
                return null;
            });
        } else {
            this.delegate.get(typeTest, boundingBox, consumer);
        }
    }

    /**
     * {@return the entity of the region this worker leases, or {@code null} when the caller may not use the
     * region-scoped path or the region does not hold the entity}
     * <p>
     * A modded entity resolves its owner every tick - {@code Projectile.getOwner()} asks the level for a UUID -
     * and the legacy lookup is server-owned storage that a worker must not read. The entity that fired a
     * projectile almost always stands in the same region, so the lookup is answered from the region the worker
     * leases. An entity of another region is not visible here: the lookup then takes the legacy path, which
     * rejects it and is counted, so a cross-region owner stays a visible refusal rather than a silent null.
     * <p>
     * The region index is an answer only while it is published: a level whose entity tick index was never
     * seeded (entity tick workers off, the engine shut down) keeps the legacy guard instead of answering
     * {@code null} from an index that holds nothing yet. An entity whose removal is deferred to the batch
     * barrier is still in the index, but vanilla would already have dropped it from its own lookup, so the
     * lookup falls through to the counted refusal instead of handing out a removed entity.
     */
    @Nullable
    private Entity regionScopedLookup(int id, @Nullable UUID uuid) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return null;
        }
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) || !engine.isEntityIndexPublished(this.level)) {
            return null;
        }
        final RegionCoordinate coordinate = RegionEntityOwnershipScope.currentLeaseCoordinate(this.level);
        if (coordinate == null) {
            return null;
        }
        final RegionState region = engine.regionStateFor(coordinate);
        if (region == null) {
            return null;
        }
        final Entity entity = uuid != null ? region.entityByUuid(uuid) : region.entityById(id);
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        RegionEntityOwnershipScope.recordAllowedQuery();
        RegionEntityQueryCounters.record(uuid != null ? RegionEntityQueryCounters.Operation.GET_BY_UUID : RegionEntityQueryCounters.Operation.GET_BY_ID,
                RegionEntityQueryCounters.Path.LOCAL);
        return entity;
    }

    /**
     * {@return the operation a refusal reports, naming the region that owns the entity when this tick's identity
     * publication knows it}
     * <p>
     * The local lookup already failed, so an identity the publication names belongs to another region: the
     * refusal says which one, and the lookup is counted separately from a genuine miss of an identity no region
     * of the level holds. The entity itself is never returned - a live entity of a running region cannot be
     * handed to another worker, and the publication answers a value so that a crash report names the owner
     * instead of only the operation.
     */
    private String lookupOperation(String operation, @Nullable UUID uuid, int id) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return operation;
        }
        final RegionEntityIdentitySnapshots.Owner owner = uuid != null
                ? RegionEntityIdentitySnapshots.ownerOf(this.level, uuid)
                : RegionEntityIdentitySnapshots.ownerOf(this.level, id);
        if (owner == null) {
            return operation;
        }
        RegionEntityIdentitySnapshots.recordNamedCrossRegionLookup();
        return operation + " of " + (uuid != null ? "uuid " + uuid : "network id " + id) + " owned by " + owner;
    }

    private static void rejectOnNativeWorker(String operation, RegionEntityQueryCounters.Operation path) {
        if (RegionThreadingHooks.shouldRejectLegacyEntityQuery()) {
            RegionThreadingHooks.rejectLegacyEntityQuery(operation, path);
        }
    }
}
