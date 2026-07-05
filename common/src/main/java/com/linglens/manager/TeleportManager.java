package com.linglens.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.linglens.data.PendingTeleport;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeleportManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");
    private static final ConcurrentHashMap<UUID, PendingTeleport> PENDING_MAP = new ConcurrentHashMap<>();
    // 存档目录，由平台主类在服务器启动时设置（设置后会从该目录加载数据）
    private static File worldDirectory = null;
    // 默认备用路径（未设置存档目录时使用）
    private static final File DEFAULT_DIR = new File("config/linglens");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 供平台主类调用的存档目录设置方法（例如在服务器WorldLoad事件中）
     * 
     * @param worldDir 世界存档目录（例如 world/dimensions/ 的上级）
     */
    public static void setWorldDirectory(File worldDir) {
        worldDirectory = worldDir;
        loadFromFile(); // 设置后立即从该目录加载已有数据
    }

    /**
     * 动态获取数据文件路径：优先使用存档目录下的 linglens/pending_teleports.json，
     * 若未设置存档目录则使用 config/linglens/pending_teleports.json 作为备用。
     */
    private static File getDataFile() {
        if (worldDirectory != null) {
            return new File(worldDirectory, "linglens/pending_teleports.json");
        }
        // 备用路径（兼容未调用 setWorldDirectory 的情形）
        return new File(DEFAULT_DIR, "pending_teleports.json");
    }

    public static void addPending(UUID uuid, double x, double y, double z, String dimension, String name) {
        PENDING_MAP.put(uuid, new PendingTeleport(uuid, name, x, y, z, dimension, System.currentTimeMillis()));
        saveToFile();
    }

    public static PendingTeleport getAndRemove(UUID uuid) {
        PendingTeleport pending = PENDING_MAP.remove(uuid);
        if (pending != null) {
            saveToFile();
        }
        return pending;
    }

    public static boolean hasPending(UUID uuid) {
        return PENDING_MAP.containsKey(uuid);
    }

    public static void loadFromFile() {
        File file = getDataFile();
        if (!file.exists()) {
            LOGGER.info("[LingLens] 待传送文件不存在，跳过加载（Pending teleport file does not exist, skipping）");
            return;
        }
        try (Reader reader = new FileReader(file)) {
            PendingTeleport[] array = GSON.fromJson(reader, PendingTeleport[].class);
            if (array != null) {
                for (PendingTeleport p : array) {
                    PENDING_MAP.put(p.getPlayerUuid(), p);
                }
            }
            LOGGER.info("[LingLens] 离线传送列表已读取（Offline transfer list read）");
        } catch (Exception e) {
            LOGGER.error("[LingLens] 加载待传送数据失败（Loading pending data failed）: ", e);
        }
    }

    public static void saveToFile() {
        File file = getDataFile();
        file.getParentFile().mkdirs();
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(PENDING_MAP.values().toArray(), writer);
            LOGGER.info("[LingLens] 离线传送列表已保存（Offline transfer list saved）");
        } catch (Exception e) {
            LOGGER.error("[LingLens] 保存待传送数据失败（Failed to save data to be transmitted）: ", e);
        }
    }

    public static void clear() {
        PENDING_MAP.clear();
        File file = getDataFile();
        if (file.exists()) {
            file.delete();
        }
    }
}