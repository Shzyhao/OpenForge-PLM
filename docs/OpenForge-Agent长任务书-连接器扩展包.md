# OpenForge PLM — Agent 长任务书（连接器生态扩展包 v1.21.0）

> 本文档是**自包含**的 Agent 执行任务书：无需会话上下文，按序执行即可完成一次完整交付。
> 产出目标：三个新内置连接器类型（SMTP_EMAIL / DINGTALK_BOT / FEISHU_BOT）+ 前端配置面板 +
> 链编排集成 + 冒烟断言 + 文档对齐 + 发版 v1.21.0。
> 编写基线：v1.20.0（README 重塑 PR #116 后，dev 与 main 同步）。

---

## PART 0 · 环境与运行手册（先读，防踩坑）

### 0.1 仓库与栈

- 仓库：`D:\ZCode Code\PLM`（OpenForge PLM；分支 `dev` 开发，`main` 发版；单人开发直接提交 dev）
- 技术栈：Java 21 + Spring Boot 3.3（11 微服务）+ React 18/TS/AntD（Vite）+ Python FastAPI（AI 网关，本次不动）
- 全栈启停（Git Bash）：
  - `./scripts/dev-up.sh`（PG 容器 + 11 Java 服务；无源码改动自动跳过构建；`PROFILE=mono` 两进程最省 / `PROFILE=core` 主链路）
  - `./scripts/dev-down.sh` 停栈
  - 冒烟：`./scripts/smoke.sh smoke-test-2026`（35 断言；admin 密码即参数值，dev 库）
  - 前端：`cd frontend && npm run dev`（:5173，代理 /api → 网关 :8080）；构建验证 `npm run build`
- dev-up 已注入：出站白名单 `localhost,127.0.0.1` + 允许私网（`OPENFORGE_CONNECTOR_EGRESS_ALLOW_PRIVATE=true`）+ 凭据主密钥默认值——本地测试凭据可解密。

### 0.2 硬纪律（违反会翻车，全部实纱验证过）

1. **构建与运行的服务绝不并行**：低内存宿主机，栈运行中跑 `mvn package` 会 OOM 连崩多服务（dev-up 已内置护栏自动跳过构建；手动构建前必须先 `dev-down`）。hs_err_pid*.log 出现即 OOM 实锤。
2. **JDK**：构建前 `export JAVA_HOME="C:\Program Files\Java\jdk-21.0.11"`，且 `export MAVEN_OPTS="-Xmx768m -XX:+UseSerialGC"`。
3. **本机 testcontainers 连不上 Docker**（npipe 400）：容器/MQ 回路测试只认 CI；本地跑 H2 集成测试即可。
4. **Docker Desktop 闪退处置**：完整杀 `Docker Desktop.exe` + `com.docker.backend.exe` → `wsl --shutdown` → 重启 Docker Desktop，等 `docker ps` 可用再 dev-up。
5. **Git Bash curl**：中文 payload 写临时文件 `--data-binary @file`；`-F "file=@path;filename=x"` 复合参数不做 MSYS 路径转换，必须 `cygpath -w` 转 Windows 路径再传。
6. **改共享模块（openforge-common / starter-*）后**：先 `mvn -pl <模块> -am install` 再跑依赖方，否则 .m2 过期假绿。
7. **验证门**（工程约定，全部有惨案背书）：
   - 每刀相关模块 `mvn verify` 全绿；
   - 网关链路冒烟 `./scripts/smoke.sh` 全绿（约定 #8）；
   - 前端交付须浏览器级真实打开巡检（约定 #9）：IAB 自动化用 `evaluate` 程序化 click 最可靠（React 合成事件对 locator click 无响应），antd 抽屉/弹窗遮罩挡 `elementFromPoint` 时先查 `.ant-drawer-open`/`.ant-modal-wrap` 残留。

### 0.3 关键现状锚点（本次任务涉及面）

