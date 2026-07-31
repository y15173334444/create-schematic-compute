package io.github.y15173334444.create_schematic_compute.compat;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Sable API 反射辅助类 / Sable API reflection helper.
 *
 * <p>统一反射入口，供 {@code SablePacketHelper}、{@code SensorBlockEntity}、
 * {@code RadarBlockEntity} 及兼容子类共用。</p>
 *
 * <p>所有 Sable API 访问均通过反射进行，使得在编译期 classpath 中
 * 不包含 Sable 的情况下，模组仍能正常编译和运行。</p>
 *
 * <p>All Sable API access goes through reflection so the mod compiles and runs
 * without Sable on the classpath at compile time.  This is the single init path
 * shared by {@code SablePacketHelper}, {@code SensorBlockEntity},
 * {@code RadarBlockEntity}, and the compat subclasses.</p>
 *
 * @author y15173334444
 */
public final class SableReflection {

    /**
     * 是否已完成初始化 / Whether initialization has been performed.
     * <p>双重检查锁定的标志位，保证 {@link #init()} 只执行一次。</p>
     */
    private static volatile boolean initialized;

    /**
     * Sable 是否在运行时可用 / Whether Sable is available at runtime.
     * <p>在专用服务器上始终为 {@code false}，因为反射访问仅限客户端。</p>
     */
    private static volatile boolean available;

    // ── SubLevelContainer 反射方法 / SubLevelContainer reflective methods ──

    /** SubLevelContainer.getContainer(Level) — 获取容器实例 / get the container instance */
    private static Method containerGetContainer;

    /** SubLevelContainer.getAllSubLevels() — 获取全部子关卡 / get all sub-levels */
    private static Method containerGetAllSubLevels;

    // ── SubLevel 反射方法 / SubLevel reflective methods ──

    /** SubLevel.logicalPose() — 获取逻辑位姿 / get the logical pose */
    private static Method subLevelLogicalPose;

    /** SubLevel.getLevel() — 获取对应的 Level / get the corresponding Level */
    private static Method subLevelGetLevel;

    /** SubLevel.getPlot() — 获取所属地块（可选） / get the owning plot (optional) */
    private static Method subLevelGetPlot;

    /** SubLevel.boundingBox() — 获取包围盒（可选） / get the bounding box (optional) */
    private static Method subLevelBoundingBox;

    // ── Plot 反射方法 / Plot reflective methods ──

    /** Plot.getCenterBlock() — 获取地块中心坐标 / get the plot center block position */
    private static Method plotGetCenterBlock;

    /** Plot.getLoadedChunks() — 获取已加载的区块 / get the loaded chunks */
    private static Method plotGetLoadedChunks;

    /** Plot.getSubLevel() — 获取所属子关卡 / get the owning sub-level */
    private static Method plotGetSubLevel;

    // ── PlotChunkHolder 反射方法 / PlotChunkHolder reflective methods ──

    /** PlotChunkHolder.getChunk() — 获取区块 / get the chunk */
    private static Method plotChunkHolderGetChunk;

    // ── Pose3dc 反射方法 / Pose3dc reflective methods ──

    /** Pose3dc.position() — 获取位置向量 / get the position vector */
    private static Method posePosition;

    /** Pose3dc.orientation() — 获取朝向四元数 / get the orientation quaternion */
    private static Method poseOrientation;

    /**
     * Pose3dc.rotationPoint() — 获取旋转中心点（可能不存在）
     * / get the rotation point (may not exist).
     * <p>旧版 Sable 无此方法，此时设为 {@code null}。</p>
     */
    private static Method poseRotationPoint;

    // ── Vec3dc 反射方法 / Vec3dc reflective methods ──

    /** Vector3dc.x() — 获取 X 分量 / get the X component */
    private static Method vecX;
    /** Vector3dc.y() — 获取 Y 分量 / get the Y component */
    private static Method vecY;
    /** Vector3dc.z() — 获取 Z 分量 / get the Z component */
    private static Method vecZ;

