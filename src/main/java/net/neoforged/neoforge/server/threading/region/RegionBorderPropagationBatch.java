/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * One border-propagation pass of one region.
 * <p>
 * The batch carries exactly one group: the captured updates that target the region and the lease that lets the
 * group apply them. It has no entity, block-entity, chunk, spawn or scheduled-tick unit, so a re-run of a region
 * only runs the propagation of its blocks and never repeats the work of a slice. A scheduled tick the cascade
 * starts is recorded in {@link #regionTickMutations()} and applied by the server thread after the pass, exactly
 * like the scheduled tick of any other region batch.
 */
@ApiStatus.Internal
final class RegionBorderPropagationBatch extends RegionTickBatch {
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();

    RegionBorderPropagationBatch(ServerLevel level, int blockUpdateEntryBudget) {
        super(level, RegionTickSlice.PROPAGATION, blockUpdateEntryBudget);
    }

    /**
     * Adds the one group of this pass and registers its worker task.
     *
     * @param region the region whose lease the group holds
     * @param work   the propagation body, run on the worker that leases {@code region}
     */
    void addGroup(RegionState region, Runnable work) {
        this.addTask("border propagation " + region.coordinate(), this.groupWork(work));
    }

    /** {@return the scheduled-tick mutations the cascade of the group recorded} */
    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
    }
}
