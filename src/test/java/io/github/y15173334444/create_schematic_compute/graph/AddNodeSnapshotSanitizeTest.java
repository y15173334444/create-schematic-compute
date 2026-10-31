package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 条目 16（ADD_NODE 快照恢复路径不校验内容）的内容卫生锚（2026-10-31 拍板 = 登记册修复
 * 方向①）。恢复通路是「客户端构造快照、协议无签名无对账」——K15 预检只覆盖 SET_* 三个
 * op，因此 OpExecutor 的 ADD_NODE case 自审三道门，本类逐门行为锚：
 *   ① 深度预扫描（{@link OpExecutor#snapshotNbtDepthExceeds}）：超 {@link OpExecutor#MAX_SNAPSHOT_DEPTH}
 *      跳过恢复（catch (Exception) 接不住 TagParser 递归的 StackOverflowError）；
 *   ② 类型一致：快照必须含 "type" 且与 op.nodeType 一致（GraphNode.save 必写 "type"）；
 *   ③ 内容钳制：控制点对 / 帧数 / 频段条数钳到 op 级同值（K15 同源常量）。
 * 另含合法快照 round-trip 零损锚（防钳误伤正常撤销）与解码/恢复同源结构锁（防钳值漂移）。
 * Behaviour anchors for entry 16 (the ADD_NODE snapshot-restore path validates no content;
 * 2026-10-31 ruling = registry fix direction ①). Snapshots are client-constructed and the
 * protocol is unsigned/unreconciled — the K15 pre-check only covers three SET_* ops, so the
 * ADD_NODE case audits three gates, anchored per gate below: (1) the depth pre-scan, (2)
 * the type-match gate, (3) the content clamps at op-level same values (K15-sourced
 * constants). A legitimate-snapshot round-trip anchor guards the clamps against biting
 * normal undos, and a decode/restore same-source structure lock guards against cap drift.
 *
 * <p>headless 取型：仅用 REDSTONE 系之外的类型（构造器不分配 ItemStack，
 * NodeAdmissionGateTest 头注同款禁区）；快照由 {@code GraphNode.save(null).toString()}
 * 产生——save 只在 itemParams/subGraph 非空时解引用 registries，所选类型两者皆空。</p>
 */
class AddNodeSnapshotSanitizeTest {

    private static final BlockPos POS = BlockPos.ZERO;
    private static final UUID ACTOR = UUID.nameUUIDFromBytes("snapshot-sanitize".getBytes());

    /** ADD_NODE op（22 参构造，ItemStack 位传 null —— 记录契约「其余 op 传 null」，
     *  headless 亦不可触 EMPTY）。/ ADD_NODE op via the 22-arg constructor with a null
     *  ItemStack slot (record contract: non-SET_HOTBAR_ITEM ops pass null; EMPTY cannot
     *  bootstrap headless). */
    private static GraphOp addNodeOp(NodeType type, int nodeId, String snapshot) {
        return new GraphOp(OpType.ADD_NODE, POS, -1, nodeId,
            0, type, 0f, 0f,
            0, 0, 0, 0, 0, 0f, snapshot,
            0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR);
    }

    private static GraphNode apply(NodeType type, int nodeId, String snapshot) {
        NodeGraph g = new NodeGraph();
        return OpExecutor.apply(g, addNodeOp(type, nodeId, snapshot));
    }

    /** 合法快照的构造基线：live 节点 save(null).toString() —— 与客户端撤销路径
     *  （GraphEditor.saveNodeNbt）同一产物形状。/ Baseline for legitimate snapshots:
     *  save(null).toString() on a live node — the same shape the client undo path produces. */
    private static String snapshotOf(GraphNode n) {
        return n.save(null).toString();
    }

    // ── 门③：控制点对数钳制（K15 同值）─────────────────────────────────

    @Test
    @DisplayName("门③ 控制点：100 对快照钳到 MAX_CTRL_POINTS，保留前 64 对的值")
    void ctrlPointsClampedToK15Cap() {
        int[] dcx = new int[100];
        int[] dcy = new int[100];
        for (int i = 0; i < 100; i++) {
            dcx[i] = Float.floatToRawIntBits((float) i);
            dcy[i] = Float.floatToRawIntBits((float) (i * 10));
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", NodeType.DEBUG_SIGNAL_GEN.id);
        tag.putIntArray("dcx", dcx);
        tag.putIntArray("dcy", dcy);

        GraphNode node = apply(NodeType.DEBUG_SIGNAL_GEN, 7, tag.toString());

        assertNotNull(node.curveX, "恢复应发生（类型一致）");
        assertEquals(GraphOp.MAX_CTRL_POINTS, node.curveX.length, "对数钳到 K15 上界");
        assertEquals(GraphOp.MAX_CTRL_POINTS, node.curveY.length);
        assertEquals(0f, node.curveX[0], 1e-6f);
        assertEquals(63f, node.curveX[GraphOp.MAX_CTRL_POINTS - 1], 1e-6f, "保留的是前 64 对");
        assertEquals(630f, node.curveY[GraphOp.MAX_CTRL_POINTS - 1], 1e-6f);
    }

    @Test
    @DisplayName("门③ 控制点：两数组长度不对称按较短者取对（旧实现 dcx 界直取 dcy[i] 会 AIOOBE 落空壳）")
    void ctrlPointPairCountBoundedByShorterArray() {
        int[] dcx = new int[100];
        int[] dcy = new int[10];
        for (int i = 0; i < 100; i++) dcx[i] = Float.floatToRawIntBits((float) i);
        for (int i = 0; i < 10; i++) dcy[i] = Float.floatToRawIntBits((float) i);
        CompoundTag tag = new CompoundTag();
        tag.putString("type", NodeType.DEBUG_SIGNAL_GEN.id);
        tag.putIntArray("dcx", dcx);
        tag.putIntArray("dcy", dcy);

        GraphNode node = apply(NodeType.DEBUG_SIGNAL_GEN, 7, tag.toString());

        assertNotNull(node.curveX, "不对称数组不应再落入外层 catch 留空壳");
        assertEquals(10, node.curveX.length, "对数 = min(dcx, dcy) 再钳上界");
        assertEquals(10, node.curveY.length);
    }

    // ── 门③：帧数钳制（K15 同值）───────────────────────────────────────

    @Test
    @DisplayName("门③ 帧：100 帧快照钳到 MAX_SEQUENCE_FRAMES，单帧仍按 W×H 重排")
    void frameCountClampedToK15Cap() {
        ListTag frames = new ListTag();
        for (int f = 0; f < 100; f++) {
            int[] px = new int[256];
            px[0] = 0xFF00FF00;
            frames.add(new IntArrayTag(px));
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", NodeType.IMAGE_SEQUENCE.id);
        tag.put("iframes", frames);

        GraphNode node = apply(NodeType.IMAGE_SEQUENCE, 8, tag.toString());

        assertNotNull(node.imageSequenceFrames);
        assertEquals(OpExecutor.MAX_SEQUENCE_FRAMES, node.imageSequenceFrames.size(),
            "帧数钳到 K15 上界");
        assertEquals(node.imageWidth * node.imageHeight, node.imageSequenceFrames.get(0).length,
            "被保留的帧仍经 fixImagePixelsToSize 重排");
    }

    // ── 门③：频段条数钳制（解码/恢复同源）───────────────────────────────

    @Test
    @DisplayName("门③ 频段：100 条快照钳到 MAX_BANDS（与线缆解码同一常量）")
    void bandCountClampedToSharedCap() {
        ListTag bands = new ListTag();
        for (int i = 0; i < 100; i++) bands.add(StringTag.valueOf("band" + i));
        CompoundTag tag = new CompoundTag();
        tag.putString("type", NodeType.CONST.id);
        tag.put("bands", bands);

        GraphNode node = apply(NodeType.CONST, 9, tag.toString());

        assertNotNull(node.signalBands);
        assertEquals(OpExecutor.MAX_BANDS, node.signalBands.size(), "条数钳到共享上限");
        assertEquals("band0", node.signalBands.get(0));
        assertEquals("band" + (OpExecutor.MAX_BANDS - 1), node.signalBands.get(OpExecutor.MAX_BANDS - 1));
    }

    // ── 门②：类型一致 ─────────────────────────────────────────────────

    @Test
    @DisplayName("门② 类型不一致：const 快照灌进 image 节点 —— 恢复整体跳过留空壳")
    void typeMismatchSkipsRestore() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", NodeType.CONST.id);
        tag.putString("formula", "y=1");
        tag.putString("sig", "smuggled");

        GraphNode node = apply(NodeType.IMAGE, 10, tag.toString());

        assertEquals(NodeType.IMAGE, node.type);
        assertEquals("", node.formula, "不一致快照的内容不得落地");
        assertEquals("", node.signalName);
    }

    @Test
    @DisplayName("门② 缺 type 键：非 GraphNode.save 产物 —— fail-closed 跳过恢复")
    void missingTypeKeySkipsRestore() {
        CompoundTag tag = new CompoundTag();
        tag.putString("formula", "y=1");
        tag.putIntArray("dcx", new int[]{Float.floatToRawIntBits(1f)});
        tag.putIntArray("dcy", new int[]{Float.floatToRawIntBits(2f)});

        GraphNode node = apply(NodeType.DEBUG_SIGNAL_GEN, 11, tag.toString());

        assertEquals("", node.formula);
        assertArrayEquals(new float[]{0f, 1f}, node.curveX, 1e-6f,
            "缺 type 的快照整体不恢复 —— 曲线保持构造默认两点（curveX 有字段初始化器，永不为 null）");
    }

    // ── 门①：深度预扫描 ───────────────────────────────────────────────

    @Test
    @DisplayName("门① 深嵌套快照：预扫描超限即跳过，不进 TagParser、不炸线程")
    void deepNestingSkipsRestore() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('{');
        sb.append('"');
        for (int i = 0; i < 200; i++) sb.append('}');

        GraphNode node = apply(NodeType.CONST, 12, sb.toString());

        assertEquals(NodeType.CONST, node.type);
        assertEquals("", node.signalName, "深嵌套快照不恢复（节点保持新建空壳）");
    }

    @Test
    @DisplayName("门① 扫描器本身：64 层放行 / 65 层超限 / 字符串值内的花括号是内容不计数")
    void depthScannerSemantics() {
        assertFalse(OpExecutor.snapshotNbtDepthExceeds("{" .repeat(64) + "}" .repeat(64),
            OpExecutor.MAX_SNAPSHOT_DEPTH), "恰好 64 层放行");
        assertTrue(OpExecutor.snapshotNbtDepthExceeds("{" .repeat(65) + "}" .repeat(65),
            OpExecutor.MAX_SNAPSHOT_DEPTH), "65 层超限");
        assertFalse(OpExecutor.snapshotNbtDepthExceeds("{\"k\":\"" + "{" .repeat(300) + "}\"}",
            OpExecutor.MAX_SNAPSHOT_DEPTH), "字符串值内的花括号不是结构（误计会错杀合法注释/公式文本）");
        assertFalse(OpExecutor.snapshotNbtDepthExceeds("{\"k\":\"a\\\\\\\"b\"}",
            OpExecutor.MAX_SNAPSHOT_DEPTH), "转义引号不终止字符串模式");
        assertTrue(OpExecutor.snapshotNbtDepthExceeds("{\"k\":\"unterminated {" .repeat(70),
            OpExecutor.MAX_SNAPSHOT_DEPTH), "未闭合字符串之后的花括号按结构计（保守方向）");
    }

    // ── 合法快照 round-trip：钳不得误伤正常撤销 ─────────────────────────

    @Test
    @DisplayName("合法快照 round-trip：曲线/文本/公式逐字段零损")
    void legitSnapshotRoundTripPreservesCurveAndText() {
        GraphNode src = new GraphNode(21, NodeType.DEBUG_SIGNAL_GEN, 3f, 4f);
        float[] cx = {0f, 0.25f, 0.5f, 0.75f, 1f};
        float[] cy = {0f, 0.1f, 0.5f, 0.9f, 1f};
        src.curveX = cx; src.curveY = cy;
        src.formula = "y=x";
        src.displayText = "hello 快照";

        GraphNode node = apply(NodeType.DEBUG_SIGNAL_GEN, 21, snapshotOf(src));

        assertEquals(src.formula, node.formula);
        assertEquals(src.displayText, node.displayText);
        assertEquals(cx.length, node.curveX.length);
        for (int i = 0; i < cx.length; i++) {
            assertEquals(cx[i], node.curveX[i], 1e-6f);
            assertEquals(cy[i], node.curveY[i], 1e-6f);
        }
    }

    @Test
    @DisplayName("合法快照 round-trip：像素帧与频段逐项零损")
    void legitSnapshotRoundTripPreservesFramesAndBands() {
        GraphNode src = new GraphNode(22, NodeType.IMAGE_SEQUENCE, 0f, 0f);
        src.imageWidth = 4;
        src.imageHeight = 4;
        src.imagePixels = new int[16];
        List< int[] > frames = new ArrayList<>();
        for (int f = 0; f < 3; f++) {
            int[] px = new int[16];
            px[f] = 0xFF0000FF;
            frames.add(px);
        }
        src.imageSequenceFrames = new ArrayList<>(frames);

        GraphNode img = apply(NodeType.IMAGE_SEQUENCE, 22, snapshotOf(src));
        assertEquals(3, img.imageSequenceFrames.size());
        for (int f = 0; f < 3; f++)
            assertArrayEquals(frames.get(f), img.imageSequenceFrames.get(f), "帧 " + f + " 逐像素一致");

        GraphNode bus = new GraphNode(23, NodeType.BUS_IN, 1f, 1f);
        bus.signalBands = new ArrayList<>(List.of("alpha", "beta", "gamma"));
        GraphNode busRestored = apply(NodeType.BUS_IN, 23, snapshotOf(bus));
        assertEquals(List.of("alpha", "beta", "gamma"), busRestored.signalBands);
    }

    // ── 同源结构锁：解码与恢复共用常量，防钳值漂移 ─────────────────────

    @Test
    @DisplayName("同源锁：解码侧字面量 64 退役，两处频段钳制同引 OpExecutor.MAX_BANDS")
    void decodeAndRestoreShareTheBandCap() throws Exception {
        String packet = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/GraphEditOpPacket.java"));
        assertTrue(packet.contains("OpExecutor.MAX_BANDS"),
            "线缆解码频段上限必须引用共享常量");
        assertFalse(packet.contains("bandCount > 64"),
            "解码侧字面量 64 禁止重现（钳值漂移 = 快照/线缆两侧分岔）");

        String exec = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/graph/OpExecutor.java"));
        assertTrue(exec.contains("i < MAX_BANDS"), "恢复侧频段钳制引用共享常量");
        assertTrue(exec.contains("i < MAX_SEQUENCE_FRAMES"), "恢复侧帧钳制引用 K15 常量");
        assertTrue(exec.contains("GraphOp.MAX_CTRL_POINTS"), "恢复侧控制点钳制引用 K15 常量");
    }
}
