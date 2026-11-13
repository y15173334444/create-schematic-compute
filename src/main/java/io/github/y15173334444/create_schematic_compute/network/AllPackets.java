package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID)
public class AllPackets {
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，headless/JUnit
     *  下起不来。日志名为本类 FQCN。 / Same-name standalone logger: never references the @Mod
     *  class — its static init needs the Minecraft registries, while LoggerFactory does not,
     *  so class init stays safe in plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AllPackets.class);

    /** 限流拒绝的 DEBUG 节流戳（照 GraphEditOpPacket 的 TEXT 帽告警纪律）：逐包 DEBUG 会
     *  在洪水下刷盘——洪水每 tick 几百包，这里每 60s 最多一条 DEBUG 提示「有人在持续被拒」，
     *  断开另有 WARN。仅日志语义，无行为状态。 / Wall-clock throttle stamp for the
     *  rate-limit DEBUG line (the GraphEditOpPacket TEXT-cap discipline): a per-packet DEBUG
     *  floods the log under a flood (hundreds of packets per tick), so at most one DEBUG per
     *  60s signals "someone is being rejected continuously"; the disconnect emits its own
     *  WARN. Logging-only, no behavioural state. */
    private static volatile long lastRateDebugMs = Long.MIN_VALUE;
    private static final long RATE_DEBUG_INTERVAL_MS = 60_000;

    /**
     * C→S 注册面的统一限流包装器：每个 {@code playToServer} 注册线都经这里包一层
     * {@link C2SPacketRateGuard} 检查，再决定放行进原 handler、丢弃还是断开。
     * <p>注册纪律：<b>新增 C→S 包必须走本包装器</b>——检查方式 =
     * {@code grep -c "rateLimited(" AllPackets.java} 应等于 {@code playToServer} 的注册数
     * （2026-11-14 实测 16 个）。玩家键取服务端权威 UUID（不信任包载荷），类型键取 TYPE
     * id 的 path。断开语义照原版：允许突发、桶耗尽拒绝（丢弃）、持续超限断开
     * （{@link C2SPacketRateGuard} 类注有完整机制说明与逐包预算表）。</p>
     * <p>The single rate-limit wrapper over the C→S registration face: every
     * {@code playToServer} line is wrapped here with a {@link C2SPacketRateGuard} check that
     * either lets the packet through to the original handler, drops it, or disconnects.
     * Registration discipline: <b>a new C→S packet must go through this wrapper</b> — the
     * check is {@code grep -c "rateLimited(" AllPackets.java} equal to the number of
     * {@code playToServer} registrations (16 as measured 2026-11-14). The player key is the
     * server-authoritative UUID (never the payload), the type key is the TYPE id's path.
     * Vanilla semantics: bursts allowed, an exhausted bucket rejects (drops), sustained
     * over-limit disconnects (full mechanism and per-type budget table in the
     * {@link C2SPacketRateGuard} class doc).</p>
     */
    private static <T extends CustomPacketPayload> IPayloadHandler<T> rateLimited(
            CustomPacketPayload.Type<T> type, C2SPacketRateGuard.Policy policy, IPayloadHandler<T> inner) {
        return (payload, ctx) -> {
            String playerKey = ctx.player().getUUID().toString();
            C2SPacketRateGuard.Verdict verdict =
                C2SPacketRateGuard.SERVER.check(playerKey, type.id().getPath(), policy);
            switch (verdict) {
                case ALLOWED -> inner.handle(payload, ctx);
                case REJECTED -> {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastRateDebugMs >= RATE_DEBUG_INTERVAL_MS) {
                        lastRateDebugMs = nowMs;
                        LOGGER.debug("Rate limit rejecting packets from {} on {} (sustained flood?); "
                                + "this line is throttled to one per {} ms",
                            playerKey, type.id(), RATE_DEBUG_INTERVAL_MS);
                    }
                }
                case DISCONNECT -> {
                    // 一条 WARN = 一次逐出（连接随之终止，天然自节流）。拒绝期间保护早已生效
                    // （包在 handler 前被丢弃）——断开是对持续灌包者的清理，不是保护本身。
                    // One WARN = one eviction (the connection dies here, self-throttling).
                    // Protection was already active throughout the rejection window (packets
                    // dropped before any handler) — the disconnect is cleanup of a sustained
                    // flooder, not the protection itself.
                    LOGGER.warn("Disconnecting {} — sustained rate-limit violations on C→S packets ({})",
                        playerKey, type.id());
                    ctx.disconnect(Component.literal(
                        "Kicked for sending custom packets too fast (rate limit).\n"
                        + "发送自定义包过于频繁（超过服务端限流），已被断开。"));
                }
            }
        };
    }

    /** 注册面：S→C 包不做限流（服务端出口由既有客户端节流与同步节奏控制，不在本威胁面
     *  内）；全部 {@code playToServer} 一律经 {@link #rateLimited} 包装。 / The registration
     *  face: S→C packets are not rate-limited (server egress is governed by the existing
     *  client-side throttles and sync cadences, outside this threat surface); every
     *  {@code playToServer} goes through {@link #rateLimited}. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(SchematicCompute.MOD_ID);
        // 保存/编译：只带坐标的请求，服务端在权威图上执行编译语义——不再上传整图（issue #17）
        // Save/compile: position-only request; the server runs compile semantics on its own graph (issue #17)
        registrar.playToServer(
                GraphSaveRequestPacket.TYPE,
                GraphSaveRequestPacket.CODEC,
                rateLimited(GraphSaveRequestPacket.TYPE, C2SPacketRateGuard.SAVE_REQUEST, GraphSaveRequestPacket::handle)
        );
        registrar.playToServer(
                BlueprintTogglePacket.TYPE,
                BlueprintTogglePacket.CODEC,
                rateLimited(BlueprintTogglePacket.TYPE, C2SPacketRateGuard.BLUEPRINT_TOGGLE, BlueprintTogglePacket::handle)
        );
        registrar.playToServer(
                ControlSeatInputPacket.TYPE,
                ControlSeatInputPacket.CODEC,
                rateLimited(ControlSeatInputPacket.TYPE, C2SPacketRateGuard.SEAT_INPUT, ControlSeatInputPacket::handle)
        );
        registrar.playToServer(
                MonitorSettingsPacket.TYPE,
                MonitorSettingsPacket.CODEC,
                rateLimited(MonitorSettingsPacket.TYPE, C2SPacketRateGuard.MONITOR_SETTINGS, MonitorSettingsPacket::handle)
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
        registrar.playToClient(
                PrivateChannelTypePacket.TYPE,
                PrivateChannelTypePacket.CODEC,
                PrivateChannelTypePacket::handle
        );
        registrar.playToClient(
                SpectrumSyncPacket.TYPE,
                SpectrumSyncPacket.CODEC,
                SpectrumSyncPacket::handle
        );
        registrar.playToServer(
                BusBandUploadPacket.TYPE,
                BusBandUploadPacket.CODEC,
                rateLimited(BusBandUploadPacket.TYPE, C2SPacketRateGuard.BAND_UPLOAD, BusBandUploadPacket::handle)
        );
        registrar.playToServer(
                RadarSettingsPacket.TYPE,
                RadarSettingsPacket.CODEC,
                rateLimited(RadarSettingsPacket.TYPE, C2SPacketRateGuard.RADAR_SETTINGS, RadarSettingsPacket::handle)
        );
        registrar.playToServer(
                SpeakerSettingsPacket.TYPE,
                SpeakerSettingsPacket.CODEC,
                rateLimited(SpeakerSettingsPacket.TYPE, C2SPacketRateGuard.SPEAKER_SETTINGS, SpeakerSettingsPacket::handle)
        );
        // v1.2.6.1: amplifier lid-spectrum monitor target (C→S)
        registrar.playToServer(
                AmplifierSettingsPacket.TYPE,
                AmplifierSettingsPacket.CODEC,
                rateLimited(AmplifierSettingsPacket.TYPE, C2SPacketRateGuard.AMPLIFIER_SETTINGS, AmplifierSettingsPacket::handle)
        );
        registrar.playToServer(
                RadarLockPacket.TYPE,
                RadarLockPacket.CODEC,
                rateLimited(RadarLockPacket.TYPE, C2SPacketRateGuard.RADAR_LOCK, RadarLockPacket::handle)
        );
        // v1.2.2: Sable sub-level device scanning
        registrar.playToServer(ScanSablePacket.TYPE, ScanSablePacket.CODEC,
                rateLimited(ScanSablePacket.TYPE, C2SPacketRateGuard.SCAN_SABLE, ScanSablePacket::handle));
        registrar.playToClient(ScanSableResponsePacket.TYPE, ScanSableResponsePacket.CODEC, ScanSableResponsePacket::handle);
        // v1.2.4+: Multiplayer collaboration
        registrar.playToServer(GraphEditOpPacket.TYPE, GraphEditOpPacket.CODEC,
                rateLimited(GraphEditOpPacket.TYPE, C2SPacketRateGuard.EDIT_OP, GraphEditOpPacket::handleServer));
        registrar.playToClient(GraphEditOpSyncPacket.TYPE, GraphEditOpSyncPacket.CODEC, GraphEditOpSyncPacket::handle);
        registrar.playToClient(GraphEditAckPacket.TYPE, GraphEditAckPacket.CODEC, GraphEditAckPacket::handle);
        // v1.2.4+: P2 Presence
        registrar.playToServer(GraphPresencePacket.TYPE, GraphPresencePacket.CODEC,
                rateLimited(GraphPresencePacket.TYPE, C2SPacketRateGuard.PRESENCE, GraphPresencePacket::handleServer));
        registrar.playToClient(GraphPresenceSyncPacket.TYPE, GraphPresenceSyncPacket.CODEC, GraphPresenceSyncPacket::handle);
        registrar.playToServer(GraphJoinPacket.TYPE, GraphJoinPacket.CODEC,
                rateLimited(GraphJoinPacket.TYPE, C2SPacketRateGuard.JOIN, GraphJoinPacket::handle));
        registrar.playToServer(GraphLeavePacket.TYPE, GraphLeavePacket.CODEC,
                rateLimited(GraphLeavePacket.TYPE, C2SPacketRateGuard.LEAVE, GraphLeavePacket::handle));
        // v1.2.4+: Server-authoritative evaluation sync
        registrar.playToClient(ClientboundGraphEvalPacket.TYPE, ClientboundGraphEvalPacket.CODEC, ClientboundGraphEvalPacket::handle);
        // v1.2.6: radar light targets channel (S→C) — 20Hz blip positions without the whole-graph NBT
        registrar.playToClient(RadarTargetsPacket.TYPE, RadarTargetsPacket.CODEC, RadarTargetsPacket::handle);
        // v1.2.6: audio note-event dispatch (S→C)
        registrar.playToClient(NoteEventPacket.TYPE, NoteEventPacket.CODEC, NoteEventPacket::handle);
        // v1.2.6: music stop marker (S→C) — cancel the speaker's queued unplayed voices
        registrar.playToClient(MusicStopPacket.TYPE, MusicStopPacket.CODEC, MusicStopPacket::handle);
        // v1.2.4+: Blob data channel (C→S and S→C use separate types per NeoForge requirement)
        registrar.playToServer(BlobDataPacket.TYPE, BlobDataPacket.CODEC,
                rateLimited(BlobDataPacket.TYPE, C2SPacketRateGuard.BLOB_CHUNK, BlobPacketHandler::handleServer));
        registrar.playToClient(BlobDataSyncPacket.TYPE, BlobDataSyncPacket.CODEC,
            (pkt, ctx) -> BlobPacketHandler.handleClient(pkt.inner(), ctx));
        // K6 交互面:公式预算调整(C→S,OP 服务端裁定) + 权威值同步(S→C) + /cscsettings 开面板(S→C)
        // The K6 interaction surface: budget adjust (C→S, server-ruled OP) + authoritative sync
        // (S→C) + the /cscsettings panel-open (S→C).
        registrar.playToServer(BudgetSetPacket.TYPE, BudgetSetPacket.CODEC,
                rateLimited(BudgetSetPacket.TYPE, C2SPacketRateGuard.BUDGET_SET, BudgetSetPacket::handle));
        registrar.playToClient(BudgetSyncPacket.TYPE, BudgetSyncPacket.CODEC, BudgetSyncPacket::handle);
        registrar.playToClient(OpenSettingsPacket.TYPE, OpenSettingsPacket.CODEC, OpenSettingsPacket::handle);
    }
}
