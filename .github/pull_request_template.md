<!-- OpenForge PR 自检（Loop 验证体系）：不通过不算完成； Agent 与人类同一标准 -->

## 自检清单

### Loop 验证（强制）
- [ ] `mvn verify` 全绿（后端 15 模块），相关模块测试覆盖本次改动
- [ ] Testcontainers 测试未被注释/跳过（Docker 不可用自动跳过 → CI 真实执行）
- [ ] 前端改动：`npm run build` 绿；AI 改动：`pytest` 绿

### 性能自检（细则见 docs/OpenForge-性能与容量画像.md §5）
- [ ] 新增内存结构（Map/缓存/聚合）有上界与过期策略
- [ ] 列表/统计聚合下推数据库，无全表载入内存计算
- [ ] 线程池/连接池/GC 显式配置（无裸默认）；连接总数 < PG max_connections
- [ ] 前端重依赖进 manualChunks；`@Scheduled` 值格式合法（毫秒/PT30S）
- [ ] 新表含 tenant_id/审计列/deleted；无租户表已登记 GLOBAL_TABLES
- [ ] 引入新基线时已同步《性能与容量画像》§2/§3

### 环境注意
- [ ] Windows：运行中 JVM 锁 jar——重打包前已停进程；构建与全套服务不同时进行
- [ ] Linux/容器：JVM 走 MaxRAMPercentage，无固定 -Xmx 冲突

## 改动说明

<!-- 做了什么 / 为什么 / 如何验证 -->
