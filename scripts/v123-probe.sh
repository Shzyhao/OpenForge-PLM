#!/usr/bin/env bash
# v1.23 三模块端到端探针（R17~R20 收口）：审批委托闭环 → 站内通知（含 MQ 通道证据）→ 回收站往返
# 用法：./scripts/v123-probe.sh [admin密码]（默认 smoke-test-2026；前置：dev-up 已起栈，EVENT_BUS=1 时同时验证 MQ 通道）
# 断言：委托规则 CRUD+代办办理+delegated_from 追溯；通知 unread/list/markRead/readAll；
#       sys_event_consumed 出现 openforge-notify 消费记录（MQ 通道实锤，EVENT_BUS=0 时此项 SKIP）；
#       回收站 软删→可见→恢复→业务侧回归；/users/options 轻量选人。
# 预期：全部 PASS 退出 0；任一 FAIL 退出 2
set +e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"   # MSYS/原生工具路径统一：curl 与 python 共用相对路径（实纱④同族）
GW="${GW:-http://localhost:8080}"
PASSWORD="${1:-smoke-test-2026}"
PSQL="docker exec openforge-pg psql -U openforge -d openforge -t -A"
# 事件总线判定：脚本进程拿不到 dev-up 的 export，按 broker 容器是否在运行判定
EVENTBUS=$(docker ps --format "{{.Names}}" 2>/dev/null | grep -qx openforge-mq-broker && echo 1 || echo 0)
PASS=0; FAIL=0

ok()   { echo "  PASS [$1]"; PASS=$((PASS+1)); }
bad()  { echo "  ✗FAIL [$1]"; FAIL=$((FAIL+1)); }

