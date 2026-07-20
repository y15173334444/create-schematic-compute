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
public record ClientboundGraphEvalPacket(BlockPos pos, Map<Integer, float[]> outputs, Map<Integer, Float> debugTimes)
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
            return new ClientboundGraphEvalPacket(pos, outputs, debugTimes);
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
                sgbe.cachedEvalSnapshot = new EvalSnapshot(outputs, debugTimes);
            }
        });
    }
}
