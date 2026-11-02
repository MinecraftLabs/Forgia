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
 * so physical spatial partitioning alone does not permit entity ticks on a region worker.
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
     * Internal mechanics-test assumption only: the entity tick worker plan may be created so the batch
     * mechanics stay covered. A worker that owns a region lease may read only that region's physical
     * section bucket through the region-scoped range query; every other partition access remains
     * server-owned. No production constructor selects this mode, so this is not a working storage mode and
     * does not make worker entity ticks safe.
     */
    REGION_OWNED
}
