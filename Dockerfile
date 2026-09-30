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

# 审计日志持久化挂载点（对应 logback-spring.xml 的 logs/aiops-audit.log）。
# 非 root 运行是容器逃逸的纵深防御：日志目录必须先 chown，否则 USER app 后无写权限
RUN mkdir -p /app/logs \
    && useradd -r -u 1001 app \
    && chown -R app:app /app/logs
USER app
VOLUME /app/logs

# 暴露应用端口
EXPOSE 8080

# 容器内存感知：JVM 默认能读 cgroup 上限（UseContainerSupport），显式把堆压到 75%
# 防止误配时堆过大与宿主 Ollama 抢内存。健康检查由部署编排层负责（compose/k8s
# healthcheck 调 /actuator/health），镜像内不装 curl/wget 保持体积
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# 部署约束：单实例设计——事件状态机（IncidentStore）与深度诊断任务队列（DiagnosisService）
# 都在进程内存，审计日志在本地卷。横向扩多副本会导致重复告警、诊断任务不共享、审计分散；
# 需要多实例时必须先引入共享状态存储（Redis 等）并改造这两处，再考虑扩容。

# 环境变量声明（方便 docker run -e 注入）：
#   SPRING_PROFILES_ACTIVE=prod         生产 profile（关闭 DebugController 等故障注入夹具；
#                                       ProdSecurityGuard 会校验 AIOPS_API_TOKEN/Milvus 密码，缺失拒绝启动）
#   AIOPS_API_TOKEN=你的强随机令牌         /api/** 鉴权令牌（prod 必填，缺失启动即失败）
#   OLLAMA_BASE_URL=http://宿主机IP:11434 Ollama 服务地址（Docker 内不能用 localhost）
#   PROMETHEUS_BASE_URL=http://宿主机IP:9090  Prometheus API 地址
#   MILVUS_HOST=宿主机IP  Milvus gRPC 地址
#   MILVUS_PASSWORD=你的强密码  Milvus 凭据（生产必须改，默认值 milvus 会被启动自检拒绝）
#   AIOPS_DIAGNOSIS_WORKER_TIMEOUT_MINUTES=15  深度诊断可调参数（队列容量/防抖窗口/超时/保留条数，见 application.yml）

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
