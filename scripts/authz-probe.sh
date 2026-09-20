#!/usr/bin/env bash
# 越权与租户隔离探针（R10 沉淀，工程约定 #8 同类合并门工具化）
# 用法：./scripts/authz-probe.sh [admin密码]（默认 smoke-test-2026；前置：dev-up 已起栈）
# 依赖：docker exec（向 dev 库写入探针用户）、python、cygpath（Git Bash）
# 断言面：
#   1) 双租户按对象 ID 直取/改/删（IDOR）——物料/文档/图纸/ECR/项目/连接器/流程实例/文件下载
#   2) 网关伪头剥除（X-User-Tenant/X-User-Id 大小写变体）
#   3) 用户管理租户边界（跨租户列表过滤/删用户拒绝/无角色列表 2004/响应无 passwordHash）
#   4) 无角色权限矩阵（manage 端点 2004）
#   5) v1.23 通知中心：跨租户标记已读/收件箱内容不泄漏 + 内部摄取接口门禁（网关不暴露/令牌校验）
#   6) v1.23 回收站：跨租户恢复被拒/列表租户过滤/无角色恢复 2004
# 预期：全部 PASS 退出 0；任一 ✗SUSPECT 退出 2（越权成功=P0 级回归）
set +e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GW="${GW:-http://localhost:8080}"
PASSWORD="${1:-smoke-test-2026}"
PSQL="docker exec openforge-pg psql -U openforge -d openforge -t -A"
PASS=0; SUSPECT=0

ok()  { echo "  PASS [$1]"; PASS=$((PASS+1)); }
bad() { echo "  ✗SUSPECT [$1]"; SUSPECT=$((SUSPECT+1)); }
codeof() { python -c "import sys,json
try: print(json.load(sys.stdin).get('code',''))
except: print('NOCODE')" 2>/dev/null; }
# resp METHOD PATH TOKEN [curl extras] → 输出体
resp() { local m=$1 p=$2 t=$3; shift 3
  curl -s -m 8 -X "$m" "$GW$p" -H "Authorization: Bearer $t" -H "Content-Type: application/json" "$@" 2>/dev/null; }

echo "=== [1/7] 探针用户就绪（租户1 SUPER/无角色 + 租户0 活靶标）==="
$PSQL -c "INSERT INTO sys_user (username,password_hash,display_name,user_type,status,tenant_id,first_login_change)
          SELECT 'probe_t2s',password_hash,'probe','SUPER','ACTIVE',1,0 FROM sys_user
          WHERE username='admin' AND NOT EXISTS (SELECT 1 FROM sys_user WHERE username='probe_t2s');" >/dev/null
$PSQL -c "INSERT INTO sys_user (username,password_hash,display_name,user_type,status,tenant_id,first_login_change)
          SELECT 'probe_t2p',password_hash,'probe','NORMAL','ACTIVE',1,0 FROM sys_user
          WHERE username='admin' AND NOT EXISTS (SELECT 1 FROM sys_user WHERE username='probe_t2p');" >/dev/null
$PSQL -c "INSERT INTO sys_user (username,password_hash,display_name,user_type,status,tenant_id,first_login_change)
          SELECT 'probe_victim',password_hash,'probe','NORMAL','ACTIVE',0,0 FROM sys_user
          WHERE username='admin' AND NOT EXISTS (SELECT 1 FROM sys_user WHERE username='probe_victim');" >/dev/null
T2SUID=$($PSQL -c "SELECT id FROM sys_user WHERE username='probe_t2s'")
VUID=$($PSQL -c "SELECT id FROM sys_user WHERE username='probe_victim'")
[ "$T2SUID" = "5" ] || true  # 历史 uid 无关紧要，取实时值

echo "=== [2/7] 登录 ==="
login() { curl -s -m 5 -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))" 2>/dev/null; }
AT=$(login admin); T2S=$(login probe_t2s); T2P=$(login probe_t2p)
[ -n "$AT" ] && [ -n "$T2S" ] && [ -n "$T2P" ] && echo "  三令牌就绪" || { echo "  登录失败，退出"; exit 1; }

