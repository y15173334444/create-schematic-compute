package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
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
 * 数控齿轮箱（运动块）图编辑器。节点白名单 = 程序计算机同款 + 运动专属
 * （MOVE/ROTATE/WAIT/CLUTCH/ENCODER）+ 动力网络读数（STRESS/RPM，
 * 经 KineticNetworkView 宿主注入读取本方块所在网络）。
 * CNC gearbox (motion block) graph editor. Whitelist = the Program Computer's set plus
 * the motion nodes (MOVE/ROTATE/WAIT/CLUTCH/ENCODER). Speed is not this block's job —
 * no rpm nodes.
 */
public class CncGearboxScreen extends AbstractGraphScreen {

    public CncGearboxScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".cnc_gearbox"), pos);
        // 分类白名单见 BlockNodeAllowances.CNC_GEARBOX（含 gearbox + kinetic）
        // Category allowlist: BlockNodeAllowances.CNC_GEARBOX (gearbox + kinetic)
        setNodeAllowance(BlockNodeAllowances.CNC_GEARBOX);
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
