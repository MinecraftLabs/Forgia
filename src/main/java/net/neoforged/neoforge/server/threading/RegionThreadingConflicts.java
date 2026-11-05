/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.ApiStatus;

/**
 * The mods that are known to fight the region model, and the fail-closed action of each kind of conflict.
 * <p>
 * The table is a static list, not a detection result: it names a mod id, the kind of hazard the mod is known
 * to introduce and why. At server start {@link #plan(boolean, boolean, boolean)} turns the loaded mods into
 * a {@link Plan}, and the plan is what {@code ServerLifecycleHooks} installs - an affected slice stays off, or
 * the engine is not installed at all, and the plan's lines are logged once at that severity. Nothing in the
 * table can widen what the engine does; every action is fail-closed.
 * <p>
 * The ids are the pack inventory's mod ids (the leading token of the mod jar names of the 170-mod
 * compatibility pack recorded in {@code .pi/tasks/modpack-compat-notes.md}); the hazards are the ones that
 * document records. An entry whose id does not match a loaded mod never fires, so a wrong id costs the
 * protection of that one row and never a wrong decision for another mod.
 * <p>
 * What is <em>not</em> detectable and therefore not in the table: whether a mod's mixin actually targets one
 * of the classes whose semantics the engine changes ({@code EntityTickList},
 * {@code PersistentEntitySectionManager}, {@code ChunkMap}, {@code ServerChunkCache}, {@code LevelChunk},
 * {@code ServerLevel}). A mixin configuration names only the mixin classes; the target of each of them lives
 * in the mixin class's own bytecode, so answering the question at runtime means opening every mod jar,
 * recursing into jar-in-jar bundles and parsing class files (that offline scan is what produced the 63/4/3
 * mixin counts of the compatibility note). Mixin exposes no transformer-to-target query either, and this
 * tree has no transformation audit trail to read the answer from. Forgia therefore reports only the known
 * table and says so in {@link #DETECTION_LIMITS}, instead of claiming an unknown-risk detection it does not
 * have.
 */
@ApiStatus.Internal
public final class RegionThreadingConflicts {
    /**
     * The statement of the detection limits, printed by {@code /neoforge regionthreading compat}.
     * <p>
     * It is part of the report because a compat report that only lists what it knows invites the reader to
     * assume it knows everything; the limits are what tells an admin which verdicts are still a human's job.
     */
    public static final String DETECTION_LIMITS = "Region threading compat limits: known conflicts are detected by mod id only; "
            + "whether a loaded mod's mixin targets EntityTickList, PersistentEntitySectionManager, ChunkMap, "
            + "ServerChunkCache, LevelChunk or ServerLevel is not detectable at startup (the target lives in the mixin "
            + "class bytecode, not in the mod metadata or the mixin configuration), so an unreviewed mod is assumed "
            + "unknown and is not refused.";

    /**
     * What the engine loses to a known conflict.
     * <p>
     * The three kinds are the three granularities the region model can be narrowed at, and each one carries the
     * action that is correct for it. A kind is chosen by the reason the mod breaks the model, not by the class
     * it patches: a mod that patches {@code LevelChunk} to add per-chunk state is not the same hazard as a mod
     * that loads chunks on its own thread.
     */
    public enum Kind {
        /**
         * The mod adds its own threads or its own chunk loading to the chunk source, or changes the chunk
         * lifecycle of {@code ChunkMap} and {@code ServerChunkCache}.
         * <p>
         * Action: the engine is not installed. Every worker slice - entities, block entities, scheduled ticks,
         * the chunk tick body and natural spawning - reaches the chunk source of its region from a worker, and
         * the engine's contract is that only the owner of a region loads, generates or mutates that region's
         * chunks while a worker runs. A mod that threads or replaces the chunk source breaks that contract for
         * every slice at once, so no slice is left that could be enabled safely; keeping the whole engine on the
         * server thread is the only fail-closed answer.
         */
        CHUNK_SOURCE(Action.DISABLE_ENGINE),
        /**
         * The mod patches the entity storage or the entity tick list of the level
         * ({@code EntityTickList}, {@code PersistentEntitySectionManager}), which the entity worker slice is
         * what changes the semantics of.
         * <p>
         * Action: the entity tick slice is not enabled. With that slice off the engine keeps main-thread
         * entity storage, so the mod's patch runs against vanilla's single-threaded structures exactly like
         * upstream; the other slices never touch the entity tick list or the section manager, so they are
         * unaffected by the mod and stay enabled.
         */
        ENTITY_STORAGE(Action.DISABLE_ENTITY_TICKS),
        /**
         * The mod reads or writes the level-wide spawn state from its own code (spawn control, per-chunk spawn
         * rules).
         * <p>
         * Action: the natural spawning slice is not enabled. Spawning is the only slice that shares the
         * level-wide spawn state and the mob caps with the server thread, so disabling it keeps vanilla's
         * inline spawning and leaves the mod's rules authoritative; the entity, block-entity and chunk slices
         * do not consult that state and stay enabled.
         */
        LEVEL_WIDE_STATE(Action.DISABLE_SPAWNING);

