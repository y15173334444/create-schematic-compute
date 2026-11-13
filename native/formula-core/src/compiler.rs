//! mini 编译器（bench 脚手架）：源码 → Tok 字节码 + 语句树。
//! **正式桥接形态不经过这里**——Java 的 FormulaParser/FormulaAstParser 是唯一前端，
//! Java 侧负责把自家 RPN 编译成 Tok 下发。本模块只保证基准脚本能与 Java 同源对拍。
//!
//! 逐字镜像 compileTokens 的 lenient 规则（探针实证）：
//! - 已知函数 `sin(1)` → RPN `[1, FT(sin)]`（FUNCTION token 连带吞掉 LPAREN）；
//! - 未知函数 `min(1,2)` → RPN `[min, 1, '(', 2]`——逗号把 '(' 弹进输出，'(' 求值时
//!   吃两个操作数得 0，结果=栈顶=最后参数（实测 y=2.0）；
//! - 一元负号 → `[0, x, -]`（Java 同款 0- 技巧，一元 + 同口径）；
//! - PI/E：ops 栈顶是分组 '(' 才算常量字面量，否则变量引用（sin(PI) → 变量）。
//!
//! Mini compiler (bench scaffolding). Mirrors compileTokens' lenient rules verbatim
//! (probe-verified); the Java parser remains the single frontend in the real bridge.

use crate::ast::{Program, Stmt};
use crate::tok::{Cmp, Fn, Tok};

/// 语句嵌套上限（镜像 MAX_PARSE_DEPTH）。
const MAX_PARSE_DEPTH: usize = 128;

/// 预处理：\r 归一、续行合并、空行/整行注释过滤（镜像 parseScript 预处理）。
fn preprocess(src: &str) -> Vec<String> {
    let normalized = src.replace("\r\n", "\n").replace('\r', "");
    let mut joined: Vec<String> = Vec::new();
    let mut cont = String::new();
    for raw in normalized.split('\n') {
        let t = raw.trim();
        if t.ends_with('\\') {
            cont.push_str(t[..t.len() - 1].trim());
            cont.push(' ');
        } else if !cont.is_empty() {
            cont.push_str(t);
            joined.push(std::mem::take(&mut cont));
        } else {
            joined.push(t.to_string());
        }
    }
    if !cont.is_empty() {
        joined.push(cont.trim().to_string());
    }
    joined
        .into_iter()
        .filter(|l| !l.is_empty() && !l.starts_with("--"))
        .collect()
}

pub fn compile(src: &str) -> Result<Program, String> {
    let lines = preprocess(src);
    let mut c = Compiler { names: Vec::new(), repeat_counts: Vec::new(), next_loop_id: 0 };
    let body = c.parse_body(&lines, &mut 0, 0)?;
    Ok(Program { body: body.into_boxed_slice(), names: c.names, repeat_counts: c.repeat_counts })
}

struct Compiler {
    names: Vec<String>,
    repeat_counts: Vec<(u16, u64)>,
    next_loop_id: u16,
}

impl Compiler {
    fn slot(&mut self, name: &str) -> u16 {
        if let Some(i) = self.names.iter().position(|n| n == name) {
            i as u16
        } else {
            self.names.push(name.to_string());
            (self.names.len() - 1) as u16
        }
    }

    /// 解析语句序列，直到行耗尽或遇到 `}`（`}` 本身不消费，由块调用方收）。
    /// 注：块体只支持逐行形态（`if (c) {` 换行）；行内 `{ ... }` 属 Java 前端能力，
    /// 脚手架不覆盖。
    fn parse_body(&mut self, lines: &[String], pos: &mut usize, depth: usize) -> Result<Vec<Stmt>, String> {
        if depth > MAX_PARSE_DEPTH {
            return Err(format!("script nesting exceeds {MAX_PARSE_DEPTH} levels"));
        }
        let mut out = Vec::new();
        while *pos < lines.len() {
            let line = lines[*pos].trim();
            if line == "}" {
                break;
            }
            out.push(self.parse_stmt(lines, pos, depth)?);
        }
        Ok(out)
    }

