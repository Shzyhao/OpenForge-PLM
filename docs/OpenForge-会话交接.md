# OpenForge PLM 会话交接文档

> 最后更新：2026-09-20（v1.23.0 发版收口）｜ 本文档由 Agent 会话结束前写入，下个会话开始时先读本文件恢复上下文

## 当前状态快照

| 维度 | 值 |
|------|-----|
| 最新发布版 | **v1.23.0**（tag + GitHub Release；站内通知中心 · 审批委托 · 回收站 + EVENT_BUS 真栈 RocketMQ 修复） |
| dev 最新 | 与 main 同步（v1.23.0 发版后回灌，91305b7）；无在途功能 |
| main vs dev | v1.23.0 合入后同步（PR #119，91305b7） |
| 工作区 | 干净；本地 admin 密码 smoke-test-2026（dev 库）；冒烟 `./scripts/smoke.sh`（10 业务域 + 连接器/触发/死信/审计 + **图纸域 6 断言，共 35 项**） |
| 全量测试 | CI 全绿；drawing DrawingIntegrationTest 4 + mono MonoSmoke **10 模块** + connector 68；真实栈 11 服务（drawing :8095）；v1.20.0 浏览器级巡检（图纸菜单/三 Tab/PDF 内嵌预览/关联交互/状态流转，DOM 断言）|

## v1.3.0 → 当前完成的全部工作（按 PR 序）

