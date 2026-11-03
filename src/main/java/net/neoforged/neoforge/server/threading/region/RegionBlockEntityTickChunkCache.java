/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Admission snapshot of the block-entity tickers of one loaded chunk.
 * <p>
 * Building the block-entity plan re-evaluated three decisions for every ticker of every level tick: the
 * allowlist of the compatibility declaration, the interior-chunk rule of the ticker's chunk and the region
 * the ticker's position belongs to. All three only change when the chunk's ticker set or the region layout
 * changes, so this snapshot holds them for the whole time they are valid: one {@link
 * RegionBlockEntityTickAdmission} per ticker (the exact binding capture, its position, its type and its
 * decision) and the interior-chunk flag of the chunk. The plan then costs one lookup per ticker plus one
 * chunk lookup per chunk instead of an admission, a binding capture and an interior-chunk computation per
 * ticker. The region of a ticker is not cached: the lease resolves it per ticker like it always did, so a
 * merge, a split or an empty retirement needs no invalidation here.
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
 * region state, and the batch resolves the region of every ticker from the regionizer.
 * Everything else is not cached here: the entity-load eligibility of the chunk stays a per-tick, per-chunk
 * read on the server thread, and the binding capture of a ticker is re-checked against the live ticker
 * before its admission is reused.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickChunkCache {
    /**
     * Snapshots that a chunk currently holds. It only proves the memory bound of the snapshot set: a chunk
     * holds at most one snapshot, and a released snapshot is not counted.
     */
    private static final AtomicInteger LIVE_SNAPSHOTS = new AtomicInteger();

    private final RegionLayout layout;
    private final long tickerRevision;
    private final boolean interiorChunk;
    private final Map<TickingBlockEntity, RegionBlockEntityTickAdmission> admissions = new IdentityHashMap<>();

    RegionBlockEntityTickChunkCache(RegionLayout layout, long tickerRevision, boolean interiorChunk) {
        this.layout = layout;
        this.tickerRevision = tickerRevision;
        this.interiorChunk = interiorChunk;
        LIVE_SNAPSHOTS.incrementAndGet();
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

    /**
     * {@return the cached admission of {@code ticker}, or {@code null} when the snapshot has none yet}
     */
    @Nullable
    RegionBlockEntityTickAdmission admission(TickingBlockEntity ticker) {
        return this.admissions.get(ticker);
    }

    /** Replaces the cached admission of {@code ticker}. */
    void putAdmission(TickingBlockEntity ticker, RegionBlockEntityTickAdmission admission) {
        this.admissions.put(ticker, admission);
    }

    /** {@return true when the chunk is an interior chunk of its region, so its types may write around it} */
    boolean interiorChunk() {
        return this.interiorChunk;
    }

    /**
     * Releases the snapshot when the chunk unloads or replaces its ticker set.
     * <p>
     * A released snapshot must not be used again: the chunk drops its reference in the same step, so the
     * release is the last transition of the object.
     */
    public void release() {
        this.admissions.clear();
        LIVE_SNAPSHOTS.decrementAndGet();
    }

    /** {@return the number of snapshots the loaded chunks currently hold} */
    public static int liveSnapshotCount() {
        return LIVE_SNAPSHOTS.get();
    }
}
