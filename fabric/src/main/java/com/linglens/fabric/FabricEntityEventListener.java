package com.linglens.fabric;

import com.linglens.entity.EntityStatsCache;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 实体事件监听器。
 * <p>
 * 注册 EntityEvents.ENTITY_LOAD / ENTITY_UNLOAD 来更新实体统计缓存，
 * 注册 ServerLevelEvents.UNLOAD 在维度卸载时自动设脏，
 * 注册 ServerLifecycleEvents.SERVER_STARTED 在服务器启动时自动重建缓存。
 */
public class FabricEntityEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 注册所有实体相关事件。
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    public static void register() {
        // 1. 实体加入世界事件
        // ServerEntityEvents.ENTITY_LOAD.register();
        ServerEntityEvents.ENTITY_LOAD.register((Entity entity, ServerLevel world) -> {
            if (!world.isClientSide()) {
                EntityStatsCache cache = EntityStatsCache.getInstance();
                cache.onEntityJoin(entity);
            }
        });

        // 2. 实体离开世界事件
        ServerEntityEvents.ENTITY_UNLOAD.register((Entity entity, ServerLevel world) -> {
            if (!world.isClientSide()) {
                EntityStatsCache cache = EntityStatsCache.getInstance();
                cache.onEntityLeave(entity);
            }
        });

        // 3. 维度卸载时标记缓存为脏
        ServerWorldEvents.UNLOAD.register((server, Level) -> {
            if (!Level.isClientSide()) {
                String dimKey = Level.dimension().location().toString();
                EntityStatsCache.getInstance().removeDimension(dimKey);
                LOGGER.info("[LingLens] 维度 {} 卸载，移除缓存", dimKey);
            }
        });

        // 4. 服务器启动完成时自动初始化缓存
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[LingLens] 服务器启动，开始重建实体统计缓存...");
            EntityStatsCache.getInstance().rebuild(server);
        });

        LOGGER.info("[LingLens] Fabric 实体事件监听器已注册");
    }
}