| PR | 内容 | 版本 |
|----|------|------|
| #61 | v1.3.0 README 版本引用回灌 dev | — |
| #62 | 性能调优（SerialGC/Hikari/Tomcat/缓存上界/查询下推/分包）+ 画像文档 + 自检清单 | — |
| #63 | 性能知识制度化——PR 模板合并门 + Loop 验证器 + CONTRIBUTING | — |
| #64 | B2 事件总线设计（信封/拓扑/幂等/outbox 分期） | — |
| #65 | compose 依赖可选化（Redis/MinIO extras、rocketmq/nacos profile、doc 数据卷修复） | — |
| #66 | B2-1 EventPublisher（信封/熔断/lazy producer/HTTP 回退）+ V23 事件表 | — |
| #70 | B2-2 事件消费者 + knowledge 自动沉淀 + 发布流水线事件优先 + 真实 MQ Testcontainers | — |
| #71 | B2-3 三域事件发射（doc/change/task） | — |
| #72 | B2 性能画像 + payload 截断 + 章节重排交叉引用 | — |
| #73 | README/CONTRIBUTING 同步交付态 | — |
| #74 | B1 Nacos 配置中心九服务接入（默认关闭 + 回路测试挂门） | — |
| #75 | **v1.5.0 发布**（B1 配置中心 → main + tag + Release + 回灌） | v1.5.0 |
| #76 | Grafana 看板模板（JVM 总览 + 网关/事件总线六计数） | — |
| #77 | UI 深度主题化（品牌 token/暗色模式/品牌化登录页/工作台仪表盘/分组菜单/Logo） | — |
| #78 | B2-P2 outbox 可靠性（事务内原子落库 + relay 补发 + 死信语义） | — |
| #79 | **v1.6.0 发布**（UI 主题化 + outbox → main + tag + Release + 回灌） | v1.6.0 |
| #80 | pgvector 向量存储切换 + 向量租户过滤（SQL 级 + 行级双保险 + Testcontainers 回路） | v1.7.0 |
| #81 | **v1.7.0 发布**（pgvector → main + tag + Release + 回灌） | v1.7.0 |
| #82 | 流程可视化设计器——自研 SVG 画布零依赖（bpmn-js 否决，见架构决策 6） | v1.8.0 |
| #83 | **v1.8.0 发布**（设计器 → main + tag + Release + 回灌） | v1.8.0 |
| #84 | 元数据 TTL 缓存（租户键/30s 可配/500 上界/afterCommit 驱逐）+ 日志保留期清理（180 天可配/分批 500） | v1.9.0 |
| #85 | **v1.9.0 发布**（技术债清偿 → main + tag + Release + 回灌） | v1.9.0 |
| #86 | 本地开发瘦身：#62 JVM 调优实装（曾只落 MAVEN_OPTS）+ 构建智能跳过 + PROFILE 预设 + yml 池变量化 + WSL 2GB | 已随 v1.21.0 发布 |
| #87 | 瘦身二刀：Nacos import 空载 + AppCDS（gateway 实测启动 -36%/3173 类共享，CDS=0 可关）+ START_PARALLEL=2 分批并行 | 已随 v1.21.0 发布 |
| #88 | 测试侧 Nacos 关闭：八服务 test yml 显式 enabled:false——消除 JVM 退出每上下文 ~10s 阻塞（CI 实证） | 已随 v1.21.0 发布 |
| #89 | 全文档对齐 v1.9.0 交付态：架构 v1.1/开发 v1.1/B2 已交付态/二开 v1.1/路线后交付表/MAS 落地标注/README 快速开始 | 已随 v1.21.0 发布 |
| #90 | 冒烟修复四处交付缺陷：路由缺 RefreshRoutesEvent（动态路由从未生效）/裸端口 URI/KERNEL 自注册被防劫持拒（metadata 连坐 BROKEN）/knowledge yml 重复键；RSS 实测 1.86GB 回写画像 | v1.10.0 |
| #91 | **v1.10.0 发布**（瘦身 + 冒烟修复 → main + tag + Release + 回灌） | v1.10.0 |
| #92 | BROKEN 模块静默摘除可观测性——module-routes 端点/health details 暴露 brokenModules 与原因（依赖未启用: xxx）+ auth 守护日志 module broken/recovered 落地（F2 设计 3.4 首次以 diff 为准） | v1.11.0 |
| #93 | Nacos 回路测试修复——**publish 读可见性竞态实锤**（零间隔 A/B 一次 NULL 一次 OK，与版本错配/网络方案无关）+ getConfig 退避重试 + 复用模式补发布 + 固定端口容器（替代 host 网络）+ test yml import 默认翻空（8 服务 loader 空载消除）+ 镜像 v2.4.3（v2.3.2/v2.2.3 libtinfo 损坏无法启动）+ **ci.yml NACOS_LOOP_TEST 常开** | v1.11.0 |
| #94 | 流程设计器只读预览遗留定义坐标兜底——浏览器级冒烟实锤：#82 前部署的定义无 x/y，查看路径未走自动布局，节点全叠 (0,0) 仅 END 可见 | v1.11.0 |
| #95 | v1.11.0 冒烟证据入册：网关链路 8/8 域 + AI 网关离线四接口 + CDS 业务服务 A/B 校准（收益 ~2-4%）+ 约定 #9（前端交付合并门=浏览器级真实打开） | v1.11.0 |
| #97 | mono 单进程模式设计刀——mono-8 + 独立 gateway 两刀方案（gateway 因 WebFlux/WebMVC 自动配置互斥不并入；13 处跨服务调用矩阵 + bean/资源冲突面全量取证） | 已随 v1.21.0 发布 |
| #98 | **mono 刀 1 骨架实施**：资源目录化（db/migration/<svc>/ + module/<svc>.yml，解 8 组同名根资源遮蔽）+ 多 Flyway 实例 + ModuleRegistrar 参数化（8 实例多心跳）+ NumberClient×4 显式命名 + 双拦截器去重 + exec classifier ×9 + PROFILE=mono；**实测 RSS 405MB（-78%）+ 网关链路 8/8 域等价**（见 mono 设计 §4.1） | v1.12.0 |
| — | 刀 2（进程内直调）**评估完成：不实施**——5 组回环均有 TTL 缓存或低频、本机 <1ms，刀 1 实测无相关瓶颈；直调化需侵入 4 服务客户端或脆弱子类覆写，风险不成比例（mono 设计 §3.2 判定入册） | — |
| #101 | **全页面浏览器级巡检**（约定 #9 扩展：15 界面逐页真实打开）实锤安全日志分页结构回归——MP Page 直返（records/size）致前端 list undefined（"暂无数据"而总数正常）；修统一 PageResponse + 回归测试；README 快速开始补 PROFILE=mono | 已随 v1.21.0 发布 |
| #103 | 网关链路冒烟脚本化——scripts/smoke.sh 13 项断言一键复跑（约定 #8 工具化，full/mono 通用，负向自检防假绿） | 已随 v1.21.0 发布 |
| #105 | **系统代码评审**（子代理全量 diff + 抽查交叉，12 发现）修复六项：生产 Dockerfile 通配符断裂（P1，自 v1.12.0）/ INTERNAL_TOKEN 双键漂移（轮换即全 401）/ mono 回环死锁窗口（Tomcat 40）/ Nacos 测试残留清理 / 守护求值定点 UPDATE / 自检列表原子替换；记录不修：路由 TOCTOU、producer 域粒度、smoke.sh bash3.2、测试固定端口 | 已随 v1.21.0 发布 |
| #107 | **替代件专项刀1**——BOM 替代组从表（替代组 API/校验矩阵/单行上限）+ 行号 position + 升版 revise（A/1→A/2 深拷贝）+ diff 补齐（位号/用量类型/属性/替代组，多类型并存按行号对位）+ D7 引用收紧（草稿件不可被引用）+ BomPage 重写（行管理/替代组面板/展开树标注）；设计文档《OpenForge-替代件与主数据变更设计》v1.0 入册；真实链路实锤 compare 未改动双版本空 types 越界 500 并修复 | v1.13.0 候选 |
| #108 | **刀2 统一变更中心 + 刀3 有效期三态**——change_type/payload/apply_state（审批与执行分离 D6）：替代组变更（服务端权威 before 快照+富化）与物料禁用启用（where-used 影响清单）审批通过即执行（material /api/v1/internal/** 执行端点+MaterialClient 内部令牌+租户透传），失败落因可重试；FROZEN/PHASED_OUT 拒绝新增引用；validityStatus 四态下发（lines/expand/where-used）+ 前端变更中心/入口按钮/色点徽标；**真实链路实锤修复 MaterialClient where-used URI 缺 query 占位符** + dev 库 ecr-review v1 角色漂移（ADMIN→已更名 ADMINS 任务无人可见）重部署 v2 | v1.13.0 候选 |
| #109 | **v1.14.0 发布**（集成编排器 MVP 五刀 → main + tag + Release） | v1.14.0 |
| — | **P2-1 AI API 配置器**（afd2d2b）：ai_provider 表（V2 迁移，AES-GCM 复用凭据加密）+ 管理 API（/api/v1/ai-providers，ai:manage V26）+ /internal/ai-provider/chain（内部令牌，返回解密 key）+ ai-gateway provider_chain 30s 轮询热加载 + LLMClient 链式降级（连接异常/5xx/429 切换）+ 前端 AI 模型 Tab | 已随 v1.21.0 发布 |
| — | **P2-2 事件/定时触发 + 死信 + R8 审计**：trigger_type/trigger_json（V3 迁移，随版本快照）+ TriggerSpecs 校验（EVENT 主题白名单六主题可配/CRON Spring 6 段秒位禁裸 *）+ CronTriggerScheduler（跨租户 @InterceptorIgnore 全量重同步 + 自续链调度 + 代际签名防复活）+ ConnectorEventConsumer（FIRST_OFFSET+启动时间闸门防回放历史/防启动窗口丢失——CI 实纱 LAST_OFFSET 对新消费组 rebalance 前消息永久错过；失败不 RECONSUME——幂等行判重使 broker 死信不可达，改落应用级死信）+ sys_connector_dlq（PENDING/RESOLVED/DISCARDED + 重放原样重投 + 终态保留期清理并入 ExecLogRetentionJob）+ DLQ API（/api/v1/connectors/dlq，conn:manage）+ auth POST /api/v1/internal/audit + AuthAuditClient afterCommit 尽力而为上报（连接器/凭据/AI 供应商 manage 全落 sys_audit_log）+ 前端触发表单与死信队列 Tab + 冒烟第 5 节 5 断言；**平台修复**：spec checkUrl 与 EgressGuard 对 URL 模板占位符 {{param}} 的误拒（哑元替换后解析，host 位占位符仍无法命中白名单）；**测试**：TriggerSpecsTest 8 + ConnectorTriggerIntegrationTest 4（CRON 真实调度/事件直调分发/死信全生命周期/跨租户扫描）+ ManageAuditIntegrationTest（假 auth 捕获 + 503 不阻断）+ ConnectorEventBrokerLoopTest（CI 真实 MQ） | v1.15.0 |
| — | **material 事件化 + 密钥轮换（R1）**：Part/Bom 状态机 transition 至 RELEASED 发射 part.released/bom.published（topic openforge-material，B2 P3 落地；afterCommit 发送，覆盖审批与变更启用两条路径）+ 连接器 EVENT 触发白名单扩至七主题（后端常量/yml/前端联动）+ material 集成测试事件发射断言；AesGcmCipher 双密钥读（PREVIOUS 回落，GCM 认证失败才回落、损坏密文仍拒）+ KeyRotationService 跨租户批处理重加密（@InterceptorIgnore 游标分批 200/批，逐行原子幂等，损坏行计数跳过）+ 轮换端点（POST /api/v1/connector-credentials/master-key/rotate，conn:manage）+ 轮换审计 CONN_MASTER_KEY_ROTATE + MasterKeyRotationIntegrationTest（独立上下文注入 PREVIOUS：重加密/跳过/损坏计数/幂等四断言）+ smoke 白名单断言；二开指南补轮换四步操作手册 | v1.16.0 |
| — | **多步骤编排四刀**（详见设计文档 §14 与 v1.17.0）：链引擎（ChainSpecs/ChainExecutor/ExecutionGateway/steps_json）+ 画布泛化（节点类型注册表）+ 编排画布页 + 分支求值（SpEL 下沉 common 沙箱化） | v1.17.0 |
| #113 | **编排分支可视化编辑**（纯前端，后端零改动）：STEP 注册表 branchAnchor/ruleTargetCheck/ruleModalHint/expandsRules + validate；FlowDesigner 分支锚点交互 + 分支边自橙锚出发 + 连线抽屉表达式编辑（工作流 CONDITION 同享）+ Modal 语境分派；chainToFlow branches→rules 零迁移回显，flowToChain 移除 keepBranches 改镜像后端约束导出（只指向步骤/默认分支唯一/未连主线拦截）；浏览器级巡检双路求值 + 工作流回归 + 视觉子代理核验 | v1.18.0 |
| — | **十二轮结构优化**（用户直觉"整体太大"的实测回应）：**prod 默认形态翻转为 mono**——10 业务服务挂 scale-out profile，新增 mono 服务段（2 JVM 进程）；gateway/ai-gateway 寻址走 AUTH_SERVICE_URI env（mono=http://mono:8090 / scale-out=http://auth:8081，.env 切换）。**prod mono 冷启动验证 21/21**：10 业务域穿透/图纸域容器卷全链/连接器全拒语义；五容器 RSS ~700MB（mono 301+gw 266+PG 66+AI 61+前端 5）vs full 2292MB **-70%**；镜像 4GB→~1.2GB；prod 构建 10 次→2 次。**连带抓到三个真缺陷并修复**：①MonoModuleRegistrarsConfig serviceUri 硬编码进程内端口（#98 dev 语义），容器形态网关按注册表寻址 Connection refused 127.0.0.1:8090→动态路由全 500——补 MODULE_SERVICE_URI 覆盖优先；②connector/drawing actuator 漏 prometheus exposure（监控盲区）；③prod compose drawing 卷命名断裂（- vs _）——config 校验即挂。Dockerfile .m2 BuildKit 缓存挂载（依赖跨构建持久）；容器内 Maven 构建受 WSL2 2GB 限制闪退实锤（约定 #6 新场景：构建需扩内存或走 CI）。AI 镜像 257→240MB（pytest 剥离 requirements-dev）。运维纪律：**容器内构建/运行场景先查 .wslconfig memory** | 已随 v1.21.0 发布 |
| — | **十一轮功能收口**（十轮建议项全部落地，执行记录见《OpenForge-多轮测试方案》§九）：**排查直出四项**——①网关入口剥除 X-Internal-Token（F2 实纱根治：认证用户携默认令牌触达 internal 端点的面归零，实测 workflow internal 2001）；②doc 文件回读端点（此前上传后永远取不回：GET /{id}/files/{fileId}/download 流式下载+归属校验，前端 DocPage 文件抽屉上传/预览/下载，sha256 字节级往返实测）；③连接器级 ACL（V5 迁移 acl_roles 白名单 + invoke 前角色交集校验 + conn:view 读权限 V30 种子（列表/详情补注解，与 DLQ 不一致收口）+ 前端表单白名单字段；空白名单=不限，内部调用不受限）；④scripts/backup.sh 备份+恢复对账演练（R11 T11-7/8 首次闭环：pg_dump+data/ 打包，临时库恢复 11 表行数对账+取号水位一致；高频审计日志表不入严格对账——在线备份窗口漂移实测 3 行）。**半建成收口四项**——⑤组织架构页（OrgController 全量接线：组织树/子组织/重命名/删除/成员挂入移出，GET 补 org:manage）；⑥租户管理页+开通流水线（POST /tenants/onboard：建租户+初始管理员绑 ADMINS+首登强制改密单事务；TenantService 全端点补平台租户(0)守卫——收口 F12：tenant:manage 持有者跨租户搬人/读全租户的洞）；⑦用户管理页批量启停+组织挂接 Select+租户列；⑧编号规则页（规则/段定义渲染/计数器水位 GET /numbers/counters/取号预览，此前只能 SQL 手查）。验证：mvn verify 四模块全绿（新增 ConnectorAclIntegrationTest 3+TenantOnboardServiceTest 3+Doc 下载往返）、npm build 绿、smoke 35/35、authz-probe 13/13、新功能 e2e 探针 13/13、浏览器级六页 DOM 验收（约定 #9）、0 ERROR、hs_err 零新增、RSS 2305MB 持平、invoke 压测 100 次 p50=23ms/p95=30ms 无退化 | 已随 v1.21.0 发布 |
| — | **十轮越权与租户边界排查**（方案与执行记录见《OpenForge-多轮测试方案-十至十六轮》）：跨租户 IDOR 全域探针实锤并修复——①用户管理零租户边界（P0，实测租户1删租户0用户成功：UserAdminService/OrgService 服务层租户守卫，跨租户按不存在应答防存在性泄露）；②GET /users 缺权限注解 + SysUser.passwordHash 直接序列化下发（P0：补 user:manage + @JsonIgnore 全局脱敏）；③workflow_instance 无租户维度可任意跨租户读实例含 defSnapshot/variables（P1：V3 迁移补 tenant_id + 写入打戳 + 读取/byBiz 校验 + WorkflowClient 补 X-User-Tenant 透传——漏传即静默落平台租户）；④**非零租户取号必失败**（P1，探针连带发现：sys_number_rule 种子全 tenant 0 却被租户过滤，新租户建不了任何带编号实体——升 GLOBAL_TABLES 平台模板；workflow_def 同病同修）；⑤AI 网关 chat 裸响应偏离 ApiResponse 包络致前端 AI 助手必挂（R12 契约比对发现：包络统一）+ 注册后无心跳进 staleModules 经网关 404（补 45s 保活线程）；⑥drawing 超长文件名落 5000（输入错误族：源头 255 收口）；JWT 三连/伪头剥除/权限矩阵 2004/双租户列表隔离实测通过；直连服务端口匿名租户0 可读写属信任链设计（prod compose 仅暴露 gateway:80，P3 记录）；沉淀 scripts/authz-probe.sh 13 断言（自清理）；保留期任务首次真跑（200 天旧行按期清除，审计/登录/connector 日志均在覆盖内）；回归：mvn verify auth 72+workflow 16+change 8+drawing 5 全绿、smoke 35/35、全服务 0 ERROR、hs_err 零新增、RSS 2292MB 持平四轮基线 | 已随 v1.21.0 发布 |
| — | **七~九轮持续测试**：七轮前端真实用户流——发现并修复 window.prompt 原生对话框缺陷（审批意见改 antd Modal，现场验证 UI 审批全链路：Modal→填意见→已通过/流程已完成→ECR 惰性回流 APPROVED）；八轮数据一致性——50 取号零重复/page 0·-1 优雅/sha256 一致/改密全流程（改→旧拒→新过→改回）/超长字段 5000 修复（drawing create 补 @Valid——原 @NotBlank/@Size 纯装饰从未生效——+ @Size + DataIntegrityViolation 分流，输入错误族第六类）；九轮故障韧性——connector 强杀→90s 心跳摘除→重启自动恢复（routeMissing 空+业务探针通）、PG 重启 5s 全链路自愈（Hikari 重连）；smoke 三轮全绿 | 已随 v1.21.0 发布 |
| — | **六轮并发与契约测试**：并发竞态实测收敛（网关路径 10 并发检出恰 1 成 9 拒 3007、同任务 10 并发 act 恰 1 成 9 拒 4001——TOCTOU 窗口实测无害）；**在途实例快照语义真栈实锤**（v2 部署前后两实例各按其版本快照派任务：V1审批/用户7 vs V2审批/角色ADMINS）；1.5MB 大文件上传下载字节级一致；OpenAPI springdoc 抽查（18/29 paths）；暗色模式切换+持久化生效；**运维教训**：栈运行中 mvn install 毒化运行中 JVM（NoClassDefFoundError 懒加载失败，纪律④场景实测——已按护栏恢复）；405/缺参新分流实战立功（compare 参数名错误精确提示） | 已随 v1.21.0 发布 |
| — | **五轮安全与调度面测试**：登录锁定实测（5 连错→2005 账号已锁定）、AI data/query 端到端（LIMIT 自动补/DELETE 拒/表白名单含 dyn_ 表）、CRON×CHAIN 组合真跑（定时调度两步链 SUCCESS+steps_json）；**发现并修复审计覆盖缺口**——drawing 域 manage 操作未落审计（对比存量 CONN_* 零 DRW_*）：新增同构 AuthAuditClient + 五埋点（CREATE/DELETE/PUBLISH/OBSOLETE/REVISE）+ 测试断言，现场验证 DRW_CREATE/DRW_PUBLISH 落库；**CI 连带修复**：mono 下 drawing/connector 同名 AuthAuditClient bean 撞名（显式命名，LocalDiskStorage 同例——**教训：新域加跨域同名类必须显式 bean 名**） | 已随 v1.21.0 发布 |
| — | **四轮收敛测试（零新发现）**：替代组 roundtrip（substitutePartId 校验/列表视图字段）、BOM 状态机全守卫实测（草稿锁行编辑/评审驳回回退/审批 3009 拒环——探针故意构造的自引用与间接环均被拦）、revise（A/1→A/2）+ compare diff 正确、drawing 主题 EVENT 触发配置白名单接受（执行缝由 CI ConnectorEventBrokerLoopTest 真实 MQ 覆盖）；终检 smoke 35/35、全服务日志 0 ERROR、RSS 2304MB 稳定、hs_err 零新增。四轮趋势 R1:3→R2:2→R3:2→R4:0 发现，收敛 | 已随 v1.21.0 发布 |
| — | **三轮业务深水区测试**：BOM 全功能实测（D7 草稿拒引用→发布→加行/展开/反查/环检测 3009 报"自身祖先路径"/冻结件 D6 拒新增引用）、PART_STATE 变更执行全链路（建单→审批→自动 apply→APPLIED→物料实冻结 FROZEN，跨服务 MaterialClient）、文档字节级往返、图纸升版链真栈（A/0→revise→B/0 快照保留文件延续）、知识库写入+检索（q 参数命中 0.305 分）、AI doc-parse 降级诚实标注；**修复输入错误族收齐五类**：畸形 JSON/缺必填参数补分流 → 1000（此前落 5000+ERROR 堆栈）；轻压测 100 调用 p50=12ms/p95=26ms、RSS 2252MB 无泄漏、全服务日志 0 ERROR、smoke 35/35 回归 | 已随 v1.21.0 发布 |
| — | **二轮深度测试**：低代码核心闭环实测（建模→DDL→发布→动态 CRUD PATCH 语义全通）、负向与边界（SQL 注入参数化安全/分页 200 钳制/10 并发取号零重复/无角色 2004 门禁）、双租户行级隔离实锤（租户0 见 14 张图纸、租户2 见 0）、AI 网关语义（离线降级回复/LIMIT 强制/表白名单含 dyn_ 表）、ECR 审批执行闭环；**修复流程引擎动作归一化缺陷**（小写 approve 使实例静默挂起——前端发大写故历史未爆，API 直调即触发；act() 归一化+校验+单测 2，存量卡单实例 7 现场修复验证）+ common 405 分流（GET-only 路径打 POST 曾落 5000）；smoke 35/35、全服务日志 0 ERROR、RSS 2301MB 稳定 | 已随 v1.21.0 发布 |
| — | **全模块体检 + 异常分流修复**：后端 16 模块 verify + 前端构建 + AI 语法全绿；11 服务起栈 smoke 35/35；深探针（文档检出检入/物料校验/ECR→流程→待办跨域闭环/项目/知识库/AI 网关离线降级）全通；浏览器巡检 10 页全过（3 页合理空态）；RSS 2.29GB/11 服务符合画像线性（单 JVM 189~220MB 无离群）；**修复**：GlobalExceptionHandler 三分流（404 误判 5000→4001/类型不匹配→1000/DB 掉线日志限噪——09-15 PG 掉线 13h 曾刷 5900+ 行 ERROR 洪水）+ 单测 3 钉住；历史 hs_err（9/6、9/13）为已治理的启动 OOM，本轮零新增 | 已随 v1.21.0 发布 |
| — | **十三轮 v1.22 ECO 联动**（图纸域候选第一刀落地）：drawing.released 事件 → 自动创建联动变更单（GENERIC，走正常审批流，升版决策留给评审——审批与执行分离）。双通道：EVENT_ENABLED=true 走 RocketMQ 消费器 DrawingReleasedEventConsumer；false（dev 默认）走同步 HTTP 回退（ChangeNotifyClient→ChangeInternalController，X-Internal-Token 门禁+租户透传）。payload 扩展 linkedParts；EcrService.autoCreateFromDrawing 幂等键 drawingNumber@version、无关联物料不空建、initiatorId=null 系统发起。**端到端真栈验证**：关联图纸发布 RELEASED→联动 ECR 自动出现（SUBMITTED 入审批流）；空关联不空建。回归 ChangeDrawingLinkIntegrationTest 3 项。教训：宿主机 Maven 后台任务当天持续假死——验证结果以通知/日志双确认，勿凭 status 判死 | 已随 v1.22.0 发布 |
| — | **十四轮连接器扩展包①②③**（长任务书收口，通知类全家桶）：① SMTP_EMAIL（spring-boot-starter-mail；spec host/port/starttls/from/to 多收件人/subject/bodyText 占位；EgressGuard.checkHost 非 HTTP host 校验；JavaMailSender 按 spec 动态装配缓存；凭据 SMTP_PASSWORD）；② DINGTALK_BOT（text/markdown + 官方加签 DingTalkSigner 纯函数固定向量钉死）；③ FEISHU_BOT（官方签名 FeishuSigner——key=ts+
+secret 对空串签，payload 内 sign 字段，与钉钉明确不同）；共享 R6 出站客户端 + check(webhookUrl)；authType 加 SMTP/WEBHOOK_SECRET；前端三配置分支+链步骤类型+凭据下拉。测试 connector 81 全绿（GreenMail 真发信 + 回环 HttpServer 断言 payload/加签）；真栈冒烟：三类型建模/发布/白名单拦截（6011）全通。教训：validateSpec 的 switch 是 checkType 之外的第二个类型分派点（新增类型四处同步：SUPPORTED_TYPES/parseSpec/validateSpec/credential 提取）；git add 列表混入错误参数静默吞 add——提交后必看 changed files 数 | 已随 v1.22.0 发布 |
| #119 | **v1.23.0 三模块发布**（通知中心/审批委托/回收站）：①通知中心落 auth 零新增服务——双通道摄取（MQ 组 openforge-notify 即 B2 预留组落地 / 总线关闭时 workflow NotifyClient HTTP 回退）、task.created→assignee（ROLE 按租户 fan-out≤50）、task.completed→发起人（payload 补 initiatorId/candidateRole）、收件箱 API+铃铛 30s 轮询、/users/options 轻量选人；②审批委托——查询期虚拟收件箱（myTasks 单嵌套 and 并集防 OR 优先级破坏 action IS NULL）、delegated_from 追溯、def_key 范围过滤；③回收站 MVP=物料+图纸（盘点：doc 无删除 API、BOM 无整册删除）。**EVENT_BUS 真栈首跑修 RocketMQ compose 四处缺口**（healthcheck 指向不存在端点恒 52 卡死依赖门/端口未发布/brokerIP1=127.0.0.1/JAVA_OPT_EXT 收堆）——rocketmq profile 此前从未真正起过。收口：verify 绿、smoke 35/35×3 ERROR=0、authz-probe 21 断言、新增 v123-probe 25 断言（MQ 实锤 sys_event_consumed）、docker-audit 净。实纱：两域同名 RecycleMapper → mono bean 冲突第三例（域前缀命名）；MSYS mktemp vs 原生 python 路径分歧；curl 内联中文 ANSI 转码；python `v or ''` 吞 falsy 0 | v1.23.0 |
| #116 | **README 重塑（docs-only）**：9 张真实环境截图入册 docs/screenshots/（登录/工作台/物料/图纸详情 PDF 预览/流程设计器画布/编排链画布/BOM/知识库/AI 助手，主画廊 6 + 折叠 3）+ ASCII 架构框图 → Mermaid flowchart（GitHub 原生渲染）+ 首屏版本流水账折叠为 changelog + 功能矩阵补集成编排器/企业级安全两行 + CI 徽章 | 已随 v1.21.0 发布 |
| #115 | **图纸管理 openforge-drawing（:8095）**：四表 drw_*（档案/三类文件/发布快照/物料关联）+ 编号引擎取号（V29）+ 检入检出 + 状态机（submit/approve/reject/obsolete/revise 大版本升版）+ 发布快照（对齐 PartVersion）+ 流式下载端点（平台首个）+ drawing.released/obsolete 事件入 connector 触发白名单 + 前端 DrawingPage（PDF/图片内嵌预览）+ mono 10 模块 + 权限种子 V27/V28；**dev-up 启动期 OOM 根治**（AppCDS 仅 gateway——业务服务 A/B 收益仅 2-4% 而训练跑瞬时+1 JVM、构建护栏防栈运行中重打包）；实纱：mono LocalDiskStorage bean 名冲突/父 pom 未绑定 repackage goal（漏则无 -exec.jar）/Git Bash curl -F 不做 MSYS 路径转换（smoke cygpath 修复）；设计文档《OpenForge-图纸管理设计》入册 | v1.20.0 |
| #114 | **SSRF 根治（R6，生产前必须项闭环）**：EgressGuard.resolveValidated 解析+校验公共入口 + EgressPinningDnsResolver 挂 httpclient5 DnsResolver（解析即校验·所解即所连，重绑定 TOCTOU 窗口归零）+ OutboundHttpConfig 共享出站客户端（重定向禁用保持）；HttpRestConnector/AiProviderService.test 两路径迁移（重试/1MB 流式截断/脱敏/消息格式等价）；httpclient5 收编（BOM 管版本，JDK HttpClient 无解析器注入点）；EgressPinningDnsResolverTest 5 + connector 68 全绿 + 冒烟 28 全绿；出站脱敏代理通道（与 ai-gateway 外呼合并）仍列路线项 | v1.19.0 |

