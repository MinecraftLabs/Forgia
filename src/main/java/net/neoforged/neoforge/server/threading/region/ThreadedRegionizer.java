/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal native regionizer facade while Folia's split/merge algorithm is ported.
 */
final class ThreadedRegionizer {
    private static final int REGION_SECTION_MERGE_RADIUS = 1;

    private final RegionLayout layout;
    private final Map<RegionCoordinate, RegionState> regions = new ConcurrentHashMap<>();
    private final Map<RegionSectionCoordinate, RegionSectionState> sections = new ConcurrentHashMap<>();
    private final Map<RegionSectionCoordinate, RegionState> sectionRegions = new ConcurrentHashMap<>();
    private final Map<Entity, RegionState> entities = new ConcurrentHashMap<>();
    private final Map<Entity, ServerLevel> entityLevels = new ConcurrentHashMap<>();
    /**
     * Level identities that finished a level clear. All accesses happen under the regionizer write lock,
     * the same lock that serializes region creation, so a creation either publishes before the clear (and
     * is cleared with it) or observes the closed identity and is rejected. The set is weak: an unloaded
     * {@link ServerLevel} is not kept alive, and a legitimate reload constructs a new identity that is not
     * closed.
     */
    private final Set<Object> closedLevels = Collections.newSetFromMap(new WeakHashMap<>());
    private final AtomicLong structuralChangeCount = new AtomicLong();
    private final AtomicLong splitCheckRequestCount = new AtomicLong();
    private final AtomicLong mergeCheckRequestCount = new AtomicLong();
    private final StampedLock regionLock = new StampedLock();
    private volatile boolean closed;
    private Thread writeLockOwner;

    ThreadedRegionizer(RegionLayout layout) {
        this.layout = layout;
    }

