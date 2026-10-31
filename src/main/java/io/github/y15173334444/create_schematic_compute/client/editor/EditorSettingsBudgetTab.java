package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.Config;
import io.github.y15173334444.create_schematic_compute.client.BudgetClientState;
import io.github.y15173334444.create_schematic_compute.network.BudgetSetPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.FormattedText;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 设置面板「公式预算」选项卡（K6 交互面）：显示服务端权威的 formulaBudgetMs，OP 可
 * **直接输入**（EditBox，Enter 即应用）或步进调整后应用（C2S {@link BudgetSetPacket}，
 * 服务端裁定权限、钳制区间、写当前世界 SERVER 配置并落盘广播）；非 OP 只读。
 * 布局全部按内容区宽度自适应（描述折行、按钮两行排布），同步落点高亮反馈消除
 * 「点了没反应」的往返延迟观感。
 * The settings panel's "formula budget" tab (the K6 interaction surface): shows the
 * server-authoritative formulaBudgetMs; an OP can **type a value directly** (EditBox,
 * Enter applies) or step-adjust then apply (C2S {@link BudgetSetPacket} — the server rules
 * on permission, clamps the range, writes this world's SERVER config and saves it to disk
 * before broadcasting); non-OP is read-only. The layout adapts to the content width
 * (wrapped description, a two-row button layout), and a highlight on the sync landing
 * closes the apply-roundtrip feedback loop.
 */
final class EditorSettingsBudgetTab {

    private static final double STEP = 0.5;
    private static final double DEFAULT_MS = 3.0;
    private static final double MIN_MS = 0.5;
    private static final double MAX_MS = 20.0;
    private static final int BTN_H = 18;
    private static final int GAP = 8;
    private static final int BOX_W = 64;

    private final EditorSettingsHost h;

    /** 数值输入框（懒创建——font 在屏幕 init 后才可用）。 / the value box (lazy — the font exists only after screen init). */
    private EditBox valueBox;
    /** 框几何（每帧渲染时刷新，命中/焦点用）。 / box geometry (refreshed per render; used by hit-testing/focus). */
    private int boxX, boxY, boxW;
    /** 解析后的待应用值（NaN = 输入无效）。 / the parsed pending value (NaN = invalid input). */
    private double pending = Double.NaN;
    /** 已见过的同步时间戳（判定「同步落点」以闪烁反馈）。 / the last-seen sync stamp (detects a sync landing for the flash). */
    private long seenSyncStamp = 0L;
    private long flashUntil = 0L;

    // ── 按钮几何（渲染时记录 → 命中测试同源）/ button geometry (recorded at render → single source for hit-testing) ──
    private int stepY = 0, row2Y = 0;
    private int minusX = 0, plusX = 0, resetX = 0, applyX = 0;
    private int minusW = 0, plusW = 0, resetW = 0, applyW = 0;

    EditorSettingsBudgetTab(EditorSettingsHost h) { this.h = h; }

    // ── 权威值 / authoritative value ──

    /** 权威现值:优先 BudgetSyncPacket 广播值,未收到过包时回落登录同步的 SERVER 配置副本。
     *  The authoritative current value: prefers the BudgetSyncPacket broadcast, falling back
     *  to the login-synced SERVER config copy until the first packet arrives. */
    private static double currentMs() {
        double s = BudgetClientState.serverMs();
        if (s >= 0) return s;
        try { return Config.FORMULA_BUDGET_MS.get(); }
        catch (IllegalStateException e) { return DEFAULT_MS; } // 配置未装载窗口 / pre-load window
    }

    /** 调整权限以服务端裁定为准（BudgetSyncPacket 携带）。 / adjustability follows the server's ruling (carried by BudgetSyncPacket). */
    private static boolean canAdjust() { return BudgetClientState.canAdjust(); }

    // ── 输入框 / the value box ──

    private void ensureBox() {
        if (valueBox != null) return;
        valueBox = new EditBox(h.font(), boxX, boxY, BOX_W, BTN_H,
            net.minecraft.network.chat.Component.literal("budget"));
        valueBox.setMaxLength(6);
        valueBox.setBordered(false);
        valueBox.setResponder(text -> pending = parse(text));
        valueBox.setValue(fmt(currentMs()));
    }