    // ── Quaterniondc 反射方法 / Quaterniondc reflective methods ──

    /** Quaterniondc.x() — 获取四元数 X 分量 / get quaternion X component */
    private static Method quatX;
    /** Quaterniondc.y() — 获取四元数 Y 分量 / get quaternion Y component */
    private static Method quatY;
    /** Quaterniondc.z() — 获取四元数 Z 分量 / get quaternion Z component */
    private static Method quatZ;
    /** Quaterniondc.w() — 获取四元数 W 分量 / get quaternion W component */
    private static Method quatW;

    /**
     * 私有构造器，防止实例化 / Private constructor to prevent instantiation.
     * <p>工具类不应被实例化。</p>
     */
    private SableReflection() {}

    /**
     * 初始化所有反射句柄 / Initialize all reflection handles.
     *
     * <p>采用分阶段加载策略：先加载核心必需类（SubLevelContainer、SubLevel），
     * 再按 best-effort 原则加载可选类（Pose3dc、Plot、PlotChunkHolder）。
     * 这样在专用服务器上可选类加载失败时不会影响核心功能。</p>
     *
     * <p>Uses a phased loading strategy: core required classes first
     * (SubLevelContainer, SubLevel), then optional classes on a best-effort
     * basis (Pose3dc, Plot, PlotChunkHolder).  This ensures optional-class
     * failures on dedicated servers don't break core functionality.</p>
     *
     * <p>线程安全：通过 {@code initialized} 标志 + volatile 保证
     * 双重检查锁定语义，多线程并发调用 {@code init()} 也只会初始化一次。</p>
     */
    private static void init() {
        if (initialized) return;  // 已初始化则直接返回 / already initialized, bail out
        initialized = true;        // 先置标志，防止重复进入 / set flag first to prevent re-entry

        // 注意：不再按专用服务器硬禁用。javap 验证反射链（SubLevelContainer、
        // SubLevel、Pose3dc、Vector3dc、Quaterniondc）全部服务端安全——
        // Pose3dc 是纯 JOML 接口，不引用 ClientLevel。旧代码（9c46fb9 之前）
        // 无此守卫且专用服务器上正常工作；9c46fb9 引入的硬禁用在专用服务器 +
        // Sable 环境下使终端无线设备扫描永久为空（回归审计 #4）。
        // Note: no longer hard-disable on dedicated servers. javap confirms the
        // reflection chain (SubLevelContainer, SubLevel, Pose3dc, Vector3dc,
        // Quaterniondc) is fully server-safe — Pose3dc is a pure JOML interface,
        // no ClientLevel references. Pre-9c46fb9 code had no such guard and
        // worked on dedicated servers (regression audit #4).
        // 若某个可选类在专用服务器缺失，各阶段 best-effort 加载会安全降级为 null。

        // ── 第一阶段：核心类（必须成功，否则整体不可用） ──
        // ── Phase 1: core classes — must succeed or the whole thing is unavailable ──
        try {
            var containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            containerGetContainer = containerClass.getMethod("getContainer", Level.class);
            containerGetAllSubLevels = containerClass.getMethod("getAllSubLevels");

            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            subLevelLogicalPose = subLevelClass.getMethod("logicalPose");
            subLevelGetLevel = subLevelClass.getMethod("getLevel");

            available = true;  // 核心就绪 / core is ready — pose/vec/quat loaded below best-effort
            SchematicCompute.LOGGER.info("SableReflection: core initialized OK");
        } catch (Exception e) {
            // 核心类加载失败则整体标记不可用，无需继续
            // If core classes fail, mark unavailable and stop — nothing else can work
            SchematicCompute.LOGGER.warn("SableReflection: core init failed — {}", e.toString());
            return;
        }

        // ── 第二阶段：位姿/向量/四元数（best-effort，服务器上可能失败） ──
        // ── Phase 2: Pose3dc / Vec3dc / Quaterniondc — best-effort, may fail on server dist ──
        try {
            var poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            posePosition = poseClass.getMethod("position");
            poseOrientation = poseClass.getMethod("orientation");

            // rotationPoint 是后续版本新增的方法，旧版可不存在
            // rotationPoint was added in a later version; may not exist in older Sable
            try { poseRotationPoint = poseClass.getMethod("rotationPoint"); }
            catch (NoSuchMethodException e) { poseRotationPoint = null; }

            var vecClass = Class.forName("org.joml.Vector3dc");
            vecX = vecClass.getMethod("x");
            vecY = vecClass.getMethod("y");
            vecZ = vecClass.getMethod("z");

            var quatClass = Class.forName("org.joml.Quaterniondc");
            quatX = quatClass.getMethod("x");
            quatY = quatClass.getMethod("y");
            quatZ = quatClass.getMethod("z");
            quatW = quatClass.getMethod("w");

            SchematicCompute.LOGGER.info("SableReflection: pose/vec/quat initialized OK");
        } catch (Exception e) {
            // 以下可选组件也不能用了，直接返回
            // Optional parts below also won't work, so stop here
            SchematicCompute.LOGGER.warn("SableReflection: core init failed — {}", e.toString());
            return;
        }

        // ── 第三阶段：Plot（基于区块的子关卡查找，best-effort） ──
        // ── Phase 3: Plot (chunk-based sub-level lookup) — best-effort, may fail on server dist ──
        try {
            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");

            // getPlot 和 boundingBox 在某些 Sable 版本中不存在
            // getPlot and boundingBox may not exist in some Sable versions
            try { subLevelGetPlot = subLevelClass.getMethod("getPlot"); }
            catch (NoSuchMethodException e) { subLevelGetPlot = null; }
            try { subLevelBoundingBox = subLevelClass.getMethod("boundingBox"); }
            catch (NoSuchMethodException e) { subLevelBoundingBox = null; }

            // 只有 getPlot 成功才继续加载 Plot 上的方法
            // Only proceed to load Plot methods if getPlot was found
            if (subLevelGetPlot != null) {
                var plotClass = subLevelGetPlot.getReturnType();
                try { plotGetCenterBlock = plotClass.getMethod("getCenterBlock"); }
                catch (NoSuchMethodException e) { plotGetCenterBlock = null; }
                try { plotGetLoadedChunks = plotClass.getMethod("getLoadedChunks"); }
                catch (NoSuchMethodException e) { plotGetLoadedChunks = null; }
                try { plotGetSubLevel = plotClass.getMethod("getSubLevel"); }
                catch (NoSuchMethodException e) { plotGetSubLevel = null; }
            }
        } catch (Exception e) {
            // Plot 相关类加载失败是安全的，仅影响基于地块的查询路径
            // Plot-related class loading failure is safe — only affects plot-based query paths
            SchematicCompute.LOGGER.debug("SableReflection: plot/chunk classes unavailable (server-safe) — {}", e.toString());
        }

        // ── 第四阶段：PlotChunkHolder（best-effort） ──
        // ── Phase 4: PlotChunkHolder — best-effort, may fail on server dist ──
        try {
            var holderClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
            plotChunkHolderGetChunk = holderClass.getMethod("getChunk");
        } catch (Exception e) {
            // PlotChunkHolder 加载失败是安全的，仅影响区块持有者查询
            // PlotChunkHolder failure is safe — only affects chunk-holder queries
            SchematicCompute.LOGGER.debug("SableReflection: PlotChunkHolder unavailable (server-safe) — {}", e.toString());
        }
    }

