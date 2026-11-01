/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Neighbor updater of a level whose update collection state is isolated per region execution context.
 * <p>
 * Outside a region context every call goes through one shared {@link CollectingNeighborUpdater}, which
 * keeps the vanilla server-thread behavior and its single update stack. Work that executes inside a
 * region context gets its own delegate, so a region worker can neither clear nor extend the update
 * batch of another worker or of the server thread. Each delegate keeps the vanilla update order and
 * applies the level's {@code maxChainedNeighborUpdates} limit to its own cascade.
 */
@ApiStatus.Internal
public final class RegionAwareNeighborUpdater implements NeighborUpdater {
    private final Level level;
    private final int maxChainedNeighborUpdates;
    private final CollectingNeighborUpdater sharedUpdater;

    public RegionAwareNeighborUpdater(Level level, int maxChainedNeighborUpdates) {
        this.level = level;
        this.maxChainedNeighborUpdates = maxChainedNeighborUpdates;
        this.sharedUpdater = new CollectingNeighborUpdater(level, maxChainedNeighborUpdates);
    }

    /**
     * {@return true when the calling thread executes a region-scoped neighbor update context}
     */
    @ApiStatus.Internal
    public static boolean isRegionScoped() {
        return RegionNeighborUpdateScope.current() != null;
    }

    private NeighborUpdater updater() {
        final RegionNeighborUpdateScope scope = RegionNeighborUpdateScope.current();
        return scope == null ? this.sharedUpdater : scope.updaterFor(this);
    }

    CollectingNeighborUpdater newRegionUpdater() {
        return new CollectingNeighborUpdater(this.level, this.maxChainedNeighborUpdates);
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int updateFlags, int updateLimit) {
        this.updater().shapeUpdate(direction, state, pos, neighborPos, updateFlags, updateLimit);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos neighborPos) {
        this.updater().neighborChanged(pos, block, neighborPos);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) {
        this.updater().neighborChanged(state, pos, block, neighborPos, movedByPiston);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skipDirection) {
        this.updater().updateNeighborsAtExceptFromFacing(pos, block, skipDirection);
    }
}
