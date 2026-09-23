# 事件状态机、降级与深度诊断设计

> 配套白皮书 §4.1（双模型路由 + 可靠性增强）、§4.2/4.3（异步深度诊断与升级路由）与 §5.1（事件生命周期管理）的详细设计文档。
> 本文档的实现已通过 108 个单元测试（2026-09-23 clean test 全绿；明细见 §6.8）+ 端到端死锁/降级/深度诊断链路验证。

## 1. 设计背景

巡检调度器（[OpsScheduler](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)）每分钟跑一次，必须满足两条硬约束：

1. **告警不刷屏**：同一持续性故障（如死锁）在恢复前会被反复"发现"，必须去重——否则 60 条/小时的告警风暴会淹没真正重要的信号，且每轮重复 LLM 推理浪费 Token。
2. **Ollama 挂了巡检不能停**：LLM 是核心决策组件，但不是唯一手段。当 Ollama 进程不可用、网络异常或推理卡死时，巡检调度器必须降级到硬阈值判断，否则等同于"AI 一挂，监控全瞎"。

补充约束：**AI 漏报兜底**——模型可能误判 normal（例如 qwen3:8b 关闭思考链后对复合故障识别有盲区），代码层必须在关键指标严重超阈值时强改 warning/critical，不依赖 AI 的正确性。

## 2. 事件状态机与去重

### 2.1 状态机定义

```
NEW（首次发现）→ ACTIVE（持续中，第二周期起改走 INFO 心跳日志）
   → RESOLVED（连续 3 轮 normal 后自动归档）
```

| 状态 | 触发条件 | 输出 |
|------|----------|------|
| NEW | 当前轮 fingerprint 在 store 中不存在或上次已 RESOLVED | [OpsAlertReporter.report](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L53) — ERROR + ASCII 框线全量报告 |
| ACTIVE | 同 fingerprint 仍在（未 RESOLVED） | [OpsAlertReporter.logHeartbeat](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L77) — INFO 一行心跳 |
| RESOLVED | 连续 3 轮巡检 normal 后自动标记 | [OpsAlertReporter.logResolved](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L90) — INFO 一行恢复 |

### 2.2 Fingerprint 构造（基于指标快照，不基于 LLM 文本）

**关键设计决策**：fingerprint 基于**指标快照特征**，**不基于 LLM rootCause 文本**。

> 原因：LLM 对同一故障的描述每次会略有不同（2026-09-15 实测：死锁 NEW 轮的 rootCause 是"存在死锁，死锁线程为 deadlock-worker-1 和 deadlock-worker-2，分别在等待对方持有的锁..."，后续 ACTIVE 轮变成"存在死锁，deadlockedThreads 列出的线程 deadlock-worker-1 和 deadlock-worker-2 因锁顺序问题相互等待..."）。用文本前缀做指纹会导致同故障被反复识别为 NEW、去重失效。
>
> 指标特征是稳定的：只要同一类指标异常（如 BLOCKED>0），无论 LLM 怎么描述都会合并到同一指纹。

实现：[IncidentStore.fingerprintOf](../src/main/java/com/aiops/aiopscopilot/service/IncidentStore.java#L127)

```
fingerprint = 异常级别 + "|" + 指标特征串
```

指标特征串的构造规则（按优先级）：

| 条件 | 特征片段 | 说明 |
|------|----------|------|
| snapshot 含 `deadlockDiagnosis` 字段 | `DEADLOCK;` | OpsScheduler 在 BLOCKED>0 时主动调用 ThreadMXBean 触发 |
| `blockedThreads > 0` | `BLOCKED=N;` | N 是具体数量（同数量合并） |
| `cpuUsage > 0.8` | `HIGH_CPU=X;` | X = `(int)(cpu * 10)`，分桶避免轻微波动（0.91 vs 0.92）产生不同指纹 |
| `heapUsage > 0.85` | `HIGH_HEAP=X;` | 同 CPU 分桶逻辑 |
| `qpsLast1m == 0 且 cpu >= 0` | `ZERO_QPS;` | 应用假死信号；cpu>=0 排除指标拉取失败的 -1 哨兵 |
| `gcCountLast5m > 10` | `FREQUENT_GC;` | 疑似内存泄漏 |

**示例**：死锁场景 → `CRITICAL|DEADLOCK;BLOCKED=2;`

### 2.3 存储选型：内存级

- 实现：`ConcurrentHashMap<String, Incident>`，重启丢失
- 取舍：与"自治诊断"能力边界一致——死锁等故障应用重启后也会消失，持久化的旧事件反而是噪音
- 零依赖、零外部状态，部署简单

### 2.4 Incident 不可变性

每次状态更新都新建 `Incident` 实例（所有字段 final），保证 `ConcurrentHashMap` 读写的内存可见性。`consecutiveNormalRounds` 计数通过新建实例递增，不暴露 setter，避免外部意外修改。

## 3. Ollama 降级与阈值兜底

### 3.1 LLM 超时保护

[OpsScheduler.callLlmWithTimeout](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L186)：

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
        inspectorChatClient.prompt().user(prompt).call().content(), llmCalls);
