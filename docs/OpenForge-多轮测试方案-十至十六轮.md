# OpenForge PLM 多轮测试方案（第十~十六轮）

> 制定日期：2026-09-19 ｜ 依据：`docs/OpenForge-会话交接.md` 一~九轮测试记录（PR 表第 65~71 行）+ 已知技术债表（第 113~124 行）
> 用法：按轮执行；每轮结束按 §五 模板回填交接文档 PR 表。本方案只定"测什么、怎么判"，执行时以 diff 为准（工程约定 #7）。

---

## 一、背景与依据

### 1.1 前九轮回顾（详见交接文档 PR 表）

| 轮 | 主题 | 主要发现 | 状态 |
|----|------|---------|------|
| 一 | 全模块体检 + 异常分流 | GlobalExceptionHandler 三分流缺陷（404 误判/类型不匹配/DB 掉线日志洪水） | 已修，未发版 |
| 二 | 深度测试（低代码/负向/双租户） | 流程引擎 act() 动作未归一化→实例静默挂起；405 分流缺失 | 已修，未发版 |
| 三 | 业务深水区（BOM/变更/图纸/知识库） | 输入错误族五类（畸形 JSON/缺参落 5000） | 已修，未发版 |
| 四 | 收敛测试 | 零新发现（R1:3→R2:2→R3:2→R4:0） | 收敛 |
| 五 | 安全与调度面 | **drawing 域审计覆盖缺口**（零 DRW_* 埋点）；mono bean 撞名 | 已修，未发版 |
| 六 | 并发与契约 | TOCTOU 实测无害；快照语义实锤；运维教训（栈运行中 mvn install 毒化 JVM）；OpenAPI 抽查 18/29 paths | 记录在案 |
| 七 | 前端真实用户流 | window.prompt 原生对话框→改 antd Modal | 已修，未发版 |
| 八 | 数据一致性 | drawing create `@Valid` 纯装饰从未生效（超长字段 5000） | 已修，未发版 |
| 九 | 故障韧性 | connector 强杀/PG 重启均自愈 | 通过 |

### 1.2 状态性风险（本方案 R16 处置）

**一~九轮全部修复（含 #101/#103/#105/#116）均标注"未发版"，main 停在 v1.20.0。** 修复只存在于 dev，任何从 main/Release 部署的环境都不含这些修复。R16 以 v1.21.0 发版预演收口。

### 1.3 已覆盖面盘点（新一轮不重复）

异常分流八类错误族、跨域业务闭环（ECR/BOM/物料冻结/图纸升版/文档往返/知识检索）、双租户**列表级**隔离、并发取号与 TOCTOU、在途实例快照语义、1.5MB 大文件往返、登录锁定、AI 网关（LIMIT/表白名单/离线降级）、审计埋点（CONN_*/DRW_*）、CRON×CHAIN 调度、故障注入（进程强杀/PG 重启）、轻压测与 RSS 画像、浏览器级 15 页巡检与 MyTasks 审批流、暗色模式、sha256 一致性、改密全流程。

### 1.4 空白清单（本方案主攻）

