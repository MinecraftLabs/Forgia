/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable runtime state for one region coordinate.
 * <p>
 * The state carries the region's entity tick index: the entities whose authoritative region mapping names this
 * region, in the order they entered the level's entity tick list. The index is the region's share of the
 * entity tick, so a worker that leases the region can enumerate the entities it may tick without reading the
 * level's server-owned tick list, and the server thread can decide per region whether the tick has any work.
 */
final class RegionState {
    private final RegionCoordinate coordinate;
    private final Map<RegionSectionCoordinate, RegionSectionState> sections = new ConcurrentHashMap<>();
    /**
     * The chunk-area cells this region owns, i.e. the region coordinates whose fixed chunk rectangle belongs
     * to it: one cell for an unmerged region and every member cell of a merged dynamic region.
     * <p>
     * The cell set is the region's extent, and it is maintained by the regionizer when it resolves or merges
     * regions, not by the level's chunk loads, so it is also complete for a region that was created after the
     * level's chunks were already loaded. The self-driven entity tick activation builds its chunk rectangle
     * from it on the server thread; a region without cells has no rectangle and keeps its entities on the
     * server thread.
     */
    private final java.util.Set<RegionSectionCoordinate> cells = ConcurrentHashMap.newKeySet();
    private final RegionTaskQueue taskQueue = new RegionTaskQueue();
    private final RegionTickTimer tickTimer = new RegionTickTimer(System.nanoTime());
    private final RegionTickMetrics tickMetrics = new RegionTickMetrics();
    private final AtomicReference<RegionLifecycleState> lifecycleState = new AtomicReference<>(RegionLifecycleState.READY);
    private final AtomicBoolean pendingRecalculation = new AtomicBoolean();
    private final AtomicBoolean workerSubmitted = new AtomicBoolean();
    /**
     * The region's entity tick index, ordered by {@link RegionEntityTickEntry#sequence()}.
     * <p>
     * Every mutation happens under the regionizer write lock, so the index and the regionizer's entity mapping
     * change together. The structures themselves are concurrent, so a worker that owns the region lease can
     * enumerate a snapshot of the index while the server thread keeps serving chunk source tasks: a worker may
     * observe an entry of a tick that is being published, never a torn entry.
     */
    private final ConcurrentSkipListSet<RegionEntityTickEntry> entityTickOrder = new ConcurrentSkipListSet<>(RegionEntityTickEntry.BY_SEQUENCE);
    private final ConcurrentHashMap<Entity, RegionEntityTickEntry> entityTickEntries = new ConcurrentHashMap<>();
    /** Number of indexed entities the level's entity tick list currently contains. */
    private final AtomicInteger tickingEntityCount = new AtomicInteger();
    /** Number of ticking entities only the server thread may tick, i.e. always-ticking entities such as players. */
    private final AtomicInteger serverThreadOnlyEntityCount = new AtomicInteger();
    private volatile Thread ownerThread;
    /**
     * Upper bound of the foreign chunk keys this region records between two version checks.
     * <p>
     * A region reads a handful of chunks of its neighbours at most, so the bound only has to keep a region that
     * scans far outside its own area from growing the set without limit. A key past the bound is not recorded,
     * so a change of that chunk is not detected by this region's check.
     */
    static final int MAX_FOREIGN_READ_CHUNKS = 256;
    /**
     * Upper bound of the written positions this region keeps for a re-run of its propagation.
     * <p>
     * The seeds of one pass are the blocks the pass wrote; a pass writes at most the deferred block-update
     * budget of its batch. A position past the bound is dropped and counted, so a re-run of a region that wrote
     * an extreme number of blocks only re-evaluates the positions that fit.
     */
    static final int MAX_RERUN_SEEDS = 1024;
    /**
     * The version of every foreign chunk this region read since its last check, by
     * {@link net.minecraft.world.level.ChunkPos#asLong(int, int)} key.
     * <p>
     * Written by the region's own worker only: a region holds one lease at a time, so the recording worker is
     * the only writer. The border fixpoint reads and clears the map on the server thread after the barrier of
     * the batch, when no worker of the region runs, so no lock is needed on either side.
     */
    private final it.unimi.dsi.fastutil.longs.Long2LongMap foreignChunkReads = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /**
     * The positions this region wrote since its last check, as packed block positions. A changed foreign chunk
     * makes the border fixpoint re-evaluate these positions on the region's own worker, which is the propagation
     * a re-run of the region repeats. Recorded and read under the same rule as {@link #foreignChunkReads}.
     */
    private final it.unimi.dsi.fastutil.longs.LongArrayList rerunSeeds = new it.unimi.dsi.fastutil.longs.LongArrayList();
    /** Seeds the {@link #MAX_RERUN_SEEDS} bound refused since the last check. */
    private int droppedRerunSeeds;

    RegionState(RegionCoordinate coordinate) {
        this.coordinate = coordinate;
    }