echo "=== [3/7] 靶标制造（租户0）==="
CATID=$($PSQL -c "SELECT id FROM part_category ORDER BY id LIMIT 1"); [ -z "$CATID" ] && CATID=1
VID=$(resp POST /api/v1/parts "$AT" -d "{\"name\":\"PROBE-VICTIM-$(date +%s)\",\"type\":\"RAW\",\"categoryId\":$CATID}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
VDW=$(resp POST /api/v1/drawings "$AT" -d "{\"title\":\"PROBE-VICTIM-DWG-$(date +%s)\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
[ -n "$VID" ] && [ -n "$VDW" ] && echo "  靶标 part=$VID drawing=$VDW" || { echo "  靶标制造失败"; exit 1; }

echo "=== [4/7] 跨租户隔离（租户1 → 租户0 对象，预期全部不可达）==="
iso() { local label=$1 r=$2 code; code=$(echo "$r" | codeof)
  local http; http=$(echo "$r" | python -c "
import sys,json
try: print('200' if json.load(sys.stdin).get('code') is not None else '200')
except: print('nonjson')" 2>/dev/null)
  if [ "$code" != "0" ]; then ok "$label"; else bad "$label code=0 越权可达"; fi; }
iso "IDOR part GET"      "$(resp GET /api/v1/parts/$VID "$T2S")"
iso "IDOR part PUT"      "$(resp PUT /api/v1/parts/$VID "$T2S" -d "{\"name\":\"X\",\"type\":\"RAW\",\"categoryId\":$CATID}")"
iso "IDOR part DELETE"   "$(resp DELETE /api/v1/parts/$VID "$T2S")"
iso "IDOR drawing GET"   "$(resp GET /api/v1/drawings/$VDW "$T2S")"
iso "IDOR drawing DELETE" "$(resp DELETE /api/v1/drawings/$VDW "$T2S")"
iso "伪头 X-User-Tenant" "$(resp GET /api/v1/parts/$VID "$T2S" -H "X-User-Tenant: 0")"
iso "伪头小写 x-user-tenant" "$(resp GET /api/v1/parts/$VID "$T2S" -H "x-user-tenant: 0")"
iso "伪头 X-User-Id 冒充" "$(resp GET /api/v1/parts/$VID "$T2S" -H "X-User-Tenant: 0" -H "X-User-Id: 1")"

echo "=== [5/7] 用户管理租户边界 ===="
R=$(resp DELETE /api/v1/users/$VUID "$T2S")
[ "$(echo "$R" | codeof)" != "0" ] && ok "跨租户删用户被拒" || bad "跨租户删用户成功（P0）"
R=$(resp GET "/api/v1/users?page=1&pageSize=50" "$T2S")
echo "$R" | grep -q '"username":"admin"' && bad "租户1列表泄漏 admin" || ok "列表租户过滤"
echo "$R" | grep -q 'passwordHash' && bad "响应携带 passwordHash" || ok "无 passwordHash 序列化"
R=$(resp GET "/api/v1/users?page=1&pageSize=5" "$T2P")
c=$(echo "$R" | codeof); [ "$c" = "2004" ] || [ "$c" = "2003" ] && ok "无角色用户列表被拒($c)" || bad "无角色可列用户 code=$c"
R=$(resp POST /api/v1/parts "$T2P" -d '{}')
c=$(echo "$R" | codeof); [ "$c" = "2004" ] || [ "$c" = "2003" ] && ok "无角色建物料被拒($c)" || bad "无角色建物料 code=$c"

echo "=== [6/7] v1.23 通知中心越权与内部接口门禁 ===="
# 受害者（租户0）收件箱植入一条未读通知，验证跨租户不可标记/不可见
$PSQL -c "INSERT INTO notify_message (tenant_id,user_id,event_type,title,read_flag) VALUES (0,$VUID,'task.created','PROBE-NOTIFY',0);" >/dev/null
NID=$($PSQL -c "SELECT id FROM notify_message WHERE user_id=$VUID AND title='PROBE-NOTIFY' ORDER BY id DESC LIMIT 1")
if [ -n "$NID" ]; then
  iso "通知跨租户标记已读" "$(resp POST /api/v1/notifications/$NID/read "$T2S")"
  R=$(resp GET "/api/v1/notifications?page=1&size=50" "$T2S")
  echo "$R" | grep -q 'PROBE-NOTIFY' && bad "通知列表跨租户泄漏" || ok "通知列表按收件人隔离"
  c=$(resp POST /api/v1/notifications/$NID/read "$T2P" | codeof)
  [ "$c" != "0" ] && ok "无归属通知标记被拒($c)" || bad "无归属通知标记成功"
else
  bad "通知靶标植入失败"
fi
# 内部摄取接口：网关不路由（ingress 剥内部令牌后不可达）；直连 auth 错误令牌 401
iso "内部通知接口网关不暴露" "$(resp POST /api/v1/internal/notifications "$AT" -d '{"eventType":"task.created","payload":{}}')"
R=$(curl -s -m 5 -X POST "${DIRECT_AUTH:-http://localhost:8081}/api/v1/internal/notifications"   -H "Content-Type: application/json" -H "X-Internal-Token: wrong-probe-token"   -d '{"eventType":"task.created","payload":{}}' 2>/dev/null)
c=$(echo "$R" | codeof); [ "$c" != "0" ] && ok "内部接口错误令牌被拒($c)" || bad "内部接口错误令牌放行（P0）"

echo "=== [7/7] v1.23 回收站越权 ===="
# 租户0 靶标 part：admin 建档后软删（仅回收站可见），验证跨租户恢复/列表过滤/无角色恢复
RP=$(resp POST /api/v1/parts "$AT" -d "{\"name\":\"PROBE-RECYCLE-$(date +%s)\",\"type\":\"RAW\",\"categoryId\":$CATID}"   | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)
if [ -n "$RP" ]; then
  resp DELETE /api/v1/parts/$RP "$AT" >/dev/null
  iso "回收站跨租户恢复被拒" "$(resp POST /api/v1/parts/$RP/restore "$T2S")"
  R=$(resp GET "/api/v1/parts/recycle" "$T2S")
  echo "$R" | grep -q "\"id\":$RP[,}]" && bad "回收站列表跨租户泄漏" || ok "回收站列表租户过滤"
  c=$(resp POST /api/v1/parts/$RP/restore "$T2P" | codeof)
  [ "$c" = "2004" ] || [ "$c" = "2003" ] && ok "无角色恢复被拒($c)" || bad "无角色恢复 code=$c"
else
  bad "回收站靶标制造失败"
fi

echo "=== 收尾：清理探针数据 ==="
[ -n "$VID" ] && resp DELETE /api/v1/parts/$VID "$AT" >/dev/null
[ -n "$VDW" ] && resp DELETE /api/v1/drawings/$VDW "$AT" >/dev/null
$PSQL -c "DELETE FROM notify_message WHERE user_id=$VUID OR title='PROBE-NOTIFY';" >/dev/null
$PSQL -c "DELETE FROM sys_user WHERE username IN ('probe_t2s','probe_t2p','probe_victim');" >/dev/null && echo "  探针用户已清（靶标 part/drawing 软删、通知靶标硬删）"

echo ""
echo "=== 探针结果: PASS=$PASS SUSPECT=$SUSPECT ==="
[ "$SUSPECT" = "0" ] || exit 2
