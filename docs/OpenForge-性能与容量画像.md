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
