/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.ScheduledTickPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behavioral equivalence harness for every redstone component laid across region borders.
 * <p>
 * Each layout is built from real vanilla blocks: dust, redstone torches, repeaters, comparators, observers,
 * pistons (including sticky), redstone lamps, note blocks, powered rails, levers and buttons. The layout is
 * driven by real scheduled block ticks, real neighbor, shape and comparator updates and the vanilla block event
 * phase; the level is a mock that runs the same write, signal, block-entity and block-event hooks the
 * production level runs. The scheduled ticks of the "on" run execute on region workers, and the border capture
 * and its same-tick fixpoint are enabled, so every cross-region update the components produce is exercised.
 * <p>
 * Every run is compared tick by tick: block state and block-entity state of a box that contains the whole
 * circuit, plus the first tick each of those states appeared. A difference in the tick a state appears fails
 * the test, not only a difference in the final state. The layouts are deterministic from a seed and the
 * failure message names the layout, the seed and the first differing tick.
 * <p>
 * The circuit is driven by a component tick, not by the test thread, whenever a component under test has a
 * scheduled tick: a repeater, torch, comparator or observer is placed between the source and the component, so
 * the cascade that crosses the border starts inside a region group and its cross-region updates go through the
 * border capture and the fixpoint.
 */
@Timeout(300)
class RegionRedstoneComponentEquivalenceTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;
    private static final int BORDER_X = REGION_CHUNK_SIZE * 16;
    private static final int Y = 64;
    private static final int Z = 1;
    private static final int TICKS = 60;

    private RegionThreadingEngine engine;
    private final AtomicLong gameTime = new AtomicLong();
    private String lastOnDiagnostics = "";

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void dustLinesOfEveryLengthCrossTheBorder() {
        for (int length = 1; length <= 15; length++) {
            final long seed = 0xD0057L + length;
            this.assertEquivalent(Layout.dustLine(seed, 1, length), seed);
        }
    }

    @Test
    void everyBorderOffsetCarriesTheComponentFamilies() {
        for (int offset = 1; offset <= 15; offset++) {
            final long seed = 0xC0FFEEL + offset;
            this.assertEquivalent(Layout.mixedChain(seed, offset), seed);
        }
    }

    @Test
    void torchAndObserverPulsesCrossTheBorder() {
        for (int offset : new int[] { 1, 4, 7, 12, 15 }) {
            final long seed = 0x70C4L + offset;
            this.assertEquivalent(Layout.torchObserver(seed, offset), seed);
        }
    }

    @Test
    void pistonMovesBlocksAcrossTheBorder() {
        for (int offset : new int[] { 1, 3, 8, 14 }) {
            for (boolean sticky : new boolean[] { false, true }) {
                final long seed = 0x91570L + offset + (sticky ? 31 : 0);
                this.assertEquivalent(Layout.piston(seed, offset, sticky), seed);
            }
        }
    }

    @Test
    void comparatorReadsAForeignBlockEntity() {
        for (int offset : new int[] { 1 }) {
            final long seed = 0xC0041L + offset;
            this.assertEquivalent(Layout.comparatorChest(seed, offset), seed);
        }
    }

    @Test
    void noteBlockQueuesItsEventAcrossTheBorder() {
        this.assertEquivalent(Layout.noteBlock(0x1207EL), 0x1207EL);
    }

    @Test
    void bothSidesOfTheBorderChangeInTheSameTick() {
        for (int offset = 1; offset <= 15; offset++) {
            final long seed = 0x51DE5L + offset;
            this.assertEquivalent(Layout.simultaneousSides(seed, offset), seed);
        }
    }

    @Test
    void inputComponentsAcrossTheBorder() {
        for (int offset : new int[] { 1, 5, 10, 15 }) {
            final long seed = 0x1E7E2L + offset;
            this.assertEquivalent(Layout.inputs(seed, offset), seed);
        }
    }

    /**
     * Measures the scheduled-tick slice on a grid of repeater-driven dust lines that are entirely inside one
     * fixed region, with the border capture off and on. No update of the grid can cross a border, so the
     * difference between the two configurations is the whole cost of the capture when no border update
     * occurs. The workload is sized by {@code -Dneoforge.redstoneBenchmarkChains} (eight chains by default);
     * every configuration runs {@value #BENCHMARK_WARMUP} warmup and {@value #BENCHMARK_MEASURED} measured
     * ticks, twice, and the printed row is the average of the two runs.
     */
    @Tag("benchmark")
    @Test
    @Timeout(900)
    void reportsRedstoneBorderUpdateCost() {
        final int chains = Integer.getInteger("neoforge.redstoneBenchmarkChains", 8);
        System.out.println("redstone-benchmark,jvmMaxHeap=" + Runtime.getRuntime().maxMemory()
                + ",jvmAvailableProcessors=" + Runtime.getRuntime().availableProcessors() + ",chains=" + chains);
        for (final boolean borderUpdates : new boolean[] { false, true }) {
            final List<Double> repeats = new ArrayList<>(2);
            final List<String> counters = new ArrayList<>(2);
            for (int repeat = 0; repeat < 2; repeat++) {
                final double[] result = this.measureRedstoneGrid(chains, borderUpdates);
                repeats.add(result[0]);
                counters.add("recorded=" + (long) result[1] + "/applied=" + (long) result[2] + "/delayed=" + (long) result[3]);
            }
            final double average = (repeats.get(0) + repeats.get(1)) / 2.0;
            System.out.println("redstone-border-updates=" + borderUpdates + ",chains=" + chains
                    + ",msPerTick=" + String.format(java.util.Locale.ROOT, "%.4f", average)
                    + ",run1=" + String.format(java.util.Locale.ROOT, "%.4f", repeats.get(0))
                    + ",run2=" + String.format(java.util.Locale.ROOT, "%.4f", repeats.get(1))
                    + ",counters=" + counters);
        }
    }

    private static final int BENCHMARK_WARMUP = 200;
    private static final int BENCHMARK_MEASURED = 600;

    private double[] measureRedstoneGrid(int chains, boolean borderUpdates) {
        RegionThreadingManager.clear();
        this.gameTime.set(0L);
        final ComponentWorld world = new ComponentWorld();
        final ServerLevel level = world.level;
        final Layout layout = Layout.interiorGrid(0xB12E5L, chains);
        layout.build(world);
        final RegionThreadingEngine installed = new RegionThreadingEngine(4, false, false, false, false, true, RegionPolicy.FIXED_3X3);
        installed.setBorderUpdatesEnabledForTesting(borderUpdates);
        installed.setBorderUpdateBudgetForTesting(20000);
        installed.enableUnclassifiedWorkerCallbacksForTesting();
        RegionThreadingManager.install(installed);
        for (int chunkX = 0; chunkX <= 2; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 2; chunkZ++) {
                installed.addRegionSection(level, chunkX, chunkZ);
            }
        }
        final boolean[] powered = new boolean[chains];
        long elapsed = 0L;
        final int total = BENCHMARK_WARMUP + BENCHMARK_MEASURED;
        for (int tick = 1; tick <= total; tick++) {
            this.gameTime.set(tick);
            for (int chain = 0; chain < chains; chain++) {
                powered[chain] = !powered[chain];
                world.level.setBlock(layout.clockPositions().get(chain),
                        powered[chain] ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
            final long start = System.nanoTime();
            final LevelTicks.TickDispatcher<Block> dispatcher = installed.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER);
            world.blockTicks.tick(tick, 65536, (pos, block) -> level.getBlockState(pos).tick(level, pos, world.scheduledRandom), dispatcher);
            world.runBlockEvents(true);
            world.tickPistonMovingBlockEntities(true);
            final long duration = System.nanoTime() - start;
            if (tick > BENCHMARK_WARMUP) {
                elapsed += duration;
            }
        }
        final long recorded = installed.diagnostics().borderUpdates().recorded();
        final long applied = installed.diagnostics().borderUpdates().applied();
        final long delayed = installed.diagnostics().borderUpdates().delayed();
        assertEquals(0, recorded, "the interior grid must not produce a border update");
        installed.shutdown();
        RegionThreadingManager.clear();
        return new double[] { elapsed / 1_000_000.0 / BENCHMARK_MEASURED, recorded, applied, delayed };
    }

    @Test
    void componentSharedStateIsThreadScoped() throws ReflectiveOperationException {
        // The dust suppression flag and the torch toggle history used to be one mutable value per class,
        // shared by every region worker. The regression guard is that the flag is a ThreadLocal and the
        // history map is synchronized; the behavioral half is the equivalence suite above.
        final java.lang.reflect.Field flag = net.minecraft.world.level.block.RedStoneWireBlock.class.getDeclaredField("SHOULD_SIGNAL");
        assertEquals(ThreadLocal.class, flag.getType(), "the dust recursion guard has to be per computation");
        final java.lang.reflect.Field toggles = RedstoneTorchBlock.class.getDeclaredField("RECENT_TOGGLES");
        toggles.setAccessible(true);
        assertTrue(toggles.get(null).getClass().getName().contains("Synchronized"),
                "the torch toggle history has to be safe for concurrent regions");
    }

    private void assertEquivalent(Layout layout, long seed) {
        final RunResult off = this.run(false, layout, seed);
        final RunResult on = this.run(true, layout, seed);
        for (int tick = 0; tick < off.perTick().size(); tick++) {
            assertEquals(off.perTick().get(tick), on.perTick().get(tick),
                    "layout '" + layout.name() + "' seed " + seed + " differs at tick " + (tick + 1)
                            + ": " + this.lastOnDiagnostics);
        }
        assertEquals(off.firstSeen(), on.firstSeen(),
                "layout '" + layout.name() + "' seed " + seed + " disagrees about the tick a state first appeared: "
                        + this.lastOnDiagnostics);
    }

    private RunResult run(boolean regionThreading, Layout layout, long seed) {
        RegionThreadingManager.clear();
        this.engine = null;
        this.gameTime.set(0L);
        final ComponentWorld world = new ComponentWorld();
        final ServerLevel level = world.level;
        layout.build(world);
        if (regionThreading) {
            final RegionThreadingEngine installed = new RegionThreadingEngine(4, false, false, false, false, true, RegionPolicy.FIXED_3X3);
            installed.setBorderUpdatesEnabledForTesting(true);
            installed.setBorderUpdateBudgetForTesting(20000);
            installed.enableUnclassifiedWorkerCallbacksForTesting();
            RegionThreadingManager.install(installed);
            this.engine = installed;
            final int firstChunk = Math.floorDiv(layout.trace().minX() - 2, 16) - 1;
            final int lastChunk = Math.floorDiv(layout.trace().maxX() + 2, 16) + 1;
            for (int chunkX = firstChunk; chunkX <= lastChunk; chunkX++) {
                installed.addRegionSection(level, chunkX, Math.floorDiv(Z, 16));
            }
        }
        // A source action at tick 0 runs after the engine is installed and before the first tick, so the first
        // cascade can start on a region worker of the on run.
        layout.applyActions(world, 0);
        final List<String> perTick = new ArrayList<>(TICKS);
        final Map<String, Integer> firstSeen = new LinkedHashMap<>();
        for (int tick = 1; tick <= TICKS; tick++) {
            this.gameTime.set(tick);
            layout.applyActions(world, tick);
            final LevelTicks.TickDispatcher<Block> dispatcher = regionThreading
                    ? this.engine.beginScheduledTickDispatcher(level, ScheduledTickPhase.BLOCK, PARKING_BARRIER)
                    : null;
            if (regionThreading) {
                assertNotNull(dispatcher, "the on run has to dispatch scheduled ticks on workers: " + this.lastOnDiagnostics);
            }
            world.blockTicks.tick(tick, 65536, (pos, block) -> level.getBlockState(pos).tick(level, pos, world.scheduledRandom), dispatcher);
            world.runBlockEvents(regionThreading);
            world.tickPistonMovingBlockEntities(regionThreading);
            final String snapshot = world.snapshot(layout.trace());
            perTick.add(snapshot);
            for (final String entry : snapshot.split("\n")) {
                firstSeen.putIfAbsent(entry, tick);
            }
        }
        if (this.engine != null) {
            final String name = layout.name();
            if (regionThreading) {
                if (!name.startsWith("comparator-chest")) {
                    assertTrue(this.engine.diagnostics().borderUpdates().recorded() > 0,
                            "layout '" + name + "' has to exercise the border capture: " + this.engine.diagnostics().borderUpdates());
                }
                if (name.startsWith("piston") || name.startsWith("sticky-piston") || name.equals("note-block")) {
                    assertTrue(this.engine.deferredBlockEvents() > 0,
                            "layout '" + name + "' has to exercise the worker block-event deferral");
                }
                if (name.startsWith("comparator-chest")) {
                    assertTrue(this.engine.foreignBlockEntityReads() > 0,
                            "layout '" + name + "' has to exercise the quiescent foreign block-entity read");
                }
            }
            this.lastOnDiagnostics = this.engine.diagnostics().borderUpdates() + ", deferredBlockEvents="
                    + this.engine.deferredBlockEvents() + ", scheduledOnWorkers="
                    + this.engine.diagnostics().scheduledTicksOnWorkers() + ", fallbackScheduled="
                    + this.engine.diagnostics().fallbackScheduledTicks() + ", skippedScheduled="
                    + this.engine.diagnostics().skippedScheduledTicks() + ", violations="
                    + this.engine.diagnostics().ownershipViolationCount();
            this.engine.shutdown();
            this.engine = null;
        }
        RegionThreadingManager.clear();
        return new RunResult(perTick, firstSeen);
    }

    private record RunResult(List<String> perTick, Map<String, Integer> firstSeen) {}

    /** The box of positions a layout traces: the state and block entity of every position inside it. */
    record TraceBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {}

    /**
     * One deterministic redstone layout: the placements, the traced box and the actions the server thread
     * applies at named ticks.
     */
    static final class Layout {
        private final String name;
        private final List<TimedAction> actions = new ArrayList<>();
        private final List<BlockPos> clockPositions = new ArrayList<>();
        private final TraceBox trace;

        private Layout(String name, int minX, int maxX) {
            this.name = name;
            this.trace = new TraceBox(minX - 2, maxX + 2, Y - 2, Y + 3, Z - 3, Z + 3);
        }

        String name() {
            return this.name;
        }

        TraceBox trace() {
            return this.trace;
        }

        List<BlockPos> clockPositions() {
            return this.clockPositions;
        }

        private record Placement(BlockPos pos, BlockState state) {}

        private record TimedAction(int tick, Consumer<ComponentWorld> body) {}

        private void place(int x, int y, int z, BlockState state) {
            this.actions.add(new TimedAction(-1, world -> world.level.setBlock(new BlockPos(x, y, z), state, 3)));
        }

        private void action(int tick, Consumer<ComponentWorld> body) {
            this.actions.add(new TimedAction(tick, body));
        }

        private void build(ComponentWorld world) {
            // The placement actions use tick -1, so the build pass only runs them. They are applied in list
            // order, exactly like the placement of the circuit by a player.
            this.applyActions(world, -1);
        }

        private void applyActions(ComponentWorld world, int tick) {
            for (final TimedAction action : this.actions) {
                if (action.tick() == tick) {
                    action.body().accept(world);
                }
            }
        }

        private static BlockState stone() {
            return Blocks.STONE.defaultBlockState();
        }

        private static BlockState repeater(Direction facing, int delay) {
            return Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.DELAY, delay);
        }

        private static BlockState comparator(Direction facing, ComparatorMode mode) {
            return Blocks.COMPARATOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(BlockStateProperties.MODE_COMPARATOR, mode);
        }

        private static BlockState dust() {
            return Blocks.REDSTONE_WIRE.defaultBlockState();
        }

        private static BlockState lamp() {
            return Blocks.REDSTONE_LAMP.defaultBlockState();
        }

        private static BlockState rail() {
            return Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(BlockStateProperties.POWERED, false)
                    .setValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT, RailShape.NORTH_SOUTH);
        }

        private static BlockState noteBlock() {
            return Blocks.NOTE_BLOCK.defaultBlockState();
        }

        private static BlockState observer(Direction facing) {
            return Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
        }

        private static BlockState piston(Direction facing, boolean sticky) {
            return (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing)
                    .setValue(BlockStateProperties.EXTENDED, false);
        }

        private static BlockState wallTorch(Direction facing) {
            return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                    .setValue(RedstoneTorchBlock.LIT, true);
        }

        private static BlockState lever(boolean powered) {
            return Blocks.LEVER.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.POWERED, powered);
        }

        private static BlockState button() {
            return Blocks.OAK_BUTTON.defaultBlockState()
                    .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                    .setValue(BlockStateProperties.POWERED, false);
        }

        private void support(int x, int z) {
            this.place(x, Y - 1, z, stone());
        }

        private void dustRun(int startX, int length, int z) {
            for (int index = 0; index < length; index++) {
                this.support(startX + index, z);
                this.place(startX + index, Y, z, dust());
            }
        }

        private void lampAt(int x, int z) {
            this.support(x, z);
            this.place(x, Y, z, lamp());
        }

        private void source(int tick, int x, int z, BlockState state) {
            this.action(tick, world -> world.level.setBlock(new BlockPos(x, Y, z), state, 3));
        }

        /**
         * A repeater-driven dust line of exactly {@code length} dust blocks. The repeater is the component
         * whose scheduled tick starts the cascade, so the dust always updates from inside a region group.
         */
        static Layout dustLine(long seed, int offset, int length) {
            final RandomSource random = RandomSource.create(seed);
            final int startX = BORDER_X - offset;
            final Layout layout = new Layout("dust-length-" + length, startX - 1, startX + length + 4);
            layout.support(startX, Z);
            layout.place(startX, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.dustRun(startX + 1, length, Z);
            layout.lampAt(startX + 1 + length, Z);
            layout.source(0, startX - 1, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            layout.source(35, startX - 1, Z, Blocks.AIR.defaultBlockState());
            return layout;
        }

        /** A chain that carries dust, a comparator, repeater stages, a rail, a note block and a lamp. */
        static Layout mixedChain(long seed, int offset) {
            final RandomSource random = RandomSource.create(seed);
            final int startX = BORDER_X - offset;
            final int length = Math.max(offset + 4, 6 + random.nextInt(8));
            final Layout layout = new Layout("mixed-offset-" + offset, startX - 1, startX + length + 8);
            layout.support(startX, Z);
            layout.place(startX, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.dustRun(startX + 1, length, Z);
            // A lamp under the first dust block is the output of the driver, and the final repeater powers a
            // note block, so a worker tick queues the note block's block event.
            layout.place(startX + 1, Y - 1, Z, lamp());
            final int comparatorX = startX + 1 + length;
            layout.support(comparatorX, Z);
            layout.place(comparatorX, Y, Z, comparator(Direction.WEST,
                    random.nextBoolean() ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT));
            layout.support(comparatorX + 1, Z);
            layout.place(comparatorX + 1, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.place(comparatorX + 2, Y, Z, noteBlock());
            layout.support(startX + 3, Z + 1);
            layout.place(startX + 3, Y, Z + 1, noteBlock());
            layout.support(startX + 4, Z - 1);
            layout.place(startX + 4, Y, Z - 1, rail());
            layout.source(0, startX - 1, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            layout.source(38, startX - 1, Z, Blocks.AIR.defaultBlockState());
            return layout;
        }

        /**
         * A wall torch inverter and an observer. The torch is unlit when placed and its wall is a redstone block
         * placed after it, so the torch schedules its two-tick turn-on from inside the first worker tick.
         */
        static Layout torchObserver(long seed, int offset) {
            final RandomSource random = RandomSource.create(seed);
            final int startX = BORDER_X - offset;
            final Layout layout = new Layout("torch-observer-offset-" + offset, startX - 1, startX + 9);
            layout.place(startX, Y, Z, wallTorch(Direction.SOUTH));
            layout.place(startX, Y, Z - 1, Blocks.REDSTONE_BLOCK.defaultBlockState());
            layout.support(startX + 1, Z);
            layout.place(startX + 1, Y, Z, dust());
            layout.support(startX + 2, Z);
            layout.place(startX + 2, Y, Z, observer(Direction.WEST));
            layout.support(startX + 3, Z);
            layout.place(startX + 3, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.lampAt(startX + 4, Z);
            layout.action(30, world -> world.level.setBlock(new BlockPos(startX, Y, Z - 1), Blocks.AIR.defaultBlockState(), 3));
            return layout;
        }

        /**
         * A repeater-triggered piston that pushes a column of blocks across the border; the sticky variant pulls
         * the column back on the falling edge.
         */
        static Layout piston(long seed, int offset, boolean sticky) {
            final RandomSource random = RandomSource.create(seed);
            // The piston sits just east of the border and pushes a column of 1..15 blocks west across it, so
            // every offset moves a different amount of blocks into the other region.
            final int pistonX = BORDER_X + 1;
            final Layout layout = new Layout((sticky ? "sticky-piston-" : "piston-") + offset, BORDER_X - offset - 2, pistonX + 4);
            layout.place(pistonX, Y, Z, piston(Direction.WEST, sticky));
            for (int index = 1; index <= Math.min(offset + 1, 12); index++) {
                layout.place(pistonX - index, Y, Z, Blocks.STONE.defaultBlockState());
            }
            layout.support(pistonX + 1, Z);
            layout.place(pistonX + 1, Y, Z, repeater(Direction.EAST, 1 + random.nextInt(4)));
            layout.source(0, pistonX + 2, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            layout.source(36, pistonX + 2, Z, Blocks.AIR.defaultBlockState());
            return layout;
        }

        /**
         * A grid of repeater-driven dust lines entirely inside one fixed region, used by the benchmark. Every
         * dust line is driven by a redstone block the benchmark toggles each tick; no update of the grid can
         * target another region, so the border capture records nothing.
         */
        static Layout interiorGrid(long seed, int chains) {
            final RandomSource random = RandomSource.create(seed);
            final Layout layout = new Layout("interior-grid", 12, 44);
            for (int chain = 0; chain < chains; chain++) {
                final int x = 16 + (chain / 8) * 6;
                final int z = 16 + (chain % 8) * 3;
                layout.support(x, z);
                layout.place(x, Y, z, repeater(Direction.WEST, 1 + random.nextInt(4)));
                layout.dustRun(x + 1, 4, z);
                layout.lampAt(x + 5, z);
                layout.clockPositions.add(new BlockPos(x - 1, Y, z));
            }
            return layout;
        }

        /** A repeater in one region drives a note block in the next; the note block queues a block event. */
        static Layout noteBlock(long seed) {
            final RandomSource random = RandomSource.create(seed);
            final Layout layout = new Layout("note-block", BORDER_X - 4, BORDER_X + 3);
            layout.support(BORDER_X - 2, Z);
            layout.place(BORDER_X - 2, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.place(BORDER_X - 1, Y, Z, Blocks.STONE.defaultBlockState());
            layout.place(BORDER_X, Y, Z, noteBlock());
            layout.source(0, BORDER_X - 3, Z, Blocks.REDSTONE_BLOCK.defaultBlockState());
            return layout;
        }

        /** A comparator whose input is a chest in the next region. */
        static Layout comparatorChest(long seed, int offset) {
            final RandomSource random = RandomSource.create(seed);
            final int startX = BORDER_X - offset;
            final Layout layout = new Layout("comparator-chest-offset-" + offset, startX - 2, startX + 8);
            final int chestX = startX;
            layout.support(chestX, Z);
            layout.place(chestX, Y, Z, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            layout.support(chestX + 1, Z);
            layout.place(chestX + 1, Y, Z, comparator(Direction.WEST,
                    random.nextBoolean() ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT));
            layout.support(chestX + 2, Z);
            layout.place(chestX + 2, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.lampAt(chestX + 3, Z);
            layout.action(0, world -> fillChest(world, chestX, 64));
            layout.action(25, world -> fillChest(world, chestX, 3));
            return layout;
        }

        /**
         * A lever and a button, each followed by a repeater that drives a dust line across the border. The
         * repeater sits in the source region and the dust line runs into the next one, so the worker-driven
         * cascade of the repeater produces a border update for every offset.
         */
        static Layout inputs(long seed, int offset) {
            final RandomSource random = RandomSource.create(seed);
            final int startX = BORDER_X - offset;
            final int length = offset + 2;
            final Layout layout = new Layout("inputs-offset-" + offset, startX - 3, startX + length + 5);
            layout.support(startX - 1, Z);
            layout.place(startX - 1, Y, Z, lever(false));
            layout.support(startX, Z);
            layout.place(startX, Y, Z, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.dustRun(startX + 1, length, Z);
            layout.lampAt(startX + 1 + length, Z);
            layout.support(startX - 1, Z + 2);
            layout.place(startX - 1, Y, Z + 2, button());
            layout.support(startX, Z + 2);
            layout.place(startX, Y, Z + 2, repeater(Direction.WEST, 1 + random.nextInt(4)));
            layout.dustRun(startX + 1, length, Z + 2);
            layout.lampAt(startX + 1 + length, Z + 2);
            layout.action(12, world -> {
                final BlockPos leverPos = new BlockPos(startX - 1, Y, Z);
                final BlockState state = world.level.getBlockState(leverPos);
                ((LeverBlock) Blocks.LEVER).pull(state, world.level, leverPos, null);
            });
            layout.action(8, world -> {
                final BlockPos buttonPos = new BlockPos(startX - 1, Y, Z + 2);
                final BlockState state = world.level.getBlockState(buttonPos);
                ((ButtonBlock) Blocks.OAK_BUTTON).press(state, world.level, buttonPos, null);
            });
            return layout;
        }

        /**
         * Two independent driver circuits, one on each side of the border, clocked on the same ticks.
         * <p>
         * The dust line of the west circuit ends on the last block of its region and the dust line of the east
         * circuit starts on the first block of its region, so the two lines connect across the border: each side
         * reads the dust of the other side while its own worker writes it. Both clocks toggle on the same ticks,
         * so the two regions write and read the same chunk in the same tick, which is the interleaving the version
         * check of a foreign read has to keep equivalent to the single-threaded run.
         */
        static Layout simultaneousSides(long seed, int offset) {
            final RandomSource random = RandomSource.create(seed);
            final int westStart = BORDER_X - offset;
            final int westLength = offset - 1;
            final int eastEnd = BORDER_X + offset - 1;
            final int eastLength = offset;
            final int delay = 1 + random.nextInt(2);
            final Layout layout = new Layout("simultaneous-sides-" + offset, westStart - 2, eastEnd + 3);
            // West: a clock, a repeater and the dust that reaches the border, with a lamp under the first dust.
            layout.support(westStart, Z);
            layout.place(westStart, Y, Z, repeater(Direction.WEST, delay));
            if (westLength > 0) {
                layout.dustRun(westStart + 1, westLength, Z);
                layout.lampAt(westStart + 1, Z);
            }
            // East: the mirrored circuit, whose dust starts on the first block of the other region.
            layout.dustRun(BORDER_X, eastLength, Z);
            layout.lampAt(eastEnd, Z);
            layout.support(eastEnd + 1, Z);
            layout.place(eastEnd + 1, Y, Z, repeater(Direction.EAST, delay));
            // Both clocks drive their side on the same tick, so both regions change in the same tick.
            for (final int tick : new int[] { 0, 20, 40 }) {
                final BlockState state = tick == 20 ? Blocks.AIR.defaultBlockState() : Blocks.REDSTONE_BLOCK.defaultBlockState();
                layout.source(tick, westStart - 1, Z, state);
                layout.source(tick, eastEnd + 2, Z, state);
            }
            return layout;
        }

        private static void fillChest(ComponentWorld world, int x, int count) {
            final BlockPos chestPos = new BlockPos(x, Y, Z);
            if (world.blockEntities.get(chestPos) instanceof Container container) {
                container.setItem(0, new ItemStack(Items.STONE, count));
                ((BlockEntity) container).setChanged();
            }
        }
    }

    /**
     * A level mock with the production write, signal, block-entity and block-event seams. The block states and
     * block entities live in concurrent maps so the propagation passes and the region workers can read each
     * other's committed state, and the scheduled ticks are the real {@link LevelTicks} scheduler.
     */
    private final class ComponentWorld {
        private final ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
        private final Thread serverThread = Thread.currentThread();
        private final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<BlockEvent> blockEvents = new ConcurrentLinkedQueue<>();
        private final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
        private final RandomSource scheduledRandom = RandomSource.create(0x5EEDL);
        private final RegionAwareNeighborUpdater neighborUpdater;

        private record BlockEvent(BlockPos pos, Block block, int id, int param) {}

        private ComponentWorld() {
            for (int chunkX = -2; chunkX <= 12; chunkX++) {
                this.blockTicks.addContainer(new ChunkPos(chunkX, 0), new LevelChunkTicks<>());
            }
            this.neighborUpdater = new RegionAwareNeighborUpdater(this.level, 1000000);
            setLevelField(this.level, "random", RandomSource.create(4321L));
            doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
            doReturn(1234L).when(this.level).getSeed();
            doAnswer(invocation -> gameTime.get()).when(this.level).getGameTime();
            doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(this.level).dimension();
            doReturn(1000000).when(this.level).maxChainedNeighborUpdates();
            doReturn(this.blockTicks).when(this.level).getBlockTicks();
            doReturn(0).when(this.level).getMinBuildHeight();
            doReturn(384).when(this.level).getMaxBuildHeight();
            doReturn(false).when(this.level).isOutsideBuildHeight(any(BlockPos.class));
            doReturn(true).when(this.level).hasChunkAt(any(BlockPos.class));
            doReturn(new WorldBorder()).when(this.level).getWorldBorder();
            doAnswer(invocation -> BuiltInRegistries.BLOCK.asLookup()).when(this.level).holderLookup(any());
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()))
                    .when(this.level).getBlockState(any(BlockPos.class));
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()).getFluidState())
                    .when(this.level).getFluidState(any(BlockPos.class));
            doAnswer(invocation -> this.setBlock(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))
                    .when(this.level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
            doAnswer(invocation -> this.removeBlock(invocation.getArgument(0), invocation.getArgument(1)))
                    .when(this.level).removeBlock(any(BlockPos.class), anyBoolean());
            doAnswer(invocation -> {
                this.neighborUpdater.updateNeighborsAtExceptFromFacing(invocation.getArgument(0), invocation.getArgument(1), null);
                return null;
            }).when(this.level).updateNeighborsAt(any(BlockPos.class), any(Block.class));
            doAnswer(invocation -> {
                this.neighborUpdater.updateNeighborsAtExceptFromFacing(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2));
                return null;
            }).when(this.level).updateNeighborsAtExceptFromFacing(any(BlockPos.class), any(Block.class), any());
            doAnswer(invocation -> {
                this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(this.level).neighborChanged(any(BlockPos.class), any(Block.class), any(BlockPos.class));
            doAnswer(invocation -> {
                this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4));
                return null;
            }).when(this.level).neighborChanged(any(BlockState.class), any(BlockPos.class), any(Block.class), any(BlockPos.class),
                    anyBoolean());
            doAnswer(invocation -> {
                this.neighborUpdater.shapeUpdate(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
                return null;
            }).when(this.level).neighborShapeChanged(any(Direction.class), any(BlockState.class), any(BlockPos.class),
                    any(BlockPos.class), anyInt(), anyInt());
            doAnswer(invocation -> {
                this.updateNeighboursAt(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(this.level).blockUpdated(any(BlockPos.class), any(Block.class));
            doAnswer(invocation -> {
                this.updateNeighbourForOutputSignal(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(this.level).updateNeighbourForOutputSignal(any(BlockPos.class), any(Block.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                int signal = state.getSignal(this.level, pos, invocation.getArgument(1));
                if (state.shouldCheckWeakPower(this.level, pos, invocation.getArgument(1))) {
                    signal = Math.max(signal, this.directSignalTo(pos));
                }
                return signal;
            }).when(this.level).getSignal(any(BlockPos.class), any(Direction.class));
            doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState())
                    .getDirectSignal(this.level, invocation.getArgument(0), invocation.getArgument(1)))
                            .when(this.level).getDirectSignal(any(BlockPos.class), any(Direction.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                final Direction direction = invocation.getArgument(1);
                final boolean diodesOnly = invocation.getArgument(2);
                final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                if (diodesOnly) {
                    return DiodeBlock.isDiode(state) ? this.level.getDirectSignal(pos, direction) : 0;
                }
                if (state.is(Blocks.REDSTONE_BLOCK)) {
                    return 15;
                }
                if (state.is(Blocks.REDSTONE_WIRE)) {
                    return state.getValue(BlockStateProperties.POWER);
                }
                return state.isSignalSource() ? this.level.getDirectSignal(pos, direction) : 0;
            }).when(this.level).getControlInputSignal(any(BlockPos.class), any(Direction.class), anyBoolean());
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                int best = 0;
                for (final Direction direction : Direction.values()) {
                    final int signal = this.level.getSignal(pos.relative(direction), direction);
                    if (signal >= 15) {
                        return 15;
                    }
                    best = Math.max(best, signal);
                }
                return best;
            }).when(this.level).getBestNeighborSignal(any(BlockPos.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                for (final Direction direction : Direction.values()) {
                    if (this.level.getSignal(pos.relative(direction), direction) > 0) {
                        return true;
                    }
                }
                return false;
            }).when(this.level).hasNeighborSignal(any(BlockPos.class));
            doAnswer(invocation -> this.level.getSignal(invocation.getArgument(0), invocation.getArgument(1)) > 0)
                    .when(this.level).hasSignal(any(BlockPos.class), any(Direction.class));
            doAnswer(invocation -> this.getBlockEntity(invocation.getArgument(0)))
                    .when(this.level).getBlockEntity(any(BlockPos.class));
            doAnswer(invocation -> {
                final BlockEntity blockEntity = invocation.getArgument(0);
                blockEntity.setLevel(this.level);
                blockEntity.clearRemoved();
                this.blockEntities.put(blockEntity.getBlockPos().immutable(), blockEntity);
                return null;
            }).when(this.level).setBlockEntity(any(BlockEntity.class));
            doAnswer(invocation -> {
                final BlockPos pos = invocation.getArgument(0);
                this.blockEntities.remove(pos);
                this.updateNeighbourForOutputSignal(pos, this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()).getBlock());
                return null;
            }).when(this.level).removeBlockEntity(any(BlockPos.class));
            doAnswer(invocation -> {
                final BlockPos pos = ((BlockPos) invocation.getArgument(0)).immutable();
                final Block block = invocation.getArgument(1);
                final int id = invocation.getArgument(2);
                final int param = invocation.getArgument(3);
                if (!RegionThreadingHooks.deferBlockEvent(this.level, pos, block, id, param)) {
                    if (Thread.currentThread() != this.serverThread) {
                        throw new IllegalStateException("a region worker mutated the level-wide block event set");
                    }
                    this.blockEvents.add(new BlockEvent(pos, block, id, param));
                }
                return null;
            }).when(this.level).blockEvent(any(BlockPos.class), any(Block.class), anyInt(), anyInt());
            doAnswer(invocation -> {
                this.blockTicks.schedule(new ScheduledTick<>(invocation.getArgument(1, Block.class),
                        ((BlockPos) invocation.getArgument(0)).immutable(), gameTime.get() + invocation.getArgument(2, Integer.class), 0));
                return null;
            }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt());
            doAnswer(invocation -> {
                this.blockTicks.schedule(new ScheduledTick<>(invocation.getArgument(1, Block.class),
                        ((BlockPos) invocation.getArgument(0)).immutable(), gameTime.get() + invocation.getArgument(2, Integer.class),
                        invocation.getArgument(3, TickPriority.class), 0L));
                return null;
            }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt(), any(TickPriority.class));
            doAnswer(invocation -> null).when(this.level).playSound(any(), any(BlockPos.class), any(), any(), anyFloat(), anyFloat());
            doAnswer(invocation -> null).when(this.level).levelEvent(any(), anyInt(), any(BlockPos.class), anyInt());
            doAnswer(invocation -> null).when(this.level).gameEvent(any(Holder.class), any(BlockPos.class), any(GameEvent.Context.class));
            doAnswer(invocation -> null).when(this.level).gameEvent(any(Holder.class), any(Vec3.class), any(GameEvent.Context.class));
            doAnswer(invocation -> null).when(this.level).gameEvent(any(Entity.class), any(Holder.class), any(BlockPos.class));
            doAnswer(invocation -> null).when(this.level).addDestroyBlockEffect(any(BlockPos.class), any(BlockState.class));
            doAnswer(invocation -> null).when(this.level).addFreshEntity(any());
            doReturn(List.of()).when(this.level).getEntities(any(), any(AABB.class));
            doReturn(List.of()).when(this.level).getEntities(any(Entity.class), any(AABB.class), any());
            doReturn(List.of()).when(this.level).getEntitiesOfClass(any(), any(AABB.class));
            final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
            doAnswer(invocation -> {
                final BooleanSupplier condition = invocation.getArgument(0);
                while (!condition.getAsBoolean()) {
                    LockSupport.parkNanos(100_000L);
                }
                return null;
            }).when(chunkSource).managedBlockOnMainThread(any());
            doReturn(chunkSource).when(this.level).getChunkSource();
            setLevelField(this.level, "neighborUpdater", this.neighborUpdater);
        }

        private int directSignalTo(BlockPos pos) {
            int best = 0;
            best = Math.max(best, this.level.getDirectSignal(pos.below(), Direction.DOWN));
            best = Math.max(best, this.level.getDirectSignal(pos.above(), Direction.UP));
            best = Math.max(best, this.level.getDirectSignal(pos.north(), Direction.NORTH));
            best = Math.max(best, this.level.getDirectSignal(pos.south(), Direction.SOUTH));
            best = Math.max(best, this.level.getDirectSignal(pos.west(), Direction.WEST));
            best = Math.max(best, this.level.getDirectSignal(pos.east(), Direction.EAST));
            return best;
        }

        private void updateNeighboursAt(BlockPos pos, Block block) {
            this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, null);
        }

        private void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
            for (final Direction direction : Direction.values()) {
                if (!this.level.hasChunkAt(pos.relative(direction))) {
                    continue;
                }
                final BlockPos neighbourPos = pos.relative(direction);
                BlockState neighbourState = this.level.getBlockState(neighbourPos);
                neighbourState.onNeighborChange(this.level, neighbourPos, pos);
                if (neighbourState.isRedstoneConductor(this.level, neighbourPos)) {
                    final BlockPos weakPos = neighbourPos.relative(direction);
                    final BlockState weakState = this.level.getBlockState(weakPos);
                    if (weakState.getWeakChanges(this.level, weakPos)) {
                        this.level.neighborChanged(weakState, weakPos, block, neighbourPos, false);
                    }
                }
            }
        }

        private boolean removeBlock(BlockPos pos, boolean isMoving) {
            final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            return this.setBlock(pos, state.getFluidState().createLegacyBlock(), 3 | (isMoving ? 64 : 0));
        }

        /**
         * The vanilla chunk write, block entity lifecycle and neighbor/shape publication of
         * {@code Level.setBlock}, condensed to what the redstone components reach.
         */
        private boolean setBlock(BlockPos pos, BlockState state, int flags) {
            pos = pos.immutable();
            final BlockState previous = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            final boolean moving = (flags & 64) != 0;
            if (previous == state) {
                return false;
            }
            this.states.put(pos, state);
            previous.onRemove(this.level, pos, state, moving);
            if (!this.states.get(pos).is(state.getBlock())) {
                return false;
            }
            state.onPlace(this.level, pos, previous, moving);
            if (state.hasBlockEntity()) {
                BlockEntity blockEntity = this.blockEntities.get(pos);
                if (blockEntity != null && !blockEntity.isValidBlockState(state)) {
                    this.blockEntities.remove(pos);
                    blockEntity = null;
                }
                if (blockEntity == null) {
                    blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                    if (blockEntity != null) {
                        blockEntity.setLevel(this.level);
                        blockEntity.clearRemoved();
                        this.blockEntities.put(pos, blockEntity);
                    }
                } else {
                    blockEntity.setBlockState(state);
                }
            }
            // Level.markAndNotifyBlock: a reentrant onPlace may already have written another state at this
            // position, in which case the outer write publishes nothing (vanilla compares the state again).
            if (this.states.get(pos) != state) {
                return true;
            }
            if ((flags & 1) != 0) {
                this.updateNeighboursAt(pos, previous.getBlock());
                if (state.hasAnalogOutputSignal()) {
                    this.updateNeighbourForOutputSignal(pos, state.getBlock());
                }
            }
            if ((flags & 16) == 0) {
                final int shapeFlags = flags & ~34;
                previous.updateIndirectNeighbourShapes(this.level, pos, shapeFlags, 511);
                state.updateNeighbourShapes(this.level, pos, shapeFlags, 511);
                state.updateIndirectNeighbourShapes(this.level, pos, shapeFlags, 511);
            }
            return true;
        }

        private BlockEntity getBlockEntity(BlockPos pos) {
            pos = pos.immutable();
            if (Thread.currentThread() != this.serverThread
                    && !RegionThreadingHooks.isRegionTickOwnedByCurrentThread(this.level, pos)
                    && !RegionThreadingHooks.isForeignRegionBlockEntityReadable(this.level, pos)) {
                return null;
            }
            return this.blockEntities.get(pos);
        }

        private void runBlockEvents(boolean regionThreading) {
            BlockEvent event;
            while ((event = this.blockEvents.poll()) != null) {
                final BlockEvent current = event;
                final BlockState state = this.states.getOrDefault(current.pos(), Blocks.AIR.defaultBlockState());
                if (!state.is(current.block())) {
                    continue;
                }
                final Runnable body = () -> state.triggerEvent(this.level, current.pos(), current.id(), current.param());
                if (regionThreading) {
                    RegionThreadingHooks.runBlockTickTask(this.level, current.pos(), body);
                } else {
                    body.run();
                }
            }
        }

        private void tickPistonMovingBlockEntities(boolean regionThreading) {
            for (final Map.Entry<BlockPos, BlockEntity> entry : new ArrayList<>(this.blockEntities.entrySet())) {
                if (!(entry.getValue() instanceof PistonMovingBlockEntity piston)) {
                    continue;
                }
                final BlockPos pos = entry.getKey();
                final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                final Runnable body = () -> PistonMovingBlockEntity.tick(this.level, pos, state, piston);
                if (regionThreading) {
                    RegionThreadingHooks.runBlockTickTask(this.level, pos, body);
                } else {
                    body.run();
                }
            }
        }

        /**
         * The state and block entity of every non-air position of the trace box, ordered by x, y, z.
         * <p>
         * Air without a block entity is omitted: it is the constant background of the box, and a position
         * that changes between air and a block still changes the lines of the other ticks. Omitting it keeps
         * the trace of one run small enough for the test worker's heap.
         */
        private String snapshot(TraceBox trace) {
            final StringBuilder snapshot = new StringBuilder();
            for (int x = trace.minX(); x <= trace.maxX(); x++) {
                for (int y = trace.minY(); y <= trace.maxY(); y++) {
                    for (int z = trace.minZ(); z <= trace.maxZ(); z++) {
                        final BlockPos pos = new BlockPos(x, y, z);
                        final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                        final BlockEntity blockEntity = this.blockEntities.get(pos);
                        if (state.isAir() && blockEntity == null) {
                            continue;
                        }
                        snapshot.append(x).append(',').append(y).append(',').append(z).append('=').append(state);
                        if (blockEntity != null) {
                            snapshot.append(' ').append(describe(blockEntity));
                        }
                        snapshot.append('\n');
                    }
                }
            }
            return snapshot.toString();
        }

        private String describe(BlockEntity blockEntity) {
            if (blockEntity instanceof ComparatorBlockEntity comparator) {
                return "comparator[output=" + comparator.getOutputSignal() + "]";
            }
            if (blockEntity instanceof PistonMovingBlockEntity piston) {
                return "movingPiston[moved=" + piston.getMovedState()
                        + ",direction=" + piston.getDirection()
                        + ",extending=" + piston.isExtending()
                        + ",source=" + piston.isSourcePiston()
                        + ",progress=" + piston.getProgress(0.0F)
                        + ",lastTicked=" + piston.getLastTicked() + "]";
            }
            return blockEntity.getType().toString();
        }
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final java.lang.reflect.Field field = Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
