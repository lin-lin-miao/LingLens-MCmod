---
description: JVM network traffic statistics (based on Netty)
---
# **注意！** **禁止直接使用本篇的代码**
功能八：JVM 网络流量统计（基于 Netty）

1. 功能目标
在服务端监控所有网络流量，具体包括：

实时统计：分别统计上传流量（客户端 → 服务端，Inbound）与下行流量（服务端 → 客户端，Outbound）的数据包数量与字节大小。

按玩家维度：统计结果关联到具体的 ServerPlayer。（后续拓展：按数据包类型统计）

外部输出：提供查询接口（命令或定时日志）导出统计数据。
2. 项目模块划分
核心开发原则：所有业务逻辑（包括 Mixin 类）全部写在 common 模块，两个平台模块仅负责加载配置。

3. 开发步骤详解
3.1 第一步：创建数据统计存储类（common模块）
路径：.../network/
作用：线程安全的全局计数器，按玩家UUID存储上下行统计。
单例模式
存储结构：UUID -> [上传包数, 上传字节数, 下行包数, 下行字节数,平均上传速度,平均下行速度]
以服务端视角
// 记录上传（服务端发给客户端）
// 记录下载（客户端发给服务端）

3.2 第二步：编写核心 Mixin 拦截器
目标类：net.minecraft.network.Connection（这是所有平台（Fabric/Forge）通用的网络连接底层类）。

逻辑说明：

拦截 send(Packet) 方法统计上传流量。

拦截 channelRead0(Packet) 方法统计下载流量。

3.3 第三步：提供数据查询入口（common模块）
方式A：注册一个简单的命令，查看全体统计。

4. 编译与测试要点
映射兼容性：Architectury 默认使用官方 Mojang 映射（official），上述代码中 net.minecraft.network.Connection 等类名需确保与映射一致。

内存泄漏防范：在 calculatePacketSize 方法中，务必 release() 创建的 ByteBuf，防止内存泄漏。
