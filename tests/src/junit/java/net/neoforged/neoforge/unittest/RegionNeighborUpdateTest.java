/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the per-context neighbor update collection state of
 * {@link RegionAwareNeighborUpdater} with real {@code CollectingNeighborUpdater} cascades.
 * <p>
 * Two region execution contexts that run the same kind of nested update chain at the same time must
 * keep their own update order and their own chained-update budget. The cascades meet at a rendezvous
 * in the middle of the chain, so both threads are inside their collection loop at the same time.
 */
@Timeout(30)
class RegionNeighborUpdateTest {
    private static final int MAX_CHAINED_UPDATES = 3;
    private static final int CHAIN_LENGTH = 5;

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void mainThreadKeepsOneSharedUpdateCascade() {
        final ServerLevel level = mock(ServerLevel.class);
        final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
        installBlockStates(level, states);
        final Block block = mock(Block.class);
        final RegionAwareNeighborUpdater updater = new RegionAwareNeighborUpdater(level, MAX_CHAINED_UPDATES);

        final Cascade first = new Cascade("first", 1, 1);
        first.install(updater, block, states);
        first.run(updater, block);
        assertEquals(List.of("first:0", "first:1", "first:2"), first.executed,
                "the shared cascade has to apply the chained-update limit of its own cascade");

        // A second cascade on the same thread reuses the shared updater but starts a fresh count.
        final Cascade second = new Cascade("second", 17, 17);
        second.install(updater, block, states);
        second.run(updater, block);
        assertEquals(List.of("second:0", "second:1", "second:2"), second.executed,
                "the shared updater has to reset the chained-update count when a cascade finishes");
        assertFalse(RegionAwareNeighborUpdater.isRegionScoped(), "the server thread is not a region context");
    }

    @Test
    void concurrentRegionContextsKeepIndependentUpdateCascades() throws Exception {
        this.engine = new RegionThreadingEngine(2, false, false);
        RegionThreadingManager.install(this.engine);
        final ServerLevel level = mock(ServerLevel.class);
        final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
        installBlockStates(level, states);
        final Block block = mock(Block.class);
        final RegionAwareNeighborUpdater updater = new RegionAwareNeighborUpdater(level, MAX_CHAINED_UPDATES);
        final CyclicBarrier rendezvous = new CyclicBarrier(2);
        final Cascade first = new Cascade("first", 1, 1, 1, rendezvous);
        final Cascade second = new Cascade("second", 257, 257, 1, rendezvous);
        first.install(updater, block, states);
        second.install(updater, block, states);

        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<?> firstRun = executor.submit(() -> runRegionCascade(level, new BlockPos(1, 64, 1), updater, block, first));
            final Future<?> secondRun = executor.submit(() -> runRegionCascade(level, new BlockPos(257, 64, 257), updater, block, second));
            firstRun.get(20, TimeUnit.SECONDS);
            secondRun.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(List.of("first:0", "first:1", "first:2"), first.executed,
                "a region context has to keep its own update order and chained-update count");
        assertEquals(List.of("second:0", "second:1", "second:2"), second.executed,
                "a concurrent region context must not clear or extend the other cascade");
        assertFalse(RegionAwareNeighborUpdater.isRegionScoped(), "the scope has to be removed when the contexts end");

        // The server thread keeps using the shared updater after the region contexts finished.
        final Cascade main = new Cascade("main", 33, 33);
        main.install(updater, block, states);
        main.run(updater, block);
        assertEquals(List.of("main:0", "main:1", "main:2"), main.executed);
    }

    private void runRegionCascade(ServerLevel level, BlockPos pos, RegionAwareNeighborUpdater updater, Block block, Cascade cascade) {
        this.engine.runInBlockRegion(level, pos, () -> {
            assertTrue(RegionAwareNeighborUpdater.isRegionScoped(), "a region execution context has to own an update scope");
            cascade.run(updater, block);
        });
        assertFalse(RegionAwareNeighborUpdater.isRegionScoped(), "the update scope has to be removed when the region context ends");
    }

    private static void installBlockStates(ServerLevel level, Map<BlockPos, BlockState> states) {
        final BlockState air = mock(BlockState.class);
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> states.getOrDefault(invocation.getArgument(0), air));
    }

    private static void awaitRendezvous(@Nullable CyclicBarrier rendezvous) {
        if (rendezvous == null) {
            return;
        }
        try {
            rendezvous.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * One nested update chain: every executed neighbor update schedules the next update, and the last
     * updates beyond {@link #MAX_CHAINED_UPDATES} are skipped by the collecting updater.
     */
    private static final class Cascade {
        private final String tag;
        private final BlockPos[] positions = new BlockPos[CHAIN_LENGTH];
        private final List<String> executed = new CopyOnWriteArrayList<>();
        private final AtomicInteger updates = new AtomicInteger();
        private final BlockState state = mock(BlockState.class);
        private final int barrierAfter;
        @Nullable
        private final CyclicBarrier rendezvous;

        Cascade(String tag, int baseX, int baseZ) {
            this(tag, baseX, baseZ, -1, null);
        }

        Cascade(String tag, int baseX, int baseZ, int barrierAfter, @Nullable CyclicBarrier rendezvous) {
            this.tag = tag;
            this.barrierAfter = barrierAfter;
            this.rendezvous = rendezvous;
            for (int i = 0; i < this.positions.length; i++) {
                this.positions[i] = new BlockPos(baseX, 64, baseZ + i);
            }
        }

        void install(RegionAwareNeighborUpdater updater, Block block, Map<BlockPos, BlockState> states) {
            for (final BlockPos pos : this.positions) {
                states.put(pos, this.state);
            }
            doAnswer(invocation -> {
                final int index = this.updates.getAndIncrement();
                this.executed.add(this.tag + ":" + index);
                if (index == this.barrierAfter) {
                    awaitRendezvous(this.rendezvous);
                }
                if (index + 1 < this.positions.length) {
                    updater.neighborChanged(this.positions[index + 1], block, this.positions[index]);
                }
                return null;
            }).when(this.state).handleNeighborChanged(any(), any(), any(), any(), anyBoolean());
        }

        void run(RegionAwareNeighborUpdater updater, Block block) {
            updater.neighborChanged(this.positions[0], block, this.positions[0].relative(Direction.WEST));
        }
    }
}
