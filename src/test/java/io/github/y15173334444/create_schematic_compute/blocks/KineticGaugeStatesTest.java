package io.github.y15173334444.create_schematic_compute.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动力传感器朝向语义的回归测试 —— 把三处真相源钉在一起。
 * Regression test for the kinetic gauge's orientation semantics — pins the three
 * artefacts together.
 *
 * <p>同一张"哪个状态用哪个模型 + x/y 旋转"的表此前存在三份：生成脚本
 * {@code tools/gen_gauge_assets.py} 的 {@code BS} 字典、生成的
 * {@code blockstates/kinetic_gauge.json}、以及渲染器里的私有静态方法。表漂了就会出现
 * 「外壳转对了、蓝屏贴到侧面」这类最难查的错。现在 BER 侧的表已抽到
 * {@link KineticGaugeStates}，本测试断言它与 blockstate JSON 的 12 个状态逐项一致
 * （无缺、无余、模型选择与 x/y 都相同）。</p>
 * <p>The "which state uses which model + x/y rotation" table used to live in three places;
 * drift produces "the shell turned correctly but the blue screen is on the side wall". The
 * BER-side table now lives in {@link KineticGaugeStates}; this test asserts it matches the
 * blockstate JSON for all 12 states.</p>
 *
 * <p>第二条断言把屏幕锚点钉在**模型几何**上，识别方式是**契约命名**（元素名 {@code screen}）：
 * 屏幕面 = 该元素两个大面里离方块中心更远的那个（朝外）。随后要求锚点的法线与该面法线同向、
 * 右/上平行于该面的两条面内轴、**基为右手系**（{@code right × up = normal} —— 渲染器据此构造
 * det=+1 的矩阵，否则文字镜像）、排版窗口不超出该面、锚点落回该面内（允许 ≤0.05 沿法线外提防
 * z-fight）。模型重做时若动了屏幕平面，这里会立刻红。</p>
 * <p>The second assertion ties the anchors to the actual model geometry, identifying the screen
 * via the contract name ({@code screen}): the screen face is the farther-from-centre of the
 * element's two large faces. It then requires the normal to match, right/up to be parallel to
 * the face's in-plane axes, **a right-handed basis** ({@code right × up = normal} — the renderer
 * builds a det=+1 matrix from it, otherwise text mirrors), the layout window to fit inside the
 * face, and the anchor to fall back onto it.</p>
 */
class KineticGaugeStatesTest {

    private static final String NS = "create_schematic_compute";
    private static final String ASSET_ROOT = "/assets/" + NS + "/";

    private static final String SCREEN_NAME = "screen";
    private static final double EPS = 1e-3;        // 单位长度/正交性容差 / unit-length & orthogonality
    private static final double ALIGN_EPS = 1e-3;  // 平行/同向判定容差 / parallelism
    private static final double OFFSET_EPS = 0.05; // 锚点相对屏幕面的允许偏移 / allowed anchor offset

    @Test
    @DisplayName("blockstates/kinetic_gauge.json must match KineticGaugeStates on all 12 states")
    void blockstateTableMatchesStates() {
        JsonObject variants = readJson(ASSET_ROOT + "blockstates/kinetic_gauge.json")
            .getAsJsonObject("variants");

        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
            JsonObject rec = e.getValue().getAsJsonObject();
            actual.put(e.getKey(), rec.get("model").getAsString()
                + "|x=" + (rec.has("x") ? rec.get("x").getAsInt() : 0)
                + "|y=" + (rec.has("y") ? rec.get("y").getAsInt() : 0));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        for (Direction facing : Direction.values()) {
            for (boolean alongFirst : new boolean[]{true, false}) {
                String model = KineticGaugeStates.useShaftYVariant(facing, alongFirst)
                    ? "kinetic_gauge_shaft_y" : "kinetic_gauge";
                expected.put("facing=" + facing.getSerializedName() + ",axis_along_first=" + alongFirst,
                    NS + ":block/" + model
                        + "|x=" + KineticGaugeStates.xRotation(facing)
                        + "|y=" + KineticGaugeStates.yRotation(facing, alongFirst));
            }
        }

        assertEquals(expected, actual,
            "blockstate 表与 KineticGaugeStates 不一致（12 态必须无缺无余）——"
                + "改了 blockstates/kinetic_gauge.json 或哪一处表，另一处必须同步");
    }

