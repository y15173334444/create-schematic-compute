package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 像素编辑器内核：像素数组上的绘制算法 + 撤销/重做状态机（自 {@link PixelEditorScreen} 拆分，
 * docs/gui-decomposition-plan.md 步骤 4 第一刀）。**不持有任何视图状态**——zoom / pan / 工具 /
 * 笔刷大小 / 透明度仍归屏幕，绘制时作为参数传入；{@code frameIndex} 由调用方传入、
 * {@link #performUndo}/{@link #performRedo} 返回钳制后的新值。
 *
 * <p>拆分动机：算法与撤销栈是纯数据操作（只依赖 {@link GraphNode}），可在无 Minecraft
 * bootstrap 的 JUnit 里测试（{@code PixelEditorKernelTest}）；屏幕只保留渲染与输入。
 * Pixel editor kernel: the painting algorithms over the pixel array plus the undo/redo
 * state machine, split out of {@link PixelEditorScreen} (roadmap step 4, first cut).
 * <b>No view state lives here</b> — zoom / pan / tool / brush size / opacity stay on the
 * screen and are passed in as parameters; the caller passes {@code frameIndex} and gets the
 * clamped value back from {@link #performUndo}/{@link #performRedo}. The split exists because
 * the algorithms and stacks are pure data operations (depending only on {@link GraphNode}),
 * testable in plain JUnit without a Minecraft bootstrap (see {@code PixelEditorKernelTest});
 * the screen keeps rendering and input.</p>
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，仅隐式外层访问改为参数 / 节点字段；撤销栈的
 * 「一次笔划一条」幂等标志（{@code strokeUndoCaptured}）属笔划生命周期，留在屏幕侧。
 * <b>Behaviour-preserving</b>: bodies moved verbatim, implicit outer-class accesses turned
 * into parameters / node fields; the one-snapshot-per-stroke flag ({@code strokeUndoCaptured})
 * belongs to the stroke lifecycle and stays on the screen.</p>
 */
public final class PixelEditorKernel {

    /** 撤销栈深度上限。 / undo-stack depth cap. */
    public static final int MAX_UNDO = 100;

    // 撤销/重做栈：快照 + 元标记（-1=像素数组，N≥0=帧数标记，-2=尺寸标记）
    // undo/redo stacks: snapshots + meta (-1 = pixel array, N ≥ 0 = frame count, -2 = size marker).
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<Integer> undoMeta = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();
    private final List<Integer> redoMeta = new ArrayList<>();

    // ══════════════ 绘制算法 / painting algorithms（static 纯函数，只改写像素数组）══════════════

    /** alpha-over 混合：out = src·o + dst·(1−o)，各通道含 alpha（透明度滑杆用）。
     *  Alpha-over blend per channel (incl. alpha) for the opacity slider. */
    public static int blendAlpha(int dst, int src, float o) {
        int sa = (src >>> 24) & 0xFF, sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        int da = (dst >>> 24) & 0xFF, dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        int a = Math.round(sa * o + da * (1 - o));
        int r = Math.round(sr * o + dr * (1 - o));
        int g = Math.round(sg * o + dg * (1 - o));
        int b = Math.round(sb * o + db * (1 - o));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 方形笔刷落点（透明度混合，越界裁剪）。/ square brush stamp (alpha-blended, bounds-clipped). */
    public static void paintBrush(GraphNode node, int cx, int cy, int color, int brushSize, float brushOpacity) {
        int[] px = node.imagePixels;
        if (px == null) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int half = brushSize / 2;
        boolean full = brushOpacity >= 0.999f;
        for (int dy = -half; dy < brushSize - half; dy++)
            for (int dx = -half; dx < brushSize - half; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < imgW && y >= 0 && y < imgH) {
                    int idx = y * imgW + x;
                    px[idx] = full ? color : blendAlpha(px[idx], color, brushOpacity);
                }
            }
    }

    /** 泛洪填充（4 邻接；透明度过小导致无视觉变化时直接跳过，避免无限入栈）。
     *  Flood fill (4-neighbour); skipped when tiny opacity rounds back to the target
     *  (which would otherwise keep re-pushing neighbours forever). */
    public static void floodFill(GraphNode node, int sx, int sy, int color, float brushOpacity) {
        int[] px = node.imagePixels;
        if (px == null) return;
        int imgW = node.imageWidth, imgH = node.imageHeight;
        int target = px[sy * imgW + sx];
        if (target == color) return;
        boolean full = brushOpacity >= 0.999f;
        // 透明度过小（含 0）时混合结果可能舍入回原色：填充无视觉变化且会无限入栈，直接跳过
        // Tiny/zero opacity can round the blend back to the target color: the fill would be a
        // no-op yet keep re-pushing neighbours forever — bail out instead.
        if (!full && blendAlpha(target, color, brushOpacity) == target) return;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0], y = p[1];
            if (x < 0 || x >= imgW || y < 0 || y >= imgH) continue;
            int idx = y * imgW + x;
            if (px[idx] != target) continue;
            px[idx] = full ? color : blendAlpha(px[idx], color, brushOpacity);
            stack.push(new int[]{x + 1, y});
            stack.push(new int[]{x - 1, y});
            stack.push(new int[]{x, y + 1});
            stack.push(new int[]{x, y - 1});
        }
    }

    /** Bresenham 直线（逐格走笔刷）。/ Bresenham line (brush-stamped cell by cell). */
    public static void drawLineCells(GraphNode node, int x0, int y0, int x1, int y1, int color, int brushSize, float brushOpacity) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            paintBrush(node, x0, y0, color, brushSize, brushOpacity);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    /** 矩形周界（逐格走笔刷）。/ rectangle perimeter (brush-stamped cell by cell). */
    public static void drawRectCells(GraphNode node, int x0, int y0, int x1, int y1, int color, int brushSize, float brushOpacity) {
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
        for (int x = minX; x <= maxX; x++) { paintBrush(node, x, minY, color, brushSize, brushOpacity); paintBrush(node, x, maxY, color, brushSize, brushOpacity); }
        for (int y = minY; y <= maxY; y++) { paintBrush(node, minX, y, color, brushSize, brushOpacity); paintBrush(node, maxX, y, color, brushSize, brushOpacity); }
    }

    /** 帧列表延迟初始化（为空时建一帧全透明画布）。/ lazy frames init (one blank canvas when null). */
    public static List<int[]> ensureFrames(GraphNode node) {
        if (node.imageSequenceFrames == null) {
            node.imageSequenceFrames = new ArrayList<>();
            int[] f = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(f, 0x00000000);
            node.imageSequenceFrames.add(f);
        }
        return node.imageSequenceFrames;
    }

    // ══════════════ 撤销/重做状态机 / undo & redo state machine ══════════════

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** 捕获一次笔划撤销快照（整帧克隆；「一次笔划一条」的幂等标志在屏幕侧）。
     *  Capture a stroke undo snapshot (full-frame clone; the once-per-stroke flag lives on the screen). */
    public void captureStrokeUndo(int[] currentPixels) {
        if (undoStack.size() < MAX_UNDO) {
            undoStack.add(currentPixels.clone());
            undoMeta.add(-1);
            redoStack.clear();
            redoMeta.clear();
        }
    }

    /** 帧数变更（新建/删除/重排）的撤销快照：全部帧 + 帧数标记。 / Frames-list undo snapshot. */
    public void pushFramesUndo(GraphNode node) {
        List<int[]> frames = ensureFrames(node);
        int n = frames.size();
        if (undoStack.size() + n + 1 > MAX_UNDO) return;
        for (int i = n - 1; i >= 0; i--) {
            undoStack.add(frames.get(i).clone());
            undoMeta.add(-1);
        }
        undoStack.add(new int[]{n});
        undoMeta.add(n);
        redoStack.clear();
        redoMeta.clear();
    }

    /** 尺寸变更撤销快照（meta=-2 标记 {oldW,oldH} + 全部帧）。 / Resize undo snapshot. */
    public void pushResizeUndo(GraphNode node, int oldW, int oldH) {
        int count = 1;
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            count = node.imageSequenceFrames.size();
        } else if (node.imagePixels == null) {
            count = 0;
        }
        if (undoStack.size() + count + 1 > MAX_UNDO) return;
        for (int i = count - 1; i >= 0; i--) {
            int[] f = node.type == NodeType.IMAGE_SEQUENCE
                ? node.imageSequenceFrames.get(i) : node.imagePixels;
            undoStack.add(f.clone());
            undoMeta.add(-1);
        }
        undoStack.add(new int[]{oldW, oldH});
        undoMeta.add(-2);
        redoStack.clear();
        redoMeta.clear();
    }

    /** 像素级撤销：节点数据就地改写，返回钳制后的 frameIndex（无操作时原样返回）。
     *  Pixel-level undo: rewrites the node in place, returns the clamped frameIndex
     *  (unchanged when there is nothing to undo). */
    public int performUndo(GraphNode node, int frameIndex) {
        if (undoStack.isEmpty()) return frameIndex;
        int[] top = undoStack.remove(undoStack.size() - 1);
        int meta = undoMeta.remove(undoMeta.size() - 1);
        if (meta >= 0) {
            int count = meta;
            int curCount = node.imageSequenceFrames != null ? node.imageSequenceFrames.size() : 0;
            for (int i = curCount - 1; i >= 0; i--) {
                redoStack.add(node.imageSequenceFrames.get(i).clone());
                redoMeta.add(-1);
            }
            redoStack.add(new int[]{curCount});
            redoMeta.add(curCount);
            List<int[]> frames = ensureFrames(node);
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, undoStack.remove(undoStack.size() - 1));
                undoMeta.remove(undoMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && !frames.isEmpty()) node.imagePixels = frames.get(frameIndex);
        } else if (meta == -2) {
            frameIndex = applyResizeUndoRedo(top, redoStack, redoMeta, undoStack, undoMeta, node, frameIndex);
        } else {
            redoStack.add(node.imagePixels.clone());
            redoMeta.add(-1);
            node.imagePixels = top;
            if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
                && frameIndex >= 0 && frameIndex < node.imageSequenceFrames.size()) {
                node.imageSequenceFrames.set(frameIndex, top);
            }
        }
        return frameIndex;
    }

    /** 像素级重做：节点数据就地改写，返回钳制后的 frameIndex（无操作时原样返回）。
     *  Pixel-level redo: rewrites the node in place, returns the clamped frameIndex
     *  (unchanged when there is nothing to redo). */
    public int performRedo(GraphNode node, int frameIndex) {
        if (redoStack.isEmpty()) return frameIndex;
        int[] top = redoStack.remove(redoStack.size() - 1);
        int meta = redoMeta.remove(redoMeta.size() - 1);
        if (meta >= 0) {
            int count = meta;
            int curCount = node.imageSequenceFrames != null ? node.imageSequenceFrames.size() : 0;
            for (int i = curCount - 1; i >= 0; i--) {
                undoStack.add(node.imageSequenceFrames.get(i).clone());
                undoMeta.add(-1);
            }
            undoStack.add(new int[]{curCount});
            undoMeta.add(curCount);
            List<int[]> frames = ensureFrames(node);
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, redoStack.remove(redoStack.size() - 1));
                redoMeta.remove(redoMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && !frames.isEmpty()) node.imagePixels = frames.get(frameIndex);
        } else if (meta == -2) {
            frameIndex = applyResizeUndoRedo(top, undoStack, undoMeta, redoStack, redoMeta, node, frameIndex);
        } else {
            undoStack.add(node.imagePixels.clone());
            undoMeta.add(-1);
            node.imagePixels = top;
            if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null
                && frameIndex >= 0 && frameIndex < node.imageSequenceFrames.size()) {
                node.imageSequenceFrames.set(frameIndex, node.imagePixels);
            }
        }
        return frameIndex;
    }

    /** 尺寸标记（meta=-2）恢复：当前状态存对侧栈，从本侧栈恢复旧尺寸与全部帧。
     *  Resize marker restore: save current state to the opposite stack, restore old size + frames. */
    private static int applyResizeUndoRedo(int[] sizeMarker,
                                     List<int[]> saveToStack, List<Integer> saveToMeta,
                                     List<int[]> popFromStack, List<Integer> popFromMeta,
                                     GraphNode node, int frameIndex) {
        int count = 1;
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            count = node.imageSequenceFrames.size();
        } else if (node.imagePixels == null) {
            count = 0;
        }
        int curW = node.imageWidth, curH = node.imageHeight;
        for (int i = count - 1; i >= 0; i--) {
            int[] f = node.type == NodeType.IMAGE_SEQUENCE
                ? node.imageSequenceFrames.get(i) : node.imagePixels;
            saveToStack.add(f.clone());
            saveToMeta.add(-1);
        }
        saveToStack.add(new int[]{curW, curH});
        saveToMeta.add(-2);
        node.imageWidth = sizeMarker[0]; node.imageHeight = sizeMarker[1];
        if (node.type == NodeType.IMAGE_SEQUENCE && node.imageSequenceFrames != null) {
            List<int[]> frames = node.imageSequenceFrames;
            frames.clear();
            for (int i = 0; i < count; i++) {
                frames.add(0, popFromStack.remove(popFromStack.size() - 1));
                popFromMeta.remove(popFromMeta.size() - 1);
            }
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            if (frameIndex >= 0 && frameIndex < frames.size()) node.imagePixels = frames.get(frameIndex);
        } else if (count > 0) {
            node.imagePixels = popFromStack.remove(popFromStack.size() - 1);
            popFromMeta.remove(popFromMeta.size() - 1);
        }
        return frameIndex;
    }
}
