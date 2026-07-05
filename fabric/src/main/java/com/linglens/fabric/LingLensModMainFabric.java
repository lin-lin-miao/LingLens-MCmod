package com.linglens.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;

import com.linglens.LingLensModMain;
import com.linglens.chat.ChatCache;
import com.linglens.command.ModCommands;
import com.linglens.manager.TeleportManager;
import com.linglens.manager.IdleTickManager;
import com.linglens.player.PlayerInfoQuery;

public final class LingLensModMainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        LingLensModMain.init();

        FabricEntityEventListener.register();
        FabricPlayerEventListener.register();
        FabricChatEventListener.register(); // 注册聊天消息事件监听器

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            File WorldDirectory = server.getWorldPath(LevelResource.ROOT).toFile();

            // 设置待传送数据持久化路径（存档目录下的 linglens/pending_teleports.json）
            // setWorldDirectory 内部会自动调用 loadFromFile()
            TeleportManager.setWorldDirectory(WorldDirectory);

            // 设置玩家在线时长持久化路径（存档目录下的 linglens/playtime.json）
            PlayerInfoQuery.setDataFile(WorldDirectory);
            PlayerInfoQuery.loadFromFile();

            // 设置聊天消息持久化路径
            ChatCache.setDataFile(WorldDirectory);

            IdleTickManager.scanPendingIfNeeded();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TeleportManager.saveToFile();
            PlayerInfoQuery.saveToFile();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ModCommands.register(dispatcher);
        });
    }
}
