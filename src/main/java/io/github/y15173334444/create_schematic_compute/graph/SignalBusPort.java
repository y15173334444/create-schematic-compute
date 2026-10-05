package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 信号总线端口（接口下沉）：REDSTONE/BUS/PRIVATE 节点的读写面——graph 不直连
 * {@code network.SignalBus}，由宿主注入实现（生产接 {@code SignalBus.PORT}）。
 * <p>Signal bus port (interface sinking): the read/write surface for REDSTONE/BUS/PRIVATE
 * nodes — the graph package never touches {@code network.SignalBus}; the host injects the
 * implementation (production wires {@code SignalBus.PORT}).</p>
 */
public interface SignalBusPort {

    /** 读频道值（缺席 = 0）。 / read a channel value (absent = 0). */
    float get(String channel);

    /** 写频道值。 / write a channel value. */
    void put(String channel, float value);

    /** 私有频道的音频属性（引脚类型变换的属性源；冲突不算定义）。 / the private channel's
     *  audio property (the source behind pin typing; conflicted nodes define nothing). */
    void setPrivateAudio(String channel, boolean audio);

    /** 频段的音频标志（随频段定义同步给订阅方着色）。 / per-band audio flags (synced with
     *  the band definition for subscriber tinting). */
    void setAudioBands(String busName, Set<String> audioBandNames);

    /** 注册频段定义（频道 BUS_OUT 所有）。 / register a band definition (owned by the
     *  channel's BUS_OUT). */
    void registerBands(String busName, List<String> bands);

    /** 权威频段名列表（null = 未注册；BUS_IN 键查询优先用它）。 / the authoritative band
     *  name list (null = unregistered; BUS_IN key lookup prefers it). */
    List<String> getBands(String busName);

    /** 频道的活动浮点映射（BUS_OUT 写、BUS_IN 读；null = 频道缺席）。
     *  The channel's live float map (BUS_OUT writes, BUS_IN reads; null = absent). */
    Map<String, Float> channelMap(String channel);
}
