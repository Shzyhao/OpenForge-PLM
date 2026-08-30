# OpenForge PLM 会话交接文档

> 最后更新：2026-08-30 ｜ 本文档由 Agent 会话结束前写入，下个会话开始时先读本文件恢复上下文

## 当前状态快照

| 维度 | 值 |
|------|-----|
| 最新发布版 | **v1.7.0**（tag + GitHub Release；pgvector 向量存储切换 + 向量租户过滤） |
| dev 最新 | 流程可视化设计器 #82（自研 SVG 画布，**未发版**） |
| main vs dev | dev 领先 1 提交（设计器 #82），其余同步 |
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
| #80 | pgvector 向量存储切换 + 向量租户过滤（SQL 级 + 行级双保险 + Testcontainers 回路） | v1.7.0 |
| #81 | **v1.7.0 发布**（pgvector → main + tag + Release + 回灌） | v1.7.0 |
| #82 | 流程可视化设计器——自研 SVG 画布零依赖（bpmn-js 否决，见架构决策 6） | 未发版 |

## 关键架构决策（已实施）

1. **模块注册机制**：openforge-module.yml 自描述 → auth sys_module 注册表 → 网关 DB 动态路由 + 启动自检（route-missing → DEGRADED）→ 依赖守护（BROKEN/4020/4022）→ 前端菜单注册表驱动
2. **事件总线 B2**：EventPublisher（信封 eventId/tenantId/traceId + 熔断 60s + outbox 原子落库）→ EventOutboxRelay（60s 补发）→ AbstractEventConsumer（幂等 sys_event_consumed + 租户回填 + MDC 串联）→ 死信 %DLQ%；EVENT_ENABLED=false 回退同步 HTTP（本地/CI 零依赖）
3. **多租户**：JWT tenant 声明 → 网关 X-User-Tenant → TenantLineInnerInterceptor（全局表清单 GLOBAL_TABLES）→ 动态表显式 tenant_id 过滤 → 文件 tenant/{id}/ 前缀
4. **pgvector**：VectorStore 接口（租户感知）→ InMemory（默认 memory，H2）/ PgVector（vector-store=pgvector，SQL 级租户过滤 + HNSW + @PostConstruct 程序化建表）；compose PG 镜像换 pgvector/pgvector:pg16
5. **性能知识制度化**：画像文档 §5 自检清单（设计期四问/实现期/合并门）→ PR 模板强制勾选 → MAS 验证器 V2+ → CONTRIBUTING 合并门
6. **流程可视化设计器**：自研 SVG 画布（零依赖）——bpmn-js 评估后否决（引擎为自有 JSON 非 BPMN 2.0，XML 双向映射层是纯开销 + bpmn.io 水印条款）；条件出口由 rules[].to 渲染（expr 标注），edges 仅存 START/APPROVAL 顺序流，部署前规范化剥离死边；节点 x/y 随定义 JSON 原样存储（引擎忽略未知字段，集成测试钉住契约）

## 下一步（按优先级）

1. **Nacos 回路测试 Harness**（需 Docker 可用；已定位关键证据，见下）：CI 诊断实锤 publish true 但服务端未持久化（fetched=null + v1 HTTP "config data not exist"）；**主嫌疑=客户端 nacos-client 2.4.2 vs 服务端镜像 v2.3.2/v2.2.3 版本错配**。两处必修：① loop test 复用模式半残——NACOS_ADDR 提前 return 跳过配置发布与 NACOS_CONFIG_ENABLED 设置，复用路径必然失败；② `optional:nacos:` import 在 enabled=false 时并不跳过 loader，连接失败被吞成 "[Nacos Config] config is empty" WARN（真正兜底是 optional: 前缀）。排查路径：`NACOS=1 dev-up` 起 compose nacos（端口映射 8848/9848）→ SDK 探针验证 publish→get；若正常则怪癖锁定 CI host 网络模式，修法=改固定端口绑定替代 host 模式（客户端 +1000 推算 gRPC 端口需 9848 同号映射）
2. **v1.8.0 发布**：设计器 #82 已在 dev 未发版。README 徽章/进度表更新 + 后续路线移除设计器项 → release PR → tag → Release → 回灌
3. **连接器与行业模板包**：需外部场景输入

## 工程约定（全程遵守，遇新坑追加）

1. feature 分支 → PR 到 dev → CI 三语言全绿 → squash 合并；release PR → main（merge commit）→ tag → GitHub Release → 回灌 dev
2. 每刀 `mvn verify` 全绿 + 相关测试；Testcontainers 不可注释
3. 本机 JAVA_HOME 指向 JDK 8，构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"`
4. 运行中 JVM 锁 jar——重打包前先停进程
5. **性能自检**：PR 模板合并门强制（内存上界/聚合下推/默认值显式/调度格式/环境画像），详见 docs/OpenForge-性能与容量画像.md §5
6. Windows 注意：WSL2 需 `.wslconfig` 限 4GB（**已落盘** %UserProfile%\.wslconfig）；Docker Desktop 闪退（wsl.exe 0xc00000fd 栈溢出）处置=完整杀进程（Docker Desktop/com.docker.backend）后重启，必要时 `wsl --shutdown` 先行
7. GitHub 间歇 502/startup_failure：空提交重触发 / close+reopen / 等待平台恢复；stacked PR 基分支被删连坐关闭 → rebase + 重建 PR

## 已知技术债 / 遗留

| 项 | 说明 |
|----|------|
| Nacos 回路测试 Harness | 挂 NACOS_LOOP_TEST 门；证据与排查路径已收敛（见下一步 1），待 Docker 恢复 |
| optional:nacos import 副作用 | enabled=false 不跳过 loader，连接失败吞为 "is empty" WARN（真正兜底是 optional: 前缀）；评估是否门控 import 位置或升级 spring-cloud-alibaba |
| Grafana 看板告警规则 | 看板模板已内置，告警规则/通知渠道随部署环境补 |
| outbox P3 | 事件 Schema 注册与版本兼容治理 |
| 动态元数据 TTL 缓存 | DynamicRecordService.loadPublished 每请求 2 次元数据查询 |
| 日志表保留期清理 | sys_login_log/sys_audit_log 无界增长 |
| bpmn-js 流程设计器 | **已交付** #82——自研 SVG 画布实现（非 bpmn-js 库，决策见架构决策 6） |
| SkyWalking | 随规模引入（agent ~100MB 开销） |