## 关键架构决策（已实施）

1. **模块注册机制**：openforge-module.yml 自描述 → auth sys_module 注册表 → 网关 DB 动态路由 + 启动自检（route-missing → DEGRADED）→ 依赖守护（BROKEN/4020/4022）→ 前端菜单注册表驱动
2. **事件总线 B2**：EventPublisher（信封 eventId/tenantId/traceId + 熔断 60s + outbox 原子落库）→ EventOutboxRelay（60s 补发）→ AbstractEventConsumer（幂等 sys_event_consumed + 租户回填 + MDC 串联）→ 死信 %DLQ%；EVENT_ENABLED=false 回退同步 HTTP（本地/CI 零依赖）
3. **多租户**：JWT tenant 声明 → 网关 X-User-Tenant → TenantLineInnerInterceptor（全局表清单 GLOBAL_TABLES）→ 动态表显式 tenant_id 过滤 → 文件 tenant/{id}/ 前缀
4. **pgvector**：VectorStore 接口（租户感知）→ InMemory（默认 memory，H2）/ PgVector（vector-store=pgvector，SQL 级租户过滤 + HNSW + @PostConstruct 程序化建表）；compose PG 镜像换 pgvector/pgvector:pg16
5. **性能知识制度化**：画像文档 §5 自检清单（设计期四问/实现期/合并门）→ PR 模板强制勾选 → MAS 验证器 V2+ → CONTRIBUTING 合并门
6. **流程可视化设计器**：自研 SVG 画布（零依赖）——bpmn-js 评估后否决（引擎为自有 JSON 非 BPMN 2.0，XML 双向映射层是纯开销 + bpmn.io 水印条款）；条件出口由 rules[].to 渲染（expr 标注），edges 仅存 START/APPROVAL 顺序流，部署前规范化剥离死边；节点 x/y 随定义 JSON 原样存储（引擎忽略未知字段，集成测试钉住契约）