    RegionCoordinate coordinate() {
        return this.coordinate;
    }

    void addSection(RegionSectionState section) {
        this.sections.put(section.coordinate(), section);
    }

    /**
     * Records one chunk-area cell of this region.
     */
    void addCell(RegionSectionCoordinate cell) {
        this.cells.add(cell);
    }

    /**
     * {@return the chunk-area cells of this region}
     */
    Collection<RegionSectionCoordinate> cells() {
        return this.cells;
    }

    /**
     * Takes over every cell of {@code source}, used when two regions merge.
     */
    void absorbCellsFrom(RegionState source) {
        this.cells.addAll(source.cells());
        source.cells().clear();
    }

    /**
     * Drops every cell of this region, used when its level is cleared.
     */
    void clearCells() {
        this.cells.clear();
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

    /**
     * {@return the loaded chunks of this region as {@link net.minecraft.world.level.ChunkPos#asLong(int, int)}
     * keys}
     * <p>
     * The set is assembled from the sections of this region and is a snapshot: the natural spawning plan counts
     * the spawnable chunks of the region from it on the server thread, before its workers run.
     */
    java.util.Set<Long> loadedChunkKeys() {
        final java.util.Set<Long> chunks = new java.util.HashSet<>(this.chunkCount());
        for (final RegionSectionState section : this.sections.values()) {
            chunks.addAll(section.chunkKeys());
        }
        return chunks;
    }

    /**
     * Indexes {@code entity} as a member of this region, behind the entities it already holds.
     * <p>
     * Called by the regionizer under its write lock when it publishes the entity's region mapping. The entry
     * is not ticking yet: only the level's entity tick list decides that.
     */
    void indexEntityTickEntry(Entity entity, long sequence) {
        final RegionEntityTickEntry entry = new RegionEntityTickEntry(entity, sequence, entity.isAlwaysTicking(), false);
        if (this.entityTickEntries.putIfAbsent(entity, entry) != null) {
            return;
        }
        this.entityTickOrder.add(entry);
    }

    /**
     * Indexes an entry that already exists, e.g. the entry of an entity whose mapping moved into this region.
     * <p>
     * The entry keeps its identity, so its sequence - and with it its position in the tick order - survives the
     * move. A region that already knows the entity keeps its current entry.
     */
    void indexExistingEntityTickEntry(RegionEntityTickEntry entry) {
        if (this.entityTickEntries.putIfAbsent(entry.entity(), entry) != null) {
            return;
        }
        this.entityTickOrder.add(entry);
        if (entry.ticking()) {
            this.tickingEntityCount.incrementAndGet();
            if (entry.serverThreadOnly()) {
                this.serverThreadOnlyEntityCount.incrementAndGet();
            }
        }
    }

    /**
     * Removes the index entry of {@code entity}.
     *
     * @return the removed entry, or {@code null} when this region did not index the entity
     */
    @Nullable
    RegionEntityTickEntry removeEntityTickEntry(Entity entity) {
        final RegionEntityTickEntry removed = this.entityTickEntries.remove(entity);
        if (removed == null) {
            return null;
        }
        this.entityTickOrder.remove(removed);
        if (removed.ticking()) {
            this.tickingEntityCount.decrementAndGet();
            if (removed.serverThreadOnly()) {
                this.serverThreadOnlyEntityCount.decrementAndGet();
            }
        }
        return removed;
    }

    /**
     * Marks the entry of {@code entity} as part of the level's entity tick list.
     * <p>
     * An entity that was already ticking keeps its position: the vanilla tick list keeps the position of an
     * entry it already contains. A re-insertion after the entity stopped ticking appends the entry, exactly
     * like the vanilla tick list appends it, so the index order stays the tick order.
     *
     * @param sequence the sequence to assign to a re-inserted entry
     */
    void markEntityTickEntryTicking(Entity entity, long sequence) {
        final RegionEntityTickEntry entry = this.entityTickEntries.get(entity);
        if (entry == null || entry.ticking()) {
            return;
        }
        final RegionEntityTickEntry ticking = entry.withTicking(sequence);
        // Replace, so the order follows the new sequence while the entity keeps one entry only.
        this.entityTickOrder.remove(entry);
        this.entityTickEntries.put(entity, ticking);
        this.entityTickOrder.add(ticking);
        this.tickingEntityCount.incrementAndGet();
        if (ticking.serverThreadOnly()) {
            this.serverThreadOnlyEntityCount.incrementAndGet();
        }
    }

    /**
     * Marks the entry of {@code entity} as no longer part of the level's entity tick list.
     * <p>
     * The entry stays indexed: the region keeps owning the entity's mapping, and a tick-list re-insertion has
     * to append it to the order again.
     */
    void markEntityTickEntryNotTicking(Entity entity) {
        final RegionEntityTickEntry entry = this.entityTickEntries.get(entity);
        if (entry == null || !entry.ticking()) {
            return;
        }
        final RegionEntityTickEntry idle = entry.withoutTicking();
        this.entityTickOrder.remove(entry);
        this.entityTickEntries.put(entity, idle);
        this.entityTickOrder.add(idle);
        this.tickingEntityCount.decrementAndGet();
        if (idle.serverThreadOnly()) {
            this.serverThreadOnlyEntityCount.decrementAndGet();
        }
    }

    /**
     * {@return the indexed entry of {@code entity}, or {@code null} when this region does not index it}
     */
    @Nullable
    RegionEntityTickEntry entityTickEntry(Entity entity) {
        return this.entityTickEntries.get(entity);
    }

    /**
     * {@return the region's entities in tick order}
     * <p>
     * The returned list is a snapshot, so a worker can iterate it while the server thread publishes the next
     * tick list change. It is the unit the region worker enumerates: the entities it may tick are the entries
     * whose {@link RegionEntityTickEntry#ticking()} is set.
     */
    List<RegionEntityTickEntry> entityTickEntries() {
        return new ArrayList<>(this.entityTickOrder);
    }

    /** {@return the number of entities indexed for this region} */
    int entityTickEntryCount() {
        return this.entityTickEntries.size();
    }

    /** {@return the number of indexed entities the level's entity tick list currently contains} */
    int tickingEntityCount() {
        return this.tickingEntityCount.get();
    }

    /** {@return the number of ticking entities only the server thread may tick} */
    int serverThreadOnlyEntityCount() {
        return this.serverThreadOnlyEntityCount.get();
    }

    /**
     * Takes over every entry of {@code source}, used when the regionizer merges two regions.
     */
    void absorbEntityTickEntriesFrom(RegionState source) {
        if (source == this) {
            return;
        }
        for (final RegionEntityTickEntry entry : source.entityTickEntries()) {
            this.indexExistingEntityTickEntry(entry);
        }
        source.clearEntityTickEntries();
    }

    /**
     * Drops every index entry of this region, used when the region is retired or its level is cleared.
     */
    void clearEntityTickEntries() {
        this.entityTickOrder.clear();
        this.entityTickEntries.clear();
        this.tickingEntityCount.set(0);
        this.serverThreadOnlyEntityCount.set(0);
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

    /**
     * Records the version of one chunk of another region this region read.
     * <p>
     * The first version a region observes for a chunk is the one it keeps: a chunk that changes after that first
     * read differs from the recorded version, so the region is invalidated either way.
     *
     * @return {@code true} when the key was recorded, {@code false} when the same chunk was already recorded or
     *         the {@link #MAX_FOREIGN_READ_CHUNKS} bound refused it
     */
    boolean recordForeignChunkRead(long chunkKey, long version) {
        if (this.foreignChunkReads.containsKey(chunkKey)) {
            return false;
        }
        if (this.foreignChunkReads.size() >= MAX_FOREIGN_READ_CHUNKS) {
            return false;
        }
        this.foreignChunkReads.put(chunkKey, version);
        return true;
    }

    /** {@return the version of every foreign chunk this region read since its last check, by chunk key} */
    it.unimi.dsi.fastutil.longs.Long2LongMap foreignChunkReads() {
        return this.foreignChunkReads;
    }

    /** Forgets the foreign chunks this region read, so the next check only sees the reads that follow. */
    void clearForeignChunkReads() {
        this.foreignChunkReads.clear();
    }

    /**
     * Records one position this region wrote, which a re-run of the region has to re-evaluate.
     *
     * @return {@code true} when the position was kept, {@code false} when the {@link #MAX_RERUN_SEEDS} bound
     *         dropped it
     */
    boolean recordRerunSeed(long packedPos) {
        if (this.rerunSeeds.size() >= MAX_RERUN_SEEDS) {
            this.droppedRerunSeeds++;
            return false;
        }
        this.rerunSeeds.add(packedPos);
        return true;
    }

    /** {@return the positions this region wrote since its last check, as packed block positions} */
    it.unimi.dsi.fastutil.longs.LongArrayList rerunSeeds() {
        return this.rerunSeeds;
    }

    /**
     * Forgets the written positions of this region.
     *
     * @return the number of positions the {@link #MAX_RERUN_SEEDS} bound dropped since the last check
     */
    int clearRerunSeeds() {
        this.rerunSeeds.clear();
        final int dropped = this.droppedRerunSeeds;
        this.droppedRerunSeeds = 0;
        return dropped;
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
        // A retired region owns no mapping anymore, so its index is empty by the time it retires; clear it
        // anyway so a retired region can never keep an entity entry - or the entity - alive.
        this.clearEntityTickEntries();
        // A retired region is dropped from the regionizer, so its metric window is released here instead of
        // waiting for the last reference to the dead region state to disappear.
        this.tickMetrics.release();
        return true;
    }
}
