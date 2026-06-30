package com.linglens.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维度 Tick 耗时跟踪器。
 * 在 ServerLevel.tick() 的 HEAD 和 TAIL 注入，记录每个维度的最近 tick 耗时。
 * 游戏性能查询(PerformanceQuery)通过此类获取各维度 MSPT。
 * 适用于 Minecraft 1.20.1，线程安全设计。
 * <p>
 * 设计说明：
 * - 在 1.20.1 中，维度 tick 在服务端主线程上串行执行，
 *   命令执行也在同一线程，所以理论上是单线程访问。
 * - 使用 ConcurrentHashMap 保证跨平台和未来版本的线程安全性。
 * - 每个维度维护一个环形缓冲区（long[]），以及一个写入索引计数器，
 *   写入索引始终递增，取模 SAMPLE_COUNT 获得实际数组位置。
 */
public class DimensionTickTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /** 每个维度保存的最近 tick 样本数 */
    private static final int SAMPLE_COUNT = 100;

    /**
     * 维度 key -> 环形缓冲区（纳秒耗时）
     * 使用 ConcurrentHashMap 确保可见性和线程安全
     */
    private static final ConcurrentHashMap<String, long[]> DIMENSION_TICK_TIMES = new ConcurrentHashMap<>();

    /**
     * 维度 key -> 当前写入计数器（总写入次数，始终递增，取模 SAMPLE_COUNT 获得数组索引）
     */
    private static final ConcurrentHashMap<String, Integer> DIMENSION_WRITE_INDEX = new ConcurrentHashMap<>();

    /**
     * 维度 key -> 当前 tick 开始时间（纳秒），用于 HEAD->TAIL 计算耗时
     * 每个维度同时只会有一个进行中的 tick，所以此处只保存一个值
     */
    private static final ConcurrentHashMap<String, Long> DIMENSION_TICK_START = new ConcurrentHashMap<>();

    /**
     * 保护 DIMENSION_WRITE_INDEX 的更新和读取操作原子性的锁对象
     * 由于 ConcurrentHashMap 不能保证复合操作的原子性，我们用了一个全局锁
     */
    private static final Object INDEX_LOCK = new Object();

    /**
     * 在 ServerLevel.tick() 的 HEAD 调用，记录 tick 开始时间。
     *
     * @param dimensionKey 维度唯一标识，例如 "minecraft:overworld"
     */
    public static void onTickStart(String dimensionKey) {
        // 使用 put (不是 putIfAbsent) 因为我们需要覆盖旧的开始时间（如果前一个 tick 没正常结束）
        DIMENSION_TICK_START.put(dimensionKey, System.nanoTime());
    }

    /**
     * 在 ServerLevel.tick() 的 TAIL 调用，记录本次 tick 耗时并存入环形缓冲区。
     *
     * @param dimensionKey 维度唯一标识，例如 "minecraft:overworld"
     */
    public static void onTickEnd(String dimensionKey) {
        Long startNanos = DIMENSION_TICK_START.remove(dimensionKey);
        if (startNanos == null) {
            LOGGER.warn("[LingLens] 维度 {} tick 开始时间丢失，跳过记录", dimensionKey);
            return;
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        if (elapsedNanos < 0) {
            LOGGER.warn("[LingLens] 维度 {} 时间异常（负值），重置: {}ns", dimensionKey, elapsedNanos);
            return;
        }

        // 获取或创建该维度的环形缓冲区
        long[] tickTimes = DIMENSION_TICK_TIMES.computeIfAbsent(dimensionKey, k -> new long[SAMPLE_COUNT]);

        // 原子性地获取当前写索引并递增
        int writeIndex;
        synchronized (INDEX_LOCK) {
            writeIndex = DIMENSION_WRITE_INDEX.getOrDefault(dimensionKey, 0);
            DIMENSION_WRITE_INDEX.put(dimensionKey, writeIndex + 1);
        }

        // 写入环形缓冲区（写索引取模后对应数组位置）
        tickTimes[writeIndex % SAMPLE_COUNT] = elapsedNanos;
    }

    /**
     * 获取指定维度的最近 N 个 tick 的平均 MSPT。
     *
     * @param dimensionKey 维度唯一标识
     * @param count        采样数量（不超过 SAMPLE_COUNT）
     * @return 平均 MSPT（毫秒），若无数据则返回 0.0
     */
    public static double getAverageMspt(String dimensionKey, int count) {
        long[] tickTimes = DIMENSION_TICK_TIMES.get(dimensionKey);
        Integer writeIndexObj = DIMENSION_WRITE_INDEX.get(dimensionKey);

        if (tickTimes == null || writeIndexObj == null || writeIndexObj == 0) {
            return 0.0;
        }

        int writeIndex = writeIndexObj;
        int available = Math.min(writeIndex, SAMPLE_COUNT);
        int sampleCount = Math.min(count, available);

        if (sampleCount <= 0) {
            return 0.0;
        }

        long sumNanos = 0;
        for (int i = 0; i < sampleCount; i++) {
            // 从最新往前取：最新写入位置是 (writeIndex - 1) % SAMPLE_COUNT
            int idx = (writeIndex - 1 - i) % SAMPLE_COUNT;
            if (idx < 0) {
                idx += SAMPLE_COUNT;
            }
            sumNanos += tickTimes[idx];
        }

        double avgNanos = (double) sumNanos / sampleCount;
        return avgNanos / 1_000_000.0; // 纳秒转毫秒
    }

    /**
     * 获取所有已记录维度的平均 MSPT。
     *
     * @param count 每个维度的采样数量
     * @return 维度 key -> 平均 MSPT 的映射
     */
    public static Map<String, Double> getAllDimensionMspt(int count) {
        Map<String, Double> result = new HashMap<>();
        for (String key : DIMENSION_TICK_TIMES.keySet()) {
            double mspt = getAverageMspt(key, count);
            if (mspt > 0.0) {
                result.put(key, mspt);
            }
        }
        return result;
    }
}