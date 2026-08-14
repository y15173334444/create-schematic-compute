# 方案：AbstractContainerScreen → Screen 迁移

> 状态：✅ 已实施（v1.2.5，2026-08-14 全部落地）/ Implemented (shipped in v1.2.5 on 2026-08-14)
> 日期：2026-07-24 起草，2026-08-14 修订为可落地并实施完毕
> 影响：7 个 Screen 类 + 7 个 Menu 类（已删除）+ 7 个 BlockEntity + 7 个 Block + ClientSetup + SchematicCompute + 新增 1 个基类 `AbstractGraphScreen`
> 交叉引用：[`docs/code-architecture.md`](code-architecture.md)「7 个编辑界面（无 Menu 架构）」小节；README v1.2.5 changelog

## 实施记录 / Implementation Record（2026-08-14）

按 §6 执行步骤落地，共 7 个提交（基线 `5892caa` → `5e6ad81`）：

```
5e6ad81 docs: sync code-architecture to the Screen migration
5e618f9 refactor: remove menu plumbing — 7 Menu classes, MENUS registry, MenuProvider
a34120a refactor: migrate MonitorScreen to Screen (most complex screen)
bbf4eab refactor: migrate RadarScreen to Screen (preClose hook for EditBox write-back)
0e9199f refactor: migrate Blueprint/Sensor/ProgramComputer/ControlSeat screens to Screen
cdac33f refactor: migrate SpeedProxyScreen to Screen (pilot for AbstractGraphScreen)
a366492 feat: add AbstractGraphScreen base — Screen + GraphEditor.Host for graph GUIs
```

实施中发现本文档修订稿仍有 **4 处问题**（不影响方案设计，仅调整了机制与提交顺序）：

1. **§2.1.4「只需改基类一处」有误**：`getDisplayName`/`createMenu` 的实现实际分布在 **7 个 BE 子类**（基类 `SyncedGraphBlockEntity` 只声明 `implements MenuProvider`）。实施时 7 个子类的方法与基类声明一并删除。
2. **§4.3 虚拟菜单调用点**：位于 `PortableTerminalScreen.openBlockUI()`（原 640-646 行），7 处 `new XxxMenu(0, editingPos)` 已改为 `new XxxScreen(editingPos)`；Screen 构造签名统一为 `(BlockPos pos)`。
3. **Phase 2 第 5 步会破坏编译**：先删基类 `MenuProvider` 会让其余 6 个 Block 的 `openMenu(be, …)` 失去 MenuProvider 实参。改为：各 Screen 迁移时同步迁移其 Block 与 `registerScreens` 行，基类 MenuProvider 与 BE 方法延后到全部 Screen 迁移完成后一次删除。
4. **§3.3 的直写 `setScreen` 模式使专用服务端无法启动（严重，运行时发现）**：公共 Block 类中 `new XxxScreen(p)` 是客户端类的 `new` 指令，专用服务端校验公共类时触发 `Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER` → `ModLoadingException` → 服务端拒绝启动（`runServer` 实测）。修复：每个 Block 改为调用私有 `@OnlyIn(Dist.CLIENT) openScreen(pos)` 助手方法，方法体由 `runtimedistcleaner` 在专用服务端剥离（commit `0f033a2`）。NeoForge 21.1 已移除 `DistExecutor`，`@OnlyIn` + dist cleaner 是标准替代。

另补充两处设计细化：基类 `render()` 增加 `renderGraphCanvas()` 钩子（Monitor 显示模式画布 / Radar 工具栏经钩子叠加，避免子类复制 render 契约）；`Screen` 无 `renderTooltip(GuiGraphics,int,int)`（1.21.1），tooltip 由 GraphEditor/NodeRenderer 自绘，基类 render 不再调用。

---

## 0. 修订说明（2026-08-14，对照基线 `5892caa`）

本方案初稿（2026-07-24）已对齐到当前代码 `5892caa`。经核对最新代码，原草案有 3 处会导致**编译失败或功能回归**的硬伤、2 处易漏点，已在下文中直接修正：

