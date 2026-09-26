package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.KineticNetworkView;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动力仪表方块实体：串在轴线上的动力网络读数表 + 计算图宿主。
 * Kinetic gauge block entity: an in-line kinetic network readout + graph host.
 *
 * <p><b>读数</b>：转速/应力/容量全部来自 Create {@link KineticBlockEntity} 自身维护的
 * 网络状态（{@code getSpeed()} 与 {@code updateFromNetwork} 维护的 {@code stress} /
 * {@code capacity}），本方块不缓存、不自算 —— 网络合并/分裂/过载后下一个 tick
 * 读数自动正确（自愈式不变量）。STRESS / RPM 节点经 {@link KineticNetworkView}
 * 宿主注入读取同一份权威值。</p>
 * <p><b>Readings</b>: speed/stress/capacity all come from the network state Create's
 * {@link KineticBlockEntity} maintains itself ({@code getSpeed()} and the
 * {@code stress}/{@code capacity} kept fresh by {@code updateFromNetwork}) — this BE
 * neither caches nor recomputes them, so readings self-heal one tick after any network
 * merge/split/overload. The STRESS / RPM nodes read the same authoritative values
 * through the {@link KineticNetworkView} host injection.</p>
 *
 * <p><b>图托管</b>：组合线（{@link GraphHost}），与 Monitor 同款的轻量求值 tick ——
 * 屏幕内容 = 服务端求值快照（DATA/TEXT 节点）+ Create 同步的网络读数（内置读数），
 * 客户端绝不自算。</p>
 * <p><b>Graph hosting</b>: composition line ({@link GraphHost}) with the Monitor-style
 * light evaluation tick — screen content = server eval snapshot (DATA/TEXT nodes) +
 * Create-synced network readings (built-in readout); the client never recomputes.</p>
 */
