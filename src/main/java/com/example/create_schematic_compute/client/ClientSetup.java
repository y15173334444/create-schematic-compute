package com.example.create_schematic_compute.client;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.BlueprintScreen;
import com.example.create_schematic_compute.blocks.ControlSeatScreen;
import com.example.create_schematic_compute.blocks.ProgramComputerScreen;
import com.example.create_schematic_compute.blocks.SensorScreen;
import com.example.create_schematic_compute.blocks.SpeedProxyScreen;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @net.neoforged.bus.api.SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SchematicCompute.BLUEPRINT_MENU.get(), BlueprintScreen::new);
        event.register(SchematicCompute.SPEED_PROXY_MENU.get(), SpeedProxyScreen::new);
        event.register(SchematicCompute.PROGRAM_MENU.get(), ProgramComputerScreen::new);
        event.register(SchematicCompute.CONTROL_SEAT_MENU.get(), ControlSeatScreen::new);
        event.register(SchematicCompute.SENSOR_MENU.get(), SensorScreen::new);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SchematicCompute.CONTROL_SEAT_ENTITY.get(), NoRenderEntityRenderer::new);
    }

    /** 不渲染任何东西的实体渲染器 */
    public static class NoRenderEntityRenderer extends EntityRenderer<ControlSeatEntity> {
        public NoRenderEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }
        @Override public ResourceLocation getTextureLocation(ControlSeatEntity entity) { return null; }
    }
}
