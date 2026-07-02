package com.linglens.entity;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 实体统计查询结果封装。
 * 包含全局总数、按维度分组、按类别分组、按具体类型分组的数据。
 * <p>
 * 提供 {@link #toReadableString()} 方法生成格式化的聊天栏文本。
 */
public class QueryResult {

    /** 全局实体总数 */
    public long globalTotal = 0;

    /** 是否来自缓存（true=缓存读取，false=即时全量扫描） */
    public boolean fromCache = false;

    /** 缓存 / 扫描的时间戳 */
    public long cacheTime = 0;

    /** 维度key → 该维度实体总数 */
    public final Map<String, Long> dimensionTotals = new LinkedHashMap<>();

    /** 维度key → (类别 → 计数) */
    public final Map<String, Map<EntityCategory, Long>> dimCatCounts = new LinkedHashMap<>();

    /** 维度key → (实体类型ID → 计数)，仅保留 Top 20 */
    public final Map<String, Map<String, Long>> dimTypeCounts = new LinkedHashMap<>();

    public QueryResult() {}

    /**
     * 生成格式化聊天栏文本（适用于 Minecraft 聊天框）。
     * 如果 fromCache = true，显示"缓存"标识；否则显示"即时扫描"标识。
     *
     * @return 格式化的字符串（含 § 颜色代码）
     */
    public String toReadableString() {
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append("§e=== 灵棱枢 实体统计");
        if (fromCache) {
            sb.append(" §7(缓存)§e");
        } else {
            sb.append(" §7(即时扫描)§e");
        }
        sb.append(" ===\n");

        // 全局总数
        sb.append("§7总计: §f").append(globalTotal).append(" 个实体\n");

        // 按维度分组显示
        for (Map.Entry<String, Long> dimEntry : dimensionTotals.entrySet()) {
            String dimKey = dimEntry.getKey();
            String dimName = dimKey.replace("minecraft:", "");
            long dimCount = dimEntry.getValue();

            sb.append("\n§6▸ §b").append(dimName).append(" §7: §f").append(dimCount).append(" 个实体\n");

            // 该类别的类别计数
            Map<EntityCategory, Long> catMap = dimCatCounts.get(dimKey);
            if (catMap != null && !catMap.isEmpty()) {
                // 按数量降序排列
                catMap.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .forEach(catEntry -> {
                            String catName = getCategoryDisplayName(catEntry.getKey());
                            long catCount = catEntry.getValue();
                            sb.append("  §7- ").append(catName).append(": §f").append(catCount).append("\n");
                        });
            }

            // 该维度的具体类型（Top 5 显示在概览中，避免过长）
            Map<String, Long> typeMap = dimTypeCounts.get(dimKey);
            if (typeMap != null && !typeMap.isEmpty()) {
                sb.append("  §8└ 主要类型:\n");
                typeMap.entrySet().stream()
                        .limit(5)
                        .forEach(typeEntry -> {
                            String typeName = typeEntry.getKey().replace("minecraft:", "");
                            sb.append("    §7- §f").append(typeName)
                                    .append(" §7× ").append(typeEntry.getValue()).append("\n");
                        });
                if (typeMap.size() > 5) {
                    sb.append("    §8... 及其他 ").append(typeMap.size() - 5).append(" 种类型\n");
                }
            }
        }

        // 缓存信息
        if (fromCache) {
            sb.append("\n§7[缓存时间: ").append(formatTimestamp(cacheTime)).append("]");
        } else {
            sb.append("\n§7[本次为即时扫描，缓存已自动重建]");
        }

        return sb.toString();
    }

    /**
     * 生成简短的统计概览（用于命令补全提示或嵌入综合消息）。
     *
     * @return 简洁的一行文本
     */
    public String toShortString() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7实体总数: §f").append(globalTotal);
        sb.append(fromCache ? " §7[缓存]" : " §7[即时]");

        // 显示各维度数量
        sb.append(" §8|");
        for (Map.Entry<String, Long> entry : dimensionTotals.entrySet()) {
            String dimName = entry.getKey().replace("minecraft:", "");
            sb.append(" §").append(entry.getKey().contains("overworld") ? "a" :
                       entry.getKey().contains("nether") ? "c" : "d")
              .append(dimName).append(": §f").append(entry.getValue());
        }

        return sb.toString();
    }

    /**
     * 获取类别的人类可读显示名称（中文 + 英文）。
     */
    private static String getCategoryDisplayName(EntityCategory category) {
        switch (category) {
            case MONSTER:    return "§c怪物(Monster)";
            case ANIMAL:     return "§a动物(Animal)";
            case PLAYER:     return "§b玩家(Player)";
            case ITEM:       return "§6掉落物(Item)";
            case EXPERIENCE: return "§e经验球(Exp)";
            case PROJECTILE: return "§d投射物(Projectile)";
            case VEHICLE:    return "§9载具(Vehicle)";
            case MISC:       return "§7其他(Misc)";
            default:         return "§8未知";
        }
    }

    /**
     * 格式化时间戳为可读字符串。
     *
     * @param timestamp 毫秒时间戳
     * @return 格式化后的时间字符串
     */
    private static String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "N/A";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }
}