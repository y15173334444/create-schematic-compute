package io.github.y15173334444.create_schematic_compute.client.renderer;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.GeometryConstants;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlock;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

public class MonitorBlockEntityRenderer implements BlockEntityRenderer<MonitorBlockEntity> {
    private static final Field STARTED_BUILDERS, FIXED_BUFFERS, SHARED_BUFFER, LAST_SHARED_TYPE;
    // BakedGlyph 私有字段反射（2026-08-24 手动字形锚定方案；模组锁定 MC 1.21.1，稳定）
    // BakedGlyph private-field reflection (manual-glyph anchoring; MC pinned to 1.21.1)
    private static final Field BG_LEFT, BG_RIGHT, BG_UP, BG_DOWN, BG_U0, BG_U1, BG_V0, BG_V1;
    private static java.lang.reflect.Method FONT_GET_FONT_SET;
    private static net.minecraft.client.gui.font.FontSet HUD_FONT_SET; // 缓存 / cached
    static {
        Field sb = null, fb = null, sh = null, lst = null;
        try {
            Class<?> cls = MultiBufferSource.BufferSource.class;
            for (var f : cls.getDeclaredFields()) {
                switch (f.getName()) {
                    case "startedBuilders" -> { sb = f; sb.setAccessible(true); }
                    case "fixedBuffers" -> { fb = f; fb.setAccessible(true); }
                    case "sharedBuffer" -> { sh = f; sh.setAccessible(true); }
                    case "lastSharedType" -> { lst = f; lst.setAccessible(true); }
                }
            }
        } catch (Exception e) { SchematicCompute.LOGGER.error("MonitorRenderer reflection init", e); }
        STARTED_BUILDERS = sb; FIXED_BUFFERS = fb; SHARED_BUFFER = sh; LAST_SHARED_TYPE = lst;
        Field lf = null, rf = null, uf = null, df = null, u0 = null, u1 = null, v0 = null, v1 = null;
        try {
            Class<?> bg = Class.forName("net.minecraft.client.gui.font.glyphs.BakedGlyph");
            for (var f : bg.getDeclaredFields()) {
                switch (f.getName()) {
                    case "left" -> { lf = f; lf.setAccessible(true); }
                    case "right" -> { rf = f; rf.setAccessible(true); }
                    case "up" -> { uf = f; uf.setAccessible(true); }
                    case "down" -> { df = f; df.setAccessible(true); }
                    case "u0" -> { u0 = f; u0.setAccessible(true); }
                    case "u1" -> { u1 = f; u1.setAccessible(true); }
                    case "v0" -> { v0 = f; v0.setAccessible(true); }
                    case "v1" -> { v1 = f; v1.setAccessible(true); }
                }
            }
            FONT_GET_FONT_SET = Font.class.getDeclaredMethod("getFontSet", net.minecraft.resources.ResourceLocation.class);
            FONT_GET_FONT_SET.setAccessible(true);
        } catch (Exception e) { SchematicCompute.LOGGER.error("MonitorRenderer BakedGlyph reflection init", e); }
        BG_LEFT = lf; BG_RIGHT = rf; BG_UP = uf; BG_DOWN = df;
        BG_U0 = u0; BG_U1 = u1; BG_V0 = v0; BG_V1 = v1;
    }

    /** 取 HUD 文字字体集合（Font.getFontSet 包私有 → 反射，缓存一次）。
     *  HUD FontSet via package-private Font.getFontSet (reflected, cached). */
    private static net.minecraft.client.gui.font.FontSet hudFontSet(Font font) {
        if (HUD_FONT_SET == null && FONT_GET_FONT_SET != null) {
            try {
                HUD_FONT_SET = (net.minecraft.client.gui.font.FontSet) FONT_GET_FONT_SET.invoke(font, net.minecraft.client.Minecraft.DEFAULT_FONT);
            } catch (Exception e) { SchematicCompute.LOGGER.error("Monitor getFontSet failed", e); }
        }
        return HUD_FONT_SET;
    }

    public MonitorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (be == null || be.graph == null) return;
        // HUD 模式：玻璃面板 + 内容直接绘制（见 renderHud——纯官方接口，无离屏 FBO）
        // HUD mode: glass panel + content drawn directly (see renderHud — official interfaces, no FBO)
        if (be.hudMode) { renderHud(be, poseStack, buffer); return; }
        if (be.graph.nodes.isEmpty()) return;

        // Read server-authoritative evaluation snapshot (synced via ClientboundGraphEvalPacket).
        // When not running, snapshot is null — display-only nodes (IMAGE, TEXT) still render,
        // but DATA nodes and signal-driven IMAGE offsets are skipped.
        // 读取服务端权威评估快照。未运行时快照为 null——仅显示节点（IMAGE、TEXT）仍渲染，
        // DATA 节点和信号驱动 IMAGE 偏移需跳过。
        var snapshot = be.cachedEvalSnapshot;
        boolean evalAvailable = be.running && snapshot != null;

