package io.github.y15173334444.create_schematic_compute.mixin;

import io.github.y15173334444.create_schematic_compute.client.editor.EditorPerfSettings;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 编辑器性能设置的 HUD 面（设置页「性能」tab）：cullFp（等效 F1）开启且图编辑器打开期间，
 * 原版 HUD（准星/快捷栏/聊天提示等）整段不画——世界照常渲染，透过编辑器透明背景观察时
 * 不被 HUD 杂讯遮挡。与 {@link GameRendererMixin} 的手部门共同构成 F1 等效集。
 * The HUD face of the editor perf settings: with cullFp (F1-equivalent) on and a
 * graph-editor-family UI open, the vanilla HUD (crosshair / hotbar / chat hints / …) is
 * skipped entirely while the world keeps rendering — watching the world through the editor's
 * transparent background stays uncluttered. Together with GameRendererMixin's hand gate this
 * forms the F1-equivalent set.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void csc$cullHudBehindEditor(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (EditorPerfSettings.cullFpActive()) ci.cancel();
    }
}
