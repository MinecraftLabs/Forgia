/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable runtime state for one region coordinate.
 */
final class RegionState {
    private final RegionCoordinate coordinate;
    private final Map<RegionSectionCoordinate, RegionSectionState> sections = new ConcurrentHashMap<>();
    private final RegionTaskQueue taskQueue = new RegionTaskQueue();
    private final RegionTickTimer tickTimer = new RegionTickTimer(System.nanoTime());
    private final RegionTickMetrics tickMetrics = new RegionTickMetrics();
    private final AtomicReference<RegionLifecycleState> lifecycleState = new AtomicReference<>(RegionLifecycleState.READY);
    private final AtomicBoolean pendingRecalculation = new AtomicBoolean();
    private final AtomicBoolean workerSubmitted = new AtomicBoolean();
    private volatile Thread ownerThread;

    RegionState(RegionCoordinate coordinate) {
        this.coordinate = coordinate;
    }

    RegionCoordinate coordinate() {
        return this.coordinate;
    }

    void addSection(RegionSectionState section) {
        this.sections.put(section.coordinate(), section);
    }

    Collection<RegionSectionState> sections() {
        return this.sections.values();
    }

    void removeSection(RegionSectionCoordinate section) {
        this.sections.remove(section);
    }

    void clearSections() {
        this.sections.clear();
    }

    boolean hasSections() {
        return !this.sections.isEmpty();
    }

    int chunkCount() {
        int count = 0;
        for (final RegionSectionState section : this.sections.values()) {
            count += section.chunkCount();
        }
        return count;
    }

    RegionTaskQueue taskQueue() {
        return this.taskQueue;
    }

    /**
     * Inserts work atomically with empty-region retirement. A task can therefore never be left in the
     * queue of a region that has already been removed from the regionizer.
     */
    synchronized boolean enqueueTask(Runnable task) {
        if (this.isDead()) {
            return false;
        }
        this.taskQueue.enqueue(task);
        return true;
    }

    synchronized RegionScheduledTask scheduleTask(Runnable task, long delayTicks) {
        if (this.isDead()) {
            return RegionScheduledTask.cancelled(task);
        }
        return this.taskQueue.schedule(task, delayTicks);
    }

    synchronized RegionScheduledTask scheduleTaskAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        if (this.isDead()) {
            return RegionScheduledTask.cancelled(task);
        }
        return this.taskQueue.scheduleAtFixedRate(task, initialDelayTicks, periodTicks);
    }

    boolean hasQueuedTasks() {
        return !this.taskQueue.isEmpty();
    }

    synchronized void clearTasks() {
        this.taskQueue.clearAndCancel();
        this.notifyAll();
    }

    RegionTickTimer tickTimer() {
        return this.tickTimer;
    }

    void makeDueForTesting() {
        this.tickTimer.advanceBy(1);
    }

    RegionTickMetrics tickMetrics() {
        return this.tickMetrics;
    }

    long cappedDrainCount() {
        return this.taskQueue.cappedDrainCount();
    }

    void beginOwnership() {
        this.ownerThread = Thread.currentThread();
    }

    void endOwnership() {
        this.ownerThread = null;
    }

    boolean isOwnedByCurrentThread() {
        return this.ownerThread == Thread.currentThread();
    }

    boolean tryMarkRunning() {
        return this.lifecycleState.compareAndSet(RegionLifecycleState.READY, RegionLifecycleState.TICKING);
    }

    synchronized void clearRunning() {
        this.lifecycleState.compareAndSet(RegionLifecycleState.TICKING, RegionLifecycleState.READY);
        this.notifyAll();
    }

    boolean isRunning() {
        return this.lifecycleState.get() == RegionLifecycleState.TICKING;
    }

    boolean tryMarkWorkerSubmitted() {
        return this.workerSubmitted.compareAndSet(false, true);
    }

    synchronized void clearWorkerSubmitted() {
        this.workerSubmitted.set(false);
        this.notifyAll();
    }

    boolean isWorkerSubmitted() {
        return this.workerSubmitted.get();
    }

    void markPendingRecalculation() {
        this.pendingRecalculation.set(true);
    }

    boolean consumePendingRecalculation() {
        return this.pendingRecalculation.compareAndSet(true, false);
    }

    boolean hasPendingRecalculation() {
        return this.pendingRecalculation.get();
    }

    boolean isDead() {
        return this.lifecycleState.get() == RegionLifecycleState.DEAD;
    }

    boolean tryMarkDead() {
        return this.lifecycleState.compareAndSet(RegionLifecycleState.READY, RegionLifecycleState.DEAD);
    }

    void markDead() {
        this.lifecycleState.set(RegionLifecycleState.DEAD);
        this.tickMetrics.release();
    }

    boolean isEmpty() {
        return !this.isDead() && !this.isRunning() && !this.isWorkerSubmitted() && !this.hasSections() && this.taskQueue.isEmpty();
    }

    /**
     * Retires an empty region and closes its queue while excluding concurrent producers.
     */
    synchronized void awaitQuiescent() {
        boolean interrupted = false;
        while (this.isWorkerSubmitted() || this.isRunning()) {
            try {
                this.wait();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    synchronized boolean retireIfEmpty() {
        if (!this.isEmpty() || !this.tryMarkDead()) {
            return false;
        }
        this.clearTasks();
        // A retired region is dropped from the regionizer, so its metric window is released here instead of
        // waiting for the last reference to the dead region state to disappear.
        this.tickMetrics.release();
        return true;
    }
}
