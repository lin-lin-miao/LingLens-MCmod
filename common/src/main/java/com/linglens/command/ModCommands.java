package com.linglens.command;

import com.linglens.manager.TeleportManager;
import com.linglens.performance.PerformanceQuery;
import com.linglens.performance.PerformanceResult;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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

import java.util.Map;
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
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("linglens");
        LiteralArgumentBuilder<CommandSourceStack> offline_tp = Commands.literal("offline-tp");
        {// 离线玩家传送命令注册
            offline_tp.requires(src -> src.hasPermission(4))
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
                                            }))));
        }
        root.then(offline_tp);

        // ========== TPS 子命令 ==========
        // 所有人可执行，无需权限
        root.then(Commands.literal("tps")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    PerformanceResult result = PerformanceQuery.query(server);

                    StringBuilder sb = new StringBuilder();
                    sb.append("§eTPS: §a").append(String.format("%.2f", result.tps())).append("\n");

                    if (result.dimensionMspt() != null && !result.dimensionMspt().isEmpty()) {
                        // 寻找最高MSPT维度（值最大，即最卡的维度）
                        Map.Entry<String, Double> maxEntry = result.dimensionMspt().entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .orElse(null);
                        if (maxEntry != null) {
                            String dimName = maxEntry.getKey().replace("minecraft:", "");
                            sb.append("§e最高MSPT维度: §a").append(dimName)
                                    .append(" §f").append(String.format("%.2f", maxEntry.getValue())).append(" ms\n");
                        }

                        // 按MSPT从大到小排序（越卡越靠前）
                        sb.append("§e各个维度MSPT(由大到小排序):\n");
                        result.dimensionMspt().entrySet().stream()
                                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                                .forEach(entry -> {
                                    String dimName = entry.getKey();
                                    sb.append("  §f").append(dimName).append(": §a")
                                            .append(String.format("%.2f", entry.getValue())).append(" ms\n");
                                });
                    }

                    ctx.getSource().sendSuccess(
                            () -> Component.literal(sb.toString()),
                            false);
                    return 1;
                }));

        // ========== MSPT 子命令 ==========
        root.then(Commands.literal("mspt")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    PerformanceResult result = PerformanceQuery.query(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§eMSPT: §a" + String.format("%.2f", result.mspt()) + " ms"),
                            false);
                    return 1;
                }));

        // ========== 性能总览子命令 ==========
        root.then(Commands.literal("perf")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    PerformanceResult result = PerformanceQuery.query(server);
                    // 构建详细信息
                    StringBuilder sb = new StringBuilder();
                    sb.append("§e=== §6LingLens 性能总览 §e===\n");
                    sb.append("§fTPS: §a").append(String.format("%.2f", result.tps())).append("\n");
                    sb.append("§fMSPT: §a").append(String.format("%.2f", result.mspt())).append(" ms\n");
                    sb.append("§f在线玩家: §a")
                            .append(server.getPlayerCount())
                            .append(" / ")
                            .append(server.getMaxPlayers())
                            .append("\n");
                    // 显示各维度 MSPT 详情（由 DimensionTickTracker 提供）
                    if (result.dimensionMspt() != null && !result.dimensionMspt().isEmpty()) {
                        sb.append("§7维度详情(由大到小排序):\n");
                        result.dimensionMspt().entrySet().stream()
                                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                                .forEach(entry -> {
                                    // 去除 "minecraft:" 前缀，让显示更简洁
                                    String dimName = entry.getKey().replace("minecraft:", "");
                                    sb.append("  §f").append(dimName)
                                            .append(": §a").append(String.format("%.2f", entry.getValue()))
                                            .append(" ms\n");
                                });
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                }));

        dispatcher.register(root);
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
}