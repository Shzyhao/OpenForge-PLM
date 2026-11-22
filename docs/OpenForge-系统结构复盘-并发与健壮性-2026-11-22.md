# OpenForge 系统结构复盘：并发与健壮性专项（2026-11-22）

> 基线：v1.30.1（fe2d85b）。方法：4 路专项扫描（JVM 并发安全 / 事务边界 / 资源健壮性与幂等 / 数据层并发与边界）
> + 主模型对全部高危项逐条精读复核。与 v1.27 八维度复扫的差异：本轮以**并发正确性**为主视角——
> v1.27 扫的是"欠账"（超时/事务边界/指标），本轮扫的是"竞态与语义"（check-then-act / 事件投递语义 / 状态机互斥）。
> 所有 P0 项均经代码逐行复核确认；≥2 路独立扫描交叉命中的项已标注。

## 0. 一句话总结

**JVM 层并发纪律优秀，DB 层并发防线系统性缺位。** 全仓零 `@Version`、零状态条件更新、
唯一一处 `FOR UPDATE` 是死代码——所有状态流转都是"内存校验 + `updateById` 整行回写"，
在单实例低并发下是竞态窗口，在并发审批/双击/事件重投下是可触发的功能缺陷。
另有一个独立缺陷族：**事件消费的 at-least-once 语义实际退化为 at-most-once**（首败即丢）。

## 1. 做得好的部分（复核确认，勿重复怀疑）

| 维度 | 结论 |
|---|---|
| JVM 并发三板斧 | CHM 分段锁（编号引擎）、volatile 不可变快照发布（ModuleRouteRefresher/缓存）、双检锁全部写对（ModuleRegistrar/EventPublisher.producer） |
| 脏面 | 全仓无 static 可变集合、无非线程安全类字段（SimpleDateFormat 等）、无 @Async、ThreadLocal 全部 finally 清理 |
| 出站超时 | Java 侧裸客户端 0 处（统一 InternalRestClientFactory 3s/10s）；Python 侧全带 timeout |
| 租户隔离 | 拦截器 + 显式 SQL 双保险扎实，pgvector 双保险有效，文件键前缀有效——**仅 REFERENCE 校验一处漏网（P0-3）** |
| 兜底面 | 回收站四域全行唯一约束兜底恢复竞态；连接器版本 uk_conn_version、编号规则 uk 兜底并发创建 |
| @Scheduled | 全部 fixedDelay/cron 自身无重叠；清扫/保留体系覆盖 5 类表（唯事件双表漏网，P1-8） |

## 2. P0 —— 功能正确性缺陷（建议 v1.31 修复）

### P0-1 事件消费"首败即丢"（at-least-once 退化为 at-most-once）⚡三路交叉
- `AbstractEventConsumer.consume()`：幂等行 `sys_event_consumed` 以 **autocommit 先于业务副作用**插入；
  外层 `catch(Exception)` 还会把 INSERT 的非重复异常（DB 瞬断）也当"解析失败"ACK 吞掉
- 链路：`handle()` 抛异常 → `RECONSUME_LATER` 重投 → 重投撞幂等行 DuplicateKey → 返回 empty → **ACK**。
  重投第一次就被拦，`maxReconsumeTimes` 永不累积 → **DLQ 也永远到不了**。日志"将重试"是假象
- 影响面（仅 `EVENT_BUS=1` 形态）：notify / knowledge 沉淀 / Drawing→ECR 联动静默丢失；
  connector 有应用级 DLQ 兜底 handle 失败（但兜不住 insert 失败），其余三个消费者无兜底
- 修复方向：幂等行改**状态机**（PENDING→DONE，重投撞 PENDING 允许重处理）或移入 handle 同事务；
  INSERT 失败不得并入"解析失败"ACK 分支

### P0-2 WorkflowEngine.act 审批竞态 ⚡三路交叉
- `act()`：`selectById → isOpen 检查 → updateById`（无 version / 无 `WHERE action IS NULL` / 无行锁）
- 三个可触发场景：①同任务双击/双代理：双双通过 isOpen → **advance 连跳两个节点**、task.completed 双发；
  ②ANY 或签两人并发：`cancelOpenTasks` 盲写互覆 → PG 行锁互等**死锁 40P01** 或下一节点任务重复生成；
  ③ALL 会签最后两票并发：READ COMMITTED 下互见对方"未办" → 双双走等待分支 → **两票都提交后实例静默挂死**
- 修复方向：抢占式条件更新 `UPDATE workflow_task SET ... WHERE id=? AND action IS NULL`（affected=0
  即已被办理，抛"已办理"）；节点推进同理用实例状态条件更新互斥

### P0-3 动态对象 REFERENCE 校验缺租户条件（多租户边界）
- `DynamicRecordService.checkReferenceExists:446`：`WHERE id=? AND deleted=0` **无 `AND tenant_id=?`**
  （同文件其余 6 处直写 SQL 全带）
