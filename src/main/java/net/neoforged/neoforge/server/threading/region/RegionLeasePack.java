/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Leases several regions of one level tick to one worker task, in a canonical order and all-or-nothing.
 * <p>
 * <strong>Exclusivity.</strong> A pack only holds {@link RegionState#tryMarkRunning() the region lease} that
 * every other acquisition holds; no region is leased twice, and each region is released by the group that ticks
 * it, so a region is still held by exactly one worker for the whole task.
 * <p>
 * <strong>Order.</strong> A pack acquires its regions in one order that every pack of every slice uses: region
 * x, then region z, of the level tick. The order is what makes holding several leases safe when two acquirers
 * run at once: whoever holds one region of the pair waits for a region that sorts behind it, so a wait cycle
 * cannot close. It is not the only thing that makes the acquisition safe - a lease is only ever taken on the
 * publishing server thread, and a worker only releases leases and never takes one - but it keeps that property
 * when a second acquirer is ever added, instead of making it a hidden assumption.
 * <p>
 * <strong>All or nothing.</strong> A pack that cannot lease one of its regions releases every region it already
 * took and reports the failure, so a region can never stay {@code TICKING} behind a pack that will not tick it.
 * The caller then falls back to the unpacked path, which waits for a busy region instead of failing on it, and
 * counts the fallback.
 */
@ApiStatus.Internal
final class RegionLeasePack {
    /**
     * The order in which a pack acquires its regions: region x, then region z, of the level tick. Every pack of
     * every slice uses it, so the order is a total order over the regions of one acquisition.
     */
    static final Comparator<RegionCoordinate> CANONICAL_ORDER = Comparator.comparingInt(RegionCoordinate::regionX)
            .thenComparingInt(RegionCoordinate::regionZ);

    /**
     * Largest number of regions one task ticks. A pack amortises one submission and one completion over its
     * regions, and a task cannot be split between workers, so the bound keeps the longest lease window and the
     * coarsest unit of load balancing bounded as well.
     */
    static final int MAX_REGIONS_PER_TASK = 16;

    /**
     * Tasks one slice is split into per worker. Two tasks per worker spread the regions of uneven regions over
     * the pool: a region of hoppers that transfers this tick costs several times a region that does not, and a
     * task cannot be split between workers, so one task per worker would leave the barrier waiting for whichever
     * worker happened to receive the expensive regions.
     */
    static final int TASKS_PER_WORKER = 2;

    private RegionLeasePack() {}

    /**
     * {@return the number of regions one packed task ticks}
     * <p>
     * The regions of a slice are spread over the pool, so a tick with {@code regionCount} regions and
     * {@code workerCount} workers submits about {@value #TASKS_PER_WORKER} tasks per worker instead of one task per
     * region. A slice with fewer regions than tasks is not packed at all: a pack is only worth a shared lease
     * window when it holds more than one region, and a single region per task keeps the whole pool busy.
     */
    static int packSize(int regionCount, int workerCount) {
        if (regionCount < 2 || workerCount < 2) {
            return 1;
        }
        final int taskCount = workerCount * TASKS_PER_WORKER;
        return Math.min(MAX_REGIONS_PER_TASK, Math.max(1, (regionCount + taskCount - 1) / taskCount));
    }

    /**
     * {@return the packs of {@code regions}, each one in the canonical lease order}
     * <p>
     * The packs are balanced: the regions of {@code regions} are split into {@code ceil(size / packSize)} packs
     * whose sizes differ by at most one, so the worker that ticks the largest pack ticks at most one region more
     * than the equal share. Splitting on a fixed pack size would leave the last pack short - 51 regions on 4
     * workers would become 16, 16, 16 and 3 - and the barrier waits for the slowest pack, so the imbalance would
     * be paid in full.
     */
    static List<List<RegionState>> split(List<RegionState> regions, int workerCount) {
        final int size = regions.size();
        if (size == 0) {
            return List.of();
        }
        final int packSize = packSize(size, workerCount);
        final int taskCount = Math.max(1, (size + packSize - 1) / packSize);
        final List<List<RegionState>> packs = new ArrayList<>(taskCount);
        int index = 0;
        for (int task = 0; task < taskCount; ++task) {
            final int share = size / taskCount + (task < size % taskCount ? 1 : 0);
            packs.add(List.copyOf(regions.subList(index, index + share)));
            index += share;
        }
        return packs;
    }

    /**
     * {@return the regions of {@code regions} in the canonical lease order}
     */
    static List<RegionState> canonicalOrder(Collection<RegionState> regions) {
        final List<RegionState> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.comparing(RegionState::coordinate, CANONICAL_ORDER));
        return ordered;
    }

    /**
     * Takes the lease of every region of {@code pack}, in the canonical order.
     *
     * @param pack    the regions to lease
     * @param release releases one lease this method took; the caller owns the cleanup of a region lease, so the
     *                pack never touches region retirement itself
     * @return the leased regions in the canonical order, or {@code null} when one of them could not be leased;
     *         a failed acquisition releases every lease it already took before it returns
     */
    @Nullable
    static List<RegionState> tryAcquireAll(List<RegionState> pack, Consumer<RegionState> release) {
        final List<RegionState> ordered = canonicalOrder(pack);
        final List<RegionState> acquired = new ArrayList<>(ordered.size());
        for (final RegionState region : ordered) {
            if (!region.tryMarkRunning()) {
                for (final RegionState taken : acquired) {
                    release.accept(taken);
                }
                return null;
            }
            acquired.add(region);
        }
        return acquired;
    }
}
