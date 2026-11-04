/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.OptionalDynamic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Region-partitioned POI storage of one server level.
 * <p>
 * The storage answers the queries of the vanilla {@code PoiManager} from region-owned sections instead of one
 * level-wide mutable map, so a region worker can run a villager, golem, raider, bee, zombie or fox tick
 * without racing the server thread and without being pinned to it.
 * <p>
 * <b>Ownership.</b> A POI section belongs to the region that owns its chunk under the engine's
 * {@link RegionLayout}. The live section data is the vanilla {@link PoiSection} of the owning region, guarded
 * by that region's write lock: the region's own worker writes and reads it, and the server thread writes and
 * reads it because it is the thread that loads and unloads chunks, places and breaks the blocks that create
 * POIs, and serves the barrier handoffs. The lock is what makes the server thread's write safe next to a
 * worker's read of the same region; it is never held across a disk read or another region's lock.
 * <p>
 * <b>Snapshots.</b> At the end of every region phase the server thread publishes the sections written since
 * the last publish as immutable {@link RegionPoiSections} views per region. A query from a worker reads its
 * own region's live sections and the published views of every other region its box overlaps, in vanilla's
 * order: the vanilla {@code getInChunk} is reproduced per chunk and the vanilla {@code getInSquare},
 * {@code getInRange} and every query built on them are untouched, so a query returns the same records in the
 * same order a single-threaded manager returns for the same data.
 * <p>
 * <b>Staleness.</b> A local answer is live. A foreign answer is at most one publish old, i.e. one region phase:
 * a POI added in region B in tick T is visible to a query of region A in tick T+1, and to a local query of B
 * in tick T. Free-ticket counts are the exception and are not stale: a ticket acquire or release is a bounded
 * operation on the live record under the owning region's lock, so a {@code take} from region A books the
 * ticket in region B immediately and two regions can never take the same last ticket. A record a query read
 * from a published view that the owner has since removed is refused as stale instead of booking a ticket on a
 * POI that no longer exists.
 * <p>
 * <b>Village distance.</b> {@code sectionsToVillage} does not drain a level-wide tracker from a worker. The
 * vanilla tracker computes, for every section, the minimum graph distance to a section that holds an occupied
 * village-tagged POI, capped at 7, and the graph is the 26-neighbourhood of sections, so that distance is the
 * Chebyshev distance to the nearest such section and a pure function of the set of village-center sections.
 * The storage keeps the village-center sections of every region (live for the querying region, published for
 * the others) and answers the query from that index, memoized per section against a version that any center
 * change bumps.
 * <p>
 * <b>Loading and saving.</b> Loading stays the server thread's work: a chunk load or a query of the server
 * thread loads the POI column of the chunk from disk through the level's {@link SimpleRegionStorage}, exactly
 * like the vanilla section storage loads a column, and distributes its sections to the owning regions. A
 * worker never loads: a query whose box reaches a column no region has loaded falls back to the server thread
 * for that chunk and is counted. Saving writes a dirty column from the sections of its owning region on the
 * server thread, in the vanilla {@code Sections}/{@code DataVersion} format, so the regionized storage writes
 * the same files the vanilla manager writes.
 */
