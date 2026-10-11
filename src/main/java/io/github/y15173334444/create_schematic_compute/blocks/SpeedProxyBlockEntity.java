package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
public class SpeedProxyBlockEntity extends SyncedGraphBlockEntity {

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
        // 第 ⑮ 步（P1-8 补线）：挂起全量同步按 grace 窗口冲刷；必须在所有早退之前（Monitor 同款）。
        // Step ⑮ (P1-8 wiring): flush the pending full sync within the grace window; before
        // every early return (Monitor style).
        flushPendingFullSync();
        boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
        var state = getBlockState();
        if (!state.hasProperty(SpeedProxyBlock.LIT)) return;
        if (state.getValue(SpeedProxyBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, state.setValue(SpeedProxyBlock.LIT, shouldBeLit), 3);
        // 第 ③ 步（P2-18 补线）：红石链路注册靠这里的 generation 比对（onLoad 只注册一次，
        // 运行中图内新增 REDSTONE_IN/OUT 节点此后静默失效直到区块重载——本调用是唯一自愈口）。
        // Step ③ (P2-18 wiring): redstone link registration rides this generation compare
        // (onLoad registers once; REDSTONE_IN/OUT nodes added to a running graph stayed dead
        // until chunk reload — this call is the only self-heal path).
        rs().checkGraphChanged(graph());
        if (!isRunning()) { onStopRunning(); return; }
        if (graphChanged()) recompileEvaluatorLight();
        // 第 ⑥ 步（P2-18 补线）：刷新红石链路输入（混合回退口径，与 Monitor/KineticGauge 的
        // L 档一致），否则图内 REDSTONE_IN 恒读 0。
        // Step ⑥ (P2-18 wiring): refresh redstone link inputs (hybrid fallback, same as the
        // L-variant Monitor/KineticGauge), otherwise every REDSTONE_IN reads 0.
        rs().refreshInputs();
        // 第 ⑦ 步（P2-12 补线）：冲突频道自愈与其他宿主同款（Blueprint 样板：refreshInputs
        // 之后、buildInputs 之前）；有恢复才 requestFullSync + 立即发包。
        // Step ⑦ (P2-12 wiring): conflicted-channel self-heal, same as every other host
        // (Blueprint style: after refreshInputs, before buildInputs); requestFullSync +
        // immediate push only when something recovered.
        if (BusChannelHelper.recoverConflictedChannels(graph(), worldPosition, level)) {
            requestFullSync();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        // 第 ⑧ 步（P2-18 补线）：按图构建输入（原 List.of() 空表让 REDSTONE_IN 恒 0）。
        // 无 REDSTONE_IN 的图得到空表，与旧行为逐字节一致——纯 SPEED_CTRL 图零回归。
        // Step ⑧ (P2-18 wiring): build inputs from the graph (the old List.of() kept every
        // REDSTONE_IN at 0). Graphs without REDSTONE_IN yield the same empty list as before —
        // zero regression for pure SPEED_CTRL graphs.
        var in = rs().buildInputs(graph());
        var results = evaluator().evaluate(in, runtimeState().pidState, 0.05f);
        // 第 ⑩ 步（P2-18 补线）：写出红石输出（与其他宿主同款；图内无 REDSTONE_OUT 时为空表
        // 零副作用）。
        // Step ⑩ (P2-18 wiring): write redstone outputs back (same as every other host; empty
        // results when the graph has no REDSTONE_OUT — a no-op).
        rs().writeOutputs(results);
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
        // 第 ⑭ 步（P2-18 补线）：运行态主动落盘（12 宿主此前唯一不 setChanged 的——持久性
        // 弱于所有宿主）。
        // Step ⑭ (P2-18 wiring): persist the running state proactively (the only one of the
        // twelve hosts that never setChanged — weaker persistence than every other host).
        setChanged();
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
