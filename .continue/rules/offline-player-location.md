---
description: Offline player location modification
---

# 功能一：离线玩家位置修改（核心实现）
# *注意！* 请勿直接使用本篇的代码
## 1. 开发流程

| 步骤 | 模块   | 任务内容                                                                 |
|------|--------|--------------------------------------------------------------------------|
| 1    | common | 定义待传送数据模型 PendingTeleport（UUID, x, y, z, timestamp）             |
| 2    | common | 创建线程安全的存储管理器 TeleportManager（使用 ConcurrentHashMap）         |
| 3    | common | 实现数据持久化（JSON序列化，服务器启动加载、停止保存）                      |
| 4    | common | 注册命令 `/#linglens offline-tp <玩家名> <x> <y> <z>`（仅OP权限）           |
| 5    | fabric | 编写 Mixin 注入 PlayerList.placeNewPlayer 方法头部（@At("HEAD")）          |
| 6    | forge  | 编写 Mixin 注入 PlayerList.placeNewPlayer 方法头部（@At("HEAD")）          |
| 7    | 测试   | 执行命令后让玩家上线，验证位置变更且日志无原区块加载记录                    |

## 2. 核心实现原理（关键）

介入时机：Minecraft 服务端处理玩家登录的入口是 `PlayerList.placeNewPlayer(ServerPlayer player)`。这个方法的执行顺序是：

1. 读取玩家 `.dat` 文件，获取原始坐标（Pos）。
2. **【我们的 Mixin 在这里拦截】** → 将内存中的 player 坐标替换为目标坐标。
3. 根据当前坐标加载所在区块（此时已经是目标坐标，所以旧区块不会碰触）。
4. 发送区块数据给客户端，玩家成功上线。

关键点：**必须在步骤 2 介入**，即在 ChunkMap 加载区块之前修改坐标。

## 3. 代码结构（分模块）

### 3.1 common 模块：数据模型与存储

```java
// ===== common/src/main/java/com/linglin/linglens/data/PendingTeleport.java =====
public class PendingTeleport {
    private final UUID playerUuid;
    private final String playerName;
    private final double x, y, z;
    private final long timestamp;

    // 构造器、getter...
}

// ===== common/src/main/java/com/linglin/linglens/manager/TeleportManager.java =====
public class TeleportManager {
    private static final ConcurrentHashMap<UUID, PendingTeleport> PENDING_MAP = new ConcurrentHashMap<>();
    private static final File DATA_FILE = new File("config/linglens/pending_teleports.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void addPending(UUID uuid, double x, double y, double z, String name) {
        PENDING_MAP.put(uuid, new PendingTeleport(uuid, name, x, y, z, System.currentTimeMillis()));
        saveToFile();
    }

    public static PendingTeleport getAndRemove(UUID uuid) {
        return PENDING_MAP.remove(uuid);
    }

    public static boolean hasPending(UUID uuid) {
        return PENDING_MAP.containsKey(uuid);
    }

    public static void loadFromFile() {
        if (!DATA_FILE.exists()) return;
        try (Reader reader = new FileReader(DATA_FILE)) {
            PendingTeleport[] array = GSON.fromJson(reader, PendingTeleport[].class);
            for (PendingTeleport p : array) {
                PENDING_MAP.put(p.getPlayerUuid(), p);
            }
        } catch (Exception e) {
            // 日志记录错误
        }
    }

    public static void saveToFile() {
        DATA_FILE.getParentFile().mkdirs();
        try (Writer writer = new FileWriter(DATA_FILE)) {
            GSON.toJson(PENDING_MAP.values().toArray(), writer);
        } catch (Exception e) {
            // 日志记录错误
        }
    }
}
```

### 3.2 common 模块：命令注册（简化版）

```java
// ===== common/src/main/java/com/linglin/linglens/command/ModCommands.java =====
public class ModCommands {
    public static void register() {
        CommandDispatcher<CommandSourceStack> dispatcher = ...;

        dispatcher.register(
            Commands.literal("linglens")
                .then(Commands.literal("offline-tp")
                    .requires(src -> src.hasPermission(4))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        String name = StringArgumentType.getString(ctx, "player");
                                        double x = DoubleArgumentType.getDouble(ctx, "x");
                                        double y = DoubleArgumentType.getDouble(ctx, "y");
                                        double z = DoubleArgumentType.getDouble(ctx, "z");

                                        GameProfile profile = ctx.getSource().getServer().getProfileCache()
                                            .get(name).orElse(null);
                                        if (profile == null) {
                                            ctx.getSource().sendFailure(Component.literal("玩家不存在"));
                                            return 0;
                                        }

                                        TeleportManager.addPending(profile.getId(), x, y, z, name);
                                        ctx.getSource().sendSuccess(
                                            Component.literal("已设置 " + name + " 下次上线传送至 (" + x + ", " + y + ", " + z + ")"),
                                            true
                                        );
                                        return 1;
                                    })
                                )
                            )
                        )
                    )
                )
        );
    }
}
```

