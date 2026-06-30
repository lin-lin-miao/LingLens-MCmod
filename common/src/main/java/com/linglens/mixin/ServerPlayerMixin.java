package com.linglens.mixin;

import com.linglens.data.PendingTeleport;
import com.linglens.manager.TeleportManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");
/**
 * 离线传送主要实现
 * @param compound
 * @param ci
 */
    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void onReadAdditionalSaveData(CompoundTag compound, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        PendingTeleport pending = TeleportManager.getAndRemove(player.getUUID());
        if (pending != null) {
            player.setPos(pending.getX(), pending.getY(), pending.getZ());
            player.fallDistance = 0.0f;
            MinecraftServer server = player.getServer();
            if (server != null) {
                ResourceLocation dimLoc = ResourceLocation.tryParse(pending.getDimension());
                if (dimLoc != null) {
                    ResourceKey<Level> dimKey = ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
                    ServerLevel targetLevel = server.getLevel(dimKey);
                    if (targetLevel != null && !targetLevel.dimension().location()
                            .equals(player.level().dimension().location())) {
                        player.setServerLevel(targetLevel);
                        compound.putString("Dimension", pending.getDimension());
                        LOGGER.info("LingLens: 跨维度调整 - 从 {} 切换到 {}",
                                player.level().dimension().location(), dimLoc);
                    }
                }
            }
            LOGGER.info("LingLens: player: {} Transferred to {} ({}, {}, {})",
                    player.getName().getString(),
                    pending.getDimension(),
                    String.format("%.2f", pending.getX()),
                    String.format("%.2f", pending.getY()),
                    String.format("%.2f", pending.getZ()));
        }
    }
}