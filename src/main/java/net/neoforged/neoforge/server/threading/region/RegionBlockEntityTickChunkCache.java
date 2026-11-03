/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Admission snapshot of the block-entity tickers of one loaded chunk.
 * <p>
 * The snapshot holds every ticker the chunk currently owns in the order the level's own ticker list would
 * tick it, together with the three decisions the plan used to re-evaluate for every ticker of every level
 * tick: the allowlist of the compatibility declaration, the interior-chunk rule of the ticker's chunk and the
 * exact binding capture. Building it costs one admission per ticker; reusing it costs one chunk lookup per
 * chunk per tick, and the workers iterate the admitted tickers themselves, so the server thread never walks
 * the tickers.
 * <p>
 * The snapshot is stored on the {@code LevelChunk} itself, so exactly one snapshot exists per loaded chunk
 * and it is released with the chunk: {@code LevelChunk.clearAllBlockEntities()} is what
 * {@code ServerLevel.unload(LevelChunk)} runs, and it drops the snapshot. Chunk churn therefore cannot grow
 * the snapshot set, and {@link #liveSnapshotCount()} makes that bound observable.
 * <p>
 * The snapshot is reused only while {@link #isCurrent(RegionLayout, long)} holds. It is invalidated at
 * every point that can change its content:
 * <ul>
 * <li>a ticker was added, removed or rebound: {@code LevelChunk.updateBlockEntityTicker} and
 * {@code LevelChunk.removeBlockEntityTicker} bump the chunk's ticker revision, and
 * {@code LevelChunk.clearAllBlockEntities} bumps it and releases the snapshot;</li>
 * <li>the chunk unloaded, was replaced or lost its block entities: the same release in
 * {@code LevelChunk.clearAllBlockEntities};</li>
 * <li>the region layout or its fixed size changed: the layout instance is part of the validity check.</li>
 * </ul>
 * A region merge, a split or an empty retirement does not invalidate the snapshot: the snapshot holds no
 * region state, and the batch resolves the region of every chunk from the regionizer.
 * Everything else is not cached here: the entity-load eligibility of the chunk stays a per-tick, per-chunk
 * read on the server thread, and the binding capture of a ticker is re-checked against the live ticker
 * before its admission is used.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickChunkCache {
    /**
     * Orders the admissions of a chunk like the level's own ticker list: the wrapper creation sequence of a
     * chunk-owned ticker never changes, and a rebind keeps the position the ticker already had.
     */
    private static final Comparator<RegionBlockEntityTickAdmission> BY_TICKER_SEQUENCE = Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence);
    /**
     * Snapshots that a chunk currently holds. It only proves the memory bound of the snapshot set: a chunk
     * holds at most one snapshot, and a released snapshot is not counted.
     */
    private static final AtomicInteger LIVE_SNAPSHOTS = new AtomicInteger();

    private final RegionLayout layout;
    private final long tickerRevision;
    private final boolean interiorChunk;
    private final List<RegionBlockEntityTickAdmission> admissions;
    private final boolean allAdmitted;
    private boolean released;

    private RegionBlockEntityTickChunkCache(RegionLayout layout, long tickerRevision, boolean interiorChunk,
            List<RegionBlockEntityTickAdmission> admissions) {
        this.layout = layout;
        this.tickerRevision = tickerRevision;
        this.interiorChunk = interiorChunk;
        this.admissions = admissions;
        boolean admitted = true;
        for (final RegionBlockEntityTickAdmission admission : admissions) {
            // A capture that is already stale, or a declaration that does not admit the type or the chunk,
            // keeps the whole region of the chunk on the server thread, exactly like a collected ticker the
            // declaration does not admit.
            admitted &= admission.isAdmitted() && admission.isCurrent();
        }
        this.allAdmitted = admitted;
        LIVE_SNAPSHOTS.incrementAndGet();
    }

    /**
     * Builds the snapshot of {@code chunk} from the tickers it currently holds.
     * <p>
     * Runs on the server thread while no worker ticks, so the chunk's ticker set cannot change underneath it.
     * The chunk's own ticker revision is read first, so a ticker added while the snapshot is built makes the
     * snapshot stale instead of silently missing from it.
     *
     * @param level         the level the tickers belong to
     * @param chunk         the chunk whose tickers the snapshot holds
     * @param layout        the region layout the snapshot is built for
     * @param interiorChunk whether the chunk is an interior chunk of its region
     */
    static RegionBlockEntityTickChunkCache build(ServerLevel level, LevelChunk chunk, RegionLayout layout, boolean interiorChunk) {
        final long tickerRevision = chunk.blockEntityTickerRevision();
        final List<TickingBlockEntity> tickers = new ArrayList<>(chunk.blockEntityTickers());
        tickers.sort(Comparator.comparingLong(LevelChunk::blockEntityTickerSequence));
        final List<RegionBlockEntityTickAdmission> admissions = new ArrayList<>(tickers.size());
        for (final TickingBlockEntity ticker : tickers) {
            final String type = ticker.getType();
            admissions.add(new RegionBlockEntityTickAdmission(
                    level,
                    ticker,
                    ticker.getPos(),
                    type,
                    LevelChunk.captureBlockEntityTickBinding(ticker, level),
                    RegionTickCompatibility.decideBlockEntityTick(type, interiorChunk),
                    LevelChunk.blockEntityTickerSequence(ticker)));
        }
        return new RegionBlockEntityTickChunkCache(layout, tickerRevision, interiorChunk, List.copyOf(admissions));
    }

    /**
     * {@return true when this snapshot still describes the chunk}
     * <p>
     * The layout is compared by identity: a level's layout instance is immutable and only replaced when the
     * configured region policy or fixed edge length changes, which relocates every chunk and invalidates the
     * cached interior-chunk flag and region with it.
     *
     * @param layout         the layout of the engine that reads the snapshot
     * @param tickerRevision the ticker revision of the chunk
     */
    public boolean isCurrent(RegionLayout layout, long tickerRevision) {
        return this.layout == layout && this.tickerRevision == tickerRevision;
    }

    /** {@return the admissions of this chunk, ordered like the level's own ticker list} */
    public List<RegionBlockEntityTickAdmission> admissions() {
        return this.admissions;
    }

    /**
     * {@return the admission of {@code ticker} in this snapshot, or {@code null} when the chunk does not hold
     * it}
     * <p>
     * Only the explicit per-ticker admission looks a ticker up; the chunk-indexed plan reads the whole list.
     */
    @Nullable
    RegionBlockEntityTickAdmission admission(TickingBlockEntity ticker) {
        for (final RegionBlockEntityTickAdmission admission : this.admissions) {
            if (admission.ticker() == ticker) {
                return admission;
            }
        }
        return null;
    }

    /**
     * {@return true when every ticker of the chunk is admitted to a worker tick and its capture is current}
     * <p>
     * The batch keeps a whole region on the server thread when one of its chunks is not fully admitted, so the
     * conservative whole-region fallback needs one boolean per chunk instead of a pass over the tickers.
     */
    public boolean allAdmitted() {
        return this.allAdmitted;
    }

    /** {@return true when the chunk is an interior chunk of its region, so its types may write around it} */
    boolean interiorChunk() {
        return this.interiorChunk;
    }

    /**
     * Releases the snapshot when the chunk unloads or replaces its ticker set.
     * <p>
     * A released snapshot must not be used again: the chunk drops its reference in the same step, so the
     * release is the last transition of the object. The admissions stay readable, because a worker group that
     * already holds the snapshot may still iterate it until the batch finishes.
     */
    public void release() {
        if (!this.released) {
            this.released = true;
            LIVE_SNAPSHOTS.decrementAndGet();
        }
    }

    /** {@return the number of snapshots the loaded chunks currently hold} */
    public static int liveSnapshotCount() {
        return LIVE_SNAPSHOTS.get();
    }
}
