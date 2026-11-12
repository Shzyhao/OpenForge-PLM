# OpenForge v1.25 设计与收口 —— 清欠批次（flaky/P3 重启/MinIO/通知渠道/回收站三域/审签流程/多模型/R14 扩面）

> 状态：随实现收口。前置：v1.24.0 已发版（PR #121）。本批 = v1.24 发版后待办清单的全部 12 项一次性清欠。

## 0. 范围与结果映射

| 项 | 交付 | 判定 |
|---|---|---|
| A1 flaky | KnowledgeEventBrokerLoopTest 等待 60s→180s、轮询 2s→1s（CI 负载下 broker 消费延迟偶发超时，PR #121 两个 run 实锤） | 修复 |
| A2 .zcodeignore | 收编入库（IDE/构建忽略配置） | 完成 |
| A3 残留 | hs_err_pid×7 + replay_pid×2 清除 | 完成 |
| B1 outbox P3 | **重启条件达成**（消费组 4 个 ≥3）→ 轻量落地：EVENT_SCHEMA_VERSION=1 显式化 + 消费端容忍规则 + B2 §7 判停翻新；Registry 仍判停 | 已重启（轻量） |
| B2 MinIO | DocMinioStorage/DrawingMinioStorage（openforge.{doc,drawing}.storage=minio 切换，LocalDisk matchIfMissing）；prod compose 共享 env 默认 local；Testcontainers IT（CI 真跑，本机 assumption 跳过） | 完成 |
| C1 通知渠道 | **notify.created** 事件（openforge-notify 主题，携带收件人 email）——通知类连接器（SMTP/DINGTALK/FEISHU/WEBHOOK）订阅即复用全套 EVENT 触发框架；connector 触发/订阅主题白名单扩 openforge-notify；总线关闭=仅站内信（预期语义） | 完成 |
| C2 回收站三域 | knowledge/change/project 接入：V2/V3/V2 迁移 + @TableLogic + RecycleMapper + delete/restore/purge + 前端七 Tab；**变更单 SUBMITTED（在途）删除护栏**（workflow 悬挂引用）；**metadata 动态表判停**（逐对象迁移+布局耦合，需独立设计） | 完成（metadata 判停） |
| C3 链重放 | **单步重放=前端"重放（同参重试）"按钮**（试运行面板，同参重 invoke）；**断点续跑判停**：单遍内存执行器无落盘步态，2~5 步链重放覆盖 >90% 场景，续跑需引入执行状态机+幂等语义，成本 >> 收益 | 重放完成（续跑判停） |
| C4 审签接流程 | flag `openforge.drawing.workflow-review`（默认关）：submit 启 drawing-review 实例（V5 种子：ENGINEER 角色评审）；detail 惰性回流终态（COMPLETED→发布/REJECTED→打回 DRAFT）——ECR 同款拉模型；flag-off 轻量直批语义不变 | 完成 |
| C5 多模型分流 | /ai/chat 增可选 model 提示：链内命中同模型供应商时其子链先行（仍保降级序），无命中回退全链——偏好非硬约束 | 完成 |
| C6 R14 扩面 | vitest 10→14：MyTasksPage（委托标记+审批弹窗流）/RecycleBinPage（七域 Tab+恢复动作）/NotificationsPage/契约层既有 | 完成 |

## 1. 实现期发现

1. **@TableLogic 三域接入的注解位移陷阱**：以"create 方法签名"作插入锚会把新方法插进原方法的 `@Transactional` 与签名之间，吞掉注解——EcrService 编译错实锤；修法=回收站块整体插在注解前。
2. **DrawingWorkflowReviewTest 的 submit 需要 MAIN 文件**（提交评审前置），测试须先 uploadFile。
3. **knowledge_feedback.query_text NOT NULL**——测试直插反馈行必须带 query_text；列序错位会产生 4 值 3 列的 BadSqlGrammar。
4. **ProjectService.tasks() 走 require()**——purge 后验证任务 GC 必须直查表（否则 404 语义拦截）。
5. MinIOContainer 的 S3 地址是 `getS3URL()`（非 getSwaggerUrl）；Testcontainers 本机管道不通仍只认 CI（实纱⑥延续），assumption 守卫保持。
6. vitest Popconfirm 无关项：恢复按钮无确认弹层直接执行，purge 才有——测试断言按组件真实交互写。

## 2. 收口记录（R22，2026-11-12，EVENT_BUS=1 真栈）

| 项 | 结果 |
|---|---|
| 全量 `mvn verify` | BUILD SUCCESS（含三域回收站/审签/MinIO IT/notify.created 断言全部新测试） |
| `smoke.sh` ×3 | **35/35 ×3**，日志 ERROR=0 |
| `v123-probe.sh` | **33 PASS / 0 FAIL**（doc.released 订阅通知 MQ 到达 + 回收站 purge 链路复验） |
| `authz-probe.sh` | 21 PASS / 0 SUSPECT |
| 消费证据 | sys_event_consumed：openforge-notify=33（通知摄取）+ **openforge-connector-trigger=84（含 notify.created 渠道分发到达）** |
| 模块测试 | auth 88 / change 11 / knowledge 9 / project 2（新）/ drawing 11（含审签 3）/ doc 7（含 MinIO IT 本机跳过、CI 真跑）/ connector / workflow 全绿 |
| 前端 | build ✓ + vitest **14/14**；pytest **33/33**（含多模型分流） |
| docker-audit | 干净（MQ 容器随收尾停止） |
