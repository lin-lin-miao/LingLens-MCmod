package com.linglens.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 此 Mixin 不再需要主动逻辑。<br>
 * 核心传送逻辑已迁移至 {@link ServerPlayerMixin#onLoad}，<br>
 * 在 {@code ServerPlayer.load()} 的 RETURN 阶段执行，时序更准确。<br>
 * 保留此 Mixin 仅作扩展预留。
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {
    // @Inject(
    //     method = "placeNewPlayer",
    //     at = @At("HEAD"),
    //     cancellable = false
    // )
    // private void onPlaceNewPlayer(Connection connection, ServerPlayer player, CallbackInfo ci) {
    //     // 此方法已留空，传送逻辑由 ServerPlayerMixin.onLoad 处理
    // }
}
