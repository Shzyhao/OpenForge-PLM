#!/usr/bin/env bash
# OpenForge PLM 一键开发环境启动（Git Bash / Linux / macOS）
# 用法: ./scripts/dev-up.sh        # 依赖 + 全部服务
#       ./scripts/dev-up.sh java   # 仅依赖 + Java 服务
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== [1/4] 基础依赖 (PostgreSQL/Redis/MinIO) ==="
docker compose -f "$ROOT/docker-compose.yml" up -d

echo "=== [2/4] 构建后端 ==="
(cd "$ROOT/backend" && mvn -B -ntp -q clean package -DskipTests)

echo "=== [3/4] 启动 Java 服务（后台） ==="
# 需设置 JAVA_HOME 指向 JDK 21
declare -A SERVICES=(
  [gateway]=8080 [auth]=8081 [material]=8082 [doc]=8083
  [workflow]=8084 [change]=8085 [knowledge]=8086 [project]=8087
)
for svc in auth material doc workflow change knowledge project gateway; do
  nohup java -jar "$ROOT/backend/openforge-$svc/target/openforge-$svc-0.1.0-SNAPSHOT.jar" \
    > "/tmp/openforge-$svc.log" 2>&1 &
  echo "  $svc (port ${SERVICES[$svc]}) pid=$! log=/tmp/openforge-$svc.log"
done

echo "=== [4/4] 启动 AI 网关与前端（可选，手动执行） ==="
echo "  AI:   cd ai && uvicorn gateway.main:app --port 8001"
echo "  前端: cd frontend && npm run dev   (http://localhost:5173)"
echo ""
echo "完成。服务健康检查: curl http://localhost:8080/actuator/health"
