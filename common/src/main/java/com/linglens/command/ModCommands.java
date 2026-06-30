package com.linglens.command;

import com.linglens.manager.TeleportManager;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModCommands {
        private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");


    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        MinecraftServer server = ctx.getSource().getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            builder.suggest(player.getName().getString());
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("linglens")
                        .then(Commands.literal("offline-tp")
                                .requires(src -> src.hasPermission(4))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        // 仅玩家名 → 主世界出生点
                                        .executes(ctx -> handleOfflineTp(ctx, null,
                                                Level.OVERWORLD.location().toString()))
                                        .then(Commands.argument("location", Vec3Argument.vec3())
                                                .executes(ctx -> {
                                                    Vec3 pos = Vec3Argument.getVec3(ctx, "location");
                                                    return handleOfflineTp(ctx, pos,
                                                            Level.OVERWORLD.location().toString());
                                                })
                                                .then(Commands
                                                        .argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> {
                                                            Vec3 pos = Vec3Argument.getVec3(ctx, "location");
                                                            ResourceLocation dimId = DimensionArgument
                                                                    .getDimension(ctx, "dimension").dimension()
                                                                    .location();
                                                            return handleOfflineTp(ctx, pos, dimId.toString());
                                                        })))))
                        // .then(Commands.literal("offline-tp"))
        // 命令结束位
        );
        LOGGER.info("[LingLens] 命令已注册(Command registered)");
    }

    /**
     * 处理离线传送命令的核心逻辑。
     * 
     * @param ctx         命令上下文
     * @param pos         目标坐标（为 null 时使用主世界出生点）
     * @param dimensionId 目标维度 ID（仅在 pos 非 null 时生效）
     */
    private static int handleOfflineTp(CommandContext<CommandSourceStack> ctx, Vec3 pos, String dimensionId) {
        String playerName = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = ctx.getSource().getServer();

        // 当坐标为空时，强制使用主世界出生点，忽略传入的 dimensionId
        double finalX, finalY, finalZ;
        String finalDimensionId;
        if (pos == null) {
            // 直接取主世界的共享出生点
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld == null) {
                ctx.getSource().sendFailure(Component.literal("无法获取主世界"));
                return 0;
            }
            BlockPos spawnPos = overworld.getSharedSpawnPos();
            finalX = spawnPos.getX() + 0.5;
            finalY = spawnPos.getY();
            finalZ = spawnPos.getZ() + 0.5;
            finalDimensionId = Level.OVERWORLD.location().toString();
        } else {
            // 用户明确提供了坐标，使用指定的坐标和维度
            finalX = pos.x;
            finalY = pos.y;
            finalZ = pos.z;
            finalDimensionId = dimensionId;
        }

        // 查找玩家 GameProfile
        GameProfile profile = server.getProfileCache().get(playerName).orElse(null);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到玩家: " + playerName));
            return 0;
        }
        UUID uuid = profile.getId();

        // 统一存入待传送列表，无论在线还是离线，下次登录时传送
        TeleportManager.addPending(uuid, finalX, finalY, finalZ, finalDimensionId, playerName);
        ctx.getSource().sendSuccess(
                () -> Component.literal(
                        "[LingLens] 已设置 " + playerName + " 下次上线传送至 " + finalDimensionId + " ("
                                + String.format("%.1f", finalX) + " " + String.format("%.1f", finalY) + " "
                                + String.format("%.1f", finalZ) + ")"),
                true);
        return 1;
    }

    /**
     * 根据维度 ID 字符串获取 ServerLevel。
     */
    // private static ServerLevel getLevelByDimensionId(MinecraftServer server, String dimensionId) {
    //     ResourceLocation loc = ResourceLocation.tryParse(dimensionId);
    //     if (loc == null)
    //         return null;
    //     ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, loc);
    //     return server.getLevel(dimensionKey);
    // }
}