    @Test
    @DisplayName("Base panel anchor must sit on the base model's screen face, right-handed")
    void basePanelSitsOnTheScreenFace() {
        assertPanelOnScreenFace("models/block/kinetic_gauge.json", KineticGaugeStates.PANEL_BASE);
    }

    @Test
    @DisplayName("Shaft-Y panel anchor must sit on the _shaft_y model's screen face, right-handed")
    void shaftYPanelSitsOnTheScreenFace() {
        assertPanelOnScreenFace("models/block/kinetic_gauge_shaft_y.json", KineticGaugeStates.PANEL_SHAFT_Y);
    }

    /**
     * 面板基矩阵必须把平移留在**最后一列**（JOML 的 16 参数 {@code set(...)} 是列主序）。
     * 写成"行主序"会整体转置：平移落进底行/透视行，GPU 做透视除法后顶点被 w 除成怪值 ——
     * 2026-09-13 实机表现为"面条一路拉到相机"。这条断言就是那次事故的回归测试。
     * The basis must keep the translation in the last column (JOML's 16-arg set is column-major).
     * Row-major transposes it, pushes the translation into the perspective row, and the GPU divides
     * the vertices by w ("noodles stretching to the camera", 2026-09-13).
     */
    @Test
    @DisplayName("Panel basis keeps the translation in the last column and the perspective row clean")
    void panelBasisKeepsTranslationInTheLastColumn() {
        for (KineticGaugeStates.Panel panel : new KineticGaugeStates.Panel[]{
            KineticGaugeStates.PANEL_BASE, KineticGaugeStates.PANEL_SHAFT_Y}) {
            Matrix4f m = KineticGaugeStates.panelBasis(panel);

            Vector4f origin = m.transform(new Vector4f(0f, 0f, 0f, 1f));
            assertEquals(panel.cx() - 8f, origin.x, 1e-4, "平移 x 必须落在最后一列");
            assertEquals(panel.cy() - 8f, origin.y, 1e-4, "平移 y 必须落在最后一列");
            assertEquals(panel.cz() - 8f, origin.z, 1e-4, "平移 z 必须落在最后一列");
            assertEquals(1f, origin.w, 1e-6, "透视行必须干净（转置时 w 会变成 x+y+z 的线性组合）");

            Vector4f ax = m.transform(new Vector4f(1f, 0f, 0f, 0f));
            assertEquals(panel.rx(), ax.x, 1e-4, "字形 +x 必须映射到 right");
            assertEquals(panel.ry(), ax.y, 1e-4);
            assertEquals(panel.rz(), ax.z, 1e-4);
            assertEquals(0f, ax.w, 1e-6, "方向向量不得被透视行影响");
            assertEquals(-panel.ux(), m.transform(new Vector4f(0f, 1f, 0f, 0f)).x, 1e-4, "字形 +y 必须映射到 −up");
            assertEquals(-panel.nx(), m.transform(new Vector4f(0f, 0f, 1f, 0f)).x, 1e-4, "字形 +z 必须映射到 −normal");
        }
    }

    // ── 锚点 ↔ 模型几何 / anchor vs model geometry ──

