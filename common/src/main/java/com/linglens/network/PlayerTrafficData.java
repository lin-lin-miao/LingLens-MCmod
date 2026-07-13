package com.linglens.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个玩家的网络流量统计数据。
 * 以服务端视角统计上传（服务端→客户端）与下载（客户端→服务端）流量。
 * 该类是可变的，更新操作需外部同步（由 NetworkTrafficStats 保证线程安全）。
 * 支持按数据包类型分别统计。
 */
public class PlayerTrafficData {
    /** 上传数据包数量 */
    private long uploadPackets;
    /** 上传字节数 */
    private long uploadBytes;
    /** 下载数据包数量 */
    private long downloadPackets;
    /** 下载字节数 */
    private long downloadBytes;
    /** 统计开始时间（毫秒时间戳） */
    private final long sessionStartTime;

    /** 按数据包类型统计（类型简单名称 → 统计信息） */
    private final ConcurrentHashMap<String, PacketTypeInfo> uploadTypeStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PacketTypeInfo> downloadTypeStats = new ConcurrentHashMap<>();

    public PlayerTrafficData() {
        this.sessionStartTime = System.currentTimeMillis();
    }

    // ==================== 记录方法 ====================

    /**
     * 记录一次上传（服务端→客户端）。
     *
     * @param bytes      数据包字节大小
     * @param packetType 数据包类型的简单名称（如 "ClientboundLevelChunkWithLightPacket"）
     */
    public void recordUpload(int bytes, String packetType) {
        this.uploadPackets++;
        this.uploadBytes += bytes;
        // 按数据包类型记录
        uploadTypeStats.computeIfAbsent(packetType, k -> new PacketTypeInfo()).record(bytes);
    }

    /**
     * 记录一次下载（客户端→服务端）。
     *
     * @param bytes      数据包字节大小
     * @param packetType 数据包类型的简单名称
     */
    public void recordDownload(int bytes, String packetType) {
        this.downloadPackets++;
        this.downloadBytes += bytes;
        downloadTypeStats.computeIfAbsent(packetType, k -> new PacketTypeInfo()).record(bytes);
    }

    // ==================== 查询方法 ====================

    public long getUploadPackets() { return uploadPackets; }
    public long getUploadBytes()   { return uploadBytes; }
    public long getDownloadPackets() { return downloadPackets; }
    public long getDownloadBytes()   { return downloadBytes; }
    public long getSessionStartTime() { return sessionStartTime; }

    /**
     * 获取上传数据包按类型的统计信息（副本快照，外部只读）。
     */
    public Map<String, PacketTypeInfo> getUploadTypeStats() {
        return new ConcurrentHashMap<>(uploadTypeStats);
    }

    /**
     * 获取下载数据包按类型的统计信息（副本快照）。
     */
    public Map<String, PacketTypeInfo> getDownloadTypeStats() {
        return new ConcurrentHashMap<>(downloadTypeStats);
    }

    /**
     * 计算平均上传速度（字节/秒）。
     * 自创建以来到当前时间的平均速率，若时间不足 1 秒则返回 -1。
     */
    public double getAverageUploadSpeed() {
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed < 1000) return -1;
        return (double) uploadBytes / (elapsed / 1000.0);
    }

    /**
     * 计算平均下载速度（字节/秒）。
     */
    public double getAverageDownloadSpeed() {
        long elapsed = System.currentTimeMillis() - sessionStartTime;
        if (elapsed < 1000) return -1;
        return (double) downloadBytes / (elapsed / 1000.0);
    }

    /**
     * 重置该玩家的统计数据（清零计数，重新计时）。
     */
    public void reset() {
        this.uploadPackets = 0;
        this.uploadBytes = 0;
        this.downloadPackets = 0;
        this.downloadBytes = 0;
        this.uploadTypeStats.clear();
        this.downloadTypeStats.clear();
        // 注意：sessionStartTime 不重置，保持初始；如需完全重置应重新创建实例
    }

    @Override
    public String toString() {
        return String.format(
            "↑ %d pkts / %d bytes, ↓ %d pkts / %d bytes",
            uploadPackets, uploadBytes, downloadPackets, downloadBytes
        );
    }
}