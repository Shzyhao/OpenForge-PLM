# OpenForge 二次开发指南

> 面向在 OpenForge 底座上构建自己系统的二开者 ｜ v1.1（按 v1.9.0 交付态校准）｜ 2026-09-03
> 上游：《OpenForge-架构文档》《OpenForge-框架化路线》《OpenForge-F2动态对象运行时设计》《OpenForge-F2模块注册机制设计》

---

## 1. 三种扩展路径（按成本从低到高）

| 路径 | 适用 | 你写什么 |
|------|------|----------|
| **动态对象** | 70% 长尾业务对象（台账/工装/模具…） | 界面建模 → 发布。零代码，自动获得 CRUD API + 物理表 + 权限点 + AI 可查 + 界面 |
| **模块注册** | 需要独立服务承载的重业务（专业领域逻辑） | 一个 Spring Boot 服务 + `openforge-module.yml`，部署即注册即路由 |
| **Pro-Code 扩展** | 深度领域逻辑（BOM 展开/流程编排…） | 参考 material/workflow 的写法，走 starter 三件套起步 |

## 2. 路径一：动态对象（零代码）

1. 前端「对象建模」页定义对象（objectKey/显示名/字段：STRING/NUMBER/DATE/BOOLEAN/REFERENCE）；
2. 「发布」——流水线自动：生成并执行 DDL（`dyn_` 前缀、只增不删）→ 创建四权限点
   `{objectKey}:view/create/update/delete`（绑 ADMINS）→ Schema 知识同步 → AI 表登记
   → 注册 EXTENSION 模块（路由/菜单/模块管理三面同构）；
3. 「动态数据」页即刻可用（按元数据渲染表格/表单）；「界面设计」页可定制字段序/可见/标签/列宽；
4. AI 侧：`sql/validate` 对 `SELECT * FROM dyn_xxx` 即刻 allowed（nl2sql 白名单自动登记）。

约束（安全红线）：objectKey/fieldKey 白名单 `^[a-z][a-z0-9_]{2,40}$` + 保留字黑名单；
已发布对象不可改（破坏性变更走新版本，本期不支持）；动态查询仅白名单字段
eq/like/in（like 仅 STRING），值参数化绑定。

## 3. 路径二：新服务（脚手架起步）

```bash
python scripts/openforge-cli.py new-service orders --port 8090 --display-name 订单
cd backend && mvn -pl openforge-orders -am verify          # 骨架含冒烟测试
SERVICES="auth gateway orders" ./scripts/dev-up.sh          # 裁剪启动
```

生成的骨架已经具备：starter 依赖（统一响应/权限/审计/多租户/模块注册）、
`openforge-module.yml`（部署即注册：网关自动路由、前端菜单自动出现）、
独立 Flyway 历史 + 样例迁移（tenant_id/审计列惯例）、H2 测试配置。

**必须遵守的模块契约**（`docs/OpenForge-F2模块注册机制设计.md`）：
- `moduleType`：KERNEL（不可停）/BUSINESS/AI/EXTENSION；
- `routes` 只能声明 `^/api/v1/[a-z0-9_/-]+$` 且**不得覆盖内核前缀**（`/api/v1/auth` 等）；
- `dependencies` 引用 moduleKey——依赖未启用时本模块 BROKEN（路由摘除）；
- 服务地址：本地默认描述符声明，容器/编排用 `MODULE_SERVICE_URI` 注入。

## 4. 路径三：Pro-Code 惯例（写代码前必读）

### 4.1 Starter 三件套

| 依赖 | 获得 |
|------|------|
| `openforge-starter-security` | 统一响应 `ApiResponse`、错误码、全局异常、`@RequirePermission` 拦截器、租户上下文、TraceId、共享日志格式 |
| `openforge-starter-data` | MyBatis-Plus（分页 + **多租户拦截器**）、PostgreSQL、Flyway、中央装配 |
| `openforge-starter-web` | 上者减 security（auth 自身用这个组合） |

### 4.2 平台惯例（违反会在评审/冒烟被抓）

- **表结构**：新表带 `tenant_id BIGINT NOT NULL DEFAULT 0` + 审计四列
  （created_by/created_at/updated_by/updated_at）+ `deleted SMALLINT`（软删）；
  无租户语义的从表要登记进 `TenantTables.GLOBAL_TABLES`（common）；
- **Flyway**：每服务独立历史表 `flyway_{svc}_history`；迁移只增不改（已应用的迁移
  禁止修改，纠偏用新版本 + `WHERE NOT EXISTS` 幂等写法，兼容 H2/PG）；
