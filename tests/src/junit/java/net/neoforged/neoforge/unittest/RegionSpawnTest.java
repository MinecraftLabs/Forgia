/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.LevelSpawnCapCounter;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionSpawnChunk;
import net.neoforged.neoforge.server.threading.region.RegionSpawnNeighborhood;
import net.neoforged.neoforge.server.threading.region.RegionSpawnPlan;
import net.neoforged.neoforge.server.threading.region.RegionSpawnSource;
import net.neoforged.neoforge.server.threading.region.RegionSpawnState;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSlice;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the regionized natural spawning against the real engine, the real {@link RegionSpawnPlan}, the real
 * {@link RegionSpawnState} and the real vanilla spawn state.
 * <p>
 * The region spawn state is built from what a region owns, and its mob cap is the level-wide count of the tick
 * ({@link LevelSpawnCapCounter}), so the counting is tested against {@link NaturalSpawner#createState} itself and
 * the cap against the real spawn bodies of two regions that share one counter. Only the level, its chunks, the mobs
 * and the per-chunk spawning answers are mocks; the plan decision, the region leases, the batch barrier, the
 * per-chunk random source, the deferred admission and the server-thread fallback all run for real.
 * <p>
 * Vanilla cannot be spawned end to end here - a real spawn body needs the vanilla entity registry, the spawn
 * placements and the biome spawn lists of a bootstrapped level - so a spawn body is made observable where it must
 * be: a chunk whose region has no spawnable chunk reaches the cap, spawns nothing and is counted, and the
 * admission of a spawned riding graph is driven through the ownership lease of a real region worker (see
 * {@link #spawnAdmissionOutsideTheRegionIsRecordedForTheBarrier}). The behavioural comparison against vanilla at
 * the same chunk count is measured by the spawning benchmark of the GameTest suite.
 */
@Timeout(60)
class RegionSpawnTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
    private static final int REGION_CHUNK_SIZE = 3;
    /**
     * A level-wide natural spawn chunk count that caps monsters at one mob and every other category at none.
     * <p>
     * The vanilla formula is {@code maxInstancesPerChunk * chunks / 289}: five chunks give the monster category
     * ({@code maxInstancesPerChunk} 70) one slot, and the largest other category ({@code WATER_AMBIENT}, 20) zero,
     * so a test that drives a spawn body sees exactly the monster category pass the cap.
     */
    private static final int ONE_MONSTER_SLOT_CHUNKS = 5;
    /** The bottom of the test level, below the heightmap the spawn position search draws from. */
    private static final int SPAWN_BODY_MIN_BUILD_HEIGHT = 60;

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.engine = null;
    }

    /**
     * The spawn state of a region counts the region's entities and the region's spawnable chunks, and nothing of
     * its neighbor.
     * <p>
     * The property run over random layouts and entity distributions compares a region state with what vanilla's
     * state counts for the same entities: the region state must equal the vanilla count of the entities that belong
     * to the region, and the sum of both regions must equal the vanilla count of the level.
     */
    @Test
    void regionStateCountsOnlyTheRegionsOwnChunksAndEntities() {
        final Random random = new Random(20261104L);
        for (int round = 0; round < 40; round++) {
            final Set<Long> firstChunks = new HashSet<>();
            final Set<Long> secondChunks = new HashSet<>();
            final Map<Long, Boolean> spawnable = new LinkedHashMap<>();
            final List<ChunkPos> allChunks = new ArrayList<>();
            // Two regions side by side, so every chunk of the grid belongs to exactly one of them.
            for (int chunkX = 0; chunkX < 2 * REGION_CHUNK_SIZE; chunkX++) {
                for (int chunkZ = 0; chunkZ < 12; chunkZ++) {
                    final ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                    allChunks.add(chunk);
                    if (RegionLayout.FIXED_3X3.regionX(chunkX) == 0) {
                        firstChunks.add(chunk.toLong());
                    } else {
                        secondChunks.add(chunk.toLong());
                    }
                    spawnable.put(chunk.toLong(), random.nextBoolean());
                }
            }
            final List<Entity> mobs = new ArrayList<>();
            final List<Entity> firstRegionMobs = new ArrayList<>();
            final List<Entity> secondRegionMobs = new ArrayList<>();
            final int mobCount = 20 + random.nextInt(60);
            for (int index = 0; index < mobCount; index++) {
                final ChunkPos chunk = allChunks.get(random.nextInt(allChunks.size()));
                final Mob mob = mockMob(new BlockPos(chunk.getMinBlockX() + random.nextInt(16), 64,
                        chunk.getMinBlockZ() + random.nextInt(16)));
                mobs.add(mob);
                if (firstChunks.contains(chunk.toLong())) {
                    firstRegionMobs.add(mob);
                } else if (secondChunks.contains(chunk.toLong())) {
                    secondRegionMobs.add(mob);
                }
            }
            final ServerLevel level = this.mockLevel(Set.copyOf(spawnable.keySet()), 20L, mobs);
            final Set<Long> spawnableChunks = new HashSet<>();
            spawnable.forEach((chunkKey, isSpawnable) -> {
                if (isSpawnable) {
                    spawnableChunks.add(chunkKey);
                }
            });
            final RegionSpawnSource source = source(level, chunkKeys(allChunks), spawnableChunks, allChunks.size());
            final LevelSpawnCapCounter counter = LevelSpawnCapCounter.count(level, source);
            final RegionSpawnState.Built first = RegionSpawnState.build(level, source, firstChunks, firstRegionMobs,
                    List.of(), counter);
            final RegionSpawnState.Built second = RegionSpawnState.build(level, source, secondChunks, secondRegionMobs,
                    List.of(), counter);
            final NaturalSpawner.SpawnState vanilla = NaturalSpawner.createState(allChunks.size(), mobs,
                    fullChunkGetter(level, chunkKeys(allChunks)), noPlayerCap());

            assertEquals(expectedSpawnableChunks(firstChunks, spawnableChunks), first.spawnableChunks(),
                    "the area term of a region has to be the number of its own spawnable chunks");
            assertEquals(expectedSpawnableChunks(secondChunks, spawnableChunks), second.spawnableChunks(),
                    "the area term of a region has to be the number of its own spawnable chunks");
            assertEquals(vanilla.getSpawnableChunkCount(), allChunks.size(),
                    "the vanilla state counts the whole level, which is the sum over its regions");
            assertEquals(vanilla.getMobCategoryCounts().getInt(MobCategory.MONSTER),
                    first.state().getMobCategoryCounts().getInt(MobCategory.MONSTER)
                            + second.state().getMobCategoryCounts().getInt(MobCategory.MONSTER),
                    "the regions of a level have to partition the entity count of the level");
            assertEquals(firstRegionMobs.size(), first.state().getMobCategoryCounts().getInt(MobCategory.MONSTER),
                    "a region state has to count exactly the entities of its region");
            assertEquals(secondRegionMobs.size(), second.state().getMobCategoryCounts().getInt(MobCategory.MONSTER),
                    "a region state has to count exactly the entities of its region");
        }
    }

    /**
     * The shared counter counts the level's mobs per category with the predicates of
     * {@code NaturalSpawner.createState}, and the plan publishes it at plan start.
     * <p>
     * The comparison is against the vanilla state of the same level: a persistent mob, a mob of the {@code MISC}
     * category and a mob whose chunk the full-chunk getter does not answer are skipped by both, and every other mob
     * counts in the category the vanilla state counts it in.
     */
    @Test
    void sharedCounterEqualsTheLevelsMobsAndTheVanillaSpawnState() {
        this.installEngine(true);
        final List<ChunkPos> knownChunks = chunks(0, 0, 4);
        final Set<Long> known = new HashSet<>(chunkKeys(knownChunks));
        final List<Entity> mobs = new ArrayList<>();
        // Nine counted monsters, one persistent monster, one creature and one ambient mob.
        for (int index = 0; index < 9; index++) {
            mobs.add(mockMob(positionIn(knownChunks.get(index % knownChunks.size()))));
        }
        mobs.add(mockMob(positionIn(knownChunks.get(0)), MobCategory.MONSTER, true));
        mobs.add(mockMob(positionIn(knownChunks.get(1)), MobCategory.CREATURE, false));
        mobs.add(mockMob(positionIn(knownChunks.get(2)), MobCategory.AMBIENT, false));
        // A mob of the MISC category, and one whose chunk the full-chunk getter never answers.
        mobs.add(mockMob(positionIn(knownChunks.get(3)), MobCategory.MISC, false));
        mobs.add(mockMob(new BlockPos(100 * 16 + 8, 64, 100 * 16 + 8)));

        final ServerLevel level = this.mockLevel(known, 20L, mobs);
        final RegionSpawnSource source = source(level, known, known, 289);
        final RegionSpawnPlan plan = this.engine.beginSpawnPlan(level, source, true, true, false, PARKING_BARRIER);
        assertNotNull(plan, "natural spawning worker execution must be enabled");
        final LevelSpawnCapCounter counter = plan.capCounter();
        final NaturalSpawner.SpawnState vanilla = NaturalSpawner.createState(289, mobs,
                fullChunkGetter(level, known), noPlayerCap());

        assertEquals(289, counter.spawnableChunkCount(), "the area term of the cap is the level's spawnable chunks");
        for (final MobCategory category : MobCategory.values()) {
            assertEquals(vanilla.getMobCategoryCounts().getInt(category), counter.count(category),
                    "the shared counter has to count the level like the vanilla spawn state: " + category);
        }
        assertEquals(9, counter.count(MobCategory.MONSTER),
                "a persistent monster and a monster of an unloaded chunk must not be counted");
        assertEquals(1, counter.count(MobCategory.CREATURE), "a creature counts in its own category");
        assertEquals(1, counter.count(MobCategory.AMBIENT), "an ambient mob counts in its own category");
        assertEquals(0, counter.count(MobCategory.MISC), "a mob of the MISC category is never counted");
        // The vanilla formula over the level-wide chunk count, not a share of it.
        assertEquals(MobCategory.MONSTER.getMaxInstancesPerChunk() * 289 / (17 * 17), counter.limit(MobCategory.MONSTER),
                "the cap of a category has to be the vanilla formula over the level's spawnable chunks");
    }

    /**
     * Two regions racing for the last slot of a category spawn exactly one mob.
     * <p>
     * Both regions spawn for their own chunk against their own spawn state, and both states share the level-wide
     * counter whose monster cap is one. The body of the first region is held after it passed the cap, while the
     * second region runs its whole body: the reservation the first body took is what refuses the second one, so the
     * level ends up with one mob and not two.
     */
    @Test
    void twoRegionsRacingForTheLastSlotSpawnExactlyOneMob() throws Exception {
        final List<ChunkPos> chunks = chunks(0, 0, 2 * REGION_CHUNK_SIZE);
        final Set<Long> known = new HashSet<>(chunkKeys(chunks));
        final ServerLevel level = this.mockLevel(known);
        final RegionSpawnSource source = source(level, known, known, ONE_MONSTER_SLOT_CHUNKS, List.of(mockPlayer()));
        final LevelSpawnCapCounter counter = LevelSpawnCapCounter.count(level, source);
        assertEquals(1, counter.limit(MobCategory.MONSTER), "the monster cap of this level is one");
        assertEquals(0, counter.limit(MobCategory.CREATURE), "every other category is capped at zero");

        // One region on each side of the layout, each with its own spawn state over the shared counter.
        final Set<Long> firstRegion = new HashSet<>();
        final Set<Long> secondRegion = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            (RegionLayout.FIXED_3X3.regionX(chunk.x) == 0 ? firstRegion : secondRegion).add(chunk.toLong());
        }
        final RegionSpawnState.Built first = RegionSpawnState.build(level, source, firstRegion, List.of(), firstRegion, counter);
        final RegionSpawnState.Built second = RegionSpawnState.build(level, source, secondRegion, List.of(), secondRegion, counter);

        final AtomicInteger firstReads = new AtomicInteger();
        final AtomicInteger secondReads = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final LevelChunk firstChunk = this.conductorChunk(new ChunkPos(1, 1), firstReads, entered, released);
        final LevelChunk secondChunk = this.conductorChunk(new ChunkPos(REGION_CHUNK_SIZE + 1, 1), secondReads);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread firstRegionWorker = new Thread(() -> {
            try {
                NaturalSpawner.spawnForChunk(level, firstChunk, first.state(), true, true, false);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "NeoForge Region Worker test-race-0");
        firstRegionWorker.start();
        assertTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS),
                "the first region never reached its spawn body");
        assertEquals(1, counter.count(MobCategory.MONSTER),
                "the slot the first region took has to be visible before it spawns its mob");

        // The second region runs its whole body while the last slot is taken.
        NaturalSpawner.spawnForChunk(level, secondChunk, second.state(), true, true, false);
        assertEquals(0, secondReads.get(), "the second region must not reach its spawn body for the last slot");

        released.countDown();
        firstRegionWorker.join();
        if (failure.get() != null) {
            throw new AssertionError("the first region's spawn body failed", failure.get());
        }
        assertEquals(1, firstReads.get(), "the first region reached its body once, for the monster category");
        assertEquals(0, counter.count(MobCategory.MONSTER),
                "the reservation of a body that spawned nothing is released when the body ends");

        // The body of the winning region spawns its mob there and then; a real spawn body needs the bootstrapped
        // spawn placements, so the test records the one mob the body would have admitted, exactly as afterSpawn
        // does, and with it the level is at its cap of one.
        counter.recordSpawn(MobCategory.MONSTER);
        assertEquals(1, counter.count(MobCategory.MONSTER), "the level spawned exactly one mob");
        assertFalse(counter.tryReserve(MobCategory.MONSTER), "a cap of one refuses a second mob");
    }

    /**
     * A mob that walks into another region still counts against the level cap.
     * <p>
     * The region it left owns no mob any more, so a cap computed from the region's own entities would let that
     * region spawn again - that is the path that spawned 58% more monsters than vanilla. The level-wide counter is
     * unchanged by the move, and the region that lost the mob is refused while the level is at its cap and passes
     * only once the mob is gone from the level.
     */
    @Test
    void aMobThatWalksIntoAnotherRegionStillCountsAgainstTheLevelCap() {
        final List<ChunkPos> chunks = chunks(0, 0, 2 * REGION_CHUNK_SIZE);
        final Set<Long> known = new HashSet<>(chunkKeys(chunks));
        final Set<Long> firstRegion = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            if (RegionLayout.FIXED_3X3.regionX(chunk.x) == 0) {
                firstRegion.add(chunk.toLong());
            }
        }
        final Mob walking = mockMob(positionIn(new ChunkPos(1, 1)));
        final List<Entity> mobs = new ArrayList<>(List.of(walking));
        final ServerLevel level = this.mockLevel(known, 20L, mobs);
        final RegionSpawnSource source = source(level, known, known, ONE_MONSTER_SLOT_CHUNKS, List.of(mockPlayer()));

        assertEquals(1, LevelSpawnCapCounter.count(level, source).count(MobCategory.MONSTER),
                "the level counts the mob where it stands");
        // The mob walks into the neighbouring region: the region that stores it changes, the level does not.
        when(walking.blockPosition()).thenReturn(positionIn(new ChunkPos(REGION_CHUNK_SIZE + 1, 1)));
        final LevelSpawnCapCounter atCap = LevelSpawnCapCounter.count(level, source);
        assertEquals(1, atCap.count(MobCategory.MONSTER),
                "a mob that walked into another region still counts against the level cap");

        final RegionSpawnState.Built emptiedRegion = RegionSpawnState.build(level, source, firstRegion, List.of(),
                firstRegion, atCap);
        final AtomicInteger atCapReads = new AtomicInteger();
        NaturalSpawner.spawnForChunk(level, this.conductorChunk(new ChunkPos(1, 1), atCapReads), emptiedRegion.state(),
                true, true, false);
        assertEquals(0, atCapReads.get(),
                "the region that lost its mob must not spawn again while the level is at its cap");

        // The same region, the same empty entity list, but a level that is no longer at its cap: the body runs.
        mobs.clear();
        final LevelSpawnCapCounter belowCap = LevelSpawnCapCounter.count(level, source);
        final RegionSpawnState.Built belowCapRegion = RegionSpawnState.build(level, source, firstRegion, List.of(),
                firstRegion, belowCap);
        final AtomicInteger belowCapReads = new AtomicInteger();
        NaturalSpawner.spawnForChunk(level, this.conductorChunk(new ChunkPos(1, 1), belowCapReads),
                belowCapRegion.state(), true, true, false);
        assertEquals(1, belowCapReads.get(),
                "the level-wide cap, not the region's own entity list, decides whether a region may spawn");
    }

    /**
     * The cap is exceeded by at most the regions of one tick, and the counter reconciles at the next tick.
     * <p>
     * Four region bodies each take the slot of one monster and then spawn a cluster of two: the reservation makes
     * the check atomic, so the count can only pass the limit by the cluster each region spawns past it, never by the
     * regions that merely race for a slot. The next tick's count over the level's own mobs is what the counter held.
     */
    @Test
    void capIsExceededByAtMostTheRegionsOfOneTickAndReconcilesAtTheNextTick() throws Exception {
        final int regions = 4;
        final int clusterSize = 2;
        final List<Entity> mobs = new ArrayList<>();
        final Set<Long> known = Set.of(new ChunkPos(1, 1).toLong());
        final ServerLevel level = this.mockLevel(known, 20L, mobs);
        final RegionSpawnSource source = source(level, known, known, ONE_MONSTER_SLOT_CHUNKS);
        final LevelSpawnCapCounter counter = LevelSpawnCapCounter.count(level, source);
        final int limit = counter.limit(MobCategory.MONSTER);
        assertEquals(1, limit, "the monster cap of this level is one");

        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> workers = new ArrayList<>(regions);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int index = 0; index < regions; index++) {
            final Thread worker = new Thread(() -> {
                try {
                    start.await();
                    if (counter.tryReserve(MobCategory.MONSTER)) {
                        try {
                            for (int mob = 0; mob < clusterSize; mob++) {
                                counter.recordSpawn(MobCategory.MONSTER);
                            }
                        } finally {
                            counter.release(MobCategory.MONSTER);
                        }
                    }
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            }, "NeoForge Region Worker test-cap-" + index);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (final Thread worker : workers) {
            worker.join();
        }
        assertTrue(failures.isEmpty(), "a region body failed: " + failures);

        final int spawned = counter.count(MobCategory.MONSTER);
        assertEquals(0, spawned % clusterSize, "every region body that took the slot spawned its whole cluster");
        assertTrue(spawned <= limit + regions,
                "the cap may be exceeded by at most the regions of one tick, but the count is " + spawned);
        assertTrue(spawned >= clusterSize, "at least one region has to pass a cap of one");

        // The next tick counts the level again and has to see the mobs the counter counted.
        for (int index = 0; index < spawned; index++) {
            mobs.add(mockMob(positionIn(new ChunkPos(1, 1))));
        }
        assertEquals(spawned, LevelSpawnCapCounter.count(level, source).count(MobCategory.MONSTER),
                "the counter reconciles with the level at the next tick");
    }

    /**
     * The spawn random of a chunk is a pure function of the world seed, the dimension, the game time, the region
     * and the chunk, so it does not depend on the region order, the worker count or the thread.
     */
    @Test
    void spawnSeedIsPurePerRegionAndChunkAndIndependentOfTheThread() throws Exception {
        final ServerLevel level = this.mockLevel(Set.of());
        final RegionCoordinate region = new RegionCoordinate(level, 2, -3);
        final RegionCoordinate otherRegion = new RegionCoordinate(level, 2, -2);
        final long chunkKey = new ChunkPos(7, -8).toLong();

        final long seed = RegionSpawnChunk.spawnSeed(level, region, chunkKey);
        assertEquals(seed, RegionSpawnChunk.spawnSeed(level, region, chunkKey), "the seed is a pure function");
        assertArrayEquals(sequence(RegionSpawnChunk.spawnSeed(level, region, chunkKey)),
                sequence(RegionSpawnChunk.spawnSeed(level, region, chunkKey)),
                "the same seed and tick have to draw the same sequence");
        assertNotEquals(sequence(seed), sequence(RegionSpawnChunk.spawnSeed(level, otherRegion, chunkKey)),
                "a neighboring region has to draw a different sequence");
        assertNotEquals(sequence(seed), sequence(RegionSpawnChunk.spawnSeed(level, region, new ChunkPos(8, -8).toLong())),
                "a neighboring chunk has to draw a different sequence");

        final AtomicReference<long[]> drawnByWorker = new AtomicReference<>();
        final Thread worker = new Thread(() -> drawnByWorker.set(sequence(RegionSpawnChunk.spawnSeed(level, region, chunkKey))),
                "NeoForge Region Worker test-0");
        worker.start();
        worker.join();
        assertArrayEquals(sequence(seed), drawnByWorker.get(), "a worker thread has to draw the same sequence");

        // The same level tick, but a different game time, has to draw a different sequence.
        final ServerLevel laterLevel = this.mockLevel(Set.of(), 21L);
        assertNotEquals(sequence(seed), sequence(RegionSpawnChunk.spawnSeed(laterLevel, region, chunkKey)),
                "the next tick has to draw a different sequence");
    }

    /**
     * The plan hands the spawnable chunks over and counts every refusal by reason, and the handed chunk spawns on
     * the worker of its region.
     */
    @Test
    void planHandsOverSpawnableChunksAndCountsEveryRefusal() {
        this.installEngine(true);
        final ServerLevel level = this.mockLevel(Set.of());
        final RegionSpawnPlan plan = this.beginPlan(level, Set.of());

        // The vanilla inner gate refused it, so nothing spawns and the chunk is counted.
        plan.accept(this.chunk(new ChunkPos(1, 1)), false);
        assertEquals(1L, this.engine.diagnostics().gateRefusedSpawnChunks(), "a gate refusal has to be counted");
        assertTrue(plan.collectedChunks().isEmpty(), "a chunk the gate refused is never handed over");

        // A chunk with an unloaded neighbor reads chunks a worker may not load, so it keeps the server thread.
        when(level.getChunkSource().hasChunk(0, 0)).thenReturn(false);
        plan.accept(this.chunk(new ChunkPos(1, 1)), true);
        assertEquals(1L, this.engine.diagnostics().unloadedNeighborhoodSpawnChunks(),
                "a chunk with an unloaded neighbor has to be counted and spawned inline");
        assertTrue(plan.collectedChunks().isEmpty(), "a chunk with an unloaded neighbor is never handed over");
        when(level.getChunkSource().hasChunk(0, 0)).thenReturn(true);

        // A chunk whose region has no spawnable chunk spawns on the worker and every category is refused by the
        // region's own cap.
        plan.accept(this.chunk(new ChunkPos(1, 1)), true);
        assertEquals(1, plan.collectedChunks().size(), "a spawnable chunk has to be handed to its region");
        plan.executeAndAwait(PARKING_BARRIER);

        assertEquals(1L, this.engine.diagnostics().spawnChunksOnWorkers(), "one chunk spawns on a region worker");
        assertEquals(0L, this.engine.diagnostics().truncatedSpawnChunks(), "a body that completes is not truncated");
        assertEquals(0L, this.engine.diagnostics().unleasedSpawnChunks(), "the region of the chunk has to be leased");
        assertTrue(this.engine.diagnostics().capRefusedSpawns() > 0L,
                "a region without a spawnable chunk has to refuse every category by its cap");
        assertEquals(0, this.engine.diagnostics().runningRegionCount(), "the batch has to release every lease");
    }

    /**
     * A chunk whose spawn body the batch cannot reserve the block writes for stays on the server thread.
     */
    @Test
    void spawnChunkWithoutDeferredUpdateBudgetStaysOnTheServerThread() {
        this.installEngine(true);
        this.engine.setDeferredBlockUpdateBudget(RegionTickSlice.SPAWN.reservationPerUnit());
        final ServerLevel level = this.mockLevel(Set.of());
        final RegionSpawnPlan plan = this.beginPlan(level, Set.of());

        plan.accept(this.chunk(new ChunkPos(1, 1)), true);
        plan.accept(this.chunk(new ChunkPos(2, 1)), true);

        assertEquals(1, plan.collectedChunks().size(), "the budget admits exactly one chunk's spawn body");
        assertEquals(1L, this.engine.diagnostics().deferredUpdateBudgetSpawnChunks(),
                "the chunk that does not fit has to be counted");
    }

    /**
     * A spawn admission is recorded for the barrier even when the spawned position lies outside the leased region,
     * because vanilla picks the position without regard to the region border; the barrier admits it exactly once on
     * the server thread, and a spawn that cannot be recorded leaves no entity behind.
     * <p>
     * The admission is driven through the ownership lease of a real region worker: the chunk tick slice is the
     * slice whose body can run arbitrary code under a lease, and it defers admissions through the same
     * {@code RegionDeferredEntityAdditions} the spawn slice uses.
     */
    @Test
    void spawnAdmissionOutsideTheRegionIsRecordedForTheBarrier() {
        this.installEngine(true);
        final List<Entity> admitted = new CopyOnWriteArrayList<>();
        final List<String> admittedOn = new CopyOnWriteArrayList<>();
        final ServerLevel level = this.mockLevel(Set.of());
        doAnswer(invocation -> {
            admitted.add(invocation.getArgument(0));
            admittedOn.add(Thread.currentThread().getName());
            return true;
        }).when(level).addFreshEntity(any(Entity.class));
        final Entity outside = mockPendingMob(level, new BlockPos(4 * 16 + 8, 64, 4 * 16 + 8));
        final Entity alreadyAdded = mockPendingMob(level, new BlockPos(4 * 16 + 8, 64, 4 * 16 + 8));
        when(alreadyAdded.isAddedToLevel()).thenReturn(true);
        final AtomicBoolean recorded = new AtomicBoolean();
        final AtomicBoolean refused = new AtomicBoolean(true);

        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, 3, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        plan.accept(this.randomTickChunk(new ChunkPos(1, 1), () -> {
            recorded.set(RegionThreadingHooks.deferSpawnAddition(level, outside));
            refused.set(!RegionThreadingHooks.deferSpawnAddition(level, alreadyAdded));
        }));
        plan.executeAndAwait(PARKING_BARRIER);

        assertTrue(recorded.get(), "an admission outside the leased region has to be recorded");
        assertTrue(refused.get(), "an entity that is already in the level must not be recorded");
        assertEquals(List.of(outside), admitted, "the barrier has to admit the recorded graph exactly once");
        assertEquals(1, admittedOn.size(), "the barrier admits one graph once");
        assertFalse(admittedOn.get(0).startsWith("NeoForge Region Worker"),
                "the admission has to run on the server thread: " + admittedOn);
        assertEquals(1L, this.engine.diagnostics().deferredEntityAdditionsApplied(),
                "the applied admission has to be counted");
    }

    /**
     * With the switch off there is no plan at all - and with it no level-wide counter - so the level runs the
     * untouched vanilla body, whose cap is the count of its own spawn state.
     */
    @Test
    void disabledSpawningSliceKeepsTheVanillaPath() {
        this.installEngine(false);
        final ServerLevel level = this.mockLevel(Set.of());
        assertNull(this.engine.beginSpawnPlan(level, source(level, Set.of(), 0), true, true, false, PARKING_BARRIER),
                "the plan only exists when the spawning slice is enabled");
        assertNull(RegionThreadingHooks.beginSpawnPlan(level, source(level, Set.of(), 0), true, true, false, PARKING_BARRIER),
                "the hook must not create a plan for a disabled slice");
        assertFalse(this.engine.diagnostics().spawnWorkerExecutionEnabled(), "the switch has to be reported off");
        assertFalse(this.engine.diagnostics().spawnWorkerDispatchEffective(), "the slice must not be effective");

        // The state the vanilla path builds is the four-argument createState, which has no shared counter: its cap
        // is its own count of the level, and a level at its cap refuses the category exactly like before.
        final Set<Long> known = Set.of(new ChunkPos(1, 1).toLong());
        final List<Entity> mobs = new ArrayList<>(List.of(mockMob(positionIn(new ChunkPos(1, 1))),
                mockMob(positionIn(new ChunkPos(1, 1)))));
        final ServerLevel capped = this.mockLevel(known, 20L, mobs);
        final AtomicInteger atCapReads = new AtomicInteger();
        NaturalSpawner.spawnForChunk(capped, this.conductorChunk(new ChunkPos(1, 1), atCapReads),
                NaturalSpawner.createState(ONE_MONSTER_SLOT_CHUNKS, mobs, fullChunkGetter(capped, known), permissiveCap()),
                true, true, false);
        assertEquals(0, atCapReads.get(),
                "the vanilla state has to refuse a category whose own count reached its own cap");
        final AtomicInteger belowCapReads = new AtomicInteger();
        NaturalSpawner.spawnForChunk(capped, this.conductorChunk(new ChunkPos(1, 1), belowCapReads),
                NaturalSpawner.createState(289, mobs, fullChunkGetter(capped, known), permissiveCap()),
                true, true, false);
        assertTrue(belowCapReads.get() > 0, "the vanilla state has to let a category through below its cap");
    }

    /**
     * The per-chunk answers of a region cover every chunk a spawn body can reach, so a body never asks the level.
     */
    @Test
    void regionNeighborhoodCoversEveryChunkASpawnBodyCanReach() {
        final Set<Long> spawnable = Set.of(new ChunkPos(1, 1).toLong());
        final ServerLevel level = this.mockLevel(spawnable);
        final RegionSpawnSource source = source(level, spawnable, spawnable, 1);
        final RegionSpawnState.Built built = RegionSpawnState.build(level, source, spawnable, List.of(), spawnable,
                LevelSpawnCapCounter.count(level, source));

        final int reach = RegionSpawnNeighborhood.REACH_CHUNKS;
        for (int offsetX = -reach; offsetX <= reach; offsetX++) {
            for (int offsetZ = -reach; offsetZ <= reach; offsetZ++) {
                final long chunkKey = new ChunkPos(1 + offsetX, 1 + offsetZ).toLong();
                assertEquals(spawnable.contains(chunkKey), built.neighborhood().isSpawnAllowed(chunkKey),
                        "a chunk within the reach of a spawn body has to be recorded");
            }
        }
        assertFalse(built.neighborhood().isSpawnAllowed(new ChunkPos(1 + reach + 1, 1).toLong()),
                "a chunk beyond the reach of a spawn body is not recorded");
    }

    private void installEngine(boolean spawnWorkers) {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, false, false, false, true,
                spawnWorkers, RegionLayout.of(RegionPolicy.FIXED_3X3));
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    private RegionSpawnPlan beginPlan(ServerLevel level, Set<Long> spawnableChunks) {
        final RegionSpawnPlan plan = this.engine.beginSpawnPlan(level, source(level, spawnableChunks, 0),
                true, true, false, PARKING_BARRIER);
        assertNotNull(plan, "natural spawning worker execution must be enabled");
        return plan;
    }

    /**
     * {@return the spawn source of a level whose spawnable chunks and level-wide count are fixed by the test}
     * <p>
     * Every spawnable chunk is also a loaded chunk of the test level, so the entity scan of the spawn state counts
     * the entities standing in them.
     */
    private static RegionSpawnSource source(ServerLevel level, Set<Long> spawnableChunks, int levelWideCount) {
        return source(level, spawnableChunks, spawnableChunks, levelWideCount);
    }

    /**
     * {@return the spawn source of a test level}
     *
     * @param knownChunks     the chunks the level's full-chunk getter answers for
     * @param spawnableChunks the chunks a player is close enough to for spawning
     * @param levelWideCount  the level-wide natural spawn chunk count of the vanilla state
     */
    private static RegionSpawnSource source(ServerLevel level, Collection<Long> knownChunks, Set<Long> spawnableChunks,
            int levelWideCount) {
        return source(level, knownChunks, spawnableChunks, levelWideCount, List.of());
    }

    /**
     * {@return the spawn source of a test level with {@code playersNear} close enough for spawning to every chunk}
     * <p>
     * The vanilla per-player cap refuses every category for a chunk no player is close to, so a test that lets a
     * spawn body run has to give it a player; the region state then counts the mobs of that player itself.
     */
    private static RegionSpawnSource source(ServerLevel level, Collection<Long> knownChunks, Set<Long> spawnableChunks,
            int levelWideCount, List<ServerPlayer> playersNear) {
        return new RegionSpawnSource(
                fullChunkGetter(level, knownChunks),
                chunkKey -> playersNear,
                spawnableChunks::contains,
                () -> levelWideCount);
    }

    /**
     * {@return the vanilla full-chunk getter of a test level, which answers a full chunk for every known chunk}
     */
    private static NaturalSpawner.ChunkGetter fullChunkGetter(ServerLevel level, Collection<Long> knownChunks) {
        final Set<Long> known = new HashSet<>(knownChunks);
        final LevelChunk fullChunk = mockFullChunk();
        return (chunkKey, consumer) -> {
            if (known.contains(chunkKey)) {
                consumer.accept(fullChunk);
            }
        };
    }

    /** {@return the chunk keys of {@code chunks}} */
    private static List<Long> chunkKeys(List<ChunkPos> chunks) {
        final List<Long> keys = new ArrayList<>(chunks.size());
        for (final ChunkPos chunk : chunks) {
            keys.add(chunk.toLong());
        }
        return keys;
    }

    private static int expectedSpawnableChunks(Set<Long> regionChunks, Set<Long> spawnableChunks) {
        int count = 0;
        for (final long chunkKey : regionChunks) {
            if (spawnableChunks.contains(chunkKey)) {
                ++count;
            }
        }
        return count;
    }

    /** {@return a per-player cap that lets every category through, so only the category cap decides} */
    private static net.minecraft.world.level.LocalMobCapCalculator permissiveCap() {
        return new net.minecraft.world.level.LocalMobCapCalculator(null) {
            @Override
            public void addMob(ChunkPos chunkPos, MobCategory category) {}

            @Override
            public boolean canSpawn(MobCategory category, ChunkPos chunkPos) {
                return true;
            }
        };
    }

    /**
     * {@return the vanilla per-player cap without a chunk map}
     * <p>
     * The comparison against the vanilla state only reads its category counts, so the per-player cap answers
     * "no player is close enough", which is what a level without a player holds.
     */
    private static net.minecraft.world.level.LocalMobCapCalculator noPlayerCap() {
        return new net.minecraft.world.level.LocalMobCapCalculator(null) {
            @Override
            public void addMob(ChunkPos chunkPos, MobCategory category) {}

            @Override
            public boolean canSpawn(MobCategory category, ChunkPos chunkPos) {
                return false;
            }
        };
    }

    /** {@return the first ten draws of the random source with {@code seed}} */
    private static long[] sequence(long seed) {
        final RandomSource random = RandomSource.create(seed);
        final long[] drawn = new long[10];
        for (int index = 0; index < drawn.length; index++) {
            drawn[index] = random.nextLong();
        }
        return drawn;
    }

    /**
     * {@return a player the vanilla per-player cap sees as close enough for spawning}
     * <p>
     * Only the identity of the player is used: the cap counts mobs per player and refuses a chunk no player is
     * close to, so a body that may spawn needs a player to hold the count of.
     */
    private static ServerPlayer mockPlayer() {
        return mock(ServerPlayer.class);
    }

    /** {@return a position inside {@code chunk}, at the height the test chunks answer} */
    private static BlockPos positionIn(ChunkPos chunk) {
        return new BlockPos(chunk.getMinBlockX() + 8, 64, chunk.getMinBlockZ() + 8);
    }

    private static List<ChunkPos> chunks(int regionX, int regionZ, int edge) {
        final List<ChunkPos> chunks = new ArrayList<>(edge * edge);
        for (int offsetX = 0; offsetX < edge; offsetX++) {
            for (int offsetZ = 0; offsetZ < edge; offsetZ++) {
                chunks.add(new ChunkPos(regionX * edge + offsetX, regionZ * edge + offsetZ));
            }
        }
        return chunks;
    }

    private ServerLevel mockLevel(Set<Long> loadedChunks) {
        return this.mockLevel(loadedChunks, 20L, List.of());
    }

    private ServerLevel mockLevel(Set<Long> loadedChunks, long gameTime) {
        return this.mockLevel(loadedChunks, gameTime, List.of());
    }

    /**
     * {@return a test level holding {@code entities}}
     * <p>
     * The entity list is what {@code level.getAllEntities()} answers, so a spawn state and the level-wide counter
     * count exactly the entities the test put into the level.
     */
    private ServerLevel mockLevel(Set<Long> loadedChunks, long gameTime, List<Entity> entities) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(gameTime).when(level).getGameTime();
        when(level.dimension()).thenReturn(OVERWORLD);
        // The physical section bucket of a region is the grid the region ownership lease tests against.
        doReturn(REGION_CHUNK_SIZE).when(level).entitySectionRegionChunkSize();
        doReturn(entities).when(level).getAllEntities();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
        // The spawn position search of a spawn body draws between the level's bottom and the heightmap.
        doReturn(SPAWN_BODY_MIN_BUILD_HEIGHT).when(level).getMinBuildHeight();
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickLightning(any());
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doReturn(chunkSource).when(level).getChunkSource();
        doReturn(true).when(level).isNaturalSpawningAllowed(any(BlockPos.class));
        // The spawn-gate answer of a chunk is recorded per chunk; a level that keeps no entity section manager
        // answers it through this stub.
        doAnswer(invocation -> loadedChunks.contains(((ChunkPos) invocation.getArgument(0)).toLong()))
                .when(level).isNaturalSpawningAllowed(any(ChunkPos.class));
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        return level;
    }

    /** {@return the chunk of one spawn chunk with no block entity and no random tick} */
    private LevelChunk chunk(ChunkPos pos) {
        final LevelChunk chunk = mock(LevelChunk.class, CALLS_REAL_METHODS);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getBlockEntities()).thenReturn(Map.of());
        when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
        doReturn(64).when(chunk).getHeight(any(Heightmap.Types.class), anyInt(), anyInt());
        return chunk;
    }

    /**
     * {@return the chunk of one chunk tick whose randomly ticking section calls {@code onRandomTick} once}
     * <p>
     * The body runs on the region worker of the chunk, inside the ownership lease the batch opens for it.
     */
    private LevelChunk randomTickChunk(ChunkPos pos, Runnable onRandomTick) {
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(false);
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            onRandomTick.run();
            return null;
        }).when(blockState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));
        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        return chunk;
    }

    /** {@return a mob of one region, at {@code pos}, of the monster category} */
    private static Mob mockMob(BlockPos pos) {
        return mockMob(pos, MobCategory.MONSTER, false);
    }

    /**
     * {@return a mob at {@code pos} of {@code category}, persistent or not, as the spawn state counts it}
     * <p>
     * Both the vanilla {@code createState} and the level-wide counter classify an entity with
     * {@code getClassification(true)} and skip it when it is persistent.
     */
    private static Mob mockMob(BlockPos pos, MobCategory category, boolean persistent) {
        final Mob mob = mock(Mob.class);
        @SuppressWarnings("unchecked")
        final EntityType<Mob> type = mock(EntityType.class);
        when(type.getCategory()).thenReturn(category);
        doReturn(type).when(mob).getType();
        when(mob.getClassification(anyBoolean())).thenReturn(category);
        when(mob.blockPosition()).thenReturn(pos);
        when(mob.isPersistenceRequired()).thenReturn(persistent);
        when(mob.requiresCustomPersistence()).thenReturn(false);
        return mob;
    }

    /**
     * {@return the chunk of one spawn body whose first block state is a redstone conductor}
     * <p>
     * The spawn body of a chunk stops at its first candidate position when the block there is a redstone conductor,
     * before it asks for a spawn placement or creates a mob. The body therefore runs to its end - it takes and
     * releases the cap slot of its category - without spawning anything, and {@code blockStateReads} counts how
     * often it got that far: one read per category whose cap let it in, none for a category the cap refused.
     */
    private LevelChunk conductorChunk(ChunkPos pos, AtomicInteger blockStateReads) {
        return this.conductorChunk(pos, blockStateReads, null, null);
    }

    /**
     * {@return a conductor chunk whose first block state read waits for {@code entered} and then for
     * {@code released}}
     * <p>
     * The read happens after the cap test of the category, so a test can hold a body that took the last slot of a
     * category while another region checks the same cap.
     */
    private LevelChunk conductorChunk(ChunkPos pos, AtomicInteger blockStateReads, @Nullable CountDownLatch entered,
            @Nullable CountDownLatch released) {
        final LevelChunk chunk = this.chunk(pos);
        final BlockState conductor = mock(BlockState.class);
        when(conductor.isRedstoneConductor(any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            blockStateReads.incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            if (released != null) {
                released.await();
            }
            return conductor;
        }).when(chunk).getBlockState(any(BlockPos.class));
        return chunk;
    }

    /** {@return an entity that is not in the level yet, at {@code pos} of {@code level}} */
    private static Entity mockPendingMob(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.isAddedToLevel()).thenReturn(false);
        when(entity.isRemoved()).thenReturn(false);
        when(entity.getSelfAndPassengers()).thenAnswer(invocation -> java.util.stream.Stream.of(entity));
        return entity;
    }

    /** {@return a full chunk whose biome has no spawn cost, as {@code NaturalSpawner.createState} reads it} */
    private static LevelChunk mockFullChunk() {
        final MobSpawnSettings mobSettings = mock(MobSpawnSettings.class);
        when(mobSettings.getMobSpawnCost(any())).thenReturn(null);
        final Biome biome = mock(Biome.class);
        when(biome.getMobSettings()).thenReturn(mobSettings);
        final Holder<Biome> holder = mockHolder(biome);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getNoiseBiome(anyInt(), anyInt(), anyInt())).thenReturn(holder);
        when(chunk.getPos()).thenReturn(ChunkPos.ZERO);
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome> mockHolder(Biome biome) {
        final Holder<Biome> holder = mock(Holder.class);
        when(holder.value()).thenReturn(biome);
        return holder;
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final Field field = Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
