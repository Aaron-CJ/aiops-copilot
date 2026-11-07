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

import org.springframework.ai.tool.annotation.Tool;
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

    /** 依赖探测超时：连接 2s / 读取 5s。RestClient 默认请求工厂不设超时，Prometheus 挂起时
     *  queryScalar 会永久阻塞，@Scheduled 单线程调度器 + fixedDelay 语义下后续巡检全部静默停摆——
     *  巡检核心循环不允许存在无超时的外部调用（与 HealthCheckController 同一套防线） */
    private static final Duration PROM_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PROM_READ_TIMEOUT = Duration.ofSeconds(5);

    /** 快照语义键全集：多目标 metrics 覆盖映射的合法键，未知键在装配期 fail-fast（防配置笔误静默失效） */
    public static final Set<String> SNAPSHOT_SEMANTICS = Set.of(
            "cpuUsage", "heapUsage", "heapMemoryByGen", "qpsLast1m",
            "maxRequestSeconds", "blockedThreads", "gcCountLast5m");

    /**
     * JVM/Spring 默认指标档：语义 → PromQL 模板，{application} 占位符在采集时替换为目标标签值。
     * 指标名是 Micrometer/JVM 标准，任何 Spring Boot 目标零配置兼容；其它语言目标经
     * aiops.targets.list[].metrics 覆盖映射接入（配什么查询得什么值，语言无关）。
     * qpsLast1m 不在本档（需要两条查询 + Java 侧减法，见 querySnapshot 内的专属处理）。
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
        m.put("cpuUsage", "process_cpu_usage{application=\"" + app + "\"}");
        m.put("heapUsage", "sum(jvm_memory_used_bytes{area=\"heap\",application=\"" + app + "\"}) / "
                + "sum(jvm_memory_max_bytes{area=\"heap\",application=\"" + app + "\"} > 0)");
        m.put("heapMemoryByGen", "jvm_memory_used_bytes{area=\"heap\",application=\"" + app + "\"}");
        m.put("maxRequestSeconds",
                "max(max_over_time(http_server_requests_seconds_max{application=\"" + app + "\"}[2m]))");
        m.put("blockedThreads", "jvm_threads_states_threads{state=\"blocked\",application=\"" + app + "\"}");
        m.put("gcCountLast5m", "sum(increase(jvm_gc_pause_seconds_count{application=\"" + app + "\"}[5m]))");
        return Collections.unmodifiableMap(m);
    }

    public PrometheusTool(@Value("${aiops.prometheus.base-url:http://localhost:9090}") String baseUrl) {
        this.prometheusBaseUrl = baseUrl;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(PROM_CONNECT_TIMEOUT).build());
        factory.setReadTimeout(PROM_READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 查询 Prometheus 时序指标（被动模式）。
     * <p>
     * 模型在用户提问时自主调用此工具，传入任意 PromQL 表达式。
     *
     * @param promql PromQL 查询表达式，例如 {@code rate(http_server_requests_seconds_count[1m])}
     * @return 结构化结果：包含 promql 原文、采样点列表（labels + timestamp + value）、命中数量；
     *         出错时返回 error 字段，不让模型拿到坏数据产生幻觉
     */
    @Tool(description = "查询 Prometheus 时序指标。传入 PromQL 表达式（如 rate(http_server_requests_seconds_count[1m])），"
            + "返回当前时刻的指标值与标签。当用户询问 QPS、CPU、内存、延迟、线程数等需要历史/时序数据的运维问题时调用此工具。")
    public Map<String, Object> queryMetric(String promql) {
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

    /**
     * 主动模式：采集单个目标的指标快照（语义键 → 数值，-1 = 查询失败/无数据）。
     * 每条查询带 application 标签过滤（跨应用隔离的关键——共享 Prometheus 会同时抓
     * 多个应用，不过滤会串数据）。目标的 metrics 覆盖优先于 JVM 默认档：
     * 非空覆盖原样使用（过滤由配置者负责），空白覆盖 = 该目标无此语义 → -1 哨兵。
     */
    public Map<String, Object> querySnapshot(MonitoredTarget target) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryTimestamp", LocalDateTime.now().toString());
        for (Map.Entry<String, String> semantic : JVM_DEFAULT_PROFILE.entrySet()) {
            String key = semantic.getKey();
            String override = target.metrics().get(key);
            if (override != null && override.isBlank()) {
                // 显式留空 = 目标声明"没有这个语义"，直接记哨兵，不发查询
                snapshot.put(key, -1.0);
                continue;
            }
            String promql = resolvePromql(override, semantic.getValue(), target.application());
            snapshot.put(key, "heapMemoryByGen".equals(key)
                    ? queryHeapMemoryByGen(promql)
                    : queryScalar(promql));
        }
        // QPS = 总速率 - actuator 抓取基线，减法在 Java 侧完成而非 PromQL（实测踩坑）：
        // sum() 产出无标签向量，rate() 产出带标签向量，PromQL 算术逐标签匹配不相等 →
        // 相减得空集（多实例下还是 many-to-many 直接报错）。任何一侧查询失败（-1）→
        // 整体 -1 哨兵，AI 按"查询失败"忽略。
        String app = target.application();
        double totalQps = queryScalar("sum(rate(http_server_requests_seconds_count{application=\""
                + app + "\"}[1m]))");
        double actuatorQps = queryScalar("rate(http_server_requests_seconds_count{application=\""
                + app + "\",uri=\"/actuator/prometheus\"}[1m])");
        snapshot.put("qpsLast1m", qpsFrom(totalQps, actuatorQps));
        return snapshot;
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
