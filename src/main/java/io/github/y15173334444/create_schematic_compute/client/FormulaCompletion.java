package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds the autocomplete candidate list for the FORMULA editor.
 * 为 FORMULA 编辑器构建自动补全候选列表。
 */
public class FormulaCompletion {

    /** Collect all completion candidates for the given formula text and node context.
     *  收集给定公式文本和节点上下文的所有补全候选项。 */
    public static List<FormulaSuggestPopup.Candidate> candidates(String text, GraphNode node) {
        var result = new ArrayList<FormulaSuggestPopup.Candidate>();

        // 1) Functions — always available / 函数 — 始终可用
        for (var e : FormulaParser.FUNCTIONS.entrySet()) {
            String name = e.getKey();
            int arity = e.getValue();
            String sig = arity == 2 ? name + "(y, x)" : arity == 3 ? name + "(x, y, z)" : name + "(x)";
            result.add(new FormulaSuggestPopup.Candidate(name, sig, name + "("));
        }

        // 2) Named constants — shown as (PI) / (E) with hint / 命名常量 — 显示为 (PI)/(E) 带提示
        for (String cn : FormulaParser.constantNames()) {
            result.add(new FormulaSuggestPopup.Candidate("(" + cn + ")",
                cn.equals("PI") ? "= π" : "= e", "(" + cn + ")"));
        }

        // 2.5) Control-flow keywords / 控制流关键字
        result.add(new FormulaSuggestPopup.Candidate("repeat", "N { … }", "repeat  {"));
        result.add(new FormulaSuggestPopup.Candidate("while", "(cond) { … }", "while () {"));
        result.add(new FormulaSuggestPopup.Candidate("if", "(cond) { … }", "if () {"));
        result.add(new FormulaSuggestPopup.Candidate("else", "{ … }", "else {"));
        result.add(new FormulaSuggestPopup.Candidate("break", "退出循环", "break"));
        result.add(new FormulaSuggestPopup.Candidate("continue", "跳到下一轮", "continue"));

        // 3) Known identifiers from the current text (via tokenize).
        //    Only suggest variables that actually appear right now.
        //    Use cachedScript.inputVars to mark external-input variables with a hint.
        //    当前文本中的标识符（通过 tokenize）。只建议当前实际存在的变量。
        //    用 cachedScript.inputVars 标记外部输入变量并附加 "input" 提示。
        var inputVarSet = new LinkedHashSet<String>();
        if (node != null && node.cachedScript != null) {
            inputVarSet.addAll(node.cachedScript.inputVars);
        }
        if (text != null && !text.isEmpty()) {
            var tokens = FormulaParser.tokenize(text);
            var seen = new LinkedHashSet<String>();
            for (var t : tokens) {
                if (t.type() == FormulaParser.TokType.IDENT) {
                    String n = t.text();
                    if (seen.add(n) && FormulaParser.isValidIdentifier(n)) {
                        // Mark as "input" only if the parsed script recognizes it as an external variable /
                        // 仅在解析脚本将其识别为外部变量时标记 "input"
                        String hint = inputVarSet.contains(n) ? "input" : null;
                        result.add(new FormulaSuggestPopup.Candidate(n, hint, n));
                    }
                }
            }
        }

        return result;
    }
}
