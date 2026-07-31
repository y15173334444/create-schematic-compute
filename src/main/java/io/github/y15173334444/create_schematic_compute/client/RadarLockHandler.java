package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;
import io.github.y15173334444.create_schematic_compute.network.RadarLockPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * Client-side handler for locking/unlocking radar blips (target markers) via right-click.
 * <p>
 * 客户端侧雷达 blip（目标标记）锁定/解锁处理器，通过右键点击触发。
 * <p>
 * When the player right-clicks in open air, this handler casts a ray from the camera
 * through the player's crosshair and checks whether it hits any visible blip rendered
 * by nearby {@link RadarBlockEntity} instances.  If a blip is hit within the tolerance
 * radius, a {@link RadarLockPacket} is sent to the server to toggle the lock state.
 * <p>
 * 当玩家在空气中右键时，此处理器从相机沿准星方向发射射线，检测是否命中附近
 * {@link RadarBlockEntity} 实例渲染的可见 blip。若在容差半径内命中，则向服务端发送
 * {@link RadarLockPacket} 以切换锁定状态。
 * <p>
 * The coordinate transformations here exactly mirror those in the blip renderer
 * ({@code RadarRenderer}) so that the hit-test geometry perfectly matches what the
 * player sees on screen, including Sable (moving platform) rotation compensation.
 * <p>
 * 此处的坐标变换与 blip 渲染器 ({@code RadarRenderer}) 完全一致，确保命中检测几何体
 * 与玩家屏幕上所见完全匹配，包括 Sable（移动平台）旋转补偿。
 *
 * @author y15173334444
 */
