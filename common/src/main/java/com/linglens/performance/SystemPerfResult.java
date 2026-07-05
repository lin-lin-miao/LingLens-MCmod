package com.linglens.performance;

// ===== 系统性能结果记录 =====
/**
 * 系统性能数据集合，包含 JVM CPU 占用百分比和内存使用情况。
 * 
 * @param cpuPercent        CPU 占用百分比（-1 表示不可用）
 * @param usedMemoryMB      已使用内存（MB）
 * @param allocatedMemoryMB 当前分配的总内存（MB）
 * @param maxMemoryMB       JVM 最大可用内存（MB）
 */
public record SystemPerfResult(
        double cpuPercent,
        double usedMemoryMB,
        double allocatedMemoryMB,
        double maxMemoryMB) {
    /**
     * 格式化输出为可读字符串，用于命令反馈。
     */
    public String toReadableString() {
        StringBuilder sb = new StringBuilder("§e=== 系统性能 ===\n");
        sb.append("§fCPU 占用: §")
                .append(cpuPercent >= 0 ? "a" + String.format("%.2f%%", cpuPercent) : "c不可用")
                .append("\n");
        sb.append("§f内存已用: §a").append(String.format("%.2f MB", usedMemoryMB)).append("\n");
        sb.append("§f内存分配: §a").append(String.format("%.2f MB", allocatedMemoryMB)).append("\n");
        sb.append("§f最大内存: §a").append(String.format("%.2f MB", maxMemoryMB)).append("\n");
        sb.append("§7内存占用率: §a")
                .append(String.format("%.1f%%", maxMemoryMB > 0 ? (usedMemoryMB / maxMemoryMB) * 100.0 : 0));
        return sb.toString();
    }
}
