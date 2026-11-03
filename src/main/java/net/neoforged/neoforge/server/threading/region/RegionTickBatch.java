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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.jetbrains.annotations.ApiStatus;

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

    private final ServerLevel level;
    private final RegionTaskBatch tasks = new RegionTaskBatch();
    private final RegionTaskBatch.Barrier groupBarrier = RegionTaskBatch.Barrier.open();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final ThreadLocal<Boolean> executing = new ThreadLocal<>();
    private final Object mainThreadWorkLock = new Object();
    private final Set<MainThreadWork<?>> pendingMainThreadWork = new LinkedHashSet<>();
    private boolean mainThreadWorkClosed;
    private boolean submitted;

    RegionTickBatch(ServerLevel level) {
        this.level = level;
    }

    /**
     * {@return the batch whose group work executes on the current thread, or {@code null} outside a batch}
     */
    static RegionTickBatch current() {
        return CURRENT.get();
    }

    final ServerLevel level() {
        return this.level;
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
        final ServerLevel level = this.level();
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
     * Wraps group work so it marks the executing thread as the owner of this batch.
     */
    final Runnable groupWork(Runnable work) {
        return () -> {
            final boolean alreadyExecuting = Boolean.TRUE.equals(this.executing.get());
            final RegionTickBatch previousBatch = CURRENT.get();
            this.executing.set(Boolean.TRUE);
            CURRENT.set(this);
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
     */
    final void submitAndAwait(RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        synchronized (this) {
            if (this.submitted) {
                this.groupBarrier.awaitCompletion();
                return;
            }
            this.submitted = true;
        }
        this.tasks.submitOpen(executor, this.groupBarrier);
        this.groupBarrier.setupComplete();
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
     * Rethrows the first failure of any group.
     */
    final void throwIfFailed() {
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
