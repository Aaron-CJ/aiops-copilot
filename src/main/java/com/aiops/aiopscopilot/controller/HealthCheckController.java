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
 * 每项返回 UP / DOWN + 详情，调用方据此判断环境是否就绪。
 */
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    private final RestClient restClient;

    @Value("${aiops.prometheus.base-url:http://localhost:9090}")
    private String prometheusBaseUrl;

    public HealthCheckController() {
        this.restClient = RestClient.create();
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> check() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Ollama
        result.put("ollama", checkOllama());

        // 2. Milvus
        result.put("milvus", checkMilvus());

        // 3. Prometheus
        result.put("prometheus", checkPrometheus());

        // 4. JVM 内存
        result.put("jvmMemory", checkJvmMemory());

        // 5. 磁盘空间
        result.put("diskSpace", checkDiskSpace());

        return Result.success(result);
    }

    private Map<String, Object> checkOllama() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            restClient.get().uri("http://localhost:11434/api/tags").retrieve().body(String.class);
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
            restClient.get().uri("http://localhost:9091/healthz").retrieve().body(String.class);
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
        info.put("status", free > max * 0.15 ? "UP" : "WARN");
        info.put("usedMB", used / 1024 / 1024);
        info.put("maxMB", max / 1024 / 1024);
        info.put("freeMB", free / 1024 / 1024);
        info.put("usagePercent", Math.round(used * 1000.0 / max) / 10.0);
        return info;
    }

    private Map<String, Object> checkDiskSpace() {
        File root = new File(".");
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", free > 5L * 1024 * 1024 * 1024 ? "UP" : "WARN"); // 5GB 阈值
        info.put("totalGB", Math.round(total / 1024.0 / 1024 / 1024 * 10) / 10.0);
        info.put("freeGB", Math.round(free / 1024.0 / 1024 / 1024 * 10) / 10.0);
        return info;
    }
}