### 3.3 fabric 模块：Mixin 核心注入

```java
// ===== fabric/src/main/java/com/linglin/linglens/fabric/mixin/PlayerListMixin.java =====
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(
        method = "placeNewPlayer",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onPlaceNewPlayer(ServerPlayer player, CallbackInfo ci) {
        PendingTeleport pending = TeleportManager.getAndRemove(player.getUUID());
        if (pending != null) {
            player.setPos(pending.getX(), pending.getY(), pending.getZ());
            player.fallDistance = 0.0f;
            player.getPersistentData().putBoolean("linglens_teleported_on_join", true);
            LogManager.getLogger().info("LingLens: 玩家 {} 已传送至 ({}, {}, {})",
                player.getName().getString(),
                pending.getX(), pending.getY(), pending.getZ()
            );
        }
    }
}
```

Mixin 配置文件 (`fabric/src/main/resources/linglens.mixins.json`)：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.linglin.linglens.fabric.mixin",
  "compatibilityLevel": "JAVA_17",
  "mixins": [
    "PlayerListMixin"
  ],
  "client": [],
  "server": []
}
```

### 3.4 forge 模块：Mixin 核心注入（几乎一致）

```java
// ===== forge/src/main/java/com/linglin/linglens/forge/mixin/PlayerListMixin.java =====
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(
        method = "placeNewPlayer",
        at = @At("HEAD"),
        remap = false
    )
    private void onPlaceNewPlayer(ServerPlayer player, CallbackInfo ci) {
        PendingTeleport pending = TeleportManager.getAndRemove(player.getUUID());
        if (pending != null) {
            player.setPos(pending.getX(), pending.getY(), pending.getZ());
            player.fallDistance = 0.0f;
            player.getPersistentData().putBoolean("linglens_teleported_on_join", true);
            LogManager.getLogger().info("LingLens: 玩家 {} 已传送至 ({}, {}, {})",
                player.getName().getString(),
                pending.getX(), pending.getY(), pending.getZ()
            );
        }
    }
}
```

## 4. 验证方案（确保不加载原区块）

在测试时，可以通过以下方式验证旧区块未被加载：

- **开启服务端调试日志**：在 `server.properties` 或启动参数中开启区块加载日志（`-Dfabric.log.level=debug` 或 Forge 的调试模式）。
- **使用 `/forge tps` 或 `/spark` 插件**观察区块加载数量，执行传送前后对比，不应出现旧坐标所在维度的区块加载记录。
- **代码埋点**：在 Mixin 中额外加一行 `player.getCommandSenderWorld().getChunkSource().addRegionLoadingObserver(...)` 来监听加载事件（生产环境可移除此埋点）。

## 5. 注意事项

| 问题             | 解决方案                                                                                     |
|------------------|----------------------------------------------------------------------------------------------|
| 玩家在跨维度时上线 | 记住也要修改 `player.setLevel()`，否则可能卡在错误维度。可在 `PendingTeleport` 中增加 `dimension` 字段，并在 Mixin 中调用 `player.changeDimension()`。 |
| 同时在线和离线冲突 | 如果玩家在线，`offline-tp` 命令应存入待传送列表。等待玩家再次上线后传送。 |
| 数据并发安全       | `ConcurrentHashMap` 保证读写安全，`saveToFile()` 在每次写入时调用，保证异常终止也不丢失。         |
| 重置时间戳         | 如果玩家长期不上线，可定期清理过期任务（如超过7天），避免文件无限膨胀。                           |

## 6. 伪代码汇总（便于理解）

```text
指令执行:
  /linglens offline-tp <玩家> <x> <y> <z>
    ├─ 检查玩家名是否合法
    ├─ 若玩家在线: 存列表再次登录后传送
    └─ 若玩家离线:
          ├─ 封装 PendingTeleport 对象
          ├─ 存入 ConcurrentHashMap
          └─ 写 JSON 文件持久化

玩家登录时 (Mixin 拦截):
  PlayerList.placeNewPlayer(ServerPlayer player)
    ├─ @Inject @At("HEAD")
    ├─ 从 ConcurrentHashMap 按 UUID 取出 PendingTeleport
    └─ 若存在:
          ├─ player.setPos(目标坐标)   // 原区块未被加载
          ├─ player.fallDistance = 0
          ├─ 打上 NBT 标记
          ├─ 记录日志
          └─ 放行，正常加载区块 (此时加载的是目标坐标的区块)
```
