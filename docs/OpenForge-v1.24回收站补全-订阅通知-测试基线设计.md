# OpenForge v1.24 设计与收口 —— 回收站补全 · 订阅通知 · 保留期 · 前端测试基线 · 生产 MQ

> 状态：随实现收口。前置：v1.23.0 已发版（PR #119）。
> 关联：`OpenForge-站内通知-审批委托-回收站设计.md`（§0 P2 清单即本批来源）。

## 0. 范围（v1.23 P2 清单 → 本批 6 项）

| # | 项 | 交付 |
|---|---|---|
| ① | 回收站彻底删除 + BOM/文档域接入 | BOM/doc 删除入口新建；四域 回收站列表+恢复+**purge**（StorageClient.delete 文件 GC） |
| ② | 通知扩展发布事件（订阅模型） | `notify_subscription`（auth V33）；part/bom/doc/drawing 五事件 fan-out ≤50；三域 NotifyClient HTTP 回退；前端通知设置 Tab |
| ③ | 通知保留期清理 | `NotifyRetentionJob`：仅清已读超期行（默认 90 天，≤0 关闭），原生 JDBC 绕租户拦截器 |
| ④ | 前端 vitest 基线（R14 蓝本） | vitest1+jsdom+RTL14；client/契约/页面三层 10 用例；CI Frontend job 接 `npm test` |
| ⑤ | 生产形态接入 rocketmq | prod compose `rocketmq` profile + 共享 env 开关（默认关，部署时显式开启） |

## 1. 关键设计决策

### 1.1 回收站彻底删除（purge）
- **路径**：`POST /api/v1/{parts,boms,docs,drawings}/{id}/purge`，仅对回收站内（deleted=1）行生效，权限=该域删除权限。
- **GC 面**：part→part_version；bom→bom_line+bom_line_substitute；doc→doc_file 行+文件字节；drawing→file/version/part-link+文件字节。
- **文件 GC 抽象**：`StorageClient.delete(storageKey)`（doc/drawing 两个接口各扩一方法），LocalDiskStorage 实现落盘删除，**MinIO 接线后同签名承接**——这就是"GC 方案"的落地层；删除 best-effort（失败告警不阻断行删除，孤儿文件由存储侧生命周期策略兜底）；`baseDir.toAbsolutePath().normalize()` + startsWith 防目录逃逸。
- **引用护栏**：purge part 前查 bom_line/bom_line_substitute 引用计数（含软删 BOM），命中即 400 指引先清 BOM。实测发现该场景正常流不可达（addLine 要求子件 RELEASED，而 RELEASED 不可删）——护栏为脏数据防御，测试经 mapper 直插模拟。
- **删除入口补建**：BOM `deleteDraft`（仅 DRAFT，RELEASED 走升版/废止）；文档 `delete`（仅 DRAFT 且未检出），新权限 `doc:delete`（auth V34 种子，ADMINS/ENGINEER）。
- **审计对称**：DRW_PURGE；material 域维持无审计（与删除基线一致）。

### 1.2 订阅通知（发布类事件）
- **模型**：`notify_subscription(user_id, event_key)` UNIQUE——**显式订阅制**而非全租户广播，发布类事件 payload 无收件人语义下的最小可行收件人解析。可订阅集合与前端 `EVENT_OPTIONS` 双侧对齐（client.test 契约用例钉住）。
- **摄取**：auth `NotifyService.ingest` 增五事件 case，收件人=同租户订阅者（join sys_user 校验存活，fan-out ≤50 复用 ROLE 通道截断）；bizType 取事件域前缀、bizId 取 `*Id`。
- **双通道补全**：part.released/bom.published/doc.released/drawing.{released,obsolete} 的发射点全部改为 `publish()` false 时 `notifyClient.deliver()`（material/doc/drawing 三域各一 NotifyClient，ChangeNotifyClient 同规）；drawing.released 的回退复合（change ECO 联动 + auth 通知）。
- **API**：`GET/POST/DELETE /api/v1/notifications/subscriptions[/{eventKey}]`（登录即可，数据按用户隔离）。

### 1.3 保留期
只清**已读**超期行（未读永不清理）；@Scheduled cron `openforge.notify.retention-cron`（默认 03:40）+ `openforge.notify.retention-days`（默认 90，≤0 关闭）；分批 500。**原生 JDBC**：后台线程 TenantContext 恒 0，走 mapper 会被租户拦截器只清平台租户行（LogRetentionJob 无此问题因表在 GLOBAL_TABLES）。

