/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingCompatReport;
import net.neoforged.neoforge.server.threading.RegionThreadingConflicts;
import net.neoforged.neoforge.server.threading.RegionThreadingDeclarations;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the declaration files of the mod compatibility layer against the real entity slices.
 * <p>
 * The files are real files in a temporary directory and the type lookup is the injectable one, so the test
 * covers the format, its error handling and the effect of a declaration on the engine without bootstrapping a
 * game registry. The declarations are applied through the production entry point
 * ({@link RegionThreadingDeclarations.Report#applyTo}), which is the call the server lifecycle hook makes.
 */
@Timeout(120)
class RegionThreadingDeclarationsTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final String ENTITY_REASON = "test: file declaration of an entity type";
    private static final String BLOCK_ENTITY_REASON = "test: file declaration of a block entity type";

    @TempDir
    Path tempDir;

    private RegionThreadingEngine engine;
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutDown() {
        RegionThreadingManager.clear();
        // The block-entity declarations are static state of the allowlist, so a test that declared a type has
        // to hand the allowlist back unchanged for the tests that run after it in the same JVM.
        RegionThreadingDeclarations.Report.empty().applyTo(null);
        RegionThreadingCompatReport.reset();
        this.tickList.clear();
        this.engine = null;
    }

    @Test
    void declaredEntityTypeTicksOnTheServerThreadAndIsCountedByItsReason() throws IOException {
        final EntityType<?> declaredType = mock(EntityType.class);
        final Path file = this.write("entity.json", """
                {"entities": [{"type": "testmod:pinned_zombie", "reason": "%s"}]}
                """.formatted(ENTITY_REASON));
        final RegionThreadingDeclarations.TypeLookup lookup = new SingleTypeLookup(declaredType);

        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                List.of(new RegionThreadingDeclarations.Source("entity.json", file)), lookup);
        assertEquals(1, report.declarationCount());
        assertEquals("testmod:pinned_zombie", report.entities().get(0).type());
        assertEquals(ENTITY_REASON, report.entities().get(0).reason());
        assertTrue(report.ignored().isEmpty());

        this.installEngine();
        report.applyTo(this.engine);

        final ServerLevel level = this.mockLevel();
        this.engine.addRegionSection(level, 0, 0);
        final Zombie zombie = this.entity(level, new BlockPos(1, 64, 1), declaredType);
        this.tickList.add(zombie);
        final List<String> tickThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            return null;
        }).when(zombie).tick();

        this.plan(level).executeAndAwait();

        verify(zombie, times(1)).tick();
        assertEquals(1, tickThreads.size());
        assertFalse(tickThreads.get(0).startsWith("NeoForge Region Worker"),
                "a type declared by a declaration file must not tick on a worker");
        final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
        assertEquals(Map.of(ENTITY_REASON, 1L), diagnostics.serverThreadOnlyEntityTicksByReason(),
                "the reason of the file declaration is the key of the counter");
        assertEquals(0, diagnostics.entityTicksOnWorkers());

        // The same declaration reaches the compatibility command, which is where an admin reads what the
        // files of the pack declared.
        RegionThreadingCompatReport.record(true, true, null, new RegionThreadingConflicts.Plan(List.of(), true, true, true, List.of()), report);
        final List<String> lines = RegionThreadingCompatReport.lines();
        assertTrue(lines.contains("Region threading declaration: entity testmod:pinned_zombie reason=\""
                + ENTITY_REASON + "\" source=entity.json"), lines.toString());
        assertTrue(lines.get(0).contains("declarations=1"), lines.get(0));
        assertEquals(1, RegionThreadingCompatReport.declarationCount());
    }

    @Test
    void declaredBlockEntityTypeLeavesTheRegionSafeAllowlist() throws IOException {
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:hopper"),
                "the hopper is region-safe before a declaration");
        final Path file = this.write("block_entity.json", """
                {"block_entities": [{"type": "minecraft:hopper", "reason": "%s"}]}
                """.formatted(BLOCK_ENTITY_REASON));
        final RegionThreadingDeclarations.TypeLookup lookup = new BlockEntityTypeLookup("minecraft:hopper");

        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                List.of(new RegionThreadingDeclarations.Source("block_entity.json", file)), lookup);
        assertEquals(1, report.declarationCount());
        assertTrue(report.ignored().isEmpty());
        report.applyTo(null);

        assertFalse(RegionTickCompatibility.requiresInteriorRegion("minecraft:hopper"),
                "a declared block-entity type may not tick on a worker at any position");
        assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType("minecraft:hopper"));
        assertEquals(RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED,
                RegionTickCompatibility.decideBlockEntityTick("minecraft:hopper", true),
                "an interior position does not rescue a declared type");
        assertEquals(Map.of("minecraft:hopper", BLOCK_ENTITY_REASON), RegionTickCompatibility.serverThreadOnlyTypes());
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:furnace"),
                "a declaration only narrows the allowlist, it never removes an undeclared type");

        RegionThreadingDeclarations.Report.empty().applyTo(null);
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:hopper"),
                "an empty report restores the allowlist");
    }

    @Test
    void unknownTypeIsReportedAndIgnored() throws IOException {
        final Path file = this.write("unknown.json", """
                {"entities": [{"type": "testmod:ghost", "reason": "test: no such type"}],
                 "block_entities": [{"type": "testmod:ghost_block", "reason": "test: no such type"}]}
                """);

        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                List.of(new RegionThreadingDeclarations.Source("unknown.json", file)), new SingleTypeLookup(null));

        assertTrue(report.isEmpty(), "an unknown type declares nothing");
        assertEquals(List.of(
                "unknown.json: unknown entity type testmod:ghost",
                "unknown.json: unknown block entity type testmod:ghost_block"), report.ignored());
        // Applying a report that ignored everything is a no-op on both registries instead of a crash.
        report.applyTo(null);
        assertTrue(RegionTickCompatibility.serverThreadOnlyTypes().isEmpty());
    }

    @Test
    void malformedFilesAreReportedAndIgnored() throws IOException {
        final Path notJson = this.write("broken.json", "{\"entities\": [");
        final Path notAnObject = this.write("array.json", "[]");
        final Path missingReason = this.write("no_reason.json", """
                {"entities": [{"type": "testmod:pinned_zombie"}]}
                """);
        final Path notAnArray = this.write("wrong_member.json", """
                {"entities": {"type": "testmod:pinned_zombie", "reason": "test: r"}}
                """);
        final List<RegionThreadingDeclarations.Source> sources = List.of(
                new RegionThreadingDeclarations.Source("broken.json", notJson),
                new RegionThreadingDeclarations.Source("array.json", notAnObject),
                new RegionThreadingDeclarations.Source("no_reason.json", missingReason),
                new RegionThreadingDeclarations.Source("wrong_member.json", notAnArray),
                new RegionThreadingDeclarations.Source("absent.json", this.tempDir.resolve("absent.json")));

        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                sources, new SingleTypeLookup(mock(EntityType.class)));

        assertTrue(report.isEmpty(), "a broken file declares nothing instead of crashing the server");
        assertEquals(5, report.ignored().size(), report.ignored().toString());
        assertTrue(report.ignored().get(0).startsWith("broken.json: the file is not valid JSON"), report.ignored().get(0));
        assertTrue(report.ignored().get(1).startsWith("array.json: the file is not a JSON object"), report.ignored().get(1));
        assertTrue(report.ignored().get(2).startsWith("no_reason.json: a declaration has no \"reason\" string"),
                report.ignored().get(2));
        assertTrue(report.ignored().get(3).startsWith("wrong_member.json: \"entities\" is not an array"), report.ignored().get(3));
        assertTrue(report.ignored().get(4).startsWith("absent.json: the file cannot be read"), report.ignored().get(4));
        // The command reports every ignored entry with its source, so a typo in a pack file is visible.
        assertTrue(report.lines().stream().allMatch(line -> line.startsWith("Region threading declaration ignored: ")),
                report.lines().toString());
    }

    @Test
    void absentFileDeclaresNothingAndLeavesTheAllowlistUnchanged() {
        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                List.of(), new SingleTypeLookup(null));

        assertTrue(report.isEmpty());
        assertTrue(report.ignored().isEmpty());
        assertTrue(report.lines().isEmpty(), "an absent file has no report line at all");
        assertTrue(RegionTickCompatibility.serverThreadOnlyTypes().isEmpty());
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:hopper"),
                "without a declaration file the allowlist is the baseline one");
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:furnace"));
        assertFalse(RegionTickCompatibility.requiresInteriorRegion("minecraft:brewing_stand"));
    }

    @Test
    void aBrokenEntryDoesNotDiscardTheOtherEntriesOfTheSameFile() throws IOException {
        final EntityType<?> declaredType = mock(EntityType.class);
        final Path file = this.write("mixed.json", """
                {"entities": [{"type": "testmod:pinned_zombie", "reason": "%s"},
                              {"type": "not a resource location", "reason": "test: bad id"},
                              {"type": "testmod:pinned_zombie"}],
                 "block_entities": [{"type": "minecraft:hopper", "reason": "%s"}]}
                """.formatted(ENTITY_REASON, BLOCK_ENTITY_REASON));
        final RegionThreadingDeclarations.TypeLookup lookup = new BlockEntityTypeLookup("minecraft:hopper") {
            @Override
            public EntityType<?> entityType(ResourceLocation id) {
                return declaredType;
            }
        };

        final RegionThreadingDeclarations.Report report = RegionThreadingDeclarations.parse(
                List.of(new RegionThreadingDeclarations.Source("mixed.json", file)), lookup);

        assertEquals(1, report.entities().size());
        assertEquals(1, report.blockEntities().size());
        assertEquals(2, report.ignored().size(), report.ignored().toString());
        assertEquals("mixed.json: \"not a resource location\" is not a resource location", report.ignored().get(0));
    }

    private Path write(String name, String content) throws IOException {
        final Path file = this.tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        return plan;
    }

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn("test-level").when(level).toString();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> true).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSpawningAnimals()).thenReturn(true);
        when(server.areNpcsEnabled()).thenReturn(true);
        doReturn(server).when(level).getServer();
        return level;
    }

    private Zombie entity(ServerLevel level, BlockPos pos, EntityType<?> type) {
        final Zombie entity = mock(Zombie.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        when(entity.getPassengers()).thenReturn(List.of());
        doReturn(type).when(entity).getType();
        return entity;
    }

    /** A lookup that answers one entity type id and nothing else. */
    private static class SingleTypeLookup implements RegionThreadingDeclarations.TypeLookup {
        @Nullable
        private final EntityType<?> type;

        SingleTypeLookup(@Nullable EntityType<?> type) {
            this.type = type;
        }

        @Override
        public EntityType<?> entityType(ResourceLocation id) {
            return this.type;
        }

        @Override
        public boolean hasBlockEntityType(ResourceLocation id) {
            return false;
        }
    }

    /** A lookup that knows one block-entity type id and no entity type. */
    private static class BlockEntityTypeLookup implements RegionThreadingDeclarations.TypeLookup {
        private final String blockEntityType;

        BlockEntityTypeLookup(String blockEntityType) {
            this.blockEntityType = blockEntityType;
        }

        @Override
        public EntityType<?> entityType(ResourceLocation id) {
            return null;
        }

        @Override
        public boolean hasBlockEntityType(ResourceLocation id) {
            return id.toString().equals(this.blockEntityType);
        }
    }
}
