# OpenForge 性能与容量画像

> 内存/性能审查结论与调优基线 ｜ v1.0 ｜ 2026-08-29 ｜ 触发：本地拉起全套服务时反复 JVM 闪退
> 适用环境：Windows 开发机（16GB 主机画像）与 Linux（CI / 容器生产）分别给出基线

---

## 1. 闪退根因（已实证）

`hs_err` 三类签名全部指向 **Windows 提交内存（commit charge）耗尽**：

| 签名 | 含义 |
|------|------|
| `malloc failed to allocate ... AllocateHeap` | JVM 堆扩容提交失败 |
| `mmap failed to map ... G1 virtual space` | G1 预留虚拟空间失败（小堆下 G1 原生开销占比反而高） |
| `cygheap read copy failed / fork: Resource temporarily unavailable` | 系统级：连 Git Bash 都 fork 不出来 |

**内存模型（16GB Windows 主机）**：

| 消耗方 | 默认行为 | 占用 |
|--------|----------|------|
| Docker Desktop WSL2 | 不限流时吃 **50% 物理内存** | ~8GB |
| 9 个 JVM 服务 | 各 ~400MB RSS（堆+元空间+线程栈+GC 原生） | ~3.6GB |
| Maven 多模块构建 | MAVEN_OPTS 未设 → 吃 1/4 物理内存 + surefire fork | 峰值 2~3GB |
| Vite dev / node | — | 0.5~1GB |
| Windows 本体 + 常驻 | — | 3~4GB |
| **合计** | | **17~19GB > 16GB + pagefile 余量** → 必然闪退 |

## 2. 调优基线（已落地）

### 2.1 开发机（Windows，16GB 画像）

1. **WSL2 限流**：`scripts/.wslconfig.template` → `%UserProfile%\.wslconfig`（默认仅 PG 场景 memory=2GB；启用 extras/rocketmq/nacos 时 4GB）+ autoMemoryReclaim，`wsl --shutdown` 后生效；
2. **JVM 参数瘦身**（dev-up.sh）：`-XX:+UseSerialGC`（小堆下原生开销比 G1 少 50~150MB/服务）、`-Xss512k`（默认 1MB × 每服务数十线程）、`-XX:MaxDirectMemorySize=64m`、元空间上限 256m→200m——**v1.9.x 实装**（#62 曾误只在 MAVEN_OPTS 生效，服务 JVM 一直跑默认 G1，详见 §8）；
3. **构建期限流 + 智能跳过**：`MAVEN_OPTS=-Xmx512m`；dev-up 默认按源码新旧自动跳过构建（原每次全量 `clean package`，构建峰值曾是闪退直接诱因）；`SKIP_BUILD=1` 强制复用；
4. **线程/连接显式化**：Tomcat `threads.max: 20`（默认 200）+ Hikari `maximum-pool-size: 5`（默认 10），已环境变量化（`TOMCAT_MAX_THREADS`/`HIKARI_MAX_POOL` 等，默认值不变）——dev-up 传 10/2（9×2=18 PG 连接，单人开发足够）；除网关（WebFlux/Netty，无 Tomcat/数据源）；
5. **启动集瘦身**：dev-up `PROFILE=core|lite|full` 预设（机器吃紧时的第一入口；A4 模块注册：不启动的服务不注册不路由）。

### 2.2 Linux / CI / 容器生产

