package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;

/**
 * 设置界面 tab 视图的宿主接口：几何、控件与少量跨 tab 状态（自 {@link EditorSettingsScreen} 抽出）。
 * Host surface for the settings-screen tab views: geometry, widgets and the few cross-tab bits
 * of state. Extracted from {@link EditorSettingsScreen} (docs/gui-decomposition-plan.md step 3).
 *
 * <p>之所以是**独立顶层接口**而不是嵌套接口：实现方正是 {@link EditorSettingsScreen} 自己，
 * 嵌套写法会构成循环继承（javac: cyclic inheritance）。
 * It is a top-level interface rather than a nested one because the implementor IS
 * {@link EditorSettingsScreen}; a nested declaration is a cyclic inheritance error.</p>
 */
interface EditorSettingsHost {
        int w();
        int h();
        net.minecraft.client.gui.Font font();
        ColorPickerWidget picker();
        boolean expanded();
        void setExpanded(boolean v);
        int slide();
        void beginAdjust(int idx);
        void collapsePalette();
        /** 开始调整某个颜色槽后的调色板几何（颜色 tab 内部使用）。 */
        int paletteX();
        int paletteY();
        float paletteScale(int h);
        int paletteDoneY();
        /** 调色板重绑定（Defaults 重置后）。 */
        void rebindPicker();
        /** 颜色列表滚动 / 拖拽状态。 */
        int colorScroll();
        void setColorScroll(int v);
        boolean colorScrollbarDrag();
        void setColorScrollbarDrag(boolean v);
        float colorScrollbarDragStartY();
        int colorScrollbarDragStartOff();
        void setColorScrollbarDragStart(float y, int off);
        void applyColorScrollbarDrag(double my);
        /** 颜色列表几何（渲染/命中/拖拽共用同一来源，故留在屏幕侧）。 */
        int colorsListTop();
        int colorsListBot();
        int colorsVisibleRows();
        int colorsMaxScroll();
        int colorsRowRight(int cx, int contentW);
        int[] colorsScrollbarThumb(int cx, int contentW);
        void collapseExpanded();
        /** 内容区起始 y（渲染与命中共用）。 */
        int cy();
        /** 选项卡列点击（屏幕侧分发）。 */
        void tabColumnClick(double my);
}
