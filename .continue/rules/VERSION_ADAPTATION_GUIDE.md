---
description: 多版本分支适配与切换说明书
---

# LingLens — 多版本分支适配与切换说明书

> 本说明适用于 **分支独立维护方案**：以 `main` 分支为最新稳定版本的上游，每个 Minecraft 大版本创建独立分支，在分支中独立适配。不提取公共核心模块，保持完整代码在每个分支中。

---

## 1. 分支策略

```
main          ← 始终指向**最新支持的 Minecraft 版本**（例如 1.21.1）
   │
   ├── 1.20.x   ← 1.20.1 及相关维护版本
   ├── 1.21.x   ← 1.21.1 及相关维护版本
   └── …        ← 按需创建更早版本分支
```

**原则：**
- **每个分支独立构建、独立发版**，各自拥有完整的 `common/fabric/forge/neo` 模块。
- 公共代码通过**复制 + 手动合并**同步，不引入子模块或共享库。
- `main` 承担核心开发，Bug 修复通常先在 `main` 完成，再 **cherry-pick** 回其他维护分支。

---

## 2. 从当前分支创建新版本分支

假设当前 `main` 是 1.20.1 版本，要适配 1.21.1：

### 2.1 冻结 1.20.x 分支

```bash
git checkout -b 1.20.x         # 基于当前 main 创建 1.20.x 分支
git push origin 1.20.x
git branch --set-upstream-to=origin/1.20.x
```

### 2.2 将 main 切换到新版本

```bash
git checkout main
# main 现在仍然是 1.20.1 的代码，接下来修改为 1.21.1 配置
```

---

## 3. 适配新版本需要修改的关键文件

以下改动应在 `main` 分支（新版本）上进行：

### 3.1 `gradle.properties`

| 键 | 示例值 |
|---|--------|
| `minecraft_version` | `1.21.1` |
| `java_version` (如需) | `21` |
| `fabric_loader_version` | `0.16.9` |
| `fabric_api_version` | `0.107.0+1.21.1` |
| `forge_version` (如支持) | 1.21 后 Forge 已停止，改用 NeoForge |
| `neo_version` (新增) | `1.21.1-21.1.91` |
| `enabled_platforms` | `fabric,neo` |

### 3.2 平台模块的 `build.gradle`

- **Fabric**: 更新 fabric-loader 和 fabric-api 版本。
- **Forge → NeoForge**: 如果 1.21 改用 NeoForge，需重写 `forge` 模块为 `neo` 模块，或完全替换。

### 3.3 Mixin 目标类

Minecraft 内部类可能随版本变化（例如 `ServerPlayer`、`Connection` 的方法签名改变），需检查：

| 当前位置 | 可能的变化 |
|----------|-----------|
| `common/.../mixin/ServerPlayerMixin.java` | `readAdditionalSaveData` 参数或返回值 |
| `common/.../mixin/ConnectionMixin.java` | `channelRead` 方法包路径 |
| `common/.../mixin/ServerLevelMixin.java` | `tick` 方法签名 |
| `common/.../mixin/PlayerListMixin.java` | 构造函数参数 |

**处理方法：**
- 每个分支保留自己版本的 Mixin 代码。
- 若 Mixin 目标类不存在或方法改变，需调整（可能使用 `@Overwrite` 或更改 `@At` 注入点）。

### 3.4 Fabric/Forge/Neo 事件 API

| 平台 | 版本差异 |
|------|---------|
| Fabric | 事件注册方式基本一致，但部分事件类包路径改变（如 `ServerMessageEvents`）。 |
| Forge → Neo | Forge 事件系统与 NeoForge 差异较大，需重写 `@SubscribeEvent` 注册逻辑和核心事件监听。 |
| 若 1.21 需支持 NeoForge，需新增 `neo/` 模块并配置对应的 build.gradle。 |

### 3.5 其他 API 变更

