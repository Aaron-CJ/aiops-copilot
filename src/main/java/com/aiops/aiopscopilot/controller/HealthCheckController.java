package com.aiops.aiopscopilot.controller;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aiops.aiopscopilot.common.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 生产环境健康检查：一键巡检所有依赖服务的连通性与资源水位。
 * <p>
 * 检查维度：
 * <ol>
 *   <li>Ollama（LLM 推理服务）——GET /api/tags</li>
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

    private final RestClient restClient;
    private final String ollamaBaseUrl;
    private final String milvusHealthUrl;
    private final String prometheusBaseUrl;

    public HealthCheckController(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${spring.ai.vectorstore.milvus.client.host:localhost}") String milvusHost,
            @Value("${aiops.prometheus.base-url:http://localhost:9090}") String prometheusBaseUrl) {
        this.restClient = RestClient.create();
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.milvusHealthUrl = "http://" + milvusHost + ":" + MILVUS_HEALTH_PORT;
        this.prometheusBaseUrl = prometheusBaseUrl;
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
            restClient.get().uri(ollamaBaseUrl + "/api/tags").retrieve().body(String.class);
            info.put("status", "UP");
        } catch (Exception e) {
            info.put("status", "DOWN");
            info.put("error", e.getMessage());
        }
        return info;
    }

    private Map<String, Object> checkMilvus() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            restClient.get().uri(milvusHealthUrl + "/healthz").retrieve().body(String.class);
            info.put("status", "UP");
        } catch (Exception e) {
            info.put("status", "DOWN");
            info.put("error", e.getMessage());
        }
        return info;
    }

    private Map<String, Object> checkPrometheus() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            restClient.get().uri(prometheusBaseUrl + "/-/healthy").retrieve().body(String.class);
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
