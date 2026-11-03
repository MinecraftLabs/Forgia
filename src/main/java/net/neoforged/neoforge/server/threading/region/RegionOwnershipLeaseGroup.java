/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.ApiStatus;

/**
 * The worker group a region ownership lease is opened for: its entity ownership decision and the server-owned
 * effects its batch defers to the barrier.
 * <p>
 * A group answers the two questions the lease asks. {@link #owns(Entity)} is the ownership half: an entity
 * tick group owns the snapshot members of its group, a block-entity tick group owns the entities stored in its
 * leased region, because its tickers reach them through the region-scoped query of that same region. The
 * {@code defer*} methods are the storage half: a grant that would reach server-owned storage is only given
 * when the batch of the group still applies the deferred effect.
 * <p>
 * A group that cannot defer an effect refuses it, so the caller keeps the storage refusal instead of mutating
 * first. A block-entity tick group therefore only defers removals: a hopper absorbs an item entity it owns,
 * but a vanilla block-entity tick neither moves nor admits an entity.
 */
@ApiStatus.Internal
interface RegionOwnershipLeaseGroup {
    /**
     * {@return whether the lease of this group owns {@code entity}}
     * <p>
     * The lease only grants a mutation of an entity this group owns; a foreign entity keeps the storage guard.
     */
    boolean owns(Entity entity);

    /** {@return true when the batch of this group will still apply deferred removals} */
    boolean acceptsDeferredRemovals();

    /**
     * Records the level callback of one removal for the batch barrier.
     *
     * @return {@code false} when the group cannot defer the removal, so the caller has to keep the storage
     *         refusal
     */
    boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason);

    /** {@return true when the batch of this group will still apply deferred section updates} */
    boolean acceptsDeferredSectionUpdates();

    /**
     * Records the server-owned level effects of one entity move for the batch barrier.
     *
     * @return {@code false} when the group cannot defer the move, so the caller has to keep the storage
     *         refusal
     */
    boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection);

    /**
     * Records the admission of one riding graph for the batch barrier.
     *
     * @return {@code false} when the group cannot defer the admission, so the caller has to keep the storage
     *         refusal
     */
    boolean deferAddition(Entity entity, List<Entity> graph);
}