    private static void assertPanelOnScreenFace(String modelPath, KineticGaugeStates.Panel panel) {
        JsonObject model = readJson(ASSET_ROOT + modelPath);

        // 1) 屏幕件按契约命名识别 / locate the screen element by its contract name
        JsonObject screen = null;
        int named = 0;
        for (JsonElement e : model.getAsJsonArray("elements")) {
            JsonObject eo = e.getAsJsonObject();
            if (eo.has("name") && SCREEN_NAME.equalsIgnoreCase(eo.get("name").getAsString().trim())) {
                named++;
                screen = eo;
            }
        }
        assertEquals(1, named, modelPath + " 必须恰好有一个元素命名 `" + SCREEN_NAME + "`（契约）");
        assertNotNull(screen);
        String faceName = outwardLargeFace(screen);

        double[] from = vec(screen.getAsJsonArray("from"));
        double[] to = vec(screen.getAsJsonArray("to"));
        boolean rotated = screen.has("rotation");
        String axis = rotated ? screen.getAsJsonObject("rotation").get("axis").getAsString() : null;
        double angle = rotated ? screen.getAsJsonObject("rotation").get("angle").getAsDouble() : 0;
        double[] origin = rotated
            ? vec(screen.getAsJsonObject("rotation").getAsJsonArray("origin"))
            : new double[]{0, 0, 0};

        // 2) 面基向量（authored）→ 按元素旋转烘焙 / face basis, then bake the element rotation
        double[][] basis = faceBasis(faceName);
        double[] normal = rotateDir(basis[0], axis, angle);
        double[] axisA = rotateDir(basis[1], axis, angle);
        double[] axisB = rotateDir(basis[2], axis, angle);
        double halfA = Math.abs(to[dimA(faceName)] - from[dimA(faceName)]) / 2;
        double halfB = Math.abs(to[dimB(faceName)] - from[dimB(faceName)]) / 2;
        double halfN = Math.abs(to[dimN(faceName)] - from[dimN(faceName)]) / 2;

        double[] boxCenter = {(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};
        double[] faceCenterLocal = add(boxCenter, scale(basis[0], halfN));
        double[] faceCenter = rotatePoint(faceCenterLocal, axis, angle, origin);

        // 3) 锚点自洽 / anchor is self-consistent
        double[] pn = {panel.nx(), panel.ny(), panel.nz()};
        double[] pr = {panel.rx(), panel.ry(), panel.rz()};
        double[] pu = {panel.ux(), panel.uy(), panel.uz()};
        assertEquals(1.0, len(pn), EPS, "面板法线必须是单位向量");
        assertEquals(1.0, len(pr), EPS, "文字右方向必须是单位向量");
        assertEquals(1.0, len(pu), EPS, "文字上方向必须是单位向量");
        assertEquals(0.0, dot(pr, pu), EPS, "文字右/上必须正交");
        assertEquals(0.0, dot(pr, pn), EPS, "文字右方向必须垂直于法线");
        assertEquals(0.0, dot(pu, pn), EPS, "文字上方向必须垂直于法线");
        assertEquals(1.0, dot(cross(pr, pu), pn), EPS,
            modelPath + ": 面板基必须是右手系 right × up = normal —— 否则渲染器构造出 det=-1 的矩阵，文字会镜像");

        // 4) 锚点朝向必须与屏幕面一致 / the anchor must face the same way as the screen face
        assertTrue(dot(pn, normal) > 1 - ALIGN_EPS,
            modelPath + ": 面板法线 " + fmt(pn) + " 与屏幕面法线 " + fmt(normal) + " 不同向 —— "
                + "模型屏幕平面动了，或锚点该改");

        boolean rightOnA = Math.abs(Math.abs(dot(pr, axisA)) - 1) < ALIGN_EPS;
        boolean rightOnB = Math.abs(Math.abs(dot(pr, axisB)) - 1) < ALIGN_EPS;
        boolean upOnA = Math.abs(Math.abs(dot(pu, axisA)) - 1) < ALIGN_EPS;
        boolean upOnB = Math.abs(Math.abs(dot(pu, axisB)) - 1) < ALIGN_EPS;
        assertTrue(rightOnA || rightOnB,
            modelPath + ": 文字右方向 " + fmt(pr) + " 不平行于屏幕面的任何一条面内轴");
        assertTrue(upOnA || upOnB,
            modelPath + ": 文字上方向 " + fmt(pu) + " 不平行于屏幕面的任何一条面内轴");
        assertTrue((rightOnA && !upOnA) || (rightOnB && !upOnB),
            modelPath + ": 文字右/上必须分别对应屏幕面的两条不同面内轴");

        // 5) 排版窗口必须装得进屏幕面 / the layout window must fit inside the screen face
        double faceHalfRight = rightOnA ? halfA : halfB;
        double faceHalfUp = rightOnA ? halfB : halfA;
        assertTrue(panel.halfRight() <= faceHalfRight + EPS,
            modelPath + ": 排版半宽 " + panel.halfRight() + " 超出屏幕面半宽 " + faceHalfRight);
        assertTrue(panel.halfUp() <= faceHalfUp + EPS,
            modelPath + ": 排版半高 " + panel.halfUp() + " 超出屏幕面半高 " + faceHalfUp);

        // 6) 锚点必须落回屏幕面内（法向只允许 ≤0.05 的防 z-fight 外提）
        //    the anchor must fall back onto the face (≤0.05 along the normal, anti-z-fight lift)
        double[] d = {panel.cx() - faceCenter[0], panel.cy() - faceCenter[1], panel.cz() - faceCenter[2]};
        assertTrue(Math.abs(dot(d, axisA)) <= OFFSET_EPS,
            modelPath + ": 锚点在屏幕面内沿轴 A 偏离 " + dot(d, axisA));
        assertTrue(Math.abs(dot(d, axisB)) <= OFFSET_EPS,
            modelPath + ": 锚点在屏幕面内沿轴 B 偏离 " + dot(d, axisB));
        assertTrue(Math.abs(dot(d, normal)) <= OFFSET_EPS,
            modelPath + ": 锚点离屏幕面的法向距离 " + dot(d, normal) + " 过大（只允许 0.02 防 z-fight 外提）");
    }

    /**
     * 屏幕面 = 屏幕件两个大面里中心离方块中心 (8,8,8) 更远的那个（朝外）。与
     * {@code tools/gen_gauge_assets.py::pick_screen_face} 同一规则。
     * The screen face = the farther-from-centre of the element's two large faces (outward),
     * the same rule as the generator's pick_screen_face.
     */
    private static String outwardLargeFace(JsonObject el) {
        double[] from = vec(el.getAsJsonArray("from"));
        double[] to = vec(el.getAsJsonArray("to"));
        int thin = 0;
        for (int k = 1; k < 3; k++) {
            if (Math.abs(to[k] - from[k]) < Math.abs(to[thin] - from[thin])) thin = k;
        }
        String[] candidates = switch (thin) {
            case 0 -> new String[]{"east", "west"};
            case 1 -> new String[]{"up", "down"};
            default -> new String[]{"north", "south"};
        };
        boolean rotated = el.has("rotation");
        String axis = rotated ? el.getAsJsonObject("rotation").get("axis").getAsString() : null;
        double angle = rotated ? el.getAsJsonObject("rotation").get("angle").getAsDouble() : 0;
        double[] origin = rotated
            ? vec(el.getAsJsonObject("rotation").getAsJsonArray("origin"))
            : new double[]{0, 0, 0};
        double[] mid = {(from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2};

        String best = candidates[0];
        double bestDist = -1;
        for (String f : candidates) {
            double[] nLocal = faceBasis(f)[0];
            int dimN = dimN(f);
            double halfN = Math.abs(to[dimN] - from[dimN]) / 2;
            double[] c = rotatePoint(add(mid, scale(nLocal, halfN)), axis, angle, origin);
            double d = (c[0] - 8) * (c[0] - 8) + (c[1] - 8) * (c[1] - 8) + (c[2] - 8) * (c[2] - 8);
            if (d > bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    // ── 面几何小工具 / face-geometry helpers ──

    /** 面基向量：{外法线, 面内轴 A, 面内轴 B}（authored 坐标，符号无关）。 */
    private static double[][] faceBasis(String face) {
        return switch (face) {
            case "north" -> new double[][]{{0, 0, -1}, {1, 0, 0}, {0, 1, 0}};
            case "south" -> new double[][]{{0, 0, 1}, {1, 0, 0}, {0, 1, 0}};
            case "east" -> new double[][]{{1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
            case "west" -> new double[][]{{-1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
            case "up" -> new double[][]{{0, 1, 0}, {1, 0, 0}, {0, 0, 1}};
            case "down" -> new double[][]{{0, -1, 0}, {1, 0, 0}, {0, 0, 1}};
            default -> throw new IllegalArgumentException("未知面 / unknown face: " + face);
        };
    }

    /** 面内轴 A 对应的坐标维（0=x,1=y,2=z）。/ coordinate dimension of in-plane axis A. */
    private static int dimA(String face) {
        return switch (face) {
            case "east", "west" -> 2;
            default -> 0;
        };
    }

    /** 面内轴 B 对应的坐标维。/ coordinate dimension of in-plane axis B. */
    private static int dimB(String face) {
        return switch (face) {
            case "up", "down" -> 2;
            default -> 1;
        };
    }

    /** 法线对应的坐标维。/ coordinate dimension of the face normal. */
    private static int dimN(String face) {
        return switch (face) {
            case "east", "west" -> 0;
            case "up", "down" -> 1;
            default -> 2;
        };
    }

    /**
     * 方向向量按元素 rotation 旋转。原版 {@code FaceBakery.applyElementRotation} 用
     * {@code Quaternionf.rotationAxis(+angle, axis)}，即**右手系**（从正轴看逆时针）——
     * 与 blockstate 变体的左手系约定相反（原版历史怪癖）。与生成脚本的 rot_dir 同一套公式。
     * Vanilla element rotation is right-handed (positive angle about the axis), the opposite of
     * the blockstate variant convention. Same formulas as the generator's rot_dir.
     */
    private static double[] rotateDir(double[] v, String axis, double angleDeg) {
        if (axis == null || angleDeg == 0) return v;
        double a = Math.toRadians(angleDeg), c = Math.cos(a), s = Math.sin(a);
        return switch (axis) {
            case "x" -> new double[]{v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c};
            case "y" -> new double[]{v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c};
            case "z" -> new double[]{v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2]};
            default -> throw new IllegalArgumentException("未知旋转轴 / unknown axis: " + axis);
        };
    }

    /** 点绕 origin 沿轴旋转。/ rotate a point about {@code origin}. */
    private static double[] rotatePoint(double[] p, String axis, double angleDeg, double[] origin) {
        double[] local = {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]};
        double[] r = rotateDir(local, axis, angleDeg);
        return new double[]{r[0] + origin[0], r[1] + origin[1], r[2] + origin[2]};
    }

    private static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double len(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] scale(double[] v, double k) {
        return new double[]{v[0] * k, v[1] * k, v[2] * k};
    }

    private static String fmt(double[] v) {
        return String.format(java.util.Locale.ROOT, "(%.4f, %.4f, %.4f)", v[0], v[1], v[2]);
    }

    // ── 资源读取 / resource loading ──

    /**
     * 先走 classpath 资源，再回落到源码树（与 {@code DedicatedServerClientRefTest} 同一种
     * 「gradle test 工作目录 = 项目根」假设），两条路都失败才报错。
     */
    private static JsonObject readJson(String assetPath) {
        try (InputStream in = KineticGaugeStatesTest.class.getResourceAsStream(assetPath)) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(r).getAsJsonObject();
                }
            }
            Path fs = Path.of("src/main/resources" + assetPath);
            assertTrue(Files.exists(fs),
                "资源找不到（classpath 与源码树都没有）: " + assetPath);
            return JsonParser.parseString(Files.readString(fs, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("读取 " + assetPath + " 失败 / failed to read: " + e, e);
        }
    }
}
