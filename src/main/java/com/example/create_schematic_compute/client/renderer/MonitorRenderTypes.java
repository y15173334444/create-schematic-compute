package com.example.create_schematic_compute.client.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * Custom RenderType instances for the Monitor block entity renderer.
 * Uses NO_CULL to ensure content is visible from any angle.
 */
public class MonitorRenderTypes {
    /** 边框和文字用 — 带光照贴图的半透明渲染，无面剔除 */
    public static final RenderType SCREEN_BORDER = RenderType.create(
        "monitor_border",
        DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
        VertexFormat.Mode.QUADS,
        65536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
    );

    /** 图像像素用 — 无光照贴图，颜色直接输出，避免光照变暗 */
    public static final RenderType SCREEN_PIXEL = RenderType.create(
        "monitor_pixel",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        4096,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_GUI_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
    );
}
