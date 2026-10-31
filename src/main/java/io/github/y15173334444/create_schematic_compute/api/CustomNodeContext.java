package io.github.y15173334444.create_schematic_compute.api;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * 一次自定义节点求值交付给 {@link CustomNodeBehavior} 的上下文（公开 API 波 1）。
 * 值级 API：输入/参数/状态均为值快照，<b>不出现</b>任何图域类型（GraphNode/NodeGraph 等，
 * 探索稿 §3-10 / §6.1 的结构性要求）。
 *
 * <p><b>只读纪律</b>：{@link #inputs()} 与 {@link #state()} 返回的是交付副本 ——
 * 修改它们对本模组状态无效；要写状态请经 {@link CustomNodeResult#suspended(CompoundTag)}
 * 或 {@code done*} 的新状态参数。</p>
 *
 * <p><b>提供方直连（D6 拍板）</b>：{@link #level()} 与 {@link #hostPos()} 让提供方在
 * 求值回调内直接读写自己模组的方块（同 tick 双向，时序归本模组求值循环）。两者在
 * 降级/测试环境可为 {@code null} —— 提供方必须空检并在缺席时自行降级（例如输出 0）。</p>
 * The context handed to a {@link CustomNodeBehavior} for one evaluation (public-API
 * wave 1). A value-level API — inputs/params/state arrive as value snapshots with zero
 * graph-domain types in sight. Read-only discipline: inputs() and state() deliver copies;
 * writing state goes through the result. Provider-direct exchange (ruling D6): level()
 * and hostPos() let the provider touch its own blocks inside the callback (same-tick
 * bidirectional, timing owned by this mod's loop); both may be null in degraded/test
 * environments — providers must null-check and degrade themselves.
 */
public interface CustomNodeContext {

    /** 输入引脚值快照（FLOAT 域，长度 = schema 输入引脚数；交付副本，只读）。
     *  <p>Input-pin value snapshot (FLOAT domain, length = the schema's input count; a
     *  delivered copy, read-only).</p> */
    float[] inputs();

    /** 数值参数（schema NUMBER 控件；键缺席 = 0）。/ number param (schema NUMBER control; 0 when absent). */
    float numberParam(String key);

    /** 文本参数（schema TEXT 控件；键缺席 = 空串）。/ text param (schema TEXT control; "" when absent). */
    String textParam(String key);

    /** 布尔参数（schema BOOL 控件；键缺席 = false；存储值 &gt; 0.5 视为真）。
     *  <p>bool param (schema BOOL control; false when absent; a stored value &gt; 0.5 reads true).</p> */
    boolean boolParam(String key);

    /** 物品参数（schema ITEM 控件；键缺席或未设置 = {@code null} —— 头eless/降级环境
     *  同样返回 null，提供方自行降级）。
     *  <p>item param (schema ITEM control; {@code null} when absent/unset — also null in
     *  headless/degraded environments; providers degrade themselves).</p> */
    ItemStack itemParam(String key);

    /** 上一次持久化的状态 blob（交付副本，永不为 null 但可能为空；只读）。
     *  <p>The previously persisted state blob (a delivered copy, never null but possibly
     *  empty; read-only).</p> */
    CompoundTag state();

    /**
     * <b>可恢复契约的检查点</b>（D2a）：本次调用已耗尽本节点的时间片 —— 提供方应在
     * 最近的循环边界收尾，把进度打包进 {@link CustomNodeResult#suspended(CompoundTag)}
     * 返回。真实等待重节点（长解算/巨遍历）必须实现本检查点；忽略它的提供方由
     * 处置梯子处置（连续超份额 → 降频 → 熔断）。
     * <p>The resumable contract's checkpoint (D2a): this call has exhausted the node's
     * time slice — wrap up at the nearest loop boundary and return
     * {@link CustomNodeResult#suspended(CompoundTag)} with the packed progress. Real
     * heavy work (long solves / huge traversals) must implement this; providers that
     * ignore it are handled by the warn → throttle → fuse ladder.</p>
     */
    boolean sliceExpired();

    /** 宿主所在服务端世界（提供方直连自己方块用；降级/测试环境为 {@code null}）。
     *  <p>The host's server level (for provider-direct block exchange; {@code null} in
     *  degraded/test environments).</p> */
    ServerLevel level();

    /** 宿主方块坐标（{@link #level()} 同源；可为 {@code null}）。
     *  <p>The host block position (same source as {@link #level()}; may be {@code null}).</p> */
    BlockPos hostPos();

    /** 当前服务端游戏刻（宿主注入的求值时戳）。/ the current server game tick (the host's injected eval stamp). */
    long tick();
}
