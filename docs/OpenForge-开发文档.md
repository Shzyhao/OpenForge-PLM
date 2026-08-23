# OpenForge PLM 系统开发文档

> OpenForge PLM ｜ 开源 · AI 原生 · 产品全生命周期管理系统（Product Lifecycle Management）
> 版本：v1.0 ｜ 状态：设计稿 ｜ 更新日期：2026-08-23

---

## 目录

1. [项目概述](#1-项目概述)
2. [总体架构](#2-总体架构)
3. [通用 PLM 核心模块](#3-通用-plm-核心模块)
4. [AI 处理模块](#4-ai-处理模块)
5. [自适应知识库](#5-自适应知识库)
6. [流程定制化引擎](#6-流程定制化引擎)
7. [数据库设计](#7-数据库设计)
8. [API 设计规范](#8-api-设计规范)
9. [前端设计](#9-前端设计)
10. [安全与权限体系](#10-安全与权限体系)
11. [非功能性需求](#11-非功能性需求)
12. [部署方案](#12-部署方案)
13. [开发里程碑](#13-开发里程碑)
14. [术语表](#14-术语表)

---

## 1. 项目概述

### 1.1 背景

制造企业在产品研发过程中普遍面临以下痛点：

- **数据孤岛**：CAD 文档、BOM 表、变更单、工艺文件散落在不同系统和个人电脑中，缺乏统一数据源；
- **流程僵化**：传统 PLM 的审批流程硬编码，组织结构调整或业务变化时需要二次开发；
- **知识流失**：老工程师的经验、历史决策原因、故障案例没有被沉淀，人员流动导致知识断层；
- **检索低效**：文档检索依赖文件名和目录，无法按语义查找"上次那个耐高温的密封方案"。

### 1.2 产品定位

构建一套 **网页端（B/S 架构）的下一代 PLM 系统**，在覆盖经典 PLM 能力（物料、BOM、文档、变更、项目）的基础上，内置三大差异化能力：

| 能力 | 说明 |
|------|------|
| **内置 AI 处理** | 文档智能解析、BOM 清洗、变更影响分析、语义搜索、对话式 AI 助手，AI 能力作为平台基础设施供各模块调用 |
| **自适应知识库** | 基于 RAG + 知识图谱，自动从业务数据中沉淀知识，根据使用反馈持续进化，与业务权限联动 |
| **流程定制化** | 低代码可视化流程设计器 + 表单设计器，业务人员可自行搭建审批流，支持版本管理和灰度切换 |

### 1.3 目标用户

- **研发工程师**：图文档管理、BOM 编制、变更发起；
- **工艺/制造工程师**：工艺路线管理、BOM 消费方；
- **项目经理**：项目计划、任务分派、里程碑跟踪；
- **质量/审核人员**：审批、变更评审、追溯；
- **系统管理员**：组织权限、流程配置、知识库运营。

### 1.4 技术选型总览

| 层次 | 技术 | 选型理由 |
|------|------|----------|
| 前端 | React 18 + TypeScript + Ant Design + Zustand | 企业级组件丰富，生态成熟 |
| 流程/表单设计器 | bpmn-js + Formily（自研封装） | BPMN 2.0 标准 + 低代码表单 |
| 后端框架 | Java 21 + Spring Boot 3.x（微服务，Spring Cloud Alibaba） | 制造业 PLM 主流栈，事务成熟 |
| AI 编排 | Python 3.12 + LangChain/LangGraph | AI 生态最完善 |
| 大模型 | 支持多模型接入：GLM / Qwen / GPT 兼容 OpenAI 协议，支持私有化部署 | 灵活切换，数据不出域可选 |
| 向量数据库 | Milvus（主）+ PostgreSQL/pgvector（轻量场景） | 开源、可水平扩展 |
| 图数据库 | Neo4j | 知识图谱、影响分析 |
| 主数据库 | PostgreSQL 16 | JSON 支持好，扩展性强 |
| 缓存/消息 | Redis + RocketMQ | 缓存、分布式锁、事件驱动 |
| 对象存储 | MinIO / S3 兼容存储 | 存放 CAD 文件、附件 |
| 搜索引擎 | Elasticsearch | 全文检索 + 向量混合检索 |
| 文件预览 | OnlyOffice / LibreOffice headless | Office 文档在线预览编辑 |
| CAD 解析 | CADConverter 服务（ Parasolid/OCC 内核二次封装） | 解析 BOM 结构、缩略图提取 |
| 容器化 | Docker + Kubernetes + Helm | 弹性伸缩、灰度发布 |
| 可观测性 | Prometheus + Grafana + Loki + SkyWalking | 指标、日志、链路追踪 |

---

## 2. 总体架构

### 2.1 架构总览

系统采用前后端分离 + 微服务架构，AI 能力独立成服务域，通过 AI 中台网关统一对外提供。

```
┌─────────────────────────────────────────────────────────────────────┐
│                          前端层（Web Browser）                        │
│   React SPA：工作台 / 物料BOM / 文档 / 变更 / 项目 / 知识库 / 流程设计器  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS / WebSocket / SSE
┌──────────────────────────────▼──────────────────────────────────────┐
│                       接入层（API Gateway）                           │
│         认证鉴权（JWT+RBAC）/ 限流 / 路由 / 灰度 / 审计日志            │
└───┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┘
    │          │          │          │          │          │
┌───▼───┐ ┌───▼───┐ ┌───▼────┐ ┌───▼────┐ ┌───▼────┐ ┌───▼────┐
│ 物料  │ │ 文档  │ │ 变更   │ │ 项目   │ │ 流程   │ │ 知识库 │
│ BOM   │ │ 服务  │ │ 服务   │ │ 服务   │ │ 引擎   │ │ 服务   │
│ 服务  │ │       │ │        │ │        │ │(定制化)│ │        │
└───┬───┘ └───┬───┘ └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘
    │          │          │          │          │          │
┌───▼──────────▼──────────▼──────────▼──────────▼──────────▼────┐
│                       基础服务域                                   │
│   用户/组织/权限 │ 消息通知 │ 编号规则 │ 全文检索 │ 文件预览转换      │
└───┬─────────────────────────────────────────────────────────┬──┘
    │                                                         │
┌───▼──────────────────────────────┐              ┌───────────▼───────────┐
│         AI 中台（Python）         │              │      数据存储层         │
│  ┌────────────────────────────┐  │              │ PostgreSQL │ Redis    │
│  │  AI 服务网关（统一LLM接入） │  │              │ Milvus    │ Neo4j     │
│  ├────────────────────────────┤  │              │ Elasticsearch │ MinIO  │
│  │ 文档解析 │ BOM清洗 │ 影响分析│  │              └───────────────────────┘
│  │ 语义搜索 │ AI助手  │ 智能审批│  │
│  ├────────────────────────────┤  │
│  │  Agent 编排（LangGraph）    │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

### 2.2 模块职责划分

| 服务 | 职责 | 关键依赖 |
|------|------|----------|
| plm-gateway | 统一入口、认证、限流、路由 | Redis |
| plm-auth | 用户、组织、角色、RBAC/ABAC 权限 | PostgreSQL |
| plm-material | 物料主数据、BOM 管理、替代料 | PostgreSQL, MinIO |
| plm-doc | 文档库、版本、检入检出、审批态 | MinIO, OnlyOffice |
| plm-change | ECR/ECO/ECN 变更全流程 | 流程引擎, AI 中台 |
| plm-project | 项目、WBS、任务、里程碑 | 流程引擎 |
| plm-workflow | 流程定义/实例/任务，可视化设计器后端 | PostgreSQL, RocketMQ |
| plm-knowledge | 知识库、RAG 管道、图谱构建、**系统元数据同步（Schema 感知）** | Milvus, Neo4j, ES, PostgreSQL |
| plm-ai（Python） | LLM 接入、Prompt 管理、Agent、工具调用、**数据操作代理（Text-to-SQL + SQL 安全网关）** | 全部业务服务、元数据服务 |
| plm-search | 混合检索（关键词+向量） | ES, Milvus |
| plm-notify | 站内信、邮件、Webhook | RocketMQ |
| plm-preview | 文档在线预览、CAD 转换 | MinIO, LibreOffice |

### 2.3 关键设计原则

1. **AI 作为基础设施**：AI 能力通过中台网关以 REST/SSE 接口提供，业务服务不直接调用大模型，便于统一计费、审计、降级；
2. **事件驱动**：跨服务通信一律走 RocketMQ 事件（如 `change.released`、`doc.checked_in`），知识库订阅这些事件自动沉淀知识；
3. **数据不可变审计**：物料、BOM、文档的所有变更保留全量历史（版本链 + 审计表）；
4. **降级可用**：AI 中台故障时，所有业务主流程可关闭 AI 增强功能正常运行（熔断开关）；
5. **多租户可选**：数据层预留 `tenant_id` 字段，支持 SaaS 化部署；
6. **AI 触达数据必经安全网关**：自然语言 → SQL 的查询与写入一律通过 SQL 安全网关校验（白名单矩阵、权限合成、分级确认），且 API 通道优先于直连 SQL（见 4.7）；数据库结构变更必须触发元数据知识同步（见 5.3），保证 AI 的"系统地图"永不滞后。

---

## 3. 通用 PLM 核心模块

### 3.1 物料与 BOM 管理

#### 3.1.1 物料（Part）

物料是系统的原子对象，覆盖原材料、标准件、自制件、外购件、半成品、成品。

**物料生命周期状态机**：

```
草稿(Draft) → 提交评审(Reviewing) → 已发布(Released) → 已废止(PhasedOut)
                    │                                      ↑
                    └──── 驳回(Rejected) → 草稿             └── 已发布可申请冻结(Frozen)
```

**核心字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| part_number | varchar(32) | 物料编码，按编号规则自动生成（支持分类码+流水号+校验位） |
| name / name_en | varchar(255) | 中英文名称 |
| type | enum | RAW/STANDARD/MADE/OUTSOURCED/SEMIFINISHED/PRODUCT |
| category_id | bigint | 物料分类（树形，分类上挂属性模板） |
| attrs | jsonb | 扩展属性（由分类的属性模板定义，如材质、颜色、耐温） |
| unit | varchar(8) | 计量单位 |
| lifecycle_state | enum | 状态机状态 |
| version | varchar(16) | 版本号（A/1 双段式：大版本.小版本） |

**物料编码规则引擎**：编号规则可配置（前缀 + 分类码段 + 日期段 + 流水号段 + 校验位），规则存储为 JSON，支持按分类绑定不同规则，支持"申请-占用-释放"防止并发重号。

#### 3.1.2 BOM 管理

支持多视图 BOM（设计 BOM / 工艺 BOM / 制造 BOM）与多类型（EBOM/PBOM/MBOM/SBOM 服务件）。

**核心模型**：

```
BOM(版本化) ──1:N── BOMLine(行)
  BOMLine: 父件ID + 子件ID + 数量 + 位号(RefDes) + 用量类型(正常/替代/选配)
           + 有效期(生效日期~失效日期) + 工艺路线引用
```

**关键功能**：

- **BOM 多层展开/反查**：递归 CTE 实现，展开支持按有效性日期过滤；反查（Where-Used）支持跨层级向上追溯；
- **BOM 对比（Diff）**：任意两个版本的 BOM 结构化对比，输出新增/删除/数量变更/属性变更清单，作为变更模块输入；
- **替代料组**：一个主料可挂多个替代料，替代关系带优先级和生效条件；
- **有效性管理**：行级日期有效性 + 序列号有效性（按台份配置）；
- **BOM 校验**：环检测（禁止自引用与循环）、悬空件检测、必填属性完整性校验；
- **导入导出**：Excel 模板导入（调用 AI 清洗，见 4.3）、多级导出、与 ERP 集成接口（发布时推送）。

#### 3.1.3 CAD 集成

- 上传 SolidWorks/Pro-E/AutoCAD/STEP 等格式后，异步触发 CAD 解析服务：提取装配结构 → 自动生成/对齐 EBOM 草稿，人工确认后入库；
- 提取缩略图与关键属性（材料、重量）回填物料属性；
- CAD 文件与物料对象双向挂接：改图时提示 BOM 同步。

### 3.2 文档管理（DMS）

| 功能 | 设计要点 |
|------|----------|
| 文档类型 | 分类树 + 类型模板（必填属性、命名规则、审批流绑定） |
| 版本管理 | 大版本（Major，内容性变更）/ 小版本（Minor，修订），版本链完整保留，任何历史版本可查看/下载/回滚 |
| 检入/检出（Check-in/out） | 检出后独占编辑锁（带超时与强制收回），检入时产生新版本并记录变更说明 |
| 状态管理 | 草稿→评审中→已发布→已归档，与流程引擎联动 |
| 在线预览 | Office 转 PDF 预览；CAD 转 3D 轻量化模型（WebGL 查看器）；水印（用户+时间） |
| 在线编辑 | OnlyOffice 协同编辑，保存即检入 |
| 关联关系 | 文档↔物料、文档↔项目、文档↔变更单的多对多挂接 |
| 安全 | 按"分类+密级"双重权限控制，下载/打印单独授权并审计 |

### 3.3 变更管理（ECM）

标准三级变更模型，全流程由流程引擎驱动（见第 6 章）：

```
ECR（变更申请）──评审通过──▶ ECO（变更执行）──执行完成──▶ ECN（变更通知）
   提出问题、影响分析        修改对象、执行计划           通知下游（采购/制造/服务）
```

**核心机制**：

1. **变更对象集合（Affected Items）**：ECR 关联受影响物料/BOM/文档；ECO 中对它们的修改在变更"受控区"进行，ECO 未发布前对其他人不可见（Working 模型）；
2. **影响分析**：人工 + AI 双通道（AI 影响分析见 4.4），输出下游受影响对象：反查 BOM、在制订单接口、库存、相关文档；
3. **快速变更**：小版本变更（如改描述）走简化轻流程，可配置；
4. **变更看板**：进行中变更、超期变更、我的待办。

### 3.4 项目管理

- 项目模板（阶段-里程碑-交付物清单可配置，如 IPD 六阶段）；
- WBS 任务分解，任务与交付物（文档/物料发布）挂接，完成标准 = 交付物就绪；
- 甘特图、关键路径、资源负载；
- 项目风险与问题跟踪；
- 项目结项后自动归档知识（推入知识库，见 5.4）。

### 3.5 协同与其他通用功能

- **编号规则中心**：所有对象编号统一管理；
- **消息与待办中心**：待审批、待阅读、@提及，支持邮件/企微/钉钉 Webhook 外发；
- **全局搜索**：一次搜索横跨物料/文档/变更/知识（混合检索，见 4.5）；
- **批量操作**：批量变更属性、批量挂接、批量下载打包；
- **报表中心**：物料发布量、变更周期、BOM 成本汇总，支持自定义报表（拖拽维度）；
- **集成接口**：ERP（物料/BOM 发布推送，双向状态回传）、MES、OA（待办穿透），均走统一 OpenAPI + 消息队列。

---

## 4. AI 处理模块

### 4.1 AI 中台架构

AI 能力独立成 Python 服务域，所有 LLM 调用收口到 AI 网关，实现：多模型路由、Prompt 版本管理、Token 计量、审计、限流、熔断降级。

```
业务服务 ──REST/SSE──▶ AI 网关 ──▶ 模型适配层 ──▶ GLM / Qwen / GPT / 本地vLLM
                        │
                        ├── Prompt 仓库（版本化，A/B 测试）
                        ├── 语义缓存（相同问题命中缓存直接返回，省 Token）
                        ├── 内容安全过滤（敏感词 + 合规审查）
                        └── 观测（Token 用量、延迟、成功率按租户/模块统计）
```

**模型接入约定**：所有模型统一封装为 OpenAI 兼容协议（`/v1/chat/completions`、Embedding 接口），配置化切换。敏感场景（图纸、核心工艺）强制路由到私有化部署模型。

**AI 功能总览**：

| 功能 | 触发方式 | 使用的模型能力 |
|------|----------|----------------|
| 文档智能解析 | 文档上传后异步 | OCR + 信息抽取（结构化输出） |
| BOM 智能清洗 | Excel 导入时 | 表头理解、实体对齐、模糊匹配 |
| 变更影响分析 | ECR 创建时辅助 | RAG + 知识图谱推理 |
| 语义搜索 | 全局搜索框 | Embedding + 混合排序 |
| AI 助手 | 侧边栏对话 | Agent + 工具调用 + RAG |
| **AI 数据操作** | 对话式指令（"帮我新增一个法兰盘"） | Text-to-SQL + 元数据知识（见 4.7） |
| 智能审批建议 | 流程审批时辅助 | RAG（历史审批 + 制度文档） |
| 知识自动摘要 | 知识入库时 | 摘要 + 关键词抽取 |

### 4.2 文档智能解析

**流程**：

```
上传文件 → 格式识别 → 分流处理管道
  ├─ PDF/图片   → OCR（PaddleOCR）→ 版面分析 → 结构化抽取
  ├─ Office     → 文本直提 → 表格结构识别
  └─ CAD/STEP   → CAD 解析服务 → 属性表
→ LLM 结构化抽取（按文档类型的 Schema 输出 JSON）
→ 字段映射与置信度标注 → 低置信度字段高亮，人工在"校对界面"确认
→ 确认后回填物料属性 / 生成文档摘要 / 推入知识库
```

**关键设计**：

- 每种文档类型（规格书、检测报告、承认书）配置一个**抽取 Schema**（JSON Schema），LLM 强制按 Schema 输出（Function Calling / JSON Mode）；
- 每个抽取字段带置信度分数，低于阈值（默认 0.85）的进入人工校对队列；
- 解析结果与源文档区域建立**溯源链接**（页码/坐标），校对时原文高亮对照。

### 4.3 BOM 智能清洗

Excel BOM 导入是最高频的数据入口，AI 清洗管道：

1. **表头智能映射**：LLM 理解任意格式的 Excel 表头（"物料名称/品名/Name/描述"→ 标准字段），人工确认映射模板后可复用；
2. **物料对齐（Entity Resolution）**：导入行与库内物料匹配 —— 精确编码匹配 → 规则相似度（名称分词+属性）→ 向量相似度召回 Top5 候选 → LLM 判定"同一物料 / 相似但不同 / 新物料"，给出合并建议；
3. **异常检测**：数量异常（负数、超大值）、单位缺失、疑似重复行、编码规则违例；
4. **清洗报告**：输出结构化报告（多少行新建、多少行关联已有、多少行需人工裁决），人工一键采纳/逐条修正。

> 原则：**AI 只建议，人决策**。所有 AI 产出以"建议"形态呈现，落库前必须经过人工确认或配置了自动通过阈值的场景。

### 4.4 变更影响分析（AI）

创建 ECR 时，系统自动生成影响分析报告：

**数据通道**（确定性数据，图数据库查询）：

- 反查该物料的所有上级 BOM（至顶）；
- 关联文档、在途 ECO、项目任务；
- ERP 在制/在购数量（集成接口）。

**AI 通道**（知识推理）：

- 从知识库检索：历史相似变更及其后果（如"上次改这个连接器导致线束重新认证"）；
- 从知识图谱推理：该物料的替代料、共用件、认证依赖关系；
- LLM 综合两通道生成**影响分析草稿**：影响范围清单 + 风险等级 + 建议的验证动作 + 历史案例引用（带溯源链接）。

评审人以此为基础修改确认，形成正式影响分析。变更关闭后，实际发生的影响回填，作为反馈数据优化后续分析（自适应闭环，见 5.5）。

### 4.5 语义搜索与 RAG 检索

全局搜索采用**混合检索 + 重排**架构：

```
用户查询 → 查询理解（改写、扩展、纠错）
   ├─ 关键词通道：Elasticsearch（BM25，支持通配/精确）
   └─ 向量通道：查询 Embedding → Milvus ANN 召回
→ RRF（倒数排名融合）合并
→ Rerank 模型（bge-reranker）精排
→ 权限过滤（只返回当前用户有权看见的结果，过滤发生在召回后、返回前）
→ 结果聚合（按对象类型分组 + AI 生成的一句话答案）
```

**权限过滤的安全性**：向量检索的元数据必须带 ACL 标签（owner/部门/密级），在 Milvus 用标量过滤实现"检索即鉴权"，杜绝越权内容出现在摘要中。

### 4.6 AI 助手（对话式 Agent）

侧边栏常驻 AI 助手，基于 LangGraph Agent 架构，可调用工具：

| 工具 | 能力 |
|------|------|
| search_parts | 按自然语言查物料（"找出所有耐温超过 200℃ 的密封件"→ 转结构化查询） |
| get_bom | 展开/反查 BOM |
| search_knowledge | RAG 检索知识库 |
| get_change_status | 查询变更单进度 |
| compare_bom | 两版本 BOM 对比 |
| data_query | 自然语言 → 安全 SQL → 只读查询（经 SQL 安全网关，见 4.7） |
| data_mutate | 白名单表的写操作：生成操作计划 + 确认卡片，确认后执行（见 4.7） |
| create_draft | 生成草稿对象（物料草稿/ECR 草稿/邮件草稿，均需人工提交） |

**安全约束**：

- 写操作仅两条通路：生成"草稿"由用户提交，或经**数据操作代理**（4.7）在白名单矩阵与分级确认机制下执行，不存在第三条；
- 所有工具调用经 RBAC 校验（AI 无超越用户本人的权限）；
- 回答必须带引用来源（"依据：KB-1024《密封件选型规范》第 3 节"）；
- 完整会话审计留存。

### 4.7 AI 数据操作代理（自然语言 → 数据操作）

用户可以用自然语言直接让 AI 处理系统中的数据：从"查一下所有耐温 200℃ 以上的密封件"，到"帮我新增一个 45# 钢、外径 120 的法兰盘"。AI 参考**系统元数据知识库**（见 5.3）理解表结构与表间关系，生成并执行操作，将结果返回用户。

#### 4.7.1 双通道执行模型

| 通道 | 路径 | 适用场景 | 说明 |
|------|------|----------|------|
| **API 通道（默认优先）** | AI 生成结构化操作意图 → 调用业务服务 API（如 `POST /api/v1/parts`） | 物料创建、BOM 修改、变更发起等有业务规则的对象操作 | 完整经过应用层校验（编号规则、状态机、必检项、流程触发），最安全 |
| **SQL 通道（白名单受限）** | AI 生成 SQL → SQL 安全网关校验 → 事务执行 → 结果返回 | 只读查询、白名单表的批量维护、API 未覆盖的简单操作 | 灵活但必须过安全网关，默认全部收紧 |

> 设计原则：**能用 API 通道的绝不用 SQL 通道**。SQL 通道是对灵活性的补充，不是绕过业务规则的捷径。

#### 4.7.2 SQL 安全网关（五层校验）

所有 AI 生成的 SQL 必须逐层通过以下校验方可执行：

```
AI 生成 SQL
 → ①语法层：AST 解析（sqlglot），拒绝多语句、DDL/DCL、存储过程、注释注入
 → ②对象层：表×操作类型白名单矩阵校验；敏感字段（密码/成本/密级）屏蔽或脱敏
 → ③权限层：以发起用户身份执行；自动注入行级过滤（tenant/组织/密级）；SELECT 强制 LIMIT
 → ④规则层：写操作预检——应用层业务规则（编号/必填/状态机）前置校验 + Dry-run 试跑后回滚
 → ⑤执行层：事务执行；影响行数阈值检查；记录反向 SQL（或 before 快照）供回滚
 → 结果脱敏 → 返回用户 + 全量审计（via=ai 标记）
```

#### 4.7.3 执行模式分级与权限合成（权限设计核心）

**分级执行**：

| 级别 | 操作类型 | 执行方式 |
|------|----------|----------|
| L1 | 只读查询 | 直接执行（限流：用户级 QPS + 单次行数上限 + 路由到只读副本） |
| L2 | 低风险写：白名单表、影响行数 < 阈值（默认 10） | 展示 SQL 与影响预览 → 用户确认 → 执行 |
| L3 | 一般写：非关键表、批量较大 | 确认卡片 + 二次确认（或走简易审批，可配置） |
| L4 | 高风险：DELETE、关键表、跨表大批量 | **禁止执行**，引导用户走对应业务流程/变更流程 |

**独立权限点族**（挂在 RBAC 下单独授予）：

- `ai:data:query` —— 允许 AI 只读查询（可按数据域细分：物料/文档/变更…）；
- `ai:data:write` —— 允许 AI 写操作（用户必须**同时**持有对应业务写权限，如 `part:create`）；
- `ai:data:admin` —— 管理白名单矩阵、阈值与黑名单配置。

**权限合成规则**：

```
AI 有效权限 = 用户业务权限 ∩ AI 操作权限点 ∩ 表白名单矩阵
```

三者取交集、缺一不可，AI 在任何情况下都不可能拥有超越发起用户的权限。

**纵深防御**：

- 数据库层面使用**专用低权限账号**执行 AI SQL（仅白名单表的 SELECT/INSERT/UPDATE，无 DELETE、无 DDL）；
- **永久黑名单**（任何配置不可解除）：用户/角色/权限表、审计日志表、流程定义与实例表、AI 配置与 Prompt 表、知识反馈表；
- 白名单矩阵默认**全部关闭**，由 `ai:data:admin` 按租户/部门渐进开放。

#### 4.7.4 典型交互流程（"帮我新增一个法兰盘"）

```
用户： 帮我新增一个 45# 钢的法兰盘，外径 120
 ① AI 检索元数据知识：part 表结构 + 物料分类树 + 属性模板（5.3）
 ② 生成操作计划： 新建物料，分类=法兰件，attrs={材质:45#, 外径:120}
 ③ 选择 API 通道： 组装 POST /api/v1/parts 请求
 ④ 确认卡片： 表单化预览全部字段 → 用户确认/修改
 ⑤ 执行： 业务校验 + 编号规则生成 part_number → 结果返回用户（含新编号）
 ⑥ 审计： 记录操作者=用户、via=ai、完整报文与会话上下文
```

若用户指令是查询（"上月发布了哪些关键件"），走 L1：生成 SELECT → 网关五层校验 → 只读副本执行 → 以表格 + 图表返回，并附可展开的 SQL 与数据来源说明。

#### 4.7.5 结果可信设计

- **透明**：每次返回附生成的 SQL（或 API 调用报文）折叠面板，用户可核查；
- **可回滚**：写操作保留反向 SQL，管理后台提供"AI 操作回滚"（回滚窗口默认 24 小时）；
- **可追溯**：`ai_sql_execution` 表全量记录谁、何时、什么指令、生成了什么 SQL、影响多少行、成败与否；
- **可熔断**：某用户/某表的 AI 操作错误率超阈值，自动暂停该范围 AI 数据操作并告警。

### 4.8 智能审批建议

审批人打开待办时，AI 侧栏给出建议（可折叠，不干扰人工判断）：

- 汇总本次变更/发布的关键差异（LLM 摘要 BOM Diff、文档变更说明）；
- 检索知识库中相关制度条款，判断是否满足要求（如"该物料为关键件，按《XX 管理规定》需要二级审批"）；
- 展示历史同类审批的通过率与常见驳回原因；
- **明确免责设计**：界面标注"AI 建议仅供参考，审批责任人是签核人本人"。

### 4.9 AI 成本与降级

- 语义缓存：重复问题直接命中（缓存键 = 归一化查询 + 权限上下文哈希）；
- 小模型分流：摘要、分类等简单任务用轻量模型，复杂推理用大模型；
- Token 预算：按租户/部门设置月度配额，超额告警；
- 熔断：AI 中台 P99 延迟超阈值或错误率上升，自动降级为纯关键词检索、隐藏 AI 建议，业务主流程不受影响。

---

## 5. 自适应知识库

### 5.1 设计理念

知识库由**三层知识**构成，统一在同一个自适应体系中运转：

| 层次 | 内容 | 价值 |
|------|------|------|
| **L1 系统元数据知识** | 数据库表结构、字段含义、表间关系、约束规则、业务术语词典 | AI 理解系统、操作数据的"地图"，支撑 Text-to-SQL 与数据操作代理（4.7） |
| **L2 业务知识** | 规范文档、变更案例、项目复盘、FAQ 等经验内容 | 支撑 RAG 问答、影响分析、审批建议 |
| **L3 使用行为知识** | 查询日志、采纳/纠错反馈、热点分析 | 驱动检索与推荐持续进化 |

"自适应"体现在五个层面：

1. **结构自适应**：数据库新增/删除表、字段、关系时，元数据知识**自动同步**（见 5.3），AI 对系统结构的认知始终与真实数据库一致，杜绝基于过期结构生成错误 SQL；
2. **自动采集**：业务知识从业务流程中自动沉淀，不依赖专门录入；
3. **自动组织**：入库时自动分类、打标、抽取实体、建立图谱关联；
4. **反馈进化**：用户的点击、采纳、纠错行为（含 SQL 纠错）回流，持续调整检索与推荐质量；
5. **自动保鲜**：检测过时知识（与新版文档冲突、结构已变更），提示更新或归档。

### 5.2 知识库架构

```
┌─────────────── 知识采集层（多源接入） ──────────────────────┐
│ ①Schema事件        ②业务事件流       ③文档库    ④手动/外部 │
│  DDL迁移钩子         变更/项目结项     规范/标准  录入/导入   │
│  pg事件触发器        审批驳回等                                │
│  每日快照对账                                                  │
└───────────────────────┬────────────────────────────────────┘
                        ▼
┌─────────────── 知识加工管道（异步） ───────────────────────┐
│ L1元数据: DDL解析 → 元数据模型差量更新 → 表结构描述重新向量化 │
│           → 关系写入图谱 → 通知AI网关刷新Schema上下文缓存     │
│ L2业务: 去重 → 解析(OCR/格式) → 切分 → 摘要/关键词/实体抽取   │
│         → 向量化 → 自动分类打标 → 质量评分 → 发布(或人工审核) │
└───────────────────────┬────────────────────────────────────┘
                        ▼
┌─────────────── 知识存储层 ─────────────────────────────────┐
│ PostgreSQL(元数据/知识条目)  Milvus(向量)  Neo4j(知识图谱)  │
│  ├ L1: 表/字段/关系/术语词典（schema知识专区, schema_vec）    │
│  └ L2: 业务知识条目与切片（knowledge_chunk_vec）             │
└───────────────────────┬────────────────────────────────────┘
                        ▼
┌─────────────── 知识消费层 ─────────────────────────────────┐
│ 语义搜索 │ RAG上下文(AI助手/影响分析/审批建议)                │
│ Text-to-SQL的Schema上下文(4.7) │ 知识门户 │ 图谱可视化        │
└───────────────────────┬────────────────────────────────────┘
                        ▼
┌─────────────── 反馈闭环层 ─────────────────────────────────┐
│ 点击/采纳/纠错/评分 │ AI查询与SQL执行的成败及纠错回流          │
│ → 反馈存储 → 质量重排与淘汰 → 标注回流(微调领域模型)          │
└────────────────────────────────────────────────────────────┘
```

### 5.3 系统元数据知识库（Schema 感知与自动同步）

系统自身的结构是最重要的知识之一：AI 要能查询和操作数据，前提是"知道"当前有哪些表、字段什么含义、表之间怎么关联。本层让知识库与数据库结构**始终保持同步**——结构新增或删除时，知识自动跟随变化。

#### 5.3.1 元数据模型

| 元素 | 采集内容 |
|------|----------|
| 表（meta_table_def） | 表名、所属服务、业务注释、密级、AI 可操作性（白名单矩阵快照） |
| 字段（meta_column_def） | 字段名、类型、可空、默认值、**业务含义**（优先取 DB 注释，支持人工润色）、字典枚举映射、敏感标记 |
| 关系（meta_relation） | 主外键关系 + **隐式业务关联**（无外键但逻辑相关的表对，人工标注沉淀） |
| 约束/规则 | 唯一约束、状态机规则、编号规则——作为 AI 生成写操作 SQL 时的校验知识 |
| 术语词典 | 自然语言同义词 → 表/字段的映射（"物料"→`part`，"图号"→`doc_info.doc_number`），支持人工维护 + AI 从查询日志中挖掘候选词 |

#### 5.3.2 结构变更同步管道（新增/修改/删除）

三个事件源保底，任何一个触发都进入同一条同步管道：

```
事件源                                        同步管道
├─ ①迁移钩子(首选)：Flyway 迁移成功事件         DDL 解析（sqlglot）
│    携带迁移版本号与 SQL          ──▶        → 元数据模型差量更新
├─ ②数据库触发器(兜底)：pg_event_trigger        → 表结构描述文本重新向量化
│    捕获绕过迁移流程的直改 DDL                → 表关系写入 Neo4j 图谱
└─ ③每日对账：information_schema 快照           → 通知 AI 网关刷新 Schema 上下文缓存
     diff，检测漂移并告警                      → 变更记录入库（schema_change_log）
                                              → 反向影响分析并通知受影响功能负责人
```

**删除的语义**：表/字段被删除时，对应知识条目标记 `ARCHIVED` 而非物理删除——保留 AI 历史回答的可解释性（"该结论基于 v12 的表结构"）；同时执行反向影响分析：检查是否有 AI 功能、术语词典、常用查询模板仍引用被删对象，输出告警清单要求处理。

**漂移防护**：每日对账一旦发现数据库实际结构与知识库记录不一致（如有人绕过迁移直改库），立即告警并强制重新同步，杜绝 AI 基于过期结构生成错误 SQL。

#### 5.3.3 面向 Text-to-SQL 的知识组织

- **表结构描述向量化**：每张表生成一段标准化描述（表名 + 注释 + 字段清单 + 关键约束 + 3 行脱敏样例数据），向量化后存入 Milvus 的 `schema_vec` Collection。用户自然语言提问时先召回相关表（Top-K），再把这几张表的精确结构注入 Prompt 构造 SQL——避免把全库 Schema 塞进上下文；
- **Join 路径推理**：表关系写入 Neo4j，多表查询时用最短路径算法推荐连接方式，防止 AI 编造不存在的关联字段；
- **常用查询模板**：高频自然语言问题与人工验证过的 SQL 固化为模板（如"反查某物料的使用处"），命中模板直接执行、不走生成——更快更稳；
- **字段含义持续润色**：DB 注释往往简陋，管理员可在元数据后台补充业务描述；AI 从查询纠错日志中定期提出"含义模糊字段"候选清单，驱动元数据质量提升。

### 5.4 知识建模

**知识条目（Knowledge Item）**：

| 字段 | 说明 |
|------|------|
| id / title | 知识编号与标题 |
| source_type | DOC / CHANGE_CASE / PROJECT_RETRO / FAQ / DECISION / EXTERNAL |
| source_ref | 溯源链接（源文档ID/变更单号/项目ID） |
| space_id | 所属知识空间（见 5.6 权限） |
| content_raw / content_text | 原文与提取后纯文本 |
| summary | AI 摘要（入库时生成） |
| chunks | 切分后的段落块（每块独立向量化，保留块级溯源） |
| entities | 抽取的实体（物料、故障模式、工艺方法…） |
| tags | 自动标签 + 人工标签 |
| quality_score | 质量分（见 5.5） |
| status | 草稿/已发布/待更新/已归档 |
| version | 知识版本，源文档升版时自动触发再加工 |

**知识图谱（Neo4j）**：

- 节点：`Part`（物料）、`Doc`（文档）、`Knowledge`（知识条目）、`Concept`（领域概念，如"耐腐蚀"、"注塑工艺"）、`Person/Team`（贡献者）、`Project`；
- 关系：`Part -[:HAS_DOC]-> Doc`、`Knowledge -[:REFERENCES]-> Part`、`Knowledge -[:SIMILAR_TO]-> Knowledge`、`Part -[:SUBSTITUTE_OF]-> Part`、`Knowledge -[:CO_OCCUR]-> Concept`；
- 图谱用于：影响分析推理、相关推荐（"看过这个案例的人也看了"）、知识全景可视化。

### 5.5 知识自动采集（事件驱动）

订阅业务事件，自动触发沉淀：

| 事件源 | 沉淀的知识 |
|--------|-----------|
| Schema 变更（`schema.migrated`） | L1 元数据知识自动更新（见 5.3.2），并通知 AI 网关刷新上下文 |
| 变更单关闭（`change.closed`） | 变更案例：问题→原因→措施→效果，LLM 从 ECR/ECO/验证报告中结构化抽取 |
| 项目结项（`project.closed`） | 复盘知识：延期原因、风险应对、经验教训 |
| 文档发布（`doc.released`） | 规范/标准类文档进入知识库（按文档分类路由到对应知识空间） |
| 审批驳回（`task.rejected`） | 驳回原因（脱敏后）成为"常见错误"知识 |
| AI 问答被采纳 | 高频问答自动转为 FAQ 候选 |

所有自动沉淀的知识带 `auto=true` 标记，重要空间可配置"先审后发"。

### 5.6 自适应机制（核心）

**(1) 检索自适应——反馈重排**

```
用户查询 → 召回 Top-K → 用户行为（点击第几条/无点击/采纳答案/纠错）
   → 反馈写入 feedback 表（含查询向量、被点击知识ID、位置）
   → 离线训练重排模型（LTR，特征：向量相似度+BM25+质量分+历史CTR+新鲜度）
   → 新模型灰度上线（按知识空间逐步放量）
```

线上短期用**规则化调权**（近期被点击的知识权重上调、被标记"无用"的下调），模型周期性离线更新，避免频繁变更。

**(2) 质量自适应——知识打分与淘汰**

每条知识维护质量分：

```
quality_score = w1*内容完整度 + w2*引用次数(检索曝光后的点击率)
              + w3*用户评分 + w4*新鲜度衰减 - w5*负反馈次数
```

- 低于阈值 → 进入"待改进"列表，通知知识 Owner；
- 与新版源文档冲突（源文档升版）→ 自动标记"待更新"并重新加工，生成新旧对比供确认；
- 连续 12 个月零引用且低分 → 建议归档。

**(3) 知识自适应——覆盖度检测**

定期分析"查询无结果/低置信度回答"的日志，聚类出**知识盲区**（如大量查询某产品线故障处理但库内无覆盖），生成"知识需求清单"推送知识运营人员定向征集。

**(5) 结构自适应——SQL 纠错回流**

AI 生成 SQL 执行失败或被用户修正时，失败原因（表名错、关系错、字段含义误解）回流到元数据知识：

- 字段含义被误解 → 生成"补充该字段业务描述"的任务，推给元数据管理员；
- AI 编造了关系 → 检查 `meta_relation` 是否缺失隐式关联，生成"建议补充关系"任务；
- 被高频修正的查询 → 沉淀为常用查询模板（5.3.3），下次直接命中。

由此形成闭环：**AI 用得越多，它对系统结构的理解越准**，元数据知识库从"被动镜像 schema"进化为"经过实战校验的系统地图"。

**(6) 标注回流**

人工对 AI 抽取的实体/标签/摘要的每一次修正，都作为微调数据累积（存储为标准 SFT 格式），达到阈值后用于微调领域小模型（Embedding 或抽取模型），持续提升领域效果。冷启动阶段依赖 Prompt 工程 + 通用模型，随数据积累逐步私有化。

### 5.7 知识空间与权限

- 知识按**空间（Space）**组织：部门空间、产品线空间、公开空间、个人空间；
- 空间权限：Owner / 编辑 / 阅读 / 申请阅读（申请需审批）；
- **密级继承**：从源文档继承密级，密级高于用户等级的知识即使被检索到也不会返回（在向量检索元数据过滤层拦截）；
- 跨空间去重：同一文档被多空间引用时保留单一实体 + 空间映射，避免向量冗余。

---

## 6. 流程定制化引擎

### 6.1 设计目标

业务人员（非开发人员）通过可视化界面完成：

- 绘制审批/业务流程（BPMN 子集 + PLM 扩展节点）；
- 自定义表单（拖拽式低代码表单）；
- 配置流转规则（条件分支、会签、加签、超时处理）；
- 版本管理流程定义，新旧版本平滑切换。

### 6.2 引擎架构

```
┌──────────────── 前端设计器 ────────────────┐
│ 流程设计器(bpmn-js定制) + 表单设计器(Formily) │
│ + 规则配置面板(条件/超时/通知)                │
└──────────────────┬─────────────────────────┘
                   │ 保存流程包(流程XML+表单Schema+规则JSON)
┌──────────────────▼─────────────────────────┐
│ 流程引擎服务 (自研，参考 Flowable 精简内核)     │
│ ├─ 定义仓库：流程包版本化存储                  │
│ ├─ 实例管理：启动/推进/挂起/终止/撤回           │
│ ├─ 节点执行器：人工节点/服务节点/AI节点/网关      │
│ ├─ 任务中心：待办/已办/抄送/代理                │
│ └─ 事件外发：RocketMQ(任务创建/流程完成等)      │
└──────────────────┬─────────────────────────┘
                   │
     ┌─────────────┼─────────────┐
     ▼             ▼             ▼
  业务服务      AI 中台         消息通知
 (回调/上下文) (AI节点能力)    (待办/超时提醒)
```

### 6.3 流程模型（节点类型）

| 节点类型 | 说明 |
|----------|------|
| 开始/结束 | 支持多结束节点（正常结束/驳回结束） |
| **审批节点（人工）** | 配置审批人：指定人/角色/部门主管/发起人主管/表单字段动态指定；支持**会签**（全票/比例票/一票通过）、**或签**、**依次审批** |
| **加签/转办** | 审批中临时增加审批人（前加签/后加签）、转办他人，全程留痕 |
| **服务节点（自动）** | 调用后端服务：自动编号、ERP 推送、BOM 校验、发布动作（配置服务标识+参数映射） |
| **AI 节点** | 调用 AI 中台：智能预审（表单合规性检查）、内容风险提示、自动分类路由建议（结果作为网关变量，不直接决定流转，除非显式配置） |
| **条件网关** | 基于表单变量/流程变量的表达式路由（可视化条件构建器，同时支持表达式语法 `order.amount > 10000 && part.type == 'KEY'`） |
| **并行网关** | 分支并行执行，全部到达后合并（可配置"任一到达即合并"） |
| **包容网关** | 满足条件的分支执行 |
| **子流程** | 嵌套引用其他流程定义，支持参数传递 |
| **等待节点** | 等待外部事件（如等 ECN 确认回执）或定时器 |

**驳回策略（可配置于流程/节点级）**：

- 驳回到上一节点 / 驳回到发起人 / 驳回到指定节点；
- 驳回后重新提交：从驳回点继续（保留已审记录）/ 重新走全流程（可配置）。

### 6.4 表单设计器

- 基于 Formily 的拖拽设计器：布局容器（栅格/卡片/表格）+ 30+ 基础组件（输入、选择、日期、人员选择器、物料选择器、附件、子表单）；
- 字段绑定数据源（物料分类、部门树、字典）；
- 校验规则可视化配置（必填、正则、联动显隐、跨字段校验）；
- 表单 Schema（JSON）与流程定义解耦，可复用（同一表单挂到多个流程）；
- 业务对象字段注入：表单可引用上下文对象（如 ECR 的受影响物料列表，只读渲染）。

### 6.5 流程定义生命周期

```
草稿(Draft) → 已发布(Published) → 新版本迭代中 → 已下线(Retired)
```

- **版本管理**：流程包（流程+表单+规则）整体作为一个版本，每次发布生成新版本号；
- **兼容性校验**：发布前校验新版本与在途实例的兼容性（节点删除/ID 变更检测），不兼容时强制选择"在途实例按旧版本走完"（默认，快照机制：实例启动时保存流程定义快照）；
- **灰度发布**：新版本可按"部门/项目/百分比"灰度生效，观察无问题后全量；
- **流程测试**：提供"沙箱试跑"，用模拟数据走一遍流程，各节点显示将路由到谁。

### 6.6 流程运行时能力

- **超时处理**：节点级配置超时时长与动作（提醒/自动通过/自动驳回/升级上级）；
- **代理设置**：用户休假期间设置审批代理，任务自动转发并标记"代理人处理"；
- **催办与撤回**：发起人可催办（站内+邮件）；首个节点未处理前可撤回；
- **流程监控**：管理员视图——在途实例、卡在哪个节点、各节点平均耗时、瓶颈分析报表；
- **审计追溯**：每次流转记录（谁、何时、动作、意见、耗时、IP）不可篡改。

### 6.7 AI 与流程的结合点

| 结合点 | 机制 |
|--------|------|
| AI 预审节点 | 提交时 AI 检查表单与制度合规性，不合格给出修改建议（拦截或提示可配置） |
| 智能路由建议 | AI 节点输出建议分支变量，条件网关消费；关键流程默认"AI 建议 + 人确认" |
| 审批辅助 | 审批界面侧栏 AI 摘要差异与历史驳回原因（见 4.7） |
| 流程挖掘 | 基于历史实例做瓶颈/异常检测（如某节点平均 5 天，建议拆分或加人） |

---

## 7. 数据库设计

> 以下为核心表设计（PostgreSQL，均含标准审计字段：`created_by, created_at, updated_by, updated_at, deleted`（软删）, `tenant_id`）。表名前缀按服务拆分。

### 7.1 物料与 BOM

```sql
-- 物料主表
CREATE TABLE part (
    id              BIGINT PRIMARY KEY,
    part_number     VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    name_en         VARCHAR(255),
    type            VARCHAR(20) NOT NULL,          -- RAW/STANDARD/MADE/...
    category_id     BIGINT NOT NULL REFERENCES part_category(id),
    attrs           JSONB DEFAULT '{}',            -- 分类属性模板的动态属性
    unit            VARCHAR(8),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version         VARCHAR(16) NOT NULL DEFAULT 'A/1',
    security_level  VARCHAR(10) DEFAULT 'PUBLIC',  -- 密级
    search_vector   TSVECTOR                       -- 全文索引列
);
CREATE INDEX idx_part_attrs ON part USING GIN (attrs);
CREATE INDEX idx_part_fts ON part USING GIN (search_vector);

-- 物料版本历史（每次发布固化一行）
CREATE TABLE part_version (
    id           BIGINT PRIMARY KEY,
    part_id      BIGINT NOT NULL,
    version      VARCHAR(16) NOT NULL,
    snapshot     JSONB NOT NULL,                   -- 全字段快照
    change_ref   BIGINT,                           -- 关联ECO
    released_by  BIGINT, released_at TIMESTAMPTZ,
    UNIQUE (part_id, version)
);

-- BOM 头
CREATE TABLE bom (
    id           BIGINT PRIMARY KEY,
    parent_part_id BIGINT NOT NULL,
    bom_type     VARCHAR(10) NOT NULL,             -- EBOM/PBOM/MBOM/SBOM
    version      VARCHAR(16) NOT NULL,
    lifecycle_state VARCHAR(20) NOT NULL,
    UNIQUE (parent_part_id, bom_type, version)
);

-- BOM 行
CREATE TABLE bom_line (
    id             BIGINT PRIMARY KEY,
    bom_id         BIGINT NOT NULL REFERENCES bom(id),
    child_part_id  BIGINT NOT NULL REFERENCES part(id),
    quantity       NUMERIC(14,4) NOT NULL CHECK (quantity > 0),
    ref_des        TEXT,                           -- 位号，逗号分隔
    usage_type     VARCHAR(10) DEFAULT 'NORMAL',   -- NORMAL/ALTERNATE/OPTIONAL
    find_number    INT,
    effective_from DATE, effective_to DATE,
    process_route_id BIGINT,
    attrs          JSONB DEFAULT '{}'
);
```

### 7.2 文档管理

```sql
CREATE TABLE doc_info (
    id           BIGINT PRIMARY KEY,
    doc_number   VARCHAR(32) NOT NULL UNIQUE,
    title        VARCHAR(255) NOT NULL,
    doc_type_id  BIGINT NOT NULL,                  -- 文档类型（挂模板/流程）
    lifecycle_state VARCHAR(20) NOT NULL,
    version_major CHAR(2), version_minor INT,
    security_level VARCHAR(10) DEFAULT 'PUBLIC',
    checked_out_by BIGINT, checked_out_at TIMESTAMPTZ,
    attrs        JSONB DEFAULT '{}'
);

CREATE TABLE doc_file (                            -- 物理文件（一个文档版本可多文件）
    id           BIGINT PRIMARY KEY,
    doc_version_id BIGINT NOT NULL,
    file_name    VARCHAR(255),
    storage_key  VARCHAR(512) NOT NULL,            -- MinIO对象键
    file_size    BIGINT, sha256 CHAR(64),
    preview_key  VARCHAR(512),                     -- 转换后预览文件键
    cad_extract  JSONB                             -- CAD解析结果（结构/属性）
);
```

### 7.3 变更管理

```sql
CREATE TABLE change_request (                      -- ECR
    id            BIGINT PRIMARY KEY,
    request_number VARCHAR(32) UNIQUE,
    title         VARCHAR(255) NOT NULL,
    reason        TEXT, expected_benefit TEXT,
    urgency       VARCHAR(10),
    affected_items JSONB,                          -- 受影响对象引用列表
    impact_analysis JSONB,                         -- AI+人工合并的影响分析
    state         VARCHAR(20) NOT NULL,
    process_instance_id BIGINT
);

CREATE TABLE change_order (                        -- ECO
    id            BIGINT PRIMARY KEY,
    order_number  VARCHAR(32) UNIQUE,
    request_id    BIGINT REFERENCES change_request(id),
    execute_plan  JSONB,
    state         VARCHAR(20) NOT NULL,
    process_instance_id BIGINT
);

CREATE TABLE change_record (                       -- 变更执行明细（对象级）
    id           BIGINT PRIMARY KEY,
    order_id     BIGINT NOT NULL,
    target_type  VARCHAR(20),                      -- PART/BOM/DOC
    target_id    BIGINT, before_version VARCHAR(16), after_version VARCHAR(16),
    executed_at  TIMESTAMPTZ, executed_by BIGINT
);

CREATE TABLE change_notice (                       -- ECN
    id            BIGINT PRIMARY KEY,
    notice_number VARCHAR(32) UNIQUE,
    order_id      BIGINT NOT NULL,
    notified_roles JSONB, effective_date DATE, state VARCHAR(20)
);
```

### 7.4 流程引擎

```sql
CREATE TABLE workflow_def (                        -- 流程定义（版本化流程包）
    id           BIGINT PRIMARY KEY,
    def_key      VARCHAR(64) NOT NULL,             -- 业务标识如 part-release
    name         VARCHAR(128) NOT NULL,
    version      INT NOT NULL,
    status       VARCHAR(20) NOT NULL,             -- DRAFT/PUBLISHED/RETIRED
    bpmn_xml     TEXT NOT NULL,
    form_schema  JSONB,                            -- 关联表单Schema
    rule_config  JSONB,                            -- 超时/通知/驳回策略
    gray_config  JSONB,                            -- 灰度发布配置
    UNIQUE (def_key, version)
);

CREATE TABLE workflow_instance (
    id           BIGINT PRIMARY KEY,
    def_id       BIGINT NOT NULL REFERENCES workflow_def(id),
    def_snapshot JSONB NOT NULL,                   -- 定义快照（保证在途实例稳定）
    biz_type     VARCHAR(30) NOT NULL,             -- ECR/PART_RELEASE/DOC...
    biz_id       BIGINT NOT NULL,
    biz_form     JSONB,                            -- 表单数据
    variables    JSONB,                            -- 流程变量
    state        VARCHAR(20) NOT NULL,             -- RUNNING/SUSPENDED/COMPLETED/...
    current_nodes JSONB,
    started_by   BIGINT, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ
);
CREATE INDEX idx_wfi_biz ON workflow_instance (biz_type, biz_id);

CREATE TABLE workflow_task (
    id           BIGINT PRIMARY KEY,
    instance_id  BIGINT NOT NULL,
    node_id      VARCHAR(64) NOT NULL,
    node_name    VARCHAR(128),
    task_type    VARCHAR(20),                      -- APPROVE/SIGN(counter-sign)/AI/SERVICE
    assignee_id  BIGINT, candidate_role VARCHAR(64),
    action       VARCHAR(20),                      -- APPROVE/REJECT/RETURN/DELEGATE/ADDSIGN
    comment      TEXT, attachments JSONB,
    ai_suggestion JSONB,                           -- AI审批建议快照
    due_at       TIMESTAMPTZ, acted_at TIMESTAMPTZ,
    agent_of     BIGINT                            -- 代理处理时记录原审批人
);
```

### 7.5 知识库

```sql
CREATE TABLE knowledge_item (
    id            BIGINT PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    space_id      BIGINT NOT NULL,
    source_type   VARCHAR(20) NOT NULL,            -- DOC/CHANGE_CASE/FAQ/...
    source_ref    JSONB,                           -- 溯源 {type,id,url}
    summary       TEXT,
    entities      JSONB, tags JSONB,
    quality_score NUMERIC(5,2) DEFAULT 60,
    usage_count   INT DEFAULT 0, positive_fb INT DEFAULT 0, negative_fb INT DEFAULT 0,
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    auto_created  BOOLEAN DEFAULT FALSE,
    version       INT DEFAULT 1,
    security_level VARCHAR(10) DEFAULT 'PUBLIC'
);

CREATE TABLE knowledge_chunk (
    id            BIGINT PRIMARY KEY,
    item_id       BIGINT NOT NULL,
    chunk_index   INT,
    content       TEXT NOT NULL,
    vector_id     VARCHAR(64) NOT NULL,            -- Milvus中的向量主键
    ref_location  JSONB                            -- 溯源位置(页码/段落)
);

CREATE TABLE knowledge_feedback (
    id           BIGINT PRIMARY KEY,
    query_text   TEXT NOT NULL, query_vector_ref VARCHAR(64),
    result_item_id BIGINT, position INT,
    action       VARCHAR(20),                      -- CLICK/ADOPT/DISMISS/CORRECT/RATE
    user_id      BIGINT, created_at TIMESTAMPTZ
);
-- Milvus Collection: knowledge_chunk_vec
--   字段: vector_id(INT PK), embedding(FLOAT_VECTOR 1024), item_id, space_id,
--         security_level, status, created_at   ← 标量过滤实现检索期权限过滤
```

### 7.6 AI 平台

```sql
CREATE TABLE ai_prompt (                           -- Prompt版本化仓库
    id BIGINT PRIMARY KEY,
    prompt_key VARCHAR(64) NOT NULL,               -- bom-clean-mapping 等
    version INT NOT NULL, status VARCHAR(10),
    template TEXT NOT NULL,                        -- {{变量}} 占位
    variables JSONB, model_config JSONB,
    UNIQUE (prompt_key, version)
);

CREATE TABLE ai_job (                              -- 异步AI任务
    id BIGINT PRIMARY KEY,
    job_type VARCHAR(40) NOT NULL,                 -- DOC_PARSE/BOM_CLEAN/IMPACT_ANALYSIS
    biz_ref JSONB NOT NULL, payload JSONB,
    state VARCHAR(20), result JSONB, confidence NUMERIC(4,3),
    need_review BOOLEAN DEFAULT FALSE,
    cost_tokens INT, latency_ms INT,
    created_at TIMESTAMPTZ, finished_at TIMESTAMPTZ
);
```

### 7.7 系统元数据与 AI 数据操作（支撑 5.3 / 4.7）

```sql
-- 系统元数据知识（Schema 同步，L1 层知识的结构化存储）
CREATE TABLE meta_table_def (
    id            BIGINT PRIMARY KEY,
    table_name    VARCHAR(64) NOT NULL UNIQUE,
    service_owner VARCHAR(32),
    description   TEXT,                        -- 业务含义（注释 + 人工润色）
    security_level VARCHAR(10) DEFAULT 'PUBLIC',
    ai_operable   JSONB DEFAULT '{}',          -- {"select":true,"insert":true} 白名单矩阵
    status        VARCHAR(10) DEFAULT 'ACTIVE',-- ACTIVE/ARCHIVED(表已删除)
    vector_id     VARCHAR(64),                 -- 表结构描述文本的向量ID(schema_vec)
    version       INT DEFAULT 1                -- 元数据版本，同步一次+1
);

CREATE TABLE meta_column_def (
    id           BIGINT PRIMARY KEY,
    table_name   VARCHAR(64) NOT NULL,
    column_name  VARCHAR(64) NOT NULL,
    data_type    VARCHAR(64), nullable BOOLEAN,
    description  TEXT,                         -- 业务含义
    dict_ref     VARCHAR(64),                  -- 枚举字典引用
    sensitive    BOOLEAN DEFAULT FALSE,        -- 敏感字段 → AI 查询时屏蔽/脱敏
    status       VARCHAR(10) DEFAULT 'ACTIVE',
    UNIQUE (table_name, column_name)
);

CREATE TABLE meta_relation (
    id           BIGINT PRIMARY KEY,
    from_table   VARCHAR(64), from_column VARCHAR(64),
    to_table     VARCHAR(64), to_column VARCHAR(64),
    rel_type     VARCHAR(10),                  -- FK / LOGICAL(人工标注的隐式关联)
    cardinality  VARCHAR(10)                   -- 1:1 / 1:N / N:N
);

CREATE TABLE schema_change_log (               -- 结构变更历史（三事件源统一入此表）
    id           BIGINT PRIMARY KEY,
    source       VARCHAR(20) NOT NULL,         -- FLYWAY/EVENT_TRIGGER/DAILY_AUDIT
    ddl_text     TEXT,
    object_type  VARCHAR(20), object_name VARCHAR(64),
    change_type  VARCHAR(10),                  -- CREATE/ALTER/DROP
    synced       BOOLEAN DEFAULT FALSE,        -- 是否已完成知识同步
    impacted_ai_funcs JSONB,                   -- 反向影响分析结果
    created_at   TIMESTAMPTZ
);

-- AI 数据操作审计（4.7 的执行记录，永久保留）
CREATE TABLE ai_sql_execution (
    id            BIGINT PRIMARY KEY,
    user_id       BIGINT NOT NULL,             -- 发起用户（责任主体）
    session_id    VARCHAR(64),                 -- AI 会话上下文
    nl_request    TEXT NOT NULL,               -- 用户自然语言指令
    channel       VARCHAR(10) NOT NULL,        -- API / SQL
    sql_text      TEXT, api_ref VARCHAR(255),
    risk_level    VARCHAR(4),                  -- L1~L4
    reviewed_by   BIGINT, reviewed_at TIMESTAMPTZ,  -- 用户确认时刻（L2/L3 必填）
    affected_rows INT, status VARCHAR(20),
    rollback_sql  TEXT, rollback_deadline TIMESTAMPTZ,
    cost_ms INT, error_msg TEXT,
    created_at    TIMESTAMPTZ
);
CREATE INDEX idx_ai_sql_exec_user ON ai_sql_execution (user_id, created_at DESC);
-- Milvus Collection: schema_vec
--   字段: vector_id(INT PK), embedding(FLOAT_VECTOR),
--         table_name, service_owner, status   ← 供 Text-to-SQL 召回相关表
```

---

## 8. API 设计规范

### 8.1 通用约定

- 风格：REST，资源名复数 kebab-case；版本置于路径 `/api/v1/...`；
- 认证：`Authorization: Bearer <JWT>`；WebSocket/SSE 走相同鉴权；
- 统一响应体：

```json
{ "code": 0, "message": "ok", "data": { }, "trace_id": "..." }
```

- 分页：`?page=1&page_size=20`，响应含 `total`；
- 幂等：写操作支持 `Idempotency-Key` 头；
- 长任务：统一返回 `job_id`，通过 `GET /api/v1/ai/jobs/{id}` 或 SSE 订阅进度；
- 错误码：`0` 成功；`1xxx` 参数；`2xxx` 认证权限；`3xxx` 业务规则；`4xxx` 资源状态；`5xxx` 系统。

### 8.2 关键接口示例

```
# 物料
POST   /api/v1/parts                     创建物料(草稿)
POST   /api/v1/parts/batch-import        Excel导入(返回AI清洗job)
GET    /api/v1/parts/{id}/where-used     反查使用处
POST   /api/v1/parts/{id}/submit-release 提交发布(触发流程)

# BOM
GET    /api/v1/boms/{id}/expand?level=all&date=2026-08-23   展开
POST   /api/v1/boms/compare              {bomA, bomB} → Diff
POST   /api/v1/boms/{id}/publish         发布并推送ERP

# 文档
POST   /api/v1/docs/{id}/check-out       检出
POST   /api/v1/docs/{id}/check-in        检入(新版本)
GET    /api/v1/docs/{id}/versions        版本链

# 变更
POST   /api/v1/changes/requests          创建ECR
POST   /api/v1/changes/requests/{id}/impact-analysis    触发AI影响分析
POST   /api/v1/changes/requests/{id}/approve            评审(触发流程动作)

# 流程
POST   /api/v1/workflow/defs             上传流程包(发布新版)
GET    /api/v1/workflow/tasks/my         我的待办
POST   /api/v1/workflow/tasks/{id}/action   {action: APPROVE|REJECT|ADDSIGN|..., comment}
POST   /api/v1/workflow/instances/{id}/preview-route    试算路由

# 知识库
POST   /api/v1/knowledge/items           手动录入
POST   /api/v1/knowledge/feedback        反馈 {item_id, action}
GET    /api/v1/knowledge/graph?center=part:1001&depth=2   图谱查询

# AI
POST   /api/v1/ai/jobs/doc-parse         {doc_file_id, schema_key}
POST   /api/v1/ai/chat                    对话(SSE流式返回)
GET    /api/v1/ai/jobs/{id}               任务状态与结果

# AI 数据操作（4.7）
POST   /api/v1/ai/data/query              自然语言查询(L1只读, 经SQL安全网关)
POST   /api/v1/ai/data/plan               生成操作计划+确认卡片(仅生成不执行)
POST   /api/v1/ai/data/execute            用户确认后执行写操作(L2/L3)
GET    /api/v1/ai/data/executions/{id}    执行详情(含回滚接口, 窗口期内)

# 系统元数据（5.3）
GET    /api/v1/meta/tables                表/字段/关系查询
POST   /api/v1/meta/sync/reconcile        触发对账(管理员)
GET    /api/v1/meta/changes               结构变更历史与同步状态

# 搜索
GET    /api/v1/search?q=耐高温密封&types=part,doc,knowledge   混合搜索
```

---

## 9. 前端设计

### 9.1 技术与工程

- React 18 + TypeScript + Vite；状态：Zustand（客户端）+ TanStack Query（服务端）；
- UI：Ant Design 5，主题 Token 定制支持白标；
- 复杂组件：bpmn-js（流程设计器）、Formily（表单设计器/渲染器）、自研 BOM 树表格（虚拟滚动支撑 10 万行）、WebGL 查看器（CAD 轻量化）；
- 图表：ECharts（甘特图、看板、报表）；
- 实时：WebSocket（待办/协作提示）+ SSE（AI 流式输出）。

### 9.2 页面结构

```
┌──────────────────────────────────────────────┐
│ 顶栏: Logo │ 全局搜索(语义) │ 消息 │ AI助手 │ 用户 │
├────────┬─────────────────────────────────────┤
│        │  工作台(待办/快捷/最近/推荐知识)        │
│  侧边  │  物料中心 │ BOM 工作台(左右树+详情)     │
│  导航  │  文档中心 │ 变更中心 │ 项目中心          │
│ (可配) │  知识门户 │ 流程中心(设计器/监控)        │
│        │  管理后台(组织/权限/编号/字典/集成)      │
└────────┴─────────────────────────────────────┘
右侧抽屉: AI 助手(全局常驻, 上下文感知当前页面)
```

### 9.3 关键交互

- **AI 助手上下文感知**：在 BOM 页打开助手时自动注入当前物料上下文，可直接问"这个件有哪些替代料"；
- **AI 建议组件规范化**：统一的 `<AISuggestion>` 组件（置信度标识、来源引用、采纳/忽略按钮），全站一致体验；
- **低代码设计器**：流程设计器属性面板双向绑定 bpmn-js 元素；表单设计器实时预览（移动端/桌面切换）；
- **性能**：路由级代码分割；BOM 大树虚拟滚动 + 懒加载展开；搜索防抖 + 服务端聚合。

---

## 10. 安全与权限体系

### 10.1 认证

- 账号密码 + 验证码；支持对接企业 SSO（OAuth2/OIDC/SAML/LDAP）；
- JWT（Access 2h + Refresh 8h），Refresh Token 旋转；关键操作（审批、发布）二次确认。

### 10.2 授权模型（RBAC + ABAC 混合）

- **RBAC**：角色（系统管理员/物料管理员/工程师/审核员…）→ 权限点（菜单、按钮、API）；
- **ABAC 数据权限**：数据可见范围 = 分类权限（物料/文档分类树授权）× 密级 × 组织范围（本部门/全部/自定义），SQL 层统一注入过滤；
- **AI 权限继承**：AI 中台所有数据访问以"当前用户身份"执行，AI 无独立超权；知识检索在向量库元数据层过滤密级与空间；
- **AI 数据操作权限**：AI 查询/写入的有效权限 = 用户业务权限 ∩ AI 操作权限点（`ai:data:query` / `ai:data:write`）∩ 表白名单矩阵，三者取交集；写操作按 L1~L4 分级确认（见 4.7.3）；数据库侧使用专用最小权限账号执行；所有 AI 数据操作以**发起用户为责任主体**，全量审计、窗口期内可回滚。

### 10.3 数据安全

- 传输 TLS 1.3；敏感字段（手机号等）存储加密；
- 文件下载全链路审计（谁、何时、哪个文件、IP）；
- 水印：预览页动态水印（用户名+时间）；
- 大模型数据安全：私有化部署选项（图纸/工艺等敏感数据不出域）；调用外部模型时经脱敏代理（正则+NER 脱去人名、客户名、编号）；
- 备份：数据库每日全备 + WAL 增量；MinIO 跨机房复制；恢复演练季度化。

### 10.4 审计合规

- 不可变审计日志（追加写，独立存储），覆盖登录、权限变更、对象生命周期操作、审批、下载、AI 调用；
- 满足可追溯性要求（如 ISO 9001 / 汽车行业 IATF 16949 对记录保持的要求），支持按对象导出完整追溯报告（版本链+变更链+审批链）。

---

## 11. 非功能性需求

| 维度 | 指标 |
|------|------|
| 并发 | 1000 在线用户 / 200 并发操作（单集群），水平扩展无状态 |
| 响应 | 页面首屏 < 2s；列表查询 P95 < 800ms；BOM 万行展开 < 3s；AI 流式首 Token < 2s |
| 可用性 | 核心服务 99.9%；AI 中台可降级不影响主流程 |
| 数据量 | 物料 500 万、文档 2000 万、BOM 行 1 亿级设计容量 |
| 兼容 | Chrome/Edge 最近两个大版本；分辨率 1366+ 自适应 |
| 国际化 | i18n 框架（中/英），时区与多币种预留 |
| 可观测 | 全链路 TraceId 贯穿（含 AI 调用）；核心接口 100% 打点 |

---

## 12. 部署方案

### 12.1 环境拓扑

- **环境**：DEV / TEST / STAGING / PROD 四套，配置中心（Nacos）管理差异；
- **生产拓扑（K8s）**：

```
Ingress(Nginx) → gateway(2副本+) → 各业务服务(2副本+, HPA按CPU)
独立节点池: AI推理服务(GPU节点, vLLM私有化部署时) / CAD转换(CPU密集)
有状态: PostgreSQL(主从+PgBouncer) / Redis(哨兵) / RocketMQ(DLedger)
        Milvus集群 / Neo4j(因果集群) / ES(3节点) / MinIO(分布式4节点+)
```

### 12.2 CI/CD

- GitLab CI：单测 → 构建镜像 → Trivy 扫描 → DEV 自动部署 → TEST 自动化回归 → STAGING 手动确认 → PROD 滚动/灰度（Argo Rollouts）；
- 数据库变更：Flyway 版本化迁移，禁止手工改库；
- 前端：CDN 静态资源 + HTML 不缓存策略。

---

## 13. 开发里程碑

| 阶段 | 周期 | 交付内容 |
|------|------|----------|
| **M1 基础平台** | 第 1–6 周 | 认证授权、组织、编号规则、网关、CI/CD、基础组件库 |
| **M2 核心 PLM** | 第 7–14 周 | 物料、BOM（含 CAD 解析）、文档管理、版本检入检出、预览 |
| **M3 流程引擎** | 第 11–18 周（部分并行） | 流程/表单设计器、任务中心、变更 ECR/ECO/ECN 全流程 |
| **M4 AI 中台 v1** | 第 15–22 周 | AI 网关、文档解析、BOM 清洗、混合搜索、AI 助手 v1 |
| **M5 知识库 v1** | 第 19–26 周 | 采集管道、RAG、知识门户、事件自动沉淀、反馈闭环（规则版） |
| **M6 完善 & 上线** | 第 27–32 周 | 项目管理、报表、集成 ERP、性能压测、安全测试、试点上线 |

**团队配置建议**：前端 3、后端 Java 5、AI/Python 3、算法 1、测试 2、UI 1、项目经理 1。

**风险与对策**：

| 风险 | 对策 |
|------|------|
| CAD 格式解析覆盖不全 | 优先 Top5 格式；长尾格式降级为"仅存储+手动建 BOM" |
| LLM 结构化输出不稳定 | JSON Mode + Schema 校验 + 失败重试降级模板 |
| 知识库冷启动无内容 | 预置制度文档导入 + 种子 FAQ；前 3 个月重点运营 |
| 流程设计器易用性不达预期 | 模板市场（预置 20+ 常用流程模板）降低上手门槛 |
| AI 生成内容误判 | 全场景"AI 建议 + 人决策"，关键写操作永不自动执行 |
| AI 直写 SQL 绕过业务校验 | API 通道优先 + SQL 安全网关五层校验 + 白名单默认全关 + L4 高风险禁止 + 数据库专用低权限账号 |
| Schema 变更未同步导致 AI 生成错误 SQL | 三事件源保底（迁移钩子/数据库触发器/每日对账），漂移即告警并强制重同步 |

---

## 14. 术语表

| 术语 | 全称 | 说明 |
|------|------|------|
| PLM | Product Lifecycle Management | 产品生命周期管理 |
| BOM | Bill of Materials | 物料清单；EBOM 设计/PBOM 工艺/MBOM 制造/SBOM 服务 |
| ECR/ECO/ECN | Engineering Change Request/Order/Notice | 变更申请/执行/通知 |
| RAG | Retrieval-Augmented Generation | 检索增强生成 |
| BPMN | Business Process Model and Notation | 业务流程建模标准 |
| RBAC/ABAC | Role/Attribute-Based Access Control | 基于角色/属性的访问控制 |
| 检入/检出 | Check-in/Check-out | 文档版本锁机制 |
| 会签 | Counter-sign | 多人并行审批的表决方式 |
| LTR | Learning to Rank | 学习排序（知识重排） |
| Text-to-SQL | — | 自然语言生成 SQL 的技术 |
| SQL 安全网关 | — | AI 生成 SQL 的五层校验执行组件（语法/对象/权限/规则/执行，见 4.7.2） |
| Schema 漂移 | Schema Drift | 数据库实际结构与知识库元数据记录不一致 |
| IATF 16949 | — | 汽车行业质量管理体系标准 |
