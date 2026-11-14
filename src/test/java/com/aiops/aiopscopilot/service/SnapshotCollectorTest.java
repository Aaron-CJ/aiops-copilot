package com.aiops.aiopscopilot.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiops.aiopscopilot.config.MonitoredTarget;
import com.aiops.aiopscopilot.config.TargetsProperties;
import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SnapshotCollector 测试：多目标并行采集、死锁二级诊断仅对 self 生效、
 * 单目标失败隔离、配置校验 fail-fast、self 自动兜底在列。
 */
class SnapshotCollectorTest {

    private static final String SELF = "aiops-copilot";

    private TargetsProperties propsOf(MonitoredTarget... targets) {
        TargetsProperties props = new TargetsProperties();
        props.setList(new java.util.ArrayList<>(List.of(targets)));
        return props;
    }

    private Map<String, Object> blockWithBlocked(double blocked) {
        return Map.of("cpuUsage", 0.1, "blockedThreads", blocked);
    }

    /** 配置为空：self 自动兜底在列（监控者自身失效必须可见，不可删除） */
    @Test
    void selfIsAlwaysEnsuredWhenConfigEmpty() {
        SnapshotCollector collector = new SnapshotCollector(
                mock(PrometheusTool.class), mock(SystemHealthTools.class),
                new TargetsProperties(), SELF);
        assertEquals(1, collector.targets().size());
        assertEquals(SELF, collector.targets().getFirst().name());
        assertEquals(SELF, collector.targets().getFirst().application());
    }

    /** 用户已配置 self（application 匹配）时不重复补位 */
    @Test
    void selfNotDuplicatedWhenConfigured() {
        SnapshotCollector collector = new SnapshotCollector(
                mock(PrometheusTool.class), mock(SystemHealthTools.class),
                propsOf(new MonitoredTarget("本人", SELF, null)), SELF);
        assertEquals(1, collector.targets().size());
        assertEquals("本人", collector.targets().getFirst().name());
    }

