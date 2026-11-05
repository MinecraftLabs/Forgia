/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Region-scoped entity range query of a native region worker that owns an entity tick lease.
 * <p>
 * The out-of-region contract is <b>clamp and count</b>: the worker sees only the entities stored in its
 * leased region. A query box that extends past the region edge is clamped to the region's block bounds
 * before the section scan, the {@link RegionThreadingDiagnostics#entityQueriesTruncatedByLease() truncated
 * counter} increments, and the caller receives the subset that lies inside the lease instead of an
 * exception. A query that lies entirely outside the lease returns no entity and is still counted as
 * truncated. Truncation is never silent.
 * <p>
 * Only the physical per-region section bucket of the lease is read. The query never consults the shared
 * vanilla section map, another region's bucket, the by-id/by-uuid lookup or the level's multipart list, so
 * a worker cannot observe an entity that belongs to another region. The result contains live entity
 * references, exactly like a vanilla query; the ownership lease still decides whether the worker may mutate
 * one of them, and removal stays rejected.
 * <p>
 * The query is only used when all of the following hold: the current thread is a native region worker, the
 * lease of that thread is active and belongs to {@code level}, and the level has physical section
 * partitions. Every other caller falls back to today's legacy query guard, which keeps the exact rejection
 * for the server thread, DYNAMIC storage and a worker without a lease.
 * <p>
 * The physical partitions of a level are created from the layout of the engine installed when the level is
 * created, so the lease coordinate and the bucket grid use the same fixed region size. The level exposes
 * that size, not the partitions themselves; the section accessor validates the requested region against the
 * active lease before it reads one bucket.
 */
@ApiStatus.Internal
public final class RegionScopedEntityQuery {
    private RegionScopedEntityQuery() {}

    /**
     * Runs {@code box}/{@code predicate} against the leased region of the current worker.
     *
     * @return the region-scoped result, or {@code null} when the current thread may not run a region-scoped
     *         query and the caller has to keep the legacy guard
     */
    @Nullable
    public static List<Entity> tryQuery(ServerLevel level, @Nullable Entity excluded, AABB box, Predicate<? super Entity> predicate) {
        final LeasedBox leased = leasedQueryBox(level, box);
        if (leased == null) {
            return null;
        }
        final List<Entity> result = new ArrayList<>();
        // The region-scoped query is what a block-entity tick pays instead of the level-wide section index of
        // the server thread; the attribution times it against the ticker body that issued it.
        final boolean timing = RegionBlockEntityTickScope.isAttributingTickerBody();
        final long start = timing ? System.nanoTime() : 0L;
        if (!isEmpty(leased.box())) {
            level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(), section -> {
                section.getEntities(leased.box(), entity -> {
                    if (entity != excluded && predicate.test(entity)) {
                        result.add(entity);
                    }
                    return AbortableIterationConsumer.Continuation.CONTINUE;
                });
                return AbortableIterationConsumer.Continuation.CONTINUE;
            });
        }
        if (timing) {
            RegionBlockEntityTickAttribution.recordTickerQuery(System.nanoTime() - start);
        }
        return result;
    }

    /**
     * Runs the typed {@code box}/{@code predicate} query against the leased region of the current worker and
     * appends to {@code result}.
     *
     * @return {@code true} when the query ran region-scoped, {@code false} when the caller has to keep the
     *         legacy guard
     */
    public static <T extends Entity> boolean tryQuery(
            ServerLevel level, EntityTypeTest<Entity, T> typeTest, AABB box, Predicate<? super T> predicate, List<? super T> result, int max) {
        final LeasedBox leased = leasedQueryBox(level, box);
        if (leased == null) {
            return false;
        }
        if (isEmpty(leased.box())) {
            return true;
        }
        final boolean timing = RegionBlockEntityTickScope.isAttributingTickerBody();
        final long start = timing ? System.nanoTime() : 0L;
        level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(), section -> section.getEntities(typeTest, leased.box(), entity -> {
            if (predicate.test(entity)) {
                result.add(entity);
                if (result.size() >= max) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }));
        if (timing) {
            RegionBlockEntityTickAttribution.recordTickerQuery(System.nanoTime() - start);
        }
        return true;
    }

    /**
     * Validates the lease and storage policy and applies the clamp-and-count contract.
     *
     * @return the lease and the box clamped to its region, or {@code null} when the query must keep the
     *         legacy guard
     */
    @Nullable
    private static LeasedBox leasedQueryBox(ServerLevel level, AABB box) {
        final RegionCoordinate coordinate = RegionEntityOwnershipScope.queryCoordinate(level);
        if (coordinate == null) {
            return null;
        }
        final int regionChunkSize = level.entitySectionRegionChunkSize();
        if (regionChunkSize <= 0) {
            // The lease exists but the level keeps the shared section storage (DYNAMIC). The worker query
            // stays rejected exactly like before instead of falling back to a whole-storage scan.
            return null;
        }
        final double minX = coordinate.regionX() * (double) regionChunkSize * 16.0;
        final double minZ = coordinate.regionZ() * (double) regionChunkSize * 16.0;
        final double maxX = minX + regionChunkSize * 16.0;
        final double maxZ = minZ + regionChunkSize * 16.0;
        final AABB clamped = new AABB(
                Math.max(box.minX, minX), box.minY, Math.max(box.minZ, minZ),
                Math.min(box.maxX, maxX), box.maxY, Math.min(box.maxZ, maxZ));
        RegionEntityOwnershipScope.recordAllowedQuery();
        if (!clamped.equals(box)) {
            RegionEntityOwnershipScope.recordTruncatedQuery();
        }
        return new LeasedBox(coordinate, clamped);
    }

    private static boolean isEmpty(AABB box) {
        return box.minX >= box.maxX || box.minZ >= box.maxZ;
    }

    private record LeasedBox(RegionCoordinate coordinate, AABB box) {}
}
