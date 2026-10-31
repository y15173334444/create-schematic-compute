package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.Config;
import io.github.y15173334444.create_schematic_compute.network.BudgetSetPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 设置面板「公式预算」选项卡（K6 交互面）：显示服务端权威的 formulaBudgetMs，
 * OP 可步进调整并应用（C2S {@link BudgetSetPacket}，服务端裁定权限、钳制区间、
 * 写当前世界的 SERVER 配置并广播权威值）；非 OP 只读。权威值经
 * {@code BudgetSyncPacket} 刷新（登录同步 + 运行期广播），未收到过包时回落到
 * NeoForge 登录时同步的 SERVER 配置副本。
 * The settings panel's "formula budget" tab (the K6 interaction surface): shows the
 * server-authoritative formulaBudgetMs; an OP can step-adjust and apply (C2S
 * {@link BudgetSetPacket} — the server rules on permission, clamps the range, writes the
 * current world's SERVER config and broadcasts the authoritative value); non-OP is
 * read-only. The authoritative value refreshes via BudgetSyncPacket (login sync +
 * runtime broadcast), falling back to the login-synced SERVER config copy until the
 * first packet arrives.
 */
final class EditorSettingsBudgetTab {

    /** 待应用值（本地步进编辑;NaN = 跟随权威值）。 / the pending value (NaN = follows the authoritative one). */
    private double pending = Double.NaN;

    private final EditorSettingsHost h;

    /** 步进粒度与默认值（与 CONFIG 区间 0.5–20 对齐）。 / step and default (aligned with the 0.5–20 config range). */
    private static final double STEP = 0.5;
    private static final double DEFAULT_MS = 3.0;
    private static final double MIN_MS = 0.5;
    private static final double MAX_MS = 20.0;

    EditorSettingsBudgetTab(EditorSettingsHost h) { this.h = h; }

    /** 权威现值:优先 BudgetSyncPacket 广播值,未收到过包时回落登录同步的 SERVER 配置副本。
     *  The authoritative current value: prefers the BudgetSyncPacket broadcast, falling back
     *  to the login-synced SERVER config copy until the first packet arrives. */
    private static double currentMs() {
        double s = io.github.y15173334444.create_schematic_compute.client.BudgetClientState.serverMs();
        if (s >= 0) return s;
        try { return Config.FORMULA_BUDGET_MS.get(); }
        catch (IllegalStateException e) { return DEFAULT_MS; } // 配置未装载窗口 / pre-load window
    }

    /** 调整权限以服务端裁定为准（BudgetSyncPacket 携带）。 / adjustability follows the server's ruling (carried by BudgetSyncPacket). */
    private static boolean canAdjust() {
        return io.github.y15173334444.create_schematic_compute.client.BudgetClientState.canAdjust();
    }

    // ── 渲染 / rendering ──

    void renderBudgetTab(GuiGraphics g, int mx, int my, int cx, int cy, int contentRight, int contentBottom) {
        g.drawString(h.font(), "§6§l" + I18n.get("gui.create_schematic_compute.budget.title"), cx, cy + 4, 0xFFFFFFFF, false);
        g.drawString(h.font(), I18n.get("gui.create_schematic_compute.budget.desc1"), cx, cy + 22, 0xFFAAAAAA, false);
        g.drawString(h.font(), I18n.get("gui.create_schematic_compute.budget.desc2"), cx, cy + 34, 0xFFAAAAAA, false);

        boolean adjust = canAdjust();
        double shown = Double.isNaN(pending) ? currentMs() : pending;
        String valueLine = I18n.get("gui.create_schematic_compute.budget.current", String.format("%.1f", shown));
        if (adjust && !Double.isNaN(pending) && Math.abs(pending - currentMs()) > 1e-9)
            valueLine += "  §e" + I18n.get("gui.create_schematic_compute.budget.pending", String.format("%.1f", pending));
        g.drawString(h.font(), valueLine, cx, cy + 56, 0xFFEEEEEE, false);

        if (!adjust) {
            g.drawString(h.font(), "§7" + I18n.get("gui.create_schematic_compute.budget.no_perm"), cx, cy + 76, 0xFFFFFFFF, false);
            return;
        }
        // 按钮行:[− 0.5] [＋ 0.5] [恢复默认] [应用] / the button row
        int by = cy + 74;
        int[] xs = buttonXs(cx);
        String[] labels = {
            "§7- " + String.format("%.1f", STEP),
            "§7+ " + String.format("%.1f", STEP),
            "§7" + I18n.get("gui.create_schematic_compute.budget.reset"),
            "§a" + I18n.get("gui.create_schematic_compute.budget.apply")};
        for (int i = 0; i < 4; i++) {
            boolean hov = mx >= xs[i] && mx <= xs[i] + BTN_W && my >= by && my <= by + BTN_H;
            g.fill(xs[i], by, xs[i] + BTN_W, by + BTN_H, hov ? NodeRenderer.HOV() : NodeRenderer.PINS());
            g.renderOutline(xs[i], by, BTN_W, BTN_H, NodeRenderer.CSB());
            g.drawString(h.font(), labels[i], xs[i] + 8, by + 5, 0xFFFFFFFF, false);
        }
    }

    /** 点击处理:选项卡列右侧的按钮区(消费一切,与全屏界面口径一致)。
     *  Click handling: the button region right of the tab column (consumes everything,
     *  matching the full-screen contract). */
    void handleClick(double mx, double my, int cx, int cy) {
        if (!canAdjust()) return;
        int by = cy + 74;
        if (my < by || my > by + BTN_H) return;
        int[] xs = buttonXs(cx);
        double x = mx, y = my;
        if (x >= xs[0] && x <= xs[0] + BTN_W) { pending = clampStep(currentMsOrPending() - STEP); }
        else if (x >= xs[1] && x <= xs[1] + BTN_W) { pending = clampStep(currentMsOrPending() + STEP); }
        else if (x >= xs[2] && x <= xs[2] + BTN_W) { pending = DEFAULT_MS; }
        else if (x >= xs[3] && x <= xs[3] + BTN_W) {
            double v = Double.isNaN(pending) ? currentMs() : pending;
            PacketDistributor.sendToServer(new BudgetSetPacket(v));
        }
    }

    private double currentMsOrPending() { return Double.isNaN(pending) ? currentMs() : pending; }

    private static double clampStep(double v) { return Math.round(Math.max(MIN_MS, Math.min(MAX_MS, v)) * 10.0) / 10.0; }

    // ── 几何(渲染与命中单源) / geometry (single source for render & hit) ──

    private static final int BTN_W = 64;
    private static final int BTN_H = 18;
    private static int[] buttonXs(int cx) { return new int[]{cx, cx + BTN_W + 8, cx + 2 * (BTN_W + 8), cx + 3 * (BTN_W + 8)}; }
}
