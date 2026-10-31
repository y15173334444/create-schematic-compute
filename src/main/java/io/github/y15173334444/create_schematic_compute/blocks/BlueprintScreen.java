package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import io.github.y15173334444.create_schematic_compute.client.editor.AbstractGraphScreen;

public class BlueprintScreen extends AbstractGraphScreen {

    public BlueprintScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".blueprint"), pos);
        // 分类白名单见 BlockNodeAllowances.BLUEPRINT（docs/node-category-allowance-plan.md §3）
        // Category allowlist: BlockNodeAllowances.BLUEPRINT (docs/node-category-allowance-plan.md §3)
        setNodeAllowance(BlockNodeAllowances.BLUEPRINT);
    }

    @Override protected BlueprintBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof BlueprintBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof BlueprintBlockEntity;
    }

    @Override public NodeGraph getGraph() { BlueprintBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { BlueprintBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { BlueprintBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        BlueprintBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    // ── GraphEditor.Host 能力查询（反向包边解环 2026-10-31）：编辑器经窄查询分叉，
    //    不再 instanceof 本类；326 = 常规 250 + 封装导入/导出按钮（196..268）的顶栏宽度。
    // GraphEditor.Host capability queries (reverse package-edge cycle break, 2026-10-31):
    // the editor forks through narrow queries instead of instanceof-ing this class;
    // 326 = the regular 250 plus the encap import/export button span (196..268).
    @Override public int toolbarWidth() { return 326; }
    @Override public boolean supportsEncapImportExport() { return true; }

    @Override
    public void toggleRunning(boolean start) {
        BlueprintBlockEntity be = getBE();
        if(be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
