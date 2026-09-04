# OpenForge PLM 会话交接文档

> 最后更新：2026-09-05 ｜ 本文档由 Agent 会话结束前写入，下个会话开始时先读本文件恢复上下文

## 当前状态快照

| 维度 | 值 |
|------|-----|
| 最新发布版 | **v1.11.0**（tag + GitHub Release；BROKEN 可观测性 + Nacos 回路修复 CI 常开 + 设计器预览坐标兜底） |
| dev 最新 | 与 main 同步（发布 PR #96 merge commit e7b4253 + 回灌 fast-forward），无未发版提交 |
| main vs dev | 完全同步 |
| 工作区 | 干净，无在途 PR，远端仅 main/dev；服务全停；本地 admin 密码 smoke-test-2026（dev 库） |
| 全量测试 | 15 模块 verify 绿（含 Nacos 回路 CI 真跑）+ AI pytest + 前端构建 + **网关链路冒烟 8/8 域 + AI 网关离线四接口 + 浏览器级登录/工作台/设计器（RSS 1.87GB）** |

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
| #82 | 流程可视化设计器——自研 SVG 画布零依赖（bpmn-js 否决，见架构决策 6） | v1.8.0 |
| #83 | **v1.8.0 发布**（设计器 → main + tag + Release + 回灌） | v1.8.0 |
| #84 | 元数据 TTL 缓存（租户键/30s 可配/500 上界/afterCommit 驱逐）+ 日志保留期清理（180 天可配/分批 500） | v1.9.0 |
| #85 | **v1.9.0 发布**（技术债清偿 → main + tag + Release + 回灌） | v1.9.0 |
| #86 | 本地开发瘦身：#62 JVM 调优实装（曾只落 MAVEN_OPTS）+ 构建智能跳过 + PROFILE 预设 + yml 池变量化 + WSL 2GB | 未发版 |
| #87 | 瘦身二刀：Nacos import 空载 + AppCDS（gateway 实测启动 -36%/3173 类共享，CDS=0 可关）+ START_PARALLEL=2 分批并行 | 未发版 |
| #88 | 测试侧 Nacos 关闭：八服务 test yml 显式 enabled:false——消除 JVM 退出每上下文 ~10s 阻塞（CI 实证） | 未发版 |
| #89 | 全文档对齐 v1.9.0 交付态：架构 v1.1/开发 v1.1/B2 已交付态/二开 v1.1/路线后交付表/MAS 落地标注/README 快速开始 | 未发版 |
| #90 | 冒烟修复四处交付缺陷：路由缺 RefreshRoutesEvent（动态路由从未生效）/裸端口 URI/KERNEL 自注册被防劫持拒（metadata 连坐 BROKEN）/knowledge yml 重复键；RSS 实测 1.86GB 回写画像 | v1.10.0 |
| #91 | **v1.10.0 发布**（瘦身 + 冒烟修复 → main + tag + Release + 回灌） | v1.10.0 |
| #92 | BROKEN 模块静默摘除可观测性——module-routes 端点/health details 暴露 brokenModules 与原因（依赖未启用: xxx）+ auth 守护日志 module broken/recovered 落地（F2 设计 3.4 首次以 diff 为准） | v1.11.0 |
| #93 | Nacos 回路测试修复——**publish 读可见性竞态实锤**（零间隔 A/B 一次 NULL 一次 OK，与版本错配/网络方案无关）+ getConfig 退避重试 + 复用模式补发布 + 固定端口容器（替代 host 网络）+ test yml import 默认翻空（8 服务 loader 空载消除）+ 镜像 v2.4.3（v2.3.2/v2.2.3 libtinfo 损坏无法启动）+ **ci.yml NACOS_LOOP_TEST 常开** | v1.11.0 |
| #94 | 流程设计器只读预览遗留定义坐标兜底——浏览器级冒烟实锤：#82 前部署的定义无 x/y，查看路径未走自动布局，节点全叠 (0,0) 仅 END 可见 | v1.11.0 |

## 关键架构决策（已实施）

1. **模块注册机制**：openforge-module.yml 自描述 → auth sys_module 注册表 → 网关 DB 动态路由 + 启动自检（route-missing → DEGRADED）→ 依赖守护（BROKEN/4020/4022）→ 前端菜单注册表驱动
2. **事件总线 B2**：EventPublisher（信封 eventId/tenantId/traceId + 熔断 60s + outbox 原子落库）→ EventOutboxRelay（60s 补发）→ AbstractEventConsumer（幂等 sys_event_consumed + 租户回填 + MDC 串联）→ 死信 %DLQ%；EVENT_ENABLED=false 回退同步 HTTP（本地/CI 零依赖）
3. **多租户**：JWT tenant 声明 → 网关 X-User-Tenant → TenantLineInnerInterceptor（全局表清单 GLOBAL_TABLES）→ 动态表显式 tenant_id 过滤 → 文件 tenant/{id}/ 前缀
4. **pgvector**：VectorStore 接口（租户感知）→ InMemory（默认 memory，H2）/ PgVector（vector-store=pgvector，SQL 级租户过滤 + HNSW + @PostConstruct 程序化建表）；compose PG 镜像换 pgvector/pgvector:pg16
5. **性能知识制度化**：画像文档 §5 自检清单（设计期四问/实现期/合并门）→ PR 模板强制勾选 → MAS 验证器 V2+ → CONTRIBUTING 合并门
6. **流程可视化设计器**：自研 SVG 画布（零依赖）——bpmn-js 评估后否决（引擎为自有 JSON 非 BPMN 2.0，XML 双向映射层是纯开销 + bpmn.io 水印条款）；条件出口由 rules[].to 渲染（expr 标注），edges 仅存 START/APPROVAL 顺序流，部署前规范化剥离死边；节点 x/y 随定义 JSON 原样存储（引擎忽略未知字段，集成测试钉住契约）

