/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * The bounded, ordered cross-region mailbox of one level.
 * <p>
 * A region that has to affect another region, or that has to ask the server thread to change global state,
 * posts a message instead of touching the target directly. The mailbox owns one queue per target region and one
 * global queue:
 * <ul>
 * <li>A message to a region is delivered at the start of that region's next tick, on the worker that holds the
 * region lease, in posting order. The delivery runs before the body of the slice group that leases the region,
 * inside the batch that owns the lease, so its block writes are recorded for the batch barrier like the writes
 * of the slice itself. A message posted while the region is already ticking is delivered by the next group that
 * leases the region, which is the next slice of the same level tick or the same slice of the next tick.</li>
 * <li>A message to the global queue is delivered on the server thread at the beginning of the next global phase
 * ({@link LevelTickPhase#GLOBAL}): the head of the level tick for a post made outside a region phase, and the
 * end of the region phase for a post a worker made. Its body therefore runs with no region batch in flight,
 * before the vanilla level tick continues.</li>
 * </ul>
 * Both queues are bounded. {@link #post} returns the outcome instead of blocking, so a sender can observe a
 * refusal:
 * <ul>
 * <li>{@link PostResult#ACCEPTED}: the message is queued.</li>
 * <li>{@link PostResult#QUEUE_FULL}: the target queue is at its bound; the message is not queued and the
 * refusal is counted. The bound is per queue, so one flooded region cannot refuse the messages of another.</li>
 * <li>{@link PostResult#TARGET_RETIRED}: the target region does not exist, or is retired; the message is not
 * queued and the drop is counted. A message is addressed to one region instance, so it is never delivered to a
 * different region that later reuses the same coordinate - a queued message whose region retired before its
 * delivery is counted and dropped when a region at that coordinate next ticks.</li>
 * <li>{@link PostResult#MAILBOX_CLOSED}: the mailbox was closed by the level unload or the engine shutdown; the
 * message is not queued and the refusal is counted.</li>
 * </ul>
 * Closing the mailbox is deterministic: it drops every queued message of every queue, counting each drop, and
 * refuses every later post. A message is delivered at most once; a body that throws is counted as a delivery
 * failure and logged for the first few failures, and it never aborts the level tick or a region group, because
 * one message cannot be allowed to kill the tick it was delivered in.
 * <p>
 * The mailbox has no producer in the production code of this milestone: it is the primitive the later
 * cross-region work (redstone, structures, weather effects, teleports) posts through, and only tests post to it
 * today. A level without a mailbox costs one map lookup per region phase end and per level tick entry, and no
 * message is ever allocated.
 */
@ApiStatus.Internal
public final class RegionMailbox {
    private static final Logger LOGGER = LogManager.getLogger();
    /** Bound of one region queue, and of the global queue, in messages. */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    /** Upper bound of the delivery failures one mailbox logs; the counter keeps counting the rest. */
    private static final int MAX_LOGGED_DELIVERY_FAILURES = 8;

    /**
     * The outcome of a {@link #post} call.
     */
    public enum PostResult {
        /** The message was queued and is delivered at the target's next phase. */
        ACCEPTED,
        /** The target queue is at its bound; the message was refused and counted. */
        QUEUE_FULL,
        /** The target region does not exist or is retired; the message was dropped and counted. */
        TARGET_RETIRED,
        /** The mailbox was closed; the message was refused and counted. */
        MAILBOX_CLOSED
    }

    private final RegionThreadingEngine engine;
    private final ServerLevel level;
    private final int regionCapacity;
    private final int globalCapacity;
    private final Map<RegionCoordinate, RegionQueue> regionQueues = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Runnable> globalQueue;
    private volatile boolean closed;

    RegionMailbox(RegionThreadingEngine engine, ServerLevel level, int regionCapacity, int globalCapacity) {
        if (regionCapacity <= 0 || globalCapacity <= 0) {
            throw new IllegalArgumentException("Mailbox queue capacities must be positive: " + regionCapacity + ", " + globalCapacity);
        }
        this.engine = engine;
        this.level = level;
        this.regionCapacity = regionCapacity;
        this.globalCapacity = globalCapacity;
        this.globalQueue = new ArrayBlockingQueue<>(globalCapacity);
    }

    /**
     * Posts {@code message} to the mailbox of {@code target}.
     *
     * @param target  the region the message is addressed to
     * @param message the effect, applied on the worker that leases {@code target} at its next tick
     * @return the outcome; a refusal never queues the message
     */
    public PostResult post(RegionCoordinate target, Runnable message) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
        if (target.level() != this.level) {
            throw new IllegalArgumentException("A mailbox belongs to one level: " + this.level + ", not " + target.level());
        }
        if (this.closed) {
            this.engine.recordMailboxRefusedClosed();
            return PostResult.MAILBOX_CLOSED;
        }
        final RegionState region = this.engine.regionStateFor(target);
        if (region == null || region.isDead()) {
            this.engine.recordMailboxDroppedRetired(1L);
            return PostResult.TARGET_RETIRED;
        }
        final RegionMessage entry = new RegionMessage(region, message);
        final RegionQueue queue = this.regionQueues.computeIfAbsent(target, coordinate -> new RegionQueue(this.regionCapacity));
        if (!queue.messages.offer(entry)) {
            this.engine.recordMailboxRefusedFull();
            return PostResult.QUEUE_FULL;
        }
        this.engine.recordMailboxAccepted();
        // The mailbox may have closed while this thread was queueing: the entry is then dropped and counted
        // here, unless the close already drained it - then it was counted as a close drop.
        if (this.closed && queue.messages.remove(entry)) {
            this.engine.recordMailboxDroppedOnClose(1L);
            return PostResult.MAILBOX_CLOSED;
        }
        return PostResult.ACCEPTED;
    }

    /**
     * Posts {@code message} to the global queue.
     *
     * @param message the effect, applied on the server thread at the beginning of the next global phase
     * @return the outcome; a refusal never queues the message
     */
    public PostResult postGlobal(Runnable message) {
        Objects.requireNonNull(message, "message");
        if (this.closed) {
            this.engine.recordMailboxRefusedClosed();
            return PostResult.MAILBOX_CLOSED;
        }
        if (!this.globalQueue.offer(message)) {
            this.engine.recordMailboxRefusedFull();
            return PostResult.QUEUE_FULL;
        }
        this.engine.recordMailboxAccepted();
        if (this.closed && this.globalQueue.remove(message)) {
            this.engine.recordMailboxDroppedOnClose(1L);
            return PostResult.MAILBOX_CLOSED;
        }
        return PostResult.ACCEPTED;
    }

    /**
     * Delivers the messages queued to the region {@code region} holds, in posting order.
     * <p>
     * Called on the worker that leases the region, at the start of the region's group body. Only messages
     * addressed to this exact region instance are delivered; a message of a retired instance is counted and
     * dropped, never delivered to the region that reused its coordinate.
     *
     * @param coordinate the coordinate of the leased region
     * @param region     the leased region instance
     */
    void deliverRegion(RegionCoordinate coordinate, RegionState region) {
        final RegionQueue queue = this.regionQueues.get(coordinate);
        if (queue == null) {
            return;
        }
        // Messages posted while this drain runs are delivered by the next group, so a message that posts
        // another message cannot keep the current group alive.
        final int limit = queue.messages.size();
        for (int i = 0; i < limit; i++) {
            final RegionMessage entry = queue.messages.poll();
            if (entry == null) {
                return;
            }
            if (entry.region() != region) {
                this.engine.recordMailboxDroppedRetired(1L);
                continue;
            }
            this.deliver(entry.message());
        }
    }

    /**
     * Delivers the messages of the global queue on the calling thread, in posting order.
     * <p>
     * Called on the server thread at the beginning of a global phase. Like a region drain, the drain is bounded
     * by the messages present when it started.
     */
    void drainGlobal() {
        final int limit = this.globalQueue.size();
        for (int i = 0; i < limit; i++) {
            final Runnable message = this.globalQueue.poll();
            if (message == null) {
                return;
            }
            this.deliver(message);
        }
    }

    /**
     * Closes the mailbox: every queued message is dropped and counted, and every later post is refused.
     * <p>
     * Deterministic and idempotent, and it never blocks, so closing the mailbox cannot leave a worker waiting.
     */
    void close() {
        this.closed = true;
        long dropped = 0L;
        for (final RegionQueue queue : this.regionQueues.values()) {
            RegionMessage entry;
            while ((entry = queue.messages.poll()) != null) {
                dropped++;
            }
        }
        this.regionQueues.clear();
        while (this.globalQueue.poll() != null) {
            dropped++;
        }
        this.engine.recordMailboxDroppedOnClose(dropped);
    }

    /** {@return true when the mailbox has been closed} */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * {@return the number of messages that are queued and not delivered yet}
     * <p>
     * A queued message is cross-tick work by design: a region message runs at the target region's next tick
     * and a global message at the level's next global phase, so a non-zero count at a save point is the work a
     * crash loses. See {@code RegionThreadingEngine#beginLevelSave}.
     */
    public int pendingMessageCount() {
        int pending = this.globalQueue.size();
        for (final RegionQueue queue : this.regionQueues.values()) {
            pending += queue.messages.size();
        }
        return pending;
    }

    private void deliver(Runnable message) {
        try {
            message.run();
        } catch (Throwable throwable) {
            final long failures = this.engine.recordMailboxDeliveryFailure();
            if (failures <= MAX_LOGGED_DELIVERY_FAILURES) {
                LOGGER.error("Region mailbox message failed", throwable);
            }
            return;
        }
        this.engine.recordMailboxDelivered();
    }

    /**
     * One message addressed to one region instance.
     * <p>
     * The instance, not the coordinate, is the target identity: a region that was retired and recreated under
     * the same coordinate is a different region, and a message of the retired one is dropped instead of being
     * applied there.
     */
    private record RegionMessage(RegionState region, Runnable message) {}

    /** The bounded queue of one region coordinate. */
    private static final class RegionQueue {
        private final ArrayBlockingQueue<RegionMessage> messages;

        private RegionQueue(int capacity) {
            this.messages = new ArrayBlockingQueue<>(capacity);
        }
    }
}
