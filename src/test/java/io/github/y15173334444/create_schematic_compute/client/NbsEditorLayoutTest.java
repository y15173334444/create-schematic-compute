package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NBS 编辑器批次一回归：面板几何单一真相源（tech-debt P2-22）、导入列表滚动钳位、
 * 上传上限契约（压测临时 1MB 已回收 256KB）、失焦提交不制造撤销记录（P3 失焦丢字的内核侧不变量）。
 * Batch-1 regressions for the NBS editor: single-source panel geometry (tech-debt P2-22),
 * import-list scroll clamp, the upload-cap contract (the 1 MB density-soak value is retired),
 * and the kernel invariant behind the blur-commit fix (a blur commit never records undo).
 */
class NbsEditorLayoutTest {

    // ══════════════ 顶栏矩形表 / top-bar rect table ══════════════

    @Test
    @DisplayName("top-bar rects are one right-to-left row, 2px gaps, no overlap / 顶栏矩形右起、2px 间隙、无重叠")
    void testTopBarRects() {
        int width = 400;
        int[][] r = NbsEditorScreen.topBarRects(width);
        assertEquals(8, r.length, "close/save/guide/export/import/redo/undo/view");
        int rightEdge = width - 4;
        for (int[] b : r) {
            assertEquals(4, b[1], "all buttons sit at y=4");
            assertEquals(18, b[3], "all buttons are 18px tall");
            assertEquals(2, rightEdge - (b[0] + b[2]), "2px gap to the previous (right-side) button");
            rightEdge = b[0];
        }
        assertTrue(rightEdge > 0, "the leftmost button stays on screen at width=400");
    }

    @Test
    @DisplayName("top-bar rects follow the window width / 顶栏矩形随窗宽平移")
    void testTopBarRectsFollowWidth() {
        int[][] a = NbsEditorScreen.topBarRects(400);
        int[][] b = NbsEditorScreen.topBarRects(500);
        for (int i = 0; i < a.length; i++)
            assertEquals(a[i][0] + 100, b[i][0], "100px wider window shifts every button 100px");
    }

    // ══════════════ 传输条矩形表 / transport-bar rect table ══════════════

    @Test
    @DisplayName("transport trio never overlaps and the tempo box follows the label width / 走带三键无重叠、速度框随标签宽")
    void testTransportBarRects() {
        int width = 420, height = 300;
        int[][] r = NbsEditorScreen.transportBarRects(width, height, 30);
        assertEquals(7, r.length);
        // 走带三键互不重叠 / the transport trio never overlaps
        int prevRight = -1;
        for (int i = NbsEditorScreen.TR_PLAY; i <= NbsEditorScreen.TR_LOOP; i++) {
            assertTrue(r[i][0] > prevRight, "button " + i + " starts after the previous one");
            prevRight = r[i][0] + r[i][2];
        }
        // 速度框起点 = 标签起点(148) + 标签宽 + 4，随语言标签宽平移
        // The tempo box starts at label start (148) + label width + 4 — locale-dependent.
        int[][] rWide = NbsEditorScreen.transportBarRects(width, height, 60);
        assertEquals(148 + 30 + 4, r[NbsEditorScreen.TR_TEMPO][0]);
        assertEquals(148 + 60 + 4, rWide[NbsEditorScreen.TR_TEMPO][0]);
        assertEquals(r[NbsEditorScreen.TR_TEMPO][0] + 30, rWide[NbsEditorScreen.TR_TEMPO][0],
            "a 30px wider tempo label shifts the box 30px");
        // 乐器前后键贴右缘、标签框夹在中间 / instrument buttons hug the right edge, label between
        assertEquals(width - 20, r[NbsEditorScreen.TR_INST_NEXT][0]);
        assertEquals(width - 124, r[NbsEditorScreen.TR_INST_PREV][0]);
        assertEquals(width - 108, r[NbsEditorScreen.TR_INST_LABEL][0]);
    }

    // ══════════════ 导入列表滚动钳位 / import-list scroll clamp ══════════════

