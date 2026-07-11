package io.github.y15173334444.create_schematic_compute.client.colorpicker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Color storage: favorites persist to JSON; recents are session-only (memory).
 *
 * JSON at {@code config/create_schematic_compute/client_colors.json}:
 * {@code {"favorites":["FFD4A017","FF4A8B3F",...]}}
 *
 * Max 24 entries per list. Thread-safe via synchronization.
 */
public final class RecentColors {

    private static final int MAX_FAVORITES = 128;
    private static final int MAX_RECENTS = 16;
    private static final Path CONFIG_PATH =
        Path.of("config", "create_schematic_compute", "client_colors.json");

    /** Default 24-color palette seeded on first run. */
    public static final int[] DEFAULT_FAVORITES = {
        0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFC0C0C0, // Black, White, Gray, Silver
        0xFFFF0000, 0xFF00FF00, 0xFF0000FF,               // Red, Green, Blue
        0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,               // Yellow, Cyan, Magenta
        0xFF800000, 0xFF008000, 0xFF000080,               // Maroon, Dark Green, Navy
        0xFF808000, 0xFF008080, 0xFF800080,               // Olive, Teal, Purple
        0xFFFFA500, 0xFFFFC0CB, 0xFFA52A2A,               // Orange, Pink, Brown
        0xFF4B0082, 0xFF00CED1, 0xFFFFD700,               // Indigo, Turquoise, Gold
        0xFF7FFFD4, 0xFFE6E6FA, 0xFFFF6347,               // Mint, Lavender, Coral
    };

    /** Session-only — never persisted. Newest first. */
    private static final List<Integer> recents = new ArrayList<>();
    /** Persisted to JSON. */
    private static final List<Integer> favorites = new ArrayList<>();

    private RecentColors() {}

    // ── Persistence (favorites only) ──

    public static void load() {
        synchronized (recents) {
            favorites.clear();
            if (!Files.exists(CONFIG_PATH)) return;
            try {
                String content = Files.readString(CONFIG_PATH).trim();
                int keyIdx = content.indexOf("\"favorites\"");
                if (keyIdx < 0) return;
                int start = content.indexOf('[', keyIdx);
                int end = content.indexOf(']', start);
                if (start < 0 || end < 0 || start >= end) return;
                String listStr = content.substring(start + 1, end);
                if (listStr.isBlank()) return;
                for (String token : listStr.split(",")) {
                    token = token.trim().replace("\"", "");
                    if (token.length() == 8) {
                        try {
                            favorites.add((int)(Long.parseLong(token, 16) & 0xFFFFFFFFL));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (IOException e) { favorites.clear(); }
        }
    }

    private static void saveFavorites() {
        synchronized (recents) {
            try {
                Files.createDirectories(CONFIG_PATH.getParent());
                StringBuilder sb = new StringBuilder("{\"favorites\":[");
                for (int i = 0; i < favorites.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(ColorUtils.hex8(favorites.get(i))).append('"');
                }
                sb.append("]}");
                Files.writeString(CONFIG_PATH, sb.toString());
            } catch (IOException e) { /* ignore */ }
        }
    }

    // ── Recent colors (session-only, memory) ──

    public static List<Integer> getRecents() {
        synchronized (recents) {
            return Collections.unmodifiableList(new ArrayList<>(recents));
        }
    }

    public static void addRecent(int argb) {
        synchronized (recents) {
            recents.removeIf(c -> c.equals(argb));
            recents.add(0, argb);
            while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
        }
        // No save — session-only
    }

    // ── Favorite colors (persisted) ──

    public static List<Integer> getFavorites() {
        synchronized (recents) {
            if (favorites.isEmpty()) load();
            if (favorites.isEmpty()) {
                for (int c : DEFAULT_FAVORITES) favorites.add(c);
                saveFavorites();
            }
            return Collections.unmodifiableList(new ArrayList<>(favorites));
        }
    }

    public static boolean addFavorite(int argb) {
        synchronized (recents) {
            favorites.removeIf(c -> c.equals(argb));
            if (favorites.size() >= MAX_FAVORITES) return false;
            favorites.add(0, argb);
        }
        saveFavorites();
        return true;
    }

    public static void removeFavorite(int argb) {
        synchronized (recents) {
            favorites.removeIf(c -> c.equals(argb));
        }
        saveFavorites();
    }

    public static void moveFavorite(int fromIndex, int toIndex) {
        if (fromIndex == toIndex) return;
        synchronized (recents) {
            if (fromIndex < 0 || fromIndex >= favorites.size()) return;
            if (toIndex < 0 || toIndex >= favorites.size()) return;
            int color = favorites.remove(fromIndex);
            favorites.add(toIndex, color);
        }
        saveFavorites();
    }

    /** Remove a favorite by index. Returns the color, or 0 if invalid. */
    public static int removeFavoriteByIndex(int index) {
        synchronized (recents) {
            if (index < 0 || index >= favorites.size()) return 0;
            int color = favorites.remove(index);
            saveFavorites();
            return color;
        }
    }

    /** Insert into favorites at position (with dedup and 128 cap). Returns false if full. */
    public static boolean insertFavorite(int index, int color) {
        synchronized (recents) {
            favorites.removeIf(c -> c.equals(color));
            if (favorites.size() >= MAX_FAVORITES) return false;
            int idx = Math.min(index, favorites.size());
            favorites.add(idx, color);
        }
        saveFavorites();
        return true;
    }

    /** Remove a recent by index. Returns the color, or 0 if invalid. */
    public static int removeRecentByIndex(int index) {
        synchronized (recents) {
            if (index < 0 || index >= recents.size()) return 0;
            return recents.remove(index);
        }
    }

    /** Insert into recents at position (with dedup and 16 cap). */
    public static void insertRecent(int index, int color) {
        synchronized (recents) {
            recents.removeIf(c -> c.equals(color));
            int idx = Math.min(index, recents.size());
            recents.add(idx, color);
            while (recents.size() > MAX_RECENTS) recents.remove(recents.size() - 1);
        }
    }

    /** Move a recent from fromIndex to toIndex. */
    public static void moveRecent(int fromIndex, int toIndex) {
        if (fromIndex == toIndex) return;
        synchronized (recents) {
            if (fromIndex < 0 || fromIndex >= recents.size()) return;
            if (toIndex < 0 || toIndex >= recents.size()) return;
            int color = recents.remove(fromIndex);
            recents.add(toIndex, color);
        }
    }

    public static void resetFavorites() {
        synchronized (recents) {
            favorites.clear();
            for (int c : DEFAULT_FAVORITES) favorites.add(c);
        }
        saveFavorites();
    }

    static { load(); }
}
