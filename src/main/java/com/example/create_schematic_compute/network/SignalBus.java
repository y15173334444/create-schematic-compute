package com.example.create_schematic_compute.network;

import java.util.concurrent.ConcurrentHashMap;

/** 私有信号总线 — 通过字符串名称传输浮点数 */
public class SignalBus {
    private static final ConcurrentHashMap<String, Float> SIGNALS = new ConcurrentHashMap<>();

    public static void put(String channel, float value) {
        SIGNALS.put(channel, value);
    }

    public static float get(String channel) {
        return SIGNALS.getOrDefault(channel, 0f);
    }

    /** 清除所有信号（在重新编译或切换图时调用，防止内存泄漏） */
    public static void clear() {
        SIGNALS.clear();
    }
}
