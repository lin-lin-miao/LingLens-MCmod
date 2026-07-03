 package com.linglens.fabric;

import com.linglens.player.PlayerInfoQuery;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 平台玩家事件监听器。
 * <p>
 * 注册 {@link ServerPlayConnectionEvents#JOIN} 和 {@link ServerPlayConnectionEvents#DISCONNECT}
 * 事件来记录玩家的登录/登出时间，用于计算在线时长。
 * 与 {@link FabricEntityEventListener} 配合使用。
 * </p>
 *
 * <h3>事件监听</h3>
 * <ul>
 *   <li>JOIN - 玩家加入时记录登录时间戳</li>
 *   <li>DISCONNECT - 玩家断开时计算本次时长并累加</li>
 * </ul>
 */
public final class FabricPlayerEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 注册玩家连接事件。
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    public static void register() {
        // 玩家加入服务器
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!player.level().isClientSide()) {
                PlayerInfoQuery.recordLogin(player.getUUID());
                LOGGER.debug("[LingLens] Fabric 玩家 {} 登录，已记录时间戳", player.getGameProfile().getName());
            }
        });

        // 玩家断开连接
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player != null && !player.level().isClientSide()) {
                PlayerInfoQuery.recordLogout(player.getUUID());
                LOGGER.debug("[LingLens] Fabric 玩家 {} 登出，已记录在线时长", player.getGameProfile().getName());
            }
        });

        LOGGER.debug("[LingLens] Fabric 玩家事件监听器已注册");
    }
}