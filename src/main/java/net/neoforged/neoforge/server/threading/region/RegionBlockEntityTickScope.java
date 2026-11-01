/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Admission scope of exactly one block-entity invocation on a region worker.
 * <p>
 * The server checks entity-load eligibility before collecting the immutable admission. Only that
 * ticker and its captured bound delegate may replace the guarded manager read, while the binding,
 * position, level and region lease still match. Siblings and same-position replacements are excluded.
 * <p>
 * This relies on the current server phase: manager tick, save, unload and close do not run during an
 * active block-entity batch; its barrier services chunk-source tasks. Both manager tick and saving
 * ({@code autoSave}/{@code saveAll}) can unload a chunk and remove its {@code LOADED} status. This
 * scope does not make arbitrary future reentrant manager saving safe.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickScope implements AutoCloseable {
    private static final ThreadLocal<RegionBlockEntityTickScope> CURRENT = new ThreadLocal<>();

    private final RegionBlockEntityTickAdmission admission;
    private final RegionState region;
    @Nullable
    private final RegionBlockEntityTickScope previous;

    private RegionBlockEntityTickScope(RegionBlockEntityTickAdmission admission, RegionState region) {
        this.admission = admission;
        this.region = region;
        this.previous = CURRENT.get();
    }

    static RegionBlockEntityTickScope open(RegionBlockEntityTickAdmission admission, RegionState region) {
        final RegionBlockEntityTickScope scope = new RegionBlockEntityTickScope(admission, region);
        CURRENT.set(scope);
        return scope;
    }

    /** {@return whether this exact invocation can replace its server-owned entity-load read} */
    public static boolean isAdmitted(ServerLevel level, @Nullable TickingBlockEntity ticker, BlockPos pos) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            return false;
        }
        final RegionBlockEntityTickScope scope = CURRENT.get();
        return scope != null
                && scope.admission.level() == level
                && (scope.admission.ticker() == ticker || scope.admission.binding().delegate() == ticker)
                && scope.admission.position().equals(pos)
                && scope.admission.isCurrent()
                && scope.region.isOwnedByCurrentThread();
    }

    @Override
    public void close() {
        if (this.previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(this.previous);
        }
    }
}
