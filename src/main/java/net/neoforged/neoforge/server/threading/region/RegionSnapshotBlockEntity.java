/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * The read-only answer a foreign block-entity read receives instead of {@code null}: the published values of
 * one border block entity of another region, as {@link RegionBlockEntitySnapshots} recorded them.
 * <p>
 * The instance is the publication itself, not a handle to the live block entity: it is created by the server
 * thread at the head of the level tick, never mutated afterwards, and shared by every reader of that tick. It
 * therefore cannot be used to reach region state, which is the property the live object does not have.
 * <p>
 * <b>Supports</b> the reads a foreign reader needs to take a value:
 * <ul>
 * <li>{@link #getBlockPos()}, {@link #getBlockState()} and {@link #getType()} report the recorded keys;</li>
 * <li>{@link #analogOutputSignal()} is the container signal strength the live block entity had when the tick
 * opened, i.e. the value {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity} answers for it;</li>
 * <li>{@link #contents()} is a copy of the item list of a container, one copied {@link ItemStack} per slot
 * (empty slots are omitted) and no live stack;</li>
 * <li>{@link #getLevel()} reports the level the value was published for, so a caller that dereferences it
 * reaches the normal, region-guarded level API and not {@code null}.</li>
 * </ul>
 * <b>Refuses</b> everything else, so a write cannot reach the owner through it:
 * <ul>
 * <li>{@link #setChanged()}, {@link #setRemoved()}, {@link #clearRemoved()}, {@link #setLevel(Level)} and
 * {@link #setBlockState(BlockState)} throw {@link UnsupportedOperationException};</li>
 * <li>saving throws: {@link #saveAdditional(CompoundTag, HolderLookup.Provider)} (and therefore every
 * {@code save*} method) throws, so a stand-in can never be written to a chunk;</li>
 * <li>it is not a {@link net.minecraft.world.Container} and not an instance of the real block-entity class,
 * so an {@code instanceof} against either is {@code false} and a {@link net.minecraft.world.Container}
 * mutation has nothing to mutate. {@code BlockEntityType.getBlockEntity} refuses it as well, so a typed
 * lookup fails closed instead of handing the stand-in out as the real type;</li>
 * <li>{@link #triggerEvent(int, int)} answers {@code false} without running anything, the same answer the
 * vanilla base class gives a block entity that ignores the event.</li>
 * </ul>
 * The one loader a subclass cannot override is {@code loadWithComponents}, which is final in the vanilla base
 * class; nothing loads NBT into a block entity a caller obtained from a level, so this is the only mutation
 * primitive that reaches a stand-in, and it changes the stand-in's own fields rather than region state.
 */
@ApiStatus.Internal
public final class RegionSnapshotBlockEntity extends BlockEntity {
    private final int analogOutputSignal;
    private final List<ItemStack> contents;

    RegionSnapshotBlockEntity(Level level, BlockEntityType<?> type, BlockPos pos, BlockState state, int analogOutputSignal,
            List<ItemStack> contents) {
        super(type, pos, state);
        // The recorded level, so getLevel() is not null; setLevel is refused below, so the field never moves.
        this.level = level;
        this.analogOutputSignal = analogOutputSignal;
        this.contents = contents;
    }

    /**
     * {@return the container signal strength of the live block entity when the tick opened}
     * <p>
     * This is the value {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity} answers for the live
     * block entity, which is the seam every vanilla container block reads its analog output through.
     */
    public int analogOutputSignal() {
        return this.analogOutputSignal;
    }

    /**
     * {@return a copy of the item list of the recorded container, one copied stack per non-empty slot}
     * <p>
     * The list is immutable and holds copies, so mutating a returned stack changes this publication only and
     * never the contents of the owning region. A block entity that is not a container publishes an empty list.
     */
    public List<ItemStack> contents() {
        return this.contents;
    }

    @Override
    public void setChanged() {
        throw refused("setChanged");
    }

    @Override
    public void setRemoved() {
        throw refused("setRemoved");
    }

    @Override
    public void clearRemoved() {
        throw refused("clearRemoved");
    }

    @Override
    public void setLevel(Level level) {
        throw refused("setLevel");
    }

    @Override
    public void setBlockState(BlockState state) {
        throw refused("setBlockState");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        throw refused("saveAdditional");
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        return false;
    }

    private UnsupportedOperationException refused(String operation) {
        return new UnsupportedOperationException("RegionSnapshotBlockEntity at " + this.getBlockPos().toShortString()
                + " is the published value of another region's " + this.getType() + ": " + operation + " is refused");
    }

    @Override
    public String toString() {
        return "RegionSnapshotBlockEntity[" + this.getType() + ", " + this.getBlockPos().toShortString()
                + ", signal=" + this.analogOutputSignal + ", contents=" + this.contents.size() + "]";
    }
}
