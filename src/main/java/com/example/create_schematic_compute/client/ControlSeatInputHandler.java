package com.example.create_schematic_compute.client;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
import com.example.create_schematic_compute.network.ControlSeatInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端处理器 — 摇杆模式使用 Mixin 导出的原始鼠标增量，不再触碰 GLFW 光标，
 * 彻底消除 glfwSetCursorPos 注入的虚假鼠标位移。
 */
@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ControlSeatInputHandler {
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

    // 视角差模式 diff 的比例系数（~3°/tick = 满摇杆，匹配旧 dx*0.05 手感）
    private static final float JOYSTICK_SCALE = 1.0f / 3.0f;

    private static volatile boolean suppressMouseTurn = false;
    public static boolean isSuppressingMouseTurn() { return suppressMouseTurn; }
    public static int getInputMode() { return inputMode; }

    // 由 Mixin 写入上一帧 turn() 的原始鼠标增量（替代 glfwGetCursorPos）
    private static volatile double rawMouseDYaw, rawMouseDPitch;
    public static void onRawMouseDelta(double yaw, double pitch) { rawMouseDYaw = yaw; rawMouseDPitch = pitch; }

    private static volatile int inputMode = 0; // 默认摇杆模式
    private static volatile boolean wasTab = false;
    private static volatile boolean wasSeatedLastTick = false;
    private static volatile float joystickX = 0, joystickY = 0;
    private static volatile boolean wantDismount = false;
    private static volatile boolean wasGuiOpen = false;
    private static float lastSableYaw = Float.NaN; // previous frame's sable relativeYaw for delta compensation

    // ═══════════════════════════════════════
    //  Pre — 摇杆值来自 Mixin 导出的原始 delta
    // ═══════════════════════════════════════
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getWindow() == null) return;

        long window = mc.getWindow().getWindow();
        var vehicle = mc.player.getVehicle();
        boolean seated = vehicle instanceof ControlSeatEntity;
        boolean guiOpen = mc.screen != null;

        if (seated) {
            mc.options.keyInventory.consumeClick();
            mc.options.keyDrop.consumeClick();
            mc.options.keySwapOffhand.consumeClick();
            mc.options.keyChat.consumeClick();
            mc.options.keyCommand.consumeClick();
            mc.options.keyAdvancements.consumeClick();
            mc.options.keyAttack.consumeClick();
            mc.options.keyUse.consumeClick();
        }

        if (seated && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_GRAVE_ACCENT) == GLFW.GLFW_PRESS) {
            wantDismount = true;
            wasTab = false; inputMode = 0; suppressMouseTurn = false; // will be set true below if re-seated
            joystickX = 0; joystickY = 0;
            wasSeatedLastTick = false;
            return;
        }

        if (seated) {
            mc.options.keyShift.setDown(false);
        }

        if (seated && !wasSeatedLastTick) {
            inputMode = 0;
            suppressMouseTurn = true;
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§e按 ~ 离开  按 Tab 切换视角模式"), true);
        }
        wasSeatedLastTick = seated;
        if (!seated) { wasTab = false; suppressMouseTurn = false; return; }

        if (!guiOpen) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
        if (wasGuiOpen && !guiOpen) {
            joystickX = 0; joystickY = 0;
        }
        wasGuiOpen = guiOpen;

        // ── TAB 切换模式 ──
        boolean tab = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
        if (tab && !wasTab) {
            inputMode = (inputMode + 1) % 2;
            suppressMouseTurn = (inputMode == 0);
        }
        wasTab = tab;

        if (guiOpen || inputMode == 1) return;

        // ══════════ 摇杆模式：从 Mixin 获取原始鼠标增量 ══════════
        joystickX = (float) Math.max(-1.0, Math.min(1.0, rawMouseDYaw * JOYSTICK_SCALE));
        joystickY = (float) Math.max(-1.0, Math.min(1.0, rawMouseDPitch * JOYSTICK_SCALE));
    }

    // ═══════════════════════════════════════
    //  Post — 锁定视角 + 发送包
    // ═══════════════════════════════════════
    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getWindow() == null) return;

        var vehicle = mc.player.getVehicle();
        boolean seated = vehicle instanceof ControlSeatEntity;
        if (!seated) { wasTab = false; suppressMouseTurn = false; return; }

        BlockPos seatPos = vehicle.blockPosition();
        float seatYaw = vehicle.getYRot();

        long keyBits = 0;
        long window = mc.getWindow().getWindow();
        for (int i = 0; i < TOTAL_KEYS; i++) {
            if (GLFW.glfwGetKey(window, KEY_INDEX_TO_GLFW[i]) == GLFW.GLFW_PRESS)
                keyBits |= (1L << i);
        }

        int mouseBtns = 0;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) mouseBtns |= 1;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) mouseBtns |= 2;

        float gLX = 0, gLY = 0, gRX = 0, gRY = 0;
        long gBtns = 0;
        if (GLFW.glfwJoystickPresent(GLFW.GLFW_JOYSTICK_1)) {
            var state = org.lwjgl.glfw.GLFWGamepadState.malloc();
            try {
                if (GLFW.glfwGetGamepadState(GLFW.GLFW_JOYSTICK_1, state)) {
                    gLX = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
                    gLY = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);
                    gRX = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X);
                    gRY = state.axes().get(org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y);
                    var btns = state.buttons();
                    for (int i = 0; i < 15 && i < btns.capacity(); i++)
                        if (btns.get(i) == 1) gBtns |= (1L << i);
                }
            } finally { state.free(); }
        }

        float mx = 0, my = 0, vy = 0, vp = 0;

        if (inputMode == 0) {
            mx = joystickX;
            my = joystickY;
            mc.player.yRotO = seatYaw;     mc.player.setYRot(seatYaw);
            mc.player.xRotO = 0;             mc.player.setXRot(0);
            mc.player.yHeadRot = seatYaw;    mc.player.yHeadRotO = seatYaw;
            mc.player.yBodyRot = seatYaw;    mc.player.yBodyRotO = seatYaw;
        } else {
            float diff = mc.player.getYRot() - seatYaw;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            vy = diff;
            vp = mc.player.getXRot();
        }

        long extKeyBits = keyBits | ((long)(mouseBtns & 3) << 58);
        PacketDistributor.sendToServer(new ControlSeatInputPacket(
            seatPos, extKeyBits, mx, my, vy, vp, inputMode,
            mouseBtns, gLX, gLY, gRX, gRY, gBtns, wantDismount
        ));
        wantDismount = false;
    }

    // ═══════════════════════════════════════
    //  渲染帧 — 摇杆模式强制 pitch=0
    // ═══════════════════════════════════════
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getVehicle() instanceof ControlSeatEntity)) return;
        if (mc.screen != null) return;

        if (inputMode == 0) {
            // Joystick mode: lock player to vehicle
            mc.player.setXRot(0);            mc.player.xRotO = 0;
            var vehicle = mc.player.getVehicle();
            if (vehicle != null) {
                float vy = vehicle.getYRot();
                mc.player.setYRot(vy);        mc.player.yRotO = vy;
                mc.player.yHeadRot = vy;       mc.player.yHeadRotO = vy;
                mc.player.yBodyRot = vy;       mc.player.yBodyRotO = vy;
            }
            lastSableYaw = Float.NaN;
        } else {
            // View Angle mode: compensate sable rotation using delta
            var vehicle = mc.player.getVehicle();
            float sy = (vehicle instanceof ControlSeatEntity cs) ? cs.getSableRelativeYaw() : 0;
            if (!Float.isNaN(lastSableYaw)) {
                float sDelta = sy - lastSableYaw; // how much sable rotated since last frame
                if (Math.abs(sDelta) > 0.001f) {
                    // Counteract: rotate player opposite to sable
                    mc.player.setYRot(mc.player.getYRot() + sDelta);
                    mc.player.yRotO += sDelta;
                    mc.player.yHeadRot += sDelta;
                    mc.player.yHeadRotO += sDelta;
                }
            }
            lastSableYaw = sy;
        }
        if (mc.player.isShiftKeyDown()) mc.player.setShiftKeyDown(false);
    }
}
