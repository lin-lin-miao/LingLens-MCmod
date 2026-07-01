---
description: Entity quantity query and statistics
---

功能四：实体数量查询与统计（混合模式）
1. 功能定义（不变）
统计维度	说明
全局总数	所有维度已加载实体的总数
按维度分组	主世界 / 下界 / 末地 / 模组维度分别统计
按类别分组	怪物 / 动物 / 物品 / 经验球 / 玩家 / 投射物 / 载具 / 其他
按具体类型（可选）	特定实体类型的数量（如 minecraft:zombie、thermal:blitz）
2. 核心设计思路（状态机）
引入一个缓存状态机，控制缓存的生命周期：
┌─────────────────────────────────────────────────────────────────┐
│                        缓存状态机                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌──────────────┐    rebuild()    ┌──────────────┐            │
│   │   UNINIT     │ ──────────────► │   READY      │            │
│   │  (未初始化)   │                 │  (可用/就绪)  │            │
│   └──────────────┘                 └──────┬───────┘            │
│          ▲                                 │                     │
│          │                                 │                     │
│          │     事件风暴 / 手动设脏           │ 查询时自动重建     │
│          │                                 ▼                     │
│          │                            ┌──────────────┐         │
│          └─────────────────────────────│   DIRTY     │         │
│                                       │  (脏/失效)   │         │
│                                       └──────┬───────┘         │
│                                              │                  │
│                              执行即时统计后   │                  │
│                              自动调用rebuild  │                  │
│                                              ▼                  │
│                                       ┌──────────────┐         │
│                                       │  REBUILDING  │         │
│                                       │ (重建中/锁定) │         │
│                                       └──────────────┘         │
└─────────────────────────────────────────────────────────────────┘
状态说明：

