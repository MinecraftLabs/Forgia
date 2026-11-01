/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Neighbor update collection state of one region execution context.
 * <p>
 * A scope owns one {@link CollectingNeighborUpdater} per level updater that runs in the context. The
 * update stack, the deferred layer and the chained-update count of a cascade therefore stay inside the
 * context: two region workers, and a region worker and the server thread, never mutate the same
 * collection state.
 * <p>
 * The scope is bound to the executing thread only for the lifetime of the context. It is removed when
 * the context ends, so a worker thread never retains the state of a finished region.
 */
@ApiStatus.Internal
final class RegionNeighborUpdateScope {
    private static final ThreadLocal<RegionNeighborUpdateScope> CURRENT = new ThreadLocal<>();
    @Nullable
    private Map<RegionAwareNeighborUpdater, CollectingNeighborUpdater> updaters;

    RegionNeighborUpdateScope() {}

    /**
     * {@return the scope of the calling thread, or {@code null} outside a region execution context}
     */
    @Nullable
    static RegionNeighborUpdateScope current() {
        return CURRENT.get();
    }

    /**
     * Makes {@code scope} the scope of the calling thread.
     *
     * @return the scope that was active before, or {@code null} when there was none
     */
    @Nullable
    static RegionNeighborUpdateScope enter(RegionNeighborUpdateScope scope) {
        final RegionNeighborUpdateScope previous = CURRENT.get();
        CURRENT.set(scope);
        return previous;
    }

    /**
     * Restores the scope that was active before {@link #enter(RegionNeighborUpdateScope)}.
     */
    static void exit(@Nullable RegionNeighborUpdateScope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * {@return the collection state of {@code owner} inside this context}
     */
    NeighborUpdater updaterFor(RegionAwareNeighborUpdater owner) {
        if (this.updaters == null) {
            this.updaters = new IdentityHashMap<>();
        }
        return this.updaters.computeIfAbsent(owner, RegionAwareNeighborUpdater::newRegionUpdater);
    }
}
