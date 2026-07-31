package io.github.y15173334444.create_schematic_compute.compat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 编译期 Sable 访问桥 — 专用服务器安全。
 * Compile-time Sable access bridge — dedicated-server safe.
 *
 * <p>为什么需要这个类：{@code SubLevelContainer} 有一个重载方法
 * {@code getContainer(ClientLevel)}，其方法签名引用 {@code net.minecraft.client.multiplayer.ClientLevel}。
 * 对 {@code SubLevelContainer} 做 {@code Class.forName}（即使不初始化）会在专用服务器 dev 环境
 * 触发 NeoForge RuntimeDistCleaner 拦截（"ClientLevel for invalid dist DEDICATED_SERVER"）。
 * 而编译期引用只解析实际调用的重载（{@code getContainer(Level)}），绝不触碰 ClientLevel 重载，
 * 故专用服务器安全（与 {@link SablePoseHelper} 已验证的模式一致）。</p>
 *
 * <p>Why this class exists: {@code SubLevelContainer} has an overloaded
 * {@code getContainer(ClientLevel)} whose signature references
 * {@code net.minecraft.client.multiplayer.ClientLevel}. Any {@code Class.forName} on
 * {@code SubLevelContainer} (even without initialization) trips NeoForge's
 * RuntimeDistCleaner on dedicated-server dev ("ClientLevel for invalid dist
 * DEDICATED_SERVER"). A compile-time reference resolves only the overload actually
 * invoked ({@code getContainer(Level)}), never touching the ClientLevel overload,
 * hence dedicated-server safe (same pattern as the validated {@link SablePoseHelper}).</p>
 */
public final class SableAccess {

    private SableAccess() {}

    /**
     * Compile-time equivalent of {@code SableReflection.getContainer(Level)}.
     * 编译期版 {@code SableReflection.getContainer(Level)}。
     *
     * @param level the overworld level / 主世界
     * @return the SubLevelContainer, or null if unavailable / SubLevelContainer，不可用时为 null
     */
    public static SubLevelContainer getContainer(Level level) {
        if (level == null || level.isClientSide()) return null;
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return null;
        try {
            return SubLevelContainer.getContainer(level);
        } catch (Exception ignored) { return null; }
    }

    /**
     * Compile-time equivalent of {@code SableReflection.getAllSubLevels(Object)}.
     * 编译期版 {@code SableReflection.getAllSubLevels(Object)}。
     *
     * @param container the container from {@link #getContainer} / getContainer 返回的容器
     * @return the list of sub-levels, or empty if unavailable / 子关卡列表，不可用时为空
     */
    @SuppressWarnings("unchecked")
    public static List<SubLevel> getAllSubLevels(SubLevelContainer container) {
        if (container == null) return List.of();
        try {
            return (List<SubLevel>) (List<?>) container.getAllSubLevels();
        } catch (Exception ignored) { return List.of(); }
    }

    /**
     * Compile-time equivalent of {@code SableReflection.getSubLevelLevel(Object)}.
     * 编译期版 {@code SableReflection.getSubLevelLevel(Object)}。
     *
     * @param subLevel a sub-level from {@link #getAllSubLevels} / getAllSubLevels 返回的子关卡
     * @return the sub-level's Level, or null if unavailable / 子关卡对应的 Level，不可用时为 null
     */
    public static Level getSubLevelLevel(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            return subLevel.getLevel();
        } catch (Exception ignored) { return null; }
    }
}
