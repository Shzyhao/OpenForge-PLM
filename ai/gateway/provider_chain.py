"""AI 供应商降级链配置源（集成编排器 MVP 设计 §12.1）。

从 connector 服务 /internal/ai-provider/chain 拉取 enabled 供应商（按 priority 升序，
已含解密 key），后台线程 30s 轮询热更新；拉取失败保持上次快照。链为空时回落
OPENFORGE_LLM_* 环境变量单模型（零破坏升级——现网不建库配置行为不变）。
"""
import logging
import os
import threading
from typing import List, Optional

import httpx

from .config import settings

log = logging.getLogger(__name__)

POLL_SECONDS = 30


class ProviderChain:
    """enabled 供应商快照 + 后台轮询；线程安全的读多写极少结构。"""

    def __init__(self) -> None:
        self._chain: List[dict] = []
        self._lock = threading.Lock()
        self._client = httpx.Client(timeout=5.0)
        self._started = False

    @property
    def connector_base_url(self) -> str:
        return os.getenv("OPENFORGE_CONNECTOR_BASE_URL", "http://localhost:8094").rstrip("/")

    def _fetch(self) -> Optional[List[dict]]:
        try:
            resp = self._client.get(
                f"{self.connector_base_url}/internal/ai-provider/chain",
                headers={"X-Internal-Token": settings.internal_token},
            )
            resp.raise_for_status()
            data = resp.json().get("data") or []
            return [p for p in data if p.get("baseUrl") and p.get("apiKey")]
        except Exception:
            return None

    def refresh(self) -> None:
        chain = self._fetch()
        if chain is not None:
            with self._lock:
                self._chain = chain
            log.info("AI 供应商降级链已加载: %d 个 provider", len(chain))

    def _env_fallback(self) -> List[dict]:
        """库配置为空时回落环境变量单模型（零破坏升级）。"""
        if settings.llm_online:
            return [{
                "baseUrl": settings.llm_base_url,
                "apiKey": settings.llm_api_key,
                "model": settings.llm_model,
                "timeoutMs": int(settings.llm_timeout_seconds * 1000),
            }]
        return []

    def get_chain(self) -> List[dict]:
        with self._lock:
            if self._chain:
                return list(self._chain)
        return self._env_fallback()

    def start_polling(self) -> None:
        """启动后台轮询（daemon）；重复调用幂等。"""
        if self._started:
            return
        self._started = True

        def loop() -> None:
            self.refresh()
            while True:
                threading.Event().wait(POLL_SECONDS)
                self.refresh()

        threading.Thread(target=loop, daemon=True, name="ai-provider-chain-poll").start()


provider_chain = ProviderChain()
