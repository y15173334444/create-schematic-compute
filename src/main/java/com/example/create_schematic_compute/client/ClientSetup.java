package com.example.create_schematic_compute.client;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.BlueprintScreen;
import com.example.create_schematic_compute.blocks.ProgramComputerScreen;
import com.example.create_schematic_compute.blocks.SpeedProxyScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @net.neoforged.bus.api.SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(SchematicCompute.BLUEPRINT_MENU.get(), BlueprintScreen::new);
        event.register(SchematicCompute.SPEED_PROXY_MENU.get(), SpeedProxyScreen::new);
        event.register(SchematicCompute.PROGRAM_MENU.get(), ProgramComputerScreen::new);
    }
}
