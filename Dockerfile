# 多阶段构建：构建阶段用 JDK 21，运行阶段只需 JRE 21，镜像体积减半。
# 固定版本而非 latest，避免重建时被静默升级。

# ===== 阶段 1：构建 JAR =====
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# 先复制 gradle wrapper 和 build 脚本，利用 Docker 缓存层加速构建
COPY gradle/ ./gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew

# 复制源码
COPY src/ ./src/

# 构建 fat JAR（跳过测试，测试在 CI 流水线单独跑）
# mavenLocal 指向 Windows 路径，在容器内不存在，gradle 自动 fallback 到 mavenCentral
RUN ./gradlew bootJar -x test --no-daemon

# ===== 阶段 2：运行 JRE 镜像 =====
FROM eclipse-temurin:21-jre

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/build/libs/*.jar app.jar

# 审计日志持久化挂载点（对应 logback-spring.xml 的 logs/aiops-audit.log）
RUN mkdir -p /app/logs
VOLUME /app/logs

# 暴露应用端口
EXPOSE 8080

# 环境变量声明（方便 docker run -e 注入）：
#   SPRING_PROFILES_ACTIVE=prod         生产 profile（关闭 DebugController 等故障注入夹具）
#   AIOPS_API_TOKEN=你的强随机令牌         /api/** 鉴权令牌（prod 必填）
#   OLLAMA_BASE_URL=http://宿主机IP:11434 Ollama 服务地址（Docker 内不能用 localhost）
#   PROMETHEUS_BASE_URL=http://宿主机IP:9090  Prometheus API 地址
#   MILVUS_HOST=宿主机IP  Milvus gRPC 地址
#   MILVUS_PASSWORD=你的强密码  Milvus 凭据（生产必须改）

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
