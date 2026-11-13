/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * One immutable publication of the lifecycle state of an entity identity in one level.
 * <p>
 * The record is written only by the server thread, and only after the server-owned vanilla maps that back
 * the state have been written. A reader that is not the server thread reads a complete record or no record
 * at all; it never observes a record that describes a half-applied transition. The record is not a snapshot
 * of the vanilla maps: {@link EntityIdentityLedger#current(UUID)} and {@link EntityIdentityLedger#current(int)}
 * are independent reads and the identity of an entity is not an atomic view of every vanilla map.
 *
 * @param uuid     the entity UUID this publication belongs to
 * @param entityId the int entity id the identity was published with
 * @param state    the lifecycle state at publication time
 * @param epoch    the ledger-wide monotonic publication sequence; strictly increasing per UUID
 */
@ApiStatus.Internal
public record EntityIdentity(UUID uuid, int entityId, State state, long epoch) {

    public EntityIdentity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(state, "state");
    }

    /** {@return true while the identity may still be present in the level} */
    public boolean isLive() {
        return this.state.isLive();
    }

    /** {@return true while the identity is registered in the level's visible by-uuid/by-id lookup} */
    public boolean isVisible() {
        return this.state == State.TRACKED;
    }
    /**
     * The published lifecycle states of one identity.
     * <p>
     * The state machine is deliberately narrow. This lane publishes admission, visibility and departure;
     * it publishes no ticking state because entity ticks stay owned by the server thread and a worker
     * ticking contract belongs to the region entity tick lane.
     */
    @ApiStatus.Internal
    public enum State {
        /** Admission started: the identity owns its UUID and int id, the vanilla maps are not written yet. */
        RESERVED,
        /** The identity is in the level (UUID set and section membership) but not in the visible lookup. */
        ADMITTED,
        /** The identity is registered in the visible by-uuid/by-id lookup of the level. */
        TRACKED,
        /** Removal started: the identity is no longer a valid admission target. */
        DEPARTING,
        /** Removal finished; the identity is only remembered as a bounded departure record. */
        GONE;

        /** {@return true while this state belongs to an identity that may still be present in the level} */
        public boolean isLive() {
            return this != GONE;
        }

        /**
         * {@return true when a publication from this state to {@code target} respects the published order}
         * <p>
         * Visibility is not a one-way ladder: a loaded entity loses and regains tracking when its chunk
         * becomes hidden or accessible. Those two edges are the only backward publication, and departure
         * is terminal.
         */
        boolean canPublish(State target) {
            return switch (this) {
                case RESERVED -> target == ADMITTED || target == DEPARTING || target == GONE;
                case ADMITTED -> target == TRACKED || target == DEPARTING || target == GONE;
                case TRACKED -> target == ADMITTED || target == DEPARTING || target == GONE;
                case DEPARTING -> target == GONE;
                case GONE -> false;
            };
        }
    }
}
