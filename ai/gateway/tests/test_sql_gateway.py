"""SQL 安全网关测试（Loop V2 级确定性验证）。"""
from gateway.sql_gateway import validate_sql

TABLES = {"part", "sys_user"}
LIMIT = 200


def test_select_allowed_and_limit_forced():
    ok, sql, reason = validate_sql("SELECT * FROM part", TABLES, LIMIT)
    assert ok, reason
    assert "LIMIT 200" in sql.upper()


def test_join_allowed_tables():
    ok, sql, _ = validate_sql(
        "SELECT p.name FROM part p JOIN sys_user u ON p.created_by = u.id LIMIT 10", TABLES, LIMIT)
    assert ok
    assert "LIMIT 10" in sql.upper()


def test_oversized_limit_rewritten():
    ok, sql, _ = validate_sql("SELECT * FROM part LIMIT 100000", TABLES, LIMIT)
    assert ok
    assert "LIMIT 200" in sql.upper()


def test_table_not_in_whitelist_rejected():
    ok, sql, reason = validate_sql("SELECT * FROM sys_role_permission", TABLES, LIMIT)
    assert not ok
    assert "白名单" in reason


def test_insert_rejected():
    ok, _, reason = validate_sql("INSERT INTO part (name) VALUES ('x')", TABLES, LIMIT)
    assert not ok
    assert "SELECT" in reason


def test_delete_rejected():
    ok, _, reason = validate_sql("DELETE FROM part", TABLES, LIMIT)
    assert not ok


def test_update_rejected():
    ok, _, _ = validate_sql("UPDATE part SET name = 'x'", TABLES, LIMIT)
    assert not ok


def test_multi_statement_rejected():
    ok, _, reason = validate_sql("SELECT 1; SELECT 2", TABLES, LIMIT)
    assert not ok
    assert "单条" in reason


def test_dangerous_function_rejected():
    ok, _, reason = validate_sql("SELECT pg_sleep(10) FROM part", TABLES, LIMIT)
    assert not ok
    assert "pg_sleep" in reason


def test_syntax_error_rejected():
    # sqlglot 对部分垃圾输入会容错解析成非 SELECT 表达式而非抛 ParseError，
    # 两条路径都必须拒绝（语法错误 或 非 SELECT 语句）
    ok, _, reason = validate_sql("SELEC * FRM part", TABLES, LIMIT)
    assert not ok
    assert reason != "ok"


def test_subquery_table_also_checked():
    ok, _, reason = validate_sql(
        "SELECT * FROM part WHERE id IN (SELECT part_id FROM secret_table)", TABLES, LIMIT)
    assert not ok
    assert "secret_table" in reason
