/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Comparator;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * One entity of one region's entity tick index.
 * <p>
 * The entry is immutable: a membership change replaces it with a new entry of the same type, so a reader of a
 * snapshot never observes a half-updated entry. {@link #sequence()} is the entity's position in the level's
 * entity tick list and is the region's tick order; it is assigned when the entity first enters the list and
 * kept when the entity's mapping moves to another region, so the relative order inside a region is the order
 * the level's own tick list has.
 * <p>
 * {@link #ticking()} is set while the level's entity tick list contains the entity: an entity that is mapped
 * to the region but not ticking (a non-ticking chunk, or an entity that only got a mapping from a scheduled
 * task) is not part of the tick. {@link #serverThreadOnly()} marks an always-ticking entity (a player): the
 * region worker must not tick it, so its region hands it to the server thread.
 */
@ApiStatus.Internal
record RegionEntityTickEntry(Entity entity, long sequence, boolean serverThreadOnly, boolean ticking) {

    static final Comparator<RegionEntityTickEntry> BY_SEQUENCE = Comparator.comparingLong(RegionEntityTickEntry::sequence);
    RegionEntityTickEntry withTicking(long newSequence) {
        return new RegionEntityTickEntry(this.entity, newSequence, this.serverThreadOnly, true);
    }

    RegionEntityTickEntry withoutTicking() {
        return new RegionEntityTickEntry(this.entity, this.sequence, this.serverThreadOnly, false);
    }
}
