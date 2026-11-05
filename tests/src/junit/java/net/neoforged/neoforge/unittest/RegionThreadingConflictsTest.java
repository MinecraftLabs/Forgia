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
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("create"));

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
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("mobspawncontroller"));

        assertTrue(plan.installEngine());
        assertTrue(plan.entityTickWorkers());
        assertFalse(plan.spawnWorkers(), "the mod owns the spawn state the spawning slice shares with the server");
        assertEquals(List.of("spawning"), plan.leftOffSlices());
        assertEquals(RegionThreadingConflicts.Action.DISABLE_SPAWNING, plan.conflicts().get(0).action());
    }

    @Test
    void aSliceTheConfigurationDidNotAskForIsNotReportedAsLeftOff() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, false, false, loaded("create"));

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
        for (final RegionThreadingConflicts.Conflict conflict : RegionThreadingConflicts.table()) {
            assertNotNull(conflict.kind(), conflict.modId());
            assertNotNull(conflict.action(), conflict.modId());
            assertFalse(conflict.reason().isBlank(), conflict.modId());
            assertTrue(modIds.add(conflict.modId()), "duplicate table row for " + conflict.modId());
            coveredActions.add(conflict.action());
            assertEquals(conflict.kind().action(), conflict.action(),
                    "the action of a row is the fail-closed action of its kind, not a per row choice");
        }
        assertEquals(Set.of(RegionThreadingConflicts.Action.values()), coveredActions,
                "every action is reachable from a row of the table");
    }

    @Test
    void theCommandReportsTheConflictsTheActionAndTheSlicesLeftOff() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, loaded("create"));
        RegionThreadingCompatReport.record(true, true, null, plan, RegionThreadingDeclarations.Report.empty());

        final List<String> lines = RegionThreadingCompatReport.lines();
        assertTrue(lines.get(0).contains("engineInstalled=true"), lines.get(0));
        assertTrue(lines.get(0).contains("conflicts=1"), lines.get(0));
        assertTrue(lines.get(0).contains("leftOffSlices=[entityTicks]"), lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.contains("create (ENTITY_STORAGE")
                && line.contains("the entity tick worker slice stays on the server thread")),
                lines.toString());
        assertEquals(RegionThreadingConflicts.DETECTION_LIMITS, lines.get(lines.size() - 1),
                "the report ends with what it cannot detect");
    }

    @Test
    void theCommandOfAServerWithoutConflictsStillStatesTheDetectionLimits() {
        final RegionThreadingConflicts.Plan plan = RegionThreadingConflicts.plan(true, true, true, NO_MODS);
        RegionThreadingCompatReport.record(true, true, null, plan, RegionThreadingDeclarations.Report.empty());

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
