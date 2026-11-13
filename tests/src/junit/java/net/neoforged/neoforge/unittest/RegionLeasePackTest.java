/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The lease rules of a packed region task: the canonical acquisition order, the all-or-nothing acquisition with
 * its release of the leases it took, and the exclusivity that no two owners ever hold one region.
 * <p>
 * The region test module cannot declare classes in the package of the regionizer, so the pack, the regionizer
 * and the region state are driven through reflection. The tests assert the invariants the engine's lease loops
 * rely on when they pack the regions of one slice into one worker task.
 */
@Timeout(60)
class RegionLeasePackTest {
    private static Method packSize;
    private static Method canonicalOrder;
    private static Method split;
    private static Method tryAcquireAll;
    private static int maxRegionsPerTask;
    private static Constructor<?> regionizerConstructor;
    private static Method getOrCreate;
    private static Method runningRegionCount;
    private static Method tryMarkRunning;
    private static Method clearRunning;
    private static Method isRunning;
    private static Method isDead;

    private final ServerLevel level = mock(ServerLevel.class);
    private Object regionizer;

    @BeforeAll
    static void locateInternals() throws ReflectiveOperationException {
        final Class<?> packClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionLeasePack");
        packSize = accessible(packClass.getDeclaredMethod("packSize", int.class, int.class));
        canonicalOrder = accessible(packClass.getDeclaredMethod("canonicalOrder", java.util.Collection.class));
        split = accessible(packClass.getDeclaredMethod("split", List.class, int.class));
        tryAcquireAll = accessible(packClass.getDeclaredMethod("tryAcquireAll", List.class, Consumer.class));
        maxRegionsPerTask = (Integer) accessible(packClass.getDeclaredField("MAX_REGIONS_PER_TASK")).get(null);
        final Class<?> regionizerClass = Class.forName("net.neoforged.neoforge.server.threading.region.ThreadedRegionizer");
        regionizerConstructor = accessible(regionizerClass.getDeclaredConstructor(RegionLayout.class));
        getOrCreate = accessible(regionizerClass.getDeclaredMethod("getOrCreate", RegionCoordinate.class));
        runningRegionCount = accessible(regionizerClass.getDeclaredMethod("runningRegionCount"));
        final Class<?> regionClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionState");
        tryMarkRunning = accessible(regionClass.getDeclaredMethod("tryMarkRunning"));
        clearRunning = accessible(regionClass.getDeclaredMethod("clearRunning"));
        isRunning = accessible(regionClass.getDeclaredMethod("isRunning"));
        isDead = accessible(regionClass.getDeclaredMethod("isDead"));
    }

    @BeforeEach
    void createRegionizer() throws ReflectiveOperationException {
        this.regionizer = regionizerConstructor.newInstance(RegionLayout.FIXED_3X3);
    }

    @Test
    void packSizeSpreadsTheRegionsOverTheWorkers() throws Exception {
        assertEquals(1, (Integer) packSize.invoke(null, 1, 4), "a single region is never packed");
        assertEquals(1, (Integer) packSize.invoke(null, 3, 8), "a slice with fewer regions than tasks keeps one task per region");
        assertEquals(1, (Integer) packSize.invoke(null, 8, 4), "eight regions on four workers are eight tasks, not packs");
        assertEquals(2, (Integer) packSize.invoke(null, 16, 4));
        assertEquals(3, (Integer) packSize.invoke(null, 20, 4));
        assertEquals(maxRegionsPerTask, (Integer) packSize.invoke(null, 4096, 4));
        assertEquals(1, (Integer) packSize.invoke(null, 20, 1), "one worker cannot pack more than one task at a time");
    }

    @Test
    void canonicalOrderIsRegionXThenRegionZ() throws Exception {
        final Object oneZero = this.region(1, 0);
        final Object zeroOne = this.region(0, 1);
        final Object zeroZero = this.region(0, 0);

        final List<?> ordered = (List<?>) canonicalOrder.invoke(null, List.of(oneZero, zeroOne, zeroZero));

        assertEquals(List.of(zeroZero, zeroOne, oneZero), ordered);
    }

    @Test
    void packsAreBalancedSoTheSlowestWorkerTicksTheEqualShare() throws Exception {
        final List<Object> regions = new ArrayList<>();
        for (int index = 0; index < 51; ++index) {
            regions.add(this.region(index, 0));
        }

        final List<?> packs = (List<?>) split.invoke(null, regions, 4);

        assertEquals(8, packs.size(), "51 regions on 4 workers must become eight tasks, two per worker");
        assertEquals(List.of(7, 7, 7, 6, 6, 6, 6, 6), packs.stream().map(pack -> ((List<?>) pack).size()).toList(),
                "the packs must differ by at most one region");
    }

