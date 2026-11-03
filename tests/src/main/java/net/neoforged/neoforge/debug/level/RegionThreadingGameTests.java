/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.registration.RegistrationHelper;

/**
 * Regression coverage for region threading slices on a live level.
 * <p>
 * The worker tests assert the real engine counters, not block existence: the furnace test requires
 * {@code blockEntityTicksOnWorkers} to advance while a furnace is lit, the random tick test requires the
 * configured but unclassified random tick slice to keep running on the server thread with its effective
 * dispatch gated and its entity query inline, and the unsafe test requires the safe furnace to move from
 * the worker path to the whole-region fallback path when a beacon joins its region. The beacon test is
 * explicitly an unsafe-fallback test: a beacon query consumes entity storage, so it must never run on a
 * worker.
 * <p>
 * These tests require the experimental region threading bridge and worker slices. The documented run
 * enables {@code enableRegionThreadingBridge}, {@code enableRegionThreadingWorkers},
 * {@code enableRegionThreadingEntityTicks}, {@code enableRegionThreadingBlockEntityTicks},
 * {@code enableRegionThreadingScheduledTicks} and {@code enableRegionThreadingRandomTicks}. When the
 * bridge is absent the tests assert that the bridge and the exercised slices are configured off instead
 * of passing silently; a default-off GameTestServer therefore checks the documented default state.
 */
@ForEachTest(groups = "level.region")
public class RegionThreadingGameTests {
    /**
     * Distance from the GameTest structure grid to the isolated evidence area. The grid is at most a few
     * hundred blocks wide and tall, so this keeps every evidence chunk away from other test structures.
     */
    private static final int EVIDENCE_AREA_Z_OFFSET = 1024;
    /** Distance between the evidence areas of the individual tests, larger than a dynamic 16x16 region. */
    private static final int EVIDENCE_AREA_SPACING = 512;