    // ── 公共访问接口 / Public accessors ──

    /**
     * 查询 Sable 是否在运行时可用 / Check whether Sable is available at runtime.
     *
     * <p>调用方应在使用本类其他方法前先检查此返回值。
     * Callers should check this before using other methods in this class.</p>
     *
     * @return {@code true} 当 Sable 反射初始化成功 / if Sable reflection initialized successfully
     */
    public static boolean isAvailable() { init(); return available; }

    /**
     * 获取主世界对应的 SubLevelContainer / Get the SubLevelContainer for an overworld level.
     *
     * @param level 目标主世界 / the target overworld level
     * @return 容器对象，失败时返回 {@code null} / the container object, or {@code null} on failure
     */
    public static Object getContainer(Level level) {
        init();
        if (!available || containerGetContainer == null) return null;
        try { return containerGetContainer.invoke(null, level); }
        catch (Exception e) { return null; }
    }

    /**
     * 获取容器中的所有子关卡 / Get all sub-levels from a container.
     *
     * @param container SubLevelContainer 实例 / the SubLevelContainer instance
     * @return 子关卡列表，失败时返回空列表 / list of sub-levels, or an empty list on failure
     */
    @SuppressWarnings("unchecked")
    public static List<Object> getAllSubLevels(Object container) {
        if (!available || container == null || containerGetAllSubLevels == null) return List.of();
        try { return (List<Object>) containerGetAllSubLevels.invoke(container); }
        catch (Exception e) { return List.of(); }
    }

