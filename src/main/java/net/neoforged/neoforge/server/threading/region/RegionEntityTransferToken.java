/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Immutable record of one cross-region entity transfer decision.
 * <p>
 * The token records the entity, its published identity, the lifecycle epoch of that identity, the source
 * region and the destination region at the moment the decision was made. A commit validates the recorded
 * values; it never reads the live entity position again and never derives the destination a second time.
 * That keeps the destination stable when the entity moves after the decision and before the commit.
 * <p>
 * A token describes the region index half of a transfer only. The vanilla section map, the by-id/by-uuid
 * lookup, tracking, network and the entity's own position are owned by the caller on the server thread
 * and are <em>not</em> made atomic by the token. The commit runs on the server thread between existing
 * barrier points and moves the region mapping of the recorded riding graph as one unit.
 * <p>
 * Identity is the recorded {@code (uuid, identityEpoch)} pair, not the {@link Entity} object alone: the
 * reference is only used to resolve the current region mapping and to run the recorded side effects, and
 * every use is validated against the recorded identity first.
 */
@ApiStatus.Internal
public final class RegionEntityTransferToken {
    /**
     * The recorded operation.
     * <p>
     * Only a region move inside one level is part of this transaction. The operation is recorded, so a
     * commit never upgrades a move into a level transfer by looking at the live entity again.
     */
    public enum Operation {
        REGION_MOVE
    }

    /**
     * One member of the recorded riding graph.
     * <p>
     * The member carries the identity fields of the moment it was recorded, so a member whose identity
     * advanced cancels the whole token instead of being transferred partially.
     */
    public record Member(Entity entity, UUID uuid, long identityEpoch) {
        public Member {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    private final long tokenId;
    private final Operation operation;
    private final Entity entity;
    private final UUID uuid;
    private final long identityEpoch;
    private final RegionCoordinate sourceRegion;
    private final RegionCoordinate destinationRegion;
    private final List<Member> ridingGraph;

    RegionEntityTransferToken(
            long tokenId,
            Operation operation,
            Entity entity,
            UUID uuid,
            long identityEpoch,
            RegionCoordinate sourceRegion,
            RegionCoordinate destinationRegion,
            List<Member> ridingGraph) {
        this.tokenId = tokenId;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.identityEpoch = identityEpoch;
        this.sourceRegion = Objects.requireNonNull(sourceRegion, "sourceRegion");
        this.destinationRegion = Objects.requireNonNull(destinationRegion, "destinationRegion");
        this.ridingGraph = List.copyOf(ridingGraph);
    }

    public long tokenId() {
        return this.tokenId;
    }

    public Operation operation() {
        return this.operation;
    }

    public Entity entity() {
        return this.entity;
    }

    public UUID uuid() {
        return this.uuid;
    }

    /** {@return the published lifecycle epoch of {@link #uuid()} observed when the decision was made} */
    public long identityEpoch() {
        return this.identityEpoch;
    }

    public RegionCoordinate sourceRegion() {
        return this.sourceRegion;
    }

    /**
     * {@return the destination region recorded by the decision}
     * <p>
     * A commit uses this value directly and never recomputes it from the entity's live position.
     */
    public RegionCoordinate destinationRegion() {
        return this.destinationRegion;
    }

    /** {@return the level identity whose region index and identity ledger the token belongs to} */
    public ServerLevel level() {
        return this.sourceRegion.level();
    }

    /** {@return the recorded riding graph, topmost vehicle first} */
    public List<Member> ridingGraph() {
        return this.ridingGraph;
    }
}