- 连接器类型注册：`backend/openforge-connector/src/main/java/com/openforge/connector/spec/ConnectorSpecs.java`（`TYPE_HTTP_REST`/`TYPE_JDBC_READONLY` 常量 + `SUPPORTED_TYPES` + 各 `parseXxx` 校验）
- SPI 实现：`.../spi/HttpRestConnector.java`（Apache HttpClient 5 共享出站客户端，见 `security/OutboundHttpConfig`；重试/1MB 截断/脱敏语义保持）——**新连接器不要直接 new HttpClient，注入共享 `CloseableHttpClient`**
- 出站防护：`.../security/EgressGuard.java`（白名单+私网拦截，`resolveValidated` 是固定解析入口）；**SMTP/webhook 目标同样必须过出站校验**
- 链引擎步骤类型：`ChainSpecs.parse` 里按 type 分派 `parseHttpRest/parseJdbcReadonly`——新增类型须同步分派（含链步骤合法性）
- 前端：`frontend/src/api/connector.ts` 的 `CONN_TYPES` + `components/ConnectorConfigPanel.tsx`（connType 分派渲染表单，`collect()` 回收 spec）+ `IntegrationPage` 新建类型下拉自动跟随 CONN_TYPES；链画布步骤面板的「步骤类型」Select 在 `flow/propertyPanels.tsx` StepPanel
- 凭据类型枚举：`BEARER/BASIC/API_KEY_HEADER/JDBC_PASSWORD`（前端凭据表单 + 后端 ConnectorRuntime 解密分派）——新类型加 `SMTP_PASSWORD`、`WEBHOOK_SECRET`

---

## PART 1 · 主任务：连接器生态扩展包（六组，按序执行）

> 设计原则：三个新类型全部是「通知类」出站（邮件 / 钉钉机器人 / 飞书机器人），复用既有
> 版本化发布、凭据加密、执行日志、链编排、EVENT/CRON 触发全部基建，零新表零迁移。

### ① SMTP_EMAIL 连接器（后端）

**目标**：通用 SMTP 邮件出站——物料发布/图纸发布/变更闭环等事件的邮件通知通道。

- 依赖：`openforge-connector/pom.xml` 加 `org.springframework.boot:spring-boot-starter-mail`（BOM 管版本）
- spec（schemaVersion=1）：`{host, port, starttls(bool), from, to, subject, bodyText, timeoutMs}`；`to` 多收件人逗号分隔；`subject/bodyText` 支持 `{{param}}` 占位（复用 `TemplateRenderer.renderTemplate` 字符串渲染面）
- `ConnectorSpecs`：`TYPE_SMTP_EMAIL = "SMTP_EMAIL"` 入 `SUPPORTED_TYPES` + `parseSmtpEmail`（host 非空/端口 1~65535/from 与 to 邮箱格式宽松校验 `^[^@\s]+@[^@\s]+\.[^@\s]+$`、逐个校验 to 列表）
- `spi/EmailConnector implements ConnectorSpi`：`JavaMailSenderImpl` 按连接器 spec 动态装配（不要注册全局 bean；每连接器实例化并缓存 host:port:from 为 key）；凭据 `SMTP_PASSWORD` → 发件认证密码；失败转 `ConnectorResult.fail`，错误消息脱敏同 HttpRestConnector 先例
- **出站安全**：连接前对 spec.host 做 `egressGuard` 白名单匹配 + `resolveValidated(host)` 私网校验（SMTP 非 http，不走 `check(url)`，但要同等语义——在 EmailConnector 里显式调用 `resolveValidated` 并对 host 先做白名单 contains 判断；`EgressGuard` 若无私网外白名单查询方法，加一个 `checkHost(String)` 公共方法并单测）
- 测试：`EmailSpecsTest`（校验矩阵）+ `EmailConnectorIntegrationTest`（test 依赖 `com.icegreen:greenmail-junit5`，scope test；起 GreenMail 断言收件主题/正文渲染；本机无 Docker 依赖，H2 上下文即可）

