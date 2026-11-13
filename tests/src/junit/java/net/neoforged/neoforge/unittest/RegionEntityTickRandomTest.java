/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the random source of one entity tick group.
 * <p>
 * Every group owns one source, derived from the world seed, the dimension, the game time and the region
 * coordinate of its level, and the engine installs it for the whole worker tick loop. The level random of
 * the mock level is the real {@link RegionAwareRandomSource} over a plain vanilla source, so a direct
 * {@code level.random} read inside an entity tick takes exactly the path block and entity code takes on a
 * server: it has to resolve the group source of the reading worker, and it has to fall back to the vanilla
 * source on the server thread.
 */
@Timeout(60)
class RegionEntityTickRandomTest {
    /** Regions of the concurrency stress test, two entities each. */
    private static final int REGIONS_OF_STRESS_TEST = 8;
    /** Draws one entity takes from {@code level.random} per stress tick, so the workers overlap. */
    private static final int STRESS_DRAWS_PER_TICK = 2000;
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
    };

    private RegionThreadingEngine engine;

    @AfterEach
    void shutdownEngine() {
        // Also shuts the engine down; a test that already shut it down is unaffected.
        RegionThreadingManager.clear();
        this.engine = null;
    }

    @Test
    void concurrentGroupsDrawFromTheirOwnSourceAndNeverFromTheVanillaOne() {
        this.installEngine(2, false, true, false, false, false);
        // A vanilla source that fails the test if a worker ever draws from it.
        final RandomSource vanilla = mock(RandomSource.class);
        when(vanilla.nextLong()).thenThrow(new AssertionError("a worker drew from the shared vanilla level random"));
        when(vanilla.nextInt(anyInt())).thenThrow(new AssertionError("a worker drew from the shared vanilla level random"));
        final ServerLevel level = mockLevel(7L, 40L, vanilla);
        final List<RandomSource> groupRandoms = Collections.synchronizedList(new ArrayList<>());
        final List<String> tickThreads = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> tickFailures = recordEntityTickFailures();
        final CyclicBarrier bothTicking = new CyclicBarrier(2);
        final Entity first = entityTickingIn(level, new BlockPos(0, 64, 0), groupRandoms, tickThreads, bothTicking, random -> {
            random.nextLong();
            random.nextInt(64);
        });
        final Entity second = entityTickingIn(level, new BlockPos(4096, 64, 0), groupRandoms, tickThreads, bothTicking, random -> {
            random.nextLong();
            random.nextInt(64);
        });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(first));
        assertTrue(plan.accept(second));
        plan.executeAndAwait();

        assertTrue(tickFailures.isEmpty(), () -> "a concurrent worker entity tick failed: " + tickFailures);
        assertEquals(2, tickThreads.stream().distinct().count(), () -> "both groups must tick at the same time: " + tickThreads);
        assertEquals(2, groupRandoms.size());
        assertNotNull(groupRandoms.get(0), "an entity tick must see the random source of its group through level.random");
        assertNotNull(groupRandoms.get(1));
        assertNotSame(groupRandoms.get(0), groupRandoms.get(1), "two concurrently ticking regions must not share a random source");
        assertFalse(groupRandoms.contains(vanilla), "a worker must never draw from the shared vanilla level random");
        verify(vanilla, never()).nextLong();
        verify(vanilla, never()).nextInt(anyInt());
        assertEquals(2L, this.engine.diagnostics().entityTicksOnWorkers());
    }

    @Test
    void entityGroupsDoNotOpenTheBlockTickGate() {
        this.installEngine(2, false, true, false, false, false);
        final ServerLevel level = mockLevel(7L, 40L, RandomSource.create(11L));
        final AtomicReference<RandomSource> blockTickRandom = new AtomicReference<>();
        final AtomicReference<RandomSource> scheduledRandom = new AtomicReference<>();
        final AtomicReference<RandomSource> chunkTickRandom = new AtomicReference<>();
        final Entity entity = entityTickingIn(level, new BlockPos(1, 64, 1), Collections.synchronizedList(new ArrayList<>()),
                Collections.synchronizedList(new ArrayList<>()), null, random -> {
                    blockTickRandom.set(this.engine.currentRegionTickRandom());
                    scheduledRandom.set(RegionThreadingHooks.scheduledTickRandom());
                    chunkTickRandom.set(RegionThreadingHooks.chunkTickRandom());
                });

        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        // The block-entity access gate asks currentRegionTickRandom(); an entity group must not answer it,
        // because only a scheduled or random tick group may read the block entities of its region.
        assertNull(blockTickRandom.get(), "an entity group must not answer the block tick random");
        assertNull(scheduledRandom.get());
        assertNull(chunkTickRandom.get());
    }

    @Test
    void serverThreadKeepsDrawingFromTheVanillaLevelRandom() {
        this.installEngine(2, false, true, false, false, false);
        final RandomSource vanilla = RandomSource.create(1234L);
        final RandomSource reference = RandomSource.create(1234L);
        final ServerLevel level = mockLevel(7L, 40L, vanilla);
        final List<RandomSource> groupRandoms = Collections.synchronizedList(new ArrayList<>());

        assertNull(this.engine.currentGroupRandom());
        for (int draw = 0; draw < 4; draw++) {
            assertEquals(reference.nextLong(), level.random.nextLong(), "the server thread keeps the vanilla sequence");
        }
        final Entity entity = entityTickingIn(level, new BlockPos(1, 64, 1), groupRandoms, Collections.synchronizedList(new ArrayList<>()),
                null, random -> {
                    random.nextLong();
                    random.nextLong();
                });
        final RegionEntityTickPlan plan = plan(level);
        assertTrue(plan.accept(entity));
        plan.executeAndAwait();

        assertNull(this.engine.currentGroupRandom(), "the batch must not leave a group random installed on the server thread");
        // The batch derives no seed from the level random, so the two worker draws must not have advanced it.
        assertEquals(reference.nextLong(), level.random.nextLong(), "worker draws must not advance the shared level random");
    }

    @Test
    void groupKeepsOneSourceForItsWholeLoopAndTheNextBatchBuildsANewOne() {
        this.installEngine(2, false, true, false, false, false);
        final RandomSource vanilla = RandomSource.create(99L);
        final RandomSource reference = RandomSource.create(99L);
        final ServerLevel level = mockLevel(11L, 3L, vanilla);
        final List<RandomSource> groupRandoms = Collections.synchronizedList(new ArrayList<>());
        final List<Entity> entities = List.of(
                entityTickingIn(level, new BlockPos(1, 64, 1), groupRandoms, Collections.synchronizedList(new ArrayList<>()), null, random -> random.nextLong()),
                entityTickingIn(level, new BlockPos(2, 64, 2), groupRandoms, Collections.synchronizedList(new ArrayList<>()), null, random -> random.nextLong()),
                entityTickingIn(level, new BlockPos(3, 64, 3), groupRandoms, Collections.synchronizedList(new ArrayList<>()), null, random -> random.nextLong()));

        final RegionEntityTickPlan firstBatch = plan(level);
        for (final Entity entity : entities) {
            assertTrue(firstBatch.accept(entity));
        }
        firstBatch.executeAndAwait();

        assertEquals(3, groupRandoms.size());
        assertNotNull(groupRandoms.get(0));
        assertSame(groupRandoms.get(0), groupRandoms.get(1), "one group keeps one random source for its whole tick loop");
        assertSame(groupRandoms.get(0), groupRandoms.get(2));
        assertNotSame(vanilla, groupRandoms.get(0), "the group draws from its own source, not from the vanilla one");
        assertNull(this.engine.currentGroupRandom(), "the worker must not keep the group random after its loop");
        assertEquals(reference.nextLong(), level.random.nextLong(), "the worker drawings must not advance the shared level random");

        // The next tick of the same region is a new group with a new source: nothing of the finished batch
        // is reused, and the source is not a leftover of the worker thread of the previous batch.
        final List<RandomSource> nextGroupRandoms = Collections.synchronizedList(new ArrayList<>());
        final Entity next = entityTickingIn(level, new BlockPos(1, 64, 1), nextGroupRandoms, Collections.synchronizedList(new ArrayList<>()), null, random -> random.nextLong());
        final RegionEntityTickPlan nextBatch = plan(level);
        assertTrue(nextBatch.accept(next));
        nextBatch.executeAndAwait();

        assertEquals(1, nextGroupRandoms.size());
        assertNotSame(groupRandoms.get(0), nextGroupRandoms.get(0), "a new tick must build a new group random source");
    }

    @Test
    void fixedSeedTickAndRegionDrawTheSameSequence() {
        this.installEngine(2, false, true, false, false, false);
        final ServerLevel level = mockLevel(42L, 100L, RandomSource.create(5L));

        final List<Long> alone = drawSequence(level, new BlockPos(0, 64, 0), null, 4);
        // Another region of the same tick is leased as its own group; it must not shift the sequence of the
        // measured region, which a batch-wide seed source handed out per group would do.
        final List<Long> withNeighbour = drawSequence(level, new BlockPos(0, 64, 0), new BlockPos(4096, 64, 0), 4);
        assertEquals(alone, withNeighbour, "the sequence of a region must not depend on the other regions of the tick");

        // The same region in a later tick starts a new sequence.
        doReturn(101L).when(level).getGameTime();
        assertNotEquals(alone, drawSequence(level, new BlockPos(0, 64, 0), null, 4), "a new game time must start a new sequence");

        // A different world seed starts a new sequence as well.
        doReturn(43L).when(level).getSeed();
        assertNotEquals(alone, drawSequence(level, new BlockPos(0, 64, 0), null, 4), "a new world seed must start a new sequence");

        // The measured sequence is reproducible: the same inputs draw the same values again.
        doReturn(42L).when(level).getSeed();
        doReturn(100L).when(level).getGameTime();
        assertEquals(alone, drawSequence(level, new BlockPos(0, 64, 0), null, 4), "the same seed, tick and region must draw the same sequence");
    }

    @Test
    void concurrentWorkersNeverTripTheLegacyRandomSourceThreadingDetector() {
        this.installEngine(4, false, true, false, false, false);
        // A real LegacyRandomSource, so its ThreadingDetector is armed exactly like on a server.
        final RandomSource vanilla = RandomSource.create(4242L);
        final RandomSource reference = RandomSource.create(4242L);
        final ServerLevel level = mockLevel(21L, 8L, vanilla);
        final List<RandomSource> tickedGroupRandoms = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> tickFailures = recordEntityTickFailures();
        final List<Entity> entities = new ArrayList<>();
        for (int region = 0; region < REGIONS_OF_STRESS_TEST; region++) {
            for (int member = 0; member < 2; member++) {
                final BlockPos pos = new BlockPos(region * 512 + member, 64, 0);
                entities.add(entityTickingIn(level, pos, tickedGroupRandoms, Collections.synchronizedList(new ArrayList<>()), null, random -> {
                    for (int draw = 0; draw < STRESS_DRAWS_PER_TICK; draw++) {
                        random.nextLong();
                        random.nextInt(1024);
                    }
                }));
            }
        }

        final RegionEntityTickPlan plan = plan(level);
        for (final Entity entity : entities) {
            assertTrue(plan.accept(entity));
        }
        // Before the per-group source every worker drew the same LegacyRandomSource here, which answered the
        // second concurrent draw with the ReportedException of its ThreadingDetector.
        plan.executeAndAwait();

        assertTrue(tickFailures.isEmpty(), () -> "a concurrent worker entity tick failed: " + tickFailures);
        final Set<RandomSource> groupRandoms = Collections.newSetFromMap(new IdentityHashMap<>());
        groupRandoms.addAll(tickedGroupRandoms);
        assertEquals(2 * REGIONS_OF_STRESS_TEST, tickedGroupRandoms.size());
        assertEquals(REGIONS_OF_STRESS_TEST, groupRandoms.size(), "every concurrently ticking region owns exactly one random source");
        assertEquals(2L * REGIONS_OF_STRESS_TEST, this.engine.diagnostics().entityTicksOnWorkers());
        assertEquals(reference.nextLong(), vanilla.nextLong(), "the stress draw must not have touched the shared level random");
    }

    @Test
    void workerSlicesKeepTheirExistingLevelRandomSource() {
        this.installEngine(2, false, true, false, false, false);
        assertTrue(RegionThreadingHooks.wrapLevelRandom(RandomSource.create(1L)) instanceof RegionAwareRandomSource,
                "an entity tick worker reads level.random, so the level random has to be wrapped");
        RegionThreadingManager.clear();

        this.installEngine(2, false, false, true, false, false);
        final RandomSource vanilla = RandomSource.create(1L);
        assertSame(vanilla, RegionThreadingHooks.wrapLevelRandom(vanilla),
                "block-entity groups own no random source, so the level random stays the vanilla one");
        RegionThreadingManager.clear();

        this.installEngine(2, false, false, false, true, false);
        assertTrue(RegionThreadingHooks.wrapLevelRandom(RandomSource.create(1L)) instanceof RegionAwareRandomSource,
                "scheduled tick groups keep their wrapped level random");
        RegionThreadingManager.clear();

        this.installEngine(2, false, false, false, false, true);
        assertTrue(RegionThreadingHooks.wrapLevelRandom(RandomSource.create(1L)) instanceof RegionAwareRandomSource,
                "random tick groups keep their wrapped level random");
    }

    /**
     * Draws {@code draws} values from {@code level.random} inside the entity tick of the region of
     * {@code pos}, with an optional second region accepted before it in the same batch.
     */
    private List<Long> drawSequence(ServerLevel level, BlockPos pos, BlockPos neighbour, int draws) {
        final List<Long> sequence = Collections.synchronizedList(new ArrayList<>());
        final RegionEntityTickPlan plan = plan(level);
        if (neighbour != null) {
            assertTrue(plan.accept(entityTickingIn(level, neighbour, Collections.synchronizedList(new ArrayList<>()),
                    Collections.synchronizedList(new ArrayList<>()), null, random -> {})));
        }
        assertTrue(plan.accept(entityTickingIn(level, pos, Collections.synchronizedList(new ArrayList<>()),
                Collections.synchronizedList(new ArrayList<>()), null, random -> {
                    for (int draw = 0; draw < draws; draw++) {
                        sequence.add(random.nextLong());
                    }
                })));
        plan.executeAndAwait();
        assertEquals(draws, sequence.size());
        return sequence;
    }

    /**
     * {@return a mock entity at {@code pos} whose tick runs {@code body} with the level random}
     *
     * @param groupRandoms receives {@link RegionThreadingEngine#currentGroupRandom()} at the start of the tick
     * @param tickThreads  receives the name of the thread that runs the tick
     * @param rendezvous   opens only when two groups tick at the same time, or {@code null}
     */
    private Entity entityTickingIn(ServerLevel level, BlockPos pos, List<RandomSource> groupRandoms, List<String> tickThreads,
            CyclicBarrier rendezvous, Consumer<RandomSource> body) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.getPassengers()).thenReturn(List.of());
        doAnswer(invocation -> {
            tickThreads.add(Thread.currentThread().getName());
            groupRandoms.add(this.engine.currentGroupRandom());
            if (rendezvous != null) {
                rendezvous.await(10, TimeUnit.SECONDS);
            }
            body.accept(level.random);
            return null;
        }).when(entity).tick();
        return entity;
    }

    /**
     * {@return the list the engine reports worker entity tick failures to}
     * <p>
     * The diagnostics observer keeps a failing tick from aborting its group and skips the vanilla
     * crash-report wrapper, so the recorded exception is the failure itself instead of the wrapper's
     * report. A test that expects no failure therefore has to assert on this list, not only on the batch.
     */
    private List<Throwable> recordEntityTickFailures() {
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        this.engine.setEntityTickFailureObserverForTesting((entity, throwable) -> failures.add(throwable));
        return failures;
    }

    private RegionEntityTickPlan plan(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "entity tick worker execution must be enabled");
        return plan;
    }

    /**
     * Creates the engine with the given slices and installs it as the active region threading
     * implementation, so the entity tick hooks of the level reach it exactly like they do on a server.
     */
    private void installEngine(int workerThreads, boolean workerExecution, boolean entityTickWorkers,
            boolean blockEntityTickWorkers, boolean scheduledTickWorkers, boolean randomTickWorkers) {
        // Only the entity tick mechanics tests construct an engine with region-owned entity storage;
        // production keeps entity storage on the server thread and therefore ticks entities inline.
        final RegionThreadingEngine created = new RegionThreadingEngine(workerThreads, workerExecution, false,
                entityTickWorkers, blockEntityTickWorkers, scheduledTickWorkers, randomTickWorkers,
                RegionPolicy.DYNAMIC, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    /**
     * Creates a level whose {@code random} is the real level random wrapper over {@code vanilla}, so a
     * {@code level.random} read inside a tick resolves exactly like on a server.
     */
    private static ServerLevel mockLevel(long seed, long gameTime, RandomSource vanilla) {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        // Region coordinates are part of worker task descriptions, which must not call into the level.
        doReturn("test-level").when(level).toString();
        // The seed of a group random source is derived from these three values of its level.
        doReturn(seed).when(level).getSeed();
        doReturn(gameTime).when(level).getGameTime();
        doReturn(Level.OVERWORLD).when(level).dimension();
        // The pre-load margin of an entity group asks the chunk source whether the neighborhood is
        // loaded; the mock level reports every chunk as loaded, so groups are submitted like before.
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelField(level, "random", RegionThreadingHooks.wrapLevelRandom(vanilla));
        return level;
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
