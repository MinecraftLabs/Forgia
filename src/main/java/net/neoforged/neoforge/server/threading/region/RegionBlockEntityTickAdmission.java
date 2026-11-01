/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.jetbrains.annotations.ApiStatus;

/** An exact ticker binding and immutable position whose entity-load eligibility was checked by the server. */
@ApiStatus.Internal
public record RegionBlockEntityTickAdmission(ServerLevel level, TickingBlockEntity ticker, BlockPos position, String type, Binding binding) {

    public RegionBlockEntityTickAdmission {
        position = position.immutable();
    }

    /** Checks before invocation; a changed binding or position must use server-thread fallback. */
    public boolean isCurrent() {
        return this.binding.isCurrent().getAsBoolean()
                && this.position.equals(this.ticker.getPos())
                && this.position.equals(this.binding.delegate().getPos())
                && this.type.equals(this.ticker.getType());
    }
    /** The bound delegate is distinct from the level's wrapper; rebinding invalidates this capture. */
    public record Binding(TickingBlockEntity delegate, BooleanSupplier isCurrent) {}
}
