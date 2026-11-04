/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters of the region-partitioned POI storage.
 * <p>
 * The counters are the observable half of the staleness policy: a local query is answered from the querying
 * region's live sections, a published query from another region's snapshot and is therefore at most one
 * publish old, and a fallback query could not be answered from the calling thread at all and ran on the
 * server thread instead. {@link #recordStaleTake()} counts a take whose candidate the owning region no longer
 * holds, i.e. a query that read a record a publish had already replaced with a removal; the take is refused
 * instead of booking a ticket on a POI that no longer exists.
 */
final class RegionPoiCounters {
    private static final AtomicLong LOCAL_QUERIES = new AtomicLong();
    private static final AtomicLong PUBLISHED_QUERIES = new AtomicLong();
    private static final AtomicLong FALLBACK_QUERIES = new AtomicLong();
    private static final AtomicLong STALE_TAKES = new AtomicLong();
    private static final AtomicLong DEFERRED_WRITES = new AtomicLong();
    private static final AtomicLong PUBLISHES = new AtomicLong();
    private static final AtomicLong LOADED_COLUMNS = new AtomicLong();
    private static final AtomicLong SAVED_COLUMNS = new AtomicLong();
    private static final AtomicLong VILLAGE_DISTANCE_QUERIES = new AtomicLong();

    private RegionPoiCounters() {}

    static void recordLocalQuery() {
        LOCAL_QUERIES.incrementAndGet();
    }

    static void recordPublishedQuery() {
        PUBLISHED_QUERIES.incrementAndGet();
    }

    static void recordFallbackQuery() {
        FALLBACK_QUERIES.incrementAndGet();
    }

    static void recordStaleTake() {
        STALE_TAKES.incrementAndGet();
    }

    static void recordDeferredWrite() {
        DEFERRED_WRITES.incrementAndGet();
    }

    static void recordPublish() {
        PUBLISHES.incrementAndGet();
    }

    static void recordLoadedColumn() {
        LOADED_COLUMNS.incrementAndGet();
    }

    static void recordSavedColumn() {
        SAVED_COLUMNS.incrementAndGet();
    }

    static void recordVillageDistanceQuery() {
        VILLAGE_DISTANCE_QUERIES.incrementAndGet();
    }

    static RegionThreadingDiagnostics.PoiCounters snapshot() {
        return new RegionThreadingDiagnostics.PoiCounters(
                LOCAL_QUERIES.get(),
                PUBLISHED_QUERIES.get(),
                FALLBACK_QUERIES.get(),
                STALE_TAKES.get(),
                DEFERRED_WRITES.get(),
                PUBLISHES.get(),
                LOADED_COLUMNS.get(),
                SAVED_COLUMNS.get(),
                VILLAGE_DISTANCE_QUERIES.get());
    }

    static void reset() {
        LOCAL_QUERIES.set(0L);
        PUBLISHED_QUERIES.set(0L);
        FALLBACK_QUERIES.set(0L);
        STALE_TAKES.set(0L);
        DEFERRED_WRITES.set(0L);
        PUBLISHES.set(0L);
        LOADED_COLUMNS.set(0L);
        SAVED_COLUMNS.set(0L);
        VILLAGE_DISTANCE_QUERIES.set(0L);
    }
}
