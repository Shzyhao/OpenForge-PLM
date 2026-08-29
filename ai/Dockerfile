# OpenForge AI 中台网关
FROM python:3.11-slim
WORKDIR /app
COPY ai/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY ai/ ./
EXPOSE 8001
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://localhost:8001/healthz')" || exit 1
# 模块注册中心地址与自身服务地址经环境变量注入（OPENFORGE_AUTH_BASE_URL /
# OPENFORGE_MODULE_SERVICE_URI / OPENFORGE_INTERNAL_TOKEN / LLM 三元组）
CMD ["python", "-m", "uvicorn", "gateway.main:app", "--host", "0.0.0.0", "--port", "8001"]
