/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Submits region-scoped work to a worker pool.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface RegionWorkerExecutor {
    /**
     * Submits {@code task} for asynchronous execution.
     *
     * @return {@code true} when the pool accepted the task, {@code false} when it rejected it (for
     *         example because the pool is already shut down); rejected tasks are never run by the pool
     */
    boolean execute(String description, Runnable task);
}
