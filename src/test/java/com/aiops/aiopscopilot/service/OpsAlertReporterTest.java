package com.aiops.aiopscopilot.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpsAlertReporter.logNormal 的多目标行格式：逐目标压缩列出关键指标，
 * 失败目标块（error）原样透出。纯函数 normalLine 的格式契约。
 */
class OpsAlertReporterTest {

    private static Map<String, Object> block(double cpu, double heap, double qps,
                                             double maxReq, double blocked, double gc) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("cpuUsage", cpu);
        b.put("heapUsage", heap);
        b.put("qpsLast1m", qps);
        b.put("maxRequestSeconds", maxReq);
        b.put("blockedThreads", blocked);
        b.put("gcCountLast5m", gc);
        return b;
    }

    /** 多目标：每目标一段（目标名 + 6 项关键指标），段间以 ;; 分隔 */
    @Test
    void normalLine_listsEachTargetWithKeyMetrics() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("aiops-copilot", block(0.03, 0.41, 0.0, 0.012, 0.0, 0.0));
        ordered.put("app-2", block(0.1, 0.2, 5.0, 0.3, 1.0, 2.0));
        Map<String, Object> snapshot = Map.of("targets", ordered);

        String line = OpsAlertReporter.normalLine(snapshot);
        assertTrue(line.contains("[aiops-copilot] CPU=0.03 堆=0.41 QPS=0.0 最大延迟=0.012 BLOCKED=0.0 GC(5m)=0.0"),
                "self 段应含全部 6 项指标: " + line);
        assertTrue(line.contains("[app-2] CPU=0.1 堆=0.2 QPS=5.0 最大延迟=0.3 BLOCKED=1.0 GC(5m)=2.0"),
                "app-2 段应含全部 6 项指标: " + line);
        assertTrue(line.contains("  ;;  "), "两目标之间应以 ;; 分隔");
    }

    /** 单目标：无线段分隔符 */
    @Test
    void normalLine_singleTargetNoSeparator() {
        Map<String, Object> snapshot = Map.of("targets",
                Map.of("aiops-copilot", block(0.03, 0.41, 0.0, 0.012, 0.0, 0.0)));
        String line = OpsAlertReporter.normalLine(snapshot);
        assertFalse(line.contains(";;"), "单目标不应出现分隔符: " + line);
        assertTrue(line.startsWith("[aiops-copilot]"), "行应以目标名开头: " + line);
    }

    /** 无 targets 键（结构异常）：返回空串，日志退化为裸状态行而非抛异常 */
    @Test
    void normalLine_missingTargetsBlockYieldsEmpty() {
        assertEquals("", OpsAlertReporter.normalLine(Map.of()));
        assertEquals("", OpsAlertReporter.normalLine(Map.of("other", 1)));
    }

    /** 失败目标块（error 字段）：原样透出，不伪装成指标 */
    @Test
    void normalLine_errorBlockPassesThrough() {
        Map<String, Object> snapshot = Map.of("targets",
                Map.of("app-2", Map.of("error", "prometheus down")));
        String line = OpsAlertReporter.normalLine(snapshot);
        assertTrue(line.contains("[app-2] 查询失败: prometheus down"), "失败目标应显示原因而非六个 null: " + line);
    }
}
