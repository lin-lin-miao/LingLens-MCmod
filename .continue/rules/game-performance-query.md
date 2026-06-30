---
description: Game performance query
---

# 功能二：游戏性能查询（TPS / MSPT）
# *注意！* 请勿直接使用本篇的代码
## 1. 功能定义

| 指标     | 全称                        | 含义                                     | 正常范围               |
|----------|-----------------------------|------------------------------------------|------------------------|
| TPS      | Ticks Per Second            | 服务器每秒执行的游戏刻数                 | 理想值 20.0，低于 18.0 视为卡顿 |
| MSPT     | Milliseconds Per Tick       | 每游戏刻消耗的毫秒数                     | 理想值 < 50ms，超过 50ms 则 TPS 开始下降 |
| Tick 耗时分布 | 可选                   | 各维度（主世界/下界/末地）的 tick 耗时   | 用于定位哪个维度在卡服 |

## 2. 开发流程

| 步骤 | 模块                | 任务内容                                                                 |
|------|---------------------|--------------------------------------------------------------------------|
| 1    | common              | 创建 PerformanceQuery 工具类，封装 TPS/MSPT 计算逻辑                      |
| 2    | common              | 设计命令 `/linglens perf`(获取所有性能相关子命令) 及 `/linglens perf tps` 和 `/linglens perf mspt`，所有人均可获取|
| 3    | common              | 实现命令执行逻辑：获取 MinecraftServer 实例，读取 tick 数据               |
| 4    | fabric / forge      | 确保跨平台获取 MinecraftServer 的方式一致（使用 Architectury API 或各平台特有方法） |
| 5    | common              | 添加各维度（Dimension）的 tick 耗时统计，输出到命令反馈                   |
| 6    | 测试                | 在服务端执行 `/linglens tps`，验证结果与 `/spark tps`（如有）一致         |

## 3. 核心实现原理

Minecraft 服务端内部维护了一个 tick 耗时数组，通常为 `MinecraftServer.TICK_TIME`（长度 100，记录最近 100 个 tick 的耗时）。TPS 的计算方式就是取最近若干个 tick 耗时的平均值，然后换算成每秒 tick 数。

**计算逻辑：**

1. 获取 `MinecraftServer` 实例。
2. 读取 `tickTimes` 数组（`long[]`，单位：纳秒）。
3. 取最近 20 个（或 100 个）tick 的耗时，求平均 → 得到 **平均 MSPT**。
4. TPS = 1000 / 平均 MSPT，但需封顶为 20.0。

**不同版本差异：**

- **1.16.5 ~ 1.20.1**：`MinecraftServer.tickTimes` 为 `long[]`，直接访问。
- **1.20.2+**：`tickTimes` 可能变为 `LongBuffer` 或 `AtomicLongArray`，需通过 `getTickTime()` 等方法访问。
- **Forge vs Fabric**：获取 `MinecraftServer` 实例的方式略有不同，需要适配。

## 4. 代码结构（分模块）

### 4.1 common 模块：TPS/MSPT 计算工具类

```java
// ===== common/src/main/java/com/linglin/linglens/performance/PerformanceQuery.java =====
public class PerformanceQuery {

    private static final int SAMPLE_COUNT = 20; // 取最近20个tick计算TPS
    private static final double IDEAL_TPS = 20.0;
    private static final long NANOS_TO_MILLIS = 1000000L;

    /**
     * 获取当前服务器的 TPS 和 MSPT
     * @param server MinecraftServer 实例
     * @return 包含 tps 和 mspt 的结果对象
     */
    public static PerformanceResult query(MinecraftServer server) {
        // 获取 tick 耗时数组（不同版本访问方式不同，通过 PlatformHelper 适配）
        long[] tickTimes = PlatformHelper.getTickTimes(server);
        if (tickTimes == null || tickTimes.length == 0) {
            return new PerformanceResult(IDEAL_TPS, 0.0, 0.0);
        }

        // 取最近 SAMPLE_COUNT 个有效数据
        int count = Math.min(SAMPLE_COUNT, tickTimes.length);
        long sumNanos = 0;
        for (int i = 0; i < count; i++) {
            sumNanos += tickTimes[i];
        }
        double avgNanos = (double) sumNanos / count;
        double avgMs = avgNanos / NANOS_TO_MILLIS;

        // 计算 TPS（封顶 20.0）
        double tps = Math.min(IDEAL_TPS, 1000.0 / avgMs);

        // 获取各维度耗时（可选）
        Map<String, Double> dimensionMspt = getDimensionMspt(server);

        return new PerformanceResult(tps, avgMs, dimensionMspt);
    }

    /**
     * 获取各维度的 MSPT（需要遍历所有已加载的世界）
     */
    private static Map<String, Double> getDimensionMspt(MinecraftServer server) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            // 通过 ServerLevel 的 tick 耗时统计（不同版本 API 不同）
            long levelTickTime = PlatformHelper.getLevelTickTime(level);
            result.put(level.dimension().location().toString(), levelTickTime / NANOS_TO_MILLIS);
        }
        return result;
    }

    /**
     * 结果封装类
     */
    public record PerformanceResult(double tps, double mspt, Map<String, Double> dimensionMspt) {
        public String toReadableString() {
            StringBuilder sb = new StringBuilder();
            sb.append("§e=== LingLens 性能报告 ===
");
            sb.append("§fTPS: §a").append(String.format("%.2f", tps)).append("
");
            sb.append("§fMSPT: §a").append(String.format("%.2f", mspt)).append("ms
");
            if (!dimensionMspt.isEmpty()) {
                sb.append("§7维度耗时:
");
                for (Map.Entry<String, Double> entry : dimensionMspt.entrySet()) {
                    sb.append("  §f").append(entry.getKey()).append(": §a")
                      .append(String.format("%.2f", entry.getValue())).append("ms
");
                }
            }
            return sb.toString();
        }
    }
}
```

