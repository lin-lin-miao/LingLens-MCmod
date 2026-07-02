package com.linglens.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
// LevelChunk、EntityTypeTest、ServerChunkCache 不再直接使用，保留以备未来扩展
import net.minecraft.world.level.entity.EntityTypeTest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 实体统计缓存管理器（状态机模式，全局统计版本，不区分维度）。
 * <p>
 * 在 READY 状态下，实体的 Join/Leave 事件会原子性地更新全局缓存（O(1) 复杂度）；
 * 当检测到事件风暴（如 /kill @e）时自动降级为 DIRTY 状态，暂停事件更新，
 * 下次查询时触发即时全量扫描并自动重建缓存。
 * <p>
 * 状态转移：
 * <pre>
 *   UNINIT ──rebuild()──▶ REBUILDING ───▶ READY
 *   READY  ──事件风暴/setDirty()──▶ DIRTY
 *   DIRTY  ──query()──▶ 即时扫描 + rebuild() ──▶ READY
 *   REBUILDING ──rebuild()完成──▶ READY
 * </pre>
 * <p>
 * 线程安全：使用 ConcurrentHashMap + AtomicReference + AtomicLong。
 * 所有公开方法均可安全地在服务端主线程或事件总线线程上调用。
 */
public class EntityStatsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    // ==================== 单例 ====================
    private static final EntityStatsCache INSTANCE = new EntityStatsCache();
    public static EntityStatsCache getInstance() { return INSTANCE; }

    // ==================== 状态枚举 ====================
    public enum State {
        UNINIT, READY, DIRTY, REBUILDING
    }

    // ==================== 状态管理 ====================
    private final AtomicReference<State> state = new AtomicReference<>(State.UNINIT);

    // ==================== 全局缓存数据结构 ====================
    /** 全局实体总数 */
    private final AtomicLong globalTotal = new AtomicLong(0);
    /** 按类别计数（全局） */
    private final ConcurrentHashMap<EntityCategory, AtomicLong> globalCatCount = new ConcurrentHashMap<>();
    /** 按实体类型ID计数（全局），如 minecraft:zombie */
    private final ConcurrentHashMap<String, AtomicLong> globalTypeCount = new ConcurrentHashMap<>();

    // ==================== 智能脏检查相关 ====================
    private final AtomicLong eventJoinCounter = new AtomicLong(0);
    private final AtomicLong eventLeaveCounter = new AtomicLong(0);
    private volatile long lastCheckTime = 0;
    private static final long EVENT_STORM_THRESHOLD = 2000; // 每秒事件阈值
    private static final long CHECK_INTERVAL_MS = 1000;     // 检查间隔

    // ==================== 元数据 ====================
    private volatile long lastUpdateTime = 0;

    private EntityStatsCache() {}

    // ================================================================
    //  1. 事件驱动更新
    // ================================================================

    /**
     * 实体加入世界时调用。仅在 READY 状态下更新全局缓存。
     */
    public void onEntityJoin(Entity entity) {
        if (!shouldTrack(entity)) return;
        eventJoinCounter.incrementAndGet();
        checkEventStorm();
        if (state.get() == State.READY) {
            doJoinUpdate(entity);
        }
    }

    /**
     * 实体离开世界时调用。仅在 READY 状态下更新全局缓存。
     */
    public void onEntityLeave(Entity entity) {
        if (!shouldTrack(entity)) return;
        eventLeaveCounter.incrementAndGet();
        checkEventStorm();
        if (state.get() == State.READY) {
            doLeaveUpdate(entity);
        }
    }

    // ================================================================
    //  2. 内部全局缓存更新（原子操作）
    // ================================================================

    private void doJoinUpdate(Entity entity) {
        EntityCategory category = classifyEntity(entity);
        String typeKey = getTypeKey(entity);

        globalTotal.incrementAndGet();
        globalCatCount.computeIfAbsent(category, k -> new AtomicLong(0)).incrementAndGet();
        globalTypeCount.computeIfAbsent(typeKey, k -> new AtomicLong(0)).incrementAndGet();
        lastUpdateTime = System.currentTimeMillis();
    }

    private void doLeaveUpdate(Entity entity) {
        EntityCategory category = classifyEntity(entity);
        String typeKey = getTypeKey(entity);

        globalTotal.updateAndGet(v -> Math.max(0, v - 1));

        AtomicLong catCounter = globalCatCount.get(category);
        if (catCounter != null) {
            catCounter.updateAndGet(v -> Math.max(0, v - 1));
        }

        AtomicLong typeCounter = globalTypeCount.get(typeKey);
        if (typeCounter != null) {
            typeCounter.updateAndGet(v -> Math.max(0, v - 1));
        }
        lastUpdateTime = System.currentTimeMillis();
    }

    // ================================================================
    //  3. 智能脏检查
    // ================================================================

    private void checkEventStorm() {
        long now = System.currentTimeMillis();
        long last = lastCheckTime;
        if (now - last >= CHECK_INTERVAL_MS) {
            synchronized (this) {
                if (now - lastCheckTime < CHECK_INTERVAL_MS) return;
                lastCheckTime = now;
                long joins = eventJoinCounter.getAndSet(0);
                long leaves = eventLeaveCounter.getAndSet(0);
                long totalEvents = joins + leaves;
                if (totalEvents > EVENT_STORM_THRESHOLD && state.get() == State.READY) {
                    setDirty("事件风暴检测: 每秒 " + totalEvents + " 次实体变化");
                }
                LOGGER.debug("[LingLens] 事件频率检查: Join={}, Leave={}, 阈值={}", joins, leaves, EVENT_STORM_THRESHOLD);
            }
        }
    }

    // ================================================================
    //  4. 全量重建缓存（全局）
    // ================================================================

    /**
     * 全量重建全局缓存：遍历所有已加载维度的所有已加载实体，重新填充全局统计。
     */
    public void rebuild(MinecraftServer server) {
        State current = state.get();
        if (current == State.REBUILDING) return;
        if (!state.compareAndSet(current, State.REBUILDING)) {
            State newCurrent = state.get();
            if (newCurrent == State.REBUILDING) return;
            if (!state.compareAndSet(newCurrent, State.REBUILDING)) return;
        }

        try {
            // 清空全局缓存
            globalTotal.set(0);
            globalCatCount.clear();
            globalTypeCount.clear();
            // 遍历所有已加载维度中的已加载区块
            for (ServerLevel level : server.getAllLevels()) {
                // 使用 EntityTypeTest 遍历所有已加载实体（替代已废弃的 chunk.getEntities() 和 level.getEntities().getAll()）
            for (Entity entity : level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> true)) {
                if (!shouldTrack(entity)) continue;
                EntityCategory category = classifyEntity(entity);
                String typeKey = getTypeKey(entity);

                globalTotal.incrementAndGet();
                globalCatCount.computeIfAbsent(category, k -> new AtomicLong(0)).incrementAndGet();
                globalTypeCount.computeIfAbsent(typeKey, k -> new AtomicLong(0)).incrementAndGet();
            }
            }

            // 重置事件计数器
            eventJoinCounter.set(0);
            eventLeaveCounter.set(0);
            lastCheckTime = System.currentTimeMillis();
            lastUpdateTime = System.currentTimeMillis();

            state.set(State.READY);
            LOGGER.info("[LingLens] 实体统计全局缓存重建完成，共 {} 个实体", globalTotal.get());
        } catch (Exception e) {
            LOGGER.error("[LingLens] 实体统计缓存重建失败，回退到 DIRTY 状态", e);
            state.set(State.DIRTY);
        }
    }

    // ================================================================
    //  5. 手动设脏 / 状态管理
    // ================================================================

    public void setDirty(String reason) {
        State oldState = state.get();
        if (oldState == State.UNINIT || oldState == State.DIRTY) return;
        if (state.compareAndSet(oldState, State.DIRTY)) {
            LOGGER.info("[LingLens] 实体统计缓存设为脏，原因: {}", reason);
        }
    }

    // ================================================================
    //  6. 查询入口（核心）
    // ================================================================

    /**
     * 查询实体统计信息。
     * <p>
     * 根据状态决定策略：
     * <ul>
     *   <li>READY → 直接从缓存读取（毫秒级）</li>
     *   <li>UNINIT / DIRTY → 即时全量扫描 + 自动重建缓存</li>
     *   <li>REBUILDING → 即时全量扫描（不触发二次重建）</li>
     * </ul>
     * <p>
     * 因 {@link QueryResult} 保留了维度层级，为兼容其结构，
     * 全局数据被映射到虚拟维度键 {@code "global"}。
     */
    public QueryResult query(MinecraftServer server) {
        State currentState = state.get();
        switch (currentState) {
            case READY:
                return buildResultFromCache();
            case UNINIT:
            case DIRTY:
                QueryResult live = scanAllEntities(server);
                rebuild(server); // 同步重建
                return live;
            case REBUILDING:
                return scanAllEntities(server);
            default:
                return scanAllEntities(server);
        }
    }

    // ================================================================
    //  7. 从全局缓存构建查询结果
    // ================================================================

    /**
     * 从全局缓存构建 QueryResult，填充到虚拟维度 "global" 下。
     */
    private QueryResult buildResultFromCache() {
        QueryResult result = new QueryResult();
        result.globalTotal = globalTotal.get();
        result.fromCache = true;
        result.cacheTime = lastUpdateTime;

        // 虚拟维度键
        String globalKey = "global";

        // 全局总数作为该维度的总数
        result.dimensionTotals.put(globalKey, globalTotal.get());

        // 类别计数
        Map<EntityCategory, Long> catLongMap = new LinkedHashMap<>();
        globalCatCount.forEach((cat, cnt) -> {
            long v = cnt.get();
            if (v > 0) catLongMap.put(cat, v);
        });
        result.dimCatCounts.put(globalKey, catLongMap);

        // 类型计数（Top 20）
        Map<String, Long> topTypes = globalTypeCount.entrySet().stream()
                .filter(e -> e.getValue().get() > 0)
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().get(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        result.dimTypeCounts.put(globalKey, topTypes);

        return result;
    }

    // ================================================================
    //  8. 即时全量扫描（全局保底）
    // ================================================================

    private QueryResult scanAllEntities(MinecraftServer server) {
        QueryResult result = new QueryResult();
        result.fromCache = false;
        result.cacheTime = System.currentTimeMillis();

        String globalKey = "global";
        int dimTotalCount = 0;
        Map<EntityCategory, Integer> catMap = new LinkedHashMap<>();
        Map<String, Integer> typeMap = new LinkedHashMap<>();

        for (ServerLevel level_ : server.getAllLevels()) {
            for (Entity entity : level_.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> true)) {
                if (!shouldTrack(entity)) continue;
                dimTotalCount++;
                result.globalTotal++;
                EntityCategory cat = classifyEntity(entity);
                catMap.merge(cat, 1, Integer::sum);
                String typeKey = getTypeKey(entity);
                typeMap.merge(typeKey, 1, Integer::sum);
            }
        }

        result.dimensionTotals.put(globalKey, (long) dimTotalCount);

        // 类别映射
        Map<EntityCategory, Long> catLongMap = new LinkedHashMap<>();
        catMap.forEach((k, v) -> catLongMap.put(k, v.longValue()));
        result.dimCatCounts.put(globalKey, catLongMap);

        // 类型 Top 20
        Map<String, Long> sortedTypes = typeMap.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(20)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().longValue(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        result.dimTypeCounts.put(globalKey, sortedTypes);

        return result;
    }

    // ================================================================
    //  9. 辅助方法
    // ================================================================

    private boolean shouldTrack(Entity entity) {
        return entity != null && entity.isAlive();
    }

    private String getTypeKey(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private EntityCategory classifyEntity(Entity entity) {
        if (entity instanceof Player)              return EntityCategory.PLAYER;
        if (entity instanceof ItemEntity)          return EntityCategory.ITEM;
        if (entity instanceof ExperienceOrb)       return EntityCategory.EXPERIENCE;
        if (entity instanceof Projectile)          return EntityCategory.PROJECTILE;
        if (entity instanceof AbstractMinecart || entity instanceof Boat) return EntityCategory.VEHICLE;
        if (entity instanceof Enemy)               return EntityCategory.MONSTER;
        if (entity instanceof Mob)                 return EntityCategory.ANIMAL;
        return EntityCategory.MISC;
    }

    // ================================================================
    //  10. 状态查询方法
    // ================================================================

    public State getState() { return state.get(); }
    public long getLastUpdateTime() { return lastUpdateTime; }

    public long getCachedGlobalTotal() {
        if (state.get() == State.UNINIT) return -1;
        return globalTotal.get();
    }

    public void reset() {
        globalTotal.set(0);
        globalCatCount.clear();
        globalTypeCount.clear();
        eventJoinCounter.set(0);
        eventLeaveCounter.set(0);
        lastCheckTime = 0;
        lastUpdateTime = 0;
        state.set(State.UNINIT);
        LOGGER.info("[LingLens] 实体统计缓存已重置");
    }
}