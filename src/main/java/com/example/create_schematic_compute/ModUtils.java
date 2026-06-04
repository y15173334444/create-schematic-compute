package com.example.create_schematic_compute;

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

    /** ItemStack 的快速哈希（用于 Map 键） */
    public static int hash(ItemStack s) {
        return s.isEmpty() ? 0 : s.getItem().hashCode();
    }

    /** 基于两个频率物品的组合键（避免哈希冲突） */
    public static long freqKey(ItemStack s1, ItemStack s2) {
        int h1 = s1.isEmpty() ? 0 : s1.getItem().hashCode();
        int h2 = s2.isEmpty() ? 0 : s2.getItem().hashCode();
        return ((long)h1 << 32) | (h2 & 0xFFFFFFFFL);
    }

    /** 基于节点参数数组的组合键 */
    public static long freqKey(ItemStack[] itemParams) {
        ItemStack s1 = (itemParams != null && itemParams.length > 0) ? itemParams[0] : ItemStack.EMPTY;
        ItemStack s2 = (itemParams != null && itemParams.length > 1) ? itemParams[1] : ItemStack.EMPTY;
        return freqKey(s1, s2);
    }
}