- **`ServerPlayer`**: `getLevel()` → `serverLevel()` (1.20→1.21)。
- **`Registry`**: 1.21 使用 `Holder` 和 `ResourceKey` 访问注册表。
- **数据组件系统**: 1.21 引入 Data Components，物品 NBT 处理方式变化。
- **聊天系统**: 1.21 聊天签名系统可能变化。

---

## 4. 分支间的代码同步（Bug 修复、功能移植）

### 4.1 从 main（新版本） cherry-pick 到旧维护分支

```bash
git checkout 1.20.x
git cherry-pick <commit-hash>          # 引入单个提交
git cherry-pick <start>..<end>        # 引入一段提交范围
```

### 4.2 从旧分支合入 main（极少，仅在需要向下兼容时）

```bash
git checkout main
git cherry-pick -m 1 <merge-commit-hash>   # 适用于合并提交
```

### 4.3 解决版本差异冲突

当 cherry-pick 出现冲突时，需手动修改代码以适应目标版本的 API。例如：
- 1.21.x 使用了 `serverLevel()`，而 1.20.x 仍是 `getLevel()`。
- 解决方案：在 cherry-pick 时保留目标版本的写法，或使用条件编译（不推荐）。

---

## 5. 构建与测试

```bash
# 在对应分支上构建
git checkout 1.20.x
./gradlew build

git checkout main   # 此时是 1.21.x
./gradlew build
```

**注意事项：**
- 每个分支独立运行 Gradle，可能需要在各分支首次构建时下载对应的 MC 资源和依赖。
- 可在 `gradle.properties` 中调整 Java 版本（1.20.1 用 Java 17，1.21.1 用 Java 21）。
- 建议在 CI 中配置多分支自动构建，避免本地环境切换。

---

## 6. 版本标签命名规范

```bash
git tag -a v1.0.0-mc1.20.1 -m "Release v1.0.0 for Minecraft 1.20.1"
git tag -a v1.1.0-mc1.21.1 -m "Release v1.1.0 for Minecraft 1.21.1"
```

标签格式：`v<模组版本>-mc<MC版本>`

---

## 7. 典型工作流示例

### 场景：修复 1.20.1 分支上的核心 Bug，并同步到 1.21.1

```bash
# 1. 在 1.20.x 分支修复
git checkout 1.20.x
# 修改代码
git commit -am "fix: correct offline-tp NPE when player not found"
git push

# 2. cherry-pick 到 main (1.21.1)
git checkout main
git cherry-pick <commit-hash>
# 解决可能出现的 API 冲突（如 getLevel vs serverLevel）
# 提交并推送
git commit --amend -m "fix: correct offline-tp NPE (backport from 1.20.x)"
git push
```

### 场景：新建 1.22.x 分支

```bash
# 从 main（当前最新）创建
git checkout -b 1.22.x
git push origin 1.22.x

# 修改 gradle.properties 来适配 1.22
git commit -am "chore: update to MC 1.22"
git push
```

---

## 8. 持续集成建议

在每个分支的 `.github/workflows/build.yml` 中配置：

```yaml
on:
  push:
    branches:
      - main
      - 1.20.x
      - 1.21.x
```

这样每次推送到任意版本分支都会触发对应版本的构建。

---

## 9. 注意事项

- **不共享 `core` 模块**：每个分支会存在代码冗余，但降低了版本耦合复杂度，适合维护较少的大版本。
- **Mixin 差异**是版本迁移的最大工作量，建议在分支中直接修改，不要试图用条件注解。
- **Forge 与 NeoForge**：MC 1.21 后 Forge 已合并到 NeoForge，若需支持需重写 `forge` 模块为 `neo`。可在 `1.20.x` 分支保留 Forge，而 `main`（1.21.x）使用 NeoForge。
- **Java 版本**：不同 MC 版本可能要求不同 JDK，请确保环境已安装对应版本。

---

> 本说明书由 AI 生成，作为后续对话的上下文参考。当需要适配新版本或切换分支时，请遵照上述流程操作。