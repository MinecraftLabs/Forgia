/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * Deferred removals of one tick batch.
 * <p>
 * A worker that removes an entity it owns writes only the entity's own removal state; the level callback of
 * the removal is server-owned storage and runs on the server thread when the batch barrier drains this record.
 * Both slices that can grant a removal own one of these: the entity tick batch (a dying entity) and the
 * block-entity tick batch (a hopper absorbing an item entity).
 * <p>
 * The entity is its own key, so a repeat - the vanilla entry point removing an entity that a previous removal
 * already marked - is one hash lookup and a counted no-op instead of a second record, and the first removal is
 * the one that runs. The map is guarded by its own monitor and is only filled from workers, so it stays
 * bounded by the entities removed in the current tick.
 */
@ApiStatus.Internal
final class RegionDeferredEntityRemovals {
    /**
     * One deferred removal: the removed entity, the level callback the inline vanilla removal would have
     * invoked and the removal reason the entity was marked with. The barrier runs that callback on the server
     * thread, which is the whole server-owned part of the removal.
     */
    record DeferredRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {}

    /**
     * One drain of the deferred removals: the removals to apply and the removal requests that were already
     * recorded and were therefore counted as no-ops.
     */
    record Drain(List<DeferredRemoval> removals, long repeats) {}

    private final RegionTickBatch batch;
    private final IdentityHashMap<Entity, DeferredRemoval> removals = new IdentityHashMap<>();
    private long repeatCount;
    private volatile boolean seamClosed;

    RegionDeferredEntityRemovals(RegionTickBatch batch) {
        this.batch = batch;
    }

    /**
     * {@return true when the barrier of the batch will still apply deferred removals}
     * <p>
     * A batch that already completed its barrier, or whose seam was closed because the engine shut down, cannot
     * defer a removal and keeps the storage refusal instead of letting a worker remove an entity whose storage
     * removal nobody applies.
     */
    boolean accepts() {
        return !this.seamClosed && !this.batch.isComplete();
    }

    /** {@return true when this record no longer accepts or applies deferred removals} */
    boolean isSeamClosed() {
        return this.seamClosed;
    }

    /**
     * Records the level callback of one removal that a worker deferred to the barrier.
     *
     * @return {@code false} when the seam was closed or the barrier already completed, so the removal can never
     *         be applied and the caller has to keep the storage refusal
     */
    boolean defer(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        synchronized (this.removals) {
            if (this.seamClosed || this.batch.isComplete()) {
                return false;
            }
            if (this.removals.containsKey(entity)) {
                this.repeatCount++;
                return true;
            }
            this.removals.put(entity, new DeferredRemoval(entity, callback, reason));
            return true;
        }
    }

    /**
     * Stops accepting deferred removals and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier will never apply a removal again, so a worker that still
     * ticks must be refused instead of granted, and the released removals must not keep an entity reference
     * alive.
     */
    Drain close() {
        synchronized (this.removals) {
            this.seamClosed = true;
            return this.drain();
        }
    }

    /** {@return the number of removals that still wait for the barrier} */
    int pendingCount() {
        synchronized (this.removals) {
            return this.removals.size();
        }
    }

    /** Takes the deferred removals that wait for the end of the batch. */
    Drain drain() {
        synchronized (this.removals) {
            final Drain drain = new Drain(List.copyOf(this.removals.values()), this.repeatCount);
            this.removals.clear();
            this.repeatCount = 0L;
            return drain;
        }
    }
}
