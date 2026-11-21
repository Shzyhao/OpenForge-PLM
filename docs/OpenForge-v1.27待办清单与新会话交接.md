# OpenForge v1.27 待办清单与新会话交接（2026-11-21）

> **【2026-11-21 v1.28 批次注记】v1.28 批次执行中**（同日 v1.27.0 发版后继续）：
> 已做：P2#12（WorkflowClient.findByBizWithHealth 区分不可达+ECR 详情 flowReachable+前端降级文案）/
> #13（Embedding 降级 warn+embedding_fallback_total）/ #14（common SecurityDefaultsChecker 非 dev profile 默认密钥醒目 WARN）/
> #15（NumberRuleService 部署约束注释钉住）/ P3#18（MaterialPage 详情抽屉+关联图纸区）/
> #19（InstanceDetailDrawer 组件，我的待办+变更详情两处接入）/ #20（RoleAdmin 用户搜索式选择+角色表分页）/
> #22（删 5 个零引用封装：fetchRecord/moveOrg/fetchProjectTaskStats/assignUserRoles/fetchPermissionTree——
> client.ts del/put 扫描清单过时实有调用，未删）/ P5#26（三基类收敛 11 份同构拷贝：
> common InternalNumberClient/InternalNotifyClient/InternalAuditClient；四 Notify 子类 @Value 默认值保留零漂移；
> drawing NumberClient 与 connector AuthAuditClient bean 名规范化为域前缀——by-type/Qualifier 已核无引用）/
> #28（ai print→logging + nl2sql 根因日志）。
> **Ecr/MetaPublish 事务内 HTTP 重构判停（评估结论）**：create 内取号+startInstance 的原子性
> （要么都落要么都回滚）是设计语义；移出事务需补偿任务（SUBMITTED 无实例卡死，惰性回流只覆盖有实例终态）
> 或 outbox 化命令消息（事件基建目前仅通知类）。现有防线已够：P1#1 超时上限 13s + Hikari pending 告警 +
> 冒烟/authz 回归面。**重启条件：连接池告警实际触发或流程启动超时工单出现**。

> **【2026-11-21 发版注记】v1.27.0 已发版（PR #124，merge 224f344）**：§3 建议批次 15 项全清
> （P1 全 8 项 + P2 的 9/10/11 + P3 的 16/17/21 + P4 的 23/24 首批 + P5 的 25/27）。
> **未做项即 v1.28 候选**：P2 的 12/13/14/15（流程降级 Tag/Embedding 降级日志/默认密钥告警/NumberRule 注释）+
> P3 的 18/19/20/22（物料图纸关联/实例详情/RoleAdmin 两处/11 个死函数）+ P4 的 25 之后批次（vitest 其余 18 页）+ P5 的 26/28（同构拷贝抽 common、ai print→logging）+ §4 注明的 EcrService/MetaPublishService 事务内 HTTP 高风险重构。
> 实纱新增四条（详见总账 #124 行）：pipe 后 $? 是 tail 的退出码；多行 log.warn 锚点插桩错位；CI 慢机按钮名须锚定正则；MinIO Item 取 objectName。

> 用途：新会话开工文档。本会话（v1.23~v1.26 四连发版）已完成全部既定批次，上下文将满，
> 待办清单基于 2026-11-21 全仓 8 维度静态扫描（TODO 标记/空实现/异常处理/测试盲区/硬编码/并发事务/前端/安全）+ 交接文档候选交叉形成。
> 新会话第一步：读本文件 → 按 §2 优先级开工 → 沿用 §5 发版流程与 §6 实纱清单。

## 1. 当前状态快照

| 维度 | 值 |
|---|---|
| 最新发布版 | **v1.26.0**（PR #123，merge defb893；动态对象回收站 · 渠道模板预置 · R23 健壮性专项） |
| dev/main | 同步于 defb893，dev 顶 79f9719（交接文档提交）；工作区干净 |
| 质量 | CI 三语言全绿；真栈收口矩阵：verify / robustness 13/0 / probe 33 / smoke 35×3 / authz 21 / vitest 14 / pytest 33 |
| 事件总线 | MQ 消费组 4 个（knowledge/connector-trigger/notify/drawing-ECO）；EVENT_BUS=1 才启用，默认 HTTP 回退 |
| 关键文档 | docs/OpenForge-会话交接.md（总账）/ v1.23~v1.26 四份设计与收口文档 / B2 事件总线设计 / mono 单进程设计 |
| 环境 | JAVA_HOME=C:\Program Files\Java\jdk-21.0.11；MAVEN_OPTS=-Xmx768m -XX:+UseSerialGC；dev-up/dev-down（EVENT_BUS=1 可选）；构建与服务绝不并行（约定④） |

