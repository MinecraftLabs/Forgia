/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.region.RegionAwareNeighborUpdater;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntitySnapshots;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.jetbrains.annotations.Nullable;

/** The box of positions a layout traces: the state and block entity of every position inside it. */
record TraceBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {}

/** The state and block entity of one position, as the comparison harness spells them. */
record Cell(BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {}

/**
 * A level mock with the production write, signal, block-entity, block-event and region-hook seams.
 * <p>
 * The block states and block entities live in concurrent maps so the propagation passes and the region
 * workers can read each other's committed state, and the scheduled ticks are the real {@link LevelTicks}
 * scheduler. Every hook the production level calls before it applies a write, a comparator update, a
 * block-entity change or a block event is called here too, so a worker write to another region goes through
 * the same deferred-write capture and fixpoint the production level uses instead of being applied inline.
 * <p>
 * Every change of a block state, a block entity or a container's contents marks its position dirty, so a
 * comparison harness can hash the state that changed in a tick instead of the whole world. The block
 * entities are ticked the way the level ticks them - the moving piston and the hopper - under the same
 * region task hook the production block-entity slice uses.
 */
final class RegionRedstoneWorld {
    final ServerLevel level = mock(ServerLevel.class, withSettings().stubOnly());
    final Thread serverThread = Thread.currentThread();
    final Map<BlockPos, BlockState> states = new ConcurrentHashMap<>();
    final Map<BlockPos, BlockEntity> blockEntities = new ConcurrentHashMap<>();
    /**
     * The level's ordered block event set, as {@code ServerLevel.blockEvents}: an insertion-ordered set, so an
     * event body that queues the same event again is a no-op and the phase cannot run one piston move twice.
     */
    private final Set<BlockEvent> blockEvents = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    /**
     * The block events the phase of one tick drained, in order, as {@code tick:pos:block:id:param}.
     * <p>
     * Recorded before the event body runs, so the trace is the phase's input and not its effect: an event
     * whose block changed since it was queued is part of the stream of both runs alike.
     */
    private final List<String> eventTrace = new java.util.concurrent.CopyOnWriteArrayList<>();
    final LevelTicks<Block> blockTicks = new LevelTicks<>(pos -> true, () -> InactiveProfiler.INSTANCE);
    final RandomSource scheduledRandom;
    private final RegionAwareNeighborUpdater neighborUpdater;
    private final AtomicLong gameTime;
    /**
     * The positions whose block state, block entity or container contents changed since the last
     * {@link #drainDirty()}. A comparison harness hashes these positions only, which is what keeps a
     * per-tick comparison of a long soak affordable; every write of this world goes through a method that
     * marks its position, so no change can escape the set.
     */
    private final Set<BlockPos> dirty = ConcurrentHashMap.newKeySet();
    /**
     * Every scheduled tick of the run, as {@code triggerTick:pos:block}, when a comparison harness asked for
     * it. The trace is the input of the scheduler in both configurations, so a difference between two runs is
     * the first scheduling call that differed, which is what a queue-size difference has to be reduced to.
     */
    private List<String> scheduleTrace;
    /**
     * The box whose block writes, neighbor updates and scheduled ticks are recorded in {@link #eventTrace}, for
     * a diagnosis that has to name the first event one run produced and the other did not.
     */
    private TraceBox tracedBox;
    private List<String> positionEvents;
    /**
     * Block-entity reads a region worker had to answer with {@code null} because the position belongs to
     * another region that is not quiescent and the position is not a published border block entity. This is the
     * documented approximation of the block-entity gate, and a comparison harness has to count it instead of
     * failing on the state difference it can cause.
     */
    private final java.util.concurrent.atomic.AtomicLong refusedBlockEntityReads = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Block-entity reads a region worker answered from the per-tick publication instead of {@code null}: the
     * read was refused by the gate and the position is a published border block entity of another region.
     */
    private final java.util.concurrent.atomic.AtomicLong publishedBlockEntityReads = new java.util.concurrent.atomic.AtomicLong();
    /** Writes a native region worker made to a position outside the region it leases. */
    private final java.util.concurrent.atomic.AtomicLong foreignWriteAttempts = new java.util.concurrent.atomic.AtomicLong();
    /** Of those, the writes the border capture did not take. */
    private final java.util.concurrent.atomic.AtomicLong uncapturedForeignWrites = new java.util.concurrent.atomic.AtomicLong();
    /**
     * The write counter of every chunk, i.e. the version a worker's block-state read of that chunk is recorded
     * with. A region re-runs its propagation when a chunk version it read has changed by the end of the phase,
     * which is the machinery that makes a dust network spanning two regions converge to the single-threaded
     * power value. Without it the harness would let a worker keep the value it computed from a chunk another
     * worker changed under the read.
     */
    private final Map<Long, java.util.concurrent.atomic.AtomicLong> chunkVersions = new ConcurrentHashMap<>();
    /** The positions of those refused reads, so a harness can name the circuit the approximation hit. */
    private final List<BlockPos> refusedReadPositions = new java.util.concurrent.CopyOnWriteArrayList<>();

    private record BlockEvent(BlockPos pos, Block block, int id, int param) {}

    RegionRedstoneWorld(AtomicLong gameTime) {
        this.gameTime = gameTime;
        this.scheduledRandom = RandomSource.create(0x5EEDL);
        for (int chunkX = -2; chunkX <= 12; chunkX++) {
            this.blockTicks.addContainer(new ChunkPos(chunkX, 0), new LevelChunkTicks<>());
        }
        this.neighborUpdater = new RegionAwareNeighborUpdater(this.level, 1000000);
        setLevelField(this.level, "random", RandomSource.create(4321L));
        doReturn(InactiveProfiler.INSTANCE).when(this.level).getProfiler();
        doReturn(1234L).when(this.level).getSeed();
        doAnswer(invocation -> this.gameTime.get()).when(this.level).getGameTime();
        doReturn(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"))).when(this.level).dimension();
        doReturn(1000000).when(this.level).maxChainedNeighborUpdates();
        doReturn(this.blockTicks).when(this.level).getBlockTicks();
        doReturn(0).when(this.level).getMinBuildHeight();
        doReturn(384).when(this.level).getMaxBuildHeight();
        doReturn(false).when(this.level).isOutsideBuildHeight(any(BlockPos.class));
        doReturn(true).when(this.level).hasChunkAt(any(BlockPos.class));
        doReturn(new WorldBorder()).when(this.level).getWorldBorder();
        doAnswer(invocation -> BuiltInRegistries.BLOCK.asLookup()).when(this.level).holderLookup(any());
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            // The production level reads a block state through its chunk, which records the version of a chunk
            // of another region so the fixpoint can re-run a region whose foreign read went stale.
            // Production counts a read of a position whose deferred write has not been applied yet.
            RegionThreadingHooks.observeReadAfterDeferredBlockWrite(pos);
            return this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }).when(this.level).getBlockState(any(BlockPos.class));
        doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()).getFluidState())
                .when(this.level).getFluidState(any(BlockPos.class));
        doAnswer(invocation -> this.setBlock(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), 512))
                .when(this.level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt());
        doAnswer(invocation -> this.setBlock(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3)))
                        .when(this.level).setBlock(any(BlockPos.class), any(BlockState.class), anyInt(), anyInt());
        doAnswer(invocation -> this.removeBlock(invocation.getArgument(0), invocation.getArgument(1)))
                .when(this.level).removeBlock(any(BlockPos.class), anyBoolean());
        doAnswer(invocation -> {
            this.neighborUpdater.updateNeighborsAtExceptFromFacing(invocation.getArgument(0), invocation.getArgument(1), null);
            return null;
        }).when(this.level).updateNeighborsAt(any(BlockPos.class), any(Block.class));
        doAnswer(invocation -> {
            this.neighborUpdater.updateNeighborsAtExceptFromFacing(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }).when(this.level).updateNeighborsAtExceptFromFacing(any(BlockPos.class), any(Block.class), any());
        doAnswer(invocation -> {
            this.recordEvent("neighbor", invocation.getArgument(0), String.valueOf((Object) invocation.getArgument(1)));
            this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(this.level).neighborChanged(any(BlockPos.class), any(Block.class), any(BlockPos.class));
        doAnswer(invocation -> {
            this.recordEvent("neighbor", invocation.getArgument(1), String.valueOf((Object) invocation.getArgument(2)));
            this.neighborUpdater.neighborChanged(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
            return null;
        }).when(this.level).neighborChanged(any(BlockState.class), any(BlockPos.class), any(Block.class), any(BlockPos.class),
                anyBoolean());
        doAnswer(invocation -> {
            this.neighborUpdater.shapeUpdate(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5));
            return null;
        }).when(this.level).neighborShapeChanged(any(Direction.class), any(BlockState.class), any(BlockPos.class),
                any(BlockPos.class), anyInt(), anyInt());
        doAnswer(invocation -> {
            this.updateNeighboursAt(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(this.level).blockUpdated(any(BlockPos.class), any(Block.class));
        doAnswer(invocation -> {
            this.updateNeighbourForOutputSignal(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(this.level).updateNeighbourForOutputSignal(any(BlockPos.class), any(Block.class));
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            int signal = state.getSignal(this.level, pos, invocation.getArgument(1));
            if (state.shouldCheckWeakPower(this.level, pos, invocation.getArgument(1))) {
                signal = Math.max(signal, this.directSignalTo(pos));
            }
            return signal;
        }).when(this.level).getSignal(any(BlockPos.class), any(Direction.class));
        doAnswer(invocation -> this.states.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState())
                .getDirectSignal(this.level, invocation.getArgument(0), invocation.getArgument(1)))
                        .when(this.level).getDirectSignal(any(BlockPos.class), any(Direction.class));
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            final Direction direction = invocation.getArgument(1);
            final boolean diodesOnly = invocation.getArgument(2);
            final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            if (diodesOnly) {
                return DiodeBlock.isDiode(state) ? this.level.getDirectSignal(pos, direction) : 0;
            }
            if (state.is(Blocks.REDSTONE_BLOCK)) {
                return 15;
            }
            if (state.is(Blocks.REDSTONE_WIRE)) {
                return state.getValue(BlockStateProperties.POWER);
            }
            return state.isSignalSource() ? this.level.getDirectSignal(pos, direction) : 0;
        }).when(this.level).getControlInputSignal(any(BlockPos.class), any(Direction.class), anyBoolean());
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            int best = 0;
            for (final Direction direction : Direction.values()) {
                final int signal = this.level.getSignal(pos.relative(direction), direction);
                if (signal >= 15) {
                    return 15;
                }
                best = Math.max(best, signal);
            }
            return best;
        }).when(this.level).getBestNeighborSignal(any(BlockPos.class));
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            for (final Direction direction : Direction.values()) {
                if (this.level.getSignal(pos.relative(direction), direction) > 0) {
                    return true;
                }
            }
            return false;
        }).when(this.level).hasNeighborSignal(any(BlockPos.class));
        doAnswer(invocation -> this.level.getSignal(invocation.getArgument(0), invocation.getArgument(1)) > 0)
                .when(this.level).hasSignal(any(BlockPos.class), any(Direction.class));
        doAnswer(invocation -> this.getBlockEntity(invocation.getArgument(0)))
                .when(this.level).getBlockEntity(any(BlockPos.class));
        doAnswer(invocation -> {
            final BlockEntity blockEntity = invocation.getArgument(0);
            blockEntity.setLevel(this.level);
            blockEntity.clearRemoved();
            final BlockPos pos = blockEntity.getBlockPos().immutable();
            this.blockEntities.put(pos, blockEntity);
            this.dirty.add(pos);
            return null;
        }).when(this.level).setBlockEntity(any(BlockEntity.class));
        doAnswer(invocation -> {
            final BlockPos pos = invocation.getArgument(0);
            this.blockEntities.remove(pos);
            this.dirty.add(pos);
            this.updateNeighbourForOutputSignal(pos, this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()).getBlock());
            return null;
        }).when(this.level).removeBlockEntity(any(BlockPos.class));
        doAnswer(invocation -> {
            final BlockPos pos = ((BlockPos) invocation.getArgument(0)).immutable();
            // The production level hands the unsaved-chunk mark of a block entity of another region to the
            // target region's propagation pass; the mark itself has no observable state in this mock, but
            // the position did change, so the harness has to see it.
            this.dirty.add(pos);
            if (RegionThreadingHooks.deferBorderBlockEntityChange(this.level, pos)) {
                return null;
            }
            return null;
        }).when(this.level).blockEntityChanged(any(BlockPos.class));
        doAnswer(invocation -> {
            final BlockPos pos = ((BlockPos) invocation.getArgument(0)).immutable();
            final Block block = invocation.getArgument(1);
            final int id = invocation.getArgument(2);
            final int param = invocation.getArgument(3);
            if (!RegionThreadingHooks.deferBlockEvent(this.level, pos, block, id, param)) {
                if (Thread.currentThread() != this.serverThread) {
                    throw new IllegalStateException("a region worker mutated the level-wide block event set");
                }
                this.blockEvents.add(new BlockEvent(pos, block, id, param));
            }
            return null;
        }).when(this.level).blockEvent(any(BlockPos.class), any(Block.class), anyInt(), anyInt());
        doAnswer(invocation -> {
            final BlockPos pos = ((BlockPos) invocation.getArgument(0)).immutable();
            final Block block = invocation.getArgument(1, Block.class);
            final int delay = invocation.getArgument(2, Integer.class);
            this.recordSchedule(pos, block, delay);
            this.blockTicks.schedule(new ScheduledTick<>(block, pos, this.gameTime.get() + delay, 0));
            return null;
        }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt());
        doAnswer(invocation -> {
            final BlockPos pos = ((BlockPos) invocation.getArgument(0)).immutable();
            final Block block = invocation.getArgument(1, Block.class);
            final int delay = invocation.getArgument(2, Integer.class);
            this.recordSchedule(pos, block, delay);
            this.blockTicks.schedule(new ScheduledTick<>(block, pos, this.gameTime.get() + delay,
                    invocation.getArgument(3, TickPriority.class), 0L));
            return null;
        }).when(this.level).scheduleTick(any(BlockPos.class), any(Block.class), anyInt(), any(TickPriority.class));
        doAnswer(invocation -> null).when(this.level).playSound(any(), any(BlockPos.class), any(), any(), anyFloat(), anyFloat());
        doAnswer(invocation -> null).when(this.level).levelEvent(any(), anyInt(), any(BlockPos.class), anyInt());
        doAnswer(invocation -> null).when(this.level).gameEvent(any(Holder.class), any(BlockPos.class), any(GameEvent.Context.class));
        doAnswer(invocation -> null).when(this.level).gameEvent(any(Holder.class), any(Vec3.class), any(GameEvent.Context.class));
        doAnswer(invocation -> null).when(this.level).gameEvent(any(Entity.class), any(Holder.class), any(BlockPos.class));
        doAnswer(invocation -> null).when(this.level).addDestroyBlockEffect(any(BlockPos.class), any(BlockState.class));
        doAnswer(invocation -> null).when(this.level).addFreshEntity(any());
        doReturn(List.of()).when(this.level).getEntities(any(), any(AABB.class));
        doReturn(List.of()).when(this.level).getEntities(any(Entity.class), any(AABB.class), any());
        doReturn(List.of()).when(this.level).getEntitiesOfClass(any(), any(AABB.class));
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doAnswer(invocation -> {
            final BooleanSupplier condition = invocation.getArgument(0);
            while (!condition.getAsBoolean()) {
                LockSupport.parkNanos(100_000L);
            }
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(this.level).getChunkSource();
        setLevelField(this.level, "neighborUpdater", this.neighborUpdater);
    }

    /** Starts recording the writes and neighbor updates of {@code box}, or stops when it is null. */
    void tracePositions(@Nullable TraceBox box) {
        this.tracedBox = box;
        this.positionEvents = box == null ? null : new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    /** {@return the recorded events of the traced box, in the order the level produced them} */
    List<String> positionEvents() {
        return this.positionEvents == null ? List.of() : new ArrayList<>(this.positionEvents);
    }

    private void recordEvent(String kind, BlockPos pos, Object detail) {
        final TraceBox box = this.tracedBox;
        if (box == null || pos.getX() < box.minX() || pos.getX() > box.maxX() || pos.getY() < box.minY()
                || pos.getY() > box.maxY() || pos.getZ() < box.minZ() || pos.getZ() > box.maxZ()) {
            return;
        }
        this.positionEvents.add(this.gameTime.get() + ":" + kind + ":" + pos.toShortString() + ":" + detail);
    }

    /** Starts or stops recording every scheduled tick of this world. */
    void traceScheduling(boolean enabled) {
        this.scheduleTrace = enabled ? new java.util.concurrent.CopyOnWriteArrayList<>() : null;
    }

    /** {@return the block-entity reads the region gate answered with null} */
    long refusedBlockEntityReads() {
        return this.refusedBlockEntityReads.get();
    }

    /** {@return the block-entity reads the region gate answered from the per-tick publication} */
    long publishedBlockEntityReads() {
        return this.publishedBlockEntityReads.get();
    }

    /** {@return the writes a native region worker made to another region} */
    long foreignWriteAttempts() {
        return this.foreignWriteAttempts.get();
    }

    /** {@return the foreign region writes the border capture did not take} */
    long uncapturedForeignWrites() {
        return this.uncapturedForeignWrites.get();
    }

    /** {@return the positions of the refused reads since the last drain, and forgets them} */
    List<BlockPos> drainRefusedReads() {
        final List<BlockPos> drained = new ArrayList<>(this.refusedReadPositions);
        this.refusedReadPositions.clear();
        return drained;
    }

    /**
     * Counts the scheduled ticks the level still holds per comparison slot: {@code cellOf} maps a position to
     * its slot, and every slot of {@code counts} is cleared first.
     */
    void countPendingTicks(int[] counts, java.util.function.ToIntFunction<BlockPos> cellOf) {
        java.util.Arrays.fill(counts, 0);
        try {
            final java.lang.reflect.Field containersField = LevelTicks.class.getDeclaredField("allContainers");
            containersField.setAccessible(true);
            final Map<?, ?> containers = (Map<?, ?>) containersField.get(this.blockTicks);
            for (final Object container : containers.values()) {
                @SuppressWarnings("unchecked")
                final java.util.stream.Stream<ScheduledTick<Block>> ticks = ((LevelChunkTicks<Block>) container).getAll();
                ticks.forEach(tick -> counts[cellOf.applyAsInt(tick.pos())]++);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return every scheduled tick the level's scheduler still holds, as {@code triggerTick:pos:block}, sorted}
     * <p>
     * The scheduler keeps its queues private, so a diagnosis reads the per-chunk set of scheduled ticks
     * reflectively. This is the state a queue-size comparison has to name: which tick one run still holds and
     * the other does not.
     */
    List<String> pendingTicks() {
        try {
            final java.lang.reflect.Field containersField = LevelTicks.class.getDeclaredField("allContainers");
            containersField.setAccessible(true);
            final Map<?, ?> containers = (Map<?, ?>) containersField.get(this.blockTicks);
            final List<String> pending = new ArrayList<>();
            for (final Object container : containers.values()) {
                @SuppressWarnings("unchecked")
                final java.util.stream.Stream<ScheduledTick<Block>> ticks = ((LevelChunkTicks<Block>) container).getAll();
                ticks.forEach(tick -> pending.add(tick.triggerTick() + ":" + tick.pos().toShortString() + ":"
                        + BuiltInRegistries.BLOCK.getKey(tick.type())));
            }
            pending.sort(java.util.Comparator.naturalOrder());
            return pending;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** {@return the scheduled ticks recorded so far, in the order the level received them} */
    List<String> scheduleTrace() {
        return this.scheduleTrace == null ? List.of() : new ArrayList<>(this.scheduleTrace);
    }

    private long chunkVersion(BlockPos pos) {
        final java.util.concurrent.atomic.AtomicLong version = this.chunkVersions.get(ChunkPos.asLong(pos));
        return version == null ? 0L : version.get();
    }

    private void bumpChunkVersion(BlockPos pos) {
        this.chunkVersions.computeIfAbsent(ChunkPos.asLong(pos), key -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
    }

    private void recordSchedule(BlockPos pos, Block block, int delay) {
        if (this.scheduleTrace != null) {
            this.scheduleTrace.add((this.gameTime.get() + delay) + ":" + pos.toShortString() + ":" + BuiltInRegistries.BLOCK.getKey(block));
        }
    }

    /** Adds the scheduled-tick container of one chunk, so a tick scheduled there is not dropped. */
    void addTickContainer(int chunkX, int chunkZ) {
        this.blockTicks.addContainer(new ChunkPos(chunkX, chunkZ), new LevelChunkTicks<>());
    }

    private int directSignalTo(BlockPos pos) {
        int best = 0;
        best = Math.max(best, this.level.getDirectSignal(pos.below(), Direction.DOWN));
        best = Math.max(best, this.level.getDirectSignal(pos.above(), Direction.UP));
        best = Math.max(best, this.level.getDirectSignal(pos.north(), Direction.NORTH));
        best = Math.max(best, this.level.getDirectSignal(pos.south(), Direction.SOUTH));
        best = Math.max(best, this.level.getDirectSignal(pos.west(), Direction.WEST));
        best = Math.max(best, this.level.getDirectSignal(pos.east(), Direction.EAST));
        return best;
    }

    private void updateNeighboursAt(BlockPos pos, Block block) {
        this.neighborUpdater.updateNeighborsAtExceptFromFacing(pos, block, null);
    }

    private void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
        for (final Direction direction : Direction.values()) {
            final BlockPos neighbourPos = pos.relative(direction);
            if (!this.level.hasChunkAt(neighbourPos)) {
                continue;
            }
            // The production level hands one direction of the comparator update to the target region's
            // propagation pass when that position is outside the caller's lease.
            if (RegionThreadingHooks.deferBorderComparatorUpdate(this.level, neighbourPos, pos, block, direction)) {
                continue;
            }
            BlockState neighbourState = this.level.getBlockState(neighbourPos);
            neighbourState.onNeighborChange(this.level, neighbourPos, pos);
            if (neighbourState.isRedstoneConductor(this.level, neighbourPos)) {
                final BlockPos weakPos = neighbourPos.relative(direction);
                final BlockState weakState = this.level.getBlockState(weakPos);
                if (weakState.getWeakChanges(this.level, weakPos)) {
                    this.level.neighborChanged(weakState, weakPos, block, neighbourPos, false);
                }
            }
        }
    }

    private boolean removeBlock(BlockPos pos, boolean isMoving) {
        final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        return this.setBlock(pos, state.getFluidState().createLegacyBlock(), 3 | (isMoving ? 64 : 0), 512);
    }

    /**
     * The vanilla chunk write, block entity lifecycle and neighbor/shape publication of
     * {@code Level.setBlock}, condensed to what the redstone components reach.
     */
    private boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        pos = pos.immutable();
        final BlockState previous = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        final boolean moving = (flags & 64) != 0;
        if (previous == state) {
            return false;
        }
        // The production level records a write to another region and lets the target region's worker apply
        // the whole vanilla call in the same tick's fixpoint. The accepted answer is the documented
        // read-after-write approximation: the write is not applied yet.
        final boolean foreign = RegionThreadingHooks.isNativeRegionWorkerThread()
                && !RegionThreadingHooks.isRegionTickOwnedByCurrentThread(this.level, pos);
        if (foreign) {
            this.foreignWriteAttempts.incrementAndGet();
        }
        if (RegionThreadingHooks.deferBorderBlockWrite(this.level, pos, state, flags, recursionLeft)) {
            return true;
        }
        if (foreign) {
            this.uncapturedForeignWrites.incrementAndGet();
        }
        this.states.put(pos, state);
        this.dirty.add(pos);
        this.bumpChunkVersion(pos);
        this.recordEvent("set", pos, state);
        previous.onRemove(this.level, pos, state, moving);
        if (!this.states.get(pos).is(state.getBlock())) {
            return false;
        }
        state.onPlace(this.level, pos, previous, moving);
        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = this.blockEntities.get(pos);
            if (blockEntity != null && !blockEntity.isValidBlockState(state)) {
                this.blockEntities.remove(pos);
                blockEntity = null;
            }
            if (blockEntity == null) {
                blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                if (blockEntity != null) {
                    blockEntity.setLevel(this.level);
                    blockEntity.clearRemoved();
                    this.blockEntities.put(pos, blockEntity);
                }
            } else {
                blockEntity.setBlockState(state);
            }
        }
        // Level.markAndNotifyBlock: a reentrant onPlace may already have written another state at this
        // position, in which case the outer write publishes nothing (vanilla compares the state again).
        if (this.states.get(pos) != state) {
            return true;
        }
        if ((flags & 1) != 0) {
            this.updateNeighboursAt(pos, previous.getBlock());
            if (state.hasAnalogOutputSignal()) {
                this.updateNeighbourForOutputSignal(pos, state.getBlock());
            }
        }
        if ((flags & 16) == 0) {
            final int shapeFlags = flags & ~34;
            previous.updateIndirectNeighbourShapes(this.level, pos, shapeFlags, 511);
            state.updateNeighbourShapes(this.level, pos, shapeFlags, 511);
            state.updateIndirectNeighbourShapes(this.level, pos, shapeFlags, 511);
        }
        return true;
    }

    private BlockEntity getBlockEntity(BlockPos pos) {
        pos = pos.immutable();
        if (Thread.currentThread() != this.serverThread
                && !RegionThreadingHooks.isRegionTickOwnedByCurrentThread(this.level, pos)
                && !RegionThreadingHooks.isForeignRegionBlockEntityReadable(this.level, pos)) {
            // The production read answers the value published for a border block entity of another region
            // instead of null; a position that is not published keeps the null answer and is counted.
            final BlockEntity published = RegionThreadingHooks.snapshotBlockEntity(this.level, pos);
            if (published != null) {
                this.publishedBlockEntityReads.incrementAndGet();
                return published;
            }
            this.refusedBlockEntityReads.incrementAndGet();
            this.refusedReadPositions.add(pos);
            return null;
        }
        return this.blockEntities.get(pos);
    }

    /**
     * Publishes the border block entities of this world for the tick that is about to run, exactly like the
     * engine publishes them at the head of a level tick: the immutable values of every block entity whose 3x3
     * chunk neighbourhood touches another region. The world owns its block entities in a map instead of in
     * loaded chunks, so it publishes them through the explicit block-entity source of the publication.
     */
    void publishBlockEntitySnapshot() {
        RegionBlockEntitySnapshots.publish(this.level, RegionLayout.FIXED_3X3, this.blockEntities.values());
    }

    List<String> drainEventTrace() {
        final List<String> drained = new ArrayList<>(this.eventTrace);
        this.eventTrace.clear();
        return drained;
    }

    void runBlockEvents(boolean regionThreading) {
        if (regionThreading) {
            // The production phase opens its quiescent scope around the whole drain; the harness mirrors it so
            // the same lease pool and the same inline cross-region writes are exercised.
            RegionThreadingHooks.beginBlockEventPhase(this.level);
        }
        try {
            while (true) {
                final BlockEvent current;
                synchronized (this.blockEvents) {
                    final java.util.Iterator<BlockEvent> iterator = this.blockEvents.iterator();
                    if (!iterator.hasNext()) {
                        break;
                    }
                    current = iterator.next();
                    iterator.remove();
                }
                this.eventTrace.add(this.gameTime.get() + ":" + current.pos().toShortString() + ":"
                        + BuiltInRegistries.BLOCK.getKey(current.block()) + ":" + current.id() + ":" + current.param());
                final BlockState state = this.states.getOrDefault(current.pos(), Blocks.AIR.defaultBlockState());
                if (!state.is(current.block())) {
                    continue;
                }
                final Runnable body = () -> state.triggerEvent(this.level, current.pos(), current.id(), current.param());
                if (regionThreading) {
                    RegionThreadingHooks.runBlockTickTask(this.level, current.pos(), body);
                } else {
                    body.run();
                }
            }
        } finally {
            if (regionThreading) {
                RegionThreadingHooks.endBlockEventPhase(this.level);
            }
        }
    }

    /**
     * Ticks every block entity of the world whose block has a ticker, in position order: the moving piston and
     * the hopper. The position order keeps the pass deterministic for both runs, and the region task hook is
     * the same one the production block-entity slice uses for a tick that stays on the server thread, so a
     * cross-region write of a tick body goes through the deferred-write capture and its fixpoint.
     */
    void tickBlockEntities(boolean regionThreading) {
        final List<BlockPos> positions = new ArrayList<>(this.blockEntities.keySet());
        positions.sort(Comparator.comparingLong(BlockPos::asLong));
        for (final BlockPos pos : positions) {
            final BlockEntity blockEntity = this.blockEntities.get(pos);
            if (blockEntity == null) {
                continue;
            }
            final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            final Runnable body;
            if (blockEntity instanceof PistonMovingBlockEntity piston) {
                body = () -> PistonMovingBlockEntity.tick(this.level, pos, state, piston);
            } else if (blockEntity instanceof HopperBlockEntity hopper) {
                body = () -> HopperBlockEntity.pushItemsTick(this.level, pos, state, hopper);
            } else {
                continue;
            }
            if (regionThreading) {
                RegionThreadingHooks.runBlockTickTask(this.level, pos, body);
            } else {
                body.run();
            }
        }
    }

    /** {@return the number of block events that wait for the phase, i.e. the size of the level's event set} */
    int blockEventCount() {
        return this.blockEvents.size();
    }

    /** {@return the number of scheduled block ticks the level's scheduler still holds} */
    int scheduledTickCount() {
        return this.blockTicks.count();
    }

    /**
     * {@return the positions whose block state, block entity or container contents changed since the last
     * drain, and forgets them}
     */
    Set<BlockPos> drainDirty() {
        final Set<BlockPos> drained = new HashSet<>(this.dirty);
        this.dirty.removeAll(drained);
        return drained;
    }

    /** {@return the positions of every block entity the world holds, in position order} */
    List<BlockPos> blockEntityPositions() {
        final List<BlockPos> positions = new ArrayList<>(this.blockEntities.keySet());
        positions.sort(Comparator.comparingLong(BlockPos::asLong));
        return positions;
    }

    /**
     * {@return a hash of the block state and block entity of {@code pos}, or zero when the position holds
     * neither}
     * <p>
     * The hash is a pure function of the observable state of one position, so a harness that keeps a running
     * sum of it over every position it has seen compares exactly the state both runs reached, not the order in
     * which they reached it. Zero is reserved for "nothing here", so a position that goes back to air leaves
     * no trace in the sum.
     */
    long cellHash(BlockPos pos) {
        final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        final BlockEntity blockEntity = this.blockEntities.get(pos);
        if (state.isAir() && blockEntity == null) {
            return 0L;
        }
        long hash = mix(pos.asLong());
        hash = mix(hash ^ Block.BLOCK_STATE_REGISTRY.getId(state));
        if (blockEntity != null) {
            hash = mix(hash ^ describe(blockEntity).hashCode());
        }
        return hash == 0L ? 1L : hash;
    }

    /** A 64-bit avalanche mix (the splitmix64 finalizer), used to fold a position, a state and a block entity. */
    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** {@return the state and block entity of {@code pos} as the comparison spells them} */
    Cell cell(BlockPos pos) {
        return new Cell(pos, this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState()), this.blockEntities.get(pos));
    }

    /** {@return every position of {@code trace} whose state or block entity is not air} */
    List<Cell> cells(TraceBox trace) {
        final List<Cell> cells = new ArrayList<>();
        for (int x = trace.minX(); x <= trace.maxX(); x++) {
            for (int y = trace.minY(); y <= trace.maxY(); y++) {
                for (int z = trace.minZ(); z <= trace.maxZ(); z++) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    final BlockEntity blockEntity = this.blockEntities.get(pos);
                    if (!state.isAir() || blockEntity != null) {
                        cells.add(new Cell(pos, state, blockEntity));
                    }
                }
            }
        }
        return cells;
    }

    /** {@return the total number of items in every container block entity of the world} */
    int totalContainerItems() {
        int total = 0;
        for (final BlockEntity blockEntity : this.blockEntities.values()) {
            if (blockEntity instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    total += container.getItem(slot).getCount();
                }
            }
        }
        return total;
    }

    /**
     * The state and block entity of every non-air position of the trace box, ordered by x, y, z.
     * <p>
     * Air without a block entity is omitted: it is the constant background of the box, and a position
     * that changes between air and a block still changes the lines of the other ticks. Omitting it keeps
     * the trace of one run small enough for the test worker's heap.
     */
    String snapshot(TraceBox trace) {
        final StringBuilder snapshot = new StringBuilder();
        for (int x = trace.minX(); x <= trace.maxX(); x++) {
            for (int y = trace.minY(); y <= trace.maxY(); y++) {
                for (int z = trace.minZ(); z <= trace.maxZ(); z++) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    final BlockState state = this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
                    final BlockEntity blockEntity = this.blockEntities.get(pos);
                    if (state.isAir() && blockEntity == null) {
                        continue;
                    }
                    snapshot.append(x).append(',').append(y).append(',').append(z).append('=').append(state);
                    if (blockEntity != null) {
                        snapshot.append(' ').append(describe(blockEntity));
                    }
                    snapshot.append('\n');
                }
            }
        }
        return snapshot.toString();
    }

    private String describe(BlockEntity blockEntity) {
        if (blockEntity instanceof ComparatorBlockEntity comparator) {
            return "comparator[output=" + comparator.getOutputSignal() + "]";
        }
        if (blockEntity instanceof Container container) {
            final StringBuilder items = new StringBuilder("container[");
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                final ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                items.append(slot).append('=').append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append('x').append(stack.getCount()).append(';');
            }
            return items.append(']').toString();
        }
        if (blockEntity instanceof PistonMovingBlockEntity piston) {
            return "movingPiston[moved=" + piston.getMovedState()
                    + ",direction=" + piston.getDirection()
                    + ",extending=" + piston.isExtending()
                    + ",source=" + piston.isSourcePiston()
                    + ",progress=" + piston.getProgress(0.0F)
                    + ",lastTicked=" + piston.getLastTicked() + "]";
        }
        return blockEntity.getType().toString();
    }

    /** Sets a private field of {@link Level} on the mock, which Mockito cannot stub. */
    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final java.lang.reflect.Field field = Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
