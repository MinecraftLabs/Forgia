/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * One slice of a level tick that executes on region workers.
 * <p>
 * Every slice writes blocks - a block entity through its own state and the container it talks to, an entity
 * through the blocks it places, a chunk through its random ticks and a scheduled tick through the block it
 * ticks - and every write reaches the two level-wide structures of {@link RegionDeferredBlockUpdates}. The
 * slice names the unit its batch admits against {@link RegionDeferredBlockUpdates#MAX_ENTRIES}, and it is the
 * key the diagnostics count the applied, dropped and refused records of a batch by.
 */
@ApiStatus.Internal
public enum RegionTickSlice {
    /**
     * One ticking entity. An entity tick writes a handful of blocks at most - the block an enderman places, the
     * fire it lights, the snow a golem leaves - so eight entries cover one entity with a wide margin.
     */
    ENTITY_TICK(8),
    /**
     * One block-entity ticker. A ticker writes its own state (a furnace lights up, a hopper moves an item), the
     * state of the container it reads and the delay of a comparator next to it, so eight entries cover one
     * ticker with a wide margin.
     */
    BLOCK_ENTITY_TICK(8),
    /**
     * One ticked chunk. A random tick of the default random tick speed writes a handful of blocks per section it
     * ticks, so thirty-two entries cover a chunk body with a wide margin.
     */
    CHUNK_TICK(32),
    /**
     * One scheduled block or fluid tick. The callback is one block tick, so it writes its own block and the
     * blocks it cascades into; eight entries cover it with a wide margin.
     */
    SCHEDULED_TICK(8);

    private final int reservationPerUnit;

    RegionTickSlice(int reservationPerUnit) {
        this.reservationPerUnit = reservationPerUnit;
    }

    /**
     * {@return the entries one admitted unit of this slice reserves in
     * {@link RegionDeferredBlockUpdates#MAX_ENTRIES}}
     */
    public int reservationPerUnit() {
        return this.reservationPerUnit;
    }
}
