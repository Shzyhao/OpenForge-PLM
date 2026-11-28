#!/usr/bin/env bash
# 备份与恢复校验（十轮改进 S1，R11 T11-7/8 演练工具化）
# 用法：
#   ./scripts/backup.sh backup            # 全库 pg_dump + data/ 文件目录打包（默认）
#   ./scripts/backup.sh verify <dump>     # 恢复到临时库 openforge_backup_verify 并对账行数（不动原库）
# 前置：openforge-pg 容器运行中；输出到 backups/（git 忽略）
# 说明：pg_dump 在线一致快照（dev 单实例足够）；关键表行数对账 = 备份可用性的最低断言
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$ROOT/backups"
CONTAINER="${CONTAINER:-openforge-pg}"
DB="${DB:-openforge}"
USER="${PGUSER:-openforge}"
VERIFY_DB="openforge_backup_verify"
# 对账表（源库 vs 恢复库行数必须一致）。
# 高频追加日志表（sys_audit_log/sys_login_log/conn_exec_log）不入严格对账：
# 在线备份窗口内系统仍在写入，行数快照必然漂移（演练实测差 3 行）——其完整性由全表 pg_dump 本身保证。
CHECK_TABLES="sys_user sys_tenant sys_number_rule sys_number_counter part doc_info drw_drawing drw_drawing_file conn_definition conn_credential workflow_instance"

mkdir -p "$BACKUP_DIR"

cmd="${1:-backup}"

if [ "$cmd" = "backup" ]; then
  STAMP="$(date +%Y%m%d-%H%M%S)"
  DUMP="$BACKUP_DIR/openforge-$STAMP.dump"
  FILES="$BACKUP_DIR/openforge-files-$STAMP.tgz"
  echo "== [1/2] pg_dump → $DUMP"
  docker exec "$CONTAINER" pg_dump -U "$USER" -d "$DB" -Fc > "$DUMP"
  [ -s "$DUMP" ] || { echo "✗ pg_dump 输出为空"; exit 1; }
  echo "== [2/2] data/ 文件目录 → $FILES"
  tar -czf "$FILES" -C "$ROOT" data 2>/dev/null || true
  echo "完成："
  ls -lh "$DUMP" "$FILES" | awk '{print "  " $9 "  " $5}'
  echo "恢复校验：./scripts/backup.sh verify $DUMP"

elif [ "$cmd" = "verify" ]; then
  DUMP="$2"
  [ -f "$DUMP" ] || { echo "用法：./scripts/backup.sh verify <dump文件>"; exit 1; }
  echo "== [1/4] 重建临时库 $VERIFY_DB"
  docker exec "$CONTAINER" psql -U "$USER" -d postgres -c "DROP DATABASE IF EXISTS $VERIFY_DB;" >/dev/null
  docker exec "$CONTAINER" psql -U "$USER" -d postgres -c "CREATE DATABASE $VERIFY_DB;" >/dev/null
  trap 'docker exec '"$CONTAINER"' psql -U '"$USER"' -d postgres -c "DROP DATABASE IF EXISTS '"$VERIFY_DB"';" >/dev/null 2>&1' EXIT
  echo "== [2/4] pg_restore"
  docker exec -i "$CONTAINER" pg_restore -U "$USER" -d "$VERIFY_DB" --no-owner < "$DUMP" 2>&1 | grep -v "already exists" || true
  echo "== [3/4] 关键表行数对账"
  FAIL=0
  printf "  %-24s %10s %10s\n" "表" "源库" "恢复库"
  for t in $CHECK_TABLES; do
    SRC=$(docker exec "$CONTAINER" psql -U "$USER" -d "$DB" -t -A -c "SELECT count(*) FROM $t" 2>/dev/null || echo "N/A")
    DST=$(docker exec "$CONTAINER" psql -U "$USER" -d "$VERIFY_DB" -t -A -c "SELECT count(*) FROM $t" 2>/dev/null || echo "N/A")
    MARK="✓"; [ "$SRC" = "$DST" ] || { MARK="✗"; FAIL=1; }
    printf "  %-24s %10s %10s  %s\n" "$t" "$SRC" "$DST" "$MARK"
  done
  echo "== [4/4] 取号水位一致性（恢复后不得重号）"
  for rk in part doc drawing ecr project bom; do
    S=$(docker exec "$CONTAINER" psql -U "$USER" -d "$DB" -t -A -c "SELECT coalesce(max(current_value),0) FROM sys_number_counter WHERE rule_key='$rk'")
    D=$(docker exec "$CONTAINER" psql -U "$USER" -d "$VERIFY_DB" -t -A -c "SELECT coalesce(max(current_value),0) FROM sys_number_counter WHERE rule_key='$rk'")
    [ "$S" = "$D" ] || { echo "  ✗ $rk 水位不一致: $S vs $D"; FAIL=1; }
  done
  echo "  水位一致 ✓"
  [ "$FAIL" = "0" ] && echo "备份校验通过" || { echo "备份校验失败"; exit 1; }

else
  echo "用法：./scripts/backup.sh [backup|verify <dump>]"
  exit 1
fi
