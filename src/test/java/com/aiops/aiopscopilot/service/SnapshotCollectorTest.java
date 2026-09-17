package com.aiops.aiopscopilot.service;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aiops.aiopscopilot.tool.PrometheusTool;
import com.aiops.aiopscopilot.tool.SystemHealthTools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SnapshotCollector 分支测试：BLOCKED=0 不触发死锁诊断；BLOCKED&gt;0 挂 ThreadMXBean 诊断结果。
 */
class SnapshotCollectorTest {

    private Map<String, Object> snapshotWithBlocked(double blocked) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("cpuUsage", 0.1);
        snap.put("blockedThreads", blocked);
        return snap;
    }

    @Test
    void noDeadlockDiagnosisWhenNoBlockedThreads() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        when(prometheusTool.queryFixedMetrics()).thenReturn(snapshotWithBlocked(0.0));

        SnapshotCollector collector = new SnapshotCollector(prometheusTool, systemHealthTools);
        Map<String, Object> result = collector.collect();

        assertEquals(0.0, result.get("blockedThreads"));
        assertFalse(result.containsKey("deadlockDiagnosis"));
        verify(systemHealthTools, never()).detectDeadlock();
    }

    @Test
    void attachesDeadlockDiagnosisWhenBlockedThreadsExist() {
        PrometheusTool prometheusTool = mock(PrometheusTool.class);
        SystemHealthTools systemHealthTools = mock(SystemHealthTools.class);
        when(prometheusTool.queryFixedMetrics()).thenReturn(snapshotWithBlocked(2.0));
        Map<String, Object> diagnosis = Map.of("deadlockDetected", true, "deadlockedThreads", 2);
        when(systemHealthTools.detectDeadlock()).thenReturn(diagnosis);

        SnapshotCollector collector = new SnapshotCollector(prometheusTool, systemHealthTools);
        Map<String, Object> result = collector.collect();

        assertSame(diagnosis, result.get("deadlockDiagnosis"));
        verify(systemHealthTools, times(1)).detectDeadlock();
    }
}
