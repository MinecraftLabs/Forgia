/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Exercises the lazy {@link Ingredient#getItems()} and {@link Ingredient#getStackingIds()} caches while many
 * threads hit a brand-new ingredient at the same time. The barrier makes every thread enter the first access
 * together instead of relying on random timing, and each thread validates the complete result.
 */
@ExtendWith(EphemeralTestServerProvider.class)
class IngredientCacheConcurrencyTest {
    private static final int THREADS = 8;
    private static final int ROUNDS = 32;

    @Test
    @Timeout(120)
    void stackingIdsConcurrentInitialization(MinecraftServer server) throws Exception {
        List<ItemStack> stacks = registeredItemStacks();
        int[] expected = expectedStackingIds(stacks);

        runRounds(THREADS, ROUNDS, round -> {
            Ingredient ingredient = Ingredient.of(stacks.stream());
            return () -> {
                IntList ids = ingredient.getStackingIds();
                Assertions.assertThat(ids.toIntArray())
                        .as("stacking ids observed by %s", Thread.currentThread().getName())
                        .containsExactly(expected);
                Assertions.assertThat(ingredient.getStackingIds().toIntArray())
                        .as("repeated stacking ids observed by %s", Thread.currentThread().getName())
                        .containsExactly(expected);
            };
        });
    }

    @Test
    @Timeout(120)
    void itemStacksConcurrentInitialization(MinecraftServer server) throws Exception {
        List<ItemStack> stacks = registeredItemStacks();
        Set<Item> expected = stacks.stream().map(ItemStack::getItem).collect(Collectors.toSet());

        runRounds(THREADS, ROUNDS, round -> {
            Ingredient ingredient = Ingredient.of(stacks.stream());
            return () -> {
                ItemStack[] items = ingredient.getItems();
                Assertions.assertThat(items)
                        .as("items observed by %s", Thread.currentThread().getName())
                        .extracting(ItemStack::getItem)
                        .containsExactlyInAnyOrderElementsOf(expected);
                Assertions.assertThat(ingredient.getItems())
                        .as("repeated items observed by %s", Thread.currentThread().getName())
                        .extracting(ItemStack::getItem)
                        .containsExactlyInAnyOrderElementsOf(expected);
            };
        });
    }

    @Test
    @Timeout(120)
    void customIngredientCachesAreBuiltOnceUnderConcurrentAccess(MinecraftServer server) throws Exception {
        List<ItemStack> stacks = registeredItemStacks();
        int[] expected = expectedStackingIds(stacks);
        Set<Item> expectedItems = stacks.stream().map(ItemStack::getItem).collect(Collectors.toSet());

        for (int round = 0; round < ROUNDS; round++) {
            CountingCustomIngredient custom = new CountingCustomIngredient(stacks);
            Ingredient ingredient = custom.toVanilla();

            runConcurrently(THREADS, () -> {
                Assertions.assertThat(ingredient.getStackingIds().toIntArray()).containsExactly(expected);
                Assertions.assertThat(ingredient.getItems())
                        .extracting(ItemStack::getItem)
                        .containsExactlyInAnyOrderElementsOf(expectedItems);
            });

            Assertions.assertThat(custom.getItemsCalls())
                    .as("custom ingredient enumeration count in round %s", round)
                    .isEqualTo(1);
        }
    }

    private static List<ItemStack> registeredItemStacks() {
        return BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(stack -> !stack.isEmpty()).toList();
    }

    private static int[] expectedStackingIds(List<ItemStack> stacks) {
        return stacks.stream()
                .mapToInt(StackedContents::getStackingIndex)
                .distinct()
                .sorted()
                .toArray();
    }

    private static void runRounds(int threads, int rounds, IntFunction<Executable> actionFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                Executable action = actionFactory.apply(round);
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Future<?>> futures = new ArrayList<>(threads);
                for (int i = 0; i < threads; i++) {
                    futures.add(executor.submit(() -> {
                        barrier.await();
                        execute(action);
                        return null;
                    }));
                }
                awaitAll(futures);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void runConcurrently(int threads, Executable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    execute(action);
                    return null;
                }));
            }
            awaitAll(futures);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
    }

    private static void execute(Executable action) {
        try {
            action.execute();
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    private static final class CountingCustomIngredient implements ICustomIngredient {
        private final List<ItemStack> stacks;
        private final AtomicInteger itemsCalls = new AtomicInteger();

        private CountingCustomIngredient(List<ItemStack> stacks) {
            this.stacks = List.copyOf(stacks);
        }

        @Override
        public boolean test(ItemStack stack) {
            return stacks.stream().anyMatch(item -> item.is(stack.getItem()));
        }

        @Override
        public Stream<ItemStack> getItems() {
            itemsCalls.incrementAndGet();
            return stacks.stream();
        }

        @Override
        public boolean isSimple() {
            return true;
        }

        @Override
        public IngredientType<?> getType() {
            return NeoForgeMod.COMPOUND_INGREDIENT_TYPE.get();
        }

        private int getItemsCalls() {
            return itemsCalls.get();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CountingCustomIngredient ingredient && stacks.equals(ingredient.stacks);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stacks);
        }
    }
}
