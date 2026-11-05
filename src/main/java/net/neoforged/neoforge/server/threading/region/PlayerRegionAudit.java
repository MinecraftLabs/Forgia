/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * The result of one audit of the player region invariant of a level.
 * <p>
 * Every player of the level must be mapped into exactly one region of that level. {@code unplaced} and
 * {@code duplicated} are the two violations of that invariant: a player no region of the level maps, and a
 * player whose region tick index entries exist in more than one region. {@code misplaced} is not a violation:
 * the mapping of a player that moved inside its own tick names the region of the position the tick started from
 * until the next publication point corrects it, so it is reported next to the violations instead of being
 * asserted.
 *
 * @param players      the players the level reported
 * @param placed       the players mapped into exactly one region of the level, at their current position
 * @param unplaced     the players no region of the level maps
 * @param duplicated   the players indexed by more than one region
 * @param misplaced    the players mapped into one region of the level, but not the one of their position
 * @param firstProblem a one line description of the first violation or misplacement, or {@code "-"}
 */
@ApiStatus.Internal
public record PlayerRegionAudit(int players, int placed, int unplaced, int duplicated, int misplaced, String firstProblem) {
    /** {@return true when every player is in exactly one region of the level} */
    public boolean ok() {
        return this.unplaced == 0 && this.duplicated == 0;
    }
}
