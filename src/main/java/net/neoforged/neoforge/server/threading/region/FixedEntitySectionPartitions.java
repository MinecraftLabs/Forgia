/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.Objects;
import java.util.function.LongFunction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Authoritative spatial storage for one fixed {@link RegionLayout}. Each bucket owns its section map and ID
 * tree; there is no global section index. Buckets are indexed by region X and then region Z, so a chunk
 * range resolves one row instead of scanning every partition. All access remains on the server thread. This
 * does not confer region ownership of entities, UUIDs, tracking, or the lazy caches inside entity
 * sections.
 * <p>
 * The layout is the one the installed engine ticks with, so a manager never partitions sections into
 * different regions than the engine that ticks them.
 */
@ApiStatus.Internal
public final class FixedEntitySectionPartitions<T extends EntityAccess> {
    /** Region X to region Z to the bucket that owns that fixed region. */
    private final Long2ObjectMap<Long2ObjectMap<Bucket<T>>> partitionRows = new Long2ObjectOpenHashMap<>();
    private final RegionLayout layout;

    /**
     * @param layout the fixed layout whose region edge length indexes the buckets
     * @throws IllegalArgumentException when {@code layout} is not a fixed layout
     */
    public FixedEntitySectionPartitions(RegionLayout layout) {
        if (!layout.isFixed()) {
            throw new IllegalArgumentException("FixedEntitySectionPartitions requires a fixed region layout: " + layout.policy());
        }
        this.layout = layout;
    }

    private int regionX(long sectionId) {
        return this.layout.regionX(SectionPos.x(sectionId));
    }

    private int regionZ(long sectionId) {
        return this.layout.regionZ(SectionPos.z(sectionId));
    }

    private static void checkAccess() {
        RegionThreadingHooks.assertServerEntityStorageAccess("FixedEntitySectionPartitions");
    }

    @Nullable
    private Bucket<T> bucket(int regionX, int regionZ) {
        Long2ObjectMap<Bucket<T>> row = this.partitionRows.get(regionX);
        return row == null ? null : row.get(regionZ);
    }

    @Nullable
    public EntitySection<T> get(long sectionId) {
        checkAccess();
        Bucket<T> bucket = this.bucket(regionX(sectionId), regionZ(sectionId));
        return bucket == null ? null : bucket.sections.get(sectionId);
    }

    public EntitySection<T> getOrCreate(long sectionId, LongFunction<EntitySection<T>> factory) {
        checkAccess();
        EntitySection<T> existing = this.get(sectionId);
        if (existing != null) {
            return existing;
        }
        // Invoke the visibility provider before publishing a bucket, so a failed creation leaves no
        // empty partition behind. No lock is held while user-provided code runs.
        EntitySection<T> section = Objects.requireNonNull(factory.apply(sectionId), "section factory returned null");
        int regionX = regionX(sectionId);
        int regionZ = regionZ(sectionId);
        Long2ObjectMap<Bucket<T>> row = this.partitionRows.computeIfAbsent(regionX, key -> new Long2ObjectOpenHashMap<>());
        Bucket<T> bucket = row.computeIfAbsent(regionZ, key -> new Bucket<>());
        // A user-provided factory may have published this section before returning. Keep the first
        // published section so one id never owns two sections or two ID entries.
        EntitySection<T> published = bucket.sections.putIfAbsent(sectionId, section);
        if (published != null) {
            return published;
        }
        bucket.sectionIds.add(sectionId);
        return section;
    }

    public void remove(long sectionId) {
        checkAccess();
        int regionX = regionX(sectionId);
        int regionZ = regionZ(sectionId);
        Long2ObjectMap<Bucket<T>> row = this.partitionRows.get(regionX);
        if (row == null) {
            return;
        }
        Bucket<T> bucket = row.get(regionZ);
        if (bucket == null) {
            return;
        }
        bucket.sections.remove(sectionId);
        bucket.sectionIds.remove(sectionId);
        if (bucket.sections.isEmpty()) {
            row.remove(regionZ);
            if (row.isEmpty()) {
                this.partitionRows.remove(regionX);
            }
        }
    }

    /**
     * Materializes only IDs for one vanilla packed-ID range, in signed long order. The temporary
     * candidates neither own sections nor retain buckets, including when a caller keeps a chunk
     * stream past bucket retirement. Entity lookup still happens when the stream is consumed.
     * <p>
     * The lookup walks only the region-X rows the packed range covers, then filters each bucket's own
     * tree by the exact range, so work is proportional to the matching rows instead of every partition
     * in the level.
     */
    public LongSortedSet sectionIdsInRange(long from, long to) {
        checkAccess();
        LongSortedSet result = new LongAVLTreeSet();
        // Keep the same invalid-range behavior as the legacy tree, including packed-long overflow.
        if (from > to) {
            throw new IllegalArgumentException("Start element is larger than end element");
        }
        if (from == to) {
            return result;
        }
        int firstRegionX = regionX(from);
        int lastRegionX = regionX(to - 1);
        int firstRegionZ = regionZ(from);
        int lastRegionZ = regionZ(to - 1);
        if (firstRegionX == lastRegionX && firstRegionZ == lastRegionZ) {
            // A chunk range belongs to exactly one bucket, including all vertical sections. Load,
            // save and status changes must not scan every partition for each individual chunk.
            Bucket<T> bucket = this.bucket(firstRegionX, firstRegionZ);
            if (bucket != null) {
                result.addAll(bucket.sectionIds.subSet(from, to));
            }
            return result;
        }
        for (int regionX = firstRegionX; regionX <= lastRegionX; regionX++) {
            Long2ObjectMap<Bucket<T>> row = this.partitionRows.get(regionX);
            if (row == null) {
                continue;
            }
            for (Bucket<T> bucket : row.values()) {
                result.addAll(bucket.sectionIds.subSet(from, to));
            }
        }
        return result;
    }

    public LongSet chunks() {
        checkAccess();
        LongSet chunks = new LongOpenHashSet();
        for (Long2ObjectMap<Bucket<T>> row : this.partitionRows.values()) {
            for (Bucket<T> bucket : row.values()) {
                for (long sectionId : bucket.sectionIds) {
                    chunks.add(ChunkPos.asLong(SectionPos.x(sectionId), SectionPos.z(sectionId)));
                }
            }
        }
        return chunks;
    }

    public int count() {
        checkAccess();
        int count = 0;
        for (Long2ObjectMap<Bucket<T>> row : this.partitionRows.values()) {
            for (Bucket<T> bucket : row.values()) {
                count += bucket.sectionIds.size();
            }
        }
        return count;
    }

    private static final class Bucket<T extends EntityAccess> {
        private final Long2ObjectMap<EntitySection<T>> sections = new Long2ObjectOpenHashMap<>();
        private final LongSortedSet sectionIds = new LongAVLTreeSet();
    }
}
