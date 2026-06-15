package com.example.create_schematic_compute.graph;

import net.minecraft.network.chat.Component;

public enum NodeType {
    CONST("node.create_schematic_compute.const", 0, 1, "value"),
    REDSTONE_IN("node.create_schematic_compute.redstone_in", 0, 1, ""),
    REDSTONE_OUT("node.create_schematic_compute.redstone_out", 1, 0, ""),
    ADD("node.create_schematic_compute.add", 2, 1, ""),
    SUB("node.create_schematic_compute.sub", 2, 1, ""),
    MUL("node.create_schematic_compute.mul", 2, 1, ""),
    DIV("node.create_schematic_compute.div", 2, 1, ""),
    MOD("node.create_schematic_compute.mod", 2, 1, ""),
    POW("node.create_schematic_compute.pow", 2, 1, ""),
    ROOT("node.create_schematic_compute.root", 2, 1, ""),
    ABS("node.create_schematic_compute.abs", 1, 1, ""),
    INTERP("node.create_schematic_compute.interp", 2, 2, ""),
    CEIL("node.create_schematic_compute.ceil", 1, 1, ""),
    FLOOR("node.create_schematic_compute.floor", 1, 1, ""),
    GT("node.create_schematic_compute.gt", 2, 1, ""),
    LT("node.create_schematic_compute.lt", 2, 1, ""),
    EQ("node.create_schematic_compute.eq", 2, 1, ""),
    GE("node.create_schematic_compute.ge", 2, 1, ""),
    LE("node.create_schematic_compute.le", 2, 1, ""),
    PID("node.create_schematic_compute.pid", 1, 1, "kp,ki,kd,scale,ilimit"),
    PID_POWER("node.create_schematic_compute.pid_power", 2, 1, "kp,ki,ilimit"),
    CLAMP("node.create_schematic_compute.clamp", 3, 1, ""),
    MAP("node.create_schematic_compute.map", 5, 1, ""),
    SPEED_CTRL("node.create_schematic_compute.speed_ctrl", 2, 1, ""),
    BOOL("node.create_schematic_compute.bool", 1, 1, "inverted"),
    GATE("node.create_schematic_compute.gate", 4, 1, "default"),
    OR("node.create_schematic_compute.or", 2, 1, ""),
    PRIVATE_IN("node.create_schematic_compute.private_in", 0, 1, ""),
    PRIVATE_OUT("node.create_schematic_compute.private_out", 1, 0, ""),
    DELAY("node.create_schematic_compute.delay", 1, 1, "ticks"),
    LATCH("node.create_schematic_compute.latch", 2, 1, ""),
    T_FLIPFLOP("node.create_schematic_compute.t_flipflop", 1, 1, "default"),
    PULSE_EXTEND("node.create_schematic_compute.pulse_extend", 1, 1, "ticks"),
    LOOP("node.create_schematic_compute.loop", 1, 1, "count,interval"),
    FUSE("node.create_schematic_compute.fuse", 1, 1, "cooldown"),
    ROUND("node.create_schematic_compute.round", 1, 1, "decimals"),
    ACCUMULATOR("node.create_schematic_compute.accumulator", 2, 1, "step"),
    INTEGRATOR("node.create_schematic_compute.integrator", 3, 1, "step,interval,limit"),
    FORMULA("node.create_schematic_compute.formula", 0, 1, ""),
    POSE_CONVERT("node.create_schematic_compute.pose_convert", 3, 2, ""),
    // Control Seat input nodes
    KEYBOARD("node.create_schematic_compute.keyboard", 0, 1, "key"),
    MOUSE_JOYSTICK("node.create_schematic_compute.mouse_joystick", 0, 2, ""),
    VIEW_ANGLE("node.create_schematic_compute.view_angle", 0, 2, ""),
    MOUSE_BUTTON("node.create_schematic_compute.mouse_button", 0, 2, ""),
    GAMEPAD_JOYSTICK("node.create_schematic_compute.gamepad_joystick", 0, 4, ""),
    GAMEPAD_BUTTON("node.create_schematic_compute.gamepad_button", 0, 1, "button"),
    GAMEPAD_TRIGGER("node.create_schematic_compute.gamepad_trigger", 0, 2, ""),
    WORLD_VIEW("node.create_schematic_compute.world_view", 0, 2, ""),
    ATTITUDE("node.create_schematic_compute.attitude", 0, 2, ""),
    FORWARD("node.create_schematic_compute.forward", 0, 2, ""),
    ACCELERATION("node.create_schematic_compute.acceleration", 0, 3, ""),
    SPLIT("node.create_schematic_compute.split", 1, 2, ""),
    TEXT("node.create_schematic_compute.text", 0, 0, ""),
    DATA("node.create_schematic_compute.data", 1, 0, ""),
    IMAGE("node.create_schematic_compute.image", 3, 0, "moveScaleX,moveScaleY,rotationScale,invertX,invertY"),
    IMAGE_SEQUENCE("node.create_schematic_compute.image_sequence", 4, 0, "moveScaleX,moveScaleY,rotationScale,invertX,invertY"),
    ENCAPSULATION("node.create_schematic_compute.encapsulation", 0, 0, ""),
    ENCAP_INPUT("node.create_schematic_compute.encap_input", 0, 1, "name"),
    ENCAP_OUTPUT("node.create_schematic_compute.encap_output", 1, 0, "name");

