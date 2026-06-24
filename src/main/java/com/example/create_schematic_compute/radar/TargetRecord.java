package com.example.create_schematic_compute.radar;

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

    /** 从 Sable 结构创建目标记录，以 (cx, cy, cz) 为扫描中心计算距离 */
    public static TargetRecord fromSableStructure(double cx, double cy, double cz,
                                                   double sx, double sy, double sz, String name) {
        double dx = sx - cx;
        double dy = sy - cy;
        double dz = sz - cz;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        // 用结构世界坐标生成稳定的负值 ID，使锁定系统可区分不同结构
        int id = -(Math.abs(java.util.Objects.hash(Math.round(sx*100), Math.round(sy*100), Math.round(sz*100))) % 999999) - 1;
        return new TargetRecord(sx, sy, sz, id, dist, TYPE_SABLE, name);
    }
}
