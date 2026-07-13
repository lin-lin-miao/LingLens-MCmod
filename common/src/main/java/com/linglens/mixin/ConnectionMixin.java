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

import java.util.UUID;

/**
 * Mixin — 拦截 Connection 类以统计网络流量。
 * <p>
 * 以服务端视角：
 * <ul>
 * <li>send(Packet) → 上传（服务端→客户端）</li>
 * <li>channelRead0 → 下载（客户端→服务端）</li>
 * </ul>
 * 计算数据包字节数时，通过序列化 Packet 为 ByteBuf 估算大小。
 * 为避免影响正常网络性能，仅统计到 NetworkTrafficStats 中。
 * </p>
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("LingLens");

    @Shadow
    private volatile net.minecraft.network.PacketListener packetListener;

    /**
     * 拦截 send(Packet) 方法，统计上传流量（服务端→客户端）。
     * <p>
     * 在方法执行前统计，以避免影响实际发送流程。
     * 仅在服务端且 packetListener 为 ServerGamePacketListenerImpl 时才统计。
     * </p>
     *
     * @param packet  数据包
     * @param listener 发送监听器（可能为 null）
     * @param ci      回调信息
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void onSend(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        try {
            UUID playerUuid = extractPlayerUuid();
            if (playerUuid == null) return;

            int bytes = estimatePacketSize(packet);
            if (bytes <= 0) return;

            String packetType = getReadablePacketType(packet);
            NetworkTrafficStats.getInstance().recordUpload(playerUuid, bytes, packetType);
        } catch (Exception e) {
            // 静默处理，避免影响正常发包
            LOGGER.debug("[LingLens] send 统计异常: {}", e.getMessage());
        }
    }

    /**
     * 拦截 channelRead0 方法，统计下载流量（客户端→服务端）。
     * <p>
     * 在方法执行前统计。
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
            if (playerUuid == null) return;

            int bytes = estimatePacketSize(packet);
            if (bytes <= 0) return;

            String packetType = getReadablePacketType(packet);
            NetworkTrafficStats.getInstance().recordDownload(playerUuid, bytes, packetType);
        } catch (Exception e) {
            // 静默处理，避免影响正常收包
            LOGGER.debug("[LingLens] channelRead0 统计异常: {}", e.getMessage());
        }
    }

        /**
     * 获取数据包的类型标识（人类可读）。
     * <p>
     * 使用 {@link PacketTypeMapper#getReadableName(Packet)} 获取可读名称，
     * 优先查映射表，再尝试 {@link Packet#type()} 协议级名称（如 {@code minecraft:keep_alive}），
     * 最后回退到类的简单名（{@link Class#getSimpleName()}）。
     * 这样即使在混淆环境（class_xxxx）下，也能显示可读性更好的名称。
     * </p>
     *
     * @param packet 数据包实例
     * @return 可读类型标识（如 "minecraft:keep_alive"），不会为 null
     */
    @Unique
    private static String getReadablePacketType(Packet<?> packet) {
        if (packet == null) return "null";
        // 使用 PacketTypeMapper 的智能回退方法（映射表 → Protocol type → 简单类名）
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
     * 注意：此操作会创建临时的 ByteBuf 并写入数据包，完成后必须释放以避免内存泄漏。
     * </p>
     *
     * @param packet 要估算大小的数据包
     * @return 估算的字节数，如果无法估算则返回 0
     */
    @Unique
    private int estimatePacketSize(Packet<?> packet) {
        if (packet == null) return 0;
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