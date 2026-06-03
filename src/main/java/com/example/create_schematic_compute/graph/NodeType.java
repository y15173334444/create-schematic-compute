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
    CEIL("node.create_schematic_compute.ceil", 1, 1, ""),
    FLOOR("node.create_schematic_compute.floor", 1, 1, ""),
    GT("node.create_schematic_compute.gt", 2, 1, ""),
    LT("node.create_schematic_compute.lt", 2, 1, ""),
    EQ("node.create_schematic_compute.eq", 2, 1, ""),
    PID("node.create_schematic_compute.pid", 2, 1, "kp,ki,kd,scale"),
    PID_POWER("node.create_schematic_compute.pid_power", 3, 1, "kp,ki,kd"),
    CLAMP("node.create_schematic_compute.clamp", 3, 1, ""),
    MAP("node.create_schematic_compute.map", 5, 1, ""),
    SPEED_CTRL("node.create_schematic_compute.speed_ctrl", 1, 1, ""),
    PRIVATE_IN("node.create_schematic_compute.private_in", 0, 1, ""),
    PRIVATE_OUT("node.create_schematic_compute.private_out", 1, 0, ""),
    DELAY("node.create_schematic_compute.delay", 1, 1, "ticks"),
    LATCH("node.create_schematic_compute.latch", 2, 1, ""),
    T_FLIPFLOP("node.create_schematic_compute.t_flipflop", 1, 1, ""),
    PULSE_EXTEND("node.create_schematic_compute.pulse_extend", 1, 1, "ticks"),
    LOOP("node.create_schematic_compute.loop", 1, 1, "count,interval"),
    FUSE("node.create_schematic_compute.fuse", 1, 1, "cooldown");

    public final String displayName;
    public final int inputs;
    public final int outputs;
    public final String[] paramNames;

    NodeType(String n, int in, int out, String params) { displayName=n; inputs=in; outputs=out; paramNames=params.isEmpty()?new String[0]:params.split(","); }
    public String getTitle() { return displayName; }
    public Component title() { return Component.translatable(displayName); }
    public String inputLabel(int i) { return switch(this){
        case ADD,SUB,MUL,DIV,MOD -> i==0?"A":"B";
        case GT,LT,EQ -> i==0?"A":"B";
        case PID -> i==0?"SP":"PV";
        case PID_POWER -> i==0?"SP":i==1?"PV":"base";
        case CLAMP -> i==0?"In":i==1?"Min":"Max";
        case MAP -> i==0?"In":i==1?"InMin":i==2?"InMax":i==3?"OutMin":"OutMax";
        case CEIL, FLOOR -> "in";
        case REDSTONE_OUT -> "In";
        case PRIVATE_OUT -> "val";
        case LATCH -> i==0?"S":"R";
        case PULSE_EXTEND, T_FLIPFLOP, DELAY, LOOP, FUSE -> "in";
        default -> "in";
    };}
    public String outputLabel(int i) { return switch(this){
        case CONST -> "float";
        case REDSTONE_IN -> "signal";
        case ADD,SUB,MUL,DIV,MOD -> "float";
        case GT,LT,EQ -> "bool";
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
        default -> "";
    };}
}
