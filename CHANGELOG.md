# Changelog

所有重要变更都将记录在此文件中。

## [1.0.0] - 2026-07-0?

### Added
- 新增 `/linglens offline-tp <player> [<x> <y> <z> [<dimension>]]` 命令，支持离线玩家位置修改（核心功能：不加载原区块，上线后生效）
- 新增 `/linglens tps`、`/linglens mspt`、`/linglens system` 命令，查询 TPS/MSPT 及系统资源占用
- 新增 `/linglens entity` 命令（含子命令 `rebuild`、`setdirty`、`status`），查询实体数量与缓存状态（状态机：UNINIT→READY→DIRTY→REBUILDING）
- 新增 `/linglens player <name>` 命令，查询在线玩家信息与累计在线时间
- 新增 `/linglens chat` 命令（含子命令 `player`、`search`、`since`、`clear`、`status`、`send`、`export`），聊天栏消息记录与检索
- 离线玩家数据持久化：使用 JSON 文件存储待传送任务，服务器启动加载、变动时保存
- 双平台事件监听：Fabric（`ServerPlayerEvents` 等） + Forge（`@SubscribeEvent`）

### Changed
- (无)

### Fixed
- (无)

[1.0.0]: https://github.com/lin-lin-miao/LingLens-MCmod/releases/tag/v1.0.0