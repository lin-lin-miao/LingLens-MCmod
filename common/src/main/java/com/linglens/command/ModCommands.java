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

public class ModCommands {

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
                                        .executes(ctx -> handleOfflineTp(ctx, null,
                                                Level.OVERWORLD.location().toString()))
                                        .then(Commands.argument("location", Vec3Argument.vec3())
                                                .executes(ctx -> {
                                                    Vec3 pos = Vec3Argument.getVec3(ctx, "location");
                                                    return handleOfflineTp(ctx, pos,
                                                            Level.OVERWORLD.location().toString());
                                                })
                                                .then(Commands.literal("in")
                                                        .then(Commands
                                                                .argument("dimension", DimensionArgument.dimension())
                                                                .executes(ctx -> {
                                                                    Vec3 pos = Vec3Argument.getVec3(ctx, "location");
                                                                    ResourceLocation dimId = DimensionArgument
                                                                            .getDimension(ctx, "dimension").dimension()
                                                                            .location();
                                                                    return handleOfflineTp(ctx, pos, dimId.toString());
                                                                }))))
                                        .then(Commands.literal("in")
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> {
                                                            ResourceLocation dimId = DimensionArgument
                                                                    .getDimension(ctx, "dimension").dimension()
                                                                    .location();
                                                            return handleOfflineTp(ctx, null, dimId.toString());
                                                        }))))));
    }

    private static int handleOfflineTp(CommandContext<CommandSourceStack> ctx, Vec3 pos, String dimensionId) {
        String playerName = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = ctx.getSource().getServer();

        // 如果坐标为空，获取目标维度的世界出生点
        double finalX, finalY, finalZ;
        if (pos == null) {
            ServerLevel targetLevel = getLevelByDimensionId(server, dimensionId);
            if (targetLevel == null) {
                ctx.getSource().sendFailure(Component.literal("维度不存在: " + dimensionId));
                return 0;
            }
            BlockPos spawnPos = targetLevel.getSharedSpawnPos();
            finalX = spawnPos.getX() + 0.5;
            finalY = spawnPos.getY();
            finalZ = spawnPos.getZ() + 0.5;
        } else {
            finalX = pos.x;
            finalY = pos.y;
            finalZ = pos.z;
        }

        // 查找玩家 GameProfile
        GameProfile profile = server.getProfileCache().get(playerName).orElse(null);
        if (profile == null) {
            ctx.getSource().sendFailure(Component.literal("未找到玩家: " + playerName));
            return 0;
        }
        UUID uuid = profile.getId();

        // 统一存入待传送列表，无论在线还是离线，下次登录时传送
        TeleportManager.addPending(uuid, finalX, finalY, finalZ, dimensionId, playerName);
        ctx.getSource().sendSuccess(
                () -> Component.literal(
                        "已设置 " + playerName + " 下次上线传送至维度 " + dimensionId + " 的 (" + String.format("%.1f", finalX)
                                + ", " + String.format("%.1f", finalY) + ", " + String.format("%.1f", finalZ) + ")"),
                true);
        return 1;
    }

    private static ServerLevel getLevelByDimensionId(MinecraftServer server, String dimensionId) {
        ResourceLocation loc = ResourceLocation.tryParse(dimensionId);
        if (loc == null)
            return null;
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, loc);
        return server.getLevel(dimensionKey);
    }
}