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

    /** 深度锚定诊断日志只打一次（每帧刷屏无意义）/ depth-anchor debug log: once only */
    private static boolean HUD_DEPTH_DEBUG_LOGGED = false;

    /** HUD 虚像画布共享字节缓冲（static 复用，2026-08-21 修复 OOM：每帧 new 1MB
     *  ByteBufferBuilder 是堆外内存泄漏）。渲染线程串行，build() 快照后 clear() 重填。
     *  Reused HUD canvas byte buffer (2026-08-21 OOM fix). Render thread is serial;
     *  build() snapshots the data, then clear() refills per frame. */
    private static ByteBufferBuilder HUD_CANVAS_BYTES;

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
        // 玻璃面板中心 → 相机空间深度 gz（顶点级深度锚定目标，2026-08-21 几何等效）
        // Glass panel center → camera-space depth gz (the vertex-level depth-anchor
        // target, 2026-08-21 geometric equivalent).
        var glassView = new org.joml.Vector3f();
        m.transformPosition(glassView);
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
                    // 4-gon mask: intersect the pixel quad with the glass-projection
                    // quad — fully-inside draws directly, fully-outside skips,
                    // partial clips (Sutherland-Hodgman) + triangle fan.
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
                        emitAnchored(canvasBuf, m2, x0, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, x1, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, x1, y1, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, x0, y0, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, x1, y1, 0f, glassZ, rf, gf, bf, af);
                        emitAnchored(canvasBuf, m2, x0, y1, 0f, glassZ, rf, gf, bf, af);
                    } else {
                        float[] clipped = clipPolyToQuad(
                            new float[]{x0, y0, x1, y0, x1, y1, x0, y1}, maskImg);
                        int nv = clipped.length / 2;
                        if (nv < 3) continue;
                        for (int i = 1; i < nv - 1; i++) {
                            emitAnchored(canvasBuf, m2, clipped[0], clipped[1], 0f, glassZ, rf, gf, bf, af);
                            emitAnchored(canvasBuf, m2, clipped[i * 2], clipped[i * 2 + 1], 0f, glassZ, rf, gf, bf, af);
                            emitAnchored(canvasBuf, m2, clipped[(i + 1) * 2], clipped[(i + 1) * 2 + 1], 0f, glassZ, rf, gf, bf, af);
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
            for (int ci = 0; ci < str.length(); ci++) {
                float cwCh = font.width(String.valueOf(str.charAt(ci)));
                float gx0 = nx + adv * s, gy0 = ny;
                float gx1 = gx0 + cwCh * s, gy1 = gy0 + fh * s;
                adv += cwCh;
                float[] ga = rotatedAabb(gx0, gy0, gx1, gy1, cxr, cyr, cosR, sinR);
                if (ga[2] <= maskAabb[0] || ga[0] >= maskAabb[2]
                    || ga[3] <= maskAabb[1] || ga[1] >= maskAabb[3]) continue; // 字符在遮罩外
                poseStack.pushPose();
                poseStack.translate(cxr, cyr, 0.001f - n.layerIndex * 0.00001f);
                poseStack.mulPose(Axis.ZP.rotationDegrees(-n.displayRotation));
                poseStack.scale(s, -s, s);
                poseStack.translate(-fw / 2f + adv - cwCh, -fh / 2f, 0);
                font.drawInBatch(String.valueOf(str.charAt(ci)), 0, 0, color, false,
                    poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, 0xF000F0);
                poseStack.popPose();
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
                drawPitchLadder(n, be, poseStack, canvasBuf, hw, hh, glassZ, maskQuad, snapshot.outputs());
            }
        }
        poseStack.popPose(); // 远处画布（×D）结束 / end of the far canvas (×D)

        // 深度锚定冲刷：绑定 hud_depth_anchor 着色器、写入 GlassNdcZ（玻璃面板中心
        // 的 NDC 深度）、绘制独立 buffer 中的虚像顶点——屏幕位置在远处画布、深度在
        // 玻璃平面（前方遮挡/后方不遮挡）。文字（font.drawInBatch）仍由 buffer 冲刷，
        // 已知限制：文字深度=远处画布，暂不锚定。
        // Depth-anchored flush: bind hud_depth_anchor, write GlassNdcZ (the glass
        // panel center's NDC depth), draw the virtual-image vertices from the
        // independent buffer — screen position on the far canvas, depth on the glass
        // plane (near occludes / far does not). Text (font.drawInBatch) still flushes
        // via the buffer; known limitation: text depth stays on the far canvas.
        drawDepthAnchored(canvasBuf);
        flushTextNoCull(buffer); // 冲刷远处画布内的文字（NO_CULL）/ flush the text
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
     *  world-pitch angle (deg) → canvas y offset (panel-local y-up, relative to canvas
     *  center). y = -K·tan(pitch+θ): pitch+θ = world direction of this tick.
     *  Pure function on doubles. */
    public static double ladderCanvasY(double pitchDeg, double thetaDeg, double halfH) {
        return -LADDER_CANVAS_SCALE * halfH * Math.tan(Math.toRadians(pitchDeg + thetaDeg));
    }

    /** 姿态仪画布刻度：对 -range..+range 每 interval 一条水平刻度线（绕画布中心
     *  旋转 roll 后经玩家屏幕 4 边形遮罩裁剪——见 addThickLineAnchored）。
     *  Canvas ladder ticks: one horizontal line per angle in -range..+range step
     *  interval, rotated about the canvas center by roll, then clipped by the
     *  player-screen 4-gon mask (see addThickLineAnchored). */
    private void drawPitchLadder(GraphNode n, MonitorBlockEntity be, PoseStack poseStack,
            BufferBuilder buf, float hw, float hh, float glassZ, float[] maskQuad,
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
        // 刻度线横跨画布 90% 宽度 / tick lines span 90% of the canvas width
        double halfLineW = hw * 0.9;
        // 刻度族（tan 透视，绕画布中心旋转，经 4 边形遮罩裁剪）——玩家透过玻璃
        // 看不到的刻度被裁掉（真实 HUD 俯仰梯只在视场附近可见）。
        // Tick family (tan perspective, rotated about the canvas center, clipped by
        // the 4-gon mask) — ticks outside the region seen through the glass are cut.
        for (float theta = -range; theta <= range + 1e-4f; theta += interval) {
            double y = ladderCanvasY(pitch, theta, hh);
            double x0 = -halfLineW, y0 = y, x1 = halfLineW, y1 = y;
            double rx0 = x0 * cr - y0 * sr, ry0 = x0 * sr + y0 * cr;
            double rx1 = x1 * cr - y1 * sr, ry1 = x1 * sr + y1 * cr;
            if (Math.abs(theta) < interval * 0.5f) {
                addThickLineAnchored(buf, m, rx0, ry0, rx1, ry1, 0.02, z, glassZ, maskQuad, 0.0f, 1f, 0.6f, 0.9f); // 地平线
            } else {
                addThickLineAnchored(buf, m, rx0, ry0, rx1, ry1, 0.008, z, glassZ, maskQuad, 1f, 1f, 1f, 0.5f);    // 刻度
            }
        }
        // 中央飞机符号（固定画布中心，roll 旋转的短机身 + 翼线）
        // Central aircraft symbol (fixed at canvas center; short fuselage + wing rotated by roll)
        addThickLineAnchored(buf, m, -hw * 0.12 * cr, -hw * 0.12 * sr, hw * 0.12 * cr, hw * 0.12 * sr,
            0.015, z, glassZ, maskQuad, 0.2f, 1f, 0.4f, 0.95f);
        addThickLineAnchored(buf, m, -hw * 0.18 * cr, -hw * 0.18 * sr, hw * 0.18 * cr, hw * 0.18 * sr,
            0.02, z, glassZ, maskQuad, 0.2f, 1f, 0.4f, 0.95f);
    }

    /** 细线 quad（画布局部坐标，深度锚定 + 4 边形遮罩）：把线段 (x0,y0)-(x1,y1)
     *  画成 w 宽矩形。顶点经画布矩阵 m 到相机空间后深度锚定到玻璃平面；与玻璃
     *  投影 4 边形 maskQuad 求交裁剪（全内直画 / 全外跳过 / 相交裁剪 + 三角扇）。
     *  Thin line quad (canvas-local coords, depth-anchored + 4-gon mask): the
     *  segment (x0,y0)-(x1,y1) as a w-wide rect. Vertices depth-anchor to the
     *  glass plane; clipped against the glass-projection 4-gon maskQuad
     *  (fully-inside draws / outside skips / partial clips + triangle fan). */
    private static void addThickLineAnchored(BufferBuilder buf, org.joml.Matrix4f m,
            double x0, double y0, double x1, double y1, double w, float z,
            float glassZ, float[] maskQuad, float r, float g, float b, float a) {
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
        float[] qa = polyAabb(quad);
        float[] ma = polyAabb(maskQuad);
        if (qa[2] <= ma[0] || qa[0] >= ma[2] || qa[3] <= ma[1] || qa[1] >= ma[3]) return; // 全外
        boolean allIn = pointInConvexQuad(q0x, q0y, maskQuad)
            && pointInConvexQuad(q1x, q1y, maskQuad)
            && pointInConvexQuad(q2x, q2y, maskQuad)
            && pointInConvexQuad(q3x, q3y, maskQuad);
        if (allIn) {
            emitAnchored(buf, m, q0x, q0y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, q1x, q1y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, q2x, q2y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, q0x, q0y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, q2x, q2y, z, zAnchor, r, g, b, a);
            emitAnchored(buf, m, q3x, q3y, z, zAnchor, r, g, b, a);
        } else {
            float[] clipped = clipPolyToQuad(quad, maskQuad);
            int nv = clipped.length / 2;
            if (nv < 3) return;
            for (int i = 1; i < nv - 1; i++) {
                emitAnchored(buf, m, clipped[0], clipped[1], z, zAnchor, r, g, b, a);
                emitAnchored(buf, m, clipped[i * 2], clipped[i * 2 + 1], z, zAnchor, r, g, b, a);
                emitAnchored(buf, m, clipped[(i + 1) * 2], clipped[(i + 1) * 2 + 1], z, zAnchor, r, g, b, a);
            }
        }
    }

    /** 顶点级深度锚定（2026-08-21 几何等效，用户讲解机制的官方接口实现）：
     *  画布局部点 (x,y,zLocal) 经画布矩阵 m2 到相机空间 V_far=(fx,fy,fz)，构造
     *  V'=(fx·zAnchor/fz, fy·zAnchor/fz, zAnchor)——屏幕投影 x'/(-z') = fx/(-fz)
     *  与远处画布一致（角尺寸保持），深度 = zAnchor（玻璃平面 + 图层偏移）→ 前方
     *  遮挡/后方不遮挡。无自定义 shader、无运行时 uniform——纯官方接口。
     *  Vertex-level depth anchor (geometric equivalent, official interfaces only):
     *  the canvas-local point (x,y,zLocal) goes through the canvas matrix m2 to
     *  camera space V_far=(fx,fy,fz); V'=(fx·zAnchor/fz, fy·zAnchor/fz, zAnchor)
     *  keeps the far-canvas screen projection (x'/z' ratio) while depth lands on
     *  zAnchor (glass plane + layer offset) → near occludes / far does not. */
    private static void emitAnchored(BufferBuilder buf, org.joml.Matrix4f m2,
            float x, float y, float zLocal, float zAnchor, float r, float g, float b, float a) {
        var v = new org.joml.Vector3f(x, y, zLocal);
        m2.transformPosition(v);
        float s = zAnchor / v.z;
        buf.addVertex(v.x * s, v.y * s, zAnchor).setColor(r, g, b, a);
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
