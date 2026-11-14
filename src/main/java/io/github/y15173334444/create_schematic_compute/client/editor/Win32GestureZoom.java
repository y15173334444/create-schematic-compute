package io.github.y15173334444.create_schematic_compute.client.editor;

import com.mojang.logging.LogUtils;
import com.sun.jna.CallbackReference;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;

/**
 * GZ-2 触屏/触摸板捏合通道（Win32 手势钩子，docs/graph-editor-gesture-zoom-plan.md +
 * docs/high-precision-gesture-zoom-brief.md）：实测 Windows 把捏合**降级成滚轮刻度**投给
 * 不处理手势的窗口——官方 Legacy Support 机制是「未处理的 WM_GESTURE 冒泡到 DefWindowProc
 * 后映射成 WM_MOUSEWHEEL+MK_CONTROL」，也有驱动层直接注入滚轮的路径。故双通道：
 * <ol>
 * <li><b>主通道</b>：SetGestureConfig 申领 GID_ZOOM，读手势帧**手指间距比** 1:1 直驱；</li>
 * <li><b>稳健回退</b>：在 WndProc 拦截**带 MK_CONTROL 且物理 Ctrl 未按**的滚轮消息
 *     （= 捏合合成，消息级 Ctrl 而 GLFW 层看不到）按细刻度映射——覆盖不走 WM_GESTURE 的
 *     驱动合成路径。</li>
 * </ol>
 * 两通道 300ms 去重（手势通道优先）；GID_PAN 不申领，两指平移继续走滚轮合成给 GZ-1。
 * 捏合滚轮与真滚轮在 GLFW 层不可区分（MK_CONTROL 被丢弃），但本钩子在 GLFW 之前看得到。
 * GZ-2 touch/trackpad pinch channel (Win32 gesture hook): Windows degrades pinch to
 * wheel notches for windows that do not handle gestures — the official Legacy Support
 * mechanism maps unhandled WM_GESTURE to WM_MOUSEWHEEL+MK_CONTROL at DefWindowProc, and
 * some driver stacks inject wheel input directly. Hence two channels: (1) primary —
 * claim GID_ZOOM and drive zoom 1:1 from the finger-distance ratio; (2) robust fallback
 * — intercept wheel messages carrying MK_CONTROL while physical Ctrl is up (synthesized
 * pinch; message-level Ctrl is invisible at the GLFW layer) and map them on a fine notch.
 * The channels dedupe over 300ms (gesture wins). GID_PAN stays unclaimed so two-finger
 * pan keeps feeding GZ-1 as wheel scroll.
 *
 * <p>实测语义（本机 Win11 build 26300，与经典文档不同）：WM_GESTURE 的 wParam=手势 ID
 * （GID_*）、lParam=HGESTUREINFO；GESTUREINFO 56B 但**距离字段在偏移 40**（@32 恒 0）；
 * ptsLocation=质心屏幕坐标。锚点=pts−窗口原点（GetWindowRect 算术；**窗口查询必须用真
 * HWND**——`Window.getWindow()` 是 GLFW 指针不是 HWND，拿它查窗必 false）。LWJGL 3.3.3
 * 的 User32 绑定没有手势三件套，raw Win32 走 JNA（MC 自带同版 5.14.0）。仅 Windows；
 * 非 Windows / 缺 JNA 静默停用；钩子内任何失败都不碰渲染线。诊断轨迹有界（每会话首几条
 * + 5s 通道计数），发布前可裁。
 * Measured semantics on this Win11 build (deviating from classic docs): WM_GESTURE's
 * wParam is the gesture id (GID_*), lParam is the HGESTUREINFO; GESTUREINFO is 56B but
 * the finger distance lives at offset 40 (@32 reads zero); ptsLocation is the centroid
 * in screen coords. The anchor is pts minus the window origin (GetWindowRect arithmetic;
 * window queries need the real HWND — `Window.getWindow()` is the GLFW pointer, not an
 * HWND, and querying with it always fails). LWJGL 3.3.3 lacks the gesture trio, raw
 * Win32 goes through JNA (MC ships the same 5.14.0). Windows only, dormant elsewhere;
 * hook failures never touch the render thread. Diagnostics are bounded (first few per
 * session + 5s channel counts) and release-trimmable.</p>
 */
