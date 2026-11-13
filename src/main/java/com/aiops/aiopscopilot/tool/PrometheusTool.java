package com.aiops.aiopscopilot.tool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aiops.aiopscopilot.config.MonitoredTarget;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Prometheus 指标查询工具：把 Prometheus HTTP API 暴露给大模型，让 Agent 拥有"时序数据的眼睛"。
 * <p>
 * 三重身份（同一份查询能力被多条路径复用，避免重复实现）：
 * <ul>
 *   <li>被动模式：{@link #queryMetric(String)} 带 {@code @Tool} 注解，模型自主决定查什么 PromQL（用户提问时）</li>
 *   <li>主动模式：{@link #querySnapshot(MonitoredTarget)} 是普通 public 方法，巡检调度器按目标预拉
 *       指标塞 Prompt——多目标下每个目标一次调用（实测，qwen3:8b：模型自主查时在单轮内
 *       并行发起 5-6 次 queryMetric、零 PromQL 重试，但开放式提问维度覆盖不确定；预拉保证每轮
 *       确定性到齐，且巡检 ChatClient 不挂工具 schema、不承担工具编排开销）</li>
 *   <li>目标过滤：主动模式的每条查询带 {@code application="标签值"} 过滤——共享的 Prometheus
 *       会同时抓多个应用，不过滤会发生跨应用串数据（求和混流量、取首样本读错应用）</li>
 * </ul>
 * 这正是"AIOps = 监控给 AI 看"理念的核心落地——Prometheus API 是 Agent 的传感器，不是展示板。
 */
@Component
public class PrometheusTool {

    private final RestClient restClient;
    private final String prometheusBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 被动模式查询缓存条目：结果 + 过期时刻（epoch ms） */
    private record CachedResult(Map<String, Object> result, long expiresAtEpochMs) {
    }

    /**
     * 被动模式（{@link #queryMetric}）的短 TTL 缓存（默认 15s，对齐 Prometheus 抓取间隔）。
     * <p>
     * 动机（2026-11-13）：模型单轮可自主并发发起 5-6 次 queryMetric（白皮书实测），
     * 且多轮提问常重复同一查询——15s 内的重复查询直接命中缓存，省 HTTP 往返也给
     * Prometheus 减压；15s 上界保证 Agent 读到的数据不老于一个抓取周期。
     * 主动模式（{@link #querySnapshot}）不走缓存：巡检 60s 一轮 > TTL，缓存无收益反而添乱。
     * 查询失败（error/-1）同样缓存——故障期缓存天然限流，避免模型重试风暴打挂半死的 Prometheus。
     */
    private final Duration queryCacheTtl;
    /** 缓存条目数上限（防御性）：不同 PromQL 无限增长时先淘汰过期项，仍超限整体清空 */
    private static final int QUERY_CACHE_MAX_ENTRIES = 256;
    private final Map<String, CachedResult> queryCache = new ConcurrentHashMap<>();

    /** 依赖探测超时：连接 2s / 读取 5s。RestClient 默认请求工厂不设超时，Prometheus 挂起时
     *  queryScalar 会永久阻塞，@Scheduled 单线程调度器 + fixedDelay 语义下后续巡检全部静默停摆——
     *  巡检核心循环不允许存在无超时的外部调用（与 HealthCheckController 同一套防线） */
    private static final Duration PROM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PROM_READ_TIMEOUT = Duration.ofSeconds(5);

    /** 快照语义键全集：多目标 metrics 覆盖映射的合法键，未知键在装配期 fail-fast（防配置笔误静默失效） */
    public static final Set<String> SNAPSHOT_SEMANTICS = Set.of(
            "cpuUsage", "heapUsage", "heapMemoryByGen", "qpsLast1m",
            "maxRequestSeconds", "blockedThreads", "gcCountLast5m");

    /** QPS 默认路径的两条子查询的内部 jobId（非语义键，不与快照语义键冲突） */
    private static final String QPS_TOTAL_JOB = "__qpsTotal";
    private static final String QPS_ACTUATOR_JOB = "__qpsActuator";

    /**
     * JVM/Spring 默认指标档：语义 → PromQL 模板，{application} 占位符在采集时替换为目标标签值。
     * 指标名是 Micrometer/JVM 标准，任何 Spring Boot 目标零配置兼容；其它语言目标经
     * aiops.targets.list[].metrics 覆盖映射接入（配什么查询得什么值，语言无关）。
     * qpsLast1m 不在本档（需要两条查询 + Java 侧减法，见 querySnapshot 内的专属处理；
     * 目标也可用 metrics.qpsLast1m 覆盖改写为单条查询）。
     * <p>
     * 各条的关键取舍（均为踩坑后的最终版）：
     * <ul>
     *   <li>maxRequestSeconds 双层聚合：外层 max 跨系列（该指标按 uri 是多条时间序列，取首样本
     *       恒读到 0），内层 max_over_time[2m] 吸收 15s 抓取滞后，窗口与"慢请求推高约维持
     *       2 分钟"的既定语义一致</li>
     *   <li>GC 次数 sum(increase(...)) 跨系列求和：jvm_gc_pause_seconds_count 带
     *       action/cause/gc 标签，Old GC 出现后分裂多系列，取首样本会漏计 FREQUENT_GC 信号</li>
     * </ul>
     */
    private static final Map<String, String> JVM_DEFAULT_PROFILE = buildJvmDefaultProfile();

    static Map<String, String> jvmDefaultProfile() {
        return JVM_DEFAULT_PROFILE;
    }

    private static Map<String, String> buildJvmDefaultProfile() {
        String app = "{application}";
        Map<String, String> m = new LinkedHashMap<>();
        // cpuUsage/blockedThreads 外层 max（2026-11-13 修复）：这两个指标每个实例一条序列，
        // 多副本目标同标签多序列，不带聚合时 queryScalar 只读第一条——副本 B 死锁、副本 A 正常
        // 时 blockedThreads 读到 0，死锁完全不可见。max 语义 = 监控看最坏实例，任一副本异常即告警
        m.put("cpuUsage", "max(process_cpu_usage{application=\"" + app + "\"})");
        m.put("heapUsage", "sum(jvm_memory_used_bytes{area=\"heap\",application=\"" + app + "\"}) / "
                + "sum(jvm_memory_max_bytes{area=\"heap\",application=\"" + app + "\"} > 0)");
        // max by (id)：按代跨副本取最大，输出序列仅剩 id 标签——多副本下同 id 多序列
        // 在 queryHeapMemoryByGen 的 Map 里会互相覆盖（后写胜出 = 随机副本），聚合后消除
        m.put("heapMemoryByGen", "max by (id) (jvm_memory_used_bytes{area=\"heap\",application=\"" + app + "\"})");
        m.put("maxRequestSeconds",
                "max(max_over_time(http_server_requests_seconds_max{application=\"" + app + "\"}[2m]))");
        m.put("blockedThreads", "max(jvm_threads_states_threads{state=\"blocked\",application=\"" + app + "\"})");
        m.put("gcCountLast5m", "sum(increase(jvm_gc_pause_seconds_count{application=\"" + app + "\"}[5m]))");
        return Collections.unmodifiableMap(m);
    }

    @Autowired
    public PrometheusTool(@Value("${aiops.prometheus.base-url:http://localhost:9090}") String baseUrl,
                          @Value("${aiops.prometheus.query-cache-ttl:15s}") Duration queryCacheTtl) {
        this.prometheusBaseUrl = baseUrl;
        this.queryCacheTtl = queryCacheTtl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(PROM_CONNECT_TIMEOUT).build());
        factory.setReadTimeout(PROM_READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 测试便捷入口：默认 15s 查询缓存 TTL（与生产默认一致） */
    PrometheusTool(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(15));
    }

    /**
     * 查询 Prometheus 时序指标（被动模式）。
     * <p>
     * 模型在用户提问时自主调用此工具，传入任意 PromQL 表达式。
     * 结果带 {@code queryCacheTtl}（默认 15s）短 TTL 缓存：TTL 内同一 PromQL 不重复打
     * Prometheus（模型单轮常并发重复查询，见字段注释）；失败结果同样入缓存 = 故障期天然限流。
     *
     * @param promql PromQL 查询表达式，例如 {@code rate(http_server_requests_seconds_count[1m])}
     * @return 结构化结果：包含 promql 原文、采样点列表（labels + timestamp + value）、命中数量；
     *         出错时返回 error 字段，不让模型拿到坏数据产生幻觉
     */
    @Tool(description = "查询 Prometheus 时序指标。传入 PromQL 表达式（如 rate(http_server_requests_seconds_count[1m])），"
            + "返回当前时刻的指标值与标签。当用户询问 QPS、CPU、内存、延迟、线程数等需要历史/时序数据的运维问题时调用此工具。")
    public Map<String, Object> queryMetric(String promql) {
        long now = System.currentTimeMillis();
        CachedResult cached = queryCache.get(promql);
        if (cached != null && now < cached.expiresAtEpochMs()) {
            return cached.result();
        }
        Map<String, Object> result = queryMetricUncached(promql);
        queryCache.put(promql, new CachedResult(result, now + queryCacheTtl.toMillis()));
        evictQueryCache(now);
        return result;
    }

    /** {@link #queryMetric} 的无缓存主体（原实现原样搬入） */
    private Map<String, Object> queryMetricUncached(String promql) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promql", promql);
        try {
            JsonNode dataResult = queryRaw(promql);
            List<Map<String, Object>> samples = new ArrayList<>();
            if (dataResult.isArray()) {
                for (JsonNode item : dataResult) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("labels", objectMapper.convertValue(item.path("metric"), Map.class));
                    JsonNode value = item.path("value");
                    if (value.isArray() && value.size() >= 2) {
                        sample.put("timestamp", value.get(0).asDouble());
                        sample.put("value", value.get(1).asString());
                    }
                    samples.add(sample);
                }
            }
            result.put("samples", samples);
            result.put("count", samples.size());
        } catch (Exception e) {
            // 工具调用失败也要给模型一个明确信号，避免它凭空编造数值
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 缓存条目超限时先淘汰过期项，仍超限整体清空（兜底，防不同 PromQL 无限增长） */
    private void evictQueryCache(long now) {
        if (queryCache.size() <= QUERY_CACHE_MAX_ENTRIES) {
            return;
        }
        queryCache.entrySet().removeIf(e -> now >= e.getValue().expiresAtEpochMs());
        if (queryCache.size() > QUERY_CACHE_MAX_ENTRIES) {
            queryCache.clear();
        }
    }

    /**
     * 主动模式：采集单个目标的指标快照（语义键 → 数值，-1 = 查询失败/无数据）。
     * 每条查询带 application 标签过滤（跨应用隔离的关键——共享 Prometheus 会同时抓
     * 多个应用，不过滤会串数据）。目标的 metrics 覆盖优先于 JVM 默认档：
     * 非空覆盖原样使用（过滤由配置者负责），空白覆盖 = 该目标无此语义 → -1 哨兵；
     * qpsLast1m 也支持覆盖（配了单条查询就跳过 Java 侧减法，语义与其它键一致）。
     * <p>
     * <b>查询并行（2026-11-13 优化）</b>：一个目标的 8 条 PromQL 彼此无依赖，此前串行执行
     * 意味着最坏耗时 = 8 × (连接 2s + 读取 5s) ≈ 56s（黑洞地址实测 40s）——采集阶段就吃掉了
     * 整个 60s 巡检周期，LLM 调用被挤到周期之外。改为并行后墙钟 = 最慢的一条查询
     * （≈5-7s），采集阶段回到巡检预算内，LLM 的 45s 预算不再被前置开销挤占。
     * <p>
     * 并行度：每目标 ≤8 条（虚拟线程，一条查询一个）；多目标时各目标本就在
     * {@link com.aiops.aiopscopilot.service.SnapshotCollector} 里并行采集，
     * 总量 = 8 × 目标数，Prometheus 侧自行排队（其默认 query.max-concurrency=20）。
     * RestClient / JDK HttpClient / Jackson ObjectMapper 均线程安全，可安全并发调用。
     */
    public Map<String, Object> querySnapshot(MonitoredTarget target) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryTimestamp", LocalDateTime.now().toString());

        // ① 计划阶段（纯计算，无 IO）：语义键 → PromQL。显式留空的覆盖 = 无此语义，不进计划
        Map<String, String> plan = new LinkedHashMap<>();
        for (Map.Entry<String, String> semantic : JVM_DEFAULT_PROFILE.entrySet()) {
            String key = semantic.getKey();
            String override = target.metrics().get(key);
            if (override != null && override.isBlank()) {
                continue;
            }
            plan.put(key, resolvePromql(override, semantic.getValue(), target.application()));
        }

        // QPS 三态：null = 用默认路径（总速率 - actuator 基线）、空白 = 该目标无此语义、非空 = 目标自定义单查询
        String qpsOverride = target.metrics().get("qpsLast1m");
        if (qpsOverride != null && !qpsOverride.isBlank()) {
            plan.put("qpsLast1m", resolvePromql(qpsOverride, "", target.application()));
        }

        // ② 查询计划：默认 QPS 拆成两条独立子查询并行发起，最后在 Java 侧相减
        List<QueryJob> jobs = new ArrayList<>();
        for (Map.Entry<String, String> e : plan.entrySet()) {
            jobs.add(new QueryJob(e.getKey(), e.getValue(), "heapMemoryByGen".equals(e.getKey())));
        }
        if (qpsOverride == null) {
            String app = target.application();
            jobs.add(new QueryJob(QPS_TOTAL_JOB,
                    "sum(rate(http_server_requests_seconds_count{application=\"" + app + "\"}[1m]))", false));
            // 基线侧同样 sum 聚合：rate() 每副本一条序列，不聚合时减法用的是
            // "随机一个副本"的基线，多副本下总-基线口径错位（副本数 × 0.067 的偏差）
            jobs.add(new QueryJob(QPS_ACTUATOR_JOB,
                    "sum(rate(http_server_requests_seconds_count{application=\"" + app + "\",uri=\"/actuator/prometheus\"}[1m]))", false));
        }

        // ③ 并行执行全部查询 → ④ 组装快照
        Map<String, Object> values = runJobs(jobs);
        for (String key : JVM_DEFAULT_PROFILE.keySet()) {
            // 没进计划 = 显式留空覆盖（该目标无此语义）→ -1 哨兵
            snapshot.put(key, values.containsKey(key) ? values.get(key) : -1.0);
        }
        if (qpsOverride == null) {
            // QPS = 总速率 - actuator 抓取基线，减法在 Java 侧完成而非 PromQL（实测踩坑）：
            // sum() 产出无标签向量，rate() 产出带标签向量，PromQL 算术逐标签匹配不相等 →
            // 相减得空集（多实例下还是 many-to-many 直接报错）。任何一侧查询失败（-1）→
            // 整体 -1 哨兵，AI 按"查询失败"忽略。
            snapshot.put("qpsLast1m", qpsFrom(
                    asDouble(values.get(QPS_TOTAL_JOB)), asDouble(values.get(QPS_ACTUATOR_JOB))));
        } else if (qpsOverride.isBlank()) {
            snapshot.put("qpsLast1m", -1.0);
        } else {
            snapshot.put("qpsLast1m", values.get("qpsLast1m"));
        }
        return snapshot;
    }

    /**
     * 并行执行查询计划：每条查询一个虚拟线程，墙钟耗时 = 最慢的一条而非全部之和。
     * <p>
     * 查询方法自身已吞掉常规异常并返回 -1 哨兵；走到 catch 说明是 Error 级异常
     * （如 OOM），此时该语义键同样按"查询失败"记 -1，不让单个查询拖垮整轮快照。
     *
     * @param jobs 查询计划（顺序即结果回填顺序）
     * @return jobId → 查询结果
     */
    private Map<String, Object> runJobs(List<QueryJob> jobs) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (jobs.isEmpty()) {
            return values;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>(jobs.size());
            for (QueryJob job : jobs) {
                futures.add(pool.submit(() -> job.heapByGen()
                        ? queryHeapMemoryByGen(job.promql())
                        : queryScalar(job.promql())));
            }
            for (int i = 0; i < jobs.size(); i++) {
                Object value;
                try {
                    value = futures.get(i).get();
                } catch (Exception e) {
                    value = -1.0;
                }
                values.put(jobs.get(i).jobId(), value);
            }
        } // close() 等待全部查询完成
        return values;
    }

    /** 一次指标查询作业：结果回填键 + PromQL + 是否按代取堆明细 */
    private record QueryJob(String jobId, String promql, boolean heapByGen) {
    }

    /** 把指标值安全转为 double；非数字或 null 返回 -1（与查询失败的哨兵值一致） */
    private static double asDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : -1;
    }

    /**
     * Java 侧 QPS 减法的纯函数形态：总速率 - actuator 基线，结果钳到 ≥0。
     * 任一侧查询失败（-1 哨兵）→ 整体 -1（AI 按"查询失败"忽略，而非误读为 0 流量）。
     */
    static double qpsFrom(double totalQps, double actuatorQps) {
        if (totalQps < 0) {
            return -1;
        }
        return Math.max(0, totalQps - Math.max(0, actuatorQps));
    }

    /**
     * 语义 → 最终 PromQL 的解析：目标 metrics 覆盖优先于 JVM 默认档，
     * {application} 占位符统一替换为目标标签值（覆盖查询里也可使用该占位符）。
     */
    static String resolvePromql(String override, String defaultTemplate, String application) {
        String template = override != null && !override.isBlank() ? override : defaultTemplate;
        return template.replace("{application}", application);
    }

    /** 单值查询：返回第一个采样点的数值，失败返回 -1（让模型知道"无数据"而非 0） */
    private double queryScalar(String promql) {
        try {
            JsonNode dataResult = queryRaw(promql);
            if (dataResult.isArray() && !dataResult.isEmpty()) {
                JsonNode value = dataResult.get(0).path("value");
                if (value.isArray() && value.size() >= 2) {
                    return parseValue(value.get(1).asString());
                }
            }
        } catch (Exception ignored) {
            // 静默吞掉，返回 -1 即可——巡检不应因单条指标失败而中断
        }
        return -1;
    }

    /** 堆内存按代分组查询：返回 {代名: 已用字节}（Eden/Survivor/Old），作为 Prompt 的分代明细 */
    private Map<String, Double> queryHeapMemoryByGen(String promql) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            JsonNode dataResult = queryRaw(promql);
            if (dataResult.isArray()) {
                for (JsonNode item : dataResult) {
                    String gen = item.path("metric").path("id").asString("unknown");
                    JsonNode value = item.path("value");
                    if (value.isArray() && value.size() >= 2) {
                        result.put(gen, parseValue(value.get(1).asString()));
                    }
                }
            }
        } catch (Exception ignored) {
            // 同 queryScalar：分代明细是辅助维度，缺失不应中断整轮快照
        }
        return result;
    }

    /**
     * 调用 Prometheus /api/v1/query 并返回 data.result 节点。
     * <p>
     * 编码方案（踩过两次坑后的最终版）：
     * 1) 坑一：手动 URLEncoder.encode 后拼进 uriBuilder 字符串 —— RestClient 会对 % 再次编码（% → %25），
     *    全部查询失败，只有纯字母指标名侥幸通过；
     * 2) 坑二：改用 uriBuilder.queryParam(...) 依赖 Spring 自动编码 —— 双引号（PromQL label 过滤必备，
     *    如 {state="blocked"}、{area="heap"}）不被正确编码，请求发坏，而
     *    不带引号的 rate(...[1m]) 反而成功——症状极具迷惑性；
     * 3) 最终方案：URLEncoder.encode 编码一次 + 传 java.net.URI 绝对地址，
     *    RestClient 对 URI 对象不做任何再编码，发出去的就是编码好的最终形态。
     * <p>
     * URLEncoder 会把空格编成 +，query 参数的标准解码规则会把 + 还原为空格，对 PromQL 语法无影响。
     */
    private JsonNode queryRaw(String promql) {
        String url = prometheusBaseUrl + "/api/v1/query?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8);
        String body = restClient.get()
                .uri(URI.create(url))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body).path("data").path("result");
    }

    /** 解析 Prometheus 数值字符串，兼容科学计数法（如 7.5497472E7）和 NaN */
    private double parseValue(String s) {
        if (s == null || s.isEmpty() || "NaN".equalsIgnoreCase(s)) {
            return -1;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
