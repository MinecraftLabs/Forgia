/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Entity getter of a {@link ServerLevel} that keeps the vanilla section/index storage owned by the
 * server thread.
 * <p>
 * The vanilla storage is a set of single-threaded maps. A native region worker that called
 * {@code getEntities().get(uuid)} or iterated {@code getAll()} would read those maps while the server
 * thread mutated them, and even a completed server-thread handoff still returns live entity references
 * and runs caller predicates/consumers. Production therefore rejects every legacy query issued from a
 * native worker before its predicate, consumer or result escapes.
 * <p>
 * The internal mechanics seam ({@code enableLegacyEntityQueryHandoffForTesting()}) keeps the handoff
 * transport covered by tests: it runs the complete query (including its predicate/consumer and, for
 * {@link #getAll()}, the iteration) on the server thread and returns the finished result. Production
 * never enables that seam.
 * <p>
 * The AABB queries of {@code Level.getEntities(...)} have one additional production path: a native worker
 * that owns a region entity tick lease runs them against the physical bucket of that region through
 * {@link RegionScopedEntityQuery}. That path is entered before this getter, so it does not appear here. A
 * direct call to this getter - including its AABB overloads - stays on the legacy rejection because the
 * by-id/by-uuid/all view is not partitioned.
 * <p>
 * On the server thread and on every non-worker caller the call runs inline and keeps the vanilla
 * semantics.
 */
@ApiStatus.Internal
public final class RegionAwareEntityGetter implements LevelEntityGetter<Entity> {
    private final ServerLevel level;
    private final LevelEntityGetter<Entity> delegate;

    public RegionAwareEntityGetter(ServerLevel level, LevelEntityGetter<Entity> delegate) {
        this.level = level;
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public Entity get(int id) {
        rejectOnNativeWorker("RegionAwareEntityGetter.get(int)");
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> this.delegate.get(id));
        }
        return this.delegate.get(id);
    }

    @Nullable
    @Override
    public Entity get(UUID uuid) {
        rejectOnNativeWorker("RegionAwareEntityGetter.get(UUID)");
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> this.delegate.get(uuid));
        }
        return this.delegate.get(uuid);
    }

    @Override
    public Iterable<Entity> getAll() {
        rejectOnNativeWorker("RegionAwareEntityGetter.getAll");
        if (!RegionThreadingHooks.shouldHandOffEntityQuery()) {
            return this.delegate.getAll();
        }
        final List<Entity> snapshot = new ArrayList<>();
        RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
            for (final Entity entity : this.delegate.getAll()) {
                snapshot.add(entity);
            }
            return null;
        });
        return snapshot;
    }

    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AbortableIterationConsumer<U> consumer) {
        rejectOnNativeWorker("RegionAwareEntityGetter.get(EntityTypeTest, AbortableIterationConsumer)");
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(typeTest, consumer);
                return null;
            });
        } else {
            this.delegate.get(typeTest, consumer);
        }
    }

    @Override
    public void get(AABB boundingBox, Consumer<Entity> consumer) {
        rejectOnNativeWorker("RegionAwareEntityGetter.get(AABB, Consumer)");
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(boundingBox, consumer);
                return null;
            });
        } else {
            this.delegate.get(boundingBox, consumer);
        }
    }

    @Override
    public <U extends Entity> void get(EntityTypeTest<Entity, U> typeTest, AABB boundingBox, AbortableIterationConsumer<U> consumer) {
        rejectOnNativeWorker("RegionAwareEntityGetter.get(EntityTypeTest, AABB, AbortableIterationConsumer)");
        if (RegionThreadingHooks.shouldHandOffEntityQuery()) {
            RegionThreadingHooks.runEntityQueryOnServerThread(this.level, () -> {
                this.delegate.get(typeTest, boundingBox, consumer);
                return null;
            });
        } else {
            this.delegate.get(typeTest, boundingBox, consumer);
        }
    }

    /**
     * Rejects a legacy query issued by a native worker before its predicate, consumer or live result can
     * escape. The mechanics seam that enables the transport is the only way past this check.
     */
    private static void rejectOnNativeWorker(String operation) {
        if (RegionThreadingHooks.shouldRejectLegacyEntityQuery()) {
            RegionThreadingHooks.rejectLegacyEntityQuery(operation);
        }
    }
}
