package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Token;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.TokType;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * A multi-line text edit widget extending Minecraft's EditBox.
 * Supports word wrap (visual line wrapping at widget width) and horizontal scroll.
 */
public class MultiLineEditBox extends EditBox {
    /** Visual line height, derived from font metrics instead of hardcoded. */
    private int lineHeight() { return font.lineHeight + 3; }
    private final Font font;

    // Word-wrap visual line mapping; rebuilt only when content or width changes
    private static class VLine { int logLine; int charStart; int charEnd; }
    private final java.util.ArrayList<VLine> visualLines = new java.util.ArrayList<>();
    private String lastBuiltText = null;
    private int lastBuiltWidth = -1;
    private int backgroundColor = 0xFF000000;
    private int textColor = 0xFFE0E0E0;
    private boolean drawMleBorder = true;

    private int cursorColor = 0xFFFFFFFF;

    // ── Syntax highlighting / 语法高亮 ──
    private java.util.function.Function<String, java.util.List<Token>> highlighter;
    private int[] tokenPalette;          // indexed by TokType.ordinal() / 按 TokType.ordinal() 索引
    private boolean hasError = false;    // red border flag / 红色边框标记
    private String lastHighlightedText = null;        // cache key / 缓存键
    private java.util.List<Token> cachedTokens = null; // cached tokenization result / 缓存的词法分析结果

    // ── Autocomplete / 自动补全 ──
    private final FormulaSuggestPopup suggestPopup = new FormulaSuggestPopup();
    private java.util.function.BiFunction<String, GraphNode, java.util.List<FormulaSuggestPopup.Candidate>> completionProvider;
    private GraphNode formulaNode;

    /** Default syntax-highlight palette.  Callers may override via {@link #setTokenPalette}.
     *  默认语法高亮调色板。调用者可通过 {@link #setTokenPalette} 覆盖。 */
    public static final int[] DEFAULT_PALETTE = createDefaultPalette();

    private static int[] createDefaultPalette() {
        int[] p = new int[TokType.values().length];
        p[TokType.FUNCTION.ordinal()]   = 0xFFE6C84D; // yellow / 黄色 — 函数
        p[TokType.CONSTANT.ordinal()]   = 0xFFFF8FC7; // pink / 粉色 — 常量 (PI)/(E)
        p[TokType.IDENT.ordinal()]      = 0xFF7FD8D8; // light cyan / 浅青色 — 标识符/变量
        p[TokType.NUMBER.ordinal()]     = 0xFFFF9D5C; // orange / 橙色 — 数字
        p[TokType.OPERATOR.ordinal()]   = 0xFFD0D0D0; // grey-white / 灰白色 — 运算符
        p[TokType.LPAREN.ordinal()]     = 0xFFD0D0D0; // grey-white / 灰白色 — 左括号
        p[TokType.RPAREN.ordinal()]     = 0xFFD0D0D0; // grey-white / 灰白色 — 右括号
        p[TokType.COMMENT.ordinal()]    = 0xFF6FA86F; // dark green / 深绿色 — 注释
        p[TokType.AT_OUTPUT.ordinal()]  = 0xFFC78BDA; // purple / 紫色 — @output
        p[TokType.ASSIGN.ordinal()]     = 0xFFC78BDA; // purple / 紫色 — 赋值
        p[TokType.UNKNOWN.ordinal()]    = 0xFFFF6B6B; // red / 红色 — 未知/非法字符
        p[TokType.LBRACE.ordinal()]     = 0xFFD0D0D0; // grey-white / 灰白色 — 左大括号
        p[TokType.RBRACE.ordinal()]     = 0xFFD0D0D0; // grey-white / 灰白色 — 右大括号
        p[TokType.KEYWORD.ordinal()]    = 0xFF6FC3FF; // light blue / 浅蓝色 — 控制流关键字
        p[TokType.SWIZZLE.ordinal()]    = 0xFF7FD8D8; // light cyan / 浅青色 — 分量访问(与变量同色)
        p[TokType.SEMICOLON.ordinal()]  = 0xFFD0D0D0; // grey-white / 灰白色 — 语句分隔符
        return p;
    }

