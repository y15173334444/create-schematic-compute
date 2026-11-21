package io.github.y15173334444.create_schematic_compute.api;

/**
 * 第三方宿主的缝面（宿主套件波 2）：逐 tick 可变的宿主行为，按引擎 tick 编排
 * 的模板位插入 —— 与内部 {@code GraphHost.TickHooks} 的缝逐位对齐（编号同源），
 * 默认全空 = 引擎基准行为。声明面（构造期不变的事实）在 {@link HostGraphType}；
 * 本接口只承载「每 tick 在这个位置多做一件事」。
 * A third-party host's seam face (host suite, wave 2): per-tick host behaviour inserted
 * at the engine tick skeleton's template slots — aligned one-for-one with the internal
 * {@code GraphHost.TickHooks} seams (same numbering), all empty by default = the engine's
 * baseline behaviour. Declaration facts live on {@link HostGraphType}; this interface
 * only carries "do one extra thing here per tick".
 *
 * <p>实例化一次（bind 时传入），引擎逐 tick 复用 —— 实现须无状态或自行保证线程口径
 * （宿主行为只在服务端 tick 线程被调）。</p>
 * <p>Instantiate once (passed to bind) and the engine reuses it per tick — implementations
 * must be stateless or manage their own threading (host behaviour is invoked on the
 * server tick thread only).</p>
 */
public interface HostGraphBehavior {

    /** 缝 0：守卫后、① 前（KineticGauge 读数推送位）。Seam 0: after the guard, before ①. */
    default void afterGuard() {}

    /** 缝 1：⑮ 后、② 前。Seam 1: after ⑮, before ②. */
    default void beforeLit() {}

    /** 缝 2：⑤ 早退后、③ 前。Seam 2: after the ⑤ early-return, before ③. */
    default void beforeGraphCheck() {}

    /** 缝 3：④/⑤ 后、⑥ 前。Seam 3: after ④/⑤, before ⑥. */
    default void beforeInputs() {}

    /** 缝 4：⑦ 后、⑧ 前。Seam 4: after ⑦, before ⑧. */
    default void afterRecover() {}

    /** 缝 5：⑧ 后、⑨ 前。Seam 5: after ⑧, before ⑨. */
    default void beforeEval() {}

    /** 缝 6：⑨ 后、⑩ 前。Seam 6: after ⑨, before ⑩. */
    default void afterEval() {}

    /** 缝 7：⑩ 后、⑪ 前。Seam 7: after ⑩, before ⑪. */
    default void afterWriteOutputs() {}

    /** 缝 8：⑫⑬ 后、共用尾段前。Seam 8: after ⑫⑬, before the shared tail. */
    default void afterEvalBroadcast() {}

    /** 缝 9：⑭ 前。Seam 9: before ⑭. */
    default void beforeSetChanged() {}

    /** 缝 10：⑭ 后。Seam 10: after ⑭. */
    default void afterSetChanged() {}

    /** ⑤ 停机路径本体；默认 = 引擎的停机收尾（{@link HostGraphHandle#stopRunning()}）。
     *  Step ⑤'s stop-path body; the default is the engine's stop epilogue
     *  ({@link HostGraphHandle#stopRunning()}). */
    default void stopBody(HostGraphHandle handle) {
        handle.stopRunning();
    }
}
