package com.linglens.forge;

import com.linglens.entity.EntityStatsCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 实体事件监听器。
 * <p>
 * 使用 @SubscribeEvent 监听实体 Join/Leave 事件来更新实体统计缓存，
 * 监听 LevelEvent.Unload 在维度卸载时自动设脏，
 * 监听 ServerStartedEvent 在服务器启动时自动重建缓存。
 * <p>
 * 自动注册到 Mod 事件总线（因为使用了 @Mod.EventBusSubscriber）。
 */
@Mod.EventBusSubscriber(modid = "linglens")
public class ForgeEntityEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 实体加入世界时调用，更新缓存。
     *
     * @param event 实体加入世界事件
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            Entity entity = event.getEntity();
            EntityStatsCache.getInstance().onEntityJoin(entity);
        }
    }

    /**
     * 实体离开世界时调用，更新缓存。
     *
     * @param event 实体离开世界事件
     */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            Entity entity = event.getEntity();
            EntityStatsCache.getInstance().onEntityLeave(entity);
        }
    }

    /**
     * 维度卸载时标记缓存为脏。
     *
     * @param event 维度卸载事件
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            String dimKey = serverLevel.dimension().location().toString();
            EntityStatsCache.getInstance().removeDimension(dimKey);
            LOGGER.info("[LingLens] 维度 {} 卸载，移除缓存", dimKey);
        }
    }

    /**
     * 服务器启动完成时自动初始化缓存。
     *
     * @param event 服务器启动完成事件
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("[LingLens] 服务器启动，开始重建实体统计缓存...");
        EntityStatsCache.getInstance().rebuild(event.getServer());
    }
}