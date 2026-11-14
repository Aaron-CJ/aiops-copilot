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
import static org.mockito.Mockito.*;

/**
 * 4.3 升级接线测试：验证 OpsScheduler.handleInspectionResult 与 DiagnosisService 的实际交互。
 * <p>
 * 纯函数规则（选哪个 trigger）由 EscalationRuleTest 覆盖；本类聚焦有状态行为：
 * parse_fail 连续计数/成功归零/degraded 不升级/各路径是否真的提交了升级任务，
 * 以及多目标判定数组的逐目标路由与 APP 前缀指纹隔离。
 * 依赖：DiagnosisService、OpsAlertReporter、MetricsService 用 Mockito mock，
 * IncidentStore 用真实实例（状态机行为本身已被 IncidentStoreTest 覆盖，这里需要它真的记事件）。
 * <p>
 * 快照形态：生产快照为 {targets:{<目标名>:块}}；本测试的 handle() 自动把单目标指标块
 * 包装成单目标快照（键 aiops-copilot），多目标用例直接构造多键快照。
 */
class OpsSchedulerEscalationWiringTest {

    private DiagnosisService diagnosisService;
    private OpsAlertReporter reporter;
    private MetricsService metricsService;
    private OpsScheduler scheduler;
    private Method handleMethod;

    private static final Map<String, Object> NORMAL_BLOCK = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 5.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
    private static final Map<String, Object> DEADLOCK_BLOCK = Map.of(
            "cpuUsage", 0.2, "heapUsage", 0.3, "qpsLast1m", 1.0,
            "blockedThreads", 2.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.2);
    private static final Map<String, Object> TRAFFIC_BLOCK = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 50.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
    private static final Map<String, Object> ZERO_QPS_BLOCK = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 0.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
    private static final Map<String, Object> QUERY_FAILED_BLOCK = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", -1,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
    private static final String SELF = "aiops-copilot";

    @BeforeEach
    void setUp() throws Exception {
        diagnosisService = mock(DiagnosisService.class);
        reporter = mock(OpsAlertReporter.class);
        metricsService = mock(MetricsService.class);
        AuditLogger audit = new AuditLogger();
        scheduler = new OpsScheduler(null, null, reporter, metricsService,
                new IncidentStore(), audit, diagnosisService, 45);
        handleMethod = OpsScheduler.class.getDeclaredMethod(
                "handleInspectionResult", String.class, Map.class, long.class, boolean.class);
        handleMethod.setAccessible(true);
    }

    /** 把单目标指标块包装成生产形态的单目标快照，再反射调用 handleInspectionResult */
    private void handle(String reply, Map<String, Object> selfBlock, boolean degraded) {
        handleMulti(reply, Map.of(SELF, selfBlock), degraded);
    }