- 影响：租户 A 可引用租户 B 的记录 id 形成跨租户脏引用；可借报错差异探测他租户记录存在性
- 修复：补租户条件（一行）

### P0-4 doc/drawing 检出独占锁本身是 check-then-act
- `DocService.checkOut:77`（无事务）/ `DrawingService:237`：读 `checkedOutBy` 判空 → updateById 写入
- 并发检出：两人同时通过判空 → **双双持有"独占"锁并发编辑** → 检入互踩（丢一方修改）
- 修复：`UPDATE ... SET checked_out_by=? WHERE id=? AND checked_out_by IS NULL`（affected=0 抛已被检出）

### P0-5 ECR/drawing 惰性回流双执行（查询路径做状态迁移）
- `EcrService.detail:194`（GET）发现流程终态 → `executeApply`（跨服务 HTTP 改物料/BOM）→ updateById，
  以内存 `state=="SUBMITTED"` 判断为门，无 `UPDATE ... WHERE state='SUBMITTED'` 抢占
- 并发两用户同开详情：双 executeApply → 第二次因物料已 FROZEN 报错 → **FAILED 整行覆盖 APPLIED**
  （updateById 写的是提交前读的陈旧实体）；retryApply 与 detail 并发同理
- drawing 同款链条 `syncWorkflowReview`（与 P1-3 叠加）
- 修复：回流入口条件更新抢占（`WHERE state='SUBMITTED'` affected=0 则他人已处理）

### P0-6 物料/BOM 状态机互相覆盖（发布不可变被破坏）
- `PartService`：updateDraft 读 DRAFT → approve 并发已置 RELEASED → updateById 整行回写
  **把已发布物料降级回 DRAFT**；deleteDraft 与 approve 并发可**删除已发布物料**
- `BomService.addLine:102`（无 @Transactional）：requireDraftBom 检查后 insert，与 approve 并发
  **向 RELEASED BOM 追加行**；submit/reject 转变同样无事务
- 修复：状态流转全部改条件更新；编辑/加行入口以条件更新占位（或加 version 列）

## 3. P1 —— 高价值修复（v1.31/1.32 搭车）

| # | 问题 | 位置 | 修复 |
|---|---|---|---|
| P1-1 | 模块注册表心跳整行回写**复活已停用模块**（心跳读到 ENABLED 快照 → 管理端 disable → 心跳写回 ENABLED；`evaluateDependencies` 已修过同类、register/disable 未修）⚡两路交叉 | `ModuleRegistryService.register:56` | 定点 UPDATE（只写 heartbeat_at/serviceUri/version） |
| P1-2 | outbox **固定双投递**：afterCommit `trySend` 成功后不回写 sent_at → relay 60s 内必然再发一次（消费去重吸收语义，业务无损但流量×2、relay 指标失真） | `EventPublisher:117` | trySend 成功后 `UPDATE sent_at` |
| P1-3 | Drawing `detail→this.approve()` 自调用事务失效：RELEASED 提交后快照失败不可恢复；且无事务路径 fallback+补调**双触发 ECO 通知**（title 幂等键挡住） | `DrawingService:456` | 拆 bean/自注入代理；fallback 去重 |
| P1-4 | ECR 联动单 selectCount-then-insert，title 无唯一约束 → 并发/重投建两条联动 ECR ⚡两路交叉 | `EcrService:150` | `change_request` 加 title 业务唯一索引 |
| P1-5 | BOM 并发升版重复版本号（无 (parent,version) 唯一约束）；checkIn 小版本并发丢递增 | `BomService:523` | 唯一约束兜底 |
| P1-6 | 钉钉/飞书 bot 无请求级超时：HC5 读超时默认**无限** → webhook 假死挂满 MaxConnTotal=20 连接池（MQ 消费线程同挂） | `DingTalkBotConnector:81`/`FeishuBot:63` | RequestConfig 补齐 |
| P1-7 | LLM 链三层超时错配：供应商串行逐家×120s + 网关未配 response-timeout + 前端 30s 已断后端继续烧 → FastAPI 40 线程耗尽连 /healthz 排队 | `ai/gateway/llm.py` + gateway yml | 网关 response-timeout + ai 并发信号量 |
| P1-8 | 事件双表无清理：sys_event_outbox / sys_event_consumed 无界增长（其余 5 类表都有保留任务） | `EventOutboxRelay` | 对齐 LogRetentionJob 模式补保留任务 |
| P1-9 | ECR 提交/文件上传前端无双击守卫（ChangePage Modal 无 confirmLoading）+ 后端无幂等键 | `ChangePage.tsx:313` 等 | confirmLoading + 后端幂等 |
| P1-10 | uploadFile 事务内文件 IO：drawing @Transactional 内 readAllBytes+落盘（mono 单池 8 连接被上传时长占用）；MinioClient 两处零超时配置（与 C9 解锁联动） | `DrawingService:184` | IO 移出事务；MinIO 超时随 C9 批次 |

