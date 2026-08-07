# StellarityOptimizer 插件方案书

## 目标
替代 Stellarity 数据包中 entity/main 的 15 次 @e 全量扫描，用插件按 type/tag 索引 + 空间分区实现 O(1) 查找。

## 架构

```
StellarityOptimizer
├── EntityRegistry          // 实体注册表，按 type+tag 索引
│   ├── HashMap<EntityType, Set<Entity>>  // 按类型索引
│   ├── HashMap<String, Set<Entity>>      // 按 tag 索引  
│   ├── HashMap<ChunkCoord, Set<Entity>>  // 按区块分区
│   └── 监听 spawn/despawn 事件维护索引
├── PlayerProximityCache    // 异步更新玩家附近实体
│   ├── 每 5 tick 异步扫描玩家 ±3 区块
│   ├── 缓存每个玩家附近的实体列表（按 type+tag 分组）
│   └── 主线程只读缓存
├── TickDispatcher          // 替代 entity/main 的每 tick 逻辑
│   ├── 从缓存读取附近实体
│   ├── 有匹配才调用数据包函数（通过 dispatchCommand）
│   ├── 无匹配时零开销（不做 @e 扫描）
│   └── 可配置哪些模块由插件接管
└── Config                  // 配置文件
    ├── modules: 哪些 @e 扫描由插件接管
    ├── cache-tick-interval: 缓存更新频率
    └── debug: 调试模式
```

## 需要替代的 entity/main 扫描

| # | type | tag | 调用的函数 | 每 tick? |
|---|------|-----|-----------|---------|
| 1 | vex | !stellarity.pixie, !smithed.entity, !stellarity.aware | stellarity:entity/pixie/check | 是 |
| 2 | vindicator | stellarity.empress_of_light | stellarity:entity/empress_of_light/main | 是 |
| 3 | marker | stellarity.empress_of_light.tracker | stellarity:entity/empress_of_light/animations/death/check_death | 是 |
| 4 | ender_dragon | stellarity.ender_dragon | stellarity:entity/dragon/main | 是 |
| 5 | zombified_piglin | stellarity.flesh_piglin | stellarity:entity/flesh_piglin/main | 是 |
| 6 | marker | stellarity.spawn_egg | stellarity:entity/handle_spawn_egg | 是 |
| 7 | illusioner | !smithed.entity | stellarity:entity/animal/end_spawn | 降10tick |
| 8 | allay | stellarity.shulking | stellarity:entity/shulking/main | 是 |
| 9 | shulker | stellarity.shulking.body | stellarity:entity/shulking/main_body | 是 |
| 10 | item_display | stellarity.shulking.spike | stellarity:entity/shulking/attacks/spike/loop | 是 |
| 11 | #stellarity:end_variant_animals | !smithed.entity, nbt=variant:"stellarity:end" | stellarity:entity/animal/convert | 降10tick |
| 12 | sheep | !stellarity.invalid_animal, !smithed.entity | stellarity:entity/animal/convert_sheep | 降10tick |
| 13 | shulker | stellarity.shulking.body | bossbar set (inline) | 是 |
| 14 | shulker | stellarity.shulking.body, distance=..256, limit=1 | bossbar set (inline) | 是 |
| 15 | item_display | stellarity.shulking.ray | stellarity:entity/shulking/attacks/ray/loop | 是 |

## 工作流

1. 插件启动 → 初始化注册表（扫描已加载实体）
2. 监听 EntityAddToWorldEvent / EntityRemoveFromWorldEvent → 维护索引
3. 每 5 tick → 异步更新每个在线玩家的附近实体缓存
4. 每 tick → TickDispatcher 从缓存读取：
   - 检查"附近有没有 vindicator[stellarity.empress_of_light]"
   - 有 → dispatchCommand("function stellarity:entity/empress_of_light/main")
   - 无 → 跳过（零开销）
5. entity/main.mcfunction 中对应的 15 行删除（由插件接管）

## 数据包改动

entity/main.mcfunction 只保留：
- `execute unless entity @a run return 0`
- 10tick 计数器
- 注释说明其余由 StellarityOptimizer 插件接管

## 技术要点

- 用 BukkitScheduler 跑异步缓存更新（sync task 但间隔 5 tick）
- 用 dispatchCommand 调用数据包函数（不是重新实现 Boss AI）
- NBT 检查（如 variant:"stellarity:end"）用 PersistentDataContainer 或 Bukkit NBT API
- 实体 tag 检查用 Entity.getScoreboardTags()
- 距离检查用 Location.distanceSquared()（比 @p[distance=..256] 快得多）