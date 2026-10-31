package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开 API 波 1 批 C 客户端面的源码锚（GUI 面走源码锚——PixelEditorThemeAdoptionTest
 * 同款手法；行为面已由 CustomNodeFoundationTest/CustomItemSlotK15Test 覆盖）。钉住七处
 * 接线，防回潮：
 * ① NodeRenderer CUSTOM 标题走 schema；② 工厂 CUSTOM 面板（NUMBER/TEXT 控件、不入
 * fieldParamIndices）；③ EditPanel CUSTOM BOOL 行与标签原文；④ GraphEditor 点击段
 * （TOGGLE_BOOL 带 paramIndex、ADD 发射带 customNodeId）；⑤ NodeAddMenu 注册表展开
 * （visibleCount/两类条目循环/hoveredCustomId）；⑥ 撤销（SET_CUSTOM_TEXT 逆 op +
 * REMOVE_NODE 逆 op 携 cid）；⑦ SpatialIndex.editHeight CUSTOM 行账 + BLUEPRINT 收编
 * CUSTOM 类。
 * Source anchors for the wave-1 batch-C client face (GUI surfaces anchor on source —
 * the PixelEditorThemeAdoptionTest pattern; the behavioural faces are covered by the
 * foundation/K15 tests). Seven wirings pinned against regression.
 */
class CustomNodeEditorFaceTest {

    private static String read(String rel) throws Exception {
        return Files.readString(Path.of(rel));
    }

    private static final String EDITOR = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java";
    private static final String RENDERER = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeRenderer.java";
    private static final String FACTORY = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeEditStateFactory.java";
    private static final String PANEL = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/EditPanel.java";
    private static final String MENU = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeAddMenu.java";
    private static final String HISTORY = "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphOpHistory.java";
    private static final String SPATIAL = "src/main/java/io/github/y15173334444/create_schematic_compute/graph/SpatialIndex.java";
    private static final String ALLOWANCES = "src/main/java/io/github/y15173334444/create_schematic_compute/graph/BlockNodeAllowances.java";

    @Test
    @DisplayName("① 渲染器标题：CUSTOM 走 schema.title，未解析回落哨兵 i18n 键")
    void rendererTitleFromSchema() throws Exception {
        String t = read(RENDERER);
        assertTrue(t.contains("spec.title()"), "CUSTOM 标题必须读 schema");
        assertTrue(t.contains("lastCustomMenuId()"), "菜单 customId 门面经渲染器委托");
    }

    @Test
    @DisplayName("② 工厂：CUSTOM 分支在前（自带 mc）、NUMBER/TEXT 控件、不入 fieldParamIndices")
    void factoryCustomPanel() throws Exception {
        String t = read(FACTORY);
        assertTrue(t.contains("NodeType.CUSTOM) {"), "CUSTOM 早分支存在");
        assertTrue(t.indexOf("NodeType.CUSTOM) {") < t.indexOf("s.paramKeys = node.type.paramNames.clone();"),
            "CUSTOM 分支必须先于通用 paramKeys 段");
        assertTrue(t.contains("SET_CUSTOM_TEXT"), "TEXT 控件走 SET_CUSTOM_TEXT");
        assertTrue(t.contains("CUSTOM 参数是控件不产引脚"), "不入 fieldParamIndices 的语义锚在");
    }

    @Test
    @DisplayName("③ EditPanel：CUSTOM BOOL 行 + 标签原文（不走 i18n 前缀）")
    void panelRowsAndLabels() throws Exception {
        String t = read(PANEL);
        assertTrue(t.contains("CUSTOM BOOL 控件行"), "BOOL 行绘制在");
        assertTrue(t.contains("st.paramKeys[i] + \":\""), "CUSTOM 标签原文直出");
    }

    @Test
    @DisplayName("④ 点击段：TOGGLE_BOOL 带 paramIndex、ADD 发射带 customNodeId（两形态）")
    void clickSegmentsCarrySlots() throws Exception {
        String t = read(EDITOR);
        assertTrue(t.contains("TOGGLE_BOOL,\n                            host.getBlockPos(), ownerNodeId(), en.id,\n                            0, null, 0f, 0f, 0, 0, 0, 0, bi, 0f, null,"),
            "CUSTOM BOOL 点击的 op 必须携带 paramIndex=bi");
        assertTrue(t.contains("addNodeRequest(\n                        host.getBlockPos(), ownerNodeId(), added.id,\n                        selectedMenuType, customMenuId"),
            "CUSTOM 建点 op 必须携带 customMenuId");
        assertTrue(t.contains("graph.addNode(selectedMenuType, customMenuId, s2cX(mx), s2cY(my))"),
            "本地建点走带 id 重载");
    }

    @Test
    @DisplayName("⑤ 菜单：注册表展开（visibleCount CUSTOM 分支 + hoveredCustomId 贯穿两类循环）")
    void menuExpandsRegistry() throws Exception {
        String t = read(MENU);
        assertTrue(t.contains("cat == NodeCategory.CUSTOM"), "visibleCount/条目循环的 CUSTOM 分支在");
        assertTrue(t.contains("hoveredCustomId = customId;") && t.contains("hoveredCustomId = id;"),
            "搜索/分类两类循环都回填 hoveredCustomId");
        assertTrue(t.contains("registeredIds()"), "条目源 = 注册表");
    }

    @Test
    @DisplayName("⑥ 撤销：SET_CUSTOM_TEXT 逆 op（oldStr）；REMOVE_NODE 逆 op 携快照 cid")
    void undoCarriesSlots() throws Exception {
        String t = read(HISTORY);
        assertTrue(t.contains("case SET_CUSTOM_TEXT ->"), "TEXT 逆 op 在");
        assertTrue(t.contains("tag.getString(\"cid\")"), "REMOVE_NODE 逆 op 解析快照 cid");
    }

    @Test
    @DisplayName("⑦ 几何账本 + 名单：editHeight CUSTOM 行账；BLUEPRINT 收编 CUSTOM 类")
    void geometryAndAllowance() throws Exception {
        String t = read(SPATIAL);
        assertTrue(t.contains("h += fields * 18 + bools * 16;"), "editHeight 的 CUSTOM 行账在");
        String a = read(ALLOWANCES);
        assertTrue(a.contains("NodeCategory.CUSTOM"), "BLUEPRINT 收编 CUSTOM 类（验收①宿主）");
    }

    @Test
    @DisplayName("前提自检：锚定文件存在（防路径漂移——包归位批的 2 处改硬编码先例）")
    void anchoredFilesExist() {
        for (String rel : List.of(EDITOR, RENDERER, FACTORY, PANEL, MENU, HISTORY, SPATIAL, ALLOWANCES))
            assertTrue(Files.exists(Path.of(rel)), rel);
    }
}
