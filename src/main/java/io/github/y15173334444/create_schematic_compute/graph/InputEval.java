package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator.InputSource;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator.SeatInputState;

/**
 * 输入域处理器（决策文档 §四）：INPUT_CTRL / INPUT_VIEW / INPUT_MOTION / INPUT_POSE ——
 * 座舱/姿态输入（KEYBOARD、MOUSE_*、GAMEPAD_*、VIEW/WORLD 角、ATTITUDE/FORWARD/
 * ACCELERATION/VELOCITY、POSITION）与姿态变换（SPLIT、POSE_CONVERT）。case 体从
 * {@link GraphEvaluator} 的 eval() switch 逐字搬迁（§三-2：方法体不改写，只改接收者/
 * 归属）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Input-domain handler (decision doc §四): INPUT_CTRL / INPUT_VIEW / INPUT_MOTION /
 * INPUT_POSE — cockpit/attitude inputs (KEYBOARD, MOUSE_*, GAMEPAD_*, view/world angles,
 * ATTITUDE/FORWARD/ACCELERATION/VELOCITY, POSITION) and pose transforms (SPLIT,
 * POSE_CONVERT). Case bodies are relocated verbatim from {@link GraphEvaluator}'s
 * eval() switch (§三-2: bodies are never rewritten, only their owner changes); this
 * class is a static function pack, no inheritance hierarchy (§三-3).</p>
 *
 * <p><b>唯一字面偏差：</b>KEYBOARD 的 {@code seat.keyBits} 改为访问器形态
 * {@code seat.keyBits()} —— {@link GraphEvaluator.SeatInputState} 是 record，组件字段在
 * 嵌套类外恒为 private（Java 语义），访问器是跨类读的唯一形态；语义逐位一致。
 * <p><b>The one literal deviation:</b> KEYBOARD's {@code seat.keyBits} uses accessor
 * form {@code seat.keyBits()} — {@link GraphEvaluator.SeatInputState} is a record whose
 * components are unconditionally private outside the nesting class (Java semantics);
 * the accessor is the only cross-class read. Bit-identical semantics.</p>
 */
public final class InputEval {

    private InputEval() {}

    /** 求值输入域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组（本域不重建数组，
     *  回写为通用契约）。
     *  Evaluate an input-domain node (case bodies relocated verbatim, see the class
     *  doc). The method opens with local aliases (receiver rewrite) so the case bodies
     *  stay untouched; it ends with a {@code ctx.o = o} write-back of the output array
     *  (this domain never rebuilds it; the write-back is the general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        List<InputSource> inputs = ctx.inputs;
        Map<Integer, Float> pidState = ctx.pidState;
        float dt = ctx.dt;
        SeatInputState seat = ctx.seat;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        switch (node.type) {
            case KEYBOARD -> {
                int keyIndex = node.params.length > 0 ? (int)node.params[0] : 0;
                keyIndex = Math.max(0, Math.min(57, keyIndex)); // 58 键: A-Z, 0-9, Space, Shift, Ctrl 等 / 58 keys: A-Z, 0-9, Space, Shift, Ctrl, etc.
                o[0] = ((seat.keyBits() >> keyIndex) & 1L) != 0 ? 1f : 0f;
            }
            case MOUSE_JOYSTICK -> {
                // abs mode (params[0]==1): accumulate mouse delta → stick position with memory.
                // inc mode (params[0]==0): direct output → mouse delta, stops=zero.
                // 绝对值模式：累积鼠标增量 → 带记忆的摇杆位置。
                // 增量模式：直接输出鼠标增量，停手归零。
                if (node.params.length > 0 && node.params[0] > 0.5f) {
                    float rawDx = seat.mouseX() / SeatInputState.JOYSTICK_SCALE;
                    float rawDy = seat.mouseY() / SeatInputState.JOYSTICK_SCALE;
                    node.runtimeStickX += rawDx * SeatInputState.ABS_SCALE;
                    node.runtimeStickY += rawDy * SeatInputState.ABS_SCALE;
                    node.runtimeStickX = Math.max(-1f, Math.min(1f, node.runtimeStickX));
                    node.runtimeStickY = Math.max(-1f, Math.min(1f, node.runtimeStickY));
                    o[0] = node.runtimeStickX;
                    o[1] = node.runtimeStickY;
                } else {
                    o[0] = seat.mouseX(); o[1] = seat.mouseY();
                }
            }
            case VIEW_ANGLE -> { o[0] = seat.pitch(); o[1] = seat.yaw(); }
            case MOUSE_BUTTON -> { o[0] = (seat.mouseButtons() & 1) != 0 ? 1 : 0; o[1] = (seat.mouseButtons() & 2) != 0 ? 1 : 0; }
            case GAMEPAD_JOYSTICK -> { o[0] = seat.gpadLX(); o[1] = seat.gpadLY(); o[2] = seat.gpadRX(); o[3] = seat.gpadRY(); }
            case GAMEPAD_BUTTON -> {
                int bi = node.params.length > 0 ? (int)node.params[0] : 0;
                o[0] = bi >= 0 && bi < 64 && ((seat.gpadButtons() >> bi) & 1L) != 0 ? 1 : 0;
            }
            case GAMEPAD_TRIGGER -> { o[0] = seat.gpadLT(); o[1] = seat.gpadRT(); }
            case WORLD_VIEW -> { o[0] = seat.worldYaw(); o[1] = seat.worldPitch(); }
            case ATTITUDE -> { o[0] = seat.attitudePitch(); o[1] = seat.attitudeRoll(); }
            case FORWARD -> { o[0] = seat.forwardYaw(); o[1] = seat.forwardPitch(); }
            case ACCELERATION -> { o[0] = seat.accelX(); o[1] = seat.accelY(); o[2] = seat.accelZ(); }
            case VELOCITY -> { o[0] = seat.velX() * 2f; o[1] = seat.velY() * 2f; o[2] = seat.velZ() * 2f; }
            case SPLIT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Math.max(0, v); o[1] = Math.max(0, -v);
            }
            case POSE_CONVERT -> {
                float pa = graph.getInputValue(node.id, 0, outputs);
                float ya = graph.getInputValue(node.id, 1, outputs);
                float roll = graph.getInputValue(node.id, 2, outputs);
                double r = Math.toRadians(roll);
                double sr = Math.sin(r), cr = Math.cos(r);
                o[0] = (float)(sr * pa + cr * ya);
                o[1] = (float)(cr * pa - sr * ya);
            }
            case POSITION -> {
                float wx = seat.worldX(), wy = seat.worldY(), wz = seat.worldZ();
                // 应用编辑区偏移（左/右、上/下、前/后），按方块世界朝向旋转
                // Apply editor offsets (left/right, up/down, front/back), rotated by block world facing
                if (node.params.length >= 3) {
                    float offX = node.params[0], offY = node.params[1], offZ = node.params[2];
                    if (offX != 0 || offY != 0 || offZ != 0) {
                        // 使用 forwardYaw 而非 blockYaw — forwardYaw 包含了 Sable 结构旋转
                        // Use forwardYaw instead of blockYaw — forwardYaw includes Sable structure rotation
                        float rad = (float) Math.toRadians(seat.forwardYaw());
                        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                        // +X = 方块右侧, +Z = 方块前方（世界空间）  /  +X = block right, +Z = block forward (world space)
                        wx += offX * cos + offZ * sin;
                        wy += offY;
                        wz += offX * sin - offZ * cos;
                    }
                }
                o[0] = wx; o[1] = wy; o[2] = wz;
            }
        }
        ctx.o = o;
    }
}
