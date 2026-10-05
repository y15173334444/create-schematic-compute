package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import io.github.y15173334444.create_schematic_compute.network.SpeakerSettingsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;

/**
 * 音响图编辑器（R1-E）：{@code AbstractGraphScreen}，节点准入 = 音频专用（SPEAKER）。
 * 图 = {@code BUS_IN(频段) --音频线--> SPEAKER_PLAY(选声道)}；在编辑器里拖线、选频段/声道。
 * <p>第二行工具按钮（雷达同款）：「播放设置」开关 gain/radius/mute 面板——静音开关即时
 * 生效（随点随发），gain/radius 输入框无焦点时回显 BE 值、有焦点时实时写入，保存按钮
 * 钳位并发包，关面板也应用。面板配色走用户主题（NodeRenderer 调色函数）。
 * <p>Speaker graph editor: an {@link AbstractGraphScreen} with the audio-only SPEAKER
 * allowance. Drag {@code BUS_IN → SPEAKER_PLAY} and pick the band/channel. A second toolbar
 * row (radar-style) hosts the playback-settings toggle: gain/radius/mute — mute applies
 * immediately, the boxes mirror the BE when unfocused and write it live while focused, and
 * the save button clamps + ships {@link SpeakerSettingsPacket}. Panel colours follow the
 * user theme via the NodeRenderer accessors.
 */
public class SpeakerScreen extends AbstractGraphScreen {

    private static final int H = 18;

    private boolean showSettings;
    private EditBox gainInput, radiusInput;
    private boolean inputsNeedInit = true;