public class KineticGaugeBlockEntity extends KineticBlockEntity
        implements GraphBlockEntity, KineticNetworkView {

    /** 组合式图托管核心。 Composition-based graph hosting core. */
    public final GraphHost host;

    public KineticGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(SchematicCompute.KINETIC_GAUGE_BE.get(), pos, state);
        this.host = new GraphHost(this);
        // 注入动力网络视图（GraphHost 每次重建求值器都会重放此回调）。
        // Inject the kinetic-network view (GraphHost replays this on every rebuild).
        this.host.setEvaluatorCustomizer(ev -> ev.setKineticNetworkView(this));
    }

    // ── 每 tick / per tick（Monitor 同款轻量路径 / Monitor-style light path）──

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;

        // 读数（转速/应力/容量）变化 → 主动推一次 BE 数据包（见 pushReadoutIfChanged 的完整理由）。
        // Push the readout to clients whenever it changes — see pushReadoutIfChanged.
        pushReadoutIfChanged();

        host.ensureBusRegistered();
        host.flushPendingFullSync();
        host.rs.checkGraphChanged(host.graph);
        if (host.graphChanged())
            host.recompileEvaluatorLight();
        if (!host.running) {
            host.onStopRunning();
            return;
        }
        host.rs.refreshInputs();
        var in = host.rs.buildInputs(host.graph);
        var results = host.evaluator.evaluate(in, host.runtimeState.pidState, 0.05f);
        host.rs.writeOutputs(results);
        host.broadcastEvalSnapshot();
        setChanged();
    }

    // ── 读数同步 / readout sync ──

    /** 上一次推给客户端的读数（NaN = 还没推过）/ last readout pushed to clients. */
    private float lastPushedSpeed = Float.NaN;
    private float lastPushedStress = Float.NaN;
    private float lastPushedCapacity = Float.NaN;

    /**
     * 读数变化时主动同步给客户端。
     * Push the readout to clients when it changes.
     *
     * <p><b>为什么必须自己做</b>：Create 的 {@link KineticBlockEntity} 只在**转速**变化时自动
     * {@code sendData()}，应力/容量变化不发 —— 于是屏幕上的应力条会一直停在旧值，直到玩家打开图编辑器
     * （触发一次全量同步）或转速碰巧变了。2026-09-13 实测："不打开图 / 不改变转速就不刷新"。
     * 这里按"变化才推"补齐；本类的 {@link #write} 仍然只在全量同步挂起时才携带整图，所以这个额外
     * 数据包不会冲掉正在进行的编辑。</p>
     * <p><b>Why this is needed</b>: Create only auto-syncs on a **speed** change; stress/capacity
     * changes are not pushed, so the on-screen stress bar froze at its old value until the graph
     * editor was opened (full sync) or the speed happened to change. This pushes on any readout
     * change; {@link #write} still ships the whole graph only while a full sync is pending, so the
     * extra packet cannot clobber edits in flight.</p>
     */
    private void pushReadoutIfChanged() {
        float speed = getSpeed();
        if (speed == lastPushedSpeed && stress == lastPushedStress && capacity == lastPushedCapacity)
            return;
        lastPushedSpeed = speed;
        lastPushedStress = stress;
        lastPushedCapacity = capacity;
        sendData();
    }

    // ── KineticNetworkView：STRESS / RPM 节点宿主视图 / host view for STRESS / RPM ──

    @Override public float kineticSpeed() { return getSpeed(); }

    @Override public float kineticStress() { return stress; }

    @Override public float kineticCapacity() { return capacity; }

    // ── 屏幕读数（客户端 BER 消费 Create 已同步的网络字段 / BER reads the
    //    Create-synced network fields; nothing is recomputed client-side）──

    /** 网络转速（RPM，带符号）。 Network speed (RPM, signed). */
    public float getGaugeSpeed() { return getSpeed(); }

    /** 网络已用应力（SU）。 Network used stress (SU). */
    public float getGaugeStress() { return stress; }

    /** 网络应力容量（SU）。 Network stress capacity (SU). */
    public float getGaugeCapacity() { return capacity; }

    /** 应力占比（0..1；无网络/零容量时为 0）。 Stress ratio (0..1; 0 offline/zero capacity). */
    public float getGaugeRatio() {
        return capacity > 0 ? stress / capacity : 0f;
    }

    // ── goggle 面板 / goggle overlay ──

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("│ Kinetic Gauge").withStyle(ChatFormatting.GOLD));
        float cap = capacity;
        float ratio = cap > 0 ? stress / cap : 0f;
        tooltip.add(Component.literal(String.format(Locale.ROOT,
                "│ speed: %d rpm", (int) getSpeed())).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal(String.format(Locale.ROOT,
                "│ stress: %s / %s SU (%.0f%%)",
                (int) stress, (int) cap, ratio * 100)).withStyle(
                isOverStressed() ? ChatFormatting.RED : ChatFormatting.GRAY));
        return true;
    }

    // ── 生命周期 / lifecycle ──

    @Override public void onLoad() { super.onLoad(); host.onHostLoad(); }

    @Override public void onChunkUnloaded() { host.onHostChunkUnloaded(); super.onChunkUnloaded(); }

    @Override public void remove() { host.onHostRemoved(); super.remove(); }

    // ── NBT（Create 的 saveAdditional/read 为 final，钩子在 write/read）──

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        // 与 CncGearbox 同策略：客户端数据包只在显式全量同步挂起时携带整图 ——
        // Create 在每次转速变化都会 sendData，整图随包会冲掉正在进行的编辑。
        // Same policy as CncGearbox: client packets ship the full graph only while an
        // explicit full sync is pending — Create fires sendData on every speed change
        // and graph-in-every-packet clobbers edits in flight.
        if (!clientPacket || host.isFullSyncPending()) {
            host.saveHostNBT(tag, registries);
        } else {
            tag.putBoolean("running", host.running);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        host.loadHostNBT(tag, registries);
    }

    // ── GraphBlockEntity 桥接 / interface bridges ──

    @Override public NodeGraph getNodeGraph() { return host.graph; }

    @Override public boolean isRunning() { return host.isRunning(); }

    @Override public void setRunning(boolean r) { host.setRunning(r); }

    @Override public boolean graphHasCycles() { return host.graphHasCycles(); }

    @Override public void clearPidState() { host.clearPidState(); }

    @Override public void syncFlipflopStates(Map<Integer, Boolean> states) { host.syncFlipflopStates(states); }

    @Override public void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) { host.syncSubFlipflopStates(subStates); }

    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) { host.syncBusBandsFromServer(busName, bands); }

    @Override public EvalSnapshot getCachedEvalSnapshot() { return host.getCachedEvalSnapshot(); }

    @Override public void setCachedEvalSnapshot(EvalSnapshot snapshot) { host.setCachedEvalSnapshot(snapshot); }

    @Override public int getPendingLocalOps() { return host.getPendingLocalOps(); }

    @Override public void setPendingLocalOps(int value) { host.setPendingLocalOps(value); }

    @Override public void flagFullSync() { host.flagFullSync(); }

    @Override public void requestFullSync() { host.requestFullSync(); }

    @Override public boolean isGraphReady() { return host.isGraphReady(); }

    @Override public Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) { return host.peekSubStateFlipflops(encapNodeId); }

    @Override public Map<Integer, Boolean> getFlipflopStates() { return host.getFlipflopStates(); }

    // ── GraphBlockEntity 宿主绑定回调 / contract host-binding callbacks ──

    @Override public BlockEntity asBlockEntity() { return this; }

    @Override public net.minecraft.world.level.Level getLevel() { return level; }

    @Override public BlockPos getBlockPos() { return worldPosition; }

    @Override public void setChanged() { super.setChanged(); }

    @Override public void sendBlockUpdated() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