    @GameTest(batch = "regionPhysicalEntityStorage", timeoutTicks = 200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Moves a tracked item across a fixed region boundary in the real persistent section storage")
    static void physicalEntitySectionCrossing(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var diagnostics = workerDiagnostics(helper);
            final ServerLevel level = helper.getLevel();
            final EntitySectionStorage<Entity> storage = persistentSections(level);
            final boolean fixed = diagnostics.isPresent() && diagnostics.get().regionPolicy() == RegionPolicy.FIXED_3X3;
            helper.assertTrue((storageField(storage, "partitions") != null) == fixed, "server storage backend must match the installed policy");
            if (!fixed) {
                helper.succeed();
                return;
            }
            helper.assertTrue(storageField(storage, "sections") == null && storageField(storage, "sectionIds") == null,
                    "fixed server storage must not retain global authoritative containers");
            final int regionChunkSize = diagnostics.get().regionChunkSize();
            final BlockPos interior = evidenceBlockPos(helper, RegionPolicy.FIXED_3X3, regionChunkSize, 5);
            final int boundaryChunkX = (Math.floorDiv(SectionPos.blockToSectionCoord(interior.getX()), regionChunkSize) + 1)
                    * regionChunkSize;
            final int boundaryX = boundaryChunkX * 16;
            final BlockPos source = new BlockPos(boundaryX - 1, interior.getY(), interior.getZ());
            final BlockPos target = source.offset(2, 0, 0);
            final AtomicReference<ItemEntity> item = new AtomicReference<>();
            helper.addEndListener(passed -> {
                if (item.get() != null) item.get().discard();
                releaseForcedChunk(level, source);
                releaseForcedChunk(level, target);
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, source);
                        forceChunk(level, target);
                    })
                    .thenWaitUntil(() -> helper.assertTrue(level.areEntitiesLoaded(new ChunkPos(source).toLong())
                            && level.areEntitiesLoaded(new ChunkPos(target).toLong()), "both entity chunks must be loaded"))
                    .thenExecute(() -> {
                        ItemEntity entity = new ItemEntity(level, source.getX() + 0.5, source.getY(), source.getZ() + 0.5, new ItemStack(Items.STONE));
                        entity.setNoGravity(true);
                        entity.setDeltaMovement(0, 0, 0);
                        item.set(entity);
                        helper.assertTrue(level.addFreshEntity(entity), "item must be registered with the persistent manager");
                        helper.assertTrue(storage.getSection(SectionPos.asLong(source)).getEntities().filter(e -> e == entity).count() == 1,
                                "source section must own the item exactly once");
                        entity.setPos(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                        var oldSection = storage.getSection(SectionPos.asLong(source));
                        helper.assertTrue(oldSection == null || oldSection.getEntities().noneMatch(e -> e == entity), "source must release the moved item");
                        helper.assertTrue(storage.getSection(SectionPos.asLong(target)).getEntities().filter(e -> e == entity).count() == 1,
                                "destination partition must own the item exactly once");
                        helper.assertTrue(level.getEntity(entity.getUUID()) == entity, "tracking lookup must preserve identity");
                        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(source).minmax(new AABB(target)).inflate(2), e -> e == entity).size() == 1,
                                "query across the partition boundary must return the item once");
                    })
                    .thenSucceed();
        });
    }

    @SuppressWarnings("unchecked")
    private static EntitySectionStorage<Entity> persistentSections(ServerLevel level) {
        return (EntitySectionStorage<Entity>) storageField(storageField(level, "entityManager"), "sectionStorage");
    }

    private static Object storageField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @GameTest(batch = "regionBeaconFallback", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Ticks a beacon with a base in an isolated region; the beacon is unsafe and must stay on the whole-region fallback path")
    static void beaconEntityQuery(final DynamicTest test) {
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos beaconPos = evidenceBlockPos(helper, diagnostics.get().regionPolicy(), diagnostics.get().regionChunkSize(), 3);
            // The beacon queries ServerPlayer entities while it applies effects, so keep a player alive.
            helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
            final long fallbackBefore = diagnostics.get().fallbackBlockEntityTicks();
            final long workersBefore = diagnostics.get().blockEntityTicksOnWorkers();
            helper.addEndListener(passed -> {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(beaconPos.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                level.setBlock(beaconPos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, beaconPos);
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, beaconPos);
                        clearEvidenceChunkBlockEntities(level, beaconPos);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                level.setBlock(beaconPos.offset(dx, -1, dz), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                            }
                        }
                        level.setBlock(beaconPos, Blocks.BEACON.defaultBlockState(), 3);
                    })
                    // The beacon only re-evaluates its base and applies effects every 80 game ticks.
                    .thenIdle(100)
                    .thenExecute(() -> {
                        helper.assertTrue(
                                level.getBlockEntity(beaconPos) instanceof BeaconBlockEntity,
                                "beacon block entity was not ticked or was removed");
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(
                                after.fallbackBlockEntityTicks() > fallbackBefore,
                                "the unsafe beacon was never classified as a server-thread fallback");
                        helper.assertTrue(
                                after.blockEntityTicksOnWorkers() == workersBefore,
                                "the unsafe beacon advanced the block entity worker counter");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionFurnaceWorker", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Requires an allowed furnace-family ticker to run its real server tick on a region worker")
    static void furnaceTicksOnRegionWorker(final DynamicTest test) {
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final RegionPolicy policy = diagnostics.get().regionPolicy();
            final BlockPos furnacePos = evidenceBlockPos(helper, policy, diagnostics.get().regionChunkSize(), 0);
            final long workersBefore = diagnostics.get().blockEntityTicksOnWorkers();
            final long fallbackBefore = diagnostics.get().fallbackBlockEntityTicks();
            helper.addEndListener(passed -> {
                level.setBlock(furnacePos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, furnacePos);
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, furnacePos);
                        clearEvidenceChunkBlockEntities(level, furnacePos);
                        final var chunk = level.getChunkAt(furnacePos);
                        helper.assertTrue(chunk != null, "the evidence furnace chunk was not loaded");
                        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
                        final var blockEntity = level.getBlockEntity(furnacePos);
                        helper.assertTrue(blockEntity instanceof AbstractFurnaceBlockEntity, "furnace block entity was not created");
                        final AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
                        furnace.setItem(0, new ItemStack(Blocks.COBBLESTONE));
                        furnace.setItem(1, new ItemStack(Items.COAL));
                    })
                    // The block-entity slice only admits a ticker whose chunk is entity-load eligible on the
                    // server thread. The evidence chunk is freshly forced, so wait for the manager's
                    // asynchronous entity load instead of assuming it finished within a fixed tick count.
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.areEntitiesLoaded(ChunkPos.asLong(furnacePos)),
                            "the evidence chunk never finished its entity load"))
                    // Re-apply the contents after the block entity was loaded and attached to the ticking
                    // chunk. A fresh block entity can be replaced by the chunk's live instance during the
                    // first ticks after placement, which would otherwise leave the ticking instance empty.
                    .thenIdle(5)
                    .thenExecute(() -> {
                        if (level.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace) {
                            furnace.setItem(0, new ItemStack(Blocks.COBBLESTONE));
                            furnace.setItem(1, new ItemStack(Items.COAL));
                        }
                    })
                    .thenIdle(100)
                    .thenExecute(() -> {
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(
                                after.blockEntityTicksOnWorkers() > workersBefore,
                                "the furnace did not tick on a region worker: before=" + workersBefore
                                        + " after=" + after.blockEntityTicksOnWorkers()
                                        + " fallbackBefore=" + fallbackBefore + " fallbackAfter=" + after.fallbackBlockEntityTicks());
                        helper.assertTrue(
                                level.getBlockState(furnacePos).getValue(AbstractFurnaceBlock.LIT),
                                "the furnace never started burning: state=" + level.getBlockState(furnacePos)
                                        + " blockEntity=" + level.getBlockEntity(furnacePos)
                                        + " workerTicksBefore=" + workersBefore
                                        + " workerTicksAfter=" + after.blockEntityTicksOnWorkers()
                                        + " fallbackAfter=" + after.fallbackBlockEntityTicks());
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionChunkTick", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Requires a configured chunk tick slice to tick an interior chunk on its region worker, with a region-scoped entity query")
    static void configuredChunkTickRunsOnTheRegionWorker(final DynamicTest test, final RegistrationHelper reg) {
        final AtomicReference<String> randomTickThread = new AtomicReference<>();
        final AtomicReference<String> queryThread = new AtomicReference<>();
        final AtomicInteger queryHits = new AtomicInteger();
        final var queryBlock = reg.blocks().register("region_query_block", () -> new Block(BlockBehaviour.Properties.of().randomTicks()) {
            @Override
            protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
                randomTickThread.compareAndSet(null, Thread.currentThread().getName());
                // A real range query issued from the random tick callback. Inside a chunk tick worker the lease
                // of the region answers it from the physical bucket of the region, so the predicate runs on the
                // worker and sees only the entities of that region.
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0), item -> {
                    queryThread.compareAndSet(null, Thread.currentThread().getName());
                    queryHits.incrementAndGet();
                    return true;
                });
            }
        });
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().chunkTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, true, "chunk tick worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final BlockPos blockPos = evidenceBlockPos(helper, diagnostics.get().regionPolicy(), diagnostics.get().regionChunkSize(), 1);
            final long chunkTicksBefore = diagnostics.get().chunkTicksOnWorkers();
            final GameRules.IntegerValue randomTickSpeed = level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTickSpeed = randomTickSpeed.get();
            final AtomicReference<ItemEntity> queryEntity = new AtomicReference<>();
            helper.addEndListener(passed -> {
                randomTickSpeed.set(previousRandomTickSpeed, level.getServer());
                level.setBlock(blockPos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, blockPos);
                final ItemEntity entity = queryEntity.get();
                if (entity != null) {
                    entity.discard();
                }
            });

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, blockPos);
                        clearEvidenceChunkBlockEntities(level, blockPos);
                        final var chunk = level.getChunkAt(blockPos);
                        helper.assertTrue(chunk != null, "the evidence random tick chunk was not loaded");
                        final ItemEntity entity = new ItemEntity(level, blockPos.getX() + 0.5, blockPos.getY() + 1.5, blockPos.getZ() + 0.5, new ItemStack(Items.STONE));
                        entity.setNoGravity(true);
                        entity.setDeltaMovement(0.0, 0.0, 0.0);
                        level.addFreshEntity(entity);
                        queryEntity.set(entity);
                        level.setBlock(blockPos, queryBlock.get().defaultBlockState(), 3);
                        helper.assertTrue(
                                level.getBlockState(blockPos).is(queryBlock.get()),
                                "the custom random tick block was not placed; state=" + level.getBlockState(blockPos));
                        randomTickSpeed.set(1000, level.getServer());
                    })
                    .thenIdle(400)
                    .thenExecute(() -> {
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(randomTickThread.get() != null, "the custom random tick block never ran");
                        helper.assertTrue(
                                randomTickThread.get().startsWith("NeoForge Region Worker"),
                                "an interior chunk tick ran on '" + randomTickThread.get() + "' instead of a region worker");
                        helper.assertTrue(queryHits.get() > 0, "the item entity predicate never ran, so no real entity query was exercised");
                        helper.assertTrue(
                                queryThread.get() != null && queryThread.get().startsWith("NeoForge Region Worker"),
                                "the chunk tick entity query ran on '" + queryThread.get() + "' instead of the leased region worker");
                        helper.assertTrue(after.chunkTickWorkerExecutionEnabled(), "the requested chunk tick worker configuration must stay visible");
                        helper.assertTrue(after.chunkTickWorkerDispatchEffective(), "a configured chunk tick slice must dispatch");
                        helper.assertTrue(
                                after.chunkTicksOnWorkers() > chunkTicksBefore,
                                "the chunk tick worker counter did not advance: before=" + chunkTicksBefore
                                        + " after=" + after.chunkTicksOnWorkers()
                                        + " fallback=" + after.fallbackChunkTicks()
                                        + " boundary=" + after.boundaryChunkTicks()
                                        + " unloadedNeighborhood=" + after.unloadedNeighborhoodChunkTicks());
                        helper.assertTrue(after.truncatedChunkTicks() == 0L, "the evidence chunk tick was truncated");
                        helper.assertTrue(after.ownershipViolationCount() == 0L, "the chunk tick raised an ownership violation");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionUnsafeFallback", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Requires an unsafe beacon in the same region to force the safe furnace onto the whole-region fallback path")
    static void unsafeTickerForcesWholeRegionFallback(final DynamicTest test) {
        test.onGameTest(helper -> {
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            if (diagnostics.isEmpty() || !diagnostics.get().blockEntityTickWorkerExecutionEnabled()) {
                assertSliceDisabled(helper, false, "block entity worker slice");
                helper.succeed();
                return;
            }
            final ServerLevel level = helper.getLevel();
            final RegionPolicy policy = diagnostics.get().regionPolicy();
            final BlockPos furnacePos = evidenceBlockPos(helper, policy, diagnostics.get().regionChunkSize(), 4);
            final BlockPos beaconPos = furnacePos.offset(1, 0, 0);
            final long workersBefore = diagnostics.get().blockEntityTicksOnWorkers();
            helper.addEndListener(passed -> {
                level.setBlock(furnacePos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(beaconPos, Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, furnacePos);
            });
            final AtomicReference<RegionThreadingDiagnostics> furnaceOnly = new AtomicReference<>();

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, furnacePos);
                        clearEvidenceChunkBlockEntities(level, furnacePos);
                        final var chunk = level.getChunkAt(furnacePos);
                        helper.assertTrue(chunk != null, "the evidence furnace chunk was not loaded");
                        level.setBlock(furnacePos, Blocks.FURNACE.defaultBlockState(), 3);
                        final var blockEntity = level.getBlockEntity(furnacePos);
                        helper.assertTrue(blockEntity instanceof AbstractFurnaceBlockEntity, "furnace block entity was not created");
                        final AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
                        furnace.setItem(0, new ItemStack(Blocks.COBBLESTONE));
                        furnace.setItem(1, new ItemStack(Items.COAL));
                    })
                    // The block-entity slice only admits a ticker whose chunk is entity-load eligible on the
                    // server thread. The evidence chunk is freshly forced, so wait for the manager's
                    // asynchronous entity load instead of assuming it finished within a fixed tick count.
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.areEntitiesLoaded(ChunkPos.asLong(furnacePos)),
                            "the evidence chunk never finished its entity load"))
                    .thenIdle(10)
                    .thenExecute(() -> {
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(
                                after.blockEntityTicksOnWorkers() > workersBefore,
                                "the safe furnace never ticked on a region worker while it was alone in its region: before="
                                        + workersBefore + " after=" + after.blockEntityTicksOnWorkers());
                        furnaceOnly.set(after);
                        level.setBlock(beaconPos, Blocks.BEACON.defaultBlockState(), 3);
                    })
                    .thenIdle(40)
                    .thenExecute(() -> {
                        final RegionThreadingDiagnostics before = furnaceOnly.get();
                        final RegionThreadingDiagnostics after = diagnostics();
                        helper.assertTrue(
                                level.getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity,
                                "the furnace block entity disappeared after the unsafe beacon joined its region");
                        helper.assertTrue(
                                after.fallbackBlockEntityTicks() >= before.fallbackBlockEntityTicks() + 20,
                                "the furnace and beacon were not both classified as whole-region fallbacks: before="
                                        + before.fallbackBlockEntityTicks() + " after=" + after.fallbackBlockEntityTicks());
                        helper.assertTrue(
                                after.blockEntityTicksOnWorkers() == before.blockEntityTicksOnWorkers(),
                                "a block entity still ticked on a worker while the region contained the unsafe beacon");
                    })
                    .thenSucceed();
        });
    }

    @GameTest(batch = "regionRandomMelt", timeoutTicks = 400)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Melts ice with a random tick on the region worker of the interior evidence chunk")
    static void randomTickMeltsIce(final DynamicTest test) {
        test.onGameTest(helper -> {
            final GameRules.IntegerValue randomTickSpeed = helper.getLevel().getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
            final int previousRandomTickSpeed = randomTickSpeed.get();
            final Optional<RegionThreadingDiagnostics> diagnostics = workerDiagnostics(helper);
            final ServerLevel level = helper.getLevel();
            final BlockPos icePos = evidenceBlockPos(helper, diagnostics.map(RegionThreadingDiagnostics::regionPolicy).orElse(RegionPolicy.FIXED_3X3), diagnostics.map(RegionThreadingDiagnostics::regionChunkSize).orElse(RegionLayout.FIXED_3X3_REGION_CHUNK_SIZE), 2);
            helper.addEndListener(passed -> {
                randomTickSpeed.set(previousRandomTickSpeed, level.getServer());
                level.setBlock(icePos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(icePos.above(), Blocks.AIR.defaultBlockState(), 3);
                releaseForcedChunk(level, icePos);
            });
            final long chunkTicksBefore = diagnostics.map(RegionThreadingDiagnostics::chunkTicksOnWorkers).orElse(0L);
            randomTickSpeed.set(1000, level.getServer());

            helper.startSequence()
                    .thenExecute(() -> {
                        forceChunk(level, icePos);
                        // Place the light first and let the light engine update the forced chunk before the
                        // ice exists; the ice random tick only melts above block light 11.
                        level.setBlock(icePos.above(), Blocks.GLOWSTONE.defaultBlockState(), 3);
                    })
                    .thenIdle(2)
                    .thenExecute(() -> level.setBlock(icePos, Blocks.ICE.defaultBlockState(), 3))
                    .thenIdle(60)
                    .thenExecute(() -> {
                        helper.assertTrue(
                                level.getBlockState(icePos).is(Blocks.WATER),
                                "ice did not melt, so the selected random tick was not executed; state="
                                        + level.getBlockState(icePos));
                        if (diagnostics.isPresent() && diagnostics.get().chunkTickWorkerExecutionEnabled()) {
                            final RegionThreadingDiagnostics after = diagnostics();
                            helper.assertTrue(
                                    after.chunkTickWorkerDispatchEffective(),
                                    "a configured chunk tick slice must report the effective dispatch");
                            helper.assertTrue(
                                    after.chunkTicksOnWorkers() > chunkTicksBefore,
                                    "the interior evidence chunk did not tick on a region worker: before=" + chunkTicksBefore
                                            + " after=" + after.chunkTicksOnWorkers()
                                            + " fallback=" + after.fallbackChunkTicks()
                                            + " boundary=" + after.boundaryChunkTicks()
                                            + " unloadedNeighborhood=" + after.unloadedNeighborhoodChunkTicks());
                        } else {
                            assertSliceDisabled(helper, true, "chunk tick worker slice");
                        }
                    })
                    .thenSucceed();
        });
    }

    /**
     * {@return the diagnostics of the installed engine, or an empty optional when the bridge is off}
     * <p>
     * Asserts the default-off state instead of silently skipping when the bridge is absent.
     */
    private static Optional<RegionThreadingDiagnostics> workerDiagnostics(GameTestHelper helper) {
        final Optional<RegionThreadingDiagnostics> diagnostics = RegionThreadingHooks.diagnostics();
        if (diagnostics.isEmpty()) {
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingBridge.get(),
                    "region threading bridge is enabled but its diagnostics are not installed");
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingBlockEntityTicks.get(),
                    "block entity worker slice is enabled but the bridge did not install it");
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingRandomTicks.get(),
                    "random tick worker slice is enabled but the bridge did not install it");
        }
        return diagnostics;
    }

    /**
     * Asserts that the slice the test could not exercise is explicitly configured off.
     */
    private static void assertSliceDisabled(GameTestHelper helper, boolean randomTickSlice, String sliceName) {
        if (randomTickSlice) {
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingRandomTicks.get(), sliceName + " is enabled but its worker execution is disabled");
        } else {
            helper.assertFalse(NeoForgeConfig.SERVER.enableRegionThreadingBlockEntityTicks.get(), sliceName + " is enabled but its worker execution is disabled");
        }
    }

    private static RegionThreadingDiagnostics diagnostics() {
        return RegionThreadingHooks.diagnostics().orElseThrow(() -> new AssertionError("region threading diagnostics disappeared during the test"));
    }

    /**
     * Chooses an absolute block position for one test in an isolated evidence area.
     * <p>
     * The fixed 3x3 and dynamic region layouts both put many test structures into one region, and the
     * GameTest runner keeps all previously placed structures in the level. An evidence block next to a
     * structure could therefore share a region with another test's block entities. Each test instead
     * uses its own slot far away from the structure grid. The slot's chunk is still made an interior
     * chunk, because the furnace family and random tick slices only leave the server thread when the
     * neighboring chunks belong to the same region.
     * <p>
     * {@code fixedRegionChunkSize} is the size of the installed layout's fixed grid
     * ({@code RegionThreadingDiagnostics.regionChunkSize()}), so the slot stays an interior chunk for any
     * configured {@code regionThreadingFixedRegionChunks} value.
     */
    private static BlockPos evidenceBlockPos(ExtendedGameTestHelper helper, RegionPolicy policy, int fixedRegionChunkSize, int slot) {
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        final int baseX = origin.getX();
        final int baseZ = origin.getZ() + EVIDENCE_AREA_Z_OFFSET + slot * EVIDENCE_AREA_SPACING;
        int chunkX = Math.floorDiv(baseX, 16);
        int chunkZ = Math.floorDiv(baseZ, 16);
        if (policy == RegionPolicy.FIXED_3X3) {
            chunkX = nearestChunkWithResidue(chunkX, fixedRegionChunkSize, 1);
            chunkZ = nearestChunkWithResidue(chunkZ, fixedRegionChunkSize, 1);
        } else {
            chunkX = dynamicInteriorChunk(chunkX);
            chunkZ = dynamicInteriorChunk(chunkZ);
        }
        return new BlockPos(chunkX * 16 + 8, origin.getY() + 2, chunkZ * 16 + 8);
    }

    /**
     * Removes the block entities of the single evidence chunk after it was force-loaded.
     * <p>
     * Only the loaded evidence chunk is touched. The old cleanup walked all nine chunks of a fixed
     * region, force-loading chunks next to other test structures and deleting their block entities.
     */
    private static void clearEvidenceChunkBlockEntities(ServerLevel level, BlockPos pos) {
        final var chunk = level.getChunkAt(pos);
        for (final BlockPos blockEntityPos : new java.util.ArrayList<>(chunk.getBlockEntities().keySet())) {
            level.removeBlock(blockEntityPos, false);
        }
    }

    /**
     * Adds a forced ticket so the target chunk is block-ticking even though the test structure is placed
     * far away from the world spawn. Without the ticket the block entity and random tick loops skip the
     * chunk and the test would not exercise any worker.
     */
    private static void forceChunk(ServerLevel level, BlockPos pos) {
        final ChunkPos chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        // A plain FORCED ticket keeps the chunk block-ticking but vanilla skips the chunk tick when no
        // player is nearby. The force-ticking ticket makes the isolated evidence chunk tick without
        // moving a player (and loading a whole player view) to it.
        level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 1, chunk, true);
    }

    private static void releaseForcedChunk(ServerLevel level, BlockPos pos) {
        final ChunkPos chunk = new ChunkPos(pos);
        level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 1, chunk, true);
        level.setChunkForced(chunk.x, chunk.z, false);
    }

    private static int nearestChunkWithResidue(int chunk, int size, int residue) {
        return chunk + Math.floorMod(residue - Math.floorMod(chunk, size), size);
    }

    private static int dynamicInteriorChunk(int chunk) {
        final int residue = Math.floorMod(chunk, 16);
        if (residue == 0) {
            return chunk + 1;
        }
        if (residue == 15) {
            return chunk - 1;
        }
        return chunk;
    }
}
