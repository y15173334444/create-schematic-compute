package io.github.y15173334444.create_schematic_compute.compat;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sable-compatible Monitor Block Entity (BE).
 * <p>
 * Implements {@link BlockEntitySubLevelActor} solely to restore the {@code level}
 * reference inside {@code sable$physicsTick} — on a Sable structure the BE's level
 * may be nulled or redirected (Radar hit the same issue), and without a valid level
 * the server-side {@code tick()} bails early so the graph is never evaluated and the
 * HUD content freezes. No pose/coordinate caching is needed: HUD content is drawn on
 * the panel canvas in the poseStack's local frame (structure transform already
 * applied by the structure renderer), so Sable structures work natively.
 * <p>
 * sable 兼容的全息显示器方块实体（BE）：
 * 实现 {@link BlockEntitySubLevelActor} 仅用于在 {@code sable$physicsTick} 中恢复
 * {@code level} 引用——Sable 结构上的 BE level 可能为 null 或被重定向（雷达遇到过
 * 同样问题），没有有效 level 时服务端 {@code tick()} 提前返回，图不再求值、HUD
 * 内容冻结。不需要姿态/坐标缓存：HUD 内容画在 poseStack 局部坐标系的面板画布上
 * （结构变换已由结构渲染器应用），Sable 结构天然支持。
 */
public class MonitorBlockEntitySable extends MonitorBlockEntity implements BlockEntitySubLevelActor {

    /** Backup Level reference — {@code level} can be null during early lifecycle
     *  phases (e.g. before onLoad completes in sable$physicsTick).
     *  {@code level} 备份引用——生命周期早期（如 sable$physicsTick 在 onLoad 完成前）可能为 null。 */
    private volatile Level savedLevel;

    public MonitorBlockEntitySable(BlockPos pos, BlockState s) { super(pos, s); }

    /**
     * Sable per-physics-frame callback: restore the Level reference so tick()
     * keeps evaluating the graph on structures.
     * <p>
     * Sable 物理帧回调：恢复 Level 引用，使结构上的 tick() 继续求值。
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (savedLevel == null) savedLevel = subLevel.getLevel();
        if (this.level == null) this.level = savedLevel;
    }
}
