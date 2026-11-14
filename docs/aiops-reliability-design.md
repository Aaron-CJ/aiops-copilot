# 事件状态机、降级与深度诊断设计

> 配套白皮书 §4.1（双模型路由 + 可靠性增强）、§4.2/4.3（异步深度诊断与升级路由）与 §5.1（事件生命周期管理）的详细设计文档。
> 本文档的实现已通过 207 个测试（205 单测 + 2 集成；单测集 2026-11-14 `clean test` 全量全绿，集成集需本地 Milvus/Ollama 基础设施、以 `gradle integrationTest` 触发；明细见 §6.8）+ 端到端死锁/降级/深度诊断链路验证。
>
> 阅读路线：**§1** 讲两条硬约束（告警不刷屏、LLM 挂了巡检不能停）与 AI 漏报兜底，**§2** 事件状态机与指纹去重，**§3** Ollama 降级与阈值兜底，**§4** 单元与端到端验证，**§5** 审计日志，**§6** 异步深度诊断与升级路由全链路。

## 1. 设计背景

巡检调度器（[OpsScheduler](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)）每分钟跑一次，必须满足两条硬约束：

1. **告警不刷屏**：同一持续性故障（如死锁）在恢复前会被反复"发现"，必须去重——否则 60 条/小时的告警风暴会淹没真正重要的信号，且每轮重复 LLM 推理浪费 Token。
2. **Ollama 挂了巡检不能停**：LLM 是核心决策组件，但不是唯一手段。当 Ollama 进程不可用、网络异常或推理卡死时，巡检调度器必须降级到硬阈值判断，否则等同于"AI 一挂，监控全瞎"。

补充约束：**AI 漏报兜底**——模型可能误判 normal（例如 qwen3:8b 关闭思考链后对复合故障识别有盲区），代码层必须在关键指标严重超阈值时强改 warning/critical，不依赖 AI 的正确性。兜底闸同样覆盖"判不了"（模型输出契约外 status）的目标：硬信号是确定性观测，模型"没说话"不能让死锁漏报一整轮。

## 2. 事件状态机与去重

> 同一持续性故障会被反复"发现"——状态机负责把它压成"NEW 1 次 + 心跳 N 次 + RESOLVED 1 次"。

### 2.1 状态机定义

```
NEW（首次发现）→ ACTIVE（持续中，第二周期起改走 INFO 心跳日志）
   → RESOLVED（该目标连续 3 轮 normal 后自动归档；LLM_UNAVAILABLE 等全局事件按连续 3 轮未降级归档）
```

| 状态 | 触发条件 | 输出 |
|------|----------|------|
| NEW | 当前轮 fingerprint 在 store 中不存在或上次已 RESOLVED | [OpsAlertReporter.report](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java) — ERROR + ASCII 框线全量报告 |
| ACTIVE | 同 fingerprint 仍在（未 RESOLVED） | [OpsAlertReporter.logHeartbeat](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java) — INFO 一行心跳 |
| RESOLVED | 同一目标连续 3 轮巡检 normal（全局事件为连续 3 轮未降级）后自动标记 | [OpsAlertReporter.logResolved](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java) — INFO 一行恢复 |

### 2.2 Fingerprint 构造（基于指标快照，不基于 LLM 文本）

**关键设计决策**：fingerprint 基于**指标快照特征**，**不基于 LLM rootCause 文本**。

> 原因：LLM 对同一故障的描述每次会略有不同（2026-09-15 实测：死锁 NEW 轮的 rootCause 是"存在死锁，死锁线程为 deadlock-worker-1 和 deadlock-worker-2，分别在等待对方持有的锁..."，后续 ACTIVE 轮变成"存在死锁，deadlockedThreads 列出的线程 deadlock-worker-1 和 deadlock-worker-2 因锁顺序问题相互等待..."）。用文本前缀做指纹会导致同故障被反复识别为 NEW、去重失效。
>
> 指标特征是稳定的：只要同一类指标异常（如 BLOCKED>0），无论 LLM 怎么描述都会合并到同一指纹。

实现：[IncidentStore.fingerprintOf](../src/main/java/com/aiops/aiopscopilot/service/IncidentStore.java)

```
fingerprint = 异常级别 + "|APP=" + 目标名 + ";" + 指标特征串
```

指标特征串的构造规则（按优先级）：

| 条件 | 特征片段 | 说明 |
|------|----------|------|
| snapshot 含 `deadlockDiagnosis` 字段 | `DEADLOCK;` | OpsScheduler 在 BLOCKED>0 时主动调用 ThreadMXBean 触发 |
| `blockedThreads > 0` | `BLOCKED=N;` | N 是具体数量（同数量合并） |
| `cpuUsage > 0.8` | `HIGH_CPU=X;` | X = `(int)(cpu * 10)`，分桶避免轻微波动（0.91 vs 0.92）产生不同指纹 |
| `heapUsage > 0.85` | `HIGH_HEAP=X;` | 同 CPU 分桶逻辑 |
| `gcCountLast5m > 10` | `FREQUENT_GC;` | 疑似内存泄漏 |
| 以上均无且 `qpsLast1m == 0 且 cpu >= 0` | `ZERO_QPS;` | 应用假死信号（兜底特征）；cpu>=0 排除指标拉取失败的 -1 哨兵 |

> **ZERO_QPS 只作兜底特征**：已有死锁/阻塞/CPU/堆/GC 等更具体信号时不再附加——否则同一故障会因"最近一分钟恰好有没有流量"在两种指纹间抖动，被状态机拆成两条并发 ACTIVE 事件，心跳、防抖窗口与 5 分钟升级计时全部按指纹分裂。

**示例**：死锁场景 → `CRITICAL|APP=aiops-copilot;DEADLOCK;BLOCKED=2;`（伴随 QPS=0 也不改变指纹）

**APP 前缀**：目标名进指纹第一位——状态机按指纹字符串去重，不同应用的故障天然分成独立事件，心跳/防抖窗口/5 分钟升级计时互不干扰，状态机本身零改动。巡检快照结构相应升级为 `{targets:{<目标名>:{...7 语义...}}}`（self 永远在列），巡检对每个目标逐一判定、逐一进状态机。

### 2.3 存储选型：内存级

