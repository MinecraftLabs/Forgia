/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What the compatibility layer knows about the running server: the declarations in effect, the detected
 * conflicts, the actions they took and the slices that were therefore left off.
 * <p>
 * The holder is filled once per server start, because that is when the engine is installed and when the
 * declaration files are read; {@code /neoforge regionthreading compat} prints it and the diagnostics line of
 * {@code /neoforge regionthreading status} carries its counts, so an admin can ask the server what it decided
 * instead of inferring it from the log. The record is reset when the server stops.
 */
@ApiStatus.Internal
public final class RegionThreadingCompatReport {
    private static final State INITIAL = new State(false, false, null,
            new RegionThreadingConflicts.Plan(List.of(), false, false, false, List.of()),
            RegionThreadingDeclarations.Report.empty());
    private static volatile State state = INITIAL;

    private RegionThreadingCompatReport() {}

    /**
     * One server start's compatibility verdict.
     *
     * @param requested       whether the configuration asked for the region threading bridge
     * @param engineInstalled whether the engine was installed
     * @param blockedBy       why the engine was not installed although it was requested, or {@code null}
     * @param plan            the plan the configuration and the known-conflict table produced
     * @param declarations    the declarations the declaration files produced
     */
    public record State(
            boolean requested,
            boolean engineInstalled,
            @Nullable String blockedBy,
            RegionThreadingConflicts.Plan plan,
            RegionThreadingDeclarations.Report declarations) {}

    /** {@return the compatibility state of the current server, or the empty state outside one} */
    public static State state() {
        return state;
    }

    /**
     * Records the verdict of one server start.
     *
     * @param requested       whether the configuration asked for the region threading bridge
     * @param engineInstalled whether the engine was installed
     * @param blockedBy       why the engine was not installed although it was requested, or {@code null}
     * @param plan            the plan of the known-conflict table and the configuration
     * @param declarations    the declarations of the declaration files
     */
    public static void record(boolean requested, boolean engineInstalled, @Nullable String blockedBy,
            RegionThreadingConflicts.Plan plan, RegionThreadingDeclarations.Report declarations) {
        state = new State(requested, engineInstalled, blockedBy, plan, declarations);
    }

    /** Drops the verdict of the stopped server, so a later server cannot report a stale one. */
    public static void reset() {
        state = INITIAL;
    }

    /** {@return the number of declarations in effect} */
    public static int declarationCount() {
        return state.declarations().declarationCount();
    }

    /** {@return the number of detected known conflicts} */
    public static int conflictCount() {
        return state.plan().conflicts().size();
    }

    /**
     * {@return the lines {@code /neoforge regionthreading compat} prints}
     * <p>
     * The summary comes first, then the declarations in effect, then the conflicts with their actions, and
     * last the limits of the detection. The same conflict lines are logged once at server start, so the log
     * and the command never disagree.
     */
    public static List<String> lines() {
        final State current = state;
        final List<String> lines = new ArrayList<>();
        lines.add("Region threading compat: requested=" + current.requested()
                + ", engineInstalled=" + current.engineInstalled()
                + ", blockedBy=" + (current.blockedBy() == null ? "none" : current.blockedBy())
                + ", declarations=" + current.declarations().declarationCount()
                + ", ignoredDeclarations=" + current.declarations().ignored().size()
                + ", conflicts=" + current.plan().conflicts().size()
                + ", leftOffSlices=" + current.plan().leftOffSlices());
        lines.addAll(current.declarations().lines());
        lines.addAll(current.plan().lines());
        lines.add(RegionThreadingConflicts.DETECTION_LIMITS);
        return lines;
    }
}
