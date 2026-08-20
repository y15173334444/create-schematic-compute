package io.github.y15173334444.create_schematic_compute.client.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom RenderType instances for the Monitor block entity renderer.
 * Uses NO_CULL to ensure content is visible from any angle.
 * Uses POSITION_COLOR_SHADER (rendertype_position_color) — a simple passthrough
 * shader that Iris/OptiFine preserve for debug overlays (F3 hitboxes). Does not
 * sample lightmap or texture, so the display is self-illuminated by default.
 */
public class MonitorRenderTypes {
    /** Borders and image pixels — world-space passthrough, no lightmap/texture needed */
    public static final RenderType SCREEN_PIXEL = RenderType.create(
        "monitor_pixel",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        65536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
    );

    /** 虚像画布内容：NO_DEPTH_TEST（深度测试恒通过）——HUD 虚像穿透地形/方块
     *  显示（真实 HUD 的无限远虚像不被世界遮挡）。仅虚像内容使用；近处屏幕
     *  （tint/边框）仍用 {@link #SCREEN_PIXEL}。
     *  Virtual-image canvas content: NO_DEPTH_TEST (depth test always passes) — the HUD
     *  virtual image pierces terrain/blocks (a real HUD's infinite-focus image is never
     *  occluded by the world). Only the virtual-image content uses this; the near screen
     *  (tint/border) keeps {@link #SCREEN_PIXEL}. */
    public static final RenderType SCREEN_PIXEL_NO_DEPTH = RenderType.create(
        "monitor_pixel_no_depth",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        65536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .createCompositeState(false)
    );

    /** 深度锚定虚像 RenderType（2026-08-21 几何等效）：**不再用自定义 shader**——
     *  Veil 4.0 拦截所有自定义 ShaderInstance 的绑定（GL_CURRENT_PROGRAM=0 /
     *  GL_INVALID_OPERATION 实证），自定义 shader 在此环境不可用。深度锚定改为
     *  **顶点级几何实现**：顶点构造为 V'=(farX·gz/farZ, farY·gz/farZ, gz)
     *  （gz=玻璃面板相机深度）→ 屏幕位置保持远处画布投影、NDC 深度 = 玻璃平面。
     *  因此这里直接用 vanilla position_color shader（Veil 正常编译/绑定），
     *  LEQUAL 深度测试（前方遮挡/后方不遮挡）+ COLOR_WRITE（不写深度）。
     *  Depth-anchored virtual-image RenderType (geometric equivalent): **no custom
     *  shader** — Veil 4.0 intercepts binding of any custom ShaderInstance (proven:
     *  GL_CURRENT_PROGRAM=0 / GL_INVALID_OPERATION spam), custom shaders are
     *  unusable here. Depth anchoring moved into the vertices: V'=(farX·gz/farZ,
     *  farY·gz/farZ, gz) (gz = glass panel camera depth) → screen position keeps
     *  the far-canvas projection, NDC depth = glass plane. So this uses the plain
     *  vanilla position_color shader (Veil compiles/binds it fine), LEQUAL depth
     *  test (near occludes / far does not) + COLOR_WRITE (no depth write). */
    public static final RenderType HUD_DEPTH_ANCHOR = RenderType.create(
        "monitor_hud_depth_anchor",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        65536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            // 只写颜色、不写深度：遮挡完全靠读场景深度缓冲（LEQUAL），虚像不污染
            // 深度缓冲（避免影响后续半透明排序/绘制）。
            // Color only, no depth write: occlusion comes purely from reading the
            // scene depth buffer (LEQUAL); the image never pollutes the depth buffer.
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false)
    );
}