public final class RegionPoiStorage implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The highest level the vanilla village distance tracker keeps; a farther section answers this level. */
    private static final int MAX_VILLAGE_DISTANCE = PoiManager.MAX_VILLAGE_DISTANCE + 1;

    private final ServerLevel level;
    private final RegionLayout layout;
    private final SimpleRegionStorage storage;
    private final RegistryAccess registryAccess;
    private final LevelHeightAccessor heightAccessor;
    private final Map<RegionCoordinate, RegionPoiRegion> regions = new ConcurrentHashMap<>();
    /** The chunk columns with unsaved writes, as {@link ChunkPos#toLong()} keys. */
    private final Set<Long> dirtyColumns = ConcurrentHashMap.newKeySet();
    /** The chunks {@link #ensureLoadedAndValid} already asked the level to load, like the vanilla manager. */
    private final Set<Long> ensuredChunks = ConcurrentHashMap.newKeySet();
    /**
     * The region a test drives this storage as, instead of the region of the calling thread's lease.
     * <p>
     * A worker query is answered from the lease's live region and the published snapshots of the others, and
     * the equivalence test has to drive exactly that path without a native worker thread. The override is a
     * thread local, so it never leaks into another test or into production, and the storage treats it exactly
     * like a lease: the forced region is live, every other region is read from its published snapshot.
     */
    private static final ThreadLocal<RegionCoordinate> FORCED_QUERY_REGION = new ThreadLocal<>();

    public RegionPoiStorage(ServerLevel level, RegionLayout layout, SimpleRegionStorage storage, RegistryAccess registryAccess, LevelHeightAccessor heightAccessor) {
        this.level = level;
        this.layout = layout;
        this.storage = storage;
        this.registryAccess = registryAccess;
        this.heightAccessor = heightAccessor;
    }

    /**
     * Drives the query path of this storage as a worker of {@code region} on the current thread.
     * <p>
     * This is the test seam of the equivalence test: it makes the calling thread take the worker branch of
     * every query - live sections of {@code region}, published snapshots of the others, no loading - without a
     * native worker thread and an engine lease. Passing {@code null} restores the production decision.
     */
    @ApiStatus.Internal
    public static void forceQueryRegionForTesting(@Nullable RegionCoordinate region) {
        if (region == null) {
            FORCED_QUERY_REGION.remove();
        } else {
            FORCED_QUERY_REGION.set(region);
        }
    }

    ServerLevel level() {
        return this.level;
    }

    /** {@return true when the calling thread has to take the worker branch of a query} */
    private boolean isWorkerQuery() {
        return FORCED_QUERY_REGION.get() != null || RegionWorkerScope.isNativeWorkerThread();
    }

    /** {@return the region the calling thread queries as, or {@code null} for the server thread} */
    @Nullable
    private RegionCoordinate leasedRegion() {
        final RegionCoordinate forced = FORCED_QUERY_REGION.get();
        return forced != null ? forced : RegionEntityOwnershipScope.currentLeaseCoordinate(this.level);
    }

    int minSection() {
        return this.heightAccessor.getMinSection();
    }

    int maxSection() {
        return this.heightAccessor.getMaxSection();
    }

    // ---------------------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------------------

    /** Adds the POI {@code type} at {@code pos} to the region that owns it. */
    public void add(BlockPos pos, Holder<PoiType> type) {
        final RegionCoordinate owner = this.ownerOf(pos);
        final RegionPoiRegion region = this.regionFor(owner);
        if (!this.prepareWrite(region, pos)) {
            RegionThreadingHooks.runOnServerThread(this.level, () -> {
                this.addOnServerThread(pos, type);
                return null;
            });
            return;
        }
        region.add(pos, type);
    }

    private void addOnServerThread(BlockPos pos, Holder<PoiType> type) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        this.ensureColumnLoaded(new ChunkPos(pos), region);
        region.add(pos, type);
    }

    /** Removes the POI at {@code pos} from the region that owns it. */
    public void remove(BlockPos pos) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        if (!this.prepareWrite(region, pos)) {
            RegionThreadingHooks.runOnServerThread(this.level, () -> {
                final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(pos));
                this.ensureColumnLoaded(new ChunkPos(pos), serverRegion);
                serverRegion.remove(pos);
                return null;
            });
            return;
        }
        region.remove(pos);
    }

    /** Releases one ticket of the POI at {@code pos} in the region that owns it. */
    public boolean release(BlockPos pos) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        if (!this.prepareWrite(region, pos)) {
            return RegionThreadingHooks.runOnServerThread(this.level, () -> {
                final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(pos));
                this.ensureColumnLoaded(new ChunkPos(pos), serverRegion);
                return serverRegion.release(pos);
            });
        }
        return region.release(pos);
    }

    /**
     * Takes the first POI that matches and has a free ticket, in the order a single-threaded manager visits it.
     * <p>
     * The candidate list is the regionized {@code getInRange} with {@code HAS_SPACE}, so the order is vanilla's.
     * The ticket is then acquired on the live record of the owning region under that region's lock, which is
     * the one write a foreign region performs: a ticket is a bounded counter, and deferring it would let two
     * regions take the same last ticket. A candidate whose live record is gone is skipped as stale, and a
     * candidate another region took between the filter and the acquire is skipped as full.
     */
    public Optional<BlockPos> take(Predicate<Holder<PoiType>> typeFilter, BiPredicate<Holder<PoiType>, BlockPos> test, BlockPos pos, int radius) {
        for (final PoiRecord candidate : this.getInRangeList(typeFilter, pos, radius, PoiManager.Occupancy.HAS_SPACE)) {
            if (!test.test(candidate.getPoiType(), candidate.getPos())) {
                continue;
            }
            final RegionPoiRegion owner = this.regionFor(this.ownerOf(candidate.getPos()));
            final RegionPoiRegion.TicketResult result = owner.acquireTicket(candidate);
            if (result == RegionPoiRegion.TicketResult.ACQUIRED) {
                return Optional.of(candidate.getPos());
            }
            if (result == RegionPoiRegion.TicketResult.STALE) {
                RegionPoiCounters.recordStaleTake();
            }
        }
        return Optional.empty();
    }

    /**
     * {@return true when the calling thread may write the live section of {@code region} without a handoff}
     * <p>
     * The server thread may always write, and so may a native worker for the region it leases. A native worker
     * writing a foreign region hands the write to the server thread: the region's live section is the server
     * thread's storage as much as the owner's, and a structural write of a foreign slice is exactly the case
     * the ownership rule defers. A worker's write of its own region is applied inline because the caller is the
     * owner and the write has to be visible to the same tick's logic.
     */
    private boolean prepareWrite(RegionPoiRegion region, BlockPos pos) {
        if (!this.isWorkerQuery()) {
            this.ensureColumnLoaded(new ChunkPos(pos), region);
            return true;
        }
        final RegionCoordinate lease = this.leasedRegion();
        if (lease != null && lease.equals(region.coordinate())) {
            if (region.knowsColumn(new ChunkPos(pos).toLong())) {
                return true;
            }
            RegionPoiCounters.recordFallbackQuery();
            return false;
        }
        RegionPoiCounters.recordDeferredWrite();
        return false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Position reads
    // ---------------------------------------------------------------------------------------------------

    /** {@return whether the regionized storage can answer a position read of {@code pos} from a view} */
    public boolean exists(BlockPos pos, Predicate<Holder<PoiType>> typeFilter) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        if (this.readsLive(region, pos)) {
            return region.exists(pos, typeFilter);
        }
        if (region.publishedCovers(new ChunkPos(pos).toLong())) {
            RegionPoiCounters.recordPublishedQuery();
            return region.publishedExists(pos, typeFilter);
        }
        RegionPoiCounters.recordFallbackQuery();
        return RegionThreadingHooks.runOnServerThread(this.level, () -> {
            final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(pos));
            this.ensureColumnLoaded(new ChunkPos(pos), serverRegion);
            return serverRegion.exists(pos, typeFilter);
        });
    }

    /** {@return the type of the POI at {@code pos}} */
    public Optional<Holder<PoiType>> getType(BlockPos pos) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        if (this.readsLive(region, pos)) {
            return region.getType(pos);
        }
        if (region.publishedCovers(new ChunkPos(pos).toLong())) {
            RegionPoiCounters.recordPublishedQuery();
            final PoiRecord record = region.publishedRecordAt(pos);
            return record == null ? Optional.empty() : Optional.of(record.getPoiType());
        }
        RegionPoiCounters.recordFallbackQuery();
        return RegionThreadingHooks.runOnServerThread(this.level, () -> {
            final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(pos));
            this.ensureColumnLoaded(new ChunkPos(pos), serverRegion);
            return serverRegion.getType(pos);
        });
    }

    /** {@return the free tickets of the POI at {@code pos}} */
    public int getFreeTickets(BlockPos pos) {
        final RegionPoiRegion region = this.regionFor(this.ownerOf(pos));
        if (this.readsLive(region, pos)) {
            return region.getFreeTickets(pos);
        }
        if (region.publishedCovers(new ChunkPos(pos).toLong())) {
            RegionPoiCounters.recordPublishedQuery();
            final PoiRecord record = region.publishedRecordAt(pos);
            return record == null ? 0 : record.getFreeTickets();
        }
        RegionPoiCounters.recordFallbackQuery();
        return RegionThreadingHooks.runOnServerThread(this.level, () -> {
            final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(pos));
            this.ensureColumnLoaded(new ChunkPos(pos), serverRegion);
            return serverRegion.getFreeTickets(pos);
        });
    }

    /**
     * {@return true when a read of {@code pos} may read the live section of {@code region}}
     * <p>
     * The server thread reads every region live, which is what makes its answer identical to a single-threaded
     * manager. A worker reads its own region live and every other region from the published view.
     */
    private boolean readsLive(RegionPoiRegion region, BlockPos pos) {
        if (!this.isWorkerQuery()) {
            this.ensureColumnLoaded(new ChunkPos(pos), region);
            return true;
        }
        final RegionCoordinate lease = this.leasedRegion();
        return lease != null && lease.equals(region.coordinate()) && region.knowsColumn(new ChunkPos(pos).toLong());
    }

    // ---------------------------------------------------------------------------------------------------
    // Box queries
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@return the records of one chunk, in vanilla's order}
     * <p>
     * The vanilla {@code getInSquare} and {@code getInRange} call this once per chunk of their box, so the
     * returned stream is the exact sequence the vanilla manager returns for the same data: the chunk order is
     * the caller's, and inside a chunk the section order and the per-section record order are vanilla's.
     */
    public Stream<PoiRecord> getInChunk(Predicate<Holder<PoiType>> typeFilter, ChunkPos chunk, PoiManager.Occupancy occupancy) {
        return this.chunkRecords(chunk, typeFilter, occupancy).stream();
    }

    private List<PoiRecord> chunkRecords(ChunkPos chunk, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy occupancy) {
        final RegionCoordinate owner = this.ownerOf(chunk.x, chunk.z);
        final RegionPoiRegion region = this.regionFor(owner);
        final boolean worker = this.isWorkerQuery();
        final RegionCoordinate lease = worker ? this.leasedRegion() : null;
        if (!worker) {
            this.ensureColumnLoaded(chunk, region);
            return region.liveChunk(chunk, typeFilter, occupancy);
        }
        if (lease != null && lease.equals(owner) && region.knowsColumn(chunk.toLong())) {
            RegionPoiCounters.recordLocalQuery();
            return region.liveChunk(chunk, typeFilter, occupancy);
        }
        if (region.publishedCovers(chunk.toLong())) {
            RegionPoiCounters.recordPublishedQuery();
            return region.publishedChunk(chunk, typeFilter, occupancy);
        }
        RegionPoiCounters.recordFallbackQuery();
        return RegionThreadingHooks.runOnServerThread(this.level, () -> {
            final RegionPoiRegion serverRegion = this.regionFor(this.ownerOf(chunk.x, chunk.z));
            this.ensureColumnLoaded(chunk, serverRegion);
            return serverRegion.liveChunk(chunk, typeFilter, occupancy);
        });
    }

    /**
     * {@return the records of the square around {@code center} that the vanilla {@code getInRange} selects}
     * <p>
     * The chunk iteration, the square filter and the distance filter are the vanilla ones, so the list is in
     * vanilla's order and holds the same records.
     */
    private List<PoiRecord> getInRangeList(Predicate<Holder<PoiType>> typeFilter, BlockPos center, int radius, PoiManager.Occupancy occupancy) {
        final int chunkRadius = Math.floorDiv(radius, 16) + 1;
        final double radiusSqr = (double) radius * (double) radius;
        final List<PoiRecord> records = new ArrayList<>();
        for (final ChunkPos chunk : ChunkPos.rangeClosed(new ChunkPos(center), chunkRadius).toList()) {
            for (final PoiRecord record : this.chunkRecords(chunk, typeFilter, occupancy)) {
                final BlockPos recordPos = record.getPos();
                if (Math.abs(recordPos.getX() - center.getX()) <= radius
                        && Math.abs(recordPos.getZ() - center.getZ()) <= radius
                        && recordPos.distSqr(center) <= radiusSqr) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    // ---------------------------------------------------------------------------------------------------
    // Village distance
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@return the number of sections between {@code sectionPos} and the nearest village center, capped at 7}
     * <p>
     * The vanilla distance tracker computes the minimum graph distance over the 26-neighbourhood of sections to
     * a section that holds an occupied village-tagged POI, which is the Chebyshev distance to the nearest such
     * section; a section farther than 6 answers 7, the tracker's default. The index of village-center sections
     * is the live set of the querying region and the published set of every other region, so the answer is
     * exact for the querying region's own sections and at most one publish old for a foreign section. The
     * result is computed for each query: live occupancy can change before publication, and different
     * querying regions intentionally see different center sets.
     */
    public int sectionsToVillage(SectionPos sectionPos) {
        RegionPoiCounters.recordVillageDistanceQuery();
        return this.computeVillageDistance(sectionPos);
    }

    private int computeVillageDistance(SectionPos sectionPos) {
        final RegionCoordinate lease = this.isWorkerQuery() ? this.leasedRegion() : null;
        final int minRegionX = this.layout.regionX(sectionPos.x() - PoiManager.MAX_VILLAGE_DISTANCE);
        final int maxRegionX = this.layout.regionX(sectionPos.x() + PoiManager.MAX_VILLAGE_DISTANCE);
        final int minRegionZ = this.layout.regionZ(sectionPos.z() - PoiManager.MAX_VILLAGE_DISTANCE);
        final int maxRegionZ = this.layout.regionZ(sectionPos.z() + PoiManager.MAX_VILLAGE_DISTANCE);
        int nearest = MAX_VILLAGE_DISTANCE;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                final RegionCoordinate coordinate = new RegionCoordinate(this.level, regionX, regionZ);
                final RegionPoiRegion region = this.regions.get(coordinate);
                if (region == null) {
                    continue;
                }
                // The server thread and the querying region read live centers; every other region is read
                // from its published snapshot, exactly like the section records.
                final boolean live = lease == null || lease.equals(coordinate);
                final Set<Long> centers = live ? region.liveVillageCenters() : region.published().villageCenters();
                for (final long center : centers) {
                    final int dx = Math.abs(SectionPos.x(center) - sectionPos.x());
                    if (dx >= nearest) {
                        continue;
                    }
                    final int dz = Math.abs(SectionPos.z(center) - sectionPos.z());
                    if (dz >= nearest) {
                        continue;
                    }
                    final int dy = Math.abs(SectionPos.y(center) - sectionPos.y());
                    final int distance = Math.max(dx, Math.max(dy, dz));
                    if (distance < nearest) {
                        nearest = distance;
                    }
                }
            }
        }
        return nearest;
    }

    // ---------------------------------------------------------------------------------------------------
    // Loading and saving
    // ---------------------------------------------------------------------------------------------------

    /**
     * Reconciles one section of a loaded chunk against its block states.
     * <p>
     * The server thread calls this from the chunk load path. The column is loaded first, so the refresh sees
     * the stored records exactly like the vanilla manager's {@code getOrLoad} would.
     */
    public void checkConsistencyWithBlocks(SectionPos sectionPos, LevelChunkSection chunkSection) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            RegionThreadingHooks.runOnServerThread(this.level, () -> {
                this.checkConsistencyWithBlocks(sectionPos, chunkSection);
                return null;
            });
            return;
        }
        final RegionPoiRegion region = this.regionFor(this.ownerOf(sectionPos.x(), sectionPos.z()));
        this.ensureColumnLoaded(sectionPos.chunk(), region);
        region.checkConsistencyWithBlocks(sectionPos, chunkSection);
    }

    /**
     * Loads and validates every POI section of the box around {@code pos}.
     * <p>
     * The vanilla {@code PortalForcer} calls this before a portal search; it loads the columns of the box and
     * asks the level to load the chunks whose sections are not valid yet, which re-reads them and reconciles
     * them through {@link #checkConsistencyWithBlocks}. The chunk loads are server-thread work, so a worker
     * hands the whole call over.
     */
    public void ensureLoadedAndValid(LevelReader reader, BlockPos pos, int radius) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            RegionThreadingHooks.runOnServerThread(this.level, () -> {
                this.ensureLoadedAndValid(reader, pos, radius);
                return null;
            });
            return;
        }
        SectionPos.aroundChunk(new ChunkPos(pos), Math.floorDiv(radius, 16), this.minSection(), this.maxSection())
                .forEach(sectionPos -> {
                    final ChunkPos chunk = sectionPos.chunk();
                    final RegionPoiRegion region = this.regionFor(this.ownerOf(chunk.x, chunk.z));
                    this.ensureColumnLoaded(chunk, region);
                    if (!region.isSectionValid(sectionPos.asLong()) && this.ensuredChunks.add(chunk.toLong())) {
                        reader.getChunk(chunk.x, chunk.z, ChunkStatus.EMPTY);
                    }
                });
    }

    /** Loads the POI column of {@code chunk} into the region that owns it when the region does not know it. */
    private void ensureColumnLoaded(ChunkPos chunk, RegionPoiRegion region) {
        if (region.knowsColumn(chunk.toLong())) {
            return;
        }
        this.loadColumn(chunk, region);
    }

    /**
     * Loads one POI column from disk and distributes its sections to the owning region.
     * <p>
     * This is the read half of the vanilla {@code SectionStorage#readColumn}: the same tag, the same data
     * version upgrade and the same {@code Sections} codec, so a column the regionized storage loads is the
     * column the vanilla manager would have loaded. A column that has no stored data marks all of its sections
     * as known and empty instead of leaving them unknown.
     */
    private void loadColumn(ChunkPos chunk, RegionPoiRegion region) {
        final CompoundTag tag = this.storage.read(chunk).join().orElse(null);
        if (tag == null) {
            region.markColumnKnown(chunk, Map.of(), false);
            RegionPoiCounters.recordLoadedColumn();
            return;
        }
        final RegistryOps<Tag> ops = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        final Dynamic<Tag> dynamic = new Dynamic<>(ops, tag);
        final int version = dynamic.get("DataVersion").asInt(1945);
        final boolean upgraded = version != SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        final Dynamic<Tag> upgradedTag = this.storage.upgradeChunkTag(dynamic, version);
        final OptionalDynamic<Tag> sectionsTag = upgradedTag.get("Sections");
        final Map<Long, PoiSection> loaded = new java.util.HashMap<>();
        for (int sectionY = this.minSection(); sectionY < this.maxSection(); sectionY++) {
            final long sectionKey = SectionPos.asLong(chunk.x, sectionY, chunk.z);
            sectionsTag.get(Integer.toString(sectionY))
                    .result()
                    .flatMap(sectionTag -> PoiSection.codec(() -> region.markSectionDirty(sectionKey))
                            .parse(sectionTag)
                            .resultOrPartial(LOGGER::error))
                    .ifPresent(section -> loaded.put(sectionKey, section));
        }
        region.markColumnKnown(chunk, loaded, upgraded);
        RegionPoiCounters.recordLoadedColumn();
    }

    /** Marks the column of {@code chunkKey} as holding unsaved writes. */
    void markColumnDirty(long chunkKey) {
        this.dirtyColumns.add(chunkKey);
    }

    /** {@return true when a dirty column waits for its save} */
    public boolean hasWork() {
        return !this.dirtyColumns.isEmpty();
    }

    /**
     * Publishes every region of the level and writes the dirty columns while {@code haveTime} allows it.
     * <p>
     * Called from the level tick on the server thread, after the chunk tick phase and before the entity
     * phase, so no worker of the level is running while a column is encoded.
     */
    public void tick(BooleanSupplier haveTime) {
        this.publishLevel();
        while (haveTime.getAsBoolean()) {
            final Long column = this.pollDirtyColumn();
            if (column == null) {
                return;
            }
            this.saveColumn(new ChunkPos(column));
        }
    }

    @Nullable
    private Long pollDirtyColumn() {
        for (final Long column : this.dirtyColumns) {
            if (this.dirtyColumns.remove(column)) {
                return column;
            }
        }
        return null;
    }

    /** Writes the dirty column of {@code chunk} when it has unsaved writes. */
    public void flush(ChunkPos chunk) {
        if (this.dirtyColumns.contains(chunk.toLong())) {
            this.saveColumn(chunk);
        }
    }

    /**
     * Encodes the sections of {@code chunk} into the vanilla tag and writes it.
     * <p>
     * The encoding is the write half of the vanilla {@code SectionStorage#writeColumn}: the same
     * {@code Sections}/{@code DataVersion} compound and the same per-section codec, taken from the sections of
     * the owning region under that region's lock.
     */
    private void saveColumn(ChunkPos chunk) {
        final RegistryOps<Tag> ops = this.registryAccess.createSerializationContext(NbtOps.INSTANCE);
        final CompoundTag sectionsTag = new CompoundTag();
        this.regionFor(this.ownerOf(chunk.x, chunk.z)).encodeColumn(chunk, ops, sectionsTag);
        final CompoundTag tag = new CompoundTag();
        tag.put("Sections", sectionsTag);
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        this.dirtyColumns.remove(chunk.toLong());
        this.storage.write(chunk, tag).exceptionally(throwable -> {
            LOGGER.error("Failed to write the regionized POI column {}", chunk, throwable);
            return null;
        });
        RegionPoiCounters.recordSavedColumn();
    }

    // ---------------------------------------------------------------------------------------------------
    // Publication and lifecycle
    // ---------------------------------------------------------------------------------------------------

    /**
     * Publishes every region that wrote since its last publish.
     * <p>
     * Runs on the server thread at the end of a region phase, when no worker of the level is running, and once
     * per level tick as a safety net for a level that had no region phase. Only dirty sections are captured,
     * so the cost is proportional to what changed.
     */
    public void publishLevel() {
        for (final RegionPoiRegion region : this.regions.values()) {
            region.publish();
        }
    }

    @Override
    public void close() {
        this.publishLevel();
        Long column;
        while ((column = this.pollDirtyColumn()) != null) {
            this.saveColumn(new ChunkPos(column));
        }
        RegionPoiStorages.detach(this);
    }

    // ---------------------------------------------------------------------------------------------------
    // Region mapping
    // ---------------------------------------------------------------------------------------------------

    RegionCoordinate ownerOf(BlockPos pos) {
        return this.layout.regionAt(this.level, pos);
    }

    RegionCoordinate ownerOf(int chunkX, int chunkZ) {
        return new RegionCoordinate(this.level, this.layout.regionX(chunkX), this.layout.regionZ(chunkZ));
    }

    RegionPoiRegion regionFor(RegionCoordinate coordinate) {
        return this.regions.computeIfAbsent(coordinate, key -> new RegionPoiRegion(this, key));
    }
}
