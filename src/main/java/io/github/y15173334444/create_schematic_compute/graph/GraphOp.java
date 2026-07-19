package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

public record GraphOp(
    OpType type,
    BlockPos graphPos,
    int ownerNodeId,
    int targetNodeId,
    int tempId,
    NodeType nodeType,
    float x, float y,
    int fromId, int fromPin, int toId, int toPin,
    int paramIndex, float paramValue,
    String stringValue,
    int colorBg, int colorBorder, int colorText,
    int sortB,
    List<String> bands,
    int keyIndex,
    int imageFrameIndex,
    int hotbarSlot,
    ItemStack itemStack,
    long editVersion,
    UUID actor,
    int blobRefId,          // 非零 → 通过 BlobRegistry 查找大数据 / non-zero → BlobRegistry lookup for large data
    int[] imageData         // 直接像素数据（替代 Base64 stringValue）/ direct pixel data (replaces Base64 stringValue)
) {
    public GraphOp(OpType type, BlockPos graphPos, int ownerNodeId, int targetNodeId, UUID actor) {
        this(type, graphPos, ownerNodeId, targetNodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public GraphOp(OpType type, BlockPos graphPos, int ownerNodeId, int targetNodeId,
                   NodeType nodeType, float x, float y, long editVersion, UUID actor) {
        this(type, graphPos, ownerNodeId, targetNodeId,
            0, nodeType, x, y, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, editVersion, actor, 0, null);
    }

    /**
     * 向后兼容的 22 参数构造函数，供尚未采用 blobRefId/imageData 的调用方使用。
     * Backward-compatible 22-arg constructor for callers that haven't adopted blobRefId/imageData yet.
     */
    public GraphOp(OpType type, BlockPos graphPos, int ownerNodeId, int targetNodeId,
                   int tempId, NodeType nodeType, float x, float y,
                   int fromId, int fromPin, int toId, int toPin,
                   int paramIndex, float paramValue, String stringValue,
                   int colorBg, int colorBorder, int colorText,
                   int sortB, List<String> bands, int keyIndex, int imageFrameIndex,
                   int hotbarSlot, ItemStack itemStack, long editVersion, UUID actor) {
        this(type, graphPos, ownerNodeId, targetNodeId,
            tempId, nodeType, x, y, fromId, fromPin, toId, toPin,
            paramIndex, paramValue, stringValue,
            colorBg, colorBorder, colorText,
            sortB, bands, keyIndex, imageFrameIndex,
            hotbarSlot, itemStack, editVersion, actor, 0, null);
    }

    public static GraphOp addNodeRequest(BlockPos pos, int ownerNodeId, int tempId,
                                          NodeType type, float x, float y, UUID actor) {
        return new GraphOp(OpType.ADD_NODE_REQUEST, pos, ownerNodeId, 0,
            tempId, type, x, y, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp moveNode(BlockPos pos, int ownerNodeId, int nodeId,
                                    float x, float y, UUID actor) {
        return new GraphOp(OpType.MOVE_NODE, pos, ownerNodeId, nodeId,
            0, null, x, y, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp addConn(BlockPos pos, int ownerNodeId,
                                   int fromId, int fromPin, int toId, int toPin, UUID actor) {
        return new GraphOp(OpType.ADD_CONN, pos, ownerNodeId, 0,
            0, null, 0f, 0f, fromId, fromPin, toId, toPin, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp removeConn(BlockPos pos, int ownerNodeId,
                                      int fromId, int fromPin, int toId, int toPin, UUID actor) {
        return new GraphOp(OpType.REMOVE_CONN, pos, ownerNodeId, 0,
            0, null, 0f, 0f, fromId, fromPin, toId, toPin, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setParam(BlockPos pos, int ownerNodeId, int nodeId,
                                    int paramIdx, float value, UUID actor) {
        return new GraphOp(OpType.SET_PARAM, pos, ownerNodeId, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, paramIdx, value,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setFormula(BlockPos pos, int ownerNodeId, int nodeId,
                                      String formula, UUID actor) {
        return new GraphOp(OpType.SET_FORMULA, pos, ownerNodeId, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            formula, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setCommentColors(BlockPos pos, int ownerNodeId, int nodeId,
                                            int bg, int border, int text, UUID actor) {
        return new GraphOp(OpType.SET_COMMENT_COLORS, pos, ownerNodeId, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, bg, border, text, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setCommentSize(BlockPos pos, int ownerNodeId, int nodeId,
                                          float w, float h, UUID actor) {
        return new GraphOp(OpType.SET_COMMENT_SIZE, pos, ownerNodeId, nodeId,
            0, null, w, h, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setDisplayLayout(BlockPos pos, int ownerNodeId, int nodeId,
                                            float lx, float ly, float scale, float rot, UUID actor) {
        return new GraphOp(OpType.SET_DISPLAY_LAYOUT, pos, ownerNodeId, nodeId,
            0, null, lx, ly, 0, 0, 0, 0, 0, scale,
            null, 0, 0, 0, 0, null, (int)(rot * 100f), 0, 0, ItemStack.EMPTY, 0L, actor, 0, null);
    }

    public static GraphOp setHotbarItem(BlockPos pos, int ownerNodeId, int nodeId,
                                         int slot, ItemStack stack, UUID actor) {
        return new GraphOp(OpType.SET_HOTBAR_ITEM, pos, ownerNodeId, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, slot, stack, 0L, actor, 0, null);
    }

    /**
     * 直接传输像素数据 — 不再使用 Base64。
     * Direct pixel data transfer — no more Base64.
     */
    public static GraphOp setImagePixels(BlockPos pos, int ownerNodeId, int nodeId,
                                          int frameIndex, int[] pixels, UUID actor) {
        return new GraphOp(OpType.SET_IMAGE_PIXELS, pos, ownerNodeId, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, frameIndex, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0, ItemStack.EMPTY, 0L, actor, 0, pixels);
    }
}
