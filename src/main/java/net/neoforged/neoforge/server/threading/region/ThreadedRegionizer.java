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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal native regionizer facade while Folia's split/merge algorithm is ported.
 */
final class ThreadedRegionizer {
    private static final int REGION_SECTION_MERGE_RADIUS = 1;
    private static final Logger LOGGER = LogManager.getLogger();

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
    /**
     * Sequence of the entity tick index, assigned when an entity enters a region's index or re-enters the
     * level's entity tick list. It is strictly increasing per regionizer, so the index order of one region is
     * the order of the level's own entity tick list restricted to that region - also after a merge, a split or
     * a move between regions, which keep the sequence of the entry they migrate.
     */
    private final AtomicLong entityTickSequence = new AtomicLong();
    /**
     * Number of write-lock acquisitions, so one barrier that publishes many mappings in one scope is
     * distinguishable from one acquisition per mapping.
     */
    private final AtomicLong writeLockAcquisitionCount = new AtomicLong();
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
            // The coordinate is a member cell of the merged region, even when its chunk was loaded before
            // this regionizer existed and no chunk load publishes it.
            sectionRegion.addCell(sectionCoordinate(coordinate));
            return sectionRegion;
        }
        final RegionState created = this.regions.computeIfAbsent(coordinate, RegionState::new);
        created.addCell(sectionCoordinate(coordinate));
        return created;
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
            return this.addEntityLocked(entity, coordinate);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * Publishes the region mapping of one entity and keeps the region's entity tick index in step with it.
     * <p>
     * The index side is what makes a region's entity set enumerable for its worker: an entity that is mapped
     * into a region is indexed there once, and a move between regions migrates the entry instead of dropping
     * it, so the entity keeps its position in the region's tick order. A first mapping creates a non-ticking
     * entry; only the level's entity tick list marks an entry as ticking.
     * <p>
     * Runs under the regionizer write lock, exactly like every other index mutation.
     */
    @Nullable
    private RegionState addEntityLocked(Entity entity, RegionCoordinate coordinate) {
        if (this.isLevelClosedLocked(coordinate.level())) {
            return null;
        }
        final RegionState region = this.getOrCreateLocked(coordinate);
        final RegionState previous = this.entities.put(entity, region);
        this.entityLevels.put(entity, coordinate.level());
        if (previous == region) {
            return region;
        }
        if (previous == null) {
            region.indexEntityTickEntry(entity, this.entityTickSequence.incrementAndGet());
            this.requestMergeCheckIfNeeded(sectionCoordinate(coordinate), region);
            this.structuralChangeCount.incrementAndGet();
        } else {
            this.migrateEntityTickEntry(entity, previous, region);
            this.removeIfEmptyLocked(previous);
            this.requestMergeCheckIfNeeded(sectionCoordinate(coordinate), region);
            this.structuralChangeCount.incrementAndGet();
        }
        return region;
    }

    /**
     * Moves the index entry of {@code entity} from {@code source} to {@code destination}.
     */
    private void migrateEntityTickEntry(Entity entity, RegionState source, RegionState destination) {
        final RegionEntityTickEntry entry = source.removeEntityTickEntry(entity);
        if (entry != null) {
            destination.indexExistingEntityTickEntry(entry);
        }
    }

    /**
     * Indexes {@code entity} for one start of its entity tick, exactly like the vanilla tick list adds it.
     * <p>
     * Called by the entity tick list hook, on the server thread, when the level starts ticking an entity: the
     * region mapping is published if the entity is not tracked yet (this is the only place a first mapping is
     * created without the entity having ticked), and the index entry is marked as ticking so the region's
     * worker ticks it. A re-insertion after the entity stopped ticking appends the entry, exactly like the
     * vanilla tick list appends it.
     *
     * @return {@code false} when the entity could not be indexed, so the caller has to keep it on the
     *         server-thread path
     */
    boolean onEntityTickStarted(ServerLevel level, Entity entity) {
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(level)) {
                return false;
            }
            final RegionState region = this.addEntityLocked(entity, this.layout.regionAt(level, entity.blockPosition()));
            if (region == null) {
                return false;
            }
            region.markEntityTickEntryTicking(entity, this.entityTickSequence.incrementAndGet());
            return true;
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * Records that the level's entity tick list no longer contains {@code entity}.
     * <p>
     * Called by the entity tick list hook on the server thread. The mapping and the index entry stay: the
     * region still owns the entity (its scheduled tasks and its region queries keep working), and only the
     * tick bit is cleared, so no worker ticks an entity the level stopped ticking.
     */
    void onEntityTickEnded(ServerLevel level, Entity entity) {
        final long stamp = this.acquireWriteLock();
        try {
            final RegionState region = this.entities.get(entity);
            if (region != null) {
                region.markEntityTickEntryNotTicking(entity);
            }
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
     * {@link TransferOutcome#SOURCE_MAPPING_CHANGED} and leaves every mapping untouched, so the caller
     * reports a failed commit instead of repairing the recorded intent.
     * <p>
     * The destination region is resolved with a plain lookup under the write lock, so a retired destination
     * is never recreated by a commit that was recorded against the previous region state. A region that is
     * ticking or submitted to a worker is never written to; that answers {@link TransferOutcome#REGION_BUSY}
     * exactly like {@link #mergeRegions(RegionState, RegionState)} defers a merge under an active lease.
     * <p>
     * The destination mapping is written before the source region is retired, so the only intermediate
     * state is a duplicate entry while the write lock is held; a reader can never observe a missing one.
     * The retirement and merge recalculation that follow the write do run regionizer code under the lock: a
     * pending split or merge may read {@code entity.level()} and {@code entity.blockPosition()} and may
     * re-point the transferred entities to a merged region. That maintenance is best effort - the mapping is
     * already committed, so a failure is logged and the commit still reports
     * {@link TransferOutcome#COMMITTED} instead of a failed transfer.
     *
     * @return the outcome of the write, resolved under the write lock
     */
    TransferOutcome commitTransfer(RegionCoordinate sourceCoordinate, RegionCoordinate destinationCoordinate, List<Entity> entities, ServerLevel level) {
        if (entities.isEmpty()) {
            return TransferOutcome.SOURCE_MAPPING_CHANGED;
        }
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed || this.isLevelClosedLocked(level)) {
                return TransferOutcome.LEVEL_CLOSED;
            }
            if (sourceCoordinate.level() != level || destinationCoordinate.level() != level) {
                return TransferOutcome.SOURCE_MAPPING_CHANGED;
            }
            for (final Entity entity : entities) {
                final RegionState current = this.entities.get(entity);
                if (current == null || !current.coordinate().equals(sourceCoordinate)) {
                    return TransferOutcome.SOURCE_MAPPING_CHANGED;
                }
                if (this.entityLevels.get(entity) != level) {
                    return TransferOutcome.SOURCE_MAPPING_CHANGED;
                }
            }
            final RegionState source = this.getLocked(sourceCoordinate);
            if (source == null || source.isDead()) {
                return TransferOutcome.SOURCE_MAPPING_CHANGED;
            }
            final RegionState destination = this.getLocked(destinationCoordinate);
            if (destination == null || destination.isDead()) {
                return TransferOutcome.DESTINATION_INACTIVE;
            }
            if (source.isRunning() || source.isWorkerSubmitted() || destination.isRunning() || destination.isWorkerSubmitted()) {
                return TransferOutcome.REGION_BUSY;
            }
            boolean changed = false;
            for (final Entity entity : entities) {
                final RegionState previous = this.entities.put(entity, destination);
                this.entityLevels.put(entity, level);
                if (previous != destination) {
                    changed = true;
                    if (previous != null) {
                        this.migrateEntityTickEntry(entity, previous, destination);
                    } else {
                        destination.indexEntityTickEntry(entity, this.entityTickSequence.incrementAndGet());
                    }
                }
            }
            // The mapping is committed from here on. Everything below is recalculation for the regions the
            // write emptied or filled; it must not turn the written transfer into a failed one.
            try {
                if (changed) {
                    this.structuralChangeCount.incrementAndGet();
                    this.requestMergeCheckIfNeeded(sectionCoordinate(destinationCoordinate), destination);
                }
                final RegionState retiringSource = this.regions.get(sourceCoordinate);
                if (retiringSource != null && retiringSource != destination) {
                    this.removeIfEmptyLocked(retiringSource);
                }
            } catch (RuntimeException | Error maintenanceFailure) {
                LOGGER.warn("Region maintenance after a recorded entity transfer from {} to {} failed; the recorded mapping is committed",
                        sourceCoordinate, destinationCoordinate, maintenanceFailure);
            }
            return TransferOutcome.COMMITTED;
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * Publishes the deferred ownership updates of one entity batch barrier under a single write lock.
     * <p>
     * Every move is validated before the first write, so a refused batch never leaves a part of itself
     * published: a closed regionizer, a destination in a level identity that finished a level clear, an
     * entity tracked in another level, or a retired destination region refuses the whole batch and reports
     * why. The caller then keeps its per-entity publication for that barrier, which is the behavior of a
     * barrier that cannot batch.
     * <p>
     * A move names the region its deferring thread captured. An entity that is already mapped to exactly
     * that live region has nothing to publish and is reported as unchanged instead of being written again, and
     * an untracked entity - or one whose published region was retired - is added like
     * {@link #addEntity(Entity, RegionCoordinate)} adds it. A move that names another region than the
     * published one is not written here: it is reported as a transfer and goes through the recorded transfer
     * transaction, which never writes a region that a worker is ticking and can defer a busy handoff to the
     * next barrier.
     *
     * @param moves the ownership updates of one barrier, one per entity
     * @return the applied result, or a refused result that names the reason nothing was written
     */
    MoveBatchResult publishEntityMoves(List<OwnerMove> moves) {
        final long stamp = this.acquireWriteLock();
        try {
            if (this.closed) {
                return MoveBatchResult.refused(MoveBatchOutcome.REGIONIZER_CLOSED);
            }
            for (final OwnerMove move : moves) {
                if (this.isLevelClosedLocked(move.destination().level())) {
                    return MoveBatchResult.refused(MoveBatchOutcome.LEVEL_CLOSED);
                }
            }
            // The classification below is the rest of the validation: it only reads, so a refused batch still
            // has written nothing when it returns.
            final List<Entity> unchanged = new ArrayList<>();
            final List<OwnerMove> transfers = new ArrayList<>();
            final List<OwnerMove> additions = new ArrayList<>();
            for (final OwnerMove move : moves) {
                final ServerLevel level = move.destination().level();
                final ServerLevel trackedLevel = this.entityLevels.get(move.entity());
                if (trackedLevel != null && trackedLevel != level) {
                    return MoveBatchResult.refused(MoveBatchOutcome.LEVEL_MISMATCH);
                }
                final RegionState current = this.entities.get(move.entity());
                if (current != null && !current.isDead()) {
                    if (current.coordinate().equals(move.destination())) {
                        unchanged.add(move.entity());
                    } else {
                        transfers.add(move);
                    }
                    continue;
                }
                final RegionState destination = this.getLocked(move.destination());
                if (destination != null && destination.isDead()) {
                    return MoveBatchResult.refused(MoveBatchOutcome.DESTINATION_INACTIVE);
                }
                additions.add(move);
            }
            final List<Entity> published = new ArrayList<>(additions.size());
            // A non-fixed layout recalculates merges per destination section, so the batch requests one merge
            // check per destination region instead of one per entity it publishes into it.
            final Set<RegionCoordinate> mergeChecks = this.layout.isFixed() ? null : new LinkedHashSet<>();
            for (final OwnerMove move : additions) {
                final RegionState destination = this.getOrCreateLocked(move.destination());
                this.entities.put(move.entity(), destination);
                this.entityLevels.put(move.entity(), move.destination().level());
                destination.indexEntityTickEntry(move.entity(), this.entityTickSequence.incrementAndGet());
                published.add(move.entity());
                if (mergeChecks != null) {
                    mergeChecks.add(move.destination());
                }
            }
            if (!published.isEmpty()) {
                // One structural change per written mapping, exactly like one single-entity publication counts
                // one change per added or re-pointed entity.
                this.structuralChangeCount.addAndGet(published.size());
                if (mergeChecks != null) {
                    for (final RegionCoordinate coordinate : mergeChecks) {
                        this.requestMergeCheckIfNeeded(sectionCoordinate(coordinate), this.getOrCreateLocked(coordinate));
                    }
                }
            }
            return new MoveBatchResult(MoveBatchOutcome.APPLIED, published, unchanged, transfers);
        } finally {
            this.releaseWriteLock(stamp);
        }
    }

    /**
     * One ownership update of a barrier: the entity and the destination region its deferring thread captured.
     */
    record OwnerMove(Entity entity, RegionCoordinate destination) {}

    /**
     * How {@link #publishEntityMoves(List)} resolved a batch: {@link #APPLIED} or the reason the batch was
     * refused as a whole and wrote nothing.
     */
    enum MoveBatchOutcome {
        /** Every move was resolved: unchanged, published, or reported as a transfer. */
        APPLIED,
        /** The regionizer finished its shutdown, so no mapping may be created or re-pointed. */
        REGIONIZER_CLOSED,
        /** A destination names a level identity that finished a level clear. */
        LEVEL_CLOSED,
        /** A destination names a level the entity is not tracked in. */
        LEVEL_MISMATCH,
        /** A destination region is retired. */
        DESTINATION_INACTIVE
    }

    /**
     * The result of one batch publication.
     *
     * @param published entities whose mapping the batch wrote, i.e. the entities that were untracked until
     *                  this barrier
     * @param unchanged entities that are already mapped to the destination, so nothing was written for them
     * @param transfers entities that are mapped to another region and need a recorded transfer
     */
    record MoveBatchResult(MoveBatchOutcome outcome, List<Entity> published, List<Entity> unchanged, List<OwnerMove> transfers) {
        static MoveBatchResult refused(MoveBatchOutcome outcome) {
            return new MoveBatchResult(outcome, List.of(), List.of(), List.of());
        }

        boolean applied() {
            return this.outcome == MoveBatchOutcome.APPLIED;
        }
    }

    /**
     * Outcome of {@link #commitTransfer(RegionCoordinate, RegionCoordinate, List, ServerLevel)}.
     * <p>
     * The values are resolved under the regionizer write lock, so the engine reports the exact reason a
     * recorded transfer was not applied instead of collapsing every rejection into a missing mapping.
     */
    enum TransferOutcome {
        /** The recorded mapping was written to the recorded destination. */
        COMMITTED,
        /** A member no longer maps to the recorded source, or the token names another level. */
        SOURCE_MAPPING_CHANGED,
        /** The recorded destination is absent or retired. */
        DESTINATION_INACTIVE,
        /** The source or destination region is ticked or submitted to a region worker. */
        REGION_BUSY,
        /** The level identity or the whole regionizer was closed. */
        LEVEL_CLOSED
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
                region.removeEntityTickEntry(entity);
                this.structuralChangeCount.incrementAndGet();
                this.removeIfEmptyLocked(region);
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
                    region.clearEntityTickEntries();
                    region.clearCells();
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
                region.clearEntityTickEntries();
                region.clearCells();
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
        this.writeLockAcquisitionCount.incrementAndGet();
        return stamp;
    }

    private void releaseWriteLock(long stamp) {
        this.writeLockOwner = null;
        this.regionLock.unlockWrite(stamp);
    }

    private static RegionSectionCoordinate sectionCoordinate(RegionCoordinate coordinate) {
        return new RegionSectionCoordinate(coordinate.level(), coordinate.regionX(), coordinate.regionZ());
    }

    /**
     * {@return true when the region still owns an entity mapping}
     * <p>
     * The entity tick index of the region holds exactly the mapped entities, so the check is constant time
     * instead of a scan of the whole level's entity map.
     */
    private boolean hasTrackedEntities(RegionState region) {
        return region.entityTickEntryCount() > 0;
    }

    /**
     * {@return the tick metrics of every live region, in regionizer order}
     * <p>
     * The per-region snapshots are immutable, so the diagnostics of one call are consistent with each other
     * even when a worker records the next tick while the list is built. Retired regions are already released
     * and are not part of the live region set, so their windows never appear here.
     */
    List<RegionTickSnapshot> regionTickSnapshots() {
        final Set<RegionState> live = this.uniqueRegions();
        final List<RegionTickSnapshot> snapshots = new ArrayList<>(live.size());
        for (final RegionState region : live) {
            final RegionCoordinate coordinate = region.coordinate();
            snapshots.add(region.tickMetrics().snapshot(dimensionName(coordinate.level()), coordinate.regionX(), coordinate.regionZ()));
        }
        return List.copyOf(snapshots);
    }

    /**
     * {@return the dimension id the per-region metrics are labelled with}
     * <p>
     * A level without a dimension key has no id to report; the metrics of such a level are labelled
     * {@code unknown} instead of failing the whole diagnostics snapshot.
     */
    private static String dimensionName(ServerLevel level) {
        final ResourceKey<Level> dimension = level.dimension();
        return dimension == null ? "unknown" : dimension.location().toString();
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

    long writeLockAcquisitionCount() {
        return this.writeLockAcquisitionCount.get();
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
        // The merged region keeps every entity and every cell of both regions; the tick order of the level's
        // own list is preserved by the sequences of the migrated entries.
        target.absorbEntityTickEntriesFrom(source);
        target.absorbCellsFrom(source);
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
        region.clearCells();
        for (final List<RegionSectionState> component : components) {
            final RegionState splitRegion = new RegionState(component.get(0).coordinate().regionCoordinate());
            for (final RegionSectionState section : component) {
                splitRegion.addSection(section);
                splitRegion.addCell(section.coordinate());
                this.sectionRegions.put(section.coordinate(), splitRegion);
                this.regions.put(section.coordinate().regionCoordinate(), splitRegion);
            }
        }

        this.entities.replaceAll((entity, currentRegion) -> {
            if (currentRegion != region || !(entity.level() instanceof ServerLevel level)) {
                return currentRegion;
            }
            final RegionState destination = this.getOrCreateLocked(this.layout.regionAt(level, entity.blockPosition()));
            this.migrateEntityTickEntry(entity, region, destination);
            return destination;
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

    /**
     * {@return the live regions of {@code level} whose entity tick index holds at least one ticking entity}
     * <p>
     * This is the activation set of the level's entity tick: the server thread leases exactly these regions
     * and submits one worker task per region. A region without a ticking entity has no entity work this tick
     * (its entities are mapped but not in the level's entity tick list), so it is not activated at all.
     */
    void collectActiveEntityTickRegions(ServerLevel level, List<RegionState> active) {
        final long stamp = this.regionLock.readLock();
        try {
            for (final RegionState region : this.regions.values()) {
                if (region.coordinate().level() != level || region.isDead() || region.tickingEntityCount() == 0) {
                    continue;
                }
                // A merged region is registered under every member coordinate; the region that owns its own
                // coordinate is the one that is activated.
                if (this.regions.get(region.coordinate()) == region) {
                    active.add(region);
                }
            }
        } finally {
            this.regionLock.unlockRead(stamp);
        }
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
