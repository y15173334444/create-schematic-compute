package io.github.y15173334444.create_schematic_compute.graph;

/** 编辑会话的服务端落点**窄口**（反向包边解环，2026-10-31 拍板）：`network.EditSessionRegistry`
 *  只需要图访问、当前状态应用、全量同步请求与落盘脏标——不再点名
 *  `blocks.GraphBlockEntity` 具体类型（E2 `EditAckSink` 同款缝-实现分离手法）。
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
}
