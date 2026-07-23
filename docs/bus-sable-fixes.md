# BUS 频道系统修复 + Sable 距离检查

> 日期：2026-07-24

---

## 1. BUS 自身冲突误报

### 问题
创建 BUS_OUT "ch1" 后，自身立即显示红色冲突警告。

### 根因
服务端注册 "ch1" 成功后广播 `BusBandSyncPacket("ch1", bands)` 到所有客户端。
客户端收到后调用 `SignalBus.registerBands("ch1", bands)` 存入本地 `BAND_REGISTRY`，
然后 `BusBandSyncPacket.reevaluateBusConflicts()` 发现 `getBands("ch1") != null`，
直接判定为跨方块冲突 → `busConflict = true`。

### 修复（2 处）

**`BusBandSyncPacket.reevaluateBusConflicts()`** — 增加 `anyBusOutOwns` 检查：
当前图中已有 BUS_OUT 叫这个名字时，频段是自己注册的，不标冲突。

**`GraphEditor.reevaluateBusConflicts()`** — 同样的检查（之前已添加）：
`getBands()` 返回非空时，先确认图中没有同名的 BUS_OUT，才认为来自外部方块。

---

## 2. BUS 频道生命周期

### 问题 1：冲突时获取旧频道图
A 创建 BUS_OUT "ch1" 带频段 [x,y]，B 创建同名 BUS_OUT 无频段。
B 收到 A 的 `BusBandSyncPacket` 后被 `syncBandsFromServer` 覆盖，获取了 A 的频段。

### 问题 2：改名后旧频道残留
A 将 BUS_OUT 从 "ch1" 改为 "ch2"，"ch1" 的信号数据残留在 `SignalBus.SIGNALS` 中。

### 问题 3：接管频道时被旧数据覆盖
A 的 BUS_OUT 运行求值产生数据后挖掉，B 新建同名 BUS_OUT 注册频道时，
`registerChannel` 中的 `internalMap.putAll(existing.internalMap)` 将旧数据拷入新 map。

### 修复（3 处）

**`SignalBus.registerChannel()`** — 移除 `internalMap.putAll(existing.internalMap)`。
同一 owner 重新注册时只替换 map 引用，不复制旧值。

**`SignalBus.updateChannel()`** — 同样的移除。

**`SignalBus.unregisterChannel()`** — refCount 归零时调用 `clearBus(channelName)`，
清除 `SIGNALS` 中的残留信号数据。

**`BusChannelHelper.syncBandsFromServer()`** — 跳过 `busConflict=true` 的 BUS_OUT 节点，
冲突节点的频段保持自身数据，不被频道所有者覆盖。

---

## 3. Sable 结构距离检查

### 问题
我们添加的安全距离检查（128 格）对 Sable 结构上的方块全部拦截。
因为 Sable 子层级中方块的 `worldPosition` 在子层级坐标空间（几千万格外），
普通 `dx*dx + dz*dz` 永远超限。

### 修复

**`SablePacketHelper.isWithinReachableRange()`** — 三层回退：
1. 普通世界坐标距离检查（非 Sable 快速路径）
2. 遍历子层级，通过 `chunk.getBlockEntities()` 匹配 `entry.getKey().equals(pos)` 找到 BE
3. 使用缓存的子层级变换（quaternion 旋转 + origin 偏移）计算世界空间位置 → 距离检查

**`SablePacketHelper.getOrComputeSubTransform()`** — 子层级变换缓存：
`ConcurrentHashMap<identityHashCode, double[10]>`，首次反射后 O(1) 命中。

**`SablePacketHelper.scanDevices()`** — 复用缓存：
从原来每子层级 ~20 次反射调用改为 `getOrComputeSubTransform()` 一次缓存查询。

---

## 4. 便携终端扫描范围

256 格 → 128 格，与网络包安全距离限制一致。
修改：`PortableTerminalScreen.java` 上限 + `README.md` 描述。

---

## 修改文件清单

| 文件 | 修改 |
|------|------|
| `network/SignalBus.java` | 移除 `putAll`（registerChannel + updateChannel）；unregisterChannel 时 clearBus |
| `network/BusChannelHelper.java` | syncBandsFromServer 跳过冲突 BUS_OUT |
| `network/BusBandSyncPacket.java` | reevaluateBusConflicts 增加 anyBusOutOwns 检查 |
| `network/SablePacketHelper.java` | 新增 isWithinReachableRange、getOrComputeSubTransform、checkSableDistance；scanDevices 复用缓存 |
| `blocks/GraphEditor.java` | reevaluateBusConflicts 增加 anyBusOutOwns 检查 |
| `client/PortableTerminalScreen.java` | 扫描范围 256→128 |
| `README.md` | 便携终端范围更新 |
