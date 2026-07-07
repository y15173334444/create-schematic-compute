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
}
