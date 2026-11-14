# 智能巡检 Agent：把 Prometheus 接成 AI 的"时序眼睛"

> 阅读路线：**Context** 讲为什么要做（两个缺口：时序数据 + 主动巡检），**决策已对齐** 列三条既定选择，**关键架构选择** 拆三处关键设计，**验证** 给 5 步端到端自检。

## Context

当前项目已有被动式运维 Agent（`/api/agent/ops`，用户问才检查）和瞬时本地状态工具（`SystemHealthTools` 拿 CPU/内存瞬时值）。

新引入两个关键能力：

1. **时序数据访问**：Agent 看不到 Prometheus 里的历史趋势，只能拿"当下这一刻"的瞬时值
2. **主动巡检**：没有 `@Scheduled` 调度，故障发生后无人主动发现，全靠用户提问

本次演进贯彻"AIOps = 监控给 AI 看，不给人看"的核心理念：把 Prometheus HTTP API 封装为 Function Tool，再用 `@Scheduled` 每分钟让 qwen3:8b（关闭思考链）主动巡检关键指标、智能判断异常、必要时输出结构化告警报告（控制台 ERROR 级格式化输出）。这套替代了传统 Grafana 看图 + Alertmanager 阈值告警的人工链路。

## 决策已对齐

- 告警推送渠道：**控制台 ERROR 级日志输出格式化报告**（不依赖外部 Webhook 凭证，后续可平滑替换为钉钉/飞书）
- 巡检间隔：**1 分钟**
- 巡检 ChatClient：**qwen3:8b**（`disableThinking()` 关闭思考链，直出 JSON 判断结论；不注册工具，指标由调度器预拉）

## 关键架构选择

> 三处关键设计，各自对应一个"为什么这么做"——读到这章时已了解上下文与既定选择，下面逐条展开。

### PrometheusTool 的双重身份

同一个工具类的查询能力被两条路径复用，避免重复实现：

- **路径 A（被动）**：`@Tool` 注解的 `queryMetric(String promql)` 方法暴露给 `opsAgentClient`，用户提问时模型自主决定查什么 PromQL
- **路径 B（主动）**：巡检调度器直接调用 `PrometheusTool` 的普通 public 方法 `querySnapshot(target)`，**对目标列表逐个预拉固定指标塞进 Prompt**（多目标：self 永远在列，每条查询带 application 标签过滤；非 JVM 目标经"语义→PromQL"覆盖映射接入），不让模型自己查（实测 2026-09-17：模型自主查在单轮内并行发起 5-6 次 queryMetric、无重试，但开放式提问只查 5 项、会漏维度；预拉保证每分钟 7 条指标覆盖确定）

这个分工对应"Agent 主动出击"——巡检不是让模型当查询工，而是让模型当"判断工"。

两条路径共用同一个 RestClient 实例，显式配置**连接 2s / 读取 5s 超时**（`JdkClientHttpRequestFactory`，2026-09-30 评审补充）：RestClient 默认请求工厂不设超时，Prometheus 挂起（接受连接但不响应）时查询会永久阻塞，`@Scheduled` 单线程 + fixedDelay 语义下整个巡检链路静默停摆。多目标下每目标一个虚拟线程并行采集，不随目标数放大；**同一目标的 8 条 PromQL 也并行发起**（2026-11-13 优化：此前串行时最坏耗时 = 8 × 5s ≈ 40s，采集阶段就吃掉整个 60s 巡检周期；改为并行后墙钟 = 最慢一条 ≈5-7s），因此仍在 60s 巡检预算内；模型自主查询单次最多 5s 超时返回 -1 哨兵，Agent 拿到 -1 会如实告知"指标不可用"而非无限等待。

### 跨实例聚合与查询缓存（2026-11-13）

