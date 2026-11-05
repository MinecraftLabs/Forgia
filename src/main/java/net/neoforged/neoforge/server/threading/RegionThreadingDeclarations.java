/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import net.neoforged.neoforgespi.language.IModInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Loads the declaration files that pin a type to the server thread and applies them to the engine.
 * <p>
 * A declaration file is JSON. Both lists are optional, so a file may declare only entities or only block
 * entities:
 *
 * <pre>{@code
 * {
 *   "entities": [{"type": "minecraft:slime", "reason": "splits on removal"}],
 *   "block_entities": [{"type": "minecraft:hopper", "reason": "modded transfer handler"}]
 * }
 * }</pre>
 *
 * The reason is required and is the token the engine reports: an entity declaration is counted under it by
 * {@code serverThreadOnlyEntityTicksByReason} and printed with it by
 * {@code /neoforge regionthreading compat}. The same file format is read from two places at server start:
 * <ul>
 * <li>{@code config/regionthreading-declarations.json}, which a pack author or a server admin writes and
 * which needs no mod at all;</li>
 * <li>per mod, the resource inside the mod's own jar named by the {@code regionThreadingDeclarations}
 * element of the mod metadata, which is the mechanism {@code FeatureFlagLoader} already uses for
 * {@code featureFlags}. A mod therefore ships its declarations without code and without a config file of
 * its own.</li>
 * </ul>
 * <p>
 * The programmatic registry stays the API: a declaration file only populates
 * {@link RegionThreadingEngine#serverThreadOnlyEntityType(EntityType, String)} and
 * {@link RegionTickCompatibility#declareServerThreadOnlyTypes(Map)}. Both are narrowing declarations - a
 * file can only ever move work back to the server thread, never admit a type to a worker - so a wrong file
 * costs throughput and never safety.
 * <p>
 * An entity is declared by its registry type, not by its class: the file names the type id a pack author
 * reads in the registry, and the declaration covers exactly that type. That is narrower than the class API,
 * whose declaration covers every subclass; a modded subclass of a declared vanilla type has its own registry
 * type and keeps its own (absent) declaration. A block entity is declared by its registry type id, which is
 * the same string {@code TickingBlockEntity.getType()} returns, and a declared type is removed from the
 * region-safe allowlist of {@link RegionTickCompatibility}.
 * <p>
 * A declaration that names an unknown type, and a file that cannot be read or parsed, is reported and
 * ignored: the file of a broken mod cannot keep a server from starting. Every ignored entry names its
 * source, so a typo in a pack file is visible in {@code /neoforge regionthreading compat}.
 */
@ApiStatus.Internal
public final class RegionThreadingDeclarations {
    /** The declaration file a pack author writes into the instance's config directory. */
    public static final String CONFIG_FILE_NAME = "regionthreading-declarations.json";
    /** The mod metadata element naming a declaration file inside the mod's own jar. */
    public static final String MOD_FILE_ELEMENT = "regionThreadingDeclarations";
    /** JSON member of the entity declarations. */
    private static final String ENTITIES = "entities";
    /** JSON member of the block-entity declarations. */
    private static final String BLOCK_ENTITIES = "block_entities";
    /** JSON member naming the registry type of one declaration. */
    private static final String TYPE = "type";
    /** JSON member carrying the reason of one declaration. */
    private static final String REASON = "reason";
    /** Source name of the pack-author file, relative to the instance directory. */
    private static final String CONFIG_SOURCE = "config/" + CONFIG_FILE_NAME;

    private RegionThreadingDeclarations() {}

    /**
     * Resolves the type ids of a declaration file.
     * <p>
     * The registry is injectable so the format and its error handling are testable without a bootstrapped
     * game registry; production uses {@link #REGISTRY}.
     */
    public interface TypeLookup {
        /** The declarations read the game's registries, which every server has bootstrapped. */
        TypeLookup REGISTRY = new TypeLookup() {
            @Override
            public EntityType<?> entityType(ResourceLocation id) {
                // The entity type registry is defaulted, so get(id) answers the default type for an unknown
                // id and a typo would silently pin a pig. containsKey is what tells an unknown id apart.
                return BuiltInRegistries.ENTITY_TYPE.containsKey(id) ? BuiltInRegistries.ENTITY_TYPE.get(id) : null;
            }

            @Override
            public boolean hasBlockEntityType(ResourceLocation id) {
                return BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id);
            }
        };

        /** {@return the entity type registered under {@code id}, or {@code null} when no such type exists} */
        @Nullable
        EntityType<?> entityType(ResourceLocation id);

        /** {@return true when a block-entity type is registered under {@code id}} */
        boolean hasBlockEntityType(ResourceLocation id);
    }

    /** One declaration file to read: the name the report prints and the path behind it. */
    public record Source(String name, Path path) {}

    /** An entity declaration resolved to the registry type it pins to the server thread. */
    public record EntityDeclaration(String type, String reason, String source, EntityType<?> entityType) {}

    /** A block-entity declaration, keyed by the type id the block-entity ticker reports. */
    public record BlockEntityDeclaration(String type, String reason, String source) {}

    /**
     * The declarations of every source, and every entry that was ignored.
     * <p>
     * The report is immutable and keeps the entries in file order, so the output of two runs over the same
     * files is comparable line by line.
     */
    public record Report(
            List<EntityDeclaration> entities,
            List<BlockEntityDeclaration> blockEntities,
            List<String> ignored) {
        public Report {
            entities = List.copyOf(entities);
            blockEntities = List.copyOf(blockEntities);
            ignored = List.copyOf(ignored);
        }

        /** {@return a report that declares nothing, which is the absent-file case} */
        public static Report empty() {
            return new Report(List.of(), List.of(), List.of());
        }

        /** {@return the number of declarations in effect} */
        public int declarationCount() {
            return this.entities.size() + this.blockEntities.size();
        }

        /** {@return true when no declaration is in effect} */
        public boolean isEmpty() {
            return declarationCount() == 0;
        }

        /**
         * Applies the declarations to the programmatic registries.
         * <p>
         * The block-entity declarations are applied whether or not an engine is installed, because the
         * allowlist they narrow is a property of the block-entity slice, not of one engine; the entity
         * declarations need the engine whose entity slice consults them. Both calls replace the previous
         * state of their registry, so applying an empty report clears what an earlier report declared.
         *
         * @param engine the installed engine, or {@code null} when region threading is not installed
         */
        public void applyTo(@Nullable RegionThreadingEngine engine) {
            if (engine != null) {
                for (final EntityDeclaration declaration : this.entities) {
                    engine.serverThreadOnlyEntityType(declaration.entityType(), declaration.reason());
                }
            }
            final Map<String, String> blockEntityReasons = new LinkedHashMap<>();
            for (final BlockEntityDeclaration declaration : this.blockEntities) {
                blockEntityReasons.put(declaration.type(), declaration.reason());
            }
            RegionTickCompatibility.declareServerThreadOnlyTypes(blockEntityReasons);
        }

        /** {@return the report lines of the declarations in effect and of the ignored entries} */
        public List<String> lines() {
            final List<String> lines = new ArrayList<>(declarationCount() + this.ignored.size());
            for (final EntityDeclaration declaration : this.entities) {
                lines.add("Region threading declaration: entity " + declaration.type()
                        + " reason=\"" + declaration.reason() + "\" source=" + declaration.source());
            }
            for (final BlockEntityDeclaration declaration : this.blockEntities) {
                lines.add("Region threading declaration: block entity " + declaration.type()
                        + " reason=\"" + declaration.reason() + "\" source=" + declaration.source());
            }
            for (final String ignored : this.ignored) {
                lines.add("Region threading declaration ignored: " + ignored);
            }
            return lines;
        }
    }

    /**
     * Reads every declaration source of this server and resolves its type ids.
     * <p>
     * Only a source whose file exists is read; the absent pack-author file and a mod that declares no file
     * cost one {@link Files#isRegularFile} check each, so a server with neither loads nothing and declares
     * nothing. Never throws: every failure becomes an ignored entry.
     */
    public static Report load() {
        final Path configFile;
        try {
            configFile = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
        } catch (RuntimeException e) {
            // The config directory is not resolvable in this environment, so no file of this server can be
            // read; a declaration is optional by definition and must never keep a server from starting.
            return new Report(List.of(), List.of(), List.of(
                    "declaration sources: the config directory is not available (" + e.getClass().getSimpleName() + ")"));
        }
        try {
            return loadSources(configFile);
        } catch (RuntimeException e) {
            return new Report(List.of(), List.of(), List.of(
                    "declaration sources: the files cannot be read (" + e.getClass().getSimpleName() + ")"));
        }
    }

    /**
     * {@return the report of every declaration source of this server}
     *
     * @param configFile the pack-author file to read when it exists
     * @see #load()
     */
    private static Report loadSources(Path configFile) {
        final List<Source> sources = new ArrayList<>();
        final List<String> ignored = new ArrayList<>();
        if (Files.isRegularFile(configFile)) {
            sources.add(new Source(CONFIG_SOURCE, configFile));
        }
        for (final IModInfo mod : ModList.get().getMods()) {
            final String declared = mod.getConfig().<String>getConfigElement(MOD_FILE_ELEMENT).orElse(null);
            if (declared == null) {
                continue;
            }
            final String source = mod.getModId() + ":" + declared;
            final Path path = mod.getOwningFile().getFile().findResource(declared);
            if (path == null || !Files.isRegularFile(path)) {
                ignored.add(source + ": the declared file does not exist in the mod file");
                continue;
            }
            sources.add(new Source(source, path));
        }
        final Report parsed = parse(sources, TypeLookup.REGISTRY);
        if (ignored.isEmpty()) {
            return parsed;
        }
        final List<String> allIgnored = new ArrayList<>(ignored);
        allIgnored.addAll(parsed.ignored());
        return new Report(parsed.entities(), parsed.blockEntities(), allIgnored);
    }

    /**
     * Reads {@code sources} and resolves their types with {@code lookup}.
     *
     * @param sources the files to read, in report order
     * @param lookup  the type lookup the entry ids are resolved against
     * @return the declarations in effect and every ignored entry, in file order
     */
    public static Report parse(List<Source> sources, TypeLookup lookup) {
        final List<EntityDeclaration> entities = new ArrayList<>();
        final List<BlockEntityDeclaration> blockEntities = new ArrayList<>();
        final List<String> ignored = new ArrayList<>();
        for (final Source source : sources) {
            final JsonObject root = readRoot(source, ignored);
            if (root == null) {
                continue;
            }
            readEntries(source, root, ENTITIES, ignored, entry -> {
                final EntityDeclaration declaration = entityDeclaration(source, entry, ignored, lookup);
                if (declaration != null) {
                    entities.add(declaration);
                }
            });
            readEntries(source, root, BLOCK_ENTITIES, ignored, entry -> {
                final BlockEntityDeclaration declaration = blockEntityDeclaration(source, entry, ignored, lookup);
                if (declaration != null) {
                    blockEntities.add(declaration);
                }
            });
        }
        return new Report(entities, blockEntities, ignored);
    }

    /**
     * {@return the JSON object of {@code source}, or {@code null} when the file cannot be read or is not an
     * object}
     */
    @Nullable
    private static JsonObject readRoot(Source source, List<String> ignored) {
        final String text;
        try {
            text = Files.readString(source.path());
        } catch (IOException | RuntimeException e) {
            ignored.add(source.name() + ": the file cannot be read (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        final JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            ignored.add(source.name() + ": the file is not valid JSON (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (root == null || !root.isJsonObject()) {
            ignored.add(source.name() + ": the file is not a JSON object");
            return null;
        }
        return root.getAsJsonObject();
    }

    /**
     * Runs {@code entryReader} over the array {@code member} of {@code root}.
     * <p>
     * An absent member means "nothing declared"; a member that is present but not an array makes the file
     * untrustworthy for that kind only, so it is reported instead of aborting the other kind of the same
     * file.
     */
    private static void readEntries(Source source, JsonObject root, String member, List<String> ignored,
            Consumer<JsonObject> entryReader) {
        final JsonElement element = root.get(member);
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (!element.isJsonArray()) {
            ignored.add(source.name() + ": \"" + member + "\" is not an array");
            return;
        }
        final JsonArray array = element.getAsJsonArray();
        for (final JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                ignored.add(source.name() + ": an entry of \"" + member + "\" is not an object");
                continue;
            }
            entryReader.accept(entry.getAsJsonObject());
        }
    }

    @Nullable
    private static EntityDeclaration entityDeclaration(Source source, JsonObject entry, List<String> ignored,
            TypeLookup lookup) {
        final String type = typeId(source.name(), entry, ignored);
        final String reason = reason(source.name(), entry, ignored);
        if (type == null || reason == null) {
            return null;
        }
        final ResourceLocation id = parseId(source.name(), type, ignored);
        if (id == null) {
            return null;
        }
        final EntityType<?> entityType = lookup.entityType(id);
        if (entityType == null) {
            ignored.add(source.name() + ": unknown entity type " + type);
            return null;
        }
        return new EntityDeclaration(type, reason, source.name(), entityType);
    }

    @Nullable
    private static BlockEntityDeclaration blockEntityDeclaration(Source source, JsonObject entry, List<String> ignored,
            TypeLookup lookup) {
        final String type = typeId(source.name(), entry, ignored);
        final String reason = reason(source.name(), entry, ignored);
        if (type == null || reason == null) {
            return null;
        }
        final ResourceLocation id = parseId(source.name(), type, ignored);
        if (id == null) {
            return null;
        }
        if (!lookup.hasBlockEntityType(id)) {
            ignored.add(source.name() + ": unknown block entity type " + type);
            return null;
        }
        return new BlockEntityDeclaration(type, reason, source.name());
    }

    @Nullable
    private static String typeId(String source, JsonObject entry, List<String> ignored) {
        final String type = string(entry, TYPE);
        if (type == null) {
            ignored.add(source + ": a declaration has no \"" + TYPE + "\" string");
        }
        return type;
    }

    @Nullable
    private static String reason(String source, JsonObject entry, List<String> ignored) {
        final String reason = string(entry, REASON);
        if (reason == null) {
            // The reason is what the counters and the report key a declaration by; without one the
            // declaration could not be attributed, so it is refused instead of being given a generated token.
            ignored.add(source + ": a declaration has no \"" + REASON + "\" string");
        }
        return reason;
    }

    @Nullable
    private static String string(JsonObject entry, String member) {
        final JsonElement element = entry.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        final String value = element.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    @Nullable
    private static ResourceLocation parseId(String source, String type, List<String> ignored) {
        try {
            return ResourceLocation.parse(type);
        } catch (RuntimeException e) {
            ignored.add(source + ": \"" + type + "\" is not a resource location");
            return null;
        }
    }
}
