package io.github.y15173334444.create_schematic_compute.api;

import javax.annotation.Nullable;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 第三方宿主的声明面（宿主套件波 2）：宿主键、准入偏好与 tick 编排偏差，全部是
 * 「构造期已知、逐 tick 不变」的事实。缝（逐 tick 可变的宿主行为）走
 * {@link HostGraphBehavior}；引擎柄走 {@link HostGraphs#bind} 返回的
 * {@link HostGraphHandle}。三者合起来 = 波 1「Type 声明 + Behavior 行为 + 注册口」
 * 同一套门面模式在宿主域的翻版。
 * A third-party host's declaration face (host suite, wave 2): the host key, admission
 * preference and tick-orchestration deviations — all facts known at construction time
 * and constant per tick. Per-tick host behaviour rides {@link HostGraphBehavior}; the
 * engine handle is the {@link HostGraphHandle} returned by {@link HostGraphs#bind}.
 * Together they mirror wave 1's "Type declares + Behavior behaves + a registration
 * point" facade shape, transplanted into the host domain.
 *
 * <p>默认值 = 蓝图机基准（与内部 {@code GraphHost.TickHooks} 的默认列一致）：
 * {@code lightRecompile=false}、{@code litAbsentAbortsTick=true}、
 * {@code stopEndsTick=true}、{@code activeInputRefresh=false}、
 * {@code wantsStep7Recover=false}、{@code wantsFlipflopDiff=false}、停机位
 * AFTER_RECOMPILE、无 LIT 属性。偏差经 {@code with*} 收编器逐项覆盖。
 * Defaults equal the blueprint baseline (the internal TickHooks default columns);
 * deviations ride the {@code with*} withers one at a time.</p>
 *
 * <p>不可变 record；所有字段构造期校验（null 即抛）。签名只用 MC 公共类型
 * （{@link BooleanProperty}）与 api 自有类型——图域类型零出现。</p>
 * <p>An immutable record; every field is validated at construction (nulls throw).
 * Signatures use MC public types ({@link BooleanProperty}) and api-owned types only —
 * zero graph-domain types.</p>
 */
public record HostGraphType(String hostKey,
                            HostAdmission admission,
                            EvalVariant evalVariant,
                            StopSlot stopSlot,
                            boolean lightRecompile,
                            boolean litAbsentAbortsTick,
                            boolean stopEndsTick,
                            boolean activeInputRefresh,
                            boolean wantsStep7Recover,
                            boolean wantsFlipflopDiff,
                            @Nullable BooleanProperty litProperty) {

    public HostGraphType {
        if (hostKey == null || hostKey.isBlank())
            throw new IllegalArgumentException("HostGraphType hostKey must be non-blank");
        if (admission == null) throw new IllegalArgumentException("HostGraphType admission must be non-null");
        if (evalVariant == null) throw new IllegalArgumentException("HostGraphType evalVariant must be non-null");
        if (stopSlot == null) throw new IllegalArgumentException("HostGraphType stopSlot must be non-null");
    }

    /** ⑨ 求值档（与内部偏差矩阵的 evaluate 列一致）。The evaluate variant (the internal
     *  deviation matrix's evaluate column). */
    public enum EvalVariant {
        /** 全状态档：时序/状态走宿主权威状态表。The full-state form: timing/state ride the
         *  host's authoritative state tables. */
        FULL_STATE,
        /** 基础档（内部按空状态表走全状态路径）——大多数第三方宿主的档位。The basic form
         *  (internally routes the full-state path with empty state maps) — the tier most
         *  third-party hosts want. */
        BASIC,
        /** 空表档：时序节点不跨 tick 演进。The empty-table form: timing nodes never evolve
         *  across ticks. */
        EMPTY_TABLE,
    }

    /** ⑤ 停机检查的执行位。The stop-check's execution slot. */
    public enum StopSlot {
        /** 图检查之前（停机早退最靠前）。Before the graph check (the earliest stop
         *  early-return). */
        BEFORE_GRAPH_CHECK,
        /** 图检查之后、重编译之前（停机不重编译）。After the graph check, before the
         *  recompile (no recompile while stopped). */
        AFTER_GRAPH_CHECK,
        /** 重编译之后（基准口径）。After the recompile (the baseline). */
        AFTER_RECOMPILE,
    }

    /** 蓝图机基准声明（键 + 准入偏好 + 求值档；其余全默认）。The blueprint-baseline
     *  declaration (key + admission + evaluate variant; everything else default). */
    public static HostGraphType of(String hostKey, HostAdmission admission, EvalVariant variant) {
        return new HostGraphType(hostKey, admission, variant, StopSlot.AFTER_RECOMPILE,
            false, true, true, false, false, false, null);
    }

    /** 收编偏差 ④：L 档轻量重编译（Monitor/动能系口径）。Deviation ④: the Light recompile
     *  tier. */
    public HostGraphType withLightRecompile() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, true, litAbsentAbortsTick,
            stopEndsTick, activeInputRefresh, wantsStep7Recover, wantsFlipflopDiff, litProperty);
    }

    /** 收编偏差 ⑤：停机检查位。Deviation ⑤: the stop-check slot. */
    public HostGraphType withStopSlot(StopSlot slot) {
        return new HostGraphType(hostKey, admission, evalVariant, slot, lightRecompile, litAbsentAbortsTick,
            stopEndsTick, activeInputRefresh, wantsStep7Recover, wantsFlipflopDiff, litProperty);
    }

    /** 收编偏差 ⑤：停机后不立即结束本 tick（落入共用尾段）。Deviation ⑤: keep ticking into
     *  the shared tail after a stop. */
    public HostGraphType withStopNotEndingTick() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, litAbsentAbortsTick,
            false, activeInputRefresh, wantsStep7Recover, wantsFlipflopDiff, litProperty);
    }

    /** 收编偏差 ⑥：主动输入刷新档。Deviation ⑥: the active input-refresh tier. */
    public HostGraphType withActiveInputRefresh() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, litAbsentAbortsTick,
            stopEndsTick, true, wantsStep7Recover, wantsFlipflopDiff, litProperty);
    }

    /** 收编偏差 ⑦：冲突自愈遗留第二挂点。Deviation ⑦: the legacy second self-heal hook. */
    public HostGraphType withStep7Recover() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, litAbsentAbortsTick,
            stopEndsTick, activeInputRefresh, true, wantsFlipflopDiff, litProperty);
    }

    /** 收编偏差 ⑬：触发器差分广播（全状态档宿主用）。Deviation ⑬: the flipflop-diff
     *  broadcast (full-state hosts). */
    public HostGraphType withFlipflopDiff() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, litAbsentAbortsTick,
            stopEndsTick, activeInputRefresh, wantsStep7Recover, true, litProperty);
    }

    /** 收编偏差 ②：LIT 属性（{@code null} = 本宿主无步 ②）。Deviation ②: the LIT property
     *  ({@code null} = this host has no step ②). */
    public HostGraphType withLitProperty(@Nullable BooleanProperty lit) {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, litAbsentAbortsTick,
            stopEndsTick, activeInputRefresh, wantsStep7Recover, wantsFlipflopDiff, lit);
    }

    /** 收编偏差 ②：属性缺席时跳过而非中止。Deviation ②: skip (not abort) when the
     *  property is absent. */
    public HostGraphType withLitAbsentSkipped() {
        return new HostGraphType(hostKey, admission, evalVariant, stopSlot, lightRecompile, false,
            stopEndsTick, activeInputRefresh, wantsStep7Recover, wantsFlipflopDiff, litProperty);
    }
}
