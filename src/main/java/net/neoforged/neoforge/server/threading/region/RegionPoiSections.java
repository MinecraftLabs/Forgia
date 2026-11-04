/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable published view of one POI section.
 * <p>
 * A view is captured from a live {@link PoiSection} under the write lock of the region that owns it, and it
 * keeps the section's records in the order the live section iterates them: {@link PoiSection#getRecords}
 * walks its per-type map and flattens each type's record set, so capturing that flattened sequence and
 * filtering it again by type and occupancy reproduces the exact sequence a single-threaded manager returns for
 * the same section. A query over a published view therefore answers in vanilla's order, not in an order the
 * regionized storage invents.
 * <p>
 * The records themselves are the live {@link PoiRecord} objects, not copies. Their position and type are
 * final, and their free-ticket count is the one mutable field the regionized storage shares on purpose: a
 * ticket change is a bounded, atomic acquire or release, and sharing the counter is what makes a cross-region
 * {@code take} see the same free tickets as the owning region instead of a value one tick old. The structure
 * of the section - which records it holds and in which order - is immutable in a published view.
 */
final class RegionPoiSections {
    /** The view of a section that is loaded and holds no record. */
    static final RegionPoiSections EMPTY = new RegionPoiSections(List.of(), new Short2ObjectOpenHashMap<>());

    private final List<PoiRecord> records;
    private final Short2ObjectMap<PoiRecord> byPosition;

    private RegionPoiSections(List<PoiRecord> records, Short2ObjectMap<PoiRecord> byPosition) {
        this.records = records;
        this.byPosition = byPosition;
    }

    /**
     * {@return the immutable view of {@code section}}
     * <p>
     * The caller must hold the write lock of the region that owns the section: the capture iterates the live
     * section, which is the mutable structure the lock protects.
     */
    static RegionPoiSections capture(PoiSection section) {
        final List<PoiRecord> captured = section.getRecords(type -> true, PoiManager.Occupancy.ANY).toList();
        if (captured.isEmpty()) {
            return EMPTY;
        }
        final Short2ObjectMap<PoiRecord> byPosition = new Short2ObjectOpenHashMap<>(captured.size());
        for (final PoiRecord record : captured) {
            byPosition.put(SectionPos.sectionRelativePos(record.getPos()), record);
        }
        return new RegionPoiSections(captured, byPosition);
    }

    /**
     * {@return the records of this view that match {@code typeFilter} and {@code occupancy}, in vanilla's
     * order}
     */
    List<PoiRecord> records(Predicate<Holder<PoiType>> typeFilter, PoiManager.Occupancy occupancy) {
        if (this.records.isEmpty()) {
            return List.of();
        }
        final List<PoiRecord> matching = new ArrayList<>();
        for (final PoiRecord record : this.records) {
            if (typeFilter.test(record.getPoiType()) && occupancy.getTest().test(record)) {
                matching.add(record);
            }
        }
        return matching;
    }

    /** {@return the record of this view at {@code pos}, or {@code null} when the view holds none} */
    @Nullable
    PoiRecord recordAt(BlockPos pos) {
        return this.byPosition.get(SectionPos.sectionRelativePos(pos));
    }
}
