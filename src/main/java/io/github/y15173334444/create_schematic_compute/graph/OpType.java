package io.github.y15173334444.create_schematic_compute.graph;

/**
 * Kinds of incremental graph operations for multiplayer collaboration.
 * Defined in a separate file so it can be public at the package level.
 */
public enum OpType {
    // Structural
    ADD_NODE_REQUEST,       // C→S
    ADD_NODE,               // S→C
    REMOVE_NODE,
    MOVE_NODE,
    ADD_CONN,
    REMOVE_CONN,

    // Parameters
    SET_PARAM,
    SET_FORMULA,
    SET_COMMENT_TEXT,
    SET_COMMENT_COLORS,
    SET_COMMENT_SIZE,
    SET_DISPLAY_TEXT,
    SET_TEXT_COLOR,
    SET_BANDS,
    SET_BLOCK_NAME,         // 图级 op：stringValue=本图玩家可见名称（顶栏，targetNodeId=0 忽略）
                            // graph-level op: stringValue = player-visible graph name
                            // (editor top bar; targetNodeId=0 and ignored)
    SET_ZORDER,
    SET_LAYER_INDEX,        // 显示器图层序 / display layer index: sortB=layerIndex
    SET_KEY_BINDING,
    SET_IMAGE_FRAME_TOGGLE,
    SET_DISPLAY_LAYOUT,     // layoutX, layoutY, displayScale, displayRotation
    TOGGLE_BOOL,
    SET_HOTBAR_ITEM,
    SET_IMAGE_PIXELS,       // stringValue=Base64 pixels, paramIndex=frameIndex
    SET_IMAGE_SIZE,         // IMAGE 画布尺寸 / canvas size: paramIndex=w, keyIndex=h (1..32)
    REMOVE_IMAGE_FRAME,     // 删除 IMAGE_SEQUENCE 帧 / remove frame: paramIndex=frameIndex
    MOVE_IMAGE_FRAME,       // 重排 IMAGE_SEQUENCE 帧 / move frame: paramIndex=from, keyIndex=to

    // UI State
    EXPAND_NODE,
    COLLAPSE_NODE,

    // Shared view bookmarks (synced via multiplayer collaboration)
    ADD_BOOKMARK,           // stringValue=name, x=camX, y=camY, paramValue=zoom
    REMOVE_BOOKMARK,        // targetNodeId=bookmark index
    RENAME_BOOKMARK,        // targetNodeId=bookmark index, stringValue=new name
    MOVE_BOOKMARK,          // targetNodeId=fromIndex, paramIndex=toIndex

    // Control points (DEBUG_SIGNAL_GEN)
    SET_CTRL_POINTS,        // imageData=ctrlX (float→int bits), blobRefId=ctrlY bits

    // Meta
    REJECT,

    // 曲目数据（NBS）。注意：网络序号 = 枚举序（GraphEditOpPacket 以 ordinal 编码），
    // 新值只能追加在尾部，插中间会错位所有后续 op。
    // Song data (NBS). Note: the wire ordinal is the enum order (GraphEditOpPacket encodes
    // ordinals) — new values must be appended at the end, never inserted.
    SET_SONG,               // 曲目写入/替换：blobRefId = BlobRegistry 分片重组句柄，字节 = NBS v5
                            // song write/replace: blobRefId = BlobRegistry reassembly handle, bytes = NBS v5

    // 触发器当前态手动切换（GATE / T_FLIPFLOP 编辑区「当前状态」按钮）。自反 op：
    // 再发一次即还原，撤销/重做无需记录旧值。params[1] 镜像由 OpExecutor 翻转，
    // 运行时触发器映射由服务端宿主 applyCurrentState 落地。
    // Manual toggle of a trigger's current state (the "Current state" row button on
    // GATE / T_FLIPFLOP). Self-inverse: sending it again restores, so undo needs no
    // recorded old value. OpExecutor flips the params[1] mirror; the server host's
    // applyCurrentState lands the runtime trigger map.
    TOGGLE_CUR_STATE,

    // 公开 API 波 1 批 C：自定义节点 TEXT 参数写入。paramIndex = schema 参数序位，
    // stringValue = 文本；执行器按 schema 校验 kind==TEXT（非 TEXT 序位拒收）。
    // 追加令：枚举尾 = 网络序尾（新值只许追加，插中间会错位所有后续 op）。
    // Public-API wave 1 batch C: a custom node's TEXT param write. paramIndex = the
    // schema param slot, stringValue = the text; the executor validates kind==TEXT
    // against the schema (non-TEXT slots are refused). Append-only: the enum tail is the
    // wire tail.
    SET_CUSTOM_TEXT
}
