package com.example.create_schematic_compute.graph;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.*;

public enum NodeType {
    CONST("const", "node.create_schematic_compute.const", 0, 1, "value"),
    REDSTONE_IN("redstone_in", "node.create_schematic_compute.redstone_in", 0, 1, ""),
    REDSTONE_OUT("redstone_out", "node.create_schematic_compute.redstone_out", 1, 0, ""),
    ADD("add", "node.create_schematic_compute.add", 2, 1, ""),
    SUB("sub", "node.create_schematic_compute.sub", 2, 1, ""),
    MUL("mul", "node.create_schematic_compute.mul", 2, 1, ""),
    DIV("div", "node.create_schematic_compute.div", 2, 1, ""),
    MOD("mod", "node.create_schematic_compute.mod", 2, 1, ""),
    POW("pow", "node.create_schematic_compute.pow", 2, 1, ""),
    ROOT("root", "node.create_schematic_compute.root", 2, 1, ""),
    ABS("abs", "node.create_schematic_compute.abs", 1, 1, ""),
    INTERP("interp", "node.create_schematic_compute.interp", 2, 2, ""),
    CEIL("ceil", "node.create_schematic_compute.ceil", 1, 1, ""),
    FLOOR("floor", "node.create_schematic_compute.floor", 1, 1, ""),
    GT("gt", "node.create_schematic_compute.gt", 2, 1, ""),
    LT("lt", "node.create_schematic_compute.lt", 2, 1, ""),
    EQ("eq", "node.create_schematic_compute.eq", 2, 1, ""),
    GE("ge", "node.create_schematic_compute.ge", 2, 1, ""),
    LE("le", "node.create_schematic_compute.le", 2, 1, ""),
    PID("pid", "node.create_schematic_compute.pid", 2, 1, "kp,ki,kd,scale,ilimit"),
    PID_POWER("pid_power", "node.create_schematic_compute.pid_power", 3, 1, "kp,ki,kd,ilimit"),
    CLAMP("clamp", "node.create_schematic_compute.clamp", 1, 1, "min,max"),
    MAP("map", "node.create_schematic_compute.map", 1, 1, "in_min,in_max,out_min,out_max"),
    SPEED_CTRL("speed_ctrl", "node.create_schematic_compute.speed_ctrl", 2, 1, ""),
    BOOL("bool", "node.create_schematic_compute.bool", 1, 1, "inverted"),
    GATE("gate", "node.create_schematic_compute.gate", 4, 1, "default"),
    OR("or", "node.create_schematic_compute.or", 2, 1, ""),
    PRIVATE_IN("private_in", "node.create_schematic_compute.private_in", 0, 1, ""),
    PRIVATE_OUT("private_out", "node.create_schematic_compute.private_out", 1, 0, ""),
    BUS_IN("bus_in", "node.create_schematic_compute.bus_in", 0, 0, ""),
    BUS_OUT("bus_out", "node.create_schematic_compute.bus_out", 0, 0, ""),
    DELAY("delay", "node.create_schematic_compute.delay", 1, 1, "ticks"),
    LATCH("latch", "node.create_schematic_compute.latch", 2, 1, "default"),
    T_FLIPFLOP("t_flipflop", "node.create_schematic_compute.t_flipflop", 1, 1, "default"),
    PULSE_EXTEND("pulse_extend", "node.create_schematic_compute.pulse_extend", 1, 1, "ticks"),
    LOOP("loop", "node.create_schematic_compute.loop", 1, 1, "count,interval"),
    FUSE("fuse", "node.create_schematic_compute.fuse", 1, 1, "cooldown"),
    ROUND("round", "node.create_schematic_compute.round", 1, 1, "decimals"),
    SIN("sin", "node.create_schematic_compute.sin", 1, 1, ""),
    COS("cos", "node.create_schematic_compute.cos", 1, 1, ""),
    TAN("tan", "node.create_schematic_compute.tan", 1, 1, ""),
    ASIN("asin", "node.create_schematic_compute.asin", 1, 1, ""),
    ACOS("acos", "node.create_schematic_compute.acos", 1, 1, ""),
    ATAN2("atan2", "node.create_schematic_compute.atan2", 2, 1, ""),
    SINH("sinh", "node.create_schematic_compute.sinh", 1, 1, ""),
    COSH("cosh", "node.create_schematic_compute.cosh", 1, 1, ""),
    DIRECTION("direction", "node.create_schematic_compute.direction", 0, 3, "ax,ay,az,bx,by,bz"),
    POSITION("position", "node.create_schematic_compute.position", 0, 3, "offsetX,offsetY,offsetZ"),
    ACCUMULATOR("accumulator", "node.create_schematic_compute.accumulator", 2, 1, "step"),
    INTEGRATOR("integrator", "node.create_schematic_compute.integrator", 3, 1, "step,interval,limit"),
    FORMULA("formula", "node.create_schematic_compute.formula", 0, 1, ""),
    POSE_CONVERT("pose_convert", "node.create_schematic_compute.pose_convert", 3, 2, ""),
    // Control Seat input nodes
    KEYBOARD("keyboard", "node.create_schematic_compute.keyboard", 0, 1, "key"),
    MOUSE_JOYSTICK("mouse_joystick", "node.create_schematic_compute.mouse_joystick", 0, 2, ""),
    VIEW_ANGLE("view_angle", "node.create_schematic_compute.view_angle", 0, 2, ""),
    MOUSE_BUTTON("mouse_button", "node.create_schematic_compute.mouse_button", 0, 2, ""),
    GAMEPAD_JOYSTICK("gamepad_joystick", "node.create_schematic_compute.gamepad_joystick", 0, 4, ""),
    GAMEPAD_BUTTON("gamepad_button", "node.create_schematic_compute.gamepad_button", 0, 1, "button"),
    GAMEPAD_TRIGGER("gamepad_trigger", "node.create_schematic_compute.gamepad_trigger", 0, 2, ""),
    WORLD_VIEW("world_view", "node.create_schematic_compute.world_view", 0, 2, ""),
    ATTITUDE("attitude", "node.create_schematic_compute.attitude", 0, 2, ""),
    FORWARD("forward", "node.create_schematic_compute.forward", 0, 2, ""),
    ACCELERATION("acceleration", "node.create_schematic_compute.acceleration", 0, 3, ""),
    VELOCITY("velocity", "node.create_schematic_compute.velocity", 0, 3, ""),
    SPLIT("split", "node.create_schematic_compute.split", 1, 2, ""),
    TEXT("text", "node.create_schematic_compute.text", 0, 0, ""),
    DATA("data", "node.create_schematic_compute.data", 1, 0, ""),
    IMAGE("image", "node.create_schematic_compute.image", 3, 0, "moveScaleX,moveScaleY,rotationScale,invertX,invertY"),
    IMAGE_SEQUENCE("image_sequence", "node.create_schematic_compute.image_sequence", 4, 0, "moveScaleX,moveScaleY,rotationScale,invertX,invertY"),
    ENCAPSULATION("encapsulation", "node.create_schematic_compute.encapsulation", 0, 0, ""),
    ENCAP_INPUT("encap_input", "node.create_schematic_compute.encap_input", 0, 1, "name"),
    ENCAP_OUTPUT("encap_output", "node.create_schematic_compute.encap_output", 1, 0, "name"),
    // Radar
    TARGET_OUT("target_out", "node.create_schematic_compute.target_out", 0, 5, "");

