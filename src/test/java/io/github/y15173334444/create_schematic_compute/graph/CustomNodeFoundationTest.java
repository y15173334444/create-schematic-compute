package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;

import io.netty.buffer.Unpooled;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开 API 波 1 批 A（结构地基）的回归锚：动态注册表契约、CUSTOM 哨兵形状、
 * schema 驱动的引脚/参数、NBT cid 持久化与提供方缺席降级、线上 customNodeId
 * 字段镜像、内置节点零感知。
 * Regression anchors for public-API wave 1 batch A (structural ground): the dynamic
 * registry contract, the CUSTOM sentinel shape, schema-driven pins/params, NBT cid
 * persistence and the missing-provider degradation, the wire customNodeId field mirror,
 * and zero impact on built-in nodes.
 *
 * <p>headless 取型：REDSTONE 系之外类型（构造器不分配 ItemStack）+ 自定义节点的
 * schema/构造/存取/执行全在 graph 域纯值路径（注册表/求值/物品均不触注册表引导）。</p>
 */
class CustomNodeFoundationTest {

    private static final String MODID = "testmod";

    private static CustomNodeType spec(String name, int in, int out, int params) {
        return new CustomNodeType(MODID + ":" + name, "Test " + name,
            nLabels("in", in), nLabels("out", out),
            java.util.stream.IntStream.range(0, params)
                .mapToObj(i -> new CustomNodeType.CustomParam("p" + i, CustomNodeType.CustomParam.ParamKind.NUMBER))
                .toList());
    }

