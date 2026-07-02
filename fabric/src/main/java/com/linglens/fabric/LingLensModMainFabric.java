package com.linglens.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import com.linglens.LingLensModMain;
import com.linglens.command.ModCommands;
import com.linglens.manager.TeleportManager;

public final class LingLensModMainFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Run our common setup.
        LingLensModMain.init();

        // 注册实体事件监听器（实体统计缓存）
        FabricEntityEventListener.register();

        // 服务器启动时：加载待传送数据
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            TeleportManager.loadFromFile();
            
        });

        // 服务器停止时：保存待传送数据
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TeleportManager.saveToFile();
        });

        // 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ModCommands.register(dispatcher);
        });
    }
}
