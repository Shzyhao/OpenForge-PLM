#!/usr/bin/env bash
# Docker 资产盘点与清理（工程约定 #10 工具化，十二轮实测 12.7GB→1.8GB 的制度化沉淀）
# 用法：
#   ./scripts/docker-audit.sh          # 分析模式：全量盘点 + 判定报告（不删任何东西）
#   ./scripts/docker-audit.sh clean    # 清理模式：执行零引用资产清理（先打印再删）
# 执行时机：每次大版本测试或版本更新收尾后（约定 #10）。
#
# 判定锚点（保留 vs 清理的边界）：
#   保留 = 运行中容器引用的镜像/卷/网络 + openforge-* 自建镜像（prod 部署产物）
#   清理 = 停止容器、悬空镜像、构建缓存、无容器挂载的孤儿卷、
#          非 openforge 前缀且不被任何容器使用的镜像（基础镜像需要时重拉，分钟级）
set +e
MODE="${1:-audit}"

echo "=== [1] 总账 ==="
docker system df

echo ""
echo "=== [2] 镜像盘点 ==="
# 运行中容器引用的镜像（保留锚点一）
RUNNING_IMGS=$(docker ps --format '{{.Image}}' | sort -u)
# openforge 自建产物（保留锚点二）
KEEP_IMGS="$(echo "$RUNNING_IMGS"; docker images --format '{{.Repository}}:{{.Tag}}' | grep '^openforge/')"
CLEAN_IMGS=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}} {{.Size}}' \
  | grep -v '<none>' | while read -r id repo tag size; do
      [[ "$repo" == openforge* ]] && continue   # openforge-xxx（连字符）与 openforge/ 均为自建
      echo "$RUNNING_IMGS" | grep -q "^${repo}$" && continue
      echo "$id|$repo:$tag|$size"
    done | sort -u | sort -t'|' -k1,1 -u)

if [ -n "$CLEAN_IMGS" ]; then
  echo "  未被运行容器使用且非 openforge 自建（clean 模式将删除，需要时可重拉）："
  echo "$CLEAN_IMGS" | awk -F'|' '{print "    ✗ " $2 "  " $3}'
else
  echo "  ✓ 无未引用镜像"
fi
DANGLING=$(docker images -f dangling=true -q)
[ -n "$DANGLING" ] && echo "  悬空镜像（必清）：$(echo "$DANGLING" | wc -l) 个" || echo "  ✓ 无悬空镜像"

echo ""
echo "=== [3] 容器盘点 ==="
STOPPED=$(docker ps -a --filter status=exited --filter status=created --format '{{.Names}}|{{.Image}}|{{.Status}}')
if [ -n "$STOPPED" ]; then
  echo "  停止/异常退出容器（clean 模式将删除，重新 up 自动重建）："
  echo "$STOPPED" | awk -F'|' '{print "    ✗ " $1 "  [" $2 "]  " $3}'
else
  echo "  ✓ 无停止容器"
fi

echo ""
echo "=== [4] 卷盘点（孤儿 = 无任何容器挂载）==="
ORPHAN_VOLS=$(docker volume ls -q | while read -r v; do
  [ -z "$(docker ps -a --filter volume="$v" --format '{{.Names}}')" ] && echo "$v"
done)
if [ -n "$ORPHAN_VOLS" ]; then
  echo "  孤儿卷（clean 模式将删除；删除前确认不含需留存的数据）："
  for v in $ORPHAN_VOLS; do echo "    ✗ $v"; done
else
  echo "  ✓ 无孤儿卷"
fi

echo ""
echo "=== [5] 构建缓存 ==="
docker buildx du 2>/dev/null | tail -1 || true

if [ "$MODE" != "clean" ]; then
  echo ""
  echo "=== 分析完成。执行清理：./scripts/docker-audit.sh clean ==="
  exit 0
fi

# ============ 清理模式 ============
echo ""
echo "=== [clean] 开始清理 ==="
[ -n "$STOPPED" ] && echo "$STOPPED" | cut -d'|' -f1 | xargs -r docker rm
[ -n "$DANGLING" ] && docker rmi $DANGLING >/dev/null 2>&1
if [ -n "$CLEAN_IMGS" ]; then
  echo "$CLEAN_IMGS" | cut -d'|' -f2 | xargs -r docker rmi >/dev/null 2>&1
fi
[ -n "$ORPHAN_VOLS" ] && echo "$ORPHAN_VOLS" | xargs -r docker volume rm >/dev/null 2>&1
docker builder prune -af >/dev/null 2>&1
# 清理后再删一次悬空（删 tag 可能新产生）
docker images -f dangling=true -q | xargs -r docker rmi >/dev/null 2>&1

echo "=== 清理后状态 ==="
docker system df
