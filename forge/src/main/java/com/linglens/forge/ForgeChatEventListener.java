package com.linglens.forge;

import com.linglens.chat.ChatCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 聊天消息事件监听器。
 * <p>
 * 使用 {@link Mod.EventBusSubscriber} 注解自动注册到 Forge 事件总线。
 * 监听 {@link ServerChatEvent} 来缓存玩家聊天消息，
 * 同时通过监听 {@link PlayerEvent.PlayerLoggedInEvent} 和
 * {@link PlayerEvent.PlayerLoggedOutEvent} 来缓存系统公告。
 * </p>
 */
@Mod.EventBusSubscriber(modid = "linglens")
public final class ForgeChatEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 玩家发送聊天消息事件处理。
     * <p>
     * 在玩家发送聊天消息时触发，缓存消息到 ChatCache。
     * </p>
     *
     * @param event 聊天消息事件
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        try {
            ServerPlayer player = event.getPlayer();
            if (player == null || player.level().isClientSide())
                return;

            String content = event.getMessage().getString();
            if (content == null || content.isBlank())
                return;

            String senderName = player.getName().getString();
            String dimension = player.level().dimension().location().toString();

            ChatCache.getInstance().addMessage(
                    player.getUUID(),
                    senderName,
                    dimension,
                    content);
        } catch (Exception e) {
            LOGGER.error("[LingLens] 缓存玩家聊天消息失败", e);
        }
    }

    /**
     * 玩家登录事件处理。
     * <p>
     * 将玩家登录消息缓存。
     * </p>
     */
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String msg = player.getName().getString() + " §e加入了游戏";
            ChatCache.getInstance().addMessage(
                    player.getUUID(),
                    player.getName().getString(),
                    player.level().dimension().location().toString(),
                    msg);
        }
    }

    /**
     * 玩家登出事件处理。
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String msg = player.getName().getString() + " §e离开了游戏";
            String dim = "minecraft:overworld";
            try {
                dim = player.level().dimension().location().toString();
            } catch (Exception ignored) {
            }
            ChatCache.getInstance().addMessage(
                    player.getUUID(),
                    player.getName().getString(),
                    dim,
                    msg);
        }
    }
}