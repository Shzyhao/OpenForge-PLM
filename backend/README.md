# OpenForge Backend

Java 21 + Spring Boot 3.3 微服务（Maven 多模块）。

## 模块

| 模块 | 端口 | 说明 |
|------|------|------|
| `openforge-gateway` | 8080 | API 网关（静态路由起步，JWT 校验过滤器随 M1 迭代补充） |
| `openforge-auth` | 8081 | 认证服务：注册 / 登录 / JWT 签发（当前） |
| `openforge-common` | - | 公共库：统一响应体 / 错误码 / 业务异常 / 全局异常处理 |
| `openforge-security` | - | 公共库：`@RequirePermission` 拦截器 + auth 权限查询客户端 |
| `openforge-material` | 8082 | 物料与 BOM 服务 |
| `openforge-doc` | 8083 | 文档服务 |
| `openforge-workflow` | 8084 | 流程引擎服务 |
| `openforge-change` | 8085 | 变更服务 |
| `openforge-knowledge` | 8086 | 知识库服务 |
| `openforge-project` | 8087 | 项目与报表服务 |
| `openforge-metadata` | 8088 | 元数据内核（F2）：动态对象建模 + DDL 生成器 |

## 本地开发

```bash
# 1. 启动依赖（PostgreSQL / Redis / MinIO）
docker compose up -d          # 仓库根目录执行

# 2. 构建并运行（首次会下载依赖）
cd backend
mvn -B -ntp verify            # 编译 + 单测（Loop L1 验证）

# 3. 启动服务（两个终端分别执行）
mvn -pl openforge-auth spring-boot:run
mvn -pl openforge-gateway spring-boot:run

# 4. 冒烟验证
# 注册
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"password123","displayName":"张三"}'
# 登录
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"password123"}'
```

## 约定

- 数据库变更一律走 Flyway（`db/migration`），禁止手工改库；
- 响应体统一为 `ApiResponse{code, message, data, traceId}`，错误码分段见 `ErrorCode`；
- 生产 JWT 密钥通过 `JWT_SECRET` 环境变量注入（≥32 字节），本地默认值仅供开发。
