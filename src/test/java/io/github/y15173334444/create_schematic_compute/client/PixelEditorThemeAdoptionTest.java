package io.github.y15173334444.create_schematic_compute.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 像素编辑器三色收编回潮锁（2026-10-31 拍板：C_BTN/C_HOVER/C_SEL 复用 EditorTheme 现有槽位，
 * 不新增专用槽位）。收编前的病灶与「NBS 收编前」同族：按钮底/悬停/选中/删除危险写死字面量，
 * 换主题不跟。本批把三件套（Screen/ToolRail/FrameStrip）的按钮底→insetBg、悬停→hover、
 * 选中→accent（NBS 收编同款）、删除危险→error，帧拖拽高亮→hover（与选中区分）。
 * 本测试用**源码锚**钉死两件事：
 * <ol>
 *   <li>四个旧字面量（0xFF3A3428/0xFF5A4A3A/0xFF3A5A2A/0xFF7A4A3A）不再出现在三个文件里
 *       ——常量或内联回潮都会红；</li>
 *   <li>每个文件都以 {@code EditorTheme.get()} 实时取值——后续有人"顺手"改回捕获形态即红。</li>
 * </ol>
 * 源码锚路径按批 4 先例用硬编码相对路径（测试不从工作目录推断源码位置）。
 * Pixel-editor trio-adoption regression lock (2026-10-31 ruling: C_BTN/C_HOVER/C_SEL reuse
 * existing EditorTheme slots, no dedicated slots). The pre-adoption disease matches the NBS
 * editor's: button bg / hover / selected / destructive were hardcoded literals that ignored
 * theme switches. This batch maps them in the Screen/ToolRail/FrameStrip trio onto
 * insetBg/hover/accent/error (selected→accent per the NBS shape; the dragged-frame highlight
 * → hover to stay distinguishable from selection). This test pins two things with **source
 * anchors**: (1) the four old literals (0xFF3A3428/0xFF5A4A3A/0xFF3A5A2A/0xFF7A4A3A) no
 * longer appear in the three files — a constant or inline regression turns red; (2) every
 * file still fetches live values via {@code EditorTheme.get()} — anyone "conveniently"
 * reverting to a captured shape turns red. Anchor paths use hardcoded relative paths per the
 * batch-4 precedent (tests never infer source locations from the working directory).
 */
class PixelEditorThemeAdoptionTest {

    private static final List<String> FILES = List.of(
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/PixelEditorScreen.java",
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/PixelEditorToolRail.java",
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/PixelEditorFrameStrip.java");

    private static final String[] RETIRED_LITERALS = {
        "0xFF3A3428", // 旧按钮底 / old button bg
        "0xFF5A4A3A", // 旧悬停 / old hover
        "0xFF3A5A2A", // 旧选中 / old selected
        "0xFF7A4A3A", // 旧删除危险 / old destructive
    };

    @Test
    @DisplayName("四个旧字面量不得回潮到像素编辑器三件套 / the four retired literals must not reappear in the trio")
    void retiredLiteralsStayRetired() throws Exception {
        for (String file : FILES) {
            String src = Files.readString(Path.of(file));
            for (String literal : RETIRED_LITERALS) {
                assertFalse(src.contains(literal),
                    file + " 重新出现旧字面量 " + literal + "——按钮底/悬停/选中/删除危险必须走 EditorTheme 槽位"
                        + "（复用现有槽位拍板），换主题才跟 / " + file + " brought back the retired literal "
                        + literal + " — the themed roles must read EditorTheme slots so themes follow");
            }
        }
    }

    @Test
    @DisplayName("三件套各自仍以 EditorTheme.get() 实时取值 / each file still reads live values via EditorTheme.get()")
    void trioStillReadsTheLiveTheme() throws Exception {
        for (String file : FILES) {
            String src = Files.readString(Path.of(file));
            assertTrue(src.contains("EditorTheme.get().insetBg()"),
                file + " 缺按钮底槽位取值（insetBg）/ missing the button-bg slot read (insetBg)");
            assertTrue(src.contains("EditorTheme.get().hover()"),
                file + " 缺悬停槽位取值（hover）/ missing the hover slot read (hover)");
        }
        // 选中→accent：屏幕与工具栏（FrameStrip 的选中帧也走 accent）
        assertTrue(Files.readString(Path.of(FILES.get(0))).contains("EditorTheme.get().error()"),
            "Screen 的危险按钮应走 error 槽位 / the Screen's destructive buttons must read the error slot");
        assertTrue(Files.readString(Path.of(FILES.get(1))).contains("EditorTheme.get().accent()"),
            "ToolRail 的选中态应走 accent 槽位（NBS 同款）/ the ToolRail's selected state must read accent (NBS shape)");
        String frameStrip = Files.readString(Path.of(FILES.get(2)));
        assertTrue(frameStrip.contains("EditorTheme.get().error()"),
            "FrameStrip 的删除按钮应走 error 槽位 / the FrameStrip's delete button must read the error slot");
        assertTrue(frameStrip.contains("EditorTheme.get().accent()"),
            "FrameStrip 的选中帧高亮应走 accent 槽位 / the FrameStrip's selected-frame highlight must read accent");
    }
}