    private static List<String> nLabels(String prefix, int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> prefix + i).toList();
    }

    @AfterEach
    void cleanRegistry() {
        CustomNodeRegistry.unregisterProvider(MODID);
    }

    // ── 注册表契约 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("注册表：重复 id 注册启动即炸（与 NodeType.BY_ID 同款纪律）；注销后可重注册")
    void registerRejectsDuplicateId() {
        CustomNodeRegistry.register(spec("counter", 1, 1, 1));
        assertThrows(IllegalStateException.class, () -> CustomNodeRegistry.register(spec("counter", 2, 2, 2)),
            "重复 id 必须炸在注册期而不是静默覆盖");
        CustomNodeRegistry.unregisterProvider(MODID);
        CustomNodeRegistry.register(spec("counter", 2, 2, 2));
        assertEquals(2, CustomNodeRegistry.get(MODID + ":counter").inputs());
    }

    @Test
    @DisplayName("schema 校验：id 必须 modid:node_id 小写形、title 非空、参数 key 不重复")
    void schemaValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> new CustomNodeType("BadId", "t", null, null, null), "id 形状校验");
        assertThrows(IllegalArgumentException.class,
            () -> new CustomNodeType(MODID + ":a", "", null, null, null), "title 非空校验");
        assertThrows(IllegalArgumentException.class, () -> new CustomNodeType(MODID + ":a", "t",
            null, null, List.of(new CustomNodeType.CustomParam("k", CustomNodeType.CustomParam.ParamKind.NUMBER),
                new CustomNodeType.CustomParam("k", CustomNodeType.CustomParam.ParamKind.TEXT))),
            "参数 key 唯一性");
        assertEquals(MODID + ":a", new CustomNodeType(MODID + ":a", "t", null, null, null).id(),
            "null 列表归一为空（不抛）");
    }

    // ── CUSTOM 哨兵形状：schema 驱动 ───────────────────────────────────

    @Test
    @DisplayName("哨兵形状：inputs/outputs/params.length 由 schema 决定；分类归 CUSTOM")
    void schemaShapeDrivesPinsAndParams() {
        CustomNodeRegistry.register(spec("counter", 2, 3, 4));
        GraphNode n = new GraphNode(1, NodeType.CUSTOM, MODID + ":counter", 0f, 0f);
        assertEquals(NodeType.CUSTOM, n.type);
        assertEquals(NodeCategory.CUSTOM, NodeCategory.of(NodeType.CUSTOM), "全覆盖校验保持绿");
        assertEquals(2, n.inputs());
        assertEquals(3, n.outputs());
        assertEquals(4, n.params.length);
        assertEquals(NodeType.CUSTOM, new GraphNode(2, NodeType.CUSTOM, MODID + ":counter", 0f, 0f).type);
    }

    @Test
    @DisplayName("降级占位：schema 未解析（提供方缺席/cid 缺失）→ 引脚 0、params 空、id 保留")
    void unresolvedSchemaDegrades() {
        GraphNode noProvider = new GraphNode(1, NodeType.CUSTOM, "ghost:missing", 0f, 0f);
        assertEquals(0, noProvider.inputs());
        assertEquals(0, noProvider.outputs());
        assertEquals(0, noProvider.params.length);
        assertEquals("ghost:missing", noProvider.customId, "降级不丢失身份——提供方回归后重载即恢复");

        GraphNode noId = new GraphNode(2, NodeType.CUSTOM, null, 0f, 0f);
        assertEquals(0, noId.inputs());
        assertEquals("", noId.signalName);
    }

    @Test
    @DisplayName("内置节点零感知：CONST 的 customId 恒 null、params/引脚面与从前一致")
    void builtinTypesUntouched() {
        GraphNode c = new GraphNode(1, NodeType.CONST, 0f, 0f);
        assertNull(c.customId);
        assertEquals(1.0f, c.params[0], 1e-6f, "CONST 默认值不变");
        assertEquals(NodeType.CONST.inputs + NodeType.CONST.editableParamCount(), c.inputs());
        assertEquals(NodeType.CONST.outputs, c.outputs());
    }

    // ── NBT：cid 持久化与降级装载 ──────────────────────────────────────

    @Test
    @DisplayName("save/load round-trip：type=custom + cid 独立键，params 逐值恢复")
    void saveLoadRoundTripCarriesCid() {
        CustomNodeRegistry.register(spec("counter", 1, 2, 3));
        GraphNode n = new GraphNode(7, NodeType.CUSTOM, MODID + ":counter", 3f, 4f);
        n.params[0] = 5f;
        n.params[2] = -1.5f;

        CompoundTag tag = n.save(null);
        assertEquals("custom", tag.getString("type"), "type 键保持封闭枚举面");
        assertEquals(MODID + ":counter", tag.getString("cid"), "稳定 id 走独立键");

        GraphNode back = GraphNode.load(tag, null);
        assertEquals(NodeType.CUSTOM, back.type);
        assertEquals(MODID + ":counter", back.customId);
        assertEquals(3, back.params.length);
        assertEquals(5f, back.params[0], 1e-6f);
        assertEquals(-1.5f, back.params[2], 1e-6f);
        assertEquals(1, back.inputs());
        assertEquals(2, back.outputs());
    }

    @Test
    @DisplayName("提供方缺席装载：CUSTOM 哨兵与 cid 保留、params 空、非 F15 未知类型兜底")
    void loadDegradesWhenProviderAbsent() {
        CustomNodeRegistry.register(spec("counter", 1, 2, 3));
        GraphNode n = new GraphNode(7, NodeType.CUSTOM, MODID + ":counter", 0f, 0f);
        n.params[0] = 9f;
        CompoundTag tag = n.save(null);
        CustomNodeRegistry.unregisterProvider(MODID);

        GraphNode back = GraphNode.load(tag, null);
        assertEquals(NodeType.CUSTOM, back.type);
        assertEquals(MODID + ":counter", back.customId);
        assertEquals(0, back.params.length, "降级占位壳（探索稿验收⑤：不损图）");
        assertFalse(back.unknownTypeFallback, "CUSTOM 降级不走 F15 未知枚举类型路径");
        assertEquals(0, back.inputs());
    }

    @Test
    @DisplayName("坏档：type=custom 而无 cid 键 → WARN 占位壳")
    void loadWithoutCidWarnsAndDegrades() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", 3);
        tag.putString("type", "custom");
        tag.putFloat("x", 0f);
        tag.putFloat("y", 0f);

        GraphNode back = GraphNode.load(tag, null);
        assertEquals(NodeType.CUSTOM, back.type);
        assertNull(back.customId);
        assertEquals(0, back.params.length);
    }

    // ── 执行器：建点带 id + 快照门 cid 一致 ─────────────────────────────

    @Test
    @DisplayName("执行器：ADD_NODE 携带 customNodeId 建点；快照 cid 不一致整体跳过恢复")
    void executorCarriesIdAndGateChecksCid() {
        CustomNodeRegistry.register(spec("alpha", 1, 1, 2));
        CustomNodeRegistry.register(spec("beta", 1, 1, 2));

        // 快照来自 alpha 节点（params[0]=5）
        GraphNode src = new GraphNode(90, NodeType.CUSTOM, MODID + ":alpha", 0f, 0f);
        src.params[0] = 5f;
        String snapshot = src.save(null).toString();

        // 落进 beta 空壳：type 同为 custom，但 cid 不同 → 恢复必须整体跳过
        var op = new GraphOp(OpType.ADD_NODE, BlockPos.ZERO, -1, 11,
            0, NodeType.CUSTOM, 0f, 0f, 0, 0, 0, 0, 0, 0f, snapshot,
            0, 0, 0, 0, null, 0, 0, 0, null, 0L, ACTOR, 0, null, MODID + ":beta");
        NodeGraph g = new NodeGraph();
        GraphNode node = OpExecutor.apply(g, op);

        assertEquals(NodeType.CUSTOM, node.type);
        assertEquals(MODID + ":beta", node.customId, "op 携带的 id 落地");
        assertEquals(2, node.params.length, "beta schema 尺寸");
        assertEquals(0f, node.params[0], 1e-6f, "alpha 快照不得灌进 beta 空壳");

        // cid 一致的快照正常恢复
        var op2 = new GraphOp(OpType.ADD_NODE, BlockPos.ZERO, -1, 12,
            0, NodeType.CUSTOM, 0f, 0f, 0, 0, 0, 0, 0, 0f, snapshot,
            0, 0, 0, 0, null, 0, 0, 0, null, 0L, ACTOR, 0, null, MODID + ":alpha");
        GraphNode node2 = OpExecutor.apply(g, op2);
        assertEquals(5f, node2.params[0], 1e-6f, "同 id 快照恢复");
    }

    private static final UUID ACTOR = UUID.nameUUIDFromBytes("custom-foundation".getBytes());

    // ── 准入门：customNodeId 协议卫生（审查批 (b)2）─────────────────────

    /** 29 参 canonical ADD_NODE/SET_PARAM op 构造助手（customNodeId 显式位）。
     *  Helper for the 29-arg canonical op with the customNodeId slot explicit. */
    private static GraphOp fullOp(OpType type, NodeType nt, String cid) {
        return new GraphOp(type, BlockPos.ZERO, -1, 1, 0, nt, 0f, 0f,
            0, 0, 0, 0, 0, 0f, null, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR, 0, null, cid);
    }

    @Test
    @DisplayName("准入门：CUSTOM 须携已注册 id；未注册/缺 id 拒；非 CUSTOM 建点携 id 拒；非建点 op 携 id 拒")
    void admissionCustomIdHygiene() {
        CustomNodeRegistry.register(spec("counter", 1, 1, 1));
        var customAllowance = NodeAllowance.of(java.util.EnumSet.of(NodeCategory.CUSTOM));

        assertNull(NodeAdmission.violation(fullOp(OpType.ADD_NODE, NodeType.CUSTOM, MODID + ":counter"),
            customAllowance, java.util.Set.of(), new NodeGraph()), "已注册 id + CUSTOM 名单 = 放行");
        assertNotNull(NodeAdmission.violation(fullOp(OpType.ADD_NODE, NodeType.CUSTOM, "ghost:missing"),
            customAllowance, java.util.Set.of(), new NodeGraph()), "未注册 id fail-closed");
        assertNotNull(NodeAdmission.violation(fullOp(OpType.ADD_NODE, NodeType.CUSTOM, null),
            customAllowance, java.util.Set.of(), new NodeGraph()), "CUSTOM 缺 id 拒");
        assertNotNull(NodeAdmission.violation(fullOp(OpType.ADD_NODE, NodeType.CONST, MODID + ":counter"),
            NodeAllowance.of(java.util.EnumSet.of(NodeCategory.VALUES)), java.util.Set.of(), new NodeGraph()),
            "非 CUSTOM 建点携 customNodeId 拒");
        assertNotNull(NodeAdmission.violation(fullOp(OpType.SET_PARAM, null, MODID + ":counter"),
            customAllowance, java.util.Set.of(), new NodeGraph()),
            "非建点 op 携 customNodeId 拒（字段走私不限于建点）");
    }

    // ── 线上编码：customNodeId 字段镜像 ────────────────────────────────

    @Test
    @DisplayName("codec 镜像：customNodeId 尾部 bool 哨兵字段 encode/decode 往返一致")
    void codecRoundTripCarriesCustomId() {
        var withId = new GraphOp(OpType.ADD_NODE, BlockPos.ZERO, -1, 5,
            0, NodeType.CUSTOM, 1f, 2f, 0, 0, 0, 0, 0, 0f, null,
            0, 0, 0, 0, null, 0, 0, 0, null, 0L, ACTOR, 0, null, MODID + ":counter");
        var withoutId = new GraphOp(OpType.ADD_NODE, BlockPos.ZERO, -1, 6,
            0, NodeType.CONST, 0f, 0f, 0, 0, 0, 0, 0, 0f, null,
            0, 0, 0, 0, null, 0, 0, 0, null, 0L, ACTOR, 0, null, null);

        var buf = Unpooled.buffer();
        GraphEditOpPacket.CODEC.encode(buf, new GraphEditOpPacket(withId));
        var back = GraphEditOpPacket.CODEC.decode(buf);
        assertEquals(MODID + ":counter", back.op().customNodeId());
        assertEquals(NodeType.CUSTOM, back.op().nodeType());

        var buf2 = Unpooled.buffer();
        GraphEditOpPacket.CODEC.encode(buf2, new GraphEditOpPacket(withoutId));
        var back2 = GraphEditOpPacket.CODEC.decode(buf2);
        assertNull(back2.op().customNodeId(), "非自定义 op 线上恒 null（记录契约）");
        assertEquals(NodeType.CONST, back2.op().nodeType());
    }
}
