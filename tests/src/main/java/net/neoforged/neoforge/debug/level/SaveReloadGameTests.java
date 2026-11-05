/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

/**
 * Proves that a world built with region threading enabled survives a save and a reload, and that a hard kill
 * leaves a world that loads with the state of its last save point.
 * <p>
 * The two tests are one milestone with two halves:
 * <ul>
 * <li>{@link #savesAndReloadsRegionThreadingState} builds a quiescent but rich fixture - blocks, block
 * entities with contents, villagers that claimed a bed and a workstation, animals, a POI set with taken
 * tickets and a redstone circuit that crosses a region border - saves the level, unloads every chunk of the
 * area and loads it back from disk, then compares every block, every block entity, every entity UUID and
 * position, every POI record with its free-ticket count and the region every position maps to. It runs in the
 * default GameTest batch with region threading on and, through the {@code saveReload} reference run, with
 * region threading off: the reference run compares its own observable state with the state the on-run
 * recorded, which is the "saved with regions on, loaded with regions off" half of the guarantee.</li>
 * <li>{@link #recoversAfterAHardKill} is driven by an external kill. The {@code prepare} phase builds the
 * fixture, saves, records the state of that save point and then halts the JVM from another thread while the
 * server is ticking, which is a hard kill with no shutdown hook and no flush of anything after the save. The
 * {@code recover} phase starts on the same world directory, asserts that the world loads, that the state of
 * the save point is intact, that no entity UUID was duplicated, that the identity ledger is rebuilt from the
 * level instead of remembering anything from the killed process, that no POI ticket leaked, and then runs the
 * invariants for another {@value #RECOVERY_TICKS} ticks.</li>
 * </ul>
 * <h2>Why the fixture is quiescent</h2>
 * The comparison needs a state that does not move between the moment it is captured and the moment it is
 * checked again. Region threading does not change what a save observes, but the game keeps ticking across an
 * unload and a reload, so every mob of the fixture is switched to {@code NoAI} once the villagers claimed
 * their POIs: their positions and their claims stay, no crop grows (the GameTest server runs with random ticks
 * off anyway), the chests and barrels are not connected to a hopper and the redstone circuit is powered by a
 * lever, which is a state and not a clock. Everything the fixture writes is recorded, so the comparison is a
 * comparison of exactly the positions the fixture owns.
 */
@ForEachTest(groups = "level.region")
public class SaveReloadGameTests {
    /**
     * System property of the save/reload report directory, relative to the game directory. The threading-on run
     * writes the observable state it reloaded; the reference run reads it and compares.
     */
    static final String REPORT_PROPERTY = "neoforge.saveReloadReport";
    /** System property of the reference run: the state file the threading-on run wrote. */
    static final String REFERENCE_PROPERTY = "neoforge.saveReloadReference";
    /**
     * System property that selects the region threading configuration of the run. The reference run sets it to
     * {@code false} and fails if a bridge is installed anyway, because "region threading off" means the vanilla
     * code path and nothing else.
     */
    static final String REGION_THREADING_PROPERTY = "neoforge.saveReloadRegionThreading";
    /** System property of the hard-kill test: {@code prepare}, {@code recover}, or unset to skip the test. */
    static final String CRASH_PHASE_PROPERTY = "neoforge.crashRecovery";
    /** System property of the hard-kill test: the state file the prepare phase writes. */
    static final String CRASH_STATE_PROPERTY = "neoforge.crashRecoveryState";
    /** System property of the hard-kill test: the ticks the prepare phase ticks before it kills the process. */
    static final String CRASH_KILL_TICKS_PROPERTY = "neoforge.crashRecoveryKillTicks";

