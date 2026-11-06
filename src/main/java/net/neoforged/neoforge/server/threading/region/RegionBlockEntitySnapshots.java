/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-tick publication of the values a foreign reader needs about the border block entities of a level.
 * <p>
 * A comparator reads the analog output of the container in front of it through
 * {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity}, and the container can stand in the next
 * region. The live block entity of a running region may not be handed to another region's worker, so the
 * read is refused and the comparator computes {@code 0} - a wrong value. The server thread therefore
 * publishes the value the read needs, once per tick at the head of the level tick while every region is
 * quiescent, and a refused read is answered from it: the analog signal of every border block entity and, for
 * a container, a copy of its item list. This is the same treatment {@link RegionPlayerSnapshots} gives the
 * player list and the regionized POI storage gives its sections; the read answers a value and never a live
 * object.
 * <p>
 * <b>Scope.</b> Only a <em>border</em> block entity is published: one whose chunk has a chunk of another
 * region in its 3x3 neighbourhood. A block entity inside a region is never read across a border - a vanilla
 * read reaches at most one chunk past the reader - so it is not copied. The border test is the layout's chunk
 * grid, which is exact for the fixed policy (the shipped configuration) and a superset for the legacy dynamic
 * policy, whose regions are unions of whole grid cells: an extra entry costs a copy, a missing one would cost
 * the answer.
 * <p>
 * <b>Staleness.</b> A published answer is at most one tick old: it is the value the live block entity had when
 * the tick opened, before any region of the tick ran. It is counted separately from a live local read
 * ({@link #publishedAnswers()}, {@link #publishedSignalAnswers()}), so a snapshot answer is never mistaken for
 * one.
 * <p>
 * <b>Cost.</b> The publication walks the loaded chunks of the regionizer, copies the border ones and records
 * one immutable entry per block entity there; the entry is the {@link RegionSnapshotBlockEntity} a reader
 * receives, so a read allocates nothing. {@link #publishedEntries()}, {@link #copiedStacks()} and
 * {@link #lastPublishNanos()} report what the last publication cost.
 */
@ApiStatus.Internal
public final class RegionBlockEntitySnapshots {
    /** Upper bound of the item stacks one container contributes, so a modded container cannot blow up a tick. */
    private static final int MAX_COPIED_STACKS = 64;

    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final AtomicLong PUBLICATION_COUNT = new AtomicLong();
    private static final AtomicLong PUBLISHED_ENTRIES = new AtomicLong();
    private static final AtomicLong COPIED_CONTAINERS = new AtomicLong();
    private static final AtomicLong COPIED_STACKS = new AtomicLong();
    private static final AtomicLong LAST_PUBLISH_NANOS = new AtomicLong();
    private static final AtomicLong TOTAL_PUBLISH_NANOS = new AtomicLong();
    private static final AtomicLong PUBLISHED_ANSWERS = new AtomicLong();
    private static final AtomicLong PUBLISHED_SIGNAL_ANSWERS = new AtomicLong();
    private static final AtomicLong UNPUBLISHED = new AtomicLong();

    private RegionBlockEntitySnapshots() {}

    /**
     * One immutable publication of the border block entities of a level.
     * <p>
     * The map is keyed by {@link BlockPos#asLong()} and replaced, never mutated: a reader either sees the
     * previous publication or the next one, both complete.
     */
    public static final class Snapshot {
        private final Map<Long, RegionSnapshotBlockEntity> entries;
        private final long gameTime;
        private final int copiedContainers;
        private final int copiedStacks;

        private Snapshot(Map<Long, RegionSnapshotBlockEntity> entries, long gameTime, int copiedContainers, int copiedStacks) {
            this.entries = entries;
            this.gameTime = gameTime;
            this.copiedContainers = copiedContainers;
            this.copiedStacks = copiedStacks;
        }

        /** {@return the number of published border block entities} */
        public int size() {
            return this.entries.size();
        }

        /** {@return the game time of the level when the publication was taken} */
        public long gameTime() {
            return this.gameTime;
        }

        /** {@return the number of container block entities whose item list was copied} */
        public int copiedContainers() {
            return this.copiedContainers;
        }

        /** {@return the number of item stacks copied into the publication} */
        public int copiedStacks() {
            return this.copiedStacks;
        }

        /** {@return the published stand-in of {@code pos}, or {@code null} when the position is not a border block entity} */
        @Nullable
        public RegionSnapshotBlockEntity at(BlockPos pos) {
            return this.entries.get(pos.asLong());
        }
    }

    /**
     * Publishes the border block entities of every loaded chunk of {@code level}, called on the server thread at
     * the head of the level tick, before any region of the tick runs.
     * <p>
     * The candidate chunks are the loaded chunks the regionizer knows - a chunk loaded before the regionizer
     * existed is not in that index and keeps the {@code null} answer it has today - and only a chunk within one
     * chunk of a region edge contributes entries.
     */
    static void publish(ServerLevel level, RegionLayout layout, ThreadedRegionizer regionizer) {
        final long start = System.nanoTime();
        final Copy copy = new Copy();
        regionizer.forEach(region -> {
            if (region.coordinate().level() != level) {
                return;
            }
            for (final RegionSectionState section : region.sections()) {
                for (final long chunkKey : section.chunkKeys()) {
                    final int chunkX = ChunkPos.getX(chunkKey);
                    final int chunkZ = ChunkPos.getZ(chunkKey);
                    if (!isBorderChunk(layout, chunkX, chunkZ)) {
                        continue;
                    }
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk == null) {
                        continue;
                    }
                    for (final BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        copy.add(level, blockEntity);
                    }
                }
            }
        });
        copy.publish(level, start);
    }

    /**
     * Publishes the border block entities of an explicit block-entity source, for a level whose block entities
     * the server thread does not reach through loaded chunks.
     * <p>
     * The level mock of the redstone harness owns its block entities in a map instead of in loaded chunks, so it
     * publishes them through this entry point. The border filter, the recorded values and the counters are the
     * ones of the production publication.
     */
    @ApiStatus.Internal
    public static void publish(ServerLevel level, RegionLayout layout, Iterable<BlockEntity> blockEntities) {
        final long start = System.nanoTime();
        final Copy copy = new Copy();
        for (final BlockEntity blockEntity : blockEntities) {
            final BlockPos pos = blockEntity.getBlockPos();
            if (isBorderChunk(layout, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))) {
                copy.add(level, blockEntity);
            }
        }
        copy.publish(level, start);
    }

    /** The entries one publication collects, and what copying them cost. */
    private static final class Copy {
        private final Map<Long, RegionSnapshotBlockEntity> entries = new HashMap<>();
        private int containers;
        private int stacks;

        private void add(ServerLevel level, BlockEntity blockEntity) {
            final BlockPos pos = blockEntity.getBlockPos();
            if (blockEntity instanceof RegionSnapshotBlockEntity || this.entries.containsKey(pos.asLong())) {
                return;
            }
            final List<ItemStack> contents = this.copyContents(blockEntity);
            final int signal = AbstractContainerMenu.getRedstoneSignalFromBlockEntity(blockEntity);
            final RegionSnapshotBlockEntity published;
            try {
                published = new RegionSnapshotBlockEntity(level, blockEntity.getType(), pos, blockEntity.getBlockState(), signal, contents);
            } catch (RuntimeException exception) {
                // A block entity whose state does not match its type cannot be published: the read keeps the
                // null answer it has today instead of failing the publication of the whole level.
                UNPUBLISHED.incrementAndGet();
                return;
            }
            this.entries.put(pos.asLong(), published);
        }

        /**
         * {@return the copied item list of {@code blockEntity}, or an empty list when it is not a container}
         * <p>
         * Every published stack is a copy, so no live {@link ItemStack} of the owning region is reachable
         * through the publication. The copy is bounded by {@value #MAX_COPIED_STACKS} stacks.
         */
        private List<ItemStack> copyContents(BlockEntity blockEntity) {
            if (!(blockEntity instanceof Container container)) {
                return List.of();
            }
            final int slots = Math.min(container.getContainerSize(), MAX_COPIED_STACKS);
            final List<ItemStack> contents = new ArrayList<>(slots);
            for (int slot = 0; slot < slots; slot++) {
                final ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    contents.add(stack.copy());
                }
            }
            this.containers++;
            this.stacks += contents.size();
            return List.copyOf(contents);
        }

        private void publish(ServerLevel level, long startNanos) {
            SNAPSHOTS.put(level, new Snapshot(Map.copyOf(this.entries), level.getGameTime(), this.containers, this.stacks));
            PUBLICATION_COUNT.incrementAndGet();
            PUBLISHED_ENTRIES.set(this.entries.size());
            COPIED_CONTAINERS.set(this.containers);
            COPIED_STACKS.set(this.stacks);
            final long nanos = System.nanoTime() - startNanos;
            LAST_PUBLISH_NANOS.set(nanos);
            TOTAL_PUBLISH_NANOS.addAndGet(nanos);
        }
    }

    /**
     * {@return true when the chunk {@code (chunkX, chunkZ)} has a chunk of another region in its 3x3
     * neighbourhood}
     */
    static boolean isBorderChunk(RegionLayout layout, int chunkX, int chunkZ) {
        final int size = layout.regionChunkSize();
        final int localX = Math.floorMod(chunkX, size);
        final int localZ = Math.floorMod(chunkZ, size);
        return localX == 0 || localX == size - 1 || localZ == 0 || localZ == size - 1;
    }

    /**
     * {@return the published stand-in of {@code pos} in this tick, or {@code null} when the position is not a
     * published border block entity of {@code level}}
     * <p>
     * A non-null answer is counted as a published block-entity answer, which is what keeps it apart from a live
     * local read.
     */
    @Nullable
    public static RegionSnapshotBlockEntity standIn(ServerLevel level, BlockPos pos) {
        final Snapshot snapshot = SNAPSHOTS.get(level);
        if (snapshot == null) {
            return null;
        }
        final RegionSnapshotBlockEntity standIn = snapshot.at(pos);
        if (standIn != null) {
            PUBLISHED_ANSWERS.incrementAndGet();
        }
        return standIn;
    }

    /**
     * {@return the recorded analog signal of {@code standIn}}, counting the answer
     * <p>
     * This is the value the patched seam of {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity}
     * answers: the publication is read and the owning region is never reached.
     */
    public static int answerSignal(RegionSnapshotBlockEntity standIn) {
        PUBLISHED_SIGNAL_ANSWERS.incrementAndGet();
        return standIn.analogOutputSignal();
    }

    /** {@return the publication of {@code level} for the current tick, or {@code null} when there is none} */
    @Nullable
    public static Snapshot of(ServerLevel level) {
        return SNAPSHOTS.get(level);
    }

    /** Drops the publication of a level that is unloading, so its block entities are not kept alive. */
    static void detach(ServerLevel level) {
        SNAPSHOTS.remove(level);
    }

    /** {@return the number of publications since the engine was installed} */
    public static long publicationCount() {
        return PUBLICATION_COUNT.get();
    }

    /** {@return the number of border block entities the last publication recorded} */
    public static long publishedEntries() {
        return PUBLISHED_ENTRIES.get();
    }

    /** {@return the number of containers the last publication copied} */
    public static long copiedContainers() {
        return COPIED_CONTAINERS.get();
    }

    /** {@return the number of item stacks the last publication copied} */
    public static long copiedStacks() {
        return COPIED_STACKS.get();
    }

    /** {@return the wall time of the last publication in nanoseconds} */
    public static long lastPublishNanos() {
        return LAST_PUBLISH_NANOS.get();
    }

    /** {@return the mean wall time of one publication since the engine was installed, in nanoseconds} */
    public static long meanPublishNanos() {
        final long publications = PUBLICATION_COUNT.get();
        return publications == 0L ? 0L : TOTAL_PUBLISH_NANOS.get() / publications;
    }

    /** {@return the number of foreign block-entity reads answered from a publication} */
    public static long publishedAnswers() {
        return PUBLISHED_ANSWERS.get();
    }

    /** {@return the number of analog signals answered from a publication} */
    public static long publishedSignalAnswers() {
        return PUBLISHED_SIGNAL_ANSWERS.get();
    }

    /** {@return the number of block entities the publications skipped because they could not be copied} */
    public static long unpublished() {
        return UNPUBLISHED.get();
    }

    /** {@return the status fragment of this publication, printed on the value snapshot line of the command} */
    public static String statusLine() {
        return "blockEntities[publishes=" + PUBLICATION_COUNT.get()
                + ",entries=" + PUBLISHED_ENTRIES.get()
                + ",containers=" + COPIED_CONTAINERS.get()
                + ",copiedStacks=" + COPIED_STACKS.get()
                + ",lastPublishNanos=" + LAST_PUBLISH_NANOS.get()
                + ",meanPublishNanos=" + meanPublishNanos()
                + ",answers=" + PUBLISHED_ANSWERS.get()
                + ",signalAnswers=" + PUBLISHED_SIGNAL_ANSWERS.get()
                + ",unpublished=" + UNPUBLISHED.get()
                + "]";
    }

    /** Resets every counter and drops every publication, called when an engine is installed and by tests. */
    @ApiStatus.Internal
    public static void reset() {
        SNAPSHOTS.clear();
        PUBLICATION_COUNT.set(0L);
        PUBLISHED_ENTRIES.set(0L);
        COPIED_CONTAINERS.set(0L);
        COPIED_STACKS.set(0L);
        LAST_PUBLISH_NANOS.set(0L);
        TOTAL_PUBLISH_NANOS.set(0L);
        PUBLISHED_ANSWERS.set(0L);
        PUBLISHED_SIGNAL_ANSWERS.set(0L);
        UNPUBLISHED.set(0L);
    }
}
