package com.example.create_schematic_compute.mixin;

import com.example.create_schematic_compute.client.ControlSeatInputHandler;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercept sable's view-vector rotation at the source.
 * Sable's camera_rotation.EntityMixin transforms the player's view vector by the
 * sublevel quaternion via EntitySubLevelRotationHelper.getEntityOrientation().
 * Return null to skip the rotation when riding a ControlSeatEntity in View Angle mode.
 */
@Mixin(targets = "dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper", remap = false)
public class EntitySubLevelUtilMixin {

    @Inject(method = "getEntityOrientation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onGetEntityOrientation(Entity entity,
            java.util.function.Function<?, ?> poseProvider, float partialTicks, Object type,
            CallbackInfoReturnable<?> cir) {
        if (!(entity instanceof Player player)) return;
        if (!(player.getVehicle() instanceof ControlSeatEntity)) return;
        if (ControlSeatInputHandler.getInputMode() != 1) return;
        cir.setReturnValue(null); // return null → skip sublevel view rotation
    }
}
