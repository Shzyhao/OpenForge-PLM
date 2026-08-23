"""统一 LLM 客户端（OpenAI 兼容协议）。

离线降级原则（架构文档 2.3-4）：未配置 LLM 时服务仍可启动、非 LLM 功能（SQL 校验等）正常，
LLM 功能返回明确的离线标识，绝不静默失败。
"""
import json
from typing import List, Dict, Optional

import httpx

from .config import settings


class LLMOfflineError(RuntimeError):
    """LLM 未配置或不可用——调用方应走离线降级路径。"""


class LLMClient:
    def __init__(self) -> None:
        self._client = httpx.Client(
            base_url=settings.llm_base_url,
            headers={"Authorization": f"Bearer {settings.llm_api_key}"},
            timeout=settings.llm_timeout_seconds,
        ) if settings.llm_online else None

    @property
    def online(self) -> bool:
        return self._client is not None

    @property
    def model(self) -> str:
        return settings.llm_model

    def complete(self, messages: List[Dict[str, str]], json_mode: bool = False) -> str:
        if not self.online:
            raise LLMOfflineError("LLM 未配置（设置 OPENFORGE_LLM_BASE_URL / OPENFORGE_LLM_API_KEY）")
        body = {"model": self.model, "messages": messages}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        resp = self._client.post("/chat/completions", json=body)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    def extract_json(self, instruction: str, text: str, schema_hint: str) -> Optional[dict]:
        """LLM 结构化抽取；解析失败返回 None（调用方降级）。"""
        try:
            raw = self.complete([
                {"role": "system", "content": f"{instruction}\n仅输出 JSON 对象，字段：{schema_hint}"},
                {"role": "user", "content": text[:8000]},
            ], json_mode=True)
            return json.loads(raw)
        except (LLMOfflineError, json.JSONDecodeError, KeyError, httpx.HTTPError):
            return None


llm_client = LLMClient()
