package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 添加节点菜单：分类列表 + 搜索框 + 滚动条 + 双列布局开关（自 {@link NodeRenderer} 拆分，
 * docs/gui-decomposition-plan.md 步骤 5 第一刀——菜单状态最独立）。
 * The add-node menu: category list + search box + scrollbar + two-column toggle, split out of
 * {@link NodeRenderer} (roadmap step 5, first cut — the menu's state is the most self-contained).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁。对外面不变——{@link NodeRenderer} 保留全部门面方法
 * （渲染 / 分类点击 / 滚动 / 搜索输入）并委托到本类，调用方零改动。主题色仍经
 * {@link NodeRenderer} 的包级访问器读取（单一来源，色板不随本刀搬移）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim. The external surface is unchanged —
 * {@link NodeRenderer} keeps every facade method (render / category click / scroll / search
 * input) delegating here, so no caller changes. Theme colours still come from
 * {@link NodeRenderer}'s package-level accessors (single source; the palette does not move
 * with this cut).</p>
 */
final class NodeAddMenu {

    private final Screen screen;

    NodeAddMenu(Screen screen) { this.screen = screen; }

    /** 分类 = lang 键 + 节点类型 + 列数。 / A category = lang key + node types + column count. */
    private record NodeCategory(String langKey, NodeType[] types, int columns) {
        NodeCategory(String langKey, NodeType[] types) { this(langKey, types, 1); }
    }
    private static final NodeCategory[] CATEGORIES = {
        new NodeCategory("category.create_schematic_compute.values", new NodeType[]{NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN}),
        new NodeCategory("category.create_schematic_compute.math_basic", new NodeType[]{NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD, NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR}),
        new NodeCategory("category.create_schematic_compute.math_advanced", new NodeType[]{NodeType.FORMULA, NodeType.POSE_CONVERT, NodeType.SPLIT, NodeType.INTERP, NodeType.ROUND}),
        new NodeCategory("category.create_schematic_compute.trig", new NodeType[]{NodeType.SIN, NodeType.COS, NodeType.TAN, NodeType.ASIN, NodeType.ACOS, NodeType.ATAN2, NodeType.SINH, NodeType.COSH, NodeType.SQRT, NodeType.LN, NodeType.LOG, NodeType.EXP, NodeType.SEC, NodeType.CSC, NodeType.COT, NodeType.ANGLE_UNWRAP, NodeType.DIRECTION}),
        new NodeCategory("category.create_schematic_compute.logic", new NodeType[]{NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ, NodeType.BOOL, NodeType.GATE, NodeType.OR, NodeType.RELAY_A, NodeType.RELAY_B}),
        new NodeCategory("category.create_schematic_compute.control", new NodeType[]{NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP}),
        // 数控齿轮箱运动反馈 / Programmable gearbox motion feedback
        new NodeCategory("category.create_schematic_compute.gearbox", new NodeType[]{NodeType.MOVE, NodeType.ROTATE, NodeType.WAIT, NodeType.CLUTCH, NodeType.ENCODER, NodeType.TX_OUT}),
        // 动力网络读数（动力仪表宿主注入）/ Kinetic network readings (kinetic gauge host)
        new NodeCategory("category.create_schematic_compute.kinetic", new NodeType[]{NodeType.STRESS, NodeType.RPM}),
        new NodeCategory("category.create_schematic_compute.output", new NodeType[]{NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.SPEED_CTRL, NodeType.BUS_OUT}),
        new NodeCategory("category.create_schematic_compute.sequential", new NodeType[]{NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND, NodeType.LOOP, NodeType.FUSE, NodeType.ACCUMULATOR, NodeType.INTEGRATOR}),
        // F: input_ctrl + input_sensor 合并 / merged
        new NodeCategory("category.create_schematic_compute.input",
            new NodeType[]{NodeType.KEYBOARD, NodeType.MOUSE_BUTTON, NodeType.MOUSE_JOYSTICK, NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON, NodeType.GAMEPAD_TRIGGER,
                           NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW, NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION, NodeType.VELOCITY, NodeType.POSITION, NodeType.TARGET_OUT}),
        // F: COMMENT 并入 display / COMMENT merged into display
        new NodeCategory("category.create_schematic_compute.display",
            new NodeType[]{NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE, NodeType.HUD_PITCH_LADDER}),
        // F: encap_io 并入 structure / encap_io merged into structure
        new NodeCategory("category.create_schematic_compute.structure",
            new NodeType[]{NodeType.ENCAPSULATION, NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT}),
        new NodeCategory("category.create_schematic_compute.debug", new NodeType[]{NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE, NodeType.COMMENT}),
    };
    private final java.util.Map<Integer, Boolean> catExpanded = new java.util.HashMap<>();
    private float menuRX, menuRY;
    private java.util.function.Predicate<NodeType> currentFilter = null;
    // —— ADF 滚动+搜索字段 / scroll + search fields ——
    private float menuW = 0;
    private float menuScrollOff = 0;
    private int menuTotalH = 0;       // 列表总高（不含封顶）/ total list height (uncapped)
    private int menuMaxH = 0;         // 封顶后的可见高度 / capped visible height
    private String menuSearchText = "";
    private boolean menuSearchFocused = false;
    /** 手动双列布局开关：开启后所有展开分类与搜索列表均按双列渲染（默认单列）。
     *  Manual two-column layout toggle: when on, every expanded category AND the search list render in two
     *  columns (default off — single column). */
    private boolean menuTwoColumns = false;
    private static final int TOP_H = 34;   // 标题(18) + 搜索框(16) 固定不滚动区 / fixed non-scroll area
    private static final int SCROLLBAR_W = 6;