    fn parse_stmt(&mut self, lines: &[String], pos: &mut usize, depth: usize) -> Result<Stmt, String> {
        let line = lines[*pos].trim().to_string();
        *pos += 1;
        if line == "break" {
            return Ok(Stmt::Break);
        }
        if line == "continue" {
            return Ok(Stmt::Continue);
        }
        if let Some(rest) = strip_keyword(&line, "repeat") {
            let (count, rest) = self.take_repeat_count(rest)?;
            let (body, _rest) = self.take_block(lines, pos, &rest, depth)?;
            let id = self.next_loop_id;
            self.next_loop_id += 1;
            self.repeat_counts.push((id, count));
            return Ok(Stmt::Repeat { id, count, body: body.into_boxed_slice() });
        }
        if let Some(rest) = strip_keyword(&line, "while") {
            let (cond, rest) = self.compile_cond(&rest)?;
            let (body, _rest) = self.take_block(lines, pos, &rest, depth)?;
            let id = self.next_loop_id;
            self.next_loop_id += 1;
            return Ok(Stmt::While {
                id,
                cond: cond.into_boxed_slice(),
                body: body.into_boxed_slice(),
            });
        }
        if let Some(rest) = strip_keyword(&line, "if") {
            let (cond, rest) = self.compile_cond(&rest)?;
            let (body, _rest) = self.take_block(lines, pos, &rest, depth)?;
            // else { } / else if：独占一行时解析；行内 else 属 Java 前端能力。
            let mut els: Vec<Stmt> = Vec::new();
            if *pos < lines.len() {
                let next = lines[*pos].trim();
                if next == "else" || next.starts_with("else ") {
                    *pos += 1;
                    let rest = next["else".len()..].trim_start();
                    if let Some(rest2) = strip_keyword(rest, "if") {
                        // else if → 递归为 else 体里的单条 If
                        let inner = self.parse_if(rest2, lines, pos, depth + 1)?;
                        els.push(inner);
                    } else {
                        let (els_body, _r) = self.take_block(lines, pos, rest, depth + 1)?;
                        els = els_body;
                    }
                }
            }
            return Ok(Stmt::If {
                cond: cond.into_boxed_slice(),
                body: body.into_boxed_slice(),
                els: els.into_boxed_slice(),
            });
        }
        if line == "else" || line.starts_with("else ") {
            return Err("else without if".into());
        }
        // 赋值 or 表达式语句
        if let Some((name, expr)) = split_assign(&line) {
            let slot = self.slot(name);
            let rpn = self.compile_expr(expr)?;
            Ok(Stmt::Assign { slot, rpn: rpn.into_boxed_slice() })
        } else {
            let rpn = self.compile_expr(&line)?;
            Ok(Stmt::Expr(rpn.into_boxed_slice()))
        }
    }

    /// `if (cond) { body }`（else 已由调用方处理）。
    fn parse_if(&mut self, rest: &str, lines: &[String], pos: &mut usize, depth: usize) -> Result<Stmt, String> {
        let (cond, rest) = self.compile_cond(rest)?;
        let (body, _r) = self.take_block(lines, pos, &rest, depth)?;
        Ok(Stmt::If {
            cond: cond.into_boxed_slice(),
            body: body.into_boxed_slice(),
            els: Box::new([]),
        })
    }

    fn take_repeat_count(&self, rest: &str) -> Result<(u64, String), String> {
        let rest = rest.trim_start();
        let num: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
        if num.is_empty() {
            return Err(format!("repeat count must be a literal, got: {rest}"));
        }
        Ok((num.parse().map_err(|e| format!("bad repeat count: {e}"))?, rest[num.len()..].to_string()))
    }

    /// `{ body }`：块体 = 后续行，直到单独的 `}`。
    fn take_block(
        &mut self,
        lines: &[String],
        pos: &mut usize,
        rest: &str,
        depth: usize,
    ) -> Result<(Vec<Stmt>, String), String> {
        let rest = rest.trim_start();
        if !rest.starts_with('{') {
            return Err(format!("expected {{ after header, got: {rest}"));
        }
        let after = rest[1..].trim().to_string();
        if after.starts_with('}') {
            return Ok((Vec::new(), after[1..].to_string()));
        }
        let body = self.parse_body(lines, pos, depth + 1)?;
        if *pos < lines.len() && lines[*pos].trim() == "}" {
            *pos += 1;
        } else {
            return Err("missing closing }".into());
        }
        Ok((body, String::new()))
    }