状态	含义	查询行为	事件处理行为
UNINIT	缓存尚未初始化（服务器刚启动）	执行即时统计，同时重建缓存，跳转到 READY	正常监听，但缓存未就绪不响应
READY	缓存就绪，数据可信	直接读取缓存（毫秒级响应）	正常更新缓存
DIRTY	缓存已失效（事件风暴 / 手动设脏）	执行即时统计，统计完成后自动重建缓存并跳转回 READY	暂停事件更新（仅计数频率），不操作缓存
REBUILDING	正在重建中（防并发）	阻塞等待重建完成，或直接执行即时统计	事件继续监听但不更新缓存
3. 开发流程
步骤	模块	任务内容
1	common	定义 EntityStatsCache 类，包含：缓存数据结构（ConcurrentHashMap）、状态枚举、状态管理方法
2	common	实现事件驱动更新方法：onEntityJoin、onEntityLeave、onDimensionUnload
3	common	实现智能脏检查：在事件监听中统计事件频率，超过阈值时自动标记为 DIRTY
4	common	实现 rebuild() 全量重建方法（同步执行，遍历所有实体）
5	common	实现查询入口 query()：根据状态决定从缓存读取还是触发即时统计
6	common	设计命令：/linglens entity（普通统计）、/linglens entity rebuild（手动重建）、/linglens entity setdirty（手动设脏）、/linglens entity status（查看缓存状态）
7	fabric / forge	注册实体事件监听器（EntityJoinLevelEvent、EntityLeaveLevelEvent、LevelEvent.Unload）
8	测试	验证：正常统计、事件风暴自动降级、手动设脏后恢复
4. 核心代码实现（分模块）
4.1 common 模块：缓存管理器（核心）
```java
// ===== common/src/main/java/com/linglin/linglens/cache/EntityStatsCache.java =====
package com.linglin.linglens.cache;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Enemy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class EntityStatsCache {

    // ===== 单例 =====
    private static final EntityStatsCache INSTANCE = new EntityStatsCache();
    public static EntityStatsCache getInstance() { return INSTANCE; }

    // ===== 缓存状态枚举 =====
    public enum State {
        UNINIT,      // 未初始化
        READY,       // 就绪（数据可信）
        DIRTY,       // 脏（数据失效，需重建）
        REBUILDING   // 重建中（锁定）
    }

    // ===== 状态管理 =====
    private final AtomicReference<State> state = new AtomicReference<>(State.UNINIT);
    
    // ===== 缓存数据结构 =====
    // 维度 -> 总数
    private final ConcurrentHashMap<String, AtomicLong> dimTotal = new ConcurrentHashMap<>();
    // 维度 -> (类别 -> 计数)
    private final ConcurrentHashMap<String, ConcurrentHashMap<EntityCategory, AtomicLong>> catCount = new ConcurrentHashMap<>();
    // 维度 -> (实体类型ID -> 计数)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> typeCount = new ConcurrentHashMap<>();
    // 全局总数
    private final AtomicLong globalTotal = new AtomicLong(0);

    // ===== 智能脏检查相关 =====
    private final AtomicLong eventJoinCounter = new AtomicLong(0);
    private final AtomicLong eventLeaveCounter = new AtomicLong(0);
    private final AtomicLong lastCheckTime = new AtomicLong(0);
    private static final long EVENT_STORM_THRESHOLD = 2000;  // 每秒超过2000次事件视为风暴
    private static final long CHECK_INTERVAL_MS = 1000;      // 每秒检查一次

    // ===== 最近更新时间 =====
    private volatile long lastUpdateTime = 0;

    // ===== 实体类别枚举 =====
    public enum EntityCategory {
        MONSTER, ANIMAL, PLAYER, ITEM, EXPERIENCE, PROJECTILE, VEHICLE, MISC
    }

    // ===== 私有构造器 =====
    private EntityStatsCache() {}

    // ===== 1. 事件驱动更新（由事件监听器调用） =====
    public void onEntityJoin(Entity entity) {
        if (!shouldTrack(entity)) return;
        
        // 智能脏检查：计数事件频率
        long joinCount = eventJoinCounter.incrementAndGet();
        checkEventStorm();

        State currentState = state.get();
        // 只有在 READY 状态下才更新缓存
        if (currentState == State.READY) {
            doJoinUpdate(entity);
        }
        // DIRTY / REBUILDING / UNINIT 状态下不更新缓存
    }

    public void onEntityLeave(Entity entity) {
        if (!shouldTrack(entity)) return;
        
        long leaveCount = eventLeaveCounter.incrementAndGet();
        checkEventStorm();

        State currentState = state.get();
        if (currentState == State.READY) {
            doLeaveUpdate(entity);
        }
    }

    // ===== 2. 实际执行缓存更新的内部方法 =====
    private void doJoinUpdate(Entity entity) {
        String dimKey = getDimensionKey(entity);
        String typeKey = getTypeKey(entity);
        EntityCategory category = classifyEntity(entity);

        // 全局总数 +1
        globalTotal.incrementAndGet();

        // 维度总数 +1
        dimTotal.computeIfAbsent(dimKey, k -> new AtomicLong(0)).incrementAndGet();

        // 类别计数 +1
        catCount.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(category, k -> new AtomicLong(0))
                .incrementAndGet();

        // 类型计数 +1
        typeCount.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(typeKey, k -> new AtomicLong(0))
                 .incrementAndGet();

        lastUpdateTime = System.currentTimeMillis();
    }

    private void doLeaveUpdate(Entity entity) {
        String dimKey = getDimensionKey(entity);
        String typeKey = getTypeKey(entity);
        EntityCategory category = classifyEntity(entity);

        // 全局总数 -1（保底不为负）
        globalTotal.updateAndGet(v -> Math.max(0, v - 1));

        // 维度总数 -1
        AtomicLong dimCounter = dimTotal.get(dimKey);
        if (dimCounter != null) {
            dimCounter.updateAndGet(v -> Math.max(0, v - 1));
        }

        // 类别计数 -1
        ConcurrentHashMap<EntityCategory, AtomicLong> catMap = catCount.get(dimKey);
        if (catMap != null) {
            AtomicLong catCounter = catMap.get(category);
            if (catCounter != null) {
                catCounter.updateAndGet(v -> Math.max(0, v - 1));
            }
        }

        // 类型计数 -1
        ConcurrentHashMap<String, AtomicLong> typeMap = typeCount.get(dimKey);
        if (typeMap != null) {
            AtomicLong typeCounter = typeMap.get(typeKey);
            if (typeCounter != null) {
                typeCounter.updateAndGet(v -> Math.max(0, v - 1));
            }
        }

        lastUpdateTime = System.currentTimeMillis();
    }

    // ===== 3. 智能脏检查 =====
    private void checkEventStorm() {
        long now = System.currentTimeMillis();
        long last = lastCheckTime.get();
        
        if (now - last >= CHECK_INTERVAL_MS) {
            // 尝试更新检查时间（CAS 防止并发重置）
            if (lastCheckTime.compareAndSet(last, now)) {
                long joins = eventJoinCounter.getAndSet(0);
                long leaves = eventLeaveCounter.getAndSet(0);
                long totalEvents = joins + leaves;

                // 如果事件频率超过阈值，标记为脏
                if (totalEvents > EVENT_STORM_THRESHOLD) {
                    // 但只有在 READY 状态下才需要标记脏
                    if (state.get() == State.READY) {
                        setDirty("事件风暴检测: 每秒 " + totalEvents + " 次实体变化");
                    }
                }
            }
        }
    }

    // ===== 4. 全量重建（同步执行） =====
    public void rebuild(MinecraftServer server) {
        // 防止并发重建
        if (!state.compareAndSet(State.READY, State.REBUILDING) &&
            !state.compareAndSet(State.DIRTY, State.REBUILDING) &&
            !state.compareAndSet(State.UNINIT, State.REBUILDING)) {
            // 如果已经是 REBUILDING，直接返回
            return;
        }

        try {
            // 清空所有缓存
            dimTotal.clear();
            catCount.clear();
            typeCount.clear();
            globalTotal.set(0);

            // 遍历所有维度、所有实体
            for (ServerLevel level : server.getAllLevels()) {
                String dimKey = level.dimension().location().toString();
                for (Entity entity : level.getEntities().toList()) {
                    // 直接调用 doJoinUpdate（内部不触发状态检查）
                    String typeKey = getTypeKey(entity);
                    EntityCategory category = classifyEntity(entity);

                    globalTotal.incrementAndGet();
                    dimTotal.computeIfAbsent(dimKey, k -> new AtomicLong(0)).incrementAndGet();
                    catCount.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(category, k -> new AtomicLong(0))
                            .incrementAndGet();
                    typeCount.computeIfAbsent(dimKey, k -> new ConcurrentHashMap<>())
                             .computeIfAbsent(typeKey, k -> new AtomicLong(0))
                             .incrementAndGet();
                }
            }

            // 清除事件风暴计数器（避免误触发）
            eventJoinCounter.set(0);
            eventLeaveCounter.set(0);
            lastCheckTime.set(System.currentTimeMillis());
            lastUpdateTime = System.currentTimeMillis();

            // 标记为就绪
            state.set(State.READY);
            LingLens.LOGGER.info("实体统计缓存重建完成，共 {} 个实体", globalTotal.get());
        } catch (Exception e) {
            // 重建失败，回退到 DIRTY 状态
            state.set(State.DIRTY);
            LingLens.LOGGER.error("实体统计缓存重建失败", e);
        }
    }

    // ===== 5. 手动设脏 =====
    public void setDirty(String reason) {
        State oldState = state.getAndSet(State.DIRTY);
        if (oldState != State.DIRTY && oldState != State.UNINIT) {
            LingLens.LOGGER.info("实体统计缓存设为脏，原因: {}", reason);
        }
    }

    // ===== 6. 查询入口（核心） =====
    public QueryResult query(MinecraftServer server) {
        State currentState = state.get();

        // 情况1：READY → 直接读缓存
        if (currentState == State.READY) {
            return buildResultFromCache();
        }

        // 情况2：UNINIT / DIRTY → 执行即时统计，并自动触发重建
        if (currentState == State.UNINIT || currentState == State.DIRTY) {
            // 执行即时统计（全量扫描）
            QueryResult result = scanAllEntities(server);
            // 统计完成后，自动重建缓存（异步或同步，这里选择同步重建，但耗时较长）
            // 为了不阻塞命令响应，可考虑异步重建，但重建完成前后续查询仍会走即时统计
            rebuild(server);  // 同步重建，会占用一定时间
            return result;    // 返回本次扫描结果
        }

        // 情况3：REBUILDING → 降级为即时统计（不触发二次重建）
        if (currentState == State.REBUILDING) {
            return scanAllEntities(server);
        }

        // fallback（保底）
        return scanAllEntities(server);
    }

    // ===== 7. 从缓存读取数据 =====
    private QueryResult buildResultFromCache() {
        QueryResult result = new QueryResult();
        result.globalTotal = globalTotal.get();
        result.fromCache = true;
        result.cacheTime = lastUpdateTime;

        // 维度总数
        for (Map.Entry<String, AtomicLong> entry : dimTotal.entrySet()) {
            result.dimensionTotals.put(entry.getKey(), entry.getValue().get());
        }

        // 类别计数
        for (Map.Entry<String, ConcurrentHashMap<EntityCategory, AtomicLong>> dimEntry : catCount.entrySet()) {
            String dimKey = dimEntry.getKey();
            for (Map.Entry<EntityCategory, AtomicLong> catEntry : dimEntry.getValue().entrySet()) {
                result.dimCatCounts.computeIfAbsent(dimKey, k -> new HashMap<>())
                                    .put(catEntry.getKey(), catEntry.getValue().get());
            }
        }

        // 类型计数（仅返回 Top 20，避免数据量过大）
        for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> dimEntry : typeCount.entrySet()) {
            String dimKey = dimEntry.getKey();
            Map<String, Long> topTypes = new LinkedHashMap<>();
            dimEntry.getValue().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(20)
                    .forEach(e -> topTypes.put(e.getKey(), e.getValue().get()));
            result.dimTypeCounts.put(dimKey, topTypes);
        }

        return result;
    }

    // ===== 8. 即时统计（全量扫描保底） =====
    private QueryResult scanAllEntities(MinecraftServer server) {
        QueryResult result = new QueryResult();
        result.fromCache = false;
        result.cacheTime = System.currentTimeMillis();

        for (ServerLevel level : server.getAllLevels()) {
            String dimKey = level.dimension().location().toString();
            int dimTotal = 0;
            Map<EntityCategory, Integer> catMap = new HashMap<>();
            Map<String, Integer> typeMap = new HashMap<>();

            for (Entity entity : level.getEntities().toList()) {
                dimTotal++;
                result.globalTotal++;
                EntityCategory cat = classifyEntity(entity);
                catMap.merge(cat, 1, Integer::sum);
                String typeKey = getTypeKey(entity);
                typeMap.merge(typeKey, 1, Integer::sum);
            }

            result.dimensionTotals.put(dimKey, dimTotal);
            result.dimCatCounts.put(dimKey, new HashMap<>(catMap));
            // 类型只保留 Top 20
            Map<String, Integer> sortedTypes = typeMap.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(20)
                    .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
            result.dimTypeCounts.put(dimKey, new LinkedHashMap<>(sortedTypes));
        }

        return result;
    }

    // ===== 9. 辅助方法 =====
    private boolean shouldTrack(Entity entity) {
        return entity != null && entity.isAlive();
    }

    private String getDimensionKey(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    private String getTypeKey(Entity entity) {
        return EntityType.getKey(entity.getType()).toString();
    }

    private EntityCategory classifyEntity(Entity entity) {
        if (entity instanceof Player) return EntityCategory.PLAYER;
        if (entity instanceof ItemEntity) return EntityCategory.ITEM;
        if (entity instanceof ExperienceOrb) return EntityCategory.EXPERIENCE;
        if (entity instanceof Projectile) return EntityCategory.PROJECTILE;
        if (entity instanceof VehicleEntity) return EntityCategory.VEHICLE;
        if (entity instanceof Enemy) return EntityCategory.MONSTER;
        if (entity instanceof Mob) return EntityCategory.ANIMAL;
        return EntityCategory.MISC;
    }

    // ===== 10. 查询结果封装 =====
    public static class QueryResult {
        public long globalTotal = 0;
        public boolean fromCache = false;
        public long cacheTime = 0;
        public final Map<String, Long> dimensionTotals = new LinkedHashMap<>();
        public final Map<String, Map<EntityCategory, Long>> dimCatCounts = new LinkedHashMap<>();
        public final Map<String, Map<String, Long>> dimTypeCounts = new LinkedHashMap<>();

        public String toReadableString() {
            StringBuilder sb = new StringBuilder();
            sb.append("§e=== 灵棱枢 实体统计");
            if (fromCache) {
                sb.append(" §7(缓存)§e");
            } else {
                sb.append(" §7(即时扫描)§e");
            }
            sb.append(" ===\n");
            sb.append("§7总计: §f").append(globalTotal).append(" 个实体\n");

            for (Map.Entry<String, Long> entry : dimensionTotals.entrySet()) {
                String dimName = entry.getKey().replace("minecraft:", "");
                sb.append("§7维度 §f").append(dimName).append("§7: §f").append(entry.getValue()).append("\n");
                
                Map<EntityCategory, Long> catMap = dimCatCounts.get(entry.getKey());
                if (catMap != null) {
                    for (Map.Entry<EntityCategory, Long> catEntry : catMap.entrySet()) {
                        sb.append("  §7- ").append(catEntry.getKey().name().toLowerCase())
                          .append(": §f").append(catEntry.getValue()).append("\n");
                    }
                }
            }

            if (!fromCache) {
                sb.append("\n§7[缓存状态: 已自动重建]");
            }
            return sb.toString();
        }
    }
}
```
4.2 common 模块：命令注册
    实体统计主命令
    手动重建缓存
    手动设脏
    查看缓存状态

