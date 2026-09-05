# B2 设计：事件总线（RocketMQ）

> 框架化路线 B2 实施蓝图 ｜ 状态：**已全部交付**（B2-1~B2-3 v1.4.0 #66/#70/#71；P2 outbox 可靠性 v1.6.0 #78）｜ 更新：2026-09-03
> 上游：《OpenForge-架构文档》6.2（事件清单）/6.3（依赖规则）、《OpenForge-F2动态对象运行时设计》§5（现有同步 HTTP 链路）

---

## 1. 目标

1. **解耦**：知识自动沉淀、流程触发等跨域协作从"同步 HTTP 尽力而为"改为事件驱动——
   生产者不感知消费者存在，新增消费方零改造生产者；
2. **可靠**：broker 停机不阻塞业务发布（语义分级保留），恢复后可补发（P2 outbox）；
3. **可观测**：事件携带 traceId/tenantId，消费侧日志串联、按租户隔离语义不丢；
4. **渐进**：MQ 关闭时行为与现状完全一致（回退同步 HTTP），本地/CI 零依赖。

## 2. 现状差距

| # | 现状 | 问题 |
|---|------|------|
| 1 | 发布流水线同步调 knowledge `/internal/items`（尽力而为） | knowledge 停机即丢沉淀，无补发 |
| 2 | 动态记录创建/更新无事件 | 架构文档规划的"workflow 触发流程、knowledge 摘要入库"无从谈起 |
| 3 | 事件清单（架构文档 6.2）七个域全部未实现 | 各域协作均为点对点同步或未建 |
| 4 | compose 有 rocketmq 注释态配置 | 基础设施就位但无代码接入 |

## 3. 核心设计

### 3.1 事件信封（Event Envelope）

所有业务事件统一信封（JSON），tags 承载事件类型：

```json
{
  "eventId":   "uuid（幂等去重键，生产侧生成）",
  "eventType": "schema.migrated",
  "eventVersion": 1,
  "occurredAt": "2026-08-29T12:00:00",
  "producer":  "metadata",
  "tenantId":  0,
  "traceId":   "网关链路 id（消费侧回填 MDC）",
  "payload":   { }
}
```

### 3.2 Topic 拓扑（最小面）

| Topic | Tags（事件类型） | 生产者 | 消费组 |
|-------|------------------|--------|--------|
| `openforge-meta` | schema.migrated / meta.published | metadata | knowledge |
| `openforge-object` | object.record.created / .updated | metadata(object-runtime) | knowledge、workflow(预留) |
| `openforge-doc` | doc.released | doc | knowledge(预留) |
| `openforge-change` | change.closed | change | knowledge(预留) |
| `openforge-task` | task.created / .completed | workflow | notify(预留)、统计(预留) |

原则：**一域一 topic**（消费组独立位点，互不拖累）；`part.released/bom.published`
随 material 事件化纳入 `openforge-material`（P3，本期不建）。

### 3.3 生产者（common 统一出口）

- `openforge-common` 新增 `EventPublisher`（随 starter-web 分发）：
  - `publish(topic, tag, payload)`：enabled 时经 RocketMQ starter 同步发送（可靠等级同步刷盘由 broker 配置）；
    **disabled 时回退调用方既有的同步 HTTP 客户端**——发布流水线语义与现状一致，本地/CI 零依赖；
  - 信封自动填充：producer=服务 moduleKey、tenantId=TenantContext、traceId=MDC；
- 发送时机：**事务提交后**（`TransactionSynchronization.afterCommit`），避免消费方读到未提交状态；
- P1 丢失窗口：afterCommit 发送失败仅告警（与现状同步 HTTP 尽力而为同级）；
  **P2 outbox 补齐**：`sys_event_outbox`（auth V23，同库共享）同事务写入，各服务内嵌
  60s relay 扫描未发送行补发 + 标记，消除丢失窗口。

### 3.4 消费者（幂等 + 租户 + 链路）

- 统一消费骨架（common `AbstractEventConsumer`）：
  1. 信封解析 → **幂等检查**：`sys_event_consumed(event_id PK, consumer, consumed_at)`
     （auth V23，同库共享；INSERT IGNORE 成功才处理——at-least-once 投递下的去重闸）；
  2. `TenantContext.setTenantId(envelope.tenantId)`（knowledge_item 等租户表写入语义正确）；
  3. `MDC traceId = envelope.traceId`（消费日志与生产请求可串联）；
  4. 业务处理 → 异常抛出触发 RocketMQ 递增重试，超限进死信 `%DLQ%{consumerGroup}`；
