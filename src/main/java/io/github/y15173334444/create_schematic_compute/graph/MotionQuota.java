package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 运动指令的配额完成模型（纯函数策略，无 Create/MC 依赖，可单测）。
 * Quota-based completion model for motion commands (pure, unit-testable).
 *
 * <p>背景：前代实现用「位置窗口采样」判定到位（|目标 - 位置| < 0.5°），在转速较高时
 * 每 tick 位置步进（speed×0.3°）会跳过窗口——指令永不完成 → 指令栈卡死
 * （"转动指令发下去了但输出停不下来"）。配额模型改为开环记账：剩余量按每 tick 实际
 * 转过的量递减，减到 0 即完成——数学上不可能跳过，也不可能永不完成。</p>
 * <p>Backdrop: the previous implementation sampled a position window (|target -
 * position| < 0.5°); at high RPM the per-tick position step (speed×0.3°) skips the
 * window, so the command never completes and the command stack wedges ("rotate
 * command sent but the output never stops"). The quota model is open-loop
 * bookkeeping: the remaining amount decreases by the actual per-tick travel and
 * completes at zero — it can neither skip nor stall.</p>
 *
 * <p>换算常量与官方一致（{@code KineticBlockEntity}）：
 * 度 = speed × 0.3 / tick；米 = speed / 512 / tick（× dt 0.05）。</p>
 */
public final class MotionQuota {

    /** 官方角度换算：度/tick = speed × 0.3。 Official angular conversion. */
    public static final float DEG_PER_RPM_TICK = 0.3f;
    /** 官方线性换算：米/tick = speed / 512 × dt(0.05)。 Official linear conversion. */
    public static final float METERS_PER_RPM_TICK = 1f / 512f * 0.05f;

    private float remaining;

    private MotionQuota(float amount) {
        this.remaining = Math.max(0f, amount);
    }

    /** 建立配额：ROTATE=度、MOVE=米、WAIT 不使用本类。 Amount: degrees (ROTATE) or meters (MOVE). */
    public static MotionQuota of(float amount) {
        return new MotionQuota(amount);
    }

    /** 按本 tick 实际转过的量递减。**内部强制取绝对值**——调用方传入带符号的行程
     *  （如 getSpeed()×0.3，负方向网络为负）时配额同样记账；漏 abs 会让负方向
     *  配额永不消耗（指令永不完成 → 离合永远接合）。
     *  Consume by the per-tick travel. **Abs applies internally** — callers may pass
     *  signed travel (e.g. getSpeed()×0.3 is negative on a negative-direction
     *  network); skipping the abs would stall the quota forever. */
    public void consumeAbs(float travel) {
        remaining -= Math.abs(travel);
    }

    /** 剩余量（度或米）。 Remaining amount (degrees or meters). */
    public float remaining() {
        return remaining;
    }

    /** 是否已完成（剩余 ≤ 0）。 Done when the remaining amount reaches zero. */
    public boolean done() {
        return remaining <= 0f;
    }

    /** 角度换算助手：RPM → 度/tick。 RPM → degrees per tick. */
    public static float degreesPerTick(float speedRpm) {
        return Math.abs(speedRpm) * DEG_PER_RPM_TICK;
    }

    /** 线性换算助手：RPM → 米/tick（与官方 convertToLinear × dt 一致）。 RPM → meters per tick. */
    public static float metersPerTick(float speedRpm) {
        return Math.abs(speedRpm) * METERS_PER_RPM_TICK;
    }
}