4.3 fabric / forge 模块：事件监听器注册
Fabric 实现：
```java
public class FabricEntityEventListener {
    
    public static void register() {
        EntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!world.isClientSide) {
                EntityStatsCache.getInstance().onEntityJoin(entity);
            }
        });
        
        EntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!world.isClientSide) {
                EntityStatsCache.getInstance().onEntityLeave(entity);
            }
        });
        
        LevelEvents.UNLOAD.register((level) -> {
            if (!level.isClientSide) {
                // 维度卸载时标记为脏（简化处理，因为维度内实体已全部 leave）
                EntityStatsCache.getInstance().setDirty("维度 " + level.dimension().location() + " 卸载");
            }
        });

        // 服务器启动完成时自动初始化缓存
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            EntityStatsCache.getInstance().rebuild(server);
        });
    }
}
```
Forge 实现（类似，使用 @SubscribeEvent）：
```java
@Mod.EventBusSubscriber
public class ForgeEntityEventListener {
    
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            EntityStatsCache.getInstance().onEntityJoin(event.getEntity());
        }
    }
    
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            EntityStatsCache.getInstance().onEntityLeave(event.getEntity());
        }
    }
    
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            EntityStatsCache.getInstance().setDirty("维度 " + serverLevel.dimension().location() + " 卸载");
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartedEvent event) {
        EntityStatsCache.getInstance().rebuild(event.getServer());
    }
}
```
5. 完整工作流程伪代码
┌─────────────────────────────────────────────────────────────────────────┐
│                        实体统计完整工作流程                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  【服务器启动】                                                           │
│  └─ ServerStartedEvent 触发                                              │
│       └─ EntityStatsCache.rebuild(server)                               │
│            ├─ 状态: UNINIT → REBUILDING                                 │
│            ├─ 遍历所有维度、所有实体，填充缓存                             │
│            └─ 状态: REBUILDING → READY                                  │
│                                                                          │
│  【运行时-事件驱动】                                                     │
│  └─ 实体加入/离开事件触发                                                │
│       ├─ 检查事件频率（每秒计数）                                         │
│       │    └─ 若 > 2000次/秒 → setDirty("事件风暴")                      │
│       ├─ 若状态 == READY → 更新缓存（O(1)）                              │
│       └─ 若状态 == DIRTY/UNINIT/REBUILDING → 不更新缓存                  │
│                                                                          │
│  【管理员查询】                                                          │
│  └─ 执行 /linglens entity                                               │
│       ├─ 调用 EntityStatsCache.query(server)                           │
│       │    ├─ 若状态 == READY → 从缓存读取 → 返回（毫秒级）              │
│       │    ├─ 若状态 == UNINIT 或 DIRTY → 执行即时统计                   │
│       │    │    ├─ 全量遍历所有实体（O(N)）                              │
│       │    │    ├─ 返回本次统计结果（可接受延迟）                         │
│       │    │    └─ 自动调用 rebuild(server) → 状态 → READY              │
│       │    └─ 若状态 == REBUILDING → 执行即时统计（保底）                │
│       └─ 发送结果给玩家（含"缓存"或"即时扫描"标识）                       │
│                                                                          │
│  【手动干预】                                                            │
│  └─ /linglens entity setdirty → 状态: READY → DIRTY（下次查询重建）     │
│  └─ /linglens entity rebuild → 强制重建缓存（状态 → READY）             │
│  └─ /linglens entity status → 查看当前状态和最后更新时间                 │
└─────────────────────────────────────────────────────────────────────────┘
6. 状态转换表
当前状态	触发条件	新状态	说明
UNINIT	rebuild() 调用	REBUILDING → READY	服务器启动时自动执行
UNINIT	查询 query()	→ 即时统计 → REBUILDING → READY	首次查询自动初始化
READY	事件频率 > 阈值	DIRTY	智能脏检查自动触发
READY	手动 setDirty()	DIRTY	管理员主动设脏
READY	手动 rebuild()	REBUILDING → READY	强制重建
DIRTY	查询 query()	→ 即时统计 → REBUILDING → READY	查询即恢复
REBUILDING	查询 query()	不变（执行即时统计但不重建）	防并发双重重建
任意	维度卸载事件	DIRTY	维度卸载标记脏
7. 性能总结
场景	惰性统计（READY）	即时统计保底（DIRTY/UNINIT）
正常查询	< 0.1ms（内存读取）	20~50ms（全量遍历，根据实体数）
事件更新开销	每次 Join/Leave O(1)，约 0.5~2μs	不适用（不维护缓存）
事件风暴（/kill @e）	自动标记 DIRTY，暂停缓存更新	直接全量扫描，无额外事件处理
内存占用	约 20~50KB	0（无缓存）
数据准确性	接近 100%（依赖事件完整性）	100%（实时扫描）
8. 配置选项（可选）
```properties
# ===== config/linglens/linglens.properties =====
# 实体统计模式: auto（默认）/ lazy（强制惰性）/ instant（强制即时）
entity_stats_mode=auto

# 事件风暴阈值（每秒事件数，超过则自动设脏）
entity_storm_threshold=2000

# 是否在服务器启动时自动重建缓存
auto_rebuild_on_start=true

# 查询时若状态为DIRTY，是否自动重建（true）还是仅返回即时统计结果（false）
auto_rebuild_on_query=true
```
这个设计实现了：

默认惰性统计：绝大多数查询走缓存，毫秒级响应。

智能脏检查：事件风暴自动降级，避免缓存维护本身成为性能瓶颈。

即时统计保底：缓存失效时，查询自动降级为全量扫描，保证数据永远可用。

手动控制：管理员可通过命令主动设脏或重建，灵活调优。