1. **`pendingLocalOps` 协同守卫（严重）**：原基类 `sendOp()` / `onClose()` 未处理 `pendingLocalOps`，会绕过 `5892caa` 修复（中途加入玩家获取完整图）。已在 §3.1 补上。
2. **`tick()` 引用不存在的 `XxxBlockEntity`（编译失败）**：原代码直接写 `instanceof XxxBlockEntity`，已改为调用子类提供的 `isBlockEntityValid()`。
3. **`render()` 不调 `super.render()`（功能回归）**：`RadarScreen` / `MonitorScreen` 通过 `addRenderableWidget` 注册的 EditBox 将不显示 / 不可交互。已复刻 `AbstractContainerScreen` 的 render 契约。
4. **`removed()` 子类专属逻辑被吞（易漏）**：`RadarScreen.removed()` 有 `applyInputs + hideInputs` 写回 BE，已通过 `preClose()` 钩子保留。
5. **`MenuProvider` 删除位置写错（易漏）**：实际在 `SyncedGraphBlockEntity` 一处，而非 7 个子类，已更正（§2.1.4）。

---

## 1. 背景与动机 / Background

### 1.1 现状

项目中 7 个 GUI 界面全部继承 `AbstractContainerScreen<XxxMenu>`：

| Screen | Menu | BlockEntity |
|--------|------|-------------|
| `BlueprintScreen` | `BlueprintMenu` | `BlueprintBlockEntity` |
| `SpeedProxyScreen` | `SpeedProxyMenu` | `SpeedProxyBlockEntity` |
| `ProgramComputerScreen` | `ProgramComputerMenu` | `ProgramComputerBlockEntity` |
| `SensorScreen` | `SensorMenu` | `SensorBlockEntity` |
| `ControlSeatScreen` | `ControlSeatMenu` | `ControlSeatBlockEntity` |
| `MonitorScreen` | `MonitorMenu` | `MonitorBlockEntity` |
| `RadarScreen` | `RadarMenu` | `RadarBlockEntity` |

### 1.2 为什么要改回 `Screen`

经代码审计，这些界面**实质上根本不是容器界面**，用 `AbstractContainerScreen` 属于架构误用：

#### 1.2.1 模组兼容性（核心动因）

第三方模组（如 **FTB Quests**、**Quark** 等）通过 `AbstractContainerScreen` 的事件钩子注入内容：
- `drawSlot` / `renderSlot` — 在槽位上绘制标记或按钮
- `renderTooltip` — 给物品提示框追加任务信息
- `containerTick` — 注入界面层的定时逻辑
- 通过 `AbstractContainerScreen` 的 `leftPos`/`topPos`/`imageWidth`/`imageHeight` 定位插入点

由于当前 7 个 Screen 继承自 `AbstractContainerScreen`，即使它们不展示任何物品槽，FTB 等模组仍会**将它们识别为容器界面**，在这些全屏画布上注入无关的按钮、任务覆盖层、装饰边框——干扰节点编辑器的正常使用。改为继承 `Screen` 后，这些模组不再识别此类界面为目标，彻底规避误注入。

#### 1.2.2 架构合理性（代码层面）

1. **无物品槽**：所有 7 个 Menu 的 `quickMoveStack()` 恒返回 `ItemStack.EMPTY`，`stillValid()` 恒返回 `true`。没有 shift-click、没有物品同步、没有槽位渲染。
2. **全屏画布**：每个 Screen 都设 `imageWidth = 9999`，完全绕过了容器的 `leftPos`/`topPos` 居中布局，`renderBg` 全权委托给 `GraphEditor`。
3. **Menu 沦为 BlockPos 载体**：Menu 唯一被实际使用的字段是 `blockPos`（以及缓存的 `blockEntity` 引用），其余 `AbstractContainerMenu` 基类设施（containerData、slots、state synchronizer）全部闲置。
4. **已有先例**：`PortableTerminalScreen` 已是纯 `Screen` 实现，构造只接收 `Player`，不依赖 Menu，证明该模式在本项目中可行。
5. **冗余开销**：每次打开界面都会创建 Menu 实例、走 `IMenuTypeExtension` 的 buffer 序列化路径、注册到 `MenuType` registry——这些都是为容器同步设计的机制，对纯画布界面是纯开销。

