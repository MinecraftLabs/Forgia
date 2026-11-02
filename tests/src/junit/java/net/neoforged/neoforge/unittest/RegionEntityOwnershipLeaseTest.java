/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import org.junit.jupiter.api.Test;

/**
 * Exercises the entity ownership lease decision itself, without a worker batch.
 * <p>
 * These are the decisions a whole entity batch cannot reach: a lease of another level, an entity of an
 * enclosing lease, a second lease opened on the same thread and an out-of-order close. The lease and its
 * group type are package-private by design, so the lease is opened through the same package-private entry
 * point the engine uses; the file location stays in this test package because every test class is its own
 * module and may not export a package the {@code neoforge} module already exports.
 * <p>
 * The end-to-end worker behavior of the same lease is covered by {@link RegionEntityOwnershipTest}.
 */
class RegionEntityOwnershipLeaseTest {
    private static final String REGION_PACKAGE = "net.neoforged.neoforge.server.threading.region.";
    private static final String SET_POS_RAW = "Entity.setPosRaw";

    private final ServerLevel level = mock(ServerLevel.class);
    private final ServerLevel otherLevel = mock(ServerLevel.class);

    @Test
    void leaseGrantsTheFieldMutationsOfAnOwnedEntity() throws ReflectiveOperationException {
        final Entity owned = mock(Entity.class);
        try (RegionEntityOwnershipScope lease = openLease(owned)) {
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.setPosRaw"));
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.move"));
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.moveTo"));
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.absMoveTo"));
        }
    }

    @Test
    void leaseRefusesEverythingOutsideTheGrantableSet() throws ReflectiveOperationException {
        final Entity owned = mock(Entity.class);
        try (RegionEntityOwnershipScope lease = openLease(owned)) {
            // Entity.teleportTo is the cross-level teleport: it changes level, identity and storage.
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.teleportTo"));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.discard"));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.remove"));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "Entity.setRemoved"));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "BlockAttachedEntity.move"));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, owned, "ServerPlayer.teleportTo"));
        }
    }

    @Test
    void leaseOfANonWorkerThreadNeverGrantsAnEntityThatIsAddedToALevel() throws ReflectiveOperationException {
        final Entity added = mock(Entity.class);
        when(added.isAddedToLevel()).thenReturn(true);
        try (RegionEntityOwnershipScope lease = openLease(added)) {
            // The position change of an added entity is only granted because a native region worker defers
            // its section publication and its target chunk acquisition to the batch barrier. This lease is
            // opened on the test thread, which defers nothing, so the storage refusal stands.
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, added, SET_POS_RAW));
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, added, "Entity.move"));
        }
    }

    @Test
    void leaseNeverGrantsARemovedEntity() throws ReflectiveOperationException {
        final Entity removed = mock(Entity.class);
        when(removed.isRemoved()).thenReturn(true);
        try (RegionEntityOwnershipScope lease = openLease(removed)) {
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, removed, SET_POS_RAW));
        }
    }

    @Test
    void leaseOfAnotherLevelNeverGrants() throws ReflectiveOperationException {
        final Entity owned = mock(Entity.class);
        try (RegionEntityOwnershipScope lease = openLease(owned)) {
            assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.otherLevel, owned, SET_POS_RAW));
        }
    }

    @Test
    void nestedLeaseDoesNotInheritTheEnclosingLease() throws ReflectiveOperationException {
        final Entity first = mock(Entity.class);
        final Entity second = mock(Entity.class);
        try (RegionEntityOwnershipScope outer = openLease(first)) {
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW));
            try (RegionEntityOwnershipScope inner = openLease(second)) {
                assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW),
                        "the nested lease must not inherit the membership of the enclosing lease");
                assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, second, SET_POS_RAW));
            }
            assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW),
                    "closing the nested lease must restore the enclosing lease");
        }
        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW),
                "a closed lease must never grant again");
    }

    @Test
    void outOfOrderCloseCannotGrantAgain() throws ReflectiveOperationException {
        final Entity first = mock(Entity.class);
        final Entity second = mock(Entity.class);
        final RegionEntityOwnershipScope outer = openLease(first);
        final RegionEntityOwnershipScope inner = openLease(second);

        outer.close();
        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW),
                "the closed outer lease must not grant through the nested lease");
        assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, second, SET_POS_RAW),
                "an out-of-order close must not unseat the lease that superseded it");

        inner.close();
        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, second, SET_POS_RAW),
                "the restored outer lease was closed and must not grant again");
        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW),
                "an out-of-order close must not revive the closed lease");

        // Closing the restored value once more restores its own predecessor, so the thread local never
        // keeps a dead lease behind.
        outer.close();
        assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, first, SET_POS_RAW));
    }

    /**
     * Opens the lease the engine opens around one worker tick loop, for a synthetic group that owns
     * {@code members}.
     * <p>
     * The group type and the lease opener are package-private by design, so they are reached reflectively.
     * The returned lease is the real {@link RegionEntityOwnershipScope}.
     */
    @SuppressWarnings("unchecked")
    private RegionEntityOwnershipScope openLease(Entity... members) throws ReflectiveOperationException {
        final RegionCoordinate coordinate = new RegionCoordinate(this.level, 0, 0);
        final Class<?> batchClass = Class.forName(REGION_PACKAGE + "RegionEntityTickBatch");
        final Constructor<?> batchConstructor = batchClass.getDeclaredConstructor(ServerLevel.class);
        batchConstructor.setAccessible(true);
        final Object batch = batchConstructor.newInstance(this.level);

        final Class<?> regionClass = Class.forName(REGION_PACKAGE + "RegionState");
        final Constructor<?> regionConstructor = regionClass.getDeclaredConstructor(RegionCoordinate.class);
        regionConstructor.setAccessible(true);
        final Object region = regionConstructor.newInstance(coordinate);

        final Method addGroup = batchClass.getDeclaredMethod("addGroup", regionClass, List.class, Function.class);
        addGroup.setAccessible(true);
        final Object group = addGroup.invoke(batch, region, List.of(members), (Function<Object, Runnable>) ignored -> () -> {});

        final Method open = Class.forName(REGION_PACKAGE + "RegionEntityOwnershipScope")
                .getDeclaredMethod("open", RegionCoordinate.class, addGroup.getReturnType());
        open.setAccessible(true);
        return (RegionEntityOwnershipScope) open.invoke(null, coordinate, group);
    }
}
