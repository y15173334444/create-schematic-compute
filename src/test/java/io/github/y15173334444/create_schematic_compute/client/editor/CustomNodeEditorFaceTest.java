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
        assertTrue(t.contains("st.fieldLabels.get(i) + \":\""), "CUSTOM 标签走字段序平行表（审查 (c)3）");
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
        assertTrue(t.contains("hoveredCustomId = id;"), "分类循环回填 hoveredCustomId");
        assertTrue(t.contains("registeredIds()"), "条目源 = 注册表");
    }

    @Test
    @DisplayName("⑤b 审查 (c)1：哨兵绝不进搜索内置集合；自定义段标题过滤+独立游标消除")
    void menuSearchSentinelExcluded() throws Exception {
        String t = read(MENU);
        assertTrue(t.contains("if (cat == NodeCategory.CUSTOM) continue;"),
            "搜索收集循环必须跳过 CUSTOM 类（哨兵混入 = customIds 游标越界）");
        assertTrue(t.contains("searchHitCustomIds(filter)"), "自定义段走原始 filter 门（非 combined）");
        assertFalse(t.contains("customCursor"), "游标复用形态退役（两段独立循环）");
        assertTrue(t.contains("matches.size() + customHits.size()"), "网格索引空间合并计数");
    }

    @Test
    @DisplayName("⑧ 批 D 菜单名单：三处 CUSTOM 计数/条目/搜索同源 rosteredCustomIds；宿主键注入链")
    void menuRosterSingleSource() throws Exception {
        String m = read(MENU);
        assertTrue(m.contains("rosteredCustomIds()"), "菜单 CUSTOM 计数/条目/搜索走同一名单快照");
        assertTrue(m.contains("CustomNodeRoster.forHost(rosterHost)"), "名单源 = CustomNodeRoster.forHost(宿主键)");
        assertTrue(m.contains("public void setRosterHost(String hostKey)"), "宿主键注入口在");
        // 高度预计算与条目循环必须同源（(c)1 同型错位防线：高度数 ≠ 条目数 = 网格越界家族）。
        // The height precompute and the entry loops must share one source (the (c)1 family
        // of cursor misalignment: height count ≠ entry count = grid overrun).
        int vc = m.indexOf("private int visibleCount");
        int rci = m.indexOf("rosteredCustomIds().size()");
        assertTrue(vc >= 0 && rci > vc, "visibleCount CUSTOM 分支按名单计数");
        String e = read(EDITOR);
        assertTrue(e.contains("renderer.setRosterHost(hostKey)"), "编辑器经渲染器门面下发宿主键");
        String scr = read("src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/AbstractGraphScreen.java");
        assertTrue(scr.contains("editor.setCustomRosterHost(allowance.hostKey())"), "宿主键单一来源 = allowance.hostKey()");
    }

    @Test
    @DisplayName("⑨ 批 D ITEM 槽 UI：三方账本（EditPanel 绘制 / 点击段 / editHeight）+ 弹窗单源")
    void itemSlotUiLedger() throws Exception {
        String pn = read(PANEL);
        assertTrue(pn.contains("CUSTOM ITEM 控件行"), "ITEM 槽行绘制在");
        assertTrue(pn.contains("g.renderItem(node.itemParams[itemIdx], ibx + 1, iby + 1);"),
            "槽内物品渲染走槽位坐标");
        String ed = read(EDITOR);
        assertTrue(ed.contains("CUSTOM ITEM 槽位点击"), "ITEM 槽点击段在");
        assertTrue(ed.contains("st.freqSlotSelected = itemIdx;"), "选中态 = itemParams 序位（CUSTOM 语义）");
        assertTrue(ed.contains("int[] r = hotbarPopupRect();") && ed.contains("int[] r2 = hotbarPopupRect();"),
            "弹窗矩形渲染与点击共用单源 helper（两处各算一份 = 几何漂移陷阱）");
        String si = read(SPATIAL);
        assertTrue(si.contains("items * 18"), "editHeight CUSTOM 账本含 ITEM 槽行（三方同账本）");
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
        // 批 D 起账本含 ITEM 槽行（条目 17①：fields/bools/items 三段）。
        // From batch D the ledger carries the ITEM slot rows too (entry 17①: the
        // fields/bools/items three segments).
        assertTrue(t.contains("h += fields * 18 + bools * 16 + items * 18;"), "editHeight 的 CUSTOM 行账在");
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