### 1.3 迁移收益

| 维度 | 迁移前 | 迁移后 |
|------|--------|--------|
| 每个界面依赖的类 | Screen + Menu + MenuType + BlockEntity(MenuProvider) | Screen + BlockEntity |
| 注册项 | `DeferredRegister<MenuType<?>>` × 7 + `RegisterMenuScreensEvent` × 7 | 无（直接 `setScreen`） |
| 网络开销 | openMenu → buffer 序列化 → 客户端反序列化 Menu | 客户端 `setScreen(new XxxScreen(be))`，零网络包 |
| 打开延迟 | 需等服务端 round-trip | 即时打开 |
| 代码行数 | 每个 Menu ~45 行 × 7 = ~315 行 | 0（删除全部 Menu 类） |

---

## 2. 影响面分析 / Impact Analysis

### 2.1 需要修改的文件

#### 2.1.1 删除（7 个 Menu 类）
```
blocks/BlueprintMenu.java
blocks/SpeedProxyMenu.java
blocks/ProgramComputerMenu.java
blocks/SensorMenu.java
blocks/ControlSeatMenu.java
blocks/MonitorMenu.java
blocks/RadarMenu.java
```

#### 2.1.2 修改 — Screen 类（7 个）

每个 `XxxScreen`：
- 父类 `AbstractContainerScreen<XxxMenu>` → `Screen`
- 构造签名 `(XxxMenu m, Inventory inv, Component t)` → `(XxxBlockEntity be)` 或 `(BlockPos pos)`
- 字段 `private final XxxMenu menu` → 删除，改存 `blockEntity` / `blockPos`
- 所有 `menu.blockPos` 引用 → `this.blockPos`
- `containerTick()` → `tick()`
- `renderBg()` → 合并进 `render()`
- `init()` 中 `super.init()` 调用保留（`Screen.init()` 也会清空 widgets）
- `removed()` → `onClose()`（语义更准确，`Screen` 无 `removed()`）。若原 `removed()` 含写回 BE 等子类逻辑（如 `RadarScreen.applyInputs + hideInputs`），改为覆盖基类 `preClose()` 钩子，而非丢弃。
- 删除 `asScreen()` 的 `return this`（`Host.asScreen()` 仍需实现，但不再有歧义）

#### 2.1.3 修改 — Block 类（7 个）

每个 `XxxBlock` 的 `useWithoutItem` / `use` 方法：
```java
// 迁移前
if (l.getBlockEntity(p) instanceof XxxBlockEntity be) {
    sp.openMenu(be, buf -> buf.writeBlockPos(p));
    return InteractionResult.SUCCESS;
}

// 迁移后（仅客户端打开 Screen）
if (l.isClientSide()) {
    if (l.getBlockEntity(p) instanceof XxxBlockEntity be) {
        Minecraft.getInstance().setScreen(new XxxScreen(be));
    }
    return InteractionResult.SUCCESS;
}
return InteractionResult.CONSUME;
```

#### 2.1.4 修改 — BlockEntity 类（仅 1 处！）

> **修正**：`MenuProvider` 不在 7 个子类上，而是在它们共同基类 `SyncedGraphBlockEntity` 上（`extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity`）。因此**只需改 `SyncedGraphBlockEntity` 一处**，7 个子类（Blueprint / SpeedProxy / ProgramComputer / Sensor / ControlSeat / Monitor / Radar）通过继承自动失去 MenuProvider，**无需逐个改**。

`SyncedGraphBlockEntity`：
- 删除 `implements MenuProvider` 及 `getDisplayName` / `createMenu` 方法
- 不再需要 `MenuProvider` 接口

