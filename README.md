# aiops-copilot：给 Spring Boot 应用配一个“AI 值班运维工程师”

每 60 秒主动拉取 Prometheus 核心指标喂给本地大模型，输出“状态 / 根因 / 建议”结构化诊断；
同一故障经事件状态机去重，只打扰人一次；复杂故障自动升级给带思考链的 deepseek-r1 做异步深度诊断；
另有 RAG 知识库问答与 Function Calling 交互排障两个入口。

**能力边界（先建立正确预期）**：

- 是“自治诊断”，不是“自愈”——系统只输出诊断与建议，重启/回滚等写操作必须人工执行；
- 事件库与诊断队列全部在进程内存——零外部存储，也因此**只能单实例部署**。

## 架构一图

```text
Prometheus ──抓取── /actuator/prometheus（自指监控：AI 能查到自己报过几次告警）
     ▲ 查询 API（每条查询带 application 标签过滤）
     │
SnapshotCollector（每 60s：对目标列表并行采集 7 条核心指标/目标；self 的
                   BLOCKED>0 时 ThreadMXBean 死锁二级诊断；self 永远在列）
     ▼
OpsScheduler ── qwen3:8b 关思考链（默认 45s 超时，可配置）── 严格 JSON：逐目标 status/rootCause/suggestion
     ├─ LLM 超时/挂掉 → 硬阈值降级兜底（连续 3 轮 → 显式 LLM_UNAVAILABLE 告警）
     ├─ 深度诊断运行 → 主动让路（跳过 LLM 走兜底，不计时失效）
     ├─ normal → 该目标事件连续 3 轮 normal 自动 RESOLVED 归档
     └─ warning/critical → IncidentStore 状态机（NEW 全量报告 / ACTIVE 心跳 / RESOLVED）
             └─ 命中升级规则 → DiagnosisService 有界队列 → deepseek-r1 深度诊断（15min 硬超时）

旁路：GET /api/agent/ops（Function Calling 交互）· GET /api/ai/rag(/stream)（Milvus RAG）· POST /api/diagnosis（人工触发）
```

**多目标**：巡检默认只监控自己；要纳管其它应用，两步接入（Prometheus 抓到哪就能看到哪，语言无关——非 JVM 目标经指标映射接入）：

```yaml
# ① docker/prometheus.yml：为目标加抓取任务
scrape_configs:
  - job_name: 'order-service'
    static_configs:
      - targets: ['host.docker.internal:8081']

# ② 应用 application.yml：把目标加入巡检列表
aiops:
  targets:
    list:
      - name: 订单服务
        application: order-service   # 目标应用需配置 management.metrics.tags.application=<同值>
      - name: 支付网关                # 非 JVM 目标：metrics 覆盖映射接入（配什么 PromQL 得什么值）
        application: payment-gateway
        metrics:
          qpsLast1m: 'sum(rate(http_requests_duration_seconds_count{app="pay"}[1m]))'
```

纳管前提：目标应用需暴露 Prometheus 指标端点（Spring Boot 加 actuator + micrometer 即可；非 JVM 用 exporter/javaagent），并配置同名 `application` 标签。

## 快速开始

前置：JDK 21、Docker Desktop、Ollama。

```powershell
# 1. 起 Milvus 栈（milvus + etcd + minio + attu + prometheus 共 5 个容器）
docker compose -f docker/milvus-standalone-docker-compose.yml up -d
# 2. 拉三个模型
ollama pull qwen3:8b ; ollama pull deepseek-r1:8b ; ollama pull bge-m3
# 3. 启动应用（默认 dev profile；Milvus 必须先就位——客户端 bean 创建即建 gRPC 连接）
./gradlew bootRun              # Windows: .\gradlew.bat bootRun
# 4. 首次灌知识库
curl -X POST http://localhost:8080/api/ai/ingest
# 5. 一键自检（Ollama 三模型在位 / Milvus / Prometheus / JVM / 磁盘）
curl http://localhost:8080/api/health/check
```

## 测试

```powershell
./gradlew test              # 205 个单元测试，秒级、零基础设施依赖（CI 自动执行：.github/workflows/ci.yml）
./gradlew integrationTest   # Spring 装配冒烟（需 Milvus 在线）+ 虚拟线程演示基准
```

测试分层见 [build.gradle](build.gradle)：`test` 排除 `@Tag("integration")`，干净 CI 不会因缺 Milvus 而红。

## 文档地图

| 文档 | 内容 |
|---|---|
| [docs/aiops-copilot-whitepaper.md](docs/aiops-copilot-whitepaper.md) | 顶层设计：五层架构、双模型路由、评估体系、演进路线 |
| [docs/aiops-reliability-design.md](docs/aiops-reliability-design.md) | 事件状态机、降级兜底、异步深度诊断的详细设计 |
| [docs/aiops-fault-injection-eval.md](docs/aiops-fault-injection-eval.md) | 故障注入评估手册：5 类注入端点 + 用例表 + 度量口径 |
| [docs/aiops-ops-agent-prometheus-tool.md](docs/aiops-ops-agent-prometheus-tool.md) | PrometheusTool 双路径（被动 @Tool / 主动预拉）设计说明 |

## 常用端点

| 端点 | 说明 |
|---|---|
| `GET /api/agent/ops?message=` | 交互运维 Agent（模型自主调工具取真实指标后作答） |
| `GET /api/ai/rag?message=` | 知识库问答（带来源引用）；`/rag/stream` 为 SSE 流式 |
| `GET /api/ai/chat?message=` | 自由对话（SSE 流式，deepseek-r1 思考段以 `<think>...</think>` 包裹推送、与正式回答区分渲染；EventSource 可用 `?token=` 传鉴权） |
| `GET /api/test` | 运行时自检：确认 Tomcat 请求线程已切换为虚拟线程（`isVirtual` 字段） |
| `POST /api/diagnosis` | 手动提交深度诊断；`GET /api/diagnosis/{taskId}` 轮询结果 |
| `GET /api/health/check` | 依赖一键自检（部署后先跑这个） |
| `GET /api/debug/*` | 故障注入夹具（dev profile + `AIOPS_DEBUG_ENABLED=true` 双重门控，默认关；生产 404），用例见评估手册 |

## 生产部署要点

- 环境变量：`SPRING_PROFILES_ACTIVE=prod`、`AIOPS_API_TOKEN`（必填）、`MILVUS_PASSWORD`（不得为出厂默认
  `milvus`）——缺失或为默认值时 **拒绝启动**（`ProdSecurityGuard` fail-fast 自检）；评估演练才需要
  `AIOPS_DEBUG_ENABLED=true`（故障注入端点默认关）。全部可调参数见
  [application.yml](src/main/resources/application.yml) 注释；
- 单实例红线：IncidentStore / DiagnosisService 都在进程内存，横向扩容前必须先外置状态（演进路径见白皮书第五章）；
- 镜像构建：仓库根目录 `Dockerfile`（多阶段构建，非 root 运行，`docker/volumes/` 与日志不入镜像）。
