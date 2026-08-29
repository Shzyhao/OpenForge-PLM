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

1. **WSL2 限流**：`scripts/.wslconfig.template` → `%UserProfile%\.wslconfig`（memory=4GB + autoMemoryReclaim），`wsl --shutdown` 后生效——单项省 ~4GB；
2. **JVM 参数瘦身**（dev-up.sh）：`-XX:+UseSerialGC`（小堆下原生开销比 G1 少 50~150MB/服务）、`-Xss512k`（默认 1MB × 每服务数十线程）、元空间上限 256m→200m；
3. **构建期限流**：`MAVEN_OPTS=-Xmx512m` + `SKIP_BUILD=1` 复用现有 jar（jar 被运行中进程锁定需先停服务，见工程约定）；
4. **线程/连接显式化**：Tomcat `threads.max: 20`（默认 200）+ Hikari `maximum-pool-size: 5`（默认 10）——除网关（WebFlux/Netty，无 Tomcat/数据源）。

### 2.2 Linux / CI / 容器生产

| 项 | 基线 | 依据 |
|----|------|------|
| CI（ubuntu, 7GB） | 全量 `mvn verify` 现状即可 | 15 模块 4 分钟，无容器叠加 |
| 容器 JVM | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`（backend/Dockerfile 已设） | cgroup 感知，勿再叠加固定 -Xmx |
| 数据库连接 | 每副本 Hikari ≤5；副本数 × 5 + 运维连接 < PG `max_connections`（默认 100） | 多副本横向扩展时的第一道墙 |
| GC | 容器内堆 ≥1GB 保持 G1；开发机小堆才用 SerialGC | G1 在大堆吞吐更优 |
| SkyWalking/追踪 | 随规模引入（路线 B3 尾注） | agent 本身有 ~100MB 级开销 |

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
| 7 | 网关路由刷新 30s 轮询 | 9 服务心跳写放大可接受 | 现状保留；Nacos 推送化后轮询降级兜底（A4 设计 §7 已预留） |

## 4. 快速自查命令

```bash
# Windows：提交内存水位（>85% 时勿再起服务/构建）
powershell "Get-Counter '\Memory\Committed Bytes','\Memory\Commit Limit'"
# 单服务真实 RSS
powershell "Get-Process java | Select-Object Id,@{n='RSS_MB';e={[int]($_.WorkingSet64/1MB)}}"
# JVM 原生内存明细（闪退取证时在启动参数临时追加）
-XX:NativeMemoryTracking=summary  +  jcmd <pid> VM.native_memory summary
```

---

## 5. 性能自检清单（开发 Loop 强制环节）

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
- [ ] 目标环境画像核对：Windows 16GB 开发机（`.wslconfig` 限 4GB）/ Linux 容器（cgroup + MaxRAMPercentage=75）
- [ ] 若引入新基线（新服务/新依赖/新定时任务），本文档 §2/§3 已同步

## 6. 治理

- 本清单由 v1.3.0 后的本地全量拉起闪退事故催生（根因：WSL2 默认吃 50% 内存 + 9 JVM +
  无限流构建，提交内存耗尽），每个条目可溯源到 §1/§3 的实证。
- Loop 映射：设计期四问进 L1（计划自检）；实现期进 L2（特性验证器）；
  合并门进 L2/L3（评审清单 + 冒烟）。新增条目的流程：事故/审查实锤 → 本文档 §3 登记
  → 转为清单条目 → PR 模板同步。
- 已知记录待办：动态元数据 TTL 缓存、日志表保留期清理、向量库租户过滤（随 M5）、
  Redis/MinIO 在 compose 中改为可选 profile（当前零代码使用，纯占位）。