#### 2.1.5 修改 — `SchematicCompute.java`

- 删除 `MENUS` DeferredRegister 及其 7 个 `DeferredHolder<MenuType<?>, ...>` 注册项
- 删除 `MENUS.register(modEventBus)` 调用
- 删除 `MenuType`、`IMenuTypeExtension` 相关 import

#### 2.1.6 修改 — `ClientSetup.java`

- 删除 `registerScreens(RegisterMenuScreensEvent)` 方法及其 `@SubscribeEvent`
- 删除 `RegisterMenuScreensEvent` import
- 删除 7 个 Screen 的 import（Screen 工厂不再需要在此注册）

### 2.2 不受影响的部分

- **`GraphEditor` 与 `GraphEditor.Host` 接口**：`Host` 接口不依赖 Menu，各 Screen 的 `Host` 实现方法签名不变。
- **网络包**：所有 `BlueprintSavePacket`、`BlueprintTogglePacket`、`GraphJoinPacket`、`GraphLeavePacket`、`RadarSettingsPacket`、`GraphEditOpPacket` 等保持不变——它们直接通过 `PacketDistributor.sendToServer()` 发送，不依赖 Menu。
- **多人协作逻辑**：`init()` 中发 `GraphJoinPacket`、`onClose()` 中发 `GraphLeavePacket` 的逻辑不变，只是方法名从 `removed()` → `onClose()`。
- **`PortableTerminalScreen`**：已是纯 `Screen`，无需改动。
- **`PortableTerminalItem`**：`screenOpener` 机制不变。

---

## 3. 详细迁移方案 / Migration Plan

### 3.1 统一 Screen 基类（可选但推荐）

提取公共基类 `AbstractGraphScreen`，消除 7 个类中大量重复的 `GraphEditor.Host` 样板代码：

```java
package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.*;
import io.github.y15173334444.create_schematic_compute.network.*;
import io.github.y15173334444.create_schematic_compute.blocks.SyncedGraphBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;

/**
 * 所有节点图编辑界面的公共基类。
 * 持有 BlockEntity 引用 + BlockPos，管理 GraphEditor 生命周期，
 * 实现 GraphEditor.Host 的多人协作方法。
 */
public abstract class AbstractGraphScreen extends Screen implements GraphEditor.Host {

    protected final BlockPos blockPos;
    protected final GraphEditor editor;

    protected AbstractGraphScreen(Component title, BlockPos pos) {
        super(title);
        this.blockPos = pos;
        this.editor = new GraphEditor(this, this);
    }

    /** 子类在构造器中调用，设置节点过滤器。 */
    protected void setNodeFilter(java.util.function.Predicate<NodeType> filter) {
        editor.setNodeFilter(filter);
    }

    // ── Screen 生命周期 ──

    @Override
    protected void init() {
        super.init();
        PacketDistributor.sendToServer(new GraphJoinPacket(blockPos));
    }

    @Override
    public void tick() {
        // BE 失效（方块被破坏 / 被卸载）时自动关闭，与现状一致
        if (!isBlockEntityValid()) {
            onClose();
            return;
        }
        editor.clientTick();
    }

    @Override
    public void onClose() {
        preClose();                              // 子类钩子：关界面时把 EditBox 输入写回 BE 等
        var be = getBE();
        if (be != null) be.pendingLocalOps = 0;  // 复位守卫（对应 5892caa 的 pendingLocalOps 修复）
        editor.onClose();
        editor.clearRemotePresences();
        PacketDistributor.sendToServer(new GraphLeavePacket(blockPos));
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g, mx, my, pt);    // 背景层
        editor.renderBg(g, mx, my);              // 节点画布在下
        for (var r : this.renderables)           // EditBox 等 widget 在上（复刻 AbstractContainerScreen 契约）
            r.render(g, mx, my, pt);
        this.renderTooltip(g, mx, my);           // tooltip 最上
    }

    // ── 输入事件委托（所有子类共享） ──

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn);
    }
    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        editor.mouseReleased(mx, my, btn);
        return super.mouseReleased(mx, my, btn);
    }
    @Override
    public void mouseMoved(double mx, double my) {
        editor.mouseMoved(mx, my);
    }
    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy);
    }
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        return editor.mouseScrolled(mx, my, sx, sy);
    }
    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key == 256) { onClose(); return true; }
        if (key >= 32 && key <= 96) return true;
        return super.keyPressed(key, sc, mod);
    }
    @Override
    public boolean keyReleased(int key, int sc, int mod) {
        return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod);
    }
    @Override
    public boolean charTyped(char ch, int mod) {
        return editor.charTyped(ch, mod) || super.charTyped(ch, mod);
    }

    // ── GraphEditor.Host 多人协作 ──

    @Override
    public BlockPos getBlockPos() { return blockPos; }
    @Override
    public UUID getPlayerUUID() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getUUID() : UUID.randomUUID();
    }
    @Override
    public GraphEditor getEditor() { return editor; }
    @Override
    public String getPlayerName() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getName().getString() : "";
    }
    @Override
    public void sendOp(GraphOp op) {
        var be = getBE();
        if (be != null) be.pendingLocalOps++;    // 守卫：本地玩家有未 ACK 的 op（对应 5892caa）
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }
    @Override
    public void onRemoteOp(GraphOp op) { editor.onRemoteOp(op); }
    @Override
    public Screen asScreen() { return this; }

    // ── 子类覆盖 ──

    /** BE 失效检查（方块被破坏 / 卸载时为 false），供 tick() 自动关界面。 */
    protected abstract boolean isBlockEntityValid();

    /** 返回当前客户端 BlockEntity；供 sendOp / onClose 访问 pendingLocalOps 守卫。子类返回各自具体类型（协变返回即可）。 */
    protected abstract SyncedGraphBlockEntity getBE();

    /** 关界面前钩子：子类可在此把 EditBox 输入写回 BE（如 RadarScreen 的 applyInputs + hideInputs）。默认空实现。 */
    protected void preClose() {}
}
```