### 1.4 生产 MQ（prod compose）
- `rocketmq-namesrv/broker` 挂 `profiles: ["rocketmq"]`，**内网不发布端口**（B2 设计）；broker 默认通告容器 IP——容器网内可路由，**勿照搬 dev 的 brokerIP1=127.0.0.1**（那是宿主形态专用）。
- 事件开关进共享 `x-java-env`：`OPENFORGE_EVENT_ENABLED: ${OPENFORGE_EVENT_ENABLED:-false}` + `OPENFORGE_EVENT_NAMESRV_ADDR: rocketmq-namesrv:9876`——默认关（无 broker 时 publish 进 outbox 重试会拖慢联动），部署者 `.env` 置 true + `--profile rocketmq` 同步启用。

### 1.5 前端测试基线
- vitest **1.x**（vite5 兼容；vitest5 需 vite6 不匹配）+ jsdom + RTL14；`npm test` 入 CI。
- 三层用例：`client.ts`（解包/错误传播/401 登出/请求头与体）、`notification api`（EVENT_OPTIONS 契约 + URL 拼接）、页面冒烟（通知中心空态/设置 Tab 订阅态）。R14 全页面四态清单以此为骨架逐批填充。

## 2. 实现期发现

1. **LocalDiskStorage 防逃逸判断在相对路径下误伤**：`baseDir` 未归一化时（`./data/...`），`target.normalize().startsWith(baseDir)` 恒假（"." 段），delete 恒抛而被 best-effort 吞掉——文件 GC 静默失效。修复：构造时 `toAbsolutePath().normalize()`，比较两端同基准。
2. doc_file 外键列为 `doc_info_id`（非 doc_id）；drawing 侧为 `drawing_id`——两域命名不对称，原生 SQL 时注意。
3. material 域测试数据约束链：addLine 要求子件 RELEASED（requireReferenceablePart）→ RELEASED 不可 deleteDraft → 回收站物料不可能被正常流引用（purge 引用护栏为防御性）；BOM 行子件≠BOM 父件否则环检测拦截。
4. H2 同名库跨类共享下，取号 mock 序号必须 static（实例字段每用例重建，撞全行唯一键）。
5. 前端：vitest5 需 vite6；antd 依赖 jsdom 缺失的 matchMedia/ResizeObserver（setup polyfill）；`render(...)` 后紧跟 `(` 行会被 ASI 解析为函数调用。

## 3. 收口记录（R21，2026-09-20，EVENT_BUS=1 真栈）

| 项 | 结果 |
|---|---|
| 全量 `mvn verify` | BUILD SUCCESS（首跑败于 mono notifyClient bean 冲突→域前缀改名→复验过） |
| 模块测试 | auth **88**（+NotifyRetention 2/订阅用例 1）/ material **21**（+BOM 回收与 purge 护栏 2）/ doc **5**（新增 DocRecycleIntegrationTest 2，含文件 GC 断言）/ drawing **8**（+purge 文件 GC 1）/ mono **3** |
| smoke ×3 | **35/35 ×3**，全栈日志 ERROR=0 |
| v123-probe | 扩至 **33 断言全绿**：+BOM/文档回收站与彻底删除、doc.released 订阅通知到达（MQ 通道） |
| authz-probe | 21 PASS / 0 SUSPECT（无回退） |
| 前端 | `npm run build` ✓ + vitest **10/10**；CI yml 增 Test 步骤 |
| prod compose | rocketmq profile 结构校验通过（内网不发布端口/共享 env 默认关） |
| docker-audit | 无悬空/无孤儿/零构建缓存（MQ 容器随收尾停止） |

### 真栈探针实锤的 bug（已修）
- **NotifyEventConsumer 漏订阅发布类主题**：只订 openforge-task，doc.released（openforge-doc 主题）静默无通知——订阅主题清单扩至 task/material/doc/drawing，并加注释钉住"与 ingest 映射范围一致"约束。教训：消费者订阅面是事件映射的隐藏耦合点，扩映射必查订阅面。

### 实纱补充（实纱②第五例）
- 四域各自新增的 `NotifyClient` 在 mono 单 classpath 下 bean 名冲突（doc 注册时撞 workflow）——统一域前缀改名 WorkflowNotifyClient/MaterialNotifyClient/DocNotifyClient/DrawingNotifyClient。**本仓 mono 环境下新增 @Component 类名必须带域前缀**，已是第三次同类事故。

### 探针脚本坑（重演与变体）
- python heredoc 生成 bash 时 `\\"` 转义层级写错 → 落盘为单引号内字面反斜杠 → JSON parse error（doc 建档静默失败）。
- 旧实例杀不净占 8081 → 新实例启动失败但健康扫描打在旧进程上——重启后必须核对**新进程日志特征行**（本例：消费者 topics= 清单）。
