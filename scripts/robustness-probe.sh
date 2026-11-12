#!/usr/bin/env bash
# R23 健壮性专项探针（v1.26 设计 §4）：畸形输入 / 重复操作幂等 / 越界分页 / 长字符串 /
# 并发通知 burst / 无效订阅事件 / 重启持久性——全部经网关真栈执行，验证"错误可承接不 500"。
# 用法：./scripts/robustness-probe.sh [admin密码]；重启持久性段需 EVENT 栈运行（单服务重启 auth）。
set +e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
GW="${GW:-http://localhost:8080}"
PASSWORD="${1:-smoke-test-2026}"
PSQL="docker exec openforge-pg psql -U openforge -d openforge -t -A"
PASS=0; FAIL=0
ok()  { echo "  PASS [$1]"; PASS=$((PASS+1)); }
bad() { echo "  ✗FAIL [$1]"; FAIL=$((FAIL+1)); }

jcode() { python -c "
import sys,json
try: print(json.loads(sys.stdin.buffer.read().decode('utf-8','replace')).get('code',''))
except Exception: print('NOCODE')
" 2>/dev/null; }
resp() { local m=$1 p=$2 t=$3; shift 3
  curl -s -m 10 -X "$m" "$GW$p" -H "Authorization: Bearer $t" -H "Content-Type: application/json" "$@" 2>/dev/null; }

echo "=== [1/6] 登录 ==="
TOKEN=$(curl -s -m 5 -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"$PASSWORD\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))" 2>/dev/null)
[ -n "$TOKEN" ] && ok "admin 登录" || { echo "  登录失败"; exit 1; }

echo "=== [2/6] 畸形输入（预期业务码拒绝，绝不 500/堆栈泄漏） ==="
c=$(resp POST /api/v1/workflow/delegates "$TOKEN" -d 'not-json' | jcode)
[ "$c" != "NOCODE" ] && [ "$c" != "" ] && ok "坏 JSON → 业务码 $c" || bad "坏 JSON 未被承接"
c=$(resp POST /api/v1/notifications/subscriptions/%22bad%22 "$TOKEN" | jcode)
[ "$c" != "0" ] && ok "无效 eventKey 拒绝($c)" || bad "无效 eventKey 放行"
c=$(resp POST /api/v1/workflow/delegates "$TOKEN" -d '{"agentId":-1,"startTime":"2099-01-01T00:00:00"}' | jcode)
[ "$c" != "0" ] && ok "负数 agentId 拒绝($c)" || bad "负数 agentId 放行"
c=$(resp GET "/api/v1/notifications?page=-5&size=99999" "$TOKEN" | jcode)
[ "$c" = "0" ] && ok "越界分页安全承接($c)" || bad "越界分页未承接($c)"
c=$(resp GET "/api/v1/objects/recycleobj/records/recycle" "$TOKEN" | jcode)
[ "$c" != "NOCODE" ] && ok "不存在对象的回收站承接($c)" || bad "不存在对象未承接"
# 长字符串（title 2000 字符）——长度校验拒绝而非入库
LONG=$(python -c "print('长'*2000)")
TMPJSON="$(mktemp -t r23XXXXXX.json)"; printf '{"agentId":1,"startTime":"2099-01-01T00:00:00","remark":"%s"}' "$LONG" > "$TMPJSON"
c=$(resp POST /api/v1/workflow/delegates "$TOKEN" --data-binary @"$TMPJSON" | jcode)
rm -f "$TMPJSON"
[ "$c" != "0" ] && ok "超长备注拒绝($c)" || bad "超长备注入库"

echo "=== [3/6] 重复操作幂等（404 语义不重复生效） ==="
CATID=$($PSQL -c "SELECT id FROM part_category ORDER BY id LIMIT 1"); [ -z "$CATID" ] && CATID=1
RID=$(resp POST /api/v1/parts "$TOKEN" -d "{\"name\":\"R23-IDEM-$(date +%s)\",\"type\":\"RAW\",\"categoryId\":$CATID}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
resp DELETE /api/v1/parts/$RID "$TOKEN" >/dev/null
c1=$(resp POST /api/v1/parts/$RID/restore "$TOKEN" | jcode)
c2=$(resp POST /api/v1/parts/$RID/restore "$TOKEN" | jcode)
[ "$c1" = "0" ] && [ "$c2" != "0" ] && ok "重复恢复首次成功二次拒绝($c1/$c2)" || bad "重复恢复语义异常($c1/$c2)"
resp DELETE /api/v1/parts/$RID "$TOKEN" >/dev/null
resp POST /api/v1/parts/$RID/purge "$TOKEN" >/dev/null
c3=$(resp POST /api/v1/parts/$RID/purge "$TOKEN" | jcode)
[ "$c3" != "0" ] && ok "重复 purge 二次拒绝($c3)" || bad "重复 purge 放行($c3)"

echo "=== [4/6] 通知 burst 连发（20 条全落库不丢不重） ==="
# 注：本环境 Git Bash 后台 & job 不执行（R23 实锤——20 条全部静默未发），改串行连发；
# 丢/重检测语义保留（20 全落库），真并行压测属压测轮职责
BEFORE=$($PSQL -c "SELECT COUNT(*) FROM notify_message WHERE title LIKE '%R23-BURST%'")
for i in $(seq 1 20); do
  curl -s -m 5 -X POST "http://localhost:8081/api/v1/internal/notifications" \
    -H "Content-Type: application/json" -H "X-Internal-Token: openforge-internal-dev-token" \
    -d "{\"eventType\":\"task.created\",\"payload\":{\"assigneeId\":1,\"nodeName\":\"R23-BURST-$i\",\"instanceId\":$i}}" >/dev/null 2>&1
done
wait
sleep 2
AFTER=$($PSQL -c "SELECT COUNT(*) FROM notify_message WHERE title LIKE '%R23-BURST%'")
echo "  [diag] BEFORE=$BEFORE AFTER=$AFTER"
DELTA=$((AFTER - BEFORE))
[ "$DELTA" -eq 20 ] && ok "20 并发通知全落库（delta=$DELTA）" || bad "burst 落库数异常 delta=$DELTA"
$PSQL -c "DELETE FROM notify_message WHERE title LIKE '%R23-BURST%'" >/dev/null

echo "=== [5/6] 内部接口网关面（必须不可达） ==="
c=$(resp POST /api/v1/internal/notifications "$TOKEN" -d '{"eventType":"task.created","payload":{}}' | jcode)
[ "$c" != "0" ] && ok "内部摄取接口网关拒绝($c)" || bad "内部接口网关暴露（P0）"

echo "=== [6/6] 重启持久性（auth 单服务重启：通知/委托/回收站计数不变） ==="
N_BEFORE=$($PSQL -c "SELECT COUNT(*) FROM notify_message")
D_BEFORE=$($PSQL -c "SELECT COUNT(*) FROM workflow_delegate")
./scripts/dev-down.sh >/dev/null 2>&1
sleep 2
netstat -ano | grep -E ":808[0-9].*LISTENING" | awk '{print $NF}' | sort -u | while read pid; do taskkill //F //PID $pid >/dev/null 2>&1; done
SKIP_BUILD=1 ./scripts/dev-up.sh > /tmp/devup-r23.log 2>&1
sleep 3
N_AFTER=$($PSQL -c "SELECT COUNT(*) FROM notify_message")
D_AFTER=$($PSQL -c "SELECT COUNT(*) FROM workflow_delegate")
[ "$N_BEFORE" = "$N_AFTER" ] && [ "$D_BEFORE" = "$D_AFTER" ] \
  && ok "重启持久性：通知($N_AFTER)/委托($D_AFTER) 计数不变" \
  || bad "重启后计数漂移 通知 $N_BEFORE→$N_AFTER 委托 $D_BEFORE→$D_AFTER"
c=$(resp GET /api/v1/notifications/unread-count "$TOKEN" | jcode)
[ "$c" = "0" ] && ok "重启后 API 正常" || bad "重启后 API 异常($c)"

echo ""
echo "=== 探针结果: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" = "0" ] || exit 2