| 项 | 基线 | 依据 |
|----|------|------|
| CI（ubuntu, 7GB） | 全量 `mvn verify` 现状即可 | 15 模块 4 分钟，无容器叠加 |
| 容器 JVM | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`（backend/Dockerfile 已设） | cgroup 感知，勿再叠加固定 -Xmx |
| 数据库连接 | 每副本 Hikari ≤5；副本数 × 5 + 运维连接 < PG `max_connections`（默认 100） | 多副本横向扩展时的第一道墙 |
| GC | 容器内堆 ≥1GB 保持 G1；开发机小堆才用 SerialGC | G1 在大堆吞吐更优 |
| SkyWalking/追踪 | 随规模引入（路线 B3 尾注） | agent 本身有 ~100MB 级开销 |
| Grafana 看板 | JVM/网关/事件总线两张模板随 provisioning 自动装载（#76） | 自定义指标：openforge_events_* 六计数 |

### 2.3 通用（两平台）

- 构建期勿同时运行全套服务（`SKIP_BUILD=1` 或先 `dev-down`）；
- Windows 特有：**运行中的 JVM 锁定 jar**（重打包前必须停进程，工程约定 #4）、页面文件余量检查（commit = RAM + pagefile）；
- Linux 特有：cgroup 限流下 JVM 参数交给 `MaxRAMPercentage`，避免固定值打架。

## 3. 代码级热点（审查结论与处置）

| # | 位置 | 问题 | 处置 |
|---|------|------|------|
| 1 | `PermissionQueryClient` 缓存 | 无上界 ConcurrentHashMap——长跑随用户数泄漏 | ✅ 已加 2048 上界（先清过期再全清，代价一轮回源） |
| 2 | `MetaObjectService.page` | 每次分页拉全量 `meta_field` 行到内存数数 | ✅ 聚合下推（`SELECT object_id, count(*) GROUP BY`） |
| 3 | `InMemoryVectorStore` | 向量全在内存（可插拔 M5 实现） | 记录：切换 pgvector/Milvus 时随租户标量过滤一并解决 |
| 4 | 前端单 bundle 2.4MB | echarts/antd 与业务代码同一 chunk | ✅ manualChunks 拆分：业务 74.7KB + vendor 独立缓存块 |
| 5 | `DynamicRecordService.loadPublished` | 每请求 2 次元数据查询 | 记录：建模量小暂可接受；发布频率低，可加带失效的 TTL 缓存（发布时 evict） |
| 6 | 日志/审计表无清理策略 | `sys_login_log`/`sys_audit_log` 无界增长 | 记录：F 生态迭代加保留期清理任务 |
| 7 | 事件总线（B2） | 发布路径同步发送最坏 3s 阻塞 | ✅ 熔断 60s + 可配超时；详见 §4 |
| 8 | 网关路由刷新 30s 轮询 | 9 服务心跳写放大可接受 | 现状保留；Nacos 推送化后轮询降级兜底（A4 设计 §7 已预留） |

## 4. 事件总线性能画像（B2 实施后新增）

| 维度 | 画像 | 优化已落地 / 建议 |
|------|------|------------------|
| 发布路径影响 | `EVENT_ENABLED=false`（默认）零开销（一次 boolean 判断）；`=true` 时每事件同步发送，内网 RTT 1~5ms，最坏阻塞 = send-timeout（默认 3s，可配 `openforge.event.send-timeout-millis`） | 熔断：连续 3 次失败后 60s 内 publish 快速返回 false 走回退——broker 停机不拖垮业务发布（#62 自检项） |
| 消费侧 | 逐条回调（consumeMessageBatchMaxSize=1），线程 2~5；幂等 = 每事件一次 `sys_event_consumed` INSERT（1:1 写放大，必要代价） | 当前事件频率（发布/记录写入/任务办理）远低于阈值；高吞吐场景调大批量 + 幂等批写（P2 outbox 一并） |
| 事件 payload | 信封 + 业务摘要 KB 级；schema.description 截断 2000 字符、record summary 截断 500 字符 | 已落地截断；payload 自包含（消费端不回读生产侧表——架构 6.3） |
| 内存 | producer/consumer 各 ~50~100MB native + 客户端堆占用；enabled=false 时客户端不创建 | lazy 单例；应用停机 shutdown 钩子回收 |
| 发射频率 | doc.released（检入）、change.closed（惰性终态）、task.created/completed（办理）均为低频人工动作；object.record.* = 动态记录写入频率 | 全部 EVENT_ENABLED=false 时为 no-op |
| 可观测 | published/sent/fallback/send_failed/duplicate/consumed 六个计数进 /actuator/prometheus；死信 %DLQ%openforge-knowledge | 积压/死信告警规则随 Grafana 看板补齐（路线 B3 尾注） |

**结论**：B2 在默认关闭下对现有系统零影响；启用后事件路径的代价集中在「发布路径最坏 3s 阻塞（有熔断兜底）」与「1:1 幂等写放大」，均在可接受区间，无进一步代码优化必要。

## 5. 快速自查命令

```bash
# Windows：提交内存水位（>85% 时勿再起服务/构建）
powershell "Get-Counter '\Memory\Committed Bytes','\Memory\Commit Limit'"
# 单服务真实 RSS
powershell "Get-Process java | Select-Object Id,@{n='RSS_MB';e={[int]($_.WorkingSet64/1MB)}}"
# JVM 原生内存明细（闪退取证时在启动参数临时追加）
-XX:NativeMemoryTracking=summary  +  jcmd <pid> VM.native_memory summary
```

---

## 6. 性能自检清单（开发 Loop 强制环节）

> 2026-08-29 起纳入 Loop 验证体系（MAS 文档 §5.2 V2/V3 层）：每刀 PR 自检一遍，
> 不通过不算完成。清单对应 §3 热点清单的反面——每个条目都来自真实踩坑或审查实锤。

### 设计期（写代码前回答四个问题）

- [ ] **内存**：新增的内存结构（Map/缓存/向量/列表聚合）是否有上界与过期策略？
      反面教材：`PermissionQueryClient` 无界缓存随用户数泄漏（#62 已修）。
- [ ] **数据库**：列表/统计是否把全表行拉进内存计算？（聚合一律下推 DB；
      反面教材：`MetaObjectService.page` 拉全量字段行数数，#62 已修）。
- [ ] **默认值**：线程池/连接池/GC 是否显式配置？（默认 Tomcat 200 线程、Hikari 10 连接、
      G1——小堆场景换 SerialGC。九服务叠加时任何"默认"都会被乘以 9。）
- [ ] **依赖**：新依赖是否最小化（starter 优先）？前端重依赖（图表/组件库）是否进 manualChunks？

### 实现期

- [ ] 新表带 tenant_id/审计四列/deleted；无租户语义的表登记 `TenantTables.GLOBAL_TABLES`
      （反面教材：`meta_form_layout` 漏登记导致租户拦截器误过滤，#50 构建暴露）。
- [ ] `@Scheduled` 值格式合法：毫秒数字或 ISO-8601 `PT30S`（**"30s" 非法**，网关启动闪退 #62）；
      心跳/轮询频率评估写放大（9 服务 × 频率 = 注册表写 QPS）。
- [ ] 容器内 JVM 用 `MaxRAMPercentage`，不与固定 `-Xmx` 打架；开发机小堆用 dev-up.sh 既有参数。
- [ ] 日志走 MDC traceId；新日志/审计表在本文档 §3 登记保留策略待办。

### 合并门（PR 描述勾选）

- [ ] `mvn verify` 全绿（15 模块）+ 相关测试；Testcontainers 未被注释/跳过
- [ ] 目标环境画像核对：Windows 16GB 开发机（`.wslconfig` 默认 2GB / extras 场景 4GB）/ Linux 容器（cgroup + MaxRAMPercentage=75）
- [ ] 若引入新基线（新服务/新依赖/新定时任务），本文档 §2/§3 已同步

## 7. 治理

- 本清单由 v1.3.0 后的本地全量拉起闪退事故催生（根因：WSL2 默认吃 50% 内存 + 9 JVM +
  无限流构建，提交内存耗尽），每个条目可溯源到 §1/§3 的实证。
- Loop 映射：设计期四问进 L1（计划自检）；实现期进 L2（特性验证器）；
  合并门进 L2/L3（评审清单 + 冒烟）。新增条目的流程：事故/审查实锤 → 本文档 §3 登记
  → 转为清单条目 → PR 模板同步。
- 已知记录待办：动态元数据 TTL 缓存、日志表保留期清理、向量库租户过滤（随 M5）、
  Redis/MinIO 已改 compose extras/nacos/rocketmq profile（#65），零代码使用不再随默认启动。

---

## 8. 本地开发瘦身（v1.9.x，触发：16GB 开发机带不动全套启动）

### 8.1 功能模块与资源画像

| 模块 | 职责 | fat jar | 关键依赖栈 | dev 必需性 |
|------|------|---------|-----------|-----------|
| openforge-auth | 认证/RBAC/租户/模块注册表/登录审计/日志保留清理 | 116MB | 全栈 + jjwt | **必需**（一切入口） |
| openforge-gateway | 动态路由/鉴权前置/TraceId | 55MB | WebFlux/Netty（无 DB） | **必需**（前端唯一入口） |
| openforge-metadata | 动态对象运行时：建模→发布→DDL/权限/AI 登记→CRUD | 112MB | 全栈 + flyway | core（二开主链路） |
| openforge-doc | 文档检入检出/事件发射 | 112MB | 全栈 | core（ECR 依赖） |
| openforge-workflow | 流程引擎/任务中心/可视化设计器后端 | 112MB | 全栈 + SpEL | core（审批链路） |
| openforge-material | 物料/BOM/状态机 | 112MB | 全栈 | full（物料场景） |
| openforge-change | ECR 变更闭环 | 112MB | 全栈 | full |
| openforge-knowledge | 知识库/向量检索（pgvector 可插拔）/自动沉淀 | 112MB | 全栈 + pgvector | full（AI 场景） |
| openforge-project | 项目任务/跨域统计 | 112MB | 全栈 | full |
| ai/（Python） | LLM 网关：解析管道/NL2SQL/助手 | — | fastapi | **可选**（仅 AI 功能） |
| frontend/ | React 18 + antd + echarts | — | Vite dev server | 必需（node ~0.5GB） |
| Docker | PostgreSQL（pgvector）唯一真依赖 | — | Redis/MinIO extras 零代码使用 | 必需（WSL2 内） |

结构事实：8 个业务服务各为 ~112MB fat jar——Spring Boot/rocketmq-client/netty/nacos-client/micrometer 全套**重复装载 8 份**；启动时各自独立 Spring 上下文 + flyway。这是「9 个 JVM 带不动」的结构根源，也是后续合并/共享化的收益空间。

### 8.2 已落地（零行为变更，dev-only；#1-6 = #86，#7-9 = #87）

| # | 项 | 内容 | 收益 |
|---|----|------|---------|
| 1 | 服务 JVM 调优**实装**（#86） | dev-up JVM_OPTS 补 `-XX:+UseSerialGC -Xss512k -XX:MaxDirectMemorySize=64m`、元空间 256→200m | 每服务省 50~150MB（G1 线程/卡表/预留），9 服务合计 **~0.7-1.3GB** |
| 2 | 构建智能跳过（#86） | 源码/pom 比 jar 旧 → 自动跳过 mvn（原每次全量 clean package，两个 hs_err 崩溃就是 Maven 自己） | 无改动启动省 **1~2 分钟 + ~1GB 峰值** |
| 3 | PROFILE 启动预设（#86） | `PROFILE=core`（auth/gateway/metadata/doc/workflow，5 服务）/ `lite`（2）/ `full`（9，默认） | core 较 full 少 4 个 JVM ≈ **-1.5GB**，主链路全覆盖 |
| 4 | 池/线程 dev 收紧（#86） | 8 服务 yml 环境变量化（默认不变），dev-up 传 Tomcat 10/2、Hikari 2/1 | PG 连接 45→18（WSL 内存同步降压），线程栈减半 |
| 5 | WSL2 上限 4→2GB（#86） | 默认 dev 仅 PG 一个容器，2GB 足够（模板已注明 extras 场景回 4GB） | **-2GB** 预留 |
| 6 | PG 就绪等待（#86） | dev-up 起 PG 后 `pg_isready` 轮询再启动服务 | 消除服务首轮 60s 健康等待窗浪费 |
| 7 | Nacos import 空载跳过（#87） | NACOS≠1 时 `NACOS_CONFIG_IMPORT=""`——`optional:nacos:` 即使 enabled=false 也激活 NacosConfigDataLoader（CI 实证每服务一次无效加载） | 每服务省 ~1s+ 并消除误导 WARN；**gateway 实测启动日志 0 条 Nacos 行** |
| 8 | AppCDS 类共享（#87，CDS=0 可关） | Boot 3.3 标准路径：`-Djarmode=tools` 解包到系统类路径（fat jar 的 LaunchedClassLoader 类不入档）→ `onRefresh` 训练（PG 已就绪）→ `SharedArchiveFile` 启动；jar 比解包目录新即自动重训；任何一步失败静默降级普通启动 | **gateway 实测：启动 4711→3022ms（-36%）、3173 类共享加载**；业务服务上下文更大收益更高。代价：target/cds ~70MB/服务磁盘（mvn clean 可清）+ 首次训练 ~17s/服务（一次性） |
| 9 | 分批并行启动（#87，START_PARALLEL 默认 2） | auth 恒先行（注册中心依赖）、gateway 恒收尾（路由表拉取最稳）、业务服务 2 个一批等健康 | 瘦身后余量足够（瞬态 +~300MB），**启动总时长约 -40%** |

**历史教训（§2.1 修正）**：#62 的 commit message 与本文档曾声称服务 JVM 已加 SerialGC/-Xss512k，实际 diff 只改了 MAVEN_OPTS——服务 JVM 一直跑默认 G1（16 线程机每 JVM 的 G1 GC 线程组 + remembered sets 是小堆下的纯税）。文档断言「已落地」必须以 diff 为准，本次逐项核对后实装。

### 8.3 已评估、暂缓的方案

| 方案 | 预期收益 | 暂缓原因 |
|------|---------|---------|
| spring.main.lazy-initialization | 启动提速、按需 bean | **不可用**：模块注册（ModuleRegistrar 启动注册）、@Scheduled（outbox relay/日志清理/路由刷新）、事件监听均依赖启动期初始化——lazy 会让服务不注册、不路由 |
| 单进程 mono 模式（9 合 1 JVM） | 后端 ~3GB → ~1GB（最大单项） | 跨服务内部 HTTP（metadata→auth /internal、发布流水线、knowledge 沉淀）指向 localhost:808x，需端口内转发或客户端改造，侵入架构，需独立设计刀 |
| H2 文件库 dev 模式（零 Docker） | 免 WSL2 整层（-2GB） | flyway 迁移虽 H2 兼容（测试已证），但 H2 文件库多进程共享需 AUTO_SERVER（Windows 不稳）+ 动态 DDL 生成器输出 PG 方言 + 丢失 pgvector 语义；作为极端瘦身备选记录 |
| 业务服务 CDS 运行时实测 | 校准 §8.4 预算 | gateway（无 DB）已实测 -36%；业务服务训练需 PG 在跑，待 Docker 恢复后 dev-up 冒烟补数 |

### 8.4 瘦身后内存预算（16GB 机，full 9 服务 + 前端）

| 消耗方 | 瘦身前 | 瘦身后 |
|--------|--------|--------|
| Windows 本体 + 常驻 | ~5GB | ~5GB |
| WSL2（Docker+PG） | 4GB 预留 | 2GB 预留 |
| 9 服务 JVM（SerialGC+Xss512k+直存封顶+CDS） | ~4.5-5.5GB | ~3.2-4GB（gateway 实测 174MB） |
| Vite dev + node | ~0.5GB | ~0.5GB |
| 构建（无改动启动已跳过） | 每次启动 +1~1.5GB 峰值 | 0（有改动才建） |
| **合计（稳态）** | **14-16GB+（必崩）** | **~11-12GB（余量 3-4GB）** |
| PROFILE=core 稳态 | — | **~9-10GB（余量 5GB+）** |

**运行时实测（2026-09-04 首次真实全链路冒烟，v1.9.0 + 瘦身两刀）**：9 服务 RSS 合计 **1.86GB**
（单服务 188~211MB——SerialGC+Xss512k 直存封顶后单服务较原 G1 画像 400MB+ 减半以上），
预算表 3.2-4GB 偏保守，实际余量更大。auth 修复重启后 8 服务 CDS 档案启动全程生效。

启动时长（gateway 实测样本）：串行 9 服务基线 ~3.5-4 分钟 → 无改动跳过构建 + CDS（-36%/服务）+ 2 并发分批 ≈ **~1.5-2 分钟**；首次运行附加一次性训练 ~2.5-3 分钟。

### 8.5 模块启动成本构成（代码级取证，#87）

每个业务服务（~112MB fat jar）启动时各自装载：Tomcat + actuator/micrometer（OTLP+prometheus 双注册）+ MyBatis-Plus + flyway + springdoc/swagger-ui（启动即扫描全部 controller 建 OpenAPI 模型）+ nacos config/discovery 客户端 + rocketmq 客户端类（producer **lazy 实证**：volatile 双检锁首用才建，默认零开销）。gateway 为 WebFlux/Netty 无数据源，模块路由表从 auth HTTP 拉取（30s 轮询）。跨服务调用全部为 localhost:808x 直连 HTTP（AUTH_SERVICE_URI / openforge.module.service-uri / knowledge、ai base-url），是 mono 合并的结构障碍（§8.3）。
