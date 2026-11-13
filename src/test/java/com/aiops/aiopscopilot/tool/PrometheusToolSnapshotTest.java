package com.aiops.aiopscopilot.tool;

import com.aiops.aiopscopilot.config.MonitoredTarget;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PrometheusTool#querySnapshot(MonitoredTarget)} 的行为测试：
 * 用 JDK 内置的 {@link HttpServer} 起一个本地假 Prometheus，不引新依赖、不需要真实基础设施。
 * <p>
 * 覆盖三件事：
 * <ol>
 *   <li><b>口径正确</b>：7 条语义键全部回填，QPS = 总速率 - actuator 基线（Java 侧减法），
 *       显式留空的覆盖语义记 -1 哨兵</li>
 *   <li><b>编码路径未退化</b>：假服务按 URL 解码后断言收到的 PromQL——
 *       {@code queryRaw} 的"URLEncoder 编码一次 + 传 java.net.URI 绝对地址"方案
 *       一旦被改成 RestClient 二次编码，这里会立刻红（项目踩过两次编码坑）</li>
 *   <li><b>并行生效</b>：每条查询注入固定延迟，串行耗时 ≈ 8×延迟、并行 ≈ 1×延迟，
 *       用墙钟断言把"查询并行"这个优化钉死，防止将来被改回串行</li>
 * </ol>
 */
class PrometheusToolSnapshotTest {

    /** 每条查询注入的固定延迟（毫秒）——串行 8×200=1600ms，并行 ≈200ms */
    private static final long QUERY_DELAY_MILLIS = 200;

    /** 墙钟上界：并行应远小于串行总耗时；留足余量避免 CI 抖动误红 */
    private static final long PARALLEL_BUDGET_MILLIS = 1200;

