#!/usr/bin/env bash
# 停止全部 OpenForge 开发服务
set -e
for svc in gateway mono auth material doc workflow change knowledge project metadata connector; do
  # 按 jar 名匹配进程
  pid=$(ps aux 2>/dev/null | grep "openforge-$svc-0.1.0-SNAPSHOT-exec.jar" | grep -v grep | awk '{print $1}' | head -1)
  if [ -n "$pid" ]; then
    kill "$pid" && echo "stopped $svc ($pid)"
  fi
done
# Windows 兜底：Git Bash 的 ps 看不到原生 java 进程命令行（文件锁残留会卡住下次 dev-up 的 CDS 刷新），
# 按命令行含 openforge 精确补杀（无匹配时为 no-op）
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'openforge' } | ForEach-Object { Write-Host ('stopped (win) ' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }" 2>/dev/null || true
echo "AI 网关(uvicorn:8001)与前端(vite:5173)请手动停止"
