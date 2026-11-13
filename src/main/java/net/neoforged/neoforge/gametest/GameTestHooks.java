/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.gametest;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.language.ModFileScanData.AnnotationData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;

public class GameTestHooks {
    private static boolean registeredGametests = false;
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Type GAME_TEST_HOLDER = Type.getType(GameTestHolder.class);
    /**
     * System property of the measurement runs: a comma-separated list of GameTest batch names to keep.
     */
    private static final String BATCH_FILTER_PROPERTY = "neoforge.gameTestServer.filter";

    public static boolean isGametestEnabled() {
        return !FMLLoader.isProduction() && (SharedConstants.IS_RUNNING_IN_IDE || isGametestServer() || Boolean.getBoolean("neoforge.enableGameTest"));
    }

    public static boolean isGametestServer() {
        return !FMLLoader.isProduction() && Boolean.getBoolean("neoforge.gameTestServer");
    }

    @SuppressWarnings("deprecation")
    public static void registerGametests() {
        if (!registeredGametests && isGametestEnabled() && !ModLoader.hasErrors()) {
            Set<String> enabledNamespaces = getEnabledNamespaces();
            LOGGER.info("Enabled Gametest Namespaces: {}", enabledNamespaces);

            Set<Method> gameTestMethods = new HashSet<>();
            RegisterGameTestsEvent event = new RegisterGameTestsEvent(gameTestMethods);

            ModLoader.postEvent(event);

            ModList.get().getAllScanData().stream()
                    .map(ModFileScanData::getAnnotations)
                    .flatMap(Collection::stream)
                    .filter(a -> GAME_TEST_HOLDER.equals(a.annotationType()))
                    .forEach(a -> addGameTestMethods(a, gameTestMethods));

            for (Method gameTestMethod : gameTestMethods) {
                GameTestRegistry.register(gameTestMethod, enabledNamespaces);
            }

            filterGameTestBatches();

            registeredGametests = true;
        }
    }

    /**
     * Restricts the registered GameTests to the comma-separated batch names of the
     * {@value #BATCH_FILTER_PROPERTY} system property. The test framework registers all of its dynamic tests
     * through a single generator, so the filter has to run after registration. Measurement runs set the
     * property so a single benchmark batch does not wait for the whole GameTest suite; without it every
     * registered test runs exactly as before.
     */
    private static void filterGameTestBatches() {
        String filter = System.getProperty(BATCH_FILTER_PROPERTY);
        if (filter == null || filter.isBlank()) {
            return;
        }

        Set<String> batches = Arrays.stream(filter.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        int before = GameTestRegistry.getAllTestFunctions().size();
        GameTestRegistry.getAllTestFunctions().removeIf(function -> !batches.contains(function.batchName()));
        LOGGER.info("GameTest batch filter {}: kept {} of {} tests", batches,
                GameTestRegistry.getAllTestFunctions().size(), before);
    }

    /**
     * System property of the soak runs: the world preset the GameTest server generates its level from. A
     * normal GameTest run leaves it unset and keeps the flat preset the vanilla harness was written for; a
     * soak that has to exercise real terrain sets it to {@code minecraft:normal}.
     */
    private static final String WORLD_PRESET_PROPERTY = "neoforge.gameTestWorldPreset";
    /**
     * System property of the soak runs: the seed of the GameTest server's world. A normal GameTest run leaves
     * it unset and keeps the vanilla seed {@code 0}; the soak sets one fixed seed so the threading-on and
     * threading-off runs generate the same terrain.
     */
    private static final String WORLD_SEED_PROPERTY = "neoforge.gameTestWorldSeed";

    /**
     * {@return the world options of the GameTest server's level}
     * <p>
     * The vanilla harness always generates seed {@code 0} without structures or a bonus chest, and the
     * {@link #WORLD_SEED_PROPERTY} of a soak run overrides only the seed. The structures stay off because the
     * gametest level is a controlled fixture: a generated village inside a test grid would make every test
     * that assumes empty ground fail. The soak builds its own content instead.
     */
    public static WorldOptions gameTestWorldOptions() {
        final String seed = System.getProperty(WORLD_SEED_PROPERTY);
        if (seed == null || seed.isBlank()) {
            return new WorldOptions(0L, false, false);
        }
        try {
            return new WorldOptions(Long.parseLong(seed.trim()), false, false);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(WORLD_SEED_PROPERTY + " must be a decimal long, got " + seed, e);
        }
    }

    /**
     * {@return the world preset the GameTest server generates its level from}
     * <p>
     * Unset keeps {@link WorldPresets#FLAT}, so a normal GameTest run generates exactly the level it always
     * did. A soak that needs real terrain, trees, water and caves sets the {@link #WORLD_PRESET_PROPERTY} to a
     * world preset id, for example {@code minecraft:normal}. An unknown id fails at level creation with the
     * preset name instead of silently falling back to the flat level.
     */
    public static ResourceKey<WorldPreset> gameTestWorldPreset() {
        final String preset = System.getProperty(WORLD_PRESET_PROPERTY);
        if (preset == null || preset.isBlank()) {
            return WorldPresets.FLAT;
        }
        return ResourceKey.create(Registries.WORLD_PRESET, ResourceLocation.parse(preset.trim()));
    }

    private static Set<String> getEnabledNamespaces() {
        String enabledNamespacesStr = System.getProperty("neoforge.enabledGameTestNamespaces");
        if (enabledNamespacesStr == null) {
            return Set.of();
        }

        return Arrays.stream(enabledNamespacesStr.split(",")).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    private static void addGameTestMethods(AnnotationData annotationData, Set<Method> gameTestMethods) {
        try {
            Class<?> clazz = Class.forName(annotationData.clazz().getClassName(), true, GameTestHooks.class.getClassLoader());

            gameTestMethods.addAll(Arrays.asList(clazz.getDeclaredMethods()));
        } catch (ClassNotFoundException e) {
            // Should not be possible
            throw new RuntimeException(e);
        }
    }

    public static String getTemplateNamespace(Method method) {
        GameTest gameTest = method.getAnnotation(GameTest.class);

        if (gameTest != null && !gameTest.templateNamespace().isEmpty()) {
            return gameTest.templateNamespace();
        }

        GameTestHolder gameTestHolder = method.getDeclaringClass().getAnnotation(GameTestHolder.class);

        if (gameTestHolder != null) {
            return gameTestHolder.value();
        }

        return "minecraft";
    }

    public static boolean prefixGameTestTemplate(Method method) {
        PrefixGameTestTemplate annotation = method.getAnnotation(PrefixGameTestTemplate.class);

        if (annotation == null) {
            annotation = method.getDeclaringClass().getAnnotation(PrefixGameTestTemplate.class);
        }

        // The vanilla game test system prefixes the classname by default, so we preserve that behavior here.
        return annotation == null || annotation.value();
    }
}
