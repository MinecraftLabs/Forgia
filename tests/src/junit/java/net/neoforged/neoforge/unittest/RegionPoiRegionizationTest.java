/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.datafixers.DataFixer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.ChunkIOErrorReporter;
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
 * Behaviour tests of the region-partitioned POI storage that the equivalence test cannot express: a ticket
 * claimed across a region boundary, the one-publish staleness of a foreign answer, the refusal of a take on a
 * record a publish has already removed, concurrent writers of two regions, and the save/load round trip.
 * <p>
 * Every test drives the production query path through {@link RegionPoiStorage#forceQueryRegionForTesting},
 * which makes the calling thread take the worker branch of a region for exactly one region, so the live and
 * published decisions are the ones a real worker makes.
 */
@Timeout(300)
class RegionPoiRegionizationTest {
    private static final int MIN_SECTION = 0;
    private static final int MAX_SECTION = 8;
    /** Region (0,0) owns chunks 0..2, i.e. blocks 0..47; region (1,0) owns chunks 3..5. */
    private static final BlockPos POSITION_IN_A = new BlockPos(40, 64, 5);
    private static final BlockPos POSITION_IN_B = new BlockPos(50, 64, 5);

    @TempDir
    Path tempDir;

    /**
     * A villager in region A claims and releases a bed in region B, and the free-ticket count is consistent on
     * both sides: the ticket is booked on the live record of B, so the second take of the last ticket fails
     * from either region and a release from A gives it back.
     */
    @Test
    void aTicketClaimedAcrossARegionBoundaryIsConsistentOnBothSides() {
        try (Fixture fixture = this.fixture("tickets")) {
            final Holder<PoiType> bed = fixture.type(PoiTypes.HOME);
            fixture.storage().add(POSITION_IN_B, bed);
            fixture.storage().publishLevel();
            final RegionCoordinate regionA = fixture.regionAt(POSITION_IN_A);
            final RegionCoordinate regionB = fixture.regionAt(POSITION_IN_B);
            assertNotEquals(regionA, regionB, "the two positions belong to different regions");

            RegionPoiStorage.forceQueryRegionForTesting(regionA);
            try {
                assertEquals(1, fixture.storage().getFreeTickets(POSITION_IN_B), "a bed starts with its one ticket");
                final Optional<BlockPos> claimed = fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_A, 16);
                assertEquals(Optional.of(POSITION_IN_B), claimed, "the villager of A claims the bed of B");
                assertEquals(0, fixture.storage().getFreeTickets(POSITION_IN_B), "the ticket of B is booked immediately");
                assertTrue(fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_A, 16).isEmpty(),
                        "the last ticket cannot be taken twice");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }

            RegionPoiStorage.forceQueryRegionForTesting(regionB);
            try {
                assertEquals(0, fixture.storage().getFreeTickets(POSITION_IN_B), "the owner sees the booked ticket");
                assertTrue(fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_B, 16).isEmpty(),
                        "the owner cannot take the ticket A booked");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }

            RegionPoiStorage.forceQueryRegionForTesting(regionA);
            try {
                assertTrue(fixture.storage().release(POSITION_IN_B), "the villager of A releases the bed of B");
                assertEquals(1, fixture.storage().getFreeTickets(POSITION_IN_B), "the ticket is free again");
                assertEquals(Optional.of(POSITION_IN_B), fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_A, 16));
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
        }
    }

    /**
     * A POI added in region B is visible to a local query of B in the same tick and to a query of region A
     * only after the publish, which is the staleness the storage promises. Both answers are counted.
     */
    @Test
    void aForeignAnswerIsAtMostOnePublishOldAndALocalAnswerIsLive() {
        try (Fixture fixture = this.fixture("staleness")) {
            final Holder<PoiType> workstation = fixture.type(PoiTypes.FARMER);
            final RegionCoordinate regionA = fixture.regionAt(POSITION_IN_A);
            final RegionCoordinate regionB = fixture.regionAt(POSITION_IN_B);
            // Load the columns of both regions and publish the empty state, so a query below has a published
            // answer to read and does not fall back.
            fixture.storage().add(POSITION_IN_B, fixture.type(PoiTypes.LODESTONE));
            fixture.storage().remove(POSITION_IN_B);
            fixture.storage().publishLevel();
            RegionPoiStorages.resetCountersForTesting();

            fixture.storage().add(POSITION_IN_B, workstation);
            assertTrue(fixture.storage().exists(POSITION_IN_B, type -> type.is(PoiTypes.FARMER)), "the owner sees its write immediately");
            RegionPoiStorage.forceQueryRegionForTesting(regionA);
            try {
                assertFalse(fixture.storage().exists(POSITION_IN_B, type -> type.is(PoiTypes.FARMER)),
                        "a foreign region is one publish behind");
                assertTrue(around(fixture.storage(), type -> type.is(PoiTypes.FARMER), POSITION_IN_A, 16).isEmpty(),
                        "the stale snapshot does not hold the new record");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
            final RegionThreadingDiagnostics.PoiCounters stale = RegionPoiStorages.counters();
            assertTrue(stale.publishedQueries() >= 2L, "the stale answers were counted as published reads: " + stale);

            RegionPoiStorage.forceQueryRegionForTesting(regionB);
            try {
                assertEquals(List.of(POSITION_IN_B), around(fixture.storage(), type -> type.is(PoiTypes.FARMER), POSITION_IN_B, 16),
                        "the local answer is live");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
            final long localQueries = RegionPoiStorages.counters().localQueries();
            assertTrue(localQueries > 0L, "the local answer was counted as a live read");

            fixture.storage().publishLevel();
            RegionPoiStorage.forceQueryRegionForTesting(regionA);
            try {
                assertEquals(List.of(POSITION_IN_B), around(fixture.storage(), type -> type.is(PoiTypes.FARMER), POSITION_IN_A, 16),
                        "the next publish makes the foreign write visible");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
        }
    }

    /**
     * A take whose candidate the owning region already removed is refused as stale instead of booking a ticket
     * on a POI that no longer exists.
     */
    @Test
    void aTakeOnARecordAPublishHasRemovedIsRefusedAsStale() {
        try (Fixture fixture = this.fixture("stale-take")) {
            final Holder<PoiType> bed = fixture.type(PoiTypes.HOME);
            fixture.storage().add(POSITION_IN_B, bed);
            fixture.storage().publishLevel();
            fixture.storage().remove(POSITION_IN_B);
            final RegionCoordinate regionA = fixture.regionAt(POSITION_IN_A);
            RegionPoiStorages.resetCountersForTesting();

            RegionPoiStorage.forceQueryRegionForTesting(regionA);
            try {
                // The published snapshot of B still holds the removed record, so the candidate list is not
                // empty; the acquisition on the live record is what refuses it.
                assertTrue(around(fixture.storage(), type -> true, POSITION_IN_A, 16).contains(POSITION_IN_B),
                        "the stale snapshot still lists the record");
                assertTrue(fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_A, 16).isEmpty(),
                        "a stale candidate is never taken");
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
            assertEquals(1L, RegionPoiStorages.counters().staleTakes(), "the refused take is counted as stale");
        }
    }

    /**
     * Two regions write their own sections from two threads while a third thread publishes and a fourth reads
     * the published snapshots. No reader ever sees a half-updated section: every observed record list is a
     * duplicate-free subset of the writes and grows monotonically.
     */
    @Test
    void concurrentRegionWritersNeverPublishAHalfUpdatedSection() throws Exception {
        try (Fixture fixture = this.fixture("concurrency")) {
            final Holder<PoiType> poiType = fixture.type(PoiTypes.MEETING);
            final int perRegion = 1200;
            final List<BlockPos> positionsInA = new ArrayList<>();
            final List<BlockPos> positionsInB = new ArrayList<>();
            for (int index = 0; index < perRegion; index++) {
                // Every record of one writer lands in one section, so the copy-on-write capture of that section
                // is what the reader races.
                positionsInA.add(new BlockPos(index % 16, 64 + index / 256, (index / 16) % 16));
                positionsInB.add(new BlockPos(48 + index % 16, 64 + index / 256, (index / 16) % 16));
            }
            final RegionCoordinate regionA = fixture.regionAt(POSITION_IN_A);
            final RegionCoordinate regionB = fixture.regionAt(POSITION_IN_B);
            // Both sections are loaded and published empty first, so the reader never falls back.
            fixture.storage().publishLevel();
            fixture.storage().add(positionsInA.get(0), poiType);
            fixture.storage().add(positionsInB.get(0), poiType);
            fixture.storage().publishLevel();

            final AtomicBoolean writing = new AtomicBoolean(true);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch started = new CountDownLatch(3);
            final Thread writerA = this.writer("poi-writer-a", regionA, fixture, poiType, positionsInA, started, writing, failure);
            final Thread writerB = this.writer("poi-writer-b", regionB, fixture, poiType, positionsInB, started, writing, failure);
            final Thread publisher = new Thread(() -> {
                RegionPoiStorage.forceQueryRegionForTesting(null);
                started.countDown();
                while (writing.get()) {
                    fixture.storage().publishLevel();
                    Thread.onSpinWait();
                }
            }, "poi-publisher");
            final List<String> seenCounts = new ArrayList<>();
            final Thread reader = new Thread(() -> {
                started.countDown();
                int lastCount = 0;
                try {
                    while (writing.get()) {
                        RegionPoiStorage.forceQueryRegionForTesting(regionA);
                        final List<BlockPos> records = around(fixture.storage(), type -> true, POSITION_IN_B, 32);
                        final Set<BlockPos> distinct = new HashSet<>(records);
                        assertEquals(records.size(), distinct.size(), "a published section is never torn: " + records.size());
                        assertTrue(lastCount <= records.size(), "the published view only grows: " + lastCount + " -> " + records.size());
                        lastCount = records.size();
                        seenCounts.add("" + records.size());
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    RegionPoiStorage.forceQueryRegionForTesting(null);
                }
            }, "poi-reader");

            writerA.start();
            writerB.start();
            publisher.start();
            reader.start();
            assertTrue(started.await(30, TimeUnit.SECONDS), "every thread started");
            writerA.join(60_000L);
            writerB.join(60_000L);
            writing.set(false);
            publisher.join(30_000L);
            reader.join(30_000L);
            if (failure.get() != null) {
                throw new AssertionError("a concurrent reader observed an inconsistent section", failure.get());
            }
            // The reader must have read published snapshots of the other region, and every sample it took was
            // internally consistent; how many samples the scheduler let it take is not a property of the
            // storage, so it is not asserted.
            assertTrue(RegionPoiStorages.counters().publishedQueries() > 0L, "the reader read a published snapshot");
            fixture.storage().publishLevel();
            RegionPoiStorage.forceQueryRegionForTesting(null);
            // Both writers wrote one section; neither lost a record and neither saw a foreign one.
            assertEquals(perRegion, fixture.storage().getInChunk(type -> true, new ChunkPos(3, 0), PoiManager.Occupancy.ANY).count(),
                    "every record of region B survived the concurrent publish");
            assertEquals(perRegion, fixture.storage().getInChunk(type -> true, new ChunkPos(0, 0), PoiManager.Occupancy.ANY).count(),
                    "every record of region A survived the concurrent publish");
        }
    }

    /**
     * {@return the positions of the square around {@code center}, read through the storage's chunk query}
     * <p>
     * The chunk iteration and the square filter are the ones {@code PoiManager.getInSquare} uses, so this is
     * the regionized answer for the square without a manager around the storage.
     */
    private static List<BlockPos> around(RegionPoiStorage storage, java.util.function.Predicate<Holder<PoiType>> filter, BlockPos center, int radius) {
        final List<BlockPos> positions = new ArrayList<>();
        for (final ChunkPos chunk : ChunkPos.rangeClosed(new ChunkPos(center), Math.floorDiv(radius, 16) + 1).toList()) {
            storage.getInChunk(filter, chunk, PoiManager.Occupancy.ANY).forEach(record -> {
                final BlockPos pos = record.getPos();
                if (Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getZ() - center.getZ()) <= radius) {
                    positions.add(pos);
                }
            });
        }
        return positions;
    }

    private Thread writer(String name, RegionCoordinate region, Fixture fixture, Holder<PoiType> type, List<BlockPos> positions,
            CountDownLatch started, AtomicBoolean writing, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            RegionPoiStorage.forceQueryRegionForTesting(region);
            started.countDown();
            try {
                int written = 0;
                for (final BlockPos pos : positions) {
                    fixture.storage().add(pos, type);
                    if (++written % 32 == 0) {
                        // Give the publisher and the reader of the other region time to interleave, so the
                        // published view is observed while it grows.
                        Thread.yield();
                    }
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                RegionPoiStorage.forceQueryRegionForTesting(null);
            }
        }, name);
    }

    /**
     * The regionized storage writes the same POI column the vanilla manager writes, and a reload of both
     * produces the same records in the same order with the same free-ticket counts.
     */
    @Test
    void theSaveFormatIsTheVanillaFormatAndAReloadRestoresTheSameRecords() {
        final Holder<PoiType> bed = this.type(PoiTypes.HOME);
        final Holder<PoiType> workstation = this.type(PoiTypes.FARMER);
        final List<BlockPos> positions = List.of(
                new BlockPos(3, 64, 3), new BlockPos(20, 64, 20), new BlockPos(41, 64, 41), new BlockPos(52, 64, 52),
                new BlockPos(10, 70, 44), new BlockPos(60, 30, 4));
        final Path referenceDir = this.tempDir.resolve("save-reference");
        final Path regionizedDir = this.tempDir.resolve("save-regionized");
        final RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final DataFixer dataFixer = this.dataFixer();
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(MIN_SECTION * 16, (MAX_SECTION - MIN_SECTION) * 16);
        try {
            final PoiManager reference = this.manager(referenceDir, registryAccess, dataFixer, heightAccessor);
            final SimpleRegionStorage regionizedStorage = new SimpleRegionStorage(
                    new RegionStorageInfo("test", Level.OVERWORLD, "poi"), regionizedDir, dataFixer, false, DataFixTypes.POI_CHUNK);
            final ServerLevel level = mock(ServerLevel.class);
            final RegionPoiStorage storage = new RegionPoiStorage(level, RegionLayout.FIXED_3X3, regionizedStorage, registryAccess, heightAccessor);
            for (int index = 0; index < positions.size(); index++) {
                final BlockPos pos = positions.get(index);
                final Holder<PoiType> type = index % 2 == 0 ? bed : workstation;
                reference.add(pos, type);
                storage.add(pos, type);
            }
            // Book one ticket on both sides, so the saved free-ticket count is compared too.
            reference.take(type -> true, (type, pos) -> pos.equals(positions.get(0)), positions.get(0), 0);
            storage.take(type -> true, (type, pos) -> pos.equals(positions.get(0)), positions.get(0), 0);
            reference.tick(() -> true);
            storage.tick(() -> true);
            regionizedStorage.synchronize(true);
            // Closing the reference manager closes its IO worker, which completes its pending column writes, so
            // both directories are complete before the tags are compared.
            reference.close();

            // The two managers wrote the same columns with the same tag: this is the format check, not only a
            // reload check.
            for (final ChunkPos chunk : Set.of(new ChunkPos(positions.get(0)), new ChunkPos(positions.get(2)),
                    new ChunkPos(positions.get(4)), new ChunkPos(positions.get(5)))) {
                final Optional<CompoundTag> referenceTag = this.read(referenceDir, dataFixer, chunk);
                final Optional<CompoundTag> regionizedTag = this.read(regionizedDir, dataFixer, chunk);
                assertEquals(referenceTag, regionizedTag, "the saved column of " + chunk + " is the vanilla column");
            }

            // Reload the regionized storage from its own files and compare with the reference data reloaded
            // from its files, in the same query order.
            final PoiManager reloadedReference = this.manager(referenceDir, registryAccess, dataFixer, heightAccessor);
            final SimpleRegionStorage reloadedStorage = new SimpleRegionStorage(
                    new RegionStorageInfo("test", Level.OVERWORLD, "poi"), regionizedDir, dataFixer, false, DataFixTypes.POI_CHUNK);
            final PoiManager reloadedRegionized = this.manager(this.tempDir.resolve("unused"), registryAccess, dataFixer, heightAccessor);
            final RegionPoiStorage reloaded = new RegionPoiStorage(level, RegionLayout.FIXED_3X3, reloadedStorage, registryAccess, heightAccessor);
            reloadedRegionized.attachRegionPoiStorage(reloaded);
            for (final BlockPos pos : positions) {
                assertEquals(reloadedReference.getType(pos), reloadedRegionized.getType(pos), "reloaded type at " + pos);
                assertEquals(reloadedReference.getFreeTickets(pos), reloadedRegionized.getFreeTickets(pos), "reloaded tickets at " + pos);
            }
            assertEquals(
                    reloadedReference.getInSquare(type -> true, BlockPos.ZERO, 64, PoiManager.Occupancy.ANY).map(PoiRecord::getPos).toList(),
                    reloadedRegionized.getInSquare(type -> true, BlockPos.ZERO, 64, PoiManager.Occupancy.ANY).map(PoiRecord::getPos).toList(),
                    "the reloaded records are in the same order");
            reloadedReference.close();
            reloadedRegionized.close();
            reloadedStorage.close();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void liveQueryInvokesPredicateOncePerSectionType() {
        try (Fixture fixture = this.fixture("predicate-order")) {
            fixture.storage().add(POSITION_IN_A, fixture.type(PoiTypes.HOME));
            fixture.storage().add(POSITION_IN_A.above(), fixture.type(PoiTypes.HOME));
            final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            assertEquals(2, fixture.storage().getInChunk(type -> {
                calls.incrementAndGet();
                return true;
            }, new ChunkPos(POSITION_IN_A), PoiManager.Occupancy.ANY).count());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void oppositeRegionPredicatesCanTakeTicketsWithoutHoldingLocalLocks() throws Exception {
        final Fixture fixture = this.fixture("predicate-locks");
        {
            fixture.storage().add(POSITION_IN_A, fixture.type(PoiTypes.HOME));
            fixture.storage().add(POSITION_IN_B, fixture.type(PoiTypes.HOME));
            fixture.storage().publishLevel();
            final CountDownLatch predicates = new CountDownLatch(2);
            final CountDownLatch completed = new CountDownLatch(2);
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            for (final BlockPos local : List.of(POSITION_IN_A, POSITION_IN_B)) {
                final BlockPos foreign = local.equals(POSITION_IN_A) ? POSITION_IN_B : POSITION_IN_A;
                final Thread worker = new Thread(() -> {
                    RegionPoiStorage.forceQueryRegionForTesting(fixture.regionAt(local));
                    try {
                        fixture.storage().getInChunk(type -> {
                            predicates.countDown();
                            try {
                                assertTrue(predicates.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException exception) {
                                throw new AssertionError(exception);
                            }
                            assertEquals(Optional.of(foreign), fixture.storage().take(t -> true, (t, pos) -> true, foreign, 0));
                            return true;
                        }, new ChunkPos(local), PoiManager.Occupancy.ANY).toList();
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    } finally {
                        RegionPoiStorage.forceQueryRegionForTesting(null);
                        completed.countDown();
                    }
                }, "poi-predicate-lock-test");
                worker.setDaemon(true);
                worker.start();
            }
            assertTrue(completed.await(10, TimeUnit.SECONDS), "opposite-region predicates deadlocked");
            fixture.close();
            assertEquals(null, failure.get());
        }
    }

    @Test
    void villageDistanceTracksLiveOccupancyAndSeparatesQueryPerspectives() {
        for (final boolean ownerFirst : List.of(true, false)) {
            try (Fixture fixture = this.fixture("distance-" + ownerFirst)) {
                final Holder<PoiType> home = org.mockito.Mockito.spy(fixture.type(PoiTypes.HOME));
                when(home.is(net.minecraft.tags.PoiTypeTags.VILLAGE)).thenReturn(true);
                fixture.storage().add(POSITION_IN_B, home);
                fixture.storage().publishLevel();
                final var section = net.minecraft.core.SectionPos.of(POSITION_IN_B);
                try {
                    RegionPoiStorage.forceQueryRegionForTesting(fixture.regionAt(POSITION_IN_B));
                    assertEquals(7, fixture.storage().sectionsToVillage(section));
                    assertEquals(Optional.of(POSITION_IN_B), fixture.storage().take(type -> true, (type, pos) -> true, POSITION_IN_B, 0));
                    for (final boolean owner : List.of(ownerFirst, !ownerFirst)) {
                        RegionPoiStorage.forceQueryRegionForTesting(fixture.regionAt(owner ? POSITION_IN_B : POSITION_IN_A));
                        assertEquals(owner ? 0 : 7, fixture.storage().sectionsToVillage(section), "unpublished occupancy, owner=" + owner);
                    }
                    RegionPoiStorage.forceQueryRegionForTesting(fixture.regionAt(POSITION_IN_B));
                    fixture.storage().release(POSITION_IN_B);
                    assertEquals(7, fixture.storage().sectionsToVillage(section), "release is visible without publishing");
                } finally {
                    RegionPoiStorage.forceQueryRegionForTesting(null);
                }
            }
        }
    }

    private Optional<CompoundTag> read(Path dir, DataFixer dataFixer, ChunkPos chunk) {
        final SimpleRegionStorage storage = new SimpleRegionStorage(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"), dir, dataFixer, false, DataFixTypes.POI_CHUNK);
        try {
            return storage.read(chunk).join();
        } finally {
            try {
                storage.close();
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private DataFixer dataFixer() {
        final DataFixer dataFixer = mock(DataFixer.class);
        when(dataFixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return dataFixer;
    }

    private PoiManager manager(Path dir, RegistryAccess registryAccess, DataFixer dataFixer, LevelHeightAccessor heightAccessor) {
        return new PoiManager(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"),
                dir,
                dataFixer,
                false,
                registryAccess,
                mock(ChunkIOErrorReporter.class),
                heightAccessor);
    }

    private Holder<PoiType> type(net.minecraft.resources.ResourceKey<PoiType> key) {
        return BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolder(key).orElseThrow();
    }

    /**
     * One level's storage over a fresh directory, with the region of a position looked up through the layout.
     */
    private Fixture fixture(String name) {
        final RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        final DataFixer dataFixer = this.dataFixer();
        final LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(MIN_SECTION * 16, (MAX_SECTION - MIN_SECTION) * 16);
        final ServerLevel level = mock(ServerLevel.class);
        final RegionLayout layout = RegionLayout.FIXED_3X3;
        final SimpleRegionStorage regionStorage = new SimpleRegionStorage(
                new RegionStorageInfo("test", Level.OVERWORLD, "poi"), this.tempDir.resolve(name), dataFixer, false, DataFixTypes.POI_CHUNK);
        final RegionPoiStorage storage = new RegionPoiStorage(level, layout, regionStorage, registryAccess, heightAccessor);
        RegionPoiStorages.resetCountersForTesting();
        return new Fixture(storage, layout, level);
    }

    private record Fixture(RegionPoiStorage storage, RegionLayout layout, ServerLevel level) implements AutoCloseable {
        RegionCoordinate regionAt(BlockPos pos) {
            return this.layout.regionAt(this.level, pos);
        }

        Holder<PoiType> type(net.minecraft.resources.ResourceKey<PoiType> key) {
            return BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolder(key).orElseThrow();
        }

        @Override
        public void close() {
            this.storage.close();
        }
    }
}
