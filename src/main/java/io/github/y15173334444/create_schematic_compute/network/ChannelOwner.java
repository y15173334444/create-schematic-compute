package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;

/**
 * 唯一标识一个 BUS_OUT / 私有发布节点：所属方块位置 + **图域** + 图内节点 ID。
 * <p>known-open-issues 条目『频道清理残余豁口』：节点 id 只在单个 NodeGraph 内唯一——主图
 * 与封装子图的 id 计数器各自独立、数值必然撞号，owner 键缺图域时注册被当成「同 owner
 * 重注册」静默接管对方的 internalMap 引用、改名走人按 owner 匹配误释放对方的条目与定义。
 * 图域 = op 既有语义的 {@code ownerNodeId}（子图 op 已携带承载它的 ENCAP 节点 id）——
 * 主图域为 -1，子图域为 ENCAP 节点 id，owner 门控在撞号面前恢复真值。</p>
 * <p>Uniquely identifies a BUS_OUT / private publisher: the hosting block position, the
 * <b>graph domain</b>, and the node id within that graph. Register entry "residual channel
 * cleanup gaps": node ids are unique only within one NodeGraph — the main graph and
 * encapsulation sub-graphs run independent counters that necessarily collide, so an owner
 * key without the graph domain let a registration pose as a "same-owner re-registration"
 * (silently taking over the peer's internalMap reference) and a rename-departure release
 * the peer's entry and definition by owner match. The graph domain = the op's existing
 * {@code ownerNodeId} semantics (sub-graph ops already carry the hosting ENC node's id) —
 * -1 for the main graph, the ENC node id for a sub-graph; owner gating regains truth under
 * collisions.</p>
 */
public record ChannelOwner(BlockPos pos, int ownerNodeId, int nodeId) {
    /** 主图域兼容构造（ownerNodeId = -1）：全部主图遍历与既有调用点的同款口径——主图
     *  walk（注册/释放/快照/自愈）天然住主图域，二参形态即显式主图语义。
     *  <p>Main-graph compatibility constructor (ownerNodeId = -1): the shared shape of every
     *  main-graph walk and existing call site — main-graph passes (registration / release /
     *  snapshots / self-heal) natively live in the main domain, so the 2-arg form states that
     *  domain explicitly.</p> */
    public ChannelOwner(BlockPos pos, int nodeId) {
        this(pos, -1, nodeId);
    }

    @Override
    public String toString() {
        return "[" + pos.toShortString() + "]" + (ownerNodeId >= 0 ? "sub" + ownerNodeId + ":" : "")
            + "#" + nodeId;
    }
}
