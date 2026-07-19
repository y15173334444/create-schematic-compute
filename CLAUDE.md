## Agent skills

### Issue tracker

GitHub Issues，外部 PR 也作为 triage 需求来源。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认的五个 triage 标签名：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局 — 根目录下的 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。

## 追问规则

当我提出任何方案、设计、架构决策、或实现方案——任何涉及在动手前需要在多个选择之间做取舍的情况——**主动调用 `grilling` 技能**进行压力测试。触发场景包括但不限于：
- "我要做 X"
- "我们应该用 Y"
- "方案是 Z"
- 任何架构或设计提案
- 任何非平凡的实现决策
- "A 还是 B？"

追问引擎会逐个质询设计的每个分支，在写代码之前解决决策依赖。能在代码库里验证的问题先查代码库，剩下的再追问我。