    @Test
    void aPackLeasesEveryRegionItWasGiven() throws Exception {
        final Object first = this.region(0, 0);
        final Object second = this.region(1, 0);
        final Object third = this.region(0, 1);

        final List<?> leased = (List<?>) tryAcquireAll.invoke(null, List.of(third, first, second), release());

        assertNotNull(leased);
        assertEquals(List.of(first, third, second), leased);
        for (final Object region : leased) {
            assertTrue(this.isRunning(region));
        }
        assertEquals(3, this.runningRegions());
        for (final Object region : leased) {
            this.release(region);
        }
        assertEquals(0, this.runningRegions());
    }

    @Test
    void aPartialAcquisitionReleasesTheLeasesItTook() throws Exception {
        final Object first = this.region(0, 0);
        final Object second = this.region(1, 0);
        final Object heldByAWorker = this.region(2, 0);
        // The region that sorts last is held by another owner, so the pack takes the first two and then fails.
        assertTrue((Boolean) tryMarkRunning.invoke(heldByAWorker));

        final List<?> leased = (List<?>) tryAcquireAll.invoke(null, List.of(second, heldByAWorker, first), release());

        assertNull(leased);
        assertFalse(this.isRunning(first), "the pack must release the lease it took for the first region");
        assertFalse(this.isRunning(second), "the pack must release the lease it took for the second region");
        assertTrue(this.isRunning(heldByAWorker), "the pack must not release a lease it never took");
        assertEquals(1, this.runningRegions(), "exactly the other owner still holds its region");
        this.release(heldByAWorker);
        assertEquals(0, this.runningRegions());
    }

    @Test
    void twoOwnersCanNeverHoldTheSameRegion() throws Exception {
        final List<Object> pack = List.of(this.region(0, 0), this.region(1, 0), this.region(2, 0), this.region(3, 0));
        final AtomicInteger owners = new AtomicInteger();
        final AtomicInteger maxOwners = new AtomicInteger();
        final AtomicInteger acquisitions = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 2; ++index) {
            final Thread thread = new Thread(() -> {
                awaitUnchecked(start);
                for (int attempt = 0; attempt < 5000; ++attempt) {
                    final List<?> leased = (List<?>) unchecked(() -> tryAcquireAll.invoke(null, pack, release()));
                    if (leased == null) {
                        continue;
                    }
                    maxOwners.accumulateAndGet(owners.incrementAndGet(), Math::max);
                    acquisitions.incrementAndGet();
                    // A held pack is exactly the interval in which a worker ticks its regions, so a second owner
                    // that entered it at the same time would be a region running against itself.
                    Thread.onSpinWait();
                    owners.decrementAndGet();
                    for (final Object region : leased) {
                        this.releaseRegion(region);
                    }
                }
            }, "lease-pack-owner-" + index);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (final Thread thread : threads) {
            thread.join(30_000L);
            assertFalse(thread.isAlive(), "a lease owner did not finish");
        }

        assertTrue(acquisitions.get() > 0, "no thread ever acquired the pack");
        assertEquals(1, maxOwners.get(), "two owners held regions of the same pack at the same time");
        assertEquals(0, this.runningRegions(), "a lease was left behind");
    }

    private Object region(int regionX, int regionZ) throws ReflectiveOperationException {
        final Object region = getOrCreate.invoke(this.regionizer, new RegionCoordinate(this.level, regionX, regionZ));
        assertNotNull(region, "the regionizer must create the region of a fixed coordinate");
        assertFalse((Boolean) isDead.invoke(region));
        return region;
    }

    private int runningRegions() throws ReflectiveOperationException {
        return (Integer) runningRegionCount.invoke(this.regionizer);
    }

    private boolean isRunning(Object region) throws ReflectiveOperationException {
        return (Boolean) isRunning.invoke(region);
    }

    private void release(Object region) throws ReflectiveOperationException {
        clearRunning.invoke(region);
    }

    private void releaseRegion(Object region) {
        try {
            clearRunning.invoke(region);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * {@return the release action the pack calls for every lease it took and has to give back}
     */
    private static Consumer<Object> release() {
        return region -> {
            try {
                clearRunning.invoke(region);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        };
    }

    private static <T extends java.lang.reflect.AccessibleObject> T accessible(T object) {
        object.setAccessible(true);
        return object;
    }

    private static Object unchecked(ReflectiveCall call) {
        try {
            return call.call();
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new IllegalStateException(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object call() throws ReflectiveOperationException;
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the lease race to start");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