- 实现：`ConcurrentHashMap<String, Incident>`，重启丢失
- 取舍：与"自治诊断"能力边界一致——死锁等故障应用重启后也会消失，持久化的旧事件反而是噪音
- 零依赖、零外部状态，部署简单
- **RESOLVED 保留窗口 1 小时**：已归档记录不再无限期驻留，超过 1 小时的 RESOLVED 由下一轮 `bumpNormalAndResolve` 顺带清除（过期判定显式 `remove(key, value)`，不依赖 `compute` 返回 null）。理由：复发判定依赖"同指纹的旧 RESOLVED 记录还在"，但无限期保留既让内存单调增长、又会把数月前的同指纹误判成 relapse；1 小时覆盖同一次故障的合理复发周期，过期后同指纹按全新 NEW 处理

### 2.4 Incident 不可变性

每次状态更新都新建 `Incident` 实例（所有字段 final），保证 `ConcurrentHashMap` 读写的内存可见性。`consecutiveNormalRounds` 计数通过新建实例递增，不暴露 setter，避免外部意外修改。

状态流转统一走 `compute` / `computeIfPresent`（单键原子），不用 `forEach + put` 的 get-then-put——后者与 `recordOrUpdate` 并发时会丢失更新（`put` 静默覆盖刚写入的 ACTIVE 版本，复活已 RESOLVED 的事件）。当前调用方是单线程调度器、竞态本不可达，改用原子方法是为了让正确性不依赖外部并发约定。

`durationOf` 取 `firstSeen → lastSeen`（而非 `firstSeen → now`）：`lastSeen` 每轮刷新，归档时写为确认恢复的时刻，心跳里的"已持续 N min"反映从首次发现到确认恢复的观测时长，RESOLVED 事件被查询时也不会"持续增长"。

## 3. Ollama 降级与阈值兜底

> LLM 是核心决策组件，但不是唯一手段。本章八条防线：超时保护、Ollama 连接级超时、框架重试收紧、降级路径、推理让路、阈值兜底、AI 漏报兜底、失效显式告警。

### 3.1 LLM 超时保护

[OpsScheduler.callLlmWithTimeout](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)：

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
        inspectorChatClient.prompt().user(prompt).call().content(), llmCalls);
return future.get(llmTimeoutSeconds, TimeUnit.SECONDS);  // 默认 45 秒，可经配置覆盖
```

- 超时预算：`aiops.inspection.llm-timeout-seconds`（默认 45 = fixedDelay 60 秒的 75%，留余量给指标拉取与解析；多目标部署 Prompt 与输出都更长，实测 2 目标 + 机器负载下 45s 不够，建议 90-150——2026-11-08 双实例实测 49s 的 LLM 轮被 150s 预算吸收）
- 跑在专用虚拟线程执行器 `llmCalls` 上而非公共 ForkJoinPool：超时后 `future.cancel(true)` 并不能中断底层 HTTP，被占死的应是廉价虚拟线程，不能落在 commonPool 上饿死其他并行计算
- 超时后本周期走降级路径，Ollama 侧推理自行结束

**指标拉取超时（2026-09-30 评审补充）**：超时防线不只在 LLM 侧——[PrometheusTool](../src/main/java/com/aiops/aiopscopilot/tool/PrometheusTool.java) 的 RestClient 显式配置连接 2s / 读取 5s 超时（`JdkClientHttpRequestFactory`）。RestClient 默认请求工厂不设超时，Prometheus 挂起（接受连接但不响应）时 `queryScalar` 会永久阻塞；`@Scheduled` 单线程调度器 + fixedDelay 语义下，后续巡检全部静默停摆，监控系统自身悄然死亡且无任何告警。多目标下每目标一个虚拟线程并行采集，不随目标数放大。

**查询并行（2026-11-13 优化）**：一个目标的 8 条 PromQL（6 条默认档语义 + 2 条 QPS 子查询）彼此无依赖，此前**串行**执行意味着采集最坏耗时 = 8 × (连接 2s + 读取 5s) ≈ 56s（黑洞地址实测 40s）——采集阶段就吃掉了整个 60s 巡检周期，LLM 的 45s 预算被前置开销挤到周期之外（当时实测巡检周期被拉到 69s）。改为**并行**发起（每条查询一个虚拟线程，墙钟 = 最慢的一条 ≈ 5-7s）后，采集阶段回到预算内，60s 周期重新成立。并行度 = 每目标 ≤8 条；多目标时总量 = 8 × 目标数，Prometheus 侧自行排队（其默认 `query.max-concurrency=20`）。RestClient / JDK HttpClient / Jackson ObjectMapper 均线程安全。回归见 [PrometheusToolSnapshotTest](../src/test/java/com/aiops/aiopscopilot/tool/PrometheusToolSnapshotTest.java)：用 JDK 内置 HttpServer 起假 Prometheus，给每条查询注入固定延迟，以墙钟断言把"并行"钉死（改回串行会立刻红）。

**框架级重试收紧（2026-11-13 调整，2026-11-14 语义修正）**：Spring AI 默认 `max-attempts=10` + 退避倍率 5 + 退避上限 180s ⇒ 单次失败调用最多重试约 **19 分钟**。本项目自研了超时（巡检 45s / 深度诊断 15min）、阈值降级、推理让路三层防线，框架重试会绕开这三层：巡检超时只中断"等待"，底层虚拟线程继续重试打 Ollama，每分钟新增一个 ⇒ Ollama 挂 1 小时后 60 个僵尸重试线程同时在打，恢复瞬间被淹没（NUM_PARALLEL=1 时更致命）。`application.yml` 收紧为 `spring.ai.retry.max-attempts: 1` + 秒级退避（初始 1s / 倍率 2 / 上限 10s）。**语义注意（2026-11-14 实证）**：该属性经 `SpringAiRetryAutoConfiguration` 映射为 spring-core 7 `RetryPolicy.maxRetries`，即"初次之外的额外尝试次数"——此前配 2 实际是 3 次尝试（jshell 实测 `maxRetries(N)` = 初次 + N 次）；且读超时异常（`ResourceAccessException`）在框架重试白名单内、每次重试重新计时，配 2 时僵死调用最长 16m×3=48 分钟才终结。取 1 后终结上界收敛为 16m×2=32 分钟（让路宽限 `zombie-grace-minutes` 按该上界配置）。

**Ollama 连接级超时（2026-11-13 补充）**：重试收紧防的是"失败后的重试风暴"，但防不了"僵死后的永久挂起"——自动装配的 OllamaApi 用默认请求工厂，**读超时无限**：Ollama 进程僵死（接受连接但不响应）时 `future.get(45s/15min)` 只中断"等待"，`future.cancel` 断不了底层 HTTP，巡检每分钟超时一次 ⇒ 每分钟泄漏 1 个挂死在线程读取上的虚拟线程，只进不出。`AiConfig#ollamaApi`（自定义 Bean 顶替自动装配）给 REST `.call()` 路径装上连接 10s + 读超时 16m（= 深度诊断 15min 硬超时 + 1min 余量：合法推理绝不截断）。注意卡死调用的终结上界是**读超时 × 框架尝试次数**（读超时异常在重试白名单内、每次重试重新计时；`max-attempts=1` 即初次+1 次重试 ⇒ 最长 ≈32 分钟），而非单次读超时 16 分钟（2026-11-14 实证修正）。流式路径（WebClient/SSE）不设此超时：流式增量返回数据，强行设响应超时会误杀长思考的合法停顿。异步超时实测（2026-11-14）：本栈未配置 `spring.mvc.async.request-timeout` 时不会被"30 秒默认值"切断（连续数据流存活 4 分钟、全静默 5 分钟，零 AsyncRequestTimeout），该默认行为随栈版本可能变化，需钉死时可显式配置。

