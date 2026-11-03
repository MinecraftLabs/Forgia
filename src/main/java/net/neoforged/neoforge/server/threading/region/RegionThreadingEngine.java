/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.GlobalRegionScheduler;
import net.neoforged.neoforge.server.threading.RegionScheduler;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Native NeoForge region threading engine shell.
 * <p>
 * This class is intentionally small until the Folia regionizer and tick loop are ported.
 */
public final class RegionThreadingEngine implements RegionThreading {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGION_LEASE_ATTEMPTS = 4;
    /**
     * Number of entity batch barriers a deferred entity move waits for a busy region before it is published
     * through the legacy path. Every attempt happens at a barrier on the server thread, so the bound is
     * counted in barriers and never in a spin.
     */
    private static final int MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS = 3;
    /**
     * Chunk margin around an entity's own chunk that the server thread requires to be loaded before it
     * submits that entity to a worker.
     * <p>
     * One chunk is 16 blocks and covers every block, fluid and collision read a vanilla entity tick makes
     * with a loading request: the collision box and the eye position are read at most 2 blocks from the
     * entity, and the AI block scans that reach further do not use the loading accessor
     * ({@code RemoveBlockGoal} probes its 24-block search range with {@code getChunk(..., false)}, and
     * pathfinding reads a {@code PathNavigationRegion} snapshot). A read that still leaves the margin is
     * refused by the chunk source guard and handed to the server-thread fallback, so the margin is a
     * heuristic that keeps the common case off the chunk source, not a correctness bound.
     */
    private static final int ENTITY_TICK_CHUNK_MARGIN = 1;
    /** Sentinels of the per batch neighborhood cache: chunk not asked yet, computed not loaded, loaded. */
    private static final byte NEIGHBORHOOD_UNKNOWN = -1;
    private static final byte NEIGHBORHOOD_NOT_LOADED = 0;
    private static final byte NEIGHBORHOOD_LOADED = 1;
    private static final java.util.concurrent.atomic.AtomicLong DIAG_LAST = new java.util.concurrent.atomic.AtomicLong();
    private static final BasicRegionThreadingContext NONE_CONTEXT = new BasicRegionThreadingContext(RegionThreadingContext.Kind.NONE, null, null, null, null);
    private static final ThreadLocal<RegionThreadingContext> CURRENT_CONTEXT = ThreadLocal.withInitial(() -> NONE_CONTEXT);

