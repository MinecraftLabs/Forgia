/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPoiStorage;
import net.neoforged.neoforge.server.threading.region.RegionPoiStorages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares the region-partitioned POI storage with a reference single-threaded {@link PoiManager}.
 * <p>
 * The reference manager is a real vanilla {@code PoiManager} on its own storage directory: it is the exact
 * implementation the server runs when the POI switch is off, so comparing against it is comparing against
 * vanilla, not against a reimplementation. The regionized manager is a second {@code PoiManager} whose
 * primitive entry points are delegated to a {@link RegionPoiStorage}; everything above them - the square and
 * range filters, the closest-first sort, the random shuffle, the take loop - is the vanilla code, so a
 * difference can only come from the storage.
 * <p>
 * Both managers receive the same randomized operation script (beds, workstations, bells, hives, portals,
 * lodestones, ticket acquisitions and releases) and every query type is compared after each step: same
 * records, same order, same free-ticket counts. The script runs over several fixed region sizes and publish
 * cadences, because the size of a region and the moment a snapshot is published are what the equivalence has
 * to survive.
 */
@Timeout(600)
class RegionPoiEquivalenceTest {
    private static final int MIN_SECTION = 0;
    private static final int MAX_SECTION = 8;
    private static final int AREA_RADIUS = 72;
    private static final int STEPS = 60;
    private static final int[] RADII = { 0, 1, 8, 16, 20, 48 };

    @TempDir
    Path tempDir;

