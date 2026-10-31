package io.github.y15173334444.create_schematic_compute.api;

import java.util.List;

/**
 * 第三方自定义节点的声明式 schema（公开 API 波 1，探索稿 D2/D4 拍板）。值类型 ——
 * 只含 JDK/String 标量与自有 record，零图域类型、零 Create/Sable 类型（跨加载器域
 * 教训的结构性要求，探索稿 §3-10）；批 B 的 {@code api} 包门面以本类为内部载体。
 *
 * <p><b>身份与稳定承诺</b>：{@link #id} 是 {@code "modid:node_id"} 形式的稳定字符串
 * （小写命名空间 + 小写名），随玩家图 NBT 持久化（{@code type} 键 = 哨兵 {@code "custom"}、
 * {@code cid} 键 = 本 id）——本模组更新不得使其损坏（探索稿 §5 持久化承诺）。注册表
 * 按本 id 寻址；提供方模组缺席时节点降级为占位壳（图不损）。</p>
 *
 * <p><b>包位置</b>：{@code api} 包 —— 本类是第三方可见公开面的组成部分（批 B 起）。
 * graph 域（GraphNode/CustomNodeRegistry）作为实现侧引用本类型（类型依赖 graph → api）；
 * api 门面对实现侧的委托（CustomNodes → CustomNodeRegistry）是门面实现边，不属于
 * 本类承载的签名面。</p>
 *
 * <p><b>引脚</b>：v1 只有 FLOAT 域（探索稿 §3-8），数量 = 标签列表长度，标签即引脚
 * 显示名（也作 pinId 的稳定编码输入，见 GraphNode 通用 pinId 路径）。空列表 = 无引脚。</p>
 *
 * <p><b>参数（控件，不产引脚）</b>：声明式参数是编辑器控件（D4：数值/文本/布尔/物品槽），
 * 求值回调里由提供方按 key 自读——「参数即寻址」的机械默认（探索稿 §8）：提供方在
 * schema 里声明如文本机器名的参数，回调里自行解析到自己的方块。与内置节点「可编辑
 * 参数自动追加参数引脚」的惯例**刻意不同**：自定义节点的参数只是控件，驱动它走引脚
 * （in/out）而不是参数引脚——语义差异记录在案，实验期（1.4 冻结前）可再议。</p>
 *
 * <p>A third-party custom node's declarative schema (public-API wave 1; rulings D2/D4).
 * A pure value type — JDK/String scalars and own records only, zero graph-domain and zero
 * Create/Sable types in the signature (the structural requirement from the cross-loader
 * narrow-port lesson; exploration doc §3-10); the batch-B {@code api} package facade uses
 * this class as its internal carrier.</p>
 */
public record CustomNodeType(
    /** 稳定字符串 id（{@code "modid:node_id"}，小写）/ stable string id ("modid:node_id", lowercase). */
    String id,
    /** 编辑器标题（字面量；experimental 期不做 i18n 键约定）/ editor title (literal; no i18n-key contract while experimental). */
    String title,
    /** 输入引脚标签（有序，长度 = 输入引脚数，FLOAT 域）/ ordered input-pin labels (length = input count, FLOAT domain). */
    List<String> inputLabels,
    /** 输出引脚标签（有序）/ ordered output-pin labels. */
    List<String> outputLabels,
    /** 声明式参数（编辑器控件；不产生引脚）/ declarative params (editor controls; no pins). */
    List<CustomParam> params
) {

    /** 参数控件描述 / a declarative parameter control. */
    public record CustomParam(String key, ParamKind kind) {
        public enum ParamKind { NUMBER, TEXT, BOOL, ITEM }
    }

    /** 单节点参数控件数上限（**K15 静态预检的上界**——CUSTOM 的 SET_HOTBAR_ITEM 槽位
     *  检查在包层只有本静态帽可依，精确槽位数由执行器按节点实际 schema 裁定；编辑区
     *  面板高度按实际控件计数，不引用本帽。32 个控件远超合理节点形态）。
     *  Cap on a schema's control count (the **K15 static pre-check's bound** — the
     *  packet-layer CUSTOM SET_HOTBAR_ITEM check has only this static cap to lean on,
     *  while the precise slot count is ruled per node by the executor; the edit-panel
     *  height counts the real controls and never reads this cap). 32 controls far exceed
     *  any reasonable node shape. */
    public static final int MAX_SCHEMA_PARAMS = 32;

    /** 单个 TEXT 控件的文本长度帽（批 D，条目 17②）——与线缆 readUtf 的 32767 全局帽
     *  同量级，但在包层预检里显式执行并给出 REJECT 回执（客户端可回滚），不再依赖
     *  线缆层静默截断；执行器同源引用作纵深防御。与求值状态 blob 的 32KB 帽
     *  （graph.CustomNodeEval.MAX_STATE_SNBT_LENGTH）同批 B 纪律。
     *  Per-control TEXT length cap (batch D, entry 17②) — the same order as the wire
     *  readUtf global 32767 cap, but enforced explicitly at the packet pre-check with a
     *  REJECT receipt (the client can roll back) instead of relying on silent wire
     *  truncation; the executor references the same constant as defense in depth. Same
     *  batch-B discipline as the evaluation state-blob's 32KB cap
     *  (graph.CustomNodeEval.MAX_STATE_SNBT_LENGTH). */
    public static final int MAX_TEXT_PARAM_LENGTH = 32768;

    public CustomNodeType {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
            throw new IllegalArgumentException("CustomNodeType id must be 'modid:node_id' (lowercase): " + id);
        if (title == null || title.isEmpty())
            throw new IllegalArgumentException("CustomNodeType title must not be empty: " + id);
        inputLabels = inputLabels == null ? List.of() : List.copyOf(inputLabels);
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        params = params == null ? List.of() : List.copyOf(params);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CustomParam p : params)
            if (!seen.add(p.key()))
                throw new IllegalArgumentException("Duplicate custom param key '" + p.key() + "' on " + id);
        if (params.size() > MAX_SCHEMA_PARAMS)
            throw new IllegalArgumentException("CustomNodeType " + id + " declares " + params.size()
                + " params, over the cap " + MAX_SCHEMA_PARAMS);
    }

    public int inputs() { return inputLabels.size(); }
    public int outputs() { return outputLabels.size(); }
    public int paramCount() { return params.size(); }
}