- **错误码**：进 `ErrorCode` 枚举分段（1xxx 参数/2xxx 认证权限/3xxx 业务规则/
  4xxx 资源状态/5xxx 系统）；HTTP 映射看 `GlobalExceptionHandler`；
- **权限**：controller 方法挂 `@RequirePermission("xxx:action")`；权限点在 auth
  加迁移播种并绑 ADMINS（注意 V14 起角色名是 **ADMINS**）或走内部接口
  `POST /api/v1/internal/permissions`（幂等）；
- **服务间调用**：直连（不经网关）+ `X-Internal-Token`；依赖他模块先
  `moduleAvailability.ensureAvailable(moduleKey)`（4022 明确语义）；
- **信任头**：只信网关注入的 `X-User-Id / X-User-Tenant / X-Trace-Id`
  （网关剥离外部伪造值）；
- **测试**：`mvn verify` 全绿是合并前提；容器测试不可注释（Docker 不可用自动跳过，
  CI 真实执行）；跨库 SQL 避免 `ON CONFLICT`（H2 不支持）。

### 4.3 可观测接入（零动作）

starter 已带 `/actuator/prometheus`（九服务 + 网关）与 TraceId 贯穿
（网关生成 → MDC → 日志 `[traceId]` 与响应体同值）。监控栈：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.monitoring.yml --profile monitoring up -d
# Prometheus :9090 / Grafana :3001（admin/openforge）
```

## 5. 多租户

- 租户经登录进入 JWT（`tenant` 声明）→ 网关 `X-User-Tenant` → SQL 行级自动过滤
  （MyBatis-Plus 拦截器 + 动态表显式条件）→ 文件按 `tenant/{id}/` 前缀隔离；
- 单租户/私有化部署：一切默认 0，行为与无租户版本一致；
- 管理：`/api/v1/tenants`（`tenant:manage`）主档/启停/用户归属调整（重新登录生效）；
- 向量检索租户隔离已交付（v1.7.0 #80：VectorStore 租户感知接口 + pgvector SQL 级过滤 +
  行级拦截器双保险）；AI 只读查询的租户过滤随查询通道演进补齐。

## 6. AI 能力接入

- **对话/文档解析**：经网关 `POST /api/v1/ai/chat|jobs/doc-parse`；LLM 未配置自动
  离线降级（不阻塞业务）；
- **自然语言查数**：`POST /api/v1/ai/data/query`（question 或直接 SQL）——一律过
  SQL 安全网关（SELECT only/表白名单/危险函数/LIMIT 强制）；动态对象发布即入白名单；
- **登记新表**：内部接口 `POST /internal/tables`（token 防护，不经网关路由）。

## 7. 部署

- **开发**：`./scripts/dev-up.sh`（PROFILE=core|lite|full 预设裁剪；SERVICES 精确指定；
  NACOS=1 启注册中心+配置中心；CDS=0 关闭类共享；无源码改动自动跳过构建——详见性能画像 §8）；
- **生产 compose**：`cp .env.example .env` → `docker compose -f docker-compose.prod.yml up -d --build`
  （required 密钥缺省拒绝启动；服务经 MODULE_SERVICE_URI 自动路由）；
- **K8s**：`deploy/helm/openforge`（values 清表驱动；helm lint 需在装有 helm 的环境执行）。

## 8. 升级与兼容

- 迁移版本只增不改；跨版本升级直接启动（Flyway 自动补齐）；
- 网关路由零手工：新模块上线自注册，旧模块停用 30s 内摘除；
- 旧 JWT 无租户声明按默认租户 0 兼容；前端菜单按注册表渲染，模块停用即隐藏
  （注册中心不可达回退全显）。

## 9. 常见排查

| 症状 | 定位 |
|------|------|
| 401 `缺少网关信任头` | 直连了服务端口——改走网关（或测试加 X-User-Id） |
| 403 `无操作权限` | 权限点未授角色；SUPER 免检可先验证链路 |
| 4022 `依赖模块未启用` | `/system/modules` 启用被依赖模块（或先停依赖方再操作） |
| 网关 404 新前缀 | 模块未注册/被停用；`GET /actuator/module-routes` 看自检详情，health DEGRADED 列缺失 |
| 动态对象发布失败 3012 | REFERENCE 指向的对象未发布——先发布被引对象 |
| H2 测试报列不存在 | 新表忘登记 `TenantTables.GLOBAL_TABLES`（无租户列表） |
