package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID)
public class AllPackets {
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(SchematicCompute.MOD_ID);
        registrar.playToServer(
                BlueprintSavePacket.TYPE,
                BlueprintSavePacket.CODEC,
                BlueprintSavePacket::handle
        );
        registrar.playToServer(
                BlueprintTogglePacket.TYPE,
                BlueprintTogglePacket.CODEC,
                BlueprintTogglePacket::handle
        );
    }
}
