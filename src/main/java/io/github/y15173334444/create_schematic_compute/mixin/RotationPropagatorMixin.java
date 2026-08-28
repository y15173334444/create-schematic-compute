package io.github.y15173334444.create_schematic_compute.mixin;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让可编程变速器获得官方 SpeedController 的传播语义。
 * Gives the programmable transmission the official SpeedController propagation
 * semantics.
 *
 * <p>官方对 SC 的传播（被上游大齿轮驱动 + 按 targetSpeed 向外传播）是
 * {@link RotationPropagator#getConveyedSpeed} 里的硬编码特判
 * （isLargeCogToSpeedController），第三方方块拿不到该分支。本 Mixin 在
 * {@code getConveyedSpeed} 入口注入：任一端是本模组变速器时委托给
 * {@link ProgrammableTransmissionBlockEntity#getConveyedSpeed}（官方逐行复刻，
 * 含 max/min 符号钳制与四方向覆盖 —— 输出侧绝对定速、输入侧正常驱动、
 * 分支永远不会反向收编变速器）。</p>
 * <p>The official SC propagation (driven by an upstream cog + conveying targetSpeed
 * outward) is a hardcoded special case inside
 * {@code RotationPropagator.getConveyedSpeed} (isLargeCogToSpeedController);
 * third-party blocks cannot reach it. This mixin injects at the entry of
 * {@code getConveyedSpeed}: whenever either end is our transmission, it delegates to
 * {@link ProgrammableTransmissionBlockEntity#getConveyedSpeed} (a line-by-line
 * official replica with the max/min sign clamp covering all four directions —
 * absolute speed on the output side, normal drive on the input side, and the branch
 * can never adopt the transmission backwards).</p>
 */
@Mixin(RotationPropagator.class)
public class RotationPropagatorMixin {

    @Inject(method = "getConveyedSpeed", at = @At("HEAD"), cancellable = true, remap = false)
    private static void csc$getConveyedSpeed(KineticBlockEntity from, KineticBlockEntity to,
                                             CallbackInfoReturnable<Float> cir) {
        // to 是变速器 → 对端朝向变速器传播（驱动我们）→ targetingController=true
        // from 是变速器 → 变速器向外传播（驱动下游）→ targetingController=false
        // to is the transmission → propagating toward us (driving) → targeting=true
        // from is the transmission → propagating outward (driving) → targeting=false
        if (to instanceof ProgrammableTransmissionBlockEntity tx) {
            cir.setReturnValue(ProgrammableTransmissionBlockEntity.getConveyedSpeed(from, tx, true));
        } else if (from instanceof ProgrammableTransmissionBlockEntity tx) {
            cir.setReturnValue(ProgrammableTransmissionBlockEntity.getConveyedSpeed(to, tx, false));
        }
    }
}
