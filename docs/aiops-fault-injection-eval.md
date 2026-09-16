# 故障注入评估手册

> 白皮书 §三「故障注入评估体系」的落地：5 类注入端点 + 用例表 + 度量口径。
> 回归分两层：
> **确定性层**（指纹构造 / 阈值兜底 / 状态机流转）由 [FaultInjectionEvaluationTest](../src/test/java/com/aiops/aiopscopilot/service/FaultInjectionEvaluationTest.java) 在 CI 自动执行；
> **LLM 根因层**（根因关键词命中、语义推理质量）需真实 Ollama，按本文第 3 节流程人工执行。
> 每次变更 prompt / 模型 / 工具后先跑确定性层，绿了再做 LLM 层回归。

## 1. 注入端点（仅 dev profile 装配）

全部位于 [DebugController](../src/main/java/com/aiops/aiopscopilot/controller/DebugController.java)，`@Profile("dev")`——prod 启动直接 404。若配置了 `AIOPS_API_TOKEN`，这些端点同其余 `/api/**` 一样需要鉴权。巡检周期 60 秒（fixedDelay），注入后最长 60 秒可观察到巡检响应。

| 端点 | 参数 | 注入效果 | 恢复方式 |
|------|------|----------|----------|
| `GET /api/debug/deadlock/a` + `GET /api/debug/deadlock/b` | -（需并发） | 相反锁序的一对业务接口，并发调用（±500ms 窗口）时两个平台 worker 交叉持锁死锁、对应请求永久挂起；后续调用阻塞在锁入口，BLOCKED 持续累积 | 仅重启应用 |
| `GET /api/debug/memory-leak` | `mb=100`（1..1024） | 保留 N MB 不释放：heapUsage ↑、Old 区增长、GC 频繁 | `/api/debug/memory-leak/release` 或 `/api/debug/reset` |
| `GET /api/debug/memory-leak/release` | - | 释放全部模拟泄漏内存 | - |
| `GET /api/debug/slow-request` | `seconds=45`（1..300） | 请求 sleep，完成后 maxRequestSeconds ↑（TimeWindow 约 2 分钟） | 自愈（约 2 分钟后回落） |
| `GET /api/debug/cpu-spike` | `seconds=5`（1..60）、`threads=0`（=核数，上限 64） | N 线程纯自旋的秒级 CPU 毛刺 | 自愈（到期自动停）或 `/api/debug/reset` |
| `GET /api/debug/reset` | - | 停止 CPU 毛刺 + 释放泄漏内存（死锁除外） | - |

## 2. 用例表（问题 → 期望行为 → 判定关键词）

| 用例 | 注入命令 | 期望行为（确定性层） | 期望根因关键词（LLM 层） |
|------|----------|---------------------|--------------------------|
| F1 死锁 | `curl --max-time 3 http://localhost:8080/api/debug/deadlock/a & curl --max-time 3 http://localhost:8080/api/debug/deadlock/b`（两条并发发） | 指纹 `CRITICAL\|DEADLOCK;BLOCKED=2;`（空闲环境因无背景流量会附加 `ZERO_QPS;`，属正常特征）；NEW 全量报告 → ACTIVE 心跳；LLM 不可用时兜底判 critical。只注入一对——重复注入会让 blockedThreads 计数漂移产生不同指纹（BLOCKED=N 分桶） | 死锁、deadlock-worker、锁 |
| F2 内存泄漏 | 多次 `curl "http://localhost:8080/api/debug/memory-leak?mb=200"`，直到 `堆使用率` > 0.90 | `heapUsage>0.95` 兜底 critical；指纹含 `HIGH_HEAP` / `FREQUENT_GC`；release 后 3 轮 normal 自动 RESOLVED | 堆、Old、内存泄漏、GC |
| F3 应用假死 | 停止全部业务流量（空闲应用即满足：actuator 抓取不计入 QPS） | `qpsLast1m=0` 且指标可拉取 → 降级路径兜底 critical；LLM 判异常时 `ZERO_QPS` 进指纹（LLM 判 normal 不强改，见第 5 节边界 2） | 假死、QPS 为零、端口在 |
| F4 慢接口/依赖卡死 | `curl "http://localhost:8080/api/debug/slow-request?seconds=45"`（可并发多笔） | 无硬阈值兜底（设计边界 2）；依赖 LLM 关联 maxRequestSeconds 与 QPS 趋势 | 慢、延迟、maxRequestSeconds、挂起 |
| F5 瞬时尖峰 | `curl "http://localhost:8080/api/debug/cpu-spike?seconds=5"` | 毛刺落在巡检间隔内 → 不告警；被捕获 → NEW 后 3 轮 normal 自动 RESOLVED，不产生持续告警 | （若被捕获）CPU、瞬时、毛刺 |

