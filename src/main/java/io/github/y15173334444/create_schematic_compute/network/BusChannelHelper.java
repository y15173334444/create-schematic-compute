package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Shared bus channel lifecycle methods, extracted from the four
 * GraphBlockEntity implementations to eliminate ~160 lines of duplication.
 * <p>共享总线频道生命周期方法，从四个 GraphBlockEntity 实现中提取，消除了约 160 行重复代码。</p>
 *
 * <p>All methods are safe to call on either side; they no-op on the client
 * and when {@code level} is null.
 * 所有方法可在任意端安全调用；在客户端和 level 为 null 时为空操作。</p>
 */
public final class BusChannelHelper {

    private BusChannelHelper() {}

    // ── Channel registration / unregistration / 频道注册 / 取消注册 ──────────────

    /** 频道注册结果：冲突旗标是否有变化（调用方触发全量同步）+ 需要广播频段定义的频道名。
     *  Channel registration result: whether any conflict flag changed (caller triggers a full
     *  sync) and the channel names whose band definitions need broadcasting. */
    public record ChannelRegistration(boolean anyConflictChanged, java.util.List<String> bandBroadcasts) {}

    /** Register every BUS_OUT node in {@code graph} with {@link SignalBus#registerChannel}.
     *  将 graph 中每个 BUS_OUT 节点注册到 SignalBus.registerChannel。
     *  On success also immediately syncs bands to {@code BAND_REGISTRY} so that
     *  other clients' editors can detect cross-block conflicts before the next tick.
     *  成功时立即将频段同步到 BAND_REGISTRY，使其他客户端编辑器能在下个 tick 前检测跨方块冲突。
     *  @return true if at least one node changed conflict state (caller should trigger a full sync) / 若至少一个节点的冲突状态变化则返回 true（调用方应触发完整同步） */
    public static boolean registerChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return false;
        var reg = registerGraphChannels(graph, pos);
        if (level instanceof ServerLevel sl) broadcastBandDefinitions(sl, pos, reg.bandBroadcasts());
        return reg.anyConflictChanged();
    }

    /** 注册内核（无 Level 判定、无客户端广播）：供首 tick 注册与 BUS_OUT 改名迁移共用，
     *  幂等 —— 同 owner 同 map 重复注册无副作用。返回需广播的频段频道名与冲突旗标变化。
     *  Registration core (no level gate, no client broadcast): shared by the first-tick
     *  registration and the BUS_OUT rename migration; idempotent — a same-owner same-map
     *  re-registration is a no-op. Returns the band broadcasts and conflict-flag changes. */
    static ChannelRegistration registerGraphChannels(NodeGraph graph, BlockPos pos) {
        boolean anyConflict = false;
        var broadcasts = new java.util.ArrayList<String>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                if (n.busInternalMap == null) n.busInternalMap = new HashMap<>();
                boolean ok = SignalBus.registerChannel(n.signalName, n.busInternalMap,
                    new ChannelOwner(pos, n.id));
                // 注：此处不做"过时 owner 强制清理"——注册失败只标冲突，所有权保持
                // "首个注册者获胜"稳定语义。残留 owner 回收由 recoverConflictedChannels
                // 的 chunk-loaded 超时机制处理（见下方），避免误杀其他方块存活的同名牌。
                // Note: no stale-owner force-cleanup here — a failed registration only
                // marks conflict, preserving stable first-registrant-wins semantics.
                // Residual-owner reclamation is handled by recoverConflictedChannels'
                // chunk-loaded timeout, avoiding stealing a live peer's channel.
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
                // EN: Registration succeeded → immediately sync bands to BAND_REGISTRY and broadcast to clients
                // 注册成功 → 立即同步 bands 到 BAND_REGISTRY 并广播客户端
                if (ok && n.signalBands != null && !n.signalBands.isEmpty()) {
                    SignalBus.registerBands(n.signalName, n.signalBands);
                    n.bandsDirty = false;
                    broadcasts.add(n.signalName);
                }
            } else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty()) {
                // 私有频道占用（BUS 同款）：首个注册者获胜，被其他 owner 占用即标冲突旗标——
                // 冲突节点不写值、不算定义，写入权属于首个注册者。
                // Private channel occupancy (BUS discipline): first registrant wins; a name held
                // by another owner flags the node — a conflicted node writes nothing and defines
                // nothing.
                boolean ok = SignalBus.registerPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
            }
        }
        return new ChannelRegistration(anyConflict, broadcasts);
    }

    /**
     * BUS_OUT 改名迁移（用户症状：改名后 BUS_IN 查找不到频道，重新拉取权威图名称仍在）。
     * <p>频道注册只在宿主首 tick 跑一次（{@code busRegistrationPending}），改名后
     * {@code CHANNELS} 条目仍挂旧名、新名无条目 —— BUS_IN 读 {@code channelMap(新名)} 为
     * null，{@code BAND_REGISTRY} 新名也没有频段定义。这里按 owner 撤掉旧名条目、按同图
     * 无他人引用时清理旧名残留，并重跑图内注册（幂等）——频段定义与冲突旗标随名字落地。
     * BUS_OUT rename migration (user symptom: after a rename BUS_IN cannot find the channel,
     * yet re-pulling the authoritative graph shows the name intact). Channel registration runs
     * once per host lifetime ({@code busRegistrationPending}); after a rename the CHANNELS entry
     * still hangs on the old name and the new name has none — BUS_IN reads
     * {@code channelMap(new)} as null and BAND_REGISTRY has no definition under the new name.
     * This drops the old-name entry by owner, clears the old name's residue when no same-graph
     * node still uses it, and re-runs the graph registration (idempotent) — band definitions and
     * the conflict flag land on the new name.
     *
     * @param renamed 已改名的 BUS_OUT（signalName 为新名）/ the renamed BUS_OUT (signalName = new name)
     * @param oldName 改名前的频道名 / the channel name before the rename
     * @return 注册结果（调用方按需广播频段定义 / registration result for the caller to broadcast)
     */
    public static ChannelRegistration applyBusOutRename(NodeGraph graph, BlockPos pos, GraphNode renamed,
                                                        String oldName, @Nullable Level level) {
        if (graph == null || renamed == null) return new ChannelRegistration(false, java.util.List.of());
        String newName = renamed.signalName;
        if (oldName != null && !oldName.isEmpty() && !oldName.equals(newName)) {
            // 按 owner 撤旧名条目（refCount 归零即顺带清残留；他人条目不动 —— 同名共用靠冲突纪律）
            // Drop the old-name entry by owner (ref-count zero clears its residue; a foreign entry
            // is left alone — same-name sharing is the conflict discipline's business).
            boolean released = SignalBus.unregisterChannel(oldName, new ChannelOwner(pos, renamed.id));
            // 与 commitBusBox 同口径：同图还有他人引用旧名就不清全局残留。
            // **owner 门控**：本就不是我们的名（冲突节点改名走人）绝不清全局数据 ——
            // 旧逻辑的 clearBus 会把真正 owner 的频段定义与值一并抹掉（跨 owner 破坏）。
            // Same rule as commitBusBox: don't clear the global residue while a same-graph node
            // still references the old name. **Ownership-gated**: if the name was never ours
            // (a conflicted node renaming away), never touch the global data — the old clearBus
            // wiped the real owner's band definitions and values along with it.
            boolean othersUseOld = false;
            for (var n : graph.nodes) {
                if (n == renamed) continue;
                if ((n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
                    && oldName.equals(n.signalName)) { othersUseOld = true; break; }
            }
            if (!othersUseOld && (released || SignalBus.getChannel(oldName) == null)) {
                SignalBus.clearBus(oldName);
                // 死名标记：发布方确证离开 → 旧名 BUS_IN 收敛成空（issue #11 清空死名），
                // 与「未定义」（瞬时缺席，跳过收敛保连线）严格区分。
                // Dead-name marker: the publisher provably left → BUS_INs on the old name converge
                // to empty (issue #11's dead-name cleanup), strictly distinct from "undefined"
                // (transient absence, skipped to preserve wires).
                SignalBus.retireBands(oldName);
            }
        }
        // 重跑图内注册（幂等）：新名条目 + 频段定义 + 冲突旗标随名字落地。
        // "" → 新名 也走这里（新建 BUS_OUT 后首次命名同样丢频道 —— 同一根因）。
        // Re-run the graph registration (idempotent): the new-name entry, band definitions and
        // the conflict flag land with the name. "" → new goes through here too (naming a freshly
        // added BUS_OUT loses the channel the same way — same root cause).
        var reg = registerGraphChannels(graph, pos);
        renamed.bandsDirty = true; // 评估器下轮重算音频标志 / evaluator recomputes audio flags next tick
        if (level instanceof ServerLevel sl) broadcastBandDefinitions(sl, pos, reg.bandBroadcasts());
        return reg;
    }

    /** 把频段定义广播给追踪客户端（首 tick 注册与改名迁移共用）。 / Broadcast band definitions
     *  to tracking clients (shared by the first-tick registration and the rename migration). */
    private static void broadcastBandDefinitions(ServerLevel sl, BlockPos pos, java.util.List<String> names) {
        for (var name : names) {
            var bands = SignalBus.getBands(name);
            PacketDistributor.sendToPlayersTrackingChunk(sl,
                new ChunkPos(pos),
                new BusBandSyncPacket(pos, name, bands != null ? bands : Collections.emptyList()));
        }
    }

    /** Unregister every BUS_OUT node in {@code graph} from {@link SignalBus#unregisterChannel}. / 将 graph 中每个 BUS_OUT 节点从 SignalBus.unregisterChannel 取消注册。 */
    public static void unregisterChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                SignalBus.unregisterChannel(n.signalName, new ChannelOwner(pos, n.id));
                n.busConflict = false;
            } else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty()) {
                SignalBus.unregisterPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
                n.busConflict = false;
            }
        }
    }

    // ── Client band-registry cleanup / 客户端频段注册表清理 ───────────────────────

    /** Send an empty {@link BusBandSyncPacket} for every unique BUS_OUT name in {@code graph}
     *  so that tracking clients remove stale entries from their {@code BAND_REGISTRY}.
     *  Also clears PRIVATE_OUT signal entries from {@link SignalBus#SIGNALS} to prevent memory leaks.
     *  Called before a block entity is unloaded / destroyed.
     *  为 graph 中每个唯一 BUS_OUT 名称发送空 BusBandSyncPacket，使追踪客户端从其 BAND_REGISTRY 中移除过期条目。
     *  同时清除 SignalBus.SIGNALS 中的 PRIVATE_OUT 信号条目以防止内存泄漏。在方块实体卸载/销毁前调用。 */
    public static void cleanupClientBands(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        if (level instanceof ServerLevel sl) {
            var names = new HashSet<String>();
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) names.add(n.signalName);
                else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty())
                    // 按 owner 释放（值 + 占用 + 定义）——冲突节点（名被对端占用）不得清掉对端的频道
                    // Owner-checked release (value + occupancy + definition) — a conflicted node
                    // must never nuke the peer's channel of the same name.
                    SignalBus.unregisterPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
            }
            for (var name : names) {
                PacketDistributor.sendToPlayersTrackingChunk(sl,
                    new ChunkPos(pos),
                    new BusBandSyncPacket(pos, name, Collections.emptyList()));
            }
        }
    }

    /** For every BUS_OUT name present in {@code oldGraph} but absent in {@code newGraph},
     *  send an empty {@link BusBandSyncPacket} so clients drop the stale band list.
     *  对于存在于 oldGraph 但不在 newGraph 中的每个 BUS_OUT 名称，发送空 BusBandSyncPacket 使客户端丢弃过期的频段列表。 */
    public static void syncDeletedBusNames(NodeGraph oldGraph, @Nullable NodeGraph newGraph,
                                            BlockPos pos, @Nullable Level level) {
        if (!(level instanceof ServerLevel sl) || oldGraph == null) return;
        var oldBusNames = new HashSet<String>();
        for (var n : oldGraph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) oldBusNames.add(n.signalName);
        if (oldBusNames.isEmpty()) return;
        for (var name : oldBusNames) {
            boolean stillExists = false;
            if (newGraph != null) {
                for (var n : newGraph.nodes) {
                    if (n.type == NodeType.BUS_OUT && n.signalName.equals(name)) {
                        stillExists = true; break;
                    }
                }
            }
            if (!stillExists) {
                PacketDistributor.sendToPlayersTrackingChunk(sl,
                    new ChunkPos(pos),
                    new BusBandSyncPacket(pos, name, Collections.emptyList()));
            }
        }
    }

    // ── BUS_IN band resolution (the single server-side resolution point) ──────
    // ── BUS_IN 频段解析（服务端唯一解析点） ─────────────────────────────────────

    /** Resolve the band list a BUS_IN must adopt for {@code channelName} — the **single**
     *  resolution point for this question (issue #11).
     *  <p>Order: a <b>non-conflicted BUS_OUT</b> with the same name in the <b>same graph</b> wins —
     *  it is the channel's definition owner, and that input is replicated graph data so every side
     *  would compute the same thing. Otherwise the global band registry (a per-side cache — the
     *  input that used to let sides disagree), otherwise empty (a name with no publisher).</p>
     *  <p>A <b>conflicted</b> BUS_OUT must never serve as the source: it does not own the channel
     *  (it neither registers bands nor publishes values), so its list is <b>not</b> the channel's
     *  definition. Letting it through made a BUS_IN inside the losing block adopt the loser's band
     *  graph while every other block resolved the winner's — one graph state, two answers.
     *  Only a BUS_OUT can be a definition source; any other same-name node falls through to the
     *  registry.</p>
     *  <p><b>Callers must be server-side.</b> The result is pushed to every editor as an
     *  authoritative {@code SET_BANDS}, so clients never resolve it themselves.</p>
     *  解析 BUS_IN 应为某个频道名采用的频段列表 —— 该问题的**唯一**解析点（issue #11）。
     *  <p>顺序：**同图**内同名的**非冲突 BUS_OUT** 优先 —— 它是频道定义的持有者，且该输入是被
     *  复制的图数据，各端本就一致；其次全局频段注册表（每端一份的缓存 —— 正是它曾让各端得出
     *  不同结果）；都没有则为空（该名字完全没有发布方）。</p>
     *  <p>**冲突的 BUS_OUT 绝不能当来源**：它并不拥有该频道（既不注册频段也不发布值），其列表
     *  **不是**频道定义。放它进来会让「输了的那一侧」图内的 BUS_IN 采纳输家的频段图，而其他方块
     *  解析到赢家的 —— 同一份图状态得出两个答案。只有 BUS_OUT 能当定义来源，其他同名节点一律
     *  回落到注册表。</p>
     *  <p><b>调用方必须是服务端。</b>结果会作为权威 SET_BANDS 下发给全部编辑者，
     *  客户端不再自行解析。</p> */
    public static List<String> resolveBusInBands(NodeGraph graph, @Nullable GraphNode self, String channelName) {
        if (graph == null || channelName == null || channelName.isEmpty()) return new ArrayList<>();
        for (var n : graph.nodes) {
            if (n != self && n.type == NodeType.BUS_OUT && !n.busConflict
                && n.signalName.equals(channelName) && n.bandCount() > 0)
                return new ArrayList<>(n.signalBands);
        }
        var gb = SignalBus.getBands(channelName);
        return (gb != null && !gb.isEmpty()) ? new ArrayList<>(gb) : new ArrayList<>();
    }

    // ── Local BUS_OUT conflict merge (the only client-side conflict inference) ──
    // ── 本地 BUS_OUT 冲突合并（客户端唯一的冲突推断） ────────────────────────────

    /** Merge the **locally provable** part of BUS_OUT conflict state into {@code graph} (issue #12).
     *  <p>A BUS_OUT's channel can also be owned by a <b>peer block</b> — but that is knowable only
     *  on the server, so it is <b>never inferred here</b>. A client cannot distinguish the band
     *  registry's own echo from a peer's claim, and the earlier attempt to guess from that registry
     *  produced false conflicts — which is why the flag is only ever <b>raised</b> by a same-graph
     *  duplicate name, and the server-synced value is never lowered.</p>
     *  <p>Locally provable: anything that is not a named BUS_OUT cannot hold a channel, so its flag
     *  is cleared.</p>
     *  把 BUS_OUT 冲突状态中**本地可证明**的部分合并进 {@code graph}（issue #12）。
     *  <p>一个 BUS_OUT 的频道也可能被**对端方块**占用 —— 但那只有服务端知道，这里**绝不推断**。
     *  客户端无法区分频段表里的条目是自身回声还是对端声明，此前正是这种猜测产生了假冲突；
     *  因此标志仅由同图重名**抬高**，绝不下调服务端同步来的值。</p>
     *  <p>本地可证明：不是「有名字的 BUS_OUT」的节点不可能持有频道，标志清零。</p> */
    public static void mergeLocalBusConflicts(NodeGraph graph) {
        if (graph == null) return;
        for (var n : graph.nodes) {
            boolean publisher = n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT;
            if (!publisher || n.signalName == null || n.signalName.isEmpty()) {
                n.busConflict = false;
                continue;
            }
            boolean sameGraphDuplicate = false;
            for (var other : graph.nodes) {
                // 重名判定只在同表内（BUS 与私有机制上分表隔离，跨表同名不是冲突）
                // Duplicates are per table — the tables are isolated, so a cross-table
                // name match is not a conflict.
                if (other != n && other.type == n.type && n.signalName.equals(other.signalName)) {
                    sameGraphDuplicate = true;
                    break;
                }
            }
            // 只增不减：绝不下调随图同步来的服务端权威值。
            // Raise only — never lower the authoritative value that arrived with the graph.
            n.busConflict = n.busConflict || sameGraphDuplicate;
        }
    }

    // ── Client graph sync / 客户端图同步 ──────────────────────────────────

    /** Apply a server-pushed band list to matching BUS_IN / BUS_OUT nodes in the local graph.
     *  Only connections on bands that were actually removed are pruned (matched by band name / pinId).
     *  将服务端推送的频段列表应用到本地图中匹配的 BUS_IN / BUS_OUT 节点。
     *  仅删除被实际移除的频段（按频段名 / pinId 匹配）上的连接。 */
    public static void syncBandsFromServer(String busName, List<String> bands, NodeGraph graph) {
        if (graph == null) return;
        List<String> newBands = bands != null ? bands : Collections.emptyList();
        for (var n : graph.nodes) {
            if ((n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
                && n.signalName.equals(busName)) {
                // Don't overwrite conflicted BUS_OUTs — their bands belong to them,
                // not to the channel owner that broadcast this sync.
                // 不要覆盖冲突的 BUS_OUT —— 其频段属于自身，不属于广播此同步的频道所有者。
                if (n.type == NodeType.BUS_OUT && n.busConflict) continue;
                if (!newBands.equals(n.signalBands)) {
                    // 频段对齐唯一规则（NodeGraph.reconcileBands）：改名重绑 pinId 不剪线，
                    // 增删按名剪除 —— 与 SET_BANDS/上传/收敛共用同一实现。重排依旧保线
                    //（名字集合未变）。
                    // The single band alignment rule: renames rebind pinIds (wires kept),
                    // additions/removals prune by name - shared with SET_BANDS, the upload
                    // and convergence. Reordering still keeps wires (the name set is unchanged).
                    graph.reconcileBands(n, newBands);
                    // legacy 索引回退：BUS_IN 的输入引脚是**索引绑定**的（GraphNode.inputPinId 只对
                    // BUS_OUT 返回频段名），因此频段减少后落到新范围之外的连线要按索引清掉
                    // ——与 releaseOldBusName / convergeBusInBands 的既有做法一致。
                    // Legacy index fallback: a BUS_IN's input pins are **index-bound**
                    // (GraphNode.inputPinId returns a band name only for BUS_OUT), so connections
                    // whose index fell outside the new range are dropped — consistent with
                    // releaseOldBusName and convergeBusInBands.
                    final int keptCount = n.signalBands.size();
                    graph.connections.removeIf(c -> c.toId == n.id && c.toPin >= keptCount);
                    graph.rebuildNodeMap(); // invalidate inputCache / 刷新 inputCache
                    graph.rebuildInputCache();
                }
            }
        }
    }

    // ── BUS_IN band convergence (the server-side invariant) / BUS_IN 频段收敛（服务端不变量） ──

    /** Keep every BUS_IN's band list equal to the channel definition — the server-side invariant
     *  behind issue #15.
     *  <p>A BUS_IN's list <b>is</b> the pin structure it exposes. It used to be refreshed only in
     *  the block where a change was initiated (a band upload or a rename); everything else relied
     *  on the client re-deriving it from its own band registry — precisely the divergence issue #11
     *  removed. Without that crutch a stale list stayed stale in every other block forever, and
     *  reopening the editor did not help because the <b>server's own copy</b> was stale
     *  (issue #15).</p>
     *  <p>Resolution still goes through {@link #resolveBusInBands}, so exactly one resolution rule
     *  remains; the result is cached per channel name for this pass (only a BUS_OUT can be a
     *  definition source, so the "self" exclusion is irrelevant here). Removal semantics match
     *  {@code SET_BANDS}: connections on bands that actually disappeared are pruned.</p>
     *  <p><b>Absence is not a definition</b> (transient-publisher guard): when the resolution comes
     *  back empty <i>and</i> {@link SignalBus#getChannel} holds no entry for the name, no loaded
     *  publisher currently owns the channel. The empty list is then an inference from absence, not
     *  an authoritative value — converging on it pruned every input connection of the BUS_IN and
     *  persisted the loss while the publisher was merely out of sight (its chunk unloaded, its host
     *  ticking later on a fresh server, the block broken). Such channels are <b>skipped</b> and the
     *  current list is kept: the authoritative {@code SET_BANDS} a rename ships already empties a
     *  BUS_IN just pointed at a dead name (issue #11), and convergence resumes — pruning only
     *  genuinely-removed bands — once a publisher registers again. A <i>loaded</i> publisher that
     *  defines zero bands still converges to empty: its CHANNELS entry proves the empty definition.</p>
     *  <p>Network-free — the caller notifies clients (see {@link #syncIfBandsChanged}).</p>
     *  让每个 BUS_IN 的频段列表等于频道定义 —— issue #15 背后的服务端不变量。
     *  <p>BUS_IN 的列表**就是**它暴露的引脚结构。它过去只在「发起变更的那个方块」里被刷新
     *  （频段上传或改名）；其余位置靠客户端按自己的频段表重新推导来遮掩 —— 而那正是 issue #11
     *  去掉的分叉源。去掉之后，其它方块里的过期列表就永远过期，且重开编辑器也没用，因为
     *  **服务端自己那份就是旧的**（issue #15）。</p>
     *  <p>解析仍统一走 {@link #resolveBusInBands}，保证只有一条解析规则；本轮按频道名缓存解析结果
     *  （只有 BUS_OUT 能当定义来源，因此「排除自身」在这里无实际作用）。移除语义与
     *  {@code SET_BANDS} 一致：真正消失的频段上的连线会被剪掉。</p>
     *  <p>**缺席不是定义**（发布方瞬时缺席守卫）：解析为空**且** {@link SignalBus#getChannel} 无该
     *  名字条目时，当前没有任何已加载的发布方持有该频道 —— 此时的「空」是从缺席推出的结论，
     *  **不是权威值**；曾因照常收敛，把发布方只是暂时不在场（所在区块被卸载、重启首 tick 注册
     *  顺序靠后、方块被拆除）的 BUS_IN 输入连线全部剪掉并落盘。这类频道本轮**跳过**、保留原列表：
     *  改名路径下发的权威 {@code SET_BANDS} 已负责把刚指到死名上的 BUS_IN 清空（issue #11）；
     *  等发布方重新注册、定义重新可证明时收敛自动恢复 —— 且只剪真正消失的频段。**已加载**的
     *  发布方定义了零频段仍收敛为空：它的 CHANNELS 条目证明了「空定义」本身。</p>
     *  <p>不涉及网络 —— 返回**变化了的频道名 → 收敛后的列表**，由调用方经**节点数据通道**
     *  （{@code BusBandSyncPacket}，按方块 + 频道名推、客户端只改匹配节点）下发；
     *  **不要**改用整图 NBT 推送，那会冲掉正在进行的编辑。</p>
     *  @return 发生变化的频道名 → 收敛后的频段列表；空表示无变化
     *  the channels that changed, mapped to their converged list; empty = nothing changed */
    public static Map<String, List<String>> convergeBusInBands(NodeGraph graph) {
        Map<String, List<String>> changed = new LinkedHashMap<>();
        if (graph == null) return changed;
        Map<String, List<String>> resolvedByChannel = null;
        Set<String> absentChannels = null;
        for (var n : graph.nodes) {
            if (n.type != NodeType.BUS_IN || n.signalName == null || n.signalName.isEmpty()) continue;
            // 缺席守卫：本轮已判定「无已加载发布方」的频道直接跳过。 / Absence guard: channels already
            if (absentChannels != null && absentChannels.contains(n.signalName)) continue;
            // judged publisher-less this pass are skipped outright.
            if (resolvedByChannel == null) resolvedByChannel = new HashMap<>();
            List<String> want = resolvedByChannel.get(n.signalName);
            if (want == null) {
                want = resolveBusInBands(graph, null, n.signalName);
                // 缺席不是定义：解析为空且 CHANNELS 里没有该名字 ⇒ 当前没有已加载的发布方，
                // 「空」证明不了。照常收敛会把 BUS_IN 剪成空列表并按索引剪光输入连线且落盘
                // （发布方区块卸载 / 重启首 tick 注册顺序靠后 / 方块被拆除都会造成这种瞬时缺席），
                // 发布方回归后频段恢复而连线永久丢失。跳过、保留原列表——改名的权威 SET_BANDS
                // 仍负责清空死名（issue #11），这里只守住后台不变量不越权。
                // Absence is not a definition: an empty resolution with no CHANNELS entry means no
                // loaded publisher — the emptiness is unprovable. Converging anyway emptied the
                // BUS_IN, pruned every input connection by index and persisted the loss whenever the
                // publisher was transiently out of sight (chunk unload / late first-tick registration
                // order after a restart / broken block); the bands came back but the wires did not.
                // Skip and keep the list — the rename path's authoritative SET_BANDS still empties a
                // dead name (issue #11); this only keeps the background invariant from overreaching.
                // 「已定义为空」（retireBands 的死名标记）不是缺席：照常收敛为空——发布方改名
                // 走人后旧名 BUS_IN 必须清掉旧图（issue #11）；只有条目整个缺席（瞬时缺席）才跳过。
                // A defined-empty entry (retireBands' dead-name marker) is NOT absence: converge
                // it to empty — a BUS_IN on the old name must drop its stale list once the
                // publisher renamed away (issue #11); only a fully absent entry (transient) skips.
                if (want.isEmpty() && SignalBus.getChannel(n.signalName) == null
                    && SignalBus.getBands(n.signalName) == null) {
                    if (absentChannels == null) absentChannels = new HashSet<>();
                    absentChannels.add(n.signalName);
                    continue;
                }
                resolvedByChannel.put(n.signalName, want);
            }
            if (want.equals(n.signalBands)) continue;
            // 频段对齐唯一规则（NodeGraph.reconcileBands）：改名重绑 pinId 不剪线，
            // 增删按名剪除 —— 与 SET_BANDS/上传/客户端同步共用同一实现。
            // The single band alignment rule: renames rebind pinIds (wires kept),
            // additions/removals prune by name - shared with SET_BANDS, the upload
            // and the client-sync path.
            graph.reconcileBands(n, want);
            // legacy 索引回退：BUS_IN 的输入引脚是**索引绑定**的（GraphNode.inputPinId 只对
            // BUS_OUT 返回频段名），因此频段减少后落到新范围之外的连线要按索引清掉
            // ——与 releaseOldBusName 的既有做法一致。
            // Legacy index fallback: a BUS_IN's input pins are **index-bound**
            // (GraphNode.inputPinId returns a band name only for BUS_OUT), so after the list
            // shrank, connections whose index fell outside the new range are dropped — the same
            // treatment releaseOldBusName already applies.
            final int newCount = n.signalBands.size();
            graph.connections.removeIf(c -> c.toId == n.id && c.toPin >= newCount);
            changed.put(n.signalName, new ArrayList<>(want));
        }
        if (!changed.isEmpty()) {
            graph.rebuildNodeMap();     // invalidate inputCache / 刷新 inputCache
            graph.rebuildInputCache();
        }
        return changed;
    }

    // ── Tick-time band-change detection / Tick 时刻频段变更检测 ────────────────────

    /** Check every non-conflicted BUS_OUT node for band-list changes since the last tick
     *  and broadcast a {@link BusBandSyncPacket} when a change is detected.
     *  {@code lastHashMap} maps node id → (signalName.hashCode()*31 + bandCount).
     *  检查每个无冲突的 BUS_OUT 节点自上次 tick 以来的频段列表变更，检测到变更时广播 BusBandSyncPacket。
     *  lastHashMap 映射 节点id → (signalName.hashCode()*31 + bandCount)。 */
    public static void syncIfBandsChanged(NodeGraph graph, BlockPos pos,
                                           Map<Integer, Integer> lastHashMap, @Nullable Level level) {
        if (!(level instanceof ServerLevel sl) || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty() && !n.busConflict) {
                int h = n.signalName.hashCode() * 31 + n.bandCount();
                Integer prev = lastHashMap.get(n.id);
                if (prev == null || prev != h) {
                    lastHashMap.put(n.id, h);
                    PacketDistributor.sendToPlayersTrackingChunk(sl,
                        new ChunkPos(pos),
                        new BusBandSyncPacket(pos, n.signalName, n.signalBands));
                }
            }
        }
    }

    // ── Diff-based re-registration (preserves channel ownership across recompiles) ──
    // ── 基于差异的重新注册（在重编译期间保留频道所有权） ──

    /**
     * Re-register BUS channels after a graph change, preserving existing ownership.
     * <p>图变更后重新注册 BUS 频道，保留现有所有权。</p>
     * <p>Unlike the naive unregister-all-then-register-all pattern, this method:
     * <ul>
     *   <li>Unregisters only BUS_OUT nodes that were <b>removed</b> from the graph</li>
     *   <li>Updates the internalMap reference for nodes that <b>remain</b> in the graph
     *       (without changing ref-count, so ownership is never lost)</li>
     *   <li>Registers <b>new</b> BUS_OUT nodes normally (first-registrant-wins)</li>
     * </ul>
     * This prevents a newly-added BUS_OUT with the same signalName from stealing
     * the channel during the brief window when all channels are unregistered.
     * 与简单的"先全部取消注册再全部注册"模式不同，此方法：
     * <ul>
     *   <li>仅取消注册从图中<b>移除</b>的 BUS_OUT 节点</li>
     *   <li>更新<b>保留</b>在图中节点的 internalMap 引用（不改变引用计数，因此所有权永不丢失）</li>
     *   <li>正常注册<b>新增</b>的 BUS_OUT 节点（先注册者胜）</li>
     * </ul>
     * 这防止了新添加的同名 BUS_OUT 在所有频道被取消注册的短暂窗口期间窃取频道。</p>
     *
     * @param newGraph the graph after the change / 变更后的图
     * @param oldGraph the graph before the change (may be null, treated as all-new) / 变更前的图（可为 null，视为全新）
     * @param pos      the block position for owner identification / 用于所有者识别的方块坐标
     * @param level    the server level / 服务端世界
     * @return true if at least one node changed conflict state / 若至少一个节点的冲突状态变化则返回 true
     */
    public static boolean reRegisterChannels(NodeGraph newGraph, @Nullable NodeGraph oldGraph,
                                              BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || newGraph == null) return false;
        boolean anyConflict = false;

        // Build a set of (signalName, nodeId) keys that exist in the new graph
        // 构建新图中存在的 (signalName, nodeId) 键集合（BUS_OUT 与私有发布节点同键空间——节点 id 全图唯一）
        var newKeys = new HashSet<String>();
        for (var n : newGraph.nodes)
            if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && !n.signalName.isEmpty())
                newKeys.add(n.signalName + "@" + n.id);

        // Step 1: Unregister only REMOVED nodes (in oldGraph but not in newGraph)
        // 步骤1：仅取消注册已移除的节点（在 oldGraph 中但不在 newGraph 中）——改名即
        //「旧名节点移除」，旧名的占用/值/定义由此释放，同名竞争者可接管。
        if (oldGraph != null) {
            for (var n : oldGraph.nodes) {
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()
                    && !newKeys.contains(n.signalName + "@" + n.id)) {
                    SignalBus.unregisterChannel(n.signalName, new ChannelOwner(pos, n.id));
                } else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty()
                    && !newKeys.contains(n.signalName + "@" + n.id)) {
                    SignalBus.unregisterPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
                }
            }
        }

        // Step 2: Register NEW nodes and update EXISTING nodes
        // 步骤2：注册新节点并更新现有节点
        // Build a set of keys that existed in the old graph (for distinguishing new vs existing)
        // 构建旧图中存在的键集合（用于区分新增与现有）
        var oldKeys = new HashSet<String>();
        if (oldGraph != null) {
            for (var n : oldGraph.nodes)
                if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && !n.signalName.isEmpty())
                    oldKeys.add(n.signalName + "@" + n.id);
        }

        for (var n : newGraph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                String key = n.signalName + "@" + n.id;
                if (n.busInternalMap == null) n.busInternalMap = new HashMap<>();
                boolean ok;
                if (oldKeys.contains(key)) {
                    // Existing node — update internalMap reference without touching ref-count.
                    // If the channel was already taken by a different owner (conflict that
                    // existed before the recompile), updateChannel returns false and we mark
                    // this node as conflicted.
                    // 现有节点 — 更新 internalMap 引用而不影响引用计数。
                    // 若频道已被其他所有者占用（重编译前已存在的冲突），updateChannel 返回 false 并将此节点标记为冲突。
                    ok = SignalBus.updateChannel(n.signalName, n.busInternalMap,
                        new ChannelOwner(pos, n.id));
                    if (!ok) {
                        // Channel doesn't exist or is owned by another node — try to register
                        // 频道不存在或属于其他节点 — 尝试注册
                        ok = SignalBus.registerChannel(n.signalName, n.busInternalMap,
                            new ChannelOwner(pos, n.id));
                    }
                } else {
                    // New node — register normally
                    // 新节点 — 正常注册
                    ok = SignalBus.registerChannel(n.signalName, n.busInternalMap,
                        new ChannelOwner(pos, n.id));
                }
                // 注：不做"过时 owner 强制清理"——与 registerChannels 一致，所有权
                // 保持首个注册者获胜。残留回收交给 recoverConflictedChannels。
                // Note: no stale-owner force-cleanup here either — consistent with
                // registerChannels, preserving first-registrant-wins. Residual-owner
                // reclamation is delegated to recoverConflictedChannels.
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
                if (ok && n.signalBands != null && !n.signalBands.isEmpty()) {
                    SignalBus.registerBands(n.signalName, n.signalBands);
                    n.bandsDirty = false;
                    if (level instanceof ServerLevel sl) {
                        PacketDistributor.sendToPlayersTrackingChunk(sl,
                            new ChunkPos(pos),
                            new BusBandSyncPacket(pos, n.signalName, n.signalBands));
                    }
                }
            } else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty()) {
                // 私有频道占用（BUS 同款）：同 owner 重注册为幂等，被占即标冲突。
                // Private occupancy (BUS discipline): same-owner re-registration is idempotent.
                boolean ok = SignalBus.registerPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
            }
        }
        return anyConflict;
    }

    // ── Conflict auto-recovery / 冲突自动恢复 ─────────────────────────────

    /** Check every conflicted publisher (BUS_OUT / PRIVATE_OUT): if the previous channel owner
     *  is gone, this node takes over; residual owners are reclaimed by timeout.
     *  检查每个冲突的发布节点（BUS_OUT / PRIVATE_OUT）：原 owner 消失即接管；残留 owner 按超时回收。
     *  Call once per tick before the evaluator runs. / 每 tick 在评估器运行前调用一次。
     *  @return true if at least one node recovered (caller should trigger a full sync) / 若至少有一个节点恢复则返回 true（调用方应触发完整同步） */
    public static boolean recoverConflictedChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return false;
        boolean anyRecovered = false;
        for (var n : graph.nodes) {
            if (!n.busConflict || n.signalName.isEmpty()) continue;
            if (n.type == NodeType.BUS_OUT) {
                if (n.bandCount() <= 0) continue;
                var entry = SignalBus.getChannel(n.signalName);
                if (entry == null) {
                    // EN: First owner is gone → take over the channel and immediately sync bands to clients
                    // 首个 owner 已消失 → 接管频道并立即同步 bands 到客户端
                    takeoverChannel(n, pos, level);
                    anyRecovered = true;
                } else if (level instanceof ServerLevel sl
                    && staleOwnerReclaim(n, entry.owner.pos(), sl)) {
                    SignalBus.unregisterChannel(n.signalName, entry.owner);
                    takeoverChannel(n, pos, level);
                    anyRecovered = true;
                }
            } else if (n.type == NodeType.PRIVATE_OUT) {
                var owner = SignalBus.getPrivateOwner(n.signalName);
                if (owner == null) {
                    takeoverPrivateChannel(n, pos);
                    anyRecovered = true;
                } else if (level instanceof ServerLevel sl
                    && staleOwnerReclaim(n, owner.pos(), sl)) {
                    SignalBus.unregisterPrivateChannel(n.signalName, owner);
                    takeoverPrivateChannel(n, pos);
                    anyRecovered = true;
                }
            }
        }
        return anyRecovered;
    }

    /** 冲突节点的 owner 存活判定（BUS 与私有共用）：owner 区块已加载且该坐标仍有图宿主 BE →
     *  重置计数、绝不回收（绝不从可能存活的 owner 处强夺）；区块已加载但方块已消失（移除时
     *  未清理频道）→ 短超时（40 tick ≈ 2 s，容忍 Sable 子关卡坐标瞬时解析失败）后回收；
     *  区块未加载 → 长超时（200 tick ≈ 10 s）后回收。返回 true = 应回收接管。
     *  Owner-liveness verdict for a conflicted node (shared by BUS and private channels): a
     *  loaded chunk holding a live graph BE never reclaims — we never steal from a
     *  possibly-live owner; a loaded chunk without one reclaims after a short timeout (40t);
     *  an unloaded chunk after a long one (200t ≈ 10 s). */
    private static boolean staleOwnerReclaim(GraphNode n, BlockPos ownerPos, ServerLevel sl) {
        int cx = ownerPos.getX() >> 4;
        int cz = ownerPos.getZ() >> 4;
        boolean ownerChunkLoaded = sl.getChunkSource().getChunkNow(cx, cz) != null;
        if (!ownerChunkLoaded) {
            n.busConflictTicks++;
            return n.busConflictTicks > 200;
        }
        boolean ownerBeAlive = sl.getBlockEntity(ownerPos)
            instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
        if (ownerBeAlive) {
            n.busConflictTicks = 0;
            return false;
        }
        n.busConflictTicks++;
        return n.busConflictTicks > 40;
    }

    /** 接管私有频道：注册为 owner、清冲突旗标（值/定义从本节点的求值重新建立）。
     *  Take over a private channel: register as owner and clear the conflict flag. */
    private static void takeoverPrivateChannel(GraphNode n, BlockPos pos) {
        SignalBus.registerPrivateChannel(n.signalName, new ChannelOwner(pos, n.id));
        n.busConflict = false;
        n.busConflictTicks = 0;
    }

    /** Take over the channel: register this node as owner, clear conflict flag, sync bands.
     *  接管频道：将此节点注册为 owner，清除冲突标志，同步 bands。 */
    private static void takeoverChannel(GraphNode n,
                                        BlockPos pos, Level level) {
        if (n.busInternalMap == null) n.busInternalMap = new java.util.HashMap<>();
        SignalBus.registerChannel(n.signalName, n.busInternalMap,
            new ChannelOwner(pos, n.id));
        n.busConflict = false;
        n.busConflictTicks = 0;
        if (n.signalBands != null && !n.signalBands.isEmpty()) {
            SignalBus.registerBands(n.signalName, n.signalBands);
            if (level instanceof ServerLevel sl) {
                PacketDistributor.sendToPlayersTrackingChunk(sl,
                    new ChunkPos(pos),
                    new BusBandSyncPacket(pos, n.signalName, n.signalBands));
            }
        }
        n.bandsDirty = false;
    }
}
