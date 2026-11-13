package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/**
 * 信号总线端口（接口下沉）：REDSTONE/BUS/PRIVATE 节点的读写面——graph 不直连
 * {@code network.SignalBus}，由宿主注入实现（生产接 {@code SignalBus.PORT}）。
 * <p>Signal bus port (interface sinking): the read/write surface for REDSTONE/BUS/PRIVATE
 * nodes — the graph package never touches {@code network.SignalBus}; the host injects the
 * implementation (production wires {@code SignalBus.PORT}).</p>
 */
public interface SignalBusPort {

    /** 读频道浮点值（缺席 = 0）。 / read a channel float value (absent = 0). */
    float get(String channel);

    /** 写频道浮点值。 / write a channel float value. */
    void put(String channel, float value);

    /** 私有频道的声明载荷类型（引脚类型变换的属性源；冲突不算定义）。
     *  The private channel's declared payload type (the source behind pin typing;
     *  conflicted nodes define nothing). */
    void setPrivateType(String channel, PinType type);

    /** 逐频段声明载荷类型（随频段定义同步给订阅方收敛/着色）。
     *  Per-band declared payload types (synced with the band definition for subscriber
     *  convergence and tinting). */
    void setBandTypes(String busName, Map<String, PinType> bandTypes);

    /** 注册频段定义（频道 BUS_OUT 所有）。 / register a band definition (owned by the
     *  channel's BUS_OUT). */
    void registerBands(String busName, List<String> bands);

    /** 权威频段名列表（null = 未注册；BUS_IN 键查询优先用它）。 / the authoritative band
     *  name list (null = unregistered; BUS_IN key lookup prefers it). */
    List<String> getBands(String busName);

    /** 频道的活动浮点映射（BUS_OUT 写、BUS_IN 读；null = 频道缺席）。
     *  The channel's live float map (BUS_OUT writes, BUS_IN reads; null = absent). */
    Map<String, Float> channelMap(String channel);

    /** 通道身份的规范化键（{@code SignalBus.key()} 前缀形态：{@code <维度id>\u0001<逻辑名>}）。
     *  <p><b>单一源纪律（issue #26 承接清单第 4 项）</b>：步骤 4 的通道 epoch 记账与步骤 3 的
     *  通道普查使用同一通道身份，键规范化必须复用 {@code SignalBus.key} 这一个入口 ——
     *  前缀格式绝不复刻。graph 包不直连 {@code network.SignalBus}，故经本门面方法把
     *  规范化「透过既有端口缝」透传出来：实现落在 network 侧（{@code SignalBus.PORT} 在
     *  SignalBus 自己的包内调用包内可见的 {@code key}），{@code key} 的可见性与格式源头
     *  一概不动，公开面只在既有端口接口上多一个方法。无作用域线程（headless 测试 / 客户端
     *  本地视图）返回原样逻辑名 —— 与 {@code SignalBus.key} 的无作用域口径逐位一致。</p>
     *  <p>The normalized channel-identity key (the {@code SignalBus.key()} prefixed form:
     *  {@code <dimensionId>\u0001<logical>}). <b>Single-source discipline (handover item 4 of
     *  issue #26)</b>: step 4's channel epoch accounting and step 3's channel census share one
     *  channel identity, and key normalization must reuse the single {@code SignalBus.key}
     *  entry — the prefix format is never replicated. The graph package never touches
     *  {@code network.SignalBus} directly, so the normalization is passed through the existing
     *  port seam by this facade method: the implementation lives on the network side
     *  ({@code SignalBus.PORT} calls the package-private {@code key} inside SignalBus's own
     *  package), the visibility and the format source of {@code key} stay untouched, and the
     *  public face grows by one method on the existing port interface only. An unscoped thread
     *  (headless tests / the client-local view) gets the logical name unchanged — bit-identical
     *  to {@code SignalBus.key}'s unscoped behaviour.</p> */
    String channelKey(String channel);

    /** 写向量值（状态语义 last-write-wins）：键 = 私有名或频段键 {@code channel\0band}。
     *  Write a vector value (state semantics, last write wins): key = private name or the
     *  band key {@code channel\0band}. */
    void putVec3(String key, Vec3f value);

    /** 读向量值（缺席 = null）。 / read a vector value (absent = null). */
    Vec3f getVec3(String key);
}