        private final Action action;

        Kind(Action action) {
            this.action = action;
        }

        /** {@return the fail-closed action of this kind of conflict} */
        public Action action() {
            return this.action;
        }
    }

    /** The fail-closed action of a conflict, in the order of the engine construction. */
    public enum Action {
        /** No worker slice can be trusted, so the engine is not installed and the server ticks vanilla. */
        DISABLE_ENGINE,
        /** The entity tick slice stays on the server thread; the rest of the engine is installed. */
        DISABLE_ENTITY_TICKS,
        /** The natural spawning slice stays on the server thread; the rest of the engine is installed. */
        DISABLE_SPAWNING
    }

    /** One known conflict: the mod, the kind of hazard and why the mod breaks the region model. */
    public record Conflict(String modId, Kind kind, String reason) {
        public Conflict {
            if (modId == null || modId.isBlank()) {
                throw new IllegalArgumentException("modId must not be blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank for " + modId);
            }
        }

        /** {@return the fail-closed action of this conflict} */
        public Action action() {
            return this.kind.action();
        }
    }

    /**
     * The mods that are known to break the region model, each with the kind of hazard and the reason.
     * <p>
     * A row is only added here once the hazard is documented in the compatibility note of the pack; a mod that
     * merely patches one of the engine's classes is not a known conflict but an unknown risk, and stays out of
     * this table (see {@link #DETECTION_LIMITS}).
     */
    private static final List<Conflict> KNOWN_CONFLICTS = List.of(
            new Conflict("c2me", Kind.CHUNK_SOURCE,
                    "threads chunk loading and generation and patches ChunkMap, ServerChunkCache, LevelChunk, ServerLevel and PersistentEntitySectionManager"),
            new Conflict("modernfix", Kind.CHUNK_SOURCE,
                    "patches ChunkMap, ServerChunkCache and LevelChunk"),
            new Conflict("moonlight", Kind.CHUNK_SOURCE,
                    "patches PersistentEntitySectionManager, ChunkMap and ServerChunkCache"),
            new Conflict("sable", Kind.CHUNK_SOURCE,
                    "patches PersistentEntitySectionManager, ChunkMap, ServerChunkCache and LevelChunk"),
            new Conflict("voxy", Kind.CHUNK_SOURCE,
                    "streams chunks for its level of detail from its own threads and patches ChunkMap and ServerChunkCache"),
            new Conflict("byepregen", Kind.CHUNK_SOURCE,
                    "pre-generates chunks and patches ChunkMap and ServerChunkCache"),
            new Conflict("ftbchunks", Kind.CHUNK_SOURCE,
                    "claims and loads chunks itself and patches ChunkMap and ServerLevel"),
            new Conflict("alltheleaks", Kind.CHUNK_SOURCE,
                    "patches EntityTickList, ChunkMap and LevelChunk to drop chunk and entity references"),
            new Conflict("create", Kind.ENTITY_STORAGE,
                    "patches EntityTickList and moves contraptions through the chunk and entity storage"),
            new Conflict("spawn", Kind.LEVEL_WIDE_STATE,
                    "controls natural spawning through ServerChunkCache and its own level-wide spawn state"),
            new Conflict("mobspawncontroller", Kind.LEVEL_WIDE_STATE,
                    "controls per-chunk spawning through LevelChunk"));

    private RegionThreadingConflicts() {}

    /** {@return the known-conflict table, in report order} */
    public static List<Conflict> table() {
        return KNOWN_CONFLICTS;
    }

    /**
     * {@return the table rows whose mod is loaded}
     * <p>
     * Detection is the presence of the mod id in the loaded mod list, which is the only cheap, exact signal a
     * server has about a mod: it reads the list the loader already built and touches no jar.
     */
    public static List<Conflict> detect() {
        return detect(modId -> ModList.get().isLoaded(modId));
    }

    /**
     * {@return the table rows whose mod satisfies {@code modLoaded}}
     *
     * @param modLoaded the loaded-mod question, injectable so a test can simulate a known mod
     */
    public static List<Conflict> detect(Predicate<String> modLoaded) {
        final List<Conflict> detected = new ArrayList<>();
        for (final Conflict conflict : KNOWN_CONFLICTS) {
            if (modLoaded.test(conflict.modId())) {
                detected.add(conflict);
            }
        }
        return detected;
    }

