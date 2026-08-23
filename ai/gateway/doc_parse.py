"""文档智能解析管道（架构文档 4.2）。

在线：LLM 按 Schema 结构化抽取。
离线：中文冒号键值规则抽取（演示降级路径，degraded=True 标识）。
两种路径都返回 {schema_key, fields, degraded, confidence}。
"""
import re
from typing import Dict, Optional

from .llm import llm_client

# 内置抽取 Schema（与开发文档 4.2 的"按文档类型配置抽取 Schema"对应；管理接口随 M5）
SCHEMAS: Dict[str, list] = {
    "spec": [
        {"key": "product_name", "label": "产品名称"},
        {"key": "model", "label": "型号"},
        {"key": "material", "label": "材质"},
        {"key": "temperature_range", "label": "温度范围"},
        {"key": "weight", "label": "重量"},
    ],
    "report": [
        {"key": "conclusion", "label": "结论"},
        {"key": "tester", "label": "检测人"},
        {"key": "passed", "label": "是否合格"},
    ],
}


def _offline_extract(text: str, schema: list) -> Dict[str, Optional[str]]:
    """离线规则抽取：匹配「字段名：值」或「字段名:值」行。"""
    fields: Dict[str, Optional[str]] = {f["key"]: None for f in schema}
    label_to_key = {f["label"]: f["key"] for f in schema}
    for line in text.splitlines():
        m = re.match(r"\s*([^：:]{2,12})[：:]\s*(.+)", line.strip())
        if not m:
            continue
        label, value = m.group(1).strip(), m.group(2).strip()
        if label in label_to_key:
            fields[label_to_key[label]] = value
    return fields


def parse_document(text: str, schema_key: str) -> dict:
    if schema_key not in SCHEMAS:
        raise ValueError(f"未知的抽取 Schema: {schema_key}（可用: {', '.join(SCHEMAS)}）")
    schema = SCHEMAS[schema_key]

    # 在线：LLM 结构化抽取（失败自动降级到规则抽取）
    extracted: Optional[dict] = None
    degraded = not llm_client.online
    if llm_client.online:
        hint = ", ".join(f'{f["key"]}({f["label"]})' for f in schema)
        result = llm_client.extract_json("从文本中抽取产品/检测信息", text, hint)
        if isinstance(result, dict):
            extracted = {f["key"]: result.get(f["key"]) for f in schema}
            degraded = False

    if extracted is None:
        extracted = _offline_extract(text, schema)

    filled = sum(1 for v in extracted.values() if v not in (None, ""))
    confidence = round(filled / len(schema), 2) if schema else 0.0
    return {
        "schema_key": schema_key,
        "fields": extracted,
        "degraded": degraded,
        "confidence": confidence,
        "model": llm_client.model if not degraded else None,
    }
