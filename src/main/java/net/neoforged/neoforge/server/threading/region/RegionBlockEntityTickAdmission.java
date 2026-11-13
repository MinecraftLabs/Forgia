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

/**
 * An exact ticker binding and immutable position whose entity-load eligibility was checked by the server.
 * <p>
 * The admission also carries the decision the server thread took for this ticker when it collected the plan:
 * whether the compatibility declaration admits the ticker to a worker tick. It is evaluated once per ticker
 * and reused while the chunk's snapshot is current (see {@link RegionBlockEntityTickChunkCache}), so the
 * batch does not re-evaluate the allowlist and the interior-chunk rule of every ticker on every tick.
 * <p>
 * {@code sequence} is the creation order of the ticker, so a worker group can tick the admissions of its
 * chunks in the order the level's own ticker list would have ticked them.
 */
@ApiStatus.Internal
public record RegionBlockEntityTickAdmission(
        ServerLevel level,
        TickingBlockEntity ticker,
        BlockPos position,
        String type,
        Binding binding,
        Decision decision,
        long sequence) {

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

    /**
     * Checks the part of the binding the plan re-validates for every collected ticker of every tick.
     * <p>
     * The plan reuses the cached admission of a chunk, so it only has to prove that the ticker is still the
     * one that was captured and still sits where it was captured. The type is not read again: the type of a
     * block entity instance never changes, and a delegate or type change is a rebind, which bumps the
     * ticker revision of the chunk and therefore rebuilds the snapshot the admission comes from (see
     * {@link RegionBlockEntityTickChunkCache}). The position of the bound delegate is not read either: a
     * wrapper delegates {@code getPos()} to exactly that delegate, so the check below covers both. The
     * worker-side admission keeps the full {@link #isCurrent()}, which also compares the type name.
     */
    public boolean isCurrentForCollection() {
        return this.binding.isCurrent().getAsBoolean() && this.position.equals(this.ticker.getPos());
    }

    /** {@return true when the compatibility declaration admits this ticker to a region worker} */
    public boolean isAdmitted() {
        return this.decision == Decision.ADMITTED;
    }
    /** The bound delegate is distinct from the level's wrapper; rebinding invalidates this capture. */
    public record Binding(TickingBlockEntity delegate, BooleanSupplier isCurrent) {}

    /**
     * The server-thread admission decision of one block-entity ticker.
     */
    public enum Decision {
        /**
         * The ticker's type is declared region-safe and its chunk is an interior chunk of its region, so the
         * ticker may tick on the region worker of its region.
         */
        ADMITTED,
        /**
         * The ticker's type is not declared region-safe, so the whole region of the ticker keeps its
         * tickers on the server thread after the batch.
         */
        TYPE_NOT_DECLARED,
        /**
         * The ticker's type is declared region-safe, but its chunk is a boundary chunk of its region: a tick
         * that writes into the chunks around its position could leave the lease, so the whole region of the
         * ticker keeps its tickers on the server thread after the batch.
         */
        NOT_INTERIOR_CHUNK
    }
}
