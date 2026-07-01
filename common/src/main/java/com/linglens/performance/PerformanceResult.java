package com.linglens.performance;

import java.util.Map;

/**
 * 性能查询结果封装类。
 * 包含 TPS、MSPT 以及各维度的 MSPT（可选）。
 */
public record PerformanceResult(double tps,double idletps, double mspt, Map<String, Double> dimensionMspt) {

    /**
     * 将结果格式化为可读的字符串（带颜色代码 §）。
     *
     * @return 格式化后的性能报告字符串
     */
    public String toReadableString() {
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== §6LingLens 性能报告 §e===\n");
        sb.append("§fTPS: §a").append(String.format("%.2f", tps)).append("\n");
        sb.append("§fMSPT: §a").append(String.format("%.2f", mspt)).append(" ms\n");
        if (dimensionMspt != null && !dimensionMspt.isEmpty()) {
            sb.append("§7维度耗时:\n");
            for (Map.Entry<String, Double> entry : dimensionMspt.entrySet()) {
                String dimName = entry.getKey().replace("minecraft:", "");
                sb.append("  §f").append(dimName).append(": §a")
                        .append(String.format("%.2f", entry.getValue())).append(" ms\n");
            }
        }
        return sb.toString();
    }
}