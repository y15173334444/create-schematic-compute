package io.github.y15173334444.create_schematic_compute.api;

/**
 * 节点类目的 api 侧镜像（宿主套件波 2）：第三方宿主在 {@link HostAdmission} 里按类目
 * 整类声明接受面 —— 常量名与主模组内部类目一一对应（VALUES=数值、MATH_BASIC/MATH_
 * ADVANCED=数学、TRIG=三角、LOGIC=逻辑、CONTROL=控制、SEQUENTIAL_*=时序累计/状态、
 * INPUT_*=输入族、GEARBOX/TRANSMISSION/SPEED/KINETIC=动力、OUTPUT=输出、AUDIO=音频、
 * RADAR=雷达、DISPLAY=显示、STRUCTURE=封装、DEBUG=调试、VECTOR=向量、CUSTOM=第三方
 * 自定义节点）。映射到内部类目的换算住在图域适配层（api 包本体不引用图域类型）。
 * The api-side mirror of node categories (host suite, wave 2): a third-party host
 * declares its acceptance face whole-category at a time via {@link HostAdmission} —
 * constant names map one-to-one onto the mod's internal categories. The conversion to
 * internal categories lives in the graph-domain adapter layer (this package's own body
 * never references graph-domain types).
 *
 * <p>类目边界原则与内部面一致：一个宿主要么整类接受、要么整类不接受；自定义节点
 * 归 {@link #CUSTOM} 单类（名单制在其下按字符串 id 细分，见
 * {@code CustomNodes.admitToHost}）。</p>
 * <p>The boundary rule matches the internal face: a host accepts a category whole or
 * not at all; custom nodes ride the single {@link #CUSTOM} category (the roster
 * subdivides below it by string id — see {@code CustomNodes.admitToHost}).</p>
 */
public enum HostCategory {
    VALUES,
    MATH_BASIC,
    MATH_ADVANCED,
    TRIG,
    LOGIC,
    CONTROL,
    SEQUENTIAL_ACC,
    SEQUENTIAL_STATE,
    INPUT_CTRL,
    INPUT_VIEW,
    INPUT_MOTION,
    INPUT_POSE,
    GEARBOX,
    TRANSMISSION,
    SPEED,
    KINETIC,
    OUTPUT,
    AUDIO,
    RADAR,
    DISPLAY,
    STRUCTURE,
    DEBUG,
    CUSTOM,
    VECTOR
}