    /**
     * 获取子关卡的逻辑位姿 / Get the logical pose of a sub-level.
     *
     * @param subLevel 子关卡实例 / the sub-level instance
     * @return Pose3dc 位姿对象，失败时返回 {@code null} / the Pose3dc object, or {@code null} on failure
     */
    public static Object getLogicalPose(Object subLevel) {
        if (!available || subLevel == null) return null;
        try { return subLevelLogicalPose.invoke(subLevel); }
        catch (Exception e) { return null; }
    }

    /**
     * 从子关卡中获取对应的 Level 对象 / Get the Level from a sub-level.
     *
     * @param subLevel 子关卡实例 / the sub-level instance
     * @return Level 对象，失败时返回 {@code null} / the Level object, or {@code null} on failure
     */
    public static Level getSubLevelLevel(Object subLevel) {
        if (!available || subLevel == null) return null;
        try { return (Level) subLevelGetLevel.invoke(subLevel); }
        catch (Exception e) { return null; }
    }

    /**
     * 获取子关卡对应的地块（用于基于区块的查询） / Get the plot for this sub-level (for chunk-based queries).
     *
     * @param subLevel 子关卡实例 / the sub-level instance
     * @return Plot 对象，不可用时返回 {@code null} / the Plot object, or {@code null} if unavailable
     */
    public static Object getPlot(Object subLevel) {
        if (!available || subLevel == null || subLevelGetPlot == null) return null;
        try { return subLevelGetPlot.invoke(subLevel); }
        catch (Exception e) { return null; }
    }

    /**
     * 获取子关卡的包围盒 / Get the bounding box from a sub-level.
     *
     * @param subLevel 子关卡实例 / the sub-level instance
     * @return 包围盒对象，不可用时返回 {@code null} / the bounding box object, or {@code null} if unavailable
     */
    public static Object getBoundingBox(Object subLevel) {
        if (!available || subLevel == null || subLevelBoundingBox == null) return null;
        try { return subLevelBoundingBox.invoke(subLevel); }
        catch (Exception e) { return null; }
    }

    /**
     * 获取地块的中心方块坐标 / Get the center block position from a plot.
     *
     * @param plot 地块实例 / the plot instance
     * @return 中心坐标，不可用时返回 {@code null} / the center BlockPos, or {@code null} if unavailable
     */
    public static BlockPos getPlotCenterBlock(Object plot) {
        if (!available || plot == null || plotGetCenterBlock == null) return null;
        try { return (BlockPos) plotGetCenterBlock.invoke(plot); }
        catch (Exception e) { return null; }
    }