    /**
     * Resolves and inserts one immediate task while holding the same lock used by retirement and
     * merge/split publication. This closes the lookup-to-insertion race without taking a region lease, and
     * it replaces a retired mapping with a fresh live region so a handoff to a region that just died is
     * queued for its successor instead of being dropped.
     *
     * @return {@code false} when the regionizer was already cleared, in which case no region may be created
     */
    boolean enqueue(RegionCoordinate coordinate, Runnable task) {
        if (this.writeLockOwner == Thread.currentThread()) {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return false;
            }
            return this.getOrCreateLiveLocked(coordinate).enqueueTask(task);
        }
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return false;
            }
            return this.getOrCreateLiveLocked(coordinate).enqueueTask(task);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * Resolves a live region and schedules a delayed task while holding the same lock used by retirement
     * and merge/split publication.
     * <p>
     * Scheduling through a previously resolved {@link RegionState} would release the lock between lookup
     * and insertion, so a concurrent merge or retirement could cancel a request that the caller already
     * accepted. Resolving and inserting under one write lock makes the delayed and fixed-rate paths behave
     * exactly like {@link #enqueue(RegionCoordinate, Runnable)}.
     */
    RegionScheduledTask schedule(RegionCoordinate coordinate, Runnable task, long delayTicks) {
        if (this.writeLockOwner == Thread.currentThread()) {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return RegionScheduledTask.cancelled(task);
            }
            return this.getOrCreateLiveLocked(coordinate).scheduleTask(task, delayTicks);
        }
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return RegionScheduledTask.cancelled(task);
            }
            return this.getOrCreateLiveLocked(coordinate).scheduleTask(task, delayTicks);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    RegionScheduledTask scheduleAtFixedRate(RegionCoordinate coordinate, Runnable task, long initialDelayTicks, long periodTicks) {
        if (this.writeLockOwner == Thread.currentThread()) {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return RegionScheduledTask.cancelled(task);
            }
            return this.getOrCreateLiveLocked(coordinate).scheduleTaskAtFixedRate(task, initialDelayTicks, periodTicks);
        }
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(coordinate.level())) {
                return RegionScheduledTask.cancelled(task);
            }
            return this.getOrCreateLiveLocked(coordinate).scheduleTaskAtFixedRate(task, initialDelayTicks, periodTicks);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * {@return a live region for {@code coordinate}, replacing a retired mapping}
     * <p>
     * A retired region has no sections and no tracked entities, so replacing it cannot lose region state.
     * Callers must hold the write lock.
     */
    private RegionState getOrCreateLiveLocked(RegionCoordinate coordinate) {
        final RegionState region = this.getOrCreateLocked(coordinate);
        if (!region.isDead()) {
            return region;
        }
        final RegionState replacement = new RegionState(coordinate);
        this.regions.put(coordinate, replacement);
        return replacement;
    }

    @Nullable
    RegionState getOrCreate(RegionCoordinate coordinate) {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.getOrCreateForLevelLocked(coordinate);
        }

        final long stamp = this.acquireWriteLock();
        try {
            return this.getOrCreateForLevelLocked(coordinate);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * {@return a live region for {@code coordinate}, or {@code null} when its level identity is closed}
     * <p>
     * Callers must hold the write lock.
     */
    @Nullable
    private RegionState getOrCreateForLevelLocked(RegionCoordinate coordinate) {
        if (this.isLevelClosedLocked(coordinate.level())) {
            return null;
        }
        return this.getOrCreateLocked(coordinate);
    }

    /**
     * {@return true when {@code level} finished a level clear}
     * <p>
     * A closed level identity never receives new region state, so callers that already resolved work
     * for it have to drop that work instead of publishing it into a retired level.
     */
    boolean isLevelClosed(Object level) {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.isLevelClosedLocked(level);
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.isLevelClosedLocked(level);
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    private boolean isLevelClosedLocked(Object level) {
        return this.closedLevels.contains(level);
    }

    private RegionState getOrCreateLocked(RegionCoordinate coordinate) {
        if (this.closed) {
            throw new IllegalStateException("Regionizer is closed");
        }
        final RegionState existing = this.regions.get(coordinate);
        if (existing != null) {
            return existing;
        }
        final RegionState sectionRegion = this.sectionRegions.get(sectionCoordinate(coordinate));
        if (sectionRegion != null) {
            this.regions.putIfAbsent(coordinate, sectionRegion);
            return sectionRegion;
        }
        return this.regions.computeIfAbsent(coordinate, RegionState::new);
    }

    RegionState get(RegionCoordinate coordinate) {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.getLocked(coordinate);
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.getLocked(coordinate);
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    private RegionState getLocked(RegionCoordinate coordinate) {
        final RegionState region = this.regions.get(coordinate);
        return region == null ? this.sectionRegions.get(sectionCoordinate(coordinate)) : region;
    }

    @Nullable
    RegionState addChunk(ServerLevel level, int chunkX, int chunkZ) {
        final long stamp = this.acquireWriteLock();
        try {
            if (this.isLevelClosedLocked(level)) {
                return null;
            }
            final RegionSectionCoordinate coordinate = RegionSectionCoordinate.fromChunk(level, chunkX, chunkZ, this.layout);
            final RegionState region = this.getOrCreateLocked(coordinate.regionCoordinate());
            final RegionSectionState section = this.sections.computeIfAbsent(coordinate, RegionSectionState::new);
            this.sectionRegions.putIfAbsent(coordinate, region);
            final boolean wasEmpty = section.isEmpty();
            if (section.addChunk(chunkX, chunkZ) && wasEmpty) {
                region.addSection(section);
                this.requestMergeCheckIfNeeded(coordinate, region);
                this.structuralChangeCount.incrementAndGet();
            }
            return region;
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    void removeChunk(ServerLevel level, int chunkX, int chunkZ) {
        final long stamp = this.acquireWriteLock();
        try {
            if (this.isLevelClosedLocked(level)) {
                return;
            }
            final RegionSectionCoordinate coordinate = RegionSectionCoordinate.fromChunk(level, chunkX, chunkZ, this.layout);
            final RegionSectionState section = this.sections.get(coordinate);
            if (section != null && section.removeChunk(chunkX, chunkZ) && section.isEmpty()) {
                final RegionState region = this.sectionRegions.remove(coordinate);
                this.sections.remove(coordinate, section);
                if (region != null) {
                    region.removeSection(coordinate);
                    this.requestSplitCheck(region);
                    this.structuralChangeCount.incrementAndGet();
                    this.removeIfEmpty(region);
                }
            }
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    @Nullable
    RegionState addEntity(Entity entity, RegionCoordinate coordinate) {
        final long stamp = this.acquireWriteLock();
        try {
            if (this.isLevelClosedLocked(coordinate.level())) {
                return null;
            }
            final RegionState region = this.getOrCreateLocked(coordinate);
            final RegionState previous = this.entities.put(entity, region);
            this.entityLevels.put(entity, coordinate.level());
            if (previous != null && previous != region) {
                this.removeIfEmpty(previous);
                this.requestMergeCheckIfNeeded(sectionCoordinate(coordinate), region);
                this.structuralChangeCount.incrementAndGet();
            } else if (previous == null) {
                this.requestMergeCheckIfNeeded(sectionCoordinate(coordinate), region);
                this.structuralChangeCount.incrementAndGet();
            }
            return region;
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    @Nullable
    RegionState moveEntity(Entity entity, RegionCoordinate coordinate) {
        return this.addEntity(entity, coordinate);
    }

    /**
     * Applies one recorded region transfer of a complete entity graph under a single write lock.
     * <p>
     * The recorded source mapping is validated before anything is written: every entity must still map to
     * {@code sourceCoordinate} and its recorded level must still be {@code level}. A mismatch returns
     * {@code null} and leaves every mapping untouched, so the caller reports a failed commit instead of
     * repairing the recorded intent.
     * <p>
     * The destination mapping is written before the source region is retired, so the only intermediate
     * state is a duplicate entry while the write lock is held; a reader can never observe a missing one.
     * No callback, event, chunk access or user code runs inside the lock scope.
     *
     * @return the destination region, or {@code null} when the recorded source mapping is no longer current
     *         or the level identity was closed
     */
    @Nullable
    RegionState commitTransfer(RegionCoordinate sourceCoordinate, RegionCoordinate destinationCoordinate, List<Entity> entities, ServerLevel level) {
        if (entities.isEmpty()) {
            return null;
        }
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(level)) {
                return null;
            }
            if (sourceCoordinate.level() != level || destinationCoordinate.level() != level) {
                return null;
            }
            for (final Entity entity : entities) {
                final RegionState current = this.entities.get(entity);
                if (current == null || !current.coordinate().equals(sourceCoordinate)) {
                    return null;
                }
                if (this.entityLevels.get(entity) != level) {
                    return null;
                }
            }
            final RegionState destination = this.getOrCreateLocked(destinationCoordinate);
            if (destination.isDead()) {
                return null;
            }
            boolean changed = false;
            for (final Entity entity : entities) {
                final RegionState previous = this.entities.put(entity, destination);
                this.entityLevels.put(entity, level);
                if (previous != destination) {
                    changed = true;
                }
            }
            if (changed) {
                this.structuralChangeCount.incrementAndGet();
                this.requestMergeCheckIfNeeded(sectionCoordinate(destinationCoordinate), destination);
            }
            final RegionState source = this.regions.get(sourceCoordinate);
            if (source != null && source != destination) {
                this.removeIfEmptyLocked(source);
            }
            return destination;
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * {@return the region that currently owns {@code entity}, or {@code null} when it is not tracked}
     * <p>
     * Unlike {@link #moveEntity(Entity, RegionCoordinate)} this never publishes a new ownership. It is used
     * to group a batch by the authoritative region after merges or splits changed the tracked region
     * between the tick snapshot and the batch.
     */
    @Nullable
    RegionState regionFor(Entity entity) {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.entities.get(entity);
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.entities.get(entity);
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    void removeEntity(Entity entity) {
        final long stamp = this.acquireWriteLock();
        try {
            final RegionState region = this.entities.remove(entity);
            this.entityLevels.remove(entity);
            if (region != null) {
                this.structuralChangeCount.incrementAndGet();
                this.removeIfEmpty(region);
            }
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    void forEach(Consumer<RegionState> action) {
        this.regions().forEach(action);
    }

    void removeIfEmpty(RegionState region) {
        if (this.writeLockOwner == Thread.currentThread()) {
            this.removeIfEmptyLocked(region);
            return;
        }

        final long stamp = this.acquireWriteLock();
        try {
            this.removeIfEmptyLocked(region);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    private void removeIfEmptyLocked(RegionState region) {
        this.recalculateIfPending(region);
        if (region.isEmpty() && !this.hasTrackedEntities(region)) {
            if (region.retireIfEmpty()) {
                this.regions.values().removeIf(value -> value == region);
            }
        }
    }

    void clearLevel(Object level) {
        final long stamp = this.acquireWriteLock();
        try {
            // Close the identity before dropping state: every creation path checks this under the same
            // write lock, so no admission can publish a fresh region after the clear below.
            this.closedLevels.add(level);
            final int previousSize = this.regions.size() + this.sections.size() + this.entities.size();
            this.regions.values().forEach(region -> {
                if (region.coordinate().level() == level) {
                    region.clearTasks();
                    region.markDead();
                }
            });
            this.sections.keySet().removeIf(section -> section.level() == level);
            this.sectionRegions.keySet().removeIf(section -> section.level() == level);
            this.entityLevels.entrySet().removeIf(entry -> {
                if (entry.getValue() == level) {
                    this.entities.remove(entry.getKey());
                    return true;
                }
                return false;
            });
            this.regions.keySet().removeIf(coordinate -> coordinate.level() == level);
            final int currentSize = this.regions.size() + this.sections.size() + this.entities.size();
            if (currentSize != previousSize) {
                this.structuralChangeCount.incrementAndGet();
            }
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    void clear() {
        final long stamp = this.acquireWriteLock();
        try {
            this.closed = true;
            final boolean changed = !this.regions.isEmpty() || !this.sections.isEmpty() || !this.entities.isEmpty();
            this.regions.values().forEach(region -> {
                region.clearTasks();
                region.markDead();
            });
            this.entityLevels.clear();
            this.entities.clear();
            this.sectionRegions.clear();
            this.sections.clear();
            this.regions.clear();
            this.closedLevels.clear();
            if (changed) {
                this.structuralChangeCount.incrementAndGet();
            }
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    private long acquireWriteLock() {
        final Thread currentThread = Thread.currentThread();
        if (this.writeLockOwner == currentThread) {
            throw new IllegalStateException("Cannot recursively operate in the regionizer");
        }
        final long stamp = this.regionLock.writeLock();
        this.writeLockOwner = currentThread;
        return stamp;
    }

    private void releaseWriteLock(long stamp) {
        this.writeLockOwner = null;
        this.regionLock.unlockWrite(stamp);
    }

    private static RegionSectionCoordinate sectionCoordinate(RegionCoordinate coordinate) {
        return new RegionSectionCoordinate(coordinate.level(), coordinate.regionX(), coordinate.regionZ());
    }

    private boolean hasTrackedEntities(RegionState region) {
        return this.entities.containsValue(region);
    }

    int size() {
        return this.uniqueRegions().size();
    }

    long structuralChangeCount() {
        return this.structuralChangeCount.get();
    }

    long splitCheckRequestCount() {
        return this.splitCheckRequestCount.get();
    }

    long mergeCheckRequestCount() {
        return this.mergeCheckRequestCount.get();
    }

    void requestSplitCheck(RegionState region) {
        if (this.layout.isFixed()) {
            return;
        }
        this.splitCheckRequestCount.incrementAndGet();
        this.splitRegionIfNeeded(region);
    }

    void requestMergeCheck(RegionState region) {
        this.mergeCheckRequestCount.incrementAndGet();
    }

    private void requestMergeCheckIfNeeded(RegionSectionCoordinate coordinate, RegionState region) {
        if (this.layout.isFixed()) {
            return;
        }
        final Set<RegionState> neighbourRegions = new HashSet<>();
        for (int dz = -REGION_SECTION_MERGE_RADIUS; dz <= REGION_SECTION_MERGE_RADIUS; ++dz) {
            for (int dx = -REGION_SECTION_MERGE_RADIUS; dx <= REGION_SECTION_MERGE_RADIUS; ++dx) {
                if ((dx | dz) == 0) {
                    continue;
                }

                final RegionSectionCoordinate neighbour = new RegionSectionCoordinate(coordinate.level(), coordinate.sectionX() + dx, coordinate.sectionZ() + dz);
                final RegionState neighbourRegion = this.sectionRegions.get(neighbour);
                if (neighbourRegion != null && neighbourRegion != region && neighbourRegions.add(neighbourRegion)) {
                    this.requestMergeCheck(neighbourRegion);
                    this.mergeRegions(region, neighbourRegion);
                }
            }
        }
    }

    private void mergeRegions(RegionState target, RegionState source) {
        if (target == source || target.isDead() || source.isDead()) {
            return;
        }
        if (target.isRunning() || source.isRunning() || target.isWorkerSubmitted() || source.isWorkerSubmitted() || target.hasQueuedTasks() || source.hasQueuedTasks()) {
            target.markPendingRecalculation();
            source.markPendingRecalculation();
            return;
        }
        if (!source.tryMarkDead()) {
            target.markPendingRecalculation();
            source.markPendingRecalculation();
            return;
        }

        for (final RegionSectionState section : source.sections()) {
            target.addSection(section);
            this.sectionRegions.put(section.coordinate(), target);
        }
        source.clearSections();
        this.regions.replaceAll((coordinate, region) -> region == source ? target : region);
        this.entities.replaceAll((entity, region) -> region == source ? target : region);
        this.structuralChangeCount.incrementAndGet();
    }

    private void splitRegionIfNeeded(RegionState region) {
        if (this.layout.isFixed()) {
            return;
        }
        if (region.isRunning() || region.isWorkerSubmitted() || region.hasQueuedTasks()) {
            region.markPendingRecalculation();
            return;
        }

        final List<List<RegionSectionState>> components = this.connectedComponents(region.sections());
        if (components.size() <= 1) {
            return;
        }
        if (!region.tryMarkDead()) {
            region.markPendingRecalculation();
            return;
        }

        this.regions.values().removeIf(value -> value == region);
        region.clearSections();
        for (final List<RegionSectionState> component : components) {
            final RegionState splitRegion = new RegionState(component.get(0).coordinate().regionCoordinate());
            for (final RegionSectionState section : component) {
                splitRegion.addSection(section);
                this.sectionRegions.put(section.coordinate(), splitRegion);
                this.regions.put(section.coordinate().regionCoordinate(), splitRegion);
            }
        }

        this.entities.replaceAll((entity, currentRegion) -> {
            if (currentRegion != region || !(entity.level() instanceof ServerLevel level)) {
                return currentRegion;
            }
            return this.getOrCreateLocked(this.layout.regionAt(level, entity.blockPosition()));
        });
        this.structuralChangeCount.incrementAndGet();
    }

    private void recalculateIfPending(RegionState region) {
        if (region.isRunning() || region.isWorkerSubmitted() || region.hasQueuedTasks() || region.isDead()) {
            return;
        }
        if (!region.consumePendingRecalculation()) {
            return;
        }

        for (final RegionSectionState section : region.sections()) {
            this.requestMergeCheckIfNeeded(section.coordinate(), region);
        }
        this.splitRegionIfNeeded(region);
    }

    private List<List<RegionSectionState>> connectedComponents(Collection<RegionSectionState> regionSections) {
        final Map<RegionSectionCoordinate, RegionSectionState> remaining = new ConcurrentHashMap<>();
        for (final RegionSectionState section : regionSections) {
            remaining.put(section.coordinate(), section);
        }

        final List<List<RegionSectionState>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            final RegionSectionState first = remaining.values().iterator().next();
            remaining.remove(first.coordinate());
            final List<RegionSectionState> component = new ArrayList<>();
            final Queue<RegionSectionState> queue = new ArrayDeque<>();
            queue.add(first);

            while (!queue.isEmpty()) {
                final RegionSectionState section = queue.remove();
                component.add(section);
                for (int dz = -REGION_SECTION_MERGE_RADIUS; dz <= REGION_SECTION_MERGE_RADIUS; ++dz) {
                    for (int dx = -REGION_SECTION_MERGE_RADIUS; dx <= REGION_SECTION_MERGE_RADIUS; ++dx) {
                        if ((dx | dz) == 0) {
                            continue;
                        }
                        final RegionSectionCoordinate neighbour = new RegionSectionCoordinate(section.coordinate().level(), section.coordinate().sectionX() + dx, section.coordinate().sectionZ() + dz);
                        final RegionSectionState neighbourSection = remaining.remove(neighbour);
                        if (neighbourSection != null) {
                            queue.add(neighbourSection);
                        }
                    }
                }
            }

            components.add(component);
        }

        return components;
    }

    private Set<RegionState> uniqueRegions() {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.uniqueRegionsLocked();
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.uniqueRegionsLocked();
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    private Set<RegionState> uniqueRegionsLocked() {
        return new HashSet<>(this.regions.values());
    }

    int sectionCount() {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.sections.size();
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.sections.size();
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    int loadedChunkCount() {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.loadedChunkCountLocked();
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.loadedChunkCountLocked();
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    private int loadedChunkCountLocked() {
        int count = 0;
        for (final RegionSectionState section : this.sections.values()) {
            count += section.chunkCount();
        }
        return count;
    }

    int entityCount() {
        if (this.writeLockOwner == Thread.currentThread()) {
            return this.entities.size();
        }

        final long stamp = this.regionLock.readLock();
        try {
            return this.entities.size();
        } finally {
            this.regionLock.unlockRead(stamp);
        }
    }

    int runningRegionCount() {
        int count = 0;
        for (final RegionState region : this.uniqueRegions()) {
            if (region.isRunning()) {
                ++count;
            }
        }
        return count;
    }

    int submittedRegionCount() {
        int count = 0;
        for (final RegionState region : this.uniqueRegions()) {
            if (region.isWorkerSubmitted()) {
                ++count;
            }
        }
        return count;
    }

    int pendingRecalculationCount() {
        int count = 0;
        for (final RegionState region : this.uniqueRegions()) {
            if (region.hasPendingRecalculation()) {
                ++count;
            }
        }
        return count;
    }

    long totalRegionTickCount() {
        long count = 0L;
        for (final RegionState region : this.uniqueRegions()) {
            count += region.tickMetrics().tickCount();
        }
        return count;
    }

    long maxRegionLastTickDurationNanos() {
        long max = 0L;
        for (final RegionState region : this.uniqueRegions()) {
            max = Math.max(max, region.tickMetrics().lastTickDurationNanos());
        }
        return max;
    }

    long cappedDrainCount() {
        long count = 0L;
        for (final RegionState region : this.uniqueRegions()) {
            count += region.cappedDrainCount();
        }
        return count;
    }

    Collection<RegionState> regions() {
        return this.uniqueRegions();
    }
}
