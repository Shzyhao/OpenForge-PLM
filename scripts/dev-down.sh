#!/usr/bin/env bash
# 停止全部 OpenForge 开发服务
set -e
for svc in gateway auth material doc workflow change knowledge project; do
  # 按 jar 名匹配进程
  pid=$(ps aux 2>/dev/null | grep "openforge-$svc-0.1.0-SNAPSHOT.jar" | grep -v grep | awk '{print $1}' | head -1)
  if [ -n "$pid" ]; then
    kill "$pid" && echo "stopped $svc ($pid)"
  fi
done
echo "AI 网关(uvicorn:8001)与前端(vite:5173)请手动停止"