    /** self 的 BLOCKED>0：挂 ThreadMXBean 二级诊断结果 */
    @Test
    void selfDeadlockDiagnosisAttachedWhenBlockedPositive() {        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        SnapshotCollector collector = new SnapshotCollector(
                prometheusTool, systemHealthTools, new TargetsProperties(), SELF);

        MonitoredTarget self = collector.targets().getFirst();
        when(prometheusTool.querySnapshot(self)).thenReturn(blockWithBlocked(2.0));
        Map<String, Object> diagnosis = Map.of("deadlockDetected", true, "deadlockedThreadCount", 2);
        when(systemHealthTools.detectDeadlock()).thenReturn(diagnosis);

        Map<String, Object> result = collector.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) ((Map<?, ?>) result.get("targets")).get(SELF);
        assertEquals(diagnosis, block.get("deadlockDiagnosis"), "self 的 BLOCKED>0 应挂死锁诊断");
        verify(systemHealthTools, times(1)).detectDeadlock();
    }

    /** self 的 BLOCKED=0：不触发死锁诊断 */
    @Test
    void noDeadlockDiagnosisWhenNoBlockedThreads() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        SnapshotCollector collector = new SnapshotCollector(
                prometheusTool, systemHealthTools, new TargetsProperties(), SELF);

        when(prometheusTool.querySnapshot(any(MonitoredTarget.class))).thenReturn(blockWithBlocked(0.0));

        Map<String, Object> result = collector.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) ((Map<?, ?>) result.get("targets")).get(SELF);
        assertEquals(0.0, block.get("blockedThreads"));
        assertFalse(block.containsKey("deadlockDiagnosis"));
        verify(systemHealthTools, never()).detectDeadlock();
    }

    /**
     * （2026-11-13）：pool.submit 的 Future 未检查——collectOne 已捕获 Exception，
     * 但 Error 级（如 OOM）会逃逸到 lambda，此前异常被吞、目标块变 null、normalLine 打出
     * "null"。修复后降级为 error 块，下游按 -1 哨兵与"不可信数据"处理。
     */
    @Test
    void errorFromCollectorYieldsErrorBlockInsteadOfNull() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        when(prometheusTool.querySnapshot(any(MonitoredTarget.class)))
                .thenThrow(new OutOfMemoryError("模拟堆溢出"));
        SnapshotCollector collector = new SnapshotCollector(
                prometheusTool, mock(SystemHealthTools.class), new TargetsProperties(), SELF);

        Map<String, Object> result = collector.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) ((Map<?, ?>) result.get("targets")).get(SELF);
        assertNotNull(block, "Error 级采集失败也不得留下 null 目标块");
        assertTrue(block.containsKey("error"), "应降级为 error 块");
    }

    /** 多目标：业务目标的 BLOCKED>0 也**不**触发 ThreadMXBean（硬边界：只能看本 JVM），
     *  两个目标都进快照且顺序与配置一致（self 自动兜底在首位） */
    @Test
    void multiTargetCollectedButDeadlockDiagnosisGatedToSelf() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        SnapshotCollector collector = new SnapshotCollector(
                prometheusTool, systemHealthTools,
                propsOf(new MonitoredTarget("app-2", "app-2", null)), SELF);
        assertEquals(2, collector.targets().size(), "self 应自动兜底在首位");

        when(prometheusTool.querySnapshot(argThat(t -> t != null && SELF.equals(t.application()))))
                .thenReturn(blockWithBlocked(0.0));
        when(prometheusTool.querySnapshot(argThat(t -> t != null && "app-2".equals(t.application()))))
                .thenReturn(blockWithBlocked(3.0));
        when(systemHealthTools.detectDeadlock()).thenReturn(Map.of("deadlockDetected", false));

        Map<String, Object> result = collector.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) result.get("targets");
        assertEquals(2, targets.size());
        assertTrue(targets.containsKey(SELF) && targets.containsKey("app-2"), "两个目标都应进快照");
        @SuppressWarnings("unchecked")
        Map<String, Object> app2 = (Map<String, Object>) targets.get("app-2");
        assertFalse(app2.containsKey("deadlockDiagnosis"),
                "业务目标的 BLOCKED>0 不得触发本 JVM 的死锁诊断（ThreadMXBean 硬边界）");
        verify(systemHealthTools, never()).detectDeadlock();
    }

    /** 单目标采集失败被隔离：失败块记 error，其余目标不受影响 */
    @Test
    void singleTargetFailureIsIsolated() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        SnapshotCollector collector = new SnapshotCollector(
                prometheusTool, systemHealthTools,
                propsOf(new MonitoredTarget("app-2", "app-2", null)), SELF);

        when(prometheusTool.querySnapshot(argThat(t -> t != null && SELF.equals(t.application()))))
                .thenReturn(blockWithBlocked(0.0));
        when(prometheusTool.querySnapshot(argThat(t -> t != null && "app-2".equals(t.application()))))
                .thenThrow(new RuntimeException("prometheus down"));

        Map<String, Object> result = collector.collect();
        @SuppressWarnings("unchecked")
        Map<String, Object> targets = (Map<String, Object>) result.get("targets");
        assertTrue(targets.get(SELF) instanceof Map<?, ?> self && self.containsKey("cpuUsage"),
                "self 应正常采集");
        @SuppressWarnings("unchecked")
        Map<String, Object> app2 = (Map<String, Object>) targets.get("app-2");
        assertTrue(app2.containsKey("error"), "失败目标应记 error 块: " + app2);
    }

    /** 配置里出现未知语义键：装配期 fail-fast（防配置笔误静默失效） */
    @Test
    void unknownSemanticKeyFailsFast() {
        TargetsProperties props = propsOf(new MonitoredTarget("app-2", "app-2",
                Map.of("cpuUsag", "process_cpu_usage{application=\"app-2\"}")));
        assertThrows(IllegalStateException.class,
                () -> new SnapshotCollector(mock(PrometheusTool.class), mock(SystemHealthTools.class), props, SELF),
                "未知语义键应在装配期拒绝，而不是运行期静默失效");
    }
}
