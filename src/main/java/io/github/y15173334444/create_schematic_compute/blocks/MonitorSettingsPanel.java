package io.github.y15173334444.create_schematic_compute.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.y15173334444.create_schematic_compute.client.EditorTheme;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.MONITOR_SETTINGS_PANEL_W;
import static io.github.y15173334444.create_schematic_compute.blocks.MonitorDisplayEditor.ff2;

/**
 * 全息显示器「显示编辑」GUI 的设置面板（拓扑审查 §三 P7 三分，自 MonitorDisplayEditor 逐字迁出）：
 * 合并面板（HUD 复选框 + 虚像缩放 + 3D 八参数双列）渲染、实时预览（previewScreenW/L 覆写）、
 * 保存（定向 MonitorSettingsPacket）、复选框即点即切（sendTabMode）与点击/键盘/字符路由。
 * 预览值被显示区几何（getEffectiveScreenW/L）读取，经 previewW()/previewL() 暴露；本类是
 * 编辑器的协作对象，不建继承体系。
 * <p>The settings panel of the holographic monitor display-layout editor (topology-review
 * §三 P7 three-way split, relocated verbatim from MonitorDisplayEditor): the merged-panel
 * render (HUD checkbox + virtual-image scale + the 3D eight params in two columns), the
 * live preview (previewScreenW/L overrides), saving (the targeted MonitorSettingsPacket),
 * the click-to-toggle checkbox (sendTabMode) and the click / key / char routing. The
 * preview values feed the display-area geometry (getEffectiveScreenW/L) via
 * previewW()/previewL(); a collaborator object, no inheritance hierarchy.</p>
 */
