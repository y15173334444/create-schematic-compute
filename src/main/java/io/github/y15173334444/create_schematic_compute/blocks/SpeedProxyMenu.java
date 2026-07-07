package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SpeedProxyMenu extends AbstractContainerMenu {
    public final SpeedProxyBlockEntity blockEntity;
    public final BlockPos blockPos;

    public SpeedProxyMenu(int id, SpeedProxyBlockEntity be) {
        super(SchematicCompute.SPEED_PROXY_MENU.get(), id);
        this.blockEntity = be;
        this.blockPos = be.getBlockPos();
    }
    /** Terminal virtual menu. */
    public SpeedProxyMenu(int id, BlockPos pos) {
        super(SchematicCompute.SPEED_PROXY_MENU.get(), id);
        this.blockEntity = null; this.blockPos = pos;
    }
    public SpeedProxyMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        super(SchematicCompute.SPEED_PROXY_MENU.get(), id);
        this.blockPos = buf.readBlockPos();
        if (inv.player.level().getBlockEntity(blockPos) instanceof SpeedProxyBlockEntity be) {
            this.blockEntity = be;
        } else {
            this.blockEntity = null;
        }
    }

    public SpeedProxyMenu(int id, Inventory inv) {
        super(SchematicCompute.SPEED_PROXY_MENU.get(), id);
        this.blockPos = BlockPos.ZERO;
        this.blockEntity = null;
    }

    @Override public @NotNull ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
