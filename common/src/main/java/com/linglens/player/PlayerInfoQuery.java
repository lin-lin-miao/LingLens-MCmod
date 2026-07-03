package com.linglens.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.linglens.annotation.IdleTickSave;
import com.linglens.manager.IdleTickManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家信息查询工具类。
 * <p>
 * 负责采集在线玩家的各项状态信息，并记录玩家的登录/登出时间
 * 以计算本次在线时长和历史总在线时长。
 * 玩家加入/离开事件由各平台事件监听器（FabricPlayerEventListener / ForgePlayerEventListener）触发。
 * </p>
 *
 * <h3>数据采集来源</h3>
 * <ul>
 * <li>玩家名称: {@code ServerPlayer.getGameProfile().getName()}</li>
 * <li>UUID: {@code ServerPlayer.getUUID()}</li>
 * <li>在线时长: {@code System.currentTimeMillis() - 登录时间戳}</li>
 * <li>当前位置: {@code ServerPlayer.position()}</li>
 * <li>当前维度: {@code ServerPlayer.level().dimension().location()}</li>
 * <li>生命值: {@code ServerPlayer.getHealth()}</li>
 * <li>最大生命值: {@code ServerPlayer.getMaxHealth()}</li>
 * <li>盔甲值: {@code ServerPlayer.getArmorValue()}</li>
 * <li>饥饿值: {@code ServerPlayer.getFoodData().getFoodLevel()}</li>
 * <li>饱和浓度: {@code ServerPlayer.getFoodData().getSaturationLevel()}</li>
 * <li>经验等级: {@code ServerPlayer.experienceLevel}</li>
 * <li>经验进度: {@code ServerPlayer.experienceProgress}</li>
 * <li>游戏模式: {@code ServerPlayer.gameMode.getGameModeForPlayer()}</li>
 * <li>延迟(Ping): {@code ServerPlayer.latency}</li>
 * </ul>
 */