> **注意**：`tick()` 中的 BE 类型检查通过子类提供的 `protected abstract boolean isBlockEntityValid()` 完成（已应用，不再写死 `XxxBlockEntity`）；`getBE()` 抽象方法供 `sendOp` / `onClose` 访问 `pendingLocalOps` 守卫；`preClose()` 钩子供子类在关闭前写回 EditBox 输入（如 `RadarScreen`）。

### 3.2 Screen 迁移示例 — 以 `SpeedProxyScreen` 为例

#### 迁移前（现状）

```java
public class SpeedProxyScreen extends AbstractContainerScreen<SpeedProxyMenu> implements GraphEditor.Host {
    private final SpeedProxyBlockEntity blockEntity;
    private final GraphEditor editor;

    public SpeedProxyScreen(SpeedProxyMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(...);
    }
    // ... 100+ 行重复的 Host 实现 + 输入委托
}
```

#### 迁移后（继承基类）

```java
public class SpeedProxyScreen extends AbstractGraphScreen {

    public SpeedProxyScreen(SpeedProxyBlockEntity be) {
        super(Component.translatable("container.create_schematic_compute.speed_proxy"),
              be.getBlockPos());
        setNodeFilter(nt -> nt == NodeType.SPEED_CTRL
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.BUS_IN
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE);
    }

    // 注意：必须是 protected（不可 private），基类 sendOp()/onClose() 要通过它访问 pendingLocalOps 守卫
    @Override
    protected SpeedProxyBlockEntity getBE() {
        if (Minecraft.getInstance().level != null
            && Minecraft.getInstance().level.getBlockEntity(blockPos) instanceof SpeedProxyBlockEntity be) {
            return be;
        }
        return null;
    }

    @Override
    protected boolean isBlockEntityValid() {
        return Minecraft.getInstance().level != null
            && Minecraft.getInstance().level.getBlockEntity(blockPos) instanceof SpeedProxyBlockEntity;
    }

    @Override
    public NodeGraph getGraph() {
        SpeedProxyBlockEntity be = getBE();
        return be != null ? be.graph : new NodeGraph();
    }
    @Override
    public boolean isRunning() {
        SpeedProxyBlockEntity be = getBE();
        return be != null && be.running;
    }
    @Override
    public Map<Integer, Boolean> getFlipflopStates() {
        SpeedProxyBlockEntity be = getBE();
        return be != null ? be.runtimeState.flipflopStates : null;
    }
    @Override
    public EvalSnapshot getCachedEvalSnapshot() {
        SpeedProxyBlockEntity be = getBE();
        return be != null ? be.cachedEvalSnapshot : null;
    }

    @Override
    public void saveGraph() {
        try {
            SpeedProxyBlockEntity be = getBE();
            if (be == null || be.getLevel() == null) return;
            var tag = new CompoundTag();
            tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Save", e);
        }
    }

    @Override
    public void toggleRunning(boolean start) {
        SpeedProxyBlockEntity be = getBE();
        if (be != null) {
            be.running = start;
            PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start));
        }
    }
}
```

