"""自然语言→SQL 测试（CI 离线环境）。"""
from gateway.nl2sql import build_prompt, nl_to_sql
from gateway.config import settings


def test_offline_nl2sql_rejected_clearly():
    if settings.llm_online:
        import pytest
        pytest.skip("本地配置了 LLM，跳过离线断言")
    ok, _, reason = nl_to_sql("有多少已发布的物料")
    assert not ok
    assert "需要配置大模型" in reason


def test_prompt_contains_only_whitelisted_tables():
    prompt = build_prompt("查询物料")
    assert "part" in prompt
    assert "sys_user" in prompt or "sys_role" in prompt  # 默认白名单含用户/角色表
    # 未在白名单的描述不注入
    assert "knowledge_item" not in prompt


def test_prompt_includes_question():
    assert "已发布的物料数量" in build_prompt("已发布的物料数量")
