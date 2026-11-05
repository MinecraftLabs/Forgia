/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Shared lifecycle of one level tick that is executed on region workers.
 * <p>
 * The batch is published on the engine <em>before</em> any region lease is acquired, and it owns the
 * completion latch of the whole lifecycle: setup (lease acquisition and group submission), every worker
 * group, and the server-thread post-processing. A concurrent shutdown or level unload that observes the
 * batch therefore cannot retire a region that the batch still holds or is about to lease.
 * <p>
 * Subclasses add their region groups through {@link #addTask(String, Runnable)}; the work is wrapped by
 * {@link #groupWork(Runnable)} so a group the executor rejected and that therefore runs inline on the
 * publishing thread still marks that thread as executing this batch.
 * <p>
 * The batch also owns the deferred block-write effects of its groups ({@link RegionDeferredBlockUpdates}).
 * {@link #groupWork(Runnable)} installs the queue of the batch for the duration of every group, so a worker of
 * any slice - block entity, entity, chunk or scheduled tick - records the light rechecks and the block update
 * publications of the blocks it writes instead of driving the level-wide light engine and the level-wide block
 * update publication itself, and the completion chain of the batch applies them on the server thread.
 * <p>
 * A worker group that catches a failing tick records it through {@link #recordTickFailure(RegionCoordinate,
 * Entity, Throwable)} and stops; the batch keeps that record bounded and {@link #throwIfFailed()} reports it on
 * the server thread, so the crash report of a failing tick is never built by a worker the server thread is
 * waiting for. See {@link RegionTickFailure}.
 */
@ApiStatus.Internal
abstract class RegionTickBatch {
    /**
     * Batch whose group work executes on the current thread. It is set for the duration of every group,
     * including a group the executor rejected and that therefore runs inline on the publishing thread.
     * The entity query handoff uses it to find the batch whose server-thread wait has to service the
     * handoff.
     */
    private static final ThreadLocal<RegionTickBatch> CURRENT = new ThreadLocal<>();
    /**
     * The deferred block-write effects of the batch whose group executes on the current thread. It is set for
     * the duration of every group, including a group the executor rejected and that therefore runs inline on the
     * publishing thread, and it is {@code null} everywhere else - in particular on the server thread that
     * publishes the batch and on the server thread that applies the records at the barrier, so a block write
     * outside a group publishes inline.
     */
    private static final ThreadLocal<RegionDeferredBlockUpdates> CURRENT_BLOCK_UPDATES = new ThreadLocal<>();

    private final ServerLevel level;
    private final RegionTickSlice slice;
    private final RegionDeferredBlockUpdates blockUpdates;
    /**
     * Bound of the tick failures this batch keeps for its server-thread report.
     * <p>
     * A worker group stops at its first failure, so a batch records at most one failure per group. The bound
     * keeps the record cheap even when a tick fails in many regions at once; the failures past the bound are
     * still counted in the diagnostics and are not reported, because the default crash path reports the first
     * one and aborts the tick.
     */
    private static final int MAX_RECORDED_TICK_FAILURES = 8;
    /**
     * Tick failures the worker groups of this batch caught, in record order. Filled from the workers and drained
     * once by {@link #reportTickFailures()} on the server thread, so it stays bounded by
     * {@link #MAX_RECORDED_TICK_FAILURES} and no failure is ever reported twice.
     */
    private final List<RegionTickFailure> tickFailures = new ArrayList<>();
    private final RegionTaskBatch tasks = new RegionTaskBatch();
    private final RegionTaskBatch.Barrier groupBarrier = RegionTaskBatch.Barrier.open();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ThreadLocal<Boolean> executing = new ThreadLocal<>();
    private final Object mainThreadWorkLock = new Object();
    private final Set<MainThreadWork<?>> pendingMainThreadWork = new LinkedHashSet<>();
    private boolean mainThreadWorkClosed;
    private boolean submitted;
    /**
     * Wall time the publishing thread needed to hand the groups to the executor, so the part of a wait that is
     * submission is observable instead of being counted as worker time.
     */
    private long submitNanos;

    RegionTickBatch(ServerLevel level, RegionTickSlice slice, int blockUpdateEntryBudget) {
        this.level = level;
        this.slice = slice;
        this.blockUpdates = new RegionDeferredBlockUpdates(blockUpdateEntryBudget);
    }

    /**
     * {@return the batch whose group work executes on the current thread, or {@code null} outside a batch}
     */
    static RegionTickBatch current() {
        return CURRENT.get();
    }

    /**
     * {@return the deferred block-write effects of the batch whose group executes on the current thread, or
     * {@code null} outside a group}
     */
    static RegionDeferredBlockUpdates currentBlockUpdates() {
        return CURRENT_BLOCK_UPDATES.get();
    }

    final ServerLevel level() {
        return this.level;
    }

    /** {@return the slice of the level tick this batch executes} */
    final RegionTickSlice slice() {
        return this.slice;
    }

    /** {@return the block-write effects the groups of this batch deferred} */
    final RegionDeferredBlockUpdates blockUpdates() {
        return this.blockUpdates;
    }

    /**
     * {@return the deferred effects of this batch that still wait for its barrier}
     * <p>
     * Every deferred queue of a batch is drained by the barrier of the batch, inside the level tick that
     * published it, so this answers zero outside a batch. A save observes it to prove that a save never
     * reads a half-applied batch; see {@code RegionThreadingEngine#beginLevelSave}. Subclasses add the
     * entity admissions, removals and section updates their groups deferred.
     */
    long pendingDeferredWork() {
        return this.blockUpdates.pendingCount();
    }

    /**
     * Reserves the entries of one unit this batch is about to admit to a worker group.
     *
     * @return {@code false} when the unit does not fit into the bound of this batch, so the caller has to refuse
     *         it before its body starts and run it on the server thread instead
     */
    final boolean tryReserveBlockUpdates() {
        return this.blockUpdates.tryReserve(this.slice);
    }

    /**
     * {@return the seed of the random source of the worker group of {@code coordinate} in this tick}
     * <p>
     * The seed is derived from values the level already owns - its world seed, its dimension, the game time
     * and the region coordinate - and not from a draw of the level random. Building a group therefore
     * neither consumes the vanilla random sequence nor makes the sequence of one region depend on the
     * groups of the other regions of the same tick: the group of a region starts from the same seed no
     * matter how many regions tick next to it, in which order their leases were acquired and which worker
     * runs it. Every input passes through the splitmix64 finalizer, so a step of one tick or of one region
     * changes every bit of the {@code LegacyRandomSource} seed instead of only its low bits.
     * <p>
     * Every slice that installs a group random uses this derivation, so an entity group and a block-entity
     * group of the same region and tick draw the same sequence. They never run at the same time - each slice
     * is its own batch - and neither one is compared with the other, so sharing the derivation keeps one
     * spelling of "the group random of this region in this tick" instead of one per slice.
     * <p>
     * Determinism provided: for one world seed, dimension, game time and region coordinate the group draws
     * a fixed sequence, independent of the other regions of the tick, of the worker count and of the order
     * in which the regions are dispatched to workers. Not provided: this is not the vanilla sequence
     * (vanilla draws every entity of a level from one level-wide source), and the sequence is not stable
     * across a region merge or split, a layout change or a change of the group's members, because each
     * of those changes the group and how many draws the members before a given one take from it.
     */
    final long groupRandomSeed(RegionCoordinate coordinate) {
        return regionRandomSeed(this.level(), coordinate);
    }

    /**
     * {@return the seed of the random source of the region {@code coordinate} of {@code level} in its current
     * tick}
     * <p>
     * The group random of every slice is derived from this value, and the natural spawning slice derives the
     * random source of a spawned chunk from it as well, so "the region random of this tick" has one spelling.
     * See {@link #groupRandomSeed(RegionCoordinate)} for what the derivation provides and what it does not.
     */
    static long regionRandomSeed(ServerLevel level, RegionCoordinate coordinate) {
        long seed = RandomSupport.mixStafford13(level.getSeed());
        seed = RandomSupport.mixStafford13(seed ^ level.getGameTime());
        seed = RandomSupport.mixStafford13(seed ^ level.dimension().location().hashCode());
        return RandomSupport.mixStafford13(seed ^ ChunkPos.asLong(coordinate.regionX(), coordinate.regionZ()));
    }

    /**
     * Adds one worker group. The caller keeps ownership of the group's region lease until its work
     * released it.
     */
    final void addTask(String description, Runnable work) {
        this.tasks.add(description, work);
    }

    /**
     * Adds one worker task that runs several region groups of this slice.
     * <p>
     * Packing only shares the submission and the completion of a task between the groups: each group keeps its
     * own region lease, its own ownership lease, its own group random and its own barrier permit, so a region is
     * still held by exactly one worker, and the barrier still completes only after every group finished. A
     * failing group stops its own body exactly like an unpacked one and does not run or complete the groups
     * packed with it a second time.
     *
     * @param description human readable description used by worker diagnostics
     * @param works       the wrapped work of the groups, each one a group body of this batch
     */
    final void addPackedTask(String description, List<Runnable> works) {
        this.tasks.addPacked(description, works);
    }

    /**
     * Wraps group work so it marks the executing thread as the owner of this batch and installs the deferred
     * block-write queue of the batch, which every slice defers into.
     */
    final Runnable groupWork(Runnable work) {
        return () -> {
            final boolean alreadyExecuting = Boolean.TRUE.equals(this.executing.get());
            final RegionTickBatch previousBatch = CURRENT.get();
            final RegionDeferredBlockUpdates previousUpdates = CURRENT_BLOCK_UPDATES.get();
            this.executing.set(Boolean.TRUE);
            CURRENT.set(this);
            CURRENT_BLOCK_UPDATES.set(this.blockUpdates);
            try {
                work.run();
            } finally {
                // Preserve the lifecycle marker when a rejected group runs inline on the publishing thread.
                if (alreadyExecuting) {
                    this.executing.set(Boolean.TRUE);
                } else {
                    this.executing.remove();
                }
                if (previousBatch != null) {
                    CURRENT.set(previousBatch);
                } else {
                    CURRENT.remove();
                }
                if (previousUpdates != null) {
                    CURRENT_BLOCK_UPDATES.set(previousUpdates);
                } else {
                    CURRENT_BLOCK_UPDATES.remove();
                }
            }
        };
    }

    /**
     * Runs a complete computation on the server thread and returns its result to the calling worker.
     * <p>
     * The computation is encapsulated in the {@link Supplier}: the server thread executes all of it, so a
     * query that iterates entity storage never runs against a concurrently mutated section map. The worker
     * blocks until the server thread ran the computation and then receives its value or its exception.
     * <p>
     * The server thread is inside {@link RegionTickBarrier#await(java.util.function.BooleanSupplier)} while
     * it waits for this batch. The barrier passed to {@link #submitAndAwait} wraps that wait and drains the
     * pending handoffs on the waiting thread, so the computation runs on the server thread while the worker
     * is still inside its group. The {@code MinecraftServer} task queue is deliberately not involved: the
     * chunk source barrier does not pump it, and it runs tasks inline once the server stopped.
     * <p>
     * A worker that is not on the server thread and whose batch is no longer accepting work fails instead of
     * waiting for a handoff that can never run.
     */
    final <T> T runOnServerThread(ServerLevel taskLevel, Supplier<T> computation) {
        final MinecraftServer server = taskLevel.getServer();
        if (server == null || server.isSameThread()) {
            // The publishing server thread (inline or rejected group) and nested server callbacks run the
            // computation directly; handing it to the wait below would deadlock the calling thread.
            return computation.get();
        }
        final MainThreadWork<T> work = new MainThreadWork<>(computation);
        synchronized (this.mainThreadWorkLock) {
            if (this.mainThreadWorkClosed) {
                throw new IllegalStateException("Region tick batch no longer accepts main-thread work");
            }
            this.pendingMainThreadWork.add(work);
        }
        return work.await();
    }

    /**
     * Runs the handoffs region workers published while this batch is awaited.
     * <p>
     * Called from the batch-aware barrier condition on the thread that waits for the batch, which is the
     * publishing server thread. Each handoff is removed from the pending set before it runs, so it executes
     * exactly once and the close path can never run it a second time.
     */
    private void runPendingMainThreadWork() {
        while (true) {
            final MainThreadWork<?> work;
            synchronized (this.mainThreadWorkLock) {
                final Iterator<MainThreadWork<?>> pending = this.pendingMainThreadWork.iterator();
                if (!pending.hasNext()) {
                    return;
                }
                work = pending.next();
                pending.remove();
            }
            work.run();
        }
    }

    /**
     * Releases every handoff that is still waiting for the server thread, so a failed or finished batch can
     * never strand a worker. The failure is reported to the worker exactly like a computation failure.
     */
    private void closeMainThreadWork(Throwable failure) {
        final List<MainThreadWork<?>> pending;
        synchronized (this.mainThreadWorkLock) {
            this.mainThreadWorkClosed = true;
            pending = new ArrayList<>(this.pendingMainThreadWork);
            this.pendingMainThreadWork.clear();
        }
        for (final MainThreadWork<?> work : pending) {
            work.fail(failure);
        }
    }

    /** {@return the wall time the publishing thread spent handing the groups to the executor} */
    final long submitNanos() {
        return this.submitNanos;
    }

    /**
     * {@return true when every worker group finished}
     */
    final boolean isGroupsComplete() {
        return this.groupBarrier.isComplete();
    }

    /**
     * {@return true when the batch is fully finished, including the server-thread post-processing}
     */
    final boolean isComplete() {
        return this.groupBarrier.isComplete() && this.finished.getCount() == 0L;
    }

    /**
     * {@return true when the current thread is executing this batch}
     */
    final boolean isExecutingOnCurrentThread() {
        return Boolean.TRUE.equals(this.executing.get());
    }

    /**
     * Marks the current thread as executing this batch for the rest of its lifecycle.
     * <p>
     * The publishing thread owns the batch from publication to {@link #finish()}, including the
     * server-thread post-processing of the deferred work. A lifecycle transition (shutdown, level unload)
     * requested from that thread would wait for a batch only it can complete, so it is rejected instead.
     */
    final void markExecutingOnCurrentThread() {
        this.executing.set(Boolean.TRUE);
    }

    /**
     * Clears the execution marker of {@link #markExecutingOnCurrentThread()}.
     */
    final void clearExecutingOnCurrentThread() {
        this.executing.remove();
    }

    /**
     * Submits every group and waits for the workers. Safe to call once; a second call only waits.
     * <p>
     * The caller opens the region phase of the level around this call (see
     * {@code RegionThreadingEngine#runRegionBatch}), so the interval in which the groups may run is exactly one
     * region phase of the level tick.
     */
    final void submitAndAwait(RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        synchronized (this) {
            if (this.submitted) {
                this.groupBarrier.awaitCompletion();
                return;
            }
            this.submitted = true;
        }
        final long submitStart = System.nanoTime();
        this.tasks.submitOpen(executor, this.groupBarrier);
        this.groupBarrier.setupComplete();
        this.submitNanos = System.nanoTime() - submitStart;
        Throwable barrierFailure = null;
        try {
            // The batch-aware barrier services the main-thread handoffs of the workers in addition to the
            // chunk source tasks the level barrier already pumps. The MinecraftServer task queue is not
            // part of that wait, so a handoff enqueued there would only run after the workers finished.
            barrier.await(() -> {
                if (this.groupBarrier.isComplete()) {
                    return true;
                }
                this.runPendingMainThreadWork();
                return this.groupBarrier.isComplete();
            });
        } catch (Throwable throwable) {
            // The workers still hold region leases and mutate region state. Wait for them before the
            // failure is reported so the caller's cleanup never races a running group. A worker that is
            // blocked on a main-thread handoff is released first, because the server thread left the
            // managed wait and will not service the handoff anymore.
            barrierFailure = throwable;
            this.closeMainThreadWork(throwable);
        } finally {
            this.groupBarrier.awaitCompletion();
        }
        if (barrierFailure != null) {
            if (barrierFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (barrierFailure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Region tick barrier failed", barrierFailure);
        }
    }

    /**
     * Waits until the whole batch, including {@link #finish()}, completed.
     */
    final void awaitCompletion() {
        this.groupBarrier.awaitCompletion();
        awaitLatch(this.finished);
    }

    /**
     * Releases the final permit of the batch. Called on the server thread after all post-processing.
     */
    final void finish() {
        // No group is running anymore; release any handoff that was enqueued but not serviced so the
        // batch can never keep a worker waiting after its lifecycle ended.
        this.closeMainThreadWork(new IllegalStateException("Region tick batch finished before its main-thread work ran"));
        this.finished.countDown();
    }

    /**
     * Records one tick failure a worker group of this batch caught, for the server thread to report.
     * <p>
     * Called on the worker thread that caught the failure, so it only stores the throwable and the context the
     * caller already holds: one record, no crash report and no log line. See {@link RegionTickFailure} for why
     * the report may not be built here.
     *
     * @param coordinate the region whose group failed
     * @param entity     the entity whose tick failed, or {@code null} for a unit vanilla reports without one
     * @param throwable  the failure
     */
    final void recordTickFailure(RegionCoordinate coordinate, @Nullable Entity entity, Throwable throwable) {
        synchronized (this.tickFailures) {
            if (this.tickFailures.size() >= MAX_RECORDED_TICK_FAILURES) {
                return;
            }
            this.tickFailures.add(new RegionTickFailure(this.slice, coordinate, this.level.getGameTime(), entity,
                    Thread.currentThread().getName(), throwable));
        }
    }

    /**
     * Reports the tick failures the workers of this batch recorded, on the calling server thread.
     * <p>
     * Called after the barrier and after the server-thread handoff of the batch, so the report of a failing
     * worker tick is built by the thread that owns the level. The record is drained before the first failure is
     * reported: a second call reports nothing, and a failure that throws - the default crash path - leaves no
     * copy behind that a later caller could report again. A failure recorded after the level closed is reported
     * like any other, because closing the level does not change what vanilla would have reported.
     */
    final void reportTickFailures() {
        final List<RegionTickFailure> failures;
        synchronized (this.tickFailures) {
            if (this.tickFailures.isEmpty()) {
                return;
            }
            failures = List.copyOf(this.tickFailures);
            this.tickFailures.clear();
        }
        for (final RegionTickFailure failure : failures) {
            failure.report();
        }
    }

    /**
     * Reports the tick failures the workers of this batch recorded and rethrows the first failure of any group.
     * <p>
     * Called on the server thread at the end of the level tick that published this batch, after the handoff.
     */
    final void throwIfFailed() {
        this.reportTickFailures();
        this.groupBarrier.throwIfFailed();
    }

    private static void awaitLatch(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One computation handed from a worker to the server thread.
     */
    private static final class MainThreadWork<T> {
        private final Supplier<T> computation;
        private final CountDownLatch done = new CountDownLatch(1);
        private T result;
        private Throwable failure;

        private MainThreadWork(Supplier<T> computation) {
            this.computation = computation;
        }

        private void run() {
            try {
                this.result = this.computation.get();
            } catch (Throwable throwable) {
                this.failure = throwable;
            } finally {
                this.done.countDown();
            }
        }

        private void fail(Throwable throwable) {
            this.failure = throwable;
            this.done.countDown();
        }

        private T await() {
            awaitLatch(this.done);
            final Throwable throwable = this.failure;
            if (throwable != null) {
                if (throwable instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (throwable instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Region tick main-thread work failed", throwable);
            }
            return this.result;
        }
    }
}
