package com.aiops.aiopscopilot.service;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.IncidentStore.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障注入评估集的确定性层回归（白皮书 §三，用例定义见
 * docs/aiops-fault-injection-eval.md）。
 * <p>
 * 每个用例对应一类注入端点产出的指标形态，验证"问题 → 期望行为"中
 * <b>不依赖 LLM</b> 的部分：指纹构造（{@link IncidentStore#fingerprintOf}）、
 * 阈值兜底（OpsScheduler#fallbackByThreshold）、瞬时尖峰的状态机抑制。
 * LLM 根因层（根因关键词命中）需真实 Ollama，按评估手册人工执行。
 * <p>
 * 用例编号 F1-F5 与评估手册表格一一对应；改 prompt/模型/工具后先跑本类，
 * 绿了再进入手册第 3 节的 LLM 层回归。
 */
class FaultInjectionEvaluationTest {

    // ==================== F1 死锁：BLOCKED>0 + 二级诊断字段 ====================

    @Test
    void f1_deadlock_fingerprintAndFallback() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("blockedThreads", 2.0);
        snap.put("deadlockDiagnosis", Map.of("deadlockDetected", true, "deadlockedThreadCount", 2));

        assertEquals("CRITICAL|DEADLOCK;BLOCKED=2;", IncidentStore.fingerprintOf("critical", snap),
                "死锁快照应产出 DEADLOCK+BLOCKED 特征指纹");

        String json = invokeFallback(snap);
        assertTrue(json.contains("\"status\":\"critical\""), "BLOCKED>0 兜底应判 critical: " + json);
        assertTrue(json.contains("死锁"), "根因应含死锁关键词: " + json);
    }

    // ==================== F2 内存泄漏：堆超限 + GC 频繁 ====================

    @Test
    void f2_memoryLeak_fingerprintAndFallback() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("heapUsage", 0.96);
        snap.put("gcCountLast5m", 15.0);

        assertEquals("CRITICAL|HIGH_HEAP=9;FREQUENT_GC;", IncidentStore.fingerprintOf("critical", snap),
                "内存泄漏快照应产出 HIGH_HEAP+FREQUENT_GC 特征指纹");

        String json = invokeFallback(snap);
        assertTrue(json.contains("\"status\":\"critical\""), "堆>0.95 兜底应判 critical: " + json);
        assertTrue(json.contains("堆内存"), "根因应含堆内存关键词: " + json);
        assertTrue(json.contains("内存泄漏"), "GC 频繁应指向内存泄漏: " + json);
    }

    // ==================== F3 应用假死：QPS=0 但指标可拉取 ====================

    @Test
    void f3_appStall_zeroQps_fingerprintAndFallback() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("qpsLast1m", 0.0);

        assertEquals("CRITICAL|ZERO_QPS;", IncidentStore.fingerprintOf("critical", snap),
                "QPS=0 应产出 ZERO_QPS 特征指纹");

        String json = invokeFallback(snap);
        assertTrue(json.contains("\"status\":\"critical\""), "QPS=0 兜底应判 critical: " + json);
        assertTrue(json.contains("假死"), "根因应含假死关键词: " + json);
    }

    // ==================== F4 慢接口：纯延迟无硬阈值（设计边界） ====================

    @Test
    void f4_slowRequest_latencyOnly_fallbackStaysNormal() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("maxRequestSeconds", 30.0);

        String json = invokeFallback(snap);
        assertTrue(json.contains("\"status\":\"normal\""),
                "纯延迟不应触发硬阈值兜底——慢接口是 LLM 语义层用例: " + json);

        // 同时记录当前指纹行为：纯延迟无指标特征，指纹退化为裸级别，
        // 同类 warning 会被合并为同一事件（LLM 层根因文本不参与指纹，设计如此）
        assertEquals("WARNING|", IncidentStore.fingerprintOf("warning", snap));
    }

    // ==================== F5 瞬时尖峰：单轮捕获后自动 RESOLVED，不产生持续告警 ====================

    @Test
    void f5_transientCpuSpike_capturedThenAutoResolved() {
        Map<String, Object> spikeSnap = baseSnapshot();
        spikeSnap.put("cpuUsage", 0.92);
        assertEquals("WARNING|HIGH_CPU=9;", IncidentStore.fingerprintOf("warning", spikeSnap),
                "CPU 0.92 应产出分桶后的 HIGH_CPU=9 特征指纹");

        IncidentStore store = new IncidentStore();
        Incident first = store.recordOrUpdate(
                IncidentStore.fingerprintOf("warning", spikeSnap),
                "warning", "CPU 尖峰", "CPU 92%", "观察");
        assertTrue(first.isNew(), "被单轮巡检捕获的尖峰应为 NEW");

        assertTrue(store.bumpNormalAndResolve().isEmpty(), "毛刺消失后第 1 轮 normal 不应 RESOLVED");
        assertTrue(store.bumpNormalAndResolve().isEmpty(), "第 2 轮 normal 不应 RESOLVED");
        List<Incident> resolved = store.bumpNormalAndResolve();
        assertEquals(1, resolved.size(), "第 3 轮 normal 应 RESOLVED——尖峰不升级为持续告警");
        assertEquals(IncidentStatus.RESOLVED, resolved.get(0).status());
        assertEquals(0, store.activeCount(), "RESOLVED 后不应残留活跃事件");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造"全部正常"形态的指标快照基底（各用例按需覆盖异常字段）。
     * 键集合与 PrometheusTool.queryFixedMetrics 对齐。
     */
    private static Map<String, Object> baseSnapshot() {
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.3);
        snap.put("heapUsage", 0.4);
        snap.put("qpsLast1m", 50.0);
        snap.put("blockedThreads", 0.0);
        snap.put("gcCountLast5m", 1.0);
        snap.put("maxRequestSeconds", 0.2);
        return snap;
    }

    /** 与 IncidentStoreTest 相同的手法：依赖为 null 的 OpsScheduler 只测纯逻辑私有方法。 */
    private String invokeFallback(Map<String, Object> snap) throws Exception {
        OpsScheduler sched = new OpsScheduler(null, null, null, null, null, new AuditLogger(), null);
        Method m = OpsScheduler.class.getDeclaredMethod("fallbackByThreshold", Map.class);
        m.setAccessible(true);
        return (String) m.invoke(sched, snap);
    }
}
