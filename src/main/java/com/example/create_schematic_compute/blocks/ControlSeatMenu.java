package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class ControlSeatMenu extends AbstractContainerMenu {
    public final ControlSeatBlockEntity blockEntity;
    public final BlockPos blockPos;

    public ControlSeatMenu(int id, ControlSeatBlockEntity be) {
        super(SchematicCompute.CONTROL_SEAT_MENU.get(), id);
        this.blockEntity = be;
        this.blockPos = be.getBlockPos();
    }

    public ControlSeatMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.CONTROL_SEAT_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        if (inv.player.level().getBlockEntity(blockPos) instanceof ControlSeatBlockEntity be) {
            this.blockEntity = be;
        } else {
            this.blockEntity = null;
        }
    }

    @Override public @NotNull ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
