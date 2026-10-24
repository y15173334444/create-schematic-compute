package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 转速代理方块实体：从邻面查找官方 {@link SpeedControllerBlockEntity} 并把图中
 * {@code SPEED_CTRL} 节点的输出写进它的滚轮目标值。
 * Speed proxy block entity: finds an official {@link SpeedControllerBlockEntity} on the
 * adjacent faces and writes the graph's SPEED_CTRL output into its scroll target.
 *
 * <p>tick 编排为规范 15 步的 L 档（Monitor/KineticGauge 同款：轻量重编译 + 3 参求值）。
 * 此前缺 ③⑥⑧⑩⑭⑮（P2-18）——图 generation 变化无人查（运行中新增 REDSTONE_IN/OUT 节点
 * 静默失效直到区块重载）、红石输入恒 0（buildInputs 用空表）、结果不写出、运行态不落盘、
 * 挂起全量同步永不冲刷；本版全部补齐。</p>
 * <p>The tick follows the canonical 15-step orchestration, L variant (Monitor/KineticGauge
 * style: light recompile + 3-arg evaluate). It previously missed ③⑥⑧⑩⑭⑮ (P2-18) — graph
 * generation changes were never checked (REDSTONE_IN/OUT nodes added while running stayed
 * dead until chunk reload), redstone inputs read 0 (empty input list), results were never
 * written back, the running state never persisted, and pending full syncs never flushed.
 * All wired now.</p>
 */
public class SpeedProxyBlockEntity extends SyncedGraphBlockEntity implements GraphHost.TickHooks {

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

    /** tick 体已收敛进 GraphHost.tickBody（K23）；转速代理的偏差化为声明与钩子（⑤ 介于
     *  ③④、④ L 档、⑨ 基础档、缝 8 SPEED_CTRL 落盘）。/ The tick body converged into
     *  GraphHost.tickBody (K23); every SpeedProxy deviation is a declaration or hook below. */
    public void tick() {
        tickBody(this);
    }

    // ── tick 编排偏差声明（K23 骨架钩子）/ tick-orchestration deviations (K23 hooks) ──

    /** ② LIT 指示属性（缺席即中止口径）。 / Step ② LIT property (abort-on-absent). */
    @Override public BooleanProperty litProperty() { return SpeedProxyBlock.LIT; }

    /** ⑤ 停机检查介于 ③ 与 ④（Monitor 口径）——保证 checkGraphChanged 先于停机早退：
     *  停机也要让链路注册跟随图 generation（P2-18）。
     *  Step ⑤ stop check between ③ and ④ (Monitor order) — checkGraphChanged precedes
     *  the stop early-return so link registration tracks graph generation even while
     *  stopped (P2-18). */
    @Override public GraphHost.StopSlot stopSlot() { return GraphHost.StopSlot.AFTER_GRAPH_CHECK; }

    /** ④ L 档轻量重编译（Monitor/KineticGauge 同款）。 / Step ④ Light recompile. */
    @Override public boolean lightRecompile() { return true; }

    /** ⑨ 基础档（3 参）。 / Step ⑨ basic (3-arg) form. */
    @Override public GraphHost.EvalVariant evalVariant() { return GraphHost.EvalVariant.BASIC; }

    /** 缝 8（⑫⑬ 后、共用尾段前）：SPEED_CTRL 输出 → 目标转速落盘。
     *  Seam 8 (after ⑫⑬, before the tail): SPEED_CTRL output → target-speed write. */
    @Override public void afterEvalBroadcast() {
        for (var n : graph().nodes) {
            if (n.type == NodeType.SPEED_CTRL) {
                float speed = evaluator().getNodeOutput(n.id, 0);
                applySpeed((int) speed);
                break;
            }
        }
    }

    /**
     * 直连官方公开 API 写目标转速（P3 依赖面清理）：Create 是 mandatory 依赖、
     * {@link SpeedControllerBlockEntity#targetSpeed} 是 public 字段（官方滚轮行为对象）、
     * {@code ScrollValueBehaviour#setValue(int)} 是 public 方法——旧版对二者走的整套
     * 反射间接纯属多余，失败还会每 tick error 刷日志，已整体删除。
     * 与 RedstoneLinkHelper 直接 import Create 类的先例同口径。
     * Writes the target speed through the official public API directly (P3 dependency-face
     * cleanup): Create is a mandatory dependency, {@link SpeedControllerBlockEntity#targetSpeed}
     * is a public field (the official scroll behaviour object) and
     * {@code ScrollValueBehaviour#setValue(int)} is public — the old reflective indirection
     * over both was pure overhead whose failure spammed an error per tick; it is gone.
     * Same discipline as RedstoneLinkHelper importing Create classes directly.
     *
     * @return true 表示 be 是转速控制器且已写入 / true when be is a speed controller and the
     *         value was written */
    private static boolean trySetSpeed(BlockEntity be, int rpm) {
        if (!(be instanceof SpeedControllerBlockEntity controller)) return false;
        controller.targetSpeed.setValue(rpm);
        be.setChanged();
        return true;
    }

    private void applySpeed(int targetRpm) {
        if (level == null) return;
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
