#!/usr/bin/env bash
# OpenForge PLM 一键开发环境启动（Git Bash / Linux / macOS）
# 真实环境冒烟经验：16GB 宿主机需小内存参数 + 串行启动，避免 JVM 峰值叠加崩溃
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}"
JVM_OPTS="-Xms48m -Xmx160m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1"

echo "=== [1/4] 基础依赖 (PostgreSQL；Redis/MinIO 为 extras 可选) ==="
docker compose -f "$ROOT/docker-compose.yml" up -d
# EXTRAS=1 附加 Redis/MinIO（当前零代码使用）；NACOS=1 见下

# F1 尾：Nacos 服务发现（可选，NACOS=1 启用）——A4 模块注册表的演进实现，
# 开启后服务注册到 Nacos（模块路由/启停语义仍由 sys_module 注册表承载）
if [ "${NACOS:-0}" = "1" ]; then
  echo "=== [Nacos] 启动注册中心（standalone） ==="
  docker compose -f "$ROOT/docker-compose.yml" --profile nacos up -d nacos
  export NACOS_ENABLED=true NACOS_ADDR=localhost:8848
  export NACOS_CONFIG_ENABLED=true   # B1：配置中心随 NACOS=1 一并启用（openforge-<svc>.yml 远程覆盖本地）
fi

echo "=== [2/4] 构建后端（需先停止运行中的服务，否则 jar 被锁） ==="
# SKIP_BUILD=1 跳过重构建（jar 已是最新时用，省 1~2 分钟与内存峰值）
# MAVEN_OPTS 限流构建 JVM（默认会吃 1/4 物理内存，多模块构建峰值是闪退诱因之一）
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -XX:+UseSerialGC}"
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  (cd "$ROOT/backend" && mvn -B -ntp -q clean package -DskipTests)
else
  echo "  SKIP_BUILD=1：复用现有 target jar"
fi

echo "=== [3/4] 串行启动 Java 服务（等待健康后启动下一个） ==="
# 依赖顺序：auth(取号/权限/模块注册中心) → 业务服务 → gateway
# SERVICES 环境变量可裁剪启动集（A4 模块注册：不启动的服务不注册/不路由）：
#   SERVICES="auth metadata gateway" ./scripts/dev-up.sh
declare -A PORTS=(
  [auth]=8081 [material]=8082 [doc]=8083 [workflow]=8084
  [change]=8085 [knowledge]=8086 [project]=8087 [metadata]=8088 [gateway]=8080
)
SVC_ORDER="auth material doc workflow change knowledge project metadata gateway"
for svc in ${SERVICES:-$SVC_ORDER}; do
  [ -z "${PORTS[$svc]}" ] && { echo "未知服务: $svc（可选: $SVC_ORDER）"; exit 1; }
done
for svc in ${SERVICES:-$SVC_ORDER}; do
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
echo "完成。管理员账号 admin 的初始密码打印在 auth 启动日志（/tmp/openforge-auth.log），首登强制改密；自助注册默认关闭。"
