/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * Immutable metrics of one region at the moment a {@link RegionThreadingDiagnostics} snapshot was taken.
 * <p>
 * <b>Region MSPT</b> is the wall time the region spent executing its leased work in one server tick: the
 * sum of the group durations ({@link RegionTickMetrics#recordTick(long)}) the region recorded during that
 * tick. A server tick in which the region was not leased contributes {@code 0} and is not a missed tick -
 * the region owed no work in it. The reported percentiles are nearest-rank percentiles of the per-tick
 * MSPT values of the window: in a full window {@code msptP95} is the fifth-worst and {@code msptP99} the
 * second-worst tick.
 * <p>
 * <b>Region TPS</b> is how often the region actually completed a tick per second, measured from the wall
 * clock span of the window: a region that ticks once per 50 ms server tick reports {@code 20}, a region
 * that delivers one tick per 100 ms reports {@code 10}. It is compared to the server target of
 * {@value RegionTickMetrics#TARGET_TICKS_PER_SECOND} ticks per second to classify the region as
 * {@link RegionTickStatus#OK} or {@link RegionTickStatus#BEHIND}.
 * <p>
 * The record is immutable and holds only plain values, so a diagnostics consumer (a command, a test or the
 * benchmark) can read it on any thread after the snapshot was taken without synchronizing on the engine.
 *
 * @param regionX        region coordinate on the chunk x axis
 * @param regionZ        region coordinate on the chunk z axis
 * @param dimension      dimension id of the region's level
 * @param samples        closed server ticks of the region in the window, including unleased ticks
 * @param msptAvg        mean region MSPT of the window in milliseconds
 * @param msptP95        95th percentile region MSPT of the window in milliseconds
 * @param msptP99        99th percentile region MSPT of the window in milliseconds
 * @param msptMax        worst region MSPT of the window in milliseconds
 * @param msptLast       region MSPT of the newest closed tick of the window in milliseconds
 * @param ticksPerSecond observed region ticks per second
 * @param missedTicks    server ticks the region was not offered while it stayed live in the window
 * @param status         classification of {@code ticksPerSecond}
 */
public record RegionTickSnapshot(
        int regionX,
        int regionZ,
        String dimension,
        int samples,
        double msptAvg,
        double msptP95,
        double msptP99,
        double msptMax,
        double msptLast,
        double ticksPerSecond,
        int missedTicks,
        RegionTickStatus status) {
    /**
     * {@return true when the window holds at least one closed tick}
     */
    public boolean hasSamples() {
        return this.samples > 0;
    }

    /**
     * {@return the region coordinate and dimension of this snapshot, for command output and logs}
     */
    public String describeRegion() {
        return this.dimension + "@" + this.regionX + "," + this.regionZ;
    }
}
