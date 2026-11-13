package io.github.y15173334444.create_schematic_compute.graph;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tok 二进制编码器（Rust 后端桥 R1）：把 {@link FormulaParser#parseScript} 产出的
 * AST + RPN 编译为 native 引擎的字节码。**格式契约 = formula-core src/bridge.rs
 * 模块头注释**，两端共同权威；任何改动必须双端同步并重生成夹具
 * （native/formula-core/tests/fixtures/*.tokbin）。
 *
 * <p>要点：槽位表 = 变量名首现序互敦（**引脚序 ≠ 槽位序**，输入钉按名查槽）；
 * loop id = 前序遍历顺序编号；Fn/Cmp 序数与 Rust 枚举声明序显式对应（bridge.rs 映射表）；
 * 未知函数名不该出现在 FunctionToken（lexer 只认表内名），遇到即抛——编辑期暴露优于
 * 静默漂移。编码纯确定性：同脚本字节恒等（夹具逐字节对拍依据）。
 * 单次 encode 的全部可变状态收在 {@link Encoder} 实例里，无线程共享。
 *
 * <p>Tok binary encoder (Rust backend bridge R1): compiles the AST+RPN produced by
 * {@link FormulaParser#parseScript} into native bytecode. The format contract lives
 * in formula-core's bridge.rs module docs — changes must be synced and fixtures
 * regenerated. Encoding is fully deterministic (byte-identical for the same script).
 */
public final class FormulaTokEncoder {

    private FormulaTokEncoder() {}

    /** 编码结果：字节码 + 槽位名表（输入钉按名查槽位）。 / encode result: bytecode + slot table. */
    public record Encoded(byte[] bytes, List<String> slotNames) {}

    /** 未知函数名等契约外形态——编辑期抛出，绝不静默漂移。 / contract violation. */
    public static final class EncodeException extends RuntimeException {
        public EncodeException(String msg) {
            super(msg);
        }
    }

    // ── Tok tag（与 bridge.rs 对齐） ──
    private static final int TAG_CONST = 0;
    private static final int TAG_VAR = 1;
    private static final int TAG_OP = 2;
    private static final int TAG_CMP = 3;
    private static final int TAG_NOT = 4;
    private static final int TAG_FN = 5;
    private static final int TAG_SWZ = 6;

    // ── Stmt tag ──
    private static final int STMT_ASSIGN = 0;
    private static final int STMT_EXPR = 1;
    private static final int STMT_REPEAT = 2;
    private static final int STMT_WHILE = 3;
    private static final int STMT_IF = 4;
    private static final int STMT_BREAK = 5;
    private static final int STMT_CONTINUE = 6;

    // ── Fn 序数（= Rust Fn 枚举声明序，bridge.rs ordinals 同表） ──
    private static final Map<String, Integer> FN_ORDINALS = buildFnOrdinals();

    private static Map<String, Integer> buildFnOrdinals() {
        Map<String, Integer> m = new LinkedHashMap<>();
        String[] names = {"vec3", "length", "normalize", "dot", "cross", "dist", "yaw", "pitch",
            "sin", "cos", "tan", "asin", "acos", "atan2", "sinh", "cosh", "sqrt",
            "ln", "log", "exp", "sec", "csc", "cot"};
        for (int i = 0; i < names.length; i++) {
            m.put(names[i], i);
        }
        return m;
    }

    // ── Cmp 序数（= Rust Cmp 枚举声明序） ──
    private static int cmpOrdinal(String op) {
        return switch (op) {
            case "==" -> 0;
            case "!=" -> 1;
            case "<=" -> 2;
            case ">=" -> 3;
            case "&&" -> 4;
            case "||" -> 5;
            default -> throw new EncodeException("unknown two-char operator: " + op);
        };
    }

    /** 编码解析结果。slotNames 含全部脚本变量（输入钉与内部变量同表）。 */
    public static Encoded encode(FormulaParser.ScriptParseResult parsed) {
        if (parsed.ast == null) {
            throw new EncodeException("script did not parse to AST mode (depth-degraded or empty)");
        }
        Encoder enc = new Encoder();
        Out out = new Out();
        out.writeBytes("CSCF".getBytes(StandardCharsets.US_ASCII));
        out.writeU32(1); // version
        // 槽位表：全 AST 首现序互敦（先于语句编码，表必须先完整）
        for (FormulaAst.Stmt s : parsed.ast) {
            enc.internStmt(s);
        }
        out.writeU32(enc.interner.names.size());
        for (String name : enc.interner.names) {
            byte[] b = name.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0xFFFF) {
                throw new EncodeException("slot name too long: " + name);
            }
            out.writeU16(b.length);
            out.writeBytes(b);
        }
        // loop id：前序遍历顺序分配（id 表先于语句编码完整）
        for (FormulaAst.Stmt s : parsed.ast) {
            enc.assignIds(s);
        }
        out.writeU32(enc.idCounts.size());
        for (Map.Entry<Integer, Long> e : enc.idCounts.entrySet()) {
            out.writeU16(e.getKey());
            out.writeU64(e.getValue());
        }
        out.writeU32(parsed.ast.size());
        for (FormulaAst.Stmt s : parsed.ast) {
            enc.encodeStmt(out, s);
        }
        return new Encoded(out.bytes(), List.copyOf(enc.interner.names));
    }

    /** 槽位互敦（首现序，LinkedHashMap 保序）。 / slot interning in first-appearance order. */
    private static final class SlotInterner {
        final Map<String, Integer> map = new LinkedHashMap<>();
        final List<String> names = new ArrayList<>();

        int slot(String name) {
            Integer i = map.get(name);
            if (i == null) {
                i = names.size();
                map.put(name, i);
                names.add(name);
            }
            return i;
        }
    }

    /** 单次 encode 的全部可变状态（非线程共享）。 / per-encode state (not shared). */
    private static final class Encoder {
        final SlotInterner interner = new SlotInterner();
        final Map<Integer, Long> idCounts = new LinkedHashMap<>();
        final Map<FormulaAst.Stmt, Integer> idByLoop = new IdentityHashMap<>();
        int nextId = 0;

        int nextLoopId(long repeatCount) {
            int id = nextId++;
            idCounts.put(id, repeatCount);
            return id;
        }

        int idOf(FormulaAst.Stmt loop) {
            Integer id = idByLoop.get(loop);
            if (id == null) {
                throw new EncodeException("loop id not assigned — encoder walk bug");
            }
            return id;
        }

        void internStmt(FormulaAst.Stmt s) {
            switch (s) {
                // Assign：目标名 + RHS 全部变量（漏 RHS 会让仅右值出现的变量——如输入钉——
                // 不进槽位表，桥接求值错位）
                case FormulaAst.AssignStmt a -> {
                    interner.slot(a.var());
                    internRpn(a.rpn());
                }
                case FormulaAst.ExprStmt e -> internRpn(e.rpn());
                case FormulaAst.RepeatStmt r -> r.body().forEach(this::internStmt);
                case FormulaAst.WhileStmt w -> {
                    internRpn(w.condRpn());
                    w.body().forEach(this::internStmt);
                }
                case FormulaAst.IfStmt i -> {
                    internRpn(i.condRpn());
                    i.body().forEach(this::internStmt);
                    i.elseBody().forEach(this::internStmt);
                }
                case FormulaAst.BreakStmt b -> {
                }
                case FormulaAst.ContinueStmt c -> {
                }
            }
        }

        void internRpn(List<Object> rpn) {
            for (Object tok : rpn) {
                if (tok instanceof String name) {
                    interner.slot(name);
                }
            }
        }

        void assignIds(FormulaAst.Stmt s) {
            switch (s) {
                case FormulaAst.RepeatStmt r -> {
                    int id = nextLoopId(r.count());
                    idByLoop.put(r, id);
                    r.body().forEach(this::assignIds);
                }
                case FormulaAst.WhileStmt w -> {
                    int id = nextLoopId(0); // while 无计数（progress 用 -1 口径）
                    idByLoop.put(w, id);
                    w.body().forEach(this::assignIds);
                }
                case FormulaAst.IfStmt i -> {
                    i.body().forEach(this::assignIds);
                    i.elseBody().forEach(this::assignIds);
                }
                default -> {
                }
            }
        }

        void encodeStmt(Out out, FormulaAst.Stmt s) {
            switch (s) {
                case FormulaAst.AssignStmt a -> {
                    out.writeU8(STMT_ASSIGN);
                    out.writeU16(interner.slot(a.var()));
                    encodeRpn(out, a.rpn());
                }
                case FormulaAst.ExprStmt e -> {
                    out.writeU8(STMT_EXPR);
                    encodeRpn(out, e.rpn());
                }
                case FormulaAst.RepeatStmt r -> {
                    out.writeU8(STMT_REPEAT);
                    out.writeU16(idOf(r));
                    out.writeU64(r.count());
                    encodeStmts(out, r.body());
                }
                case FormulaAst.WhileStmt w -> {
                    out.writeU8(STMT_WHILE);
                    out.writeU16(idOf(w));
                    encodeRpn(out, w.condRpn());
                    encodeStmts(out, w.body());
                }
                case FormulaAst.IfStmt i -> {
                    out.writeU8(STMT_IF);
                    encodeRpn(out, i.condRpn());
                    encodeStmts(out, i.body());
                    encodeStmts(out, i.elseBody());
                }
                case FormulaAst.BreakStmt b -> out.writeU8(STMT_BREAK);
                case FormulaAst.ContinueStmt c -> out.writeU8(STMT_CONTINUE);
            }
        }

        void encodeStmts(Out out, List<FormulaAst.Stmt> stmts) {
            out.writeU32(stmts.size());
            for (FormulaAst.Stmt s : stmts) {
                encodeStmt(out, s);
            }
        }

        /** RPN 编码：每 token 定宽 16B（tag + 7 pad + 8 payload，小端）。 */
        void encodeRpn(Out out, List<Object> rpn) {
            out.writeU32(rpn.size());
            for (Object tok : rpn) {
                out.writeU8(tagOf(tok));
                for (int i = 0; i < 7; i++) {
                    out.writeU8(0); // pad
                }
                writePayload(out, tok);
            }
        }

        private void writePayload(Out out, Object tok) {
            // payload 定宽 8B：不足部分零填充（契约 = bridge.rs；变宽会让 Rust 定宽
            // 解码错位——首版 bug 即此）。
            switch (tok) {
                case Double d -> out.writeF64(d);
                case String name -> {
                    out.writeU16(interner.slot(name));
                    for (int i = 0; i < 6; i++) {
                        out.writeU8(0);
                    }
                }
                case Character c -> {
                    out.writeU8((byte) c.charValue());
                    for (int i = 0; i < 7; i++) {
                        out.writeU8(0);
                    }
                }
                case FormulaParser.OpToken ot -> {
                    if (!ot.op().equals("!")) {
                        out.writeU8(cmpOrdinal(ot.op()));
                    }
                    for (int i = 0; i < 7; i++) {
                        out.writeU8(0);
                    }
                }
                case FormulaParser.FunctionToken ft -> {
                    Integer ord = FN_ORDINALS.get(ft.name());
                    if (ord == null) {
                        throw new EncodeException(
                            "unknown function name in FunctionToken: " + ft.name());
                    }
                    out.writeU8(ord);
                    for (int i = 0; i < 7; i++) {
                        out.writeU8(0);
                    }
                }
                case FormulaParser.SwizzleToken st -> {
                    String comps = st.comps();
                    if (comps.length() == 1) {
                        out.writeU8(switch (comps) {
                            case "x" -> 0;
                            case "y" -> 1;
                            default -> 2;
                        });
                    } else {
                        out.writeU8(9); // 多分量 → 求值期 lenient 恒零
                    }
                    for (int i = 0; i < 7; i++) {
                        out.writeU8(0);
                    }
                }
                default -> throw new EncodeException("unreachable token payload");
            }
        }
    }

    private static int tagOf(Object tok) {
        if (tok instanceof Double) {
            return TAG_CONST;
        }
        if (tok instanceof String) {
            return TAG_VAR;
        }
        if (tok instanceof Character) {
            return TAG_OP;
        }
        if (tok instanceof FormulaParser.OpToken ot) {
            return ot.op().equals("!") ? TAG_NOT : TAG_CMP;
        }
        if (tok instanceof FormulaParser.FunctionToken) {
            return TAG_FN;
        }
        if (tok instanceof FormulaParser.SwizzleToken) {
            return TAG_SWZ;
        }
        throw new EncodeException("unknown RPN token type: " + tok.getClass());
    }

    /** 小端字节汇。 / little-endian byte sink. */
    private static final class Out {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        void writeU8(int v) {
            buf.write(v & 0xFF);
        }

        void writeU16(int v) {
            buf.write(v & 0xFF);
            buf.write((v >>> 8) & 0xFF);
        }

        void writeU32(int v) {
            buf.write(v & 0xFF);
            buf.write((v >>> 8) & 0xFF);
            buf.write((v >>> 16) & 0xFF);
            buf.write((v >>> 24) & 0xFF);
        }

        void writeU64(long v) {
            for (int i = 0; i < 8; i++) {
                buf.write((int) ((v >>> (8 * i)) & 0xFF));
            }
        }

        void writeF64(double d) {
            writeU64(Double.doubleToLongBits(d));
        }

        void writeBytes(byte[] b) {
            buf.writeBytes(b);
        }

        byte[] bytes() {
            return buf.toByteArray();
        }
    }
}
