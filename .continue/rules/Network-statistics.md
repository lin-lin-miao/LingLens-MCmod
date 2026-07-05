---
description: JVM network traffic statistics (based on Netty)
---

功能八：JVM 网络流量统计（基于 Netty）

1. 功能概述
目标：统计当前 JVM 进程（即 Minecraft 服务端）通过 Netty 网络框架发送和接收的累计字节数（自统计启动以来的总量），实时网速。

集成方式：作为 SystemQuery 的补充指标，在 /linglens system 命令中一并展示。

核心要求：

实时性：流量统计应持续更新。

低开销：不影响主游戏线程的 TPS。

跨平台：在 Fabric / Forge / NeoForge 下均可工作。

2. 技术选型与原理
方案：改造 Netty 管道，插入自定义 ChannelDuplexHandler。

关键技术：

Netty TrafficCounter：Netty 自带的流量统计工具，可精确统计读写字节数，支持按间隔采样。

Mixin：用于在服务端管道初始化时，将自定义处理器注入到每个客户端连接的 ChannelPipeline 中。

统计范围：统计所有经过服务端 Netty 管道的业务流量（即 Minecraft 游戏数据包），不包括 TCP 握手等底层协议开销（可忽略）。

3. 核心组件设计
3.1 TrafficHandler（自定义处理器）
继承：ChannelDuplexHandler（同时处理入站和出站事件）。

成员：TrafficCounter trafficCounter。

核心方法：

channelRead()：调用 trafficCounter.bytesRealReadFlow(payloadSize) 记录入站字节数。

write()：调用 trafficCounter.bytesRealWriteFlow(payloadSize) 记录出站字节数。

handlerAdded()：启动 trafficCounter.start()。

handlerRemoved()：停止 trafficCounter.stop()。

3.2 TrafficManager（全局管理器）
单例：使用 static final 实例。

数据结构：ConcurrentHashMap<Channel, TrafficHandler> 维护所有已注册的处理器。

主要方法：

registerChannel(Channel)：若管道尚未包含该处理器，则添加 TrafficHandler 并存入 Map。

unregisterChannel(Channel)：从 Map 中移除处理器，并清理资源。

getTotalTraffic()：遍历所有 TrafficHandler，累加 cumulativeReadBytes() 和 cumulativeWrittenBytes()，返回 long[2]（读/写）。

resetTraffic()（可选）：调用每个 TrafficCounter.resetCumulativeTime() 重置累计计数。

3.3 Mixin 注入目标
目标类：net.minecraft.network.Connection（Fabric/Forge 通用）。

注入点：Connection 类中有一个内部类（通常是 Connection$1），它实现了 ChannelInitializer 接口的 initChannel 方法。

操作：在 initChannel 方法的尾部（@At("TAIL")），调用 TrafficManager.registerChannel(channel)。

附加处理：为 channel.closeFuture() 添加监听器，在通道关闭时调用 TrafficManager.unregisterChannel(channel)。

3.4 集成到现有系统查询
在 SystemQuery.collect() 中调用 TrafficManager.getTotalTraffic()，将获得的发送/接收字节数加入 SystemInfo 记录中。

在 SystemInfo.toReadableString() 中增加两行输出，如：

```text
网络流量(累计): 发送 12.3 MB, 接收 45.6 MB 
网速: ↑ 3.2 MB  ↓ 453.6 KB
```
4. 开发流程（分模块）

步骤	模块	任务	关键点
1	common	定义 TrafficHandler 和 TrafficManager 的接口/抽象类	不依赖具体 Netty 版本，使用 Channel、ChannelDuplexHandler 等通用类型
2	common	实现 TrafficManager（单例，ConcurrentHashMap，汇总逻辑）	注意线程安全，使用 ConcurrentHashMap
3	fabric / forge	各自实现 TrafficHandler（继承 ChannelDuplexHandler）	使用各自模块的 Netty 依赖，创建 TrafficCounter 时需传入 ScheduledExecutorService（可用 Executors.newSingleThreadScheduledExecutor()）
4	fabric / forge	编写 Mixin：目标为 Connection$1.initChannel，注入 registerChannel	需使用 @Inject 在 TAIL 注入，注意映射名可能因版本不同而异，建议使用 @Shadow 或 @Local 辅助
5	common	修改 SystemQuery.collect() 调用 TrafficManager.getTotalTraffic()	若统计未初始化则返回 0
6	测试	验证：玩家登录、进出数据包时，流量数值是否变化；重置功能是否有效	可对比系统监控工具（如 iftop）进行粗略校验
7	（可选）	增加命令 /linglens traffic reset 以重置累计流量	需权限等级 4，调用 TrafficManager.resetTraffic()


