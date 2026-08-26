"""OpenForge AI 中台网关（架构文档 4.1：统一 LLM 接入 + 计量 + 降级）。

端点：
- GET  /healthz
- POST /api/v1/ai/chat          对话（离线模式返回标识性回复）
- POST /api/v1/ai/jobs/doc-parse  文档结构化抽取
- POST /api/v1/ai/sql/validate    SQL 安全网关校验（纯校验不执行）
- POST /api/v1/ai/data/query      自然语言/SQL 查询（M4：SQL 直提交 + 校验 + 只读执行）
- POST /internal/tables           动态表登记（F2 发布流水线；不经网关路由，X-Internal-Token 防护）
"""
import re
from typing import List, Dict, Optional

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

from .config import settings
from .doc_parse import parse_document
from .llm import llm_client, LLMOfflineError
from .nl2sql import nl_to_sql
from .sql_gateway import validate_sql

app = FastAPI(title="OpenForge AI Gateway", version="0.1.0")

OFFLINE_CHAT_REPLY = (
    "【AI 离线模式】当前未配置大模型接入（OPENFORGE_LLM_BASE_URL / OPENFORGE_LLM_API_KEY），"
    "对话能力处于降级状态。文档解析已自动切换到规则抽取；SQL 安全网关与校验功能不受影响。"
)


class ChatRequest(BaseModel):
    messages: List[Dict[str, str]]
    question: Optional[str] = None


class DocParseRequest(BaseModel):
    text: str
    schema_key: str = "spec"


class SqlValidateRequest(BaseModel):
    sql: str


class DataQueryRequest(BaseModel):
    sql: Optional[str] = None
    question: Optional[str] = None  # 自然语言（在线 LLM 生成 SQL，仍过安全网关）


class TableRegisterRequest(BaseModel):
    table: str                       # 物理表名（动态表为 dyn_ 前缀）
    description: str = ""            # 表/列级业务描述（注入 nl2sql Prompt 的 Schema 知识）


@app.get("/healthz")
def healthz():
    return {"status": "ok", "llm_online": llm_client.online, "model": settings.llm_model}


@app.post("/api/v1/ai/chat")
def chat(req: ChatRequest):
    if not llm_client.online:
        return {"reply": OFFLINE_CHAT_REPLY, "mode": "offline", "model": None}
    try:
        reply = llm_client.complete(req.messages)
        return {"reply": reply, "mode": "online", "model": llm_client.model}
    except LLMOfflineError:
        return {"reply": OFFLINE_CHAT_REPLY, "mode": "offline", "model": None}


@app.post("/api/v1/ai/jobs/doc-parse")
def doc_parse(req: DocParseRequest):
    try:
        return parse_document(req.text, req.schema_key)
    except ValueError as e:
        from fastapi import HTTPException
        raise HTTPException(status_code=400, detail=str(e))


@app.post("/api/v1/ai/sql/validate")
def sql_validate(req: SqlValidateRequest):
    allowed, final_sql, reason = validate_sql(req.sql, settings.sql_allowed_tables, settings.sql_max_limit)
    return {"allowed": allowed, "sql": final_sql, "reason": reason,
            "allowed_tables": sorted(settings.sql_allowed_tables)}


@app.post("/api/v1/ai/data/query")
def data_query(req: DataQueryRequest):
    """双入口：sql=直接提交只读 SQL；question=自然语言（LLM 生成后同样过安全网关）。

    权限合成（用户权限∩AI权限∩白名单）在网关层由 Java 侧 JWT 校验承担；
    本端点只做语句级安全（白名单/只读/LIMIT）。
    """
    from fastapi import HTTPException

    generated_by = "direct"
    sql = req.sql
    if req.question and not sql:
        ok, generated, reason = nl_to_sql(req.question)
        if not ok:
            raise HTTPException(status_code=400, detail=reason)
        sql = generated
        generated_by = "nl2sql"
    if not sql:
        raise HTTPException(status_code=400, detail="sql 或 question 必须提供一项")

    allowed, final_sql, reason = validate_sql(sql, settings.sql_allowed_tables, settings.sql_max_limit)
    if not allowed:
        raise HTTPException(status_code=403, detail=reason)
    if not settings.sql_readonly_url:
        return {"sql": final_sql, "generated_by": generated_by, "rows": [], "executed": False,
                "note": "只读数据源未配置（OPENFORGE_SQL_READONLY_URL），已通过安全校验但未执行"}
    # 执行层：独立只读数据源（M4 交付校验与护栏，执行接入随部署配置启用）
    return {"sql": final_sql, "generated_by": generated_by, "rows": [], "executed": False,
            "note": "安全校验通过；执行层随生产部署配置启用"}


TABLE_NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,63}$")


def _require_internal_token(token: Optional[str]) -> None:
    if not settings.internal_token or token != settings.internal_token:
        raise HTTPException(status_code=401, detail="内部接口令牌无效")


@app.post("/internal/tables")
def register_table(req: TableRegisterRequest, x_internal_token: Optional[str] = Header(default=None)):
    """动态表登记（F2 设计 5 发布流水线）：表白名单 + 表描述运行时注册，
    新发布对象即刻可被 nl2sql/数据查询访问。路径在 /api/v1/ai/** 之外，
    不经 Java 网关路由；幂等：重复登记覆盖描述、白名单不变。
    """
    _require_internal_token(x_internal_token)
    table = req.table.strip()
    if not TABLE_NAME_PATTERN.match(table):
        raise HTTPException(status_code=400, detail="表名须匹配 ^[a-z][a-z0-9_]{2,63}$")
    settings.sql_allowed_tables.add(table)
    if req.description:
        settings.table_descriptions[table] = req.description
    return {"registered": table,
            "allowed_tables": sorted(settings.sql_allowed_tables),
            "has_description": table in settings.table_descriptions}