**行数对比**：114 行 → ~60 行（减少 ~47%），且所有输入事件处理和多人协作样板代码消失。

### 3.3 Block 迁移示例 — 以 `SpeedProxyBlock` 为例

```java
// 迁移前
@Override
public InteractionResult useWithoutItem(BlockState st, Level l, BlockPos p, Player pl, BlockHitResult hit) {
    if (!l.isClientSide()) {
        if (l.getBlockEntity(p) instanceof SpeedProxyBlockEntity be) {
            pl.openMenu(be, buf -> buf.writeBlockPos(p));
        }
    }
    return InteractionResult.SUCCESS;
}

// 迁移后
@Override
public InteractionResult useWithoutItem(BlockState st, Level l, BlockPos p, Player pl, BlockHitResult hit) {
    if (l.isClientSide()) {
        if (l.getBlockEntity(p) instanceof SpeedProxyBlockEntity be) {
            Minecraft.getInstance().setScreen(new SpeedProxyScreen(be));
        }
    }
    return InteractionResult.SUCCESS;
}
```

### 3.4 BlockEntity 迁移

```java
// 迁移前
public class BlueprintBlockEntity extends BlockEntity implements MenuProvider {
    // ...
    @Override
    public Component getDisplayName() {
        return Component.translatable("container." + SchematicCompute.MOD_ID + ".blueprint");
    }
    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
        return new BlueprintMenu(id, this);
    }
}

// 迁移后
public class BlueprintBlockEntity extends BlockEntity {
    // ...（删除 MenuProvider 相关方法）
}
```

### 3.5 SchematicCompute.java 清理

```java
// 删除以下内容：
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);

public static final DeferredHolder<MenuType<?>, MenuType<BlueprintMenu>> BLUEPRINT_MENU =
    MENUS.register("blueprint", () -> IMenuTypeExtension.create((id, inv, buf) -> new BlueprintMenu(id, inv, buf)));
// ... 其余 6 个 MENU 注册项同理删除

// 在 modSetup 中删除：
MENUS.register(modEventBus);
```

### 3.6 ClientSetup.java 清理

```java
// 删除整个方法及其 @SubscribeEvent：
@net.neoforged.bus.api.SubscribeEvent
public static void registerScreens(RegisterMenuScreensEvent event) {
    event.register(SchematicCompute.BLUEPRINT_MENU.get(), BlueprintScreen::new);
    // ... 7 行
}

// 删除相关 import：
// import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
// import 各 Screen 类（若不再被其他方法引用）
```

---

## 4. 特殊情况处理 / Edge Cases

### 4.1 `RadarScreen` — 有原生 EditBox 组件

