package com.aiops.aiopscopilot.service;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.IncidentStore.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件状态机与两条代码兜底路径的纯单元测试（不依赖 Spring 容器与 LLM）。
 * <p>
 * 覆盖三块逻辑：
 * <ol>
 *   <li>{@link IncidentStore} 状态机去重（NEW → ACTIVE → RESOLVED、复发重新 NEW、指纹构造）</li>
 *   <li>{@link OpsScheduler#applyThresholdBackstop} AI 漏报兜底（normal 被硬指标强改）</li>
 *   <li>{@link OpsScheduler#fallbackByThreshold} Ollama 降级路径（阈值规则 + JSON 拼装）</li>
 * </ol>
 * 后两者是 OpsScheduler 的 private 方法，用反射调用——不为测试改生产方法可见性；
 * 构造器其余依赖传 null，因为这两条路径根本不会触达它们。
 */
class IncidentStoreTest {

    // ==================== A. IncidentStore 状态机去重 ====================

    @Test
    void incidentStore_firstOccurrence_isNew() {
        IncidentStore store = new IncidentStore();
        Incident inc = store.recordOrUpdate("CRITICAL|存在死锁", "critical",
                "死锁持续中", "存在死锁", "重启应用");

        assertEquals(IncidentStatus.NEW, inc.status(), "首次出现应为 NEW");
        assertTrue(inc.isNew(), "isNew() 应为 true");
        assertEquals(1, store.activeCount(), "NEW 应计入 activeCount（NEW+ACTIVE）");
    }

    @Test
    void incidentStore_secondOccurrence_isActiveNotNew() {
        IncidentStore store = new IncidentStore();
        Map<String, Object> snap = new HashMap<>();
        snap.put("blockedThreads", 2.0);
        snap.put("deadlockDiagnosis", "deadlock detected");
        snap.put("cpuUsage", 0.5);
        snap.put("heapUsage", 0.4);
        snap.put("qpsLast1m", 100.0);
        snap.put("gcCountLast5m", 0.0);
        String fp = IncidentStore.fingerprintOf("critical", snap);

        Incident first = store.recordOrUpdate(fp, "critical", "首次", "存在死锁", "重启");
        Incident second = store.recordOrUpdate(fp, "critical", "持续中", "存在死锁", "重启");

        assertEquals(IncidentStatus.NEW, first.status());
        assertEquals(IncidentStatus.ACTIVE, second.status(), "第二次出现应升为 ACTIVE");
        assertFalse(second.isNew(), "第二次不应是 isNew");
        assertEquals(first.firstSeen(), second.firstSeen(), "firstSeen 应保留");
    }

    @Test
    void incidentStore_threeNormals_resolves() {
        IncidentStore store = new IncidentStore();
        String fp = "WARNING|CPU 高";

        store.recordOrUpdate(fp, "warning", "CPU 高", "CPU 92%", "扩容");

        // 第 1 轮 normal：未达阈值（需 3 轮）
        List<Incident> r1 = store.bumpNormalAndResolve();
        assertTrue(r1.isEmpty(), "第 1 轮 normal 不应 RESOLVED");

        // 第 2 轮 normal：未达阈值
        List<Incident> r2 = store.bumpNormalAndResolve();
        assertTrue(r2.isEmpty(), "第 2 轮 normal 不应 RESOLVED");

        // 第 3 轮 normal：达到阈值 → RESOLVED
        List<Incident> r3 = store.bumpNormalAndResolve();
        assertEquals(1, r3.size(), "第 3 轮 normal 应有 1 个 RESOLVED");
        assertEquals(IncidentStatus.RESOLVED, r3.get(0).status());
        assertEquals(0, store.activeCount(), "RESOLVED 后 activeCount 应为 0");
    }

    @Test
    void incidentStore_recurrence_afterResolved_isNewAgain() {
        IncidentStore store = new IncidentStore();
        String fp = "CRITICAL|死锁";

        // 首次 → NEW
        store.recordOrUpdate(fp, "critical", "首次", "死锁", "重启");
        // 3 轮 normal → RESOLVED
        for (int i = 0; i < 3; i++) store.bumpNormalAndResolve();
        // 复发：同指纹再次出现，应作为新事件 NEW
        Incident recur = store.recordOrUpdate(fp, "critical", "复发", "死锁", "重启");

        assertEquals(IncidentStatus.NEW, recur.status(), "RESOLVED 后复发应重新置 NEW");
        assertTrue(recur.isNew());
    }

    @Test
    void incidentStore_active_normalResetsCounter() {
        // 故障持续中时新一轮 normal 应重置计数（不能继续上次累计）
        IncidentStore store = new IncidentStore();
        String fp = "WARNING|CPU";

        store.recordOrUpdate(fp, "warning", "CPU", "CPU 91%", "扩容");
        store.bumpNormalAndResolve(); // 1 轮 normal
        // 故障再次出现：recordOrUpdate 把计数重置为 0
        store.recordOrUpdate(fp, "warning", "CPU", "CPU 92%", "扩容");
        List<Incident> r = store.bumpNormalAndResolve();
        assertTrue(r.isEmpty(), "故障再次出现后 normal 计数应从头开始，不应立即 RESOLVED");
    }

    // ==================== B. OpsScheduler.applyThresholdBackstop（AI 漏报兜底） ====================

    @Test
    void backstop_aiNormal_cpuAbove90_forceWarning() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.92);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);

        String result = invokeBackstop(sched, "normal", snap);

        assertEquals("warning", result, "AI 判 normal 但 CPU>90% 应强改 warning");
    }

    @Test
    void backstop_aiNormal_blockedAbove0_forceCritical() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.5);
        snap.put("blockedThreads", 2.0);
        snap.put("heapUsage", 0.5);

        String result = invokeBackstop(sched, "normal", snap);

        assertEquals("critical", result, "AI 判 normal 但 BLOCKED>0 应强改 critical");
    }

    @Test
    void backstop_aiNormal_heapAbove95_forceCritical() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.5);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.97);

        String result = invokeBackstop(sched, "normal", snap);

        assertEquals("critical", result, "AI 判 normal 但堆>95% 应强改 critical");
    }

    @Test
    void backstop_aiWarning_cpuAbove90_staysWarning() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.92);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);

        // AI 已判 warning（非 normal），不应被兜底改判
        String result = invokeBackstop(sched, "warning", snap);
        assertEquals("warning", result);
    }

    @Test
    void backstop_aiNormal_allMetricsNormal_staysNormal() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.5);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);

        String result = invokeBackstop(sched, "normal", snap);
        assertEquals("normal", result, "所有指标正常时兜底不应介入");
    }

    // ==================== C. OpsScheduler.fallbackByThreshold（Ollama 降级路径） ====================

    @Test
    void fallback_blockedAbove0_returnsCritical() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.5);
        snap.put("blockedThreads", 2.0);
        snap.put("heapUsage", 0.5);
        snap.put("qpsLast1m", 100.0);
        snap.put("gcCountLast5m", 0.0);

        String json = invokeFallback(sched, snap);

        assertTrue(json.contains("\"status\":\"critical\""), "BLOCKED>0 应降级为 critical: " + json);
        assertTrue(json.contains("代码级兜底"), "rootCause 应含代码级兜底标注");
    }

    @Test
    void fallback_cpuAbove90_returnsWarning() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.95);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);
        snap.put("qpsLast1m", 50.0);
        snap.put("gcCountLast5m", 2.0);

        String json = invokeFallback(sched, snap);

        assertTrue(json.contains("\"status\":\"warning\""), "CPU>90% 应降级为 warning: " + json);
    }

    @Test
    void fallback_qpsZero_returnsCritical() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.5);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);
        snap.put("qpsLast1m", 0.0);
        snap.put("gcCountLast5m", 0.0);

        String json = invokeFallback(sched, snap);

        assertTrue(json.contains("\"status\":\"critical\""), "QPS=0 应降级为 critical（应用假死）: " + json);
    }

    @Test
    void fallback_allNormal_returnsNormal() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.3);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.4);
        snap.put("qpsLast1m", 50.0);
        snap.put("gcCountLast5m", 1.0);

        String json = invokeFallback(sched, snap);

        assertTrue(json.contains("\"status\":\"normal\""), "所有指标正常应返回 normal: " + json);
    }

    @Test
    void fallback_gcAbove10_returnsWarning() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.3);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.4);
        snap.put("qpsLast1m", 50.0);
        snap.put("gcCountLast5m", 15.0);

        String json = invokeFallback(sched, snap);

        assertTrue(json.contains("\"status\":\"warning\""), "5min GC>10 应降级为 warning: " + json);
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个依赖基本为 null 的 OpsScheduler——只用于测试纯逻辑的私有方法。
     * audit 传真实实例（AuditLogger 无 Spring 依赖，可直接 new），避免 NPE。
     */
    private OpsScheduler newSchedulerWithNulls() {
        return new OpsScheduler(null, null, null, null, null, new AuditLogger(), null);
    }

    private String invokeBackstop(OpsScheduler sched, String status, Map<String, Object> snap) throws Exception {
        Method m = OpsScheduler.class.getDeclaredMethod(
                "applyThresholdBackstop", String.class, Map.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(sched, status, snap, "original RC");
    }

    private String invokeFallback(OpsScheduler sched, Map<String, Object> snap) throws Exception {
        Method m = OpsScheduler.class.getDeclaredMethod("fallbackByThreshold", Map.class);
        m.setAccessible(true);
        return (String) m.invoke(sched, snap);
    }
}
