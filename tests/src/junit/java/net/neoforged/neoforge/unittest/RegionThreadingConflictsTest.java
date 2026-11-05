/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatReport;
import net.neoforged.neoforge.server.threading.RegionThreadingConflicts;
import net.neoforged.neoforge.server.threading.RegionThreadingDeclarations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the known-conflict table of the mod compatibility layer.
 * <p>
 * The loaded-mod question is the injected one, so a test can simulate a known mod without installing it: the
 * table, the plan and the report lines are the production ones, only the mod list is answered differently.
 */
class RegionThreadingConflictsTest {
    private static final Predicate<String> NO_MODS = modId -> false;

    @AfterEach
    void reset() {
        RegionThreadingCompatReport.reset();
    }

    @Test
    void noKnownModInstallsTheRequestedEngineAndLeavesNoSliceOff() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, NO_MODS);

        assertTrue(plan.installEngine());
        assertTrue(plan.entityTickWorkers());
        assertTrue(plan.spawnWorkers());
        assertTrue(plan.conflicts().isEmpty());
        assertTrue(plan.leftOffSlices().isEmpty(), "a slice the configuration did not ask for is not left off");
        assertTrue(plan.lines().isEmpty(), "nothing is logged when no known mod is present");
    }

    @Test
    void aForcedPlanKeepsEverySliceAndStillReportsTheConflicts() {
        // The force flag exists for the compatibility harness, which has to observe what a conflicting mod
        // actually does instead of trusting the table. It must not hide the conflict, only ignore its action.
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("c2me"), true);

        assertTrue(plan.installEngine(), "a forced plan still installs the engine");
        assertTrue(plan.entityTickWorkers(), "a forced plan keeps the entity slice");
        assertTrue(plan.spawnWorkers(), "a forced plan keeps the spawning slice");
        assertEquals(1, plan.conflicts().size(), "the conflict is still detected and reported");
        assertTrue(plan.leftOffSlices().isEmpty(), "a forced plan leaves no slice off");
    }

    @Test
    void aChunkSourceConflictRefusesTheEngineAndTheServerKeepsTicking() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("c2me"));

        assertFalse(plan.installEngine(), "a mod that owns the chunk source makes every worker slice unsafe");
        assertFalse(plan.entityTickWorkers());
        assertFalse(plan.spawnWorkers());
        assertEquals(List.of("engine"), plan.leftOffSlices());
        assertEquals(1, plan.conflicts().size());
        assertEquals(RegionThreadingConflicts.Action.DISABLE_ENGINE, plan.conflicts().get(0).action());
        // The verdict is a statement, not an exception: the server starts on the vanilla server-thread tick.
        final List<String> lines = plan.lines();
        assertEquals(2, lines.size(), lines.toString());
        assertEquals(1, lines.stream().filter(line -> line.contains("c2me")).count(), "one line per conflict");
        assertTrue(lines.get(0).contains("CHUNK_SOURCE"), lines.get(0));
        assertTrue(lines.get(0).contains("the region threading engine is not installed"), lines.get(0));
        assertTrue(lines.get(1).contains("Region threading engine disabled"), lines.get(1));
    }

    @Test
    void anEntityStorageConflictLeavesOnlyTheEntitySliceOff() {
        // The shipped table marks create as measured compatible, so the fail-closed entity action is exercised
        // with a table of its own.
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("create"),
                false, List.of(new RegionThreadingConflicts.Conflict("create",
                        RegionThreadingConflicts.Kind.ENTITY_STORAGE, "test row")));

        assertTrue(plan.installEngine(), "the rest of the engine does not reach the entity tick list");
        assertFalse(plan.entityTickWorkers(), "the entity slice changes the semantics the mod patches");
        assertTrue(plan.spawnWorkers());
        assertEquals(List.of("entityTicks"), plan.leftOffSlices());
        final List<String> lines = plan.lines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("ENTITY_STORAGE"), lines.get(0));
        assertTrue(lines.get(0).contains("entity tick worker slice stays on the server thread"), lines.get(0));
        assertTrue(lines.get(1).contains("left off by compatibility: [entityTicks]"), lines.get(1));
    }

    @Test
    void aLevelWideStateConflictLeavesOnlyTheSpawningSliceOff() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("mobspawncontroller"),
                false, List.of(new RegionThreadingConflicts.Conflict("mobspawncontroller",
                        RegionThreadingConflicts.Kind.LEVEL_WIDE_STATE, "test row")));

        assertTrue(plan.installEngine());
        assertTrue(plan.entityTickWorkers());
        assertFalse(plan.spawnWorkers(), "the mod owns the spawn state the spawning slice shares with the server");
        assertEquals(List.of("spawning"), plan.leftOffSlices());
        assertEquals(RegionThreadingConflicts.Action.DISABLE_SPAWNING, plan.conflicts().get(0).action());
    }

    @Test
    void aSliceTheConfigurationDidNotAskForIsNotReportedAsLeftOff() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, false, false, loaded("create"),
                false, List.of(new RegionThreadingConflicts.Conflict("create",
                        RegionThreadingConflicts.Kind.ENTITY_STORAGE, "test row")));

        assertTrue(plan.installEngine());
        assertFalse(plan.entityTickWorkers());
        assertEquals(1, plan.conflicts().size());
        assertTrue(plan.leftOffSlices().isEmpty(), plan.leftOffSlices().toString());
        assertEquals(1, plan.lines().size(), "only the conflict line, no summary of a slice that was never requested");
    }

    @Test
    void aDisabledEngineLeavesEverySliceOffAndThePlanDoesNotClaimOtherwise() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(false, true, true, loaded("c2me"));

        assertFalse(plan.installEngine());
        assertFalse(plan.entityTickWorkers());
        assertFalse(plan.spawnWorkers());
        assertTrue(plan.leftOffSlices().isEmpty(), "the engine was not requested, so compatibility left nothing off");
    }

    @Test
    void everyTableRowIsWellFormedAndNamesAKnownHazard() {
        final Set<String> modIds = new HashSet<>();
        final Set<RegionThreadingConflicts.Action> coveredActions = new HashSet<>();
        final Set<RegionThreadingConflicts.Kind> coveredKinds = new HashSet<>();
        for (final RegionThreadingConflicts.Conflict conflict : RegionThreadingConflicts.table()) {
            assertNotNull(conflict.kind(), conflict.modId());
            assertNotNull(conflict.action(), conflict.modId());
            assertFalse(conflict.reason().isBlank(), conflict.modId());
            assertTrue(modIds.add(conflict.modId()), "duplicate table row for " + conflict.modId());
            coveredActions.add(conflict.action());
            coveredKinds.add(conflict.kind());
            // A row either carries the fail-closed action of its kind, or it is REPORT_ONLY because the
            // compatibility harness ran that mod with the engine and saw no violation - in which case the reason
            // has to say so, so a downgraded row can never be mistaken for an unanalysed one.
            if (conflict.action() == RegionThreadingConflicts.Action.REPORT_ONLY) {
                assertTrue(conflict.reason().contains("measured compatible"),
                        "a report-only row has to say it was measured: " + conflict.modId());
            } else {
                assertEquals(conflict.kind().action(), conflict.action(),
                        "the action of a row is the fail-closed action of its kind unless it was measured");
            }
        }
        // The rows the harness verified carry REPORT_ONLY, so the per-kind fail-closed actions are covered by the
        // kind tests instead; what the table itself has to cover is every kind of hazard.
        assertEquals(Set.of(RegionThreadingConflicts.Kind.values()), coveredKinds,
                "every kind of hazard is reachable from a row of the table");
        assertTrue(coveredActions.contains(RegionThreadingConflicts.Action.DISABLE_ENGINE),
                "the table still blocks the mods the evidence did not clear");
    }

    @Test
    void theCommandReportsTheConflictsTheActionAndTheSlicesLeftOff() {
        // c2me is the one row the evidence still blocks, so it is what the command report is checked against.
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("c2me"));
        RegionThreadingCompatReport.record(true, plan.installEngine(), null, plan, RegionThreadingDeclarations.Report.empty());

        final List<String> lines = RegionThreadingCompatReport.lines();
        assertTrue(lines.get(0).contains("engineInstalled=false"), lines.get(0));
        assertTrue(lines.get(0).contains("conflicts=1"), lines.get(0));
        assertTrue(lines.get(0).contains("leftOffSlices=[engine]"), lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.contains("c2me (CHUNK_SOURCE")
                && line.contains("the region threading engine is not installed")),
                lines.toString());
        assertEquals(RegionThreadingConflicts.DETECTION_LIMITS, lines.get(lines.size() - 1),
                "the report ends with what it cannot detect");
    }

    @Test
    void theCommandOfAServerWithoutConflictsStillStatesTheDetectionLimits() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, NO_MODS);
        RegionThreadingCompatReport.record(true, plan.installEngine(), null, plan, RegionThreadingDeclarations.Report.empty());

        final List<String> lines = RegionThreadingCompatReport.lines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("conflicts=0"), lines.get(0));
        assertTrue(lines.get(0).contains("declarations=0"), lines.get(0));
        assertEquals(RegionThreadingConflicts.DETECTION_LIMITS, lines.get(1));
    }

    private static Predicate<String> loaded(String... modIds) {
        final Set<String> loaded = Set.of(modIds);
        return loaded::contains;
    }
}
