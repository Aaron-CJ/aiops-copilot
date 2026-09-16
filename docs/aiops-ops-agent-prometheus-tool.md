# 智能巡检 Agent：把 Prometheus 接成 AI 的"时序眼睛"

## Context

当前项目已有被动式运维 Agent（`/api/agent/ops`，用户问才检查）和瞬时本地状态工具（`SystemHealthTools` 拿 CPU/内存瞬时值）。

新引入两个关键能力：

1. **时序数据访问**：Agent 看不到 Prometheus 里的历史趋势，只能拿"当下这一刻"的瞬时值
2. **主动巡检**：没有 `@Scheduled` 调度，故障发生后无人主动发现，全靠用户提问

本次演进贯彻"AIOps = 监控给 AI 看，不给人看"的核心理念：把 Prometheus HTTP API 封装为 Function Tool，再用 `@Scheduled` 每分钟让 qwen3:8b（关闭思考链）主动巡检关键指标、智能判断异常、必要时输出结构化告警报告（控制台日志模拟）。这套替代了传统 Grafana 看图 + Alertmanager 阈值告警的人工链路。

## 决策已对齐

- 告警推送渠道：**控制台 ERROR 级日志输出格式化报告**（不依赖外部 Webhook 凭证，后续可平滑替换为钉钉/飞书）
- 巡检间隔：**1 分钟**
- 巡检 ChatClient：**qwen3:8b**（`disableThinking()` 关闭思考链，直出 JSON 判断结论；不注册工具，指标由调度器预拉）

## 关键架构选择

### PrometheusTool 的双重身份

同一个工具类的查询能力被两条路径复用，避免重复实现：

- **路径 A（被动）**：`@Tool` 注解的 `queryMetric(String promql)` 方法暴露给 `opsAgentClient`，用户提问时模型自主决定查什么 PromQL
- **路径 B（主动）**：巡检调度器直接调用 `PrometheusTool` 的普通 public 方法 `queryFixedMetrics()`，**预拉固定指标塞进 Prompt**，不让模型自己反复试错查（每分钟跑一次，模型自主查可能产生 5-10 次无效工具往返）

这个分工对应"Agent 主动出击"——巡检不是让模型当查询工，而是让模型当"判断工"。

### 新建 opsAgentClient Bean（而非复用 deepseekChatClient）

复用 `deepseekChatClient` 会把巡检专属的 system prompt 和工具 schema 注入到所有对话调用里，污染 `AiChatController`、`ChatController` 等场景的 token 消耗。新建专用 Bean 隔离职责。

## 验证

0. **编译通过**：`.\gradlew.bat compileJava`
1. **首次巡检日志**：启动应用 30 秒后应看到 `[OpsScheduler] 开始巡检...` + `[OpsScheduler] 巡检完成: 正常` INFO 日志
2. **被动 Agent 查 Prometheus**：访问 `http://localhost:8080/api/agent/ops?message=过去1分钟QPS是多少` → 模型应调 `queryMetric` 拿到真实数据后回答
3. **故障触发告警**：并发访问 `http://localhost:8080/api/debug/deadlock/a` 与 `http://localhost:8080/api/debug/deadlock/b`（相反锁序的两个接口，并发即双锁交叉死锁）→ 等待最多 1 分钟巡检 → 应看到 ERROR 级告警报告，含"BLOCKED 线程数: 2"+ 根因分析 + 处置建议
4. **恢复正常验证（人工操作）**：人工重启应用清除死锁（当前系统不含自动修复）→ 下次巡检恢复 INFO 正常日志