    /// `while`/`if` 头部的 `(cond)`。返回条件 RPN 与括号后的剩余文本。
    fn compile_cond(&mut self, rest: &str) -> Result<(Vec<Tok>, String), String> {
        let rest = rest.trim_start();
        if !rest.starts_with('(') {
            return Err(format!("expected ( after keyword, got: {rest}"));
        }
        let mut depth_p = 0usize;
        let mut end = rest.len();
        for (i, ch) in rest.char_indices() {
            match ch {
                '(' => depth_p += 1,
                ')' => {
                    depth_p -= 1;
                    if depth_p == 0 {
                        end = i + 1;
                        break;
                    }
                }
                _ => {}
            }
        }
        if depth_p != 0 {
            return Err("unbalanced ( in condition".into());
        }
        let inner = &rest[1..end - 1];
        let rpn = self.compile_expr(inner)?;
        Ok((rpn, rest[end..].to_string()))
    }

    // ───────────── 表达式编译（镜像 compileTokens 的 shunting-yard 口径） ─────────────

    fn compile_expr(&mut self, expr: &str) -> Result<Vec<Tok>, String> {
        let toks = lex(expr)?;
        let mut out: Vec<Tok> = Vec::new();
        let mut ops: Vec<Op> = Vec::new();
        let mut expect_unary = true;
        let mut it = toks.into_iter().peekable();
        while let Some(t) = it.next() {
            match t {
                LT::Num(d) => {
                    out.push(Tok::Const(d));
                    expect_unary = false;
                }
                LT::Ident(name) => {
                    // PI/E：ops 栈顶是分组 '(' 才算常量字面量（镜像 CONSTANT case 的
                    // ops.peek()=='(' 复核；sin(PI) 的 PI 是变量）。
                    if (name == "PI" || name == "E") && matches!(ops.last(), Some(Op::Paren)) {
                        out.push(Tok::Const(if name == "PI" { std::f64::consts::PI } else { std::f64::consts::E }));
                        expect_unary = false;
                        continue;
                    }
                    let known = Fn::from_name(&name);
                    let is_call = matches!(it.peek(), Some(LT::LParen));
                    match known {
                        Some(f) if is_call => {
                            // 已知函数：FT 压 ops，连带吞掉 LPAREN（Java FUNCTION case）
                            ops.push(Op::Fn(f));
                            it.next(); // consume (
                            expect_unary = true;
                        }
                        _ => {
                            // 未知函数名也走 IDENT（Java lexer 只认表内名为 FUNCTION）：
                            // 压变量名进输出，'(' 留给 LPAREN case 按分组压栈——逗号会把
                            // 它弹进输出（'(' 求值 = 吃两项得 0），结果=栈顶=最后参数。
                            out.push(Tok::Var(self.slot(&name)));
                            expect_unary = false;
                        }
                    }
                }
                LT::LParen => {
                    ops.push(Op::Paren);
                    expect_unary = true;
                }
                LT::RParen => {
                    // 弹到 '(' 或 FT 为止；'(' 丢弃，FT 进输出；栈空则 lenient 无操作。
                    loop {
                        match ops.pop() {
                            None => break,
                            Some(Op::Paren) => break,
                            Some(Op::Fn(f)) => {
                                out.push(Tok::Fn(f));
                                break;
                            }
                            Some(op) => out.push(op.emit()),
                        }
                    }
                    expect_unary = false;
                }
                LT::Comma => {
                    // 镜像 Java：弹到 FunctionToken 为止（无 FT 时连 '(' 一起弹进输出）
                    while let Some(top) = ops.last() {
                        if matches!(top, Op::Fn(_)) {
                            break;
                        }
                        out.push(ops.pop().unwrap().emit());
                    }
                    expect_unary = true;
                }
                LT::Op2(s) => {
                    let op = match s {
                        "==" => Op::Cmp(Cmp::Eq),
                        "!=" => Op::Cmp(Cmp::Ne),
                        "<=" => Op::Cmp(Cmp::Le),
                        ">=" => Op::Cmp(Cmp::Ge),
                        "&&" => Op::Cmp(Cmp::And),
                        _ => Op::Cmp(Cmp::Or),
                    };
                    let prec = op.prec();
                    while let Some(top) = ops.last() {
                        if matches!(top, Op::Paren | Op::Fn(_)) || top.prec() < prec {
                            break;
                        }
                        out.push(ops.pop().unwrap().emit());
                    }
                    ops.push(op);
                    expect_unary = true;
                }
                LT::Op1(s) => {
                    let c = s.chars().next().unwrap();
                    if c == '.' {
                        // swizzle 后缀：SWIZZLE token 直进输出（Java SWIZZLE case）。
                        let mut comps = String::new();
                        loop {
                            let take = match it.peek() {
                                Some(LT::Ident(n)) if n.len() <= 3 && n.chars().all(|ch| "xyz".contains(ch)) => {
                                    n.clone()
                                }
                                _ => break,
                            };
                            it.next();
                            comps.push_str(&take);
                        }
                        if !comps.is_empty() {
                            out.push(if comps.len() == 1 {
                                Tok::Swz(match comps.as_str() {
                                    "x" => 0,
                                    "y" => 1,
                                    _ => 2,
                                })
                            } else {
                                Tok::Swz(9) // 多分量 → 求值期 lenient 零
                            });
                            expect_unary = false;
                        }
                        // 裸 '.'（无分量）= Java UNKNOWN → lenient 跳过
                    } else if expect_unary && (c == '-' || c == '+') {
                        // Java 一元技巧：emit 0 再压二元 '-'（一元 + 同口径）
                        out.push(Tok::Const(0.0));
                        ops.push(Op::Char(b'-'));
                        expect_unary = true;
                    } else if c == '!' && expect_unary {
                        ops.push(Op::Not);
                        expect_unary = true;
                    } else if "+-*/%^<>".contains(c) {
                        let op = Op::Char(c as u8);
                        let prec = op.prec();
                        while let Some(top) = ops.last() {
                            if matches!(top, Op::Paren | Op::Fn(_)) || top.prec() < prec {
                                break;
                            }
                            out.push(ops.pop().unwrap().emit());
                        }
                        ops.push(op);
                        expect_unary = true;
                    } else {
                        return Err(format!("unexpected token: {s}"));
                    }
                }
            }
        }
        // 收尾：弹空全部（含未闭合 '('——Java 同款，RPN 里出现 '(' 求值吃两项得 0）
        while let Some(op) = ops.pop() {
            out.push(op.emit());
        }
        Ok(out)
    }
}

