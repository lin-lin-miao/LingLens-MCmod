---
description: Loading-chack-information
---


功能六：已加载区块信息查询
1. 功能定义
统计维度	说明	用途
全局总区块数	所有维度已加载区块的总和	评估整体内存占用和计算负载
各维度区块数	主世界/下界/末地/模组维度分别统计	定位异常高负载的维度
强制加载区块数	使用 /forceload 或模组设置的常加载区块	排查性能瓶颈源头
区块实体数量（可选）	每个维度中带有 Tile Entity（方块实体）的区块估算	判断红石/机械负载
区块加载密度（进阶）	区块数 / 玩家数	判断玩家活动范围是否异常
2. 开发流程
步骤	模块	任务内容
1	common	创建 ChunkQuery 工具类，封装区块信息采集逻辑
2	common	设计命令 /linglens perf chunks（概览）
3	common	实现命令执行逻辑：遍历 server.getAllLevels()，调用 getChunkSource() 和 getForcedChunks()
4	common	格式化输出为表格或列表（含维度名称、加载数量、强制加载数量）
5	common	（可选）计算区块/玩家密度比，给出健康度提示
6	测试	在不同维度执行命令，验证数据准确性
1. 核心实现原理
Minecraft 服务端通过 ServerLevel（维度）管理区块加载状态，核心 API 如下：

信息	API 调用	说明
已加载区块数量	serverLevel.getChunkSource().getLoadedChunksCount()	返回当前维度已加载的区块总数，所有版本通用，高度稳定
强制加载区块列表	serverLevel.getForcedChunks()	返回 Set<ChunkPos>，调用 size() 获取数量

4. 代码结构（分模块）
4.1 common 模块：区块统计结果封装

5. 命令输出预览
/linglens perf chunks
text
=== 灵棱枢 区块加载统计 ===
总加载区块: 12450
强制加载区块: 42

维度名称                   加载数  强制数
----------------------------------------
overworld                  8500     30
the_nether                 2500      8
the_end                     900      4
thermal:deep_underground    550      0

