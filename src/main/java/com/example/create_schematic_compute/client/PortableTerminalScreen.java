package com.example.create_schematic_compute.client;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.*;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayInputStream;
import java.util.*;

public class PortableTerminalScreen extends Screen implements GraphEditor.Host {

    // ── Static callback for packet responses ──
    private static PortableTerminalScreen activeInstance;

    /** Called by ResponseGraphPacket on the client thread */
    public static void onGraphResponse(BlockPos pos, byte[] nbt, int version) {
        if (activeInstance != null) activeInstance.handleGraphResponse(pos, nbt, version);
    }

    /** Called by SaveRejectedPacket on the client thread */
    public static void onSaveRejected(BlockPos pos, int currentVersion) {
        if (activeInstance != null) activeInstance.handleSaveRejected(pos, currentVersion);
    }

    /** Called by ScanSableResponsePacket on the client thread */
    public static void onSableScanResult(List<SablePacketHelper.SableDeviceEntry> results) {
        if (activeInstance != null) activeInstance.mergeSableResults(results);
    }

    /** Called by ResponseSettingsPacket — no-op handler (settings editing via native UI). */
    public static void onSettingsResponse(BlockPos pos, byte[] nbt) {}

    // ── Screen state ──
    private final Player player;
    private final GraphEditor editor;
    private final Minecraft mc = Minecraft.getInstance();

    // Scan state
    private int scanRange;
    private EditBox rangeInput;
    private static int savedScanRange = 16;
    private int scrollOff = 0;
    private List<DeviceEntry> devices = new ArrayList<>();
    private boolean needsRescan = true;
    private double lastScanX, lastScanY, lastScanZ;
    private static final double MOVE_THRESHOLD = 3.0;
    // Edit mode
    private boolean editMode = false;
    private BlockPos editingPos;
    private boolean editingSable = false;
    private Class<?> editingBeClass;
    private NodeGraph remoteGraph;
    private int remoteGraphVersion = -1;
    private boolean remoteRunning;
    private String editingBlockName = "";
    private record DeviceEntry(BlockPos pos, String name, Class<?> beClass, boolean sable, float sableDistance, long subLevelId) {
        DeviceEntry(BlockPos pos, String name, Class<?> beClass) {
            this(pos, name, beClass, false, -1, 0);
        }
        DeviceEntry(BlockPos pos, String name, Class<?> beClass, boolean sable, float sableDistance) {
            this(pos, name, beClass, sable, sableDistance, 0);
        }
    }

    public PortableTerminalScreen(Player player) {
        super(Component.translatable("gui.create_schematic_compute.terminal.title"));
        this.player = player;
        this.editor = new GraphEditor(this, this);
        scanRange = savedScanRange;
        lastScanX = player.getX(); lastScanY = player.getY(); lastScanZ = player.getZ();
    }

    @Override protected void init() {
        super.init();
        activeInstance = this;
        // Create range input box
        rangeInput = new EditBox(mc.font, 0, 0, 36, 14, Component.literal("R"));
        rangeInput.setValue(String.valueOf(scanRange));
        rangeInput.setFilter(s -> s.matches("\\d{0,3}"));
        addRenderableWidget(rangeInput);
        if (needsRescan) scanNearbyBlocks();
    }

    @Override public void onClose() {
        savedScanRange = scanRange;
        activeInstance = null;
        super.onClose();
    }

    @Override public void tick() {
        // Movement-triggered auto-refresh
        double dx = Math.abs(player.getX() - lastScanX);
        double dy = Math.abs(player.getY() - lastScanY);
        double dz = Math.abs(player.getZ() - lastScanZ);
        if (dx + dy + dz > MOVE_THRESHOLD) {
            needsRescan = true;
        }
        if (needsRescan) {
            scanNearbyBlocks();
            needsRescan = false;
            lastScanX = player.getX(); lastScanY = player.getY(); lastScanZ = player.getZ();
        }
    }