| # | 空白 | 说明 |
|---|------|------|
| G1 | **水平越权（IDOR）** | 二轮只实锤"租户 2 列表见 0"，从未按对象 ID 跨租户直取/改/删 |
| G2 | 文件与存储安全 | 只测过正常文件往返；类型伪装/文件名注入/存储-DB 对账未测 |
| G3 | 备份恢复演练 | 从未做过 pg_dump→恢复→对账 |
| G4 | 契约补全 | OpenAPI 仅抽查 18/29；前端 13 个 api/*.ts 与后端 DTO 从未逐字段比对 |
| G5 | 数据生命周期 | LogRetentionJob/ExecLogRetentionJob 从未真跑；孤儿数据无探针 |
| G6 | 前端深化 | 七轮只覆盖 MyTasks 一页的审批流；错误态/空态/控制台 error 无系统性巡检 |
| G7 | 运维与部署面 | docker-compose.prod 从未冷启动验证；helm 从未渲染断言；Grafana 告警规则为已知债（技术债表"随部署环境补"） |

---

## 二、总体策略

1. **不重复已覆盖面**：每轮回归只跑 `./scripts/smoke.sh`（35 断言）作为基线，已实锤的场景不重测，只回归"本轮改动波及面"。
2. **优先级排序**：安全面（R10/R11）> 数据面（R13）> 契约面（R12）> 体验面（R14）> 运维面（R15）> 收敛发版（R16）。真栈单机串行执行（RSS 画像约束 + 约定 #4 jar 锁），R15 需先 `dev-down`（端口/资源互斥）。
3. **收敛判据**：连续两轮零 P0/P1 新发现可提前收尾（R4 先例）；反之某轮爆出 P0 则在后续轮追加同类探针直至收敛。
4. **纪律继承**（约定编号见交接文档 §工程约定）：
   - 构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"` + `MAVEN_OPTS="-Xmx768m -XX:+UseSerialGC"`（约定 #3）
   - 栈运行中禁 `mvn package/install`（约定 #4，六轮毒化事故先例）
   - multipart 上传统一 `cygpath -w` 转路径（约定 9.5）
   - 前端自动化点击用 `evaluate` 程序化 `.click()`（约定 #9）
   - 每个确认的 bug：修复 + 回归测试钉住 + conventional commit + 交接文档回填，缺一不可
   - 视觉验证按用户 AGENTS.md 档位路由，批量截图合并派发视觉子代理，结论附证据

---

## 三、轮次总览

| 轮 | 主题 | 主攻空白 | 前置环境 |
|----|------|---------|---------|
| R10 | 水平越权与授权深度 | G1 | dev-up full 真栈 |
| R11 | 文件与存储安全 + 备份恢复 | G2、G3 | 真栈 + data/ 目录 |
| R12 | 契约一致性 | G4 | 真栈（导出 openapi.json） |
| R13 | 数据生命周期与清理 | G5 | 真栈 + 可控时间数据 |
| R14 | 前端全页面体检深化 | G6 | 真栈 + 前端 dev server :5173 |
| R15 | 运维与部署面 | G7 | dev-down + docker compose prod |
| R16 | 收敛轮 + 发版预演 | §1.2 风险收口 | 全栈 |

---

## 四、分轮详细方案

### R10 水平越权与授权深度（IDOR）

**为什么现在**：二轮实锤的是列表级隔离（租户 2 列表为空）。行级按 ID 直取从未验证——如果某 Mapper 漏挂租户拦截器或动态表漏显式过滤，列表查不到但按 ID 仍可操作，这是多租户最经典的渗透路径。

**探针清单**（每条格式：操作 → 预期）：

| # | 探针 | 预期 |
|---|------|------|
| T10-1 | 租户 A 建物料/文档/图纸/变更单/项目/知识条目 → 租户 B JWT 按 ID GET/PUT/DELETE | 404（不可见且不泄露存在性，不能 403 泄露"存在但无权"以外的信息——统一 404 亦可，关键是**返回成功或泄露数据即 P0**） |
| T10-2 | 租户 A 的流程任务 ID → 租户 B / 非指派人 act() | 拒绝（403/4004 族），不产生状态变更 |
| T10-3 | drawing/doc 文件 ID 跨租户走流式下载端点 | 404，无字节回流 |
| T10-4 | 连接器/凭据/AI Provider ID 跨租户 GET/试运行/轮换密钥 | 404；密钥轮换绝不跨租户生效 |
| T10-5 | 经网关访问各服务 `/api/v1/internal/**`（MaterialClient 执行端点等）全枚举 | 2001/401（smoke 只断言了一条，此处全量枚举） |
| T10-6 | 绕过网关直连业务服务端口（8081~8095）带伪造 `X-User-Tenant`/`X-User-Name` | 记录取证。服务若信任网关头=本机 dev 可直通——按部署假设定级（compose 内部网络下非缺陷则记 P3 录入技术债表；若服务间内部端点也无 INTERNAL_TOKEN 校验则 P1） |
| T10-7 | JWT 操纵四件套：过期 token / 篡改签名 / tenant 声明改大数字 / 删 tenant 声明 | 全部 401，绝不降级为默认租户放行 |
| T10-8 | 角色权限矩阵遍历：无角色用户 × 各域 manage 端点（conn:manage、ai:manage、drawing 权限种子 V27/V28、admin 端点） | 统一 2004/2003 门禁，无漏网端点 |
| T10-9 | 低代码动态表（dyn_*）跨租户 CRUD（二轮测过列表，这里按 ID 直取 + 动态表显式 tenant_id 过滤验证） | 404 |

**方法**：curl 负向探针脚本化（可沉淀 `scripts/authz-probe.sh`，与 smoke.sh 同风格），双租户 JWT 由注册流程现造。
**收尾**：smoke 35/35 + 全服务日志 0 新 ERROR + 发现逐条按 §五 分级。

### R11 文件与存储安全 + 备份恢复演练

**探针清单**：

| # | 探针 | 预期 |
|---|------|------|
| T11-1 | 类型伪装：`.exe`/`.sh` 改名 `.pdf` 上传、伪造 Content-Type | 记录当前行为并按安全假设定级；至少不可执行落点、预览不触发下载执行 |
| T11-2 | SVG 内嵌 `<script>` 上传后走内嵌预览 | 预览上下文无脚本执行（XSS）——drawing/文档预览是平台首类内嵌渲染，属真实风险面 |
| T11-3 | 0 字节文件 / 超大文件（>服务端上限）/ 断点截断文件 | 优雅拒绝（1000 族），不产生半截 DB 记录或孤儿文件 |
| T11-4 | 文件名注入：`../`、`..\`、unicode RTL 覆盖符、超长名、纯空格、含 CRLF/引号（Content-Disposition 注入） | 净化或拒绝；物理落盘路径不逃逸 `data/`；下载响应头无注入 |
| T11-5 | 同档案重复上传同名文件 | 明确语义（版本化保留 vs 覆盖），且不破坏既有快照文件延续（三轮已验升版快照保留，此处测同版本重传） |
| T11-6 | 存储对账：`data/doc-files/`、`data/drawing-files/` 物理文件 vs DB 记录双向对账（孤儿文件/悬空记录） | 产出对账脚本（可沉淀 `scripts/storage-reconcile.py`）；发现孤儿即溯源归属（R11 本身制造删除场景验证是否遗留） |
| T11-7 | 备份恢复演练：`pg_dump` 全库 → 恢复到新库 → 服务指向恢复库 → smoke 35/35 + 关键数据比对（图纸档案/文件记录/取号水位） | 数据零丢失；取号水位恢复后不重号 |
| T11-8 | 文件目录整体拷贝（本地盘存储无 MinIO，备份必须含 `data/`）→ 恢复后下载字节级一致 | sha256 一致 |

**注意**：T11-7/8 演练会动本地库，先 `dev-down` + 导出，再恢复验证，完成后回切。
**产出**：备份恢复操作手册一节（可附于本文档 §七 或交接文档），这是 G3 首次闭环。

### R12 契约一致性

**探针清单**：

| # | 探针 | 预期 |
|---|------|------|
| T12-1 | 导出各服务 `/v3/api-docs`，补齐六轮未抽查的剩余 paths（18/29 已查） | 文档 vs 实际行为逐条一致；偏差即修注解或修行为 |
| T12-2 | 前端 13 个 `frontend/src/api/*.ts` 与后端 DTO 逐字段比对（重点：分页结构 PageResponse——#101 曾实锤 records/size 回归） | 字段名/可空性/分页结构全一致；重点回归**所有列表页**的分页取值路径 |
| T12-3 | 错误码前端处理路径：对 1000/2001/2003/2004/3007/3009/4001/5000 逐个从前端触发 | 每个码都有用户可见提示，无静默失败、无裸 message 弹堆栈 |
| T12-4 | 幂等性抽查：建单双击重复提交 / 检入重复 / 审批重复 act / 连接器手动触发连点 | 后端状态机拒绝（4001 族）且前端有 loading 防抖；不产生重复单据 |
| T12-5 | 枚举/字典漂移：前端写死的枚举（状态色点、trigger_type、change_type）vs 后端常量 | 无漂移（#108 曾有角色漂移 ecr-review 教训——v1 角色指向已更名角色导致任务无人可见） |

**方法**：T12-1 用 curl 拉取 openapi.json 后程序化比对；T12-2 以 TypeScript 类型定义 vs Java DTO 字段清单逐项核对（可派子代理做机械比对，主模型复核偏差）。

### R13 数据生命周期与清理

**探针清单**：

| # | 探针 | 预期 |
|---|------|------|
| T13-1 | LogRetentionJob（180 天/每日 03:30/分批 500）真跑：造旧数据（回拨 created_at 或临时调短保留期配置）→ 手动触发/等调度 → 断言删除且分批不锁表 | 删除准确；批间不阻塞业务写入 |
| T13-2 | ExecLogRetentionJob（connector 执行日志 + DLQ 终态保留期）真跑 | 同上；PENDING 死信**不**被清理 |
| T13-3 | 审计日志（sys_audit_log）是否在保留期覆盖内 | 明确答案并记录；若永不清理由本轮定性（记技术债，不强行当轮扩需求） |
| T13-4 | 孤儿探针一：删除/禁用物料 → 其 BOM 行、where-used、drawing 关联的行为 | 与设计一致（禁用而非物理删则验证 FROZEN 拒新增已有 D6 守卫——此处验证删除路径是否存在且受控） |
| T13-5 | 孤儿探针二：删除 drawing 档案 → 物理文件、发布快照文件、物料关联行 | 无悬空记录；文件清理语义明确（保留快照文件则对账脚本 T11-6 应能解释） |
| T13-6 | 删除流程定义 → 在途实例 | 快照语义保证在途按旧版本走完（六轮已实锤版本部署窗口，此处验证**删除**是否被禁止或有同等保护） |
| T13-7 | 深分页/排序边界：page 超大、size 0、非法排序字段（order by 注入面） | 200 钳制或优雅拒绝；排序字段白名单/参数化，无 SQL 拼接 |
| T13-8 | 有效期三态边界：validityStatus 即将过期/恰好过期的计算口径 | 与《替代件与主数据变更设计》三态定义一致 |

### R14 前端全页面体检深化

**范围**：七轮只深测了 MyTasksPage。本轮对其余页面逐页过"四态"：正常流 / 错误态（后端 500/断网/403）/ 空态 / 暗色模式。重点页面（历史巡检未深触）：

| 页面 | 重点场景 |
|------|---------|
| IntegrationPage | 编排链画布（v1.18 分支可视化）错误表达式保存拦截、死信 Tab 空态、触发器表单校验 |
| MetaObjectsPage / ObjectDataPage / LayoutDesignerPage | 建模→布局→数据录入全闭环；动态表单校验错误展示；保存后列表即时刷新 |
| SecurityLogPage | 分页回归（#101 修过 PageResponse，此页是首发页）、筛选条件组合 |
| ModuleAdminPage / RoleAdminPage / UserAdminPage | 权限变更即时生效、模块 BROKEN 状态展示 |
| KnowledgePage | 检索空结果态、AI 离线时的提示语义 |

**方法与纪律**：
- 浏览器真实用户流（约定 #9 合并门），`evaluate` 程序化 click
- 每页截图存 `shots/r14/<page>-<step>.png`，批量合并派发视觉子代理核验（按用户 AGENTS.md 路由；检查项写明"四态各自预期"）
- 每页开 DevTools 控制台：**零 error**（console.error/未捕获 Promise 拒绝）为通过线；warning 记录不阻断
- 产出"页面 × 场景 × 结果"清单表，作为后续自动化补课（可选项，见 §六）的基线资产

### R15 运维与部署面

| # | 探针 | 预期 |
|---|------|------|
| T15-1 | `docker-compose.prod.yml` 全栈冷启动（`.env.example` 补密钥）→ smoke 35/35 | 一次成功；失败即 prod 编排缺陷（#105 修过 Dockerfile 通配符断裂，此类缺陷只在此面暴露） |
| T15-2 | `helm template` 渲染 + `helm lint` 断言（无集群环境则止于渲染断言） | 渲染零错误；values 覆盖链生效 |
| T15-3 | monitoring 栈起（`docker-compose.monitoring.yml`）→ Prometheus targets 全 12 服务 UP → Grafana 两看板（openforge-jvm / openforge-gateway-events）逐面板有数据 | 无空面板；无 DOWN target |
| T15-4 | 告警规则缺口验证（技术债表："告警规则/通知渠道随部署环境补"） | 产出**最小告警规则集清单**（服务 DOWN / 5xx 率 / RSS 越界 / DLQ 积压 / PG 连接池），以 prometheus rule 片段形式给出，随环境启用——技术债首次给出可落地件 |
| T15-5 | 前端生产镜像 nginx.conf：SPA 路由 fallback、/api 代理、静态资源缓存头 | 刷新深链接不 404；API 不被缓存 |
| T15-6 | prod 态日志：容器日志驱动/轮转策略确认 | 记录现状；dev /tmp 无轮转属已知 dev 限定，不混判 |

**注意**：R15 与 dev 栈端口/资源互斥，执行前 `./scripts/dev-down.sh`；结束回切 dev 验证 smoke 后才算收尾。

### R16 收敛轮 + 发版预演

1. **回归清单**：从交接文档提取一~九轮全部已修 bug（异常分流三分流+405+缺参+畸形 JSON+超长字段、act() 归一化、PageResponse、动态路由 RefreshRoutesEvent、设计器只读坐标兜底、window.prompt→Modal、DRW 审计五埋点、bean 撞名显式命名等）逐项回归——每一项都有钉住测试的跑测试，只有现场修复记录的补现场探针。
2. **十~十五轮全部修复**同表回归。
3. **全量收尾**：`mvn verify` 16 模块 + 前端 build + ai pytest + smoke×3 + 全服务日志 0 ERROR + RSS 对比画像 §8 + hs_err 零新增。
4. **发版预演（收口 §1.2 风险）**：按约定 #1 走 v1.21.0——release PR → main（merge commit）→ tag → GitHub Release → 回灌 dev。Release Notes 载荷 = 一~十五轮全部修复 + 本方案产出物（探针/对账脚本/告警规则片段/备份手册）。
5. 交接文档"当前状态快照"与版本历史同步刷新，未发版行批量改为已随 v1.21.0 发布。

---

## 五、bug 分级与每轮执行模板

### 5.1 分级

| 级 | 定义 | 处置 |
|----|------|------|
| P0 | 数据丢失/越权成功/主流程阻断 | 当轮必修 + 回归测试钉住 |
| P1 | 功能错误且无绕行 | 当轮必修 + 回归测试钉住 |
| P2 | 边界缺陷/体验缺陷，有绕行 | 当轮或次轮修复 |
| P3 | 按部署假设/规模判定暂不修 | 记录不修，入交接文档技术债表（#105 先例），写明重启条件 |

### 5.2 每轮执行模板

```
起栈（dev-up full，等 health UP）
→ 探针执行，逐条记录（操作/预期/实际/判定级别）
→ 修复 + 回归测试钉住 + conventional commit
→ 收尾四件套：smoke 35/35 ｜ 全服务日志扫 ERROR ｜ RSS 记录 ｜ hs_err 检查
→ 交接文档 PR 表回填一行（格式对齐既有轮次记录，含"未发版（随下版带上）"标注）
```

### 5.3 退出标准

- 该轮探针清单全过（含探针本身产出物：脚本/清单/手册落盘）
- 无新增 P0/P1 遗留
- 交接文档已回填

---

## 六、可选后续（不属本方案范围，执行完再议）

- **前端测试基线**：R14 产出的"页面×场景"清单可作为 vitest + RTL 选型的用例蓝本（当前前端零自动化测试，CI 前端 job 只构建）
- **契约自动化**：T12-1/T12-2 的比对脚本化后可入 CI
- **越权探针脚本化**：T10 系列沉淀为 `scripts/authz-probe.sh` 后可并入 smoke 或独立合并门
- **ESLint/Checkstyle**：仓库无 lint 配置，随自动化补课一并评估

## 七、R11 备份恢复操作手册（T11-7/8 执行时落笔）

（占位：执行 R11 时按实际步骤写入，含 pg_dump 参数、恢复验证断言清单、回切步骤。）

---

## 八、执行记录（2026-09-19，R10~R13 已执行）

### 8.1 发现与修复（全部已修 + 回归测试钉住 + 运行时复验）

| # | 级 | 发现 | 修复 | 验证 |
|---|----|------|------|------|
| F1 | P0 | 用户管理零租户边界：租户1 实测删掉租户0 用户（update/disable/reset-password/batch/assignOrg 同面）；sys_user 全局表（登录需跨租户寻址）拦截器不覆盖，服务层未自守 | UserAdminService.require() 租户守卫 + page() 租户过滤 + OrgService.assignUserOrg 同规；跨租户一律 404 防存在性泄露 | 活靶标 DELETE→404/4001 且行无恙；UserTenantBoundaryServiceTest 4 项 |
| F2 | P0 | GET /api/v1/users 无权限注解（无角色可列全部用户）+ SysUser.passwordHash 直接序列化下发 | page() 补 @RequirePermission("user:manage")；passwordHash @JsonIgnore 全局脱敏（覆盖 create/update/enable/disable/角色 members 等全部 SysUser 响应） | 无角色→2004；响应 0 处 passwordHash；Jackson 断言 |
| F3 | P1 | workflow_instance 无租户维度，任意租户按 ID 读他租户实例（含 defSnapshot/variables）；WorkflowClient（change→workflow）不透传租户 | V3 迁移补 tenant_id + 引擎写入打戳 + instance()/findByBiz 租户校验 + WorkflowClient 补透传 | 跨租户 GET→404、同租户 200、租户1 ECR 实例 tenant_id=1；WorkflowTenantIsolationTest 3 项 |
| F4 | P1 | 非零租户取号必失败：sys_number_rule 种子全 tenant 0 却被租户过滤（探针连带发现，新租户开箱即坏） | sys_number_rule 升 GLOBAL_TABLES（平台模板，计数器本就全局） | 租户1 建 ECR 取号成功端到端；NumberRuleTenantVisibilityTest |
| F5 | P1 | workflow_def 同病：租户0 部署的定义非零租户 4001 | 升 GLOBAL_TABLES（定义=平台模板；实例/任务按租户戳+指派人隔离） | definitionSharedAcrossTenants 测试 + 租户1 ECR 端到端 |
| F6 | P2 | AI 网关 chat 裸响应偏离 ApiResponse 包络，前端 aiChat 恒抛 ApiError、AI 助手必挂（R12 契约比对发现） | main.py chat 统一 _ok() 包络 | 经网关实测 {"code":0,...}；pytest 32 全绿 |
| F7 | P2 | AI 网关注册后无心跳→staleModules→经网关 404 | 45s 幂等重注册保活线程（对齐 JVM ModuleRegistrar） | module-routes staleModules=[]；chat 经网关 200 |
| F8 | P2 | drawing 上传超长文件名落 5000+ERROR（varchar(255) 溢出；运行中 jar 早于八轮全局分流，且源头无校验） | DrawingService.uploadFile 源头 255 收口→1000 | 实测 1000 精确提示；DrawingIntegrationTest 钉住 |

**记录不修（P3，按部署假设/产品模型定性）**：直连服务端口可匿名以租户 0 读写（信任链=网关头是既定设计，prod compose 仅暴露 gateway:80 与前端:80）；workflow/knowledge internal 端点落在网关已注册前缀内（INTERNAL_TOKEN 门禁，prod 必改默认令牌——.env.example 已载）；connector 列表/详情无权限注解（租户内读，与 DLQ 不一致）；零字节文件被接受；软删档案的物理文件保留（对账脚本可解释）。

### 8.2 实测通过面（本轮不重复既有覆盖）

网关伪头剥除（大小写变体+双头冒充）、JWT 篡改/过期/alg=none 全 401、双租户列表隔离全域、跨租户文件下载 404、internal 缺令牌 401、无角色权限矩阵（manage 端点全 2004）、存储对账（盘库一致、UUID 落盘无路径逃逸、tenant/ 前缀无越出）、SVG 下载强制 attachment/octet-stream（无内嵌脚本执行路径）、Content-Disposition 无注入、ECR 跨域链路（双租户）、保留期任务真跑（200 天旧行按期清除；审计/登录/connector 日志均在覆盖内，PENDING 死信不清理）、smoke 35/35、全服务 0 ERROR、hs_err 零新增、RSS 2292MB 持平四轮基线。

### 8.3 沉淀物

- `scripts/authz-probe.sh`：越权与租户隔离探针 13 断言（自造靶标/自清理，SUSPECT>0 退出码 2）
- 回归测试 4 个：UserTenantBoundaryServiceTest（4）、NumberRuleTenantVisibilityTest（1）、WorkflowTenantIsolationTest（3）、DrawingIntegrationTest 超长文件名（1）

### 8.4 遗留与后续

- **R11 残留**：T11-7/8 备份恢复演练（pg_dump→恢复→对账）未执行（需停栈窗口，见 §七 占位）
- **R12 残留**：metadata 模块 PageResponse 用 `items` 偏离全局 `list` 约定（前端已适配，建议后续统一，技术债级）
- **R14/R15/R16 未执行**：前端四态深化巡检、prod compose/helm/监控告警面、收敛轮+v1.21.0 发版预演（一~十轮全部"未发版"修复随版带上）
