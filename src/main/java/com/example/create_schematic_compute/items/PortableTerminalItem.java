package com.example.create_schematic_compute.items;

import com.example.create_schematic_compute.client.PortableTerminalScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class PortableTerminalItem extends Item {
    public PortableTerminalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            Minecraft.getInstance().setScreen(new PortableTerminalScreen(player));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