    private HttpServer server;
    private String baseUrl;
    /** 收到的 PromQL 原文（按到达顺序）——并发下顺序不固定，用集合断言 */
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", this::handleQuery);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 假 Prometheus：按查询内容返回不同的固定值 */
    private void handleQuery(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String promql = decodeQueryParam(exchange.getRequestURI().getRawQuery());
        receivedQueries.add(promql);
        try {
            Thread.sleep(QUERY_DELAY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = prometheusResponse(promql).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** 从 raw query 里解出 "query" 参数（URI 解码一次即得 PromQL 原文） */
    private static String decodeQueryParam(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "query".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    /** 按 PromQL 内容返回对应的 Prometheus 标准响应体 */
    private static String prometheusResponse(String promql) {
        if (promql.contains("jvm_memory_max_bytes")) {
            // 堆使用率（sum/max 比值）
            return vector("0.55");
        }
        if (promql.contains("jvm_memory_used_bytes")) {
            // 堆分代明细：多条序列，按 id 分代
            return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                    + "{\"metric\":{\"id\":\"PS Old Gen\"},\"value\":[1700000000,\"100.0\"]},"
                    + "{\"metric\":{\"id\":\"PS Eden Space\"},\"value\":[1700000000,\"20.0\"]}]}}";
        }
        if (promql.contains("process_cpu_usage")) {
            return vector("0.42");
        }
        if (promql.contains("max_over_time")) {
            return vector("1.25");
        }
        if (promql.contains("jvm_threads_states_threads")) {
            return vector("0.0");
        }
        if (promql.contains("increase(jvm_gc_pause_seconds_count")) {
            return vector("3.0");
        }
        if (promql.contains("actuator/prometheus")) {
            return vector("0.067");
        }
        if (promql.contains("http_requests_total")) {
            // 非 JVM 目标的自定义 QPS 表达式（与内置的 http_server_requests_* 区分开）
            return vector("7.5");
        }
        if (promql.contains("http_server_requests_seconds_count")) {
            return vector("10.067");
        }
        return vector("0.0");
    }

    private static String vector(String value) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"__name__\":\"x\"},\"value\":[1700000000,\"" + value + "\"]}]}}";
    }

    // ==================== 用例 ====================

    /** 默认档：7 条语义键全部回填；QPS = 总速率(10.067) - actuator 基线(0.067) = 10.0 */
    @Test
    void defaultProfileFillsAllSemanticsAndSubtractsActuatorBaseline() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        Map<String, Object> snapshot = tool.querySnapshot(
                new MonitoredTarget("self", "aiops-copilot", null));

        assertEquals(0.42, (Double) snapshot.get("cpuUsage"), 1e-9);
        assertEquals(0.55, (Double) snapshot.get("heapUsage"), 1e-9);
        assertEquals(1.25, (Double) snapshot.get("maxRequestSeconds"), 1e-9);
        assertEquals(0.0, (Double) snapshot.get("blockedThreads"), 1e-9);
        assertEquals(3.0, (Double) snapshot.get("gcCountLast5m"), 1e-9);
        assertEquals(10.0, (Double) snapshot.get("qpsLast1m"), 1e-9, "QPS = 总速率 - actuator 基线");

        @SuppressWarnings("unchecked")
        Map<String, Double> byGen = (Map<String, Double>) snapshot.get("heapMemoryByGen");
        assertNotNull(byGen, "堆分代明细应存在");
        assertEquals(100.0, byGen.get("PS Old Gen"), 1e-9);
        assertEquals(20.0, byGen.get("PS Eden Space"), 1e-9);
    }

    /** 每条查询都带 application 标签过滤（共享 Prometheus 不串数据的前提） */
    @Test
    void everyQueryCarriesApplicationLabelFilter() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        tool.querySnapshot(new MonitoredTarget("self", "order-service", null));

        assertFalse(receivedQueries.isEmpty(), "应发出查询");
        for (String q : receivedQueries) {
            assertTrue(q.contains("application=\"order-service\""),
                    "查询缺少 application 标签过滤: " + q);
        }
    }

    /**
     * 跨实例聚合（2026-11-13）：多副本目标同标签多序列，cpuUsage/blockedThreads 必须
     * max 聚合（副本 B 死锁、副本 A 正常时不聚合会读到 0，死锁完全不可见）、
     * 堆分代 max by (id)、actuator 基线 sum(rate(...))——基线不聚合时 QPS 减法
     * 用的是"随机一个副本"的基线，多副本下口径错位。
     */
    @Test
    void perInstanceMetricsAreAggregatedAcrossReplicas() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        tool.querySnapshot(new MonitoredTarget("self", "aiops-copilot", null));

        assertTrue(receivedQueries.contains(
                        "max(process_cpu_usage{application=\"aiops-copilot\"})"),
                "CPU 必须跨副本 max: " + receivedQueries);
        assertTrue(receivedQueries.stream().anyMatch(q ->
                        q.startsWith("max(jvm_threads_states_threads{state=\"blocked\",")),
                "BLOCKED 必须跨副本 max（死锁可见性的关键）: " + receivedQueries);
        assertTrue(receivedQueries.stream().anyMatch(q ->
                        q.startsWith("max by (id) (jvm_memory_used_bytes{")),
                "堆分代必须 max by (id)（多副本同 id 序列不再互相覆盖）: " + receivedQueries);
        assertTrue(receivedQueries.stream().anyMatch(q ->
                        q.startsWith("sum(rate(http_server_requests_seconds_count{")
                                && q.contains("uri=\"/actuator/prometheus\"}")),
                "actuator 基线必须 sum 聚合: " + receivedQueries);
    }

    /** 编码路径未退化：收到的必须是可被 Prometheus 解析的 PromQL 原文 */
    @Test
    void promqlIsReceivedDecodedCorrectly() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        tool.querySnapshot(new MonitoredTarget("self", "aiops-copilot", null));

