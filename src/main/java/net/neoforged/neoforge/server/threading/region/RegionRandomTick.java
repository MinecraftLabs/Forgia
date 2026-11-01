/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * One random tick that was selected on the server thread and has to run its callback.
 */
@ApiStatus.Internal
record RegionRandomTick(BlockPos pos, RegionRandomTickCallback callback) {}