## 2. v1.27 任务清单（按优先级）

### P1 真实缺陷与故障放大器（全做，约半天）

| # | 任务 | 定位（文件:行） | 修法建议 |
|---|---|---|---|
| 1 | 内部 RestClient 全量补超时 | change/client/NumberClient.java:29、doc/client/NumberClient 与 NotifyClient、drawing/client/{NumberClient,DrawingNotifyClient,DrawingWorkflowClient,AuthAuditClient}、material/client/{NumberClient,MaterialNotifyClient}、project/client/NumberClient、workflow/client/WorkflowNotifyClient、security/PermissionQueryClient、auth/ModuleRegistryService 相关 | 全部 `RestClient.builder()` 裸建=JDK 无限等待。统一加 `.requestFactory`（connect 3s / read 10s）；最省事是在 openforge-common 加一个 `InternalRestClientFactory` 静态工具逐个替换（注意 mono 同名 bean 教训——工厂方法无冲突风险） |
| 2 | 通知回退移出事务 | DocService.java:107（deliver 在 @Transactional checkIn 内）、PartService.publishReleased 的 emit 内、BomService 同型、WorkflowEngine.emitTask（事务内 act/deploy 路径） | drawing 的 publishAfterCommit（DrawingService.java:439）是正确范式：事务活跃 registerSynchronization afterCommit 再 deliver；或直接复用该模式抽到 common |
| 3 | EventOutboxRelay 行认领 | common/event/EventOutboxRelay.java:35-61 | 每个启用 MQ 的服务都内嵌 relay 共扫共享 sys_event_outbox：无 FOR UPDATE SKIP LOCKED、无 producer 属主过滤→多服务并发补发同一行+代发他服务 topic。最小修：认领 UPDATE ... SET retry_count=retry_count, sent_at=now() WHERE id IN (...) AND producer = 本服务名 RETURNING（PG）；或加 producer 过滤即可消除代发（重复补发靠消费幂等已是现状语义，先做属主过滤收益最大） |
| 4 | CronTriggerScheduler 单实例护栏 | connector/service/CronTriggerScheduler.java:58-93 | 多实例=重复触发所有 CRON 连接器。最小修：类注释钉住单实例部署约束（对齐 LogRetentionJob.java:19 先例）；或乐观锁（sys_module 表抢租约）判停留档 |
| 5 | 前端 fetch 超时 | frontend/src/api/client.ts:45-49 | request() 加 AbortSignal.timeout(ms)（GET 15s/POST 30s 可分）；401 语义不变 |
| 6 | 新建链默认 URL 去 localhost | frontend/src/pages/IntegrationPage.tsx:454 | 默认步骤 URL `http://localhost:8080/actuator/health` 改占位符（如 `https://example.com/api`），用户必改 |
| 7 | ModuleRoutePlan 纯端口拼 127.0.0.1 | gateway/modules/ModuleRoutePlan.java:83 | serviceUri 仅端口时 `http://127.0.0.1:`+port 容器场景指向网关自身；改用容器名推断或启动告警 |
| 8 | affectedItems 损坏静默 "[]" | change/service/EcrService.java:172-173 | catch 内加 log.warn（含 ecrId 与原文片段），返回语义不变 |

### P2 健壮性：降级可视化+孤儿补偿（做 #9/#10/#11，其余 v1.28）

