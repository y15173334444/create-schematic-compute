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

    /** HUD 模式 BER 主 pass：玻璃 tint quad + 内容直接绘制（纯官方接口，无 FBO）。
     *  HUD-mode BER main pass: glass tint quad + content drawn directly (official
     *  interfaces only, no FBO). */
    private void renderHud(MonitorBlockEntity be, PoseStack poseStack, MultiBufferSource buffer) {
        float hw = be.panelSizeX * 0.5f, hh = be.panelSizeY * 0.5f;
        var mc = Minecraft.getInstance();
        var font = mc.font;
        var snapshot = be.cachedEvalSnapshot;
        boolean evalAvailable = be.running && snapshot != null;
        // 共形投影：玩家相机（眼睛）+ 面板世界帧（中心/法线/右/上）
        // Conformal projection: player camera (eye) + panel world frame (center/normal/right/up)
        var camera = mc.gameRenderer.getMainCamera();
        double[] eye = {camera.getPosition().x, camera.getPosition().y, camera.getPosition().z};
        float viewYaw = camera.getYRot();
        double[][] frame = hudPanelFrame(be);
        double[] pCenter = frame[0], pNormal = frame[1], pRight = frame[2], pUp = frame[3];

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
        // 玻璃 tint quad（POSITION_COLOR，无纹理）：内容为空时也能看到面板边界
        // Glass tint quad (POSITION_COLOR, no texture): panel bounds stay visible even with no content
        var tintBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        tintBuf.addVertex(m, -hw, -hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m,  hw, -hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m,  hw,  hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);
        tintBuf.addVertex(m, -hw,  hh, 0.0005f).setColor(0.02f, 0.10f, 0.04f, 0.35f);

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
            // 锚定：贴玻璃 = 左上角 clamp 定位；贴世界 = 共形投影中心 + layout 偏移
            // Anchor: on-glass = top-left clamp; on-world = conformal projection center + layout offset
            float cell = 0.03f * n.displayScale;
            float halfW = (n.imageWidth * 0.5f) * cell, halfH = (n.imageHeight * 0.5f) * cell;
            double[] anchor = null;
            if (n.anchorMode == 1) {
                anchor = projectDirectionToPanel(eye, directionFromYawPitch(n.anchorYaw, n.anchorPitch),
                    pCenter, pNormal, pRight, pUp, hw, hh);
                if (anchor == null) continue; // 锚定方向在面板背后/出界 → 不画
            }
            float nx, ny;
            if (anchor != null) {
                nx = (float)anchor[0] + (n.layoutX - 0.5f) * be.panelSizeX;
                ny = (float)anchor[1] + (n.layoutY - 0.5f) * be.panelSizeY;
            } else {
                float rA = (float)Math.abs(Math.cos(Math.toRadians(effectiveRot)));
                float rB = (float)Math.abs(Math.sin(Math.toRadians(effectiveRot)));
                float bbHalfW = (halfW * rA + halfH * rB) / cw;
                float bbHalfH = (halfW * rB + halfH * rA) / ch;
                float rawX = n.layoutX + dx;
                float rawY = n.layoutY + dy;
                float cpx = Math.max(0, Math.min(1 - 2 * bbHalfW, rawX));
                float cpy = Math.max(0, Math.min(1 - 2 * bbHalfH, rawY));
                nx = cx + cpx * cw;
                ny = cy - cpy * ch;
            }
            poseStack.pushPose();
            if (anchor != null) {
                poseStack.translate(nx, ny, 0.001f - n.layerIndex * 0.00001f); // 中心锚 / center anchor
            } else {
                poseStack.translate(nx + halfW, ny - halfH, 0.001f - n.layerIndex * 0.00001f);
            }
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
            // 锚定：贴玻璃 = layout 左上角；贴世界 = 共形投影中心 + layout 偏移
            // Anchor: on-glass = layout top-left; on-world = conformal projection center + layout offset
            double[] anchor = null;
            if (n.anchorMode == 1) {
                anchor = projectDirectionToPanel(eye, directionFromYawPitch(n.anchorYaw, n.anchorPitch),
                    pCenter, pNormal, pRight, pUp, hw, hh);
                if (anchor == null) continue; // 锚定方向在面板背后/出界 → 不画
            }
            float nx, ny;
            if (anchor != null) {
                nx = (float)anchor[0] + (n.layoutX - 0.5f) * be.panelSizeX;
                ny = (float)anchor[1] + (n.layoutY - 0.5f) * be.panelSizeY;
            } else {
                nx = cx + n.layoutX * cw;
                ny = cy - n.layoutY * ch;
            }
            poseStack.pushPose();
            float fw = font.width(str), fh = 10f;
            if (anchor != null) {
                poseStack.translate(nx, ny, 0.001f - n.layerIndex * 0.00001f); // 中心锚 / center anchor
            } else {
                poseStack.translate(nx + fw * s / 2f, ny - fh * s / 2f, 0.001f - n.layerIndex * 0.00001f);
            }
            poseStack.mulPose(Axis.ZP.rotationDegrees(-n.displayRotation));
            poseStack.scale(s, -s, s);
            poseStack.translate(-fw / 2f, -fh / 2f, 0);
            font.drawInBatch(str, 0, 0, color, false,
                poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            poseStack.popPose();
        }

        // ── HUD_PITCH_LADDER（共形俯仰梯 + 地平线 + 姿态标记，Phase 2） ──
        if (evalAvailable) {
            var hudNodes = new ArrayList<GraphNode>();
            for (var n : graph.nodes) {
                if (n.type == NodeType.HUD_PITCH_LADDER) hudNodes.add(n);
            }
            hudNodes.sort((n1, n2) -> Integer.compare(n1.layerIndex, n2.layerIndex));
            for (var n : hudNodes) {
                drawPitchLadder(n, be, poseStack, buffer, eye, viewYaw,
                    pCenter, pNormal, pRight, pUp, hw, hh, snapshot.outputs());
            }
        }

        // ── Flush font with NO_CULL（tint + 像素由 endBatch 冲刷） ──
        flushTextNoCull(buffer);

        poseStack.popPose();
    }

    // ── §9.1 共形投影（纯函数，供单元测试） / Conformal projection (§9.1, pure functions for tests) ──

    /** 俯仰梯方位采样常量：以玩家视线为中心展开 ±50°、33 点（折线平滑、边缘裁剪后连续）。
     *  Ladder azimuth sampling: ±50° around the player's view, 33 points per line
     *  (smooth polylines; Liang-Barsky clipping keeps edge segments continuous). */
    private static final int LADDER_SAMPLES = 33;
    private static final float LADDER_AZIMUTH_HALF = 50f;

    /** 面板世界帧：{center[3], normal[3], right[3], up[3]}。与 renderHud 的位姿
     *  变换完全一致（方块中心 + 面板偏移 + FACING 旋转 + 法线距离）。
     *  Sable 结构上（onSableStructure）：用缓存的方块世界坐标 + 结构朝向四元数，
     *  面板偏移/法线/右/上向量经四元数旋转到世界——getBlockPos() 在结构上是子世界
     *  本地坐标，直接用它会导致共形投影坐标系错乱（俯仰梯完全不显示）。
     *  Panel world frame: {center, normal, right, up} — matches renderHud's pose
     *  exactly (block center + panel offset + FACING rotation + normal distance).
     *  On a Sable structure: uses the cached world position + structure orientation
     *  quaternion; panel offset/normal/right/up are rotated into world space —
     *  getBlockPos() is sub-world LOCAL there, so using it directly breaks the
     *  conformal projection's coordinate frame (pitch ladder not drawn at all). */
    public static double[][] hudPanelFrame(MonitorBlockEntity be) {
        double yawRad = 0;
        if (be.getBlockState().hasProperty(MonitorBlock.FACING)) {
            yawRad = Math.toRadians(be.getBlockState().getValue(MonitorBlock.FACING).toYRot());
        }
        return panelFrameFromBasis(
            be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ(),
            (float) Math.toDegrees(yawRad), be.panelOffsetX, be.panelOffsetY, be.panelDistance,
            be.onSableStructure(), be.cachedSubWorldX, be.cachedSubWorldY, be.cachedSubWorldZ,
            be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw);
    }

    /** 纯函数：面板世界帧计算（可单元测试）。
     *  Sable 结构上（onSable）：cachedSubWorld 是方块中心世界坐标（已含结构旋转），
     *  面板偏移是 renderHud 的世界平移语义（直接加），法线距离沿 FACING 方向
     *  （结构本地向量）经结构四元数旋转到世界；法线/右/上向量同样旋转。
     *  普通路径：方块坐标 + 偏移 + FACING 旋转 + 法线距离（与 renderHud 一致）。
     *  Pure function: panel world frame computation (unit-testable).
     *  On a Sable structure: cachedSubWorld is the block-center world position
     *  (structure-rotated); the panel offset is renderHud's world-translation (added
     *  directly); the FACING-normal distance (a structure-local vector) is rotated
     *  into world space by the structure quaternion, as are normal/right/up.
     *  Plain path: block coords + offset + FACING rotation + normal distance. */
    public static double[][] panelFrameFromBasis(
            double bx, double by, double bz,
            float facingYawDeg, float panelOffsetX, float panelOffsetY, float panelDistance,
            boolean onSable, double swx, double swy, double swz,
            double qx, double qy, double qz, double qw) {
        double yawRad = Math.toRadians(facingYawDeg);
        double sy = Math.sin(yawRad), cy = Math.cos(yawRad);
        double[] n = {-sy, 0, cy};  // Ry(-yaw) * (0,0,1)
        double[] r = {cy, 0, sy};   // Ry(-yaw) * (1,0,0)
        double[] u = {0, 1, 0};     // 竖直面板的上向量 / up for a vertical panel
        double d = panelDistance;

        if (onSable) {
            var q = new org.joml.Quaterniond(qx, qy, qz, qw);
            var dist = new org.joml.Vector3d(n[0] * d, n[1] * d, n[2] * d);
            q.transform(dist);
            double[] c = {swx + panelOffsetX + dist.x, swy + panelOffsetY + dist.y, swz + dist.z};
            var nv = new org.joml.Vector3d(n[0], n[1], n[2]); q.transform(nv);
            var rv = new org.joml.Vector3d(r[0], r[1], r[2]); q.transform(rv);
            var uv = new org.joml.Vector3d(u[0], u[1], u[2]); q.transform(uv);
            return new double[][]{c, {nv.x, nv.y, nv.z}, {rv.x, rv.y, rv.z}, {uv.x, uv.y, uv.z}};
        }

        double[] c = {
            bx + 0.5 + panelOffsetX + n[0] * d,
            by + 0.5 + panelOffsetY + n[1] * d,
            bz + 0.5 + n[2] * d};
        return new double[][]{c, n, r, u};
    }

    /** 世界绝对方向（yaw/pitch 度，MC 约定：yaw=0 → +Z，正 yaw 顺时针）→ 单位向量。
     *  World-absolute direction (yaw/pitch degrees, MC convention) → unit vector. */
    public static double[] directionFromYawPitch(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new double[]{-Math.sin(yaw) * cp, Math.sin(pitch), Math.cos(yaw) * cp};
    }

    /** §9.1 共形投影：世界方向经玩家眼投影到玻璃平面，得面板局部坐标（y-up，中心原点）。
     *  返回 null：方向在面板背后（t≤0）、与面板平行、或投影出界。
     *  §9.1 conformal projection: project a world direction through the player eye
     *  onto the glass plane, yielding panel-local coords (y-up, origin at center).
     *  Returns null when the direction is behind the panel (t≤0), parallel, or off-panel. */
    public static double[] projectDirectionToPanel(
            double[] eye, double[] dir,
            double[] center, double[] normal, double[] right, double[] up,
            double halfW, double halfH) {
        double dx = dir[0], dy = dir[1], dz = dir[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return null;
        dx /= len; dy /= len; dz /= len;
        double denom = dx * normal[0] + dy * normal[1] + dz * normal[2];
        if (Math.abs(denom) < 1e-9) return null; // 平行于面板 / parallel to the panel
        double t = ((center[0] - eye[0]) * normal[0] + (center[1] - eye[1]) * normal[1]
                  + (center[2] - eye[2]) * normal[2]) / denom;
        if (t <= 0) return null; // 玻璃在背后 / the panel is behind the eye
        double hx = eye[0] + dx * t - center[0];
        double hy = eye[1] + dy * t - center[1];
        double hz = eye[2] + dz * t - center[2];
        double rr = hx * right[0] + hy * right[1] + hz * right[2];
        double uu = hx * up[0] + hy * up[1] + hz * up[2];
        if (Math.abs(rr) > halfW || Math.abs(uu) > halfH) return null; // 出界 / off-panel
        return new double[]{rr, uu};
    }

    /** 俯仰梯单条刻度线：刻度角 θ → 面板局部折线点列（出界点跳过）。
     *  One pitch-ladder line: ladder angle θ → panel-local polyline points (off-panel skipped). */
    public static java.util.List<double[]> ladderLinePoints(
            float viewYawDeg, float thetaDeg, int samples, float azimuthHalfSpreadDeg,
            double[] eye, double[] center, double[] normal, double[] right, double[] up,
            double halfW, double halfH) {
        var pts = new java.util.ArrayList<double[]>();
        for (int i = 0; i < samples; i++) {
            float az = samples <= 1 ? 0f
                : -azimuthHalfSpreadDeg + 2f * azimuthHalfSpreadDeg * i / (samples - 1);
            double[] dir = directionFromYawPitch(viewYawDeg + az, thetaDeg);
            double[] p = projectDirectionToPanel(eye, dir, center, normal, right, up, halfW, halfH);
            if (p != null) pts.add(p);
        }
        return pts;
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

    /** 折线绘制（Liang-Barsky 线段裁剪到面板矩形）：相邻点连细线，出界线段
     *  裁剪到面板边界——刻度线在玻璃边缘平滑截断（移出视野）而非整段消失。
     *  Polyline drawing (Liang-Barsky clip to the panel rect): adjacent points are
     *  joined by thin lines; off-panel segments are clipped to the panel boundary so
     *  ladder lines cut off smoothly at the glass edge instead of vanishing whole. */
    private static void drawPolyline(VertexConsumer buf, org.joml.Matrix4f m,
            java.util.List<double[]> pts, double w, float z,
            float r, float g, float b, float a, double hw, double hh) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] p0 = pts.get(i), p1 = pts.get(i + 1);
            addClippedLine(buf, m, p0[0], p0[1], p1[0], p1[1], w, z, r, g, b, a, hw, hh);
        }
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

    /** 共形俯仰梯 + 地平线 + 姿态标记（§9.2 首批）。
     *  刻度族 = 相对世界水平面各俯仰角方向族（全范围 ±90°），经玩家相机投影成折线；
     *  地平线（0°）加粗高亮；姿态标记 = pitch 输入定位 + roll 输入旋转的绿色横线。
     *  Conformal pitch ladder + horizon + attitude marker (§9.2 first batch).
     *  Ladder lines = fixed world-horizontal direction fans (±90° full range) projected
     *  through the player camera; horizon (0°) is bold; the attitude marker is a green
     *  line positioned by the pitch input and rotated by the roll input. */
    private void drawPitchLadder(GraphNode n, MonitorBlockEntity be, PoseStack poseStack,
            MultiBufferSource buffer, double[] eye, float viewYaw,
            double[] center, double[] normal, double[] right, double[] up,
            float hw, float hh, java.util.Map<Integer, float[]> outputs) {
        float targetPitch = be.graph.getInputValue(n.id, 0, outputs);
        float targetRoll = be.graph.getInputValue(n.id, 1, outputs);
        // 姿态平滑：20Hz 数据 → 60fps 指数插值（现实 HUD 姿态标记是连续平滑的，不跳变）。
        // Attitude smoothing: 20Hz data → 60fps exponential interpolation (a real HUD
        // marker moves continuously; it must not step).
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
        // 刻度族（全范围）/ ladder line fan (full range)
        for (float theta = -range; theta <= range + 1e-4f; theta += interval) {
            var pts = ladderLinePoints(viewYaw, theta, LADDER_SAMPLES, LADDER_AZIMUTH_HALF,
                eye, center, normal, right, up, hw, hh);
            if (Math.abs(theta) < interval * 0.5f) {
                drawPolyline(buf, m, pts, 0.02, z, 0.0f, 1f, 0.6f, 0.9f, hw, hh);   // 地平线：加粗高亮 / horizon: bold
            } else {
                drawPolyline(buf, m, pts, 0.008, z, 1f, 1f, 1f, 0.5f, hw, hh);      // 普通刻度：半透明白 / ticks: translucent white
            }
        }
        // 姿态标记：pitch 输入定位、roll 输入旋转（绕标记中心）
        // Attitude marker: positioned by the pitch input, rotated by the roll input
        double[] mark = projectDirectionToPanel(eye,
            directionFromYawPitch(viewYaw, pitch), center, normal, right, up, hw, hh);
        if (mark != null) {
            double rad = Math.toRadians(roll);
            double cr = Math.cos(rad), sr = Math.sin(rad);
            float mw = be.panelSizeX * 0.18f;
            double x0 = -mw, y0 = 0, x1 = mw, y1 = 0;
            double rx0 = x0 * cr - y0 * sr + mark[0], ry0 = x0 * sr + y0 * cr + mark[1];
            double rx1 = x1 * cr - y1 * sr + mark[0], ry1 = x1 * sr + y1 * cr + mark[1];
            addThickLine(buf, m, rx0, ry0, rx1, ry1, 0.015, z, 0.2f, 1f, 0.4f, 0.95f);
            // 中央小飞机机身（简化）/ central aircraft fuselage stub (simplified)
            addThickLine(buf, m, mark[0] - mw * 0.15, mark[1], mark[0] + mw * 0.15, mark[1],
                0.02, z, 0.2f, 1f, 0.4f, 0.95f);
        }
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