## 下一步（按优先级）

1. **v1.23.0 已发版**（PR #119 → main + tag + Release + 回灌）。站内通知中心（原候选，双通道摄取）/审批委托/回收站（物料+图纸两域）全部交付。近期候选：回收站彻底删除（需 MinIO 文件 GC 方案）与 BOM/文档域接入（待其删除入口建立）、通知扩展至材料/文档/图纸发布事件（需订阅模型，payload 无收件人语义）、通知保留期清理策略、前端 vitest 基线（R14 清单作蓝本）、生产形态接入 rocketmq profile（prod compose 尚无 MQ 服务，事件总线生产态默认关闭）。其余候选：断点续跑/单步重放、审签接流程引擎（REVIEWING 简化态）、CADConverter 服务端转换预览、MinIO 切换（与 doc 同欠账）、新内置连接器类型、多模型分流、规模化基建随规模信号
2. **单进程 mono 模式**：**刀 1（骨架）已实施并全栈实测（PROFILE=mono）**——mono 224MB + gateway 181MB = **405MB RSS（-78%）**、网关链路冒烟 8/8 域等价，方案与数据见 docs/OpenForge-mono单进程设计.md；**刀 2 评估完成不实施**（回环均有缓存/低频，直调化收益≈零、侵入风险不成比例，见 PR 表与 mono 设计 §3.2）；H2 文件库 dev 模式维持 §8.3 备选不动
3. **连接器与行业模板包**：需外部场景输入
4. **Milvus/Neo4j/ES**：架构文档路线项，随规模引入

