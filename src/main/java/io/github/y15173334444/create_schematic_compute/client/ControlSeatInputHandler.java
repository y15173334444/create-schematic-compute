package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatBlock;
import io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity;
import io.github.y15173334444.create_schematic_compute.network.ControlSeatInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side input handler for the Control Seat. Manages two camera modes:
 * <ul>
 *   <li><b>Mode 0 — FIXED</b>: camera locked to seat world orientation (yaw + pitch).
 *       Sable sub-level orientation is computed client-side from block FACING +
 *       sub-level render pose quaternion. / 相机锁定到座椅世界朝向（偏航+俯仰），
 *       Sable 子关卡朝向由客户端从方块 FACING + 渲染姿态四元数计算。</li>
 *   <li><b>Mode 1 — VIEW_DIFFERENCE</b>: camera free (mouse-controlled).
 *       Outputs vy = playerYaw - seatWorldYaw, vp = playerPitch - seatWorldPitch —
 *       the angular difference between player view and seat forward.
 *       / 相机自由（鼠标控制）。输出 vy/vp = 玩家视角与座椅前方角度差。</li>
 * </ul>
 * <p>
 * 客户端输入处理器。管理控制座椅的两种相机模式。
 * Raw mouse delta is exported by {@code LocalPlayerMixin} (no GLFW cursor manipulation).
 * / 原始鼠标增量由 LocalPlayerMixin 导出（不操作 GLFW 光标）。
 */
