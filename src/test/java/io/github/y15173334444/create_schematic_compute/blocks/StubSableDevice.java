package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 测试桩：模拟「被 Sable 用独立类加载器拷贝进结构的图方块实体」。
 * 只实现名字解析路径会触达的方法（getNodeGraph/getCustomName 默认实现），
 * 其余一律空实现 —— 不得引入 MC 注册表依赖，保证无游戏环境可实例化。
 * <p>
 * Test stub: simulates a graph block entity copied into a Sable structure by a
 * SEPARATE classloader. Only the methods touched by the name-resolution path are
 * real (getNodeGraph / the getCustomName default); everything else is a no-op —
 * no MC registry dependencies, so it instantiates without a game environment.
 */
public class StubSableDevice implements GraphBlockEntity {

    /** 桩图的自定义名 —— 断言目标。 / Custom name of the stub graph — the assertion target. */
    public static final String NAME = "结构上的齿轮箱";

    private final NodeGraph graph = new NodeGraph();

    public StubSableDevice() {
        graph.customName = NAME;
    }

    @Override public NodeGraph getNodeGraph() { return graph; }

    @Override public net.minecraft.world.level.block.entity.BlockEntity asBlockEntity() { return null; }

    @Nullable @Override public Level getLevel() { return null; }

    @Override public net.minecraft.core.BlockPos getBlockPos() { return net.minecraft.core.BlockPos.ZERO; }

    @Override public void setChanged() { }

    @Override public void sendBlockUpdated() { }

    /** 满足接口映射的占位 —— 名字解析路径不会调用。 / Placeholder shims — never on the name-resolution path. */
    @Override public void syncFlipflopStates(Map<Integer, Boolean> states) { }

    /** 桩不参与服务端准入门（P2-7）：全类放行 + 空普查。 / The stub never rides the server
     *  admission gate (P2-7): admit-all allowlist + empty census. */
    @Override public io.github.y15173334444.create_schematic_compute.graph.NodeAllowance nodeAllowance() {
        return io.github.y15173334444.create_schematic_compute.graph.NodeAllowance.of(
            "csc:stub", java.util.EnumSet.allOf(io.github.y15173334444.create_schematic_compute.graph.NodeCategory.class));
    }

    @Override public java.util.Set<io.github.y15173334444.create_schematic_compute.graph.NodeType> loadTimeNodeTypes() {
        return java.util.Set.of();
    }

    @Override public java.util.Set<String> loadTimeCustomIds() {
        return java.util.Set.of();
    }
}