    /** 解析输入:逗号容差→clamp→一位小数;无效返回 NaN。 / parse the input: comma-tolerant → clamp → one decimal; NaN when invalid. */
    private static double parse(String text) {
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            if (!Double.isFinite(v)) return Double.NaN;
            return Math.round(Math.max(MIN_MS, Math.min(MAX_MS, v)) * 10.0) / 10.0;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String fmt(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return (r == Math.floor(r) && !Double.isInfinite(r)) ? String.format("%.1f", r) : String.valueOf(r);
    }

    private void setPending(double v) {
        pending = v;
        if (valueBox != null) valueBox.setValue(fmt(v));
    }

    /** 应用:输入有效才发包（服务端裁定权限/钳制/落盘/广播）。 / apply: sends only a valid value (the server rules on permission, clamps, saves, broadcasts). */
    private void apply() {
        double v = valueBox != null ? parse(valueBox.getValue()) : Double.NaN;
        if (Double.isNaN(v)) return;
        pending = v;
        PacketDistributor.sendToServer(new BudgetSetPacket(v));
    }

    // ── 焦点路由（屏幕 keyPressed/charTyped 转发）/ focus routing (forwarded by the screen) ──

    boolean boxFocused() { return valueBox != null && valueBox.isFocused() && canAdjust(); }

    void unfocusBox() { if (valueBox != null) valueBox.setFocused(false); }

    /** Enter = 应用，其余按键转发输入框。 / Enter applies; everything else goes to the box. */
    boolean boxKeyPressed(int key, int sc, int mod) {
        if (key == 257 || key == 335) { apply(); return true; }
        return valueBox.keyPressed(key, sc, mod);
    }

    boolean boxCharTyped(char ch, int mod) { return valueBox.charTyped(ch, mod); }

    // ── 渲染 / rendering ──

    void renderBudgetTab(GuiGraphics g, int mx, int my, int cx, int cy, int contentRight, float pt) {
        int contentW = contentRight - cx;
        var font = h.font();
        g.drawString(font, "§6§l" + I18n.get("gui.create_schematic_compute.budget.title"), cx, cy + 4, 0xFFFFFFFF, false);
        // 描述按宽度折行（GUI 缩放下内容区可能只有 ~230px）。 / wrap the copy to the width (the content area can be ~230px at GUI scale 2).
        int ly = cy + 22;
        ly = drawWrapped(g, I18n.get("gui.create_schematic_compute.budget.desc1"), cx, ly, contentW);
        ly = drawWrapped(g, I18n.get("gui.create_schematic_compute.budget.desc2"), cx, ly, contentW);
        ly += 6;

        // 同步落点闪烁: stamp 变化即起一次 800ms 高亮——apply 往返有可见终点。
        // Sync-landing flash: a stamp change starts an 800 ms highlight — the apply roundtrip gets a visible end.
        long stamp = BudgetClientState.syncStamp();
        if (stamp != seenSyncStamp) { seenSyncStamp = stamp; flashUntil = stamp + 800; }
        boolean flash = System.currentTimeMillis() < flashUntil;

        double server = currentMs();
        boolean editing = !Double.isNaN(pending) && Math.abs(pending - server) > 1e-9;
        String valueLine = I18n.get("gui.create_schematic_compute.budget.current", fmt(server));
        if (flash) valueLine += "  §a" + I18n.get("gui.create_schematic_compute.budget.applied");
        if (editing) valueLine += "  §e" + I18n.get("gui.create_schematic_compute.budget.pending", fmt(pending));
        g.drawString(font, valueLine, cx, ly, flash ? 0xFF8FE89F : 0xFFEEEEEE, false);
        ly += 16;

        if (!canAdjust()) {
            g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.budget.no_perm"), cx, ly, 0xFFFFFFFF, false);
            return;
        }

        ensureBox();
        // 行 1:数值框 + 两个步进钮。 / row 1: the value box + the two steppers.
        boxX = cx; boxY = ly; boxW = BOX_W;
        valueBox.setX(boxX); valueBox.setY(boxY); valueBox.setWidth(boxW);
        valueBox.setTextColor(Double.isNaN(pending) ? 0xFFFF6666 : 0xFFEEEEEE);
        g.fill(boxX - 3, boxY - 2, boxX + boxW + 3, boxY + BTN_H + 2,
            valueBox.isFocused() ? NodeRenderer.HOV() : NodeRenderer.PINS());
        g.renderOutline(boxX - 3, boxY - 2, boxW + 6, BTN_H + 4, NodeRenderer.CSB());
        valueBox.render(g, mx, my, pt);
        this.stepY = ly;
        int bx = cx + BOX_W + 6 + GAP;
        minusX = bx; minusW = drawButton(g, bx, stepY, "§7- " + fmt(STEP), mx, my); bx += minusW + GAP;
        plusX = bx; plusW = drawButton(g, bx, stepY, "§7+ " + fmt(STEP), mx, my);
        // 行 2:恢复默认 + 应用。 / row 2: reset + apply.
        this.row2Y = ly + BTN_H + 6;
        resetX = cx; resetW = drawButton(g, cx, row2Y, "§7" + I18n.get("gui.create_schematic_compute.budget.reset"), mx, my);
        applyX = resetX + resetW + GAP;
        applyW = drawButton(g, applyX, row2Y, "§a" + I18n.get("gui.create_schematic_compute.budget.apply"), mx, my);
    }

    /** 按宽度折行绘制,返回下一行 y。 / draw text wrapped to the width; returns the next line's y. */
    private int drawWrapped(GuiGraphics g, String text, int cx, int y, int contentW) {
        for (var line : h.font().split(FormattedText.of(text), contentW)) {
            g.drawString(h.font(), line, cx, y, 0xFFAAAAAA, false);
            y += 12;
        }
        return y;
    }

    /** 画一枚按钮(悬停高亮),返回宽度。 / draw one button (hover highlight); returns its width. */
    private int drawButton(GuiGraphics g, int x, int y, String label, double mx, double my) {
        int w = h.font().width(label) + 16;
        boolean hov = mx >= x && mx <= x + w && my >= y && my <= y + BTN_H;
        g.fill(x, y, x + w, y + BTN_H, hov ? NodeRenderer.HOV() : NodeRenderer.PINS());
        g.renderOutline(x, y, w, BTN_H, NodeRenderer.CSB());
        g.drawString(h.font(), label, x + 8, y + 5, 0xFFFFFFFF, false);
        return w;
    }

    // ── 点击(与渲染同一几何源) / click handling (same geometry source as rendering) ──

    /** 点击:框内聚焦、框外失焦;按钮按下即生效(步进本地即时,应用发包)。 / click: focus in-box, unfocus out; buttons act on press (steppers local, apply sends). */
    void handleClick(double mx, double my) {
        if (valueBox != null) {
            boolean inBox = mx >= boxX - 3 && mx <= boxX + boxW + 3 && my >= boxY - 2 && my <= boxY + BTN_H + 2;
            if (inBox) { valueBox.setFocused(true); return; }
            if (valueBox.isFocused()) valueBox.setFocused(false);
        }
        if (!canAdjust()) return;
        boolean r1 = my >= stepY && my <= stepY + BTN_H;
        boolean r2 = my >= row2Y && my <= row2Y + BTN_H;
        if (r1 && mx >= minusX && mx <= minusX + minusW) setPending(Math.round(clamp(cur() - STEP) * 10.0) / 10.0);
        else if (r1 && mx >= plusX && mx <= plusX + plusW) setPending(Math.round(clamp(cur() + STEP) * 10.0) / 10.0);
        else if (r2 && mx >= resetX && mx <= resetX + resetW) setPending(DEFAULT_MS);
        else if (r2 && mx >= applyX && mx <= applyX + applyW) apply();
    }

    private double cur() { return Double.isNaN(pending) ? currentMs() : pending; }
    private double clamp(double v) { return Math.max(MIN_MS, Math.min(MAX_MS, v)); }
}
