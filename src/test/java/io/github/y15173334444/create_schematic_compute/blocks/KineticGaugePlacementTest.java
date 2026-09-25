package io.github.y15173334444.create_schematic_compute.blocks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动力传感器**放置朝向**的回归测试（2026-09-13 实测报告：贴地放置只有两个朝向）。
 * Regression test for the kinetic gauge's **placement direction** (in-game report:
 * floor placement only reached two directions).
 *
 * <p>机制（由 Create 6.0.10 字节码确认）：官方 {@code GaugeBlock} 的
 * {@code getFacingForPlacement} = {@code ctx.getClickedFace()}。贴地/贴顶时点击面是竖直的
 * （{@code up}/{@code down}），而 {@code facing=up|down} 只剩 {@code axis_along_first} 一个
 * 自由度 —— **2 个状态换不出 4 个偏航角**，于是屏幕只能朝西或朝北；朝东/朝南只有贴墙
 * （点竖直面）才拿得到。本测试把"放置 → 状态 → 屏幕世界朝向"整条链算出来，钉住修复后的
 * 语义：贴地/贴顶都必须得到 4 个朝向，且每个都水平正对玩家。</p>
 * <p>Mechanism (confirmed from Create 6.0.10 bytecode): the official gauge sets
 * {@code facing} to the clicked face, so a floor/ceiling placement yields a vertical
 * {@code facing}, where only {@code axis_along_first} remains — two states cannot encode four
 * yaws. This test walks the whole chain (placement → blockstate → world-space screen normal)
 * and pins the fixed semantics: floor and ceiling placement must each reach 4 directions,
 * every one of them facing the player.</p>
 *
 * <p>链条中的每一环都取自真实产物：状态表读 {@code blockstates/kinetic_gauge.json}，
 * 屏幕法线读 {@link KineticGaugeStates#PANEL_BASE}/{@link KineticGaugeStates#PANEL_SHAFT_Y}
 * （它们又被 {@code KineticGaugeStatesTest} 钉在模型几何上）。</p>
 * <p>Every link comes from a real artefact: the state table from the generated blockstate
 * JSON, the screen normals from the two Panel anchors.</p>
 */
class KineticGaugePlacementTest {

    private static final String NS = "create_schematic_compute";
    private static final String ASSET_ROOT = "/assets/" + NS + "/";

    /** 玩家的四个水平朝向（= {@code ctx.getHorizontalDirection()}）。 */
    private static final Direction[] HORIZONTAL_LOOKS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    @Test
    @DisplayName("Floor/ceiling: level look = horizontal lectern, steep look = vertical shaft")
    void floorCeilingSplitOnLookPitch() {
        JsonObject variants = readJson(ASSET_ROOT + "blockstates/kinetic_gauge.json")
            .getAsJsonObject("variants");

        // 俯仰判定用 nearest-looking（Sable 会变换到子世界局部系；getXRot 世界角会错）
        // Steep = nearest-looking axis is vertical (Sable-aware), not raw world pitch.
        assertTrue(Direction.UP.getAxis().isVertical());
        assertTrue(Direction.DOWN.getAxis().isVertical());
        assertFalse(Direction.NORTH.getAxis().isVertical());

        for (Direction clicked : new Direction[]{Direction.UP, Direction.DOWN}) {
            Set<Direction> reached = new LinkedHashSet<>();
            for (Direction look : HORIZONTAL_LOOKS) {
                Direction facing = KineticGaugeStates.facingForPlacement(clicked, look, look);
                assertTrue(facing.getAxis().isHorizontal(), "平视放置 facing 须水平: " + facing);
                boolean alongFirst = facing.getAxis() == Direction.Axis.Z;
                Direction.Axis shaft = shaftOf(facing, alongFirst);
                assertTrue(shaft != Direction.Axis.Y,
                    "平视放置必须是横置（轴非 Y），实测轴 " + shaft);
                double[] n = worldScreenNormal(variants, facing, alongFirst);
                Direction want = look.getOpposite();
                assertTrue(dotHorizontal(n, want) > 0.5,
                    "平视贴 " + clicked + " 视线 " + look + "：屏幕须朝 " + want);
                reached.add(want);
            }
            assertEquals(4, reached.size());
        }

        // 俯视/仰视（nearestLooking 轴竖直）→ 竖置（轴 Y，facing 仍水平正对玩家）
        Direction facing = KineticGaugeStates.facingForPlacement(Direction.UP, Direction.DOWN, Direction.NORTH);
        assertEquals(Direction.SOUTH, facing, "俯视地面放置应朝南（玩家水平朝北）");
        assertFalse(KineticGaugeStates.alongFirstForVerticalShaft(facing));
        assertEquals(Direction.Axis.Y, shaftOf(facing, KineticGaugeStates.alongFirstForVerticalShaft(facing)),
            "俯视放置必须是竖直轴");
        facing = KineticGaugeStates.facingForPlacement(Direction.DOWN, Direction.UP, Direction.EAST);
        assertEquals(Direction.WEST, facing, "仰视天花板放置应朝西（玩家水平朝东）");
        assertTrue(KineticGaugeStates.alongFirstForVerticalShaft(facing));
        assertEquals(Direction.Axis.Y, shaftOf(facing, KineticGaugeStates.alongFirstForVerticalShaft(facing)),
            "仰视放置必须是竖直轴");
    }

    private static Direction.Axis shaftOf(Direction facing, boolean alongFirst) {
        return switch (facing.getAxis()) {
            case X -> alongFirst ? Direction.Axis.Y : Direction.Axis.Z;
            case Y -> alongFirst ? Direction.Axis.X : Direction.Axis.Z;
            case Z -> alongFirst ? Direction.Axis.X : Direction.Axis.Y;
        };
    }

    private static double dotHorizontal(double[] n, Direction dir) {
        return n[0] * dir.getStepX() + n[2] * dir.getStepZ();
    }

    @Test
    @DisplayName("displayPanel keeps text upright in world space on every state (facing=DOWN included)")
    void displayPanelKeepsWorldTextUpright() {
        // facing=DOWN 走 blockstate x:180，若不做字形 180° 翻正，倒置挂墙/朝下时读数会上下颠倒。
        // facing=DOWN applies x:180; without the in-plane glyph spin the inverted mount shows
        // the readout upside-down (in-game report).
        for (Direction facing : Direction.values()) {
            for (boolean alongFirst : new boolean[]{true, false}) {
                KineticGaugeStates.Panel p = KineticGaugeStates.displayPanel(facing, alongFirst);
                double[] up = rotateVanillaY(
                    rotateVanillaX(new double[]{p.ux(), p.uy(), p.uz()},
                        KineticGaugeStates.xRotation(facing, alongFirst)),
                    KineticGaugeStates.yRotation(facing, alongFirst));
                assertTrue(up[1] > 0.5,
                    "facing=" + facing + ",A=" + alongFirst + " 的世界空间文字上方向 Y=" + up[1]
                        + " 必须朝上（倒置态文字才不会颠倒）");
                // 右手系在翻正后仍成立：right × up = normal
                double[] r = {p.rx(), p.ry(), p.rz()};
                double[] u = {p.ux(), p.uy(), p.uz()};
                double[] n = {p.nx(), p.ny(), p.nz()};
                double[] cross = {
                    r[1] * u[2] - r[2] * u[1],
                    r[2] * u[0] - r[0] * u[2],
                    r[0] * u[1] - r[1] * u[0]};
                assertEquals(1.0, cross[0] * n[0] + cross[1] * n[1] + cross[2] * n[2], 1e-3,
                    "facing=" + facing + ",A=" + alongFirst + " 翻正后面板基仍须右手系");
            }
        }
    }

    @Test
    @DisplayName("Horizontal yaw (Y-face wrench) keeps the lectern up-tilted on all 4 directions")
    void horizontalYawKeepsLecternUpTilted() {
        // 点上/下偏航 W→N→E→S：四步都必须是上仰讲台（法线 Y>0.5），否则会有两步屏幕朝下反了。
        // The four horizontal facings must all be up-tilted lectern poses (normal Y > 0.5).
        for (Direction facing : new Direction[]{Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH}) {
            // 该偏航环上的状态：W/N→沿 Z 或 X 交替（Create 默认 Y 点击即此环）
            boolean alongFirst = facing.getAxis() == Direction.Axis.Z;
            double[] n = normalD(facing, alongFirst);
            assertTrue(n[1] > 0.5,
                "facing=" + facing + ",A=" + alongFirst + " 的屏幕法线 Y=" + n[1]
                    + " 必须朝上（偏航四步不许出现朝下反面）");
        }
    }

    @Test
    @DisplayName("Shaft-end wrench roll: 90° steps about the shaft, getClockWise sense, closed 4-cycle")
    void shaftRollCyclesAreNinetyDegreeSteps() {
        JsonObject variants = readJson(ASSET_ROOT + "blockstates/kinetic_gauge.json")
            .getAsJsonObject("variants");
        // 2026-09-26 实机回归：Y 环曾写成 NW→SW→NE→SE，第 2/4 步为 180° 对角跳 ——
        // 「互不重合」钉子查不出顺序错，必须把每步的视觉法线钉在绕轴 90° + getClockWise 同向上。
        // 2026-09-26 in-game regression: the Y ring was NW→SW→NE→SE, hopping 180° on steps
        // 2 and 4. Distinctness pins cannot catch a mis-ORDER, so every step's visual normal
        // is pinned to a 90° getClockWise rotation about the shaft.
        for (Direction.Axis shaft : Direction.Axis.values()) {
            List<KineticGaugeStates.WrenchTarget> members = new ArrayList<>();
            for (Direction facing : Direction.values()) {
                for (boolean alongFirst : new boolean[]{true, false}) {
                    if (shaftOf(facing, alongFirst) != shaft) continue;
                    KineticGaugeStates.WrenchTarget next = KineticGaugeStates.nextInShaftRoll(
                        facing, alongFirst, shaft);
                    assertNotNull(next, shaft + " 环缺 " + facing + "+" + alongFirst
                        + "（三环必须按轴划分 12 态）");
                    members.add(new KineticGaugeStates.WrenchTarget(facing, alongFirst));
                    assertEquals(shaft, shaftOf(next.facing(), next.alongFirst()),
                        "滚转不许改轴：" + facing + "+" + alongFirst);
                    double[] before = worldScreenNormal(variants, facing, alongFirst);
                    double[] after = worldScreenNormal(variants, next.facing(), next.alongFirst());
                    assertArrayEquals(rotateAboutAxisCw(before, shaft), after, 1e-3,
                        shaft + " 环 " + facing + "+" + alongFirst + " → " + next.facing()
                            + "+" + next.alongFirst() + "：法线必须绕轴恰转 90°（防 180° 对角跳）");
                }
            }
            // 环闭合：从任一成员走 4 步回到起点，且途经 4 个互异状态。
            KineticGaugeStates.WrenchTarget cur = members.get(0);
            Set<KineticGaugeStates.WrenchTarget> seen = new LinkedHashSet<>();
            for (int i = 0; i < 4; i++) {
                cur = KineticGaugeStates.nextInShaftRoll(cur.facing(), cur.alongFirst(), shaft);
                seen.add(cur);
            }
            assertEquals(4, seen.size(), shaft + " 环 4 步必须历经 4 个互异状态：" + seen);
            assertEquals(members.get(0), cur, shaft + " 环走 4 步必须回到起点");
        }
    }

    @Test
    @DisplayName("Y-face wrench yaw: 90° steps around Y staying on the current tilt, both rings")
    void yawRingsPreserveTiltAndFollowClockwise() {
        JsonObject variants = readJson(ASSET_ROOT + "blockstates/kinetic_gauge.json")
            .getAsJsonObject("variants");
        // 点上/下：沿当前倾侧（朝上 x=0 环 / 朝下 x=180 环）偏航 90°、随 getClockWise(Y)；
        // 倒置屏不许翻回朝上（2026-09 实机）。平板态的 Y 面是轴端面，走滚转，不在环上。
        for (Direction facing : Direction.values()) {
            for (boolean alongFirst : new boolean[]{true, false}) {
                if (KineticGaugeStates.useShaftYVariant(facing, alongFirst)) continue;
                KineticGaugeStates.WrenchTarget next = KineticGaugeStates.nextInYaw(facing, alongFirst);
                double[] before = worldScreenNormal(variants, facing, alongFirst);
                double[] after = worldScreenNormal(variants, next.facing(), next.alongFirst());
                assertArrayEquals(rotateAboutAxisCw(before, Direction.Axis.Y), after, 1e-3,
                    "点上/下 " + facing + "+" + alongFirst + " → " + next.facing() + "+"
                        + next.alongFirst() + "：法线必须绕 Y 恰转 90°");
                assertEquals(before[1] > 0, after[1] > 0,
                    "偏航必须保持倾侧（朝上环恒朝上、朝下环恒朝下）：" + facing + "+" + alongFirst);
            }
        }
        // 两环各自 4 步闭合。
        for (boolean downTilt : new boolean[]{false, true}) {
            KineticGaugeStates.WrenchTarget cur = downTilt
                ? new KineticGaugeStates.WrenchTarget(Direction.DOWN, false)
                : new KineticGaugeStates.WrenchTarget(Direction.WEST, false);
            KineticGaugeStates.WrenchTarget start = cur;
            for (int i = 0; i < 4; i++)
                cur = KineticGaugeStates.nextInYaw(cur.facing(), cur.alongFirst());
            assertEquals(start, cur, (downTilt ? "朝下" : "朝上") + "环走 4 步必须回到起点");
        }
    }

    @Test
    @DisplayName("Wall placement keeps display = clicked face")
    void wallPlacementKeepsClickedFace() {
        for (Direction clicked : HORIZONTAL_LOOKS) {
            assertEquals(clicked, KineticGaugeStates.facingForPlacement(clicked, clicked.getOpposite()),
                "贴墙放置：显示面应保持 = 点击面（官方表语义）");
        }
    }

    // ── 状态 → 世界空间屏幕法线 / state -> world-space screen normal ──

    private static double[] worldScreenNormal(JsonObject variants, Direction facing, boolean alongFirst) {
        String key = "facing=" + facing.getSerializedName() + ",axis_along_first=" + alongFirst;
        JsonElement recEl = variants.get(key);
        assertNotNull(recEl, "blockstate 表缺少状态 " + key);
        JsonObject rec = recEl.getAsJsonObject();
        String model = rec.get("model").getAsString();
        int x = rec.has("x") ? rec.get("x").getAsInt() : 0;
        int y = rec.has("y") ? rec.get("y").getAsInt() : 0;

        KineticGaugeStates.Panel panel = model.endsWith("_shaft_y")
            ? KineticGaugeStates.PANEL_SHAFT_Y : KineticGaugeStates.PANEL_BASE;
        double[] normal = {panel.nx(), panel.ny(), panel.nz()};
        // blockstate 旋转：先 x 后 y（与 KineticGaugeRenderer 的 q = Ry·Rx 一致）。
        return rotateVanillaY(rotateVanillaX(normal, x), y);
    }

    /** 原版 blockstate x 旋转（本表只出现 x=180）。 */
    private static double[] rotateVanillaX(double[] v, int deg) {
        if (deg == 0) return v;
        if (deg == 180) return new double[]{v[0], -v[1], -v[2]};
        throw new IllegalArgumentException("状态表出现未支持的 x 旋转: " + deg);
    }

    /**
     * 原版 blockstate y 旋转（俯视顺时针）。映射由可验证的原版事实推得：熔炉模型正面朝北、
     * 变体 {@code facing=east} 用 {@code y:90} —— 即 y=90 把北面转到东面（N→E→S→W→N）。
     * Vanilla {@code y} rotation (clockwise seen from above), derived from a checkable vanilla
     * fact: the furnace model faces north and {@code facing=east} uses {@code y:90}.
     */
    private static double[] rotateVanillaY(double[] v, int deg) {
        return switch (((deg % 360) + 360) % 360) {
            case 0 -> v;
            case 90 -> new double[]{-v[2], v[1], v[0]};
            case 180 -> new double[]{-v[0], v[1], -v[2]};
            case 270 -> new double[]{v[2], v[1], -v[0]};
            default -> throw new IllegalArgumentException("状态表出现未支持的 y 旋转: " + deg);
        };
    }

    // ── 扳手环表几何 / wrench ring geometry ──

    /** {@link KineticGaugeStates#panelNormalWorld} 的 double 视图。 */
    private static double[] normalD(Direction facing, boolean alongFirst) {
        float[] n = KineticGaugeStates.panelNormalWorld(facing, alongFirst);
        return new double[]{n[0], n[1], n[2]};
    }

    /**
     * 绕坐标轴把向量转 90°，方向取自**原版 {@link Direction#getClockWise(axis) 的方向循环}**
     * （与生产环表的约定同源、独立推导）：轴上基向量不动，另两个基向量各沿方向循环走一步。
     * Rotate a vector 90° about a coordinate axis, derived independently from vanilla's
     * {@code getClockWise(axis)} direction cycles: the axial basis vector stays, the other
     * two each advance one step.
     */
    private static double[] rotateAboutAxisCw(double[] v, Direction.Axis axis) {
        Direction d1 = switch (axis) {
            case X -> Direction.UP; case Y -> Direction.EAST; case Z -> Direction.UP;
        };
        Direction d2 = switch (axis) {
            case X -> Direction.SOUTH; case Y -> Direction.SOUTH; case Z -> Direction.EAST;
        };
        double[] r1 = unit(d1.getClockWise(axis));
        double[] r2 = unit(d2.getClockWise(axis));
        int ia = iAxis(axis), i1 = iAxis(d1.getAxis()), i2 = iAxis(d2.getAxis());
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = v[i1] * r1[i] + v[i2] * r2[i];
        out[ia] = v[ia]; // 轴向分量绕自身旋转不动
        return out;
    }

    private static int iAxis(Direction.Axis a) { return a == Direction.Axis.X ? 0 : a == Direction.Axis.Y ? 1 : 2; }

    private static double[] unit(Direction d) {
        return new double[]{d.getStepX(), d.getStepY(), d.getStepZ()};
    }

    // ── 资源读取 / resource loading ──

    private static JsonObject readJson(String assetPath) {
        try (InputStream in = KineticGaugePlacementTest.class.getResourceAsStream(assetPath)) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(r).getAsJsonObject();
                }
            }
            Path fs = Path.of("src/main/resources" + assetPath);
            assertTrue(Files.exists(fs), "资源找不到（classpath 与源码树都没有）: " + assetPath);
            return JsonParser.parseString(Files.readString(fs, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("读取 " + assetPath + " 失败 / failed to read: " + e, e);
        }
    }
}
