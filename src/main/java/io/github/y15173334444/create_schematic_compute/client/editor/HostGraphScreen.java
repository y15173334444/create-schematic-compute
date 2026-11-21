package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.EditSessionTargets;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;

/**
 * 泛用图宿主屏（宿主套件波 2）：第三方宿主的图编辑器 —— 经
 * {@code api.HostGraphs.openEditor} 的 client-only 钩子打开。图数据、运行态、快照、
 * 名单全部经 {@link GraphBlockEntities#resolve} 解析出的宿主视图（套件适配器）读取，
 * 不点名任何具体宿主类型；CSC 自有宿主的专属屏不受影响（各宿主仍用自己的屏）。
 * The generic graph-host screen (host suite, wave 2): the graph editor for third-party
 * hosts, opened through the {@code api.HostGraphs.openEditor} client-only hook. Graph
 * data, running state, snapshots and the allowance all read through the {@link
 * HostGraphView} narrow view resolved by the graph-domain {@link EditSessionTargets#resolve}
 * (the suite adapter), naming no concrete host type and never reaching into blocks (the
 * client/editor package-edge rule); this mod's own hosts keep their dedicated screens.
 *
 * <p>与 {@code BlueprintScreen} 等既有屏同款接线：init 发 {@code GraphJoinPacket}，
 * 关闭走 leave，运行切换 = 本地乐观写 + {@link BlueprintTogglePacket}（服务端权威）。
 * 第三方子编辑器（像素/谱面族）不在本波范围 —— 泛用屏只服务图编辑主面。</p>
 * <p>The wiring matches the existing screens: init joins the edit session, close leaves
 * it, the running toggle = optimistic local write + {@link BlueprintTogglePacket} (the
 * server stays authoritative). Third-party sub-editors (the pixel/note-roll family) are
 * out of this wave's scope — the generic screen serves the main graph-editing face
 * only.</p>
 */
public class HostGraphScreen extends AbstractGraphScreen {

    public HostGraphScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".host_graph"), pos);
        // 名单与宿主键同源：适配器的 nodeAllowance() 由 bind 时的 HostAdmission 换算而来
        // —— 客户端菜单过滤与服务端准入门同源（P2-7 口径）。
        // Allowance and host key share the source: the adapter's nodeAllowance() converts
        // the bind-time HostAdmission — the client menu filter and the server admission
        // gate read the same face (the P2-7 policy).
        var be = beResolved();
        if (be != null) setNodeAllowance(be.nodeAllowance());
    }

    /** 经 graph 域 resolver 取宿主窄视图（套件适配器）；未 bind / 客户端 BE 缺席返回 null。
     *  The host narrow view via the graph-domain resolver (the suite adapter); null when
     *  unbound or the client BE is absent. */
    private HostGraphView beResolved() {
        if (minecraft == null || minecraft.level == null) return null;
        return EditSessionTargets.resolve(minecraft.level.getBlockEntity(blockPos))
            instanceof HostGraphView v ? v : null;
    }

    @Override protected HostGraphView getBE() {
        return beResolved();
    }

    @Override protected boolean isBlockEntityValid() {
        return beResolved() != null;
    }

    @Override public NodeGraph getGraph() {
        var be = getBE();
        return be != null ? be.getNodeGraph() : new NodeGraph();
    }

    @Override public boolean isRunning() {
        var be = getBE();
        return be != null && be.isRunning();
    }

    @Override public Map<Integer, Boolean> getFlipflopStates() {
        var be = getBE();
        return be != null ? be.getFlipflopStates() : null;
    }

    @Override public EvalSnapshot getCachedEvalSnapshot() {
        var be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void toggleRunning(boolean start) {
        var be = getBE();
        if (be != null) {
            be.setRunning(start);
            PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start));
        }
    }
}
