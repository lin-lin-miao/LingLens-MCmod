package com.linglens.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 聊天消息持久化管理器。
 * <p>
 * 将聊天消息追加存储到 JSON Lines 文件（每行一个 JSON 对象），
 * 支持按时间排序读取最近 N 条，按玩家/关键词过滤，以及按天清理过期消息。
 * 线程安全：使用 {@link ReentrantReadWriteLock} 保护文件读写。
 * </p>
 */
public class ChatPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    private static final Gson GSON = new GsonBuilder().create();

    /** 文件路径：存档目录下的 linglens/chat_history.jsonl */
    private static File dataFile = null;

    /** 读写锁，保护文件操作 */
    private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private static final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private static final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    // 存储时使用的 Gson 类型（ChatMessage 作为 record 序列化需指定）
    private static final Type MESSAGE_TYPE = new TypeToken<ChatMessage>() {
    }.getType();

    // ==================== 路径设置 ====================

    /**
     * 设置持久化文件路径（相对于世界存档目录）。
     * 文件名：linglens/chat_history.jsonl
     *
     * @param worldDirectory 世界存档目录
     */
    public static void setDataFile(File worldDirectory) {
        File dir = new File(worldDirectory, "linglens");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        dataFile = new File(dir, "chat_history.jsonl");
        LOGGER.debug("[LingLens] 聊天持久化文件路径已设置: {}", dataFile.getAbsolutePath());
    }

    // ==================== 写入 ====================

    /**
     * 追加一条聊天消息到文件末尾（异步安全）。
     *
     * @param msg 聊天消息
     */
    public static void append(ChatMessage msg) {
        if (dataFile == null) {
            LOGGER.warn("[LingLens] 聊天持久化未设置文件路径，消息将被丢弃");
            return;
        }
        String line;
        try {
            line = GSON.toJson(msg, MESSAGE_TYPE) + "\n";
        } catch (Exception e) {
            LOGGER.error("[LingLens] 序列化聊天消息失败: ", e);
            return;
        }

        writeLock.lock();
        try {
            // 使用 FileWriter 追加模式
            try (FileWriter fw = new FileWriter(dataFile, StandardCharsets.UTF_8, true);
                    BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(line);
                bw.flush();
            } catch (IOException e) {
                LOGGER.error("[LingLens] 写入聊天消息到文件失败: ", e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // ==================== 读取 ====================

    /**
     * 从文件尾部读取最近的 N 条消息（不改变文件内容）。
     *
     * @param count 最多返回条数
     * @return 最近的消息列表（从新到旧）
     */
    public static List<ChatMessage> loadRecent(int count) {
        if (count <= 0 || dataFile == null || !dataFile.exists()) {
            return Collections.emptyList();
        }
        readLock.lock();
        try {
            List<ChatMessage> all = loadAllLines();
            if (all.isEmpty())
                return Collections.emptyList();
            // 从后往前取最多 count 条
            int size = all.size();
            int start = Math.max(0, size - count);
            List<ChatMessage> result = new ArrayList<>(all.subList(start, size));
            // 反转成从新到旧（文件存储从旧到新）
            Collections.reverse(result);
            return result;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 按玩家名称过滤查询（从新到旧）。
     */
    public static List<ChatMessage> filterByPlayer(String playerName, int count) {
        if (count <= 0 || dataFile == null || !dataFile.exists())
            return Collections.emptyList();
        readLock.lock();
        try {
            List<ChatMessage> all = loadAllLines();
            List<ChatMessage> result = new ArrayList<>();
            // 从后往前扫描，匹配指定玩家
            for (int i = all.size() - 1; i >= 0 && result.size() < count; i--) {
                ChatMessage msg = all.get(i);
                if (msg.senderName().equals(playerName)) {
                    result.add(msg);
                }
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 按关键词搜索（从新到旧，不区分大小写）。
     */
    public static List<ChatMessage> searchByKeyword(String keyword, int count) {
        if (count <= 0 || dataFile == null || !dataFile.exists())
            return Collections.emptyList();
        String lower = keyword.toLowerCase(Locale.ROOT);
        readLock.lock();
        try {
            List<ChatMessage> all = loadAllLines();
            List<ChatMessage> result = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && result.size() < count; i--) {
                ChatMessage msg = all.get(i);
                if (msg.content().toLowerCase(Locale.ROOT).contains(lower)) {
                    result.add(msg);
                }
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取文件中的总消息数。
     */
    public static int totalMessages() {
        if (dataFile == null || !dataFile.exists())
            return 0;
        readLock.lock();
        try {
            return loadAllLines().size();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 加载文件中所有消息（从旧到新，即文件存储顺序）。
     * 供 {@link ChatCache#getSnapshot()} 等全量导出使用。
     *
     * @return 按时间升序排列的所有消息列表（空文件返回空列表）
     */
    public static List<ChatMessage> loadAll() {
        if (dataFile == null || !dataFile.exists()) {
            return Collections.emptyList();
        }
        readLock.lock();
        try {
            return loadAllLines();
        } finally {
            readLock.unlock();
        }
    }

    // ==================== 清理 ====================

    /**
     * 清理超过保留天数的消息。
     * <p>
     * 读取所有行，只保留 timestamp 在保留期内的行，然后重写文件。
     * 此方法可能耗时较长（文件较大时），建议在空闲 tick 中调用。
     * </p>
     *
     * @param retentionDays 保留天数（eg. 3 表示保留最近 3 天的消息）
     */
    public static void cleanupExpired(int retentionDays) {
        if (retentionDays <= 0 || dataFile == null || !dataFile.exists())
            return;

        long cutoff = System.currentTimeMillis() - (long) retentionDays * 86400000L;

        writeLock.lock();
        try {
            List<ChatMessage> all = loadAllLines();
            List<ChatMessage> kept = new ArrayList<>();
            for (ChatMessage msg : all) {
                if (msg.timestamp() >= cutoff) {
                    kept.add(msg);
                }
            }
            if (kept.size() == all.size()) {
                LOGGER.debug("[LingLens] 聊天历史无需清理（{}/{} 条）", kept.size(), all.size());
                return;
            }
            // 重写文件
            try (FileWriter fw = new FileWriter(dataFile, StandardCharsets.UTF_8, false);
                    BufferedWriter bw = new BufferedWriter(fw)) {
                for (ChatMessage msg : kept) {
                    String line = GSON.toJson(msg, MESSAGE_TYPE) + "\n";
                    bw.write(line);
                }
                bw.flush();
            } catch (IOException e) {
                LOGGER.error("[LingLens] 重写聊天历史文件失败: ", e);
                return;
            }
            LOGGER.info("[LingLens] 聊天历史清理完成：删除 {} 条，保留 {} 条（保留天数={}）",
                    all.size() - kept.size(), kept.size(), retentionDays);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 清空持久化文件。
     */
    public static void clear() {
        if (dataFile == null)
            return;
        writeLock.lock();
        try {
            if (dataFile.exists()) {
                dataFile.delete();
                dataFile.createNewFile();
                LOGGER.info("[LingLens] 聊天历史文件已清空");
            }
        } catch (IOException e) {
            LOGGER.error("[LingLens] 清空聊天历史文件失败: ", e);
        } finally {
            writeLock.unlock();
        }
    }

    // ==================== 内部辅助 ====================

    /**
     * 读取文件所有行并解析为 ChatMessage 列表。
     * 注意：调用者必须持有 readLock 或 writeLock。
     */
    private static List<ChatMessage> loadAllLines() {
        List<ChatMessage> messages = new ArrayList<>();
        if (dataFile == null || !dataFile.exists())
            return messages;
        try (BufferedReader br = new BufferedReader(new FileReader(dataFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank())
                    continue;
                try {
                    ChatMessage msg = GSON.fromJson(line, MESSAGE_TYPE);
                    if (msg != null) {
                        messages.add(msg);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[LingLens] 解析聊天历史行失败，跳过: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.error("[LingLens] 读取聊天历史文件失败: ", e);
        }
        return messages;
    }

    // ==================== 文件大小 ====================

    /**
     * 获取文件大小（字节数）。
     */
    public static long fileSize() {
        if (dataFile == null || !dataFile.exists())
            return 0;
        readLock.lock();
        try {
            return dataFile.length();
        } finally {
            readLock.unlock();
        }
    }
}