final class Win32GestureZoom {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int WM_GESTURE = 0x0119;
    private static final int WM_MOUSEWHEEL = 0x020A;
    private static final int GID_BEGIN = 1;
    private static final int GID_END = 2;
    private static final int GID_ZOOM = 3;
    private static final int GID_PRESSANDTAP = 7;
    private static final int GC_ZOOM = 0x0001;
    private static final int GWLP_WNDPROC = -4;
    private static final int MK_CONTROL = 0x0008;
    private static final int VK_CONTROL = 0x11;

    /** 捏合合成滚轮的每刻度增益（实测调参；比 GZ-1 的 1.12 细，因为合成流是高频整刻度）。
     *  Per-notch gain for synthesized pinch wheel (tuned live; finer than GZ-1's 1.12
     *  because the synthesized stream is high-rate whole notches). */
    private static final double PINCH_NOTCH = 1.05;

    private static boolean attempted;
    private static boolean available;
    private static boolean gestureClaimed; // SetGestureConfig 成功才消化手势流 / consume gesture streams only when claimed
    private static long hookedHwnd;
    private static long oldWndProc;
    /** 强引用：原生回调被 GC 会炸 / strong ref: a collected native callback crashes */
    private static WndProc hookProc;

    private static long lastDist = -1;
    /** GESTUREINFO 尺寸：x64 标准 56，未解析时**每条消息只试一个候选**旋转探测（GetGestureInfo
     *  疑似一手一读——同一 handle 连读会废，第一读后其余全失败）；读通即缓存。候选覆盖 8 字节
     *  步进（新系统可能扩展了结构体）。/ GESTUREINFO size: 56 is the x64 standard. While
     *  unresolved, probe ONE candidate per message and rotate (GetGestureInfo looks
     *  single-read-per-handle — back-to-back reads on one handle die after the first);
     *  cache whichever works. Candidates step by 8 bytes (newer builds may extend the
     *  struct). */
    private static int giSize = -1;
    private static boolean giGaveUp;
    private static int giProbeIdx;
    /** 句柄源：0=wParam（经典语义），1=lParam（新系统疑似换位——实测 wParam=0x3=GID_ZOOM）。
     *  Handle source: 0=wParam (classic), 1=lParam (new builds may have swapped roles —
     *  measured wParam=0x3=GID_ZOOM). */
    private static int giHandleSource = -1;
    private static final int[] GI_SIZE_PROBES = {56, 80, 64, 88, 48, 72, 96, 104, 40, 112, 120, 128, 32};
    private static int diagMsgDumps;

    // 待消化的捏合缩放（渲染线单产单销）。锚点=质心换算（GetWindowRect 手算客户区；两种
    // 坐标系候选按窗口范围择一），换算失败回退 drain 帧鼠标坐标（指针模拟=最后单点触摸）。
    // Pending pinch zoom, render-thread single producer/consumer. The anchor is the
    // centroid conversion (GetWindowRect arithmetic; two coordinate-space candidates
    // chosen by window bounds), falling back to the drain frame's mouse coords (pointer
    // emulation = the last single touch) when the conversion fails.
    private static double pendingScale = 1.0;
    private static double pendingX, pendingY;
    private static boolean pendingAnchorValid;
    private static int diagCentroidDumps;

    // 通道去重与诊断 / channel dedupe + diagnostics
    private static long lastGestureZoomMs;
    private static long lastGestureMsgMs;
    private static long lastWheelZoomMs;
    private static int diagGestureMsgs, diagGiFails, diagWheelPinches, diagCfgFails, diagGiFailErr;
    private static long diagRaw0, diagRaw8;
    private static int diagGiOk, diagStructDumps, diagZoomTraces;
    private static long lastDiagMs;

    private Win32GestureZoom() {}

    /** user32 的最小 FFI 面（仅本类用；x64）。 / The minimal user32 FFI face (this class
     *  only; x64). */
    interface U32 extends Library {
        U32 INSTANCE = Native.load("user32", U32.class);

        long SetWindowLongPtrW(Pointer hWnd, int nIndex, long dwNewLong);
        long CallWindowProcW(long lpPrevWndFunc, Pointer hWnd, int Msg, long wParam, long lParam);
        boolean GetWindowRect(Pointer hWnd, RECT lpRect);
        boolean SetGestureConfig(Pointer hwnd, int dwReserved, int cIDs, GESTURECONFIG pGestureConfig, int cbSize);
        boolean GetGestureInfo(Pointer hGestureInfo, Pointer pGestureInfo);
        boolean CloseGestureInfoHandle(Pointer hGestureInfo);
        short GetAsyncKeyState(int vKey);
    }

    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class RECT extends Structure {
        public int left, top, right, bottom;
    }

