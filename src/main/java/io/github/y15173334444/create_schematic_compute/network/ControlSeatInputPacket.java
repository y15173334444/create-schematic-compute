package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ControlSeatInputPacket(
    BlockPos pos,
    long keyBits,
    float mouseX, float mouseY,
    float viewYaw, float viewPitch,
    int inputMode,
    int mouseButtons,
    float gpadLX, float gpadLY, float gpadRX, float gpadRY,
    float gpadLT, float gpadRT,
    long gpadButtons,
    boolean dismount    // ~ 键请求下马
) implements CustomPacketPayload {

    public static final Type<ControlSeatInputPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "seat_input"));

    public static final StreamCodec<ByteBuf, ControlSeatInputPacket> CODEC = new StreamCodec<>() {
        @Override public ControlSeatInputPacket decode(ByteBuf buf) {
            return new ControlSeatInputPacket(
                BlockPos.STREAM_CODEC.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readInt(),
                buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readLong(),
                buf.readBoolean()
            );
        }
        @Override public void encode(ByteBuf buf, ControlSeatInputPacket p) {
            BlockPos.STREAM_CODEC.encode(buf, p.pos);
            ByteBufCodecs.VAR_LONG.encode(buf, p.keyBits);
            buf.writeFloat(p.mouseX); buf.writeFloat(p.mouseY);
            buf.writeFloat(p.viewYaw); buf.writeFloat(p.viewPitch);
            buf.writeInt(p.inputMode);
            buf.writeInt(p.mouseButtons);
            buf.writeFloat(p.gpadLX); buf.writeFloat(p.gpadLY);
            buf.writeFloat(p.gpadRX); buf.writeFloat(p.gpadRY);
            buf.writeFloat(p.gpadLT); buf.writeFloat(p.gpadRT);
            buf.writeLong(p.gpadButtons);
            buf.writeBoolean(p.dismount);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 座位实体与包内座位方块位置的一致性阈值（平方，2 格）。合法客户端发送
     * {@code vehicle.blockPosition()}，与服务端座位实体位置一致；容差只吸收客户端/服务端
     * 之间的实体同步滞后，不足以把输入投递到另一台机器的座位上。
     * <p>Position-match tolerance between the seat entity and the packet's seat block
     * (squared, 2 blocks). A legitimate client sends {@code vehicle.blockPosition()}, which
     * matches the server-side seat entity; the tolerance only absorbs entity-sync lag and is
     * far too small to deliver input onto another machine's seat.</p>
     */
    static final double SEAT_MATCH_DIST_SQ = 2.0 * 2.0;

    /**
     * P1-1 骑乘校验：发送者必须**正骑在目标座位处的 {@code ControlSeatEntity} 上**才能
     * 提交输入。合法客户端只在 {@code getVehicle() instanceof ControlSeatEntity} 且以
     * {@code vehicle.blockPosition()} 为目标时发送（见 {@code ControlSeatInputHandler}）；
     * 距离校验（128 格）此前是唯一守卫，任何玩家可每 tick 覆盖他人按键位掩码劫持驾驶、
     * 或遥控无人机器 —— 而 {@code ControlSeatBlockEntity.consumeInput} 在无骑乘者时
     * **刻意归零输入**，可见遥控并非设计意图，故校验失败时静默忽略整包（含 dismount）。
     * <p>P1-1 rider check: the sender must currently be <b>riding the {@code ControlSeatEntity}
     * at the target seat</b> to submit input. Legitimate clients only send while
     * {@code getVehicle() instanceof ControlSeatEntity} and target {@code vehicle.blockPosition()}
     * (see {@code ControlSeatInputHandler}); the distance check (128 blocks) used to be the only
     * guard, so any player could overwrite another's key mask every tick (full steering hijack)
     * or remotely drive an unmanned machine — while {@code ControlSeatBlockEntity.consumeInput}
     * deliberately zeroes input when no rider exists, so remote control is not the design
     * intent. On failure the whole packet (including dismount) is silently ignored.</p>
     *
     * @param sender  the sending entity (server-side player) / 发送实体（服务端玩家）
     * @param seatPos the seat block position claimed by the packet / 包内声称的座位方块位置
     * @return true if the sender rides the seat at seatPos / 发送者骑乘该座位时为 true
     */
    static boolean senderMayControl(net.minecraft.world.entity.Entity sender, BlockPos seatPos) {
        // 非骑乘者（vehicle 为 null 或非 ControlSeatEntity）一律拒绝
        // Non-riders (vehicle null or not a ControlSeatEntity) are always refused
        if (sender == null) return false;
        var vehicle = sender.getVehicle();
        if (!(vehicle instanceof io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity seat))
            return false;
        // 必须骑在**这个**座位上：实体位置须与包内方块位置一致（2 格容差）
        // Must ride *this* seat: the entity position must match the packet's block position
        return seatBlockMatches(seat.blockPosition(), seatPos);
    }

    /**
     * 纯几何判定：骑乘者的座位方块位置须与包内座位位置一致（容差内）。抽出为无实体
     * 依赖的静态方法，headless 测试可直接覆盖（Entity 为抽象类且 &lt;clinit&gt; 触发注册表
     * 引导，无法僵尸实例化）。
     * <p>Pure geometry: the rider's seat-block position must match the packet's within the
     * tolerance. Extracted as an entity-free static so headless tests can cover it directly
     * (Entity is abstract and its &lt;clinit&gt; triggers registry bootstrap — no zombie
     * instance possible).</p>
     */
    static boolean seatBlockMatches(BlockPos riderBlockPos, BlockPos seatPos) {
        return riderBlockPos != null && riderBlockPos.distSqr(seatPos) <= SEAT_MATCH_DIST_SQ;
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查（ControlSeat 输入来自骑乘玩家，不做编辑会话检查）
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
                // P1-1 骑乘校验：非该座位的乘客（含无人座位遥控、他人驾驶劫持）整包忽略。
                // P1-1 rider check: non-passengers of this seat (unmanned remote control,
                // hijacking another's driving) have the whole packet ignored.
                if (!senderMayControl(sp, pos)) {
                    SchematicCompute.LOGGER.debug(
                        "[ControlSeatInput] ignored non-rider input at {} from {}", pos,
                        sp.getName().getString());
                    return;
                }
            }
            // ~ 键下马（仅骑乘者可达此处 / only riders reach this point)
            if (dismount) {
                ctx.player().stopRiding();
            }
            var found = ctx.player().level().getBlockEntity(pos);
            if (found instanceof ControlSeatBlockEntity be) {
                be.keyBits = keyBits; be.mouseJoystickX = mouseX; be.mouseJoystickY = mouseY;
                be.viewYaw = viewYaw; be.viewPitch = viewPitch; be.inputMode = inputMode;
                be.mouseButtons = mouseButtons;
                be.gpadLX = gpadLX; be.gpadLY = gpadLY; be.gpadRX = gpadRX; be.gpadRY = gpadRY;
                be.gpadLT = gpadLT; be.gpadRT = gpadRT;
                be.gpadButtons = gpadButtons;
            } else {
                ControlSeatBlockEntity.storeInput(ctx.player().getUUID(), keyBits, mouseX, mouseY,
                    viewYaw, viewPitch, inputMode, mouseButtons,
                    gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons);
            }
        });
    }
}
