/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Declares which vanilla block entities may tick on a region worker under the main-thread-owned entity
 * storage protocol.
 * <p>
 * The list is an allowlist, not a denylist: an unknown or modded block entity type always stays on the
 * server thread. A type is only allowed on a worker when its server tick
 * <ul>
 * <li>only reaches entity storage through the region-scoped query of the lease and only mutates an entity
 * that lease owns, with every server-owned effect deferred to the batch barrier,</li>
 * <li>never adds an entity, and</li>
 * <li>only mutates its own block/container without writing outside the region lease it holds.</li>
 * </ul>
 * Every entry is interior-only and uses the same one-chunk bound as the random tick slice: the ticker's
 * chunk and all eight neighboring chunks must belong to the same region, so the writes the tick starts
 * stay inside the lease. Chained neighbor cascades that travel farther than one chunk, the scheduled tick
 * a neighbor update can start (deferred to the server thread) and arbitrary modded cross-region writes are
 * outside this bound and are not claimed to be safe.
 * Every other known pattern stays on the server thread. In particular, a tick that queries entity
 * storage and then mutates the returned entities (beacon, conduit, bell, shulker box openers,
 * enchanting table, vault and trial spawner are examples of that pattern) cannot become region-safe by
 * shipping the query to the server thread, because the consumer would still mutate another region's
 * entities from a worker. Those types are therefore simply absent from the allowlist.
 * <p>
 * The brewing stand is absent even though its normal brewing path only writes its own block: the patched
 * completion calls {@code Containers.dropItemStack} when the ingredient has a crafting remainder and is
 * not consumed entirely, which spawns an item entity. An interior position does not make entity creation
 * safe, so no declared type is safe at an arbitrary position.
 * <p>
 * The hopper is the one declared type that reaches entity storage, and it is declared because the entity
 * slice gave the block-entity slice the machinery its tick needs. Its item query is a region-scoped query of
 * the leased region, its container reads and writes are one block away - the block above it, the block it
 * faces and the chest half of a double chest, all inside the one-chunk neighborhood the interior rule
 * requires - and the one entity it mutates, the item entity it absorbs, is removed through the ownership
 * lease, so the level callback of that removal runs on the server thread at the barrier. Its
 * {@code level.random} draw (the container entity of a block that has no container block entity) resolves the
 * group random, and the scheduled tick its own {@code setChanged} can start next to a comparator is deferred
 * to the server thread. A modded {@code IItemHandler} or {@code Container} of an adjacent block stays
 * arbitrary modded code and is not claimed to be safe, exactly like every other modded write.
 * <p>
 * The {@code getType()} key of a modded block entity that reuses a vanilla ticker is its own registry
 * key, so a modded subclass never inherits a vanilla entry by accident.
 */
@ApiStatus.Internal
public final class RegionTickCompatibility {
    /**
     * Types whose server tick writes the blocks around its own position only within the same chunk
     * neighborhood, so it may only run when the chunks around its position belong to the same region.
     * <p>
     * The furnace family is audited to only mutate its own container and block with neighbor updates and
     * to never query entity storage or spawn/remove an entity. The hopper is audited to query the item
     * entities of its own region through the region-scoped query, to read and write the containers one block
     * away, and to remove the item entity it absorbs through the ownership lease of its group, which defers
     * the level callback of that removal to the batch barrier.
     */
    private static final Set<String> REGION_SAFE_INTERIOR_BLOCK_ENTITY_TYPES = Set.of(
            "minecraft:furnace",
            "minecraft:blast_furnace",
            "minecraft:hopper",
            "minecraft:smoker");

    private RegionTickCompatibility() {}

    /**
     * {@return true when a block entity of {@code type} may tick on a region worker}
     * <p>
     * A type that requires an interior position still has to pass
     * {@link #requiresInteriorRegion(String)} at its actual position.
     */
    public static boolean isRegionSafeBlockEntityType(@Nullable String type) {
        return requiresInteriorRegion(type);
    }

    /**
     * {@return true when the server tick of {@code type} starts neighbor updates and therefore may only
     * run when the chunks around its position belong to the same region}
     */
    public static boolean requiresInteriorRegion(@Nullable String type) {
        return type != null && REGION_SAFE_INTERIOR_BLOCK_ENTITY_TYPES.contains(type);
    }
}
