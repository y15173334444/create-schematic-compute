package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Token;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.TokType;

import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.CONSTANT_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.FUNCTION_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.KEYWORDS;

/**
 * 公式词法相位（拓扑审查 §三 P5 按相拆分，自 FormulaParser 逐字迁出）：全角归一 sanitizeFullwidth、
 * 词法扫描 tokenize（语法高亮/自动补全/校验/RPN 编译共用同一次扫描）、变量名抽取 extractVariables
 * 与标识符合法性 isValidIdentifier。词法只认识名字（函数/常量/关键字），表本体在 FormulaFunctions
 * 与 FormulaParser.KEYWORDS。静态函数包，不建继承体系；公开入口经 FormulaParser 冻结门面。
 * <p>Formula lexer phase (topology-review §三 P5 split, relocated verbatim from
 * FormulaParser): the sanitizeFullwidth full-width normalisation, the tokenize scan
 * (one scan shared by highlighting / completion / validation / RPN compilation),
 * extractVariables and isValidIdentifier. The lexer only knows names (functions /
 * constants / keywords); the tables themselves live in FormulaFunctions and
 * FormulaParser.KEYWORDS. Static function pack, no inheritance hierarchy; public
 * entries go through FormulaParser's frozen facade.</p>
 */
final class FormulaLexer {

    private FormulaLexer() {}

