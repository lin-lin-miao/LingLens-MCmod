package com.linglens.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 灵棱枢 (LingLens) 全局配置文件管理器。
 * <p>
 * 配置文件路径：<游戏目录>/config/linglens.json
 * 所有配置项均可通过命令 /linglens config 查询与修改。
 * </p>
 *
 * <p>
 * 首次创建时，日志会输出每项配置的中文说明。各模块在运行时通过调用
 * {@link #get...()} 方法获取配置值，而非使用硬编码常量。
 * </p>
 */
public class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    // 单例
    private static final ConfigManager INSTANCE = new ConfigManager();

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    // ================================================================
    // 配置默认值
    // ================================================================

    // ----- EntityStatsCache -----
    /** 事件风暴阈值：每秒事件数超过此值则视为事件风暴，缓存自动降级为 DIRTY */
    private volatile long eventStormThreshold = 2000L;

    /** 事件风暴检测间隔（毫秒） */
    private volatile long checkIntervalMs = 1000L;

    // ----- ChatCache -----
    /** 聊天缓存最大消息数（内存环形队列容量） */
    private volatile int chatMaxSize = 500;

    /** 聊天记录保留天数（超过此天数的消息将被清理） */
    private volatile int chatRetentionDays = 3;

    // ----- IdleTickManager -----
    /** 空闲保存任务理想间隔（Tick 数）：达到此间隔且空闲时开始一轮保存 */
    private volatile int idleTargetIntervalTicks = 36000;

    /** 硬性最大间隔（Tick 数）：超过此间隔后强制开始保存 */
    private volatile int idleMaxIntervalTicks = 72000;

    /** 空闲阈值（毫秒）：平均 Tick 耗时小于此值才视为空闲 */
    private volatile double idleThresholdMs = 45.0;

    // ================================================================
    // 文件路径 & 读写锁
    // ================================================================

    private Path configFilePath;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 描述映射（用于打印中文注释）
    private static final Map<String, String> KEY_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        KEY_DESCRIPTIONS.put("eventStormThreshold",
                "事件风暴阈值（每秒事件数，超过此值缓存自动降级为脏）");
        KEY_DESCRIPTIONS.put("checkIntervalMs",
                "事件风暴检测间隔（毫秒）");
        KEY_DESCRIPTIONS.put("chatMaxSize",
                "聊天缓存最大消息数（内存环形队列容量）");
        KEY_DESCRIPTIONS.put("chatRetentionDays",
                "聊天记录保留天数（超出自动清理）");
        KEY_DESCRIPTIONS.put("idleTargetIntervalTicks",
                "空闲保存理想间隔（Tick 数，达到此间隔且空闲时开始保存）");
        KEY_DESCRIPTIONS.put("idleMaxIntervalTicks",
                "硬性最大间隔（Tick 数，超过此间隔强制开始保存）");
        KEY_DESCRIPTIONS.put("idleThresholdMs",
                "空闲阈值（毫秒，平均 Tick 耗时小于此值视为空闲）");
    }

    private ConfigManager() {
    }

    // ================================================================
    // 初始化
    // ================================================================

    /**
     * 初始化配置管理器，指定配置文件路径（通常为 <游戏目录>/config/linglens.json）。
     *
     * @param configDir 配置目录（如 server.getServerDirectory() 下的 config 文件夹）
     */
    public static void init(Path configDir) {
        INSTANCE.configFilePath = configDir.resolve("linglens.json");
        INSTANCE.loadFromFile();
    }

    // ================================================================
    // 加载 / 保存
    // ================================================================

    /**
     * 从 JSON 文件加载配置。
     * 如果文件不存在，创建默认配置并输出中文注释。
     */
    public void loadFromFile() {
        rwLock.writeLock().lock();
        try {
            File file = configFilePath.toFile();
            if (!file.exists()) {
                // 创建默认配置并输出中文注释
                saveToFileInternal();
                LOGGER.info("[LingLens] 创建配置文件");
                return;
            }

            // 读取已有配置文件
            String jsonStr = Files.readString(configFilePath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

            // 解析各字段（带类型转换）
            if (root.has("eventStormThreshold"))
                eventStormThreshold = root.get("eventStormThreshold").getAsLong();
            if (root.has("checkIntervalMs"))
                checkIntervalMs = root.get("checkIntervalMs").getAsLong();
            if (root.has("chatMaxSize"))
                chatMaxSize = root.get("chatMaxSize").getAsInt();
            if (root.has("chatRetentionDays"))
                chatRetentionDays = root.get("chatRetentionDays").getAsInt();
            if (root.has("idleTargetIntervalTicks"))
                idleTargetIntervalTicks = root.get("idleTargetIntervalTicks").getAsInt();
            if (root.has("idleMaxIntervalTicks"))
                idleMaxIntervalTicks = root.get("idleMaxIntervalTicks").getAsInt();
            if (root.has("idleThresholdMs"))
                idleThresholdMs = root.get("idleThresholdMs").getAsDouble();

            LOGGER.info("[LingLens] 配置文件已加载: {} ({} 项配置)", configFilePath, root.size());
        } catch (Exception e) {
            LOGGER.error("[LingLens] 加载配置文件失败，将使用默认值", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 将当前配置保存回文件。
     */
    public void saveToFile() {
        rwLock.writeLock().lock();
        try {
            saveToFileInternal();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void saveToFileInternal() {
        try {
            Files.createDirectories(configFilePath.getParent());
            JsonObject root = new JsonObject();

            root.addProperty("eventStormThreshold", eventStormThreshold);
            root.addProperty("checkIntervalMs", checkIntervalMs);
            root.addProperty("chatMaxSize", chatMaxSize);
            root.addProperty("chatRetentionDays", chatRetentionDays);
            root.addProperty("idleTargetIntervalTicks", idleTargetIntervalTicks);
            root.addProperty("idleMaxIntervalTicks", idleMaxIntervalTicks);
            root.addProperty("idleThresholdMs", idleThresholdMs);

            String jsonStr = GSON.toJson(root);
            Files.writeString(configFilePath, jsonStr, StandardCharsets.UTF_8);
            LOGGER.debug("[LingLens] 配置文件已保存");
        } catch (IOException e) {
            LOGGER.error("[LingLens] 保存配置文件失败", e);
        }
    }

    // ================================================================
    // 查询方法
    // ================================================================

    public long getEventStormThreshold() {
        rwLock.readLock().lock();
        try {
            return eventStormThreshold;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long getCheckIntervalMs() {
        rwLock.readLock().lock();
        try {
            return checkIntervalMs;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getChatMaxSize() {
        rwLock.readLock().lock();
        try {
            return chatMaxSize;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getChatRetentionDays() {
        rwLock.readLock().lock();
        try {
            return chatRetentionDays;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getIdleTargetIntervalTicks() {
        rwLock.readLock().lock();
        try {
            return idleTargetIntervalTicks;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getIdleMaxIntervalTicks() {
        rwLock.readLock().lock();
        try {
            return idleMaxIntervalTicks;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public double getIdleThresholdMs() {
        rwLock.readLock().lock();
        try {
            return idleThresholdMs;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ================================================================
    // 修改方法（用于命令中调用）
    // ================================================================

    /**
     * 设置配置项的值并持久化。
     *
     * @param key   配置键（取自 KEY_DESCRIPTIONS 中的键名）
     * @param value 新的值（字符串形式，会尝试自动转换类型）
     * @return 操作成功返回 true，键不存在或值不合法返回 false
     */
    public boolean setConfigValue(String key, String value) {
        rwLock.writeLock().lock();
        try {
            switch (key) {
                case "eventStormThreshold":
                    eventStormThreshold = Long.parseLong(value);
                    break;
                case "checkIntervalMs":
                    checkIntervalMs = Long.parseLong(value);
                    break;
                case "chatMaxSize":
                    chatMaxSize = Integer.parseInt(value);
                    if (chatMaxSize < 1)
                        chatMaxSize = 1;
                    break;
                case "chatRetentionDays":
                    chatRetentionDays = Integer.parseInt(value);
                    if (chatRetentionDays < 1)
                        chatRetentionDays = 1;
                    break;
                case "idleTargetIntervalTicks":
                    idleTargetIntervalTicks = Integer.parseInt(value);
                    if (idleTargetIntervalTicks < 1)
                        idleTargetIntervalTicks = 1;
                    break;
                case "idleMaxIntervalTicks":
                    idleMaxIntervalTicks = Integer.parseInt(value);
                    if (idleMaxIntervalTicks < 1)
                        idleMaxIntervalTicks = 1;
                    break;
                case "idleThresholdMs":
                    idleThresholdMs = Double.parseDouble(value);
                    if (idleThresholdMs <= 0)
                        idleThresholdMs = 1.0;
                    break;
                default:
                    LOGGER.warn("[LingLens] 未知配置键: {}", key);
                    return false;
            }
            LOGGER.info("[LingLens] 配置项 '{}' 已更新为: {}", key, getCurrentValueString(key));
            // 保存到文件
            saveToFileInternal();
            return true;
        } catch (NumberFormatException e) {
            LOGGER.warn("[LingLens] 配置值 '{}' 类型不合法，需要数字", value);
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /**
     * 获取所有配置项及其当前值和描述的文本（用于命令显示）。
     */
    public String getConfigDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== [LingLens] 当前配置 ===\n");
        for (Map.Entry<String, String> entry : KEY_DESCRIPTIONS.entrySet()) {
            String key = entry.getKey();
            String desc = entry.getValue();
            String curValue = getCurrentValueString(key);
            sb.append("§e").append(key).append("§f: §a").append(curValue).append("\n");
            sb.append("  §7").append(desc).append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取指定键的当前值字符串表示。
     */
    private String getCurrentValueString(String key) {
        switch (key) {
            case "eventStormThreshold":
                return String.valueOf(eventStormThreshold);
            case "checkIntervalMs":
                return checkIntervalMs + " ms";
            case "chatMaxSize":
                return String.valueOf(chatMaxSize);
            case "chatRetentionDays":
                return chatRetentionDays + " 天";
            case "idleTargetIntervalTicks":
                return idleTargetIntervalTicks + " tick";
            case "idleMaxIntervalTicks":
                return idleMaxIntervalTicks + " tick";
            case "idleThresholdMs":
                return String.format("%.1f ms", idleThresholdMs);
            default:
                return "N/A";
        }
    }

    /**
     * 获取所有可用配置键的集合。
     */
    public static java.util.Set<String> getConfigKeys() {
        return KEY_DESCRIPTIONS.keySet();
    }
}