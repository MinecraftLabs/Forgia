/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.datafixers.DataFixer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPoiStorage;
import net.neoforged.neoforge.server.threading.region.RegionPoiStorages;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saves a region-partitioned POI storage, reloads it from disk and compares it with a vanilla one.
 * <p>
 * The reference manager is a real single-threaded {@link PoiManager}: the exact implementation the server runs
 * when the regionized storage is off. Both managers receive the same script of adds, removes and ticket
 * acquisitions, both are written to their own region directory, both directories are reopened with fresh
 * storages, and the reloaded state of the two is compared record by record and free ticket by free ticket.
 * That is the save/reload half of the milestone's guarantee, and because the reference writes the vanilla
 * format, comparing the two stored tags is also the proof that the regionized storage stores vanilla data.
 * <p>
 * Three things are checked, and they are the three ways the regionized storage could lose data across a save:
 * <ul>
 * <li>{@link #reloadedStorageAnswersTheSameRecordsAndTicketsAsTheVanillaManager}: the records, their type,
 * their free-ticket count and the village distance answer survive the write and the reload unchanged.</li>
 * <li>{@link #theStoredColumnIsTheVanillaTag}: the stored compound of every dirty column is the compound the
 * vanilla section storage writes - same {@code Sections} map, same {@code DataVersion} - so a world can be
 * written by one and read by the other, which is the file-format claim of the milestone.</li>
 * <li>{@link #reloadedSectionsBelongToTheRegionThatOwnsTheirChunk}: after the reload every section is
 * distributed to the region the layout assigns to its chunk, so a query of the owning region answers it live
 * and never falls back to the server thread.</li>
 * </ul>
 */
@Timeout(600)
class RegionPoiSaveReloadTest {
    private static final int MIN_SECTION = 0;
    private static final int MAX_SECTION = 8;
    /** Half edge of the scripted area in blocks: 5 chunks wide, so the 3-chunk layout has borders inside it. */
    private static final int AREA_RADIUS = 40;
    private static final int STEPS = 120;
    private static final int[] RADII = { 0, 1, 8, 16, 20, 32 };

    @TempDir
    Path tempDir;

    @Test
    void reloadedStorageAnswersTheSameRecordsAndTicketsAsTheVanillaManager() {
        final Path referenceDir = this.tempDir.resolve("reference");
        final Path regionizedDir = this.tempDir.resolve("regionized");
        final List<BlockPos> live;
        try (Harness harness = this.harness(referenceDir, regionizedDir)) {
            live = this.runScript(harness, 0x5A7E_0001L);
            this.save(harness);
        }
        // Both directories are closed and their IOWorkers flushed; the comparison below only sees data that
        // crossed the disk.
        assertTrue(live.size() > 20, "the script left " + live.size() + " live POI records to reload");
        try (Harness reloaded = this.harness(referenceDir, regionizedDir)) {
            this.compareAll(reloaded);
            this.compareFreeTickets(reloaded, live);
            this.compareVillageDistance(reloaded);
        }
    }

    /**
     * Closes the regionized storage without a tick and reloads it.
     * <p>
     * This is the durability the regionized storage promises and the vanilla one does not: a POI write of the
     * last tick is on disk after a clean close even though the IO worker had not picked it up yet, because
     * {@link RegionPoiStorage#close()} writes the remaining columns and waits for them. A vanilla section
     * storage drops exactly that write.
     */
    @Test
    void closingTheRegionizedStorageWritesEveryColumnItHolds() {
        final Path referenceDir = this.tempDir.resolve("close-reference");
        final Path regionizedDir = this.tempDir.resolve("close-regionized");
        final List<BlockPos> live;
        final java.util.Map<BlockPos, Integer> ticketsBeforeClose = new java.util.HashMap<>();
        try (Harness harness = this.harness(referenceDir, regionizedDir)) {
            live = this.runScript(harness, 0x5A7E_0004L);
            // No tick: every record of the script is still a dirty column of the storage.
            assertTrue(harness.storage().dirtyColumnCount() > 0, "the script left no dirty POI column behind");
            for (final BlockPos pos : live) {
                ticketsBeforeClose.put(pos, harness.regionized().getFreeTickets(pos));
            }
            harness.close();
        }
        try (Harness reloaded = this.harness(referenceDir, regionizedDir)) {
            for (final BlockPos pos : live) {
                assertTrue(reloaded.regionized().getType(pos).isPresent(), "the close of the storage lost the POI at " + pos);
                assertEquals(ticketsBeforeClose.get(pos).intValue(), reloaded.regionized().getFreeTickets(pos),
                        "the close of the storage lost the ticket count of " + pos);
            }
        }
    }

    @Test
    void theStoredColumnIsTheVanillaTag() {
        final Path referenceDir = this.tempDir.resolve("tag-reference");
        final Path regionizedDir = this.tempDir.resolve("tag-regionized");
        try (Harness harness = this.harness(referenceDir, regionizedDir)) {
            this.runScript(harness, 0x5A7E_0002L);
            this.save(harness);
        }
        final SimpleRegionStorage referenceStorage = this.storage(referenceDir);
        final SimpleRegionStorage regionizedStorage = this.storage(regionizedDir);
        try {
            int compared = 0;
            for (int chunkX = -3; chunkX <= 3; chunkX++) {
                for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                    final ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                    final Optional<CompoundTag> reference = referenceStorage.read(chunk).join();
                    final Optional<CompoundTag> regionized = regionizedStorage.read(chunk).join();
                    assertEquals(reference.isPresent(), regionized.isPresent(), "stored column of " + chunk);
                    if (reference.isEmpty()) {
                        continue;
                    }
                    assertEquals(reference.get().getCompound("Sections"), regionized.get().getCompound("Sections"),
                            "the Sections compound of " + chunk + " is not the vanilla one");
                    assertEquals(reference.get().getInt("DataVersion"), regionized.get().getInt("DataVersion"),
                            "the DataVersion of " + chunk);
                    compared++;
                }
            }
            assertTrue(compared > 0, "the script stored no POI column at all");
        } finally {
            closeQuietly(referenceStorage);
            closeQuietly(regionizedStorage);
        }
    }

    @Test
    void reloadedSectionsBelongToTheRegionThatOwnsTheirChunk() {
        final Path referenceDir = this.tempDir.resolve("mapping-reference");
        final Path regionizedDir = this.tempDir.resolve("mapping-regionized");
        final List<BlockPos> live;
        try (Harness harness = this.harness(referenceDir, regionizedDir)) {
            live = this.runScript(harness, 0x5A7E_0003L);
            this.save(harness);
        }
        try (Harness reloaded = this.harness(referenceDir, regionizedDir)) {
            // Load every column of the area on the server thread first, which is the load a chunk performs.
            for (final BlockPos pos : live) {
                reloaded.regionized().getType(pos);
            }
            reloaded.storage().publishLevel();
            int checked = 0;
            for (final BlockPos pos : live) {
                final RegionCoordinate owner = reloaded.layout().regionAt(reloaded.level(), pos);
                final RegionThreadingDiagnostics.PoiCounters before = RegionPoiStorages.counters();
                RegionPoiStorage.forceQueryRegionForTesting(owner);
                try {
                    assertEquals(reloaded.reference().getType(pos), reloaded.regionized().getType(pos),
                            "the owning region " + owner.regionX() + "/" + owner.regionZ() + " does not hold " + pos);
                } finally {
                    RegionPoiStorage.forceQueryRegionForTesting(null);
                }
                final RegionThreadingDiagnostics.PoiCounters after = RegionPoiStorages.counters();
                assertEquals(0L, after.fallbackQueries() - before.fallbackQueries(),
                        "a query of the owning region fell back to the server thread for " + pos);
                checked++;
            }
            assertEquals(live.size(), checked, "every reloaded record was checked against its owning region");
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Script and comparison
    // ---------------------------------------------------------------------------------------------------

    /**
     * Applies a deterministic script of writes and ticket changes to both managers and returns the live
     * positions.
     */
    private List<BlockPos> runScript(Harness harness, long seed) {
        final RandomSource random = RandomSource.create(seed);
        final List<BlockPos> live = new ArrayList<>();
        // Load the whole area first, so a query never pays a disk read inside the script and both managers hold
        // the same loaded columns.
        harness.reference().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList();
        harness.regionized().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList();
        for (int step = 0; step < STEPS; step++) {
            final int roll = random.nextInt(100);
            if (roll < 55 || live.isEmpty()) {
                final BlockPos pos = this.freePosition(harness, random, live);
                if (pos == null) {
                    continue;
                }
                final Holder<PoiType> type = harness.types().get(random.nextInt(harness.types().size()));
                harness.reference().add(pos, type);
                harness.regionized().add(pos, type);
                live.add(pos);
            } else if (roll < 70) {
                final BlockPos pos = live.remove(random.nextInt(live.size()));
                harness.reference().remove(pos);
                harness.regionized().remove(pos);
            } else if (roll < 92) {
                final BlockPos center = this.areaPosition(random);
                final int radius = RADII[random.nextInt(RADII.length)];
                final Predicate<Holder<PoiType>> filter = this.randomFilter(harness, random);
                assertEquals(
                        harness.reference().take(filter, (type, pos) -> true, center, radius),
                        harness.regionized().take(filter, (type, pos) -> true, center, radius),
                        "take at " + center + " radius " + radius);
            } else {
                final BlockPos pos = live.get(random.nextInt(live.size()));
                // A ticket is only released while the record still holds it, so both managers see the same call.
                if (harness.reference().getFreeTickets(pos) < harness.reference().getType(pos).orElseThrow().value().maxTickets()) {
                    assertEquals(harness.reference().release(pos), harness.regionized().release(pos), "release at " + pos);
                }
            }
        }
        return live;
    }

    /**
     * Writes the dirty columns of both managers and closes them, which is the save of a level.
     * <p>
     * The tick is the write path of both storages - it is the level tick that writes the dirty POI columns.
     * The regionized storage then writes the columns its tick left and waits for its IO worker in
     * {@link RegionPoiStorage#close()}; the vanilla section storage schedules the same writes but its close
     * does not wait for them, so the reference is given the synchronize a save with flush gives the chunk IO.
     * Without that wait the reference loses the writes its IO thread had not picked up yet, which is a vanilla
     * gap the regionized storage closes and not a difference in the stored data.
     */
    private void save(Harness harness) {
        harness.reference().tick(() -> true);
        harness.regionized().tick(() -> true);
        synchronizeSectionStorage(harness.reference());
        harness.close();
    }

    /**
     * Waits for the IO worker of a vanilla {@code SectionStorage} to write what it was given.
     * <p>
     * {@code PoiManager} keeps its {@code SimpleRegionStorage} private, so the wait goes through the field the
     * section storage holds it in. A reference manager is the vanilla implementation under test, not
     * production code of this repository, so reaching into it is the only way to compare it at a defined
     * point.
     */
    private static void synchronizeSectionStorage(PoiManager manager) {
        try {
            final java.lang.reflect.Field field = net.minecraft.world.level.chunk.storage.SectionStorage.class
                    .getDeclaredField("simpleRegionStorage");
            field.setAccessible(true);
            ((SimpleRegionStorage) field.get(manager)).synchronize(true).join();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("the vanilla section storage changed its storage field", exception);
        }
    }

    private void compareAll(Harness harness) {
        int chunks = 0;
        int records = 0;
        for (int chunkX = -3; chunkX <= 3; chunkX++) {
            for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                final ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                for (final PoiManager.Occupancy occupancy : List.of(PoiManager.Occupancy.ANY, PoiManager.Occupancy.HAS_SPACE,
                        PoiManager.Occupancy.IS_OCCUPIED)) {
                    final List<String> expected = describe(harness.reference().getInChunk(type -> true, chunk, occupancy).toList());
                    final List<String> actual = describe(harness.regionized().getInChunk(type -> true, chunk, occupancy).toList());
                    assertEquals(expected, actual, "getInChunk " + chunk + " " + occupancy);
                    if (occupancy == PoiManager.Occupancy.ANY) {
                        records += actual.size();
                    }
                }
                chunks++;
            }
        }
        assertEquals(
                describe(harness.reference().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList()),
                describe(harness.regionized().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList()),
                "getInSquare over the reloaded area");
        assertTrue(records > 0, "the reloaded area holds no POI record at all");
        // The reloaded records are also what the reference manager answers from the same directory, so the
        // comparison is a comparison of two independent reloads of the same script.
        assertTrue(chunks > 0, "no chunk was compared");
    }

    private void compareFreeTickets(Harness harness, List<BlockPos> live) {
        for (final BlockPos pos : live) {
            assertEquals(harness.reference().getType(pos), harness.regionized().getType(pos), "reloaded type at " + pos);
            assertEquals(harness.reference().getFreeTickets(pos), harness.regionized().getFreeTickets(pos),
                    "reloaded free tickets at " + pos);
            final int max = harness.reference().getType(pos).orElseThrow().value().maxTickets();
            final int free = harness.regionized().getFreeTickets(pos);
            assertTrue(free >= 0 && free <= max, "the reloaded ticket count of " + pos + " is outside 0.." + max + ": " + free);
        }
    }

    private void compareVillageDistance(Harness harness) {
        int compared = 0;
        for (int sectionX = -2; sectionX <= 2; sectionX++) {
            for (int sectionZ = -2; sectionZ <= 2; sectionZ++) {
                for (int sectionY = MIN_SECTION; sectionY < MAX_SECTION; sectionY++) {
                    final net.minecraft.core.SectionPos section = net.minecraft.core.SectionPos.of(sectionX, sectionY, sectionZ);
                    assertEquals(harness.reference().sectionsToVillage(section), harness.regionized().sectionsToVillage(section),
                            "sectionsToVillage at " + section);
                    compared++;
                }
            }
        }
        assertTrue(compared > 0, "no section distance was compared");
    }

    private BlockPos freePosition(Harness harness, RandomSource random, List<BlockPos> live) {
        for (int attempt = 0; attempt < 16; attempt++) {
            final BlockPos candidate = this.areaPosition(random);
            if (!live.contains(candidate) && harness.reference().getType(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos areaPosition(RandomSource random) {
        return new BlockPos(
                random.nextInt(AREA_RADIUS * 2) - AREA_RADIUS,
                1 + random.nextInt(120),
                random.nextInt(AREA_RADIUS * 2) - AREA_RADIUS);
    }

    private Predicate<Holder<PoiType>> randomFilter(Harness harness, RandomSource random) {
        return switch (random.nextInt(4)) {
            case 0 -> type -> type.is(PoiTypes.HOME);
            case 1 -> type -> type.is(PoiTypeTags.VILLAGE);
            case 2 -> type -> type.is(PoiTypes.NETHER_PORTAL);
            default -> type -> true;
        };
    }

    private static List<String> describe(List<PoiRecord> records) {
        final List<String> described = new ArrayList<>(records.size());
        for (final PoiRecord record : records) {
            described.add(record.getPos() + "/" + record.getPoiType().getRegisteredName() + "/" + record.getFreeTickets());
        }
        return described;
    }

    // ---------------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------------

    /**
     * One pair of managers over one pair of directories, plus the regionized storage of the second.
     */
    private record Harness(PoiManager reference, PoiManager regionized, RegionPoiStorage storage, ServerLevel level, RegionLayout layout,
            List<Holder<PoiType>> types) implements AutoCloseable {
        @Override
        public void close() {
            try {
                this.regionized().close();
                this.reference().close();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private Harness harness(Path referenceDir, Path regionizedDir) {
        final RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final DataFixer dataFixer = mock(DataFixer.class);
        when(dataFixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(MIN_SECTION * 16, (MAX_SECTION - MIN_SECTION) * 16);
        final net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter errorReporter = mock(
                net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter.class);
        final PoiManager reference = new PoiManager(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                referenceDir,
                dataFixer,
                false,
                registryAccess,
                errorReporter,
                heightAccessor);
        final PoiManager regionized = new PoiManager(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                regionizedDir,
                dataFixer,
                false,
                registryAccess,
                errorReporter,
                heightAccessor);
        final SimpleRegionStorage storage = new SimpleRegionStorage(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                regionizedDir,
                dataFixer,
                false,
                DataFixTypes.POI_CHUNK);
        final ServerLevel level = mock(ServerLevel.class);
        final RegionLayout layout = RegionLayout.FIXED_3X3;
        final RegionPoiStorage regionPoiStorage = new RegionPoiStorage(level, layout, storage, registryAccess, heightAccessor);
        regionized.attachRegionPoiStorage(regionPoiStorage);
        RegionPoiStorages.resetCountersForTesting();
        return new Harness(reference, regionized, regionPoiStorage, level, layout, this.types());
    }

    private SimpleRegionStorage storage(Path dir) {
        final DataFixer dataFixer = mock(DataFixer.class);
        when(dataFixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return new SimpleRegionStorage(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                dir,
                dataFixer,
                false,
                DataFixTypes.POI_CHUNK);
    }

    private List<Holder<PoiType>> types() {
        return List.of(PoiTypes.HOME, PoiTypes.MEETING, PoiTypes.FARMER, PoiTypes.LIBRARIAN, PoiTypes.ARMORER,
                PoiTypes.BEEHIVE, PoiTypes.BEE_NEST, PoiTypes.NETHER_PORTAL, PoiTypes.LODESTONE, PoiTypes.LIGHTNING_ROD)
                .stream()
                .map(key -> BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolder(key).orElseThrow())
                .map(holder -> (Holder<PoiType>) holder)
                .toList();
    }

    private static void closeQuietly(SimpleRegionStorage storage) {
        assertNotNull(storage);
        try {
            storage.close();
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }
}