    /** Stable string identifier for NBT serialisation — never change these. */
    public final String id;
    public final String displayName;
    public final int inputs;
    public final int outputs;
    public final String[] paramNames;

    /** Lookup table for deserialising from stable string id. */
    public static final Map<String, NodeType> BY_ID;
    static {
        var map = new HashMap<String, NodeType>();
        for (NodeType t : values()) {
            if (map.put(t.id, t) != null) {
                throw new IllegalStateException("Duplicate NodeType id: " + t.id);
            }
        }
        BY_ID = Collections.unmodifiableMap(map);
    }

    NodeType(String id, String n, int in, int out, String params) {
        this.id = id;
        displayName = n;
        inputs = in;
        outputs = out;
        paramNames = params.isEmpty() ? new String[0] : params.split(",");
    }

    /**
     * Safe ordinal lookup for migration use only.
     * Returns {@code null} if ordinal is out of range (corrupted data).
     */
    public static @Nullable NodeType byOrdinalSafe(int ordinal) {
        NodeType[] vals = values();
        return (ordinal >= 0 && ordinal < vals.length) ? vals[ordinal] : null;
    }

    /** 数值 EditBox 参数的数量（这些参数获得额外输入引脚）。返回 0 表示无。 */
    public int editableParamCount() {
        return switch (this) {
            case BOOL, GATE, T_FLIPFLOP, KEYBOARD, GAMEPAD_BUTTON, LATCH,
                 ENCAP_INPUT, ENCAP_OUTPUT, IMAGE, IMAGE_SEQUENCE,
                 BUS_IN, BUS_OUT -> 0;
            default -> paramNames.length;
        };
    }

    public String getTitle() { return displayName; }
    public Component title() { return Component.translatable(displayName); }

    /** Shorthand for pin i18n key: pin.create_schematic_compute.<label> */
    private static String pk(String label) { return "pin.create_schematic_compute." + label; }

