package io.github.y15173334444.create_schematic_compute.graph;

import java.util.EnumSet;

/**
 * 10 个方块节点图的准入名单（分类白名单 + 类内黑名单）。
 * Per-block node allowances for the ten graph editors.
 *
 * <p>与 docs/node-category-allowance-plan.md §3 表一一对应。子图过滤器
 * （{@code GraphEditor} 内的 ENCAP_INPUT/ENCAP_OUTPUT）不在此处，由编辑器自理。</p>
 * <p>Matches the §3 table in docs/node-category-allowance-plan.md. The sub-graph
 * filter (ENCAP_INPUT/ENCAP_OUTPUT in {@code GraphEditor}) stays separate.</p>
 */
public final class BlockNodeAllowances {

    private BlockNodeAllowances() {}

    /** 蓝图计算机：主图；不含显示器与动力宿主节点。
     *  齿轮箱/变速器专属（MOVE 系、TX_OUT）仅数控齿轮箱/变速器图，见 docs/node-guide.md。
     *  含 {@code input_pose}：POSE_CONVERT / SPLIT 本就在蓝图可用，归类搬家后保留。
     *  Blueprint computer: main graph; no display / kinetic-host nodes. Gearbox/transmission
     *  nodes (MOVE family, TX_OUT) stay in their own hosts (docs/node-guide.md). Includes
     *  {@code input_pose} so POSE_CONVERT / SPLIT survive the category move. */
    public static final NodeAllowance BLUEPRINT = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.MATH_BASIC, NodeCategory.MATH_ADVANCED,
            NodeCategory.TRIG, NodeCategory.LOGIC, NodeCategory.CONTROL,
            NodeCategory.OUTPUT, NodeCategory.SEQUENTIAL_ACC, NodeCategory.DEBUG,
            NodeCategory.STRUCTURE, NodeCategory.INPUT_POSE),
        NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT);

    /** 程序计算机：逻辑 / 数学 / 时序齐全，无运动与宿主专属节点。
     *  Program computer: full math/logic/sequential; no motion or host-specific nodes. */
    public static final NodeAllowance PROGRAM_COMPUTER = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.MATH_BASIC, NodeCategory.MATH_ADVANCED,
            NodeCategory.TRIG, NodeCategory.LOGIC, NodeCategory.CONTROL,
            NodeCategory.OUTPUT, NodeCategory.SEQUENTIAL_ACC, NodeCategory.SEQUENTIAL_STATE,
            NodeCategory.DEBUG));

    /** 数控齿轮箱：程序计算机能力 + 运动指令 + 动力读数。 */
    public static final NodeAllowance CNC_GEARBOX = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.MATH_BASIC, NodeCategory.MATH_ADVANCED,
            NodeCategory.TRIG, NodeCategory.LOGIC, NodeCategory.CONTROL,
            NodeCategory.OUTPUT, NodeCategory.SEQUENTIAL_ACC, NodeCategory.SEQUENTIAL_STATE,
            NodeCategory.GEARBOX, NodeCategory.KINETIC, NodeCategory.DEBUG));

    /** 动力仪表：动力读数 + 比较逻辑 + 值/输出。
     *  刻意不含 input/gearbox（读座舱/运动状态，仪表宿主不提供 → 恒 0）与
     *  SPEED_CTRL（仪表无目标转速概念），亦不含 display/math/control/sequential
     *  （可用但暂不提供，想要时加类即可）。
     *  Kinetic gauge: kinetic readings + comparison/logic + values/outputs.
     *  Excludes input/gearbox (they read seat/motion state this host never provides)
     *  and SPEED_CTRL (no target-speed concept); display/math/control/sequential are
     *  deliberately withheld (add the category when wanted). */
    public static final NodeAllowance KINETIC_GAUGE = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.LOGIC, NodeCategory.KINETIC,
            NodeCategory.OUTPUT, NodeCategory.DEBUG));

    /** 控制椅：键鼠手柄 / 视角 / 姿态运动 / 姿态转换；FORWARD 菜单不列出。
     *  Control seat: devices / view / motion / pose; FORWARD is menu-hidden. */
    public static final NodeAllowance CONTROL_SEAT = NodeAllowance.of(
        EnumSet.of(NodeCategory.INPUT_CTRL, NodeCategory.INPUT_VIEW, NodeCategory.INPUT_MOTION,
            NodeCategory.INPUT_POSE, NodeCategory.OUTPUT, NodeCategory.DEBUG),
        NodeType.FORWARD);

    /** 传感器：姿态运动 / 姿态转换 / 输出。 */
    public static final NodeAllowance SENSOR = NodeAllowance.of(
        EnumSet.of(NodeCategory.INPUT_MOTION, NodeCategory.INPUT_POSE,
            NodeCategory.OUTPUT, NodeCategory.DEBUG));

    /** 显示器：数值 + 显示组件。 */
    public static final NodeAllowance MONITOR = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.DISPLAY, NodeCategory.DEBUG));

    /** 雷达：目标输出。 */
    public static final NodeAllowance RADAR = NodeAllowance.of(
        EnumSet.of(NodeCategory.OUTPUT, NodeCategory.RADAR, NodeCategory.DEBUG));

    /** 转速代理：转速指令。 */
    public static final NodeAllowance SPEED_PROXY = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.SPEED, NodeCategory.DEBUG));

    /** 可编程变速器：动力读数 + 变速输出。 */
    public static final NodeAllowance TRANSMISSION = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.KINETIC, NodeCategory.TRANSMISSION,
            NodeCategory.DEBUG));
}
