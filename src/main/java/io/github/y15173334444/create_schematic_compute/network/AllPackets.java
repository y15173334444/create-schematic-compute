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
     * （2026-11-14 实测 16 个，控制台查询批后 17 个，拖动临场流批后 18 个）。玩家键取服务端权威 UUID（不信任包载荷），类型键取 TYPE
     * id 的 path。断开语义照原版：允许突发、桶耗尽拒绝（丢弃）、持续超限断开
     * （{@link C2SPacketRateGuard} 类注有完整机制说明与逐包预算表）。</p>
     * <p>The single rate-limit wrapper over the C→S registration face: every
     * {@code playToServer} line is wrapped here with a {@link C2SPacketRateGuard} check that
     * either lets the packet through to the original handler, drops it, or disconnects.
     * Registration discipline: <b>a new C→S packet must go through this wrapper</b> — the
     * check is {@code grep -c "rateLimited(" AllPackets.java} equal to the number of
     * {@code playToServer} registrations (16 as measured 2026-11-14, 17 after the console-query batch, 18 after the drag-stream batch). The player key is the
     * server-authoritative UUID (never the payload), the type key is the TYPE id's path.
     * Vanilla semantics: bursts allowed, an exhausted bucket rejects (drops), sustained
     * over-limit disconnects (full mechanism and per-type budget table in the
     * {@link C2SPacketRateGuard} class doc).</p>
     */
    private static <T extends CustomPacketPayload> IPayloadHandler<T> rateLimited(
            CustomPacketPayload.Type<T> type, C2SPacketRateGuard.Policy policy, IPayloadHandler<T> inner) {
        return rateLimited(type, policy, inner, null);
    }

    /**
     * 带丢弃回执的变体：桶耗尽拒绝时，若该类型带「被拒 op 提取器」，则以 **REJECT 契约回执**
     * 发回发起者。限流拒绝与 handler 级拒绝语义同构（op 未被应用），因此复用同一条 REJECT
     * 契约线——回滚乐观态 / 归还 pendingLocalOps / 终止 temp-id 挂起——客户端零新增状态面，
     * 也不需要新协议包（批 2，docs/packet-flood-rate-limit-plan.md §四的实现取型：以 REJECT
     * 同款回执代替专用丢弃包 + 序号登记表，少一个协议面且路径完全收口）。仅编辑 op opt-in
     * （其余 C→S 类型没有客户端挂起状态）；DISCONNECT 不回执（连接即将终止）。
     * <p>Variant with a drop receipt: when the bucket rejects and the type carries a refused-op
     * extractor, a <b>REJECT-contract receipt</b> goes back to the sender. A rate-limit drop is
     * semantically identical to a handler-level rejection (the op was not applied), so the same
     * REJECT contract line is reused — optimistic rollback / pendingLocalOps return / temp-id
     * wait termination — with zero new client state and no new protocol packet (batch 2, the
     * plan §4 implementation shape: a REJECT-shaped receipt instead of a dedicated drop packet
     * plus a sequence registry, one fewer protocol face and a fully closed path). Only edit ops
     * opt in (other C→S types track no client pending state); DISCONNECT sends no receipt (the
     * connection is terminating anyway).</p>
     */
    private static <T extends CustomPacketPayload> IPayloadHandler<T> rateLimited(
            CustomPacketPayload.Type<T> type, C2SPacketRateGuard.Policy policy, IPayloadHandler<T> inner,
            @org.jetbrains.annotations.Nullable java.util.function.Function<T, io.github.y15173334444.create_schematic_compute.graph.GraphOp> refusedOpExtractor) {
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
                    // 丢弃回执（opt-in）：限流拒绝 = op 未应用 = 与 handler 级拒绝同一条 REJECT
                    // 契约。静默丢弃会让客户端 pendingLocalOps 只增不减、闩死回弹保护（批 2 的
                    // 病灶），回执让它与普通被拒 op 同路径收敛。
                    // Drop receipt (opt-in): a rate-limit rejection means "not applied" — the
                    // same REJECT contract as handler-level refusals. A silent drop leaks the
                    // client's pendingLocalOps (latching the bounce-back guard — batch 2's
                    // target); the receipt converges it like any refused op.
                    if (refusedOpExtractor != null
                        && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                        var refused = refusedOpExtractor.apply(payload);
                        if (refused != null) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            sp, new GraphEditOpSyncPacket(
                                io.github.y15173334444.create_schematic_compute.graph.GraphOp.reject(
                                    refused, sp.getUUID())));
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
        // 批 2：编辑 op opt-in 丢弃回执——限流拒绝以 REJECT 契约回执发回（堵 pendingLocalOps
        // 静默泄漏），其余类型无客户端挂起状态、保持静默。
        // Batch 2: edit ops opt in to the drop receipt — a rate-limit rejection answers with a
        // REJECT-contract receipt (sealing the silent pendingLocalOps leak); other types track
        // no client pending state and stay silent.
        registrar.playToServer(GraphEditOpPacket.TYPE, GraphEditOpPacket.CODEC,
                rateLimited(GraphEditOpPacket.TYPE, C2SPacketRateGuard.EDIT_OP, GraphEditOpPacket::handleServer,
                        pkt -> pkt.op()));
        registrar.playToClient(GraphEditOpSyncPacket.TYPE, GraphEditOpSyncPacket.CODEC, GraphEditOpSyncPacket::handle);
        registrar.playToClient(GraphEditAckPacket.TYPE, GraphEditAckPacket.CODEC, GraphEditAckPacket::handle);
        // v1.2.4+: P2 Presence
        registrar.playToServer(GraphPresencePacket.TYPE, GraphPresencePacket.CODEC,
                rateLimited(GraphPresencePacket.TYPE, C2SPacketRateGuard.PRESENCE, GraphPresencePacket::handleServer));
        registrar.playToClient(GraphPresenceSyncPacket.TYPE, GraphPresenceSyncPacket.CODEC, GraphPresenceSyncPacket::handle);
        // 1.3.0 批 1（docs/packet-flood-rate-limit-plan.md §三）：拖动临场流——拖拽中的位置/尺寸
        // 更新离开 EDIT_OP 通道（服务端只中继不落图，松手即提交）。
        // 1.3.0 batch 1 (plan doc §3): the drag presence stream — transient drag position/size
        // updates leave the EDIT_OP channel (relay-only server, commit on release).
        registrar.playToServer(GraphDragStreamPacket.TYPE, GraphDragStreamPacket.CODEC,
                rateLimited(GraphDragStreamPacket.TYPE, C2SPacketRateGuard.DRAG_STREAM, GraphDragStreamPacket::handleServer));
        registrar.playToClient(GraphDragStreamSyncPacket.TYPE, GraphDragStreamSyncPacket.CODEC, GraphDragStreamSyncPacket::handle);
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
        // C 阶段步骤 5：图内控制台查询(C→S,限流——原始字符串进,文法唯一真相源在服务端
        // ConsoleProtocol) + 响应(S→C,文本行,发送前经 clampResponse 钳制)
        // Phase-C step 5: the in-editor console query (C→S, rate-limited — raw string in,
        // the grammar's single source of truth is server-side ConsoleProtocol) + response
        // (S→C, text lines, clamped by clampResponse before sending).
        registrar.playToServer(ConsoleQueryPacket.TYPE, ConsoleQueryPacket.CODEC,
                rateLimited(ConsoleQueryPacket.TYPE, C2SPacketRateGuard.CONSOLE_QUERY, ConsoleQueryPacket::handle));
        registrar.playToClient(ConsoleResponsePacket.TYPE, ConsoleResponsePacket.CODEC, ConsoleResponsePacket::handle);
    }
}
