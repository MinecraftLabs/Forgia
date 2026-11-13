/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Registry of the region-partitioned POI storages of the running levels.
 * <p>
 * The storage of a level is created by the level's {@code PoiManager} when the POI switch is on and the
 * installed engine uses a fixed region layout, and it is removed when the manager closes. The engine publishes
 * the dirty regions of a level at the end of its region phase through this registry, so the POI storage does
 * not need a reference from the engine and a level without the switch costs one map lookup per region phase.
 */
@ApiStatus.Internal
public final class RegionPoiStorages {
    private static final Map<ServerLevel, RegionPoiStorage> STORAGES = new ConcurrentHashMap<>();

    private RegionPoiStorages() {}

    /**
     * {@return true when a level created now gets a region-partitioned POI storage}
     * <p>
     * The switch is off by default. It requires an installed native engine with a fixed region layout, because
     * the storage maps a chunk to exactly one region coordinate and a fixed layout is the one where that
     * mapping never merges or splits. With the switch off, or with a dynamic layout, the vanilla
     * {@code PoiManager} storage is used unchanged.
     */
    public static boolean isEnabled() {
        if (!RegionThreadingConfig.poiStorageEnabled()) {
            return false;
        }
        return RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine && engine.regionLayout().isFixed();
    }

    /**
     * Creates and registers the regionized storage of {@code level}.
     *
     * @return the storage, or {@code null} when the switch is off
     */
    @Nullable
    public static RegionPoiStorage attach(ServerLevel level, SimpleRegionStorage storage, RegistryAccess registryAccess,
            LevelHeightAccessor heightAccessor) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) || !isEnabled()) {
            return null;
        }
        final RegionPoiStorage created = new RegionPoiStorage(level, engine.regionLayout(), storage, registryAccess, heightAccessor);
        STORAGES.put(level, created);
        return created;
    }

    /** {@return the regionized storage of {@code level}, or {@code null} when it has none} */
    @Nullable
    public static RegionPoiStorage of(ServerLevel level) {
        return STORAGES.get(level);
    }

    /** Publishes the dirty regions of {@code level}, called on the server thread after a region phase. */
    static void publishLevel(ServerLevel level) {
        final RegionPoiStorage storage = STORAGES.get(level);
        if (storage != null) {
            storage.publishLevel();
        }
    }

    /** Removes the storage of a level whose manager closed. */
    static void detach(RegionPoiStorage storage) {
        STORAGES.remove(storage.level(), storage);
    }

    /** {@return the process-wide POI storage counters, as the diagnostics report them} */
    @ApiStatus.Internal
    public static RegionThreadingDiagnostics.PoiCounters counters() {
        return RegionPoiCounters.snapshot();
    }

    /** Resets the process-wide POI storage counters, for a test that asserts them from a known point. */
    @ApiStatus.Internal
    public static void resetCountersForTesting() {
        RegionPoiCounters.reset();
    }
}
