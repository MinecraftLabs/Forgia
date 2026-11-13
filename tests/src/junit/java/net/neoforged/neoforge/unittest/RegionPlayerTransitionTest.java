/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentity;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.PlayerRegionAudit;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the region transitions of players against the real engine: walking across a border, a teleport of
 * 1000 blocks, a teleport that happens while a batch of the level is in flight, a nether round trip and two
 * players swapping regions in one tick.
 * <p>
 * The level mock reports the test's tick list through the seed seam, so the production path is the one under test:
 * the server thread publishes the region of every player at the start of the entity slice, the region whose index
 * holds the player hands it to the server thread, and the player is ticked exactly once. The tick list membership
 * hooks of the level are driven directly ({@code entityTickStarted}/{@code entityTickEnded}), because a mock level
 * has no vanilla tick list; everything else is the production code.
 */
@Timeout(120)
class RegionPlayerTransitionTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int REGION_CHUNK_SIZE = 3;
    /** Region 0 covers chunks 0..2, so its last block is x=47; x=48 is the first block of region 1. */
    private static final int REGION_0_LAST_BLOCK_X = REGION_CHUNK_SIZE * 16 - 1;

    private RegionThreadingEngine engine;
    /** The tick list of the level as the seed seam reports it. */
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final List<ChunkPos> nonTickingChunks = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(1);

    @AfterEach
    void shutdownEngine() {
        RegionThreadingManager.clear();
        this.tickList.clear();
        this.nonTickingChunks.clear();
        this.engine = null;
    }

    @Test
    void walkAcrossARegionBorderIsPublishedAtTheNextSliceAndTicksOnce() {
        this.installEngine();
        final ServerLevel level = this.mockLevel(Level.OVERWORLD, null);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(40, 64, 1));
        final ServerPlayer player = this.player(level, position);
        this.tickList.add(player);

        // The first slice publishes the mapping of the player, which is its first region: no transition.
        this.tick(level);
        verify(player, times(1)).tick();
        assertEquals(0, this.regionX(player), "the player starts in region 0");
        assertEquals(0, this.counters().walkTransitions(), "a first mapping is not a transition");
        assertEquals(1, this.counters().entries(), "the first mapping enters one region");

        // Walk one block per tick across the border at x=48. Every tick publishes the region of the position the
        // tick starts from, so the tick that starts on the last block of region 0 is the one that crosses.
        int ticks = 1;
        for (int x = 41; x <= REGION_0_LAST_BLOCK_X + 1; x++) {
            position.set(new BlockPos(x, 64, 1));
            this.tick(level);
            ticks++;
            final PlayerRegionAudit audit = this.engine.auditPlayerRegions(level);
            assertTrue(audit.ok(), "a player must be in exactly one region every tick: " + audit.firstProblem());
            assertEquals(0, audit.misplaced(), "the publication point publishes the region of the position");
            assertEquals(1, this.engine.indexedRegionCount(player), "a player must be indexed by exactly one region");
        }

        verify(player, times(ticks)).tick();
        assertEquals(1, this.counters().walkTransitions(), "walking across the border is one walk transition");
        assertEquals(0, this.counters().teleportTransitions(), "a one block step is not a teleport");
        assertEquals(0, this.counters().dimensionTransitions(), "the player never changed level");
        assertEquals(1, this.regionX(player), "the player ends in region 1");
        assertEquals(0, this.engine.diagnostics().serverThreadOnlyEntityTicks() - ticks,
                "a region hands the player over exactly once per tick");
        assertEquals(0, this.counters().invariantFailures(), "the invariant never failed");
        assertEquals(0, this.counters().refusedTransitions(), "no publication was refused");
    }

    @Test
    void thousandBlockTeleportMovesTheMappingAndRetiresTheOldRegion() {
        this.installEngine();
        final ServerLevel level = this.mockLevel(Level.OVERWORLD, null);
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(40, 64, 1));
        final ServerPlayer player = this.player(level, position);
        this.tickList.add(player);
        this.tick(level);
        assertTrue(this.regionExists(level, 0, 0), "the region of the player is live");
        assertEquals(0, this.counters().teleportTransitions());

        // The teleport of 1000 blocks, and the chunk tickets that kept the old area loaded move with it: the
        // section of the old region is released before the player's next entity slice.
        position.set(new BlockPos(1040, 64, 1));
        this.engine.addRegionSection(level, 63, 0);
        RegionThreadingHooks.removeRegionSection(level, 0, 0);
        this.tick(level);

        verify(player, times(2)).tick();
        assertEquals(21, this.regionX(player), "the mapping follows the player to the region of its new position");
        assertEquals(1, this.counters().teleportTransitions(), "a move of 1000 blocks is a teleport");
        assertEquals(0, this.counters().walkTransitions(), "a teleport is not a walk");
        assertFalse(this.regionExists(level, 0, 0), "the old region retires once nothing holds it any more");
        assertTrue(this.regionExists(level, 21, 0), "the new region is live");
        final PlayerRegionAudit audit = this.engine.auditPlayerRegions(level);
        assertTrue(audit.ok(), "a player must be in exactly one region after a teleport: " + audit.firstProblem());
        assertEquals(1, audit.placed(), "the player is placed in the region of its position");
        assertEquals(0, this.counters().invariantFailures());
    }

    @Test
    void teleportDuringABatchIsRefusedAndPublishedAtTheNextSlice() {
        this.installEngine();
        final ServerLevel level = this.mockLevel(Level.OVERWORLD, null);
        this.engine.addRegionSection(level, 0, 0);
        final AtomicReference<BlockPos> playerPosition = new AtomicReference<>(new BlockPos(40, 64, 1));
        final ServerPlayer player = this.player(level, playerPosition);
        final Entity mob = this.entity(level, new BlockPos(41, 64, 1));
        this.tickList.addAll(List.of(player, mob));
        this.tick(level);
        assertEquals(0, this.regionX(player));

        // The worker tick of the mob hands the teleport of the player to the server thread, which is inside the
        // batch: the batch is published and not finished, so the publication point refuses the transition.
        final AtomicReference<RegionTickSnapshot[]> regionsDuringBatch = new AtomicReference<>();
        final AtomicReference<PlayerRegionAudit> auditDuringBatch = new AtomicReference<>();
        doAnswer(invocation -> {
            RegionThreadingHooks.runOnServerThread(level, () -> {
                playerPosition.set(new BlockPos(1040, 64, 1));
                this.engine.addRegionSection(level, 63, 0);
                // The next entity slice of the level, asked for while this batch is still in flight.
                final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
                assertNotNull(plan);
                regionsDuringBatch.set(this.engine.diagnostics().regionTicks().toArray(RegionTickSnapshot[]::new));
                auditDuringBatch.set(this.engine.auditPlayerRegions(level));
                return null;
            });
            return null;
        }).when(mob).tick();

        this.tick(level);

        verify(mob, times(2)).tick();
        verify(player, times(2)).tick();
        assertEquals(1, this.counters().transitionsDuringBatch(), "the publication asked for during a batch is counted");
        assertEquals(1, this.counters().refusedTransitions(), "and refused instead of writing a half-applied tick");
        assertEquals(0, this.counters().teleportTransitions(), "the refused transition is not published yet");
        assertEquals(0, this.regionX(player), "the player keeps the region of the batch that is in flight");
        assertNotNull(auditDuringBatch.get());
        assertTrue(auditDuringBatch.get().ok(), "the invariant holds while a batch is in flight");

        // The next entity slice publishes the transition, and the player is still ticked exactly once per tick.
        this.tick(level);

        verify(player, times(3)).tick();
        assertEquals(21, this.regionX(player), "the transition is published at the next defined point");
        assertEquals(1, this.counters().teleportTransitions());
        assertTrue(this.engine.auditPlayerRegions(level).ok(), "the invariant holds after the batch completed");
    }

    @Test
    void netherRoundTripKeepsOneRegionPerLevelAndContinuesTheIdentity() {
        this.installEngine();
        final EntityIdentityLedger overworldLedger = new EntityIdentityLedger();
        final EntityIdentityLedger netherLedger = new EntityIdentityLedger();
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel overworld = this.mockLevel(Level.OVERWORLD, server);
        final ServerLevel nether = this.mockLevel(Level.NETHER, server);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(overworld);
        when(server.getLevel(Level.NETHER)).thenReturn(nether);
        doReturn(overworldLedger).when(overworld).entityIdentityLedger();
        doReturn(netherLedger).when(nether).entityIdentityLedger();
        this.engine.addRegionSection(overworld, 0, 0);
        this.engine.addRegionSection(nether, 0, 0);

        final AtomicReference<ServerLevel> levelRef = new AtomicReference<>(overworld);
        final AtomicReference<BlockPos> position = new AtomicReference<>(new BlockPos(40, 64, 1));
        final ServerPlayer player = this.player(levelRef, position);
        final UUID uuid = player.getUUID();
        final int entityId = player.getId();
        this.tickList.add(player);
        this.tick(overworld);
        assertEquals(0, this.regionX(player));
        assertEquals(EntityIdentity.State.TRACKED, this.publishTracked(overworldLedger, player).state());

        // Overworld -> nether: the travel hook records the kind and hands the identity over, the removal retires
        // the mapping of the old level, and the destination level admits the same identity.
        this.travel(player, levelRef, position, overworld, nether, new BlockPos(40, 64, 1), true);
        assertEquals(EntityIdentity.State.GONE, overworldLedger.current(uuid).state(), "the old level publishes the departure");
        final EntityIdentity netherIdentity = netherLedger.current(uuid);
        assertNotNull(netherIdentity, "the destination level holds the identity");
        assertEquals(entityId, netherIdentity.entityId(), "the identity keeps its int id across the dimension change");
        assertEquals(EntityIdentity.State.TRACKED, netherIdentity.state(), "the destination publishes the identity as tracked");
        assertEquals(1, netherLedger.continuedTransfers(), "the entry follows the player instead of being reserved again");

        this.tick(nether);
        assertEquals(1, this.counters().portalTransitions(), "a portal sets the cooldown the transition is counted with");
        assertEquals(0, this.regionX(player), "the player is in exactly one region of the nether");
        assertTrue(this.engine.auditPlayerRegions(nether).ok(), "exactly one region of the level the player is in");

        // Nether -> overworld: the same in the other direction.
        this.travel(player, levelRef, position, nether, overworld, new BlockPos(41, 64, 1), true);
        assertEquals(1, overworldLedger.continuedTransfers(), "the return trip continues the identity as well");
        this.tick(overworld);

        verify(player, times(3)).tick();
        assertEquals(2, this.counters().portalTransitions(), "both legs are portal transitions");
        assertEquals(2, this.counters().dimensionTransitions() + this.counters().portalTransitions(), "two transitions in total");
        assertEquals(0, this.counters().refusedTransitions());
        assertTrue(this.engine.auditPlayerRegions(overworld).ok(), "exactly one region of the overworld after the round trip");
        assertEquals(EntityIdentity.State.GONE, netherLedger.current(uuid).state(), "the nether published the departure");
        assertEquals(entityId, overworldLedger.current(uuid).entityId(), "the identity kept its int id for the round trip");
        assertEquals(1, this.engine.indexedRegionCount(player), "the player is indexed by exactly one region");
        assertEquals(2, this.counters().identityHandovers(), "one identity handover per leg");
    }

    @Test
    void twoPlayersSwappingRegionsInOneTickAreBothPublished() {
        this.installEngine();
        final ServerLevel level = this.mockLevel(Level.OVERWORLD, null);
        this.engine.addRegionSection(level, 0, 0);
        this.engine.addRegionSection(level, REGION_CHUNK_SIZE, 0);
        final AtomicReference<BlockPos> firstPosition = new AtomicReference<>(new BlockPos(REGION_0_LAST_BLOCK_X, 64, 1));
        final AtomicReference<BlockPos> secondPosition = new AtomicReference<>(new BlockPos(REGION_0_LAST_BLOCK_X + 1, 64, 1));
        final ServerPlayer first = this.player(level, firstPosition);
        final ServerPlayer second = this.player(level, secondPosition);
        this.tickList.addAll(List.of(first, second));
        this.tick(level);
        assertEquals(0, this.regionX(first));
        assertEquals(1, this.regionX(second));

        // Both players cross the border in the same tick, in opposite directions.
        firstPosition.set(new BlockPos(REGION_0_LAST_BLOCK_X + 1, 64, 1));
        secondPosition.set(new BlockPos(REGION_0_LAST_BLOCK_X, 64, 1));
        this.tick(level);

        verify(first, times(2)).tick();
        verify(second, times(2)).tick();
        assertEquals(1, this.regionX(first), "the first player is in the region it walked into");
        assertEquals(0, this.regionX(second), "the second player is in the region it walked into");
        assertEquals(2, this.counters().walkTransitions(), "one walk transition per player");
        assertEquals(2, this.counters().departures(), "one departure per player");
        assertEquals(4, this.counters().entries(), "one entry per player per tick: two first mappings and two moves");
        assertEquals(1, this.engine.indexedRegionCount(first));
        assertEquals(1, this.engine.indexedRegionCount(second));
        assertTrue(this.engine.auditPlayerRegions(level).ok(), "both players are in exactly one region");
        assertEquals(0, this.counters().invariantFailures());
    }

    @Test
    void auditReportsAPlayerInNoRegionAndTheAssertionFails() {
        this.installEngine();
        final ServerLevel level = this.mockLevel(Level.OVERWORLD, null);
        this.engine.addRegionSection(level, 0, 0);
        final ServerPlayer player = this.player(level, new AtomicReference<>(new BlockPos(40, 64, 1)));
        this.tickList.add(player);
        this.tick(level);
        assertTrue(this.engine.auditPlayerRegions(level).ok());

        // The mapping of the player is retired without a transition, which is the state the invariant forbids.
        RegionThreadingHooks.removeRegionEntity(player);

        final PlayerRegionAudit audit = this.engine.auditPlayerRegions(level);
        assertFalse(audit.ok());
        assertEquals(1, audit.unplaced(), "a player no region maps is unplaced");
        assertEquals(0, audit.duplicated());
        assertTrue(audit.firstProblem().contains("is in no region of"), audit.firstProblem());
        assertEquals(1, this.counters().invariantFailures(), "the audit counts the violation");
        assertThrows(IllegalStateException.class, () -> this.engine.assertPlayerRegions(level),
                "the debug assertion fails for a player in no region");
    }

    /**
     * Performs the level change of {@code player} the way the vanilla path does: the travel hook first, then the
     * removal from the old level (tick list, level leave, identity departure), then the admission into the new
     * level (identity admission, tracking, tick list).
     */
    private void travel(ServerPlayer player, AtomicReference<ServerLevel> levelRef, AtomicReference<BlockPos> position,
            ServerLevel source, ServerLevel destination, BlockPos destinationPosition, boolean portal) {
        if (portal) {
            // The vanilla portal path sets the cooldown immediately before it resolves the destination, so the
            // travel hook of a portal sees a live cooldown and the transition is counted as a portal.
            when(player.getPortalCooldown()).thenReturn(300);
        }
        RegionThreadingHooks.recordPlayerDimensionTransition(player, destination.dimension());
        // The removal from the source level: the tick list, the leave-level hook and the identity departure.
        this.tickList.remove(player);
        RegionThreadingHooks.entityTickEnded(source, player);
        RegionThreadingHooks.removeRegionEntity(player);
        source.entityIdentityLedger().publishDeparting(player);
        source.entityIdentityLedger().publishGone(player);
        // The admission into the destination level, which is what ServerLevel#addDuringTeleport does.
        levelRef.set(destination);
        position.set(destinationPosition);
        assertEquals(EntityIdentityLedger.Admission.ADMITTED, destination.entityIdentityLedger().admit(player),
                "the destination level admits the travelling identity");
        destination.entityIdentityLedger().publishAdmitted(player);
        destination.entityIdentityLedger().publishTracked(player);
        this.tickList.add(player);
        RegionThreadingHooks.entityTickStarted(destination, player);
    }

    private EntityIdentity publishTracked(EntityIdentityLedger ledger, ServerPlayer player) {
        ledger.admit(player);
        ledger.publishAdmitted(player);
        ledger.publishTracked(player);
        final EntityIdentity identity = ledger.current(player.getUUID());
        assertNotNull(identity, "the ledger publishes the identity");
        return identity;
    }

    private RegionThreadingDiagnostics.PlayerRegionCounters counters() {
        return this.engine.diagnostics().playerRegions();
    }

    private void tick(ServerLevel level) {
        final RegionEntityTickPlan plan = this.engine.beginEntityTickPlan(level, entity -> true, PARKING_BARRIER);
        assertNotNull(plan, "the entity tick worker slice must be enabled");
        assertTrue(plan.ticksEntitiesOnRegionWorkers(), "the entity tick must be driven by the regions");
        plan.executeAndAwait();
    }

    private void installEngine() {
        final RegionThreadingEngine created = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(created);
        this.engine = created;
    }

    /** {@return true when a live region of {@code level} has the coordinate {@code regionX}/{@code regionZ}} */
    private boolean regionExists(ServerLevel level, int regionX, int regionZ) {
        final String dimension = level.dimension().location().toString();
        for (final RegionTickSnapshot snapshot : this.engine.diagnostics().regionTicks()) {
            if (snapshot.regionX() == regionX && snapshot.regionZ() == regionZ && snapshot.dimension().equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    private ServerLevel mockLevel(ResourceKey<Level> dimension, MinecraftServer server) {
        final ServerLevel level = mock(ServerLevel.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn(1L).when(level).getSeed();
        doReturn(0L).when(level).getGameTime();
        doReturn(dimension).when(level).dimension();
        doReturn("test-level-" + dimension.location()).when(level).toString();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        final LevelChunk loadedChunk = mock(LevelChunk.class);
        when(chunkSource.getChunkNow(anyInt(), anyInt())).thenReturn(loadedChunk);
        doReturn(chunkSource).when(level).getChunkSource();
        // The seed seam of the self-driven entity tick: the level reports the test's tick list.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> {
            final long chunkKey = invocation.getArgument(0);
            return !this.nonTickingChunks.contains(new ChunkPos(chunkKey));
        }).when(level).isInEntityTickingRange(anyLong());
        final TickRateManager tickRateManager = mock(TickRateManager.class);
        when(tickRateManager.runsNormally()).thenReturn(true);
        doReturn(tickRateManager).when(level).tickRateManager();
        final MinecraftServer levelServer = server == null ? mock(MinecraftServer.class) : server;
        when(levelServer.isSpawningAnimals()).thenReturn(false);
        when(levelServer.areNpcsEnabled()).thenReturn(true);
        when(levelServer.isSameThread()).thenReturn(false);
        doReturn(levelServer).when(level).getServer();
        return level;
    }

    private Entity entity(ServerLevel level, BlockPos pos) {
        final Entity entity = mock(Entity.class);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        return entity;
    }

    private ServerPlayer player(ServerLevel level, AtomicReference<BlockPos> position) {
        final AtomicReference<ServerLevel> levelRef = new AtomicReference<>(level);
        return this.player(levelRef, position);
    }

    private ServerPlayer player(AtomicReference<ServerLevel> levelRef, AtomicReference<BlockPos> position) {
        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.level()).thenAnswer(invocation -> levelRef.get());
        when(player.blockPosition()).thenAnswer(invocation -> position.get());
        when(player.chunkPosition()).thenAnswer(invocation -> {
            final BlockPos pos = position.get();
            return new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        });
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(player.isRemoved()).thenReturn(false);
        // A player is always-ticking, which is what makes a region hand it to the server thread.
        when(player.isAlwaysTicking()).thenReturn(true);
        when(player.getPortalCooldown()).thenReturn(0);
        when(player.toString()).thenReturn("test-player");
        return player;
    }

    /** {@return the region x coordinate the regionizer maps {@code entity} to} */
    private int regionX(Entity entity) {
        final int[] coordinate = this.regionCoordinate(entity);
        return coordinate[0];
    }

    private int[] regionCoordinate(Entity entity) {
        try {
            final Field regionizerField = RegionThreadingEngine.class.getDeclaredField("regionizer");
            regionizerField.setAccessible(true);
            final Object regionizer = regionizerField.get(this.engine);
            final Field entitiesField = regionizer.getClass().getDeclaredField("entities");
            entitiesField.setAccessible(true);
            final Object region = ((Map<?, ?>) entitiesField.get(regionizer)).get(entity);
            assertNotNull(region, "the entity is not tracked by the regionizer");
            final Method coordinateMethod = region.getClass().getDeclaredMethod("coordinate");
            coordinateMethod.setAccessible(true);
            final Object coordinate = coordinateMethod.invoke(region);
            final Method regionXMethod = coordinate.getClass().getDeclaredMethod("regionX");
            regionXMethod.setAccessible(true);
            final Method regionZMethod = coordinate.getClass().getDeclaredMethod("regionZ");
            regionZMethod.setAccessible(true);
            return new int[] { (int) regionXMethod.invoke(coordinate), (int) regionZMethod.invoke(coordinate) };
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot read the region mapping of " + entity, exception);
        }
    }
}
