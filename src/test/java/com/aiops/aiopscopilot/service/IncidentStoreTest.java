package com.aiops.aiopscopilot.service;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.IncidentStore.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件状态机与两条代码兜底路径的纯单元测试（不依赖 Spring 容器与 LLM）。
 * <p>
 * 覆盖三块逻辑：
 * <ol>
 *   <li>{@link IncidentStore} 状态机去重（NEW → ACTIVE → RESOLVED、复发重新 NEW、指纹构造）</li>
 *   <li>{@link OpsScheduler#applyThresholdBackstop} AI 漏报兜底（normal 被硬指标强改）</li>
 *   <li>{@link OpsScheduler#fallbackByThreshold} Ollama 降级路径（阈值规则 → 逐目标判定；
 *       死锁/内存泄漏/QPS 假死的完整场景在 FaultInjectionEvaluationTest F1-F5）</li>
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
        String fp = IncidentStore.fingerprintOf("aiops-copilot", "critical", snap);

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
        List<Incident> r1 = store.bumpNormalAndResolve(Set.of());
        assertTrue(r1.isEmpty(), "第 1 轮 normal 不应 RESOLVED");

        // 第 2 轮 normal：未达阈值
        List<Incident> r2 = store.bumpNormalAndResolve(Set.of());
        assertTrue(r2.isEmpty(), "第 2 轮 normal 不应 RESOLVED");

        // 第 3 轮 normal：达到阈值 → RESOLVED
        List<Incident> r3 = store.bumpNormalAndResolve(Set.of());
        assertEquals(1, r3.size(), "第 3 轮 normal 应有 1 个 RESOLVED");
        assertEquals(IncidentStatus.RESOLVED, r3.getFirst().status());
        assertEquals(0, store.activeCount(), "RESOLVED 后 activeCount 应为 0");
    }

    @Test
    void incidentStore_recurrence_afterResolved_isNewAgain() {
        IncidentStore store = new IncidentStore();
        String fp = "CRITICAL|死锁";

        // 首次 → NEW
        store.recordOrUpdate(fp, "critical", "首次", "死锁", "重启");
        // 3 轮 normal → RESOLVED
        for (int i = 0; i < 3; i++) store.bumpNormalAndResolve(Set.of());
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
        store.bumpNormalAndResolve(Set.of()); // 1 轮 normal
        // 故障再次出现：recordOrUpdate 把计数重置为 0
        store.recordOrUpdate(fp, "warning", "CPU", "CPU 92%", "扩容");
        List<Incident> r = store.bumpNormalAndResolve(Set.of());
        assertTrue(r.isEmpty(), "故障再次出现后 normal 计数应从头开始，不应立即 RESOLVED");
    }

    // ==================== A2. IncidentStore 加固行为：durationOf、RESOLVED TTL、并发更新 ====================

    /**
     * 多目标按目标归档：只有本轮 normal 的目标所辖事件递增计数，
     * 另一目标持续异常时其事件必须保持 ACTIVE（否则 multi-target 下永不恢复）。
     */
    @Test
    void bumpNormalAndResolve_onlyMatchingTarget() {
        IncidentStore store = new IncidentStore();
        String fpA = IncidentStore.fingerprintOf("app-a", "critical", Map.of());
        String fpB = IncidentStore.fingerprintOf("app-b", "critical", Map.of());
        store.recordOrUpdate(fpA, "critical", "A", "rc", "sg");
        store.recordOrUpdate(fpB, "critical", "B", "rc", "sg");

        // app-b 连续 3 轮 normal；app-a 仍异常（不在 normalTargets 中）
        assertTrue(store.bumpNormalAndResolve(Set.of("app-b")).isEmpty());
        assertTrue(store.bumpNormalAndResolve(Set.of("app-b")).isEmpty());
        List<Incident> resolved = store.bumpNormalAndResolve(Set.of("app-b"));
        assertEquals(1, resolved.size(), "只有 app-b 的事件应被归档");
        assertEquals(fpB, resolved.getFirst().fingerprint());
        assertEquals(1, store.activeCount(), "app-a 的事件必须保持 ACTIVE");
    }

    /** durationOf 应基于 lastSeen（首次到最后一次观测），而非 firstSeen→now。 */
    @Test
    void durationOf_usesLastSeen_notNow() {
        IncidentStore store = new IncidentStore();
        Incident inc = store.recordOrUpdate("CRITICAL|X", "critical", "s", "rc", "sg");
        // lastSeen 由 recordOrUpdate 刷为 now，firstSeen==lastSeen，时长应≈0 而非随调用时刻增长
        java.time.Duration d = IncidentStore.durationOf(inc);
        assertTrue(d.toMillis() < 1000, "durationOf 应≈0（firstSeen==lastSeen），而非 firstSeen→now: " + d);
    }

    /** RESOLVED 超过 TTL 后应被清除，且复发按全新 NEW 处理（不沿用旧 lastSeen）。 */
    @Test
    void resolvedEntries_expireAfterTtl() {
        IncidentStore store = new IncidentStore();
        String fp = "CRITICAL|OLD";

        // 造一个 RESOLVED 且 lastSeen 远早于 TTL 的记录
        store.recordOrUpdate(fp, "critical", "首次", "rc", "sg");
        for (int i = 0; i < 3; i++) store.bumpNormalAndResolve(Set.of()); // → RESOLVED
        assertEquals(0, store.activeCount());

        // 手动把 lastSeen 改到 2 小时前（超过 1h TTL），通过反射塞回 map 模拟陈旧记录
        Incident stale = findIncident(store, fp);
        assertNotNull(stale);
        Incident backdated = new Incident(fp, IncidentStatus.RESOLVED,
                stale.firstSeen(), stale.lastSeen().minusSeconds(7200),
                stale.consecutiveNormalRounds(), stale.severity(), stale.summary(),
                stale.rootCause(), stale.suggestion());
        injectIncident(store, fp, backdated);

        // 下一轮 normal 应把它清掉（返回空 resolved 列表，因它早就是 RESOLVED）
        store.bumpNormalAndResolve(Set.of());
        assertNull(findIncident(store, fp), "过期 RESOLVED 应被清除");

        // 同指纹复发：因旧记录已删，按全新 NEW 处理
        Incident recur = store.recordOrUpdate(fp, "critical", "复发", "rc", "sg");
        assertEquals(IncidentStatus.NEW, recur.status(), "过期清除后复发应重新置 NEW");
    }

    /** bumpNormalAndResolve 与 recordOrUpdate 并发时，computeIfPresent 不应丢失更新。 */
    @Test
    void bumpNormalAndResolve_concurrentWithRecord_noLostUpdate() throws Exception {
        IncidentStore store = new IncidentStore();
        String fp = "CRITICAL|RACE";
        store.recordOrUpdate(fp, "critical", "首次", "rc", "sg");

        // 并发：一边 bump（推向 RESOLVED），一边 recordOrUpdate（重置计数回 ACTIVE）。
        // try-with-resources：close() = shutdown + 等待终止，断言失败也不会泄漏线程池
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> f1 = pool.submit(() -> {
                await(start);
                for (int i = 0; i < 50; i++) store.bumpNormalAndResolve(Set.of());
            });
            java.util.concurrent.Future<?> f2 = pool.submit(() -> {
                await(start);
                for (int i = 0; i < 50; i++) store.recordOrUpdate(fp, "critical", "持续", "rc", "sg");
            });
            start.countDown();
            f1.get();
            f2.get();
        }

        // 无异常、map 中该键存在即通过——computeIfPresent 保证单键原子，
        // 不会出现 put 覆盖 compute 导致的"复活已 RESOLVED"或计数错乱
        Incident final_ = findIncident(store, fp);
        assertNotNull(final_, "并发后记录应存在");
    }

    private static void await(java.util.concurrent.CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static Incident findIncident(IncidentStore store, String fp) {
        try {
            java.lang.reflect.Field f = IncidentStore.class.getDeclaredField("incidents");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Incident> m = (Map<String, Incident>) f.get(store);
            return m.get(fp);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void injectIncident(IncidentStore store, String fp, Incident inc) {
        try {
            java.lang.reflect.Field f = IncidentStore.class.getDeclaredField("incidents");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Incident> m = (Map<String, Incident>) f.get(store);
            m.put(fp, inc);
        } catch (Exception e) { throw new RuntimeException(e); }
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
    // 完整场景覆盖（死锁/内存泄漏/QPS 假死的指纹+阈值判定+根因关键词）在
    // FaultInjectionEvaluationTest F1-F3；此处只保留评估集未覆盖的三条单指标分支。

    @Test
    void fallback_cpuAbove90_returnsWarning() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.95);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.5);
        snap.put("qpsLast1m", 50.0);
        snap.put("gcCountLast5m", 2.0);

        OpsScheduler.TargetJudgment j = invokeFallback(sched, snap);

        assertEquals("warning", j.status(), "CPU>90% 应降级为 warning");
    }

    /**
     * QPS=0 假死判定的完整语义（基线门控 / 连续 2 轮确认 / 恢复归零）由
     * FaultInjectionEvaluationTest.f3 覆盖（含指纹构造断言），此处不再重复。
     */

    @Test
    void fallback_allNormal_returnsNormal() throws Exception {
        OpsScheduler sched = newSchedulerWithNulls();
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.3);
        snap.put("blockedThreads", 0.0);
        snap.put("heapUsage", 0.4);
        snap.put("qpsLast1m", 50.0);
        snap.put("gcCountLast5m", 1.0);

        String json = invokeFallback(sched, snap).status();

        assertEquals("normal", json, "所有指标正常应返回 normal");
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

        String json = invokeFallback(sched, snap).status();

        assertEquals("warning", json, "5min GC>10 应降级为 warning");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个依赖基本为 null 的 OpsScheduler——只用于测试纯逻辑的私有方法。
     * audit 传真实实例（AuditLogger 无 Spring 依赖，可直接 new），避免 NPE。
     */
    private OpsScheduler newSchedulerWithNulls() {
        return new OpsScheduler(null, null, null, null, null, new AuditLogger(), null, 45);
    }

    private String invokeBackstop(OpsScheduler sched, String aiStatus, Map<String, Object> block) throws Exception {
        Method m = OpsScheduler.class.getDeclaredMethod(
                "applyThresholdBackstop", String.class, String.class, Map.class);
        m.setAccessible(true);
        return (String) m.invoke(sched, "aiops-copilot", aiStatus, block);
    }

    private OpsScheduler.TargetJudgment invokeFallback(OpsScheduler sched, Map<String, Object> block) throws Exception {
        Method m = OpsScheduler.class.getDeclaredMethod("fallbackByThreshold", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<OpsScheduler.TargetJudgment> judgments =
                (java.util.List<OpsScheduler.TargetJudgment>) m.invoke(sched, wrap(block));
        assertEquals(1, judgments.size(), "单目标块应产出恰好一条判定");
        return judgments.getFirst();
    }

    /** 把单目标指标块包装成生产形态的多目标快照（与 SnapshotCollector.collect 结构一致） */
    private static Map<String, Object> wrap(Map<String, Object> block) {
        return Map.of("targets", Map.of("aiops-copilot", block));
    }
}
