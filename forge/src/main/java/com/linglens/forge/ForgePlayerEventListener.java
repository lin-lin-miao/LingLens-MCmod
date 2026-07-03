package com.linglens.forge;

import com.linglens.manager.IdleTickManager;
import com.linglens.player.PlayerInfoQuery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 平台玩家事件监听器。
 * <p>
 * 使用 {@link Mod.EventBusSubscriber} 注解自动注册到 Forge 事件总线，
 * 无需手动注册。
 * 监听玩家登录和登出事件，用于记录在线时长。
 * 与 {@link ForgeEntityEventListener} 配合使用。
 * </p>
 *
 * <h3>事件监听</h3>
 * <ul>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} - 玩家登录时记录登录时间戳</li>
 *   <li>{@link PlayerEvent.PlayerLoggedOutEvent} - 玩家登出时计算时长并累加</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "linglens")
public final class ForgePlayerEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 玩家登录事件处理。
     * <p>
     * 在玩家成功登录服务器时触发，记录登录时间戳。
     * </p>
     *
     * @param event 玩家登录事件
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInfoQuery.recordLogin(player.getUUID());
            LOGGER.debug("[LingLens] Forge 玩家 {} 登录，已记录时间戳", player.getGameProfile().getName());
        }
    }

    /**
     * 玩家登出事件处理。
     * <p>
     * 在玩家断开连接时触发，计算本次在线时长并累加到总时长中。
     * </p>
     *
     * @param event 玩家登出事件
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInfoQuery.recordLogout(player.getUUID());
            LOGGER.debug("[LingLens] Forge 玩家 {} 登出，已记录在线时长", player.getGameProfile().getName());
        }
    }

    /**
     * 服务器 tick 事件处理（自动保存）。
     * <p>
     * 在服务器 tick 结束时调用 {@link PlayerInfoQuery#onServerTick()}，
     * 实现每 60 秒自动保存在线时长数据。
     * </p>
     *
     * @param event 服务器 tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            IdleTickManager.onTickEnd(event.getServer());
        }
    }


}