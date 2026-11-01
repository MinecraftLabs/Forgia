/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests the package-private worker pool shutdown contract through reflection: {@code close()} must not
 * return while a worker can still mutate region state.
 */
@Timeout(20)
class RegionWorkerPoolTest {
    private static Constructor<?> poolConstructor;
    private static Method execute;
    private static Method close;

    @BeforeAll
    static void locateInternals() throws ReflectiveOperationException {
        final Class<?> poolClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        poolConstructor = poolClass.getDeclaredConstructor(int.class);
        poolConstructor.setAccessible(true);
        execute = poolClass.getDeclaredMethod("execute", String.class, Runnable.class);
        execute.setAccessible(true);
        close = poolClass.getDeclaredMethod("close");
        close.setAccessible(true);
    }

    @Test
    void closeWaitsForARunningWorkerTask() throws Exception {
        final Object pool = poolConstructor.newInstance(1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean();
        assertTrue((boolean) execute.invoke(pool, "blocking", (Runnable) () -> {
            started.countDown();
            await(release);
            finished.set(true);
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                close.invoke(pool);
                closed.set(true);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "pool-close-test");
        closer.start();

        Thread.sleep(200L);
        assertTrue(closer.isAlive(), "close must not return while a worker task is still running");
        assertFalse(closed.get());

        release.countDown();
        closer.join(10_000L);

        assertFalse(closer.isAlive(), "close never returned after the worker finished");
        assertNull(failure.get(), () -> "close failed: " + failure.get());
        assertTrue(finished.get(), "the worker task did not finish before close returned");
        assertTrue(closed.get());
    }

    @Test
    void interruptingTheCloserStillWaitsForANonCooperativeWorker() throws Exception {
        final Object pool = poolConstructor.newInstance(1);
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean released = new AtomicBoolean();
        assertTrue((boolean) execute.invoke(pool, "non-cooperative", (Runnable) () -> {
            started.countDown();
            while (!released.get()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Deliberately ignore the interrupt to model non-cooperative code.
                }
            }
        }));
        assertTrue(started.await(5, TimeUnit.SECONDS));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                close.invoke(pool);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "pool-close-test");
        closer.start();

        Thread.sleep(200L);
        closer.interrupt();
        Thread.sleep(200L);
        assertTrue(closer.isAlive(), "an interrupted close must keep waiting for the worker");

        released.set(true);
        closer.join(10_000L);

        assertFalse(closer.isAlive(), "close never returned after the worker finished");
        assertNull(failure.get(), () -> "close failed: " + failure.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
