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
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the regionized natural spawning against the real engine, the real {@link RegionSpawnPlan}, the real
 * {@link RegionSpawnState} and the real vanilla spawn state.
 * <p>
 * The region spawn state is built from what a region owns, so the cap semantics are tested against
 * {@link NaturalSpawner#createState} itself: the same values vanilla would count, restricted to the region. Only
 * the level, its chunks, the mobs and the per-chunk spawning answers are mocks; the plan decision, the region
 * leases, the batch barrier, the per-chunk random source, the deferred admission and the server-thread fallback all
 * run for real.
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
            final ServerLevel level = this.mockLevel(Set.copyOf(spawnable.keySet()));
            final Set<Long> spawnableChunks = new HashSet<>();
            spawnable.forEach((chunkKey, isSpawnable) -> {
                if (isSpawnable) {
                    spawnableChunks.add(chunkKey);
                }
            });
            final RegionSpawnSource source = source(level, chunkKeys(allChunks), spawnableChunks, allChunks.size());
            final RegionSpawnState.Built first = RegionSpawnState.build(level, source, firstChunks, firstRegionMobs, List.of());
            final RegionSpawnState.Built second = RegionSpawnState.build(level, source, secondChunks, secondRegionMobs, List.of());
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
     * The mob cap of a region is the vanilla formula over the region's own spawnable chunks.
     */
    @Test
    void regionCapIsTheVanillaFormulaOverTheRegionsOwnChunks() {
        final ServerLevel level = this.mockLevel(Set.of());
        final List<ChunkPos> chunks = chunks(0, 0, REGION_CHUNK_SIZE);
        final Set<Long> spawnable = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            spawnable.add(chunk.toLong());
        }
        final RegionSpawnState.Built built = RegionSpawnState.build(level, source(level, spawnable, spawnable, 289),
                spawnable, List.of(), List.of());

        assertEquals(spawnable.size(), built.spawnableChunks(), "a 3x3-chunk region has nine spawnable chunks");
        // The vanilla cap of NaturalSpawner.SpawnState.canSpawnForCategory: maxInstancesPerChunk * chunks / 289,
        // where 289 is the 17x17 square of the spawn distance in chunks (NaturalSpawner.MAGIC_NUMBER).
        assertEquals(MobCategory.MONSTER.getMaxInstancesPerChunk() * spawnable.size() / (17 * 17),
                MobCategory.MONSTER.getMaxInstancesPerChunk() * built.spawnableChunks() / (17 * 17),
                "the region cap has to apply the vanilla formula to the region's own chunk count");
        assertTrue(MobCategory.MONSTER.getMaxInstancesPerChunk() * built.spawnableChunks() / (17 * 17) < MobCategory.MONSTER.getMaxInstancesPerChunk() * 289 / (17 * 17),
                "the region cap has to be a fraction of the level-wide cap, so mob farms are bounded per region");
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
     * With the switch off there is no plan at all, so the level runs the untouched vanilla body.
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
    }

    /**
     * The per-chunk answers of a region cover every chunk a spawn body can reach, so a body never asks the level.
     */
    @Test
    void regionNeighborhoodCoversEveryChunkASpawnBodyCanReach() {
        final Set<Long> spawnable = Set.of(new ChunkPos(1, 1).toLong());
        final ServerLevel level = this.mockLevel(spawnable);
        final RegionSpawnState.Built built = RegionSpawnState.build(level, source(level, spawnable, spawnable, 1),
                spawnable, List.of(), spawnable);

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
        return new RegionSpawnSource(
                fullChunkGetter(level, knownChunks),
                chunkKey -> List.<ServerPlayer>of(),
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
        return this.mockLevel(loadedChunks, 20L);
    }

    private ServerLevel mockLevel(Set<Long> loadedChunks, long gameTime) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("test-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(gameTime).when(level).getGameTime();
        when(level.dimension()).thenReturn(OVERWORLD);
        // The physical section bucket of a region is the grid the region ownership lease tests against.
        doReturn(REGION_CHUNK_SIZE).when(level).entitySectionRegionChunkSize();
        doReturn(List.of()).when(level).getAllEntities();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
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
        final Mob mob = mock(Mob.class);
        @SuppressWarnings("unchecked")
        final EntityType<Mob> type = mock(EntityType.class);
        when(type.getCategory()).thenReturn(MobCategory.MONSTER);
        doReturn(type).when(mob).getType();
        when(mob.getClassification(anyBoolean())).thenReturn(MobCategory.MONSTER);
        when(mob.blockPosition()).thenReturn(pos);
        when(mob.isPersistenceRequired()).thenReturn(false);
        when(mob.requiresCustomPersistence()).thenReturn(false);
        return mob;
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
