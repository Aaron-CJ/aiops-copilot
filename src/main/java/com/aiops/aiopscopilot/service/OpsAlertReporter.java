package com.aiops.aiopscopilot.service;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;

/**
 * 巡检告警报告输出器：当前以控制台日志输出 ASCII 框线格式报告
 * （含根因分析与处置建议，由巡检模型生成）。
 * <p>
 * 输出分级：
 * <ul>
 *   <li>{@link #report} — 首次发现（NEW）的故障：ERROR 级 + ASCII 框线全量报告</li>
 *   <li>{@link #logHeartbeat} — 持续中（ACTIVE）的故障：INFO 级一行心跳，避免告警风暴</li>
 *   <li>{@link #logResolved} — 故障恢复（RESOLVED）：INFO 级一行，告知运维"已恢复"</li>
 *   <li>{@link #logNormal} — 巡检正常：INFO 级一行摘要</li>
 * </ul>
 * <p>
 * 所有决策点同时写入审计日志（{@link AuditLogger}），落盘到 logs/aiops-audit.log，
 * 容器重启不丢证据。
 * <p>
 * 后续替换为钉钉/飞书 Webhook 时，只需修改各方法体内的输出实现，
 * 调用方（{@link OpsScheduler}）无需改动。
 */
@Component
public class OpsAlertReporter {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertReporter.class);
    private final AuditLogger audit;

    public OpsAlertReporter(AuditLogger audit) {
        this.audit = audit;
    }

    /**
     * 输出结构化告警报告（首次发现 NEW 时调用）。
     *
     * @param fingerprint     事件指纹（基于指标快照，用于审计日志关联）
     * @param status          异常级别：warning / critical
     * @param summary         一句话异常摘要（模型生成）
     * @param rootCause       根因分析（模型生成）
     * @param suggestion      处置建议（模型生成）
     * @param metricsSnapshot 巡检时的指标快照
     * @param modelRawOutput  模型原始输出（DEBUG 级保留，便于调优 prompt）
     */
    public void report(String fingerprint, String status, String summary, String rootCause, String suggestion,
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
        log.debug("[AIOps] 模型原始输出:\n{}", modelRawOutput);
        audit.incidentNew(fingerprint, status, summary);
    }

    /**
     * 持续中故障的心跳日志（ACTIVE 时调用）。
     * INFO 级一行，避免同一故障每分钟刷屏——这是去重的关键。
     */
    public void logHeartbeat(Incident incident) {
        Duration dur = IncidentStore.durationOf(incident);
        log.info("[AIOps 心跳] 故障持续中 | 指纹={} | 已持续 {}min | 首次摘要={} | 最新根因={}",
                incident.fingerprint(),
                dur.toMinutes(),
                incident.summary(),
                incident.rootCause());
        audit.incidentActive(incident.fingerprint(), dur);
    }

    /**
     * 故障恢复日志（连续 N 轮 normal 后标记 RESOLVED 时调用）。
     */
    public void logResolved(Incident incident) {
        Duration dur = IncidentStore.durationOf(incident);
        log.info("[AIOps 恢复] 故障已恢复 | 指纹={} | 总持续 {}min | 历史根因={}",
                incident.fingerprint(),
                dur.toMinutes(),
                incident.rootCause());
        audit.incidentResolved(incident.fingerprint(), dur);
    }

    /**
     * 巡检正常时输出 INFO 级一行摘要。
     * 设计原则：正常情况应该静默，运维同学不该被无意义日志打扰。
     */
    public void logNormal(Map<String, Object> metricsSnapshot) {
        log.info("[OpsScheduler] 巡检完成: 正常 | CPU={} | 堆使用率={} | QPS={} | 最大延迟={} | BLOCKED={} | GC(5m)={}",
                metricsSnapshot.get("cpuUsage"),
                metricsSnapshot.get("heapUsage"),
                metricsSnapshot.get("qpsLast1m"),
                metricsSnapshot.get("maxRequestSeconds"),
                metricsSnapshot.get("blockedThreads"),
                metricsSnapshot.get("gcCountLast5m"));
    }

    /**
     * 深度诊断报告（DiagnosisService worker 完成一个任务时调用）。
     * <p>
     * INFO 级——深度报告是对已有故障的补充分析，不是新告警，事件本身的首次发现已由
     * {@link #report} 以 ERROR 报过；FAILED 时降为 WARN。
     * 后续接飞书/钉钉时，push 动作从这里接出。
     */
    public void logDeepReport(DiagnosisTask task, long elapsedMs) {
        boolean success = task.status() == DiagnosisTask.Status.SUCCEEDED;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\n==================================================");
        sb.append("\n[AIOps 深度诊断报告] trigger=").append(task.trigger());
        sb.append(" | taskId=").append(task.taskId());
        sb.append(" | 指纹=").append(task.fingerprint());
        sb.append(" | 耗时=").append(elapsedMs / 1000).append("s");
        sb.append(" | 状态=").append(task.status());
        sb.append("\n--------------------------------------------------");
        if (success) {
            sb.append("\n").append(task.report());
        } else {
            sb.append("\n深度诊断失败: ").append(task.error());
        }
        sb.append("\n==================================================");
        if (success) {
            log.info(sb.toString());
        } else {
            log.warn(sb.toString());
        }
    }
}