    /**
     * 获取地块中已加载的区块集合 / Get the loaded chunks from a plot.
     *
     * @param plot 地块实例 / the plot instance
     * @return PlotChunkHolder 集合，不可用时返回空列表 / collection of PlotChunkHolder, or empty list if unavailable
     */
    @SuppressWarnings("unchecked")
    public static java.util.Collection<Object> getPlotLoadedChunks(Object plot) {
        if (!available || plot == null || plotGetLoadedChunks == null) return java.util.List.of();
        try { return (java.util.Collection<Object>) plotGetLoadedChunks.invoke(plot); }
        catch (Exception e) { return java.util.List.of(); }
    }

    /**
     * 从地块中获取对应的子关卡 / Get the SubLevel from a plot.
     *
     * @param plot 地块实例 / the plot instance
     * @return SubLevel 实例，不可用时返回 {@code null} / the SubLevel, or {@code null} if unavailable
     */
    public static Object getPlotSubLevel(Object plot) {
        if (!available || plot == null || plotGetSubLevel == null) return null;
        try { return plotGetSubLevel.invoke(plot); }
        catch (Exception e) { return null; }
    }

    /**
     * 从 PlotChunkHolder 中获取区块对象 / Get the chunk from a PlotChunkHolder.
     *
     * @param holder PlotChunkHolder 实例 / the PlotChunkHolder instance
     * @return 区块对象，不可用时返回 {@code null} / the chunk object, or {@code null} if unavailable
     */
    public static Object getChunkFromHolder(Object holder) {
        if (!available || holder == null || plotChunkHolderGetChunk == null) return null;
        try { return plotChunkHolderGetChunk.invoke(holder); }
        catch (Exception e) { return null; }
    }

    // ── 位姿提取 / Pose extraction ──

    /**
     * 从逻辑位姿中提取世界坐标 (x, y, z) / Extract world position (x, y, z) from a logical pose.
     *
     * @param pose Pose3dc 位姿对象 / the Pose3dc pose object
     * @return 包含 [x, y, z] 的数组，失败时返回 {@code null} / array of [x, y, z], or {@code null} on failure
     */
    public static double[] extractPosition(Object pose) {
        if (pose == null) return null;
        try {
            // 先获取位置向量，再分别提取三个分量
            // Get the position vector, then extract each component individually
            var pos = posePosition.invoke(pose);
            return new double[]{
                (double) vecX.invoke(pos),
                (double) vecY.invoke(pos),
                (double) vecZ.invoke(pos)
            };
        } catch (Exception e) { return null; }
    }

    /**
     * 从逻辑位姿中提取朝向四元数 (qx, qy, qz, qw) / Extract world orientation quaternion (qx, qy, qz, qw) from a logical pose.
     *
     * @param pose Pose3dc 位姿对象 / the Pose3dc pose object
     * @return 包含 [qx, qy, qz, qw] 的数组，失败时返回 {@code null} / array of [qx, qy, qz, qw], or {@code null} on failure
     */
    public static double[] extractOrientation(Object pose) {
        if (pose == null) return null;
        try {
            // 先获取朝向四元数，再分别提取四个分量
            // Get the orientation quaternion, then extract each component individually
            var oq = poseOrientation.invoke(pose);
            return new double[]{
                (double) quatX.invoke(oq),
                (double) quatY.invoke(oq),
                (double) quatZ.invoke(oq),
                (double) quatW.invoke(oq)
            };
        } catch (Exception e) { return null; }
    }

    /**
     * 从逻辑位姿中提取旋转中心点 (x, y, z) / Extract rotation point (x, y, z) from a logical pose.
     *
     * <p>旧版 Sable 不支持旋转中心点，此时返回 {@code null}。
     * Older Sable versions don't support rotation point; returns {@code null} in that case.</p>
     *
     * @param pose Pose3dc 位姿对象 / the Pose3dc pose object
     * @return 包含 [rx, ry, rz] 的数组，不可用时返回 {@code null} / array of [rx, ry, rz], or {@code null} if unavailable
     */
    public static double[] extractRotationPoint(Object pose) {
        if (pose == null || poseRotationPoint == null) return null;
        try {
            var rp = poseRotationPoint.invoke(pose);
            if (rp == null) return null;  // 方法存在但返回 null / method exists but returned null
            return new double[]{
                (double) vecX.invoke(rp),
                (double) vecY.invoke(rp),
                (double) vecZ.invoke(rp)
            };
        } catch (Exception e) { return null; }
    }

