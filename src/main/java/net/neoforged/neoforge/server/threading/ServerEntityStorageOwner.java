/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Implemented by the {@code LevelCallback} object a {@link ServerLevel} hands to its entity section manager.
 * <p>
 * The manager reaches its level through the callback argument it already receives, so its constructor keeps
 * vanilla's three-argument shape and a mod's mixin on that construction still matches. The level is the owner
 * of the section and index storage and the source of the region layout that storage is partitioned with; the
 * manager reads both through this interface instead of through extra constructor arguments.
 * <p>
 * This needs no access transformer: the implementing class is patched to implement a public interface, and the
 * manager only ever calls it through the interface.
 */
@ApiStatus.Internal
public interface ServerEntityStorageOwner {
    /** {@return the level that owns the entity storage of the manager built with this callback} */
    ServerLevel owningLevel();
}
