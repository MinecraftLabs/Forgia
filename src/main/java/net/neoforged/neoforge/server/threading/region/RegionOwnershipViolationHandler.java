/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class RegionOwnershipViolationHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final AtomicLong VIOLATION_COUNT = new AtomicLong();
    private static final AtomicLong ENTITY_ADDITION_REJECTION_COUNT = new AtomicLong();
    private static final Set<String> REPORTED_STORAGE_ACCESSES = ConcurrentHashMap.newKeySet();

    private RegionOwnershipViolationHandler() {}

    static void handle(String message) {
        VIOLATION_COUNT.incrementAndGet();
        switch (RegionThreadingConfig.ownershipViolationMode()) {
            case DISABLED -> {}
            case WARN -> LOGGER.warn(message);
            case THROW -> throw new IllegalStateException(message);
        }
    }

    /**
     * Counts one ownership violation whose report was already produced by the same caller, so a repeated
     * violation keeps the count exact without repeating the log line.
     */
    static void recordViolation() {
        VIOLATION_COUNT.incrementAndGet();
    }

    /**
     * Rejects a server-owned entity storage access issued from a native region worker.
     * <p>
     * Unlike {@link #handle(String)} this rejection is unconditional: it never consults
     * {@code ownershipViolationMode}. Allowing a worker into the shared vanilla section/index storage
     * would race the server thread regardless of whether the violation is only logged. The operation
     * name is part of the thrown message and is reported once per distinct operation so a misbehaving
     * worker cannot flood the log.
     */
    public static void rejectNativeWorkerStorageAccess(String operation) {
        VIOLATION_COUNT.incrementAndGet();
        final String message = "Native region worker attempted server-owned entity storage access: " + operation;
        if (REPORTED_STORAGE_ACCESSES.add(operation)) {
            LOGGER.error(message);
        }
        throw new IllegalStateException(message);
    }

    static long violationCount() {
        return VIOLATION_COUNT.get();
    }

    /**
     * Counts the entity admission of a native region worker that was rejected before it wrote any storage.
     * <p>
     * The count is separate from {@link #violationCount()} so the additions a worker attempted - death loot,
     * experience orbs, projectiles, a spawn - stay observable after the removals of an owned entity became
     * grantable. Every counted addition is also a storage violation, because the guard throws.
     */
    public static void recordEntityAdditionRejection() {
        ENTITY_ADDITION_REJECTION_COUNT.incrementAndGet();
    }

    static long entityAdditionRejectionCount() {
        return ENTITY_ADDITION_REJECTION_COUNT.get();
    }

    static void reset() {
        VIOLATION_COUNT.set(0L);
        ENTITY_ADDITION_REJECTION_COUNT.set(0L);
    }
}