    /**
     * 从逻辑位姿中提取完整变换 / Extract the full transform from a logical pose.
     *
     * <p>返回数组格式：{@code [ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw]}，
     * 其中旋转中心点可能为 {@code null}（→ 使用 {0, 0, 0} 代替）。
     * Return format: {@code [ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw]},
     * where the rotation point may be null (→ falls back to {0, 0, 0}).</p>
     *
     * @param pose Pose3dc 位姿对象 / the Pose3dc pose object
     * @return 包含 10 个元素的数组 [ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw]，失败时返回 {@code null}
     *         / 10-element array [ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw], or {@code null} on failure
     */
    public static double[] extractFullTransform(Object pose) {
        if (pose == null) return null;
        try {
            double[] pos = extractPosition(pose);
            double[] orient = extractOrientation(pose);
            double[] rp = extractRotationPoint(pose);
            if (pos == null || orient == null) return null;
            return new double[]{
                pos[0], pos[1], pos[2],
                // 旋转中心不存在时回退为零向量，保证变换仍然可用
                // Fall back to zero vector when rotation point is absent, so the transform remains usable
                rp != null ? rp[0] : 0, rp != null ? rp[1] : 0, rp != null ? rp[2] : 0,
                orient[0], orient[1], orient[2], orient[3]
            };
        } catch (Exception e) { return null; }
    }

    // ── 包围盒访问器（反射调用 Sable 自定义 BBox 类型） ──
    // ── Bounding box accessors (reflective — Sable BBox is a custom type) ──

    /**
     * 获取包围盒 X 轴最小值 / Get the minimum X of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return X 轴最小值，失败时返回 0 / minimum X, or 0 on failure
     */
    public static double getBBoxMinX(Object bb) {
        try { return (double) bb.getClass().getMethod("minX").invoke(bb); }
        catch (Exception e) { return 0; }
    }

    /**
     * 获取包围盒 X 轴最大值 / Get the maximum X of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return X 轴最大值，失败时返回 0 / maximum X, or 0 on failure
     */
    public static double getBBoxMaxX(Object bb) {
        try { return (double) bb.getClass().getMethod("maxX").invoke(bb); }
        catch (Exception e) { return 0; }
    }

    /**
     * 获取包围盒 Y 轴最小值 / Get the minimum Y of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return Y 轴最小值，失败时返回 0 / minimum Y, or 0 on failure
     */
    public static double getBBoxMinY(Object bb) {
        try { return (double) bb.getClass().getMethod("minY").invoke(bb); }
        catch (Exception e) { return 0; }
    }

    /**
     * 获取包围盒 Y 轴最大值 / Get the maximum Y of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return Y 轴最大值，失败时返回 0 / maximum Y, or 0 on failure
     */
    public static double getBBoxMaxY(Object bb) {
        try { return (double) bb.getClass().getMethod("maxY").invoke(bb); }
        catch (Exception e) { return 0; }
    }

    /**
     * 获取包围盒 Z 轴最小值 / Get the minimum Z of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return Z 轴最小值，失败时返回 0 / minimum Z, or 0 on failure
     */
    public static double getBBoxMinZ(Object bb) {
        try { return (double) bb.getClass().getMethod("minZ").invoke(bb); }
        catch (Exception e) { return 0; }
    }

    /**
     * 获取包围盒 Z 轴最大值 / Get the maximum Z of a bounding box.
     *
     * @param bb Sable 包围盒对象 / the Sable bounding box object
     * @return Z 轴最大值，失败时返回 0 / maximum Z, or 0 on failure
     */
    public static double getBBoxMaxZ(Object bb) {
        try { return (double) bb.getClass().getMethod("maxZ").invoke(bb); }
        catch (Exception e) { return 0; }
    }
}
