#!/usr/bin/env bash
# OpenForge 网关链路冒烟（工程约定 #8 合并门工具化，#90/#101 冒烟实践沉淀）
# 用法：./scripts/smoke.sh [admin密码]（默认 smoke-test-2026；前置：dev-up 已起栈，full/mono 均可）
# 断言：登录→JWT→module-routes 自检→9 业务域经网关返回业务码 0（动态路由/注册表/信任头链路全穿）
#       + 连接器域 6 断言（集成编排器：路由注册/凭据加密建连/发布/试运行/白名单拦截/内部令牌门禁）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GW="${GW:-http://localhost:8080}"
PASSWORD="${1:-smoke-test-2026}"
PASS=0; FAIL=0

fail() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }

echo "=== [1/3] 网关健康与登录 ==="
HEALTH=$(curl -s -m 5 "$GW/actuator/health" || true)
echo "$HEALTH" | grep -q '"UP"' && ok "gateway /actuator/health UP" || { fail "gateway 健康检查：$HEALTH"; }

LOGIN=$(curl -s -m 5 -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"$PASSWORD\"}" || true)
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))" 2>/dev/null || true)
[ -n "$TOKEN" ] && ok "登录获取 accessToken（len=${#TOKEN}）" || { fail "登录失败: $(echo $LOGIN | head -c 120)"; }

AUTH=("Authorization: Bearer $TOKEN")

echo "=== [2/3] 模块注册表自检（#92 可观测性） ==="
ROUTES=$(curl -s -m 5 "$GW/actuator/module-routes" || true)
echo "$ROUTES" | grep -q '"registryReachable":true' && ok "注册中心可达" || fail "registryReachable 非 true: $(echo $ROUTES | head -c 120)"
echo "$ROUTES" | grep -q '"routeMissing":\[\]' && ok "routeMissing 空" || fail "routeMissing 非空: $ROUTES"
echo "$ROUTES" | grep -q '"brokenModules":\[\]' && ok "brokenModules 空" || fail "brokenModules 非空: $ROUTES"

echo "=== [3/3] 9 业务域网关穿透（动态路由→单/多 upstream→业务码 0） ==="
# 端点为各域真实 GET 列表/统计端点（#90 教训：裸前缀无控制器会 500，非路由缺陷）
declare -A EPS=(
  [material]="/api/v1/parts"
  [doc]="/api/v1/docs"
  [change]="/api/v1/changes/stats"
  [knowledge]="/api/v1/knowledge/items"
  [project]="/api/v1/projects"
  [workflow]="/api/v1/workflow/defs"
  [metadata]="/api/v1/meta/objects"
  [connector]="/api/v1/connectors"
  [auth]="/api/v1/modules"
)
for svc in material doc change knowledge project workflow metadata connector auth; do
  ep=${EPS[$svc]}
  resp=$(curl -s -m 5 "$GW$ep" -H "${AUTH[0]}" || true)
  if echo "$resp" | grep -q '"code":0'; then
    ok "$svc $ep"
  else
    fail "$svc $ep → $(echo "$resp" | head -c 100)"
  fi
done

echo "=== [4/4] 集成编排器（连接器全链路：建模→发布→invoke→白名单拦截） ==="
# Windows/Git Bash 注意：curl 命令行内联中文会被 ANSI 代码页转码（服务端 JSON parse error），
# 含中文的 payload 一律写临时文件走 --data-binary @file（字节原样）
TMPJSON="$(mktemp -t smokeconnXXXXXX.json)"
trap 'rm -f "$TMPJSON"' EXIT
CONN_CODE="smoke_conn_$(date +%s)"

printf '{"credCode":"smoke_cred","credName":"冒烟凭据","authType":"BEARER","secret":"smoke-secret-1"}' > "$TMPJSON"
CR=$(curl -s -m 5 -X POST "$GW/api/v1/connector-credentials" -H "${AUTH[0]}" -H "Content-Type: application/json"   --data-binary @"$TMPJSON" || true)
if echo "$CR" | grep -q '"code":0'; then
  ok "凭据创建（密文落库）"
elif echo "$CR" | grep -q '"code":6008'; then
  ok "凭据已存在（幂等复用）"
else
  fail "凭据创建: $(echo $CR | head -c 100)"
fi

printf '{"connCode":"%s","connName":"冒烟连接器","connType":"HTTP_REST","spec":{"schemaVersion":1,"method":"GET","url":"http://localhost:8080/actuator/health"}}' "$CONN_CODE" > "$TMPJSON"
CC=$(curl -s -m 5 -X POST "$GW/api/v1/connectors" -H "${AUTH[0]}" -H "Content-Type: application/json"   --data-binary @"$TMPJSON" || true)
echo "$CC" | grep -q '"code":0' && ok "连接器建模" || fail "连接器建模: $(echo $CC | head -c 100)"
CONN_ID=$(echo "$CC" | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null || true)

PB=$(curl -s -m 5 -X POST "$GW/api/v1/connectors/$CONN_ID/publish" -H "${AUTH[0]}" || true)
echo "$PB" | grep -q '"version":1' && ok "发布生成版本快照 v1" || fail "发布: $(echo $PB | head -c 100)"

IV=$(curl -s -m 5 -X POST "$GW/api/v1/connectors/invoke/$CONN_CODE" -H "${AUTH[0]}" -H "Content-Type: application/json"   -d '{}' || true)
echo "$IV" | grep -q '"status":"SUCCESS"' && ok "invoke 成功（localhost 白名单内目标）" || fail "invoke: $(echo $IV | head -c 120)"

printf '{"connCode":"%s_b","connName":"白名单外","connType":"HTTP_REST","spec":{"schemaVersion":1,"method":"GET","url":"http://evil.example.com/steal"}}' "$CONN_CODE" > "$TMPJSON"
BK=$(curl -s -m 5 -X POST "$GW/api/v1/connectors" -H "${AUTH[0]}" -H "Content-Type: application/json"   --data-binary @"$TMPJSON" || true)
BK_ID=$(echo "$BK" | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null || true)
BKR=$(curl -s -m 5 -X POST "$GW/api/v1/connectors/$BK_ID/test" -H "${AUTH[0]}" -H "Content-Type: application/json" -d '{}' || true)
echo "$BKR" | grep -q '"code":6011' && ok "白名单外目标拦截（6011）" || fail "白名单拦截: $(echo $BKR | head -c 120)"

IT=$(curl -s -m 5 -X POST "$GW/api/v1/connectors/invoke/$CONN_CODE" -H "Content-Type: application/json" -d '{}' || true)
echo "$IT" | grep -q '"code":2001' && ok "内部令牌缺失拒绝（2001）" || fail "内部令牌门禁: $(echo $IT | head -c 100)"
curl -s -m 5 -X DELETE "$GW/api/v1/connectors/$CONN_ID" -H "${AUTH[0]}" >/dev/null || true

echo ""
echo "=== 冒烟结果：$PASS 通过 / $FAIL 失败 ==="
[ "$FAIL" = "0" ] || exit 1
