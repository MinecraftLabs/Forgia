/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.junit.jupiter.api.Test;

/**
 * Verifies the conservative block-entity compatibility policy that decides which vanilla block entities
 * may leave the server thread.
 */
class RegionTickCompatibilityTest {
    @Test
    void brewingStandStaysOnTheServerThreadBecauseItCanSpawnARemainderEntity() {
        // BrewingStandBlockEntity.doBrew calls Containers.dropItemStack when the ingredient has a crafting
        // remainder and is not consumed entirely. Spawning an item entity is not a region-local write, so
        // the brewing stand is absent from the allowlist at every position, including an interior one.
        assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType("minecraft:brewing_stand"));
        assertFalse(RegionTickCompatibility.requiresInteriorRegion("minecraft:brewing_stand"));
    }

    @Test
    void furnaceFamilyIsRegionSafeOnlyAwayFromARegionBoundary() {
        for (final String type : new String[] { "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" }) {
            assertTrue(RegionTickCompatibility.isRegionSafeBlockEntityType(type), type);
            assertTrue(RegionTickCompatibility.requiresInteriorRegion(type), type);
        }
    }

    @Test
    void entityStorageConsumersStayOnTheServerThread() {
        // Every one of these ticks queries entity storage and then mutates or consumes the returned
        // entities, or spawns/removes entities. A main-thread query handoff would not make the consumer
        // safe, so they must not be declared region-safe.
        for (final String type : new String[] {
                "minecraft:beacon",
                "minecraft:brewing_stand",
                "minecraft:conduit",
                "minecraft:bell",
                "minecraft:shulker_box",
                "minecraft:enchanting_table",
                "minecraft:vault",
                "minecraft:trial_spawner",
                "minecraft:dispenser",
                "minecraft:dropper",
                "minecraft:crafter",
                "minecraft:spawner",
                "minecraft:campfire",
                "minecraft:moving_piston"
        }) {
            assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType(type), type);
        }
    }

    @Test
    void hopperIsRegionSafeOnlyAwayFromARegionBoundary() {
        // The hopper is the one declared type that reaches entity storage: its item query is a region-scoped
        // query of the leased region, its containers are one block away, and the item entity it absorbs is
        // removed through the ownership lease of its group, which defers the level callback to the barrier.
        // It still starts neighbor updates, so it needs the same interior position as the furnace family.
        assertTrue(RegionTickCompatibility.isRegionSafeBlockEntityType("minecraft:hopper"));
        assertTrue(RegionTickCompatibility.requiresInteriorRegion("minecraft:hopper"));
    }

    @Test
    void unknownAndModdedTypesDefaultToTheServerThread() {
        assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType(null));
        assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType("mymod:furnace"));
        assertFalse(RegionTickCompatibility.isRegionSafeBlockEntityType("minecraft:not_a_block_entity"));
        assertFalse(RegionTickCompatibility.requiresInteriorRegion(null));
    }
}
