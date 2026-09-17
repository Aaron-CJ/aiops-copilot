package com.aiops.aiopscopilot.service;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;

/**
 * 巡检快照采集器：Prometheus 预拉 7 条核心指标 + BLOCKED&gt;0 时 ThreadMXBean 二级死锁诊断。
 * <p>
 * 从 OpsScheduler 抽出为独立组件，使巡检调度与异步深度诊断（DiagnosisService）
 * 拿到的快照口径完全一致，同时避免两个 service 之间循环依赖。
 */
@Component
public class SnapshotCollector {

    private final PrometheusTool prometheusTool;
    private final SystemHealthTools systemHealthTools;

    public SnapshotCollector(PrometheusTool prometheusTool, SystemHealthTools systemHealthTools) {
        this.prometheusTool = prometheusTool;
        this.systemHealthTools = systemHealthTools;
    }

    /**
     * 采集一轮完整快照。
     * <p>
     * Prometheus 只能告诉我们"有几个阻塞线程"，ThreadMXBean 才能告诉我们
     * "是否真死锁 + 谁在等谁的锁 + 阻塞在哪个方法"——
     * 这套组合：Prometheus 是触角，ThreadMXBean 是显微镜。
     */
    public Map<String, Object> collect() {
        Map<String, Object> snapshot = prometheusTool.queryFixedMetrics();
        Object blocked = snapshot.get("blockedThreads");
        if (blocked instanceof Number n && n.doubleValue() > 0) {
            snapshot.put("deadlockDiagnosis", systemHealthTools.detectDeadlock());
        }
        return snapshot;
    }
}
