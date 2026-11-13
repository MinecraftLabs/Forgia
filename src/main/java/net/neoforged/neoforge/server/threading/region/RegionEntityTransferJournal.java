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
 * decisions exist, so a full journal never silently drops an accepted decision: every refusal is counted and
 * reported to the caller as a {@code null} token plus a rate-limited warning. Terminal results are kept
 * in a bounded insertion-order map so a replay of a recent token answers {@link RegionEntityTransferResult#ALREADY_COMMITTED}
 * (or the recorded abort reason); the terminal map stores the result enum only, never the token or the
 * entity, so clearing the journal leaves no strong reference to any entity behind.
 * <p>
 * A level clear cancels the pending decisions of the closed level and an engine shutdown clears both maps.
 * A recorded token never spans levels, so a level clear can always release every decision that touches the
 * closing level. A commit can therefore never write a region mapping after the level or engine cleanup that
 * owns the index started, and a completion that arrives after that cleanup does not re-create its terminal
 * entry: the caller's replay reports the lifecycle outcome instead of a result of a discarded transaction.
 */
final class RegionEntityTransferJournal {
    /** Maximum number of recorded decisions that wait for a commit. */
    static final int MAX_PENDING_TOKENS = 1024;
    /** Maximum number of terminal results kept for idempotent replays. */
    static final int MAX_REMEMBERED_RESULTS = 4096;

    private final Map<Long, Entry> pending = new LinkedHashMap<>();
    private final Map<Long, RegionEntityTransferResult> remembered = new LinkedHashMap<>();
    private long rejectedRecordCount;

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
            ++this.rejectedRecordCount;
            return false;
        }
        this.pending.put(token.tokenId(), new Entry(token));
        return true;
    }

    /** {@return the number of recordings the pending bound rejected} */
    synchronized long rejectedRecordCount() {
        return this.rejectedRecordCount;
    }

    /**
     * Claims the token for its one allowed commit.
     * <p>
     * A token whose commit already completed answers {@link RegionEntityTransferResult#ALREADY_COMMITTED};
     * a token another commit is still applying answers {@link RegionEntityTransferResult#IN_PROGRESS} and
     * keeps its pending entry; an aborted token answers its recorded abort reason; a token the journal never
     * recorded (or whose entry was already cleared) answers {@link RegionEntityTransferResult#UNKNOWN_TOKEN}.
     */
    synchronized Claim claim(RegionEntityTransferToken token) {
        final Entry entry = this.pending.get(token.tokenId());
        if (entry != null) {
            if (entry.token != token) {
                return new Claim(RegionEntityTransferResult.UNKNOWN_TOKEN);
            }
            if (entry.claimed) {
                return new Claim(RegionEntityTransferResult.IN_PROGRESS);
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

    /**
     * Removes the claimed entry and remembers the commit for idempotent replays.
     * <p>
     * A level clear or a shutdown can retire the entry while the commit is applying its recorded decision.
     * That completion must not add a terminal entry back into the cleared journal, so a missing pending
     * entry leaves the journal untouched and the caller's replay reports the lifecycle outcome.
     */
    synchronized void completed(RegionEntityTransferToken token) {
        if (this.pending.remove(token.tokenId()) == null) {
            return;
        }
        this.remember(token.tokenId(), RegionEntityTransferResult.COMMITTED);
    }

    /**
     * Removes the claimed entry and remembers the abort for idempotent replays.
     * <p>
     * Like {@link #completed(RegionEntityTransferToken)} this leaves a journal alone whose entry a lifecycle
     * step already retired.
     */
    synchronized void aborted(RegionEntityTransferToken token, RegionEntityTransferResult result) {
        if (this.pending.remove(token.tokenId()) == null) {
            return;
        }
        this.remember(token.tokenId(), result);
    }

    /**
     * Releases an uncommitted entry on behalf of its caller and remembers the cancellation.
     * <p>
     * A terminal token answers its recorded result, so aborting twice, or aborting after a commit failed,
     * changes nothing. A claimed entry belongs to the commit that is applying it; removing it would let that
     * commit finish unseen, so the journal leaves it alone and answers
     * {@link RegionEntityTransferResult#IN_PROGRESS} instead.
     *
     * @return {@link RegionEntityTransferResult#CANCELLED} when this call released the entry, the recorded
     *         terminal result when the token was already terminal, or
     *         {@link RegionEntityTransferResult#UNKNOWN_TOKEN} when the journal never recorded the token
     */
    synchronized RegionEntityTransferResult abort(RegionEntityTransferToken token) {
        final Entry entry = this.pending.get(token.tokenId());
        if (entry != null) {
            if (entry.token != token) {
                return RegionEntityTransferResult.UNKNOWN_TOKEN;
            }
            if (entry.claimed) {
                return RegionEntityTransferResult.IN_PROGRESS;
            }
            this.pending.remove(token.tokenId());
            this.remember(token.tokenId(), RegionEntityTransferResult.CANCELLED);
            return RegionEntityTransferResult.CANCELLED;
        }
        final RegionEntityTransferResult rememberedResult = this.remembered.get(token.tokenId());
        if (rememberedResult == RegionEntityTransferResult.COMMITTED) {
            return RegionEntityTransferResult.ALREADY_COMMITTED;
        }
        return rememberedResult != null ? rememberedResult : RegionEntityTransferResult.UNKNOWN_TOKEN;
    }

    /** Cancels the pending decisions of {@code level}, without retaining their entities. */
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
