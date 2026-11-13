/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Refusal of a chunk request that a native region worker issued and the server chunk source cannot serve
 * immediately.
 * <p>
 * {@code ServerChunkCache.getChunk} dispatches every off-thread request to the main-thread processor and
 * waits for it. A region worker that does the same waits for the server thread, which is inside the batch
 * barrier waiting for that worker: the request either stalls the tick for as long as the chunk takes to
 * load or deadlocks the tick outright. A worker therefore only receives a chunk that is already available,
 * and asks for the server-thread fallback instead of waiting for one that is not.
 * <p>
 * A missing-chunk refusal is a handoff, not a failure. The entity tick group catches it per entity, counts it in
 * {@link RegionThreadingDiagnostics#chunkFallbackEntityTicks()} and ticks that entity on the server thread
 * after the batch, exactly once. The block-entity tick group hands the refused ticker to its existing
 * server-thread fallback the same way. {@link net.minecraft.world.level.Level#guardEntityTick} rethrows the
 * exception unwrapped, so the handoff never builds a crash report and never discards the entity.
 * <p>
 * An unstable-read refusal is different: {@link #mustNotReplay(Throwable)} identifies a body that may
 * already have applied effects. Its handlers abandon the unit for this tick without replay or removal.
 * Regular entity, block-entity and chunk ticks can run again in the next tick; a consumed scheduled call
 * is not rescheduled as a whole. The unstable-read counter records every such loss.
 * <p>
 * The exception is only ever thrown from a native region worker (see
 * {@link net.neoforged.neoforge.server.threading.RegionThreadingHooks#isNativeRegionWorkerThread()}). The
 * server thread, the client, the chunk generation threads and every other thread keep the vanilla blocking
 * request unchanged.
 */
@ApiStatus.Internal
public final class RegionWorkerChunkUnavailableException extends IllegalStateException {
    private static final AtomicLong REFUSALS = new AtomicLong();
    private static final AtomicLong UNSTABLE_READ_REFUSALS = new AtomicLong();
    private boolean noReplay;
    private static final AtomicLong FOREIGN_WRITE_REFUSALS = new AtomicLong();

    public static void countForeignWriteRefusal() {
        FOREIGN_WRITE_REFUSALS.incrementAndGet();
    }

    public static long foreignWriteRefusalCount() {
        return FOREIGN_WRITE_REFUSALS.get();
    }

    /**
     * Bound of the cause walk in {@link #isChunkUnavailable(Throwable)}, so a cyclic cause chain cannot hang
     * the worker that is already recovering from a refusal.
     */
    private static final int MAX_CAUSE_DEPTH = 32;

    private RegionWorkerChunkUnavailableException(String message) {
        super(message);
    }

    /** Unlike missing-chunk handoffs, this mid-body refusal must never replay prior callback effects. */
    public static RegionWorkerChunkUnavailableException forUnstableRead(int chunkX, int chunkZ) {
        UNSTABLE_READ_REFUSALS.incrementAndGet();
        final RegionWorkerChunkUnavailableException refusal = new RegionWorkerChunkUnavailableException(
                "Foreign chunk remained unstable during bounded read: " + chunkX + ", " + chunkZ);
        refusal.noReplay = true;
        return refusal;
    }

    public static RegionWorkerChunkUnavailableException forVersionlessRead(int chunkX, int chunkZ) {
        final RegionWorkerChunkUnavailableException refusal = new RegionWorkerChunkUnavailableException(
                "Native worker cannot verify a versionless chunk read: " + chunkX + ", " + chunkZ);
        refusal.noReplay = true;
        return refusal;
    }

    public static long unstableReadRefusalCount() {
        return UNSTABLE_READ_REFUSALS.get();
    }

    public static boolean mustNotReplay(@Nullable Throwable throwable) {
        for (int depth = 0; throwable != null && depth < MAX_CAUSE_DEPTH; depth++, throwable = throwable.getCause()) {
            if (throwable instanceof RegionWorkerChunkUnavailableException refusal) {
                return refusal.noReplay;
            }
        }
        return false;
    }

    /**
     * Records one refused chunk request and returns the exception to throw.
     *
     * @param chunkX the requested chunk x, which the caller uses to widen the pre-load margin
     * @param chunkZ the requested chunk z
     * @param status the requested chunk status
     */
    public static RegionWorkerChunkUnavailableException forChunk(int chunkX, int chunkZ, ChunkStatus status) {
        REFUSALS.incrementAndGet();
        return new RegionWorkerChunkUnavailableException("Native region worker requested the chunk "
                + chunkX + ", " + chunkZ + " at status " + status + " and it is not loaded");
    }

    /**
     * Records one refused chunk future request and returns the exception to throw.
     * <p>
     * A chunk future is completed by the server thread, which is waiting for the batch barrier while the
     * worker runs, so a worker that obtained one could only ever wait for a completion that cannot happen.
     */
    public static RegionWorkerChunkUnavailableException forChunkFuture(int chunkX, int chunkZ, ChunkStatus status) {
        REFUSALS.incrementAndGet();
        return new RegionWorkerChunkUnavailableException("Native region worker requested the chunk future "
                + chunkX + ", " + chunkZ + " at status " + status + ", which only the server thread can complete");
    }

    /**
     * {@return true when {@code throwable} is this refusal, or was wrapped in one}
     * <p>
     * The refusal travels through vanilla code that wraps a failure into a crash report
     * ({@code ReportedException}) or into a {@code CompletionException}, so the cause chain is searched
     * before the caller decides that the entity tick failed for another reason.
     */
    public static boolean isChunkUnavailable(@Nullable Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RegionWorkerChunkUnavailableException) {
                return true;
            }
            final Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    /**
     * {@return the number of chunk requests a native region worker refused instead of blocking}
     */
    public static long refusalCount() {
        return REFUSALS.get();
    }

    static void reset() {
        REFUSALS.set(0L);
    }
}