return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);  // 45 秒
```

- 超时预算：`LLM_TIMEOUT_SECONDS = 45`（fixedDelay 60 秒的 75%，留余量给指标拉取与解析）
- 跑在专用虚拟线程执行器 `llmCalls` 上而非公共 ForkJoinPool：超时后 `future.cancel(true)` 并不能中断底层 HTTP，被占死的应是廉价虚拟线程，不能落在 commonPool 上饿死其他并行计算
- 超时后本周期走降级路径，Ollama 侧推理自行结束

### 3.2 降级路径

[OpsScheduler.handleDegraded](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L206)：

```
catch (TimeoutException | Exception):
  if snapshot != null:
    handleDegraded(snapshot, start)  // 走阈值兜底
  else:
    recordInspection("error", ...)   // 指标拉取也失败，本轮完全失败
```

降级路径调用 [fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L258) 构造与 LLM 等价的 JSON，再走 `handleInspectionResult(..., degraded=true)`：
- 仍接入 IncidentStore 状态机去重（同一指纹的 NEW→ACTIVE 流转不变）
- `metricsService.recordInspection("degraded_" + status, ...)` 标记降级路径，便于运维区分

### 3.3 阈值兜底规则

[OpsScheduler.fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L258) 的硬阈值判断（优先级：死锁 > OOM > 假死 > GC > CPU）：

| 条件 | 级别 | rootCause 标注 |
|------|------|---------------|
| `blockedThreads > 0` | critical | `代码级兜底：存在 BLOCKED 线程（N），疑似死锁` |
| `heapUsage > 0.95` | critical | `代码级兜底：堆内存使用率 X > 0.95` |
| `qpsLast1m == 0 且 cpu >= 0` | critical | `代码级兜底：QPS=0 但端口在，应用可能假死` |
| `gcCountLast5m > 10` | warning（若未达 critical） | `代码级兜底：5min GC 次数 N > 10，疑似内存泄漏` |
| `cpuUsage > 0.90` | warning（若未达 critical） | `代码级兜底：CPU 使用率 X > 0.90` |

### 3.4 AI 漏报兜底（applyThresholdBackstop）

[OpsScheduler.applyThresholdBackstop](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L519) 在 LLM 路径（非降级）中额外加一道兜底：

- **仅当 AI 判 normal 时介入**（warning/critical 不改判，避免覆盖 AI 的更细致判断）
- normal 但 `blockedThreads > 0` → 强改 critical
- normal 但 `heapUsage > 0.95` → 强改 critical
- normal 但 `cpuUsage > 0.90` → 强改 warning
- rootCause 追加 `[代码级兜底已介入：warning]` 标注，便于事后定位 AI 漏报原因

### 3.5 LLM 长期不可用的显式告警（LLM_UNAVAILABLE）

Ollama 长期挂掉时每轮只留下一条 WARN 日志与 `degraded_*` 指标——"AI 瞎了"这样重要的故障不能只活在日志里。设计（`OpsScheduler.trackLlmUnavailable`）：

- **触发**：连续 3 轮降级（`handleDegraded` 被调用）即登记独立指纹 `CRITICAL|LLM_UNAVAILABLE;` 的 critical 事件，复用 5.1 状态机——后续降级轮走 INFO 心跳；偶发 1-2 轮降级（瞬时抖动）不告警
- **恢复**：LLM 正常返回即重置计数；事件由连续 3 轮 normal 自动 RESOLVED
- **配套规则一（盲期不归档）**：降级轮的 normal 只是硬阈值判断、语义置信度低，不调用 `bumpNormalAndResolve`——否则 Ollama 长挂时事件会被"降级 normal"轮错误归档，随后又被下一轮降级登记为 NEW，反复震荡
- **配套规则二（降级不升级）**：降级路径不触发任何 4.3 深度诊断升级——r1 与快通道共用同一个 Ollama，挂了就都调不动，排队只会产出一串注定 FAILED 的任务

## 4. 验证

### 4.1 单元测试

[IncidentStoreTest](../src/test/java/com/aiops/aiopscopilot/service/IncidentStoreTest.java) 覆盖 15 个用例：

| 维度 | 用例数 | 关键验证 |
|------|--------|---------|
| IncidentStore 状态机 | 5 | NEW→ACTIVE→RESOLVED 流转、复发重新置 NEW、normal 重置计数 |
| AI 漏报兜底 | 5 | normal+CPU>90→warning、normal+BLOCKED>0→critical、normal+堆>95→critical、warning 不变、全正常不变 |
| Ollama 降级路径 | 5 | BLOCKED>0→critical、CPU>90→warning、QPS=0→critical、GC>10→warning、全正常→normal |

另见 [故障注入评估手册](aiops-fault-injection-eval.md)：F1-F5 五类故障场景（死锁/内存泄漏/假死/慢接口/瞬时尖峰）的确定性回归由 [FaultInjectionEvaluationTest](../src/test/java/com/aiops/aiopscopilot/service/FaultInjectionEvaluationTest.java) 覆盖，验证指纹构造、阈值兜底与尖峰抑制的状态机行为。

### 4.2 端到端验证

**死锁去重**（[OpsScheduler](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java) 实际运行日志）：

| 轮次 | 行为 | 日志级别 |
|------|------|----------|
| Round 1 baseline | CPU=0、BLOCKED=0 → 正常 | INFO |
| Round 2（NEW） | 发现死锁，全量 ASCII 报告 + 根因 | ERROR |
| Round 3（ACTIVE） | `故障持续中 \| 指纹=CRITICAL\|DEADLOCK;BLOCKED=2; \| 已持续 1min` | INFO 心跳 |
| Round 4（ACTIVE） | `已持续 3min` | INFO 心跳 |

**Ollama 降级**（kill ollama 进程后）：

```
17:48:06 巡检开始
17:48:08 WARN Spring AI Retry count 1
17:48:18 WARN Spring AI Retry count 2
17:48:51 WARN [OpsScheduler] LLM 推理超过 45s 未返回，启用阈值兜底
17:48:51 INFO [AIOps 心跳] 故障持续中 | 指纹=CRITICAL|DEADLOCK;BLOCKED=2;  ← fallbackByThreshold 识别死锁
```

降级路径与 LLM 路径生成同一指纹（`CRITICAL|DEADLOCK;BLOCKED=2;`），正确合并到同一事件，输出 INFO 心跳而非 ERROR 全量报告。

## 5. 审计日志

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
| `inspectionFinished()` | 巡检结束 | `巡检\|END\|status=critical\|elapsedMs=3500\|fingerprint=CRITICAL\|DEADLOCK;BLOCKED=2;` |
| `degradedPathTriggered()` | LLM 超时/异常降级 | `巡检\|DEGRADED\|reason=llm_timeout\|elapsedMs=45000` |
| `backstopTriggered()` | AI 漏报兜底介入 | `巡检\|BACKSTOP\|aiStatus=normal\|forcedStatus=warning\|metric=cpuUsage\|value=0.92` |
| `incidentNew()` | 事件首次发现 | `事件\|NEW\|fingerprint=...\|status=critical\|summary=...` |
| `incidentActive()` | 事件持续中心跳 | `事件\|ACTIVE\|fingerprint=...\|durationMin=3` |
| `incidentResolved()` | 事件恢复归档 | `事件\|RESOLVED\|fingerprint=...\|totalDurationMin=5` |
| `fallbackResult()` | 降级路径阈值判断结果 | `巡检\|FALLBACK_RESULT\|status=degraded\|rootCause=...\|snapshot=...` |

审计链闭合保证：每条 `巡检|START` 都有对应的 `巡检|END`——解析失败记 `status=parse_error`（降级路径为 `degraded_parse_error`）、指标拉取/兜底全失败记 `status=error`，不会出现只有 START 的悬空轮次。

## 6. 异步深度诊断与升级路由（白皮书 4.2/4.3）

### 6.1 为什么必须异步

deepseek-r1:8b 启用思考链后单次推理实测 1.7-6.6 分钟（见白皮书双模型表），是巡检 60 秒预算的 2-7 倍、同步 HTTP 120 秒超时的 1-3 倍。同步等待会同时拖垮巡检周期与交互请求；但 r1 的长链推理在复合故障与 SOP 决策上的能力是关闭思考链的 qwen3:8b 不具备的。解法是**任务队列 + 轮询**，把"等待"从请求线程移走。

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
| [SnapshotCollector](../src/main/java/com/aiops/aiopscopilot/service/SnapshotCollector.java) | 7 条指标 + BLOCKED>0 时死锁二级诊断；巡检与深度诊断共用，保证快照口径一致 |

### 6.3 队列、超时与防抖参数

| 参数 | 值 | 理由 |
|------|----|------|
| 队列容量 | 20 | r1 串行且单次数分钟，堆积无意义；满了拒绝 + 审计 `REJECTED|reason=queue_full`，不阻塞巡检线程 |
| worker 数 | 1（虚拟线程） | Ollama 本身串行推理，多 worker 只会排队争抢；99% 时间阻塞在 HTTP 等待，适合虚拟线程 |
| 推理硬超时 | 15 分钟 | r1 独占 Ollama 实测最长 6.6 分钟；2026-09-17 端到端实测 worker 与每分钟巡检 qwen3 并发争抢 Ollama 串行推理时单任务 530 秒（8.8 分钟），15 分钟覆盖争抢场景且不误杀；超时任务标 FAILED |
| 同指纹防抖窗口 | 10 分钟 | 有 PENDING/RUNNING 在途、或窗口内已升级过 → 拒绝（`reason=dedup_window_or_inflight`）；**手动触发不受限** |
| 终态任务保留 | 最近 100 条 | 内存态，超出按 finishedAt 淘汰最旧 |

> 表中"值"均为生产默认值，已全部外部化到 `application.yml` 的 `aiops.diagnosis.*`
> （`queue-capacity` / `dedup-window-minutes` / `worker-timeout-minutes` / `max-finished-tasks`），
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

一轮巡检最多升级一个 trigger。降级路径（Ollama 不可用）不触发升级——r1 同样调不动，排队只会全部 FAILED。

巡检 JSON 契约因此新增可选字段：`"confidence":"high|medium|low"`，老输出缺字段时按 `medium` 处理，向后兼容。

### 6.5 深度 Prompt 结构

自由文本 Markdown（报告给人读，非 JSON），五个固定部分：根因定位（区分现象与根因）、证据链（逐条指标）、影响面与演化风险、处置建议（只读动作与写操作分开，写操作强制标注【需人工审批】）、置信度与不确定性。快照与快通道结论均标注为不可信数据，延续提示注入防御口径。

### 6.6 审计事件

| 方法 | 输出 |
|------|------|
| `deepDiagnosisSubmitted()` | `深度诊断\|SUBMITTED\|taskId=..\|trigger=..\|fingerprint=..` |
| `deepDiagnosisFinished()` | `深度诊断\|END\|...\|result=succeeded/failed\|elapsedMs=..` |
| `deepDiagnosisRejected()` | `深度诊断\|REJECTED\|trigger=..\|fingerprint=..\|reason=dedup_window_or_inflight/queue_full` |

### 6.7 送达渠道的边界

当前为 pull 模式（轮询接口 + 控制台/审计），**不含** push——飞书/钉钉卡片属于白皮书 5.2。接入时 push 只从 `OpsAlertReporter.logDeepReport` 一处接出，队列/worker/升级规则都不用改。系统能力边界不变：深度模型只产出分析与建议，写操作仍须人工审批。

### 6.8 测试覆盖

| 测试类 | 用例数 | 关键验证 |
|--------|--------|---------|
| DiagnosisServiceTest | 16 | 同指纹 PENDING/RUNNING 在途防抖、窗口内终态防抖与窗口过期放行、不同指纹放行、队列满拒绝自动与手动任务、get 查询、worker 成功流转 SUCCEEDED 且 prompt 含五段要素/不可信数据/人工审批声明、模型异常流转 FAILED 且 worker 继续消费下一条、**15 分钟硬超时按注入阈值（200ms）快速 FAILED 且 error 标明硬超时、cancel(true) 中断挂死调用后 worker 继续消费**、手动提交绕防抖、终态淘汰到上限且不误删在途任务、start/stop 生命周期 |
| EscalationRuleTest | 17 | critical+写建议→critical_write、warning 含写词不升级、**否定词修饰不算写建议（"无需重启""不建议重启或回滚"）且跨小句不传染（"不要慌，建议重启"仍算）**、持续 5 分钟整（>=）→persistent、4分59秒不升级、NEW 旧 firstSeen 不走 persistent、ACTIVE critical 写建议超时落到 persistent、critical_write 优先级压过 low、severity 大小写不敏感、中英文写关键词与空串、low→low_confidence、persistent 优先级高于 low、medium/HIGH 不误触发 |
| OpsSchedulerEscalationWiringTest | 12 | 反射调 handleInspectionResult/handleDegraded：首轮失败不升级、连续 2 轮失败恰好一次 PARSE_FAIL 且 note 带原始输出、升级后计数归零、成功轮重置计数、原始输出截断 1000 字符、degraded 路径永不升级（parse_fail 与 **critical+写建议两条路径都验**）、**连续 3 轮降级 → LLM_UNAVAILABLE 告警 report 一次、后续降级走心跳且降级 normal 轮不 RESOLVED**、critical 写建议→CRITICAL_WRITE 指纹含 BLOCKED=2、normal+low→LOW_CONFIDENCE、缺 confidence 默认 medium 不升级、同指纹第二轮走心跳不升级 |
| SnapshotCollectorTest | 2 | BLOCKED=0 不调 detectDeadlock 且快照无诊断字段；BLOCKED>0 挂死锁诊断结果 |
| DiagnosisControllerTest | 7 | POST 成功 200 返回 PENDING 视图、空白 message 归一化 null、非空白 trim、队列满 503、GET 存在返回终态视图、不存在 404、FAILED 视图带 error |
| ApiTokenAuthFilterTest | 8 | MockHttpServletRequest 纯单测：无凭据/错 token→401 且不放行，Bearer（scheme 大小写不敏感、值首尾空白容忍）、X-API-Key、?token= 三种方式放行，空 Bearer 值→401 |
| KnowledgeIngesterTest | 8 | 标题提取纯函数：标准标题提取并剥离正文、无标题兜底文件名、文首空行跳过、英文冒号与 trim、单/双引号标题拒绝、空内容兜底；ingest 接线（mock VectorStore + 真实语料）：新旧 source 双删、切片继承标题 source、标题不入正文 |
| OpsSchedulerExtractJsonTest | 9 | 反射测 extractJson：纯 JSON 透传、```json/```围栏剥离、只有开头围栏、</think> 残留剥离、思考段+围栏组合、null→空串、散文原样返回（不做花括号扫描）、围栏后空白 trim |
| PrometheusToolTest | 7 | 反射测 parseValue：小数/整数/科学计数法正常解析，NaN（大小写）/空串/脏字符串/null 统一 -1 哨兵（全系统"查询失败"契约） |
| 其余 | 22 | IncidentStoreTest 15（指纹/状态机/反射兜底）+ FaultInjectionEvaluationTest 5（5 类注入的确定性回归）+ AiopsCopilotApplicationTests 1（Spring 装配冒烟，**需 Milvus 可达**：客户端 bean 创建即建 gRPC 连接）+ VirtualThreadVsThreadPoolBenchmarkTest 1（虚拟线程演示基准，非断言型回归） |

> 合计 **108** 个用例（2026-09-23，clean test 全绿；除 contextLoads 需 Milvus 外均不依赖任何外部服务）。除 contextLoads 外全部是不起容器的纯单测（反射/动态代理伪 ChatClient/Mockito/真实 record 夹具四种手法，见 .trae/learning-guide.md 测试章节）。

