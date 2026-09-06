package com.aiops.aiopscopilot.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 巡检告警报告输出器。
 * <p>
 * 当前实现：控制台 ERROR 级日志输出 ASCII 框线格式告警报告。
 * 这套替代了传统 Alertmanager 的"无脑阈值告警 + 无上下文短信"——
 * 我们的告警已经包含根因分析和处置建议（由 deepseek-r1 生成）。
 * <p>
 * 后续替换为钉钉/飞书 Webhook 时，只需修改 {@link #report} 方法体，
 * 把 StringBuilder 拼好的内容改成 HTTP POST 即可，调用方无需改动。
 */
@Component
public class OpsAlertReporter {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertReporter.class);

    /**
     * 输出结构化告警报告。
     *
     * @param status          异常级别：warning / critical
     * @param summary         一句话异常摘要（模型生成）
     * @param rootCause       根因分析（模型生成）
     * @param suggestion      处置建议（模型生成）
     * @param metricsSnapshot 巡检时的指标快照
     * @param modelRawOutput  模型原始输出（DEBUG 级保留，便于调优 prompt）
     */
    public void report(String status, String summary, String rootCause, String suggestion,
                       Map<String, Object> metricsSnapshot, String modelRawOutput) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n==================================================");
        sb.append("\n[AIOps 巡检告警] 级别: ").append(status.toUpperCase());
        sb.append("\n--------------------------------------------------");
        sb.append("\n异常摘要: ").append(summary);
        sb.append("\n--------------------------------------------------");
        sb.append("\n关键指标快照:");
        metricsSnapshot.forEach((k, v) -> sb.append("\n  - ").append(k).append(": ").append(v));
        sb.append("\n--------------------------------------------------");
        sb.append("\n根因分析: ").append(rootCause);
        sb.append("\n--------------------------------------------------");
        sb.append("\n处置建议: ").append(suggestion);
        sb.append("\n==================================================");
        log.error(sb.toString());

        // 模型原始输出保留在 DEBUG 级，便于后续调 prompt
        log.debug("[AIOps] 模型原始输出:\n{}", modelRawOutput);
    }

    /**
     * 巡检正常时输出 INFO 级一行摘要。
     * 设计原则：正常情况应该静默，运维同学不该被无意义日志打扰。
     */
    public void logNormal(Map<String, Object> metricsSnapshot) {
        log.info("[OpsScheduler] 巡检完成: 正常 | CPU={} | 堆内存={} | QPS={} | BLOCKED={} | GC(5m)={}",
                metricsSnapshot.get("cpuUsage"),
                metricsSnapshot.get("heapMemoryByGen"),
                metricsSnapshot.get("qpsLast1m"),
                metricsSnapshot.get("blockedThreads"),
                metricsSnapshot.get("gcCountLast5m"));
    }
}
