package io.github.y15173334444.create_schematic_compute.api;

/**
 * 第三方自定义节点的求值行为（本模组公开 API 的一部分）。提供方实现本接口
 * 并与 {@link CustomNodeType} schema 一同注册；求值回调在<b>服务端主线程</b>由本模组
 * 求值循环调用——执行线程承诺：<b>永远在服务端主线程</b>，由本模组按其求值节奏调用。
 *
 * <p><b>签名零图域类型</b>：只见本包值类型 + JDK/MC 公共类型（结构性约束：提供方只对
 * 本包编译，绝不触达内部或可选依赖模组的类）。</p>
 *
 * <p><b>执行义务</b>：</p>
 * <ul>
 *   <li><b>普通（同步）节点</b>：µs 级义务 —— 单次调用应远短于本节点的时间片；
 *       超时由墙钟埋点记连续超份额，处置梯子（告警 → 降频 → 熔断）生效。</li>
 *   <li><b>可恢复（重）节点</b>：在循环边界检查 {@link CustomNodeContext#sliceExpired()}，
 *       到期即把进度写入 {@link CustomNodeResult#suspended} 的检查点并返回 ——
 *       求值器按独立预算池的 slice 轮转推进（与本模组内置公式节点的时间片轮转同一机制），
 *       输出在完成时才写（时序承诺：除重节点固有的跨 tick 推进外，不引入新的延迟类型）。</li>
 * </ul>
 * A third-party custom node's evaluation behavior (part of this mod's public API).
 * Providers implement this and register it together with the {@link CustomNodeType}
 * schema; the callback runs on the <b>server main thread</b>, called by this mod's
 * evaluation loop. Signatures carry zero graph-domain types (a structural requirement:
 * providers compile against this package alone). Execution obligations: ordinary nodes owe µs-scale latency
 * (overstays feed the warn → throttle → fuse ladder); resumable nodes check
 * {@link CustomNodeContext#sliceExpired()} at loop boundaries and return
 * {@link CustomNodeResult#suspended} with a checkpoint — the evaluator rotates them via
 * the independent budget pool's slice (the same time-slice rotation as this mod's
 * built-in formula node), and outputs land only on completion.
 */
public interface CustomNodeBehavior {

    /**
     * 求值一次。每宿主每 tick 至多一次（降频/熔断/提供方缺席时更少）；返回
     * {@code null} 视为本次空转（输出保持；本次耗时照常按墙钟进处置梯子，仅超份额才记）。
     * Evaluate once. At most once per host per tick (less under throttle/fuse or a
     * missing provider); a null return idles this call (outputs hold; its wall-clock time
     * still feeds the ladder as usual — only an overstay counts).
     *
     * @param ctx 本次求值上下文 / this evaluation's context
     * @return 完成结果（含输出）或挂起检查点 / a completion (with outputs) or a suspension checkpoint
     */
    CustomNodeResult evaluate(CustomNodeContext ctx);
}
