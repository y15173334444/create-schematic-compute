package com.example.create_schematic_compute.network;

import java.util.HashMap;
import java.util.Map;

/** 私有信号总线 — 通过字符串名称传输浮点数 */
public class SignalBus {
    private static final Map<String, Float> SIGNALS = new HashMap<>();

    public static void put(String channel, float value) {
        SIGNALS.put(channel, value);
    }

    public static float get(String channel) {
        return SIGNALS.getOrDefault(channel, 0f);
    }

    public static void clear() { SIGNALS.clear(); }
}