final class MonitorSettingsPanel {

    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorSettingsPanel.class);

    private final MonitorDisplayEditor ed;
    private final MonitorDisplayEditor.Host host;

    MonitorSettingsPanel(MonitorDisplayEditor ed) {
        this.ed = ed;
        this.host = ed.host();
        var mc = Minecraft.getInstance();
        settingFields = new net.minecraft.client.gui.components.EditBox[8];
        for (int i = 0; i < 8; i++) {
            settingFields[i] = new net.minecraft.client.gui.components.EditBox(mc.font, 0, 0, 60, 14, Component.literal(""));
            settingFields[i].setMaxLength(8);
        }
        hudSettingFields = new net.minecraft.client.gui.components.EditBox[1];
        for (int i = 0; i < 1; i++) {
            hudSettingFields[i] = new net.minecraft.client.gui.components.EditBox(mc.font, 0, 0, 60, 14, Component.literal(""));
            hudSettingFields[i].setMaxLength(8);
        }
    }

    // ── Settings panel state ──
    private boolean showSettings = false;
    private boolean settingsInited = false;
    private net.minecraft.client.gui.components.EditBox[] settingFields;
    // HUD 模式复选框 + 虚像缩放字段（docs/monitor-mode-settings-merge-plan.md §3.1/§3.4）。
    // HUD 玻璃面板参数已删除——玻璃与 3D 悬浮屏幕共用 screen* 参数。
    // HUD-mode checkbox + virtual-image-scale field (merge-plan §3.1/§3.4). The HUD
    // glass-panel params are gone — the glass shares the 3D screen's screen* params.
    private Checkbox hudModeCheckbox;
    private net.minecraft.client.gui.components.EditBox[] hudSettingFields;
    // Live preview overrides for screen settings (negative = not previewing)
    private float previewScreenW = -1, previewScreenL = -1;
    private static final String[] SETTING_KEYS = {
        "gui.create_schematic_compute.monitor.scr_w",
        "gui.create_schematic_compute.monitor.scr_l",
        "gui.create_schematic_compute.monitor.scr_x",
        "gui.create_schematic_compute.monitor.scr_y",
        "gui.create_schematic_compute.monitor.scr_z",
        "gui.create_schematic_compute.monitor.scr_roll",
        "gui.create_schematic_compute.monitor.scr_pitch",
        "gui.create_schematic_compute.monitor.scr_yaw"
    };
    private static final String[] HUD_SETTING_KEYS = {
        "gui.create_schematic_compute.monitor.vis_scale"
    };

    // ── 编辑器接缝 / editor seam ──

    boolean isOpen() { return showSettings; }
    void open() { showSettings = true; }
    void close() { showSettings = false; settingsInited = false; }
    /** 预览覆写（负 = 未预览），显示区几何读取 / preview override, read by the display-area geometry. */
    float previewW() { return previewScreenW; }
    float previewL() { return previewScreenL; }

    /** 原编辑器 handleKeyPressed 的设置面板段。返回 null = 面板未消费（编辑器继续自己的路径）；
     *  非 null = 原样直返（含 EditBox 委托的 false）。 */
    Boolean tryKeyPressed(int key, int sc, int mod) {
        if (!showSettings) return null;
        for (var f : settingFields) if (f.isFocused()) {
            if ((key == 257 || key == 335) && host.be() != null) { saveAllSettings(); return true; }
            return f.keyPressed(key, sc, mod);
        }
        for (var f : hudSettingFields) if (f.isFocused()) {
            if ((key == 257 || key == 335) && host.be() != null) { saveAllSettings(); return true; }
            return f.keyPressed(key, sc, mod);
        }
        if (key == 256) { previewScreenW = -1; previewScreenL = -1; showSettings = false; settingsInited = false; return true; }
        return null;
    }

    /** 原编辑器 handleCharTyped 的设置面板段。返回语义同 tryKeyPressed。 */
    Boolean tryCharTyped(char ch, int mod) {
        if (!showSettings) return null;
        for (var f : settingFields) if (f.isFocused()) return f.charTyped(ch, mod);
        for (var f : hudSettingFields) if (f.isFocused()) return f.charTyped(ch, mod);
        // 面板开着但没字段聚焦：显示模式下字符不外泄，否则交还屏幕给节点图输入框。
        // Panel open with no focused field: swallow in display mode, otherwise hand the
        // character back to the screen so node-graph edit boxes receive it.
        if (ed.active()) return false;
        return null;
    }

    // ════ 以下自 MonitorDisplayEditor 原样搬迁 / verbatim-moved from MonitorDisplayEditor ════

    void renderSettingsPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int pw = MONITOR_SETTINGS_PANEL_W;
        // 合并面板平衡布局：首行 = HUD 复选框 + 虚像缩放；下方 3D 8 项分左右两列
        // （4+4）并排。总高 ≈ 186px，不超出小屏；两列均有内容，无空列（merge-plan §3.1）。
        // Balanced merged panel: first row = HUD checkbox + virtual-image scale; below,
        // the 3D 8 fields split into two side-by-side columns (4+4). Total host.screenHeight() ≈ 186px
        // fits short screens; both columns carry content, no empty column (§3.1).
        int rows = 4;
        int ph = 56 + 20 + rows * 20 + 30; // 标题行 + 首行(复选框+虚像缩放) + 3D 字段行 + 保存按钮
        int px = (host.screenWidth() - pw) / 2, py = (host.screenHeight() - ph) / 2;
        g.fill(px, py, px + pw, py + ph, EditorTheme.get().panelBg());
        g.renderOutline(px, py, pw, ph, EditorTheme.get().panelBorder());
        g.fill(px + 2, py + 2, px + pw - 2, py + 18, EditorTheme.get().panelHeader());
        g.drawString(Minecraft.getInstance().font, "§6§l" + I18n.get("gui.create_schematic_compute.monitor.settings_title"), px + 6, py + 5, 0xFFFFFFFF, false);
        // Close
        g.fill(px + pw - 18, py + 2, px + pw - 2, py + 18, 0xFF4A3028);
        g.renderOutline(px + pw - 18, py + 2, 16, 16, 0xFF8B5333);
        g.drawString(Minecraft.getInstance().font, "§cX", px + pw - 14, py + 5, 0xFFFFFFFF, false);

        // Load BE values into EditBoxes only once when panel opens
        MonitorBlockEntity mbe = host.be();
        if (!settingsInited && mbe != null) {
            settingFields[0].setValue(ff2(mbe.screenWidth));
            settingFields[1].setValue(ff2(mbe.screenLength));
            settingFields[2].setValue(ff2(mbe.screenX));
            settingFields[3].setValue(ff2(mbe.screenY));
            settingFields[4].setValue(ff2(mbe.screenZ));
            settingFields[5].setValue(ff2(mbe.screenRoll));
            settingFields[6].setValue(ff2(mbe.screenPitch));
            settingFields[7].setValue(ff2(mbe.screenYaw));
            hudSettingFields[0].setValue(ff2(mbe.virtualImageScale));
            // 复选框初始状态跟随服务端实际模式（取代原 tab 状态行）
            // Checkbox initial state follows the server's actual mode (replaces the old tab line)
            hudModeCheckbox = Checkbox.builder(
                Component.translatable("gui.create_schematic_compute.monitor.hud_mode_cb"),
                Minecraft.getInstance().font)
                .pos(px + 10, py + 24)
                .selected(mbe.hudMode)
                .build();
            settingsInited = true;
        }

        // 首行：HUD 模式复选框（左）+ 虚像缩放（右），并排
        // First row: HUD-mode checkbox (left) + virtual-image scale (right), side by side
        if (hudModeCheckbox != null) {
            hudModeCheckbox.setPosition(px + 10, py + 24);
            hudModeCheckbox.render(g, mx, my, 0);
        }
        boolean hudOn = hudModeCheckbox != null && hudModeCheckbox.selected();
        g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(HUD_SETTING_KEYS[0]) + ":", px + 230, py + 26, 0xFFCCCCCC, false);
        var visField = hudSettingFields[0];
        visField.active = true; // 虚像缩放两模式通用（仅 HUD 生效）/ works in both modes (HUD-only effect)
        visField.setX(px + 330); visField.setY(py + 24);
        visField.render(g, mx, my, 0);

        // 3D 字段区：左列 i=0..3、右列 i=4..7，各 4 行并排。
        // 两组字段始终可编辑（hudMode 只切换渲染路径，不影响参数——用户明确要求
        // 「是否 HUD 模式都不影响面板大小/位置/姿态」，故不置灰）。
        // 3D field area: left column i=0..3, right column i=4..7, 4 rows each.
        // Both field groups are always editable — hudMode only switches the render
        // path and never locks the params (the user required hud mode to not affect
        // the panel geometry, so no greying-out here).
        int ey = py + 24 + 20 + 6;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 2; col++) {
                int i = col == 0 ? row : row + 4;
                int lx = col == 0 ? px + 10 : px + 230;
                int bx = col == 0 ? px + 110 : px + 330;
                g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(SETTING_KEYS[i]) + ":", lx, ey + 2, 0xFFCCCCCC, false);
                var f = settingFields[i];
                f.active = true;
                f.setX(bx); f.setY(ey);
                f.render(g, mx, my, 0);
            }
            ey += 20;
        }
        // Live preview: parse current field values into overrides so the display area updates in real-time
        if (!hudOn) {
            try {
                previewScreenW = Float.parseFloat(settingFields[0].getValue().trim());
                previewScreenL = Float.parseFloat(settingFields[1].getValue().trim());
            } catch (Exception e) { previewScreenW = -1; previewScreenL = -1; }
        } else {
            previewScreenW = -1; previewScreenL = -1;
        }

        // Save button
        int svX = px + 10, svY = ey + 8;
        g.fill(svX, svY, svX + 200, svY + 18, 0xFF3A5A2A);
        g.renderOutline(svX, svY, 200, 18, 0xFF5A8A3A);
        g.drawString(Minecraft.getInstance().font, "§a" + I18n.get("gui.create_schematic_compute.monitor.save_close"), svX + 60, svY + 4, 0xFFFFFFFF, false);
    }

    /** Save all settings and close the panel.
     *  保存所有设置并关闭面板 */
    void saveAllSettings() {
        if (host.be() == null) return;
        try {
            float w = Float.parseFloat(settingFields[0].getValue().trim());
            float l = Float.parseFloat(settingFields[1].getValue().trim());
            float x = Float.parseFloat(settingFields[2].getValue().trim());
            float y = Float.parseFloat(settingFields[3].getValue().trim());
            float z = Float.parseFloat(settingFields[4].getValue().trim());
            float r = Float.parseFloat(settingFields[5].getValue().trim());
            float p = Float.parseFloat(settingFields[6].getValue().trim());
            float yw = Float.parseFloat(settingFields[7].getValue().trim());
            float vis = Float.parseFloat(hudSettingFields[0].getValue().trim());
            // 设置面板只发定向的 MonitorSettingsPacket，不做整图上传（避免覆盖其他玩家并发
            // 的图编辑）；图数据本身已由各类定向 op 增量同步。HUD 模式与虚像缩放随本包同发。
            // Settings send only the targeted MonitorSettingsPacket — no whole-graph upload
            // (which would clobber other players' concurrent graph edits); graph data is
            // already incrementally synced by the targeted ops. HUD mode + virtual-image
            // scale ride the same packet.
            boolean hud = hudModeCheckbox != null && hudModeCheckbox.selected();
            var pkt = new io.github.y15173334444.create_schematic_compute.network.MonitorSettingsPacket(
                host.be().getBlockPos(), w, l, x, y, z, r, p, yw,
                hud, vis);
            PacketDistributor.sendToServer(pkt);
        } catch (Exception e) { LOGGER.warn("Failed to parse monitor settings", e); }
        previewScreenW = -1; previewScreenL = -1;
        showSettings = false; settingsInited = false;
    }

    /** 复选框点击：立即切换模式（服务端广播 → 所见即所得）。面板参数沿用服务端当前已应用
     *  值，不做隐式提交——其余字段仍走显式 Apply/Enter 契约。
     *  Checkbox click: switch mode immediately (server broadcast → WYSIWYG). Panel params keep
     *  the server's current applied values — no implicit commit; other fields stay on the
     *  explicit Apply/Enter contract. */
    private void sendTabMode(boolean hud) {
        MonitorBlockEntity be = host.be();
        if (be == null) return;
        var pkt = new io.github.y15173334444.create_schematic_compute.network.MonitorSettingsPacket(
            be.getBlockPos(),
            be.screenWidth, be.screenLength, be.screenX, be.screenY, be.screenZ,
            be.screenRoll, be.screenPitch, be.screenYaw,
            hud, be.virtualImageScale);
        PacketDistributor.sendToServer(pkt);
    }

    /** 设置面板点击（显示模式由 handleClick 转发；节点图模式下面板打开时由屏幕直接路由）。
     *  Settings-panel click: forwarded by handleClick in display mode, routed directly by
     *  the screen when only the panel is open (graph mode keeps it visible). */
    boolean handleSettingsClick(double mx, double my, int btn) {
        if (btn != 0) return false;
        int pw = MONITOR_SETTINGS_PANEL_W;
        int rows = 4; // 平衡布局：3D 8 项分两列（4+4），首行含复选框+虚像缩放
        int ph = 56 + 20 + rows * 20 + 30;
        int px = (host.screenWidth() - pw) / 2, py = (host.screenHeight() - ph) / 2;
        // Close button
        if (mx >= px + pw - 18 && mx <= px + pw - 2 && my >= py + 2 && my <= py + 18) {
            previewScreenW = -1; previewScreenL = -1;
            showSettings = false; settingsInited = false; return true;
        }
        // 复选框（点击非当前状态 → 立即切换模式）
        // Checkbox (clicking toggles the mode immediately)
        if (hudModeCheckbox != null && mx >= px + 10 && mx <= px + 10 + 200 && my >= py + 24 && my <= py + 44) {
            hudModeCheckbox.onPress();
            sendTabMode(hudModeCheckbox.selected());
            return true;
        }
        // Save button
        int ey = py + 24 + 20 + 6 + rows * 20;
        int svX = px + 10, svY = ey + 8;
        if (mx >= svX && mx <= svX + 200 && my >= svY && my <= svY + 18 && host.be() != null) {
            saveAllSettings();
            return true;
        }
        // EditBox focus: clear all first, then focus the clicked one.
        // 3D 与虚像缩放字段始终可聚焦——hudMode 只切换渲染路径，不锁定参数。
        // Both the 3D and virtual-image-scale fields are always focusable — hudMode
        // only switches the render path and never locks the params.
        for (int i = 0; i < 8; i++) settingFields[i].setFocused(false);
        for (int i = 0; i < 1; i++) hudSettingFields[i].setFocused(false);
        // 虚像缩放两模式都可编辑（仅 HUD 生效）/ scale editable in both modes (HUD-only effect)
        for (var f : hudSettingFields) {
            if (mx >= f.getX() && mx <= f.getX() + 60 && my >= f.getY() && my <= f.getY() + 14) {
                f.setFocused(true); f.mouseClicked(mx, my, btn); return true;
            }
        }
        for (var f : settingFields) {
            if (mx >= f.getX() && mx <= f.getX() + 60 && my >= f.getY() && my <= f.getY() + 14) {
                f.setFocused(true); f.mouseClicked(mx, my, btn); break;
            }
        }
        return true;
    }
}
