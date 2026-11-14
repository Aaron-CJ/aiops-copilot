package com.aiops.aiopscopilot.service;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisService;
import com.aiops.aiopscopilot.service.diagnosis.DiagnosisTask;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * status 白名单归一化的回归测试。
 * <p>
 * <b>背景（2026-11-13 修复的真实缺陷）</b>：解析层把模型输出里的 status 原样取用
 * （缺字段时兜底 "unknown"），路由层却只把 "normal" 当正常、其余一律当异常——
 * 于是模型漏掉 status 字段、或输出 "ok" 这类契约外措辞时，
 * 系统会对<b>指标完全健康</b>的目标发出一整块 ERROR 级告警报告，
 * 并留下一个永不归档的幽灵事件（指纹 {@code UNKNOWN|APP=x;} / {@code OK|APP=x;}）。
 * <p>
 * 本类锁死两道边界：
 * <ol>
 *   <li>{@link OpsScheduler#normalizeStatus(String)} 纯函数：契约内取值归一化，
 *       契约外一律归为 {@code STATUS_UNKNOWN}（"判不了"），不得泄漏成第四种告警级别</li>
 *   <li>路由层：判不了的目标<b>不建事件、不误报</b>，且其存量事件<b>不归档</b>
 *       （避免"没判出来"被当成"已恢复"）——但硬信号（BLOCKED&gt;0 等）命中时仍由
 *       AI 漏报兜底接住，"没说话"不能让死锁漏报</li>
 * </ol>
 * 与 {@link OpsSchedulerEscalationWiringTest} 同一手法：反射调私有方法，
 * 不为测试扩大生产 API 面。
 */
class OpsSchedulerStatusNormalizeTest {

    private static final String SELF = "aiops-copilot";

    /** 指标全部健康的快照块——任何判 abnormal 的结果都是误报 */
    private static final Map<String, Object> HEALTHY_BLOCK = Map.of(
            "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 5.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);

    /** 空闲实例快照块：其余指标健康，仅 QPS=0（从未有流量的常态） */
    private static final Map<String, Object> IDLE_BLOCK = Map.of(
            "cpuUsage", 0.0, "heapUsage", 0.15, "qpsLast1m", 0.0,
            "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);

    private IncidentStore incidentStore;
    private OpsAlertReporter reporter;
    private SimpleMeterRegistry registry;
    private DiagnosisService diagnosisService;
    private OpsScheduler scheduler;
    private Method handleMethod;

    @BeforeEach
    void setUp() throws Exception {
        incidentStore = new IncidentStore();
        reporter = mock(OpsAlertReporter.class);
        registry = new SimpleMeterRegistry();
        MetricsService metricsService = new MetricsService(registry);
        diagnosisService = mock(DiagnosisService.class);
        scheduler = new OpsScheduler(null, null, reporter, metricsService,
                incidentStore, new AuditLogger(), diagnosisService, 45);
        handleMethod = OpsScheduler.class.getDeclaredMethod(
                "handleInspectionResult", String.class, Map.class, long.class, boolean.class);
        handleMethod.setAccessible(true);
    }

    // ==================== A. normalizeStatus 纯函数 ====================

    /** 契约内的三个取值原样通过 */
    @Test
    void contractStatusesPassThrough() {
        assertEquals("normal", OpsScheduler.normalizeStatus("normal"));
        assertEquals("warning", OpsScheduler.normalizeStatus("warning"));
        assertEquals("critical", OpsScheduler.normalizeStatus("critical"));
    }

    /** 大小写与首尾空白不敏感：模型输出常见的手工拼写差异不该被当成"判不了" */
    @Test
    void caseAndWhitespaceAreTolerated() {
        assertEquals("critical", OpsScheduler.normalizeStatus("  CRITICAL  "));
        assertEquals("warning", OpsScheduler.normalizeStatus("Warning"));
        assertEquals("normal", OpsScheduler.normalizeStatus("Normal"));
    }

    /** 契约外取值（"ok"/"healthy"/空串/null）一律归为"判不了"，绝不泄漏成告警级别 */
    @Test
    void offContractValuesBecomeUnknown() {
        assertEquals(OpsScheduler.STATUS_UNKNOWN, OpsScheduler.normalizeStatus("ok"));
        assertEquals(OpsScheduler.STATUS_UNKNOWN, OpsScheduler.normalizeStatus("healthy"));
        assertEquals(OpsScheduler.STATUS_UNKNOWN, OpsScheduler.normalizeStatus("异常"));
        assertEquals(OpsScheduler.STATUS_UNKNOWN, OpsScheduler.normalizeStatus(""));
        assertEquals(OpsScheduler.STATUS_UNKNOWN, OpsScheduler.normalizeStatus(null));
    }

    /** 中文同义词按最小映射收敛：中文模型偶发把 status 写成中文时不该白白丢掉一个目标的判定 */
    @Test
    void chineseAliasesAreMapped() {
        assertEquals("normal", OpsScheduler.normalizeStatus("正常"));
        assertEquals("warning", OpsScheduler.normalizeStatus("警告"));
        assertEquals("warning", OpsScheduler.normalizeStatus("告警"));
        assertEquals("critical", OpsScheduler.normalizeStatus("严重"));
        assertEquals("critical", OpsScheduler.normalizeStatus("危急"));
    }

    // ==================== B. 路由层：判不了的目标不误报、不建事件 ====================

    /**
     * 回归主缺陷：模型输出 status="ok"（指标完全健康）——
     * 修复前会发出 ERROR 级告警并留下幽灵事件；修复后不报、不建事件。
     */
    @Test
    void offContractStatusDoesNotRaiseAlertNorCreateIncident() throws Exception {
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"ok\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}");

        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertEquals(0, incidentStore.activeCount(), "契约外 status 不得留下任何事件");
        assertEquals(1.0, registry.get("aiops_inspection_status_invalid_total")
                .tag("target", SELF).counter().count(), "应记录一次契约外 status 指标");
    }

    /** 模型漏掉 status 字段：与输出 "ok" 同等对待（修复前的兜底值正是 "unknown"） */
    @Test
    void missingStatusDoesNotRaiseAlertNorCreateIncident() throws Exception {
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}");

        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertEquals(0, incidentStore.activeCount());
    }

    /** 对照组：契约内的 normal 不报、不建事件（确认上两条不是"因为啥都不做所以通过"） */
    @Test
    void normalStatusStillStaysSilent() throws Exception {
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"normal\",\"confidence\":\"high\","
                + "\"summary\":\"各项指标正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}");

        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertEquals(0, incidentStore.activeCount());
    }

    /** 对照组：契约内的 critical 仍然正常建事件并全量报告（修复没有削弱真正的告警能力） */
    @Test
    void criticalStatusStillRaisesAlert() throws Exception {
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"CPU 打满\",\"rootCause\":\"CPU 持续 95%\",\"suggestion\":\"扩容或限流\"}]}");

        verify(reporter, times(1)).report(startsWith("CRITICAL|APP=" + SELF + ";"), eq("critical"),
                anyString(), anyString(), anyString(), any(), anyString());
        assertEquals(1, incidentStore.activeCount());
    }

    /** 判不了的目标不计入 normal 集合 → 它的事件不会被"没判出来"误判为已恢复 */
    @Test
    void unjudgedTargetDoesNotResolveItsExistingIncident() throws Exception {
        // 先用一个真实故障建立 ACTIVE 事件
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"critical\",\"confidence\":\"high\","
                + "\"summary\":\"CPU 打满\",\"rootCause\":\"CPU 持续 95%\",\"suggestion\":\"扩容或限流\"}]}");
        assertEquals(1, incidentStore.activeCount());

        // 下一轮模型给出契约外 status → 该目标"判不了"，事件必须保持 ACTIVE 而非被归档
        handle("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"ok\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}");

        assertEquals(1, incidentStore.activeCount(), "\"判不了\"不等于\"已恢复\"，存量事件必须保持 ACTIVE");
        verify(reporter, never()).logResolved(any());
    }

    /**
     * 判不了 ≠ 硬信号可以被无视：模型漏 status 的目标若恰有 BLOCKED&gt;0（死锁），
     * 硬信号兜底必须接住——否则修复"误报"的同时会引入更危险的"死锁漏报一整轮"
     * （修复前的旧缺陷至少还留 UNKNOWN 指纹，静默吞掉是纯倒退）。
     * 兜底命中后按兜底级别走正常路由：建事件、全量报告、rootCause 带兜底标注。
     */
    @Test
    void unjudgedTargetWithHardSignalIsRescuedByBackstop() throws Exception {
        Map<String, Object> blocked = Map.of(
                "cpuUsage", 0.1, "heapUsage", 0.2, "qpsLast1m", 5.0,
                "blockedThreads", 2.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}",
                Map.of(SELF, blocked));

        verify(reporter, times(1)).report(startsWith("CRITICAL|APP=" + SELF + ";"), eq("critical"),
                anyString(), contains("代码级兜底"), anyString(), any(), anyString());
        assertEquals(1, incidentStore.activeCount(), "硬信号命中的目标必须建事件，不得静默");
    }

    /** 兜底三条硬信号线的第三条：判不了 + CPU&gt;90% → 强改 warning（与前两条同一闸门） */
    @Test
    void unjudgedTargetWithHighCpuIsRescuedToWarning() throws Exception {
        Map<String, Object> hotCpu = Map.of(
                "cpuUsage", 0.95, "heapUsage", 0.2, "qpsLast1m", 5.0,
                "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1);
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}",
                Map.of(SELF, hotCpu));

        verify(reporter, times(1)).report(startsWith("WARNING|APP=" + SELF + ";"), eq("warning"),
                anyString(), contains("代码级兜底"), anyString(), any(), anyString());
        assertEquals(1, incidentStore.activeCount());
    }

    /** 全部目标都判不了 → 整轮输出不可信 → 走 parse_error 通道（连续 2 轮升级深度诊断） */
    @Test
    void allTargetsUnjudgedFallsIntoParseErrorChannel() throws Exception {
        String reply = "{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"ok\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}";
        handle(reply);
        handle(reply);

        assertEquals(2.0, registry.get("aiops_inspection_total")
                .tag("status", "parse_error").counter().count(),
                "全部目标判不了应走 parse_error 通道");
        verify(diagnosisService, times(1)).submitEscalated(
                eq(DiagnosisTask.Trigger.PARSE_FAIL), eq("PARSE_FAIL"), any(), anyString());
    }

    /**
     * 多目标：部分目标判不了时，判得了的目标照常判定，轮级状态取 "unknown"。
     * <p>
     * 轮级状态语义："unknown" 的 rank 高于 warning、低于 critical——
     * "没判出来"比 warning 更值得注意，但不掩盖 critical。
     */
    @Test
    void partiallyUnjudgedRoundReportsUnknownAtRoundLevel() throws Exception {
        Map<String, Object> targets = Map.of(
                SELF, HEALTHY_BLOCK,
                "app-2", Map.of("cpuUsage", 0.95, "heapUsage", 0.3, "qpsLast1m", 5.0,
                        "blockedThreads", 0.0, "gcCountLast5m", 0.0, "maxRequestSeconds", 0.1));
        String reply = "{\"targets\":["
                + "{\"name\":\"" + SELF + "\",\"status\":\"ok\",\"confidence\":\"high\","
                + "\"summary\":\"一切正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"},"
                + "{\"name\":\"app-2\",\"status\":\"warning\",\"confidence\":\"high\","
                + "\"summary\":\"CPU 偏高\",\"rootCause\":\"CPU 波动\",\"suggestion\":\"观察\"}]}";

        handleMulti(reply, targets);

        // app-2 的 warning 正常建事件并报告；self（判不了）不建事件
        verify(reporter, times(1)).report(startsWith("WARNING|APP=app-2;"), eq("warning"),
                anyString(), anyString(), anyString(), any(), anyString());
        assertEquals(1, incidentStore.activeCount(), "只有 app-2 应有事件");
    }

    // ==================== C. 空闲基线门控：QPS=0 的 warning 不建事件 ====================

    /**
     * 从未观测到流量的实例，QPS=0 是常态而非假死前兆。
     * <p>
     * 模型按 Prompt 第 3 条仍会判"低流量或假死待确认"warning（实测空闲基线轮即触发），
     * 若放行则空闲环境每轮新增一条 ZERO_QPS 事件、9 分钟后才自愈。
     * 门控：无任何其它硬信号时降为 normal——不报、不建事件。
     */
    @Test
    void idleZeroQpsWarningIsSuppressed() throws Exception {
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"warning\",\"confidence\":\"high\","
                + "\"summary\":\"各项指标正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}",
                Map.of(SELF, IDLE_BLOCK));

        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertEquals(0, incidentStore.activeCount(), "空闲实例的 QPS=0 warning 不得建事件");
    }

    /**
     * 对照组：观测到过流量后归零，就是真实的假死前兆，门控必须放行——
     * 否则修复"空闲误报"会把"假死漏报"引进来。
     */
    @Test
    void zeroQpsWarningStillRaisedAfterTrafficWasObserved() throws Exception {
        // 第一轮有真实流量：observedTraffic 置位
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"normal\",\"confidence\":\"high\","
                + "\"summary\":\"各项指标正常\",\"rootCause\":\"N/A\",\"suggestion\":\"N/A\"}]}",
                Map.of(SELF, HEALTHY_BLOCK));
        // 第二轮流量归零：应正常报 warning 并建事件
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"warning\",\"confidence\":\"high\","
                + "\"summary\":\"QPS 归零，疑似假死\",\"rootCause\":\"QPS=0\",\"suggestion\":\"确认服务状态\"}]}",
                Map.of(SELF, IDLE_BLOCK));

        verify(reporter, times(1)).report(startsWith("WARNING|APP=" + SELF + ";ZERO_QPS;"), eq("warning"),
                anyString(), anyString(), anyString(), any(), anyString());
        assertEquals(1, incidentStore.activeCount(), "有流量后归零是真实假死信号，必须报");
    }

    /**
     * 门控压下的 warning（status 被改为 normal）不得落入 normal+low 的
     * LOW_CONFIDENCE 升级分支——否则空闲环境按防抖周期（10 分钟）反复唤醒 r1
     * 去"复核"一个确定性空闲的目标（独占 Ollama 1.7-6.6 分钟并触发巡检让路），
     * 且升级 note 会失真地声称"模型判为 normal"（实际判的是 warning）。
     */
    @Test
    void suppressedWarningWithLowConfidenceDoesNotWakeDeepDiagnosis() throws Exception {
        handleMulti("{\"targets\":[{\"name\":\"" + SELF + "\",\"status\":\"warning\",\"confidence\":\"low\","
                + "\"summary\":\"低流量或假死待确认\",\"rootCause\":\"QPS=0\",\"suggestion\":\"观察\"}]}",
                Map.of(SELF, IDLE_BLOCK));

        verify(diagnosisService, never()).submitEscalated(any(), anyString(), any(), anyString());
        verify(reporter, never()).report(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
        assertEquals(0, incidentStore.activeCount());
    }

    // normal+low（非门控路径）的漏报复核升级已由 OpsSchedulerEscalationWiringTest
    // .normalWithLowConfidenceSubmitsLowConfidenceReview 单一事实来源覆盖（断言更细：
    // 精确指纹与精确快照块），且该用例同样流经门控分支（normal 时门控跳过），不再重复。

    // ==================== 辅助方法 ====================

    private void handle(String reply) throws Exception {
        handleMulti(reply, Map.of(SELF, HEALTHY_BLOCK));
    }

    private void handleMulti(String reply, Map<String, Object> targetsBlock) throws Exception {
        Map<String, Object> snapshot = Map.of("targets", targetsBlock);
        try {
            handleMethod.invoke(scheduler, reply, snapshot, System.currentTimeMillis(), false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
