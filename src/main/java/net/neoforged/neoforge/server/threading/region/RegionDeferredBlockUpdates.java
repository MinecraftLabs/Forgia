/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * The block-write effects of one region tick batch that a region worker may not apply itself.
 * <p>
 * A block write through {@code LevelChunk.setBlockState} drives two structures that are one per level, not one
 * per region: the light engine of the level ({@code LevelLightEngine}, one engine with one set of queues and one
 * light thread for the whole level) and the block update publication of the level
 * ({@code ServerLevel.sendBlockUpdated}, which marks the changed sections of the chunk holder, invalidates the
 * level-wide path type cache and recomputes the paths of every navigating mob of the level - a mob that can live
 * in any region). Every slice that writes blocks therefore records what has to happen, in the order its body
 * produced it, and the server thread applies the records at the barrier of the batch that recorded them, before
 * the level's per-chunk broadcast runs.
 * <p>
 * The records are ordered and applied in that order, so a light recheck of one position never runs before the
 * section status change of the same write and a block update never runs before the light recheck of the write
 * that caused it.
 * <p>
 * <strong>Bound.</strong> One batch reserves at most {@code maxEntries} entries for the units it admits, in the
 * reservation unit of its slice ({@link RegionTickSlice#reservationPerUnit()}): one chunk of a chunk tick, one
 * ticker of a block-entity tick, one entity of an entity tick and one tick of a scheduled tick. A unit whose
 * reservation does not fit is refused before its body starts and counted, and it runs on the server thread
 * afterwards, where its writes need no record at all. The reservation is therefore an admission bound, not a
 * hard stop: a unit whose body needs more than its reservation cannot be refused in the middle of the body - the
 * work that already ran would run twice - so its entries are still recorded. The queue can only exceed
 * {@code maxEntries} by the writes of the units that were already admitted, and no recorded effect is ever
 * dropped while the level is open.
 * <p>
 * The entity, block-entity and scheduled tick slice admit their units on the thread that decides the unit, so
 * they reserve through {@link #tryReserve(RegionTickSlice)}. The chunk tick slice admits its chunks in the chunk
 * tick plan on the server thread, which counts them against the same bound; the batch of that slice therefore
 * never has to refuse a unit itself.
 */
@ApiStatus.Internal
final class RegionDeferredBlockUpdates {
    /**
     * Upper bound of the entries one batch reserves for its units. It only has to bound the memory of the records
     * of one level tick; the units are admitted against it before their body starts.
     */
    static final int MAX_ENTRIES = 1 << 16;

    /** The recorded effects, in the order the batch bodies produced them. */
    private final Queue<Update> updates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicInteger reserved = new AtomicInteger();
    /** Entries one batch may reserve, i.e. the admission bound of every slice of that batch. */
    private final int maxEntries;

    RegionDeferredBlockUpdates(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /**
     * One effect a worker deferred. A record carries everything the server thread needs to reproduce the vanilla
     * call, so the barrier never has to read back the chunk a worker already mutated.
     */
    sealed interface Update permits LightSectionStatus, LightCheck, BlockUpdate {}

    /**
     * The emptiness change of the light section of one written position, i.e. the vanilla
     * {@code LevelLightEngine.updateSectionStatus(BlockPos, boolean)} call of {@code LevelChunk.setBlockState}.
     */
    record LightSectionStatus(BlockPos pos, boolean empty) implements Update {}

    /** The vanilla {@code LevelLightEngine.checkBlock(BlockPos)} recheck of one written position. */
    record LightCheck(BlockPos pos) implements Update {}

    /**
     * The vanilla {@code ServerLevel.sendBlockUpdated(BlockPos, BlockState, BlockState, int)} publication of one
     * written position. It is deferred whole: the chunk holder change, the path type cache invalidation and the
     * navigating mob recomputation are one server-thread effect and must stay in their vanilla order.
     */
    record BlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags) implements Update {}

    /** {@return the entries one batch may reserve} */
    int maxEntries() {
        return this.maxEntries;
    }

    /** {@return the entries recorded so far, including the ones already drained} */
    int recordedCount() {
        return this.recorded.get();
    }

    /** {@return the entries the admitted units of this batch reserved} */
    int reservedCount() {
        return this.reserved.get();
    }

    /** {@return true when no effect waits for the server thread} */
    boolean isEmpty() {
        return this.updates.isEmpty();
    }

    /** {@return the number of effects that still wait for the server thread} */
    int pendingCount() {
        return this.updates.size();
    }

    /**
     * Reserves the entries of one admitted unit of {@code slice}.
     * <p>
     * Called on the thread that decides the admission of the unit, so a batch whose units run on different
     * workers can never admit more than its bound.
     *
     * @param slice the slice the unit belongs to, which names its reservation unit
     * @return {@code false} when the unit does not fit, so the caller has to refuse it before its body starts and
     *         run it on the server thread instead
     */
    boolean tryReserve(RegionTickSlice slice) {
        final int entries = slice.reservationPerUnit();
        while (true) {
            final int current = this.reserved.get();
            if (current + entries > this.maxEntries) {
                return false;
            }
            if (this.reserved.compareAndSet(current, current + entries)) {
                return true;
            }
        }
    }

    /**
     * Records the light section status change of one written position.
     *
     * @param pos   the written position, already immutable
     * @param empty whether the section of the position holds only air now
     */
    void deferSectionStatus(BlockPos pos, boolean empty) {
        this.add(new LightSectionStatus(pos, empty));
    }

    /**
     * Records the light recheck of one written position.
     *
     * @param pos the written position, already immutable
     */
    void deferLightCheck(BlockPos pos) {
        this.add(new LightCheck(pos));
    }

    /**
     * Records the block update publication of one written position.
     *
     * @param pos      the written position, already immutable
     * @param oldState the state the position had before the write
     * @param newState the state the position has after the write
     * @param flags    the update flags of the write
     */
    void deferBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        this.add(new BlockUpdate(pos, oldState, newState, flags));
    }

    private void add(Update update) {
        this.updates.add(update);
        this.recorded.incrementAndGet();
    }

    /**
     * Hands every recorded effect to {@code consumer} in recording order and empties the queue.
     *
     * @return the number of effects that were handed over
     */
    int drain(Consumer<Update> consumer) {
        int drained = 0;
        Update update;
        while ((update = this.updates.poll()) != null) {
            consumer.accept(update);
            drained++;
        }
        return drained;
    }

    /** {@return the number of effects that were recorded but never handed to the server thread} */
    int discard() {
        int discarded = 0;
        while (this.updates.poll() != null) {
            discarded++;
        }
        return discarded;
    }
}