    public String inputLabel(int i) {
        if (this == FORMULA) return "" + (char)('A' + i);
        return switch(this){
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT -> i==0?pk("a"):pk("b");
        case GT,LT,EQ,GE,LE,OR -> i==0?pk("a"):pk("b");
        case PID -> i==0?pk("sp"):pk("pv");
        case PID_POWER -> i==0?pk("sp"):i==1?pk("pv"):pk("base");
        case CLAMP -> i==0?pk("in"):i==1?pk("min"):pk("max");
        case MAP -> i==0?pk("in"):i==1?pk("in_min"):i==2?pk("in_max"):i==3?pk("out_min"):pk("out_max");
        case CEIL, FLOOR, BOOL, ABS, ROUND -> pk("in");
        case REDSTONE_OUT -> pk("in");
        case PRIVATE_OUT -> pk("val");
        case LATCH -> i==0?pk("s"):pk("r");
        case GATE -> i==0?pk("val"):i==1?pk("open"):i==2?pk("close"):pk("tog");
        case SPEED_CTRL -> i==0?pk("speed"):pk("dir");
        case PULSE_EXTEND, T_FLIPFLOP, DELAY, LOOP, FUSE -> pk("in");
        case ACCUMULATOR -> i == 0 ? pk("plus") : i == 1 ? pk("minus") : pk("step");
        case INTEGRATOR -> i == 0 ? pk("plus") : i == 1 ? pk("minus") : i == 2 ? pk("clear") : i == 3 ? pk("step") : i == 4 ? pk("interval") : pk("limit");
        case KEYBOARD -> pk("value");
        case POSE_CONVERT -> i == 0 ? pk("pitch_a") : i == 1 ? pk("yaw_a") : pk("roll");
        case DATA -> pk("val");
        case IMAGE -> i == 0 ? pk("x") : i == 1 ? pk("y") : pk("rotation");
        case IMAGE_SEQUENCE -> i == 0 ? pk("x") : i == 1 ? pk("y") : i == 2 ? pk("frame") : pk("rotation");
        case DIRECTION -> i==0?pk("ax"):i==1?pk("ay"):i==2?pk("az"):i==3?pk("bx"):i==4?pk("by"):pk("bz");
        case ENCAPSULATION -> pk("in"); // dynamic label from sub-graph ENCAP_INPUT name
        case ENCAP_OUTPUT -> pk("val");
        default -> pk("in");
    };}
    public String outputLabel(int i) { return switch(this){
        case CONST -> pk("float");
        case REDSTONE_IN -> pk("signal");
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT,ABS,ROUND,SIN,COS,TAN,ASIN,ACOS,ATAN2,SINH,COSH -> pk("float");
        case INTERP -> i==0?pk("a"):pk("b");
        case GT,LT,EQ,GE,LE, BOOL, OR -> pk("bool");
        case PID -> pk("ctrl");
        case PID_POWER -> pk("power");
        case CLAMP,MAP -> pk("float");
        case CEIL, FLOOR -> pk("int");
        case DELAY -> pk("out");
        case LATCH, GATE -> pk("out");
        case T_FLIPFLOP -> pk("tog_out");
        case PULSE_EXTEND -> pk("pulse");
        case LOOP -> pk("clk");
        case FUSE -> pk("pulse");
        case PRIVATE_IN -> pk("val");
        case SPEED_CTRL -> pk("rpm");
        case MOUSE_JOYSTICK -> i == 0 ? pk("x") : pk("y");
        case KEYBOARD -> pk("one_zero");
        case VIEW_ANGLE -> i == 0 ? pk("pitch") : pk("yaw");
        case MOUSE_BUTTON -> i == 0 ? pk("l_btn") : pk("r_btn");
        case GAMEPAD_JOYSTICK -> switch (i) { case 0 -> pk("lx"); case 1 -> pk("ly"); case 2 -> pk("rx"); default -> pk("ry"); };
        case GAMEPAD_BUTTON -> pk("one_zero");
        case GAMEPAD_TRIGGER -> i == 0 ? pk("lt") : pk("rt");
        case WORLD_VIEW -> i == 0 ? pk("yaw") : pk("pitch");
        case ATTITUDE -> i == 0 ? pk("pitch") : pk("roll");
        case FORWARD -> i == 0 ? pk("yaw") : pk("pitch");
        case ACCELERATION -> i == 0 ? pk("accel_x") : i == 1 ? pk("accel_y") : pk("accel_z");
        case VELOCITY -> i == 0 ? pk("vel_x") : i == 1 ? pk("vel_y") : pk("vel_z");
        case POSITION -> i == 0 ? pk("x") : i == 1 ? pk("y") : pk("z");
        case SPLIT -> i == 0 ? pk("plus_out") : pk("minus_out");
        case ACCUMULATOR, INTEGRATOR -> pk("val");
        case POSE_CONVERT -> i == 0 ? pk("pitch_b") : pk("yaw_b");
        case DIRECTION -> i==0?pk("yaw"):i==1?pk("pitch"):pk("distance");
        case ENCAPSULATION -> pk("out"); // dynamic label from sub-graph ENCAP_OUTPUT name
        case ENCAP_INPUT -> pk("val");
        case TARGET_OUT -> switch(i) { case 0 -> pk("x"); case 1 -> pk("y"); case 2 -> pk("z"); case 3 -> pk("entity_id"); default -> pk("distance"); };
        default -> "";
    };}
}
