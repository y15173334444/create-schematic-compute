package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class ProgramComputerMenu extends AbstractContainerMenu {
    public final ProgramComputerBlockEntity blockEntity;
    public final BlockPos blockPos;

    public ProgramComputerMenu(int id, ProgramComputerBlockEntity be) {
        super(SchematicCompute.PROGRAM_MENU.get(), id);
        this.blockEntity = be;
        this.blockPos = be.getBlockPos();
    }
    /** Terminal virtual menu — no local BE available. */
    public ProgramComputerMenu(int id, BlockPos pos) {
        super(SchematicCompute.PROGRAM_MENU.get(), id);
        this.blockEntity = null;
        this.blockPos = pos;
    }
    public ProgramComputerMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.PROGRAM_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        if (inv.player.level().getBlockEntity(blockPos) instanceof ProgramComputerBlockEntity be) this.blockEntity = be;
        else this.blockEntity = null;
    }
    public ProgramComputerMenu(int id, Inventory inv) {
        super(SchematicCompute.PROGRAM_MENU.get(), id);
        this.blockPos = BlockPos.ZERO;
        this.blockEntity = null;
    }
    @Override public @NotNull ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
