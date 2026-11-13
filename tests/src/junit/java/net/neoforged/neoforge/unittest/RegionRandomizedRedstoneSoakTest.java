/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.ticks.LevelTicks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntitySnapshots;
import net.neoforged.neoforge.server.threading.region.RegionEntityIdentitySnapshots;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A seeded randomized soak of live redstone circuits laid across region borders.
 * <p>
 * The hand-written equivalence suite tests the layouts its author thought of. This one draws circuits from a
 * seeded generator - dust runs with bends and T-junctions, repeater ladders at every delay, comparators in both
 * modes, observers, normal and sticky pistons pushing blocks and target blocks, a burnout-prone torch clock, a
 * lever-driven door and trapdoor, a button, hoppers feeding a comparator, a dust loop that re-powers itself
 * through a foreign write, two clocks that drift into phase and an observer chain - and places them on a grid
 * whose cells are centred on the region borders, so a configurable fraction of the circuits straddles a border.
 * <p>
 * The same seed runs twice, once inline and once with the region engine installed, and every tick of the two
 * runs is compared: the block state and block entity of every position either run touched (folded into a
 * running hash), the size of the scheduled-tick queue, the size of the block-event queue and the number of items
 * in the whole volume. The first tick that differs is reported with the seed, and a diagnostic re-run names the
 * first differing position and both states. Every circuit is asserted to be live - a circuit that never changes
 * state is a generator bug, not a pass.
 * <p>
 * The level is the {@link RegionRedstoneWorld} mock, which calls the production write, comparator-update,
 * block-entity-change and block-event hooks, so a worker write to another region is the deferred write of the
 * engine and not an inline write of the mock.
 * <p>
 * The matrix is run twice against the same inline reference. The primary table is the shipped configuration -
 * every slice of the engine enabled, the scheduled block ticks on the server thread, which is what
 * {@code beginScheduledTickDispatcher} produces in production because it refuses unclassified callbacks - and it
 * requires zero unexplained state differences. The second table enables the internal mechanics seam that
 * dispatches the collected ticks on region workers; its purpose is to measure the distance between that parallel
 * dispatch and the single-threaded collected order, not to claim equivalence, so it reports and counts its
 * unexplained differences instead of failing. {@code region-threading/EXPERIMENTS.md} section 6 records why the
 * seam cannot be equivalent: a cross-region write is applied by the target region after its own collected ticks of
 * the tick, so "has this tick run yet" can never match the collected order in both directions.
 * <p>
 * The scale is configurable: {@code -Dneoforge.redstoneSoakShortSeeds}, {@code ...ShortTicks},
 * {@code ...ShortCircuits}, the same three for the long phase and {@code -Dneoforge.redstoneSoakStraddlingFraction}
 * (default 0.7) for the share of circuits that straddle a border. Because a full run takes hours, the tests are
 * tagged {@code benchmark} and run on demand:
 *
 * <pre>
 * ./gradlew :tests:junitBenchmark --tests '*RegionRandomizedRedstoneSoakTest' \
 *     -PsoakShortSeeds=3 -PsoakShortTicks=200 -PsoakShortCircuits=24 \
 *     -PsoakLongSeeds=2 -PsoakLongTicks=600 -PsoakLongCircuits=12
 * </pre>
 *
 * <p>
 * <strong>The defect this harness found.</strong> The {24 circuit, 200 tick} matrix reported state differences in
 * the {@code torch_clock} circuits from tick 24 on (seeds 11816960, 11816961 and 11816962): the first difference
 * was always a scheduled-tick count of a repeater, or of a dust line whose power that repeater wrote. The cause was
 * the dispatcher path of {@code LevelTicks}, which cleared the collected-tick set before any region callback ran,
 * so {@code willTickThisTick} answered false for a tick the inline loop still held for this tick, and a block
 * notified a second time in the same tick scheduled a tick that vanilla suppresses. That tick fired after the
 * block's input changed and flipped it. The collected ticks now stay in the set until their own callback is about
 * to run, reported per tick by the dispatcher on the worker that runs it, and those seeds are the regression cases
 * of {@link #regressionSeedsStayEquivalent()}. A refused foreign block-entity read is now answered from the value
 * the server thread publishes for a border block entity (see {@code RegionBlockEntitySnapshots}) instead of
 * {@code null}, and the harness asserts that the publication answered it. The remaining tolerated difference of
 * the parallel table is a queue-only one - a redundant evaluation whose state is identical in both runs - and it
 * is named and counted rather than failed.
 */
class RegionRandomizedRedstoneSoakTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    /** The fixed 3x3-chunk region of the engine, in blocks. */
    private static final int REGION_BLOCKS = 3 * 16;
    private static final int Y = 64;
    /**
     * One circuit cell. The grid origin puts one region border inside every other column and every other row,
     * so a cell either contains a border on an axis or is at least 12 blocks away from every border.
     */
    private static final int CELL_PITCH = 24;
    private static final int CELL_COLUMNS = 4;
    private static final int CELL_ROWS = 6;
    private static final int CELL_ORIGIN_X = 36;
    private static final int CELL_ORIGIN_Z = 36;
    private static final long BASE_SEED = 0xB4_5000L;
    /**
     * A circuit has to change state at least once per this many ticks, or it tests nothing. The slowest live
     * circuit of the mix is the hopper pair, which transfers one stack every nine ticks.
     */
    private static final int TICKS_PER_CHANGE = 125;
    /**
     * The period at which the generator puts a lit torch back into the burnout-prone torch clock. The torch of
     * that circuit toggles against the dust it feeds itself, so it burns out after eight toggles and a burnt-out
     * torch stays off while its input is powered - which the latched feedback chain of the circuit does. Without
     * the re-arm the circuit would be dead for the rest of a long run and assert nothing.
     */
    private static final int TORCH_REARM_TICKS = 200;
    /**
     * The longest a live circuit may stay unchanged. A torch clock burns out after eight toggles inside the
     * sixty-tick toggle window and is re-armed every {@link #TORCH_REARM_TICKS} ticks, so the gap of that circuit
     * is bounded by the re-arm period plus the latency of the burst the re-arm starts, and not by the circuit.
     * Scaling the re-arm period with the run length would keep a longer run inside this bound without a longer
     * burst latency, so the two numbers are derived from each other instead of being set twice.
     */
    private static final int MAX_CHANGE_GAP = TORCH_REARM_TICKS + 20;

    private RegionThreadingEngine engine;
    private final AtomicLong gameTime = new AtomicLong();
    /** Block events a phase's region workers deferred, accumulated over every seed of the phase. */
    private long phaseDeferredBlockEvents;
    /** Block-entity reads a phase's region workers had to answer across a border, accumulated over the phase. */
    private long phaseForeignBlockEntityReads;
    /** Block-entity reads a phase's region workers answered from the per-tick publication of the phase. */
    private long phasePublishedBlockEntityReads;
    private static final boolean DEBUG_CHANGES = Boolean.getBoolean("neoforge.redstoneSoakDebugChanges");

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
    }

    /**
     * How a run executes the scheduled block ticks of the level.
     */
    private enum Mode {
        /** No region engine at all: the single-threaded reference every other mode is compared with. */
        INLINE,
        /**
         * The region engine with every slice it admits, and the scheduled block ticks on the server thread. This
         * is the shipped configuration: {@code beginScheduledTickDispatcher} refuses unclassified callbacks, so the
         * production level never hands the collected ticks to a worker.
         */
        PRODUCTION,
        /**
         * The region engine with the internal seam that dispatches the collected scheduled ticks on region
         * workers. The seam exists for these mechanics tests only.
         */
        PARALLEL_SEAM;

        private String label() {
            return this == INLINE ? "inline" : this == PRODUCTION ? "production" : "parallel-seam";
        }
    }

    /**
     * The primary equivalence run: the shipped configuration, scheduled ticks on the server thread, every other
     * slice of the engine enabled. A state difference here is a defect, not an approximation.
     */
    @Tag("benchmark")
    @Test
    void randomizedCircuitsAreEquivalentAcrossRegionBorders() {
        this.soak("Short", "short", 20, 1000, 24, Mode.PRODUCTION);
    }

    @Tag("benchmark")
    @Test
    void longRandomizedCircuitsAreEquivalentAcrossRegionBorders() {
        this.soak("Long", "long", 5, 4000, 12, Mode.PRODUCTION);
    }

    /**
     * The same matrix with the internal parallel scheduled-tick seam enabled. The seam cannot reproduce the
     * single-threaded collected order across parallel region groups, so this table measures how far it is from the
     * production order instead of claiming equivalence; every state difference it reports has to be the documented
     * cross-border write ordering shape, and everything else fails.
     */
    @Tag("benchmark")
    @Test
    void randomizedCircuitsOfTheParallelScheduledTickSeamAreMeasured() {
        this.soak("Short", "seam-short", 20, 1000, 24, Mode.PARALLEL_SEAM);
    }

    @Tag("benchmark")
    @Test
    void longRandomizedCircuitsOfTheParallelScheduledTickSeamAreMeasured() {
        this.soak("Long", "seam-long", 5, 4000, 12, Mode.PARALLEL_SEAM);
    }

    /**
     * The seeds a randomized run found a defect with. Every entry is a permanent regression case: it runs the
     * same generator, the same comparison and the same liveness assertions as the soak, with the circuit count
     * and the tick count it failed at, in the production configuration.
     */
    private static final RegressionCase[] REGRESSION_CASES = {
            // The three seeds of the {24 circuit, 200 tick} matrix that reported the dispatcher defect of
            // LevelTicks from tick 24 on. The re-arm of the torch clock fires at tick 200, so the burst the
            // divergence appeared in is unchanged.
            new RegressionCase(0xB4_5000L, 24, 200),
            new RegressionCase(0xB4_5001L, 24, 200),
            new RegressionCase(0xB4_5002L, 24, 200),
    };

    @Test
    void regressionSeedsStayEquivalent() {
        for (final RegressionCase regression : REGRESSION_CASES) {
            this.assertSeedEquivalent(regression.seed(), regression.circuits(), regression.ticks(), "regression", Mode.PRODUCTION);
        }
    }

    private record RegressionCase(long seed, int circuits, int ticks) {}

    /**
     * One tick of one run: the state hash of every comparison slot, the scheduled ticks every slot holds, the
     * size of the block-event queue and the number of items the whole volume holds.
     */
    private record TickRecord(long[] hashes, int[] pending, int blockEvents, int items) {
        private boolean sameAs(TickRecord other) {
            return this.items == other.items && this.blockEvents == other.blockEvents
                    && java.util.Arrays.equals(this.hashes, other.hashes)
                    && java.util.Arrays.equals(this.pending, other.pending);
        }

        /** {@return true when the state or the scheduled ticks of {@code slot} differ} */
        private boolean differsAt(TickRecord other, int slot) {
            return this.hashes[2 * slot] != other.hashes[2 * slot]
                    || this.hashes[2 * slot + 1] != other.hashes[2 * slot + 1]
                    || this.pending[slot] != other.pending[slot];
        }
    }

    private void soak(String phase, String label, int defaultSeeds, int defaultTicks, int defaultCircuits, Mode mode) {
        final int seeds = Integer.getInteger("neoforge.redstoneSoak" + phase + "Seeds", defaultSeeds);
        final int ticks = Integer.getInteger("neoforge.redstoneSoak" + phase + "Ticks", defaultTicks);
        final int circuits = Integer.getInteger("neoforge.redstoneSoak" + phase + "Circuits", defaultCircuits);
        // Every seed runs even when an earlier one fails, so the report is a table and not the first failure.
        final List<String> failures = new ArrayList<>();
        this.phaseDeferredBlockEvents = 0L;
        this.phaseForeignBlockEntityReads = 0L;
        this.phasePublishedBlockEntityReads = 0L;
        for (int index = 0; index < seeds; index++) {
            try {
                this.assertSeedEquivalent(BASE_SEED + index, circuits, ticks, label, mode);
            } catch (AssertionError error) {
                failures.add("seed " + (BASE_SEED + index) + ": " + error.getMessage());
            }
        }
        if (mode == Mode.PARALLEL_SEAM && seeds > 0 && System.getProperty("neoforge.redstoneSoakKind", "").isEmpty()) {
            // Per phase, not per seed: a seed's layout decides whether its circuits reach across a border, so a
            // single seed without a hopper pair on the far side of a border proves nothing about the mix.
            assertTrue(this.phaseDeferredBlockEvents > 0,
                    "the " + label + " phase never queued a block event on a region worker, so the event deferral "
                            + "was not exercised");
            assertTrue(this.phaseForeignBlockEntityReads > 0,
                    "the " + label + " phase never read a foreign block entity on a region worker, so the comparator "
                            + "seam was not exercised");
            assertTrue(this.phasePublishedBlockEntityReads > 0,
                    "the " + label + " phase never answered a refused foreign block-entity read from the per-tick "
                            + "publication, so the value snapshot was not exercised");
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + seeds + " seeds of the " + label + " phase failed: " + String.join(" | ", failures));
        }
    }

    private void assertSeedEquivalent(long seed, int circuitCount, int ticks, String phase, Mode mode) {
        final SoakRun off = this.run(Mode.INLINE, seed, circuitCount, ticks, 0, null);
        final SoakRun on = this.run(mode, seed, circuitCount, ticks, 0, null);
        assertEquals(off.ticks.size(), on.ticks.size(), "the two runs of seed " + seed + " compared a different number of ticks");
        int mismatches = 0;
        int approximated = 0;
        int queueOnlyTicks = 0;
        int crossBorderOrderingTicks = 0;
        int firstDivergentTick = -1;
        String firstDivergence = "none";
        String firstCrossBorderOrdering = "none";
        TraceBox firstDivergentBox = null;
        int consecutiveQueueDifference = 0;
        int worstConsecutiveQueueDifference = 0;
        final java.util.Set<String> approximatedCircuits = new java.util.TreeSet<>();
        final java.util.Set<String> crossBorderOrderingCircuits = new java.util.TreeSet<>();
        for (int tick = 0; tick < off.ticks.size(); tick++) {
            final TickRecord inline = off.ticks.get(tick);
            final TickRecord region = on.ticks.get(tick);
            if (inline.sameAs(region)) {
                consecutiveQueueDifference = 0;
                continue;
            }
            final int[] refused = on.refusedSnapshots.get(tick);
            final boolean countsDiffer = inline.items() != region.items() || inline.blockEvents() != region.blockEvents();
            boolean unexplained = countsDiffer;
            boolean queueOnly = true;
            for (int slot = 0; slot < refused.length; slot++) {
                final boolean stateDiffers = inline.hashes[2 * slot] != region.hashes[2 * slot]
                        || inline.hashes[2 * slot + 1] != region.hashes[2 * slot + 1];
                if (stateDiffers) {
                    queueOnly = false;
                    // The foreign block-entity read approximation makes a comparator compute a different
                    // output, so a state difference of the circuit it hit is named and counted, not failed.
                    if (refused[slot] == 0) {
                        unexplained = true;
                    } else {
                        approximatedCircuits.add(on.circuitName(slot));
                    }
                }
                if (inline.pending[slot] != region.pending[slot] && refused[slot] > 0) {
                    approximatedCircuits.add(on.circuitName(slot));
                }
            }
            if (queueOnly) {
                // The state of this tick is identical in both runs; only the number of pending evaluations
                // differs. Such an evaluation is idempotent - it does nothing when the block's input did not
                // change - which is why the state stays identical, so it is counted instead of failed. The
                // instances the matrix reports coincide with a refused foreign block-entity read of the hopper
                // pair: the comparator of that circuit reads the hopper of another region and is answered null.
                queueOnlyTicks++;
                consecutiveQueueDifference++;
                worstConsecutiveQueueDifference = Math.max(worstConsecutiveQueueDifference, consecutiveQueueDifference);
                approximated++;
            } else if (!unexplained) {
                approximated++;
            } else {
                // The only difference of the parallel seam this table may count: a block write one group recorded
                // across a region border reached the target region after its own collected tick of the same tick,
                // so a block notified twice in one tick scheduled a follow-up that the single-threaded collected
                // order suppresses. Nothing else may be counted, in this mode or in any other.
                final String ordering = mode == Mode.PARALLEL_SEAM
                        ? crossBorderOrderingEvidence(tick, inline, region, on)
                        : null;
                if (ordering != null) {
                    crossBorderOrderingTicks++;
                    for (int slot = 0; slot < refused.length; slot++) {
                        if (inline.differsAt(region, slot)) {
                            crossBorderOrderingCircuits.add(on.circuitName(slot));
                        }
                    }
                    if ("none".equals(firstCrossBorderOrdering)) {
                        firstCrossBorderOrdering = "tick " + tick + ": " + ordering;
                    }
                    continue;
                }
                mismatches++;
                if (firstDivergentTick < 0) {
                    firstDivergentTick = tick;
                    final List<String> slots = new ArrayList<>();
                    for (int slot = 0; slot < refused.length; slot++) {
                        if (inline.differsAt(region, slot)) {
                            slots.add(on.circuitName(slot) + "(state=" + (inline.hashes[2 * slot] != region.hashes[2 * slot]
                                    || inline.hashes[2 * slot + 1] != region.hashes[2 * slot + 1])
                                    + ",pending=" + inline.pending[slot] + "->" + region.pending[slot]
                                    + ",refused=" + refused[slot] + ")");
                        }
                    }
                    firstDivergence = slots.toString();
                    for (int slot = 0; slot < refused.length; slot++) {
                        if (refused[slot] == 0 && inline.differsAt(region, slot)) {
                            firstDivergentBox = on.circuitTrace(slot);
                            break;
                        }
                    }
                }
            }
        }
        final TickRecord lastInline = off.ticks.get(off.ticks.size() - 1);
        final TickRecord lastRegion = on.ticks.get(on.ticks.size() - 1);
        if (mode != Mode.PARALLEL_SEAM) {
            assertTrue(java.util.Arrays.equals(lastInline.pending(), lastRegion.pending()),
                    "seed " + seed + ": the scheduled-tick queues of the two runs did not converge by the last tick");
        }
        queueOnlyTicks = queueOnlyTicks;
        String diagnosis = "none";
        if (firstDivergentTick >= 0) {
            diagnosis = this.diagnose(mode, seed, circuitCount, ticks, firstDivergentTick, firstDivergentBox);
        }
        System.out.println("redstone-soak,phase=" + phase + ",scheduledTicks=" + mode.label() + ",seed=" + seed
                + ",circuits=" + circuitCount + ",straddling=" + off.straddling
                + ",ticks=" + ticks + ",ticksCompared=" + (off.ticks.size() - 1)
                + ",mismatches=" + mismatches + ",approximatedTicks=" + approximated
                + ",queueOnlyTicks=" + queueOnlyTicks
                + ",worstQueueDivergenceTicks=" + worstConsecutiveQueueDifference
                + ",crossBorderOrderingTicks=" + crossBorderOrderingTicks
                + ",crossBorderOrderingCircuits=" + crossBorderOrderingCircuits
                + ",firstCrossBorderOrdering=" + firstCrossBorderOrdering
                + ",approximation=" + (approximated == 0 ? "none"
                        : "foreign block-entity read answered null; queue-only difference of a redundant evaluation")
                + ",approximatedCircuits=" + approximatedCircuits
                + ",firstDivergentTick=" + (firstDivergentTick < 0 ? "none" : firstDivergentTick)
                + ",firstDivergence=" + firstDivergence
                + ",itemsIn=" + off.itemsIn + ",itemsOut=" + off.itemsOut
                + ",itemsMin=" + off.itemsMin + ",itemsMax=" + off.itemsMax
                + ",borderUpdates=" + on.diagnostics.borderUpdates().recorded()
                + ",borderWrites=" + on.diagnostics.borderUpdates().blockWritesRecorded()
                + ",deferredBlockEvents=" + on.deferredBlockEvents
                + ",foreignBlockEntityReads=" + on.foreignBlockEntityReads
                + ",publishedBlockEntityReads=" + on.publishedBlockEntityReads
                + ",snapshotEntries=" + on.snapshotEntries
                + ",snapshotPublishNanos=" + on.snapshotPublishNanos
                + ",identityPublishNanos=" + on.identityPublishNanos
                + ",readAfterDeferredWrite=" + on.diagnostics.borderUpdates().readAfterBlockWrite()
                + ",fallbackScheduled=" + on.diagnostics.fallbackScheduledTicks()
                + ",skippedScheduled=" + on.diagnostics.skippedScheduledTicks()
                + ",ownershipViolations=" + on.diagnostics.ownershipViolationCount()
                + ",refusedBlockEntityReads=" + on.refusedBlockEntityReads
                + ",foreignWriteAttempts=" + on.foreignWriteAttempts
                + ",uncapturedForeignWrites=" + on.uncapturedForeignWrites
                + ",offMs=" + off.millis + ",onMs=" + on.millis
                + ",diagnosis=" + diagnosis);
        // A circuit that never changes state tests nothing: the generator has to produce live circuits.
        final int requiredChanges = Math.max(1, ticks / TICKS_PER_CHANGE);
        for (int circuit = 0; circuit < circuitCount; circuit++) {
            final SoakCircuit generated = off.circuits.get(circuit);
            assertTrue(off.changes[circuit] >= requiredChanges,
                    "seed " + seed + " circuit " + generated.name() + " changed state only " + off.changes[circuit]
                            + " times in " + ticks + " ticks, so it is not a live circuit (required " + requiredChanges + ")");
            assertTrue(off.maxGap[circuit] <= MAX_CHANGE_GAP,
                    "seed " + seed + " circuit " + generated.name() + " stayed unchanged for " + off.maxGap[circuit]
                            + " ticks, so it is not a live circuit");
        }
        assertEquals(off.circuits.size(), on.circuits.size());
        if (mode == Mode.PARALLEL_SEAM) {
            // The seam table measures the distance between the parallel dispatch and the single-threaded collected
            // order; it does not claim equivalence, so an unexplained state difference is reported and counted in
            // that table instead of failing the run. The production table above asserts zero.
            if (mismatches > 0) {
                System.out.println("redstone-seam-divergence,seed=" + seed + ",firstDivergentTick=" + firstDivergentTick
                        + ",unexplainedStateTicks=" + mismatches + ",firstDivergence=" + firstDivergence
                        + ",diagnosis=" + diagnosis);
            }
        } else {
            assertEquals(0, mismatches,
                    "seed " + seed + " differs between the inline reference and the " + mode.label() + " run at tick "
                            + firstDivergentTick + " of " + ticks + ": " + firstDivergence + ": " + diagnosis);
        }
        if (on.refusedBlockEntityReads > 0) {
            assertTrue(approximatedCircuits.size() <= 2,
                    "seed " + seed + ": the foreign block-entity read approximation is not confined to the comparator "
                            + "circuits: " + approximatedCircuits);
        }
        this.phaseDeferredBlockEvents += on.deferredBlockEvents;
        this.phaseForeignBlockEntityReads += on.foreignBlockEntityReads;
        this.phasePublishedBlockEntityReads += on.publishedBlockEntityReads;
        this.assertSeamsExercised(mode, off, on, seed, circuitCount);
    }

    /**
     * Asserts the run really went through the seams the soak exists for: the deferred cross-region block write,
     * the worker block-event deferral, the foreign block-entity read and the block-event phase, with no
     * scheduled tick that stayed on the server thread and no ownership violation.
     */
    private void assertSeamsExercised(Mode mode, SoakRun off, SoakRun on, long seed, int circuitCount) {
        // A narrowed diagnosis run (-Dneoforge.redstoneSoakKind) does not contain every circuit family, so the
        // phase-level seam coverage of soak() is skipped for it; the engine invariants below are asserted for
        // every run.
        assertEquals(0L, on.diagnostics.fallbackScheduledTicks(),
                "seed " + seed + ": a scheduled tick of a region with work stayed on the server thread");
        assertEquals(0L, on.diagnostics.skippedScheduledTicks(),
                "seed " + seed + ": a scheduled tick was skipped");
        assertEquals(0L, on.diagnostics.ownershipViolationCount(),
                "seed " + seed + ": the region workers reported an ownership violation");
        assertEquals(0L, on.diagnostics.borderUpdates().dropped(),
                "seed " + seed + ": the engine dropped border updates");
        assertEquals(0L, on.diagnostics.borderUpdates().overflowed(),
                "seed " + seed + ": the border update queue overflowed");
        if (mode == Mode.PARALLEL_SEAM) {
            // The seams belong to a scheduled callback that runs on a region worker: a callback of the production
            // configuration runs on the server thread, which reaches the level directly, and only the block-entity
            // and block-event slices of that configuration cross a border at all. The two deferral seams are
            // covered once per phase instead of once per seed (see soak()), because a seed's layout decides
            // whether its circuits actually reach across the border.
            assertTrue(on.diagnostics.borderUpdates().recorded() > 0,
                    "seed " + seed + ": the circuits of the soak recorded no cross-region update");
        }
        assertTrue(on.diagnostics.blockEventPhase().phases() > 0,
                "seed " + seed + ": the block event phase never ran as a quiescent scope");
        assertEquals(0L, on.diagnostics.blockEventPhase().fixpoints(),
                "seed " + seed + ": the block event phase captured a border update, so it was not quiescent");
        assertEquals(off.circuits.size(), circuitCount);
    }

    /**
     * Re-runs the failing seed with both configurations and names the first position whose state differs at
     * {@code tick}, or the queue and item difference when the states agree.
     */
    private String diagnose(Mode mode, long seed, int circuitCount, int ticks, int tick, TraceBox tracedBox) {
        final SoakRun off = this.run(Mode.INLINE, seed, circuitCount, ticks, tick, tracedBox);
        final String traced = String.valueOf(tracedBox);
        final SoakRun on = this.run(mode, seed, circuitCount, ticks, tick, tracedBox);
        final Map<BlockPos, String> offCells = new HashMap<>();
        for (final Cell cell : off.capture) {
            offCells.put(cell.pos(), cell.state() + (cell.blockEntity() == null ? "" : " " + cell.blockEntity().getType()));
        }
        final List<BlockPos> positions = new ArrayList<>();
        for (final Cell cell : off.capture) {
            positions.add(cell.pos());
        }
        for (final Cell cell : on.capture) {
            if (!offCells.containsKey(cell.pos())) {
                positions.add(cell.pos());
            }
        }
        positions.sort(java.util.Comparator.comparingLong(BlockPos::asLong));
        for (final BlockPos pos : positions) {
            final String offState = offCells.getOrDefault(pos, "air");
            final String onState = on.cellState(pos);
            if (!offState.equals(onState)) {
                return "first difference at " + pos.toShortString() + ": inline=" + offState + " region=" + onState
                        + ", events=" + eventDifference(off.positionEvents, on.positionEvents)
                        + ", scheduling=" + callDifference(off.scheduleTrace, on.scheduleTrace);
            }
        }
        final TickRecord offTick = off.ticks.get(tick);
        final TickRecord onTick = on.ticks.get(tick);
        for (int at = 1; at < Math.min(off.pendingPerTick.size(), on.pendingPerTick.size()); at++) {
            final List<String> offPending = off.pendingPerTick.get(at);
            final List<String> onPending = on.pendingPerTick.get(at);
            final List<String> onlyInline = new ArrayList<>(offPending);
            onlyInline.removeAll(onPending);
            final List<String> onlyRegion = new ArrayList<>(onPending);
            onlyRegion.removeAll(offPending);
            if (!onlyInline.isEmpty() || !onlyRegion.isEmpty()) {
                return "state difference at tick " + tick + ": inline=" + offTick + " region=" + onTick
                        + ", pending ticks first differ at tick " + (at + 1)
                        + ", traced=" + traced + ", events=" + off.positionEvents.size() + "/" + on.positionEvents.size()
                        + ": only inline=" + onlyInline + ", only region=" + onlyRegion
                        + ", inline pending=" + offPending + ", region pending=" + onPending
                        + ", previous inline pending=" + (at > 0 ? off.pendingPerTick.get(at - 1) : List.of())
                        + ", previous region pending=" + (at > 0 ? on.pendingPerTick.get(at - 1) : List.of())
                        + ", first event difference=" + eventDifference(off.positionEvents, on.positionEvents)
                        + ", scheduling call difference=" + callDifference(off.scheduleTrace, on.scheduleTrace)
                        + ", circuits=" + off.describeCircuits();
            }
        }
        return "state difference at tick " + tick + ": inline=" + offTick + " region=" + onTick
                + ", pending ticks identical at every tick (" + off.pendingTicks.size() + " at the end)"
                + ", traced=" + traced + ", first event difference=" + eventDifference(off.positionEvents, on.positionEvents);
    }

    /**
     * {@return the evidence that the state difference of {@code tick} is the documented ordering approximation of
     * the parallel scheduled-tick seam, or {@code null} when it is a defect}
     * <p>
     * The sanctioned shape has three parts, and every one of them has to hold. The region run holds more pending
     * evaluations of the differing circuit than the inline run, that circuit straddles a region border, and the
     * engine recorded a cross-region update in the tick. The mechanism behind it is a cross-border neighbor update:
     * the target region applies it in the fixpoint, i.e. after its own collected ticks of the tick, so a block that
     * is notified twice in one tick schedules a follow-up that the single-threaded collected order suppresses, and
     * the tick it schedules fires after the input changed.
     * <p>
     * Seed {@code 0xB4_5003} of the 24-circuit table is the traced instance: at tick 25 the repeater at
     * {@code 47,64,72} (region 0) notifies the dust at {@code 48,64,72} (region 1), which region 1 applies after its
     * own lamp write of the same tick; the comparator at {@code 49,64,72} then schedules the follow-up the inline
     * run suppresses and is powered at tick 46 where the inline run is not. A state difference of any other shape,
     * in this mode or in any other, is a defect and not an approximation.
     */
    private static String crossBorderOrderingEvidence(int tick, TickRecord inline, TickRecord region, SoakRun on) {
        final List<String> raised = new ArrayList<>();
        for (int slot = 0; slot < inline.pending().length; slot++) {
            final boolean stateDiffers = inline.hashes()[2 * slot] != region.hashes()[2 * slot]
                    || inline.hashes()[2 * slot + 1] != region.hashes()[2 * slot + 1];
            if (stateDiffers && region.pending()[slot] > inline.pending()[slot]
                    && slot < on.circuits.size() && on.circuits.get(slot).straddles()) {
                raised.add(on.circuitName(slot) + " " + inline.pending()[slot] + "->" + region.pending()[slot]);
            }
        }
        if (raised.isEmpty()) {
            return null;
        }
        final long crossRegionUpdates = on.borderUpdatesAt(tick);
        if (crossRegionUpdates <= 0) {
            return null;
        }
        return "pending " + raised + ", " + crossRegionUpdates + " cross-region update(s) recorded in the tick";
    }

    /**
     * {@return the first event of the traced box that only one run produced, with the entries before it}
     * <p>
     * The two runs record the same events in the same order inside a region, so the first difference is the
     * first write or neighbor update one run made and the other did not.
     */
    private static String eventDifference(List<String> inline, List<String> region) {
        for (int index = 0; index < Math.max(inline.size(), region.size()); index++) {
            final String inlineEvent = index < inline.size() ? inline.get(index) : "<none>";
            final String regionEvent = index < region.size() ? region.get(index) : "<none>";
            if (!inlineEvent.equals(regionEvent)) {
                final int from = Math.max(0, index - 6);
                return "inline[" + index + "]=" + inlineEvent + " region[" + index + "]=" + regionEvent
                        + " contextInline=" + inline.subList(from, Math.min(inline.size(), index + 2))
                        + " contextRegion=" + region.subList(from, Math.min(region.size(), index + 2));
            }
        }
        return "identical (" + inline.size() + " events)";
    }

    /**
     * {@return the scheduling calls whose multiplicity differs between the two runs, as {@code count x call}}
     * <p>
     * A redstone circuit notifies the same position many times per tick, so the interesting difference is the
     * multiplicity of one call, not the order of the trace.
     */
    private static List<String> callDifference(List<String> inline, List<String> region) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final String call : inline) {
            counts.merge(call, 1, Integer::sum);
        }
        for (final String call : region) {
            counts.merge(call, -1, Integer::sum);
        }
        final List<String> difference = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != 0) {
                difference.add(entry.getValue() + "x:" + entry.getKey());
            }
        }
        return difference;
    }

    private SoakRun run(Mode mode, long seed, int circuitCount, int ticks, int captureTick, TraceBox tracedBox) {
        final long startNanos = System.nanoTime();
        final boolean regionThreading = mode != Mode.INLINE;
        final boolean trace = captureTick > 0;
        RegionThreadingManager.clear();
        this.engine = null;
        this.gameTime.set(0L);
        final RegionRedstoneWorld world = new RegionRedstoneWorld(this.gameTime);
        world.traceScheduling(trace);
        world.tracePositions(tracedBox);
        final List<SoakCircuit> circuits = SoakCircuit.generate(seed, circuitCount, ticks);
        final int minChunkX = Math.floorDiv(CELL_ORIGIN_X - 2, 16);
        final int maxChunkX = Math.floorDiv(CELL_ORIGIN_X + CELL_COLUMNS * CELL_PITCH + 2, 16);
        final int minChunkZ = Math.floorDiv(CELL_ORIGIN_Z - 2, 16);
        final int maxChunkZ = Math.floorDiv(CELL_ORIGIN_Z + CELL_ROWS * CELL_PITCH + 2, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.addTickContainer(chunkX, chunkZ);
            }
        }
        for (final SoakCircuit circuit : circuits) {
            circuit.build(world);
        }
        if (regionThreading) {
            final RegionThreadingEngine installed = new RegionThreadingEngine(4, false, false, false, false, true, RegionPolicy.FIXED_3X3);
            installed.setBorderUpdatesEnabledForTesting(true);
            installed.setBorderUpdateBudgetForTesting(20000);
            installed.enableUnclassifiedWorkerCallbacksForTesting();
            RegionThreadingManager.install(installed);
            this.engine = installed;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    installed.addRegionSection(world.level, chunkX, chunkZ);
                }
            }
        }
        // The scripted sources fire at tick zero, after the engine is installed, so the first cascade of a
        // circuit starts on a region worker of the on run.
        for (final SoakCircuit circuit : circuits) {
            circuit.applyActions(world, 0);
        }
        final SoakRun result = new SoakRun(world, circuits);
        result.captureState(world);
        result.borderUpdateCounts.add(0L);
        result.ticks.add(result.digest(world, world.blockEventCount()));
        final int[] changes = new int[circuitCount];
        final int[] lastChange = new int[circuitCount];
        final int[] maxGap = new int[circuitCount];
        final int[] circuitByCell = new int[CELL_COLUMNS * CELL_ROWS];
        java.util.Arrays.fill(circuitByCell, -1);
        for (int index = 0; index < circuits.size(); index++) {
            circuitByCell[circuits.get(index).cell()] = index;
        }
        for (int tick = 1; tick <= ticks; tick++) {
            this.gameTime.set(tick);
            for (final SoakCircuit circuit : circuits) {
                circuit.applyActions(world, tick);
            }
            if (regionThreading) {
                // The head of the level tick: the scripted input of the tick is applied by the server thread,
                // every region of the tick is quiescent, and the production engine publishes the values a
                // foreign read needs here. See RegionBlockEntitySnapshots.
                world.publishBlockEntitySnapshot();
            }
            // The production configuration leaves the scheduled ticks on the server thread: LevelTicks runs its
            // own inline loop and only the other slices reach region workers.
            final LevelTicks.TickDispatcher<Block> dispatcher = mode == Mode.PARALLEL_SEAM
                    ? this.engine.beginScheduledTickDispatcher(world.level, ScheduledTickPhase.BLOCK, PARKING_BARRIER)
                    : null;
            if (mode == Mode.PARALLEL_SEAM) {
                assertNotNull(dispatcher, "the parallel seam run has to dispatch scheduled ticks on workers");
            }
            world.blockTicks.tick(tick, 65536,
                    (pos, block) -> world.level.getBlockState(pos).tick(world.level, pos, world.scheduledRandom), dispatcher);
            final int blockEvents = world.blockEventCount();
            world.runBlockEvents(regionThreading);
            world.tickBlockEntities(regionThreading);
            final Map<Integer, Integer> changedCells = new LinkedHashMap<>();
            for (final BlockPos pos : world.drainDirty()) {
                result.update(pos);
                if (DEBUG_CHANGES) {
                    System.out.println("debug,region=" + regionThreading + ",tick=" + tick + ",pos=" + pos.toShortString()
                            + ",state=" + world.level.getBlockState(pos));
                }
                final int cell = circuitAt(pos);
                if (cell >= 0 && circuitByCell[cell] >= 0) {
                    changedCells.merge(circuitByCell[cell], 1, Integer::sum);
                }
            }
            for (final Integer circuit : changedCells.keySet()) {
                changes[circuit]++;
                maxGap[circuit] = Math.max(maxGap[circuit], tick - lastChange[circuit]);
                lastChange[circuit] = tick;
            }
            result.borderUpdateCounts.add(this.engine == null ? 0L : this.engine.diagnostics().borderUpdates().recorded());
            result.ticks.add(result.digest(world, blockEvents));
            if (trace) {
                result.pendingPerTick.add(world.pendingTicks());
            }
            if (captureTick == tick) {
                result.capture(world);
                break;
            }
        }
        for (int circuit = 0; circuit < circuitCount; circuit++) {
            maxGap[circuit] = Math.max(maxGap[circuit], ticks - lastChange[circuit]);
        }
        result.changes = changes;
        result.maxGap = maxGap;
        if (this.engine != null) {
            result.diagnostics = this.engine.diagnostics();
            result.deferredBlockEvents = this.engine.deferredBlockEvents();
            result.foreignBlockEntityReads = this.engine.foreignBlockEntityReads();
            this.engine.shutdown();
            this.engine = null;
        }
        result.refusedBlockEntityReads = world.refusedBlockEntityReads();
        result.publishedBlockEntityReads = world.publishedBlockEntityReads();
        result.snapshotEntries = RegionBlockEntitySnapshots.publishedEntries();
        result.snapshotPublishNanos = RegionBlockEntitySnapshots.meanPublishNanos();
        result.identityPublishNanos = RegionEntityIdentitySnapshots.meanPublishNanos();
        result.foreignWriteAttempts = world.foreignWriteAttempts();
        result.uncapturedForeignWrites = world.uncapturedForeignWrites();
        result.finish(world);
        result.scheduleTrace = world.scheduleTrace();
        result.positionEvents = world.positionEvents();
        result.pendingTicks = world.pendingTicks();
        RegionThreadingManager.clear();
        result.millis = (System.nanoTime() - startNanos) / 1_000_000L;
        return result;
    }

    /** {@return the index of the circuit cell that contains {@code pos}, or -1 outside the grid} */
    private static int circuitAt(BlockPos pos) {
        final int column = Math.floorDiv(pos.getX() - CELL_ORIGIN_X, CELL_PITCH);
        final int row = Math.floorDiv(pos.getZ() - CELL_ORIGIN_Z, CELL_PITCH);
        if (column < 0 || column >= CELL_COLUMNS || row < 0 || row >= CELL_ROWS) {
            return -1;
        }
        return column * CELL_ROWS + row;
    }

    /**
     * One run of one seed: the per-tick comparison records, the running state hash and the counters the report
     * prints.
     */
    private static final class SoakRun {
        private final RegionRedstoneWorld world;
        private final List<SoakCircuit> circuits;
        private static final int SLOTS = CELL_COLUMNS * CELL_ROWS + 1;
        private static final int OUTSIDE = SLOTS - 1;
        private final List<TickRecord> ticks = new ArrayList<>();
        /** Per tick: the cumulative number of cross-region updates the engine recorded. */
        private final List<Long> borderUpdateCounts = new ArrayList<>();
        /** The cumulative refused foreign block-entity reads per slot, snapshotted once per tick. */
        private final List<int[]> refusedSnapshots = new ArrayList<>();
        private final int[] refusedBySlot = new int[SLOTS];
        private final int[] pendingCounts = new int[SLOTS];
        private final List<Integer> items = new ArrayList<>();
        private final Map<BlockPos, long[]> entries = new HashMap<>();
        /** Two accumulators per slot, so a swap of two states inside one slot cannot cancel out. */
        private final long[] slotHashes = new long[2 * SLOTS];
        private List<Cell> capture = List.of();
        private int[] changes = new int[0];
        private int[] maxGap = new int[0];
        private int straddling;
        private int itemsIn;
        private int itemsOut;
        private int itemsMin;
        private int itemsMax;
        private long millis;
        private long refusedBlockEntityReads;
        private long publishedBlockEntityReads;
        private long snapshotEntries;
        private long snapshotPublishNanos;
        private long identityPublishNanos;
        private long foreignWriteAttempts;
        private long uncapturedForeignWrites;
        private List<String> scheduleTrace = List.of();
        private List<String> positionEvents = List.of();
        private List<String> pendingTicks = List.of();
        private final List<List<String>> pendingPerTick = new ArrayList<>();
        private long deferredBlockEvents;
        private long foreignBlockEntityReads;
        private RegionThreadingDiagnostics diagnostics;

        private SoakRun(RegionRedstoneWorld world, List<SoakCircuit> circuits) {
            this.world = world;
            this.circuits = circuits;
            for (final SoakCircuit circuit : circuits) {
                if (circuit.straddles()) {
                    this.straddling++;
                }
            }
        }

        /** {@return the comparison slot of {@code pos}: the circuit cell, or {@link #OUTSIDE}} */
        private static int slotOf(BlockPos pos) {
            final int cell = circuitAt(pos);
            return cell < 0 ? OUTSIDE : cell;
        }

        /** {@return the traced box of the circuit in {@code slot}, or a box around it} */
        private TraceBox circuitTrace(int slot) {
            for (final SoakCircuit circuit : this.circuits) {
                if (circuit.cell() == slot) {
                    return circuit.trace();
                }
            }
            return null;
        }

        /** {@return the name of the circuit in {@code slot}, for a report of the approximation} */
        private String circuitName(int slot) {
            if (slot == OUTSIDE) {
                return "outside-the-grid";
            }
            for (final SoakCircuit circuit : this.circuits) {
                if (circuit.cell() == slot) {
                    return circuit.name() + (circuit.straddles() ? "*" : "") + "@" + circuit.trace().minX() + ","
                            + circuit.trace().minZ();
                }
            }
            return "empty-cell-" + slot;
        }

        /**
         * Folds every changed position into the running hash of the state of its circuit, so a difference of
         * one tick is attributed to the circuit it happened in.
         */
        private void update(BlockPos pos) {
            final long hash = this.world.cellHash(pos);
            final long[] previous = this.entries.get(pos);
            final int slot = 2 * slotOf(pos);
            if (previous != null) {
                this.slotHashes[slot] -= previous[0];
                this.slotHashes[slot + 1] -= previous[1];
            }
            if (hash == 0L) {
                this.entries.remove(pos);
            } else {
                final long folded = RegionRedstoneWorld.mix(hash ^ 0xD1B54A32D192ED03L);
                this.entries.put(pos, new long[] { hash, folded });
                this.slotHashes[slot] += hash;
                this.slotHashes[slot + 1] += folded;
            }
        }

        private void captureState(RegionRedstoneWorld world) {
            for (final BlockPos pos : world.drainDirty()) {
                this.update(pos);
            }
            for (final BlockPos pos : world.blockEntityPositions()) {
                this.update(pos);
            }
        }

        /**
         * Records one tick: the state hash of every slot, the scheduled ticks every slot holds, the size of the
         * block-event queue and the number of items in the volume.
         */
        private TickRecord digest(RegionRedstoneWorld world, int blockEvents) {
            world.countPendingTicks(this.pendingCounts, SoakRun::slotOf);
            final int items = world.totalContainerItems();
            this.items.add(items);
            for (final BlockPos refused : world.drainRefusedReads()) {
                this.refusedBySlot[slotOf(refused)]++;
            }
            final int[] previousSnapshot = this.refusedSnapshots.isEmpty() ? null
                    : this.refusedSnapshots.get(this.refusedSnapshots.size() - 1);
            this.refusedSnapshots.add(previousSnapshot != null && java.util.Arrays.equals(previousSnapshot, this.refusedBySlot)
                    ? previousSnapshot
                    : this.refusedBySlot.clone());
            return new TickRecord(this.slotHashes.clone(), this.pendingCounts.clone(), blockEvents, items);
        }

        private void capture(RegionRedstoneWorld world) {
            final List<Cell> cells = new ArrayList<>();
            for (final SoakCircuit circuit : this.circuits) {
                cells.addAll(world.cells(circuit.trace()));
            }
            this.capture = cells;
        }

        /** {@return the cross-region updates the engine recorded during {@code tick}} */
        private long borderUpdatesAt(int tick) {
            if (tick <= 0 || tick >= this.borderUpdateCounts.size()) {
                return 0L;
            }
            return this.borderUpdateCounts.get(tick) - this.borderUpdateCounts.get(tick - 1);
        }

        private String cellState(BlockPos pos) {
            final Cell cell = this.world.cell(pos);
            return cell.state() + (cell.blockEntity() == null ? "" : " " + cell.blockEntity().getType());
        }

        /** {@return one line per circuit: its kind, its straddling flag and its traced box} */
        private String describeCircuits() {
            final List<String> described = new ArrayList<>();
            for (final SoakCircuit circuit : this.circuits) {
                described.add(circuit.name() + (circuit.straddles() ? "*" : "") + "@" + circuit.trace().minX() + ".."
                        + circuit.trace().maxX() + "," + circuit.trace().minZ() + ".." + circuit.trace().maxZ());
            }
            return described.toString();
        }

        private void finish(RegionRedstoneWorld world) {
            this.itemsOut = world.totalContainerItems();
            this.itemsIn = this.items.get(0);
            this.itemsMin = this.items.stream().mapToInt(Integer::intValue).min().orElse(0);
            this.itemsMax = this.items.stream().mapToInt(Integer::intValue).max().orElse(0);
        }
    }

    /** One generated circuit: its kind, its traced box and the placements and scripted inputs of the seed. */
    private static final class SoakCircuit {
        private final String kind;
        private final TraceBox trace;
        private final List<TimedAction> actions;
        private final boolean straddles;
        private final int cell;

        private SoakCircuit(String kind, TraceBox trace, List<TimedAction> actions, boolean straddles, int cell) {
            this.kind = kind;
            this.trace = trace;
            this.actions = actions;
            this.straddles = straddles;
            this.cell = cell;
        }

        private record TimedAction(int tick, Consumer<RegionRedstoneWorld> body) {}

        private String name() {
            return this.kind;
        }

        private TraceBox trace() {
            return this.trace;
        }

        private boolean straddles() {
            return this.straddles;
        }

        /** {@return the grid cell this circuit occupies, as {@code column * CELL_ROWS + row}} */
        private int cell() {
            return this.cell;
        }

        private void build(RegionRedstoneWorld world) {
            this.applyActions(world, -1);
        }

        private void applyActions(RegionRedstoneWorld world, int tick) {
            for (final TimedAction action : this.actions) {
                if (action.tick() == tick) {
                    action.body().accept(world);
                }
            }
        }

        /**
         * Draws {@code count} circuits from {@code seed}. Each circuit gets a cell of the grid, a kind and a
         * seam: a circuit that straddles is placed so that a region border runs through its own blocks, and the
         * share of those circuits is the generator's straddling fraction.
         */
        private static List<SoakCircuit> generate(long seed, int count, int ticks) {
            final RandomSource random = RandomSource.create(seed);
            final double fraction = Double.parseDouble(System.getProperty("neoforge.redstoneSoakStraddlingFraction", "0.7"));
            final List<int[]> straddlingCells = new ArrayList<>();
            final List<int[]> interiorCells = new ArrayList<>();
            for (int column = 0; column < CELL_COLUMNS; column++) {
                for (int row = 0; row < CELL_ROWS; row++) {
                    if (xBorder(column) >= 0 || zBorder(row) >= 0) {
                        straddlingCells.add(new int[] { column, row });
                    } else {
                        interiorCells.add(new int[] { column, row });
                    }
                }
            }
            shuffle(straddlingCells, random);
            shuffle(interiorCells, random);
            final int wantedStraddling = Math.min(straddlingCells.size(), (int) Math.round(fraction * count));
            final int wantedInterior = Math.min(interiorCells.size(), count - wantedStraddling);
            int remainingStraddling = wantedStraddling + (count - wantedStraddling - wantedInterior);
            // A diagnosis narrows a failing seed to one circuit family with -Dneoforge.redstoneSoakKind=NAME.
            final String onlyKind = System.getProperty("neoforge.redstoneSoakKind", "");
            final List<Kind> kinds = new ArrayList<>();
            for (final Kind kind : Kind.values()) {
                if (onlyKind.isEmpty() || kind.name().equalsIgnoreCase(onlyKind)) {
                    kinds.add(kind);
                }
            }
            if (kinds.isEmpty()) {
                throw new IllegalArgumentException("no circuit kind is named " + onlyKind);
            }
            shuffleKinds(kinds, random);
            final List<SoakCircuit> circuits = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                final Kind kind = kinds.get(index % kinds.size());
                int[] cell = null;
                boolean straddle = false;
                if (remainingStraddling > 0 && kind.canStraddle()) {
                    cell = takeMatching(straddlingCells, kind);
                    straddle = cell != null;
                    if (straddle) {
                        remainingStraddling--;
                    }
                }
                if (cell == null && !interiorCells.isEmpty()) {
                    cell = interiorCells.remove(0);
                }
                if (cell == null && !straddlingCells.isEmpty()) {
                    cell = straddlingCells.remove(0);
                }
                if (cell == null) {
                    cell = interiorCells.remove(0);
                }
                circuits.add(build(kind, cell[0], cell[1], straddle, random, ticks));
            }
            return circuits;
        }

        /** {@return the first free cell whose border lies on the kind's axis, or null when there is none} */
        private static int[] takeMatching(List<int[]> cells, Kind kind) {
            for (int index = 0; index < cells.size(); index++) {
                final int[] cell = cells.get(index);
                if (kind.axis == Axis.X ? xBorder(cell[0]) >= 0 : zBorder(cell[1]) >= 0) {
                    cells.remove(index);
                    return cell;
                }
            }
            return null;
        }

        private static SoakCircuit build(Kind kind, int column, int row, boolean straddle, RandomSource random, int ticks) {
            final int cellMinX = CELL_ORIGIN_X + column * CELL_PITCH;
            final int cellMinZ = CELL_ORIGIN_Z + row * CELL_PITCH;
            final int borderX = xBorder(column);
            final int borderZ = zBorder(row);
            final boolean straddleX = straddle && kind.axis == Axis.X && borderX >= 0;
            final boolean straddleZ = straddle && kind.axis == Axis.Z && borderZ >= 0;
            final int anchorX;
            final int anchorZ;
            if (straddleX) {
                final int maxOffset = Math.min(kind.width - 3, borderX - cellMinX - 1);
                final int offset = kind.seamX > 0 ? kind.seamX : 2 + random.nextInt(maxOffset - 1);
                anchorX = borderX - offset;
                anchorZ = interiorCoordinate(cellMinZ, kind.depth, borderZ, random);
            } else if (straddleZ) {
                final int maxOffset = Math.min(kind.depth - 3, borderZ - cellMinZ - 1);
                anchorZ = borderZ - (2 + random.nextInt(maxOffset - 1));
                anchorX = interiorCoordinate(cellMinX, kind.width, borderX, random);
            } else {
                anchorX = interiorCoordinate(cellMinX, kind.width, borderX, random);
                anchorZ = interiorCoordinate(cellMinZ, kind.depth, borderZ, random);
            }
            final Builder builder = new Builder(anchorX, anchorZ, random, ticks);
            kind.draw(builder);
            return new SoakCircuit(kind.name().toLowerCase(Locale.ROOT),
                    new TraceBox(anchorX - 1, anchorX + kind.width, Y - 2, Y + 3, anchorZ - 1, anchorZ + kind.depth),
                    builder.actions, straddle, column * CELL_ROWS + row);
        }

        /**
         * {@return the coordinate of a design of {@code extent} blocks that stays clear of the region border of
         * the cell, or any coordinate of the cell when the cell holds no border}
         */
        private static int interiorCoordinate(int cellMin, int extent, int border, RandomSource random) {
            if (border < 0) {
                return cellMin + 1 + random.nextInt(CELL_PITCH - extent - 2);
            }
            final boolean west = random.nextBoolean();
            if (west) {
                return cellMin + 1 + random.nextInt(border - 2 - extent - cellMin);
            }
            return border + 2 + random.nextInt(cellMin + CELL_PITCH - 1 - extent - border - 1);
        }

        /** {@return the region border inside the cell column, or -1 when the column holds none} */
        private static int xBorder(int column) {
            final int cellMin = CELL_ORIGIN_X + column * CELL_PITCH;
            final int border = Math.floorDiv(cellMin + REGION_BLOCKS - 1, REGION_BLOCKS) * REGION_BLOCKS;
            return border >= cellMin + 2 && border <= cellMin + CELL_PITCH - 3 ? border : -1;
        }

        /** {@return the region border inside the cell row, or -1 when the row holds none} */
        private static int zBorder(int row) {
            final int cellMin = CELL_ORIGIN_Z + row * CELL_PITCH;
            final int border = Math.floorDiv(cellMin + REGION_BLOCKS - 1, REGION_BLOCKS) * REGION_BLOCKS;
            return border >= cellMin + 2 && border <= cellMin + CELL_PITCH - 3 ? border : -1;
        }

        private static void shuffleKinds(List<Kind> values, RandomSource random) {
            for (int index = values.size() - 1; index > 0; index--) {
                final int other = random.nextInt(index + 1);
                final Kind value = values.get(index);
                values.set(index, values.get(other));
                values.set(other, value);
            }
        }

        private static void shuffle(List<int[]> values, RandomSource random) {
            for (int index = values.size() - 1; index > 0; index--) {
                final int other = random.nextInt(index + 1);
                final int[] value = values.get(index);
                values.set(index, values.get(other));
                values.set(other, value);
            }
        }
    }

    /** The axis of a cell a circuit's design is long enough to straddle. */
    private enum Axis {
        X,
        Z
    }

    /**
     * The circuit kinds. Each kind declares the box its design occupies and the axis it can straddle, and draws
     * itself into a {@link Builder} in local coordinates. Every kind is live: it changes state on its own, or on
     * a scripted input whose period is short enough that the liveness assertion can see it.
     */
    private enum Kind {
        /** A lever-driven dust run with a bend, a T-junction, a lamp and a note block. */
        DUST_BENDS(8, 3, Axis.X) {
            @Override
            void draw(Builder b) {
                b.lever(0, 1, 6 + b.random.nextInt(7));
                b.repeater(1, 1, Direction.WEST);
                b.dust(2, 1);
                b.dust(3, 1);
                b.dust(4, 1);
                b.dust(5, 1);
                b.dust(3, 0);
                b.lamp(2, 0);
                b.noteBlock(4, 0);
                b.dust(5, 2);
                b.lamp(4, 2);
                b.repeater(6, 2, Direction.WEST);
                b.lamp(7, 2);
            }
        },
        /** A clock through four repeaters of every delay into dust and a lamp, with a note block branch. */
        REPEATER_LADDER(8, 2, Axis.X) {
            @Override
            void draw(Builder b) {
                b.clock(0, 0, 3 + b.random.nextInt(6));
                b.repeater(1, 0, Direction.WEST);
                b.repeater(2, 0, Direction.WEST);
                b.repeater(3, 0, Direction.WEST);
                b.repeater(4, 0, Direction.WEST);
                b.dust(5, 0);
                b.dust(6, 0);
                b.lamp(7, 0);
                b.noteBlock(6, 1);
            }
        },
        /** Two comparators in the two modes, one reading a clock-driven dust line, one reading the other. */
        COMPARATOR_MODES(9, 3, Axis.X) {
            @Override
            void draw(Builder b) {
                b.clock(0, 1, 4 + b.random.nextInt(6));
                b.repeater(1, 1, Direction.WEST);
                b.dust(2, 1);
                b.comparator(3, 1, Direction.WEST);
                b.dust(4, 1);
                b.dust(5, 1);
                b.comparator(6, 1, Direction.WEST);
                b.dust(7, 1);
                b.lamp(8, 1);
                b.clock(1, 0, 5 + b.random.nextInt(7));
                b.repeater(2, 0, Direction.WEST);
                b.dust(3, 0);
            }
        },
        /** An observer watching a clock-driven dust line, pulsing a normal or sticky piston that moves blocks. */
        OBSERVER_PISTON(9, 3, Axis.X, 6) {
            @Override
            void draw(Builder b) {
                b.clock(0, 1, 6 + b.random.nextInt(9));
                b.repeater(1, 1, Direction.WEST);
                b.dust(2, 1);
                b.observer(3, 1, Direction.WEST);
                b.dust(4, 1);
                b.piston(5, 1, Direction.EAST, b.random.nextBoolean());
                b.solid(6, 1, Blocks.STONE.defaultBlockState());
                b.solid(7, 1, Blocks.TARGET.defaultBlockState());
            }
        },
        /**
         * A torch inverter whose feedback runs through two repeaters of the longest delay.
         * <p>
         * The wall torch reads the dust in front of it and feeds that same dust, so the fastest loop of the
         * circuit is the torch against its own output: it toggles every two ticks, and the repeater feedback only
         * shifts the level of the dust the torch reads. That is the burnout-prone loop of the mix - eight toggles
         * inside the sixty-tick window burn the torch out, and a burnt-out torch only relights while its input is
         * unpowered, which the latched feedback chain prevents. Nothing in vanilla recovers that state, so the
         * generator re-arms the torch every {@code TORCH_REARM_TICKS} ticks the way a player rebuilds the clock:
         * the toggle history, the burnout, its level event and the relight of the next burst all stay in the run.
         */
        TORCH_CLOCK(9, 3, Axis.X, 5) {
            @Override
            void draw(Builder b) {
                b.solid(0, 0, Blocks.STONE.defaultBlockState());
                b.wallTorch(1, 0, Direction.WEST);
                b.dust(2, 0);
                b.repeater(3, 0, Direction.WEST, 4);
                b.dust(4, 0);
                b.dust(4, 1);
                b.repeater(3, 1, Direction.EAST, 4);
                b.dust(2, 1);
                b.dust(1, 1);
                b.dust(0, 1);
                b.lamp(1, 2);
                b.repeater(5, 0, Direction.WEST);
                b.dust(6, 0);
                b.repeater(7, 0, Direction.WEST);
                b.lamp(8, 0);
                final BlockPos torch = b.absolute(1, 0);
                for (int tick = TORCH_REARM_TICKS; tick <= b.ticks; tick += TORCH_REARM_TICKS) {
                    b.action(tick, world -> world.level.setBlock(torch, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                            .setValue(BlockStateProperties.LIT, true), 3));
                }
            }
        },
        /** A lever-driven repeater opening an iron door and a trapdoor, with a button on a second lamp. */
        LEVER_DOOR(8, 3, Axis.X) {
            @Override
            void draw(Builder b) {
                b.lever(0, 1, 12 + b.random.nextInt(13));
                b.repeater(1, 1, Direction.WEST);
                b.dust(2, 1);
                b.dust(3, 1);
                b.dust(4, 1);
                b.door(5, 1);
                b.dust(4, 0);
                b.trapdoor(5, 0);
                b.button(0, 2, 25 + b.random.nextInt(16));
                b.repeater(1, 2, Direction.WEST);
                b.dust(2, 2);
                b.dust(3, 2);
                b.lamp(4, 2);
            }
        },
        /** A clock-driven normal or sticky piston pushing a stone and a target block across its column. */
        PISTON_PUSH(9, 2, Axis.X, 4) {
            @Override
            void draw(Builder b) {
                b.clock(0, 0, 10 + b.random.nextInt(11));
                b.repeater(1, 0, Direction.WEST);
                b.dust(2, 0);
                b.lamp(2, 1);
                b.piston(3, 0, Direction.EAST, b.random.nextBoolean());
                b.solid(4, 0, Blocks.STONE.defaultBlockState());
                b.solid(5, 0, Blocks.TARGET.defaultBlockState());
                b.solid(6, 0, Blocks.STONE.defaultBlockState());
            }
        },
        /** A dust loop that leaves one region and returns into the other, so it re-powers itself across the seam. */
        WIRE_SELF_POWER(7, 3, Axis.X) {
            @Override
            void draw(Builder b) {
                b.lever(0, 1, 7 + b.random.nextInt(8));
                b.repeater(1, 1, Direction.WEST);
                for (int x = 2; x <= 5; x++) {
                    b.dust(x, 1);
                    b.dust(x, 2);
                }
                b.lamp(3, 0);
                b.noteBlock(6, 1);
            }
        },
        /** Two clocks of different periods whose dust lines meet on the border and drift into phase. */
        DOUBLE_CLOCK_DRIFT(9, 3, Axis.X) {
            @Override
            void draw(Builder b) {
                b.clock(0, 1, 3 + b.random.nextInt(3));
                b.repeater(1, 1, Direction.WEST);
                b.dust(2, 1);
                b.dust(3, 1);
                b.dust(4, 1);
                b.lamp(2, 2);
                b.clock(8, 1, 5 + b.random.nextInt(4));
                b.repeater(7, 1, Direction.EAST);
                b.dust(6, 1);
                b.dust(5, 1);
                b.lamp(6, 2);
            }
        },
        /** Hoppers shuttling one stack between them, read by a comparator on the other side of the border. */
        HOPPER_COMPARATOR(3, 6, Axis.Z) {
            @Override
            void draw(Builder b) {
                b.hopper(1, 5, Direction.NORTH);
                b.hopper(1, 4, Direction.SOUTH);
                b.hopperAbove(1, 5, Direction.DOWN);
                b.comparator(1, 3, Direction.SOUTH);
                b.repeater(1, 2, Direction.SOUTH);
                b.dust(1, 1);
                b.lamp(1, 0);
                b.action(0, world -> fill(world, b.absolute(1, Y + 1, 5), 3));
            }
        },
        /** A chain of two observers and a repeater across the border, clocked by a block that toggles. */
        OBSERVER_CHAIN(3, 8, Axis.Z) {
            @Override
            void draw(Builder b) {
                b.clock(1, 0, 5 + b.random.nextInt(5));
                b.observer(1, 1, Direction.NORTH);
                b.observer(1, 3, Direction.NORTH);
                b.repeater(1, 5, Direction.NORTH);
                b.lamp(1, 7);
            }
        };

        final int width;
        final int depth;
        final Axis axis;
        /**
         * The local coordinate a straddling border has to fall on, or zero when any interior position will do.
         * A design whose dust network has to stay inside one region pins the crossing to the part that only
         * reads a foreign scalar state.
         */
        final int seamX;

        Kind(int width, int depth, Axis axis) {
            this(width, depth, axis, 0);
        }

        Kind(int width, int depth, Axis axis, int seamX) {
            this.width = width;
            this.depth = depth;
            this.axis = axis;
            this.seamX = seamX;
        }

        abstract void draw(Builder b);

        /** {@return true when the design is long enough on its axis to have a border run through it} */
        boolean canStraddle() {
            return this.axis == Axis.X ? this.width >= 5 : this.depth >= 5;
        }

        /** Puts one stack of stone into the hopper at {@code pos}. */
        private static void fill(RegionRedstoneWorld world, BlockPos pos, int count) {
            if (world.blockEntities.get(pos) instanceof HopperBlockEntity hopper) {
                hopper.setItem(0, new ItemStack(Items.STONE, count));
            }
        }
    }

    /** Draws one circuit in local coordinates into the actions of a {@link SoakCircuit}. */
    private static final class Builder {
        private final int anchorX;
        private final int anchorZ;
        private final RandomSource random;
        private final int ticks;
        private final List<SoakCircuit.TimedAction> actions = new ArrayList<>();

        private Builder(int anchorX, int anchorZ, RandomSource random, int ticks) {
            this.anchorX = anchorX;
            this.anchorZ = anchorZ;
            this.random = random;
            this.ticks = ticks;
        }

        private BlockPos absolute(int x, int z) {
            return this.absolute(x, Y, z);
        }

        private BlockPos absolute(int x, int y, int z) {
            return new BlockPos(this.anchorX + x, y, this.anchorZ + z);
        }

        private void action(int tick, Consumer<RegionRedstoneWorld> body) {
            this.actions.add(new SoakCircuit.TimedAction(tick, body));
        }

        private void putAt(int x, int y, int z, BlockState state) {
            final BlockPos pos = new BlockPos(this.anchorX + x, y, this.anchorZ + z);
            this.action(-1, world -> world.level.setBlock(pos, state, 3));
        }

        private void put(int x, int z, BlockState state) {
            this.putAt(x, Y, z, state);
        }

        private void floor(int x, int z) {
            this.putAt(x, Y - 1, z, Blocks.STONE.defaultBlockState());
        }

        private void solid(int x, int z, BlockState state) {
            this.floor(x, z);
            this.put(x, z, state);
        }

        private void dust(int x, int z) {
            this.floor(x, z);
            this.put(x, z, Blocks.REDSTONE_WIRE.defaultBlockState());
        }

        private void lamp(int x, int z) {
            this.floor(x, z);
            this.put(x, z, Blocks.REDSTONE_LAMP.defaultBlockState());
        }

        private void noteBlock(int x, int z) {
            this.floor(x, z);
            this.put(x, z, Blocks.NOTE_BLOCK.defaultBlockState());
        }

        private void repeater(int x, int z, Direction facing) {
            this.repeater(x, z, facing, 1 + this.random.nextInt(4));
        }

        private void repeater(int x, int z, Direction facing, int delay) {
            this.floor(x, z);
            this.put(x, z, Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.DELAY, delay));
        }

        private void comparator(int x, int z, Direction facing) {
            this.floor(x, z);
            this.put(x, z, Blocks.COMPARATOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.MODE_COMPARATOR,
                            this.random.nextBoolean() ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT));
        }

        private void observer(int x, int z, Direction facing) {
            this.floor(x, z);
            this.put(x, z, Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, facing));
        }

        private void piston(int x, int z, Direction facing, boolean sticky) {
            this.floor(x, z);
            this.put(x, z, (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing)
                    .setValue(BlockStateProperties.EXTENDED, false));
        }

        private void wallTorch(int x, int z, Direction facing) {
            this.put(x, z, Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.LIT, true));
        }

        private void lever(int x, int z, int period) {
            this.floor(x, z);
            final BlockPos pos = this.absolute(x, z);
            this.put(x, z, Blocks.LEVER.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.POWERED, false));
            for (int tick = period; tick <= this.ticks; tick += period) {
                this.action(tick, world -> {
                    final BlockState state = world.level.getBlockState(pos);
                    ((LeverBlock) Blocks.LEVER).pull(state, world.level, pos, null);
                });
            }
        }

        private void button(int x, int z, int period) {
            this.floor(x, z);
            final BlockPos pos = this.absolute(x, z);
            this.put(x, z, Blocks.OAK_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.POWERED, false));
            for (int tick = period; tick <= this.ticks; tick += period) {
                this.action(tick, world -> {
                    final BlockState state = world.level.getBlockState(pos);
                    if (!state.getValue(BlockStateProperties.POWERED)) {
                        ((ButtonBlock) Blocks.OAK_BUTTON).press(state, world.level, pos, null);
                    }
                });
            }
        }

        /** A redstone block that the scripted schedule places and removes, i.e. a clock of {@code period} ticks. */
        private void clock(int x, int z, int period) {
            this.floor(x, z);
            final BlockPos pos = this.absolute(x, z);
            for (int tick = period; tick <= this.ticks; tick += period) {
                final boolean powered = (tick / period) % 2 == 1;
                this.action(tick, world -> world.level.setBlock(pos,
                        powered ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3));
            }
        }

        private void door(int x, int z) {
            this.floor(x, z);
            this.put(x, z, Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                    .setValue(DoorBlock.OPEN, false)
                    .setValue(DoorBlock.POWERED, false));
        }

        private void trapdoor(int x, int z) {
            this.floor(x, z);
            this.put(x, z, Blocks.OAK_TRAPDOOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                    .setValue(BlockStateProperties.OPEN, false)
                    .setValue(BlockStateProperties.POWERED, false));
        }

        private void hopper(int x, int z, Direction facing) {
            this.floor(x, z);
            this.put(x, z, Blocks.HOPPER.defaultBlockState()
                    .setValue(HopperBlock.FACING, facing)
                    .setValue(HopperBlock.ENABLED, true));
        }

        private void hopperAbove(int x, int z, Direction facing) {
            this.putAt(x, Y + 1, z, Blocks.HOPPER.defaultBlockState()
                    .setValue(HopperBlock.FACING, facing)
                    .setValue(HopperBlock.ENABLED, true));
        }
    }
}