### v1.11.0 冒烟证据快照（2026-09-05）

- **网关链路**：login→JWT→注册表 8/8 ENABLED→动态路由 10/10 前缀穿透→业务数据回流（parts/docs/projects/changes/knowledge/workflow/meta 直连断言；boms/part-categories 为 POST-only API 形状，链路同样穿透）；`/actuator/module-routes` 现场返回 `brokenModules:[]`（#92 生效）
- **浏览器级**：登录→注册表驱动菜单 8 模块→工作台真实计数→设计器画布（#94 缺陷发现+修复+现场复验三点坐标断言）。注意：IAB 自动化的 Playwright locator click 与 CUA 坐标 click 均不触发 React 合成事件——用 `evaluate` 程序化 `.click()` 可靠
- **AI 网关（离线模式）**：healthz llm_online=false / chat 降级提示 / sql validate LIMIT 强制+表白名单 / doc-parse degraded 规则抽取 / NL2SQL 按设计要求在线配置
- **CDS 校准**：业务服务 A/B 收益仅 ~2-4%（详见画像 §8.3），gateway -36% 为无 DB 特例

## 工程约定（全程遵守，遇新坑追加）

1. feature 分支 → PR 到 dev → CI 三语言全绿 → squash 合并；release PR → main（merge commit）→ tag → GitHub Release → 回灌 dev
2. 每刀 `mvn verify` 全绿 + 相关测试；Testcontainers 不可注释
3. 本机 JAVA_HOME 指向 JDK 8，构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"`
4. 运行中 JVM 锁 jar——重打包前先停进程
5. **性能自检**：PR 模板合并门强制（内存上界/聚合下推/默认值显式/调度格式/环境画像），详见 docs/OpenForge-性能与容量画像.md §5
6. Windows 注意：WSL2 `.wslconfig` **默认 2GB**（仅 PG；extras/rocketmq/nacos 场景 4GB，模板有注）；Docker Desktop 闪退（wsl.exe 0xc00000fd 栈溢出）处置=完整杀进程（Docker Desktop/com.docker.backend）后重启，必要时 `wsl --shutdown` 先行
7. **文档断言「已落地」必须以 diff 为准**（#62 教训：commit message 称服务 JVM 已加 SerialGC/Xss512k，实际只落 MAVEN_OPTS，服务 JVM 跑了三版默认 G1——#86 才实装，见性能画像 §8.2）
8. **合并门前必须有真实网关链路冒烟**（#90 教训：MockMvc/Testcontainers 直连测不出网关动态路由/注册表链路缺陷——动态路由自 A4 交付以来从未真实生效，直到 #90 首次全链路冒烟才暴露；凡动网关/模块注册/路由，冒烟为合并门强制环节；**一键执行：`./scripts/smoke.sh`**——登录→注册表自检→8 业务域穿透 13 项断言，dev-up 后即可跑，full/mono 通用，含负向自检）
8. GitHub 间歇 502/startup_failure：空提交重触发 / close+reopen / 等待平台恢复；stacked PR 基分支被删连坐关闭 → rebase + 重建 PR
9.5. **Git Bash curl 实纱**：`-F "file=@/tmp/x;filename=y"` 复合参数不做 MSYS 路径转换（Windows curl 读不到 POSIX 临时路径，静默空响应）；`--data-binary @/tmp/x` 纯路径形态可以。multipart 上传统一 `cygpath -w` 转换后传 Windows 路径（smoke.sh 图纸节先例）
9. **前端交付合并门 = 浏览器级真实打开**（#94 教训：设计器只读预览自 #82 交付以来从未被真实打开——构建绿 + locator 断言测不出"节点全叠原点"这类视觉缺陷；自动化时 React 合成事件对 locator/CUA click 无响应，用 `evaluate` 程序化 `.click()`）；#101 扩展：**新页面交付走全页面巡检**（15 界面逐页，检查错误提示/表格渲染/空态一致性）——实锤了分页结构这类"构建绿但用户可见坏"的接口契约缺陷
10. **大版本测试/更新收尾必做 Docker 资产盘点**：`./scripts/docker-audit.sh` 分析 + `clean` 清理（十二轮实测 12.7GB→1.8GB：失败构建缓存 6.3GB/判停路线 ES 2GB/废弃选型 rabbitmq/悬空层/孤儿卷）。判定锚点已脚本化——保留=运行中容器引用 + `openforge*` 自建产物，其余（含 compose 有定义但零运行的 extras 镜像）一律清掉，需要时重拉分钟级；**先分析后 clean，clean 前过目孤儿卷清单**