## 下一步（按优先级）

1. **v1.12.0 候选**：无在途功能。Nacos Harness（#93 已常开 CI）、BROKEN 可观测性（#92）、设计器浏览器验证（#94 + 现场验证）三项 v1.11.0 候选已全部交付；剩余大项见下
2. **单进程 mono 模式（9 合 1 JVM，-2GB 大项）/ H2 文件库 dev 模式**：需独立设计刀（跨服务内部 HTTP 指向 localhost:808x，需转发/客户端改造；H2 多进程共享 AUTO_SERVER Windows 不稳 + DDL 生成器 PG 方言，见 §8.3）
3. **连接器与行业模板包**：需外部场景输入
4. **Milvus/Neo4j/ES**：架构文档路线项，随规模引入

### v1.11.0 冒烟证据快照（2026-09-05）

- **网关链路**：login→JWT→注册表 8/8 ENABLED→动态路由 10/10 前缀穿透→业务数据回流（parts/docs/projects/changes/knowledge/workflow/meta 直连断言；boms/part-categories 为 POST-only API 形状，链路同样穿透）；`/actuator/module-routes` 现场返回 `brokenModules:[]`（#92 生效）
- **浏览器级**：登录→注册表驱动菜单 8 模块→工作台真实计数→设计器画布（#94 缺陷发现+修复+现场复验三点坐标断言）。注意：IAB 自动化的 Playwright locator click 与 CUA 坐标 click 均不触发 React 合成事件——用 `evaluate` 程序化 `.click()` 可靠
- **AI 网关（离线模式）**：healthz llm_online=false / chat 降级提示 / sql validate LIMIT 强制+表白名单 / doc-parse degraded 规则抽取 / NL2SQL 按设计要求在线配置
- **CDS 校准**：业务服务 A/B 收益仅 ~2-4%（详见画像 §8.3），gateway -36% 为无 DB 特例

## 工程约定（全程遵守，遇新坑追加）

1. feature 分支 → PR 到 dev → CI 三语言全绿 → squash 合并；release PR → main（merge commit）→ tag → GitHub Release → 回灌 dev
2. 每刀 `mvn verify` 全绿 + 相关测试；Testcontainers 不可注释
3. 本机 JAVA_HOME 指向 JDK 8，构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"`
4. 运行中 JVM 锁 jar——重打包前先停进程
5. **性能自检**：PR 模板合并门强制（内存上界/聚合下推/默认值显式/调度格式/环境画像），详见 docs/OpenForge-性能与容量画像.md §5
6. Windows 注意：WSL2 `.wslconfig` **默认 2GB**（仅 PG；extras/rocketmq/nacos 场景 4GB，模板有注）；Docker Desktop 闪退（wsl.exe 0xc00000fd 栈溢出）处置=完整杀进程（Docker Desktop/com.docker.backend）后重启，必要时 `wsl --shutdown` 先行
7. **文档断言「已落地」必须以 diff 为准**（#62 教训：commit message 称服务 JVM 已加 SerialGC/Xss512k，实际只落 MAVEN_OPTS，服务 JVM 跑了三版默认 G1——#86 才实装，见性能画像 §8.2）
8. **合并门前必须有真实网关链路冒烟**（#90 教训：MockMvc/Testcontainers 直连测不出网关动态路由/注册表链路缺陷——动态路由自 A4 交付以来从未真实生效，直到 #90 首次全链路冒烟才暴露；凡动网关/模块注册/路由，冒烟为合并门强制环节）
8. GitHub 间歇 502/startup_failure：空提交重触发 / close+reopen / 等待平台恢复；stacked PR 基分支被删连坐关闭 → rebase + 重建 PR
9. **前端交付合并门 = 浏览器级真实打开**（#94 教训：设计器只读预览自 #82 交付以来从未被真实打开——构建绿 + locator 断言测不出"节点全叠原点"这类视觉缺陷；自动化时 React 合成事件对 locator/CUA click 无响应，用 `evaluate` 程序化 `.click()`）

## 已知技术债 / 遗留

| 项 | 说明 |
|----|------|
| Nacos 回路测试 Harness | **已清偿** #93——publish 读可见性竞态实锤（退避重试），CI NACOS_LOOP_TEST 常开合并门（容器模式固定端口） |
| optional:nacos import 副作用 | **已清偿** #93——8 服务 test yml import 默认翻空（blank 才彻底跳过 loader），运行时由 dev-up NACOS_CONFIG_IMPORT="" 承担 |
| Grafana 看板告警规则 | 看板模板已内置，告警规则/通知渠道随部署环境补 |
| outbox P3 | 事件 Schema 注册与版本兼容治理 |
| 动态元数据 TTL 缓存 | **已清偿** #84——PublishedMetaCache（租户键/TTL 30s/500 上界/afterCommit 驱逐） |
| 日志表保留期清理 | **已清偿** #84——LogRetentionJob（180 天可配/每日 03:30/分批 500 选删） |
| bpmn-js 流程设计器 | **已交付** #82——自研 SVG 画布实现（非 bpmn-js 库，决策见架构决策 6） |
| SkyWalking | 随规模引入（agent ~100MB 开销） |
