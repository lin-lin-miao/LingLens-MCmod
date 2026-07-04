package com.linglens.command;

import com.linglens.entity.EntityStatsCache;
import com.linglens.entity.EntityQueryResult;
import com.linglens.manager.TeleportManager;
import com.linglens.performance.PerformanceQuery;
import com.linglens.performance.SystemPerfResult;
import com.linglens.performance.PerformanceResult;
import com.linglens.chunk.ChunkQueryResult;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;

import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.linglens.player.PlayerInfo;
import com.linglens.player.PlayerInfoQuery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 灵棱枢 (LingLens) 命令注册与处理类。
 * 包含五个命令组：
 * <ul>
 * <li>offline-tp — 离线玩家位置修改</li>
 * <li>perf — 游戏/系统性能查询</li>
 * <li>entity — 实体数量查询与统计</li>
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

        // /linglens perf system —— 系统性能（CPU / 内存）
        perfCommand.then(Commands.literal("system")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    SystemPerfResult sysResult = PerformanceQuery.getSystemPerf(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(getFormatSysmsg(sysResult)),
                            false);
                    return 1;
                }));

        // /linglens perf tps —— 游戏性能（TPS + 各维度 MSPT）
        perfCommand.then(Commands.literal("tps")
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    PerformanceResult gameResult = PerformanceQuery.query(server);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(getFormatGamemsg(server, gameResult)),
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

        // /linglens entity —— 实体数量统计（常规查询）
        entityCommand.executes(ctx -> {
            MinecraftServer server = ctx.getSource().getServer();
            EntityStatsCache cache = EntityStatsCache.getInstance();
            EntityQueryResult result = cache.query(server);
            ctx.getSource().sendSuccess(
                    () -> result.toReadableString(),
                    false);
            return 1;
        });

        // /linglens entity rebuild —— 手动重建缓存（需要 OP 权限）
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
                            () -> Component.literal("§a[LingLens] 实体统计缓存重建完成，耗时 "
                                    + elapsed + " ms，当前状态: READY"),
                            true);
                    LOGGER.info("[LingLens] 手动触发实体缓存重建，耗时 {} ms", elapsed);
                    return 1;
                }));
        // /linglens entity setdirty —— 手动设脏，使缓存失效（需要 OP 权限）
        entityCommand.then(Commands.literal("setdirty")
                .requires(src -> src.hasPermission(4))
                .executes(ctx -> {
                    EntityStatsCache cache = EntityStatsCache.getInstance();
                    cache.setDirty("管理员手动设脏");
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("§e[LingLens] 实体统计缓存已手动设为 DIRTY（下次查询将自动重建）"),
                            true);
                    return 1;
                }));

        // /linglens entity status —— 查看缓存状态（需要 OP 权限）
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

        // ========== players 命令（在线玩家列表概要） ==========
        LiteralArgumentBuilder<CommandSourceStack> playersCommand = Commands.literal("players");
        playersCommand.executes(ModCommands::executePlayersList);
        // ========== player 命令（单个玩家详细信息） ==========
        playersCommand.then(Commands.argument("name", StringArgumentType.word())
                .requires(src -> src.hasPermission(4))
                .suggests(PLAYER_SUGGESTIONS)
                .executes(ModCommands::executePlayerDetail));

        LiteralArgumentBuilder<CommandSourceStack> killableCommand = Commands.literal("killable");
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

                        // targetPlayer.remove(Entity.RemovalReason.KILLED);
                        server.getPlayerList().broadcastSystemMessage(
                                Component.literal("§c" + playerName + " 被猫猫处决了！"),
                                false
                        );
                        targetPlayer.setHealth(0);
                        targetPlayer.die(targetPlayer.damageSources().anvil(targetPlayer));
                        source.sendSuccess(
                            () -> Component.literal(playerName+"已处决"),
                            false);
                        
                    } catch (Exception e) {
                        LOGGER.error("[LingLens] 查询玩家详细信息异常: ", e);
                        return 0;
                    }
                    return 1;
                }));
        playersCommand.then(killableCommand);
        root.then(playersCommand);

        dispatcher.register(root);
        LOGGER.info("[LingLens] 命令已注册(Command registered)");
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
        // ----- 游戏性能部分（使用 MessageFormat）-----
        StringBuilder gamemsg = new StringBuilder();
        MessageFormat gameMf = new MessageFormat(
                "§6=== [ 游戏性能 ] ===\n" +
                        "§fTPS: §{0}{1} / §{2}{3}\n" +
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
            // 寻找最高MSPT维度（值最大，即最卡的维度）
            Map.Entry<String, Double> maxEntry = gameResult.dimensionMspt().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (maxEntry != null) {
                String dimName = maxEntry.getKey().replace("minecraft:", "");
                gamemsg.append("§e最高MSPT维度: §a").append(dimName)
                        .append(" §f").append(String.format("%.2f", maxEntry.getValue())).append(" ms\n");
            }
            // 按MSPT从大到小排序（越卡越靠前）
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

    // ==================== 在线玩家信息查询命令 ====================

    /**
     * 执行 /linglens players 命令。
     * <p>
     * 列出所有在线玩家的概要信息，包括名称、维度、坐标、生命值、在线时长和延迟。
     * 按玩家名称排序。
     * </p>
     *
     * @param ctx 命令上下文
     * @return 命令执行结果码（1=成功，0=失败）
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

            LOGGER.debug("[LingLens] 已执行在线玩家列表查询，当前在线 {} 人", playerInfos.size());
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
     * 查询单个玩家的详细信息，包括 UUID、位置、维度、生命值、饥饿值、经验等级、
     * 在线时长、延迟和游戏模式，以及装备信息（主手、副手、盔甲栏）。
     * 如果玩家不在线，返回错误提示。
     * </p>
     *
     * @param ctx 命令上下文，包含 "name" 参数
     * @return 命令执行结果码（1=成功，0=失败/玩家不在线）
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

            // 合并所有基础消息为一条，一次性发送
            List<Component> messages = PlayerInfoQuery.buildPlayerDetailMessage(info);
            MutableComponent detailMessage = Component.literal("");
            for (int i = 0; i < messages.size(); i++) {
                detailMessage.append(messages.get(i));
            }
            detailMessage.append(Component.literal("\n"));
            // 添加玩家装备信息（含主手、副手和盔甲）
            detailMessage.append(buildEquipmentComponent(targetPlayer));

            source.sendSuccess(() -> detailMessage, false);

            LOGGER.debug("[LingLens] 已查询玩家 {} 的详细信息（含装备）", playerName);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("查询玩家详细信息失败: " + e.getMessage()));
            LOGGER.error("[LingLens] 查询玩家详细信息异常: ", e);
            return 0;
        }
    }

    // ==================== 装备信息辅助方法 ====================

    /** 可交互物品栏位名称常量数组（与 getArmorSlots 顺序匹配） */
    private static final String[] ARMOR_SLOT_NAMES = { "靴子", "护腿", "胸甲", "头盔" };

    /**
     * 构建玩家装备信息的可交互组件。
     * <p>
     * 包括主手、副手和盔甲栏物品，悬停可查看物品详情（SHOW_ITEM格式），
     * 点击将对应的 /give 指令填入输入框（不自动执行）。
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

        // 盔甲栏（getArmorSlots 返回顺序：脚→腿→胸→头）
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
     * 非空物品显示名称和注册 ID，悬停时显示 MC 原版 SHOW_ITEM 提示框，
     * 点击时将 /give 指令填入输入框（SUGGEST_COMMAND 便于玩家修改）。
     * </p>
     *
     * @param slotName 栏位名称（如 "主手"、"头盔"）
     * @param stack    该栏位中的物品栈
     * @return 可交互的 Component
     */
    private static Component buildSlotComponent(String slotName, ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.literal("§7" + slotName + ": 空");
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Component itemName = stack.getDisplayName();

        // 构造带完整 NBT 的 /give 指令，确保点击获取时得到完全一致的物品（含附魔、自定义名称、 lore 等）
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