    // ── Palette / highlighter / completion setters / 调色板·高亮·补全设置器 ──

    public void setBackgroundColor(int color) { this.backgroundColor = color; }
    public void setTextColor(int color) { this.textColor = color; }
    public void setCursorColor(int color) { this.cursorColor = color; }
    public void setDrawBorder(boolean draw) { this.drawMleBorder = draw; }

    /** Dismiss popup when losing focus / 失去焦点时关闭候选框 */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) suggestPopup.close();
    }

    /** Set the tokenizer for syntax highlighting / 设置语法高亮的词法分析器 */
    public void setHighlighter(java.util.function.Function<String, java.util.List<Token>> h) { this.highlighter = h; }
    /** Set the colour palette indexed by {@link TokType#ordinal()} / 设置按 {@link TokType#ordinal()} 索引的调色板 */
    public void setTokenPalette(int[] palette) { this.tokenPalette = palette; }
    /** Mark the editor as having validation errors (red border) / 标记编辑器存在校验错误（红色边框） */
    public void setHasError(boolean err) { this.hasError = err; }
    /** Set the completion provider and the node whose pins provide variable suggestions.
     *  设置补全提供器和用于变量建议的节点。 */
    public void setCompletionProvider(
            java.util.function.BiFunction<String, GraphNode, java.util.List<FormulaSuggestPopup.Candidate>> provider,
            GraphNode node) {
        this.completionProvider = provider;
        this.formulaNode = node;
    }
    /** Expose the popup so that {@code NodeRenderer} can render it at C=5.5.
     *  暴露弹出框供 {@code NodeRenderer} 在 C=5.5 渲染。 */
    public FormulaSuggestPopup getSuggestPopup() { return suggestPopup; }

    /** Compute the caret's (x, y) position relative to this widget's origin.
     *  The returned y is the bottom of the text line + 1px padding — suitable
     *  for anchoring a suggestion popup just below the caret line.
     *  计算光标相对于此组件原点的 (x, y) 位置。
     *  返回的 y 是文本行底部 + 1px 间距 —— 适合作为候选框锚点。 */
    public int[] getCaretLocalXY() {
        buildVisualLines();
        String text = getValue();
        int cursor = getCursorPosition();
        int cl = getLineOf(cursor);
        int cc = cursor - getLineStart(cl);
        int vi = findVisualLine(cl, cc);
        if (vi < 0 || vi >= visualLines.size()) return new int[]{2, 3};
        VLine vl = visualLines.get(vi);
        int ls = getLineStart(vl.logLine);
        String chunk = text.substring(ls + vl.charStart, ls + Math.min(vl.charEnd, text.length() - ls));
        int visCol = Math.min(cc - vl.charStart, chunk.length());
        int x = 2 + font.width(chunk.substring(0, Math.max(0, visCol)));
        // y = top-padding + visual-line-offset + font-height + 1px gap
        int y = 3 + vi * lineHeight() + font.lineHeight + 1;
        return new int[]{x, y};
    }

    /** 脚本最大长度(刀5 火控脚本 ~5KB;16KB 对 NBT/网络包仍轻量)/ max script length
     *  (knife-5 fire-control scripts run ~5KB; 16KB stays light for NBT/network packets). */
    public static final int MAX_LENGTH = 16384;

    public MultiLineEditBox(Font font, int x, int y, int width, int height) {
        super(font, x, y, width, height, Component.empty());
        this.font = font;
        setMaxLength(MAX_LENGTH);
    }

    // ==================== Line utilities ====================

    public int getLineCount() {
        String text = getValue();
        if (text.isEmpty()) return 1;
        int count = 1;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\n') count++;
        return count;
    }

    public int getLineStart(int line) {
        if (line <= 0) return 0;
        String text = getValue();
        int lc = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') { lc++; if (lc == line) return i + 1; }
        }
        return text.length();
    }

    public int getLineEnd(int line) {
        String text = getValue();
        int start = getLineStart(line);
        int end = start;
        while (end < text.length() && text.charAt(end) != '\n') end++;
        return end;
    }

    public int getCursorLine() { return getLineOf(getCursorPosition()); }
    public int getCursorColumn() { return getCursorPosition() - getLineStart(getCursorLine()); }

    private int getLineOf(int pos) {
        String text = getValue();
        int line = 0;
        for (int i = 0; i < pos && i < text.length(); i++)
            if (text.charAt(i) == '\n') line++;
        return line;
    }

    /** Build the visual line map, skipping if content and width unchanged */
    private void buildVisualLines() {
        String text = getValue();
        int w = getWidth();
        if (text.equals(lastBuiltText) && w == lastBuiltWidth && !visualLines.isEmpty()) return;
        lastBuiltText = text;
        lastBuiltWidth = w;
        visualLines.clear();
        int availW = w - 4;
        if (availW <= 0) { availW = 100; }
        for (int li = 0; li < getLineCount(); li++) {
            int ls = getLineStart(li), le = getLineEnd(li);
            String lineText = text.substring(ls, le);
            if (lineText.isEmpty()) {
                VLine vl = new VLine(); vl.logLine = li; vl.charStart = 0; vl.charEnd = 0;
                visualLines.add(vl);
                continue;
            }
            // Word wrap: split line into chunks that fit available width
            int pos = 0;
            while (pos < lineText.length()) {
                // Use font.plainSubstrByWidth for O(log n) binary search instead of O(n²) substring loop
                String remaining = lineText.substring(pos);
                String fitted = font.plainSubstrByWidth(remaining, availW, false);
                int fit = fitted.length();
                if (fit == 0 && pos < lineText.length()) fit = 1; // force at least 1 char
                VLine vl = new VLine();
                vl.logLine = li;
                vl.charStart = pos;
                vl.charEnd = pos + fit;
                visualLines.add(vl);
                pos += fit;
            }
        }
    }

    /** Find which visual line corresponds to (logLine, col) */
    private int findVisualLine(int logLine, int col) {
        for (int vi = 0; vi < visualLines.size(); vi++) {
            VLine vl = visualLines.get(vi);
            if (vl.logLine == logLine && col >= vl.charStart && (col < vl.charEnd || (col == vl.charEnd && vl.charEnd == getLineEnd(logLine) - getLineStart(logLine))))
                return vi;
        }
        return Math.max(0, visualLines.size() - 1);
    }

    /** Get total visual line count */
    public int getVisualLineCount() {
        if (visualLines.isEmpty()) buildVisualLines();
        return visualLines.size();
    }

    // ==================== Rendering ====================

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        if (!isVisible()) return;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), backgroundColor);
        if (drawMleBorder) {
            int borderColor = isFocused()
                ? (hasError ? 0xFFFF4444 : 0xFFFFFFFF)
                : (hasError ? 0xFFAA3333 : 0xFFA0A0A0);
            g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY(), borderColor);
            g.fill(getX() - 1, getY() + getHeight(), getX() + getWidth() + 1, getY() + getHeight() + 1, borderColor);
            g.fill(getX() - 1, getY(), getX(), getY() + getHeight(), borderColor);
            g.fill(getX() + getWidth(), getY(), getX() + getWidth() + 1, getY() + getHeight(), borderColor);
        }

        buildVisualLines();
        String text = getValue();
        int cursorLine = getCursorLine();
        int cursorCol = getCursorColumn();
        int cursorVisLine = findVisualLine(cursorLine, cursorCol);

        // Token list for syntax highlighting (null if no highlighter set).
        // Cache tokenization — only re-tokenize when text changes.
        if (highlighter != null && !text.equals(lastHighlightedText)) {
            cachedTokens = highlighter.apply(text);
            lastHighlightedText = text;
        }
        java.util.List<Token> tokens = cachedTokens;
        int[] palette = tokenPalette != null ? tokenPalette : DEFAULT_PALETTE;
        int tokIdx = 0; // cursor into the token list for efficient scanning

        for (int vi = 0; vi < visualLines.size(); vi++) {
            int y = getY() + 3 + vi * lineHeight();
            if (y + lineHeight() > getY() + getHeight()) break;

            VLine vl = visualLines.get(vi);
            int ls = getLineStart(vl.logLine);
            String chunk = text.substring(ls + vl.charStart, ls + vl.charEnd);
            int chunkStartGlobal = ls + vl.charStart;
            int chunkEndGlobal = ls + vl.charEnd;
            int drawX = getX() + 2;

            // Selection bookkeeping
            int selA = Math.min(getCursorPosition(), selAnchor());
            int selB = Math.max(getCursorPosition(), selAnchor());
            boolean hasSel = selA != selB;
            int selStartInChunk = hasSel ? Math.max(0, selA - chunkStartGlobal) : 0;
            int selEndInChunk = hasSel ? Math.min(chunk.length(), selB - chunkStartGlobal) : 0;

            if (tokens == null || tokens.isEmpty()) {
                // ── No highlighter: fallback to uniform-colour rendering ──
                if (hasSel && selStartInChunk < selEndInChunk) {
                    int selX1 = drawX + font.width(chunk.substring(0, selStartInChunk));
                    int selX2 = drawX + font.width(chunk.substring(0, selEndInChunk));
                    g.fill(selX1, y - 1, selX2, y + font.lineHeight, 0xFF2B5A8C);
                    g.drawString(font, chunk.substring(0, selStartInChunk), drawX, y, textColor, false);
                    g.drawString(font, chunk.substring(selStartInChunk, selEndInChunk), selX1, y, 0xFFFFFFFF, false);
                    g.drawString(font, chunk.substring(selEndInChunk), selX2, y, textColor, false);
                } else {
                    g.drawString(font, chunk, drawX, y, textColor, false);
                }
            } else {
                // ── Syntax-highlighted rendering: segment chunk by intersecting tokens ──
                // Draw selection background first (spanning the entire selected range in this chunk)
                if (hasSel && selStartInChunk < selEndInChunk) {
                    int selX1 = drawX + font.width(chunk.substring(0, selStartInChunk));
                    int selX2 = drawX + font.width(chunk.substring(0, selEndInChunk));
                    g.fill(selX1, y - 1, selX2, y + font.lineHeight, 0xFF2B5A8C);
                }
                // Advance tokIdx past tokens that end before this chunk
                while (tokIdx < tokens.size() && tokens.get(tokIdx).end() <= chunkStartGlobal) tokIdx++;

                int posInChunk = 0; // how many chars of the chunk we've rendered so far
                int curTok = tokIdx;
                while (curTok < tokens.size() && posInChunk < chunk.length()) {
                    Token tk = tokens.get(curTok);
                    if (tk.start() >= chunkEndGlobal) break;
                    // Intersection of token range with chunk range
                    int segStartGlobal = Math.max(tk.start(), chunkStartGlobal);
                    int segEndGlobal   = Math.min(tk.end(),   chunkEndGlobal);
                    if (segStartGlobal >= segEndGlobal) { curTok++; continue; }

                    // Map global offsets → chunk-relative indices
                    int segStart = segStartGlobal - chunkStartGlobal;
                    int segEnd   = segEndGlobal   - chunkStartGlobal;

                    // If there is a gap before this segment, render it in default colour
                    if (segStart > posInChunk) {
                        String gap = chunk.substring(posInChunk, segStart);
                        drawChunkSegment(g, font, gap, drawX, y, textColor, null, 0,
                            selStartInChunk, selEndInChunk, posInChunk);
                        drawX += font.width(gap);
                        posInChunk = segStart;
                    }

                    String segText = chunk.substring(segStart, segEnd);
                    int segColor = (tk.type().ordinal() < palette.length) ? palette[tk.type().ordinal()] : textColor;
                    drawChunkSegment(g, font, segText, drawX, y, segColor,
                        tk.type() == TokType.UNKNOWN ? 0xFFFF4444 : null, lineHeight(),
                        selStartInChunk, selEndInChunk, posInChunk);
                    drawX += font.width(segText);
                    posInChunk = segEnd;
                    curTok++;
                }
                // Trailing text after the last token
                if (posInChunk < chunk.length()) {
                    String tail = chunk.substring(posInChunk);
                    drawChunkSegment(g, font, tail, drawX, y, textColor, null, 0,
                        selStartInChunk, selEndInChunk, posInChunk);
                }
            }

            // Blinking cursor
            if (isFocused() && vi == cursorVisLine
                && System.currentTimeMillis() / 500 % 2 == 0) {
                int visCol = cursorCol - vl.charStart;
                if (visCol >= 0 && visCol <= chunk.length()) {
                    int curX = getX() + 2 + font.width(chunk.substring(0, Math.min(visCol, chunk.length())));
                    g.fill(curX, y - 1, curX + 1, y + font.lineHeight, cursorColor);
                }
            }
        }

        // ── Update popup anchor from cursor position (no visualLines dependency) ──
        // 用光标位置更新候选框锚点（不依赖 visualLines）
        {
            int ls = getLineStart(cursorLine);
            String lineText = text.substring(ls, Math.min(ls + cursorCol, text.length()));
            suggestPopup.anchorX = 2 + font.width(lineText);
            suggestPopup.anchorY = 3 + cursorLine * lineHeight() + font.lineHeight;
        }
    }

    /** Draw a segment of a visual-line chunk, respecting selection highlight.
     *  If {@code errorColor} is non-null and non-zero, draw a 1px underline in that colour.
     *  {@code chunkOffset} is the position of this segment within the chunk.
     *  绘制视觉行的一个片段，正确处理选区高亮。errorColor 非 null 且非零时画 1px 下划线。 */
    private static void drawChunkSegment(GuiGraphics g, Font font, String text,
            float drawX, int y, int color, Integer errorColor, int lineH,
            int selStart, int selEnd, int chunkOffset) {
        int len = text.length();
        int segEnd = chunkOffset + len;
        // No overlap with selection → draw uniformly
        if (selStart >= selEnd || segEnd <= selStart || chunkOffset >= selEnd) {
            g.drawString(font, text, (int)drawX, y, color, false);
        } else {
            // Split segment around selection
            int preLen = Math.max(0, selStart - chunkOffset);
            int selLen = Math.min(len - preLen, selEnd - Math.max(chunkOffset, selStart));
            // Pre-selection
            if (preLen > 0) g.drawString(font, text.substring(0, preLen), (int)drawX, y, color, false);
            // Selection
            if (selLen > 0) {
                int selX = (int)drawX + font.width(text.substring(0, preLen));
                g.drawString(font, text.substring(preLen, preLen + selLen), selX, y, 0xFFFFFFFF, false);
                // Post-selection
                int postStart = preLen + selLen;
                if (postStart < len) {
                    int postX = (int)drawX + font.width(text.substring(0, postStart));
                    g.drawString(font, text.substring(postStart), postX, y, color, false);
                }
            }
        }
        // Error underline
        if (errorColor != null && errorColor != 0) {
            int uw = font.width(text);
            g.fill((int)drawX, y + font.lineHeight, (int)drawX + uw, y + font.lineHeight + 1, errorColor);
        }
    }

    // ==================== Keyboard ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;

        // ── Autocomplete popup visible: route keys ──
        if (suggestPopup.isVisible()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                suggestPopup.close();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                String insert = suggestPopup.acceptSelected();
                if (insert != null) replaceCurrentWord(insert);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP)   { suggestPopup.moveUp();   return true; }
            if (keyCode == GLFW.GLFW_KEY_DOWN) { suggestPopup.moveDown(); return true; }
            // Any other key: close popup, fall through to normal handling
            suggestPopup.close();
        }

        String text = getValue();
        int cursor = getCursorPosition();
        int cursorLine = getCursorLine();

        // Ctrl+A / Ctrl+C / Ctrl+X / Ctrl+V — handled before per-key logic
        if (keyCode == GLFW.GLFW_KEY_A && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Select all
            setCursorPosition(getValue().length());
            setHighlightPos(0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Copy: delegate to parent
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Paste: my insertText already handles \r\n → \n
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_X && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Cut: delegate to parent
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        return switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                String before = text.substring(0, cursor);
                String after = text.substring(cursor);
                setValue(before + "\n" + after);
                setCursorPosition(cursor + 1);
                setHighlightPos(cursor + 1); setSelAnchor(cursor + 1); // sync after setValue→moveCursorToEnd
                fireResponder();
                yield true;
            }
            case GLFW.GLFW_KEY_UP -> {
                int col = getCursorColumn();
                int curVL = findVisualLine(cursorLine, col);
                int newPos;
                if (curVL > 0) {
                    VLine prevVL = visualLines.get(curVL - 1);
                    int prevStart = getLineStart(prevVL.logLine);
                    int newCol = Math.min(col, prevVL.charEnd - 1);
                    newPos = prevStart + newCol;
                } else { newPos = 0; }
                setCursorPosition(newPos);
                // Arrow keys (without Shift) collapse selection / 方向键（无 Shift）折叠选区
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                int col = getCursorColumn();
                int curVL = findVisualLine(cursorLine, col);
                int newPos;
                if (curVL < visualLines.size() - 1) {
                    VLine nextVL = visualLines.get(curVL + 1);
                    int nextStart = getLineStart(nextVL.logLine);
                    int newCol = Math.min(col, nextVL.charEnd - 1);
                    newPos = nextStart + newCol;
                } else { newPos = text.length(); }
                setCursorPosition(newPos);
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int curVL = findVisualLine(cursorLine, getCursorColumn());
                int newPos = getLineStart(visualLines.get(curVL).logLine) + visualLines.get(curVL).charStart;
                setCursorPosition(newPos);
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                int curVL = findVisualLine(cursorLine, getCursorColumn());
                int newPos = getLineStart(visualLines.get(curVL).logLine) + visualLines.get(curVL).charEnd;
                setCursorPosition(newPos);
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                int newPos = cursor > 0 ? cursor - 1 : 0;
                setCursorPosition(newPos);
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                int newPos = cursor < text.length() ? cursor + 1 : cursor;
                setCursorPosition(newPos);
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) == 0) {
                    setHighlightPos(newPos); setSelAnchor(newPos);
                }
                yield true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> { deleteText(-1); yield true; }
            case GLFW.GLFW_KEY_DELETE -> { deleteText(1); yield true; }
            default -> super.keyPressed(keyCode, scanCode, modifiers);
        };
    }

    // ==================== Autocomplete helpers ====================

    /** Override charTyped to trigger completion on identifier-char input.
     *  重写 charTyped 以在输入标识符字符时触发补全。 */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // Force-insert '@' — MC may reject it in isAllowedChatCharacter / 强制插入 @ — MC 可能拒绝它
        if (codePoint == '@') {
            insertText("@");
            if (completionProvider != null) triggerCompletion();
            return true;
        }
        boolean result = super.charTyped(codePoint, modifiers);
        if (completionProvider != null) triggerCompletion();
        return result;
    }

    /** Compute the current word prefix at cursor and show the suggestion popup.
     *  计算光标处的当前单词前缀并显示建议候选框。 */
    private void triggerCompletion() {
        String text = getValue();
        int cursor = getCursorPosition();
        if (cursor <= 0 || cursor > text.length()) { suggestPopup.close(); return; }

        // ── @ completion: show @output when user types '@' ──
        char atCursor = text.charAt(cursor - 1);
        if (atCursor == '@') {
            var cand = java.util.List.of(
                new FormulaSuggestPopup.Candidate("@output", "declare output", "@output "));
            int cl = getCursorLine();
            int ls = getLineStart(cl);
            int x = 2 + font.width(getValue().substring(ls, getCursorPosition()));
            int y = 3 + cl * lineHeight() + font.lineHeight;
            suggestPopup.open(x, y, cand);
            return;
        }

        // Walk backwards from cursor-1 to find the start of the current identifier
        int start = cursor - 1;
        while (start >= 0) {
            char c = text.charAt(start);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_') start--;
            else break;
        }
        start++; // first char of the identifier
        String prefix = text.substring(start, cursor);
        if (prefix.isEmpty()) { suggestPopup.close(); return; }

        // Build candidates
        var allCandidates = completionProvider.apply(text, formulaNode);
        if (allCandidates == null || allCandidates.isEmpty()) { suggestPopup.close(); return; }
        var filtered = FormulaSuggestPopup.filter(allCandidates, prefix, 8);
        // Remove exact prefix matches: don't suggest the word the user already typed
        filtered.removeIf(c -> c.name().equalsIgnoreCase(prefix));
        if (filtered.isEmpty()) { suggestPopup.close(); return; }

        // Anchor at caret position — computed directly, no visualLines dependency.
        int cl = getCursorLine();
        int ls = getLineStart(cl);
        int x = 2 + font.width(text.substring(ls, cursor));
        int y = 3 + cl * lineHeight() + font.lineHeight;
        suggestPopup.open(x, y, filtered);
    }

    /** Public entry point for popup click-to-accept (called from GraphEditor). */
    public void replaceCurrentWordForPopup(String replacement) { replaceCurrentWord(replacement); }

    /** Replace the word at the cursor with the given text. Used to accept a completion.
     *  Note: {@code setValue()} already triggers the responder via EditBox internals;
     *  we do not call {@code fireResponder()} again to avoid double-send. */
    private void replaceCurrentWord(String replacement) {
        String text = getValue();
        int cursor = getCursorPosition();
        if (replacement == null || replacement.isEmpty()) return;
        // Find word boundaries at cursor
        int end = cursor;
        while (end < text.length()) {
            char c = text.charAt(end);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '@') end++;
            else break;
        }
        int start = cursor > 0 ? cursor - 1 : 0;
        while (start >= 0) {
            char c = text.charAt(start);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '@') start--;
            else break;
        }
        start++; // first char of current word
        String before = text.substring(0, start);
        String after = text.substring(end);
        setValue(before + replacement + after);
        setCursorPosition(start + replacement.length());
        setHighlightPos(start + replacement.length());
        setSelAnchor(start + replacement.length());
        // setValue already calls onValueChange → responder; no explicit fireResponder needed
    }

    // ==================== Responder ====================

    private int hlPos = 0; // selection anchor (mirrors parent's private highlightPos)

    /** Get the selection anchor position (parent EditBox.getHighlightPos is private in MC 1.21) */
    private int selAnchor() { return hlPos; }
    private void setSelAnchor(int pos) { this.hlPos = Mth.clamp(pos, 0, getValue().length()); }

    @Override
    public void setHighlightPos(int pos) {
        super.setHighlightPos(pos);
        this.hlPos = Mth.clamp(pos, 0, getValue().length());
    }

    @Override
    public void setCursorPosition(int pos) {
        super.setCursorPosition(pos);
        // NOTE: Do NOT sync hlPos here — that would break mouse-drag
        // selection by overwriting the anchor set in mouseClicked.
        // Callers that need the anchor synced (insertText, deleteText,
        // replaceCurrentWord) do so explicitly via setSelAnchor().
        // 注意：不要在这里同步 hlPos——那会覆盖 mouseClicked 设置的锚点，
        // 导致鼠标拖拽选区失效。需要同步锚点的调用者（insertText、deleteText、
        // replaceCurrentWord）显式通过 setSelAnchor() 处理。
    }

    private java.util.function.Consumer<String> myResponder;

    @Override
    public void setResponder(java.util.function.Consumer<String> responder) {
        super.setResponder(responder);
        this.myResponder = responder;
    }

    private void fireResponder() {
        if (myResponder != null) myResponder.accept(getValue());
    }

    @Override
    public void insertText(String textToInsert) {
        // 输入即转:中文/全角符号实时转半角 ASCII(（）→()、×→* 等),显示与解析均为英文符号
        // Convert on input: CJK/full-width symbols become half-width ASCII live — display and parser both see English symbols
        String clean = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.sanitizeFullwidth(
            textToInsert.replace("\r\n", "\n").replace("\r", ""));
        int cursor = getCursorPosition();
        String before = getValue().substring(0, cursor);
        String after = getValue().substring(cursor);
        String combined = before + clean + after;
        if (combined.length() > MAX_LENGTH) return;
        setValue(combined);
        setCursorPosition(cursor + clean.length());
        setSelAnchor(cursor + clean.length()); // sync hlPos, setValue moved it to end
        fireResponder();
    }

    public void deleteText(int count) {
        int selStart = Math.min(getCursorPosition(), selAnchor());
        int selEnd = Math.max(getCursorPosition(), selAnchor());
        String text = getValue();
        if (selStart != selEnd) {
            // Delete selection
            setValue(text.substring(0, selStart) + text.substring(selEnd));
            setCursorPosition(selStart);
            setHighlightPos(selStart); setSelAnchor(selStart);
            fireResponder();
            return;
        }
        int cursor = getCursorPosition();
        if (count < 0) {
            int del = Math.min(-count, cursor);
            if (del <= 0) return;
            setValue(text.substring(0, cursor - del) + text.substring(cursor));
            setCursorPosition(cursor - del);
            setHighlightPos(cursor - del); setSelAnchor(cursor - del);
        } else {
            int del = Math.min(count, text.length() - cursor);
            if (del <= 0) return;
            setValue(text.substring(0, cursor) + text.substring(cursor + del));
            // Restore cursor position: setValue() calls moveCursorToEnd() internally,
            // which moves both cursor and highlightPos to the end. Without this restore
            // selAnchor() would return a stale hlPos on the next keystroke → entire
            // selection range deleted instead of a single character.
            setCursorPosition(cursor);
            setHighlightPos(cursor); setSelAnchor(cursor);
        }
        fireResponder();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isVisible()) return false;
        if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
            return false;
        suggestPopup.close(); // cursor moved — dismiss popup
        setFocused(true);
        buildVisualLines();
        int relY = (int)(my - getY() - 3);
        int clickedVL = Mth.clamp(relY / lineHeight(), 0, visualLines.size() - 1);
        VLine vl = visualLines.get(clickedVL);
        int ls = getLineStart(vl.logLine);
        String lineText = getValue().substring(ls + vl.charStart, ls + vl.charEnd);

        int relX = (int)(mx - getX() - 2);
        int bestCol = 0;
        for (int c = 0; c <= lineText.length(); c++) {
            if (font.width(lineText.substring(0, c)) <= relX) bestCol = c;
            else break;
        }
        int newPos = ls + vl.charStart + bestCol;
        setCursorPosition(newPos);
        setHighlightPos(newPos); setSelAnchor(newPos); // reset selection on click
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!isVisible() || !isFocused()) return false;
        buildVisualLines();
        int relY = (int)(my - getY() - 3);
        int dragVL = Mth.clamp(relY / lineHeight(), 0, visualLines.size() - 1);
        VLine vl = visualLines.get(dragVL);
        int ls = getLineStart(vl.logLine);
        String lineText = getValue().substring(ls + vl.charStart, ls + vl.charEnd);
        int relX = (int)(mx - getX() - 2);
        int bestCol = 0;
        for (int c = 0; c <= lineText.length(); c++) {
            if (font.width(lineText.substring(0, c)) <= relX) bestCol = c;
            else break;
        }
        // Move cursor to drag position; anchor stays where mouseClicked set it
        setCursorPosition(ls + vl.charStart + bestCol);
        return true;
    }

    /** Visual line count for a logical line (for prefix alignment in EditPanel) */
    public int visualLinesForLogicalLine(int logLine) {
        int count = 0;
        for (VLine vl : visualLines) if (vl.logLine == logLine) count++;
        return Math.max(1, count);
    }

    public int getContentHeight() {
        return Math.max(visualLines.size() * lineHeight() + 6, lineHeight() + 6);
    }
}
