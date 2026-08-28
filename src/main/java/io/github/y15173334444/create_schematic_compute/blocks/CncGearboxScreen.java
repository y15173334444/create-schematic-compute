package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 数控齿轮箱（运动块）图编辑器。节点白名单 = 通用逻辑 + 运动专属
 * （MOVE/ROTATE/WAIT/CLUTCH/ENCODER）。速度不属于本方块的职责 —— 没有 rpm 类节点。
 * CNC gearbox (motion block) graph editor. Whitelist = general logic + motion nodes
 * (MOVE/ROTATE/WAIT/CLUTCH/ENCODER). Speed is not this block's job — no rpm nodes.
 */
public class CncGearboxScreen extends AbstractGraphScreen {

    public CncGearboxScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".cnc_gearbox"), pos);
        setNodeFilter(nt ->
            nt == NodeType.MOVE || nt == NodeType.ROTATE || nt == NodeType.WAIT
            || nt == NodeType.CLUTCH || nt == NodeType.ENCODER
            || nt == NodeType.CONST || nt == NodeType.REDSTONE_IN || nt == NodeType.REDSTONE_OUT
            || nt == NodeType.ADD || nt == NodeType.SUB || nt == NodeType.MUL || nt == NodeType.DIV
            || nt == NodeType.ABS || nt == NodeType.ROUND || nt == NodeType.CLAMP || nt == NodeType.MAP
            || nt == NodeType.GT || nt == NodeType.LT || nt == NodeType.EQ || nt == NodeType.GE
            || nt == NodeType.LE || nt == NodeType.BOOL || nt == NodeType.GATE || nt == NodeType.OR
            || nt == NodeType.SPLIT
            || nt == NodeType.SIN || nt == NodeType.COS || nt == NodeType.TAN || nt == NodeType.ATAN2
            || nt == NodeType.ANGLE_UNWRAP
            || nt == NodeType.PID || nt == NodeType.PID_POWER || nt == NodeType.ACCUMULATOR
            || nt == NodeType.INTEGRATOR || nt == NodeType.DELAY || nt == NodeType.LATCH
            || nt == NodeType.T_FLIPFLOP || nt == NodeType.PULSE_EXTEND || nt == NodeType.LOOP
            || nt == NodeType.FUSE || nt == NodeType.FORMULA
            || nt == NodeType.PRIVATE_IN || nt == NodeType.PRIVATE_OUT
            || nt == NodeType.BUS_IN || nt == NodeType.BUS_OUT
            || nt == NodeType.DEBUG_SIGNAL_GEN || nt == NodeType.DEBUG_PROBE
            || nt == NodeType.COMMENT);
    }

    @Override protected CncGearboxBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof CncGearboxBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof CncGearboxBlockEntity;
    }

    @Override public NodeGraph getGraph() { CncGearboxBlockEntity be = getBE(); return be != null ? be.host.graph : new NodeGraph(); }
    @Override public boolean isRunning() { CncGearboxBlockEntity be = getBE(); return be != null && be.host.running; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { CncGearboxBlockEntity be = getBE(); return be != null ? be.host.runtimeState.flipflopStates : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() { CncGearboxBlockEntity be = getBE(); return be != null ? be.getCachedEvalSnapshot() : null; }

    @Override
    public void saveGraph() {
        try {
            var be = getBE();
            if (be == null || be.getLevel() == null) return;
            var tag = new CompoundTag();
            tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        var be = getBE();
        if (be != null) { be.host.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