### 3.2 降级路径

[OpsScheduler.handleDegraded](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)：

```
catch (TimeoutException | Exception):
  if snapshot != null:
    handleDegraded(snapshot, start, trackUnavailable)  // 走阈值兜底
  else:
    recordInspection("error", ...)   // 指标拉取也失败，本轮完全失败
```

降级路径调用 [fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 为每个目标产出与 LLM 等价的判定（`TargetJudgment` 列表，**不是 JSON**），再直接走 [routeJudgments](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)（`degraded=true`）：
- 仍接入 IncidentStore 状态机去重（同一指纹的 NEW→ACTIVE 流转不变）
- `metricsService.recordInspection("degraded_" + status, ...)` 标记降级路径，便于运维区分
- 第三个参数 `trackUnavailable` 控制"让路轮"是否计入 LLM 失效计数（见 §3.5）

> **历史说明**：本节早期版本描述的"降级路径调用 fallbackByThreshold 构造与 LLM 等价的 JSON，再走 `handleInspectionResult(..., degraded=true)`"是 2026-10 前的实现——当时降级路径复用模型的 JSON 解析通道。现已改为直接产出判定对象，**JSON 解析只发生在 AI 路径**；降级路径因此不会因模型输出格式问题而误入 parse_error 通道。

**推理让路**：深度诊断有 PENDING/RUNNING 任务时，巡检跳过 LLM 直接走阈值兜底（审计 `DEGRADED|reason=diagnosis_running`，实测单轮让路仅耗时 66ms）。动机：单 Ollama（CPU 推理且 NUM_PARALLEL=1）下 qwen3 巡检与 r1 深度推理并发会互相拖慢——实测 r1 被拖到 15 分钟硬超时、巡检也每轮 45 秒超时（对应 §6.3 的 530 秒争抢实测）。让路是主动调度不是故障：`handleDegraded` 增加 `trackUnavailable` 参数，让路轮**不计入** `trackLlmUnavailable` 的连续失效计数，避免把"r1 在忙"误报成"Ollama 挂了"。控制台的降级行文案因此写"本轮未走 AI"而非"LLM 不可用"（具体原因看审计的 reason）。

### 3.3 阈值兜底规则

[OpsScheduler.fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 的硬阈值判断（优先级：死锁 > OOM > 假死 > GC > CPU）：

| 条件 | 级别 | rootCause 标注 |
|------|------|---------------|
| `blockedThreads > 0` | critical | `代码级兜底：存在 BLOCKED 线程（N），疑似死锁` |
| `heapUsage > 0.95` | critical | `代码级兜底：堆内存使用率 X > 0.95` |
| `qpsLast1m == 0 且 cpu >= 0 且此前观测到过流量`（连续第 1 轮） | warning | `QPS=0（第 1 轮，待确认——可能为空闲低流量，连续 2 轮后升级 critical）` |
| `qpsLast1m == 0 且 cpu >= 0 且此前观测到过流量`（连续 ≥ 2 轮） | critical | `QPS=0 已持续 N 轮，端口在但无请求，应用可能假死` |
| `gcCountLast5m > 10` | warning（若未达 critical） | `代码级兜底：5min GC 次数 N > 10，疑似内存泄漏` |
| `cpuUsage > 0.90` | warning（若未达 critical） | `代码级兜底：CPU 使用率 X > 0.90` |

**QPS=0 连续确认（ZERO_QPS_CONFIRM_ROUNDS=2）**：actuator 抓取已从 QPS 基线中扣除，合法空闲/低流量系统每轮 QPS 都是严格 0——单轮 critical 会让空闲环境每分钟误报一次"应用假死"（告警狼来了效应）。改为连续 2 轮确认：第 1 轮 warning"待确认"，QPS 恢复 >0 即归零计数，连续 2 轮才升级 critical。真实假死只多等 1 分钟确认，换来空闲系统零误报（2026-09-30 实测：空闲系统兜底第 1 轮正确输出 warning 待确认）。AI 路径的巡检 Prompt 同步增加了"CPU 极低且无其他异常时 QPS=0 可能为合法空闲"的边界说明，双路径口径一致。兜底输出改用 ObjectMapper 序列化（原手写 JSON 拼接的转义器漏控制字符，已删除）。

**QPS=0 基线门控（observedTraffic）**：连续确认之上再加一道——QPS=0 只有在"此前观测到过真实流量、随后跌到零"时才构成假死信号。全新启动或长期空闲的实例 QPS 恒为 0，没有基线门控时仍会被连续 2 轮确认误判成 critical 假死（2026-11-07 实测空闲演示环境即触发该误报）。实现：`OpsScheduler.observedTraffic` 在任一路径观测到 QPS>0 时置 true 且不回退（重启才清零）；连续计数的归零由两条路径同一口径维护——`fallbackByThreshold` 内零流量轮升计数、其余归零，`routeJudgments` 入口观测到 QPS≠0（>0 有流量 / <0 查询失败，2026-11-13 补齐后者）都打断序列："没观测到"不能充当连续假死的一环，否则一次指标拉取失败后残留计数跨轮存活，下一个降级轮直接跳级 critical 假死。

**AI 路径空闲门控（2026-11-14）**：基线门控此前只覆盖降级路径——AI 路径的模型按巡检 Prompt 第 3 条仍会对空闲系统的 QPS=0 判"低流量或假死待确认"warning，空闲环境每次 AI 轮都新增/维持一条 ZERO_QPS 事件（实测 9 分钟才自愈）。补齐：AI 判 warning 但该目标从未观测到流量、且指纹**只剩** `ZERO_QPS`（无死锁/高堆/高 CPU/GC 任一硬信号，复用 `fingerprintOf` 判定）时降为 normal 并记审计（`aiStatus=warning` 如实保留），被门控压下的 warning 不触发 low-confidence 深度复核（基线已确定性回答"空闲"，复核同一份快照不产生新信息，只会按防抖周期反复唤醒 r1）；一旦观测过流量，同样的 QPS=0 保持模型判定不动——防"空闲误报"修复引入"假死漏报"。

### 3.4 AI 漏报兜底（applyThresholdBackstop）

[OpsScheduler.applyThresholdBackstop](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 在 LLM 路径（非降级）中额外加一道兜底：

- **仅当 AI 判 normal 时介入**（warning/critical 不改判，避免覆盖 AI 的更细致判断）
- normal 但 `blockedThreads > 0` → 强改 critical
- normal 但 `heapUsage > 0.95` → 强改 critical
- normal 但 `cpuUsage > 0.90` → 强改 warning
- rootCause 追加 `[代码级兜底已介入：warning]` 标注，便于事后定位 AI 漏报原因

### 3.5 LLM 长期不可用的显式告警（LLM_UNAVAILABLE）

Ollama 长期挂掉时每轮只留下一条 WARN 日志与 `degraded_*` 指标——"AI 瞎了"这样重要的故障不能只活在日志里。设计（`OpsScheduler.trackLlmUnavailable`）：

- **触发**：连续 3 轮降级（`handleDegraded` 被调用）即登记独立指纹 `CRITICAL|LLM_UNAVAILABLE;` 的 critical 事件，复用 5.1 状态机——后续降级轮走 INFO 心跳；偶发 1-2 轮降级（瞬时抖动）不告警
- **恢复**：LLM 正常返回即重置计数；事件由连续 3 轮未降级自动 RESOLVED（不受单个目标长期异常影响——它挂在巡检链路本身，不挂在目标上）
- **配套规则一（盲期不归档）**：降级轮的 normal 只是硬阈值判断、语义置信度低，不调用 `bumpNormalAndResolve`——否则 Ollama 长挂时事件会被"降级 normal"轮错误归档，随后又被下一轮降级登记为 NEW，反复震荡
- **配套规则二（降级不升级）**：降级路径不触发任何 4.3 深度诊断升级——r1 与快通道共用同一个 Ollama，挂了就都调不动，排队只会产出一串注定 FAILED 的任务

## 4. 验证

> 两层验证：单测锁定状态机与阈值规则，端到端日志验证真实 Ollama 故障下的去重与降级行为。

### 4.1 单元测试

[IncidentStoreTest](../src/test/java/com/aiops/aiopscopilot/service/IncidentStoreTest.java) 覆盖 19 个用例：

| 维度 | 用例数 | 关键验证 |
|------|--------|---------|
| IncidentStore 状态机 | 9 | NEW→ACTIVE→RESOLVED 流转、复发重新置 NEW、normal 重置计数、按目标归档隔离（另一目标异常时本目标正常事件仍可归档）、durationOf 用 lastSeen、RESOLVED 超 TTL 清除、并发 record/bump 不丢更新 |
| AI 漏报兜底 | 5 | normal+CPU>90→warning、normal+BLOCKED>0→critical、normal+堆>95→critical、warning 不变、全正常不变 |
| Ollama 降级路径 | 5 | BLOCKED>0→critical、CPU>90→warning、QPS=0 连续 2 轮才 critical（首轮 warning 待确认）、GC>10→warning、全正常→normal |

另见 [故障注入评估手册](aiops-fault-injection-eval.md)：F1-F5 五类故障场景（死锁/内存泄漏/假死/慢接口/瞬时尖峰）的确定性回归由 [FaultInjectionEvaluationTest](../src/test/java/com/aiops/aiopscopilot/service/FaultInjectionEvaluationTest.java) 覆盖，验证指纹构造、阈值兜底与尖峰抑制的状态机行为。

### 4.2 端到端验证

**死锁去重**（[OpsScheduler](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 实际运行日志）：

| 轮次 | 行为 | 日志级别 |
|------|------|----------|
| Round 1 baseline | CPU=0、BLOCKED=0 → 正常 | INFO |
| Round 2（NEW） | 发现死锁，全量 ASCII 报告 + 根因 | ERROR |
| Round 3（ACTIVE） | `故障持续中 \| 指纹=CRITICAL\|APP=aiops-copilot;DEADLOCK;BLOCKED=2; \| 已持续 1min` | INFO 心跳 |
| Round 4（ACTIVE） | `已持续 3min` | INFO 心跳 |

**Ollama 降级**（kill ollama 进程后）：

```
17:48:06 巡检开始
17:48:08 WARN Spring AI Retry count 1
17:48:18 WARN Spring AI Retry count 2
17:48:51 WARN [OpsScheduler] LLM 推理超过 45s 未返回，启用阈值兜底
17:48:51 INFO [AIOps 心跳] 故障持续中 | 指纹=CRITICAL|APP=aiops-copilot;DEADLOCK;BLOCKED=2;  ← fallbackByThreshold 识别死锁
```

降级路径与 LLM 路径生成同一指纹（`CRITICAL|APP=aiops-copilot;DEADLOCK;BLOCKED=2;`），正确合并到同一事件，输出 INFO 心跳而非 ERROR 全量报告。

## 5. 审计日志

> 审计日志独立于业务日志，记录"AI 为什么这么判"的完整证据链，Agent 自身无写接口、不可篡改。

### 5.1 设计原则

[AuditLogger](../src/main/java/com/aiops/aiopscopilot/common/audit/AuditLogger.java) 独立于业务日志，落盘到 `logs/aiops-audit.log`（[logback-spring.xml](../src/main/resources/logback-spring.xml) 配置按天轮转、保留 30 天、200MB/文件、5GB 总上限、gzip 压缩）。

三条原则：
1. **不可被 Agent 篡改**——AuditLogger 不暴露任何写接口给 `@Tool` 层，Agent 无法读写审计记录
2. **独立 appender**——Logger 名 `com.aiops.aiopscopilot.audit`，`additivity=false` 不冒泡控制台，避免运维噪音
3. **结构化文本**——管道分隔格式 `时间|级别|类别|字段1=值1|字段2=值2`，便于 grep 与后续转 JSON

### 5.2 记录的决策点

| 方法 | 触发时机 | 示例输出 |
|------|----------|----------|
| `inspectionStarted()` | 每轮巡检开始 | `巡检\|START` |
| `inspectionFinished()` | 巡检结束 | `巡检\|END\|status=critical\|elapsedMs=3500\|fingerprint=CRITICAL\|APP=aiops-copilot;DEADLOCK;BLOCKED=2;` |
| `degradedPathTriggered()` | LLM 超时/异常降级 | `巡检\|DEGRADED\|reason=llm_timeout\|elapsedMs=45000` |
| `backstopTriggered()` | AI 漏报兜底介入 | `巡检\|BACKSTOP\|target=aiops-copilot\|aiStatus=normal\|forcedStatus=warning\|metric=cpuUsage\|value=0.92` |
| `incidentNew()` | 事件首次发现 | `事件\|NEW\|fingerprint=...\|status=critical\|summary=...` |
| `incidentActive()` | 事件持续中心跳 | `事件\|ACTIVE\|fingerprint=...\|durationMin=3` |
| `incidentResolved()` | 事件恢复归档 | `事件\|RESOLVED\|fingerprint=...\|totalDurationMin=5` |
| `fallbackResult()` | 降级路径阈值判断结果 | `巡检\|FALLBACK_RESULT\|status=degraded\|rootCause=...\|snapshot=...` |

审计链闭合保证：每条 `巡检|START` 都有对应的 `巡检|END`——解析失败记 `status=parse_error`（降级路径为 `degraded_parse_error`）、指标拉取/兜底全失败记 `status=error`，不会出现只有 START 的悬空轮次。

## 6. 异步深度诊断与升级路由（白皮书 4.2/4.3）

> 同步等不起 r1 的分钟级推理——本章用"任务队列 + 轮询"把等待从请求线程移走：6.1 讲动机，6.2 数据流，6.3 参数，6.4 升级规则，6.5 深度 Prompt，6.6 审计事件，6.7 渠道边界，6.8 测试覆盖。

### 6.1 为什么必须异步

deepseek-r1:8b 启用思考链后单次推理实测 1.7-6.6 分钟（见白皮书双模型表），是巡检 60 秒预算的 2-7 倍，也远超任何合理的同步 HTTP 超时预算。同步等待会同时拖垮巡检周期与交互请求；但 r1 的长链推理在复合故障与 SOP 决策上的能力是关闭思考链的 qwen3:8b 不具备的。解法是**任务队列 + 轮询**，把"等待"从请求线程移走。

### 6.2 组件与数据流

```
OpsScheduler（巡检）─4.3 规则命中─┐
                                 ├─→ DiagnosisService.enqueue（有界队列 20）
POST /api/diagnosis（人工）──────┘            │ PENDING
                                             ▼
                          单个虚拟线程 worker（deep-diagnosis-worker）
                                             │ RUNNING：deepseekChatClient + enableThinking
                                             │ 15 分钟硬超时
                                             ▼
                          SUCCEEDED(report) / FAILED(error)
                                             │
                          OpsAlertReporter.logDeepReport（控制台）
                          AuditLogger（深度诊断|END，落盘）
                          GET /api/diagnosis/{taskId}（轮询拉取）
```

涉及类：

| 类 | 职责 |
|----|------|
| [DiagnosisTask](../src/main/java/com/aiops/aiopscopilot/service/diagnosis/DiagnosisTask.java) | 不可变任务记录，状态 `PENDING→RUNNING→SUCCEEDED/FAILED` 经 with* 派生 |
| [DiagnosisService](../src/main/java/com/aiops/aiopscopilot/service/diagnosis/DiagnosisService.java) | 有界队列、单 worker、防抖、终态淘汰、r1 调用与深度 Prompt 组装 |
| [DiagnosisController](../src/main/java/com/aiops/aiopscopilot/controller/DiagnosisController.java) | `POST /api/diagnosis` 提交、`GET /api/diagnosis/{taskId}` 轮询 |
| [SnapshotCollector](../src/main/java/com/aiops/aiopscopilot/service/SnapshotCollector.java) | 对目标列表（self 永远在列）并行采集、每目标 7 条指标 + self 的 BLOCKED>0 死锁二级诊断；巡检与深度诊断共用，保证快照口径一致 |

### 6.3 队列、超时与防抖参数

| 参数 | 值 | 理由 |
|------|----|------|
| 队列容量 | 20 | r1 串行且单次数分钟，堆积无意义；满了拒绝 + 审计 `REJECTED\|reason=queue_full`，不阻塞巡检线程 |
| worker 数 | 1（虚拟线程） | Ollama 本身串行推理，多 worker 只会排队争抢；99% 时间阻塞在 HTTP 等待，适合虚拟线程 |
| 推理硬超时 | 15 分钟 | r1 独占 Ollama 实测最长 6.6 分钟；2026-09-17 端到端实测 worker 与每分钟巡检 qwen3 并发争抢 Ollama 串行推理时单任务 530 秒（8.8 分钟），15 分钟覆盖争抢场景且不误杀；超时任务标 FAILED |
| 超时后僵尸宽限 | 20 分钟 | `cancel` 断不掉底层 HTTP：任务超时标 FAILED 后，被放弃的 r1 调用仍占着 Ollama 串行推理（上界 ≈ 读超时 16m × 框架尝试次数 − 任务硬超时 ≈ 17 分钟）。宽限期内 `hasInflightTask()` 继续 true、巡检继续让路，避免 qwen3 与僵尸争抢把"让路"要防的场景原样复现（2026-11-14 审查修复） |
| 同指纹防抖窗口 | 10 分钟 | 有 PENDING/RUNNING 在途、或窗口内已升级过 → 拒绝（`reason=dedup_window_or_inflight`）；**手动触发不受防抖限制，但受自动升级预留槽位限流**（2026-11-13：空闲槽位不足容量的 1/4（至少 1）时手动先拒，`reason=manual_reserved_for_auto`——手动旁路无防抖也无预留时，一个脚本可瞬间填满队列，4.3 升级链路被 queue_full 静默饿死；容量为 1 的极端配置下唯一槽位即预留槽，手动通道恒被拒） |
| 终态任务保留 | 最近 100 条 | 内存态，超出按 finishedAt 淘汰最旧 |

> 表中"值"均为生产默认值，已全部外部化到 `application.yml` 的 `aiops.diagnosis.*`
> （`queue-capacity` / `dedup-window-minutes` / `worker-timeout-minutes` / `max-finished-tasks` / `zombie-grace-minutes`），
> 可用同名大写环境变量 `AIOPS_DIAGNOSIS_*` 覆盖，无需改代码重新打包。
> 抽成可注入参数而非常量的另一个收益：worker 超时分支可用毫秒级 Duration 单测，不必真等 15 分钟。

重启语义：任务不持久化——与 IncidentStore 取舍一致，未完成的深度诊断由下一轮巡检按 4.3 规则重新升级。

### 6.4 升级触发规则（4.3）

[OpsScheduler.chooseEscalationTrigger](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 是纯静态函数（不碰 LLM 与队列），规则与优先级：

| 优先级 | trigger | 条件 |
|--------|---------|------|
| 1（最高） | `critical_write` | NEW + severity=critical + suggestion 含写操作关键词（重启/回滚/停机/下线/kill/restart/rollback） |
| 2 | `persistent` | ACTIVE 且 firstSeen 距今 ≥ 5 分钟 |
| 3 | `low_confidence` | 快通道 JSON 自报 `confidence=low`（含判 normal 时的漏报复核） |
| 旁路 | `parse_fail` | 正常路径（非降级）连续 2 轮 JSON 解析失败；提交后计数归零，防抖窗口兜底 |

一轮内每个目标的判定最多各升级一个 trigger（多目标下两个应用可同时各升一个）。降级路径（Ollama 不可用）不触发升级——r1 同样调不动，排队只会全部 FAILED。

巡检 JSON 契约因此新增可选字段：`"confidence":"high|medium|low"`，老输出缺字段时按 `medium` 处理，向后兼容。

### 6.5 深度 Prompt 结构

自由文本 Markdown（报告给人读，非 JSON），五个固定部分：根因定位（区分现象与根因）、证据链（逐条指标）、影响面与演化风险、处置建议（只读动作与写操作分开，写操作强制标注【需人工审批】）、置信度与不确定性。快照与快通道结论均标注为不可信数据，延续提示注入防御口径。

### 6.6 审计事件

| 方法 | 输出 |
|------|------|
| `deepDiagnosisSubmitted()` | `深度诊断\|SUBMITTED\|taskId=..\|trigger=..\|fingerprint=..` |
| `deepDiagnosisFinished()` | `深度诊断\|END\|...\|result=succeeded/failed\|elapsedMs=..` |
| `deepDiagnosisRejected()` | `深度诊断\|REJECTED\|trigger=..\|fingerprint=..\|reason=dedup_window_or_inflight/queue_full/manual_reserved_for_auto（仅手动）` |

### 6.7 送达渠道的边界

当前为 pull 模式（轮询接口 + 控制台/审计），**不含** push——飞书/钉钉卡片属于白皮书 5.2。接入时 push 只从 `OpsAlertReporter.logDeepReport` 一处接出，队列/worker/升级规则都不用改。系统能力边界不变：深度模型只产出分析与建议，写操作仍须人工审批。

### 6.8 测试覆盖

各测试类的用例数与关键验证维度如下（超长验证维度用列表展开，避免表格单元格过长）：

| 测试类 | 用例数 |
|--------|--------|
| [DiagnosisServiceTest](../src/test/java/com/aiops/aiopscopilot/service/diagnosis/DiagnosisServiceTest.java) | 21 |
| [EscalationRuleTest](../src/test/java/com/aiops/aiopscopilot/service/EscalationRuleTest.java) | 17 |
| [OpsSchedulerEscalationWiringTest](../src/test/java/com/aiops/aiopscopilot/service/OpsSchedulerEscalationWiringTest.java) | 18 |
| [SnapshotCollectorTest](../src/test/java/com/aiops/aiopscopilot/service/SnapshotCollectorTest.java) | 8 |
| [DiagnosisControllerTest](../src/test/java/com/aiops/aiopscopilot/controller/DiagnosisControllerTest.java) | 7 |
| [ApiTokenAuthFilterTest](../src/test/java/com/aiops/aiopscopilot/common/security/ApiTokenAuthFilterTest.java) | 12 |
| [KnowledgeIngesterTest](../src/test/java/com/aiops/aiopscopilot/service/KnowledgeIngesterTest.java) | 8 |
| [OpsSchedulerExtractJsonTest](../src/test/java/com/aiops/aiopscopilot/service/OpsSchedulerExtractJsonTest.java) | 12 |
| [OpsSchedulerStatusNormalizeTest](../src/test/java/com/aiops/aiopscopilot/service/OpsSchedulerStatusNormalizeTest.java) | 16 |
| [PrometheusToolTest](../src/test/java/com/aiops/aiopscopilot/tool/PrometheusToolTest.java) | 15 |
| [PrometheusToolSnapshotTest](../src/test/java/com/aiops/aiopscopilot/tool/PrometheusToolSnapshotTest.java) | 10 |
| [TargetsPropertiesTest](../src/test/java/com/aiops/aiopscopilot/config/TargetsPropertiesTest.java) | 9 |
| [OpsAlertReporterTest](../src/test/java/com/aiops/aiopscopilot/service/OpsAlertReporterTest.java) | 4 |
| [DebugControllerWiringTest](../src/test/java/com/aiops/aiopscopilot/controller/DebugControllerWiringTest.java) | 4 |
| [DebugControllerTest](../src/test/java/com/aiops/aiopscopilot/controller/DebugControllerTest.java) | 1 |
| [GlobalExceptionHandlerTest](../src/test/java/com/aiops/aiopscopilot/common/exception/GlobalExceptionHandlerTest.java) | 6 |
| [ApiTokenSecurityConfigTest](../src/test/java/com/aiops/aiopscopilot/common/security/ApiTokenSecurityConfigTest.java) | 4 |
| [RequestLimitsTest](../src/test/java/com/aiops/aiopscopilot/common/web/RequestLimitsTest.java) | 5 |
| [AiChatControllerTest](../src/test/java/com/aiops/aiopscopilot/controller/AiChatControllerTest.java) | 6 |
| 其余单测 2 类（明细见下方列表） | 22 |
| integration 集（明细见下方列表） | 2 |

**各测试类关键验证维度**：

- **DiagnosisServiceTest（21 个）**
  - 同指纹 PENDING/RUNNING 在途防抖、窗口内终态防抖与窗口过期放行、不同指纹放行
  - 队列满时自动与手动任务均被拒绝
  - **手动提交限流（2026-11-13）**：手动最多占用 容量 − 预留（1/4、至少 1）个槽位，自动升级在预留槽位上照常入队
  - 15 分钟硬超时按注入阈值（200ms）快速 FAILED 且 error 标明硬超时；`cancel(true)` 中断挂死调用后 worker 继续消费
  - **（2026-11-14 审查修复）**：从 process 的 catch(Exception) 之外逃逸的 Error（快照序列化 OOM）→ workerLoop Throwable 兜底收敛 FAILED（worker_error）并继续消费——模型调用路径的 Error 会被 Future 包装成 ExecutionException 走正常 FAILED，兜底只拦真正的逸出；终态后的簿记异常不覆盖成功报告且 worker 存活；硬超时后僵尸宽限期内 `hasInflightTask()` 继续 true、宽限期外恢复
  - 手动提交绕防抖；终态淘汰到上限且不误删在途任务；start/stop 生命周期
  - 成功流转 SUCCEEDED 时 prompt 含五段要素、不可信数据声明、人工审批声明
  - 模型异常流转 FAILED 后 worker 继续消费下一条；get 查询

- **EscalationRuleTest（17 个）**
  - 优先级与边界：critical+写建议→`critical_write`（最高）；持续 5 分钟整（>=）→`persistent`，4 分 59 秒不升级
  - 否定词修饰不算写建议（"无需重启""不建议重启或回滚"），且跨小句不传染（"不要慌，建议重启"仍算）
  - NEW 旧 firstSeen 不走 persistent；ACTIVE critical 写建议超时落到 persistent；critical_write 优先级压过 low
  - severity 大小写不敏感；中英文写关键词与空串
  - low→`low_confidence`；persistent 优先级高于 low；medium/HIGH 不误触发

- **OpsSchedulerEscalationWiringTest（18 个）**
  - 反射调 `handleInspectionResult`/`handleDegraded`：首轮失败不升级、连续 2 轮失败恰好一次 `parse_fail` 且 note 带原始输出
  - 升级后计数归零、成功轮重置计数、原始输出截断 1000 字符
  - 降级路径永不升级（`parse_fail` 与 critical+写建议两条路径都验）
  - **跨路径 QPS 归零统一（2026-11-13）**：降级轮 QPS=0（计数 1）→ AI 轮查询失败 qps=-1 → 降级轮 QPS=0 仍是 warning，残留计数不再跳级 critical 假死
  - 连续 3 轮降级 → `LLM_UNAVAILABLE` 告警 report 一次、后续降级走心跳、降级 normal 轮不 RESOLVED
  - critical 写建议 → `CRITICAL_WRITE` 指纹含 `BLOCKED=2`；normal+low→`LOW_CONFIDENCE`；缺 confidence 默认 medium 不升级
  - 同指纹第二轮走心跳不升级
  - 多目标（2026-11-08）：双目标逐目标路由（APP 前缀指纹隔离）、同异常双目标两条独立指纹、幻觉目标名跳过、旧单对象契约在多目标快照下拒绝
  - **裸数组输出兼容（2026-11-13 实测发现）**：qwen3 偶发省略 `{"targets":...}` 外壳直接输出判定数组——解析层接受，照常进状态机
  - 让路：降级路径的 critical+写建议与 parse_fail 均不升级（r1 调不动）

- **SnapshotCollectorTest（8 个）**：self 兜底在列/不重复、self BLOCKED>0 挂死锁诊断、多目标采集顺序、业务目标不触发 ThreadMXBean（硬边界）、单目标失败隔离、未知语义键 fail-fast、Error 级采集失败降级为 error 块而非 null 目标块（2026-11-13）
- **DiagnosisControllerTest（7 个）**：POST 成功 200 返回 PENDING 视图、空白 message 归一化 null、非空白 trim、队列满 503、GET 存在返回终态视图、不存在 404、FAILED 视图带 error
- **ApiTokenAuthFilterTest（12 个）**：MockHttpServletRequest 纯单测——无凭据/错 token→401 且不放行；Bearer（scheme 大小写不敏感、值首尾空白容忍）、X-API-Key、`?token=`（仅两个 SSE 端点放行，普通端点拒绝）三种方式；空 Bearer 值→401；**context-path 部署按 servletPath 匹配 SSE 白名单（2026-11-13）**、未设 servletPath 时回退 requestURI、带前缀的非 SSE 路径照样 401
- **KnowledgeIngesterTest（8 个）**：标题提取纯函数（标准标题提取并剥离正文、无标题兜底文件名、文首空行跳过、英文冒号与 trim、单/双引号标题拒绝、空内容兜底）；ingest 接线（mock VectorStore + 真实语料）：新旧 source 双删、切片继承标题 source、标题不入正文
- **OpsSchedulerExtractJsonTest（12 个）**：反射测 `extractJson`——纯 JSON 透传、```json/```围栏剥离、只有开头围栏、**无换行围栏（```json{...}```，2026-11-13）/无语言标记/无换行+空白组合**、`</think>` 残留剥离、思考段+围栏组合、null→空串、散文原样返回（不做花括号扫描）、围栏后空白 trim
- **GlobalExceptionHandlerTest（6 个，2026-11-13 新增）**：405/406/415 与坏 JSON 请求体 400 各自返回正确业务码，不再落入 500 兜底；**参数字节非 UTF-8（如 GBK 表单）→ 400**（2026-11-14，实测发现该场景曾误报 500"系统内部错误"）；**客户端断开（SSE 中断）静默完成不回写**（2026-11-14 断连实测：此前落 500 兜底产生 ERROR + handler 二次失败双重噪音）
- **ApiTokenSecurityConfigTest（4 个，2026-11-13 新增）**：TokenConfigured 条件直读 Environment——未配置/纯空白 token 不装配、含单引号的 SpEL 炸弹 token 正常装配、正常 token 装配
- **RequestLimitsTest（5 个，2026-11-13 新增）**：checkMessage 消息长度边界（纯单测）——null/空串放行、恰好等于上限放行、超一个字符拒绝（400 业务码含上限值）；**violationMessage 非抛校验（2026-11-14，SSE 端点专用）**——可接受输入返回 null、超限返回与 checkMessage 同文错误文案；运行时 GET query 会先被 Tomcat 的 8KB header 上限拦截，应用层这道边界只能经 POST form body 触达，故以单测钉死而不依赖容器配置
- **PrometheusToolTest（15 个）**：QPS 减法（正常/基线大于总量钳零/总量缺失哨兵/基线缺失按 0）、`resolvePromql`（默认模板替换 application、覆盖优先且支持占位符、无占位符原样用）、JVM 默认档覆盖除 qpsLast1m 外的全部语义键；反射测 `parseValue`——小数/整数/科学计数法正常解析，NaN（大小写）/空串/脏字符串/null 统一 -1 哨兵（全系统"查询失败"契约）
- **TargetsPropertiesTest（9 个）**：self 自动在列且不重复、重名/重复 application/指纹分隔符/破坏 PromQL 的字符/空白字段拒绝、自定义目标保留、metrics 防御性不可变拷贝
- **OpsAlertReporterTest（4 个）**：normal 摘要逐目标列出关键指标（多目标带分隔、单目标无分隔）、缺 targets 块退化为空、error 块透传
- **OpsSchedulerStatusNormalizeTest（16 个，2026-11-13 新增）**：status 白名单归一化（契约内三值 / 大小写与首尾空白 / 中文同义词最小映射 / 契约外一律"判不了"）；路由层——契约外 status 与缺失 status 均不报 ERROR 告警、不建事件、不污染状态机；对照组（normal 仍静默、critical 仍照常告警）；"判不了"的目标其存量事件不被误归档（没判出来 ≠ 已恢复）；**判不了但硬信号命中仍由 AI 漏报兜底接住**（BLOCKED>0 → critical、CPU>90% → warning 两条都验；"没说话"不能让死锁漏报一整轮）；全部目标判不了（含兜底未命中）走 parse_error 通道（连续 2 轮升级深度诊断）；部分目标判不了时轮级状态取 unknown；**AI 路径空闲基线门控（2026-11-14）**——AI 判 warning 但从未观测到流量且指纹纯 ZERO_QPS 时降为 normal（不报、不建事件，实测空闲环境曾 9 分钟才自愈），对照组：观测过流量后归零照常告警（防"空闲误报"修复引入"假死漏报"）；门控压下的 warning+low 不唤醒深度诊断（防空闲环境按防抖周期反复唤醒 r1）
- **DebugControllerWiringTest（4 个，2026-11-13 新增）**：ApplicationContextRunner 验证 `@Profile("dev")` + `@ConditionalOnProperty(aiops.debug.enabled)` 双重门控的四种组合——dev+开启装配（演练形态）、dev 未开启不装配（默认安全形态）、prod 误开仍不装配、无 profile 误开仍不装配
- **DebugControllerTest（1 个，2026-11-13 新增）**：直接 new 不起 Spring 容器，与 WiringTest 互补（后者锁装配门控，本类锁端点行为）——cpu-spike 入参钳制（seconds 超上限钳到 60）与代数终止（reset 递增 generation 让自旋线程退出，测试 JVM 不留后台自旋线程）；deadlock/slow-request 按设计留作演练手册的人工验证项，不进秒级单测集
- **AiChatControllerTest（6 个，2026-11-14 新增）**：两个流式端点（/api/ai/chat、/api/ai/rag/stream）的输入契约——缺参与超长消息均以首个 `[ERROR]` 事件返回而非同步抛异常（EventSource 固定 Accept: text/event-stream，同步异常经全局异常器产出 JSON Result 会因内容协商失败退化为容器 406 裸错误）；**SSE 心跳（2026-11-14 断连实测后新增）**——静默期每 15s 一条注释行事件（无 data 字段、EventSource 按规范忽略），数据完成即停（不拖延收尾）；直接 new 不起 Spring 容器
- **PrometheusToolSnapshotTest（10 个，2026-11-13 新增）**：用 JDK 内置 HttpServer 起假 Prometheus（不引新依赖、不需真实基础设施）——默认档 7 条语义键全部回填且 QPS = 总速率 − actuator 基线、每条查询带 application 标签过滤、URL 编码路径未退化（服务端解出的 PromQL 原文含双引号标签值）、显式留空语义记 -1 哨兵且不发查询（qpsLast1m 留空时只发 6 条默认语义、减法子查询一并跳过）、自定义 QPS 走单查询跳过减法、8 条查询并行（注入固定延迟后以墙钟断言钉死，改回串行会立刻红）、**跨实例聚合形态**——CPU/BLOCKED 外层 max、堆分代 max by (id)、actuator 基线 sum(rate(...))、**被动模式查询缓存**——TTL 内重复查询只发一次 HTTP、过期重发且不同 PromQL 互相独立
- **其余 22 个**：IncidentStoreTest 17（指纹/状态机/按目标归档隔离/反射兜底/durationOf/TTL 过期/并发防丢失更新；**与评估集 F1/F3 重复的 2 条兜底用例已于 2026-11-13 去重删除**，死锁与 QPS 假死完整场景以 FaultInjectionEvaluationTest 为单一事实来源）+ FaultInjectionEvaluationTest 5（5 类注入的确定性回归）
- **integration 集（2 个，`gradle integrationTest` 触发）**：AiopsCopilotApplicationTests 1（Spring 装配冒烟，**需 Milvus 可达**：客户端 bean 创建即建 gRPC 连接）+ VirtualThreadVsThreadPoolBenchmarkTest 1（虚拟线程演示基准——墙钟断言在负载下偶发红，不进秒级单测集）

> 合计 **207** 个用例（205 单测 + 2 集成；单测集 2026-11-14 `clean test` 全量全绿）。单测集全部不起容器（反射/动态代理伪 ChatClient/Mockito/真实 record 夹具四种手法），均不依赖外部服务；集成集含需 Milvus 的装配冒烟与不依赖基础设施的演示基准。单测集由 [.github/workflows/ci.yml](../.github/workflows/ci.yml) 在 push/PR 时自动执行（2026-11-13 新增；集成集按分层设计不进 CI，见 build.gradle 注释）。

