# 代码审查报告：多人协作图编辑功能（v1.2.4+ WIP）

- **日期**: 2026-07-16
- **范围**: WIP 基线 `809c500`（31 个文件，+1744/-32 行）— 多人协作图编辑功能
- **方法**: 5 维度并行审查（服务端逻辑 / 客户端编辑器 / 网络协议安全 / 端侧分离与生命周期 / 持久化一致性），每项发现经独立对抗性验证（58 个代理）
- **结果**: 52 项发现 → 确认 50 项、驳回 2 项 → 去重合并 32 个独立问题
- **编译状态**: ✅ 通过
- **总体结论**: 功能可编译，存在 6 个 Critical 缺陷（3 个正常玩法触发），不建议按基线发布

---

## 严重程度统计

| 级别 | 数量 | 触发条件 |
|---|---|---|
| 🔴 Critical | 6 | 3 项正常玩法触发，3 项协议/恶意客户端 |
| 🟠 High | 11 | 多为双人正常协作 |
| 🟡 Medium | 9 | 边界场景 / 恶意客户端 |
| ⚪ Low | 6 | 日志、泄漏、死代码 |

---

## 🔴 Critical（6 项）

| # | 文件:行 | 问题 | 触发 |
|---|---|---|---|
| C1 | `EditSessionRegistry.java:126` | **环检测跑在 ID 重映射副本上** — `NodeGraph.copy()` 把 ID 重新分配，`op.fromId/toId` 不再对应原节点。删过节点后 (a) `computeTopoOrder` NPE 打死 op 处理 (b) 真正成环的边**通过**校验写入权威图 (c) 合法连接被误拒 | 正常 |
| C2 | `EditSessionRegistry.java:135` | **存在性检查用外层图** — op 已路由到 `encap.subGraph`，但 `findNode` 仍查外层 `graph`。子图连线被误拒；竞态 ID 恰好在外层存在 → 悬空连接 → tick 求值 NPE 崩溃 | 正常 |
| C3 | `OpExecutor.java:41` | **MOVE_NODE 服务器不落地** — ≥2px 只设 `remote*` lerp 动画字段，仅客户端渲染循环推进；服务器永不落地 x/y，transient 字段不序列化 → 存档时所有节点位置丢失 | 正常 |
| C4 | `GraphEditor.java:1401` | **并发 ADD_NODE ID 冲突** — 客户端自分配 ID 直发 ADD_NODE；`ADD_NODE_REQUEST` + tempId/ack 重映射是死代码（ack 永远 `0,0`）。两人同时加节点 → 两个同 ID 节点 → 永久失步并持久化 | 并发 |
| C5 | `GraphEditOpPacket.java:125` | **无距离/会话校验** — 改装客户端可强制加载任意区块（DoS）并编辑任意坐标图方块 | 恶意 |
| C6 | `GraphEditOpPacket.java:59` | **bands 解码无上界** — `bandCount` varint 可到 MAX_INT → ~8GB 单次分配 → 服务器 JVM OOM | 恶意 |

## 🟠 High（11 项）

| # | 文件:行 | 问题 |
|---|---|---|
| H1 | `EditSessionRegistry.java:31` | **会话键无维度** — 同坐标主世界/下界/Sable 共享会话，跨图损坏 |
| H2 | `GraphEditor.java:2155` | **REJECT 无回滚** — 连线先本地应用再发 op；被拒后 REJECT 是 no-op，客户端永远带着服务器没有的边 |
| H3 | `GraphEditor.java:428` | **SET_PARAM 回声** — `eb.setValue` 触发 responder → 自己发 op + ff3 舍入覆盖发送者精确值 + 进错撤销栈 |
| H4 | `GraphEditOpSyncPacket.java:41` | **客户端不校验 pos** — 关屏后迟到的 op 应用到刚打开的另一个图（各图 ID 都从 1 开始） |
| H5 | `GraphEditor.java:280` | **Presence 永不移除** — 对方断线/关屏后其选中节点永久软锁，无人能再编辑 |
| H6 | `GraphEditor.java:390` | **EXPAND/COLLAPSE 忽略 ownerNodeId** — 子图的展开操作展开主图同 ID 节点 + 抢键盘焦点 |
| H7 | `GraphEditor.java:2224` | **多选撤销坐标错误** — 5 个节点全跳到主节点旧位置堆叠，并同步到所有客户端 |
| H8 | `OpExecutor.java:216,195` | **负数索引绕过边界检查** — `hotbarSlot=-1` 或 `imageFrameIndex` 负数 → 服务器 `ArrayIndexOutOfBoundsException` |
| H9 | `SchematicCompute.java:172` | **断线不清理 editors** — `leave` 只靠客户端关屏包；掉线/崩溃/被踢 → 幽灵编辑者永久残留 |
| H10 | `EditSessionRegistry.java:178` | **7 种方块只有 Blueprint setChanged()** — 其余 6 种的协作编辑在世界保存时全部丢失 |
| H11 | `BlueprintSavePacket.java:35` | **旧保存路径按需全量覆盖** — 关屏/中途多处用客户端快照写回服务器图，践踏并发编辑，使 opLog 无效 |