/// `repeat`/`while`/`if`/`else` 前缀剥离（后随空白或括号才算关键字）。
fn strip_keyword<'a>(line: &'a str, kw: &str) -> Option<&'a str> {
    if !line.starts_with(kw) {
        return None;
    }
    let rest = &line[kw.len()..];
    if rest.starts_with(|ch: char| ch.is_whitespace() || ch == '(') {
        Some(rest)
    } else {
        None
    }
}

/// `name = expr` 切分（首个顶层 '='；==/!=/<=/>= 已由词法切开，此处再兜底跳过）。
fn split_assign(line: &str) -> Option<(&str, &str)> {
    let bytes = line.as_bytes();
    let mut eq = None;
    for (i, &b) in bytes.iter().enumerate() {
        if b == b'=' {
            if i + 1 < bytes.len() && bytes[i + 1] == b'=' {
                continue;
            }
            if i > 0 && (bytes[i - 1] == b'!' || bytes[i - 1] == b'<' || bytes[i - 1] == b'>' || bytes[i - 1] == b'=') {
                continue;
            }
            eq = Some(i);
            break;
        }
    }
    let eq = eq?;
    let name = line[..eq].trim();
    if name.is_empty() || !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return None;
    }
    Some((name, line[eq + 1..].trim()))
}

