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
 * 动力仪表图编辑器。**新增节点白名单**（2026-09-13 与作者确认）= 动力读数（STRESS / RPM）+
 * 输出类（REDSTONE_OUT / PRIVATE_OUT / BUS_OUT）+ 值/输入（CONST / REDSTONE_IN / PRIVATE_IN /
 * BUS_IN）+ 比较与逻辑（GT / LT / GE / LE / EQ / BOOL / GATE / OR / RELAY_A / RELAY_B）+
 * 调试/注释。
 * 蓝屏渲染规则不变：图里有 DATA/TEXT 节点时显示它们（与全息显示器同机制），否则显示内置读数
 * （转速 + 应力条）—— 白名单只影响**新增**，不动已有图。
 * Kinetic gauge graph editor. Add-node whitelist (confirmed with the author 2026-09-13) = kinetic
 * readings + outputs + values/inputs + comparison/logic + debug/comment. The blue-screen render rule
 * is unchanged: with DATA/TEXT nodes present they are drawn, otherwise the built-in readout shows.
 * The whitelist only gates ADDING nodes; existing graphs are untouched.
 */
public class KineticGaugeScreen extends AbstractGraphScreen {

    public KineticGaugeScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".kinetic_gauge"), pos);
        // 分类白名单见 BlockNodeAllowances.KINETIC_GAUGE（values + logic + kinetic + output + debug）
        // 能力边界理由（为何排除 input/gearbox/SPEED_CTRL）写在该常量的 javadoc。
        // Category allowlist: BlockNodeAllowances.KINETIC_GAUGE; capability-boundary
        // rationale (why input/gearbox/SPEED_CTRL are out) lives on that constant.
        setNodeAllowance(BlockNodeAllowances.KINETIC_GAUGE);
    }

    @Override protected KineticGaugeBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof KineticGaugeBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof KineticGaugeBlockEntity;
    }

    @Override public NodeGraph getGraph() { KineticGaugeBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { KineticGaugeBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { KineticGaugeBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        KineticGaugeBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void saveGraph() {
        try {
            KineticGaugeBlockEntity be = getBE();
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
        KineticGaugeBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
