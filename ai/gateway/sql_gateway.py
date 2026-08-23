"""SQL 安全网关（架构文档 4.7.2 五层校验的 M4 实现：语法/语句类型/表白名单/危险函数/LIMIT）。

纯函数实现，不依赖数据库连接——执行层由调用方按只读数据源执行。
"""
from typing import Tuple

import sqlglot
from sqlglot import exp

DANGEROUS_FUNCTIONS = {
    "pg_sleep", "pg_read_file", "pg_read_binary_file", "pg_ls_dir",
    "dblink", "lo_import", "lo_export", "copy", "pg_terminate_backend",
}


def validate_sql(sql: str, allowed_tables: set, max_limit: int = 200) -> Tuple[bool, str, str]:
    """校验并规范化只读 SQL。

    返回 (allowed, final_sql_or_reason, reason)
    - allowed=True 时 final_sql 为规范化后语句（强制 LIMIT）
    - allowed=False 时 final_sql 为原始语句，reason 为拒绝原因
    """
    try:
        statements = sqlglot.parse(sql, read="postgres")
    except sqlglot.errors.ParseError as e:
        return False, sql, f"SQL 语法错误: {e}"

    if len(statements) != 1 or statements[0] is None:
        return False, sql, "仅允许单条 SQL 语句"
    stmt = statements[0]

    # 语句类型：仅 SELECT（CTE 中也不允许写操作——SELECT only 天然排除）
    if not isinstance(stmt, exp.Select):
        return False, sql, f"仅允许 SELECT 查询，检测到: {type(stmt).__name__}"

    # 表白名单
    tables = {t.name for t in stmt.find_all(exp.Table)}
    blocked = tables - allowed_tables
    if blocked:
        return False, sql, f"表不在白名单中: {', '.join(sorted(blocked))}"

    # 危险函数黑名单
    called = {f.sql_name().lower() if hasattr(f, 'sql_name') else str(f.name).lower()
              for f in stmt.find_all(exp.Func)}
    anon = {str(f.this).lower() for f in stmt.find_all(exp.Anonymous)}
    hit = (called | anon) & DANGEROUS_FUNCTIONS
    if hit:
        return False, sql, f"禁止调用函数: {', '.join(sorted(hit))}"

    # LIMIT 强制与上限改写
    final = stmt
    current_limit = stmt.args.get("limit")
    if current_limit is None:
        final = stmt.limit(max_limit)
    else:
        try:
            value = int(current_limit.expression.this)
            if value > max_limit:
                final = stmt.limit(max_limit)
        except (AttributeError, ValueError, TypeError):
            pass  # 非字面量 LIMIT（如参数占位）放行——执行层仍有超时与行数护栏

    return True, final.sql(dialect="postgres"), "ok"
