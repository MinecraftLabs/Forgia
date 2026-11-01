/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Narrow adapter from a level's {@link EntityIdentityLedger} to the epoch a transfer token records.
 * <p>
 * The token only needs the published lifecycle epoch of one UUID. Keeping that read behind this interface
 * keeps the token format independent of the ledger implementation and documents the one identity source
 * the transfer transaction uses: {@code ServerLevel.entityIdentityLedger().current(uuid).epoch()}.
 * <p>
 * Obtaining the ledger is a server-owned storage read, so this adapter must be created on the server
 * thread. Once created, {@link #epoch(UUID)} only calls the thread-safe
 * {@link EntityIdentityLedger#current(UUID)} read and is safe from any thread.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface RegionEntityTransferEpochSource {
    /** Returned when the ledger has no current record for the UUID. */
    long MISSING_EPOCH = Long.MIN_VALUE;

    /** {@return the published lifecycle epoch of {@code uuid}, or {@link #MISSING_EPOCH}} */
    long epoch(UUID uuid);

    /** {@return an adapter over {@code ledger}, or an always-missing adapter for {@code null}} */
    static RegionEntityTransferEpochSource of(@Nullable EntityIdentityLedger ledger) {
        if (ledger == null) {
            return uuid -> MISSING_EPOCH;
        }
        return uuid -> {
            final EntityIdentity identity = ledger.current(uuid);
            return identity == null ? MISSING_EPOCH : identity.epoch();
        };
    }
}
