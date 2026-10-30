package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.TargetRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;

/** 服务端→客户端：雷达目标**轻量通道**（20Hz 精度）。
 *  目标/锁定/分配的原始变化 + **参考系**（扫描中心世界坐标 + 姿态四元数/YPR）走本包
 *  全量率推送——blip 位置、姿态换算、扫描中心移动都保持 20Hz；全量 BE NBT（整图）只在
 *  语义变化（设置/成员/名称）时推。运动数据永远不搭整图的车（最小数据面）。
 *  <p>参考系必须随包：载具**旋转**时目标绝对坐标与距离都不变（旋转保距），
 *  只看目标表会漏发——姿态换算就会慢刷。/ Server→client: the radar's **light targets
 *  channel** (20Hz precision). Raw changes to targets/locks/assignments plus the
 *  **reference frame** (scan-center world position + attitude quaternion/YPR) ride this
 *  packet at full rate — blip positions, attitude rotation and scan-center motion all
 *  keep 20Hz — while the full BE NBT (whole graph) pushes only on semantic change
 *  (settings/membership/names); motion never rides the whole-graph payload. The frame
 *  must ride along: a rotating craft leaves absolute target positions AND distances
 *  unchanged (rotation preserves distance), so a targets-only comparison would miss it
 *  and the attitude conversion would refresh slowly. */
public record RadarTargetsPacket(BlockPos pos, Frame frame, List<TargetRecord> targets,
                                 int[] locked, int[] active)
        implements CustomPacketPayload {

    /** 雷达参考系快照：扫描中心 + 姿态。NaN = 不在 Sable 载具上（沿用缓存字段语义）。
     *  Radar reference-frame snapshot: scan center + attitude. NaN = not on a Sable
     *  craft (mirrors the cache-field semantics). */
    public record Frame(float cx, float cy, float cz,
                        float yaw, float pitch, float roll,
                        float qx, float qy, float qz, float qw) {}

    public static final Type<RadarTargetsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "radar_targets"));

    public static final StreamCodec<ByteBuf, RadarTargetsPacket> CODEC = new StreamCodec<>() {
        @Override public RadarTargetsPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            BlockPos p = b.readBlockPos();
            var frame = new Frame(b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat());
            int count = b.readVarInt();
            var list = new ArrayList<TargetRecord>(count);
            for (int i = 0; i < count; i++) {
                double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
                int id = b.readVarInt();
                float dist = b.readFloat();
                String type = b.readUtf();
                String name = b.readUtf();
                list.add(new TargetRecord(x, y, z, id, dist, type, name));
            }
            int[] locked = b.readVarIntArray();
            int[] active = b.readVarIntArray();
            return new RadarTargetsPacket(p, frame, list, locked, active);
        }
        @Override public void encode(ByteBuf buf, RadarTargetsPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            var f = pkt.frame;
            if (f != null) {
                b.writeFloat(f.cx()); b.writeFloat(f.cy()); b.writeFloat(f.cz());
                b.writeFloat(f.yaw()); b.writeFloat(f.pitch()); b.writeFloat(f.roll());
                b.writeFloat(f.qx()); b.writeFloat(f.qy()); b.writeFloat(f.qz()); b.writeFloat(f.qw());
            } else {
                for (int i = 0; i < 10; i++) b.writeFloat(Float.NaN);
            }
            var targets = pkt.targets;
            b.writeVarInt(targets != null ? targets.size() : 0);
            if (targets != null) {
                for (var t : targets) {
                    b.writeDouble(t.x()); b.writeDouble(t.y()); b.writeDouble(t.z());
                    b.writeVarInt(t.entityId());
                    b.writeFloat(t.distance());
                    b.writeUtf(t.entityType() != null ? t.entityType() : "");
                    b.writeUtf(t.name() != null ? t.name() : "");
                }
            }
            b.writeVarIntArray(pkt.locked != null ? pkt.locked : new int[0]);
            b.writeVarIntArray(pkt.active != null ? pkt.active : new int[0]);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var level = ctx.player().level();
            if (level == null) return;
            if (level.getBlockEntity(pos)
                instanceof io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity radar) {
                radar.applyTargetsSync(frame, targets, locked, active);
            }
        });
    }
}
