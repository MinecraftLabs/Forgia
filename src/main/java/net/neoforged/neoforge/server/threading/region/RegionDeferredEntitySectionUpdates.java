/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * Deferred section updates of one tick batch.
 * <p>
 * A worker that moves an entity added to its level cannot run the level effects of that move: the
 * {@link EntityInLevelCallback#onMove()} publication of the entity section and the target chunk acquisition
 * are server-owned storage. The worker records one update per move, and the barrier applies both effects on
 * the server thread from the entity's final position.
 * <p>
 * Both slices that can move an owned entity own one of these: the entity tick batch (an entity tick that
 * moves) and the chunk tick batch (a precipitation tick that pushes the entities standing in a snow layer).
 * <p>
 * Every move is recorded: the section publication of the barrier is idempotent because it reconciles the
 * membership with the entity's final position, while the target chunk acquisition of each move still runs,
 * exactly like the inline vanilla call the worker skipped.
 */
@ApiStatus.Internal
final class RegionDeferredEntitySectionUpdates {
    /**
     * One deferred section update: the moved entity, the level callback the inline move would have invoked
     * and the section of the position the move reached. The section carries the target chunk acquisition as
     * well, because a section coordinate is a chunk coordinate.
     */
    record DeferredSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {}

    private final RegionTickBatch batch;
    private final List<DeferredSectionUpdate> updates = Collections.synchronizedList(new ArrayList<>());
    /** Set when the engine shuts down; guarded by the monitor of {@link #updates}. */
    private volatile boolean seamClosed;

    RegionDeferredEntitySectionUpdates(RegionTickBatch batch) {
        this.batch = batch;
    }

    /**
     * {@return true when the barrier of the batch will still apply deferred section updates}
     * <p>
     * The section publication and the target chunk acquisition of a worker entity move are server-owned
     * storage, so they may only be deferred to a barrier that still runs. A batch that already completed its
     * barrier, or whose seam was closed because the engine shut down, cannot defer them and keeps the storage
     * refusal instead.
     */
    boolean accepts() {
        return !this.seamClosed && !this.batch.isComplete();
    }

    /** {@return true when this record no longer accepts or applies deferred section updates} */
    boolean isSeamClosed() {
        return this.seamClosed;
    }

    /**
     * Records the level effects of one entity move that a worker deferred to the barrier.
     *
     * @param entity        the moved entity
     * @param callback      the level callback the inline move would have invoked
     * @param targetSection the section of the position this move reached, captured on the worker
     * @return {@code false} when the seam was closed or the barrier already completed, so the move can never
     *         be published and the caller has to refuse it before it mutates the entity
     */
    boolean defer(Entity entity, EntityInLevelCallback callback, long targetSection) {
        synchronized (this.updates) {
            if (this.seamClosed || this.batch.isComplete()) {
                return false;
            }
            this.updates.add(new DeferredSectionUpdate(entity, callback, targetSection));
            return true;
        }
    }

    /**
     * Stops accepting deferred section updates and releases the ones that already wait.
     * <p>
     * Called when the engine shuts down: the barrier of this batch will never publish a section again, so a
     * worker that still ticks must be refused instead of granted, and the released updates must not keep an
     * entity reference alive.
     *
     * @return the number of released updates, which the caller counts as dropped
     */
    int close() {
        synchronized (this.updates) {
            this.seamClosed = true;
            final int released = this.updates.size();
            this.updates.clear();
            return released;
        }
    }

    /** {@return the number of section updates that still wait for the barrier} */
    int pendingCount() {
        synchronized (this.updates) {
            return this.updates.size();
        }
    }

    /** Takes the deferred section updates that wait for the end of the batch. */
    List<DeferredSectionUpdate> drain() {
        synchronized (this.updates) {
            final List<DeferredSectionUpdate> drained = List.copyOf(this.updates);
            this.updates.clear();
            return drained;
        }
    }
}