### 4.2 common 模块：跨平台获取 tick 数据的接口

```java
// ===== common/src/main/java/com/linglin/linglens/platform/PlatformHelper.java =====
public interface PlatformHelper {

    /**
     * 获取 MinecraftServer 实例
     */
    MinecraftServer getServer();

    /**
     * 获取 tick 耗时数组（各平台实现不同）
     */
    long[] getTickTimes(MinecraftServer server);

    /**
     * 获取指定维度的 tick 耗时
     */
    long getLevelTickTime(ServerLevel level);

    // 工厂方法，由各平台模块注入实现
    static PlatformHelper getInstance() {
        return InstanceHolder.INSTANCE;
    }

    class InstanceHolder {
        private static PlatformHelper INSTANCE;
        public static void setInstance(PlatformHelper instance) {
            INSTANCE = instance;
        }
    }
}
```

### 4.3 fabric 模块：PlatformHelper 实现

```java
// ===== fabric/src/main/java/com/linglin/linglens/fabric/platform/FabricPlatformHelper.java =====
public class FabricPlatformHelper implements PlatformHelper {

    @Override
    public MinecraftServer getServer() {
        return MinecraftServer.getServer(); // Fabric 直接静态获取
    }

    @Override
    public long[] getTickTimes(MinecraftServer server) {
        // Fabric 中 tickTimes 是 long[]（1.16.5 ~ 1.20.1）
        // 对于 1.20.2+，可能变为 LongBuffer，需要判断类型
        try {
            Field field = MinecraftServer.class.getDeclaredField("tickTimes");
            field.setAccessible(true);
            Object obj = field.get(server);
            if (obj instanceof long[]) {
                return (long[]) obj;
            } else if (obj instanceof LongBuffer buffer) {
                // 转换为 long[]
                long[] array = new long[buffer.remaining()];
                buffer.get(array);
                return array;
            }
        } catch (Exception e) {
            // 回退方案
        }
        return new long[0];
    }

    @Override
    public long getLevelTickTime(ServerLevel level) {
        // Fabric 中 ServerLevel 的 tick 耗时统计
        return 0; // 简化实现
    }
}
```

### 4.4 forge 模块：PlatformHelper 实现

```java
// ===== forge/src/main/java/com/linglin/linglens/forge/platform/ForgePlatformHelper.java =====
public class ForgePlatformHelper implements PlatformHelper {

    @Override
    public MinecraftServer getServer() {
        return MinecraftServer.getServer(); // Forge 同样支持静态获取
    }

    @Override
    public long[] getTickTimes(MinecraftServer server) {
        try {
            Field field = MinecraftServer.class.getDeclaredField("tickTimes");
            field.setAccessible(true);
            Object obj = field.get(server);
            if (obj instanceof long[]) {
                return (long[]) obj;
            }
        } catch (Exception e) {
            // 回退方案
        }
        return new long[0];
    }

    @Override
    public long getLevelTickTime(ServerLevel level) {
        return 0;
    }
}
```

### 4.5 common 模块：命令注册

