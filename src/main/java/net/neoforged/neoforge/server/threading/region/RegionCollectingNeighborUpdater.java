/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The vanilla {@link net.minecraft.world.level.redstone.CollectingNeighborUpdater} of one region context, with
 * border capture.
 * <p>
 * The collection stack, the deferred layer, the update order and the chained-update limit are exactly the
 * vanilla ones. The difference is the target of one queued update: before the vanilla body reads the target
 * state or calls it, the update asks {@link RegionThreadingHooks#deferBorderNeighborUpdate} whether the target
 * belongs to another region. A cross-region update is recorded as a {@link RegionBorderUpdates.BorderUpdate}
 * instead of being applied by the worker, so the target region applies it on its own worker after the worker
 * phase and the two regions never read or write each other concurrently.
 * <p>
 * The capture does not change the chained-update count: vanilla counts the queued update, not its six expanded
 * directions, and the capture happens inside the expansion, exactly where vanilla would have read the target.
 * A cascade therefore hits the same limit with the border capture on as it would with it off.
 */
@ApiStatus.Internal
final class RegionCollectingNeighborUpdater implements NeighborUpdater {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Level level;
    private final int maxChainedNeighborUpdates;
    private final ArrayDeque<NeighborUpdates> stack = new ArrayDeque<>();
    private final List<NeighborUpdates> addedThisLayer = new ArrayList<>();
    private int count;

    RegionCollectingNeighborUpdater(Level level, int maxChainedNeighborUpdates) {
        this.level = level;
        this.maxChainedNeighborUpdates = maxChainedNeighborUpdates;
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int updateFlags, int updateLimit) {
        this.addAndRun(pos, new ShapeUpdate(direction, state, pos.immutable(), neighborPos.immutable(), updateFlags, updateLimit));
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos neighborPos) {
        this.addAndRun(pos, new SimpleNeighborUpdate(pos, block, neighborPos.immutable()));
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) {
        this.addAndRun(pos, new FullNeighborUpdate(state, pos.immutable(), block, neighborPos.immutable(), movedByPiston));
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skipDirection) {
        this.addAndRun(pos, new MultiNeighborUpdate(pos.immutable(), block, skipDirection));
    }

    private void addAndRun(BlockPos pos, NeighborUpdates update) {
        final boolean flag = this.count > 0;
        final boolean atLimit = this.maxChainedNeighborUpdates >= 0 && this.count >= this.maxChainedNeighborUpdates;
        this.count++;
        if (!atLimit) {
            if (flag) {
                this.addedThisLayer.add(update);
            } else {
                this.stack.push(update);
            }
        } else if (this.count - 1 == this.maxChainedNeighborUpdates) {
            LOGGER.error("Too many chained neighbor updates. Skipping the rest. First skipped position: " + pos.toShortString());
        }
        if (!flag) {
            this.runUpdates();
        }
    }

    private void runUpdates() {
        try {
            while (!this.stack.isEmpty() || !this.addedThisLayer.isEmpty()) {
                for (int i = this.addedThisLayer.size() - 1; i >= 0; i--) {
                    this.stack.push(this.addedThisLayer.get(i));
                }
                this.addedThisLayer.clear();
                final NeighborUpdates update = this.stack.peek();
                while (this.addedThisLayer.isEmpty()) {
                    if (!update.runNext(this.level)) {
                        this.stack.pop();
                        break;
                    }
                }
            }
        } finally {
            this.stack.clear();
            this.addedThisLayer.clear();
            this.count = 0;
        }
    }

    private interface NeighborUpdates {
        boolean runNext(Level level);
    }

    private record FullNeighborUpdate(BlockState state, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) implements NeighborUpdates {
        @Override
        public boolean runNext(Level level) {
            if (RegionThreadingHooks.deferBorderNeighborUpdate(level, this.pos, this.block, this.neighborPos, this.movedByPiston)) {
                return false;
            }
            NeighborUpdater.executeUpdate(level, this.state, this.pos, this.block, this.neighborPos, this.movedByPiston);
            return false;
        }
    }

    private static final class MultiNeighborUpdate implements NeighborUpdates {
        private final BlockPos sourcePos;
        private final Block sourceBlock;
        @Nullable
        private final Direction skipDirection;
        private int idx;

        private MultiNeighborUpdate(BlockPos sourcePos, Block sourceBlock, @Nullable Direction skipDirection) {
            this.sourcePos = sourcePos;
            this.sourceBlock = sourceBlock;
            this.skipDirection = skipDirection;
            if (UPDATE_ORDER[this.idx] == skipDirection) {
                this.idx++;
            }
        }

        @Override
        public boolean runNext(Level level) {
            final BlockPos target = this.sourcePos.relative(UPDATE_ORDER[this.idx++]);
            if (!RegionThreadingHooks.deferBorderNeighborUpdate(level, target, this.sourceBlock, this.sourcePos, false)) {
                final BlockState state = level.getBlockState(target);
                NeighborUpdater.executeUpdate(level, state, target, this.sourceBlock, this.sourcePos, false);
            }
            if (this.idx < UPDATE_ORDER.length && UPDATE_ORDER[this.idx] == this.skipDirection) {
                this.idx++;
            }
            return this.idx < UPDATE_ORDER.length;
        }
    }

    private record ShapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int updateFlags,
            int updateLimit) implements NeighborUpdates {
        @Override
        public boolean runNext(Level level) {
            if (RegionThreadingHooks.deferBorderShapeUpdate(level, this.direction, this.state, this.pos, this.neighborPos, this.updateFlags, this.updateLimit)) {
                return false;
            }
            NeighborUpdater.executeShapeUpdate(level, this.direction, this.state, this.pos, this.neighborPos, this.updateFlags, this.updateLimit);
            return false;
        }
    }

    private record SimpleNeighborUpdate(BlockPos pos, Block block, BlockPos neighborPos) implements NeighborUpdates {
        @Override
        public boolean runNext(Level level) {
            if (RegionThreadingHooks.deferBorderNeighborUpdate(level, this.pos, this.block, this.neighborPos, false)) {
                return false;
            }
            final BlockState state = level.getBlockState(this.pos);
            NeighborUpdater.executeUpdate(level, state, this.pos, this.block, this.neighborPos, false);
            return false;
        }
    }
}
