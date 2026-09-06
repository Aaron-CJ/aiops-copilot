package com.aiops.aiopscopilot.tool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Prometheus 指标查询工具：把 Prometheus HTTP API 暴露给大模型，让 Agent 拥有"时序数据的眼睛"。
 * <p>
 * 双重身份（同一份查询能力被两条路径复用，避免重复实现）：
 * <ul>
 *   <li>被动模式：{@link #queryMetric(String)} 带 {@code @Tool} 注解，模型自主决定查什么 PromQL（用户提问时）</li>
 *   <li>主动模式：{@link #queryFixedMetrics()} 是普通 public 方法，巡检调度器预拉 6 条核心指标塞 Prompt，
 *       不让模型反复试错查（每分钟跑一次，模型自主查会浪费 5-10 次 token）</li>
 * </ul>
 * 这正是"AIOps = 监控给 AI 看"理念的核心落地——Prometheus API 是 Agent 的传感器，不是展示板。
 */
@Component
public class PrometheusTool {

    private final RestClient restClient;
    private final String prometheusBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrometheusTool(@Value("${aiops.prometheus.base-url:http://localhost:9090}") String baseUrl) {
        this.prometheusBaseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
                        sample.put("value", value.get(1).asText());
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
     * 巡检调度器专用：一次性批量查询 6 条核心指标返回结构化快照。
     * <p>
     * 设计意图：巡检每分钟跑一次，让模型通过 Function Calling 自主查 6 个指标
     * 会产生 5-10 次工具调用往返、消耗大量 token 且时延高。改为调度器预拉数据塞 Prompt，
     * 模型只负责"判断"——这正是用户描述的"让模型当判断工，不是查询工"。
     */
    public Map<String, Object> queryFixedMetrics() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryTimestamp", LocalDateTime.now().toString());
        snapshot.put("cpuUsage", queryScalar("process_cpu_usage"));
        snapshot.put("heapMemoryByGen", querySeriesByLabel(
                "jvm_memory_used_bytes{area=\"heap\"}", "id"));
        snapshot.put("qpsLast1m", queryScalar(
                "rate(http_server_requests_seconds_count[1m])"));
        snapshot.put("maxRequestSeconds", queryScalar("http_server_requests_seconds_max"));
        snapshot.put("blockedThreads", queryScalar(
                "jvm_threads_states_threads{state=\"blocked\"}"));
        snapshot.put("gcCountLast5m", queryScalar(
                "increase(jvm_gc_pause_seconds_count[5m])"));
        return snapshot;
    }

    /** 单值查询：返回第一个采样点的数值，失败返回 -1（让模型知道"无数据"而非 0） */
    private double queryScalar(String promql) {
        try {
            JsonNode dataResult = queryRaw(promql);
            if (dataResult.isArray() && !dataResult.isEmpty()) {
                JsonNode value = dataResult.get(0).path("value");
                if (value.isArray() && value.size() >= 2) {
                    return parseValue(value.get(1).asText());
                }
            }
        } catch (Exception ignored) {
            // 静默吞掉，返回 -1 即可——巡检不应因单条指标失败而中断
        }
        return -1;
    }

    /** 按标签分组查询：返回 {labelValue: value} 映射，用于堆内存分代等多维指标 */
    private Map<String, Double> querySeriesByLabel(String promql, String labelKey) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            JsonNode dataResult = queryRaw(promql);
            if (dataResult.isArray()) {
                for (JsonNode item : dataResult) {
                    String labelValue = item.path("metric").path(labelKey).asText("unknown");
                    JsonNode value = item.path("value");
                    if (value.isArray() && value.size() >= 2) {
                        result.put(labelValue, parseValue(value.get(1).asText()));
                    }
                }
            }
        } catch (Exception ignored) {
            // 静默吞掉
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
     *    如 {state="blocked"}、{area="heap"}）不被正确编码，请求发坏，heap/blocked 类查询全挂，
     *    而不带引号的 rate(...[1m]) 反而成功——症状极具迷惑性；
     * 3) 最终方案：URLEncoder.encode 编码一次 + 传 java.net.URI 绝对地址，
     *    RestClient 对 URI 对象不做任何再编码，发出去的就是编码好的最终形态。
     * <p>
     * URLEncoder 会把空格编成 +，query 参数的标准解码规则会把 + 还原为空格，对 PromQL 语法无影响。
     */
    private JsonNode queryRaw(String promql) throws Exception {
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
