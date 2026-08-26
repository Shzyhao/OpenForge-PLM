# OpenForge 框架化路线

> 从应用到框架的差距分析与实施计划
> 版本：v1.0 ｜ 状态：执行中（F1）｜ 更新日期：2026-08-26
> 前提：v1.1.0 应用主体已完成（8 个 Java 微服务 + Python AI 网关 + React 前端，32 个 PR）

---

## 1. 定位

OpenForge 当前是一套**业务写死的 PLM 应用**。框架化 = 变成**可让他人快速构建自己系统的平台底座**：
二次开发者不写（或少写）后端代码即可定义新业务对象、界面与流程，并自动获得权限、编号、审计、AI 等平台能力。
架构文档（元数据驱动 / 低代码平台 / 动态对象运行时）即为此设计，实现进度约三分之一。

## 2. 差距清单（六域）

### A. 平台内核化（框架的本质，最大差距）

| # | 事项 | 现状 | 目标 |
|---|------|------|------|
| A1 | 动态对象运行时 | ❌ 未实现（架构文档 5.3） | 对象建模器 + `/objects/{objectKey}/records` 通用 CRUD——零后端代码定义新对象 |
| A2 | 建模→DDL→AI 闭环 | ❌ | 建模发布自动生成 Flyway 迁移 + Schema 知识同步（新对象即刻可被 AI 查询） |
| A3 | 表单/列表/报表设计器 | ❌（仅 JSON 流程编辑器） | 至少补表单 + 列表两个设计器 |
| A4 | 模块化注册 | ❌ 7 服务固化 | 模块启停机制（不装某模块则不路由不迁移），内核/业务边界——设计见《OpenForge-F2模块注册机制设计》 |
| A5 | Starter 化 | 半个（security） | `openforge-starter-web/data/security`：引依赖即得统一响应/权限/审计/编号/多租户 |

### B. 基础设施（当前为单机演示级）

| # | 事项 | 现状 |
|---|------|------|
| B1 | 服务注册发现 + 配置中心 | URL 硬编码 localhost，待 Nacos |
| B2 | 事件总线 | RocketMQ 未接（知识自动沉淀依赖它，现为同步 HTTP 轮询） |
| B3 | 可观测 | SkyWalking/Prometheus 设计有实现零 |
| B4 | 部署形态 | K8s Helm + 生产 compose 待做（现有 dev-up.sh 仅开发用） |

### C. 多租户（SaaS 前提）

`tenant_id` 字段已预留但全链路隔离为零：JWT 携带租户、SQL 自动注入过滤、文件/向量按租户隔离。

### D. 开发者体验

1. **API 文档**（OpenAPI/Swagger）——F1 交付；
2. 脚手架生成器 `openforge-cli new-service`；
3. 二开文档（扩展点/插件指南/组件规范）；
4. 版本化迁移 + 升级指南。

### E. 质量保障

**Testcontainers 集成测试矩阵**：三轮真实冒烟抓到的 7 个缺陷全部是 H2 单库 + MockBean 盲区，必须把"真实 PG + Flyway 全量 + 核心链路"固化进 CI——F1 交付。

### F. 产品化与生态

安装初始化向导、行业模板包（离散/汽配/电子）、i18n、文档站 + 在线 Demo、开源治理（内核 Apache-2.0 + 高级模块商业化）。

## 3. 实施路线

| 阶段 | 内容 | 定位 | 状态 |
|------|------|------|------|
| **F1 基础达标** | OpenAPI 文档（springdoc 全服务）+ Testcontainers 测试矩阵（真实 PG 跑 Flyway 全量与核心链路）+ Nacos 服务发现 | 从"能跑的 Demo"到"可信的底座" | 🔄 执行中（OpenAPI + Testcontainers 先行；Nacos 需引入注册中心运维，随 F1 尾部署环境一并做） |
| F2 核心卖点 | 动态对象运行时 + 建模→DDL→AI 闭环 + 模块注册机制 | 兑现"低代码 × AI"架构承诺 | ⬜ |
| F3 商业化 | 多租户 + 表单设计器 + 脚手架 CLI + Helm | 可对外交付的框架产品 | ⬜ |

## 4. 工程备忘（已踩坑沉淀）

1. **网关路由清单**：新增 API 前缀必须同步 gateway `application.yml`（三次被真实冒烟捕获）；F2 实现启动自检（扫描各服务 Controller 前缀与路由比对，方案 C6 同源思路）；
2. **H2 与 PG 的 SQL 方言**：跨库迁移避免 `ON CONFLICT`（H2 不支持），用 `WHERE NOT EXISTS` 幂等写法；
3. **MyBatis-Plus 三坑**：分页拦截器必须注册（selectPage 不算 total）；`updateById` 默认忽略 null（置空列需 `FieldStrategy.ALWAYS`）；`@Value` 不绑定 yml 列表（用逗号分隔字符串）；
4. **测试矩阵盲区**：纯 Mockito 单测无法覆盖 Spring 容器装配、H2 独立库无法覆盖多服务共库——Testcontainers 为唯一可信层。
