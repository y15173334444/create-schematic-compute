package com.example.create_schematic_compute.mixin;

import com.example.create_schematic_compute.client.ControlSeatInputHandler;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stabilize camera when riding ControlSeatEntity in View Angle mode.
 *  Sable sublevel transforms override the camera at the render level.
 *  We intercept Camera.setup() and force the camera back to the player's
 *  actual head rotation, undoing sable's coordinate transform. */
@Mixin(Camera.class)
public class CameraMixin {

    private static final java.lang.reflect.Field YROT_FIELD;
    private static final java.lang.reflect.Field XROT_FIELD;
    static {
        java.lang.reflect.Field yf = null, xf = null;
        try {
            yf = Camera.class.getDeclaredField("yRot");
            yf.setAccessible(true);
            xf = Camera.class.getDeclaredField("xRot");
            xf.setAccessible(true);
        } catch (Exception ignored) {}
        YROT_FIELD = yf; XROT_FIELD = xf;
    }

    @Inject(method = "setup", at = @At("RETURN"), remap = false)
    private void afterSetup(BlockGetter level, Entity entity, boolean detached,
                            boolean thirdPersonReverse, float partialTick,
                            CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || entity == null || entity != player) return;
        if (!(player.getVehicle() instanceof ControlSeatEntity)) return;
        if (ControlSeatInputHandler.getInputMode() != 1) return;

        // Override camera rotation with player's actual head yaw/pitch,
        // undoing sable's sublevel transform that was applied during setup
        try {
            if (YROT_FIELD != null) YROT_FIELD.setFloat(this, player.getYHeadRot());
            if (XROT_FIELD != null) XROT_FIELD.setFloat(this, player.getXRot());
        } catch (Exception ignored) {}
    }
}