| # | 任务 | 定位 | 修法 |
|---|---|---|---|
| 9 | MQ 消费者健康指示 | auth/event/NotifyEventConsumer、change/event/DrawingReleasedEventConsumer、connector/event/ConnectorEventConsumer、knowledge/event/KnowledgeEventConsumer（各 :6x-8x 启动 catch） | 启动失败后进程看似健康。最小修：失败置 AtomicBoolean，注册 health indicator 或 micrometer Gauge `openforge_mq_consumer_up{group=...}`；prometheus-rules.yml 加告警 |
| 10 | 文件/向量孤儿清扫 | DocService.java:165、DrawingService.java:167（GC 失败继续删行）、KnowledgeService.java:72 | purge 时 best-effort GC 失败→孤儿永久残留。补对账任务（每周：列 storage_dir 下文件 vs doc_file/drw_drawing_file 表 storage_key 差集；向量同理）删除双侧孤儿，报告计数 |
| 11 | 审计/事件链路指标 | auth/service/SecurityLogService.java:34,48；AuthAuditClient×2（connector:61/drawing:62）；common/event/EventPublisher.java:99-131 三处；EventOutboxRelay retry≥32 死信滞留 | 全部只有日志。补 Metrics.counter（audit_report_fail_total / events_lost_total / outbox_dead_total），monitoring/prometheus-rules.yml 补 3 条告警 |
| 12 | 流程状态降级可见（v1.28） | WorkflowClient.java:75 return null；DrawingWorkflowClient.java:77 | 前端 ECR/图纸列表对 flowState=null 显示"流程服务不可达"Tag |
| 13 | EmbeddingClient 降级日志（v1.28） | knowledge/service/EmbeddingClient.java:55 | 静默切词袋加 log.warn+计数器 |
| 14 | dev 默认密钥告警（v1.28） | auth application.yml JWT/internal-token 默认值 | 启动时检测默认值且非 dev profile 打醒目 WARN |
| 15 | NumberRule 多实例语义（v1.28） | auth/service/NumberRuleService.java:26,42,90 | 注释欠账：文档化部署约束或 SELECT FOR UPDATE |

### P3 功能补全（做 #16/#17/#21，其余 v1.28）

| # | 任务 | 说明 |
|---|---|---|
| 16 | 连接器单步 invoke 前端入口 | api/connector.ts:174 invokeConnector 已封装无页面调用——IntegrationPage 列表行加"调用"按钮弹参数面板（复用试运行面板组件） |
| 17 | 凭据编辑 | api/connector.ts:192 updateCredential 无入口——凭据表行内编辑（凭据值仍只写不改读） |
| 18 | 物料侧关联图纸（v1.28） | api/drawing.ts:95 fetchDrawingsByPart——MaterialPage 详情抽屉加关联图纸区 |
| 19 | 流程实例详情（v1.28） | api/workflow.ts:53 fetchInstance——我的待办/变更页加实例查看 |
| 20 | RoleAdmin 两处（v1.28） | RoleAdminPage.tsx:38 用户选前 100 截断（改搜索式选择）；:113 角色表 pagination={false} |
| 21 | 分页 showTotal 补齐 | 重点 IntegrationPage DLQ 表（:690 积压量可见性）；KnowledgePage:99、RecycleBinPage:184、DelegatesPage:116、MetaObjectsPage:258 顺带 |
| 22 | 死函数清点（v1.28） | api 层 11 个无调用函数：client.ts del/put、metadata fetchRecord、org moveOrg、project fetchProjectTaskStats、user assignUserRoles/fetchPermissionTree——逐个补入口或删封装 |

### P4 测试补齐（做 #23，#24 首批四页）

| # | 任务 | 说明 |
|---|---|---|
| 23 | CredentialService 直接测试 | 凭据 CRUD/加密零直接测试（安全面）：创建/加密存取/轮换窗口回落（AesGcmCipherTest 已有原语级） |
| 24 | vitest 第三批（22/25 页无测试） | 首批：IntegrationPage（试运行/发布/死信 Tab 空态）、Login（表单校验/提交）、UserAdminPage（启停/角色分配）、DelegatesPage（创建/撤销）。现有范式见 MyTasksPage.test.tsx（vi.mock api 层+PermContext 包装） |
| 25 | Placeholder.tsx 删除 | 无路由引用的死文件 |

### P5 结构杂项（搭车）

| # | 任务 |
|---|---|
| 26 | NumberClient×5/NotifyClient×4/AuthAuditClient×2 同构拷贝抽 common（mono 场景收益最大；注意 bean 命名） |
| 27 | 仓库根 `monitoring;C` 意外目录删除（命令笔误产物） |
| 28 | ai/gateway print→logging（main.py:183,185）；nl2sql.py:41 异常根因日志 |

### 维持外部条件门控（不动，勿重复评估）

CADConverter（需转换二进制选型）· 新内置连接器类型（需场景输入）· 断点续跑（链规模>5 步重启）· Milvus/Neo4j/ES、SkyWalking、Grafana 告警渠道（随规模）· MinIO 实启动（registry 配额；就绪件已全：双域实现+healthcheck+minio-init 桶初始化，OPENFORGE_MINIO_IT=1 门控测试）· outbox Schema Registry（轻量版已落地 EVENT_SCHEMA_VERSION，重评条件事件≥10/跨团队）

