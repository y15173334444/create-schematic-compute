package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.*;
import io.github.y15173334444.create_schematic_compute.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Portable Terminal Screen — a handheld-device GUI that scans for nearby
 * compatible block entities (radar, monitor, blueprint, program-computer,
 * control-seat, sensor, speed-proxy) and allows the player to remotely open
 * their configuration UIs.
 *
 * <p>Supports two discovery modes:
 * <ul>
 *   <li><b>Local scan</b> — iterates loaded chunks within the configured
 *       range, matches {@link GraphBlockEntity} instances by class.</li>
 *   <li><b>Sable scan</b> — sends a {@link ScanSablePacket} to the server
 *       which responds with wireless (cross-dimension) devices discovered
 *       via the Sable network.</li>
 * </ul>
 *
 * <p>The screen is position-aware: it re-scans automatically when the player
 * moves more than {@value #MOVE_THRESHOLD} blocks since the last scan.
 *
 * <p>Editing a device opens its native screen wrapped in a
 * {@link TerminalWrapper} so that closing the inner screen returns the
 * player to this terminal rather than to the game world.
 *
 * 便携终端界面 — 显示一个手持设备的 GUI，扫描周围兼容的方块实体（雷达、监视器、
 * 蓝图、编程计算机、控制座椅、传感器、速度代理），并允许玩家远程打开它们的配置界面。
 *
 * <p>支持两种发现模式：
 * <ul>
 *   <li><b>本地扫描</b> — 在配置范围内遍历已加载区块，按类匹配
 *       {@link GraphBlockEntity} 实例。</li>
 *   <li><b>Sable 扫描</b> — 向服务器发送 {@link ScanSablePacket}，
 *       服务器通过 Sable 网络响应发现的无线（跨维度）设备。</li>
 * </ul>
 *
 * <p>此界面具有位置感知能力：当玩家移动超过 {@value #MOVE_THRESHOLD} 格后，
 * 会自动重新扫描。
 *
 * <p>编辑设备时会打开其原生界面，包裹在 {@link TerminalWrapper} 中，
 * 这样关闭内部界面后玩家会回到此终端界面而非游戏世界。
 */
public class PortableTerminalScreen extends Screen {

    /** Marker interface — wrapper screens that delegate to a GraphEditor.Host inner screen.
     *  Used by {@link io.github.y15173334444.create_schematic_compute.blocks.GraphEditor#getActiveHost()}
     *  to unwrap the portable terminal's editor wrapper.
     *
     *  标记接口 — 将操作委托给内部 GraphEditor.Host 的包装屏幕。
     *  由 {@link io.github.y15173334444.create_schematic_compute.blocks.GraphEditor#getActiveHost()}
     *  用于解包便携终端的编辑器包装层，以获取真正的宿主界面。 */
    public interface HostWrapper {
        /** 获取被包装的内部屏幕 / Get the wrapped inner screen. */
        Screen getInnerScreen();
    }

    /** Currently active instance of this screen, used by network packet handlers
     *  to deliver async scan results. Only one terminal screen is open at a time.
     *
     *  此屏幕的当前活动实例，供网络包处理器在异步扫描结果到达时使用。
     *  同一时间只有一个终端屏幕处于打开状态。 */
    private static PortableTerminalScreen activeInstance;

    /** Called by ScanSableResponsePacket on the client thread.
     *  Merges wireless (Sable) device results into the terminal's device list.
     *
     *  由 ScanSableResponsePacket 在客户端线程调用。
     *  将无线（Sable）设备扫描结果合并到终端的设备列表中。 */
    public static void onSableScanResult(List<SablePacketHelper.SableDeviceEntry> results) {
        if (activeInstance != null) activeInstance.mergeSableResults(results);
    }
    /** No-op: settings editing via native UI.
     *  Settings are edited through the device-specific screens opened by
     *  {@link #openBlockUI()}, not through this terminal directly.
     *
     *  空操作：设置通过原生界面编辑。
     *  设置编辑是通过 {@link #openBlockUI()} 打开的各设备专属界面进行的，
     *  而非直接通过本终端。 */
    public static void onSettingsResponse(BlockPos pos, byte[] nbt) {}

    // ── State ──
    // ── 状态字段 ──

    /** The player who opened this terminal / 打开此终端的玩家 */
    private final Player player;
    /** Minecraft client instance for rendering and screen management /
     *  Minecraft 客户端实例，用于渲染和屏幕管理 */
    private final Minecraft mc = Minecraft.getInstance();

    // Scan configuration / 扫描配置
    /** Current scan range in blocks (1–128) / 当前扫描范围（格，1–128） */
    private int scanRange;
    /** Text field widget for entering the scan range / 输入扫描范围的文本框控件 */
    private EditBox rangeInput;
    /** Persisted scan range across screen open/close cycles / 跨屏幕开关周期持久化的扫描范围 */
    private static int savedScanRange = 16;
    // Scroll state / 滚动状态
    /** Current scroll offset (how many items scrolled past) / 当前滚动偏移（已滚过的条目数） */
    private int scrollOff = 0;
    /** Whether the scrollbar thumb is being dragged / 滚动条滑块是否正在被拖拽 */
    private boolean scrollbarDragging = false;
    /** Y-coordinate where the scroll drag started / 滚动拖拽开始时的 Y 坐标 */
    private double scrollDragStartY = 0;
    /** scrollOff value at the start of the drag / 拖拽开始时的 scrollOff 值 */
    private int scrollDragStartOff = 0;

    /** Discovered devices (local + Sable merged) / 已发现的设备（本地 + Sable 合并） */
    private List<DeviceEntry> devices = new ArrayList<>();
    /** Whether a re-scan is needed (triggered by movement or range change) /
     *  是否需要重新扫描（由移动或范围变化触发） */
    private boolean needsRescan = true;
    /** Player's position at the last scan, used to detect movement /
     *  上次扫描时玩家的位置，用于检测移动 */
    private double lastScanX, lastScanY, lastScanZ;
    /** Manhattan-distance-like threshold in blocks before auto-rescan triggers.
     *  Uses sum of absolute deltas (dx+dy+dz) rather than Euclidean distance
     *  for cheaper per-tick computation.
     *
     *  触发自动重新扫描的类曼哈顿距离阈值（格）。
     *  使用绝对差值之和（dx+dy+dz）而非欧几里得距离，以降低每 tick 的计算开销。 */
    private static final double MOVE_THRESHOLD = 3.0;

    // Editing state — populated when the player clicks "Edit" on a device /
    // 编辑状态 — 当玩家点击设备上的"编辑"时填充
    /** Position of the block entity currently being edited /
     *  当前正在编辑的方块实体的位置 */
    private BlockPos editingPos;
    /** Whether the device being edited is a Sable (wireless) device /
     *  正在编辑的设备是否为 Sable（无线）设备 */
    private boolean editingSable = false;
    /** The BlockEntity class of the device being edited /
     *  正在编辑的设备的 BlockEntity 类 */
    private Class<?> editingBeClass;

    /**
     * Immutable record holding metadata about a discovered device.
     *
     * 不可变记录，保存已发现设备的元数据。
     *
     * @param pos          block position in-world / 方块在世界中的坐标
     * @param name         display name (localized) / 显示名称（已本地化）
     * @param beClass      concrete BlockEntity class / 具体的 BlockEntity 类
     * @param sable        whether discovered via Sable network (wireless) /
     *                     是否通过 Sable 网络（无线）发现
     * @param sableDistance distance in meters if Sable, -1 otherwise /
     *                      Sable 设备时表示距离（米），否则为 -1
     * @param subLevelId   sub-level ID for Sable devices (0 = not Sable) /
     *                     Sable 设备的子关卡 ID（0 表示非 Sable）
     */
    private record DeviceEntry(BlockPos pos, String name, Class<?> beClass, boolean sable, float sableDistance, long subLevelId) {
        /** Convenience constructor for local (non-Sable) devices.
         *  本地（非 Sable）设备的便捷构造器。 */
        DeviceEntry(BlockPos pos, String name, Class<?> beClass) {
            this(pos, name, beClass, false, -1, 0);
        }
    }

    /**
     * Constructs a new Portable Terminal screen.
     *
     * 构造一个新的便携终端界面。
     *
     * @param player the player who opened the terminal / 打开终端的玩家
     */
    public PortableTerminalScreen(Player player) {
        super(Component.translatable("gui.create_schematic_compute.terminal.title"));
        this.player = player;
        scanRange = savedScanRange;
        // Capture initial position so movement detection works from frame 0
        // 捕获初始位置，使移动检测从第一帧起生效
        lastScanX = player.getX(); lastScanY = player.getY(); lastScanZ = player.getZ();
    }

    /**
     * Initializes the screen: creates the range input widget, registers this
     * instance as the active terminal, and triggers the initial scan if needed.
     *
     * 初始化界面：创建范围输入控件，将自身注册为活动终端实例，并在需要时触发初次扫描。
     */
    @Override protected void init() {
        super.init();
        activeInstance = this;
        rangeInput = new EditBox(mc.font, 0, 0, 36, 14, Component.literal("R"));
        rangeInput.setValue(String.valueOf(scanRange));
        // Only allow up to 3 digits to keep the value in a valid range (1–128)
        // 只允许最多 3 位数字，使值保持在有效范围（1–128）内
        rangeInput.setFilter(s -> s.matches("\\d{0,3}"));
        addRenderableWidget(rangeInput);
        if (needsRescan) scanNearbyBlocks();
    }

    /**
     * Called when the screen is closed. Persists the current scan range and
     * clears the active instance reference so network handlers don't deliver
     * stale results.
     *
     * 界面关闭时调用。持久化当前扫描范围并清除活动实例引用，
     * 防止网络处理器向已关闭的界面投递过期的结果。
     */
    @Override public void onClose() {
        savedScanRange = scanRange;
        activeInstance = null;
        super.onClose();
    }

    /**
     * Called every client tick. Checks whether the player has moved far enough
     * to warrant a re-scan.
     *
     * 每客户端 tick 调用。检查玩家是否移动了足够远的距离以触发重新扫描。
     */
    @Override public void tick() {
        // Use Manhattan-like sum of deltas for cheap per-tick movement detection
        // 使用曼哈顿式的差值之和，以较低开销进行每 tick 移动检测
        double dx = Math.abs(player.getX() - lastScanX);
        double dy = Math.abs(player.getY() - lastScanY);
        double dz = Math.abs(player.getZ() - lastScanZ);
        if (dx + dy + dz > MOVE_THRESHOLD) needsRescan = true;
        if (needsRescan) {
            scanNearbyBlocks();
            needsRescan = false;
            lastScanX = player.getX(); lastScanY = player.getY(); lastScanZ = player.getZ();
        }
    }

    // ── Scanning ──
    // ── 扫描逻辑 ──

    /**
     * Performs a local block scan within the configured range, then sends a
     * {@link ScanSablePacket} to the server to discover wireless devices.
     *
     * <p>The local scan iterates a cubic volume centered on the player —
     * O(r^3) in chunk-loaded blocks. Results are sorted with Sable devices
     * first (by distance), then local devices by squared distance.
     *
     * 在配置范围内执行本地方块扫描，然后向服务器发送
     * {@link ScanSablePacket} 以发现无线设备。
     *
     * <p>本地扫描遍历以玩家为中心的立方体区域，复杂度为已加载区块的 O(r^3)。
     * 结果按 Sable 设备优先（按距离排序），然后按本地设备的距离平方排序。
     */
    private void scanNearbyBlocks() {
        devices.clear();
        Level level = player.level();
        BlockPos playerPos = player.blockPosition();
        int r = scanRange;
        // Triple-nested loop over a cube of side 2r+1 centered on the player.
        // Vanilla's getBlockEntity is O(1) per call, so this is tolerable for r ≤ 128.
        // 以玩家为中心遍历边长为 2r+1 的立方体。
        // 原版 getBlockEntity 每次调用为 O(1)，所以 r ≤ 128 时性能可接受。
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    // Only GraphBlockEntity subclasses participate in the terminal ecosystem
                    // 只有 GraphBlockEntity 子类参与终端生态系统
                    if (be instanceof GraphBlockEntity) {
                        // Strip formatting codes (§.) from the display name for a clean label
                        // 去除显示名称中的格式化代码（§.），获得干净的标签文字
                        String name = I18n.get(be.getBlockState().getBlock().getDescriptionId()).replaceAll("§.", "");
                        devices.add(new DeviceEntry(p.immutable(), name, be.getClass()));
                    }
                }
        // Fire-and-forget network request for Sable (wireless/cross-dim) devices
        // 发送即发即忘的网络请求，获取 Sable（无线/跨维度）设备
        PacketDistributor.sendToServer(new ScanSablePacket(playerPos, scanRange));
        // Sort: Sable devices first (by distance), local devices by squared distance.
        // Sable distances are synthesized by the server and may be -1 for local entries.
        // 排序：Sable 设备优先（按距离），本地设备按距离平方排序。
        // Sable 距离由服务器合成，本地条目的 sableDistance 为 -1。
        devices.sort(Comparator.comparingDouble(d ->
            d.sable ? d.sableDistance * d.sableDistance : d.pos.distSqr(playerPos)));
    }

    /**
     * Merges Sable network scan results into the device list.
     * Removes all previously known Sable entries, then inserts the fresh results.
     *
     * 将 Sable 网络扫描结果合并到设备列表中。
     * 移除所有之前已知的 Sable 条目，然后插入新的结果。
     *
     * @param results list of Sable device entries from the server /
     *                来自服务器的 Sable 设备条目列表
     */
    private void mergeSableResults(List<SablePacketHelper.SableDeviceEntry> results) {
        // Remove all old Sable entries before inserting fresh ones —
        // the server always sends the complete set, not a delta.
        // 在插入新条目前移除所有旧的 Sable 条目 —
        // 服务器总是发送完整集合，而非增量更新。
        devices.removeIf(d -> d.sable);
        for (var se : results) {
            Class<?> cls = resolveBeClass(se.beClassName());
            if (cls != null) {
                // subLevelId != 0 indicates this is a genuine Sable (wireless) entry
                // subLevelId != 0 表示这是一个真正的 Sable（无线）条目
                boolean isSable = se.subLevelId() != 0;
                devices.add(new DeviceEntry(se.localPos(), se.name(), cls, isSable, se.distance(), se.subLevelId()));
            }
        }
        BlockPos playerPos = player.blockPosition();
        devices.sort(Comparator.comparingDouble(d ->
            d.sable ? d.sableDistance * d.sableDistance : d.pos.distSqr(playerPos)));
    }

    /**
     * Resolves a BlockEntity class name (as sent over the network) to its
     * concrete {@link Class} reference.
     *
     * <p>Sable-specific class names end with "Sable" to distinguish wireless
     * variants; the suffix is stripped before matching.
     *
     * 将通过网络发送的 BlockEntity 类名解析为具体的 {@link Class} 引用。
     *
     * <p>Sable 特定的类名以 "Sable" 结尾以区分无线变体；
     * 匹配前会先去掉此后缀。
     *
     * @param name the class name from the packet / 来自数据包的类名
     * @return the resolved class, or null if unrecognized / 解析后的类，无法识别则返回 null
     */
    private static Class<?> resolveBeClass(String name) {
        // Strip "Sable" suffix for wireless variants so they map to the same BE class
        // 去掉无线变体的 "Sable" 后缀，使其映射到相同的 BE 类
        if (name.endsWith("Sable")) name = name.substring(0, name.length() - 5);
        return switch (name) {
            case "BlueprintBlockEntity"       -> BlueprintBlockEntity.class;
            case "ProgramComputerBlockEntity" -> ProgramComputerBlockEntity.class;
            case "SpeedProxyBlockEntity"      -> SpeedProxyBlockEntity.class;
            case "SensorBlockEntity"          -> SensorBlockEntity.class;
            case "ControlSeatBlockEntity"     -> ControlSeatBlockEntity.class;
            case "MonitorBlockEntity"         -> MonitorBlockEntity.class;
            case "RadarBlockEntity"           -> RadarBlockEntity.class;
            default -> null;
        };
    }

    // ── Rendering ──
    // ── 渲染逻辑 ──

    /**
     * Main render entry point. Delegates to {@link #renderDeviceList} for the
     * full-screen overlay with scrolling device list.
     *
     * 主渲染入口。委托给 {@link #renderDeviceList} 绘制带滚动设备列表的全屏覆盖层。
     *
     * @param g  the GuiGraphics context / GuiGraphics 绘图上下文
     * @param mx mouse X in screen coordinates / 屏幕坐标下的鼠标 X
     * @param my mouse Y in screen coordinates / 屏幕坐标下的鼠标 Y
     * @param pt partial tick delta for smooth animations / 部分 tick 增量，用于平滑动画
     */
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderDeviceList(g, mx, my, pt);
    }

    /**
     * Renders the device list overlay: background, title bar, close button,
     * range input, scrollable device entries with edit buttons, and scrollbar.
     *
     * 渲染设备列表覆盖层：背景、标题栏、关闭按钮、范围输入、可滚动的设备条目及编辑按钮、滚动条。
     */
    private void renderDeviceList(GuiGraphics g, int mx, int my, float pt) {
        int w = width, h = height;
        // Panel is 80% of screen size, centered / 面板占屏幕 80%，居中
        int cw = (int)(w * 0.8), ch = (int)(h * 0.8);
        int cx = (w - cw) / 2, cy = (h - ch) / 2;

        // Semi-transparent backdrop dims the world behind the panel
        // 半透明背景遮罩将面板背后的世界变暗
        g.fill(0, 0, w, h, 0xAA000000);
        // Panel body — dark warm brown / 面板主体 — 深暖棕色
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF2A2822);
        // Panel border — gold-brown outline / 面板边框 — 金棕色轮廓
        g.renderOutline(cx, cy, cw, ch, 0xFF8B7533);

        // Title bar — slightly lighter warm tone / 标题栏 — 稍亮的暖色调
        g.fill(cx + 2, cy + 2, cx + cw - 2, cy + 20, 0xFF4A3F28);
        // Title text — gold bold / 标题文字 — 金色粗体
        g.drawString(mc.font, "§6§l" + title.getString(), cx + 6, cy + 6, 0xFFFFFFFF);

        // Close button (X) — top-right corner of the panel / 关闭按钮 (X) — 面板右上角
        int closeX = cx + cw - 18, closeY = cy + 2;
        g.fill(closeX, closeY, closeX + 16, closeY + 16, 0xFF4A3028);
        g.renderOutline(closeX, closeY, 16, 16, 0xFF8B5333);
        g.drawString(mc.font, "§cX", closeX + 5, closeY + 4, 0xFFFFFFFF);

        // Range input row / 范围输入行
        int tby = cy + 26;
        g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.terminal.range") + ":", cx + 6, tby + 6, 0xFFCCCCCC);
        rangeInput.setX(cx + 40); rangeInput.setY(tby + 3); rangeInput.setWidth(44);
        rangeInput.render(g, mx, my, pt);
        // Parse the text field value each frame — cheap for a 3-char string.
        // If valid and changed, update scanRange and flag a rescan.
        // 每帧解析文本框的值 — 3 个字符的字符串开销很小。
        // 如果有效且已更改，则更新 scanRange 并标记需要重新扫描。
        try { int v = Integer.parseInt(rangeInput.getValue()); if (v >= 1 && v <= 128 && v != scanRange) { scanRange = v; needsRescan = true; } } catch (NumberFormatException ignored) {}

        // Device list area / 设备列表区域
        int listY = tby + 28;
        int listH = ch - 58;
        g.fill(cx + 4, listY, cx + cw - 4, listY + listH, 0xFF1A1814);
        g.renderOutline(cx + 4, listY, cw - 8, listH, 0xFF3A3832);

        if (devices.isEmpty()) {
            // Centered "no devices" message when the list is empty
            // 列表为空时居中显示"无设备"消息
            String msg = I18n.get("gui.create_schematic_compute.terminal.no_devices");
            g.drawString(mc.font, "§7" + msg, cx + (cw - mc.font.width(msg)) / 2, cy + ch / 2, 0xFF888888);
        } else {
            int itemH = 22;
            int visItems = listH / itemH;
            int maxScroll = Math.max(0, devices.size() - visItems);
            // Clamp scroll offset to valid range / 将滚动偏移钳制在有效范围内
            if (scrollOff < 0) scrollOff = 0;
            if (scrollOff > maxScroll) scrollOff = maxScroll;
            for (int i = scrollOff; i < Math.min(devices.size(), scrollOff + visItems); i++) {
                var dev = devices.get(i);
                int ri = i - scrollOff;
                int iy = listY + 2 + ri * itemH;
                // Alternating row background for readability / 交替行背景以提高可读性
                if (ri % 2 == 0) g.fill(cx + 6, iy, cx + cw - 6, iy + itemH, 0xFF222020);
                // Sable devices show purple [Sable] tag and distance; local devices show coordinates
                // Sable 设备显示紫色 [Sable] 标签和距离；本地设备显示坐标
                String label = dev.sable
                    ? "§d[Sable]§r " + dev.name + " §7(" + (int) dev.sableDistance + "m)"
                    : dev.name + " §8" + dev.pos.getX() + ", " + dev.pos.getY() + ", " + dev.pos.getZ();
                g.drawString(mc.font, label, cx + 10, iy + 5, 0xFFCCCCCC);
                // Edit button per row / 每行的编辑按钮
                int eX = cx + cw - 58, eY = iy + 2;
                boolean eHover = mx >= eX && mx <= eX + 50 && my >= eY && my <= eY + 18;
                // Hover highlight — brighter green when mouse is over the button
                // 悬停高亮 — 鼠标悬停时变亮绿色
                g.fill(eX, eY, eX + 50, eY + 18, eHover ? 0xFF4A5A2A : 0xFF3A4A1A);
                g.renderOutline(eX, eY, 50, 18, 0xFF6A8A3A);
                g.drawString(mc.font, eHover ? "§a" + I18n.get("gui.create_schematic_compute.terminal.edit") : "§2" + I18n.get("gui.create_schematic_compute.terminal.edit"), eX + 10, eY + 4, 0xFFFFFFFF);
            }
            // Scrollbar rendering — only visible when content overflows
            // 滚动条渲染 — 仅在内容溢出时可见
            if (maxScroll > 0) {
                int sbX = cx + cw - 8, sbY = listY;
                // Scrollbar track / 滚动条轨道
                g.fill(sbX, sbY, sbX + 6, sbY + listH, 0xFF2A2822);
                // Thumb size proportional to visible fraction, min 12px tall
                // 滑块大小与可见比例成正比，最小 12px 高
                float thumbH = Math.max(12, listH * (float) visItems / devices.size());
                float thumbY = sbY + (float) scrollOff / maxScroll * (listH - thumbH);
                g.fill(sbX + 1, (int) thumbY, sbX + 5, (int)(thumbY + thumbH), 0xFF8B7533);
            }
        }
    }

    // ── Input ──
    // ── 输入处理 ──

    /**
     * Handles left-click: close button, scrollbar thumb drag initiation,
     * and edit button clicks.
     *
     * 处理左键点击：关闭按钮、滚动条滑块拖拽开始、编辑按钮点击。
     *
     * @param mx  mouse X in screen coordinates / 屏幕坐标下的鼠标 X
     * @param my  mouse Y in screen coordinates / 屏幕坐标下的鼠标 Y
     * @param btn mouse button index (0 = left) / 鼠标按键索引（0 = 左键）
     * @return true if the click was consumed / 点击被消费则返回 true
     */
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Only handle left-clicks; pass other buttons to super
        // 只处理左键点击；其他按键传递给父类
        if (btn != 0) return super.mouseClicked(mx, my, btn);
        int cw = (int)(width * 0.8), ch = (int)(height * 0.8);
        int cx = (width - cw) / 2, cy = (height - ch) / 2;

        // Close button hit test / 关闭按钮命中测试
        if (mx >= cx + cw - 18 && mx <= cx + cw - 2 && my >= cy + 2 && my <= cy + 18) {
            onClose(); return true;
        }
        int listY = cy + 54, listH = ch - 58, itemH = 22;
        int visItems = listH / itemH;
        // Scrollbar thumb drag detection
        // Recalculate thumb geometry to perform hit test against it.
        // 滚动条滑块拖拽检测
        // 重新计算滑块几何信息以进行命中测试。
        int maxScroll = Math.max(0, devices.size() - visItems);
        if (maxScroll > 0) {
            int sbX = cx + cw - 8;
            float thumbH = Math.max(12, listH * (float) visItems / devices.size());
            float thumbY = listY + (float) scrollOff / maxScroll * (listH - thumbH);
            if (mx >= sbX && mx <= sbX + 6 && my >= thumbY && my <= thumbY + thumbH) {
                scrollbarDragging = true;
                scrollDragStartY = my;
                scrollDragStartOff = scrollOff;
                return true;
            }
        }
        // Edit button hit test — iterate visible rows / 编辑按钮命中测试 — 遍历可见行
        for (int i = scrollOff; i < Math.min(devices.size(), scrollOff + visItems); i++) {
            int iy = listY + 2 + (i - scrollOff) * itemH;
            int eX = cx + cw - 58;
            if (mx >= eX && mx <= eX + 50 && my >= iy + 2 && my <= iy + 20) {
                requestEdit(devices.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    /**
     * Handles mouse drag. If the scrollbar is being dragged, updates the scroll
     * offset proportionally to the drag distance.
     *
     * 处理鼠标拖拽。如果正在拖拽滚动条，则按拖拽距离成比例更新滚动偏移。
     *
     * @return true if scrollbar drag was handled / 滚动条拖拽被处理则返回 true
     */
    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (scrollbarDragging) {
            int listH = (int)(height * 0.8) - 58;
            int visItems = listH / 22;
            int maxScroll = Math.max(0, devices.size() - visItems);
            if (maxScroll > 0) {
                float thumbH = Math.max(12, listH * (float) visItems / devices.size());
                // Map mouse delta to scroll offset: the fraction of the track the
                // mouse moved times the total scroll range.
                // 将鼠标增量映射到滚动偏移：鼠标在轨道上移动的比例乘以总滚动范围。
                float delta = (float) (my - scrollDragStartY) / (listH - thumbH);
                int newOff = scrollDragStartOff + Math.round(delta * maxScroll);
                if (newOff < 0) newOff = 0;
                if (newOff > maxScroll) newOff = maxScroll;
                scrollOff = newOff;
            }
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    /**
     * Ends scrollbar dragging on mouse release.
     *
     * 鼠标释放时结束滚动条拖拽。
     *
     * @return true if scrollbar drag was active / 滚动条拖拽处于活动状态则返回 true
     */
    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (scrollbarDragging) { scrollbarDragging = false; return true; }
        return super.mouseReleased(mx, my, btn);
    }

    /**
     * Handles mouse wheel scrolling. Each notch scrolls one device row.
     *
     * 处理鼠标滚轮滚动。每格滚轮滚动一行设备条目。
     *
     * @param sx horizontal scroll amount (unused) / 水平滚动量（未使用）
     * @param sy vertical scroll amount — positive = up, negative = down /
     *           垂直滚动量 — 正 = 上滚，负 = 下滚
     * @return always true (scroll always consumed) / 始终返回 true（滚动始终被消费）
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // sy > 0 means scroll up → decrease offset (scroll toward top)
        // sy > 0 表示上滚 → 减小偏移（向顶部滚动）
        scrollOff += (sy > 0) ? -1 : 1;
        return true;
    }

    /**
     * Handles key presses. ESC (key 256) closes the screen.
     *
     * 处理按键。ESC（键码 256）关闭界面。
     *
     * @param key the GLFW key code / GLFW 按键码
     * @return true if the key was handled / 按键被处理则返回 true
     */
    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    // ── Native UI ──
    // ── 原生界面打开逻辑 ──

    /**
     * Records which device the player wants to edit, then opens its native UI.
     *
     * 记录玩家想要编辑的设备，然后打开其原生界面。
     *
     * @param dev the device entry selected by the player / 玩家选择的设备条目
     */
    private void requestEdit(DeviceEntry dev) {
        editingPos = dev.pos;
        editingSable = dev.sable;
        editingBeClass = dev.beClass();
        openBlockUI();
    }

    /**
     * Instantiates the appropriate screen for the selected BlockEntity type
     * and wraps it in a {@link TerminalWrapper} so that closing the inner
     * screen returns the player to this terminal.
     *
     * <p>Each device type has its own screen and menu pair. The simple class
     * name is matched via substring containment so that Sable variants
     * (whose names end with "Sable") still route correctly.
     *
     * 为选中的 BlockEntity 类型实例化对应的界面，
     * 并将其包裹在 {@link TerminalWrapper} 中，
     * 使关闭内部界面后玩家回到此终端。
     *
     * <p>每种设备类型有各自的屏幕/菜单对。通过子串包含来匹配简类名，
     * 使 Sable 变体（类名以 "Sable" 结尾）也能正确路由。
     */
    private void openBlockUI() {
        if (editingBeClass == null || editingPos == null) return;
        String cn = editingBeClass.getSimpleName();
        Screen inner = null;
        // Match by substring so "MonitorSable" still routes to MonitorScreen
        // 通过子串匹配使 "MonitorSable" 仍能路由到 MonitorScreen
        if (cn.contains("Monitor"))       inner = new MonitorScreen(new MonitorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Radar"))    inner = new RadarScreen(new RadarMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Blueprint")) inner = new BlueprintScreen(new BlueprintMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Program"))  inner = new ProgramComputerScreen(new ProgramComputerMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("ControlSeat")) inner = new ControlSeatScreen(new ControlSeatMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Sensor"))   inner = new SensorScreen(new SensorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("SpeedProxy")) inner = new SpeedProxyScreen(editingPos);
        if (inner == null) return;

        // Store reference for re-opening terminal after the inner screen closes
        // 保存引用，以便内部界面关闭后重新打开终端
        final Screen terminalScreen = this;
        final Screen innerScreen = inner;

        mc.setScreen(new TerminalWrapper(inner, terminalScreen));
    }

    /** Wrapper screen that delegates to an inner GraphEditor.Host.
     *  Implements HostWrapper so {@link io.github.y15173334444.create_schematic_compute.blocks.GraphEditor#getActiveHost()}
     *  can find the inner host for collaboration features.
     *
     *  <p>When the player presses ESC or the inner screen closes naturally,
     *  this wrapper restores the terminal screen rather than returning to
     *  the game world.
     *
     *  包装屏幕，将操作委托给内部的 GraphEditor.Host。
     *  实现 HostWrapper 使 {@link io.github.y15173334444.create_schematic_compute.blocks.GraphEditor#getActiveHost()}
     *  能够找到内部宿主以支持协作功能。
     *
     *  <p>当玩家按 ESC 或内部界面自然关闭时，此包装器恢复终端界面而非返回游戏世界。 */
    private class TerminalWrapper extends Screen implements HostWrapper {
        /** The device-specific screen being wrapped / 被包装的设备专属界面 */
        private final Screen inner;
        /** Reference back to the terminal screen for restoration / 反向引用终端界面以用于恢复 */
        private final Screen terminal;

        /**
         * @param inner    the device-specific screen to delegate to / 要委托给的设备专属界面
         * @param terminal the terminal screen to restore on close / 关闭时要恢复的终端界面
         */
        TerminalWrapper(Screen inner, Screen terminal) {
            super(inner.getTitle());
            this.inner = inner;
            this.terminal = terminal;
            this.minecraft = mc;
        }
        @Override protected void init() { inner.init(minecraft, width, height); }
        @Override public void tick() { inner.tick(); }
        @Override public void render(GuiGraphics g, int mx, int my, float pt) { inner.render(g, mx, my, pt); }
        @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) { inner.renderBackground(g, mx, my, pt); }
        @Override public boolean mouseClicked(double mx, double my, int btn) { return inner.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
        @Override public boolean mouseReleased(double mx, double my, int btn) { return inner.mouseReleased(mx, my, btn); }
        @Override public void mouseMoved(double mx, double my) { inner.mouseMoved(mx, my); }
        @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return inner.mouseDragged(mx, my, btn, dx, dy); }
        @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return inner.mouseScrolled(mx, my, sx, sy); }
        @Override public boolean keyPressed(int key, int sc, int mod) {
            // ESC — try inner screen first; if it doesn't handle it, close the wrapper
            // ESC — 先尝试内部界面；如果它不处理，则关闭包装器
            if (key == 256 && inner.keyPressed(key, sc, mod)) return true;
            if (key == 256) { onClose(); return true; }
            return inner.keyPressed(key, sc, mod);
        }
        @Override public boolean keyReleased(int key, int sc, int mod) { return inner.keyReleased(key, sc, mod); }
        @Override public boolean charTyped(char ch, int mod) { return inner.charTyped(ch, mod); }
        /**
         * When the inner screen closes, restore the terminal screen and flag a
         * rescan so the device list reflects any changes made.
         *
         * 当内部界面关闭时，恢复终端界面并标记需要重新扫描，
         * 使设备列表反映出所做的任何更改。
         */
        @Override public void onClose() {
            inner.onClose();
            if (activeInstance != null) {
                needsRescan = true;
                mc.setScreen(terminal);
            }
        }
        /**
         * Called when the screen is removed from the display stack (e.g. window
         * resize triggers a screen rebuild). Restores the terminal screen on the
         * next tick, preserving mouse position.
         *
         * 当屏幕从显示栈中移除时调用（例如窗口大小调整触发了屏幕重建）。
         * 在下一 tick 恢复终端界面，同时保留鼠标位置。
         */
        @Override public void removed() {
            inner.removed();
            if (activeInstance != null) {
                needsRescan = true;
                double mx = mc.mouseHandler.xpos();
                double my = mc.mouseHandler.ypos();
                // Defer screen restoration by one tick — setting screen during
                // removed() can conflict with Minecraft's screen lifecycle.
                // 延迟一 tick 恢复界面 — 在 removed() 中设置屏幕可能与
                // Minecraft 的屏幕生命周期冲突。
                mc.tell(() -> {
                    if (mc.screen == null) {
                        mc.setScreen(activeInstance);
                        // Restore cursor position so mouse doesn't jump
                        // 恢复光标位置，避免鼠标跳动
                        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), mx, my);
                    }
                });
            }
        }
        /** Terminal wrapper does not pause the game / 终端包装器不暂停游戏 */
        @Override public boolean isPauseScreen() { return false; }
        /** Returns the inner screen for GraphEditor unwrapping /
         *  返回内部界面供 GraphEditor 解包 */
        @Override public Screen getInnerScreen() { return inner; }
    }
}
