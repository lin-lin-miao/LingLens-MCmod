![:lin-lin-miao](https://count.getloli.com/get/@:lin-lin-miao-LingLens-MCmod?theme=rule34)

# LingLens - 灵棱枢

#### 综合 Minecraft 服务器管理模组（Fabric / Forge / NeoForge）
- 1.20.1 > 主要

---

## 简介

LingLens 是一款面向服务器管理员的多功能管理模组，无需前置依赖。提供 **离线玩家传送、性能监控、实体统计、聊天缓存、玩家查询** 等实用功能，帮助管理员高效维护服务器。服务端/客户端可选。

> ps:大部分代码由deepseek生成

---

## 功能一览

| 功能 | 说明 |
|------|------|
| **离线玩家位置修改** | 将离线（或在线）玩家强制传送至指定坐标与维度，下次上线时生效。 |
| **游戏/系统性能查询** | 实时查看 TPS、MSPT、CPU、内存占用及区块加载统计。 |
| **实体数量统计** | 统计全服各维度实体数量，支持缓存状态查看与手动重建。 |
| **聊天栏消息记录** | 内存缓存聊天消息，支持按玩家、关键词、时间范围检索与导出 JSON。 |
| **玩家信息查询** | 查看在线玩家列表、详细信息（含坐标、生命值、在线时长、装备等）。 |
| **实用工具** | 戴帽。 |
| **配置管理** | 运行时修改配置参数（聊天缓存上限、事件风暴阈值等），即时生效。 |

---

## 命令速览

所有命令以 `/linglens` 为根，需拥有对应 OP 权限。

### 离线传送 `offline-tp`

- `/linglens offline-tp <玩家名>` —— 传送到主世界出生点（OP 4）
- `/linglens offline-tp <玩家名> <x y z>` —— 指定坐标（主世界，OP 4）
- `/linglens offline-tp <玩家名> <x y z> <维度>` —— 指定坐标与维度（OP 4）

### 性能查询 `perf`

- `/linglens perf` —— 综合性能（TPS + 系统 + 区块，OP 0）
- `/linglens perf system` —— 系统资源（CPU + 内存，OP 0）
- `/linglens perf tps` —— 游戏性能（TPS、MSPT、维度详情，OP 0）
- `/linglens perf chunks` —— 区块加载统计（OP 0）

### 实体统计 `entity`

- `/linglens entity` —— 实体数量统计（OP 0）
- `/linglens entity rebuild` —— 强制重建缓存（OP 4）
- `/linglens entity setdirty` —— 手动设脏（OP 4）
- `/linglens entity status` —— 查看缓存状态（OP 4）

### 聊天记录 `chat`

- `/linglens chat` —— 最近 20 条消息（OP 0）
- `/linglens chat <数量>` —— 最近 N 条消息（OP 0）
- `/linglens chat player <玩家名> [数量]` —— 按玩家过滤（OP 0）
- `/linglens chat search <关键词>` —— 关键词搜索（OP 0）
- `/linglens chat time <起始分钟前> <结束分钟前>` —— 时间范围（OP 0）
- `/linglens chat since <起始索引> <结束索引>` —— 索引范围（OP 0）
- `/linglens chat clear` —— 清空缓存（OP 4）
- `/linglens chat status` —— 缓存状态（OP 0）
- `/linglens chat send <玩家名> <消息>` —— 模拟玩家消息（OP 4）
- `/linglens chat export` —— 导出 JSON（OP 4）

### 玩家信息 `players` / `player`

- `/linglens players` —— 在线玩家列表（含延迟、在线时长等，OP 0）
- `/linglens players list` —— 所有玩家（含离线）时长排名（OP 4）
- `/linglens players get <玩家名>` —— 玩家详细信息、装备（OP 4）
- `/linglens players killable <玩家名>` —— 强制处决玩家（OP 4）

### 工具 `tool`

- `/linglens tool hat` —— 互换主手与头盔物品（OP 0）

### 配置管理 `config`

- `/linglens config` —— 列出所有配置项（OP 4）
- `/linglens config set <键> <值>` —— 修改配置（OP 4）
- `/linglens config save` —— 保存配置文件（OP 4）
- `/linglens config reload` —— 从文件重载配置（OP 4）

---

## 配置文件

路径：`<游戏目录>/config/linglens.json`

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `eventStormThreshold` | 2000 | 事件风暴阈值（每秒事件数） |
| `checkIntervalMs` | 1000 | 事件风暴检测间隔（ms） |
| `chatMaxSize` | 500 | 聊天缓存最大消息数 |
| `chatRetentionDays` | 3 | 聊天记录保留天数 |
| `idleTargetIntervalTicks` | 36000 | 空闲保存理想间隔（tick） |
| `idleMaxIntervalTicks` | 72000 | 硬性最大间隔（tick） |
| `idleThresholdMs` | 45.0 | 空闲阈值（ms） |

所有参数均可在游戏内通过 `/linglens config set` 修改，并立即生效。

---

## 安装

1. 下载对应版本的模组 jar 文件（Fabric / Forge / NeoForge）。
2. 放入 `mods` 文件夹。
3. 启动服务器，无需额外依赖。

---

## 支持

- 服务端安装即可使用，客户端可选安装（部分信息命令可能仅服务端有效）。
- 如遇问题，请查阅日志（搜索 `[LingLens]`）或提交 Issue。