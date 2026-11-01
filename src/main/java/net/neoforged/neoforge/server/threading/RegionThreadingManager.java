/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Access point for the active regionized threading implementation.
 */
public final class RegionThreadingManager {
    private static final AtomicReference<RegionThreading> current = new AtomicReference<>();

    private RegionThreadingManager() {}

    /**
     * {@return the active region threading implementation, if installed}
     */
    public static Optional<RegionThreading> get() {
        return Optional.ofNullable(current.get());
    }

    /**
     * {@return the active region threading implementation, or {@code null} when none is installed}
     * <p>
     * Unlike {@link #get()} this does not allocate an {@link Optional}; it is used by per-tick hot paths.
     */
    @Nullable
    @ApiStatus.Internal
    public static RegionThreading currentOrNull() {
        return current.get();
    }

    /**
     * {@return true if a region threading implementation is installed}
     */
    public static boolean isInstalled() {
        return current.get() != null;
    }

    /**
     * Runs {@code action} when region threading is installed.
     */
    public static void ifInstalled(Consumer<RegionThreading> action) {
        final RegionThreading threading = current.get();
        if (threading != null) {
            action.accept(threading);
        }
    }

    /**
     * {@return the active region threading implementation}
     *
     * @throws IllegalStateException if region threading is not installed
     */
    public static RegionThreading getOrThrow() {
        final RegionThreading threading = current.get();
        if (threading == null) {
            throw new IllegalStateException("Region threading is not installed");
        }
        return threading;
    }

    /**
     * Installs the active region threading implementation.
     */
    @ApiStatus.Internal
    public static synchronized void install(RegionThreading threading) {
        if (current.get() != null) {
            throw new IllegalStateException("Region threading is already installed; clear it before installing another implementation");
        }
        current.set(threading);
    }

    /**
     * Clears the active region threading implementation during shutdown.
     * <p>
     * The installed engine is always uninstalled, even when shutdown throws. A region worker cannot wait
     * for the shutdown it is currently part of, so its call fails fast before taking the manager monitor;
     * a server-thread clear may already hold that monitor while waiting for the worker, which would
     * otherwise deadlock.
     */
    @ApiStatus.Internal
    public static void clear() {
        final RegionThreading clearedInstance = current.get();
        if (clearedInstance instanceof RegionThreadingEngine engine && engine.isWorkerThread(Thread.currentThread())) {
            try {
                throw new IllegalStateException("Cannot clear region threading from a region worker thread");
            } finally {
                current.compareAndSet(clearedInstance, null);
            }
        }
        synchronized (RegionThreadingManager.class) {
            try {
                if (clearedInstance instanceof RegionThreadingEngine engine) {
                    engine.shutdown();
                }
            } finally {
                // A worker branch or another clear may have uninstalled the captured instance while the
                // shutdown waited; never uninstall a replacement that was installed in the meantime.
                current.compareAndSet(clearedInstance, null);
            }
        }
    }
}
