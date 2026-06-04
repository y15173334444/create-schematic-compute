package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
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
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SpeedProxyBlockEntity extends BlockEntity implements MenuProvider {
    private static final Map<Integer, Float> EMPTY_PID = Collections.emptyMap();
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    // 缓存的反射字段
    private static Class<?> speedControllerClass = null;
    private static Field targetSpeedField = null;
    private static Class<?> scrollValueClass = null;
    private static java.lang.reflect.Method setValueMethod = null;

    // 缓存的转速控制器位置（避免每 tick 扫描）
    private BlockPos cachedControllerPos = null;
    // 未找到控制器时的扫描冷却（tick）
    private int scanCooldown = 0;

    public SpeedProxyBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SPEED_PROXY_BE.get(), pos, s); }

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
        var results = evaluator.evaluate(List.of(), EMPTY_PID, 0.05f);
        for (var n : graph.nodes) {
            if (n.type == NodeType.SPEED_CTRL) {
                float speed = evaluator.getNodeOutput(n.id, 0);
                applySpeed((int) speed);
                break;
            }
        }
    }

    private void applySpeed(int targetRpm) {
        if (level == null) return;
        targetRpm = Math.max(-256, Math.min(256, targetRpm));
        // 延迟初始化反射
        if (speedControllerClass == null) {
            try {
                speedControllerClass = Class.forName("com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity");
                targetSpeedField = speedControllerClass.getDeclaredField("targetSpeed");
                targetSpeedField.setAccessible(true);
                scrollValueClass = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour");
                setValueMethod = scrollValueClass.getMethod("setValue", int.class);
            } catch (Exception e) {
                SchematicCompute.LOGGER.error("Failed to init SpeedController reflection", e);
                speedControllerClass = null;
                return;
            }
        }

        // 冷却期跳过扫描
        if (scanCooldown > 0) { scanCooldown--; return; }

        // 先尝试缓存的控制器位置
        if (cachedControllerPos != null) {
            BlockEntity be = level.getBlockEntity(cachedControllerPos);
            if (be != null && speedControllerClass.isInstance(be)) {
                setControllerSpeed(be, targetRpm);
                return;
            }
            cachedControllerPos = null; // 缓存失效，重新扫描
        }

        // 在 6 格范围内查找
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos p = worldPosition.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (be != null && speedControllerClass.isInstance(be)) {
                        cachedControllerPos = p;
                        setControllerSpeed(be, targetRpm);
                        return;
                    }
                }
            }
        }
        // 未找到，冷却 20 tick 后重试
        scanCooldown = 20;
    }

    private void setControllerSpeed(BlockEntity be, int targetRpm) {
        try {
            Object targetSpeed = targetSpeedField.get(be);
            setValueMethod.invoke(targetSpeed, targetRpm);
            be.setChanged();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to set SpeedController speed at {}", be.getBlockPos(), e);
            cachedControllerPos = null;
        }
    }

    public void loadGraphFromBytes(byte[] data) {
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

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) { super.saveAdditional(t,r); t.put("graph",graph.save(r)); t.putBoolean("running",running); }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) { super.loadAdditional(t,r); if(t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),r); } if(t.contains("running"))running=t.getBoolean("running"); }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".speed_proxy"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new SpeedProxyMenu(id, this); }
}