    public final String displayName;
    public final int inputs;
    public final int outputs;
    public final String[] paramNames;

    NodeType(String n, int in, int out, String params) { displayName=n; inputs=in; outputs=out; paramNames=params.isEmpty()?new String[0]:params.split(","); }
    public String getTitle() { return displayName; }
    public Component title() { return Component.translatable(displayName); }

    /** Shorthand for pin i18n key: pin.create_schematic_compute.<label> */
    private static String pk(String label) { return "pin.create_schematic_compute." + label; }

    public String inputLabel(int i) {
        if (this == FORMULA) return "" + (char)('A' + i);
        return switch(this){
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT -> i==0?pk("a"):pk("b");
        case GT,LT,EQ,GE,LE,OR -> i==0?pk("a"):pk("b");
        case PID -> pk("sp");
        case PID_POWER -> i==0?pk("sp"):pk("base");
        case CLAMP -> i==0?pk("in"):i==1?pk("min"):pk("max");
        case MAP -> i==0?pk("in"):i==1?pk("in_min"):i==2?pk("in_max"):i==3?pk("out_min"):pk("out_max");
        case CEIL, FLOOR, BOOL, ABS, ROUND -> pk("in");
        case REDSTONE_OUT -> pk("in");
        case PRIVATE_OUT -> pk("val");
        case LATCH -> i==0?pk("s"):pk("r");
        case GATE -> i==0?pk("val"):i==1?pk("open"):i==2?pk("close"):pk("tog");
        case SPEED_CTRL -> i==0?pk("speed"):pk("dir");
        case PULSE_EXTEND, T_FLIPFLOP, DELAY, LOOP, FUSE -> pk("in");
        case ACCUMULATOR -> i == 0 ? pk("plus") : pk("minus");
        case INTEGRATOR -> i == 0 ? pk("plus") : i == 1 ? pk("minus") : pk("clear");
        case KEYBOARD -> pk("value");
        case POSE_CONVERT -> i == 0 ? pk("pitch_a") : i == 1 ? pk("yaw_a") : pk("roll");
        case DATA -> pk("val");
        case IMAGE -> i == 0 ? pk("x") : i == 1 ? pk("y") : pk("rotation");
        case IMAGE_SEQUENCE -> i == 0 ? pk("x") : i == 1 ? pk("y") : i == 2 ? pk("frame") : pk("rotation");
        case ENCAPSULATION -> pk("in"); // dynamic label from sub-graph ENCAP_INPUT name
        case ENCAP_OUTPUT -> pk("val");
        default -> pk("in");
    };}
    public String outputLabel(int i) { return switch(this){
        case CONST -> pk("float");
        case REDSTONE_IN -> pk("signal");
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT,ABS,ROUND -> pk("float");
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
        case MOUSE_BUTTON -> i == 0 ? pk("l") : pk("r");
        case GAMEPAD_JOYSTICK -> switch (i) { case 0 -> pk("lx"); case 1 -> pk("ly"); case 2 -> pk("rx"); default -> pk("ry"); };
        case GAMEPAD_BUTTON -> pk("one_zero");
        case GAMEPAD_TRIGGER -> i == 0 ? pk("lt") : pk("rt");
        case WORLD_VIEW -> i == 0 ? pk("yaw") : pk("pitch");
        case ATTITUDE -> i == 0 ? pk("pitch") : pk("roll");
        case FORWARD -> i == 0 ? pk("yaw") : pk("pitch");
        case ACCELERATION -> i == 0 ? pk("accel_x") : i == 1 ? pk("accel_y") : pk("accel_z");
        case SPLIT -> i == 0 ? pk("plus_out") : pk("minus_out");
        case ACCUMULATOR, INTEGRATOR -> pk("val");
        case POSE_CONVERT -> i == 0 ? pk("pitch_b") : pk("yaw_b");
        case ENCAPSULATION -> pk("out"); // dynamic label from sub-graph ENCAP_OUTPUT name
        case ENCAP_INPUT -> pk("val");
        default -> "";
    };}
}
