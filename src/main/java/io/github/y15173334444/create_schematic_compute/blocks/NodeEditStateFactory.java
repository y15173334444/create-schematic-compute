package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerButton;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 节点编辑状态工厂（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6a）：
 * 按节点类型构建展开面板的 {@link GraphEditor.EditState}（参数框 / 信号名 / 频段 / 文本 /
 * 取色按钮 / 公式编辑器 / 调试信号发生器条件面板），以及调试信号发生器的模式切换状态机。
 * The node edit-state factory (split out of {@link GraphEditor}, roadmap step 6a): builds the
 * expanded panel's {@link GraphEditor.EditState} per node type — param boxes, signal name,
 * bands, text, colour buttons, the formula editor and the debug signal generator's conditional
 * panel — plus the debug generator's mode-toggle state machine.
 *
 * <p><b>行为零变更</b>：实现逐字搬迁。 lambdas 捕获的 op 记录 / 发送（recordOp / host.sendOp）、
 * enterActions 注册、回显抑制标志等编辑器内部成员经传入的 {@code ed} 引用访问（同包，成员已放宽
 * 到包级）；视图/画笔参数（zoom / colorPicker）同样经 ed 取用。返回的 EditState 仍由编辑器
 * 持有（nodeEditStatesById）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim. The op recording/sending (recordOp /
 * host.sendOp), enterActions registration and the echo-suppression flag captured by the lambdas
 * are reached through the passed-in {@code ed} reference (same package; members widened to
 * package level), as are the view/brush parameters (zoom / colorPicker). The returned EditState
 * is still owned by the editor (nodeEditStatesById).</p>
 */
final class NodeEditStateFactory {

    private NodeEditStateFactory() { }

