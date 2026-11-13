/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * The section columns of a game event dispatch that a region worker left to the region that owns them.
 * <p>
 * {@code GameEventDispatcher.post} visits every chunk of the event's notification radius - up to 16 blocks for
 * the common events and 32 for {@code shriek} - and every section of that chunk, and the listener it finds
 * there writes into its own block entity: {@code VibrationSystem.Listener.handleGameEvent} adds a candidate to
 * the listener's {@code VibrationSelector} and the sculk catalyst blooms, both inside the listener's own state.
 * With a 3-chunk region the radius therefore crosses a region border in the common case, and a worker that
 * visited the foreign section would write another region's block entity while its owner ticks it.
 * <p>
 * A worker records such a section column here instead of visiting it, and the server thread replays it on the
 * target region's own worker in the same tick, before the next slice of the level tick runs. The record is the
 * column - the chunk and the section-Y range of the dispatch - and not the listener, because reading a foreign
 * section's listener list is the same race as writing its block entity: the registry is a plain
 * {@code ArrayList} that only its owning region mutates. The replay therefore asks the target registry itself,
 * with the same distance filter the vanilla dispatch uses, and visits exactly the listeners the vanilla
 * dispatch would have visited.
 * <p>
 * <strong>Order.</strong> Records keep the order the workers produced them, and one record covers the vanilla
 * iteration order of one column (section-Y ascending). The delivery groups the records by target region and
 * replays each region's records in that order, so a listener sees the events of one tick in posting order. The
 * selector of a listener only compares candidates recorded in the same game time ({@code VibrationSelector}
 * refuses a candidate of another tick), so the recorded order decides the winner of the tie-break and the game
 * time decides the activation tick, never the wall time of the delivery.
 * <p>
 * <strong>Bound.</strong> One pass records at most {@link #MAX_ENTRIES} columns. The bound is an admission
 * bound of the capture queue: a column past it is not queued and is counted, and the event of that column is
 * dropped rather than delivered a tick late, because a candidate recorded in the next game time would change
 * the activation tick of the listener instead of only its order.
 */
@ApiStatus.Internal
final class RegionBorderGameEvents {
    /**
     * Upper bound of the columns one level tick's dispatch keeps. The queue only has to bound the memory of one
     * level tick's cross-region dispatch; the columns are admitted against it before the delivery runs.
     */
    static final int MAX_ENTRIES = 1 << 16;

    /** The recorded columns, in the order the worker bodies produced them. */
    private final ConcurrentLinkedQueue<SectionColumn> columns = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();
    /** Entries the queue admits; {@link #MAX_ENTRIES} in production and the mechanics seam in a test. */
    private final int maxEntries;

    RegionBorderGameEvents() {
        this(MAX_ENTRIES);
    }

    RegionBorderGameEvents(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** {@return true when no captured column waits for the delivery} */
    boolean isEmpty() {
        return this.columns.isEmpty();
    }

    /** {@return the number of columns accepted so far} */
    int recordedCount() {
        return this.recorded.get();
    }

    /** {@return the number of columns refused because the capture bound was reached} */
    int refusedCount() {
        return this.refused.get();
    }

    /**
     * Records one section column.
     *
     * @return {@code true} when the column is queued, {@code false} when the capture bound refused it
     */
    boolean record(SectionColumn column) {
        if (this.queuedCount.get() >= this.maxEntries) {
            this.refused.incrementAndGet();
            return false;
        }
        this.columns.add(column);
        this.queuedCount.incrementAndGet();
        this.recorded.incrementAndGet();
        return true;
    }

    /**
     * Hands every recorded column to the caller in recording order and empties the queue.
     * <p>
     * Runs on the server thread after the worker phase, when no worker can record anymore, so the queue cannot
     * be mutated while the list is built.
     */
    List<SectionColumn> drain() {
        final List<SectionColumn> drained = new ArrayList<>(this.columns.size());
        SectionColumn column;
        while ((column = this.columns.poll()) != null) {
            this.queuedCount.decrementAndGet();
            drained.add(column);
        }
        return drained;
    }

    /** Drops every queued column, counting nothing: the caller owns the count of a closed level. */
    void discard() {
        this.columns.clear();
        this.queuedCount.set(0);
    }

    /**
     * One section column of one game event dispatch.
     * <p>
     * The record carries the event, the position it was posted from and its context, so the replay posts the
     * same event to the same listeners with the same source. The source position is what the vanilla dispatch
     * measured the listener distance from, so the recorded distance of a replayed candidate is the one the
     * inline run would have recorded, and with it the travel time and the redstone strength.
     *
     * @param event       the posted game event
     * @param source      the position the event was posted from
     * @param context     the context of the post
     * @param chunkX      the chunk column's X coordinate
     * @param chunkZ      the chunk column's Z coordinate
     * @param minSectionY the lowest section of the dispatch's Y range
     * @param maxSectionY the highest section of the dispatch's Y range
     */
    record SectionColumn(Holder<GameEvent> event, Vec3 source, GameEvent.Context context, int chunkX, int chunkZ,
            int minSectionY, int maxSectionY) {}
}
