package com.linglens.player;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

/**
 * 玩家信息数据模型。
 * <p>
 * 封装单个在线玩家的所有可查询状态信息，用于命令输出显示。
 * 使用 Java 16+ Record 类型作为不可变数据传输对象。
 * </p>
 *
 * @param name               玩家名称
 * @param uuid               玩家 UUID 字符串
 * @param position           当前位置坐标（Vec3）
 * @param dimension          当前维度（ResourceLocation）
 * @param health             当前生命值
 * @param maxHealth          最大生命值
 * @param armorValue         盔甲值（点数，如 0-20）
 * @param foodLevel          饥饿值（饱和度等级）
 * @param saturation         饱和浓度
 * @param experienceLevel    经验等级
 * @param experienceProgress 经验进度（0.0 ~ 1.0）
 * @param gameMode           游戏模式名称（survival/creative/adventure/spectator）
 * @param latency            延迟（Ping，单位毫秒）
 * @param sessionTimeSeconds 本次在线时长（秒）
 * @param totalTimeSeconds   总在线时长（秒）
 */
public record PlayerInfo(
    String name,
    String uuid,
    Vec3 position,
    ResourceLocation dimension,
    float health,
    float maxHealth,
    int armorValue,
    int foodLevel,
    float saturation,
    int experienceLevel,
    float experienceProgress,
    String gameMode,
    int latency,
    long sessionTimeSeconds,
    long totalTimeSeconds
) {

    /**
     * 获取格式化的坐标字符串（保留一位小数）。
     *
     * @return 形如 "123.5, 64.0, -45.8" 的字符串
     */
    public String getPositionString() {
        return String.format("%.1f, %.1f, %.1f", position.x(), position.y(), position.z());
    }

    /**
     * 获取人类可读的维度简称。
     * <p>
     * 从完整 ResourceLocation 中提取命名空间后的名称，如 "minecraft:overworld" → "overworld"。
     * 自定义维度保留完整名称。
     * </p>
     *
     * @return 维度简称
     */
    public String getDimensionShortName() {
        String full = dimension.toString();
        int colonIndex = full.indexOf(':');
        return colonIndex >= 0 ? full.substring(colonIndex + 1) : full;
    }

    /**
     * 获取格式化的生命值字符串。
     *
     * @return 形如 "20.0/20.0" 的字符串
     */
    public String getHealthString() {
        return String.format("%.1f/%.1f", health, maxHealth);
    }

    /**
     * 获取盔甲值显示字符串。
     *
     * @return 形如 "10" 或 "0" 的字符串
     */
    public String getArmorString() {
        return String.valueOf(armorValue);
    }

    /**
     * 获取格式化的饥饿值字符串（含饱和度）。
     *
     * @return 形如 "20 (+5.0)" 的字符串
     */
    public String getFoodString() {
        return String.format("%d (+%.1f)", foodLevel, saturation);
    }

    /**
     * 获取本次在线时长的可读字符串。
     *
     * @return 形如 "30m 15s" 的简洁格式
     */
    public String getSessionTimeString() {
        return formatDuration(sessionTimeSeconds);
    }

    /**
     * 获取总在线时长的可读字符串。
     *
     * @return 形如 "2h 30m 15s" 的格式
     */
    public String getTotalTimeString() {
        return formatDuration(totalTimeSeconds);
    }

    /**
     * 将秒数格式化为人类可读时长字符串。
     * <ul>
     *   <li>大于等于86400秒: 显示 "Xd Yh Zm"</li>
     *   <li>大于等于3600秒: 显示 "Xh Ym Zs"</li>
     *   <li>大于等于60秒: 显示 "Xm Ys"</li>
     *   <li>不足60秒: 显示 "Xs"</li>
     * </ul>
     *
     * @param seconds 秒数
     * @return 格式化的时长字符串，永不返回空串
     */
    private static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("s");
        return sb.toString().trim();
    }
}