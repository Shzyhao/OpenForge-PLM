"""AI 供应商降级链测试（§12.1）：链解析/env 兜底/逐个降级。CI 无 LLM 与 connector 环境。"""
import httpx
import pytest

from gateway import llm as llm_mod
from gateway.llm import LLMClient, LLMOfflineError
from gateway.provider_chain import ProviderChain


def _chain(pairs):
    return [{"baseUrl": u, "apiKey": k, "model": m, "timeoutMs": 1000} for u, k, m in pairs]


def test_empty_chain_falls_back_to_env_offline(monkeypatch):
    chain = ProviderChain()
    monkeypatch.setattr(chain, "_fetch", lambda: None)  # connector 不可达
    monkeypatch.setattr(chain, "_chain", [])
    import gateway.config as cfg
    monkeypatch.setattr(cfg.settings, "llm_base_url", "", raising=False)
    monkeypatch.setattr(cfg.settings, "llm_api_key", "", raising=False)
    assert chain.get_chain() == []


def test_env_fallback_single_provider(monkeypatch):
    chain = ProviderChain()
    monkeypatch.setattr(chain, "_fetch", lambda: None)
    monkeypatch.setattr(chain, "_chain", [])
    import gateway.config as cfg
    monkeypatch.setattr(cfg.settings, "llm_base_url", "https://env.example.com/v4", raising=False)
    monkeypatch.setattr(cfg.settings, "llm_api_key", "env-key", raising=False)
    monkeypatch.setattr(cfg.settings, "llm_model", "glm-4-flash", raising=False)
    resolved = chain.get_chain()
    assert resolved == [{
        "baseUrl": "https://env.example.com/v4",
        "apiKey": "env-key",
        "model": "glm-4-flash",
        "timeoutMs": 60000,
    }]


def test_llm_degrades_to_next_provider(monkeypatch):
    client = LLMClient()
    calls = []

    def fake_post(url, json=None, headers=None, timeout=None):
        calls.append(url)
        if url.startswith("https://down.example.com"):
            raise llm_mod.httpx.ConnectError("connection refused")

        class Resp:
            status_code = 200

            def raise_for_status(self):
                pass

            def json(self):
                return {"choices": [{"message": {"content": "ok-reply"}}]}

        return Resp()

    monkeypatch.setattr(client._client, "post", fake_post)
    monkeypatch.setattr(
        llm_mod.provider_chain, "get_chain",
        lambda: _chain([("https://down.example.com/v4", "k1", "model-down"),
                        ("https://up.example.com/v4", "k2", "model-up")]))
    reply = client.complete([{"role": "user", "content": "hi"}])
    assert reply == "ok-reply"
    assert [u.split("/")[2] for u in calls] == ["down.example.com", "up.example.com"]
    assert client.model == "model-up"


def test_llm_all_providers_down(monkeypatch):
    client = LLMClient()

    def fake_post(url, json=None, headers=None, timeout=None):
        raise llm_mod.httpx.ConnectError("connection refused")

    monkeypatch.setattr(client._client, "post", fake_post)
    monkeypatch.setattr(
        llm_mod.provider_chain, "get_chain",
        lambda: _chain([("https://a.example.com/v4", "k1", "m1")]))
    with pytest.raises(LLMOfflineError):
        client.complete([{"role": "user", "content": "hi"}])


def test_model_hint_reorders_chain(monkeypatch):
    """多模型分流（v1.25 §7）：hint 命中同模型供应商时其子链先行，无命中保持全链原序。"""
    from gateway import llm as llm_mod
    fake_chain = [
        {"model": "m-a", "baseUrl": "http://a", "apiKey": "k"},
        {"model": "m-b", "baseUrl": "http://b", "apiKey": "k"},
        {"model": "m-a2", "baseUrl": "http://c", "apiKey": "k"},
    ]
    monkeypatch.setattr(llm_mod.provider_chain, "get_chain", lambda: list(fake_chain))
    client = llm_mod.LLMClient()
    calls = []

    def fake_post(url, json=None, headers=None, timeout=None):
        calls.append(json["model"])
        class R:
            status_code = 500  # 全部 5xx 触发降级遍历
        raise httpx.HTTPError("boom")

    monkeypatch.setattr(client._client, "post", fake_post)
    # hint=m-b：m-b 先试，随后其余按原序
    try:
        client.complete([{"role": "user", "content": "hi"}], model_hint="m-b")
    except llm_mod.LLMOfflineError:
        pass
    assert calls[0] == "m-b"
    assert calls[1:] == ["m-a", "m-a2"]
    # 无命中 hint：保持原序
    calls.clear()
    try:
        client.complete([{"role": "user", "content": "hi"}], model_hint="nope")
    except llm_mod.LLMOfflineError:
        pass
    assert calls == ["m-a", "m-b", "m-a2"]
