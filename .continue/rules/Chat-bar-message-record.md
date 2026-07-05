---
description: Chat bar message record
---
# **注意！** **禁止直接使用本篇的代码**
功能七：聊天栏消息记录（内存缓存）
1. 功能定义
功能点	说明
缓存机制	仅内存缓存，不持久化到磁盘（重启即清空）
容量上限	可配置，默认 500 条，超过时自动淘汰最旧消息
消息内容	记录发送者（含 UUID）、发送时间、维度、消息内容（支持格式化文本）
获取方式	通过指令 /linglens chat 查看最近 N 条消息
权限控制	仅 OP（权限等级 ≥ 2）可查看，保护玩家隐私
可选过滤	可配置排除某些玩家（如机器人的消息）
2. 开发流程
步骤	模块	任务内容
1	common	创建 ChatMessage 数据模型（时间、发送者、维度、内容）
2	common	创建 ChatCache 缓存管理器（环形队列 + 容量控制）
3	common	设计命令 /linglens chat（查看最近消息）和 /linglens chat <条数>（查看指定条数）
4	common	实现聊天事件监听器（Fabric/Forge 分别适配）
5	common	（可选）添加配置项：缓存上限、忽略的玩家名列表
6	测试	在游戏中发送消息，验证缓存、查询、淘汰机制
3. 核心实现原理
3.1 数据结构选择：环形队列（Queue）


新消息入队时，若队列已满（size() >= maxSize），移除最旧消息，再 添加新消息。

线程安全：使用 Collections.synchronizedList 或 ReentrantReadWriteLock 保证并发安全。

3.2 消息格式存储
为了支持颜色格式化，存储时保留 Component 对象的文本表示：

使用 Component.literal(message).getString() 获取纯文本（去除样式）。

或保留 Component 本身，在输出时再渲染（更灵活但更复杂）。

建议：存储纯文本 + 发送者名称即可，输出时由命令发送者重新渲染。

3.3 跨平台事件监听
加载器	事件类	监听方式
Fabric	ServerChatEvents.CHAT	在模组初始化时注册事件
Forge	ServerChatEvent（net.minecraftforge.event.ServerChatEvent）	使用 @SubscribeEvent 监听
(NeoForge未有)	同 Forge（事件包名可能略有不同）	使用 @SubscribeEvent 监听
两者均能获取 ServerPlayer（发送者）和 Component（消息内容）。
获取所有在聊天栏中的公共内容，包括玩家进出，玩家成就，死亡消息，系统消息，聊天消息等

1. 代码结构（分模块）
4.1 common 模块：消息数据模型
4.3 common 模块：命令注册
4.4 事件监听器（平台实现）
Fabric 实现：
Forge 实现：

# 聊天缓存容量上限（条数），默认 500
chat_cache_max_size=500

/linglens chat [条数] 执行:
  ├─ 若未指定条数 → count = 20
  ├─ 从 ChatCache 读取最近 count 条消息（使用读锁）
  ├─ 若缓存为空 → 返回 "暂无缓存消息"
  ├─ 否则:
  │    ├─ 遍历消息列表
  │    ├─ 每条消息格式: [时间] 玩家名 [维度] 消息内容
  │    └─ 拼接输出
  └─ 发送给命令执行者

/linglens chat clear 执行:
  ├─ 检查权限（等级 ≥ 4）
  └─ ChatCache.clear() 清空所有缓存

聊天事件监听（异步/同步）:
  ├─ 玩家发送消息 → 触发事件
  ├─ 提取: 发送者 (ServerPlayer), 消息内容 (String)
  ├─ 跳过空消息
  ├─ ChatCache.addMessage(sender, content)
  │    ├─ 使用写锁
  │    ├─ 若缓存已满 → pollFirst() 移除最旧
  │    └─ addLast() 添加到末尾
  └─ 放行，不影响正常聊天流程


  扩展功能	说明
按玩家过滤	/linglens chat player <名称> 只显示某玩家的消息
按关键词搜索	/linglens chat search <关键词> 搜索包含关键词的消息(可选)
时间范围查询	/linglens chat since <时间> <时间> 查询指定时间到指定时间的消息
条数范围查询	/linglens chat since <n> <m> 查询n-m的消息
消息导出	/linglens chat export 将缓存导出为 JSON 文件（需要持久化权限）

过滤器 可选择添加一种或多种的过滤

模仿玩家发送消息 /linglens chat send <玩家名> <消息>  用于外部接入