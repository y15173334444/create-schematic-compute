package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Server→Client: synchronize Monitor graph evaluation results.
 * <p>服务端→客户端：同步 Monitor 的图评估结果。</p>
 *
 * <p>Replaces the old architecture where clients ran GraphEvaluator locally.
 * After each tick the server evaluates the graph and sends output snapshots
 * via this packet to all tracking clients. Client renderers read the cached
 * results directly without performing local computation.
 * 替代了原先客户端本地运行 GraphEvaluator 的架构。服务端每 tick 评估完成后，
 * 将输出快照通过此包发送给所有追踪客户端。客户端渲染器直接读取缓存的结果而不做本地计算。</p>
 */
public record ClientboundGraphEvalPacket(BlockPos pos, Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes,
                                      Map<Integer, Map<Integer, float[]>> subOutputs,
                                      Map<Integer, Map<Integer, Float>> subDebugTimes)
        implements CustomPacketPayload {

    public static final Type<ClientboundGraphEvalPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_eval"));

    public static final StreamCodec<ByteBuf, ClientboundGraphEvalPacket> CODEC = new StreamCodec<>() {
        @Override
        public ClientboundGraphEvalPacket decode(ByteBuf buf) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            int size = VarInt.read(buf);
            var outputs = new HashMap<Integer, float[]>(size);
            for (int i = 0; i < size; i++) {
                int nodeId = VarInt.read(buf);
                int len = VarInt.read(buf);
                float[] arr = new float[len];
                for (int j = 0; j < len; j++) arr[j] = buf.readFloat();
                outputs.put(nodeId, arr);
            }
            int dtSize = VarInt.read(buf);
            var debugTimes = new HashMap<Integer, Float>(dtSize);
            for (int i = 0; i < dtSize; i++) {
                int nodeId = VarInt.read(buf);
                float t = buf.readFloat();
                debugTimes.put(nodeId, t);
            }
            // 子图输出（ENCAPSULATION sub-graph outputs）
            int subCount = VarInt.read(buf);
            Map<Integer, Map<Integer, float[]>> subOutputs;
            if (subCount == 0) {
                subOutputs = java.util.Collections.emptyMap();
            } else {
                subOutputs = new HashMap<>(subCount);
                for (int si = 0; si < subCount; si++) {
                    int encapId = VarInt.read(buf);
                    int nodeCount = VarInt.read(buf);
                    var subMap = new HashMap<Integer, float[]>(nodeCount);
                    for (int ni = 0; ni < nodeCount; ni++) {
                        int nodeId = VarInt.read(buf);
                        int len = VarInt.read(buf);
                        float[] arr = new float[len];
                        for (int j = 0; j < len; j++) arr[j] = buf.readFloat();
                        subMap.put(nodeId, arr);
                    }
                    subOutputs.put(encapId, subMap);
                }
            }
            // 子图 debugTime（ENCAPSULATION sub-graph debugTimes）
            int subDtCount = VarInt.read(buf);
            Map<Integer, Map<Integer, Float>> subDebugTimes;
            if (subDtCount == 0) {
                subDebugTimes = java.util.Collections.emptyMap();
            } else {
                subDebugTimes = new HashMap<>(subDtCount);
                for (int si = 0; si < subDtCount; si++) {
                    int encapId = VarInt.read(buf);
                    int nodeCount = VarInt.read(buf);
                    var subMap = new HashMap<Integer, Float>(nodeCount);
                    for (int ni = 0; ni < nodeCount; ni++) {
                        int nodeId = VarInt.read(buf);
                        float t = buf.readFloat();
                        subMap.put(nodeId, t);
                    }
                    subDebugTimes.put(encapId, subMap);
                }
            }
            return new ClientboundGraphEvalPacket(pos, outputs, debugTimes, subOutputs, subDebugTimes);
        }

        @Override
        public void encode(ByteBuf buf, ClientboundGraphEvalPacket pkt) {
            BlockPos.STREAM_CODEC.encode(buf, pkt.pos);
            var outputs = pkt.outputs;
            VarInt.write(buf, outputs.size());
            for (var e : outputs.entrySet()) {
                VarInt.write(buf, e.getKey());
                float[] arr = e.getValue();
                VarInt.write(buf, arr.length);
                for (float v : arr) buf.writeFloat(v);
            }
            var dt = pkt.debugTimes;
            VarInt.write(buf, dt.size());
            for (var e : dt.entrySet()) {
                VarInt.write(buf, e.getKey());
                buf.writeFloat(e.getValue());
            }
            // 子图输出（ENCAPSULATION sub-graph outputs）
            var sub = pkt.subOutputs;
            VarInt.write(buf, sub.size());
            for (var se : sub.entrySet()) {
                VarInt.write(buf, se.getKey());
                var subMap = se.getValue();
                VarInt.write(buf, subMap.size());
                for (var ne : subMap.entrySet()) {
                    VarInt.write(buf, ne.getKey());
                    float[] arr = ne.getValue();
                    VarInt.write(buf, arr.length);
                    for (float v : arr) buf.writeFloat(v);
                }
            }
            // 子图 debugTime（ENCAPSULATION sub-graph debugTimes）
            var subDt = pkt.subDebugTimes;
            VarInt.write(buf, subDt.size());
            for (var se : subDt.entrySet()) {
                VarInt.write(buf, se.getKey());
                var subMap = se.getValue();
                VarInt.write(buf, subMap.size());
                for (var ne : subMap.entrySet()) {
                    VarInt.write(buf, ne.getKey());
                    buf.writeFloat(ne.getValue());
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var level = ctx.player().level();
            if (level == null) return;
            var be = level.getBlockEntity(pos);
            if (be instanceof io.github.y15173334444.create_schematic_compute.blocks.SyncedGraphBlockEntity sgbe) {
                sgbe.cachedEvalSnapshot = new EvalSnapshot(outputs, debugTimes, subOutputs, subDebugTimes);
            }
        });
    }
}