    static GraphEditor.EditState create(GraphEditor ed, GraphNode node) {
        // 保存旧状态引用（供 busBox 保留输入值） (Save old state ref for busBox value preservation)
        final var oldStRef = ed.nodeEditStatesById.get(node.id);
        // ── 焦点保留（1/2）：重建 EditState 会把所有输入框换成新实例，新实例默认未聚焦。
        // 打字时本地 op 的服务器回声会走重建路径，焦点就被悄悄夺走。这里先记下聚焦字段的
        // 下标与光标位置，重建后按下标还原（字段顺序确定：参数 → 总线名/频段 → 该类型文本框）。
        // Focus preservation (1/2): a rebuild replaces every box and fresh boxes start
        // unfocused, so the echo of the user own op steals focus mid-typing. Record the
        // focused field index + caret and restore them on the rebuilt field of the same index.
        int prevFocusIdx = -1;
        int prevFocusCursor = -1;
        if (oldStRef != null) {
            for (int fi = 0; fi < oldStRef.fields.size(); fi++) {
                if (oldStRef.fields.get(fi).isFocused()) {
                    prevFocusIdx = fi;
                    prevFocusCursor = oldStRef.fields.get(fi).getCursorPosition();
                    break;
                }
            }
        }
        // 先移除本节点的旧 EditState（避免旧 EditBox 仍在旧 state 中被保留） (Remove old EditState first to avoid stale EditBox references)
        ed.nodeEditStatesById.remove(node.id);
        // 清除不再被任何 EditState 引用的旧 EditBox 的 enterActions (Clean up enterActions for old EditBoxes no longer referenced)
        ed.enterActions.keySet().removeIf(eb -> {
            for (var st : ed.nodeEditStatesById.values())
                if (st.fields.contains(eb)) return false;
            return true;
        });
        var s = new GraphEditor.EditState();
        s.graph = ed.getGraph(); // 用于检查参数引脚连线状态 (Used to check param pin connection state)
        s.paramKeys = node.type.paramNames.clone();
        var mc = Minecraft.getInstance();
        for (int i = 0; i < node.params.length; i++) {
            if (node.type == NodeType.BOOL || node.type == NodeType.GATE || node.type == NodeType.T_FLIPFLOP || node.type == NodeType.LATCH || node.type == NodeType.KEYBOARD || node.type == NodeType.GAMEPAD_BUTTON
                || node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT
                || node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE
                || node.type == NodeType.DEBUG_SIGNAL_GEN || node.type == NodeType.MOUSE_JOYSTICK
                // FORMULA 的 warm 参数(刀5)由编辑区自己渲染为切换按钮(GATE 同款),不走通用 EditBox——EditBox 会抢走脚本编辑区的键盘焦点
                // FORMULA's warm param (knife 5) renders as its own toggle button (GATE-style) — a generic EditBox would steal keyboard focus from the script editor
                || node.type == NodeType.FORMULA) continue;
            // 参数输入引脚已连线 → 阻止折叠（值由连线提供，但引脚仍可见） (Param input pin has connection → block collapse; value driven by connection but pin still visible)
            // 刀5:参数引脚索引用 paramPinIndex(FORMULA 功能引脚数动态,参数在其后)
            // Knife 5: param pin index via paramPinIndex (FORMULA's functional count is dynamic, params follow it)
            int pinIdx = node.paramPinIndex(i);
            if (node.type.editableParamCount() > 0 && ed.getGraph().hasInputConnection(node.id, pinIdx)) {
                s.blockCollapse = true;
            }
            int idx = i;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(GraphEditor.ff3(node.params[i]));
            final float[] preEditParam = {node.params[idx]}; // captured before edit session / 编辑会话开始前捕获
            final float[] lastSentParam = {node.params[idx]};
            b.setResponder(text -> { try {
                if (ed.suppressEditBoxResponder) return; // remote SET_PARAM setValue → don't echo back
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentParam[0]) > 0.0001f) {
                    node.params[idx] = newV;
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, newV, ed.host.getPlayerUUID());
                    ed.host.sendOp(op); // sync to server, undo recorded on commit / 同步到服务器，撤销在提交时记录
                    lastSentParam[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            ed.enterActions.put(b, () -> {
                if (Math.abs(lastSentParam[0] - preEditParam[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, lastSentParam[0], ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, preEditParam[0], null);
                    preEditParam[0] = lastSentParam[0];
                }
            });
            s.fields.add(b);
            s.fieldParamIndices.add(i);
        }
        if ((node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) && node.itemParams.length < 2)
            node.itemParams = new net.minecraft.world.item.ItemStack[]{net.minecraft.world.item.ItemStack.EMPTY, net.minecraft.world.item.ItemStack.EMPTY};
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32); sb.setValue(node.signalName);
            final String[] preEditSig = {node.signalName}; // initial value for undo
            final String[] lastSig = {node.signalName};
            sb.setResponder(text -> {
                if (!text.equals(lastSig[0])) {
                    node.signalName = text;
                    // PRIVATE 信号没有频段定义（BAND_REGISTRY 是 BUS 频道的表），这里过去还会
                    // 逐键按客户端本地 BAND_REGISTRY 重写 signalBands —— 正是 #11/#15 铲掉的
                    // 「各端各自查表推导」模式的最后一处残留。PRIVATE 节点不暴露频段引脚，
                    // 服务端副本从来不做这种推导；客户端不得本端重算权威值，删。
                    // PRIVATE signals carry no band definition (BAND_REGISTRY belongs to BUS
                    // channels); this used to also rewrite signalBands from the client's local
                    // registry on every keystroke — the last leftover of the per-side derivation
                    // pattern #11/#15 removed. PRIVATE nodes expose no band pins and the server's
                    // copy never derived them; the client must not re-derive authoritative values.
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastSig[0] = text;
                }
            });
            ed.enterActions.put(sb, () -> {
                if (!lastSig[0].equals(preEditSig[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastSig[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, 0, preEditSig[0]);
                    preEditSig[0] = lastSig[0];
                }
            });
            s.fields.add(sb);
        }
        if (node.type == NodeType.BUS_IN || node.type == NodeType.BUS_OUT) {
            // 预计算编辑区引脚位置（避免连线首帧跳动） (Pre-compute edit-area pin positions to avoid first-frame connection jitter)
            int bandCnt = node.bandCount();
            if (bandCnt > 0) {
                s.bandPinY = new float[bandCnt];
                for (int i = 0; i < bandCnt; i++) {
                    s.bandPinY[i] = GraphEditor.bandPinY(node, i, ed.zoom);
                    // 检查频段引脚是否有连线，有则阻止折叠 (Check if band pin has a connection, block collapse if so)
                    if (node.type == NodeType.BUS_OUT && ed.getGraph().hasInputConnection(node.id, i))
                        s.blockCollapse = true;
                    else if (node.type == NodeType.BUS_IN) {
                        final int bi = i;
                        if (ed.getGraph().connections.stream().anyMatch(c -> c.fromId == node.id && c.fromPin == bi))
                            s.blockCollapse = true;
                    }
                }
            }
            // BUS_IN 展开时**不再**本地解析频段（issue #11）：频段列表是服务端权威数据，
            // 随图一起下发（打开编辑器会向服务端拉取权威图），展开时无需再从本地频段注册表
            // 或同图 BUS_OUT 推算 —— 那正是各端得出不同结果的来源。
            // No local band resolution when a BUS_IN expands (issue #11): the band list is
            // authoritative server data delivered with the graph (opening the editor pulls the
            // authoritative graph). Resolving it locally from the band registry or a same-graph
            // BUS_OUT was exactly what let different sides disagree.
            var busBox = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            busBox.setMaxLength(32); busBox.setValue(node.signalName);
            // busBox 不通过 enterActions 提交；保留旧聚焦 busBox 的输入值 (busBox not committed via enterActions; preserve old focused busBox input)
            var oldSt = oldStRef;
            s.busNode = node;
            if (oldSt != null && oldSt.busBox != null && oldSt.busBox.isFocused()) {
                busBox.setValue(oldSt.busBox.getValue()); // 保留用户正在输入的内容 (Preserve user's in-progress input)
                busBox.setFocused(true);
            }
            s.busBox = busBox;
            s.fields.add(busBox);
            s.fieldParamIndices.add(-1);
            if (node.signalBands == null) node.signalBands = new java.util.ArrayList<>();
            // 同步旧频段 EditBox 的值到 signalBands（仅同步未聚焦的，防止干扰正在编辑的框） (Sync old band EditBox values to signalBands; only unfocused ones to avoid disrupting active edits)
            if (oldSt != null && oldSt.fields.size() > 1 && node.type == NodeType.BUS_OUT) {
                for (int bi = 1; bi < oldSt.fields.size(); bi++) {
                    int sigIdx = bi - 1;
                    var oldBox = oldSt.fields.get(bi);
                    if (sigIdx < node.signalBands.size() && !oldBox.isFocused()) {
                        String val = oldBox.getValue();
                        if (!val.isEmpty()) node.signalBands.set(sigIdx, val);
                        node.bandsDirty = true;
                    }
                }
            }
            for (int bi = 0; bi < node.signalBands.size(); bi++) {
                final int idx = bi;
                var bandBox = new EditBox(mc.font, 0, 0, 80, 16, Component.literal(""));
                bandBox.setMaxLength(16); bandBox.setValue(node.signalBands.get(bi));
                if (node.type == NodeType.BUS_IN) bandBox.setEditable(false);
                // BUS_OUT 频段可编辑但不在 enterActions 中（由 recompile 批量同步） (BUS_OUT bands editable but not in enterActions; synced in batch by recompile)
                s.fields.add(bandBox);
                s.fieldParamIndices.add(bi);
            }
        }
        if (node.type == NodeType.TEXT) {
            var tb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            tb.setMaxLength(256); tb.setValue(node.displayText);
            final String[] preEditText = {node.displayText}; // initial value for undo
            final String[] lastText = {node.displayText};
            tb.setResponder(text -> {
                if (!text.equals(lastText[0])) {
                    node.displayText = text;
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastText[0] = text;
                }
            });
            ed.enterActions.put(tb, () -> {
                if (!lastText[0].equals(preEditText[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastText[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, 0, preEditText[0]);
                    preEditText[0] = lastText[0];
                }
            });
            s.fields.add(tb);
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFFCCCCCC,
                c -> { int oldC = node.textColor; node.textColor = c; ed.markDirty();
                    var tcOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, null, 0, 0, c, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.host.sendOp(tcOp); ed.recordOp(tcOp, 0, 0, oldC, null); },
                ed.colorPicker
            );
            s.paramKeys = new String[]{"text", "color"};
        }
        if (node.type == NodeType.DATA) {
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFF88FF88,
                c -> { int oldC = node.textColor; node.textColor = c; ed.markDirty();
                    var tcOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, null, 0, 0, c, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.host.sendOp(tcOp); ed.recordOp(tcOp, 0, 0, oldC, null); },
                ed.colorPicker
            );
            s.paramKeys = new String[]{"color"};
        }
        if (node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) {
            float[] defaults = {0.01f, 0.01f, 1f};
            for (int pi = 0; pi < 3; pi++) {
                int idx = pi;
                var b = new EditBox(mc.font, 0, 0, 50, 16, Component.literal(""));
                b.setMaxLength(8); b.setValue(GraphEditor.ff3(node.params.length > idx ? node.params[idx] : defaults[idx]));
                int iidx = idx; ed.registerEnter(b, () -> { try { if (node.params.length > iidx) node.params[iidx] = Float.parseFloat(b.getValue().trim()); } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
                s.fields.add(b);
            }
            // 画布尺寸 W/H 已移入像素编辑器（双击 IMAGE/IMAGE_SEQUENCE 打开，顶部 Canvas W/H 输入框），
            // 此处不再显示尺寸字段。s.paramKeys 与字段数保持一致（3 个：moveX/moveY/rotScl）。
            // Canvas W/H moved into the pixel editor (double-click an IMAGE/IMAGE_SEQUENCE node;
            // Canvas W/H fields sit at the top of that overlay). No size fields here anymore —
            // paramKeys stays aligned with the 3 remaining fields (moveX/moveY/rotScl).
            s.paramKeys = new String[]{"moveX", "moveY", "rotScl"};
        }
        if (node.type == NodeType.COMMENT) {
            int editW = Math.max(40, Math.round(node.commentWidth) - 28);
            var mle = new io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox(
                mc.font, 0, 0, editW, 18);
            mle.setMaxLength(4096);
            mle.setValue(node.displayText);
            mle.setBackgroundColor(0x00000000); // transparent, let comment bg show through
            mle.setTextColor(node.commentTextColor);
            mle.setCursorColor(node.commentTextColor);
            mle.setDrawBorder(false);
            mle.setResponder(t -> { node.displayText = t;
                ed.host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_TEXT,
                    ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                    0, 0, 0, 0, 0, 0f, t, 0, 0, 0, 0, null, 0, 0, 0,
                    net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID())); });
            // 自动聚焦只用于「新建面板」；重建（oldStRef != null）不得抢焦点：
            // 否则输入中的注释框会被 op 回声重建并夺走焦点，与 syncEditStateToSelection 的
            // 「非选中节点不得持焦点」规则相争。
            // Auto-focus only on a fresh panel; a rebuild must not steal focus (see 焦点保留).
            if (oldStRef == null) mle.setFocused(true); // auto-focus so user can type immediately
            s.fields.add(mle);
        }
        if (node.type == NodeType.FORMULA) {
            // Multi-line script editor — single MultiLineEditBox
            int editW = NodeRenderer.WIDE_NW - 36;
            var mle = new io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox(
                mc.font, 0, 0, editW, 18);
            // 16KB 上限:火控等大脚本(~5KB)可整篇粘贴 / 16KB cap: fire-control-scale scripts (~5KB) paste whole
            mle.setMaxLength(io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox.MAX_LENGTH);
            mle.setValue(node.formula);
            node.cachedScript = null;

            // ── Syntax highlighting / 语法高亮 ──
            mle.setHighlighter(io.github.y15173334444.create_schematic_compute.graph.FormulaParser::tokenize);
            mle.setTokenPalette(io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox.DEFAULT_PALETTE);

            // ── Autocomplete / 自动补全 ──
            mle.setCompletionProvider(
                io.github.y15173334444.create_schematic_compute.client.FormulaCompletion::candidates, node);

            // Initial parse and validation from the actual formula text
            if (!node.formula.isEmpty()) {
                var initScript = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(node.formula);
                node.dynamicInputCount = initScript.inputVars.size();
                node.dynamicOutputCount = Math.max(1, initScript.outputLabels.size());
                node.outputLabels = initScript.outputLabels;
                node.formulaIssues = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(node.formula);
                mle.setHasError(hasErrors(node.formulaIssues));
            }

            final int formulaNodeId = node.id; // capture id, re-fetch node each call
            mle.setResponder(t -> {
                // 全角/中文符号兜底转换(insertText 已实时转换,此处覆盖 setValue/撤销等路径)
                // Full-width fallback conversion (insertText already converts live; this covers setValue/undo paths)
                String sanitized = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.sanitizeFullwidth(t);
                // Re-fetch from current graph — the graph reference may have been
                // replaced by an NBT sync between keystrokes.
                // 每次按键重新获取图引用——NBT 同步可能在两次按键之间替换了图对象。
                var cur = ed.host.getGraph().findNode(formulaNodeId);
                if (cur == null || cur.type != NodeType.FORMULA) return;
                cur.formula = sanitized;
                var res = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(sanitized);
                cur.cachedScript = res; // cache for ensureScriptParsed() — avoids double-parse
                int newIn = res.inputVars.size();
                int newOut = Math.max(1, res.outputLabels.size());
                cur.dynamicInputCount = newIn;
                cur.dynamicOutputCount = newOut;
                cur.outputLabels = res.outputLabels;

                // Clean up connections to now-removed pins using stable pinId (v1.2.4).
                // Uses pinIndex resolution, not list.contains, to correctly handle
                // default output labels ("out0" vs "") and cachedScript-based resolution.
                // 使用 pinIndex 解析判断（而非 list.contains），正确处理默认输出标签
                // （"out0" vs ""）以及基于 cachedScript 的解析。
                ed.host.getGraph().connections.removeIf(c -> {
                    if (c.toId == cur.id) {
                        if (c.toPinId != null) return cur.inputPinIndex(c.toPinId) < 0;
                        else return c.toPin >= cur.inputs(); // legacy fallback
                    }
                    if (c.fromId == cur.id) {
                        if (c.fromPinId != null) return cur.outputPinIndex(c.fromPinId) < 0;
                        else return c.fromPin >= cur.outputs(); // legacy fallback
                    }
                    return false;
                });
                ed.host.getGraph().rebuildInputCache();

                // ── Real-time validation (client-side only) ──
                cur.formulaIssues = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(sanitized);
                mle.setHasError(hasErrors(cur.formulaIssues));

                ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                    ed.host.getBlockPos(), ed.ownerNodeId(), formulaNodeId, sanitized, ed.host.getPlayerUUID()));
            });
            s.fields.add(mle);
        }
        if (node.type == NodeType.DEBUG_SIGNAL_GEN) {
            createDebugSignalGenEditState(ed, node, s, mc);
        }
        if (node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT) {
            var nb = new EditBox(mc.font, 0, 0, 100, 16, Component.literal(""));
            nb.setMaxLength(32); nb.setValue(node.displayText);
            final String[] preEditName = {node.displayText}; // initial value for undo
            final String[] lastName = {node.displayText};
            nb.setResponder(text -> {
                if (!text.equals(lastName[0])) {
                    node.displayText = text;
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastName[0] = text;
                }
            });
            ed.enterActions.put(nb, () -> {
                if (!lastName[0].equals(preEditName[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastName[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, 0, preEditName[0]);
                    preEditName[0] = lastName[0];
                }
            });
            s.fields.add(nb);
            s.paramKeys = new String[]{"name"};
        }
        // ── 焦点保留（2/2）：按下标还原聚焦与光标。光标只对多行编辑器还原，且仅当文本仍够长
        // —— 单行 EditBox 的光标是内部状态，图刷新后文本可能整体变值，越界光标会让插入抛异常。
        // Restore focus (2/2). Caret is restored only on multi-line editors and only when the
        // text is still long enough (an out-of-range caret makes TextFieldHelper.insert throw).
        if (prevFocusIdx >= 0 && prevFocusIdx < s.fields.size()) {
            var fb = s.fields.get(prevFocusIdx);
            fb.setFocused(true);
            if (fb instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox
                && prevFocusCursor >= 0 && prevFocusCursor <= fb.getValue().length()) {
                fb.setCursorPosition(prevFocusCursor);
            }
        }
        return s;
    }

    /** Helper: true if the issue list contains at least one ERROR-level item.
     *  辅助方法：如果问题列表中至少有一个 ERROR 级别的问题则返回 true。 */
    private static boolean hasErrors(java.util.List<io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaIssue> issues) {
        if (issues == null) return false;
        for (var iss : issues)
            if (iss.severity() == io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Severity.ERROR)
                return true;
        return false;
    }

    /** 为 DEBUG_SIGNAL_GEN 创建条件编辑状态（公式/参数按 setMode/outMode 动态可见）。
     *  Create conditional edit state for DEBUG_SIGNAL_GEN (formula/params visible per setMode/outMode). */
    static void createDebugSignalGenEditState(GraphEditor ed, GraphNode node, GraphEditor.EditState s, Minecraft mc) {
        int setMode = node.params.length > 0 ? (int) node.params[0] : 0;
        int outMode = node.params.length > 1 ? (int) node.params[1] : 0;
        int editW = NodeRenderer.WIDE_NW - 36;

        // 公式 EditBox（仅 SET_FORMULA 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_FORMULA) {
            var fe = new EditBox(mc.font, 0, 0, editW, 16, Component.literal(""));
            fe.setMaxLength(256);
            fe.setValue(node.formula);
            fe.setHint(Component.literal("f(x)=... (sin/cos 为度, x∈[0,1])"));
            fe.setResponder(t -> {
                // 全角/中文符号实时转半角(与 FORMULA 编辑器同款);转换时写回输入框显示,响应器以干净文本重入
                // Convert full-width/CJK symbols live (same as the FORMULA editor); write back to the box
                // on conversion — the responder re-enters with clean text
                String sanitized = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.sanitizeFullwidth(t);
                if (!sanitized.equals(t)) { fe.setValue(sanitized); return; }
                node.formula = sanitized;
                node.debugFormulaRpn = null;
                ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                    ed.host.getBlockPos(), ed.ownerNodeId(), node.id, sanitized, ed.host.getPlayerUUID()));
            });
            s.fields.add(fe);
        }

        // speed EditBox（仅 SET_MANUAL + OUT_FREQ 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL
            && outMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_FREQ) {
            int idx = 2;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(GraphEditor.ff3(node.params.length > idx ? node.params[idx] : (1f / 20f)));
            final float[] preEditSpd = {node.params.length > idx ? node.params[idx] : (1f / 20f)};
            final float[] lastSentSpd = {preEditSpd[0]};
            b.setResponder(text -> { try {
                if (ed.suppressEditBoxResponder) return;
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentSpd[0]) > 0.0001f) {
                    if (node.params.length > idx) node.params[idx] = newV;
                    ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, newV, ed.host.getPlayerUUID()));
                    lastSentSpd[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            ed.enterActions.put(b, () -> {
                if (Math.abs(lastSentSpd[0] - preEditSpd[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, lastSentSpd[0], ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, preEditSpd[0], null);
                    preEditSpd[0] = lastSentSpd[0];
                }
            });
            s.fields.add(b);
        }

        // amplitude EditBox（仅 SET_MANUAL 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL) {
            int idx = 3;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(GraphEditor.ff3(node.params.length > idx ? node.params[idx] : 1f));
            final float[] preEditAmp = {node.params.length > idx ? node.params[idx] : 1f};
            final float[] lastSentAmp = {preEditAmp[0]};
            b.setResponder(text -> { try {
                if (ed.suppressEditBoxResponder) return;
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentAmp[0]) > 0.0001f) {
                    if (node.params.length > idx) node.params[idx] = newV;
                    ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, newV, ed.host.getPlayerUUID()));
                    lastSentAmp[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            ed.enterActions.put(b, () -> {
                if (Math.abs(lastSentAmp[0] - preEditAmp[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(ed.host.getBlockPos(), ed.ownerNodeId(), node.id, idx, lastSentAmp[0], ed.host.getPlayerUUID());
                    ed.recordOp(op, 0, 0, preEditAmp[0], null);
                    preEditAmp[0] = lastSentAmp[0];
                }
            });
            s.fields.add(b);
        }

        // inputX 不显示 EditBox — 用户直接拖拽 XY 图上的天蓝色扫描线设置 x 值
        // inputX has no EditBox — set x by dragging the sky-blue marker on the chart
    }

    /** 处理 DEBUG_SIGNAL_GEN 模式切换按钮点击（含二次确认状态机）。
     *  Handle DEBUG_SIGNAL_GEN mode toggle button click (with confirm-on-second-click state machine).
     *  @param hit format: "setMode:0", "setMode:1", "outMode:0", "outMode:1" */
    static void handleModeToggleClick(GraphEditor ed, GraphNode node, GraphEditor.EditState st, String hit) {
        long now = System.currentTimeMillis();
        String[] parts = hit.split(":");
        boolean isSetMode = parts[0].equals("setMode");
        int targetVal = Integer.parseInt(parts[1]);
        int paramIdx = isSetMode ? 0 : 1;
        int currentVal = node.params.length > paramIdx ? (int) node.params[paramIdx] : 0;

        // 点击当前已激活的模式 → 忽略
        if (targetVal == currentVal) {
            // clear any pending state
            if (isSetMode) { st.pendingSetMode = -1; st.pendingSetModeExpireMs = 0; }
            else { st.pendingOutMode = -1; st.pendingOutModeExpireMs = 0; }
            return;
        }

        // 检查是否匹配待确认的目标
        int pendingTarget = isSetMode ? st.pendingSetMode : st.pendingOutMode;
        long pendingExpire = isSetMode ? st.pendingSetModeExpireMs : st.pendingOutModeExpireMs;
        boolean hasPending = pendingTarget >= 0 && now < pendingExpire;

        if (hasPending && pendingTarget == targetVal) {
            // 二次点击确认 → 执行切换并清空原模式数据
            // Second click confirmed → execute switch and clear old mode data
            ed.beginUndoBatch();
            float oldMode = node.params.length > paramIdx ? node.params[paramIdx] : 0;
            float oldSpeed = node.params.length > 2 ? node.params[2] : 1f / 20f;
            float oldAmp = node.params.length > 3 ? node.params[3] : 1f;
            String oldFormula = node.formula != null ? node.formula : "";
            float[] oldCtrlX = node.debugCtrlX;
            float[] oldCtrlY = node.debugCtrlY;
            node.params[paramIdx] = targetVal;
            if (isSetMode) {
                // 切换设置模式 → 清空原模式数据 + 重置参数为默认，同步
                // Switching set mode → clear old data + reset params to default, sync
                if (targetVal == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL) {
                    // 切换到手动曲线 → 清空公式，重置 speed/amp 为默认
                    node.formula = "";
                    node.debugFormulaRpn = null;
                    var opF = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, "", ed.host.getPlayerUUID());
                    ed.host.sendOp(opF); ed.recordOp(opF, 0, 0, 0, oldFormula);
                    node.params[2] = 1f / 20f; // speed 默认
                    node.params[3] = 1f;       // amplitude 默认
                    var opSp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 2, 1f / 20f, ed.host.getPlayerUUID());
                    ed.host.sendOp(opSp); ed.recordOp(opSp, 0, 0, oldSpeed, null);
                    var opAmp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 3, 1f, ed.host.getPlayerUUID());
                    ed.host.sendOp(opAmp); ed.recordOp(opAmp, 0, 0, oldAmp, null);
                } else {
                    // 切换到 f(x) → 重置控制点为默认，speed/amp 恢复默认
                    node.debugCtrlX = new float[]{0f, 1f};
                    node.debugCtrlY = new float[]{0f, 0f};
                    String oldCtrlStr = oldCtrlX != null ? GraphEditor.encodeCtrlPoints(oldCtrlX, oldCtrlY) : "";
                    var opCp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, node.debugCtrlX, node.debugCtrlY, ed.host.getPlayerUUID());
                    ed.host.sendOp(opCp); ed.recordOp(opCp, 0, 0, 0, oldCtrlStr);
                    node.params[2] = 1f / 20f;
                    node.params[3] = 1f;
                    var opSp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 2, 1f / 20f, ed.host.getPlayerUUID());
                    ed.host.sendOp(opSp); ed.recordOp(opSp, 0, 0, oldSpeed, null);
                    var opAmp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 3, 1f, ed.host.getPlayerUUID());
                    ed.host.sendOp(opAmp); ed.recordOp(opAmp, 0, 0, oldAmp, null);
                }
            }
            // 发送 SET_PARAM op
            var modeOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                ed.host.getBlockPos(), ed.ownerNodeId(), node.id, paramIdx, (float) targetVal, ed.host.getPlayerUUID());
            ed.host.sendOp(modeOp); ed.recordOp(modeOp, 0, 0, oldMode, null);
            ed.endUndoBatch();
            // 清除待确认状态
            if (isSetMode) { st.pendingSetMode = -1; st.pendingSetModeExpireMs = 0; }
            else { st.pendingOutMode = -1; st.pendingOutModeExpireMs = 0; }
            // 重建编辑状态以更新可见的 EditBox
            ed.nodeEditStatesById.put(node.id, create(ed, node));
            ed.markDirty();
        } else {
            // 首次点击 → 设置待确认状态（3 秒超时）
            // First click → set pending confirmation (3 second timeout)
            if (isSetMode) { st.pendingSetMode = targetVal; st.pendingSetModeExpireMs = now + 3000; }
            else { st.pendingOutMode = targetVal; st.pendingOutModeExpireMs = now + 3000; }
        }
    }
}
