package com.linglens.chunk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区块统计查询结果封装类。
 * <p>
 * 用于统计所有维度的已加载区块数、强制加载区块数，并计算全局合计。
 * </p>
 */
public class ChunkQueryResult {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /** 各维度区块统计条目 */
    private final List<DimensionChunkInfo> dimensionInfos;
    /** 全局总加载区块数 */
    private final long totalLoaded;
    /** 全局总强制加载区块数 */
    private final long totalForced;

    public ChunkQueryResult(List<DimensionChunkInfo> dimensionInfos, long totalLoaded, long totalForced) {
        this.dimensionInfos = dimensionInfos;
        this.totalLoaded = totalLoaded;
        this.totalForced = totalForced;
    }

    /**
     * 对服务器执行区块统计查询，收集所有维度的加载/强制加载区块信息。
     *
     * @param server MinecraftServer 实例
     * @return 包含全部维度区块统计的 ChunkQueryResult 对象
     */
    public static ChunkQueryResult queryAll(MinecraftServer server) {
        List<DimensionChunkInfo> infos = new ArrayList<>();
        long totalLoaded = 0;
        long totalForced = 0;

        for (ServerLevel level : server.getAllLevels()) {
            // 获取已加载区块数量（API 返回 int，但转换为 long 避免溢出）
            long loadedCount = level.getChunkSource().getLoadedChunksCount();

            // 获取强制加载区块集合（Set<ChunkPos>）
            Set<?> forcedChunks = level.getForcedChunks();
            long forcedCount = (forcedChunks != null) ? forcedChunks.size() : 0;

            // 维度名称：格式如 "minecraft:overworld"
            String dimensionName = level.dimension().location().toString();

            infos.add(new DimensionChunkInfo(dimensionName, loadedCount, forcedCount));
            totalLoaded += loadedCount;
            totalForced += forcedCount;
        }

        LOGGER.debug("[LingLens] 区块统计查询完成: 总加载区块={}, 总强制区块={}, 维度数={}",
                totalLoaded, totalForced, infos.size());

        return new ChunkQueryResult(infos, totalLoaded, totalForced);
    }

    // ==================== 格式化输出 ====================

    /**
     * 生成可读的区块统计文本，带有 Minecraft 颜色代码。
     *
     * @return 格式化的区块统计字符串
     */
    public String toReadableString() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== [LingLens] 区块加载统计 ===\n");
        sb.append("§f总加载区块: §a").append(totalLoaded).append("\n");
        sb.append("§f强制加载区块: §a").append(totalForced).append("\n");

        // 仅在有维度数据时输出表格
        if (!dimensionInfos.isEmpty()) {
            sb.append("\n§6维度名称").append(String.format("%6s", "加载数  强制数\n"));
            sb.append("§7----------------------------------------§r\n");
            for (DimensionChunkInfo info : dimensionInfos) {
                // 缩短维度名称显示（去掉 minecraft: 前缀）
                String shortName = info.dimensionName;
                sb.append("§f").append(String.format("%-28s", shortName))
                        .append("§a").append(String.format("%6d", info.loadedCount))
                        .append("  ")
                        .append("§b").append(String.format("%6d", info.forcedCount))
                        .append("\n");
            }
        } else {
            sb.append("§e未找到任何已加载维度。\n");
        }

        return sb.toString();
    }

    // ==================== 内部数据结构 ====================

    /**
     * 单个维度的区块统计信息。
     */
    public static class DimensionChunkInfo {
        private final String dimensionName;
        private final long loadedCount;
        private final long forcedCount;

        public DimensionChunkInfo(String dimensionName, long loadedCount, long forcedCount) {
            this.dimensionName = dimensionName;
            this.loadedCount = loadedCount;
            this.forcedCount = forcedCount;
        }

        public String getDimensionName() { return dimensionName; }
        public long getLoadedCount() { return loadedCount; }
        public long getForcedCount() { return forcedCount; }
    }

    // ==================== Getter ====================

    public List<DimensionChunkInfo> getDimensionInfos() { return dimensionInfos; }
    public long getTotalLoaded() { return totalLoaded; }
    public long getTotalForced() { return totalForced; }
}