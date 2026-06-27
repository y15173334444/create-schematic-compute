package com.example.create_schematic_compute.client.renderer;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.client.GeometryConstants;
import com.example.create_schematic_compute.blocks.MonitorBlock;
import com.example.create_schematic_compute.blocks.MonitorBlockEntity;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
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
import java.util.Arrays;
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

    // Cached evaluator (reused across frames until graph changes)
    private NodeGraph cachedEvalGraph;
    private GraphEvaluator cachedEvaluator;
    private final java.util.HashMap<Integer, Float> cachedPidState = new java.util.HashMap<>();
    // Reusable input list to avoid per-frame allocation (Phase 1 optimization)
    private final java.util.ArrayList<GraphEvaluator.InputSource> reusableInputs = new java.util.ArrayList<>();

    @Override
    public void render(MonitorBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (be == null || !be.running) return;
        if (be.graph == null || be.graph.nodes.isEmpty()) return;

        // Reuse evaluator across frames until graph changes
        if (cachedEvaluator == null || cachedEvalGraph != be.graph) {
            // Release old textures when graph is replaced
            if (cachedEvalGraph != null) {
                releaseNodeTextures(cachedEvalGraph);
            }
            cachedEvalGraph = be.graph;
            cachedEvaluator = new GraphEvaluator(be.graph);
            cachedPidState.clear();
        }
        var evaluator = cachedEvaluator;
        var pidState = cachedPidState;
        // Build InputSource from synced redstone inputs (reuse list to avoid allocation)
        reusableInputs.clear();
        for (var n : be.graph.nodes) {
            if (n.type == NodeType.REDSTONE_IN) {
                long fk = com.example.create_schematic_compute.ModUtils.freqKey(n.itemParams);
                int sig = be.getRedstoneInput(fk);
                reusableInputs.add(new GraphEvaluator.InputSource(fk, sig));
            }
        }
        evaluator.evaluate(reusableInputs, pidState, 0.05f);

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
        if (be.getBlockState().hasProperty(com.example.create_schematic_compute.blocks.MonitorBlock.FACING)) {
            facingYDeg = be.getBlockState().getValue(com.example.create_schematic_compute.blocks.MonitorBlock.FACING).toYRot();
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
        float margin = 0.04f;
        float cx = -hw + margin, cy = hh - margin;
        float cw = be.screenWidth - 2 * margin, ch = be.screenLength - 2 * margin;

        // ── Border + IMAGE pixels use POSITION_COLOR with POSITION_COLOR_SHADER
        //     (rendertype_position_color = F3 debug shader — Iris preserves it; NO_CULL ensures all-angle visibility) ──
        var sceneBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        drawBorderFace(sceneBuf, m, l, r, t, b, bw, 1);
        drawBorderFace(sceneBuf, m, l, r, t, b, bw, -1);
        for (var n : be.graph.nodes) {
            if (n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE) continue;
            // X/Y/rotation signal offsets
            float ox = be.graph.getInputValue(n.id, 0, evaluator.getCurrentOutputs());
            float oy = be.graph.getInputValue(n.id, 1, evaluator.getCurrentOutputs());
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
                int frameIdx = Math.round(be.graph.getInputValue(n.id, 2, evaluator.getCurrentOutputs()));
                if (n.imageSequenceFrames != null && !n.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, n.imageSequenceFrames.size() - 1));
                    pixels = n.imageSequenceFrames.get(frameIdx);
                }
            }
            if (pixels == null || pixels.length != 256) continue;
            float rotInput = be.graph.getInputValue(n.id, rotPin, evaluator.getCurrentOutputs());
            float effectiveRot = n.displayRotation + rotInput * rotScale;
            // Clamp so rotated bounding box doesn't overflow right/bottom
            float cell = GeometryConstants.IMAGE_CELL_BLOCK * n.displayScale;
            float iw = 8f * cell, ih = 8f * cell;
            float rA = (float)Math.abs(Math.cos(Math.toRadians(effectiveRot)));
            float rB = (float)Math.abs(Math.sin(Math.toRadians(effectiveRot)));
            float bbHalfW = (iw * rA + ih * rB) / cw;
            float bbHalfH = (iw * rB + ih * rA) / ch;
            float rawX = n.layoutX + dx;
            float rawY = n.layoutY + dy;
            float cpx = Math.max(0, Math.min(1 - bbHalfW, rawX));
            float cpy = Math.max(0, Math.min(1 - bbHalfH, rawY));
            float nx = cx + cpx * cw;
            float ny = cy - cpy * ch;

            // ── Phase 4: Baked texture (1 quad instead of 256) ──
            var texLoc = (net.minecraft.resources.ResourceLocation) n.bakedTextureLocation;
            var dt = (net.minecraft.client.renderer.texture.DynamicTexture) n.bakedTexture;
            var mc2 = Minecraft.getInstance();
            // Lazy-init texture
            if (dt == null) {
                var ni = new com.mojang.blaze3d.platform.NativeImage(16, 16, true);
                for (int py = 0; py < 16; py++)
                    for (int px = 0; px < 16; px++)
                        ni.setPixelRGBA(px, py, pixels[py * 16 + px]);
                dt = new net.minecraft.client.renderer.texture.DynamicTexture(ni);
                texLoc = mc2.getTextureManager().register("monitor_pixels", dt);
                n.bakedTexture = dt;
                n.bakedTextureLocation = texLoc;
                n.bakedPixelHash = java.util.Arrays.hashCode(pixels);
            } else {
                // Update texture if pixels changed
                int newHash = java.util.Arrays.hashCode(pixels);
                if (newHash != n.bakedPixelHash) {
                    n.bakedPixelHash = newHash;
                    var ni = dt.getPixels();
                    if (ni != null) {
                        for (int py = 0; py < 16; py++)
                            for (int px = 0; px < 16; px++)
                                ni.setPixelRGBA(px, py, pixels[py * 16 + px]);
                        dt.upload();
                    }
                }
            }

            poseStack.pushPose();
            float halfW = 8f * cell, halfH = 8f * cell;
            poseStack.translate(nx + halfW, ny - halfH, 0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(-effectiveRot));
            poseStack.translate(-halfW, halfH, 0);
            var m2 = poseStack.last().pose();
            var texBuf = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL_TEX.apply(texLoc));
            texBuf.addVertex(m2, 0, 0, 0).setUv(0, 0);
            texBuf.addVertex(m2, iw, 0, 0).setUv(1, 0);
            texBuf.addVertex(m2, iw, -ih, 0).setUv(1, 1);
            texBuf.addVertex(m2, 0, -ih, 0).setUv(0, 1);
            poseStack.popPose();
        }

        // ── Text (uses font's own RenderType) ──
        for (var n : be.graph.nodes) {
            if (n.type != NodeType.TEXT && n.type != NodeType.DATA) continue;
            float nx = cx + n.layoutX * cw;
            float ny = cy - n.layoutY * ch;
            String str = n.type == NodeType.DATA
                ? String.format("%.1f", be.graph.getInputValue(n.id, 0, evaluator.getCurrentOutputs()))
                : n.displayText;
            if (str.isEmpty()) continue;
            int color = n.textColor != 0 ? n.textColor : (n.type == NodeType.DATA ? 0xFF88FF88 : 0xFFCCCCCC);
            float s = GeometryConstants.FONT_BLOCK_SCALE * n.displayScale;
            poseStack.pushPose();
            float fw = font.width(str), fh = 10f;
            poseStack.translate(nx + fw * s / 2f, ny - fh * s / 2f, 0);
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

    /** Release baked textures for all IMAGE nodes in a graph (Phase 4 cleanup). */
    private static void releaseNodeTextures(NodeGraph graph) {
        var mc = Minecraft.getInstance();
        var tm = mc.getTextureManager();
        for (var n : graph.nodes) {
            if (n.bakedTextureLocation != null) {
                tm.release((net.minecraft.resources.ResourceLocation) n.bakedTextureLocation);
            }
            n.bakedTexture = null;
            n.bakedTextureLocation = null;
            n.bakedPixelHash = 0;
        }
    }

    @Override
    public AABB getRenderBoundingBox(MonitorBlockEntity be) {
        if (!be.running || be.graph == null || be.graph.nodes.isEmpty()) {
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

    @Override public boolean shouldRenderOffScreen(MonitorBlockEntity be) { return true; }
}
