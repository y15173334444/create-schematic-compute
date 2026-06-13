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
 * 客户端处理器
 *
 * 两种模式都保持 CURSOR_DISABLED。
 * RenderFrameEvent.Pre 中读取 sable 子世界渲染姿态，让玩家视角随结构旋转。
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
    private static final int TOTAL_KEYS = KEY_INDEX_TO_GLFW.length; // 58

    // 摇杆增量追踪
    private static double prevCursorX, prevCursorY;
    private static boolean cursorInit = false;
    private static int inputMode = 0;
    private static boolean wasTab = false;
    private static boolean wasSeatedLastTick = false;

    // Pre 事件中计算的摇杆值
    private static float joystickX = 0, joystickY = 0;
    private static boolean wantDismount = false;
    private static boolean wasGuiOpen = false;
    private static Float transitionYaw = null, transitionPitch = null;


    // ═══════════════════════════════════════
    //  Pre — 读鼠标增量
    // ═══════════════════════════════════════
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getWindow() == null) return;

        long window = mc.getWindow().getWindow();
        var vehicle = mc.player.getVehicle();
        boolean seated = vehicle instanceof ControlSeatEntity;
        boolean guiOpen = mc.screen != null;

        // ── 坐下时阻止游戏操作按键和鼠标交互 ──
        if (seated) {
            mc.options.keyInventory.consumeClick();
            mc.options.keyDrop.consumeClick();
            mc.options.keySwapOffhand.consumeClick();
            mc.options.keyChat.consumeClick();
            mc.options.keyCommand.consumeClick();
            mc.options.keyAdvancements.consumeClick();
            // 屏蔽鼠标左右键：不破坏方块、不攻击实体，按键状态由包发送到服务端处理
            mc.options.keyAttack.consumeClick();
            mc.options.keyUse.consumeClick();
        }

        // ── ~ 键离开座椅（标记下马，通过数据包发送到服务端） ──
        if (seated && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_GRAVE_ACCENT) == GLFW.GLFW_PRESS) {
            wantDismount = true;
            wasTab = false; cursorInit = false; inputMode = 0;
            joystickX = 0; joystickY = 0;
            wasSeatedLastTick = false;
            return;
        }

        // ── 坐下时阻止 Shift 下马 ──
        if (seated) {
            mc.options.keyShift.setDown(false);
        }

        // ── 坐下时显示提示 + 默认为摇杆模式 ──
        if (seated && !wasSeatedLastTick) {
            inputMode = 0;
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§e按 ~ 离开  按 Tab 切换视角模式"),
                true);
        }
        wasSeatedLastTick = seated;

        if (!seated) { wasTab = false; cursorInit = false; return; }
        // GUI 打开时不锁定光标（ESC 打开菜单后能正常使用鼠标）
        if (!guiOpen) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }

        // ── 从 GUI 回到游戏时重置光标位置 ──
        if (wasGuiOpen && !guiOpen) {
            cursorInit = false;
        }
        wasGuiOpen = guiOpen;

        boolean tab = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
        if (tab && !wasTab) {
            int oldMode = inputMode;
            inputMode = (inputMode + 1) % 2;
            cursorInit = false;
            if (oldMode == 0 && inputMode == 1 && mc.player != null) {
                // 保存当前视角，Post 中恢复以消除 MC 累积鼠标 delta 产生的跳变
                transitionYaw = mc.player.getYRot();
                transitionPitch = mc.player.getXRot();
            }
        }
        wasTab = tab;

        if (guiOpen || inputMode == 1) return;

        // 摇杆
        double[] xp = new double[1], yp = new double[1];
        GLFW.glfwGetCursorPos(window, xp, yp);

        if (!cursorInit) {
            int cw = mc.getWindow().getScreenWidth();
            int ch = mc.getWindow().getScreenHeight();
            GLFW.glfwSetCursorPos(window, cw / 2.0, ch / 2.0);
            prevCursorX = cw / 2.0; prevCursorY = ch / 2.0;
            cursorInit = true;
            joystickX = 0; joystickY = 0;
            return;
        }

        double dx = xp[0] - prevCursorX;
        double dy = yp[0] - prevCursorY;
        int cw = mc.getWindow().getScreenWidth();
        int ch = mc.getWindow().getScreenHeight();
        GLFW.glfwSetCursorPos(window, cw / 2.0, ch / 2.0);
        prevCursorX = cw / 2.0; prevCursorY = ch / 2.0;

        joystickX = (float) Math.max(-1.0, Math.min(1.0, dx * 0.05));
        joystickY = (float) Math.max(-1.0, Math.min(1.0, dy * 0.05));
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
        if (!seated) { wasTab = false; cursorInit = false; return; }

        BlockPos seatPos = vehicle.blockPosition();
        float seatYaw = vehicle.getYRot();

        long keyBits = 0;
        long window = mc.getWindow().getWindow();
        for (int i = 0; i < TOTAL_KEYS; i++) {
            if (GLFW.glfwGetKey(window, KEY_INDEX_TO_GLFW[i]) == GLFW.GLFW_PRESS)
                keyBits |= (1L << i);
        }

        // ── 鼠标按键 ──
        int mouseBtns = 0;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) mouseBtns |= 1;
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS) mouseBtns |= 2;

        // ── 手柄输入 ──
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
            } finally {
                state.free();
            }
        }

        float mx = 0, my = 0, vy = 0, vp = 0;

        if (inputMode == 0) {
            transitionYaw = null; transitionPitch = null;
            mx = joystickX;
            my = joystickY;
            mc.player.yRotO = seatYaw;
            mc.player.setYRot(seatYaw);
            mc.player.xRotO = 0;
            mc.player.setXRot(0);
            mc.player.yHeadRot = seatYaw;
            mc.player.yBodyRot = seatYaw;
        } else {
            if (transitionYaw != null) {
                mc.player.setYRot(transitionYaw); mc.player.yRotO = transitionYaw;
                mc.player.setXRot(transitionPitch); mc.player.xRotO = transitionPitch;
                transitionYaw = null; transitionPitch = null;
            }
            float diff = mc.player.getYRot() - seatYaw;
            while (diff > 180) diff -= 360;
            while (diff < -180) diff += 360;
            vy = diff;
            vp = mc.player.getXRot();
        }

        // 把 mouse/gamepad 数据附加到 keyBits 的高位
        long extKeyBits = keyBits | ((long)(mouseBtns & 3) << 58);

        PacketDistributor.sendToServer(new ControlSeatInputPacket(
            seatPos, extKeyBits, mx, my, vy, vp, inputMode,
            mouseBtns, gLX, gLY, gRX, gRY, gBtns, wantDismount
        ));
        wantDismount = false;
    }

    // ═══════════════════════════════════════
    //  每渲染帧 — 摇杆模式强制 pitch=0；阻止下蹲动画
    // ═══════════════════════════════════════
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getVehicle() instanceof ControlSeatEntity)) return;
        if (mc.screen != null) return;

        if (inputMode == 0) {
            mc.player.setXRot(0);
            mc.player.xRotO = 0;
            var vehicle = mc.player.getVehicle();
            if (vehicle != null) {
                float vy = vehicle.getYRot();
                mc.player.setYRot(vy); mc.player.yRotO = vy;
                mc.player.yHeadRot = vy; mc.player.yBodyRot = vy;
            }
        }

        // 渲染层阻止下蹲（同时防止 Shift 下马）
        if (mc.player.isShiftKeyDown()) {
            mc.player.setShiftKeyDown(false);
        }
    }
}
