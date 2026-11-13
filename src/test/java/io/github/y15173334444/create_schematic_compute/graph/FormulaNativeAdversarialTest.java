package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JNI 面防御对抗测试（R2.5）：**package-private 不是安全边界**——同包类定义/反射
 * setAccessible 都能直调这些 native。对抗口径：任何敌意输入只允许得到负错误码或
 * 无操作，**绝不允许 JVM 崩溃/挂死**。会话句柄是表键非裸指针（double-free/UAF/
 * 伪造句柄从构造上消灭）；字节码/挂起态尺寸帽在拷贝前检查；解码深度帽镜像
 * MAX_PARSE_DEPTH（crafted 深嵌套字节码打不出 native 栈溢出）。
 *
 * <p>Defense adversarial tests (R2.5): package-private is NOT a security boundary —
 * same-package class definitions and reflective setAccessible can reach these
 * natives. Contract: hostile input yields a negative code or a no-op, never a JVM
 * crash. Handles are table keys (UAF/double-free structurally gone); size caps are
 * checked before copies; the decode depth cap mirrors MAX_PARSE_DEPTH.
 */
class FormulaNativeAdversarialTest {

    private static void assumeNative() {
        assumeTrue(FormulaNative.AVAILABLE,
            "formula_native cdylib unavailable — skipping adversarial native tests");
    }

    /** 敌意句柄矩阵（null 哨兵/负数/巨数/位翻转）。 / hostile handle matrix. */
    private static final long[] HOSTILE_SESSIONS = {
        0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40, (1L << 40) + 12345, 42L,
    };

    private static final String SCALAR_LOOP =
        "i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}";

    @Test
    @DisplayName("敌意句柄矩阵:全操作负错误码,JVM 存活")
    void hostileSessionHandlesNeverCrash() {
        assumeNative();
        byte[] program = FormulaTokEncoder.encode(FormulaParser.parseScript(SCALAR_LOOP)).bytes();
        double[] outputs = new double[2];
        for (long h : HOSTILE_SESSIONS) {
            assertEquals(-1, FormulaNative.exec(h, 0, new double[0], null, 0L, outputs, null),
                "exec hostile session " + h);
            assertEquals(-1, FormulaNative.programUpload(h, program), "upload hostile " + h);
            assertEquals(-1, FormulaNative.programRelease(h, 0), "release hostile " + h);
        }
        // destroy 敌意句柄 = 无操作（幂等口径覆盖 0/负数/巨数）。 / destroy = no-op.
        assertDoesNotThrow(() -> {
            for (long h : HOSTILE_SESSIONS) {
                FormulaNative.sessionDestroy(h);
            }
        });
    }

    @Test
    @DisplayName("double-destroy 与销毁后使用:句柄表语义(幂等/查无此人),无 UAF")
    void doubleDestroyAndUseAfterDestroy() {
        assumeNative();
        long session = FormulaNative.sessionCreate();
        FormulaNative.sessionDestroy(session);
        // 二次 destroy = 无操作。 / second destroy is a no-op (table key gone).
        assertDoesNotThrow(() -> FormulaNative.sessionDestroy(session));
        // 销毁后所有操作 = -1(句柄只是表键,UAF 从构造上不存在)。
        // / Every op on a destroyed handle = -1 (handles are table keys, no UAF).
        byte[] program = FormulaTokEncoder.encode(FormulaParser.parseScript(SCALAR_LOOP)).bytes();
        assertEquals(-1, FormulaNative.programUpload(session, program), "upload after destroy");
        assertEquals(-1, FormulaNative.exec(session, 0, new double[0], null, 0L,
            new double[2], null), "exec after destroy");
        // 新会话不复用已销毁 id。 / fresh sessions never reuse destroyed ids.
        long fresh = FormulaNative.sessionCreate();
        try {
            assertTrue(fresh > session, "session ids must be monotonic (no reuse)");
        } finally {
            FormulaNative.sessionDestroy(fresh);
        }
    }

