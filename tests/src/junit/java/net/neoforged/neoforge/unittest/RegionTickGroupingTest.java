/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionTickGrouping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class RegionTickGroupingTest {
    @Test
    void itemsOfOneRegionFormASingleGroup() {
        final RegionTickGrouping<String, String> grouping = new RegionTickGrouping<>();
        grouping.add("region-a", "entity-1");
        grouping.add("region-b", "entity-2");
        grouping.add("region-a", "entity-3");

        assertEquals(List.of("region-a", "region-b"), new ArrayList<>(grouping.regions()));
        assertEquals(List.of("entity-1", "entity-3"), grouping.items("region-a"));
        assertEquals(List.of("entity-2"), grouping.items("region-b"));
        assertEquals(2, grouping.size("region-a"));
        assertEquals(1, grouping.size("region-b"));
    }

    @Test
    void regionsKeepTheOrderTheyWereFirstSeen() {
        final RegionTickGrouping<Integer, String> grouping = new RegionTickGrouping<>();
        grouping.add(3, "a");
        grouping.add(1, "b");
        grouping.add(2, "c");
        grouping.add(3, "d");

        assertEquals(List.of(3, 1, 2), new ArrayList<>(grouping.regions()));
    }

    @Test
    void oneRegionBecomesOneSerializedWorkerTask() {
        // One region maps to one batch group, so its work can never run against itself.
        final RegionTickGrouping<String, Integer> grouping = new RegionTickGrouping<>();
        grouping.add("region-a", 1);
        grouping.add("region-a", 2);
        grouping.add("region-b", 3);

        final RegionTaskBatch batch = new RegionTaskBatch();
        final AtomicInteger submittedGroups = new AtomicInteger();
        for (final String region : grouping.regions()) {
            batch.add(region, () -> {
                submittedGroups.incrementAndGet();
                assertTrue(grouping.size(region) > 0);
            });
        }

        assertEquals(2, batch.groupCount());
        final RegionTaskBatch.Barrier barrier = batch.submit((description, task) -> {
            task.run();
            return true;
        });

        assertTrue(barrier.isComplete());
        assertEquals(2, submittedGroups.get());
    }
}