    @Test
    @DisplayName("import scroll clamps to [0, files - visible rows] / 导入滚动停在可见窗内")
    void testImportScrollClamp() {
        assertEquals(0, NbsEditorScreen.clampImportScroll(-5, 30), "never below 0");
        assertEquals(0, NbsEditorScreen.clampImportScroll(0, 30));
        assertEquals(7, NbsEditorScreen.clampImportScroll(7, 30), "mid-range passes through");
        assertEquals(30 - NbsEditorScreen.IMPORT_ROWS, NbsEditorScreen.clampImportScroll(999, 30),
            "the tail row stays visible / 尾行常驻可见");
        assertEquals(0, NbsEditorScreen.clampImportScroll(999, 5),
            "fewer files than rows → pinned at 0 / 文件不足一屏时钉在 0");
        assertEquals(0, NbsEditorScreen.clampImportScroll(999, 0), "empty list → 0");
        // 滚动条像素模型 → 行模型的桥 / the scrollbar's pixel→row bridge
        assertEquals(3, NbsEditorScreen.importScrollFromPx(3 * NbsEditorScreen.ROW_H, 30), "exact row pixels");
        assertEquals(3, NbsEditorScreen.importScrollFromPx(3 * NbsEditorScreen.ROW_H + 5, 30), "rounds to the nearest row");
        assertEquals(30 - NbsEditorScreen.IMPORT_ROWS, NbsEditorScreen.importScrollFromPx(99999, 30),
            "and clamps at the tail");
    }

    // ══════════════ 滚动条 / scrollbars ══════════════

    @Test
    @DisplayName("thumb is proportional to visible/total, clamped and grabbable / 滑块比例、钳位、可抓")
    void testThumbRect() {
        // 横向：200px 轨道、总量 100、可见 25 → 滑块 50px
        int[] t = NbsScrollBar.thumbRect(0, 0, 200, 10, 100, 25, 0);
        assertEquals(50, t[2], "thumb = track * visible / total");
        assertEquals(0, t[0], "at pos 0 the thumb hugs the start");
        int[] t2 = NbsScrollBar.thumbRect(0, 0, 200, 10, 100, 25, 75);
        assertEquals(150, t2[0], "at max pos the thumb hugs the end");
        int[] t3 = NbsScrollBar.thumbRect(0, 0, 200, 10, 10000, 10, 0);
        assertEquals(12, t3[2], "minimum grabbable thumb");
        // 纵向同理 / vertical likewise
        int[] v = NbsScrollBar.thumbRect(0, 0, 10, 400, 200, 50, 0);
        assertEquals(100, v[3]);
        assertEquals(0, v[1]);
    }

    @Test
    @DisplayName("thumb drag maps proportionally; track click pages / 拖动按比例、点轨道翻页")
    void testScrollBarMapping() {
        assertEquals(75, NbsScrollBar.posFromThumbDrag(200, 50, 200, 100, 25), "drag to the end");
        assertEquals(0, NbsScrollBar.posFromThumbDrag(200, 50, 0, 100, 25), "drag to the start");
        assertEquals(25, NbsScrollBar.posFromTrackClick(10, 25, 50, 50, 100, 25), "click before the thumb pages back");
        assertEquals(75, NbsScrollBar.posFromTrackClick(90, 25, 50, 50, 100, 25), "click after the thumb pages forward");
        assertEquals(0, NbsScrollBar.posFromTrackClick(10, 25, 50, 10, 100, 25), "clamped at the start");
        assertEquals(75, NbsScrollBar.posFromTrackClick(90, 25, 50, 70, 100, 25), "clamped at the end");
    }

    // ══════════════ 上限契约 / upload cap contract ══════════════

    @Test
    @DisplayName("song cap is back to 256 KB (density-soak value retired) / 曲目上限回归 256 KB")
    void testMaxBytesRestored() {
        assertEquals(256 * 1024, NbsSong.MAX_BYTES,
            "the temporary 1 MB density-soak cap was to be restored after the soak / 压测后按 TODO 回归");
    }

