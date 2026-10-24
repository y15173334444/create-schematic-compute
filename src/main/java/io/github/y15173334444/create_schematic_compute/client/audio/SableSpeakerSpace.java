package io.github.y15173334444.create_schematic_compute.client.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Sable 结构内音响的发声坐标换算（客户端，v1.2.6.2）。
 * <p>Sable 把 sub-level 内容宿主在父维度的保留「绘图网格」区：结构里方块的
 * {@code worldPosition} 是网格本地坐标，而玩家实体始终活在父世界。音符包原样携带
 * 网格坐标、客户端在父世界按坐标发声——发声点落在网格保留区、离真实结构任意远，
 * 距离衰减即「Sable 结构上的音响没有声音」（v1.2.6.2 实测）。包投递本身没问题：
 * Sable 的 ChunkMapMixin 把绘图区块的 {@code getPlayers} 重定向到追踪玩家。</p>
 * <p>本类在客户端把音响坐标经 sub-level 姿态变换到世界空间再播。注意两个口径：</p>
 * <ul>
 *   <li><b>用 {@code logicalPose}，绝不用 {@code renderPose}</b>——后者按 partialTick
 *       原地改写共享 {@code Pose3d}（set + lerp/slerp），音频网络线程调用会撕裂真正的
 *       渲染姿态（视觉毛刺）。{@code logicalPose} 是只读快照语义；与渲染插值相比最多
 *       滞后一个快照周期（~50ms），发声定位听感无差。快照由客户端主线程应用，本类跨线程
 *       读位置/朝向存在良性撕裂窗口——最坏一个音符落点偏差一次快照差量，不做同步。</li>
 *   <li><b>返回 null = 原坐标播</b>——非 Sable / 坐标不在绘图区 / Sable 版本线外 API 漂移
 *       （catch 全收）都回落到原始坐标，与修复前输出一致，不会更糟。</li>
 * </ul>
 * <p>音符包的 {@code speakerTag}（停止标记清除键）保持网格本地 {@code asLong} 不变——
 * play 与 MusicStopPacket 必须在同一空间下才能对上键，本类不参与键。</p>
 * <p>Client-side world-space resolution for speakers inside Sable structures. Sable hosts
 * sub-level content in a reserved "plot grid" region of the parent dimension: a block's
 * {@code worldPosition} is grid-local, while players always live in the parent world. Note
 * packets carry the grid position and the client plays at raw coordinates - the voice lands
 * in the reserved region, arbitrarily far from the visible structure, and distance
 * attenuation yields the reported "speaker on a Sable structure has no sound" (packet
 * delivery is fine: Sable's ChunkMapMixin redirects plot-chunk {@code getPlayers} to the
 * tracking players). See the class-level rules above (logicalPose never renderPose; null
 * falls back to the raw position). The note packet's speakerTag (stop-marker key) stays
 * grid-local on purpose - play and stop must key in the same space.</p>
 */
public final class SableSpeakerSpace {

    private static final boolean SABLE_LOADED = net.neoforged.fml.ModList.get().isLoaded("sable");

    private SableSpeakerSpace() {}

    /**
     * 音响坐标（可能是 sub-level 绘图网格本地）→ 世界空间发声坐标；无 Sable / 不在绘图区 /
     * API 漂移时返回 null（调用方用原坐标）。网络线程可调用（见类头的良性撕裂注记）。
     * Speaker pos (possibly sub-level grid-local) → world-space playback position; null when
     * Sable is absent, the pos is outside the plot grid, or the Sable API drifted (caller
     * falls back to the raw position). Safe from the network thread (see the benign-tear note).
     */
    public static Vec3 toWorldSpace(BlockPos speakerPos) {
        if (!SABLE_LOADED) return null;
        try {
            var sl = dev.ryanhcode.sable.Sable.HELPER.getContainingClient(
                new net.minecraft.world.level.ChunkPos(speakerPos));
            if (sl == null) return null;
            return sl.logicalPose().transformPosition(new Vec3(
                speakerPos.getX() + 0.5, speakerPos.getY() + 0.5, speakerPos.getZ() + 0.5));
        } catch (Throwable t) {
            return null;
        }
    }
}
