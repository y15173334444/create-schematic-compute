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

public class PortableTerminalScreen extends Screen {

    private static PortableTerminalScreen activeInstance;

    /** Called by ScanSableResponsePacket on the client thread */
    public static void onSableScanResult(List<SablePacketHelper.SableDeviceEntry> results) {
        if (activeInstance != null) activeInstance.mergeSableResults(results);
    }
    /** No-op: settings editing via native UI. */
    public static void onSettingsResponse(BlockPos pos, byte[] nbt) {}

    // ── State ──
    private final Player player;
    private final Minecraft mc = Minecraft.getInstance();

    private int scanRange;
    private EditBox rangeInput;
    private static int savedScanRange = 16;
    private int scrollOff = 0;
    private List<DeviceEntry> devices = new ArrayList<>();
    private boolean needsRescan = true;
    private double lastScanX, lastScanY, lastScanZ;
    private static final double MOVE_THRESHOLD = 3.0;

    private BlockPos editingPos;
    private boolean editingSable = false;
    private Class<?> editingBeClass;

    private record DeviceEntry(BlockPos pos, String name, Class<?> beClass, boolean sable, float sableDistance, long subLevelId) {
        DeviceEntry(BlockPos pos, String name, Class<?> beClass) {
            this(pos, name, beClass, false, -1, 0);
        }
    }

    public PortableTerminalScreen(Player player) {
        super(Component.translatable("gui.create_schematic_compute.terminal.title"));
        this.player = player;
        scanRange = savedScanRange;
        lastScanX = player.getX(); lastScanY = player.getY(); lastScanZ = player.getZ();
    }

    @Override protected void init() {
        super.init();
        activeInstance = this;
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
                        String name = I18n.get(be.getBlockState().getBlock().getDescriptionId()).replaceAll("§.", "");
                        devices.add(new DeviceEntry(p.immutable(), name, be.getClass()));
                    }
                }
        PacketDistributor.sendToServer(new ScanSablePacket(playerPos, scanRange));
        devices.sort(Comparator.comparingDouble(d ->
            d.sable ? d.sableDistance * d.sableDistance : d.pos.distSqr(playerPos)));
    }

    private void mergeSableResults(List<SablePacketHelper.SableDeviceEntry> results) {
        devices.removeIf(d -> d.sable);
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

    private static Class<?> resolveBeClass(String name) {
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

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderDeviceList(g, mx, my, pt);
    }

    private void renderDeviceList(GuiGraphics g, int mx, int my, float pt) {
        int w = width, h = height;
        int cw = (int)(w * 0.8), ch = (int)(h * 0.8);
        int cx = (w - cw) / 2, cy = (h - ch) / 2;

        g.fill(0, 0, w, h, 0xAA000000);
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF2A2822);
        g.renderOutline(cx, cy, cw, ch, 0xFF8B7533);

        g.fill(cx + 2, cy + 2, cx + cw - 2, cy + 20, 0xFF4A3F28);
        g.drawString(mc.font, "§6§l" + title.getString(), cx + 6, cy + 6, 0xFFFFFFFF);

        int closeX = cx + cw - 18, closeY = cy + 2;
        g.fill(closeX, closeY, closeX + 16, closeY + 16, 0xFF4A3028);
        g.renderOutline(closeX, closeY, 16, 16, 0xFF8B5333);
        g.drawString(mc.font, "§cX", closeX + 5, closeY + 4, 0xFFFFFFFF);

        int tby = cy + 26;
        g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.terminal.range") + ":", cx + 6, tby + 6, 0xFFCCCCCC);
        rangeInput.setX(cx + 40); rangeInput.setY(tby + 3); rangeInput.setWidth(44);
        rangeInput.render(g, mx, my, pt);
        try { int v = Integer.parseInt(rangeInput.getValue()); if (v >= 1 && v <= 256 && v != scanRange) { scanRange = v; needsRescan = true; } } catch (NumberFormatException ignored) {}

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
                String label = dev.sable
                    ? "§d[Sable]§r " + dev.name + " §7(" + (int) dev.sableDistance + "m)"
                    : dev.name + " §8" + dev.pos.getX() + ", " + dev.pos.getY() + ", " + dev.pos.getZ();
                g.drawString(mc.font, label, cx + 10, iy + 5, 0xFFCCCCCC);
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

    // ── Input ──

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return super.mouseClicked(mx, my, btn);
        int cw = (int)(width * 0.8), ch = (int)(height * 0.8);
        int cx = (width - cw) / 2, cy = (height - ch) / 2;

        if (mx >= cx + cw - 18 && mx <= cx + cw - 2 && my >= cy + 2 && my <= cy + 18) {
            onClose(); return true;
        }
        int listY = cy + 54, listH = ch - 58, itemH = 22;
        int visItems = listH / itemH;
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

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollOff += (sy > 0) ? -1 : 1;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    // ── Native UI ──

    private void requestEdit(DeviceEntry dev) {
        editingPos = dev.pos;
        editingSable = dev.sable;
        editingBeClass = dev.beClass();
        openBlockUI();
    }

    private void openBlockUI() {
        if (editingBeClass == null || editingPos == null) return;
        String cn = editingBeClass.getSimpleName();
        Screen inner = null;
        if (cn.contains("Monitor"))       inner = new MonitorScreen(new MonitorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Radar"))    inner = new RadarScreen(new RadarMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Blueprint")) inner = new BlueprintScreen(new BlueprintMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Program"))  inner = new ProgramComputerScreen(new ProgramComputerMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("ControlSeat")) inner = new ControlSeatScreen(new ControlSeatMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("Sensor"))   inner = new SensorScreen(new SensorMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        else if (cn.contains("SpeedProxy")) inner = new SpeedProxyScreen(new SpeedProxyMenu(0, editingPos), mc.player.getInventory(), Component.empty());
        if (inner == null) return;

        final Screen innerScreen = inner;
        final Screen terminalScreen = this;
        Screen wrapper = new Screen(inner.getTitle()) {
            { this.minecraft = mc; }
            @Override public void render(GuiGraphics g, int mx, int my, float pt) { innerScreen.render(g, mx, my, pt); }
            @Override public boolean mouseClicked(double mx, double my, int btn) { return innerScreen.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
            @Override public boolean mouseReleased(double mx, double my, int btn) { return innerScreen.mouseReleased(mx, my, btn); }
            @Override public void mouseMoved(double mx, double my) { innerScreen.mouseMoved(mx, my); }
            @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return innerScreen.mouseDragged(mx, my, btn, dx, dy); }
            @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return innerScreen.mouseScrolled(mx, my, sx, sy); }
            @Override public boolean keyPressed(int key, int sc, int mod) {
                // Let inner screen handle Esc first (e.g., pixel editor, settings panel)
                if (key == 256 && innerScreen.keyPressed(key, sc, mod)) return true;
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
                if (activeInstance != null) {
                    needsRescan = true;
                    // Save mouse pos before returning to game, restore after switching back
                    double mx = mc.mouseHandler.xpos();
                    double my = mc.mouseHandler.ypos();
                    mc.tell(() -> {
                        if (mc.screen == null) {
                            mc.setScreen(activeInstance);
                            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), mx, my);
                        }
                    });
                }
            }
        };
        mc.setScreen(wrapper);
    }
}
