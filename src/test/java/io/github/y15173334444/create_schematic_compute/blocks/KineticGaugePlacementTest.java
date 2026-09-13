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
import java.util.LinkedHashSet;
import java.util.Set;

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
    @DisplayName("Floor/ceiling placement must reach 4 screen directions, each facing the player")
    void floorAndCeilingPlacementReachFourPlayerFacingDirections() {
        JsonObject variants = readJson(ASSET_ROOT + "blockstates/kinetic_gauge.json")
            .getAsJsonObject("variants");

        for (Direction clicked : new Direction[]{Direction.UP, Direction.DOWN}) {
            Set<Direction> reached = new LinkedHashSet<>();
            for (Direction look : HORIZONTAL_LOOKS) {
                Direction facing = KineticGaugeStates.facingForPlacement(clicked, look);
                boolean alongFirst = axisAlongFirst(facing);
                Direction display = dominantHorizontal(worldScreenNormal(variants, facing, alongFirst));
                assertNotNull(display, "屏幕法线没有水平分量：clicked=" + clicked
                    + " look=" + look + " facing=" + facing + " alongFirst=" + alongFirst);
                assertEquals(look.getOpposite(), display,
                    "贴 " + clicked + " 放置、视线朝 " + look + " 时，屏幕必须正对玩家（"
                        + look.getOpposite() + "），实测朝 " + display
                        + "（facing=" + facing + ", axis_along_first=" + alongFirst + "）");
                reached.add(display);
            }
            assertEquals(4, reached.size(),
                "贴 " + clicked + " 放置只得到 " + reached.size() + " 个屏幕朝向：" + reached
                    + " —— facing 若落在竖直轴上，2 个状态换不出 4 个偏航角");
        }
    }

    @Test
    @DisplayName("Placement facing follows the look: level look -> horizontal, steep look -> up/down")
    void placementFacingFollowsTheLook() {
        // 平视（nearestLooking 为水平）→ 显示面水平（四向可选）
        for (Direction clicked : Direction.values()) {
            for (Direction look : HORIZONTAL_LOOKS) {
                Direction facing = KineticGaugeStates.facingForPlacement(clicked, look);
                assertTrue(facing.getAxis().isHorizontal(),
                    "平视放置得到水平朝向（clicked=" + clicked + " look=" + look + " → " + facing + "）");
            }
        }
        // 俯视地面 / 仰视天花板 → 显示面朝上 / 朝下：**竖置状态可直接放出**
        // （2026-09-13 实测回归：改前用水平视线取反，竖置只能靠扳手转出来）
        assertEquals(Direction.UP, KineticGaugeStates.facingForPlacement(Direction.UP, Direction.DOWN),
            "俯视地面放置必须得到 facing=up（屏幕朝上）");
        assertEquals(Direction.DOWN, KineticGaugeStates.facingForPlacement(Direction.DOWN, Direction.UP),
            "仰视天花板放置必须得到 facing=down（屏幕朝下）");
    }

    @Test
    @DisplayName("Wrench: shaft end face + display side cycle 90° (Create semantics); the other two rigid-rotate")
    void wrenchFacesClassifyCorrectly() {
        // 基础讲台屏（facing=up,A=false → 轴 Z、屏幕正前方 = up 与 west）
        // Base lectern (facing=up,A=false → shaft Z; display side = up + west)
        assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.NORTH),
            "轴端面必须绕轴 90° 循环 —— Create 点端面就是 90° 绕轴转（作者 2026-09-13 指正）");
        assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.SOUTH));
        assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.UP));
        assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.WEST));
        assertEquals(KineticGaugeStates.WrenchAction.RIGID_ROTATE,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.EAST));
        assertEquals(KineticGaugeStates.WrenchAction.RIGID_ROTATE,
            KineticGaugeStates.wrenchAction(Direction.Axis.Z, Direction.UP, false, Direction.DOWN));

        // 手绘竖版（facing=west,A=true → 轴 Y、屏幕正前方 = west 与 north）
        // Hand-drawn vertical variant (facing=west,A=true → shaft Y; display side = west + north)
        for (Direction f : new Direction[]{Direction.UP, Direction.DOWN}) {
            assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
                KineticGaugeStates.wrenchAction(Direction.Axis.Y, Direction.WEST, true, f),
                "竖置状态的轴端面（上/下面）同样绕轴 90° 循环：" + f);
        }
        assertEquals(KineticGaugeStates.WrenchAction.CYCLE_DISPLAY,
            KineticGaugeStates.wrenchAction(Direction.Axis.Y, Direction.WEST, true, Direction.NORTH));
        assertEquals(KineticGaugeStates.WrenchAction.RIGID_ROTATE,
            KineticGaugeStates.wrenchAction(Direction.Axis.Y, Direction.WEST, true, Direction.SOUTH));
        assertEquals(KineticGaugeStates.WrenchAction.RIGID_ROTATE,
            KineticGaugeStates.wrenchAction(Direction.Axis.Y, Direction.WEST, true, Direction.EAST));
    }

    @Test
    @DisplayName("Display-side faces follow the panel normal (a 45° panel has TWO display faces)")
    void displayFacesFollowThePanelNormal() {
        // 基础讲台屏（法线上仰 45° 朝西）：up 与 west 都正对屏幕
        assertTrue(KineticGaugeStates.isDisplayFace(Direction.UP, false, Direction.UP));
        assertTrue(KineticGaugeStates.isDisplayFace(Direction.UP, false, Direction.WEST));
        assertFalse(KineticGaugeStates.isDisplayFace(Direction.UP, false, Direction.EAST));
        assertFalse(KineticGaugeStates.isDisplayFace(Direction.UP, false, Direction.DOWN));
        // 手绘竖版（法线朝西北，state facing=west,A=true）：north 与 west 都正对屏幕
        // —— 2026-09-13 实测回归：只认 clickedFace==facing 时，另一个面会触发整表刚性旋转
        //（轴跟着转），用户感知为"竖置状态用扳手转屏幕只能 180°"
        assertTrue(KineticGaugeStates.isDisplayFace(Direction.WEST, true, Direction.WEST));
        assertTrue(KineticGaugeStates.isDisplayFace(Direction.WEST, true, Direction.NORTH));
        assertFalse(KineticGaugeStates.isDisplayFace(Direction.WEST, true, Direction.SOUTH));
        assertFalse(KineticGaugeStates.isDisplayFace(Direction.WEST, true, Direction.EAST));
    }

    @Test
    @DisplayName("Wall placement keeps display = clicked face")
    void wallPlacementKeepsClickedFace() {
        for (Direction clicked : HORIZONTAL_LOOKS) {
            assertEquals(clicked, KineticGaugeStates.facingForPlacement(clicked, clicked.getOpposite()),
                "贴墙放置：显示面应保持 = 点击面（官方表语义）");
        }
    }

    // ── 放置判定之外的参考规则 / reference rule outside our placement hook ──

    /**
     * 自由放置时 {@code axis_along_first} 的取值：Create
     * {@code DirectionalAxisKineticBlock.getStateForPlacement} 的水平分支默认
     * {@code alongFirst = (facing 轴 == Z)}（字节码 20-47；有相邻传动轴时会翻成
     * {@code axis == X}，本测试按"空地上放置"取默认值）。
     * 竖直分支不在此处建模 —— 修复后 {@code facing} 恒为水平，
     * 由 {@link #placementFacingIsAlwaysHorizontal()} 守住该不变量。
     * Default {@code axis_along_first} for free placement, straight from the base class.
     */
    private static boolean axisAlongFirst(Direction facing) {
        return facing.getAxis() == Direction.Axis.Z;
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

    /** 屏幕法线的水平主方向（+X=东, +Z=南）；法线带仰角，但水平分量只有一个轴非零。 */
    private static Direction dominantHorizontal(double[] n) {
        if (Math.abs(n[0]) < 1e-6 && Math.abs(n[2]) < 1e-6) return null;
        if (Math.abs(n[0]) >= Math.abs(n[2])) return n[0] > 0 ? Direction.EAST : Direction.WEST;
        return n[2] > 0 ? Direction.SOUTH : Direction.NORTH;
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
