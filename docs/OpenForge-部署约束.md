# OpenForge 部署约束（单实例语义与扩展前置条件）

> 版本基线：v1.29.0（2026-11-22）｜本文档集中陈述**当前实现的部署形态约束**——此前散落在
> 代码注释（LogRetentionJob / NumberRuleService / CronTriggerScheduler / 三个孤儿清扫 Job），
> scale-out 前必须整体阅读本文档。与《架构文档》"水平扩展"愿景态的差距在 §3 逐项列明。

## 1. 核心约束：所有服务均为单实例

**当前所有 OpenForge 服务（auth/connector/doc/drawing/knowledge/material/metadata/project/workflow/gateway 及 mono 聚合形态）不支持多实例部署。** 单实例不是临时妥协，而是一组机制共同的设计前提：

| # | 依赖单实例的机制 | 位置 | 多实例下的故障形态 |
|---|---|---|---|
| 1 | **模块注册表** sys_module（心跳/路由/依赖守护） | auth 服务 | 多实例心跳互相覆盖，路由/依赖状态抖动 |
| 2 | **编号引擎** JVM 分段锁（`(ruleKey:period)` → synchronized） | `NumberRuleService` | 跨进程锁失效 → 并发取号靠 DB 唯一约束反复重试（取号 500 + 重试风暴） |
| 3 | **CRON 连接器调度**（每 30s 全量重同步触发） | `CronTriggerScheduler` | 每实例都触发全部 CRON 连接器 → 重复执行 |
| 4 | **保留期/孤儿清扫 @Scheduled 任务**（日志保留、存储/向量对账） | `LogRetentionJob` / `StorageOrphanSweepJob`×2 / `VectorOrphanSweepJob` / `NotifyRetentionJob` / `ExecLogRetentionJob` | 重复清理（幂等但浪费）与日志互相干扰 |
| 5 | **outbox relay**（属主过滤 `producer=本服务` 后应用内扫描补发） | `EventOutboxRelay` | 同服务多实例并发补发同一行（有消费幂等兜底，重试计数被放大） |

prod 默认形态为 **mono 单进程**（`--profile mono`，v1.21 起），天然满足本约束；`--profile scale-out` 全服务形态同样**每服务单实例**。

## 2. 其他部署形态约束

- **事件总线**（RocketMQ）：`EVENT_BUS=1` 开启（dev）/ prod compose rocketmq profile；默认关闭时通知走 auth HTTP 回退通道，功能完整。消费者启动失败不阻断进程——以 `openforge_mq_consumer_up{group}` gauge + Prometheus 告警观测（v1.27）。
- **文件存储**：默认 `local`（本地磁盘，键约定 `tenant/{tenantId}/{date}/{uuid}`）；MinIO 实现已就绪（`openforge.{doc,drawing}.storage=minio` + 桶自动初始化），但**仅多节点共享存储场景需要**——单机/mono 形态不应启用（v1.29 复盘结论：单机部署对象存储无收益）。
- **默认密钥**：`INTERNAL_TOKEN` / `JWT_SECRET` 环境变量必须在生产覆盖——显式非 dev/test/local profile 下仍为默认值时启动打醒目 WARN + `openforge_default_secret_active` 计数（v1.28 `SecurityDefaultsChecker`）。
- **向量存储**：默认内存（`memory`）；`pgvector` 多租户/生产切换（SQL 级租户过滤）。Milvus 为千万级路线项，未启用。

## 3. scale-out 前置条件矩阵（扩容前必须逐项改造）

| 服务 | 扩容前置改造 | 参考实现路径 |
|---|---|---|
| auth | ① 注册表：sys_module 心跳改按实例标识/租约；② 取号：`nextNumber` 改 `SELECT FOR UPDATE`（同事务 read+write）或 Redis INCR；③ 保留期任务：抢租约或分片 | 架构文档 9.1 |
| connector | CRON 调度：sys_module 乐观锁抢租约（单轮触发权）或 Quartz 集群模式 | CronTriggerScheduler 类注释 |
| doc/drawing/knowledge | 清扫 Job：租约或分片；文件存储必须先切 MinIO（本地磁盘多实例不可见） | §2 存储形态 |
| 全部 | outbox relay：`FOR UPDATE SKIP LOCKED` 行认领（PG）或单实例 relay 专职化 | EventOutboxRelay 类注释 |

> 注：《架构文档》§"水平扩展无状态服务 + K8s HPA"是**愿景态**；上表改造完成前，该表述不适用于当前版本。扩容信号出现（单实例 CPU/内存持续高位）时，优先评估 mono → scale-out + 上述改造的一次性批次，而非逐服务零散扩容。

## 4. 约束的维护约定

- 新增 `@Scheduled` 任务、应用内锁、注册表写入、共享表扫描类机制时，**必须**在本文件 §1 表格登记一行（或注明为何无单实例依赖）。
- 本文件与代码注释双向引用：代码注释写"见 docs/OpenForge-部署约束.md"，本文件列机制所在类名。
- 新增门控/判停项时（v1.30 清单 C 组模式），**必须**同时写**判据**（可观测触发信号）与**最近复评日期**，并登记进 v1.30 待办清单 C 组；每季度扫一遍复评日期防僵尸项（触发前开工=过度设计，v1.30 A3 制度化）。
