/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Publishes one immutable lifecycle record per entity identity for one server level.
 * <p>
 * One instance belongs to one {@code PersistentEntitySectionManager}, i.e. to one server level. The
 * existing global maps stay server-owned: {@code knownUuids}, the visible by-uuid/by-id lookup, the
 * ticking list, the chunk entity map and the section membership are still written only by the server
 * thread under the existing owner check. This ledger adds a publication seam on top of them, not a
 * second source of truth and not a second identity ledger that would make the vanilla maps readable by
 * workers.
 * <p>
 * <b>Contract.</b> Every mutation of this ledger is rejected for a native region worker before it
 * changes state, so all publications linearize on the server thread. Each publication is one store of
 * an immutable {@link EntityIdentity}, sequenced after the vanilla maps that back the published state
 * have been written. A reader therefore sees either the state before a transition or the state after
 * it, never a mixed one. The ledger does not claim that the several vanilla writes of one admission are
 * atomic: it claims that no published record describes a partially applied one.
 * <p>
 * {@link #current(UUID)}, {@link #current(int)} and {@link #isLive(UUID)} are the worker-readable
 * surface. They are safe to call from any thread and are the only entity identity source a future
 * region worker may consult: a worker must not read {@code knownUuids}, the visible lookup, the ticking
 * list, the chunk entity map or the entity sections. An identity record is not an atomic snapshot of
 * those maps; {@link EntityIdentity#epoch()} is the lifecycle epoch a caller compares against to detect
 * that the identity advanced, and it is strictly increasing per UUID inside this level.
 */
@ApiStatus.Internal
public final class EntityIdentityLedger {
    /** Departure records are informational; only the most recent ones are kept so the ledger stays bounded. */
    private static final int MAX_REMEMBERED_DEPARTURES = 1024;

    private final ConcurrentHashMap<UUID, EntityIdentity> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, EntityIdentity> liveById = new ConcurrentHashMap<>();
    private final Map<UUID, EntityIdentity> recentDepartures = Collections.synchronizedMap(new BoundedDepartureCache());
    private final AtomicLong epoch = new AtomicLong();

    private volatile boolean sealed;

    /**
     * Identities another level handed to this ledger, by UUID, waiting for their admission here.
     * <p>
     * A dimension change removes the entity from the old level and admits it into the new one, so without this
     * record the new level would publish a {@link EntityIdentity.State#RESERVED} reservation for an identity
     * that already existed: a reader of the new level would observe the identity as not yet admitted, and the
     * identity would be released by the old level and reserved again by the new one instead of following the
     * entity. The record is written on the server thread when the travel is announced and consumed by the
     * admission of the same UUID and int id. A travel that is cancelled leaves its entry behind; the entry is
     * dropped when the admission does not match it and is bounded so a cancelled travel cannot grow it.
     */
    private final Map<UUID, EntityIdentity> expectedTransfers = Collections.synchronizedMap(new BoundedTransferCache());

    /** Server-thread only diagnostics; never read by the publication path. */
    private long duplicateUuidAdmissions;
    private long duplicateIdAdmissions;
    private long rejectedTransitions;
    private long unknownTransitions;
    private long rejectedRebinds;
    private long continuedTransfers;
    private long unusedTransfers;

    /**
     * Reserves the identity of {@code entity} before the vanilla manager writes any of its maps.
     * <p>
     * The reservation publishes {@link EntityIdentity.State#RESERVED}: a reader may observe the identity
     * before the vanilla maps contain it and has to retry. The UUID and int id are both claimed here, so
     * a duplicate is rejected before the vanilla UUID set or the visible by-id lookup is silently
     * overwritten. On {@link Admission#ADMITTED} the caller must perform the vanilla writes and then
     * publish {@link #publishAdmitted(Entity)}, or publish {@link #abandon(Entity)} when the vanilla UUID
     * registration still refuses the entity.
     * <p>
     * An identity this ledger was handed by another level - see {@link #expectTransfer(EntityIdentity)} - is
     * not reserved: it is published as {@link EntityIdentity.State#ADMITTED} directly, so the entry follows the
     * entity across the dimension change and no reader of this level ever observes a reservation of an identity
     * that already existed.
     */
    public Admission admit(Entity entity) {
        checkServerAccess("EntityIdentityLedger.admit");
        if (this.sealed) {
            return Admission.SHUTDOWN;
        }
        final UUID uuid = entity.getUUID();
        final int id = entity.getId();
        final EntityIdentity transferred = this.claimTransfer(uuid, id);
        final EntityIdentity identity = new EntityIdentity(uuid, id,
                transferred == null ? EntityIdentity.State.RESERVED : EntityIdentity.State.ADMITTED, this.nextEpoch());
        if (this.live.putIfAbsent(uuid, identity) != null) {
            this.duplicateUuidAdmissions++;
            return Admission.DUPLICATE_UUID;
        }
        if (this.liveById.putIfAbsent(id, identity) != null) {
            this.live.remove(uuid, identity);
            this.duplicateIdAdmissions++;
            return Admission.DUPLICATE_ID;
        }
        this.recentDepartures.remove(uuid);
        return Admission.ADMITTED;
    }

    /**
     * {@return the live identity of {@code entity}, to be handed to the ledger of the level it travels to}
     * <p>
     * This reads the publication and changes nothing: a dimension change that is cancelled must leave the source
     * level's identity untouched, so the source does not publish a departure here. The removal of the entity from
     * the source level publishes that departure as it always did.
     *
     * @return the live identity, or {@code null} when this level holds none for the entity
     */
    @Nullable
    public EntityIdentity liveIdentity(Entity entity) {
        checkServerAccess("EntityIdentityLedger.liveIdentity");
        return this.live.get(entity.getUUID());
    }

    /**
     * Records that the identity of {@code transferred} is travelling to this ledger.
     * <p>
     * The next admission of the same UUID and int id continues the identity instead of reserving it: see
     * {@link #admit(Entity)}. The record is bounded, and an admission that does not match it leaves it to be
     * evicted rather than adopting an identity the caller did not hand over.
     */
    public void expectTransfer(EntityIdentity transferred) {
        checkServerAccess("EntityIdentityLedger.expectTransfer");
        this.expectedTransfers.put(transferred.uuid(), transferred);
    }

    /**
     * {@return the identity {@code uuid} was handed with, when it matches the int id of the admission}
     * <p>
     * The entry is consumed, so one handover continues exactly one admission. A mismatch is counted and dropped:
     * the identity was re-ided between the handover and the admission, so the admission reserves normally.
     */
    @Nullable
    private EntityIdentity claimTransfer(UUID uuid, int id) {
        final EntityIdentity transferred = this.expectedTransfers.remove(uuid);
        if (transferred == null) {
            return null;
        }
        if (transferred.entityId() != id) {
            this.unusedTransfers++;
            return null;
        }
        this.continuedTransfers++;
        return transferred;
    }

    /**
     * Cancels a reservation whose vanilla admission did not happen, and remembers it as departed.
     * <p>
     * An identity that was continued from another level is published as admitted by the reservation itself, so
     * the cancellation covers that state as well: the caller reaches here only when the vanilla UUID registration
     * refused the entity, in which case the identity is not in the level either way.
     */
    public void abandon(Entity entity) {
        checkServerAccess("EntityIdentityLedger.abandon");
        final UUID uuid = entity.getUUID();
        final EntityIdentity reservation = this.live.get(uuid);
        if (reservation == null || (reservation.state() != EntityIdentity.State.RESERVED && reservation.state() != EntityIdentity.State.ADMITTED)) {
            return;
        }
        this.retire(reservation, new EntityIdentity(uuid, reservation.entityId(), EntityIdentity.State.GONE, this.nextEpoch()));
    }

    /** Publishes that the vanilla UUID set and the section membership contain {@code entity}. */
    public void publishAdmitted(Entity entity) {
        this.transition(entity, EntityIdentity.State.ADMITTED, "EntityIdentityLedger.publishAdmitted");
    }

    /** Publishes that the visible by-uuid/by-id lookup contains {@code entity}. */
    public void publishTracked(Entity entity) {
        this.transition(entity, EntityIdentity.State.TRACKED, "EntityIdentityLedger.publishTracked");
    }

    /**
     * Publishes that the visible lookup no longer contains {@code entity} while it stays admitted.
     * <p>
     * A departure keeps its own state: a tracking removal that runs inside a removal must not reopen the
     * identity as admitted.
     */
    public void publishUntracked(Entity entity) {
        checkServerAccess("EntityIdentityLedger.publishUntracked");
        final EntityIdentity current = this.live.get(entity.getUUID());
        if (current == null || current.state() != EntityIdentity.State.TRACKED) {
            return;
        }
        this.transition(entity, EntityIdentity.State.ADMITTED, "EntityIdentityLedger.publishUntracked");
    }

    /** Publishes the start of the removal of {@code entity}; the identity stops being an admission target. */
    public void publishDeparting(Entity entity) {
        this.transition(entity, EntityIdentity.State.DEPARTING, "EntityIdentityLedger.publishDeparting");
    }

    /** Publishes the completed removal of {@code entity}. */
    public void publishGone(Entity entity) {
        this.transition(entity, EntityIdentity.State.GONE, "EntityIdentityLedger.publishGone");
    }

    /**
     * Rebinds a live identity to a new int id.
     * <p>
     * This is the server-side re-id seam, used by player respawn. It is a no-op when the ledger holds no
     * live identity for the entity's UUID or when the caller's previous id is already stale. The target
     * id is only adopted when no other live identity owns it, so a rebind can never make two identities
     * claim one int id.
     */
    public void rebindId(Entity entity, int previousId, int newId) {
        checkServerAccess("EntityIdentityLedger.rebindId");
        if (previousId == newId) {
            return;
        }
        final UUID uuid = entity.getUUID();
        final EntityIdentity current = this.live.get(uuid);
        if (current == null || current.entityId() != previousId) {
            return;
        }
        final EntityIdentity next = new EntityIdentity(uuid, newId, current.state(), this.nextEpoch());
        final EntityIdentity occupant = this.liveById.putIfAbsent(newId, next);
        if (occupant != null && !occupant.uuid().equals(uuid)) {
            this.rejectedRebinds++;
            return;
        }
        final EntityIdentity previousBinding = this.liveById.get(previousId);
        if (previousBinding != null && previousBinding.uuid().equals(uuid)) {
            this.liveById.remove(previousId, previousBinding);
        }
        this.liveById.put(newId, next);
        this.live.put(uuid, next);
    }

    /**
     * Rebinds a live identity to a new UUID.
     * <p>
     * This is the server-side UUID change seam. It is a no-op when the ledger holds no live identity for
     * {@code previousUuid}, and it refuses a UUID that another live identity already owns, so a UUID
     * change can never merge two identities.
     */
    public void rebindUuid(Entity entity, UUID previousUuid, UUID newUuid) {
        checkServerAccess("EntityIdentityLedger.rebindUuid");
        if (previousUuid.equals(newUuid)) {
            return;
        }
        final EntityIdentity current = this.live.get(previousUuid);
        if (current == null) {
            return;
        }
        if (this.live.containsKey(newUuid)) {
            this.rejectedRebinds++;
            return;
        }
        final EntityIdentity next = new EntityIdentity(newUuid, current.entityId(), current.state(), this.nextEpoch());
        this.live.put(newUuid, next);
        this.live.remove(previousUuid, current);
        this.liveById.put(next.entityId(), next);
        this.recentDepartures.remove(newUuid);
    }

    /**
     * Seals the ledger when the level closes and publishes every remaining identity as gone.
     * <p>
     * Sealing is the close transition of the level: a load completion that arrives after the level
     * closed is rejected instead of admitting an entity into a closed manager.
     */
    public void seal() {
        checkServerAccess("EntityIdentityLedger.seal");
        if (this.sealed) {
            return;
        }
        this.sealed = true;
        for (final EntityIdentity identity : this.live.values()) {
            this.retire(identity, new EntityIdentity(identity.uuid(), identity.entityId(), EntityIdentity.State.GONE, this.nextEpoch()));
        }
        this.live.clear();
        this.liveById.clear();
    }

    /**
     * {@return the newest published identity for {@code uuid}, or {@code null}}
     * <p>
     * The answer covers a live identity and the bounded set of remembered departures. A departed identity
     * is published as {@link EntityIdentity.State#GONE}; a UUID that was never published, or whose
     * departure record was already evicted, answers {@code null}. Safe to call from any thread.
     */
    @Nullable
    public EntityIdentity current(UUID uuid) {
        final EntityIdentity identity = this.live.get(uuid);
        return identity != null ? identity : this.recentDepartures.get(uuid);
    }

    /**
     * {@return the live identity that owns {@code id}, or {@code null}}
     * <p>
     * This answers only identities that are still present in the level. Departed identities are not
     * remembered by int id because int ids are not persisted and a new admission may reuse one. Safe to
     * call from any thread.
     */
    @Nullable
    public EntityIdentity current(int id) {
        return this.liveById.get(id);
    }

    /** {@return true while a live identity is published for {@code uuid}} Safe to call from any thread. */
    public boolean isLive(UUID uuid) {
        return this.live.containsKey(uuid);
    }

    /** {@return true once the level close sealed this ledger} */
    public boolean isSealed() {
        return this.sealed;
    }

    /** {@return the number of live published identities} */
    public int liveCount() {
        return this.live.size();
    }

    /** {@return the number of remembered departure records} */
    public int rememberedDepartureCount() {
        return this.recentDepartures.size();
    }

    /** {@return the last published lifecycle epoch of this level} */
    public long publishedEpoch() {
        return this.epoch.get();
    }

    /** {@return the number of rejected duplicate UUID admissions} */
    public long duplicateUuidAdmissions() {
        return this.duplicateUuidAdmissions;
    }

    /** {@return the number of rejected duplicate int id admissions} */
    public long duplicateIdAdmissions() {
        return this.duplicateIdAdmissions;
    }

    /** {@return the number of publications that the state machine refused} */
    public long rejectedTransitions() {
        return this.rejectedTransitions;
    }

    /** {@return the number of publications for an identity this ledger never admitted} */
    public long unknownTransitions() {
        return this.unknownTransitions;
    }

    /** {@return the number of rejected id or UUID rebinds} */
    public long rejectedRebinds() {
        return this.rejectedRebinds;
    }

    /**
     * {@return the number of admissions that continued an identity another level handed over}
     * <p>
     * A continued admission publishes {@link EntityIdentity.State#ADMITTED} directly instead of reserving the
     * identity again, so this counts the dimension changes whose identity followed the entity.
     */
    public long continuedTransfers() {
        return this.continuedTransfers;
    }

    /**
     * {@return the number of handovers whose admission did not match the handed identity}
     * <p>
     * The identity was re-ided or the travel was cancelled between the handover and the admission, so the
     * admission reserved normally instead of adopting a stale record.
     */
    public long unusedTransfers() {
        return this.unusedTransfers;
    }

    private void transition(Entity entity, EntityIdentity.State target, String operation) {
        checkServerAccess(operation);
        final UUID uuid = entity.getUUID();
        final EntityIdentity current = this.live.get(uuid);
        if (current == null) {
            this.unknownTransitions++;
            return;
        }
        if (current.state() == target) {
            return;
        }
        if (!current.state().canPublish(target)) {
            this.rejectedTransitions++;
            return;
        }
        final EntityIdentity next = new EntityIdentity(uuid, entity.getId(), target, this.nextEpoch());
        if (target == EntityIdentity.State.GONE) {
            this.retire(current, next);
        } else {
            this.live.put(uuid, next);
            this.liveById.remove(current.entityId(), current);
            this.liveById.put(next.entityId(), next);
        }
    }

    private void retire(EntityIdentity current, EntityIdentity gone) {
        this.live.remove(current.uuid(), current);
        this.liveById.remove(current.entityId(), current);
        this.recentDepartures.put(gone.uuid(), gone);
    }

    private long nextEpoch() {
        return this.epoch.incrementAndGet();
    }

    private static void checkServerAccess(String operation) {
        RegionThreadingHooks.assertServerEntityStorageAccess(operation);
    }

    private static final class BoundedDepartureCache extends LinkedHashMap<UUID, EntityIdentity> {
        BoundedDepartureCache() {
            super(MAX_REMEMBERED_DEPARTURES, 0.75F, false);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, EntityIdentity> eldest) {
            return size() > MAX_REMEMBERED_DEPARTURES;
        }
    }

    /**
     * The handovers of other levels that this ledger has not consumed yet.
     * <p>
     * Bounded like the departure cache: a travel that is announced and then cancelled never reaches the admission
     * that would consume its entry, so an unbounded map would grow with cancelled travels.
     */
    private static final class BoundedTransferCache extends LinkedHashMap<UUID, EntityIdentity> {
        BoundedTransferCache() {
            super(MAX_REMEMBERED_DEPARTURES, 0.75F, false);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, EntityIdentity> eldest) {
            return size() > MAX_REMEMBERED_DEPARTURES;
        }
    }

    /** Result of one {@link #admit(Entity)} attempt. The vanilla entry point keeps its synchronous boolean result. */
    @ApiStatus.Internal
    public enum Admission {
        /** The identity is reserved; the caller performs the vanilla writes and then publishes them. */
        ADMITTED,
        /** Another live identity already owns the entity UUID. */
        DUPLICATE_UUID,
        /** Another live identity already owns the int entity id. */
        DUPLICATE_ID,
        /** The level close sealed the ledger. */
        SHUTDOWN
    }
}
