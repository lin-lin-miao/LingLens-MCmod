package com.linglens.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网络流量统计管理器（单例，线程安全）。
 * 以服务端视角按玩家 UUID 存储上下行统计数据。
 * 提供记录、查询、重置等功能。
 */
public class NetworkTrafficStats {
    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");
    private static final NetworkTrafficStats INSTANCE = new NetworkTrafficStats();

    /** 玩家 UUID → 流量数据 */
    private final ConcurrentHashMap<UUID, PlayerTrafficData> statsMap = new ConcurrentHashMap<>();

    /** 全局开始统计时间（首次创建实例的时间） */
    private final long globalStartTime = System.currentTimeMillis();

    /** 全局上传总字节 */
    private long globalUploadBytes;
    /** 全局下载总字节 */
    private long globalDownloadBytes;
    /** 全局上传总包数 */
    private long globalUploadPackets;
    /** 全局下载总包数 */
    private long globalDownloadPackets;

    private NetworkTrafficStats() {
        LOGGER.info("[LingLens] 网络流量统计管理器初始化");
    }

    public static NetworkTrafficStats getInstance() {
        return INSTANCE;
    }

    // ==================== 记录流量 ====================

    /**
     * 记录一次上传流量（服务端→客户端）。
     *
     * @param playerUuid 玩家 UUID
     * @param bytes      数据包大小（字节）
     * @param packetType 数据包类型的简单名称（如 "ClientboundLevelChunkWithLightPacket"）
     */
    public void recordUpload(UUID playerUuid, int bytes, String packetType) {
        PlayerTrafficData data = statsMap.computeIfAbsent(playerUuid, k -> new PlayerTrafficData());
        synchronized (data) {
            data.recordUpload(bytes, packetType);
        }
        synchronized (this) {
            globalUploadPackets++;
            globalUploadBytes += bytes;
        }
    }

    /**
     * 记录一次下载流量（客户端→服务端）。
     *
     * @param playerUuid 玩家 UUID
     * @param bytes      数据包大小（字节）
     * @param packetType 数据包类型的简单名称
     */
    public void recordDownload(UUID playerUuid, int bytes, String packetType) {
        PlayerTrafficData data = statsMap.computeIfAbsent(playerUuid, k -> new PlayerTrafficData());
        synchronized (data) {
            data.recordDownload(bytes, packetType);
        }
        synchronized (this) {
            globalDownloadPackets++;
            globalDownloadBytes += bytes;
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取指定玩家的流量数据（返回快照副本，不会被后续记录影响）。
     * 若玩家从未有流量，返回 null。
     */
    public PlayerTrafficData getPlayerStats(UUID playerUuid) {
        PlayerTrafficData data = statsMap.get(playerUuid);
        if (data == null) return null;
        synchronized (data) {
            return data;
        }
    }

    /**
     * 返回所有玩家的统计数据映射（UUID → PlayerTrafficData）。
     * 注意：返回的 Map 是 ConcurrentHashMap 的视图，直接操作可能造成并发问题，建议只读。
     */
    public Map<UUID, PlayerTrafficData> getAllStats() {
        return statsMap;
    }

    // ==================== 全局统计 ====================

    public long getGlobalUploadPackets()   { return globalUploadPackets; }
    public long getGlobalUploadBytes()     { return globalUploadBytes; }
    public long getGlobalDownloadPackets() { return globalDownloadPackets; }
    public long getGlobalDownloadBytes()   { return globalDownloadBytes; }

    /**
     * 计算全局平均上传速度（字节/秒）。
     */
    public double getGlobalAverageUploadSpeed() {
        long elapsed = System.currentTimeMillis() - globalStartTime;
        if (elapsed < 1000) return -1;
        return (double) globalUploadBytes / (elapsed / 1000.0);
    }

    /**
     * 计算全局平均下载速度（字节/秒）。
     */
    public double getGlobalAverageDownloadSpeed() {
        long elapsed = System.currentTimeMillis() - globalStartTime;
        if (elapsed < 1000) return -1;
        return (double) globalDownloadBytes / (elapsed / 1000.0);
    }

    // ==================== 重置 ====================

    /**
     * 重置所有玩家的统计数据（清零计数，保留玩家键）。
     */
    public void resetAll() {
        statsMap.clear();
        synchronized (this) {
            globalUploadPackets = 0;
            globalUploadBytes = 0;
            globalDownloadPackets = 0;
            globalDownloadBytes = 0;
        }
        LOGGER.info("[LingLens] 网络流量统计已重置");
    }

    /**
     * 移除指定玩家的统计数据。
     */
    public void removePlayer(UUID playerUuid) {
        statsMap.remove(playerUuid);
    }

    /**
     * 获取当前被追踪的玩家数量。
     */
    public int getTrackedPlayerCount() {
        return statsMap.size();
    }
}