    /** Pattern for valid identifiers: a-z, A-Z, 0-9, underscore, starting with letter or underscore */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /** 将常见中文/全角符号实时转换为半角 ASCII 等价物(公式编辑器输入即调用,
     *  FORMULA 脚本框与信号发生器公式框共用)。未命中的字符原样保留。
     *  Converts common CJK/full-width symbols to half-width ASCII equivalents on the fly
     *  (applied live on formula input; shared by the FORMULA script box and the signal
     *  generator's formula box). Unmatched characters pass through unchanged. */
    static String sanitizeFullwidth(String s) {
        if (s == null || s.isEmpty()) return s;
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '　') c = ' ';                                                  // 全角空格 / ideographic space
            if (c >= '０' && c <= '９') c = (char) (c - '０' + '0');                     // 全角数字 / full-width digits
            if (c >= 'Ａ' && c <= 'Ｚ') c = (char) (c - 'Ａ' + 'A');                     // 全角大写 / full-width uppercase
            if (c >= 'ａ' && c <= 'ｚ') c = (char) (c - 'ａ' + 'a');                     // 全角小写 / full-width lowercase
            sb.append(switch (c) {
                case '（' -> '(';  case '）' -> ')';
                case '，' -> ',';  case '。', '．' -> '.';
                case '；' -> ';';  case '：' -> ':';
                case '＋' -> '+';  case '－' -> '-';
                case '×' -> '*';  case '÷' -> '/';
                case '＝' -> '=';  case '！' -> '!';
                case '＜' -> '<';  case '＞' -> '>';
                case '＆' -> '&';  case '｜' -> '|';
                case '｛' -> '{';  case '｝' -> '}';
                case '％' -> '%';  case '＾' -> '^';
                default -> c;
            });
        }
        // 组合符号(一对多映射,字符串级替换)/ composite symbols (1→2 mappings, string-level)
        return sb.toString()
            .replace("≥", ">=").replace("≤", "<=").replace("≠", "!=");
    }

    /** 解析 formula 返回所有变量名（按出现顺序，跳过函数名）。
     *  变量名支持字母、数字、下划线，首字符必须是字母或下划线。
     *  命名常量 (PI)/(E) 仅在其紧邻前驱为分组 '(' 时跳过（视为字面量），
     *  函数调用内的裸 PI/E（如 sin(PI)）仍作为变量。
     *  Uses {@link #tokenize(String)} so that CONSTANT vs IDENT distinction
     *  is consistent with {@link FormulaParser#compile(String)}. */
    static List<String> extractVariables(String formula) {
        var vars = new LinkedHashSet<String>();
        if (formula == null || formula.isEmpty()) return new ArrayList<>(vars);
        // Normalize full-width parens to match tokenize() behaviour
        String s = formula.replace('（', '(').replace('）', ')');
        var tokens = tokenize(s);
        for (Token t : tokens) {
            if (t.type() == TokType.IDENT && !FUNCTION_NAMES.contains(t.text())) {
                vars.add(t.text());
            }
            // CONSTANT tokens represent literal PI/E inside grouping (…) and are
            // deliberately skipped — they are not variables.  PI/E tokens inside
            // function calls (e.g. sin(PI)) are marked IDENT by tokenize() because
            // the function's LPAREN is consumed by the FUNCTION token and never
            // emitted as a standalone LPAREN.
        }
        return new ArrayList<>(vars);
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }
    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /**
     * Lexically tokenize a formula string for syntax highlighting, autocomplete, and validation.
     * Returns a flat list of tokens with [start, end) character offsets.
     * Whitespace is skipped (no tokens emitted). Unknown single characters that aren't
     * part of any valid token are emitted as {@code UNKNOWN}.
     *
     * 对公式字符串进行词法分析，用于语法高亮、自动补全和校验。
     * 返回带 [start, end) 字符偏移的 token 列表。空白字符被跳过（不生成 token）。
     * 不属于任何有效 token 的未知字符被标记为 {@code UNKNOWN}。
     */
    static List<Token> tokenize(String src) {
        var tokens = new ArrayList<Token>();
        if (src == null || src.isEmpty()) return tokens;
        // Normalize full-width parens
        String s = src.replace('（', '(').replace('）', ')');
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            // Whitespace — skip
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue; }
            // Line continuation: backslash (treated as operator for coloring)
            if (c == '\\') { tokens.add(new Token(i, i + 1, TokType.OPERATOR, "\\")); i++; continue; }
            // Comment: -- to end of line
            if (c == '-' && i + 1 < len && s.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < len && s.charAt(j) != '\n') j++;
                tokens.add(new Token(i, j, TokType.COMMENT, s.substring(i, j)));
                i = j; continue;
            }
            // @output marker
            if (c == '@' && i + 6 <= len && s.startsWith("output", i + 1)) {
                int j = i + 7; // "@output".length()
                // only match if followed by whitespace, end, or non-ident char
                if (j >= len || !isIdentPart(s.charAt(j))) {
                    tokens.add(new Token(i, i + 7, TokType.AT_OUTPUT, "@output"));
                    i = i + 7; continue;
                }
            }
            // Identifiers
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < len && isIdentPart(s.charAt(j))) j++;
                String name = s.substring(i, j);
                // Control-flow keyword — before function/variable checks
                if (KEYWORDS.contains(name)) {
                    tokens.add(new Token(i, j, TokType.KEYWORD, name));
                    i = j; continue;
                }
                // Peek ahead: is it a function call?
                int k = j;
                while (k < len && s.charAt(k) == ' ') k++;
                if (FUNCTION_NAMES.contains(name) && k < len && s.charAt(k) == '(') {
                    tokens.add(new Token(i, j, TokType.FUNCTION, name));
                    i = k + 1; // skip '(' — don't emit as standalone LPAREN,
                               // so that PI in sin(PI) is IDENT (variable),
                               // while PI in (PI) is CONSTANT (literal)
                    continue;
                }
                // Named constant: (PI) / (E) — only when immediately preceded by LPAREN token
                if (CONSTANT_NAMES.contains(name) && !tokens.isEmpty()
                    && tokens.get(tokens.size() - 1).type() == TokType.LPAREN) {
                    tokens.add(new Token(i, j, TokType.CONSTANT, name));
                } else {
                    tokens.add(new Token(i, j, TokType.IDENT, name));
                }
                i = j; continue;
            }
            // Numbers
            if (c >= '0' && c <= '9') {
                int j = i + 1;
                while (j < len && ((s.charAt(j) >= '0' && s.charAt(j) <= '9') || s.charAt(j) == '.')) j++;
                String numStr = s.substring(i, j);
                try {
                    Double.parseDouble(numStr);
                    tokens.add(new Token(i, j, TokType.NUMBER, numStr));
                } catch (NumberFormatException e) {
                    tokens.add(new Token(i, j, TokType.UNKNOWN, numStr));
                }
                i = j; continue;
            }
            // Parens
            if (c == '(') { tokens.add(new Token(i, i + 1, TokType.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(i, i + 1, TokType.RPAREN, ")")); i++; continue; }
            // Two-char operators: == != <= >=
            if ((c == '=' || c == '!' || c == '<' || c == '>') && i + 1 < len && s.charAt(i + 1) == '=') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, s.substring(i, i + 2)));
                i += 2; continue;
            }
            // Two-char logical operators: && ||
            if (c == '&' && i + 1 < len && s.charAt(i + 1) == '&') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, "&&"));
                i += 2; continue;
            }
            if (c == '|' && i + 1 < len && s.charAt(i + 1) == '|') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, "||"));
                i += 2; continue;
            }
            // Assignment
            if (c == '=') { tokens.add(new Token(i, i + 1, TokType.ASSIGN, "=")); i++; continue; }
            // Comparison / logical singles: < > !
            if (c == '<' || c == '>' || c == '!') {
                tokens.add(new Token(i, i + 1, TokType.OPERATOR, String.valueOf(c)));
                i++; continue;
            }
            // Braces (control-flow blocks)
            if (c == '{') { tokens.add(new Token(i, i + 1, TokType.LBRACE, "{")); i++; continue; }
            if (c == '}') { tokens.add(new Token(i, i + 1, TokType.RBRACE, "}")); i++; continue; }
            // Swizzle: '.' followed by identifier → member access (v.x / v.xy)
            if (c == '.' && i + 1 < len && isIdentStart(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < len && isIdentPart(s.charAt(j))) j++;
                tokens.add(new Token(i, j, TokType.SWIZZLE, s.substring(i + 1, j)));
                i = j; continue;
            }
            // Semicolon (statement separator, knife 3)
            if (c == ';') { tokens.add(new Token(i, i + 1, TokType.SEMICOLON, ";")); i++; continue; }
            // Comma (function argument separator)
            if (c == ',') { tokens.add(new Token(i, i + 1, TokType.OPERATOR, ",")); i++; continue; }
            // Arithmetic operators
            if ("+-*/%^".indexOf(c) >= 0) {
                tokens.add(new Token(i, i + 1, TokType.OPERATOR, String.valueOf(c)));
                i++; continue;
            }
            // Unknown character — group consecutive unknown chars
            int j = i + 1;
            while (j < len) {
                char nc = s.charAt(j);
                if (nc == ' ' || nc == '\t' || nc == '\r' || nc == '\n') break;
                if (isIdentStart(nc) || (nc >= '0' && nc <= '9')) break;
                if ("()=,+\\-*/%^{}!.&|<>".indexOf(nc) >= 0) break;
                if (nc == '@' || nc == '#' || nc == '?' || nc == ';' || nc == ':' || nc == '\'' || nc == '"') break;
                j++;
            }
            tokens.add(new Token(i, j, TokType.UNKNOWN, s.substring(i, j)));
            i = j;
        }
        return tokens;
    }

    /** 检查字符串是否为合法标识符 */
    static boolean isValidIdentifier(String s) {
        return s != null && !s.isEmpty() && IDENTIFIER.matcher(s).matches() && !FUNCTION_NAMES.contains(s);
    }
}
