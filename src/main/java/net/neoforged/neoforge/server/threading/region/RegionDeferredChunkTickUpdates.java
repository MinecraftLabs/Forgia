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
 * The block-write effects of one chunk tick batch that a region worker may not apply itself.
 * <p>
 * A chunk tick writes blocks through {@code LevelChunk.setBlockState}, which drives two level-wide structures
 * that are not partitioned per region: the light engine of the level ({@code LevelLightEngine}, one engine with
 * one set of queues and one light thread for the whole level) and the block update publication of the level
 * ({@code ServerLevel.sendBlockUpdated}, which invalidates the level-wide path type cache and recomputes the
 * paths of every navigating mob of the level). A worker therefore only records what has to happen, in the order
 * the chunk body produced it, and the server thread applies the records at the batch barrier, before the
 * level's per-chunk broadcast runs.
 * <p>
 * The records are ordered and applied in that order, so a light recheck of one position never runs before the
 * section status change of the same write and a block update never runs before the light recheck of the write
 * that caused it.
 * <p>
 * <strong>Bound.</strong> One batch holds at most {@link #MAX_CHUNKS_PER_BATCH} chunks, and the chunk tick plan
 * admits a chunk only while its reservation {@link #RESERVATION_PER_CHUNK} still fits into
 * {@link #MAX_ENTRIES}. A chunk whose body needs more than its reservation cannot be refused in the middle of
 * the body - the random ticks that already ran would run twice - so the entries are still recorded and
 * {@link RegionThreadingEngine} counts the batch as over budget. The reservation is therefore the admission
 * bound, not a hard stop: the queue can only exceed {@link #MAX_ENTRIES} by the writes of the chunks that were
 * already admitted, and no recorded effect is ever dropped while the level is open.
 */
@ApiStatus.Internal
final class RegionDeferredChunkTickUpdates {
    /**
     * Upper bound of the entries one batch reserves for its chunks. It only has to bound the memory of the
     * records of one level tick; the chunks are admitted against it before their body starts.
     */
    static final int MAX_ENTRIES = 1 << 16;
    /**
     * Entries one admitted chunk reserves. A chunk tick of the default random tick speed writes a handful of
     * blocks per section it ticks, so the reservation covers a chunk body with a wide margin while keeping the
     * number of chunks a batch may admit well above the chunk counts a level ticks per tick.
     */
    static final int RESERVATION_PER_CHUNK = 32;
    /** Chunks one batch may admit, derived from the entry bound and the per-chunk reservation. */
    static final int MAX_CHUNKS_PER_BATCH = MAX_ENTRIES / RESERVATION_PER_CHUNK;

    /** The recorded effects, in the order the chunk bodies produced them. */
    private final Queue<Update> updates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger recorded = new AtomicInteger();

    /**
     * One effect a worker deferred. A record carries everything the server thread needs to reproduce the
     * vanilla call, so the barrier never has to read back the chunk a worker already mutated.
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

    /** {@return the entries recorded so far, including the ones already drained} */
    int recordedCount() {
        return this.recorded.get();
    }

    /** {@return true when no effect waits for the server thread} */
    boolean isEmpty() {
        return this.updates.isEmpty();
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
