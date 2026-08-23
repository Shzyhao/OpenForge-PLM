# 🔨 OpenForge PLM

<div align="center">

**Open source PLM, forged with AI.**

**开源 · AI 原生 · 产品全生命周期管理平台**

`Open` 开源开放 ｜ `Forge` 锻造熔炉 ｜ `PLM` 产品全生命周期管理

[![Status](https://img.shields.io/badge/status-design%20phase-blue)]()
[![Docs](https://img.shields.io/badge/docs-3%20documents-green)]()
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

## 🛠️ 技术栈

**前端** React 18 + TypeScript + Ant Design + bpmn-js + Formily ｜ **后端** Java 21 + Spring Boot 3 + Spring Cloud Alibaba ｜ **AI** Python + LangGraph + 多模型接入（支持全私有化） ｜ **存储** PostgreSQL / Milvus / Neo4j / Elasticsearch / MinIO ｜ **基础设施** Kubernetes + RocketMQ + Prometheus/SkyWalking

## 🗺️ Roadmap

| 阶段 | 内容 |
|------|------|
| M1（第 1–6 周） | 基础平台：认证权限、编号规则、网关、CI/CD |
| M2（第 7–14 周） | 核心 PLM：物料、BOM（CAD 解析）、文档管理 |
| M3（第 11–18 周） | 流程引擎：流程/表单设计器、变更全流程 |
| M4（第 15–22 周） | AI 中台 v1：文档解析、BOM 清洗、混合搜索、AI 助手 |
| M5（第 19–26 周） | 知识库 v1：RAG、事件自动沉淀、反馈闭环 |
| M6（第 27–32 周） | 项目管理、报表、ERP 集成、试点上线 |

并行推进 MAS-1~4：Agent 基础设施 → Dev Agent Team → 运行时 MAS 化 → 生态治理。

## 🎨 品牌指南

- **命名寓意**：`Forge`（锻造熔炉）——制造业的锻造传统 + 开源社区的协作熔炉（SourceForge/Electron Forge 一脉）+ AI 像炉火一样重塑产品研发生命周期；
- **Slogan**：*Open source PLM, forged with AI.*（开源锻造，智造产品全生命周期）
- **Logo 概念**：铁砧上的产品轮廓/齿轮 + 智能火花，或锻炉火焰构成的闭环箭头；
- **品牌色**：锻炉橙 `#F25C05`（主）+ 钢铁灰 `#4A5568`（辅）；
- **仓库命名**：GitHub 组织 `openforge-plm`，域名 `openforgeplm.com`（待查重注册）。

## 🤝 参与贡献

项目当前处于**设计阶段**——三份架构与设计文档已就绪，欢迎在以下方向参与：

- 设计评审：对架构、数据模型、AI 安全机制提出 Issue 讨论；
- 早期共建：M1 基础平台的模块认领（网关/认证/编号规则/前端骨架）；
- 场景输入：分享你所在行业的 PLM 痛点与流程样本，帮助打磨低代码模板库。

## 📄 License

计划采用 **Apache-2.0**：与主流生态兼容、含明确专利授权、对企业用户友好，同时保留双重许可（开源版 + 商业版增值模块）的演进空间。正式发布前将在仓库根目录添加完整许可证文本。

---

<div align="center">

**OpenForge PLM** — *Open source PLM, forged with AI.*

⭐ 如果这个项目对你有价值，欢迎 Star 关注进展

</div>
