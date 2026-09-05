#!/usr/bin/env bash
# OpenForge 网关链路冒烟（工程约定 #8 合并门工具化，#90/#101 冒烟实践沉淀）
# 用法：./scripts/smoke.sh [admin密码]（默认 smoke-test-2026；前置：dev-up 已起栈，full/mono 均可）
# 断言：登录→JWT→module-routes 自检→8 业务域经网关返回业务码 0（动态路由/注册表/信任头链路全穿）
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

echo "=== [3/3] 8 业务域网关穿透（动态路由→单/多 upstream→业务码 0） ==="
# 端点为各域真实 GET 列表/统计端点（#90 教训：裸前缀无控制器会 500，非路由缺陷）
declare -A EPS=(
  [material]="/api/v1/parts"
  [doc]="/api/v1/docs"
  [change]="/api/v1/changes/stats"
  [knowledge]="/api/v1/knowledge/items"
  [project]="/api/v1/projects"
  [workflow]="/api/v1/workflow/defs"
  [metadata]="/api/v1/meta/objects"
  [auth]="/api/v1/modules"
)
for svc in material doc change knowledge project workflow metadata auth; do
  ep=${EPS[$svc]}
  resp=$(curl -s -m 5 "$GW$ep" -H "${AUTH[0]}" || true)
  if echo "$resp" | grep -q '"code":0'; then
    ok "$svc $ep"
  else
    fail "$svc $ep → $(echo "$resp" | head -c 100)"
  fi
done

echo ""
echo "=== 冒烟结果：$PASS 通过 / $FAIL 失败 ==="
[ "$FAIL" = "0" ] || exit 1
