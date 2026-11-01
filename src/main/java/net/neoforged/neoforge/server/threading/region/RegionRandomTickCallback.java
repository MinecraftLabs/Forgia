/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.ApiStatus;

/**
 * One selected block or fluid random tick callback.
 * <p>
 * The position and the random source are supplied by the region tick batch: a worker group passes its
 * region-owned source, while a callback that stays on the server thread receives the level random. The
 * block or fluid state itself is captured on the server thread before the callback is collected.
 */
@FunctionalInterface
@ApiStatus.Internal
public interface RegionRandomTickCallback {
    void randomTick(ServerLevel level, BlockPos pos, RandomSource random);
}
