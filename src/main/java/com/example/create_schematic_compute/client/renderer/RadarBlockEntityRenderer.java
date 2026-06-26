package com.example.create_schematic_compute.client.renderer;

import com.example.create_schematic_compute.blocks.RadarBlockEntity;
import com.example.create_schematic_compute.client.ClientSetup;
import com.example.create_schematic_compute.radar.TargetRecord;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

public class RadarBlockEntityRenderer implements BlockEntityRenderer<RadarBlockEntity> {

    // Reusable objects to avoid per-frame allocations (Phase 1 optimization)
    private final org.joml.Vector3f reusableVec = new org.joml.Vector3f();
    private org.joml.Quaternionf reusableQuat = null;

    public RadarBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(RadarBlockEntity be, float partialTick, PoseStack ps, MultiBufferSource buffer, int light, int overlay) {
        var level = be.getLevel();
        if (level == null) return;

        int scanRange = be.scanRange;
        int displayScale = be.displayScale;
        boolean running = be.running;

        ps.pushPose();
        var state = be.getBlockState();
        float facingYDeg = state.hasProperty(HorizontalDirectionalBlock.FACING)
            ? state.getValue(HorizontalDirectionalBlock.FACING).toYRot() : 0;

        boolean onSable = !Float.isNaN(be.cachedSubYaw);

        ps.translate(0.5, 0.5, 0.5);
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-facingYDeg));

        // ═══ 旋转扫描碟（BakedModel，绕几何中心旋转，完整光照） ═══
        ps.pushPose();
        ps.translate(-0.5, -0.5, -0.5); // 回到方块角
        // 旋转中心 = 模型几何中心 (8,2,8)px = (0.5, 0.125, 0.5) block
        ps.translate(0.5, 0.125, 0.5);
        ps.mulPose(com.mojang.math.Axis.YP.rotation((level.getGameTime() + partialTick) * 0.06f));
        ps.translate(-0.5, -0.125, -0.5); // 回到方块角
        ps.translate(0, 0.059375f, 0); // Y渲染位置（块角起算）
        var model = Minecraft.getInstance().getModelManager().getModel(ClientSetup.SCANNER_MODEL);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer()
            .renderModel(ps.last(), buffer.getBuffer(RenderType.solid()),
                be.getBlockState(), model, 1f, 1f, 1f, light, overlay);
        ps.popPose();

        ps.translate(be.displayX, be.displayY, be.displayZ);
        Matrix4f m = ps.last().pose();

        float axisLen = displayScale * 0.5f;
        var vc = buffer.getBuffer(MonitorRenderTypes.SCREEN_PIXEL);
        float w = 0.02f;

        float a = 0.25f;
        if (running && be.displayStyle == 0) {
            line(m, vc, 0, 0, 0,  axisLen, 0, 0, w, 0, 1, 1, a);
            line(m, vc, 0, 0, 0, -axisLen, 0, 0, w, 0, 1, 1, a);
            line(m, vc, 0, 0, 0, 0,  axisLen, 0, w, 0, 1, 0, a);
            line(m, vc, 0, 0, 0, 0, -axisLen, 0, w, 0, 1, 0, a);
            line(m, vc, 0, 0, 0, 0, 0,  axisLen, w, 1, 0, 1, a);
            line(m, vc, 0, 0, 0, 0, 0, -axisLen, w, 1, 0, 1, a);
        }
        if (running && be.displayStyle == 1) {
            cube(m, vc, 0, 0, 0, 0.05f, 1f, 1f, 1f);
            float by = -axisLen;
            q(m, vc, -axisLen, by, -axisLen, axisLen, by, -axisLen, axisLen, by, axisLen, -axisLen, by, axisLen, 0.2f, 0.4f, 0.9f, 0.3f);
        }

        double cx = onSable ? be.cachedSubWorldX : be.getBlockPos().getX() + 0.5;
        double cy = onSable ? be.cachedSubWorldY : be.getBlockPos().getY() + 0.5;
        double cz = onSable ? be.cachedSubWorldZ : be.getBlockPos().getZ() + 0.5;

        synchronized (be.targets) {
        if (running) {
        // 提升到循环外：四元数只创建一次
        org.joml.Quaternionf invQ = null;
        if (onSable && !Float.isNaN(be.cachedSubQw)) {
            if (reusableQuat == null) reusableQuat = new org.joml.Quaternionf();
            invQ = reusableQuat.set(be.cachedSubQx, be.cachedSubQy, be.cachedSubQz, be.cachedSubQw).conjugate();
        }
        float facingRad = (float) Math.toRadians(facingYDeg);
        for (var t : be.targets) {
            float dx = (float)(t.x() - cx);
            float dy = (float)(t.y() - cy);
            float dz = (float)(t.z() - cz);
            var v = reusableVec.set(dx, dy, dz);
            if (invQ != null) v.rotate(invQ);
            v.rotateY(facingRad);

            float rx = v.x / scanRange * axisLen;
            float ry = v.y / scanRange * axisLen;
            float rz = v.z / scanRange * axisLen;

            if (Math.abs(rx) > axisLen || Math.abs(ry) > axisLen || Math.abs(rz) > axisLen) continue;

            float cr = 0f, cg = 0.667f, cb = 1f;
            if (TargetRecord.TYPE_MOB.equals(t.entityType())) { cr = 1f; cg = 0.667f; cb = 0f; }
            else if (TargetRecord.TYPE_SABLE.equals(t.entityType())) { cr = 0.667f; cg = 0f; cb = 1f; }
            cube(m, vc, rx, ry, rz, 0.03f, cr, cg, cb);

            boolean highlight = be.lockMode == 1
                ? be.lockedTargets.contains(t.entityId())
                : be.activeTargets.contains(t.entityId());
            if (highlight) wireframeBox(m, vc, rx, ry, rz, 0.06f, cr, cg, cb, 0.8f);
        }
        } // if (running)
        } // synchronized
        ps.popPose();
    }

    private void wireframeBox(Matrix4f m, VertexConsumer vc, float cx, float cy, float cz, float s,
                               float r, float g, float b, float a) {
        float[][] edges = {
            {cx-s,cy-s,cz-s, cx+s,cy-s,cz-s}, {cx+s,cy-s,cz-s, cx+s,cy-s,cz+s},
            {cx+s,cy-s,cz+s, cx-s,cy-s,cz+s}, {cx-s,cy-s,cz+s, cx-s,cy-s,cz-s},
            {cx-s,cy+s,cz-s, cx+s,cy+s,cz-s}, {cx+s,cy+s,cz-s, cx+s,cy+s,cz+s},
            {cx+s,cy+s,cz+s, cx-s,cy+s,cz+s}, {cx-s,cy+s,cz+s, cx-s,cy+s,cz-s},
            {cx-s,cy-s,cz-s, cx-s,cy+s,cz-s}, {cx+s,cy-s,cz-s, cx+s,cy+s,cz-s},
            {cx+s,cy-s,cz+s, cx+s,cy+s,cz+s}, {cx-s,cy-s,cz+s, cx-s,cy+s,cz+s},
        };
        for (float[] e : edges) {
            float dx = e[3]-e[0], dy = e[4]-e[1], dz = e[5]-e[2];
            float len = (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
            if (len < 0.001f) continue;
            float hw = 0.005f, ux, uy, uz;
            if (Math.abs(dy/len) < 0.9f) { ux=0; uy=1; uz=0; } else { ux=1; uy=0; uz=0; }
            float vx=uy*dz-uz*dy, vy=uz*dx-ux*dz, vz=ux*dy-uy*dx;
            float vl=(float)Math.sqrt(vx*vx+vy*vy+vz*vz);
            vx=vx/vl*hw; vy=vy/vl*hw; vz=vz/vl*hw;
            float wx=dy*vz-dz*vy, wy=dz*vx-dx*vz, wz=dx*vy-dy*vx;
            float wl=(float)Math.sqrt(wx*wx+wy*wy+wz*wz);
            wx=wx/wl*hw; wy=wy/wl*hw; wz=wz/wl*hw;
            q(m, vc, e[0]+vx,e[1]+vy,e[2]+vz, e[3]+vx,e[4]+vy,e[5]+vz, e[3]+wx,e[4]+wy,e[5]+wz, e[0]+wx,e[1]+wy,e[2]+wz, r,g,b,a);
            q(m, vc, e[0]+wx,e[1]+wy,e[2]+wz, e[3]+wx,e[4]+wy,e[5]+wz, e[3]-vx,e[4]-vy,e[5]-vz, e[0]-vx,e[1]-vy,e[2]-vz, r,g,b,a);
            q(m, vc, e[0]-vx,e[1]-vy,e[2]-vz, e[3]-vx,e[4]-vy,e[5]-vz, e[3]-wx,e[4]-wy,e[5]-wz, e[0]-wx,e[1]-wy,e[2]-wz, r,g,b,a);
            q(m, vc, e[0]-wx,e[1]-wy,e[2]-wz, e[3]-wx,e[4]-wy,e[5]-wz, e[3]+vx,e[4]+vy,e[5]+vz, e[0]+vx,e[1]+vy,e[2]+vz, r,g,b,a);
        }
    }

    private void line(Matrix4f m, VertexConsumer vc, float x1, float y1, float z1, float x2, float y2, float z2,
                      float w, float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001f) return;
        float ux, uy, uz;
        if (Math.abs(dy / len) < 0.9f) { ux = 0; uy = 1; uz = 0; } else { ux = 1; uy = 0; uz = 0; }
        float vx = uy * dz - uz * dy, vy = uz * dx - ux * dz, vz = ux * dy - uy * dx;
        float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        vx = vx / vl * w; vy = vy / vl * w; vz = vz / vl * w;
        float wx = dy * vz - dz * vy, wy = dz * vx - dx * vz, wz = dx * vy - dy * vx;
        float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        wx = wx / wl * w; wy = wy / wl * w; wz = wz / wl * w;
        q(m, vc, x1+vx, y1+vy, z1+vz, x2+vx, y2+vy, z2+vz, x2+wx, y2+wy, z2+wz, x1+wx, y1+wy, z1+wz, r, g, b, a);
        q(m, vc, x1+wx, y1+wy, z1+wz, x2+wx, y2+wy, z2+wz, x2-vx, y2-vy, z2-vz, x1-vx, y1-vy, z1-vz, r, g, b, a);
        q(m, vc, x1-vx, y1-vy, z1-vz, x2-vx, y2-vy, z2-vz, x2-wx, y2-wy, z2-wz, x1-wx, y1-wy, z1-wz, r, g, b, a);
        q(m, vc, x1-wx, y1-wy, z1-wz, x2-wx, y2-wy, z2-wz, x2+vx, y2+vy, z2+vz, x1+vx, y1+vy, z1+vz, r, g, b, a);
    }

    private void cube(Matrix4f m, VertexConsumer vc, float cx, float cy, float cz, float s, float r, float g, float b) {
        q(m, vc, cx+s, cy-s, cz-s, cx+s, cy+s, cz-s, cx+s, cy+s, cz+s, cx+s, cy-s, cz+s, r, g, b, 0.5f);
        q(m, vc, cx-s, cy-s, cz+s, cx-s, cy+s, cz+s, cx-s, cy+s, cz-s, cx-s, cy-s, cz-s, r, g, b, 0.5f);
        q(m, vc, cx-s, cy+s, cz-s, cx+s, cy+s, cz-s, cx+s, cy+s, cz+s, cx-s, cy+s, cz+s, r, g, b, 0.5f);
        q(m, vc, cx-s, cy-s, cz+s, cx+s, cy-s, cz+s, cx+s, cy-s, cz-s, cx-s, cy-s, cz-s, r, g, b, 0.5f);
        q(m, vc, cx+s, cy-s, cz+s, cx+s, cy+s, cz+s, cx-s, cy+s, cz+s, cx-s, cy-s, cz+s, r, g, b, 0.5f);
        q(m, vc, cx-s, cy-s, cz-s, cx-s, cy+s, cz-s, cx+s, cy+s, cz-s, cx+s, cy-s, cz-s, r, g, b, 0.5f);
    }

    private void q(Matrix4f m, VertexConsumer vc, float x1, float y1, float z1, float x2, float y2, float z2,
                   float x3, float y3, float z3, float x4, float y4, float z4, float r, float g, float b, float a) {
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(m, x4, y4, z4).setColor(r, g, b, a);
    }

    // NOTE: AABB.INFINITE is used because radar must render even when the block is far away
    // (especially on moving Sable structures where the render extent is unpredictable).
    // The performance cost is negligible for radars — there are typically only 1-2 per world.
    @Override public AABB getRenderBoundingBox(RadarBlockEntity be) { return AABB.INFINITE; }
    @Override public boolean shouldRenderOffScreen(RadarBlockEntity be) { return true; }
    @Override public int getViewDistance() { return 256; }
}