    private void scanNearbyBlocks() {
        devices.clear();
        Level level = player.level();
        BlockPos playerPos = player.blockPosition();
        int r = scanRange;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = playerPos.offset(dx, dy, dz);
                    BlockEntity be = level.getBlockEntity(p);
                    if (be instanceof GraphBlockEntity) {
                        String name = I18n.get(be.getBlockState().getBlock().getDescriptionId())
                        .replaceAll("§.", "");
                    devices.add(new DeviceEntry(p.immutable(), name, be.getClass()));
                    }
                }
        // Send server-side Sable scan request
        PacketDistributor.sendToServer(new ScanSablePacket(playerPos, scanRange));
        devices.sort(Comparator.comparingDouble(d ->
            d.sable ? d.sableDistance * d.sableDistance : d.pos.distSqr(playerPos)));
    }

    /** Merge Sable scan results from the server, keeping overworld entries intact. */
    private void mergeSableResults(List<SablePacketHelper.SableDeviceEntry> results) {
        // Remove old Sable entries
        devices.removeIf(d -> d.sable);
        // Map new results to DeviceEntry
        for (var se : results) {
            Class<?> cls = resolveBeClass(se.beClassName());
            if (cls != null) {
                boolean isSable = se.subLevelId() != 0;
                devices.add(new DeviceEntry(se.localPos(), se.name(), cls, isSable, se.distance(), se.subLevelId()));
            }
        }
        BlockPos playerPos = player.blockPosition();
        devices.sort(Comparator.comparingDouble(d ->
            d.sable ? d.sableDistance * d.sableDistance : d.pos.distSqr(playerPos)));
    }

    /** Resolve a BlockEntity simple class name back to a Class reference. */
    private static Class<?> resolveBeClass(String name) {
        // Strip "Sable" suffix from Sable-compat subclasses
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

    // ═══════════════════════════════════════════
    //  Rendering
    // ═══════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (editMode) {
            super.render(g, mx, my, pt);
            editor.renderBg(g, mx, my);
        } else {
            renderDeviceList(g, mx, my, pt);
        }
    }

    private void renderDeviceList(GuiGraphics g, int mx, int my, float pt) {
        int w = width, h = height;
        int cw = (int)(w * 0.8), ch = (int)(h * 0.8);
        int cx = (w - cw) / 2, cy = (h - ch) / 2;

        // Semi-transparent background
        g.fill(0, 0, w, h, 0xAA000000);
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF2A2822);
        g.renderOutline(cx, cy, cw, ch, 0xFF8B7533);

        // Title bar
        g.fill(cx + 2, cy + 2, cx + cw - 2, cy + 20, 0xFF4A3F28);
        g.drawString(mc.font, "§6§l" + title.getString(), cx + 6, cy + 6, 0xFFFFFFFF);

        // Close button
        int closeX = cx + cw - 18, closeY = cy + 2;
        g.fill(closeX, closeY, closeX + 16, closeY + 16, 0xFF4A3028);
        g.renderOutline(closeX, closeY, 16, 16, 0xFF8B5333);
        g.drawString(mc.font, "§cX", closeX + 5, closeY + 4, 0xFFFFFFFF);

        // Toolbar
        int tby = cy + 26;
        g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.terminal.range") + ":", cx + 6, tby + 6, 0xFFCCCCCC);
        rangeInput.setX(cx + 40); rangeInput.setY(tby + 3); rangeInput.setWidth(44);
        rangeInput.render(g, mx, my, pt);

        // Sync EditBox → scanRange (auto-refresh on valid value change)
        try { int v = Integer.parseInt(rangeInput.getValue()); if (v >= 1 && v <= 256 && v != scanRange) { scanRange = v; needsRescan = true; } } catch (NumberFormatException ignored) {}

        // Device list background
        int listY = tby + 28;
        int listH = ch - 58;
        g.fill(cx + 4, listY, cx + cw - 4, listY + listH, 0xFF1A1814);
        g.renderOutline(cx + 4, listY, cw - 8, listH, 0xFF3A3832);

        if (devices.isEmpty()) {
            String msg = I18n.get("gui.create_schematic_compute.terminal.no_devices");
            g.drawString(mc.font, "§7" + msg, cx + (cw - mc.font.width(msg)) / 2, cy + ch / 2, 0xFF888888);
        } else {
            int itemH = 22;
            int visItems = listH / itemH;
            int maxScroll = Math.max(0, devices.size() - visItems);
            if (scrollOff < 0) scrollOff = 0;
            if (scrollOff > maxScroll) scrollOff = maxScroll;
            for (int i = scrollOff; i < Math.min(devices.size(), scrollOff + visItems); i++) {
                var dev = devices.get(i);
                int ri = i - scrollOff;
                int iy = listY + 2 + ri * itemH;
                if (ri % 2 == 0) g.fill(cx + 6, iy, cx + cw - 6, iy + itemH, 0xFF222020);
                String label;
                if (dev.sable)
                    label = "§d[Sable]§r " + dev.name + " §7(" + (int) dev.sableDistance + "m)";
                else
                    label = dev.name + " §8" + dev.pos.getX() + ", " + dev.pos.getY() + ", " + dev.pos.getZ();
                g.drawString(mc.font, label, cx + 10, iy + 5, 0xFFCCCCCC);
                // Edit button
                int eX = cx + cw - 58, eY = iy + 2;
                boolean eHover = mx >= eX && mx <= eX + 50 && my >= eY && my <= eY + 18;
                g.fill(eX, eY, eX + 50, eY + 18, eHover ? 0xFF4A5A2A : 0xFF3A4A1A);
                g.renderOutline(eX, eY, 50, 18, 0xFF6A8A3A);
                g.drawString(mc.font, eHover ? "§a" + I18n.get("gui.create_schematic_compute.terminal.edit") : "§2" + I18n.get("gui.create_schematic_compute.terminal.edit"), eX + 10, eY + 4, 0xFFFFFFFF);
            }
            if (maxScroll > 0) {
                int sbX = cx + cw - 8, sbY = listY;
                g.fill(sbX, sbY, sbX + 6, sbY + listH, 0xFF2A2822);
                float thumbH = Math.max(12, listH * (float) visItems / devices.size());
                float thumbY = sbY + (float) scrollOff / maxScroll * (listH - thumbH);
                g.fill(sbX + 1, (int) thumbY, sbX + 5, (int)(thumbY + thumbH), 0xFF8B7533);
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Input handling
    // ═══════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (editMode) return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn);
        if (btn != 0) return super.mouseClicked(mx, my, btn);

        int w = width, h = height;
        int cw = (int)(w * 0.8), ch = (int)(h * 0.8);
        int cx = (w - cw) / 2, cy = (h - ch) / 2;

        // Close
        if (mx >= cx + cw - 18 && mx <= cx + cw - 2 && my >= cy + 2 && my <= cy + 18) {
            onClose(); return true;
        }
        // Device edit buttons (with scroll offset)
        int listY = cy + 54;
        int listH = ch - 58;
        int itemH = 22;
        int visItems = listH / itemH;
        for (int i = scrollOff; i < Math.min(devices.size(), scrollOff + visItems); i++) {
            int ri = i - scrollOff;
            int iy = listY + 2 + ri * itemH;
            int eX = cx + cw - 58;
            if (mx >= eX && mx <= eX + 50 && my >= iy + 2 && my <= iy + 20) {
                requestEdit(devices.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void requestEdit(DeviceEntry dev) {
        editingPos = dev.pos;
        editingSable = dev.sable;
        editingBeClass = dev.beClass();
        editingBlockName = dev.name;
        openBlockUI();
    }

    /** Open the native block GUI screen — wraps to ensure return to terminal on close. */
    private void openBlockUI() {
        if (editingBeClass == null || editingPos == null) return;
        String cn = editingBeClass.getSimpleName();
        Screen inner = null;
        if (cn.contains("Monitor")) {
            inner = new MonitorScreen(new MonitorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("Radar")) {
            inner = new RadarScreen(new RadarMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("Blueprint")) {
            inner = new BlueprintScreen(new BlueprintMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("Program")) {
            inner = new ProgramComputerScreen(new ProgramComputerMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("ControlSeat")) {
            inner = new ControlSeatScreen(new ControlSeatMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("Sensor")) {
            inner = new SensorScreen(new SensorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        } else if (cn.contains("SpeedProxy")) {
            inner = new SpeedProxyScreen(new SpeedProxyMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        }
        if (inner == null) return;
        final Screen innerScreen = inner;
        final Screen terminalScreen = this;
        // Wrapper that returns to terminal on close
        Screen wrapper = new Screen(inner.getTitle()) {
            { this.minecraft = mc; }
            @Override public void render(GuiGraphics g, int mx, int my, float pt) { innerScreen.render(g, mx, my, pt); }
            @Override public boolean mouseClicked(double mx, double my, int btn) { return innerScreen.mouseClicked(mx, my, btn); }
            @Override public boolean mouseReleased(double mx, double my, int btn) { return innerScreen.mouseReleased(mx, my, btn); }
            @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return innerScreen.mouseDragged(mx, my, btn, dx, dy); }
            @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return innerScreen.mouseScrolled(mx, my, sx, sy); }
            @Override public boolean keyPressed(int key, int sc, int mod) {
                if (key == 256) { onClose(); return true; }
                return innerScreen.keyPressed(key, sc, mod);
            }
            @Override public boolean keyReleased(int key, int sc, int mod) { return innerScreen.keyReleased(key, sc, mod); }
            @Override public boolean charTyped(char ch, int mod) { return innerScreen.charTyped(ch, mod); }
            @Override public void tick() { innerScreen.tick(); }
            @Override protected void init() { innerScreen.init(mc, width, height); }
            @Override public void onClose() {
                innerScreen.removed();
                needsRescan = true;
                mc.setScreen(terminalScreen);
            }
            @Override public void removed() {
                innerScreen.removed();
                // mc.screen is still the wrapper at this point (setScreen sets it to null AFTER removed())
                // Schedule a deferred task to restore the terminal on the next tick
                if (activeInstance != null) {
                    needsRescan = true;
                    mc.tell(() -> { if (mc.screen == null) mc.setScreen(activeInstance); });
                }
            }
        };
        mc.setScreen(wrapper);
    }
    private void applyFilterForBlock() {
        String cn = editingBeClass.getSimpleName();
        if (cn.contains("Blueprint")) {
            editor.setNodeFilter(nt -> nt != NodeType.SPEED_CTRL
                && nt != NodeType.DELAY && nt != NodeType.LATCH && nt != NodeType.T_FLIPFLOP
                && nt != NodeType.PULSE_EXTEND && nt != NodeType.LOOP && nt != NodeType.FUSE
                && nt != NodeType.KEYBOARD && nt != NodeType.MOUSE_JOYSTICK && nt != NodeType.MOUSE_BUTTON
                && nt != NodeType.GAMEPAD_JOYSTICK && nt != NodeType.GAMEPAD_BUTTON && nt != NodeType.GAMEPAD_TRIGGER
                && nt != NodeType.VIEW_ANGLE && nt != NodeType.WORLD_VIEW
                && nt != NodeType.ATTITUDE && nt != NodeType.FORWARD
                && nt != NodeType.ACCELERATION && nt != NodeType.VELOCITY
                && nt != NodeType.POSITION && nt != NodeType.TARGET_OUT
                && nt != NodeType.TEXT && nt != NodeType.DATA
                && nt != NodeType.IMAGE && nt != NodeType.IMAGE_SEQUENCE
                && nt != NodeType.ENCAP_INPUT && nt != NodeType.ENCAP_OUTPUT);
        } else if (cn.contains("Program")) {
            editor.setNodeFilter(nt -> nt == NodeType.CONST
                || nt == NodeType.REDSTONE_IN || nt == NodeType.REDSTONE_OUT
                || nt == NodeType.PRIVATE_IN || nt == NodeType.PRIVATE_OUT
                || nt == NodeType.BUS_IN || nt == NodeType.BUS_OUT
                || nt == NodeType.DELAY || nt == NodeType.LATCH || nt == NodeType.T_FLIPFLOP
                || nt == NodeType.PULSE_EXTEND || nt == NodeType.LOOP || nt == NodeType.FUSE
                || nt == NodeType.BOOL || nt == NodeType.ACCUMULATOR || nt == NodeType.INTEGRATOR
                || nt == NodeType.GATE
                || nt == NodeType.SIN || nt == NodeType.COS || nt == NodeType.TAN
                || nt == NodeType.ASIN || nt == NodeType.ACOS || nt == NodeType.ATAN2
                || nt == NodeType.SINH || nt == NodeType.COSH
                || nt == NodeType.SQRT || nt == NodeType.LN || nt == NodeType.LOG || nt == NodeType.EXP
                || nt == NodeType.SEC || nt == NodeType.CSC || nt == NodeType.COT
                || nt == NodeType.ANGLE_UNWRAP || nt == NodeType.DIRECTION);
        } else if (cn.contains("SpeedProxy")) {
            editor.setNodeFilter(nt -> nt == NodeType.SPEED_CTRL
                || nt == NodeType.PRIVATE_IN || nt == NodeType.BUS_IN);
        } else if (cn.contains("Sensor") || cn.contains("Attitude")) {
            editor.setNodeFilter(nt -> nt == NodeType.ATTITUDE || nt == NodeType.FORWARD
                || nt == NodeType.ACCELERATION || nt == NodeType.VELOCITY
                || nt == NodeType.POSITION || nt == NodeType.BUS_OUT
                || nt == NodeType.REDSTONE_OUT || nt == NodeType.PRIVATE_OUT);
        } else if (cn.contains("ControlSeat")) {
            editor.setNodeFilter(nt -> nt == NodeType.KEYBOARD
                || nt == NodeType.MOUSE_JOYSTICK || nt == NodeType.VIEW_ANGLE
                || nt == NodeType.MOUSE_BUTTON || nt == NodeType.GAMEPAD_JOYSTICK
                || nt == NodeType.GAMEPAD_BUTTON || nt == NodeType.GAMEPAD_TRIGGER
                || nt == NodeType.WORLD_VIEW || nt == NodeType.ATTITUDE
                || nt == NodeType.ACCELERATION || nt == NodeType.VELOCITY || nt == NodeType.POSITION
                || nt == NodeType.BUS_OUT || nt == NodeType.POSE_CONVERT || nt == NodeType.SPLIT
                || nt == NodeType.REDSTONE_OUT || nt == NodeType.PRIVATE_OUT);
        } else if (cn.contains("Radar")) {
            editor.setNodeFilter(nt -> nt == NodeType.TARGET_OUT || nt == NodeType.REDSTONE_OUT
                || nt == NodeType.PRIVATE_OUT || nt == NodeType.BUS_OUT);
        } else if (cn.contains("Monitor")) {
            editor.setNodeFilter(nt -> nt == NodeType.CONST
                || nt == NodeType.REDSTONE_IN || nt == NodeType.PRIVATE_IN || nt == NodeType.BUS_IN
                || nt == NodeType.TEXT || nt == NodeType.DATA
                || nt == NodeType.IMAGE || nt == NodeType.IMAGE_SEQUENCE);
        }
    }

    // ═══════════════════════════════════════════
    //  Packet response handlers (called from static methods)
    // ═══════════════════════════════════════════

    void handleGraphResponse(BlockPos pos, byte[] nbt, int version) {
        if (!pos.equals(editingPos)) return;
        try {
            var tag = NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.create(2 * 1024 * 1024));
            remoteGraph = NodeGraph.load(tag, mc.level.registryAccess());
            remoteGraphVersion = version;
            remoteRunning = false;
            applyFilterForBlock();
            editMode = true;
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Terminal: failed to load remote graph", e);
        }
    }

    void handleSaveRejected(BlockPos pos, int currentVersion) {
        if (!pos.equals(editingPos)) return;
        remoteGraphVersion = currentVersion;
        PacketDistributor.sendToServer(new RequestGraphPacket(pos, editingSable));
    }

    // ═══════════════════════════════════════════
    //  GraphEditor.Host
    // ═══════════════════════════════════════════

    @Override public NodeGraph getGraph() { return remoteGraph != null ? remoteGraph : new NodeGraph(); }
    @Override public boolean isRunning() { return remoteRunning; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { return null; }

    @Override
    public void saveGraph() {
        if (editingPos == null || remoteGraph == null) return;
        try {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("graph", remoteGraph.save(mc.level.registryAccess()));
            var baos = new java.io.ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new SaveGraphPacket(editingPos, baos.toByteArray(), remoteGraphVersion, editingSable));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) { SchematicCompute.LOGGER.error("Terminal save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        if (editingPos != null) {
            PacketDistributor.sendToServer(new BlueprintTogglePacket(editingPos, start, editingSable));
            remoteRunning = start;
        }
    }

    @Override public Screen asScreen() { return this; }

    // ── Input delegation (edit mode) ──
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { if (editMode) editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (editMode) return editor.mouseScrolled(mx, my, sx, sy);
        scrollOff += (sy > 0) ? -1 : 1;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (editMode) {
            // ESC → back to device list (unless editor has an open overlay)
            if (key == 256) {
                if (editor.showMenu || editor.showColorConfig || editor.showExportDialog || editor.showImportDialog) {
                    return editor.keyPressed(key, sc, mod);
                }
                editMode = false; remoteGraph = null; needsRescan = true; return true;
            }
            if (editor.keyPressed(key, sc, mod)) return true;
            if (key >= 32 && key <= 96) return true;
            return true; // consume all other keys in edit mode
        }
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editMode ? editor.keyReleased(key, sc, mod) : super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editMode ? editor.charTyped(ch, mod) : super.charTyped(ch, mod); }
}