public final class PlayerInfoQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    /**
     * 脏标记，记录数据是否已被修改（玩家登录/登出）。
     * 用于避免无数据变更时重复保存文件。
     */
    private static boolean dirty = false;

    /**
     * 存储玩家UUID {@literal ->} 本次登录时间戳（毫秒）的映射。
     * 在玩家登录时记录，登出时移除。
     */
    private static final Map<UUID, Long> loginTimestamps = new ConcurrentHashMap<>();

    /**
     * 存储玩家UUID {@literal ->} 累计在线时长（秒）的映射。
     * 数据持久化到存档文件夹中的 linglens_playtime.json。
     */
    private static final Map<UUID, Long> totalPlayTime = new ConcurrentHashMap<>();

    /** 持久化文件，相对于存档目录 */
    private static File dataFile = null;

    /** Gson 实例 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 防止实例化
    private PlayerInfoQuery() {
    }

    // ==================== 持久化 ====================

    /**
     * 设置持久化文件路径（相对于世界存档目录）。
     * <p>
     * 应在服务器启动时由各平台主类调用，传入世界存档目录，
     * 例如 {@code new File(worldDirectory, "linglens/playtime.json")}。
     * </p>
     * 
     * @param worldDirectory 世界存档目录（可由 MinecraftServer.getWorldPath 或存储目录获取）
     */
    public static void setDataFile(File worldDirectory) {
        File dir = new File(worldDirectory, "linglens");
        dataFile = new File(dir, "playtime.json");
        LOGGER.debug("[LingLens] 玩家在线时长数据文件路径已设置: {}", dataFile.getAbsolutePath());
    }

    /**
     * 从存档文件夹加载历史在线时长数据。
     * <p>
     * 应在设置 dataFile 后、服务器完全启动前（或首次查询前）调用。
     * </p>
     */
    public static void loadFromFile() {
        if (dataFile == null || !dataFile.exists()) {
            return;
        }
        try (Reader reader = new FileReader(dataFile)) {
            Type type = new TypeToken<Map<String, Long>>() {
            }.getType();
            Map<String, Long> raw = GSON.fromJson(reader, type);
            if (raw != null) {
                totalPlayTime.clear();
                for (Map.Entry<String, Long> entry : raw.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        totalPlayTime.put(uuid, entry.getValue());
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[LingLens] 忽略无效的UUID键: {}", entry.getKey());
                    }
                }
                LOGGER.info("[LingLens] 玩家在线时长数据已加载，共 {} 条记录", totalPlayTime.size());
            }
        } catch (Exception e) {
            LOGGER.error("[LingLens] 加载玩家在线时长数据失败: ", e);
        }
    }

    static {
        // 注册本类到待扫描列表，等待服务器启动后由 IdleTickManager 自动扫描注解
        IdleTickManager.registerPendingClass(PlayerInfoQuery.class);
    }

    /**
     * 将历史在线时长数据保存到存档文件夹。
     * <p>
     * 保存策略：
     * <ul>
     * <li>服务器停止时手动调用</li>
     * <li>定时自动保存（每 60 秒由 {@link #IdleTickSave()} 触发）</li>
     * </ul>
     * 注意：保存时会强制将当前在线玩家的 session 时间加入累计值，防止 auto-save 丢失数据。
     * </p>
     */
    @IdleTickSave
    public static void saveToFile() {
        if (dataFile == null) {
            LOGGER.error("[LingLens] 保存玩家在线时长数据失败: 无保存位置");
            return;
        }
        // 若没有数据变动且没有在线玩家，跳过保存；有在线玩家时强制保存（保证在线时长实时持久化）
        if (!dirty && loginTimestamps.isEmpty()) {
            return;
        }
        dataFile.getParentFile().mkdirs();
        try (Writer writer = new FileWriter(dataFile)) {
            Map<String, Long> raw = new HashMap<>();
            long now = System.currentTimeMillis();
            // 合并 totalPlayTime 与 loginTimestamps（在线玩家）
            Set<UUID> allUuids = new HashSet<>();
            allUuids.addAll(totalPlayTime.keySet());
            allUuids.addAll(loginTimestamps.keySet());
            for (UUID uuid : allUuids) {
                long accumulated = totalPlayTime.getOrDefault(uuid, 0L);
                Long loginTime = loginTimestamps.get(uuid);
                if (loginTime != null) {
                    // 玩家当前在线，计算本次 session 时长并累加
                    long sessionDuration = (now - loginTime) / 1000;
                    accumulated += sessionDuration;
                }
                raw.put(uuid.toString(), accumulated);
            }
            GSON.toJson(raw, writer);
            dirty = false;
            LOGGER.debug("[LingLens] 玩家在线时长数据已保存（含在线玩家 session），共 {} 条记录", raw.size());
        } catch (Exception e) {
            LOGGER.error("[LingLens] 保存玩家在线时长数据失败: ", e);
        }
    }

    // ==================== 在线时长管理 ====================

    /**
     * 记录玩家登录时间。
     * <p>
     * 当玩家加入服务器时由事件监听器调用此方法，
     * 保存登录时间戳，用于计算本次在线时长。
     * 若该玩家已有记录则覆盖。
     * </p>
     *
     * @param playerUUID 玩家UUID
     */
    public static void recordLogin(UUID playerUUID) {
        long now = System.currentTimeMillis();
        loginTimestamps.put(playerUUID, now);
        LOGGER.debug("[LingLens] 记录玩家登录时间戳: UUID={}, time={}", playerUUID, now);
    }

    /**
     * 记录玩家登出并计算本次在线时长，累加到总时长中。
     * <p>
     * 当玩家离开服务器时由事件监听器调用此方法，
     * 清理登录时间戳并累计在线时长。
     * 如果该玩家没有登录记录则忽略，防止空指针。
     * </p>
     *
     * @param playerUUID 玩家UUID
     */
    public static void recordLogout(UUID playerUUID) {
        Long loginTime = loginTimestamps.remove(playerUUID);
        if (loginTime != null) {
            long sessionDuration = (System.currentTimeMillis() - loginTime) / 1000;
            totalPlayTime.merge(playerUUID, sessionDuration, Long::sum);
            dirty = true; // 数据发生变更
            LOGGER.debug("[LingLens] 记录玩家登出: UUID={}, 本次在线={}s", playerUUID, sessionDuration);
        }
    }

    /**
     * 获取本次在线时长（秒）。
     *
     * @param playerUUID 玩家UUID
     * @return 本次在线时长（秒），若无登录记录则返回0
     */
    public static long getSessionTime(UUID playerUUID) {
        Long loginTime = loginTimestamps.get(playerUUID);
        if (loginTime == null) {
            return 0;
        }
        return (System.currentTimeMillis() - loginTime) / 1000;
    }

    /**
     * 获取总在线时长（秒）。
     * <p>
     * 返回值 = 历史累计总时长 + 本次在线时长。
     * 若玩家从未记录过，则返回0。
     * </p>
     *
     * @param playerUUID 玩家UUID
     * @return 总在线时长（秒）
     */
    public static long getTotalPlayTime(UUID playerUUID) {
        long accumulated = totalPlayTime.getOrDefault(playerUUID, 0L);
        return accumulated + getSessionTime(playerUUID);
    }

    // ==================== 信息采集 ====================

    /**
     * 采集单个在线玩家的信息。
     * <p>
     * 从 ServerPlayer 对象中提取所有可查询的状态信息，
     * 封装为 {@link PlayerInfo} 记录。
     * </p>
     *
     * @param player 在线玩家对象（不能为 null）
     * @return 玩家信息数据模型，若采集过程中发生异常则返回 null
     */
    public static PlayerInfo collectPlayerInfo(ServerPlayer player) {
        try {
            // 基础信息
            String name = player.getGameProfile().getName();
            String uuid = player.getUUID().toString();

            // 位置与维度
            var position = player.position();
            ResourceLocation dimension = player.level().dimension().location();

            // 生命状态
            float health = player.getHealth();
            float maxHealth = player.getMaxHealth();
            int armorValue = player.getArmorValue();
            int foodLevel = player.getFoodData().getFoodLevel();
            float saturation = player.getFoodData().getSaturationLevel();

            // 经验值
            int expLevel = player.experienceLevel;
            float expProgress = player.experienceProgress;

            // 游戏模式
            GameType gameType = player.gameMode.getGameModeForPlayer();
            String gameMode = gameType.getName();

            // 延迟
            int latency = player.latency;

            // 在线时长
            UUID playerUuid = player.getUUID();
            long sessionTime = getSessionTime(playerUuid);
            long totalTime = getTotalPlayTime(playerUuid);

            return new PlayerInfo(
                    name, uuid, position, dimension,
                    health, maxHealth, armorValue, foodLevel, saturation,
                    expLevel, expProgress, gameMode,
                    latency, sessionTime, totalTime);
        } catch (Exception e) {
            LOGGER.error("[LingLens] 采集玩家信息时发生错误: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 采集所有在线玩家的信息列表。
     * <p>
     * 遍历 {@link net.minecraft.server.players.PlayerList#getPlayers()}，
     * 收集每个在线玩家的信息，并按名称排序。
     * </p>
     *
     * @param server Minecraft服务器实例
     * @return 所有在线玩家的信息列表（按名称排序，不会包含 null 元素）
     */
    public static List<PlayerInfo> collectAllOnlinePlayers(MinecraftServer server) {
        List<PlayerInfo> result = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PlayerInfo info = collectPlayerInfo(player);
            if (info != null) {
                result.add(info);
            }
        }
        result.sort(Comparator.comparing(PlayerInfo::name));
        return result;
    }

    // ==================== 命令输出构建 ====================

    /**
     * 构建在线玩家概要列表消息。
     * <p>
     * 格式示例：
     * 
     * <pre>
     * === 在线玩家列表 (3 / 20) ===
     * 1. Alice [overworld] 123.1, 64.0, -45.5 | HP: 20.0/20.0 | 盔甲: 10
     *    在线: 2h 15m (+26m) | 延迟: 45ms
     * 2. Bob [the_end] -100.5, 80.0, 200.3 | HP: 18.0/20.0 | 盔甲: 6
     *    在线: 1h 30m (+5m) | 延迟: 120ms
     * </pre>
     * </p>
     *
     * @param infoList   玩家信息列表
     * @param maxPlayers 服务器最大玩家数
     * @return 格式化的消息组件列表，每行一个 Component
     */
    public static List<Component> buildPlayerListMessage(List<PlayerInfo> infoList, int maxPlayers) {
        List<Component> messages = new ArrayList<>();

        // 标题行
        MutableComponent title = Component.literal("=== 在线玩家列表 (")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.valueOf(infoList.size())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" / ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(String.valueOf(maxPlayers)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(") ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        messages.add(title);

        if (infoList.isEmpty()) {
            messages.add(Component.literal("暂无在线玩家").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return messages;
        }

        // 玩家列表行
        int index = 1;
        for (PlayerInfo info : infoList) {
            messages.add(buildPlayerSummaryLine(index, info));
            index++;
        }

        return messages;
    }

    /**
     * 构建单个玩家的概要行（用于列表显示）。
     * <p>
     * 格式：两行结构
     * 第一行：序号. 名称 [维度] 坐标 | HP: x/x | 盔甲: x
     * 第二行（缩进）：在线: 总时长 (+本次时长) | 延迟: xms
     * </p>
     *
     * @param index 序号（从1开始）
     * @param info  玩家信息
     * @return 格式化的消息组件
     */
    private static Component buildPlayerSummaryLine(int index, PlayerInfo info) {
        MutableComponent line = Component.literal("");

        // 序号
        line.append(Component.literal(index + ". ").withStyle(ChatFormatting.DARK_GRAY));

        // 玩家名称
        line.append(Component.literal(info.name()).withStyle(ChatFormatting.WHITE));

        // 维度
        line.append(Component.literal(" [" + info.getDimensionShortName() + "]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)));

        // 坐标
        // line.append(Component.literal(" " + info.getPositionString())
        // .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));

        // 分隔符
        line.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        // 生命值
        line.append(Component.literal("HP: ").withStyle(ChatFormatting.GRAY));
        line.append(Component.literal(info.getHealthString()).withStyle(ChatFormatting.WHITE));

        // 盔甲值
        line.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        line.append(Component.literal("盔甲: ").withStyle(ChatFormatting.GRAY));
        String armorStr = info.getArmorString();
        int armorVal = info.armorValue();
        line.append(Component.literal(armorStr).withStyle(
                armorVal >= 10 ? ChatFormatting.GREEN : (armorVal >= 5 ? ChatFormatting.YELLOW : ChatFormatting.GOLD)));

        // 换行 + 缩进显示在线时长和延迟（第二行）
        MutableComponent secondLine = Component.literal("    ");

        // 在线时长
        secondLine.append(Component.literal("在线: ").withStyle(ChatFormatting.GRAY));
        secondLine.append(Component.literal(info.getTotalTimeString()).withStyle(ChatFormatting.GREEN));

        // 本次在线时长（加号显示，超过1分钟才显示）
        long sessionSec = info.sessionTimeSeconds();
        if (sessionSec > 60) {
            secondLine.append(Component.literal(" (+" + info.getSessionTimeString() + ")")
                    .withStyle(ChatFormatting.DARK_GREEN));
        }

        // 延迟
        secondLine.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        secondLine.append(Component.literal("延迟: ").withStyle(ChatFormatting.GRAY));
        secondLine.append(Component.literal(info.latency() + "ms")
                .withStyle(getLatencyColor(info.latency())));
        line.append(Component.literal("\n"));
        line.append(secondLine);
        return line;
    }

    /**
     * 构建单个玩家的详细信息消息。
     * <p>
     * 格式示例：
     * 
     * <pre>
     * === 玩家详细信息: Alice ===
     * UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
     * 位置: 123.1, 64.0, -45.5 [overworld]
     * 状态: HP 20.0/20.0 | 盔甲 10 | 饥饿 20 (+5.0) | 等级 42 (25%)
     * 在线: 总 10h 30m 15s (本次 26m 30s)
     * 延迟: 45ms | 模式: survival
     * </pre>
     * </p>
     *
     * @param info 玩家信息
     * @return 格式化的消息组件列表，每行一个 Component
     */
    public static List<Component> buildPlayerDetailMessage(PlayerInfo info) {
        List<Component> messages = new ArrayList<>();

        // 标题
        MutableComponent title = Component.literal("=== 玩家详细信息: ")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(info.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        messages.add(title);

        // UUID
        MutableComponent uuidLine = Component.literal("")
                .append(Component.literal("UUID: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(info.uuid()).withStyle(ChatFormatting.WHITE));
        messages.add(uuidLine);

        // 位置与维度
        MutableComponent posLine = Component.literal("")
                .append(Component.literal("位置: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(info.getPositionString()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" [" + info.getDimensionShortName() + "]")
                        .withStyle(ChatFormatting.DARK_AQUA));
        messages.add(posLine);

        // 状态：生命值、盔甲、饥饿值、经验
        MutableComponent statusLine = Component.literal("")
                .append(Component.literal("状态: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("HP ").withStyle(ChatFormatting.RED))
                .append(Component.literal(info.getHealthString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("盔甲 ").withStyle(ChatFormatting.BLUE))
                .append(Component.literal(info.getArmorString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("饥饿 ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(info.getFoodString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("等级 ").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(String.valueOf(info.experienceLevel())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + (int) (info.experienceProgress() * 100) + "%)")
                        .withStyle(ChatFormatting.GRAY));
        messages.add(statusLine);

        // 在线时长
        MutableComponent timeLine = Component.literal("")
                .append(Component.literal("在线: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("总 " + info.getTotalTimeString()).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (本次 " + info.getSessionTimeString() + ")")
                        .withStyle(ChatFormatting.DARK_GREEN));
        messages.add(timeLine);

        // 延迟与游戏模式
        MutableComponent miscLine = Component.literal("")
                .append(Component.literal("延迟: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(info.latency() + "ms").withStyle(getLatencyColor(info.latency())))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("模式: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(info.gameMode()).withStyle(getGameModeColor(info.gameMode())));
        messages.add(miscLine);

        return messages;
    }

    /**
     * 构建"玩家未找到"提示消息。
     *
     * @param playerName 查询的玩家名称
     * @return 红色提示消息
     */
    public static Component buildPlayerNotFoundMessage(String playerName) {
        return Component.literal("玩家 \"" + playerName + "\" 不在线或不存在")
                .withStyle(ChatFormatting.RED);
    }

    // ==================== 辅助方法 ====================

    /**
     * 根据延迟值返回对应的聊天颜色。
     * <ul>
     * <li>{@code <= 50ms} - 绿色（良好）</li>
     * <li>{@code <= 100ms} - 黄色（一般）</li>
     * <li>{@code <= 200ms} - 金色（较差）</li>
     * <li>{@code > 200ms} - 红色（很差）</li>
     * </ul>
     *
     * @param latency 延迟（毫秒）
     * @return 对应的聊天颜色样式
     */
    private static Style getLatencyColor(int latency) {
        if (latency <= 50) {
            return Style.EMPTY.withColor(ChatFormatting.GREEN);
        } else if (latency <= 100) {
            return Style.EMPTY.withColor(ChatFormatting.YELLOW);
        } else if (latency <= 200) {
            return Style.EMPTY.withColor(ChatFormatting.GOLD);
        } else {
            return Style.EMPTY.withColor(ChatFormatting.RED);
        }
    }

    /**
     * 根据游戏模式返回对应的聊天颜色。
     * <ul>
     * <li>survival - 绿色</li>
     * <li>creative - 淡紫色</li>
     * <li>adventure - 金色</li>
     * <li>spectator - 灰色</li>
     * </ul>
     *
     * @param gameMode 游戏模式名称（不区分大小写）
     * @return 对应的聊天颜色样式
     */
    private static Style getGameModeColor(String gameMode) {
        return switch (gameMode.toLowerCase()) {
            case "survival" -> Style.EMPTY.withColor(ChatFormatting.GREEN);
            case "creative" -> Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
            case "adventure" -> Style.EMPTY.withColor(ChatFormatting.GOLD);
            case "spectator" -> Style.EMPTY.withColor(ChatFormatting.GRAY);
            default -> Style.EMPTY.withColor(ChatFormatting.WHITE);
        };
    }

    /**
     * 按名称查找在线玩家。
     * <p>
     * 遍历 {@link net.minecraft.server.players.PlayerList#getPlayers()}，
     * 使用不区分大小写的名称匹配。
     * </p>
     *
     * @param server     Minecraft服务器实例
     * @param playerName 玩家名称
     * @return 如果在线返回对应的 ServerPlayer，否则返回 null
     */
    public static ServerPlayer findPlayerByName(MinecraftServer server, String playerName) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }
}