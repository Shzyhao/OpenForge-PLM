# 贡献指南 ｜ Contributing to OpenForge PLM

感谢你关注 OpenForge PLM！本文档说明如何参与贡献。项目当前处于**设计阶段→M1 基础平台启动期**，最需要的是设计评审与早期共建。

## 🌿 分支模型

| 分支 | 用途 | 保护策略 |
|------|------|----------|
| `main` | 稳定版本，只接受来自 dev 的合并与发版 Tag | 禁止直接 push，需 PR + Review |
| `dev` | **开发主线**，所有初始开发信息先到这里 | 需 PR + CI 通过 |
| `feature/<module>-<topic>` | 功能分支，从 dev 切出，完成后 PR 回 dev | 个人工作区 |

```bash
# 典型工作流
git checkout dev
git pull
git checkout -b feature/auth-jwt
# ...开发...
git push -u origin feature/auth-jwt
# 然后在 GitHub 发起 PR: feature/auth-jwt → dev
```

## 📋 贡献类型

1. **设计评审**：对三份设计文档提出 Issue（架构、数据模型、AI 安全机制、流程引擎），标注 `design-review`；
2. **模块认领**：认领 M1 里程碑模块（见 Roadmap），在对应 Issue 下留言认领；
3. **代码贡献**：遵循下方规范；
4. **场景输入**：分享行业 PLM 痛点与流程样本，帮助打磨低代码模板库，标注 `use-case`。

## ✅ PR 验收标准（Loop Engineering 原则）

本项目的开发流程遵循[多智能体与 Loop Engineering 架构](docs/OpenForge-多智能体与LoopEngineering.md)中的验证原则，PR 必须：

- [ ] **验证器先行**：新功能附带测试（先写或同步写验收测试），无测试的 PR 不予合并；
- [ ] 确定性验证通过：编译零错、单测全绿、Lint 无高危；
- [ ] 架构依赖规则不破坏（跨域禁止直读他域数据库等，见架构文档 6.3）；
- [ ] 提交信息符合 Conventional Commits（见下）；
- [ ] 涉及 AI/安全面的改动需额外说明权限影响。

## 📝 提交信息规范（Conventional Commits）

```
<type>(<scope>): <subject>

type: feat | fix | docs | refactor | test | chore | ci
scope: 模块名，如 auth / material / workflow / ai-gateway / docs
subject: 中文或英文一句话，祈使语气，不加句号

示例:
feat(auth): JWT 双令牌签发与刷新轮换
fix(workflow): 会签或签模式下任务完成事件丢失
docs(readme): 补充品牌指南
```

## 🛠️ 开发环境（随 M1 推进更新）

- JDK 21 / Node 20+ / Python 3.12 / Docker
- 后端：Spring Boot 3.x；前端：React 18 + Vite；AI：LangGraph
- 本地依赖（PostgreSQL/Redis/Milvus 等）统一用 `docker-compose` 启动（M1 提供）

## 🤖 与智能体贡献者协作

本项目欢迎 AI 辅助开发，但约定：

- Agent 产出的提交必须打 `author=agent:<id>` 标注（Co-authored-by trailer）；
- Agent 提交与人类提交走**相同的 PR 验收标准**，无豁免；
- 合并主干、生产部署、权限配置三类操作永久人类门禁（详见 MAS 文档第 10 章）。

## 💬 讨论

- Issue：bug 报告与功能建议
- Discussions：设计讨论（推荐先讨论再写代码）

行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。提交 PR 即表示你同意以 Apache-2.0 许可贡献代码。
