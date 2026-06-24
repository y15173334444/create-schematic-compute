package com.example.create_schematic_compute.client;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.RadarBlockEntity;
import com.example.create_schematic_compute.network.RadarLockPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class RadarLockHandler {

    /** 射线命中 blip 的结果 */
    private record BlipHit(int entityId, double distance) {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Post event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT || event.getAction() != GLFW.GLFW_PRESS) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        var player = mc.player;

        // 如果射线命中雷达方块，由服务端 useWithoutItem 处理，客户端跳过避免重复
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();
        Vec3 end = eyePos.add(lookVec.scale(20));
        var blockHit = mc.level.clip(new ClipContext(eyePos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            if (mc.level.getBlockEntity(blockHit.getBlockPos()) instanceof RadarBlockEntity) return;
        }

        // 遍历所有已加载的雷达方块，用射线检测 blip（空中点击 blip 的情况）
        BlipHit bestHit = null;
        RadarBlockEntity bestRadar = null;
        double bestDist = 2.0;

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
        }
    }

    /** 发送锁定数据包并更新本地状态 */
    private static void handleLock(RadarBlockEntity radar, net.minecraft.core.BlockPos blockPos, int targetId) {
        boolean isLocked = radar.lockedTargets.contains(targetId);
        PacketDistributor.sendToServer(new RadarLockPacket(blockPos, targetId, !isLocked));
        if (isLocked) radar.lockedTargets.remove(targetId);
        else radar.lockedTargets.add(targetId);
    }

    /** 客户端侧：与渲染器完全相同的 blip 位置计算，命中最靠近准星的 blip */
    @Nullable
    private static BlipHit findBlipClient(RadarBlockEntity be, net.minecraft.world.entity.player.Player player) {
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        boolean onSable = !Float.isNaN(be.cachedSubYaw);
        float facingYDeg = be.getBlockState().hasProperty(HorizontalDirectionalBlock.FACING)
            ? be.getBlockState().getValue(HorizontalDirectionalBlock.FACING).toYRot() : 0;
        int scanRange = Math.max(1, be.scanRange);
        float axisLen = be.displayScale * 0.5f;
        // 雷达世界位置 + Sable 姿态变换后的显示偏移
        double rwx = onSable ? be.cachedSubWorldX : be.getBlockPos().getX() + 0.5;
        double rwy = onSable ? be.cachedSubWorldY : be.getBlockPos().getY() + 0.5;
        double rwz = onSable ? be.cachedSubWorldZ : be.getBlockPos().getZ() + 0.5;
        var dispOff = new Vector3f(be.displayX, be.displayY, be.displayZ);
        dispOff.rotateY((float) Math.toRadians(-facingYDeg)); // 去掉方块朝向 → 子世界本地
        if (onSable && !Float.isNaN(be.cachedSubQw)) {
            var q = new org.joml.Quaternionf(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw);
            dispOff.rotate(q); // 子世界本地 → 世界（正向旋转）
        }
        rwx += dispOff.x; rwy += dispOff.y; rwz += dispOff.z;

        // 预计算 Sable 逆旋转四元数（世界→子世界本地）
        org.joml.Quaternionf invQ = null;
        if (onSable && !Float.isNaN(be.cachedSubQw)) {
            invQ = new org.joml.Quaternionf(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw);
            invQ.conjugate();
        }

        int best = 0;
        double bestDist = 2.0;
        boolean found = false;
        for (var t : be.targets) {
            float dx = (float)(t.x() - rwx);
            float dy = (float)(t.y() - rwy);
            float dz = (float)(t.z() - rwz);
            var v = new Vector3f(dx, dy, dz);
            if (invQ != null) v.rotate(invQ); // 世界 → 子世界本地
            v.rotateY((float) Math.toRadians(facingYDeg)); // 子世界本地 → 显示空间
            float rx = v.x / scanRange * axisLen;
            float ry = v.y / scanRange * axisLen;
            float rz = v.z / scanRange * axisLen;
            if (Math.abs(rx) > axisLen || Math.abs(ry) > axisLen || Math.abs(rz) > axisLen) continue;
            var wo = new Vector3f(rx, ry, rz);
            wo.rotateY((float) Math.toRadians(-facingYDeg)); // 去掉方块朝向 → 子世界本地
            if (onSable && !Float.isNaN(be.cachedSubQw)) {
                var q = new org.joml.Quaternionf(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw);
                wo.rotate(q); // 子世界本地 → 世界（正向旋转）
            }
            double wx = rwx + wo.x;
            double wy = rwy + wo.y;
            double wz = rwz + wo.z;

            var tp = new Vec3(wx, wy, wz);
            var toTarget = tp.subtract(eyePos);
            double dot = toTarget.dot(lookVec);
            if (dot <= 0) continue;
            var proj = eyePos.add(lookVec.scale(dot));
            double dist = tp.distanceTo(proj);
            if (dist < bestDist) { bestDist = dist; best = t.entityId(); found = true; }
        }
        return found ? new BlipHit(best, bestDist) : null;
    }
}
