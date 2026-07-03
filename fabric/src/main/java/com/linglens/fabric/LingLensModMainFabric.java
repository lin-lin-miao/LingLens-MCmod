package com.linglens.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.storage.LevelResource;

import com.linglens.LingLensModMain;
import com.linglens.command.ModCommands;
import com.linglens.manager.TeleportManager;
import com.linglens.player.PlayerInfoQuery;

public final class LingLensModMainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Run our common setup.
        LingLensModMain.init();

        // 注册实体事件监听器（实体统计缓存）
        FabricEntityEventListener.register();

        // 注册玩家事件监听器（在线时长记录）
        FabricPlayerEventListener.register();

        // 服务器启动时：加载待传送数据 与 玩家在线时长
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TeleportManager.loadFromFile();
            
            // 设置玩家在线时长持久化路径（存档目录下的 linglens/playtime.json）
            PlayerInfoQuery.setDataFile(server.getWorldPath(LevelResource.ROOT).toFile());
            PlayerInfoQuery.loadFromFile();
        });

        // 服务器停止时：保存待传送数据 与 玩家在线时长
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TeleportManager.saveToFile();
            PlayerInfoQuery.saveToFile();
        });

        // 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ModCommands.register(dispatcher);
        });
    }
}
