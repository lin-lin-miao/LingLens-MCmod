package com.linglens.performance;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * 性能查询工具类。
 * 封装 TPS/MSPT 的计算逻辑。
 * <p>
 * 全局游戏性能数据完全依赖 {@link DimensionTickTracker} 的记录，
 * 该跟踪器通过 ServerLevelMixin 在每次维度 tick 时收集耗时数据。
 * <p>
 * 各维度 tick 串行执行，因此全局 MSPT = 各维度 MSPT 之和，
 * TPS = min(20.0, 1000 ms / 全局 MSPT)。
 * 适用于 Minecraft 1.20.1 及类似版本。
 */
public class PerformanceQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /** 用于计算 TPS 的采样数量（取最近 N 个 tick） */
    private static final int SAMPLE_COUNT = 20;

    /** MinecraftServer.tickTimes 环形缓冲区总容量 */
    private static final int TICK_TIMES_CAPACITY = 100;

    /** 理想 TPS 值 */
    private static final double IDEAL_TPS = 20.0;

    /** 纳秒转毫秒的换算系数 */
    private static final long NANOS_TO_MILLIS = 1_000_000L;

    public static PerformanceResult query(MinecraftServer server) {
        return getGamePerf(server);
    }

    /**
     * 获取当前 JVM 的系统性能数据（CPU 占用百分比、内存使用情况），并返回结果对象。
     * 参考 getGamePerf 的返回风格，将系统性能数据封装为 SystemPerfResult。
     *
     * @param server MinecraftServer 实例（暂未使用，保留参数以兼容后续扩展）
     * @return SystemPerfResult 包含 CPU 百分比、已用/总/最大内存（MB）
     */
    public static SystemPerfResult getSystemPerf(MinecraftServer server) {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        double usedMb = bytesToMb(usedMemory);
        double totalMb = bytesToMb(totalMemory);
        double maxMb = bytesToMb(maxMemory);

        // CPU 占用（百分比）
        double cpuPercent = -1.0;
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                double load = sunOsBean.getProcessCpuLoad();
                if (load >= 0) {
                    cpuPercent = load * 100.0;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[LingLens] 获取 JVM CPU 占用失败: {}", e.getMessage());
        }

        LOGGER.debug("[LingLens] 系统性能: CPU={}%, 内存={:.2f}/{:.2f}/{:.2f} MB",
                cpuPercent >= 0 ? String.format("%.2f", cpuPercent) : "N/A",
                usedMb, totalMb, maxMb);

        return new SystemPerfResult(cpuPercent, usedMb, totalMb, maxMb);
    }

    /**
     * 将字节数转换为 MB，保留两位小数
     */
    private static double bytesToMb(long bytes) {
        return bytes / 1024.0 / 1024.0;
    }

    /**
     * 执行查询，获取当前服务器的 TPS、MSPT 以及各维度的 MSPT。
     * <p>
     * 核心逻辑：
     * 1. 从 DimensionTickTracker 获取各维度 MSPT（已按 SAMPLE_COUNT 采样平均）。
     * 2. 全局 MSPT = 各维度 MSPT 之和（各维度 tick 串行执行）。
     * 3. TPS = min(20.0, 1000 / 全局 MSPT)。
     * <p>
     * 若维度数据为空（服务器刚启动尚未收集到数据），返回默认值（20 TPS, 0 MSPT）。
     *
     * @param server MinecraftServer 实例
     * @return 包含全部性能数据的 PerformanceResult 对象
     */
    public static PerformanceResult getGamePerf(MinecraftServer server) {
        // 1. 从 DimensionTickTracker 获取各维度 MSPT（毫秒）
        Map<String, Double> dimensionMspt = getDimensionMspt();

        if (dimensionMspt.isEmpty()) {
            // 尚未收集到任何维度数据，返回默认值
            LOGGER.info("[LingLens] 暂无维度 tick 数据，返回默认性能值");
            return new PerformanceResult(IDEAL_TPS, 0, 0.0, dimensionMspt);
        }

        // 2. 全局 MSPT = 各维度 MSPT 之和（各维度 tick 串行执行）
        double totalMspt = 0.0;
        for (double mspt : dimensionMspt.values()) {
            totalMspt += mspt;
        }

        // 3. 计算 TPS（封顶 20.0）
        double tps;
        double idletps;
        if (totalMspt > 0.0) {
            double maxtps = 1000.0 / totalMspt;
            tps = Math.min(IDEAL_TPS, maxtps);
            idletps = maxtps - tps;
        } else {
            tps = IDEAL_TPS;
            idletps = 0;
        }

        LOGGER.debug("[LingLens] 游戏性能查询结果: TPS={}+{}, MSPT={}ms, 维度数量={}",
                String.format("%.2f", tps), String.format("%.2f", idletps),
                String.format("%.2f", totalMspt),
                dimensionMspt.size());

        return new PerformanceResult(tps, idletps, totalMspt, dimensionMspt);
    }

    /**
     * 通过反射获取 MinecraftServer 中的 tickTimes 数组。
     * <p>
     * 
     * @deprecated 不再使用此方法；游戏性能数据完全依赖 DimensionTickTracker。
     *             保留代码仅供跨平台备选方案参考。
     *
     * @param server MinecraftServer 实例
     * @return tick 耗时数组（单位：纳秒），若获取失败则返回空数组
     */
    @Deprecated
    private static long[] getTickTimes(MinecraftServer server) {
        try {
            Field field = MinecraftServer.class.getDeclaredField("tickTimes");
            field.setAccessible(true);
            Object obj = field.get(server);
            if (obj instanceof long[]) {
                return (long[]) obj;
            }
        } catch (Exception e) {
            LOGGER.debug("[LingLens] getTickTimes 反射失败（已预期，改用 DimensionTickTracker）");
        }
        return new long[0];
    }

    /**
     * 通过反射获取 MinecraftServer 中的 tickCount 字段。
     * <p>
     * 
     * @deprecated 不再使用此方法；游戏性能数据完全依赖 DimensionTickTracker。
     *             保留代码仅供跨平台备选方案参考。
     *
     * @param server MinecraftServer 实例
     * @return 当前 tick 计数
     */
    @Deprecated
    private static int getTickCount(MinecraftServer server) {
        try {
            Field field = MinecraftServer.class.getDeclaredField("tickCount");
            field.setAccessible(true);
            return field.getInt(server);
        } catch (Exception e) {
            LOGGER.debug("[LingLens] getTickCount 反射失败（已预期，改用 DimensionTickTracker）");
            return TICK_TIMES_CAPACITY * 2;
        }
    }

    /**
     * 获取各已加载维度的 MSPT。
     * 数据来自 DimensionTickTracker，由 ServerLevelMixin 在每次 tick 时记录。
     * 如果维度跟踪器尚未收集到数据，返回空映射。
     *
     * @return 维度名称 -> MSPT 的映射
     */
    private static Map<String, Double> getDimensionMspt() {
        try {
            return DimensionTickTracker.getAllDimensionMspt(SAMPLE_COUNT);
        } catch (Exception e) {
            LOGGER.warn("[LingLens] 获取维度 MSPT 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 快速获取 TPS（便捷方法）。
     *
     * @param server MinecraftServer 实例
     * @return TPS 值
     */
    public static double getTps(MinecraftServer server) {
        return query(server).tps();
    }

    /**
     * 快速获取 MSPT（便捷方法）。
     *
     * @param server MinecraftServer 实例
     * @return MSPT 值（毫秒）
     */
    public static double getMspt(MinecraftServer server) {
        return query(server).mspt();
    }
}