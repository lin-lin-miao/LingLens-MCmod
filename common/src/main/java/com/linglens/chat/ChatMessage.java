package com.linglens.chat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 聊天消息数据模型。
 * <p>
 * 记录单条聊天消息的完整信息：时间戳、发送者 UUID、发送者名称、维度、消息内容。
 * 支持格式化为可读字符串。
 * </p>
 */
public record ChatMessage(
        /** 消息发送时的系统时间戳（毫秒） */
        long timestamp,
        /** 发送者 UUID（玩家、系统机器人等） */
        UUID senderUuid,
        /** 发送者显示名称 */
        String senderName,
        /** 消息发出的维度键（如 minecraft:overworld） */
        String dimension,
        /** 消息纯文本内容 */
        String content) {

    /** 日期格式化器（线程不安全，使用时需加锁或每次新建） */
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    /**
     * 格式化为带颜色的 Minecraft 聊天行。
     * <p>
     * 格式: {@code §7[HH:mm:ss] §f玩家名 §7[维度] §f消息内容}
     * </p>
     *
     * @return 格式化后的字符串
     */
    public String toFormattedString() {
        String timeStr;
        synchronized (SDF) {
            timeStr = SDF.format(new Date(timestamp));
        }
        String dimShort = dimension.replace("minecraft:", "");
        return "§7[" + timeStr + "] §f" + senderName + " §7[" + dimShort + "] §f" + content;
    }
}