## 3. LLM 层手动回归流程

0. 前置：`./gradlew test`（确定性层必须全绿）；dev 启动应用 + Ollama + Prometheus + Milvus。
1. 记录基线：等待 2 轮 normal 巡检（控制台 INFO 一行摘要或审计日志 `巡检|END|status=normal`）。
2. 按用例表逐个注入，**每轮只注入一类**（避免信号混叠），每类观察至少 3 个巡检周期：
   - NEW 轮：记录控制台 ERROR 全量报告 + 审计 `事件|NEW`；
   - ACTIVE 轮：确认降为 INFO 心跳 + 审计 `事件|ACTIVE`；
   - 恢复轮：F2 用 release、F5 自然恢复，确认审计 `事件|RESOLVED`。
3. 判定：全量报告的根因是否命中用例表"期望根因关键词"列；未命中或误报记入下轮 prompt 优化输入。
4. F4 语义层专项：注入后 2 分钟窗口内的巡检应报 warning 以上并提及延迟——巡检 Prompt 第 6 条已显式要求关联 maxRequestSeconds；若静默说明 prompt 对延迟信号不敏感。

## 4. 度量口径

| 指标 | 定义 | 数据来源 |
|------|------|----------|
| 根因定位准确率 | 命中期望关键词的 NEW 事件数 / NEW 事件总数 | 审计日志 `事件|NEW` + 人工标注 |
| 误报率（尖峰抑制率） | 1 −（单轮 NEW 后即 RESOLVED 且未复发的事件数 / 单轮 NEW 总数） | 审计 `事件|NEW` 与 `事件|RESOLVED` 配对 |
| 漏报率 | BACKSTOP 介入次数 / 巡检总次数（AI 判 normal 被代码强改的比例） | 审计 `巡检|BACKSTOP` / `巡检|END` |
| 平均诊断时长 | LLM 路径巡检 elapsedMs 均值 | 审计 `巡检|END\|status=...\|elapsedMs=` |
| 降级率 | degraded_ 前缀巡检数 / 巡检总数 | `aiops_inspection_total{status=~"degraded_.*"}` / `aiops_inspection_total` |
| 单巡检 Token 消耗 | Ollama 侧统计 | Ollama（`/api/ps`、OLLAMA_DEBUG 日志；应用侧未计量，如实标注） |

## 5. 已知边界（如实标注，避免误判"失败"）

1. **虚拟线程死锁不可观测（JDK 21.0.12 实测）**：`ThreadMXBean.findDeadlockedThreads()` 与 `Thread.getAllStackTraces()`（Micrometer 线程状态指标数据源）均不覆盖虚拟线程——虚拟请求线程上的 synchronized/ReentrantLock 死锁对 blockedThreads 指标与 detectDeadlock() 完全不可见（本注入器最初直接在请求线程上加锁时实测发现，连续多轮巡检误报 normal）。因此死锁注入刻意把锁竞争放在平台 worker 线程上（对应真实系统 @Async/批处理 worker 池形态）。虚拟线程挂起需引入活跃请求数等业务信号识别，已列入白皮书路线图。
2. **空闲即 QPS=0（实测修正）**：actuator 抓取请求**不计入** `http_server_requests`——本机实测空闲应用（仅 Prometheus 抓取）`qpsLast1m=0.0`。因此无业务流量完成时 ZERO_QPS 特征即成立（F1 实测指纹含 `ZERO_QPS;`）；但 LLM 路径下模型对空闲 QPS=0 多判 normal，且 AI 漏报兜底刻意不覆盖 QPS（强改会对空闲系统每轮误报假死）——ZERO_QPS 进指纹发生在模型判异常或降级路径时。真正的全进程假死（连 actuator 都不响应）表现为指标整体变 -1（Prometheus 抓取失败），依赖 LLM 按"-1=查询失败"规则忽略，属于更深的观测边界。
3. **F4 无代码兜底是设计而非缺陷**：纯延迟属于语义信号，硬阈值会产生大量误报（定时任务同样推高 max）——这正是白皮书"语义判断替代静态阈值"要解决的场景。
4. **死锁只能重启恢复**：`/api/debug/reset` 不含死锁（死锁线程无法代码释放）。
5. **LLM 层结果随模型版本波动**：每次更换模型 / prompt 后必须重跑并归档（建议把结果表追加为本文新小节）。
