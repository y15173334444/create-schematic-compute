package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.MonitorRedstoneSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.ByteArrayInputStream;

public class MonitorBlockEntity extends SyncedGraphBlockEntity {
    // Display settings (units in blocks)
    public float screenWidth = 1.5f, screenLength = 1.2f;
    public float screenX = 0f, screenY = 2.0f, screenZ = 0f;
    public float screenRoll = 0f, screenPitch = 0f, screenYaw = 0f;

    // HUD 模式（设置面板选项卡）与玻璃面板变换。
    // HUD mode (settings-panel tab) + glass panel transform — per docs/monitor-hud-mode-design.md §六.
    public boolean hudMode = false;          // 当前 tab（false=3D, true=HUD）/ current tab
    public float panelSizeX = 2.0f;          // 面板宽（方块单位）/ panel width (blocks)
    public float panelSizeY = 1.2f;          // 面板高（方块单位）/ panel height (blocks)
    public float panelOffsetX = 0.0f;        // 相对方块中心横向偏移 / lateral offset from block center
    public float panelOffsetY = 0.0f;        // 相对方块中心纵向偏移 / vertical offset from block center
    public float panelDistance = 0.05f;      // 沿 FACING 法线距离（≥~0.05 防 z-fighting）/ distance along FACING normal

    // 客户端 HUD 姿态标记平滑值（20Hz 数据 → 60fps 插值显示；transient 不存 NBT）。
    // Client-side smoothing for the HUD attitude marker (20Hz data → 60fps display;
    // transient, never serialized). NaN = 未初始化（首帧直接取目标值）。
    public float smoothPitch = Float.NaN;
    public float smoothRoll = Float.NaN;

