package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.BlueprintScreen;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatScreen;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorScreen;
import io.github.y15173334444.create_schematic_compute.blocks.ProgramComputerScreen;
import io.github.y15173334444.create_schematic_compute.blocks.RadarScreen;
import io.github.y15173334444.create_schematic_compute.blocks.SensorScreen;
import io.github.y15173334444.create_schematic_compute.blocks.SpeedProxyScreen;
import io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity;
import io.github.y15173334444.create_schematic_compute.items.PortableTerminalItem;
import io.github.y15173334444.create_schematic_compute.network.ScanSableResponsePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.minecraft.client.resources.model.ModelResourceLocation;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    public static final ModelResourceLocation SCANNER_MODEL = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "block/radar_scanner"));

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(SCANNER_MODEL);
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ScanSableResponsePacket.clientHandler = PortableTerminalScreen::onSableScanResult;
            PortableTerminalItem.screenOpener = p -> Minecraft.getInstance().setScreen(new PortableTerminalScreen(p));
        });
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SchematicCompute.BLUEPRINT_MENU.get(), BlueprintScreen::new);
        event.register(SchematicCompute.SPEED_PROXY_MENU.get(), SpeedProxyScreen::new);
        event.register(SchematicCompute.PROGRAM_MENU.get(), ProgramComputerScreen::new);
        event.register(SchematicCompute.CONTROL_SEAT_MENU.get(), ControlSeatScreen::new);
        event.register(SchematicCompute.SENSOR_MENU.get(), SensorScreen::new);
        event.register(SchematicCompute.MONITOR_MENU.get(), MonitorScreen::new);
        event.register(SchematicCompute.RADAR_MENU.get(), RadarScreen::new);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SchematicCompute.CONTROL_SEAT_ENTITY.get(), NoRenderEntityRenderer::new);
    }

    /** 不渲染任何东西的实体渲染器 */
    public static class NoRenderEntityRenderer extends EntityRenderer<ControlSeatEntity> {
        public NoRenderEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }
        @Override public ResourceLocation getTextureLocation(ControlSeatEntity entity) { return ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "textures/entity/control_seat.png"); }
        @Override
        public void render(ControlSeatEntity entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffer, int light) {}
    }
}
