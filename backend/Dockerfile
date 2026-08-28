# OpenForge 后端服务统一构建镜像（B4 部署形态）
# 用法（仓库根目录执行）:
#   docker build -f backend/Dockerfile --build-arg MODULE=openforge-metadata --build-arg PORT=8088 -t openforge-metadata .
# 构建阶段全量编译，产物只拷贝目标模块的 fat jar。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/ .
RUN mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre
ARG MODULE=openforge-auth
ARG PORT=8081
ENV PORT=${PORT}
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE ${PORT}
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD wget -q -O /dev/null http://localhost:${PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app.jar"]
