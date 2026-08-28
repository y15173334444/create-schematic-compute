package io.github.y15173334444.create_schematic_compute.mixin;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 传播决策诊断探针（临时调研设施）：打印每次 propagateNewSource 的双方状态，
 * 用于定位官方 SpeedController 大幅调速存活的精确序列。
 * Propagation decision probe (temporary research instrumentation): logs each
 * propagateNewSource's both-side state to locate the exact sequence by which the
 * official SpeedController survives large jumps.
 */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class RotationPropagatorDiagnosticsMixin {

    @Inject(
        method = "propagateNewSource(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z", remap = false),
        remap = false
    )
    private static void csc$onDestroyProbe(KineticBlockEntity currentTE, CallbackInfo ci) {
        java.io.StringWriter sw = new java.io.StringWriter();
        new Exception("destroy at " + currentTE.getBlockPos()).printStackTrace(new java.io.PrintWriter(sw));
        String[] lines = sw.toString().split("\\n");
        StringBuilder key = new StringBuilder();
        for (String l : lines) {
            if (l.contains("RotationPropagator$")) continue;
            if (l.contains("RotationPropagator.")) { key.append(l.trim()).append(" | "); }
        }
        SchematicCompute.LOGGER.error("[PropDestroy] {} | frames: {}", currentTE.getBlockPos(), key);
    }

    @Inject(
        method = "propagateNewSource(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;)V",
        at = @At("HEAD"),
        remap = false
    )
    private static void csc$probe(KineticBlockEntity currentTE, CallbackInfo ci) {
        if (!(currentTE.getLevel() instanceof net.minecraft.server.level.ServerLevel))
            return;
        SchematicCompute.LOGGER.info("[Prop] enter cur={}({},{}) theo={} net={} src={} flicker={}",
            currentTE.getBlockState().getBlock(), currentTE.getBlockPos().getX(),
            currentTE.getBlockPos().getY(), currentTE.getBlockPos().getZ(),
            String.format("%.2f", currentTE.getTheoreticalSpeed()),
            currentTE.hasNetwork() ? currentTE.network : "null",
            currentTE.hasSource() ? currentTE.source : "null",
            currentTE.getFlickerScore());
    }
}
