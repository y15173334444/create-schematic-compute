package com.example.create_schematic_compute;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/** 共享工具方法 */
public class ModUtils {

    /** 获取有效的频率物品：优先槽位#1，回退到槽位#2 */
    public static ItemStack getFreq(ItemStack[] itemParams) {
        if (itemParams == null) return ItemStack.EMPTY;
        if (itemParams.length > 0 && !itemParams[0].isEmpty()) return itemParams[0];
        if (itemParams.length > 1 && !itemParams[1].isEmpty()) return itemParams[1];
        return ItemStack.EMPTY;
    }

    /** 获取物品的频率颜色（基于 DYED_COLOR 组件），无颜色时返回 -1 */
    public static int freqColor(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        var color = stack.get(DataComponents.DYED_COLOR);
        return color != null ? color.rgb() : -1;
    }

    /** ItemStack 的带颜色哈希（用于 Map 键 — 区分不同频段） */
    public static int hash(ItemStack s) {
        if (s.isEmpty()) return 0;
        return s.getItem().hashCode() * 31 ^ freqColor(s);
    }

    /** 基于两个频率物品的组合键 */
    public static long freqKey(ItemStack s1, ItemStack s2) {
        int h1 = hash(s1);
        int h2 = hash(s2);
        return ((long)h1 << 32) | (h2 & 0xFFFFFFFFL);
    }

    /** 基于节点参数数组的组合键 */
    public static long freqKey(ItemStack[] itemParams) {
        ItemStack s1 = (itemParams != null && itemParams.length > 0) ? itemParams[0] : ItemStack.EMPTY;
        ItemStack s2 = (itemParams != null && itemParams.length > 1) ? itemParams[1] : ItemStack.EMPTY;
        return freqKey(s1, s2);
    }
}
