/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The region transitions of players: walking across a border, a teleport, a portal and a dimension change.
 * <p>
 * A player is an always-ticking entity, so a region never ticks it: the region whose entity tick index holds it
 * hands it to the server thread. Which region that is only decides <em>when</em> and <em>from where</em> the
 * handoff happens, and a mapping that names a region the player already left is still safe for one tick - but a
 * mapping that names no region at all loses the handoff, and a player that is mapped into two regions would be
 * handed over twice. This class makes the mapping of a player follow the player at one defined point and asserts
 * that it is exactly one region of the level the player is in.
 * <p>
 * <b>Published point.</b> The region of every player of a level is published at the start of that level's entity
 * slice, on the server thread, before the batch of that slice is published - {@code ServerLevel.tick} calls
 * {@link RegionThreadingEngine#beginEntityTickPlan} there, and no region of the level is leased at that moment.
 * Every kind of transition is published at that same point and nowhere else:
 * <ul>
 * <li>a walk across a border,</li>
 * <li>a teleport of any distance, including one that happens inside the player's own tick or after it,</li>
 * <li>a portal, which for a player runs inline in its tick,</li>
 * <li>a dimension change: the old level's mapping is retired by the level-leave hook of the removal, and the new
 * level's mapping is published at the start of the new level's next entity slice. A level that ticks after the
 * transition publishes it in the same server tick; a level that already ticked publishes it in the next one. The
 * two seams that used to publish a player's mapping outside the tick - the join event and the tick list
 * membership - only record the player here, so a dimension change has one writer like every other
 * transition.</li>
 * </ul>
 * The transfer is therefore visible to the target region at the next entity slice that reads the region's index,
 * which is the slice that publishes it: the target region's worker enumerates the migrated index entry of the
 * player in the batch of that slice. A publication while a batch of the level is in flight, after a shutdown or
 * for a closed level identity is refused and counted, because the defined point is quiescent.
 * <p>
 * <b>The kind of a transition</b> is the cause observed at the publication point. A level change is the kind the
 * travel hook recorded before the player left its level - a {@link Kind#PORTAL} when the portal cooldown the
 * vanilla portal path sets immediately before it resolves the destination was live, a {@link Kind#DIMENSION}
 * otherwise - and a level change without a recorded travel is a {@link Kind#DIMENSION}. A same-level move of more
 * than {@value #TELEPORT_DISTANCE_BLOCKS} blocks since the last publication is a {@link Kind#TELEPORT}, because no
 * tick of player movement covers a chunk; everything else is a {@link Kind#WALK}.
 * <p>
 * <b>The invariant</b> is checked after every publication: the player is mapped into exactly one region, that
 * region belongs to the level the player is in, and it is the region of the player's position. A violation is
 * counted and thrown, because a player in no region or in two regions is a lost or a duplicated handoff, not an
 * approximation. {@link #audit} additionally scans every region of the regionizer for a second index entry of a
 * player, which is the structural form of "in two regions".
 * <p>
 * The players of a level are the players whose tick membership the level published: the tick list hook records
 * them, so the publication reads no other state of the level and the set is exactly the players the region index
 * of the level holds. All state of this class is written on the server thread; the counters are read from any
 * thread.
 */
@ApiStatus.Internal
final class PlayerRegionTransitions {
    /** A same-level move of more than one chunk between two publications cannot be walking. */
    static final int TELEPORT_DISTANCE_BLOCKS = 16;

    /** The cause of one published player region transition. */
    enum Kind {
        WALK,
        TELEPORT,
        PORTAL,
        DIMENSION
    }

    /** The last publication of one player: the level, the region and the position it was published at. */
    private static final class Publication {
        private ServerLevel level;
        private int regionX;
        private int regionZ;
        private BlockPos position;

        private Publication(ServerLevel level, RegionCoordinate coordinate, BlockPos position) {
            this.update(level, coordinate, position);
        }

        private void update(ServerLevel level, RegionCoordinate coordinate, BlockPos position) {
            this.level = level;
            this.regionX = coordinate.regionX();
            this.regionZ = coordinate.regionZ();
            this.position = position;
        }

        private boolean names(ServerLevel level, RegionCoordinate coordinate) {
            return this.level == level && this.regionX == coordinate.regionX() && this.regionZ == coordinate.regionZ();
        }
    }

    /** Server thread only: the publication of every player that has one, keyed by player identity. */
    private final Map<ServerPlayer, Publication> published = new IdentityHashMap<>();
    /**
     * Server thread only: the transition a dimension change announced before it happened, keyed by player.
     * <p>
     * The travel hook runs before the player leaves its level, so the kind of the transition is known there and
     * consumed by the publication that follows it. A travel that is cancelled leaves the entry behind; it is
     * dropped by the next publication of that player that does not change the level.
     */
    private final Map<ServerPlayer, Kind> requests = new IdentityHashMap<>();
    /**
     * The players whose tick membership each level published, i.e. the players that have to have a region of that
     * level. Weak on the level, so an unloaded level is not kept alive by its player set.
     */
    private final Map<ServerLevel, Set<ServerPlayer>> trackedPlayers = Collections.synchronizedMap(new WeakHashMap<>());

    private final AtomicLong entries = new AtomicLong();
    private final AtomicLong departures = new AtomicLong();
    private final AtomicLong walkTransitions = new AtomicLong();
    private final AtomicLong teleportTransitions = new AtomicLong();
    private final AtomicLong portalTransitions = new AtomicLong();
    private final AtomicLong dimensionTransitions = new AtomicLong();
    private final AtomicLong transitionsDuringBatch = new AtomicLong();
    private final AtomicLong refusedTransitions = new AtomicLong();
    private final AtomicLong deferredArrivals = new AtomicLong();
    private final AtomicLong identityHandovers = new AtomicLong();
    private final AtomicLong invariantChecks = new AtomicLong();
    private final AtomicLong invariantFailures = new AtomicLong();

    /**
     * Publishes the region of every player of {@code level} at the defined point.
     * <p>
     * Runs on the server thread at the start of the level's entity slice, before its batch is published. A
     * publication that is asked for while a batch of the level is in flight, after the engine shut down or for a
     * closed level identity is refused and counted instead of writing a mapping a worker could observe
     * half-applied.
     */
    void publish(RegionThreadingEngine engine, ServerLevel level) {
        if (engine.isShutDown() || engine.isLevelClosed(level)) {
            this.refusedTransitions.incrementAndGet();
            return;
        }
        if (engine.hasActiveBatch(level)) {
            // The defined point is quiescent: a batch in flight owns the region index of this level, so a
            // mapping written here would be a half-applied tick. The transition is published at the next
            // start of the entity slice instead, and the attempt is counted.
            this.transitionsDuringBatch.incrementAndGet();
            this.refusedTransitions.incrementAndGet();
            return;
        }
        final List<ServerPlayer> players = this.playersOf(level);
        for (final ServerPlayer player : players) {
            this.publishOne(engine, level, player);
        }
        this.forgetDepartedPlayers(level, players);
    }

    private void publishOne(RegionThreadingEngine engine, ServerLevel level, ServerPlayer player) {
        if (player.isRemoved()) {
            this.refusedTransitions.incrementAndGet();
            return;
        }
        final BlockPos position = player.blockPosition();
        final RegionCoordinate coordinate = engine.regionAt(level, position);
        final Publication previous = this.published.get(player);
        final RegionState current = engine.regionFor(player);
        if (previous != null && previous.names(level, coordinate) && current != null && current.coordinate().equals(coordinate)) {
            // The player is in the region it is mapped to: not a transition. The position is still recorded,
            // because the displacement of the next publication is measured against it.
            previous.update(level, coordinate, position);
            this.requests.remove(player);
            return;
        }
        final Kind kind = this.classify(player, level, previous, position);
        final RegionState region = engine.publishPlayerRegion(level, player, coordinate);
        if (region == null) {
            // The level identity closed between the check above and the write, or the regionizer refused the
            // level: the player keeps no mapping in this level and the next publication retries it.
            this.refusedTransitions.incrementAndGet();
            return;
        }
        this.assertExactlyOneRegion(engine, level, player, coordinate, region);
        if (kind != null) {
            this.departures.incrementAndGet();
            this.count(kind);
        }
        this.entries.incrementAndGet();
        if (previous == null) {
            this.published.put(player, new Publication(level, coordinate, position));
        } else {
            previous.update(level, coordinate, position);
        }
        this.requests.remove(player);
    }

    /**
     * {@return the cause of the transition the publication is about to write, or {@code null} when this is the
     * first mapping of the player in the level and therefore no transition}
     * <p>
     * The travel hook records the kind before the player leaves its level; a level change without a recorded
     * travel is a dimension change that reached the publication point by another path. Everything else is decided
     * by the displacement since the last publication.
     */
    @Nullable
    private Kind classify(ServerPlayer player, ServerLevel level, @Nullable Publication previous, BlockPos position) {
        final Kind requested = this.requests.get(player);
        if (requested != null) {
            return requested;
        }
        if (previous == null) {
            return null;
        }
        if (previous.level != level) {
            return Kind.DIMENSION;
        }
        if (previous.position.distSqr(position) > (long) TELEPORT_DISTANCE_BLOCKS * TELEPORT_DISTANCE_BLOCKS) {
            return Kind.TELEPORT;
        }
        return Kind.WALK;
    }

    private void count(Kind kind) {
        switch (kind) {
            case WALK -> this.walkTransitions.incrementAndGet();
            case TELEPORT -> this.teleportTransitions.incrementAndGet();
            case PORTAL -> this.portalTransitions.incrementAndGet();
            case DIMENSION -> this.dimensionTransitions.incrementAndGet();
        }
    }

    /**
     * Asserts that the mapping that was just published is exactly one region of {@code level}, and that it is the
     * region of the player's position.
     * <p>
     * This is the cheap half of the invariant, checked after every publication: the regionizer holds one mapping
     * per entity, so a player that is mapped at all is mapped once, and the read back proves that the write
     * landed. The structural half - no second index entry of the same player in another region - is the
     * {@link #audit}.
     */
    private void assertExactlyOneRegion(RegionThreadingEngine engine, ServerLevel level, ServerPlayer player, RegionCoordinate coordinate, RegionState publishedRegion) {
        this.invariantChecks.incrementAndGet();
        final RegionState current = engine.regionFor(player);
        if (current == publishedRegion && publishedRegion.coordinate().equals(coordinate)) {
            return;
        }
        this.invariantFailures.incrementAndGet();
        throw new IllegalStateException("A player is not in exactly one region of its level after its region transition was published: "
                + player + " in " + level.dimension().location() + " at " + player.blockPosition()
                + " published to " + coordinate.regionX() + "/" + coordinate.regionZ()
                + " but reads back " + (current == null ? "no region of that level" : current.coordinate().regionX() + "/" + current.coordinate().regionZ()));
    }

    /**
     * Drops the publication records of players that left {@code level}, so the bookkeeping stays bounded by the
     * players that have a region.
     * <p>
     * The record of a player that travelled to another level is consumed by the publication of the destination
     * level, which runs before this level's next entity slice: the destination level of a dimension change either
     * ticks later in the same server tick or earlier in the next one.
     */
    private void forgetDepartedPlayers(ServerLevel level, List<ServerPlayer> players) {
        this.published.entrySet().removeIf(entry -> entry.getValue().level == level && !players.contains(entry.getKey()));
        this.requests.keySet().removeIf(player -> !this.isTrackedAnywhere(player));
    }

    /**
     * Records that the tick list of {@code level} contains {@code player}, so its region is published at the next
     * defined point.
     */
    void trackPlayer(ServerLevel level, ServerPlayer player) {
        synchronized (this.trackedPlayers) {
            this.trackedPlayers.computeIfAbsent(level, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(player);
        }
    }

    /** Records that the tick list of {@code level} no longer contains {@code player}. */
    void untrackPlayer(ServerLevel level, ServerPlayer player) {
        synchronized (this.trackedPlayers) {
            final Set<ServerPlayer> players = this.trackedPlayers.get(level);
            if (players != null) {
                players.remove(player);
            }
        }
    }

    /**
     * Records that {@code player} is about to change level, so the publication that follows it counts the
     * transition with its real cause and a change that happens while a batch of the level is in flight is counted
     * as such.
     * <p>
     * The travel hook runs before the player leaves its level, so the level of the player is still the source
     * level. The entry is consumed by the publication that publishes the arrival; a cancelled travel leaves it
     * behind and the next publication of the player that does not change the level drops it.
     */
    void noteDimensionChange(RegionThreadingEngine engine, ServerPlayer player) {
        if (engine.isShutDown()) {
            this.refusedTransitions.incrementAndGet();
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (engine.hasActiveBatch(level)) {
            this.transitionsDuringBatch.incrementAndGet();
        }
        this.requests.put(player, player.getPortalCooldown() > 0 ? Kind.PORTAL : Kind.DIMENSION);
    }

    /**
     * {@return true when the tick membership of {@code player} in {@code level} is published at the defined point
     * instead of by the caller}
     * <p>
     * A player that already has a region of the level is a re-insertion into the tick list after a stop: only the
     * membership has to be published, and the caller keeps doing that. A player without a region of the level - a
     * login, or the arrival half of a dimension change - is recorded and published with the level's next entity
     * slice.
     */
    boolean deferArrival(RegionThreadingEngine engine, ServerLevel level, ServerPlayer player) {
        this.trackPlayer(level, player);
        if (engine.regionFor(player) != null) {
            return false;
        }
        this.deferredArrivals.incrementAndGet();
        return true;
    }

    /**
     * Records that the identity of a travelling player was handed to the ledger of the destination level, so the
     * entry continues instead of being reserved again there.
     */
    void recordIdentityHandover() {
        this.identityHandovers.incrementAndGet();
    }

    /**
     * {@return the audit of the player region invariant of {@code level}}
     * <p>
     * A player of the level is <em>unplaced</em> when no region of the level maps it and <em>duplicated</em> when
     * the region tick indexes of the regionizer hold it more than once. Both are violations of the invariant and
     * are counted in the diagnostics. A <em>misplaced</em> player is one whose single mapping names another
     * region than the one of its current position: that is the mapping of a move inside a tick, which the next
     * publication point corrects, and it is reported next to the violations instead of being asserted here.
     */
    PlayerRegionAudit audit(RegionThreadingEngine engine, ServerLevel level) {
        final List<ServerPlayer> players = this.playersOf(level);
        int placed = 0;
        int unplaced = 0;
        int duplicated = 0;
        int misplaced = 0;
        String firstProblem = "-";
        for (final ServerPlayer player : players) {
            final RegionState region = engine.regionFor(player);
            final int indexes = engine.indexedRegionCount(player);
            if (region == null || indexes == 0) {
                unplaced++;
                if ("-".equals(firstProblem)) {
                    firstProblem = player + " at " + player.blockPosition() + " is in no region of " + level.dimension().location()
                            + " (" + indexes + " region indexes hold it)";
                }
                continue;
            }
            if (indexes > 1) {
                duplicated++;
                if ("-".equals(firstProblem)) {
                    firstProblem = player + " at " + player.blockPosition() + " is indexed by " + indexes + " regions of "
                            + level.dimension().location();
                }
                continue;
            }
            if (!region.coordinate().equals(engine.regionAt(level, player.blockPosition()))) {
                misplaced++;
                if ("-".equals(firstProblem)) {
                    firstProblem = player + " at " + player.blockPosition() + " is mapped to "
                            + region.coordinate().regionX() + "/" + region.coordinate().regionZ() + " of " + level.dimension().location();
                }
                continue;
            }
            placed++;
        }
        this.invariantChecks.incrementAndGet();
        final int failures = unplaced + duplicated;
        if (failures > 0) {
            this.invariantFailures.addAndGet(failures);
        }
        return new PlayerRegionAudit(players.size(), placed, unplaced, duplicated, misplaced, firstProblem);
    }

    /** {@return a snapshot of the players the tick membership of {@code level} published} */
    private List<ServerPlayer> playersOf(ServerLevel level) {
        synchronized (this.trackedPlayers) {
            final Set<ServerPlayer> players = this.trackedPlayers.get(level);
            return players == null ? List.of() : new ArrayList<>(players);
        }
    }

    private boolean isTrackedAnywhere(ServerPlayer player) {
        synchronized (this.trackedPlayers) {
            for (final Set<ServerPlayer> players : this.trackedPlayers.values()) {
                if (players.contains(player)) {
                    return true;
                }
            }
        }
        return false;
    }

    long entries() {
        return this.entries.get();
    }

    long departures() {
        return this.departures.get();
    }

    long walkTransitions() {
        return this.walkTransitions.get();
    }

    long teleportTransitions() {
        return this.teleportTransitions.get();
    }

    long portalTransitions() {
        return this.portalTransitions.get();
    }

    long dimensionTransitions() {
        return this.dimensionTransitions.get();
    }

    long transitionsDuringBatch() {
        return this.transitionsDuringBatch.get();
    }

    long refusedTransitions() {
        return this.refusedTransitions.get();
    }

    long deferredArrivals() {
        return this.deferredArrivals.get();
    }

    long identityHandovers() {
        return this.identityHandovers.get();
    }

    long invariantChecks() {
        return this.invariantChecks.get();
    }

    long invariantFailures() {
        return this.invariantFailures.get();
    }

    /** {@return the number of players this tracker holds a publication for} */
    int publishedCount() {
        return this.published.size();
    }
}
