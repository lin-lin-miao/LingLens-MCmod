package com.linglens.command;

import com.linglens.chat.ChatCache;
import com.linglens.chat.ChatMessage;
import com.linglens.chat.ChatPersistence;
import com.linglens.entity.EntityStatsCache;
import com.linglens.entity.EntityQueryResult;
import com.linglens.manager.TeleportManager;
import com.linglens.performance.PerformanceQuery;
import com.linglens.performance.SystemPerfResult;
import com.linglens.performance.PerformanceResult;
import com.linglens.chunk.ChunkQueryResult;
import com.linglens.config.ConfigManager;
import com.linglens.network.NetworkTrafficStats;
import com.linglens.network.PacketTypeInfo;
import com.linglens.network.PlayerTrafficData;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;

import java.lang.reflect.Field;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linglens.player.PlayerInfo;
import com.linglens.player.PlayerInfoQuery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 灵棱枢 (LingLens) 命令注册与处理类。
 * 包含五个命令组:
 * <ul>
 * <li>offline-tp — 离线玩家位置修改</li>
 * <li>perf — 游戏/系统性能查询</li>
 * <li>entity — 实体数量查询与统计</li>
 * <li>chat — 聊天栏消息记录(内存缓存)</li>
 * <li>players — 在线玩家列表概要</li>
 * <li>player — 单个玩家详细信息查询</li>
 * </ul>
 */
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

        // ========== offline-tp 命令组 ==========
        LiteralArgumentBuilder<CommandSourceStack> offline_tp = Commands.literal("offline-tp");

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
        root.then(offline_tp);

        // ========== perf 命令组 ==========
        LiteralArgumentBuilder<CommandSourceStack> perfCommand = Commands.literal("perf");

        // /linglens perf system —— 系统性能(CPU / 内存)
        perfCommand.then(Commands.literal("system")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    SystemPerfResult sysResult = PerformanceQuery.getSystemPerf(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(getFormatSysmsg(sysResult)),
                            false);
                    return 1;
                }));

        // /linglens perf tps —— 游戏性能(TPS + 各维度 MSPT)
        perfCommand.then(Commands.literal("tps")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    PerformanceResult gameResult = PerformanceQuery.query(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(getFormatGamemsg(server, gameResult)),
                            false);
                    return 1;
                }));

        // /linglens perf —— 综合性能(系统资源 + 游戏性能)
        perfCommand.executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            // 同时获取游戏性能与系统性能
            PerformanceResult gameResult = PerformanceQuery.query(server);
            SystemPerfResult sysResult = PerformanceQuery.getSystemPerf(server);

            StringBuilder sb = new StringBuilder();
            sb.append("§e=== §6[LingLens] 综合性能总览 §e===\n");
            sb.append(getFormatSysmsg(sysResult));
            sb.append(getFormatGamemsg(server, gameResult));
            // 区块总览
            ChunkQueryResult chunkResult = ChunkQueryResult.queryAll(server);
            sb.append("§6=== [ 区块加载 ] ===\n");
            sb.append("§f总加载区块: §a").append(chunkResult.getTotalLoaded()).append("\n");
            sb.append("§f强制加载区块: §a").append(chunkResult.getTotalForced()).append("\n");

            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
            LOGGER.debug("[LingLens] 综合性能命令执行: TPS={}+{}, MSPT={}ms, CPU={}, 内存={:.2f}MB, 区块={}",
                    String.format("%.2f", gameResult.tps()), String.format("%.2f", gameResult.idletps()),
                    String.format("%.2f", gameResult.mspt()),
                    sysResult.cpuPercent() >= 0 ? String.format("%.2f%%", sysResult.cpuPercent()) : "N/A",
                    sysResult.usedMemoryMB(),
                    chunkResult.getTotalLoaded());
            return 1;
        });

        // /linglens perf chunks —— 区块加载统计详情
        perfCommand.then(Commands.literal("chunks")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    ChunkQueryResult chunkResult = ChunkQueryResult.queryAll(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(chunkResult.toReadableString()),
                            false);
                    // LOGGER.info("[LingLens] 区块统计查询: 总加载={}, 总强制={}",
                    // chunkResult.getTotalLoaded(), chunkResult.getTotalForced());
                    return 1;
                }));

        root.then(perfCommand);

        // ========== entity 命令组 ==========
        LiteralArgumentBuilder<CommandSourceStack> entityCommand = Commands.literal("entity");

        // /linglens entity —— 实体数量统计(常规查询)
        entityCommand.executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            EntityStatsCache cache = EntityStatsCache.getInstance();
            EntityQueryResult result = cache.query(server);
            ctx.getSource().sendSuccess(
                    () -> result.toReadableString(),
                    false);
            return 1;
        });

        // /linglens entity rebuild —— 手动重建缓存(需要 OP 权限)
        entityCommand.then(Commands.literal("rebuild")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§e[LingLens] 开始强制重建实体统计缓存..."),
                            true);
                    long start = System.currentTimeMillis();
                    EntityStatsCache.getInstance().rebuild(server);
                    long elapsed = System.currentTimeMillis() - start;
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[LingLens] 实体统计缓存重建完成,耗时 "
                                    + elapsed + " ms,当前状态: READY"),
                            true);
                    LOGGER.info("[LingLens] 手动触发实体缓存重建,耗时 {} ms", elapsed);
                    return 1;
                }));
        // /linglens entity setdirty —— 手动设脏,使缓存失效(需要 OP 权限)
        entityCommand.then(Commands.literal("setdirty")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    EntityStatsCache cache = EntityStatsCache.getInstance();
                    cache.setDirty("管理员手动设脏");
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§e[LingLens] 实体统计缓存已手动设为 DIRTY(下次查询将自动重建)"),
                            true);
                    return 1;
                }));

        // /linglens entity status —— 查看缓存状态(需要 OP 权限)
        entityCommand.then(Commands.literal("status")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    EntityStatsCache cache = EntityStatsCache.getInstance();
                    EntityStatsCache.State state = cache.getState();
                    long lastUpdate = cache.getLastUpdateTime();
                    long cachedTotal = cache.getCachedGlobalTotal();

                    StringBuilder sb = new StringBuilder();
                    sb.append("§6=== [LingLens] 实体统计缓存状态 ===\n");
                    sb.append("§f当前状态: §").append(getStateColor(state)).append(state.name()).append("\n");
                    sb.append("§f缓存全局总数: §")
                            .append(cachedTotal >= 0 ? "f" + cachedTotal : "cN/A(未初始化)")
                            .append("\n");
                    sb.append("§f上次更新时间: §f")
                            .append(lastUpdate > 0 ? formatTimestamp(lastUpdate) : "§c从未")
                            .append("\n");
                    if (state == EntityStatsCache.State.READY) {
                        long dimCount = 0;
                        for (var ignored : server.getAllLevels())
                            dimCount++;
                        sb.append("§f已加载维度数量: §a").append(dimCount).append("\n");
                    }
                    sb.append("§e使用 /linglens entity 查询完整统计");

                    ctx.getSource().sendSuccess(
                            () -> Component.literal(sb.toString()),
                            false);
                    return 1;
                }));

        root.then(entityCommand);

        // ========== chat 命令组(聊天消息缓存) ==========
        LiteralArgumentBuilder<CommandSourceStack> chatCommand = Commands.literal("chat");
        // 默认查看最近 20 条(需要 OP 等级 ≥ 2)
        chatCommand.executes(ctx -> executeChatRecent(ctx, 20))
                // /linglens chat <条数>
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> {
                            int count = IntegerArgumentType.getInteger(ctx, "count");
                            return executeChatRecent(ctx, count);
                        }))
                // /linglens chat player <名称> [条数]
                .then(Commands.literal("player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    return executeChatFilterPlayer(ctx, name, 20);
                                })
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            int count = IntegerArgumentType.getInteger(ctx, "count");
                                            return executeChatFilterPlayer(ctx, name, count);
                                        }))))
                // /linglens chat search <关键词>
                .then(Commands.literal("search")
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String keyword = StringArgumentType.getString(ctx, "keyword");
                                    return executeChatSearch(ctx, keyword, 20);
                                })))
                // /linglens chat time <起始分钟前> <结束分钟前> —— 按时间范围查询(OP 2)
                .then(Commands.literal("time")
                        .then(Commands.argument("startMinutesAgo", IntegerArgumentType.integer(0, 5256000))
                                .then(Commands.argument("endMinutesAgo", IntegerArgumentType.integer(0, 5256000))
                                        .executes(ctx -> {
                                            int startMinutesAgo = IntegerArgumentType.getInteger(ctx,
                                                    "startMinutesAgo");
                                            int endMinutesAgo = IntegerArgumentType.getInteger(ctx, "endMinutesAgo");
                                            return executeChatTimeRange(ctx, startMinutesAgo, endMinutesAgo);
                                        }))))
                // /linglens chat since <起始索引> <结束索引>
                .then(Commands.literal("since")
                        .then(Commands.argument("from", IntegerArgumentType.integer(1))
                                .then(Commands.argument("to", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            int from = IntegerArgumentType.getInteger(ctx, "from");
                                            int to = IntegerArgumentType.getInteger(ctx, "to");
                                            return executeChatRange(ctx, from, to);
                                        }))))
                // /linglens chat clear —— 清空缓存(OP 4)
                .then(Commands.literal("clear")
                        .requires(src -> src.hasPermission(4))
                        .executes(ctx -> {
                            ChatCache.getInstance().clear();
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("§a[LingLens] 聊天缓存已清空"),
                                    true);
                            return 1;
                        }))
                // /linglens chat status —— 查看缓存状态
                .then(Commands.literal("status")
                        .executes(ModCommands::executeChatStatus))
                // /linglens chat send <玩家名> <消息> —— 模拟玩家发送(OP 4)
                .then(Commands.literal("send")
                        .requires(src -> src.hasPermission(4))
                        .then(Commands.argument("playerName", StringArgumentType.word())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String playerName = StringArgumentType.getString(ctx, "playerName");
                                            String message = StringArgumentType.getString(ctx, "message");
                                            return executeChatSend(ctx, playerName, message);
                                        }))))
                // /linglens chat export —— 导出缓存为 JSON(OP 4)
                .then(Commands.literal("export")
                        .requires(src -> src.hasPermission(4))
                        .executes(ModCommands::executeChatExport));

        root.then(chatCommand);

        // ========== players 命令组(在线玩家列表 + 所有玩家时长排名) ==========
        LiteralArgumentBuilder<CommandSourceStack> playersCommand = Commands.literal("players");
        // /linglens players —— 在线玩家列表概要
        playersCommand.executes(ModCommands::executePlayersList);
        // /linglens players list —— 所有玩家(含离线)在线时长排名(降序)
        playersCommand.then(Commands.literal("list")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    Component msg = PlayerInfoQuery.buildAllPlayTimeSortedMessage(server);
                    ctx.getSource().sendSuccess(() -> msg, false);
                    // LOGGER.info("[LingLens] 已执行所有玩家在线时长排名查询");
                    return 1;
                }));
        // ========== player 命令(单个玩家详细信息) ==========
        playersCommand.then(Commands.literal("get")

                .requires(src -> src.hasPermission(4))
                .then(Commands.argument("name", StringArgumentType.word())
                        .requires(src -> src.hasPermission(4))
                        .suggests(PLAYER_SUGGESTIONS)
                        .executes(ModCommands::executePlayerDetail)));

        LiteralArgumentBuilder<CommandSourceStack> killableCommand = Commands.literal("killable")
                .requires(src -> src.hasPermission(4));
        killableCommand.then(Commands.argument("name", StringArgumentType.word())
                .requires(src -> src.hasPermission(4))
                .suggests(PLAYER_SUGGESTIONS)
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    try {
                        String playerName = StringArgumentType.getString(ctx, "name");
                        MinecraftServer server = source.getServer();

                        ServerPlayer targetPlayer = PlayerInfoQuery.findPlayerByName(server, playerName);

                        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                        if (overworld == null) {
                            ctx.getSource().sendFailure(Component.literal("无法获取主世界"));
                            return 0;
                        }
                        BlockPos spawnPos = overworld.getSharedSpawnPos();

                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("§c" + playerName + " 被猫猫处决了！"),
                                false);
                        ClientboundPlayerCombatKillPacket killPacket = new ClientboundPlayerCombatKillPacket(
                                targetPlayer.getId(),
                                Component.literal("猫猫不准你活")
                                        .withStyle(style -> style.withColor(TextColor.fromRgb(0xFF5555))));
                        targetPlayer.connection.send(killPacket);

                        targetPlayer.kill();
                        targetPlayer.setHealth(0);
                        // targetPlayer.remove(Entity.RemovalReason.KILLED);
                        targetPlayer.die(targetPlayer.damageSources().anvil(targetPlayer));
                        source.sendSuccess(
                                () -> Component.literal(playerName + "已处决"),
                                false);

                    } catch (Exception e) {
                        LOGGER.error("[LingLens] 查询玩家详细信息异常: ", e);
                        return 0;
                    }
                    return 1;
                }));
        playersCommand.then(killableCommand);
        root.then(playersCommand);

        // ========== tool 命令组 ==========
        LiteralArgumentBuilder<CommandSourceStack> toolCommand = Commands.literal("tool");
        // /linglens tool hat —— 交换主手与头部物品(OP 2)
        toolCommand.then(Commands.literal("hat")
                .executes(ModCommands::executeToolHat));
        // /linglens tool —— 帮助信息
        toolCommand.executes(ctx -> {
            ctx.getSource().sendSuccess(
                    () -> Component.literal("§6=== [LingLens] Tool 命令 ===\n§e/linglens tool hat §f- 交换主手与头部物品"), false);
            return 1;
        });
        root.then(toolCommand);

        // ========== config 命令组(配置文件管理) ==========
        LiteralArgumentBuilder<CommandSourceStack> configCommand = Commands.literal("config")
                .requires(src -> src.hasPermission(4));

        // /linglens config —— 查看所有配置项(OP 2)
        configCommand.executes(ctx -> {
            ctx.getSource().sendSuccess(
                    () -> Component.literal(ConfigManager.getInstance().getConfigDisplay()),
                    false);
            return 1;
        });

        // /linglens config set <key> <value> —— 修改配置项(OP 4)
        configCommand.then(Commands.literal("set")
                .requires(src -> src.hasPermission(4))
                .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String k : ConfigManager.getConfigKeys()) {
                                builder.suggest(k);
                            }
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String key = StringArgumentType.getString(ctx, "key");
                                    String value = StringArgumentType.getString(ctx, "value");
                                    boolean success = ConfigManager.getInstance().setConfigValue(key, value);
                                    if (success) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§a[LingLens] 配置项 " + key + " 已更新为: " + value),
                                                true);
                                        // LOGGER.info("[LingLens] 管理员修改配置: {} = {}", key, value);
                                    } else {
                                        ctx.getSource().sendFailure(
                                                Component.literal("§c配置项 " + key + " 不存在或值不合法"));
                                    }
                                    return success ? 1 : 0;
                                }))));

        // /linglens config save —— 手动保存配置(OP 4)
        configCommand.then(Commands.literal("save")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    ConfigManager.getInstance().saveToFile();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[LingLens] 配置文件已保存"),
                            true);
                    return 1;
                }));

        // /linglens config reload —— 从文件重载配置(OP 4)
        configCommand.then(Commands.literal("reload")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    ConfigManager.getInstance().loadFromFile();
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[LingLens] 配置文件已重新加载"),
                            true);
                    LOGGER.info("[LingLens] 管理员手动重载配置");
                    return 1;
                }));

        root.then(configCommand);

        // ========== list 命令(JSON 序列化输出服务器综合信息) ==========
                LiteralArgumentBuilder<CommandSourceStack> listCommand = Commands.literal("list")
                        .requires(src -> src.hasPermission(2))
                        // /linglens list —— 综合信息(所有模块)
                        .executes(ModCommands::executeList)
                        // /linglens list system —— 仅系统性能(JSON)
                        .then(Commands.literal("system")
                                .executes(ModCommands::executeListSystem))
                        // /linglens list perf —— 仅游戏性能(JSON)
                        .then(Commands.literal("perf")
                                .executes(ModCommands::executeListPerf))
                        // /linglens list entity —— 仅实体统计(JSON)
                        .then(Commands.literal("entity")
                                .executes(ModCommands::executeListEntity))
                        // /linglens list players —— 仅在线玩家(JSON)
                        .then(Commands.literal("players")
                                .executes(ModCommands::executeListPlayers))
                        // /linglens list chunks —— 仅区块信息(JSON)
                        .then(Commands.literal("chunks")
                                .executes(ModCommands::executeListChunks))
                        // /linglens list network —— 仅网络流量(JSON)
                        .then(Commands.literal("network")
                                .executes(ModCommands::executeListNetwork));
                root.then(listCommand);

        // ========== network 命令组(网络流量统计) ==========
        LiteralArgumentBuilder<CommandSourceStack> networkCommand = Commands.literal("network")
                .requires(src -> src.hasPermission(2));

        // /linglens network —— 全局网络流量总览(仅上传/下载总量)
        networkCommand.executes(ModCommands::executeNetworkOverview);

        // /linglens network players —— 玩家流量排行
        networkCommand.then(Commands.literal("players")
                .executes(ModCommands::executeNetworkPlayers));

        // /linglens network packet —— 数据包流量排行
        networkCommand.then(Commands.literal("packet")
                .executes(ModCommands::executeNetworkPackets));

        root.then(networkCommand);

        dispatcher.register(root);
        LOGGER.info("[LingLens] 命令已注册 (Command registered)");
    }

    // ==================== 格式化辅助方法 ====================

    /**
     * 根据缓存状态返回对应的 Minecraft 聊天颜色代码。
     */
    private static String getStateColor(EntityStatsCache.State state) {
        switch (state) {
            case READY:
                return "a"; // 绿色
            case DIRTY:
                return "e"; // 黄色
            case REBUILDING:
                return "6"; // 金色
            case UNINIT:
                return "c"; // 红色
            default:
                return "f"; // 白色
        }
    }

    /**
     * 格式化时间戳为可读的日期时间字符串。
     *
     * @param timestamp 毫秒时间戳
     * @return 形如 "2024-01-15 14:30:25" 的字符串
     */
    private static String formatTimestamp(long timestamp) {
        if (timestamp <= 0)
            return "N/A";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }

    public static String getFormatGamemsg(MinecraftServer server, PerformanceResult gameResult) {
        // ----- 游戏性能部分(使用 MessageFormat)-----
        StringBuilder gamemsg = new StringBuilder();
        MessageFormat gameMf = new MessageFormat(
                "§6=== [ 游戏性能 ] ===\n" +
                        "§fTPS: §{0}{1} + §{2}{3}\n" +
                        "§fMSPT: §a{4} ms\n" +
                        "§f在线玩家: §a{5} / §b{6} §f: {7}\n");
        String tmp = gameMf.format(new Object[] {
                gameResult.tps() < 18.0 ? "c" : "a",
                String.format("%.2f", gameResult.tps()),
                gameResult.tps() < 18.0 ? "c" : "a",
                String.format("%.2f", gameResult.idletps()),
                String.format("%.2f", gameResult.mspt()),
                server.getPlayerCount(),
                server.getMaxPlayers(), Arrays.toString(server.getPlayerNames())
        });
        gamemsg.append(tmp);
        // 各维度 MSPT 详情
        if (gameResult.dimensionMspt() != null && !gameResult.dimensionMspt().isEmpty()) {
            // 寻找最高MSPT维度(值最大,即最卡的维度)
            Map.Entry<String, Double> maxEntry = gameResult.dimensionMspt().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (maxEntry != null) {
                String dimName = maxEntry.getKey().replace("minecraft:", "");
                gamemsg.append("§e最高MSPT维度: §a").append(dimName)
                        .append(" §f").append(String.format("%.2f", maxEntry.getValue())).append(" ms\n");
            }
            // 按MSPT从大到小排序(越卡越靠前)
            gamemsg.append("§e各个维度MSPT(由大到小排序):\n");
            gameResult.dimensionMspt().entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(8)
                    .forEach(entry -> {
                        String dimName = entry.getKey();
                        gamemsg.append("  §f").append(dimName).append(": §a")
                                .append(String.format("%.2f", entry.getValue())).append(" ms\n");
                    });
            if (gameResult.dimensionMspt().size() > 8) {
                gamemsg.append("  §8... 及其他 ").append(gameResult.dimensionMspt().size() - 8).append(" 个维度\n");
            }
        }
        return gamemsg.toString();
    }

    public static String getFormatSysmsg(SystemPerfResult sysResult) {
        // ---- 系统性能部分 ----
        MessageFormat sysmf = new MessageFormat(
                "§6=== [ 系统资源 ] ===\n" +
                        "§fCPU: §{0}{1}\n" +
                        "§f内存: §a{2} MB / {3} MB (已分配)\n" +
                        "§7内存占用率: §a{4}\n");
        String sysmsg = sysmf.format(new Object[] { sysResult.cpuPercent() >= 50 ? "e" : "a",
                String.format("%.2f%%", sysResult.cpuPercent()),
                String.format("%.2f", sysResult.usedMemoryMB()), String.format("%.2f", sysResult.allocatedMemoryMB()),
                String.format("%.1f%%",
                        sysResult.allocatedMemoryMB() > 0
                                ? (sysResult.usedMemoryMB() / sysResult.allocatedMemoryMB()) * 100.0
                                : 0),
        });
        return sysmsg;
    }

    // ==================== Chat 命令处理方法 ====================

    /**
     * 执行 /linglens chat [count] —— 查看最近 N 条消息。
     */
    private static int executeChatRecent(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack source = ctx.getSource();
        try {
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> messages = cache.getRecentMessages(count);
            if (messages.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 暂无缓存消息"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 最近 ").append(messages.size()).append(" 条消息 ===\n");
            for (ChatMessage msg : messages) {
                sb.append(msg.toFormattedString()).append("\n");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("查询聊天缓存失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 查询聊天缓存异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat player <name> [count] —— 按玩家过滤。
     */
    private static int executeChatFilterPlayer(CommandContext<CommandSourceStack> ctx, String playerName, int count) {
        CommandSourceStack source = ctx.getSource();
        try {
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> messages = cache.filterByPlayer(playerName, count);
            if (messages.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 未找到玩家 " + playerName + " 的消息"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] ").append(playerName).append(" 最近 ").append(messages.size())
                    .append(" 条消息 ===\n");
            for (ChatMessage msg : messages) {
                sb.append(msg.toFormattedString()).append("\n");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("过滤玩家消息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 过滤玩家消息异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat search <keyword> —— 关键词搜索。
     */
    private static int executeChatSearch(CommandContext<CommandSourceStack> ctx, String keyword, int count) {
        CommandSourceStack source = ctx.getSource();
        try {
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> messages = cache.searchByKeyword(keyword, count);
            if (messages.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 未找到包含关键词 \"" + keyword + "\" 的消息"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 搜索 \"").append(keyword).append("\" 结果 (").append(messages.size())
                    .append(" 条) ===\n");
            for (ChatMessage msg : messages) {
                sb.append(msg.toFormattedString()).append("\n");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("搜索消息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 搜索消息异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat since <from> <to> —— 条数范围查询。
     */
    private static int executeChatRange(CommandContext<CommandSourceStack> ctx, int from, int to) {
        CommandSourceStack source = ctx.getSource();
        try {
            if (from > to) {
                source.sendFailure(Component.literal("起始索引不能大于结束索引"));
                return 0;
            }
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> messages = cache.filterByRange(from, to);
            if (messages.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 该范围内无消息"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 第 ").append(from).append(" 到 ").append(to).append(" 条消息 (")
                    .append(messages.size()).append(" 条) ===\n");
            for (ChatMessage msg : messages) {
                sb.append(msg.toFormattedString()).append("\n");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("查询消息范围失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 查询消息范围异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat time <startMinutesAgo> <endMinutesAgo> —— 按时间范围查询。
     * startMinutesAgo 和 endMinutesAgo 分别表示从当前时间往前推的分钟数。
     * 例如:/linglens chat time 0 60 表示查询最近 1 小时内的消息。
     * 两个参数中较小的值作为起始时间(较新),较大的值作为结束时间(较旧)。
     */
    private static int executeChatTimeRange(CommandContext<CommandSourceStack> ctx, int startMinutesAgo,
            int endMinutesAgo) {
        CommandSourceStack source = ctx.getSource();
        try {
            // 确保 startMinutesAgo <= endMinutesAgo,将较小的作为起始(较新),较大的作为结束(较旧)
            int fromMinutes = Math.min(startMinutesAgo, endMinutesAgo);
            int toMinutes = Math.max(startMinutesAgo, endMinutesAgo);

            long now = System.currentTimeMillis();
            long startTime = now - (long) toMinutes * 60_000L; // 较旧的边界(更早)
            long endTime = now - (long) fromMinutes * 60_000L; // 较新的边界(更晚)

            // 最多返回 200 条,避免输出过长
            int maxCount = 200;
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> messages = cache.filterByTimeRange(startTime, endTime, maxCount);
            if (messages.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 该时间范围内无消息"), false);
                return 1;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 时间范围查询 (").append(fromMinutes).append(" ~ ").append(toMinutes).append(" 分钟前, ")
                    .append(messages.size()).append(" 条) ===\n");
            for (ChatMessage msg : messages) {
                sb.append(msg.toFormattedString()).append("\n");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("时间范围查询失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 时间范围查询异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat status —— 查看缓存状态。
     */
    private static int executeChatStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ChatCache cache = ChatCache.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 聊天缓存状态 ===\n");
            sb.append("§f内存消息数: §a").append(cache.size()).append(" / ").append(cache.getMaxSize()).append("\n");
            sb.append("§f文件记录数: §a").append(ChatPersistence.totalMessages()).append("\n");
            sb.append("§f文件大小: §a").append(formatFileSize(ChatCache.persistentFileSize())).append("\n");
            sb.append("§f保留天数: §a").append(cache.getRetentionDays()).append(" 天\n");
            sb.append("§f忽略的玩家: §f")
                    .append(cache.getIgnoredPlayers().isEmpty() ? "无" : String.join(", ", cache.getIgnoredPlayers()))
                    .append("\n");
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("获取缓存状态失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 获取缓存状态异常: ", e);
            return 0;
        }
    }

    /**
     * 格式化文件大小为人类可读字符串。
     */
    private static String formatFileSize(long bytes) {
        if (bytes <= 0)
            return "0 B";
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 执行 /linglens chat send <playerName> <message> —— 模拟玩家发送消息。
     */
    private static int executeChatSend(CommandContext<CommandSourceStack> ctx, String playerName, String message) {
        CommandSourceStack source = ctx.getSource();/// <<<<<
        try {
            MinecraftServer server = source.getServer();
            UUID uuid;
            // 先查在线玩家
            ServerPlayer player = PlayerInfoQuery.findPlayerByName(server, playerName);
            if (player != null) {
                uuid = player.getUUID();
            } else {
                var profileOpt = server.getProfileCache().get(playerName);
                if (profileOpt.isPresent()) {
                    uuid = profileOpt.get().getId();
                } else {
                    uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes());
                }
            }
            String dimension = source.getLevel().dimension().location().toString();
            ChatCache.getInstance().addMessage(uuid, playerName, dimension, message);

            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("<" + playerName + "> " + message),
                    false);

            // 构造未签名的玩家聊天消息
            // PlayerChatMessage unsignedMsg = PlayerChatMessage.unsigned(uuid, message);
            // RegistryAccess registry = server.registryAccess();
            // ChatType.Bound bound = ChatType.bind(
            // ChatType.CHAT, // ResourceKey<ChatType>,ChatType.CHAT 是静态常量
            // registry,
            // Component.literal(playerName) // 显示名称直接为玩家名(无样式)
            // );
            // // 4. 广播(使用第一个重载,支持控制台来源)
            // server.getPlayerList().broadcastChatMessage(unsignedMsg, source, bound);

            source.sendSuccess(
                    () -> Component.literal("§a[LingLens] 已模拟 " + playerName + " 发送消息到缓存"),
                    true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("模拟发送消息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 模拟发送消息异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens chat export —— 导出缓存为 JSON 格式文本。
     */
    private static int executeChatExport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ChatCache cache = ChatCache.getInstance();
            List<ChatMessage> snapshot = cache.getSnapshot();
            if (snapshot.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 缓存为空,无内容可导出"), false);
                return 1;
            }
            StringBuilder json = new StringBuilder();
            json.append("[\n");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (int i = 0; i < snapshot.size(); i++) {
                ChatMessage msg = snapshot.get(i);
                String timeStr;
                synchronized (sdf) {
                    timeStr = sdf.format(new Date(msg.timestamp()));
                }
                json.append("  {\n");
                json.append("    \"index\": ").append(i + 1).append(",\n");
                json.append("    \"time\": \"").append(timeStr).append("\",\n");
                json.append("    \"sender\": \"").append(msg.senderName()).append("\",\n");
                json.append("    \"uuid\": \"").append(msg.senderUuid().toString()).append("\",\n");
                json.append("    \"dimension\": \"").append(msg.dimension()).append("\",\n");
                json.append("    \"content\": \"").append(msg.content().replace("\\", "\\\\").replace("\"", "\\\""))
                        .append("\"\n");
                json.append("  }");
                if (i < snapshot.size() - 1)
                    json.append(",");
                json.append("\n");
            }
            json.append("]");
            String jsonStr = json.toString();
            if (jsonStr.length() <= 32767) {
                source.sendSuccess(() -> Component.literal("§6=== [LingLens] 聊天缓存导出 (JSON) ===\n" + jsonStr), false);
            } else {
                int chunkSize = 32000;
                for (int offset = 0; offset < jsonStr.length(); offset += chunkSize) {
                    int end = Math.min(offset + chunkSize, jsonStr.length());
                    final int finalStart = offset;
                    source.sendSuccess(() -> Component.literal(jsonStr.substring(finalStart, end)), false);
                }
                source.sendSuccess(() -> Component.literal("§a[LingLens] 导出完成,共 " + snapshot.size() + " 条消息"), true);
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("导出缓存失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 导出缓存异常: ", e);
            return 0;
        }
    }

    // ==================== 在线玩家信息查询命令 ====================

    /**
     * 执行 /linglens players 命令。
     * <p>
     * 列出所有在线玩家的概要信息,包括名称、维度、坐标、生命值、在线时长和延迟。
     * 按玩家名称排序。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 命令执行结果码(1=成功,0=失败)
     */
    private static int executePlayersList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            List<PlayerInfo> playerInfos = PlayerInfoQuery.collectAllOnlinePlayers(server);
            int maxPlayers = server.getPlayerList().getMaxPlayers();

            List<Component> messages = PlayerInfoQuery.buildPlayerListMessage(playerInfos, maxPlayers);
            for (Component msg : messages) {
                source.sendSuccess(() -> msg, false);
            }

            LOGGER.debug("[LingLens] 已执行在线玩家列表查询,当前在线 {} 人", playerInfos.size());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("查询在线玩家列表失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 查询在线玩家列表异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens players <name> 命令。
     * <p>
     * 查询单个玩家的详细信息,包括 UUID、位置、维度、生命值、饥饿值、经验等级、
     * 在线时长、延迟和游戏模式,以及装备信息(主手、副手、盔甲栏)。
     * 如果玩家不在线,返回错误提示。
     * </p>
     *
     * @param ctx 命令上下文,包含 "name" 参数
     * @return 命令执行结果码(1=成功,0=失败/玩家不在线)
     */
    private static int executePlayerDetail(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            String playerName = StringArgumentType.getString(ctx, "name");
            MinecraftServer server = source.getServer();

            ServerPlayer targetPlayer = PlayerInfoQuery.findPlayerByName(server, playerName);
            if (targetPlayer == null) {
                source.sendFailure(PlayerInfoQuery.buildPlayerNotFoundMessage(playerName));
                LOGGER.warn("[LingLens] 查询玩家详细信息失败: {} 不在线", playerName);
                return 0;
            }

            PlayerInfo info = PlayerInfoQuery.collectPlayerInfo(targetPlayer);
            if (info == null) {
                source.sendFailure(Component.literal("采集玩家信息时发生错误"));
                return 0;
            }

            // 合并所有基础消息为一条,一次性发送
            List<Component> messages = PlayerInfoQuery.buildPlayerDetailMessage(info);
            MutableComponent detailMessage = Component.literal("");
            for (int i = 0; i < messages.size(); i++) {
                detailMessage.append(messages.get(i));
            }
            detailMessage.append(Component.literal("\n"));
            // 添加玩家装备信息(含主手、副手和盔甲)
            detailMessage.append(buildEquipmentComponent(targetPlayer));

            source.sendSuccess(() -> detailMessage, false);

            LOGGER.debug("[LingLens] 已查询玩家 {} 的详细信息(含装备)", playerName);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("查询玩家详细信息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 查询玩家详细信息异常: ", e);
            return 0;
        }
    }

    // ==================== 装备信息辅助方法 ====================

    /** 可交互物品栏位名称常量数组(与 getArmorSlots 顺序匹配) */
    private static final String[] ARMOR_SLOT_NAMES = { "靴子", "护腿", "胸甲", "头盔" };

    /**
     * 构建玩家装备信息的可交互组件。
     * <p>
     * 包括主手、副手和盔甲栏物品,悬停可查看物品详情(SHOW_ITEM格式),
     * 点击将对应的 /give 指令填入输入框(不自动执行)。
     * 空物品显示灰色 "空"。
     * </p>
     *
     * @param player 目标在线玩家
     * @return 包含所有装备栏信息的 Component
     */
    private static Component buildEquipmentComponent(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        Iterable<ItemStack> armorSlots = player.getArmorSlots();

        MutableComponent equip = Component.literal("§6=== 装备信息 ===\n");

        // 主手
        equip.append(buildSlotComponent("主手", mainHand)).append(Component.literal("\n"));
        // 副手
        equip.append(buildSlotComponent("副手", offHand)).append(Component.literal("\n"));

        // 盔甲栏(getArmorSlots 返回顺序:脚→腿→胸→头)
        // 转为列表方便索引
        java.util.ArrayList<ItemStack> armorList = new java.util.ArrayList<>();
        armorSlots.forEach(armorList::add);
        for (int i = 0; i < armorList.size(); i++) {
            equip.append(buildSlotComponent(ARMOR_SLOT_NAMES[i], armorList.get(i))).append(Component.literal("\n"));
        }
        return equip;
    }

    /**
     * 构建单个物品栏位的可交互组件。
     * <p>
     * 空物品显示灰色 "空"；
     * 非空物品显示名称和注册 ID,悬停时显示 MC 原版 SHOW_ITEM 提示框,
     * 点击时将 /give 指令填入输入框(SUGGEST_COMMAND 便于玩家修改)。
     * </p>
     *
     * @param slotName 栏位名称(如 "主手"、"头盔")
     * @param stack    该栏位中的物品栈
     * @return 可交互的 Component
     */
    private static Component buildSlotComponent(String slotName, ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.literal("§7" + slotName + ": 空");
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Component itemName = stack.getDisplayName();

        // 构造带完整 NBT 的 /give 指令,确保点击获取时得到完全一致的物品(含附魔、自定义名称、 lore 等)
        CompoundTag tag = stack.getTag();
        String nbtString = "";
        if (tag != null && !tag.isEmpty()) {
            nbtString = tag.toString();
        }
        String suggestCommand = "/give @p " + itemId + nbtString + " " + stack.getCount();

        return Component.literal("§e" + slotName + ": §f").append(itemName)
                .append(Component.literal(" §7(" + itemId + ")")).append(Component.literal(" X" + stack.getCount()))
                .setStyle(Style.EMPTY
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
                                new HoverEvent.ItemStackInfo(stack)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestCommand)));
    }

    /**
     * 处理离线传送命令的核心逻辑。
     * 
     * @param ctx         命令上下文
     * @param pos         目标坐标(为 null 时使用主世界出生点)
     * @param dimensionId 目标维度 ID(仅在 pos 非 null 时生效)
     */
    private static int handleOfflineTp(CommandContext<CommandSourceStack> ctx, Vec3 pos, String dimensionId) {
        String playerName = StringArgumentType.getString(ctx, "player");
        MinecraftServer server = ctx.getSource().getServer();

        // 当坐标为空时,强制使用主世界出生点,忽略传入的 dimensionId
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
            // 用户明确提供了坐标,使用指定的坐标和维度
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

        // 统一存入待传送列表,无论在线还是离线,下次登录时传送
        TeleportManager.addPending(uuid, finalX, finalY, finalZ, finalDimensionId, playerName);
        ctx.getSource().sendSuccess(
                () -> Component.literal(
                        "[LingLens] 已设置 " + playerName + " 下次上线传送至 " + finalDimensionId + " ("
                                + String.format("%.1f", finalX) + " " + String.format("%.1f", finalY) + " "
                                + String.format("%.1f", finalZ) + ")"),
                true);
        return 1;
    }

    // ==================== Tool 命令(工具类) ====================

    /**
     * 执行 /linglens tool hat —— 交换玩家主手物品与头部盔甲栏物品。
     * <p>
     * 逻辑:
     * <ol>
     * <li>获取执行命令的玩家(要求必须是玩家执行)</li>
     * <li>读取主手物品和头盔栏物品</li>
     * <li>若主手为空且头盔为空,则提示"两手空空"；</li>
     * <li>若主手为空但头盔有物品,将头盔移至主手；</li>
     * <li>若主手有物品但头盔为空,将主手物品戴到头上；</li>
     * <li>若两者都有物品,互换。</li>
     * </ol>
     * 需要 OP 等级 ≥ 2。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeToolHat(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        // 仅玩家可以执行
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§c该命令只能由玩家使用"));
            return 0;
        }

        try {
            // 获取主手和头盔物品
            ItemStack mainHand = player.getMainHandItem();
            ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);

            boolean mainHandEmpty = mainHand.isEmpty();
            boolean helmetEmpty = helmet.isEmpty();

            if (mainHandEmpty && helmetEmpty) {
                // 两者都空
                source.sendSuccess(() -> Component.literal("§e[LingLens] 你手里和头上都空空的！"), false);
                return 1;
            }

            if (mainHandEmpty && !helmetEmpty) {
                // 主手空、头盔有 → 头盔移到主手
                player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.MAINHAND, helmet);
                source.sendSuccess(() -> Component.literal("§a[LingLens] 将 ").append(helmet.getDisplayName())
                        .append(Component.literal(" §a从头盔位置移动到主手")), false);
                LOGGER.info("[LingLens] {} 将头盔 {} 移到主手", player.getName().getString(),
                        helmet.getDisplayName().getString());
                return 1;
            }

            if (!mainHandEmpty && helmetEmpty) {
                // 主手有、头盔空 → 戴到头上
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.HEAD, mainHand);
                source.sendSuccess(() -> Component.literal("§a[LingLens] 将 ").append(mainHand.getDisplayName())
                        .append(Component.literal(" §a戴到了头上")), false);
                LOGGER.info("[LingLens] {} 将 {} 戴到头上", player.getName().getString(),
                        mainHand.getDisplayName().getString());
                return 1;
            }

            // 两者都有 → 互换
            player.setItemSlot(EquipmentSlot.MAINHAND, helmet);
            player.setItemSlot(EquipmentSlot.HEAD, mainHand);
            source.sendSuccess(() -> Component.literal("§a[LingLens] 已互换主手与头部物品"), false);
            LOGGER.info("[LingLens] {} 互换了主手 {} 和头盔 {}",
                    player.getName().getString(),
                    mainHand.getDisplayName().getString(),
                    helmet.getDisplayName().getString());
            return 1;

        } catch (Exception e) {
            source.sendFailure(Component.literal("§c执行 hat 命令时出错: " + e.getMessage()));
            LOGGER.error("[LingLens] executeToolHat 异常: ", e);
            return 0;
        }
    }

    // ==================== list 命令(JSON 序列化输出服务器综合信息) ====================

    /**
     * 执行 /linglens list 命令。
     * <p>
     * 收集服务器综合信息(性能、实体统计、在线玩家、区块数据),
     * 以 JSON 文本形式序列化输出到聊天框。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 命令执行结果码(1=成功,0=失败)
     */
    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();

            // 1. 系统性能
            SystemPerfResult sysPerf = PerformanceQuery.getSystemPerf(server);
            // 2. 游戏性能
            PerformanceResult gamePerf = PerformanceQuery.getGamePerf(server);
            // 3. 实体统计
            EntityStatsCache entityCache = EntityStatsCache.getInstance();
            EntityQueryResult entityResult = entityCache.query(server);
            // 4. 在线玩家
            List<PlayerInfo> players = PlayerInfoQuery.collectAllOnlinePlayers(server);
            // 5. 区块信息
            ChunkQueryResult chunkResult = ChunkQueryResult.queryAll(server);

            // 使用 Gson 构建 JSON
            JsonObject root = new JsonObject();

            // ---- 系统性能 ----
            JsonObject sys = new JsonObject();
            sys.addProperty("cpuPercent", Math.round(sysPerf.cpuPercent() * 100.0) / 100.0);
            sys.addProperty("usedMemoryMB", Math.round(sysPerf.usedMemoryMB() * 100.0) / 100.0);
            sys.addProperty("allocatedMemoryMB", Math.round(sysPerf.allocatedMemoryMB() * 100.0) / 100.0);
            sys.addProperty("maxMemoryMB", Math.round(sysPerf.maxMemoryMB() * 100.0) / 100.0);
            root.add("system", sys);

            // ---- 游戏性能 ----
            JsonObject game = new JsonObject();
            game.addProperty("tps", Math.round(gamePerf.tps() * 100.0) / 100.0);
            game.addProperty("idleTps", Math.round(gamePerf.idletps() * 100.0) / 100.0);
            game.addProperty("mspt", Math.round(gamePerf.mspt() * 100.0) / 100.0);
            JsonObject dimMspt = new JsonObject();
            if (gamePerf.dimensionMspt() != null) {
                for (var entry : gamePerf.dimensionMspt().entrySet()) {
                    dimMspt.addProperty(entry.getKey(), Math.round(entry.getValue() * 100.0) / 100.0);
                }
            }
            game.add("dimensionMspt", dimMspt);
            root.add("performance", game);

            // ---- 实体统计 ----
            JsonObject entity = new JsonObject();
            entity.addProperty("globalTotal", entityResult.globalTotal);
            entity.addProperty("fromCache", entityResult.fromCache);
            entity.addProperty("cacheTime", entityResult.cacheTime);

            // 各维度实体总数
            JsonObject dimTotals = new JsonObject();
            for (var entry : entityResult.dimensionTotals.entrySet()) {
                dimTotals.addProperty(entry.getKey(), entry.getValue());
            }
            entity.add("dimensionTotals", dimTotals);

            // 各维度类别统计
            JsonObject dimCategories = new JsonObject();
            for (var dimEntry : entityResult.dimCatCounts.entrySet()) {
                JsonObject cats = new JsonObject();
                for (var catEntry : dimEntry.getValue().entrySet()) {
                    cats.addProperty(catEntry.getKey().name(), catEntry.getValue());
                }
                dimCategories.add(dimEntry.getKey(), cats);
            }
            entity.add("dimensionCategories", dimCategories);

            // 各维度类型 Top20
            JsonObject dimTypes = new JsonObject();
            for (var dimEntry : entityResult.dimTypeCounts.entrySet()) {
                JsonObject types = new JsonObject();
                for (var typeEntry : dimEntry.getValue().entrySet()) {
                    types.addProperty(typeEntry.getKey(), typeEntry.getValue());
                }
                dimTypes.add(dimEntry.getKey(), types);
            }
            entity.add("dimensionTopTypes", dimTypes);

            root.add("entity", entity);

            // ---- 在线玩家 ----
            JsonArray playerArray = new JsonArray();
            for (PlayerInfo p : players) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("name", p.name());
                pObj.addProperty("uuid", p.uuid());
                pObj.addProperty("dimension", p.dimension().toString());
                pObj.addProperty("x", Math.round(p.position().x * 100.0) / 100.0);
                pObj.addProperty("y", Math.round(p.position().y * 100.0) / 100.0);
                pObj.addProperty("z", Math.round(p.position().z * 100.0) / 100.0);
                pObj.addProperty("health", p.health());
                pObj.addProperty("maxHealth", p.maxHealth());
                pObj.addProperty("armor", p.armorValue());
                pObj.addProperty("foodLevel", p.foodLevel());
                pObj.addProperty("saturation", Math.round(p.saturation() * 100.0) / 100.0);
                pObj.addProperty("experienceLevel", p.experienceLevel());
                pObj.addProperty("experienceProgress", Math.round(p.experienceProgress() * 100.0) / 100.0);
                pObj.addProperty("gameMode", p.gameMode());
                pObj.addProperty("latency", p.latency());
                pObj.addProperty("sessionTimeSeconds", p.sessionTimeSeconds());
                pObj.addProperty("totalTimeSeconds", p.totalTimeSeconds());
                playerArray.add(pObj);
            }
            root.add("players", playerArray);
            root.addProperty("onlinePlayerCount", players.size());
            root.addProperty("maxPlayers", server.getMaxPlayers());

                        // ---- 区块信息 ----
            JsonObject chunk = new JsonObject();
            chunk.addProperty("totalLoaded", chunkResult.getTotalLoaded());
            chunk.addProperty("totalForced", chunkResult.getTotalForced());
            root.add("chunks", chunk);

            // ---- 网络流量 ----
            NetworkTrafficStats netStats = NetworkTrafficStats.getInstance();
            JsonObject network = new JsonObject();
            network.addProperty("uploadPackets", netStats.getGlobalUploadPackets());
            network.addProperty("uploadBytes", netStats.getGlobalUploadBytes());
            network.addProperty("downloadPackets", netStats.getGlobalDownloadPackets());
            network.addProperty("downloadBytes", netStats.getGlobalDownloadBytes());
            network.addProperty("averageUploadSpeedBps", Math.round(netStats.getGlobalAverageUploadSpeed() * 100.0) / 100.0);
            network.addProperty("averageDownloadSpeedBps", Math.round(netStats.getGlobalAverageDownloadSpeed() * 100.0) / 100.0);
            network.addProperty("trackedPlayerCount", netStats.getTrackedPlayerCount());
            root.add("network", network);

            // ---- 时间戳 ----
            root.addProperty("timestamp", System.currentTimeMillis());

            // 序列化为 JSON 字符串
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String jsonOutput = gson.toJson(root);

            // 输出 JSON(如果过长则分段)
            int maxMsgLength = 32000;
            if (jsonOutput.length() <= maxMsgLength) {
                source.sendSuccess(() -> Component.literal("§6=== [LingLens] 服务器综合信息 (JSON) ===\n" + jsonOutput),
                        false);
            } else {
                for (int offset = 0; offset < jsonOutput.length(); offset += maxMsgLength) {
                    int end = Math.min(offset + maxMsgLength, jsonOutput.length());
                    final int finalOffset = offset;
                    source.sendSuccess(() -> Component.literal(jsonOutput.substring(finalOffset, end)), false);
                }
            }

            LOGGER.info("[LingLens] list命令执行完成,输出JSON长度={}", jsonOutput.length());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] 收集服务器信息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] list命令异常: ", e);
            return 0;
        }
    }

    // ==================== list 子命令处理方法(JSON单模块输出) ====================

    /**
     * 执行 /linglens list system —— 仅输出系统性能信息的 JSON 字符串。
     * <p>包含 CPU 占用率、已用/已分配/最大内存。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListSystem(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            SystemPerfResult sysPerf = PerformanceQuery.getSystemPerf(server);

            JsonObject root = new JsonObject();
            root.addProperty("cpuPercent", Math.round(sysPerf.cpuPercent() * 100.0) / 100.0);
            root.addProperty("usedMemoryMB", Math.round(sysPerf.usedMemoryMB() * 100.0) / 100.0);
            root.addProperty("allocatedMemoryMB", Math.round(sysPerf.allocatedMemoryMB() * 100.0) / 100.0);
            root.addProperty("maxMemoryMB", Math.round(sysPerf.maxMemoryMB() * 100.0) / 100.0);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(root)), false);
            LOGGER.debug("[LingLens] list system 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list system 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListSystem 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens list perf —— 仅输出游戏性能信息的 JSON 字符串。
     * <p>包含 TPS、IdleTPS、MSPT、各维度 MSPT 详情。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListPerf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            PerformanceResult gamePerf = PerformanceQuery.getGamePerf(server);

            JsonObject game = new JsonObject();
            game.addProperty("tps", Math.round(gamePerf.tps() * 100.0) / 100.0);
            game.addProperty("idleTps", Math.round(gamePerf.idletps() * 100.0) / 100.0);
            game.addProperty("mspt", Math.round(gamePerf.mspt() * 100.0) / 100.0);
            JsonObject dimMspt = new JsonObject();
            if (gamePerf.dimensionMspt() != null) {
                for (var entry : gamePerf.dimensionMspt().entrySet()) {
                    dimMspt.addProperty(entry.getKey(), Math.round(entry.getValue() * 100.0) / 100.0);
                }
            }
            game.add("dimensionMspt", dimMspt);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(game)), false);
            LOGGER.debug("[LingLens] list perf 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list perf 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListPerf 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens list entity —— 仅输出实体统计信息的 JSON 字符串。
     * <p>包含全局实体总数、各维度统计、分类统计和 Top 类型。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListEntity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            EntityStatsCache entityCache = EntityStatsCache.getInstance();
            EntityQueryResult entityResult = entityCache.query(server);

            JsonObject entity = new JsonObject();
            entity.addProperty("globalTotal", entityResult.globalTotal);
            entity.addProperty("fromCache", entityResult.fromCache);
            entity.addProperty("cacheTime", entityResult.cacheTime);

            JsonObject dimTotals = new JsonObject();
            for (var entry : entityResult.dimensionTotals.entrySet()) {
                dimTotals.addProperty(entry.getKey(), entry.getValue());
            }
            entity.add("dimensionTotals", dimTotals);

            JsonObject dimCategories = new JsonObject();
            for (var dimEntry : entityResult.dimCatCounts.entrySet()) {
                JsonObject cats = new JsonObject();
                for (var catEntry : dimEntry.getValue().entrySet()) {
                    cats.addProperty(catEntry.getKey().name(), catEntry.getValue());
                }
                dimCategories.add(dimEntry.getKey(), cats);
            }
            entity.add("dimensionCategories", dimCategories);

            JsonObject dimTypes = new JsonObject();
            for (var dimEntry : entityResult.dimTypeCounts.entrySet()) {
                JsonObject types = new JsonObject();
                for (var typeEntry : dimEntry.getValue().entrySet()) {
                    types.addProperty(typeEntry.getKey(), typeEntry.getValue());
                }
                dimTypes.add(dimEntry.getKey(), types);
            }
            entity.add("dimensionTopTypes", dimTypes);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(entity)), false);
            LOGGER.debug("[LingLens] list entity 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list entity 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListEntity 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens list players —— 仅输出在线玩家信息的 JSON 字符串。
     * <p>包含在线玩家数量、最大玩家数及每个玩家的详细信息。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListPlayers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            List<PlayerInfo> players = PlayerInfoQuery.collectAllOnlinePlayers(server);

            JsonObject root = new JsonObject();
            JsonArray playerArray = new JsonArray();
            for (PlayerInfo p : players) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("name", p.name());
                pObj.addProperty("uuid", p.uuid());
                pObj.addProperty("dimension", p.dimension().toString());
                pObj.addProperty("x", Math.round(p.position().x * 100.0) / 100.0);
                pObj.addProperty("y", Math.round(p.position().y * 100.0) / 100.0);
                pObj.addProperty("z", Math.round(p.position().z * 100.0) / 100.0);
                pObj.addProperty("health", p.health());
                pObj.addProperty("maxHealth", p.maxHealth());
                pObj.addProperty("armor", p.armorValue());
                pObj.addProperty("foodLevel", p.foodLevel());
                pObj.addProperty("saturation", Math.round(p.saturation() * 100.0) / 100.0);
                pObj.addProperty("experienceLevel", p.experienceLevel());
                pObj.addProperty("experienceProgress", Math.round(p.experienceProgress() * 100.0) / 100.0);
                pObj.addProperty("gameMode", p.gameMode());
                pObj.addProperty("latency", p.latency());
                pObj.addProperty("sessionTimeSeconds", p.sessionTimeSeconds());
                pObj.addProperty("totalTimeSeconds", p.totalTimeSeconds());
                playerArray.add(pObj);
            }
            root.add("players", playerArray);
            root.addProperty("onlinePlayerCount", players.size());
            root.addProperty("maxPlayers", server.getMaxPlayers());

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(root)), false);
            LOGGER.debug("[LingLens] list players 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list players 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListPlayers 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens list chunks —— 仅输出区块信息的 JSON 字符串。
     * <p>包含总加载区块数和强制加载区块数。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListChunks(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            MinecraftServer server = source.getServer();
            ChunkQueryResult chunkResult = ChunkQueryResult.queryAll(server);

            JsonObject chunk = new JsonObject();
            chunk.addProperty("totalLoaded", chunkResult.getTotalLoaded());
            chunk.addProperty("totalForced", chunkResult.getTotalForced());

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(chunk)), false);
            LOGGER.debug("[LingLens] list chunks 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list chunks 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListChunks 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens list network —— 仅输出网络流量统计信息的 JSON 字符串。
     * <p>包含全局上传/下行统计、平均速度、以及实时速度。</p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeListNetwork(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            NetworkTrafficStats netStats = NetworkTrafficStats.getInstance();

            JsonObject network = new JsonObject();
            // 全局累计数据
            network.addProperty("uploadPackets", netStats.getGlobalUploadPackets());
            network.addProperty("uploadBytes", netStats.getGlobalUploadBytes());
            network.addProperty("downloadPackets", netStats.getGlobalDownloadPackets());
            network.addProperty("downloadBytes", netStats.getGlobalDownloadBytes());
            // 平均速度(字节/秒)
            network.addProperty("averageUploadSpeedBps", Math.round(netStats.getGlobalAverageUploadSpeed() * 100.0) / 100.0);
            network.addProperty("averageDownloadSpeedBps", Math.round(netStats.getGlobalAverageDownloadSpeed() * 100.0) / 100.0);
            // 被追踪的玩家数
            network.addProperty("trackedPlayerCount", netStats.getTrackedPlayerCount());

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            source.sendSuccess(() -> Component.literal(gson.toJson(network)), false);
            LOGGER.debug("[LingLens] list network 命令执行完成");
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[LingLens] list network 失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeListNetwork 异常: ", e);
            return 0;
        }
    }

    // ==================== Network 命令处理方法 ====================

    /**
     * 执行 /linglens network —— 全局网络流量总览。
     * <p>
     * 仅显示全局上传/下载的包数、字节数、平均速度,以及当前被追踪的玩家数。
     * 不包含玩家排行和数据包类型排行(由子命令提供)。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeNetworkOverview(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            NetworkTrafficStats stats = NetworkTrafficStats.getInstance();

            long upPackets = stats.getGlobalUploadPackets();
            long upBytes = stats.getGlobalUploadBytes();
            long downPackets = stats.getGlobalDownloadPackets();
            long downBytes = stats.getGlobalDownloadBytes();
            double upSpeed = stats.getGlobalAverageUploadSpeed();
            double downSpeed = stats.getGlobalAverageDownloadSpeed();
            int playerCount = stats.getTrackedPlayerCount();

            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 网络流量总览 ===\n");
            sb.append("§f↑ §a").append(formatFileSize(upBytes)).append(" §7(").append(upPackets).append(")  §a")
                    .append(upSpeed >= 0 ? formatSpeed(upSpeed) : "§c统计中...").append("\n");
            sb.append("§f↓ §a").append(formatFileSize(downBytes)).append(" §7(").append(downPackets).append(")  §a")
                    .append(downSpeed >= 0 ? formatSpeed(downSpeed) : "§c统计中...").append("\n");

            sb.append("§f当前追踪玩家数: §a").append(playerCount).append("\n");

            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            LOGGER.debug("[LingLens] 网络流量总览: 上传={}B/{}pkt, 下载={}B/{}pkt, 玩家={}", upBytes, upPackets, downBytes,
                    downPackets, playerCount);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c查询网络流量总览失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeNetworkOverview 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens network players —— 玩家流量排行。
     * <p>
     * 列出所有被追踪玩家的流量数据,按上传+下行总量降序排列,
     * 显示每个玩家的名称、上传/下行字节数及总量。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeNetworkPlayers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            NetworkTrafficStats stats = NetworkTrafficStats.getInstance();
            Map<UUID, PlayerTrafficData> allStats = stats.getAllStats();

            if (allStats.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 暂无玩家流量数据"), false);
                return 1;
            }

            MinecraftServer server = source.getServer();

            StringBuilder sb = new StringBuilder();
            // sb.append("§6=== [LingLens] 玩家流量排行 ===\n");
            // sb.append("§7排名 | 玩家名 | 上传 | 下行 | 总量\n");

            // 按上传+下行总量从大到小排序
            final int[] rank = { 1 };
            allStats.entrySet().stream()
                    .sorted((a, b) -> {
                        long totalA = a.getValue().getUploadBytes() + a.getValue().getDownloadBytes();
                        long totalB = b.getValue().getUploadBytes() + b.getValue().getDownloadBytes();
                        return Long.compare(totalB, totalA);
                    })
                    .forEach(entry -> {
                        UUID uuid = entry.getKey();
                        PlayerTrafficData data = entry.getValue();
                        // 尝试获取玩家名,若不在线则用 UUID 前 8 位
                        String playerName = server.getPlayerList().getPlayer(uuid) != null
                                ? server.getPlayerList().getPlayer(uuid).getName().getString()
                                : uuid.toString().substring(0, 8) + "...";
                        long upBytes = data.getUploadBytes();
                        long downBytes = data.getDownloadBytes();
                        long totalBytes = upBytes + downBytes;
                        long upPackets = data.getUploadPackets();
                        long downPackets = data.getDownloadPackets();

                        sb.append("§f").append(rank[0]).append(". §b").append(playerName).append(" §f:\n");
                        sb.append(" §7↑ §a").append(formatFileSize(upBytes))
                                .append(" §8(").append(upPackets).append(")");
                        sb.append(" §7↓ §a").append(formatFileSize(downBytes))
                                .append(" §8(").append(downPackets).append(") §e").append(formatFileSize(totalBytes))
                                .append("\n");
                        rank[0]++;
                    });

            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            LOGGER.debug("[LingLens] 玩家流量排行查询,共 {} 名玩家", allStats.size());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c查询玩家流量排行失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeNetworkPlayers 异常: ", e);
            return 0;
        }
    }

    /**
     * 执行 /linglens network packet —— 数据包流量排行。
     * <p>
     * 聚合所有玩家的数据包类型统计,按字节数降序排列显示 Top 10。
     * 包类型按可读名称(如 "login.start"、"play.chunkData")显示。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 1 成功,0 失败
     */
    private static int executeNetworkPackets(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            NetworkTrafficStats stats = NetworkTrafficStats.getInstance();

            // 聚合所有玩家的数据包类型统计
            // 上传类型: 包名 -> {总包数, 总字节数}
            Map<String, long[]> uploadTypes = new HashMap<>(); // long[0]=包数, long[1]=字节数
            Map<String, long[]> downloadTypes = new HashMap<>();

            for (Map.Entry<UUID, PlayerTrafficData> entry : stats.getAllStats().entrySet()) {
                PlayerTrafficData data = entry.getValue();
                synchronized (data) {
                    // 上传类型
                    for (Map.Entry<String, PacketTypeInfo> typeEntry : data.getUploadTypeStats().entrySet()) {
                        PacketTypeInfo info = typeEntry.getValue();
                        String typeName = typeEntry.getKey();
                        long[] agg = uploadTypes.computeIfAbsent(typeName, k -> new long[2]);
                        agg[0] += info.getPacketCount();
                        agg[1] += info.getTotalBytes();
                    }
                    // 下行类型
                    for (Map.Entry<String, PacketTypeInfo> typeEntry : data.getDownloadTypeStats().entrySet()) {
                        PacketTypeInfo info = typeEntry.getValue();
                        String typeName = typeEntry.getKey();
                        long[] agg = downloadTypes.computeIfAbsent(typeName, k -> new long[2]);
                        agg[0] += info.getPacketCount();
                        agg[1] += info.getTotalBytes();
                    }
                }
            }

            boolean hasUpload = !uploadTypes.isEmpty();
            boolean hasDownload = !downloadTypes.isEmpty();

            if (!hasUpload && !hasDownload) {
                source.sendSuccess(() -> Component.literal("§e[LingLens] 暂无数据包类型统计"), false);
                return 1;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("§6=== [LingLens] 数据包流量排行 ===\n");

            // ---- 上传类型排行 ----
            if (hasUpload) {
                sb.append("§6=== 上传数据包 Top 10 (按字节数) ===\n");
                uploadTypes.entrySet().stream()
                        .sorted(Map.Entry.<String, long[]>comparingByValue(
                                (a, b) -> Long.compare(b[1], a[1]))) // 按字节数降序
                        .limit(10)
                        .forEach(e -> {
                            String typeName = e.getKey();
                            long[] val = e.getValue();
                            long pkts = val[0];
                            long bytes = val[1];
                            sb.append(" §b")
                                    .append(typeName).append(" §7: ");
                            sb.append("§a").append(formatFileSize(bytes));
                            sb.append(" §7: §a").append(pkts).append("\n");
                        });
            }

            // ---- 下行类型排行 ----
            if (hasDownload) {
                sb.append("§6=== 下行数据包 Top 10 (按字节数) ===\n");
                sb.append("§7数据包类型 | 总量 | 包数\n");
                downloadTypes.entrySet().stream()
                        .sorted(Map.Entry.<String, long[]>comparingByValue(
                                (a, b) -> Long.compare(b[1], a[1]))) // 按字节数降序
                        .limit(10)
                        .forEach(e -> {
                            String typeName = e.getKey();
                            long[] val = e.getValue();
                            long pkts = val[0];
                            long bytes = val[1];
                            sb.append(" §b")
                                    .append(typeName).append(" §7: ");
                            sb.append("§a").append(formatFileSize(bytes));
                            sb.append(" §7: §a").append(pkts).append("\n");
                        });
            }

            source.sendSuccess(() -> Component.literal(sb.toString()), false);
            LOGGER.debug("[LingLens] 数据包流量排行查询: 上传类型={}种, 下行类型={}种", uploadTypes.size(), downloadTypes.size());
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c查询数据包流量排行失败: " + e.getMessage()));
            LOGGER.error("[LingLens] executeNetworkPackets 异常: ", e);
            return 0;
        }
    }

    /**
     * 格式化速度(字节/秒)为可读字符串。
     *
     * @param bytesPerSecond 字节每秒
     * @return 形如 "1.23 KB/s" 的字符串
     */
    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 0)
            return "N/A";
        if (bytesPerSecond < 1024) {
            return String.format("%.2f B/s", bytesPerSecond);
        } else if (bytesPerSecond < 1024 * 1024) {
            return String.format("%.2f KB/s", bytesPerSecond / 1024.0);
        } else {
            return String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        }
    }
}
