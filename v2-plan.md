# StellarityOptimizer v2 方案 - 全面接管剩余 @e 扫描

## 当前状态
- entity/main ✅ 已由插件接管（15 个扫描）
- 剩余 ~26 个 @e 扫描仍在数据包中

## 剩余热点分析

| 优先级 | 文件 | 扫描数 | 说明 |
|:------|:----|:-----:|:----|
| P0 | main.mcfunction (marker loop) | 1 | 扫描全服所有 marker，最贵 |
| P0 | main.mcfunction (item loop) | 1 | 扫描全服所有 item |
| P0 | main.mcfunction (end_crystal) | 2 | 末地城水晶 + 出口传送门水晶 |
| P1 | kohara/main | 4 | 状态效果/粒子/物品/治疗 |
| P1 | item/main | 6 | 龙刃/箭/三叉戟/锤子 |
| P1 | True-Ending tick | ~12 | 标记实体/龙火球/幻影守卫 |
| P2 | util/main | 3 | 效果云/花 |
| P2 | block/main | 2 | 方块放置/tick |
| P2 | sfx/main | 1 | 末影之眼 |
| P2 | mechanic/main | 1 | 末地水晶交互 |
| P2 | item_loop | 4 | 炼药锅 |

## 架构扩展

### 1. AsyncMarkerTracker
- 监听 marker 的 spawn/despawn
- 维护 `Map<String, Set<Entity>>` 按 tag 索引 marker
- 异步更新玩家附近 marker 列表
- 替代 `@e[type=marker,tag=stellarity.marker]`

### 2. AsyncItemTracker
- 监听 item 的 spawn/despawn
- 维护 `Map<String, Set<Entity>>` 按 tag 索引 item
- 替代 `@e[type=item,tag=stellarity.item]`

### 3. AsyncTrueEndingDispatcher
- 处理 True-Ending 的 @e 扫描
- 龙 Boss AI 标记、幻影守卫、龙火球等
- 保留时钟逻辑在数据包中

### 4. 数据包改动
- main.mcfunction → 空壳（由插件全权调度）
- kohara/main → 空壳
- item/main → 空壳
- True-Ending tick → 保留时钟逻辑，@e 扫描由插件接管

## 预期效果
- EntitySelector 占比从 46.81% → <5%
- 高峰卡顿消除
- TPS 稳定 20.00