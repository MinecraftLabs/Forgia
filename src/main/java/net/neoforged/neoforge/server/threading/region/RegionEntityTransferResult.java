/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Terminal outcome of one recorded entity transfer commit or abort.
 * <p>
 * Every commit and every abort returns exactly one value. Only {@link #COMMITTED} reports that the recorded
 * region mapping was applied. Every other value except {@link #IN_PROGRESS} reports that nothing was
 * mutated; the validation names the first check that failed, and a failed token is never retried or
 * repaired.
 * <p>
 * The transaction does not cover vanilla entity, world or network side effects. A {@link #COMMITTED}
 * result therefore means "the recorded region mapping moved", not "the entity was transferred atomically
 * in every subsystem".
 */
@ApiStatus.Internal
public enum RegionEntityTransferResult {
    /** The recorded region mapping was applied to every member of the riding graph. */
    COMMITTED,
    /** The same token was already committed; no second mutation happened. */
    ALREADY_COMMITTED,
    /** The token was aborted by its caller; the pending decision was released and no mutation happened. */
    CANCELLED,
    /**
     * The same token is currently being applied by another commit on the server thread. The pending entry
     * is left to that commit, so this is the one non-terminal answer.
     */
    IN_PROGRESS,
    /** Region threading is not installed, so the caller keeps the vanilla path. */
    DISABLED,
    /** The journal never recorded the token, or the recorded entry was already cleared. */
    UNKNOWN_TOKEN,
    /** The recorded entity or one of its riding graph members is removed. */
    ENTITY_REMOVED,
    /** The token's level identity was closed by an unload before the commit. */
    LEVEL_CLOSED,
    /** The engine was shut down before the commit. */
    ENGINE_SHUT_DOWN,
    /** The identity ledger has no current record for the UUID, or its epoch advanced after recording. */
    STALE_IDENTITY_EPOCH,
    /**
     * The recorded destination region does not exist, was retired, or does not belong to the token's level.
     */
    DESTINATION_INACTIVE,
    /**
     * The recorded source mapping changed after recording, so the transfer is no longer the same one.
     * <p>
     * A merge of the source region into a neighbour and a second token for an entity that the first commit
     * already moved are both reported here.
     */
    SOURCE_MAPPING_CHANGED,
    /** The riding graph gained or lost a member, or a member belongs to another level. */
    RIDING_GRAPH_SPLIT,
    /**
     * The recorded source or the recorded destination region is ticked or submitted to a region worker, so
     * the mapping must not move under that lease. The token stays terminally busy like any other failure.
     */
    REGION_BUSY,
    /** The commit failed before it wrote the region mapping; the token stays terminally failed. */
    COMMIT_FAILED
}
