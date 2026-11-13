/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Declares who may touch the vanilla entity section/index storage while region workers run.
 * <p>
 * Both persistent server storage (including physical fixed-grid section partitions) and transient
 * storage keep their by-id/by-uuid lookup, section maps and section ID trees in single-threaded
 * collections. Entity ticks move, remove and spawn entities and therefore mutate those collections,
 * so physical spatial partitioning alone does not permit entity ticks on a region worker. The mode
 * therefore decides whether the entity tick slice may leave the server thread at all
 * ({@link RegionThreadingEngine#beginEntityTickPlan}), and it is what makes the guarded storage surface
 * of the level meaningful: with {@link #REGION_OWNED} the guards hand a leased worker its own region's
 * section bucket and the batch barrier, and with {@link #MAIN_THREAD_ONLY} they reject the worker
 * outright.
 */
@ApiStatus.Internal
public enum RegionEntityStorageMode {
    /**
     * The vanilla storage stays single-threaded and is owned by the server thread.
     * <p>
     * Region workers never read or mutate it directly: native workers are rejected from the legacy entity
     * query surface ({@link RegionAwareEntityGetter}), from the public read surface of the persistent
     * section/index manager and from every guarded storage mutation, and region work only runs for tickers
     * whose compatibility declaration proves that they do not touch entity storage. Entity ticks
     * themselves stay on the server thread.
     */
    MAIN_THREAD_ONLY,
    /**
     * The entity tick slice runs on the region workers while the server thread waits for the batch.
     * <p>
     * The physical section partitions of a fixed layout are the region-private storage of a lease: a worker
     * reads only the bucket of the region it holds through {@link RegionScopedEntityQuery}, and every
     * storage effect of its ticks - the position change's section publication and chunk acquisition, the
     * removal's level callback and the admission of a new entity - is recorded and applied by the batch
     * barrier on the server thread. The shared vanilla section map, the by-id/by-uuid lookup and the
     * section id tree therefore stay server-owned; a worker that reaches one of them is rejected and
     * counted, not served. A level only supports this mode when its storage was partitioned with the fixed
     * layout of the engine (see {@link FixedEntitySectionPartitions}), which is why
     * {@link #forConfiguration(boolean, RegionLayout)} refuses it for a dynamic layout.
     */
    REGION_OWNED;

    /**
     * {@return the storage mode of a production engine built from the server configuration}
     * <p>
     * Entity worker ticks imply region-owned storage: a worker entity tick moves, removes and spawns
     * entities, and the region-owned guards are what keeps those mutations out of the shared vanilla
     * storage. Enabling the entity tick switch with a layout that cannot partition the storage (a dynamic
     * region may merge and split, so its sections have no stable region coordinate) therefore keeps the
     * whole entity slice on the server thread instead of shipping ticks to a worker that could not be
     * served by a region-private bucket.
     *
     * @param entityTickWorkers whether {@code enableRegionThreadingEntityTicks} is enabled
     * @param layout            the layout of the engine that is about to be installed
     */
    public static RegionEntityStorageMode forConfiguration(boolean entityTickWorkers, RegionLayout layout) {
        return entityTickWorkers && layout.isFixed() ? REGION_OWNED : MAIN_THREAD_ONLY;
    }
}
