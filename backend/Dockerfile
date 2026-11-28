# OpenForge 后端服务统一构建镜像（B4 部署形态）
# 用法（仓库根目录执行）:
#   docker build -f backend/Dockerfile --build-arg MODULE=openforge-metadata --build-arg PORT=8088 -t openforge-metadata .
# 构建阶段全量编译，产物只拷贝目标模块的 fat jar。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/ .
# .m2 挂 BuildKit 缓存卷：依赖跨构建持久（mono 双镜像形态下重复构建只编译改动源码，不再全量下载）
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre
ARG MODULE=openforge-auth
ARG PORT=8081
ENV PORT=${PORT}
# boot 插件 exec classifier（v1.12.0）：主 artifact 为普通 jar、可执行 fat jar 带 -exec 后缀，
# 通配符必须锚定 exec 否则多源 COPY 到单文件目标构建失败
COPY --from=build /build/${MODULE}/target/${MODULE}-*-exec.jar /app.jar
# 时区（2026-11-28 审查实锤：容器形态 JVM 曾为 UTC——LocalDate.now() 零处显式时区，
# 北京 0-8 点的单据编号日期回退一天、时间显示早 8 小时）；TZ 与 -Duser.timezone 双保险
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
EXPOSE ${PORT}
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD wget -q -O /dev/null http://localhost:${PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app.jar"]
