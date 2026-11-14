package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rust 后端会话柄 + 程序缓存（R3 接线，§4.7-2）。会话 = 世界级生命周期：
 * **懒建**（首次使用 {@link #handle()} 幂等创建——不挂 ServerStarting 监听器，
 * 省一个挂点且 headless 测试可用）+ **ServerStoppingEvent 销毁**（与
 * {@code FormulaCompute.clearAll()} 同一监听块）。{@link FormulaNative#AVAILABLE}
 * 为假时全链短路（handle 恒 0、programFor 恒 null），求值走 Java 解释器兜底。
 *
 * <p>程序缓存按<b>脚本文本</b>去重（{@code sourceFormula} → programId + 引脚→槽位
 * 映射）：槽位序 = 变量首现序 ≠ 引脚序，upload 时由 {@code Encoded.slotNames} 按
 * {@code inputVars} 名查一次并缓存 int[]。节点不持 programId（每 eval 一次
 * HashMap 命中，key 是既有 String，零分配）。不做引用计数：会话级泄漏 = 历史上
 * 出现过的不同脚本数（KB 级/个，编辑会话内数百 = MB 级上限），随 {@link #clear()}
 * 全量回收；批形态收口时再评估（§4.7-2 诚实校准）。</p>
 *
 * <p>线程口径：exec/upload 恒服务端线程；{@link #handle()} 的懒建走
 * {@code synchronized}（Rust 句柄表本身锁保护，误用并发也只是串行化，非内存不安全），
 * {@link #programFor} 同理——正常面下无竞争，防御面下不错乱。</p>
 *
 * <p>The Rust-backend session handle + program cache (R3 wiring, §4.7-2). The
 * session is world-scoped: <b>lazily created</b> (idempotent on first use — no
 * ServerStarting listener, headless tests work) and <b>destroyed on
 * ServerStoppingEvent</b> (the same listener block as FormulaCompute.clearAll()).
 * With {@link FormulaNative#AVAILABLE} false everything short-circuits (handle 0,
 * programFor null) and the Java interpreter stays the fallback. Programs are cached
 * by script text (sourceFormula → programId + pin→slot map); no refcounting — the
 * session-level leak equals the number of distinct scripts ever seen (KBs each),
 * reclaimed wholesale by {@link #clear()}.</p>
 */
public final class FormulaNativeSession {

    private FormulaNativeSession() {}

    /** 会话柄（0 = 未建 / native 不可用）。 / session handle (0 = not created / native unavailable). */
    private static volatile long handle = 0;

    /** 程序缓存：脚本文本 → programId + 引脚→槽位映射 + 槽数。 / program cache keyed by script text. */
    private static final Map<String, CachedProgram> PROGRAMS = new ConcurrentHashMap<>();

    /** 一份已上传程序。 / one uploaded program. */
    record CachedProgram(int programId, int[] pinSlots, int slotCount) {}

    /**
     * 会话柄（懒建；AVAILABLE=false 恒 0）。 / the session handle (lazy; 0 when native is unavailable).
     */
    static long handle() {
        if (!FormulaNative.AVAILABLE) {
            return 0;
        }
        long h = handle;
        if (h != 0) {
            return h;
        }
        synchronized (FormulaNativeSession.class) {
            if (handle == 0) {
                handle = FormulaNative.sessionCreate();
            }
            return handle;
        }
    }

    /**
     * 查/传程序：缓存命中直接返回；未命中编码 + 上传（槽位映射按 inputVars 名查）。
     * 编码失败（契约外：lexer 只产表内函数名）返回 null——调用方按「输出保持」处置，
     * 绝不静默漂移。
     * Look up or upload a program: cache hit returns directly; a miss encodes + uploads
     * (the pin→slot map resolved by inputVars names). Encode failure (contract-
     * impossible) returns null — the caller holds outputs, never drifting silently.
     */
    static CachedProgram programFor(FormulaParser.ScriptParseResult script) {
        if (!FormulaNative.AVAILABLE) {
            return null;
        }
        CachedProgram cached = PROGRAMS.get(script.sourceFormula);
        if (cached != null) {
            return cached;
        }
        FormulaTokEncoder.Encoded enc;
        try {
            enc = FormulaTokEncoder.encode(script);
        } catch (FormulaTokEncoder.EncodeException e) {
            FormulaEval.LOGGER.warn("FORMULA native encode failed (contract-impossible; holding outputs): {}",
                e.getMessage());
            return null;
        }
        long h = handle();
        int id = FormulaNative.programUpload(h, enc.bytes());
        if (id < 0) {
            FormulaEval.LOGGER.warn("FORMULA native upload failed (rc={}): holding outputs", id);
            return null;
        }
        List<String> slots = enc.slotNames();
        int[] pinSlots = new int[script.inputVars.size()];
        for (int vi = 0; vi < pinSlots.length; vi++) {
            pinSlots[vi] = slots.indexOf(script.inputVars.get(vi));
        }
        CachedProgram prog = new CachedProgram(id, pinSlots, slots.size());
        PROGRAMS.put(script.sourceFormula, prog);
        return prog;
    }

    /**
     * ServerStoppingEvent 清理（跨世界防污染面，与 FormulaCompute.clearAll 同块）。
     * 销毁 native 会话 + 清空程序缓存；之后首个使用方懒建新会话。
     * ServerStoppingEvent cleanup (the cross-world pollution guard, same block as
     * FormulaCompute.clearAll): destroys the native session and clears the program
     * cache; the next user lazily creates a fresh session.
     */
    public static void clear() {
        long h = handle;
        handle = 0;
        PROGRAMS.clear();
        if (h != 0 && FormulaNative.AVAILABLE) {
            FormulaNative.sessionDestroy(h);
        }
    }

    /** 仅测试用：全量复位（与 HostEvalPass.resetForTest 同口径）。 / test-only reset. */
    static void resetForTest() {
        clear();
    }
}
