package com.linglens.mixin;

import com.linglens.network.NetworkTrafficStats;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin — 拦截 Connection 类以统计网络流量。
 * <p>
 * 以服务端视角：
 * <ul>
 * <li>send(Packet) → 上传（服务端→客户端）</li>
 * <li>channelRead0 → 下载（客户端→服务端）</li>
 * </ul>
 * 上传流量通过序列化 Packet 为 ByteBuf 估算字节大小（安全：包尚未发送）。
 * 下载流量使用基于数据包类型的缓存估算，避免调用 packet.write() 影响已解码包的状态。
 * （某些模组如 KubeJS 的 packet.write() 会消耗内部缓冲区，导致后续正常处理失败）
 * </p>
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    // ==================== 下载方向缓存估算 ====================
    /** 缓存已知数据包类型的字节大小（仅用于下载方向的安全估算） */
    @Unique
    private static final Map<String, Integer> DOWNLOAD_SIZE_CACHE = new ConcurrentHashMap<>();
    /** 下载方向缓存默认值（64 字节），当首次遇到未知类型且估算失败时使用 */
    @Unique
    private static final int DEFAULT_DOWNLOAD_ESTIMATE = 64;
    /** 最大缓存条目数 */
    @Unique
    private static final int MAX_CACHE_SIZE = 500;

    @Shadow
    private volatile net.minecraft.network.PacketListener packetListener;

    // ==================== 上传方向（send） ====================

    /**
     * 拦截 send(Packet) 方法，统计上传流量（服务端→客户端）。
     * <p>
     * 此处使用 {@link #estimatePacketSize(Packet)} 序列化估算字节大小，
     * 因为包尚未发送，调 write() 不会影响后续流程。
     * </p>
     *
     * @param packet   数据包
     * @param listener 发送监听器（可能为 null）
     * @param ci       回调信息
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void onSend(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        try {
            UUID playerUuid = extractPlayerUuid();
            if (playerUuid == null)
                return;

            int bytes = estimatePacketSize(packet);
            if (bytes <= 0)
                return;

            String packetType = getReadablePacketType(packet);
            NetworkTrafficStats.getInstance().recordUpload(playerUuid, bytes, packetType);
        } catch (Exception e) {
            // 静默处理，避免影响正常发包
            LOGGER.debug("[LingLens] send 统计异常: {}", e.getMessage());
        }
    }

    // ==================== 下载方向（channelRead0） ====================

    /**
     * 拦截 channelRead0 方法，统计下载流量（客户端→服务端）。
     * <p>
     * <b>重要：不能调用 packet.write() 来估算大小！</b><br>
     * 因为数据包在到达此方法时已被解码，{@link Packet#write(FriendlyByteBuf)} 方法
     * 可能消耗其内部缓冲区状态（如 KubeJS 的 FirstClickMessage），导致后续正常处理失败。
     * </p>
     * <p>
     * 改用基于数据包类型的缓存估算策略：
     * <ol>
     * <li>首次遇到某种包类型时，尝试安全的序列化估算，成功则缓存结果</li>
     * <li>若估算失败（write 抛异常），则用默认值 64 字节并缓存</li>
     * <li>后续同类型包直接读取缓存，不再调用 write()</li>
     * </ol>
     * </p>
     *
     * @param ctx    ChannelHandlerContext
     * @param packet 接收到的数据包
     * @param ci     回调信息
     */
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        try {
            UUID playerUuid = extractPlayerUuid();
            if (playerUuid == null)
                return;

            String packetType = getReadablePacketType(packet);
            // int bytes = 1;//<<未能解决获取下载数据包大小
            // // int bytes = estimateDownloadPacketSize(packet, packetType);
            // if (bytes <= 0) return;

            NetworkTrafficStats.getInstance().recordDownload(playerUuid, 0, packetType);
        } catch (Exception e) {
            // 静默处理，避免影响正常收包
            LOGGER.debug("[LingLens] channelRead0 统计异常: {}", e.getMessage());
        }
    }

    /**
     * 获取数据包的类型标识（人类可读）。
     * <p>
     * 使用类的简单名（{@link Class#getSimpleName()}）作为标识。
     * </p>
     *
     * @param packet 数据包实例
     * @return 可读类型标识（如 "ClientboundKeepAlivePacket"），不会为 null
     */
    @Unique
    private static String getReadablePacketType(Packet<?> packet) {
        if (packet == null)
            return "null";
        return packet.getClass().getSimpleName();
    }

    /**
     * 从 packetListener 中提取玩家 UUID。
     * <p>
     * 仅在连接已关联到服务端游戏会话（ServerGamePacketListenerImpl）时返回 UUID，
     * 其他情况返回 null（如尚未登录、连接关闭等）。
     * </p>
     *
     * @return 玩家 UUID，如果无法获取则返回 null
     */
    @Unique
    private UUID extractPlayerUuid() {
        net.minecraft.network.PacketListener listener = this.packetListener;
        if (listener instanceof ServerGamePacketListenerImpl serverListener) {
            ServerPlayer player = serverListener.getPlayer();
            if (player != null) {
                return player.getUUID();
            }
        }
        return null;
    }

    /**
     * 通过序列化数据包为 ByteBuf 来估算其字节大小。
     * <p>
     * <b>仅在上传方向（send）中使用</b>——此时包尚未发送，调用 write() 是安全的。
     * 注意：此操作会创建临时的 ByteBuf 并写入数据包，完成后必须释放以避免内存泄漏。
     * </p>
     *
     * @param packet 要估算大小的数据包
     * @return 估算的字节数，如果无法估算则返回 0
     */
    @Unique
    private int estimatePacketSize(Packet<?> packet) {
        if (packet == null)
            return 0;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            return buf.readableBytes();
        } catch (Exception e) {
            // 某些数据包写入可能失败（如需要注册表上下文），忽略
            return 0;
        } finally {
            buf.release();
        }
    }
}