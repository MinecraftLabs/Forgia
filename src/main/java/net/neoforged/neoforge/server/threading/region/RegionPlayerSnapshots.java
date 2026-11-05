/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-tick publication of the player list of a level, read by native region workers.
 * <p>
 * A player is an always-ticking entity that only the server thread ticks, so a worker may not walk the
 * level's live player list ({@code ServerLevel.players}, a mutable {@code ArrayList}) and may not answer a
 * player lookup from its own region: the players a targeting AI, an aura or a {@code getPlayerByUUID} lookup
 * asks for are level-wide, not region-wide. The server thread therefore publishes one immutable snapshot of
 * the list once per tick, at the head of the level tick, and every worker answers from it. This is the same
 * treatment the regionized POI storage gives its published sections.
 * <p>
 * The snapshot is replaced, never mutated: a worker either sees the previous snapshot or the next one, both
 * complete. Its staleness is exactly one tick - a player who logged in, logged out or changed dimension after
 * the publication is not in the snapshot the worker reads, and a recorded bounding box is the one the player had
 * when the tick opened. The recorded bounding box is what a region-scoped box query intersects with the
 * caller's box, so a box that clips a player's body answers like the vanilla section scan instead of only
 * counting a player whose centre happens to lie inside; the entity the caller receives is the live
 * {@link ServerPlayer}, so a caller's own predicate and targeting test read the current state exactly like the
 * vanilla loop does.
 * <p>
 * A level without a published snapshot - the engine is not installed, the level was never ticked through the
 * bridge, or its state was cleared on unload - answers {@code null}. A caller that is not a native region worker
 * then keeps the previous behaviour of reading the live list; a worker is refused by
 * {@code RegionThreadingHooks.playersOfLevel}, because the live list is the server-owned storage the snapshot
 * replaces.
 */
@ApiStatus.Internal
public final class RegionPlayerSnapshots {
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicLong PUBLICATION_COUNT = new AtomicLong();

    private RegionPlayerSnapshots() {}

    /**
     * One player of a snapshot: the immutable lookup keys and the bounding box the player had when the tick
     * opened, next to the live entity the caller receives.
     */
    public record Entry(UUID uuid, int entityId, AABB boundingBox, ServerPlayer player) {
        /** {@return true when the recorded bounding box of this entry intersects {@code box}} */
        public boolean intersects(AABB box) {
            return box.intersects(this.boundingBox);
        }
    }

    /**
     * One immutable publication of the player list of a level.
     * <p>
     * {@link #players()} is the immutable list the level's player lookups iterate, and {@link #entries()} is
     * the same list with the recorded keys and bounding boxes, which is what a region-scoped box query
     * intersects with the caller's box.
     */
    public static final class Snapshot {
        private final List<ServerPlayer> players;
        private final List<Entry> entries;
        private final long gameTime;

        private Snapshot(List<ServerPlayer> players, long gameTime) {
            this.players = players;
            final List<Entry> recorded = new ArrayList<>(players.size());
            for (final ServerPlayer player : players) {
                recorded.add(new Entry(player.getUUID(), player.getId(), player.getBoundingBox(), player));
            }
            this.entries = List.copyOf(recorded);
            this.gameTime = gameTime;
        }

        /** {@return the published players, as an immutable list} */
        public List<ServerPlayer> players() {
            return this.players;
        }

        /** {@return the published entries, with the keys and bounding boxes recorded at publication time} */
        public List<Entry> entries() {
            return this.entries;
        }

        /** {@return the game time of the level when the snapshot was published} */
        public long gameTime() {
            return this.gameTime;
        }
    }

    /**
     * Publishes the player list of {@code level}, called on the server thread at the head of its level tick.
     * <p>
     * The live list is copied, so the publication never observes a list a later tick mutates, and the
     * publication is one reference write per level. Nothing is published when the level's list is not
     * available yet.
     */
    static void publish(ServerLevel level) {
        final List<ServerPlayer> live = level.players();
        if (live == null) {
            return;
        }
        SNAPSHOTS.put(level, new Snapshot(List.copyOf(live), level.getGameTime()));
        PUBLICATION_COUNT.incrementAndGet();
    }

    /** {@return the snapshot published for {@code level} in the current tick, or {@code null} when there is none} */
    @Nullable
    public static Snapshot of(ServerLevel level) {
        return SNAPSHOTS.get(level);
    }

    /** Drops the snapshot of a level that is unloading, so its player list is not kept alive. */
    static void detach(ServerLevel level) {
        SNAPSHOTS.remove(level);
    }

    /** {@return the number of snapshots published since the engine was installed} */
    static long publicationCount() {
        return PUBLICATION_COUNT.get();
    }

    static void reset() {
        SNAPSHOTS.clear();
        PUBLICATION_COUNT.set(0L);
    }
}
