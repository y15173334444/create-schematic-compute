package io.github.y15173334444.create_schematic_compute.mixin;

import io.github.y15173334444.create_schematic_compute.client.editor.EditorPerfSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 编辑器性能设置的渲染面（docs/graph-editor-render-performance-plan.md §四 PERF-1 附注；
 * 设置页「性能」tab）：图编辑器打开期间按开关剔除世界与手部。
 *
 * <ul>
 * <li><b>renderLevel</b>：cullWorld 开启时整段取消——地形/实体/天空/手部全部不画，
 *     背板由 {@code AbstractGraphScreen.renderBackground} 的不透明主题底接管（ blur 后处理
 *     一并跳过，陈旧帧残影不可见）；</li>
 * <li><b>renderItemInHand</b>：cullFp（等效 F1）开启时取消手部——世界照常渲染，
 *     与 cullWorld 正交（两者都开时 renderLevel 已取消，本门是兜底空转）。</li>
 * </ul>
 * The render face of the editor perf settings: while a graph-editor-family UI is open, the
 * world and the hand are culled per the toggles. renderLevel cancels whole (terrain /
 * entities / sky / hand all skipped; the backdrop is taken over by the opaque theme base in
 * AbstractGraphScreen.renderBackground, and the blur pass is skipped with it); renderItemInHand
 * cancels just the hand for cullFp (F1-equivalent) with the world still rendering — orthogonal
 * to cullWorld (with both on, the renderLevel cancel already covers it and this gate idles).
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void csc$cullLevelBehindEditor(DeltaTracker delta, CallbackInfo ci) {
        if (EditorPerfSettings.cullWorldActive()) ci.cancel();
    }

    @Inject(method = "renderItemInHand(Lnet/minecraft/client/renderer/Camera;FLorg/joml/Matrix4f;)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void csc$cullFirstPersonHand(Camera camera, float partialTick, Matrix4f pose, CallbackInfo ci) {
        if (EditorPerfSettings.cullFpActive()) ci.cancel();
    }
}
