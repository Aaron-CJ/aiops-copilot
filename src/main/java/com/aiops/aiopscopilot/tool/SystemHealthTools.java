package com.aiops.aiopscopilot.tool;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.sun.management.OperatingSystemMXBean;

/**
 * 服务器健康检查工具：通过 Spring AI 的 Function Calling 机制
 * 暴露给大模型，由模型在对话中自主决定何时调用。
 * <p>
 * 注意：Spring AI 1.0 早期使用的 {@code @Description} 注解
 * 在 2.0 GA 中已移除，统一改为 {@link Tool @Tool(description = "...")}。
 */
@Component
public class SystemHealthTools {

    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * 获取服务器 CPU 占用率与内存剩余。
     * 由 qwenChatClient 注册为可调用工具，用户提问"服务器健康"等运维问题时，
     * 模型会自动调用本方法获取真实指标，避免幻觉。
     */
    @Tool(description = "获取当前服务器的 CPU 占用率（百分比）和剩余可用内存（字节与 MB）。"
            + "当用户询问服务器健康状态、CPU 使用率、内存剩余等运维问题时调用此工具。")
    public Map<String, Object> getServerHealth() {
        // getCpuLoad() 在 JVM 启动后首次调用可能返回 -1（尚未完成采样），按 0 处理避免误导模型
        double cpuLoad = osBean.getCpuLoad();
        if (cpuLoad < 0) {
            cpuLoad = 0;
        }
        long freeBytes = osBean.getFreeMemorySize();
        long totalBytes = osBean.getTotalMemorySize();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cpuUsagePercent", Math.round(cpuLoad * 1000) / 10.0);  // 保留 1 位小数
        result.put("freeMemoryBytes", freeBytes);
        result.put("totalMemoryBytes", totalBytes);
        result.put("freeMemoryMB", freeBytes / 1024 / 1024);
        result.put("totalMemoryMB", totalBytes / 1024 / 1024);
        return result;
    }
}
