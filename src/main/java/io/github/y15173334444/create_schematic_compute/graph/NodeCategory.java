package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 节点分类：菜单分组的唯一真相源，每个 {@link NodeType} 恰好属于一类。
 * Node categories: the single source of truth for add-menu grouping. Each
 * {@link NodeType} belongs to exactly one category.
 *
 * <p>分类边界原则：一个方块要么整类接受，要么整类不接受；做不到就拆类
 * （见 docs/node-category-allowance-plan.md）。</p>
 * <p>Boundary rule: a block accepts a category whole or not at all; otherwise
 * the category is split (see docs/node-category-allowance-plan.md).</p>
 */
public enum NodeCategory {
    VALUES("category.create_schematic_compute.values",
        NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN),
    MATH_BASIC("category.create_schematic_compute.math_basic",
        NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD,
        NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR,
        NodeType.ROUND, NodeType.SQRT, NodeType.LN, NodeType.LOG, NodeType.EXP),
    MATH_ADVANCED("category.create_schematic_compute.math_advanced",
        NodeType.FORMULA, NodeType.INTERP, NodeType.DIRECTION),
    TRIG("category.create_schematic_compute.trig",
        NodeType.SIN, NodeType.COS, NodeType.TAN, NodeType.ASIN, NodeType.ACOS,
        NodeType.ATAN2, NodeType.SINH, NodeType.COSH, NodeType.SEC, NodeType.CSC,
        NodeType.COT, NodeType.ANGLE_UNWRAP),
    LOGIC("category.create_schematic_compute.logic",
        NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ,
        NodeType.BOOL, NodeType.GATE, NodeType.OR, NodeType.RELAY_A, NodeType.RELAY_B),
    CONTROL("category.create_schematic_compute.control",
        NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP),
    /** 累加 / 积分（蓝图与主机都需要的状态量）/ accumulate & integrate */
    SEQUENTIAL_ACC("category.create_schematic_compute.sequential_acc",
        NodeType.ACCUMULATOR, NodeType.INTEGRATOR),
    /** 延时 / 锁存 / 触发器 / 脉冲 / 循环 / 保险丝 / delay-latch-flipflop-pulse-loop-fuse */
    SEQUENTIAL_STATE("category.create_schematic_compute.sequential_state",
        NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND,
        NodeType.LOOP, NodeType.FUSE),
    INPUT_CTRL("category.create_schematic_compute.input_ctrl",
        NodeType.KEYBOARD, NodeType.MOUSE_BUTTON, NodeType.MOUSE_JOYSTICK,
        NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON, NodeType.GAMEPAD_TRIGGER),
    INPUT_VIEW("category.create_schematic_compute.input_view",
        NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW),
    INPUT_MOTION("category.create_schematic_compute.input_motion",
        NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION,
        NodeType.VELOCITY, NodeType.POSITION),
    INPUT_POSE("category.create_schematic_compute.input_pose",
        NodeType.POSE_CONVERT, NodeType.SPLIT),
    GEARBOX("category.create_schematic_compute.gearbox",
        NodeType.MOVE, NodeType.ROTATE, NodeType.WAIT, NodeType.CLUTCH, NodeType.ENCODER),
    TRANSMISSION("category.create_schematic_compute.transmission", NodeType.TX_OUT),
    SPEED("category.create_schematic_compute.speed", NodeType.SPEED_CTRL),
    KINETIC("category.create_schematic_compute.kinetic", NodeType.STRESS, NodeType.RPM),
    OUTPUT("category.create_schematic_compute.output",
        NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.BUS_OUT),
    RADAR("category.create_schematic_compute.radar", NodeType.TARGET_OUT),
    DISPLAY("category.create_schematic_compute.display",
        NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE,
        NodeType.HUD_PITCH_LADDER),
    STRUCTURE("category.create_schematic_compute.structure",
        NodeType.ENCAPSULATION, NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT),
    DEBUG("category.create_schematic_compute.debug",
        NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE, NodeType.COMMENT);

    /** i18n 键 / lang key */
    public final String langKey;
    /** 本类包含的节点 / node types in this category */
    public final EnumSet<NodeType> types;

    NodeCategory(String langKey, NodeType first, NodeType... rest) {
        this.langKey = langKey;
        this.types = EnumSet.of(first, rest);
    }

    /** NodeType → 所属分类（构建时填充，运行期 O(1)）。
     *  Reverse map: NodeType → its category. */
    private static final Map<NodeType, NodeCategory> CATEGORY_OF;
    static {
        Map<NodeType, NodeCategory> m = new EnumMap<>(NodeType.class);
        for (NodeCategory c : values()) {
            for (NodeType t : c.types) {
                if (m.put(t, c) != null) {
                    throw new IllegalStateException("NodeType in two categories: " + t);
                }
            }
        }
        if (m.size() != NodeType.values().length) {
            throw new IllegalStateException("Category coverage gap: " + m.size()
                + " of " + NodeType.values().length);
        }
        CATEGORY_OF = Collections.unmodifiableMap(m);
    }

    /** 反查分类；枚举全覆盖，理论不为 null。
     *  Reverse lookup; full coverage, null should be impossible. */
    public static NodeCategory of(NodeType type) {
        return CATEGORY_OF.get(type);
    }

    /** 全部节点类型集合（覆盖校验用）/ every node type (for coverage checks). */
    public static Set<NodeType> allTypes() {
        return CATEGORY_OF.keySet();
    }
}
