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
        // 名单（2026-09-13 与作者确认）：动力读数 + 输出类 + 值/输入 + **比较与逻辑** + 调试/注释。
        // 剩余分类**刻意移出**，分两类理由：
        //   · 能力不足（加进去也是死的）：input（键盘/鼠标/手柄/姿态/速度/位置/目标）与 gearbox
        //     （MOVE/ROTATE/WAIT/CLUTCH/ENCODER/TX_OUT）读的是"座舱/运动"状态（GraphEvaluator 全部走
        //     SeatInputState），仪表宿主不提供 → 恒 0；SPEED_CTRL 改的是宿主的**目标转速**，仪表没有
        //     这个概念 → 无效。
        //   · 作者取舍（可用但暂不提供）：显示（TEXT/DATA 上蓝屏）、数学（基础/高级）、三角、控制
        //     （PID/CLAMP/MAP）、时序、结构/子图 —— 想要时说一声即可加回。
        // 注意：这只是**新增节点菜单**的白名单；已有存档图里的节点照常求值与显示。
        // Whitelist for the ADD-NODE MENU only: kinetic readings + outputs + values/inputs +
        // comparison/logic + debug/comment. The rest is out either by capability (input/gearbox read
        // seat/motion state the gauge never provides; SPEED_CTRL drives a target speed it has no
        // concept of) or by the author's choice (display/math/trig/control/sequential/structure).
        // Nodes already in saved graphs keep evaluating and rendering.
        setNodeFilter(nt ->
            nt == NodeType.STRESS || nt == NodeType.RPM
            || nt == NodeType.REDSTONE_OUT || nt == NodeType.PRIVATE_OUT || nt == NodeType.BUS_OUT
            || nt == NodeType.CONST || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN || nt == NodeType.BUS_IN
            || nt == NodeType.GT || nt == NodeType.LT || nt == NodeType.GE
            || nt == NodeType.LE || nt == NodeType.EQ || nt == NodeType.BOOL
            || nt == NodeType.GATE || nt == NodeType.OR
            || nt == NodeType.RELAY_A || nt == NodeType.RELAY_B
            || nt == NodeType.DEBUG_SIGNAL_GEN || nt == NodeType.DEBUG_PROBE || nt == NodeType.COMMENT);
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
