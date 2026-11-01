package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * 雷达扫描目标数据记录。
 * entityId=-1 表示 Sable 物理结构。
 */
public record TargetRecord(double x, double y, double z, int entityId, float distance,
                           String entityType, String name) {

    public static final String TYPE_PLAYER = "player";
    public static final String TYPE_MOB = "mob";
    public static final String TYPE_SABLE = "sable";

    /** 从实体创建目标记录，以 (cx, cy, cz) 为扫描中心计算距离 */
    public static TargetRecord fromEntity(Entity e, double cx, double cy, double cz) {
        double dx = e.getX() - cx;
        double dy = e.getY() - cy;
        double dz = e.getZ() - cz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        String type = e instanceof Player ? TYPE_PLAYER : e instanceof Mob ? TYPE_MOB : "other";
        return new TargetRecord(e.getX(), e.getY(), e.getZ(), e.getId(), dist,
            type, e.getName().getString());
    }

    /** 从 Sable 结构创建目标记录，以 (cx, cy, cz) 为扫描中心计算距离。
     *  {@code identity} 是结构的**稳定身份**（子关卡 UUID 等，移动/重载不变）——
     *  绝不能用世界坐标推导：载具结构一移动坐标就变，按坐标哈希的 id 每 tick 漂移，
     *  锁定下一 tick 就被「不在扫描范围」的清理剪掉（「无法锁定 sable 结构」）。
     *  Create a target record for a Sable structure, distance measured from the scan
     *  center (cx, cy, cz). {@code identity} is the structure's **stable identity**
     *  (the sub-level UUID etc. — invariant under movement and reloads); never derive
     *  it from world coordinates: a moving craft's origin shifts every tick, so a
     *  position-hashed id churns and the out-of-range lock prune drops the lock on the
     *  next tick ("cannot lock Sable structures"). */
    public static TargetRecord fromSableStructure(double cx, double cy, double cz,
                                                   double sx, double sy, double sz,
                                                   int identity, String name) {
        double dx = sx - cx;
        double dy = sy - cy;
        double dz = sz - cz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new TargetRecord(sx, sy, sz, sableId(identity), dist, TYPE_SABLE, name);
    }

    /** 结构身份 → 稳定负 id（恒为负，避开正的实体 id；{@code & 0x7FFFFFFF} 防 Math.abs 溢出）。
     *  Structure identity → stable negative id (always negative, clear of positive entity
     *  ids; {@code & 0x7FFFFFFF} guards the Math.abs overflow). */
    public static int sableId(int identity) {
        return -((identity & 0x7FFFFFFF) % 999999) - 1;
    }
}
