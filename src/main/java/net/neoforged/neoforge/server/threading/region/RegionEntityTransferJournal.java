/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Pending and terminal transfer decisions of one engine.
 * <p>
 * Entries are keyed by the recorded token id, never by the {@link net.minecraft.world.entity.Entity}
 * object alone. The journal, not the live entity state, decides whether a commit may run: a pending entry
 * can be claimed exactly once, and a terminal result makes a replay idempotent without a second write.
 * <p>
 * The journal is bounded in both directions. Recording is refused once {@link #MAX_PENDING_TOKENS} pending
 * decisions exist, so a full journal never silently drops an accepted decision. Terminal results are kept
 * in a bounded insertion-order map so a replay of a recent token answers {@link RegionEntityTransferResult#ALREADY_COMMITTED}
 * (or the recorded abort reason); the terminal map stores the result enum only, never the token or the
 * entity, so clearing the journal leaves no strong reference to any entity behind.
 * <p>
 * A level clear cancels the pending decisions that touch the closed level and an engine shutdown clears
 * both maps. A commit can therefore never write a region mapping after the level or engine cleanup that
 * owns the index started.
 */
final class RegionEntityTransferJournal {
    /** Maximum number of recorded decisions that wait for a commit. */
    static final int MAX_PENDING_TOKENS = 1024;
    /** Maximum number of terminal results kept for idempotent replays. */
    static final int MAX_REMEMBERED_RESULTS = 4096;

    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private final Map<Long, RegionEntityTransferResult> remembered = new LinkedHashMap<>();

    private static final class Entry {
        private final RegionEntityTransferToken token;
        private boolean claimed;

        private Entry(RegionEntityTransferToken token) {
            this.token = token;
        }
    }

    /**
     * {@return the claim result of one commit attempt}
     *
     * @param terminal the result to return without a write, or {@code null} when the caller now owns the
     *                 commit
     */
    record Claim(@Nullable RegionEntityTransferResult terminal) {
        boolean claimed() {
            return this.terminal == null;
        }
    }

    /**
     * Records a new pending decision.
     *
     * @return {@code false} when the pending bound is reached; the caller must not advertise a token that
     *         the journal does not know
     */
    synchronized boolean record(RegionEntityTransferToken token) {
        if (this.pending.containsKey(token.tokenId())) {
            return true;
        }
        if (this.pending.size() >= MAX_PENDING_TOKENS) {
            return false;
        }
        this.pending.put(token.tokenId(), new Entry(token));
        return true;
    }

    /**
     * Claims the token for its one allowed commit.
     * <p>
     * A previously committed token answers {@link RegionEntityTransferResult#ALREADY_COMMITTED}; a
     * previously aborted token answers its recorded abort reason; a token the journal never recorded (or
     * whose entry was already cleared) answers {@link RegionEntityTransferResult#UNKNOWN_TOKEN}.
     */
    synchronized Claim claim(RegionEntityTransferToken token) {
        final Entry entry = this.pending.get(token.tokenId());
        if (entry != null) {
            if (entry.token != token) {
                return new Claim(RegionEntityTransferResult.UNKNOWN_TOKEN);
            }
            if (entry.claimed) {
                return new Claim(RegionEntityTransferResult.ALREADY_COMMITTED);
            }
            entry.claimed = true;
            return new Claim(null);
        }
        final RegionEntityTransferResult rememberedResult = this.remembered.get(token.tokenId());
        if (rememberedResult == RegionEntityTransferResult.COMMITTED) {
            return new Claim(RegionEntityTransferResult.ALREADY_COMMITTED);
        }
        if (rememberedResult != null) {
            return new Claim(rememberedResult);
        }
        return new Claim(RegionEntityTransferResult.UNKNOWN_TOKEN);
    }

    /** Removes the claimed entry and remembers the commit for idempotent replays. */
    synchronized void completed(RegionEntityTransferToken token) {
        this.pending.remove(token.tokenId());
        this.remember(token.tokenId(), RegionEntityTransferResult.COMMITTED);
    }

    /** Removes the claimed entry and remembers the abort for idempotent replays. */
    synchronized void aborted(RegionEntityTransferToken token, RegionEntityTransferResult result) {
        this.pending.remove(token.tokenId());
        this.remember(token.tokenId(), result);
    }

    /** Cancels the pending decisions that belong to {@code level}, without retaining their entities. */
    synchronized void clearPendingFor(ServerLevel level) {
        this.pending.values().removeIf(entry -> entry.token.level() == level);
    }

    /** Clears every pending and remembered decision. */
    synchronized void clear() {
        this.pending.clear();
        this.remembered.clear();
    }

    synchronized int pendingCount() {
        return this.pending.size();
    }

    synchronized int rememberedCount() {
        return this.remembered.size();
    }

    private void remember(long tokenId, RegionEntityTransferResult result) {
        this.remembered.put(tokenId, result);
        while (this.remembered.size() > MAX_REMEMBERED_RESULTS) {
            final Long eldest = this.remembered.keySet().iterator().next();
            this.remembered.remove(eldest);
        }
    }
}
