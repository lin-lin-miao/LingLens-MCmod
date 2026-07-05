package com.linglens.chat;

import com.linglens.annotation.IdleTickSave;
import com.linglens.config.ConfigManager;
import com.linglens.manager.IdleTickManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 聊天消息缓存管理器。
 * <p>
 * 基于环形队列（ArrayDeque）的线程安全内存缓存。
 * 容量上限可配置（默认 500 条），超过时自动淘汰最旧消息。
 * 支持按玩家、关键词、时间范围、条数范围等多种过滤方式查询。
 * 提供只读快照（{@link #getSnapshot()}）用于导出等场景。
 * </p>
 *
 * <p>
 * 线程安全：使用 {@link ReentrantReadWriteLock} 保证并发安全。
 * 持久化：新增 {@link ChatPersistence} 集成，消息同时保存到文件（JSON Lines）。
 * 定时清理：通过 {@link IdleTickSave} 注解自动清理过期消息（默认保留 3 天）。
 * </p>
 */
public class ChatCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    // ==================== 单例 ====================
    private static final ChatCache INSTANCE = new ChatCache();

    public static ChatCache getInstance() {
        return INSTANCE;
    }

    // ==================== 配置 ====================
    public static final int DEFAULT_MAX_SIZE = 500;
    /** 默认保留天数 */
    public static final int DEFAULT_RETENTION_DAYS = 3;

    private volatile int maxSize = DEFAULT_MAX_SIZE;
    private volatile int retentionDays = DEFAULT_RETENTION_DAYS;
    private volatile Set<String> ignoredPlayers = new HashSet<>();

    // ==================== 环形队列 + 读写锁 ====================
    private final ArrayDeque<ChatMessage> queue = new ArrayDeque<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = rwLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = rwLock.writeLock();

    private ChatCache() {
    }

    static {
        // 注册本类到待扫描列表，等待服务器启动后由 IdleTickManager 自动扫描注解
        IdleTickManager.registerPendingClass(ChatCache.class);
    }

    // ================================================================
    // 1. 配置同步（从 ConfigManager 读取并应用）
    // ================================================================

    /**
     * 从 ConfigManager 同步聊天缓存相关配置。
     * 在 ConfigManager 初始化后由平台主类调用。
     */
    public static void applyConfigFromManager() {
        ChatCache cache = getInstance();
        ConfigManager cfg = ConfigManager.getInstance();
        cache.setMaxSize(cfg.getChatMaxSize());
        cache.setRetentionDays(cfg.getChatRetentionDays());
        LOGGER.info("[LingLens] 聊天缓存配置已从 ConfigManager 同步: maxSize={}, retentionDays={}",
                cfg.getChatMaxSize(), cfg.getChatRetentionDays());
    }

    // ================================================================
    // 1. 持久化路径设置
    // ================================================================

    /**
     * 设置持久化文件路径，并加载历史数据（可选）。
     * 由各平台主类在服务器启动时调用。
     *
     * @param worldDirectory 世界存档目录
     */
    public static void setDataFile(File worldDirectory) {
        ChatPersistence.setDataFile(worldDirectory);
        LOGGER.info("[LingLens] 聊天持久化路径已设置");
    }

    // ================================================================
    // 2. 添加消息
    // ================================================================

    public void addMessage(UUID senderUuid, String senderName, String dimension, String content) {
        if (content == null || content.isBlank())
            return;
        if (ignoredPlayers.contains(senderName))
            return;

        ChatMessage msg = new ChatMessage(System.currentTimeMillis(), senderUuid, senderName, dimension, content);
        writeLock.lock();
        try {
            while (queue.size() >= maxSize) {
                queue.pollFirst();
            }
            queue.addLast(msg);
            LOGGER.debug("[LingLens] 聊天消息已缓存: [{}]{}: {}", dimension, senderName, content);
        } finally {
            writeLock.unlock();
        }

        // 异步持久化到文件（不阻塞主线程）
        CompletableFuture.runAsync(() -> {
            ChatPersistence.append(msg);
        });
    }

    // ================================================================
    // 3. 查询方法
    // ================================================================

    /**
     * 获取最近的 N 条消息（从新到旧）。
     * 优先从内存缓存获取，不足部分从持久化文件补充。
     */
    public List<ChatMessage> getRecentMessages(int count) {
        if (count <= 0)
            return Collections.emptyList();

        // 1. 先从内存取
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            if (queue.isEmpty()) {
                memResult = Collections.emptyList();
            } else {
                int actualCount = Math.min(count, queue.size());
                ChatMessage[] array = queue.toArray(new ChatMessage[0]);
                List<ChatMessage> result = new ArrayList<>(actualCount);
                for (int i = array.length - 1; i >= 0 && result.size() < actualCount; i--) {
                    result.add(array[i]);
                }
                memResult = result;
            }
        } finally {
            readLock.unlock();
        }

        // 2. 若内存不足且 count > 内存数量，从持久化补足
        if (memResult.size() < count) {
            int need = count - memResult.size();
            List<ChatMessage> fileResult = ChatPersistence.loadRecent(need);
            // 合并（去重，防止内存尚未淘汰但文件已写入导致的重复）
            Set<String> memSet = new HashSet<>();
            for (ChatMessage m : memResult) {
                memSet.add(m.timestamp() + m.senderName() + m.content());
            }
            List<ChatMessage> merged = new ArrayList<>(memResult);
            for (ChatMessage m : fileResult) {
                String key = m.timestamp() + m.senderName() + m.content();
                if (!memSet.contains(key)) {
                    merged.add(m);
                    memSet.add(key);
                }
            }
            // 按时间降序排序
            merged.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
            // 截取 count
            if (merged.size() > count) {
                merged = merged.subList(0, count);
            }
            return merged;
        }
        return memResult;
    }

    /**
     * 获取包含所有缓存消息的只读快照（从旧到新）。
     * 合并内存缓存和持久化文件中的消息，去重后返回。
     */
    public List<ChatMessage> getSnapshot() {
        // 1. 取内存快照（从旧到新）
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            memResult = List.copyOf(queue);
        } finally {
            readLock.unlock();
        }

        // 2. 从持久化文件加载所有消息（从旧到新）
        List<ChatMessage> fileResult = ChatPersistence.loadAll();
        if (fileResult.isEmpty()) {
            return memResult;
        }

        // 3. 去重合并（以 timestamp+senderName+content 为键）
        Set<String> seen = new LinkedHashSet<>();
        List<ChatMessage> merged = new ArrayList<>();

        // 优先将文件结果按顺序加入（优先保留文件中的旧数据，因为它们不会被淘汰）
        for (ChatMessage m : fileResult) {
            String key = m.timestamp() + m.senderName() + m.content();
            if (seen.add(key)) {
                merged.add(m);
            }
        }
        // 再将内存中的最新消息加入（覆盖/去重）
        for (ChatMessage m : memResult) {
            String key = m.timestamp() + m.senderName() + m.content();
            if (seen.add(key)) {
                merged.add(m);
            }
        }
        return merged;
    }

    /**
     * 按发送者名称过滤查询（从新到旧）。支持从持久化补充。
     */
    public List<ChatMessage> filterByPlayer(String playerName, int maxCount) {
        if (playerName == null || playerName.isBlank() || maxCount <= 0)
            return Collections.emptyList();
        // 先从内存查
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            List<ChatMessage> reversed = new ArrayList<>(queue);
            Collections.reverse(reversed);
            memResult = reversed.stream()
                    .filter(msg -> msg.senderName().equals(playerName))
                    .limit(maxCount)
                    .toList();
        } finally {
            readLock.unlock();
        }

        if (memResult.size() < maxCount) {
            int need = maxCount - memResult.size();
            List<ChatMessage> fileResult = ChatPersistence.filterByPlayer(playerName, need);
            // 去重合并
            Set<String> memSet = new HashSet<>();
            for (ChatMessage m : memResult) {
                memSet.add(m.timestamp() + m.senderName() + m.content());
            }
            List<ChatMessage> merged = new ArrayList<>(memResult);
            for (ChatMessage m : fileResult) {
                String key = m.timestamp() + m.senderName() + m.content();
                if (!memSet.contains(key)) {
                    merged.add(m);
                    memSet.add(key);
                }
            }
            merged.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
            if (merged.size() > maxCount)
                merged = merged.subList(0, maxCount);
            return merged;
        }
        return memResult;
    }

    /**
     * 按关键词搜索消息内容（从新到旧，不区分大小写）。支持从持久化补充。
     */
    public List<ChatMessage> searchByKeyword(String keyword, int maxCount) {
        if (keyword == null || keyword.isBlank() || maxCount <= 0)
            return Collections.emptyList();
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            List<ChatMessage> reversed = new ArrayList<>(queue);
            Collections.reverse(reversed);
            memResult = reversed.stream()
                    .filter(msg -> msg.content().toLowerCase(Locale.ROOT).contains(lowerKeyword))
                    .limit(maxCount)
                    .toList();
        } finally {
            readLock.unlock();
        }

        if (memResult.size() < maxCount) {
            int need = maxCount - memResult.size();
            List<ChatMessage> fileResult = ChatPersistence.searchByKeyword(keyword, need);
            Set<String> memSet = new HashSet<>();
            for (ChatMessage m : memResult) {
                memSet.add(m.timestamp() + m.senderName() + m.content());
            }
            List<ChatMessage> merged = new ArrayList<>(memResult);
            for (ChatMessage m : fileResult) {
                String key = m.timestamp() + m.senderName() + m.content();
                if (!memSet.contains(key)) {
                    merged.add(m);
                    memSet.add(key);
                }
            }
            merged.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
            if (merged.size() > maxCount)
                merged = merged.subList(0, maxCount);
            return merged;
        }
        return memResult;
    }

    /**
     * 查询指定时间范围内的消息（从新到旧）。支持从持久化文件补充。
     */
    public List<ChatMessage> filterByTimeRange(long startTime, long endTime, int maxCount) {
        if (startTime > endTime || maxCount <= 0)
            return Collections.emptyList();
        // 1. 先从内存缓存获取
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            List<ChatMessage> reversed = new ArrayList<>(queue);
            Collections.reverse(reversed);
            memResult = reversed.stream()
                    .filter(msg -> msg.timestamp() >= startTime && msg.timestamp() <= endTime)
                    .limit(maxCount)
                    .toList();
        } finally {
            readLock.unlock();
        }

        // 2. 如果内存结果不足，从持久化文件补充
        if (memResult.size() < maxCount) {
            int need = maxCount - memResult.size();
            // 使用 loadAll() 加载所有文件消息（从旧到新），然后按时间过滤
            List<ChatMessage> fileAll = ChatPersistence.loadAll();
            if (!fileAll.isEmpty()) {
                // 对文件结果按时间降序排序后再过滤，以获取最新的 need 条
                List<ChatMessage> fileDesc = new ArrayList<>(fileAll);
                fileDesc.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
                List<ChatMessage> fileFiltered = fileDesc.stream()
                        .filter(msg -> msg.timestamp() >= startTime && msg.timestamp() <= endTime)
                        .limit(need)
                        .toList();
                // 去重合并
                Set<String> memSet = new HashSet<>();
                for (ChatMessage m : memResult) {
                    memSet.add(m.timestamp() + m.senderName() + m.content());
                }
                List<ChatMessage> merged = new ArrayList<>(memResult);
                for (ChatMessage m : fileFiltered) {
                    String key = m.timestamp() + m.senderName() + m.content();
                    if (!memSet.contains(key)) {
                        merged.add(m);
                        memSet.add(key);
                    }
                }
                // 最终按时间降序排序并截取
                merged.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
                if (merged.size() > maxCount) {
                    merged = merged.subList(0, maxCount);
                }
                return merged;
            }
        }
        return memResult;
    }

    /**
     * 查询指定条数范围内的消息（按索引位置，从新到旧）。
     * 例如 from=1, to=10 表示第 1 到第 10 条（最新）。
     * 优先从内存缓存获取，超出部分从持久化文件补充。
     */
    public List<ChatMessage> filterByRange(int from, int to) {
        if (from < 1 || to < from)
            return Collections.emptyList();
        int count = to - from + 1;

        // 1. 先从内存缓存获取
        List<ChatMessage> memResult;
        readLock.lock();
        try {
            int size = queue.size();
            if (size == 0) {
                memResult = Collections.emptyList();
            } else {
                List<ChatMessage> allDesc = new ArrayList<>(size);
                queue.descendingIterator().forEachRemaining(allDesc::add);
                int startIdx = Math.max(0, from - 1);
                int endIdx = Math.min(size, to);
                if (startIdx >= size) {
                    memResult = Collections.emptyList();
                } else {
                    memResult = allDesc.subList(startIdx, Math.min(endIdx, allDesc.size()));
                }
            }
        } finally {
            readLock.unlock();
        }

        // 2. 如果 from 超出内存缓存大小，需要从持久化文件补充
        // 内存缓存中的消息是最新的，持久化文件中包含更旧的消息
        int memSize;
        readLock.lock();
        try {
            memSize = queue.size();
        } finally {
            readLock.unlock();
        }

        if (from > memSize) {
            // 完全从持久化文件获取
            int fileNeed = count;
            List<ChatMessage> fileResult = ChatPersistence.loadRecent(from);
            if (fileResult.isEmpty()) {
                return Collections.emptyList();
            }
            // fileResult 是从新到旧的，需要截取指定范围
            int fileStart = Math.max(0, from - 1);
            int fileEnd = Math.min(fileResult.size(), to);
            if (fileStart >= fileResult.size()) {
                return Collections.emptyList();
            }
            return fileResult.subList(fileStart, Math.min(fileEnd, fileResult.size()));
        } else if (to > memSize) {
            // 部分在内存，部分在文件：内存部分已取到，从文件补充缺失
            int needFromFile = to - memSize;
            List<ChatMessage> fileResult = ChatPersistence.loadRecent(needFromFile);
            // 文件结果是从新到旧，需要从文件头部取最新的 needFromFile 条
            if (fileResult.isEmpty()) {
                return memResult;
            }
            List<ChatMessage> fileSubset = fileResult.subList(0, Math.min(needFromFile, fileResult.size()));
            // 合并去重
            Set<String> memSet = new HashSet<>();
            for (ChatMessage m : memResult) {
                memSet.add(m.timestamp() + m.senderName() + m.content());
            }
            List<ChatMessage> merged = new ArrayList<>(memResult);
            for (ChatMessage m : fileSubset) {
                String key = m.timestamp() + m.senderName() + m.content();
                if (!memSet.contains(key)) {
                    merged.add(m);
                    memSet.add(key);
                }
            }
            // 按时间降序排序
            merged.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));
            // 截取 to 条
            if (merged.size() > count) {
                merged = merged.subList(0, count);
            }
            return merged;
        }
        return memResult;
    }

    // ================================================================
    // 4. 管理方法
    // ================================================================

    /**
     * 清空内存缓存 + 持久化文件。
     */
    public void clear() {
        writeLock.lock();
        try {
            queue.clear();
            LOGGER.info("[LingLens] 聊天消息缓存已清空");
        } finally {
            writeLock.unlock();
        }
        // 同时清空持久化文件
        ChatPersistence.clear();
    }

    /**
     * 获取内存缓存中的消息数量（不含持久化文件）。
     */
    public int size() {
        readLock.lock();
        try {
            return queue.size();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取持久化文件中的消息记录数（仅文件）。
     */
    public int persistentCount() {
        return ChatPersistence.totalMessages();
    }

    /**
     * 获取可查询的总消息数（缓存 + 文件，可能存在重复）。
     */
    public int totalMessages() {
        return size() + persistentCount();
    }

    public void setMaxSize(int newMaxSize) {
        if (newMaxSize < 1)
            newMaxSize = 1;
        writeLock.lock();
        try {
            maxSize = newMaxSize;
            while (queue.size() > maxSize) {
                queue.pollFirst();
            }
            LOGGER.info("[LingLens] 聊天缓存上限已设为 {}", maxSize);
        } finally {
            writeLock.unlock();
        }
    }

    public int getMaxSize() {
        return maxSize;
    }

    // ==================== 保留天数 ====================

    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * 设置保留天数（消息保存天数，超过自动清理）。
     */
    public void setRetentionDays(int days) {
        if (days < 1)
            days = 1;
        this.retentionDays = days;
        LOGGER.info("[LingLens] 聊天历史保留天数已设为 {}", days);
    }

    // ==================== 忽略列表 ====================

    public void setIgnoredPlayers(Set<String> playerNames) {
        this.ignoredPlayers = playerNames != null ? new HashSet<>(playerNames) : new HashSet<>();
    }

    public Set<String> getIgnoredPlayers() {
        return ignoredPlayers;
    }

    // ================================================================
    // 5. 定时清理（由 IdleTickManager 触发）
    // ================================================================

    /**
     * 定时清理持久化文件中超过保留天数的消息。
     * 此方法由 IdleTickManager 通过 @IdleTickSave 注解自动调用。
     */
    @IdleTickSave
    public static void autoCleanup() {
        ChatCache cache = getInstance();
        int days = cache.getRetentionDays();
        ChatPersistence.cleanupExpired(days);
    }

    /**
     * 手动触发一次清理（可能通过命令调用）。
     */
    public static void manualCleanup() {
        int days = getInstance().getRetentionDays();
        LOGGER.info("[LingLens] 手动触发聊天历史清理（保留天数={}）", days);
        ChatPersistence.cleanupExpired(days);
    }

    /**
     * 获取持久化文件大小（字节）。
     */
    public static long persistentFileSize() {
        return ChatPersistence.fileSize();
    }
}