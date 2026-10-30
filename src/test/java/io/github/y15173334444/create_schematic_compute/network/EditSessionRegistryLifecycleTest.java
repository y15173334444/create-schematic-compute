package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry;

/**
 * EditSessionRegistry 生命周期回归（P2-10）：
 * <ul>
 *   <li>(a) 方块拆除回收——{@code dropBlock} 清掉该坐标的 editors/editVersions/opLogs，
 *       此前方块被拆/替换后留下"幽灵编辑者"（op 广播发给没开界面的人）且映射跨会话慢泄漏；</li>
 *   <li>(b) 会话自然结束回收——{@code leave}/{@code leaveAll} 使 editors 变空时顺带回收
 *       该图的 editVersions/opLogs，此前只在小停机清空（opLog 现零读取方，留它纯泄漏）。</li>
 * </ul>
 * registry 三张表全是纯 Java（GlobalPos 键），headless 全测。
 *
 * <p>EditSessionRegistry lifecycle regression (P2-10): (a) block removal reclaims the
 * whole session via {@code dropBlock} — a broken/replaced block used to leave ghost
 * editors (op broadcasts to players with no screen open) and leaked the maps across
 * sessions; (b) a naturally-ended session reclaims its editVersions/opLogs when the
 * editor set empties — they used to survive until server stop (opLog has zero readers
 * today, so keeping it is a pure leak). All three tables are plain Java keyed by
 * GlobalPos — fully testable headlessly.</p>
 */
class EditSessionRegistryLifecycleTest {

    private static GlobalPos key(String dim, int x, int y, int z) {
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("csc_test", dim)), new BlockPos(x, y, z));
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() { EditSessionRegistry.clearAll(); }

    private static GraphOp someOp(UUID actor) {
        return GraphOp.addNodeRequest(BlockPos.ZERO, -1, 3, NodeType.CONST, 0f, 0f, actor);
    }

    @Test
    @DisplayName("join 播种版本号并登记编辑者")
    void joinSeedsVersionAndTracksEditors() {
        var k = key("overworld", 1, 2, 3);
        var u = UUID.randomUUID();
        EditSessionRegistry.join(k, u);
        assertTrue(EditSessionRegistry.getEditors(k).contains(u));
        assertEquals(1L, EditSessionRegistry.peekVersion(k), "版本种子应为 1（首个 op 拿 ≥2）");
    }

    @Test
    @DisplayName("最后一位编辑者离开：回收版本表与 opLog（P2-10b）")
    void leaveToEmptyReclaimsVersionAndOpLog() {
        var k = key("overworld", 1, 2, 3);
        var u = UUID.randomUUID();
        EditSessionRegistry.join(k, u);
        EditSessionRegistry.appendToLog(k, someOp(u));
        assertTrue(EditSessionRegistry.isSessionTracked(k));

        EditSessionRegistry.leave(k, u);

        assertFalse(EditSessionRegistry.isSessionTracked(k),
            "最后一位编辑者离开后，editors/editVersions/opLogs 三张表必须全部回收");
        assertNull(EditSessionRegistry.peekVersion(k));
        assertNull(EditSessionRegistry.peekOpLog(k));
    }

    @Test
    @DisplayName("仍有编辑者在线时 leave 不得回收会话状态")
    void leaveWithRemainingEditorsKeepsState() {
        var k = key("overworld", 1, 2, 3);
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        EditSessionRegistry.join(k, a);
        EditSessionRegistry.join(k, b);
        EditSessionRegistry.appendToLog(k, someOp(a));

        EditSessionRegistry.leave(k, a);

        assertTrue(EditSessionRegistry.isSessionTracked(k), "会话仍有编辑者，状态必须保留");
        assertEquals(1, EditSessionRegistry.getEditors(k).size());
        assertNotNull(EditSessionRegistry.peekOpLog(k), "opLog 必须保留给仍在会话的编辑者");
    }

    @Test
    @DisplayName("leaveAll 只回收变空的会话，多编辑者会话不受影响")
    void leaveAllReclaimsOnlyEmptiedSessions() {
        var k1 = key("overworld", 1, 1, 1);
        var k2 = key("overworld", 2, 2, 2);
        var a = UUID.randomUUID(); // 只在 k1 / k1 only
        var b = UUID.randomUUID(); // 在 k1 与 k2 / k1 and k2
        EditSessionRegistry.join(k1, a);
        EditSessionRegistry.join(k1, b);
        EditSessionRegistry.join(k2, b);
        EditSessionRegistry.appendToLog(k1, someOp(a));

        EditSessionRegistry.leaveAll(b); // 模拟玩家断线 / player disconnect

        assertTrue(EditSessionRegistry.isSessionTracked(k1), "k1 仍有 a 在线，不得回收");
        assertFalse(EditSessionRegistry.isSessionTracked(k2), "k2 因断线变空，必须回收");
        assertNull(EditSessionRegistry.peekOpLog(k2));
    }

    @Test
    @DisplayName("dropBlock 回收整个会话，即使编辑者还在线（P2-10a）")
    void dropBlockReclaimsWholeSessionEvenWithEditorsOnline() {
        var k = key("overworld", 3, 4, 5);
        var a = UUID.randomUUID();
        EditSessionRegistry.join(k, a);
        EditSessionRegistry.appendToLog(k, someOp(a));
        assertTrue(EditSessionRegistry.isSessionTracked(k));

        EditSessionRegistry.dropBlock(k);

        assertFalse(EditSessionRegistry.isSessionTracked(k),
            "方块被拆/替换后不得残留幽灵编辑者（op 广播发给没开界面的人）");
        assertNull(EditSessionRegistry.peekVersion(k));
        assertNull(EditSessionRegistry.peekOpLog(k));
    }

    @Test
    @DisplayName("同坐标新方块 join 重建全新会话（版本种子回到 1）")
    void samePositionRebuildsFreshSessionAfterDrop() {
        var k = key("overworld", 3, 4, 5);
        var a = UUID.randomUUID();
        EditSessionRegistry.join(k, a);
        EditSessionRegistry.dropBlock(k);

        EditSessionRegistry.join(k, a);

        assertEquals(1L, EditSessionRegistry.peekVersion(k), "新方块 = 新图，版本应从头播种");
        assertTrue(EditSessionRegistry.getEditors(k).contains(a));
    }

    @Test
    @DisplayName("同坐标不同维度是互不干扰的两个会话（GlobalPos 键语义）")
    void samePositionDifferentDimensionStaysDistinct() {
        var overworld = key("overworld", 7, 7, 7);
        var nether = key("nether", 7, 7, 7);
        EditSessionRegistry.join(overworld, UUID.randomUUID());

        assertFalse(EditSessionRegistry.hasEditors(nether),
            "维度限定的键：主世界有编辑者不得让下界同坐标出现编辑者");
        assertFalse(EditSessionRegistry.isSessionTracked(nether));
        EditSessionRegistry.dropBlock(nether); // 不得误清主世界会话 / must not touch the overworld session
        assertFalse(EditSessionRegistry.isSessionTracked(nether));
    }
}
