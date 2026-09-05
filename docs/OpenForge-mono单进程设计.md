# OpenForge mono 单进程模式设计（9 合 1 → mono-8 + 独立 gateway）

> 状态：**设计刀 v1（待评审，未实施）** ｜ 提出背景：画像 §8.3 已评估暂缓项，v1.11.0 会话完成全栈冒烟与 CDS 校准后重启评估
> 调研基线：v1.11.0（dev 8ef2449），跨服务调用 13 处 RestClient 调用点全量核对

## 1. 目标与非目标

**目标**
- 本地开发/演示场景内存再降 ~1GB（9 服务 JVM ~1.75GB → mono-8 + gateway 合计目标 ≤ 1GB）
- Hikari 连接 9×5=45 → 5（当前注释自述逼近 PG max_connections=100）
- `PROFILE=mono` 一键切换，与 full/core 并存；CI 全绿不回退

**非目标**
- 生产部署拓扑变更（生产本就应容器化按服务伸缩，mono 仅服务本地与资源受限场景）
- ai-gateway（Python uvicorn:8001）进程内托管——无机制无收益，恒留进程外
- H2 文件库零 Docker 模式（§8.3 已论证 Windows 不稳 + pgvector 语义丢失，维持备选不动）

## 2. 关键调研结论（设计依据）

| # | 事实 | 来源 |
|---|------|------|
| 1 | 9 服务共享同一 PG 库 openforge；flyway 历史表按服务隔离（`flyway_<svc>_history`），仅 auth 未配 table（用默认 `flyway_schema_history`，与其描述符 `flyway_auth_history` 声明不一致——mono 需顺手修正） | 各 application.yml |
| 2 | 8 个 jar 各自持有同名根资源 `db/migration/*` 与 `openforge-module.yml`——单 classpath 只见第一个，**naive 合 jar 必然只迁移/注册一个模块** | ModuleRegistrar.java:49 等 |
| 3 | `NumberClient` ×4 同名 `@Component`（material/doc/project/change）——合并启动即 bean 冲突 | change/doc/material/project 各 client |
| 4 | auth 版（库直查）与 security 版（HTTP 客户端）两套 `PermissionInterceptor` 均挂 `/api/**`——mono 全包扫描后会双重鉴权 | auth WebMvcConfig:39-50 + OpenForgeSecurityAutoConfiguration:29-38 |
| 5 | **gateway（spring-cloud-gateway/WebFlux/Netty）与业务服务（starter-web/Tomcat）无法共存于一个 Boot 上下文**——自动配置互斥，"双 server 双端口"无框架支持 | gateway pom:32 + Boot 语义 |
| 6 | 内部调用全部为同步 RestClient（13 处），对端恒带 `X-Internal-Token`；业务→auth 的直连调用今天就不经网关，mono 换端口后 servlet 链语义不变 | 调用矩阵 §A |
| 7 | B2 唯一消费者 KnowledgeEventConsumer.handle 本就直调 `knowledgeService.create()`；EVENT_ENABLED=false 时仅 MetaPublishService 有同步回退——mono 下天然进程内化 | KnowledgeEventConsumer.java:151-174 |
| 8 | outbox relay 多服务同表扫描靠 `UPDATE...WHERE id` 幂等兜底——mono 单实例反而消除该模式 | EventOutboxRelay |

## 3. 方案选型

### 3.1 gateway 不并入（决策 1）

**选定：mono-8（auth + 7 业务服务合一）+ gateway 保持独立 JVM（:8080）。**

- 依据事实 #5：并入 gateway 要么放弃 WebMVC（业务全灭）要么手工装配双 server（非常规、AuthGlobalFilter 信任头模型全部重写）。gateway 是最小的 JVM（实测 174MB），为它承担全部架构风险不成比例。
- gateway 的动态路由在 mono 下退化为**多条前缀 → 同一 upstream `http://localhost:8090`**，路由/自检/DEGRADED 机制原样保留，模块注册表机制原样保留（这正是冒烟与二开指南承诺的行为面）。

### 3.2 阶段化：先骨架（零客户端改造），后进程内直调（优化刀）

**刀 1（骨架）**：新聚合模块 `openforge-mono`
- 启动类 `@SpringBootApplication(scanBasePackages = "com.openforge")`，servlet 栈，端口 8090（`MONO_PORT` 可覆盖）
- **资源目录化**（解事实 #2）：
  - 各服务 `db/migration/` → `db/migration/<svc>/`（纯移动，服务自身测试同步改 locations）
  - `openforge-module.yml` → `module/<svc>.yml`，`ModuleRegistrar` 改为按目录枚举多描述符（每服务一个 Registrar 实例，仍各带 60s 心跳）
