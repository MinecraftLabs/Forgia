/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-tick publication of which region owns which entity of a level, read by native region workers.
 * <p>
 * A worker that asks for an entity of another region is refused, because the live entity of a running region
 * cannot be handed to another worker. The refusal names the operation but not where the entity is, which
 * leaves a crash report without the one fact an operator needs. The server thread therefore publishes an
 * immutable {@code UUID -> owning region} mapping once per tick, at the head of the level tick while every
 * region is quiescent, and the refusal of a cross-region lookup names the owner it found there.
 * <p>
 * The publication is a value, not a handle: it carries the region coordinate and the network id of an
 * identity, and never the {@link net.minecraft.world.entity.Entity}. Returning the foreign entity is exactly
 * what the ownership rule forbids, and a stale mapping is not better than a refusal - so the snapshot is used
 * to name the owner in the refusal and for nothing else.
 * <p>
 * The entries come from the regionizer's own entity index, so the publication names the version of the
 * mapping the regionizer would return without reading it from another thread. {@code EntityIdentityLedger}
 * publishes the lifecycle of the same identities; this publication adds the region they belong to.
 */
@ApiStatus.Internal
public final class RegionEntityIdentitySnapshots {
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicLong PUBLICATION_COUNT = new AtomicLong();
    private static final AtomicLong PUBLISHED_ENTRIES = new AtomicLong();
    private static final AtomicLong LAST_PUBLISH_NANOS = new AtomicLong();
    private static final AtomicLong TOTAL_PUBLISH_NANOS = new AtomicLong();
    private static final AtomicLong NAMED_CROSS_REGION_LOOKUPS = new AtomicLong();

    private RegionEntityIdentitySnapshots() {}

    /**
     * The published owner of one entity identity: the region coordinate that held the mapping and the network
     * id the entity had when the publication was taken.
     */
    public record Owner(int regionX, int regionZ, int networkId) {
        @Override
        public String toString() {
            return "region(" + this.regionX + "," + this.regionZ + ")";
        }
    }

    /**
     * One immutable publication of the entity ownership of a level.
     * <p>
     * The map is replaced, never mutated, so a reader either sees the previous publication or the next one.
     */
    public static final class Snapshot {
        private final Map<UUID, Owner> owners;
        private final long gameTime;

        private Snapshot(Map<UUID, Owner> owners, long gameTime) {
            this.owners = owners;
            this.gameTime = gameTime;
        }

        /** {@return the number of published identities} */
        public int size() {
            return this.owners.size();
        }

        /** {@return the game time of the level when the publication was taken} */
        public long gameTime() {
            return this.gameTime;
        }

        /**
         * {@return the published owner of {@code uuid}, or {@code null} when the identity is not published}
         * <p>
         * The answer is a value: it names the region without handing out the entity the region owns.
         */
        @Nullable
        public Owner ownerOf(UUID uuid) {
            return this.owners.get(uuid);
        }

        /**
         * {@return the published owner of the identity with network id {@code networkId}, or {@code null}
         * when no published identity has it}
         * <p>
         * The publication is keyed by UUID, because a UUID is the identity that survives a level change; a
         * network id is only ever asked for on the refusal path of a by-id miss, so the scan it needs is not
         * worth a second map.
         */
        @Nullable
        public Owner ownerOf(int networkId) {
            for (final Owner owner : this.owners.values()) {
                if (owner.networkId() == networkId) {
                    return owner;
                }
            }
            return null;
        }
    }

