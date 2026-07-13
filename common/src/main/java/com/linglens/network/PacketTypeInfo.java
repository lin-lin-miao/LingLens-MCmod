package com.linglens.network;

/**
 * 单个数据包类型的统计数据（线程不安全，需由外部同步）。
 * 记录该类型数据包的发送/接收次数和总字节数。
 */
public class PacketTypeInfo {
    /** 数据包计数 */
    private long packetCount;
    /** 总字节数 */
    private long totalBytes;

    /**
     * 记录一次数据包。
     *
     * @param bytes 该数据包的字节大小
     */
    public void record(int bytes) {
        this.packetCount++;
        this.totalBytes += bytes;
    }

    public long getPacketCount() { return packetCount; }
    public long getTotalBytes() { return totalBytes; }
}