    /** kernel32：GetLastError（GetGestureInfo 失败原因诊断）。 / kernel32: GetLastError
     *  (diagnosing GetGestureInfo failures). */
    interface K32 extends Library {
        K32 INSTANCE = Native.load("kernel32", K32.class);
        int GetLastError();
    }

    /** x64 GESTUREINFO（Win32 自然对齐：ptsLocation 后 4 字节垫到 8，共 56B）。 / x64
     *  GESTUREINFO (Win32 natural alignment, 56 bytes). */
    @Structure.FieldOrder({"dwID", "dwWant", "dwBlock"})
    public static class GESTURECONFIG extends Structure {
        public int dwID, dwWant, dwBlock;
    }

    /** WndProc 回调（x64 调用约定统一；StdCallCallback 兼顾 32 位 ABI）。 / WndProc callback
     *  (uniform x64 calling convention; StdCallCallback keeps the 32-bit ABI honest). */
    interface WndProc extends StdCallLibrary.StdCallCallback {
        long invoke(Pointer hWnd, int msg, long wParam, long lParam);
    }

    /** 幂等装钩：窗口句柄变了（全屏切换重建窗口）就重挂。 / Idempotent install: re-hooks
     *  when the window handle changes (fullscreen toggles recreate the GLFW window). */
    static void ensureInstalled() {
        try {
            if (!attempted) {
                if (Platform.get() != Platform.WINDOWS) { attempted = true; return; }
                Class.forName("com.sun.jna.Native"); // 缺 JNA 整体停用 / dormant without JNA
                attempted = true;
                available = true;
            }
            if (!available) return;
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
            if (hwnd == 0 || hwnd == hookedHwnd) return;
            installOn(Pointer.createConstant(hwnd));
            hookedHwnd = hwnd;
        } catch (Throwable t) {
            available = false; // 钩子失败绝不炸渲染线 / never break the render thread
            LOGGER.warn("[GZ-2] gesture hook disabled: {}", t.toString());
        }
    }

