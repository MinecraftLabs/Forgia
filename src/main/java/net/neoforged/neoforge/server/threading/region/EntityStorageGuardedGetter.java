/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Iterator;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * {@link LevelEntityGetter} view of the server-owned entity section/index storage that runs the owner
 * check before every operation.
 * <p>
 * The check runs per operation, not only when the view is obtained: a mod that captured the getter on the
 * server thread before a native region worker started still gets rejected when the worker uses it. Every
 * operation is rejected before it reads the section map, the section id tree or the by-id/by-uuid lookup,
 * and before a caller predicate or consumer runs.
 * <p>
 * The check is supplied by the owning {@link PersistentEntitySectionManager}; the generic manager
 * constructor keeps a no-op check, so tests and other users see the vanilla getter unchanged.
 * <p>
 * {@link #getAll()} returns a live view in vanilla, so the returned iterable runs the check in
 * {@link Iterable#iterator()} and in every iterator operation. A view captured on the server thread
 * therefore rejects a native worker before it obtains or advances the live storage iterator, while the
 * generic no-op manager keeps the vanilla live iteration semantics.
 * <p>
 * The wrapper is {@link Iterable}-only: it stays live (it does not buffer or snapshot) but it is not a
 * {@link java.util.Collection}, and the returned iterator does not support {@link Iterator#remove()}.
 * Rejection happens before the delegate iterator is obtained or advanced, so a rejected call does not
 * consume an element.
 */
@ApiStatus.Internal
public final class EntityStorageGuardedGetter<T extends EntityAccess> implements LevelEntityGetter<T> {
    private final LevelEntityGetter<T> delegate;
    private final PersistentEntitySectionManager.EntityStorageAccessCheck accessCheck;

    public EntityStorageGuardedGetter(LevelEntityGetter<T> delegate, PersistentEntitySectionManager.EntityStorageAccessCheck accessCheck) {
        this.delegate = delegate;
        this.accessCheck = accessCheck;
    }

    @Nullable
    @Override
    public T get(int id) {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().get(int)");
        return this.delegate.get(id);
    }

    @Nullable
    @Override
    public T get(UUID uuid) {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().get(UUID)");
        return this.delegate.get(uuid);
    }

    @Override
    public Iterable<T> getAll() {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().getAll");
        final Iterable<T> iterable = this.delegate.getAll();
        return () -> {
            this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().getAll.iterator");
            final Iterator<T> delegateIterator = iterable.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    EntityStorageGuardedGetter.this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().getAll.iterator.hasNext");
                    return delegateIterator.hasNext();
                }

                @Override
                public T next() {
                    EntityStorageGuardedGetter.this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().getAll.iterator.next");
                    return delegateIterator.next();
                }
            };
        };
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> typeTest, AbortableIterationConsumer<U> consumer) {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().get(EntityTypeTest, AbortableIterationConsumer)");
        this.delegate.get(typeTest, consumer);
    }

    @Override
    public void get(AABB boundingBox, Consumer<T> consumer) {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().get(AABB, Consumer)");
        this.delegate.get(boundingBox, consumer);
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> typeTest, AABB boundingBox, AbortableIterationConsumer<U> consumer) {
        this.accessCheck.checkAccess("PersistentEntitySectionManager.getEntityGetter().get(EntityTypeTest, AABB, AbortableIterationConsumer)");
        this.delegate.get(typeTest, boundingBox, consumer);
    }
}
