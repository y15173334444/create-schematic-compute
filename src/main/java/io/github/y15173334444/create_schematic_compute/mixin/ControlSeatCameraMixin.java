package io.github.y15173334444.create_schematic_compute.mixin;

import dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper;
import io.github.y15173334444.create_schematic_compute.client.ControlSeatInputHandler;
import io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into Sable's {@code EntitySubLevelRotationHelper.shouldCameraRotate()} to
 * disable Sable's sub-level camera rotation while riding a Control Seat.
 * <p>
 * Sable sub-level camera rotation is disabled for Control Seat riders because
 * the seat manages camera orientation via {@code ControlSeatInputHandler}:
 * FIXED mode uses a manual camera lock (playerYaw/pitch = seat world yaw/pitch),
 * and VIEW_DIFFERENCE mode leaves the camera free. Letting Sable rotate the
 * view vector on top of the manual lock would cause double-rotation and jitter.
 * <p>
 * 控制座椅骑乘时禁用 Sable 子关卡相机旋转。座椅通过 ControlSeatInputHandler 管理
 * 相机朝向：FIXED 模式手动锁（playerYaw/pitch = 座椅世界朝向），VIEW_DIFFERENCE
 * 模式相机自由。若同时让 Sable 旋转 viewVector，会与手动锁叠加产生双重旋转和抖动。
 *
 * <p>Registered in {@code create_schematic_compute.sable.mixins.json} ({@code required:false})
 * so it is silently skipped when Sable is absent. / 注册在 required:false 配置中，
 * 无 Sable 时静默跳过。</p>
 */
@Mixin(EntitySubLevelRotationHelper.class)
public abstract class ControlSeatCameraMixin {

    /**
     * Always return {@code false} when the local player is riding a Control Seat.
     * This prevents Sable from rotating the camera view vector, delegating full
     * camera control to {@code ControlSeatInputHandler}.
     * <p>
     * 本地玩家骑乘控制座椅时始终返回 false，阻止 Sable 旋转相机，完全交由
     * ControlSeatInputHandler 管理。
     */
    @Inject(method = "shouldCameraRotate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void csc$controlSeatCamera(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (!(camera instanceof LocalPlayer player)) return;
        if (!(player.getVehicle() instanceof ControlSeatEntity)) return;
        // Disable Sable camera rotation for Control Seat riders.
        // 控制座椅骑乘时禁用 Sable 相机旋转。
        cir.setReturnValue(false);
    }
}