    /**
     * The slice switches of one server start after the compatibility verdict.
     * <p>
     * The plan is what the lifecycle hook installs: {@link #installEngine()} false means the engine is not
     * created at all, and the two slice flags are the requested switches narrowed by every detected conflict
     * of the matching kind. The plan never widens a request, so a server whose configuration asks for a slice
     * that a conflict disables simply does not get that slice. {@code leftOffSlices} is what the verdict took
     * away: a slice the configuration never asked for is not a slice the compatibility layer left off.
     */
    public record Plan(
            List<Conflict> conflicts,
            boolean installEngine,
            boolean entityTickWorkers,
            boolean spawnWorkers,
            List<String> leftOffSlices) {
        public Plan {
            conflicts = List.copyOf(conflicts);
            leftOffSlices = List.copyOf(leftOffSlices);
        }

        /**
         * {@return the lines of the verdict, one per conflict plus one summary, in plan order}
         * <p>
         * The same lines are logged once at server start and printed by
         * {@code /neoforge regionthreading compat}, so an admin who reads the log and one who reads the
         * command see the same statement.
         */
        public List<String> lines() {
            final List<String> lines = new ArrayList<>(this.conflicts.size() + 1);
            for (final Conflict conflict : this.conflicts) {
                lines.add("Region threading conflict: " + conflict.modId() + " (" + conflict.kind() + ": "
                        + conflict.reason() + ") - action: " + actionText(conflict.action()));
            }
            if (!this.installEngine && !this.conflicts.isEmpty()) {
                lines.add("Region threading engine disabled: " + this.conflicts.size()
                        + " known conflict(s) make every worker slice unsafe; the server runs the vanilla "
                        + "server-thread tick");
            } else if (!this.leftOffSlices.isEmpty()) {
                lines.add("Region threading slices left off by compatibility: " + this.leftOffSlices);
            }
            return lines;
        }
    }

    /**
     * {@return the plan of a server start that asks for {@code engineRequested}, {@code entityTickWorkers} and
     * {@code spawnWorkers}}
     * <p>
     * The requested flags are the configuration; the plan is what the compatibility verdict allows of them.
     */
    public static Plan plan(boolean engineRequested, boolean entityTickWorkers, boolean spawnWorkers) {
        return plan(engineRequested, entityTickWorkers, spawnWorkers, modId -> ModList.get().isLoaded(modId));
    }

    /**
     * {@return the plan of a server start whose loaded mods are answered by {@code modLoaded}}
     *
     * @param engineRequested   whether the configuration requests the region threading engine
     * @param entityTickWorkers whether the configuration requests the entity worker slice
     * @param spawnWorkers      whether the configuration requests the natural spawning slice
     * @param modLoaded         the loaded-mod question, injectable so a test can simulate a known mod
     */
    public static Plan plan(boolean engineRequested, boolean entityTickWorkers, boolean spawnWorkers,
            Predicate<String> modLoaded) {
        return plan(engineRequested, entityTickWorkers, spawnWorkers, modLoaded, false);
    }

    /**
     * {@return the plan, ignoring the actions of the known-conflict table when {@code force} is set}
     * <p>
     * The force flag exists for measurement, not for production: it lets the compatibility harness run the
     * engine with a mod the table distrusts and observe what actually happens, so the table can be replaced by
     * evidence instead of guesses. Every conflict is still detected, reported and logged; only the decision to
     * drop a slice is suppressed, and the caller logs that the run is forced.
     */
    public static Plan plan(boolean engineRequested, boolean entityTickWorkers, boolean spawnWorkers,
            Predicate<String> modLoaded, boolean force) {
        final List<Conflict> conflicts = detect(modLoaded);
        boolean installEngine = engineRequested;
        boolean entityTickSlice = entityTickWorkers;
        boolean spawnSlice = spawnWorkers;
        for (final Conflict conflict : force ? List.<Conflict>of() : conflicts) {
            switch (conflict.action()) {
                case DISABLE_ENGINE -> installEngine = false;
                case DISABLE_ENTITY_TICKS -> entityTickSlice = false;
                case DISABLE_SPAWNING -> spawnSlice = false;
            }
        }
        final List<String> leftOff = new ArrayList<>(3);
        if (engineRequested && !installEngine) {
            leftOff.add("engine");
        } else {
            if (entityTickWorkers && !entityTickSlice) {
                leftOff.add("entityTicks");
            }
            if (spawnWorkers && !spawnSlice) {
                leftOff.add("spawning");
            }
        }
        if (!installEngine) {
            // A slice of an engine that is not installed does not run either, and the report must not claim
            // that it does.
            entityTickSlice = false;
            spawnSlice = false;
        }
        return new Plan(conflicts, installEngine, entityTickSlice, spawnSlice, leftOff);
    }

    /** {@return the phrase one action is reported with} */
    public static String actionText(Action action) {
        return switch (action) {
            case DISABLE_ENGINE -> "the region threading engine is not installed";
            case DISABLE_ENTITY_TICKS -> "the entity tick worker slice stays on the server thread";
            case DISABLE_SPAWNING -> "the natural spawning worker slice stays on the server thread";
        };
    }
}
