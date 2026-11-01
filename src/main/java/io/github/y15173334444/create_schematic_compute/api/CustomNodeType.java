package io.github.y15173334444.create_schematic_compute.api;

import java.util.List;

/**
 * 第三方自定义节点的声明式 schema（本模组公开 API 的组成部分）。值类型 ——
 * 只含 JDK/String 标量与自有 record，零图域类型、零 Create/Sable 类型（结构性要求：
 * 提供方只对本包编译，绝不触达内部或可选依赖模组的类）；本类是 {@code api} 公开面与实现侧注册表之间共用的载体。
 *
 * <p><b>身份与稳定承诺</b>：{@link #id} 是 {@code "modid:node_id"} 形式的稳定字符串
 * （小写命名空间 + 小写名），随玩家图 NBT 持久化（{@code type} 键 = 哨兵 {@code "custom"}、
 * {@code cid} 键 = 本 id）——<b>持久化承诺：本模组更新不得使其损坏</b>。注册表
 * 按本 id 寻址；提供方模组缺席时节点降级为占位壳（图不损）。</p>
 *
 * <p><b>包位置</b>：{@code api} 包 —— 本类是第三方可见公开面的组成部分。
 * graph 域（GraphNode/CustomNodeRegistry）作为实现侧引用本类型（类型依赖 graph → api）；
 * api 门面对实现侧的委托（CustomNodes → CustomNodeRegistry）是门面实现边，不属于
 * 本类承载的签名面。</p>
 *
 * <p><b>引脚</b>：v1 只有 FLOAT 域，数量 = 标签列表长度，标签即引脚
 * 显示名（也作 pinId 的稳定编码输入，见 GraphNode 通用 pinId 路径）。空列表 = 无引脚。</p>
 *
 * <p><b>参数（控件，不产引脚）</b>：声明式参数是编辑器控件（数值/文本/布尔/物品槽四类），
 * 求值回调里由提供方按 key 自读——「参数即寻址」约定：提供方在
 * schema 里声明如文本机器名的参数，回调里自行解析到自己的方块。与内置节点「可编辑
 * 参数自动追加参数引脚」的惯例<b>刻意不同</b>：自定义节点的参数只是控件，驱动它走引脚
 * （in/out）而不是参数引脚——语义差异记录在案，实验期（1.4 冻结前）可再议。</p>
 *
 * <p>A third-party custom node's declarative schema (part of this mod's public API).
 * A pure value type — JDK/String scalars and own records only, zero graph-domain and zero
 * Create/Sable types in the signature (a structural requirement: providers compile against
 * this package alone, never against internal or optional-dependency classes); this class is
 * the shared carrier between the {@code api} public face and the implementation-side
 * registry.</p>
 *
 * @param id 稳定字符串 id（{@code "modid:node_id"}，小写）/ stable string id ("modid:node_id", lowercase).
 * @param title 编辑器标题（字面量；experimental 期不做 i18n 键约定）/ editor title (literal; no i18n-key contract while experimental).
 * @param inputLabels 输入引脚标签（有序，长度 = 输入引脚数，FLOAT 域）/ ordered input-pin labels (length = input count, FLOAT domain).
 * @param outputLabels 输出引脚标签（有序）/ ordered output-pin labels.
 * @param params 声明式参数（编辑器控件；不产生引脚）/ declarative params (editor controls; no pins).
 */
public record CustomNodeType(
    String id,
    String title,
    List<String> inputLabels,
    List<String> outputLabels,
    List<CustomParam> params
) {

    /** 参数控件描述 / a declarative parameter control.
     *  @param key  控件键（同一 schema 内唯一；求值回调按此键读值）/ the control key (unique
     *              within the schema; the callback reads the value by it)
     *  @param kind 控件类型 / the control kind */
    public record CustomParam(String key, ParamKind kind) {
        /** 控件类型 / the control kind. */
        public enum ParamKind {
            /** 数值输入框，经 {@link CustomNodeContext#numberParam} 读取 / a number field, read via numberParam. */
            NUMBER,
            /** 文本输入框，经 {@link CustomNodeContext#textParam} 读取 / a text field, read via textParam. */
            TEXT,
            /** 开关，经 {@link CustomNodeContext#boolParam} 读取 / a toggle, read via boolParam. */
            BOOL,
            /** 物品槽，经 {@link CustomNodeContext#itemParam} 读取 / an item slot, read via itemParam. */
            ITEM
        }
    }

    /** 单节点参数控件数上限（<b>包层静态预检的上界</b>——对自定义节点物品槽的写入，包层只有
     *  本静态帽可依，精确槽位数由服务端执行时按节点实际 schema 裁定；编辑区
     *  面板高度按实际控件计数，不引用本帽。32 个控件远超合理节点形态）。
     *  Cap on a schema's control count (the <b>packet layer's static pre-check bound</b> —
     *  an item-slot write to a custom node has only this static cap to lean on at the
     *  packet layer, while the precise slot count is ruled per node against its actual
     *  schema on the server; the edit-panel height counts the real controls and never
     *  reads this cap). 32 controls far exceed any reasonable node shape. */
    public static final int MAX_SCHEMA_PARAMS = 32;

    /** 单个 TEXT 控件的文本长度帽——与线缆 readUtf 的 32767 全局帽
     *  同量级，但在包层预检里显式执行并给出拒收回执（客户端可回滚），不依赖
     *  线缆层静默截断；服务端执行时同源引用作纵深防御。单节点全部 TEXT 控件的文本
     *  总量另有 32768 字符聚合帽（增总且越帽的写入不落地，缩减/等额改写恒放行）。
     *  与求值状态 blob 的 32KB 帽（见 {@link CustomNodeResult#suspended}）同一纪律。
     *  Per-control TEXT length cap — the same order as the wire
     *  readUtf global 32767 cap, but enforced explicitly at the packet pre-check with a
     *  refusal receipt (the client can roll back) instead of relying on silent wire
     *  truncation; the server-side apply references the same constant as defense in depth.
     *  All TEXT controls of one node share a further 32768-character aggregate cap (a write
     *  that grows the total past it does not land; shrinking or equal-total rewrites always
     *  land). Same discipline as the evaluation state-blob's 32KB cap (see
     *  {@link CustomNodeResult#suspended}). */
    public static final int MAX_TEXT_PARAM_LENGTH = 32768;

    /** 校验并规范化：id 须为小写 {@code modid:node_id}、标题非空、参数键在 schema 内唯一且
     *  数量不超过 {@link #MAX_SCHEMA_PARAMS}；三个列表做不可变拷贝（null 视为空列表）。
     *  Validates and normalizes: a lowercase {@code modid:node_id} id, a non-empty title, and
     *  param keys unique within the schema and at most {@link #MAX_SCHEMA_PARAMS}; the three
     *  lists are copied immutably (null reads as empty).
     *  @throws IllegalArgumentException 任一校验失败 / when any check fails */
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

    /** 输入引脚数 / the input-pin count.
     *  @return 输入标签列表长度 / the length of the input labels */
    public int inputs() { return inputLabels.size(); }
    /** 输出引脚数 / the output-pin count.
     *  @return 输出标签列表长度 / the length of the output labels */
    public int outputs() { return outputLabels.size(); }
    /** 参数控件数 / the parameter-control count.
     *  @return 参数列表长度 / the length of the params */
    public int paramCount() { return params.size(); }
}