- **多 Flyway 实例**（解事实 #1/#2）：`@Configuration` 为每个 svc 建 `Flyway` bean（`locations=classpath:db/migration/<svc>`，`table=<现状表名>`，auth 显式 `flyway_auth_history` 并修正描述符）
- **bean 冲突修复**（解事实 #3）：4 个 `NumberClient` 收敛——抽取为 auth 内部 `NumberService` 的进程内调用（见刀 2）或最小改动 `@Component("materialNumberClient")` 等显式命名。刀 1 取显式命名（零行为变更），刀 2 再收敛
- **鉴权去重**（解事实 #4）：mono profile 排除 auth 版 WebMvcConfig 注册（`@Profile("!mono")` 或条件化），统一走 security 自动配置拦截器；auth 控制器域由后者覆盖
- **调用面零改造**：13 处 RestClient 的 base-url 全部指 `http://localhost:8090`（AUTH_SERVICE_URI/WORKFLOW_SERVICE_URI/knowledge-base-url 环境变量注入，Tomcat 回环）——行为面与今天 8 进程拓扑完全同构（事实 #6）
- `spring.application.name=openforge-mono`（EventPublisher.producerName 统一，可接受；描述符级 moduleKey 不受影响）
- dev-up.sh：`PROFILE=mono` = gateway + mono 两进程；CDS 训练走既有 `jarmode=tools` 路径
- 测试：15 模块既有测试不动（各服务仍以独立 Application 类跑 H2 上下文）；新增 mono 冒烟集成测试（起 mono 上下文 → 单端口逐域断言）；CI 增 openforge-mono 模块

**刀 2（进程内直调，优化）**：按依赖方向以 `@Profile("mono")` 条件 bean 替换 HTTP 客户端
- `PermissionQueryClient`/`ModuleAvailabilityClient` → auth 库直查版移植（auth 已有先例实现）
- 4×`NumberClient` → auth `NumberService` 直调
- change `WorkflowClient` → `WorkflowEngine` 直调
- `MetaPublishService.syncSchemaItem` 回退 → `KnowledgeService.create` 直调（事实 #7，消费者逻辑现成）
- ModuleRouteRefresher 仍在 gateway 进程内（HTTP 拉 mono 的 /internal/modules，不变）
- 预期再省每请求一次回环反序列化；刀 1 上线后按画像 §5 自检评估是否值得

### 3.3 备选已否决

| 备选 | 否决原因 |
|------|---------|
| gateway 并入 mono（双 server / 纯 WebFlux） | 事实 #5，信任头模型重写，风险/收益不成比例 |
| 合并全部 flyway 迁移到单一目录 | V1 冲突（8 套 V1 语义各异），历史表语义破坏，回滚困难 |
| `allow-bean-definition-overriding=true` 硬过冲突 | 掩盖问题：4 个 NumberClient 语义各自独立，静默覆盖 = 3 个服务的取号走错实现 |

## 4. 预期收益与验证标准

| 维度 | 现状（full） | mono 目标 | 验证方式 |
|------|-------------|----------|---------|
| JVM 进程 | 9（1.75GB RSS 实测） | 2（mono+gateway，目标 ≤1GB） | `Get-Process java` RSS |
| Hikari 连接 | 45 | 5 | 启动日志 |
| 启动 wall（含构建跳过） | ~1.5-2min | ~1min | dev-up 计时 |
| 启动 wall（mono 场景 CDS A/B） | — | 如实补录 | 画像 §8.3 惯例 |
| 网关链路冒烟 | 8/8 域 | 8/8 域（PROFILE=mono 复测） | curl 断言 |
| CI | 三语言绿 | 三语言绿 + mono 模块 | verify |

## 5. 风险与回退

1. **资源目录化是一次性大移动**（8 模块 migration 目录 + 描述符）：独立 PR、纯移动不改内容、逐模块 verify 护航；出错回退 = revert 单 PR
2. **双鉴权拦截器语义差异**：auth 控制器（用户/角色/组织管理）改走 security 拦截器后，权限点行为需冒烟覆盖（登录后管理页全走一遍）——列入刀 1 冒烟清单
3. **mono 仅本地场景**：生产不切换，无线上风险；PROFILE 未选 mono 时一切如旧（现有 15 模块测试即回归证明）

## 6. 工作量估计

- 刀 1（骨架）：1 个完整会话（资源目录化 + 多 Flyway/Registrar + 冲突修复 + dev-up/CI 集成 + mono 冒烟 + PROFILE=mono 全栈实测绘进入册）
- 刀 2（直调优化）：半个会话（4 组客户端替换 + 逐域冒烟）
- 前置：无。可随时开工
