# OpenForge PLM 会话交接文档

> 最后更新：2026-08-30 ｜ 本文档由 Agent 会话结束前写入，下个会话开始时先读本文件恢复上下文

## 当前状态快照

| 维度 | 值 |
|------|-----|
| 最新发布版 | **v1.6.0**（tag + GitHub Release；UI 深度主题化 + outbox 可靠性） |
| dev 最新 | pgvector 切换 + 向量租户过滤（#80 已合并，**尚未发版**） |
| main vs dev | dev 领先 2 提交（pgvector #80 + main 回灌合并），干净同步 |
| 工作区 | 干净，无在途 PR，远端分支仅 main/dev |
| 全量测试 | 15 模块 verify 绿 + AI pytest 28 + 前端构建绿 + CLI selftest 绿 |

## v1.3.0 → 当前完成的全部工作（按 PR 序）

| PR | 内容 | 版本 |
|----|------|------|
| #61 | v1.3.0 README 版本引用回灌 dev | — |
| #62 | 性能调优（SerialGC/Hikari/Tomcat/缓存上界/查询下推/分包）+ 画像文档 + 自检清单 | — |
| #63 | 性能知识制度化——PR 模板合并门 + Loop 验证器 + CONTRIBUTING | — |
| #64 | B2 事件总线设计（信封/拓扑/幂等/outbox 分期） | — |
| #65 | compose 依赖可选化（Redis/MinIO extras、rocketmq/nacos profile、doc 数据卷修复） | — |
| #66 | B2-1 EventPublisher（信封/熔断/lazy producer/HTTP 回退）+ V23 事件表 | — |
| #70 | B2-2 事件消费者 + knowledge 自动沉淀 + 发布流水线事件优先 + 真实 MQ Testcontainers | — |
| #71 | B2-3 三域事件发射（doc/change/task） | — |
| #72 | B2 性能画像 + payload 截断 + 章节重排交叉引用 | — |
| #73 | README/CONTRIBUTING 同步交付态 | — |
| #74 | B1 Nacos 配置中心九服务接入（默认关闭 + 回路测试挂门） | — |
| #75 | **v1.5.0 发布**（B1 配置中心 → main + tag + Release + 回灌） | v1.5.0 |
| #76 | Grafana 看板模板（JVM 总览 + 网关/事件总线六计数） | — |
| #77 | UI 深度主题化（品牌 token/暗色模式/品牌化登录页/工作台仪表盘/分组菜单/Logo） | — |
| #78 | B2-P2 outbox 可靠性（事务内原子落库 + relay 补发 + 死信语义） | — |
| #79 | **v1.6.0 发布**（UI 主题化 + outbox → main + tag + Release + 回灌） | v1.6.0 |
| #80 | pgvector 向量存储切换 + 向量租户过滤（SQL 级 + 行级双保险 + Testcontainers 回路） | **未发版** |

## 关键架构决策（已实施）

1. **模块注册机制**：openforge-module.yml 自描述 → auth sys_module 注册表 → 网关 DB 动态路由 + 启动自检（route-missing → DEGRADED）→ 依赖守护（BROKEN/4020/4022）→ 前端菜单注册表驱动
2. **事件总线 B2**：EventPublisher（信封 eventId/tenantId/traceId + 熔断 60s + outbox 原子落库）→ EventOutboxRelay（60s 补发）→ AbstractEventConsumer（幂等 sys_event_consumed + 租户回填 + MDC 串联）→ 死信 %DLQ%；EVENT_ENABLED=false 回退同步 HTTP（本地/CI 零依赖）
3. **多租户**：JWT tenant 声明 → 网关 X-User-Tenant → TenantLineInnerInterceptor（全局表清单 GLOBAL_TABLES）→ 动态表显式 tenant_id 过滤 → 文件 tenant/{id}/ 前缀
4. **pgvector**：VectorStore 接口（租户感知）→ InMemory（默认 memory，H2）/ PgVector（vector-store=pgvector，SQL 级租户过滤 + HNSW + @PostConstruct 程序化建表）；compose PG 镜像换 pgvector/pgvector:pg16
5. **性能知识制度化**：画像文档 §5 自检清单（设计期四问/实现期/合并门）→ PR 模板强制勾选 → MAS 验证器 V2+ → CONTRIBUTING 合并门

## 下一步（按优先级）

1. **v1.7.0 发布**：pgvector 切换（#80 已在 dev 未发版）。README 徽章/进度表更新 → release PR → tag → GitHub Release → 回灌 dev
2. **Nacos 回路测试 Harness**：Testcontainers nacos 2.3.2/2.2.3 standalone 持久化怪癖（publish true 但 get null/not exist），挂 NACOS_LOOP_TEST 门。可用 `NACOS=1 dev-up` + `NACOS_LOOP_TEST=true NACOS_ADDR=localhost:8848` 本地对 compose nacos 排查
3. **bpmn-js 可视化流程设计器**：workflow 服务有 JSON 定义快照（ProcessDefinition.NodeDef），前端可做图形编辑器（非标准 BPMN，但引擎现有格式可直接映射）
4. **连接器与行业模板包**：需外部场景输入

## 工程约定（全程遵守，遇新坑追加）

1. feature 分支 → PR 到 dev → CI 三语言全绿 → squash 合并；release PR → main（merge commit）→ tag → GitHub Release → 回灌 dev
2. 每刀 `mvn verify` 全绿 + 相关测试；Testcontainers 不可注释
3. 本机 JAVA_HOME 指向 JDK 8，构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"`
4. 运行中 JVM 锁 jar——重打包前先停进程
5. **性能自检**：PR 模板合并门强制（内存上界/聚合下推/默认值显式/调度格式/环境画像），详见 docs/OpenForge-性能与容量画像.md §5
6. Windows 注意：WSL2 需 `.wslconfig` 限 4GB（scripts/.wslconfig.template）；Docker Desktop 崩溃时 `wsl --shutdown` + 重启
7. GitHub 间歇 502/startup_failure：空提交重触发 / close+reopen / 等待平台恢复；stacked PR 基分支被删连坐关闭 → rebase + 重建 PR

## 已知技术债 / 遗留

| 项 | 说明 |
|----|------|
| Nacos 回路测试 Harness | 容器持久化怪癖（publish true 但 get null），挂 NACOS_LOOP_TEST 门 |
| Grafana 看板告警规则 | 看板模板已内置，告警规则/通知渠道随部署环境补 |
| outbox P3 | 事件 Schema 注册与版本兼容治理 |
| 动态元数据 TTL 缓存 | DynamicRecordService.loadPublished 每请求 2 次元数据查询 |
| 日志表保留期清理 | sys_login_log/sys_audit_log 无界增长 |
| bpmn-js 流程设计器 | workflow JSON 定义快照 → 图形编辑器 |
| SkyWalking | 随规模引入（agent ~100MB 开销） |
