package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-1 回归：座位控制输入的骑乘校验（{@code ControlSeatInputPacket.senderMayControl}）。
 * <p>P1-1 regressions: the rider check for control-seat input
 * ({@code ControlSeatInputPacket.senderMayControl}).</p>
 *
 * <p>安全语义：此前仅距离校验（128 格）—— 任何玩家可每 tick 覆盖骑乘者的按键位掩码
 * 完全劫持驾驶，或遥控无人机器（{@code consumeInput} 对无人座位刻意归零输入，可见遥控
 * 并非设计意图）。修复后发送者必须正骑在目标座位实体上且位置一致。</p>
 * <p>Security semantics: the 128-block distance check used to be the only guard — any player
 * could overwrite a rider's key mask every tick (full steering hijack) or drive an unmanned
 * machine ({@code consumeInput} deliberately zeroes input for unmanned seats, so remote
 * control is not the design intent). The sender must now be riding the target seat entity
 * with a matching position.</p>
 *
 * <p>headless 边界：{@code Entity} 是抽象类（Unsafe 无法实例化）且 {@code ControlSeatEntity}
 * 的类初始化触发注册表引导 —— 僵尸实例路线不可行，故测试覆盖 null 发送者分支与抽出的
 * 纯几何判定 {@link ControlSeatInputPacket#seatBlockMatches}（位置一致 / 容差 / 越位）。
 * "载具非 ControlSeatEntity" 分支由 {@code instanceof} 类型模式直接表达，无额外逻辑可测。</p>
 * <p>Headless limits: {@code Entity} is abstract (Unsafe cannot instantiate it) and
 * {@code ControlSeatEntity}'s class init triggers registry bootstrap — the zombie route is not
 * viable, so the tests cover the null-sender branch and the extracted pure geometry
 * {@link ControlSeatInputPacket#seatBlockMatches} (match / tolerance / mismatch). The
 * "vehicle is not a ControlSeatEntity" branch is expressed directly by the {@code instanceof}
 * type pattern and carries no extra logic to test.</p>
 */
class ControlSeatRiderCheckTest {

    private static final BlockPos SEAT_POS = new BlockPos(10, 64, 10);

    @Test
    @DisplayName("空发送者（防御性分支）拒绝")
    void nullSenderIsRefused() {
        assertFalse(ControlSeatInputPacket.senderMayControl(null, SEAT_POS),
            "无发送实体不得提交座位输入");
    }

    @Test
    @DisplayName("位置精确一致 → 放行")
    void exactMatchAccepted() {
        assertTrue(ControlSeatInputPacket.seatBlockMatches(SEAT_POS, SEAT_POS),
            "合法客户端发送 vehicle.blockPosition()，与服务端座位实体一致");
    }

    @Test
    @DisplayName("容差内（实体同步滞后 ≤2 格）→ 放行；容差外 → 拒绝")
    void positionToleranceWindow() {
        BlockPos near = SEAT_POS.offset(1, 1, 0); // distSqr = 2 ≤ 4
        assertTrue(ControlSeatInputPacket.seatBlockMatches(near, SEAT_POS));
        BlockPos far = SEAT_POS.offset(3, 0, 0);  // distSqr = 9 > 4
        assertFalse(ControlSeatInputPacket.seatBlockMatches(far, SEAT_POS),
            "容差只吸收同步滞后，不足以把输入投递到另一台机器的座位上");
    }

    @Test
    @DisplayName("空座位位置（实体 <clinit> 未跑的退化态）拒绝")
    void nullRiderBlockPosRefused() {
        assertFalse(ControlSeatInputPacket.seatBlockMatches(null, SEAT_POS));
    }
}
