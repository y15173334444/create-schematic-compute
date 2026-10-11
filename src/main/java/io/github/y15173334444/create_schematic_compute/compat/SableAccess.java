package io.github.y15173334444.create_schematic_compute.compat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 编译期 Sable 容器桥 — 专用服务器安全。
 * Compile-time Sable container bridge — dedicated-server safe.
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
 *
 * <p>P2-19：{@code SableReflection} 反射层退役后，本类是容器访问的唯一入口——
 * 守卫顺序（空参 → ModList → 才触碰 Sable 类）是全部 Sable 访问的统一纪律，
 * 供 {@code SablePacketHelper} 等消费方复用。</p>
 *
 * <p>P2-19: with the reflective {@code SableReflection} layer retired, this class is the
 * single entry point for container access — the guard order (null args → ModList → only
 * then Sable classes) is the shared discipline for all Sable access, reused by
 * {@code SablePacketHelper} and other consumers.</p>
 */
public final class SableAccess {

    private SableAccess() {}

    /**
     * 获取主世界对应的 SubLevelContainer（专用服务器安全：只解析 Level 重载）。
     * Get the SubLevelContainer for an overworld level (dedicated-server safe:
     * only the Level overload is ever resolved).
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
     * 获取容器中的所有子关卡。
     * Get all sub-levels from a container.
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
}
