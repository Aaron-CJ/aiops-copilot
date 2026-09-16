package com.aiops.aiopscopilot.common.audit;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 决策审计日志：独立于业务日志，落盘到 logs/aiops-audit.log（见 logback-spring.xml）。
 * <p>
 * 设计原则：
 * <ol>
 *   <li>不可被 Agent 自身读写——AuditLogger 不暴露任何写接口给工具层，Agent 无法篡改审计记录</li>
 *   <li>结构化文本格式（管道分隔），便于 grep 与后续转 JSON：{@code 时间|级别|类别|字段1=值1|字段2=值2}</li>
 *   <li>记录关键决策点：巡检起止、降级触发、AI 漏报兜底、事件状态变更</li>
 * </ol>
 * <p>
 * 审计 logger 名 {@code com.aiops.aiopscopilot.audit}（additive=false，仅写文件不冒泡控制台），
 * 防止控制台噪音淹没运维同学。
 */
@Component
public class AuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("com.aiops.aiopscopilot.audit");

    /** 巡检开始 */
    public void inspectionStarted() {
        audit.info("巡检|START");
    }

    /** 巡检结束 */
    public void inspectionFinished(String status, long elapsedMs, String fingerprint) {
        audit.info("巡检|END|status={}|elapsedMs={}|fingerprint={}", status, elapsedMs, fingerprint);
    }

    /** LLM 超时降级触发 */
    public void degradedPathTriggered(String reason, long elapsedMs) {
        audit.warn("巡检|DEGRADED|reason={}|elapsedMs={}", reason, elapsedMs);
    }

    /** AI 漏报兜底介入（AI 判 normal 但指标超阈值，代码强改 warning/critical） */
    public void backstopTriggered(String aiStatus, String forcedStatus, String metric, double value) {
        audit.warn("巡检|BACKSTOP|aiStatus={}|forcedStatus={}|metric={}|value={}", aiStatus, forcedStatus, metric, value);
    }

    /** 事件首次发现（NEW） */
    public void incidentNew(String fingerprint, String status, String summary) {
        audit.info("事件|NEW|fingerprint={}|status={}|summary={}", fingerprint, status, summary);
    }

    /** 事件持续中（ACTIVE 心跳） */
    public void incidentActive(String fingerprint, Duration duration) {
        audit.info("事件|ACTIVE|fingerprint={}|durationMin={}", fingerprint, duration.toMinutes());
    }

    /** 事件恢复（RESOLVED） */
    public void incidentResolved(String fingerprint, Duration totalDuration) {
        audit.info("事件|RESOLVED|fingerprint={}|totalDurationMin={}", fingerprint, totalDuration.toMinutes());
    }

    /** 降级路径的阈值判断结果（fallbackByThreshold 产出） */
    public void fallbackResult(String status, String rootCause, Map<String, Object> snapshot) {
        audit.info("巡检|FALLBACK_RESULT|status={}|rootCause={}|snapshot={}", status, rootCause, snapshot);
    }
}
