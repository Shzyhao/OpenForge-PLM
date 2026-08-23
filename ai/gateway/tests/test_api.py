"""API 端点测试（TestClient，覆盖离线模式的完整请求路径）。"""
from fastapi.testclient import TestClient

from gateway.main import app

client = TestClient(app)


def test_healthz():
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
    assert "llm_online" in resp.json()


def test_chat_offline_degrades_gracefully():
    resp = client.post("/api/v1/ai/chat", json={"messages": [{"role": "user", "content": "你好"}]})
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] in ("online", "offline")
    if body["mode"] == "offline":
        assert "离线" in body["reply"]


def test_doc_parse_endpoint():
    resp = client.post("/api/v1/ai/jobs/doc-parse",
                       json={"text": "材质：45#钢\n型号：FL-1", "schema_key": "spec"})
    assert resp.status_code == 200
    fields = resp.json()["fields"]
    assert fields["material"] == "45#钢"
    assert fields["model"] == "FL-1"


def test_sql_validate_endpoint_blocks_dml():
    resp = client.post("/api/v1/ai/sql/validate", json={"sql": "DELETE FROM part"})
    assert resp.status_code == 200
    assert resp.json()["allowed"] is False


def test_sql_validate_endpoint_allows_readonly():
    resp = client.post("/api/v1/ai/sql/validate", json={"sql": "SELECT count(*) FROM part"})
    assert resp.status_code == 200
    assert resp.json()["allowed"] is True
    assert "LIMIT" in resp.json()["sql"].upper()


def test_data_query_rejects_unsafe_sql():
    resp = client.post("/api/v1/ai/data/query", json={"sql": "DROP TABLE part"})
    assert resp.status_code == 403