    private void handleMulti(String reply, Map<String, Object> targetsBlock, boolean degraded) {
        Map<String, Object> snapshot = Map.of("targets", targetsBlock);
        try {
            handleMethod.invoke(scheduler, reply, snapshot, System.currentTimeMillis(), degraded);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 反射调 handleDegraded：走阈值兜底路径（Ollama 不可用形态），trackUnavailable=true */
    private void handleDegraded(Map<String, Object> selfBlock) {
        try {
            Method m = OpsScheduler.class.getDeclaredMethod(
                    "handleDegraded", Map.class, long.class, boolean.class);
            m.setAccessible(true);
            m.invoke(scheduler, Map.of("targets", Map.of(SELF, selfBlock)),
                    System.currentTimeMillis(), true);
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

    /**
     * （2026-11-13 实测发现）：qwen3 偶发省略 {@code {"targets":...}} 外壳直接输出裸数组——
     * 元素结构与 targets 数组一致，解析层必须尽力接受而非白扔一轮进 parse_error
     * （连续 2 轮就升级分钟级 r1 深度诊断，代价高）。
     */
    @Test
    void bareArrayOutputWithoutTargetsWrapperIsAccepted() {
        String reply = "[{\"name\":\"" + SELF + "\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"CPU 打满\",\"rootCause\":\"CPU 持续 95%\",\"suggestion\":\"扩容或限流\"}]";
        handle(reply, NORMAL_BLOCK, false);

        verify(reporter, times(1)).report(startsWith("CRITICAL|APP=" + SELF), eq("critical"),
                anyString(), anyString(), anyString(), any(), anyString());
    }

    /** 首轮解析失败：计数 1，不升级 */
    @Test
    void singleParseFailureDoesNotEscalate() {
        handle("这不是 JSON", NORMAL_BLOCK, false);
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 连续第 2 轮解析失败：恰好升级一次 PARSE_FAIL，note 带原始输出 */
    @Test
    void twoConsecutiveParseFailuresEscalateOnceWithRawOutput() {
        handle("坏输出#1", NORMAL_BLOCK, false);
        handle("坏输出#2", NORMAL_BLOCK, false);

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
        handle("坏#1", NORMAL_BLOCK, false);
        handle("坏#2", NORMAL_BLOCK, false); // 触发并归零
        handle("坏#3", NORMAL_BLOCK, false); // 新一轮计数 1

        verify(diagnosisService, times(1)).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 成功解析一轮后计数归零：失败-成功-失败 不升级 */
    @Test
    void successfulRoundResetsParseFailureCounter() {
        handle("坏", NORMAL_BLOCK, false);
        handle(json("normal", "high", "各项指标正常", "N/A", "N/A"), NORMAL_BLOCK, false);
        handle("又坏了", NORMAL_BLOCK, false);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 升级 note 中的原始输出截断到 1000 字符，防止 r1 prompt 被超长坏输出灌爆 */
    @Test
    void parseFailRawOutputIsTruncatedToThousandChars() {
        String huge = "X".repeat(1200);
        handle(huge, NORMAL_BLOCK, false);
        handle(huge, NORMAL_BLOCK, false);

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
        handle("坏", NORMAL_BLOCK, true);
        handle("坏", NORMAL_BLOCK, true);
        handle("坏", NORMAL_BLOCK, true);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /**
     * （2026-11-13）：两路径 QPS 归零规则统一——AI 轮观测到 qps=-1（查询失败）也必须打断
     * "连续零流量"序列。修复前只有 qps&gt;0 才归零：降级轮 QPS=0（计数 1）→ AI 轮查询失败
     * （计数残留）→ 下一个降级轮 QPS=0 计数到 2，直接跳级 critical 假死。
     * 修复后与降级路径同口径：qps≠0（&gt;0 有流量 / &lt;0 查询失败）都打断——
     * "没观测到"不能充当连续假死的一环。
     */
    @Test
    void qpsQueryFailureBreaksConsecutiveZeroQpsChain() {
        // 1. 基线：观测到真实流量（qps>0 同时建立 observedTraffic 门控）
        handle(json("normal", "high", "各项指标正常", "N/A", "N/A"), TRAFFIC_BLOCK, false);
        // 2. 降级轮 QPS=0：计数 1 → warning 待确认
        handleDegraded(ZERO_QPS_BLOCK);
        // 3. AI 轮指标拉取失败（qps=-1 哨兵）：修复后必须打断连续计数
        handle(json("normal", "high", "各项指标正常", "N/A", "N/A"), QUERY_FAILED_BLOCK, false);
        // 4. 降级轮 QPS=0：计数应从 0 重新开始 → 仍是 warning，而非残留计数直接跳 critical
        handleDegraded(ZERO_QPS_BLOCK);

        // 降级路径 rawReply=null（不走模型），matcher 用 any() 而非 anyString()
        verify(reporter, never()).report(anyString(), eq("critical"), anyString(),
                anyString(), anyString(), any(), any());
        verify(reporter, atLeastOnce()).report(startsWith("WARNING|APP=" + SELF), eq("warning"),
                anyString(), anyString(), anyString(), any(), any());
    }

    /**
     * 降级路径的 critical + 写建议（兜底建议固定含"重启 Ollama"）也不升级——
     * 与 parse_fail 分支同一守卫：Ollama 已不可用，r1 同样调不动。
     */
    @Test
    void degradedCriticalWithWriteSuggestionNeverEscalates() {
        String degradedReply = json("critical", "medium",
                "阈值兜底：存在 BLOCKED 线程", "代码级兜底：疑似死锁",
                "Ollama 不可用，建议人工介入或重启 Ollama 服务");
        handle(degradedReply, DEADLOCK_BLOCK, true);

        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /**
     * LLM 连续 3 轮降级 → 显式 LLM_UNAVAILABLE 告警（report 一次）；
     * 继续降级走心跳，且降级 normal 轮不 bumpNormal——事件不得被"降级 normal"错误归档。
     */
    @Test
    void threeDegradedRoundsRaiseLlmUnavailableIncident() {
        for (int i = 0; i < 3; i++) {
            handleDegraded(NORMAL_BLOCK);
        }
        verify(reporter, times(1)).report(eq("CRITICAL|LLM_UNAVAILABLE;"), eq("critical"),
                anyString(), anyString(), anyString(), any(), any());

        // 第 4、5 轮继续降级：同指纹心跳而非重复报告；绝无 RESOLVED（盲期不归档）
        handleDegraded(NORMAL_BLOCK);
        handleDegraded(NORMAL_BLOCK);
        verify(reporter, times(2)).logHeartbeat(any());
        verify(reporter, never()).logResolved(any());
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** critical NEW + 写操作建议：提交 CRITICAL_WRITE 升级，指纹对应 APP 前缀 + BLOCKED=2 */
    @Test
    void criticalWithWriteSuggestionSubmitsCriticalWrite() {
        String reply = json("critical", "high",
                "发现 2 个 BLOCKED 线程", "存在死锁", "建议立即重启应用解除死锁");
        handle(reply, DEADLOCK_BLOCK, false);

        ArgumentCaptor<String> trigger = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(diagnosisService, times(1)).submitEscalated(
                trigger.capture(), fp.capture(), eq(DEADLOCK_BLOCK), anyString());
        assertEquals(DiagnosisTask.Trigger.CRITICAL_WRITE, trigger.getValue());
        assertTrue(fp.getValue().contains("APP=" + SELF + ";"), "指纹应含 APP 前缀: " + fp.getValue());
        assertTrue(fp.getValue().contains("BLOCKED=2"), "指纹应含 BLOCKED=2: " + fp.getValue());
    }

    /** normal + confidence=low：提交 LOW_CONFIDENCE 漏报复核，指纹带 APP 前缀 */
    @Test
    void normalWithLowConfidenceSubmitsLowConfidenceReview() {
        handle(json("normal", "low", "各项指标正常", "N/A", "N/A"), NORMAL_BLOCK, false);

        verify(diagnosisService, times(1)).submitEscalated(
                eq(DiagnosisTask.Trigger.LOW_CONFIDENCE), eq("NORMAL|APP=" + SELF + ";"),
                eq(NORMAL_BLOCK), anyString());
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
        handle(reply, DEADLOCK_BLOCK, false); // NEW
        handle(reply, DEADLOCK_BLOCK, false); // ACTIVE（0 分钟，未到 persistent 阈值）

        verify(reporter, times(1)).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verify(reporter, times(1)).logHeartbeat(any());
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    // ==================== 多目标：逐目标路由与 APP 前缀隔离 ====================

    /** 双目标：self 正常 + app-2 critical → 仅 app-2 进状态机并全量报告（normal 静默），轮级状态取最差 */
    @Test
    void twoTargetsAreRoutedIntoIndependentIncidents() {
        Map<String, Object> targets = Map.of(
                SELF, NORMAL_BLOCK,
                "app-2", Map.of("cpuUsage", 0.95, "heapUsage", 0.3, "qpsLast1m", 5.0,
                        "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1));
        String reply = "{\"targets\":["
                + "{\"name\":\"" + SELF + "\",\"status\":\"normal\",\"confidence\":\"high\","
                + "\"summary\":\"各项指标正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"},"
                + "{\"name\":\"app-2\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"CPU 打满\",\"rootCause\":\"CPU 持续 95%\",\"suggestion\":\"扩容或限流\"}]}";
        handleMulti(reply, targets, false);

        // 仅 critical 的 app-2 走 NEW 全量报告，指纹带 APP 前缀；self 正常静默不进状态机
        verify(reporter, times(1)).report(startsWith("CRITICAL|APP=app-2;"), eq("critical"),
                anyString(), anyString(), anyString(), any(), anyString());
        // 轮级 metrics/审计取最差状态 critical
        verify(metricsService, times(1)).recordInspection(eq("critical"), anyLong());
    }

    /** 同一异常出现在两个目标 → 两条独立指纹（同形态、不同 APP 前缀），互不合并 */
    @Test
    void sameAnomalyOnTwoTargetsStaysIsolated() {
        Map<String, Object> blocked = Map.of("cpuUsage", 0.2, "heapUsage", 0.3, "qpsLast1m", 1.0,
                "blockedThreads", 2.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.2);
        Map<String, Object> targets = Map.of(SELF, blocked, "app-2", blocked);
        String perTarget = "{\"name\":\"%s\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"死锁\",\"rootCause\":\"存在死锁\",\"suggestion\":\"人工重启\"}";
        String reply = "{\"targets\":[" + String.format(perTarget, SELF) + ","
                + String.format(perTarget, "app-2") + "]}";
        handleMulti(reply, targets, false);

        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(reporter, times(2)).report(fp.capture(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertTrue(fp.getAllValues().get(0).startsWith("CRITICAL|APP=" + SELF + ";"));
        assertTrue(fp.getAllValues().get(1).startsWith("CRITICAL|APP=app-2;"),
                "同形态异常在不同目标必须是两条独立指纹");
        assertNotEquals(fp.getAllValues().get(0), fp.getAllValues().get(1));
    }

    /** 模型编造未知目标名：跳过并 WARN，不让幻觉目标进状态机；已知目标的 normal 判定静默 */
    @Test
    void unknownTargetNameIsSkipped() {
        String reply = "{\"targets\":["
                + "{\"name\":\"ghost\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"幻觉\",\"rootCause\":\"幻觉\",\"suggestion\":\"幻觉\"},"
                + "{\"name\":\"" + SELF + "\",\"status\":\"normal\",\"confidence\":\"high\","
                + "\"summary\":\"各项指标正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}";
        handle(reply, NORMAL_BLOCK, false);

        // 幻觉目标不产生任何报告；self 正常判定静默，轮级状态 normal
        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        verify(metricsService, times(1)).recordInspection(eq("normal"), anyLong());
        verify(diagnosisService, never()).submitEscalated(anyString(), anyString(), any(), any());
    }

    /** 旧单对象契约在多目标快照下必须拒绝（无法定位目标）→ parse_error 通道 */
    @Test
    void legacySingleObjectRejectedOnMultiTarget() {
        Map<String, Object> targets = Map.of(SELF, NORMAL_BLOCK, "app-2", NORMAL_BLOCK);
        handleMulti(json("normal", "high", "各项指标正常", "N/A", "N/A"), targets, false);
        handleMulti(json("normal", "high", "各项指标正常", "N/A", "N/A"), targets, false);

        verify(metricsService, times(2)).recordInspection(eq("parse_error"), anyLong());
        verify(diagnosisService, times(1)).submitEscalated(
                eq(DiagnosisTask.Trigger.PARSE_FAIL), eq("PARSE_FAIL"), any(), anyString());
    }
}