/// 运算符栈条目。
#[derive(Clone, Copy)]
enum Op {
    Char(u8),
    Cmp(Cmp),
    Not,
    Paren,
    Fn(Fn),
}

impl Op {
    fn prec(&self) -> u8 {
        // 统一优先级：|| < && < 比较 < +- < */ % < ^ < 一元 !（决策 §五）
        match self {
            Op::Cmp(Cmp::Or) => 1,
            Op::Cmp(Cmp::And) => 2,
            Op::Cmp(_) => 3,
            Op::Char(b'<') | Op::Char(b'>') => 3,
            Op::Char(b'+') | Op::Char(b'-') => 4,
            Op::Char(b'*') | Op::Char(b'/') | Op::Char(b'%') => 5,
            Op::Char(b'^') => 6,
            Op::Not => 7,
            Op::Fn(_) => 9, // 函数体不受弹栈（RPAREN 才收）
            Op::Paren => 0,
            Op::Char(_) => 0,
        }
    }

    fn emit(self) -> Tok {
        match self {
            Op::Char(c) => Tok::Op(c),
            Op::Cmp(c) => Tok::Cmp(c),
            Op::Not => Tok::Not,
            Op::Paren => Tok::Op(b'('), // 镜像 Java：'(' 进输出，求值吃两项得 0
            Op::Fn(f) => Tok::Fn(f),
        }
    }
}

/// 词法单元。
enum LT {
    Num(f64),
    Ident(String),
    Op1(String),
    Op2(&'static str),
    LParen,
    RParen,
    Comma,
}

/// 手写词法：数字/标识符/双字符运算符/单字符运算符（镜像 FormulaLexer 的 token 族；
/// 已知函数名+`(` 在 compile_expr 内归并为 Fn）。
fn lex(s: &str) -> Result<Vec<LT>, String> {
    let cs: Vec<char> = s.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < cs.len() {
        let c = cs[i];
        if c.is_whitespace() {
            i += 1;
            continue;
        }
        if c.is_ascii_digit() || (c == '.' && i + 1 < cs.len() && cs[i + 1].is_ascii_digit()) {
            let start = i;
            while i < cs.len() && (cs[i].is_ascii_digit() || cs[i] == '.') {
                i += 1;
            }
            if i < cs.len() && (cs[i] == 'e' || cs[i] == 'E') {
                let mut j = i + 1;
                if j < cs.len() && (cs[j] == '+' || cs[j] == '-') {
                    j += 1;
                }
                if j < cs.len() && cs[j].is_ascii_digit() {
                    i = j;
                    while i < cs.len() && cs[i].is_ascii_digit() {
                        i += 1;
                    }
                }
            }
            let text: String = cs[start..i].iter().collect();
            out.push(LT::Num(text.parse().map_err(|_| format!("bad number: {text}"))?));
            continue;
        }
        if c.is_ascii_alphabetic() || c == '_' {
            let start = i;
            while i < cs.len() && (cs[i].is_ascii_alphanumeric() || cs[i] == '_') {
                i += 1;
            }
            out.push(LT::Ident(cs[start..i].iter().collect()));
            continue;
        }
        let two: String = cs[i..(i + 2).min(cs.len())].iter().collect();
        let op2 = match two.as_str() {
            "==" => Some("=="),
            "!=" => Some("!="),
            "<=" => Some("<="),
            ">=" => Some(">="),
            "&&" => Some("&&"),
            "||" => Some("||"),
            _ => None,
        };
        if let Some(op) = op2 {
            out.push(LT::Op2(op));
            i += 2;
            continue;
        }
        match c {
            '(' => out.push(LT::LParen),
            ')' => out.push(LT::RParen),
            ',' => out.push(LT::Comma),
            '+' | '-' | '*' | '/' | '%' | '^' | '<' | '>' | '!' | '.' => out.push(LT::Op1(c.to_string())),
            _ => return Err(format!("unexpected character: {c}")),
        }
        i += 1;
    }
    Ok(out)
}
