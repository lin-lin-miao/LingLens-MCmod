package com.linglens.performance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 性能查询工具类。
 * 封装 TPS/MSPT 的计算逻辑，通过反射读取 MinecraftServer 中的 tickTimes 环形缓冲区数组。
 * MinecraftServer.tickTimes 是长度为 100 的 long[]，用作环形缓冲区，
 * 写入索引由 tickCount % 100 决定。
 * 各维度的 MSPT 通过 DimensionTickTracker (ServerLevelMixin 注入) 获取。
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
     * 核心逻辑：
     * 1. 通过反射获取 tickTimes 环形缓冲区（long[100]）
     * 2. 通过反射获取 tickCount（当前写入索引）
     * 3. 从 tickCount 往前倒推 SAMPLE_COUNT 个有效 tick，计算平均 MSPT
     * 4. TPS = min(20.0, 1000 / 平均 MSPT)
     * 5. 从 DimensionTickTracker 获取各维度 MSPT
     *
     * @param server MinecraftServer 实例
     * @return 包含全部性能数据的 PerformanceResult 对象
     */
    public static PerformanceResult getGamePerf(MinecraftServer server) {
        // 1. 获取 tick 耗时数组（环形缓冲区）和当前写入索引
        long[] tickTimes = getTickTimes(server);
        int tickCount = getTickCount(server);

        if (tickTimes == null || tickTimes.length == 0) {
            LOGGER.warn("[LingLens] tickTimes 数组为空或无法获取，返回默认值");
            return new PerformanceResult(IDEAL_TPS, 0.0, getDimensionMspt());
        }

        // 2. 从环形缓冲区中取出最近 SAMPLE_COUNT 个有效 tick 耗时
        int capacity = TICK_TIMES_CAPACITY;
        int writeIndex = tickCount % capacity; // 当前写入位置（下一个将覆盖的位置）
        int available = Math.min(tickCount, capacity); // 实际有多少个有效数据
        int count = Math.min(SAMPLE_COUNT, available);

        long sumNanos = 0;
        for (int i = 0; i < count; i++) {
            // 从 writeIndex - 1 往前取（最旧的先写，最新的后写）
            int idx = (writeIndex - 1 - i) % capacity;
            if (idx < 0) {
                idx += capacity;
            }
            sumNanos += tickTimes[idx];
        }

        // 3. 计算平均 MSPT（毫秒）
        double avgNanos = (double) sumNanos / count;
        double avgMs = avgNanos / NANOS_TO_MILLIS;

        // 4. 计算 TPS（封顶 20.0）
        double tps;
        if (avgMs > 0.0) {
            tps = Math.min(IDEAL_TPS, 1000.0 / avgMs);
        } else {
            tps = IDEAL_TPS;
        }

        // 5. 获取各维度 MSPT（由 DimensionTickTracker 提供）
        Map<String, Double> dimensionMspt = getDimensionMspt();

        LOGGER.debug("[LingLens] 性能查询结果: TPS={}, MSPT={}ms, tickCount={}, writeIndex={}",
                String.format("%.2f", tps),
                String.format("%.2f", avgMs),
                tickCount,
                writeIndex);
        return new PerformanceResult(tps, avgMs, dimensionMspt);
    }

    /**
     * 通过反射获取 MinecraftServer 中的 tickTimes 数组。
     * Minecraft 1.20.1 中 tickTimes 为 long[100]，作为环形缓冲区使用。
     *
     * @param server MinecraftServer 实例
     * @return tick 耗时数组（单位：纳秒），若获取失败则返回空数组
     */
    private static long[] getTickTimes(MinecraftServer server) {
        try {
            // 尝试直接访问字段名 tickTimes
            Field field = MinecraftServer.class.getDeclaredField("tickTimes");
            field.setAccessible(true);
            Object obj = field.get(server);
            if (obj instanceof long[]) {
                return (long[]) obj;
            } else if (obj == null) {
                LOGGER.warn("[LingLens] tickTimes 字段为 null");
            } else {
                LOGGER.warn("[LingLens] tickTimes 字段类型异常，期望 long[]，实际: {}", obj.getClass().getName());
            }
        } catch (NoSuchFieldException e) {
            LOGGER.error("[LingLens] 未找到 tickTimes 字段 (NoSuchFieldException): {}", e.getMessage());
        } catch (IllegalAccessException e) {
            LOGGER.error("[LingLens] 无法访问 tickTimes 字段 (IllegalAccessException): {}", e.getMessage());
        }
        return new long[0];
    }

    /**
     * 通过反射获取 MinecraftServer 中的 tickCount 字段，即当前已执行的 tick 总数。
     * 用于确定环形缓冲区中哪些数据是有效的。
     * 如果获取失败，则使用 tickTimes 全量数据（对新服务器可能不精确）。
     *
     * @param server MinecraftServer 实例
     * @return 当前 tick 计数
     */
    private static int getTickCount(MinecraftServer server) {
        try {
            // MinecraftServer.tickCount 是 int 类型，记录总 tick 数
            Field field = MinecraftServer.class.getDeclaredField("tickCount");
            field.setAccessible(true);
            return field.getInt(server);
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[LingLens] 未找到 tickCount 字段，回退到全量数据模式: {}", e.getMessage());
            // 回退：返回大数，使得环形缓冲区被视为写满状态
            return TICK_TIMES_CAPACITY * 2;
        } catch (IllegalAccessException e) {
            LOGGER.error("[LingLens] 无法访问 tickCount 字段: {}", e.getMessage());
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