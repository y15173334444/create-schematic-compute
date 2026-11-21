package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.EditSessionTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

/**
 * 泛用屏开启器（宿主套件波 2）：{@code api.HostGraphs.openEditor} 的 client-only
 * 实现落点 —— {@code ClientSetup} 在客户端初始化时经
 * {@code HostGraphs.registerEditorOpener(HostGraphScreens::open)} 注册。专用服务器上
 * 钩子恒空（openEditor 抛 ISE）；api 包不 import client 类件的纪律由这条委托边维持。
 * The generic-screen opener (host suite, wave 2; consuming through the
 * HostGraphView narrow port, no blocks imports): the client-only implementation
 * registered into {@code api.HostGraphs.openEditor} by {@code ClientSetup} at client
 * init ({@code HostGraphs.registerEditorOpener(HostGraphScreens::open)}). The hook stays
 * empty on dedicated servers (openEditor throws there); the api-never-imports-client
 * discipline rides this delegate edge.
 */
public final class HostGraphScreens {

    private HostGraphScreens() {}

    /**
     * 打开目标坐标的泛用图屏；目标不是可解析宿主时静默返回（openEditor 契约：没有
     * 可开之图不是错误）。
     * Opens the generic graph screen at the target; silently returns when the target is
     * not a resolvable host (the openEditor contract: no editor to open is not an
     * error).
     */
    public static void open(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;
        var be = EditSessionTargets.resolve(mc.level.getBlockEntity(pos));
        if (!(be instanceof HostGraphView)) return;
        mc.setScreen(new HostGraphScreen(pos));
    }
}