`RadarScreen` 在 `init()` 中通过 `addRenderableWidget()` 注册了 6 个 `EditBox`（rangeInput、scaleInput 等）。迁移到 `Screen` 后：
- `Screen` 同样有 `addRenderableWidget()`（继承自 `AbstractWidgetEventHandler`），**无需改动**。
- `init()` 中 `super.init()` 调用保留即可。
- `containerTick()` → `tick()`，内部逻辑不变。
- **关键**：原 `RadarScreen.removed()` 里有 `RadarBlockEntity be = getBE(); if (be != null) { applyInputs(be); hideInputs(); }`——这是"关界面时把 EditBox 输入写回 BE"。迁移后基类 `onClose()` 不调用这些，必须通过覆盖 `preClose()` 钩子保留，否则雷达设置关界面后不生效：
  ```java
  @Override
  protected void preClose() {
      RadarBlockEntity be = getBE();
      if (be != null) { applyInputs(be); hideInputs(); }
  }
  ```
- 基类 `render()` 已复刻 `AbstractContainerScreen` 的 render 契约（先 `renderBackground` → `editor.renderBg` → 遍历 `renderables` 画 EditBox → `renderTooltip`），故 6 个 EditBox 在 `Screen` 下仍能正常显示与交互。

### 4.2 `MonitorScreen` — 超大文件（1784 行）

`MonitorScreen` 是最复杂的 Screen，包含像素编辑器、图层拖拽、显示模式等。迁移要点：
- 构造签名 `(MonitorMenu m, Inventory inv, Component t)` → `(MonitorBlockEntity be)`
- `settingFields`（8 个 EditBox）在构造器中初始化，依赖 `Minecraft.getInstance().font`——这在 `Screen` 中同样可用。
- `render()` 需要合并 `renderBg()` + 原有 `render()` 逻辑（若有额外 render 覆盖）。
- 所有 `menu.blockPos` → `this.blockPos`。
- `containerTick()` → `tick()`。

### 4.3 `BlueprintScreen` 的 Terminal 虚拟菜单路径

`BlueprintMenu` 有一个 `(int id, BlockPos pos)` 构造和 `(int id, Inventory inv)` 构造，用于"终端虚拟菜单"场景。迁移后：
- `PortableTerminalScreen` 已经是纯 `Screen`，通过 `PortableTerminalItem.screenOpener` 打开，不经过 Menu。
- 若终端需要远程打开某个方块的编辑界面，可直接通过 `ClientboundGraphEvalPacket` 等现有网络包携带 `BlockPos`，客户端收到后 `setScreen(new XxxScreen(clientBE))`。
- 需要逐一排查 `BlueprintMenu(int id, BlockPos pos)` 和 `BlueprintMenu(int id, Inventory inv)` 的调用点，确认是否有实际使用场景。若有，需提供等价的 `Screen` 打开路径。

### 4.4 BlockEntity 引用的客户端安全性

迁移后 `Screen` 直接持有客户端 `BlockEntity` 引用（通过 `level.getBlockEntity(pos)`）。这与当前 `getBE()` 方法的逻辑完全一致——当前代码也是从 `menu.blockPos` 出发在客户端 level 查找 BE。`blockEntity` 字段在当前代码中只是缓存首次获取的引用，迁移后 `getBE()` 每次从 level 查找即可（或保留缓存字段）。

---

## 5. 风险与缓解 / Risks & Mitigations