@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ControlSeatInputHandler {
    // ── Key index → GLFW key code mapping / 按键索引 → GLFW键码映射 ──
    private static final int[] KEY_INDEX_TO_GLFW = {
        GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_E,
        GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_J,
        GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_M, GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_O,
        GLFW.GLFW_KEY_P, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_T,
        GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_Y,
        GLFW.GLFW_KEY_Z,
        GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
        GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9,
        GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT,
        GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL,
        GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT,
        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_BACKSPACE,
        GLFW.GLFW_KEY_CAPS_LOCK, GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL,
        GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET,
        GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_APOSTROPHE,
        GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD, GLFW.GLFW_KEY_SLASH,
        GLFW.GLFW_KEY_BACKSLASH, GLFW.GLFW_KEY_GRAVE_ACCENT
    };
    private static final int TOTAL_KEYS = KEY_INDEX_TO_GLFW.length;

    /** Joystick scale: ~3°/tick at full deflection (~60°/s), matches legacy dx*0.05 feel.
     *  摇杆比例系数：满偏约 3°/tick (60°/s)，匹配旧版手感。 */
    public static final float JOYSTICK_SCALE = 1.0f / 3.0f;
    /** Absolute-mode accumulation scale (per-tick), slower than incremental to avoid overshoot.
     *  绝对值模式每tick累积系数，比增量模式更缓和，避免过冲。 */
    public static final float ABS_SCALE = 1.0f / 6.0f;

    // ── State fields / 状态字段 ──
    private static volatile boolean suppressMouseTurn = false;
    public static boolean isSuppressingMouseTurn() { return suppressMouseTurn; }

    /** Raw mouse delta from LocalPlayerMixin.turn() — replaces glfwGetCursorPos.
     *  LocalPlayerMixin 导出的原始鼠标增量（替代 glfwGetCursorPos）。 */
    private static volatile double rawMouseDYaw, rawMouseDPitch;
    public static void onRawMouseDelta(double yaw, double pitch) { rawMouseDYaw = yaw; rawMouseDPitch = pitch; }

    /** Current camera input mode (0=FIXED, 1=VIEW_DIFFERENCE). / 当前相机模式。 */
    private static volatile int inputMode = 0;
    public static int getInputMode() { return inputMode; }

    // ── Sable availability / Sable 可用性 ──
    /** Cached at class init; false when Sable is absent. / 类加载时缓存；无 Sable 时为 false。 */
    private static final boolean SABLE_LOADED = net.neoforged.fml.ModList.get().isLoaded("sable");

    private static volatile boolean wasTab = false;
    private static volatile boolean wasSeatedLastTick = false;
    private static volatile float joystickX = 0, joystickY = 0;
    private static volatile boolean wantDismount = false;
    private static volatile boolean wasGuiOpen = false;
    /** Hotbar slot locked while seated. / 乘坐时锁定的物品栏槽位。 */
    private static volatile int savedHotbarSlot = -1;

    // ════════════════════════════════════════════════════════════════
    //  Seat world orientation (client-side Sable computation)
    //  座椅世界朝向（客户端 Sable 计算）
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute the seat's world-space yaw and pitch from block FACING direction
     * transformed by the Sable sub-level's render-pose quaternion.
     * <p>
     * Falls back to {@code vehicle.getYRot()/getXRot()} when Sable is absent or
     * the seat is not inside a sub-level.
     * <p>
     * 从方块 FACING 方向经 Sable 子关卡渲染姿态四元数变换，计算座椅世界空间偏航和俯仰。
     * 无 Sable 或座椅不在子关卡内时，退回到 vehicle.getYRot()/getXRot()。
     *
     * @return float[] {worldYaw, worldPitch} in degrees / 度数
     */
    private static float[] getSeatWorldOrientation(Minecraft mc, BlockPos seatPos, Entity vehicle) {
        if (!SABLE_LOADED)
            return new float[]{vehicle.getYRot(), vehicle.getXRot()};
        try {
            var sectionPos = net.minecraft.core.SectionPos.of(seatPos);
            var sl = dev.ryanhcode.sable.Sable.HELPER.getContainingClient(sectionPos);
            if (sl == null)
                return new float[]{vehicle.getYRot(), vehicle.getXRot()};

            // Read block FACING → seat local forward / 方块朝向 → 座椅本地前向
            float localYaw = 0;
            var bs = mc.player.level().getBlockState(seatPos);
            if (bs.hasProperty(ControlSeatBlock.FACING))
                localYaw = bs.getValue(ControlSeatBlock.FACING).toYRot();

            // Build local forward unit vector (Minecraft yaw: 0=south, +yaw=CCW)
            // 构建本地前向单位向量（MC偏航约定：0=南，+偏航=逆时针）
            double lr = Math.toRadians(localYaw);
            double lx = -Math.sin(lr);
            double ly = 0;
            double lz = Math.cos(lr);

            // Transform by sub-level render pose quaternion (pitch + yaw + roll)
            // 用子关卡渲染姿态四元数变换（含俯仰+偏航+横滚）
            var orient = sl.renderPose().orientation();
            var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
            var v = new org.joml.Vector3d(lx, ly, lz);
            q.transform(v);

            // Convert world forward vector back to yaw & pitch
            // 世界前向向量转回偏航和俯仰
            double worldYaw = Math.toDegrees(Math.atan2(-v.x, v.z));
            double horiz = Math.sqrt(v.x * v.x + v.z * v.z);
            double worldPitch = Math.toDegrees(Math.atan2(v.y, horiz));
            while (worldYaw < 0) worldYaw += 360;
            while (worldYaw >= 360) worldYaw -= 360;
            return new float[]{(float) worldYaw, (float) worldPitch};
        } catch (Exception e) {
            return new float[]{vehicle.getYRot(), vehicle.getXRot()};
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Pre-tick — consume keys, TAB switch, accumulate joystick delta
    //  前置 tick — 消耗按键、TAB切换、累积摇杆增量
    // ════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getWindow() == null) return;

        long window = mc.getWindow().getWindow();
        var vehicle = mc.player.getVehicle();
        boolean seated = vehicle instanceof ControlSeatEntity;
        boolean guiOpen = mc.screen != null;

        // ── Lock hotbar and consume interaction keys while seated ──
        // ── 乘坐时锁定物品栏并消耗交互按键 ──
        if (seated) {
            if (savedHotbarSlot < 0) savedHotbarSlot = mc.player.getInventory().selected;
            mc.player.getInventory().selected = savedHotbarSlot;
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
            mc.options.keyPickItem.setDown(false);
            mc.options.keyInventory.consumeClick();
            mc.options.keyDrop.consumeClick();
            mc.options.keySwapOffhand.consumeClick();
            mc.options.keyChat.consumeClick();
            mc.options.keyCommand.consumeClick();
            mc.options.keyAdvancements.consumeClick();
            mc.options.keyAttack.consumeClick();
            mc.options.keyUse.consumeClick();
            mc.options.keyPickItem.consumeClick();
            for (int i = 0; i < 9; i++) mc.options.keyHotbarSlots[i].consumeClick();
        } else {
            savedHotbarSlot = -1;
        }

        // ── Dismount via ~ key / 按 ~ 离开 ──
        if (seated && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_GRAVE_ACCENT) == GLFW.GLFW_PRESS) {
            wantDismount = true;
            wasTab = false; inputMode = 0; suppressMouseTurn = false;
            joystickX = 0; joystickY = 0;
            wasSeatedLastTick = false;
            return;
        }

        if (seated) {
            mc.options.keyShift.setDown(false);
        }

        // ── Just sat down → init FIXED mode / 刚坐下 → 初始化 FIXED 模式 ──
        if (seated && !wasSeatedLastTick) {
            inputMode = 0;
            suppressMouseTurn = true;
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§e按 ~ 离开  按 Tab 切换视角模式"), true);
        }
        wasSeatedLastTick = seated;
        if (!seated) { wasTab = false; suppressMouseTurn = false; return; }

        // ── Grab cursor when GUI is closed / GUI 关闭时抓取光标 ──
        if (!guiOpen) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
        wasGuiOpen = guiOpen;

        // ── TAB: toggle camera mode / TAB 切换相机模式 ──
        boolean tab = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
        if (tab && !wasTab) {
            inputMode = (inputMode + 1) % 2;
            suppressMouseTurn = (inputMode == 0);
            // On switching to Mode 1, one-time align player yaw/pitch with seat
            // world orientation so that vy/vp start from 0 smoothly.
            // 切到 Mode 1 时一次性将玩家视角对齐座椅世界朝向，使 vy/vp 从 0 起步。
            if (inputMode == 1) {
                var v = mc.player.getVehicle();
                if (v != null) {
                    float[] ori = getSeatWorldOrientation(mc, v.blockPosition(), v);
                    mc.player.setYRot(ori[0]);
                    mc.player.setXRot(ori[1]);
                }
            }
        }
        wasTab = tab;

        if (guiOpen || inputMode == 1) return;

        // ── FIXED mode: accumulate joystick delta from raw mouse input ──
        // ── FIXED 模式：从原始鼠标增量累积摇杆值 ──
        joystickX = (float) Math.max(-1.0, Math.min(1.0, rawMouseDYaw * JOYSTICK_SCALE));
        joystickY = (float) Math.max(-1.0, Math.min(1.0, rawMouseDPitch * JOYSTICK_SCALE));
    }

    // ════════════════════════════════════════════════════════════════
    //  Post-tick — camera lock + build & send packet to server
    //  后置 tick — 相机锁定 + 构建并发送数据包到服务端
    // ════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getWindow() == null) return;

        var vehicle = mc.player.getVehicle();
        boolean seated = vehicle instanceof ControlSeatEntity;
        if (!seated) { wasTab = false; suppressMouseTurn = false; return; }

        BlockPos seatPos = vehicle.blockPosition();
        float[] seatOri = getSeatWorldOrientation(mc, seatPos, vehicle);
        float seatYaw = seatOri[0];
        float seatPitch = seatOri[1];

        // ── Read keyboard state / 读取键盘状态 ──
        long keyBits = 0;
        long window = mc.getWindow().getWindow();
        for (int i = 0; i < TOTAL_KEYS; i++) {
            if (GLFW.glfwGetKey(window, KEY_INDEX_TO_GLFW[i]) == GLFW.GLFW_PRESS)
                keyBits |= (1L << i);
        }

        // ── Read mouse buttons / 读取鼠标按键 ──
        int mouseBtns = 0;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) mouseBtns |= 1;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) mouseBtns |= 2;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS) mouseBtns |= 4;

        // ── Read gamepad state / 读取手柄状态 ──
        float gLX = 0, gLY = 0, gRX = 0, gRY = 0, gLT = 0, gRT = 0;
        long gBtns = 0;
        if (GLFW.glfwJoystickPresent(GLFW.GLFW_JOYSTICK_1)) {
            var state = org.lwjgl.glfw.GLFWGamepadState.malloc();
            try {
                if (GLFW.glfwGetGamepadState(GLFW.GLFW_JOYSTICK_1, state)) {
                    gLX = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
                    gLY = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
                    gRX = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X);
                    gRY = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y);
                    gLT = Math.max(0f, state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER));
                    gRT = Math.max(0f, state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER));
                    var btns = state.buttons();
                    for (int i = 0; i < 15 && i < btns.capacity(); i++)
                        if (btns.get(i) == 1) gBtns |= (1L << i);
                }
            } finally { state.free(); }
        }

        // ── Build output values / 构建输出值 ──
        float mx = 0, my = 0, vy = 0, vp = 0;

        if (inputMode == 0) {
            // FIXED mode: camera locked to seat world orientation; joystick from mouse delta.
            // FIXED 模式：相机锁定到座椅世界朝向；摇杆来自鼠标增量。
            mx = joystickX;
            my = joystickY;
            mc.player.yRotO = seatYaw;     mc.player.setYRot(seatYaw);
            mc.player.xRotO = seatPitch;   mc.player.setXRot(seatPitch);
            mc.player.yHeadRot = seatYaw;  mc.player.yHeadRotO = seatYaw;
            mc.player.yBodyRot = seatYaw;  mc.player.yBodyRotO = seatYaw;
        } else {
            // VIEW_DIFFERENCE mode: camera free. vy/vp = angular difference between
            // player view and seat's current world forward.
            // VIEW_DIFFERENCE 模式：相机自由。vy/vp = 玩家视角与座椅世界前方夹角。
            float diff = mc.player.getYRot() - seatYaw;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            vy = diff;
            vp = mc.player.getXRot() - seatPitch;
        }

        // ── Send packet to server / 发送数据包 ──
        long extKeyBits = keyBits | ((long)(mouseBtns & 7) << 58); // L/R/M buttons → bits 58-60
        PacketDistributor.sendToServer(new ControlSeatInputPacket(
            seatPos, extKeyBits, mx, my, vy, vp, inputMode,
            mouseBtns, gLX, gLY, gRX, gRY, gLT, gRT, gBtns, wantDismount
        ));
        wantDismount = false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Render-frame pre — reinforce camera lock to avoid drift between ticks
    //  渲染帧前 — 强化相机锁定，防止 tick 间隙漂移
    // ════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getVehicle() instanceof ControlSeatEntity)) return;
        if (mc.screen != null) return;

        if (inputMode == 0) {
            // FIXED mode: re-apply camera lock each render frame to prevent
            // any drift between tick boundaries (e.g. from mouse events).
            // FIXED 模式：每渲染帧重新应用相机锁，防止 tick 间隙漂移。
            var vehicle = mc.player.getVehicle();
            if (vehicle != null) {
                float[] ori = getSeatWorldOrientation(mc, vehicle.blockPosition(), vehicle);
                mc.player.setYRot(ori[0]);     mc.player.yRotO = ori[0];
                mc.player.setXRot(ori[1]);     mc.player.xRotO = ori[1];
                mc.player.yHeadRot = ori[0];   mc.player.yHeadRotO = ori[0];
                mc.player.yBodyRot = ori[0];   mc.player.yBodyRotO = ori[0];
            }
        }
        // Prevent shift (sneak) while seated / 乘坐时阻止潜行
        if (mc.player.isShiftKeyDown()) mc.player.setShiftKeyDown(false);
    }
}
