/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Groups tick work by its owning region, preserving the order in which regions were first seen.
 * <p>
 * One region maps to exactly one group, so grouping is what guarantees that a region never ticks
 * against itself: everything that belongs to a region is handled by a single worker task.
 */
@ApiStatus.Internal
public final class RegionTickGrouping<K, T> {
    private final Map<K, List<T>> groups = new LinkedHashMap<>();

    public void add(K region, T item) {
        this.groups.computeIfAbsent(region, key -> new ArrayList<>()).add(item);
    }

    public boolean isEmpty() {
        return this.groups.isEmpty();
    }

    /**
     * {@return the regions in the order they were first seen}
     */
    public Set<K> regions() {
        return Collections.unmodifiableSet(this.groups.keySet());
    }

    /**
     * {@return the items grouped under {@code region}, in insertion order}
     */
    public List<T> items(K region) {
        return Collections.unmodifiableList(this.groups.getOrDefault(region, List.of()));
    }

    public int size(K region) {
        return this.groups.getOrDefault(region, List.of()).size();
    }
}
