package com.aiops.aiopscopilot.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.IncidentStore.IncidentStatus;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4.3 升级触发规则纯函数测试（不依赖 Spring 容器与 LLM）。
 */
class EscalationRuleTest {

    private Incident incident(IncidentStatus status, String severity, String suggestion,
                              Instant firstSeen) {
        return new Incident("CRITICAL|DEADLOCK;", status, firstSeen, Instant.now(),
                0, severity, "summary", "rootCause", suggestion);
    }

    @Test
    void newCriticalWithWriteSuggestionEscalates() {
        Incident inc = incident(IncidentStatus.NEW, "critical", "建议立即重启应用以解除死锁", Instant.now());
        assertEquals(DiagnosisTask.Trigger.CRITICAL_WRITE,
                OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    @Test
    void newCriticalWithoutWriteSuggestionDoesNotEscalateOnHighConfidence() {
        Incident inc = incident(IncidentStatus.NEW, "critical", "建议 dump 线程栈后人工分析", Instant.now());
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    @Test
    void writeKeywordOnWarningDoesNotTriggerCriticalWrite() {
        Incident inc = incident(IncidentStatus.NEW, "warning", "可择机重启", Instant.now());
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    @Test
    void persistentIncidentBeyondThresholdEscalates() {
        Instant sixMinutesAgo = Instant.now().minus(6, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.ACTIVE, "critical", "dump 线程栈分析", sixMinutesAgo);
        assertEquals(DiagnosisTask.Trigger.PERSISTENT,
                OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    @Test
    void persistentIncidentWithinThresholdDoesNotEscalate() {
        Instant fourMinutesAgo = Instant.now().minus(4, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.ACTIVE, "warning", "继续观察", fourMinutesAgo);
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    @Test
    void lowConfidenceEscalatesWhenNoStrongerRule() {
        Incident inc = incident(IncidentStatus.NEW, "warning", "继续观察指标", Instant.now());
        assertEquals(DiagnosisTask.Trigger.LOW_CONFIDENCE,
                OpsScheduler.chooseEscalationTrigger(inc, "low", Instant.now()));
    }

    /** 优先级：持续未消除 高于 低置信度（一轮只升级一个，避免同故障一次排两个深度任务） */
    @Test
    void persistentRuleHasPriorityOverLowConfidence() {
        Instant eightMinutesAgo = Instant.now().minus(8, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.ACTIVE, "warning", "继续观察", eightMinutesAgo);
        assertEquals(DiagnosisTask.Trigger.PERSISTENT,
                OpsScheduler.chooseEscalationTrigger(inc, "low", Instant.now()));
    }

    @Test
    void writeKeywordDetection() {
        assertTrue(OpsScheduler.containsWriteAction("建议 restart 服务"));
        assertTrue(OpsScheduler.containsWriteAction("执行 rollback 回滚发布"));
        assertTrue(OpsScheduler.containsWriteAction("KILL 掉异常进程"));
        assertFalse(OpsScheduler.containsWriteAction("只读取证：jstack dump 与 GC 日志"));
        assertFalse(OpsScheduler.containsWriteAction(null));
    }

    // ==================== G7：持续时长边界值 ====================

    /** 恰好 5 分钟（>= 边界，包含等于）→ persistent */
    @Test
    void persistentBoundaryExactlyFiveMinutesEscalates() {
        Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.ACTIVE, "critical", "dump 分析", fiveMinutesAgo);
        assertEquals(DiagnosisTask.Trigger.PERSISTENT,
                OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    /** 4 分 59 秒：toMinutes()=4，不升级 */
    @Test
    void persistentBoundaryJustUnderFiveMinutesDoesNotEscalate() {
        Instant under = Instant.now().minus(5, ChronoUnit.MINUTES).plusSeconds(1);
        Incident inc = incident(IncidentStatus.ACTIVE, "warning", "继续观察", under);
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    // ==================== G8/G9：两条规则的 isNew 闸门 ====================

    /** NEW 事件即使 firstSeen 很旧也不走 persistent（NEW 只会是首次发现，旧时间戳属异常输入，按非持续处理） */
    @Test
    void newIncidentWithOldFirstSeenDoesNotTriggerPersistent() {
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.NEW, "critical", "dump 线程栈（只读）", tenMinutesAgo);
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    /** ACTIVE + critical + 写建议 + 超时：规则 1 的 isNew 闸门挡住 critical_write，落到 persistent */
    @Test
    void activeCriticalWithWriteSuggestionAndOldGoesPersistentNotCriticalWrite() {
        Instant sevenMinutesAgo = Instant.now().minus(7, ChronoUnit.MINUTES);
        Incident inc = incident(IncidentStatus.ACTIVE, "critical", "建议重启应用", sevenMinutesAgo);
        assertEquals(DiagnosisTask.Trigger.PERSISTENT,
                OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    // ==================== G10：规则 1 优先级压过低置信度 ====================

    /** NEW + critical + 写建议 + confidence=low：critical_write 必须赢（最危险组合优先） */
    @Test
    void criticalWriteHasPriorityOverLowConfidence() {
        Incident inc = incident(IncidentStatus.NEW, "critical", "建议立即回滚发布", Instant.now());
        assertEquals(DiagnosisTask.Trigger.CRITICAL_WRITE,
                OpsScheduler.chooseEscalationTrigger(inc, "low", Instant.now()));
    }

    // ==================== G11：大小写/中文关键词/空值 ====================

    /** severity 大小写不敏感 */
    @Test
    void severityMatchingIsCaseInsensitive() {
        Incident inc = incident(IncidentStatus.NEW, "CRITICAL", "建议 RESTART 服务", Instant.now());
        assertEquals(DiagnosisTask.Trigger.CRITICAL_WRITE,
                OpsScheduler.chooseEscalationTrigger(inc, "high", Instant.now()));
    }

    /** 中文写操作关键词：停机 / 下线 */
    @Test
    void chineseWriteKeywordsDetected() {
        assertTrue(OpsScheduler.containsWriteAction("建议停机维护"));
        assertTrue(OpsScheduler.containsWriteAction("将异常节点下线"));
        assertFalse(OpsScheduler.containsWriteAction(""));
        assertFalse(OpsScheduler.containsWriteAction("建议先观察一个周期"));
    }

    /** confidence 非 low（medium/high/未知值大小写）不误触发 */
    @Test
    void nonLowConfidenceDoesNotEscalate() {
        Incident inc = incident(IncidentStatus.NEW, "warning", "继续观察", Instant.now());
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "medium", Instant.now()));
        assertNull(OpsScheduler.chooseEscalationTrigger(inc, "HIGH", Instant.now()));
    }
}
