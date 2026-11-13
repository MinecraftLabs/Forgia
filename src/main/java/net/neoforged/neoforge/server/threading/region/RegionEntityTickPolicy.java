/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-type declarations of entity ticks that may only run on the server thread.
 * <p>
 * The default is the worker: a type without a declaration is admitted to the entity slice exactly like
 * before, so an unknown or modded type keeps today's dispatch and adding a type to the engine never silently
 * moves one off a worker. A declaration names the shared server-owned structure that the tick of the type
 * reaches, and it covers the declared class and every subclass of it, so a modded subclass of a declared
 * vanilla type is pinned with it while a modded type of its own is pinned by its own declaration.
 * <p>
 * The reason string of a declaration is what the engine counts the handed-back ticks with and what
 * {@code /neoforge regionthreading status} reports, so one reason token per declaration makes the cause of
 * a server-thread tick readable instead of only its total. This registry is the hook the mod compatibility
 * layer uses: a mod packs its own declaration through
 * {@link RegionThreadingEngine#serverThreadOnlyEntity(Class, String)} instead of being refused a worker
 * wholesale, and a declaration file (see {@code RegionThreadingDeclarations}) packs a registry type through
 * {@link RegionThreadingEngine#serverThreadOnlyEntityType(EntityType, String)}.
 * <p>
 * A class declaration and a type declaration have different scopes on purpose. The class declaration of the
 * API covers the declared class and every subclass of it, because a mod's subclass of a declared vanilla
 * type is written against the same guard sites. A type declaration names one registry type - the only scope a
 * declaration file can name - and covers exactly that type; a modded subtype is a registry type of its own and
 * is pinned by its own declaration.
 * <p>
 * The vanilla POI declarations are gone: the level's {@code PoiManager} is partitioned per region
 * ({@code enableRegionThreadingPoi}), so a villager, iron golem, raider, bee, zombie or fox tick reads
 * region-owned sections and no longer needs to be handed back to the server thread. What remains declared are
 * the types whose own tick reaches a mutation the entity lease does not own - a slime that splits on removal
 * and a fishing hook that discards itself - which
 * {@link RegionThreadingEngine#declareVanillaServerThreadOnlyEntities()} declares for every engine. The portal
 * search is not a type property (every entity can enter a portal) and is deferred to the batch barrier by the
 * engine instead of being declared here.
 */
@ApiStatus.Internal
final class RegionEntityTickPolicy {
    private final Map<Class<? extends Entity>, String> declarations = new ConcurrentHashMap<>();
    /**
     * Declarations by registry type, which is what a declaration file can name.
     * <p>
     * The map is empty in every configuration that declares no type. A tick reads this volatile field once and
     * skips the type check when the map is empty, so a server without a declaration file keeps the class
     * lookup it always had. A declaration replaces the map instead of mutating it, so a tick never observes a
     * half-applied set.
     */
    private volatile Map<EntityType<?>, String> typeDeclarations = Map.of();
    /**
     * Resolved answers per runtime class, tagged with the declaration version they were computed at.
     * <p>
     * The lookup runs once per ticking entity, so the hierarchy walk is memoized. A memo computed before
     * the last declaration is ignored instead of being trusted, so a class that was resolved as undeclared
     * cannot stay undeclared after a declaration that matches it.
     */
    private final Map<Class<?>, Memo> resolved = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> serverThreadOnlyTicks = new ConcurrentHashMap<>();
    private volatile long declarationVersion;

    private record Memo(long declarationVersion, @Nullable String reason) {}

    /**
     * Declares that the tick of {@code type} and of every subclass of it may only run on the server thread.
     * <p>
     * A later declaration of the same class replaces the reason of the earlier one; every declaration
     * invalidates the memoized resolutions, so a class that was already seen undeclared is resolved again.
     *
     * @param type   the declared entity class
     * @param reason the short reason token of the declaration; it is the key of the per reason counter and
     *               the token the status output reports
     */
    void serverThreadOnly(Class<? extends Entity> type, String reason) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        this.declarations.put(type, reason);
        this.declarationVersion++;
    }

    /**
     * Declares that the tick of the registry type {@code type} may only run on the server thread.
     * <p>
     * The declaration covers exactly the registry type it names: an entity of a subtype has its own registry
     * type, so the class-hierarchy coverage of {@link #serverThreadOnly(Class, String)} is not extended by this
     * declaration. A later declaration of the same type replaces the reason of the earlier one.
     *
     * @param type   the declared entity registry type
     * @param reason the short reason token of the declaration; it is the key of the per reason counter and
     *               the token the status output reports
     */
    void serverThreadOnlyType(EntityType<?> type, String reason) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        final Map<EntityType<?>, String> updated = new HashMap<>(this.typeDeclarations);
        updated.put(type, reason);
        this.typeDeclarations = Map.copyOf(updated);
    }

    /**
     * {@return the reason of the declaration that covers {@code entity} or one of its passengers, or
     * {@code null} when no declaration covers the tick of the entity}
     * <p>
     * A passenger is ticked through the vehicle that carries it ({@code Entity#rideTick} runs the whole
     * vanilla tick of the passenger, brain and goals included), so a declared passenger pins the tick of its
     * vehicle as well. The walk is recursive, so a declaration anywhere in the riding stack decides the
     * stack.
     */
    @Nullable
    String reasonFor(Entity entity) {
        final String typeReason = this.typeReasonFor(entity);
        if (typeReason != null) {
            return typeReason;
        }
        final String reason = this.reasonForType(entity.getClass());
        if (reason != null) {
            return reason;
        }
        for (final Entity passenger : entity.getPassengers()) {
            final String passengerReason = this.reasonFor(passenger);
            if (passengerReason != null) {
                return passengerReason;
            }
        }
        return null;
    }

    /**
     * {@return the reason of the declaration of the registry type of {@code entity}}
     * <p>
     * A type declaration cannot be memoized per class, because two registry types can share one class, so the
     * type map is read once per decision. The empty map of a server without a declaration file is the only
     * cost of the check: one volatile read and an empty test.
     */
    @Nullable
    private String typeReasonFor(Entity entity) {
        final Map<EntityType<?>, String> byType = this.typeDeclarations;
        if (byType.isEmpty()) {
            return null;
        }
        final EntityType<?> type = entity.getType();
        return type == null ? null : byType.get(type);
    }

    /**
     * Records one decision of the policy and returns it.
     * <p>
     * Every tick the entity slices take away from a worker is counted with the reason of its declaration, so
     * the counters answer why an entity tick stayed on the server thread and not only how many did.
     *
     * @return the reason of the declaration that covers the tick of {@code entity}, or {@code null} when the
     *         entity is not declared and therefore keeps the worker
     */
    @Nullable
    String recordServerThreadOnly(Entity entity) {
        final String reason = this.reasonFor(entity);
        if (reason != null) {
            this.serverThreadOnlyTicks.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
        }
        return reason;
    }

    /**
     * {@return the number of decisions per reason, ordered by reason}
     * <p>
     * The snapshot is immutable and ordered, so the status output and the diagnostics of two runs are
     * comparable line by line.
     */
    Map<String, Long> serverThreadOnlyTicks() {
        final TreeMap<String, Long> ordered = new TreeMap<>();
        this.serverThreadOnlyTicks.forEach((reason, count) -> ordered.put(reason, count.get()));
        return new LinkedHashMap<>(ordered);
    }

    @Nullable
    private String reasonForType(Class<?> type) {
        final long version = this.declarationVersion;
        final Memo memo = this.resolved.get(type);
        if (memo != null && memo.declarationVersion() == version) {
            return memo.reason();
        }
        final String reason = this.lookupDeclaredReason(type);
        this.resolved.put(type, new Memo(version, reason));
        return reason;
    }

    /**
     * {@return the reason of the nearest declaration in the class hierarchy of {@code type}}
     */
    @Nullable
    private String lookupDeclaredReason(Class<?> type) {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            final String reason = this.declarations.get(candidate);
            if (reason != null || candidate == Entity.class) {
                // Entity is the root of every tickable class, so the walk stops there: a declaration below it
                // is inherited (the runtime class of an entity is a subclass of Entity), and nothing above it
                // can name a tickable entity.
                return reason;
            }
        }
        return null;
    }
}