        boolean hasContent = false;
        for (var n : be.graph.nodes)
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA
                || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE)
                { hasContent = true; break; }
        if (!hasContent) return;

        float hw = be.screenWidth * 0.5f;
        float hh = be.screenLength * 0.5f;
        var mc = Minecraft.getInstance();
        var font = mc.font;

        poseStack.pushPose();
        // Compute facing offset and base yaw adjustment so yaw=0 → block's front
        float facingYDeg = 0;
        if (be.getBlockState().hasProperty(io.github.y15173334444.create_schematic_compute.blocks.MonitorBlock.FACING)) {
            facingYDeg = be.getBlockState().getValue(io.github.y15173334444.create_schematic_compute.blocks.MonitorBlock.FACING).toYRot();
            float rad = (float)Math.toRadians(facingYDeg);
            float c = (float)Math.cos(rad), s = (float)Math.sin(rad);
            float tx = be.screenX * c - be.screenZ * s;
            float tz = be.screenX * s + be.screenZ * c;
            poseStack.translate(0.5 + tx, be.screenY, 0.5 + tz);
        } else {
            poseStack.translate(0.5 + be.screenX, be.screenY, 0.5 + be.screenZ);
        }
        // yaw: offset so yaw=0 faces the block's front (e.g. EAST facing → yaw offset = -90°)
        // pitch/roll: absolute world-space Euler angles (Y after X after Z)
        float adjYaw = be.screenYaw - facingYDeg;
        poseStack.mulPose(new Quaternionf().rotationY((float)Math.toRadians(adjYaw)));
        poseStack.mulPose(new Quaternionf().rotationX((float)Math.toRadians(be.screenPitch)));
        poseStack.mulPose(new Quaternionf().rotationZ((float)Math.toRadians(be.screenRoll)));
        var m = poseStack.last().pose();

        float l = -hw, r = hw, t = -hh, b = hh, bw = 0.04f;
        // 内容边距与编辑器共用常量，保证归一化坐标映射一致
        // Content margin shares the constant with the editor so normalized coords map identically
        float margin = GeometryConstants.BEZEL_MARGIN;
        float cx = -hw + margin, cy = hh - margin;
        float cw = be.screenWidth - 2 * margin, ch = be.screenLength - 2 * margin;

        // ── Border + IMAGE pixels use POSITION_COLOR with POSITION_COLOR_SHADER
        //     (rendertype_position_color = F3 debug shader — Iris preserves it; NO_CULL ensures all-angle visibility) ──
        var sceneBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        drawBorderFace(sceneBuf, m, l, r, t, b, bw, 1);
        drawBorderFace(sceneBuf, m, l, r, t, b, bw, -1);
        // Collect IMAGE/IMAGE_SEQUENCE nodes and sort by layerIndex (back→front)
        var imgNodes = new java.util.ArrayList<GraphNode>();
        for (var n : be.graph.nodes) {
            if (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) imgNodes.add(n);
        }
        imgNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
        for (var n : imgNodes) {
            // X/Y/rotation signal offsets (0 when not running / 未运行时为 0)
            float ox = evalAvailable ? be.graph.getInputValue(n.id, 0, snapshot.outputs()) : 0;
            float oy = evalAvailable ? be.graph.getInputValue(n.id, 1, snapshot.outputs()) : 0;
            float msX = n.params.length > 0 ? n.params[0] : 0.01f;
            float msY = n.params.length > 1 ? n.params[1] : 0.01f;
            float rotScale = n.params.length > 2 ? n.params[2] : 1f;
            boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
            boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
            float dx = ox * (invX ? -msX : msX);
            float dy = oy * (invY ? -msY : msY);
            // Select the pixel array to render — IMAGE uses imagePixels, IMAGE_SEQUENCE picks a frame
            int[] pixels = n.imagePixels;
            int rotPin = n.type == NodeType.IMAGE_SEQUENCE ? 3 : 2;
            if (n.type == NodeType.IMAGE_SEQUENCE) {
                int frameIdx = evalAvailable ? Math.round(be.graph.getInputValue(n.id, 2, snapshot.outputs())) : 0;
                if (n.imageSequenceFrames != null && !n.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, n.imageSequenceFrames.size() - 1));
                    pixels = n.imageSequenceFrames.get(frameIdx);
                }
            }
            if (pixels == null || pixels.length != n.imageWidth * n.imageHeight) continue;
            float rotInput = evalAvailable ? be.graph.getInputValue(n.id, rotPin, snapshot.outputs()) : 0;
            float effectiveRot = n.displayRotation + rotInput * rotScale;
            // Clamp the TOP-LEFT anchor so the rotated bounding box stays inside the content
            // area. layoutX/Y is the top-left corner (matching the editor's draw/drag/hit-test),
            // so the upper bound must subtract the FULL rotated AABB (2*bbHalf). The previous
            // bound subtracted only half, letting the image overhang the right/bottom edge by
            // half its own width and diverge from the editor's clamped position.
            // 左上角锚点 clamp：layoutX/Y 是图像左上角（与编辑器绘制/拖拽/命中测试一致），
            // 上界须减去完整旋转 AABB（2*bbHalf）。旧公式只减半幅，导致图像在右/下边缘
            // 伸出半个图像宽度、与编辑器内位置错位。
            float cell = 0.03f * n.displayScale;
            float halfW = (n.imageWidth * 0.5f) * cell, halfH = (n.imageHeight * 0.5f) * cell;
            float rA = (float)Math.abs(Math.cos(Math.toRadians(effectiveRot)));
            float rB = (float)Math.abs(Math.sin(Math.toRadians(effectiveRot)));
            float bbHalfW = (halfW * rA + halfH * rB) / cw;
            float bbHalfH = (halfW * rB + halfH * rA) / ch;
            float rawX = n.layoutX + dx;
            float rawY = n.layoutY + dy;
            float cpx = Math.max(0, Math.min(1 - 2 * bbHalfW, rawX));
            float cpy = Math.max(0, Math.min(1 - 2 * bbHalfH, rawY));
            float nx = cx + cpx * cw;
            float ny = cy - cpy * ch;
            poseStack.pushPose();
            poseStack.translate(nx + halfW, ny - halfH, -n.layerIndex * 0.00001f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-effectiveRot));
            poseStack.translate(-halfW, halfH, 0);
            var m2 = poseStack.last().pose();
            for (int py = 0; py < n.imageHeight; py++) {
                for (int px = 0; px < n.imageWidth; px++) {
                    int idx = py * n.imageWidth + px;
                    if (idx >= pixels.length) continue;
                    int c = pixels[idx];
                    int a = (c >> 24) & 0xFF, rr = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                    if (a == 0) continue;
                    float x0 = px * cell, x1 = x0 + cell;
                    float y0 = -py * cell, y1 = y0 - cell;
                    sceneBuf.addVertex(m2, x0, y0, 0).setColor(rr / 255f, g / 255f, bl / 255f, a / 255f);
                    sceneBuf.addVertex(m2, x1, y0, 0).setColor(rr / 255f, g / 255f, bl / 255f, a / 255f);
                    sceneBuf.addVertex(m2, x1, y1, 0).setColor(rr / 255f, g / 255f, bl / 255f, a / 255f);
                    sceneBuf.addVertex(m2, x0, y1, 0).setColor(rr / 255f, g / 255f, bl / 255f, a / 255f);
                }
            }
            poseStack.popPose();
        }

        // ── Text (uses font's own RenderType, sort by layerIndex back→front) ──
        var textNodes = new java.util.ArrayList<GraphNode>();
        for (var n : be.graph.nodes) {
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA) textNodes.add(n);
        }
        textNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
        for (var n : textNodes) {
            float nx = cx + n.layoutX * cw;
            float ny = cy - n.layoutY * ch;
            if (n.type == NodeType.DATA && !evalAvailable) continue; // need eval output / 需要评估输出
            String str = n.type == NodeType.DATA
                ? String.format("%.1f", be.graph.getInputValue(n.id, 0, snapshot.outputs()))
                : n.displayText;
            if (str.isEmpty()) continue;
            int color = n.textColor != 0 ? n.textColor : (n.type == NodeType.DATA ? 0xFF88FF88 : 0xFFCCCCCC);
            float s = GeometryConstants.FONT_BLOCK_SCALE * n.displayScale;
            poseStack.pushPose();
            float fw = font.width(str), fh = 10f;
            poseStack.translate(nx + fw * s / 2f, ny - fh * s / 2f, -n.layerIndex * 0.00001f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-n.displayRotation));
            poseStack.scale(s, -s, s);
            poseStack.translate(-fw / 2f, -fh / 2f, 0);
            font.drawInBatch(str, 0, 0, color, false,
                poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            poseStack.popPose();
        }

        // ── Flush font with NO_CULL (border + pixels are flushed by endBatch) ──
        // 3D 模式不锚定（文字直接画屏幕，深度正常）——传 null 走无锚定路径。
        flushTextNoCull(buffer, null, null, 0f);

        poseStack.popPose();
    }

    // ── HUD 模式：玻璃面板 + 内容直接绘制（Phase 1，docs/monitor-hud-mode-design.md §八） ──
    // 纯官方接口路径：PoseStack + MultiBufferSource + MonitorRenderTypes + Font.drawInBatch，
    // 与 3D 模式同一套已验证的 Sodium/Iris 兼容管线。2026-08-16 决策：弃用离屏 FBO——
    // Sodium/Veil 世界管线在 flush 时重置视口/裁剪（读回实证：内容只落进纹理一角），且
    // 采样「曾用作渲染目标的纹理」在批处理 BER 管线里不显示（劫持实验）。内容改为每帧
    // 直接生成顶点画到面板平面：无纹理采样、无裸 GL、无 20Hz 节流（与 3D 模式同开销）。
    // HUD mode: glass panel + content drawn directly (Phase 1, docs/monitor-hud-mode-design.md §8).
    // Official interfaces only — PoseStack + MultiBufferSource + MonitorRenderTypes +
    // Font.drawInBatch, the same Sodium/Iris-proven pipeline as 3D mode. Decision 2026-08-16:
    // the offscreen FBO is dropped — the Sodium/Veil world pipeline resets viewport/scissor at
    // flush time (readback proved content only landed in one corner of the texture), and
    // sampling a render-target texture doesn't display in the batched BER pipeline (hijack
    // experiment). Content vertices are now emitted per frame straight onto the panel plane:
    // no texture sampling, no raw GL, no 20Hz throttle (same cost profile as 3D mode).

    /** HUD 模式 BER 主 pass：近处屏幕（玻璃 tint + 边框）+ 远处虚像内容。
     *  虚像画布沿面板法线平移 {@link #VIRTUAL_IMAGE_D} 格、尺寸 ×D——保持角尺寸，
     *  玩家看屏幕时内容恒定大小浮在远处（HUD 无限远聚焦）。画布在世界固定位置
     *  （poseStack 局部坐标系，Sable 结构上随结构）→ 天然共形（贴世界）且不依赖
     *  玩家相机投影。显示区域只做裁剪（Liang-Barsky）与遮挡（深度 + layerIndex）。
     *  HUD-mode BER main pass: near screen (glass tint + border) + far virtual-image
     *  content. The virtual-image canvas is pushed VIRTUAL_IMAGE_D blocks along the
     *  panel normal with size ×D — the angular size is preserved, so content floats
     *  at a constant size far away (HUD infinite-focus). The canvas sits at a fixed
     *  world position (poseStack local frame; follows the structure on Sable) → natively
     *  conformal (world-anchored) with no player-camera projection. The display area
     *  only clips (Liang-Barsky) and occludes (depth + layerIndex). */
    private static final float VIRTUAL_IMAGE_D = 100f; // 虚像距离（格）/ virtual-image distance (blocks)

    /** 锚定比例 s 上界钳制（2026-08-24）：s=zAnchor/fz，掠射（视角与玻璃法线差
     *  ≥~80°）时画布边缘顶点视线深度 fz→0⁻ → s→∞ → 锚定顶点坐标溢出为 Inf/NaN
     *  → 虚像撕裂/颜色溢出（用户实测必然触发）。钳制 s 到有限上界：顶点坐标有界
     *  （float 精确）、屏幕 NDC 落在视锥外 → GPU 视锥裁剪干净切掉，fz 正常时
     *  （|s|≈0.03）完全不受影响。**不用** 8-22 的 clipPolyToHalfPlane 几何裁剪：
     *  画布巨大（×D=200×120 格），正常斜视（30-50°）时画布边缘 fz 也接近 0 →
     *  会把正常可见内容误切掉（"显示不全"，8-23 实测删除、8-24 复现误切）。
     *  Anchor-ratio s upper clamp: s=zAnchor/fz; at grazing view (≥~80°) canvas-edge
     *  vertices go fz→0⁻ → s→∞ → anchored vertex coords overflow to Inf/NaN → the
     *  image tears/bleeds (user-measured, guaranteed). Clamping s to a finite bound
     *  keeps vertex coords finite (float-exact) with NDC outside the frustum → the
     *  GPU frustum clip cuts them cleanly; normal |s|≈0.03 is untouched. The 8-22
     *  clipPolyToHalfPlane geometric clip is NOT used: the canvas is huge (×D,
     *  200×120 blocks), so at ordinary oblique views (30-50°) canvas-edge fz also
     *  nears 0 → it wrongly cuts visible content ("incomplete display", removed
     *  8-23 after measurement, reproduced 8-24). */
    static final float MAX_ANCHOR_S = 1e4f;

    /** 深度锚定诊断日志只打一次（每帧刷屏无意义）/ depth-anchor debug log: once only */
    private static boolean HUD_DEPTH_DEBUG_LOGGED = false;

    /** HUD 虚像画布共享字节缓冲（static 复用，2026-08-21 修复 OOM：每帧 new 1MB
     *  ByteBufferBuilder 是堆外内存泄漏）。渲染线程串行，build() 快照后 clear() 重填。
     *  Reused HUD canvas byte buffer (2026-08-21 OOM fix). Render thread is serial;
     *  build() snapshots the data, then clear() refills per frame. */
    private static ByteBufferBuilder HUD_CANVAS_BYTES;

    /** HUD 文字字形共享字节缓冲（2026-08-24 手动字形锚定：文字不走 Iris 包装的
     *  MultiBufferSource，改为手动生成字形顶点 + 锚定到玻璃深度，独立字节缓冲）。
     *  Reused HUD text-glyph byte buffer (manual-glyph anchoring; independent of the
     *  Iris-wrapped MultiBufferSource). */
    private static ByteBufferBuilder HUD_TEXT_BYTES;

    /** 每帧首个字形的 RenderType（2026-08-24：用于 drawTextAnchored 冲刷——绑定
     *  正确字体 atlas；同 FontSet 所有字形共享）。跨帧缓存，资源重载后自动更新。
     *  First glyph's RenderType of the frame (used by drawTextAnchored — binds the
     *  correct font atlas; shared by all glyphs of the same FontSet). Cached across
     *  frames; refreshed after a resource reload. */
    private static RenderType hudTextRenderType;

    private void renderHud(MonitorBlockEntity be, PoseStack poseStack, MultiBufferSource buffer) {
        float hw = be.panelSizeX * 0.5f, hh = be.panelSizeY * 0.5f;
        var mc = Minecraft.getInstance();
        var font = mc.font;
        var snapshot = be.cachedEvalSnapshot;
        boolean evalAvailable = be.running && snapshot != null;

        poseStack.pushPose();
        // 1. 方块中心 + 面板偏移（面板偏移在方块局部帧，随 FACING 旋转）
        poseStack.translate(0.5 + be.panelOffsetX, 0.5 + be.panelOffsetY, 0.5);
        // 2. FACING 旋转（yaw=0 → 方块正面）
        if (be.getBlockState().hasProperty(MonitorBlock.FACING)) {
            poseStack.mulPose(Axis.YP.rotationDegrees(-be.getBlockState().getValue(MonitorBlock.FACING).toYRot()));
        }
        // 3. 面板与虚像布局：MonitorBlock 放置时 FACING = 玩家面朝方向的**反方向**
        //    （getStateForPlacement 用 getOpposite()），面板在 -FACING（玩家侧，玻璃
        //    正面朝玩家）。虚像内容**直接绘制在玻璃平面**（面板局部 z=0，深度锚定）：
        //    屏幕投影 = 玻璃投影（显示区域）、几何深度 = 玻璃深度（前方遮挡/后方不遮
        //    挡）——2026-08-20 用户讲解的模板缓冲+深度锚定的几何等效实现（详见下方
        //    内容绘制注释）。
        //    Panel and virtual-image layout: MonitorBlock places FACING OPPOSITE to the
        //    placing player's facing (getStateForPlacement uses getOpposite()), so the
        //    panel sits on the -FACING (player) side (the glass front faces the player).
        //    The virtual-image content is drawn DIRECTLY on the glass plane (panel-local
        //    z=0, depth-anchored): screen projection = glass projection (display region),
        //    geometric depth = glass depth (near occludes / far does not) — the geometric
        //    equivalent of the stencil+depth-anchor mechanisms the user explained on
        //    2026-08-20 (see the content-drawing comment below).
        poseStack.translate(0, 0, -(0.5f + be.panelDistance));
        var m = poseStack.last().pose();
        // 相机旋转（世界 → 相机空间）：BER 的 poseStack 只含相机平移（LevelRenderer
        // 把相机旋转乘进 RenderSystem 全局 modelView，不进入 BER poseStack），因此
        // poseStack 变换后顶点的 z 分量是**世界 Z 分量**而非视线深度。深度锚定的
        // 透视除法 s=zAnchor/fz 必须用真正的视线深度：玩家面朝东西（视线沿 X 轴）时
        // 玻璃/画布的世界 Z 分量 ≈ 玩家 Z → fz≈0 → s 爆炸/翻转 → 虚像溢出
        // （2026-08-24 修复，FacingOverflowDiagTest 数值实证：EAST s=0.9/-0.018、
        // WEST s=-Infinity，NORTH/SOUTH 正常）。
        // Camera rotation (world → camera space): the BER poseStack carries only the
        // camera translation (LevelRenderer multiplies the camera rotation into the
        // RenderSystem modelView stack, never into the BER poseStack), so a vertex's
        // z after the poseStack is a **world-Z component**, not the view depth. The
        // depth-anchor perspective division s=zAnchor/fz needs the true view depth:
        // facing EAST/WEST (view along X), the glass/canvas world-Z ≈ the player Z →
        // fz≈0 → s blows up/flips → the virtual image overflows (2026-08-24 fix;
        // FacingOverflowDiagTest numbers: EAST s=0.9/-0.018, WEST s=-Infinity,
        // NORTH/SOUTH fine).
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Quaternionf camRotInv = cam.rotation().conjugate(new Quaternionf());
        org.joml.Matrix4f viewRot = new org.joml.Matrix4f().rotation(camRotInv);  // 世界→相机空间
        org.joml.Matrix4f viewRotInv = new org.joml.Matrix4f(viewRot).invert();   // 相机空间→世界
        // 玻璃面板中心 → 相机空间深度 gz（顶点级深度锚定目标，2026-08-21 几何等效；
        // 2026-08-24：先经 poseStack 到世界（相机相对），再经相机旋转到视线深度）。
        // Glass panel center → camera-space depth gz (the vertex-level depth-anchor
        // target, 2026-08-21 geometric equivalent; 2026-08-24: poseStack → world
        // (camera-relative), then camera rotation → true view depth).
        var glassView = new org.joml.Vector3f();
        m.transformPosition(glassView);
        viewRot.transformPosition(glassView);
        float glassZ = glassView.z;
        // 玩家屏幕定位遮罩（4 边形）：眼睛在面板局部（相机原点经面板矩阵逆变换，
        // ez>0 = 玻璃前）；玻璃 4 角点投影到画布平面 → 内容局部 4 边形 + AABB。
        // Player-screen-positioned mask (4-gon): the eye in panel-local coords
        // (camera origin through the inverse panel matrix; ez>0 = in front of the
        // glass); the glass 4 corners project onto the canvas plane → content-local
        // 4-gon + AABB.
        var mInv = new org.joml.Matrix4f(m).invert();
        var eye = new org.joml.Vector4f(0f, 0f, 0f, 1f);
        mInv.transform(eye);
        float[] maskQuad = projectGlassCornersToCanvas(eye.x, eye.y, eye.z, hw, hh, VIRTUAL_IMAGE_D);
        float[] maskAabb = polyAabb(maskQuad);
        // 屏幕边框（画布边界可见；不再画半透明 tint 底色——双重半透明（tint + NO_DEPTH
        // 虚像）在 Sodium/Veil 透明通道排序下会遮蔽虚像，只保留边框做「屏幕」轮廓）。
        // Screen border only (the visible canvas outline; the translucent tint fill is
        // dropped — double translucency (tint + NO_DEPTH image) can hide the virtual image
        // in the Sodium/Veil translucency sort; the border alone outlines the "screen").
        var tintBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        // 边框画在玻璃平面矩形上（本就在矩形内），无需裁剪——组件级剔除已移除
        // (2026-08-21：4 边形玩家视角遮罩取代全部组件级裁剪，省 CPU)。
        // The border sits on the glass-plane rect (already inside) — no clip needed;
        // component-level culling was removed (2026-08-21: the 4-gon player-screen
        // mask replaces all component-level clipping to save CPU).
        addThickLine(tintBuf, m, -hw, -hh, hw, -hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f);
        addThickLine(tintBuf, m, hw, -hh, hw, hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f);
        addThickLine(tintBuf, m, hw, hh, -hw, hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f);
        addThickLine(tintBuf, m, -hw, hh, -hw, -hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f);

        // ── 远处虚像画布 + 顶点级深度锚定 + 4 边形遮罩（2026-08-21 最终方案）──
        // 虚像内容画在远处画布（面板局部 z=-D、×D 缩放——远处虚像「浮起」视觉）。
        // 深度锚定 = 顶点数学（emitAnchored）：顶点构造为 V'=(fx·gz/fz, fy·gz/fz,
        // gz)——屏幕位置保持远处画布投影、深度 = 玻璃平面 gz → 前方物体遮挡、后方
        // 不遮挡（官方接口，无自定义 shader——Veil 4.0 拦截自定义 ShaderInstance）。
        // 「显示区域」= 玩家屏幕定位 4 边形遮罩（projectGlassCornersToCanvas）：
        // 玻璃面板 4 角点从玩家眼睛投影到画布平面，内容与 4 边形求交（clipPolyToQuad
        // Sutherland-Hodgman）——内容只在玩家透过玻璃看到的区域内显示。组件级剔除
        // 已全部移除（2026-08-21：GPU 省不了多少、CPU 开销大）。
        // ── Far virtual-image canvas + vertex-level depth anchor + 4-gon mask
        //    (2026-08-21 final) ──
        // Content draws on the far canvas (panel-local z=-D, ×D scale — the "floating
        // far away" look). Depth anchoring = vertex math (emitAnchored): V'=(fx·gz/fz,
        // fy·gz/fz, gz) keeps the far-canvas screen projection while depth lands on
        // the glass plane gz → near occludes / far does not (official interfaces, no
        // custom shader — Veil 4.0 intercepts custom ShaderInstances).
        // "Display region" = the player-screen-positioned 4-gon mask
        // (projectGlassCornersToCanvas): the glass panel's 4 corners project from the
        // player's eye onto the canvas plane; content intersects the 4-gon
        // (clipPolyToQuad Sutherland-Hodgman) — content shows only inside the region
        // seen through the glass. Component-level culling was removed entirely
        // (2026-08-21: negligible GPU savings, heavy CPU cost).
        poseStack.pushPose();
        poseStack.translate(0, 0, -VIRTUAL_IMAGE_D);
        poseStack.scale(VIRTUAL_IMAGE_D, VIRTUAL_IMAGE_D, -VIRTUAL_IMAGE_D);
        float cx = -hw, cy = hh;
        float cw = be.panelSizeX, ch = be.panelSizeY;
        var graph = be.graph;
        // 虚像顶点收集到**独立 BufferBuilder**（TRIANGLES 模式，支撑遮罩裁剪的
        // 三角形扇）：绕开 Iris 的 FullyBufferedMultiBufferSource（其 endBatch 无法
        // 配合独立绘制），由 drawDepthAnchored 用官方 RenderType 冲刷。
        // The virtual-image vertices go into an **independent BufferBuilder**
        // (TRIANGLES mode for mask-clipped triangle fans), bypassing Iris'
        // FullyBufferedMultiBufferSource; drawDepthAnchored flushes them through the
        // official RenderType path.
        // 2026-08-21 修复：**复用** ByteBufferBuilder（堆外内存源）——此前每帧 new
        // 1MB ByteBufferBuilder 是堆外泄漏（长时间运行 OOM，日志铁证 Failed to allocate
        // 1048576 bytes at renderHud）。BufferBuilder 每帧轻量 new（无堆外），共享
        // 复用的字节缓冲（clear() 重置写入位置）；渲染线程串行，build() 快照后安全。
        // Reuse the ByteBufferBuilder (the off-heap allocation source) — a fresh 1MB
        // one per frame leaked off-heap memory (OOM after long sessions, log-proven
        // "Failed to allocate 1048576 bytes" at renderHud). The lightweight
        // BufferBuilder is newed per frame but shares the reused byte buffer
        // (clear() resets the write position); the render thread is serial, so a
        // build() snapshot is safe before the next clear.
        if (HUD_CANVAS_BYTES == null) HUD_CANVAS_BYTES = new ByteBufferBuilder(1 << 21);
        else HUD_CANVAS_BYTES.clear();
        BufferBuilder canvasBuf = new BufferBuilder(HUD_CANVAS_BYTES,
            VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        // 文字字形缓冲（2026-08-24 手动字形锚定：POSITION_COLOR_TEX_LIGHTMAP +
        // TRIANGLES——字符级部分遮罩输出三角扇（3 顶点/三角形）；QUADS 模式会按
        // 4 顶点解析导致三角扇顶点流错位（全部拉伸变形）。独立字节缓冲——文字不走
        // Iris 包装的 MultiBufferSource）
        // Text-glyph buffer (manual-glyph anchoring: PCTL + TRIANGLES — per-glyph
        // partial masking emits triangle fans (3 verts); QUADS parses 4 verts/quad
        // and misaligns the fan stream (everything stretched). Dedicated byte buffer
        // — text bypasses the Iris-wrapped MultiBufferSource)
        if (HUD_TEXT_BYTES == null) HUD_TEXT_BYTES = new ByteBufferBuilder(1 << 21);
        else HUD_TEXT_BYTES.clear();
        BufferBuilder textBuf = new BufferBuilder(HUD_TEXT_BYTES,
            VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

        // ── IMAGE/IMAGE_SEQUENCE（layerIndex 排序，同世界渲染） ──
        var imgNodes = new ArrayList<GraphNode>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) imgNodes.add(n);
        }
        imgNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
        for (var n : imgNodes) {
            // X/Y/rotation 信号偏移（未运行时为 0）
            float ox = evalAvailable ? graph.getInputValue(n.id, 0, snapshot.outputs()) : 0;
            float oy = evalAvailable ? graph.getInputValue(n.id, 1, snapshot.outputs()) : 0;
            float msX = n.params.length > 0 ? n.params[0] : 0.01f;
            float msY = n.params.length > 1 ? n.params[1] : 0.01f;
            float rotScale = n.params.length > 2 ? n.params[2] : 1f;
            boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
            boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
            float dx = ox * (invX ? -msX : msX);
            float dy = oy * (invY ? -msY : msY);
            // 选择要渲染的像素数组——IMAGE 用 imagePixels，IMAGE_SEQUENCE 取一帧
            int[] pixels = n.imagePixels;
            int rotPin = n.type == NodeType.IMAGE_SEQUENCE ? 3 : 2;
            if (n.type == NodeType.IMAGE_SEQUENCE) {
                int frameIdx = evalAvailable ? Math.round(graph.getInputValue(n.id, 2, snapshot.outputs())) : 0;
                if (n.imageSequenceFrames != null && !n.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, n.imageSequenceFrames.size() - 1));
                    pixels = n.imageSequenceFrames.get(frameIdx);
                }
            }
            if (pixels == null || pixels.length != n.imageWidth * n.imageHeight) continue;
            float rotInput = evalAvailable ? graph.getInputValue(n.id, rotPin, snapshot.outputs()) : 0;
            float effectiveRot = n.displayRotation + rotInput * rotScale;
            // 画布定位：左上角锚点 clamp（与 3D 模式一致：上界 1-2*bbHalf）；HUD 面板整幅无边框。
            // Canvas positioning: top-left anchor clamp (same as 3D mode). All components are
            // drawn on the panel canvas — no player-camera projection (Sable-friendly by design).
            float cell = 0.03f * n.displayScale;
            float halfW = (n.imageWidth * 0.5f) * cell, halfH = (n.imageHeight * 0.5f) * cell;
            float rA = (float)Math.abs(Math.cos(Math.toRadians(effectiveRot)));
            float rB = (float)Math.abs(Math.sin(Math.toRadians(effectiveRot)));
            float bbHalfW = (halfW * rA + halfH * rB) / cw;
            float bbHalfH = (halfW * rB + halfH * rA) / ch;
            float rawX = n.layoutX + dx;
            float rawY = n.layoutY + dy;
            float cpx = Math.max(0, Math.min(1 - 2 * bbHalfW, rawX));
            float cpy = Math.max(0, Math.min(1 - 2 * bbHalfH, rawY));
            float nx = cx + cpx * cw;
            float ny = cy - cpy * ch;
            poseStack.pushPose();
            poseStack.translate(nx + halfW, ny - halfH, 0.001f - n.layerIndex * 0.00001f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-effectiveRot));
            poseStack.translate(-halfW, halfH, 0);
            var m2 = poseStack.last().pose();
            // 玩家屏幕定位 4 边形遮罩（唯一显示裁剪，2026-08-21）：玻璃投影 4 边形
            // 从内容局部变换到图像局部，逐像素与像素 quad 求交——全内直画、全外
            // 跳过、相交则 Sutherland-Hodgman 裁剪 + 三角形扇。其他组件级剔除
            // （画布矩形裁剪等）已移除——GPU 省不了多少、CPU 开销大。
            // Player-screen 4-gon mask (the only display clip, 2026-08-21): the
            // glass-projection 4-gon transformed into image-local coords, per-pixel
            // intersection with the pixel quad — fully-inside draws, outside skips,
            // partial clips (Sutherland-Hodgman) + triangle fan. All other
            // component-level culling (canvas-rect clips) was removed — negligible
            // GPU savings, heavy CPU cost.
            float imgCx = nx + halfW, imgCy = ny - halfH;
            // 4 边形遮罩 → 图像局部坐标（裁剪在图像局部进行，发射路径不变）
            // Transform the 4-gon mask into image-local coords (clip in image-local
            // space so the emission path stays unchanged).
            float cosR = (float)Math.cos(Math.toRadians(effectiveRot));
            float sinR = (float)Math.sin(Math.toRadians(effectiveRot));
            float[] maskImg = new float[8];
            for (int k = 0; k < 4; k++) {
                float px = maskQuad[k * 2] - imgCx, py = maskQuad[k * 2 + 1] - imgCy;
                maskImg[k * 2]     = px * cosR + py * sinR + halfW;
                maskImg[k * 2 + 1] = -px * sinR + py * cosR - halfH;
            }
            float[] maskImgAabb = polyAabb(maskImg);
            for (int py = 0; py < n.imageHeight; py++) {
                for (int px = 0; px < n.imageWidth; px++) {
                    int idx = py * n.imageWidth + px;
                    if (idx >= pixels.length) continue;
                    int c = pixels[idx];
                    int a = (c >> 24) & 0xFF, rr = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, bl = c & 0xFF;
                    if (a == 0) continue;
                    float x0 = px * cell, x1 = x0 + cell;
                    float y0 = -py * cell, y1 = y0 - cell;
                    // 4 边形遮罩：像素 quad 与玻璃投影 4 边形求交——全内直画、
                    // 全外跳过、相交则 Sutherland-Hodgman 裁剪 + 三角形扇。
                    // （2026-08-24：不再做相机平面几何裁剪——画布巨大（×D），正常
                    // 斜视时画布边缘 fz 也接近 0，几何裁剪会误切正常可见内容；掠射
                    // 溢出由 emitAnchored 的 s 钳制防 float 溢出，GPU 视锥干净裁剪。）
                    // 4-gon mask: intersect the pixel quad with the glass-projection
                    // quad — fully-inside draws directly, fully-outside skips,
                    // partial clips (Sutherland-Hodgman) + triangle fan. (No camera-
                    // plane geometric clip since 2026-08-24: the canvas is huge (×D),
                    // so ordinary oblique views put canvas-edge fz near 0 and a
                    // geometric clip wrongly cuts visible content; grazing overflow
                    // is handled by the s clamp in emitAnchored + the GPU frustum.)
                    if (x1 <= maskImgAabb[0] || x0 >= maskImgAabb[2]
                        || y1 <= maskImgAabb[1] || y0 >= maskImgAabb[3]) continue; // 全外
                    boolean allIn = pointInConvexQuad(x0, y0, maskImg)
                        && pointInConvexQuad(x1, y0, maskImg)
                        && pointInConvexQuad(x1, y1, maskImg)
                        && pointInConvexQuad(x0, y1, maskImg);
                    float rf = rr / 255f, gf = g / 255f, bf = bl / 255f, af = a / 255f;
                    if (allIn) {
                        // 顶点级深度锚定：屏幕位置保持远处画布投影、深度 = 玻璃平面 gz
                        // (Vertex-level depth anchor: far-canvas projection, glass depth)
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x0, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x1, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x1, y1, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x0, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x1, y1, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, viewRot, viewRotInv, x0, y1, 0f, glassZ, rf, gf, bf, af);
                    } else {
                        float[] clipped = clipPolyToQuad(
                            new float[]{x0, y0, x1, y0, x1, y1, x0, y1}, maskImg);
                        int nv = clipped.length / 2;
                        if (nv < 3) continue;
                        for (int i = 1; i < nv - 1; i++) {
                            emitAnchored(canvasBuf, m2, viewRot, viewRotInv, clipped[0], clipped[1], 0f, glassZ, rf, gf, bf, af);
                            emitAnchored(canvasBuf, m2, viewRot, viewRotInv, clipped[i * 2], clipped[i * 2 + 1], 0f, glassZ, rf, gf, bf, af);
                            emitAnchored(canvasBuf, m2, viewRot, viewRotInv, clipped[(i + 1) * 2], clipped[(i + 1) * 2 + 1], 0f, glassZ, rf, gf, bf, af);
                        }
                    }
                }
            }
            poseStack.popPose();
        }

        // ── TEXT/DATA（layerIndex 排序，同世界渲染；y-up 无需镜像缩放） ──
        var textNodes = new ArrayList<GraphNode>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA) textNodes.add(n);
        }
        textNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
        for (var n : textNodes) {
            if (n.type == NodeType.DATA && !evalAvailable) continue; // 需评估输出
            String str = n.type == NodeType.DATA
                ? String.format("%.1f", graph.getInputValue(n.id, 0, snapshot.outputs()))
                : n.displayText;
            if (str.isEmpty()) continue;
            int color = n.textColor != 0 ? n.textColor : (n.type == NodeType.DATA ? 0xFF88FF88 : 0xFFCCCCCC);
            float s = GeometryConstants.FONT_BLOCK_SCALE * n.displayScale;
            // 画布定位：layout 左上角（所有组件贴画布，无玩家相机投影）
            // Canvas positioning: layout top-left (all components on the panel canvas)
            float nx = cx + n.layoutX * cw;
            float ny = cy - n.layoutY * ch;
            float fw = font.width(str), fh = 10f;
            float rad = (float)Math.toRadians(-n.displayRotation);
            float cosR = (float)Math.cos(rad), sinR = (float)Math.sin(rad);
            float cxr = nx + fw * s / 2f, cyr = ny - fh * s / 2f; // 字符串中心（内容局部）
            // 字符级 4 边形遮罩（AABB 快检）：逐字符旋转后 AABB 与遮罩 AABB 求交，
            // 完全在玩家透过玻璃可见区域外的字符跳过（「hud 只在玻璃上显示」对文字
            // 同样生效）。字符级 AABB 近似——遮罩边界处字符整画（可接受）。
            // Per-glyph 4-gon mask (AABB quick reject): each glyph's rotated AABB vs
            // the mask AABB — glyphs fully outside the region seen through the glass
            // are skipped (the "HUD shows only on the glass" rule applies to text
            // too). Glyph-level AABB approximation — boundary glyphs draw whole.
            float adv = 0f;
            var fontSet = hudFontSet(font);
            var canvasM = poseStack.last().pose();
            for (int ci = 0; ci < str.length(); ci++) {
                float cwCh = font.width(String.valueOf(str.charAt(ci)));
                float gx0 = nx + adv * s, gy0 = ny;
                float gx1 = gx0 + cwCh * s, gy1 = gy0 + fh * s;
                adv += cwCh;
                float[] ga = rotatedAabb(gx0, gy0, gx1, gy1, cxr, cyr, cosR, sinR);
                if (ga[2] <= maskAabb[0] || ga[0] >= maskAabb[2]
                    || ga[3] <= maskAabb[1] || ga[1] >= maskAabb[3]) continue; // 字符在遮罩外
                // 手动字形 + 锚定（2026-08-24 方案 X）：charMat 复刻原 drawInBatch 的
                // 字符变换链（T(cxr,cyr)·R(-rot)·S(s,-s,s)·T(-fw/2+adv-cwCh,-fh/2)），
                // 字形 4 角经它 → 画布矩阵 → 锚定到玻璃深度 → textBuf。
                // Manual glyph + anchor (plan X): charMat replicates the original
                // drawInBatch character chain; glyph corners → canvas matrix →
                // anchored to the glass depth → textBuf.
                var charMat = new org.joml.Matrix4f()
                    .translate(cxr, cyr, 0f)
                    .rotateZ((float) Math.toRadians(-n.displayRotation))
                    .scale(s, -s, s)
                    .translate(-fw / 2f + adv - cwCh, -fh / 2f, 0f);
                emitTextGlyph(textBuf, fontSet, str.charAt(ci), canvasM, viewRot, viewRotInv,
                    glassZ, charMat, maskQuad, color);
            }
        }

        // ── HUD_PITCH_LADDER（画布姿态仪：tan 透视刻度 + pitch 平移地平线 + roll 旋转，Phase 2） ──
        if (evalAvailable) {
            var hudNodes = new ArrayList<GraphNode>();
            for (var n : graph.nodes) {
                if (n.type == NodeType.HUD_PITCH_LADDER) hudNodes.add(n);
            }
            hudNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
            for (var n : hudNodes) {
                drawPitchLadder(n, be, poseStack, canvasBuf, textBuf, hw, hh, glassZ, viewRot, viewRotInv,
                    maskQuad, maskAabb, font, snapshot.outputs());
            }
        }
        poseStack.popPose(); // 远处画布（×D）结束 / end of the far canvas (×D)

        // 深度锚定冲刷：绑定 hud_depth_anchor 着色器、写入 GlassNdcZ（玻璃面板中心
        // 的 NDC 深度）、绘制独立 buffer 中的虚像顶点——屏幕位置在远处画布、深度在
        // 玻璃平面（前方遮挡/后方不遮挡）。文字（2026-08-24 方案 X）手动字形锚定到
        // 玻璃深度后由 drawTextAnchored 冲刷（官方文字 RenderType + 不写深度）。
        // Depth-anchored flush: bind hud_depth_anchor, write GlassNdcZ (the glass
        // panel center's NDC depth), draw the virtual-image vertices from the
        // independent buffer — screen position on the far canvas, depth on the glass
        // plane (near occludes / far does not). Text (plan X) is manually glyph-
        // anchored to the glass depth and flushed by drawTextAnchored (official text
        // RenderType, no depth write).
        drawDepthAnchored(canvasBuf);
        drawTextAnchored(textBuf);
        poseStack.popPose(); // 面板 / panel
    }

    // ── 画布姿态仪（俯仰梯）：tan 透视刻度 + pitch 平移 + roll 旋转（纯函数，供单元测试） ──
    // Canvas attitude indicator (pitch ladder): tan-perspective ticks + pitch shift +
    // roll rotation (pure functions, unit-testable). No player-camera projection —
    // everything is drawn on the panel canvas, so Sable structures work natively.

    /** 画布刻度纵向尺度：1 弧度 ≈ 此比例 × 画布半高（tan 透视，近地平线密、远处疏）。
     *  Canvas tick scale: 1 radian ≈ this ratio × panel half-height (tan perspective). */
    private static final float LADDER_CANVAS_SCALE = 0.9f;

    /** 刻度角度（度）→ 画布 y 偏移（面板局部 y-up，相对画布中心）。
     *  y = -K·tan(pitch−θ)：θ 是刻度代表的世界俯仰角（+ 在上方），pitch 抬头时
     *  地平线（θ=0）下移、+10° 刻度在中心上方（2026-08-24 修复：原为 pitch+θ
     *  「同向叠加」，导致平飞时 +10° 刻度跑到中心下方、上方显示负角——不符合
     *  HUD 惯例「向上为正」）。
     *  Pure function on doubles.
     *  Tick angle (deg) → canvas y offset (panel-local y-up, relative to canvas
     *  center). y = -K·tan(pitch−θ): θ is the world pitch this tick labels (+ above);
     *  nose-up pitch moves the horizon (θ=0) down, and the +10° tick sits above the
     *  center (2026-08-24 fix: the old pitch+θ "same-direction sum" put the +10° tick
     *  below center at level flight — negative angles above — against the HUD
     *  convention "up is positive"). */
    public static double ladderCanvasY(double pitchDeg, double thetaDeg, double halfH) {
        return -LADDER_CANVAS_SCALE * halfH * Math.tan(Math.toRadians(pitchDeg - thetaDeg));
    }

    /** 姿态仪画布（2026-08-24 全新样式，细线 + 不透明 alpha=1——光影/Iris/Veil 会剔除
     *  半透明元素）：① 白色四段中空十字准星（固定画布中心，不随姿态移动/旋转）；
     *  ② 白色水平线（地平线，随 pitch 上下移动，贯穿画布宽）；③ SC 式绿色俯仰横滚
     *  指示条——两段式中空档线（中心留空避让准星，各 40% 画布宽），随 pitch 上下
     *  （tan 透视 ladderCanvasY）、随 roll 整组绕画布中心旋转；④ 度数标注（±10° 起
     *  每 10°，白色小字，档线两端外侧，随档组旋转）。全部经玩家屏幕 4 边形遮罩
     *  裁剪（addThickLineAnchored / 标注字符级 AABB 快检）。
     *  Canvas attitude indicator (2026-08-24 new style, thin + opaque alpha=1 — shaders/
     *  Iris/Veil cull translucent elements): ① white 4-segment hollow cross boresight
     *  (fixed at canvas center, never moves/rotates with attitude); ② white horizon
     *  line (moves with pitch, spans the canvas width); ③ SC-style green pitch/roll
     *  indicator bars — two segments with a center gap (clearing the boresight, 40%
     *  canvas width each), moving with pitch (tan perspective ladderCanvasY) and
     *  rotating about the canvas center with roll; ④ degree labels (±10° step 10°,
     *  white small text at the outer bar ends, rotating with the group). All clipped
     *  by the player-screen 4-gon mask (addThickLineAnchored / glyph AABB quick reject). */
    private void drawPitchLadder(GraphNode n, MonitorBlockEntity be, PoseStack poseStack,
            BufferBuilder buf, BufferBuilder textBuf, float hw, float hh, float glassZ,
            org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv,
            float[] maskQuad, float[] maskAabb, Font font,
            java.util.Map<Integer, float[]> outputs) {
        float targetPitch = be.graph.getInputValue(n.id, 0, outputs);
        float targetRoll = be.graph.getInputValue(n.id, 1, outputs);
        // 姿态平滑：20Hz 数据 → 60fps 指数插值（真实 HUD 姿态仪连续平滑，不跳变）。
        // Attitude smoothing: 20Hz data → 60fps exponential interpolation.
        float pitch, roll;
        if (Float.isNaN(be.smoothPitch) || Float.isNaN(be.smoothRoll)) {
            be.smoothPitch = targetPitch; be.smoothRoll = targetRoll;
            pitch = targetPitch; roll = targetRoll;
        } else {
            be.smoothPitch += (targetPitch - be.smoothPitch) * 0.25f;
            be.smoothRoll += (targetRoll - be.smoothRoll) * 0.25f;
            pitch = be.smoothPitch; roll = be.smoothRoll;
        }
        float range = n.params.length > 0 ? Math.max(1f, Math.min(180f, n.params[0])) : 90f;
        float interval = n.params.length > 1 ? Math.max(1f, n.params[1]) : 5f;
        var m = poseStack.last().pose();
        float z = 0.001f - n.layerIndex * 0.00001f;
        double rad = Math.toRadians(roll);
        double cr = Math.cos(rad), sr = Math.sin(rad);
        // 样式常量（2026-08-24：细线 + 不透明）/ style constants (thin + opaque)
        double halfLineW = hw * 0.4;   // 档线半长（两段式，各 40% 画布宽）
        double gap = hw * 0.12;        // 中心留空半宽（避让准星/地平线）
        double horizonW = hw * 0.9;    // 白色地平线贯穿宽
        float wBar = 0.008f;           // 档线/地平线宽（细线主元素）
        float wFine = 0.006f;          // 准星宽（更细）
        // 画布矩形（虚像屏幕边框，内容坐标 ±hw×±hh，BL→BR→TR→TL）——档线/地平线/
        // 准星只在该矩形内显示，超出屏幕边框的部分裁剪掉（贴脸时 mask > 画布）。
        // Canvas rect (the virtual-screen frame, content-local ±hw×±hh, BL→BR→TR→TL)
        // — bars/horizon/boresight show only inside it (close-up mask > canvas).
        float[] canvasRect = {-hw, -hh, hw, -hh, hw, hh, -hw, hh};
        // ① 白色四段中空十字准星（固定中心，不随姿态；中空半径 bsGap、段长 bsLen；
        //    2026-08-24 用户要求缩小到 1/3）
        // ① white 4-segment hollow cross boresight (fixed; hollow radius, segment
        // length; 2026-08-24 shrunk to 1/3 per user request)
        float bsGap = hw * 0.04f / 3f, bsLen = hw * 0.12f / 3f;
        addThickLineAnchored(buf, m, -bsLen, 0, -bsGap, 0, wFine, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 1f, 1f, 1f, 1f);
        addThickLineAnchored(buf, m, bsGap, 0, bsLen, 0, wFine, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 1f, 1f, 1f, 1f);
        addThickLineAnchored(buf, m, 0, bsGap, 0, bsLen, wFine, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 1f, 1f, 1f, 1f);
        addThickLineAnchored(buf, m, 0, -bsLen, 0, -bsGap, wFine, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 1f, 1f, 1f, 1f);
        // ②③ 档族 + 白色地平线（tan 透视，绕画布中心旋转，经 4 边形遮罩裁剪）
        // ②③ bar family + white horizon (tan perspective, rotated about the canvas
        // center, clipped by the 4-gon mask) — bars outside the glass view are cut.
        for (float theta = -range; theta <= range + 1e-4f; theta += interval) {
            double y = ladderCanvasY(pitch, theta, hh);
            boolean horizon = Math.abs(theta) < interval * 0.5f;
            if (horizon) {
                // ② 白色水平线（地平线，随 pitch 移动，贯穿画布宽，不透明）
                addThickLineAnchored(buf, m, -horizonW * cr - y * sr, -horizonW * sr + y * cr,
                    horizonW * cr - y * sr, horizonW * sr + y * cr, wBar, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 1f, 1f, 1f, 1f);
            } else {
                // ③ 绿色两段式中空档（SC 绿，不透明）
                double lx0 = -halfLineW * cr - y * sr, ly0 = -halfLineW * sr + y * cr;
                double lx1 = -gap * cr - y * sr, ly1 = -gap * sr + y * cr;
                double rx0 = gap * cr - y * sr, ry0 = gap * sr + y * cr;
                double rx1 = halfLineW * cr - y * sr, ry1 = halfLineW * sr + y * cr;
                addThickLineAnchored(buf, m, lx0, ly0, lx1, ly1, wBar, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 0.2f, 1f, 0.4f, 1f);
                addThickLineAnchored(buf, m, rx0, ry0, rx1, ry1, wBar, z, glassZ, viewRot, viewRotInv, canvasRect, maskQuad, 0.2f, 1f, 0.4f, 1f);
            }
            // ④ 度数标注：±10° 起每 10°，白色小字，档线两端外侧，随档组旋转
            // ④ degree labels: ±10° onward every 10°, white small text at the outer
            // bar ends, rotating with the group (0° horizon not labelled).
            if (!horizon && Math.abs(theta) > 9.99f && Math.abs(theta % 10f) < interval * 0.5f) {
                drawLadderLabel(textBuf, font, m, viewRot, viewRotInv, glassZ,
                    Math.round(theta), y, cr, sr, halfLineW, maskAabb, canvasRect, maskQuad);
            }
        }
    }

    /** 档线度数标注（2026-08-24 方案 X）：白色小字，**手动字形 + 锚定**到玻璃深度
     *  （绕开 Iris 包装的 MultiBufferSource），档线两端外侧，随档组绕画布中心旋转；
     *  字符级 AABB 快检先 vs 画布矩形（屏幕边框）再 vs 玩家屏幕遮罩（玻璃外不显示）。
     *  Bar degree label (plan X): white small text, **manual glyph + anchored** to the
     *  glass depth (bypasses the Iris-wrapped MultiBufferSource), at both outer bar
     *  ends rotating with the group; glyph AABB quick reject vs the canvas rect then
     *  the player-screen 4-gon mask (hidden outside the glass). */
    private void drawLadderLabel(BufferBuilder textBuf, Font font,
            org.joml.Matrix4f m, org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv,
            float glassZ, int deg, double y, double cr, double sr, double halfLineW,
            float[] maskAabb, float[] canvasRect, float[] maskQuad) {
        String label = Integer.toString(deg);
        float s = GeometryConstants.FONT_BLOCK_SCALE * 0.6f; // 小字 / small text
        float fw = font.width(label), fh = 10f;
        float off = (float)(halfLineW + 0.045); // 档线端点外侧偏移
        // 两端外侧位置（绕画布中心随 roll 旋转）/ outer ends, rotated by roll
        float lx = (float)(-off * cr - y * sr), ly = (float)(-off * sr + y * cr);
        float rx = (float)(off * cr - y * sr), ry = (float)(off * sr + y * cr);
        float cosR = (float)cr, sinR = (float)sr; // 文字随档组旋转
        float rollRad = (float) Math.atan2(sr, cr); // 档组旋转角（弧度）
        float[] ca = polyAabb(canvasRect); // 画布矩形（虚像屏幕边框）AABB
        for (int side = 0; side < 2; side++) {
            float x = side == 0 ? lx : rx;
            float yp = side == 0 ? ly : ry;
            // 字符级 AABB 快检：先 vs 画布矩形（屏幕边框，2026-08-24），再 vs 遮罩
            // glyph AABB quick reject: first vs the canvas rect (screen frame),
            // then vs the player-screen mask
            float[] ga = rotatedAabb(x - fw * s * 0.5f, yp - fh * s * 0.5f,
                x + fw * s * 0.5f, yp + fh * s * 0.5f, x, yp, cosR, sinR);
            if (ga[2] <= ca[0] || ga[0] >= ca[2]
                || ga[3] <= ca[1] || ga[1] >= ca[3]) continue; // 画布外
            if (ga[2] <= maskAabb[0] || ga[0] >= maskAabb[2]
                || ga[3] <= maskAabb[1] || ga[1] >= maskAabb[3]) continue; // 遮罩外
            // 逐字符手动字形 + 锚定（charMat 复刻原标注 drawInBatch 变换链，绕标注
            // 中心随档组旋转）
            var fontSet = hudFontSet(font);
            float advL = 0f;
            for (int ci = 0; ci < label.length(); ci++) {
                float cwCh = font.width(String.valueOf(label.charAt(ci)));
                advL += cwCh;
                var charMat = new org.joml.Matrix4f()
                    .translate(x, yp, 0f)
                    .rotateZ(rollRad)
                    .scale(s, -s, s)
                    .translate(-fw * 0.5f + advL - cwCh, -fh * 0.5f, 0f);
                emitTextGlyph(textBuf, fontSet, label.charAt(ci), m, viewRot, viewRotInv,
                    glassZ, charMat, maskQuad, 0xFFFFFFFF);
            }
        }
    }

    /** 细线 quad（画布局部坐标，深度锚定 + 4 边形遮罩 + 画布矩形裁剪）：把线段
     *  (x0,y0)-(x1,y1) 画成 w 宽矩形。顶点经画布矩阵 m 到相机空间后深度锚定到
     *  玻璃平面；先与**画布矩形** canvasRect（内容坐标 ±hw×±hh，虚像屏幕边框）
     *  求交（2026-08-24：贴脸时 mask > 画布，档线 pitch 大时会画到画布外——
     *  超出屏幕边框仍显示；画布矩形裁剪兜底），再与玻璃投影 4 边形 maskQuad
     *  求交（全内直画 / 全外跳过 / 相交裁剪 + 三角扇）。
     *  Thin line quad (canvas-local coords, depth-anchored + 4-gon mask + canvas
     *  rect clip): the segment (x0,y0)-(x1,y1) as a w-wide rect. Vertices
     *  depth-anchor to the glass plane; intersected first with the **canvas rect**
     *  canvasRect (content-local ±hw×±hh, the virtual-screen frame; 2026-08-24:
     *  close-up the mask exceeds the canvas, so bars at large pitch draw past the
     *  canvas edge — the rect clip caps them), then with the glass-projection
     *  4-gon maskQuad (fully-inside draws / outside skips / partial clips +
     *  triangle fan). */
    private static void addThickLineAnchored(BufferBuilder buf, org.joml.Matrix4f m,
            double x0, double y0, double x1, double y1, double w, float z,
            float glassZ, org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv,
            float[] canvasRect, float[] maskQuad, float r, float g, float b, float a) {
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return;
        double nx = -dy / len * w * 0.5, ny = dx / len * w * 0.5;
        // 图层偏移（画布局部 z）→ 相机空间：画布 z 缩放 = -D → 锚定 z 加偏移保持层级
        // Layer offset (canvas-local z) → camera space: canvas z-scale = -D → the
        // anchor z keeps the layer offset for ordering.
        float zAnchor = glassZ - VIRTUAL_IMAGE_D * z;
        float q0x = (float)(x0 + nx), q0y = (float)(y0 + ny);
        float q1x = (float)(x1 + nx), q1y = (float)(y1 + ny);
        float q2x = (float)(x1 - nx), q2y = (float)(y1 - ny);
        float q3x = (float)(x0 - nx), q3y = (float)(y0 - ny);
        float[] quad = {q0x, q0y, q1x, q1y, q2x, q2y, q3x, q3y};
        // 相机平面剔除（2026-08-24 用户要求「超出虚像屏幕的指示线遮罩掉」）：
        // 掠射/侧视时档线远端 fz>0（相机后方）→ 锚定 s 负镜像 → 线显示到屏幕外；
        // 2D mask 挡不住（mask 内也有 fz>0 内容，数值实证）。阈值 0：只切相机后方
        // 内容，fz<0 正常内容不动（不误切）。**fz 必须用 transformPosition 计算**
        // （与 emitAnchored 完全同路径）——JOML 列主序下手算 m20/m21/m23 元素与
        // transformPosition 实际语义不一致（诊断实证：m20=m21=m23=0 但变换给
        // fz=-103.5），手算会算出 fz≈-0.1 的假值导致误切。
        // Camera-plane reject (clip indicator lines outside the virtual screen):
        // at grazing/side views the far bar ends go fz>0 (behind camera) → anchored
        // s mirrors → the line shows off-screen; the 2D mask cannot stop it. Cut
        // behind-camera content at threshold 0; normal fz<0 content untouched.
        // **fz must come from transformPosition** (same path as emitAnchored) —
        // hand-deriving m20/m21/m23 under JOML column-major layout disagrees with
        // transformPosition (measured: m20=m21=m23=0 yet transform gives fz=-103.5).
        var va = new org.joml.Vector3f(q0x, q0y, z); m.transformPosition(va); viewRot.transformPosition(va);
        var vb = new org.joml.Vector3f(q1x, q1y, z); m.transformPosition(vb); viewRot.transformPosition(vb);
        var vc = new org.joml.Vector3f(q2x, q2y, z); m.transformPosition(vc); viewRot.transformPosition(vc);
        var vd = new org.joml.Vector3f(q3x, q3y, z); m.transformPosition(vd); viewRot.transformPosition(vd);
        float fMin = Math.min(Math.min(va.z, vb.z), Math.min(vc.z, vd.z));
        float fMax = Math.max(Math.max(va.z, vb.z), Math.max(vc.z, vd.z));
        if (fMin > 0f) return; // 整条线在相机后方 → 不显示
        boolean wasClipped = false;
        if (fMax > 0f) {
            quad = clipPolyByDepth(quad, new float[]{va.z, vb.z, vc.z, vd.z}, 0f);
            wasClipped = true;
            if (quad.length / 2 < 3) return;
        }
        // 画布矩形裁剪（虚像屏幕边框 ±hw×±hh）：贴脸时 mask > 画布，档线 pitch 大
        // 会画到画布外仍显示——裁剪到屏幕边框内。
        // Canvas-rect clip (the virtual-screen frame ±hw×±hh): close-up the mask
        // exceeds the canvas, so bars at large pitch drew past the canvas edge;
        // clip them back inside the screen frame.
        float[] canvasClipped = clipPolyToQuad(quad, canvasRect);
        if (canvasClipped.length / 2 < 3) return;
        if (!java.util.Arrays.equals(quad, canvasClipped)) {
            quad = canvasClipped;
            wasClipped = true;
        }
        float[] qa = polyAabb(quad);
        float[] ma = polyAabb(maskQuad);
        if (qa[2] <= ma[0] || qa[0] >= ma[2] || qa[3] <= ma[1] || qa[1] >= ma[3]) return; // 全外
        boolean allIn = !wasClipped
            && pointInConvexQuad(q0x, q0y, maskQuad)
            && pointInConvexQuad(q1x, q1y, maskQuad)
            && pointInConvexQuad(q2x, q2y, maskQuad)
            && pointInConvexQuad(q3x, q3y, maskQuad);
        if (allIn) {
            emitAnchored(buf, m, viewRot, viewRotInv, q0x, q0y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, viewRot, viewRotInv, q1x, q1y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, viewRot, viewRotInv, q2x, q2y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, viewRot, viewRotInv, q0x, q0y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, viewRot, viewRotInv, q2x, q2y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, viewRot, viewRotInv, q3x, q3y, z, zAnchor, r, g, b, a);
        } else {
            float[] clipped = clipPolyToQuad(quad, maskQuad);
            int nv = clipped.length / 2;
            if (nv < 3) return;
            for (int i = 1; i < nv - 1; i++) {
                emitAnchored(buf, m, viewRot, viewRotInv, clipped[0], clipped[1], z, zAnchor, r, g, b, a);
                emitAnchored(buf, m, viewRot, viewRotInv, clipped[i * 2], clipped[i * 2 + 1], z, zAnchor, r, g, b, a);
                emitAnchored(buf, m, viewRot, viewRotInv, clipped[(i + 1) * 2], clipped[(i + 1) * 2 + 1], z, zAnchor, r, g, b, a);
            }
        }
    }

    /** 顶点级深度锚定（2026-08-21 几何等效 + 2026-08-24 相机空间修复）：
     *  画布局部点 (x,y,zLocal) 经画布矩阵 m2 到世界（相机相对）坐标，再经相机旋转
     *  viewRot 到**真正的相机空间** V_cam=(fx,fy,fz)（fz = 视线深度）——BER 的
     *  poseStack 只含相机平移，直接取 z 分量是世界 Z 分量，玩家面朝东西时 fz≈0
     *  → s 爆炸（溢出根因，见 renderHud 相机旋转注释）。构造锚定顶点
     *  V'=(fx·zAnchor/fz, fy·zAnchor/fz, zAnchor)：屏幕投影 x'/(-z') = fx/(-fz)
     *  与远处画布一致（角尺寸保持），深度 = zAnchor（玻璃平面 + 图层偏移）→ 前方
     *  遮挡/后方不遮挡；再经 viewRotInv 转回世界坐标输出（shader 侧会再乘相机
     *  旋转）。无自定义 shader、无运行时 uniform——纯官方接口。
     *  Vertex-level depth anchor (2026-08-21 geometric equivalent + 2026-08-24
     *  camera-space fix): the canvas-local point (x,y,zLocal) goes through the
     *  canvas matrix m2 to world (camera-relative) coords, then the camera rotation
     *  viewRot into **true camera space** V_cam=(fx,fy,fz) (fz = view depth) — the
     *  BER poseStack carries only the camera translation, so taking the raw z is a
     *  world-Z component; facing EAST/WEST fz≈0 → s blows up (the overflow root
     *  cause; see the camera-rotation note in renderHud). The anchored vertex
     *  V'=(fx·zAnchor/fz, fy·zAnchor/fz, zAnchor) keeps the far-canvas screen
     *  projection (x'/z' ratio) while depth lands on zAnchor (glass plane + layer
     *  offset) → near occludes / far does not; then viewRotInv maps it back to
     *  world coords for emission (the shader applies the camera rotation again).
     *  No custom shader, no runtime uniform — official interfaces only. */
    private static void emitAnchored(BufferBuilder buf, org.joml.Matrix4f m2,
            org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv,
            float x, float y, float zLocal, float zAnchor, float r, float g, float b, float a) {
        var v = new org.joml.Vector3f(x, y, zLocal);
        m2.transformPosition(v);          // 世界（相机相对）/ world (camera-relative)
        viewRot.transformPosition(v);     // 相机空间：fz = 视线深度 / camera space: fz = view depth
        float s = zAnchor / v.z;
        // 2026-08-24：钳制 s 上界——掠射时 fz→0⁻ → s→∞ → 顶点 Inf/NaN → 撕裂。
        // 钳到有限值：坐标有界（float 精确）、NDC 视锥外 → GPU 干净裁剪。
        // Clamp s: at grazing fz→0⁻ → s→∞ → Inf/NaN vertices → tearing. Finite s
        // keeps coords float-exact and NDC out of the frustum → clean GPU clip.
        if (s > MAX_ANCHOR_S) s = MAX_ANCHOR_S;
        else if (s < -MAX_ANCHOR_S) s = -MAX_ANCHOR_S;
        var out = new org.joml.Vector3f(v.x * s, v.y * s, zAnchor); // 相机空间锚定顶点
        viewRotInv.transformPosition(out); // 回世界坐标输出 / back to world for emission
        buf.addVertex(out.x, out.y, out.z).setColor(r, g, b, a);
    }

    // ── 手动字形锚定（2026-08-24 方案 X）：文字不走 Iris 包装的 MultiBufferSource，
    // 直接读 BakedGlyph 字形几何 + UV 生成 4 顶点，锚定到玻璃深度后写入独立 textBuf。
    // Manual glyph anchoring (plan X): text bypasses the Iris-wrapped MultiBufferSource;
    // BakedGlyph geometry/UV is read directly to emit 4 vertices, anchored to the glass
    // depth into a dedicated textBuf. (MC pinned to 1.21.1 — reflection is stable.)

    /** 生成单个字形的 4 个锚定顶点（QUADS，顶点顺序与 BakedGlyph.render 一致）。
     *  charMat = 复刻原 drawInBatch 的字符变换链（T(cxr,cyr)·R(-rot)·S(s,-s,s)·
     *  T(-fw/2+adv-cwCh, -fh/2)）——绕序/翻转/UV 与原路径完全一致（避免 180° 翻转）。
     *  Emit the 4 anchored vertices of one glyph (QUADS, same order as
     *  BakedGlyph.render). charMat replicates the original drawInBatch character
     *  transform chain so winding/flip/UV match the original path exactly. */
    private static void emitTextGlyph(BufferBuilder buf, net.minecraft.client.gui.font.FontSet fontSet,
            int code, org.joml.Matrix4f m, org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv,
            float glassZ, org.joml.Matrix4f charMat, float[] maskQuad, int color) {
        if (BG_LEFT == null || fontSet == null) return;
        net.minecraft.client.gui.font.glyphs.BakedGlyph glyph = fontSet.getGlyph(code);
        if (glyph == null) return;
        if (hudTextRenderType == null) hudTextRenderType = glyph.renderType(Font.DisplayMode.NORMAL);
        try {
            float left = BG_LEFT.getFloat(glyph), right = BG_RIGHT.getFloat(glyph);
            float up = BG_UP.getFloat(glyph), down = BG_DOWN.getFloat(glyph);
            float u0 = BG_U0.getFloat(glyph), u1 = BG_U1.getFloat(glyph);
            float v0 = BG_V0.getFloat(glyph), v1 = BG_V1.getFloat(glyph);
            float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f, a = ((color >> 24) & 0xFF) / 255f;
            // 字符 4 角（画布内容坐标，经 charMat）——顶点顺序 BakedGlyph.render 一致：
            // (left,up,u0,v0) (left,down,u0,v1) (right,down,u1,v1) (right,up,u1,v0)
            // Glyph 4 corners in canvas-content coords (via charMat), same winding as
            // BakedGlyph.render: (left,up,u0,v0) (left,down,u0,v1) (right,down,u1,v1)
            // (right,up,u1,v0).
            float[] cx = new float[8];
            var p0 = new org.joml.Vector3f(left, up, 0f); charMat.transformPosition(p0);
            var p1 = new org.joml.Vector3f(left, down, 0f); charMat.transformPosition(p1);
            var p2 = new org.joml.Vector3f(right, down, 0f); charMat.transformPosition(p2);
            var p3 = new org.joml.Vector3f(right, up, 0f); charMat.transformPosition(p3);
            cx[0] = p0.x; cx[1] = p0.y; cx[2] = p1.x; cx[3] = p1.y;
            cx[4] = p2.x; cx[5] = p2.y; cx[6] = p3.x; cx[7] = p3.y;
            // 2026-08-24 字符内部分遮罩：4 角全在玩家屏幕遮罩内 → 直画；跨遮罩边界
            // → Sutherland-Hodgman 裁剪 + UV 双线性插值 + 三角扇（不再是整字符二元
            // 显示/隐藏，边界字符只显示玻璃内部分）。
            // Per-glyph partial mask: fully inside → draw whole; crossing the mask
            // edge → Sutherland-Hodgman clip + bilinear UV interpolation + triangle
            // fan (boundary glyphs show only their on-glass part).
            boolean allIn = pointInConvexQuad(cx[0], cx[1], maskQuad)
                && pointInConvexQuad(cx[2], cx[3], maskQuad)
                && pointInConvexQuad(cx[4], cx[5], maskQuad)
                && pointInConvexQuad(cx[6], cx[7], maskQuad);
            float[] clipped;
            if (allIn) clipped = cx;
            else {
                clipped = clipPolyToQuad(cx, maskQuad);
                if (clipped.length / 2 < 3) return;
            }
            // UV 双线性插值（平行四边形仿射反演）：P0 角 (u0,v0)，e1 = P0→P1（v 增），
            // e3 = P0→P3（u 增）。Bilinear UV (parallelogram affine inverse): P0 corner
            // (u0,v0), e1 = P0→P1 (v grows), e3 = P0→P3 (u grows).
            float e1x = cx[2] - cx[0], e1y = cx[3] - cx[1];
            float e3x = cx[6] - cx[0], e3y = cx[7] - cx[1];
            float det = e3x * e1y - e3y * e1x;
            int nv = clipped.length / 2;
            for (int i = 1; i < nv - 1; i++) {
                float[] uvA = interpGlyphUv(clipped[0], clipped[1], cx, e1x, e1y, e3x, e3y, det, u0, u1, v0, v1);
                float[] uvB = interpGlyphUv(clipped[i * 2], clipped[i * 2 + 1], cx, e1x, e1y, e3x, e3y, det, u0, u1, v0, v1);
                float[] uvC = interpGlyphUv(clipped[i * 2 + 2], clipped[i * 2 + 3], cx, e1x, e1y, e3x, e3y, det, u0, u1, v0, v1);
                anchorTextVertex(buf, m, viewRot, viewRotInv, glassZ, clipped[0], clipped[1], uvA[0], uvA[1], r, g, b, a);
                anchorTextVertex(buf, m, viewRot, viewRotInv, glassZ, clipped[i * 2], clipped[i * 2 + 1], uvB[0], uvB[1], r, g, b, a);
                anchorTextVertex(buf, m, viewRot, viewRotInv, glassZ, clipped[i * 2 + 2], clipped[i * 2 + 3], uvC[0], uvC[1], r, g, b, a);
            }
        } catch (IllegalAccessException e) {
            SchematicCompute.LOGGER.error("Monitor BakedGlyph field read failed", e);
        }
    }

    /** UV 双线性插值：内容坐标点 (px,py) 在字符平行四边形（P0 角 + e1/e3 边）内的
     *  (s,t)，映射到 (u,v)。平行四边形仿射，双线性退化为线性可逆。
     *  Bilinear UV: (s,t) of the content point inside the glyph parallelogram
     *  (P0 corner + e1/e3 edges), mapped to (u,v). Affine → linear inverse. */
    private static float[] interpGlyphUv(float px, float py, float[] cx,
            float e1x, float e1y, float e3x, float e3y, float det,
            float u0, float u1, float v0, float v1) {
        if (Math.abs(det) < 1e-9f) return new float[]{u0, v0};
        float dx = px - cx[0], dy = py - cx[1];
        float s = (dx * e1y - dy * e1x) / det;
        float t = (e3x * dy - e3y * dx) / det;
        return new float[]{u0 + s * (u1 - u0), v0 + t * (v1 - v0)};
    }

    /** 单个字形顶点（画布内容坐标直通）：画布矩阵 m → 相机空间 → 锚定到玻璃深度
     *  → 回世界 → textBuf。One glyph vertex (canvas-content coords): canvas matrix m
     *  → camera space → anchor to glass depth → back to world → textBuf. */
    private static void anchorTextVertex(BufferBuilder buf, org.joml.Matrix4f m,
            org.joml.Matrix4f viewRot, org.joml.Matrix4f viewRotInv, float glassZ,
            float x, float y, float u, float v, float r, float g, float b, float a) {
        var p = new org.joml.Vector3f(x, y, 0f);
        m.transformPosition(p);          // 世界（相机相对）
        viewRot.transformPosition(p);    // 相机空间：fz = 视线深度
        float s = glassZ / p.z;
        if (s > MAX_ANCHOR_S) s = MAX_ANCHOR_S;
        else if (s < -MAX_ANCHOR_S) s = -MAX_ANCHOR_S;
        var out = new org.joml.Vector3f(p.x * s, p.y * s, glassZ);
        viewRotInv.transformPosition(out); // 回世界
        buf.addVertex(out.x, out.y, out.z).setColor(r, g, b, a).setUv(u, v).setLight(0xF000F0);
    }

    /** 冲刷锚定文字（2026-08-24）：用**字形自身的 RenderType**（BakedGlyph.renderType
     *  绑定正确字体 atlas；RenderType.text() 在部分环境下纹理绑定不可靠 → 紫黑块），
     *  depthMask(false)（不写深度，同虚像内容语义——玻璃深度写入会遮挡后续渲染）。
     *  Flush the anchored text: use the **glyph's own RenderType** (BakedGlyph.
     *  renderType binds the correct font atlas; RenderType.text() texture binding is
     *  unreliable in some environments → purple-black blocks), depthMask(false) (no
     *  depth write, same as the virtual image — a glass-depth write would occlude
     *  everything rendered afterwards). */
    private void drawTextAnchored(BufferBuilder buf) {
        var data = buf.build();
        if (data == null) return;
        try {
            RenderType rt = hudTextRenderType;
            if (rt == null) rt = RenderType.text(net.minecraft.client.Minecraft.DEFAULT_FONT);
            rt.setupRenderState();
            // 强制不剔除背面：字形 RenderType 可能启用 cull 而锚定后顶点绕序与
            // 原绘制路径相反 → 正面被剔除（"正面不显示、背面显示"）。
            // Force no cull: the glyph RenderType may cull, and the anchored vertex
            // winding differs from the original draw path → front face got culled.
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            BufferUploader.drawWithShader(data);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            rt.clearRenderState();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Monitor depth-anchored text draw failed", e);
        }
    }

    // ── 玩家屏幕定位遮罩（4 边形）：玻璃面板 4 角点从玩家眼睛投影到远处画布平面 ──
    // Player-screen-positioned mask (4-gon): the glass panel's 4 corners projected
    // from the player's eye onto the far canvas plane.

    /** 玻璃面板 4 角点 → 远处画布平面的透视投影（4 边形遮罩，内容局部坐标）。
     *  玩家眼睛在面板局部 (ex,ey,ez)（ez>0：玩家在玻璃前）。每个玻璃角点
     *  (gx,gy,0) 沿视线投影到画布平面（面板局部 z=-canvasD）：t=(canvasD+ez)/ez，
     *  交点内容局部坐标 = 面板局部/D。返回 {x0,y0,x1,y1,x2,y2,x3,y3}（角点顺序
     *  BL→BR→TR→TL）。纯函数——可单测。
     *  Glass panel 4 corners → perspective projection onto the far canvas plane
     *  (the 4-gon display mask, content-local coords). The player eye sits at
     *  panel-local (ex,ey,ez) (ez>0: in front of the glass). Each glass corner
     *  (gx,gy,0) is projected along its view ray onto the canvas plane
     *  (panel-local z=-canvasD): t=(canvasD+ez)/ez, hit = panel-local/D in
     *  content-local coords. Returns {x0,y0,...,x3,y3} (BL→BR→TR→TL). */
    public static float[] projectGlassCornersToCanvas(float ex, float ey, float ez,
            float hw, float hh, float canvasD) {
        float t = (canvasD + ez) / ez;
        return new float[]{
            (ex + t * (-hw - ex)) / canvasD, (ey + t * (-hh - ey)) / canvasD,
            (ex + t * ( hw - ex)) / canvasD, (ey + t * (-hh - ey)) / canvasD,
            (ex + t * ( hw - ex)) / canvasD, (ey + t * ( hh - ey)) / canvasD,
            (ex + t * (-hw - ex)) / canvasD, (ey + t * ( hh - ey)) / canvasD
        };
    }

    /** 点是否在凸四边形内（maskQuad 8 floats，顶点顺序一致）。纯函数——可单测。
     *  Is a point inside the convex quad (maskQuad 8 floats, consistent winding)?
     *  Pure function. */
    public static boolean pointInConvexQuad(float px, float py, float[] q) {
        boolean sign = false;
        for (int i = 0; i < 4; i++) {
            float ax = q[i * 2], ay = q[i * 2 + 1];
            float bx = q[((i + 1) % 4) * 2], by = q[((i + 1) % 4) * 2 + 1];
            float c = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            if (i == 0) sign = c >= 0;
            else if ((c >= 0) != sign) return false;
        }
        return true;
    }

    /** 叉积（边 AB 与点 P）/ cross product (edge AB vs point P) */
    private static float cross2(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** 线段 PQ 与直线 AB 的交点（AB 为裁剪边方向）/ segment PQ ∩ line AB */
    private static float[] intersect2(float ax, float ay, float bx, float by,
            float px, float py, float qx, float qy) {
        float dpx = qx - px, dpy = qy - py;
        float dax = bx - ax, day = by - ay;
        float denom = dpx * day - dpy * dax;
        if (Math.abs(denom) < 1e-9f) return new float[]{qx, qy}; // 平行（防御）
        float t = ((ax - px) * day - (ay - py) * dax) / denom;
        return new float[]{px + t * dpx, py + t * dpy};
    }

    /** Sutherland-Hodgman 凸多边形裁剪：subject 多边形（float[]{x,y,...}）被凸
     *  四边形 quad 裁剪 → 裁剪后顶点列表（可为空）。纯函数——可单测。
     *  Sutherland-Hodgman convex-polygon clip: subject polygon clipped by the
     *  convex quad → clipped vertex list (possibly empty). Pure function. */
    public static float[] clipPolyToQuad(float[] poly, float[] quad) {
        float[] out = poly.clone();
        for (int i = 0; i < 4; i++) {
            float ax = quad[i * 2], ay = quad[i * 2 + 1];
            float bx = quad[((i + 1) % 4) * 2], by = quad[((i + 1) % 4) * 2 + 1];
            float[] next = new float[out.length + 8];
            int n = 0;
            int cnt = out.length / 2;
            for (int j = 0; j < cnt; j++) {
                float px = out[j * 2], py = out[j * 2 + 1];
                float qx = out[((j + 1) % cnt) * 2], qy = out[((j + 1) % cnt) * 2 + 1];
                boolean pIn = cross2(ax, ay, bx, by, px, py) >= 0;
                boolean qIn = cross2(ax, ay, bx, by, qx, qy) >= 0;
                if (qIn) {
                    if (!pIn) {
                        float[] ip = intersect2(ax, ay, bx, by, px, py, qx, qy);
                        next[n++] = ip[0]; next[n++] = ip[1];
                    }
                    next[n++] = qx; next[n++] = qy;
                } else if (pIn) {
                    float[] ip = intersect2(ax, ay, bx, by, px, py, qx, qy);
                    next[n++] = ip[0]; next[n++] = ip[1];
                }
            }
            out = new float[n];
            System.arraycopy(next, 0, out, 0, n);
            if (n == 0) return out;
        }
        return out;
    }

    /** 相机平面半平面裁剪（阈值 0，2026-08-24 指示线遮罩用，**逐顶点 fz 版**）：
     *  subject 多边形（画布局部坐标）对「fz ≤ threshold」半平面做 Sutherland-Hodgman
     *  单边裁剪，剔除相机后方（fz>0）的镜像内容。fz 数组是每个顶点经
     *  transformPosition（与 emitAnchored 同路径）算出的视线深度，边交点用 fz
     *  线性插值——不依赖矩阵元素手算（JOML 列主序下手算 m20/m21/m23 与
     *  transformPosition 语义不一致，会算出假 fz 误切）。阈值 0 只切真正越过后方
     *  的内容，fz<0 正常内容不动。纯函数——可单测。
     *  Camera-plane half-plane clip (threshold 0, per-vertex fz): Sutherland-Hodgman
     *  single-edge clip of the subject polygon (canvas-local coords) against
     *  fz ≤ threshold, cutting mirrored behind-camera (fz>0) content. fz[] holds
     *  each vertex's view depth from transformPosition (same path as emitAnchored);
     *  edge hits are linearly interpolated in fz — no hand-derived matrix elements
     *  (JOML column-major m20/m21/m23 disagree with transformPosition, which made
     *  false fz≈-0.1 and wrongly cut content). Pure function — unit-testable. */
    public static float[] clipPolyByDepth(float[] poly, float[] fz, float threshold) {
        int cnt = poly.length / 2;
        if (cnt < 3) return new float[0];
        float[] out = new float[0];
        float[] next = new float[poly.length + 8];
        int n = 0;
        for (int j = 0; j < cnt; j++) {
            float px = poly[j * 2], py = poly[j * 2 + 1];
            float qx = poly[((j + 1) % cnt) * 2], qy = poly[((j + 1) % cnt) * 2 + 1];
            float fp = fz[j], fq = fz[(j + 1) % cnt];
            boolean pIn = fp <= threshold, qIn = fq <= threshold;
            if (qIn) {
                if (!pIn) {
                    float t = (threshold - fp) / (fq - fp);
                    next[n++] = px + t * (qx - px);
                    next[n++] = py + t * (qy - py);
                }
                next[n++] = qx; next[n++] = qy;
            } else if (pIn) {
                float t = (threshold - fp) / (fq - fp);
                next[n++] = px + t * (qx - px);
                next[n++] = py + t * (qy - py);
            }
        }
        out = new float[n];
        System.arraycopy(next, 0, out, 0, n);
        return out;
    }

    /** 裁剪多边形 AABB / AABB of a clipped polygon (float[]{x,y,...}) */
    public static float[] polyAabb(float[] poly) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < poly.length; i += 2) {
            if (poly[i] < minX) minX = poly[i];
            if (poly[i] > maxX) maxX = poly[i];
            if (poly[i + 1] < minY) minY = poly[i + 1];
            if (poly[i + 1] > maxY) maxY = poly[i + 1];
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** 矩形绕中心 (cxr,cyr) 旋转 cosR/sinR 后的 AABB（内容局部坐标，字符级遮罩用）。
     *  AABB of a rect rotated by cosR/sinR about (cxr,cyr) (content-local, for the
     *  per-glyph mask). Pure function — unit-testable. */
    public static float[] rotatedAabb(float x0, float y0, float x1, float y1,
            float cxr, float cyr, float cosR, float sinR) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float px = (i & 1) == 0 ? x0 : x1;
            float py = (i & 2) == 0 ? y0 : y1;
            float dx = px - cxr, dy = py - cyr;
            float rx = cxr + dx * cosR - dy * sinR;
            float ry = cyr + dx * sinR + dy * cosR;
            if (rx < minX) minX = rx;
            if (rx > maxX) maxX = rx;
            if (ry < minY) minY = ry;
            if (ry > maxY) maxY = ry;
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** 细线 quad（面板局部坐标，玻璃平面、不锚定）：边框用——边框画在玻璃平面
     *  （z=0，近处），深度天然 = 玻璃深度，无需锚定。
     *  Thin line quad (panel-local coords, glass plane, no anchoring): the border
     *  sits on the glass plane (z=0, near), so its depth already equals the glass. */
    private static void addThickLine(VertexConsumer buf, org.joml.Matrix4f m,
            double x0, double y0, double x1, double y1, double w, float z,
            float r, float g, float b, float a) {
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return;
        double nx = -dy / len * w * 0.5, ny = dx / len * w * 0.5;
        buf.addVertex(m, (float)(x0 + nx), (float)(y0 + ny), z).setColor(r, g, b, a);
        buf.addVertex(m, (float)(x1 + nx), (float)(y1 + ny), z).setColor(r, g, b, a);
        buf.addVertex(m, (float)(x1 - nx), (float)(y1 - ny), z).setColor(r, g, b, a);
        buf.addVertex(m, (float)(x0 - nx), (float)(y0 - ny), z).setColor(r, g, b, a);
    }

    /** 「hud 只在玻璃上显示」的核心投影：把玻璃矩形（面板局部 z=0，[-hw,hw]×[-hh,hh]）
     *  沿玩家视线投影到虚像画布平面（面板局部 z=canvasD，canvasD 带符号：-FACING 侧为
     *  负——玩家透过玻璃看远处画布）。玩家眼睛在面板局部 (ex,ey,ez)。投影截面仍是
     *  矩形：中心 = 视线方向延伸与画布平面的交点，半宽半高按 |t| 缩放。内容布局
     *  [0,1]×[0,1] 映射到该截面 → 玩家透过玻璃看到的画布区域恰好 = 玻璃矩形 → 内容
     *  完整显示在玻璃内、玻璃外无内容。纯函数——可单测。
     *  <p>返回 {secCx, secCy, secHw, secHh}（画布局部、×100 缩放前坐标）。
     *  The core projection for "HUD shows only on the glass": the glass rect (panel-local
     *  z=0) projected along the player's view onto the virtual-image canvas plane
     *  (panel-local z=canvasD, signed: negative on the -FACING side — the player sees the
     *  far canvas through the glass). The eye is at panel-local (ex,ey,ez). The section
     *  is a rect: center = the view-ray/canvas-plane hit, half-extents scaled by |t|.
     *  Content layout [0,1]×[0,1] maps onto it → the canvas region seen through the glass
     *  is exactly the glass rect. Pure function — unit-testable.
     *  <p>Returns {secCx, secCy, secHw, secHh} (canvas-local, pre-×100-scale). */
    @SuppressWarnings("unchecked")
    private void flushTextNoCull(MultiBufferSource buffer, org.joml.Matrix4f viewRot,
            org.joml.Matrix4f viewRotInv, float glassZ) {
        // 解包 Iris 的 BufferSourceWrapper（2026-08-24）：光影环境把 BER 的
        // MultiBufferSource 包装成 net.irisshaders.iris.layer.BufferSourceWrapper，
        // instanceof BufferSource 失败 → 文字冲刷路径从未生效（文字由 LevelRenderer
        // 正常冲刷，深度=远处画布 → 被地形遮挡）。用运行时反射调用 getOriginal()
        // 逐层解包（不依赖编译期 Iris 依赖）。
        // Unwrap Iris' BufferSourceWrapper: with shaders the BER MultiBufferSource
        // is wrapped in net.irisshaders.iris.layer.BufferSourceWrapper, so the
        // instanceof BufferSource check failed and the text flush path never ran
        // (text flushed by LevelRenderer at far-canvas depth → occluded by terrain).
        // Peel via runtime reflection on getOriginal() (no compile-time Iris dep).
        MultiBufferSource unwrapped = buffer;
        for (int peel = 0; peel < 8; peel++) {
            if (!"net.irisshaders.iris.layer.BufferSourceWrapper".equals(unwrapped.getClass().getName())) break;
            try {
                Object o = unwrapped.getClass().getMethod("getOriginal").invoke(unwrapped);
                if (!(o instanceof MultiBufferSource)) break;
                unwrapped = (MultiBufferSource) o;
            } catch (Exception e) {
                SchematicCompute.LOGGER.error("Monitor unwrap BufferSourceWrapper failed", e);
                break;
            }
        }
        if (!(unwrapped instanceof MultiBufferSource.BufferSource bs)) return;
        if (STARTED_BUILDERS == null) return;
        try {
            var started = (Map<RenderType, BufferBuilder>) STARTED_BUILDERS.get(bs);
            var shared = (ByteBufferBuilder) SHARED_BUFFER.get(bs);
            var it = started.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                RenderType type = entry.getKey();
                if (type == null || type.format() != DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) continue;
                BufferBuilder builder = entry.getValue();
                var data = builder.build();
                if (data == null) { it.remove(); continue; }
                if (type.sortOnUpload()) data.sortQuads(shared, RenderSystem.getVertexSorting());
                // 3D 模式 NO_CULL 冲刷（HUD 模式文字已走方案 X 手动字形锚定，
                // 不再经此路径）。不写深度，避免文字深度污染场景缓冲。
                // 3D-mode NO_CULL flush (HUD-mode text now uses plan-X manual glyph
                // anchoring and no longer goes through here). No depth write, so the
                // text depth never pollutes the scene buffer.
                type.setupRenderState();
                RenderSystem.disableCull();
                RenderSystem.depthMask(false);
                BufferUploader.drawWithShader(data);
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                type.clearRenderState();
                it.remove();
                if (LAST_SHARED_TYPE != null) {
                    Object lt = LAST_SHARED_TYPE.get(bs);
                    if (lt == type || (lt != null && lt.equals(type))) LAST_SHARED_TYPE.set(bs, null);
                }
            }
        } catch (Exception e) { SchematicCompute.LOGGER.error("Monitor flushTextNoCull failed", e); }
    }

    /** 深度锚定绘制（2026-08-21 几何等效，官方接口）：冲刷独立 BufferBuilder 中的
     *  虚像顶点——顶点已由 {@link #emitAnchored} 构造为「屏幕位置 = 远处画布投影、
     *  深度 = 玻璃平面」，因此直接用 vanilla position_color shader 的 RenderType
     *  （{@link MonitorRenderTypes#HUD_DEPTH_ANCHOR}：LEQUAL 深度测试 + 不写深度）
     *  经官方 setupRenderState/drawWithShader/clearRenderState 绘制。无自定义
     *  shader、无运行时 uniform——Veil 4.0 对自定义 ShaderInstance 的绑定拦截
     *  （GL_CURRENT_PROGRAM=0 / GL_INVALID_OPERATION 实证）在此完全绕开。
     *  Depth-anchored draw (geometric equivalent, official interfaces): flushes the
     *  virtual-image vertices from the independent BufferBuilder — the vertices were
     *  built by {@link #emitAnchored} as "screen position = far-canvas projection,
     *  depth = glass plane", so the vanilla position_color RenderType
     *  ({@link MonitorRenderTypes#HUD_DEPTH_ANCHOR}: LEQUAL depth test + no depth
     *  write) draws them through the official setupRenderState/drawWithShader/
     *  clearRenderState path. No custom shader, no runtime uniform — Veil 4.0's
     *  custom-ShaderInstance bind interception (GL_CURRENT_PROGRAM=0 /
     *  GL_INVALID_OPERATION, proven) is fully bypassed. */
    private void drawDepthAnchored(BufferBuilder buf) {
        var data = buf.build();
        if (data == null) return;
        try {
            MonitorRenderTypes.HUD_DEPTH_ANCHOR.setupRenderState();
            BufferUploader.drawWithShader(data);
            MonitorRenderTypes.HUD_DEPTH_ANCHOR.clearRenderState();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Monitor depth-anchored draw failed", e);
        }
    }

    @Override
    public AABB getRenderBoundingBox(MonitorBlockEntity be) {
        // HUD 模式：玻璃始终可渲染（内容可为空），包围盒覆盖玻璃体积（面板大于方块时必做）
        // HUD mode: the glass always renders (content may be empty); the box must cover it
        if (be.hudMode) return hudGlassAabb(be);
        if (be.graph == null || be.graph.nodes.isEmpty()) {
            return AABB.INFINITE;
        }
        boolean hasDisplayContent = false;
        for (var n : be.graph.nodes) {
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA
                || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) {
                hasDisplayContent = true;
                break;
            }
        }
        if (!hasDisplayContent) {
            return AABB.INFINITE;
        }

        // screenX/Z offset rotated by block facing (same as renderer)
        float fx = be.screenX, fz = be.screenZ, fy = be.screenY;
        float facingYDeg = 0;
        if (be.getBlockState().hasProperty(MonitorBlock.FACING)) {
            facingYDeg = be.getBlockState().getValue(MonitorBlock.FACING).toYRot();
            float rad = (float) Math.toRadians(facingYDeg);
            float c = (float) Math.cos(rad);
            float s = (float) Math.sin(rad);
            float tx = fx * c - fz * s;
            float tz = fx * s + fz * c;
            fx = tx; fz = tz;
        }
        double centerX = be.getBlockPos().getX() + 0.5 + fx;
        double centerY = be.getBlockPos().getY() + fy;
        double centerZ = be.getBlockPos().getZ() + 0.5 + fz;

        float hw = be.screenWidth * 0.5f + 0.04f;
        float hh = be.screenLength * 0.5f + 0.04f;
        float depth = 0.06f;

        // R = Ry(adjYaw) * Rx(pitch) * Rz(roll) — matches renderer
        float adjYaw = be.screenYaw - facingYDeg;
        float yawRad = (float) Math.toRadians(adjYaw);
        float pitchRad = (float) Math.toRadians(be.screenPitch);
        float rollRad = (float) Math.toRadians(be.screenRoll);
        float cy = (float) Math.cos(yawRad), sy = (float) Math.sin(yawRad);
        float cp = (float) Math.cos(pitchRad), sp = (float) Math.sin(pitchRad);
        float cr = (float) Math.cos(rollRad), sr = (float) Math.sin(rollRad);

        // Ry * Rx * Rz
        float m00 = cy * cr + sy * sp * sr;
        float m01 = -cy * sr + sy * sp * cr;
        float m02 = sy * cp;
        float m10 = cp * sr;
        float m11 = cp * cr;
        float m12 = -sp;
        float m20 = -sy * cr + cy * sp * sr;
        float m21 = sy * sr + cy * sp * cr;
        float m22 = cy * cp;

        float[] exts = {-hw, hw, -hh, hh, -depth, depth};
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (int ix = 0; ix < 2; ix++) {
            float ex = exts[ix];
            for (int iy = 0; iy < 2; iy++) {
                float ey = exts[2 + iy];
                for (int iz = 0; iz < 2; iz++) {
                    float ez = exts[4 + iz];
                    double wx = centerX + m00 * ex + m01 * ey + m02 * ez;
                    double wy = centerY + m10 * ex + m11 * ey + m12 * ez;
                    double wz = centerZ + m20 * ex + m21 * ey + m22 * ez;
                    minX = Math.min(minX, wx);
                    maxX = Math.max(maxX, wx);
                    minY = Math.min(minY, wy);
                    maxY = Math.max(maxY, wy);
                    minZ = Math.min(minZ, wz);
                    maxZ = Math.max(maxZ, wz);
                }
            }
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static final float BR = 48f / 255f; // border gray
    private static final float BG = 48f / 255f;
    private static final float BB = 48f / 255f;

    /** Draw one side of the border frame. dir=1 for front (+Z), dir=-1 for back (-Z). */
    private void drawBorderFace(VertexConsumer buf, org.joml.Matrix4f m,
                                 float l, float r, float t, float b, float bw, int dir) {
        float z = 0.001f * dir;
        buf.addVertex(m, l - bw, t - bw, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, t - bw, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l - bw, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l - bw, b, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, b, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, b + bw, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l - bw, b + bw, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l - bw, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l, b, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, l - bw, b, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, t, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r + bw, b, z).setColor(BR, BG, BB, 1f);
        buf.addVertex(m, r, b, z).setColor(BR, BG, BB, 1f);
    }

    /** HUD 玻璃的裁剪包围盒：中心 = 方块中心 + FACING 局部帧内的偏移 + 法线方向距离；
     *  半长 = 面板尺寸/2 经 FACING 旋转的 OBB→AABB。面板大于方块时必需，否则某些视角
     *  玻璃会凭空消失（设计文档 §十一）。/ Cull box for the HUD glass: center = block
     *  center + offsets in the FACING local frame + normal distance; half-extents are the
     *  panel half-size expanded to an AABB under the FACING rotation. Required when the
     *  panel is bigger than the block (design doc §11). */
    private static AABB hudGlassAabb(MonitorBlockEntity be) {
        float facingYDeg = 0;
        if (be.getBlockState().hasProperty(MonitorBlock.FACING)) {
            facingYDeg = be.getBlockState().getValue(MonitorBlock.FACING).toYRot();
        }
        double rad = Math.toRadians(facingYDeg);
        double c = Math.cos(rad), s = Math.sin(rad);
        double ox = be.panelOffsetX, d = 0.5 + be.panelDistance;
        // renderHud 与 renderHud 面板位置必须一致：局部帧原点 (0.5+ox, 0.5+oy, 0.5)
        // 先平移、后 Ry(-toYRot)、再 (0,0,-d)（面板在 -FACING 侧方块表面外——注意
        // MonitorBlock 放置时 FACING = 玩家面朝方向的反方向）。局部 (ox,0,-d) 经
        // Ry(-θ)（列主序）→ x' = ox·cosθ + d·sinθ，z' = ox·sinθ - d·cosθ。
        // Must match renderHud's panel position: local origin (0.5+ox, 0.5+oy, 0.5),
        // then Ry(-toYRot), then (0,0,-d) — the panel sits outside the -FACING face
        // (MonitorBlock sets FACING opposite to the placing player). Local (ox,0,-d)
        // through Ry(-θ) (column-major) → x' = ox·cosθ + d·sinθ, z' = ox·sinθ - d·cosθ.
        double cx = be.getBlockPos().getX() + 0.5 + ox * c + d * s;
        double cz = be.getBlockPos().getZ() + 0.5 + ox * s - d * c;
        double cy = be.getBlockPos().getY() + 0.5 + be.panelOffsetY;
        double hw = be.panelSizeX * 0.5 + 0.02, hh = be.panelSizeY * 0.5 + 0.02;
        double dep = d + 0.02; // 从方块中心到面板外缘，AABB 覆盖方块+玻璃
        double ex = Math.abs(hw * c) + Math.abs(dep * s);
        double ez = Math.abs(hw * s) + Math.abs(dep * c);
        return new AABB(cx - ex, cy - hh, cz - ez, cx + ex, cy + hh, cz + ez);
    }

    @Override public boolean shouldRenderOffScreen(MonitorBlockEntity be) { return true; }
}
