package io.github.y15173334444.create_schematic_compute.graph;

/** 编辑会话的服务端落点**窄口**（反向包边解环，2026-10-31 拍板）：`network.EditSessionRegistry`
 *  只需要图访问、当前状态应用、全量同步请求、落盘脏标与准入门应答（P2-7：名单 + 装载普查）
 *  ——不再点名 `blocks.GraphBlockEntity` 具体类型（E2 `EditAckSink` 同款缝-实现分离手法）。
 *  窄口住 `graph/` 公共域：签名含 `NodeGraph` 等图域类型，须与 `blocks.GraphBlockEntity`
 *  同处 Sable 跨类加载器的复制域（blocks/graph 子优先复制——签名跨域共享会触发 JVM
 *  加载器约束，见 SableDeviceNameCrossLoaderTest）。
 *  `blocks.GraphBlockEntity` 继承本口，宿主 BE 类随行实现。
 *  Narrow server-side target port for edit sessions (reverse package-edge cycle break,
 *  2026-10-31 ruling): `network.EditSessionRegistry` only needs graph access,
 *  apply-current-state, full-sync requests and the dirty mark — it no longer names
 *  `blocks.GraphBlockEntity` (the E2 `EditAckSink` playbook). The port lives in the
 *  `graph/` shared domain because its signatures carry graph-domain types like
 *  `NodeGraph`, which must share the Sable cross-classloader duplication domain with
 *  `blocks.GraphBlockEntity` (child-first copies of blocks/graph — a shared interface
 *  whose signatures cross domains trips the JVM loader constraint, see
 *  SableDeviceNameCrossLoaderTest). `blocks.GraphBlockEntity` extends this port;
 *  host BE classes ride along.
 */
public interface EditSessionTarget {

    /** 当前图（可空）/ the current graph (nullable). */
    NodeGraph getNodeGraph();

    /** 按 op 落当前状态（触发器镜像等）/ apply current state for the op (flipflop mirrors etc.). */
    void applyCurrentState(int ownerNodeId, int nodeId);

    /** 请求全量同步 / request a full sync. */
    void requestFullSync();

    /** 标记脏数据以便区块落盘（`BlockEntity.setChanged` 的窄口面）/ mark dirty for chunk
     *  persistence (the narrow face of `BlockEntity.setChanged`). */
    void setChanged();

    /** 本宿主的节点准入名单（服务端 op 链准入门 P2-7 的宿主侧应答；各宿主 BE 各答自己的
     *  `BlockNodeAllowances` 条目）。缺席（null）按 fail-closed 处理。
     *  The host's node admission allowlist (the host-side answer for the P2-7 server gate;
     *  each host BE answers its own `BlockNodeAllowances` entry). Absent (null) fails closed. */
    NodeAllowance nodeAllowance();

    /** 装载时图树类型普查（旧档兼容口径：装载时已在场的类型本会话内可再进来，见
     *  `NodeAdmission` 类注）。由引擎（`GraphHost`）在图替换点记录，宿主 BE 转发。
     *  Load-time graph-tree type census (legacy tolerance: types present at load stay
     *  admissible, see the `NodeAdmission` class doc). Recorded by the engine (`GraphHost`)
     *  at the graph-replacement site; host BEs forward it. */
    java.util.Set<NodeType> loadTimeNodeTypes();

    /** 装载时图树自定义节点 id 普查（批 D 撤销面字符串化，见 `NodeAdmission.censusCustomIds`
     *  类注）。与类型普查同点记录、同寿命，宿主 BE 转发。
     *  Load-time custom-id census (batch D undo-face string-isation, see
     *  `NodeAdmission.censusCustomIds`). Recorded with the type census, same lifetime;
     *  host BEs forward it. */
    java.util.Set<String> loadTimeCustomIds();
}
