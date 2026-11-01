/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * Waits for region worker work to finish before the server thread continues.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface RegionTickBarrier {
    /**
     * Blocks until {@code condition} returns {@code true}.
     * <p>
     * The server thread implementation keeps servicing chunk source tasks while it waits, because chunk
     * requests issued by entity code on a worker are dispatched back to the server thread and would
     * otherwise deadlock against the barrier.
     * <p>
     * This wait does not pump the {@code MinecraftServer} task queue. {@link RegionTickBatch#submitAndAwait}
     * therefore wraps the condition and runs the main-thread handoffs its workers published on the waiting
     * thread; a handoff scheduled on the server queue would never run during this wait.
     */
    void await(BooleanSupplier condition);
}
