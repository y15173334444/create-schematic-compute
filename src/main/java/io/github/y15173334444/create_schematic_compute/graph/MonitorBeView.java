package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;

/** 显示器 BE 面向显示编辑面板的**窄视图**（反向包边解环，2026-10-31 拍板）：面板只读
 *  屏幕几何、虚像缩放、HUD 模式、运行态、图与求值快照——不再点名
 *  `blocks.MonitorBlockEntity` 具体类型（E2 `EditAckSink` 同款缝-实现分离手法）。
 *  窄口住 `graph/` 公共域：签名含 `NodeGraph`/`EvalSnapshot` 等图域类型，须与
 *  `blocks.MonitorBlockEntity` 同处 Sable 跨类加载器的复制域（blocks/graph 子优先复制
 *  ——签名跨域共享会触发 JVM 加载器约束，见 SableDeviceNameCrossLoaderTest）。
 *  `blocks.MonitorBlockEntity` 实现本口（getter 直读既有公有字段/方法，零行为变更）。
 *  Narrow view of the monitor BE for the display-edit panels (reverse package-edge cycle
 *  break, 2026-10-31 ruling): the panels only read screen geometry, virtual-image scale,
 *  HUD mode, running state, the graph and the eval snapshot — they no longer name
 *  `blocks.MonitorBlockEntity` (the E2 `EditAckSink` playbook). The port lives in the
 *  `graph/` shared domain because its signatures carry graph-domain types, which must
 *  share the Sable cross-classloader duplication domain with `blocks.MonitorBlockEntity`
 *  (child-first copies of blocks/graph — a shared interface whose signatures cross
 *  domains trips the JVM loader constraint). `blocks.MonitorBlockEntity` implements it
 *  (getters read existing fields/methods verbatim — zero behaviour change).
 */
public interface MonitorBeView {

    /** 方块坐标 / the block position. */
    BlockPos getBlockPos();

    /** 当前图（可空）/ the current graph (nullable). */
    NodeGraph getNodeGraph();

    /** 客户端缓存的求值快照 / the client-cached eval snapshot. */
    EvalSnapshot getCachedEvalSnapshot();

    /** HUD 模式（渲染路径：false=3D 直接绘制, true=HUD 虚像）/ HUD mode (render path). */
    boolean isHudMode();

    /** 图是否在跑 / whether the graph is running. */
    boolean isRunning();

    /** 屏幕宽（方块）/ screen width in blocks. */
    float getScreenWidth();

    /** 屏幕长（方块）/ screen length in blocks. */
    float getScreenLength();

    float getScreenX();
    float getScreenY();
    float getScreenZ();
    float getScreenRoll();
    float getScreenPitch();
    float getScreenYaw();

    /** 虚拟图像缩放 / virtual image scale. */
    float getVirtualImageScale();
}
