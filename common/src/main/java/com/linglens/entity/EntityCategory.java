package com.linglens.entity;

/**
 * 实体类别枚举 —— 用于统计时按类别分组。
 * 根据 Minecraft 实体继承体系分类，涵盖服务器上常见的所有实体类型。
 */
public enum EntityCategory {
    /** 敌对生物（怪物），如僵尸、骷髅、苦力怕 */
    MONSTER,
    /** 友好生物（动物），如牛、羊、村民 */
    ANIMAL,
    /** 玩家 */
    PLAYER,
    /** 掉落物（物品实体） */
    ITEM,
    /** 经验球 */
    EXPERIENCE,
    /** 投射物（箭、雪球、火球等） */
    PROJECTILE,
    /** 载具（矿车、船） */
    VEHICLE,
    /** 其他（盔甲架、末影水晶等无法归入上述类别的实体） */
    MISC
}