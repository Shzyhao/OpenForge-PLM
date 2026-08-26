"""内部动态表登记测试（F2-3：发布→登记→sql/validate 闭环）。"""
from fastapi.testclient import TestClient

from gateway.config import settings
from gateway.main import app

client = TestClient(app)
TOKEN = settings.internal_token


def _cleanup(*tables):
    for t in tables:
        settings.sql_allowed_tables.discard(t)
        settings.table_descriptions.pop(t, None)


def test_register_requires_internal_token():
    resp = client.post("/internal/tables", json={"table": "dyn_token_check"})
    assert resp.status_code == 401
    resp = client.post("/internal/tables", json={"table": "dyn_token_check"},
                       headers={"X-Internal-Token": "wrong"})
    assert resp.status_code == 401


def test_register_rejects_invalid_table_name():
    resp = client.post("/internal/tables",
                       json={"table": "dyn_evil; DROP TABLE part", "description": "x"},
                       headers={"X-Internal-Token": TOKEN})
    assert resp.status_code == 400
    resp = client.post("/internal/tables", json={"table": "UPPER_CASE"},
                       headers={"X-Internal-Token": TOKEN})
    assert resp.status_code == 400


def test_register_then_sql_validate_closed_loop():
    """F2 验收 #3 闭环：登记 dyn_ 表后 SELECT ... FROM 该表返回 allowed。"""
    table = "dyn_equipment"
    try:
        # 登记前：不在白名单，拒绝
        before = client.post("/api/v1/ai/sql/validate",
                             json={"sql": "SELECT * FROM dyn_equipment"}).json()
        assert before["allowed"] is False

        resp = client.post("/internal/tables",
                           json={"table": table,
                                 "description": "设备台账：name 名称, location 位置, purchase_price 采购价, is_critical 关键设备"},
                           headers={"X-Internal-Token": TOKEN})
        assert resp.status_code == 200
        assert resp.json()["registered"] == table
        assert resp.json()["has_description"] is True

        # 登记后：即刻可查（F2 设计 5：新对象即刻可被自然语言查询）
        after = client.post("/api/v1/ai/sql/validate",
                            json={"sql": "SELECT name FROM dyn_equipment WHERE is_critical = 1"}).json()
        assert after["allowed"] is True, after["reason"]
        assert "LIMIT" in after["sql"].upper()  # 安全网关仍强制 LIMIT

        # 幂等：重复登记不报错，描述覆盖
        again = client.post("/internal/tables",
                            json={"table": table, "description": "设备台账（更新描述）"},
                            headers={"X-Internal-Token": TOKEN})
        assert again.status_code == 200
        assert settings.table_descriptions[table] == "设备台账（更新描述）"
    finally:
        _cleanup(table)


def test_registered_description_feeds_table_descriptions():
    table = "dyn_site"
    try:
        client.post("/internal/tables",
                    json={"table": table, "description": "厂区：name 名称"},
                    headers={"X-Internal-Token": TOKEN})
        assert table in settings.table_descriptions
        assert "厂区" in settings.table_descriptions[table]
    finally:
        _cleanup(table)
