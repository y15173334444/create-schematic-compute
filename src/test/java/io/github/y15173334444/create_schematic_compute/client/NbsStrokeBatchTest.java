package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NBS 笔划分组回归：按下格必须与拖过格同批——一次笔划 = 一条撤销（条目 5）。
 * 直接驱动 grid 的 mouseDown/mouseDrag（无 MC bootstrap）：开组责任在 mouseDown 内部，
 * 屏幕层不再事后 beginBatch。
 */
class NbsStrokeBatchTest {

    /** 计数撤销栈深度（performUndo 逐条弹）。 */
    private static int undoDepth(NbsEditorKernel k) {
        int n = 0;
        while (k.performUndo()) n++;
        return n;
    }

    @Test
    @DisplayName("卷帘涂抹：按下格 + 拖过格 = 一条撤销 / roll paint: press + drag = one undo")
    void rollPaintStrokeIsOneUndo() {
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        NbsPianoRoll roll = new NbsPianoRoll();
        // 默认 scrollTick=0 / viewTopKey=60 / pxPerTick=10 / pxPerKey=9；GUTTER=30 RULER=12
        // 格 A: tick0/key57 → mx=35 my=43 ; 格 B: tick1/key57 → mx=45 my=43
        assertTrue(roll.mouseDown(0, 0, 400, 400, 35, 43, 0, k, 0, 60), "press consumed");
        assertTrue(roll.mouseDrag(0, 0, 45, 43, k, 0, 60), "drag consumed");
        k.endBatch();
        roll.mouseUp();

        assertNotNull(song.getNote(0, 0), "press note landed");
        assertNotNull(song.getNote(1, 0), "drag note landed");
        assertEquals(1, undoDepth(k), "一次笔划必须只有一条撤销 / one stroke must be one undo");
    }

    @Test
    @DisplayName("层序擦除：按下格 + 拖过格 = 一条撤销 / lane erase: press + drag = one undo")
    void laneEraseStrokeIsOneUndo() {
        NbsSong song = new NbsSong();
        NbsEditorKernel k = new NbsEditorKernel(song);
        // 先放两格待擦（直接内核放，各自独立撤销，随后清栈）
        song.putNote(0, 0, 0, 60, 100, 100, 0);
        song.putNote(1, 0, 0, 60, 100, 100, 0);
        assertNotNull(song.getNote(0, 0), "setup tick0");
        assertNotNull(song.getNote(1, 0), "setup tick1");
        NbsLaneGrid lane = new NbsLaneGrid();

        // lane 默认 pxPerTick=10、rowH 由参数给；GRID 判定同 roll（GUTTER 由 lane 自有常量）
        // 用反射-free 的方式：直接以 screen 传入的 area/坐标试命中；先确认 hit 为 GRID
        // lane 无 GUTTER、pxPerTick=16：mx=5→tick0，mx=20→tick1；RULER_H=18、rowH=20：my=20→layer0
        assertEquals(NbsLaneGrid.Zone.GRID, lane.hit(0, 0, 400, 400, 5, 20), "press in grid");
        assertTrue(lane.mouseDown(0, 0, 400, 400, 5, 20, 1, k, 0, 60, 0, 20), "erase press consumed");
        assertTrue(lane.mouseDrag(0, 0, 400, 400, 20, 20, k, 0, 20), "erase drag consumed");
        k.endBatch();
        lane.mouseUp();

        assertNull(song.getNote(0, 0), "press note erased");
        assertNull(song.getNote(1, 0), "drag note erased");
        assertEquals(1, undoDepth(k), "一次擦除笔划必须只有一条撤销 / one erase stroke must be one undo");
    }
}
