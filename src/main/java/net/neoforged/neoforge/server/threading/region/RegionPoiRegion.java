/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The POI sections one region owns.
 * <p>
 * The live data is the vanilla {@link PoiSection} per section key, so a read of the owning region returns the
 * same records in the same order a single-threaded {@code PoiManager} returns for the same data. Every access
 * to the live sections takes this region's {@link #lock}: the owning region's worker, the server thread (which
 * loads and unloads chunks, places blocks and serves the barrier handoffs) and a foreign worker that acquires
 * or releases a ticket all mutate or read the same mutable structure. The lock is the only shared state a
 * worker and the server thread can contend on, and it is never held across a disk read or a callback into
 * vanilla code that could take another region's lock.
 * <p>
 * At the end of a region phase the server thread calls {@link #publish()}: the sections written since the
 * last publish are captured into immutable {@link RegionPoiSections} views and the whole view map is swapped
 * in one volatile write. A foreign region reads that published map without a lock and without touching the
 * live sections, so a foreign answer is at most one publish old while a local answer is live.
 */
final class RegionPoiRegion {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Result of acquiring one ticket for a record a query returned. */
    enum TicketResult {
        /** The ticket was acquired on the live record. */
        ACQUIRED,
        /** The live record exists but has no free ticket left. */
        NO_SPACE,
        /** The record is no longer part of the live section: the query read a stale view. */
        STALE
    }

    /**
     * The immutable published state of one region.
     *
     * @param sections       the captured view of every section of a loaded column, including empty ones
     * @param columns        the chunk columns the region has loaded, as {@link ChunkPos#toLong()} keys
     * @param villageCenters the sections that hold at least one occupied village-tagged POI
     * @param version        the publish sequence number, for diagnostics
     */
    record Published(Map<Long, RegionPoiSections> sections, Set<Long> columns, Set<Long> villageCenters, long version) {

        static final Published EMPTY = new Published(Map.of(), Set.of(), Set.of(), 0L);
        boolean covers(long chunkKey) {
            return this.columns.contains(chunkKey);
        }

        RegionPoiSections section(long sectionKey) {
            return this.sections.getOrDefault(sectionKey, RegionPoiSections.EMPTY);
        }
    }

    private final RegionPoiStorage storage;
    private final RegionCoordinate coordinate;
    private final ReentrantLock lock = new ReentrantLock();
    /** The live sections of this region, keyed by {@link SectionPos#asLong()}; guarded by {@link #lock}. */
    private final Map<Long, PoiSection> sections = new HashMap<>();
    /** The chunk columns this region loaded; guarded by {@link #lock}. */
    private final Set<Long> knownColumns = new HashSet<>();
    /** The section keys written since the last publish; guarded by {@link #lock}. */
    private final Set<Long> dirtySections = new HashSet<>();
    /** The section keys that hold an occupied village-tagged POI; guarded by {@link #lock}. */
    private final Set<Long> villageCenters = new HashSet<>();
    /** Immutable copy of {@link #villageCenters} for the lock-free village distance read. */
    private volatile Set<Long> liveVillageCenters = Set.of();
    private volatile boolean publishPending;
    private volatile Published published = Published.EMPTY;
    private long publishVersion;

    RegionPoiRegion(RegionPoiStorage storage, RegionCoordinate coordinate) {
        this.storage = storage;
        this.coordinate = coordinate;
    }

    RegionCoordinate coordinate() {
        return this.coordinate;
    }

    Published published() {
        return this.published;
    }

    Set<Long> liveVillageCenters() {
        return this.liveVillageCenters;
    }

    /** {@return true when the published view of this region can answer a query of {@code chunkKey}} */
    boolean publishedCovers(long chunkKey) {
        return this.published.covers(chunkKey);
    }

    /** {@return true when this region loaded the POI column of {@code chunkKey}} */
    boolean knowsColumn(long chunkKey) {
        this.lock.lock();
        try {
            return this.knownColumns.contains(chunkKey);
        } finally {
            this.lock.unlock();
        }
    }

    /** {@return true when the section {@code sectionKey} is loaded and validated} */
    boolean isSectionValid(long sectionKey) {
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            return section != null && section.isValid();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Records that {@code chunk} was loaded, together with the sections the load produced.
     * <p>
     * Called on the server thread only. Every section of the column becomes known - a column with no POI data
     * is known and empty, exactly like the vanilla section storage keeps an empty optional for it - so a query
     * over the column never has to fall back for it.
     *
     * @param loaded   the sections the load produced, keyed by section position
     * @param upgraded whether the stored data was upgraded to the current data version, which marks the column
     *                 dirty so the upgrade is written back
     */
    void markColumnKnown(ChunkPos chunk, Map<Long, PoiSection> loaded, boolean upgraded) {
        this.lock.lock();
        try {
            this.knownColumns.add(chunk.toLong());
            for (int sectionY = this.storage.minSection(); sectionY < this.storage.maxSection(); sectionY++) {
                final long sectionKey = SectionPos.asLong(chunk.x, sectionY, chunk.z);
                final PoiSection section = loaded.get(sectionKey);
                if (section != null) {
                    this.sections.put(sectionKey, section);
                }
                this.dirtySections.add(sectionKey);
                this.updateVillageCenter(sectionKey);
            }
            this.publishPending = true;
            if (upgraded) {
                this.storage.markColumnDirty(chunk.toLong());
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Adds one POI to the section that holds {@code pos}.
     * <p>
     * The caller guarantees that the column of {@code pos} is loaded. The write lands in the live section, so
     * the owning region sees it in the same tick and every other region sees it after the next publish.
     */
    void add(BlockPos pos, Holder<PoiType> type) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.computeIfAbsent(sectionKey, key -> new PoiSection(() -> this.markSectionDirty(key)));
            section.add(pos, type);
            // A section that was created empty still has to be published, so a foreign query knows it exists.
            this.markSectionDirty(sectionKey);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Removes the POI at {@code pos} when the section that holds it is loaded.
     * <p>
     * The vanilla removal ignores a position of a section that is not loaded, because the load of that
     * section is what would have created the record; the caller therefore loads the column first.
     */
    void remove(BlockPos pos) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            if (section != null) {
                section.remove(pos);
                this.markSectionDirty(sectionKey);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Releases one ticket of the POI at {@code pos}.
     *
     * @throws IllegalStateException when the section that holds {@code pos} is not loaded, exactly like the
     *                               vanilla manager throws for a POI that was never registered
     */
    boolean release(BlockPos pos) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            if (section == null) {
                throw new IllegalStateException("POI never registered at " + pos);
            }
            final boolean released = section.release(pos);
            this.markSectionDirty(sectionKey);
            return released;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Acquires one ticket of {@code record}.
     * <p>
     * The record has to be the live record of this region: a query that read a published view may hand in a
     * record that was removed or replaced since the publish, and acquiring a ticket on it would book a ticket
     * on a POI that no longer exists. Such an acquisition is refused as {@link TicketResult#STALE}.
     */
    TicketResult acquireTicket(PoiRecord record) {
        final long sectionKey = SectionPos.asLong(record.getPos());
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            final Optional<PoiRecord> live = section == null ? Optional.empty() : section.getRecord(record.getPos());
            if (live.isEmpty() || live.get() != record) {
                return TicketResult.STALE;
            }
            if (!record.hasSpace()) {
                return TicketResult.NO_SPACE;
            }
            record.acquireTicket();
            this.markSectionDirty(sectionKey);
            return TicketResult.ACQUIRED;
        } finally {
            this.lock.unlock();
        }
    }

    Optional<Holder<PoiType>> getType(BlockPos pos) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            return section == null ? Optional.empty() : section.getType(pos);
        } finally {
            this.lock.unlock();
        }
    }

    boolean exists(BlockPos pos, Predicate<Holder<PoiType>> typeFilter) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            return section != null && section.exists(pos, typeFilter);
        } finally {
            this.lock.unlock();
        }
    }

    int getFreeTickets(BlockPos pos) {
        final long sectionKey = SectionPos.asLong(pos);
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            return section == null ? 0 : section.getFreeTickets(pos);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * {@return the records of {@code chunk} in vanilla's order, read from the live sections}
     * <p>
     * The sections are visited in ascending section-y order and each section's records are materialized under
     * the lock, so the returned list is the exact sequence the vanilla {@code getInChunk} produces for the
     * same data.
     */
    List<PoiRecord> liveChunk(ChunkPos chunk, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy occupancy) {
        this.lock.lock();
        try {
            List<PoiRecord> records = List.of();
            for (int sectionY = this.storage.minSection(); sectionY < this.storage.maxSection(); sectionY++) {
                final PoiSection section = this.sections.get(SectionPos.asLong(chunk.x, sectionY, chunk.z));
                if (section == null) {
                    continue;
                }
                final List<PoiRecord> sectionRecords = section.getRecords(typeFilter, occupancy).toList();
                if (sectionRecords.isEmpty()) {
                    continue;
                }
                if (records.isEmpty()) {
                    records = new ArrayList<>(sectionRecords);
                } else {
                    records.addAll(sectionRecords);
                }
            }
            return records;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * {@return the records of {@code chunk} in vanilla's order, read from the published views}
     */
    List<PoiRecord> publishedChunk(ChunkPos chunk, Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy occupancy) {
        final Published snapshot = this.published;
        List<PoiRecord> records = List.of();
        for (int sectionY = this.storage.minSection(); sectionY < this.storage.maxSection(); sectionY++) {
            final List<PoiRecord> sectionRecords = snapshot.section(SectionPos.asLong(chunk.x, sectionY, chunk.z))
                    .records(typeFilter, occupancy);
            if (sectionRecords.isEmpty()) {
                continue;
            }
            if (records.isEmpty()) {
                records = new ArrayList<>(sectionRecords);
            } else {
                records.addAll(sectionRecords);
            }
        }
        return records;
    }

    /**
     * {@return the published record at {@code pos}, or {@code null} when the published view holds none}
     */
    @Nullable
    PoiRecord publishedRecordAt(BlockPos pos) {
        return this.published.section(SectionPos.asLong(pos)).recordAt(pos);
    }

    /**
     * {@return true when the published view of {@code sectionKey} holds a record at {@code pos}}
     */
    boolean publishedExists(BlockPos pos, Predicate<Holder<PoiType>> typeFilter) {
        final PoiRecord record = this.publishedRecordAt(pos);
        return record != null && typeFilter.test(record.getPoiType());
    }

    /**
     * Reconciles one section against the block states of a loaded chunk section.
     * <p>
     * This is the vanilla {@code checkConsistencyWithBlocks} body: an invalid section is refreshed from the
     * block states, and a section that the load produced as absent is created when the chunk section may hold
     * a POI. The caller guarantees that the column of {@code sectionPos} is loaded.
     */
    void checkConsistencyWithBlocks(SectionPos sectionPos, LevelChunkSection chunkSection) {
        final long sectionKey = sectionPos.asLong();
        this.lock.lock();
        try {
            final PoiSection section = this.sections.get(sectionKey);
            if (section != null) {
                section.refresh(adder -> {
                    if (mayHavePoi(chunkSection)) {
                        updateFromSection(chunkSection, sectionPos, adder);
                    }
                });
            } else if (mayHavePoi(chunkSection)) {
                final PoiSection created = new PoiSection(() -> this.markSectionDirty(sectionKey));
                updateFromSection(chunkSection, sectionPos, created::add);
                this.sections.put(sectionKey, created);
            }
            this.dirtySections.add(sectionKey);
            this.publishPending = true;
            this.updateVillageCenter(sectionKey);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Encodes the sections of {@code chunk} into the vanilla {@code Sections} compound.
     * <p>
     * Called on the server thread while the column is saved. Every section the region holds is encoded, an
     * empty one included, because the vanilla section storage encodes an empty but present section the same
     * way; a section the region does not hold is left out, which is exactly what the vanilla storage does for
     * an empty optional.
     */
    void encodeColumn(ChunkPos chunk, DynamicOps<Tag> ops, net.minecraft.nbt.CompoundTag sectionsTag) {
        this.lock.lock();
        try {
            for (int sectionY = this.storage.minSection(); sectionY < this.storage.maxSection(); sectionY++) {
                final PoiSection section = this.sections.get(SectionPos.asLong(chunk.x, sectionY, chunk.z));
                if (section == null) {
                    continue;
                }
                final String sectionKey = Integer.toString(sectionY);
                PoiSection.codec(() -> {}).encodeStart(ops, section).resultOrPartial(LOGGER::error)
                        .ifPresent(encoded -> sectionsTag.put(sectionKey, encoded));
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Publishes the sections written since the last publish.
     * <p>
     * Runs on the server thread at the end of a region phase, when no worker of the level is running. Only the
     * dirty sections are captured, so the cost per publish is proportional to what changed, not to the size of
     * the region; the view map is copied once and swapped in a single volatile write, so a reader sees either
     * the previous publish or this one and never a half-updated map.
     */
    void publish() {
        if (!this.publishPending) {
            return;
        }
        this.lock.lock();
        try {
            if (!this.publishPending) {
                return;
            }
            final Set<Long> previousCenters = this.published.villageCenters();
            final Map<Long, RegionPoiSections> views = new HashMap<>(this.published.sections());
            for (final long sectionKey : this.dirtySections) {
                final PoiSection section = this.sections.get(sectionKey);
                views.put(sectionKey, section == null ? RegionPoiSections.EMPTY : RegionPoiSections.capture(section));
            }
            this.dirtySections.clear();
            this.publishPending = false;
            final Set<Long> centers = Set.copyOf(this.villageCenters);
            this.published = new Published(views, Set.copyOf(this.knownColumns), centers, ++this.publishVersion);
            RegionPoiCounters.recordPublish();
            if (!centers.equals(previousCenters)) {
                // The village distance cache is invalidated after the new centers are installed, so a query
                // that races this publish either recomputes or is not cached against the old centers.
                this.storage.centerVersionChanged();
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Marks {@code sectionKey} as written: it is captured by the next publish, its column is scheduled for
     * saving and its village-center status is recomputed.
     * <p>
     * Called from inside the lock by the dirty callback of every live section, so a ticket change marks the
     * section too, exactly like the vanilla {@code setDirty} does.
     */
    void markSectionDirty(long sectionKey) {
        this.dirtySections.add(sectionKey);
        this.publishPending = true;
        this.storage.markColumnDirty(new ChunkPos(SectionPos.x(sectionKey), SectionPos.z(sectionKey)).toLong());
        this.updateVillageCenter(sectionKey);
    }

    private void updateVillageCenter(long sectionKey) {
        final PoiSection section = this.sections.get(sectionKey);
        final boolean center = section != null
                && section.getRecords(type -> type.is(PoiTypeTags.VILLAGE), PoiManager.Occupancy.IS_OCCUPIED).findAny().isPresent();
        final boolean changed = center ? this.villageCenters.add(sectionKey) : this.villageCenters.remove(sectionKey);
        if (changed) {
            this.liveVillageCenters = Set.copyOf(this.villageCenters);
        }
    }

    private static boolean mayHavePoi(LevelChunkSection chunkSection) {
        return chunkSection.maybeHas(PoiTypes::hasPoi);
    }

    private static void updateFromSection(LevelChunkSection chunkSection, SectionPos sectionPos, java.util.function.BiConsumer<BlockPos, Holder<PoiType>> adder) {
        sectionPos.blocksInside().forEach(pos -> {
            final BlockState state = chunkSection.getBlockState(
                    SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()),
                    SectionPos.sectionRelative(pos.getZ()));
            PoiTypes.forState(state).ifPresent(type -> adder.accept(pos, type));
        });
    }
}
