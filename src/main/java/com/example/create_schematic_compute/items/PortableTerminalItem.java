package com.example.create_schematic_compute.items;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

public class PortableTerminalItem extends Item {
    public PortableTerminalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** Client-side handler — set during client init, no-op on server. */
    public static Consumer<Player> screenOpener = p -> {};

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            screenOpener.accept(player);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
