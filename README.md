# 🔨 OpenForge PLM

<div align="center">

**Open source PLM, forged with AI.**

> v1.1.0 新增：完整权限体系——固定 admin 账号、角色自定义与界面级增删改查权限矩阵、密码半年过期强制重置、登录锁定与安全审计。
>
> v1.2.0（框架化 F1）：OpenAPI 全服务文档、Testcontainers 真实 PG 测试矩阵、Nacos 服务发现（默认关闭）。
>
> v1.3.0（框架化 F2~F3 + 尾项）：动态对象运行时与建模→DDL→AI 闭环、模块注册机制（部署即注册/停用即摘除/启动自检）、多租户全链路、表单/列表设计器、Starter 三件套与 `openforge-cli` 脚手架、Prometheus+TraceId 可观测、生产 compose 与 Helm 骨架、[二次开发指南](docs/OpenForge-二开指南.md)（详见[框架化路线](docs/OpenForge-框架化路线.md)）。
>
> v1.4.0（B2 事件总线）：RocketMQ 事件驱动跨域协作——发布/记录/文档/变更/任务五域事件、knowledge 知识自动沉淀、幂等消费+死信、默认关闭回退同步 HTTP（[设计文档](docs/OpenForge-B2事件总线设计.md)）。

**开源 · AI 原生 · 产品全生命周期管理平台**

`Open` 开源开放 ｜ `Forge` 锻造熔炉 ｜ `PLM` 产品全生命周期管理

