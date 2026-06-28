package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class RadarMenu extends AbstractContainerMenu {
    public final RadarBlockEntity blockEntity;
    public final BlockPos blockPos;

    public RadarMenu(int id, RadarBlockEntity be) {
        super(SchematicCompute.RADAR_MENU.get(), id);
        this.blockEntity = be;
        this.blockPos = be.getBlockPos();
    }
    /** Terminal virtual menu — no local BE available. */
    public RadarMenu(int id, BlockPos pos) {
        super(SchematicCompute.RADAR_MENU.get(), id);
        this.blockEntity = null;
        this.blockPos = pos;
    }
    public RadarMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.RADAR_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        if (inv.player.level().getBlockEntity(blockPos) instanceof RadarBlockEntity be) this.blockEntity = be;
        else this.blockEntity = null;
    }

    @Override public @NotNull ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
