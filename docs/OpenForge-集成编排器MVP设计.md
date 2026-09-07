# OpenForge PLM 集成编排器 MVP 设计

> 版本：v0.2（评审意见已并入：①错误码 6xxx 段确认可用；②执行日志保留期 180 天；③设计器 MVP 即按画布骨架预留）｜ 状态：待开发启动 ｜ 关联：架构文档 5.1（⑦集成编排器）、8（集成架构）、二开指南（路径二新服务）
>
> 目标：把架构文档中规划但未落地的"集成编排器 + 连接器运行时"以 MVP 形态交付——业务人员可在界面上**低代码配置对外部系统的集成**（外部数据库只读查询、外部 REST API 调用），并发布为可调用的连接器。

---

## 目录

1. [背景与现状](#1-背景与现状)
2. [范围界定](#2-范围界定)
3. [总体设计](#3-总体设计)
4. [数据模型](#4-数据模型)
5. [连接器 SPI 与内置实现](#5-连接器-spi-与内置实现)
6. [API 设计](#6-api-设计)
7. [安全设计](#7-安全设计)
8. [前端：集成设计器页面](#8-前端集成设计器页面)
9. [模块注册与部署形态](#9-模块注册与部署形态)
10. [实施拆解（五刀）](#10-实施拆解五刀)
11. [测试与验收](#11-测试与验收)
12. [后续路线（P2/P3）](#12-后续路线p2p3)
13. [风险与开放问题](#13-风险与开放问题)

---

## 1. 背景与现状

### 1.1 需求来源

用户诉求三类"低代码可配置的外部对接"：

| 诉求 | 对应架构文档 8.1 集成模式 | MVP 覆盖 |
|------|--------------------------|----------|
| 查询其他系统的数据库（历史/中间表系统） | 数据库直连（只读） | ✅ JDBC 只读连接器 |
| 调用其他系统的接口（ERP 查库存、SSO 等） | 同步 API（网关路由 + 连接器） | ✅ HTTP/REST 连接器 |
| 配置 AI API（多模型、换 Key、加模型） | ——（AI 配置器，架构文档 5.1 预留） | ⏳ P2（本期只做地基约定） |

### 1.2 现状盘点（截至 v1.12.2 / dev #108）

- 七大设计器实际落地三个：对象建模器、表单/列表/布局设计器、流程设计器；规则/报表/**集成编排器**/AI 配置器未实现。
- 后端无任何连接器运行时代码；`MaterialClient`/`NumberClient` 等均为内部服务间调用。
- AI 中台（`ai/gateway/config.py`）模型接入为**环境变量注入、单模型**，无界面化管理。
- 元数据内核（F1~F3）已交付：版本化发布、发布广播、缓存刷新、模块注册、mono 聚合模式均为现成地基。

### 1.3 设计原则

1. **贴着已验证的机制做**：版本化发布仿 `MetaObject/MetaObjectVersion/MetaPublishService` 既有模式，事件经 `EventPublisher`（outbox），路由/菜单走 module.yml 注册——不发明新机制。
2. **安全先于易用**：凭据加密落库、出站域白名单、JDBC 只读白名单，任何一项缺失不交付。
3. **MVP 单步编排**：一次连接器调用 = 一个端点/一条 SQL。多步骤编排、字段映射脚本、画布设计器全部后移（见 §12），避免 MVP 面积失控。

---

## 2. 范围界定

### 2.1 MVP（本期交付）

- **HTTP/REST 连接器**（出站调用外部 API）：方法/URL/Header/Query 模板、认证（NONE/BASIC/BEARER/API_KEY Header）、参数化请求体模板、超时、重试（固定次数 + 固定退避）、响应原样透传。
- **JDBC 只读连接器**（查询外部数据库）：数据源配置、SQL 模板（命名参数）、表白名单、行数上限、超时、强制只读校验。驱动首版支持 PostgreSQL + MySQL。
- **连接器定义管理**：新建/编辑/删除/版本化发布/停用；发布后生成不可变版本快照。
- **凭据管理**：独立凭据库（密码/Key 加密存储，界面只写不读），连接器通过 `credRef` 引用。
- **测试调用面板**：设计时手动触发试运行，返回结果与耗时。
- **运行时调用 API**：发布后的连接器经网关以 `POST /api/v1/connectors/invoke/{connCode}` 调用（前端/其他系统可用）。
- **执行日志**：每次执行留痕（触发方式/结果/耗时/traceId），凭据与敏感 Header 强制脱敏。
- **集成设计器页面**：连接器列表 + **画布编辑视图**（复用自研 SVG 画布，MVP 单节点）+ 节点属性面板（表单向导）+ 测试面板 + 执行日志查看；交互框架按多步骤编排预留（见 §8）。
- **部署**：独立微服务 + mono 模式聚合 + compose + 冒烟断言扩展。

### 2.2 明确不做（后移）

| 项 | 后移原因 | 去向 |
|----|----------|------|
| 多步骤编排（步骤链/条件分支） | 单步已覆盖 80% 场景；画布交互框架已在 MVP 预留（§8），P3 仅扩展数据模型与执行引擎 | P3 |
| 字段映射/转换脚本（脚本沙箱） | 涉及沙箱安全，MVP 用 JSON 模板占位符替代 | P3 |
| 事件触发（订阅 RocketMQ 主题触发连接器） | 需要消费组管理与幂等设计 | P2 |
| 定时调度触发 | 需要调度器选型（Spring Scheduling 起步即可） | P2 |
| 飞书/企微/钉钉/邮件/SFTP 连接器 | 属于"内置连接器包"，架构同 HTTP 连接器 | P3 |
| 出站脱敏代理（与 AI 外呼同通道） | 依赖 AI 中台通道改造 | P3 |
| **AI API 配置器**（模型注册表/多模型/降级链） | 独立需求域，见 §12.1 专述 | P2 |
| 死信队列 + 人工重放界面 | MVP 同步调用无死信形态 | P2（随事件触发引入） |

---

## 3. 总体设计

### 3.1 服务归属决策：独立新服务 `openforge-connector`

**决策**：新建 `openforge-connector` 微服务承载连接器元数据 + 运行时，`moduleType: EXTENSION`，`dependencies: [auth, metadata]`。

**理由**：

- 元数据内核（`openforge-metadata`）是 KERNEL 不可停用；连接器运行时涉及**凭据存储、外部 IO、重试**，故障域与元数据内核隔离——外部系统抖动不应影响动态对象 CRUD。
- 连接器执行日志是无界增长数据（同登录日志先例，v1.9.0 清偿过类似技术债），独立服务便于独立治理保留期。
- 脚手架 `openforge-cli new-service` 已验证，接入成本 ≈ 0；mono 模式资源目录化（`module/<svc>.yml`、`db/migration/<svc>/`）有 8 组先例。

**权衡**：架构文档 5.2 目标态是"九类元数据统一登记在元数据中心"。MVP 阶段连接器定义**自持版本化**（独立表，仿 MetaObject 模式），不强注册进 KERNEL——代价是发布广播/兼容性检查逻辑重复一份，收益是 KERNEL 零侵入、独立演进。统一登记列为 P2 评估项（§12.3）。

### 3.2 架构图

```
┌────────────── 前端：集成设计器页面（/integration/connectors） ──────────────┐
│   连接器列表 │ 配置抽屉（HTTP/JDBC 表单向导） │ 测试面板 │ 执行日志        │
└──────────────────────────────┬────────────────────────────────────────────┘
                        网关（module.yml 自动路由 /api/v1/connectors）
┌──────────────────────────────▼────────────────────────────────────────────┐
│                    openforge-connector（EXTENSION，依赖 auth+metadata）     │
│  ┌───────────── 设计态 ─────────────┐  ┌──────────── 运行态 ────────────┐ │
│  │ 连接器定义 CRUD（conn:manage）    │  │ ConnectorRuntime 统一执行入口  │ │
│  │ 凭据管理（AES-GCM 密文落库）      │  │  → 按 connCode 路由到 SPI 实现 │ │
│  │ 发布（版本快照 + connector.published│  │  → 凭据解密（内存态，不落日志）│ │
│  │     事件广播 + 缓存 afterCommit 驱逐）│ │  → 出站白名单校验（SSRF 防护） │ │
│  │ PublishedConnCache（30s TTL 缓存）│  │  → 超时/重试/行数上限           │ │
│  └──────────────────────────────────┘  │  → conn_exec_log 留痕（脱敏）   │ │
│                                        └────────────┬───────────────────┘ │
│  ConnectorSpi（接口）：supports(type) / execute(spec, params, cred)        │
│    ├─ HttpRestConnector   （java.net.http.HttpClient）                     │
│    └─ JdbcReadonlyConnector（独立只读 DataSource，PG/MySQL 驱动）           │
└────────────────────────────────────────────────────────────────────────────┘
                     出站                                      出站
              ┌──────────▼──────────┐                 ┌─────────▼─────────┐
              │ 外部系统 REST API    │                 │ 外部数据库（只读） │
              └─────────────────────┘                 └───────────────────┘
```

### 3.3 核心流程

**设计→发布**（仿 MetaPublishService）：

1. 新建/编辑连接器，状态 `DRAFT`，spec 以 JSON 存 `conn_definition.spec_json`；
2. 点击发布 → 事务内：兼容性校验（凭据引用存在、URL/SQL 非空、白名单可解析）→ 生成不可变 `conn_definition_version` 快照 → 主档 `status=PUBLISHED, current_version=N+1` → 事务提交后 afterCommit：驱逐 PublishedConnCache + `eventPublisher.publish("openforge-connector", "connector.published", ...)`（未启用事件总线时仅缓存驱逐，行为等价——连接器缓存是本服务内存态，无跨服务消费者，MVP 广播仅作预留）；
3. 运行时按**已发布版本快照**执行，编辑中的 DRAFT 不影响在途调用；回滚 = 把主档指回旧版本号。

**运行时调用**：

```
POST /api/v1/connectors/invoke/{connCode}（@RequirePermission("conn:invoke")）
  → PublishedConnCache 取已发布 spec（未发布/停用 → 4xxx 明确错误码）
  → 参数按 parameterSchema 校验 → 模板渲染（{{param}} 占位替换，值做 HTML/SQL 转义兜底）
  → 出站白名单校验（HTTP host；JDBC 校验 allowedTables）
  → SPI execute（超时/重试/行数上限）
  → conn_exec_log 落库（异步）→ 返回统一 ApiResponse（data=外部响应原样/rows）
```

---

## 4. 数据模型

Flyway 独立历史表 `flyway_connector_history`，迁移目录 `db/migration/connector/`。全部表遵守平台惯例：`tenant_id BIGINT NOT NULL DEFAULT 0` + 审计四列 + `deleted SMALLINT`（凭据表与执行日志表登记 `TenantTables.GLOBAL_TABLES` 评估项——执行日志天然带 tenant_id，保留行级过滤；凭据表同样保留）。

```sql
-- V1__conn_definition.sql
CREATE TABLE conn_definition (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id       BIGINT NOT NULL DEFAULT 0,
  conn_code       VARCHAR(64)  NOT NULL,   -- 租户内唯一，^[a-z][a-z0-9_]{2,63}$
  conn_name       VARCHAR(128) NOT NULL,
  conn_type       VARCHAR(32)  NOT NULL,   -- HTTP_REST / JDBC_READONLY
  status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PUBLISHED/DISABLED
  current_version INT          NOT NULL DEFAULT 0,       -- 0=从未发布
  description     VARCHAR(512),
  spec_json       TEXT         NOT NULL,   -- 设计态 spec（见 4.1）
  created_by BIGINT, created_at TIMESTAMP DEFAULT now(),
  updated_by BIGINT, updated_at TIMESTAMP DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_conn_code UNIQUE (tenant_id, conn_code)
);

-- 不可变版本快照（发布时整份拷贝 spec_json）
CREATE TABLE conn_definition_version (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  conn_id   BIGINT NOT NULL,
  version   INT    NOT NULL,
  spec_json TEXT   NOT NULL,
  published_by BIGINT, published_at TIMESTAMP DEFAULT now(),
  CONSTRAINT uq_conn_ver UNIQUE (tenant_id, conn_id, version)
);

-- V2__conn_credential.sql  凭据独立管理（连接器 spec 以 cred_code 引用）
CREATE TABLE conn_credential (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  cred_code VARCHAR(64) NOT NULL,
  cred_name VARCHAR(128) NOT NULL,
  auth_type VARCHAR(32) NOT NULL,          -- BASIC/BEARER/API_KEY_HEADER/JDBC_PASSWORD
  secret_cipher TEXT NOT NULL,             -- AES-GCM 密文 base64(iv||ciphertext)
  extra_json TEXT,                          -- 如 API_KEY 的 headerName
  created_by BIGINT, created_at TIMESTAMP DEFAULT now(),
  updated_by BIGINT, updated_at TIMESTAMP DEFAULT now(),
  deleted SMALLINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_cred_code UNIQUE (tenant_id, cred_code)
);

-- V3__conn_exec_log.sql  执行日志（无界增长 → 保留期清理任务随刀2交付，默认 180 天，对齐登录日志惯例）
CREATE TABLE conn_exec_log (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  conn_id   BIGINT NOT NULL,
  conn_version INT NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,       -- MANUAL / API
  status     VARCHAR(16) NOT NULL,         -- SUCCESS/FAILED/TIMEOUT/BLOCKED
  http_status INT,                          -- HTTP 连接器
  rows_returned INT,                        -- JDBC 连接器
  duration_ms BIGINT NOT NULL,
  error_msg  VARCHAR(1024),                 -- 已脱敏（不含 URL query 中参数与响应体原文）
  trace_id   VARCHAR(64),
  created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_conn_exec_log_conn ON conn_exec_log (tenant_id, conn_id, created_at DESC);
```

### 4.1 spec_json 契约

spec_json 顶层携带 `"schemaVersion": 1`。MVP 为单步语义（字段直接置于顶层）；P3 多步骤编排升级为 `steps[]` + `edges[]`（schemaVersion: 2）时按版本字段兼容解析——v1 契约不变，无破坏性迁移。

**HTTP/REST**：

```json
{
  "schemaVersion": 1,
  "endpoint": {
    "method": "POST",
    "url": "https://erp.example.com/api/v1/inventory/query",
    "headers": { "Content-Type": "application/json" },
    "timeoutMs": 5000
  },
  "credentialRef": "cred_erp_main",
  "parameterSchema": {
    "type": "object",
    "properties": { "materialNumber": { "type": "string" } },
    "required": ["materialNumber"]
  },
  "requestTemplate": { "body": { "materialNumber": "{{materialNumber}}" } },
  "retry": { "maxAttempts": 3, "backoffMs": 500 }
}
```

**JDBC 只读**：

```json
{
  "schemaVersion": 1,
  "datasource": { "jdbcUrl": "jdbc:postgresql://legacy-mes:5432/mes", "username": "readonly_user" },
  "passwordRef": "cred_mes_db",
  "allowedTables": ["mes_stock", "mes_work_order"],
  "sqlTemplate": "SELECT item_code, qty FROM mes_stock WHERE item_code = :itemCode",
  "maxRows": 200,
  "timeoutMs": 5000
}
```

模板渲染只做**占位符替换**（`{{p}}` 注入请求体/Header/Query，`:p` 走 JDBC 绑定参数——JDBC 一律 PreparedStatement，禁止拼接），不支持表达式，堵死注入面。

---

## 5. 连接器 SPI 与内置实现

```java
public interface ConnectorSpi {
    String type();                                        // "HTTP_REST" / "JDBC_READONLY"
    void validate(ConnectorSpec spec);                    // 发布前兼容性校验
    ConnectorResult execute(ConnectorSpec spec,
                            Map<String, Object> params,
                            DecryptedCredential cred);    // 凭据解密后仅内存传递
}
```

| 实现 | 要点 |
|------|------|
| `HttpRestConnector` | Java 21 内置 `java.net.http.HttpClient`（零新依赖）；认证注入：BASIC/BEARER → Authorization 头，API_KEY_HEADER → `extra_json.headerName` 指定头；仅 `application/json` 与文本响应 MVP 支持；重试仅对 5xx/IO 异常生效（4xx 不重试）；响应体 >1MB 截断存储摘要 |
| `JdbcReadonlyConnector` | 每连接器独立 `DataSource`（HikariCP，池参数固定小值 max=2，空闲即回收）；启动时懒加载、失败不影响服务；SQL 静态校验：提取表名 ⊆ allowedTables、拒绝写关键字（INSERT/UPDATE/DELETE/DDL/MERGE/CALL 等）+ 连接层 `Connection.setReadOnly(true)` 双保险；行数上限硬截断并标注 `truncated: true` |

**凭据加密**：AES-256-GCM，主密钥环境变量 `OPENFORGE_CONNECTOR_MASTER_KEY`（Base64，32 字节）；未配置时**凭据管理接口直接拒绝创建**（不降级为明文——安全先于易用）。解密只发生在执行瞬间，异常栈与日志禁止携带。

**缓存**：`PublishedConnCache` 仿 `PublishedMetaCache`——Caffeine，`tenant:connCode` 键，TTL 30s + 发布 afterCommit 显式驱逐（元数据服务 v1.9.0 已验证该模式）。

---

## 6. API 设计

路由前缀 `/api/v1/connectors`、`/api/v1/connector-credentials`（module.yml `routes` 声明，网关自动生效）。统一 `ApiResponse` 包裹；错误码进 `ErrorCode` 枚举 **6xxx 段**（新增"集成域"分段，已确认未被占用）。

| 方法 | 路径 | 权限点 | 说明 |
|------|------|--------|------|
| GET/POST | `/api/v1/connectors` | `conn:manage` | 列表（分页，PageResponse 统一结构）/新建 |
| GET/PUT/DELETE | `/api/v1/connectors/{id}` | `conn:manage` | 详情（含 spec 与版本历史）/更新/删除（已发布需先停用） |
| POST | `/api/v1/connectors/{id}/publish` | `conn:manage` | 发布（生成版本快照） |
| POST | `/api/v1/connectors/{id}/disable` | `conn:manage` | 停用（在途 invoke 立即 4xxx） |
| POST | `/api/v1/connectors/{id}/test` | `conn:manage` | 设计时试运行（trigger_type=MANUAL，计费/日志同正式） |
| GET | `/api/v1/connectors/{id}/exec-logs` | `conn:manage` | 执行日志分页 |
| POST | `/api/v1/connectors/invoke/{connCode}` | `conn:invoke` | **运行时调用**（body=参数 JSON） |
| GET/POST | `/api/v1/connector-credentials` | `conn:manage` | 凭据管理（响应永不回显 secret） |

**权限播种**：auth 侧 Flyway 迁移 `V24__connector_permissions.sql` 播种 `conn:manage` / `conn:invoke` 并绑定 ADMINS（V10/V12/V17 既有先例，幂等写法防重；比内部接口播种少一次运行期依赖）。

**其他服务调用连接器**：直连 `openforge-connector` 服务地址 + `X-Internal-Token`（平台惯例），内部端点 `POST /internal/connector/invoke/{connCode}`，调用前 `moduleAvailability.ensureAvailable("connector")`。

---

## 7. 安全设计

| 风险面 | 措施 |
|--------|------|
| 凭据泄露 | AES-GCM 加密落库；API 响应永不回显；解密仅执行瞬间内存态；日志/异常/错误信息强制脱敏（Authorization、password 字段、JDBC URL 去参数） |
| SSRF | 出站域白名单 `OPENFORGE_CONNECTOR_EGRESS_WHITELIST`（逗号分隔 host:port，**未配置 = 拒绝一切出站**）；发布时校验 + 执行时二次校验；禁解析内网 IP 段（10/8、172.16/12、192.168/16、127/8、169.254/16，DNS 解析后逐一校验） |
| SQL 注入（JDBC 连接器） | PreparedStatement 绑定参数；SQL 静态解析提取表名 ⊆ 白名单；写操作关键字黑名单 + 连接只读标志双保险；`maxRows` 硬上限 |
| 越权 | 权限点拆分（manage/invoke）；租户行级隔离沿用 MyBatis-Plus 拦截器；invoke 按连接器授权（MVP：拥有 `conn:invoke` 即可调用本租户全部已发布连接器，按连接器的 ACL 授权列 P2） |
| 资源耗尽 | HTTP 超时上限 30s、响应体 1MB 上限；JDBC 超时上限 30s、行数上限 1000、连接池 max=2 |
| 审计 | 所有 manage 操作落操作审计（沿用 starter 审计拦截）；执行落 conn_exec_log（含 traceId） |

---

## 8. 前端：集成设计器页面

新页面 `IntegrationPage.tsx`（路由 `/integration/connectors`，菜单"集成编排器"，icon `ApiOutlined`，经 module.yml 菜单自动出现，权限点控制入口可见性）。

**形态决策：MVP 即按"画布骨架"搭建，为多步骤编排预留交互框架**（评审确认：后续编排可能复杂）。复用流程设计器的自研 SVG 画布组件（`frontend/src/flow/`，v1.8.0 资产，零依赖）：MVP 一个连接器 = 画布上一个节点（带输入/输出锚点占位），属性面板承载全部配置表单。P3 引入多步骤时，steps/edges 数据模型与节点连线交互在既有画布上**增量演进，交互框架零重做**。

页面结构（Ant Design，参照 `WorkflowDefsPage` 的"定义列表 + 画布编辑"模式）：

1. **连接器列表**（默认视图）：Code/名称/类型/状态（DRAFT/PUBLISHED/DISABLED Tag）/版本/更新时间/操作（编辑→进入画布视图、发布、停用、测试、日志）；
2. **画布编辑视图**（新建/编辑）：
   - 中央画布：MVP 渲染单节点 + 输入/输出锚点占位，节点可拖拽定位；
   - 右侧属性面板（`NodeConfigPanel` 组件，关键预留点）：分步表单——基础信息 → 连接配置（URL+认证引用 或 JDBC URL+凭据）→ 参数定义（动态键值对生成 parameterSchema）→ 模板（HTTP body 模板编辑器 / SQL 模板编辑器 + 白名单表）；凭据下拉选择（凭据单独 Tab 管理：新建弹窗，密码框 write-only）；
   - 顶部工具栏：保存、发布、测试（唤起测试面板）、返回列表；
3. **测试面板**：参数输入 → 调用 `/test` → 展示状态/耗时/响应（JSON 格式化）/错误信息；
4. **执行日志抽屉**：分页表格（时间/触发/状态/耗时/traceId），行内展开看错误摘要。

**降级路径**：刀3 首日做 `flow/` 画布组件复用可行性 spike——若泛化改造成本 >1.5 天（节点类型/锚点语义强绑定流程域），则画布骨架退回 P3 一次性引入，MVP 降级为纯列表+抽屉；`NodeConfigPanel` 组件化与 spec 的 `schemaVersion` 契约不受影响，P3 演进仍然平滑。

API 层新增 `frontend/src/api/connector.ts`（仿 `metadata.ts`，经 `client.ts` 统一封装）。

---

## 9. 模块注册与部署形态

### 9.1 module.yml（`backend/openforge-connector/src/main/resources/module/connector.yml`）

```yaml
moduleKey: connector
moduleType: EXTENSION
displayName: 集成编排器
version: 0.1.0
routes:
  - /api/v1/connectors
  - /api/v1/connector-credentials
dependencies: [auth]     # 实现修正：连接器不消费 metadata，如实声明（原设计 [auth, metadata]）
menu:
  - { path: /integration/connectors, title: 集成编排器, icon: ApiOutlined }
flyway:
  historyTable: flyway_connector_history
serviceUri: 8094
```

### 9.2 部署面改动清单

| 项 | 改动 |
|----|------|
| `openforge-mono/pom.xml` | 增加 `openforge-connector` 依赖（聚合为第 9 个业务模块）；资源目录化遵循先例：`module/connector.yml`、`db/migration/connector/` 防同名根遮蔽 |
| `docker-compose.yml` / `docker-compose.prod.yml` | 增加 connector 服务（独立模式）+ 相关环境变量（MASTER_KEY、EGRESS_WHITELIST） |
| `scripts/dev-up.sh` PROFILE 预设 | full/mono 预设加入 connector |
| `scripts/smoke.sh` | 新增连接器域断言：module-routes 含 connector 路由、凭据创建（加密生效）、连接器发布、mock 端点调用成功、白名单外 URL 被拒（BLOCKED） |
| 网关 | 零改动（module 注册自动路由） |
| 前端构建 | 零改动（新页面进常规构建） |

新增环境变量汇总：`OPENFORGE_CONNECTOR_MASTER_KEY`（必填，凭据功能开关）、`OPENFORGE_CONNECTOR_EGRESS_WHITELIST`（默认空=全拒）。

---

## 10. 实施拆解（五刀）

> 每刀独立可合并、`mvn verify` 全绿 + 冒烟不回退为合并门（约定 #8）。
>
> **刀1~刀5 全部完成 + 分析优化轮（2026-09-06，工作区未提交）**：四表合并为单个 `V1__init_connector.sql`（服务未在任何环境运行，无历史包袱）；connector 33 测试 + auth 66 测试 + MonoSmokeTest 3/3 全绿（PG/MySQL 容器测试就位，本机无 Docker 自动跳过、CI 真实执行）；脚手架缺口 `@MapperScan` 与 Testcontainers 依赖已补；两处实现偏差（dependencies 仅 [auth]；权限播种走 auth V24/V25 迁移而非内部接口）；mono 聚合为第 9 模块（MonoFlywayConfig/MonoModuleRegistrarsConfig 各 +1 实例）；smoke.sh 扩至 9 业务域 + 连接器 6 断言；README/二开指南/架构文档已同步。
>
> **优化轮修复**：①运行时 invoke 凭据双查收敛为单查（解析期跳过存在性预检，执行期 CONN_CRED_NOT_FOUND 承接）；②CredentialService 复用注入 ObjectMapper（原每次 resolveByCode new 实例）；③前端配置面板 JSON 非法输入从静默回退改为显式报错；④exec-logs API 补集成测试断言（分页结构 + 脱敏错误信息）；⑤SSRF TOCTOU（DNS 重绑定）窗口与凭据池 key 摘要入册风险表（R6/R7，根治随 P2 出站代理）。
>
> **提交前验证轮（2026-09-07，真实栈）**：全量 mvn verify 202 测试绿 + smoke.sh 20/20（连跑两遍，第二遍验证凭据幂等）+ 浏览器级巡检（登录→菜单→新建→试运行 SUCCESS/HTTP 200→执行日志，see-coder 视觉验收 5 项全过）。**验证轮揪出并修复三个交付缺陷**：①connector 模块类型误用 EXTENSION（本仓 EXTENSION 专指动态对象扩展、须 ownerRef 指向 meta_object.id）→ 改 BUSINESS；②ModuleRegistrar 静默失败——auth 把业务拒绝包在 HTTP 200 ApiResponse 里，toBodilessEntity 不抛异常 → 改为解析业务码非 0 即 WARN（平台级修复）；③Windows/Git Bash 下 curl 内联中文被 ANSI 代码页转码致 JSON parse error → smoke.sh 含中文 payload 改走临时文件 --data-binary；另 dev-up.sh 补凭据主密钥开发默认值、dev-down.sh 补 Windows 进程兜底清理、脚手架生成的 module/service-uri 自引用占位符循环修正（对齐 metadata 纯字面量模式）。

| 刀 | 内容 | 涉及 | 预估 |
|----|------|------|------|
| **刀1：骨架 + HTTP 连接器** | `openforge-cli new-service connector` 起步；V1~V3 迁移（表结构）；`ConnectorSpi` + `HttpRestConnector`；连接器/凭据 CRUD + 错误码 6xxx；AES-GCM 加密器 + 主密钥装配；权限播种（internal/permissions）；单测 + Testcontainers PG 容器测试 | backend/openforge-connector（新）、openforge-common（ErrorCode 6xxx 段） | 3d |
| **刀2：版本化发布 + 运行时 + 安全** | publish/版本快照/disable；`PublishedConnCache` + afterCommit 驱逐 + `connector.published` 事件；`ConnectorRuntime` 统一执行（invoke 端点）；出站域白名单 + SSRF 内网校验；JDBC 连接器（PG/MySQL 驱动、白名单、只读双保险）；exec_log 保留期清理任务（180 天可配，对齐登录日志，仿 v1.9.0 日志清理）；内部 invoke 端点 | openforge-connector | 3d |
| **刀3：前端集成设计器** | 首日画布复用 spike（§8 降级路径）；`IntegrationPage.tsx`（列表视图 + 画布编辑视图骨架 + `NodeConfigPanel` 属性面板 + 测试面板/日志）+ `api/connector.ts` + 路由/菜单；凭据管理 Tab | frontend | 3.5d |
| **刀4：部署与冒烟** | mono 聚合 + compose + PROFILE + dev-up.sh；smoke.sh 连接器域 6 断言；浏览器级全页面巡检（约定 #9：分页结构等回归点） | mono/compose/scripts | 1.5d |
| **刀5：文档与收尾** | README 版本块（v1.14.0 候选）；二开指南补"连接器扩展"节（如何新增 ConnectorSpi 实现）；架构文档 8.2 状态标注（内置连接器两项已交付）；交接文档 | docs | 0.5d |

合计 ≈ 11.5 人日（单人不间断；并行可压至一周余）。画布骨架 spike 若触发降级路径，刀3 回落 2.5d、合计 10.5 人日。

---

## 11. 测试与验收

### 11.1 测试策略（沿用平台矩阵）

- 单测：模板渲染、SQL 静态校验、AES-GCM 往返、白名单命中/内网拒绝、错误码映射；
- Testcontainers PG 容器测试（CI 真实执行）：CRUD/发布/版本快照/invoke 全链路、租户隔离（双租户互不可见）、凭据密文断言（库里无明文）、exec_log 脱敏断言；
- HTTP 连接器对端：测试内起 JDK 内置 `com.sun.net.httpserver.HttpServer` mock（不引入新测试依赖）；
- JDBC 连接器对端：Testcontainers 起 MySQL 容器（若 CI 时长敏感，MySQL 用例打 `@DisabledIfDockerUnavailable` 同既有惯例）；
- 浏览器级巡检：集成设计器页面全流程（新建→凭据→发布→测试→日志）。

### 11.2 验收清单（逐条可演示）

1. 界面创建 HTTP 连接器（指向白名单内 mock 端点，BEARER 认证）→ 发布 → 测试面板调用成功，响应原样返回；
2. 界面创建 JDBC 只读连接器（Testcontainers MySQL）→ 发布 → `invoke` 传参查询返回行，`maxRows` 截断生效；
3. 未发布连接器 invoke → 明确错误码（4xxx），提示"未发布"；
4. 白名单外域名 / 内网 IP URL → 发布与执行均被拒（BLOCKED 落日志）；
5. `conn_definition_version` 快照不可变：发布后修改 spec，旧版本调用行为不变；回退版本后立即生效；
6. 数据库直查 `conn_credential.secret_cipher` 无明文；API 响应与执行日志无凭据/敏感头；
7. 密码错误的凭据 → FAILED + 脱敏错误信息，重试次数符合配置；
8. mono 模式：2 进程全栈含 connector 域，smoke 13+6 断言全绿；独立模式 10 服务注册正常；
9. 停用连接器 → invoke 立即 4xxx；恢复后正常；
10. `metadata` 模块停用 → connector 模块 BROKEN，网关路由摘除（module-routes 可见原因"依赖未启用: metadata"）。

---

## 12. 后续路线（P2/P3）

### 12.1 P2：AI API 配置器（用户诉求第三项）

将 `ai/gateway/config.py` 的环境变量单模型升级为**库配置 + 界面管理**：

- Java 侧（openforge-connector 或 knowledge 域）新增 `ai_provider` 表（provider_code/base_url/api_key 加密/model/timeout/enabled/降级优先级），AES-GCM 复用同一主密钥体系；
- ai-gateway 增加 `GET /internal/llm-config`（X-Internal-Token 鉴权），启动与 30s 轮询加载，**环境变量继续作为兜底**（库无配置时回落，保证现网零破坏升级）；
- 前端"AI 模型配置"页：供应商列表、连通性测试（调一次 models 端点）、启停、降级链排序；
- 里程碑收敛：多模型分流（不同功能绑不同模型）随 AI 中台迭代再排。

### 12.2 P2：事件触发 + 定时调度

- 连接器增加 `trigger` 配置（EVENT：订阅既有主题如 `part.released` → 渲染模板 → 执行；CRON：Spring Scheduling 起步）；
- 引入 `sys_connector_dlq` 死信 + 重放端点 + 界面（对齐 B2 事件总线幂等/死信既有语义）。

### 12.3 P2/P3：评估项

- 连接器元数据是否统一登记进元数据中心（换 KERNEL 级发布治理/依赖图，付耦合成本）；
- 按连接器的 ACL 细粒度授权；出站脱敏代理与 AI 外呼通道合并；多步骤编排（画布骨架与 `NodeConfigPanel` 已在 MVP 就位，P3 扩展 steps[]/edges 数据模型、节点连线交互与顺序/分支执行引擎）。

---

## 13. 风险与开放问题

> **已确认决策（2026-09-06 评审）**：①错误码 6xxx 段未被占用，可用；②执行日志保留期 **180 天**（对齐登录日志惯例）；③设计器 MVP 即按画布骨架预留（§8，含降级路径）。

| # | 风险/问题 | 应对 |
|---|-----------|------|
| R1 | 主密钥丢失 → 凭据不可解 | 部署文档强制说明密钥备份；密钥轮换（重加密批处理）列 P2 |
| R2 | JDBC 新增 MySQL 驱动依赖体积 | 仅 `mysql-connector-j` 一个 jar（~2.5MB），可接受；更多方言按需加 |
| R3 | mono 资源遮蔽（v1.12.0 前车之鉴） | 资源目录化从刀1 就按先例执行，不走裸根路径 |
| R4 | 出站白名单配置成本（每环境维护） | 文档给出常用示例；未配置=全拒是刻意的安全默认值 |
| R5 | `flow/` 画布组件为流程域设计，复用需泛化改造 | 刀3 spike 结论：flowModel 深度绑定流程域语义（审批/规则/序列化），泛化成本超阈值，触发降级路径（列表+NodeConfigPanel 组件化，P3 一次性引入画布，组件复用零浪费） |
| R6 | SSRF 校验与实际请求之间存在 DNS 重绑定窗口（TOCTOU） | MVP 已做：白名单 host 匹配 + 解析后私网拒绝（收敛窗口）。根治需固定解析 IP 直连或出站代理，列 P2（与出站脱敏代理通道合并实施） |
| R7 | 凭据以明文参与连接池 key 摘要（SHA-256 of char[]） | 摘要不可逆，泄露面可接受；凭据轮换生成新池、旧池 LRU 驱逐关闭 |
| Q2 | `conn:invoke` 是否要细化到连接器级 ACL | MVP 租户级，P2 评估（§12.3） |