```java
// ===== common/src/main/java/com/linglin/linglens/command/ModCommands.java（追加） =====
public class ModCommands {

    public static void register() {
        CommandDispatcher<CommandSourceStack> dispatcher = ...;

        // 根命令
        var root = Commands.literal("linglens")
            .requires(src -> src.hasPermission(2)); // 基础权限

        // === TPS 子命令 ===
        root.then(Commands.literal("tps")
            .executes(ctx -> {
                MinecraftServer server = PlatformHelper.getInstance().getServer();
                PerformanceResult result = PerformanceQuery.query(server);
                ctx.getSource().sendSuccess(Component.literal(result.toReadableString()), false);
                return 1;
            })
        );

        // === MSPT 子命令（别名） ===
        root.then(Commands.literal("mspt")
            .executes(ctx -> {
                MinecraftServer server = PlatformHelper.getInstance().getServer();
                PerformanceResult result = PerformanceQuery.query(server);
                String msg = "MSPT: " + String.format("%.2f", result.mspt()) + "ms";
                ctx.getSource().sendSuccess(Component.literal(msg), false);
                return 1;
            })
        );

        // === 性能总览（扩展） ===
        root.then(Commands.literal("perf")
            .executes(ctx -> {
                return showPerfOverview(ctx.getSource());
            })
        );

        dispatcher.register(root);
    }

    private static int showPerfOverview(CommandSourceStack source) {
        MinecraftServer server = PlatformHelper.getInstance().getServer();
        PerformanceResult result = PerformanceQuery.query(server);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== 灵棱枢 性能总览 ===
");
        sb.append("§fTPS: §a").append(String.format("%.2f", result.tps())).append("
");
        sb.append("§fMSPT: §a").append(String.format("%.2f", result.mspt())).append("ms
");
        sb.append("§f在线玩家: §a").append(server.getPlayerCount()).append(" / ").append(server.getMaxPlayers()).append("
");
        if (!result.dimensionMspt().isEmpty()) {
            sb.append("§7维度详情:
");
            for (Map.Entry<String, Double> entry : result.dimensionMspt().entrySet()) {
                String dimName = entry.getKey().replace("minecraft:", "");
                sb.append("  §f").append(dimName).append(": §a")
                  .append(String.format("%.2f", entry.getValue())).append("ms
");
            }
        }
        source.sendSuccess(Component.literal(sb.toString()), false);
        return 1;
    }
}
```

## 5. 跨版本适配要点

| Minecraft 版本         | tickTimes 访问方式                | 注意事项                                     |
|------------------------|----------------------------------|----------------------------------------------|
| 1.16.5 ~ 1.20.1       | `MinecraftServer.tickTimes` (long[]) | 反射获取即可                               |
| 1.20.2 ~ 1.20.4       | 可能为 `LongBuffer` 或 `AtomicLongArray` | 需通过 `getTickTimes()` 方法或反射适配     |
| 1.21+                 | 结构变化较大，建议使用 `server.getTickTime()` 替代 | 部分版本直接提供 `getTickTime()` 方法 |

建议：在 `PlatformHelper` 中针对不同版本使用 `@ExpectPlatform` 或 Gradle 配置条件编译。

## 6. 验证方案

| 测试项                  | 预期结果                                                         |
|-------------------------|------------------------------------------------------------------|
| 执行 `/linglens tps`    | 输出 TPS 数值，≥ 18.0 为健康                                     |
| 执行 `/linglens mspt`   | 输出 MSPT 数值，< 50ms 为健康                                    |
| 执行 `/linglens perf`   | 输出 TPS + MSPT + 各维度耗时 + 在线人数                          |
| 与 `/spark tps` 对比    | 数值误差在 ±0.1 以内（算法差异导致）                             |
| RCON 执行               | 通过 RCON 客户端执行命令，输出格式与游戏内一致                   |

## 7. 注意事项

| 问题                       | 解决方案                                                                                     |
|----------------------------|----------------------------------------------------------------------------------------------|
| `tickTimes` 为 null         | 服务器刚启动时数组未填满，返回默认 20.0 TPS 和 0.0 MSPT                                     |
| 维度 MSPT 获取失败         | 降级为仅显示全局 TPS/MSPT，不输出维度详情                                                    |
| 跨版本兼容                 | 在 `PlatformHelper` 中增加版本判断逻辑，或为不同大版本创建独立分支                           |
| 性能开销                   | tick 数据读取是 O(1) 操作，不会影响服务器性能                                                |

## 8. 伪代码汇总

```text
/linglens tps 执行:
  ├─ 获取 MinecraftServer 实例
  ├─ 读取 tickTimes 数组 (最近 100 个 tick)
  ├─ 取最近 20 个求平均 (MSPT)
  ├─ TPS = min(20.0, 1000 / MSPT)
  ├─ (可选) 遍历所有 ServerLevel，获取各维度 MSPT
  └─ 格式化输出到命令反馈

跨平台适配:
  ├─ Fabric:  反射 MinecraftServer.tickTimes
  ├─ Forge:   反射 MinecraftServer.tickTimes (或使用 Forge 特有 API)
  └─ 1.20.2+: 降级方案通过 getTickTime() 获取
```
