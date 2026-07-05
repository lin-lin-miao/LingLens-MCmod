package com.linglens.entity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

// ================================================================
//  11. 查询结果封装（内部类）
// ================================================================

/**
 * 实体统计查询结果，包含维度层级数据。
 */
public class EntityQueryResult {
    /** 所有维度的实体总数 */
    public long globalTotal = 0;
    /** 是否来自缓存（false 表示即时扫描） */
    public boolean fromCache = false;
    /** 缓存/扫描时间戳 */
    public long cacheTime = 0;
    /** 维度键 -> 该维度实体总数 */
    public final Map<String, Long> dimensionTotals = new LinkedHashMap<>();
    /** 维度键 -> (类别 -> 计数) */
    public final Map<String, Map<EntityCategory, Long>> dimCatCounts = new LinkedHashMap<>();
    /** 维度键 -> (实体类型ID -> 计数)，仅包含 Top 20 */
    public final Map<String, Map<String, Long>> dimTypeCounts = new LinkedHashMap<>();

    /**
     * 生成格式化聊天栏文本（适用于 Minecraft 聊天框）。
     * 默认显示：全局总数 → 全局类别分布 → 全局类型Top10 → 维度概览前5。
     * 跳过没有实体的维度。如需查看完整维度详情（每个维度类别/类型），请使用 toDetailedString()。
     *
     * @return 格式化的聊天组件（含 § 颜色代码）
     */
    public Component toReadableString() {
        MutableComponent component = Component.literal("");

        // 标题
        component.append(Component.literal("§e=== [LingLens] 实体统计"));
        if (fromCache) {
            component.append(Component.literal(" §7(缓存)§e"));
        } else {
            component.append(Component.literal(" §7(即时扫描)§e"));
        }
        component.append(Component.literal(" ===\n"));

        // 全局总数
        component.append(Component.literal("§7总计: §f" + globalTotal + " 个实体\n"));

        // ── 全局类别统计（汇总所有维度） ──
        Map<EntityCategory, Long> globalCatCounts = new LinkedHashMap<>();
        for (Map<EntityCategory, Long> catMap : dimCatCounts.values()) {
            for (Map.Entry<EntityCategory, Long> entry : catMap.entrySet()) {
                globalCatCounts.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        if (!globalCatCounts.isEmpty()) {
            component.append(Component.literal("§7全局类别分布:\n"));
            globalCatCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(catEntry -> {
                        String catName = getCategoryDisplayName(catEntry.getKey());
                        component.append(Component.literal("  §7- " + catName + ": §f" + catEntry.getValue() + "\n"));
                    });
        }

        // ── 全局类型统计（汇总所有维度，Top 10） ──
        Map<String, Long> globalTypeCounts = new LinkedHashMap<>();
        for (Map<String, Long> typeMap : dimTypeCounts.values()) {
            for (Map.Entry<String, Long> entry : typeMap.entrySet()) {
                globalTypeCounts.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }
        if (!globalTypeCounts.isEmpty()) {
            String Command = "/kill @e[type=";
            component.append(Component.literal("  §7└ §f主要类型:\n"));
            globalTypeCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(typeEntry -> {
                        String typeName = typeEntry.getKey().replace("minecraft:", "");
                        component.append(Component
                                .literal("    §7- §f" + typeName + " §7× " + typeEntry.getValue() + "\n")
                                .setStyle(Style.EMPTY
                                        .withHoverEvent(
                                                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("杀")))
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                                Command + typeEntry.getKey() + "]"))));
                    });
            if (globalTypeCounts.size() > 8) {
                component.append(Component.literal("  §8... 及其他 " + (globalTypeCounts.size() - 8) + " 种类型\n"));
            }
        }

        // ── 维度概览：按数量降序，最多显示5个有实体的维度 ──
        var sortedDimensions = dimensionTotals.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .toList();

        if (!sortedDimensions.isEmpty()) {
            component.append(Component.literal("§7维度概览 (Top 5):\n"));
            int limit = Math.min(5, sortedDimensions.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Long> entry = sortedDimensions.get(i);
                String dimName = entry.getKey();
                component.append(Component.literal("§6▸ §b" + dimName + " §7: §f" + entry.getValue() + " 个实体\n"));
            }
            int remaining = sortedDimensions.size() - limit;
            if (remaining > 0) {
                component.append(Component.literal("§8... 及其他 " + remaining + " 个维度\n"));
            }
        }

        // 缓存信息
        if (fromCache) {
            component.append(Component.literal("\n§7[缓存时间: " + formatTimestamp(cacheTime) + "]"));
        } else {
            component.append(Component.literal("\n§7[本次为即时扫描，缓存已自动重建]"));
        }

        return component;
    }

    /**
     * 生成完整的维度详情文本，包含各维度类别和主要类型分布。
     * 跳过没有实体的维度。此为 toReadableString() 的完整版本。
     *
     * @return 格式化的完整统计字符串
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();

        sb.append("§e=== 灵棱枢 实体统计");
        if (fromCache) {
            sb.append(" §7(缓存)§e");
        } else {
            sb.append(" §7(即时扫描)§e");
        }
        sb.append(" ===\n");

        sb.append("§7总计: §f").append(globalTotal).append(" 个实体\n");

        for (Map.Entry<String, Long> dimEntry : dimensionTotals.entrySet()) {
            String dimKey = dimEntry.getKey();
            String dimName = dimKey.replace("minecraft:", "");
            long dimCount = dimEntry.getValue();

            if (dimCount <= 0)
                continue; // 跳过无实体的维度

            sb.append("\n§6▸ §b").append(dimName).append(" §7: §f").append(dimCount).append(" 个实体\n");

            Map<EntityCategory, Long> catMap = dimCatCounts.get(dimKey);
            if (catMap != null && !catMap.isEmpty()) {
                catMap.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .forEach(catEntry -> {
                            String catName = getCategoryDisplayName(catEntry.getKey());
                            long catCount = catEntry.getValue();
                            sb.append("  §7- ").append(catName).append(": §f").append(catCount).append("\n");
                        });
            }

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
            sb.append(" §")
                    .append(entry.getKey().contains("overworld") ? "a" : entry.getKey().contains("nether") ? "c" : "d")
                    .append(dimName).append(": §f").append(entry.getValue());
        }

        return sb.toString();
    }

    /**
     * 获取类别的人类可读显示名称（中文 + 英文）。
     */
    private static String getCategoryDisplayName(EntityCategory category) {
        switch (category) {
            case MONSTER:
                return "§c怪物(Monster)";
            case ANIMAL:
                return "§a动物(Animal)";
            case PLAYER:
                return "§b玩家(Player)";
            case ITEM:
                return "§6掉落物(Item)";
            case EXPERIENCE:
                return "§e经验球(Exp)";
            case PROJECTILE:
                return "§d投射物(Projectile)";
            case VEHICLE:
                return "§9载具(Vehicle)";
            case MISC:
                return "§7其他(Misc)";
            default:
                return "§8未知";
        }
    }

    /**
     * 格式化时间戳为可读字符串。
     *
     * @param timestamp 毫秒时间戳
     * @return 格式化后的时间字符串
     */
    private static String formatTimestamp(long timestamp) {
        if (timestamp <= 0)
            return "N/A";
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }
}
