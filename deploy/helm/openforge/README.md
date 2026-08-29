# OpenForge Helm Chart（B4 K8s 部署骨架）

> 状态：骨架交付——本仓 CI 未安装 helm，`helm lint` / `helm template` 的完整校验
> 需在装有 helm 的环境执行；YAML 语法已经脚本级校验（剥离模板指令后逐文档解析）。
> 与 `docker-compose.prod.yml` 同源（服务清单/环境变量/健康检查一一对应）。

## 使用

```bash
# 1. 构建并推送镜像（或改 values 用你的 registry）
docker build -f backend/Dockerfile --build-arg MODULE=openforge-auth --build-arg PORT=8081 -t <registry>/openforge-auth:0.1.0 .
# ... 其余服务同理（MODULE/PORT 对照 values.yaml services 清单）

# 2. 安装
helm install openforge ./deploy/helm/openforge \
  --set global.postgres.password=... \
  --set global.jwtSecret=... \
  --set global.internalToken=... \
  --set global.imageRegistry=<registry>/

# 3. 验证（模块注册自动路由：各服务经 MODULE_SERVICE_URI 上报，网关 30s 内生成路由）
kubectl get pods && curl http://<gateway-ingress>/actuator/health
```

## 结构

- `values.yaml`：`services` 清表驱动八后端服务 Deployment/Service；
  gateway（含 Ingress）/ai-gateway/frontend/postgres(StatefulSet) 独立模板
- 密钥：values 仅作演示，生产应改用 `externalSecret`/sealed-secrets 注入
  `global.postgres.password`、`global.jwtSecret`、`global.internalToken`
- 与 compose 形态的等价性：环境变量、`MODULE_SERVICE_URI`、readinessProbe 均同源
