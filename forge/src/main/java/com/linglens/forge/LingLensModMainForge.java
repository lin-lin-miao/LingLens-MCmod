package com.linglens.forge;

import java.io.File;

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
        LingLensModMain.init();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        File WorldDirectory = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();

        // 设置待传送数据持久化路径（存档目录下的 linglens/pending_teleports.json）
        // setWorldDirectory 内部会自动调用 loadFromFile()
        TeleportManager.setWorldDirectory(WorldDirectory);

        // 设置玩家在线时长持久化路径（存档目录下的 linglens/playtime.json）
        PlayerInfoQuery.setDataFile(WorldDirectory);
        PlayerInfoQuery.loadFromFile();

        IdleTickManager.scanPendingIfNeeded();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        TeleportManager.saveToFile();
        PlayerInfoQuery.saveToFile();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }
}