## 3. v1.27 建议批次构成

P1 全部(1-8) + P2 的 9/10/11 + P3 的 16/17/21 + P4 的 23/24首批 + P5 搭车 25/27 ≈ 15 项，一天量级。
验收即 R24 收口：全量 verify + robustness-probe（可加超时断言）+ v123-probe + smoke×3 + authz + 前端 build/test + docker-audit，然后按 §5 发版 v1.27.0。

## 4. 快速定位索引（扫描结论存档）

- 全仓 0 TODO/FIXME/console.log/any/MD5；CORS 无配置=同源架构（nginx 反代+vite 代理），勿"修复"
- 事务内同步 HTTP 全集：EcrService.java:101-132(create),139(autoCreate),341,345(apply，设计自认)；MetaPublishService.java:102-104,130-131(权限/模块登记，注释自认同命运)——P1#2 只修通知类（低风险），Ecr/MetaPublish 的事务重构风险高留 v1.28 评估
- 9 处 @Scheduled 已错峰（3:30/3:40/3:45），单服务各一，唯 LogRetentionJob 有单实例注释
- 动态表 SQL 拼接是设计使然（IdentifierValidator 白名单+测试覆盖），非缺陷
- admin 初始密码明文打印启动日志是设计权衡（AdminBootstrap.java:46），日志采集侧注意即可

## 5. 发版流程（老流程，多次验证）

1. dev 分组提交（conventional commits，按域分组）→ `git -c http.postBuffer=524288000 push origin dev`
2. **push 后必须 ls-remote 对比本地 SHA 双确认**（GitHub git-receive 间歇 502：成功也可能报 fatal；失败退避 60s×N 重试）
3. `gh run watch <id>` 三语言全绿（Backend ~5m/Frontend ~45s/Python ~15s）
4. `gh pr create` → checks 全 pass → `gh pr merge --merge`
5. **fetch origin main 后核验 merge SHA == PR merge 提交再打 tag**（v1.26 教训：fetch 撞 502 缓存旧 main，tag 打错旧 SHA，靠 ls-remote 发现后删远端 tag 重打）
6. tag push（502 退避）→ `gh release create --notes-file`（Upgrade 注意：列 Flyway 迁移与开关）
7. merge origin/main 回灌 dev → 交接文档对齐（版本/PR 表/下一步）→ push
8. 记忆同步（connector-mvp-progress.md + MEMORY.md 索引行）

## 6. 实纱速查（新会话必读，防重踩）

①mono 单 classpath 下新增 @Component/mapper 类名必须域前缀（NotifyClient/RecycleMapper 两轮五次实锤）；②同名 H2 跨测试类共享——新测试 @AfterEach 自清理+断言用增量式，surefire 类序 Linux/Windows 不同；③探针 LIKE 查询一律带前导 %（title 有前缀）；④Git Bash 后台 `&` job 不执行——脚本串行化；⑤curl 内联中文经 ANSI 转码致 JSON parse error——payload 写临时文件 --data-binary；⑥Testcontainers 本机管道不通只认 CI（Docker Desktop 掉线：杀进程重启）；⑦构建与服务绝不并行；⑧给端点加权限注解必破既有测试的 Permission mock；⑨宿主机 Maven 后台任务当天勿信 status；⑩MSYS mktemp 与原生 python 路径分歧——脚本 cd $ROOT 用相对路径；⑪surefire 需 `-XX:+EnableDynamicAgentLoading`（JDK 21.0.13+，已在父 pom）；⑫jsdom 用 25（30 需 Node 22+）；⑬打 tag 前核验 origin/main（见 §5.5）。

## 7. 会话历史索引（需要细节时查阅）

- v1.23.0：通知中心/审批委托/回收站（PR #119，设计=docs/OpenForge-站内通知-审批委托-回收站设计.md）
- v1.24.0：回收站四域+purge/订阅通知/保留期/vitest 基线/prod MQ/CI 漂移三修（PR #121，docs/OpenForge-v1.24回收站补全-订阅通知-测试基线设计.md）
- v1.25.0：清欠 12 项（PR #122，docs/OpenForge-v1.25清欠批次设计.md）
- v1.26.0：动态对象回收站/渠道模板/robustness-probe 13 断言/agentId 真 bug（PR #123，docs/OpenForge-v1.26动态对象回收站-健壮性专项设计.md）