    public SpeakerScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".speaker"), pos);
        setNodeAllowance(BlockNodeAllowances.SPEAKER);
    }

    @Override protected SpeakerBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof SpeakerBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof SpeakerBlockEntity;
    }
    @Override public NodeGraph getGraph() { SpeakerBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { SpeakerBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { SpeakerBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        SpeakerBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void toggleRunning(boolean start) {
        SpeakerBlockEntity be = getBE();
        if (be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }

    @Override protected void init() {
        super.init(); // Screen.init 清空 widget / clears widgets
        gainInput = new EditBox(font, 0, 0, 44, 14, Component.literal("G"));
        radiusInput = new EditBox(font, 0, 0, 44, 14, Component.literal("R"));
        gainInput.setFilter(s -> s.matches("-?\\d{0,2}(\\.\\d{0,2})?"));
        radiusInput.setFilter(s -> s.matches("\\d{0,5}"));
        hideInputs();
        addRenderableWidget(gainInput);
        addRenderableWidget(radiusInput);
    }

    /** 工具行 y（雷达同款）：紧贴基础工具栏下沿 / 底置。 / radar-style row position. */
    private int toolbarRowY() {
        return NodeRenderer.isToolbarBottom() ? height - 44 : GraphEditor.TOP_BAR_H + 24;
    }

    /** 画布钩子：节点编辑器 + 第二行工具按钮 + 播放设置面板 / canvas hook: editor + toolbar row + settings panel */
    @Override protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        editor.renderBg(g, mx, my);
        SpeakerBlockEntity be = getBE();
        if (be == null) return;
        int y = toolbarRowY();
        renderSpeakerToolbar(g, mx, my, y);
        if (showSettings) renderSettingsPanel(g, mx, my, y, be);
    }

    private void renderSpeakerToolbar(GuiGraphics g, int mx, int my, int y) {
        // 开关按钮：面板打开时用强调色提示。 / toggle tinted with the accent while the panel is open.
        g.fill(4, y, 4 + 68, y + H, showSettings ? NodeRenderer.ACC() : NodeRenderer.PINS());
        g.renderOutline(4, y, 68, H, NodeRenderer.CSB());
        g.drawString(font, I18n.get("gui.create_schematic_compute.speaker.settings_title"), 8, y + 5, 0xFFCCCCCC);
    }

    private void renderSettingsPanel(GuiGraphics g, int mx, int my, int toolY, SpeakerBlockEntity be) {
        insureInputs(be);
        boolean bottom = NodeRenderer.isToolbarBottom();
        int py = bottom ? toolY - 86 : toolY + 22;
        int px = 4, pw = 320, ph = 80;
        g.fill(px, py, px + pw, py + ph, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xDD));
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
        g.drawString(font, "§6§l" + I18n.get("gui.create_schematic_compute.speaker.settings_title"), px + 6, py + 4, 0xFFFFFFFF);
        // 保存按钮（右下角）：强调色。 / save in the accent colour, bottom-right.
        g.fill(px + pw - 46, py + ph - H - 2, px + pw - 4, py + ph - 2, NodeRenderer.ACC());
        g.renderOutline(px + pw - 46, py + ph - H - 2, 42, H, NodeRenderer.CSB());
        g.drawString(font, I18n.get("gui.create_schematic_compute.speaker.apply"), px + pw - 42, py + ph - H + 2, 0xFFFFFFFF);
        // 静音开关（第一行左）：开=警示色。 / the mute switch; error-tinted while muted.
        g.fill(px + 6, py + 24, px + 76, py + 24 + H, be.mute ? NodeRenderer.ERR() : NodeRenderer.PINS());
        g.renderOutline(px + 6, py + 24, 70, H, NodeRenderer.CSB());
        String muteLabel = I18n.get("gui.create_schematic_compute.speaker.mute") + ": "
            + I18n.get(be.mute ? "gui.create_schematic_compute.speaker.mute_on"
                               : "gui.create_schematic_compute.speaker.mute_off");
        g.drawString(font, muteLabel, px + 12, py + 29, be.mute ? 0xFFFFFFFF : 0xFFCCCCCC);
        // 两行标签（输入框在 init 里定位）。 / labels; the boxes are positioned in insureInputs.
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.speaker.gain") + ":", px + 90, py + 29, 0xFFCCCCCC);
        g.drawString(font, "§7" + I18n.get("gui.create_schematic_compute.speaker.radius") + ":", px + 90, py + 51, 0xFFCCCCCC);
    }

    private void hideInputs() { gainInput.setVisible(false); radiusInput.setVisible(false); }

    /** 无焦点回显 BE 值、有焦点实时写入（雷达同款契约）。 / radar contract: mirror BE unfocused, write BE live while focused. */
    private void insureInputs(SpeakerBlockEntity be) {
        int toolY = toolbarRowY();
        boolean bottom = NodeRenderer.isToolbarBottom();
        int px = 4, py = bottom ? toolY - 86 : toolY + 22;
        if (inputsNeedInit) {
            inputsNeedInit = false;
            gainInput.setX(px + 200);   gainInput.setY(py + 24);
            radiusInput.setX(px + 200); radiusInput.setY(py + 46);
            gainInput.setVisible(true); radiusInput.setVisible(true);
        }
        if (!gainInput.isFocused()) gainInput.setValue(trim(be.gain));
        else try { be.gain = Math.max(0f, Math.min(4f, Float.parseFloat(gainInput.getValue()))); } catch (Exception ignored) { }
        if (!radiusInput.isFocused()) radiusInput.setValue(String.valueOf(be.radius));
        else try { be.radius = Math.max(1, Math.min(4096, Integer.parseInt(radiusInput.getValue()))); } catch (Exception ignored) { }
    }

    private static String trim(float v) {
        // 1.0 → "1"、1.5 → "1.5"：默认值不显示拖尾零。 / no trailing zeros on the default.
        return (v == Math.floor(v)) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private void send(SpeakerBlockEntity be) {
        be.gain = Math.max(0f, Math.min(4f, be.gain));
        be.radius = Math.max(1, Math.min(4096, be.radius));
        // band 仅作空图默认图种子；SPEAKER_PLAY 恒等直通（2026-10-04 起无声道参数）。
        // The band only seeds an empty graph's default; SPEAKER_PLAY passes through
        // identically (no channel param since 2026-10-04).
        PacketDistributor.sendToServer(new SpeakerSettingsPacket(
            be.getBlockPos(), be.channelBand, be.gain, be.radius, be.mute));
    }

    // ── Clicks ──
    @Override public boolean mouseClicked(double mx, double my, int btn) {
        SpeakerBlockEntity be = getBE();
        if (be != null && btn == 0) {
            int y = toolbarRowY();
            // 工具行：播放设置开关。 / the row's settings toggle.
            if (my >= y && my <= y + H && mx >= 4 && mx <= 72) {
                showSettings = !showSettings;
                if (showSettings) inputsNeedInit = true;
                else { insureInputs(be); send(be); hideInputs(); }
                return true;
            }
            if (showSettings) {
                boolean bottom = NodeRenderer.isToolbarBottom();
                int py = bottom ? y - 86 : y + 22;
                int px = 4, pw = 320, ph = 80;
                // 保存：钳位 + 发包 + 关面板。 / save: clamp + ship + close.
                if (mx >= px + pw - 46 && mx <= px + pw - 4 && my >= py + ph - H - 2 && my <= py + ph - 2) {
                    insureInputs(be); send(be);
                    showSettings = false; hideInputs(); return true;
                }
                // 静音开关：即时生效。 / the mute switch applies immediately.
                if (mx >= px + 6 && mx <= px + 76 && my >= py + 24 && my <= py + 24 + H) {
                    be.mute = !be.mute; send(be); return true;
                }
                // 面板矩形吞掉区域内一切点击（空隙/标签不穿透到画布节点），
                // 输入框聚焦经 super 分发给 children。
                // The panel rect swallows every click inside it (gaps/labels never reach the
                // nodes behind); box focus still happens via the super dispatch to children.
                if (mx >= px && mx <= px + pw && my >= py && my <= py + ph) {
                    super.mouseClicked(mx, my, btn);
                    return true;
                }
            }
        }
        return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn);
    }

    /** 关界面前钩子：未保存的面板编辑写回并发包（雷达同款）。 / flush unsaved panel edits on close (radar-style). */
    @Override protected void preClose() {
        SpeakerBlockEntity be = getBE();
        if (be != null && showSettings) { insureInputs(be); send(be); }
        hideInputs();
    }
}
