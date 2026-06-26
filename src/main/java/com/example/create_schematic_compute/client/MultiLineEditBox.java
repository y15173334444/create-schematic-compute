package com.example.create_schematic_compute.client;

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
    private static final int LINE_HEIGHT = 12;
    private final Font font;

    // Word-wrap visual line mapping; rebuilt only when content or width changes
    private static class VLine { int logLine; int charStart; int charEnd; }
    private final java.util.ArrayList<VLine> visualLines = new java.util.ArrayList<>();
    private String lastBuiltText = null;
    private int lastBuiltWidth = -1;

    public MultiLineEditBox(Font font, int x, int y, int width, int height) {
        super(font, x, y, width, height, Component.empty());
        this.font = font;
        setMaxLength(4096);
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
        int bgColor = 0xFF000000;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bgColor);
        int borderColor = isFocused() ? 0xFFFFFFFF : 0xFFA0A0A0;
        g.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY(), borderColor);
        g.fill(getX() - 1, getY() + getHeight(), getX() + getWidth() + 1, getY() + getHeight() + 1, borderColor);
        g.fill(getX() - 1, getY(), getX(), getY() + getHeight(), borderColor);
        g.fill(getX() + getWidth(), getY(), getX() + getWidth() + 1, getY() + getHeight(), borderColor);

        buildVisualLines();
        String text = getValue();
        int cursorLine = getCursorLine();
        int cursorCol = getCursorColumn();
        int cursorVisLine = findVisualLine(cursorLine, cursorCol);
        int textColor = 0xFFE0E0E0;

        for (int vi = 0; vi < visualLines.size(); vi++) {
            int y = getY() + 3 + vi * LINE_HEIGHT;
            if (y + LINE_HEIGHT > getY() + getHeight()) break;

            VLine vl = visualLines.get(vi);
            int ls = getLineStart(vl.logLine);
            String chunk = text.substring(ls + vl.charStart, ls + vl.charEnd);

            int drawX = getX() + 2;

            // Selection highlight
            int selA = Math.min(getCursorPosition(), selAnchor());
            int selB = Math.max(getCursorPosition(), selAnchor());
            boolean hasSel = selA != selB;
            int chunkStartGlobal = ls + vl.charStart;
            int chunkEndGlobal = ls + vl.charEnd;
            int selStartInChunk = hasSel ? Math.max(0, selA - chunkStartGlobal) : 0;
            int selEndInChunk = hasSel ? Math.min(chunk.length(), selB - chunkStartGlobal) : 0;

            if (hasSel && selStartInChunk < selEndInChunk) {
                // Draw selection background and white text for selected portion
                int selX1 = drawX + font.width(chunk.substring(0, selStartInChunk));
                int selX2 = drawX + font.width(chunk.substring(0, selEndInChunk));
                g.fill(selX1, y - 1, selX2, y + font.lineHeight, 0xFF2B5A8C);
                // Unselected left portion
                g.drawString(font, chunk.substring(0, selStartInChunk), drawX, y, textColor, false);
                // Selected portion in white
                g.drawString(font, chunk.substring(selStartInChunk, selEndInChunk), selX1, y, 0xFFFFFFFF, false);
                // Unselected right portion
                g.drawString(font, chunk.substring(selEndInChunk), selX2, y, textColor, false);
            } else {
                g.drawString(font, chunk, drawX, y, textColor, false);
            }

            if (isFocused() && vi == cursorVisLine
                && System.currentTimeMillis() / 500 % 2 == 0) {
                int visCol = cursorCol - vl.charStart;
                if (visCol >= 0 && visCol <= chunk.length()) {
                    int curX = drawX + font.width(chunk.substring(0, Math.min(visCol, chunk.length())));
                    g.fill(curX, y - 1, curX + 1, y + font.lineHeight, 0xFFFFFFFF);
                }
            }
        }
    }

    // ==================== Keyboard ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;

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
                fireResponder();
                yield true;
            }
            case GLFW.GLFW_KEY_UP -> {
                int col = getCursorColumn();
                int curVL = findVisualLine(cursorLine, col);
                if (curVL > 0) {
                    VLine prevVL = visualLines.get(curVL - 1);
                    int prevStart = getLineStart(prevVL.logLine);
                    int newCol = Math.min(col, prevVL.charEnd - 1);
                    setCursorPosition(prevStart + newCol);
                } else { setCursorPosition(0); }
                yield true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                int col = getCursorColumn();
                int curVL = findVisualLine(cursorLine, col);
                if (curVL < visualLines.size() - 1) {
                    VLine nextVL = visualLines.get(curVL + 1);
                    int nextStart = getLineStart(nextVL.logLine);
                    int newCol = Math.min(col, nextVL.charEnd - 1);
                    setCursorPosition(nextStart + newCol);
                } else { setCursorPosition(text.length()); }
                yield true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                int curVL = findVisualLine(cursorLine, getCursorColumn());
                setCursorPosition(getLineStart(visualLines.get(curVL).logLine) + visualLines.get(curVL).charStart);
                yield true;
            }
            case GLFW.GLFW_KEY_END -> {
                int curVL = findVisualLine(cursorLine, getCursorColumn());
                setCursorPosition(getLineStart(visualLines.get(curVL).logLine) + visualLines.get(curVL).charEnd);
                yield true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) setCursorPosition(cursor - 1);
                yield true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < text.length()) setCursorPosition(cursor + 1);
                yield true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> { deleteText(-1); yield true; }
            case GLFW.GLFW_KEY_DELETE -> { deleteText(1); yield true; }
            default -> super.keyPressed(keyCode, scanCode, modifiers);
        };
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
        String clean = textToInsert.replace("\r\n", "\n").replace("\r", "");
        int cursor = getCursorPosition();
        String before = getValue().substring(0, cursor);
        String after = getValue().substring(cursor);
        String combined = before + clean + after;
        if (combined.length() > 4096) return;
        setValue(combined);
        setCursorPosition(cursor + clean.length());
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
        }
        fireResponder();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!isVisible()) return false;
        if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
            return false;
        setFocused(true);
        buildVisualLines();
        int relY = (int)(my - getY() - 3);
        int clickedVL = Mth.clamp(relY / LINE_HEIGHT, 0, visualLines.size() - 1);
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
        int dragVL = Mth.clamp(relY / LINE_HEIGHT, 0, visualLines.size() - 1);
        VLine vl = visualLines.get(dragVL);
        int ls = getLineStart(vl.logLine);
        String lineText = getValue().substring(ls + vl.charStart, ls + vl.charEnd);
        int relX = (int)(mx - getX() - 2);
        int bestCol = 0;
        for (int c = 0; c <= lineText.length(); c++) {
            if (font.width(lineText.substring(0, c)) <= relX) bestCol = c;
            else break;
        }
        // Move cursor to drag position (highlight stays at original click position)
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
        return Math.max(visualLines.size() * LINE_HEIGHT + 6, LINE_HEIGHT + 6);
    }
}
