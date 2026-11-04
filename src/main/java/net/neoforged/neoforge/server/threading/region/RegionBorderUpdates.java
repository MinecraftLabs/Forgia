/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.jetbrains.annotations.ApiStatus;

/**
 * The neighbor updates a region worker could not apply because they target another region.
 * <p>
 * A region worker owns exactly one region lease. An update that reaches a position outside that lease - the
 * neighbour update of a block write, the comparator update of {@code updateNeighbourForOutputSignal}, or the
 * unsaved-chunk mark of a block-entity change - is recorded here instead of being applied by the worker. The
 * server thread drains the records after the worker phase and applies them on the target region's own worker
 * in the same tick, so the target region sees the write of the previous pass and the circuit converges before
 * the tick ends.
 * <p>
 * <strong>Ordering.</strong> Records keep the order the workers produced them. The fixpoint groups them by
 * target region and applies each region's records in that order, so a cascade inside one region keeps its
 * vanilla order. Regions are processed one after another, so an update of the second region sees the writes of
 * the first region of the same pass.
 * <p>
 * Every invocation is retained, including identical payloads: payload equality does not identify a call.
 * <p>
 * <strong>Bound.</strong> One pass records at most {@link #MAX_ENTRIES} updates. The bound is an
 * admission bound of the capture queue: a record past it is not queued and is counted, so the worker that
 * produced it has to hand it to the next-tick fallback instead. In production the fixpoint bound
 * ({@code maxChainedNeighborUpdates}) is reached long before this memory bound.
 */
@ApiStatus.Internal
final class RegionBorderUpdates {
    /**
     * Upper bound of the records one capture pass keeps. The queue only has to bound the memory of one level
     * tick's border work; the units are admitted against it before the fixpoint runs.
     */
    static final int MAX_ENTRIES = 1 << 16;

    /** The recorded updates, in the order the worker bodies produced them. */
    private final ConcurrentLinkedQueue<BorderUpdate> updates = new ConcurrentLinkedQueue<>();
    /** Records past {@link #MAX_ENTRIES}, kept for the next-tick fallback instead of being applied inline. */
    private final ConcurrentLinkedQueue<BorderUpdate> overflowed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedCount = new AtomicInteger();
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger overflow = new AtomicInteger();

    /** The outcome of one capture. */
    enum RecordResult {
        /** The update is queued for the fixpoint. */
        ACCEPTED,
        /** The capture bound is exhausted; the update is kept for the next-tick fallback. */
        OVERFLOW
    }

    /** {@return true when no border update waits for the fixpoint} */
    boolean isEmpty() {
        return this.updates.isEmpty();
    }

    /** {@return the number of records accepted so far} */
    int recordedCount() {
        return this.recorded.get();
    }

    /** {@return the number of records refused because the capture bound was reached} */
    int overflowCount() {
        return this.overflow.get();
    }

    /**
     * Records one border update.
     * <p>
     * Every call is consumed: the caller skips the inline body because the update is queued for this
     * fixpoint or, after overflow, for the next-tick fallback.
     */
    RecordResult record(BorderUpdate update) {
        if (this.queuedCount.get() >= MAX_ENTRIES) {
            this.overflow.incrementAndGet();
            this.overflowed.add(update);
            return RecordResult.OVERFLOW;
        }
        this.updates.add(update);
        this.queuedCount.incrementAndGet();
        this.recorded.incrementAndGet();
        return RecordResult.ACCEPTED;
    }

    /** {@return the updates the capture bound refused, in recording order} */
    List<BorderUpdate> drainOverflow() {
        final List<BorderUpdate> drained = new ArrayList<>(this.overflowed.size());
        BorderUpdate update;
        while ((update = this.overflowed.poll()) != null) {
            drained.add(update);
        }
        return drained;
    }

    /**
     * Hands every recorded update to the caller in recording order and empties the queue.
     * <p>
     * Runs on the server thread after the worker phase, when no worker can record anymore, so the
     * queue cannot be mutated while the list is built.
     */
    List<BorderUpdate> drain() {
        final List<BorderUpdate> drained = new ArrayList<>(this.updates.size());
        BorderUpdate update;
        while ((update = this.updates.poll()) != null) {
            this.queuedCount.decrementAndGet();
            drained.add(update);
        }
        return drained;
    }

