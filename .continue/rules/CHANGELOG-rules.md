---
description: CHANGELOG 范例
---

2. 编写规范与格式
2.1 推荐格式：Keep a Changelog
社区广泛采用 Keep a Changelog 规范，其核心原则是：

版本号按语义化版本（SemVer）命名，如 1.2.0。

变更分类按以下类型分组：

Added — 新增功能

Changed — 现有功能的变更

Deprecated — 即将移除的功能

Removed — 已移除的功能

Fixed — Bug 修复

Security — 安全性修复

2.2 基本模板
```markdown
# Changelog

所有重要变更都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 2026-07-05

### Added
- 初始发布
- 支持 TPS / MSPT 性能查询
- 支持硬件信息查询（CPU / 内存）
- 支持实体数量统计（惰性缓存模式）
- 支持在线玩家信息查询
- 支持已加载区块信息查询
- 支持聊天消息缓存（最近 500 条）
- 支持 Netty 网络流量统计
- 支持离线玩家位置修改（不加载原区块）

### Fixed
- (无)

### Changed
- (无)

[1.0.0]: https://github.com/yourname/LingLens/releases/tag/v1.0.0
```
2.3 针对 LingLens 的 CHANGELOG 范例
```markdown
# Changelog

所有重要变更都将记录在此文件中。

## [1.2.0] - 2026-07-05

### Added
- 新增 `/linglens traffic` 命令，查看 JVM 网络流量统计
- 新增 `/linglens chat clear` 命令，清空聊天缓存
- 新增 `/linglens entity setdirty` 命令，手动标记实体缓存为脏

### Changed
- 实体统计默认启用惰性缓存模式，查询速度提升 10 倍
- 优化 `SystemQuery` 内存占用，减少 GC 压力

### Fixed
- 修复离线玩家传送后，原区块被错误加载的问题（#42）
- 修复 Fabric 环境下聊天缓存无法记录中文消息的问题

### Security
- 敏感命令（如 `offline-tp`）权限等级提升至 4

## [1.1.0] - 2026-06-20

### Added
- 支持 Forge 1.20.1 / 1.21.1
- 新增 `/linglens chunks` 命令，查看区块加载统计

### Fixed
- 修复 `getLoadedChunksCount()` 在 NeoForge 下返回 0 的问题

## [1.0.0] - 2026-06-01

### Added
- 首次发布，支持 Fabric 1.20.4 / 1.21
- 核心功能：TPS 查询、硬件信息、实体统计、玩家查询、区块查询
```
3. 编写要点
要点	说明
版本号顺序	最新的版本放在最上面（倒序），方便玩家先看到最新变化
日期格式	使用 YYYY-MM-DD（ISO 8601 标准）
链接引用	在文件末尾添加 [版本号]: GitHub Release URL，方便跳转
语言	建议使用英文（国际平台通用）或中英双语（国内玩家友好）
长度控制	每个版本的变化点控制在 5~15 条，过于琐碎的修复可合并描述

4. 发布时的使用方式
4.1 在 GitHub Release 中使用
创建 Release 时，将 CHANGELOG.md 中对应版本的内容复制到 Release 描述框。

或使用 GitHub Actions 自动读取：

```yaml
# .github/workflows/publish.yml 中的片段
- name: Get Changelog
  id: changelog
  run: |
    CHANGELOG=$(awk '/## \[${{ github.ref_name }}\]/{flag=1; next} /## \[/{flag=0} flag' CHANGELOG.md)
    echo "changelog=$CHANGELOG" >> $GITHUB_OUTPUT
```

4.2 在 CurseForge / Modrinth 中使用
手动上传时，直接粘贴对应版本的内容到“Changelog”文本框中。

自动化上传时，mc-publish Action 会自动读取 CHANGELOG.md 并上传。