| 风险 | 级别 | 说明 | 缓解 |
|------|------|------|------|
| 多人协作时序变化 | 中 | `openMenu` 有服务端确认 round-trip，`setScreen` 是纯客户端即时打开 | `GraphJoinPacket` 仍在 `init()` 中发送，服务端仍能感知玩家加入编辑会话。时序差异在毫秒级，不影响协作正确性 |
| BlockEntity 引用失效 | 低 | 客户端 BE 可能在 Screen 打开期间被卸载 | `tick()` 中已有 `isBlockEntityValid()` 检查，BE 失效时自动 `onClose()`，与现状一致 |
| Terminal 虚拟菜单路径断裂 | 低 | `BlueprintMenu` 的非 BE 构造可能被外部调用 | 需全局搜索 `new BlueprintMenu(` / `new SpeedProxyMenu(` 等确认调用点 |
| 服务端不再感知界面打开 | 低 | `openMenu` 会让服务端创建 Menu 实例 | 当前服务端 Menu 的 `stillValid` 恒为 true，未做任何权限检查。服务端感知靠的是 `GraphJoinPacket`，不受影响 |
| 回归测试范围大 | 中 | 7 个界面 + 7 个 Block 交互 | 逐个界面验证：打开、编辑、保存、关闭、多人协作 |
| `pendingLocalOps` 守卫丢失 | 高 | 若基类未处理 `pendingLocalOps`，会绕过 `5892caa` 修复，中途加入的玩家拿不到完整图 | 基类 `sendOp()` 自增、`onClose()` 复位（见 §3.1）；验证"玩家 A 打开界面 → 玩家 B 中途加入也能拿到完整图" |

---

## 6. 执行步骤 / Execution Steps

建议按以下顺序逐步迁移，每步可独立编译验证：

### Phase 1：基础设施（1 次提交）
1. 创建 `AbstractGraphScreen` 基类
2. 编译验证基类无错

### Phase 2：最简单的 Screen 先行（1 次提交）
3. 迁移 `SpeedProxyScreen` → 继承 `AbstractGraphScreen`
4. 修改 `SpeedProxyBlock.useWithoutItem` → `setScreen`
5. 修改 `SyncedGraphBlockEntity` → 删除 `MenuProvider`（**仅此一处**，7 个子类自动失去 MenuProvider）
6. **暂不删除** `SpeedProxyMenu` 和 `SPEED_PROXY_MENU` 注册（保持编译通过）
7. 编译 + 手动测试 SpeedProxy 界面

### Phase 3：批量迁移（1-2 次提交）
8. 按同样模式迁移 `SensorScreen`、`ProgramComputerScreen`、`ControlSeatScreen`、`BlueprintScreen`
9. 迁移 `RadarScreen`（注意 EditBox 组件）
10. 迁移 `MonitorScreen`（最复杂，单独提交）

### Phase 4：清理（1 次提交）
11. 删除 7 个 Menu 类
12. 删除 `SchematicCompute.MENUS` 及 7 个 `DeferredHolder`
13. 删除 `ClientSetup.registerScreens`
14. 全局搜索确认无残留引用：`AbstractContainerScreen`、`MenuProvider`、`openMenu`、`XxxMenu`

### Phase 5：验证
15. 逐个方块右键打开界面
16. 节点增删改、连线、保存、运行/停止
17. 多人服务器测试协作编辑
18. 雷达设置面板、监视器像素编辑器等特殊功能

---

## 7. 验证清单 / Verification Checklist

- [ ] 7 个方块右键均可打开界面
- [ ] 界面内节点创建、拖拽、连线、删除正常
- [ ] Ctrl+S（或等价保存）触发 `BlueprintSavePacket`，重进后图数据保留
- [ ] 运行/停止按钮触发 `BlueprintTogglePacket`
- [ ] Esc 关闭界面，触发 `GraphLeavePacket`
- [ ] 多人模式下，A 打开界面后 B 可看到 A 的光标
- [ ] 破坏方块后，已打开的界面自动关闭（`tick()` 中的 BE 检查）
- [ ] `RadarScreen` 的 6 个 EditBox 输入正常
- [ ] `MonitorScreen` 像素编辑器、图层拖拽正常
- [ ] `PortableTerminalScreen` 不受影响
- [ ] 全局搜索无 `AbstractContainerScreen` / `MenuProvider` / `openMenu` 残留（`PortableTerminalScreen` 除外）
- [ ] 编译无 warning（未使用 import 等）
- [ ] 多人回归：玩家 A 打开界面 → 玩家 B 中途加入，B 能拿到完整图（验证 `pendingLocalOps` 守卫未被绕过）
- [ ] 安装 FTB Quests / Quark 等模组后，节点编辑界面无按钮、任务覆盖层、装饰边框等误注入
