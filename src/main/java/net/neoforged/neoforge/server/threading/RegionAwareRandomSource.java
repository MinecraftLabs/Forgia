/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.jetbrains.annotations.ApiStatus;

/**
 * Level random that redirects region tick workers to the random source of their region group.
 * <p>
 * Vanilla {@link RandomSource} implementations are not safe for concurrent use, and entity, block or fluid
 * code may read {@code level.random} directly instead of the random passed to the tick callback. The wrapper
 * therefore resolves the random on every call: a worker inside an entity tick, scheduled tick or random tick
 * group draws from that group's random source, while the server thread and every other thread keep using the
 * wrapped vanilla random. Without an active region group the wrapper is a plain delegate, so behavior
 * outside the batch is unchanged.
 * <p>
 * The engine is resolved through {@link RegionThreadingManager} on every call instead of being captured by
 * the wrapper. A level captures its random when it is created, while an engine can be installed, replaced or
 * cleared afterwards (the diagnostics runs replace the configured engine), so a captured engine would keep
 * the wrapper pointing at worker groups that no longer run. With no engine installed the wrapper delegates
 * to the vanilla random again.
 */
@ApiStatus.Internal
public final class RegionAwareRandomSource implements RandomSource {
    private final RandomSource vanilla;

    public RegionAwareRandomSource(RandomSource vanilla) {
        this.vanilla = vanilla;
    }

    private RandomSource current() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            final RandomSource group = engine.currentGroupRandom();
            if (group != null) {
                return group;
            }
        }
        return this.vanilla;
    }

    @Override
    public RandomSource fork() {
        return this.current().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return this.current().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        this.current().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return this.current().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return this.current().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return this.current().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return this.current().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return this.current().nextFloat();
    }

    @Override
    public double nextDouble() {
        return this.current().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return this.current().nextGaussian();
    }
}
