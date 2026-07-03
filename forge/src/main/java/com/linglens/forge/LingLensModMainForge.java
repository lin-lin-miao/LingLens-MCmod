package com.linglens.forge;

import com.linglens.LingLensModMain;
import com.linglens.command.ModCommands;
import com.linglens.manager.IdleTickManager;
import com.linglens.manager.TeleportManager;
import com.linglens.player.PlayerInfoQuery;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(LingLensModMain.MOD_ID)
public final class LingLensModMainForge {
    public LingLensModMainForge() {
        // Run our common setup.
        LingLensModMain.init();

        // 注册事件监听
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();

        // 加载待传送数据
        TeleportManager.loadFromFile();

        // 设置玩家在线时长持久化路径（存档目录下的 linglens/playtime.json）
        // LevelResource.ROOT 对应存档根目录
        PlayerInfoQuery.setDataFile(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile());
        PlayerInfoQuery.loadFromFile();

        IdleTickManager.scanPendingIfNeeded();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 保存待传送数据
        TeleportManager.saveToFile();

        // 保存玩家在线时长
        PlayerInfoQuery.saveToFile();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }
}
