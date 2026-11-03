/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import org.jetbrains.annotations.ApiStatus;

/**
 * The level-wide mob count per category of one level tick, shared by the spawn bodies of every region.
 * <p>
 * Vanilla decides the mob cap from the level: {@code NaturalSpawner.SpawnState} counts every mob of the level and
 * {@code canSpawnForCategory} compares that count with {@code maxInstancesPerChunk * spawnableChunkCount / 289}.
 * A region cannot count the level, so the server thread counts it once per tick, at the point where vanilla builds
 * its state, and publishes the result here. Every region's state asks this counter instead of its own count, so the
 * level-wide cap decides whether a region may spawn, and a mob that walks into the neighbouring region keeps the
 * slot it holds instead of freeing capacity where it came from.
 * <p>
 * <strong>The count.</strong> {@link #count(ServerLevel, RegionSpawnSource)} applies the predicates of
 * {@code NaturalSpawner.createState}: a persistent mob is skipped, a mob of the {@code MISC} category is skipped,
 * and a mob counts only if the level's full-chunk getter answers for its chunk. It runs on the server thread and
 * reads nothing a region worker may not read.
 * <p>
 * <strong>The slot.</strong> A spawn body checks the cap once per category and then spawns a cluster of mobs, so a
 * plain check followed by an increment would let two regions take the last slot at the same time - vanilla cannot,
 * because its increments land before the next chunk of the level is considered. A passing check therefore reserves
 * the slot ({@link #tryReserve(MobCategory)}) and the body releases the reservation when it ends
 * ({@link #release(MobCategory)}). Every mob the body spawns counts itself in ({@link #recordSpawn(MobCategory)}),
 * so once the body released, the counter holds exactly the mobs that exist plus the reservations of the bodies that
 * still run.
 * <p>
 * The count is not a hard bound: a body that spawns a cluster of {@code k} mobs while holding one reservation can
 * take the level {@code k - 1} mobs past the limit, exactly like the vanilla body, which spawns its cluster after
 * its check as well. The counter reconciles with the level at the next tick, when the server thread counts again.
 */
@ApiStatus.Internal
public final class LevelSpawnCapCounter {
    /**
     * The vanilla {@code NaturalSpawner.MAGIC_NUMBER}: the 17x17 chunks a player keeps spawnable around itself, so
     * a category is capped at {@code maxInstancesPerChunk} mobs per 289 spawnable chunks.
     */
    private static final int MAGIC_NUMBER = 17 * 17;
    /** The number of chunks a player is close enough to for spawning, the area term of the level cap. */
    private final int spawnableChunkCount;
    /** The level-wide count per category, indexed by {@link MobCategory#ordinal()}, including reservations. */
    private final AtomicIntegerArray counts;

    private LevelSpawnCapCounter(int spawnableChunkCount, int[] counts) {
        this.spawnableChunkCount = spawnableChunkCount;
        this.counts = new AtomicIntegerArray(counts);
    }

    /**
     * Counts the mobs of {@code level} per category, exactly like {@code NaturalSpawner.createState} counts them.
     * <p>
     * Runs on the server thread, once per level tick, at the point where vanilla builds its level-wide state.
     *
     * @param source the server-owned reads of that level; its full-chunk getter decides which mobs count and its
     *               natural spawn chunk count is the area term of the cap
     */
    public static LevelSpawnCapCounter count(ServerLevel level, RegionSpawnSource source) {
        final int[] counts = new int[MobCategory.values().length];
        final NaturalSpawner.ChunkGetter chunkGetter = source.chunkGetter();
        for (final Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) {
                continue;
            }
            final MobCategory category = entity.getClassification(true);
            if (category == MobCategory.MISC) {
                continue;
            }
            final BlockPos pos = entity.blockPosition();
            chunkGetter.query(ChunkPos.asLong(pos), chunk -> ++counts[category.ordinal()]);
        }
        return new LevelSpawnCapCounter(source.naturalSpawnChunkCount().getAsInt(), counts);
    }

    /** {@return the number of chunks a player is close enough to for spawning, the area term of the cap} */
    public int spawnableChunkCount() {
        return this.spawnableChunkCount;
    }

    /** {@return the level-wide cap of {@code category}, i.e. the vanilla formula over the level's chunk count} */
    public int limit(MobCategory category) {
        return category.getMaxInstancesPerChunk() * this.spawnableChunkCount / MAGIC_NUMBER;
    }

    /** {@return the level-wide count of {@code category}, including the slots running bodies reserved} */
    public int count(MobCategory category) {
        return this.counts.get(category.ordinal());
    }

    /**
     * {@return whether the slot this check took was reserved for the calling spawn body}
     * <p>
     * The check and the reservation are one atomic step, so two regions cannot both pass a cap that has one slot
     * left. A caller that reserved has to {@link #release(MobCategory)} the slot when its body ends.
     */
    public boolean tryReserve(MobCategory category) {
        final int limit = this.limit(category);
        final int index = category.ordinal();
        while (true) {
            final int current = this.counts.get(index);
            if (current >= limit) {
                return false;
            }
            if (this.counts.compareAndSet(index, current, current + 1)) {
                return true;
            }
        }
    }

    /** Releases the slot a spawn body reserved and did not consume. */
    public void release(MobCategory category) {
        this.counts.decrementAndGet(category.ordinal());
    }

    /** Counts one mob a spawn body admitted. */
    public void recordSpawn(MobCategory category) {
        this.counts.incrementAndGet(category.ordinal());
    }
}
