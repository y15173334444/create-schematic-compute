package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

public class SpeedProxyBlockEntity extends SyncedGraphBlockEntity {
    // EN: Reflection cache: SpeedControllerBlockEntity's targetSpeed field
    // 反射缓存：SpeedControllerBlockEntity 的 targetSpeed 字段
    private static Class<?> speedControllerClass = null;
    private static java.lang.reflect.Field targetSpeedField = null;

    // EN: Cached speed controller position (avoids scanning every tick)
    // 缓存的转速控制器位置（避免每 tick 扫描）
    private BlockPos cachedControllerPos = null;
    // EN: Scan cooldown when controller not found (in ticks)
    // 未找到控制器时的扫描冷却（tick）
    private int scanCooldown = 0;

    public SpeedProxyBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SPEED_PROXY_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——SpeedProxy 无类型特定字段。
    // 行为微调：基类在合并末了会 sendBlockUpdated，而原先此处不发送——与其他六个
    // BE 对齐。/ accept() moved up to SyncedGraphBlockEntity (phase 1) — SpeedProxy has
    // no type-specific fields. Small behaviour change: the base class ends the merge
    // with sendBlockUpdated, which this one used to skip — now it matches the other six.

    public void tick() {
        if (level == null || level.isClientSide()) return;
        ensureBusRegistered();
        boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
        var state = getBlockState();
        if (!state.hasProperty(SpeedProxyBlock.LIT)) return;
        if (state.getValue(SpeedProxyBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, state.setValue(SpeedProxyBlock.LIT, shouldBeLit), 3);
        if (!isRunning()) { onStopRunning(); return; }
        if (graphChanged()) recompileEvaluatorLight();
        var results = evaluator().evaluate(List.of(), runtimeState().pidState, 0.05f);
        // Broadcast eval snapshot so DEBUG_SIGNAL_GEN / DEBUG_PROBE charts render on client.
        // 广播评测快照，使客户端 DEBUG_SIGNAL_GEN / DEBUG_PROBE 图表正常渲染。
        broadcastEvalSnapshot();
        for (var n : graph().nodes) {
            if (n.type == NodeType.SPEED_CTRL) {
                float speed = evaluator().getNodeOutput(n.id, 0);
                applySpeed((int) speed);
                break;
            }
        }
    }

    /** Initialize reflection (lazy-loaded on first use).
     *  初始化反射（第一次使用时延迟加载） */
    private static boolean initReflection() {
        if (speedControllerClass != null) return true;
        try {
            speedControllerClass = Class.forName("com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity");
            targetSpeedField = speedControllerClass.getField("targetSpeed");
            return true;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to init SpeedController reflection", e);
            return false;
        }
    }

    /** Check if the block entity is a speed controller and set its RPM.
     *  判断方块实体是否为转速控制器并设置转速 */
    private static boolean trySetSpeed(BlockEntity be, int rpm) {
        if (!initReflection() || be == null) return false;
        if (!speedControllerClass.isInstance(be)) return false;
        try {
            Object scrollValue = targetSpeedField.get(be);
            scrollValue.getClass().getMethod("setValue", int.class).invoke(scrollValue, rpm);
            be.setChanged();
            return true;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to set SpeedController speed", e);
            return false;
        }
    }

    private void applySpeed(int targetRpm) {
        if (level == null || !initReflection()) return;
        targetRpm = Math.max(-256, Math.min(256, targetRpm));

        // EN: Try cached controller position first (not affected by cooldown)
        // 先尝试缓存的控制器位置（不受冷却影响）
        if (cachedControllerPos != null) {
            if (trySetSpeed(level.getBlockEntity(cachedControllerPos), targetRpm))
                return;
            cachedControllerPos = null; // EN: Cache invalidated, continue scanning / 缓存失效，继续扫描
        }

        // EN: Skip scanning during cooldown (only effective when no cache)
        // 冷却期跳过扫描（仅在没有缓存时生效）
        if (scanCooldown > 0) { scanCooldown--; return; }

        // EN: Search for speed controller on 6 adjacent faces
        // 在 6 个相邻面查找转速控制器
        for (var dir : net.minecraft.core.Direction.values()) {
            BlockPos p = worldPosition.relative(dir);
            if (trySetSpeed(level.getBlockEntity(p), targetRpm)) {
                cachedControllerPos = p;
                return;
            }
        }
        // EN: Not found, cooldown 20 ticks before retrying
        // 未找到，冷却 20 tick 后重试
        scanCooldown = 20;
    }
}
