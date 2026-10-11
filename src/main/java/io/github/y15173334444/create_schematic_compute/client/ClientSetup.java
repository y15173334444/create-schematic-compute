package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;
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
import net.minecraft.client.resources.model.ModelResourceLocation;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    public static final ModelResourceLocation SCANNER_MODEL = ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "block/radar_scanner"));

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(SCANNER_MODEL);
        event.register(ModelResourceLocation.standalone(CncGearboxVisual.FRONT_SHAFT.modelLocation()));
        event.register(ModelResourceLocation.standalone(CncGearboxVisual.REAR_SHAFT.modelLocation()));
        event.register(ModelResourceLocation.standalone(TransmissionVisual.FRONT_SHAFT.modelLocation()));
        event.register(ModelResourceLocation.standalone(TransmissionVisual.REAR_SHAFT.modelLocation()));
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // E2：注册客户端 ACK 汇点 —— GraphEditAckPacket（network）→ GraphEditor（UI）
            // 的解耦路由，网络层不再 import 编辑器。
            // E2: register the client ACK sink — the decoupled route from
            // GraphEditAckPacket (network) to GraphEditor (UI); the network layer no
            // longer imports the editor.
            io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket
                .setClientSink(new GraphAckRouter());
            ScanSableResponsePacket.clientHandler = PortableTerminalScreen::onSableScanResult;
            PortableTerminalItem.screenOpener = p -> Minecraft.getInstance().setScreen(new PortableTerminalScreen(p));
            // CNC 齿轮箱 Flywheel 视觉（两端独立旋转体）；Flywheel 不可用时 vanilla renderer 兜底
            dev.engine_room.flywheel.api.visualization.VisualizerRegistry.setVisualizer(
                SchematicCompute.CNC_GEARBOX_BE.get(),
                new dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer<>() {
                    @Override
                    public dev.engine_room.flywheel.api.visual.BlockEntityVisual<? super CncGearboxBlockEntity>
                    createVisual(dev.engine_room.flywheel.api.visualization.VisualizationContext ctx,
                                 CncGearboxBlockEntity be, float partialTick) {
                        return new CncGearboxVisual(ctx, be, partialTick);
                    }

                    @Override
                    public boolean skipVanillaRender(CncGearboxBlockEntity be) {
                        return be.getLevel() != null
                            && dev.engine_room.flywheel.api.visualization.VisualizationManager
                                .supportsVisualization(be.getLevel());
                    }
                });
            // 可编程变速器 Flywheel 视觉（CNC 同款轴承效果：两端独立旋转体，输入端随
            // 网络、输出端随程序目标转速）；Flywheel 不可用时 vanilla renderer 兜底
            dev.engine_room.flywheel.api.visualization.VisualizerRegistry.setVisualizer(
                SchematicCompute.TRANSMISSION_BE.get(),
                new dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer<>() {
                    @Override
                    public dev.engine_room.flywheel.api.visual.BlockEntityVisual<? super ProgrammableTransmissionBlockEntity>
                    createVisual(dev.engine_room.flywheel.api.visualization.VisualizationContext ctx,
                                 ProgrammableTransmissionBlockEntity be, float partialTick) {
                        return new TransmissionVisual(ctx, be, partialTick);
                    }

                    @Override
                    public boolean skipVanillaRender(ProgrammableTransmissionBlockEntity be) {
                        return be.getLevel() != null
                            && dev.engine_room.flywheel.api.visualization.VisualizationManager
                                .supportsVisualization(be.getLevel());
                    }
                });
        });
    }
    /**
     * 客户端退世界：清空客户端雷达注册表（P2-15）。与 SchematicCompute 里
     * {@code GraphEditor.clearTempView()} 的 LoggingOut 清理同事件——区块卸载钩子之外的
     * 兜底，防止换服务器/换世界残留指向已释放 Level 的死雷达供锁定 UI 遍历。
     * Client leaves the world: clear the client radar registry (P2-15). Same
     * {@code LoggingOut} event as SchematicCompute's {@code GraphEditor.clearTempView()}
     * cleanup — a backstop beyond the chunk-unload hook so switching servers/worlds never
     * leaves dead radars pointing at a released Level for the lock UI to iterate.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onClientLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        RadarBlockEntity.clearClientRadars();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SchematicCompute.CONTROL_SEAT_ENTITY.get(), NoRenderEntityRenderer::new);
        event.registerBlockEntityRenderer(SchematicCompute.CNC_GEARBOX_BE.get(), CncGearboxRenderer::new);
        event.registerBlockEntityRenderer(SchematicCompute.TRANSMISSION_BE.get(), TransmissionRenderer::new);
    }

    /** 不渲染任何东西的实体渲染器 */
    public static class NoRenderEntityRenderer extends EntityRenderer<ControlSeatEntity> {
        public NoRenderEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }
        @Override public ResourceLocation getTextureLocation(ControlSeatEntity entity) { return ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "textures/entity/control_seat.png"); }
        @Override
        public void render(ControlSeatEntity entity, float yaw, float partialTick, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffer, int light) {}
    }
}
