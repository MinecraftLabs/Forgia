/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raider;
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
 * wholesale.
 * <p>
 * The vanilla declarations are the audit of the level's {@code PoiManager}. It is one storage per level, it
 * is partitioned per region only in a later milestone, and nothing in this engine guards it: deliberately
 * reading and writing it from a worker races the server thread and the other workers of the level, and even
 * a read mutates it ({@code SectionStorage#getOrLoad} inserts the loaded column into the section map and may
 * join a column read, and {@code PoiManager#sectionsToVillage} drains a level-wide distance tracker). The
 * audit found the goal and brain paths below plus the portal search; the portal search is not a type
 * property (every entity can enter a portal) and is deferred to the batch barrier by the engine instead of
 * being pinned here. A later milestone that shards the POI storage per region is expected to remove these
 * declarations again; until then they are the difference between a declarative refusal and a data race.
 */
@ApiStatus.Internal
final class RegionEntityTickPolicy {
    /** Reason prefix of the vanilla declarations: the tick of the type reaches the level's {@code PoiManager}. */
    private static final String POI = "poi: ";

    /**
     * One vanilla declaration: the type whose tick reaches the POI storage and the path it reaches it with.
     */
    private record Declaration(Class<? extends Entity> type, String reason) {}

    /**
     * The POI declarations of the audit, keyed one per audited tick path.
     * <p>
     * {@code Villager}: the brain behaviors of {@code VillagerGoalPackages} ({@code AcquirePoi},
     * {@code ValidateNearbyPoi}, {@code PoiCompetitorScan}, {@code YieldJobSite}, {@code GoToPotentialJobSite},
     * {@code AssignProfessionFromJobSite}, {@code SetClosestHomeAsWalkTarget}, {@code GoToClosestVillage},
     * {@code VillageBoundRandomStroll}, {@code PlayTagWithOtherKids}, {@code LocateHidingPlace},
     * {@code VillagerMakeLove}) and {@code NearestBedSensor} read and write it every tick, and
     * {@code Villager#releaseAllPois} does on death. {@code IronGolem}: {@code MoveBackToVillageGoal} and
     * {@code GolemRandomStrollInVillageGoal}. {@code Raider} (and with it the pillager, vindicator, evoker,
     * illusioner, witch and ravager): {@code PathfindToRaidGoal} and
     * {@code Raider.RaiderMoveThroughVillageGoal}. {@code Bee}: {@code Bee.BeeLocateHiveGoal}. {@code Zombie}
     * (and with it the husk, drowned, zombie villager and zombified piglin): {@code MoveThroughVillageGoal},
     * which checks the village distance of the level at night. {@code Fox}: {@code Fox.SeekShelterGoal} and
     * {@code Fox.FoxStrollThroughVillageGoal}.
     */
    private static final List<Declaration> VANILLA_POI_DECLARATIONS = List.of(
            new Declaration(Villager.class, POI + "villager brain and releasePoi on death"),
            new Declaration(IronGolem.class, POI + "IronGolem village goals"),
            new Declaration(Raider.class, POI + "raider village goals"),
            new Declaration(Bee.class, POI + "bee hive search"),
            new Declaration(Zombie.class, POI + "MoveThroughVillageGoal village check"),
            new Declaration(Fox.class, POI + "Fox village goals"));

    private final Map<Class<? extends Entity>, String> declarations = new ConcurrentHashMap<>();
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

    /**
     * Installs the vanilla POI declarations of the audit into {@code policy}.
     */
    static void installVanillaPoiDeclarations(RegionEntityTickPolicy policy) {
        for (final Declaration declaration : VANILLA_POI_DECLARATIONS) {
            policy.serverThreadOnly(declaration.type(), declaration.reason());
        }
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
