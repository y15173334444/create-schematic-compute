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
        flushTextNoCull(buffer);

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
        // 3. 沿 FACING 法线推到面板距离
        poseStack.translate(0, 0, be.panelDistance);
        var m = poseStack.last().pose();
        // 玻璃 tint quad（POSITION_COLOR，无纹理）：近处「屏幕」底色
        // Glass tint quad (POSITION_COLOR, no texture): the near-screen background
        var tintBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        tintBuf.addVertex(m, -hw, -hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m,  hw, -hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m,  hw,  hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m, -hw,  hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        // 屏幕边框（画布边界可见）/ screen border (visible canvas bounds)
        addClippedLine(tintBuf, m, -hw, -hh, hw, -hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f, hw, hh);
        addClippedLine(tintBuf, m, hw, -hh, hw, hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f, hw, hh);
        addClippedLine(tintBuf, m, hw, hh, -hw, hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f, hw, hh);
        addClippedLine(tintBuf, m, -hw, hh, -hw, -hh, 0.01, 0.0005f, 0.1f, 0.6f, 0.2f, 0.5f, hw, hh);

        // ── 远处虚像画布：沿法线 D 格 + 尺寸 ×D（角尺寸保持 → 内容浮在远处） ──
        // Far virtual-image canvas: pushed D along the normal with size ×D (angular
        // size preserved → content floats far away).
        poseStack.pushPose();
        poseStack.translate(0, 0, VIRTUAL_IMAGE_D);
        poseStack.scale(VIRTUAL_IMAGE_D, VIRTUAL_IMAGE_D, VIRTUAL_IMAGE_D);

        // 内容区 = 整幅面板（HUD 无边框，设计文档 §八）；布局数学与 3D 模式同一套
        // （layoutX/Y 归一化、layerIndex 排序、旋转、信号偏移、左上角 clamp），左上角
        // 锚点 y-up（3D 模式同款；旧 FBO 方案是 y-down + v 翻转，已不存在）。
        // Content area = the full panel (HUD has no bezel, design doc §8); same normalized
        // layout math as 3D mode (layoutX/Y, layerIndex sort, rotation, signal offsets,
        // top-left clamp), top-left anchor in y-up (like 3D mode; the old FBO approach was
        // y-down with a v-flip — gone now).
        float cx = -hw, cy = hh;
        float cw = be.panelSizeX, ch = be.panelSizeY;
        var graph = be.graph;

        // ── IMAGE/IMAGE_SEQUENCE（layerIndex 排序，同世界渲染） ──
        var imgNodes = new ArrayList<GraphNode>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) imgNodes.add(n);
        }
        imgNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
        var sceneBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
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
            poseStack.pushPose();
            float fw = font.width(str), fh = 10f;
            poseStack.translate(nx + fw * s / 2f, ny - fh * s / 2f, 0.001f - n.layerIndex * 0.00001f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-n.displayRotation));
            poseStack.scale(s, -s, s);
            poseStack.translate(-fw / 2f, -fh / 2f, 0);
            font.drawInBatch(str, 0, 0, color, false,
                poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            poseStack.popPose();
        }

        // ── HUD_PITCH_LADDER（画布姿态仪：tan 透视刻度 + pitch 平移地平线 + roll 旋转，Phase 2） ──
        if (evalAvailable) {
            var hudNodes = new ArrayList<GraphNode>();
            for (var n : graph.nodes) {
                if (n.type == NodeType.HUD_PITCH_LADDER) hudNodes.add(n);
            }
            hudNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
            for (var n : hudNodes) {
                drawPitchLadder(n, be, poseStack, buffer, hw, hh, snapshot.outputs());
            }
        }
        poseStack.popPose(); // 虚像画布（×D）结束 / end of the ×D virtual-image canvas

        // ── Flush font with NO_CULL（tint + 像素由 endBatch 冲刷） ──
        flushTextNoCull(buffer);

        poseStack.popPose();
    }

    // ── 画布姿态仪（俯仰梯）：tan 透视刻度 + pitch 平移 + roll 旋转（纯函数，供单元测试） ──
    // Canvas attitude indicator (pitch ladder): tan-perspective ticks + pitch shift +
    // roll rotation (pure functions, unit-testable). No player-camera projection —
    // everything is drawn on the panel canvas, so Sable structures work natively.

    /** 画布刻度纵向尺度：1 弧度 ≈ 此比例 × 画布半高（tan 透视，近地平线密、远处疏）。
     *  Canvas tick scale: 1 radian ≈ this ratio × panel half-height (tan perspective). */
    private static final float LADDER_CANVAS_SCALE = 0.9f;

    /** 刻度角度（度）→ 画布 y 偏移（面板局部 y-up，相对画布中心）。
     *  world-pitch angle (deg) → canvas y offset (panel-local y-up, relative to canvas
     *  center). y = -K·tan(pitch+θ): pitch+θ = world direction of this tick.
     *  Pure function on doubles. */
    public static double ladderCanvasY(double pitchDeg, double thetaDeg, double halfH) {
        return -LADDER_CANVAS_SCALE * halfH * Math.tan(Math.toRadians(pitchDeg + thetaDeg));
    }

    /** 姿态仪画布刻度：对 -range..+range 每 interval 一条水平刻度线（绕画布中心
     *  旋转 roll 后经 Liang-Barsky 裁剪到画布矩形）。
     *  Canvas ladder ticks: one horizontal line per angle in -range..+range step
     *  interval, rotated about the canvas center by roll, then Liang-Barsky-clipped. */
    private void drawPitchLadder(GraphNode n, MonitorBlockEntity be, PoseStack poseStack,
            MultiBufferSource buffer, float hw, float hh, java.util.Map<Integer, float[]> outputs) {
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
        var buf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        var m = poseStack.last().pose();
        float z = 0.001f - n.layerIndex * 0.00001f;
        double rad = Math.toRadians(roll);
        double cr = Math.cos(rad), sr = Math.sin(rad);
        // 刻度线横跨画布 90% 宽度 / tick lines span 90% of the canvas width
        double halfLineW = hw * 0.9;
        // 刻度族（tan 透视，绕画布中心旋转，Liang-Barsky 裁剪到画布）——超画布的刻度
        // 被裁剪掉（真实 HUD 俯仰梯只在视场附近可见，即「显示区域只做裁剪」）。
        for (float theta = -range; theta <= range + 1e-4f; theta += interval) {
            double y = ladderCanvasY(pitch, theta, hh);
            // 旋转端点（绕画布中心）后裁剪
            double x0 = -halfLineW, y0 = y, x1 = halfLineW, y1 = y;
            double rx0 = x0 * cr - y0 * sr, ry0 = x0 * sr + y0 * cr;
            double rx1 = x1 * cr - y1 * sr, ry1 = x1 * sr + y1 * cr;
            if (Math.abs(theta) < interval * 0.5f) {
                addClippedLine(buf, m, rx0, ry0, rx1, ry1, 0.02, z, 0.0f, 1f, 0.6f, 0.9f, hw, hh); // 地平线
            } else {
                addClippedLine(buf, m, rx0, ry0, rx1, ry1, 0.008, z, 1f, 1f, 1f, 0.5f, hw, hh);    // 刻度
            }
        }
        // 中央飞机符号（固定画布中心，roll 旋转的短机身 + 翼线）
        // Central aircraft symbol (fixed at canvas center; short fuselage + wing rotated by roll)
        addClippedLine(buf, m, -hw * 0.12 * cr, -hw * 0.12 * sr, hw * 0.12 * cr, hw * 0.12 * sr,
            0.015, z, 0.2f, 1f, 0.4f, 0.95f, hw, hh);
        addClippedLine(buf, m, -hw * 0.18 * cr, -hw * 0.18 * sr, hw * 0.18 * cr, hw * 0.18 * sr,
            0.02, z, 0.2f, 1f, 0.4f, 0.95f, hw, hh);
    }

    /** 细线 quad（面板局部坐标）：把线段 (x0,y0)-(x1,y1) 画成 w 宽矩形。
     *  Thin line quad (panel-local coords): the segment (x0,y0)-(x1,y1) as a w-wide rect. */
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

    /** Liang-Barsky 线段 vs 面板矩形裁剪：仅绘制落在 [-hw,hw]×[-hh,hh] 内的部分。
     *  Liang-Barsky segment-vs-panel-rect clip: draws only the part inside
     *  [-hw,hw]×[-hh,hh]. Pure function on doubles — unit-testable. */
    public static double[] clipSegmentToPanel(
            double x0, double y0, double x1, double y1, double hw, double hh) {
        double t0 = 0, t1 = 1;
        double dx = x1 - x0, dy = y1 - y0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 + hw, hw - x0, y0 + hh, hh - y0};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                if (q[i] < 0) return null; // 平行且在矩形外 / parallel and outside
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    if (t > t1) return null;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return null;
                    if (t < t1) t1 = t;
                }
            }
        }
        if (t1 <= t0) return null;
        return new double[]{x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy};
    }

    private static void addClippedLine(VertexConsumer buf, org.joml.Matrix4f m,
            double x0, double y0, double x1, double y1, double w, float z,
            float r, float g, float b, float a, double hw, double hh) {
        double[] seg = clipSegmentToPanel(x0, y0, x1, y1, hw, hh);
        if (seg == null) return;
        addThickLine(buf, m, seg[0], seg[1], seg[2], seg[3], w, z, r, g, b, a);
    }

    @SuppressWarnings("unchecked")
    private void flushTextNoCull(MultiBufferSource buffer) {
        if (!(buffer instanceof MultiBufferSource.BufferSource bs)) return;
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
                type.setupRenderState();
                RenderSystem.disableCull();
                BufferUploader.drawWithShader(data);
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
        double ox = be.panelOffsetX, d = be.panelDistance;
        // 局部帧原点 (0.5+ox, 0.5+oy, 0.5) 先平移、后 Ry(-toYRot)、再 (0,0,d)
        double cx = be.getBlockPos().getX() + 0.5 + ox * c - d * s;
        double cz = be.getBlockPos().getZ() + 0.5 + ox * s + d * c;
        double cy = be.getBlockPos().getY() + 0.5 + be.panelOffsetY;
        double hw = be.panelSizeX * 0.5 + 0.02, hh = be.panelSizeY * 0.5 + 0.02, dep = be.panelDistance + 0.02;
        double ex = Math.abs(hw * c) + Math.abs(dep * s);
        double ez = Math.abs(hw * s) + Math.abs(dep * c);
        return new AABB(cx - ex, cy - hh, cz - ez, cx + ex, cy + hh, cz + ez);
    }

    @Override public boolean shouldRenderOffScreen(MonitorBlockEntity be) { return true; }
}
