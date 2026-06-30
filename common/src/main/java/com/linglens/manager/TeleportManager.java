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
    private static final File DATA_FILE = new File("config/linglens/pending_teleports.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        if (!DATA_FILE.exists())
            return;
        try (Reader reader = new FileReader(DATA_FILE)) {
            PendingTeleport[] array = GSON.fromJson(reader, PendingTeleport[].class);
            if (array != null) {
                for (PendingTeleport p : array) {
                    PENDING_MAP.put(p.getPlayerUuid(), p);
                }
            }
            LOGGER.info("[LingLens] 离线传送列表已读取(Offline transfer list read)");
        } catch (Exception e) {
            LOGGER.error("[LingLens] 加载待传送数据失败(Loading pending data failed): ", e);
        }
    }

    public static void saveToFile() {
        DATA_FILE.getParentFile().mkdirs();
        try (Writer writer = new FileWriter(DATA_FILE)) {
            GSON.toJson(PENDING_MAP.values().toArray(), writer);
            LOGGER.info("[LingLens] 离线传送列表已保存(Offline transfer list saved)");
        } catch (Exception e) {
            LOGGER.error("[LingLens] 保存待传送数据失败(Failed to save data to be transmitted): ", e);
        }
    }

    public static void clear() {
        PENDING_MAP.clear();
        if (DATA_FILE.exists()) {
            DATA_FILE.delete();
        }
    }
}