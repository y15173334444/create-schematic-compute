package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BAND_TYPES 发布时序回归（2026-11-08 双轴审查的阻断缺陷）：声明类型表此前只挂 BusEval 的
 * BUS_OUT {@code bandsDirty} 分支发布，而重载路径（{@code registerGraphChannels} /
 * {@code reRegister} / {@code takeoverChannel}）只 {@code registerBands} 并把 dirty 清掉 ——
 * 新 JVM 里 BAND_TYPES 恒空，订阅侧收敛按 {@code getOrDefault} 降 FLOAT，首个 tick 把
 * AUDIO/VEC3 频段的线剪掉并落盘。
 * <p>
 * BAND_TYPES publication-timing regression (the blocking defect from the 2026-11-08
 * dual-axis review): the declared-type table used to publish only from BusEval's BUS_OUT
 * {@code bandsDirty} branch, while the reload paths only called {@code registerBands} and
 * cleared the dirty flag — so BAND_TYPES stayed empty in a fresh JVM, subscribers degraded
 * to {@code getOrDefault} FLOAT during convergence, and the first tick cut the AUDIO/VEC3
 * wires and persisted the loss.
 *
 * <p>本组测试走<strong>真实注册流</strong>（{@link BusChannelHelper#registerGraphChannels}，
 * 生产首 tick 同款），不直接种 CHANNELS/BAND_TYPES —— 正是「收敛测试种 CHANNELS 绕过注册流
 * 测不到」的缺口。These tests drive the <b>real registration flow</b> (the production
 * first-tick core) instead of planting CHANNELS/BAND_TYPES directly — precisely the gap
 * that let the defect slip through the convergence suite.</p>
 */
class BandTypePublicationTest {

    private static final BlockPos POS = new BlockPos(1, 2, 3);

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    private static GraphNode busOut(NodeGraph graph, String name, String[] bands, PinType... types) {
        GraphNode n = graph.addNode(NodeType.BUS_OUT, 0, 0);
        n.signalName = name;
        n.signalBands = new ArrayList<>(List.of(bands));
        n.bandTypes = new ArrayList<>();
        for (PinType t : types) n.bandTypes.add(t);
        return n;
    }

    @Test @DisplayName("重载注册流必须发布 BAND_TYPES（不是只在求值 dirty 分支）")
    void registrationPublishesBandTypes() {
        var graph = new NodeGraph();
        busOut(graph, "ch", new String[] {"kick", "snare"}, PinType.AUDIO, PinType.FLOAT);

        BusChannelHelper.registerGraphChannels(graph, POS); // 生产同款首 tick 注册（重载路径）

        assertEquals(Map.of("kick", PinType.AUDIO, "snare", PinType.FLOAT),
            SignalBus.getBandTypes("ch"),
            "注册点必须随频段定义发布声明类型——此前只挂 BusEval 的 bandsDirty 分支，重载后恒空");
    }

    @Test @DisplayName("重载后订阅侧收敛不得剪 AUDIO 线（首个 tick 剪线的用户症状回归）")
    void subscriberAudioWireSurvivesConvergenceAfterReload() {
        // 发布方：BUS_OUT "ch" band "kick" 声明 AUDIO，走真实注册流（= 重载后的首 tick）。
        // Publisher: BUS_OUT "ch" band "kick" declared AUDIO through the real registration
        // flow (= the first tick after a reload).
        var pub = new NodeGraph();
        busOut(pub, "ch", new String[] {"kick"}, PinType.AUDIO);
        BusChannelHelper.registerGraphChannels(pub, POS);

        // 订阅方：BUS_IN "ch" band "kick" 声明 AUDIO，band → AMP.audio 的 AUDIO 线。
        // Subscriber: BUS_IN "ch" band "kick" declared AUDIO with an AUDIO wire
        // band → AMP.audio.
        var sub = new NodeGraph();
        GraphNode in = sub.addNode(NodeType.BUS_IN, 0, 0);
        in.signalName = "ch";
        in.signalBands = new ArrayList<>(List.of("kick"));
        in.bandTypes = new ArrayList<>(List.of(PinType.AUDIO));
        GraphNode amp = sub.addNode(NodeType.AMP, 100, 0);
        assertTrue(sub.addConnection(in.id, 0, amp.id, 0), "夹具前提：AUDIO 线已连上");
        assertEquals(1, sub.connections.size());

        BusChannelHelper.convergeBusInBands(sub); // 首个 tick 的订阅侧收敛 / first-tick convergence

        assertEquals(List.of("kick"), in.signalBands, "无重命名：频段列表应原样保留");
        assertEquals(PinType.AUDIO, in.bandType(0),
            "空注册表不得把 AUDIO 声明降成 FLOAT（阻断缺陷的降级面）");
        assertEquals(1, sub.connections.size(),
            "AUDIO 线不得在收敛中被剪（阻断缺陷的剪线面）");
    }

    @Test @DisplayName("注册即定义的取型与求值发布同源（bandTypeMap 单源）")
    void registrationAndNodeDeclarationsAgree() {
        var graph = new NodeGraph();
        GraphNode out = busOut(graph, "ch", new String[] {"kick"}, PinType.AUDIO);
        BusChannelHelper.registerGraphChannels(graph, POS);
        assertEquals(out.bandTypeMap(), SignalBus.getBandTypes("ch"),
            "注册表内容必须等于节点的声明表（GraphNode.bandTypeMap 单源）");
    }
}