# 取 JSON 字段（UTF-8 安全：GBK 控制台下用 buffer+decode，约定 #17）
jget() { python -c "
import sys,json
try:
    v = json.loads(sys.stdin.buffer.read().decode('utf-8','replace')).get('data',{}).get('$1')
    print('' if v is None else v)   # 不得用 or ''——数值 0 会被吞成空串
except Exception: print('')
" 2>/dev/null; }
jcode() { python -c "
import sys,json
try: print(json.loads(sys.stdin.buffer.read().decode('utf-8','replace')).get('code',''))
except Exception: print('NOCODE')
" 2>/dev/null; }
jhas() { python -c "
import sys
d=sys.stdin.buffer.read().decode('utf-8','replace')
print('YES' if ('''$1''') in d else 'NO')
" 2>/dev/null; }
resp() { local m=$1 p=$2 t=$3; shift 3
  curl -s -m 8 -X "$m" "$GW$p" -H "Authorization: Bearer $t" -H "Content-Type: application/json" "$@" 2>/dev/null; }

echo "=== [1/6] 登录与被委托人就绪 ==="
login() { curl -s -m 5 -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" | jget accessToken; }
AT=$(login admin)
[ -n "$AT" ] && ok "admin 登录" || { echo "  登录失败，退出"; exit 1; }
ADMINID=$($PSQL -c "SELECT id FROM sys_user WHERE username='admin'")
$PSQL -c "INSERT INTO sys_user (username,password_hash,display_name,user_type,status,tenant_id,first_login_change)
          SELECT 'probe_agent',password_hash,'probe','NORMAL','ACTIVE',0,0 FROM sys_user
          WHERE username='admin' AND NOT EXISTS (SELECT 1 FROM sys_user WHERE username='probe_agent');" >/dev/null
AGENTID=$($PSQL -c "SELECT id FROM sys_user WHERE username='probe_agent'")
AG=$(login probe_agent)
[ -n "$AGENTID" ] && [ "$AGENTID" != "$ADMINID" ] && ok "被委托人 probe_agent=$AGENTID" || { echo "  探针用户就绪失败"; exit 1; }

echo "=== [2/6] 审批委托闭环 ==="
R=$(resp POST /api/v1/workflow/delegates "$AT" -d "{\"agentId\":$AGENTID,\"startTime\":\"$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%S)\",\"remark\":\"v123-probe\"}")
DID=$(echo "$R" | jget id)
[ -n "$DID" ] && ok "创建委托规则 id=$DID" || bad "创建委托规则失败: $(echo "$R" | head -c 100)"
c=$(resp POST /api/v1/workflow/delegates "$AT" -d "{\"agentId\":$ADMINID,\"startTime\":\"$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%S)\"}" | jcode)
[ "$c" != "0" ] && ok "自委托被拒($c)" || bad "自委托放行"
[ "$(resp GET /api/v1/workflow/delegates "$AT" | jhas "\"id\":$DID")" = "YES" ] && ok "委托规则列表可见" || bad "委托规则列表缺失"
# 撤销越权：被委托人撤销委托人规则 → 按不存在应答
c=$(resp DELETE /api/v1/workflow/delegates/$DID "$AG" | jcode)
[ "$c" != "0" ] && ok "被委托人撤销被拒($c)" || bad "被委托人撤销成功（越权）"

echo "=== [3/6] 流程任务经委托进入代办待办并办理（delegated_from 追溯） ==="
# definition 须为 JSON 字符串：bash 拼 wrapper，内层 JSON 无中文无双引号歧义，直接组装
DEFINNER="{\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"a1\",\"type\":\"APPROVAL\",\"name\":\"PROBE-NODE\",\"assignee\":{\"type\":\"USER\",\"value\":\"$ADMINID\"}},{\"id\":\"end\",\"type\":\"END\"}],\"edges\":[{\"from\":\"start\",\"to\":\"a1\"},{\"from\":\"a1\",\"to\":\"end\"}]}"
echo "{\"defKey\":\"v123-probe-flow\",\"name\":\"v123-probe\",\"definition\":\"$(echo "$DEFINNER" | sed 's/"/\\"/g')\"}" > .v123probe.def.json
resp POST /api/v1/workflow/defs "$AT" --data-binary @.v123probe.def.json >/dev/null
IID=$(resp POST /api/v1/workflow/instances "$AT" -d "{\"defKey\":\"v123-probe-flow\",\"bizType\":\"PROBE\",\"bizId\":1}" | jget id)
[ -n "$IID" ] && ok "启动探针流程实例 id=$IID" || bad "启动探针流程失败"
TASKID=$(resp GET /api/v1/workflow/tasks/my "$AG" | python -c "
import sys,json
try:
    tasks=json.loads(sys.stdin.buffer.read().decode('utf-8','replace')).get('data') or []
    via=[t for t in tasks if t.get('viaDelegation')]
    print(via[0]['id'] if via else (tasks[0]['id'] if tasks else ''))
except Exception: print('')
" 2>/dev/null)
[ -n "$TASKID" ] && ok "代办待办出现任务 id=$TASKID（viaDelegation）" || bad "被委托人待办未见委托任务"
# comment 必须纯 ASCII：curl 命令行内联中文经 Git Bash ANSI 转码会致服务端 JSON parse error（smoke 头注释实纱④）
STATE=$(resp POST /api/v1/workflow/tasks/$TASKID/act "$AG" -d '{"action":"APPROVE","comment":"v123-probe act"}' | jget state)
[ "$STATE" = "COMPLETED" ] && ok "代办办理推进至 COMPLETED" || bad "代办办理失败 state=$STATE"
if [ -n "$TASKID" ]; then
  DF=$($PSQL -c "SELECT COALESCE(delegated_from,0) FROM workflow_task WHERE id=$TASKID")
  [ "$DF" = "$ADMINID" ] && ok "delegated_from 追溯原指派人" || bad "delegated_from=$DF 预期 $ADMINID"
else
  bad "无任务可查 delegated_from"
fi
c=$(resp DELETE /api/v1/workflow/delegates/$DID "$AT" | jcode)
[ "$c" = "0" ] && ok "委托人撤销规则" || bad "委托人撤销失败($c)"

echo "=== [4/6] 站内通知（task.created→收件箱；EVENT_BUS=1 时验证 MQ 消费证据） ==="
FOUND=0
for i in $(seq 1 15); do
  CNT=$(resp GET /api/v1/notifications/unread-count "$AT" | jget count)
  [ -n "$CNT" ] && [ "$CNT" -ge 1 ] 2>/dev/null && { FOUND=1; break; }
  sleep 2
done
[ "$FOUND" = "1" ] && ok "admin 未读通知出现（count=$CNT）" || bad "task.created 未产生通知"
LIST=$(resp GET "/api/v1/notifications?page=1&size=10" "$AT")
[ "$(echo "$LIST" | jhas 'PROBE-NODE')" = "YES" ] && ok "通知标题含审批节点名" || bad "通知列表未见 PROBE-NODE"
NID=$(echo "$LIST" | python -c "
import sys,json
try:
    d=json.loads(sys.stdin.buffer.read().decode('utf-8','replace')).get('data',{}).get('list') or []
    print(d[0]['id'] if d else '')
except Exception: print('')
" 2>/dev/null)
[ -n "$NID" ] && { c=$(resp POST /api/v1/notifications/$NID/read "$AT" | jcode)
  [ "$c" = "0" ] && ok "单条已读" || bad "单条已读失败($c)"; } || bad "通知列表为空无法已读"
RAW=$(resp POST /api/v1/notifications/read-all "$AT")
c=$(echo "$RAW" | jcode)
CNT2=$(resp GET /api/v1/notifications/unread-count "$AT" | jget count)
[ "$c" = "0" ] && [ "$CNT2" = "0" ] && ok "全部已读清零" || bad "全部已读后 count=$CNT2 c=$c"
if [ "$EVENTBUS" = "1" ]; then
  MQ=0
  for i in $(seq 1 15); do
    N=$($PSQL -c "SELECT COUNT(*) FROM sys_event_consumed WHERE consumer='openforge-notify'")
    [ -n "$N" ] && [ "$N" -ge 1 ] 2>/dev/null && { MQ=1; break; }
    sleep 2
  done
  [ "$MQ" = "1" ] && ok "MQ 通道实锤：sys_event_consumed 有 openforge-notify 记录（$N 条）" \
                  || bad "EVENT_BUS=1 但未见 notify 消费组消费记录"
else
  echo "  SKIP [MQ 通道证据]（OPENFORGE_EVENT_ENABLED!=1，本次走 HTTP 回退通道）"
fi

echo "=== [5/6] 回收站往返（part） ==="
CATID=$($PSQL -c "SELECT id FROM part_category ORDER BY id LIMIT 1"); [ -z "$CATID" ] && CATID=1
RP=$(resp POST /api/v1/parts "$AT" -d "{\"name\":\"PROBE-V123-RECYCLE-$(date +%s)\",\"type\":\"RAW\",\"categoryId\":$CATID}" | jget id)
[ -n "$RP" ] && ok "靶标物料建档 id=$RP" || bad "靶标物料建档失败"
c=$(resp DELETE /api/v1/parts/$RP "$AT" | jcode)
[ "$c" = "0" ] && ok "软删入回收站" || bad "软删失败($c)"
[ "$(resp GET /api/v1/parts/recycle "$AT" | jhas "\"id\":$RP,")" = "YES" ] && ok "回收站列表可见" || bad "回收站列表未见靶标"
c=$(resp POST /api/v1/parts/$RP/restore "$AT" | jcode)
[ "$c" = "0" ] && ok "恢复成功" || bad "恢复失败($c)"
[ "$(resp GET /api/v1/parts/recycle "$AT" | jhas "\"id\":$RP,")" = "NO" ] && ok "恢复后移出回收站" || bad "恢复后仍在回收站"
[ "$(resp GET "/api/v1/parts?page=1&pageSize=100" "$AT" | jhas "\"id\":$RP,")" = "YES" ] && ok "业务列表回归" || bad "业务列表未见恢复物料"
c=$(resp POST /api/v1/parts/$RP/restore "$AT" | jcode)
[ "$c" != "0" ] && ok "重复恢复按不存在应答($c)" || bad "重复恢复放行"
resp DELETE /api/v1/parts/$RP "$AT" >/dev/null   # 收尾：靶标软删（与既有探针同规）

echo "=== [6/6] 用户轻量选项（委托选人，不走 user:manage） ==="
R=$(resp GET /api/v1/users/options "$AG")
[ "$(echo "$R" | jcode)" = "0" ] && ok "普通登录用户可读 options" || bad "options 被拒"
echo "$R" | grep -Eq 'passwordHash|email|"status"|userType' && bad "options 暴露管理字段" || ok "options 仅轻量字段(id/username/displayName)"

echo "=== 收尾：清理探针数据 ==="
rm -f .v123probe.def.json
$PSQL -c "DELETE FROM workflow_delegate WHERE remark='v123-probe';" >/dev/null
$PSQL -c "DELETE FROM sys_user WHERE username='probe_agent';" >/dev/null && echo "  探针用户/委托规则已清（part/流程实例/通知留档）"

echo ""
echo "=== 探针结果: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" = "0" ] || exit 2
