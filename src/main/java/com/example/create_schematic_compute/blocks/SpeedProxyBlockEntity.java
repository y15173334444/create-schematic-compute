package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

public class SpeedProxyBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE {
    // PID 积分状态（实例级，避免 static 共享导致状态污染）
    public final Map<Integer, Float> pidState = new java.util.HashMap<>();
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    // 反射缓存：SpeedControllerBlockEntity 的 targetSpeed 字段
    private static Class<?> speedControllerClass = null;
    private static java.lang.reflect.Field targetSpeedField = null;

    // 缓存的转速控制器位置（避免每 tick 扫描）
    private BlockPos cachedControllerPos = null;
    // 未找到控制器时的扫描冷却（tick）
    private int scanCooldown = 0;

    public SpeedProxyBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SPEED_PROXY_BE.get(), pos, s); }

    @Override public void accept(net.minecraft.world.level.block.entity.BlockEntity other) {
        if(other instanceof SpeedProxyBlockEntity src) {
            this.graph = src.graph;
            this.running = src.running;
            setChanged();
        }
    }

    @Override public void onLoad() { super.onLoad(); }

    public void tick() {
        if (level == null || level.isClientSide()) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var state = getBlockState();
        if (state.getValue(SpeedProxyBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, state.setValue(SpeedProxyBlock.LIT, shouldBeLit), 3);
        if (!running) return;
        if (evaluator == null || lastEvaluatedGraph != graph) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
        }
        var results = evaluator.evaluate(List.of(), pidState, 0.05f);
        for (var n : graph.nodes) {
            if (n.type == NodeType.SPEED_CTRL) {
                float speed = evaluator.getNodeOutput(n.id, 0);
                applySpeed((int) speed);
                break;
            }
        }
    }

    /** 初始化反射（第一次使用时延迟加载） */
    private static boolean initReflection() {
        if (speedControllerClass != null) return true;
        try {
            speedControllerClass = Class.forName("com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity");
            targetSpeedField = speedControllerClass.getField("targetSpeed"); // public 字段，无需 setAccessible
            return true;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to init SpeedController reflection", e);
            return false;
        }
    }

    /** 判断方块实体是否为转速控制器并设置转速 */
    private static boolean trySetSpeed(BlockEntity be, int rpm) {
        if (!initReflection() || be == null) return false;
        if (!speedControllerClass.isInstance(be)) return false;
        try {
            Object scrollValue = targetSpeedField.get(be);
            // ScrollValueBehaviour.setValue(int) 是 public 方法
            scrollValue.getClass().getMethod("setValue", int.class).invoke(scrollValue, rpm);
            be.setChanged();
            return true;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to set SpeedController speed", e);
            return false;
        }
    }

    private void applySpeed(int targetRpm) {
        if (level == null || !initReflection()) return;
        targetRpm = Math.max(-256, Math.min(256, targetRpm));

        // 先尝试缓存的控制器位置（不受冷却影响）
        if (cachedControllerPos != null) {
            if (trySetSpeed(level.getBlockEntity(cachedControllerPos), targetRpm))
                return;
            cachedControllerPos = null; // 缓存失效，继续扫描
        }

        // 冷却期跳过扫描（仅在没有缓存时生效）
        if (scanCooldown > 0) { scanCooldown--; return; }

        // 在 6 个相邻面查找转速控制器
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos p = worldPosition.relative(dir);
            if (trySetSpeed(level.getBlockEntity(p), targetRpm)) {
                cachedControllerPos = p;
                return;
            }
        }
        // 未找到，冷却 20 tick 后重试
        scanCooldown = 20;
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
            }
            setChanged();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load speed proxy graph, resetting", e);
            graph = new NodeGraph();
            setChanged();
        }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            graph = NodeGraph.load(t.getCompound("graph"), r);
        }
        if (t.contains("running")) running = t.getBoolean("running");
        setChanged();
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".speed_proxy"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new SpeedProxyMenu(id, this); }
}
