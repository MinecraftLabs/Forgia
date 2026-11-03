/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The server-thread decision of one region's entity tick.
 * <p>
 * A region worker enumerates the entities of the region it leased, but the two vanilla reads that decide
 * whether an entity may be ticked are server-owned and may not run on a worker:
 * {@code DistanceManager#inEntityTickingRange} traverses the distance manager's ticking tracker, which the
 * server thread mutates while it waits at the batch barrier, and the entity-load neighborhood of the chunk
 * guard asks the chunk source. Both are functions of a chunk coordinate, so this activation evaluates them
 * once per chunk of the region's own chunk rectangle instead of once per entity, and hands the result to the
 * worker together with the three vanilla level flags the entity body reads.
 * <p>
 * The rectangle is the union of the region's section coordinates, which is exactly the set of chunks the
 * region's own mapping can name; an entity of the region that stands outside the rectangle is one whose
 * position outran the mapping it was published with (a move inside the tick), so its chunk cannot be decided
 * here and it is ticked on the server thread instead - where its own tick republishes the mapping. The
 * snapshot never force-loads a chunk and never reads entity storage.
 */
@ApiStatus.Internal
final class RegionEntityTickActivation {
    /**
     * Largest chunk rectangle an activation snapshot may cover.
     * <p>
     * A fixed region covers {@code regionChunkSize^2} chunks and a merged dynamic region the union of its
     * sections. A rectangle beyond this bound would make the per-region snapshot more expensive than the
     * per-entity collection it replaces, so such a region is counted and handed to the server thread instead.
     */
    private static final int MAX_CHUNK_RECTANGLE = 4096;
    private final RegionState region;
    private final int originChunkX;
    private final int originChunkZ;
    private final int chunksPerSide;
    /** {@code true} when the chunk is in the vanilla entity ticking range, indexed by {@link #indexOf(ChunkPos)}. */
    private final boolean[] entityTicking;
    /** {@code true} when the chunk's {@link RegionThreadingEngine#entityTickChunkMargin()} neighborhood is loaded. */
    private final boolean[] neighborhoodLoaded;
    private final int eligibleChunkCount;
    private final boolean spawningAnimals;
    private final boolean npcsEnabled;
    private final boolean tickRateNormal;

    private RegionEntityTickActivation(
            RegionState region,
            int originChunkX,
            int originChunkZ,
            int chunksPerSide,
            boolean[] entityTicking,
            boolean[] neighborhoodLoaded,
            int eligibleChunkCount,
            boolean spawningAnimals,
            boolean npcsEnabled,
            boolean tickRateNormal) {
        this.region = region;
        this.originChunkX = originChunkX;
        this.originChunkZ = originChunkZ;
        this.chunksPerSide = chunksPerSide;
        this.entityTicking = entityTicking;
        this.neighborhoodLoaded = neighborhoodLoaded;
        this.eligibleChunkCount = eligibleChunkCount;
        this.spawningAnimals = spawningAnimals;
        this.npcsEnabled = npcsEnabled;
        this.tickRateNormal = tickRateNormal;
    }

    /**
     * Decides one region's chunk eligibility and captures the vanilla level flags of the tick.
     * <p>
     * Runs on the server thread, before the region is leased. The chunk memos are shared by every region of
     * the tick, so the chunk source and the distance manager are asked once per distinct chunk of the whole
     * entity pass instead of once per region.
     *
     * @return the activation, or {@code null} when the region's chunk rectangle cannot be decided here and
     *         the region has to keep its entities on the server thread
     */
    @Nullable
    static RegionEntityTickActivation activate(ServerLevel level, RegionState region, RegionLayout layout, Long2ByteMap chunkLoaded, Long2ByteMap neighborhoodLoaded) {
        int minChunkX = Integer.MAX_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        final int regionChunkSize = layout.regionChunkSize();
        for (final RegionSectionCoordinate cell : region.cells()) {
            if (cell.level() != level) {
                continue;
            }
            final int cellChunkX = cell.sectionX() * regionChunkSize;
            final int cellChunkZ = cell.sectionZ() * regionChunkSize;
            minChunkX = Math.min(minChunkX, cellChunkX);
            minChunkZ = Math.min(minChunkZ, cellChunkZ);
            maxChunkX = Math.max(maxChunkX, cellChunkX + regionChunkSize - 1);
            maxChunkZ = Math.max(maxChunkZ, cellChunkZ + regionChunkSize - 1);
        }
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            // The region owns no chunk-area cell at all, so nothing of it can be decided here.
            return null;
        }
        final int chunksPerSideX = maxChunkX - minChunkX + 1;
        final int chunksPerSideZ = maxChunkZ - minChunkZ + 1;
        if ((long) chunksPerSideX * chunksPerSideZ > MAX_CHUNK_RECTANGLE) {
            return null;
        }
        final int side = Math.max(chunksPerSideX, chunksPerSideZ);
        final int chunkCount = side * side;
        final boolean[] entityTicking = new boolean[chunkCount];
        final boolean[] neighborhood = new boolean[chunkCount];
        int eligible = 0;
        for (int offsetX = 0; offsetX < chunksPerSideX; offsetX++) {
            for (int offsetZ = 0; offsetZ < chunksPerSideZ; offsetZ++) {
                final int index = offsetX * side + offsetZ;
                final int chunkX = minChunkX + offsetX;
                final int chunkZ = minChunkZ + offsetZ;
                entityTicking[index] = level.isInEntityTickingRange(ChunkPos.asLong(chunkX, chunkZ));
                neighborhood[index] = RegionThreadingEngine.isEntityTickNeighborhoodLoaded(level, chunkX, chunkZ, chunkLoaded, neighborhoodLoaded);
                if (entityTicking[index] && neighborhood[index]) {
                    ++eligible;
                }
            }
        }
        final MinecraftServer server = level.getServer();
        return new RegionEntityTickActivation(
                region,
                minChunkX,
                minChunkZ,
                side,
                entityTicking,
                neighborhood,
                eligible,
                server == null || server.isSpawningAnimals(),
                server == null || server.areNpcsEnabled(),
                level.tickRateManager() == null || level.tickRateManager().runsNormally());
    }

    /** {@return the region this activation belongs to} */
    RegionState region() {
        return this.region;
    }

    /** {@return the number of chunks of the region that may tick an entity this tick} */
    int eligibleChunkCount() {
        return this.eligibleChunkCount;
    }

    /**
     * {@return the snapshot index of {@code chunk}, or {@code -1} when it is outside the region rectangle}
     */
    int indexOf(ChunkPos chunk) {
        final int offsetX = chunk.x - this.originChunkX;
        final int offsetZ = chunk.z - this.originChunkZ;
        if (offsetX < 0 || offsetZ < 0 || offsetX >= this.chunksPerSide || offsetZ >= this.chunksPerSide) {
            return -1;
        }
        return offsetX * this.chunksPerSide + offsetZ;
    }

    /** {@return true when the vanilla entity ticking range of the chunk at {@code index} contains it} */
    boolean isEntityTicking(int index) {
        return index >= 0 && index < this.entityTicking.length && this.entityTicking[index];
    }

    /** {@return true when the chunk guard margin around the chunk at {@code index} is loaded} */
    boolean isNeighborhoodLoaded(int index) {
        return index >= 0 && index < this.neighborhoodLoaded.length && this.neighborhoodLoaded[index];
    }

    /**
     * {@return true when the vanilla entity pass would discard {@code entity} before ticking it}
     * <p>
     * The body is the vanilla {@code ServerLevel.shouldDiscardEntity} predicate, evaluated against the two
     * server flags captured by {@link #activate}.
     */
    boolean shouldDiscard(Entity entity) {
        return shouldDiscard(entity, this.spawningAnimals, this.npcsEnabled);
    }

    /**
     * {@return the vanilla {@code ServerLevel.shouldDiscardEntity} predicate for {@code entity}}
     *
     * @param spawningAnimals {@code MinecraftServer.isSpawningAnimals()}
     * @param npcsEnabled     {@code MinecraftServer.areNpcsEnabled()}
     */
    static boolean shouldDiscard(Entity entity, boolean spawningAnimals, boolean npcsEnabled) {
        if (spawningAnimals || !(entity instanceof Animal) && !(entity instanceof WaterAnimal)) {
            return !npcsEnabled && entity instanceof Npc;
        }
        return true;
    }

    /**
     * {@return true when the vanilla entity pass skips {@code entity} while the tick rate is frozen}
     * <p>
     * The body is the vanilla {@code TickRateManager.isEntityFrozen} predicate, evaluated against the
     * {@code runsNormally} flag captured by {@link #activate}; a player and an entity a player rides never
     * freeze.
     */
    boolean isFrozen(Entity entity) {
        return isFrozen(entity, this.tickRateNormal);
    }

    /**
     * {@return the vanilla {@code TickRateManager.isEntityFrozen} predicate for {@code entity}}
     *
     * @param tickRateNormal {@code TickRateManager.runsNormally()}
     */
    static boolean isFrozen(Entity entity, boolean tickRateNormal) {
        return !tickRateNormal && !(entity instanceof Player) && entity.countPlayerPassengers() <= 0;
    }
}
