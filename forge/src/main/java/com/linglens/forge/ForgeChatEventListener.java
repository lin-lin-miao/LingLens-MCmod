package com.linglens.forge;

import com.linglens.chat.ChatCache;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
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
 * 监听 {@link PlayerEvent.PlayerLoggedInEvent} 和 {@link PlayerEvent.PlayerLoggedOutEvent} 缓存登录/登出消息，
 * 监听 {@link AdvancementEvent.AdvancementEarnEvent} 缓存成就消息，
 * 监听 {@link LivingDeathEvent} 缓存死亡消息。
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

    /**
     * 玩家获得成就/进度事件处理。
     * <p>
     * 当玩家完成一个进度（包括成就）时触发，将消息缓存。
     * 使用 {@link AdvancementEvent.AdvancementEarnEvent} 确保仅在真正获得成就时触发。
     * </p>
     *
     * @param event 成就获得事件
     */
    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        try {
            // 事件实体必定是 ServerPlayer
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;

            Advancement advancement = event.getAdvancement();
            // 有些进度可能没有显示图标（如内部进度），跳过它们
            if (advancement.getDisplay() == null)
                return;

            // 获取成就标题（已经应用了格式化颜色）
            Component titleComponent = advancement.getDisplay().getTitle();
            String title = titleComponent.getString();

            // 构建消息：玩家名 + 成就标题
            String msg = player.getName().getString() + " §a获得了成就 [§6" + title + "§a]";
            String dim = player.level().dimension().location().toString();

            ChatCache.getInstance().addMessage(
                    player.getUUID(),
                    player.getName().getString(),
                    dim,
                    msg);
        } catch (Exception e) {
            LOGGER.error("[LingLens] 缓存成就消息失败", e);
        }
    }

    /**
     * 玩家死亡事件处理。
     * <p>
     * 当玩家死亡时触发，将死亡消息缓存。
     * </p>
     *
     * @param event 死亡事件
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;

            // 获取死亡消息（已包含玩家名和死亡原因）
            Component deathComponent = player.getCombatTracker().getDeathMessage();
            String deathMessage = deathComponent.getString();
            String dim = player.level().dimension().location().toString();

            ChatCache.getInstance().addMessage(
                    player.getUUID(),
                    player.getName().getString(),
                    dim,
                    deathMessage);
        } catch (Exception e) {
            LOGGER.error("[LingLens] 缓存死亡消息失败", e);
        }
    }
}