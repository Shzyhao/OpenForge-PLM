# OpenForge AI 中台网关（M4/M5）

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
| `POST /api/v1/ai/data/query` | 校验 + 只读执行（`sql` 直提交或 `question` 自然语言生成，均过安全网关；执行层随生产部署配置启用） |
| `POST /internal/tables` | 动态表登记（F2 发布流水线）：表白名单 + Schema 描述注入，X-Internal-Token 防护 |

## SQL 安全网关（架构文档 4.7.2 M4 子集）

1. sqlglot 语法解析；2. 单语句 + 仅 SELECT；3. 表白名单（`OPENFORGE_SQL_ALLOWED_TABLES`，默认 part 域+用户角色表）；4. 危险函数黑名单（pg_sleep/dblink/文件读取等）；5. LIMIT 强制与上限改写。

## 测试

```bash
cd ai && pytest
```

## 路线

M5 已交付：自然语言→SQL（`question=` 双入口 + 动态表登记注入 Schema 知识）、向量检索（knowledge 服务，可插拔）。
后续：BOM 清洗管道、Token 计量与熔断。
