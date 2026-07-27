package io.github.y15173334444.create_schematic_compute.mixin;

import dev.ryanhcode.sable.Sable;
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
 * control sub-level camera rotation for Control Seat riders.
 * <p>
 * In FIXED mode inside a Sable sub-level, Sable is allowed to rotate the camera
 * (handling pitch/yaw/roll correctly). The manual lock sets the player to the
 * seat's block-local facing, and Sable adds the sub-level orientation on top.
 * <p>
 * In VIEW_DIFFERENCE mode or outside sub-levels, Sable rotation is disabled.
 * <p>
 * FIXED 模式在 Sable 子关卡内时允许 Sable 旋转相机（正确处理俯仰/偏航/横滚）。
 * 手动锁将玩家设为座椅方块本地朝向，Sable 在此基础上叠加子关卡旋转。
 * VIEW_DIFFERENCE 模式或不在子关卡内时禁用 Sable 旋转。
 *
 * <p>Registered in {@code create_schematic_compute.sable.mixins.json} ({@code required:false}).</p>
 */
@Mixin(EntitySubLevelRotationHelper.class)
public abstract class ControlSeatCameraMixin {

    @Inject(method = "shouldCameraRotate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void csc$controlSeatCamera(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        if (!(camera instanceof LocalPlayer player)) return;
        if (!(player.getVehicle() instanceof ControlSeatEntity)) return;

        int mode = ControlSeatInputHandler.getInputMode();
        if (mode == 1) {
            // VIEW_DIFFERENCE: free camera, no Sable rotation
            // 视角差模式：自由相机，无 Sable 旋转
            cir.setReturnValue(false);
            return;
        }
        // FIXED mode: enable Sable rotation only inside a sub-level
        // (Sable handles pitch/yaw/roll; manual lock sets local block facing)
        // FIXED 模式：仅在子关卡内启用 Sable 旋转
        // （Sable 处理俯仰/偏航/横滚；手动锁设方块本地朝向）
        boolean inSubLevel = Sable.HELPER.getContaining(player.getVehicle()) != null;
        cir.setReturnValue(inSubLevel);
    }
}
