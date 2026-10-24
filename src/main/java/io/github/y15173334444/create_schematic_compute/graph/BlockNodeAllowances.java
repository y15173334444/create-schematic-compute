package io.github.y15173334444.create_schematic_compute.graph;

import java.util.EnumSet;

/**
 * 12 个方块节点图的准入名单（分类白名单 + 类内黑名单）。
 * Per-block node allowances for the twelve graph editors.
 *
 * <p>与 docs/node-category-allowance-plan.md §3 表对应（2026-10-24 复核见该文末尾）。
 * 子图过滤器（{@code GraphEditor} 内的 ENCAP_INPUT/ENCAP_OUTPUT）不在此处，由编辑器自理。</p>
 * <p>Matches the §3 table in docs/node-category-allowance-plan.md (see the 2026-10-24
 * review addendum at the end of that doc). The sub-graph filter (ENCAP_INPUT/ENCAP_OUTPUT
 * in {@code GraphEditor}) stays separate.</p>
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

    /** 数控齿轮箱：可编程运动机的功能面——值/四则/三角/逻辑（含比较）+ 时序编排 + 运动指令 +
     *  调试。2026-10-24 按功能面指派（用户裁定）：去高级运算（公式/插值/向量转角度，蓝图向
     *  脚本/曲线工具），控制（PID 系）、累计（累加器/积分器）、动力读数（应力/转速）三组
     *  整类隐藏——已放入的节点照常求值，动力读数仍是动力宿主求值域（仪表/变速器/齿轮箱）。
     *  CNC gearbox: a programmable motion machine's functional surface — values/arithmetic/
     *  trig/logic (with compare) + sequential orchestration + motion commands + debug.
     *  Assigned per functional surface, 2026-10-24 (user decision): math_advanced
     *  (formula/interp/direction — blueprint-tier script & curve tools) plus the control,
     *  accumulator and kinetic groups are withheld whole-category; nodes already placed
     *  still evaluate, and kinetic readings remain in the kinetic hosts' evaluation domain
     *  (gauge / transmission / gearbox). */
    public static final NodeAllowance CNC_GEARBOX = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.MATH_BASIC, NodeCategory.TRIG,
            NodeCategory.LOGIC, NodeCategory.OUTPUT, NodeCategory.SEQUENTIAL_STATE,
            NodeCategory.GEARBOX, NodeCategory.DEBUG));

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

    /** 功放电脑：音频制作与播放编排的功能面——音频类（黑名单 {@code SPEAKER_PLAY} 与
     *  {@code AUDIO_OUT}：前者是音响宿主的播放池，后者「音频输出」只有音响能真正发声，
     *  功放图上它只会误导——对外发布走音频承载的 BUS_OUT 同一条通路）；值/输出控制面 +
     *  时序（play/stop 触发与延时编排）+ 触发逻辑（含比较）+ 调试；数学/三角/脚本/控制
     *  整类刻意不给（想要时加类即可，动力仪表同款先例）。2026-10-24 按功能面指派；
     *  2026-10-25 AUDIO_OUT 移出功放菜单（v1.2.6.2 实测反馈）。
     *  WSHAPE 可用：其 LUT 挂在 AudioRef 上随频段表 → 音符包送达客户端，不限宿主图。
     *  Amplifier computer: the audio-production & playback-orchestration functional
     *  surface — the AUDIO category (blacklisting {@code SPEAKER_PLAY}, the speaker
     *  host's playback sink, and {@code AUDIO_OUT}: only a speaker truly emits sound, so
     *  an "audio output" node on the amplifier misleads — external publishing rides the
     *  audio-carrying BUS_OUT path) + the values/output control plane + sequential
     *  (play/stop triggering & delay orchestration) + trigger logic (compare rides the
     *  whole category) + debug; math/trig/script/control are deliberately withheld (add
     *  a category when wanted — the kinetic gauge precedent). Assigned per functional
     *  surface, 2026-10-24; AUDIO_OUT left the amplifier menu 2026-10-25 (v1.2.6.2).
     *  WSHAPE allowed: its LUT rides the AudioRef through the band table into the note
     *  packet, so it works from any host graph. */
    public static final NodeAllowance AMPLIFIER = NodeAllowance.of(
        EnumSet.of(NodeCategory.VALUES, NodeCategory.LOGIC, NodeCategory.OUTPUT,
            NodeCategory.SEQUENTIAL_ACC, NodeCategory.SEQUENTIAL_STATE,
            NodeCategory.AUDIO, NodeCategory.DEBUG),
        NodeType.SPEAKER_PLAY, NodeType.AUDIO_OUT);

    /** 音响（音频图）：AUDIO 类（含音频频段 BUS_IN/收发）+ VALUES（BUS_IN/CONST 等，通信/控制）
     *  + DEBUG，但不含曲目宿主/发布节点（MUSIC/AUDIO_OUT 不进菜单：MUSIC 在功放侧编排，
     *  AUDIO_OUT 是发布节点且音响的发声出口是 SPEAKER_PLAY 直通宿主）。
     *  Speaker (audio graph): AUDIO + VALUES (BUS_IN/CONST for communication & control) + DEBUG,
     *  minus MUSIC/AUDIO_OUT (MUSIC is orchestrated on the amplifier; AUDIO_OUT is a publish
     *  node and the speaker's emission path is SPEAKER_PLAY straight into the host). */
    public static final NodeAllowance SPEAKER = NodeAllowance.of(
        EnumSet.of(NodeCategory.AUDIO, NodeCategory.VALUES, NodeCategory.DEBUG),
        NodeType.MUSIC, NodeType.AUDIO_OUT);
}
