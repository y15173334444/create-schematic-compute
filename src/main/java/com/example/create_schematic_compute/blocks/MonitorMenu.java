package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class MonitorMenu extends AbstractContainerMenu {
    public final MonitorBlockEntity blockEntity;
    public final BlockPos blockPos;

    public MonitorMenu(int id, MonitorBlockEntity be) {
        super(SchematicCompute.MONITOR_MENU.get(), id);
        this.blockEntity = be;
        this.blockPos = be.getBlockPos();
    }

    /** Terminal virtual menu — no local BE available. */
    public MonitorMenu(int id, BlockPos pos) {
        super(SchematicCompute.MONITOR_MENU.get(), id);
        this.blockEntity = null;
        this.blockPos = pos;
    }

    public MonitorMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.MONITOR_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        if (inv.player.level().getBlockEntity(blockPos) instanceof MonitorBlockEntity be) {
            this.blockEntity = be;
        } else {
            this.blockEntity = null;
        }
    }

    public MonitorMenu(int id, Inventory inv) {
        super(SchematicCompute.MONITOR_MENU.get(), id);
        this.blockPos = BlockPos.ZERO;
        this.blockEntity = null;
    }

    @Override public @NotNull ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