    @Test
    @DisplayName("字节码轰炸:垃圾/截断/位翻转/超帽 = -5,JVM 存活")
    void hostileProgramBytesNeverCrash() {
        assumeNative();
        long session = FormulaNative.sessionCreate();
        try {
            byte[] valid = FormulaTokEncoder.encode(FormulaParser.parseScript(SCALAR_LOOP)).bytes();
            // 垃圾字节 / garbage
            assertEquals(-5, FormulaNative.programUpload(session, new byte[0]), "empty program");
            assertEquals(-5, FormulaNative.programUpload(session, new byte[]{1, 2, 3}), "tiny garbage");
            // 位翻转 / bit flips
            Random rng = new Random(0xC5CC5C5C5C5C5C5CL);
            for (int round = 0; round < 500; round++) {
                byte[] mutated = valid.clone();
                for (int i = 0; i < 1 + rng.nextInt(16); i++) {
                    mutated[rng.nextInt(mutated.length)] = (byte) rng.nextInt();
                }
                int rc = FormulaNative.programUpload(session, mutated);
                assertTrue(rc == -5 || rc >= 0,
                    "bit-flip round " + round + " must be -5 or a clean upload, got " + rc);
            }
            // 超帽(16MB+1):拷贝之前拒绝(native 侧零大分配)。
            // / Over the cap — rejected before any native copy.
            byte[] huge = new byte[FormulaNative.PROGRAM_MAX_BYTES + 1];
            assertEquals(-5, FormulaNative.programUpload(session, huge), "oversized program");
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }

    @Test
    @DisplayName("carrier 轰炸:随机字节/坏头 = -4;合法形态往返可用")
    void hostileCarriersNeverCrash() {
        assumeNative();
        long session = FormulaNative.sessionCreate();
        try {
            var parsed = FormulaParser.parseScript(SCALAR_LOOP);
            var enc = FormulaTokEncoder.encode(parsed);
            int pid = FormulaNative.programUpload(session, enc.bytes());
            assertTrue(pid >= 0, "upload");
            // 随机 carrier 轰炸:exec 只能 -4(解码失败)或 1(碰巧过了头校验的合法化形态)。
            // / Random carrier bombs: -4 (decode failure) or 1 (a re-legitimized form).
            Random rng = new Random(0xB0BB0B0B0B0B0B0BL);
            byte[] bomb = new byte[256];
            for (int round = 0; round < 500; round++) {
                rng.nextBytes(bomb);
                bomb[0] = 'C';
                bomb[1] = 'S';
                bomb[2] = 'C';
                bomb[3] = 'C'; // 头合法化,强制走深度解析路径
                int rc = FormulaNative.exec(session, pid, new double[0], bomb, 0L,
                    new double[enc.slotNames().size()], null);
                assertTrue(rc == -4 || rc == 0 || rc == 1,
                    "carrier bomb round " + round + " gave " + rc);
            }
            // 纯垃圾头 = -4。 / garbage header = -4.
            byte[] garbage = new byte[64];
            new Random(7).nextBytes(garbage);
            assertEquals(-4, FormulaNative.exec(session, pid, new double[0], garbage, 0L,
                new double[enc.slotNames().size()], null), "garbage carrier");
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }

    @Test
    @DisplayName("深嵌套 crafted 字节码 = -5 受控拒绝(不打穿 native 栈)")
    void deepNestedBytecodeRejected() {
        assumeNative();
        long session = FormulaNative.sessionCreate();
        try {
            // 手工构造 400 层嵌套 Repeat(合法编码形态,深度越界)——与 Rust 侧
            // adversarial_deep_nesting_is_rejected_not_crashed 同构,经真 JNI 面。
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.writeBytes("CSCF".getBytes());
            out.write(1); out.write(0); out.write(0); out.write(0);          // version=1
            out.write(0); out.write(0); out.write(0); out.write(0);          // 0 slots
            out.write(0); out.write(0); out.write(0); out.write(0);          // 0 repeats
            out.write(1); out.write(0); out.write(0); out.write(0);          // 1 top stmt
            for (int i = 0; i < 400; i++) {
                out.write(2);                                                // Repeat
                out.write(i & 0xFF); out.write((i >> 8) & 0xFF);             // id u16
                long count = 1;
                for (int b = 0; b < 8; b++) out.write((int) ((count >>> (8 * b)) & 0xFF));
                out.write(1); out.write(0); out.write(0); out.write(0);      // body 1 stmt
            }
            out.write(0);                                                    // Assign
            out.write(0); out.write(0);                                      // slot 0
            out.write(1); out.write(0); out.write(0); out.write(0);          // rpn 1 token
            out.write(0);                                                    // Const
            for (int i = 0; i < 7; i++) out.write(0);                        // pad
            long bits = Double.doubleToLongBits(1.0);
            for (int b = 0; b < 8; b++) out.write((int) ((bits >>> (8 * b)) & 0xFF));
            assertEquals(-5, FormulaNative.programUpload(session, out.toByteArray()),
                "deep nesting must be a controlled -5, never a native crash");
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }
}
