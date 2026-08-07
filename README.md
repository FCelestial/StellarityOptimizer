# StellarityOptimizer

**优化 Stellarity 整合包运行性能**，修复 True-Ending tick 和状态效果 bug。

## 功能

- 实体注册表管理（spawn_costs / energy_budget 优化）
- 玩家邻近缓存，减少无效扫描
- True-Ending tick 调度（保持原版行为不变）
- 状态效果 tick 修复（spawn 后自动清理残留效果）
- 模块化配置，可独立开关

## 适用

- Paper / Leaf 1.21+ 服务端（Java 25 构建）
- 配合 Stellarity 数据包使用

## 配置

见 `config.yml`，各模块可独立启停。