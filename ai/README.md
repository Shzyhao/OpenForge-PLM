# OpenForge AI 中台网关

Python 3.11 + FastAPI。统一 LLM 接入（OpenAI 兼容协议，支持 GLM/Qwen/vLLM 等），
未配置模型时自动进入**离线降级模式**：服务可启动、文档解析降级为规则抽取、SQL 安全网关不受影响。

## 本地运行

```bash
cd ai
pip install -r requirements.txt
uvicorn gateway.main:app --port 8001 --reload

# 配置真实模型（可选）
export OPENFORGE_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
export OPENFORGE_LLM_API_KEY=your-key
export OPENFORGE_LLM_MODEL=glm-4-flash
```

## 端点

| 端点 | 说明 |
|------|------|
| `GET /healthz` | 健康检查（含 LLM 在线状态） |
| `POST /api/v1/ai/chat` | 对话（离线返回降级标识回复） |
| `POST /api/v1/ai/jobs/doc-parse` | 文档结构化抽取（在线 LLM / 离线规则，返回 degraded 与 confidence） |
| `POST /api/v1/ai/sql/validate` | SQL 安全网关校验（单语句/SELECT only/表白名单/危险函数/LIMIT 强制） |
| `POST /api/v1/ai/data/query` | 自然语言（`question`→LLM 生成 SQL）或直接提交 SQL；均过安全网关后只读执行（数据源未配置时返回校验通过未执行） |
| `POST /internal/tables` | 动态表登记（F2 发布流水线）：表白名单 + 表描述运行时注册，新发布对象即刻可被 nl2sql/数据查询访问；`X-Internal-Token` 防护，不经 Java 网关路由 |

## SQL 安全网关（架构文档 4.7.2 M4 子集）

1. sqlglot 语法解析；2. 单语句 + 仅 SELECT；3. 表白名单（`OPENFORGE_SQL_ALLOWED_TABLES`，默认 part 域+用户角色表）；4. 危险函数黑名单（pg_sleep/dblink/文件读取等）；5. LIMIT 强制与上限改写。

## 测试

```bash
cd ai && pytest
```

## 路线

自然语言→SQL（M5）已交付：白名单表业务描述注入 Prompt 生成 SELECT，生成结果必过 SQL 安全网关；动态对象发布经 `/internal/tables` 自动登记表描述。后续：向量检索、BOM 清洗管道、Token 计量与熔断。