- **多副本聚合**：`process_cpu_usage` 与 `jvm_threads_states_threads{state="blocked"}` 每个实例各一条序列，多副本目标不带聚合时 `queryScalar` 只读第一条——副本 B 死锁、副本 A 正常时 `blockedThreads` 读到 0，**死锁完全不可见**。默认档因此外层 `max(...)`（监控看最坏实例：任一副本异常即告警）；堆分代明细用 `max by (id)`（多副本同 id 序列在 Java 侧 Map 里会互相覆盖，聚合后只剩 id 标签）；actuator 基线用 `sum(rate(...))`（基线不聚合时 QPS 减法用的是"随机一个副本"的基线，多副本下口径错位）。单副本部署下 `max`/`sum` 语义与裸查询完全一致，行为无变化。
- **被动模式查询缓存**：`queryMetric` 带 15s TTL 缓存（`aiops.prometheus.query-cache-ttl`，对齐 Prometheus 抓取间隔）——模型单轮自主并发 5-6 次 queryMetric 且多轮提问常重复同一查询，TTL 内相同 PromQL 不重复打 Prometheus；**失败结果同样入缓存**，故障期天然限流，避免模型重试风暴打挂半死的 Prometheus。主动模式 `querySnapshot` 不走缓存（巡检 60s 一轮 > TTL，缓存无收益）。

### 新建 opsAgentClient Bean（而非复用 deepseekChatClient）

复用 `deepseekChatClient` 会把 Agent 的系统提示词与工具 schema 注入到所有对话调用里，污染 `AiChatController`、`ChatController` 等场景的 token 消耗。新建专用 Bean 隔离职责。

### 自指监控的口径处理（2026-09-28 修复）

系统用同一个 Prometheus 既暴露自身指标又供巡检查询（自指结构）。Prometheus 每 15s 抓取
`/actuator/prometheus` 本身会在 `http_server_requests_seconds` 留下样本，给巡检 QPS 引入
≈0.067 的恒定基线污染——后果不只是数字不准：**空闲系统永远看不到 qps=0**，"qps==0 且
cpu>=0 → 应用假死"的兜底判定被静默屏蔽。

巡检快照的 QPS 因此排除自指流量（减法在 Java 侧完成：PromQL 里 sum() 的无标签向量与 rate() 的带标签向量逐标签匹配不相等，直接相减得空集——2026-11-08 双实例实测踩坑）。**注意不能直接写 `{uri!="/actuator/prometheus"}`**：
`URI.create` 会把 `%21`（`!`）规范化为字面量 `!`，但保留 `%3D`（`=`），Prometheus 收到后报
`unexpected character after '!' inside braces`。实际方案是 Java 侧减法：
`sum(rate(http_server_requests_seconds_count[1m])) - sum(rate(http_server_requests_seconds_count{uri="/actuator/prometheus"}[1m]))`（基线侧同样 sum 聚合：rate() 每副本一条序列，多副本下不聚合的基线会取到"随机一个副本"，减法口径错位），
两条子查询都只用 `=`（编码路径已验证可用），`totalQps<0` 时保留 -1 哨兵语义。
maxRequestSeconds 不过滤：actuator 抓取耗时通常 <0.5s，远低于 5s 慢请求阈值，基线无害。
被动模式 `queryMetric(promql)` 由模型自由构造表达式，不做强制过滤（人机对话场景基线无害，
且系统提示词的示例本身即教学用途）。

## 验证

> 5 步端到端自检，覆盖"启动 → 首次巡检 → 被动查询 → 故障告警 → 恢复"全链路。

1. **编译通过**：`.\gradlew.bat compileJava`
2. **首次巡检日志**：启动应用 30 秒后应看到 `[OpsScheduler] 开始巡检...` + `[OpsScheduler] 巡检完成: 正常` INFO 日志
3. **被动 Agent 查 Prometheus**：访问 `http://localhost:8080/api/agent/ops?message=过去1分钟QPS是多少` → 模型应调 `queryMetric` 拿到真实数据后回答（15s TTL 内重复提问命中缓存，不再打 Prometheus）
4. **故障触发告警**：先以 `AIOPS_DEBUG_ENABLED=true` 启动（注入端点默认关），再并发访问 `http://localhost:8080/api/debug/deadlock/a` 与 `http://localhost:8080/api/debug/deadlock/b`（相反锁序的两个接口，并发即双锁交叉死锁）→ 等待最多 1 分钟巡检 → 应看到 ERROR 级告警报告，含"blockedThreads: 2"+ 根因分析 + 处置建议
5. **恢复正常验证（人工操作）**：人工重启应用清除死锁（当前系统不含自动修复）→ 下次巡检恢复 INFO 正常日志