    /** Drops every queued update, counting nothing: the caller owns the count of a closed level. */
    void discard() {
        this.updates.clear();
        this.queuedCount.set(0);
        this.overflowed.clear();
    }

    /**
     * One vanilla update a worker had to leave to another region.
     * <p>
     * The record carries everything the target worker needs to reproduce the vanilla call. The target state is
     * read on the target worker, so the replay never uses a state a worker of another region read concurrently.
     */
    sealed interface BorderUpdate permits NeighborUpdate, ShapeUpdate, ComparatorUpdate, BlockEntityChange, SelfUpdate {
        /** {@return the position the update targets, i.e. the position of the region that has to run it} */
        BlockPos pos();

        /** Runs the vanilla update on the thread that holds the lease of {@link #pos()}'s region. */
        void apply(ServerLevel level);
    }

    /**
     * The vanilla {@code NeighborUpdater.executeUpdate} of one target position, i.e. the neighbour update of a
     * block write or of a comparator weak change.
     */
    record NeighborUpdate(BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) implements BorderUpdate {
        @Override
        public void apply(ServerLevel level) {
            NeighborUpdater.executeUpdate(level, level.getBlockState(this.pos), this.pos, this.block, this.neighborPos, this.movedByPiston);
        }
    }

    /** The vanilla {@code NeighborUpdater.executeShapeUpdate} of one target position. */
    record ShapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int updateFlags,
            int updateLimit) implements BorderUpdate {
        @Override
        public void apply(ServerLevel level) {
            NeighborUpdater.executeShapeUpdate(level, this.direction, this.state, this.pos, this.neighborPos, this.updateFlags, this.updateLimit);
        }
    }

    /**
     * One direction of the vanilla {@code Level.updateNeighbourForOutputSignal} loop.
     * <p>
     * The record names the neighbour position, the changed position it was derived from, the block that changed
     * and the direction of the loop. The replay runs the exact body the vanilla loop runs for that direction:
     * the {@code onNeighborChange} callback, and the weak change of the block behind a redstone conductor.
     */
    record ComparatorUpdate(BlockPos pos, BlockPos sourcePos, Block sourceBlock, Direction direction) implements BorderUpdate {
        @Override
        public void apply(ServerLevel level) {
            BlockState state = level.getBlockState(this.pos);
            state.onNeighborChange(level, this.pos, this.sourcePos);
            if (state.isRedstoneConductor(level, this.pos)) {
                final BlockPos weakPos = this.pos.relative(this.direction);
                final BlockState weakState = level.getBlockState(weakPos);
                if (weakState.getWeakChanges(level, weakPos)) {
                    level.neighborChanged(weakState, weakPos, this.sourceBlock, this.pos, false);
                }
            }
        }
    }

    /** The vanilla {@code Level.blockEntityChanged} of one block entity in another region. */
    record BlockEntityChange(BlockPos pos) implements BorderUpdate {
        @Override
        public void apply(ServerLevel level) {
            level.blockEntityChanged(this.pos);
        }
    }

    /**
     * The re-evaluation of one position a region wrote, used by the re-run of a region whose foreign read was
     * invalidated.
     * <p>
     * The block at the position decided its own state from a chunk of another region. The re-run replays the
     * vanilla neighbour update of the position against itself, which is the weakest source a block re-evaluates
     * from, and the target state is read on the worker that holds the lease, so the re-run never uses the state
     * the invalidated pass read. A block that ignores its source - redstone dust, a piston, a repeater - decides
     * again from the state the foreign chunk has now.
     */
    record SelfUpdate(BlockPos pos) implements BorderUpdate {
        @Override
        public void apply(ServerLevel level) {
            final BlockState state = level.getBlockState(this.pos);
            NeighborUpdater.executeUpdate(level, state, this.pos, state.getBlock(), this.pos, false);
        }
    }
}
