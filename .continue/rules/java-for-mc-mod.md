---
description: Comprehensive Minecraft Server Management Module Development Guide
---

正在使用VSC进行开发，编译步骤留给用户，项目位置`E:\mcmod\LingLens`。
目前正在编写`minecraft_version = 1.20.1`

在关键代码、主要函数上加入注释

日志信息使用`org.slf4j.LoggerFactory.getLogger("LingLens")`，格式`[LingLens] 日志信息`

项目名称:LingLens - 灵棱枢

综合Minecraft服务器管理模组开发指南
一、项目概述
1.1 目标功能
服务器性能查询（硬件占用：CPU/内存，游戏性能：TPS/MSPT）

实体数量查询与统计

玩家信息查询（在线/离线）

在线玩家位置修改（传送）

离线玩家位置修改（核心需求：不加载原区块）

支持RCON指令执行

服务端/客户端可选安装

1.2 技术选型
框架: Architectury（实现跨平台支持：Fabric/Forge/NeoForge）

核心工具:

Architectury Plugin (Gradle构建插件)

Architectury Loom (替代ForgeGradle的构建工具)

前置模组: 无（可选择不使用Architectury API，实现零前置）

语言: Java (JDK 17/21 根据目标MC版本)

1.3 支持的目标版本
1.20.X/1.21.X/及旧版的热门版本
策略建议: 使用分支管理不同版本，如 branch/1.20.x 和 branch/1.21.x，而非强行用一套代码适配所有版本。

2核心功能初步介绍
2.1离线玩家位置修改 
采用上线后修改生成位置- 必须在区块加载之前执行