## 4. P2 —— 低频/条件触发（记录观察，按判据触发再修）

- @Scheduled 共享默认**单线程**：mono 形态下 10 心跳+relay+resync+3 日清+3 周扫串行；周清扫（全目录 walk）>180s 即心跳超时被误判 BROKEN（新增门控项，判据=BROKEN 告警实际触发且根因是清扫占用）
- 权限缓存 `PermissionQueryClient:53` clear 逻辑缺陷：注释说"清过期后仍超限才全清"，代码无条件全清 → 多线程触顶时自造缓存风暴
- 登录失败计数/知识反馈 RMW 丢更新（影响=锁定阈值延迟/丢一次加分，非绕过）
- JdbcReadonlyConnector 池 LRU 驱逐在锁外交引用：>50 活跃数据源才触发
- NumberRuleService 锁 map 只增不减（周期性慢泄漏，对象小）；`selectForUpdate` 为死代码（注释宣称的 DB 锁未接线——单实例约束下 JVM synchronized 有效，scale-out 前须接线）
- 动态记录 update LWW（无 version）；元数据 update 可把 PUBLISHED 写回 DRAFT（管理员低频）
- whereUsed N+1 / approve 全深递归 expand(MAX_VALUE)（大 BOM 慢查询+内存尖峰）；回收站列表不分页；上传 100MB 全量入堆
- 前端列表页无 AbortController 竞态守卫（AbortSignal.timeout 只管超时）；低延迟内网窗口小
- mono 回环死锁窗口（已知判停）；权限缓存失效瞬间惊群（回环 <1ms，放大有限）

## 5. 健壮性水位（六维）

| 维度 | 水位 | 说明 |
|---|---|---|
| 出站超时 | ★★★★☆ | 统一工厂+Python 全覆盖；残余仅两家 bot 请求级 + LLM 链错配 |
| 资源关闭 | ★★★★★ | HTTP response/文件流/MinIO 全对；无泄漏点 |
| 缓存上界 | ★★★★☆ | 权限 2048/元数据 500 均有；clear 逻辑缺陷自造风暴 |
| 事件可靠性 | ★★☆☆☆ | **最大短板**：首败即丢 + 双投递 + 双表无清理（三个缺陷同族） |
| 状态机正确性 | ★★☆☆☆ | 零乐观锁零条件更新——审批/检出/回流/发布四族全裸 |
| 可观测告警 | ★★★★☆ | 六计数+四告警+消费者 gauge；告警渠道 v1.30.1 已通 |

## 6. 并发可能性推演（scale-out 视角，非开工建议）

1. **当前并发防线全在两处**：JVM 内（synchronized/CHM——单实例有效）+ DB 唯一约束（天然多实例有效）。
   零乐观锁意味着 scale-out 时所有"内存校验+整行回写"点从**竞态窗口**变**必然竞态**（部署约束 §3 矩阵的取号/注册表/清扫项之外，还需补本批全部 P0 点）
2. **P0 修复与 C5/C6 天然联动**：P0-2/4/5/6 的条件更新方案本身就是多实例安全的（`WHERE state=X` 抢占不依赖 JVM 锁）——**修 P0 = 同时支付 scale-out 的部分前置成本**，这是"现在就修"的额外理由
3. 事件族（P0-1/P1-2）与 C6（relay 行级认领）联动：幂等行状态机改造时一并设计 SKIP LOCKED 认领，避免二次返工
4. 判据补强：C1（Hikari pending）在 mono 单池 8 连接下比画像时**更易触发**——告警阈值如按 9×5 连接基线设的需复核

## 7. 修复批次建议

- **v1.31（R26 并发正确性专项）**：P0 六项 + P1-1/2/3/4 —— 每项配并发复现测试（Testcontainers PG 双线程竞争断言，R14 测试模式扩展并发用例）；量级估 2~3 天
- **v1.32 搭车**：P1-5~10（超时补齐/保留任务/前端守卫/MinIO 随 C9）
- 新增门控项登记（按 §4 维护约定写判据）：@Scheduled 池化（判据=mono 下 BROKEN 误告警且根因为清扫占用）
- P2 其余项不排期，随相关模块迭代顺带

## 8. 与既有文档的联动

- 《部署约束》§1 表格不变（本批均为应用层竞态，非单实例机制项）；§3 矩阵补注"P0 修复即部分前置"
- 《v1.30 待办清单》C 组：C1/C9 判据联动见 §6.4；本清单问题登记以本文档为准（勿重复开清单）
