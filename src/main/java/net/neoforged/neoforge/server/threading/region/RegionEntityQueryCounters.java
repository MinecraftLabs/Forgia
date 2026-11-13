/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-operation counters of the entity queries a native region worker issues.
 * <p>
 * Every operation that a worker can reach reports one of four answers, and the counters keep them apart so
 * the report shows how often each path actually runs:
 * <ul>
 * <li>{@link Path#LOCAL} - answered from the entity index or the physical section bucket of the region the
 * worker leases.</li>
 * <li>{@link Path#PUBLISHED} - answered from a snapshot the server thread published for the current tick,
 * which is how a worker sees the players of the level and how a player-typed box query includes a player
 * standing outside the leased region.</li>
 * <li>{@link Path#TRUNCATED} - a local answer that is not the whole level answer: the query box was clamped
 * to the leased region, or the region's index holds fewer entities than the level's. Truncation is never
 * silent; it is counted here.</li>
 * <li>{@link Path#REFUSED} - no local and no published answer was available, so the legacy query guard refused
 * the operation, named it and threw; a player lookup of a level without a published snapshot is refused the
 * same way. A caller that is not a native region worker is never counted: it keeps the live storage and the
 * vanilla answer.</li>
 * </ul>
 * One call can count in two columns, e.g. a player-typed box query counts {@link Path#LOCAL} for the region
 * bucket and {@link Path#PUBLISHED} for the players the snapshot adds. The counters are plain atomics, so a
 * worker updates them without a lock and the status command reads them without one.
 */
@ApiStatus.Internal
public final class RegionEntityQueryCounters {
    /** The answer a worker received for one operation. */
    public enum Path {
        LOCAL,
        PUBLISHED,
        TRUNCATED,
        REFUSED
    }

    /**
     * The entity query entry points a native region worker can reach.
     * <p>
     * The player lookups share one operation, because every one of them - {@code getNearestPlayer},
     * {@code getPlayerByUUID}, {@code hasNearbyAlivePlayer}, {@code getNearbyPlayers} - is answered through
     * the level's player list, which is the one seam the snapshot replaces.
     */
    public enum Operation {
        GET_BY_ID("get(int)"),
        GET_BY_UUID("get(UUID)"),
        GET_ALL("getAll()"),
        GET_BY_TYPE("get(EntityTypeTest)"),
        GET_IN_BOX("get(AABB)"),
        GET_BY_TYPE_IN_BOX("get(EntityTypeTest, AABB)"),
        PLAYERS("players()");

        private final String label;

        Operation(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static final Operation[] OPERATIONS = Operation.values();
    private static final Path[] PATHS = Path.values();
    private static final AtomicLongArray COUNTS = new AtomicLongArray(OPERATIONS.length * PATHS.length);

    private RegionEntityQueryCounters() {}

    public static void record(Operation operation, Path path) {
        COUNTS.incrementAndGet(operation.ordinal() * PATHS.length + path.ordinal());
    }

    /** {@return the number of times {@code operation} answered with {@code path}} */
    public static long count(Operation operation, Path path) {
        return COUNTS.get(operation.ordinal() * PATHS.length + path.ordinal());
    }

    /**
     * Resets every counter, called when an engine is installed and by the tests that assert absolute counts.
     */
    public static void reset() {
        for (int index = 0; index < COUNTS.length(); index++) {
            COUNTS.set(index, 0L);
        }
    }

    /** {@return the immutable per-operation snapshot the diagnostics report carries} */
    static List<OperationCounters> snapshot() {
        final List<OperationCounters> counters = new ArrayList<>(OPERATIONS.length);
        for (final Operation operation : OPERATIONS) {
            counters.add(new OperationCounters(operation.label(),
                    count(operation, Path.LOCAL),
                    count(operation, Path.PUBLISHED),
                    count(operation, Path.TRUNCATED),
                    count(operation, Path.REFUSED)));
        }
        return List.copyOf(counters);
    }

    /** {@return the one status line of the per-operation counters of {@code paths}} */
    public static String summaryLine(List<OperationCounters> paths) {
        final StringBuilder line = new StringBuilder("Region entity queries:");
        for (final OperationCounters counters : paths) {
            line.append(' ').append(counters.operation())
                    .append("[local=").append(counters.local())
                    .append(",published=").append(counters.published())
                    .append(",truncated=").append(counters.truncated())
                    .append(",refused=").append(counters.refused())
                    .append(']');
        }
        return line.toString();
    }

    /** The counters of one operation, as the diagnostics report carries them. */
    public record OperationCounters(String operation, long local, long published, long truncated, long refused) {}
}
