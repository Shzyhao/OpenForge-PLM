"""文档解析管道测试（离线降级路径——CI 无 LLM 环境）。"""
import pytest

from gateway.doc_parse import parse_document

SPEC_TEXT = """
产品规格书
产品名称：高温密封法兰盘
型号：FL-120A
材质：316L 不锈钢
温度范围：-40℃ ~ 260℃
重量：2.4 kg
其他无关行：本行没有冒号分隔的有效标签信息
"""


def test_offline_extraction_fills_fields():
    result = parse_document(SPEC_TEXT, "spec")
    fields = result["fields"]
    assert fields["product_name"] == "高温密封法兰盘"
    assert fields["material"] == "316L 不锈钢"
    assert fields["temperature_range"] == "-40℃ ~ 260℃"
    assert result["confidence"] == 1.0


def test_degraded_flag_when_llm_offline():
    # CI 环境必然离线（未配置 LLM），验证降级标识与模型为空
    result = parse_document(SPEC_TEXT, "spec")
    if not result["degraded"]:
        pytest.skip("本地配置了 LLM，跳过离线断言")
    assert result["degraded"] is True
    assert result["model"] is None


def test_empty_text_gives_zero_confidence():
    result = parse_document("无有效内容", "spec")
    assert result["confidence"] == 0.0
    assert all(v is None for v in result["fields"].values())


def test_unknown_schema_rejected():
    with pytest.raises(ValueError):
        parse_document("text", "no_such_schema")