**验收**：`mvn -pl openforge-connector test` 全绿；白名单外 SMTP host 被拒（BLOCKED 落日志）。

### ② DINGTALK_BOT 连接器（后端）

**目标**：钉钉自定义机器人出站（含**加签**）。

- spec：`{webhookUrl, msgtype: "text"|"markdown", title?, textTemplate, atMobiles?}`；`textTemplate`/`title` 支持 `{{param}}` 渲染
- 加签：凭据 `WEBHOOK_SECRET`（secret）→ `timestamp + "\n" + secret` 以 secret 为 key 做 HmacSHA256 → Base64 → URL 编码 → 追加 `&timestamp=<ts>&sign=<sign>` 到 webhookUrl（钉钉官方算法，纯数学可单测钉死）
- payload：text → `{"msgtype":"text","text":{"content":...}}`；markdown → `{"msgtype":"markdown","markdown":{"title":...,"text":...}}`；`atMobiles` 逗号分隔渲染进 `at/atMobiles`
- `spi/DingTalkBotConnector`：复用共享 `CloseableHttpClient` POST JSON；URL 已是 https 域名，经 `egressGuard.check(webhookUrl)`（既有语义直接适用）
- 测试：`DingTalkSignerTest`（已知 secret+固定 timestamp 的签名向量断言）+ 集成测试用 JDK 内置 `com.sun.net.httpserver.HttpServer` 起本地回环接收 POST，断言 payload 结构与 query 参数（dev allow-private 已放行 localhost）

**验收**：签名向量测试绿；本地 HttpServer 收到的 JSON 与官方格式一致。

### ③ FEISHU_BOT 连接器（后端）

**目标**：飞书自定义机器人出站（含**签名校验**）。

- spec：`{webhookUrl, msgType: "text"|"interactive"(卡片降级为 text 即可), textTemplate}`；模板渲染同上
- 签名（飞书官方算法，与钉钉不同）：`timestamp + "\n" + secret` 作为 **HMAC-SHA256 的 key**，对**空字符串**签名 → Base64 → payload 内带 `timestamp`/`sign` 字段（不是 query 参数）
- `spi/FeishuBotConnector`：共享 HttpClient POST；`egressGuard.check(webhookUrl)`
- 测试：`FeishuSignerTest`（固定向量）+ 本地 HttpServer 断言 payload 含 sign

**验收**：同 ②。

### ④ 通用收尾（后端）

- `ChainSpecs.parse`：链步骤 type 分派补 `SMTP_EMAIL/DINGTALK_BOT/FEISHU_BOT`（链内可作通知步骤，如「物料发布 → 查 ERP 库存 → 钉钉群播报」）；steps 校验同步
- `CredentialService`/前端凭据表单：`authType` 枚举加 `SMTP_PASSWORD`、`WEBHOOK_SECRET`（解密后仅为字符串 secret，无 headerName 之类 extra）
- 执行日志/审计：零改动（既有 writeLog 按类型透传）

### ⑤ 前端配置面板

- `api/connector.ts`：`ConnType` 联合类型 + `CONN_TYPES` 加三项（标签：邮件通知（SMTP）/钉钉机器人/飞书机器人）
- `ConnectorConfigPanel.tsx`：connType 分派新增三个表单分支（表单字段与 spec 一一对应；`collect()` 输出 canonical spec；凭据下拉按 connType 过滤 authType）
- `flow/propertyPanels.tsx` StepPanel「步骤类型」Select options 加三项（链编排里可选通知步骤）
- 凭据表单（IntegrationPage + api）：authType 下拉加两个新类型

**验收**：`npm run build` 绿 + 浏览器巡检：新建三种连接器各一（本地假 host/webhook 指向 localhost:8080），表单校验/保存/试运行错误提示可读。

### ⑥ 冒烟 + 文档 + 发版

