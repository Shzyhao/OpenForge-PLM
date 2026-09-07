"""统一 LLM 客户端（OpenAI 兼容协议）。

降级链（集成编排器 MVP 设计 §12.1）：按 provider_chain 提供的 enabled 供应商顺序逐个尝试，
连接异常/5xx/超时自动切换下一个；全失败抛 LLMOfflineError。链为空 = 离线模式（env 兜底
由 provider_chain 承担）。离线降级原则（架构文档 2.3-4）：绝不静默失败。
"""
import json
import logging
from typing import Dict, List, Optional

import httpx

from .provider_chain import provider_chain

log = logging.getLogger(__name__)


class LLMOfflineError(RuntimeError):
    """LLM 未配置或全部供应商不可用——调用方应走离线降级路径。"""


class LLMClient:
    """无状态按链调用：每请求按当前快照选择供应商，失败自动降级。"""

    def __init__(self) -> None:
        self._client = httpx.Client(timeout=60.0)
        self._last_model: Optional[str] = None

    @property
    def online(self) -> bool:
        return bool(provider_chain.get_chain())

    @property
    def model(self) -> str:
        return self._last_model or (provider_chain.get_chain()[0]["model"] if self.online else "")

    def complete(self, messages: List[Dict[str, str]], json_mode: bool = False) -> str:
        chain = provider_chain.get_chain()
        if not chain:
            raise LLMOfflineError("LLM 未配置（库中无启用供应商，且未设置 OPENFORGE_LLM_* 环境变量）")
        last_error: Exception = LLMOfflineError("无可用供应商")
        for provider in chain:
            body: Dict[str, object] = {"model": provider["model"], "messages": messages}
            if json_mode:
                body["response_format"] = {"type": "json_object"}
            url = provider["baseUrl"].rstrip("/") + "/chat/completions"
            try:
                resp = self._client.post(
                    url,
                    json=body,
                    headers={"Authorization": f"Bearer {provider['apiKey']}"},
                    timeout=provider.get("timeoutMs", 60_000) / 1000,
                )
                if resp.status_code >= 500 or resp.status_code == 429:
                    last_error = RuntimeError(f"{provider['model']} 上游 {resp.status_code}，降级下一个")
                    log.warning("%s，剩余 %d 个供应商", last_error, len(chain) - chain.index(provider) - 1)
                    continue
                resp.raise_for_status()
                content = resp.json()["choices"][0]["message"]["content"]
                self._last_model = provider["model"]
                return content
            except httpx.HTTPError as e:
                last_error = e
                log.warning("%s 调用失败（%s），尝试降级下一个", provider["model"], e.__class__.__name__)
        raise LLMOfflineError(f"全部 {len(chain)} 个供应商不可用: {last_error}")

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
