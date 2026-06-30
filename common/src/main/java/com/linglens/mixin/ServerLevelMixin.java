package com.linglens.mixin;

import com.linglens.performance.DimensionTickTracker;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;

/**
 * ServerLevel Mixin，用于记录每个维度每次 tick 的耗时。
 * 在 ServerLevel.tick(BooleanSupplier) 方法 HEAD 记录开始时间，TAIL 记录结束时间并存入 DimensionTickTracker。
 * 在 1.20.1 中，ServerLevel.tick(BooleanSupplier) 的参数表示是否还有时间执行下一次迭代（用于降频）。
 * 时序：HEAD 在维度 tick 逻辑开始前，TAIL 在维度 tick 逻辑结束后。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Unique
    private static final Logger LINGLENS_LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 在 ServerLevel.tick(BooleanSupplier) 开始时注入，记录 tick 开始时间。
     *
     * @param shouldKeepTicking 是否继续 tick 的布尔供应器（Minecraft 原版参数）
     * @param ci                CallbackInfo
     */
    @SuppressWarnings("resource")
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        String dimensionKey = level.dimension().location().toString();
        DimensionTickTracker.onTickStart(dimensionKey);
    }

    /**
     * 在 ServerLevel.tick(BooleanSupplier) 结束时注入，记录本次 tick 耗时。
     *
     * @param shouldKeepTicking 是否继续 tick 的布尔供应器（Minecraft 原版参数）
     * @param ci                CallbackInfo
     */
    @SuppressWarnings("resource")
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        String dimensionKey = level.dimension().location().toString();
        DimensionTickTracker.onTickEnd(dimensionKey);
    }
}