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
    PID("node.create_schematic_compute.pid", 1, 1, "kp,ki,kd,scale,ilimit"),
    PID_POWER("node.create_schematic_compute.pid_power", 2, 1, "kp,ki,ilimit"),
    CLAMP("node.create_schematic_compute.clamp", 3, 1, ""),
    MAP("node.create_schematic_compute.map", 5, 1, ""),
    SPEED_CTRL("node.create_schematic_compute.speed_ctrl", 2, 1, ""),
    BOOL("node.create_schematic_compute.bool", 1, 1, "inverted"),
    PRIVATE_IN("node.create_schematic_compute.private_in", 0, 1, ""),
    PRIVATE_OUT("node.create_schematic_compute.private_out", 1, 0, ""),
    DELAY("node.create_schematic_compute.delay", 1, 1, "ticks"),
    LATCH("node.create_schematic_compute.latch", 2, 1, ""),
    T_FLIPFLOP("node.create_schematic_compute.t_flipflop", 1, 1, ""),
    PULSE_EXTEND("node.create_schematic_compute.pulse_extend", 1, 1, "ticks"),
    LOOP("node.create_schematic_compute.loop", 1, 1, "count,interval"),
    FUSE("node.create_schematic_compute.fuse", 1, 1, "cooldown"),
    ACCUMULATOR("node.create_schematic_compute.accumulator", 2, 1, "step"),
    FORMULA("node.create_schematic_compute.formula", 0, 1, ""),
    POSE_CONVERT("node.create_schematic_compute.pose_convert", 3, 2, ""),
    // Control Seat input nodes
    KEYBOARD("node.create_schematic_compute.keyboard", 0, 1, "key"),
    MOUSE_JOYSTICK("node.create_schematic_compute.mouse_joystick", 0, 2, ""),
    VIEW_ANGLE("node.create_schematic_compute.view_angle", 0, 2, ""),
    MOUSE_BUTTON("node.create_schematic_compute.mouse_button", 0, 2, ""),
    GAMEPAD_JOYSTICK("node.create_schematic_compute.gamepad_joystick", 0, 4, ""),
    GAMEPAD_BUTTON("node.create_schematic_compute.gamepad_button", 0, 1, "button"),
    WORLD_VIEW("node.create_schematic_compute.world_view", 0, 2, ""),
    ATTITUDE("node.create_schematic_compute.attitude", 0, 2, ""),
    FORWARD("node.create_schematic_compute.forward", 0, 2, ""),
    SPLIT("node.create_schematic_compute.split", 1, 2, ""),
    TEXT("node.create_schematic_compute.text", 0, 0, ""),
    DATA("node.create_schematic_compute.data", 1, 0, ""),
    IMAGE("node.create_schematic_compute.image", 2, 0, ""),
    IMAGE_SEQUENCE("node.create_schematic_compute.image_sequence", 3, 0, "");

    public final String displayName;
    public final int inputs;
    public final int outputs;
    public final String[] paramNames;

    NodeType(String n, int in, int out, String params) { displayName=n; inputs=in; outputs=out; paramNames=params.isEmpty()?new String[0]:params.split(","); }
    public String getTitle() { return displayName; }
    public Component title() { return Component.translatable(displayName); }
    public String inputLabel(int i) {
        if (this == FORMULA) return "" + (char)('A' + i);
        return switch(this){
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT -> i==0?"A":"B";
        case GT,LT,EQ -> i==0?"A":"B";
        case PID -> "SP";
        case PID_POWER -> i==0?"SP":"base";
        case CLAMP -> i==0?"In":i==1?"Min":"Max";
        case MAP -> i==0?"In":i==1?"InMin":i==2?"InMax":i==3?"OutMin":"OutMax";
        case CEIL, FLOOR, BOOL, ABS -> "in";
        case REDSTONE_OUT -> "In";
        case PRIVATE_OUT -> "val";
        case LATCH -> i==0?"S":"R";
        case SPEED_CTRL -> i==0?"speed":"dir";
        case PULSE_EXTEND, T_FLIPFLOP, DELAY, LOOP, FUSE -> "in";
        case ACCUMULATOR -> i == 0 ? "+" : "-";
        case KEYBOARD -> "value";
        case POSE_CONVERT -> i == 0 ? "pitch_a" : i == 1 ? "yaw_a" : "roll";
        case DATA -> "val";
        case IMAGE, IMAGE_SEQUENCE -> i == 0 ? "X" : i == 1 ? "Y" : "frame";
        default -> "in";
    };}
    public String outputLabel(int i) { return switch(this){
        case CONST -> "float";
        case REDSTONE_IN -> "signal";
        case ADD,SUB,MUL,DIV,MOD,POW,ROOT,ABS -> "float";
        case INTERP -> i==0?"A":"B";
        case GT,LT,EQ, BOOL -> "bool";
        case PID -> "ctrl";
        case PID_POWER -> "power";
        case CLAMP,MAP -> "float";
        case CEIL, FLOOR -> "int";
        case DELAY -> "out";
        case LATCH -> "q";
        case T_FLIPFLOP -> "tog";
        case PULSE_EXTEND -> "pulse";
        case LOOP -> "clk";
        case FUSE -> "pulse";
        case PRIVATE_IN -> "val";
        case SPEED_CTRL -> "rpm";
        case MOUSE_JOYSTICK -> i == 0 ? "X" : "Y";
        case KEYBOARD -> "1/0";
        case VIEW_ANGLE -> i == 0 ? "pitch" : "yaw";
        case MOUSE_BUTTON -> i == 0 ? "L" : "R";
        case GAMEPAD_JOYSTICK -> switch (i) { case 0 -> "LX"; case 1 -> "LY"; case 2 -> "RX"; default -> "RY"; };
        case GAMEPAD_BUTTON -> "1/0";
        case WORLD_VIEW -> i == 0 ? "yaw" : "pitch";
        case ATTITUDE -> i == 0 ? "pitch" : "roll";
        case FORWARD -> i == 0 ? "yaw" : "pitch";
        case SPLIT -> i == 0 ? "+out" : "-out";
        case ACCUMULATOR -> "val";
        case POSE_CONVERT -> i == 0 ? "pitch_b" : "yaw_b";
        default -> "";
    };}
}