5. 伪代码（结构性描述）
5.1 TrafficHandler 伪代码
text
类 TrafficHandler 继承 ChannelDuplexHandler:
    字段:
        trafficCounter: TrafficCounter
        executor: ScheduledExecutorService (共享)

    构造器(executor):
        trafficCounter = new TrafficCounter(executor, "LingLens-Traffic", 间隔毫秒)
        trafficCounter.start()

    方法 channelRead(ctx, msg):
        若 msg 是 ByteBuf:
            size = msg.readableBytes()
            trafficCounter.bytesRealReadFlow(size)
        继续调用 super.channelRead(ctx, msg)

    方法 write(ctx, msg, promise):
        若 msg 是 ByteBuf:
            size = msg.readableBytes()
            trafficCounter.bytesRealWriteFlow(size)
        继续调用 super.write(ctx, msg, promise)

    方法 handlerRemoved(ctx):
        trafficCounter.stop()
5.2 TrafficManager 伪代码
text
单例 TrafficManager:
    字段:
        handlerMap: ConcurrentHashMap<Channel, TrafficHandler>
        executor: ScheduledExecutorService (共享)

    方法 registerChannel(channel):
        若 channel.pipeline().get("linglens_traffic") == null:
            handler = new TrafficHandler(executor)
            channel.pipeline().addFirst("linglens_traffic", handler)
            handlerMap.put(channel, handler)

    方法 unregisterChannel(channel):
        handler = handlerMap.remove(channel)
        若 handler != null:
            // 可选：从 pipeline 移除
            channel.pipeline().remove("linglens_traffic")

    方法 getTotalTraffic():
        totalRead = 0, totalWrite = 0
        遍历 handlerMap.values():
            counter = handler.trafficCounter
            totalRead += counter.cumulativeReadBytes()
            totalWrite += counter.cumulativeWrittenBytes()
        返回 [totalRead, totalWrite]

    方法 resetTraffic():
        遍历 handlerMap.values():
            counter.trafficCounter.resetCumulativeTime()
5.3 Mixin 伪代码
text
@Mixin(targets = "net.minecraft.network.Connection$1")
abstract class MixinConnectionInit:

    @Inject(method = "initChannel", at = @At("TAIL"))
    private void onInitChannel(Channel channel, CallbackInfo ci):
        TrafficManager.getInstance().registerChannel(channel)
        channel.closeFuture().addListener { future ->
            TrafficManager.getInstance().unregisterChannel(future.channel())
        }
5.4 SystemQuery 集成伪代码
text
SystemInfo collect():
    // 原有硬件信息...
    traffic = TrafficManager.getInstance().getTotalTraffic()
    将 traffic[0] 作为 bytesReceived, traffic[1] 作为 bytesSent 存入 SystemInfo
6. 关键注意事项
方面	说明
版本兼容性	Mixin 目标 Connection$1 在不同 MC 版本中内部类命名可能变化（如 Connection$1 或 Connection$2）。建议通过 targets 使用字符串匹配，或注入到 Connection 类的某个私有方法（如 init）。可参考 Fabric/Forge 网络相关模组的 Mixin 实践。
线程安全	TrafficCounter 内部使用 AtomicLong，是线程安全的。handlerMap 使用 ConcurrentHashMap。ScheduledExecutorService 应作为单例共享，避免创建过多线程。
资源清理	务必在 Channel 关闭时移除 Handler 并从 Map 中删除，防止内存泄漏。
性能影响	TrafficCounter 每次读写仅执行原子加法，开销极小。但 ScheduledExecutorService 会周期性计算平均值（如每秒），若不需要可调大间隔或禁用，只保留累计值。
分维度统计（可选）	当前实现为全局累计，若需按玩家或维度拆分，需在 TrafficHandler 中附加玩家 ID 或维度信息，并增加多层 Map 累加。设计时可考虑扩展性。
重置行为	resetCumulativeTime() 会重置累计字节数，同时也会重置时间窗口，应确保所有 Handler 同步重置。
跨平台差异	Forge 和 Fabric 的 Netty 版本可能略有差异，但 ChannelDuplexHandler 和 TrafficCounter 均属于 Netty 公共 API，兼容性良好。
7. 测试验证点
玩家登录/退出时，流量累计值是否增加。

发送大型数据包（如地图数据）时，发送字节数是否明显增长。

执行 /linglens system 是否正常显示流量数值。

执行 /linglens traffic reset 后，流量数值是否归零（若实现重置命令）。

服务器运行一段时间后，内存占用是否稳定（无 Handler 泄漏）。

8. 后续扩展可能性
按玩家统计：在 TrafficHandler 中存储对应玩家对象，并在 TrafficManager 中提供按玩家查询的接口。

按维度统计：通过 ServerLevel 信息标记流量来源维度。

实时速率：利用 TrafficCounter 的 getLastReadThroughput() 等方法显示当前带宽。

此文档可作为编码 AI 实现该功能的完整设计蓝图，开发者可根据此描述编写具体的 Java 代码、Mixin 配置和事件监听器。如有任何细节需要澄清，可随时补充。
---