    /**
     * Publishes the entity ownership of {@code level}, called on the server thread at the head of the level
     * tick, before any region of the tick runs.
     */
    static void publish(ServerLevel level, ThreadedRegionizer regionizer) {
        final long start = System.nanoTime();
        final Map<UUID, Owner> owners = new HashMap<>();
        regionizer.forEach(region -> {
            if (region.coordinate().level() != level) {
                return;
            }
            final int regionX = region.coordinate().regionX();
            final int regionZ = region.coordinate().regionZ();
            for (final Map.Entry<UUID, net.minecraft.world.entity.Entity> entry : region.entitiesByUuid().entrySet()) {
                owners.put(entry.getKey(), new Owner(regionX, regionZ, entry.getValue().getId()));
            }
        });
        SNAPSHOTS.put(level, new Snapshot(Map.copyOf(owners), level.getGameTime()));
        PUBLICATION_COUNT.incrementAndGet();
        PUBLISHED_ENTRIES.set(owners.size());
        final long nanos = System.nanoTime() - start;
        LAST_PUBLISH_NANOS.set(nanos);
        TOTAL_PUBLISH_NANOS.addAndGet(nanos);
    }

    /**
     * {@return the published owner of {@code uuid}, or {@code null} when this tick does not know the identity}
     * <p>
     * The answer is the region coordinate of the owner and the network id of the entity. It never contains the
     * entity: a caller names the owner in its refusal and must not act as if the entity were available.
     */
    @Nullable
    public static Owner ownerOf(ServerLevel level, UUID uuid) {
        final Snapshot snapshot = SNAPSHOTS.get(level);
        return snapshot == null ? null : snapshot.ownerOf(uuid);
    }

    /** {@return the published owner of the identity with {@code networkId}, or {@code null} when it is unknown} */
    @Nullable
    public static Owner ownerOf(ServerLevel level, int networkId) {
        final Snapshot snapshot = SNAPSHOTS.get(level);
        return snapshot == null ? null : snapshot.ownerOf(networkId);
    }

    /** Counts one refused lookup that named the owning region of the entity it asked for. */
    static void recordNamedCrossRegionLookup() {
        NAMED_CROSS_REGION_LOOKUPS.incrementAndGet();
    }

    /** {@return the publication of {@code level} for the current tick, or {@code null} when there is none} */
    @Nullable
    public static Snapshot of(ServerLevel level) {
        return SNAPSHOTS.get(level);
    }

    /** Drops the publication of a level that is unloading, so its identities are not kept alive. */
    static void detach(ServerLevel level) {
        SNAPSHOTS.remove(level);
    }

    /** {@return the number of publications since the engine was installed} */
    public static long publicationCount() {
        return PUBLICATION_COUNT.get();
    }

    /** {@return the number of identities the last publication recorded} */
    public static long publishedEntries() {
        return PUBLISHED_ENTRIES.get();
    }

    /** {@return the wall time of the last publication in nanoseconds} */
    public static long lastPublishNanos() {
        return LAST_PUBLISH_NANOS.get();
    }

    /** {@return the mean wall time of one publication since the engine was installed, in nanoseconds} */
    public static long meanPublishNanos() {
        final long publications = PUBLICATION_COUNT.get();
        return publications == 0L ? 0L : TOTAL_PUBLISH_NANOS.get() / publications;
    }

    /** {@return the number of cross-region lookups whose refusal named the owning region} */
    public static long namedCrossRegionLookups() {
        return NAMED_CROSS_REGION_LOOKUPS.get();
    }

    /** {@return the status fragment of this publication, printed on the value snapshot line of the command} */
    public static String statusLine() {
        return "entityIdentities[publishes=" + PUBLICATION_COUNT.get()
                + ",entries=" + PUBLISHED_ENTRIES.get()
                + ",lastPublishNanos=" + LAST_PUBLISH_NANOS.get()
                + ",meanPublishNanos=" + meanPublishNanos()
                + ",namedCrossRegionLookups=" + NAMED_CROSS_REGION_LOOKUPS.get()
                + "]";
    }

    /** Resets every counter and drops every publication, called when an engine is installed and by tests. */
    @ApiStatus.Internal
    public static void reset() {
        SNAPSHOTS.clear();
        PUBLICATION_COUNT.set(0L);
        PUBLISHED_ENTRIES.set(0L);
        LAST_PUBLISH_NANOS.set(0L);
        TOTAL_PUBLISH_NANOS.set(0L);
        NAMED_CROSS_REGION_LOOKUPS.set(0L);
    }
}
