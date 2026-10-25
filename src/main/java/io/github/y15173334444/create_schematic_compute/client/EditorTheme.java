package io.github.y15173334444.create_schematic_compute.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编辑器主题 —— 调色板的单一真相源（**实例态**）/ Editor theme — the single source of truth for
 * the editor palette (**instance state**).
 *
 * <p>自 {@code blocks.NodeRenderer} 抽出（docs/file-topology-split-review-2026-10-11.md §三 P2、
 * 复审 P2-23）：活色板（volatile 数组、整体换装）、四套预设主题、颜色配置读写
 * （loadColorConfig / saveColorConfig）搬到这里；NodeRenderer 保留全部原静态签名做门面
 * （门面冻结，存量引用零改动），逐个委托到 {@link #get()} 的活动实例。
 * P2-23 的修法：调用方改为每次**实时取值**（{@code EditorTheme.get().panelBg()}），
 * 不再有 static final 在类加载时把颜色固化——编辑器设置里改配色后所有屏立即跟随。</p>
 * <p>Extracted from {@code blocks.NodeRenderer} (topology review §三 P2 / tech-debt P2-23): the
 * live palette (volatile array, swapped whole), the four preset themes and the colour-config IO
 * (loadColorConfig / saveColorConfig) live here; NodeRenderer keeps every original static
 * signature as a frozen facade (existing references untouched), delegating to the
 * {@link #get() active instance}. P2-23 fix: callers fetch live on every use
 * ({@code EditorTheme.get().panelBg()}) — no static final captures the colour at class load, so
 * a palette change in the editor settings reaches every screen immediately.</p>
 *
 * <p>归属 client 而非 blocks 的理由：消费方几乎全是客户端屏（colorpicker / Pixel 三件套 / NBS /
 * MultiLineEditBox / 各编辑器与设置页）；落在 client 才能把「colorpicker→blocks」这条唯一跨包出边
 * 归零（拓扑审查 §2.2 咬合点 2、§四 E3），而 blocks→client 方向本就存在（NodeRenderer 等已引用
 * client 类），不新增反向依赖。本类不引用任何 Minecraft 类，headless/JUnit 可直接测。</p>
 * <p>Why client and not blocks: consumers are almost all client screens; hosting the theme in
 * client is what zeroes the colorpicker→blocks edge (topology review 2.2 #2, §四 E3). The
 * blocks→client direction already exists (NodeRenderer et al. already reference client classes),
 * so no new reverse dependency is introduced. This class references no Minecraft classes and is
 * directly unit-testable headless.</p>
 */
public final class EditorTheme {

    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(EditorTheme.class);

    /** 主题色槽位总数（19 图形语义色 + 7 GUI 镶边色）/ palette slot count (19 graph-editor
     *  semantic colours + 7 GUI chrome colours added with the settings theme extension). */
    public static final int NUM_COLORS = 26;

    // 槽位索引（与 {@link #COLOR_KEYS} 一一对应）/ slot indexes (mirrors COLOR_KEYS)
    private static final int C_BG=0, C_GRID=1, C_NODE=2, C_HEADER=3, C_BORDER=4,
        C_INPUT=5, C_OUTPUT=6, C_WIRE=7, C_WIRE_AUDIO=8, C_PIN_AUDIO=9, C_PIN_AUDIO_BORDER=10, C_DRAG=11,
        C_MENU_TEXT=12, C_MENU_HOVER=13, C_NODE_TITLE=14, C_CAT_TEXT=15, C_SYS_BORDER=16,
        C_INPUT_BORDER=17, C_OUTPUT_BORDER=18, C_PANEL_BG=19, C_PANEL_HEADER=20, C_PANEL_BORDER=21,
        C_INSET_BG=22, C_ACCENT=23, C_ERROR=24, C_HOVER=25;

    /** 槽位语义名（配置文件键 + 设置页 i18n 键的后缀）/ slot key names (config-file keys and the
     *  settings-page i18n suffixes), order = slot order. */
    public static final String[] COLOR_KEYS = {"bg","grid","node","header","border","input","output","wire","wire_audio","pin_audio","pin_audio_border","drag","menu_text","menu_hover","node_title","cat_text","sys_border","input_border","output_border","panel_bg","panel_header","panel_border","inset_bg","accent","error","hover"};

    /** 出厂默认色板（= 预设 0）/ factory default palette (= preset 0). */
    public static final int[] DEFAULT_COLORS = {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFF3FB8A8,0xFF5ED0BC,0xFF2E7A6E,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22,0xFF2A2822,0xFF4A3F28,0xFF5A4D3A,0xFF1A1814,0xFFFFAA00,0xFFFF4444,0xFF3A3428};

    /** 预设主题（26 色 × 4 套，顺序同 COLOR_KEYS）/ preset themes (26 colours × 4, slot order). */
    public static final int[][] THEMES = {
        // 26色: bg…output_border + panel_bg,panel_header,panel_border,inset_bg,accent,error,hover（wire_audio/pin_audio/pin_audio_border 为音频域三色）
        {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFF3FB8A8,0xFF5ED0BC,0xFF2E7A6E,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22,0xFF2A2822,0xFF4A3F28,0xFF5A4D3A,0xFF1A1814,0xFFFFAA00,0xFFFF4444,0xFF3A3428},
        {0xFF0A1020,0xFF152040,0xFF1A2A4A,0xFF2A3A5A,0xFF3A5A7A,0xFF44AAFF,0xFFFFAA44,0xFF66BBFF,0xFF40E0C8,0xFF6FEBD8,0xFF2E9C8A,0xFFFFFF88,0xFF88AACC,0xFFFFDD77,0xFFFFDD77,0xFF88AACC,0xFF6688AA,0xFF2266AA,0xFFAA6622,0xFF141C2A,0xFF22304A,0xFF3A5A7A,0xFF0A1220,0xFFFFCC44,0xFFFF5544,0xFF2A3A5A},
        {0xFF0A0A0A,0xFF1A1A1A,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF00FF88,0xFFFF4466,0xFF888888,0xFF00DDCC,0xFF35F0DC,0xFF0E8F82,0xFFFFFF88,0xFFAAAAAA,0xFFCCCCCC,0xFFCCCCCC,0xFF888888,0xFF666666,0xFF444444,0xFF444444,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF1A1A1A,0xFFFFDD00,0xFFFF5555,0xFF444444},
        {0xFF1E1410,0xFF2A1C14,0xFF3A2820,0xFF4A3428,0xFF5A4438,0xFFFF8844,0xFFAA6633,0xFFDD8844,0xFF4FC3B7,0xFF79DCCF,0xFF2E9484,0xFFFFCC66,0xFFAA8866,0xFFFFCC77,0xFFFFCC77,0xFFAA8866,0xFF8B6B53,0xFF6B4A33,0xFF6B3A23,0xFF2A1C14,0xFF4A3428,0xFF5A4438,0xFF1E1410,0xFFFFCC66,0xFFFF6644,0xFF3A2820},
    };

    /** 活动主题实例：类初始化即建（先默认色板），随后立刻尝试从配置文件加载用户配色。
     *  The active theme instance: created at class init (default palette first), then the user
     *  palette is loaded from the config file if present. */
    private static final EditorTheme ACTIVE = new EditorTheme(DEFAULT_COLORS.clone());

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "create_schematic_compute-client.properties";

    static { loadColorConfig(); }

    /** 活动主题实例（全局唯一；颜色经实例方法实时读取）。
     *  The active theme instance (process-wide; colours are read live through instance methods). */
    public static EditorTheme get() { return ACTIVE; }

    // ── 实例态 / instance state ──

    /** 活色板：volatile 引用 + **整体换装**（从不原位改写），读侧天然一致。
     *  Live palette: volatile reference swapped **whole** (never mutated in place), so readers
     *  are trivially consistent. */
    private volatile int[] colors;
    /** 最近应用的预设索引（配置缺失时重应用它）/ last applied preset index (re-applied when no config exists). */
    private int themeIndex = 0;

    private EditorTheme(int[] colors) {
        if (colors == null || colors.length < NUM_COLORS) colors = DEFAULT_COLORS.clone();
        this.colors = colors;
    }

    // ══════════════ 取色（实例访问器，语义名 = COLOR_KEYS）/ colour accessors (semantic names = COLOR_KEYS) ══════════════
    // 门面映射表 / facade mapping（blocks.NodeRenderer 旧名 → 本类新名）:
    //   CG→bg  CGL→grid  CN→node  CH→header  CB→border  CPI→inputPin  CPO→outputPin
    //   CW→wire  CWA→wireAudio  CAP→pinAudio  CAPB→pinAudioBorder  CWD→drag
    //   CMN→menuText  CMH→menuHover  CNT→nodeTitle  CCT→catText  CSB→sysBorder
    //   CPIB→inputPinBorder  CPOB→outputPinBorder  PBG→panelBg  PHT→panelHeader
    //   PBR→panelBorder  PINS→insetBg  ACC→accent  ERR→error  HOV→hover

    /** 图形区背景 / graph-area background. */
    public int bg() { return colors[C_BG]; }
    /** 网格线 / grid lines. */
    public int grid() { return colors[C_GRID]; }
    /** 节点体 / node body. */
    public int node() { return colors[C_NODE]; }
    /** 节点头部 / node header. */
    public int header() { return colors[C_HEADER]; }
    /** 节点边框 / node border. */
    public int border() { return colors[C_BORDER]; }
    /** 输入引脚 / input pin fill. */
    public int inputPin() { return colors[C_INPUT]; }
    /** 输出引脚 / output pin fill. */
    public int outputPin() { return colors[C_OUTPUT]; }
    /** 普通连线 / ordinary wire. */
    public int wire() { return colors[C_WIRE]; }
    /** 音频连线 / audio wire. */
    public int wireAudio() { return colors[C_WIRE_AUDIO]; }
    /** 音频引脚 / audio pin fill. */
    public int pinAudio() { return colors[C_PIN_AUDIO]; }
    /** 音频引脚边框 / audio pin border. */
    public int pinAudioBorder() { return colors[C_PIN_AUDIO_BORDER]; }
    /** 拖拽把手 / drag handle. */
    public int drag() { return colors[C_DRAG]; }
    /** 菜单文字 / menu text. */
    public int menuText() { return colors[C_MENU_TEXT]; }
    /** 菜单悬停 / menu hover. */
    public int menuHover() { return colors[C_MENU_HOVER]; }
    /** 节点标题文字 / node title text. */
    public int nodeTitle() { return colors[C_NODE_TITLE]; }
    /** 分类文字 / category text. */
    public int catText() { return colors[C_CAT_TEXT]; }
    /** 系统镶边（按钮描边等）/ system chrome (button outlines etc.). */
    public int sysBorder() { return colors[C_SYS_BORDER]; }
    /** 输入引脚边框 / input pin border. */
    public int inputPinBorder() { return colors[C_INPUT_BORDER]; }
    /** 输出引脚边框 / output pin border. */
    public int outputPinBorder() { return colors[C_OUTPUT_BORDER]; }
    /** 面板底 / panel bg. */
    public int panelBg() { return colors[C_PANEL_BG]; }
    /** 面板标题带 / panel header band. */
    public int panelHeader() { return colors[C_PANEL_HEADER]; }
    /** 面板边框 / panel border. */
    public int panelBorder() { return colors[C_PANEL_BORDER]; }
    /** 内凹井底（滚动条轨道、列表行）/ inset well (scroll tracks, list rows). */
    public int insetBg() { return colors[C_INSET_BG]; }
    /** 强调色（选中/主高亮）/ accent (selection / primary highlight). */
    public int accent() { return colors[C_ACCENT]; }
    /** 警示/错误 / error. */
    public int error() { return colors[C_ERROR]; }
    /** 悬停高亮 / hover highlight. */
    public int hover() { return colors[C_HOVER]; }

    // ══════════════ 主题操作 / theme operations ══════════════

    /** 最近应用的预设索引 / the last applied preset index. */
    public int themeIndex() { return themeIndex; }

    /** 应用预设主题（越界回落 0）。整体换装——所有取色点下一帧即时生效。
     *  Apply a preset theme (out-of-range falls back to 0). Swaps the palette whole, so every
     *  live fetch picks the new colours up on the next frame. */
    public void applyPreset(int index) {
        if (index < 0 || index >= THEMES.length) index = 0;
        themeIndex = index;
        colors = THEMES[index]; // 原子整体换装 / atomic whole-palette swap
    }

    /** 当前色板快照（副本——改它不影响主题）/ snapshot of the live palette (a copy; mutating it
     *  does not touch the theme). */
    public int[] snapshot() { return colors.clone(); }

    /** 用给定色板换装（长度不足 26 时忽略——与旧 NodeRenderer.setColors 同口径）。
     *  Swap in the given palette (ignored when shorter than {@link #NUM_COLORS} — same contract
     *  as the old NodeRenderer.setColors). */
    public void setColors(int[] c) {
        if (c == null || c.length < NUM_COLORS) return;
        colors = c.clone(); // 原子整体换装 / atomic whole-palette swap
    }

    /** 主题色的半透明变体：保留 RGB、替换透明度（alpha 0..255）。跨屏同源遮罩/淡入用，
     *  使叠层也随主题变色。 / Theme colour with a given alpha (RGB kept, alpha 0..255);
     *  keeps shared scrim/fade layers theme-reactive. */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ══════════════ 配置读写 / config IO ══════════════

    /** 从配置文件加载用户配色（文件缺失且应用过预设时重应用该预设）。
     *  Load the user palette from the config file (re-applies the last preset when the file is
     *  absent and a non-default preset was applied). */
    public static void loadColorConfig() {
        loadColorConfig(Path.of(CONFIG_DIR, CONFIG_FILE));
    }

    /** 保存当前配色到配置文件（保留已有非颜色属性）。
     *  Save the current palette to the config file (existing non-colour properties are kept). */
    public static void saveColorConfig() {
        saveColorConfig(Path.of(CONFIG_DIR, CONFIG_FILE));
    }

    /** 同上，路径可注入（测试用；生产路径见无参重载）。 / Same, with an injectable path (for
     *  tests; the production path is the no-arg overload). */
    static void loadColorConfig(Path path) {
        try {
            if (Files.exists(path)) {
                var props = new Properties();
                try (var is = Files.newInputStream(path)) { props.load(is); }
                int[] c = DEFAULT_COLORS.clone();
                for (int i = 0; i < NUM_COLORS; i++) {
                    String v = props.getProperty("color." + COLOR_KEYS[i]);
                    if (v != null && v.length() == 8) try { c[i] = (int)(Long.parseLong(v, 16) & 0xFFFFFFFFL); } catch (Exception ignored) { LOGGER.debug("Bad hex color value: {}", v); }
                }
                ACTIVE.setColors(c);
            } else if (ACTIVE.themeIndex > 0) {
                ACTIVE.applyPreset(ACTIVE.themeIndex);
            }
        } catch (Exception e) { LOGGER.error("Failed to load color config", e); }
    }

    /** 同上，路径可注入（测试用）。 / Same, with an injectable path (for tests). */
    static void saveColorConfig(Path path) {
        try {
            Files.createDirectories(path.getParent());
            var props = new Properties();
            if (Files.exists(path))
                try (var is = Files.newInputStream(path)) { props.load(is); }
            int[] c = ACTIVE.snapshot();
            for (int i = 0; i < NUM_COLORS; i++) props.setProperty("color." + COLOR_KEYS[i], String.format("%08X", c[i]));
            try (var os = Files.newOutputStream(path)) { props.store(os, "Create: Schematic Compute Theme"); }
        } catch (Exception e) { LOGGER.error("Failed to save color config", e); }
    }
}
