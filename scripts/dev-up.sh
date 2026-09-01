#!/usr/bin/env bash
# OpenForge PLM 一键开发环境启动（Git Bash / Linux / macOS）
# 真实环境冒烟经验：16GB 宿主机需小内存参数 + 串行启动，避免 JVM 峰值叠加崩溃
# 用法：./scripts/dev-up.sh                    # 全部 9 服务
#       PROFILE=core ./scripts/dev-up.sh       # 预设子集（见下）——机器吃紧时优先
#       SERVICES="auth metadata gateway" ...   # 自定义子集
#       SKIP_BUILD=1 ...                       # 强制复用现有 jar（默认已按源码新旧自动判断）
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}"
# 小内存画像（性能画像 §2.1）：SerialGC（每服务省 G1 线程/卡表原生开销 50~150MB）+
# 512k 线程栈（默认 1MB × 每服务数十线程）+ 元空间/代码缓存/直接内存显式上限
JVM_OPTS="-Xms48m -Xmx160m -Xss512k -XX:MaxMetaspaceSize=200m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
# dev 收紧线程/连接池（yml 已环境变量化，默认值不变；9×2=18 PG 连接，单人开发足够）
export TOMCAT_MAX_THREADS="${TOMCAT_MAX_THREADS:-10}" TOMCAT_MIN_SPARE="${TOMCAT_MIN_SPARE:-2}"
export HIKARI_MAX_POOL="${HIKARI_MAX_POOL:-2}" HIKARI_MIN_IDLE="${HIKARI_MIN_IDLE:-1}"

echo "=== [1/4] 基础依赖 (PostgreSQL；Redis/MinIO 为 extras 可选) ==="
docker compose -f "$ROOT/docker-compose.yml" up -d
# EXTRAS=1 附加 Redis/MinIO（当前零代码使用）；NACOS=1 见下
# 等 PG 真正就绪再继续（省掉服务首个 60s 健康等待窗的浪费）
for i in $(seq 1 30); do
  docker exec openforge-pg pg_isready -U openforge >/dev/null 2>&1 && break
  [ "$i" = 30 ] && echo "  警告: PostgreSQL 60s 未就绪，服务启动可能失败"
  sleep 2
done

# F1 尾：Nacos 服务发现（可选，NACOS=1 启用）——A4 模块注册表的演进实现，
# 开启后服务注册到 Nacos（模块路由/启停语义仍由 sys_module 注册表承载）
if [ "${NACOS:-0}" = "1" ]; then
  echo "=== [Nacos] 启动注册中心（standalone） ==="
  docker compose -f "$ROOT/docker-compose.yml" --profile nacos up -d nacos
  export NACOS_ENABLED=true NACOS_ADDR=localhost:8848
  export NACOS_CONFIG_ENABLED=true   # B1：配置中心随 NACOS=1 一并启用（openforge-<svc>.yml 远程覆盖本地）
fi

echo "=== [2/4] 构建后端（需先停止运行中的服务，否则 jar 被锁） ==="
# SKIP_BUILD=1 强制跳过；默认按源码新旧自动判断——无改动时省 1~2 分钟与构建内存峰值
# （多模块构建峰值曾是闪退直接诱因之一：MAVEN_OPTS 限 512m）
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -XX:+UseSerialGC}"
MARKER_JAR="$ROOT/backend/openforge-gateway/target/openforge-gateway-0.1.0-SNAPSHOT.jar"
STALE_SRC="$(find "$ROOT/backend" \( -name '*.java' -o -name 'pom.xml' \) -newer "$MARKER_JAR" -print -quit 2>/dev/null)"
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  echo "  SKIP_BUILD=1：复用现有 target jar"
elif [ ! -f "$MARKER_JAR" ] || [ -n "$STALE_SRC" ]; then
  echo "  源码有更新（${STALE_SRC#$ROOT/}），重新打包…"
  (cd "$ROOT/backend" && mvn -B -ntp -q package -DskipTests)
else
  echo "  源码未变，复用现有 jar（SKIP_BUILD=1 可强制）"
fi

echo "=== [3/4] 串行启动 Java 服务（等待健康后启动下一个） ==="
# 依赖顺序：auth(取号/权限/模块注册中心) → 业务服务 → gateway
# PROFILE 预设（机器吃紧时的瘦身入口，A4 模块注册：不启动的服务不注册/不路由）：
#   core = auth gateway metadata doc workflow  （主链路：登录/动态建模/文档/审批）
#   lite = auth gateway                        （前端联调骨架）
#   full = 全部 9 服务（默认）；SERVICES 显式指定时优先于 PROFILE
declare -A PORTS=(
  [auth]=8081 [material]=8082 [doc]=8083 [workflow]=8084
  [change]=8085 [knowledge]=8086 [project]=8087 [metadata]=8088 [gateway]=8080
)
SVC_ORDER="auth material doc workflow change knowledge project metadata gateway"
case "${PROFILE:-full}" in
  core) PRESET="auth metadata doc workflow gateway" ;;
  lite) PRESET="auth gateway" ;;
  *)    PRESET="$SVC_ORDER" ;;
esac
for svc in ${SERVICES:-$PRESET}; do
  [ -z "${PORTS[$svc]}" ] && { echo "未知服务: $svc（可选: $SVC_ORDER）"; exit 1; }
done
for svc in ${SERVICES:-$PRESET}; do
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
