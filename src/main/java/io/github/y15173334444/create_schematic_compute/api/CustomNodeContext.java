package io.github.y15173334444.create_schematic_compute.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 一次自定义节点求值交付给 {@link CustomNodeBehavior} 的上下文（本模组公开 API 的组成部分）。
 * 值级 API：输入/参数/状态均为值快照，<b>不出现</b>任何图域类型（GraphNode/NodeGraph 等）
 * ——结构性约束：签名只出现 JDK / Minecraft 公共类型与本包的值类型。
 *
 * <p><b>只读纪律</b>：{@link #inputs()} 与 {@link #state()} 返回的是交付副本 ——
 * 修改它们对本模组状态无效；要写状态请经 {@link CustomNodeResult#suspended(CompoundTag)}
 * 或 {@code done*} 的新状态参数。</p>
 *
 * <p><b>提供方直连</b>：{@link #level()} 与 {@link #hostPos()} 让提供方在
 * 求值回调内直接读写自己模组的方块（同 tick 双向，时序归本模组求值循环）。两者在
 * 降级/测试环境可为 {@code null} —— 提供方必须空检并在缺席时自行降级（例如输出 0）。</p>
 * The context handed to a {@link CustomNodeBehavior} for one evaluation (part of this
 * mod's public API). A value-level API — inputs/params/state arrive as value snapshots with zero
 * graph-domain types in sight. Read-only discipline: inputs() and state() deliver copies;
 * writing state goes through the result. Provider-direct exchange: level()
 * and hostPos() let the provider touch its own blocks inside the callback (same-tick
 * bidirectional, timing owned by this mod's loop); both may be null in degraded/test
 * environments — providers must null-check and degrade themselves.
 */
public interface CustomNodeContext {

    /** 输入引脚值快照（FLOAT 域，长度 = schema 输入引脚数；交付副本，只读）。
     *  <p>Input-pin value snapshot (FLOAT domain, length = the schema's input count; a
     *  delivered copy, read-only).</p>
     *  @return 输入引脚值的副本 / a copy of the input-pin values */
    float[] inputs();

    /** 数值参数（schema NUMBER 控件；键缺席 = 0）。/ number param (schema NUMBER control; 0 when absent).
     *  @param key NUMBER 控件的键 / the NUMBER control's key
     *  @return 控件值；键缺席为 0 / the value; 0 when absent */
    float numberParam(String key);

    /** 文本参数（schema TEXT 控件；键缺席 = 空串）。/ text param (schema TEXT control; "" when absent).
     *  @param key TEXT 控件的键 / the TEXT control's key
     *  @return 文本；键缺席为空串 / the text; "" when absent */
    String textParam(String key);

    /** 布尔参数（schema BOOL 控件；键缺席 = false；存储值 &gt; 0.5 视为真）。
     *  <p>bool param (schema BOOL control; false when absent; a stored value &gt; 0.5 reads true).</p>
     *  @param key BOOL 控件的键 / the BOOL control's key
     *  @return 存储值 &gt; 0.5 为真；键缺席为 false / true when the stored value exceeds 0.5; false when absent */
    boolean boolParam(String key);

    /** 物品参数（schema ITEM 控件；键缺席或未设置 = {@code null} —— headless/降级环境
     *  同样返回 null，提供方自行降级）。
     *  <p>item param (schema ITEM control; {@code null} when absent/unset — also null in
     *  headless/degraded environments; providers degrade themselves).</p>
     *  @param key ITEM 控件的键 / the ITEM control's key
     *  @return 物品；键缺席、未设置或降级环境为 {@code null} / the item; {@code null} when absent,
     *          unset or degraded */
    ItemStack itemParam(String key);

    /** 上一次持久化的状态 blob（交付副本，永不为 null 但可能为空；只读）。
     *  <p>The previously persisted state blob (a delivered copy, never null but possibly
     *  empty; read-only).</p>
     *  @return 状态副本（永不为 null）/ a copy of the state (never null) */
    CompoundTag state();

    /**
     * <b>可恢复契约的检查点</b>：本次调用已耗尽本节点的时间片 —— 提供方应在
     * 最近的循环边界收尾，把进度打包进 {@link CustomNodeResult#suspended(CompoundTag)}
     * 返回。真实等待重节点（长解算/巨遍历）必须实现本检查点；忽略它的提供方由
     * 处置梯子事后处置（连续超份额 → 降频 → 熔断；单次调用吃满整池预算 = 立即熔断）。
     * <b>诚实边界</b>：回调在服务端主线程执行，永不返回的单次调用无法被抢占 ——
     * 那属信任模型失效级（与任何模组的坏 tick 处理器同级），非本检查点的防护范围。
     * <p>The resumable contract's checkpoint: this call has exhausted the node's
     * time slice — wrap up at the nearest loop boundary and return
     * {@link CustomNodeResult#suspended(CompoundTag)} with the packed progress. Real
     * heavy work (long solves / huge traversals) must implement this; providers that
     * ignore it are handled post-hoc by the warn → throttle → fuse ladder (a call that
     * consumes the whole pool budget fuses immediately). <b>Honest boundary</b>: the
     * callback runs on the server main thread — a single call that never returns cannot
     * be preempted; that is a trust-model failure (the same class as any mod's broken
     * tick handler), outside this checkpoint's protection.</p>
     * @return 本次调用已超出本节点时间片时为 true / true once this call has exhausted the node's slice
     */
    boolean sliceExpired();

    /** 宿主所在服务端世界（提供方直连自己方块用；降级/测试环境为 {@code null}）。
     *  <p>The host's server level (for provider-direct block exchange; {@code null} in
     *  degraded/test environments).</p>
     *  @return 宿主世界；降级/测试环境为 {@code null} / the host level; {@code null} when degraded or in tests */
    ServerLevel level();

    /** 宿主方块坐标（{@link #level()} 同源；可为 {@code null}）。
     *  <p>The host block position (same source as {@link #level()}; may be {@code null}).</p>
     *  @return 宿主坐标；可为 {@code null} / the host position; may be {@code null} */
    BlockPos hostPos();

    /** 当前服务端游戏刻（宿主注入的求值时戳）。/ the current server game tick (the host's injected eval stamp).
     *  @return 当前游戏刻 / the current game tick */
    long tick();
}
