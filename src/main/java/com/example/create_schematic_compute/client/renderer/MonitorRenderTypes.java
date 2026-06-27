package com.example.create_schematic_compute.client.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * Custom RenderType instances for the Monitor block entity renderer.
 * All shaders are chosen for Iris/OptiFine compatibility (no custom GLSL).
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

    /** Textured version — 1 quad per IMAGE node instead of 256 quads.
     *  Uses POSITION_TEX format and creates a unique RenderType per texture location,
     *  enabling the batching system to group draws by texture. */
    public static final Function<ResourceLocation, RenderType> SCREEN_PIXEL_TEX =
        Util.memoize(loc -> RenderType.create(
            "monitor_pixel_tex",
            DefaultVertexFormat.POSITION_TEX,
            VertexFormat.Mode.QUADS,
            4096,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.POSITION_TEX_SHADER)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setTextureState(new RenderStateShard.TextureStateShard(loc, false, false))
                .createCompositeState(false)
        ));
}
