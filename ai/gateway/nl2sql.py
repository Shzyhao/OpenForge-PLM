"""自然语言 → SQL（架构文档 4.7 DataOps：生成侧）。

在线：将白名单表的业务描述注入 Prompt，LLM 生成单条 SELECT；
生成的 SQL 必须再过 SQL 安全网关（生成不可信，校验兜底——Loop Engineering 原则）。
离线：明确拒绝并提示（nl2sql 无规则降级路径）。
"""
from typing import Optional, Tuple

from .config import settings
from .llm import llm_client
from .sql_gateway import validate_sql

SYSTEM_PROMPT = (
    "你是 PostgreSQL 专家。根据用户的自然语言问题和可用表结构，生成一条查询 SQL。\n"
    "规则：只输出一条 SELECT 语句，不要解释、不要 Markdown 代码块；只能使用列出的表；"
    "合理使用 LIMIT（默认不超过 100）。"
)


def build_prompt(question: str) -> str:
    tables = "\n".join(
        f"- {name}: {desc}"
        for name, desc in settings.table_descriptions.items()
        if name in settings.sql_allowed_tables
    )
    return f"可用表结构：\n{tables}\n\n用户问题：{question}"


def nl_to_sql(question: str) -> Tuple[bool, str, str]:
    """返回 (ok, sql, reason)。ok=False 时 reason 为原因（离线/生成失败/校验拒绝）。"""
    if not llm_client.online:
        return False, "", "自然语言查询需要配置大模型（OPENFORGE_LLM_BASE_URL / OPENFORGE_LLM_API_KEY）"

    generated: Optional[str] = None
    try:
        raw = llm_client.complete([
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(question)},
        ])
        generated = raw.strip().removeprefix("```sql").removeprefix("```").removesuffix("```").strip()
    except Exception:
        return False, "", "SQL 生成失败，请稍后重试"

    if not generated:
        return False, "", "SQL 生成结果为空"

    # 生成的 SQL 必须过安全网关（绝不直接执行模型输出）
    allowed, final_sql, reason = validate_sql(
        generated, settings.sql_allowed_tables, settings.sql_max_limit)
    if not allowed:
        return False, generated, f"生成的 SQL 未通过安全校验（{reason}），已拦截"
    return True, final_sql, "ok"
