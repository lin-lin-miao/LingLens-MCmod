package com.linglens.fabric;

import com.linglens.chat.ChatCache;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 聊天消息事件监听器。
 * <p>
 * 注册 {@link ServerMessageEvents#CHAT_MESSAGE} 事件，
 * 当玩家在聊天栏发送消息时，自动缓存消息内容、发送者、维度和时间戳。
 * 还监听 {@link ServerMessageEvents#GAME_MESSAGE} 捕获系统/游戏消息（如玩家进出、成就、死亡消息等）。
 * </p>
 */
public final class FabricChatEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 注册聊天事件监听器。
     * 应在 ModInitializer.onInitialize() 中调用。
     */
    public static void register() {
        // 1. 监听玩家聊天消息
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender == null || sender.level().isClientSide())
                return;

            try {
                // Minecraft 1.20.1: PlayerChatMessage 使用 signedBody().content() 获取 Component
                String content = message.signedBody().content();
                String senderName = sender.getName().getString();
                String dimension = sender.level().dimension().location().toString();

                ChatCache.getInstance().addMessage(
                        sender.getUUID(),
                        senderName,
                        dimension,
                        content);
            } catch (Exception e) {
                LOGGER.error("[LingLens] 缓存玩家聊天消息失败", e);
            }
        });

        // 2. 监听系统/游戏消息（如玩家登录/登出、成就、死亡消息等）
        ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
            try {
                String content = message.getString();
                if (content == null || content.isBlank())
                    return;

                // 系统消息使用一个固定的虚拟发送者信息
                ChatCache.getInstance().addMessage(
                        new java.util.UUID(0, 1),
                        "§8System",
                        "minecraft:overworld",
                        content);
            } catch (Exception e) {
                LOGGER.error("[LingLens] 缓存系统消息失败", e);
            }
        });

        LOGGER.info("[LingLens] Fabric 聊天事件监听器已注册");
    }
}