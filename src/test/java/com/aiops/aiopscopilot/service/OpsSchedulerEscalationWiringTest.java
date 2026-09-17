package com.aiops.aiopscopilot.service;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisService;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 4.3 升级接线测试：验证 OpsScheduler.handleInspectionResult 与 DiagnosisService 的实际交互。
 * <p>
 * 纯函数规则（选哪个 trigger）由 EscalationRuleTest 覆盖；本类聚焦有状态行为：
 * parse_fail 连续计数/成功归零/degraded 不升级/各路径是否真的提交了升级任务。
 * 依赖：DiagnosisService、OpsAlertReporter、MetricsService 用 Mockito mock，
 * IncidentStore 用真实实例（状态机行为本身已被 IncidentStoreTest 覆盖，这里需要它真的记事件）。
 */
class OpsSchedulerEscalationWiringTest {

    private DiagnosisService diagnosisService;
    private OpsAlertReporter reporter;
    private OpsScheduler scheduler;
    private Method handleMethod;

    private static final Map<String, Object> NORMAL_SNAP = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 5.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
    private static final Map<String, Object> DEADLOCK_SNAP = Map.of(
            "cpuUsage", 0.2, "heapUsage", 0.3, "qpsLast1m", 1.0,
            "blockedThreads", 2.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.2);

    @BeforeEach
    void setUp() throws Exception {
        diagnosisService = mock(DiagnosisService.class);
        reporter = mock(OpsAlertReporter.class);
        MetricsService metricsService = mock(MetricsService.class);
        AuditLogger audit = new AuditLogger();
        scheduler = new OpsScheduler(null, null, reporter, metricsService,
                new IncidentStore(), audit, diagnosisService);
        handleMethod = OpsScheduler.class.getDeclaredMethod(
                "handleInspectionResult", String.class, Map.class, long.class, boolean.class);
        handleMethod.setAccessible(true);
    }

    private void handle(String reply, Map<String, Object> snapshot, boolean degraded) {
        try {
            handleMethod.invoke(scheduler, reply, snapshot, System.currentTimeMillis(), degraded);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String json(String status, String confidence, String summary,
                               String rootCause, String suggestion) {
        return "{\"status\":\"" + status + "\",\"confidence\":\"" + confidence + "\","
                + "\"summary\":\"" + summary + "\",\"rootCause\":\"" + rootCause + "\","
                + "\"suggestion\":\"" + suggestion + "\"}";
    }

    /** 首轮解析失败：计数 1，不升级 */
    @Test
    void singleParseFailureDoesNotEscalate() {
        handle("这不是 JSON", NORMAL_SNAP, false);
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 连续第 2 轮解析失败：恰好升级一次 PARSE_FAIL，note 带原始输出 */
    @Test
    void twoConsecutiveParseFailuresEscalateOnceWithRawOutput() {
        handle("坏输出#1", NORMAL_SNAP, false);
        handle("坏输出#2", NORMAL_SNAP, false);

        ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(diagnosisService, times(1)).submitEscalated(
                trigger.capture(), fp.capture(), any(), note.capture());
        assertEquals(DiagnosisTask.Trigger.PARSE_FAIL, trigger.getValue());
        assertEquals("PARSE_FAIL", fp.getValue());
        assertTrue(note.getValue().contains("坏输出#2"), "升级 note 应含最近一次原始输出");
    }

    /** 升级后计数归零：第 3 轮仍失败只计 1，不会再次升级（防抖之外的第二道保险） */
    @Test
    void counterResetsAfterEscalation() {
        handle("坏#1", NORMAL_SNAP, false);
        handle("坏#2", NORMAL_SNAP, false); // 触发并归零
        handle("坏#3", NORMAL_SNAP, false); // 新一轮计数 1

        verify(diagnosisService, times(1)).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 成功解析一轮后计数归零：失败-成功-失败 不升级 */
    @Test
    void successfulRoundResetsParseFailureCounter() {
        handle("坏", NORMAL_SNAP, false);
        handle(json("normal", "high", "各项指标正常", "N/A", "N/A"), NORMAL_SNAP, false);
        handle("又坏了", NORMAL_SNAP, false);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 升级 note 中的原始输出截断到 1000 字符，防止 r1 prompt 被超长坏输出灌爆 */
    @Test
    void parseFailRawOutputIsTruncatedToThousandChars() {
        String huge = "X".repeat(1200);
        handle(huge, NORMAL_SNAP, false);
        handle(huge, NORMAL_SNAP, false);

        ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
        verify(diagnosisService, times(1)).submitEscalated(
                anyString(), anyString(), any(), note.capture());
        String value = note.getValue();
        assertTrue(value.contains("X".repeat(900)));
        assertFalse(value.contains("X".repeat(1001)), "原始输出必须截断到 1000 字符");
    }

    /** 降级路径（Ollama 不可用）连续解析失败也不升级——r1 同样调不动 */
    @Test
    void degradedPathNeverTriggersParseFailEscalation() {
        handle("坏", NORMAL_SNAP, true);
        handle("坏", NORMAL_SNAP, true);
        handle("坏", NORMAL_SNAP, true);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** critical NEW + 写操作建议：提交 CRITICAL_WRITE 升级，指纹对应 BLOCKED=2 */
    @Test
    void criticalWithWriteSuggestionSubmitsCriticalWrite() {
        String reply = json("critical", "high",
                "发现 2 个 BLOCKED 线程", "存在死锁", "建议立即重启应用解除死锁");
        handle(reply, DEADLOCK_SNAP, false);

        ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(diagnosisService, times(1)).submitEscalated(
                trigger.capture(), fp.capture(), eq(DEADLOCK_SNAP), anyString());
        assertEquals(DiagnosisTask.Trigger.CRITICAL_WRITE, trigger.getValue());
        assertTrue(fp.getValue().contains("BLOCKED=2"), "指纹应含 BLOCKED=2: " + fp.getValue());
    }

    /** normal + confidence=low：提交 LOW_CONFIDENCE 漏报复核 */
    @Test
    void normalWithLowConfidenceSubmitsLowConfidenceReview() {
        handle(json("normal", "low", "各项指标正常", "N/A", "N/A"), NORMAL_SNAP, false);

        verify(diagnosisService, times(1)).submitEscalated(
                eq(DiagnosisTask.Trigger.LOW_CONFIDENCE), eq("NORMAL|"), eq(NORMAL_SNAP), anyString());
    }

    /** JSON 缺 confidence 字段：默认 medium，warning NEW 无写建议 → 不升级（向后兼容） */
    @Test
    void missingConfidenceDefaultsToMediumAndNoEscalation() {
        String reply = "{\"status\":\"warning\",\"summary\":\"轻度 CPU 波动\","
                + "\"rootCause\":\"瞬时波动\",\"suggestion\":\"继续观察\"}";
        handle(reply, Map.of("cpuUsage", 0.5, "heapUsage", 0.2, "qpsLast1m", 3.0,
                "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1), false);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 第二轮 ACTIVE（同指纹）：不再走全量报告改走心跳，且首见即本轮不满足 5 分钟 → 不升级 */
    @Test
    void secondRoundSameFingerprintGoesHeartbeatWithoutEscalation() {
        String reply = json("critical", "high", "死锁", "存在死锁", "dump 线程栈（只读）后人工分析");
        handle(reply, DEADLOCK_SNAP, false); // NEW
        handle(reply, DEADLOCK_SNAP, false); // ACTIVE（0 分钟，未到 persistent 阈值）

        verify(reporter, times(1)).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verify(reporter, times(1)).logHeartbeat(any());
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }
}
