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
        registrar.playToServer(
                ControlSeatInputPacket.TYPE,
                ControlSeatInputPacket.CODEC,
                ControlSeatInputPacket::handle
        );
        registrar.playToServer(
                MonitorSettingsPacket.TYPE,
                MonitorSettingsPacket.CODEC,
                MonitorSettingsPacket::handle
        );
        registrar.playToClient(
                MonitorRedstoneSyncPacket.TYPE,
                MonitorRedstoneSyncPacket.CODEC,
                MonitorRedstoneSyncPacket::handle
        );
        registrar.playToClient(
                RuntimeStateSyncPacket.TYPE,
                RuntimeStateSyncPacket.CODEC,
                RuntimeStateSyncPacket::handle
        );
        registrar.playToClient(
                BusBandSyncPacket.TYPE,
                BusBandSyncPacket.CODEC,
                BusBandSyncPacket::handle
        );
        registrar.playToServer(
                BusBandUploadPacket.TYPE,
                BusBandUploadPacket.CODEC,
                BusBandUploadPacket::handle
        );
        registrar.playToServer(
                RadarSettingsPacket.TYPE,
                RadarSettingsPacket.CODEC,
                RadarSettingsPacket::handle
        );
        registrar.playToServer(
                RadarLockPacket.TYPE,
                RadarLockPacket.CODEC,
                RadarLockPacket::handle
        );
        // v1.2.2: portable terminal packets
        registrar.playToServer(RequestGraphPacket.TYPE, RequestGraphPacket.CODEC, RequestGraphPacket::handle);
        registrar.playToClient(ResponseGraphPacket.TYPE, ResponseGraphPacket.CODEC, ResponseGraphPacket::handle);
        registrar.playToServer(SaveGraphPacket.TYPE, SaveGraphPacket.CODEC, SaveGraphPacket::handle);
        registrar.playToClient(SaveRejectedPacket.TYPE, SaveRejectedPacket.CODEC, SaveRejectedPacket::handle);
        // v1.2.2: Sable sub-level device scanning (server-side)
        registrar.playToServer(ScanSablePacket.TYPE, ScanSablePacket.CODEC, ScanSablePacket::handle);
        registrar.playToClient(ScanSableResponsePacket.TYPE, ScanSableResponsePacket.CODEC, ScanSableResponsePacket::handle);
        // v1.2.2: remote settings sync for terminal
        registrar.playToServer(RequestSettingsPacket.TYPE, RequestSettingsPacket.CODEC, RequestSettingsPacket::handle);
        registrar.playToClient(ResponseSettingsPacket.TYPE, ResponseSettingsPacket.CODEC, ResponseSettingsPacket::handle);
        registrar.playToServer(SaveSettingsPacket.TYPE, SaveSettingsPacket.CODEC, SaveSettingsPacket::handle);
    }
}