[![Status](https://img.shields.io/badge/status-v1.4.0-blue)]()
[![Docs](https://img.shields.io/badge/docs-7%20documents-green)]()
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)]()

</div>

---

**English** — OpenForge PLM is an open-source, AI-native Product Lifecycle Management platform for modern manufacturing: built-in AI agents that can query and operate your data through natural language, a self-adaptive knowledge base that learns from your business, and a low-code customization engine for objects, forms and workflows — all forged in one open platform.

**中文** — OpenForge PLM 是一套面向现代制造业的开源网页端产品生命周期管理系统。它不只是把 AI 外挂在传统 PLM 旁边，而是让 AI 成为平台基础设施：自然语言直接查询与操作业务数据、随业务使用持续进化的自适应知识库、业务人员可自行搭建的流程与表单。核心域用专业代码保证深度与性能，长尾需求用低代码配置实现天级交付。

---

## ✨ 核心特性

| 特性 | 说明 |
|------|------|
| 🔧 **通用 PLM 内核** | 物料与多视图 BOM（EBOM/PBOM/MBOM）、文档版本与检入检出、三级变更管理（ECR→ECO→ECN）、项目管理、CAD 集成、ERP/MES 集成 |
| 🤖 **内置 AI（非外挂）** | AI 中台统一接入多模型（GLM/Qwen/私有化 vLLM）：文档智能解析、BOM 智能清洗、变更影响分析、混合语义搜索、对话式 AI 助手 |
| 💬 **AI 数据操作** | 自然语言 → 数据操作："帮我新增一个 45# 钢的法兰盘"。API 通道优先 + SQL 安全网关五层校验 + L1~L4 分级确认，AI 权限永远是用户权限的子集 |
| 🧠 **自适应知识库** | 三层知识（系统元数据 / 业务知识 / 使用行为）：数据库结构变更自动同步为 AI 的"系统地图"；变更案例、项目复盘自动沉淀；反馈回流驱动检索与推荐持续进化 |
| ⚙️ **流程定制化** | bpmn-js 可视化流程设计器 + Formily 低代码表单；会签/加签/驳回策略、超时处理、审批代理；流程包版本化 + 在途实例快照 + 灰度发布 |
| 🧱 **低代码平台** | 元数据驱动内核：七大设计器（对象/表单/列表/流程/规则/报表/集成）+ 动态对象运行时。新对象建模发布后，零代码获得 CRUD API 与可配置界面，AI 立即可查 |
| 🤝 **多智能体 + Loop Engineering** | 开发平面（Agent 团队开发本系统）与运行平面（AI 功能 MAS 化）同构；所有智能体产出必须通过"生成→验证→修正"闭环，确定性验证优先，LLM 永不终审 |

## 🏗️ 架构一览

```
┌─────────────────── 用户层：Web SPA / 移动H5 / OpenAPI ──────────────────┐
├─────────────────── 接入层：API Gateway(认证/限流/灰度/审计) ─────────────┤
├─────────────────── 应用层：物料BOM │ 文档 │ 变更 │ 项目 │ 搜索 ────────────┤
├──────────────────────────── 平台层 ────────────────────────────────────┤
│  低代码平台域                    │  AI 中台域                            │
│  元数据内核 · 设计器集群           │  模型网关 · Agent编排                  │
│  动态对象运行时 · 流程/规则引擎    │  Text-to-SQL安全网关 · 知识加工管道     │
│  连接器运行时                    │  Schema元数据同步                      │
├──────────────────────────── 数据层 ────────────────────────────────────┤
│  PostgreSQL │ Redis │ Milvus │ Neo4j │ ES │ MinIO │ RocketMQ           │
└────────────────────────────────────────────────────────────────────────┘
```

## 📚 文档导航

| 文档 | 内容 | 适合谁 |
|------|------|--------|
| [OpenForge-开发文档](docs/OpenForge-开发文档.md) | 功能规格、数据库设计、API 规范、里程碑 | 全体开发/QA |
| [OpenForge-架构文档](docs/OpenForge-架构文档.md) | 分层架构、C4 视图、低代码平台架构、ADR、部署 | 架构师/DevOps |
| [OpenForge-多智能体与LoopEngineering](docs/OpenForge-多智能体与LoopEngineering.md) | 双平面 MAS、四层循环验证体系、Agent 基础设施 | AI 工程师 |
| [OpenForge-权限体系完善方案](docs/OpenForge-权限体系完善方案.md) | 固定 admin、双层权限、密码时效与登录安全（P1~P6 已交付） | 后端/前端 |
| [OpenForge-框架化路线](docs/OpenForge-框架化路线.md) | 从应用到框架的差距分析与实施计划（F1~F3 已完成） | 全体开发/架构师 |
| [OpenForge-F2动态对象运行时设计](docs/OpenForge-F2动态对象运行时设计.md) | 动态对象建模→DDL→AI 闭环实施蓝图（已交付） | 后端/前端 |
| [OpenForge-F2模块注册机制设计](docs/OpenForge-F2模块注册机制设计.md) | 部署即注册/停用即摘除的模块化机制设计（已交付） | 后端/架构师 |

## 🛠️ 技术栈

**前端** React 18 + TypeScript + Ant Design + ECharts ｜ **后端** Java 21 + Spring Boot 3（9 个微服务：gateway/auth/material/doc/workflow/change/knowledge/project/metadata）+ openforge-common/security 公共库 + openforge-starter-web/data/security 起步依赖三件套 ｜ **AI** Python FastAPI + LangChain 生态 + 多模型接入（支持全私有化与离线降级） ｜ **存储** PostgreSQL / Milvus(路线) / Neo4j(路线) / MinIO ｜ **基础设施** Kubernetes(路线) + Flyway 多服务迁移 + GitHub Actions 三语言 CI

## 🗺️ Roadmap（v1.0 + 权限专项 + 框架化 F1~F3 全部交付）

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M1 v0.1.0 | 基础平台：认证 / RBAC + 注解式权限 / 组织树(物化路径) / 编号规则引擎(并发防重号) / 前端框架 | ✅ |
| M2 v0.2.0 | 核心 PLM：物料分类树 / 属性模板校验 / 物料状态机+版本快照 / BOM(展开·环检测·反查·对比) / 文档检入检出 | ✅ |
| M3 v0.3.0 | 流程引擎：定义版本化+定义快照 / 会签·或签·驳回回退 / 任务中心 / ECR 变更闭环 | ✅ |
| M4 v0.4.0 | AI 中台：统一 LLM 接入+离线降级 / 文档解析管道 / SQL 安全网关(AST 五重校验) / AI 助手 | ✅ |
| M5 v0.5.0 | 自适应知识库：向量检索(可插拔) / 反馈闭环质量分 / 自然语言→SQL(Schema 注入+安全网关兜底) | ✅ |
| M6 v1.0.0 | 项目与任务管理 / 跨服务统计报表(ECharts) / 一键启动脚本 | ✅ |
| 权限专项 v1.1.0 | 固定admin / 双层权限(菜单+每界面增删改查) / ADMINS次级管理员 / 用户管理 / 密码半年过期强制重置 / 登录锁定 / 密码历史 / 登录日志与审计 / 前端权限联动 | ✅ |
| 框架化 F1 v1.2.0 | OpenAPI 全服务文档 / Testcontainers 真实 PG 测试矩阵 / Nacos 服务发现(默认关闭) | ✅ |
| 框架化 F2 v1.3.0 | 动态对象运行时(建模→发布→DDL/权限点/AI 登记→动态 CRUD→界面) / 模块注册机制(部署即注册·停用即摘除·启动自检·依赖守护·EXTENSION 同构) | ✅ |
| 框架化 F3 v1.3.0 | 多租户全链路(JWT/网关/SQL 行级隔离/租户管理) / 表单列表设计器 / Starter 三件套 / openforge-cli 脚手架 | ✅ |
| 尾项 v1.3.0 | Prometheus+TraceId 可观测+监控栈 / 文件租户隔离 / 生产 compose 全栈 + Helm 骨架 / 二开指南 | ✅ |
| 事件总线 v1.4.0 | RocketMQ 事件驱动：信封(eventId 幂等/租户/traceId) / 一域一 topic / 知识自动沉淀 / 幂等消费+死信 / 熔断+HTTP 回退(默认关闭) | ✅ |

**后续路线**：Nacos 配置中心、bpmn-js 可视化流程设计器、pgvector/Milvus 切换、Grafana 看板模板、ERP/MES 连接器、行业模板包。

## 🚀 快速开始

```bash
# 一键启动（依赖 + 9 个 Java 服务）
./scripts/dev-up.sh
# NACOS=1 ./scripts/dev-up.sh   # 可选：启用 Nacos 服务注册（默认关闭）

# AI 网关与前端（另开终端）
cd ai && pip install -r requirements.txt && uvicorn gateway.main:app --port 8001
cd frontend && npm install && npm run dev   # http://localhost:5173

# 停止
./scripts/dev-down.sh
```

## 🎨 品牌指南

- **命名寓意**：`Forge`（锻造熔炉）——制造业的锻造传统 + 开源社区的协作熔炉（SourceForge/Electron Forge 一脉）+ AI 像炉火一样重塑产品研发生命周期；
- **Slogan**：*Open source PLM, forged with AI.*（开源锻造，智造产品全生命周期）
- **Logo 概念**：铁砧上的产品轮廓/齿轮 + 智能火花，或锻炉火焰构成的闭环箭头；
- **品牌色**：锻炉橙 `#F25C05`（主）+ 钢铁灰 `#4A5568`（辅）；
- **仓库命名**：GitHub 组织 `openforge-plm`，域名 `openforgeplm.com`（待查重注册）。

## 🤝 参与贡献

应用主体（M1~M6 + 权限专项 v1.1.0）与框架化 F1~F3 已交付，欢迎在以下方向参与：

- 方向讨论：框架化路线剩余域（RocketMQ 事件总线、Nacos 配置中心、行业模板包）提出 Issue 讨论；
- 早期共建：bpmn-js 流程设计器、Grafana 看板模板、ERP/MES 连接器、连接器与插件生态；
- 场景输入：分享你所在行业的 PLM 痛点与流程样本，帮助打磨低代码模板库。

## 📄 License

采用 **Apache-2.0**（完整许可证文本见仓库根目录 [LICENSE](LICENSE)）：与主流生态兼容、含明确专利授权、对企业用户友好，同时保留双重许可（开源版 + 商业版增值模块）的演进空间。

---

<div align="center">

**OpenForge PLM** — *Open source PLM, forged with AI.*

⭐ 如果这个项目对你有价值，欢迎 Star 关注进展

</div>