    private static void installOn(Pointer hwnd) {
        GESTURECONFIG cfg = new GESTURECONFIG();
        cfg.dwID = GID_ZOOM;
        cfg.dwWant = GC_ZOOM;
        cfg.dwBlock = 0;
        gestureClaimed = U32.INSTANCE.SetGestureConfig(hwnd, 0, 1, cfg, cfg.size());
        if (!gestureClaimed) diagCfgFails++;
        if (hookProc == null) {
            hookProc = (h, msg, wParam, lParam) -> {
                try {
                    if (msg == WM_GESTURE) {
                        // 读得出=消化（手势通道）；读不出=放行到滚轮合成保底；未申领=放行
                        // （防吞两指平移）。/ Consumed when a gesture was read; falls through
                        // on read failure (wheel-synthesis floor) or when unclaimed.
                        boolean handled = onGesture(wParam, lParam);
                        return (gestureClaimed && handled) ? 0L
                            : U32.INSTANCE.CallWindowProcW(oldWndProc, h, msg, wParam, lParam);
                    }
                    if (msg == WM_MOUSEWHEEL && onSynthWheel(wParam, lParam)) return 0L;
                } catch (Throwable ignored) {
                    // 单条消息异常只吞不炸 / swallow per-message failures
                }
                return U32.INSTANCE.CallWindowProcW(oldWndProc, h, msg, wParam, lParam);
            };
        }
        oldWndProc = U32.INSTANCE.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, Pointer.nativeValue(CallbackReference.getFunctionPointer(hookProc)));
        LOGGER.info("[GZ-2] gesture hook installed: hwnd=0x{}, SetGestureConfig={}, prevWndProc={}",
            Long.toHexString(Pointer.nativeValue(hwnd)), gestureClaimed, oldWndProc != 0);
    }

    /** 主通道：GID_ZOOM 手指间距比 1:1 直驱。读法用 raw 缓冲（偏移：dwID@8、pts@24/26、
     *  ullArguments@32）——绕开 JNA 结构体对齐变量；以 dwID 合法性验收；读不出**放行**到
     *  DefWindowProc 的滚轮合成（体验保底：最差回「滚轮级」，绝不「完全不缩放」）。
     *  Primary channel: GID_ZOOM finger-distance ratio, direct 1:1. Raw-buffer reads
     *  (dwID@8, pts@24/26, ullArguments@32) sidestep JNA struct-alignment variables; dwID
     *  plausibility decides; on read failure the message falls through to DefWindowProc's
     *  wheel synthesis (graceful: worst case is wheel-level zoom, never a dead pinch). */
    private static boolean onGesture(long wParam, long lParam) {
        diagGestureMsgs++;
        lastGestureMsgMs = System.currentTimeMillis();
        // 前三条消息原始参数转储（语义审计：实测 wParam=0x3=GID_ZOOM，疑似 wParam=手势 ID、
        // lParam=句柄/数据）。/ Raw dump of the first messages (semantics audit: measured
        // wParam=0x3=GID_ZOOM — wParam looks like the gesture id, lParam the handle/data).
        if (diagMsgDumps < 10) {
            diagMsgDumps++;
            LOGGER.info("[GZ-2] raw gesture msg #{}: wParam=0x{} lParam=0x{}", diagMsgDumps,
                Long.toHexString(wParam), Long.toHexString(lParam));
        }
        Memory mem = new Memory(128);
        int id = -1;
        if (giSize > 0) {
            id = tryReadGesture(mem, giHandleSource == 1 ? lParam : wParam, giSize);
        } else if (!giGaveUp) {
            // 每条消息只试一个 (句柄源, 尺寸) 组合（一手一读嫌疑）；轮换 2×13 档，60Hz 下
            // 约半秒扫完。/ One (handle source, size) pair per message (single-read-per-handle
            // suspicion); 2×13 combos sweep in about half a second at 60Hz.
            int slot = giProbeIdx++ % (2 * GI_SIZE_PROBES.length);
            int source = slot / GI_SIZE_PROBES.length;
            int size = GI_SIZE_PROBES[slot % GI_SIZE_PROBES.length];
            id = tryReadGesture(mem, source == 1 ? lParam : wParam, size);
            if (id >= 0) {
                giSize = size;
                giHandleSource = source;
                LOGGER.info("[GZ-2] GESTUREINFO resolved: size={} handleSource={} (x64 standard is 56/wParam)",
                    size, source == 1 ? "lParam" : "wParam");
            } else if (giProbeIdx >= 2 * GI_SIZE_PROBES.length) {
                giGaveUp = true;
                diagGiFailErr = K32.INSTANCE.GetLastError();
                diagRaw0 = mem.getLong(0);
                diagRaw8 = mem.getLong(8);
                LOGGER.warn("[GZ-2] GESTUREINFO unresolved after full rotation (wParam=0x{}, lParam=0x{}, lastErr={}, raw0=0x{}, raw8=0x{}) - association channel is the floor",
                    Long.toHexString(wParam), Long.toHexString(lParam), diagGiFailErr,
                    Long.toHexString(diagRaw0), Long.toHexString(diagRaw8));
            }
        }
        if (id < 0) {
            diagGiFails++;
            diagGiFailErr = K32.INSTANCE.GetLastError();
            diagRaw0 = mem.getLong(0);
            diagRaw8 = mem.getLong(8);
            U32.INSTANCE.CloseGestureInfoHandle(Pointer.createConstant(giHandleSource == 1 ? lParam : wParam));
            return false; // 放行 → 滚轮合成保底 / fall through to wheel synthesis as the floor
        }
        diagGiOk++;
        if (diagStructDumps < 3) {
            // 成功读的整块转储：找距离字段的真实偏移（32 偏移读出常量=偏移错）。
            // Full-struct dump of successful reads: find where the distance really lives
            // (a constant at offset 32 means the offset is wrong).
            diagStructDumps++;
            LOGGER.info("[GZ-2] GESTUREINFO dump #{} (id={}): 0x{} 0x{} 0x{} 0x{} 0x{} 0x{} 0x{}",
                diagStructDumps, id,
                Long.toHexString(mem.getLong(0)), Long.toHexString(mem.getLong(8)),
                Long.toHexString(mem.getLong(16)), Long.toHexString(mem.getLong(24)),
                Long.toHexString(mem.getLong(32)), Long.toHexString(mem.getLong(40)),
                Long.toHexString(mem.getLong(48)));
        }
        try {
            if (id == GID_ZOOM) {
                // 距离字段=偏移 40（实测转储：@40=973→959→947 逐帧递减=捏合中的两指间距；
                // @32 恒 0）。32 留兜底（经典布局在此系统之外）。/ The distance lives at
                // offset 40 (measured dumps: 973→959→947 shrinking per frame = the finger
                // distance mid-pinch; offset 32 reads zero). 32 stays as the fallback for
                // classic layouts elsewhere.
                long dist = mem.getLong(40);
                if (dist == 0) dist = mem.getLong(32);
                boolean moved = lastDist > 0 && dist > 0 && dist != lastDist;
                if (diagZoomTraces++ < 5) {
                    // 无条件轨迹：内层块疑似从未执行，这行定真凶（moved/scale/pts 全量）。
                    // Unconditional trace: the inner block looks like it never ran - this
                    // line names the culprit (moved/scale/pts in full).
                    LOGGER.info("[GZ-2] zoom frame: dist={} lastDist={} moved={} scale={} pts=({}, {})",
                        dist, lastDist, moved,
                        lastDist > 0 ? (double) dist / lastDist : -1.0,
                        mem.getShort(24), mem.getShort(26));
                }
                if (moved) {
                    double scale = (double) dist / lastDist;
                    // 钳掉单帧野值（丢帧/误触），正常捏合比例贴近 1 / clamp single-frame outliers
                    if (scale > 0.2 && scale < 5.0) {
                        pendingScale *= scale;
                        rememberCentroid(mem.getShort(24), mem.getShort(26));
                        lastGestureZoomMs = System.currentTimeMillis();
                    }
                }
                lastDist = dist;
                // 产出缩放才消化；无变化/读不出=放行回滚轮保底（绝不死捏合）。
                // Consume only when zoom was produced; no motion/read falls through to the
                // wheel floor (never a dead pinch).
                return moved;
            } else if (id == GID_BEGIN || id == GID_END) {
                lastDist = -1;
                return false; // 无数据可产，放行无碍 / nothing to produce; harmless to fall through
            }
            return true;
        } finally {
            U32.INSTANCE.CloseGestureInfoHandle(Pointer.createConstant(giHandleSource == 1 ? lParam : wParam));
        }
    }

    /** 按给定句柄与 cbSize 试读一次手势；dwID 合法才认（cbSize 不匹配时 API 直接 FALSE 且
     *  不写缓冲）。 / One read attempt at the given handle and cbSize; accepted only when
     *  dwID is plausible (on a cbSize mismatch the API returns FALSE without writing). */
    private static int tryReadGesture(Memory mem, long handle, int size) {
        mem.clear(128);
        mem.setInt(0, size);
        U32.INSTANCE.GetGestureInfo(Pointer.createConstant(handle), mem);
        int id = mem.getInt(8);
        return (id >= GID_BEGIN && id <= GID_PRESSANDTAP) ? id : -1;
    }

    /** 回退通道：捏合合成滚轮的认定 = **消息级 MK_CONTROL**（官方 Legacy 形态）**或**
     *  手势流后 400ms 内的滚轮（实测合成滚轮不带 MK_CONTROL——按关联性认定；两指平移不产生
     *  WM_GESTURE（GID_PAN 未申领），真滚动/真滚轮不会被误吞）。按细刻度映射、整条消化。
     *  Fallback channel: pinch-synthesized wheel = message-level MK_CONTROL (the official
     *  Legacy shape) OR a wheel within 400ms of gesture traffic (measured: synthesized
     *  wheels carry no MK_CONTROL — associate instead; two-finger pan produces no
     *  WM_GESTURE since GID_PAN is unclaimed, so real scroll never gets eaten). Mapped on
     *  a fine notch and consumed. */
    private static boolean onSynthWheel(long wParam, long lParam) {
        int keys = (int) (wParam & 0xFFFF);
        boolean ctrlFlagged = (keys & MK_CONTROL) != 0;
        boolean recentGesture = System.currentTimeMillis() - lastGestureMsgMs < 400;
        if (!ctrlFlagged && !recentGesture) return false; // 真滚轮 / real wheel passes through
        if ((U32.INSTANCE.GetAsyncKeyState(VK_CONTROL) & 0x8000) != 0) return false; // 物理 Ctrl=用户在按 / physical Ctrl held
        // 手势通道活着时让位（双通道去重，GID_ZOOM 1:1 更准）/ defer to the live gesture channel
        if (System.currentTimeMillis() - lastGestureZoomMs < 300) return true;
        short delta = (short) ((wParam >>> 16) & 0xFFFF); // HIWORD=带符号滚轮增量 / signed wheel delta
        if (delta == 0) return true;
        pendingScale *= ScreenTransform.wheelNotchFactor(delta, PINCH_NOTCH);
        lastWheelZoomMs = System.currentTimeMillis();
        diagWheelPinches++;
        return true;
    }

    /** 质心换算（自闭环）：客户区 = pts − 窗口原点（GetWindowRect 手算，不再依赖会静默失败的
     *  ScreenToClient）；桌面系与「已是客户区系」两种候选按窗口范围择一；全程留据，失败回退
     *  鼠标锚点。 / Centroid conversion (self-contained): client = pts − window origin
     *  (GetWindowRect arithmetic, no silently-failing ScreenToClient); desktop-space and
     *  already-client candidates chosen by window bounds; fully logged, mouse-anchor
     *  fallback on failure. */
    private static void rememberCentroid(short ptsX, short ptsY) {
        if (diagCentroidDumps < 3) {
            diagCentroidDumps++;
            LOGGER.info("[GZ-2] centroid ENTER pts=({}, {}) (pre-conversion trace)", ptsX, ptsY);
        }
        try {
            var win = Minecraft.getInstance().getWindow();
            RECT r = new RECT();
            // 用装钩时拿到的真 HWND——win.getWindow() 是 GLFW 指针不是 HWND（拿它查窗口
            // 矩形必 false，v9 之前 ScreenToClient 静默失败同根）。
            // Use the real HWND from install — win.getWindow() is the GLFW pointer, not an
            // HWND (querying the window rect with it always fails; the same root cause as
            // the silently-failing ScreenToClient before v9).
            if (!U32.INSTANCE.GetWindowRect(Pointer.createConstant(hookedHwnd), r)) {
                pendingAnchorValid = false;
                LOGGER.info("[GZ-2] centroid: GetWindowRect returned false");
                return;
            }
            double cx = ptsX - r.left, cy = ptsY - r.top;
            int w = win.getScreenWidth(), h = win.getScreenHeight();
            if (cx < -w || cx > 2 * w || cy < -h || cy > 2 * h) {
                cx = ptsX; // 原始值更像客户区/屏幕系 / raw values look client-space already
                cy = ptsY;
            }
            pendingX = cx * win.getGuiScaledWidth() / w;
            pendingY = cy * win.getGuiScaledHeight() / h;
            pendingAnchorValid = true;
            if (diagCentroidDumps < 3) {
                diagCentroidDumps++;
                LOGGER.info("[GZ-2] centroid: pts=({},{}) rect=({},{})->({},{}) -> gui=({}, {})",
                    ptsX, ptsY, r.left, r.top, r.right, r.bottom, pendingX, pendingY);
            }
        } catch (Throwable t) {
            pendingAnchorValid = false;
            LOGGER.warn("[GZ-2] centroid conversion failed: {}", t.toString());
        }
    }

    /** 每帧消化攒下的捏合比例（无捏合时近乎零开销）。锚点优先质心换算，失败回退本帧鼠标坐标。
     *  Consume the accumulated pinch ratio once per frame (near-zero cost with no gesture).
     *  The anchor prefers the converted centroid, falling back to the frame's mouse coords. */
    static void drain(GraphEditor ed, double mx, double my) {
        double s = pendingScale;
        if (s != 1.0) {
            pendingScale = 1.0;
            ed.applyGestureZoom(s, pendingAnchorValid ? pendingX : mx, pendingAnchorValid ? pendingY : my);
        }
        long now = System.currentTimeMillis();
        if (now - lastDiagMs > 5000) {
            if (diagGestureMsgs + diagGiFails + diagGiOk + diagWheelPinches + diagCfgFails > 0) {
                LOGGER.info("[GZ-2] channels(5s): msgs={} ok={} fails(lastErr={} raw0=0x{} raw8=0x{}) wheelPinches={} cfgFails={}",
                    diagGestureMsgs, diagGiOk, diagGiFailErr,
                    Long.toHexString(diagRaw0), Long.toHexString(diagRaw8),
                    diagWheelPinches, diagCfgFails);
                diagGestureMsgs = diagGiOk = diagGiFails = diagWheelPinches = diagCfgFails = 0;
            }
            lastDiagMs = now;
        }
    }
}
