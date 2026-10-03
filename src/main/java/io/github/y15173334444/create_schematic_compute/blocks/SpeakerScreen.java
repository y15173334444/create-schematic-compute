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

/**
 * 音响图编辑器（R1-E）：{@code AbstractGraphScreen}，节点准入 = 音频专用（SPEAKER）。
 * 图 = {@code BUS_IN(频段) --音频线--> SPEAKER_PLAY(选声道)}；在编辑器里拖线、选频段/声道。
 * <p>Speaker graph editor: an {@link AbstractGraphScreen} with the audio-only SPEAKER
 * allowance. Drag {@code BUS_IN → SPEAKER_PLAY} (the BUS_IN audio branch reads the band)
 * and pick the band/channel.</p>
 */
public class SpeakerScreen extends AbstractGraphScreen {

    public SpeakerScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".speaker"), pos);
        setNodeAllowance(BlockNodeAllowances.SPEAKER);
    }

    @Override protected SpeakerBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof SpeakerBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof SpeakerBlockEntity;
    }
    @Override public NodeGraph getGraph() { SpeakerBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { SpeakerBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { SpeakerBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        SpeakerBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void toggleRunning(boolean start) {
        SpeakerBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