    // ══════════════ 失焦提交不制造撤销 / blur commits never record undo ══════════════

    @Test
    @DisplayName("no-op layer-name/tempo sets record no undo (blur-commit safe) / 同值提交不记撤销")
    void testNoOpSetRecordsNoUndo() {
        var kernel = new NbsEditorKernel(new NbsSong());
        var song = kernel.song();
        String sameName = song.layers.get(0).name;
        kernel.setLayerName(0, sameName);
        kernel.setTempo(song.tempo);
        assertFalse(kernel.performUndo(), "an unchanged set must not push an undo entry");
        assertFalse(kernel.performRedo(), "and leaves the stacks empty");
    }

    // ══════════════ 层行小按钮几何（单一真相源）/ layer-row button geometry (single source) ══════════════

    @Test
    @DisplayName("layer-row buttons: in-row, right-aligned, non-overlapping / 行内小按钮几何")
    void testLayerRowButtonsGeometry() {
        for (int rowH : new int[]{13, 20}) {           // 卷帘行 / 层列行
            int rowW = 164;
            int[][] r = NbsEditorScreen.layerRowButtons(rowW, rowH);
            assertEquals(6, r.length, "听/细/锁/↑/↓/删 six buttons");
            int prevRight = -1;
            for (int[] b : r) {
                assertTrue(b[2] > 0 && b[3] > 0, "buttons have area");
                assertTrue(b[1] >= 0 && b[1] + b[3] <= rowH, "vertically inside the row");
                assertTrue(b[0] > prevRight, "ordered left to right without overlap");
                assertTrue(b[0] + b[2] <= rowW, "inside the row width");
                prevRight = b[0] + b[2];
            }
            // 名称列有得画（按钮区左缘留出名称宽）/ the name column keeps room
            assertTrue(r[0][0] >= 40, "name column keeps a usable width");
        }
    }

    // ══════════════ 卷帘层头 + 总览面板几何 / roll header & overview geometry ══════════════

    @Test
    @DisplayName("roll header slots fit the 176px panel without overlap / 卷帘层头七槽无重叠且不出面板")
    void testRollHeaderRects() {
        int[][] r = NbsEditorScreen.rollHeaderRects();
        assertEquals(7, r.length, "◀/名称/▶/总览/细/加层/删 seven slots");
        int prevRight = -1;
        for (int[] b : r) {
            assertTrue(b[2] > 0 && b[3] > 0, "slots have area");
            assertTrue(b[0] > prevRight, "ordered left to right without overlap");
            assertTrue(b[0] + b[2] <= 176, "inside the panel width");
            prevRight = b[0] + b[2];
        }
        assertTrue(r[NbsEditorScreen.HB_NAME][2] >= 60, "the name slot keeps a usable width");
        assertEquals(r[NbsEditorScreen.HB_PREV][1], r[NbsEditorScreen.HB_DELETE][1], "one header row");
    }

    @Test
    @DisplayName("overview rows tile and the scroll clamp is pixel-exact / 总览行首尾相接、滚动钳位精确")
    void testOverviewGeometry() {
        int[] a = NbsEditorScreen.layerOverviewRow(800, 600, 3, 0);
        int[] b = NbsEditorScreen.layerOverviewRow(800, 600, 4, 0);
        assertEquals(a[1] + NbsEditorScreen.OVERVIEW_ROW_H, b[1], "rows tile exactly");
        assertEquals(10, NbsEditorScreen.OVERVIEW_VIEW_H / NbsEditorScreen.OVERVIEW_ROW_H, "ten rows per viewport");
        assertEquals(0, NbsEditorScreen.clampOverviewScroll(-5, 3), "negative scroll clamps to 0");
        assertEquals(0, NbsEditorScreen.clampOverviewScroll(999, 3), "3 layers = 42px content < 140px viewport");
        assertEquals(14 * NbsEditorScreen.OVERVIEW_ROW_H - NbsEditorScreen.OVERVIEW_VIEW_H,
            NbsEditorScreen.clampOverviewScroll(999, 14), "14 layers clamp at content − viewport");
    }
}