@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class RadarLockHandler {

    /**
     * Reusable {@link Vector3f} instance to avoid per-frame heap allocations during
     * the hit-test loop.  Allocated once as a static final; mutated in-place on each
     * call to {@link #findBlipClient}.
     * <p>
     * 可复用的 {@link Vector3f} 实例，避免命中检测循环中每帧产生堆分配。
     * 以 static final 分配一次，每次调用 {@link #findBlipClient} 时原地修改。
     */
    private static final Vector3f REUSABLE_VEC = new Vector3f();

    /**
     * Second reusable {@link Vector3f} for intermediate calculations in the blip
     * coordinate-transform pipeline.
     * <p>
     * 第二个可复用 {@link Vector3f}，用于 blip 坐标变换管线中的中间计算。
     */
    private static final Vector3f REUSABLE_VEC2 = new Vector3f();

    /**
     * Third reusable {@link Vector3f} used when converting the local display-space
     * position back to world-space for distance comparison against the crosshair ray.
     * <p>
     * 第三个可复用 {@link Vector3f}，用于将局部的显示空间坐标转换回世界空间，
     * 以便与准星射线进行距离比较。
     */
    private static final Vector3f REUSABLE_VEC3 = new Vector3f();

    /**
     * Lazily-initialized reusable {@link org.joml.Quaternionf} for the inverse
     * Sable rotation (world-to-sub-local).  Lazy because the quaternion is only
     * needed when the radar is mounted on a Sable contraption.
     * <p>
     * 延迟初始化的可复用 {@link org.joml.Quaternionf}，用于 Sable 逆旋转
     * （世界→子世界本地）。延迟初始化是因为仅在雷达安装在 Sable 装置上时才需要。
     */
    private static org.joml.Quaternionf reusableQuat = null;

    /**
     * Lazily-initialized reusable {@link org.joml.Quaternionf} for the forward
     * Sable rotation (sub-local-to-world).  Kept separate from {@link #reusableQuat}
     * so that both can coexist in the same frame without overwriting each other.
     * <p>
     * 延迟初始化的可复用 {@link org.joml.Quaternionf}，用于 Sable 正向旋转
     * （子世界本地→世界）。与 {@link #reusableQuat} 分开以允许同一帧内二者共存。
     */
    private static org.joml.Quaternionf reusableQuat2 = null;

    /**
     * A hit result representing a player's crosshair intersecting a radar blip.
     * <p>
     * 表示玩家准星与雷达 blip 相交的命中结果。
     *
     * @param entityId the entity ID of the blip target / blip 目标实体 ID
     * @param distance perpendicular distance from the crosshair ray to the blip center / 准星射线到 blip 中心的垂直距离
     */
    private record BlipHit(int entityId, double distance) {}

    /**
     * Handles right-click mouse input on the client side.
     * <p>
     * 处理客户端侧鼠标右键输入。
     * <p>
     * Logic (逻辑):
     * <ol>
     *   <li>Ignore non-right-click or non-press events / 忽略非右键或非按下事件</li>
     *   <li>If the player is looking at a radar block itself, defer to the server-side
     *       {@code useWithoutItem} handler to avoid duplicate processing / 若玩家正在看向
     *       雷达方块本身，交由服务端 {@code useWithoutItem} 处理以避免重复</li>
     *   <li>Otherwise, raycast against all loaded radar block-entities' blips and lock/unlock
     *       the closest one within the 2.0-block tolerance radius / 否则对已加载的所有雷达方块
     *       实体的 blip 进行射线检测，锁定/解锁 2.0 方块容差半径内最近的一个</li>
     * </ol>
     *
     * @param event the mouse-button input event posted after vanilla processing /
     *              原版处理后发布的鼠标按键输入事件
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        // Only handle right-click press; ignore releases and other buttons
        // 仅处理右键按下；忽略释放和其他按键
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.getAction() != GLFW.GLFW_PRESS) return;
        var mc = Minecraft.getInstance();
        // Bail out if the world, player, or camera isn't ready, or if a GUI is open
        // 世界、玩家或相机未就绪，或有 GUI 打开时提前退出
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        var player = mc.player;

        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();
        Vec3 end = eyePos.add(lookVec.scale(20));
        var blockHit = mc.level.clip(new ClipContext(eyePos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));

        // Raycast blips FIRST. Blips float in the air, typically right above the radar
        // block itself, so a raycast to a blip often passes through the radar block
        // first. If we deferred to useWithoutItem whenever the block is hit, the air
        // blip selection would be silently rejected (the block UI/`useWithoutItem`
        // takes over). Blip intent takes priority; only when NO blip is hit do we
        // let the radar block's own handler proceed.
        // 先做 blip 射线检测。blip 悬浮在空中，通常就在雷达方块正上方，指向 blip 的
        // 射线往往先穿过雷达方块本体。若一命中方块就让位给 useWithoutItem，空中 blip
        // 选择会被静默拒绝（方块 UI 接管）。故 blip 意图优先；仅当无 blip 命中时才
        // 交给雷达方块自身的处理。
        BlipHit bestHit = null;
        RadarBlockEntity bestRadar = null;
        double bestDist = 2.0; // tolerance radius in blocks / 容差半径（方块）

        for (var radar : RadarBlockEntity.getClientRadars()) {
            if (radar.targets.isEmpty()) continue;
            BlipHit hit = findBlipClient(radar, player);
            if (hit != null && hit.distance < bestDist) {
                bestDist = hit.distance;
                bestHit = hit;
                bestRadar = radar;
            }
        }

        if (bestHit != null && bestRadar != null) {
            handleLock(bestRadar, bestRadar.getBlockPos(), bestHit.entityId);
            return;
        }

        // No blip was hit — if the crosshair is on a radar block itself, defer to the
        // server-side useWithoutItem (shift = program GUI, no-shift = server-side blip
        // pick), avoiding a duplicate packet.
        // 无 blip 命中——若准星正指向雷达方块本身，交由服务端 useWithoutItem 处理
        //（潜行 = 编程 GUI，非潜行 = 服务端 blip 选取），避免重复发包。
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            if (mc.level.getBlockEntity(blockHit.getBlockPos()) instanceof RadarBlockEntity) return;
        }
    }

    /**
     * Sends a lock/unlock toggle packet to the server and optimistically updates
     * the local lock set so the UI responds immediately.
     * <p>
     * 向服务端发送锁定/解锁切换数据包，并乐观更新本地锁定集合以使 UI 即时响应。
     * <p>
     * The optimistic update may be reverted if the server rejects the lock (e.g. the
     * entity no longer exists).  No explicit rollback mechanism is needed because the
     * server-synced target list will overwrite the local set on the next update tick.
     * <p>
     * 若服务端拒绝锁定（如实体已不存在），乐观更新可能被回退。无需显式回滚机制，
     * 因为服务端同步的目标列表会在下一个更新 tick 覆盖本地集合。
     *
     * @param radar    the radar block entity whose lock set is being mutated /
     *                 其锁定集合将被修改的雷达方块实体
     * @param blockPos the world position of the radar block, included in the packet
     *                 so the server can locate the block entity /
     *                 雷达方块的世界坐标，包含在数据包中供服务端定位方块实体
     * @param targetId the entity ID of the target blip to toggle / 要切换的 blip 目标实体 ID
     */
    private static void handleLock(RadarBlockEntity radar, net.minecraft.core.BlockPos blockPos, int targetId) {
        boolean isLocked = radar.lockedTargets.contains(targetId);
        // Toggle: if currently locked → unlock; if unlocked → lock.
        // 切换：当前已锁定 → 解锁；未锁定 → 锁定。
        PacketDistributor.sendToServer(new RadarLockPacket(blockPos, targetId, !isLocked));
        if (isLocked) radar.lockedTargets.remove(targetId);
        else {
            radar.lockedTargets.add(targetId);
            // Optimistic update — the server may reject this if the entity is stale.
            // 乐观更新 —— 若实体已过期，服务端可能拒绝此操作。
        }
    }

    /**
     * Performs a client-side crosshair-vs-blip hit-test against all targets of a
     * single radar block entity, using exactly the same coordinate transformations
     * as the blip renderer ({@code RadarRenderer}).
     * <p>
     * 对单个雷达方块实体的所有目标执行客户端侧准星对 blip 的命中检测，
     * 使用与 blip 渲染器 ({@code RadarRenderer}) 完全相同的坐标变换。
     * <p>
     * The transform pipeline mirrors the renderer's world-to-screen mapping in reverse,
     * plus a final projection of the blip world position onto the crosshair ray:
     * <p>
     * 变换管线反向复现了渲染器的世界到屏幕映射，外加将 blip 世界坐标
     * 投影到准星射线上：
     * <ol>
     *   <li>Compute the radar's world-space display center, including Sable rotation
     *       offset if mounted on a moving contraption / 计算雷达世界空间显示中心，
     *       若安装在移动装置上则包含 Sable 旋转偏移</li>
     *   <li>For each target: compute offset from radar center, apply inverse Sable
     *       rotation (world→sub-local), then apply facing rotation to reach display
     *       space / 对每个目标：计算相对雷达中心的偏移，应用 Sable 逆旋转
     *       （世界→子世界本地），再应用朝向旋转到达显示空间</li>
     *   <li>Clamp targets to the display axis bounds (same as renderer clipping) /
     *       将目标限制在显示坐标轴边界内（与渲染器裁剪一致）</li>
     *   <li>Transform the display-space position back to world-space (forward Sable
     *       rotation + facing un-rotation) to obtain the true world position for
     *       ray-projection / 将显示空间位置变换回世界空间（正向 Sable 旋转 +
     *       取消朝向旋转）以获得真实世界位置用于射线投影</li>
     *   <li>Project onto the crosshair ray: compute the perpendicular distance;
     *       the blip with the smallest distance under the tolerance wins /
     *       投影到准星射线：计算垂直距离；容差内距离最小者胜出</li>
     * </ol>
     *
     * @param be     the radar block entity whose targets to test / 要检测目标的雷达方块实体
     * @param player the local player (provides eye position and look vector) /
     *               本地玩家（提供眼睛位置和视线方向向量）
     * @return the closest blip hit within the 2.0-block tolerance, or {@code null} if
     *         no blip is under the crosshair / 2.0 方块容差内最近的 blip 命中，
     *         若无 blip 在准星下则返回 {@code null}
     */
    @Nullable
    private static BlipHit findBlipClient(RadarBlockEntity be, net.minecraft.world.entity.player.Player player) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        // Determine whether the radar is mounted on a Sable contraption (moving platform).
        // When on Sable, the radar's world position and orientation are dynamic per-frame
        // rather than tied to a fixed BlockPos.
        // 判断雷达是否安装在 Sable 装置（移动平台）上。
        // 在 Sable 上时，雷达的世界位置和朝向是每帧动态的，而非固定在 BlockPos。
        boolean onSable = !Float.isNaN(be.cachedSubYaw);
        float facingYDeg = be.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)
            ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING).toYRot() : 0;
        int scanRange = Math.max(1, be.scanRange);
        float axisLen = be.displayScale * 0.5f;

        // Radar world-space center + display offset after Sable pose transform.
        // 雷达世界空间中心 + Sable 姿态变换后的显示偏移。
        double rwx = onSable ? be.cachedSubWorldX : be.getBlockPos().getX() + 0.5;
        double rwy = onSable ? be.cachedSubWorldY : be.getBlockPos().getY() + 0.5;
        double rwz = onSable ? be.cachedSubWorldZ : be.getBlockPos().getZ() + 0.5;
        var dispOff = REUSABLE_VEC.set(be.displayX, be.displayY, be.displayZ);
        // Undo the block's horizontal facing rotation to bring the display offset into
        // sub-local space (the coordinate system of the Sable contraption, if present).
        // 取消方块水平朝向旋转，将显示偏移带入子世界本地空间（Sable 装置的坐标系，若存在）。
        dispOff.rotateY((float) Math.toRadians(-facingYDeg));

        // Build the forward Sable rotation quaternion (sub-local → world).
        // This transforms positions from the contraption's local frame to the world frame.
        // 构建 Sable 正向旋转四元数（子世界本地 → 世界）。
        // 将位置从装置本地坐标系变换到世界坐标系。
        org.joml.Quaternionf fwdQ = null;
        if (onSable && !Float.isNaN(be.cachedSubQw)) {
            if (reusableQuat2 == null) reusableQuat2 = new org.joml.Quaternionf();
            fwdQ = reusableQuat2.set(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw);
            dispOff.rotate(fwdQ);
        }
        // Accumulate the display offset onto the radar world center.
        // 将显示偏移累加到雷达世界中心上。
        rwx += dispOff.x; rwy += dispOff.y; rwz += dispOff.z;

        // Pre-compute the inverse Sable rotation quaternion (world → sub-local).
        // This is the conjugate of the forward quaternion, used to transform target
        // world positions back into the contraption's local frame for display-axis
        // clipping, mirroring the renderer's transform order.
        // 预计算 Sable 逆旋转四元数（世界 → 子世界本地）。
        // 这是正向四元数的共轭，用于将目标世界位置变换回装置本地坐标系以进行
        // 显示轴裁剪，与渲染器的变换顺序一致。
        org.joml.Quaternionf invQ = null;
        if (onSable && !Float.isNaN(be.cachedSubQw)) {
            if (reusableQuat == null) reusableQuat = new org.joml.Quaternionf();
            // conjugate() yields the inverse for a unit quaternion, which Sable always provides.
            // 对于单位四元数（Sable 始终提供），conjugate() 即为逆。
            invQ = reusableQuat.set(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw).conjugate();
        }

        int best = 0;
        double bestDist = 2.0; // tolerance radius in blocks / 容差半径（方块）
        boolean found = false;
        for (var t : be.targets) {
            // Step 1: offset from radar world center to target world position
            // 第 1 步：从雷达世界中心到目标世界位置的偏移
            float dx = (float)(t.x() - rwx);
            float dy = (float)(t.y() - rwy);
            float dz = (float)(t.z() - rwz);
            var v = REUSABLE_VEC2.set(dx, dy, dz);

            // Step 2: if on Sable, rotate into the contraption's local frame so the
            // display-axis clipping is performed in a consistent orientation.
            // 第 2 步：若在 Sable 上，旋转变换到装置本地坐标系，
            // 使显示轴裁剪在一致的方向下执行。
            if (invQ != null) v.rotate(invQ);

            // Step 3: apply block facing rotation to reach display space,
            // then scale by scanRange→axisLen to match the rendered coordinate system.
            // 第 3 步：应用方块朝向旋转到达显示空间，
            // 再按 scanRange→axisLen 缩放以匹配渲染坐标系。
            v.rotateY((float) Math.toRadians(facingYDeg));
            float rx = v.x / scanRange * axisLen;
            float ry = v.y / scanRange * axisLen;
            float rz = v.z / scanRange * axisLen;

            // Clamp to display axis bounds — targets outside the rendered axes are invisible.
            // 裁剪到显示坐标轴边界 —— 超出渲染轴范围的目标不可见。
            if (Math.abs(rx) > axisLen || Math.abs(ry) > axisLen || Math.abs(rz) > axisLen) continue;

            // Step 4: reverse the transform to get the true world position.
            // First undo the facing rotation, then apply forward Sable rotation.
            // 第 4 步：反向变换以获得真实世界位置。
            // 先撤销朝向旋转，再应用正向 Sable 旋转。
            var wo = REUSABLE_VEC3.set(rx, ry, rz);
            wo.rotateY((float) Math.toRadians(-facingYDeg));
            if (fwdQ != null) {
                wo.rotate(fwdQ);
            }
            // Reconstruct the actual world-space position of this blip for ray projection.
            // 重建此 blip 的实际世界空间位置，用于射线投影。
            double wx = rwx + wo.x;
            double wy = rwy + wo.y;
            double wz = rwz + wo.z;

            // Project the blip position onto the player's crosshair ray.
            // Compute the perpendicular distance from the blip center to the ray.
            // 将 blip 位置投影到玩家准星射线上。
            // 计算 blip 中心到射线的垂直距离。
            var tp = new Vec3(wx, wy, wz);
            var toTarget = tp.subtract(eyePos);
            double dot = toTarget.dot(lookVec);
            // Skip targets behind the player (dot ≤ 0 means the angle is ≥ 90°).
            // 跳过玩家背后的目标（dot ≤ 0 表示角度 ≥ 90°）。
            if (dot <= 0) continue;
            var proj = eyePos.add(lookVec.scale(dot));
            double dist = tp.distanceTo(proj);
            if (dist < bestDist) { bestDist = dist; best = t.entityId(); found = true; }
        }
        return found ? new BlipHit(best, bestDist) : null;
    }
}