- `scripts/smoke.sh` 新增第 7 节（3~4 断言）：
  - 建钉钉机器人连接器 webhook 指向 `http://localhost:8080/api/v1/auth/login`（POST 端点）→ 试运行返回「已收到上游响应」（4xx 也是链路通的证据，断言 httpStatus 非空）
  - EMAIL：建连接器 host 指向白名单外域名 → 断言 6011 BLOCKED（出站防护对 SMTP 生效）
  - 链：两步链（HTTP 查询 → DINGTALK 通知）试运行 SUCCESS（本地回环）
- 文档：集成编排器设计文档补「§内置连接器类型」小节（四类对比表：用途/spec 要点/凭据类型/签名算法）；README 版本块/徽章/里程碑 v1.21.0 + 功能矩阵「集成编排器」行补三个通知渠道；`backend/README` 无需动
- 发版（严格按序）：
  1. `dev-down` → 全量 `mvn verify`（openforge-connector + mono + 受影响模块）→ `dev-up` → smoke 全绿
  2. 分笔提交（feat / docs），push dev，等 CI 三作业绿
  3. `gh pr create --base main --head dev` → checks 绿 → merge
  4. `git fetch origin main` 后**先核实 merge commit SHA 再打 tag** `v1.20.1` 或 `v1.21.0`（建议 v1.21.0）→ `gh release create`
  5. `git merge origin/main` 回灌 dev → 交接文档 `docs/OpenForge-会话交接.md` 对齐（快照区 + PR 表 + 下一步）→ push
- 交接文档同步要点：新增依赖（starter-mail、greenmail）、签名向量来源、smoke 断言计数 35→38±

**总工作量参考**：①~④ 约 2.5~3 人日，⑤ 约 1 人日，⑥ 约 0.5 人日。

---

## PART 2 · 后续长任务队列（本轮不做；每项已给出锚点，接力时按此展开）

| # | 任务 | 锚点与要点 | 前置 |
|---|------|-----------|------|
| Q-1 | **ECO 自动升版联动**：变更执行端点在物料/图纸受影响时自动 revise | drawing.released/obsolete 事件口已留（v1.20.0）；change 域执行端点已有 internal 通道（MaterialClient 先例）；需产品决策「哪些变更类别触发」 | 建议先征用户确认规则 |
| Q-2 | **断点续跑/单步重放**（编排链 Q3） | steps_json 已记每步状态；DLQ 重放当前=整链重跑；方案：重放参数带 `resumeFrom` 步骤 key，ChainExecutor 跳过已成功步（幂等性由连接器作者责任的边界要重申） | 真实使用反馈 |
| Q-3 | **MinIO 统一对象存储**：doc + drawing 的 StorageClient 双实现 | key 约定 `tenant/{id}/{yyyyMMdd}/{uuid}{ext}` 已按 MinIO 兼容设计（两域 LocalDiskStorage 注释均预留）；配置切换 `openforge.<svc>.storage=minio|local`；compose 已有 minio 服务（extras） | 无 |
| Q-4 | **CADConverter 评估**（DWG/DXF 服务端转换预览） | ODA/Teigha 授权成本高；评估 LibreDXF+渲染 或 as-service 外部转换器；结论大概率判停——产出评估记录即可 | 无 |
| Q-5 | **连接器级 ACL**（Q2） | conn:invoke 细化到连接器维度；sys_permission 动态注册模式已有先例（F2 权限点随发布注册） | 多租户真实诉求 |

---

## PART 3 · 完成定义（DoD）

- [ ] 三个新连接器类型经网关 invoke/链编排/试运行全链路可用，凭据密文落库
- [ ] SMTP/webhook 出站均过 EgressGuard（白名单+私网+固定解析语义）
- [ ] connector 模块全量测试绿（含签名向量 + GreenMail + 本地 HttpServer 断言）；mono/CI 全绿
- [ ] smoke.sh 全绿且断言计数更新；浏览器级巡检三种新配置表单
- [ ] 文档四处对齐（设计文档/README/交接文档/任务书勾选）；发版 tag+Release+回灌完成
