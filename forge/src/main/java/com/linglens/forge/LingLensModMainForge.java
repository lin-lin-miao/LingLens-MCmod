package com.linglens.forge;

import com.linglens.LingLensModMain;
import com.linglens.command.ModCommands;
import com.linglens.manager.TeleportManager;
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
        // 加载待传送数据
        TeleportManager.loadFromFile();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 保存待传送数据
        TeleportManager.saveToFile();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }
}
