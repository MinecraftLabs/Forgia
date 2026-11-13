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
 * <p>
 * One scope object is kept per worker thread and reused for the next ticker of the same thread: a
 * ticker opens and closes a scope, and allocating one object plus replacing the thread-local entry for
 * every ticker was a measurable part of the per-ticker worker cost. A nested open - a ticker that starts
 * another admitted ticker - still gets its own object linked to the enclosing one, exactly like before.
 */
@ApiStatus.Internal
public final class RegionBlockEntityTickScope implements AutoCloseable {
    private static final ThreadLocal<RegionBlockEntityTickScope> CURRENT = new ThreadLocal<>();

    @Nullable
    private RegionBlockEntityTickAdmission admission;
    @Nullable
    private RegionState region;
    /** Whether this scope is the admission of the ticker that is running right now. */
    private boolean active;
    /** The scope this one was opened inside of, or {@code null} for the outermost one. */
    @Nullable
    private RegionBlockEntityTickScope previous;

    private RegionBlockEntityTickScope(RegionBlockEntityTickAdmission admission, RegionState region, @Nullable RegionBlockEntityTickScope previous) {
        this.admission = admission;
        this.region = region;
        this.active = true;
        this.previous = previous;
    }

    static RegionBlockEntityTickScope open(RegionBlockEntityTickAdmission admission, RegionState region) {
        final RegionBlockEntityTickScope current = CURRENT.get();
        if (current != null && !current.active) {
            // The scope of the previous ticker of this thread already closed, so it is idle and can be reused:
            // the thread-local entry stays the same object for the whole batch.
            current.admission = admission;
            current.region = region;
            current.active = true;
            current.previous = null;
            return current;
        }
        final RegionBlockEntityTickScope scope = new RegionBlockEntityTickScope(admission, region, current);
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
                && scope.active
                && scope.admission.level() == level
                && (scope.admission.ticker() == ticker || scope.admission.binding().delegate() == ticker)
                && scope.admission.position().equals(pos)
                && scope.admission.isCurrent()
                && scope.region.isOwnedByCurrentThread();
    }

    /**
     * {@return true when the calling thread is inside the body of an admitted block-entity ticker whose
     * attribution is enabled}
     * <p>
     * The region-scoped entity query uses this to time itself against the ticker attribution. A disabled
     * attribution answers after one volatile read, so an unmeasured server pays nothing for it.
     */
    static boolean isAttributingTickerBody() {
        if (!RegionBlockEntityTickAttribution.isEnabled()) {
            return false;
        }
        final RegionBlockEntityTickScope scope = CURRENT.get();
        return scope != null && scope.active;
    }

    @Override
    public void close() {
        this.active = false;
        this.admission = null;
        this.region = null;
        final RegionBlockEntityTickScope parent = this.previous;
        this.previous = null;
        if (parent != null) {
            CURRENT.set(parent);
        }
        // Without a parent the finished scope stays installed and the next open of this thread reuses it.
    }
}
