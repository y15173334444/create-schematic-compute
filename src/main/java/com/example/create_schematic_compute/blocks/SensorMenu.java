package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SensorMenu extends AbstractContainerMenu {
    public final SensorBlockEntity blockEntity;
    public final BlockPos blockPos;
    public SensorMenu(int id, SensorBlockEntity be) { super(SchematicCompute.SENSOR_MENU.get(), id); this.blockEntity = be; this.blockPos = be.getBlockPos(); }
    /** Terminal virtual menu. */
    public SensorMenu(int id, BlockPos pos) { super(SchematicCompute.SENSOR_MENU.get(), id); this.blockEntity = null; this.blockPos = pos; }
    public SensorMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.SENSOR_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        this.blockEntity = inv.player.level().getBlockEntity(blockPos) instanceof SensorBlockEntity be ? be : null;
    }
    @Override public @NotNull ItemStack quickMoveStack(Player p, int s) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) { return true; }
}
