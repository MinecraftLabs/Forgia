/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.GlobalRegionScheduler;
import net.neoforged.neoforge.server.threading.RegionScheduler;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Native NeoForge region threading engine shell.
 * <p>
 * This class is intentionally small until the Folia regionizer and tick loop are ported.
 * <p>
 * The engine owns the server-thread side of every deferred effect of a region worker. For the entity tick
 * slice that is the section publication and target chunk acquisition of a move, the level callback of a
 * removal and the admission of a new entity, applied in that order at the barrier of one entity batch
 * (section updates, removals, admissions, fallback ticks, server tasks, ownership publication). The
 * admission is the one step that changes a caller-visible contract: on a native worker whose lease covers
 * the entity's position, {@code ServerLevel.addFreshEntity} answers <b>"accepted for this tick"</b> instead
 * of "already in the world". The entity is not visible to the level for the rest of the worker tick and the
 * barrier admits it afterwards through the unmodified vanilla path; an admission the barrier cannot apply is
 * <b>dropped</b>, which loses the entity, and is counted in
 * {@link RegionThreadingDiagnostics#deferredEntityAdditionsDropped()} next to the applied and recorded
 * admissions. Entity construction events ({@code EntityEvent.EntityConstructing}, {@code EntityEvent.Size})
 * therefore fire on the worker thread; that is a documented compatibility exposure, not a defect of the
 * admission.
 */
public final class RegionThreadingEngine implements RegionThreading {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGION_LEASE_ATTEMPTS = 4;
    /**
     * Upper bound of the server thread's wait for a native global drain to release the global region. The
     * drain itself is bounded by the per-drain task limits, so this only guards against a worker that never
     * returns instead of letting the tick loop hang forever.
     */
    private static final long GLOBAL_REGION_WAIT_NANOS = 50_000_000L;
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
    /** Temporary switch of the activation diagnostics of the self-driven entity tick. */
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
    private final boolean chunkTickWorkerExecutionEnabled;
    private final boolean spawnWorkerExecutionEnabled;
    /**
     * Whether a slice leases several regions into one worker task. Off means one task per region, exactly like
     * before packing existed.
     */
    private final boolean packedRegionLeasesEnabled;
    /** Packed worker tasks this engine submitted. */
    private final AtomicLong packedTasks = new AtomicLong();
    /** Regions the packed tasks of this engine hold; divided by {@link #packedTasks} it is the regions per task. */
    private final AtomicLong packedRegions = new AtomicLong();
    /**
     * Packed acquisitions that could not take every region of their pack and released what they took. The
     * affected regions were leased one by one instead; a growing count means the pool and the region work of the
     * previous tick overlap, not that a region was skipped.
     */
    private final AtomicLong packedLeaseFallbacks = new AtomicLong();
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
    /** Regions the self-driven entity tick leased and submitted one worker task for. */
    private final AtomicLong entityTickRegionsOnWorkers = new AtomicLong();
    /**
     * Regions whose entity tick could not be enumerated safely (no lease, no chunk rectangle, a retired
     * region) and whose entities were therefore ticked on the server thread instead of being dropped.
     */
    private final AtomicLong fallbackEntityTickRegions = new AtomicLong();
    /**
     * Regions with a ticking entity whose own chunks are all outside the vanilla entity ticking range. Such a
     * region is still submitted: its entity body runs the despawn check and skips the tick, exactly like the
     * vanilla pass would.
     */
    private final AtomicLong entityTickRegionsWithoutEligibleChunk = new AtomicLong();
    /** Entities of a region whose position left the region's chunk rectangle, ticked on the server thread. */
    private final AtomicLong entityTicksOutsideRegion = new AtomicLong();
    /** Always-ticking entities (players) a region handed to the server thread instead of ticking on a worker. */
    private final AtomicLong serverThreadOnlyEntityTicks = new AtomicLong();
    /** Entity tick list entries the region index could not take; they keep the server-thread path. */
    private final AtomicLong unindexedEntityTickStarts = new AtomicLong();
    /** Level ticks that fell back to the per-entity entity plan because the level's region index is unavailable. */
    private final AtomicLong entityTickIndexFallbacks = new AtomicLong();
    /**
     * Levels whose entity tick index was seeded from the level's authoritative tick list.
     */
    private final Set<Object> seededEntityTickLevels = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /**
     * Chunk-load answers of the current loaded-chunk generation of each level.
     * <p>
     * The pre-load margin of the entity tick asks the same chunk neighborhood every tick, and a chunk changes
     * its loaded state only while the level's loaded chunk set changes. The answers are therefore kept until
     * the level reports a different loaded chunk count; both directions of a stale answer are safe - a stale
     * "loaded" is refused by the worker chunk guard and ticked on the server thread, a stale "not loaded"
     * keeps the entity on the server thread - so the cache only decides where an entity ticks, never whether
     * it ticks.
     */
    private final Map<ServerLevel, ChunkLoadAnswers> chunkLoadAnswers = new ConcurrentHashMap<>();
    /**
     * The global/region phase state of the tick of each level.
     * <p>
     * The state exists for every level whose tick was opened by the engine bridge, so the state machine can
     * assert that no region batch is in flight while the level's global statements run. A level without a
     * tick entry - direct engine use from a mechanics test or a benchmark - reads as
     * {@link LevelTickPhase#GLOBAL} and counts its region phases as work outside a level tick; see
     * {@link LevelTickPhase}.
     */
    private final Map<ServerLevel, LevelTickPhaseState> levelTickPhases = new ConcurrentHashMap<>();
    /**
     * The cross-region mailbox of each level that has one. A level without an entry has no mailbox and pays
     * one map lookup per level tick entry and per region phase end; the entry is created by the first post.
     */
    private final Map<ServerLevel, RegionMailbox> mailboxes = new ConcurrentHashMap<>();
    /*
     * The mailbox counters belong to the engine, not to one mailbox: a mailbox dies with its level, and the
     * messages it dropped on the close have to stay observable in the diagnostics after that.
     */
    private final AtomicLong mailboxAcceptedMessages = new AtomicLong();
    private final AtomicLong mailboxDeliveredMessages = new AtomicLong();
    private final AtomicLong mailboxRefusedFullMessages = new AtomicLong();
    private final AtomicLong mailboxRefusedClosedMessages = new AtomicLong();
    private final AtomicLong mailboxDroppedRetiredMessages = new AtomicLong();
    private final AtomicLong mailboxDroppedOnCloseMessages = new AtomicLong();
    private final AtomicLong mailboxDeliveryFailures = new AtomicLong();

    /**
     * The captured border updates of each level. A region worker records an update that targets another region
     * here instead of applying it; the server thread drains the queue after the worker phase and runs the
     * same-tick fixpoint. The map is empty for a level without border updates and for a server whose switch is
     * off.
     */
    private final Map<ServerLevel, RegionBorderUpdates> borderUpdates = new ConcurrentHashMap<>();
    /**
     * Whether the installed engine captures cross-region border updates. It is the engine's copy of
     * {@code enableRegionThreadingBorderUpdates}, taken at construction so a batch never re-reads the config.
     */
    private volatile boolean borderUpdatesEnabled = RegionThreadingConfig.borderUpdatesEnabled();
    /** Test override of the fixpoint bound; a negative value reads the level's vanilla limit. */
    private volatile int borderUpdateBudgetForTesting = -1;
    /** Entries one capture queue of a level admits; the production bound unless a test lowers it. */
    private volatile int borderUpdateCapacity = RegionBorderUpdates.MAX_ENTRIES;
    /** Border updates a worker recorded instead of applying. */
    private final AtomicLong borderUpdatesRecorded = new AtomicLong();
    /** Border updates a propagation pass applied on the target region's worker. */
    private final AtomicLong borderUpdatesApplied = new AtomicLong();
    /** Border updates the fixpoint bound pushed to the target region's next tick through the mailbox. */
    private final AtomicLong borderUpdatesDelayed = new AtomicLong();
    /** Border updates neither applied nor delayed, i.e. a closed mailbox or a retired target. */
    private final AtomicLong borderUpdatesDropped = new AtomicLong();
    /** Full fixpoint passes the server thread ran after a worker phase. */
    private final AtomicLong borderUpdatePasses = new AtomicLong();
    /** Region propagation groups the fixpoint re-ran. */
    private final AtomicLong borderUpdateRegionsRerun = new AtomicLong();
    /** Worst number of passes one worker phase needed. */
    private final AtomicLong borderUpdateWorstPasses = new AtomicLong();
    /** Border updates the capture bound refused, which were handed to the next-tick fallback. */
    private final AtomicLong borderUpdatesOverflowed = new AtomicLong();
    /** Block writes a worker recorded for the region that owns the written position. */
    private final AtomicLong borderBlockWritesRecorded = new AtomicLong();
    /** Block writes a propagation pass applied on the target region's worker. */
    private final AtomicLong borderBlockWritesApplied = new AtomicLong();
    /** Block writes the capture bound refused at the call site, i.e. the caller's write was not applied. */
    private final AtomicLong borderBlockWritesOverflowed = new AtomicLong();
    /** Block writes whose target chunk was no longer loaded when the fixpoint grouped them. */
    private final AtomicLong borderBlockWritesDropped = new AtomicLong();
    /** Reads of a position the same region wrote outside its lease during the same lease. */
    private final AtomicLong readAfterDeferredBlockWrite = new AtomicLong();
    /**
     * The regions of each level that read a chunk of another region since their last version check.
     * <p>
     * A region adds itself on the first foreign read after a check and the fixpoint removes it when it checks
     * the region, so the fixpoint only walks the regions that actually read across a border. A level that never
     * does has no entry at all.
     */
    private final Map<ServerLevel, Set<RegionState>> regionsWithForeignReads = new ConcurrentHashMap<>();
    /** Foreign chunk reads a region recorded, i.e. one per chunk and region and pass. */
    private final AtomicLong foreignChunkReads = new AtomicLong();
    /** Stable reads of a versioned chunk that no region owns. */
    private final AtomicLong readOfUnownedChunk = new AtomicLong();
    /** Native-worker requests refused because their chunk has no seqlock version. */
    private final AtomicLong readOfVersionlessChunk = new AtomicLong();
    /** Fixpoint checks in which a region observed a changed version of a foreign chunk it read. */
    private final AtomicLong foreignReadInvalidatedPasses = new AtomicLong();
    /** Region propagation groups the fixpoint re-ran because a foreign read was invalidated. */
    private final AtomicLong foreignReadRegionReruns = new AtomicLong();
    /** Written positions a region dropped because the re-run seed bound of the region was reached. */
    private final AtomicLong foreignReadSeedsDropped = new AtomicLong();
    /**
     * Whether a changed foreign chunk version re-runs the propagation of the reader. The mechanics seam of the
     * race tests turns it off to show the stale read the re-run repairs.
     */
    private volatile boolean foreignReadRerunEnabled = true;
    /** Block events a region worker recorded for the server thread's block event phase. */
    private final AtomicLong deferredBlockEvents = new AtomicLong();
    /** Block entity reads a region group made against a quiescent foreign region. */
    private final AtomicLong foreignBlockEntityReads = new AtomicLong();

    /** One level's chunk-load answers and the loaded chunk count they were read at. */
    private static final class ChunkLoadAnswers {
        private final int loadedChunkCount;
        private final Long2ByteMap answers = new Long2ByteOpenHashMap();
        /** The derived one-chunk-margin answer per chunk, so a chunk's neighborhood is decided once. */
        private final Long2ByteMap neighborhoodAnswers = new Long2ByteOpenHashMap();

        private ChunkLoadAnswers(int loadedChunkCount) {
            this.loadedChunkCount = loadedChunkCount;
            this.answers.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
            this.neighborhoodAnswers.defaultReturnValue(NEIGHBORHOOD_UNKNOWN);
        }
    }

    private final AtomicLong blockEntityTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedBlockEntityTicks = new AtomicLong();
    private final AtomicLong fallbackBlockEntityTicks = new AtomicLong();
    /** Chunks the plan did not hand over because they are not block-ticking (or the tick rate is frozen). */
    private final AtomicLong nonTickingBlockEntityChunks = new AtomicLong();
    /** Chunks the plan did not hand over because their entities are still loading. */
    private final AtomicLong entitiesLoadingBlockEntityChunks = new AtomicLong();
    /** Chunks the plan did not hand over because they are no longer loaded, so no snapshot exists. */
    private final AtomicLong missingBlockEntityChunks = new AtomicLong();
    /** Chunks whose snapshot went stale between the plan and the worker, so the server thread ticked them. */
    private final AtomicLong staleBlockEntityChunkSnapshots = new AtomicLong();
    private final AtomicLong scheduledTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedScheduledTicks = new AtomicLong();
    private final AtomicLong fallbackScheduledTicks = new AtomicLong();
    private final AtomicLong chunkTicksOnWorkers = new AtomicLong();
    private final AtomicLong skippedChunkTicks = new AtomicLong();
    private final AtomicLong fallbackChunkTicks = new AtomicLong();
    /** Chunks the plan kept on the server thread because a random tick of them could leave their region. */
    private final AtomicLong boundaryChunkTicks = new AtomicLong();
    /** Chunks the plan kept on the server thread because a neighboring chunk is not loaded. */
    private final AtomicLong unloadedNeighborhoodChunkTicks = new AtomicLong();
    /** Chunk ticks a worker abandoned because the chunk source refused a chunk it needed. */
    private final AtomicLong truncatedChunkTicks = new AtomicLong();
    /** Chunks collected after their batch already ran, so the server thread ticked them inline. */
    private final AtomicLong lateChunkTicks = new AtomicLong();
    /** Regions whose lease could not be acquired, so their chunks ticked on the server thread. */
    private final AtomicLong unleasedChunkTicks = new AtomicLong();
    /** Lightning strikes a chunk tick worker rolled and the server thread resolved at the barrier. */
    private final AtomicLong chunkTickLightningStrikes = new AtomicLong();
    /**
     * Deferred light rechecks and block update publications the barrier of each slice applied, indexed by
     * {@link RegionTickSlice#ordinal()}.
     */
    private final AtomicLong[] appliedDeferredBlockUpdates = newSliceCounters();
    /** Deferred light rechecks and block updates of each slice that were never applied, indexed by slice. */
    private final AtomicLong[] droppedDeferredBlockUpdates = newSliceCounters();
    /**
     * Units of each slice that were refused before their body because the deferred block-update budget of their
     * batch was exhausted, indexed by slice.
     */
    private final AtomicLong[] refusedDeferredBlockUpdates = newSliceCounters();
    /**
     * Tick failures the worker groups of each slice recorded for the server thread, indexed by slice. A failure is
     * counted once, on the worker that caught it, and reported once by
     * {@link RegionTickBatch#reportTickFailures()}.
     */
    private final AtomicLong[] workerTickFailures = newSliceCounters();
    /**
     * Chunks the plan kept on the server thread because the deferred update budget of the batch was exhausted,
     * so their block writes would have had nowhere to be recorded.
     */
    private final AtomicLong deferredUpdateBudgetChunkTicks = new AtomicLong();
    /**
     * Level ticks whose chunk tick stayed on the server thread because the level keeps the legacy shared entity
     * section storage instead of per-region buckets.
     */
    private final AtomicLong nonPartitionedChunkTicks = new AtomicLong();
    /** Chunks whose natural spawning ran on the worker of their region. */
    private final AtomicLong spawnChunksOnWorkers = new AtomicLong();
    /**
     * Chunks the plan kept on the server thread, by reason. Every one of them spawns exactly once, from the
     * vanilla level-wide spawn state, so none of them is dropped.
     */
    private final AtomicLong gateRefusedSpawnChunks = new AtomicLong();
    private final AtomicLong unloadedNeighborhoodSpawnChunks = new AtomicLong();
    private final AtomicLong deferredUpdateBudgetSpawnChunks = new AtomicLong();
    private final AtomicLong lateSpawnChunks = new AtomicLong();
    /** Regions whose lease could not be acquired, so their chunks spawned on the server thread. */
    private final AtomicLong unleasedSpawnChunks = new AtomicLong();
    /** Chunks whose worker spawn body a chunk source refusal truncated after its first spawn. */
    private final AtomicLong truncatedSpawnChunks = new AtomicLong();
    /** Chunks a closed level identity kept out of the batch, so they never spawned. */
    private final AtomicLong skippedSpawnChunks = new AtomicLong();
    /**
     * Mob categories a spawn state refused because the level-wide mob cap was reached. Counted on the worker for
     * the region that owns the chunk, so it measures cap refusals, not missing spawn attempts.
     */
    private final AtomicLong capRefusedSpawns = new AtomicLong();
    /**
     * Spawn attempts that could not be recorded as an admission, so no entity was created. The barrier's own
     * refusals are counted in {@link #droppedDeferredAdditionCount}.
     */
    private final AtomicLong refusedSpawnAdditions = new AtomicLong();
    private final AtomicBoolean entityTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean blockEntityTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean scheduledTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean chunkTickWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean chunkTickStorageNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean spawnWorkersNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean spawnStorageNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean entityTickFallbackNoticeLogged = new AtomicBoolean();
    private final AtomicBoolean scheduledTickFallbackNoticeLogged = new AtomicBoolean();
    /**
     * Declares who may touch the vanilla entity section/index storage while this engine's workers run.
     * <p>
     * Entity sections, their class index and the by-id/by-uuid lookup are still the single-threaded
     * vanilla structures, so the server thread keeps them and a worker is rejected from the legacy entity
     * query surface and from every guarded storage mutation. Entity ticks may only leave the server thread
     * with {@link RegionEntityStorageMode#REGION_OWNED}, which is what the production configuration selects
     * when the entity tick slice is enabled on a fixed layout
     * ({@link RegionEntityStorageMode#forConfiguration(boolean, RegionLayout)}): the physical section
     * partitions of that layout are then the region-private storage of a lease, and every storage effect of
     * a worker tick is applied by the batch barrier on the server thread.
     */
    private final RegionEntityStorageMode entityStorageMode;
    /**
     * The per-type declarations of the entity tick, consulted before an entity body starts.
     * <p>
     * The registry ships the vanilla declarations of {@link #declareVanillaServerThreadOnlyEntities()}: the
     * types whose own tick reaches a mutation the entity lease does not own. The vanilla POI declarations are
     * gone because the level's POI storage is partitioned per region, and
     * {@link #serverThreadOnlyEntity(Class, String)} is the hook of the mod compatibility layer.
     */
    private final RegionEntityTickPolicy entityTickPolicy = new RegionEntityTickPolicy();
    /**
     * Portal transitions a worker recorded for the batch barrier because the destination search reads the
     * {@code PoiManager} of the destination level. Each one is applied on the server thread after the batch,
     * so the transition of a worker-ticked entity never reaches that storage from a worker.
     */
    private final AtomicLong deferredPortalTransitionCount = new AtomicLong();
    /** Portal transitions the barrier applied on the server thread. */
    private final AtomicLong appliedPortalTransitionCount = new AtomicLong();
    /**
     * Portal transitions a barrier did not apply: the engine shut down, the level identity closed or the
     * entity was removed or left the level before the barrier. The entity keeps its portal state, so the
     * transition happens on a later tick instead of being lost silently.
     */
    private final AtomicLong refusedPortalTransitionCount = new AtomicLong();
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
     * Mechanics-test seam of the per-chunk block-entity admission cache. Production keeps the cache enabled;
     * the equivalence test of the cache compares the cached plan against the uncached evaluation over the
     * same chunk and ticker states.
     */
    private volatile boolean blockEntityAdmissionCacheEnabled = true;
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
     * Random source of the block-entity tick group that executes on the current thread. It is installed once
     * per group for the whole worker tick loop, so {@link #currentGroupRandom()} answers with it for every
     * ticker of the group and the block-entity access gate opens for the block entities of its region; both
     * are removed again after the loop.
     */
    private final ThreadLocal<RandomSource> currentBlockEntityTickRandom = new ThreadLocal<>();
    /**
     * Random source of the scheduled tick group that executes on the current thread. It is only set for
     * the duration of a worker group, so {@link #scheduledTickRandom()} returns {@code null} everywhere
     * else and the vanilla level random stays authoritative outside a batch.
     */
    private final ThreadLocal<RandomSource> currentScheduledTickRandom = new ThreadLocal<>();
    /** The propagation decision to revisit on a foreign read, even when the decision writes nothing. */
    private final ThreadLocal<BlockPos> currentPropagationOrigin = new ThreadLocal<>();
    /**
     * Server-owned mutation queue of the region tick group executing on the current thread. It is only
     * set for the duration of a worker group; the server thread drains it after the phase, so a worker
     * never mutates either {@code LevelTicks} instance concurrently.
     * <p>
     * Scheduled block/fluid ticks, random ticks and block-entity ticks all mutate {@code LevelTicks} through
     * this queue, so the slices never mutate the scheduling structures concurrently.
     */
    private final ThreadLocal<Queue<Runnable>> currentRegionTickMutations = new ThreadLocal<>();
    /**
     * Random source of the chunk tick group that executes on the current thread. It is installed for the tick
     * of one chunk, so {@link #chunkTickRandom()} answers with the random source of that chunk and
     * {@code level.random} draws of the chunk body resolve it instead of the shared vanilla source.
     */
    private final ThreadLocal<RandomSource> currentChunkTickRandom = new ThreadLocal<>();
    /**
     * Position selection of the chunk tick of the current thread.
     * <p>
     * {@code Level.getBlockRandomPos} selects a position from the level-wide {@code randValue} sequence, which
     * one worker cannot share with another. A chunk tick therefore installs the chunk's own random source here
     * and the level selects the position from it, so the selection of a chunk is per-chunk deterministic.
     */
    private final ThreadLocal<RandomSource> currentChunkTickPositionRandom = new ThreadLocal<>();
    /**
     * The strikes a chunk tick worker rolled and may not resolve itself.
     * <p>
     * Only set inside a worker group, so the level defers a strike there and resolves it inline on the server
     * thread.
     */
    private final ThreadLocal<Queue<BlockPos>> currentChunkTickLightning = new ThreadLocal<>();
    /**
     * Random source of the spawn body that executes on the current thread. It is installed for the spawn body of
     * one chunk, so {@link #currentGroupRandom()} answers with the random source of that chunk and every
     * {@code level.random} draw of the body resolves it instead of the shared vanilla source.
     */
    private final ThreadLocal<RandomSource> currentSpawnRandom = new ThreadLocal<>();
    /**
     * Per-chunk answers of the spawn body that executes on the current thread, or {@code null} outside a spawn
     * group.
     * <p>
     * The vanilla spawn body asks the level whether a candidate position's chunk may spawn and which players are
     * close enough for spawning, both of which read server-owned structures. Inside a spawn group the level reads
     * this snapshot instead, so a worker never touches the entity manager's visibility map or the chunk map.
     */
    private final ThreadLocal<RegionSpawnNeighborhood> currentSpawnNeighborhood = new ThreadLocal<>();
    /**
     * Entries one region tick batch may reserve for the units it admits to its deferred block updates.
     * Production keeps {@link RegionDeferredBlockUpdates#MAX_ENTRIES}, which is the bound every slice derives its
     * admission count from ({@link RegionTickSlice#reservationPerUnit()}); the mechanics tests lower it to
     * observe the refusal without admitting thousands of units.
     */
    private volatile int deferredBlockUpdateBudget = RegionDeferredBlockUpdates.MAX_ENTRIES;
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
     * The part of {@link #droppedDeferredSectionUpdateCount} whose entity was removed, left the level or was
     * never added to it between the move and the barrier.
     * <p>
     * Such an update has nothing left to publish: the removal retires the section membership of the entity
     * before the barrier runs, so dropping the publication is the correct outcome and not a lost effect. The
     * subset is counted on its own so a soak can tell the expected drops of a dying mob apart from a
     * publication that failed, which is the drop that really loses a move.
     */
    private final AtomicLong droppedDeferredSectionUpdateForRemovedEntityCount = new AtomicLong();
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
     * Deferred admissions a worker recorded for a barrier. One record is one riding graph, so a vehicle and
     * its passengers count once.
     */
    private final AtomicLong recordedDeferredAdditionCount = new AtomicLong();
    /**
     * Deferred admissions a barrier admitted on the server thread through the unmodified
     * {@code ServerLevel.addEntity}. One applied admission is one whole riding graph in the level.
     */
    private final AtomicLong appliedDeferredAdditionCount = new AtomicLong();
    /**
     * Deferred admissions that did not complete: the engine shut down (including the admissions a shut-down
     * batch releases without applying), the level identity closed, the entity was removed or left the level
     * between the record and the barrier, a member of the graph was already in the level, or the vanilla path
     * refused a member. A dropped admission loses the entity - it was never in the level and nothing retries
     * it - so it is always counted.
     */
    private final AtomicLong droppedDeferredAdditionCount = new AtomicLong();
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
     * This constructor keeps entity storage on the server thread. A production server passes the mode of
     * {@link RegionEntityStorageMode#forConfiguration(boolean, RegionLayout)}, which selects region-owned
     * storage when the entity tick slice is enabled.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionPolicy regionPolicy) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, regionPolicy, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /**
     * Creates an engine with an explicit region layout policy, every bounded tick slice and an entity
     * storage mode.
     * <p>
     * The policy selects the size-3 default fixed layout; production passes the configured
     * {@link RegionLayout} instead, so the engine, its regionizer and the physical section partitions of
     * the levels it ticks share one fixed region size.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionPolicy regionPolicy, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, RegionLayout.of(regionPolicy), entityStorageMode);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice and an entity storage mode.
     * <p>
     * This is the production constructor: the layout carries both the policy and the fixed region edge
     * length, so nothing downstream has to guess the size. Natural spawning stays on the server thread; a caller
     * that wants the spawning slice uses
     * {@link #RegionThreadingEngine(int, boolean, boolean, boolean, boolean, boolean, boolean, boolean, RegionLayout, RegionEntityStorageMode)}.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, false, layout, entityStorageMode);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice, the natural spawning slice and
     * an entity storage mode.
     * <p>
     * The layout carries both the policy and the fixed region edge length, so nothing downstream has to guess the
     * size. The spawning slice is the only slice that admits entities a region does not store itself, because
     * vanilla picks a spawn position without regard to the region border; see {@link RegionSpawnPlan}.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, spawnWorkerExecutionEnabled, false, false, layout, entityStorageMode);
    }

    /**
     * Creates an engine with the light-load dispatch of the worker pool and of the region leases selected.
     * <p>
     * {@code persistentWorkers} hands a task to the slot of one persistent worker instead of a shared executor
     * queue, and {@code packedRegionLeases} lets one task tick several regions of the same slice. Both are off by
     * default, and off means the unchanged behaviour of every slice.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, boolean persistentWorkers, boolean packedRegionLeases, RegionLayout layout, RegionEntityStorageMode entityStorageMode) {
        this.layout = layout;
        this.regionizer = new ThreadedRegionizer(this.layout);
        this.workerPool = new RegionWorkerPool(workerThreads > 0 ? workerThreads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1), persistentWorkers);
        this.workerExecutionEnabled = workerExecutionEnabled;
        this.globalWorkerExecutionEnabled = globalWorkerExecutionEnabled;
        this.entityTickWorkerExecutionEnabled = entityTickWorkerExecutionEnabled;
        this.blockEntityTickWorkerExecutionEnabled = blockEntityTickWorkerExecutionEnabled;
        this.scheduledTickWorkerExecutionEnabled = scheduledTickWorkerExecutionEnabled;
        this.chunkTickWorkerExecutionEnabled = chunkTickWorkerExecutionEnabled;
        this.spawnWorkerExecutionEnabled = spawnWorkerExecutionEnabled;
        this.packedRegionLeasesEnabled = packedRegionLeases;
        this.entityStorageMode = entityStorageMode;
        this.declareVanillaServerThreadOnlyEntities();
        RegionOwnershipViolationHandler.reset();
        RegionEntityOwnershipScope.resetCounters();
    }

    /**
     * Declares the vanilla entity types whose tick reaches a mutation the entity lease does not own.
     * <p>
     * A declaration is a property of the type, not of a workload: the guard sites of the vanilla classes are
     * audited against the tick of the class, and a type whose own tick can reach a rejected mutation keeps
     * the server thread instead of failing the group of its region at the first such tick. The declarations
     * are deliberately narrow - a type that only reaches the granted funnel stays on the worker:
     * <ul>
     * <li>{@code Slime} (and therefore {@code MagmaCube}): {@code Slime.remove} creates the child slimes of a
     * dead slime <em>before</em> it reaches the removal funnel, so the split is an entity addition inside a
     * removal callback the lease does not grant. A slime that dies in its own tick - fire, drowning, a fall,
     * another mob's attack that the same worker resolves - would otherwise abort the group and crash the
     * server.</li>
     * <li>{@code FishingHook}: its tick discards the hook in five places (the owner is gone, the hook hits a
     * block, the hook is in lava, the reel-in ends), and {@code discard} funnels into
     * {@code FishingHook.remove}, which returns the caught item and the rod damage to the owner before it
     * reaches the funnel.</li>
     * </ul>
     * A vehicle ({@code Boat}, {@code ChestBoat}, {@code AbstractMinecartContainer}) and a
     * {@code BlockAttachedEntity} keep the worker: their own tick never removes or re-anchors them, so their
     * guard sites are only reached from the server thread that destroys or moves them.
     */
    private void declareVanillaServerThreadOnlyEntities() {
        this.entityTickPolicy.serverThreadOnly(Slime.class, "slime_split_on_removal");
        this.entityTickPolicy.serverThreadOnly(FishingHook.class, "fishing_hook_remove");
    }

    /**
     * Creates an engine with an explicit region layout and every bounded tick slice, keeping entity storage
     * on the server thread.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, RegionLayout layout) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, layout, RegionEntityStorageMode.MAIN_THREAD_ONLY);
    }

    /**
     * Creates an engine with an explicit region layout, every bounded tick slice and the natural spawning slice,
     * keeping entity storage on the server thread.
     */
    public RegionThreadingEngine(int workerThreads, boolean workerExecutionEnabled, boolean globalWorkerExecutionEnabled, boolean entityTickWorkerExecutionEnabled, boolean blockEntityTickWorkerExecutionEnabled, boolean scheduledTickWorkerExecutionEnabled, boolean chunkTickWorkerExecutionEnabled, boolean spawnWorkerExecutionEnabled, RegionLayout layout) {
        this(workerThreads, workerExecutionEnabled, globalWorkerExecutionEnabled, entityTickWorkerExecutionEnabled, blockEntityTickWorkerExecutionEnabled, scheduledTickWorkerExecutionEnabled, chunkTickWorkerExecutionEnabled, spawnWorkerExecutionEnabled, layout, RegionEntityStorageMode.MAIN_THREAD_ONLY);
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

    /**
     * Declares that the tick of {@code type} and of every subclass of it may only run on the server thread.
     * <p>
     * The entity slices consult the declaration before an entity body starts and tick a declared entity on
     * the server thread in the same tick instead: in the self-driven slice through the server-thread fallback
     * of its batch, in the per-entity slice by not accepting the entity, which keeps the vanilla inline tick.
     * The declaration is counted per reason and reported by the region threading status, and it covers the
     * riding stack, because a passenger is ticked through the vehicle that carries it.
     * <p>
     * This is the hook of the mod compatibility layer: a mod declares the types whose tick reaches a shared
     * server-owned structure instead of being refused a region worker as a whole. A type that is never
     * declared keeps the worker, so this call only ever moves work back to the server thread.
     *
     * @param type   the declared entity class
     * @param reason the short reason token of the declaration, e.g. the shared structure the tick reaches;
     *               it is the key of the per reason counter and the token the status output reports
     */
    public void serverThreadOnlyEntity(Class<? extends Entity> type, String reason) {
        this.entityTickPolicy.serverThreadOnly(type, reason);
    }

    /**
     * {@return the per-type entity tick policy of this engine}
     */
    RegionEntityTickPolicy entityTickPolicy() {
        return this.entityTickPolicy;
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

    /**
     * {@return true when the current region context may read a block entity of another region}
     * <p>
     * A comparator reads the analog output of the block behind it and a piston reads the moving block entity
     * in front of it, and that block can belong to the next region. A region that holds no execution lease
     * cannot start a group before the current phase ends, because the server thread leases every region of a
     * phase before it submits the phase's groups. Its block entities are therefore quiescent for the duration
     * of the current group, and the read is safe. A region that is running, or has a worker submission
     * pending, keeps the vanilla {@code null} answer: only its own worker may read it.
     */
    @ApiStatus.Internal
    public boolean isForeignRegionBlockEntityReadable(ServerLevel level, BlockPos pos) {
        if (this.shutdown) {
            return false;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionState == null
                || !basicContext.regionState.isOwnedByCurrentThread()) {
            return false;
        }
        final RegionCoordinate coordinate = this.layout.regionAt(level, pos);
        if (coordinate.equals(basicContext.regionCoordinate)) {
            return false;
        }
        final RegionState target = this.regionizer.get(coordinate);
        final boolean readable = target == null || (!target.isDead() && !target.isRunning() && !target.isWorkerSubmitted());
        if (readable) {
            this.foreignBlockEntityReads.incrementAndGet();
        }
        return readable;
    }

    /**
     * Records the version of one chunk a region worker is about to read through the chunk source.
     * <p>
     * A chunk of the caller's own region is not recorded: it is mutated by the caller only, so its reads cannot
     * race another region's worker. Every other chunk is remembered with the version it has now, and the border
     * fixpoint re-runs the caller's propagation if that version changed before the check. The read itself stays
     * lock-free; this method only adds a map entry.
     * <p>
     * A chunk no region owns cannot change under the read, and a chunk without a version - a proto chunk of
     * another status - cannot be verified and is refused without replay. These cases have separate counters.
     *
     * @param chunkX  the chunk x of the chunk that is handed to the worker
     * @param chunkZ  the chunk z of the chunk that is handed to the worker
     * @param version the version of the chunk, or a negative value when the chunk has none
     */
    @ApiStatus.Internal
    public void recordForeignChunkRead(ServerLevel level, int chunkX, int chunkZ, long version) {
        if (!this.borderUpdatesEnabled) {
            return;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionState == null) {
            return;
        }
        final RegionState region = basicContext.regionState;
        if (version < 0L) {
            this.recordVersionlessChunkRead();
            throw RegionWorkerChunkUnavailableException.forVersionlessRead(chunkX, chunkZ);
        }
        if (this.ownsChunk(level, chunkX, chunkZ, basicContext, region)) {
            return;
        }
        if (this.regionizer.get(this.layout.regionAt(level, chunkX, chunkZ)) == null) {
            // No region owns the chunk, so no region mutates it and no version can change under the read. The read
            // keeps the behaviour it had before this check and is counted.
            this.readOfUnownedChunk.incrementAndGet();
            return;
        }
        final BlockPos origin = this.currentPropagationOrigin.get();
        if (origin != null) {
            region.recordRerunSeed(origin.asLong());
            this.currentPropagationOrigin.remove();
        }
        if (region.recordForeignChunkRead(ChunkPos.asLong(chunkX, chunkZ), version)) {
            this.foreignChunkReads.incrementAndGet();
            this.regionsWithForeignReads.computeIfAbsent(level, key -> ConcurrentHashMap.newKeySet()).add(region);
        }
    }

    /**
     * {@return true when the chunk of {@code chunkX}/{@code chunkZ} belongs to the region the current thread
     * holds the lease of}
     * <p>
     * The fixed layout answers from the chunk coordinate alone, so the check of a read of the caller's own
     * region costs two divisions and two comparisons. A dynamic region may be merged and then owns more than one
     * layout cell, so only the regionizer can answer for it.
     */
    private boolean ownsChunk(ServerLevel level, int chunkX, int chunkZ, BasicRegionThreadingContext context, RegionState region) {
        if (!region.isOwnedByCurrentThread()) {
            return false;
        }
        final RegionCoordinate coordinate = context.regionCoordinate;
        if (coordinate != null && coordinate.level() == level && this.layout.isFixed()
                && this.layout.regionX(chunkX) == coordinate.regionX()
                && this.layout.regionZ(chunkZ) == coordinate.regionZ()) {
            return true;
        }
        return this.regionizer.get(this.layout.regionAt(level, chunkX, chunkZ)) == region;
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
            // The global region is owned by another thread for a bounded drain: the native global worker
            // holds it while it runs the due global tasks. The server thread's global work - the server tick
            // events, which must be posted every tick - is never dropped, so the server thread waits for that
            // drain to finish instead of reporting a violation. A worker never waits: the server thread may
            // be waiting for that worker's own batch, so waiting there would deadlock the tick, and the work
            // is refused and counted instead.
            if (RegionWorkerScope.isNativeWorkerThread() || !this.awaitGlobalRegionIdle()
                    || !this.globalRegion.tryMarkRunning()) {
                RegionOwnershipViolationHandler.handle("Global region is already running on another thread");
                return;
            }
        }
        try {
            this.runInGlobalRegionOwned(task);
        } finally {
            this.globalRegion.clearRunning();
        }
    }

    /**
     * Waits for the bounded drain of the native global worker to release the global region.
     * <p>
     * The drain is bounded by {@code regionThreadingMaxImmediateTasksPerDrain} and
     * {@code regionThreadingMaxScheduledTasksPerDrain}, and the worker that drains never waits for the server
     * thread, so this wait is bounded by the drain itself. The bound exists only so a broken worker cannot
     * hang the tick loop: a drain that does not finish inside it is reported as an ownership violation and the
     * caller keeps its failure path.
     *
     * @return {@code true} when the global region became free
     */
    private boolean awaitGlobalRegionIdle() {
        final long deadline = System.nanoTime() + GLOBAL_REGION_WAIT_NANOS;
        while (this.globalRegion.isRunning()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
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
     * {@return the phase of the tick of {@code level}}
     * <p>
     * A level whose tick was not opened through {@link #beginLevelTick(ServerLevel)} reads as
     * {@link LevelTickPhase#GLOBAL}: no region batch of it is in flight, so its global statements are the only
     * thing that could run.
     */
    @ApiStatus.Internal
    public LevelTickPhase levelTickPhase(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        return state == null ? LevelTickPhase.GLOBAL : state.phase();
    }

    /**
     * Opens the global phase of the tick of {@code level} and drains the level's global mailbox.
     * <p>
     * Called on the server thread at the head of the level tick, before the queued region work of the tick and
     * before every vanilla statement. The global messages a worker posted since the last global phase are
     * delivered here, in posting order, so no region batch can be in flight while they run. A level tick that
     * throws skips its close; the next entry counts the leftover segment as abandoned and continues from it.
     */
    @ApiStatus.Internal
    public void beginLevelTick(ServerLevel level) {
        this.levelTickPhaseState(level).beginGlobalPhase();
        this.drainGlobalMessages(level);
    }

    /**
     * Closes the global phase of the tick of {@code level}.
     */
    @ApiStatus.Internal
    public void endLevelTick(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        if (state != null) {
            state.endGlobalPhase();
        }
    }

    /**
     * Enters the region phase of one slice batch of {@code level}.
     * <p>
     * The batch that submits region work owns the phase from here until {@link #endRegionPhase(ServerLevel)}:
     * exactly one batch of a level is in flight, and none is in flight while the level's global statements run.
     */
    @ApiStatus.Internal
    public void beginRegionPhase(ServerLevel level) {
        this.levelTickPhaseState(level).beginRegionPhase();
    }

    /**
     * Leaves the region phase, which returns the level to its global phase and delivers the global messages the
     * workers of the batch posted.
     */
    @ApiStatus.Internal
    public void endRegionPhase(ServerLevel level) {
        final LevelTickPhaseState state = this.levelTickPhaseState(level);
        state.endRegionPhase();
        if (!state.isRegionPhase()) {
            this.drainGlobalMessages(level);
            // The outermost region phase of the level is over and no worker is running, so the POI storage can
            // publish what its regions wrote during the phase without racing a reader of the live sections.
            RegionPoiStorages.publishLevel(level);
        }
    }

    /**
     * {@return the mailbox of {@code level}}, creating it on first use.
     * <p>
     * The mailbox of a shut-down engine is closed: a post through it is refused and counted instead of being
     * queued for a tick that never comes.
     */
    @ApiStatus.Internal
    public RegionMailbox mailbox(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.computeIfAbsent(level,
                key -> new RegionMailbox(this, key, RegionMailbox.DEFAULT_QUEUE_CAPACITY, RegionMailbox.DEFAULT_QUEUE_CAPACITY));
        if (this.shutdown) {
            mailbox.close();
        }
        return mailbox;
    }

    /**
     * Rejects a write of the state the global phase owns from a region phase.
     * <p>
     * The rule and the state it covers are the ownership table of {@link LevelTickPhase}. The check is two
     * volatile reads, so it is cheap enough for the single accessors it guards. It is an ownership assertion,
     * not a storage guard: the configured {@code regionThreadingOwnershipViolationMode} decides whether the
     * caller is interrupted, and the violation is always counted.
     *
     * @param level     the level whose state is written
     * @param operation the operation that writes it, named in the report
     */
    @ApiStatus.Internal
    public void assertGlobalStateWrite(ServerLevel level, String operation) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            RegionOwnershipViolationHandler.handle("Region phase wrote global state: " + operation);
            return;
        }
        final LevelTickPhaseState state = this.levelTickPhases.get(level);
        if (state != null && state.phase() == LevelTickPhase.REGION) {
            RegionOwnershipViolationHandler.handle("Region phase wrote global state: " + operation);
        }
    }

    /** {@return the region state of {@code coordinate}, or {@code null} when the coordinate has no live region} */
    @Nullable
    RegionState regionStateFor(RegionCoordinate coordinate) {
        return this.regionizer.get(coordinate);
    }

    /**
     * Runs one region slice batch of {@code level}: the level leaves its global phase for the lifetime of the
     * batch and returns to it when the batch and its server-thread wait are over.
     * <p>
     * Every submission of a region slice batch goes through here, so the phase covers exactly the submitted
     * worker work plus the barrier - the interval in which workers may run - and closes before the batch's
     * server-thread handoff, which is not region work.
     */
    private void runRegionBatch(ServerLevel level, Runnable batch) {
        this.beginRegionPhase(level);
        Throwable failure = null;
        try {
            batch.run();
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            try {
                // The worker phase of the batch is over: no worker holds a lease anymore, so the server thread
                // can hand the captured cross-region updates to their target regions and repeat until the
                // circuit stops changing or the vanilla chained-update bound is reached. A failure of the batch
                // is not masked by a failure of the fixpoint.
                try {
                    this.runBorderUpdateFixpoint(level);
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    } else {
                        failure.addSuppressed(throwable);
                    }
                }
            } finally {
                this.endRegionPhase(level);
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    /**
     * Applies the border updates of the worker phase that just ended and re-runs the affected regions until no
     * border update remains or the bound is reached.
     * <p>
     * The fixpoint is a sequence of passes. A pass groups the pending updates by target region and runs one
     * propagation-only group per region, one region after another, so the second region of a pass sees the
     * writes of the first one. A propagation group only applies the captured vanilla updates and runs their
     * neighbor cascades; it ticks no entity, block entity, chunk or spawn body. The cascades record further
     * cross-region updates into the same queue, which become the next pass.
     * <p>
     * <strong>Bound.</strong> The bound is the level's {@code max-chained-neighbor-updates} limit, the number
     * vanilla uses for one cascade (default {@code 1000000}). A negative configured value means unlimited in
     * vanilla; the fixpoint then uses {@link SharedConstants#MAX_CHAINED_NEIGHBOR_UPDATES} so it can never spin.
     * The bound applies to the border updates applied by the passes and to the number of passes, because every
     * pass applies at least one update. The updates that do not fit are posted to the target region's mailbox
     * and applied at the start of its next group, so the circuit converges one tick later instead of losing
     * an update.
     * <p>
     * The capture queue's own bound ({@link RegionBorderUpdates#MAX_ENTRIES}) is an overflow of the memory of
     * one pass, not of the vanilla limit: the records past it are posted to the same next-tick fallback.
     * <p>
     * <strong>Foreign reads.</strong> A propagation group reads block states, fluids and block entities of the
     * neighbouring regions, and a chunk of another region can change between two of those reads. Every region
     * records the version of each foreign chunk it read ({@link RegionState#foreignChunkReads()}), and the
     * version of every position it wrote ({@link RegionState#rerunSeeds()}). The check runs once for the worker
     * phase before the first pass and once per region after its propagation group. A region whose observed
     * version changed re-runs its propagation - the updates it applied, plus a re-evaluation of the positions it
     * wrote - in the next pass. The re-run is bounded by the same pass bound and falls back to the same mailbox,
     * so a region that keeps invalidating converges one tick later instead of spinning.
     */
    private void runBorderUpdateFixpoint(ServerLevel level) {
        if (!this.borderUpdatesEnabled) {
            return;
        }
        // The regions whose reads of the worker phase observed a chunk version that changed before the phase
        // ended. They re-run their propagation in the first pass even when no border update is pending, because
        // the repair of a stale read is not an update the fixpoint's own queue could carry.
        final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidated = this.invalidatedForeignReads(level);
        final RegionBorderUpdates updates = this.borderUpdates.get(level);
        final List<RegionBorderUpdates.BorderUpdate> pending = updates == null ? List.of() : updates.drain();
        final List<RegionBorderUpdates.BorderUpdate> overflow = updates == null ? List.of() : updates.drainOverflow();
        if (pending.isEmpty() && overflow.isEmpty() && invalidated.isEmpty()) {
            return;
        }
        if (this.regionizer.isLevelClosed(level) || this.shutdown) {
            this.borderUpdatesDropped.addAndGet(pending.size() + overflow.size());
            if (updates != null) {
                updates.discard();
            }
            return;
        }
        this.delayBorderUpdates(level, overflow);

        final int budget = this.borderUpdateBudget(level);
        final RegionTickBarrier barrier = borderFixpointBarrier(level);
        int passes = 0;
        int applied = 0;
        List<RegionBorderUpdates.BorderUpdate> deferred = new ArrayList<>();
        Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> reruns = invalidated;
        if (!reruns.isEmpty()) {
            this.foreignReadInvalidatedPasses.incrementAndGet();
        }
        List<RegionBorderUpdates.BorderUpdate> nextPending = pending;
        while ((!nextPending.isEmpty() || !reruns.isEmpty()) && passes < budget && applied < budget) {
            passes++;
            final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> byRegion = this.groupBorderUpdatesByRegion(level, nextPending);
            for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> rerun : reruns.entrySet()) {
                final RegionState region = rerun.getKey();
                if (region.isDead()) {
                    this.borderUpdatesDropped.addAndGet(rerun.getValue().size());
                    continue;
                }
                byRegion.merge(region, rerun.getValue(), (existing, added) -> {
                    existing.addAll(added);
                    return existing;
                });
            }
            reruns = new java.util.LinkedHashMap<>();
            final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> ranThisPass = new java.util.LinkedHashMap<>();
            for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> entry : byRegion.entrySet()) {
                final List<RegionBorderUpdates.BorderUpdate> regionUpdates = entry.getValue();
                if (applied >= budget) {
                    deferred.addAll(regionUpdates);
                    continue;
                }
                final int allowed = Math.min(regionUpdates.size(), budget - applied);
                final List<RegionBorderUpdates.BorderUpdate> apply = regionUpdates.subList(0, allowed);
                if (allowed < regionUpdates.size()) {
                    deferred.addAll(regionUpdates.subList(allowed, regionUpdates.size()));
                }
                if (this.runBorderPropagationPass(level, entry.getKey(), apply, barrier)) {
                    applied += apply.size();
                    this.borderUpdateRegionsRerun.incrementAndGet();
                    ranThisPass.put(entry.getKey(), apply);
                } else {
                    deferred.addAll(apply);
                }
            }
            // A region whose propagation read a foreign chunk that changed under the read re-runs in the next
            // pass, so its cascade sees the state the chunk has now. The check also drains the region's read set
            // and its written positions, so both are per pass.
            boolean passInvalidated = false;
            for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> entry : ranThisPass.entrySet()) {
                final List<RegionBorderUpdates.BorderUpdate> payload = this.invalidatedForeignRead(level, entry.getKey(), entry.getValue());
                if (payload == null) {
                    continue;
                }
                passInvalidated = true;
                reruns.put(entry.getKey(), payload);
                this.foreignReadRegionReruns.incrementAndGet();
            }
            if (passInvalidated) {
                this.foreignReadInvalidatedPasses.incrementAndGet();
            }
            // The cascades of this pass recorded new border updates. They run in the next pass, after every
            // region of this pass saw the writes of the regions before it.
            nextPending = updates == null ? List.of() : updates.drain();
        }
        if (!nextPending.isEmpty()) {
            deferred.addAll(nextPending);
        }
        for (final List<RegionBorderUpdates.BorderUpdate> leftover : reruns.values()) {
            deferred.addAll(leftover);
        }
        this.borderUpdatePasses.addAndGet(passes);
        this.borderUpdateWorstPasses.accumulateAndGet(passes, Math::max);
        if (!deferred.isEmpty()) {
            this.delayBorderUpdates(level, deferred);
        }
    }

    /**
     * Checks the foreign chunks every region of {@code level} read during the worker phase that just ended.
     *
     * @return the propagation each invalidated region has to re-run, in first-seen order, or an empty map when
     *         no region read a foreign chunk that changed
     */
    private Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidatedForeignReads(ServerLevel level) {
        final Set<RegionState> regions = this.regionsWithForeignReads.get(level);
        if (regions == null || regions.isEmpty()) {
            return Map.of();
        }
        Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidated = null;
        for (final RegionState region : List.copyOf(regions)) {
            regions.remove(region);
            if (region.isDead()) {
                this.discardForeignReads(region);
                continue;
            }
            final List<RegionBorderUpdates.BorderUpdate> payload = this.invalidatedForeignRead(level, region, List.of());
            if (payload != null) {
                if (invalidated == null) {
                    invalidated = new java.util.LinkedHashMap<>();
                }
                invalidated.put(region, payload);
                this.foreignReadRegionReruns.incrementAndGet();
            }
        }
        return invalidated == null ? Map.of() : invalidated;
    }

    /**
     * Checks the foreign chunks {@code region} read since its last check and builds the propagation it has to
     * re-run, then forgets both the reads and the reevaluation origins of the region.
     * <p>
     * The re-run payload is the propagation the region already ran - the updates it applied in this pass - plus a
     * re-evaluation of propagation origins and written positions, as self-directed neighbour updates. A block that decided its
     * state from a foreign read therefore decides it again, against the state the read reports now, without
     * repeating any entity, block-entity, chunk or spawn work.
     *
     * @param applied the border updates the region applied in the checked pass, empty for the worker phase
     * @return the propagation to re-run, or {@code null} when no observed version changed
     */
    @Nullable
    private List<RegionBorderUpdates.BorderUpdate> invalidatedForeignRead(ServerLevel level, RegionState region,
            List<RegionBorderUpdates.BorderUpdate> applied) {
        final it.unimi.dsi.fastutil.longs.Long2LongMap reads = region.foreignChunkReads();
        final boolean changed = this.foreignReadRerunEnabled && this.foreignReadVersionsChanged(level, reads);
        final List<RegionBorderUpdates.BorderUpdate> payload = changed ? this.rerunPayload(applied, region.rerunSeeds()) : null;
        final int dropped = region.clearRerunSeeds();
        if (dropped > 0) {
            this.foreignReadSeedsDropped.addAndGet(dropped);
        }
        region.clearForeignChunkReads();
        return payload == null || payload.isEmpty() ? null : payload;
    }

    /**
     * {@return true when the version of any chunk in {@code reads} differs from the version the region observed}
     * <p>
     * A chunk that is no longer loaded cannot be shown to be unchanged, so its read counts as changed. A level
     * without a chunk source - a mechanics test level - verifies nothing and keeps the vanilla behaviour.
     */
    private boolean foreignReadVersionsChanged(ServerLevel level, it.unimi.dsi.fastutil.longs.Long2LongMap reads) {
        if (reads.isEmpty()) {
            return false;
        }
        final ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource == null) {
            return false;
        }
        boolean changed = false;
        for (final it.unimi.dsi.fastutil.longs.Long2LongMap.Entry read : reads.long2LongEntrySet()) {
            if (currentChunkVersion(chunkSource, read.getLongKey()) != read.getLongValue()) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * {@return the version of the chunk with {@code chunkKey}, or {@code -1} when the chunk is not a loaded
     * {@link LevelChunk}}
     */
    private static long currentChunkVersion(@Nullable ServerChunkCache chunkSource, long chunkKey) {
        if (chunkSource == null) {
            return -1L;
        }
        final LevelChunk chunk = chunkSource.getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        return chunk == null ? -1L : chunk.chunkVersion();
    }

    /**
     * Builds the propagation a re-run of {@code region} repeats: the updates it applied, plus a self-directed
     * neighbour update for every written position no applied update already targets.
     */
    private List<RegionBorderUpdates.BorderUpdate> rerunPayload(List<RegionBorderUpdates.BorderUpdate> applied,
            it.unimi.dsi.fastutil.longs.LongArrayList seeds) {
        final List<RegionBorderUpdates.BorderUpdate> payload = new ArrayList<>(applied);
        if (seeds.isEmpty()) {
            return payload;
        }
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet covered = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(applied.size());
        for (final RegionBorderUpdates.BorderUpdate update : applied) {
            covered.add(update.pos().asLong());
        }
        for (final long seed : seeds) {
            if (covered.add(seed)) {
                payload.add(new RegionBorderUpdates.SelfUpdate(BlockPos.of(seed)));
            }
        }
        return payload;
    }

    /** Forgets the foreign reads and the written positions of {@code region}, counting the dropped seeds. */
    private void discardForeignReads(RegionState region) {
        final int dropped = region.clearRerunSeeds();
        if (dropped > 0) {
            this.foreignReadSeedsDropped.addAndGet(dropped);
        }
        region.clearForeignChunkReads();
    }

    /**
     * Groups the pending border updates by the live region that has to apply them, in first-seen order.
     * <p>
     * An update whose target region does not exist or is retired is counted as dropped: no region will ever
     * run it, and handing it to a different region would apply it to the wrong state. A block write whose
     * target chunk is no longer loaded is dropped the same way and counted separately: the target region could
     * only apply it by loading a chunk, which no worker may do while the server thread waits for the barrier.
     */
    private Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> groupBorderUpdatesByRegion(ServerLevel level,
            List<RegionBorderUpdates.BorderUpdate> updates) {
        final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> byRegion = new java.util.LinkedHashMap<>();
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            if (update instanceof RegionBorderUpdates.BlockWrite && !level.isLoaded(update.pos())) {
                this.borderUpdatesDropped.incrementAndGet();
                this.borderBlockWritesDropped.incrementAndGet();
                continue;
            }
            final RegionState region = this.regionizer.get(this.layout.regionAt(level, update.pos()));
            if (region == null || region.isDead()) {
                this.borderUpdatesDropped.incrementAndGet();
                continue;
            }
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(update);
        }
        return byRegion;
    }

    /**
     * Runs one propagation-only pass of one region and waits for it.
     *
     * @return {@code true} when the region was leased and the pass ran; {@code false} when the region retired
     *         or the level closed before the lease, so the caller has to fall back to the next tick
     */
    private boolean runBorderPropagationPass(ServerLevel level, RegionState region, List<RegionBorderUpdates.BorderUpdate> updates,
            RegionTickBarrier barrier) {
        final RegionBorderPropagationBatch batch = new RegionBorderPropagationBatch(level, this.deferredBlockUpdateBudget);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication != TickBatchPublication.PUBLISHED) {
            return false;
        }
        batch.markExecutingOnCurrentThread();
        Throwable failure = null;
        boolean ran = false;
        try {
            if (this.acquireRegionLease(region, barrier)) {
                ran = true;
                this.addLeasedGroup(region, () -> batch.addGroup(region, this.borderPropagationGroup(batch, region, updates)));
                this.beginRegionPhase(level);
                try {
                    batch.submitAndAwait(this.workerPool, barrier);
                } catch (Throwable throwable) {
                    failure = throwable;
                } finally {
                    this.endRegionPhase(level);
                }
            }
        } catch (Throwable throwable) {
            if (failure == null) {
                failure = throwable;
            } else {
                failure.addSuppressed(throwable);
            }
        } finally {
            failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
            failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
            batch.finish();
            this.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
        return ran;
    }

    /**
     * The worker body of one border-propagation group.
     * <p>
     * The group holds the lease of the target region, enters the same region execution context and neighbor
     * update scope as a tick group, and applies the captured vanilla updates in recording order. It installs
     * the scheduled-tick mutation queue so a cascade that schedules a tick defers it to the server thread, but
     * it ticks nothing itself.
     */
    private Runnable borderPropagationGroup(RegionBorderPropagationBatch batch, RegionState region,
            List<RegionBorderUpdates.BorderUpdate> updates) {
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = batch.level();
        return () -> {
            final long start = System.nanoTime();
            try {
                region.beginOwnership();
                this.currentRegionTickMutations.set(batch.regionTickMutations());
                try {
                    final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                    try {
                        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                            for (final RegionBorderUpdates.BorderUpdate update : updates) {
                                this.currentPropagationOrigin.set(update.pos());
                                try {
                                    update.apply(level);
                                } catch (Throwable throwable) {
                                    if (!RegionWorkerChunkUnavailableException.mustNotReplay(throwable)) {
                                        throw throwable;
                                    }
                                } finally {
                                    this.currentPropagationOrigin.remove();
                                }
                                this.borderUpdatesApplied.incrementAndGet();
                                if (update instanceof RegionBorderUpdates.BlockWrite) {
                                    this.borderBlockWritesApplied.incrementAndGet();
                                }
                            }
                        });
                    } finally {
                        RegionNeighborUpdateScope.exit(previousNeighbors);
                    }
                } finally {
                    this.currentRegionTickMutations.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("border propagation " + coordinate, duration);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * {@return the fixpoint bound of {@code level}, i.e. vanilla's own chained-update limit}
     */
    private int borderUpdateBudget(ServerLevel level) {
        if (this.borderUpdateBudgetForTesting >= 0) {
            return this.borderUpdateBudgetForTesting;
        }
        final int configured = level.maxChainedNeighborUpdates();
        return configured >= 0 ? configured : SharedConstants.MAX_CHAINED_NEIGHBOR_UPDATES;
    }

    /**
     * {@return the barrier the propagation passes wait on}
     * <p>
     * Production uses the level's chunk-source barrier, exactly like a tick batch. A mechanics test whose mock
     * level has no chunk source parks until the group finishes.
     */
    private static RegionTickBarrier borderFixpointBarrier(ServerLevel level) {
        final RegionTickBarrier barrier = levelBarrier(level);
        return barrier != null ? barrier : condition -> {
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
        };
    }

    /**
     * Hands the border updates the fixpoint could not apply this tick to the target regions' mailboxes.
     * <p>
     * A message is delivered at the start of the target region's next group, on the worker that holds the
     * lease, and only records the updates again; the fixpoint of that batch then applies them. The message is
     * therefore one tick later, never lost. A target region that retired, or a mailbox that is full or closed,
     * drops the updates and counts them.
     */
    private void delayBorderUpdates(ServerLevel level, List<RegionBorderUpdates.BorderUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        final Map<RegionCoordinate, List<RegionBorderUpdates.BorderUpdate>> byTarget = new java.util.LinkedHashMap<>();
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            final RegionState region = this.regionizer.get(this.layout.regionAt(level, update.pos()));
            if (region == null || region.isDead()) {
                this.borderUpdatesDropped.incrementAndGet();
                continue;
            }
            byTarget.computeIfAbsent(region.coordinate(), key -> new ArrayList<>()).add(update);
        }
        if (byTarget.isEmpty()) {
            return;
        }
        final RegionMailbox mailbox = this.mailbox(level);
        for (final Map.Entry<RegionCoordinate, List<RegionBorderUpdates.BorderUpdate>> entry : byTarget.entrySet()) {
            final List<RegionBorderUpdates.BorderUpdate> message = List.copyOf(entry.getValue());
            final RegionMailbox.PostResult result = mailbox.post(entry.getKey(), () -> this.recordDelayedBorderUpdates(level, message));
            if (result == RegionMailbox.PostResult.ACCEPTED) {
                this.borderUpdatesDelayed.addAndGet(message.size());
            } else {
                this.borderUpdatesDropped.addAndGet(message.size());
            }
        }
    }

    /**
     * Re-queues the updates a mailbox delivered at the start of the target region's next group, so the fixpoint
     * of that batch applies them on the target region's worker.
     */
    private void recordDelayedBorderUpdates(ServerLevel level, List<RegionBorderUpdates.BorderUpdate> updates) {
        final RegionBorderUpdates queue = this.borderQueue(level);
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            if (queue.record(update) == RegionBorderUpdates.RecordResult.OVERFLOW) {
                this.borderUpdatesOverflowed.incrementAndGet();
            }
        }
    }

    /**
     * Wraps one region group body so the messages the mailbox queued to {@code region} are delivered before it,
     * on the thread that runs the group.
     */
    private Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body) {
        return () -> this.deliverRegionMessages(level, region, body);
    }

    /**
     * Delivers the messages queued to {@code region} and then runs {@code body}, at the start of the group the
     * worker executes for that region.
     */
    void deliverRegionMessages(ServerLevel level, RegionState region, Runnable body) {
        final RegionMailbox mailbox = this.mailboxes.get(level);
        if (mailbox != null) {
            mailbox.deliverRegion(region.coordinate(), region);
        }
        body.run();
    }

    /** Delivers the global messages of {@code level} on the current thread, in posting order. */
    private void drainGlobalMessages(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.get(level);
        if (mailbox != null) {
            mailbox.drainGlobal();
        }
    }

    private LevelTickPhaseState levelTickPhaseState(ServerLevel level) {
        return this.levelTickPhases.computeIfAbsent(level, key -> new LevelTickPhaseState());
    }

    void recordMailboxAccepted() {
        this.mailboxAcceptedMessages.incrementAndGet();
    }

    void recordMailboxDelivered() {
        this.mailboxDeliveredMessages.incrementAndGet();
    }

    void recordMailboxRefusedFull() {
        this.mailboxRefusedFullMessages.incrementAndGet();
    }

    void recordMailboxRefusedClosed() {
        this.mailboxRefusedClosedMessages.incrementAndGet();
    }

    void recordMailboxDroppedRetired(long messages) {
        this.mailboxDroppedRetiredMessages.addAndGet(messages);
    }

    void recordMailboxDroppedOnClose(long messages) {
        this.mailboxDroppedOnCloseMessages.addAndGet(messages);
    }

    /** {@return the delivery failures counted so far, after counting one more} */
    long recordMailboxDeliveryFailure() {
        return this.mailboxDeliveryFailures.incrementAndGet();
    }

    /** Closes and forgets the mailbox of {@code level}, dropping and counting every queued message. */
    private void closeLevelMailbox(ServerLevel level) {
        final RegionMailbox mailbox = this.mailboxes.remove(level);
        if (mailbox != null) {
            mailbox.close();
        }
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
        if (!this.seedEntityTickIndex(level)) {
            // The level's authoritative tick list could not be seeded into the region index (a level without
            // a tick list, or a retiring identity). The per-region index is then not a complete view of the
            // level's entities, so this tick keeps the per-entity collection instead of dropping entities the
            // index does not know.
            this.entityTickIndexFallbacks.incrementAndGet();
            return new RegionEntityTickPlan(this, level, inEntityTickingRange, barrier, false);
        }
        return RegionEntityTickPlan.selfDriven(this, level, inEntityTickingRange, barrier);
    }

    /**
     * Starts the per-entity collection of the current {@code level} tick.
     * <p>
     * This is the fallback mode of the entity slice: the server thread walks the level's entity tick list and
     * the plan collects the entities whose region can own them. The production entry point
     * {@link #beginEntityTickPlan} selects it only for a level whose region entity tick index cannot be
     * seeded; the region entity tick mechanics are tested through it directly.
     */
    @org.jetbrains.annotations.VisibleForTesting
    public RegionEntityTickPlan beginPerEntityTickPlan(ServerLevel level, Predicate<Entity> inEntityTickingRange, RegionTickBarrier barrier) {
        return new RegionEntityTickPlan(this, level, inEntityTickingRange, barrier, false);
    }

    /**
     * Seeds {@code level}'s region entity tick index from the level's authoritative entity tick list, once.
     * <p>
     * The index is fed by the entity tick list hooks, so a level that starts ticking entities after the engine
     * was installed - including a level that existed before the bridge was installed - needs one pass over the
     * list to publish its members. The seed runs on the server thread outside the entity pass, publishes the
     * region mapping of every entity and marks its index entry as ticking, exactly like the hook would have.
     *
     * @return {@code false} when the level has no entity tick list to seed from
     */
    private boolean seedEntityTickIndex(ServerLevel level) {
        if (this.seededEntityTickLevels.contains(level)) {
            return true;
        }
        if (!level.forEachEntityInTickListForRegionIndex(entity -> {
            if (!this.regionizer.onEntityTickStarted(level, entity)) {
                this.unindexedEntityTickStarts.incrementAndGet();
            }
        })) {
            return false;
        }
        this.seededEntityTickLevels.add(level);
        return true;
    }

    /**
     * Publishes that the level's entity tick list now contains {@code entity}, so the entity is indexed in the
     * region its mapping names and the region's worker ticks it.
     * <p>
     * Called by the entity tick list hook on the server thread. A server that does not execute entity ticks on
     * region workers keeps the vanilla tick list only.
     */
    @ApiStatus.Internal
    public void entityTickStarted(ServerLevel level, Entity entity) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown) {
            return;
        }
        if (!this.regionizer.onEntityTickStarted(level, entity)) {
            this.unindexedEntityTickStarts.incrementAndGet();
        }
    }

    /**
     * Publishes that the level's entity tick list no longer contains {@code entity}.
     * <p>
     * Called by the entity tick list hook on the server thread. The entity keeps its region mapping and its
     * index entry; only the tick membership is cleared.
     */
    @ApiStatus.Internal
    public void entityTickEnded(ServerLevel level, Entity entity) {
        if (!this.entityTickWorkerExecutionEnabled || this.shutdown) {
            return;
        }
        this.regionizer.onEntityTickEnded(level, entity);
    }

    /**
     * Ticks the entities of every active region on its owning region worker and waits for the batch.
     * <p>
     * This is the self-driven form of {@link #tickEntities(ServerLevel, List, RegionTickBarrier)}: the server
     * thread does not collect entities. It activates the level's regions (one chunk eligibility snapshot per
     * region), publishes the batch, leases every region, submits one worker task per region, waits at the
     * barrier and runs the handoff. A worker enumerates the entity tick index of the region it leased, so the
     * server thread never walks the level's entity tick list.
     * <p>
     * A region whose entity tick cannot be enumerated - no chunk rectangle, a retired region, a lease that
     * cannot be acquired - is counted, and its entities are ticked on the server thread after the batch
     * instead of being dropped. An entity of a leased region whose position left the region rectangle, whose
     * chunk guard would need an unloaded chunk, or that only the server thread may tick (a player) is handed
     * back to the server thread the same way.
     */
    void tickSelfDrivenEntities(ServerLevel level, RegionTickBarrier barrier) {
        final List<RegionState> regions = new ArrayList<>();
        this.regionizer.collectActiveEntityTickRegions(level, regions);
        if (regions.isEmpty()) {
            return;
        }

        final RegionEntityTickBatch batch = new RegionEntityTickBatch(level, this.deferredBlockUpdateBudget);
        // The chunk-load answers are shared by every region of the pass and by every following pass of the
        // same loaded chunk generation, so the level's loaded chunks are asked once instead of once per
        // neighborhood.
        final ChunkLoadAnswers chunkAnswers = this.chunkLoadAnswers(level);
        final Long2ByteMap chunkLoaded = chunkAnswers.answers;
        final Long2ByteMap neighborhoodLoaded = chunkAnswers.neighborhoodAnswers;
        final List<RegionEntityTickActivation> activations = new ArrayList<>(regions.size());
        for (final RegionState region : regions) {
            final RegionEntityTickActivation activation = RegionEntityTickActivation.activate(level, region, this.layout, chunkLoaded, neighborhoodLoaded);
            if (activation == null) {
                // The region's chunk rectangle cannot be decided on the server thread: a region without a
                // loaded chunk section, or a merged rectangle beyond the snapshot bound. Its entities are
                // ticked on the server thread instead of being dropped.
                this.fallbackEntityTickRegions.incrementAndGet();
                this.addRegionFallback(batch, region);
                continue;
            }
            if (activation.eligibleChunkCount() == 0) {
                // Nothing in the region may be ticked this tick, but the vanilla body still runs the despawn
                // check of its entities, so the region is dispatched like any other.
                this.entityTickRegionsWithoutEligibleChunk.incrementAndGet();
            }
            activations.add(activation);
        }
        RegionEntityTickAttribution.endPlanSegment();
        if (activations.isEmpty()) {
            // Every region was handed to the server thread: no batch has to be published for this tick.
            this.tickSelfDrivenFallbacks(batch);
            return;
        }

        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after the activation. Its region state is retired and no batch may be
            // published for it: the activated entities must not be ticked or handed to the server-thread
            // fallback after their level unloaded.
            for (final RegionEntityTickActivation activation : activations) {
                this.skippedEntityTicks.addAndGet(activation.region().tickingEntityCount());
            }
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            for (final RegionEntityTickActivation activation : activations) {
                this.addRegionFallback(batch, activation.region());
            }
            this.tickSelfDrivenFallbacks(batch);
            return;
        }
        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionEntityTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                for (final RegionEntityTickActivation activation : activations) {
                    final RegionState region = activation.region();
                    if (!this.acquireRegionLease(region, barrier)) {
                        this.fallbackEntityTickRegions.incrementAndGet();
                        this.addRegionFallback(batch, region);
                        continue;
                    }
                    this.entityTickRegionsOnWorkers.incrementAndGet();
                    this.addLeasedGroup(region, () -> batch.addSelfDrivenGroup(activation,
                            group -> this.withRegionMailboxDelivery(batch.level(), region, this.selfDrivenEntityTickGroup(group, activation))));
                }
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, barrier));
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
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                // The portal transition of a worker-ticked entity runs first: it is the earliest level effect
                // of the tick it belongs to, and the destination search may read the POI manager of the
                // destination level, which only the server thread may touch.
                failure = runBatchCompletion(failure, () -> this.applyDeferredPortalTransitions(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntitySectionUpdates(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntityRemovals(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntityAdditions(batch));
                // The light rechecks and block updates of the blocks the workers wrote are the level-wide effects
                // of their tick, so they run before the server-thread fallback ticks of this batch observe the
                // written state.
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
                failure = runBatchCompletion(failure, () -> this.tickSelfDrivenFallbacks(batch));
                failure = runBatchCompletion(failure, batch::runDeferredServerTasks);
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
     * {@return the chunk-load answers of the level's current loaded-chunk generation}
     * <p>
     * A new generation starts when the level reports a different loaded chunk count; the map is then rebuilt
     * from the server thread as the pre-load margin asks its chunks.
     */
    private ChunkLoadAnswers chunkLoadAnswers(ServerLevel level) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        final int loadedChunkCount = chunkSource == null ? 0 : chunkSource.getLoadedChunksCount();
        ChunkLoadAnswers answers = this.chunkLoadAnswers.get(level);
        if (answers == null || answers.loadedChunkCount != loadedChunkCount) {
            answers = new ChunkLoadAnswers(loadedChunkCount);
            this.chunkLoadAnswers.put(level, answers);
        }
        return answers;
    }

    /**
     * Hands every ticking entity of {@code region} to the server thread.
     * <p>
     * This is the counted per-region fallback: the region's entity tick could not be driven on a worker this
     * tick, so its entities keep the vanilla server-thread body instead of being dropped.
     */
    private void addRegionFallback(RegionEntityTickBatch batch, RegionState region) {
        for (final RegionEntityTickEntry entry : region.entityTickEntries()) {
            if (entry.ticking()) {
                batch.addSelfDrivenFallback(entry.entity());
            }
        }
    }

    /**
     * Ticks the entities of this pass that a worker handed back, on the server thread.
     */
    private void tickSelfDrivenFallbacks(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final Entity entity : batch.chunkFallbackEntities()) {
            // The chunk source refused a chunk the worker tick needed; the tick it abandoned is completed
            // here, exactly like the fallback of the per-entity plan.
            this.tickEntityInline(level, entity);
        }
        for (final Entity entity : batch.deferredUpdateFallbackEntities()) {
            // The batch could not reserve the block-write effects of the tick, so the worker never started it;
            // the whole vanilla body runs here, like for an entity whose region could not be enumerated.
            this.tickEntityServerSide(level, entity);
        }
        for (final Entity entity : batch.selfDrivenFallbackEntities()) {
            this.tickEntityServerSide(level, entity);
        }
    }

    /**
     * Runs the whole vanilla entity body for {@code entity} on the server thread: the discard predicate, the
     * frozen predicate, the despawn check, the entity ticking range, the riding stack and the non-passenger
     * tick.
     * <p>
     * This is the server-thread path of an entity whose region could not be enumerated or that a worker handed
     * back, so it reproduces the vanilla pass instead of only its tick.
     */
    private void tickEntityServerSide(ServerLevel level, Entity entity) {
        if (entity.isRemoved() || entity.level() != level) {
            this.skippedEntityTicks.incrementAndGet();
            return;
        }
        if (this.regionizer.isLevelClosed(level)) {
            this.skippedEntityTicks.incrementAndGet();
            return;
        }
        final MinecraftServer server = level.getServer();
        if (RegionEntityTickActivation.shouldDiscard(entity, server == null || server.isSpawningAnimals(), server == null || server.areNpcsEnabled())) {
            entity.discard();
            return;
        }
        if (RegionEntityTickActivation.isFrozen(entity, level.tickRateManager() == null || level.tickRateManager().runsNormally())) {
            return;
        }
        entity.checkDespawn();
        if (entity.isRemoved()) {
            return;
        }
        if (!level.isInEntityTickingRange(entity.chunkPosition().toLong())) {
            return;
        }
        if (ridesLiveVehicle(entity)) {
            return;
        }
        if (entity.getVehicle() != null) {
            entity.stopRiding();
        }
        if (entity.isRemoved() || entity instanceof PartEntity<?>) {
            return;
        }
        level.guardEntityTick(level::tickNonPassenger, entity);
        this.fallbackEntityTicks.incrementAndGet();
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
     * Starts the region-scoped chunk tick of the current {@code level} tick.
     * <p>
     * The level's own chunk loop decides which chunks are ticked (natural spawning gate, forced-tick gate,
     * {@code shouldTickBlocksAt}) and hands every such chunk to {@link RegionChunkTickPlan#accept}. This call
     * only decides whether the slice runs at all.
     *
     * @param randomTickSpeed the level's random tick speed, read once by the level for the whole tick
     * @param barrier         waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when the chunk tick has to stay on the server thread
     */
    @Nullable
    public RegionChunkTickPlan beginChunkTickPlan(ServerLevel level, int randomTickSpeed, RegionTickBarrier barrier) {
        if (!this.chunkTickWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        if (level.entitySectionRegionChunkSize() <= 0) {
            // A random tick reaches entity storage through the region-scoped query of its lease - a raised snow
            // layer pushes the entities standing in it up, a hatched egg spawns one - and that query only exists
            // for a level whose sections are partitioned per region. A level that keeps the legacy shared section
            // storage would have to refuse those ticks, so its chunk tick stays on the server thread.
            if (this.chunkTickStorageNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region chunk ticks stay on the server thread for a level without region-owned entity section storage");
            }
            this.nonPartitionedChunkTicks.incrementAndGet();
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker ticks would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; tick the chunks inline like vanilla instead of publishing a
            // batch for region state that no longer exists.
            return null;
        }
        return new RegionChunkTickPlan(this, level, randomTickSpeed, barrier);
    }

    /**
     * {@return true when every chunk around {@code chunkPos} belongs to the same region}
     * <p>
     * The block-entity tick slice admits a ticker only when its own chunk is interior: the ticker reads the
     * container next to it, and a block entity of another region is not readable by a worker, so the read would
     * silently answer {@code null} instead of the neighbour's value. The chunk tick slice no longer requires it:
     * a write of a random tick that leaves the region is recorded as a border block write and applied by the
     * target region in the same tick. This predicate stays the measure of how much of a level is still held
     * back from the block-entity slice, and the chunk tick plan counts the boundary chunks it dispatches.
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
     * Starts the region-scoped natural spawning of the current {@code level} tick.
     * <p>
     * The level's own chunk loop evaluates the spawn gate of every chunk and hands every chunk that may spawn to
     * {@link RegionSpawnPlan#accept(LevelChunk, boolean)}. This call only decides whether the slice runs at all.
     *
     * @param source  the server-owned reads the plan needs from the level's chunk source
     * @param barrier waits for the submitted region work on the server thread
     * @return the plan to fill, or {@code null} when natural spawning has to stay on the server thread
     */
    @Nullable
    public RegionSpawnPlan beginSpawnPlan(ServerLevel level, RegionSpawnSource source, boolean spawnFriendlies,
            boolean spawnEnemies, boolean spawnPersistent, RegionTickBarrier barrier) {
        if (!this.spawnWorkerExecutionEnabled || this.shutdown) {
            return null;
        }
        if (level.entitySectionRegionChunkSize() <= 0) {
            // A spawn admits its entity through the region ownership lease, and that lease only exists for a level
            // whose sections are partitioned per region. A level that keeps the legacy shared section storage
            // would have to refuse every spawn, so its natural spawning stays on the server thread.
            if (this.spawnStorageNoticeLogged.compareAndSet(false, true)) {
                LOGGER.info("Region natural spawning stays on the server thread for a level without region-owned entity section storage");
            }
            return null;
        }
        if (level.getProfiler() != InactiveProfiler.INSTANCE) {
            // The server profiler is a single stack based filler; concurrent worker spawns would corrupt it.
            return null;
        }
        if (this.regionizer.isLevelClosed(level)) {
            // The level identity is retired; spawn inline like vanilla instead of publishing a batch for region
            // state that no longer exists.
            return null;
        }
        return new RegionSpawnPlan(this, level, source, spawnFriendlies, spawnEnemies, spawnPersistent, barrier);
    }

    /**
     * {@return true when the chunk tick of a level executes on region workers}
     * <p>
     * This is the configured request, not the effective dispatch: the profiler, a closing level, the
     * loaded-neighborhood rule, the deferred update budget and a late collection still keep work on the server
     * thread, and every refusal is counted.
     */
    @ApiStatus.Internal
    public boolean isChunkTickWorkerExecutionEnabled() {
        return this.chunkTickWorkerExecutionEnabled;
    }

    /**
     * {@return true when the configured scheduled tick worker slice actually dispatches callbacks}
     */
    @ApiStatus.Internal
    public boolean isScheduledTickWorkerDispatchEffective() {
        return this.scheduledTickWorkerExecutionEnabled && this.unclassifiedWorkerCallbacksForTesting;
    }

    /**
     * {@return true when the configured chunk tick worker slice actually dispatches chunks}
     */
    @ApiStatus.Internal
    public boolean isChunkTickWorkerDispatchEffective() {
        return this.chunkTickWorkerExecutionEnabled;
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
     * {@return true when the block-entity plan reuses the cached admission of a chunk}
     */
    @ApiStatus.Internal
    public boolean isBlockEntityAdmissionCacheEnabled() {
        return this.blockEntityAdmissionCacheEnabled;
    }

    /**
     * Turns the per-chunk block-entity admission cache off for internal tests only.
     * <p>
     * Production never calls this: the cache is the production plan path. The seam exists so the equivalence
     * test can run the uncached evaluation, which is the reference behavior of the cached plan, over the same
     * chunk and ticker states.
     */
    @ApiStatus.Internal
    public void setBlockEntityAdmissionCacheEnabledForTesting(boolean enabled) {
        this.blockEntityAdmissionCacheEnabled = enabled;
    }

    /**
     * Installs the diagnostics-only observer of worker entity tick failures, or removes it with
     * {@code null}.
     * <p>
     * Only the entity worker diagnostics benchmark calls this. Every failing tick is reported to the
     * observer and the group continues with its next entity, so one run collects every blocked operation
     * instead of only the first one. The worker entity body builds no crash report at all: without an
     * observer the failure is recorded for the server thread and the group stops, with an observer it reaches
     * the observer and the group continues, see {@link RegionTickFailure}.
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
    public RandomSource chunkTickRandom() {
        return this.currentChunkTickRandom.get();
    }

    /**
     * {@return the position selection of the chunk tick executing on the current thread, or {@code null}
     * outside a chunk tick worker group}
     */
    @Nullable
    public RandomSource chunkTickPositionRandom() {
        return this.currentChunkTickPositionRandom.get();
    }

    /**
     * Records the lightning strike of a chunk tick worker for the batch barrier.
     *
     * @return {@code true} when the strike was recorded, {@code false} on a thread that has to resolve it
     *         inline
     */
    public boolean deferChunkTickLightning(BlockPos rolledPos) {
        final Queue<BlockPos> strikes = this.currentChunkTickLightning.get();
        if (strikes == null) {
            return false;
        }
        strikes.add(rolledPos);
        return true;
    }

    /**
     * Records the light section status change of one block write of a region worker group for the batch barrier.
     *
     * @param pos   the written position, already immutable
     * @param empty whether the section of the position holds only air now
     * @return {@code true} when the change was recorded, {@code false} on a thread that has to call the light
     *         engine itself
     */
    public boolean deferLightSectionStatus(BlockPos pos, boolean empty) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        updates.deferSectionStatus(pos, empty);
        return true;
    }

    /**
     * Records the light recheck of one block write of a region worker group for the batch barrier.
     *
     * @param pos the written position, already immutable
     * @return {@code true} when the recheck was recorded, {@code false} on a thread that has to call the light
     *         engine itself
     */
    public boolean deferLightCheck(BlockPos pos) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        updates.deferLightCheck(pos);
        return true;
    }

    /**
     * Records the block update publication of one block write of a region worker group for the batch barrier.
     *
     * @return {@code true} when the publication was recorded, {@code false} on a thread that has to publish it
     *         itself
     */
    public boolean deferBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        final RegionDeferredBlockUpdates updates = RegionTickBatch.currentBlockUpdates();
        if (updates == null) {
            return false;
        }
        updates.deferBlockUpdate(pos, oldState, newState, flags);
        // The position is the propagation a re-run of this region has to repeat if a chunk it read changed
        // under the write. Recording it here costs one context read and one list add per written block.
        this.recordRerunSeed(pos);
        return true;
    }

    /**
     * Remembers one position the region of the current group wrote, so a re-run of the region can re-evaluate it.
     * <p>
     * Only a thread that holds the lease of the region records: a write the group sends to another region is
     * already a border update, and the region that applies it records it as its own seed there. A region that has
     * not read a chunk of another region since its last check records nothing: no re-run can need the position,
     * so the seed list of a region that never reads across a border stays empty.
     */
    private void recordRerunSeed(BlockPos pos) {
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext) || basicContext.regionState == null) {
            return;
        }
        final RegionState region = basicContext.regionState;
        if (region.foreignChunkReads().isEmpty() || !region.isOwnedByCurrentThread()) {
            return;
        }
        // A position past the bound is counted by the region and drained with its next check.
        region.recordRerunSeed(pos.asLong());
    }

    /** {@return true when this engine captures cross-region border updates} */
    public boolean borderUpdatesEnabled() {
        return this.borderUpdatesEnabled;
    }

    /**
     * Enables or disables the border capture of this engine.
     * <p>
     * Production reads the switch from the server config at construction. The setter is the mechanics seam the
     * cross-region tests use, so a test does not have to load the config.
     */
    @ApiStatus.Internal
    public void setBorderUpdatesEnabledForTesting(boolean enabled) {
        this.borderUpdatesEnabled = enabled;
    }

    /**
     * Overrides the fixpoint bound, so a mechanics test can observe the next-tick fallback without producing
     * the level's default million chained updates.
     */
    @ApiStatus.Internal
    public void setBorderUpdateBudgetForTesting(int budget) {
        this.borderUpdateBudgetForTesting = budget;
    }

    /**
     * Lowers the number of border updates one capture pass admits, so a mechanics test can observe the capture
     * bound without producing {@link RegionBorderUpdates#MAX_ENTRIES} updates.
     * <p>
     * The capacity is read when the queue of a level is created, so a test has to call this before the first
     * update of the level is recorded.
     *
     * @param capacity the entries one capture queue admits; must be positive
     */
    @ApiStatus.Internal
    public void setBorderUpdateCapacityForTesting(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("The border update capacity has to admit at least one entry: " + capacity);
        }
        this.borderUpdateCapacity = capacity;
    }

    /** {@return the capture queue of {@code level}, created with the configured capacity} */
    private RegionBorderUpdates borderQueue(ServerLevel level) {
        return this.borderUpdates.computeIfAbsent(level, key -> new RegionBorderUpdates(this.borderUpdateCapacity));
    }

    /**
     * Records one neighbor update whose target belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla update
     */
    public boolean deferBorderNeighborUpdate(ServerLevel level, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level, new RegionBorderUpdates.NeighborUpdate(pos.immutable(), block, neighborPos.immutable(), movedByPiston));
    }

    /**
     * Records one shape update whose target belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla update
     */
    public boolean deferBorderShapeUpdate(ServerLevel level, Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos,
            int updateFlags, int updateLimit) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level,
                new RegionBorderUpdates.ShapeUpdate(direction, state, pos.immutable(), neighborPos.immutable(), updateFlags, updateLimit));
    }

    /**
     * Records one direction of {@code Level.updateNeighbourForOutputSignal} whose target belongs to another
     * region.
     *
     * @return {@code true} when the caller has to skip the vanilla body of that direction
     */
    public boolean deferBorderComparatorUpdate(ServerLevel level, BlockPos pos, BlockPos sourcePos, Block sourceBlock, Direction direction) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level,
                new RegionBorderUpdates.ComparatorUpdate(pos.immutable(), sourcePos.immutable(), sourceBlock, direction));
    }

    /**
     * Records the unsaved-chunk mark of one block-entity change whose block entity belongs to another region.
     *
     * @return {@code true} when the caller has to skip the vanilla mark
     */
    public boolean deferBorderBlockEntityChange(ServerLevel level, BlockPos pos) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        return this.recordBorderUpdate(level, new RegionBorderUpdates.BlockEntityChange(pos.immutable()));
    }

    /**
     * Records one block write whose position belongs to another region, so the target region runs the vanilla
     * {@code Level.setBlock} on its own worker at the barrier.
     * <p>
     * The record is the whole call - position, state, update flags and recursion budget - so the target region's
     * block-entity lifecycle, heightmaps, light recheck and neighbour and shape propagation are the vanilla ones
     * of the write, not a partial replay of it. The recording worker never mutates the target chunk.
     * <p>
     * <strong>Read after write.</strong> The write is applied by another region at the barrier, so a callback
     * that reads the same position again in the same lease observes the state before the write. The position is
     * remembered in the recording region, so
     * {@link net.neoforged.neoforge.server.threading.RegionThreadingHooks#observeReadAfterDeferredBlockWrite(BlockPos)}
     * can count the approximation instead of leaving it silent.
     * <p>
     * <strong>Bound.</strong> The capture queue of the fixpoint bounds the records of one pass. A write past the
     * bound is not queued and this method answers {@code false}: the caller falls back to the worker mutation
     * guard, which refuses the write with the existing no-replay reason and counts it. Unlike a neighbour update,
     * an overflowed block write is not handed to the next-tick fallback, because the caller of the write was
     * told it was accepted and a write that appears a tick later would be a silent difference from inline.
     *
     * @return {@code true} when the caller has to skip the inline write and report the call as accepted
     */
    @ApiStatus.Internal
    public boolean deferBorderBlockWrite(ServerLevel level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
        if (!this.capturesBorderUpdate(level, pos)) {
            return false;
        }
        final RegionBorderUpdates updates = this.borderQueue(level);
        final BlockPos writePos = pos.immutable();
        final RegionBorderUpdates.RecordResult result = updates.record(
                new RegionBorderUpdates.BlockWrite(writePos, state, flags, recursionLeft), false);
        if (result == RegionBorderUpdates.RecordResult.OVERFLOW) {
            this.borderUpdatesOverflowed.incrementAndGet();
            this.borderBlockWritesOverflowed.incrementAndGet();
            return false;
        }
        this.borderUpdatesRecorded.incrementAndGet();
        this.borderBlockWritesRecorded.incrementAndGet();
        this.recordDeferredWrite(writePos);
        return true;
    }

    /**
     * Counts one read of a position the current region wrote outside its lease during the current lease.
     * <p>
     * Runs on the read path of a foreign chunk of a native region worker, so it costs one context read and one
     * hash lookup per such read. It never changes the value the read returns: the caller observes the state
     * before the deferred write, which is the documented approximation.
     *
     * @param packedPos the read position, as {@link BlockPos#asLong(int, int, int)}
     */
    @ApiStatus.Internal
    public void observeReadAfterDeferredBlockWrite(long packedPos) {
        final RegionThreadingContext context = this.currentContext();
        if (context instanceof BasicRegionThreadingContext basicContext
                && basicContext.regionState != null
                && basicContext.regionState.wroteDeferred(packedPos)) {
            this.readAfterDeferredBlockWrite.incrementAndGet();
        }
    }

    /** Remembers one position the current region wrote outside its lease, for the read-after-write count. */
    private void recordDeferredWrite(BlockPos pos) {
        final RegionThreadingContext context = this.currentContext();
        if (context instanceof BasicRegionThreadingContext basicContext && basicContext.regionState != null) {
            basicContext.regionState.recordDeferredWrite(pos.asLong());
        }
    }

    /**
     * Records the block event of a region worker for the server thread's block event phase.
     * <p>
     * {@code ServerLevel.blockEvent} adds the event to one level-wide ordered set, and the body of a piston
     * move or a note block plays through the level and can write the next region. The event is recorded in the
     * worker batch's server-owned mutation queue, so the set is mutated only on the server thread, in the order
     * the worker produced the event. The queue is drained at the batch barrier, before vanilla's block event
     * phase, so the event still runs in the same tick.
     *
     * @return {@code true} when the caller has to skip the inline add
     */
    @ApiStatus.Internal
    public boolean deferBlockEvent(ServerLevel level, BlockPos pos, Block block, int eventId, int eventParam) {
        final Queue<Runnable> mutations = this.currentRegionTickMutations.get();
        if (mutations == null || this.shutdown) {
            return false;
        }
        final BlockPos immutablePos = pos.immutable();
        mutations.add(() -> level.blockEvent(immutablePos, block, eventId, eventParam));
        this.deferredBlockEvents.incrementAndGet();
        return true;
    }

    /** {@return the block events region workers deferred to the server thread's block event phase} */
    @ApiStatus.Internal
    public long deferredBlockEvents() {
        return this.deferredBlockEvents.get();
    }

    /** {@return the block entity reads a region group made against a quiescent foreign region} */
    @ApiStatus.Internal
    public long foreignBlockEntityReads() {
        return this.foreignBlockEntityReads.get();
    }

    /** {@return the foreign chunk reads a region recorded for the border fixpoint} */
    @ApiStatus.Internal
    public long foreignChunkReads() {
        return this.foreignChunkReads.get();
    }

    /** {@return reads of versioned chunks that no region owns} */
    @ApiStatus.Internal
    public long readOfUnownedChunk() {
        return this.readOfUnownedChunk.get();
    }

    /** Counts a versionless native-worker read refused before returning chunk data. */
    @ApiStatus.Internal
    public void recordVersionlessChunkRead() {
        this.readOfVersionlessChunk.incrementAndGet();
    }

    /** {@return the fixpoint checks in which a region observed a changed version of a foreign chunk} */
    @ApiStatus.Internal
    public long foreignReadInvalidatedPasses() {
        return this.foreignReadInvalidatedPasses.get();
    }

    /** {@return the region propagation groups the fixpoint re-ran because a foreign read was invalidated} */
    @ApiStatus.Internal
    public long foreignReadRegionReruns() {
        return this.foreignReadRegionReruns.get();
    }

    /** {@return the written positions a region dropped because its re-run seed bound was reached} */
    @ApiStatus.Internal
    public long foreignReadSeedsDropped() {
        return this.foreignReadSeedsDropped.get();
    }

    /**
     * Enables or disables the re-run of a region whose foreign read was invalidated.
     * <p>
     * The race tests turn it off to show the stale state a cross-region read produces without the version
     * check; production always has it on. The switch does not disable the recording, so a disabled check still
     * counts the foreign reads it did not act on.
     */
    @ApiStatus.Internal
    public void setForeignReadRerunEnabledForTesting(boolean enabled) {
        this.foreignReadRerunEnabled = enabled;
    }

    /**
     * {@return true when the current thread holds a region lease that does not contain {@code pos}}
     * <p>
     * Only a region lease captures. The server thread, the global region, a plain region queue task without a
     * lease and a worker whose target is inside its own region all keep the vanilla inline path. The check runs
     * before the record is allocated, so a server with the switch on but no cross-region update pays no
     * allocation per neighbor update.
     */
    private boolean capturesBorderUpdate(ServerLevel level, BlockPos pos) {
        if (!this.borderUpdatesEnabled) {
            return false;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext)
                || basicContext.kind() != RegionThreadingContext.Kind.REGION
                || basicContext.regionCoordinate == null) {
            return false;
        }
        return !this.isOwnedByCurrentRegion(level, pos);
    }

    private boolean recordBorderUpdate(ServerLevel level, RegionBorderUpdates.BorderUpdate update) {
        final RegionBorderUpdates updates = this.borderQueue(level);
        final RegionBorderUpdates.RecordResult result = updates.record(update);
        if (result == RegionBorderUpdates.RecordResult.ACCEPTED) {
            this.borderUpdatesRecorded.incrementAndGet();
        } else if (result == RegionBorderUpdates.RecordResult.OVERFLOW) {
            this.borderUpdatesOverflowed.incrementAndGet();
        }
        // A duplicate is already queued and an overflow is kept for the next-tick fallback: the caller must not
        // apply either inline, because the worker does not hold the target lease.
        return true;
    }

    /**
     * {@return true when the current thread holds a region lease that does not contain {@code update.pos()}}
     * <p>
     * Only a region lease captures. The server thread, the global region, a plain region queue task without a
     * lease and a worker whose target is inside its own region all keep the vanilla inline path.
     */
    /**
     * {@return the entries one region tick batch may reserve for its deferred block updates}
     */
    int deferredBlockUpdateBudget() {
        return this.deferredBlockUpdateBudget;
    }

    /**
     * {@return the number of chunks one chunk tick batch may admit to its deferred update budget}
     */
    int chunkTickDeferredUpdateBudget() {
        return this.deferredBlockUpdateBudget / RegionTickSlice.CHUNK_TICK.reservationPerUnit();
    }

    /**
     * Lowers the deferred block-update budget, so a mechanics test can observe the refusal without admitting
     * {@link RegionDeferredBlockUpdates#MAX_ENTRIES} entries.
     *
     * @param maxEntries the entries one batch may reserve; must be positive
     */
    @org.jetbrains.annotations.VisibleForTesting
    public void setDeferredBlockUpdateBudget(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("The deferred block-update budget has to admit at least one entry: " + maxEntries);
        }
        this.deferredBlockUpdateBudget = maxEntries;
    }

    /**
     * Lowers the deferred update budget of the chunk tick batch, so a mechanics test can observe the refusal
     * without admitting thousands of chunks.
     *
     * @param maxChunks the number of chunks one batch may admit; must be positive
     */
    @org.jetbrains.annotations.VisibleForTesting
    public void setChunkTickDeferredUpdateBudget(int maxChunks) {
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("The deferred update budget has to admit at least one chunk: " + maxChunks);
        }
        this.deferredBlockUpdateBudget = maxChunks * RegionTickSlice.CHUNK_TICK.reservationPerUnit();
    }

    /**
     * {@return the random source of the region worker group executing on the current thread, or
     * {@code null} outside a worker group}
     * <p>
     * Entity, scheduled, random and block-entity tick groups each own a random source while they run. The
     * level random wrapper uses this to redirect direct {@code level.random} reads of a worker to the random
     * source of its group instead of the shared vanilla source, which no two workers ever share because every
     * group owns its own source.
     */
    @Nullable
    public RandomSource currentGroupRandom() {
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        if (scheduled != null) {
            return scheduled;
        }
        final RandomSource random = this.currentChunkTickRandom.get();
        if (random != null) {
            return random;
        }
        final RandomSource spawn = this.currentSpawnRandom.get();
        if (spawn != null) {
            return spawn;
        }
        final RandomSource blockEntity = this.currentBlockEntityTickRandom.get();
        if (blockEntity != null) {
            return blockEntity;
        }
        return this.currentEntityTickRandom.get();
    }

    /**
     * {@return the random source of the block tick group executing on the current thread, or {@code null}
     * outside a scheduled, random or block-entity tick worker group}
     * <p>
     * This is the narrower question the block-entity access gate asks, because only a group that ticks the
     * blocks or the block entities of its region opens that gate. A scheduled or random tick group opens it
     * because its callback runs block code that reads its own block entity; a block-entity tick group opens it
     * because its ticker reads the block entities of its region - a hopper reads its attached container, which
     * is one block away. An entity tick group owns a random source as well, but that one is resolved through
     * {@link #currentGroupRandom()} and does not grant block-entity access.
     */
    @Nullable
    public RandomSource currentRegionTickRandom() {
        final RandomSource scheduled = this.currentScheduledTickRandom.get();
        if (scheduled != null) {
            return scheduled;
        }
        final RandomSource random = this.currentChunkTickRandom.get();
        return random != null ? random : this.currentBlockEntityTickRandom.get();
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
     * <p>
     * This publishes the mapping of an untracked entity and therefore takes the regionizer write lock. The
     * per-tick grouping of the entity slice uses {@link #regionFor(Entity)} instead: the authoritative
     * mapping is maintained by the entity's own tick (and by the recorded transfer), so grouping does not
     * need to write it again.
     */
    @Nullable
    RegionState regionOf(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return this.regionizer.moveEntity(entity, this.layout.regionAt(level, entity.blockPosition()));
    }

    /**
     * {@return the region currently owning {@code entity}, or {@code null} when it is not tracked}
     * <p>
     * The read-only counterpart of {@link #regionOf(Entity)}: it never publishes a mapping and takes only the
     * regionizer read lock. The entity tick plan groups with it, so grouping a tick does not need the
     * regionizer write lock per entity.
     */
    @Nullable
    RegionState regionFor(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        final RegionState region = this.regionizer.regionFor(entity);
        if (region == null || region.coordinate().level() != level) {
            return null;
        }
        return region;
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

        final RegionEntityTickBatch batch = new RegionEntityTickBatch(level, this.deferredBlockUpdateBudget);
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
                this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, barrier));
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
                // The portal transition of a worker-ticked entity runs first, exactly like in the self-driven
                // slice: it is the earliest level effect of the tick it belongs to, and the destination search
                // may read the POI manager of the destination level.
                failure = runBatchCompletion(failure, () -> this.applyDeferredPortalTransitions(batch));
                // The section publication and the target chunk acquisition of a worker entity move are the
                // vanilla effects of the move itself, so they run before any other server-thread work observes
                // the entity and before the ownership handoff of the batch commits a new region.
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntitySectionUpdates(batch));
                // The removal of an owned entity is the other vanilla storage effect a worker may defer. It
                // runs after the section updates, so the removal removes the entity from the section it was
                // published in, and before the ownership publication, so the handoff observes the removed
                // entity and publishes its departure instead of a region mapping.
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntityRemovals(batch));
                // The admission of a new entity is the third deferred vanilla storage effect. It runs after
                // the removals, so a record whose entity was removed before the barrier is dropped by the
                // removal it observes, and after the section updates, so an entity admitted here lands in the
                // section bucket its final position names and not in one a pending update is about to move.
                // It runs before the fallback ticks, so a graph admitted in this tick is visible to the
                // server-thread ticks of the same batch, and before the ownership publication, so the
                // handoff tracks the new entity.
                failure = runBatchCompletion(failure, () -> this.applyDeferredEntityAdditions(batch));
                // The light rechecks and block updates of the blocks the workers wrote are the level-wide effects
                // of their tick, so they run before the server-thread fallback ticks of this batch observe the
                // written state.
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
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
            // The regions are leased in one canonical order and split into balanced packs, so one task of this
            // slice can hold several regions. A slice with fewer regions than workers is not packed and keeps the
            // unpacked submission of one task per region.
            for (final List<RegionState> pack : this.regionPacks(grouping.regions())) {
                final List<RegionState> toLease = new ArrayList<>(pack.size());
                for (final RegionState region : pack) {
                    if (batch.leases(region)) {
                        // An earlier group of this same batch already owns the lease. It cannot be
                        // acquired again and must never be awaited on this thread.
                        batch.group(region).ifPresent(group -> group.addEntities(grouping.items(region)));
                        continue;
                    }
                    toLease.add(region);
                }
                if (toLease.isEmpty()) {
                    continue;
                }
                if (toLease.size() == 1) {
                    if (this.leaseEntityGroup(batch, grouping, toLease.get(0), barrier, next)) {
                        leasedAny = true;
                        batchedEntities += grouping.size(toLease.get(0));
                    }
                    continue;
                }
                final List<RegionState> leased = RegionLeasePack.tryAcquireAll(toLease, this::releaseRegionLease);
                if (leased == null) {
                    // The pack could not take every region: it released the ones it took again, and every
                    // region of the pack is leased through the unpacked path, which waits for a region a worker
                    // still holds instead of failing on it. The fallback is counted.
                    this.packedLeaseFallbacks.incrementAndGet();
                    for (final RegionState region : toLease) {
                        if (this.leaseEntityGroup(batch, grouping, region, barrier, next)) {
                            leasedAny = true;
                            batchedEntities += grouping.size(region);
                        }
                    }
                    continue;
                }
                leasedAny = true;
                for (final RegionState region : leased) {
                    batchedEntities += grouping.size(region);
                }
                this.addPackedEntityGroups(batch, grouping, leased);
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
     * Leases one region of an entity batch and submits its group as its own task.
     *
     * @return {@code true} when the region was leased and its group submitted, {@code false} when its entities
     *         were recorded for the next attempt
     * @see #leaseBlockEntityGroup the same unpacked path of the block-entity slice
     */
    private boolean leaseEntityGroup(RegionEntityTickBatch batch, RegionTickGrouping<RegionState, Entity> grouping, RegionState region, RegionTickBarrier barrier, List<Entity> retry) {
        if (!this.acquireRegionLease(region, barrier)) {
            retry.addAll(grouping.items(region));
            return false;
        }
        this.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region),
                group -> this.withRegionMailboxDelivery(batch.level(), region, this.entityTickGroup(group))));
        return true;
    }

    /**
     * Registers the groups of one leased pack and submits them as one worker task.
     * <p>
     * A failure while a group is built releases every lease of the pack, exactly like the block-entity pack: a
     * region must never stay TICKING behind a group no worker will run.
     */
    private void addPackedEntityGroups(RegionEntityTickBatch batch, RegionTickGrouping<RegionState, Entity> grouping, List<RegionState> pack) {
        final List<Runnable> works = new ArrayList<>(pack.size());
        try {
            for (final RegionState region : pack) {
                final RegionEntityTickBatch.Group group = batch.registerGroup(region, grouping.items(region));
                works.add(batch.packedWork(group, leased -> this.withRegionMailboxDelivery(batch.level(), region, this.entityTickGroup(leased))));
            }
        } catch (Throwable throwable) {
            for (final RegionState region : pack) {
                this.releaseRegionLease(region);
            }
            throw throwable;
        }
        this.packedTasks.incrementAndGet();
        this.packedRegions.addAndGet(pack.size());
        batch.addPackedTask("entity tick pack of " + pack.size(), works);
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
    static boolean isEntityTickNeighborhoodLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded, Long2ByteMap neighborhoodLoaded) {
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
    static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ, Long2ByteMap chunkLoaded) {
        final long key = ChunkPos.asLong(chunkX, chunkZ);
        final byte cached = chunkLoaded.get(key);
        if (cached != NEIGHBORHOOD_UNKNOWN) {
            return cached == NEIGHBORHOOD_LOADED;
        }
        // Asked at most once per chunk and loaded-chunk generation, so the chunk source round trip of
        // getChunkNow is not on the per-tick path of the neighborhood.
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
            this.releaseRegionLease(region);
            throw throwable;
        }
    }

    /**
     * Releases a lease this thread took and never handed to a worker.
     * <p>
     * Retiring an empty region is part of the release: the lease is what kept the region alive, and a region that
     * is retired after its last group is the same cleanup a finished group runs.
     */
    private void releaseRegionLease(RegionState region) {
        region.clearRunning();
        this.regionizer.removeIfEmpty(region);
    }

    /**
     * {@return the packs of one slice's regions, each in the canonical lease order}
     * <p>
     * With packing enabled the regions are split into balanced packs of about {@code regionCount / workerCount}
     * regions; with it disabled every pack holds one region, which is the unpacked submission of one task per
     * region.
     *
     * @see RegionLeasePack#split(List, int)
     */
    private List<List<RegionState>> regionPacks(Collection<RegionState> regions) {
        if (!this.packedRegionLeasesEnabled) {
            // The unpacked path keeps the order in which the regions were first seen, exactly like before
            // packing existed: a pack is the only thing that needs a canonical order.
            final List<List<RegionState>> single = new ArrayList<>(regions.size());
            for (final RegionState region : regions) {
                single.add(List.of(region));
            }
            return single;
        }
        return RegionLeasePack.split(RegionLeasePack.canonicalOrder(regions), this.workerPool.threadCount());
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
                                if (!group.batch().tryReserveBlockUpdates()) {
                                    // The batch cannot reserve the block-write effects of this entity's tick, so
                                    // the tick is refused before its body starts and runs on the server thread
                                    // after the batch, where its writes need no record.
                                    group.batch().addDeferredUpdateFallback(entity);
                                    this.countRefusedDeferredBlockUpdate(RegionTickSlice.ENTITY_TICK);
                                    continue;
                                }
                                if (!prepareOwnedEntityTick(entity)) {
                                    continue;
                                }
                                try {
                                    // The guarded part of the vanilla pass is exactly the non-passenger tick:
                                    // a failure of the despawn check or of the discard predicate above keeps
                                    // propagating like vanilla lets it propagate.
                                    tickEntityBody(level, entity);
                                } catch (Throwable throwable) {
                                    if (handOffRefusedChunk(group, entity, throwable)) {
                                        continue;
                                    }
                                    if (failureObserver != null) {
                                        // Diagnostics only: the observer records the rejected operation and the
                                        // group keeps ticking its remaining entities instead of stopping at the
                                        // first one.
                                        failureObserver.accept(entity, throwable);
                                        continue;
                                    }
                                    // The failure is recorded for the server thread instead of being turned into
                                    // a crash report on this worker; the group stops here, exactly like the
                                    // vanilla entity pass stops at its first failing entity.
                                    this.recordWorkerTickFailure(group.batch(), coordinate, entity, throwable);
                                    return;
                                }
                                ++ticked[0];
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
     * Builds the worker task of one self-driven region group.
     * <p>
     * The task enumerates the entity tick index of the region it leased - the server thread handed it nothing
     * but the lease and the activation snapshot - revalidates the ownership of every entry, and runs the
     * vanilla entity body of the region's ticking entities inside the region scope. This is what removes the
     * per-entity server-thread collection from the entity pass: the server thread never iterated the level's
     * entity tick list for this region.
     */
    private Runnable selfDrivenEntityTickGroup(RegionEntityTickBatch.Group group, RegionEntityTickActivation activation) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                // The group random is installed once for the whole worker tick loop, exactly like the
                // per-entity plan does for the group it was handed.
                this.currentEntityTickRandom.set(random);
                try {
                    // The index is enumerated once per group: the tick list of the region cannot change
                    // underneath this loop, because entity moves are deferred to the barrier and a leased
                    // region cannot merge, split or transfer.
                    final List<RegionEntityTickEntry> entries = region.entityTickEntries();
                    final List<Entity> tickable = new ArrayList<>(entries.size());
                    for (final RegionEntityTickEntry entry : entries) {
                        if (!entry.ticking()) {
                            // Mapped to the region but not in the level's entity tick list: the vanilla pass
                            // does not tick it, and neither does the region.
                            continue;
                        }
                        if (entry.serverThreadOnly()) {
                            // An always-ticking entity (a player) is ticked by the server thread, never by a
                            // region worker: its tick reaches the connection and the chunk source.
                            group.batch().addSelfDrivenFallback(entry.entity());
                            this.serverThreadOnlyEntityTicks.incrementAndGet();
                            continue;
                        }
                        if (!ridesLiveVehicle(entry.entity())) {
                            // The per-type policy is consulted before the body starts. A declared type is
                            // ticked on the server thread after the batch instead of on this worker; an
                            // entity that rides a live vehicle is decided at the vehicle, whose entry covers
                            // the riding stack.
                            if (this.entityTickPolicy.recordServerThreadOnly(entry.entity()) != null) {
                                group.batch().addSelfDrivenFallback(entry.entity());
                                continue;
                            }
                        }
                        tickable.add(entry.entity());
                    }
                    if (!tickable.isEmpty()) {
                        // The ownership lease covers exactly the entities of this region. Installing them
                        // before the first tick keeps the ownership check of every mutation of the loop and
                        // covers the passenger stacks the group ticks through their vehicle.
                        group.addEntities(tickable);
                    }
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    final BiConsumer<Entity, Throwable> failureObserver = this.entityTickFailureObserverForTesting;
                    try {
                        this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, group), () -> {
                            for (final Entity entity : tickable) {
                                if (!group.batch().tryReserveBlockUpdates()) {
                                    // The batch cannot reserve the block-write effects of this entity's tick, so
                                    // the tick is refused before its body starts and runs on the server thread
                                    // after the batch, where its writes need no record.
                                    group.batch().addDeferredUpdateFallback(entity);
                                    this.countRefusedDeferredBlockUpdate(RegionTickSlice.ENTITY_TICK);
                                    continue;
                                }
                                if (!this.prepareSelfDrivenEntityTick(level, entity, activation, group)) {
                                    continue;
                                }
                                try {
                                    // The guarded part of the vanilla pass is exactly the non-passenger tick:
                                    // a failure of the discard predicate, the despawn check or the riding
                                    // stack above keeps propagating like vanilla lets it propagate.
                                    tickEntityBody(level, entity);
                                } catch (Throwable throwable) {
                                    if (handOffRefusedChunk(group, entity, throwable)) {
                                        continue;
                                    }
                                    if (failureObserver != null) {
                                        // Diagnostics only: the observer records the rejected operation and the
                                        // group keeps ticking its remaining entities instead of stopping at the
                                        // first one.
                                        failureObserver.accept(entity, throwable);
                                        continue;
                                    }
                                    // The failure is recorded for the server thread instead of being turned into
                                    // a crash report on this worker; the group stops here, exactly like the
                                    // vanilla entity pass stops at its first failing entity.
                                    this.recordWorkerTickFailure(group.batch(), coordinate, entity, throwable);
                                    return;
                                }
                                ++ticked[0];
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
     * Runs the vanilla preconditions of one entity of a leased region on the worker.
     * <p>
     * The preconditions are the ones the vanilla {@code ServerLevel} entity pass runs before it ticks: the
     * discard predicate, the frozen predicate, the despawn check, the entity ticking range of the entity's
     * chunk and the riding stack. The two server-owned reads of that pass - the ticking range and the
     * entity-load neighborhood of the chunk guard - are answered from the activation snapshot the server
     * thread built before the lease.
     * <p>
     * An entity whose chunk cannot be decided from the snapshot, or whose chunk guard would need an unloaded
     * chunk, is handed to the server thread instead of being dropped. The entity body has not run for it at
     * that point, so the handoff runs all of it.
     * <p>
     * The non-passenger tick itself is not run here: it is the part of the vanilla pass that
     * {@code Level#guardEntityTick} wraps, so the caller runs it and decides what a failure of it means - a
     * recorded failure for the server thread, or a rejected operation for the diagnostics observer.
     *
     * @return {@code true} when the caller has to run the vanilla non-passenger tick for {@code entity}
     */
    private boolean prepareSelfDrivenEntityTick(ServerLevel level, Entity entity, RegionEntityTickActivation activation, RegionEntityTickBatch.Group group) {
        if (entity.isRemoved()) {
            return false;
        }
        // The cached chunk position of the entity: the chunk its tick starts from, read once for the
        // activation snapshot and the ticking range check, exactly like the vanilla body reads it before it
        // ticks.
        final int chunk = activation.indexOf(entity.chunkPosition());
        if (chunk < 0) {
            // The entity left the region's chunk rectangle between its last mapping publication and this
            // tick, so its chunk cannot be decided here. The server thread ticks it and republishes its
            // mapping there.
            group.batch().addSelfDrivenFallback(entity);
            this.entityTicksOutsideRegion.incrementAndGet();
            return false;
        }
        if (!activation.isNeighborhoodLoaded(chunk)) {
            // The chunk guard would have to ask the chunk source for a chunk the tick may read; the entity is
            // ticked on the server thread instead of blocking the worker on the chunk source.
            group.batch().addSelfDrivenFallback(entity);
            this.chunkPreloadRefusedEntityTicks.incrementAndGet();
            return false;
        }
        if (activation.shouldDiscard(entity)) {
            // The vanilla body discards the entity instead of ticking it. On a worker the removal itself is
            // deferred to the barrier, exactly like every other removal of a leased region.
            entity.discard();
            return false;
        }
        if (activation.isFrozen(entity)) {
            return false;
        }
        entity.checkDespawn();
        if (entity.isRemoved()) {
            return false;
        }
        if (!activation.isEntityTicking(chunk)) {
            // Outside the vanilla entity ticking range: the despawn check above is all the vanilla body does,
            // and the entity is not ticked.
            return false;
        }
        if (ridesLiveVehicle(entity)) {
            // Ticked as part of the vehicle stack, which the group ticks through the vehicle.
            return false;
        }
        if (entity.getVehicle() != null) {
            entity.stopRiding();
        }
        if (entity.isRemoved() || entity instanceof PartEntity<?>) {
            return false;
        }
        return true;
    }

    /**
     * Runs the part of the vanilla entity pass that {@code Level#guardEntityTick} wraps - the non-passenger tick -
     * on the worker, with the entity timing that the guard records around it.
     * <p>
     * The guard's crash report is not built here: a failure propagates to the caller, which records it for the
     * server thread instead, see {@link RegionTickFailure}.
     */
    private static void tickEntityBody(ServerLevel level, Entity entity) {
        TimeTracker.ENTITY_UPDATE.trackStart(entity);
        try {
            level.tickNonPassenger(entity);
        } finally {
            TimeTracker.ENTITY_UPDATE.trackEnd(entity);
        }
    }

    /**
     * {@return true when {@code entity} is ticked through the vehicle that still carries it}
     * <p>
     * A passenger is not ticked on its own: the tick of its vehicle runs the whole vanilla tick of every
     * passenger ({@code Entity#rideTick}), so the vehicle is the entry the entity slices decide on. A
     * vehicle that was removed or that no longer carries the entity is not a live ride: the vanilla body
     * stops the ride and ticks the entity itself.
     */
    private static boolean ridesLiveVehicle(Entity entity) {
        final Entity vehicle = entity.getVehicle();
        return vehicle != null && !vehicle.isRemoved() && vehicle.hasPassenger(entity);
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
        if (RegionWorkerChunkUnavailableException.mustNotReplay(throwable)) {
            return true;
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
     * Ticks the entities whose region could not be leased safely, the entities a worker handed back after a
     * refused chunk request and the entities a worker refused before their body because of the deferred
     * block-update bound, on the server thread after the batch.
     */
    private void tickFallbackEntities(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final Entity entity : batch.fallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
        for (final Entity entity : batch.chunkFallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
        for (final Entity entity : batch.deferredUpdateFallbackEntities()) {
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
        this.republishServerThreadEntityMove(level, entity);
    }

    /**
     * Republishes the region mapping of an entity the server thread just ticked, when its tick moved it out
     * of the region it is mapped to.
     * <p>
     * A worker tick defers every position write and the batch barrier publishes the captured destination, so
     * the mapping of a worker-ticked entity is maintained by the entity slice. A server-thread tick - the
     * pre-load fallback, the chunk fallback, a declared server-thread-only type and the always-ticking player
     * - runs the unmodified vanilla body, whose position write reaches the level callback directly and never
     * the entity batch. Without this publication the mapping of such an entity keeps the region it started in
     * while the entity walks away, and the region index would group and lease it by a region that no longer
     * contains it.
     * <p>
     * The common case - the entity stayed inside its region - costs one read-lock lookup and one coordinate
     * comparison and writes nothing. Only a move that really crosses a region border takes the write lock and
     * notifies the entity's scheduled tasks, exactly like the deferred path does.
     */
    private void republishServerThreadEntityMove(ServerLevel level, Entity entity) {
        if (this.shutdown || entity.isRemoved() || entity.level() != level) {
            return;
        }
        final RegionState mapped = this.regionizer.regionFor(entity);
        if (mapped == null) {
            // The entity is not mapped at all (it never started ticking in a region); publish the first
            // mapping so the index knows it.
            this.publishEntityMove(entity);
            return;
        }
        if (mapped.coordinate().equals(this.layout.regionAt(level, entity.blockPosition()))) {
            return;
        }
        this.publishEntityMove(entity);
    }

    /** Records a chunk the plan did not hand over because it is not block-ticking this tick. */
    void countNonTickingBlockEntityChunk() {
        this.nonTickingBlockEntityChunks.incrementAndGet();
    }

    /** Records a chunk the plan did not hand over because its entities are still loading. */
    void countEntitiesLoadingBlockEntityChunk() {
        this.entitiesLoadingBlockEntityChunks.incrementAndGet();
    }

    /** Records a chunk the plan did not hand over because it is no longer loaded. */
    void countMissingBlockEntityChunk() {
        this.missingBlockEntityChunks.incrementAndGet();
    }

    /**
     * Ticks {@code chunks} on their owning region workers and waits for the batch.
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
     * successor instead of dropping the chunk. Chunks whose region cannot be leased safely, and chunks whose
     * admission snapshot went stale before the worker ticked them, are ticked on the server thread after the
     * batch rather than being dropped.
     * <p>
     * The two server-owned effects a worker group defers are applied here, after the workers finished and
     * before the batch is released: the removals of the entities an owned ticker removed, and the scheduled
     * ticks its neighbor updates started. The fallback ticks run between them, so a ticker the server thread
     * ticks after the batch observes the removals of the batch it belongs to.
     */
    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier) {
        if (chunks.isEmpty()) {
            return;
        }

        final RegionBlockEntityTickBatch batch = new RegionBlockEntityTickBatch(level, this.deferredBlockUpdateBudget);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was collected. Its region state is retired
            // and no batch may be published for it: the collected tickers must not tick for a level
            // that already unloaded.
            this.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the collected tickers still
            // have to tick, but no batch may be published after shutdown started.
            for (final RegionBlockEntityTickChunk chunk : chunks) {
                this.tickBlockEntityChunkInline(level, chunk);
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
                this.leaseBlockEntityTickGroups(batch, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            final long awaitCpuStart = attribution ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() : 0L;
            try {
                // The barrier is the level's main-thread chunk-source block, so the batch waits by polling the
                // main-thread queue. Counting the polls and the CPU time of the wait shows how much of the wait
                // the server thread runs instead of parking, which is what competes with the workers.
                this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, attribution ? condition -> barrier.await(() -> {
                    RegionBlockEntityTickAttribution.recordAwaitPoll();
                    return condition.getAsBoolean();
                }) : barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    final long awaitEnd = System.nanoTime();
                    RegionBlockEntityTickAttribution.recordAwait(awaitEnd - awaitStart,
                            RegionBlockEntityTickAttribution.currentThreadCpuNanos() - awaitCpuStart);
                    // The slowest group bounds the wait the workers caused; the rest of the await is dispatch
                    // and completion, which is what a barrier-model fix could remove.
                    RegionBlockEntityTickAttribution.recordBatchMakespan(batch.slowestGroupNanos());
                    // Splits the wait into the time until the first group started, the span the workers ran
                    // in and the time until the wait noticed the last group finished.
                    RegionBlockEntityTickAttribution.recordBatchPhases(awaitStart, awaitEnd,
                            batch.firstGroupStartNanos(), batch.lastGroupEndNanos());
                    RegionBlockEntityTickAttribution.recordBatchSubmit(batch.submitNanos());
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                // The light rechecks and block updates of the blocks the tickers wrote are the level-wide effects
                // of their ticker body, and that body runs before the removal of an item a hopper absorbed, so
                // they run first. They are not one of the timed handoff steps: the step timers below split the
                // removal, fallback and mutation work, and this segment is the vanilla publication of the batch.
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
                // Every handoff step is timed on its own when the attribution is enabled, so the deferred
                // work of a batch is attributable per step instead of as one block.
                final long removalStart = attribution ? System.nanoTime() : 0L;
                final long removalsBefore = attribution ? this.deferredRemovalCount() : 0L;
                // The removal of an owned item entity is the one vanilla storage effect a block-entity
                // worker may defer. It runs before the fallback ticks, so a hopper that the server thread
                // ticks after the batch no longer sees the item entity a worker already absorbed.
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockEntityRemovals(batch));
                final long fallbackStart = attribution ? System.nanoTime() : 0L;
                final long removals = attribution ? this.deferredRemovalCount() - removalsBefore : 0L;
                failure = runBatchCompletion(failure, () -> this.tickFallbackBlockEntities(batch));
                final long mutationStart = attribution ? System.nanoTime() : 0L;
                final long fallbackTickers = attribution ? countBlockEntityTickers(batch.fallbackChunks()) + batch.fallbackTickers().size() : 0L;
                final long mutations = attribution ? batch.regionTickMutations().size() : 0L;
                // The scheduled ticks a ticker started through the neighbor update of its own setChanged()
                // were recorded on the workers; they mutate LevelTicks here, on the server thread.
                failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
                final long finishStart = attribution ? System.nanoTime() : 0L;
                batch.finish();
                this.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
                if (attribution) {
                    final long finishEnd = System.nanoTime();
                    RegionBlockEntityTickAttribution.recordHandoffRemovals(fallbackStart - removalStart, removals);
                    RegionBlockEntityTickAttribution.recordHandoffFallback(mutationStart - fallbackStart, fallbackTickers);
                    RegionBlockEntityTickAttribution.recordHandoffMutations(finishStart - mutationStart, mutations);
                    RegionBlockEntityTickAttribution.recordHandoffFinish(finishEnd - finishStart);
                }
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
    private void leaseBlockEntityTickGroups(RegionBlockEntityTickBatch batch, List<RegionBlockEntityTickChunk> pending, RegionTickBarrier barrier) {
        List<RegionBlockEntityTickChunk> remaining = pending;
        int batchedTickers = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping = new RegionTickGrouping<>();
            final List<RegionBlockEntityTickChunk> retry = new ArrayList<>();
            for (final RegionBlockEntityTickChunk chunk : remaining) {
                final RegionState region = this.regionizer.getOrCreate(chunk.region(this.layout, batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionBlockEntityTickChunk> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            // The regions are leased in one canonical order and split into balanced packs, so one task of this
            // slice can hold several regions. A slice with fewer regions than workers is not packed and keeps the
            // unpacked submission of one task per region.
            for (final List<RegionState> pack : this.regionPacks(grouping.regions())) {
                final List<RegionState> toLease = new ArrayList<>(pack.size());
                for (final RegionState region : pack) {
                    final List<RegionBlockEntityTickChunk> regionChunks = grouping.items(region);
                    if (!this.isRegionSafeBlockEntityGroup(regionChunks)) {
                        // Conservative whole-region fallback: a region that contains at least one ticker whose
                        // compatibility declaration does not prove region-local, storage-free work keeps all
                        // of its tickers on the server thread after the batch. The order inside the region is
                        // preserved and no query consumer ever runs on a worker.
                        for (final RegionBlockEntityTickChunk chunk : regionChunks) {
                            batch.addFallbackChunk(chunk);
                            this.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
                            RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION, chunk.admissions().size());
                        }
                        continue;
                    }
                    if (batch.leases(region)) {
                        // An earlier group of this same batch already owns the lease. It cannot be
                        // acquired again and must never be awaited on this thread.
                        batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                        continue;
                    }
                    toLease.add(region);
                }
                if (toLease.isEmpty()) {
                    continue;
                }
                if (toLease.size() == 1) {
                    if (this.leaseBlockEntityGroup(batch, grouping, toLease.get(0), barrier, next)) {
                        leasedAny = true;
                        batchedTickers += this.countLeasedBlockEntityTickers(grouping, toLease.get(0));
                    }
                    continue;
                }
                final List<RegionState> leased = RegionLeasePack.tryAcquireAll(toLease, this::releaseRegionLease);
                if (leased == null) {
                    // The pack could not take every region: it released the ones it took again, and every
                    // region of the pack is leased through the unpacked path, which waits for a region a worker
                    // still holds instead of failing on it. The fallback is counted.
                    this.packedLeaseFallbacks.incrementAndGet();
                    for (final RegionState region : toLease) {
                        if (this.leaseBlockEntityGroup(batch, grouping, region, barrier, next)) {
                            leasedAny = true;
                            batchedTickers += this.countLeasedBlockEntityTickers(grouping, region);
                        }
                    }
                    continue;
                }
                leasedAny = true;
                for (final RegionState region : leased) {
                    batchedTickers += this.countLeasedBlockEntityTickers(grouping, region);
                }
                this.addPackedBlockEntityGroups(batch, grouping, leased);
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final RegionBlockEntityTickChunk chunk : remaining) {
            batch.addFallbackChunk(chunk);
            this.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
            RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNRESOLVABLE_REGION, chunk.admissions().size());
        }
        if (batchedTickers > 0 && this.blockEntityTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region block entity ticks are now executing on region workers: {} block entities", batchedTickers);
        }
    }

    /**
     * Leases one region of a block-entity batch and submits its group as its own task.
     * <p>
     * This is the unpacked path: it is used for a slice that is not packed, for a pack with one region and for
     * the fallback of a pack that could not take every region it needs.
     *
     * @return {@code true} when the region was leased and its group submitted, {@code false} when its chunks were
     *         recorded for the next attempt
     */
    private boolean leaseBlockEntityGroup(RegionBlockEntityTickBatch batch, RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, RegionState region, RegionTickBarrier barrier, List<RegionBlockEntityTickChunk> retry) {
        final List<RegionBlockEntityTickChunk> regionChunks = grouping.items(region);
        if (!this.acquireRegionLease(region, barrier)) {
            retry.addAll(regionChunks);
            return false;
        }
        this.addLeasedGroup(region, () -> batch.addGroup(region, regionChunks,
                group -> this.withRegionMailboxDelivery(batch.level(), region, this.blockEntityTickGroup(group))));
        return true;
    }

    /** {@return the number of tickers one leased region of {@code grouping} hands to a worker} */
    private int countLeasedBlockEntityTickers(RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, RegionState region) {
        return countBlockEntityTickers(grouping.items(region));
    }

    /**
     * Registers the groups of one leased pack and submits them as one worker task.
     * <p>
     * The leases are already taken, so a failure while a group is built releases every lease of the pack
     * instead of leaving a region TICKING behind a group no worker will run, which would block shutdown and
     * level unload forever.
     */
    private void addPackedBlockEntityGroups(RegionBlockEntityTickBatch batch, RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, List<RegionState> pack) {
        final List<Runnable> works = new ArrayList<>(pack.size());
        try {
            for (final RegionState region : pack) {
                final RegionBlockEntityTickBatch.Group group = batch.registerGroup(region, grouping.items(region));
                works.add(batch.packedWork(group, leased -> this.withRegionMailboxDelivery(batch.level(), region, this.blockEntityTickGroup(leased))));
            }
        } catch (Throwable throwable) {
            for (final RegionState region : pack) {
                this.releaseRegionLease(region);
            }
            throw throwable;
        }
        this.packedTasks.incrementAndGet();
        this.packedRegions.addAndGet(pack.size());
        batch.addPackedTask("block entity tick pack of " + pack.size(), works);
    }

    private boolean isRegionSafeBlockEntityGroup(List<RegionBlockEntityTickChunk> chunks) {
        for (final RegionBlockEntityTickChunk chunk : chunks) {
            if (!chunk.allAdmitted()) {
                return false;
            }
        }
        return true;
    }

    /** {@return the number of tickers {@code chunks} hand to a worker group} */
    private static int countBlockEntityTickers(Collection<RegionBlockEntityTickChunk> chunks) {
        int tickers = 0;
        for (final RegionBlockEntityTickChunk chunk : chunks) {
            tickers += chunk.admissions().size();
        }
        return tickers;
    }

    private Runnable blockEntityTickGroup(RegionBlockEntityTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final List<RegionBlockEntityTickChunk> chunks = group.chunks();
        final ServerLevel level = group.batch().level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            final boolean tickerTiming = RegionBlockEntityTickAttribution.isEnabled();
            final long cpuStart = tickerTiming ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() : 0L;
            int admitted = 0;
            try {
                final List<RegionBlockEntityTickAdmission> tickers = this.orderBlockEntityGroup(group, chunks);
                admitted = tickers.size();
                region.beginOwnership();
                // The group random is installed once for the whole worker tick loop, so a direct
                // level.random read of a ticker resolves this group's source through the level random
                // wrapper instead of the shared vanilla one, and the block-entity access gate of the
                // level answers for the block entities of this region.
                this.currentBlockEntityTickRandom.set(random);
                // LevelTicks is server-owned storage: a scheduled tick a ticker starts through the
                // neighbor update of its own setChanged() is recorded here and applied by the publishing
                // server thread after the batch, never from a worker.
                this.currentRegionTickMutations.set(group.batch().regionTickMutations());
                try {
                    // The ownership lease covers exactly this worker tick loop: it is opened after the
                    // region lease and closed before it, so a worker task outside the loop never sees it.
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    try {
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
                                    RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION, 1);
                                    continue;
                                }
                                if (RegionWorkerScope.isNativeWorkerThread()) {
                                    if (!group.batch().tryReserveBlockUpdates()) {
                                        // The batch cannot reserve the block-write effects of this ticker, so
                                        // the tick is refused before its body starts and runs on the server
                                        // thread after the batch, where its writes need no record.
                                        group.batch().addFallback(admission);
                                        this.fallbackBlockEntityTicks.incrementAndGet();
                                        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.DEFERRED_UPDATE_BUDGET, 1);
                                        this.countRefusedDeferredBlockUpdate(RegionTickSlice.BLOCK_ENTITY_TICK);
                                        continue;
                                    }
                                    final long tickerStart = tickerTiming ? System.nanoTime() : 0L;
                                    try (RegionBlockEntityTickScope scope = RegionBlockEntityTickScope.open(admission, region)) {
                                        ticker.tick();
                                    } catch (Throwable throwable) {
                                        // A chunk request the chunk source refused is a handoff, not a failure: the
                                        // ticker is ticked on the server thread after the batch instead of aborting
                                        // the group.
                                        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                                            // The failure is recorded for the server thread instead of failing the
                                            // group with it; the group stops at its first failing ticker.
                                            this.recordWorkerTickFailure(group.batch(), coordinate, null, throwable);
                                            return;
                                        }
                                        if (!RegionWorkerChunkUnavailableException.mustNotReplay(throwable)) {
                                            group.batch().addFallback(admission);
                                            this.fallbackBlockEntityTicks.incrementAndGet();
                                            RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.CHUNK_UNAVAILABLE, 1);
                                        }
                                        continue;
                                    }
                                    if (tickerTiming) {
                                        // The ticker's own tick, so the group wall time can be split into the
                                        // vanilla body and the wrapper the slice adds around it.
                                        RegionBlockEntityTickAttribution.recordTicker(System.nanoTime() - tickerStart);
                                    }
                                } else {
                                    // Executor rejection runs on the server thread and keeps the real load check.
                                    this.tickBlockEntityInline(level, ticker);
                                }
                                ++ticked[0];
                            }
                        });
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.currentRegionTickMutations.remove();
                    this.currentBlockEntityTickRandom.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                // The group reports its own wall time and size, so the measured window can tell a barrier
                // that waits for the slowest group from one that waits for work.
                RegionBlockEntityTickAttribution.recordGroup(duration,
                        tickerTiming ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() - cpuStart : 0L,
                        admitted, ticked[0]);
                if (tickerTiming) {
                    group.batch().recordGroupDuration(duration);
                    group.batch().recordGroupStart(start);
                    group.batch().recordGroupEnd(start + duration);
                }
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("block entity batch " + coordinate, duration);
                this.blockEntityTicksOnWorkers.addAndGet(ticked[0]);
                if (tickerTiming) {
                    // Retiring an empty region takes the regionizer write lock, which every finishing group of
                    // the batch wants at the same time: the one shared section a group enters after its tickers.
                    final long retireStart = System.nanoTime();
                    this.regionizer.removeIfEmpty(region);
                    RegionBlockEntityTickAttribution.recordGroupRetire(System.nanoTime() - retireStart);
                } else {
                    this.regionizer.removeIfEmpty(region);
                }
            }
        };
    }

    /**
     * Orders the tickers of a worker group's chunks like the level's own ticker list, and hands the chunks
     * whose snapshot went stale back to the server thread.
     * <p>
     * Each chunk's admissions are already in ticker order, so a group of one chunk needs no work. A group of
     * several chunks merges them by ticker sequence, which reproduces the order the level's ticker list would
     * have ticked that region in. A chunk that unloaded or changed its ticker set since the plan handed it
     * over is not ticked from its stale snapshot: its tickers are revalidated and ticked on the server thread
     * after the batch, and the stale snapshot is counted.
     */
    private List<RegionBlockEntityTickAdmission> orderBlockEntityGroup(RegionBlockEntityTickBatch.Group group, List<RegionBlockEntityTickChunk> chunks) {
        final RegionLayout layout = this.layout;
        if (chunks.size() == 1) {
            final RegionBlockEntityTickChunk chunk = chunks.get(0);
            if (!chunk.isCurrent(layout)) {
                this.deferStaleBlockEntityChunk(group, chunk);
                return List.of();
            }
            return chunk.admissions();
        }
        final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>(countBlockEntityTickers(chunks));
        for (final RegionBlockEntityTickChunk chunk : chunks) {
            if (!chunk.isCurrent(layout)) {
                this.deferStaleBlockEntityChunk(group, chunk);
                continue;
            }
            ordered.addAll(chunk.admissions());
        }
        ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
        return ordered;
    }

    /**
     * Hands the tickers of a chunk whose snapshot went stale to the server-thread fallback of {@code group}.
     */
    private void deferStaleBlockEntityChunk(RegionBlockEntityTickBatch.Group group, RegionBlockEntityTickChunk chunk) {
        this.staleBlockEntityChunkSnapshots.incrementAndGet();
        this.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT, chunk.admissions().size());
        group.batch().addFallbackChunk(chunk);
    }

    /**
     * Ticks the block-entity chunks whose region could not be leased safely, whose snapshot went stale before
     * the worker ticked it, and the tickers a worker handed back, on the server thread after the batch.
     */
    private void tickFallbackBlockEntities(RegionBlockEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final RegionBlockEntityTickChunk chunk : batch.fallbackChunks()) {
            this.tickBlockEntityChunkInline(level, chunk);
        }
        for (final RegionBlockEntityTickAdmission ticker : batch.fallbackTickers()) {
            this.tickBlockEntityInline(level, ticker.ticker());
        }
    }

    /** Ticks the tickers of one chunk on the server thread, in their collected order. */
    private void tickBlockEntityChunkInline(ServerLevel level, RegionBlockEntityTickChunk chunk) {
        for (final RegionBlockEntityTickAdmission admission : chunk.admissions()) {
            this.tickBlockEntityInline(level, admission.ticker());
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

        final RegionScheduledTickBatch<T> batch = new RegionScheduledTickBatch<>(level, phase, this.deferredBlockUpdateBudget);
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
        // A scheduled callback that writes a block records its light recheck and block update for the barrier, so
        // the batch admits a bounded number of ticks. The ticks beyond the bound run on the server thread after the
        // workers finished, where their writes need no record.
        final int admittedTickCount = Math.min(ticks.size(),
                this.deferredBlockUpdateBudget / RegionTickSlice.SCHEDULED_TICK.reservationPerUnit());
        final List<ScheduledTick<T>> admittedTicks = admittedTickCount == ticks.size() ? ticks : ticks.subList(0, admittedTickCount);
        if (admittedTickCount < ticks.size()) {
            batch.addFallback(ticks.subList(admittedTickCount, ticks.size()));
            this.refusedDeferredBlockUpdates[RegionTickSlice.SCHEDULED_TICK.ordinal()].addAndGet(ticks.size() - admittedTickCount);
        }
        Throwable failure = null;
        try {
            try {
                this.leaseScheduledTickGroups(batch, admittedTicks, callback, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                try {
                    this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, barrier));
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    }
                }
            }
        } finally {
            // A scheduled callback that wrote a block recorded its light recheck and block update; they run before
            // the server-thread fallback ticks of this phase observe the written state.
            failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
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
                this.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region), batch.nextRandomSeed(),
                        group -> this.withRegionMailboxDelivery(batch.level(), region, this.scheduledTickGroup(group, callback))));
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
                            try {
                                this.currentPropagationOrigin.set(tick.pos());
                                try {
                                    callback.accept(tick.pos(), tick.type());
                                } finally {
                                    this.currentPropagationOrigin.remove();
                                }
                            } catch (Throwable throwable) {
                                if (RegionWorkerChunkUnavailableException.mustNotReplay(throwable)) {
                                    continue;
                                }
                                // The failure is recorded for the server thread instead of failing the group with
                                // it; the group stops at its first failing tick, like every other worker group.
                                this.recordWorkerTickFailure(group.batch(), coordinate, null, throwable);
                                return;
                            }
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
     * Ticks the chunks of one level tick on the region workers and waits for the batch.
     * <p>
     * Runs on the server thread after the level's own chunk loop decided which chunks tick: natural spawning,
     * the inhabited time update, the custom spawners and the per-chunk broadcast already ran there. The batch is
     * published under the lifecycle read gate before any region lease is acquired, so a concurrent shutdown or
     * level unload observes it and waits instead of retiring regions that the batch still holds or is about to
     * lease. Region ownership is re-resolved after the collection, so a region that merged or split between the
     * decision and the lease acquisition is replaced by its authoritative successor instead of dropping the
     * chunk. Chunks whose region cannot be leased safely run on the server thread after the batch rather than
     * being dropped.
     * <p>
     * The server thread waits for the whole batch before the level broadcast and the rest of the level tick
     * continue. The deferred effects of the workers are applied in this order: the section publications of the
     * moves, the removals, the admissions, the light rechecks and block updates of the block writes, the lightning
     * strikes the workers rolled, the chunks that could not be leased, and finally the {@code LevelTicks}
     * mutations, so a random tick never mutates a server-owned structure from a worker.
     */
    void tickChunks(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed, RegionTickBarrier barrier) {
        // The server thread is done deciding which chunks it hands over; the attribution of the slice closes its
        // plan segment here, before the batch is published.
        RegionChunkTickAttribution.endPlanSegment();
        if (chunks.isEmpty()) {
            return;
        }

        final RegionChunkTickBatch batch = new RegionChunkTickBatch(level, randomTickSpeed, this.deferredBlockUpdateBudget);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this collection. Its region state is retired and no batch may be
            // published for it: the collected chunks must not tick for a level that already unloaded.
            this.skippedChunkTicks.addAndGet(chunks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the collection and the batch; the collected chunks still have to
            // tick, but no batch may be published after shutdown started.
            for (final RegionChunkTickEntry chunk : chunks) {
                this.tickChunkOnServerThread(level, chunk, randomTickSpeed);
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it (fallback
        // work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        // The opt-in attribution is decided once per batch, never per chunk.
        final boolean attribution = RegionChunkTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseChunkTickGroups(batch, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = runBatchCompletion(failure, () -> this.applyDeferredChunkTickSectionUpdates(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredChunkTickRemovals(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredChunkTickAdditions(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
                failure = runBatchCompletion(failure, () -> this.applyChunkTickLightning(batch));
                failure = runBatchCompletion(failure, () -> this.tickFallbackChunkTicks(batch));
                failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
                batch.finish();
                this.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Spawns for {@code chunks} on the workers of their regions and waits for the batch.
     * <p>
     * Runs on the server thread: the batch is published under the lifecycle read gate before any region lease is
     * acquired, exactly like the other slices, so a concurrent shutdown or level unload waits for it instead of
     * retiring a region the batch still holds. The per-region spawn state and the per-chunk answers are built
     * here, on the server thread, while the groups are leased: they read the region entity index and the chunk
     * map, which only the server thread may touch.
     * <p>
     * The worker bodies only read chunks and mutate entities they create, so the batch runs before the chunk tick
     * batch of the same level tick: no worker of this slice writes a block that another region's spawn body could
     * read. Every chunk a worker cannot spawn for - an unleased region, a truncated body, a closed level - is
     * spawned on the server thread after the barrier instead of being dropped.
     */
    void spawnChunks(ServerLevel level, List<RegionSpawnChunk> chunks, RegionSpawnPlan plan, RegionTickBarrier barrier) {
        // The server thread is done deciding which chunks may spawn; the attribution of the slice closes its plan
        // segment here, before the batch is published.
        RegionSpawnAttribution.endPlanSegment();
        if (chunks.isEmpty()) {
            return;
        }

        final RegionSpawnBatch batch = new RegionSpawnBatch(level, plan.spawnFriendlies(), plan.spawnEnemies(),
                plan.spawnPersistent(), this.deferredBlockUpdateBudget);
        final TickBatchPublication publication = this.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this collection. Its region state is retired and no batch may be
            // published for it: the collected chunks must not spawn for a level that already unloaded.
            this.skippedSpawnChunks.addAndGet(chunks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the collection and the batch; the collected chunks still have to
            // spawn, but no batch may be published after shutdown started.
            for (final RegionSpawnChunk chunk : chunks) {
                plan.spawnInline(chunk.chunk());
            }
            return;
        }
        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionSpawnAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseSpawnGroups(batch, plan, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.runRegionBatch(level, () -> batch.submitAndAwait(this.workerPool, barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = runBatchCompletion(failure, () -> this.applyDeferredSpawnRemovals(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredSpawnAdditions(batch));
                failure = runBatchCompletion(failure, () -> this.applyDeferredBlockUpdates(batch));
                failure = runBatchCompletion(failure, () -> this.spawnFallbackChunks(batch, plan));
                failure = runBatchCompletion(failure, () -> this.applyRegionTickMutations(batch.regionTickMutations()));
                batch.finish();
                this.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups the collected chunks by their authoritative region, takes the lease of every group and builds the
     * spawn state of that region.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be taken
     * here. A region that dies while its lease is acquired is re-resolved on the next attempt. Anything that
     * cannot be resolved to a live, leasable region is spawned on the server thread instead.
     */
    private void leaseSpawnGroups(RegionSpawnBatch batch, RegionSpawnPlan plan, List<RegionSpawnChunk> pending, RegionTickBarrier barrier) {
        List<RegionSpawnChunk> remaining = pending;
        int batchedChunks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionSpawnChunk> grouping = new RegionTickGrouping<>();
            final List<RegionSpawnChunk> retry = new ArrayList<>();
            for (final RegionSpawnChunk chunk : remaining) {
                final RegionState region = this.regionizer.getOrCreate(chunk.region(this.layout, batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionSpawnChunk> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                final List<RegionSpawnChunk> regionChunks = grouping.items(region);
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be acquired again
                    // and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(regionChunks);
                    continue;
                }
                leasedAny = true;
                batchedChunks += regionChunks.size();
                this.addLeasedGroup(region, () -> {
                    // The state of the region is built from the region's own entities, on the server thread, from
                    // the same full-chunk getter the vanilla state uses; its cap is the level-wide counter of the
                    // tick, which the plan counted before any worker ran.
                    final RegionSpawnState.Built built = plan.buildRegionState(region, regionChunks);
                    batch.addGroup(region, regionChunks, built.state(), built.neighborhood(),
                            group -> this.withRegionMailboxDelivery(batch.level(), region, this.spawnGroup(group)));
                });
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final RegionSpawnChunk chunk : remaining) {
            batch.addFallback(chunk);
            this.unleasedSpawnChunks.incrementAndGet();
        }
        if (batchedChunks > 0 && this.spawnWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region natural spawning is now executing on region workers: {} chunks", batchedChunks);
        }
    }

    /**
     * The worker task of one region: it spawns for the chunks of that region in collection order.
     * <p>
     * The group holds the lease of the region and the entity ownership lease of the entities stored in it, so a
     * spawn body reaches entity storage only through the region-scoped query and every admission it makes is
     * deferred to the barrier. The chunk's own random source is installed for the whole spawn body, so every
     * {@code level.random} draw of the body resolves it instead of the shared vanilla source, and the per-chunk
     * answers of the region are installed so the body never reads the server-owned chunk map or the entity
     * manager's visibility map.
     * <p>
     * A spawn body that needs a chunk the worker may not load cannot be re-run - the mobs that already spawned
     * would spawn twice - so the chunk's body stops where the refusal happened and the chunk is counted as
     * truncated.
     */
    private Runnable spawnGroup(RegionSpawnBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.batch().level();
        final List<RegionSpawnChunk> chunks = group.chunks();
        final boolean spawnFriendlies = group.batch().spawnFriendlies();
        final boolean spawnEnemies = group.batch().spawnEnemies();
        final boolean spawnPersistent = group.batch().spawnPersistent();
        return () -> {
            final long start = System.nanoTime();
            final int[] spawned = { 0 };
            final int[] truncated = { 0 };
            try {
                region.beginOwnership();
                // A spawn body writes no scheduled tick in vanilla, but a modded spawn hook can; the queue is the
                // one every slice defers into, so nothing has to be special-cased if it does.
                this.currentRegionTickMutations.set(group.batch().regionTickMutations());
                try {
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    try {
                        final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                        try {
                            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                                for (final RegionSpawnChunk chunk : chunks) {
                                    if (!region.isOwnedByCurrentThread() || this.regionizer.isLevelClosed(level)
                                            || group.batch().isAdditionSeamClosed()) {
                                        // The lease is gone, the level unloaded or the batch can no longer admit:
                                        // the remaining chunks of the region keep the server-thread body.
                                        group.batch().addFallback(chunk);
                                        continue;
                                    }
                                    if (!group.batch().tryReserveBlockUpdates()) {
                                        // The batch cannot reserve the block-write effects of this chunk, so its
                                        // spawn body is refused before it starts and spawns on the server thread.
                                        group.batch().addFallback(chunk);
                                        this.countRefusedDeferredBlockUpdate(RegionTickSlice.SPAWN);
                                        continue;
                                    }
                                    final RandomSource random = chunk.random(level, coordinate);
                                    this.currentSpawnRandom.set(random);
                                    this.currentSpawnNeighborhood.set(group.neighborhood());
                                    try {
                                        net.minecraft.world.level.NaturalSpawner.spawnForChunk(level, chunk.chunk(), group.state(),
                                                spawnFriendlies, spawnEnemies, spawnPersistent);
                                        ++spawned[0];
                                    } catch (Throwable throwable) {
                                        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                                            // The failure is recorded for the server thread instead of failing the
                                            // group with it; the group stops at its first failing chunk.
                                            this.recordWorkerTickFailure(group.batch(), coordinate, null, throwable);
                                            return;
                                        }
                                        // The spawns this body already deferred stay deferred; the rest of the
                                        // chunk's spawn attempt is lost and counted.
                                        ++truncated[0];
                                    } finally {
                                        this.currentSpawnNeighborhood.remove();
                                        this.currentSpawnRandom.remove();
                                    }
                                }
                            });
                        } finally {
                            RegionNeighborUpdateScope.exit(previousNeighbors);
                        }
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.currentRegionTickMutations.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("spawning batch " + coordinate, duration);
                this.spawnChunksOnWorkers.addAndGet(spawned[0]);
                this.truncatedSpawnChunks.addAndGet(truncated[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Spawns for the chunks whose region could not be leased or whose body was truncated on the server thread
     * after the batch.
     * <p>
     * The chunks were decided and collected on the server thread, so they must not go through the generic region
     * handoff again: that could defer them to a later region tick or drop them on a closed level. A chunk whose
     * level identity closed while the batch ran is counted as skipped instead of spawning into a retired level.
     */
    private void spawnFallbackChunks(RegionSpawnBatch batch, RegionSpawnPlan plan) {
        final ServerLevel level = batch.level();
        final Collection<RegionSpawnChunk> fallback = batch.fallbackChunks();
        if (fallback.isEmpty()) {
            return;
        }
        if (this.regionizer.isLevelClosed(level)) {
            this.skippedSpawnChunks.addAndGet(fallback.size());
            fallback.clear();
            return;
        }
        for (final RegionSpawnChunk chunk : fallback) {
            plan.spawnInline(chunk.chunk());
        }
        fallback.clear();
    }

    /**
     * Applies the admissions that a spawn body deferred to the barrier.
     * <p>
     * Every mob a region spawns is one record of its riding graph; the barrier runs the unmodified vanilla
     * admission on the server thread. A record that cannot be admitted is counted as dropped, because the entity
     * never reached the level and nothing retries it.
     */
    private void applyDeferredSpawnAdditions(RegionSpawnBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    /**
     * Applies the removals that a spawn body deferred to the barrier.
     * <p>
     * A {@code finalizeSpawn} can remove an entity - vanilla does not, but a modded one can - so the slice
     * carries the same removal seam as the other spawning slices.
     */
    private void applyDeferredSpawnRemovals(RegionSpawnBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Groups {@code pending} chunks by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt. Anything
     * that cannot be resolved to a live, leasable region is recorded as a server-thread fallback.
     */
    private void leaseChunkTickGroups(RegionChunkTickBatch batch, List<RegionChunkTickEntry> pending, RegionTickBarrier barrier) {
        List<RegionChunkTickEntry> remaining = pending;
        int batchedChunks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionChunkTickEntry> grouping = new RegionTickGrouping<>();
            final List<RegionChunkTickEntry> retry = new ArrayList<>();
            for (final RegionChunkTickEntry chunk : remaining) {
                final RegionState region = this.regionizer.getOrCreate(chunk.region(this.layout, batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionChunkTickEntry> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                final List<RegionChunkTickEntry> regionChunks = grouping.items(region);
                if (batch.leases(region)) {
                    // An earlier group of this same batch already owns the lease. It cannot be acquired again
                    // and must never be awaited on this thread.
                    batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                    continue;
                }
                if (!this.acquireRegionLease(region, barrier)) {
                    next.addAll(regionChunks);
                    continue;
                }
                leasedAny = true;
                batchedChunks += regionChunks.size();
                this.addLeasedGroup(region, () -> batch.addGroup(region, regionChunks,
                        group -> this.withRegionMailboxDelivery(batch.level(), region, this.chunkTickGroup(group))));
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final RegionChunkTickEntry chunk : remaining) {
            batch.addFallback(chunk);
            this.unleasedChunkTicks.incrementAndGet();
        }
        if (batchedChunks > 0 && this.chunkTickWorkersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region chunk ticks are now executing on region workers: {} chunks", batchedChunks);
        }
    }

    /**
     * The worker task of one region: it ticks the chunks of that region in collection order.
     * <p>
     * The group holds the lease of the region and the entity ownership lease of the entities stored in it, so a
     * random tick reaches entity storage only through the region-scoped query and every server-owned effect it
     * causes is deferred to the barrier. The chunk's own random source is installed for the whole body, so
     * {@code level.random} draws of the chunk body and the position selection of the level resolve it instead of
     * the shared vanilla sources.
     * <p>
     * A chunk tick that needs a chunk the worker may not load cannot be re-run - the random ticks that already
     * ran would run twice - so the chunk's tick stops where the refusal happened and the chunk is counted as
     * truncated.
     */
    private Runnable chunkTickGroup(RegionChunkTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.batch().level();
        final List<RegionChunkTickEntry> chunks = group.chunks();
        final int randomTickSpeed = group.batch().randomTickSpeed();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                // LevelTicks is server-owned storage: a scheduled tick a random tick starts is recorded here and
                // applied by the publishing server thread after the batch, never from a worker.
                this.currentRegionTickMutations.set(group.batch().regionTickMutations());
                try {
                    // The ownership lease covers exactly this worker tick loop: it is opened after the region
                    // lease and closed before it, so a worker task outside the loop never sees it.
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    try {
                        // Neighbor cascades of the block writes of a chunk tick stay inside this context, so two
                        // workers never mutate the same collecting updater.
                        final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                        try {
                            this.runWithContext(new BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                                for (final RegionChunkTickEntry chunk : chunks) {
                                    if (!region.isOwnedByCurrentThread() || this.regionizer.isLevelClosed(level)) {
                                        // The lease is gone or the level unloaded underneath the group: the
                                        // remaining chunks of the region keep the server-thread body.
                                        group.batch().addFallback(chunk);
                                        continue;
                                    }
                                    final RandomSource random = chunk.random(level);
                                    this.currentChunkTickRandom.set(random);
                                    this.currentChunkTickPositionRandom.set(random);
                                    this.currentChunkTickLightning.set(group.batch().lightningStrikes());
                                    try {
                                        level.tickChunk(chunk.chunk(), randomTickSpeed);
                                        ++ticked[0];
                                    } catch (Throwable throwable) {
                                        // A chunk request the chunk source refused is a truncation, not a
                                        // failure: the random ticks that already ran must not run again.
                                        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                                            // The failure is recorded for the server thread instead of failing the
                                            // group with it; the group stops at its first failing chunk.
                                            this.recordWorkerTickFailure(group.batch(), coordinate, null, throwable);
                                            return;
                                        }
                                        this.truncatedChunkTicks.incrementAndGet();
                                    } finally {
                                        this.currentChunkTickLightning.remove();
                                        this.currentChunkTickPositionRandom.remove();
                                        this.currentChunkTickRandom.remove();
                                    }
                                }
                            });
                        } finally {
                            RegionNeighborUpdateScope.exit(previousNeighbors);
                        }
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.currentRegionTickMutations.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("chunk tick batch " + coordinate, duration);
                this.chunkTicksOnWorkers.addAndGet(ticked[0]);
                this.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Ticks the chunks whose region could not be leased safely on the server thread after the batch.
     * <p>
     * The chunks were decided and collected on the server thread, so they must not go through the generic region
     * handoff again: that could defer them to a later region tick or drop them on a closed level. A chunk whose
     * level identity closed while the batch ran is counted as skipped, exactly like the collected chunks of a
     * closed level.
     */
    private void tickFallbackChunkTicks(RegionChunkTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final RegionChunkTickEntry chunk : batch.fallbackChunks()) {
            this.tickChunkOnServerThread(level, chunk, batch.randomTickSpeed());
        }
    }

    /**
     * Ticks one collected chunk on the server thread.
     * <p>
     * The chunk draws from the same per-chunk random source it would have drawn from on a worker, so a chunk
     * that falls back produces the same selection as a chunk that is dispatched, and a retried chunk never
     * depends on which thread ticked it.
     */
    /**
     * Ticks one chunk inline on the server thread with the chunk's own random source.
     * <p>
     * A chunk that a plan refuses before its body has to select exactly the positions it would select on a
     * worker, so the selection never depends on the dispatch decision or on the thread. The caller owns the
     * refusal counter, because the reason differs per call site.
     */
    void tickChunkInline(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        final RandomSource random = RegionChunkTickEntry.of(chunk).random(level);
        this.currentChunkTickRandom.set(random);
        this.currentChunkTickPositionRandom.set(random);
        try {
            level.tickChunk(chunk, randomTickSpeed);
        } finally {
            this.currentChunkTickPositionRandom.remove();
            this.currentChunkTickRandom.remove();
        }
    }

    private void tickChunkOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed) {
        if (this.regionizer.isLevelClosed(level)) {
            this.skippedChunkTicks.incrementAndGet();
            return;
        }
        this.fallbackChunkTicks.incrementAndGet();
        final RandomSource random = chunk.random(level);
        this.currentChunkTickRandom.set(random);
        this.currentChunkTickPositionRandom.set(random);
        try {
            level.tickChunk(chunk.chunk(), randomTickSpeed);
        } finally {
            this.currentChunkTickPositionRandom.remove();
            this.currentChunkTickRandom.remove();
        }
    }

    /**
     * Applies the light rechecks and block update publications the worker groups of {@code batch} deferred, in the
     * order the batch bodies recorded them, and attributes them to the slice of the batch.
     * <p>
     * Both effects are level-wide, not partitioned per region: the light engine of a level owns one set of queues
     * and one light thread, and the block update publication invalidates the level-wide path type cache and
     * recomputes the paths of every navigating mob of the level. A worker of any slice therefore only recorded
     * them, and this method runs the vanilla calls on the server thread, after the barrier of the batch that
     * recorded them.
     * <p>
     * A level that closed while the batch ran drops the records instead of publishing block updates for a level
     * that no longer exists; every dropped record is counted. One failing record does not strand the ones behind
     * it, so the queue is always empty when this method returns.
     */
    private void applyDeferredBlockUpdates(RegionTickBatch batch) {
        final RegionDeferredBlockUpdates updates = batch.blockUpdates();
        if (updates.isEmpty()) {
            return;
        }
        final int slice = batch.slice().ordinal();
        final ServerLevel level = batch.level();
        if (this.regionizer.isLevelClosed(level)) {
            this.droppedDeferredBlockUpdates[slice].addAndGet(updates.discard());
            return;
        }
        final ServerChunkCache chunkSource = level.getChunkSource();
        final Throwable[] failure = new Throwable[1];
        final int applied = updates.drain(update -> {
            try {
                switch (update) {
                    case RegionDeferredBlockUpdates.LightSectionStatus status -> chunkSource.getLightEngine()
                            .updateSectionStatus(status.pos(), status.empty());
                    case RegionDeferredBlockUpdates.LightCheck check -> chunkSource.getLightEngine().checkBlock(check.pos());
                    case RegionDeferredBlockUpdates.BlockUpdate blockUpdate -> level.sendBlockUpdated(blockUpdate.pos(),
                            blockUpdate.oldState(), blockUpdate.newState(), blockUpdate.flags());
                }
            } catch (Throwable throwable) {
                if (failure[0] == null) {
                    failure[0] = throwable;
                } else {
                    failure[0].addSuppressed(throwable);
                }
            }
        });
        this.appliedDeferredBlockUpdates[slice].addAndGet(applied);
        if (failure[0] != null) {
            throwUnchecked(failure[0]);
        }
    }

    /**
     * Resolves the lightning strikes the chunk tick workers rolled and applies them on the server thread.
     * <p>
     * A worker may not resolve a strike itself: the target comes from the POI manager (a lightning rod within
     * 128 blocks) and from entity storage, and it can be in another region, so the strike is applied here after
     * the barrier with the level random, exactly like the vanilla thunder body. A strike whose level closed
     * while the batch ran is dropped instead of applying after the unload.
     */
    private void applyChunkTickLightning(RegionChunkTickBatch batch) {
        final Queue<BlockPos> strikes = batch.lightningStrikes();
        if (strikes.isEmpty()) {
            return;
        }
        final ServerLevel level = batch.level();
        if (this.shutdown || this.regionizer.isLevelClosed(level)) {
            strikes.clear();
            return;
        }
        BlockPos rolled;
        while ((rolled = strikes.poll()) != null) {
            level.tickLightning(rolled);
            this.chunkTickLightningStrikes.incrementAndGet();
        }
    }

    /**
     * {@return true when every chunk around {@code chunkPos} is loaded}
     * <p>
     * A random tick, an entity tick or a spawn body of the chunk can read or write the eight neighboring chunks,
     * and a worker may not load a chunk: the chunk source refuses the request instead of waiting for the server
     * thread that waits for the worker. A chunk with an unloaded neighbor therefore keeps the server-thread body.
     */
    boolean isLoadedChunkNeighborhood(ServerLevel level, ChunkPos chunkPos) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (offsetX == 0 && offsetZ == 0) {
                    continue;
                }
                if (!chunkSource.hasChunk(chunkPos.x + offsetX, chunkPos.z + offsetZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Counts one chunk of a boundary chunk tick that the plan handed to the region batch.
     * <p>
     * A chunk is a boundary chunk when a chunk of its eight-neighbour neighbourhood belongs to another region.
     * Such a chunk is dispatched since a write of its body that leaves the region is recorded as a border block
     * write and applied by the target region in the same tick, so the count is the share of the level's chunk
     * ticks the relaxed rule moved off the server thread. It is not a fallback and does not add to
     * {@link #fallbackChunkTicks}.
     */
    void countBoundaryChunkTick() {
        this.boundaryChunkTicks.incrementAndGet();
    }

    /** Counts one chunk the plan kept on the server thread because a neighboring chunk is not loaded. */
    void countUnloadedNeighborhoodChunkTick() {
        this.unloadedNeighborhoodChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
    }

    /** Counts one chunk collected after its batch already ran, so the server thread ticked it inline. */
    void countLateChunkTick() {
        this.lateChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
    }

    /**
     * Counts one chunk the plan kept on the server thread because the batch had already admitted its whole
     * deferred update budget.
     */
    void countDeferredUpdateBudgetChunkTick() {
        this.deferredUpdateBudgetChunkTicks.incrementAndGet();
        this.fallbackChunkTicks.incrementAndGet();
        this.countRefusedDeferredBlockUpdate(RegionTickSlice.CHUNK_TICK);
    }

    /**
     * Counts one unit of {@code slice} that a batch refused before its body because it could not reserve the
     * block-write effects of the unit; the unit ran on the server thread instead.
     */
    private void countRefusedDeferredBlockUpdate(RegionTickSlice slice) {
        this.refusedDeferredBlockUpdates[slice.ordinal()].incrementAndGet();
    }

    /** {@return one counter per {@link RegionTickSlice}, indexed by the ordinal of the slice} */
    private static AtomicLong[] newSliceCounters() {
        final AtomicLong[] counters = new AtomicLong[RegionTickSlice.values().length];
        for (int index = 0; index < counters.length; ++index) {
            counters[index] = new AtomicLong();
        }
        return counters;
    }

    /**
     * Counts one tick failure of a worker group of {@code batch} and records it for the server thread.
     * <p>
     * Called on the worker thread that caught the failure, right before the group stops. The count is per slice,
     * so the diagnostics separate a failing entity tick from a failing block-entity, chunk, scheduled or spawn
     * body, and it counts every failure including the ones past the bound of the record. The record itself
     * carries the context and is reported by {@link RegionTickBatch#reportTickFailures()} on the server thread.
     */
    private void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, @Nullable Entity entity, Throwable throwable) {
        this.workerTickFailures[batch.slice().ordinal()].incrementAndGet();
        batch.recordTickFailure(coordinate, entity, throwable);
    }

    /** Counts one chunk whose vanilla spawn gate refused it, so vanilla spawns nothing for it either. */
    void countGateRefusedSpawnChunk() {
        this.gateRefusedSpawnChunks.incrementAndGet();
    }

    /** Counts one chunk kept on the server thread because a neighboring chunk is not loaded. */
    void countUnloadedNeighborhoodSpawnChunk() {
        this.unloadedNeighborhoodSpawnChunks.incrementAndGet();
    }

    /**
     * Counts one chunk kept on the server thread because the batch had already admitted its whole deferred
     * update budget, so the block writes of its spawn body would have had nowhere to be recorded.
     */
    void countDeferredUpdateBudgetSpawnChunk() {
        this.deferredUpdateBudgetSpawnChunks.incrementAndGet();
        this.countRefusedDeferredBlockUpdate(RegionTickSlice.SPAWN);
    }

    /** Counts one chunk collected after its batch already ran, so the server thread spawned for it inline. */
    void countLateSpawnChunk() {
        this.lateSpawnChunks.incrementAndGet();
    }

    /**
     * Counts one mob category a spawn state refused because the level-wide mob cap was reached.
     * <p>
     * Called from the vanilla spawn loop of a spawn worker through the region threading hook. On the server
     * thread - the inline path of a refused chunk - nothing is counted: that path is the vanilla body, which the
     * vanilla diagnostics already report.
     */
    public void countSpawnCapRefusal() {
        if (this.currentSpawnNeighborhood.get() != null) {
            this.capRefusedSpawns.incrementAndGet();
        }
    }

    /**
     * Counts one spawn attempt that could not be recorded as a deferred admission, so no entity was created.
     * <p>
     * A spawn worker records every admission it makes for the barrier; an attempt that the batch refuses (its
     * seam is closed, the entity is already in the level or the lease is gone) reaches the storage guard, which
     * refuses it. The counter makes the lost spawn visible next to the admission counters of the barrier.
     */
    public void countRefusedSpawnAddition() {
        this.refusedSpawnAdditions.incrementAndGet();
    }

    /**
     * {@return the number of chunks one spawn batch may admit to its deferred update budget}
     * <p>
     * The plan asks before it collects a chunk, so a chunk whose spawn body would have no room for its block
     * writes keeps the server-thread body instead.
     */
    int spawnDeferredUpdateBudget() {
        return this.deferredBlockUpdateBudget / RegionTickSlice.SPAWN.reservationPerUnit();
    }

    /**
     * {@return the vanilla spawn-gate answer for {@code pos} of {@code level} in the spawn body executing on the
     * current thread, or {@code null} when the caller has to ask the level itself}
     * <p>
     * The vanilla body asks {@code ServerLevel.isNaturalSpawningAllowed} for a candidate position outside the
     * chunk that spawns. That read goes through the entity manager's visibility map, which only the server thread
     * may touch, so a spawn worker answers from the snapshot the plan recorded for its region instead.
     */
    @Nullable
    public Boolean spawnPositionAllowed(ServerLevel level, BlockPos pos) {
        final RegionSpawnNeighborhood neighborhood = this.currentSpawnNeighborhood.get();
        if (neighborhood == null) {
            return null;
        }
        final RegionThreadingContext context = this.currentContext();
        if (!(context instanceof BasicRegionThreadingContext basicContext) || basicContext.level().orElse(null) != level) {
            return null;
        }
        return neighborhood.isSpawnAllowed(ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
    }

    /** {@return true when the configured natural spawning slice actually dispatches chunks} */
    @ApiStatus.Internal
    public boolean isSpawnWorkerDispatchEffective() {
        return this.spawnWorkerExecutionEnabled;
    }

    /** {@return a copy of {@code counters} as plain longs, indexed by the ordinal of the slice} */
    private static long[] snapshot(AtomicLong[] counters) {
        final long[] snapshot = new long[counters.length];
        for (int index = 0; index < counters.length; ++index) {
            snapshot[index] = counters[index].get();
        }
        return snapshot;
    }

    /** {@return the per-slice block-update counters of the diagnostics snapshot} */
    private RegionThreadingDiagnostics.BlockUpdateCounters deferredBlockUpdateCounters() {
        return new RegionThreadingDiagnostics.BlockUpdateCounters(
                snapshot(this.appliedDeferredBlockUpdates),
                snapshot(this.droppedDeferredBlockUpdates),
                snapshot(this.refusedDeferredBlockUpdates));
    }

    /** {@return the per-slice worker tick failure counters of the diagnostics snapshot} */
    private RegionThreadingDiagnostics.TickFailureCounters tickFailureCounters() {
        return new RegionThreadingDiagnostics.TickFailureCounters(snapshot(this.workerTickFailures));
    }

    /** {@return the worker dispatch counters of the pool and of the packed region leases} */
    private RegionThreadingDiagnostics.WorkerDispatchCounters workerDispatchCounters() {
        final RegionWorkerPool.DispatchSnapshot dispatch = this.workerPool.dispatchSnapshot();
        return new RegionThreadingDiagnostics.WorkerDispatchCounters(
                dispatch.submissions(),
                dispatch.submitNanos(),
                dispatch.wakeNanos(),
                dispatch.idleWakeUps(),
                dispatch.spinWakeUps(),
                dispatch.parkWakeUps(),
                this.packedTasks.get(),
                this.packedRegions.get(),
                this.packedLeaseFallbacks.get(),
                this.workerPool.isPersistent(),
                this.packedRegionLeasesEnabled);
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
        // The task ran under a region lease outside a batch: a neighbour, shape, comparator or block-entity
        // update it sent to another region was captured for the fixpoint, and no batch barrier follows this
        // call. Flush the fixpoint here so a block event or a server-thread fallback tick still converges in
        // the tick it runs in. The fixpoint is not entered from a native worker: a worker's target region is
        // queued, not leased inline.
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            this.runBorderUpdateFixpoint(level);
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
     * <p>
     * The drain is a region phase of the level tick: it is opened before the first region is activated and
     * closed after the last worker was awaited, so no queued region task of the level runs next to a global
     * statement of the same level.
     */
    @ApiStatus.Internal
    public void drainLevelRegionTasks(ServerLevel level) {
        this.beginRegionPhase(level);
        try {
            this.drainLevelRegionTasks(level, levelBarrier(level));
        } finally {
            this.endRegionPhase(level);
        }
    }

    private void drainLevelRegionTasks(ServerLevel level, @Nullable RegionTickBarrier barrier) {
        final long now = System.nanoTime();
        // Every live region of this level opens the metric slot of this server tick before any of its work is
        // recorded, so a region that is never leased closes the slot as 0 ns instead of a missed tick.
        final long tickIndex = level.getGameTime();
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
            region.tickMetrics().beginTick(tickIndex, now);
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
                    this.chunkLoadAnswers.remove(level);
                    this.seededEntityTickLevels.remove(level);
                    this.regionizer.clearLevel(level);
                    // The mailbox and the phase state of the level die with its region state: the queued
                    // messages are dropped and counted, and a later post is refused instead of waiting for a
                    // tick of a level that no longer exists. No batch of the level is in flight here, so no
                    // worker can be inside a delivery or a phase transition either.
                    this.closeLevelMailbox(level);
                    this.levelTickPhases.remove(level);
                    final Set<RegionState> foreignReaders = this.regionsWithForeignReads.remove(level);
                    if (foreignReaders != null) {
                        foreignReaders.forEach(this::discardForeignReads);
                    }
                    final RegionBorderUpdates borderUpdates = this.borderUpdates.remove(level);
                    if (borderUpdates != null) {
                        this.borderUpdatesDropped.addAndGet(borderUpdates.drain().size() + borderUpdates.drainOverflow().size());
                        borderUpdates.discard();
                    }
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
        this.applyDeferredSectionUpdates(batch.level(), batch.drainDeferredSectionUpdates(), batch.isSectionUpdateSeamClosed());
    }

    /**
     * Applies the portal transitions of the worker-ticked entities of {@code batch} on the server thread.
     * <p>
     * A worker never resolves a portal transition: the destination search reads the {@code PoiManager} of the
     * destination level, which is one storage per level and is not partitioned per region. The worker records
     * the transition instead and the barrier runs the vanilla resolution here, once per entity per tick.
     * <p>
     * The transitions run first in the handoff, because the transition is the earliest level effect of the
     * tick it belongs to: {@code Entity#baseTick} runs it before the rest of the body. An entity the barrier
     * cannot transition (it was removed, it left the level, the level identity retired or the engine shut
     * down) keeps its own portal state, so the next tick of the entity records the transition again instead of
     * losing it; the refusal is counted.
     */
    private void applyDeferredPortalTransitions(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        final List<Entity> transitions = batch.drainDeferredPortalTransitions();
        if (transitions.isEmpty()) {
            return;
        }
        // Every drained record is one deferred transition, and every one of them is either applied or
        // refused below, so the counters never lose one silently.
        this.deferredPortalTransitionCount.addAndGet(transitions.size());
        if (this.shutdown || this.regionizer.isLevelClosed(level)) {
            this.refusedPortalTransitionCount.addAndGet(transitions.size());
            return;
        }
        Throwable failure = null;
        for (final Entity entity : transitions) {
            if (entity.isRemoved() || entity.level() != level) {
                this.refusedPortalTransitionCount.incrementAndGet();
                continue;
            }
            try {
                entity.completePortalTransition(level);
                this.appliedPortalTransitionCount.incrementAndGet();
            } catch (Throwable throwable) {
                this.refusedPortalTransitionCount.incrementAndGet();
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
     * Applies the section updates of the moves that a chunk tick worker deferred to the barrier.
     * <p>
     * A precipitation tick pushes the entities standing in the snow layer it raises out of the block, which is
     * an owned move of the chunk tick group, so it is deferred and applied exactly like the move of an entity
     * during its own tick.
     */
    private void applyDeferredChunkTickSectionUpdates(RegionChunkTickBatch batch) {
        this.applyDeferredSectionUpdates(batch.level(), batch.drainDeferredSectionUpdates(), batch.isSectionUpdateSeamClosed());
    }

    /**
     * Applies one drain of deferred section updates on the server thread.
     *
     * @see #applyDeferredEntitySectionUpdates(RegionEntityTickBatch)
     */
    private void applyDeferredSectionUpdates(ServerLevel level, List<RegionDeferredEntitySectionUpdates.DeferredSectionUpdate> updates, boolean seamClosed) {
        if (updates.isEmpty()) {
            return;
        }
        if (this.shutdown || this.regionizer.isLevelClosed(level) || seamClosed) {
            this.droppedDeferredSectionUpdateCount.addAndGet(updates.size());
            return;
        }
        Throwable failure = null;
        for (final RegionDeferredEntitySectionUpdates.DeferredSectionUpdate update : updates) {
            final Entity entity = update.entity();
            if (entity.isRemoved() || entity.level() != level || !entity.isAddedToLevel()) {
                this.droppedDeferredSectionUpdateCount.incrementAndGet();
                this.droppedDeferredSectionUpdateForRemovedEntityCount.incrementAndGet();
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
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Applies the removals of an owned entity that a block-entity worker deferred to the barrier.
     * <p>
     * A hopper that absorbs an item entity of its region removes it through the same vanilla funnel, so the
     * removal is deferred and applied exactly like the removal of a dying entity: the worker writes the
     * entity's own removal state, and this step runs the level callback on the server thread after the batch.
     */
    private void applyDeferredBlockEntityRemovals(RegionBlockEntityTickBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Applies the removals of an owned entity that a chunk tick worker deferred to the barrier.
     * <p>
     * A random tick that removes an entity of its region removes it through the same vanilla funnel, so the
     * removal is deferred and applied exactly like the removal of a dying entity: the worker writes the entity's
     * own removal state, and this step runs the level callback on the server thread after the batch.
     */
    private void applyDeferredChunkTickRemovals(RegionChunkTickBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * {@return the number of deferred entity removals this engine applied or repeated so far}
     * <p>
     * The difference of two reads around a drain is the number of removals that drain carried, which the
     * opt-in attribution reports as the count of the deferred removal step.
     */
    private long deferredRemovalCount() {
        return this.appliedDeferredRemovalCount.get() + this.repeatedDeferredRemovalCount.get();
    }

    /**
     * Applies one drain of deferred removals on the server thread.
     *
     * @see #applyDeferredEntityRemovals(RegionEntityTickBatch)
     */
    private void applyDeferredRemovals(ServerLevel level, RegionDeferredEntityRemovals.Drain drain, boolean seamClosed) {
        this.repeatedDeferredRemovalCount.addAndGet(drain.repeats());
        if (drain.removals().isEmpty()) {
            return;
        }
        if (this.shutdown || this.regionizer.isLevelClosed(level) || seamClosed) {
            this.droppedDeferredRemovalCount.addAndGet(drain.removals().size());
            return;
        }
        Throwable failure = null;
        for (final RegionDeferredEntityRemovals.DeferredRemoval removal : drain.removals()) {
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
     * Applies the admissions of new entities that a worker deferred to the barrier.
     * <p>
     * A worker that spawns an entity inside its leased region cannot admit it: the admission writes the
     * level's UUID set, its visible lookup, the entity section, the ticking list, the tracking and the region
     * index. The worker therefore constructs and configures the entity - the position writes of a pending
     * entity are field writes and are granted - and records the whole riding graph. This barrier runs the
     * unmodified {@code ServerLevel.addEntity} for every member of the graph on the server thread, so the
     * admission is written exactly like a server-thread spawn: the join event, the identity ledger, the
     * section of the entity's final position, the tracking callbacks, the ticking list and the region index.
     * <p>
     * The members are admitted in the recorded self-then-passengers order, so a vehicle is in the level before
     * its passengers. A graph is admitted whole or counted as dropped; a member the vanilla path refuses after
     * the graph was admitted partially stays admitted, because an admission cannot be rolled back, but the
     * record is counted as dropped so the loss is never silent.
     * <p>
     * An admission that cannot be applied is dropped without touching storage and counted in
     * {@link RegionThreadingDiagnostics#deferredEntityAdditionsDropped()}: the engine shut down, the level
     * identity closed, the entity was removed or left the level between the record and the barrier, a member
     * of the graph is already in the level, or a member's admission threw. The step runs after the deferred
     * removals, so a record whose entity was removed meanwhile is dropped by the state the removal left, and
     * before the fallback ticks and the ownership publication, so both observe the admitted entity.
     */
    private void applyDeferredEntityAdditions(RegionEntityTickBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    /**
     * Applies the admissions that a chunk tick worker deferred to the barrier.
     * <p>
     * A random tick that hatches an egg or otherwise spawns an entity is a worker spawn of the chunk tick
     * group, so it is deferred and admitted exactly like the spawn of an entity tick.
     */
    private void applyDeferredChunkTickAdditions(RegionChunkTickBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    /**
     * Applies one drain of deferred admissions on the server thread.
     *
     * @see #applyDeferredEntityAdditions(RegionEntityTickBatch)
     */
    private void applyDeferredAdditions(ServerLevel level, RegionDeferredEntityAdditions.Drain drain, boolean seamClosed) {
        this.recordedDeferredAdditionCount.addAndGet(drain.recorded());
        if (drain.additions().isEmpty()) {
            return;
        }
        if (this.shutdown || this.regionizer.isLevelClosed(level) || seamClosed) {
            this.droppedDeferredAdditionCount.addAndGet(drain.additions().size());
            return;
        }
        Throwable failure = null;
        for (final RegionDeferredEntityAdditions.DeferredEntityAddition addition : drain.additions()) {
            if (!canAdmitDeferredEntityAddition(level, addition)) {
                this.droppedDeferredAdditionCount.incrementAndGet();
                continue;
            }
            try {
                boolean admitted = true;
                for (final Entity member : addition.graph()) {
                    if (!level.addFreshEntity(member)) {
                        // The vanilla path refused a member - a duplicate UUID, a cancelled join event or a
                        // sealed identity. The members it already accepted stay in the level, because an
                        // admission cannot be rolled back, and the record is counted as dropped.
                        admitted = false;
                    }
                }
                if (admitted) {
                    this.appliedDeferredAdditionCount.incrementAndGet();
                } else {
                    this.droppedDeferredAdditionCount.incrementAndGet();
                }
            } catch (Throwable throwable) {
                this.droppedDeferredAdditionCount.incrementAndGet();
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
     * {@return true when the barrier can still admit the recorded riding graph of {@code addition}}
     * <p>
     * The whole graph is validated before the first member is admitted, so the common drop reasons - the
     * entity was removed or discarded by the worker after it recorded the admission, its level changed, a
     * member is already in the level - never leave half a graph behind.
     */
    private static boolean canAdmitDeferredEntityAddition(ServerLevel level, RegionDeferredEntityAdditions.DeferredEntityAddition addition) {
        for (final Entity member : addition.graph()) {
            if (member.isRemoved() || member.isAddedToLevel() || member.level() != level) {
                return false;
            }
        }
        return true;
    }

    /**
     * Closes the deferred seams of every active batch when shutdown starts.
     * <p>
     * A shut-down engine never runs a barrier again, so a batch that still ticks must not accept another
     * section update or another removal: the next worker move or removal is refused by the storage guard
     * instead of being granted and dropped. The updates and removals that already wait are released and
     * counted, so no entity reference stays reachable from the batch and nothing is applied after the
     * shutdown. A block-entity batch only defers removals, so that is the only seam it closes; a spawn batch
     * defers admissions and removals and closes both.
     */
    private void closeDeferredSeams() {
        for (final RegionTickBatch active : this.activeTickBatches) {
            if (active instanceof RegionEntityTickBatch entityBatch) {
                this.refusedPortalTransitionCount.addAndGet(entityBatch.closeDeferredPortalTransitions());
                this.droppedDeferredSectionUpdateCount.addAndGet(entityBatch.closeDeferredSectionUpdates());
                this.releaseDeferredRemovals(entityBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = entityBatch.closeDeferredAdditions();
                this.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
            } else if (active instanceof RegionBlockEntityTickBatch blockEntityBatch) {
                this.releaseDeferredRemovals(blockEntityBatch.closeDeferredRemovals());
            } else if (active instanceof RegionChunkTickBatch chunkTickBatch) {
                this.droppedDeferredSectionUpdateCount.addAndGet(chunkTickBatch.closeDeferredSectionUpdates());
                this.releaseDeferredRemovals(chunkTickBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = chunkTickBatch.closeDeferredAdditions();
                this.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
            } else if (active instanceof RegionSpawnBatch spawnBatch) {
                this.releaseDeferredRemovals(spawnBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = spawnBatch.closeDeferredAdditions();
                this.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
            }
        }
    }

    /**
     * Counts the removals a closed seam released as dropped and the repeats it counted.
     */
    private void releaseDeferredRemovals(RegionDeferredEntityRemovals.Drain released) {
        this.droppedDeferredRemovalCount.addAndGet(released.removals().size());
        this.repeatedDeferredRemovalCount.addAndGet(released.repeats());
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
     * <p>
     * The snapshot includes the per-region tick metrics of every live region and their aggregate. Both are
     * immutable, so a caller reads them without holding a lock of the engine, and a diagnostics call that
     * finds no region reports an empty region list and a summary of zero regions.
     */
    @ApiStatus.Internal
    public RegionThreadingDiagnostics diagnostics() {
        final List<RegionTickSnapshot> regionTicks = this.regionizer.regionTickSnapshots();
        long globalPhases = 0L;
        long regionPhases = 0L;
        long regionPhasesWithoutGlobalPhase = 0L;
        long abandonedGlobalSegments = 0L;
        for (final LevelTickPhaseState state : this.levelTickPhases.values()) {
            globalPhases += state.globalPhaseCount();
            regionPhases += state.regionPhaseCount();
            regionPhasesWithoutGlobalPhase += state.regionPhasesWithoutGlobalPhase();
            abandonedGlobalSegments += state.abandonedGlobalSegments();
        }
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
                this.chunkTickWorkerExecutionEnabled,
                this.spawnWorkerExecutionEnabled,
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
                this.nonTickingBlockEntityChunks.get(),
                this.entitiesLoadingBlockEntityChunks.get(),
                this.missingBlockEntityChunks.get(),
                this.staleBlockEntityChunkSnapshots.get(),
                this.scheduledTicksOnWorkers.get(),
                this.skippedScheduledTicks.get(),
                this.fallbackScheduledTicks.get(),
                this.chunkTicksOnWorkers.get(),
                this.skippedChunkTicks.get(),
                this.fallbackChunkTicks.get(),
                this.boundaryChunkTicks.get(),
                this.unloadedNeighborhoodChunkTicks.get(),
                this.truncatedChunkTicks.get(),
                this.lateChunkTicks.get(),
                this.unleasedChunkTicks.get(),
                this.chunkTickLightningStrikes.get(),
                this.deferredBlockUpdateCounters(),
                this.tickFailureCounters(),
                this.deferredUpdateBudgetChunkTicks.get(),
                this.nonPartitionedChunkTicks.get(),
                this.spawnChunksOnWorkers.get(),
                this.gateRefusedSpawnChunks.get(),
                this.unloadedNeighborhoodSpawnChunks.get(),
                this.deferredUpdateBudgetSpawnChunks.get(),
                this.lateSpawnChunks.get(),
                this.unleasedSpawnChunks.get(),
                this.truncatedSpawnChunks.get(),
                this.skippedSpawnChunks.get(),
                this.capRefusedSpawns.get(),
                this.refusedSpawnAdditions.get(),
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
                this.isChunkTickWorkerDispatchEffective(),
                this.isSpawnWorkerDispatchEffective(),
                this.entityTransferJournal.pendingCount(),
                this.entityTransferJournal.rememberedCount(),
                this.entityTransferJournal.rejectedRecordCount(),
                this.legacyDeferredEntityMoveCount.get(),
                this.retriedDeferredEntityMoveCount.get(),
                this.appliedDeferredSectionUpdateCount.get(),
                this.droppedDeferredSectionUpdateCount.get(),
                this.droppedDeferredSectionUpdateForRemovedEntityCount.get(),
                this.appliedDeferredRemovalCount.get(),
                this.droppedDeferredRemovalCount.get(),
                this.repeatedDeferredRemovalCount.get(),
                this.recordedDeferredAdditionCount.get(),
                this.appliedDeferredAdditionCount.get(),
                this.droppedDeferredAdditionCount.get(),
                RegionOwnershipViolationHandler.entityAdditionRejectionCount(),
                RegionWorkerChunkUnavailableException.refusalCount(),
                this.chunkFallbackEntityTicks.get(),
                this.chunkPreloadRefusedEntityTicks.get(),
                this.regionizer.writeLockAcquisitionCount(),
                this.batchedDeferredEntityMoveCount.get(),
                this.unchangedDeferredEntityMoveCount.get(),
                this.refusedDeferredEntityMoveBatchCount.get(),
                this.entityTaskMigrationCount.get(),
                this.entityTickRegionsOnWorkers.get(),
                this.fallbackEntityTickRegions.get(),
                this.entityTickRegionsWithoutEligibleChunk.get(),
                this.entityTicksOutsideRegion.get(),
                this.serverThreadOnlyEntityTicks.get(),
                this.entityTickPolicy.serverThreadOnlyTicks(),
                this.unindexedEntityTickStarts.get(),
                this.entityTickIndexFallbacks.get(),
                this.deferredPortalTransitionCount.get(),
                this.appliedPortalTransitionCount.get(),
                this.refusedPortalTransitionCount.get(),
                globalPhases,
                regionPhases,
                regionPhasesWithoutGlobalPhase,
                abandonedGlobalSegments,
                this.mailboxAcceptedMessages.get(),
                this.mailboxDeliveredMessages.get(),
                this.mailboxRefusedFullMessages.get(),
                this.mailboxRefusedClosedMessages.get(),
                this.mailboxDroppedRetiredMessages.get(),
                this.mailboxDroppedOnCloseMessages.get(),
                this.mailboxDeliveryFailures.get(),
                new RegionThreadingDiagnostics.BorderUpdateCounters(
                        this.borderUpdatesRecorded.get(),
                        this.borderUpdatesApplied.get(),
                        this.borderUpdatesDelayed.get(),
                        this.borderUpdatesDropped.get(),
                        this.borderUpdatePasses.get(),
                        this.borderUpdateRegionsRerun.get(),
                        this.borderUpdateWorstPasses.get(),
                        this.borderUpdatesOverflowed.get(),
                        this.foreignChunkReads.get(),
                        this.readOfUnownedChunk.get(),
                        this.readOfVersionlessChunk.get(),
                        this.foreignReadInvalidatedPasses.get(),
                        this.foreignReadRegionReruns.get(),
                        this.foreignReadSeedsDropped.get(),
                        this.borderBlockWritesRecorded.get(),
                        this.borderBlockWritesApplied.get(),
                        this.borderBlockWritesOverflowed.get(),
                        this.borderBlockWritesDropped.get(),
                        this.readAfterDeferredBlockWrite.get()),
                RegionPoiCounters.snapshot(),
                this.workerDispatchCounters(),
                RegionMetricsSummary.summarize(regionTicks),
                regionTicks);
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
                this.chunkLoadAnswers.clear();
                this.seededEntityTickLevels.clear();
                this.regionizer.clear();
                // Every mailbox is closed before the pool goes away: its queued messages are dropped and
                // counted, and a post from a worker that is still unwinding is refused instead of queued for a
                // tick that never comes. The phase state of the levels is meaningless after this point.
                for (final RegionMailbox mailbox : this.mailboxes.values()) {
                    mailbox.close();
                }
                this.mailboxes.clear();
                this.levelTickPhases.clear();
                for (final RegionBorderUpdates borderUpdates : this.borderUpdates.values()) {
                    this.borderUpdatesDropped.addAndGet(borderUpdates.drain().size() + borderUpdates.drainOverflow().size());
                    borderUpdates.discard();
                }
                this.borderUpdates.clear();
                // The global region has no coordinate and is not part of the region metrics, so its window is
                // released here: a shut-down engine holds no metric buffer at all.
                this.globalRegion.tickMetrics().release();
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
