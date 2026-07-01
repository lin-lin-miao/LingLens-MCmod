package com.linglens.command;

import com.linglens.manager.TeleportManager;
import com.linglens.performance.PerformanceQuery;
import com.linglens.performance.SystemPerfResult;
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

        // ========== perf 命令组 ==========
        LiteralArgumentBuilder<CommandSourceStack> perfCommand = Commands.literal("perf");

        // /linglens perf system —— 系统性能（CPU / 内存）
        perfCommand.then(Commands.literal("system")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    SystemPerfResult sysResult = PerformanceQuery.getSystemPerf(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(sysResult.toReadableString()),
                            false);
                    LOGGER.debug("[LingLens] 系统性能命令执行: CPU={}, 内存={:.2f}MB",
                            sysResult.cpuPercent() >= 0 ? String.format("%.2f%%", sysResult.cpuPercent()) : "N/A",
                            sysResult.usedMemoryMB());
                    return 1;
                }));

        // /linglens perf tps —— 游戏性能（TPS + 各维度 MSPT）
        perfCommand.then(Commands.literal("tps")
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

        // /linglens perf —— 综合性能（系统资源 + 游戏性能）
        perfCommand.executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            // 同时获取游戏性能与系统性能
            PerformanceResult gameResult = PerformanceQuery.query(server);
            SystemPerfResult sysResult = PerformanceQuery.getSystemPerf(server);

            StringBuilder sb = new StringBuilder();
            sb.append("§e=== §6LingLens 综合性能总览 §e===\n");

            // ---- 系统性能部分 ----
            sb.append("§6[ 系统资源 ]\n");
            sb.append("§fCPU: §")
                    .append(sysResult.cpuPercent() >= 0 ? "a" : "c")
                    .append(sysResult.cpuPercent() >= 0
                            ? String.format("%.2f%%", sysResult.cpuPercent())
                            : "不可用")
                    .append("\n");
            sb.append("§f内存: §a")
                    .append(String.format("%.2f", sysResult.usedMemoryMB()))
                    .append(" MB / ")
                    .append(String.format("%.2f", sysResult.allocatedMemoryMB()))
                    .append(" MB (已分配) / ")
                    .append(String.format("%.2f", sysResult.maxMemoryMB()))
                    .append(" MB (最大)\n");
            sb.append("§7内存占用率: §a")
                    .append(String.format("%.1f%%",
                            sysResult.maxMemoryMB() > 0
                                    ? (sysResult.usedMemoryMB() / sysResult.maxMemoryMB()) * 100.0
                                    : 0))
                    .append("\n");

            // ---- 游戏性能部分 ----
            sb.append("§6[ 游戏性能 ]\n");
            sb.append("§fTPS: §a").append(String.format("%.2f", gameResult.tps())).append("\n");
            sb.append("§fMSPT: §a").append(String.format("%.2f", gameResult.mspt())).append(" ms\n");
            sb.append("§f在线玩家: §a")
                    .append(server.getPlayerCount())
                    .append(" / ")
                    .append(server.getMaxPlayers())
                    .append("\n");

            // 各维度 MSPT 详情
            if (gameResult.dimensionMspt() != null && !gameResult.dimensionMspt().isEmpty()) {
                sb.append("§7各维度 MSPT (由大到小):\n");
                gameResult.dimensionMspt().entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .forEach(entry -> {
                            String dimName = entry.getKey().replace("minecraft:", "");
                            sb.append("  §f").append(dimName)
                                    .append(": §a").append(String.format("%.2f", entry.getValue()))
                                    .append(" ms\n");
                        });
            }

            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            LOGGER.debug("[LingLens] 综合性能命令执行: TPS={}, MSPT={}ms, CPU={}, 内存={:.2f}MB",
                    String.format("%.2f", gameResult.tps()),
                    String.format("%.2f", gameResult.mspt()),
                    sysResult.cpuPercent() >= 0 ? String.format("%.2f%%", sysResult.cpuPercent()) : "N/A",
                    sysResult.usedMemoryMB());
            return 1;
        });

        root.then(perfCommand);

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