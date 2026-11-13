/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Region-scoped entity queries of a native region worker that owns an entity tick lease.
 * <p>
 * Every query shape the level's entity view offers is answered here from data the worker may read: the
 * physical section bucket of its leased region, the region's own entity index, and the per-tick player
 * snapshot the server thread publishes ({@link RegionPlayerSnapshots}).
 * <p>
 * The out-of-region contract of a box query is <b>clamp and count</b> for the region part: the worker sees
 * only the entities stored in its leased region. A query box that extends past the region edge is clamped to
 * the region's block bounds before the section scan, the
 * {@link RegionThreadingDiagnostics#entityQueriesTruncatedByLease() truncated counter} increments, and the
 * caller receives the subset that lies inside the lease instead of an exception. A query that lies entirely
 * outside the lease returns no entity and is still counted as truncated. Truncation is never silent.
 * <p>
 * The {@code getAll()} and type-filtered shapes have no box, so their clamp is the region index: they return
 * exactly the entities whose region mapping names the lease, and they are counted as truncated when the
 * level's own index holds more entities than the region's.
 * <p>
 * Players are the one population that is not region-owned: they are always-ticking entities that only the
 * server thread ticks. A player-typed query - {@code getEntitiesOfClass(Player.class, box)} and every query of
 * a subtype of {@link Player} - therefore also answers from the level's published player snapshot: the players
 * whose recorded position lies inside the caller's box, which are exactly the players the region bucket cannot
 * see. That part of the answer is counted as {@link RegionEntityQueryCounters.Path#PUBLISHED} and is at most
 * one tick old; see {@link RegionPlayerSnapshots}. A query of another type stays clamped to the lease, so
 * collision, explosion and targeting queries of ordinary entities never reach a player of another region.
 * <p>
 * A player the physical section bucket already answered is recorded before it reaches the caller, so the
 * snapshot union never hands the same player over twice: every query answers each player exactly once.
 * <p>
 * The box queries that {@code Level.getEntities(...)} runs append the parts of the multipart owners the lease
 * answered, exactly where vanilla appends the parts of the level after its section scan; a part belongs to the
 * region of its owner. The getter shapes - {@code get(AABB, Consumer)} and
 * {@code get(EntityTypeTest, AABB, AbortableIterationConsumer)} - stay entity-only, exactly like vanilla.
 * <p>
 * Only the physical per-region section bucket, the region's own index and the published snapshot are read.
 * The query never consults the shared vanilla section map, another region's bucket or the level's live player
 * list, so a worker cannot observe storage another thread is mutating. The result contains live entity
 * references, exactly like a vanilla query; the ownership lease still decides whether the worker may mutate
 * one of them, and removal stays rejected.
 * <p>
 * The query is only used when all of the following hold: the current thread is a native region worker, the
 * lease of that thread is active and belongs to {@code level}, and the level has physical section partitions.
 * The index-based shapes additionally need the level's region entity index to be published, because an empty
 * index is not an answer. Every other caller falls back to the legacy query guard, which keeps the exact
 * rejection for the server thread, DYNAMIC storage, a worker without a lease and a worker of a level whose
 * index is not published.
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
        final LeasedBox leased = leasedQueryBox(level, box, RegionEntityQueryCounters.Operation.GET_IN_BOX);
        if (leased == null) {
            return null;
        }
        final List<Entity> result = new ArrayList<>();
        final List<Entity> multipartOwners = new ArrayList<>(0);
        // The region-scoped query is what a block-entity tick pays instead of the level-wide section index of
        // the server thread; the attribution times it against the ticker body that issued it.
        final boolean timing = RegionBlockEntityTickScope.isAttributingTickerBody();
        final long start = timing ? System.nanoTime() : 0L;
        if (!isEmpty(leased.box())) {
            level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(), section -> {
                section.getEntities(leased.box(), entity -> {
                    if (entity.isMultipartEntity()) {
                        multipartOwners.add(entity);
                    }
                    if (entity != excluded && predicate.test(entity)) {
                        result.add(entity);
                    }
                    return AbortableIterationConsumer.Continuation.CONTINUE;
                });
                return AbortableIterationConsumer.Continuation.CONTINUE;
            });
        }
        appendAnsweredParts(multipartOwners, EntityTypeTest.forClass(Entity.class), excluded, box, predicate, result, Integer.MAX_VALUE);
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
        final LeasedBox leased = leasedQueryBox(level, box, RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX);
        if (leased == null) {
            return false;
        }
        if (!isEmpty(leased.box())) {
            final List<Entity> multipartOwners = new ArrayList<>(0);
            final boolean timing = RegionBlockEntityTickScope.isAttributingTickerBody();
            final long start = timing ? System.nanoTime() : 0L;
            level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(),
                    section -> section.getEntities(typeTest, leased.box(), entity -> {
                        if (entity.isMultipartEntity()) {
                            multipartOwners.add(entity);
                        }
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
            appendAnsweredParts(multipartOwners, typeTest, null, box, predicate, result, max);
        }
        if (result.size() < max) {
            final PlayerUnion players = PlayerUnion.prepare(level, box, typeTest.getBaseClass(), RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX);
            players.noteLocal(result);
            for (final ServerPlayer player : players.remote()) {
                final T cast = typeTest.tryCast(player);
                if (cast != null && predicate.test(cast)) {
                    result.add(cast);
                    if (result.size() >= max) {
                        break;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Runs {@code box}/{@code consumer} against the leased region of the current worker.
     *
     * @return {@code true} when the query ran region-scoped, {@code false} when the caller has to keep the
     *         legacy guard
     */
    public static boolean tryGetInBox(ServerLevel level, AABB box, Consumer<Entity> consumer) {
        final LeasedBox leased = leasedQueryBox(level, box, RegionEntityQueryCounters.Operation.GET_IN_BOX);
        if (leased == null) {
            return false;
        }
        if (!isEmpty(leased.box())) {
            level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(),
                    section -> section.getEntities(leased.box(), entity -> {
                        consumer.accept(entity);
                        return AbortableIterationConsumer.Continuation.CONTINUE;
                    }));
        }
        return true;
    }

    /**
     * Runs the typed {@code box}/{@code consumer} query against the leased region of the current worker.
     *
     * @return {@code true} when the query ran region-scoped, {@code false} when the caller has to keep the
     *         legacy guard
     */
    public static <T extends Entity> boolean tryGetByTypeInBox(ServerLevel level, EntityTypeTest<Entity, T> typeTest, AABB box,
            AbortableIterationConsumer<T> consumer) {
        final LeasedBox leased = leasedQueryBox(level, box, RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX);
        if (leased == null) {
            return false;
        }
        boolean aborted = false;
        // The scan already answers a player that stands inside the lease. Recording it here is what keeps the
        // union below from handing the same player to the consumer twice; the typed list overload does the same
        // with its finished result.
        final List<Player> answeredPlayers = new ArrayList<>(0);
        if (!isEmpty(leased.box())) {
            final boolean[] stopped = { false };
            level.forEachAccessibleNonEmptySectionInRegion(leased.coordinate().regionX(), leased.coordinate().regionZ(), leased.box(),
                    section -> {
                        final AbortableIterationConsumer.Continuation continuation = section.getEntities(typeTest, leased.box(), entity -> {
                            if (entity instanceof Player player) {
                                answeredPlayers.add(player);
                            }
                            return consumer.accept(entity);
                        });
                        if (continuation == AbortableIterationConsumer.Continuation.ABORT) {
                            stopped[0] = true;
                        }
                        return continuation;
                    });
            aborted = stopped[0];
        }
        if (!aborted) {
            final PlayerUnion players = PlayerUnion.prepare(level, box, typeTest.getBaseClass(), RegionEntityQueryCounters.Operation.GET_BY_TYPE_IN_BOX);
            players.noteLocal(answeredPlayers);
            for (final ServerPlayer player : players.remote()) {
                final T cast = typeTest.tryCast(player);
                if (cast != null && consumer.accept(cast) == AbortableIterationConsumer.Continuation.ABORT) {
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Runs {@code getAll()} against the entity index of the leased region of the current worker.
     *
     * @return the region-scoped result, or {@code null} when the current thread may not answer it from its
     *         region and the caller has to keep the legacy guard
     */
    @Nullable
    public static List<Entity> tryGetAll(ServerLevel level) {
        final RegionState region = leasedEntityIndex(level, RegionEntityQueryCounters.Operation.GET_ALL);
        if (region == null) {
            return null;
        }
        return region.indexedEntities();
    }

    /**
     * Runs the type-filtered {@code get(EntityTypeTest, consumer)} against the entity index of the leased
     * region of the current worker.
     *
     * @return {@code true} when the query ran region-scoped, {@code false} when the caller has to keep the
     *         legacy guard
     */
    public static <T extends Entity> boolean tryGetByType(ServerLevel level, EntityTypeTest<Entity, T> typeTest, AbortableIterationConsumer<T> consumer) {
        final RegionState region = leasedEntityIndex(level, RegionEntityQueryCounters.Operation.GET_BY_TYPE);
        if (region == null) {
            return false;
        }
        for (final Entity entity : region.indexedEntities()) {
            final T cast = typeTest.tryCast(entity);
            if (cast != null && consumer.accept(cast) == AbortableIterationConsumer.Continuation.ABORT) {
                break;
            }
        }
        return true;
    }

    /**
     * Validates the lease and storage policy and applies the clamp-and-count contract of a box query.
     *
     * @return the lease and the box clamped to its region, or {@code null} when the query must keep the
     *         legacy guard
     */
    @Nullable
    private static LeasedBox leasedQueryBox(ServerLevel level, AABB box, RegionEntityQueryCounters.Operation operation) {
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
        RegionEntityQueryCounters.record(operation, RegionEntityQueryCounters.Path.LOCAL);
        if (!clamped.equals(box)) {
            RegionEntityOwnershipScope.recordTruncatedQuery();
            RegionEntityQueryCounters.record(operation, RegionEntityQueryCounters.Path.TRUNCATED);
        }
        return new LeasedBox(coordinate, clamped);
    }

    /**
     * {@return the entity index of the region the current worker leases, or {@code null} when the caller may
     * not answer an index-shaped query from its region}
     * <p>
     * The index is the region's own share of the level's entity view, so an answer from it is a local answer.
     * A level whose index is not published yet - entity tick workers are off, the level was never seeded, the
     * engine shut down - answers {@code null} instead of an empty index, so the caller keeps the legacy guard
     * and the refusal is counted rather than a silently empty result.
     */
    @Nullable
    private static RegionState leasedEntityIndex(ServerLevel level, RegionEntityQueryCounters.Operation operation) {
        final RegionCoordinate coordinate = RegionEntityOwnershipScope.queryCoordinate(level);
        if (coordinate == null || level.entitySectionRegionChunkSize() <= 0) {
            return null;
        }
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) || !engine.isEntityIndexPublished(level)) {
            return null;
        }
        final RegionState region = engine.regionStateFor(coordinate);
        if (region == null) {
            return null;
        }
        RegionEntityOwnershipScope.recordAllowedQuery();
        RegionEntityQueryCounters.record(operation, RegionEntityQueryCounters.Path.LOCAL);
        if (engine.indexedEntityCount(level) > region.entityTickEntryCount()) {
            // The level's index holds entities outside this region, so the region is only a part of the
            // level-wide answer. Truncation is counted, never silent.
            RegionEntityQueryCounters.record(operation, RegionEntityQueryCounters.Path.TRUNCATED);
        }
        return region;
    }

    private static boolean isEmpty(AABB box) {
        return box.minX >= box.maxX || box.minZ >= box.maxZ;
    }

    /**
     * Appends the parts of the multipart owners a box query answered, where vanilla appends the parts of the
     * level after its section scan.
     * <p>
     * Vanilla iterates {@code Level.getPartEntities()} - the live part map of the server thread - and appends
     * every part whose bounding box intersects the query box. A worker may not read that map, so it appends the
     * parts of the owners its own answer holds: a part belongs to the region of its owner, and an owner outside
     * the lease stays outside the answer exactly like its parts. Reading {@code Entity.getParts()} of an owner
     * the answer already carries costs nothing and touches no server-owned storage.
     */
    private static <T extends Entity> void appendAnsweredParts(List<Entity> owners, EntityTypeTest<Entity, T> typeTest, @Nullable Entity excluded,
            AABB box, Predicate<? super T> predicate, List<? super T> result, int max) {
        for (final Entity owner : owners) {
            for (final PartEntity<?> part : owner.getParts()) {
                if (part == excluded) {
                    continue;
                }
                final T cast = typeTest.tryCast(part);
                if (cast != null && part.getBoundingBox().intersects(box) && predicate.test(cast)) {
                    result.add(cast);
                    if (result.size() >= max) {
                        return;
                    }
                }
            }
        }
    }

    private record LeasedBox(RegionCoordinate coordinate, AABB box) {}

    /**
     * The published players a box query has to add to its region-scoped answer.
     * <p>
     * Prepared once per player-typed query from the level's per-tick snapshot: the players whose recorded
     * bounding box intersects the caller's box. {@link #noteLocal} records the players the
     * region bucket already answered, so {@link #remote()} returns exactly the players the region could not
     * see - the ones of the other regions. The preparation is counted as
     * {@link RegionEntityQueryCounters.Path#PUBLISHED} once, when a snapshot answered the query.
     */
    private static final class PlayerUnion {
        private static final PlayerUnion EMPTY = new PlayerUnion(List.of(), false);
        private final List<RegionPlayerSnapshots.Entry> entries;
        private final List<Player> local = new ArrayList<>(0);
        private final boolean published;

        private PlayerUnion(List<RegionPlayerSnapshots.Entry> entries, boolean published) {
            this.entries = entries;
            this.published = published;
        }

        /**
         * {@return the union for {@code box}, or an empty one when the query is not player-typed or the level
         * has no published snapshot}
         */
        private static PlayerUnion prepare(ServerLevel level, AABB box, Class<?> baseClass, RegionEntityQueryCounters.Operation operation) {
            if (!Player.class.isAssignableFrom(baseClass)) {
                return EMPTY;
            }
            final RegionPlayerSnapshots.Snapshot snapshot = RegionPlayerSnapshots.of(level);
            if (snapshot == null) {
                return EMPTY;
            }
            RegionEntityQueryCounters.record(operation, RegionEntityQueryCounters.Path.PUBLISHED);
            final List<RegionPlayerSnapshots.Entry> inside = new ArrayList<>(snapshot.entries().size());
            for (final RegionPlayerSnapshots.Entry entry : snapshot.entries()) {
                if (entry.intersects(box)) {
                    inside.add(entry);
                }
            }
            return new PlayerUnion(inside, true);
        }

        /** Records the players of {@code answered} as answered by the region bucket already. */
        private void noteLocal(Iterable<?> answered) {
            if (!this.published) {
                return;
            }
            for (final Object element : answered) {
                if (element instanceof Player player) {
                    this.local.add(player);
                }
            }
        }

        /** {@return the published players inside the box that the region bucket did not answer} */
        private List<ServerPlayer> remote() {
            if (!this.published || this.entries.isEmpty()) {
                return List.of();
            }
            final List<ServerPlayer> players = new ArrayList<>(this.entries.size());
            for (final RegionPlayerSnapshots.Entry entry : this.entries) {
                if (!this.local.contains(entry.player())) {
                    players.add(entry.player());
                }
            }
            return players;
        }
    }
}
