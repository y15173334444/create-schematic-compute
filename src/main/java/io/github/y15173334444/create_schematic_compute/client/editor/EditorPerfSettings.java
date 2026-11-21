package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.client.Minecraft;

/**
 * 编辑器性能设置（设置页「性能」tab 的存储与生效门）。三项：
 * <ul>
 * <li><b>textLod</b> 文字 LOD（默认开）——低缩放跳过引脚标签等次要文字（PERF-1，
 *     {@link NodeRenderer#detailText} 的调用点读此开关）；</li>
 * <li><b>cullWorld</b> 剔除世界（默认关）——图编辑器打开期间不渲染 3D 世界
 *     （mixin 取消 {@code GameRenderer.renderLevel}），背板改画不透明主题底；
 *     手部随世界一并消失（renderLevel 的末段）；</li>
 * <li><b>cullFp</b> 剔除第一人称显示（默认关，等效 F1）——图编辑器打开期间隐藏
 *     手部与原版 HUD（mixin 取消 {@code GameRenderer.renderItemInHand} 与
 *     {@code Gui.render}），世界照常渲染（配合透明背景观察世界用）。</li>
 * </ul>
 * 持久化走 {@code config/create_schematic_compute-client.properties}（grid_snap 同款
 * 读取-合并-写回）。mixin 门控与文件 IO 均绑 MC 运行时，无头不可测；纯 IO 往返经
 * 包内可见的路径注入重载由 {@code EditorPerfSettingsTest} 覆盖。
 * Editor performance settings (the storage and gates behind the settings screen's
 * "Performance" tab). Three switches: textLod (default on — PERF-1's secondary-text skip),
 * cullWorld (default off — while the graph editor is open the 3D world is not rendered at
 * all and the backdrop becomes the opaque theme base; the hand vanishes with the level),
 * cullFp (default off, F1-equivalent — hand and vanilla HUD hidden while the editor is
 * open, world still renders for see-through use). Persistence rides
 * {@code config/create_schematic_compute-client.properties} (the grid_snap read-merge-write
 * pattern). The mixin gates and file IO are runtime-bound and headless-untestable; the pure
 * IO round-trip is covered via the package-visible path-injected overloads.
 */
public final class EditorPerfSettings {
    private EditorPerfSettings() {}

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger(EditorPerfSettings.class);

    static boolean textLod = true;
    static boolean cullWorld = false;
    static boolean cullFp = false;

    static {
        load(defaultPath());
    }

    // ── 读取面 / read face ──

    public static boolean textLod() { return textLod; }
    public static boolean cullWorld() { return cullWorld; }
    public static boolean cullFp() { return cullFp; }

    // ── 生效门（mixin 调用）/ the active gates (called from the mixins) ──

    /** 图编辑器族界面打开中：图编辑器本体 + 其设置页。 / A graph-editor-family UI is open:
     *  the editor itself plus its settings screen. */
    public static boolean editorUiOpen() {
        var s = Minecraft.getInstance().screen;
        return s instanceof AbstractGraphScreen || s instanceof EditorSettingsScreen;
    }

    public static boolean cullWorldActive() { return cullWorld && editorUiOpen(); }
    public static boolean cullFpActive() { return cullFp && editorUiOpen(); }

    // ── 写入面（设置页调用，落盘即生效）/ the write face (settings tab; saves land instantly) ──

    public static void setTextLod(boolean v) { textLod = v; save(defaultPath()); }
    public static void setCullWorld(boolean v) { cullWorld = v; save(defaultPath()); }
    public static void setCullFp(boolean v) { cullFp = v; save(defaultPath()); }

    // ── 持久化（grid_snap 同款读取-合并-写回）/ persistence (the grid_snap read-merge-write pattern) ──

    private static java.nio.file.Path defaultPath() {
        return java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
    }

    static void load(java.nio.file.Path path) {
        // 先落默认再读文件：文件缺失/损坏时状态确定性地等于默认值（无前态残留）。
        // Defaults land first, then the file reads: a missing/corrupt file deterministically
        // yields the defaults with no residue from prior state.
        textLod = true; cullWorld = false; cullFp = false;
        try {
            if (!java.nio.file.Files.exists(path)) return;
            var props = new java.util.Properties();
            try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            textLod = Boolean.parseBoolean(props.getProperty("perf.text_lod", "true"));
            cullWorld = Boolean.parseBoolean(props.getProperty("perf.cull_world", "false"));
            cullFp = Boolean.parseBoolean(props.getProperty("perf.cull_fp", "false"));
        } catch (Exception e) {
            LOGGER.error("Failed to load editor perf settings", e);
        }
    }

    static void save(java.nio.file.Path path) {
        try {
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            props.setProperty("perf.text_lod", String.valueOf(textLod));
            props.setProperty("perf.cull_world", String.valueOf(cullWorld));
            props.setProperty("perf.cull_fp", String.valueOf(cullFp));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, null); }
        } catch (Exception e) {
            LOGGER.error("Failed to save editor perf settings", e);
        }
    }
}
