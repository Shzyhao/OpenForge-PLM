# OpenForge v1.26 设计与收口 —— 动态对象回收站 · 渠道模板预置 · R23 健壮性专项

> 状态：随实现收口。前置：v1.25.0 已发版（PR #122）。本批 = v1.25 后剩余项全清 + 健壮性检验轮。

## 0. 范围与判定

| 项 | 交付 | 判定 |
|---|---|---|
| metadata 动态表回收站 | **探底修正**：`deleted` 列建表即有（DdlGenerator 标准列 L37）、软删删除端点已有（DynamicRecordService.delete L170）——缺的只是**恢复面**。补：`GET /objects/{key}/records/recycle` + `POST .../{id}/restore` + `POST .../{id}/purge`（物理 DELETE），权限沿用对象编程式校验（{objectKey}:view/:delete）；DynamicRecordRecycleTest 全链路 | **完成**（判停解除：探底发现基建已有，非"逐对象迁移"困境） |
| 渠道模板预置 | `GET /api/v1/connectors/presets`：SMTP_EMAIL/DINGTALK_BOT/FEISHU_BOT/HTTP_REST(Webhook) 四模板（最小合法 spec + notify.created 变量占位 {{title}}/{{content}}/{{email}}）；前端新建连接器选类型自动预填 spec（用户只补连接信息） | 完成 |
| MinIO 生产灰度 | compose 补强：minio healthcheck（/dev/tcp）+ **minio-init 桶自动初始化 job**（openforge-doc/openforge-drawing，--ignore-existing 幂等）；灰度步骤手册化。**实启动受阻**：本机与 CI 同源镜像拉取 denied（v1.25 CI 404 同因），演练降级为结构验证 + 手册，需有 registry 配额环境执行 | 就绪（实启动受阻于外部条件） |
| R23 健壮性专项 | 新增 `scripts/robustness-probe.sh`（§2 断言面） | 完成 |

## 1. 明确受阻项（外部条件门控，非实现遗漏）

| 项 | 阻塞条件 | 解锁动作 |
|---|---|---|
| CADConverter | 需外部转换二进制（LibreOffice/Aspose 类）与独立服务资源 | 选型后按 drawing-storage 同模式接 |
| 新内置连接器类型 | 需外部场景输入（长任务书原文约定） | 场景确定后四处同步点扩展（SUPPORTED_TYPES/parseSpec/validateSpec/credential） |
| 断点续跑 | 链规模 >5 步或真实需求出现（判停决策 v1.25） | 届时引入执行状态机 + 步态落库 |
| Milvus/Neo4j/ES、SkyWalking、Grafana 告警 | 随规模/部署环境 | 规模信号触发 |

## 2. R23 健壮性断言面（scripts/robustness-probe.sh）

1. **畸形输入**：坏 JSON / 无效 eventKey / 负数 agentId / 超长字符串（2000 字）→ 业务码承接不 500 不泄漏堆栈；越界分页（page=-5&size=99999）安全收敛；不存在对象的回收站查询承接。
2. **重复操作幂等**：同一软删行 restore 二次拒绝 / purge 二次拒绝（404 族语义）。
3. **并发通知 burst**：20 路并发摄取全落库不丢不重（delta=20）。
4. **内部接口网关面**：/api/v1/internal/** 经网关必须不可达（回归）。
5. **重启持久性**：auth 单服务重启前后 notify_message/workflow_delegate 计数不变 + API 恢复正常。

## 3. 实现期发现

1. **metadata 回收站的探底教训**：v1.25 判停时假设"需逐对象迁移加列"——实际 DdlGenerator 建表标准列早含 deleted，软删端点也已交付；判停理由过时。教训：判停项重启前先探底现状，基建可能已随其它批次就位。
2. 动态表 Page 响应是 {total,page,pageSize,rows} 包装对象——JSONPath 断言不能直接 `$.data[?(@.id)]`，测试改查表断言（顺带避开 H2 同库跨类共享的残留干扰）。
3. `GET /recycle` 与 `GET /{id}` 同路径共存安全：Spring 优先字面量映射（"recycle" 无法转 Long 的变量路径不会误匹配）。
4. 对象级权限码是 `{objectKey}:create` 运行时拼接——测试 stub 必须按对象键给权限。

## 4. 收口记录（R23，2026-11-13，EVENT_BUS=1 真栈）

| 项 | 结果 |
|---|---|
| 全量 `mvn verify` | BUILD SUCCESS（含 DynamicRecordRecycleTest 等新测试） |
| **robustness-probe（新增）** | **13 PASS / 0 FAIL**：畸形输入×6 / 幂等×2 / burst delta=20 / 网关内部面 / 重启持久性 |
| v123-probe | 33 PASS / 0 FAIL（回归） |
| smoke ×3 | 35/35 ×3，日志 ERROR=0 |
| authz-probe | 21 PASS / 0 SUSPECT |
| 前端 build + pytest | ✓ / 33 绿 |
| docker-audit | 干净（MQ 容器随收尾停止） |

### R23 实锤与修复

1. **真 bug：负数/零 agentId 放行**——DelegateService.create 缺正数校验（畸形输入段抓到），已修（"被委托人必填且须为有效用户 ID"）。
2. **探针三连假失败根因**：通知 title 格式为"待办审批：{nodeName}"，探针 `LIKE 'R23-BURST%'` 锚定开头永远查不到——漏前导 %（诊断日志实锤 insert id=114~116 全成功）。教训：LIKE 模式与被检字段格式存在隐式耦合，探针查询一律带前导 %。
3. **Git Bash 后台 & job 不执行**：20 条 `curl ... &` 全部静默未发（burst 段假失败第二因）——串行化 + 注释钉住；真并行压测属压测轮职责。
4. 重启持久性实证：auth 单服务重启前后 notify_message/workflow_delegate 计数不变（PG 持久化语义验证）。