        assertTrue(receivedQueries.stream()
                        .anyMatch(q -> q.contains("process_cpu_usage{application=\"aiops-copilot\"}")),
                "CPU 查询原文不符（编码被二次处理？）: " + receivedQueries);
        assertTrue(receivedQueries.stream()
                        .anyMatch(q -> q.contains("state=\"blocked\"")),
                "BLOCKED 查询应保留双引号标签值: " + receivedQueries);
        assertTrue(receivedQueries.stream()
                        .anyMatch(q -> q.contains("uri=\"/actuator/prometheus\"")),
                "actuator 基线查询应保留双引号标签值: " + receivedQueries);
    }

    /** 显式留空的覆盖语义 = 该目标无此语义 → -1 哨兵，且不发对应查询 */
    @Test
    void blankOverrideMeansNoSuchSemanticAndIssuesNoQuery() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        Map<String, Object> snapshot = tool.querySnapshot(new MonitoredTarget("pay", "payment-gateway",
                Map.of("blockedThreads", "", "gcCountLast5m", "")));

        assertEquals(-1.0, (Double) snapshot.get("blockedThreads"), 1e-9);
        assertEquals(-1.0, (Double) snapshot.get("gcCountLast5m"), 1e-9);
        assertFalse(receivedQueries.stream().anyMatch(q -> q.contains("jvm_threads_states_threads")),
                "留空语义不应发查询: " + receivedQueries);
        assertFalse(receivedQueries.stream().anyMatch(q -> q.contains("jvm_gc_pause_seconds_count")),
                "留空语义不应发查询: " + receivedQueries);
    }

    /** 目标自定义 QPS 表达式：单条查询，跳过内置的 Java 侧减法 */
    @Test
    void qpsOverrideUsesSingleQueryAndSkipsSubtraction() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        Map<String, Object> snapshot = tool.querySnapshot(new MonitoredTarget("pay", "payment-gateway",
                Map.of("qpsLast1m", "sum(rate(http_requests_total{app=\"pay\"}[1m]))")));

        assertEquals(7.5, (Double) snapshot.get("qpsLast1m"), 1e-9, "自定义 QPS 直接取查询值，不做减法");
        assertTrue(receivedQueries.contains("sum(rate(http_requests_total{app=\"pay\"}[1m]))"),
                "应发出目标自定义的查询: " + receivedQueries);
    }

    /**
     * 查询并行生效：8 条查询各注入 200ms 延迟，串行需 ≈1.6s、并行只需 ≈0.2s。
     * <p>
     * 这条断言把"2026-11-13 查询并行"这个优化钉死——改回串行会立刻红。
     */
    @Test
    void queriesRunInParallel() {
        PrometheusTool tool = new PrometheusTool(baseUrl);
        long start = System.currentTimeMillis();
        tool.querySnapshot(new MonitoredTarget("self", "aiops-copilot", null));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(8, requestCount.get(), "默认档应发出 8 条查询（6 语义 + 2 条 QPS 子查询）");
        assertTrue(elapsed < PARALLEL_BUDGET_MILLIS,
                "8 条查询串行需 ≈" + (8 * QUERY_DELAY_MILLIS) + "ms，并行应 ≈" + QUERY_DELAY_MILLIS
                        + "ms；实际 " + elapsed + "ms（查询可能被改回串行了？）");
    }

    // ==================== 被动模式查询缓存 ====================

    /**
     * TTL 内同一 PromQL 不重复打 Prometheus（2026-11-13）：模型单轮可并发发起
     * 5-6 次相同/相似查询，15s 缓存对齐抓取间隔，省往返也给 Prometheus 减压。
     */
    @Test
    void repeatedQueryWithinTtlHitsCache() {
        PrometheusTool tool = new PrometheusTool(baseUrl, java.time.Duration.ofSeconds(15));
        Map<String, Object> first = tool.queryMetric("process_cpu_usage{application=\"aiops-copilot\"}");
        Map<String, Object> second = tool.queryMetric("process_cpu_usage{application=\"aiops-copilot\"}");

        assertEquals(1, requestCount.get(), "TTL 内重复查询必须命中缓存，只发一次 HTTP");
        assertEquals(first, second, "缓存命中的结果应与首次一致");
        assertEquals(1, ((Number) first.get("count")).intValue());
    }

    /** TTL 过期后重新发起查询；不同 PromQL 各自独立（互不命中） */
    @Test
    void expiredTtlQueriesAgainAndDifferentPromqlIsIndependent() {
        PrometheusTool tool = new PrometheusTool(baseUrl, java.time.Duration.ofMillis(50));
        tool.queryMetric("process_cpu_usage{application=\"aiops-copilot\"}");
        try {
            Thread.sleep(120); // 越过 50ms TTL
        } catch (InterruptedException ignored) {
        }
        tool.queryMetric("process_cpu_usage{application=\"aiops-copilot\"}"); // 过期 → 重发
        tool.queryMetric("jvm_threads_states_threads{state=\"blocked\"}");   // 不同 key → 必发

        assertEquals(3, requestCount.get(), "过期重发 + 不同 PromQL 独立，共 3 次 HTTP");
    }
}
