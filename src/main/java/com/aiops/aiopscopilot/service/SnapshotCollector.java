package com.aiops.aiopscopilot.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aiops.aiopscopilot.config.MonitoredTarget;
import com.aiops.aiopscopilot.config.TargetsProperties;
import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;

/**
 * 巡检快照采集器：对目标列表（self 永远在列，见 TargetsProperties）并行采集多目标指标快照，
 * 供巡检调度器与异步深度诊断共用（两边口径一致，也避免两个 service 循环依赖）。
 * <p>
 * 多目标设计：
 * <ul>
 *   <li>每个目标一个虚拟线程并行采集——N 个目标不放大轮耗时（最坏 = 单目标的 8 条串行查询）；
 *       Prometheus 只能串行应答的部分由 Prometheus 侧自行排队，应用侧并行无副作用</li>
 *   <li>死锁二级诊断（ThreadMXBean）仅对 self 目标生效：它只能看本 JVM 的线程（硬边界）——
 *       业务目标的 BLOCKED 仍会被指标计数发现并升级，但精查不到对方栈帧，报告如实标注</li>
 *   <li>单目标采集失败不影响其它目标：失败块记 error 字段（对模型是不可信数据），
 *       其余语义键缺失时下游按 -1 哨兵处理</li>
 * </ul>
 * <p>
 * Prometheus 只能告诉我们"有几个阻塞线程"，ThreadMXBean 才能告诉我们
 * "是否真死锁 + 谁在等谁的锁 + 阻塞在哪个方法"——这套组合：Prometheus 是触角，ThreadMXBean 是显微镜。
 */
@Component
public class SnapshotCollector {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCollector.class);

    private final PrometheusTool prometheusTool;
    private final SystemHealthTools systemHealthTools;
    private final List<MonitoredTarget> targets;
    private final String selfApplication;

    public SnapshotCollector(PrometheusTool prometheusTool,
                             SystemHealthTools systemHealthTools,
                             TargetsProperties targetsProperties,
                             @Value("${spring.application.name}") String selfApplication) {
        this.prometheusTool = prometheusTool;
        this.systemHealthTools = systemHealthTools;
        this.selfApplication = selfApplication;
        this.targets = targetsProperties.resolved(selfApplication);
        for (MonitoredTarget t : this.targets) {
            for (String key : t.metrics().keySet()) {
                if (!PrometheusTool.SNAPSHOT_SEMANTICS.contains(key)) {
                    throw new IllegalStateException("监控目标 " + t.name() + " 的 metrics 映射含未知语义键: "
                            + key + "（合法键: " + PrometheusTool.SNAPSHOT_SEMANTICS + "）");
                }
            }
        }
    }

    /** 有效目标列表（self 兜底在首位），供巡检调度器按目标路由与构建 Prompt */
    public List<MonitoredTarget> targets() {
        return targets;
    }

    /**
     * 采集一轮多目标快照：
     * {@code {"queryTimestamp":"...", "targets":{"<目标名>":{...7 语义...}, ...}}}
     */
    public Map<String, Object> collect() {
        Map<String, Map<String, Object>> collected = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MonitoredTarget t : targets) {
                pool.submit(() -> collected.put(t.name(), collectOne(t)));
            }
        } // close() 等待全部采集完成
        Map<String, Object> targetsBlock = new LinkedHashMap<>();
        for (MonitoredTarget t : targets) {
            targetsBlock.put(t.name(), collected.get(t.name()));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("queryTimestamp", LocalDateTime.now().toString());
        snapshot.put("targets", targetsBlock);
        return snapshot;
    }

    private Map<String, Object> collectOne(MonitoredTarget target) {
        try {
            // 防御性拷贝：querySnapshot 返回的实现可能不可变，附加死锁诊断前先拷贝
            Map<String, Object> snap = new java.util.LinkedHashMap<>(prometheusTool.querySnapshot(target));
            // 死锁二级诊断仅对 self 生效：ThreadMXBean 只能看本 JVM（硬边界）
            if (target.application().equals(selfApplication)) {
                Object blocked = snap.get("blockedThreads");
                if (blocked instanceof Number n && n.doubleValue() > 0) {
                    snap.put("deadlockDiagnosis", systemHealthTools.detectDeadlock());
                }
            }
            return snap;
        } catch (Exception e) {
            // 单目标失败不拖垮整轮：记 error 块，下游阈值按 -1 处理，AI 按"不可信数据"对待
            log.warn("[SnapshotCollector] 目标 {} 采集失败: {}", target.name(), e.toString());
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }
}