    private final RegionLayout layout;
    private final GlobalRegionState globalRegion = new GlobalRegionState();
    private final ThreadedRegionizer regionizer;
    /**
     * Registered entity scheduled tasks, grouped by the entity they belong to.
     * <p>
     * A move notification only reaches the wrappers of its own entity, so the ownership handoff of a barrier
     * looks the entity up instead of scanning every registered task: an unchanged handoff can then skip the
     * scan for an entity that has no wrapper at all, and a changed one costs the wrappers of that entity.
     * Every task is registered and unregistered through the same entry, so the entry of an entity disappears
     * with its last wrapper and no entity stays reachable through an empty group.
     */
    private final Map<Entity, Set<EntityRegionScheduledTask>> entityTasksByEntity = new ConcurrentHashMap<>();
    private final RegionWorkerPool workerPool;
    private final GlobalRegionTickRunner globalTickRunner = new GlobalRegionTickRunner();
    private final RegionTickRunner tickRunner = new RegionTickRunner();
    private final GlobalRegionScheduler globalScheduler = new EngineGlobalRegionScheduler(this);
    private final RegionScheduler regionScheduler = new EngineRegionScheduler(this);
    private final EntityRegionScheduler entityScheduler = new EngineEntityRegionScheduler(this);
    private final boolean workerExecutionEnabled;
    private final boolean globalWorkerExecutionEnabled;
    private final boolean entityTickWorkerExecutionEnabled;
    private final boolean blockEntityTickWorkerExecutionEnabled;
    private final boolean scheduledTickWorkerExecutionEnabled;
    private final boolean randomTickWorkerExecutionEnabled;
    private final AtomicLong rejectedWorkerTaskCount = new AtomicLong();
    private final AtomicLong failedQueuedTaskCount = new AtomicLong();
    private final AtomicLong entityTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedEntityTicks = new AtomicLong();
    private final AtomicLong fallbackEntityTicks = new AtomicLong();
    private final AtomicLong deferredEntityTicks = new AtomicLong();
    /**
     * Entity ticks a worker handed back because the chunk source refused a chunk the tick needed. Each one
     * is ticked on the server thread after the batch instead of blocking the worker on the chunk source.
     */
    private final AtomicLong chunkFallbackEntityTicks = new AtomicLong();
    /**
     * Entities the pre-load check refused because their own chunk neighborhood was not loaded. They are
     * ticked on the server thread like the entities whose region could not be leased, while the rest of
     * their region still runs on a worker.
     */
    private final AtomicLong chunkPreloadRefusedEntityTicks = new AtomicLong();
    private final AtomicLong blockEntityTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedBlockEntityTicks = new AtomicLong();
    private final AtomicLong fallbackBlockEntityTicks = new AtomicLong();
    private final AtomicLong scheduledTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedScheduledTicks = new AtomicLong();
    private final AtomicLong fallbackScheduledTicks = new AtomicLong();
    private final AtomicLong randomTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedRandomTicks = new AtomicLong();
    private final AtomicLong fallbackRandomTicks = new AtomicLong();
    private final AtomicBoolean entityTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean blockEntityTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean scheduledTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean randomTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean entityTickFallbackNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean scheduledTickFallbackNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean randomTickFallbackNoticeLogged = new AtomicBoolean();
    /**
     * Declares who may touch the vanilla entity section/index storage while this engine's workers run.
     * <p>
     * Entity sections, their class index and the by-id/by-uuid lookup are still the single-threaded
     * vanilla structures, so production uses {@link RegionEntityStorageMode#MAIN_THREAD_ONLY}: native
     * workers are rejected from the legacy entity query surface and from every guarded storage mutation.
     * Entity ticks therefore stay on the server thread; the block-entity slice only runs the types whose
     * compatibility declaration proves that their tick does not touch storage.
     */
    private final RegionEntityStorageMode entityStorageMode;
    /**
     * Mechanics-test seam for the scheduled/random callback slices. Production leaves it {@code false}:
     * no scheduled or random callback class is admitted as entity-storage safe yet, so a configured worker
     * slice falls back to the vanilla inline path. Only the internal mechanics tests enable it.
     */
    private volatile boolean unclassifiedWorkerCallbacksForTesting;
    /**
     * Mechanics-test seam for the legacy entity query handoff. Production rejects legacy entity queries
     * from native workers; the transport itself stays covered by the tests that enable this seam.
     */
    private volatile boolean legacyEntityQueryHandoffForTesting;
    /**
     * Diagnostics-only observer of a worker entity tick failure.
     * <p>
     * The entity worker diagnostics benchmark installs it so one failing tick is recorded and the group
     * continues with the next entity; the production tick loop has no catch and lets the first failure
     * abort the group, the batch and the level tick. Production leaves this {@code null}, so the worker
     * body keeps the vanilla failure path.
     */
    @Nullable
    private volatile BiConsumer<Entity, Throwable> entityTickFailureObserverForTesting;
    /**
     * Random source of the entity tick group that executes on the current thread. It is installed once per
     * group for the whole worker tick loop, so {@link #currentGroupRandom()} answers with it for every
     * entity of the group and returns {@code null} again after the loop; the vanilla level random stays
     * authoritative outside a batch.
     */
    private final ThreadLocal<RandomSource> currentEntityTickRandom = new ThreadLocal<>();
    /**
     * Random source of the scheduled tick group that executes on the current thread. It is only set for
     * the duration of a worker group, so {@link #scheduledTickRandom()} returns {@code null} everywhere
     * else and the vanilla level random stays authoritative outside a batch.
     */
    private final ThreadLocal<RandomSource> currentScheduledTickRandom = new ThreadLocal<>();
    /**
     * Server-owned mutation queue of the region tick group executing on the current thread. It is only
     * set for the duration of a worker group; the server thread drains it after the phase, so a worker
     * never mutates either {@code LevelTicks} instance concurrently.
     * <p>
     * Scheduled block/fluid ticks and random ticks both mutate {@code LevelTicks} through this queue, so
     * the two slices never mutate the scheduling structures concurrently.
     */
    private final ThreadLocal<Queue<Runnable>> currentRegionTickMutations = new ThreadLocal<>();
    /**
     * Random source of the random tick group that executes on the current thread. It is only set for the
     * duration of a worker group, so {@link #randomTickRandom()} returns {@code null} everywhere else
     * and the vanilla level random stays authoritative outside a batch.
     */
    private final ThreadLocal<RandomSource> currentRandomTickRandom = new ThreadLocal<>();
    private final Set<RegionTickBatch> activeTickBatches = ConcurrentHashMap.newKeySet();
    /**
     * Pending and terminal decisions of the recorded cross-region entity transfer transaction. The
     * journal, not the live entity state, decides whether a commit may run.
     */
    private final RegionEntityTransferJournal entityTransferJournal = new RegionEntityTransferJournal();
    private final AtomicLong nextEntityTransferTokenId = new AtomicLong();
    /**
     * Deferred entity moves whose recorded commit answered {@link RegionEntityTransferResult#REGION_BUSY}.
     * Every entry keeps the destination captured when the move was deferred and is applied again at the next
     * entity batch barrier, so a busy region delays the handoff instead of dropping it or replacing its
     * destination with one re-derived from the live position. Guarded by {@link #admissionLock}, so a level
     * clear and a shutdown drop the entries of their level together with the journal.
     */
    private final List<DeferredEntityMove> pendingDeferredEntityMoves = new ArrayList<>();
    private final AtomicLong legacyDeferredEntityMoveCount = new AtomicLong();
    private final AtomicLong retriedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership updates a barrier resolved through the regionizer batch, i.e. updates that needed a
     * publication (an untracked entity) or a recorded transfer.
     */
    private final AtomicLong batchedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership updates that named the region their entity was already mapped to. They publish
     * nothing, and their scheduled-task notification is skipped for an entity without a wrapper.
     */
    private final AtomicLong unchangedDeferredEntityMoveCount = new AtomicLong();
    /**
     * Deferred ownership batches the regionizer refused as a whole (a closed regionizer, a closed level
     * identity, a level mismatch or a retired destination). The barrier then publishes every update of that
     * batch through the per-entity path, which is the behavior of a barrier that cannot batch.
     */
    private final AtomicLong refusedDeferredEntityMoveBatchCount = new AtomicLong();
    /**
     * Scheduled entity tasks that a move notification had to migrate to another region, i.e. tasks whose
     * entity crossed a region boundary while the task waited.
     */
    private final AtomicLong entityTaskMigrationCount = new AtomicLong();
    private final AtomicLong appliedDeferredSectionUpdateCount = new AtomicLong();
    private final AtomicLong droppedDeferredSectionUpdateCount = new AtomicLong();
    /**
     * Deferred removals a barrier applied on the server thread. One applied removal is one level callback of
     * the removal: the section membership, the tracking and ticking callbacks, the identity ledger and the
     * region index of the entity are written exactly like a server-thread removal.
     */
    private final AtomicLong appliedDeferredRemovalCount = new AtomicLong();
    /**
     * Deferred removals that did not complete: the engine shut down (including the removals a shut-down batch
     * releases without applying), the level identity closed, or the level callback threw. A dropped removal
     * never leaves a half-applied removal behind.
     */
    private final AtomicLong droppedDeferredRemovalCount = new AtomicLong();
    /**
     * Removal requests that were no-ops: the entity was already recorded in the same batch, its removal had
     * already been applied by the server thread, it was revived, it left the level or its level changed. A
     * repeat is never a second removal.
     */
    private final AtomicLong repeatedDeferredRemovalCount = new AtomicLong();
    /**
     * Serializes region drain activation with lifecycle cleanup. A drain holds the read lock only while
     * it checks the lifecycle state and marks the lease or worker submission; shutdown and level clear
     * hold the write lock for the complete cleanup, so an activation either wins and is awaited or is
     * rejected after cleanup completed.
     */
    private final ReentrantReadWriteLock lifecycleGate = new ReentrantReadWriteLock();
    /**
     * Test hook run immediately before a level drain acquires a region lease or marks a worker
     * submission. It lets tests force the interleaving with level cleanup; production leaves it
     * {@code null}.
     */
    @Nullable
    private volatile Runnable beforeRegionDrainActivation;
    /**
     * Test hook run while an admission holds {@link #admissionLock}, immediately before the admission is
     * published. It lets tests force the interleaving between an admission and level cleanup; production
     * leaves it {@code null}.
     */
    @Nullable
    private volatile Runnable beforeRegionAdmission;
    /**
     * Serializes task admission with the shutdown transition. Every scheduler path takes this lock for the
     * lookup and insertion, and shutdown sets its flag while holding it, so a task can never be inserted
     * after the cleanup that closes the queues.
     */
    private final Object admissionLock = new Object();
    private volatile boolean shutdown;

    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, false, false);
    }

    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, false);
    }

    /**
     * Creates an engine with the legacy {@link RegionPolicy#DYNAMIC} layout.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, false);
    }

    /**
     * Creates an engine with the legacy {@link RegionPolicy#DYNAMIC} layout.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, RegionPolicy.DYNAMIC);
    }

    /**
     * Creates an engine with an explicit region layout policy.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, false, regionPolicy);
    }

    /**
     * Creates an engine with an explicit region layout policy.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, false, regionPolicy);
    }

    /**
     * Creates an engine with an explicit region layout policy and every bounded tick slice.
     * <p>
     * Production uses {@link RegionEntityStorageMode#MAIN_THREAD_ONLY}; only the entity tick worker
     * mechanics tests construct an engine with region-owned storage.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean randomTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, randomTickWorkerExecutionEnabled, regionPolicy, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /**
     * Creates an engine with an explicit region layout policy, every bounded tick slice and an entity
     * storage mode.
     * <p>
     * The policy selects the size-3 default fixed layout; production passes the configured
     * {@link RegionLayout} instead, so the engine, its regionizer and the physical section partitions of
     * the levels it ticks share one fixed region size.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean randomTickWorkerExecutionEnabled, RegionPolicy regionPolicy, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, randomTickWorkerExecutionEnabled, RegionLayout.of(regionPolicy), entityStorageMode);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice and an entity storage mode.
     * <p>
     * This is the production constructor: the layout carries both the policy and the fixed region edge
     * length, so nothing downstream has to guess the size.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean randomTickWorkerExecutionEnabled, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this.layout = layout;
        this.regionizer = new ThreadedRegionizer(this.layout);
        this.workerPool = new RegionWorkerPool(workerThreads > 0 ? workerThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        this.workerExecutionEnabled = workerExecutionEnabled;
        this.globalWorkerExecutionEnabled = globalWorkerExecutionEnabled;
        this.entityTickWorkerExecutionEnabled = entityTickWorkerExecutionEnabled;
        this.blockEntityTickWorkerExecutionEnabled = blockEntityTickWorkerExecutionEnabled;
        this.scheduledTickWorkerExecutionEnabled = scheduledTickWorkerExecutionEnabled;
        this.randomTickWorkerExecutionEnabled = randomTickWorkerExecutionEnabled;
        this.entityStorageMode = entityStorageMode;
        RegionOwnershipViolationHandler.reset();
        RegionEntityOwnershipScope.resetCounters();
    }

    /**
     * Creates an engine with an explicit region layout and every bounded tick slice, keeping entity storage
     * on the server thread.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean randomTickWorkerExecutionEnabled, RegionLayout layout) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, randomTickWorkerExecutionEnabled, layout, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    public RegionPolicy regionPolicy() {
        return this.layout.policy();
    }

    /**
     * {@return the layout this engine maps chunks, entities and tasks with}
     * <p>
     * The physical entity section partitions of a level capture this layout when the level is created, so
     * the manager partitions sections with exactly the size this engine ticks with.
     */
    public RegionLayout regionLayout() {
        return this.layout;
    }

    /**
     * {@return the chunk margin around an entity's own chunk that has to be loaded before the entity is
     * submitted to a worker}
     * <p>
     * The margin is the heuristic that keeps the common entity tick off the server chunk source; a tick that
     * still needs a chunk outside it is handed to the server-thread fallback by the chunk source guard.
     */
    public static int entityTickChunkMargin() {
        return ENTITY_TICK_CHUNK_MARGIN;
    }

    @Override
    public GlobalRegionScheduler globalScheduler() {
        return this.globalScheduler;
    }

    @Override
    public RegionScheduler regionScheduler() {
        return this.regionScheduler;
    }

    @Override
    public EntityRegionScheduler entityScheduler() {
        return this.entityScheduler;
    }

    @Override
    public boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos) {
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionCoordinate == null) {
            return false;
        }

        final RegionState region = basicContext.regionState;
        if (region == null || !region.isOwnedByCurrentThread()) {
            return false;
        }
        // A region group holds the lease of its region for its whole body, and the regionizer refuses to
        // merge, split or transfer a running region. The mapping of the group's own coordinate therefore
        // cannot change while the group runs, so the coordinate comparison proves ownership without the
        // regionizer read and the RegionCoordinate allocation that every block-entity ticker of a group
        // would otherwise pay for. Positions outside the group's region keep the authoritative lookup.
        if (region.coordinate().equals(basicContext.regionCoordinate)
                && this.layout.isInRegion(level, pos, basicContext.regionCoordinate)) {
            return true;
        }

        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        return this.regionizer.get(coordinate) == region;
    }

    @Override
    public boolean isOwnedByCurrentRegion(Entity entity) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null && group.owns(entity)) {
            return true;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return this.isOwnedByCurrentRegion(level, entity.blockPosition());
    }

    @Override
    public void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos) {
        if (!this.isOwnedByCurrentRegion(level, pos)) {
            RegionOwnershipViolationHandler.handle("Current thread does not own region at " + pos);
        }
    }

    @Override
    public void assertOwnedByCurrentRegion(Entity entity) {
        if (!this.isOwnedByCurrentRegion(entity)) {
            RegionOwnershipViolationHandler.handle("Current thread does not own entity " + entity);
        }
    }

    @Override
    public RegionThreadingContext currentContext() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * {@return true when {@code thread} belongs to this engine's region worker pool}
     */
    @ApiStatus.Internal
    public boolean isWorkerThread(Thread thread) {
        return this.workerPool.isWorkerThread(thread);
    }

    void enqueueGlobal(Runnable task) {
        this.runAdmitted(() -> this.globalRegion.taskQueue().enqueue(task));
    }

    RegionScheduledTask scheduleGlobal(Runnable task, long delayTicks) {
        return this.callAdmitted(() -> this.globalRegion.taskQueue().schedule(task, delayTicks));
    }

    RegionScheduledTask scheduleGlobalAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        return this.callAdmitted(() -> this.globalRegion.taskQueue().scheduleAtFixedRate(task, initialDelayTicks, periodTicks));
    }

    void enqueueRegion(ServerLevel level, BlockPos pos, Runnable task) {
        this.runAdmitted(() -> {
            if (!this.enqueueRegionTask(level, pos, task)) {
                // The level identity was closed by an unload after the shutdown check; dropping the task
                // is the only safe outcome for work that belongs to an unloaded level.
                LOGGER.debug("Dropped region task for a closed level");
            }
        });
    }

    RegionScheduledTask scheduleRegion(ServerLevel level, BlockPos pos, Runnable task, long delayTicks) {
        return this.callAdmitted(() -> {
            if (delayTicks < 0L) {
                throw new IllegalArgumentException("delayTicks must be >= 0");
            }
            return this.regionizer.schedule(this.layout.regionAt(level, pos), task, delayTicks);
        });
    }

    RegionScheduledTask scheduleRegionAtFixedRate(ServerLevel level, BlockPos pos, Runnable task, long initialDelayTicks, long periodTicks) {
        return this.callAdmitted(() -> {
            if (initialDelayTicks < 0L || periodTicks <= 0L) {
                throw new IllegalArgumentException("initialDelayTicks must be >= 0 and periodTicks must be > 0");
            }
            return this.regionizer.scheduleAtFixedRate(this.layout.regionAt(level, pos), task, initialDelayTicks, periodTicks);
        });
    }

    EntityRegionScheduledTask scheduleEntity(Entity entity, Runnable task, long delayTicks) {
        return this.scheduleEntityTask(entity, task, delayTicks, 0L);
    }

    EntityRegionScheduledTask scheduleEntityAtFixedRate(Entity entity, Runnable task, long initialDelayTicks, long periodTicks) {
        return this.scheduleEntityTask(entity, task, initialDelayTicks, periodTicks);
    }

    private EntityRegionScheduledTask scheduleEntityTask(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        return this.callAdmitted(() -> {
            final EntityRegionScheduledTask scheduledTask = new EntityRegionScheduledTask(this, entity, task, delayTicks, periodTicks);
            this.registerEntityTask(scheduledTask);
            try {
                scheduledTask.start();
            } catch (RuntimeException | Error exception) {
                this.unregisterEntityTask(scheduledTask);
                throw exception;
            }
            return scheduledTask;
        });
    }

    /**
     * Registers a wrapper, so a move notification of its entity reaches it.
     * <p>
     * The group of the entity is created and filled in one compute on the entity key, so a notification that
     * reads the index observes either no group or a complete one, never a group that is still being filled.
     */
    private void registerEntityTask(EntityRegionScheduledTask task) {
        this.entityTasksByEntity.compute(task.entity(), (entity, tasks) -> {
            if (tasks == null) {
                tasks = ConcurrentHashMap.newKeySet();
            }
            tasks.add(task);
            return tasks;
        });
    }

    /**
     * Drains tasks queued to the global region.
     */
    @ApiStatus.Internal
    public void drainGlobalTasks() {
        if (this.globalRegion.isRunning() || this.globalRegion.isWorkerSubmitted()) {
            return;
        }
        final long now = System.nanoTime();
        if (this.globalWorkerExecutionEnabled) {
            this.submitGlobalTick(now);
            return;
        }
        this.globalTickRunner.runIfDue(this.globalRegion, now, this::drainGlobalRegionState);
    }

    private void submitGlobalTick(long nowNanos) {
        if (!this.globalRegion.tryMarkWorkerSubmitted()) {
            return;
        }
        if (!this.workerPool.execute("global region drain", () -> {
            try {
                this.globalTickRunner.runIfDue(this.globalRegion, nowNanos, this::drainGlobalRegionState);
            } finally {
                this.globalRegion.clearWorkerSubmitted();
            }
        })) {
            this.globalRegion.clearWorkerSubmitted();
            this.rejectedWorkerTaskCount.incrementAndGet();
        }
    }

    private void drainGlobalRegionState() {
        if (!this.globalRegion.tryMarkRunning()) {
            return;
        }
        try {
            this.runInGlobalRegionOwned(() -> this.failedQueuedTaskCount.addAndGet(this.globalRegion.taskQueue().drain()));
        } finally {
            this.globalRegion.clearRunning();
        }
    }

    /**
     * Runs {@code task} while the current thread owns the global region context.
     */
    @ApiStatus.Internal
    public void runInGlobalRegion(Runnable task) {
        if (this.globalRegion.isRunning() && this.globalRegion.isOwnedByCurrentThread()) {
            task.run();
            return;
        }
        if (!this.globalRegion.tryMarkRunning()) {
            RegionOwnershipViolationHandler.handle("Global region is already running on another thread");
            return;
        }
        try {
            this.runInGlobalRegionOwned(task);
        } finally {
            this.globalRegion.clearRunning();
        }
    }

    private void runInGlobalRegionOwned(Runnable task) {
        this.globalRegion.beginOwnership();
        try {
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.GLOBAL_REGION, null, null, null, null), task);
        } finally {
            this.globalRegion.endOwnership();
        }
    }

    /**
     * Runs {@code task} while the current thread is executing a server level tick bridge.
     */
    @ApiStatus.Internal
    public void runInLevelTick(ServerLevel level, Runnable task) {
        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.LEVEL, level, null, null, null), task);
    }

    /**
     * Runs {@code task} while the current thread owns the entity's current region context.
     * <p>
     * Inside an entity tick batch the task runs inline only when the entity belongs to the batch group
     * that owns the current region lease. The group's snapshot membership stays authoritative for the
     * duration of the tick, so an entity that moved to another region is still ticked under the region
     * that snapshotted it. Work for an entity outside the group is queued and executed on the server
     * thread after the batch, never inline under a lease that does not own it.
     */
    @ApiStatus.Internal
    public void runInEntityRegion(Entity entity, Runnable task) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            if (group.owns(entity)) {
                task.run();
                return;
            }
            if (!entity.isRemoved()) {
                group.batch().queueServerTask(() -> {
                    if (!entity.isRemoved()) {
                        this.runInEntityRegionOutsideBatch(entity, task);
                    }
                });
                this.deferredEntityTicks.incrementAndGet();
            }
            return;
        }
        if (this.isOwnedByCurrentRegion(entity)) {
            task.run();
            return;
        }
        this.runInEntityRegionOutsideBatch(entity, task);
    }

    private void runInEntityRegionOutsideBatch(Entity entity, Runnable task) {
        if (this.shutdown) {
            // The regionizer is closed; run the tick like vanilla instead of creating a new region.
            task.run();
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            task.run();
            return;
        }

        final RegionCoordinate coordinate = this.layout.regionAt(level, entity.blockPosition());
        final boolean tryLifecycleGateOnly = this.workerPool.isWorkerThread(Thread.currentThread())
                || this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread();
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            // A level cleanup is in progress and this thread may be part of the work it waits for. Running
            // the tick inline like vanilla is the only safe outcome; leasing a region now could race the
            // retirement of the level identity.
            task.run();
            return;
        }
        final RegionState region;
        final boolean inline;
        final boolean retry;
        try {
            if (this.shutdown) {
                inline = true;
                region = null;
                retry = false;
            } else {
                region = this.regionizer.moveEntity(entity, coordinate);
                // The level identity may have been unloaded for region threading; run the tick inline
                // like vanilla instead of creating a region that the unload already excluded.
                inline = region == null;
                retry = !inline && region.isDead();
                if (!inline && !retry && !region.tryMarkRunning()) {
                    RegionOwnershipViolationHandler.handle("Entity region is already running for " + entity);
                    return;
                }
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        if (inline) {
            task.run();
            return;
        }
        if (retry) {
            // The region may have been retired after the task was queued. Re-resolve against the
            // regionizer's authoritative mappings instead of dropping entity work.
            this.regionizer.removeIfEmpty(region);
            this.runInEntityRegionOutsideBatch(entity, task);
            return;
        }

        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), task);
        } finally {
            region.endOwnership();
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
        }
    }

    /**
     * {@return the batch group that owns the current worker task, or {@code null} outside an entity batch}
     */
    @Nullable
    private RegionEntityTickBatch.Group currentEntityTickGroup() {
        return this.currentContext() instanceof BasicRegionThreadingContext basicContext
                ? basicContext.entityTickGroup
                : null;
    }

    /**
     * Starts collecting the entities of the current {@code level} tick that should tick on region workers.
     *
     * @return the plan to fill, or {@code null} when entity ticks have to stay on the server thread
     */
    @Nullable
    public RegionEntityTickPlan beginEntityTickPlan(ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        if (this.entityStorageMode != RegionEntityStorageMode.REGION_OWNED) {
            // A worker entity tick moves, removes and spawns entities, which mutates the shared vanilla
            // section/index storage. Entity storage is not region-owned, so the whole pass stays on the
            // server thread instead of shipping a query or a consumer on a worker.
            if (this.entityTickFallbackNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region entity ticks stay on the server thread until entity storage is region-owned (mode {})", this.entityStorageMode);
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the entities inline like vanilla instead of publishing a
            // batch for region state that no longer exists. The publication re-checks this atomically.
            return null;
        }
        return new RegionEntityTickPlan(this, level, inEntityTickingRange, barrier);
    }

    /**
     * Starts collecting the block-entity tickers of the current {@code level} tick that should tick on
     * region workers.
     *
     * @return the plan to fill, or {@code null} when block-entity ticks have to stay on the server thread
     */
    @Nullable
    public RegionBlockEntityTickPlan beginBlockEntityTickPlan(ServerLevel level, RegionTickBarrier barrier) {
        if (!this.blockEntityTickWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        // Safety is decided per region when the batch is leased: a region only runs on a worker when
        // every ticker in it is declared region-safe by RegionTickCompatibility. Types that query entity
        // storage and consume the result (beacon and similar) are not declared safe and tick on the
        // server thread after the batch instead.
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the block entities inline like vanilla instead of
            // publishing a batch for region state that no longer exists.
            return null;
        }
        return new RegionBlockEntityTickPlan(this, level, barrier);
    }

    /**
     * {@return the dispatcher that executes the scheduled ticks of one phase on region workers, or
     * {@code null} when scheduled ticks have to stay on the server thread}
     * <p>
     * The dispatcher is created before {@link LevelTicks#tick(long, int, java.util.function.BiConsumer,
     * LevelTicks.TickDispatcher)} collects the ticks, so a tick phase that started with the slice enabled
     * stays enabled even if the profiler is activated while the workers run.
     */
    @Nullable
    public <T> LevelTicks.TickDispatcher<T> beginScheduledTickDispatcher(ServerLevel level, ScheduledTickPhase phase, RegionTickBarrier barrier) {
        if (!this.scheduledTickWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        if (!this.unclassifiedWorkerCallbacksForTesting) {
            // No scheduled callback class is admitted as entity-storage safe yet. An arbitrary callback
            // can spawn, remove or move entities and mutate shared level state, so the whole phase runs
            // through the unchanged vanilla inline loop. Returning null keeps the synchronous ordering,
            // return values and cancellation semantics of the caller.
            if (this.scheduledTickFallbackNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region scheduled ticks are configured for workers but fall back to the server thread: no callback class is admitted as entity-storage safe");
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the scheduled ticks inline like vanilla instead of
            // publishing a batch for region state that no longer exists.
            return null;
        }
        return (collected, callback, fallbackCallback) -> this.tickScheduledTicks(level, phase, collected, callback, fallbackCallback, barrier);
    }

    /**
     * Starts collecting the block and fluid random ticks of the current {@code level} tick that should run
     * on region workers.
     *
     * @param barrier waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when random ticks have to stay on the server thread
     */
    @Nullable
    public RegionRandomTickPlan beginRandomTickPlan(ServerLevel level, RegionTickBarrier barrier) {
        if (!this.randomTickWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        if (!this.unclassifiedWorkerCallbacksForTesting) {
            // A random tick callback is arbitrary block/fluid code and can touch entity storage or shared
            // level state, so no callback class is admitted yet. The vanilla inline path runs instead and
            // keeps the synchronous result of the callback.
            if (this.randomTickFallbackNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region random ticks are configured for workers but fall back to the server thread: no callback class is admitted as entity-storage safe");
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the random ticks inline like vanilla instead of
            // publishing a batch for region state that no longer exists.
            return null;
        }
        return new RegionRandomTickPlan(this, level, barrier);
    }

    /**
     * {@return true when every chunk around {@code chunkPos} belongs to the same region}
     * <p>
     * Work that writes into the chunks around its own chunk may only run on a worker when all eight
     * neighboring chunks belong to the same region, so every write within one chunk of its position stays
     * inside the region lease the worker holds. Boundary chunks stay on the server thread. Writes farther
     * than one chunk away and arbitrary modded cross-region writes are outside this bound and are not
     * claimed to be safe.
     */
    boolean isInteriorRegionChunk(ChunkPos chunkPos) {
        final int regionX = this.layout.regionX(chunkPos.x);
        final int regionZ = this.layout.regionZ(chunkPos.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (this.layout.regionX(chunkPos.x + dx) != regionX || this.layout.regionZ(chunkPos.z + dz) != regionZ) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@return the random source of the scheduled tick group executing on the current thread, or
     * {@code null} outside a worker group}
     */
    @Nullable
    public RandomSource scheduledTickRandom() {
        return this.currentScheduledTickRandom.get();
    }

    /**
     * {@return the server-owned mutation queue of the region tick group executing on the current thread,
     * or {@code null} outside a worker group}
     * <p>
     * Both {@code LevelTicks} instances of a level share this queue, so a block worker that schedules a
     * fluid tick and a fluid worker that schedules a block tick never mutate the other scheduler
     * concurrently. Scheduled tick and random tick groups use the same queue, so the two slices never
     * mutate the scheduling structures concurrently either. The publishing server thread drains it after
     * all worker groups of the batch finished.
     */
    @Nullable
    public Queue<Runnable> regionTickMutationQueue() {
        return this.currentRegionTickMutations.get();
    }

    /**
     * {@return true when the level tick of this engine ticks entities on region workers}
     * <p>
     * This is the configured request, not the effective dispatch: without region-owned entity storage
     * {@link #beginEntityTickPlan} returns {@code null} and the vanilla inline entity tick runs instead.
     */
    @ApiStatus.Internal
    public boolean isEntityTickWorkerExecutionEnabled() {
        return this.entityTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when scheduled block and fluid ticks execute on region workers}
     * <p>
     * This is the configured request, not the effective dispatch. Without an audited callback admission
     * {@link #beginScheduledTickDispatcher} returns {@code null} and the vanilla inline loop runs instead.
     */
    @ApiStatus.Internal
    public boolean isScheduledTickWorkerExecutionEnabled() {
        return this.scheduledTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when the selected block and fluid random ticks execute on region workers}
     * <p>
     * This is the configured request, not the effective dispatch. Without an audited callback admission
     * {@link #beginRandomTickPlan} returns {@code null} and the vanilla inline loop runs instead.
     */
    @ApiStatus.Internal
    public boolean isRandomTickWorkerExecutionEnabled() {
        return this.randomTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when the configured scheduled tick worker slice actually dispatches callbacks}
     */
    @ApiStatus.Internal
    public boolean isScheduledTickWorkerDispatchEffective() {
        return this.scheduledTickWorkerExecutionEnabled && this.unclassifiedWorkerCallbacksForTesting;
    }

    /**
     * {@return true when the configured random tick worker slice actually dispatches callbacks}
     */
    @ApiStatus.Internal
    public boolean isRandomTickWorkerDispatchEffective() {
        return this.randomTickWorkerExecutionEnabled && this.unclassifiedWorkerCallbacksForTesting;
    }

    /**
     * Enables the unclassified scheduled/random callback mechanics for internal tests only.
     * <p>
     * Production never calls this: an arbitrary scheduled or random callback can spawn, remove or move
     * entities, so it is not admitted to a worker before it starts. The seam exists so the existing
     * batch, random and mutation-queue mechanics keep their coverage.
     */
    @ApiStatus.Internal
    public void enableUnclassifiedWorkerCallbacksForTesting() {
        this.unclassifiedWorkerCallbacksForTesting = true;
    }

    /**
     * Enables the legacy entity query handoff transport for internal tests only.
     * <p>
     * Production rejects legacy entity queries from native workers because a completed handoff still
     * returns live entity references and runs caller predicates/consumers. The seam keeps the handoff
     * transport tests separate from that production policy.
     */
    @ApiStatus.Internal
    public void enableLegacyEntityQueryHandoffForTesting() {
        this.legacyEntityQueryHandoffForTesting = true;
    }

    /**
     * {@return true when the legacy entity query handoff is enabled for the current engine}
     */
    @ApiStatus.Internal
    public boolean isLegacyEntityQueryHandoffEnabled() {
        return this.legacyEntityQueryHandoffForTesting;
    }

    /**
     * Installs the diagnostics-only observer of worker entity tick failures, or removes it with
     * {@code null}.
     * <p>
     * Only the entity worker diagnostics benchmark calls this. Every failing tick is reported to the
     * observer and the group continues with its next entity, so one run collects every blocked operation
     * instead of only the first one. A tick that fails under an observer also skips the vanilla crash-report
     * wrapper, which would build a crash report - and with it a system hardware enumeration - per rejection.
     * Without an observer the worker body has no catch: the first failure reaches {@code guardEntityTick}
     * (which turns it into a {@code ReportedException}), fails the group, and
     * {@code RegionTickBatch#throwIfFailed()} rethrows it on the server thread.
     */
    @ApiStatus.Internal
    public void setEntityTickFailureObserverForTesting(@Nullable BiConsumer<Entity, Throwable> observer) {
        this.entityTickFailureObserverForTesting = observer;
    }

    /**
     * {@return the random source of the random tick group executing on the current thread, or
     * {@code null} outside a random tick worker group}
     */
    @Nullable
    public RandomSource randomTickRandom() {
        return this.currentRandomTickRandom.get();
    }

    /**
     * {@return the random source of the region worker group executing on the current thread, or
     * {@code null} outside a worker group}
     * <p>
     * Entity, scheduled and random tick groups each own a random source while they run. The level random
     * wrapper uses this to redirect direct {@code level.random} reads of a worker to the random source of
     * its group instead of the shared vanilla source, which no two workers ever share because every group
     * owns its own source.
     */
    @Nullable
    public RandomSource currentGroupRandom() {
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        if (scheduled != null) {
            return scheduled;
        }
        final RandomSource random = this.currentRandomTickRandom.get();
        if (random != null) {
            return random;
        }
        return this.currentEntityTickRandom.get();
    }

    /**
     * {@return the random source of the block tick group executing on the current thread, or {@code null}
     * outside a scheduled or random tick worker group}
     * <p>
     * This is the narrower question the block-entity access gate asks, because only a scheduled or random
     * tick group opens that gate for the block entities of its region. An entity tick group owns a random
     * source as well, but that one is resolved through {@link #currentGroupRandom()} and does not grant
     * block-entity access.
     */
    @Nullable
    public RandomSource currentRegionTickRandom() {
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        return scheduled != null ? scheduled : this.currentRandomTickRandom.get();
    }

    /**
     * {@return true when the current thread is a region worker that executes a region tick batch whose
     * server-thread wait can service a handoff}
     */
    @ApiStatus.Internal
    public boolean isRegionTickBatchWorkerThread() {
        return RegionTickBatch.current() != null && this.workerPool.isWorkerThread(Thread.currentThread());
    }

    /**
     * Runs a complete computation on the server thread while the current region tick worker waits for it.
     * <p>
     * The computation must encapsulate everything that touches unregionized shared state, including the
     * consumer of any entity query. The server thread is inside the batch barrier while it waits, so the
     * handoff is serviced during the wait; the worker receives the exact result or exception of the
     * computation and never continues against the shared state itself. Outside a region tick batch (the
     * server thread, a region drain or the global worker) the computation runs inline unchanged.
     * <p>
     * A level that closed before the handoff ran fails the computation instead of running a callback after
     * the level was unloaded.
     */
    @ApiStatus.Internal
    public <T> T runOnServerThread(ServerLevel level, Supplier<T> computation) {
        final RegionTickBatch batch = RegionTickBatch.current();
        if (batch == null) {
            return computation.get();
        }
        return batch.runOnServerThread(level, () -> {
            if (this.regionizer.isLevelClosed(level)) {
                throw new IllegalStateException("Level closed before region main-thread work ran");
            }
            return computation.get();
        });
    }

    /**
     * {@return the region that owns {@code pos} under this engine's layout}
     */
    RegionCoordinate regionAt(ServerLevel level, BlockPos pos) {
        return this.layout.regionAt(level, pos);
    }

    /**
     * {@return the region currently owning {@code entity}, recording the ownership in the regionizer}
     */
    @Nullable
    RegionState regionOf(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return this.regionizer.moveEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
    }

    /**
     * Publishes {@code batch} as active, or rejects it when its level identity is already closed or the
     * engine was shut down.
     * <p>
     * The closed-level check, the shutdown check and the publication happen while the lifecycle read gate
     * is held, so they are atomic with {@link #clearLevelRegionTasks(ServerLevel)} and {@link #shutdown()}:
     * a cleanup either observes the published batch and waits for it, or the publication observes the
     * closed identity and rejects the batch. The read gate is released before any region lease is acquired,
     * because cleanup holds the write gate while it awaits published batches. A published batch therefore
     * has to finish leasing, tick its workers and run {@code removeIfEmpty} without the read gate; taking it
     * again would deadlock against the cleanup that waits for that same batch.
     *
     * @return the publication result; only {@link TickBatchPublication#PUBLISHED} means the caller owns the
     *         batch lifecycle from this point on
     */
    private TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level) {
        final Lock readLock = this.lifecycleGate.readLock();
        readLock.lock();
        try {
            if (this.regionizer.isLevelClosed(level)) {
                return TickBatchPublication.LEVEL_CLOSED;
            }
            if (this.shutdown) {
                return TickBatchPublication.SHUTDOWN;
            }
            this.activeTickBatches.add(batch);
            return TickBatchPublication.PUBLISHED;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Outcome of {@link #publishTickBatch(RegionTickBatch, ServerLevel)}.
     */
    private enum TickBatchPublication {
        PUBLISHED,
        LEVEL_CLOSED,
        SHUTDOWN
    }

    /**
     * Ticks {@code accepted} entities on their owning region workers and waits for the batch.
     * <p>
     * Runs on the server thread: while the workers tick, the server thread only services chunk source
     * tasks, so off thread chunk requests issued by entity code cannot deadlock against the barrier.
     * <p>
     * The batch is published under the lifecycle read gate before any region lease is acquired, so a
     * concurrent shutdown or level unload observes it and waits instead of retiring regions that the batch
     * still holds or is about to lease. The gate is released before leasing: the cleanup waits for the
     * published batch, so the batch must be able to acquire leases and retire its regions without taking
     * the gate again. Region ownership is re-resolved from the regionizer after the snapshot, so a region
     * that merged or split between collection and lease acquisition is replaced by its authoritative
     * successor instead of dropping the entity. An entity whose region cannot be leased safely is ticked on
     * the server thread after the batch rather than being dropped.
     */
    void tickEntities(ServerLevel level, List<Entity> accepted, RegionTickBarrier barrier) {
        if (accepted.isEmpty()) {
            return;
        }

        final RegionEntityTickBatch batch = new RegionEntityTickBatch(level);
        final List<Entity> pending = new ArrayList<>(accepted.size());
        for (final Entity entity : accepted) {
            if (entity.isRemoved() || entity.level() != level) {
                // The entity or its level went away between the snapshot and the batch; the vanilla
                // inline path would have skipped it as well.
                this.skippedEntityTicks.incrementAndGet();
                continue;
            }
            pending.add(entity);
        }
        if (pending.isEmpty()) {
            return;
        }

        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was accepted. Its region state is retired
            // and no batch may be published for it: the accepted entities must not be ticked or
            // handed to the vanilla inline fallback after their level unloaded.
            this.skippedEntityTicks.addAndGet(pending.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the accepted entities still
            // have to tick, but no batch may be published after shutdown started.
            for (final Entity entity : pending) {
                this.tickEntityInline(level, entity);
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (deferred work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        // The opt-in attribution is decided once per batch, never per entity: a disabled attribution only
        // costs this one volatile read and takes no timestamp.
        final boolean attribution = RegionEntityTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseEntityTickGroups(batch, pending, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                batch.submitAndAwait(this.workerPool, barrier);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            // The deferred work after the barrier is measured as its own segment, so the fallback ticks and
            // the section publication are reported instead of being folded into the barrier wait.
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                // The section publication and the target chunk acquisition of a worker entity move are the
                // vanilla effects of the move itself, so they run before any other server-thread work observes
                // the entity and before the ownership handoff of the batch commits a new region.
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntitySectionUpdates(batch));
                // The removal of an owned entity is the other vanilla storage effect a worker may defer. It
                // runs after the section updates, so the removal removes the entity from the section it was
                // published in, and before the ownership publication, so the handoff observes the removed
                // entity and publishes its departure instead of a region mapping.
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntityRemovals(batch));
                failure = runBatchCompletion(failure, () -> this.tickFallbackEntities(batch));
                failure = runBatchCompletion(failure, batch::runDeferredServerTasks);
                // The ownership publication is timed on its own inside the handoff, so the cost of the
                // deferred ownership handoff is separable from the fallback entity ticks that share the
                // handoff segment.
                final long publicationStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = runBatchCompletion(failure, () -> this.applyDeferredEntityUpdates(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordDeferredPublication(System.nanoTime() - publicationStart);
                    }
                }
                batch.finish();
                this.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups {@code pending} by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt, because
     * a merge or split re-points the tracked entities to the surviving region. Anything that cannot be
     * resolved to a live, leasable region is recorded as a server-thread fallback, and so is every entity
     * whose own chunk neighborhood is not loaded enough to tick on a worker.
     */
    private void leaseEntityTickGroups(RegionEntityTickBatch batch, List<Entity> pending, RegionTickBarrier barrier) {
        final List<Entity> unloadedNeighborhood = new ArrayList<>();
        final List<Entity> tickable = new ArrayList<>(pending.size());
        this.splitPreloadRefusedEntities(batch.level(), pending, tickable, unloadedNeighborhood);
        List<Entity> remaining = tickable;
        int batchedEntities = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, Entity> grouping = new RegionTickGrouping<>();
            final List<Entity> retry = new ArrayList<>();
            for (final Entity entity : remaining) {
                final RegionState region = this.regionizer.regionFor(entity);
                if (region == null || region.isDead()) {
                    retry.add(entity);
                } else {
                    grouping.add(region, entity);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<Entity> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be
                    // acquired again and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addEntities(grouping.items(region)));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(grouping.items(region));
                    continue;
                }
                leasedAny = true;
                batchedEntities += grouping.size(region);
                this.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region), group -> this.entityTickGroup(group)));
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final Entity entity : unloadedNeighborhood) {
            batch.addFallback(entity);
        }
        for (final Entity entity : remaining) {
            batch.addFallback(entity);
            this.fallbackEntityTicks.incrementAndGet();
        }
        if (batchedEntities > 0 && this.entityTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region entity ticks are now executing on region workers: {} entities", batchedEntities);
        }
    }

    /**
     * Splits {@code pending} into the entities whose own chunk neighborhood is loaded enough to tick on a
     * worker and the ones that have to tick on the server thread.
     * <p>
     * Runs on the server thread before any group is submitted. A chunk counts as loaded when the chunk
     * source can hand out its {@code FULL} chunk without loading one, which is exactly what the worker-side
     * immediate lookup of {@code ServerChunkCache.getChunk} answers. Nothing is force-loaded. The check is
     * per entity, so an entity at the edge of the loaded area falls back alone while the rest of its region
     * stays on the workers; a whole region was refused only because one of its chunks was missing, which is
     * what made the edge entities the dominant server-thread cost of the handoff.
     * <p>
     * The neighborhood of a chunk is a pure function of its coordinate, so it is computed once per chunk and
     * shared by every entity standing in it, and the answer of the chunk source for one chunk is shared by
     * every neighborhood that contains it. Both memos are batch local, so the server thread asks the chunk
     * source once per distinct chunk of the batch instead of once per entity. The split happens once per
     * batch: no entity moves before its lease (the server thread only awaits region work in between), a chunk
     * that was missing is only ever loaded later, and a refused entity therefore never reaches a worker in
     * this tick.
     *
     * @param level    the level whose chunk source answers the check
     * @param pending  the entities of the batch, in snapshot order
     * @param tickable receives the entities whose neighborhood is loaded
     * @param refused  receives the entities that fall back to the server thread
     */
    private void splitPreloadRefusedEntities(ServerLevel level, List<Entity> pending, List<Entity> tickable, List<Entity> refused) {
        final Long2ByteMap chunkLoaded = new Long2ByteOpenHashMap();
        chunkLoaded.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
        final Long2ByteMap neighborhoodLoaded = new Long2ByteOpenHashMap();
        neighborhoodLoaded.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
        for (final Entity entity : pending) {
            // The cached block position of the entity: it is the chunk the entity's tick starts from and
            // reading it allocates nothing, unlike deriving a chunk position per entity.
            final BlockPos pos = entity.blockPosition();
            final int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            final int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            if (this.isEntityTickNeighborhoodLoaded(level, chunkX, chunkZ, chunkLoaded, neighborhoodLoaded)) {
                tickable.add(entity);
            } else {
                refused.add(entity);
                this.chunkPreloadRefusedEntityTicks.incrementAndGet();
            }
        }
    }

    /**
     * {@return true when the {@link #ENTITY_TICK_CHUNK_MARGIN} neighborhood of the chunk at
     * {@code chunkX, chunkZ} is loaded}
     * <p>
     * {@code neighborhoodLoaded} memoizes the answer per chunk of the batch (absent, not loaded, loaded), so
     * the entities of one chunk ask once instead of once per entity, and {@code chunkLoaded} memoizes the
     * chunk source answer per chunk, so a chunk shared by the neighborhoods of two adjacent chunks is looked
     * up once. Nothing is force-loaded: a chunk that the chunk source does not already hold makes its
     * neighborhood unloaded.
     */
    private boolean isEntityTickNeighborhoodLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded, Long2ByteMap neighborhoodLoaded) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final byte cached = neighborhoodLoaded.get(key);
        if (cached != NEIGHBORHOOD_UNKNOWN) {
            return cached == NEIGHBORHOOD_LOADED;
        }
        boolean loaded = true;
        for (int offsetX = -ENTITY_TICK_CHUNK_MARGIN; offsetX <= ENTITY_TICK_CHUNK_MARGIN && loaded; offsetX++) {
            for (int offsetZ = -ENTITY_TICK_CHUNK_MARGIN; offsetZ <= ENTITY_TICK_CHUNK_MARGIN; offsetZ++) {
                if (!isChunkLoaded(level, chunkX + offsetX, chunkZ + offsetZ, chunkLoaded)) {
                    loaded = false;
                    break;
                }
            }
        }
        neighborhoodLoaded.put(key, loaded ? NEIGHBORHOOD_LOADED : NEIGHBORHOOD_NOT_LOADED);
        return loaded;
    }

    /**
     * {@return true when the chunk source can hand out the {@code FULL} chunk at {@code chunkX, chunkZ}
     * without loading one}
     * <p>
     * {@code chunkLoaded} memoizes the answer per chunk of the batch.
     */
    private static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final byte cached = chunkLoaded.get(key);
        if (cached != NEIGHBORHOOD_UNKNOWN) {
            return cached == NEIGHBORHOOD_LOADED;
        }
        final boolean loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        chunkLoaded.put(key, loaded ? NEIGHBORHOOD_LOADED : NEIGHBORHOOD_NOT_LOADED);
        return loaded;
    }

    /**
     * Takes the region lease for a whole entity batch.
     * <p>
     * When a worker still leases the region for queued region work, the server thread waits for that work
     * while servicing chunk source tasks, so queued region tasks can never interleave with an entity batch
     * of the same region. The wait has no timeout: letting the server thread continue would let it mutate
     * state that the leasing worker is still touching. The wait always ends in a lease or in the region
     * becoming dead; a dead region is re-resolved by the caller, never waited on again.
     */
    private boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier) {
        if (region.isDead()) {
            return false;
        }
        if (region.tryMarkRunning()) {
            return true;
        }
        final AtomicBoolean acquired = new AtomicBoolean();
        barrier.await(() -> {
            if (region.isDead()) {
                return true;
            }
            acquired.set(region.tryMarkRunning());
            return acquired.get();
        });
        return acquired.get();
    }

    /**
     * Registers the worker group of a freshly leased region and releases the lease again when the group
     * cannot be built.
     * <p>
     * Group creation runs batch code (for example the creation of the per-group random source), so a failure
     * between {@link #acquireRegionLease} and {@link RegionTickBatch#addTask} must not leave the region
     * {@code TICKING}: shutdown and level unload would wait for a lease no worker owns anymore.
     */
    private void addLeasedGroup(RegionState region, Runnable registration) {
        try {
            registration.run();
        } catch (Throwable throwable) {
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
            throw throwable;
        }
    }

    private Runnable entityTickGroup(RegionEntityTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final List<Entity> entities = group.entities();
        final ServerLevel level = group.level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                // The group random is installed once for the whole worker tick loop, so every direct
                // level.random read of an entity tick resolves this group's source through the level
                // random wrapper instead of the shared vanilla one. The loop body itself writes no
                // thread-local.
                this.currentEntityTickRandom.set(random);
                try {
                    // The entity ownership lease covers exactly this worker tick loop: it is opened after the
                    // region lease and closed before it, so a worker task outside the loop never sees it.
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    final BiConsumer<Entity, Throwable> failureObserver = this.entityTickFailureObserverForTesting;
                    try {
                        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, group), () -> {
                            for (final Entity entity : entities) {
                                if (failureObserver == null) {
                                    try {
                                        if (tickOwnedEntity(level, entity)) {
                                            ++ticked[0];
                                        }
                                    } catch (Throwable throwable) {
                                        if (!handOffRefusedChunk(group, entity, throwable)) {
                                            throw throwable;
                                        }
                                    }
                                    continue;
                                }
                                try {
                                    if (tickOwnedEntityForDiagnostics(level, entity)) {
                                        ++ticked[0];
                                    }
                                } catch (Throwable throwable) {
                                    if (handOffRefusedChunk(group, entity, throwable)) {
                                        continue;
                                    }
                                    // Diagnostics only: the observer records the rejected operation and the group
                                    // keeps ticking its remaining entities instead of aborting the batch.
                                    failureObserver.accept(entity, throwable);
                                }
                            }
                        });
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.currentEntityTickRandom.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("entity batch " + coordinate, duration);
                this.entityTicksOnWorkers.addAndGet(ticked[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Ticks one entity of a worker group: the vanilla despawn check followed by the non-passenger tick.
     *
     * @return {@code true} when {@code level.tickNonPassenger} ran for {@code entity}
     */
    private static boolean tickOwnedEntity(ServerLevel level, Entity entity) {
        if (!prepareOwnedEntityTick(entity)) {
            return false;
        }
        level.guardEntityTick(level::tickNonPassenger, entity);
        return true;
    }

    /**
     * Diagnostics counterpart of {@link #tickOwnedEntity(ServerLevel, Entity)}: the same despawn check and
     * non-passenger tick, but without the vanilla crash-report wrapper.
     * <p>
     * Building a crash report enumerates the system hardware through external processes, which costs tens of
     * milliseconds per rejected tick and would dominate the diagnostic window; the failure itself reaches the
     * observer unchanged.
     *
     * @return {@code true} when {@code level.tickNonPassenger} ran for {@code entity}
     */
    private static boolean tickOwnedEntityForDiagnostics(ServerLevel level, Entity entity) {
        if (!prepareOwnedEntityTick(entity)) {
            return false;
        }
        level.tickNonPassenger(entity);
        return true;
    }

    /**
     * Hands {@code entity} to the server-thread fallback when {@code throwable} is a chunk request the
     * chunk source refused to a native region worker.
     * <p>
     * The entity tick group does not abort on the refusal: the entity is recorded as a chunk fallback and
     * ticked on the server thread after the batch, exactly once, instead of blocking the worker on the chunk
     * source. Every other failure keeps the production path, which aborts the group and the level tick. The
     * worker attempt that was abandoned here is not rolled back; the fallback tick is the tick the entity
     * completes, and the guard fires at the first block read that leaves the pre-loaded margin.
     *
     * @return {@code true} when the entity was handed off, {@code false} when the caller has to keep the
     *         failure
     */
    private boolean handOffRefusedChunk(RegionEntityTickBatch.Group group, Entity entity, Throwable throwable) {
        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
            return false;
        }
        group.batch().addChunkFallback(entity);
        this.chunkFallbackEntityTicks.incrementAndGet();
        return true;
    }

    /**
     * Runs the vanilla preconditions of one worker entity tick.
     *
     * @return {@code true} when the entity still has to be ticked
     */
    private static boolean prepareOwnedEntityTick(Entity entity) {
        if (entity.isRemoved()) {
            return false;
        }
        entity.checkDespawn();
        return !entity.isRemoved();
    }

    /**
     * Ticks the entities whose region could not be leased safely, and the entities a worker handed back
     * after a refused chunk request, on the server thread after the batch.
     */
    private void tickFallbackEntities(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final Entity entity : batch.fallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
        for (final Entity entity : batch.chunkFallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
    }

    private void tickEntityInline(ServerLevel level, Entity entity) {
        if (entity.isRemoved() || entity.level() != level) {
            return;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // A fallback tick is only reached for an accepted snapshot; once the level unloaded, the
            // entity belongs to a retired identity and must not tick on the server thread either.
            this.skippedEntityTicks.incrementAndGet();
            return;
        }
        entity.checkDespawn();
        if (entity.isRemoved()) {
            return;
        }
        level.guardEntityTick(level::tickNonPassenger, entity);
    }

    /**
     * Ticks {@code tickers} on their owning region workers and waits for the batch.
     * <p>
     * Runs on the server thread: while the workers tick, the server thread only services chunk source
     * tasks, so off thread chunk requests issued by block-entity code cannot deadlock against the barrier.
     * <p>
     * The batch is published under the lifecycle read gate before any region lease is acquired, so a
     * concurrent shutdown or level unload observes it and waits instead of retiring regions that the batch
     * still holds or is about to lease. The gate is released before leasing: the cleanup waits for the
     * published batch, so the batch must be able to acquire leases and retire its regions without taking
     * the gate again. Region ownership is re-resolved from the regionizer after the snapshot, so a region
     * that merged or split between collection and lease acquisition is replaced by its authoritative
     * successor instead of dropping the ticker. Tickers whose region cannot be leased safely are ticked on
     * the server thread after the batch rather than being dropped.
     */
    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickAdmission> tickers, RegionTickBarrier barrier) {
        if (tickers.isEmpty()) {
            return;
        }

        final RegionBlockEntityTickBatch batch = new RegionBlockEntityTickBatch(level);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was collected. Its region state is retired
            // and no batch may be published for it: the collected tickers must not tick for a level
            // that already unloaded.
            this.skippedBlockEntityTicks.addAndGet(tickers.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the collected tickers still
            // have to tick, but no batch may be published after shutdown started.
            for (final RegionBlockEntityTickAdmission ticker : tickers) {
                this.tickBlockEntityInline(level, ticker.ticker());
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (fallback work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        // The opt-in attribution is decided once per batch, never per ticker: a disabled attribution only
        // costs this one volatile read and takes no timestamp.
        final boolean attribution = RegionBlockEntityTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseBlockEntityTickGroups(batch, tickers, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                batch.submitAndAwait(this.workerPool, barrier);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = runBatchCompletion(failure, () -> this.tickFallbackBlockEntities(batch));
                batch.finish();
                this.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups {@code pending} tickers by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt.
     * Anything that cannot be resolved to a live, leasable region is recorded as a server-thread fallback.
     */
    private void leaseBlockEntityTickGroups(RegionBlockEntityTickBatch batch, List<RegionBlockEntityTickAdmission> pending, RegionTickBarrier barrier) {
        List<RegionBlockEntityTickAdmission> remaining = pending;
        int batchedTickers = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionBlockEntityTickAdmission> grouping = new RegionTickGrouping<>();
            final List<RegionBlockEntityTickAdmission> retry = new ArrayList<>();
            for (final RegionBlockEntityTickAdmission ticker : remaining) {
                final RegionState region = this.regionizer.getOrCreate(this.layout.regionAt(batch.level(), ticker.position()));
                if (region == null || region.isDead()) {
                    retry.add(ticker);
                } else {
                    grouping.add(region, ticker);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionBlockEntityTickAdmission> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                final List<RegionBlockEntityTickAdmission> regionTickers = grouping.items(region);
                if (!this.isRegionSafeBlockEntityGroup(regionTickers)) {
                    // Conservative whole-region fallback: a region that contains at least one ticker whose
                    // compatibility declaration does not prove region-local, storage-free work keeps all
                    // of its tickers on the server thread after the batch. The order inside the region is
                    // preserved and no query consumer ever runs on a worker.
                    for (final RegionBlockEntityTickAdmission ticker : regionTickers) {
                        batch.addFallback(ticker);
                        this.fallbackBlockEntityTicks.incrementAndGet();
                    }
                    continue;
                }
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be
                    // acquired again and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addTickers(regionTickers));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(regionTickers);
                    continue;
                }
                leasedAny = true;
                batchedTickers += regionTickers.size();
                this.addLeasedGroup(region, () -> batch.addGroup(region, regionTickers, group -> this.blockEntityTickGroup(group)));
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final RegionBlockEntityTickAdmission ticker : remaining) {
            batch.addFallback(ticker);
            this.fallbackBlockEntityTicks.incrementAndGet();
        }
        if (batchedTickers > 0 && this.blockEntityTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region block entity ticks are now executing on region workers: {} block entities", batchedTickers);
        }
    }

    private boolean isRegionSafeBlockEntityGroup(List<RegionBlockEntityTickAdmission> tickers) {
        for (final RegionBlockEntityTickAdmission ticker : tickers) {
            final String type = ticker.type();
            if (!RegionTickCompatibility.isRegionSafeBlockEntityType(type)) {
                return false;
            }
            if (RegionTickCompatibility.requiresInteriorRegion(type) && !this.isInteriorRegionChunk(new ChunkPos(ticker.position()))) {
                return false;
            }
        }
        return true;
    }

    private Runnable blockEntityTickGroup(RegionBlockEntityTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final List<RegionBlockEntityTickAdmission> tickers = group.tickers();
        final ServerLevel level = group.batch().level();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                    for (final RegionBlockEntityTickAdmission admission : tickers) {
                        final TickingBlockEntity ticker = admission.ticker();
                        if (ticker.isRemoved()) {
                            continue;
                        }
                        // Validate before the body: stale admissions are deferred exactly once, never
                        // recovered by throwing after a ticker could already have produced side effects.
                        if (admission.level() != level || !admission.isCurrent() || !region.isOwnedByCurrentThread()) {
                            group.batch().addFallback(admission);
                            this.fallbackBlockEntityTicks.incrementAndGet();
                            continue;
                        }
                        if (RegionWorkerScope.isNativeWorkerThread()) {
                            try (RegionBlockEntityTickScope scope = RegionBlockEntityTickScope.open(admission, region)) {
                                ticker.tick();
                            } catch (Throwable throwable) {
                                // A chunk request the chunk source refused is a handoff, not a failure: the
                                // ticker is ticked on the server thread after the batch instead of aborting
                                // the group.
                                if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                                    throw throwable;
                                }
                                group.batch().addFallback(admission);
                                this.fallbackBlockEntityTicks.incrementAndGet();
                                continue;
                            }
                        } else {
                            // Executor rejection runs on the server thread and keeps the real load check.
                            this.tickBlockEntityInline(level, ticker);
                        }
                        ++ticked[0];
                    }
                });
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("block entity batch " + coordinate, duration);
                this.blockEntityTicksOnWorkers.addAndGet(ticked[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Ticks the block-entity tickers whose region could not be leased safely on the server thread after
     * the batch.
     */
    private void tickFallbackBlockEntities(RegionBlockEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final RegionBlockEntityTickAdmission ticker : batch.fallbackTickers()) {
            this.tickBlockEntityInline(level, ticker.ticker());
        }
    }

    private void tickBlockEntityInline(ServerLevel level, TickingBlockEntity ticker) {
        if (ticker.isRemoved()) {
            return;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // A fallback tick is only reached for a collected snapshot; once the level unloaded, the
            // ticker belongs to a retired identity and must not tick on the server thread either.
            this.skippedBlockEntityTicks.incrementAndGet();
            return;
        }
        if (level.areEntitiesLoaded(ChunkPos.asLong(ticker.getPos()))) {
            ticker.tick();
        }
    }

    /**
     * Executes the scheduled ticks of one phase that {@code LevelTicks} selected for this tick.
     * <p>
     * The collection, budget, de-duplication and per-tick bookkeeping already ran on the server thread;
     * this method only distributes the callbacks. The batch is published under the lifecycle read gate
     * before any region lease is acquired, so a concurrent shutdown or level unload observes it and waits
     * instead of retiring regions that the batch still holds or is about to lease. Region ownership is
     * re-resolved after the snapshot, so a region that merged or split between collection and lease
     * acquisition is replaced by its authoritative successor instead of dropping the tick. Ticks whose
     * region cannot be leased safely run on the server thread after the batch rather than being dropped.
     * <p>
     * The server thread waits for the whole phase before it applies the mutations the callbacks
     * requested, so a worker never mutates the scheduling structures and a tick scheduled by a callback
     * is never executed early by this batch. Ticks whose region could not be leased run through
     * {@code fallbackCallback} directly on the server thread after the workers finished; the callback
     * must not hand them to a region queue because {@code LevelTicks} already removed them.
     */
    <T> void tickScheduledTicks(ServerLevel level, ScheduledTickPhase phase, List<ScheduledTick<T>> ticks, BiConsumer<BlockPos, T> callback,
            BiConsumer<BlockPos, T> fallbackCallback, RegionTickBarrier barrier) {
        if (ticks.isEmpty()) {
            return;
        }

        final RegionScheduledTickBatch<T> batch = new RegionScheduledTickBatch<>(level, phase);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was collected. Its region state is retired and
            // no batch may be published for it: the collected ticks must not tick for a level that already
            // unloaded.
            this.skippedScheduledTicks.addAndGet(ticks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the collected ticks still have
            // to tick, but no batch may be published after shutdown started.
            for (final ScheduledTick<T> tick : ticks) {
                fallbackCallback.accept(tick.pos(), tick.type());
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (fallback work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        Throwable failure = null;
        try {
            try {
                this.leaseScheduledTickGroups(batch, ticks, callback, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                try {
                    batch.submitAndAwait(this.workerPool, barrier);
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    }
                }
            }
        } finally {
            failure = runBatchCompletion(failure, () -> this.tickFallbackScheduledTicks(batch, fallbackCallback));
            failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.scheduledTickMutations()));
            batch.finish();
            this.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups {@code pending} scheduled ticks by their authoritative region and takes the lease of every
     * group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt.
     * Anything that cannot be resolved to a live, leasable region is recorded as a server-thread fallback.
     */
    private <T> void leaseScheduledTickGroups(RegionScheduledTickBatch<T> batch, List<ScheduledTick<T>> pending, BiConsumer<BlockPos, T> callback, RegionTickBarrier barrier) {
        List<ScheduledTick<T>> remaining = pending;
        int batchedTicks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, ScheduledTick<T>> grouping = new RegionTickGrouping<>();
            final List<ScheduledTick<T>> retry = new ArrayList<>();
            for (final ScheduledTick<T> tick : remaining) {
                final RegionState region = this.regionizer.getOrCreate(this.layout.regionAt(batch.level(), tick.pos()));
                if (region == null || region.isDead()) {
                    retry.add(tick);
                } else {
                    grouping.add(region, tick);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<ScheduledTick<T>> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be acquired
                    // again and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addTicks(grouping.items(region)));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(grouping.items(region));
                    continue;
                }
                leasedAny = true;
                batchedTicks += grouping.size(region);
                this.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region), batch.nextRandomSeed(), group -> this.scheduledTickGroup(group, callback)));
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        if (!remaining.isEmpty()) {
            batch.addFallback(remaining);
        }
        if (batchedTicks > 0 && this.scheduledTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region scheduled {} ticks are now executing on region workers: {} ticks", batch.phase().description(), batchedTicks);
        }
    }

    private <T> Runnable scheduledTickGroup(RegionScheduledTickBatch.Group<T> group, BiConsumer<BlockPos, T> callback) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.level();
        final List<ScheduledTick<T>> ticks = group.ticks();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                this.currentScheduledTickRandom.set(random);
                this.currentRegionTickMutations.set(group.batch().scheduledTickMutations());
                try {
                    this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                        for (final ScheduledTick<T> tick : ticks) {
                            callback.accept(tick.pos(), tick.type());
                            ++ticked[0];
                        }
                    });
                } finally {
                    this.currentScheduledTickRandom.remove();
                    this.currentRegionTickMutations.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("scheduled tick batch " + coordinate, duration);
                this.scheduledTicksOnWorkers.addAndGet(ticked[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Executes the ticks whose region could not be leased safely on the server thread after the batch.
     * <p>
     * The ticks were already removed from {@code LevelTicks}, so they must not go through the generic
     * region handoff again: that could defer them to a later region tick or drop them on a closed level.
     * Instead they run through the direct server-thread callback before the dispatcher returns. A tick
     * whose level identity closed while the batch ran is handled by the existing closed-level policy and
     * counted as skipped, exactly like the collected snapshot of a closed level.
     */
    private <T> void tickFallbackScheduledTicks(RegionScheduledTickBatch<T> batch, BiConsumer<BlockPos, T> fallbackCallback) {
        final ServerLevel level = batch.level();
        for (final ScheduledTick<T> tick : batch.fallbackTicks()) {
            if (this.regionizer.isLevelClosed(level)) {
                this.skippedScheduledTicks.incrementAndGet();
                continue;
            }
            this.fallbackScheduledTicks.incrementAndGet();
            fallbackCallback.accept(tick.pos(), tick.type());
        }
    }

    /**
     * Applies every mutation a worker requested on either {@code LevelTicks} instance of the batch.
     * <p>
     * Runs on the server thread after all workers finished. Each mutation is applied in request order and
     * one failing mutation does not strand the ones behind it.
     */
    private void applyRegionTickMutations(Queue<Runnable> mutations) {
        Throwable failure = null;
        Runnable mutation;
        while ((mutation = mutations.poll()) != null) {
            try {
                mutation.run();
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Ticks {@code ticks} on their owning region workers and waits for the batch.
     * <p>
     * Runs on the server thread: chunk selection, random position selection, section traversal, thunder,
     * precipitation, natural spawning and inhabited time already ran in the unchanged server-thread body,
     * so this method only distributes the selected random tick callbacks. The batch is published under the
     * lifecycle read gate before any region lease is acquired, so a concurrent shutdown or level unload
     * observes it and waits instead of retiring regions that the batch still holds or is about to lease.
     * Region ownership is re-resolved after the snapshot, so a region that merged or split between
     * collection and lease acquisition is replaced by its authoritative successor instead of dropping the
     * tick. Ticks whose region cannot be leased safely run on the server thread after the batch rather than
     * being dropped.
     * <p>
     * The server thread waits for the whole batch before the level broadcast and the rest of the level tick
     * continue. The mutation queue is drained afterwards, so a random tick that schedules a block or fluid
     * tick never mutates {@code LevelTicks} from a worker.
     */
    void tickRandomTicks(ServerLevel level, List<RegionRandomTick> ticks, List<RegionRandomTick> chunkFallbackTicks, RegionTickBarrier barrier) {
        if (ticks.isEmpty() && chunkFallbackTicks.isEmpty()) {
            return;
        }

        final RegionRandomTickBatch batch = new RegionRandomTickBatch(level, chunkFallbackTicks);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was collected. Its region state is retired and
            // no batch may be published for it: the collected random ticks must not tick for a level that
            // already unloaded.
            this.skippedRandomTicks.addAndGet(ticks.size() + chunkFallbackTicks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the collected random ticks still
            // have to tick, but no batch may be published after shutdown started.
            for (final RegionRandomTick tick : ticks) {
                this.tickRandomTickInline(level, tick);
            }
            for (final RegionRandomTick tick : chunkFallbackTicks) {
                this.tickRandomTickInline(level, tick);
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (fallback work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        Throwable failure = null;
        try {
            try {
                this.leaseRandomTickGroups(batch, ticks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                try {
                    batch.submitAndAwait(this.workerPool, barrier);
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    }
                }
            }
        } finally {
            failure = runBatchCompletion(failure, () -> this.tickFallbackRandomTicks(batch));
            failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
            batch.finish();
            this.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups {@code pending} random ticks by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt.
     * Anything that cannot be resolved to a live, leasable region is recorded as a server-thread fallback.
     */
    private void leaseRandomTickGroups(RegionRandomTickBatch batch, List<RegionRandomTick> pending, RegionTickBarrier barrier) {
        List<RegionRandomTick> remaining = pending;
        int batchedTicks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionRandomTick> grouping = new RegionTickGrouping<>();
            final List<RegionRandomTick> retry = new ArrayList<>();
            for (final RegionRandomTick tick : remaining) {
                final RegionState region = this.regionizer.getOrCreate(this.layout.regionAt(batch.level(), tick.pos()));
                if (region == null || region.isDead()) {
                    retry.add(tick);
                } else {
                    grouping.add(region, tick);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionRandomTick> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be
                    // acquired again and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addTicks(grouping.items(region)));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(grouping.items(region));
                    continue;
                }
                leasedAny = true;
                batchedTicks += grouping.size(region);
                this.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region), batch.nextRandomSeed(), this::randomTickGroup));
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        if (!remaining.isEmpty()) {
            batch.addFallback(remaining);
        }
        if (batchedTicks > 0 && this.randomTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region random ticks are now executing on region workers: {} ticks", batchedTicks);
        }
    }

    private Runnable randomTickGroup(RegionRandomTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.level();
        final List<RegionRandomTick> ticks = group.ticks();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                this.currentRandomTickRandom.set(random);
                this.currentRegionTickMutations.set(group.batch().regionTickMutations());
                try {
                    this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                        for (final RegionRandomTick tick : ticks) {
                            tick.callback().randomTick(level, tick.pos(), random);
                            ++ticked[0];
                        }
                    });
                } finally {
                    this.currentRandomTickRandom.remove();
                    this.currentRegionTickMutations.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("random tick batch " + coordinate, duration);
                this.randomTicksOnWorkers.addAndGet(ticked[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Executes the random ticks whose region could not be leased safely, and the ticks of chunks that are
     * not surrounded by chunks of the same region, on the server thread after the batch.
     * <p>
     * The callbacks were selected from the level sections, so they must not go through the generic region
     * handoff again: that could defer them to a later region tick or drop them on a closed level. A tick
     * whose level identity closed while the batch ran is handled by the existing closed-level policy and
     * counted as skipped, exactly like the collected snapshot of a closed level.
     */
    private void tickFallbackRandomTicks(RegionRandomTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final RegionRandomTick tick : batch.fallbackTicks()) {
            if (this.regionizer.isLevelClosed(level)) {
                this.skippedRandomTicks.incrementAndGet();
                continue;
            }
            this.fallbackRandomTicks.incrementAndGet();
            tick.callback().randomTick(level, tick.pos(), level.random);
        }
    }

    private void tickRandomTickInline(ServerLevel level, RegionRandomTick tick) {
        if (this.regionizer.isLevelClosed(level)) {
            this.skippedRandomTicks.incrementAndGet();
            return;
        }
        this.fallbackRandomTicks.incrementAndGet();
        tick.callback().randomTick(level, tick.pos(), level.random);
    }

    private static void throwUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region tick batch failed", throwable);
    }

    /**
     * Runs {@code task} while the current thread owns the region containing {@code pos}.
     * <p>
     * Work for the position's own region runs inline. When the current thread holds no region lease (the
     * server tick thread and the level tick bridge), the tick takes the lease of the owning region and runs
     * under it when the region is free; a region that a worker currently owns is deferred to its queue so
     * the tick runs in the next tick of that region. When the current thread already holds a region lease,
     * the tick is always deferred: taking a second region's lease from inside a region tick would nest
     * leases and let a cross-region tick run while another region is still being ticked.
     */
    @ApiStatus.Internal
    public void runInBlockRegion(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.isOwnedByCurrentRegion(level, pos)) {
            task.run();
            return;
        }
        if (this.shutdown) {
            // The regionizer is closed; tick like vanilla instead of dropping the work into a queue that
            // no longer exists. The server thread owns the tick at this point.
            task.run();
            return;
        }
        final RegionThreadingContext.Kind kind = this.currentContext().kind();
        if (kind == RegionThreadingContext.Kind.REGION || kind == RegionThreadingContext.Kind.GLOBAL_REGION) {
            this.enqueueRegionTask(level, pos, task);
            return;
        }
        if (!this.runBlockTaskUnderLease(level, pos, task)) {
            this.enqueueRegionTask(level, pos, task);
        }
    }

    /**
     * Runs {@code task} under a freshly acquired lease of the region containing {@code pos}.
     *
     * @return {@code false} when the region is currently leased by another thread or the engine is shut
     *         down, in which case the caller has to defer the tick to the region queue instead of running
     *         it here
     */
    private boolean runBlockTaskUnderLease(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.shutdown) {
            return false;
        }
        final boolean tryLifecycleGateOnly = this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread()
                || this.workerPool.isWorkerThread(Thread.currentThread());
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            return false;
        }
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        final RegionState region;
        try {
            if (this.shutdown) {
                return false;
            }
            region = this.regionizer.getOrCreate(coordinate);
            if (region == null || region.isDead() || !region.tryMarkRunning()) {
                return false;
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), task);
        } finally {
            region.endOwnership();
            region.clearRunning();
            this.regionizer.removeIfEmpty(region);
        }
        return true;
    }

    private boolean enqueueRegionTask(ServerLevel level, BlockPos pos, Runnable task) {
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        // The regionizer resolves a live owner and inserts the task while holding the same lock that
        // retirement and merge/split publication use, so the handoff can never land in a retired queue and
        // never needs a retry or an inline fallback that would drop it.
        return this.regionizer.enqueue(coordinate, () -> this.runQueuedBlockTask(level, pos, task));
    }

    private void runQueuedBlockTask(ServerLevel level, BlockPos pos, Runnable task) {
        if (this.isOwnedByCurrentRegion(level, pos)) {
            task.run();
            return;
        }
        // A merge/split can change the owner after insertion. Hand off again under the current queue's
        // lease instead of executing against the wrong region. This remains deferred and non-blocking.
        this.enqueueRegionTask(level, pos, task);
    }

    /**
     * Drains tasks queued to the region that owns {@code pos}.
     * <p>
     * A worker tick that is still running for the same region is awaited first, unless the current thread
     * is the one running it. Otherwise the drain could observe the lease as taken and return while the
     * queued work is only picked up by the worker's next tick.
     * <p>
     * When the current thread already owns this region, it is running inside the region's tick. Work that
     * was queued during that tick belongs to the next tick, so the drain returns without nesting a second
     * queue snapshot or advancing the logical queue tick.
     */
    @ApiStatus.Internal
    public void makeRegionDueForTesting(ServerLevel level, BlockPos pos) {
        final RegionState region = this.regionizer.get(this.layout.regionAt(level, pos));
        if (region != null) {
            region.makeDueForTesting();
        }
    }

    /**
     * Installs a hook that runs immediately before a level drain activates a region.
     * <p>
     * Only used by tests to force the interleaving between drain activation and lifecycle cleanup.
     */
    @ApiStatus.Internal
    public void setBeforeRegionDrainActivationForTesting(@Nullable Runnable hook) {
        this.beforeRegionDrainActivation = hook;
    }

    /**
     * Installs a hook that runs while an admission holds the admission lock, immediately before the
     * admission is published.
     * <p>
     * Only used by tests to force the interleaving between an admission and lifecycle cleanup.
     */
    @ApiStatus.Internal
    public void setBeforeRegionAdmissionForTesting(@Nullable Runnable hook) {
        this.beforeRegionAdmission = hook;
    }

    @ApiStatus.Internal
    public void drainRegionTasks(ServerLevel level, BlockPos pos) {
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        final RegionState region = this.regionizer.get(coordinate);
        if (region == null) {
            return;
        }
        if (region.isOwnedByCurrentThread()) {
            // Reentrant same-region drain: the queue drain that is running on this thread owns the region
            // and will pick up newly inserted work on its next tick. Nested draining would snapshot the
            // queue again and advance its logical tick (and scheduled deadlines) inside the outer tick.
            return;
        } else if (this.workerPool.isWorkerThread(Thread.currentThread())) {
            // A pool worker cannot wait for a submission that may be queued behind its own task, and it
            // must never drain a queue whose worker was already accepted.
            if (!region.isWorkerSubmitted() && !region.isRunning()) {
                this.drainRegionStateWithLifecycleGate(region, true);
            }
        } else {
            this.awaitRegionQuiescent(region, levelBarrier(level));
            final boolean tryLifecycleGateOnly = this.isExecutingTickBatchOnCurrentThread()
                    || this.isExecutingRegionTickOnCurrentThread();
            this.drainRegionStateWithLifecycleGate(region, tryLifecycleGateOnly);
        }
        this.regionizer.removeIfEmpty(region);
    }

    /**
     * Drains all tasks queued to regions in {@code level} and waits for the region ticks submitted by this
     * drain before returning.
     * <p>
     * A worker drain mutates the region queue and can call back into the level, so the vanilla continuation
     * after this method must not observe the region as idle while its worker is still running. The wait
     * uses the level chunk source barrier, exactly like an entity batch, so off-thread chunk requests can
     * still be serviced. A pool worker never waits here: work it submitted may only be runnable after the
     * current worker returns.
     */
    @ApiStatus.Internal
    public void drainLevelRegionTasks(ServerLevel level) {
        this.drainLevelRegionTasks(level, levelBarrier(level));
    }

    private void drainLevelRegionTasks(ServerLevel level, @Nullable RegionTickBarrier barrier) {
        final long now = System.nanoTime();
        final boolean workerThread = this.workerPool.isWorkerThread(Thread.currentThread());
        // A lifecycle cleanup may already wait for the entity batch or region tick running on this thread.
        // Blocking on the lifecycle gate would wait for itself, so such a drain only tries the read lock
        // and rejects its activation when a cleanup is in progress.
        final boolean tryLifecycleGateOnly = workerThread || this.isExecutingTickBatchOnCurrentThread()
                || this.isExecutingRegionTickOnCurrentThread();
        final RegionTaskBatch submissions = new RegionTaskBatch();
        final RegionTaskBatch.Barrier completion = RegionTaskBatch.Barrier.open();
        final List<RegionState> alreadyActive = new ArrayList<>();
        this.regionizer.forEach(region -> {
            if (region.coordinate().level() != level) {
                return;
            }
            if (region.isOwnedByCurrentThread()) {
                // The current thread is already ticking this region (a nested level drain from a region
                // task). Skip it entirely: activating it would consume the region tick timer without a
                // drain and push its next legitimate tick into the future.
                return;
            }
            if (this.workerExecutionEnabled) {
                this.trySubmitRegionDrain(region, now, submissions, alreadyActive, tryLifecycleGateOnly);
            } else {
                this.tickRunner.runIfDue(region, now, () -> this.drainRegionStateWithLifecycleGate(region, tryLifecycleGateOnly));
            }
            this.regionizer.removeIfEmpty(region);
        });

        submissions.submitOpen(this.workerPool, completion);
        completion.setupComplete();
        this.rejectedWorkerTaskCount.addAndGet(completion.rejectedCount());
        if (workerThread) {
            // A region worker cannot wait for a submission that may be queued behind its own task.
            return;
        }
        if (barrier != null) {
            barrier.await(completion::isComplete);
        }
        completion.awaitCompletion();
        for (final RegionState region : alreadyActive) {
            this.awaitRegionQuiescent(region, barrier);
        }
        completion.throwIfFailed();
    }

    private static RegionTickBarrier levelBarrier(ServerLevel level) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        return chunkSource == null ? null : chunkSource::managedBlockOnMainThread;
    }

    private void awaitRegionQuiescent(RegionState region, @Nullable RegionTickBarrier barrier) {
        if (region.isOwnedByCurrentThread()) {
            return;
        }
        if (barrier == null) {
            region.awaitQuiescent();
            return;
        }
        barrier.await(() -> !region.isWorkerSubmitted() && !region.isRunning());
    }

    /**
     * Drops queued region work and scheduled entity tasks for {@code level} during level unload.
     * <p>
     * The clear is serialized with every admission path: running work is awaited under the lifecycle
     * gate first, then the level identity is closed and its regionizer state is dropped while holding the
     * admission lock and the regionizer write lock. No admission can therefore publish a region or task
     * that this clear would miss, and no region, section, entity or scheduled task for the level remains
     * when this method returns.
     * <p>
     * Lock order is lifecycle write gate, then the regionizer. The write gate is held while active tick
     * batches are awaited, but the regionizer lock is not: a published batch keeps leasing regions and its
     * workers retire empty regions ({@code removeIfEmpty}) without the lifecycle gate, so holding the
     * regionizer here would block the very batch the clear waits for. Publication takes the read gate for
     * its closed-level check, so a batch either publishes before this clear (and is awaited) or observes the
     * closed identity and rejects itself.
     * <p>
     * Closure is per {@link ServerLevel} identity and lasts for the lifetime of that identity: later
     * admissions for an unloaded level are rejected (schedules return a cancelled task, enqueues and
     * section/entity updates are dropped) instead of recreating live region state. An unloaded level is
     * never reloaded under the same instance; a legitimate reload constructs a new {@code ServerLevel},
     * which receives a fresh lifecycle. Closed identities are tracked weakly, so this does not keep an
     * unloaded level alive.
     */
    @ApiStatus.Internal
    public void clearLevelRegionTasks(ServerLevel level) {
        this.rejectLifecycleWaitFromWorker("clear region tasks for a level");
        synchronized (this) {
            final Lock lifecycleWriteLock = this.lifecycleGate.writeLock();
            lifecycleWriteLock.lock();
            try {
                this.awaitActiveTickBatches(level);
                this.awaitSubmittedRegionTasks(level);
                // The admission lock closes the lookup-to-insertion window: an admission that already
                // passed its shutdown check either publishes before this block (and is cleared below) or
                // observes the closed level identity and rejects its work.
                synchronized (this.admissionLock) {
                    // A pending decision that touches the closing level becomes terminal here. A later
                    // commit therefore cannot write a region mapping after the clear below.
                    this.entityTransferJournal.clearPendingFor(level);
                    this.pendingDeferredEntityMoves.removeIf(move -> move.destination().level() == level);
                    this.regionizer.clearLevel(level);
                    for (final EntityRegionScheduledTask task : this.entityTaskSnapshot()) {
                        if (task.isOwnedBy(level)) {
                            task.cancel();
                        }
                    }
                }
            } finally {
                lifecycleWriteLock.unlock();
            }
        }
    }

    /**
     * Tracks a loaded chunk section in the native regionizer facade.
     */
    @ApiStatus.Internal
    public void addRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        this.callAdmitted(() -> this.regionizer.addChunk(level, chunkX, chunkZ));
    }

    /**
     * Removes a loaded chunk section from the native regionizer facade.
     */
    @ApiStatus.Internal
    public void removeRegionSection(ServerLevel level, int chunkX, int chunkZ) {
        this.runAdmitted(() -> this.regionizer.removeChunk(level, chunkX, chunkZ));
    }

    /**
     * Tracks an entity in the native regionizer facade.
     */
    @ApiStatus.Internal
    public void addRegionEntity(Entity entity) {
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            if (entity.level() instanceof ServerLevel level) {
                this.regionizer.addEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
            }
        }
    }

    /**
     * Removes an entity from the native regionizer facade.
     * <p>
     * Inside an entity tick batch the removal is deferred until the batch finished, so the snapshot region
     * stays authoritative while the entity is ticked.
     */
    @ApiStatus.Internal
    public void removeRegionEntity(Entity entity) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            group.batch().deferEntityUpdate(entity, this.deferredEntityDestination(entity));
            return;
        }
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            this.publishEntityRemoval(entity);
        }
    }

    /**
     * Updates an entity's current region in the native regionizer facade.
     * <p>
     * Inside an entity tick batch the move is deferred until the batch finished. Publishing the new owner
     * while the old snapshot region is still ticking would make ownership checks fail and let scheduled
     * work move to the new region before the old tick completed.
     */
    @ApiStatus.Internal
    public void moveRegionEntity(Entity entity) {
        final RegionEntityTickBatch.Group group = this.currentEntityTickGroup();
        if (group != null) {
            group.batch().deferEntityUpdate(entity, this.deferredEntityDestination(entity));
            return;
        }
        synchronized (this.admissionLock) {
            if (this.shutdown) {
                return;
            }
            this.publishEntityMove(entity);
        }
    }

    /**
     * {@return the destination region of a deferred ownership change, captured on the deferring thread}
     * <p>
     * Inside an entity tick batch the position read here is the one the deferring worker already observed
     * while it ticked the entity. The destination is stored with the deferred update and committed at the
     * barrier, so a position the entity reaches in between cannot redirect the handoff.
     */
    @Nullable
    private RegionCoordinate deferredEntityDestination(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return this.layout.regionAt(level, entity.blockPosition());
    }

    void publishEntityMove(Entity entity) {
        if (this.shutdown || !(entity.level() instanceof ServerLevel level)) {
            // The engine is being torn down or the entity left the server side; its scheduled wrappers
            // have no live region to migrate to and must be retired like a removal.
            this.cancelEntityTasks(entity);
            return;
        }
        final RegionState region = this.regionizer.moveEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
        if (region == null) {
            // The level identity was unloaded for region threading. The unload only cancelled the tasks
            // it could still attribute to the level; a wrapper whose entity already reported the new
            // level would stay registered with a dead child, so retire it here as well.
            this.cancelEntityTasks(entity);
            return;
        }
        this.notifyEntityMoved(entity);
    }

    /**
     * Applies the deferred ownership handoffs of a finished entity batch on the server thread.
     * <p>
     * Every handoff carries the destination region its deferring worker captured, so nothing here reads the
     * entity's live position to decide where the mapping goes.
     * <p>
     * The batch's own captures are applied before the moves that a busy region left pending at an earlier
     * barrier, because a capture of this batch is the newer observation of the entity. Committing the newer
     * capture prunes the pending entry of the same entity, so the stale queued destination is not applied and
     * the newest observation wins instead of the mapping lagging a tick behind. The pending entries are claimed
     * one by one after the batch ran, so a move that this barrier queues for a still busy region waits for the
     * next barrier instead of being attempted twice in a row.
     */
    private void applyDeferredEntityUpdates(RegionEntityTickBatch batch) {
        final List<DeferredEntityMove> waiting = this.pendingDeferredEntityMovesSnapshot();
        this.applyDeferredEntityUpdateBatch(batch.drainDeferredUpdates(), batch.level());
        for (final DeferredEntityMove move : waiting) {
            if (this.claimPendingDeferredEntityMove(move)) {
                this.applyDeferredEntityUpdate(move.entity(), move.destination(), move.attempts());
            }
        }
    }

    /**
     * Publishes the deferred ownership updates drained from one barrier, in one regionizer write scope.
     * <p>
     * An update the barrier cannot trust to the batch - a removed or cross-level entity, a destination that
     * was not captured in the entity's current level, a shut-down engine or a barrier that is not the server
     * thread - keeps the per-entity path, which is what the barrier did for every update before the batch
     * existed. Every other update is handed to
     * {@link ThreadedRegionizer#publishEntityMoves(List)}: the regionizer validates the whole list and then
     * publishes the untracked entities and the no-ops under one write lock, so the common case of a tick that
     * only re-observes the region of every entity costs one write-lock scope and no mapping write at all.
     * <p>
     * The moves that name another region than the published one are not written by the batch. They are
     * returned as transfers and keep the recorded transfer transaction, which never writes a region that a
     * worker is ticking and defers a busy handoff to the next barrier, exactly like before. A batch the
     * regionizer refused as a whole (a closed regionizer or level identity, a level mismatch, a retired
     * destination) is counted and published per entity instead, which keeps the legacy behavior of a barrier
     * that cannot batch.
     */
    private void applyDeferredEntityUpdateBatch(List<RegionEntityTickBatch.DeferredEntityUpdate> updates, ServerLevel batchLevel) {
        if (updates.isEmpty()) {
            return;
        }
        final List<ThreadedRegionizer.OwnerMove> moves = new ArrayList<>(updates.size());
        final List<RegionEntityTickBatch.DeferredEntityUpdate> perEntity = new ArrayList<>(0);
        final boolean batchable = this.canBatchDeferredEntityMoves(batchLevel);
        for (final RegionEntityTickBatch.DeferredEntityUpdate update : updates) {
            if (batchable && this.isBatchableDeferredEntityMove(update)) {
                moves.add(new ThreadedRegionizer.OwnerMove(update.entity(), update.destination()));
            } else {
                perEntity.add(update);
            }
        }
        if (!moves.isEmpty()) {
            final ThreadedRegionizer.MoveBatchResult result = this.regionizer.publishEntityMoves(moves);
            if (result.applied()) {
                this.publishDeferredEntityMoveBatch(result);
            } else {
                this.refusedDeferredEntityMoveBatchCount.incrementAndGet();
                for (final ThreadedRegionizer.OwnerMove move : moves) {
                    this.applyDeferredEntityUpdate(move.entity(), move.destination(), 0);
                }
            }
        }
        for (final RegionEntityTickBatch.DeferredEntityUpdate update : perEntity) {
            this.applyDeferredEntityUpdate(update.entity(), update.destination(), 0);
        }
    }

    /**
     * Applies the result of a batch publication: counts it, notifies the entities that have scheduled tasks
     * and hands the cross-region moves to the recorded transfer transaction.
     * <p>
     * A move notification only reaches the registered wrappers of its own entity, so exactly the entities that
     * have one are notified. That includes the unchanged moves: a task whose entity stayed in its region does
     * not need the notification, but a task that was scheduled in a region the entity has already left is
     * migrated by it, so the notification is never dropped for an entity that has a task. An entity without a
     * wrapper has nothing to migrate, so the scan is skipped for it - which is the whole population of a tick
     * in the common case.
     */
    private void publishDeferredEntityMoveBatch(ThreadedRegionizer.MoveBatchResult result) {
        this.batchedDeferredEntityMoveCount.addAndGet(result.published().size() + result.transfers().size());
        this.unchangedDeferredEntityMoveCount.addAndGet(result.unchanged().size());
        if (!this.entityTasksByEntity.isEmpty()) {
            for (final Entity entity : result.published()) {
                this.notifyEntityMoved(entity);
            }
            for (final Entity entity : result.unchanged()) {
                this.notifyEntityMoved(entity);
            }
        }
        for (final ThreadedRegionizer.OwnerMove move : result.transfers()) {
            this.applyDeferredEntityUpdate(move.entity(), move.destination(), 0);
        }
    }

    /**
     * {@return true when this barrier may hand its deferred updates to the regionizer batch}
     * <p>
     * The regionizer storage is server-owned, and the batch publishes mappings on the thread that calls it. A
     * barrier that is not the server thread owning the level of the batch, or that runs after the engine shut
     * down, therefore keeps the per-entity publication instead.
     */
    private boolean canBatchDeferredEntityMoves(ServerLevel batchLevel) {
        if (this.shutdown) {
            return false;
        }
        final MinecraftServer server = batchLevel.getServer();
        return server != null && server.isSameThread();
    }

    /**
     * {@return true when {@code update} can be published by the regionizer batch}
     * <p>
     * The update carries the destination its deferring thread captured, so the batch only needs the entity to
     * be alive, added to a server level, and to name that level: a removed entity is published as a removal
     * and a destination outside the entity's current level keeps the vanilla publish from the live position,
     * both exactly like the per-entity path does.
     */
    private boolean isBatchableDeferredEntityMove(RegionEntityTickBatch.DeferredEntityUpdate update) {
        final RegionCoordinate destination = update.destination();
        if (destination == null || update.entity().isRemoved()) {
            return false;
        }
        return update.entity().level() instanceof ServerLevel level && destination.level() == level;
    }

    /**
     * Applies the section publications and target chunk acquisitions of a finished entity batch.
     * <p>
     * A worker that moves an entity added to its level cannot publish the entity section or acquire the
     * target chunk, because both are server-owned storage. It records the level callback the inline vanilla
     * move would have invoked and the section of the position the move reached, and this barrier runs both
     * effects on the server thread. The publication reconciles the section membership with the entity's
     * final position, so several moves of one tick each invoke it but only the first one moves the
     * membership; the chunk acquisition of every recorded move still runs, exactly like the inline calls
     * the worker skipped.
     * <p>
     * Every entry is applied on its own: a publication or chunk acquisition that throws - a modded
     * {@code EntityEvent.EnteringSection} listener or a level callback of the level - drops and counts that
     * one entry and leaves the entries behind it untouched, because their entities carry the same moved
     * position. The first failure is rethrown afterwards, so the batch reports it through the same
     * completion path as every other server-thread step.
     * <p>
     * An update that cannot be applied is dropped without touching storage and counted in
     * {@link RegionThreadingDiagnostics#droppedDeferredEntitySectionUpdates()}: the engine shut down, the
     * level identity closed, the batch closed its section seam, the entity was removed or left the level
     * between the move and the barrier, or its publication threw. The barrier runs before the ownership
     * handoff of the same batch, so a section update is always published before a transfer commit can move
     * the entity to another region.
     */
    private void applyDeferredEntitySectionUpdates(RegionEntityTickBatch batch) {
        final List<RegionEntityTickBatch.DeferredSectionUpdate> updates = batch.drainDeferredSectionUpdates();
        if (updates.isEmpty()) {
            return;
        }
        final ServerLevel level = batch.level();
        if (this.shutdown || this.regionizer.isLevelClosed(level) || batch.isSectionUpdateSeamClosed()) {
            this.droppedDeferredSectionUpdateCount.addAndGet(updates.size());
            return;
        }
        Throwable failure = null;
        for (final RegionEntityTickBatch.DeferredSectionUpdate update : updates) {
            final Entity entity = update.entity();
            if (entity.isRemoved() || entity.level() != level || !entity.isAddedToLevel()) {
                this.droppedDeferredSectionUpdateCount.incrementAndGet();
                continue;
            }
            try {
                update.callback().onMove();
                level.getChunk(SectionPos.x(update.targetSection()), SectionPos.z(update.targetSection()));
                this.appliedDeferredSectionUpdateCount.incrementAndGet();
            } catch (Throwable throwable) {
                // The entry did not complete, so it is dropped and counted; the remaining entries still run
                // because their entities were moved by the same batch and must not keep an unpublished
                // section. The first failure is reported once the whole batch was served.
                this.droppedDeferredSectionUpdateCount.incrementAndGet();
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Applies the removals of an owned entity that a worker deferred to the barrier.
     * <p>
     * A worker that removes an entity it owns writes only the entity's own removal state; the level callback
     * of the removal is server-owned storage, so it runs here on the server thread. The callback is the normal
     * removal path: it publishes the departure in the identity ledger, removes the entity from its section,
     * stops its ticking and tracking (which fires {@code EntityLeaveLevelEvent} and retires the region mapping),
     * runs the destruction callback and clears the entity's level callback. Applying it here therefore leaves
     * the storage, the identity ledger, the tracking state and the region index exactly as a server-thread
     * removal does.
     * <p>
     * The step runs after the deferred section updates, so a removal removes the entity from the section its
     * final position was published in, and before the deferred ownership publication, so the handoff observes
     * the removed entity and publishes its departure instead of a region mapping.
     * <p>
     * An entry that cannot be applied is a counted no-op and never touches storage: the entity was revived
     * ({@code !isRemoved()}), the server thread already applied the removal (the section manager replaced the
     * level callback the worker recorded), the entity left the level, or its level changed. A callback that throws drops and counts that one entry and leaves the entries behind it
     * untouched, because they name other entities; the first failure is rethrown afterwards through the same
     * completion path as every other server-thread step.
     */
    private void applyDeferredEntityRemovals(RegionEntityTickBatch batch) {
        final RegionEntityTickBatch.RemovalDrain drain = batch.drainDeferredRemovals();
        this.repeatedDeferredRemovalCount.addAndGet(drain.repeats());
        if (drain.removals().isEmpty()) {
            return;
        }
        final ServerLevel level = batch.level();
        if (this.shutdown || this.regionizer.isLevelClosed(level) || batch.isRemovalSeamClosed()) {
            this.droppedDeferredRemovalCount.addAndGet(drain.removals().size());
            return;
        }
        Throwable failure = null;
        for (final RegionEntityTickBatch.DeferredEntityRemoval removal : drain.removals()) {
            final Entity entity = removal.entity();
            if (!entity.isRemoved()
                    || !entity.isAddedToLevel()
                    || entity.level() != level
                    || entity.getLevelCallback() != removal.callback()) {
                // The removal was already applied by the server thread (the section manager replaced the
                // level callback it recorded with the NULL callback), the entity was revived, its level
                // changed or it left the level: the deferred removal is a no-op, never a second removal.
                this.repeatedDeferredRemovalCount.incrementAndGet();
                continue;
            }
            try {
                removal.callback().onRemove(removal.reason());
                this.appliedDeferredRemovalCount.incrementAndGet();
            } catch (Throwable throwable) {
                this.droppedDeferredRemovalCount.incrementAndGet();
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Closes the deferred seams of every active entity batch when shutdown starts.
     * <p>
     * A shut-down engine never runs a barrier again, so a batch that still ticks must not accept another
     * section update or another removal: the next worker move or removal is refused by the storage guard
     * instead of being granted and dropped. The updates and removals that already wait are released and
     * counted, so no entity reference stays reachable from the batch and nothing is applied after the
     * shutdown.
     */
    private void closeDeferredSeams() {
        for (final RegionTickBatch active : this.activeTickBatches) {
            if (active instanceof RegionEntityTickBatch entityBatch) {
                this.droppedDeferredSectionUpdateCount.addAndGet(entityBatch.closeDeferredSectionUpdates());
                final RegionEntityTickBatch.RemovalDrain releasedRemovals = entityBatch.closeDeferredRemovals();
                this.droppedDeferredRemovalCount.addAndGet(releasedRemovals.removals().size());
                this.repeatedDeferredRemovalCount.addAndGet(releasedRemovals.repeats());
            }
        }
    }

    /**
     * Applies one deferred ownership change at the entity batch barrier.
     * <p>
     * This is the per-entity path of the barrier: the ownership updates of a barrier are published by
     * {@link #applyDeferredEntityUpdateBatch(List, ServerLevel)}, and this path serves the updates that batch
     * cannot carry (a removed or cross-level entity, a refused batch) and the recorded transfers, busy retries
     * and no-ops it hands back.
     * <p>
     * The destination was captured by the deferring thread and is committed through the recorded transfer
     * transaction, so the barrier decides it exactly once. The transaction is a bookmark, not a new
     * atomicity claim: only the region-index mapping moves, and the vanilla section, identity, tracking and
     * network side effects stay caller-owned.
     * <p>
     * Every outcome that cannot use the transaction keeps the previous behavior of publishing the deferred
     * move from the live position and is counted in {@link RegionThreadingDiagnostics#legacyDeferredEntityMoveCount()}:
     * a removed or cross-level entity, a shut-down engine, a level the current thread does not own, a refused
     * recording (an untracked entity, a riding graph that spans levels or lost its identity epoch, a closed
     * level, a full journal) and any other terminal commit result. None of them is retried with a destination
     * derived a second time. An update that names the region the entity is already mapped to is the no-op
     * case of the per-tick move hook: it is published the same way but is not counted as a fallback.
     * <p>
     * {@link RegionEntityTransferResult#REGION_BUSY} is the one transient answer: the same captured
     * destination is applied again at the next barrier, at most {@link #MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS}
     * times, so a leased region delays the handoff instead of dropping it. Once the bound is reached the move
     * is published through the legacy path as well, which keeps the pre-transaction behavior for a region
     * that stays busy.
     */
    private void applyDeferredEntityUpdate(Entity entity, @Nullable RegionCoordinate destination, int attempts) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
            this.publishEntityRemoval(entity);
            return;
        }
        if (!this.canRecordDeferredEntityMove(level, destination)) {
            this.publishLegacyDeferredEntityMove(entity);
            return;
        }
        final RegionState source = this.regionizer.regionFor(entity);
        if (source == null || source.coordinate().equals(destination)) {
            // The deferred update names the region the entity is already mapped to. That is the common case of
            // the per-tick move hook, which defers on every entity tick; publishing it keeps the
            // pre-transaction behavior, including the scheduled-task notification, without booking a transfer
            // that changes nothing.
            this.publishEntityMove(entity);
            return;
        }
        final RegionEntityTransferToken token = this.recordEntityRegionTransfer(entity, destination);
        if (token == null) {
            this.publishLegacyDeferredEntityMove(entity);
            return;
        }
        final RegionEntityTransferResult result = this.commitEntityRegionTransfer(token);
        if (result == RegionEntityTransferResult.COMMITTED) {
            return;
        }
        if (result == RegionEntityTransferResult.REGION_BUSY && attempts < MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS) {
            this.retriedDeferredEntityMoveCount.incrementAndGet();
            synchronized (this.admissionLock) {
                this.pendingDeferredEntityMoves.add(new DeferredEntityMove(entity, destination, attempts + 1));
            }
            return;
        }
        this.publishLegacyDeferredEntityMove(entity);
    }

    /**
     * {@return true when the barrier can book the deferred move of an entity in {@code level}}
     * <p>
     * The recorded API rejects a caller it cannot trust with an exception, so the barrier checks the same
     * preconditions first: the engine is running, the destination was captured in the entity's level, and the
     * current thread is the server thread owning that level. A level without an owning server, or a batch
     * driven from another thread, therefore keeps the pre-transaction publish instead of failing the whole
     * batch for a handoff that simply cannot be booked.
     */
    private boolean canRecordDeferredEntityMove(ServerLevel level, @Nullable RegionCoordinate destination) {
        if (this.shutdown || destination == null || destination.level() != level) {
            return false;
        }
        final MinecraftServer server = level.getServer();
        return server != null && server.isSameThread();
    }

    /**
     * {@return a snapshot of the deferred entity moves that wait for a busy region}
     */
    private List<DeferredEntityMove> pendingDeferredEntityMovesSnapshot() {
        synchronized (this.admissionLock) {
            return List.copyOf(this.pendingDeferredEntityMoves);
        }
    }

    /**
     * Removes {@code move} from the pending list so it is attempted exactly once per barrier.
     *
     * @return true when the move was still waiting, i.e. no newer capture of its entity pruned it
     */
    private boolean claimPendingDeferredEntityMove(DeferredEntityMove move) {
        synchronized (this.admissionLock) {
            return this.pendingDeferredEntityMoves.remove(move);
        }
    }

    /**
     * Publishes a deferred move that the recorded transfer transaction could not carry, exactly like before
     * the transaction existed, and counts it so a transaction that refuses handoffs stays observable.
     */
    private void publishLegacyDeferredEntityMove(Entity entity) {
        this.legacyDeferredEntityMoveCount.incrementAndGet();
        this.publishEntityMove(entity);
    }

    /**
     * One deferred entity move that waits for its busy source or destination region.
     *
     * @param destination the region captured when the move was deferred; it is never re-derived
     * @param attempts    the number of barriers this move was already retried at
     */
    private record DeferredEntityMove(Entity entity, RegionCoordinate destination, int attempts) {}

    /**
     * Records a cross-region entity transfer decision without mutating any index.
     * <p>
     * The token records the entity, its current published identity epoch, the current source region and
     * the supplied destination region. The caller owns the vanilla entity, world and network side effects;
     * it records the intent first and commits the token on the server thread afterwards. A commit never
     * reads the live position again, so the destination stays the one recorded here even when the entity
     * moves in between.
     * <p>
     * Recording is a server-owned storage operation: it is rejected for a native region worker before it
     * reads the identity ledger, and for any other thread except the server thread owning the entity's
     * level. It answers {@code null} when no transfer can be recorded - no native engine ledger, an
     * untracked or removed entity, a graph member without a current identity, a graph that is not confined
     * to this level, the same source and destination region, a destination in another level, or a full
     * journal - and the caller then keeps the vanilla path. The vanilla cross-level transfer is therefore
     * never interrupted by an exception from this bookmark; a destination in another level and a riding
     * graph that spans levels are simply not part of the region transaction.
     *
     * @throws IllegalStateException when the caller is a native region worker, or any other thread except
     *                               the server thread owning the entity's level
     */
    @ApiStatus.Internal
    @Nullable
    public RegionEntityTransferToken recordEntityRegionTransfer(Entity entity, RegionCoordinate destinationRegion) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.recordEntityRegionTransfer");
        Objects.requireNonNull(destinationRegion, "destinationRegion");
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(level, "RegionThreadingEngine.recordEntityRegionTransfer");
        if (this.shutdown || entity.isRemoved()) {
            return null;
        }
        if (destinationRegion.level() != level) {
            return null;
        }
        final RegionState source = this.regionizer.regionFor(entity);
        if (source == null || source.isDead() || source.coordinate().equals(destinationRegion)) {
            return null;
        }
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return null;
        }
        final RegionEntityTransferEpochSource epochs = RegionEntityTransferEpochSource.of(level.entityIdentityLedger());
        final long identityEpoch = epochs.epoch(uuid);
        if (identityEpoch == RegionEntityTransferEpochSource.MISSING_EPOCH) {
            return null;
        }
        final List<RegionEntityTransferToken.Member> ridingGraph = new ArrayList<>();
        if (!this.collectRidingGraphMembers(topmostRidingEntity(entity), level, epochs, ridingGraph)) {
            return null;
        }
        final RegionEntityTransferToken token = new RegionEntityTransferToken(
                this.nextEntityTransferTokenId.incrementAndGet(),
                RegionEntityTransferToken.Operation.REGION_MOVE,
                entity,
                uuid,
                identityEpoch,
                source.coordinate(),
                destinationRegion,
                ridingGraph);
        synchronized (this.admissionLock) {
            // The graph reads above run outside the admission lock so they never delay admissions. The
            // insertion reuses the admission serialization that a level clear and a shutdown use for
            // their journal cleanup, so a decision either becomes visible before that cleanup and is
            // cleared by it, or it observes the retired level/engine here and is not recorded at all.
            if (this.shutdown || this.regionizer.isLevelClosed(level)) {
                return null;
            }
            if (this.entityTransferJournal.record(token)) {
                return token;
            }
        }
        final long rejected = this.entityTransferJournal.rejectedRecordCount();
        if (Long.bitCount(rejected) == 1) {
            // Rate-limited by the rejection count itself: a full journal must be observable without
            // flooding the log once per refused transfer.
            LOGGER.warn("Entity transfer journal is full ({} pending decisions), recording token {} for {} was refused; {} recordings were refused so far",
                    RegionEntityTransferJournal.MAX_PENDING_TOKENS, token.tokenId(), uuid, rejected);
        }
        return null;
    }

    /**
     * {@return the maximum number of recorded transfers that wait for a commit}
     * <p>
     * Recording a transfer beyond this bound is refused and counted, so the bound is shared with the
     * mechanics tests instead of being duplicated there.
     */
    @ApiStatus.Internal
    public static int maxPendingEntityTransfers() {
        return RegionEntityTransferJournal.MAX_PENDING_TOKENS;
    }

    /**
     * Commits one recorded transfer on the server thread.
     * <p>
     * A native region worker is rejected with the named operation before the journal or the regionizer is
     * touched, and every other caller except the server thread owning the token's level is rejected before
     * the journal entry is claimed. The recorded values are validated in order and a mismatch aborts without
     * a mutation. A replay of a committed token answers {@link RegionEntityTransferResult#ALREADY_COMMITTED}
     * and writes nothing a second time; a token another commit is still applying answers
     * {@link RegionEntityTransferResult#IN_PROGRESS} and keeps its pending entry.
     * <p>
     * A commit never writes while the recorded source or destination region is ticking or submitted to a
     * worker; that answers {@link RegionEntityTransferResult#REGION_BUSY}. A written mapping is never
     * reported as a failure: post-commit bookkeeping failures are logged and the token stays
     * {@link RegionEntityTransferResult#COMMITTED}. A completion that a level clear or a shutdown already
     * retired does not re-create its terminal journal entry.
     */
    @ApiStatus.Internal
    public RegionEntityTransferResult commitEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.commitEntityRegionTransfer");
        if (token == null) {
            return RegionEntityTransferResult.UNKNOWN_TOKEN;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(token.level(), "RegionThreadingEngine.commitEntityRegionTransfer");
        final RegionEntityTransferJournal.Claim claim = this.entityTransferJournal.claim(token);
        if (!claim.claimed()) {
            return this.lifecycleTransferResult(token, claim.terminal());
        }
        final RegionEntityTransferResult result;
        try {
            result = this.applyRecordedEntityTransfer(token);
        } catch (Throwable throwable) {
            this.entityTransferJournal.aborted(token, RegionEntityTransferResult.COMMIT_FAILED);
            LOGGER.warn("Entity transfer token {} failed before its region mapping was written", token.tokenId(), throwable);
            return RegionEntityTransferResult.COMMIT_FAILED;
        }
        if (result == RegionEntityTransferResult.COMMITTED) {
            this.entityTransferJournal.completed(token);
        } else {
            this.entityTransferJournal.aborted(token, result);
        }
        return result;
    }

    /**
     * Releases a recorded transfer decision that will never be committed.
     * <p>
     * The pending entry is released without touching any index, so a caller that gave up on the vanilla
     * side of a transfer does not leave a decision behind that would keep rejecting recordings once the
     * journal bound is reached. A replay of an already terminal token answers its recorded result, so an
     * abort is idempotent. A native region worker and any other caller that is not the server thread
     * owning the token's level are rejected before the pending entry is released.
     */
    @ApiStatus.Internal
    public RegionEntityTransferResult abortEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.abortEntityRegionTransfer");
        if (token == null) {
            return RegionEntityTransferResult.UNKNOWN_TOKEN;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(token.level(), "RegionThreadingEngine.abortEntityRegionTransfer");
        return this.lifecycleTransferResult(token, this.entityTransferJournal.abort(token));
    }

    /**
     * {@return the terminal result to report for a journal answer that lost its entry to a lifecycle step}
     * <p>
     * The journal clears pending entries when a level closes or the engine shuts down, so an entry that is
     * gone has to report that lifecycle reason instead of collapsing it into an unknown token.
     */
    private RegionEntityTransferResult lifecycleTransferResult(RegionEntityTransferToken token, @Nullable RegionEntityTransferResult terminal) {
        if (terminal == RegionEntityTransferResult.UNKNOWN_TOKEN) {
            if (this.shutdown) {
                return RegionEntityTransferResult.ENGINE_SHUT_DOWN;
            }
            if (this.regionizer.isLevelClosed(token.level())) {
                return RegionEntityTransferResult.LEVEL_CLOSED;
            }
        }
        return terminal != null ? terminal : RegionEntityTransferResult.UNKNOWN_TOKEN;
    }

    /** {@return the number of recorded transfers that still wait for a commit} */
    @ApiStatus.Internal
    public int pendingEntityTransferCount() {
        return this.entityTransferJournal.pendingCount();
    }

    /** {@return the number of terminal transfer results kept for idempotent replays} */
    @ApiStatus.Internal
    public int rememberedEntityTransferResultCount() {
        return this.entityTransferJournal.rememberedCount();
    }

    private RegionEntityTransferResult applyRecordedEntityTransfer(RegionEntityTransferToken token) {
        if (token.entity().isRemoved()) {
            return RegionEntityTransferResult.ENTITY_REMOVED;
        }
        if (this.shutdown) {
            return RegionEntityTransferResult.ENGINE_SHUT_DOWN;
        }
        final ServerLevel level = token.level();
        if (this.regionizer.isLevelClosed(level)) {
            return RegionEntityTransferResult.LEVEL_CLOSED;
        }
        if (token.destinationRegion().level() != level) {
            // Recording never accepts a destination in another level, so such a token cannot name a region
            // of this level's index: it is as unresolved as a retired destination.
            return RegionEntityTransferResult.DESTINATION_INACTIVE;
        }
        final RegionEntityTransferEpochSource epochs = RegionEntityTransferEpochSource.of(level.entityIdentityLedger());
        if (epochs.epoch(token.uuid()) != token.identityEpoch()) {
            return RegionEntityTransferResult.STALE_IDENTITY_EPOCH;
        }
        final RegionState source = this.regionizer.get(token.sourceRegion());
        if (source == null || source.isDead()) {
            return RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
        }
        final RegionState destination = this.regionizer.get(token.destinationRegion());
        if (destination == null || destination.isDead()) {
            return RegionEntityTransferResult.DESTINATION_INACTIVE;
        }
        final RegionEntityTransferResult graphResult = this.validateRidingGraph(token, epochs);
        if (graphResult != null) {
            return graphResult;
        }
        final List<Entity> entities = new ArrayList<>(token.ridingGraph().size());
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            entities.add(member.entity());
        }
        final RegionEntityTransferResult outcome = transferResult(this.regionizer.commitTransfer(token.sourceRegion(), token.destinationRegion(), entities, level));
        if (outcome != RegionEntityTransferResult.COMMITTED) {
            return outcome;
        }
        // The recorded mapping is written. Nothing below may downgrade the terminal answer: the deferred
        // handoff and the scheduled-task notifications are bookkeeping of an already committed transfer.
        try {
            this.discardPendingDeferredEntityUpdates(level, entities);
        } catch (Throwable throwable) {
            LOGGER.warn("Entity transfer token {} could not discard the deferred region updates of its entities", token.tokenId(), throwable);
        }
        for (final Entity moved : entities) {
            try {
                this.notifyEntityMoved(moved);
            } catch (Throwable throwable) {
                LOGGER.warn("Entity transfer token {} could not notify the scheduled tasks of a moved entity", token.tokenId(), throwable);
            }
        }
        return RegionEntityTransferResult.COMMITTED;
    }

    /**
     * Maps the outcome of the regionizer write to the transfer result.
     * <p>
     * The regionizer resolves the outcome under its write lock, so every reason a recorded transfer was not
     * applied keeps its own name here instead of collapsing into a missing mapping.
     */
    private static RegionEntityTransferResult transferResult(ThreadedRegionizer.TransferOutcome outcome) {
        return switch (outcome) {
            case COMMITTED -> RegionEntityTransferResult.COMMITTED;
            case SOURCE_MAPPING_CHANGED -> RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
            case DESTINATION_INACTIVE -> RegionEntityTransferResult.DESTINATION_INACTIVE;
            case REGION_BUSY -> RegionEntityTransferResult.REGION_BUSY;
            case LEVEL_CLOSED -> RegionEntityTransferResult.LEVEL_CLOSED;
        };
    }

    /**
     * Drops the deferred ownership handoffs of {@code entities} that are still pending in an entity batch of
     * {@code level}.
     * <p>
     * A commit runs on the server thread while the entity tick batch is finishing its server-thread work, so
     * publishing an older deferred update afterwards would move the entity away from the region that was
     * just committed. The committed decision wins.
     */
    private void discardPendingDeferredEntityUpdates(ServerLevel level, List<Entity> entities) {
        for (final RegionTickBatch batch : this.activeTickBatches) {
            if (batch.level() == level && batch instanceof RegionEntityTickBatch entityBatch) {
                entityBatch.discardDeferredUpdates(entities);
            }
        }
        // A move that waits for a busy region carries the destination of the older decision too, so the
        // committed transfer has to drop it as well; otherwise the retry would overwrite the commit.
        final Set<Entity> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        discarded.addAll(entities);
        synchronized (this.admissionLock) {
            this.pendingDeferredEntityMoves.removeIf(move -> discarded.contains(move.entity()));
        }
    }

    /**
     * Validates that the recorded riding graph is unchanged and still belongs to the recorded source.
     * <p>
     * An added or removed member, or a member in another level, rejects the whole token as
     * {@link RegionEntityTransferResult#RIDING_GRAPH_SPLIT}. A member whose region moved away from the
     * recorded source keeps the graph intact but makes this a different transfer, so it is reported as
     * {@link RegionEntityTransferResult#SOURCE_MAPPING_CHANGED} - that covers a normal merge of the source
     * region and a second token for an entity an earlier commit already moved. A graph is never transferred
     * partially.
     */
    @Nullable
    private RegionEntityTransferResult validateRidingGraph(RegionEntityTransferToken token, RegionEntityTransferEpochSource epochs) {
        if (token.ridingGraph().isEmpty()) {
            return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
        }
        final Set<Entity> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            recorded.add(member.entity());
        }
        final Set<Entity> current = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!collectCurrentRidingGraph(token.ridingGraph().get(0).entity(), current) || !current.equals(recorded)) {
            return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
        }
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            final Entity entity = member.entity();
            if (entity.isRemoved()) {
                return RegionEntityTransferResult.ENTITY_REMOVED;
            }
            if (entity.level() != token.level()) {
                return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
            }
            final RegionState region = this.regionizer.regionFor(entity);
            if (region == null || !region.coordinate().equals(token.sourceRegion())) {
                return RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
            }
            if (!member.uuid().equals(entity.getUUID()) || epochs.epoch(member.uuid()) != member.identityEpoch()) {
                return RegionEntityTransferResult.STALE_IDENTITY_EPOCH;
            }
        }
        return null;
    }

    /**
     * Collects the recorded riding graph of one level.
     * <p>
     * The recorded operation is a region move inside {@code level}, so every member has to belong to that
     * level. A graph that spans levels is refused before anything is recorded: no level clear could release
     * a decision that touches members of two levels, so such a token would stay pending until shutdown.
     *
     * @return {@code false} when the graph leaves {@code level} or a member has no published identity
     */
    private static boolean collectRidingGraphMembers(Entity entity, ServerLevel level, RegionEntityTransferEpochSource epochs, List<RegionEntityTransferToken.Member> members) {
        if (entity.level() != level) {
            return false;
        }
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return false;
        }
        final long identityEpoch = epochs.epoch(uuid);
        if (identityEpoch == RegionEntityTransferEpochSource.MISSING_EPOCH) {
            return false;
        }
        members.add(new RegionEntityTransferToken.Member(entity, uuid, identityEpoch));
        for (final Entity passenger : entity.getPassengers()) {
            if (passenger == null || !collectRidingGraphMembers(passenger, level, epochs, members)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectCurrentRidingGraph(Entity entity, Set<Entity> members) {
        if (!members.add(entity)) {
            return true;
        }
        for (final Entity passenger : entity.getPassengers()) {
            if (passenger == null || !collectCurrentRidingGraph(passenger, members)) {
                return false;
            }
        }
        return true;
    }

    private static Entity topmostRidingEntity(Entity entity) {
        Entity current = entity;
        final Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(current);
        while (true) {
            final Entity vehicle = current.getVehicle();
            if (vehicle == null || !vehicle.hasPassenger(current) || !visited.add(vehicle)) {
                return current;
            }
            current = vehicle;
        }
    }

    /**
     * Notifies the registered scheduled tasks of {@code entity} that its region may have moved.
     * <p>
     * Only the wrappers of this entity are reached, so an entity without a wrapper costs one lookup and
     * nothing else. {@link EntityRegionScheduledTask#onEntityMoved()} migrates a task that waits in another
     * region and returns early when the entity still resolves to the region the task was scheduled for, so a
     * notification for an unchanged mapping is never a migration.
     */
    private void notifyEntityMoved(Entity entity) {
        final Set<EntityRegionScheduledTask> tasks = this.entityTasksByEntity.get(entity);
        if (tasks == null) {
            return;
        }
        for (final EntityRegionScheduledTask task : List.copyOf(tasks)) {
            task.onEntityMoved();
        }
    }

    private void cancelEntityTasks(Entity entity) {
        final Set<EntityRegionScheduledTask> tasks = this.entityTasksByEntity.get(entity);
        if (tasks == null) {
            return;
        }
        for (final EntityRegionScheduledTask task : List.copyOf(tasks)) {
            task.cancel();
        }
    }

    /**
     * {@return every registered wrapper, so a lifecycle cleanup that retires a whole level or the engine
     * itself can cancel them}
     */
    private List<EntityRegionScheduledTask> entityTaskSnapshot() {
        final List<EntityRegionScheduledTask> snapshot = new ArrayList<>();
        for (final Set<EntityRegionScheduledTask> tasks : this.entityTasksByEntity.values()) {
            snapshot.addAll(tasks);
        }
        return snapshot;
    }

    /** {@return the number of registered wrappers, one per scheduled entity task} */
    private int entityTaskCount() {
        int count = 0;
        for (final Set<EntityRegionScheduledTask> tasks : this.entityTasksByEntity.values()) {
            count += tasks.size();
        }
        return count;
    }

    /**
     * Records that a move notification had to migrate a scheduled task to the region that now contains its
     * entity.
     */
    void recordEntityTaskMigration() {
        this.entityTaskMigrationCount.incrementAndGet();
    }

    void publishEntityRemoval(Entity entity) {
        RegionState region = this.regionizer.regionFor(entity);
        if (region == null && entity.level() instanceof ServerLevel level) {
            region = this.regionizer.get(this.layout.regionAt(level, entity.blockPosition()));
        }
        this.cancelEntityTasks(entity);
        this.regionizer.removeEntity(entity);
        if (region != null) {
            this.regionizer.removeIfEmpty(region);
        } else if (entity.level() instanceof ServerLevel level) {
            // Scheduling alone can keep an otherwise untracked entity's region alive.
            final RegionState scheduledRegion = this.regionizer.get(this.layout.regionAt(level, entity.blockPosition()));
            if (scheduledRegion != null) {
                this.regionizer.removeIfEmpty(scheduledRegion);
            }
        }
    }

    void unregisterEntityTask(EntityRegionScheduledTask task) {
        this.entityTasksByEntity.computeIfPresent(task.entity(), (entity, tasks) -> {
            tasks.remove(task);
            return tasks.isEmpty() ? null : tasks;
        });
    }

    /**
     * {@return a snapshot of internal runtime diagnostics}
     */
    @ApiStatus.Internal
    public RegionThreadingDiagnostics diagnostics() {
        return new RegionThreadingDiagnostics(
                this.regionizer.size(),
                this.regionizer.runningRegionCount(),
                this.regionizer.submittedRegionCount(),
                this.regionizer.sectionCount(),
                this.regionizer.loadedChunkCount(),
                this.regionizer.pendingRecalculationCount(),
                this.regionizer.structuralChangeCount(),
                this.regionizer.splitCheckRequestCount(),
                this.regionizer.mergeCheckRequestCount(),
                this.regionizer.entityCount(),
                this.entityTaskCount(),
                this.workerPool.threadCount(),
                this.workerExecutionEnabled,
                this.globalWorkerExecutionEnabled,
                this.entityTickWorkerExecutionEnabled,
                this.blockEntityTickWorkerExecutionEnabled,
                this.scheduledTickWorkerExecutionEnabled,
                this.randomTickWorkerExecutionEnabled,
                this.entityTicksOnWorkers.get(),
                this.skippedEntityTicks.get(),
                this.fallbackEntityTicks.get(),
                this.deferredEntityTicks.get(),
                RegionEntityOwnershipScope.allowedMutationCount(),
                RegionEntityOwnershipScope.rejectedMutationCount(),
                RegionEntityOwnershipScope.allowedQueryCount(),
                RegionEntityOwnershipScope.truncatedQueryCount(),
                RegionEntityOwnershipScope.rejectedQueryCount(),
                this.blockEntityTicksOnWorkers.get(),
                this.skippedBlockEntityTicks.get(),
                this.fallbackBlockEntityTicks.get(),
                this.scheduledTicksOnWorkers.get(),
                this.skippedScheduledTicks.get(),
                this.fallbackScheduledTicks.get(),
                this.randomTicksOnWorkers.get(),
                this.skippedRandomTicks.get(),
                this.fallbackRandomTicks.get(),
                this.globalRegion.isRunning(),
                this.globalRegion.isWorkerSubmitted(),
                this.rejectedWorkerTaskCount.get(),
                this.failedQueuedTaskCount.get(),
                RegionOwnershipViolationHandler.violationCount(),
                this.globalRegion.tickMetrics().tickCount(),
                this.globalRegion.tickMetrics().lastTickDurationNanos(),
                this.regionizer.totalRegionTickCount(),
                this.regionizer.maxRegionLastTickDurationNanos(),
                this.globalRegion.cappedDrainCount() + this.regionizer.cappedDrainCount(),
                this.layout.policy(),
                this.layout.regionChunkSize(),
                this.entityStorageMode,
                this.isScheduledTickWorkerDispatchEffective(),
                this.isRandomTickWorkerDispatchEffective(),
                this.entityTransferJournal.pendingCount(),
                this.entityTransferJournal.rememberedCount(),
                this.entityTransferJournal.rejectedRecordCount(),
                this.legacyDeferredEntityMoveCount.get(),
                this.retriedDeferredEntityMoveCount.get(),
                this.appliedDeferredSectionUpdateCount.get(),
                this.droppedDeferredSectionUpdateCount.get(),
                this.appliedDeferredRemovalCount.get(),
                this.droppedDeferredRemovalCount.get(),
                this.repeatedDeferredRemovalCount.get(),
                RegionOwnershipViolationHandler.entityAdditionRejectionCount(),
                RegionWorkerChunkUnavailableException.refusalCount(),
                this.chunkFallbackEntityTicks.get(),
                this.chunkPreloadRefusedEntityTicks.get(),
                this.regionizer.writeLockAcquisitionCount(),
                this.batchedDeferredEntityMoveCount.get(),
                this.unchangedDeferredEntityMoveCount.get(),
                this.refusedDeferredEntityMoveBatchCount.get(),
                this.entityTaskMigrationCount.get());
    }

    /**
     * Shuts down region threading runtime resources.
     * <p>
     * Serialized and idempotent: the first caller stops new work, waits for the in-progress entity batch
     * (including its lease acquisition and server-thread handoff), then clears the regions and waits for
     * the worker pool to terminate. Lifecycle waits are rejected before any state changes when they are
     * requested from a pool worker, because the worker would be waiting for work only it can complete.
     */
    @ApiStatus.Internal
    public void shutdown() {
        // The worker check must run before this method can block on the engine monitor: a shutdown that
        // already waits for a worker must not deadlock against that worker calling shutdown itself.
        this.rejectLifecycleWaitFromWorker("shut down region threading");
        synchronized (this) {
            if (this.shutdown) {
                return;
            }
            final Lock lifecycleWriteLock = this.lifecycleGate.writeLock();
            lifecycleWriteLock.lock();
            try {
                if (this.shutdown) {
                    return;
                }
                for (final RegionTickBatch active : this.activeTickBatches) {
                    if (active.isExecutingOnCurrentThread()) {
                        throw new IllegalStateException("Cannot shut down region threading from its region tick batch");
                    }
                }
                synchronized (this.admissionLock) {
                    if (this.shutdown) {
                        return;
                    }
                    this.shutdown = true;
                    this.entityTransferJournal.clear();
                    this.pendingDeferredEntityMoves.clear();
                }
                // Close the deferred seams before waiting for the batches: a worker of a batch that still runs
                // must be refused from now on, and the updates and removals that already wait are released.
                this.closeDeferredSeams();
                this.awaitActiveTickBatches(null);
                this.awaitSubmittedRegionTasks(null);
                // Drain work whose worker submission was rejected while the pool was closing. No new worker
                // submissions are allowed now, and all queued handoffs finish on this server thread.
                this.regionizer.forEach(region -> this.drainRegionState(region));
                this.globalRegion.clearTasks();
                for (final EntityRegionScheduledTask task : this.entityTaskSnapshot()) {
                    task.cancel();
                }
                this.entityTasksByEntity.clear();
                this.regionizer.clear();
                this.workerPool.close();
            } finally {
                lifecycleWriteLock.unlock();
            }
        }
    }

    /**
     * Waits for the region tick batch that is currently in progress.
     * <p>
     * The batch is published before any lease acquisition, so this also covers a batch that is still
     * waiting for a region lease. A shutdown triggered from inside the batch cannot wait for itself and
     * is rejected, because retiring a region while its worker still ticks it is never safe.
     *
     * @param level the level whose batch to wait for, or {@code null} for any level
     */
    private void awaitActiveTickBatches(@Nullable ServerLevel level) {
        while (true) {
            RegionTickBatch pending = null;
            for (final RegionTickBatch active : this.activeTickBatches) {
                if (active.isComplete() || level != null && active.level() != level) {
                    continue;
                }
                if (active.isExecutingOnCurrentThread()) {
                    throw new IllegalStateException("Cannot retire region state while its region tick batch is running on the current thread");
                }
                pending = active;
                break;
            }
            if (pending == null) {
                return;
            }
            pending.awaitCompletion();
        }
    }

    private void awaitSubmittedRegionTasks(@Nullable ServerLevel level) {
        final RegionTickBarrier barrier = level == null ? null : levelBarrier(level);
        this.regionizer.forEach(region -> {
            if (level == null || region.coordinate().level() == level) {
                // A submitted worker owns the region until its finally block clears the marker and
                // releases the lease. Waiting for both prevents queue retirement under that worker.
                this.awaitRegionQuiescent(region, barrier);
            }
        });
    }

    private static Throwable runBatchCompletion(@Nullable Throwable previousFailure, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (previousFailure == null) {
                return throwable;
            }
            previousFailure.addSuppressed(throwable);
        }
        return previousFailure;
    }

    private void ensureRunning() {
        if (this.shutdown) {
            throw new IllegalStateException("Region threading engine is shut down");
        }
    }

    private void rejectLifecycleWaitFromWorker(String action) {
        if (this.workerPool.isWorkerThread(Thread.currentThread())) {
            throw new IllegalStateException("Cannot " + action + " from a region worker thread");
        }
    }

    /**
     * Runs a void admission while holding the lock that shutdown uses to publish its flag.
     */
    private void runAdmitted(Runnable admission) {
        synchronized (this.admissionLock) {
            this.ensureRunning();
            this.runBeforeRegionAdmissionHook();
            admission.run();
        }
    }

    /**
     * Resolves a value admission while holding the lock that shutdown uses to publish its flag.
     */
    private <T> T callAdmitted(Supplier<T> admission) {
        synchronized (this.admissionLock) {
            this.ensureRunning();
            this.runBeforeRegionAdmissionHook();
            return admission.get();
        }
    }

    private void runBeforeRegionAdmissionHook() {
        final Runnable hook = this.beforeRegionAdmission;
        if (hook != null) {
            hook.run();
        }
    }

    /**
     * Marks a region drain worker under the lifecycle gate, or records that an accepted drain is already
     * active and has to be awaited.
     * <p>
     * The idle check, the shutdown flag and {@link RegionState#tryMarkWorkerSubmitted()} are evaluated
     * while the lifecycle read lock is held. Cleanup holds the write lock until it finished clearing, so a
     * drain either publishes its submission before cleanup starts (and cleanup waits for it) or observes
     * the cleared/dead region and rejects the activation.
     */
    private void trySubmitRegionDrain(RegionState region, long nowNanos, RegionTaskBatch submissions, List<RegionState> alreadyActive, boolean tryLifecycleGateOnly) {
        final Runnable hook = this.beforeRegionDrainActivation;
        if (hook != null) {
            hook.run();
        }
        if (!this.acquireLifecycleReadLock(tryLifecycleGateOnly)) {
            return;
        }
        try {
            if (this.shutdown || region.isDead()) {
                return;
            }
            if (region.isWorkerSubmitted() || region.isRunning()) {
                // An accepted worker that has not started yet is not a rejection. Await it instead of
                // draining its queue here, which would run the snapshot twice and advance the queue
                // tick twice.
                alreadyActive.add(region);
                return;
            }
            if (region.tryMarkWorkerSubmitted()) {
                submissions.add("region drain " + region.coordinate(), () -> this.runSubmittedRegionTick(region, nowNanos));
            } else if (region.isWorkerSubmitted() || region.isRunning()) {
                // Another drain won the submission between the idle check and the CAS; await its worker
                // instead of returning while that accepted drain is still running.
                alreadyActive.add(region);
            }
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
    }

    /**
     * Acquires the lifecycle read lock for a drain activation.
     * <p>
     * A pool worker or an entity batch thread must never block here: cleanup may hold the write lock
     * while waiting for work only that thread can complete. Its activation is rejected instead and the
     * caller returns to finish its current task.
     */
    private boolean acquireLifecycleReadLock(boolean tryOnly) {
        final Lock readLock = this.lifecycleGate.readLock();
        if (tryOnly) {
            return readLock.tryLock();
        }
        readLock.lock();
        return true;
    }

    /**
     * {@return true when the current thread executes a region tick batch that a cleanup may be waiting
     * for}
     */
    private boolean isExecutingTickBatchOnCurrentThread() {
        for (final RegionTickBatch active : this.activeTickBatches) {
            if (active.isExecutingOnCurrentThread()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return true when the current thread already holds a region lease for the tick it is executing}
     * <p>
     * A cleanup may wait for that lease, so the thread must not block on the lifecycle write lock. Its
     * drain or entity tick is rejected (or falls back to inline execution) instead.
     */
    private boolean isExecutingRegionTickOnCurrentThread() {
        return this.currentContext() instanceof BasicRegionThreadingContext basicContext
                && basicContext.kind() == RegionThreadingContext.Kind.REGION
                && basicContext.regionState != null
                && basicContext.regionState.isOwnedByCurrentThread();
    }

    /**
     * Acquires a region lease for a drain while the lifecycle cleanup is excluded. The read lock is
     * released before the queued tasks run, so a cleanup requested from a task cannot wait for a read
     * lock held by the caller; it observes the running lease and waits for it instead.
     */
    private boolean drainRegionStateWithLifecycleGate(RegionState region, boolean tryOnly) {
        if (!this.acquireLifecycleReadLock(tryOnly)) {
            return false;
        }
        final boolean acquired;
        try {
            if (this.shutdown || region.isDead()) {
                return false;
            }
            acquired = region.tryMarkRunning();
        } finally {
            this.lifecycleGate.readLock().unlock();
        }
        if (!acquired) {
            return false;
        }
        this.runRegionState(region);
        return true;
    }

    private boolean drainRegionState(RegionState region) {
        if (region.isDead() || !region.tryMarkRunning()) {
            return false;
        }
        this.runRegionState(region);
        return true;
    }

    private void runRegionState(RegionState region) {
        final RegionCoordinate coordinate = region.coordinate();
        try {
            region.beginOwnership();
            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, coordinate.level(), coordinate, region, null), () -> this.failedQueuedTaskCount.addAndGet(region.taskQueue().drain()));
        } finally {
            region.endOwnership();
            region.clearRunning();
        }
    }

    /**
     * Runs one worker submission created by the level drain and clears its submitted marker in every case.
     */
    private void runSubmittedRegionTick(RegionState region, long nowNanos) {
        try {
            this.tickRunner.runIfDue(region, nowNanos, () -> this.drainRegionState(region));
        } finally {
            region.clearWorkerSubmitted();
            this.regionizer.removeIfEmpty(region);
        }
    }

    private void runWithContext(BasicRegionThreadingContext context, Runnable task) {
        final RegionThreadingContext previousContext = CURRENT_CONTEXT.get();
        // A region context owns its neighbor update collection state, so a worker can neither clear nor
        // extend the update stack of another region or of the server thread. The server thread keeps the
        // shared state outside a region context, including for main-thread handoff work.
        final boolean regionScoped = context.kind() == RegionThreadingContext.Kind.REGION
                || (context.kind() == RegionThreadingContext.Kind.GLOBAL_REGION && this.workerPool.isWorkerThread(Thread.currentThread()));
        final RegionNeighborUpdateScope previousNeighborScope = regionScoped
                ? RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope())
                : null;
        CURRENT_CONTEXT.set(context);
        try {
            task.run();
        } finally {
            CURRENT_CONTEXT.set(previousContext);
            if (regionScoped) {
                RegionNeighborUpdateScope.exit(previousNeighborScope);
            }
        }
    }

    private record BasicRegionThreadingContext(
            RegionThreadingContext.Kind kind,
            @Nullable ServerLevel nullableLevel,
            @Nullable RegionCoordinate regionCoordinate,
            @Nullable RegionState regionState,
            @Nullable RegionEntityTickBatch.Group entityTickGroup) implements RegionThreadingContext {
        @Override
        public Optional<ServerLevel> level() {
            return Optional.ofNullable(this.nullableLevel);
        }
    }
}