## 🟡 Medium（9 项）

| # | 文件:行 | 问题 |
|---|---|---|
| M1 | `EditSessionRegistry.java:144` | **失败 op 也 ack** — `addConnection` 返回 false（输入已被占）仍 ack 成功 + 广播 + 入 opLog；发起者永久失步 |
| M2 | `GraphEditor.java:131` | **undo/redo 应用到当前查看的图** — 在子图里按 Ctrl+Z 撤销了错误图上的操作 |
| M3 | `GraphEditor.java:123` | **撤销缺口** — 删除节点不记录；Ctrl+D 复制不记录；REMOVE_CONN、SET_DISPLAY_TEXT 记录但无反向 |
| M4 | `GraphEditor.java:2217` | **点击选中广播 op** — 每次点选都发 SET_ZORDER + 零位移 MOVE + 清空 redo 栈 |
| M5 | `GraphEditor.java:414` | **远程 REMOVE_NODE 不清理悬挂引用** — 别人删掉你正在编辑的封装节点 → 后续所有编辑被服务器静默丢弃 |
| M6 | `GraphEditor.java:2532` | **Ctrl+D 同步缺子图/signalBands/itemParams** — 复制封装节点本地有完整子图，服务器/其他人是空的 |
| M7 | `EditSessionRegistry.java:148` | **actor UUID 可伪造** — 服务器转发客户端提供的 UUID，其他客户端看到攻击者冒充的编辑者 |
| M8 | `GraphJoinPacket.java:44` | **先注册后校验** — 对非图方块实体 `send(null)` NPE |
| M9 | `BlueprintScreen.java:55` | **窗口 resize 重发 join** — F11/改 GUI 比例 → 全量 NBT 重载替换正在编辑的图对象，所有引用悬空 |

## ⚪ Low（6 项）

| # | 文件:行 | 问题 |
|---|---|---|
| L1 | `EditSessionRegistry.java:108` | 每个 op INFO 日志（拖动刷屏）+ `chunkPos` 死代码 |
| L2 | `GraphEditor.java:293` | 软锁忽略 ownerNodeId — 子图选择锁住主图同 ID 节点 |
| L3 | `GraphEditor.java:2290` | 拖含 30 节点的注释 → ~620 ops/秒洪泛，200 条 opLog ~0.3s 滚穿 |
| L4 | `EditSessionRegistry.java:32` | `editVersions`/`opLogs` 只停机清理 — 运行中无 per-position 释放 |
| L5 | `GraphEditOpPacket.java:30` | `TYPE_SYNC` + `handleClient` 死代码；和 `GraphEditOpSyncPacket` 同资源 ID — 未来误注册即崩 |
| L6 | `EditSessionRegistry.java:50` | `editVersion` 播种自相矛盾（join=1，nextVersion 从 0 数）；`getOpsSince` 零调用方 |

---

## 五个系统性根因

| 根因 | 消除 | 改动量 |
|---|---|---|
| **A. 校验对象错误** — 环检测副本 ID 重映射；存在性检查用错图 | C1 C2 | ~30 行 EditSessionRegistry + ~15 行 NodeGraph（加 structuralCopy） |
| **B. MOVE 服务器不落地** — OpExecutor 不分端；服务器需要直接写 x/y | C3 | ~20 行 OpExecutor（端分支或参数） |
| **C. ID 权威性** — 客户端自分配最终 ID；启用 `ADD_NODE_REQUEST→ACK` 流程 | C4 | ~80 行 GraphEditor + ~30 行 OpExecutor |
| **D. 恶意客户端防护** — 距离/会话校验、varint 上界、负数检查、actor 覆盖 | C5 C6 H8 M7 M8 | ~60 行（分散在 ~5 个 handler） |
| **E. 会话键 + 客户端过滤** — `BlockPos` → `GlobalPos`；handler 比对 pos | H1 H4 | ~30 行（改键 + handler guard） |
| 其他 | 标脏全类型、生命周期清理、保存路径冲突、客户端编辑器批量 | 剩余全部 | ~200 行 |

---

## 修复顺序建议

1. **A: 服务器校验** → 2. **B: MOVE 落地** → 3. **C: 服务器权威 ID** → 4. **D: 协议加固** → 5. **E: 会话键** → 6. **标脏全类型** → 7. **生命周期** → 8. **新旧保存路径** → 9. **客户端编辑器批量** → 10. **Low 级清理**
