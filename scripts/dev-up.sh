#!/usr/bin/env bash
# OpenForge PLM 一键开发环境启动（Git Bash / Linux / macOS）
# 真实环境冒烟经验：16GB 宿主机需小内存参数 + 串行启动，避免 JVM 峰值叠加崩溃
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}"
JVM_OPTS="-Xms48m -Xmx160m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1"

echo "=== [1/4] 基础依赖 (PostgreSQL/Redis/MinIO) ==="
docker compose -f "$ROOT/docker-compose.yml" up -d

echo "=== [2/4] 构建后端（需先停止运行中的服务，否则 jar 被锁） ==="
(cd "$ROOT/backend" && mvn -B -ntp -q clean package -DskipTests)

echo "=== [3/4] 串行启动 Java 服务（等待健康后启动下一个） ==="
# 依赖顺序：auth(取号/权限) → 业务服务 → gateway
declare -A PORTS=(
  [auth]=8081 [material]=8082 [doc]=8083 [workflow]=8084
  [change]=8085 [knowledge]=8086 [project]=8087 [gateway]=8080
)
for svc in auth material doc workflow change knowledge project gateway; do
  nohup java $JVM_OPTS -jar "$ROOT/backend/openforge-$svc/target/openforge-$svc-0.1.0-SNAPSHOT.jar" \
    > "/tmp/openforge-$svc.log" 2>&1 &
  port=${PORTS[$svc]}
  for i in $(seq 1 30); do
    sleep 2
    if curl -s -m 2 "http://localhost:$port/actuator/health" 2>/dev/null | grep -q UP; then
      echo "  $svc (:$port) UP"; break
    fi
    [ "$i" = 30 ] && echo "  $svc (:$port) 未在 60s 内就绪，查看 /tmp/openforge-$svc.log"
  done
done

echo "=== [4/4] 可选服务（手动执行） ==="
echo "  AI:   cd ai && pip install -r requirements.txt && python -m uvicorn gateway.main:app --port 8001"
echo "  前端: cd frontend && npm install && npm run dev   (http://localhost:5173)"
echo ""
echo "完成。首个注册用户将自动获得 ADMIN 角色。"