- 首批消费者：knowledge 消费 `schema.migrated`（转 SCHEMA 知识条目，替代发布流水线里的
  同步调用）与 `object.record.created/.updated`（记录摘要入库——AI 知识自动沉淀的正主）；
- AI 网关不在本期消费组：Python RocketMQ 客户端不成熟，且发布时 metadata 已同步调
  `/internal/tables` 登记（F2-3）；后续如需事件化，加 Java 桥接服务而非 Python 客户端。

### 3.5 配置与开关（默认关闭，Nacos 同模式）

```yaml
openforge:
  event:
    enabled: ${EVENT_ENABLED:false}        # 关闭=现状语义（同步 HTTP 回退）
    namesrv-addr: ${ROCKETMQ_NAMESRV:localhost:9876}
    producer-group: openforge-{moduleKey}
```

compose：rocketmq namesrv/broker 注释态转 `profiles: ["rocketmq"]`，
`docker compose --profile rocketmq up -d` 启用；K8s 随 Helm values 开关。

## 4. 安全红线

1. broker 仅内网可达（compose 不发布 9876/10911 端口到宿主机之外；K8s ClusterIP）；
   P1 不开 ACL（内网信任模型与 X-Internal-Token 一致），公网/跨信任域部署前必须启用 ACL + 独立凭据；
2. payload 不携带敏感明文（密码/令牌/文件内容），遵循现有表字段暴露面；
3. 消费端信任模型：事件来自内网 broker + 信封完整性由消费组独占性保障；跨信任域时
   信封需签名（P3 生态项）；
4. 幂等表/outbox 表为平台表，登记 `TenantTables.GLOBAL_TABLES`（无租户列，
   租户语义在 envelope 内由消费侧显式设置）。

## 5. 实施切分

| PR | 内容 | 规模 |
|----|------|------|
| B2-1 | common EventPublisher（信封/开关/afterCommit/HTTP 回退）+ compose rocketmq profile + auth V23（outbox/幂等表）+ 单测 | 中 |
| B2-2 | knowledge 消费者（schema.migrated + object.record.*，幂等/租户/MDC 骨架）+ 发布流水线切换事件优先 + 动态记录事件发射 + 通用 RocketMQ Testcontainers 真实 broker 验证 | 中 |
| B2-3 | doc.released / change.closed / task.* 发射（各服务挂 EventPublisher）+ 消费积压指标进 /actuator/prometheus + 死信告警 | 中 |

依赖：B2-1 → B2-2 → B2-3；与现行 F 系列无文件冲突。

## 6. 验收标准（B2 Definition of Done）

1. `EVENT_ENABLED=false`（默认）：发布动态对象行为与 v1.3.0 完全一致（同步 HTTP 回退路径）；全量测试绿；
2. `EVENT_ENABLED=true`：发布动态对象 → knowledge 异步出现 SCHEMA 条目（消费日志带原请求 traceId、租户正确）；
3. 创建动态记录 → knowledge 异步出现摘要条目；重复投递（人工重发同 eventId）不产生重复条目；
4. broker 停机：业务发布不受阻（P1 告警日志 / P2 outbox 补发恢复后自动到位）；
5. 通用 RocketMQ Testcontainers（apache/rocketmq 镜像）真实 broker 链路在 CI 绿（本机 Docker 不可用自动跳过）。

## 7. 演进（不在本期）

- ~~outbox 可靠性补齐（P2）~~ → **已交付** v1.6.0 #78：事务内原子落库 + afterCommit 发送 + 60s relay 补发 + retry≥32 死信；
- notify 服务（站内信/Webhook）与 search 索引消费组；
- 事件 Schema 注册与版本兼容治理（eventVersion 升级策略）——outbox P3 技术债；
  **评估判停（v1.12.1 会话）**：当前 5 事件类型/1 消费者（knowledge）/单团队，payload 加字段天然向后兼容，
  Registry+兼容性 CI 成本 >> 收益。**重启触发条件（满足任一）**：消费者 ≥3 或跨团队消费 /
  首次 payload 破坏性变更需求 / 事件类型 ≥10。届时先轻量（信封 eventVersion + 消费端按版本分支），
  规模化再上 Registry；
- material 事件域（part.released/bom.published → connector 推 ERP）。
