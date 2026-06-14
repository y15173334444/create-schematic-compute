package com.example.create_schematic_compute.mixin;

import com.example.create_schematic_compute.client.ControlSeatInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercept Entity.turn() — joystick mode suppression + mouse delta export + View Angle accumulation */
@Mixin(Entity.class)
public class LocalPlayerMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, remap = false)
    private void onTurn(double yaw, double pitch, CallbackInfo ci) {
        if ((Object)this != Minecraft.getInstance().player) return;
        ControlSeatInputHandler.onRawMouseDelta(yaw, pitch);
        if (ControlSeatInputHandler.isSuppressingMouseTurn()) {
            ci.cancel();
        }
    }
}
