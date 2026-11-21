package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.client.gui.GuiGraphics;

import static net.minecraft.client.resources.language.I18n.get;

/**
 * 设置页「性能」tab：三项开关（文字 LOD / 剔除世界 / 剔除第一人称显示），行式布局与
 * 预算 tab 同语言——渲染时记录行几何 → 命中测试同源；点击即切换并落盘
 * （{@link EditorPerfSettings}，grid_snap 同款即时生效，无确定钮）。
 * The settings screen's "Performance" tab: three switches (text LOD / cull world / cull the
 * first-person view) in the budget tab's row language — row geometry recorded at render is
 * the single source for hit-testing; a click toggles and persists instantly through
 * {@link EditorPerfSettings} (grid_snap-style immediate effect, no confirm button).
 */
final class EditorSettingsPerfTab {

    private static final int ROW_H = 22;
    private static final int ROW_PITCH = 30;

    private final EditorSettingsHost h;

    /** 行几何（渲染时记录 → 命中同源）。 / Row geometry (recorded at render → hit-testing shares it). */
    private int row0Y = 0;
    private int rowX = 0;
    private int rowW = 0;

    EditorSettingsPerfTab(EditorSettingsHost h) { this.h = h; }

    /** 布尔写入器（JDK 无 BooleanConsumer）。 / A boolean setter (the JDK has no BooleanConsumer). */
    private interface BoolSetter { void accept(boolean v); }

    /** 开关行定义：存取器对 + 双语标签键。 / A switch row: accessor pair + label keys. */
    private record Row(String labelKey, String descKey, java.util.function.BooleanSupplier get,
                       BoolSetter set) {}

    private static final Row[] ROWS = {
        new Row("perf.text_lod", "perf.text_lod.desc",
            EditorPerfSettings::textLod, EditorPerfSettings::setTextLod),
        new Row("perf.cull_world", "perf.cull_world.desc",
            EditorPerfSettings::cullWorld, EditorPerfSettings::setCullWorld),
        new Row("perf.cull_fp", "perf.cull_fp.desc",
            EditorPerfSettings::cullFp, EditorPerfSettings::setCullFp),
    };

    // ── 渲染 / rendering ──

    void renderPerfTab(GuiGraphics g, int mx, int my, int cx, int cy, int contentRight, int contentBottom) {
        int contentW = contentRight - cx;
        var font = h.font();
        g.drawString(font, "§6§l" + get("gui.create_schematic_compute.perf.title"), cx, cy + 4, 0xFFFFFFFF, false);

        int ly = cy + 24;
        ly = drawWrapped(g, font, get("gui.create_schematic_compute.perf.desc"), cx, ly, contentW);
        ly += 8;

        row0Y = ly;
        rowX = cx;
        rowW = Math.min(contentW, 320);
        for (int i = 0; i < ROWS.length; i++) {
            int ry = row0Y + i * ROW_PITCH;
            boolean hover = mx >= cx && mx < cx + rowW && my >= ry && my < ry + ROW_H;
            boolean on = ROWS[i].get().getAsBoolean();
            g.fill(cx, ry, cx + rowW, ry + ROW_H, hover ? NodeRenderer.HOV() : NodeRenderer.PINS());
            g.renderOutline(cx, ry, rowW, ROW_H, NodeRenderer.CSB());
            g.drawString(font, get("gui.create_schematic_compute." + ROWS[i].labelKey()), cx + 8, ry + 7, 0xFFFFFFFF, false);
            // 状态字右对齐（同引脚标签的右贴齐口径）。 / state text right-aligned (the pin-label right-align rule).
            String state = get("gui.create_schematic_compute.perf." + (on ? "on" : "off"));
            g.drawString(font, on ? "§a" + state : "§7" + state,
                cx + rowW - font.width(state) - 10, ry + 7, 0xFFFFFFFF, false);
            // 描述折行在行下。 / the row's copy wraps beneath it.
            ly = drawWrapped(g, font, get("gui.create_schematic_compute." + ROWS[i].descKey()), cx, ry + ROW_H + 3, contentW);
        }
    }

    /** 描述折行（预算 tab 同款实现）。 / wrapped copy (the budget tab's implementation). */
    private int drawWrapped(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int x, int y, int w) {
        for (var line : font.split(net.minecraft.network.chat.FormattedText.of(text), Math.max(60, w))) {
            g.drawString(font, line, x, y, 0xFFAAAAAA, false);
            y += 12;
        }
        return y;
    }

    // ── 命中 / hit-testing ──

    void handleClick(double mx, double my) {
        if (rowW <= 0) return;
        for (int i = 0; i < ROWS.length; i++) {
            int ry = row0Y + i * ROW_PITCH;
            if (mx >= rowX && mx < rowX + rowW && my >= ry && my < ry + ROW_H) {
                var row = ROWS[i];
                row.set().accept(!row.get().getAsBoolean());
                return;
            }
        }
    }
}
