# 事件状态机与降级设计

> 配套白皮书 §4.1（双模型路由 + 可靠性增强）与 §5.1（事件生命周期管理）的详细设计文档。
> 本文档的实现已通过 17 个单元测试 + 端到端死锁/降级路径验证。

## 1. 设计背景

巡检调度器（[OpsScheduler](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java)）每分钟跑一次，必须满足两条硬约束：

1. **告警不刷屏**：同一持续性故障（如死锁）在恢复前会被反复"发现"，必须去重——否则 60 条/小时的告警风暴会淹没真正重要的信号，且每轮重复 LLM 推理浪费 Token。
2. **Ollama 挂了巡检不能停**：LLM 是核心决策组件，但不是唯一手段。当 Ollama 进程不可用、网络异常或推理卡死时，巡检调度器必须降级到硬阈值判断，否则等同于"AI 一挂，监控全瞎"。

补充约束（来自项目 memory）：**AI 漏报兜底**——模型可能误判 normal（例如 qwen3:8b 关闭思考链后对复合故障识别有盲区），代码层必须在关键指标严重超阈值时强改 warning/critical，不依赖 AI 的正确性。

## 2. 事件状态机与去重

### 2.1 状态机定义

```
NEW（首次发现）→ ACTIVE（持续中，第二周期起改走 INFO 心跳日志）
   → RESOLVED（连续 3 轮 normal 后自动归档）
```

| 状态 | 触发条件 | 输出 |
|------|----------|------|
| NEW | 当前轮 fingerprint 在 store 中不存在或上次已 RESOLVED | [OpsAlertReporter.report](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L42) — ERROR + ASCII 框线全量报告 |
| ACTIVE | 同 fingerprint 仍在（未 RESOLVED） | [OpsAlertReporter.logHeartbeat](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L65) — INFO 一行心跳 |
| RESOLVED | 连续 3 轮巡检 normal 后自动标记 | [OpsAlertReporter.logResolved](../src/main/java/com/aiops/aiopscopilot/service/OpsAlertReporter.java#L77) — INFO 一行恢复 |

### 2.2 Fingerprint 构造（基于指标快照，不基于 LLM 文本）

**关键设计决策**：fingerprint 基于**指标快照特征**，**不基于 LLM rootCause 文本**。

> 原因：LLM 对同一故障的描述每次会略有不同（实测：死锁的 rootCause 第一轮是"存在死锁，死锁线程为 deadlock-thread-1 和..."，第二轮变成"存在死锁，deadlockedThreads 列出了..."）。用文本前缀做指纹会导致同故障被反复识别为 NEW、去重失效。
>
> 指标特征是稳定的：只要同一类指标异常（如 BLOCKED>0），无论 LLM 怎么描述都会合并到同一指纹。

实现：[IncidentStore.fingerprintOf](../src/main/java/com/aiops/aiopscopilot/service/IncidentStore.java#L113)

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

[OpsScheduler.callLlmWithTimeout](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L139)：

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
        inspectorChatClient.prompt().user(prompt).call().content());
return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);  // 45 秒
```

- 超时预算：`LLM_TIMEOUT_SECONDS = 45`（fixedDelay 60 秒的 75%，留余量给指标拉取与解析）
- 超时后 `future.cancel(true)`，但 Ollama 客户端可能不响应中断——至少释放等待线程，本周期走降级路径

### 3.2 降级路径

[OpsScheduler.handleDegraded](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L162)：

```
catch (TimeoutException | Exception):
  if snapshot != null:
    handleDegraded(snapshot, start)  // 走阈值兜底
  else:
    recordInspection("error", ...)   // 指标拉取也失败，本轮完全失败
```

降级路径调用 [fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L170) 构造与 LLM 等价的 JSON，再走 `handleInspectionResult(..., degraded=true)`：
- 仍接入 IncidentStore 状态机去重（同一指纹的 NEW→ACTIVE 流转不变）
- `metricsService.recordInspection("degraded_" + status, ...)` 标记降级路径，便于运维区分

### 3.3 阈值兜底规则

[OpsScheduler.fallbackByThreshold](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L170) 的硬阈值判断（优先级：死锁 > OOM > 假死 > GC > CPU）：

| 条件 | 级别 | rootCause 标注 |
|------|------|---------------|
| `blockedThreads > 0` | critical | `代码级兜底：存在 BLOCKED 线程（N），疑似死锁` |
| `heapUsage > 0.95` | critical | `代码级兜底：堆内存使用率 X > 0.95` |
| `qpsLast1m == 0 且 cpu >= 0` | critical | `代码级兜底：QPS=0 但端口在，应用可能假死` |
| `gcCountLast5m > 10` | warning（若未达 critical） | `代码级兜底：5min GC 次数 N > 10，疑似内存泄漏` |
| `cpuUsage > 0.90` | warning（若未达 critical） | `代码级兜底：CPU 使用率 X > 0.90` |

### 3.4 AI 漏报兜底（applyThresholdBackstop）

[OpsScheduler.applyThresholdBackstop](../src/main/java/com/aiops/aiopscopilot/service/OpsScheduler.java#L326) 在 LLM 路径（非降级）中额外加一道兜底：

- **仅当 AI 判 normal 时介入**（warning/critical 不改判，避免覆盖 AI 的更细致判断）
- normal 但 `blockedThreads > 0` → 强改 critical
- normal 但 `heapUsage > 0.95` → 强改 critical
- normal 但 `cpuUsage > 0.90` → 强改 warning
- rootCause 追加 `[代码级兜底已介入：warning]` 标注，便于事后定位 AI 漏报原因

## 4. 验证

### 4.1 单元测试

[IncidentStoreTest](../src/test/java/com/aiops/aiopscopilot/service/IncidentStoreTest.java) 覆盖 15 个用例：

| 维度 | 用例数 | 关键验证 |
|------|--------|---------|
| IncidentStore 状态机 | 5 | NEW→ACTIVE→RESOLVED 流转、复发重新置 NEW、normal 重置计数 |
| AI 漏报兜底 | 5 | normal+CPU>90→warning、normal+BLOCKED>0→critical、normal+堆>95→critical、warning 不变、全正常不变 |
| Ollama 降级路径 | 5 | BLOCKED>0→critical、CPU>90→warning、QPS=0→critical、GC>10→warning、全正常→normal |

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