    /**
     * The two managers of one comparison and the regionized storage between them.
     */
    private record Harness(PoiManager reference, PoiManager regionized, RegionPoiStorage storage, ServerLevel level, RegionLayout layout,
            List<Holder<PoiType>> types) implements AutoCloseable {
        @Override
        public void close() {
            try {
                this.regionized.close();
                this.reference.close();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 3, 4, 8 })
    void everyQueryMatchesTheReferenceManagerForEveryRegionSize(int regionChunkSize) {
        final RegionLayout layout = RegionLayout.fixed(regionChunkSize);
        try (Harness harness = this.harness(layout, "size-" + regionChunkSize)) {
            this.runScript(harness, layout, 0x5EED_0000L + regionChunkSize, STEPS, 1);
        }
    }

    @Test
    void everyQueryMatchesTheReferenceManagerForSeveralPublishCadences() {
        // A publish cadence of five means several writes of one region are visible to another region only after
        // the next publish, which is the staleness the storage promises. The comparison happens after the
        // publish, so the published state has to be the live state exactly.
        final RegionLayout layout = RegionLayout.FIXED_3X3;
        try (Harness harness = this.harness(layout, "cadence")) {
            this.runScript(harness, layout, 0xC0FFEE, STEPS, 5);
        }
    }

    private void runScript(Harness harness, RegionLayout layout, long seed, int steps, int publishCadence) {
        final RandomSource random = RandomSource.create(seed);
        final List<BlockPos> live = new ArrayList<>();
        // Load every column of the area on both managers first, so a query never pays a disk read in the loop
        // and both managers hold the same loaded columns.
        harness.reference().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList();
        harness.regionized().getInSquare(type -> true, BlockPos.ZERO, AREA_RADIUS, PoiManager.Occupancy.ANY).toList();

        for (int step = 0; step < steps; step++) {
            this.applyOperation(harness, random, live);
            final boolean published = (step + 1) % publishCadence == 0;
            if (published) {
                harness.storage().publishLevel();
            }
            this.compareStep(harness, random, step, published);
        }
        harness.storage().publishLevel();
        this.compareQueries(harness, random, steps);
        this.compareFreeTickets(harness, live);
        this.compareVillageDistance(harness);
        // The published path was actually taken, so the equivalence above is not only the server-thread path.
        assertTrue(RegionPoiStorages.counters().publishedQueries() > 0L, "the published snapshot path was exercised");
    }

    /**
     * Applies one randomized operation to both managers.
     */
    private void applyOperation(Harness harness, RandomSource random, List<BlockPos> live) {
        final int roll = random.nextInt(100);
        if (roll < 50 || live.isEmpty()) {
            final BlockPos pos = this.freePosition(harness, random, live);
            if (pos == null) {
                return;
            }
            final Holder<PoiType> type = harness.types().get(random.nextInt(harness.types().size()));
            harness.reference().add(pos, type);
            harness.regionized().add(pos, type);
            live.add(pos);
            return;
        }
        if (roll < 70) {
            final BlockPos pos = live.remove(random.nextInt(live.size()));
            harness.reference().remove(pos);
            harness.regionized().remove(pos);
            return;
        }
        if (roll < 85) {
            final BlockPos center = this.areaPosition(random);
            final int radius = RADII[random.nextInt(RADII.length)];
            final Predicate<Holder<PoiType>> filter = this.randomFilter(harness, random);
            final Optional<BlockPos> expected = harness.reference().take(filter, (type, pos) -> true, center, radius);
            final Optional<BlockPos> actual = harness.regionized().take(filter, (type, pos) -> true, center, radius);
            assertEquals(expected, actual, "take at " + center + " radius " + radius);
            return;
        }
        final BlockPos pos = live.get(random.nextInt(live.size()));
        assertEquals(harness.reference().release(pos), harness.regionized().release(pos), "release at " + pos);
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

    /**
     * Compares the two managers after one script step: the square query and the free-ticket counts, from the
     * server thread and from the region of the queried center. The full battery runs once at the end.
     */
    private void compareStep(Harness harness, RandomSource random, int step, boolean published) {
        final BlockPos center = this.areaPosition(random);
        // The server thread reads every region live, so its answer is exact after every step, published or not.
        assertEquals(
                positions(harness.reference().getInRange(type -> true, center, 20, PoiManager.Occupancy.ANY)),
                positions(harness.regionized().getInRange(type -> true, center, 20, PoiManager.Occupancy.ANY)),
                "getInRange at step " + step);
        if (!published) {
            // A foreign region is allowed to be one publish behind; the full comparison after the last publish
            // is what proves the published state is exact.
            return;
        }
        final RegionCoordinate perspective = harness.layout().regionAt(harness.level(), center);
        RegionPoiStorage.forceQueryRegionForTesting(perspective);
        try {
            assertEquals(
                    positions(harness.reference().getInSquare(type -> true, center, 48, PoiManager.Occupancy.ANY)),
                    positions(harness.regionized().getInSquare(type -> true, center, 48, PoiManager.Occupancy.ANY)),
                    "getInSquare at step " + step + " from " + perspective.regionX() + "/" + perspective.regionZ());
        } finally {
            RegionPoiStorage.forceQueryRegionForTesting(null);
        }
    }

    /**
     * Compares every query type at a handful of centers, from the server thread, from the region of each
     * center and from every region around the origin. Every perspective has to agree with the reference,
     * because the published snapshot of a region is what a foreign query reads.
     */
    private void compareQueries(Harness harness, RandomSource random, int step) {
        final List<BlockPos> centers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            centers.add(this.areaPosition(random));
        }
        centers.add(BlockPos.ZERO);
        final List<RegionCoordinate> perspectives = new ArrayList<>();
        perspectives.add(null);
        for (final BlockPos center : centers) {
            perspectives.add(harness.layout().regionAt(harness.level(), center));
        }
        for (int regionX = -1; regionX <= 1; regionX++) {
            for (int regionZ = -1; regionZ <= 1; regionZ++) {
                perspectives.add(new RegionCoordinate(harness.level(), regionX, regionZ));
            }
        }
        for (final RegionCoordinate perspective : perspectives) {
            RegionPoiStorage.forceQueryRegionForTesting(perspective);
            try {
                for (final BlockPos center : centers) {
                    for (final int radius : new int[] { 0, 8, 48 }) {
                        for (final PoiManager.Occupancy occupancy : PoiManager.Occupancy.values()) {
                            this.compareAt(harness, center, radius, occupancy, type -> true, step, perspective);
                            this.compareAt(harness, center, radius, occupancy, type -> type.is(PoiTypeTags.VILLAGE), step, perspective);
                            this.compareAt(harness, center, radius, occupancy, type -> type.is(PoiTypes.HOME), step, perspective);
                        }
                    }
                }
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
        }
    }

    private void compareAt(Harness harness, BlockPos center, int radius, PoiManager.Occupancy occupancy,
            Predicate<Holder<PoiType>> filter, int step, RegionCoordinate perspective) {
        final String where = "step " + step + " center " + center + " radius " + radius + " occupancy " + occupancy
                + " perspective " + (perspective == null ? "server" : perspective.regionX() + "/" + perspective.regionZ());

        assertEquals(
                positions(harness.reference().getInSquare(filter, center, radius, occupancy)),
                positions(harness.regionized().getInSquare(filter, center, radius, occupancy)),
                "getInSquare " + where);
        assertEquals(
                positions(harness.reference().getInRange(filter, center, radius, occupancy)),
                positions(harness.regionized().getInRange(filter, center, radius, occupancy)),
                "getInRange " + where);
        assertEquals(
                harness.reference().findAll(filter, pos -> true, center, radius, occupancy).toList(),
                harness.regionized().findAll(filter, pos -> true, center, radius, occupancy).toList(),
                "findAll " + where);
        assertEquals(
                harness.reference().find(filter, pos -> pos.getY() % 2 == 0, center, radius, occupancy),
                harness.regionized().find(filter, pos -> pos.getY() % 2 == 0, center, radius, occupancy),
                "find " + where);
        assertEquals(
                harness.reference().findClosest(filter, center, radius, occupancy),
                harness.regionized().findClosest(filter, center, radius, occupancy),
                "findClosest " + where);
        assertEquals(
                harness.reference().findClosest(filter, pos -> pos.getY() % 3 == 0, center, radius, occupancy),
                harness.regionized().findClosest(filter, pos -> pos.getY() % 3 == 0, center, radius, occupancy),
                "findClosest filtered " + where);
        assertEquals(
                pairs(harness.reference().findAllWithType(filter, pos -> true, center, radius, occupancy)),
                pairs(harness.regionized().findAllWithType(filter, pos -> true, center, radius, occupancy)),
                "findAllWithType " + where);
        assertEquals(
                pairs(harness.reference().findAllClosestFirstWithType(filter, pos -> true, center, radius, occupancy)),
                pairs(harness.regionized().findAllClosestFirstWithType(filter, pos -> true, center, radius, occupancy)),
                "findAllClosestFirstWithType " + where);
        assertEquals(
                harness.reference().findClosestWithType(filter, center, radius, occupancy),
                harness.regionized().findClosestWithType(filter, center, radius, occupancy),
                "findClosestWithType " + where);
        assertEquals(
                harness.reference().getCountInRange(filter, center, radius, occupancy),
                harness.regionized().getCountInRange(filter, center, radius, occupancy),
                "getCountInRange " + where);
        final RandomSource referenceRandom = RandomSource.create(radius * 31L + center.asLong());
        final RandomSource regionizedRandom = RandomSource.create(radius * 31L + center.asLong());
        assertEquals(
                harness.reference().getRandom(filter, pos -> true, occupancy, center, radius, referenceRandom),
                harness.regionized().getRandom(filter, pos -> true, occupancy, center, radius, regionizedRandom),
                "getRandom " + where);
    }

    private void compareFreeTickets(Harness harness, List<BlockPos> live) {
        for (final BlockPos pos : live) {
            assertEquals(harness.reference().getFreeTickets(pos), harness.regionized().getFreeTickets(pos), "free tickets at " + pos);
            assertEquals(harness.reference().getType(pos), harness.regionized().getType(pos), "type at " + pos);
            assertTrue(harness.regionized().exists(pos, type -> true), "the POI at " + pos + " still exists");
        }
        for (final Holder<PoiType> type : harness.types()) {
            for (final BlockPos pos : live) {
                assertEquals(harness.reference().existsAtPosition(type.unwrapKey().orElseThrow(), pos),
                        harness.regionized().existsAtPosition(type.unwrapKey().orElseThrow(), pos),
                        "existsAtPosition at " + pos);
            }
        }
    }

    private void compareVillageDistance(Harness harness) {
        for (int sectionX = -6; sectionX <= 6; sectionX++) {
            for (int sectionZ = -6; sectionZ <= 6; sectionZ++) {
                for (int sectionY = MIN_SECTION; sectionY < MAX_SECTION; sectionY++) {
                    final SectionPos sectionPos = SectionPos.of(sectionX, sectionY, sectionZ);
                    assertEquals(harness.reference().sectionsToVillage(sectionPos), harness.regionized().sectionsToVillage(sectionPos),
                            "sectionsToVillage at " + sectionPos);
                }
            }
        }
    }

    private static List<BlockPos> positions(Stream<PoiRecord> stream) {
        return stream.map(PoiRecord::getPos).toList();
    }

    private static List<Pair<Holder<PoiType>, BlockPos>> pairs(Stream<Pair<Holder<PoiType>, BlockPos>> stream) {
        return stream.toList();
    }

    private Harness harness(RegionLayout layout, String name) {
        final RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final DataFixer dataFixer = mock(DataFixer.class);
        when(dataFixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        // A real height accessor: the reference manager reads it once per section of every query, so a mock
        // would dominate the run.
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(MIN_SECTION * 16, (MAX_SECTION - MIN_SECTION) * 16);
        final ChunkIOErrorReporter errorReporter = mock(ChunkIOErrorReporter.class);
        final PoiManager reference = new PoiManager(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                this.tempDir.resolve(name + "-reference"),
                dataFixer,
                false,
                registryAccess,
                errorReporter,
                heightAccessor);
        final PoiManager regionized = new PoiManager(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                this.tempDir.resolve(name + "-regionized"),
                dataFixer,
                false,
                registryAccess,
                errorReporter,
                heightAccessor);
        final SimpleRegionStorage storage = new SimpleRegionStorage(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                this.tempDir.resolve(name + "-storage"),
                dataFixer,
                false,
                DataFixTypes.POI_CHUNK);
        final ServerLevel level = mock(ServerLevel.class);
        final RegionPoiStorage regionPoiStorage = new RegionPoiStorage(level, layout, storage, registryAccess, heightAccessor);
        regionized.attachRegionPoiStorage(regionPoiStorage);
        RegionPoiStorages.resetCountersForTesting();
        return new Harness(reference, regionized, regionPoiStorage, level, layout, this.types());
    }

    private List<Holder<PoiType>> types() {
        // The vanilla POI types of a village, a hive, a portal and a lodestone: beds, workstations, the meeting
        // point, hives, portals and lodestones are exactly the kinds the milestone names.
        return List.of(PoiTypes.HOME, PoiTypes.MEETING, PoiTypes.FARMER, PoiTypes.LIBRARIAN, PoiTypes.ARMORER,
                PoiTypes.BEEHIVE, PoiTypes.BEE_NEST, PoiTypes.NETHER_PORTAL, PoiTypes.LODESTONE, PoiTypes.LIGHTNING_ROD)
                .stream()
                .map(key -> BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolder(key).orElseThrow())
                .map(holder -> (Holder<PoiType>) holder)
                .toList();
    }
}