    // ══════════════ 渲染 / render ══════════════

    NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my) {
        return renderAddNodeMenu(g, menuX, menuY, mx, my, null);
    }

    /** 计算分类中可见节点数 */
    private int visibleCount(NodeCategory cat, java.util.function.Predicate<NodeType> filter) {
        int n = 0;
        for (var nt : cat.types) if (filter == null || filter.test(nt)) n++;
        return n;
    }

    /** 当前生效列数：手动双列开关开启时全部分类双列，否则单列。
     *  Effective column count: two columns for every category while the manual toggle is on, otherwise one. */
    private int effectiveCols(NodeCategory cat) { return menuTwoColumns ? 2 : cat.columns; }

    NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my, java.util.function.Predicate<NodeType> filter) {
        // D: 组合外部 filter + 搜索文本 / combine external filter + search text
        java.util.function.Predicate<NodeType> combined = nt -> {
            if (filter != null && !filter.test(nt)) return false;
            if (menuSearchText.isEmpty()) return true;
            String q = menuSearchText.toLowerCase();
            return I18n.get(nt.displayName).toLowerCase().contains(q);
        };
        currentFilter = combined;

        int ih=14, ch=16, colW=144;
        // 双列开关开启时面板宽度常驻双列（即使无展开分类也保持，避免切换时面板跳动）
        // two-column width persists while the toggle is on, even with nothing expanded
        int maxCols = menuTwoColumns ? 2 : 1;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], combined) == 0) continue;
            if (catExpanded.getOrDefault(ci, false))
                maxCols = Math.max(maxCols, effectiveCols(CATEGORIES[ci]));
        }
        menuW = 16 + maxCols * colW;
        boolean searching = !menuSearchText.isEmpty();
        int totalH = TOP_H;
        if (searching) {
            // 搜索模式：扁平列表高度（无分类标题行）——修复搜索时无法滚动的问题
            // search mode: flat-list height (no category title rows) — fixes search not being scrollable
            int n = 0;
            for (var cat : CATEGORIES) for (var nt : cat.types) if (combined.test(nt)) n++;
            int cols = menuTwoColumns ? 2 : 1;
            totalH += (int)Math.ceil((double)n / cols) * ih;
        } else {
            for (int ci = 0; ci < CATEGORIES.length; ci++) {
                if (visibleCount(CATEGORIES[ci], combined) == 0) continue;
                totalH += ch;
                if (catExpanded.getOrDefault(ci, false)) {
                    int cols = effectiveCols(CATEGORIES[ci]);
                    int items = visibleCount(CATEGORIES[ci], combined);
                    totalH += (int)Math.ceil((double)items / cols) * ih;
                }
            }
        }
        // A: 真实高度封顶 / height cap
        int maxH = Math.min(totalH, screen.height - 12);
        menuTotalH = totalH; menuMaxH = maxH;
        menuScrollOff = Math.max(0, Math.min(menuScrollOff, totalH - maxH));
        menuRX = Math.max(0, Math.min(menuX, screen.width - menuW));
        menuRY = Math.max(0, Math.min(menuY, screen.height - maxH));

        // 面板框（固定 maxH 高）/ panel frame (fixed maxH height)
        g.fill((int)menuRX, (int)menuRY, (int)(menuRX + menuW), (int)(menuRY + maxH), 0xFF2A2822);
        g.renderOutline((int)menuRX, (int)menuRY, (int)menuW, maxH, NodeRenderer.CSB());
        g.renderOutline((int)menuRX + 1, (int)menuRY + 1, (int)menuW - 2, maxH - 2, 0xFF1A1814);

        // —— 固定顶部区（标题 + 搜索框，不滚动）/ fixed top area (title + search, non-scrolling) ——
        drawStr(g, "§l" + I18n.get("gui.create_schematic_compute.nodes"), menuRX + 6, menuRY + 4, NodeRenderer.CCT());
        // D: 搜索框 / search box
        int sbX = (int)menuRX + 6, sbY = (int)menuRY + 18, sbW = (int)menuW - 12, sbH = 12;
        g.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0xFF1A1814);
        g.renderOutline(sbX, sbY, sbW, sbH, menuSearchFocused ? NodeRenderer.ACC() : 0xFF5A4D3A);
        String shown = menuSearchText.isEmpty()
            ? I18n.get("gui.create_schematic_compute.search_hint")
            : menuSearchText + (menuSearchFocused && (System.currentTimeMillis() / 500 % 2 == 0) ? "_" : "");
        drawStr(g, shown, sbX + 3, sbY + 2, menuSearchText.isEmpty() ? 0xFF777777 : NodeRenderer.CMN());

        // —— 双列切换按钮（标题行右侧，双语文本标签，显示当前状态）/ two-column toggle button (title row right, bilingual label showing current state) ——
        String colsLabel = columnsLabel();
        int[] tb = columnsButtonRect();
        boolean tbHover = mx >= tb[0] && mx <= tb[0] + tb[2] && my >= tb[1] && my <= tb[1] + tb[3];
        g.fill(tb[0], tb[1], tb[0] + tb[2], tb[1] + tb[3], tbHover ? NodeRenderer.HOV() : 0xFF1A1814);
        // 开启（双列）时金色边框高亮 / gold border while two-column mode is active
        g.renderOutline(tb[0], tb[1], tb[2], tb[3], menuTwoColumns ? NodeRenderer.ACC() : 0xFF5A4D3A);
        drawStr(g, colsLabel, tb[0] + 5, tb[1] + 2,
            menuTwoColumns ? NodeRenderer.ACC() : 0xFF777777);

        NodeType hovered = null;
        // A: scissor 裁剪列表区 / scissor-clip list area (screen coords, y=0=top)
        g.enableScissor((int)menuRX, (int)(menuRY + TOP_H), (int)menuRX + (int)menuW, (int)(menuRY + maxH));
        // 滚动条可见时，hover 排除滚动条区域 / exclude scrollbar from hover when visible
        float hoverRight = menuRX + menuW - (totalH > maxH ? SCROLLBAR_W + 4 : 2);
        int cy = (int)menuRY + TOP_H - (int)menuScrollOff;

        if (searching) {
            // D: 搜索模式 — 扁平列表，按匹配度排序 / search mode — flat list, sorted by relevance
            var matches = new java.util.ArrayList<NodeType>();
            for (var cat : CATEGORIES) for (var nt : cat.types) if (combined.test(nt)) matches.add(nt);
            String q = menuSearchText.toLowerCase();
            matches.sort((a, b) -> {
                String na = I18n.get(a.displayName).toLowerCase();
                String nb = I18n.get(b.displayName).toLowerCase();
                int scoreA = na.equals(q) ? 0 : na.startsWith(q) ? 1 : 2;
                int scoreB = nb.equals(q) ? 0 : nb.startsWith(q) ? 1 : 2;
                if (scoreA != scoreB) return Integer.compare(scoreA, scoreB);
                return na.compareTo(nb);
            });
            int cols = menuTwoColumns ? 2 : 1; // 列数与双列开关同步 / column count follows the two-column toggle
            int itemsPerCol = (int)Math.ceil((double)matches.size() / cols);
            int idx = 0;
            for (var nt : matches) {
                int col = idx / itemsPerCol, row = idx % itemsPerCol;
                int ix = (int)menuRX + 8 + col * colW;
                int iy = cy + row * ih;
                int itemRight = (int)Math.min(ix + colW - 4, hoverRight);
                boolean h = mx >= ix && mx <= itemRight && my >= iy && my < iy + ih;
                if (h) { g.fill(ix, iy, itemRight, iy + ih, NodeRenderer.HOV()); hovered = nt; }
                drawStr(g, I18n.get(nt.displayName), ix + 4, iy + 2, h ? NodeRenderer.CMH() : NodeRenderer.CMN());
                idx++;
            }
        } else {
            for (int ci = 0; ci < CATEGORIES.length; ci++) {
                NodeCategory cat = CATEGORIES[ci];
                int vis = visibleCount(cat, combined);
                if (vis == 0) continue;
                boolean exp = catExpanded.getOrDefault(ci, false);
                int cols = exp ? effectiveCols(cat) : 1;
                String title = (exp ? "▼ " : "▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
                boolean titleHover = mx >= menuRX + 2 && mx <= hoverRight && my >= cy && my < cy + ch;
                if (titleHover) g.fill((int)menuRX + 2, cy, (int)(hoverRight), (int)(cy + ch), NodeRenderer.HOV());
                drawStr(g, title, menuRX + 6, cy + 2, titleHover ? NodeRenderer.CMH() : NodeRenderer.CCT());
                cy += ch;
                if (!exp) continue;
                int itemsPerCol = (int)Math.ceil((double)vis / cols);
                int itemIdx = 0;
                for (var nt : cat.types) {
                    if (!combined.test(nt)) continue;
                    int col = itemIdx / itemsPerCol;
                    int row = itemIdx % itemsPerCol;
                    int ix = (int)menuRX + 8 + col * colW;
                    int iy = cy + row * ih;
                    int itemRight = (int)Math.min(ix + colW - 4, hoverRight);
                    boolean h = mx >= ix && mx <= itemRight && my >= iy && my < iy + ih;
                    if (h) { g.fill(ix, iy, itemRight, iy + ih, NodeRenderer.HOV()); hovered = nt; }
                    drawStr(g, I18n.get(nt.displayName), ix + 4, iy + 2, h ? NodeRenderer.CMH() : NodeRenderer.CMN());
                    itemIdx++;
                }
                cy += itemsPerCol * ih;
            }
        }
        g.disableScissor();

        // A: 右侧滚动条 / scrollbar
        if (totalH > maxH) {
            int trackH = maxH - TOP_H;
            int thumbH = Math.max(16, (int)((double)maxH / totalH * trackH));
            int thumbY = (int)menuRY + TOP_H + (int)((double)menuScrollOff / (totalH - maxH) * (trackH - thumbH));
            g.fill((int)(menuRX + menuW - SCROLLBAR_W - 2), thumbY,
                   (int)(menuRX + menuW - 2), thumbY + thumbH, NodeRenderer.CB());
        }
        return hovered;
    }

    /** Handle category expand/collapse click + search box focus. Returns true if consumed. */
    boolean handleCategoryClick(int mx, int my) {
        // 双列切换按钮 / two-column toggle button
        int[] tb = columnsButtonRect();
        if (mx >= tb[0] && mx <= tb[0] + tb[2] && my >= tb[1] && my <= tb[1] + tb[3]) {
            toggleMenuColumns();
            return true;
        }
        // D: 命中搜索框 → 聚焦 / hit search box → focus
        int sbX = (int)menuRX + 6, sbY = (int)menuRY + 18, sbW = (int)menuW - 12, sbH = 12;
        if (mx >= sbX && mx <= sbX + sbW && my >= sbY && my <= sbY + sbH) {
            menuSearchFocused = true; return true;
        }
        // 搜索模式下无分类折叠 / no category toggle in search mode
        if (!menuSearchText.isEmpty()) return false;
        int ih=14, ch=16;
        float clickRight = menuRX + menuW - (menuHasScrollbar() ? SCROLLBAR_W + 4 : 2);
        int cy = (int)menuRY + TOP_H - (int)menuScrollOff;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            int vis = visibleCount(CATEGORIES[ci], currentFilter);
            if (vis == 0) continue;
            if (mx >= menuRX + 2 && mx <= clickRight && my >= cy && my < cy + ch) {
                catExpanded.put(ci, !catExpanded.getOrDefault(ci, false));
                return true;
            }
            cy += ch;
            if (!catExpanded.getOrDefault(ci, false)) continue;
            int cols = effectiveCols(CATEGORIES[ci]);
            cy += (int)Math.ceil((double)vis / cols) * ih;
        }
        return false;
    }

    // ── 双列布局开关 / two-column layout toggle ──
    /** 切换添加节点菜单的双列布局（搜索列表同步跟随）。
     *  Toggle the add-node menu's two-column layout (the search list follows). */
    void toggleMenuColumns() { menuTwoColumns = !menuTwoColumns; }
    /** @return 双列布局是否开启 / whether two-column layout is on */
    boolean isMenuTwoColumns() { return menuTwoColumns; }

    /** 双列按钮矩形（屏幕坐标）——渲染与点击命中共用同一计算，按当前语言标签动态定宽。
     *  Toggle button rect (screen coords) — shared by render & click hit-testing,
     *  width follows the localized state label. */
    private int[] columnsButtonRect() {
        int w = Minecraft.getInstance().font.width(columnsLabel()) + 10;
        return new int[]{(int)menuRX + (int)menuW - w - 6, (int)menuRY + 4, w, 12};
    }

    /** 双列按钮标签：显示当前状态（单列/双列），随开关切换。
     *  Toggle button label: shows the current state (single/two columns). */
    private String columnsLabel() {
        return I18n.get(menuTwoColumns
            ? "gui.create_schematic_compute.columns_double"
            : "gui.create_schematic_compute.columns_single");
    }

    // ── D: 搜索框辅助方法 / search box helpers ──
    void scrollMenu(float delta) { setMenuScrollOff((int)(menuScrollOff + delta)); }
    /** 菜单是否有滚动条。 / Whether the menu has a scrollbar. */
    boolean menuHasScrollbar() { return menuTotalH > menuMaxH && menuMaxH > 0; }
    /** 滚动条轨道区（屏幕坐标）。 / Scrollbar track area (screen coords). */
    int[] menuScrollbarTrack() {
        return new int[]{(int)(menuRX + menuW - SCROLLBAR_W - 2), (int)(menuRY + TOP_H),
                         SCROLLBAR_W, menuMaxH - TOP_H};
    }
    /** 按当前 scrollOff 计算 thumb Y 与高度。 */
    int[] menuScrollbarThumb() {
        int trackH = menuMaxH - TOP_H;
        int thumbH = Math.max(16, (int)((double)menuMaxH / menuTotalH * trackH));
        int maxOff = menuTotalH - menuMaxH;
        int thumbY = (int)(menuRY + TOP_H);
        if (maxOff > 0) thumbY += (int)((double)menuScrollOff / maxOff * (trackH - thumbH));
        return new int[]{thumbY, thumbH};
    }
    int menuScrollOff() { return (int)menuScrollOff; }
    int menuMaxScrollOff() { return Math.max(0, menuTotalH - menuMaxH); }
    void setMenuScrollOff(int off) { menuScrollOff = Math.max(0, Math.min(off, menuMaxScrollOff())); }
    void appendMenuSearch(char c) { menuSearchText += c; }
    void menuSearchBackspace() {
        if (!menuSearchText.isEmpty()) menuSearchText = menuSearchText.substring(0, menuSearchText.length() - 1);
    }
    void resetMenuSearch() { menuSearchText = ""; menuSearchFocused = false; menuScrollOff = 0; }
    boolean isMenuSearchFocused() { return menuSearchFocused; }
    void setMenuSearchFocused(boolean f) { menuSearchFocused = f; }
    void setMenuSearchText(String t) { menuSearchText = t == null ? "" : t; }

    private static void drawStr(GuiGraphics g, String t, float x, float y, int c) {
        g.drawString(Minecraft.getInstance().font, t, (int)x, (int)y, c, false);
    }
}
