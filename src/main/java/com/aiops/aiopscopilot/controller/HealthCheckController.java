package com.aiops.aiopscopilot.controller;

import java.io.File;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aiops.aiopscopilot.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 生产环境健康检查：一键巡检所有依赖服务的连通性与资源水位。
 * <p>
 * 检查维度：
 * <ol>
 *   <li>Ollama（LLM 推理服务）——GET /api/tags，并校验快通道（巡检/交互）、深度诊断、embedding
 *       三个模型已拉取
 *       （进程活着但模型缺失时巡检会全走降级，部署自检必须暴露，报 WARN）</li>
 *   <li>Milvus（向量数据库）——GET /healthz（9091 端口）</li>
 *   <li>Prometheus（指标采集）——GET /-/healthy</li>
 *   <li>JVM 堆内存——Runtime.freeMemory / maxMemory</li>
 *   <li>磁盘空间——File.usableSpace</li>
 * </ol>
 * 每项返回 UP / DOWN / WARN + 详情，调用方据此判断环境是否就绪。
 */
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    /** Milvus standalone 的健康检查端口固定为 9091（与 gRPC 数据端口 19530 不同） */
    private static final int MILVUS_HEALTH_PORT = 9091;

    /** 依赖连通性探测超时：连接 2s / 读取 3s——健康检查不能因单个依赖挂起而永久阻塞请求 */
    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String ollamaBaseUrl;
    private final String milvusHealthUrl;
    private final String prometheusBaseUrl;
    /** 巡检/交互（fast）、深度诊断（chat）、RAG（embedding）依赖的三个模型，缺失即全线降级 */
    private final List<String> requiredModels;

    public HealthCheckController(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.vectorstore.milvus.client.host:localhost}") String milvusHost,
            @Value("${aiops.prometheus.base-url:http://localhost:9090}") String prometheusBaseUrl,
            @Value("${aiops.models.fast}") String fastModel,
            @Value("${spring.ai.ollama.chat.model}") String chatModel,
            @Value("${spring.ai.ollama.embedding.model}") String embeddingModel) {
        // 显式超时：RestClient 默认请求工厂不设连接/读取超时，依赖挂起会拖死健康检查本身
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(PROBE_CONNECT_TIMEOUT).build());
        factory.setReadTimeout(PROBE_READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.ollamaBaseUrl = ollamaBaseUrl;
        // Milvus standalone 的 9091 健康端口只提供明文 HTTP（无 TLS），按实际协议拼接，勿改成 https
        this.milvusHealthUrl = "http://" + milvusHost + ":" + MILVUS_HEALTH_PORT;
        this.prometheusBaseUrl = prometheusBaseUrl;
        this.requiredModels = List.of(fastModel, chatModel, embeddingModel);
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> check() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ollama", checkOllama());
        result.put("milvus", checkMilvus());
        result.put("prometheus", checkPrometheus());
        result.put("jvmMemory", checkJvmMemory());
        result.put("diskSpace", checkDiskSpace());

        return Result.success(result);
    }

    private Map<String, Object> checkOllama() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            String body = restClient.get().uri(ollamaBaseUrl + "/api/tags").retrieve().body(String.class);
            // 响应体只解析一次复用（此前每个模型各 readTree 一遍，2026-11-14 审查微优化）
            List<String> modelNames = parseModelNames(body);
            List<String> missing = requiredModels.stream()
                    .filter(model -> !modelPresent(modelNames, model))
                    .toList();
            if (missing.isEmpty()) {
                info.put("status", "UP");
            } else {
                // 进程活着但模型缺失：巡检/深度诊断/RAG 全走降级或失败——部署自检必须暴露
                info.put("status", "WARN");
                info.put("missingModels", missing);
            }
        } catch (Exception e) {
            info.put("status", "DOWN");
            info.put("error", e.getMessage());
        }
        return info;
    }

    /** /api/tags 响应中的模型名列表；非法 JSON 按空列表处理（各模型如实报缺失） */
    private List<String> parseModelNames(String tagsBody) {
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode model : objectMapper.readTree(tagsBody).path("models")) {
                names.add(model.path("name").asString(""));
            }
            return names;
        } catch (Exception e) {
            // 响应体不是合法 JSON：按"全不在位"处理，由 missing 列表如实暴露
            return List.of();
        }
    }

    /** 模型是否在位（配置写 qwen3:8b，回报可能是 qwen3:8b:latest） */
    private boolean modelPresent(List<String> modelNames, String required) {
        return modelNames.stream()
                .anyMatch(name -> name.equals(required) || name.startsWith(required + ":"));
    }

    private Map<String, Object> checkMilvus() {
        return probe(milvusHealthUrl + "/healthz");
    }

    private Map<String, Object> checkPrometheus() {
        return probe(prometheusBaseUrl + "/-/healthy");
    }

    /** Milvus 与 Prometheus 的健康探测同构（GET 一次，不抛异常即 UP），提取公共实现 */
    private Map<String, Object> probe(String url) {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            restClient.get().uri(url).retrieve().body(String.class);
            info.put("status", "UP");
        } catch (Exception e) {
            info.put("status", "DOWN");
            info.put("error", e.getMessage());
        }
        return info;
    }

    private Map<String, Object> checkJvmMemory() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long free = max - used;

        Map<String, Object> info = new LinkedHashMap<>();
        // 可用堆内存不足 15% 时告警：本项目 LLM 推理在 Ollama 进程，JVM 堆压力小，15% 是偏宽松的预警线
        info.put("status", free > max * 0.15 ? "UP" : "WARN");
        info.put("usedMB", used / 1024 / 1024);
        info.put("maxMB", max / 1024 / 1024);
        info.put("freeMB", free / 1024 / 1024);
        info.put("usagePercent", Math.round(used * 1000.0 / max) / 10.0);
        return info;
    }

    private Map<String, Object> checkDiskSpace() {
        // 注意：new File(".") 取的是应用工作目录所在盘（IDE 启动时即项目目录所在盘），
        // 不是主机全部磁盘；容器化部署后反映的是挂载该工作目录的卷
        File root = new File(".");
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();

        Map<String, Object> info = new LinkedHashMap<>();
        // 可用空间低于 5GB 告警：Milvus 数据卷在 compose 中独立挂载，这里只反映应用盘
        info.put("status", free > 5L * 1024 * 1024 * 1024 ? "UP" : "WARN");
        info.put("totalGB", Math.round(total / 1024.0 / 1024 / 1024 * 10) / 10.0);
        info.put("freeGB", Math.round(free / 1024.0 / 1024 / 1024 * 10) / 10.0);
        return info;
    }
}