    /** Region edge length in chunks of the production layout. */
    private static final int REGION_CHUNK_SIZE = 3;
    /** First chunk of the area. Chunk {@code 60} is the first chunk of a 3-chunk region. */
    private static final int ORIGIN_CHUNK_X = 60;
    private static final int ORIGIN_CHUNK_Z = 60;
    /** Edge length of the area in chunks: five chunks, so a region border crosses it in both axes. */
    private static final int AREA_CHUNK_SIDE = 5;
    /** Chunk ticket distance that makes a chunk entity-ticking, i.e. the level ticks its entities. */
    private static final int ENTITY_TICKING_TICKET_RADIUS = 2;
    /**
     * Ticks between building the fixture and booking its tickets: the POI record of a placed block is added by
     * the server task queue of the next tick, not by the placement itself.
     */
    private static final int POI_SETTLE_TICKS = 4;
    /** Ticks between the save and the point the state is compared at. */
    private static final int RELOAD_SETTLE_TICKS = 2;
    /** Ticks the recovered world has to keep its invariants after a hard kill. */
    private static final int RECOVERY_TICKS = 600;
    /** Ticks the prepare phase keeps ticking after its save point before it kills the process. */
    private static final int DEFAULT_KILL_TICKS = 40;
    /** Villagers of the fixture; each owns a bed and a workstation, so each claims two POI records. */
    private static final int VILLAGER_COUNT = 3;
    /** Animals of the fixture. */
    private static final int COW_COUNT = 6;
    /** Wall time of one state capture, reported by the run. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    @GameTest(batch = "saveReload", timeoutTicks = 900)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Saves a world with region threading enabled, unloads it, reloads it from disk and compares every block, block entity, entity, POI record and region mapping")
    static void savesAndReloadsRegionThreadingState(final DynamicTest test) {
        test.onGameTest(helper -> {
            final ServerLevel level = helper.getLevel();
            final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
            assertConfiguration(helper, engine);
            final String profile = engine.isPresent() ? "on" : "off";
            final List<ChunkPos> grid = areaGrid();
            final Fixture world = new Fixture(level, grid);
            final AtomicReference<FixtureState> beforeSave = new AtomicReference<>();
            final AtomicReference<FixtureState> afterReload = new AtomicReference<>();
            final AtomicReference<Long> saveNanos = new AtomicReference<>();
            final AtomicReference<Long> reloadNanos = new AtomicReference<>();
            final GameRules.BooleanValue mobSpawning = level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
            final boolean previousMobSpawning = mobSpawning.get();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            final long previousDayTime = level.getDayTime();
            helper.addEndListener(passed -> {
                world.discard();
                releaseChunks(level, grid);
                mobSpawning.set(previousMobSpawning, level.getServer());
                daylight.set(previousDaylight, level.getServer());
                level.setDayTime(previousDayTime);
            });

            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the save/reload chunk grid never finished its entity load"))
                    .thenExecute(() -> {
                        // The GameTest server runs with mob spawning off and on peaceful; the fixture turns
                        // both off and restores them afterwards, so nothing spawns among the content.
                        mobSpawning.set(false, level.getServer());
                        daylight.set(false, level.getServer());
                        level.setDayTime(18000L);
                        world.build();
                        // The villagers are frozen right after they are added. A claim of the villager brain
                        // depends on the level random of the tick that draws it, and region threading
                        // deliberately gives every region its own tick random, so a brain claim is not the same
                        // in the on and off runs and could not be compared. The fixture books its tickets
                        // through the same PoiManager.take the brain uses, one tick after the blocks land.
                        world.freeze();
                    })
                    .thenIdle(POI_SETTLE_TICKS)
                    .thenExecute(() -> {
                        final int taken = world.takeTickets();
                        System.out.println("SAVE_RELOAD_NOTE fixture: " + taken + " tickets taken by the fixture, "
                                + world.claimedTickets() + " tickets taken in the area");
                        helper.assertTrue(world.claimedTickets() > 0,
                                "the save point holds no taken POI ticket to reload");
                    })
                    .thenIdle(RELOAD_SETTLE_TICKS)
                    .thenExecute(() -> {
                        beforeSave.set(world.capture());
                        final long start = System.nanoTime();
                        // The save point: a flushing save is the guarantee a shutdown gives, and it waits for
                        // the chunk, entity and (through the engine) POI writes.
                        level.save(null, true, false);
                        saveNanos.set(System.nanoTime() - start);
                    })
                    .thenExecute(() -> releaseChunks(level, grid))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().noneMatch(chunk -> level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false) != null),
                            "the save/reload chunk grid never unloaded, so the reload would read memory"))
                    .thenExecute(() -> {
                        final long start = System.nanoTime();
                        forceChunks(level, grid);
                        reloadNanos.set(System.nanoTime() - start);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the save/reload chunk grid never finished its entity load after the reload"))
                    .thenExecute(() -> {
                        afterReload.set(world.capture());
                        compareStates(helper, beforeSave.get(), afterReload.get());
                        assertSavePointCounters(helper);
                        report(helper, profile, beforeSave.get(), afterReload.get(), saveNanos.get(), reloadNanos.get());
                    })
                    .thenIdle(20)
                    .thenExecute(() -> {
                        // The reloaded world has to keep ticking: the region mapping of every entity was rebuilt
                        // from its position, and a stale or missing mapping would show up as an ownership
                        // violation or a tick failure in these ticks.
                        assertNoViolations(helper);
                        helper.assertTrue(grid.stream().allMatch(chunk -> level.getChunk(chunk.x, chunk.z) != null),
                                "a chunk of the reloaded area is not loaded");
                    })
                    .thenSucceed();
        });
    }

    /**
     * Kills the process in the middle of the activity and checks the world the next process loads.
     * <p>
     * The phase is selected by {@value #CRASH_PHASE_PROPERTY}:
     * <ul>
     * <li>{@code prepare} builds the fixture, saves, records the state of that save point, keeps ticking and
     * then halts the JVM from a separate thread, which is a hard kill: no shutdown hook runs, no chunk, entity
     * or POI data is written after the save point, and whatever the tick in flight had not applied is gone.</li>
     * <li>{@code recover} starts on the same world directory, asserts that the level loaded, that the state of
     * the save point is intact, that every entity UUID is unique, that the identity ledger describes this
     * process instead of remembering the killed one, that no POI ticket leaked and no deferral was left
     * dangling, and then runs the invariants for {@value #RECOVERY_TICKS} ticks.</li>
     * </ul>
     * The durable state of a hard kill is the last save point in both runs, which is exactly what vanilla
     * guarantees: the block update, item transfer, entity move or POI write of the tick that never returned is
     * lost, and nothing that was applied before the save point is.
     */
    @GameTest(batch = "crashRecovery", timeoutTicks = 2200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Kills the JVM in the middle of the activity, restarts on the same world and checks that the save point is intact and that no identity, ticket or deferral leaked")
    static void recoversAfterAHardKill(final DynamicTest test) {
        test.onGameTest(helper -> {
            final String phase = System.getProperty(CRASH_PHASE_PROPERTY, "").trim();
            if (phase.isEmpty()) {
                System.out.println("SAVE_RELOAD_NOTE skipped the hard-kill recovery: -D" + CRASH_PHASE_PROPERTY + " is not set");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
            assertConfiguration(helper, engine);
            final List<ChunkPos> grid = areaGrid();
            final Fixture world = new Fixture(level, grid);
            final GameRules.BooleanValue mobSpawning = level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING);
            final boolean previousMobSpawning = mobSpawning.get();
            final GameRules.BooleanValue daylight = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
            final boolean previousDaylight = daylight.get();
            final long previousDayTime = level.getDayTime();
            helper.addEndListener(passed -> {
                if (!"prepare".equals(phase)) {
                    world.discard();
                    releaseChunks(level, grid);
                }
                mobSpawning.set(previousMobSpawning, level.getServer());
                daylight.set(previousDaylight, level.getServer());
                level.setDayTime(previousDayTime);
            });
            if ("prepare".equals(phase)) {
                final int killTicks = intProperty(CRASH_KILL_TICKS_PROPERTY, DEFAULT_KILL_TICKS);
                helper.startSequence()
                        .thenExecute(() -> forceChunks(level, grid))
                        .thenWaitUntil(() -> helper.assertTrue(
                                grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                                "the crash fixture grid never finished its entity load"))
                        .thenExecute(() -> {
                            mobSpawning.set(false, level.getServer());
                            daylight.set(false, level.getServer());
                            level.setDayTime(18000L);
                            world.build();
                            world.freeze();
                        })
                        .thenIdle(POI_SETTLE_TICKS)
                        .thenExecute(() -> {
                            final int taken = world.takeTickets();
                            System.out.println("SAVE_RELOAD_NOTE fixture: " + taken + " tickets taken by the fixture, "
                                    + world.claimedTickets() + " tickets taken in the area");
                            helper.assertTrue(world.claimedTickets() > 0, "the save point holds no taken POI ticket to lose");
                        })
                        .thenIdle(RELOAD_SETTLE_TICKS)
                        .thenExecute(() -> {
                            level.save(null, true, false);
                            final FixtureState saved = world.capture();
                            writeState(statePath(), saved);
                            System.out.println("SAVE_RELOAD_NOTE crash phase: saved the state of the save point at game time "
                                    + level.getGameTime() + " with " + saved.blocks().size() + " blocks and "
                                    + saved.pois().size() + " POI records");
                        })
                        // Keep everything running and then kill the process from another thread while the
                        // server thread is in the middle of a tick. That is what a kill -9 at an arbitrary
                        // moment does; nothing in the process can flush anything after this point.
                        .thenExecuteAfter(killTicks, () -> {
                            System.out.println("SAVE_RELOAD_NOTE crash phase: halting the JVM at game time " + level.getGameTime());
                            System.out.flush();
                            final Thread killer = new Thread(() -> {
                                try {
                                    Thread.sleep(1L);
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                }
                                Runtime.getRuntime().halt(0);
                            }, "save-reload-hard-kill");
                            killer.setDaemon(true);
                            killer.start();
                        })
                        .thenIdle(60)
                        .thenExecute(() -> helper.assertTrue(false, "the hard kill never happened"))
                        .thenSucceed();
                return;
            }
            helper.assertTrue("recover".equals(phase), CRASH_PHASE_PROPERTY + " has to be prepare or recover, got " + phase);
            final FixtureState saved = readState(statePath());
            helper.assertTrue(saved != null, "the recover phase found no state of the prepare phase at " + statePath());
            helper.startSequence()
                    .thenExecute(() -> forceChunks(level, grid))
                    .thenWaitUntil(() -> helper.assertTrue(
                            grid.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())),
                            "the recovered world never finished loading the entities of its save point"))
                    .thenExecute(() -> {
                        world.adoptPositions(saved);
                        final FixtureState recovered = world.capture();
                        compareRecoveredState(helper, saved, recovered);
                        assertIdentity(helper, level, saved, recovered);
                        assertPoiTickets(helper, level, recovered, world.poiCenter());
                        assertNothingDangling(helper, engine);
                    })
                    .thenIdle(RECOVERY_TICKS)
                    .thenExecute(() -> {
                        assertNoViolations(helper);
                        final FixtureState later = world.capture();
                        helper.assertTrue(later.uuids().size() >= saved.uuids().size(),
                                "the recovered world lost entities during the recovery window: " + later.uuids().size()
                                        + " of " + saved.uuids().size());
                        assertPoiTickets(helper, level, later, world.poiCenter());
                        System.out.println("SAVE_RELOAD_NOTE recovery: green for " + RECOVERY_TICKS + " ticks, "
                                + later.uuids().size() + " entities, " + later.pois().size() + " POI records");
                    })
                    .thenSucceed();
        });
    }

    // ---------------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------------

    /**
     * Asserts that the run is the configuration it claims: the threading-on run needs the engine and the
     * regionized POI storage, the reference run needs no engine at all.
     */
    private static void assertConfiguration(ExtendedGameTestHelper helper, Optional<RegionThreadingDiagnostics> engine) {
        // Unset means "whatever the run is configured with": the default GameTest batch runs the whole
        // repository with region threading off, which is the reference configuration of this milestone.
        final String configured = System.getProperty(REGION_THREADING_PROPERTY);
        final boolean expectOn = configured == null || configured.isBlank()
                ? engine.isPresent()
                : !"false".equalsIgnoreCase(configured.trim());
        if (!expectOn) {
            helper.assertTrue(engine.isEmpty(), "the reference run must not install the region threading bridge");
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingPoi.get(),
                    "the reference run must not enable the regionized POI storage");
            return;
        }
        helper.assertTrue(engine.isPresent(), "the region threading run must install the bridge");
        final RegionThreadingDiagnostics diagnostics = engine.get();
        helper.assertTrue(diagnostics.regionPolicy() == net.neoforged.neoforge.server.threading.region.RegionPolicy.FIXED_3X3
                && diagnostics.regionChunkSize() == REGION_CHUNK_SIZE,
                "the save/reload test needs the fixed " + REGION_CHUNK_SIZE + "-chunk layout, got "
                        + diagnostics.regionPolicy() + "/" + diagnostics.regionChunkSize());
        helper.assertTrue(NeoForgeConfig.SERVER.enableRegionThreadingPoi.get(),
                "the save/reload test needs the regionized POI storage enabled");
        helper.assertTrue(diagnostics.entityTickWorkerExecutionEnabled(), "the save/reload test needs the entity tick slice");
        helper.assertTrue(diagnostics.chunkTickWorkerExecutionEnabled(), "the save/reload test needs the chunk tick slice");
    }

    /**
     * Compares the state before the save with the state after the reload, position by position.
     * <p>
     * Every map is a sorted text form, so a failure names the first key that differs and both values instead of
     * printing two large maps.
     */
    private static void compareStates(ExtendedGameTestHelper helper, FixtureState before, FixtureState after) {
        helper.assertTrue(before != null && after != null, "a state capture is missing");
        compareMaps(helper, "block state", before.blocks(), after.blocks());
        compareMaps(helper, "block entity", before.blockEntities(), after.blockEntities());
        compareMaps(helper, "entity identity", before.uuids(), after.uuids());
        compareMaps(helper, "entity position", before.entityPositions(), after.entityPositions());
        compareMaps(helper, "POI record", before.pois(), after.pois());
        compareMaps(helper, "region mapping", before.regions(), after.regions());
        compareMaps(helper, "POI region mapping", before.poiRegions(), after.poiRegions());
    }

    private static void compareRecoveredState(ExtendedGameTestHelper helper, FixtureState saved, FixtureState recovered) {
        compareMaps(helper, "block state", saved.blocks(), recovered.blocks());
        compareMaps(helper, "block entity", saved.blockEntities(), recovered.blockEntities());
        compareMaps(helper, "entity identity", saved.uuids(), recovered.uuids());
        compareMaps(helper, "POI record", saved.pois(), recovered.pois());
        compareMaps(helper, "region mapping", saved.regions(), recovered.regions());
        helper.assertTrue(recovered.uuids().size() == recovered.entityPositions().size(),
                "the recovered world holds " + recovered.uuids().size() + " entity identities but "
                        + recovered.entityPositions().size() + " entity positions");
    }

    /** Compares two position-keyed maps entry by entry and names the first difference. */
    private static void compareMaps(ExtendedGameTestHelper helper, String what, Map<String, String> expected, Map<String, String> actual) {
        for (final Map.Entry<String, String> entry : expected.entrySet()) {
            final String actualValue = actual.get(entry.getKey());
            helper.assertTrue(actualValue != null, what + " missing after the reload: " + entry.getKey() + " was " + entry.getValue());
            helper.assertTrue(entry.getValue().equals(actualValue),
                    what + " changed across the reload at " + entry.getKey() + ": expected " + entry.getValue() + ", got " + actualValue);
        }
        for (final String key : actual.keySet()) {
            helper.assertTrue(expected.containsKey(key), what + " appeared across the reload: " + key + " = " + actual.get(key));
        }
    }

    /**
     * Asserts the save point counters of the engine.
     * <p>
     * A save must never observe a region batch in flight or a deferred effect its barrier had not applied; both
     * are counted as violations and both are zero for a save of the server thread. The cross-tick counters may
     * be non-zero and are only reported, because the work they count is deferred across a tick boundary by
     * design.
     */
    private static void assertSavePointCounters(ExtendedGameTestHelper helper) {
        final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
        if (engine.isEmpty()) {
            return;
        }
        final RegionThreadingDiagnostics.SaveCounters save = engine.get().saveCounters();
        helper.assertTrue(save.saves() > 0L, "the engine observed no save at all");
        helper.assertTrue(save.savesWithBatchInFlight() == 0L,
                "a save observed a region tick batch in flight: " + save.savesWithBatchInFlight() + " of " + save.saves());
        helper.assertTrue(save.savesWithTickDeferredWork() == 0L,
                "a save observed deferred effects of a batch that had not reached its barrier: " + save.savesWithTickDeferredWork());
        System.out.println("SAVE_RELOAD_NOTE save point counters: saves=" + save.saves()
                + " batchInFlight=" + save.savesWithBatchInFlight()
                + " tickDeferred=" + save.savesWithTickDeferredWork()
                + " crossTickDeferred=" + save.savesWithCrossTickDeferredWork()
                + " crossTickEntries=" + save.crossTickDeferredEntries()
                + " dirtyPoiColumns=" + save.dirtyPoiColumns()
                + " flushedPoiColumns=" + save.flushedPoiColumns());
    }

    /**
     * Asserts that the entity identities of the recovered world are the identities of its save point.
     * <p>
     * A hard kill must not leave a duplicate UUID behind, and it must not resurrect an entity the save point did
     * not contain: the identity of an entity is part of the chunk data, so the set the level admits after a
     * reload is exactly the set the save wrote. Every fixture entity of the save point is therefore present and
     * no UUID appears twice.
     */
    private static void assertIdentity(ExtendedGameTestHelper helper, ServerLevel level, FixtureState saved, FixtureState recovered) {
        final java.util.Set<java.util.UUID> unique = new java.util.HashSet<>();
        int duplicates = 0;
        for (final Entity entity : level.getEntities().getAll()) {
            if (!unique.add(entity.getUUID())) {
                duplicates++;
            }
        }
        helper.assertTrue(duplicates == 0, duplicates + " entity UUIDs are duplicated after the hard kill");
        compareMaps(helper, "entity identity", saved.uuids(), recovered.uuids());
        for (final String uuid : saved.uuids().keySet()) {
            helper.assertTrue(level.getEntity(java.util.UUID.fromString(uuid)) != null,
                    "the entity " + uuid + " of the save point was not admitted after the hard kill");
        }
        final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
        engine.ifPresent(diagnostics -> helper.assertTrue(diagnostics.ownershipViolationCount() == 0L,
                "the recovered world reported " + diagnostics.ownershipViolationCount() + " ownership violations"));
    }

    /**
     * Asserts that every POI record holds a ticket count inside its type's range.
     * <p>
     * A ticket count outside the range is the signature of a leaked or double-booked ticket: the count can only
     * leave {@code 0..maxTickets} if an acquire or a release ran against a record that no longer exists.
     */
    private static void assertPoiTickets(ExtendedGameTestHelper helper, ServerLevel level, FixtureState state, BlockPos center) {
        int occupied = 0;
        for (final PoiRecord record : level.getPoiManager()
                .getInSquare(type -> true, center, 64, PoiManager.Occupancy.ANY).toList()) {
            final int max = record.getPoiType().value().maxTickets();
            final int free = record.getFreeTickets();
            helper.assertTrue(free >= 0 && free <= max,
                    "the POI at " + record.getPos() + " holds " + free + " free tickets of " + max);
            if (free < max) {
                occupied++;
            }
        }
        helper.assertTrue(occupied > 0, "no POI record of the recovered world has a taken ticket");
    }

    /**
     * Asserts that nothing the recovery path could leave behind is still pending: no deferred entity move, no
     * pending transfer decision, no ownership violation after the recovery window.
     */
    private static void assertNothingDangling(ExtendedGameTestHelper helper, Optional<RegionThreadingDiagnostics> engine) {
        if (engine.isEmpty()) {
            return;
        }
        final RegionThreadingDiagnostics diagnostics = engine.get();
        helper.assertTrue(diagnostics.droppedDeferredEntityAdditions() == 0L,
                "the recovery dropped " + diagnostics.droppedDeferredEntityAdditions() + " deferred entity admissions");
        helper.assertTrue(diagnostics.pendingEntityTransferCount() == 0,
                "the recovery left " + diagnostics.pendingEntityTransferCount() + " pending entity transfer decisions");
    }

    private static void assertNoViolations(ExtendedGameTestHelper helper) {
        final Optional<RegionThreadingDiagnostics> engine = RegionThreadingHooks.diagnostics();
        if (engine.isEmpty()) {
            helper.assertTrue(RegionThreadingHooks.diagnostics().isEmpty(), "the reference run has no engine");
            return;
        }
        final RegionThreadingDiagnostics diagnostics = engine.get();
        helper.assertTrue(diagnostics.ownershipViolationCount() == 0L,
                "the region threading runtime reported " + diagnostics.ownershipViolationCount() + " ownership violations");
        long failures = 0L;
        for (final net.neoforged.neoforge.server.threading.region.RegionTickSlice slice : net.neoforged.neoforge.server.threading.region.RegionTickSlice
                .values()) {
            failures += diagnostics.tickFailures().recorded(slice);
        }
        helper.assertTrue(failures == 0L, "the region threading runtime reported " + failures + " tick failures");
    }

    // ---------------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------------

    /**
     * Writes the observable state of the reloaded world, or compares it with the state the threading-on run
     * wrote, and prints the timings.
     */
    private static void report(ExtendedGameTestHelper helper, String profile, FixtureState before, FixtureState after,
            long saveNanos, long reloadNanos) {
        System.out.println("SAVE_RELOAD_NOTE profile=" + profile
                + " blocks=" + after.blocks().size()
                + " blockEntities=" + after.blockEntities().size()
                + " entities=" + after.uuids().size()
                + " poiRecords=" + after.pois().size()
                + " takenTickets=" + after.takenTickets()
                + " saveMs=" + round(saveNanos / NANOS_PER_MILLI)
                + " reloadMs=" + round(reloadNanos / NANOS_PER_MILLI));
        final String reference = System.getProperty(REFERENCE_PROPERTY);
        if (reference == null || reference.isBlank()) {
            final Path path = reportPath().resolve("state-" + profile + ".txt");
            writeState(path, after);
            System.out.println("SAVE_RELOAD_NOTE wrote the reloaded state to " + path.toAbsolutePath());
            return;
        }
        final FixtureState expected = readState(Path.of(reference.trim()));
        helper.assertTrue(expected != null, "the reference state file " + reference + " is missing");
        // The profile-independent half of the state: positions, block states, block-entity contents, POI
        // records with their tickets and the region mapping. The entity identities are per-run UUIDs and the
        // positions of mobs that are not frozen are not comparable across two independent runs.
        compareMaps(helper, "block state", expected.blocks(), after.blocks());
        compareMaps(helper, "block entity", expected.blockEntities(), after.blockEntities());
        compareMaps(helper, "POI record", expected.pois(), after.pois());
        compareMaps(helper, "region mapping", expected.regions(), after.regions());
        compareMaps(helper, "POI region mapping", expected.poiRegions(), after.poiRegions());
        System.out.println("SAVE_RELOAD_NOTE the reloaded state of this run equals the state the threading-on run reloaded");
    }

    private static Path reportPath() {
        return Path.of(System.getProperty(REPORT_PROPERTY, "save-reload-report"));
    }

    private static Path statePath() {
        final String configured = System.getProperty(CRASH_STATE_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return reportPath().resolve("crash-state.txt");
    }

    /** Writes one state as sorted {@code key=value} lines, so a diff of two runs is a diff of the world. */
    private static void writeState(Path path, FixtureState state) {
        final List<String> lines = new ArrayList<>();
        appendSection(lines, "block", state.blocks());
        appendSection(lines, "blockEntity", state.blockEntities());
        appendSection(lines, "entity", state.uuids());
        appendSection(lines, "poi", state.pois());
        appendSection(lines, "region", state.regions());
        appendSection(lines, "poiRegion", state.poiRegions());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, lines);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write the save/reload state to " + path, exception);
        }
    }

    private static FixtureState readState(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        final Map<String, String> blocks = new TreeMap<>();
        final Map<String, String> blockEntities = new TreeMap<>();
        final Map<String, String> uuids = new TreeMap<>();
        final Map<String, String> pois = new TreeMap<>();
        final Map<String, String> regions = new TreeMap<>();
        final Map<String, String> poiRegions = new TreeMap<>();
        try {
            for (final String line : Files.readAllLines(path)) {
                final int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                final String section = line.substring(0, split);
                final String rest = line.substring(split + 1);
                final int keyEnd = rest.indexOf('=');
                if (keyEnd < 0) {
                    continue;
                }
                final String key = rest.substring(0, keyEnd);
                final String value = rest.substring(keyEnd + 1);
                switch (section) {
                    case "block" -> blocks.put(key, value);
                    case "blockEntity" -> blockEntities.put(key, value);
                    case "entity" -> uuids.put(key, value);
                    case "poi" -> pois.put(key, value);
                    case "region" -> regions.put(key, value);
                    case "poiRegion" -> poiRegions.put(key, value);
                    default -> {}
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not read the save/reload state from " + path, exception);
        }
        return new FixtureState(blocks, blockEntities, uuids, new TreeMap<>(), pois, regions, poiRegions);
    }

    private static void appendSection(List<String> lines, String section, Map<String, String> values) {
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            lines.add(section + "=" + entry.getKey() + "=" + entry.getValue());
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ---------------------------------------------------------------------------------------------------
    // The fixture
    // ---------------------------------------------------------------------------------------------------

    /**
     * The captured observable state of the fixture area.
     *
     * @param blocks          every block the fixture wrote, with its state
     * @param blockEntities   every block entity of the area, with its full NBT
     * @param uuids           the identity and position of every fixture entity
     * @param entityPositions the position of every fixture entity, keyed by position
     * @param pois            every POI record of the area with its free-ticket count
     * @param regions         the region coordinate of every entity position of the area
     * @param poiRegions      the region coordinate of every POI record
     */
    private record FixtureState(Map<String, String> blocks, Map<String, String> blockEntities, Map<String, String> uuids,
            Map<String, String> entityPositions, Map<String, String> pois, Map<String, String> regions,
            Map<String, String> poiRegions) {
        int takenTickets() {
            int taken = 0;
            for (final String value : this.pois.values()) {
                taken += Integer.parseInt(value.substring(value.lastIndexOf('#') + 1));
            }
            return taken;
        }
    }

    /**
     * The content of the save/reload area.
     * <p>
     * Everything is placed at fixed absolute positions on the flat GameTest level, and every position the
     * fixture writes is recorded, so the comparison covers exactly the blocks the fixture owns. The content is
     * spread over a region border and covers every kind of state the save has to carry: block states, block
     * entities with contents, entity identities, and POI records whose tickets the villagers took.
     */
    static final class Fixture {
        private final ServerLevel level;
        private final List<ChunkPos> grid;
        private final List<BlockPos> written = new ArrayList<>();
        private final List<Mob> mobs = new ArrayList<>();
        private final List<Villager> villagers = new ArrayList<>();
        private final List<BlockPos> bedPositions = new ArrayList<>();
        private final RegionLayout layout;

        Fixture(ServerLevel level, List<ChunkPos> grid) {
            this.level = level;
            this.grid = grid;
            this.layout = RegionLayout.FIXED_3X3;
        }

        /**
         * Builds the content: beds, workstations, containers, a farm, a powered circuit across the border.
         * <p>
         * The sites sit in the chunks of the five-chunk fixture area, one per chunk, so the fixture covers
         * every region of the area and the circuit crosses the border between the region of chunks {@code
         * 60..62} and the region of chunks {@code 63..65}.
         */
        void build() {
            final int bedX = blockX(0, 3);
            final int bedZ = blockZ(0, 3);
            this.buildVillagers(bedX, bedZ, this.surface(bedX, bedZ));
            final int containerX = blockX(1, 2);
            final int containerZ = blockZ(1, 2);
            this.buildContainers(containerX, containerZ, this.surface(containerX, containerZ));
            final int farmX = blockX(3, 5);
            final int farmZ = blockZ(3, 5);
            this.buildFarm(farmX, farmZ, this.surface(farmX, farmZ));
            final int circuitX = borderBlockX() - 6;
            final int circuitZ = blockZ(2, 8);
            this.buildCircuit(circuitX, circuitZ, this.surface(circuitX, circuitZ));
            final int pastureX = blockX(4, 2);
            final int pastureZ = blockZ(4, 2);
            this.buildPasture(pastureX, pastureZ, this.surface(pastureX, pastureZ) + 1);
            if (this.written.isEmpty()) {
                throw new IllegalStateException("the fixture wrote no block");
            }
        }

        private void buildVillagers(int x, int z, int base) {
            for (int i = 0; i < VILLAGER_COUNT; i++) {
                final BlockPos foot = new BlockPos(x + i * 4, base, z);
                this.set(foot, Blocks.RED_BED.defaultBlockState()
                        .setValue(BedBlock.FACING, Direction.SOUTH)
                        .setValue(BedBlock.PART, BedPart.FOOT));
                this.set(foot.south(), Blocks.RED_BED.defaultBlockState()
                        .setValue(BedBlock.FACING, Direction.SOUTH)
                        .setValue(BedBlock.PART, BedPart.HEAD));
                this.bedPositions.add(foot);
                // The workstation stands next to the villager: a villager only converts its potential job site
                // into a claimed one when it stands within two blocks of it.
                this.set(foot.offset(2, 0, 0), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
                final Villager villager = new Villager(EntityType.VILLAGER, this.level);
                villager.moveTo(foot.getX() + 1.5, base, foot.getZ() + 0.5, 0.0F, 0.0F);
                villager.setPersistenceRequired();
                this.level.addFreshEntity(villager);
                this.villagers.add(villager);
                this.mobs.add(villager);
            }
            // A villager home is lit, so nothing spawns among the beds and kills the POI users.
            this.set(new BlockPos(x + 1, base + 3, z + 1), Blocks.GLOWSTONE.defaultBlockState());
        }

        /** Chests, a barrel, a dropper and a lectern: block entities with data that has to survive the save. */
        private void buildContainers(int x, int z, int base) {
            this.set(new BlockPos(x, base, z), Blocks.CHEST.defaultBlockState());
            final BlockEntity chest = this.level.getBlockEntity(new BlockPos(x, base, z));
            if (chest instanceof net.minecraft.world.Container container) {
                container.setItem(0, new ItemStack(Items.DIAMOND, 17));
                container.setItem(5, new ItemStack(Items.OAK_LOG, 3));
                chest.setChanged();
            }
            this.set(new BlockPos(x + 2, base, z), Blocks.BARREL.defaultBlockState());
            final BlockEntity barrel = this.level.getBlockEntity(new BlockPos(x + 2, base, z));
            if (barrel instanceof net.minecraft.world.Container container) {
                container.setItem(3, new ItemStack(Items.WHEAT, 42));
                barrel.setChanged();
            }
            this.set(new BlockPos(x + 4, base, z), Blocks.DROPPER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, Direction.UP));
            final BlockEntity dropper = this.level.getBlockEntity(new BlockPos(x + 4, base, z));
            if (dropper instanceof net.minecraft.world.Container container) {
                container.setItem(0, new ItemStack(Items.ARROW, 9));
                dropper.setChanged();
            }
            // A sign: a block entity whose data is text, which is the third kind of payload next to the item
            // stacks of the containers and the bees of the nest.
            this.set(new BlockPos(x + 6, base, z), Blocks.OAK_SIGN.defaultBlockState());
            if (this.level.getBlockEntity(new BlockPos(x + 6, base, z)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                sign.setText(new net.minecraft.world.level.block.entity.SignText()
                        .setMessage(0, net.minecraft.network.chat.Component.literal("Forgia"))
                        .setMessage(1, net.minecraft.network.chat.Component.literal("save/reload")), true);
                sign.setChanged();
            }
            this.set(new BlockPos(x + 8, base, z), Blocks.BEE_NEST.defaultBlockState());
            this.set(new BlockPos(x + 10, base, z), Blocks.BELL.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.BellBlock.FACING, Direction.NORTH));
            this.set(new BlockPos(x + 12, base, z), Blocks.LODESTONE.defaultBlockState());
        }

        /**
         * A farm: farmland with water and wheat at a fixed age, which is a block state the save has to keep.
         * <p>
         * The farmland replaces the ground block of the site, so it has support and neither it nor a crop falls
         * or breaks: a broken block would drop an item entity that moves while the world is reloaded.
         */
        private void buildFarm(int x, int z, int base) {
            for (int dx = 0; dx < 5; dx++) {
                for (int dz = 0; dz < 5; dz++) {
                    final BlockPos pos = new BlockPos(x + dx, base, z + dz);
                    if (dx == 2 && dz == 2) {
                        this.set(pos, Blocks.WATER.defaultBlockState());
                    } else {
                        this.set(pos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                        this.set(pos.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));
                    }
                }
            }
        }

        /**
         * A powered circuit that crosses a region border: a lever feeds redstone dust over the border into a
         * repeater, a comparator and a lamp, and the piston pushes a block.
         * <p>
         * The lever is placed on, so the circuit settles into one state in the first ticks and stays there. The
         * point is not the redstone logic - the redstone milestone proves that - but that the state a cross-region
         * circuit reaches is a set of block states that the save carries.
         */
        private void buildCircuit(int x, int z, int base) {
            this.set(new BlockPos(x, base, z), Blocks.SMOOTH_STONE.defaultBlockState());
            this.set(new BlockPos(x, base, z).offset(1, 0, 0), Blocks.LEVER.defaultBlockState()
                    .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                    .setValue(LeverBlock.FACING, Direction.EAST)
                    .setValue(LeverBlock.POWERED, true));
            for (int dx = 2; dx <= 8; dx++) {
                this.set(new BlockPos(x + dx, base, z), Blocks.REDSTONE_WIRE.defaultBlockState());
            }
            this.set(new BlockPos(x + 9, base, z), Blocks.REPEATER.defaultBlockState()
                    .setValue(RepeaterBlock.FACING, Direction.EAST)
                    .setValue(RepeaterBlock.DELAY, 2));
            this.set(new BlockPos(x + 10, base, z), Blocks.COMPARATOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ComparatorBlock.FACING, Direction.EAST));
            this.set(new BlockPos(x + 11, base, z), Blocks.REDSTONE_LAMP.defaultBlockState());
        }

        /** The animals of the fixture, on a fenced platform. */
        private void buildPasture(int x, int z, int base) {
            for (int dx = 0; dx < 6; dx++) {
                for (int dz = 0; dz < 6; dz++) {
                    this.set(new BlockPos(x + dx, base - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            for (int i = 0; i < COW_COUNT; i++) {
                final Cow cow = new Cow(EntityType.COW, this.level);
                cow.moveTo(x + 0.5 + (i % 3) * 2, base, z + 0.5 + (i / 3) * 2, 0.0F, 0.0F);
                cow.setPersistenceRequired();
                this.level.addFreshEntity(cow);
                this.mobs.add(cow);
            }
        }

        /** Stops every mob of the fixture, so the captured state does not move while the world is reloaded. */
        void freeze() {
            for (final Mob mob : this.mobs) {
                mob.setNoAi(true);
                mob.setPersistenceRequired();
                mob.setDeltaMovement(0.0D, 0.0D, 0.0D);
            }
        }

        /**
         * Books a ticket on two home POIs, through the same {@code PoiManager.take} the villager brain uses.
         * <p>
         * The villagers of the fixture normally claim their own bed and workstation, but a claim is a vanilla
         * AI decision that needs a night, a clear path and a few scans; the take here makes the fixture's
         * ticket state independent of those decisions, so the save always holds a POI record with a taken
         * ticket. The villager claims that did happen are reported next to it.
         */
        int takeTickets() {
            final PoiManager poiManager = this.level.getPoiManager();
            int taken = 0;
            for (int i = 0; i < 2; i++) {
                if (poiManager.take(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME),
                        (type, pos) -> true, this.poiCenter(), 64).isPresent()) {
                    taken++;
                }
            }
            return taken;
        }

        /** {@return the villagers whose brain holds a home, i.e. that claimed their bed} */
        int villagersWithHome() {
            int claimed = 0;
            for (final Villager villager : this.villagers) {
                if (!villager.isRemoved()
                        && villager.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)) {
                    claimed++;
                }
            }
            return claimed;
        }

        /**
         * {@return the centre of the POI queries of the fixture, at the fixture's own height}
         * <p>
         * The flat GameTest level puts its surface far below y=0, and a {@code getInRange} is a three-dimensional
         * query, so the centre and the radius have to be around the content and not around the world origin.
         */
        BlockPos poiCenter() {
            return new BlockPos(blockX(2, 8), this.surface(blockX(2, 8), blockZ(2, 8)) + 2, blockZ(2, 8));
        }

        /** {@return the number of tickets taken from the POI records of the fixture area} */
        int claimedTickets() {
            int taken = 0;
            for (final PoiRecord record : this.level.getPoiManager()
                    .getInSquare(type -> true, this.poiCenter(), 64, PoiManager.Occupancy.ANY).toList()) {
                taken += record.getPoiType().value().maxTickets() - record.getFreeTickets();
            }
            return taken;
        }

        /**
         * Adopts the block positions a state file recorded, so a later process can read the same positions.
         * <p>
         * The recover phase of the hard-kill test starts on a world it did not build, so its fixture has written
         * nothing. The positions of the save point are what the comparison is about, and they are in the state
         * file, so the recovered process reads the world at exactly those positions.
         */
        void adoptPositions(FixtureState state) {
            for (final String key : state.blocks().keySet()) {
                final String[] parts = key.split(",");
                if (parts.length != 3) {
                    throw new IllegalStateException("not a block position: " + key);
                }
                this.written.add(new BlockPos(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())));
            }
        }

        /** Captures every observable bit of the fixture area. */
        FixtureState capture() {
            final Map<String, String> blocksNow = new TreeMap<>();
            for (final BlockPos pos : this.written) {
                blocksNow.put(pos.toShortString(), this.level.getBlockState(pos).toString());
            }
            // The entities are read from the level and not from the list the fixture spawned: a reload creates
            // new entity instances, so the list the fixture built only names the instances of the process that
            // spawned them. Every loaded entity of the area is part of the state a save has to carry.
            final java.util.Set<Long> areaChunks = new java.util.HashSet<>();
            for (final ChunkPos chunk : this.grid) {
                areaChunks.add(chunk.toLong());
            }
            final Map<String, String> entities = new TreeMap<>();
            final Map<String, String> entityPositions = new TreeMap<>();
            final Map<String, String> regions = new TreeMap<>();
            for (final Entity entity : this.level.getEntities().getAll()) {
                if (entity.isRemoved() || entity instanceof net.minecraft.world.entity.player.Player
                        || !areaChunks.contains(entity.chunkPosition().toLong())) {
                    continue;
                }
                final String key = entity.getUUID().toString();
                final String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                entities.put(key, type + "@" + entity.blockPosition().toShortString());
                entityPositions.put(entity.blockPosition().toShortString() + "#" + key, entity.blockPosition().toShortString());
                // The region mapping is keyed by position and type, not by UUID, so two runs that
                // independently create their entities still compare the mapping the layout derives.
                regions.put(entity.blockPosition().toShortString() + "#" + type, this.regionOf(entity.blockPosition()));
            }
            final Map<String, String> blockEntities = new TreeMap<>();
            for (final ChunkPos chunk : this.grid) {
                final LevelChunk loaded = this.level.getChunk(chunk.x, chunk.z);
                for (final Map.Entry<BlockPos, BlockEntity> entry : new TreeMap<>(loaded.getBlockEntities()).entrySet()) {
                    blockEntities.put(entry.getKey().toShortString(), entry.getValue().saveWithFullMetadata(this.level.registryAccess()).toString());
                }
            }
            final Map<String, String> pois = new TreeMap<>();
            final Map<String, String> poiRegions = new TreeMap<>();
            for (final PoiRecord record : this.level.getPoiManager()
                    .getInSquare(type -> true, this.poiCenter(), 64, PoiManager.Occupancy.ANY).toList()) {
                final String key = record.getPos().toShortString();
                pois.put(key, record.getPoiType().getRegisteredName() + "#"
                        + (record.getPoiType().value().maxTickets() - record.getFreeTickets()));
                poiRegions.put(key, this.regionOf(record.getPos()));
            }
            return new FixtureState(blocksNow, blockEntities, entities, entityPositions, pois, regions, poiRegions);
        }

        /** {@return the region coordinate the layout assigns to {@code pos}} */
        String regionOf(BlockPos pos) {
            final net.neoforged.neoforge.server.threading.region.RegionCoordinate coordinate = this.layout.regionAt(this.level, pos);
            return coordinate.regionX() + "/" + coordinate.regionZ();
        }

        void discard() {
            for (final Mob mob : this.mobs) {
                mob.discard();
            }
            this.mobs.clear();
            this.villagers.clear();
        }

        private void set(BlockPos pos, BlockState state) {
            this.level.setBlockAndUpdate(pos, state);
            this.written.add(pos);
        }

        private int surface(int x, int z) {
            return this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }

        /** {@return the x coordinate of {@code offsetInChunk} of the chunk {@code chunkOffset} of the area} */
        private static int blockX(int chunkOffset, int offsetInChunk) {
            return chunkMinX(ORIGIN_CHUNK_X + chunkOffset) + offsetInChunk;
        }

        /** {@return the z coordinate of {@code offsetInChunk} of the chunk {@code chunkOffset} of the area} */
        private static int blockZ(int chunkOffset, int offsetInChunk) {
            return chunkMinX(ORIGIN_CHUNK_Z + chunkOffset) + offsetInChunk;
        }

        private static int chunkMinX(int chunkX) {
            return chunkX << 4;
        }

        /** {@return the first block of the chunk that starts the second region of the area} */
        private static int borderBlockX() {
            return chunkMinX(ORIGIN_CHUNK_X + REGION_CHUNK_SIZE);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Chunk handling
    // ---------------------------------------------------------------------------------------------------

    private static List<ChunkPos> areaGrid() {
        final List<ChunkPos> grid = new ArrayList<>(AREA_CHUNK_SIDE * AREA_CHUNK_SIDE);
        for (int dx = 0; dx < AREA_CHUNK_SIDE; dx++) {
            for (int dz = 0; dz < AREA_CHUNK_SIDE; dz++) {
                grid.add(new ChunkPos(ORIGIN_CHUNK_X + dx, ORIGIN_CHUNK_Z + dz));
            }
        }
        return grid;
    }

    private static void forceChunks(ServerLevel level, List<ChunkPos> grid) {
        for (final ChunkPos chunk : grid) {
            level.setChunkForced(chunk.x, chunk.z, true);
            level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, ENTITY_TICKING_TICKET_RADIUS, chunk, true);
        }
    }

    /**
     * Drops every ticket of the grid, so the chunks unload and the next load reads them from disk.
     * <p>
     * The forced chunk set and the region ticket are the two things that keep a chunk of the fixture loaded;
     * both are removed here, and the test waits until the chunk source reports the chunk as absent before it
     * forces it again. Without that wait a "reload" would read the chunk it never unloaded from memory.
     */
    private static void releaseChunks(ServerLevel level, List<ChunkPos> grid) {
        for (final ChunkPos chunk : grid) {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, ENTITY_TICKING_TICKET_RADIUS, chunk, true);
            level.setChunkForced(chunk.x, chunk.z, false);
        }
    }

    private static int intProperty(String property, int fallback) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(property + " must be an integer, got " + value, exception);
        }
    }

    private SaveReloadGameTests() {}
}
