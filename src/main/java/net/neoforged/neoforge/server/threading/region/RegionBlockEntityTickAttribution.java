/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Opt-in wall-clock attribution of the server-thread block-entity tick slice.
 * <p>
 * The slice starts where the level begins its block-entity ticker pass and ends where the pass returns, so it
 * covers the whole server-thread block-entity slice of one level tick. Inside the slice the instrumentation
 * accumulates four segments: the collection and admission of the tickers up to the worker batch, the region
 * lease acquisition, the wait for the batch barrier and the deferred work that runs after the barrier.
 * <p>
 * The handoff segment is further split into the steps the publishing server thread runs after the barrier, so
 * the deferred work of a batch is attributable per step: the deferred entity removals, the server-thread
 * fallback ticks, the deferred {@code LevelTicks} mutations and the batch finish. A step that did nothing in
 * the measured workload reports zero nanoseconds and a zero count, which is how an empty step is told apart
 * from a cheap one.
 * <p>
 * The group distribution reports what the workers did with the batch: one record per region group, taken on the
 * worker that ran it, with the group's wall time, its admitted tickers and the tickers it actually ticked. The
 * aggregate - group count, mean, maximum, coefficient of variation and the per-worker busy time - separates a
 * barrier that waits for the slowest group from one that waits for work: a batch whose groups are balanced
 * reports a maximum close to the mean and a maximum close to the worker count times the mean. A latency
 * histogram of the same groups reports the percentiles, so a mean that a few very slow groups pull up is not
 * read as a uniform cost.
 * <p>
 * The fallback counters split the tickers that did not tick on a worker by the reason the slice handed them
 * back, so a workload whose whole region falls back is not confused with a workload whose snapshots went
 * stale.
 * <p>
 * The server-thread segments are measured with {@link System#nanoTime()} into plain {@code long} accumulators;
 * the group distribution is written by the workers and therefore uses additive accumulators. Nothing is
 * allocated or logged in the measured path except one accumulator entry per worker thread, so the only
 * allocation of a measured window is the per-worker load record. Only the server thread resets the
 * accumulators, between windows.
 * <p>
 * The instrumentation is disabled by default. When it is disabled every entry point returns after one
 * volatile read of {@link #isEnabled()}, which is read once per level tick and once per worker group, never
 * per ticker. A disabled attribution therefore cannot change tick behavior or cost measurable time.
 * <p>
 * The difference between {@link Snapshot#sliceNanos()} and the four segments is the batch publication and
 * setup: publishing the batch under the lifecycle gate, creating it and releasing it after the deferred
 * work.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickAttribution {
    /**
     * Width of one bucket of the group latency distribution: 4 microseconds, so {@value #LATENCY_BUCKETS}
     * buckets cover the first 4.1 milliseconds of a group and everything slower is counted as overflow.
     */
    private static final long LATENCY_BUCKET_NANOS = 4_000L;
    /** Buckets of the group latency distribution. */
    private static final int LATENCY_BUCKETS = 1024;
    /**
     * A group slower than one millisecond is a machine stall, not the cost of the slice: the measured workload's
     * groups are two orders of magnitude faster. The counters below keep those groups and their wall time
     * apart, so a mean that a stall pulled up can be reported with and without them.
     */
    private static final long STALLED_GROUP_NANOS = 1_000_000L;
    private static volatile boolean enabled;
    private static long sliceStartNanos;
    private static long planNanos;
    private static long leaseNanos;
    private static long awaitNanos;
    private static long awaitCpuNanos;
    private static long handoffNanos;
    private static long sliceNanos;
    private static long sliceCount;
    private static long handoffRemovalNanos;
    private static long handoffRemovalCount;
    private static long handoffFallbackNanos;
    private static long handoffFallbackTickers;
    private static long handoffMutationNanos;
    private static long handoffMutationCount;
    private static long handoffFinishNanos;
    private static final LongAdder awaitPolls = new LongAdder();
    private static long batchMakespanNanos;
    private static long batchMakespanMaxNanos;
    private static long batchSubmitNanos;
    private static long batchDispatchNanos;
    private static long batchSpanNanos;
    private static long batchTailNanos;
    private static final LongAdder groupCount = new LongAdder();
    private static final LongAdder groupNanos = new LongAdder();
    private static final LongAdder groupCpuNanos = new LongAdder();
    private static final LongAdder groupRetireNanos = new LongAdder();
    private static final DoubleAdder groupNanosSquared = new DoubleAdder();
    private static final AtomicLong groupNanosMax = new AtomicLong();
    private static final LongAdder groupTickers = new LongAdder();
    private static final DoubleAdder groupTickersSquared = new DoubleAdder();
    private static final AtomicLong groupTickersMax = new AtomicLong();
    private static final LongAdder groupTicked = new LongAdder();
    private static final LongAdder tickerNanos = new LongAdder();
    private static final LongAdder tickerCount = new LongAdder();
    private static final LongAdder emptyGroups = new LongAdder();
    private static final LongAdder idleGroups = new LongAdder();
    private static final LongAdder stalledGroups = new LongAdder();
    private static final LongAdder stalledGroupNanos = new LongAdder();
    private static final LongAdder[] groupNanosHistogram = new LongAdder[LATENCY_BUCKETS];
    private static final LongAdder groupNanosHistogramOverflow = new LongAdder();
    private static final ConcurrentHashMap<String, WorkerLoadAccumulator> workerLoads = new ConcurrentHashMap<>();
    private static final LongAdder[] fallbackTickers = new LongAdder[FallbackReason.values().length];
    /**
     * The CPU-time clock of the running thread, or {@code null} when this JVM does not support it. Read once,
     * because the lookup allocates and the measured path must not.
     */
    private static final ThreadMXBean CPU_TIME_BEAN = cpuTimeBean();

    static {
        for (final FallbackReason reason : FallbackReason.values()) {
            fallbackTickers[reason.ordinal()] = new LongAdder();
        }
        for (int index = 0; index < groupNanosHistogram.length; ++index) {
            groupNanosHistogram[index] = new LongAdder();
        }
    }

    private static ThreadMXBean cpuTimeBean() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() ? bean : null;
    }

    private RegionBlockEntityTickAttribution() {}

    /**
     * Why a block-entity ticker of the measured slice did not tick on a worker and ticked on the server thread
     * after the batch instead.
     */
    public enum FallbackReason {
        /**
         * The region of the ticker holds at least one ticker that is not admitted, so the whole region keeps its
         * tickers on the server thread.
         */
        UNSAFE_REGION,
        /**
         * The region of the ticker could not be resolved to a live region within the lease attempts.
         */
        UNRESOLVABLE_REGION,
        /**
         * The chunk snapshot of the ticker went stale between the plan and the worker tick.
         */
        STALE_SNAPSHOT,
        /**
         * The admission of the ticker no longer matched its ticker when the worker reached it.
         */
        STALE_ADMISSION,
        /**
         * The ticker requested a chunk the worker may not load, so it was ticked on the server thread.
         */
        CHUNK_UNAVAILABLE,
        /**
         * The batch could not reserve the block-write effects of the ticker, so it was refused before its body
         * and ticked on the server thread after the batch.
         */
        DEFERRED_UPDATE_BUDGET
    }

    /**
     * {@return true when the attribution accumulates}
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the attribution and clears the accumulators, so a measurement starts from zero.
     */
    public static void setEnabled(boolean enabled) {
        RegionBlockEntityTickAttribution.enabled = enabled;
        reset();
    }

    /**
     * Clears the accumulators and the open slice, so a measurement window starts from zero.
     */
    public static void reset() {
        sliceStartNanos = 0L;
        planNanos = 0L;
        leaseNanos = 0L;
        awaitNanos = 0L;
        awaitCpuNanos = 0L;
        handoffNanos = 0L;
        sliceNanos = 0L;
        sliceCount = 0L;
        handoffRemovalNanos = 0L;
        handoffRemovalCount = 0L;
        handoffFallbackNanos = 0L;
        handoffFallbackTickers = 0L;
        handoffMutationNanos = 0L;
        handoffMutationCount = 0L;
        handoffFinishNanos = 0L;
        awaitPolls.reset();
        batchMakespanNanos = 0L;
        batchMakespanMaxNanos = 0L;
        batchSubmitNanos = 0L;
        batchDispatchNanos = 0L;
        batchSpanNanos = 0L;
        batchTailNanos = 0L;
        groupCount.reset();
        groupNanos.reset();
        groupCpuNanos.reset();
        groupRetireNanos.reset();
        groupNanosSquared.reset();
        groupNanosMax.set(0L);
        groupTickers.reset();
        groupTickersSquared.reset();
        groupTickersMax.set(0L);
        groupTicked.reset();
        tickerNanos.reset();
        tickerCount.reset();
        emptyGroups.reset();
        idleGroups.reset();
        stalledGroups.reset();
        stalledGroupNanos.reset();
        for (final LongAdder bucket : groupNanosHistogram) {
            bucket.reset();
        }
        groupNanosHistogramOverflow.reset();
        workerLoads.clear();
        for (final LongAdder tickers : fallbackTickers) {
            tickers.reset();
        }
    }

    /**
     * Opens the slice of the block-entity ticker pass that is about to start on the server thread.
     */
    public static void beginSlice() {
        if (!enabled) {
            return;
        }
        sliceStartNanos = System.nanoTime();
    }

    /**
     * Closes the slice opened by {@link #beginSlice()}.
     */
    public static void endSlice() {
        if (!enabled) {
            return;
        }
        final long start = sliceStartNanos;
        sliceStartNanos = 0L;
        if (start == 0L) {
            return;
        }
        sliceNanos += System.nanoTime() - start;
        ++sliceCount;
    }

    /**
     * Closes the plan segment, which is the part of the slice the server thread spent collecting and admitting
     * the tickers before it submitted the worker batch.
     */
    public static void endPlanSegment() {
        if (!enabled) {
            return;
        }
        final long start = sliceStartNanos;
        if (start == 0L) {
            return;
        }
        planNanos += System.nanoTime() - start;
    }

    /**
     * Adds the time the server thread spent grouping the collected tickers by region and acquiring the region
     * leases.
     */
    public static void recordLease(long durationNanos) {
        if (enabled) {
            leaseNanos += durationNanos;
        }
    }

    /**
     * Adds the time the server thread spent submitting the worker batch and waiting for the barrier,
     * including the main-thread handoffs it serviced while it waited.
     *
     * @param durationNanos wall time of the wait
     * @param cpuNanos      the CPU time the server thread consumed during the wait, so a wait that parks is
     *                      told apart from one that runs the whole time
     */
    public static void recordAwait(long durationNanos, long cpuNanos) {
        if (enabled) {
            awaitNanos += durationNanos;
            awaitCpuNanos += cpuNanos;
        }
    }

    /**
     * {@return the CPU time of the calling thread, or {@code 0} when the JVM does not measure it}
     * <p>
     * Used around the barrier wait: the server thread that waits by polling the level's main-thread queue
     * either parks between polls or runs through the whole wait, and the two differ by the CPU time it burns
     * while the workers run.
     */
    public static long currentThreadCpuNanos() {
        final ThreadMXBean bean = CPU_TIME_BEAN;
        return bean == null ? 0L : bean.getCurrentThreadCpuTime();
    }

    /**
     * Adds the time the server thread spent on the deferred work after the barrier: the fallback tickers and
     * the batch completion.
     */
    public static void recordHandoff(long durationNanos) {
        if (enabled) {
            handoffNanos += durationNanos;
        }
    }

    /**
     * Adds one handoff step: the deferred entity removals the barrier applied on the server thread.
     *
     * @param durationNanos the wall time of the step
     * @param removals      the removals the step applied and the repeats it turned into a no-op
     */
    public static void recordHandoffRemovals(long durationNanos, long removals) {
        if (enabled) {
            handoffRemovalNanos += durationNanos;
            handoffRemovalCount += removals;
        }
    }

    /**
     * Adds one handoff step: the tickers and chunks the server thread ticked after the batch.
     *
     * @param durationNanos the wall time of the step
     * @param tickers       the block-entity tickers the step ticked
     */
    public static void recordHandoffFallback(long durationNanos, long tickers) {
        if (enabled) {
            handoffFallbackNanos += durationNanos;
            handoffFallbackTickers += tickers;
        }
    }

    /**
     * Adds one handoff step: the scheduled tick mutations the workers deferred to {@code LevelTicks}.
     *
     * @param durationNanos the wall time of the step
     * @param mutations     the deferred mutations the step ran
     */
    public static void recordHandoffMutations(long durationNanos, long mutations) {
        if (enabled) {
            handoffMutationNanos += durationNanos;
            handoffMutationCount += mutations;
        }
    }

    /**
     * Adds one handoff step: releasing the batch after its deferred work ran.
     */
    public static void recordHandoffFinish(long durationNanos) {
        if (enabled) {
            handoffFinishNanos += durationNanos;
        }
    }

    /**
     * Counts the block-entity tickers that ticked on the server thread after the batch, by reason.
     *
     * @param reason  why the slice handed the tickers back
     * @param tickers the block-entity tickers of that reason
     */
    public static void recordFallback(FallbackReason reason, int tickers) {
        if (enabled) {
            fallbackTickers[reason.ordinal()].add(tickers);
        }
    }

    /**
     * Adds the wall time of the slowest group of one awaited batch.
     * <p>
     * The server thread waits for the whole batch, so this is the part of the wait the workers caused. The
     * difference between it and {@link #recordAwait} is what the barrier itself adds: dispatching the groups and
     * detecting that the last one finished. It also bounds how much of the wait a scheduling fix could remove,
     * because no barrier can finish before its slowest group.
     */
    public static void recordBatchMakespan(long durationNanos) {
        if (enabled) {
            batchMakespanNanos += durationNanos;
            if (durationNanos > batchMakespanMaxNanos) {
                batchMakespanMaxNanos = durationNanos;
            }
        }
    }

    /**
     * Adds the time the server thread needed to hand the groups of one batch to the executor.
     * <p>
     * Submission happens inside the wait and before the first group can start, so a large value is a serialized
     * cost of the barrier model itself rather than work the workers did.
     */
    public static void recordBatchSubmit(long durationNanos) {
        if (enabled) {
            batchSubmitNanos += durationNanos;
        }
    }

    /**
     * Splits the wait of one awaited batch into the time until the first group started, the span the workers ran
     * in and the time from the last group's end to the end of the wait.
     * <p>
     * The span is the only part in which workers could do work; the dispatch part is what the server thread needs
     * to hand the groups to the pool, and the tail part is how long the wait needed to notice that the last group
     * finished. A tail that is large relative to the span is completion latency, not work.
     *
     * @param awaitStartNanos      start of the wait
     * @param awaitEndNanos        end of the wait
     * @param firstGroupStartNanos start of the first group, or zero when no group reported one
     * @param lastGroupEndNanos    end of the last group, or zero when no group reported one
     */
    public static void recordBatchPhases(long awaitStartNanos, long awaitEndNanos, long firstGroupStartNanos, long lastGroupEndNanos) {
        if (!enabled || firstGroupStartNanos <= 0L || lastGroupEndNanos <= 0L) {
            return;
        }
        batchDispatchNanos += Math.max(firstGroupStartNanos - awaitStartNanos, 0L);
        batchSpanNanos += Math.max(lastGroupEndNanos - firstGroupStartNanos, 0L);
        batchTailNanos += Math.max(awaitEndNanos - lastGroupEndNanos, 0L);
    }

    /**
     * Counts one evaluation of the batch barrier condition on the server thread.
     * <p>
     * The barrier of a region batch waits by polling the level's main-thread queue, so one evaluation is one
     * poll iteration of that wait. The count per tick against the await time tells whether the server thread
     * spins through the whole wait or parks.
     */
    public static void recordAwaitPoll() {
        if (enabled) {
            awaitPolls.increment();
        }
    }

    /**
     * Adds the time one group spent retiring its region after its tickers ran.
     * <p>
     * This is the only shared section a group enters after its tickers: it takes the regionizer write lock, so a
     * batch whose groups finish together serializes there. It runs after the group's recorded wall time, so it
     * lands in the wait after the last group, not in the group distribution.
     */
    public static void recordGroupRetire(long durationNanos) {
        if (enabled) {
            groupRetireNanos.add(durationNanos);
        }
    }

    /**
     * Adds the time one ticker spent inside its own tick on a worker.
     * <p>
     * The two {@link System#nanoTime()} reads per ticker are part of the measurement, so a measured window
     * reports a slightly higher ticker cost than an uninstrumented one; every profile of a comparison pays the
     * same, and the difference between the group wall time and this sum is the wrapper the slice adds around
     * the ticker.
     */
    public static void recordTicker(long durationNanos) {
        if (enabled) {
            tickerNanos.add(durationNanos);
            tickerCount.increment();
        }
    }

    /**
     * Adds one finished worker group.
     * <p>
     * Called on the worker that ran the group, after the group released its region.
     *
     * @param durationNanos the wall time the group ran
     * @param tickers       the tickers the group was admitted to tick
     * @param ticked        the tickers the group actually ticked
     */
    public static void recordGroup(long durationNanos, long cpuNanos, int tickers, int ticked) {
        if (!enabled) {
            return;
        }
        groupCount.increment();
        groupNanos.add(durationNanos);
        groupCpuNanos.add(cpuNanos);
        groupNanosSquared.add((double) durationNanos * durationNanos);
        groupNanosMax.accumulateAndGet(durationNanos, Math::max);
        groupTickers.add(tickers);
        groupTickersSquared.add((double) tickers * tickers);
        groupTickersMax.accumulateAndGet(tickers, Math::max);
        groupTicked.add(ticked);
        if (tickers == 0) {
            emptyGroups.increment();
        }
        if (ticked == 0) {
            idleGroups.increment();
        }
        if (durationNanos >= STALLED_GROUP_NANOS) {
            stalledGroups.increment();
            stalledGroupNanos.add(durationNanos);
        }
        final long bucket = durationNanos / LATENCY_BUCKET_NANOS;
        if (bucket < groupNanosHistogram.length) {
            groupNanosHistogram[(int) bucket].increment();
        } else {
            groupNanosHistogramOverflow.increment();
        }
        workerLoads.computeIfAbsent(Thread.currentThread().getName(), name -> new WorkerLoadAccumulator()).record(durationNanos, tickers);
    }

    /**
     * {@return the accumulated attribution of the server thread so far}
     */
    public static Snapshot snapshot() {
        final List<WorkerLoad> loads = new ArrayList<>(workerLoads.size());
        workerLoads.forEach((thread, load) -> loads.add(load.snapshot(thread)));
        loads.sort(Comparator.comparing(WorkerLoad::thread));
        final long[] fallbacks = new long[fallbackTickers.length];
        for (final FallbackReason reason : FallbackReason.values()) {
            fallbacks[reason.ordinal()] = fallbackTickers[reason.ordinal()].sum();
        }
        final long[] buckets = new long[groupNanosHistogram.length];
        for (int index = 0; index < buckets.length; ++index) {
            buckets[index] = groupNanosHistogram[index].sum();
        }
        return new Snapshot(
                planNanos,
                leaseNanos,
                awaitNanos,
                awaitCpuNanos,
                handoffNanos,
                sliceNanos,
                sliceCount,
                awaitPolls.sum(),
                batchMakespanNanos,
                batchMakespanMaxNanos,
                batchSubmitNanos,
                batchDispatchNanos,
                batchSpanNanos,
                batchTailNanos,
                new HandoffSteps(handoffRemovalNanos, handoffRemovalCount, handoffFallbackNanos, handoffFallbackTickers,
                        handoffMutationNanos, handoffMutationCount, handoffFinishNanos),
                new GroupDistribution(groupCount.sum(), groupNanos.sum(), groupNanosSquared.sum(), groupNanosMax.get(),
                        groupTickers.sum(), groupTickersSquared.sum(), groupTickersMax.get(), groupTicked.sum(), groupCpuNanos.sum(),
                        groupRetireNanos.sum(),
                        tickerNanos.sum(), tickerCount.sum(),
                        emptyGroups.sum(), idleGroups.sum(), stalledGroups.sum(), stalledGroupNanos.sum(),
                        new LatencyDistribution(LATENCY_BUCKET_NANOS, buckets, groupNanosHistogramOverflow.sum()), loads),
                fallbacks);
    }

    /**
     * The accumulated attribution of the block-entity tick slice, in nanoseconds.
     *
     * @param sliceCount            the number of slices the accumulators cover, so the segments can be reported per
     *                              tick
     * @param awaitNanos            wall time of the barrier wait
     * @param awaitCpuNanos         CPU time the server thread consumed during that wait
     * @param awaitPolls            the barrier condition evaluations of the measured slices, i.e. the poll
     *                              iterations of the server-thread wait
     * @param batchMakespanNanos    the summed wall time of the slowest group of every awaited batch
     * @param batchMakespanMaxNanos the slowest single group of the measured slices
     * @param batchSubmitNanos      the summed time the server thread needed to hand the groups to the executor
     * @param batchDispatchNanos    the summed time from the start of the wait to the first group's start
     * @param batchSpanNanos        the summed span the workers of a batch ran in
     * @param batchTailNanos        the summed time from the last group's end to the end of the wait
     * @param handoffSteps          the split of {@code handoffNanos} into the steps of the deferred work
     * @param groups                what the worker groups of the measured slices did
     * @param fallbackTickers       the tickers that ticked on the server thread after the batch, indexed by
     *                              {@link FallbackReason#ordinal()}
     */
    public record Snapshot(
            long planNanos,
            long leaseNanos,
            long awaitNanos,
            long awaitCpuNanos,
            long handoffNanos,
            long sliceNanos,
            long sliceCount,
            long awaitPolls,
            long batchMakespanNanos,
            long batchMakespanMaxNanos,
            long batchSubmitNanos,
            long batchDispatchNanos,
            long batchSpanNanos,
            long batchTailNanos,
            HandoffSteps handoffSteps,
            GroupDistribution groups,
            long[] fallbackTickers) {
        /**
         * {@return the tickers that ticked on the server thread after the batch for {@code reason}}
         */
        public long fallbackTickers(FallbackReason reason) {
            return this.fallbackTickers[reason.ordinal()];
        }
    }

    /**
     * The steps of the deferred work the publishing server thread ran after the barrier, in nanoseconds and
     * counts.
     *
     * @param removalNanos    wall time of the deferred entity removals
     * @param removalCount    removals applied plus removals that were already applied (no-ops)
     * @param fallbackNanos   wall time of the server-thread fallback ticks
     * @param fallbackTickers block-entity tickers ticked by the fallback
     * @param mutationNanos   wall time of the deferred {@code LevelTicks} mutations
     * @param mutationCount   deferred mutations run
     * @param finishNanos     wall time of releasing the batch
     */
    public record HandoffSteps(
            long removalNanos,
            long removalCount,
            long fallbackNanos,
            long fallbackTickers,
            long mutationNanos,
            long mutationCount,
            long finishNanos) {}

    /**
     * What the worker groups of the measured slices did.
     * <p>
     * The distribution is over groups, not over ticks: a tick whose batch holds one group per region contributes
     * one sample per region, so {@code maxNanos} is the slowest group of the window and
     * {@code nanos / groups} its mean. The squared sum makes the coefficient of variation computable, which is
     * the load spread across regions; {@code workers} is the same for the threads that ran them.
     *
     * @param groups         finished groups
     * @param nanos          summed group wall time
     * @param nanosSquared   summed squared group wall time, for the coefficient of variation
     * @param maxNanos       slowest group
     * @param tickers        summed admitted tickers
     * @param tickersSquared summed squared admitted tickers
     * @param maxTickers     largest group by admitted tickers
     * @param ticked         summed ticked tickers
     * @param cpuNanos       summed CPU time of the groups, so wall time lost to preemption is told apart from
     *                       time lost to memory or cache contention
     * @param retireNanos    summed time the groups spent retiring their region under the regionizer write lock
     * @param tickerNanos    summed wall time the ticked tickers spent inside their own tick
     * @param tickerCount    the tickers the timing was taken for
     * @param emptyGroups    groups that were admitted no ticker at all
     * @param idleGroups     groups that ticked no ticker
     * @param stalledGroups  groups slower than {@link #STALLED_GROUP_NANOS}, i.e. machine stalls rather than
     *                       the cost of the slice
     * @param stalledNanos   the wall time of those groups
     * @param latency        the distribution of the group wall times, so a mean that a few very slow groups pull
     *                       up is not read as a uniform cost
     * @param workers        the per-worker load of the same groups
     */
    public record GroupDistribution(
            long groups,
            long nanos,
            double nanosSquared,
            long maxNanos,
            long tickers,
            double tickersSquared,
            long maxTickers,
            long ticked,
            long cpuNanos,
            long retireNanos,
            long tickerNanos,
            long tickerCount,
            long emptyGroups,
            long idleGroups,
            long stalledGroups,
            long stalledNanos,
            LatencyDistribution latency,
            List<WorkerLoad> workers) {}

    /**
     * Nearest-rank latency distribution of the group wall times.
     *
     * @param bucketNanos the width of one bucket; bucket {@code i} holds the groups that ran between
     *                    {@code i * bucketNanos} and {@code (i + 1) * bucketNanos} nanoseconds
     * @param buckets     the group count per bucket
     * @param overflow    the groups slower than the last bucket
     */
    public record LatencyDistribution(long bucketNanos, long[] buckets, long overflow) {
        /** {@return the number of groups the distribution covers} */
        public long count() {
            long total = this.overflow;
            for (final long bucket : this.buckets) {
                total += bucket;
            }
            return total;
        }

        /**
         * {@return the upper bound of the bucket that holds the nearest-rank {@code percentile} group}
         * <p>
         * A group in the overflow tail reports the last bucket, because the distribution only knows that it was
         * slower than that.
         */
        public long percentileNanos(double percentile) {
            final long total = this.count();
            if (total <= 0L) {
                return 0L;
            }
            final long rank = Math.max(1L, (long) Math.ceil(percentile * total));
            long seen = 0L;
            for (int index = 0; index < this.buckets.length; ++index) {
                seen += this.buckets[index];
                if (seen >= rank) {
                    return (index + 1L) * this.bucketNanos;
                }
            }
            return this.buckets.length * this.bucketNanos;
        }
    }

    /**
     * The groups one worker thread ran.
     *
     * @param thread  the name of the worker thread
     * @param groups  finished groups
     * @param tickers admitted tickers
     * @param nanos   summed group wall time
     */
    public record WorkerLoad(String thread, long groups, long tickers, long nanos) {}

    /**
     * Mutable accumulator of one worker thread's groups.
     */
    private static final class WorkerLoadAccumulator {
        private final LongAdder groups = new LongAdder();
        private final LongAdder tickers = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        void record(long durationNanos, int admittedTickers) {
            this.groups.increment();
            this.tickers.add(admittedTickers);
            this.nanos.add(durationNanos);
        }

        WorkerLoad snapshot(String thread) {
            return new WorkerLoad(thread, this.groups.sum(), this.tickers.sum(), this.nanos.sum());
        }
    }
}
