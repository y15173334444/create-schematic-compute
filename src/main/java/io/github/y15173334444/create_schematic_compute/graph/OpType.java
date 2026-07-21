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
    SET_ZORDER,
    SET_KEY_BINDING,
    SET_IMAGE_FRAME_TOGGLE,
    SET_DISPLAY_LAYOUT,     // layoutX, layoutY, displayScale, displayRotation
    TOGGLE_BOOL,
    SET_HOTBAR_ITEM,
    SET_IMAGE_PIXELS,       // stringValue=Base64 pixels, paramIndex=frameIndex

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
    REJECT
}
