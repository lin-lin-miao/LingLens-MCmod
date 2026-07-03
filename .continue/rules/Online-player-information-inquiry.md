---
description: Online player information inquiry
---
# **注意！** **禁止直接使用本篇的代码**

功能五：在线玩家信息查询
1. 功能定义
查询维度	具体信息	说明
在线状态	在线总时长、当前时长	累计在线时间
位置信息	维度	当前所在维度
生命状态	生命值、饥饿值、经验等级	当前游戏内状态
延迟（可选）	延迟（Ping）	便于排查网络问题

2. 开发流程
步骤	模块	任务内容
1	common	创建 PlayerInfoQuery 工具类，封装玩家信息采集逻辑
2	common	设计命令 /linglens players（列出所有在线玩家概要）和 /linglens player <名称>（查询单个玩家详细信息）
3	common	实现命令执行逻辑：获取 MinecraftServer 实例，遍历 getPlayerList().getPlayers()
4	common	格式化输出：概要模式用表格/列表，详细模式用结构化文本
5	测试	验证在 1 人 / 多人 / 不同维度下的输出正确性

3. 核心实现原理
Minecraft 服务端通过 MinecraftServer.getPlayerList() 管理所有在线玩家，返回 PlayerList 对象。通过 PlayerList.getPlayers() 可以获取所有在线玩家的 ServerPlayer 列表。

数据采集来源：

信息	获取方式	注意事项
玩家名称	ServerPlayer.getGameProfile().getName()	
UUID	ServerPlayer.getUUID()	
在线时长	System.currentTimeMillis() - 玩家首次登录时间	需要存储登录时间（玩家加入时记录）
当前位置	ServerPlayer.position()	返回 Vec3
当前维度	ServerPlayer.level().dimension().location()	返回 ResourceLocation
生命值	ServerPlayer.getHealth()	浮点数
饥饿值	ServerPlayer.getFoodData().getFoodLevel()	整数
经验等级	ServerPlayer.experienceLevel	整数
游戏模式	ServerPlayer.gameMode.getGameModeForPlayer()	返回 GameType
玩家 Ping	ServerPlayer.latency	整数（毫秒），仅服务端可见

1. 代码结构（分模块）
4.1 common 模块：玩家信息数据模型
4.2 common 模块：玩家信息查询器
4.3 common 模块：命令注册
4.4 事件监听：记录登录/登出时间
需要监听玩家登录和登出事件，记录在线时长。
Fabric 实现：
```java
public class FabricPlayerEventListener {
    
    public static void register() {
        // 玩家登录
        PlayerEvents.PLAYER_JOIN.register((player) -> {
            if (!player.level().isClientSide()) {
                PlayerInfoQuery.recordLogin(player.getUUID());
            }
        });
        
        // 玩家登出
        PlayerEvents.PLAYER_QUIT.register((player) -> {
            if (!player.level().isClientSide()) {
                PlayerInfoQuery.recordLogout(player.getUUID());
            }
        });
    }
}
```
Forge 实现：
```java
@Mod.EventBusSubscriber
public class ForgePlayerEventListener {
    
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInfoQuery.recordLogin(player.getUUID());
        }
    }
    
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerInfoQuery.recordLogout(player.getUUID());
        }
    }
}
```

1. 命令输出预览
/linglens players（在线玩家列表）
```text
=== 在线玩家列表 (3 / 20) ===
名称[维度]|HP等信息|在线总时长(+当前时长)|延迟
§fAlice §7[minecraft:overworld] §e12.3, 64.0, -45.1 §7| HP: x/x §7| 2h 15m (+26m) §7| 45ms
§fBob §7[minecraft:the_end] §e-100.5, 80.0, 200.3 §7| HP: x/x §7| 1h 30m §7| 120ms
§fCharlie §7[minecraft:the_end] §e0.0, 50.0, 0.0 §7| HP: x/x §7| 5m 30s §7| 230ms
```

/linglens players 执行:
  ├─ 获取 MinecraftServer 实例
  ├─ 获取 server.getPlayerList().getPlayers()
  ├─ 对每个 ServerPlayer:
  │    ├─ 从 loginTimestamps 获取登录时间
  │    ├─ 构建 PlayerInfo 对象（含位置/生命/饥饿/经验/延迟等）
  │    └─ 生成单行摘要文本
  ├─ 格式化输出: 标题 + 序号 + 摘要
  └─ 发送给玩家