## 已知技术债 / 遗留

| 项 | 说明 |
|----|------|
| Nacos 回路测试 Harness | **已清偿** #93——publish 读可见性竞态实锤（退避重试），CI NACOS_LOOP_TEST 常开合并门（容器模式固定端口） |
| optional:nacos import 副作用 | **已清偿** #93——8 服务 test yml import 默认翻空（blank 才彻底跳过 loader），运行时由 dev-up NACOS_CONFIG_IMPORT="" 承担 |
| Grafana 看板告警规则 | 看板模板已内置，告警规则/通知渠道随部署环境补 |
| outbox P3（Schema 治理） | **评估判停**（v1.12.1 会话）——1 消费者规模成本>>收益；重启条件见 B2 设计 §7（消费者≥3/破坏性变更/事件≥10） |
| 动态元数据 TTL 缓存 | **已清偿** #84——PublishedMetaCache（租户键/TTL 30s/500 上界/afterCommit 驱逐） |
| 日志表保留期清理 | **已清偿** #84——LogRetentionJob（180 天可配/每日 03:30/分批 500 选删） |
| bpmn-js 流程设计器 | **已交付** #82——自研 SVG 画布实现（非 bpmn-js 库，决策见架构决策 6） |
| SkyWalking | 随规模引入（agent ~100MB 开销） |
