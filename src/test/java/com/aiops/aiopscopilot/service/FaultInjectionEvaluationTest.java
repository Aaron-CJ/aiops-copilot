package com.aiops.aiopscopilot.service;

import com.aiops.aiopscopilot.common.audit.AuditLogger;
import com.aiops.aiopscopilot.service.IncidentStore.Incident;
import com.aiops.aiopscopilot.service.IncidentStore.IncidentStatus;
import com.aiops.aiopscopilot.service.OpsScheduler.TargetJudgment;
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
 * 多目标：兜底按目标评估，测试快照经 wrap() 包装成
 * {targets:{aiops-copilot:块}} 形态——与生产快照结构一致。
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

        assertEquals("CRITICAL|APP=aiops-copilot;DEADLOCK;BLOCKED=2;",
                IncidentStore.fingerprintOf("aiops-copilot", "critical", snap),
                "死锁快照应产出 APP+DEADLOCK+BLOCKED 特征指纹");

        // QPS 归零只是死锁的伴随症状：若把它也编进指纹，同一死锁会在"有流量/无流量"两轮之间
        // 来回切换身份，被拆成两条并发事件，心跳与升级计时全部分裂
        snap.put("qpsLast1m", 0.0);
        assertEquals("CRITICAL|APP=aiops-copilot;DEADLOCK;BLOCKED=2;",
                IncidentStore.fingerprintOf("aiops-copilot", "critical", snap),
                "已有更具体信号时，ZERO_QPS 不应改变同一死锁的指纹");

        TargetJudgment j = invokeFallback(snap);
        assertEquals("critical", j.status(), "BLOCKED>0 兜底应判 critical");
        assertTrue(j.rootCause().contains("死锁"), "根因应含死锁关键词: " + j.rootCause());
    }

    // ==================== F2 内存泄漏：堆超限 + GC 频繁 ====================

    @Test
    void f2_memoryLeak_fingerprintAndFallback() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("heapUsage", 0.96);
        snap.put("gcCountLast5m", 15.0);

        assertEquals("CRITICAL|APP=aiops-copilot;HIGH_HEAP=9;FREQUENT_GC;",
                IncidentStore.fingerprintOf("aiops-copilot", "critical", snap),
                "内存泄漏快照应产出 HIGH_HEAP+FREQUENT_GC 特征指纹");

        TargetJudgment j = invokeFallback(snap);
        assertEquals("critical", j.status(), "堆>0.95 兜底应判 critical");
        assertTrue(j.rootCause().contains("堆内存"), "根因应含堆内存关键词: " + j.rootCause());
        assertTrue(j.rootCause().contains("内存泄漏"), "GC 频繁应指向内存泄漏: " + j.rootCause());
    }

    // ==================== F3 应用假死：QPS=0 但指标可拉取（连续 2 轮确认） ====================

    /**
     * QPS=0 假死判定带连续确认（ZERO_QPS_CONFIRM_ROUNDS=2）：空闲/低流量系统单轮 QPS=0
     * 是常态，第 1 轮只判 warning"待确认"，同一实例连续第 2 轮仍 QPS=0 才升级 critical。
     * 用同一个 OpsScheduler 实例连调两次验证计数器语义。
     * <p>
     * 另做基线门控：从未观测到流量的实例 QPS 恒为 0，此时不能判假死——否则空闲环境
     * 会每分钟误报 critical（实测空闲演示环境即出现过该误报）。
     */
    @Test
    void f3_appStall_zeroQps_fingerprintAndFallback() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("qpsLast1m", 0.0);

        assertEquals("CRITICAL|APP=aiops-copilot;ZERO_QPS;",
                IncidentStore.fingerprintOf("aiops-copilot", "critical", snap),
                "QPS=0 应产出 ZERO_QPS 特征指纹");

        OpsScheduler sched = newScheduler();
        TargetJudgment idleFromStart = invokeFallback(sched, snap);
        assertEquals("normal", idleFromStart.status(),
                "从未观测到流量的空闲实例 QPS=0 是常态，不得判假死: " + idleFromStart);

        // 先观测一轮真实流量建立基线，后续 QPS=0 才是"从有流量跌到零"的假死信号
        invokeFallback(sched, baseSnapshot());

        TargetJudgment firstRound = invokeFallback(sched, snap);
        assertEquals("warning", firstRound.status(),
                "第 1 轮 QPS=0 应判 warning 待确认（空闲低流量单轮 QPS=0 是常态）: " + firstRound);
        assertTrue(firstRound.rootCause().contains("待确认"),
                "第 1 轮根因应含待确认说明: " + firstRound.rootCause());

        TargetJudgment secondRound = invokeFallback(sched, snap);
        assertEquals("critical", secondRound.status(),
                "连续第 2 轮 QPS=0 应升级 critical 假死: " + secondRound);
        assertTrue(secondRound.rootCause().contains("假死"),
                "根因应含假死关键词: " + secondRound.rootCause());

        // QPS 恢复后计数必须归零：下一轮 QPS=0 重新从 warning 起步
        invokeFallback(sched, baseSnapshot());
        TargetJudgment reconfirm = invokeFallback(sched, snap);
        assertEquals("warning", reconfirm.status(),
                "QPS 恢复一轮后计数应归零，再次 QPS=0 从 warning 重新确认: " + reconfirm);
    }

    // ==================== F4 慢接口：纯延迟无硬阈值（设计边界） ====================

    @Test
    void f4_slowRequest_latencyOnly_fallbackStaysNormal() throws Exception {
        Map<String, Object> snap = baseSnapshot();
        snap.put("maxRequestSeconds", 30.0);

        TargetJudgment j = invokeFallback(snap);
        assertEquals("normal", j.status(),
                "纯延迟不应触发硬阈值兜底——慢接口是 LLM 语义层用例: " + j);

        // 同时记录当前指纹行为：纯延迟无指标特征，指纹退化为 APP 前缀 + 裸级别，
        // 同类 warning 会被合并为同一事件（LLM 层根因文本不参与指纹，设计如此）
        assertEquals("WARNING|APP=aiops-copilot;", IncidentStore.fingerprintOf("aiops-copilot", "warning", snap));
    }

    // ==================== F5 瞬时尖峰：单轮捕获后自动 RESOLVED，不产生持续告警 ====================

    @Test
    void f5_transientCpuSpike_capturedThenAutoResolved() {
        Map<String, Object> spikeSnap = baseSnapshot();
        spikeSnap.put("cpuUsage", 0.92);
        assertEquals("WARNING|APP=aiops-copilot;HIGH_CPU=9;",
                IncidentStore.fingerprintOf("aiops-copilot", "warning", spikeSnap),
                "CPU 0.92 应产出分桶后的 HIGH_CPU=9 特征指纹");

        IncidentStore store = new IncidentStore();
        Incident first = store.recordOrUpdate(
                IncidentStore.fingerprintOf("aiops-copilot", "warning", spikeSnap),
                "warning", "CPU 尖峰", "CPU 92%", "观察");
        assertTrue(first.isNew(), "被单轮巡检捕获的尖峰应为 NEW");

        assertTrue(store.bumpNormalAndResolve().isEmpty(), "毛刺消失后第 1 轮 normal 不应 RESOLVED");
        assertTrue(store.bumpNormalAndResolve().isEmpty(), "第 2 轮 normal 不应 RESOLVED");
        List<Incident> resolved = store.bumpNormalAndResolve();
        assertEquals(1, resolved.size(), "第 3 轮 normal 应 RESOLVED——尖峰不升级为持续告警");
        assertEquals(IncidentStatus.RESOLVED, resolved.getFirst().status());
        assertEquals(0, store.activeCount(), "RESOLVED 后不应残留活跃事件");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造"全部正常"形态的指标快照基底（各用例按需覆盖异常字段）。
     * 键集合与 PrometheusTool.querySnapshot 对齐。
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

    /** 把单目标指标块包装成生产形态的多目标快照（与 SnapshotCollector.collect 输出结构一致） */
    private static Map<String, Object> wrap(Map<String, Object> block) {
        return Map.of("targets", Map.of("aiops-copilot", block));
    }

    /** 与 IncidentStoreTest 相同的手法：依赖为 null 的 OpsScheduler 只测纯逻辑私有方法。 */
    private OpsScheduler newScheduler() {
        return new OpsScheduler(null, null, null, null, null, new AuditLogger(), null, 45);
    }

    private TargetJudgment invokeFallback(Map<String, Object> block) throws Exception {
        return invokeFallback(newScheduler(), block);
    }

    private TargetJudgment invokeFallback(OpsScheduler sched, Map<String, Object> block) throws Exception {
        Method m = OpsScheduler.class.getDeclaredMethod("fallbackByThreshold", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TargetJudgment> judgments = (List<TargetJudgment>) m.invoke(sched, wrap(block));
        assertEquals(1, judgments.size(), "单目标快照应产出恰好一条判定");
        return judgments.getFirst();
    }
}
