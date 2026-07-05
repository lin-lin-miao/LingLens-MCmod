package com.linglens.entity;

import com.linglens.config.ConfigManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.entity.EntityTypeTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 实体统计缓存管理器(状态机模式，支持分维度统计)。
 * <p>
 * 缓存按维度(minecraft:overworld / minecraft:the_nether 等)存储数据，
 * 同时维护全局汇总。
 * 在 READY 状态下，实体的 Join/Leave 事件会原子性地更新对应维度的缓存(O(1) 复杂度)；
 * 当检测到事件风暴(如 /kill @e)时自动降级为 DIRTY 状态，暂停事件更新，
 * 下次查询时触发即时全量扫描并自动重建缓存。
 * <p>
 * 状态转移：
 * 
 * <pre>
 *   UNINIT ──rebuild()──▶ REBUILDING ───▶ READY
 *   READY  ──事件风暴/setDirty()──▶ DIRTY
 *   DIRTY  ──query()──▶ 即时扫描 + rebuild() ──▶ READY
 *   REBUILDING ──rebuild()完成──▶ READY
 * </pre>
 */
public class EntityStatsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    // ==================== 单例 ====================
    private static final EntityStatsCache INSTANCE = new EntityStatsCache();

    public static EntityStatsCache getInstance() {
        return INSTANCE;
    }

    // ==================== 状态枚举 ====================
    public enum State {
        UNINIT, READY, DIRTY, REBUILDING
    }

    // ==================== 状态管理 ====================
    private final AtomicReference<State> state = new AtomicReference<>(State.UNINIT);

    // ==================== 维度级缓存数据结构 ====================
    /** 维度键 -> 该维度的统计容器 */
    private final ConcurrentHashMap<String, DimensionStats> dimensionStats = new ConcurrentHashMap<>();
    // 全局汇总不再单独维护，由 buildResultFromCache() 从各维度数据汇总得到

    /**
     * 单个维度的统计数据容器
     */
    private static class DimensionStats {
        final AtomicLong total = new AtomicLong(0);
        final ConcurrentHashMap<EntityCategory, AtomicLong> catCount = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> typeCount = new ConcurrentHashMap<>();
    }

    // ==================== 智能脏检查相关 ====================
    private final AtomicLong eventJoinCounter = new AtomicLong(0);
    private final AtomicLong eventLeaveCounter = new AtomicLong(0);
    private volatile long lastCheckTime = 0;
    // 从 ConfigManager 获取事件风暴阈值和检查间隔
    // 通过 ConfigManager 实例方法获取最新配置值（可在运行时通过命令修改）

    // ==================== 元数据 ====================
    private volatile long lastUpdateTime = 0;

    private EntityStatsCache() {
    }

    // ================================================================
    // 1. 事件驱动更新
    // ================================================================

    /**
     * 实体加入世界时调用。仅在 READY 状态下更新对应维度的缓存。
     */
    public void onEntityJoin(Entity entity) {
        if (!shouldTrack(entity))
            return;
        if (state.get() == State.READY) {
            eventJoinCounter.incrementAndGet();
            checkEventStorm();
            doJoinUpdate(entity);
        }
    }

    /**
     * 实体离开世界时调用。仅在 READY 状态下更新对应维度的缓存。
     */
    public void onEntityLeave(Entity entity) {
        if (!shouldTrack(entity))
            return;
        if (state.get() == State.READY) {
            eventLeaveCounter.incrementAndGet();
            checkEventStorm();
            doLeaveUpdate(entity);
        }
    }

    // ================================================================
    // 2. 内部缓存更新(原子操作，按维度)
    // ================================================================

    private void doJoinUpdate(Entity entity) {
        String dimKey = getDimensionKey(entity);
        EntityCategory category = classifyEntity(entity);
        String typeKey = getTypeKey(entity);

        // 获取或创建该维度的统计
        DimensionStats ds = dimensionStats.computeIfAbsent(dimKey, k -> new DimensionStats());

        // 维度级更新（仅维护维度级数据，全局汇总由 buildResultFromCache 动态计算）
        ds.total.incrementAndGet();
        ds.catCount.computeIfAbsent(category, k -> new AtomicLong(0)).incrementAndGet();
        ds.typeCount.computeIfAbsent(typeKey, k -> new AtomicLong(0)).incrementAndGet();

        lastUpdateTime = System.currentTimeMillis();
    }

    private void doLeaveUpdate(Entity entity) {
        String dimKey = getDimensionKey(entity);
        EntityCategory category = classifyEntity(entity);
        String typeKey = getTypeKey(entity);

        DimensionStats ds = dimensionStats.get(dimKey);
        if (ds == null)
            return;

        // 维度级减量（仅维护维度级数据）
        ds.total.updateAndGet(v -> Math.max(0, v - 1));
        AtomicLong catCounter = ds.catCount.get(category);
        if (catCounter != null)
            catCounter.updateAndGet(v -> Math.max(0, v - 1));
        AtomicLong typeCounter = ds.typeCount.get(typeKey);
        if (typeCounter != null)
            typeCounter.updateAndGet(v -> Math.max(0, v - 1));

        lastUpdateTime = System.currentTimeMillis();
    }

    // ================================================================
    // 3. 智能脏检查
    // ================================================================

    private void checkEventStorm() {
        ConfigManager cfg = ConfigManager.getInstance();
        long now = System.currentTimeMillis();
        long last = lastCheckTime;
        if (now - last >= cfg.getCheckIntervalMs()) {
            synchronized (this) {
                if (now - lastCheckTime < cfg.getCheckIntervalMs())
                    return;
                lastCheckTime = now;
                long joins = eventJoinCounter.getAndSet(0);
                long leaves = eventLeaveCounter.getAndSet(0);
                long totalEvents = joins + leaves;
                if (totalEvents > cfg.getEventStormThreshold() && state.get() == State.READY) {
                    setDirty("事件风暴检测: 每秒 " + totalEvents + " 次实体变化");
                }
                LOGGER.debug("[LingLens] 事件频率检查: Join={}, Leave={}, 阈值={}", joins, leaves,
                        cfg.getEventStormThreshold());
            }
        }
    }

    // ================================================================
    // 4. 全量重建缓存(分维度重建)
    // ================================================================

    /**
     * 全量重建所有维度的缓存：遍历所有已加载维度的已加载实体，重新填充维度级和全局汇总。
     */
    public void rebuild(MinecraftServer server) {
        State current = state.get();
        if (current == State.REBUILDING)
            return;
        if (!state.compareAndSet(current, State.REBUILDING)) {
            State newCurrent = state.get();
            if (newCurrent == State.REBUILDING)
                return;
            if (!state.compareAndSet(newCurrent, State.REBUILDING))
                return;
        }

        try {
            // 清空所有缓存（仅清空维度级数据，全局汇总由 buildResultFromCache 动态汇总）
            dimensionStats.clear();

            // 遍历所有已加载维度
            for (ServerLevel level : server.getAllLevels()) {
                String dimKey = level.dimension().location().toString();
                DimensionStats ds = new DimensionStats();

                for (Entity entity : level.getEntities(
                        EntityTypeTest.forClass(Entity.class),
                        e -> true)) {
                    if (!shouldTrack(entity))
                        continue;
                    EntityCategory category = classifyEntity(entity);
                    String typeKey = getTypeKey(entity);

                    // 填充维度级（仅维护维度级数据）
                    ds.total.incrementAndGet();
                    ds.catCount.computeIfAbsent(category, k -> new AtomicLong(0)).incrementAndGet();
                    ds.typeCount.computeIfAbsent(typeKey, k -> new AtomicLong(0)).incrementAndGet();
                }

                dimensionStats.put(dimKey, ds);
            }

            // 重置事件计数器
            eventJoinCounter.set(0);
            eventLeaveCounter.set(0);
            lastCheckTime = System.currentTimeMillis();
            lastUpdateTime = System.currentTimeMillis();

            state.set(State.READY);
            // 汇总全局总数
            long totalEntities = dimensionStats.values().stream().mapToLong(ds -> ds.total.get()).sum();
            LOGGER.info("[LingLens] 实体统计缓存(分维度)重建完成，共 {} 个维度，{} 个实体",
                    dimensionStats.size(), totalEntities);
        } catch (Exception e) {
            LOGGER.error("[LingLens] 实体统计缓存重建失败，回退到 DIRTY 状态", e);
            state.set(State.DIRTY);
        }
    }

    // ================================================================
    // 5. 手动设脏 / 状态管理
    // ================================================================

    public void setDirty(String reason) {
        State oldState = state.get();
        if (oldState == State.UNINIT || oldState == State.DIRTY)
            return;
        if (state.compareAndSet(oldState, State.DIRTY)) {
            LOGGER.info("[LingLens] 实体统计缓存设为脏，原因: {}", reason);
        }
    }

    // ================================================================
    // 6. 查询入口(核心)
    // ================================================================

    /**
     * 查询实体统计信息(按维度分组)。
     * <p>
     * 策略：
     * - READY：直接读缓存(毫秒级)
     * - UNINIT / DIRTY：调用 rebuild() 全量重建(一次扫描)后，从缓存读取结果
     * - REBUILDING：降级为即时扫描(避免等待并发重建)
     */
    public EntityQueryResult query(MinecraftServer server) {
        State currentState = state.get();
        switch (currentState) {
            case READY:
                return buildResultFromCache();
            case UNINIT:
            case DIRTY:
                // 全量重建(遍历所有实体)，重建后状态变为 READY，缓存填充完毕
                rebuild(server);
                // 从刚刚重建好的缓存中构建结果，相当于一次即时扫描 + 缓存填充
                return buildResultFromCache();
            case REBUILDING:
                // 重建中，不等待，降级为即时扫描
                return buildResultFromCache();
            default:
                rebuild(server);
                return buildResultFromCache();
        }
    }

    // ================================================================
    // 7. 从缓存构建查询结果(带维度数据)
    // ================================================================

    private EntityQueryResult buildResultFromCache() {
        EntityQueryResult result = new EntityQueryResult();
        result.fromCache = true;
        result.cacheTime = lastUpdateTime;

        // 遍历所有维度，同时汇总全局计数
        long globalTotal = 0;
        Map<EntityCategory, Long> globalCatAgg = new HashMap<>();
        Map<String, Long> globalTypeAgg = new HashMap<>();

        for (Map.Entry<String, DimensionStats> dimEntry : dimensionStats.entrySet()) {
            String dimKey = dimEntry.getKey();
            DimensionStats ds = dimEntry.getValue();

            long dimTotal = ds.total.get();
            result.dimensionTotals.put(dimKey, dimTotal);
            globalTotal += dimTotal;

            // 类别计数
            Map<EntityCategory, Long> catMap = new LinkedHashMap<>();
            for (Map.Entry<EntityCategory, AtomicLong> catEntry : ds.catCount.entrySet()) {
                long v = catEntry.getValue().get();
                if (v > 0) {
                    catMap.put(catEntry.getKey(), v);
                    globalCatAgg.merge(catEntry.getKey(), v, Long::sum);
                }
            }
            result.dimCatCounts.put(dimKey, catMap);

            // 类型计数(Top 20)
            Map<String, Long> topTypes = ds.typeCount.entrySet().stream()
                    .filter(e -> e.getValue().get() > 0)
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(20)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().get(),
                            (a, b) -> a,
                            LinkedHashMap::new));
            result.dimTypeCounts.put(dimKey, topTypes);

            // 汇总全局类型计数（用于 Top 20 排序）
            for (Map.Entry<String, AtomicLong> typeEntry : ds.typeCount.entrySet()) {
                globalTypeAgg.merge(typeEntry.getKey(), typeEntry.getValue().get(), Long::sum);
            }
        }

        // 填充全局汇总
        result.globalTotal = globalTotal;

        return result;
    }

    // ================================================================
    // 8. 即时全量扫描(保底，带维度分离)
    // ================================================================

    // private EntityQueryResult scanAllEntities(MinecraftServer server) {
    // EntityQueryResult result = new EntityQueryResult();
    // result.fromCache = false;
    // result.cacheTime = System.currentTimeMillis();

    // // 暂存扫描数据(维度->统计数据)
    // Map<String, Map<EntityCategory, Integer>> dimCatMap = new LinkedHashMap<>();
    // Map<String, Map<String, Integer>> dimTypeMap = new LinkedHashMap<>();

    // for (ServerLevel level : server.getAllLevels()) {
    // String dimKey = level.dimension().location().toString();
    // Map<EntityCategory, Integer> catMap = new LinkedHashMap<>();
    // Map<String, Integer> typeMap = new LinkedHashMap<>();
    // int dimCount = 0;

    // for (Entity entity : level.getEntities(
    // EntityTypeTest.forClass(Entity.class),
    // e -> true)) {
    // if (!shouldTrack(entity))
    // continue;
    // dimCount++;
    // result.globalTotal++;
    // EntityCategory cat = classifyEntity(entity);
    // catMap.merge(cat, 1, Integer::sum);
    // String typeKey = getTypeKey(entity);
    // typeMap.merge(typeKey, 1, Integer::sum);
    // }

    // result.dimensionTotals.put(dimKey, (long) dimCount);
    // dimCatMap.put(dimKey, catMap);
    // dimTypeMap.put(dimKey, typeMap);
    // }

    // // 构建类别和类型输出
    // for (Map.Entry<String, Long> dimEntry : result.dimensionTotals.entrySet()) {
    // String dimKey = dimEntry.getKey();

    // // 类别映射
    // Map<EntityCategory, Long> catLongMap = new LinkedHashMap<>();
    // Map<EntityCategory, Integer> catIntMap = dimCatMap.get(dimKey);
    // if (catIntMap != null) {
    // catIntMap.forEach((k, v) -> catLongMap.put(k, v.longValue()));
    // }
    // result.dimCatCounts.put(dimKey, catLongMap);

    // // 类型 Top 20
    // Map<String, Integer> typeIntMap = dimTypeMap.get(dimKey);
    // Map<String, Long> sortedTypes = new LinkedHashMap<>();
    // if (typeIntMap != null) {
    // sortedTypes = typeIntMap.entrySet().stream()
    // .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
    // .limit(20)
    // .collect(Collectors.toMap(
    // Map.Entry::getKey,
    // e -> e.getValue().longValue(),
    // (a, b) -> a,
    // LinkedHashMap::new));
    // }
    // result.dimTypeCounts.put(dimKey, sortedTypes);
    // }

    // return result;
    // }

    // ================================================================
    // 9. 辅助方法
    // ================================================================

    private boolean shouldTrack(Entity entity) {
        return entity != null;
    }

    private String getDimensionKey(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    private String getTypeKey(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private EntityCategory classifyEntity(Entity entity) {
        if (entity instanceof Player)
            return EntityCategory.PLAYER;
        if (entity instanceof ItemEntity)
            return EntityCategory.ITEM;
        if (entity instanceof ExperienceOrb)
            return EntityCategory.EXPERIENCE;
        if (entity instanceof Projectile)
            return EntityCategory.PROJECTILE;
        if (entity instanceof AbstractMinecart || entity instanceof Boat)
            return EntityCategory.VEHICLE;
        if (entity instanceof Enemy)
            return EntityCategory.MONSTER;
        if (entity instanceof Mob)
            return EntityCategory.ANIMAL;
        return EntityCategory.MISC;
    }

    // ================================================================
    // 10. 状态查询方法
    // ================================================================

    public State getState() {
        return state.get();
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public long getCachedGlobalTotal() {
        if (state.get() != State.READY)
            return -1;
        // 从各维度汇总全局总数
        return dimensionStats.values().stream().mapToLong(ds -> ds.total.get()).sum();
    }

    /**
     * 获取当前缓存中的所有维度键
     */
    public Set<String> getCachedDimensions() {
        if (state.get() != State.READY)
            return Collections.emptySet();
        return dimensionStats.keySet();
    }

    public void reset() {
        dimensionStats.clear();
        eventJoinCounter.set(0);
        eventLeaveCounter.set(0);
        lastCheckTime = 0;
        lastUpdateTime = 0;
        state.set(State.UNINIT);
        LOGGER.info("[LingLens] 实体统计缓存已重置");
    }

    /**
     * 移除指定维度的缓存数据（不再维护全局汇总，下次查询时自动汇总）。
     * 适用于维度卸载时精确清理缓存，减少脏标记带来的重建开销。
     *
     * @param dimKey 维度键(如 minecraft:overworld)
     */
    public void removeDimension(String dimKey) {
        DimensionStats ds = dimensionStats.remove(dimKey);
        if (ds == null) {
            LOGGER.debug("[LingLens] 维度 '{}' 无缓存数据，无需移除", dimKey);
            return;
        }

        long dimTotal = ds.total.get();
        LOGGER.debug("[LingLens] 已移除维度 '{}' 的缓存({} 个实体)", dimKey, dimTotal);
    }

}