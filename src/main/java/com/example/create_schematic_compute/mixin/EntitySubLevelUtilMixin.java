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
 * Intercept sable's hasCustomEntityOrientation() to disable sublevel view-vector
 * rotation when riding a ControlSeatEntity in View Angle mode.
 * <p>
 * Sable has two rotation mechanisms:
 * 1. entities_turn_with_sub_levels — subtracts sublevel yaw from player fields (skipped when has vehicle)
 * 2. camera_rotation.EntityMixin — rotates view vector by sublevel quaternion (ALWAYS active)
 * <p>
 * This Mixin makes mechanism 2 skip our seat by reporting "has custom orientation".
 */
@Mixin(targets = "dev.ryanhcode.sable.api.entity.EntitySubLevelUtil", remap = false)
public class EntitySubLevelUtilMixin {

    @Inject(method = "hasCustomEntityOrientation", at = @At("RETURN"), cancellable = true, remap = false)
    private static void onHasCustomEntityOrientation(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) return;
        if (!(entity instanceof Player player)) return;
        if (!(player.getVehicle() instanceof ControlSeatEntity)) return;
        if (ControlSeatInputHandler.getInputMode() != 1) return;
        cir.setReturnValue(true);
    }
}