    public MonitorBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.MONITOR_BE.get(), pos, s); }

    /** 工厂：Sable 加载时创建 compat 子类（sable$physicsTick 恢复结构上的 level
     *  引用——Sable 结构上的 BE level 可能为 null，不恢复会导致图不求值、内容不更新）。
     *  反射创建避免编译期硬依赖（与 Radar/Sensor 同模式）。
     *  Factory: creates the Sable-compat subclass when Sable is loaded (its
     *  sable$physicsTick restores the Level reference — on a Sable structure the BE's
     *  level may be nulled, which would stop graph evaluation / content updates).
     *  Reflection avoids a hard compile-time Sable dependency (same as Radar/Sensor). */
    public static MonitorBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.MonitorBlockEntitySable");
                return (MonitorBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Monitor: Sable factory failed for {}: {}", pos, e.toString());
        }
        return new MonitorBlockEntity(pos, state);
    }

    @Override public void accept(BlockEntity other) {
        if(other instanceof MonitorBlockEntity src) {
            unregisterBusChannels(graph); // 先注销旧图的 BUS 频道 / unregister old graph's BUS channels first
            this.graph = src.graph; this.running = src.running;
            this.screenWidth = src.screenWidth; this.screenLength = src.screenLength;
            this.screenX = src.screenX; this.screenY = src.screenY; this.screenZ = src.screenZ;
            this.screenRoll = src.screenRoll; this.screenPitch = src.screenPitch; this.screenYaw = src.screenYaw;
            this.hudMode = src.hudMode;
            this.panelSizeX = src.panelSizeX; this.panelSizeY = src.panelSizeY;
            this.panelOffsetX = src.panelOffsetX; this.panelOffsetY = src.panelOffsetY;
            this.panelDistance = src.panelDistance;
            runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void toggleRunning() { running = !running; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }

    public void tick() {
        if(level == null || level.isClientSide()) return;
        ensureBusRegistered();
        flushPendingFullSync(); // 合并冲刷显示拖拽的延迟全量同步（约 0.5Hz）/ flush coalesced display-drag full syncs (~0.5Hz)
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(MonitorBlock.LIT)) return;
        if(currentState.getValue(MonitorBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(MonitorBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(!running) { onStopRunning(); return; }
        if(graphChanged()) recompileEvaluatorLight();
        rs.refreshInputs();
        var in = rs.buildInputs(graph);
        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt);
        rs.writeOutputs(results);
        // Sync redstone inputs + eval snapshot to tracking clients
        if (level instanceof ServerLevel sl) {
            for (var e : rs.lastInputs().entrySet())
                PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                    new MonitorRedstoneSyncPacket(worldPosition, e.getKey(), e.getValue()));
            broadcastEvalSnapshot();
        }
        setChanged();
    }

    @Override public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                unregisterBusChannels(graph); // unregister old BUS channels before replacing graph
                // Do NOT call cleanupBusChannels — it broadcasts empty band syncs to clients,
                // permanently deleting BUS connections. Next tick's recompile restores correct bands.
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                // Force generation bump so graphChanged() triggers recompile + BUS re-registration.
                // 强制 bump 代数，确保下一 tick 重编译并重新注册 BUS 频道。
                graph.bumpGeneration();
                // 重置 lastGraphGeneration 为 -1（与基类/Blueprint 一致）：bump 到 1 可能
                // 与上次重编译留下的 lastGraphGeneration=1 冲突，graphChanged() 为 false
                // → 重编译（及 BUS 重注册）被跳过 → BUS_IN 读 0。
                // Reset lastGraphGeneration to -1 (consistent with base/Blueprint): bumping
                // to 1 can collide with the prior compile's lastGraphGeneration=1.
                lastGraphGeneration = -1;
            }
            if (t != null) loadSettings(t);
            rs.onLoad(graph);
            setChanged();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load monitor graph, resetting", e);
            graph = new NodeGraph();
            rs.onLoad(graph);
            setChanged();
        }
    }

    public void applySettings(float w, float l, float x, float y, float z, float r, float p, float yw,
                              boolean hudMode, float psx, float psy, float pox, float poy, float pd) {
        this.screenWidth = Math.max(0.1f, Math.min(10f, w)); this.screenLength = Math.max(0.1f, Math.min(10f, l));
        this.screenX = Math.max(-10f, Math.min(10f, x)); this.screenY = Math.max(-10f, Math.min(10f, y));
        this.screenZ = Math.max(-10f, Math.min(10f, z));
        this.screenRoll = r % 360f; this.screenPitch = p % 360f; this.screenYaw = yw % 360f;
        // HUD 面板参数 clamp：尺寸 0.1..10 方块、偏移 ±10、距离 ≥0.05（防 z-fighting）。
        // HUD panel clamps: size 0.1..10 blocks, offset ±10, distance >= 0.05 (anti z-fighting).
        this.hudMode = hudMode;
        this.panelSizeX = Math.max(0.1f, Math.min(10f, psx));
        this.panelSizeY = Math.max(0.1f, Math.min(10f, psy));
        this.panelOffsetX = Math.max(-10f, Math.min(10f, pox));
        this.panelOffsetY = Math.max(-10f, Math.min(10f, poy));
        this.panelDistance = Math.max(0.05f, Math.min(2f, pd));
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void saveSettings(CompoundTag t) {
        t.putFloat("ss_w", screenWidth); t.putFloat("ss_l", screenLength);
        t.putFloat("ss_x", screenX); t.putFloat("ss_y", screenY); t.putFloat("ss_z", screenZ);
        t.putFloat("ss_r", screenRoll); t.putFloat("ss_p", screenPitch); t.putFloat("ss_yw", screenYaw);
        // HUD 字段（可选键：旧档缺省 → 默认值，无需 DATA_VERSION 迁移）
        // HUD fields (optional keys: absent on legacy saves -> defaults, no migration needed)
        t.putBoolean("hm", hudMode);
        t.putFloat("ps_x", panelSizeX); t.putFloat("ps_y", panelSizeY);
        t.putFloat("po_x", panelOffsetX); t.putFloat("po_y", panelOffsetY);
        t.putFloat("pd", panelDistance);
    }
    public void loadSettings(CompoundTag t) {
        if (t.contains("ss_w")) screenWidth = t.getFloat("ss_w"); if (t.contains("ss_l")) screenLength = t.getFloat("ss_l");
        if (t.contains("ss_x")) screenX = t.getFloat("ss_x"); if (t.contains("ss_y")) screenY = t.getFloat("ss_y");
        if (t.contains("ss_z")) screenZ = t.getFloat("ss_z");
        if (t.contains("ss_r")) screenRoll = t.getFloat("ss_r"); if (t.contains("ss_p")) screenPitch = t.getFloat("ss_p");
        if (t.contains("ss_yw")) screenYaw = t.getFloat("ss_yw");
        if (t.contains("hm")) hudMode = t.getBoolean("hm");
        if (t.contains("ps_x")) panelSizeX = t.getFloat("ps_x"); if (t.contains("ps_y")) panelSizeY = t.getFloat("ps_y");
        if (t.contains("po_x")) panelOffsetX = t.getFloat("po_x"); if (t.contains("po_y")) panelOffsetY = t.getFloat("po_y");
        if (t.contains("pd")) panelDistance = t.getFloat("pd");
    }

    @Override protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        saveSettings(t);
        var inputs = new CompoundTag();
        for(var e : rs.lastInputs().entrySet()) inputs.putInt(String.valueOf(e.getKey()), e.getValue());
        t.put("rs_in", inputs);
    }
    @Override protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        loadSettings(t);
        if (t.contains("rs_in")) { var inputs = t.getCompound("rs_in"); for(var k : inputs.getAllKeys()) putRedstoneInput(Long.parseLong(k), inputs.getInt(k)); }
    }

    /** Always send full data — the graph is the authoritative source for in-world rendering.
     *  始终发送完整数据 — 图是世界内渲染的权威数据源。 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag(); saveAdditional(t, r); return t;
    }
}
